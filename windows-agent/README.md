# PC Wake Controller — Windows Agent

Windows companion for [PC Wake Controller](../README.md): a Windows Service that
executes authenticated shutdown/restart/sleep commands and reports honest agent
health, plus an interactive tray companion for pairing and local administration.

- **Service:** ASP.NET Core HTTPS API on TCP `47101`, Windows Service
  (`PcWakeAgent`), automatic start, fixed power-command surface.
- **Tray:** pairing QR/code, service/device status, pending-command indicator and
  cancel button.
- **Discovery:** optional UDP `47100` responder for the Android app.
- **Protocol:** [`documentation/PROTOCOL.md`](../documentation/PROTOCOL.md).

The agent is not a remote shell. It exposes only status, pairing, revoke and
`shutdown` / `restart` / `sleep` / `cancel`. No arbitrary command execution,
file transfer, registry editing or UAC bypass exists.

## Requirements

- Windows 10/11 on x64, Windows PowerShell 5.1+.
- .NET 8 SDK to build; the published installer is self-contained and needs no
  .NET runtime on the target PC.
- Administrator rights to install the service and firewall rules.
- Wired Ethernet on the target PC for Wake-on-LAN. See the
  [BIOS + Windows Wake-on-LAN guide](../documentation/BIOS_WOL.md).

## Build and test

From the repository root:

```powershell
cd windows-agent
dotnet build -c Release
dotnet test -c Release
```

If using the repository's workspace-local SDK:

```powershell
$env:DOTNET_ROOT = "$PWD\..\.tools\dotnet"
& "$env:DOTNET_ROOT\dotnet.exe" test PcWake.Agent.sln -c Release
```

Tests cover the normative HMAC vectors, auth/replay/skew/rate-limit rejection,
pairing expiry and lockout, state/audit redaction, grace clamping, fixed command
arguments, cancel semantics, and HTTP pairing/status/revoke/power integration
with a fake power controller. No test invokes real shutdown, restart or sleep.

Run without installing a service for local diagnosis (elevated shell):

```powershell
.\artifacts\publish\service\PcWake.Agent.Service.exe --console
```

## Installer

Build the MSI after publishing the service and tray:

```powershell
# From an elevated shell if .tools\wix needs restoring
.\installer\Build-Installer.ps1
```

Outputs:

- `artifacts/publish/service/PcWake.Agent.Service.exe`
- `artifacts/publish/tray/PcWake.Agent.Tray.exe`
- `artifacts/msi/PcWake.Agent.1.0.0.msi`

The MSI (`perMachine`) installs the service with automatic start, starts it, adds
a Start Menu shortcut, opens firewall ports `47101/tcp` and `47100/udp` on
Private/Domain profiles, and removes the service/firewall rules on uninstall. It
is not code-signed in this repository's local build; Windows may show a
SmartScreen prompt. Verify the release SHA-256 before running.

Silent install:

```powershell
msiexec /i .\artifacts\msi\PcWake.Agent.1.0.0.msi /qb
```

### Fallback installer (no MSI)

```powershell
.\installer\install.ps1            # elevated
.\installer\uninstall.ps1          # elevated
```

`install.ps1` performs the same setup with `sc.exe`/`netsh`, restricts
`%ProgramData%\PcWakeAgent` to SYSTEM + Administrators, creates the Start Menu
shortcut and starts the service. It requires the publish outputs to exist.

## Pairing

1. Launch the tray:
   `C:\Program Files\PcWake Agent\PcWake.Agent.Tray.exe` (Start Menu shortcut:
   **PC Wake Agent**).
2. Choose **Pair device**. The tray requests administrator elevation for local
   pairing administration; UAC is never bypassed.
3. Scan the QR from **Android app → Device pairing → Windows agent** or enter
   host, port, one-time code and the certificate fingerprint manually.
4. The pairing window is single-use and expires after 5 minutes. Paired device
   tokens are returned once; the app stores them in Android Keystore.

The agent supports multiple phones. Revoke from the tray's device list or from the
paired app (Settings → revoke). Revocation takes effect immediately.

