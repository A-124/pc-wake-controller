# PC Wake Controller - Wake Bridge

The Wake Bridge is the always-on LAN component of the PC Wake Controller. The
Android app pairs with it over HTTPS and asks it to send Wake-on-LAN Magic
Packets to an allow-listed set of PCs. It implements wire protocol v1
(`../documentation/PROTOCOL.md`), role `bridge`.

Linux code and deployment here are distribution-agnostic; Raspberry Pi OS and
any systemd distribution work. Runtime is modern Node.js (20+), with **zero npm
dependencies** and no build step.

The bridge only *sends* wake packets. It cannot shut down, restart or sleep a
PC; that requires the Windows Agent. `sent` in API responses means UDP
datagrams were written, never that a PC booted.

## Quick start (Linux / Raspberry Pi)

Install Node.js 20 or newer first (for example from NodeSource; the Node.js
package in older Raspberry Pi OS releases may be too old). No npm packages are
needed for this project.

```sh
# 1. Copy this directory to the host (for example /opt/pcwake-bridge).
cd wake-bridge

# 2. Create a self-signed ECDSA P-256 certificate with SANs.
./deploy/gen-cert.sh 192.168.1.10 ./certs

# 3. Create config and edit name, publicHost and targets.
cp config.example.json config.json
$EDITOR config.json

# 4. Run in the foreground.
node src/index.js --config config.json
# HTTPS on 0.0.0.0:8443; admin API on 127.0.0.1:47111.

# 5. In another terminal, open a pairing window and print the QR/paste JSON.
node src/cli.js pair --config config.json --label "Pixel"
```

The `pair` command prints one single-line JSON payload (the exact
`PROTOCOL.md` section 3.1 shape), followed by human instructions and the
certificate SHA-256 fingerprint. In the Android app choose Pair, then scan or
paste that JSON line. The window is valid for 5 minutes, single-use, and the
code is invalidated after 5 failed attempts.

For production use, install the hardened systemd unit:

```sh
sudo ./deploy/install.sh
sudo systemctl start pcwake-bridge
sudo systemctl status pcwake-bridge
```

The installer copies files to `/opt/pcwake-bridge`, creates `/etc/pcwake-bridge`
and `/var/lib/pcwake-bridge` (0700), installs and enables
`deploy/pcwake-bridge.service`, and prints certificate/firewall guidance. It
does not run npm or start the service before you have configured TLS.

## Configuration

`config.example.json` is validated strictly at startup. Unknown keys, duplicate
MACs or ids, invalid MAC/broadcast/port/repeat values and relative paths that
cannot be resolved produce a clear error and a non-zero exit.

| field | meaning |
|---|---|
| `name` | Display name returned in pairing and status. |
| `listenHost` / `listenPort` | HTTPS bind address. Default `0.0.0.0:8443`. Port `0` is accepted for development/tests only (OS chooses). |
| `publicHost` | Host/IP placed in pairing payloads. If omitted and the listen host is a wildcard, the first non-internal interface address is used. |
| `tlsCertPath` / `tlsKeyPath` | PEM certificate and private key. Required; plaintext HTTP is not supported. Relative paths resolve against the config file. |
| `stateDir` | Directory for `devices.json`, `admin.json` and `audit.log` (0700). Default `./state`. |
| `adminPort` | Loopback-only admin listener. Default `47111`; always bound to `127.0.0.1`. `0` chooses an ephemeral port (tests). |
| `targets` | Allow-list. Each has `id` (lowercase slug), `name`, `mac`, `broadcast` (IPv4), `port` (default 9), `repeats` (1..10, default 3). |
| `rateLimits` | `authPerMinutePerDevice` (60), `authFailuresPerMinutePerIp` (10), `wakesPerMinutePerDevice` (20), `pairAttemptsPerMinutePerIp` (30). |
| `auditMaxBytes` | Rotate `audit.log` to `audit.log.1` above this size. Default 5 MiB. |

