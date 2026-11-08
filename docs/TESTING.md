# Testing

## Verified in this repository

All checks below were run on the build machine during development. Rebuild locally with the
included Gradle wrapper to reproduce them.

### JVM unit and integration tests — 86 tests, all passing

```bash
./gradlew :app:testDebugUnitTest
```

| Test class | What it proves |
|---|---|
| `crypto.X25519Test` (3) | RFC 7748 section 6.1 public keys and shared secret; all-zero (low-order) point rejected; key pairs agree |
| `crypto.CryptoTest` (5) | HKDF-SHA256 matches RFC 5869 test case 1; AES-GCM round trip; tampered ciphertext and wrong AAD are rejected |
| `crypto.InviteTest` (5) | Code round trip, copy-artifact tolerance, malformed/truncated codes rejected, topic deterministic, 20 bytes, derived (not the secret) |
| `protocol.AppCodecTest` (20) | Text and file round trips (including Unicode), unknown types rejected, truncated payloads rejected, invalid UTF-8 rejected, empty text rejected, absurd timestamps rejected, wrong version rejected, maximum-size text encodable, file offer/data/ack/end/done validation, digest fields and integrity reasons round trip, unknown done reasons rejected, zero-byte offers, oversize rejection, filename sanitization and MIME limits |
| `protocol.FileDigestTest` (4) | FNV-1a matches published vectors, chunked updates equal a one-shot hash, offset/length slices are respected, different content yields a different digest |
| `protocol.ChunkingTest` (5) | Out-of-order reassembly, duplicate tolerance, inconsistent counts rejected, oversized chunks rejected, stale assemblies expire, maximum application message reassembled |
| `net.SecureChannelTest` (5) | Encrypted frame round trip, a 300-frame out-of-order burst inside the widened replay window, duplicate reported without redelivery, frame older than the window rejected, tampering and wrong-key frames rejected |
| `net.HandshakeTest` (7) | Both sides derive mirrored keys, wrong invitation cannot join, tampered MAC rejected, retransmissions idempotent, cross-session confirmation rejected, keys bound to the transcript, static initiation check and identity extraction |
| `net.PeerLinkTest` (6) | **Full stack over loopback UDP**: real handshake, identity exchange, encrypted text delivery and transport acknowledgement; wrong invitation never connects; **both directions keep working across transport ACKs and idle heartbeats** (the interleaving that stalled the v1 stream and closed the link); a spoofed initiation cannot tear down a live link; a link that only answers an incoming initiation (the engine's responder path) still connects; **2 MiB of file-sized blocks arrive complete and in order** |
| `net.dht.BencodeTest` (5) | Nested round trip with binary strings, canonical dictionary order, malformed input and trailing garbage rejected |
| `net.rendezvous.StunClientTest` (2) | XOR-MAPPED-ADDRESS parsing from a real binding-success packet; a reply with a foreign transaction id is ignored |
| `net.rendezvous.TrackerRendezvousTest` (1) | Real BEP 15 connect/announce byte layout against a local fake tracker: advertised port, `started` event, compact peer parsing, announce throttle |
| `data.ContactModelTest` (12) | Local label/note sanitization and limits, initials, name fallback order, pairing and invitation flags, contact search, chat-summary sort order and preview formatting for text/files |
| `data.SettingsModelTest` (6) | Theme parsing and fallback, notification title/body policy with previews on or off, background-service policy, default settings |

These tests exercise the real socket path (`DatagramPacket`/`DatagramSocket` on loopback)
and the real crypto, not mocks. The contact and settings tests cover the pure model/policy
layer that the new tabs and notification behavior rely on.

### Live rendezvous check (build machine network)

A temporary JVM integration test opened two local UDP sockets through `PublicRendezvous` and
announced a random topic. Result on 2026-11-07:

- STUN learned both public endpoints (`172.232.208.202:33346` and `:49610`);
- `open.stealth.si:80`, `tracker.torrents.observer:80` and `open.ftorrent.com:443` all
  answered, and socket 2 discovered socket 1's endpoint;
- the same network **blocks the DHT bootstrap ports** (all five seeds timed out), which is the
  failure the tracker/STUN path was added for.

The temporary live test was not committed; the deterministic fake-server tests remain in the
suite.

### Build and artifact checks

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/*/*.apk
```

Recorded results:

- all 86 JVM tests pass, including a 2 MiB ordered file-block transfer over loopback UDP;
- `:app:lintDebug` completes with no errors (warnings are informational);
- debug and release APKs build from a clean Gradle cache;
- the release APK verifies with the local `knot` certificate (v2/v3 signature scheme);
- package `dev.twoperson.messenger`, versionCode 2, versionName 1.1.0, minSdk 26, targetSdk 34;
- record the SHA-256 of your local build.

### Not verified here

No Android device or emulator is attached to this machine, so the following were **not**
run: installation, first launch, invitation QR scanning, camera permission flow, on-device
UI rendering, tab navigation, contact search/rename flows, theme switching, two-phone
discovery, hole punching, reconnection after a network change, large-file transfer/resume,
notification permission and channel behavior, and battery/data measurements. They are the
job of the matrix and checks below.

## Two-phone test matrix (to be completed)

Use two physical phones with the release APK. For each row record: rendezvous discovery success
(which path: tracker/STUN, DHT or LAN beacon), direct connection success, time to connect,
whether a message was delivered and acknowledged, failure reason if any, and the connection
label the app displayed (*direct (internet)* / *direct (local network)*).

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
| 12 | Clean install → upgrade install (v1.0 single-contact history) → uninstall/reinstall | | | | | |
| 13 | Two paired contacts, switch between them | | | | | |

### Functional checks to perform on the phones

1. Pair via QR; confirm the code text also works by pasting. Repeat for a second contact and
   confirm each contact has a separate invitation and history.
2. Send messages both ways at the same time; confirm ordering and no duplicates.
3. Kill both apps, reopen; confirm every contact's history is intact.
4. Send while disconnected; confirm the message shows `Queued`, then `Sent to peer`, then
   `Received by peer` after reconnection.
5. With one app closed, confirm the sender never claims delivery.
6. Force a wrong invitation on a third device; confirm it cannot join.
7. Use *Clear conversation*; confirm only that contact's history is removed and the pairing and
   other contacts survive.
8. Use *Unpair device*; confirm the invitation is gone and that chat is unavailable until
   repaired, without affecting other contacts.
9. Confirm the active card shows *Unable to connect directly* when a direct path is
   deliberately blocked (e.g. block outbound UDP), and that no traffic is relayed anywhere.
10. Check `adb logcat` during a session for absence of message text and secrets.
11. Attach a small file in each direction; confirm progress, speed, completion, tap-to-open and
    the long-press actions (open, share, save to `Downloads/Knot`, delete) work, and that the
    receiver’s copy opens after a restart.
12. Attach a zero-byte file; confirm it completes and opens (not an error).
13. Send a large file (≥ 1 GB if available), then switch Wi-Fi/mobile data or toggle airplane
    mode mid-transfer; confirm it resumes rather than restarting and that the final byte count
    and content match the source (compare SHA-256).
14. Clear the conversation during an active transfer; confirm both sides stop, no row remains,
    and app storage does not keep the part file.
15. Send text while a large file is streaming; confirm the message arrives promptly.
16. Background the app and receive a message/file; confirm one per-conversation notification
    appears on the high-importance channel and the service notification (if enabled) stays
    silent. Keep the chat open and receive another; confirm no alert is posted while a Knot
    screen is visible.
17. Deny notification permission on Android 13+; confirm the app still works, Settings links to
    system settings, the permission is not requested on launch, and the switch reflects the
    state once notifications are enabled.
18. Send several messages while scrolled up, then send one; confirm the list scrolls only for
    the outgoing send. Tap the down-arrow to return to the newest row.
19. Long-press a sent text message and copy it; confirm the clipboard contains exactly the
    text. Long-press a failed message and retry after reconnecting; confirm it becomes
    `Received by peer`. Delete a message and confirm it disappears and its app-private file is
    gone.
20. Choose a file from a provider that reports no size (for example a cloud document
    provider); confirm the app makes a local copy and the transfer completes.
21. Revoke a persisted URI grant or delete the original after sending; confirm the outgoing
    message reports the file as unavailable instead of crashing.
22. Use the Chats, Contacts and Settings tabs. Confirm titles, the unread badge, contact search,
    the empty states and back-from-tab behavior work, and that opening a contact detail makes it
    the active connection target.
23. Rename a contact from the Chats three-dot menu after pairing; confirm the list, chat title,
    notification title and contact detail update immediately while the peer's own name is
    unchanged.
24. Switch System/Light/Dark in Settings and confirm every screen, dialog, card and status bar
    follows the choice after recreation. Return to System and confirm the OS setting is used.
25. Hide message previews and receive a message; confirm the notification shows only the app
    name and a private body, and that the lockscreen public version is redacted.
26. Open one conversation's notification; confirm only that chat opens and its notification is
    cleared while another contact's notification remains.
27. Enable *Background connection*; confirm the silent ongoing service notification appears and
    survives backgrounding. Disable it and confirm the notification disappears and the grace
    period still delivers for a short time.
28. On Android 13+, revoke notification permission while background mode is on; confirm the
    service does not crash and Settings still reports the blocked state.
29. Use *Settings → Delete all contacts & data*; confirm contacts, messages, files, invitations
    and local settings are removed, notifications are cleared and the UI returns to a clean
    first-run state.
30. Upgrade from a v1.0 single-contact install; confirm the old conversation appears as the
    `primary` contact with its pairing and history intact, and that the old invitation still
    connects.

### File-transfer matrix (to be completed)

| # | File size | Network | Interruption | Resume offset correct | Final hash matches | Notes |
|---|---|---|---|---|---|---|
| 1 | 0 B | Same Wi-Fi | None | | | |
| 2 | 1 MB | Same Wi-Fi | None | | | |
| 3 | 100 MB | Same Wi-Fi | Wi-Fi toggle | | | |
| 4 | 1 GB | Different networks | Airplane mode 30 s | | | |
| 5 | 100 MB | Mobile data | App backgrounded | | | |
| 6 | Any | Any | Clear conversation mid-transfer | n/a | n/a | Both sides stop cleanly |

### Known constraints for the tester

- Both apps must be running while the message or file travels. With **Background connection**
  enabled, the foreground service keeps the link alive with the screen off; with it off, Knot
  keeps the link for a short grace period after the last screen closes. Force-stopping either
  app ends the link until it is opened again. A file transfer pauses with the link and resumes
  after reconnection while the history is intact.
- Only one contact is connected at a time. Switching contacts cancels in-flight transfers for
  the previous contact and starts discovery for the newly selected one; queued messages remain
  in that contact's own outbox.
- The receiver needs free space for the file plus an 8 MiB safety margin; the UI rejects the
  offer cleanly when space is insufficient. Providers that cannot report a size also need
  space for a temporary app-private copy on the sender.
- Some carriers block UDP entirely; that case should end in *Unable to connect directly*,
  which is a correct result, not a bug.
