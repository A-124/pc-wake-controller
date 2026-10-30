# Changelog

All notable changes are documented here. Format: Keep a Changelog; versioning:
Semantic Versioning.

## 1.0.0 — 2026-10-30

First public release.

### Android

- Material 3 Compose dashboard, onboarding, splash, PC profiles, details, power
  controls, Wake-on-LAN setup, pairing, bridge setup, diagnostics, history,
  settings and about screens.
- Room persistence for PCs, connection profiles and command history.
- Real 102-byte Wake-on-LAN Magic Packets with configurable broadcast/port,
  retries, interface selection and honest “sent” reporting.
- Pinned HTTPS agent/bridge clients; QR or manual secure pairing; one-time codes;
  certificate pinning with TOFU warning.
- Android Keystore AES-256-GCM vault for tokens/pins; no credentials in Room.
- HMAC-SHA256 request signing, replay/timestamp awareness, command confirmations
  and agent-backed online status. Unreachable is Unknown unless a power-off was
  accepted.
- Encrypted configuration export/import (PBKDF2 + AES-256-GCM) and CSV history
  export.
- LAN agent discovery (UDP 47100), light/dark/system themes, diagnostics.

### Windows agent

- .NET 8 Windows Service with Kestrel HTTPS and a separate WinForms tray.
- Pairing, device revocation, hashed token state, DPAPI-protected token vault,
  self-signed ECDSA certificate, certificate fingerprint/QR.
- Signed fixed power actions: shutdown, restart, sleep; one pending command;
  cancellation; native Windows countdown plus tray indicator.
- LAN discovery responder, audit JSONL, rate limits, ACL-restricted data,
  LocalSystem rationale, MSI and PowerShell installers.
- MSI now starts the service and adds a Start Menu shortcut.

### Wake Bridge

- Zero-dependency Node.js HTTPS bridge with localhost-only admin CLI.
- Allow-listed MAC targets, real Magic Packets, per-device and per-IP rate
  limits, replay protection, revocation, audit and systemd deployment.

### Documentation

- Wire protocol with normative test vectors; setup, BIOS/WoL, remote access,
  security, architecture, troubleshooting and verification report.
- Target-specific hardware, phone and network identifiers are intentionally not
  included in the repository.

### Known limitations

- Wake from full shutdown depends on hardware/firmware; test Sleep and S5.
- Local APK is debug-signed; the release APK is signed with the project release
  key (fingerprint in RELEASE_SIGNING). Windows MSI is unsigned.
- Remote wake requires an always-on Wake Bridge; bridge Linux/systemd deployment
  is documented but not hardware-tested in this release.