Only MACs in `targets` can be woken. The broadcast address and UDP port are
always taken from the target configuration; values sent by clients are ignored.
For a PC on the same subnet use its directed broadcast (for example
`192.168.1.255`). WoL broadcasts do not cross routers/VLANs unless the network
is configured with a directed-broadcast helper.

## Certificates and pinning

The bridge **requires TLS** and refuses to start if the certificate or key is
missing, empty, malformed or mismatched. There is no insecure HTTP mode.

Clients pin the SHA-256 of the DER leaf certificate (lowercase hex). The pair
payload carries that fingerprint, and `node src/cli.js pair` prints it. Changing
the certificate is a hard error in the app until the user re-pairs.

### Self-signed (LAN)

```sh
./deploy/gen-cert.sh 192.168.1.10 ./certs
```

`gen-cert.sh` creates an ECDSA P-256 self-signed certificate with SANs for the
supplied host/IP plus localhost/127.0.0.1/::1, writes the key as 0600 and the
certificate as 0644, and prints the lowercase SHA-256 fingerprint. Example
config:

```json
"publicHost": "192.168.1.10",
"tlsCertPath": "./certs/bridge-cert.pem",
"tlsKeyPath": "./certs/bridge-key.pem"
```

When running as the `pcwake` service user, make the key group-readable:
`chown root:pcwake certs/bridge-key.pem && chmod 0640 certs/bridge-key.pem`.
The certificate itself can stay 0644. Do not regenerate the certificate
casually; every paired client would need to re-pair.

### Tailscale

Tailscale provides a publicly trusted certificate for a tailnet name and
encrypts transport, while the app still pins the certificate:

```sh
sudo tailscale cert wake-bridge.tailxxxx.ts.net
# point tlsCertPath/tlsKeyPath at the .crt/.key
# set "publicHost": "wake-bridge.tailxxxx.ts.net"
```

Use Tailscale Serve/Funnel only with care: the bridge speaks TLS itself, so
point clients directly at the bridge's tailnet HTTPS endpoint. Do not expose an
insecure proxy in front of it. Tailscale ACLs should restrict port 8443 to your
devices.

## API summary

- `GET /health` (unauthenticated): `{"ok":true,"service":"pcwake-bridge","version":"1.0.0","kind":"bridge"}`. No host details or pairing state.
- `POST /api/v1/pair` (unauthenticated, rate limited): body `{"code":"XXXX-XXXX","deviceName":"Pixel 8"}`. Returns `deviceId`, the one-time `token`, and `server` with `kind`, `name`, `version` and public `targets` (`id`,`name`,`mac` only).
- `GET /api/v1/status` (authenticated): name, kind, version, online, server time, targets with broadcast/port.
- `POST /api/v1/wake` (authenticated): `{"mac":"AA:BB:CC:DD:EE:FF","repeats":3}`. Unknown MAC -> `403 target_not_allowed`. Repeats are clamped to 1..10. Success returns `{"ok":true,"target":"...","sent":N,"port":9,"broadcast":"...","at":...}`; `sent` counts datagrams written.
- `POST /api/v1/revoke` (authenticated): `{"ok":true}`; the calling device is revoked immediately.

Authenticated requests carry `X-PCWake-Device`, `X-PCWake-Timestamp`,
`X-PCWake-Nonce`, `X-PCWake-Signature` and are HMAC-SHA256 signed over the exact
canonical string from protocol section 4. The server enforces +/-60 seconds,
a 180-second nonce replay cache, a 60 requests/minute per-device limit and
10 authentication failures/minute per source IP. Errors use
`{"ok":false,"error":"..."}`; auth failures are always `401 unauthorized`.

## Management CLI

The CLI reads `<stateDir>/admin.json` (admin token and current port) and talks
only to `127.0.0.1`; the admin API is never mounted on the public HTTPS server.

