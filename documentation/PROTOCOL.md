# PC Wake Controller — Wire Protocol v1

This document is the authoritative contract between the Android app, the Windows
Agent, and the Wake Bridge. All three implementations MUST match it. Test vectors
at the end are normative.

## 1. Transport and certificate pinning

- HTTPS only. The Windows Agent serves HTTPS on TCP **47101** by default.
  The Wake Bridge defaults to TCP **8443**.
- The Android app pins the **SHA-256 of the DER-encoded server leaf certificate**
  (lowercase hex, no separators).
- The pin is learned from the pairing payload / QR code. If a user types the
  address manually, the app performs trust-on-first-use and MUST display a
  warning before saving the pin.
- The pinned pin is mandatory for every subsequent request. A changed
  certificate is a hard error (possible MITM) until the user re-pairs.
- Servers SHOULD use ECDSA P-256 self-signed certificates with SANs for their
  LAN addresses, or a publicly trusted certificate (also pinned).
- No endpoint other than `/health` and `/api/v1/pair` may be exposed without
  request authentication. No unauthenticated UDP Wake-on-LAN port may be
  exposed to the public internet.

## 2. Roles and devices

- `serverKind`: `agent` or `bridge`.
- Each server has a stable `serverId`? No — servers are identified by `kind` +
  canonical `endpoint` (`https://host:port`). A pairing creates a credential
  bound to that endpoint and pin.
- Device credentials are issued by the server at pairing time:
  - `deviceId`: 16 random bytes, lowercase hex (32 chars).
  - `token`: 32 random bytes, base64url without padding (43 chars). The token is
    returned exactly once. Servers persist only `SHA-256(token)` (lowercase hex).

## 3. Pairing

Pairing is enabled only in a short window:

- Windows Agent: started from the tray (administrator), valid 5 minutes,
  single-use code.
- Wake Bridge: created with the local admin CLI, valid 5 minutes, single-use.

### 3.1 Pairing payload (QR / shareable text)

A single-line JSON object:

```json
{"v":1,"kind":"agent","name":"DESKTOP-7F3A","host":"192.168.1.50","port":47101,"fingerprint":"<sha256 hex>","code":"K7Q2-M4XP"}
```

Fields:

| field | type | notes |
|---|---|---|
| v | int | protocol version, `1` |
| kind | string | `agent` or `bridge` |
| name | string | display name |
| host | string | IPv4, IPv6, or DNS name |
| port | int | 1..65535 |
| fingerprint | string, optional | cert pin; required for secure manual entry |
| code | string, optional | one-time pairing code, format `XXXX-XXXX` (Crockford base32, no I/L/O/U) |

The app accepts the payload from QR or pasted text. It may also accept host,
port, code, and optional fingerprint entered manually.

### 3.2 Pair request

`POST /api/v1/pair` — no authentication headers, rate limited.

```json
{
  "code": "K7Q2-M4XP",
  "deviceName": "Pixel 8",
  "deviceModel": "Google Pixel 8",
  "appVersion": "1.0.0"
}
```

Success `200`:

```json
{
  "ok": true,
  "deviceId": "<hex32>",
  "token": "<base64url43>",
  "server": {
    "kind": "agent",
    "name": "DESKTOP-7F3A",
    "version": "1.0.0",
    "agentVersion": "1.0.0",
    "hostName": "DESKTOP-7F3A",
    "adapters": [
      {"name": "Ethernet", "mac": "AA:BB:CC:DD:EE:FF", "ipv4": ["192.168.1.50"]}
    ],
    "targets": null
  }
}
```

For a bridge, `server.targets` is an array of allow-listed targets (no secrets):

```json
"targets": [{"id":"gaming-pc","name":"Gaming PC","mac":"AA:BB:CC:DD:EE:FF"}]
```

Errors use a JSON envelope: `{"ok":false,"error":"invalid_code"}` with HTTP
`400`, `401` (bad/expired code), `409` (pairing already in progress/closed),
`429` (too many attempts).

Codes are single-use, expire after 300 seconds, and are consumed only on a
successful pairing. More than 5 failed attempts invalidate the current code.
The agent exposes POST `/api/v1/pair/start`? No — pairing windows are started
out-of-band via the tray/admin socket only; pairing HTTP surface is just
`/api/v1/pair`.

## 4. Authenticated requests

Every authenticated request carries:

