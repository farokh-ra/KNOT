# Relationship to `roadmap.md`

## The one structural deviation: native Kotlin instead of an AlterSend fork

The roadmap's Phase 0 requires reproducing AlterSend's Android build before any feature work.
That was not possible in this environment, for concrete reasons:

- AlterSend's Android app is React Native + Expo with a **Bare worklet** that compiles a
  native networking core. Building it needs the Android NDK/CMake toolchain and, in practice,
  Android Studio plus Expo prebuild/EAS steps.
- This machine has only `cmdline-tools`, `platform-tools`, `platforms` (34/36) and
  build-tools 36; there is **no NDK**, no Android Studio, and originally no JDK at all.
- Even with the toolchain, the delivery target — a signed APK — would depend on a large
  native build that cannot be exercised here.

So this project implements the same architecture natively, keeping every product and
security requirement from the roadmap, and replacing only the discovery layer:

| Roadmap expectation | This implementation |
|---|---|
| Hyperswarm DHT discovery on a topic from the invitation secret | Three public rendezvous paths on a topic derived by HMAC-SHA256 from the invitation: BEP 15 UDP trackers on ports 80/443, mainline DHT (BEP 5) `get_peers` / `announce_peer`, and STUN for the socket's translated port; same guarantee that the raw secret is never published |
| Noise-encrypted peer connection | X25519 ephemeral agreement + HMAC-SHA256 authentication + HKDF-SHA256 + AES-256-GCM frames, with a confirmation MAC and replay window (Noise-XX-style, custom construction) |
| Direct connection with NAT traversal | UDP hole punching toward the endpoint learned from the tracker/STUN/DHT rendezvous, plus a same-subnet broadcast beacon; incoming authenticated initiations are answered even when this side is not dialing |
| Relay fallback removed / disabled | No relay, TURN or forwarding code exists at all |
| File transfer replaced by a small messaging protocol | The base text protocol is still a versioned binary schema with strict validation, chunking, acknowledgements and duplicate suppression. At the user’s request, direct-only streaming file transfer was added on top of it: 16 KiB blocks, resume from the receiver’s acknowledged offset, flow control and a 4 TiB schema cap |
| Local storage and history | SQLite in the app sandbox (text, file metadata, transfer progress, read state); Keystore-wrapped identity and invitation; app-private incoming/outgoing files |
| Android UI | Three Material 3 screens (Chats, Pairing, Chat) with conversation/contact cards, file bubbles, date separators, unread badge and jump-to-latest |

Everything else in the roadmap is implemented as written. The trade-off is deliberate: a
buildable, testable, signed APK today, with the discovery library difference documented.

## Phase-by-phase status

| Phase | Status |
|---|---|
| 0 — Repository audit / baseline | Not applicable: no AlterSend checkout was buildable here. Baseline is this native app (documented toolchain in `BUILD.md`) |
| 1 — Fork, remove file transfer | Not applicable to the native baseline (there was no inherited transfer code). Direct-only file transfer was subsequently implemented deliberately, without a relay or upload service |
| 2 — Direct-only networking | Done: single UDP path to the authenticated peer; no relay/TURN/forwarding; failures surface as *Unable to connect directly* |
| 3 — Pairing and identity | Done: 32-byte invitation, QR + paste + scan, invitation-derived topic, peer proves secret possession, identity pinned, malformed/wrong codes rejected |
| 4 — Text messaging | Done: versioned schema, 2000-code-point limit, unique IDs, transport acks, application acks, duplicate suppression, four UI states, simultaneous bidirectional sends supported |
| 4a — File transfer (requested extension) | Done: arbitrary-size streaming over the same encrypted link, resume from durable acknowledged offset, 8 MiB flow-control window, end-to-end FNV-1a digest verification with integrity retry, text priority, progress/speed UI, open/share/save through `FileProvider`, retry/delete actions, cancel/cleanup on clear |
| 5 — Local history and reconnect | Done: SQLite persistence, save-before-send, save-before-ack, outbox retry with the same IDs, restart-safe, network-change handling with bounded backoff; file state and read state persist too |
| 6 — Real-world network testing | **Not possible here** — no physical devices or emulators are attached. The matrix is prepared in `TESTING.md` for the two-phone run |
| 7 — APK hardening and release | Partly done: permissions audited, app ID/name/icon/version set, debug and signed release APKs built and verified, signatures and SHA-256 recorded. Installation and on-device testing remain for the user's two phones |

## Deliberate scope choices

- **A `dataSync` foreground service was added**, although the roadmap only asked for
  foreground reliability. The requirement is a connection that survives backgrounding and
  screen-off, which Android will not allow without one. It is a lifecycle holder only (no
  relay, no content, no wake lock) with an ongoing silent notification that mirrors the real
  state; background delivery while the device is off or the app is force-stopped is still not
  promised, and there is still no push channel.
- **Alerting notifications are message-only.** A separate high-importance channel is used only
  when a real incoming message or completed file is stored while no Knot activity is visible;
  the mandatory foreground-service notification never alerts.
- No message or file delivery while the peer is offline: the row stays queued on the sender.
- No invitation expiry: replaced manually; documented in `LIMITATIONS.md`.
- Release builds are not minified, so no shrinker rules can break reflection at runtime
  without a device test.
