# PC Wake Controller

A free, self-hosted Android + Windows system for waking Windows PCs over the
LAN or mobile data and controlling shutdown, restart and sleep with
authenticated commands. No subscription, ads, mandatory cloud, or paid WoL API.

## Components

| Component | Stack | Purpose |
|---|---|---|
| `android-app/` | Kotlin, Compose, Material 3, Room, Keystore | Dashboard, PC profiles, real Magic Packets, signed power commands, pairing, monitoring, diagnostics, history |
| `windows-agent/` | C# / .NET 8, Windows Service + tray, ASP.NET Core HTTPS | Pairing, authenticated fixed power actions, health, LAN discovery, audit, visible pending-command indicator |
| `wake-bridge/` | Node.js (zero runtime dependencies) | Always-on remote wake gateway: TLS, pairing, allow-listed targets, real Magic Packets |
| `documentation/` | — | Protocol, setup, BIOS, security, remote access, troubleshooting, test report |

## What is real

- **Wake-on-LAN:** app and bridge generate true 102-byte magic packets
  (`6 × FF` + `MAC × 16`), configurable broadcast/port, retries. A packet sent is
  never reported as a boot.
- **Shutdown / restart / sleep:** only via signed commands to the Windows agent.
  Success is reported only when the agent accepts. Windows shows its native
  countdown; the tray adds a visible indicator and cancel control.
- **Monitoring:** online means an authenticated agent response. Unreachable is
  shown as **Unknown**; **Offline** appears only after an accepted power-off
  command. ICMP failure is never treated as "powered off".
- **Security:** one-time pairing codes, issued-once tokens stored in Android
  Keystore, HMAC-SHA256 request signing, timestamp window, nonce replay cache,
  rate limits, exact certificate pinning, revocation, audit logs, no arbitrary
  commands or remote shell.
- **Remote:** Android → HTTPS Wake Bridge → allow-listed LAN Magic Packet. Works
  on mobile data. Tailscale/WireGuard optional; the bridge must stay on while
  the PC is off, because Tailscale on a powered-off PC cannot answer.

## Download

Prebuilt **v1.0.0** artifacts (unsigned, debug APK) are attached to the
[GitHub release](https://github.com/A-124/pc-wake-controller/releases/tag/v1.0.0):

| Artifact | SHA-256 |
|---|---|
| `PC-Wake-1.0.0-debug.apk` | `546bd2570ef8ddba818af0c3a3a1a65afc20bfc9fd738364416e76d15db031a3` |
| `PC-Wake-Agent-1.0.0.msi` | `3ebeee245688da56f1286b3f214edcbe31086da3a6989c868a1a6fff9f00dc09` |

The APK is debug-signed and the MSI is not code-signed. Verify the hashes and
review the source before installing. Do not accept a certificate pin mismatch.

## Quick start

1. **Build the app:** `cd android-app && ./gradlew :app:assembleDebug`
   → `app/build/outputs/apk/debug/app-debug.apk`.
2. **Build/install the Windows agent:** see [`windows-agent/README.md`](windows-agent/README.md)
   and [`documentation/SETUP.md`](documentation/SETUP.md). Install the MSI or
   run the PowerShell installer with administrator rights.
3. **Pair the phone:** tray → *Pair device* (or bridge CLI `node src/cli.js pair`),
   then Android → *Device pairing* → scan QR / enter code + fingerprint.
4. **Add a PC:** Android → *Add PC*; enter MAC, LAN broadcast and UDP 9.
5. **Test locally:** PC asleep → tap *Power on* → wake from Sleep first, then
   test full shutdown per the BIOS guide.

Detailed guides:

- [Setup](documentation/SETUP.md)
- [BIOS + Windows Wake-on-LAN](documentation/BIOS_WOL.md)
- [Remote access and Wake Bridge](documentation/REMOTE_ACCESS.md)
- [Security model](documentation/SECURITY.md)
- [Troubleshooting](documentation/TROUBLESHOOTING.md)
- [Architecture](documentation/ARCHITECTURE.md)
- [Wire protocol](documentation/PROTOCOL.md)
- [Verification report](documentation/TEST_REPORT.md)

## Repository layout

```
android-app/       Android Studio project, APK, unit tests
windows-agent/     .NET solution: Service, Core, Tray, tests, installer
wake-bridge/       Node.js bridge, tests, systemd deployment
documentation/     Protocol and operator docs
deployment/        Cross-component deployment helpers
scripts/           Build and smoke-test scripts
```

## Costs and third parties

The software is MIT-licensed and free. Local wake has no recurring cost. Remote
wake requires hardware you likely already own or an always-on device (Raspberry
Pi, router, mini PC). If you choose Tailscale, hosting, a public DNS name, or a
certificate authority, their free-plan limits, terms and possible fees apply.
No cloud service is built into or required by this project.

## Verification status

Run on Windows 10 against a physical Android phone and a wired-Ethernet
Windows PC: **18/18 Android JVM tests, 54/54 Wake Bridge tests and 51/51 Windows
agent tests pass**, the APK was installed and launched, the agent MSI was
installed, and the phone paired over TLS with certificate pinning and reported
the PC **Online** through an authenticated agent request.

Actual shutdown/restart/sleep execution, a controlled S3/S5 wake test and Linux
bridge deployment remain operator/hardware validation steps. See
[`documentation/TEST_REPORT.md`](documentation/TEST_REPORT.md) for exact results,
artifact hashes and the checklist that remains.

## Privacy

This repository contains no target-specific MAC addresses, IP addresses,
hostnames, phone identifiers, pairing codes, certificate pins or tokens. Those
details exist only in the local runtime state of a deployment. Release assets
contain only generic application binaries and documentation.

## License

MIT — see [`LICENSE`](LICENSE).