## Configuration and data

Everything mutable lives in `%ProgramData%\PcWakeAgent` with ACLs limited to
SYSTEM and Administrators:

| File | Purpose |
|---|---|
| `config.json` | HTTPS/discovery ports, display name |
| `state.json` | device metadata and SHA-256 token hashes |
| `device-secrets.dat` | DPAPI machine-scoped HMAC tokens (never plaintext) |
| `certs/agent.pfx` + `agent.pfx.pass` | ECDSA P-256 server certificate and DPAPI-protected password |
| `logs/` | JSONL audit and service logs, with size rotation |

Default `config.json`:

```json
{
  "schemaVersion": 1,
  "httpsPort": 47101,
  "discoveryEnabled": true,
  "discoveryPort": 47100,
  "displayName": null
}
```

Restart the service after configuration changes:

```powershell
Restart-Service PcWakeAgent
```

The self-signed certificate is generated on first start with SANs for the
machine's LAN addresses and hostname. Clients pin the leaf SHA-256 from pairing.
**Reinstalling the certificate requires re-pairing all phones.** Changing or
removing `certs/` has the same effect.

## Tray and visible indicators

The tray shows service state, paired devices, pending commands and agent version.
It polls the read-only status pipe every two seconds and shows a visible
notification for remote shutdown/restart/sleep. For a pending shutdown you can
cancel from the tray (or the Android app). Windows also displays its own native
shutdown countdown produced by `shutdown.exe /t`.

The tray does not auto-start with Windows; the service does. Launch the shortcut
when you need pairing, device management, or the cancellation UI.

## Service account and least privilege

The service runs as **LocalSystem** because shutdown/restart and
`SetSuspendState` require system power privileges. Compensating controls:

- Only fixed argument vectors are executed; no shell or user-supplied executable.
- Only TCP `47101` (HTTPS, Private/Domain) and optional UDP `47100` are opened.
- Pairing administration uses an Administrators-only named pipe; a separate
  read-only status pipe allows the interactive session to cancel a pending
  command.
- Tokens are DPAPI-encrypted; state contains hashes; data ACL is SYSTEM/Admins.
- Audit logs never include tokens, pairing codes or certificate private keys.

Do not port-forward `47101` on the public internet. Use LAN, Tailscale/WireGuard,
or a secured reverse proxy, and keep certificate pinning.

## Updates

- MSI upgrades preserve `%ProgramData%\PcWakeAgent` and pairings.
- Stop the service, publish new binaries, and run the new MSI/installer.
- If the server certificate or host identity changes, users must re-pair.
- There is no silent auto-updater. Download updates only from a trusted release;
  verify SHA-256 and review the source.

## Uninstall

```powershell
msiexec /x .\PcWake.Agent.1.0.0.msi /qb
```

or `.\installer\uninstall.ps1`. Uninstall removes the service and firewall rules.
`%ProgramData%\PcWakeAgent` is preserved by default; delete it explicitly if you
want to remove pairings and the certificate (all phones must re-pair).

## Troubleshooting

```powershell
Get-Service PcWakeAgent
sc.exe query PcWakeAgent
curl.exe -k https://127.0.0.1:47101/health
Get-NetFirewallRule -DisplayName "PC Wake Controller Agent*"
Get-Content "$env:ProgramData\PcWakeAgent\logs\*.log" -Tail 50
```

- Service doesn't start: run the service exe with `--console` elevated and read
  the error; check the Windows Application event log.
- Phone cannot reach agent: confirm phone and PC are on the same normal LAN, the
  network profile is Private, firewall rules exist, and port `47101` is listening.
- Pairing closed: start a new pairing window from the tray; codes are single-use.
- Certificate/pin error: the certificate changed (reinstall/regenerate). Verify
  the host, then re-pair. Never bypass a pin mismatch.
- Power command returns 409: another power command is pending; wait or cancel.
- Sleep wakes immediately: a device is armed to wake incorrectly; check
  `powercfg -devicequery wake_armed`.
