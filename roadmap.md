# Two-Person P2P Messenger --- Android Development Roadmap

**Project goal:** Build a small Android app for exactly two trusted
people to exchange text messages directly over the internet.

**Primary constraint:** No app-operated messaging backend, no cloud
database, no message relay, and no server that stores or forwards chat
content. Public Hyperswarm DHT bootstrap nodes may be used only for peer
discovery/rendezvous. Chat traffic must travel over a direct, end-to-end
encrypted peer connection. If a direct path cannot be established, the
app must report that it cannot connect; it must not silently fall back
to a relay.

**Success deliverable:** A signed, installable Android APK, tested on
two physical phones on different networks, with documented limitations.

------------------------------------------------------------------------

## 1. Make the key architectural decision first

### Recommended approach: fork AlterSend

Repository: <https://github.com/denislupookov/altersend>

Do not start by writing a new Kotlin P2P stack or replacing Hyperswarm
with a different networking library.

AlterSend already has: - An Android app built with React Native /
Expo. - A separate Bare worklet that owns the P2P networking. -
Hyperswarm / Kademlia DHT peer discovery. - A join-code flow based on a
random 32-byte secret. - Noise-encrypted peer connections. - A
direct-connection / NAT-traversal path. - An optional relay fallback
that can be removed or disabled for this project.

Its architecture separates the UI, domain logic and networking core.
This is a much safer starting point than attempting to implement NAT
traversal, cryptography and Android runtime integration from scratch.

**Important:** Keep AlterSend's existing mobile runtime and P2P core
initially. Replace its file-transfer product logic with a small
messaging protocol. Avoid a Kotlin rewrite until there is a proven
reason that the existing Android runtime cannot meet a requirement.

### What "no server" means in this project

The target is: - No server operated by this app's developer. - No
account/authentication backend. - No cloud message storage. - No message
relay. - No TURN relay. - No server receiving plaintext or ciphertext
chat messages for forwarding.

The app may use public DHT bootstrap nodes to help peers discover each
other. These nodes are infrastructure and are not literally "no
servers"; they must not carry the chat conversation. If the requirement
is literally zero third-party infrastructure of any kind, global
internet discovery cannot be guaranteed. In that stricter mode, users
would need another way to exchange reachable network addresses, and
CGNAT will still prevent some connections.

------------------------------------------------------------------------

## 2. Scope: keep version 1 deliberately small

### Must have

1.  One private conversation between two devices.
2.  Create invitation and join invitation.
3.  A clear connection state: discovering, connecting, connected,
    disconnected, or unable to connect.
4.  Send and receive plain text.
5.  Local message history on each device.
6.  Automatic reconnection attempts after temporary network changes.
7.  End-to-end authenticated encryption.
8.  A visible indication that the connection is direct.
9.  A release APK that installs on supported Android devices.

### Explicitly out of scope for version 1

-   File, image, audio or video sharing.
-   Voice/video calls.
-   Group chats or multiple conversations.
-   Phone-number, email or social login.
-   Cloud backup or cloud synchronization.
-   Push notifications that require a backend.
-   Read receipts, typing indicators and presence beyond basic
    connection state.
-   Message delivery while either app is fully offline.
-   A promise of 100% connectivity on every carrier/network.

Do not add these until direct text messaging is stable.

------------------------------------------------------------------------

## 3. Proposed application structure

Keep the existing AlterSend monorepo and its established boundaries.
Adapt its existing structure rather than inventing a new one.

  -----------------------------------------------------------------------
  Existing area                       Responsibility after adaptation
  ----------------------------------- -----------------------------------
  `apps/mobile`                       Android UI and application
                                      lifecycle

  `packages/core`                     Hyperswarm discovery, authenticated
                                      peer connection, encrypted
                                      transport and message protocol

  `packages/domain`                   Chat state, message validation,
                                      connection state and use cases

  `packages/components`               Minimal chat UI components

  Local mobile storage                Message history and local device
                                      settings
  -----------------------------------------------------------------------

The exact file names and APIs must be confirmed from the checked-out
repository before editing. Do not assume that a path or method mentioned
in this roadmap exists unchanged in the current branch.

### UI: only three screens

**A. Home** - Partner/device label. - Connection state. - "Create
invitation" and "Join invitation". - "Open chat" when paired.

**B. Pairing** - Generate and display a QR code and copyable invitation
code. - Scan or paste the other person's code. - Explain that the code
is secret and should only be shared with the intended person.

**C. Chat** - Message list. - Text input and send button. - Sending /
sent / received / failed state. - Reconnecting banner. - No unnecessary
settings or social features.

------------------------------------------------------------------------

## 4. Peer discovery and secure connection

