# PC Wake Controller v1.0.0

Free, self-hosted Android + Windows PC wake and power control. No subscription,
ads, mandatory cloud, or paid Wake-on-LAN API.

## Downloads

| Asset | SHA-256 |
|---|---|
| `PC-Wake-1.0.0-release.apk` (signed release, Android 8+) | `4a704a3b6252199d0a27224bd3494cb146f99cb0a221cf1fffd411ea7d568987` |
| `PC-Wake-Agent-1.0.0.msi` (Windows 10/11 x64) | `3ebeee245688da56f1286b3f214edcbe31086da3a6989c868a1a6fff9f00dc09` |

APK signer certificate SHA-256:
`B9:22:35:D2:E2:F6:89:6E:AC:FC:9C:B6:FC:2E:97:FA:CF:AF:0A:92:EA:FB:87:E3:CB:4A:FF:3D:1E:69:FB:D4`.
The Windows MSI is **not Authenticode-signed** (no code-signing certificate is
available); verify the hash and review the source.

## Highlights

- Android 14-target Compose app: dashboard, onboarding, QR/manual pairing,
  profiles, details, full power controls, WoL setup, bridge setup, diagnostics,
  history, settings, about.
- Real 102-byte Magic Packets with broadcast/port/retries/interface selection.
- Honest status: wake is **sent**, never “booted”; Online requires an
  authenticated Windows agent response.
- HMAC-SHA256 signed commands, nonce/timestamp replay protection, rate limits,
  exact certificate pinning, one-time pairing codes, revocation.
- Windows service with fixed shutdown/restart/sleep/cancel actions, tray
  indicator, DPAPI-protected secrets, audit logs, MSI/PowerShell installer.
- Zero-dependency Node.js Wake Bridge for mobile-data wake through an always-on
  LAN host, with target allow-list and localhost-only pairing CLI.
- Encrypted Android config backup (PBKDF2 + AES-256-GCM) that excludes tokens.

## Install

1. Verify hashes above.
2. Install the MSI as administrator (SmartScreen may warn). It starts the
   `PcWakeAgent` service, adds firewall rules on Private/Domain, and creates a
   Start Menu shortcut.
3. Install the release APK; pair via **Device pairing → Windows agent** using
   the tray QR/code.
4. Add the wired PC's MAC, LAN broadcast and UDP port 9.
5. Enable BIOS Wake-on-LAN and vendor NIC wake settings. Read
   [documentation/BIOS_WOL.md](documentation/BIOS_WOL.md).
6. Test from Sleep first; full-shutdown wake is hardware-dependent.

Remote wake requires deploying
[wake-bridge](wake-bridge/README.md) on an always-on host. Never expose UDP 9
or the agent port to the public internet.

## Verification

- 18/18 Android JVM tests, 54/54 Wake Bridge tests, 51/51 Windows agent tests.
- Physical Android phone paired to the agent with certificate pinning and
  reported the PC **Online** through an authenticated request.
- Release APK built with R8 and signature-verified with `apksigner`.
- Hardware-specific identities are intentionally excluded from source and
  release artifacts.

Known unverified items: real shutdown/restart/sleep command execution, a
controlled S3/S5 wake capture, and Linux Wake Bridge deployment. See
[documentation/TEST_REPORT.md](documentation/TEST_REPORT.md).

## Security

MIT licensed. No accounts, analytics, telemetry or bundled third-party cloud.
Report vulnerabilities privately per [SECURITY.md](SECURITY.md). Never accept a
changed certificate pin without verifying the host.
