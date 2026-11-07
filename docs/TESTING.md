# Testing

## Verified in this repository

All checks below were run on the build machine during development. Rebuild locally with the
included Gradle wrapper to reproduce them.

### JVM unit and integration tests — 48 tests, all passing

```bash
./gradlew :app:testDebugUnitTest
```

| Test class | What it proves |
|---|---|
| `crypto.X25519Test` (3) | RFC 7748 section 6.1 public keys and shared secret; all-zero (low-order) point rejected; key pairs agree |
| `crypto.CryptoTest` (5) | HKDF-SHA256 matches RFC 5869 test case 1; AES-GCM round trip; tampered ciphertext and wrong AAD are rejected |
| `crypto.InviteTest` (5) | Code round trip, copy-artifact tolerance, malformed/truncated codes rejected, topic deterministic, 20 bytes, derived (not the secret) |
| `protocol.AppCodecTest` (11) | Round trips (including Unicode), unknown types rejected, truncated payloads rejected, invalid UTF-8 rejected, empty text rejected, absurd timestamps rejected, wrong version rejected, maximum-size text encodable |
| `protocol.ChunkingTest` (4) | Out-of-order reassembly, duplicate tolerance, inconsistent counts rejected, oversized chunks rejected, stale assemblies expire |
| `net.SecureChannelTest` (5) | Encrypted frame round trip, replay reported as duplicate, tampering rejected, wrong key rejected, truncation rejected |
| `net.HandshakeTest` (6) | Both sides derive mirrored keys, wrong invitation cannot join, tampered MAC rejected, retransmissions idempotent, cross-session confirmation rejected, keys bound to the transcript |
| `net.PeerLinkTest` (4) | **Full stack over loopback UDP**: real handshake, identity exchange, encrypted text delivery and transport acknowledgement; wrong invitation never connects; **both directions keep working across transport ACKs and idle heartbeats** (the interleaving that stalled the v1 stream and closed the link); a spoofed initiation cannot tear down a live link |
| `net.dht.BencodeTest` (5) | Nested round trip with binary strings, canonical dictionary order, malformed input and trailing garbage rejected |

These tests exercise the real socket path (`DatagramPacket`/`DatagramSocket` on loopback)
and the real crypto, not mocks.

### Build and artifact checks

```bash
./gradlew :app:assembleDebug :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/*/*.apk
```

Recorded results:

- debug and release APKs build from a clean Gradle cache;
- the release APK verifies with the local `twoperson` certificate (v2/v3 signature scheme);
- package `dev.twoperson.messenger`, versionCode 1, versionName 1.0.0, minSdk 26, targetSdk 34;
- record the SHA-256 of your local build.

### Not verified here

No Android device or emulator is attached to this machine, so the following were **not**
run: installation, first launch, invitation QR scanning, camera permission flow, on-device
UI rendering, two-phone discovery, hole punching, reconnection after a network change, and
battery/data measurements. They are the job of the matrix below.

## Two-phone test matrix (to be completed)

Use two physical phones with the release APK. For each row record: DHT discovery success,
direct connection success, time to connect, whether a message was delivered and
acknowledged, failure reason if any, and the connection label the app displayed
(*direct (internet)* / *direct (local network)*).

| # | Network setup | Discovery | Direct | Time | Message + ACK | Label / failure |
|---|---|---|---|---|---|---|
| 1 | Same Wi-Fi | | | | | |
| 2 | Different home Wi-Fi networks | | | | | |
| 3 | A on Wi-Fi, B on mobile data | | | | | |
| 4 | Two different mobile operators | | | | | |
| 5 | Both on mobile data | | | | | |
| 6 | VPN on one side | | | | | |
| 7 | VPN on both sides | | | | | |
| 8 | Network change (Wi-Fi → mobile) mid-conversation | | | | | |
| 9 | App backgrounded then resumed | | | | | |
| 10 | Temporary internet loss (airplane mode 30 s) | | | | | |
| 11 | Restrictive CGNAT / symmetric NAT | | | | | |
| 12 | Clean install → upgrade install → uninstall/reinstall | | | | | |

### Functional checks to perform on the phones

1. Pair via QR; confirm the code text also works by pasting.
2. Send messages both ways at the same time; confirm ordering and no duplicates.
3. Kill both apps, reopen; confirm history is intact.
4. Send while disconnected; confirm the message shows `Queued`, then `Sent to peer`, then
   `Received by peer` after reconnection.
5. With one app closed, confirm the sender never claims delivery.
6. Force a wrong invitation on a third device; confirm it cannot join.
7. Use *Clear conversation*; confirm the history is removed and the pairing survives.
8. Use *Forget partner*; confirm the invitation is gone and chat is unavailable until repaired.
9. Confirm the home screen shows *Unable to connect directly* when a direct path is
   deliberately blocked (e.g. block outbound UDP), and that no traffic is relayed anywhere.
10. Check `adb logcat` during a session for absence of message text and secrets.

### Known constraints for the tester

- Both apps must be running; the connection is held by the foreground service, so the screen
  may be off. Force-stopping either app ends the link until it is opened again.
- Some carriers block UDP entirely; that case should end in *Unable to connect directly*,
  which is a correct result, not a bug.