```
X-PCWake-Device: <deviceId>
X-PCWake-Timestamp: <decimal unix seconds>
X-PCWake-Nonce: <base64url, >= 16 random bytes>
X-PCWake-Signature: <base64url HMAC-SHA256(token, canonical)>
```

### 4.1 Canonical string (exact bytes)

```
METHOD + "\n" +
PATH_WITH_QUERY + "\n" +
TIMESTAMP + "\n" +
NONCE + "\n" +
LOWERCASE_HEX(SHA-256(bodyBytes))
```

- `METHOD`: uppercase, e.g. `POST`.
- `PATH_WITH_QUERY`: starts with `/`, exactly the request-target sent on the
  wire, query preserved verbatim.
- `TIMESTAMP`: same decimal string as the header.
- `NONCE`: same string as the header.
- `bodyBytes`: exact request body bytes; empty byte string for requests without
  a body (hash of empty input, not of `"{}"`).
- HMAC output is base64url without padding.

### 4.2 Validation rules (servers)

- Timestamp must be within ±60 seconds of server time.
- Nonce must be 16..64 base64url characters, unused for this device within the
  last 180 seconds (replay cache), and stored after signature verification.
- Signature comparison is constant-time.
- Device must exist and not be revoked. Token hash is recomputed over the raw
  token; servers never store raw tokens. Implementations may derive the token
  deterministically from a server-held secret and the device ID (the Wake
  Bridge does) so the raw token can be verified after restarts while only
  `SHA-256(token)` is persisted. This is an internal storage choice; it does not
  change the wire format or client behavior.
- Limits per device: 60 authenticated requests/minute (status polling uses
  far fewer). Authentication failures: 10/minute per source IP, then HTTP 429.
- On success, update `lastSeenAt` (agent devices) and audit log.
- Errors: `401 {"ok":false,"error":"unauthorized"}` for any auth failure;
  avoid leaking whether deviceId, timestamp or signature failed.
  `429 {"ok":false,"error":"rate_limited"}`.

All responses are JSON, `Content-Type: application/json; charset=utf-8`.
Authenticated responses include `"ok": true` where a body is returned.

## 5. Windows Agent API

Base: `https://<host>:<port>`.

### GET /health (unauthenticated)

```json
{"ok":true,"service":"pcwake-agent","version":"1.0.0","kind":"agent"}
```

No host details, no pairing state.

### GET /api/v1/status (authenticated)

```json
{
  "ok": true,
  "name": "DESKTOP-7F3A",
  "hostName": "DESKTOP-7F3A",
  "os": "Windows 11 Pro 23H2 (22631)",
  "agentVersion": "1.0.0",
  "online": true,
  "uptimeSeconds": 123456,
  "adapters": [
    {"name": "Ethernet", "mac": "AA:BB:CC:DD:EE:FF", "ipv4": ["192.168.1.50"], "ipv6": []}
  ],
  "capabilities": ["shutdown", "restart", "sleep", "cancel"],
  "pendingCommand": null,
  "lastCommand": null
}
```

`pendingCommand` / `lastCommand`:

```json
{"id":"<hex>","action":"shutdown","requestedAt":1720000000,"executeAt":1720000015,"status":"pending|executed|failed|cancelled","graceSeconds":15,"error":null}
```

`online` reflects the agent process, not a wake confirmation.

### POST /api/v1/power/shutdown|restart|sleep (authenticated)

Body:

```json
{"graceSeconds": 15, "reason": "PC Wake Controller remote shutdown"}
```

- `graceSeconds`: integer 0..60. Default 15 for shutdown/restart, 3 for sleep.
- `reason`: optional, max 200 printable chars; sanitized; used in the native
  Windows notification/comment where supported.

Success `202`:

```json
{"ok":true,"command":{"id":"<hex>","action":"shutdown","requestedAt":1720000000,"executeAt":1720000015,"status":"pending","graceSeconds":15,"error":null}}
```

Only one pending power command at a time; a second request returns `409
{"ok":false,"error":"command_pending"}` unless it is `cancel`.

Actual behavior:

- shutdown: fixed `shutdown.exe /s /t <grace> /d p:4:1 /c "<reason>"` invocation.
  Windows itself displays the shutdown warning to interactive users.
- restart: `shutdown.exe /r /t <grace> /d p:4:1 /c "<reason>"`.
- sleep: waits 3 seconds (to deliver the response), logs, shows tray
  notification if the tray is running, then calls
  `SetSuspendState(FALSE, FALSE, FALSE)`.
