# Wire protocol (version 2)

All peer packets share a 6-byte header:

```
0..3  magic  "TPM" 0x02
4     kind   1 = initiation, 2 = confirmation, 3 = data, 4 = ack, 5 = control
5     version 0x02
```

Version 2 changed one thing that mattered in practice: the reliable sequence number moved
inside the encrypted DATA payload, so acknowledgements and heartbeats no longer share the
counter space with data frames. In version 1 an ACK or a heartbeat consumed a counter value
that the receiver's ordered pointer was waiting for, which stalled every later message and
then dropped the connection. A version 1 or version 2 peer can never half-interoperate: the
version byte is inside the handshake MAC, so a mismatch fails the handshake instead of
producing a corrupted stream.

## 1. Invitation and topic

- Invitation secret: 32 bytes from `SecureRandom`.
- Text form: `TPM1-XXXX-XXXX-…` (RFC 4648 base32, 52 characters in groups of 4).
- `authKey = HMAC-SHA256(secret, "tpmsg/v1/auth")` — proves possession of the invitation.
- `topic = HMAC-SHA256(authKey, "tpmsg/v1/topic")[0..19]` — a 20-byte DHT infohash. The raw
  secret is never published anywhere.

## 2. Handshake (symmetric, both sides send at once)

Initiation (118 bytes):

```
6    nonce            16 random bytes
22   ephemeral X25519 public key   32 bytes
54   identity X25519 public key    32 bytes
86   MAC              HMAC-SHA256(authKey, magic || kind || version || body[0..80])
```

Confirmation (70 bytes):

```
6    transcript hash  32 bytes
38   MAC              HMAC-SHA256(confirmKey, magic || kind || version || transcriptHash)
```

Key schedule:

```
shared        = X25519(my ephemeral private, peer ephemeral public)   (rejects all-zero)
transcript    = SHA-256("tpmsg/v1/transcript" || sorted(eph, nonce, identity) pairs)
salt          = SHA-256(authKey || transcript)
okm (64 B)    = HKDF-SHA256(ikm = shared, salt, info = "tpmsg/v1/keys")
confirmKey    = HKDF-SHA256(ikm = shared, salt, info = "tpmsg/v1/confirm", 32 B)
```

The side whose ephemeral public key sorts lower sends with the first 32 bytes of `okm` and
receives with the second; the other side mirrors. The identity public key is inside the
authenticated transcript, so a tampered key exchange fails, and the peer identity is pinned
after the first successful pairing.

## 3. Encrypted frames

```
0..3   magic           "TPM" 0x02
4      kind            3 data, 4 ack, 5 control
5      version         0x02
6..13  counter         big-endian per-direction AEAD counter (nonce only)
14..15 ciphertext len  big-endian
16..   ciphertext      AES-256-GCM(payload), 16-byte tag
```

Nonce = 4 zero bytes || counter (12 bytes). AAD = magic || kind || version || counter.
The receiver keeps a 128-frame replay window; a duplicate frame is re-acknowledged but not
delivered twice, and a frame that fails authentication is dropped silently.

The AEAD counter is **only** a nonce. It carries no ordering meaning, which is why control
frames can be sent or lost between data frames without affecting delivery.

Plaintext payloads:

```
data      (3): reliable sequence u64 || chunk (see section 4)
ack       (4): cumulative data sequence u64 || 32-bit selective bitmap u32
control   (5): 0x01 heartbeat
```

The data sequence is a per-direction, per-link counter starting at 0 and increasing by one
for every DATA frame, independent of kind. The receiver delivers strictly in sequence,
buffers up to 96 out-of-order frames, and acknowledges cumulatively plus a 32-bit bitmap.
A duplicate data sequence is acknowledged again and not redelivered. `control` frames are
never acknowledged and never retransmitted: they exist only to keep the path warm and to
let each side see authenticated traffic.

## 3a. Keeping the link up

- Retransmission backs off exponentially per frame: 400 ms, 800 ms, 1.6 s, 3.2 s, then every
  5 s, up to 14 attempts (~56 s of a fully stalled path).
- A heartbeat is sent after 3 s of outgoing silence; a link closes only after 30 s without
  any authenticated frame from the peer.
- Every authenticated frame (data, ack, control, duplicate) updates the peer endpoint. A NAT
  rebind or a switch between Wi-Fi and mobile data therefore changes the address without
  dropping the link, because only the holder of the session keys can move the endpoint.
- A valid initiation carrying a *new* ephemeral key means the peer restarted its link; the
  old link closes immediately with `peer_restarted` instead of staying deaf until timeout,
  and the engine redials the address the initiation came from.

## 4. Chunks

```
0..3   chunk id (u32)
4..5   chunk index
6..7   chunk count (1..64)
8..    payload, at most 480 bytes
```

A reassembler completes a message only when every chunk arrived; assemblies expire after
60 s, at most 8 are pending, and the total size is capped at 16 KiB.

## 5. Application messages

```
hello  (1): type u8, version u16, device id 16 B, identity public key 32 B, name len u8, name
text   (2): type u8, message id 16 B, sequence u64, created-at ms u64, body len u16, UTF-8 body
ack    (3): type u8, message id 16 B
```

Validation rules enforced on decode (each returns "invalid" rather than coercing):

- `hello.version` must equal 2 (the app rejects a peer that completed the handshake with a
  different application version rather than guessing);
- device id 16 bytes, public key 32 bytes, name ≤ 64 bytes;
- text body must be valid UTF-8, non-empty, ≤ 8000 bytes and ≤ 2000 code points;
- `created_at` must be between 2001-09-09 and 2100-01-01 (clock-abuse bound);
- exact remaining-length checks for every field; unknown type bytes are rejected;
- total message ≤ 16 KiB.

## 6. Discovery messages

All discovery uses the same UDP socket as the peer link, so the NAT mapping peers learn is the
mapping chat packets leave from.

### DHT

Standard BEP 5 KRPC (`find_node`, `get_peers`, `announce_peer`) with `info_hash = topic` and
`implied_port = 1`, so nodes record the observed UDP endpoint.

### STUN

A single RFC 5389 binding request (20 bytes: type `0x0001`, length 0, magic `0x2112A442`,
12-byte random transaction id). The reply's `XOR-MAPPED-ADDRESS` (or `MAPPED-ADDRESS`) gives the
public IPv4 endpoint of the socket. No attribute beyond the mapped address is used, and no
STUN server ever sees message content.

### UDP trackers (BEP 15)

Connect and announce use the standard BEP 15 layout, with `info_hash = topic`:

```
connect request   16 B: protocol id 0x41727101980 (8) || action 0 (4) || txid (4)
connect response  16 B: action 0 (4) || txid (4) || connection id (8)
announce request  98 B: connection id (8) || action 1 (4) || txid (4) || info_hash (20)
                        || peer id (20) || downloaded (8) || left (8) || uploaded (8)
                        || event (4) || ip (4, zero) || key (4) || num_want (4, -1) || port (2)
announce response >=20 B: action 1 (4) || txid (4) || interval (4) || leechers (4)
                        || seeders (4) || compact IPv4 peers (6 bytes each)
```

The advertised port is the STUN-learned public port when available, otherwise the local socket
port. `event = started` is sent on the first announce and `event = none` afterwards. Responses
are matched to the request by transaction id *and* source address. Trackers ignore the
`announce` event semantics; only the peer list is used.

### LAN beacon

A 27-byte broadcast on UDP port 41999:

```
"TPML" 0x01 || topic (20 bytes) || primary UDP port (u16)
```

It carries no secret and no message content.
