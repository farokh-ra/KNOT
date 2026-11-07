# Architecture

Single-module Android app. Everything runs on the device; there is no server component in
this repository and no hosted service operated for this app.

## Layers

| Layer | Files | Responsibility |
|---|---|---|
| UI | `ui/HomeActivity`, `ui/PairingActivity`, `ui/ChatActivity`, `ui/ChatAdapter`, `ui/Qr` | Three screens, state rendering, QR generation and scanning |
| Service | `service/ConnectionService` | Foreground service that owns the engine lifetime so the link survives backgrounding and screen-off |
| Engine | `engine/ConnectionEngine` | Connection state machine, discovery loop, outbox, acknowledgements, network-change handling, instant reconnect |
| Protocol | `protocol/Protocol`, `protocol/Chunking` | Versioned message schema, strict validation, chunking and bounded reassembly |
| Transport | `net/UdpTransport`, `net/PeerLink`, `net/SecureChannel`, `net/Handshake`, `net/Wire` | One UDP socket, authenticated handshake, encrypted reliable frames |
| Discovery | `net/dht/DhtClient`, `net/dht/Bencode`, `net/LanBeacon` | DHT rendezvous on a derived topic; same-subnet beacon |
| Storage | `data/Db`, `data/MessageRepository`, `data/SecureStore` | SQLite history, pinned peer identity, Keystore-wrapped secrets |
| Crypto | `crypto/X25519`, `crypto/Crypto`, `crypto/Invite` | X25519 (RFC 7748), HKDF-SHA256, HMAC, AES-256-GCM, invitation/topic derivation |

## Why one UDP socket

`UdpTransport` binds a single ephemeral UDP socket and shares it between the DHT client and
the peer link. The NAT mapping that the other device learns from the DHT is the mapping of
that socket's source port, so chat packets must leave from the same socket for hole
punching to succeed. A peer packet is recognised by the `TPM\x01` magic; a KRPC packet by
its leading `d` (bencoded dictionary).

## Connection state machine

`ConnectionEngine` exposes `StateFlow<ConnectionStatus>`:

```
NOT_PAIRED ──(invitation joined/created)──▶ DISCOVERING
DISCOVERING ──candidate endpoint──▶ CONNECTING ──handshake ok──▶ CONNECTED
CONNECTING ──handshake failed──▶ next candidate (stays CONNECTING/DISCOVERING)
DISCOVERING for > 40 s without a connection ──▶ UNABLE_TO_CONNECT (keeps retrying)
CONNECTED ──link closed──▶ DISCONNECTED ──backoff──▶ DISCOVERING
```

- Discovery cycles run every 4 s: DHT `get_peers`/`announce_peer` plus a LAN beacon every 3 s.
  One `DhtClient` is reused for the lifetime of the socket, so reconnects do not repeat the
  bootstrap round trip.
- Candidates are attempted one at a time with a 12 s handshake budget. The endpoint that last
  carried authenticated traffic is tried first with a 4 s budget, which is what makes a
  reconnect take well under a second instead of waiting for a lookup.
- Reconnection uses bounded exponential backoff (1.5 s → 30 s) with jitter.
- Only the **default** network is watched (`registerDefaultNetworkCallback`). Wi-Fi and mobile
  data coexisting is not an event, and a connected link is never torn down by a network
  change: the socket re-routes and the peer endpoint follows the authenticated packets.
- `ConnectionService` is a `dataSync` foreground service started whenever the app is paired.
  It holds the engine open with the screen off or the app backgrounded, and its notification
  mirrors the real state. Without it, Android would suspend the process and the link would
  drop; there is still no background delivery while the device is powered off or the app is
  force-stopped.

## Message flow

Outgoing:

1. Validate text (non-empty, ≤ 2000 code points).
2. Allocate a random 16-byte message ID and a persistent per-device sequence number.
3. Insert into SQLite as `PENDING`.
4. If a link is ready, split the encoded message into ≤ 480-byte chunks and hand them to the
   reliable layer.
5. When every chunk of that message has been acknowledged by the transport layer, mark
   `SENT`. When the peer's application-level `ACK` arrives, mark `DELIVERED`.
6. If the peer stops acknowledging (`delivery_failed`), mark remaining queued messages
   `FAILED` — never delete them. The next connection resets them to `PENDING` and resends
   with the same IDs.

Incoming:

1. Reassemble chunks, decode and validate the schema (rejects unknown types, bad UTF-8,
   oversized bodies, absurd timestamps).
2. Insert into SQLite, ignoring duplicates by primary key on the message ID.
3. Only after the row is stored, send the application-level `ACK`.
4. A duplicate resend is acknowledged again but not stored twice.

Display order is deterministic: `created_at`, then `sender_id`, then message `id`.

## Reliability layer

Inside `PeerLink` every application message is chunked, each chunk becomes one encrypted
frame carrying its own reliable sequence number, and frames are:

- acknowledged cumulatively plus a 32-bit selective bitmap,
- retransmitted with exponential backoff (400 ms → 5 s, 14 attempts),
- delivered strictly in order (out-of-order frames are buffered up to 96),
- kept alive by a 3 s heartbeat and closed only after 30 s without an authenticated frame,
- independent of ACK and heartbeat traffic, which never occupy the data sequence space.

The transport acknowledgement means "the peer's stack received these bytes", not "the user
received the message". Only the application-level ACK, sent after persistence, means that.

## Storage

SQLite (`twoperson.db`, version 1):

- `messages(id TEXT PRIMARY KEY, direction, body, created_at, seq, state, sender_id)`
- `peer(id = 1, device_id, public_key, label, display_name, paired_at)` — the pinned identity
  and the local-only label
- `meta(key, value)` — the persistent outgoing sequence counter

The invitation secret and the X25519 identity private key are wrapped with an AES-256-GCM
key held in the Android Keystore and stored in private SharedPreferences. The database
itself is **not** encrypted at rest; it lives in the app sandbox.
