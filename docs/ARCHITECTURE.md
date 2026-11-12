# Architecture

Single-module Android app. Everything runs on the device; there is no server component in
this repository and no hosted service operated for this app.

## Layers

| Layer | Files | Responsibility |
|---|---|---|
| UI | `ui/HomeActivity`, `ui/ChatsFragment`, `ui/ContactsFragment`, `ui/SettingsFragment`, `ui/ContactDetailActivity`, `ui/PairingActivity`, `ui/ChatActivity`, `ui/AboutActivity`, `ui/ChatAdapter`, `ui/UiTime`, `ui/Qr` | Tabbed shell (Chats, Contacts, Settings) plus contact detail, pairing, chat and About screens; contact search, chat previews, date separators, file bubbles, unread badges, send-only auto-scroll, QR generation and scanning |
| Service | `service/ConnectionService`, `service/BootReceiver`, `service/MessageNotifications` | Always-on `dataSync` foreground service that holds the engine lifetime and restarts after reboot or app update; silent service channel and a separate per-conversation alerting channel for real incoming messages/files |
| Engine | `engine/ConnectionEngine`, `engine/FileTransferManager` | Contact-scoped connection state machine, discovery loop, serial message worker, per-contact text outbox, streaming file transfer, acknowledgements, network-change handling, instant reconnect |
| Protocol | `protocol/Protocol`, `protocol/Chunking` | Versioned (v4) schema for text and files, strict validation, filename sanitization, chunking and bounded reassembly |
| Transport | `net/UdpTransport`, `net/PeerLink`, `net/SecureChannel`, `net/Handshake`, `net/Wire` | One UDP socket with 4 MB buffers, authenticated handshake, encrypted reliable frames and a 4096-frame window |
| Discovery | `net/rendezvous/TrackerRendezvous`, `net/rendezvous/StunClient`, `net/rendezvous/PublicRendezvous`, `net/dht/DhtClient`, `net/dht/Bencode`, `net/LanBeacon` | BEP 15 tracker and STUN rendezvous on allowed ports; DHT rendezvous on a derived topic; same-subnet beacon |
| Storage | `data/Db`, `data/MessageRepository`, `data/SecureStore`, `data/SettingsStore` | SQLite contacts, history and transfer state, app-private incoming/outgoing files, per-contact pinned identity, local settings, Keystore-wrapped secrets |
| Crypto | `crypto/X25519`, `crypto/Crypto`, `crypto/Invite` | X25519 (RFC 7748), HKDF-SHA256, HMAC, AES-256-GCM, invitation/topic derivation |

## Why one UDP socket

`UdpTransport` binds a single ephemeral UDP socket and shares it between the DHT client, the
tracker/STUN rendezvous client and the peer link. The NAT mapping that the other device learns
from any rendezvous path is the mapping of that socket's source port, so chat packets must
leave from the same socket for hole punching to succeed. Routing is by shape: a peer packet
starts with the `TPM` magic, a KRPC packet with a leading `d` (bencoded dictionary), a STUN
reply with `0x0101`, and a BEP 15 tracker reply with a non-negative action word. Anything
else is dropped.

## Connection state machine

`ConnectionEngine` exposes `StateFlow<ConnectionStatus>`:

```
NOT_PAIRED ──(invitation joined/created)──▶ DISCOVERING
DISCOVERING ──candidate endpoint──▶ CONNECTING ──handshake ok──▶ CONNECTED
CONNECTING ──handshake failed──▶ next candidate (stays CONNECTING/DISCOVERING)
DISCOVERING for > 40 s without a connection ──▶ UNABLE_TO_CONNECT (keeps retrying)
CONNECTED ──link closed──▶ DISCONNECTED ──backoff──▶ DISCOVERING
```

- Discovery runs two loops in parallel. The DHT loop performs `get_peers`/`announce_peer`
  every 4 s; the public-rendezvous loop asks STUN for the socket's translated port and
  announces the derived topic on BEP 15 trackers every 7 s (tracker-requested intervals are
  honoured). A LAN beacon broadcasts every 3 s. One client of each kind is reused for the
  lifetime of the socket, so reconnects do not repeat DNS or bootstrap work.
- Incoming authenticated initiations are accepted even when this side is not dialing: the
  engine builds a responder `PeerLink` from the packet, and promotes it to `activeLink` once
  the handshake completes. This is what makes one-sided discovery (only the peer found a
  candidate) connect.