```sh
node src/cli.js pair [--label "Pixel"]   # start 5-min single-use window
node src/cli.js status                   # status, fingerprint, pairing window
node src/cli.js devices                  # paired devices (no token hashes)
node src/cli.js revoke <deviceId>        # revoke a device
node src/cli.js --help
```

The service must be running. Only `pair` starts a pairing window; there is no
HTTP endpoint that can open one. The CLI also accepts `--config <path>` and the
`PCWAKE_CONFIG` environment variable.

## Firewall advice

- Expose **only TCP 8443** (or your configured port), and only to your LAN or
  Tailscale interface. Clients pin the certificate, so TLS interception or an
  untrusted proxy is not acceptable.
- Never expose `47111`: it is bound to loopback and protected by a random admin
  token, but it is for local management only.
- Never expose UDP WoL ports (usually 9 or 7) to the internet. The bridge only
  sends packets from the LAN; it does not listen for WoL. Do not port-forward
  the admin API or the WoL broadcast domain.
- On a Raspberry Pi, use a stable DHCP reservation or static address for
  `publicHost`/DNS.

## Tests

```sh
npm test
```

The suite uses Node's built-in test runner and requires no third-party packages.
It spins up real HTTPS listeners with a clearly marked **test-only** certificate
in `test/fixtures/`, exercises pairing/auth/revocation over the wire, sends real
UDP datagrams to a local listener and asserts the exact 102-byte Magic Packet,
and covers the normative protocol test vectors, replay/skew/tamper rejection,
rate limits, config validation, audit rotation, state-at-rest and the CLI
pairing path. The test certificate is for tests only; never deploy it.

`node tools/gen-vectors.js` regenerates the section 8 vectors.

## Security notes

- Pairing codes exist only in memory and only as SHA-256 hashes; plaintext codes
  are returned once by the admin `pair` call and printed by the CLI. Codes are
  never written to logs or the audit file.
- Device tokens are returned once. `devices.json` stores only
  `sha256(token)`; the raw token never reaches disk. `admin.json` is mode 0600
  and holds the random admin token plus the state-dir administrative secret.
- **Protocol note:** PROTOCOL.md requires server-side HMAC verification using
  the raw token, while also requiring that only SHA-256(token) be persisted.
  Those constraints cannot both be met with a literally random opaque token.
  This implementation derives each 32-byte token as
  `HMAC-SHA256(tokenKey, deviceId)` and stores only its hash. The token is
  returned once, is unpredictable without the 0600 administrative secret, and
  remains verifiable across restarts without any raw token on disk. Back up
  `admin.json`; losing it means every device must re-pair.
- The admin token comparison is constant-time and the admin listener is hard
  bound to `127.0.0.1`; admin API calls are additionally checked for a loopback
  peer address. Anyone who can read `admin.json` as root can control the bridge.
- Audit logging is JSONL with size rotation. Accepted and rejected wake
  requests record device id, IP, MAC, target and result; raw tokens/codes are
  never recorded.
- The systemd unit uses a dedicated user, an empty capability set,
  `NoNewPrivileges`, `ProtectSystem=strict` and a single writable state path.
  `MemoryDenyWriteExecute` is deliberately not enabled because V8's JIT needs it.

## Limitations

- Wake only. Shutdown/restart/sleep/cancel belong to the Windows Agent.
- No boot confirmation. `sent` is a transport-level result only.
- Wake-on-LAN must be enabled in the PC firmware/NIC and the network must deliver
  the configured broadcast. Some NICs require magic-packet mode to remain armed
  after a full power loss.
- Broadcasts are normally limited to the local subnet. A single bridge can reach
  only networks its host can route/broadcast to.
- No discovery (agent-only feature), no web UI, no multi-bridge clustering.
- State is local files; back up `admin.json` and `devices.json` together for
  disaster recovery. Devices are otherwise durable across restarts.
