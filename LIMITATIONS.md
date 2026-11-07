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

6. **Background delivery needs the app to still be running.** A `dataSync` foreground service
   holds the direct connection while the screen is off or another app is in front, so leaving
   the app no longer drops the link, and an ongoing notification shows the real state. It
   cannot survive the device being powered off, the app being force-stopped by the user, or
   long Doze/standby periods; in those cases the other side sees the link close and the app
   reconnects when it runs again.
7. **Both people must have the app running for a message to travel.** There is no push
   notification and no server to hold messages, so a message sent while the peer is off stays
   queued on the sender and is delivered after the peer reconnects.

## Messaging semantics

8. **Delivery is only as good as the acknowledgement.** `Received by peer` means the other
   device stored the message and returned an application-level ACK. There is no read receipt.
9. **`Not delivered` is retried automatically** on the next connection; the text is never
   destroyed, but it is also not guaranteed to arrive if the peer never comes back online.
10. **Message text is limited to 2000 characters** (a strict schema limit).
11. **History is local and not encrypted at rest.** Clearing app data or uninstalling deletes
    it permanently. There is no backup, export or sync.

## Security posture

12. **The handshake is a custom construction**, tested against RFC 7748/HKDF vectors and
    covered by unit tests, but not externally audited and not the Noise protocol itself.
13. **No invitation expiry or revocation flow** beyond creating a new invitation or using
    *Forget partner*.
14. **Metadata is visible to the network**: DHT nodes, UDP trackers, STUN servers and the peer
    see your public IP/port while the app is open.

## Verification

15. **No physical-device or emulator testing has been performed in this environment.** The
    app compiles, its JVM test suite passes, and the APKs are built and signature-verified,
    but invitation scanning, on-device UI, and two-phone connectivity must be exercised by
    the user. `docs/TESTING.md` contains the matrix to record those results.
