# Local protocol reference

Roboff's control client talks directly to a numeric private/link-local LAN address on TCP **58867**. It binds sockets to Wi-Fi, including Wi-Fi without Internet access. `LocalClient` exposes a fixed method allowlist and contains no MQTT, HTTP API, account login, or cloud fallback. A separate HTTPS onboarding client retrieves local keys and home/room metadata during explicit setup. Discovery does not disclose the vacuum's local key. See [onboarding and offline authentication](OFFLINE_AUTH.md).

The implementation follows the supplied ESP32 and Python references and real S8 observations. It supports the V1 vacuum RPC schema with local transport versions `1.0` and `L01`. This is not a compatibility claim for every Roborock model: A01/B01 devices are unsupported, settings vary by firmware, and L01 has fixture/simulated transport coverage rather than hardware validation here.

## TCP frames

All integers are unsigned, big-endian on the wire. A four-byte length prefix counts the following frame, excluding the prefix itself. TCP reads must retain partial prefixes/bodies and handle multiple frames arriving together.

Offsets below start immediately after the length prefix:

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 3 | ASCII version: `1.0` or `L01` |
| 3 | 4 | Packet sequence |
| 7 | 4 | Random/nonce field |
| 11 | 4 | Unix timestamp in seconds |
| 15 | 2 | Protocol number |
| 17 | 2 | Encrypted payload length, when a payload is present |
| 19 | N | Encrypted payload |
| 19 + N | 4 | CRC32 of the frame through the encrypted payload, excluding the prefix |

Encrypted frames require an exact payload length, a valid CRC32, and successful decryption/padding or GCM tag verification. The payload length field limits encrypted data to 65,535 bytes. The reader also caps announced frames and decompressed gzip data at 1 MiB. CRC32 is a transmission checksum, not a cryptographic MAC.

Empty control frames differ from encrypted frames. The decoder accepts a bare 17-byte header, an explicit zero-length field, and the firmware's 21/23-byte empty forms. In particular, the real S8 hello reply ends with `00000000`; those bytes are **not** CRC32. The Python reference skips checksum validation for empty control payloads, and Roboff follows that behavior for protocols 0–3. Empty protocol-5 ACKs may also have a zero trailer; a nonzero supplied ACK CRC is checked.

## Handshake and message correlation

`connect()` first attempts `1.0`, then `L01` on a fresh socket if negotiation fails. Hello uses protocol **0**, sequence **1**, and a connect nonce in 10,000–32,767. A matching protocol-**1** reply completes negotiation; its random field supplies the L01 acknowledgement nonce. A successful hello alone does not validate the local key. The UI waits for an authenticated, readable `get_status` result before enabling controls.

Keepalive uses protocols **2**/ **3**, correlated by packet sequence. It runs every 10 seconds, with a five-second reply timeout. TCP connection attempts and RPC calls have seven-second timeouts; each hello has a five-second reply timeout.

RPC requests use header protocol **4** and UTF-8 JSON:

```json
{"dps":{"101":"{\"id\":12345,\"method\":\"get_status\",\"params\":[]}"},"t":1700000000}
```

The inner request is a JSON **string**, not a nested JSON object. RPC IDs use 10,000–32,767; ordinary packet sequences independently use 100,000–999,999. Responses are matched using the inner DPS-**102** RPC ID, not the packet sequence.

Real S8 behavior observed during a read-only host query:

1. A protocol-**5** response with a bare 17-byte header acknowledges transport receipt.
2. A later, encrypted header protocol-**4** message contains DPS **102** with the actual result.

The reader therefore accepts JSON under header protocols **4**, **5**, and **102**. An empty protocol-5 ACK never resolves an RPC, even if its sequence equals that RPC's ID. Unsolicited DPS updates go to the listener. Firmware error objects and non-`ok` string results raise `RpcException`; unsupported methods map to code `-32601`. An API rejection leaves the connection usable.

## Encryption

The local key is the existing device secret entered as exactly 16 UTF-8 bytes. It is not hex-decoded. Every derivation below uses byte concatenation; `BE32(x)` means four bytes in network byte order.

For both versions, convert the packet timestamp to eight lowercase hexadecimal ASCII characters. Rearrange those characters using zero-based indices `[5, 6, 3, 7, 1, 2, 0, 4]` to obtain `T`. The constant salt is ASCII `TXdfu$jyZ#TZHsg4`.

For `1.0`:

