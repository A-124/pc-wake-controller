# Security model

PC Wake is a remote power controller, not a remote shell. Security is built
around pairing, signed requests, certificate pinning and a fixed command set.

## Assets and threats

| Asset | Protection |
|---|---|
| Pairing token | Entropy 256-bit, returned once; server stores only SHA-256 hash; Android stores ciphertext under Android Keystore AES-256-GCM |
| Server identity | SHA-256 pin of the DER leaf certificate, learned from the pairing QR; pinned clients reject any other certificate |
| Power authority | Fixed actions only; no command line or shell; server rate limits; confirmation in app; native Windows warning |
| Audit trail | JSONL logs for pairing, auth failures, commands, wake requests, revocations (no secrets) |
| Config | Agent state ACL: SYSTEM + Administrators; bridge state mode 0600; no plaintext tokens |

Threats considered: LAN attacker, malicious Wi-Fi, replay, stolen phone,
impostor server during pairing, unauthenticated internet scans, compromised
bridge/agent account, local user cancelling a mistaken shutdown.

## Pairing

- Pairing windows are opened only on the server host (Windows agent tray or
  bridge localhost admin CLI).
- Codes are 40-bit Crockford base32 (`XXXX-XXXX`), single-use, 5-minute TTL,
  invalidated after five failed attempts. Pairing is rate limited.
- The server generates a 16-byte device ID and 32-byte token. The token appears
  once in the response; only its SHA-256 hash is persisted.
- QR payloads include the certificate fingerprint. If the user types an address
  without a fingerprint, the Android app warns and uses trust-on-first-use
  (TOFU). On hostile networks, TOFU can be MITM'd; prefer the QR.
- Certificates: agent generates ECDSA P-256 self-signed with SANs. Bridge should
  use a real certificate (Tailscale/Let's Encrypt) or a generated self-signed
  certificate. Both are pinned by the app, so a changed certificate is an error,
  not a silent re-trust.

## Authenticated requests

Headers and canonical string are defined in [`PROTOCOL.md`](PROTOCOL.md).
Protection:

- HMAC-SHA256 over method, path+query, timestamp, nonce and body hash.
- Timestamp ±60 s; nonce replay cache 180 s per device.
- Constant-time signature comparison and token hash lookup.
- Per-device 60 requests/min; 10 auth failures/min/IP; bridge wake 20/min/device.
- 401 responses do not reveal which check failed.
- Each pairing window expires after 5 minutes; each signed request expires after
  60 seconds. Device credentials are long-lived until revoked (there is no silent
  refresh or infinite session token): the operator explicitly re-pairs after a
  revocation, host reinstall, or certificate rotation. Tokens are never rotated
  silently because there is no refresh channel; revocation is the session
  termination mechanism for this small self-hosted deployment.
- The Wake Bridge derives device tokens from a state-dir secret so it can verify
  HMACs across restarts while persisting only token hashes. Protect/back up
  `admin.json` (mode 0600); losing it requires re-pairing all devices.

Revocation: Android removes the local token and calls `/api/v1/revoke`. The
agent/bridge also supports host-side revocation. Revoking a bridge credential
does not unpair the Windows agent, and vice versa.

## Command surface

The Windows agent exposes exactly:

- `GET /api/v1/status`
- `POST /api/v1/power/shutdown|restart|sleep` with clamped grace 0–60 s
- `POST /api/v1/power/cancel`
- `POST /api/v1/revoke`
- pairing and health endpoints

Arguments are fixed (`shutdown.exe /s|/r /t … /c …`, `SetSuspendState`). There is
no arbitrary command execution, no upload/download endpoint, no shell, no
registry editor, no UAC bypass. The agent cannot unlock a locked PC or bypass
Windows security.

## Windows service hardening

- Runs as a Windows Service; LocalSystem is used because the power APIs require
  shutdown privilege. Functionality is heavily restricted to compensate; there is
  no interpreter interface and no user-controlled command string.
- Config/data under `%ProgramData%\PcWakeAgent` with ACLs limited to SYSTEM and
  Administrators. PFX password protected with machine-scope DPAPI.
- HTTPS only, pinned by clients; firewall rule only TCP 47101 (and optional UDP
  47100 discovery) on Private/Domain profiles.
- Tray admin commands use an administrators-only named pipe. A read-only status
  pipe allows the interactive session to cancel a pending shutdown.
- No hidden service installation: the installer/service name and firewall rules
  are visible; uninstall removes them.

The bridge runs as a non-root service with systemd hardening (`NoNewPrivileges`,
`ProtectSystem=strict`, restricted write path), binds only its HTTPS port, and
keeps its admin API on loopback.

## Data collection

None. No analytics, ads, accounts, telemetry or cloud backups. Android backup is
disabled for app data; configuration export is an explicit, passphrase-encrypted
file that excludes tokens/pins. Command history stays local until cleared.

## Residual risks

- A compromised phone can control paired PCs until tokens are revoked.
- A compromised bridge or agent host can perform power actions and read its own
  audit logs; secure the host.
- Manual TOFU pairing over an untrusted network is weaker than QR+fingerprint.
- Physical LAN attackers can also send unauthenticated magic packets — WoL wake
  itself is not confidentiality-protected; that only turns a PC on.
- Self-hosted TLS/hardware/OS patching is the operator's responsibility.
- Do not expose the agent to the internet without a private network or a reverse
  proxy and firewall; the protocol assumes HTTPS plus pinning.

## Incident response

1. Revoke the device in Android Settings and on the server/tray/bridge CLI.
2. Remove or rotate the server certificate (pins will fail and require re-pair).
3. Review agent/bridge audit logs for unexpected pairing or commands.
4. Rebuild from a trusted checkout; verify release hashes.
5. If the host is suspect, power it off physically and investigate.
