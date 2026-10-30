# Deployment overview

Component-specific deployment files live with each component. This directory is
for cross-component planning and operator checklists.

| Target | Artifact / script | Guide |
|---|---|---|
| Android phone | `android-app/app/build/outputs/apk/debug/app-debug.apk` (debug) or signed release APK | [SETUP](../documentation/SETUP.md), [Android README](../android-app/README.md) |
| Windows PC | `windows-agent/artifacts/` MSI or `windows-agent/installer/install.ps1` | [windows-agent README](../windows-agent/README.md) |
| Raspberry Pi / Linux bridge | `wake-bridge/deploy/install.sh`, systemd unit | [wake-bridge README](../wake-bridge/README.md), [REMOTE_ACCESS](../documentation/REMOTE_ACCESS.md) |

## Deployment order

1. Wire the target PC to the router with Ethernet; enable BIOS + Windows WoL
   ([BIOS guide](../documentation/BIOS_WOL.md)).
2. Install the Windows agent; allow firewall TCP 47101 (and UDP 47100 discovery)
   on Private/Domain profiles.
3. Build/install the Android app.
4. Pair the phone with the agent via QR; add a PC profile; verify local wake.
5. Optional: deploy the Wake Bridge on an always-on host, pair it, add it to the
   PC profile; verify wake over mobile data.
6. Verify shutdown/restart/sleep and cancellation.
7. Record results in [TEST_REPORT](../documentation/TEST_REPORT.md).

## Network exposure checklist

- [ ] No UDP Wake-on-LAN port (9/7) forwarded from the internet.
- [ ] Agent HTTPS 47101 reachable only on LAN/private VPN, never open publicly.
- [ ] Bridge HTTPS exposed only through Tailscale/reverse proxy; TLS configured.
- [ ] Firewall restricted to Private/Domain on Windows.
- [ ] No unauthenticated admin endpoints; bridge pairing only from localhost.
- [ ] Certificate fingerprints verified via QR during pairing.
- [ ] Tokens revoked for lost devices.
- [ ] Backups exclude credentials; passphrases stored safely.

## Cost notes

Local wake is free. Remote wake requires an always-on device. Tailscale,
hosting, DNS and certificates are optional third-party choices with their own
terms and possible costs. The project software is MIT and does not require any
paid service.