- Candidates are attempted one at a time with a 6 s handshake budget. The endpoint that last
  carried authenticated traffic is tried first with a 4 s budget, which is what makes a
  reconnect take well under a second instead of waiting for a lookup. Retried candidates are
  deduplicated for 15 s, and the engine filters its own STUN endpoint.
- Reconnection uses bounded exponential backoff (1.5 s → 30 s) with jitter.
- Only the **default** network is watched (`registerDefaultNetworkCallback`). Wi-Fi and mobile
  data coexisting is not an event. Every genuine default-network change is different: the old
  socket and NAT mapping are suspect, so the runtime is torn down and revalidated after a short
  settle delay even if the status still says `CONNECTED`. The callback's initial `onAvailable`
  for the network already in use is ignored.
- One contact is the connection target at a time. `MessageRepository` stores the selected
  contact in `meta`; opening another contact calls `selectContact`, which closes the previous
  runtime, cancels its in-flight file transfers and starts discovery for the new contact's
  invitation. Each contact has its own per-contact outbox sequence and invitation secret.
- `ConnectionService` is an **always-on** `dataSync` foreground service. It starts whenever a
  contact with an invitation is paired and restarts after a reboot or app update through
  `BootReceiver`. It holds the engine open with the screen off or the app backgrounded, and its
  notification mirrors the real state on a silent, minimum-importance channel. A short
  background grace period remains only as a fallback when Android refuses to promote the
  service. Returning to the app after more than five seconds triggers a fresh handshake, and a
  peer that stops sending heartbeats is considered gone after 15 seconds. There is still no
  background delivery while the device is powered off or the app is force-stopped.
- `MessageNotifications` uses a different, high-importance channel only for real incoming
  messages and completed files. `AppVisibility` tracks started activities; the notifier is
  invoked by the engine after persistence and checks that no Knot screen is visible before
  posting. Alerts are per contact (stable notification ID and a `PendingIntent` that opens that
  chat) plus a group summary. The service notification never alerts and never uses the message
  channel.

## Message flow

Outgoing (always scoped to the active contact):

1. Validate text (non-empty, ≤ 2000 code points).
2. Allocate a random 16-byte message ID and a persistent per-contact sequence number.
3. Insert into SQLite as `PENDING` with that contact's ID.
4. If a link is ready, split the encoded message into ≤ 1100-byte chunks and hand them to the
   reliable layer. Text is sent before bulk file data and keeps priority while a file streams.
5. When every chunk of that message has been acknowledged by the transport layer, mark
   `SENT`. When the peer's application-level `ACK` arrives, mark `DELIVERED`.
6. If the peer stops acknowledging (`delivery_failed`), mark remaining queued messages
   `FAILED` — never delete them. The next connection resets them to `PENDING` and resends
   with the same IDs.
7. Delivery states are updated through an explicit rank (`PENDING < SENT < FAILED <
   DELIVERED`) so a late failure callback can never downgrade a message the peer already
   stored.

Incoming:

1. Reassemble chunks, decode and validate the schema (rejects unknown types, bad UTF-8,
   oversized bodies, absurd timestamps).
2. Insert into SQLite, ignoring duplicates by primary key on the message ID.
3. Only after the row is stored, send the application-level `ACK`.
4. A duplicate resend is acknowledged again but not stored twice.

Display order is deterministic: `created_at`, then `sender_id`, then message `id`.

## File transfer

`FileTransferManager` receives application messages from the engine's **single serial message
worker**. That worker is what guarantees 16 KiB blocks are appended in transport order; doing
one coroutine per datagram would reorder them.

- The sender reads the source in 16 KiB blocks, either from a persisted SAF descriptor or an
  app-private copy made when the provider cannot report a size.
- The receiver holds a `.part` file in `filesDir/transfers`, appends only at the expected
  offset, persists progress to SQLite at most every 2 MiB/1 s, and acknowledges at most
  every 512 KiB/50 ms. Acknowledgements describe bytes actually written, so resume never
  skips or duplicates data.
- The receiver keeps a running FNV-1a digest of the bytes it durably holds. The digest is
  persisted beside the offset, so a resumed transfer adopts the prefix hash instead of
  re-reading it. On `FileEnd` the receiver compares the whole-file digest before publishing
  the file; a mismatch discards the part file and asks the sender to retry from zero.
