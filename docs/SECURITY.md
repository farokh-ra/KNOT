# Security notes and checklist

## Threat model

Two devices that already share an invitation secret want to talk without any server reading
or storing the conversation. The app assumes:

- the invitation code is exchanged out of band and only with the intended person;
- the network is hostile (packets can be observed, injected, replayed, dropped);
- public DHT nodes, UDP trackers and STUN servers are untrusted infrastructure, useful only
  for rendezvous;
- neither phone's operating system is compromised, and the app sandbox holds.

## What the design provides

- **Confidentiality and integrity of chat traffic**: AES-256-GCM frames over a direct path,
  keys derived from an ephemeral X25519 agreement, per-direction counters as nonces.
- **Forward secrecy per session**: the handshake uses fresh ephemeral keys each time; the
  long-term identity key only signs its way into the transcript, never encrypts messages.
- **Proof of invitation possession**: the initiation MAC is keyed by the invitation secret;
  an unrelated device that guesses the topic cannot complete a handshake.
- **No relay**: there is no code path that forwards chat through a third party. When a direct
  path fails, the UI says so.
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
- Invitations do not expire. Replacing the invitation or using *Forget partner* is manual.
- Message history is **not** encrypted at rest. It is plain SQLite inside the app sandbox; the
  identity key and invitation secret are wrapped, but message text is not.
- No protection against a compromised device, screenshot malware, or a rooted phone.
- Metadata: while the app is open, DHT nodes, UDP trackers, STUN servers and the other device
  learn your public IP and UDP port. That is inherent to serverless rendezvous.

## Checklist

| Item | Status |
|---|---|
| Invitation secret from a cryptographically secure RNG | Done — `SecureRandom`, 32 bytes |
| Raw invitation secret never published to rendezvous services | Done — only the HMAC-derived 20-byte topic reaches DHT nodes and trackers; STUN sees only the endpoint |
| Peer proves possession of the invitation secret | Done — initiation MAC, verified in `HandshakeTest`; incoming initiations are MAC-checked before any state is created |
| Tracker/STUN replies cannot spoof another endpoint | Done — transaction id and source address must match; STUN replies also require the full 12-byte transaction |
| Remote peer identity authenticated | Done — identity key inside the MAC'd transcript; pinned after pairing |
| Encrypted channel retained and verified | Done — AES-256-GCM, replay window, tamper tests |
| No relay / TURN / message-forwarding fallback | Done — none exists in the code base |
| Rendezvous services receive no message text | Done — DHT carries KRPC only; trackers see the topic hash and endpoint; STUN sees a binding request |
| Message parser validates type, length, encoding | Done — `AppCodecTest` |
| Duplicate messages ignored | Done — chunk reassembly plus `messages.id` primary key |
| ACK sent only after local persistence | Done — repository insert precedes the ACK send |
| Private keys never in logs or plain preferences | Done — Keystore-wrapped; no secret is logged |
| No message content to analytics or crash reporting | Done — no analytics or crash SDK is included |
| Network failure does not delete unsent messages | Done — queued/failed rows persist and are retried |
| App displays the true connection state | Done — explicit states, including "unable to connect directly" |
| Release APK signed and tested on two physical devices | **Signed APK built and verified here; two-device testing is outstanding** (no devices attached to this machine) |

See `LIMITATIONS.md` for the operational consequences.