Preserve AlterSend's established Hyperswarm design unless code
inspection reveals a blocking issue.

### Invitation flow

1.  Generate a cryptographically secure random 32-byte invitation
    secret.
2.  Encode it for QR/text sharing.
3.  Derive the DHT discovery topic/key from the secret using the
    project's existing, reviewed derivation.
4.  Join the same topic on both devices.
5.  Never publish or send the raw invitation secret through DHT.
6.  Authenticate the peer after connection; knowing or guessing a
    discovery topic alone must not grant access.
7.  Verify the remote peer identity and establish the encrypted channel.

Do not invent a new cryptographic scheme. Reuse the repository's
established Noise and topic-authentication implementation and verify its
current behavior in source.

### Direct-only connection policy

-   Keep the existing direct connection / hole-punching mechanism.
-   Remove or hard-disable relay fallback in the networking core, not
    just hide a UI setting.
-   Remove TURN configuration and relay upgrade paths if present.
-   Do not add WebSocket message relays, hosted signaling services or a
    developer-operated rendezvous backend.
-   Expose connection type/state to the UI where the existing networking
    API allows it.
-   On failure, show a clear "Direct connection unavailable on these
    networks" state.

**Do not claim that DHT guarantees connectivity.** DHT helps peers find
each other; it does not defeat every NAT/firewall. Some carrier-grade
NAT, symmetric NAT, VPN and firewall combinations prevent direct inbound
connectivity. Without a relay or other reachable third party, those
pairs cannot communicate.

------------------------------------------------------------------------

## 5. Text message protocol

Replace the file-transfer offer/chunk protocol with a small, versioned
message protocol over the already encrypted peer stream.

Each message should include: - Protocol version. - Unique message ID. -
Sender device identity. - Message type. - Message text. - Creation
timestamp.

Use a strict schema and validate all received fields, lengths and
message types. Set a reasonable maximum text length. Reject malformed,
oversized or unsupported messages safely.

### Reliability rules

-   Save an outgoing message locally before attempting network
    transmission.
-   Give every message a stable unique ID.
-   A receiver stores a message once and ignores duplicate IDs.
-   Send an acknowledgement after the receiver has durably stored the
    message.
-   Distinguish `pending`, `sent to peer`, `received by peer` and
    `failed`.
-   Retry pending messages after reconnection, using the same message
    ID.
-   Do not claim a message was delivered merely because the local socket
    accepted bytes.
-   Keep ordering deterministic; use sequence numbers per conversation
    or a clearly defined ordering rule.
-   Handle simultaneous sends from both peers.

### Offline behavior

There is no server to queue messages. If the recipient is offline, keep
the message in the sender's local outbox and send it when a direct
connection returns. The sender must understand that delivery is pending,
not guaranteed.

------------------------------------------------------------------------

## 6. Local storage and privacy

Use local persistent storage already suitable for the mobile app, or add
a small SQLite-backed store if the existing stack does not provide one.

Store only what is needed: - Local message history. - Pending outgoing
messages. - Partner's verified public identity. - Local device identity
reference. - Pairing information needed to reconnect.

Security requirements: - Keep private identity keys in Android Keystore
/ the existing secure-storage integration. - Do not store invitation
secrets in plaintext logs or analytics. - Do not log message bodies,
private keys or full invitation codes. - Avoid analytics and crash
reporting in the first build, or ensure they cannot collect chat data. -
Provide a local "Clear conversation" action only after basic messaging
works. - Explain that deleting the app or clearing its data may erase
local history.

Do not promise encrypted-at-rest history unless it is actually
implemented and tested.

------------------------------------------------------------------------

## 7. Android lifecycle and battery behavior

Android can suspend or kill background apps. A normal socket is not
guaranteed to remain alive indefinitely.

For the first release: - Make foreground, app-open messaging reliable
first. - Handle activity recreation and process restart. - Detect
network changes and reconnect with bounded exponential backoff and
jitter. - Close stale sockets and DHT sessions cleanly. - Persist
pending messages before attempting delivery. - Avoid aggressive wake
locks and battery-draining polling.

Only add a foreground service if continuous active chat while the app is
backgrounded is a demonstrated requirement and the current Android/Expo
runtime supports it correctly. A foreground service requires a
persistent notification and the appropriate current Android service
declarations. Do not promise background delivery when the operating
system can terminate the app.

------------------------------------------------------------------------

## 8. Development phases and acceptance gates

Do not move to the next phase until the current phase passes its
acceptance criteria.

### Phase 0 --- Repository audit and reproducible baseline

1.  Read the current README, `docs/architecture.md`, `CONTRIBUTING.md`,
    package manifests and mobile build scripts.
2.  Check the current branch, commit and license.
3.  Confirm the exact Node.js, npm, Android Studio, JDK and Android SDK
    versions required by the repository.