- The sender stays within 8 MiB of the acknowledged offset and retains a block that could not
  be queued in the 4096-frame transport window. It re-reads only after an acknowledged offset,
  never mid-block.
- Text messages outrank file blocks: the file sender checks the text outbox every 250 ms and
  pauses while text is waiting.
- On completion the receiver verifies the exact length, moves the part file to
  `filesDir/incoming` under a collision-free sanitized name, updates the same message row, and
  notifies through `onIncomingFileReady`. A `FileProvider` exposes incoming files and cached
  outgoing copies for open/share intents.
- Clearing the conversation cancels the outgoing job, closes and deletes part files, deletes
  the rows, then deletes app-private paths that belonged to those rows. SAF originals are never
  modified.

## Reliability layer

Inside `PeerLink` every application message is chunked, each chunk becomes one encrypted
frame carrying its own reliable sequence number, and frames are:

- acknowledged cumulatively plus a 4096-bit selective bitmap covering the send window,
- retransmitted with exponential backoff (300 ms → 4 s, 16 attempts),
- delivered strictly in order (out-of-order frames are buffered up to 4096),
- sent through a 4096-frame window (~4.4 MB of chunk data) with 4 MB socket buffers and a
  50 ms tick,
- kept alive by a 3 s heartbeat and closed only after 30 s without an authenticated frame,
- independent of ACK and heartbeat traffic, which never occupy the data sequence space.

The transport acknowledgement means "the peer's stack received these bytes", not "the user
received the message". Only the application-level ACK, or a completed file's `FileDone`,
means that.

## UI state

- `MessageRepository` exposes the contact list, the selected contact, one ordered
  `StateFlow<List<Message>>` for that contact, a chat-summary list and total/per-chat unread
  counts. The Chats tab shows every contact with a pairing, invitation or history; local-only
  contacts stay in the Contacts tab.
- The shell uses three tabs that keep their own fragment state, with badge and title updates
  driven by the repository and engine flows. Back from Contacts or Settings returns to Chats.
- Incoming unread messages are marked read when their chat is visible. Notifications are
  per-conversation and are dismissed for that contact when its chat opens; the Chats tab badge
  is driven by the total unread count.
- File progress updates mutate the already loaded row in place instead of re-querying the
  whole history several times per second.
- Auto-scroll is armed only for a newly inserted **outgoing** message/file and targets the
  expected outgoing count, so incoming traffic never pulls the reader away from older
  messages. A floating down-arrow appears whenever the newest row is off-screen.
- Long-pressing any bubble opens message actions: copy text, retry a failed send, delete, and
  for completed files open/share/save to `Downloads/Knot`. The screen is kept awake only while
  a file transfer is pending.
- Date chips are derived from message timestamps; each bubble uses Material 3 surfaces with a
  tighter corner on the sender side and a ripple only on tappable file bubbles. Active
  transfers show the measured rate next to the percentage.

## Storage

SQLite (`twoperson.db`, version 4):

- `contacts(id TEXT PRIMARY KEY, label, note, device_id, public_key, display_name, paired_at,
  created_at, last_opened_at, has_invitation)` — local address book, per-contact pinned
  identity and invitation state. `label` and `note` never leave the device.
- `messages(id TEXT PRIMARY KEY, contact_id, direction, body, created_at, seq, state, sender_id,
  kind, file_name, mime_type, file_size, file_uri, file_path, transfer_id, received_bytes,
  transfer_digest, is_read)` — text and file rows, progress, read state and transfer identity
- `meta(key, value)` — the active contact ID and per-contact outgoing sequence counters. The
  version-4 migration turns a single-peer installation into the `primary` contact and assigns
  its existing messages to that contact.
- The legacy `peer` table is left in place after migration for safety but is no longer read.

Each contact has its own invitation secret and pinned X25519 identity. Secrets and the local
identity private key are wrapped with an AES-256-GCM key held in the Android Keystore and kept
in private SharedPreferences. Message text, transfer state, received files, cached outgoing
copies and local settings are **not** encrypted at rest; they live in the app sandbox and are
excluded from cloud backup and device-to-device transfer. App-private files are removed when a
conversation is cleared, all data is deleted, app data is cleared, or the app is uninstalled.
