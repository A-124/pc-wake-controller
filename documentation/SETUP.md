# End-to-end setup

This guide installs all three parts on real hardware. For a quick test you need
one Android phone, one Windows PC (wired Ethernet) and, for remote use, an
always-on Wake Bridge.

## 0. Prerequisites

- Windows 10/11 PC with wired Ethernet and BIOS Wake-on-LAN support.
- Android 8.0+ phone (minSdk 26).
- JDK 17 + Android SDK 34 to build the APK; Android Studio optional.
- .NET 8 SDK on Windows to build the agent.
- Node.js 20+ on the bridge host (Raspberry Pi/Linux).
- Phone and PC on the same LAN for local wake.

## 1. Build and install the Android app

```bash
cd android-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug package: `io.pcwake.controller.debug`. For a release APK, configure signing
as described in [`android-app/README.md`](../android-app/README.md#release-signing).
The app has no accounts and no analytics.

## 2. Build and install the Windows agent

Follow [`windows-agent/README.md`](../windows-agent/README.md). In short:

```powershell
cd windows-agent
dotnet build -c Release
# Installer (produced by the build) or:
powershell -ExecutionPolicy Bypass -File installer\install.ps1
```

The installer:

- installs the service (`PcWakeAgent`) with automatic start,
- adds firewall rules for TCP 47101 (HTTPS) and UDP 47100 (LAN discovery) on
  Private/Domain profiles,
- restricts `%ProgramData%\PcWakeAgent` ACLs to SYSTEM and Administrators,
- can be removed with the MSI uninstall or `uninstall.ps1`.

Verify:

```powershell
Get-Service PcWakeAgent
Invoke-WebRequest https://localhost:47101/health -SkipCertificateCheck
```

The agent generates a self-signed ECDSA certificate on first start. Do not
manually trust or replace it; the Android app pins it during pairing. If you
reinstall/regenerate the certificate, re-pair the phone.

## 3. Pair the phone with the agent

1. Open **PC Wake Agent** in the tray (administrator) → **Pair device**.
2. It displays a QR, the host/port, a one-time code and the certificate
   fingerprint, valid for 5 minutes.
3. Android → **Device pairing → Windows agent** → scan the QR. If you enter
   manually, copy the fingerprint; otherwise the app warns about TOFU.
4. After pairing, keep the detected adapter selected and save the PC profile.
   Verify the MAC matches the wired adapter (`ipconfig /all`).

Pairing creates a token stored in Android Keystore. The agent stores only a hash.

## 4. Configure Wake-on-LAN

Follow [`BIOS_WOL.md`](BIOS_WOL.md). Enter in the app:

- MAC of the wired NIC,
- LAN broadcast (e.g. `192.168.1.255`),
- UDP port 9 (default) or 7 if configured.

Use **Detect this phone's LAN** as a starting point. Settings → *Wake interface*
can bind sending to a phone interface; *Automatic* is usually correct.

Test: put the PC to **Sleep**, tap **Power on**, and wait for the app to report
online only when the agent responds. Then test full shutdown; not all boards
support S5 wake.

## 5. (Optional) Wake Bridge for mobile data

Follow [`REMOTE_ACCESS.md`](REMOTE_ACCESS.md) and
[`wake-bridge/README.md`](../wake-bridge/README.md):

1. Install the bridge on an always-on Linux host.
2. Give it TLS (Tailscale/Let's Encrypt or self-signed) and an allow-list of
   target MACs with the LAN broadcast.
3. Run `node src/cli.js pair --label "Pixel"` and scan the payload in the app.
4. In Add/Edit PC, select the bridge for that PC, or use the Bridge chip when
   waking.

Only expose the bridge HTTPS port (ideally over Tailscale). Never expose UDP
Wake-on-LAN to the internet.

## 6. Verify commands

- Shutdown/restart: in the app tap the action, confirm; the agent logs and
  Windows shows its native countdown. The tray shows a pending indicator.
- Sleep: 3-second delay, then suspend; Wake-on-LAN remains armed.
- Cancel: tray or app can cancel a pending shutdown/restart while still pending.
- History: every operation is recorded locally with **sent/accepted/failed**,
  never a fabricated "success".

## 7. Remote agent control (optional)

For shutdown/restart/sleep while away, the Windows agent must be reachable. Use
Tailscale/WireGuard between phone and PC (the PC must be awake) or a secured
reverse proxy for TCP 47101. Do not port-forward the agent without TLS pinning,
pairing and firewall limits.

## 8. Backup and recovery

- Android Settings → *Export* creates an AES-256-GCM/PBKDF2 backup of PC
  profiles **without tokens or pins**. After import, re-pair servers.
- Losing the phone: revoke the old device from the agent tray/bridge CLI. Tokens
  cannot be recovered from the phone; pairing again is required.
- Losing the agent certificate/state: reinstall and re-pair phones.

## 9. Updates

- Android: install a newer APK over the old one (same signing key).
- Agent: run the newer MSI/installer; state and certificate are preserved unless
  you explicitly remove `%ProgramData%\PcWakeAgent`. Re-pair if the certificate
  changes.
- Bridge: stop service, replace code, restart; state (hashed tokens, audit)
  remains. Review changelogs and not due security notices.

## 10. Uninstall

- Android: uninstall app; optionally revoke pairings first.
- Agent: MSI uninstall / `uninstall.ps1` (removes service and firewall rules;
  ask whether to delete state).
- Bridge: stop/disable the systemd unit, remove `/opt/pcwake-bridge` and its
  state directory.