- The agent never accepts arbitrary command lines or shell text.

### POST /api/v1/power/cancel (authenticated)

Body `{}`. Cancels a pending shutdown/restart via `shutdown.exe /a`.

```json
{"ok":true,"cancelled":true}
```

`sleep` cannot be cancelled once the process suspends; the endpoint returns
`{"ok":true,"cancelled":false}`.

### POST /api/v1/revoke (authenticated)

Revokes the calling device. Response `{"ok":true}`. Server also supports
revocation from the tray admin UI.

### Discovery (UDP, LAN only)

- Port **47100** (configurable). Probe payload (ASCII, exact):
  `PCWAKE_DISCOVER_V1`
- Agent replies unicast JSON:

```json
{"v":1,"kind":"agent","name":"DESKTOP-7F3A","port":47101,"paired":true}
```

No secrets, no MACs, no fingerprint. Rate limit: 1 response/second per source.
Discovery can be disabled in agent config.

## 6. Wake Bridge API

Same pairing and authentication semantics as the agent.

### GET /health (unauthenticated)

```json
{"ok":true,"service":"pcwake-bridge","version":"1.0.0","kind":"bridge"}
```

### GET /api/v1/status (authenticated)

```json
{
  "ok": true,
  "name": "Home Wake Bridge",
  "kind": "bridge",
  "version": "1.0.0",
  "online": true,
  "time": 1720000000,
  "targets": [
    {"id":"gaming-pc","name":"Gaming PC","mac":"AA:BB:CC:DD:EE:FF","broadcast":"192.168.1.255","port":9}
  ]
}
```

### POST /api/v1/wake (authenticated)

```json
{"mac": "AA:BB:CC:DD:EE:FF", "repeats": 3}
```

- `mac`: only allow-listed MACs may be woken; otherwise `403
  {"ok":false,"error":"target_not_allowed"}`.
- `repeats`: optional 1..10, default target config (or 3).
- The bridge sends the Magic Packet only to the broadcast/port from its own
  target configuration. Client-supplied broadcast/port are not accepted.
- Response `200`:

```json
{"ok":true,"target":"gaming-pc","sent":3,"port":9,"broadcast":"192.168.1.255","at":1720000000}
```

- `sent` means UDP datagrams were written, **not** that the PC booted.
- Per-device rate limit: 20 wakes/minute; failed auth as in §4.2.
- Audit every accepted and rejected wake request (device id, MAC, result, IP).

### POST /api/v1/revoke (authenticated)

Same as agent.

## 7. Android local Wake-on-LAN

- Magic packet: 6 bytes `0xFF`, followed by the target MAC bytes repeated 16
  times (total 102 bytes).
- Default UDP port: 9. Allowed ports: 1..65535.
- Directed broadcast from the device profile, e.g. `192.168.1.255`; the socket
  enables `SO_BROADCAST`. Interface binding is optional. Repeats: default 3,
  interval 500 ms (settings).
- The app reports `sent` / `datagrams` only and never claims the PC booted.
- Multiple PCs, multiple profiles supported.

## 8. Test vectors (normative)

Token (UTF-8): `test-token-0123456789`

Request A (POST with body):
- Method: `POST`
- Path: `/api/v1/power/shutdown`
- Timestamp header: `1720000000`
- Nonce: `AAECAwQFBgcICQoLDA0ODw`
- Body bytes (exact, UTF-8): `{"graceSeconds":15}`
- Body SHA-256 hex:
  `41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5`
- Canonical:
  `POST\n/api/v1/power/shutdown\n1720000000\nAAECAwQFBgcICQoLDA0ODw\n41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5`
- Signature (base64url):
  `qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ`

Request B (GET, empty body):
- Method: `GET`
- Path: `/api/v1/status`
- Timestamp: `1720000000`
- Nonce: `AAECAwQFBgcICQoLDA0ODw`
- Empty body SHA-256:
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- Signature (base64url):
  `6S_CySELEsBTeWGBaBgzKQqWjAkz3CMQTwxPMNKyVUI`

These vectors are authoritative and must be asserted by all three test suites.
They can be re-generated with `wake-bridge/tools/gen-vectors.js`.

## 9. Pairing code alphabet

Crockford base32 without `I L O U`; displayed uppercase as `XXXX-XXXX`
(8 symbols, 40 bits). Normalization: uppercase, remove `-`/spaces; ambiguity map
`I,L -> 1`, `O -> 0`, `U -> V`.
