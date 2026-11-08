<div align="center">

# Knot

**Private, serverless, end-to-end encrypted messaging for exactly two people.**

A small Android app that lets two trusted devices talk directly over the internet — with no
account system, no messaging backend, no cloud database, no relay and no analytics.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/version-1.1.0-14B8A6?style=for-the-badge)](https://github.com/farokh-ra/KNOT)
[![Tests](https://img.shields.io/badge/tests-86%20passing-22C55E?style=for-the-badge)](docs/TESTING.md)
[![Backend](https://img.shields.io/badge/backend-none-0F172A?style=for-the-badge)](#privacy-by-design)
[![Stars](https://img.shields.io/github/stars/farokh-ra/KNOT?style=for-the-badge&logo=github)](https://github.com/farokh-ra/KNOT/stargazers)

[Overview](#overview) · [Features](#features) · [How it works](#how-it-works) ·
[Security](#security-model) · [Build](#build-from-source) · [Install](#install) ·
[Documentation](#documentation) · [Limitations](#limitations)

</div>

---

> **Project status — v1.1.0**
> The app compiles and the 86-test JVM suite passes. The release signing path is documented
> and produces a signed APK when the local keystore is present. No prebuilt release artifact
> is published in this repository — build it locally with the included Gradle wrapper.
> On-device installation, QR scanning and the two-phone network matrix still need to be
> exercised on physical hardware. See [`docs/TESTING.md`](docs/TESTING.md) for the exact
> verification record and the test matrix to complete.

## Overview

Knot is a deliberately small messenger for **private one-to-one conversations between paired
devices**. You can keep a local address book with several contacts, but each contact owns one
private thread and only one direct connection is active at a time. It is built around a simple
rule: if the two phones cannot reach each other directly, the app says so. It never silently
routes a message through a third party.

Public infrastructure — BitTorrent-DHT bootstrap nodes, UDP trackers and STUN servers — is
used **only for rendezvous**, to help the two devices discover each other's UDP endpoint.
Chat traffic travels over a direct, authenticated, encrypted peer connection. There is no
server component in this repository and no hosted service operated for the app.

| | |
|---|---|
| **App name** | Knot |
| **Package** | `dev.twoperson.messenger` |
| **Version** | 1.1.0 (versionCode 2) |
| **Platform** | Android 8.0 (API 26) and newer |
| **Language** | Kotlin 2.0.21 |
| **Build** | Gradle 8.11.1 · Android Gradle Plugin 8.7.3 |
| **Transport** | Direct UDP · AES-256-GCM |
| **Backend** | None |
| **Build output** | `app/build/outputs/apk/` (debug and release) |

### Why another messenger?

Most messengers ask you to trust a server. Knot removes that server from the conversation.
The trade-off is honest and visible: direct-only peer-to-peer networking cannot cross every
NAT, firewall or carrier-grade NAT. When a path does not exist, Knot reports
**“Unable to connect directly”** instead of falling back to a relay.

## Features

| | Feature | What it means |
|---|---|---|
| **Tabs and contacts** | Chats · Contacts · Settings | A local address book with search, private notes, per-contact invitations and a conversation list that includes every contact with a pairing, invitation or history. Rename a contact from the chat list at any time. |
| **Pair once** | 32-byte invitation secret per contact | One person creates an invitation; the other scans the QR code or pastes the `TPM1-…` code. Each contact has an independent secret and pinned identity. |
| **Serverless discovery** | UDP trackers + DHT + LAN beacon | Both devices derive a rendezvous topic from the invitation and find each other without an account server, even on networks that block the usual DHT ports. |
| **Direct connection** | UDP hole punching | Peers authenticate and talk directly; no TURN, relay or message-forwarding path exists in the codebase. |
| **End-to-end encryption** | X25519 + HKDF + AES-256-GCM | Fresh ephemeral keys per session, authenticated handshake, per-direction nonces and a replay window. |
| **Reliable messaging** | Local outbox + ACKs | Messages are stored before sending, retried after reconnection and acknowledged only after the peer stores them. |
| **Files of any size** | Streaming blocks + resume | Attach any file the system picker can open. Bytes stream in 16 KiB blocks over the same encrypted link, with live progress, automatic resume from the receiver’s durable acknowledged offset, and an end-to-end FNV-1a digest check before the file is published. |
| **Duplicate suppression** | Stable message and transfer IDs | A resent message is acknowledged again but never displayed twice; a resumed file never repeats or skips a block. |
| **Background link** | Opt-in `dataSync` foreground service | Off by default. When enabled, the connection survives screen-off and app backgrounding with a silent, ongoing notification that mirrors the real state. With it off, Knot keeps the link for a short grace period after the last screen closes. |
| **Message actions** | Long-press a bubble | Copy text, retry a failed send, delete a message, and open, share or save a completed file to `Downloads/Knot`. |
| **Real message alerts** | Per-conversation notifications | A high-importance notification is posted only when a new message or completed file arrives while no Knot screen is visible. Each conversation has its own notification; opening that chat clears it. Previews can be hidden in Settings. |
| **Themes and privacy** | System / Light / Dark · private previews | Material 3 light and dark palettes, a notification-preview switch, and a one-tap “delete all contacts & data” action. |
| **Local history** | SQLite in the app sandbox | Per-contact history survives restarts. Clearing app data or uninstalling removes it permanently. Received files and outgoing cache copies stay private to the app. |
| **Honest failures** | Explicit connection states | The UI shows discovering, connecting, connected, disconnected and unable-to-connect states. |
| **No telemetry** | No analytics or crash SDK | No account, no tracking, no message content leaves the two devices except over the encrypted peer link. |

### What Knot deliberately does **not** do

No group chats, voice or video calls, cloud backup, server push, read receipts, typing
indicators, contact discovery, accounts, phone-number login or message relay. A file transfer
still requires both devices to be online at the same time. Each contact is intentionally one
private conversation; Knot never merges contacts into a group, and only one direct connection
runs at a time.

## How it works

### 1. Pairing

1. Phone A generates a **32-byte invitation secret** with `SecureRandom`.
2. The secret is encoded as a copyable `TPM1-XXXX-XXXX-…` code and a QR code.
3. Phone B scans or pastes the code.
4. Both devices derive the same values locally:

```text
authKey = HMAC-SHA256(secret, "tpmsg/v1/auth")
topic   = HMAC-SHA256(authKey, "tpmsg/v1/topic")[0..19]
```

The raw secret is **never published**. Only the derived 20-byte topic reaches the DHT and the
public trackers.

### 2. Discovery

Discovery has four independent paths, all sharing the same UDP socket:

1. **UDP trackers (BEP 15)** on HTTP/HTTPS ports (80/443). Both devices announce the derived
   topic and read each other's public endpoint back. This keeps discovery working on carriers
   that block the DHT's port 6881.
2. **STUN** learns the NAT's translated port for the shared socket, so the tracker announce
   publishes the port peers should actually dial.
3. **BitTorrent mainline DHT** (BEP 5 `get_peers` / `announce_peer`) on the derived topic.
4. **LAN beacon** on UDP port 41999 for the same-Wi-Fi case.

These services learn a derived topic hash and your public IP/UDP port while the engine is
running — nothing else. Incoming authenticated handshakes are accepted at any time, not only
while this side is dialing, so one-sided discovery still connects.

### 3. Direct connection

Peers send authenticated X25519 handshake packets to the endpoint they discovered. The
handshake proves possession of the invitation secret, binds the peer's static identity key
into the transcript, and derives fresh session keys. Chat then travels as AES-256-GCM frames
over the same UDP socket used for discovery, so the NAT mapping stays valid.

If no direct path exists, the app keeps retrying and displays **“Unable to connect directly”**.
It does not fall back to a relay.

### 4. Messaging

Outgoing messages are persisted as `PENDING` **before** any network send. The reliable layer
chunks each message into encrypted frames, acknowledges them cumulatively with a selective
bitmap, retransmits with exponential backoff, and delivers strictly in order. When the peer
has stored the message, it returns an application-level `ACK` and the sender marks it
**Received by peer**. A message that cannot be delivered stays on the sender and is retried
with the same ID on the next connection.

### 5. File transfer

Files use the same authenticated, encrypted reliable channel as text, so there is still no
relay and no separate upload service:

1. The sender stores the file reference (a persisted Storage Access Framework URI, or an
   app-private copy when the provider cannot report a size) and inserts a visible file message.
2. It announces a `FileOffer` with a stable transfer ID, exact byte count, sanitized file name
   and MIME type.
3. The receiver checks the size, free space and schema bounds, creates a `.part` file and
   replies with a `FileAck` containing the number of bytes it durably holds. Zero bytes means a
   fresh transfer; a non-zero offset resumes an interrupted one.
4. The sender streams 16 KiB blocks with offsets, stays at most 8 MiB ahead of the receiver’s
   durable acknowledgement (flow control), and yields to text messages so the chat remains
   responsive.
5. The receiver appends blocks in transport order, persists progress and a running FNV-1a
   digest, and acknowledges at intervals. On `FileEnd`, it verifies the exact length and the
   whole-file digest, moves the part file into app-private storage under a collision-free
   name, marks the message delivered and posts a notification if no Knot screen is open. A
   digest mismatch discards the part file and makes the sender retry from zero.
6. On reconnect, both sides restart from the receiver’s acknowledged offset and adopt its
   persisted prefix digest. A block that could not be queued is retained and retried, so a
   full send window never loses bytes.

The protocol caps a file at 4 TiB and an individual name at 255 UTF-8 bytes; the UI reports
oversize files instead of creating a message. Clearing the conversation cancels active
transfers and removes the app-private copies that belonged to the deleted messages; the
original file chosen through the system picker is never touched.

## Architecture

```mermaid
flowchart LR
    subgraph A["Phone A"]
        AUI["Knot UI<br/>Chats · Contacts · Settings<br/>Pairing · Chat · About"]
        AENG["Connection engine<br/>contact selection · outbox · ACKs"]
        ASTORE[("SQLite history<br/>Keystore-wrapped secrets")]
        AUI --> AENG
        AENG --> ASTORE
    end

    subgraph B["Phone B"]
        BUI["Knot UI<br/>Chats · Contacts · Settings<br/>Pairing · Chat · About"]
        BENG["Connection engine<br/>contact selection · outbox · ACKs"]
        BSTORE[("SQLite history<br/>Keystore-wrapped secrets")]
        BUI --> BENG
        BENG --> BSTORE
    end

    AENG <-->|"Direct UDP · AES-256-GCM"| BENG
    AENG -.->|"rendezvous only"| DHT[("Public rendezvous<br/>UDP trackers · STUN · DHT")]
    BENG -.->|"rendezvous only"| DHT
    AENG <-.->|"LAN beacon"| BENG
```

| Layer | Responsibility |
|---|---|
| **UI** | Tabbed Material 3 shell — Chats, Contacts, Settings — plus Pairing, Chat, Contact detail and About screens; contact search, chat previews, unread badges, date separators, file bubbles and jump-to-latest. |
| **Service** | An opt-in `dataSync` foreground service keeps the engine alive in the background; its notification is silent, and alerts use a separate per-conversation message channel. With the option off, the engine uses a short background grace period. |
| **Engine** | Contact-scoped connection state machine, discovery loop, per-contact text outbox, file transfer manager, acknowledgements, network-change handling and reconnect backoff. |
| **Protocol** | Versioned binary schema (v3) with strict validation, chunking, bounded reassembly and file offer/data/ack/end/done messages. |
| **Transport** | One UDP socket, authenticated handshake, encrypted reliable frames, 4096-frame window and heartbeats. |
| **Discovery** | BEP 15 UDP-tracker rendezvous, STUN public-endpoint lookup, minimal mainline-DHT client and a same-subnet LAN beacon. |
| **Storage** | SQLite message and transfer state, app-private incoming files and outgoing cache copies, pinned peer identity and Keystore-wrapped secrets. |
| **Crypto** | X25519, HKDF-SHA256, HMAC-SHA256 and AES-256-GCM. |

The full design is documented in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), and the exact
wire format is in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Security model

Knot assumes the network is hostile: packets can be observed, injected, replayed, dropped or
reordered. Public DHT nodes, UDP trackers and STUN servers are untrusted infrastructure. The
invitation is exchanged out-of-band with the intended person.

| Property | Implementation |
|---|---|
| **Key exchange** | X25519 (RFC 7748) with fresh ephemeral keys for every session |
| **Key derivation** | HKDF-SHA256 with a transcript-bound salt |
| **Peer authentication** | HMAC-SHA256 keyed by the invitation-derived `authKey`, plus a confirmation MAC |
| **Transport encryption** | AES-256-GCM; per-direction counters as nonces; 128-frame replay window |
| **Forward secrecy** | Session keys come from ephemeral X25519 keys; the long-term identity key signs into the transcript but never encrypts messages |
| **Identity pinning** | The peer's static X25519 identity is pinned after the first successful pairing; a changed identity is rejected |
| **Secret storage** | The identity private key and invitation secret are wrapped with a non-exportable AES key held in the Android Keystore |
| **Discovery privacy** | Only the HMAC-derived 20-byte topic is published to DHT nodes and trackers; STUN only reflects the socket's public endpoint. The invitation secret is never published |
| **File confidentiality** | File blocks travel inside the same AES-256-GCM frames as text; the offer, name, MIME type, offsets and acknowledgements are authenticated application messages |
| **File bounds** | A schema cap of 4 TiB, 16 KiB blocks, 255-byte UTF-8 names, 128-byte MIME types and sanitized paths prevent unbounded or traversal-style writes |
| **File storage** | Received files land in app-private `filesDir/incoming`; outgoing copies use `filesDir/outgoing`. Sharing and opening use a `FileProvider` read grant |
| **Notification privacy** | Message alerts use `VISIBILITY_PRIVATE` with a redacted public version; the ongoing service notification is silent and minimum-importance |
| **Relay** | None. There is no relay, TURN or forwarding code path anywhere in the project |

### Honest gaps

- The handshake is a custom Noise-XX-style construction (X25519 + HMAC + HKDF + confirmation
  MAC). It is covered by unit tests and RFC 7748 vectors, but it has **not** been externally
  audited and is not the Noise protocol implementation itself.
- Pairing is **trust-on-first-use**. If someone obtains the invitation before the intended
  person pairs, they can become the first peer. Invitations do not expire; replacing the
  invitation or using *Unpair device* is manual.
- Message history **and received files are not encrypted at rest**. They sit in plain SQLite
  and app-private storage inside the sandbox; the identity key and invitation secret are
  wrapped, but message text and file bytes are not.
- Outgoing files are referenced by a persisted Storage Access Framework URI. If the provider
  revokes that grant, or the app is reinstalled, the message reports the file as unavailable;
  providers that cannot report a size are copied into app storage first.
- While the engine is running, DHT nodes, UDP trackers, STUN servers and the active peer learn
  your public IP and UDP port. This is inherent to serverless rendezvous. Notification content
  may be visible on an unlocked device or to a granted notification listener, unless previews
  are turned off in Settings.
- A compromised device, rooted phone, screenshot malware or a malicious keyboard is outside
  the threat model.

Read the full threat model and checklist in [`docs/SECURITY.md`](docs/SECURITY.md).

## Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Android Views, Material 3, ViewBinding |
| Concurrency | Kotlin Coroutines and `StateFlow` |
| Networking | Raw UDP (`DatagramSocket`), custom reliable link |
| Discovery | BEP 15 UDP trackers, RFC 5389 STUN, BitTorrent mainline DHT (BEP 5), LAN broadcast beacon |
| Cryptography | `java.security` / `javax.crypto`: X25519, HKDF-SHA256, HMAC-SHA256, AES-256-GCM |
| Storage | SQLite (`SQLiteOpenHelper`), Android Keystore |
| QR | ZXing core + `zxing-android-embedded` (no Play Services) |
| Build | Gradle 8.11.1, AGP 8.7.3, JDK 17+ |
| Tests | JUnit 4, `kotlinx-coroutines-test`, real loopback UDP |

## Build from source

### Requirements

- **JDK 17 or newer** (the recorded artifacts were built with Temurin 21.0.13+9).
- **Android SDK** with **platform 34** and **build-tools 34.0.0**.
- No NDK, Android Studio, Node.js or Expo toolchain is required. The Gradle wrapper is
  included.

### Clone and configure

```bash
git clone https://github.com/farokh-ra/KNOT.git
cd KNOT
```

Point Gradle at your Android SDK by creating `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

On Windows, escape the path:

```properties
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

### Build and test

**Windows (PowerShell):**

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17-or-newer"

.\gradlew.bat :app:testDebugUnitTest      # 86 JVM tests
.\gradlew.bat :app:lintDebug              # Android lint
.\gradlew.bat :app:assembleDebug          # debug APK
.\gradlew.bat :app:assembleRelease        # signed release APK
```

**macOS / Linux:**

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer

./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### Release signing

Release signing is read from `signing/keystore.properties`, which is **git-ignored**:

```properties
storeFile=signing/release.jks
storePassword=…
keyAlias=knot
keyPassword=…
```

If the file is missing, `assembleRelease` produces an **unsigned** APK instead of silently
falling back to a debug key. To create a fresh key:

```bash
keytool -genkeypair -keystore signing/release.jks -storetype JKS -alias knot \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Knot Release, O=Knot, C=US"
```

Reusing the same signing key matters: a different key means the installed app cannot be
upgraded in place. Never commit the keystore or its passwords.

Full build and verification details: [`docs/BUILD.md`](docs/BUILD.md).

## Install

Build an APK locally with the included Gradle wrapper.

For a quick local install, use the debug build:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, configure `signing/keystore.properties` first (see above), then:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it from the Files app. The APK is a single universal
build with no native libraries, so it runs on arm64, arm32 and x86 devices with Android 8.0+.

- Camera permission is requested only when you tap **Scan QR code**; you can always paste the
  invitation code instead.
- Samsung/One UI adds extra sideloading gates. See
  [`docs/INSTALL.md`](docs/INSTALL.md) for Auto Blocker, Play Protect and
  unknown-sources troubleshooting.
- The debug and release builds share the same application ID but use different signing keys,
  so uninstall one before installing the other.

## Usage

1. Open **Contacts** and tap **Add contact**. Give the person a name and, if you like, a private
   note. The note never leaves your device.
2. Tap the new contact, then **Show invitation** on phone A. A QR code and a `TPM1-…` code
   appear. On phone B, add a contact and choose **Join with code**, then scan or paste it.
3. Keep both apps open for the first connection. The active card on **Chats** shows
   **Searching…**, then **Connected — direct over the internet** or **Connected — direct on the
   local network**.
4. Open the conversation and type. Message states are:

| State | Meaning |
|---|---|
| **Queued** | Stored locally; not yet handed to a connected peer |
| **Sent to peer** | The peer's transport stack acknowledged the bytes |
| **Received by peer** | The peer stored the message and returned an application-level ACK |
| **Not delivered** | The link closed before delivery; the message is kept and retried on the next connection |

5. Tap the paperclip to attach one or more files. Each file appears as a bubble with its size
   and live progress; tap a completed file to open it, or long-press to share it.
6. The chat scrolls to the bottom after **you** send a message or file, not when an incoming
   message arrives. If you are reading older messages, a down-arrow button jumps to the latest.
7. Rename any contact from the three-dot menu on the **Chats** or **Contacts** list. Renaming is
   local only: the other device keeps its own name for you.
8. Notifications are only posted for real incoming messages and completed files while no Knot
   screen is visible. Each conversation has its own notification and opening it clears that
   chat's alert. The permission is requested only from **Settings → Enable**; if it is denied,
   the Settings tab links to system settings.
9. **Settings** contains the System/Light/Dark theme, the notification-preview switch and the
   optional **Background connection** mode. Background mode uses a silent, ongoing notification
   because Android requires one for a foreground service. It is off by default; when off, Knot
   keeps the link for a short grace period after the last screen closes.
10. **About** links to the GitHub repository and the author's Telegram contact.

The chat subtitle shows the direct connection type while the channel is up. Only one contact is
the active connection target at a time; opening another contact switches the engine to it.

Use **Clear conversation** to cancel active transfers and delete local history plus the
app-private copies that belonged to it (the pairing survives), or **Unpair device** to remove
the invitation and require a new pairing. **Settings → Delete all contacts & data** removes every
contact, message, file and local setting.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

The JVM suite contains **86 tests** across crypto, protocol, files, DHT encoding, STUN, BEP 15
trackers, secure channel, handshake, a full loopback-UDP peer link, contact models and settings
policy. The tests use real sockets and real crypto, not mocks.

| Test class | Focus |
|---|---|
| `crypto.X25519Test` (3) | RFC 7748 vectors, low-order point rejection, key agreement |
| `crypto.CryptoTest` (5) | HKDF-SHA256 RFC 5869 vector, AES-GCM round trip, tamper rejection |
| `crypto.InviteTest` (5) | Invitation round trip, malformed codes, deterministic derived topic |
| `protocol.AppCodecTest` (20) | Text and file schema round trips, UTF-8 and length validation, filename sanitization, unknown types |
| `protocol.FileDigestTest` (4) | FNV-1a vectors, chunked and one-shot hashes agree, offset slices |
| `protocol.ChunkingTest` (5) | Out-of-order reassembly, duplicates, maximum-size messages, bounds and expiry |
| `net.SecureChannelTest` (5) | Encrypted frames, replay detection, tampering and truncation |
| `net.HandshakeTest` (7) | Mirrored keys, wrong invitation, tampered MAC, idempotent retransmission |
| `net.PeerLinkTest` (6) | Full loopback UDP link, bidirectional traffic, heartbeats, spoof resistance, incoming-only responder, ordered 2 MiB file-block transfer |
| `net.dht.BencodeTest` (5) | Nested bencode round trip, canonical order, malformed input |
| `net.rendezvous.StunClientTest` (2) | XOR-MAPPED-ADDRESS parsing, forged-transaction rejection |
| `net.rendezvous.TrackerRendezvousTest` (1) | Real BEP 15 connect/announce format, compact peer parsing, announce throttle |
| `data.ContactModelTest` (12) | Name/note sanitization, initials, search, pairing flags, chat sort order and preview formatting |
| `data.SettingsModelTest` (6) | Theme parsing, notification preview policy, background-service policy and defaults |

`:app:lintDebug` also completes with no lint errors. The build and artifact checks are recorded
in [`docs/TESTING.md`](docs/TESTING.md), along with
the **two-phone test matrix** that still needs to be completed on physical hardware. No
emulator or physical device was attached to the build machine, so installation, QR scanning
and real-world NAT traversal remain to be verified by the user.

## Project structure

```text
KNOT/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/dev/twoperson/messenger/
│       │   │   ├── crypto/       X25519, HKDF/HMAC, AES-GCM, invitations, topics
│       │   │   ├── protocol/     Versioned schema, validation, chunking, reassembly
│       │   │   ├── net/          UDP transport, handshake, secure channel, reliable link
│       │   │   ├── net/dht/      Bencode and minimal mainline-DHT rendezvous client
│       │   │   ├── net/rendezvous/  BEP 15 UDP trackers, STUN, shared-socket rendezvous
│       │   │   ├── data/         Contacts, SQLite history and transfers, settings, Keystore secrets
│       │   │   ├── engine/       Contact-scoped connection state machine, discovery, outbox, file streaming
│       │   │   ├── service/      Opt-in foreground service and per-conversation notifications
│       │   │   ├── util/         Activity visibility tracking and theme controller
│       │   │   └── ui/           Tabbed shell, chat list, contacts, settings, pairing, chat, about
│       │   └── res/              Material 3 light/dark themes, layouts, icons, FileProvider paths
│       └── test/                 86 JVM tests (crypto, protocol, files, rendezvous, loopback link, models)
├── docs/                         Architecture, protocol, security, testing, build, install
├── gradle/                       Gradle wrapper
├── signing/                      Local signing material (git-ignored)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LIMITATIONS.md
├── roadmap.md
└── README.md
```

## Documentation

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Layers, connection state machine, message flow, reliability and persistence |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Exact wire formats: invitation, handshake, frames, chunks and application messages |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, security checklist and honest gaps |
| [`docs/TESTING.md`](docs/TESTING.md) | Verified results, test matrix and on-device functional checks |
| [`docs/BUILD.md`](docs/BUILD.md) | Toolchain, build commands, signing and APK verification |
| [`docs/INSTALL.md`](docs/INSTALL.md) | Installing on a phone, including Samsung/One UI troubleshooting |
| [`docs/ROADMAP_DEVIATIONS.md`](docs/ROADMAP_DEVIATIONS.md) | How this native Kotlin build maps to `roadmap.md` |
| [`LIMITATIONS.md`](LIMITATIONS.md) | What the app cannot do, stated plainly |
| [`roadmap.md`](roadmap.md) | The original product and engineering roadmap |

## Limitations

These are properties of a direct-only, serverless messenger — not bugs to be fixed later by
adding a relay:

- **A direct path cannot exist on every network.** Carrier-grade NAT, symmetric NAT, strict
  corporate firewalls and some VPN combinations block inbound UDP. Knot reports the failure
  and keeps retrying.
- **Discovery needs either the DHT or a tracker/STUN server.** Networks that block all
  outbound UDP cannot discover peers; UDP trackers on ports 80/443 cover the common case where
  only the DHT's port 6881 is blocked.
- **Both people must have the app running.** There is no push notification and no server to
  hold messages or file bytes. A message or file sent while the peer is offline stays queued on
  the sender.
- **File transfers resume only against the same local history.** The receiver’s acknowledged
  offset lives in SQLite; clearing the conversation (or app data) cancels the transfer and
  removes the part file, so the next attempt starts from zero.
- **Some providers cannot report a file size.** Those files are copied once into app storage
  before sending, which requires enough free space for a second copy. Others expose a persisted
  URI; if that grant is later revoked, the message reports the file as unavailable.
- **The protocol caps a file at 4 TiB**, a name at 255 UTF-8 bytes and a MIME type at 128 bytes.
  Larger files are rejected with a distinct message.
- **Background delivery is opt-in and best-effort.** With **Background connection** enabled, a
  `dataSync` foreground service holds the link while the screen is off, but it cannot survive
  the device being powered off, the app being force-stopped, or long Doze/standby periods. With
  it off, Knot keeps the link for a short grace period after the last screen closes.
- **Notifications are only real events.** A high-importance alert appears only for a new
  incoming message or completed file while no Knot screen is visible. Each notification belongs
  to one conversation and is cleared when that chat is opened. If notification permission is
  denied, the app still works; the Settings tab links to system settings.
- **History and received files are local and not encrypted at rest.** Clearing app data or
  uninstalling deletes them permanently. There is no backup, export or sync.
- **The handshake is not externally audited.**
- **Metadata is visible to the network.** DHT nodes, UDP trackers, STUN servers and the active
  peer see your public IP/port while the engine is running.

The complete list is in [`LIMITATIONS.md`](LIMITATIONS.md).

## Roadmap

**Shipped in 1.1.0**

- Tabbed Material 3 shell with Chats, Contacts and Settings; dedicated contact, pairing, chat
  and About screens.
- Local contacts with search, private notes, per-contact invitations, chat previews, unread
  counts and rename/clear/delete actions. Only one direct connection is active at a time.
- Invitation pairing via QR code, paste and scan, with an independent secret per contact.
- Direct-only UDP transport with authenticated X25519 handshake and AES-256-GCM frames.
- Public rendezvous: UDP trackers (BEP 15), STUN and DHT, plus a same-subnet LAN beacon.
- Reliable outbox, application acknowledgements, duplicate suppression and reconnect backoff.
- Arbitrary-size streaming file transfer over the same encrypted link, with progress, resume,
  flow control and a 4 TiB protocol cap.
- Per-conversation message notifications, a privacy-preview switch and an opt-in background
  connection service with a silent, ongoing notification.
- System/light/dark themes, contact search, About page and destructive-data controls.
- SQLite history with contact-aware migration, pinned per-contact identity and
  Keystore-wrapped secrets.
- Release signing configuration for APK Signature Scheme v2 + v3.

**Next, in priority order**

1. Complete the two-phone network matrix in [`docs/TESTING.md`](docs/TESTING.md).
2. Add invitation expiry/revocation and a clearer re-pairing flow.
3. Evaluate encryption-at-rest for local history.
4. Commission an external review of the handshake construction.
5. Improve accessibility, localization and onboarding polish.

Items are candidates, not commitments. The non-negotiable constraint remains: **no relay and
no server that stores or forwards chat content**.

## Privacy by design

- No analytics, no crash reporting, no accounts and no advertising SDK.
- Contacts, message history, transfer state and per-contact pinned identities live in the app's
  private SQLite database. Received files and provider-size fallback copies live in app-private
  storage.
- The identity private key and each contact's invitation secret are wrapped with a
  non-exportable AES key from the Android Keystore.
- DHT nodes and UDP trackers learn only a derived topic hash plus your IP/port while the engine
  is running; STUN sees only the endpoint of the shared socket.
- Chat and file content is sent only to the one authenticated peer endpoint for the active
  contact.
- Clearing app data or uninstalling destroys the local history, received files, cached outgoing
  copies and the wrapped secrets.

## FAQ

**Is there a server?**
No app-operated server. Public DHT bootstrap nodes, UDP trackers and STUN servers help the two
devices find each other; they never carry chat content.

**Can I use Knot with more than one person?**
Yes, as separate one-to-one conversations. Add several contacts, pair each device and switch
between them from Chats or Contacts. Only one direct connection is active at a time, so opening
a different contact moves the connection target to that conversation.

**Does it work when the other person is offline?**
No. There is no store-and-forward server. The message stays queued on the sender and is
delivered after the peer reconnects.

**Are messages and files encrypted?**
Yes, in transit: X25519 key agreement, HKDF-SHA256 key derivation and AES-256-GCM frames carry
both text and file blocks. Local history and received files are not encrypted at rest.

**Can I send a large file?**
Yes. Files stream in 16 KiB blocks with flow control and resume after reconnection. The protocol
cap is 4 TiB; both devices must be online at the same time because there is no upload server.

**Why does it say “Unable to connect directly”?**
The two networks cannot establish a direct UDP path. Knot does not use a relay, so this is an
honest failure rather than a silent downgrade.

**Is it on Google Play?**
No. Build the APK from source and install it manually.

**Can I build it myself?**
Yes. See [Build from source](#build-from-source). JDK 17+, Android SDK 34 and the included
Gradle wrapper are all you need.

## Contributing

Contributions are welcome, especially around real-world network testing and documentation.

1. Fork the repository and create a focused branch.
2. Keep the no-relay, no-server constraint intact.
3. Run `./gradlew :app:testDebugUnitTest` before opening a pull request.
4. Never commit signing keys, keystores, passwords, message contents or invitation secrets.
5. Describe the network conditions and devices used for any connectivity change.

For security issues, please report them privately through GitHub's security advisory flow or
contact the maintainer directly instead of opening a public issue with exploit details.

## Author

**Farokh RashidAhmadi** ([@farokh-ra](https://github.com/farokh-ra))

Software developer focused on C#, PHP, HTML, CSS and Windows development — building useful
software, solving problems and always learning something new.

## Disclaimer

Knot is an experimental, independently developed Android application. It has not been
externally audited. Direct-only peer-to-peer networking cannot guarantee connectivity on every
network. Use it for conversations where both people understand and accept those constraints.
The software is provided as-is, without warranty of any kind.

---

<div align="center">

**Knot** — two devices, one private thread.

If this project is useful to you, consider starring the repository.

</div>