4.  Install dependencies using the documented lockfile workflow.
5.  Build and install the unmodified Android app.
6.  Record the baseline APK size, startup behavior and any existing
    build failures.
7.  Confirm the P2P connection works between two existing AlterSend
    clients before changing the core.

**Gate:** A clean baseline Android build and a reproducible two-device
P2P test. If this fails, fix the baseline before feature work.

### Phase 1 --- Create a clean fork and remove product complexity

1.  Fork the repository and create a dedicated branch.
2.  Preserve upstream history and license notices.
3.  Identify file-transfer screens, domain actions, RPC methods and
    drive/chunk-transfer code.
4.  Remove file-transfer UI and entry points.
5.  Keep the Hyperswarm, Noise, identity, topic authentication and
    Android worklet infrastructure.
6.  Do not delete shared networking code merely because it is used by
    file transfer; trace dependencies first.

**Gate:** App builds and opens; no file-transfer UI remains; existing
P2P connection can still be established.

### Phase 2 --- Direct-only networking

1.  Trace all direct connection and relay paths in the current core.
2.  Disable/remove relay fallback, relay discovery, relay upgrade, TURN
    and any message-forwarding service.
3.  Keep only DHT bootstrap/discovery and direct peer transport.
4.  Ensure a relay cannot be silently used after a direct connection
    failure.
5.  Expose useful connection errors to the domain/UI.
6.  Add tests or instrumentation proving that chat payloads are sent
    only through the direct peer connection.

**Gate:** Successful direct connection is identified as direct;
deliberately blocked direct connections fail visibly and do not use a
relay.

### Phase 3 --- Pairing and identity

1.  Reuse the existing random invitation and topic-authentication
    design.
2.  Add QR generation/scanning and copy/paste.
3.  Bind a successful pairing to the expected peer public key.
4.  Reject wrong codes, malformed codes and unexpected peers.
5.  Handle invitation expiration/revocation if supported by the existing
    design; otherwise document the current invitation lifetime.

**Gate:** Two devices can securely pair; an unrelated third device
cannot join by guessing or observing the public discovery topic.

### Phase 4 --- Text messaging

1.  Define a versioned message schema.
2.  Add send/receive operations to the core RPC boundary.
3.  Validate all incoming messages.
4.  Implement unique IDs, acknowledgements and duplicate suppression.
5.  Implement pending/sent/received/failed UI states.
6.  Test simultaneous messages in both directions.

**Gate:** Both phones can exchange text in both directions over a direct
encrypted connection.

### Phase 5 --- Local history and reconnection

1.  Add local persistence.
2.  Save outgoing messages before network send.
3.  Save incoming messages before acknowledging them.
4.  Retry pending messages after reconnect.
5.  Handle duplicate delivery and app restart.
6.  Handle Wi-Fi/mobile-data changes without corrupting history.

**Gate:** Messages survive app restarts; pending messages are not lost;
duplicates do not appear.

### Phase 6 --- Real-world network testing

Test with two physical Android devices, not only emulators.

Required matrix: - Same Wi-Fi. - Different home Wi-Fi networks. - Wi-Fi
versus mobile data. - Two different mobile operators. - Both devices on
mobile data. - VPN enabled on one side. - VPN enabled on both sides. -
Network change during a conversation. - App backgrounded and resumed. -
Temporary loss of internet. - Restrictive CGNAT / symmetric NAT cases
where available.

For each test record: - Carrier/network type. - Whether DHT discovery
succeeded. - Whether direct connection succeeded. - Time to connect. -
Whether a message was delivered and acknowledged. - Failure reason where
known. - Battery and data usage during a longer session.

**Gate:** Document actual success/failure results. Do not claim "works
on every network"; direct-only P2P cannot guarantee that.

### Phase 7 --- APK hardening and release

1.  Remove unused file-transfer dependencies only after confirming they
    are not required by the networking runtime.
2.  Check Android permissions and remove unnecessary ones.
3.  Configure application ID, app name, icon and version.
4.  Build a debug APK for repeated testing.
5.  Build a signed release APK using a locally controlled signing key.
6.  Keep the signing key and passwords out of Git.
7.  Test clean installation, upgrade installation and
    uninstall/reinstall behavior.
8.  Verify the final APK installs and runs on both target phones.
9.  Record SHA-256 checksum, version, supported Android range and known
    limitations.

**Gate:** A signed release APK installs on two physical devices and
passes the core two-way text messaging test.

------------------------------------------------------------------------

## 9. Build and development workflow

Use the repository's current documented commands and scripts; verify
them from `package.json` and the mobile app documentation before running
them because scripts can change.