```text
AES key = MD5(T || UTF8(localKey) || salt)              # 16 bytes
payload = AES-128-ECB(PKCS#7(UTF8(JSON)))
```

Java's `AES/ECB/PKCS5Padding` provides the required 16-byte-block padding. A packet timestamp determines that packet's key; decoding uses the received timestamp.

For `L01`:

```text
AES key = SHA256(T || UTF8(localKey) || salt)           # 32 bytes
IV      = SHA256(BE32(sequence) || BE32(random)
                 || BE32(timestamp))[0:12]
AAD     = BE32(sequence) || BE32(connectNonce) || BE32(ackNonce)
          || BE32(random) || BE32(timestamp)
payload = AES-256-GCM(UTF8(JSON), IV, AAD)              # ciphertext || 16-byte tag
```

L01 has no PKCS padding. Its acknowledgement nonce must be available before encrypted RPCs are sent.

## Exposed commands and units

| Methods | Parameters |
| --- | --- |
| `get_status`, `get_consumable`, `get_clean_summary`, `get_room_mapping` | `[]` |
| `app_get_init_status`, `get_clean_motor_mode`, `get_dnd_timer`, `get_child_lock_status`, `get_sound_volume`, `get_carpet_mode` | `[]`; read-only capability and setting probes |
| `app_start`, `app_pause`, `app_stop`, `app_charge`, `find_me`, `resume_segment_clean` | `[]`; resume is offered only for a paused segment task |
| `set_dnd_timer` | `[start_hour,start_minute,end_hour,end_minute]` |
| `close_dnd_timer` | `[]` |
| `set_child_lock_status` | `{"lock_status":0 or 1}` |
| `change_sound_volume` | `[0..100]` |
| `set_carpet_mode` | `[{"enable":0 or 1,"stall_time":n,"current_low":n,"current_high":n,"current_integral":n}]`; values other than `enable` come from the vacuum's own `get_carpet_mode` reply |
| `app_rc_start`, `app_rc_stop`, `app_rc_end` | `[]` |
| `app_rc_move` | `[{"omega":radians_per_second,"velocity":meters_per_second,"duration":300,"seqnum":n}]` |
| `set_custom_mode`, `set_water_box_custom_mode`, `set_mop_mode` | `[integer mode]`; UI values target the S8 |
| `app_segment_clean` | `[{"segments":[16,17],"repeat":1,"clean_order_mode":0}]` |

Manual drive sends `app_rc_start`, then refreshes the motion vector every 300 ms using the S8 values used by the supplied ESP32 client (up to 0.3 m/s and π rad/s). Leaving the mode sends `app_rc_stop` followed by `app_rc_end`.

Maps, photos, cloud room names, cloud timers, firmware downloads, and account operations are outside this local command allowlist. `get_room_mapping` provides local segment IDs plus opaque room identifiers; Roboff resolves them against names imported during setup, with user-assigned local overrides taking precedence.

| Value | Wire unit/shape |
| --- | --- |
| `battery` | Integer percent, 0–100 |
| `clean_time`, maintenance work counters | Seconds |
| `clean_area` | Square millimetres; divide by 1,000,000 for m² |
| `get_status`, `get_consumable` | Object or a one-element array containing an object |
| `get_clean_summary` | Array beginning with time/area/count, or object with `clean_time`, `clean_area`, `clean_count` |

Unknown state/mode values must remain displayable without inventing a supported setting. Missing telemetry remains unavailable rather than inheriting an older snapshot.

## Ownership, cancellation, and checks

`connect()` and `request()` block and must run off the UI thread. A reader thread dispatches per-ID futures; writes are serialized independently, so replies may arrive out of order. Listener callbacks run on worker threads. The Activity guards callbacks and queued work with a generation token when changing devices or stopping.

Explicit `close()` is terminal: it closes connecting/reading sockets, stops keepalive, fails pending calls, and prevents a queued request from reconnecting that client. Reconnection creates a new client. Timed-out commands are never replayed automatically, and a lost reply does not establish whether a physical action ran.

Implementation: [LocalClient](../app/src/main/kotlin/com/roboff/lan/protocol/LocalClient.kt), [PacketCodec](../app/src/main/kotlin/com/roboff/lan/protocol/PacketCodec.kt). Tests cover independent Python V1/L01 encryption vectors, real S8 empty control framing, protocol-4 results after protocol-5 ACKs, corrupt frames, concurrent RPCs, and terminal cancellation.

