# Security notes and checklist

## Threat model

Two devices that already share an invitation secret want to exchange messages and files
without any server reading or storing them. The app assumes:

- the invitation code is exchanged out of band and only with the intended person;
- the network is hostile (packets can be observed, injected, replayed, dropped);
- public DHT nodes, UDP trackers and STUN servers are untrusted infrastructure, useful only
  for rendezvous;
- neither phone's operating system is compromised, and the app sandbox holds.

## What the design provides

- **Confidentiality and integrity of chat and file traffic**: AES-256-GCM frames over a direct
  path, keys derived from an ephemeral X25519 agreement, per-direction counters as nonces.
  File offers, blocks, offsets and acknowledgements are inside the same authenticated channel.
- **Forward secrecy per session**: the handshake uses fresh ephemeral keys each time; the
  long-term identity key only signs its way into the transcript, never encrypts messages.
- **Proof of invitation possession**: the initiation MAC is keyed by the invitation secret;
  an unrelated device that guesses the topic cannot complete a handshake.
- **No relay**: there is no code path that forwards chat or file bytes through a third party.
  When a direct path fails, the UI says so and transfer pauses.
- **Bounded file handling**: a 4 TiB protocol cap, 16 KiB blocks, a 64 KiB application-message
  cap, 255-byte UTF-8 names after sanitization, 128-byte MIME types, an 8 MiB flow-control
  window and a free-space check before a part file is created. Names are stripped of control
  characters and path separators, and destinations are built inside app-private directories,
  so a peer cannot choose an arbitrary path. Offsets must be sequential and blocks must not
  overlap the acknowledged prefix.
- **App-private file storage**: received files live in `filesDir/incoming`; cached outgoing
  copies live in `filesDir/outgoing`; part files live in `filesDir/transfers`. Opening or
  sharing uses a `FileProvider` read grant, not a file path.
- **Privacy-aware notifications**: real message alerts use `VISIBILITY_PRIVATE` with a
  redacted public version and can hide all names and content through the preview switch. The
  optional foreground-service notification is on a silent, minimum-importance channel and
  never carries message text.
- **Per-contact identities**: every contact has an independent invitation secret and pinned
  static identity, so pairing one contact cannot authenticate or read another contact's thread.
  The legacy single-peer identity is migrated to one `primary` contact on upgrade.
- **Minimal disclosure to rendezvous services**: DHT nodes and UDP trackers learn only a
  derived 20-byte topic plus the observed IP/port; STUN servers only reflect the socket's
  public endpoint. The invitation secret is never sent to any of them.

## Honest gaps

- The handshake is a custom Noise-XX-style construction (X25519 + HMAC + HKDF + confirmation
  MAC). It is covered by unit tests and the RFC 7748 vectors, but it has **not** been
  reviewed by an external cryptographer and is not the Noise protocol implementation itself.
- Pairing is trust-on-first-use: the invitation authenticates the pair, and the peer's static
  identity is pinned afterwards. If someone obtains the invitation before the intended person
  pairs, they can be the first peer.
- Invitations do not expire. Replacing the invitation or using *Unpair device* is manual.
- Message history, transfer state, received files and cached outgoing copies are **not**
  encrypted at rest. They sit in plain SQLite and app-private storage inside the sandbox; the
  identity key and invitation secret are wrapped, but message text and file bytes are not.
- Received files are not malware-scanned. Opening a file is the user’s decision and depends on
  the viewer app; the transport guarantees integrity, not safety of the content.
- Outgoing files can remain referenced by a persisted SAF URI. If the provider revokes the
  grant or the app is reinstalled, the message reports the file as unavailable. Providers that
  cannot report a size are copied into app storage first; that copy is protected only by the
  app sandbox and is deleted when the conversation is cleared.
- A notification listener or someone using an unlocked device may see sender names and message
  previews; the public lockscreen version is redacted but the private version is not.
- No protection against a compromised device, screenshot malware, or a rooted phone.
- Metadata: while the engine is running, DHT nodes, UDP trackers, STUN servers and the active
  peer learn your public IP and UDP port. That is inherent to serverless rendezvous.

## Checklist

| Item | Status |
|---|---|
| Invitation secret from a cryptographically secure RNG | Done — `SecureRandom`, 32 bytes |
| Raw invitation secret never published to rendezvous services | Done — only the HMAC-derived 20-byte topic reaches DHT nodes and trackers; STUN sees only the endpoint |
| Peer proves possession of the invitation secret | Done — initiation MAC, verified in `HandshakeTest`; incoming initiations are MAC-checked before any state is created |
| Tracker/STUN replies cannot spoof another endpoint | Done — transaction id and source address must match; STUN replies also require the full 12-byte transaction |
| Remote peer identity authenticated | Done — identity key inside the MAC'd transcript; pinned per contact after pairing |
| Contacts isolated from each other | Done — independent invitation secret, pinned identity, outbox and history per contact; only the selected contact is connected |
| Encrypted channel retained and verified | Done — AES-256-GCM, replay window, tamper tests |
| No relay / TURN / message-forwarding fallback | Done — none exists in the code base |
| Rendezvous services receive no message text | Done — DHT carries KRPC only; trackers see the topic hash and endpoint; STUN sees a binding request |
| Message parser validates type, length, encoding | Done — `AppCodecTest` covers text and file schemas |
| Duplicate messages ignored | Done — chunk reassembly plus `messages.id` primary key |
| ACK sent only after local persistence | Done — repository insert precedes the ACK send |
| File size, block length, offset and MIME bounded | Done — decoder rejects out-of-range values; UI rejects known-oversize files |
| File names cannot escape the destination directory | Done — control characters, `/`, `\` and dot-only names sanitized; destination built in `filesDir/incoming` |
| Received file acknowledged only after durable completion | Done — part file flushed, exact length and FNV-1a digest verified, then moved before `FileDone(success)` |
| Resume trusts bytes actually on disk | Done — part file length is re-read after I/O errors and its prefix digest is re-computed if the persisted value is unavailable |
| File data cannot starve chat | Done — text outbox checked every 250 ms; file sender pauses while text waits |
| File bytes stay on the authenticated peer path | Done — no relay or upload endpoint exists; all blocks use `PeerLink` |
| Clearing history removes app-private file copies | Done — active sessions cancelled, part files deleted, row paths removed; SAF originals untouched |
| Notifications only for real incoming content | Done — `AppVisibility` gate; per-conversation IDs; optional service channel is silent and separate |
| Local data excluded from backup and device transfer | Done — `allowBackup=false` plus `data_extraction_rules.xml` excludes every domain |
| Notification permission handled without crashing | Done — requested only from the explicit enable button, never on launch; checks before every post; `SecurityException` caught |
| Private keys never in logs or plain preferences | Done — Keystore-wrapped; no secret is logged |
| No message content to analytics or crash reporting | Done — no analytics or crash SDK is included |
| Network failure does not delete unsent messages | Done — queued/failed rows persist and are retried |
| App displays the true connection state | Done — explicit states, including "unable to connect directly" |
| Release APK signed and tested on two physical devices | **Signed APK built and verified here; two-device testing is outstanding** (no devices attached to this machine) |

See `LIMITATIONS.md` for the operational consequences.