The upstream README currently documents this general setup:

``` bash
git clone https://github.com/denislupookov/altersend.git
cd altersend
npm install

cp apps/desktop/.env.example apps/desktop/.env
cp apps/mobile/.env.example apps/mobile/.env

npm run mobile:setup
npm run mobile
```

For this Android-only fork: - Do not build or maintain desktop/web
targets unless a shared package requires them. - Follow the repository's
documented mobile Android build procedure. - Prefer a reproducible local
Android build when supported. - If the project requires Expo/EAS or a
particular Bare native build step, use the exact currently documented
workflow. - Build a debug APK early, not only at the end. - Keep a short
build log and record successful tool versions.

### APK verification

After building, verify the actual APK artifact: - Confirm the file
exists and is a valid APK. - Inspect its package ID, version and minimum
SDK. - Install with `adb install -r path/to/app.apk`. - Launch it on
both physical devices. - Test invitation, direct connection, sending,
receiving, history and reconnect. - Save the exact release APK and its
SHA-256 checksum.

Do not treat "Metro started", "Gradle succeeded" or "EAS build queued"
as proof that a working APK has been produced.

------------------------------------------------------------------------

## 10. Security and quality checklist

Before release: - \[ \] Invitation secret generated with a
cryptographically secure random generator. - \[ \] Raw invitation secret
never published to DHT. - \[ \] Peer proves possession of the invitation
secret. - \[ \] Remote peer identity is authenticated. - \[ \]
Noise-encrypted connection is retained and verified. - \[ \] No
relay/TURN/message-forwarding fallback exists. - \[ \] DHT receives no
message text. - \[ \] Message parser validates type, length and
encoding. - \[ \] Duplicate messages are ignored. - \[ \]
Acknowledgement is sent only after local persistence. - \[ \] Private
keys are not written to logs or ordinary preferences. - \[ \] No message
contents are sent to analytics or crash reporting. - \[ \] Network
failure does not delete unsent messages. - \[ \] App displays the true
connection state. - \[ \] Release APK is signed and tested on physical
devices.

------------------------------------------------------------------------

## 11. Major risks and honest limitations

  -----------------------------------------------------------------------
  Risk                                Reality / response
  ----------------------------------- -----------------------------------
  Both phones behind restrictive      Direct connection may be impossible
  CGNAT                               without relay. Show failure
                                      clearly.

  Public DHT bootstrap availability   Discovery depends on reachable
                                      bootstrap infrastructure; allow
                                      multiple known bootstrap addresses
                                      where supported.

  Mobile OS background limits         Android may suspend/kill the app.
                                      Prioritize foreground reliability.

  Network changes                     Reconnect and preserve pending
                                      messages.

  Hyperswarm native/runtime           Preserve AlterSend's existing Bare
  compatibility                       worklet and Android integration;
                                      prove baseline builds first.

  Upstream changes                    Pin a known-good commit and upgrade
                                      deliberately.

  Invitation code leakage             Treat it as a secret; authenticate
                                      peer possession and identity.

  No offline message server           Messages wait on the sender's
                                      device until a direct connection is
                                      available.
  -----------------------------------------------------------------------

### Non-negotiable truth

A direct-only app cannot guarantee a connection between every pair of
internet connections. DHT solves discovery, not every NAT/firewall
problem. If a network path does not exist between the two devices, no
software-only trick can send a message without introducing another
reachable path (such as a relay).

The project should optimize for the highest practical direct-connection
success rate while preserving the no-relay requirement.

------------------------------------------------------------------------

## 12. Final definition of done

The project is complete when:

1.  There is a working, signed Android APK.
2.  It installs on two physical Android phones.
3.  The two phones pair using a QR code or invitation code.
4.  Both phones discover each other through DHT.
5.  They establish a direct, authenticated, encrypted connection.
6.  They exchange text messages in both directions.
7.  Chat history persists locally.
8.  Pending messages retry after reconnection.
9.  No chat message is sent through a relay or central server.
10. Failed direct connections are reported honestly.
11. The tested network matrix and known limitations are documented.

------------------------------------------------------------------------

## 13. Reference material

-   AlterSend repository: <https://github.com/denislupookov/altersend>
-   AlterSend architecture:
    <https://github.com/denislupookov/altersend/blob/main/docs/architecture.md>
-   AlterSend contribution/build notes:
    <https://github.com/denislupookov/altersend/blob/main/CONTRIBUTING.md>
-   AlterSend releases:
    <https://github.com/denislupookov/altersend/releases>

**Implementation principle:** First reproduce the existing Android P2P
connection. Then remove relay support. Then replace file transfer with
text messages. Build and test an APK after every major phase. Do not
rewrite the working networking foundation without a demonstrated
blocker.
