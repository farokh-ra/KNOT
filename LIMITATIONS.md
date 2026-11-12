# Known limitations

These are properties of a direct-only, serverless messenger, not bugs to be fixed later by
adding a relay.

## Connectivity

1. **A direct path cannot exist on every network.** Carrier-grade NAT, symmetric NAT, strict
   corporate firewalls and some VPN combinations prevent inbound UDP. Tracker and STUN
   rendezvous improve discovery but cannot punch through a symmetric NAT; in those cases the
   app shows *Unable to connect directly* and keeps retrying. It will not route your messages
   through a third party.
2. **Discovery needs at least one public rendezvous path.** The app tries UDP trackers on
   HTTP/HTTPS ports (80/443), STUN and the DHT bootstrap nodes
   (`router.bittorrent.com`, `dht.transmissionbt.com`, `router.utorrent.com`,
   `dht.libtorrent.org`, `dht.aelitis.com`). Many carriers block the DHT's port 6881; the
   tracker path keeps discovery working there. A network that blocks all outbound UDP cannot
   discover anything.
3. **Same-Wi-Fi connections rely on the LAN beacon or NAT hairpinning.** Many routers do not
   support hairpinning; the beacon (UDP broadcast on port 41999) usually saves this case, but
   client-isolated Wi-Fi and broadcast filtering can defeat it. Some routers block the fixed
   port entirely.
4. **UDP may be blocked or throttled** by a mobile carrier. There is no TCP fallback, because
   a TCP path would need a reachable server.
5. **The invitation is only a rendezvous secret.** Anyone who obtains it can attempt to
   connect, and pairing is trust-on-first-use. Share it only with the intended person.

## Behaviour while the app is not open

6. **Background delivery is always on while a contact is paired.** A `dataSync` foreground
   service holds the direct connection while the screen is off or another app is in front, and
   a silent ongoing notification shows the real state. The service is restarted after a reboot
   or app update. It cannot survive the app being force-stopped by the user, aggressive OEM
   task killers, or long Doze/standby periods that suspend network access; setting Knot to
   **Unrestricted** in Android battery settings improves the odds. When the service is
   refused or killed, the engine falls back to a short grace period after the last screen
   closes and reconnects when the app runs again.
7. **Both people must have the app running for messages or files to travel.** The always-on
   service keeps it running in the background, but there is no push notification and no server
   to hold data. A message or file queued while the peer is genuinely offline stays on the
   sender and continues after the peer reconnects.
8. **Only one direct connection is active at a time.** Contacts are independent one-to-one
   threads, but opening a different contact changes the engine's connection target, cancels
   in-flight transfers for the previous contact and starts discovery for the new one. Queued
   messages remain in each contact's own outbox and are retried when that contact becomes
   active again.

## Messaging and file semantics

9. **Delivery is only as good as the acknowledgement.** `Received by peer` means the other
   device stored the message (or completed file) and returned an application-level ACK. There
   is no read receipt.
10. **`Not delivered` is retried automatically** on the next connection and can also be retried
   immediately from the message long-press menu. The text or file is never destroyed, but it
   is also not guaranteed to arrive if the peer never comes back online.
11. **Message text is limited to 2000 characters** (a strict schema limit).
12. **Files are streamed, not uploaded.** Both devices must be online at the same time; there
    is no server or relay that stores bytes while the receiver is away.
13. **The protocol caps a file at 4 TiB**, a file name at 255 UTF-8 bytes and a MIME type at
    128 bytes. The UI rejects a known-oversize file before creating a message.
14. **Providers that cannot report a size are copied once into app storage.** The copy is
    capped at 4 TiB and requires enough free space for a second copy of the file.
15. **Resume depends on the receiver’s local history.** The acknowledged offset, the running
    FNV-1a prefix digest and the `.part` file are device-local. A completed transfer is checked
    against the sender’s whole-file digest before it is published; a mismatch restarts that
    file from zero. Clearing the conversation cancels transfers and deletes those part files,
    so a later attempt starts from zero. The original SAF file is never modified.
16. **Received files and cached outgoing copies are app-private but not encrypted at rest.**
    They are deleted when the conversation is cleared, the app data is cleared, or the app is
    uninstalled. A completed file can be exported explicitly to `Downloads/Knot`; there is no
    automatic sync or cloud backup.
17. **A persisted SAF URI can become unavailable** if the provider revokes the grant or the
    app is reinstalled. The message then reports the file as unavailable instead of crashing.
18. **History is local and not encrypted at rest.** Clearing app data or uninstalling deletes
    it permanently. There is no backup, export or sync.

## Notifications

19. **Alerting notifications are only for real incoming messages and completed files** while no
    Knot screen is visible. They are never used to keep a connection alive. Each notification
    belongs to one conversation, keeps its unread count while the app is backgrounded and is
    cleared when that chat is opened.
20. **The foreground-service notification is mandatory while a contact is paired.** It is
    placed on a silent, minimum-importance channel and mirrors the connection state. There is
    no switch that turns it off, because turning it off is exactly what stops realtime delivery.
    Android may also show an app-level foreground-service indicator in system settings while
    the service runs.
21. **Notification permission is requested only from the explicit enable button**, never on
    launch. If it is denied or notifications are blocked in system settings, the app still
    sends and receives; the Settings tab shows the blocked state and a settings shortcut. No
    server push exists, so a force-stopped app cannot be woken by a notification.

## Security posture

22. **The handshake is a custom construction**, tested against RFC 7748/HKDF vectors and
    covered by unit tests, but not externally audited and not the Noise protocol itself.
23. **No invitation expiry or revocation flow** beyond creating a new invitation or using
    *Unpair device*.
24. **File names are sanitized and storage paths are app-owned**, but file content is not
    scanned for malware; opening a received file is the user’s decision.
25. **Metadata is visible to the network**: DHT nodes, UDP trackers, STUN servers and the active
    peer see your public IP/port while the engine is running.

## Verification

26. **No physical-device or emulator testing has been performed in this environment.** The
    app compiles, its JVM test suite passes, and the APKs are built and signature-verified,
    but invitation scanning, tab and theme behavior, two-phone connectivity, large-file resume
    and the notification flows must be exercised by the user. `docs/TESTING.md` contains the
    matrix to record those results.
