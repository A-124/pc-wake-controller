# Verification report

Date: 2026-10-30
Repository state: `main` snapshot prepared for public release (local paths,
credentials and target identity redacted).

## Environment

| Item | Value |
|---|---|
| Host OS | Windows 10 x64 |
| Target PC | Windows PC with wired Ethernet |
| Android | Physical test phone, USB debugging, APK installed with ADB |
| JDK / Gradle | Temurin 17.0.20.1 / Gradle 8.7 |
| Android SDK | Platform 34, build-tools 34.0.0 |
| .NET | SDK 8.0.427 (workspace-local) |
| Node | 24.21.0 |
| Hardware WoL state | Vendor LAN driver; `Wake on Magic Packet` + `Shutdown Wake-On-Lan` enabled; NIC wake-armed |

The target-specific MAC, IP, hostname, certificate pin, pairing code and phone
identifiers are intentionally omitted. They never entered source, commits or
release assets.

## Automated tests

| Suite | Command | Result |
|---|---|---|
| Protocol gate | `node scripts/check-protocol.mjs` | PASS (both §8 vectors, body hashes, documentation match) |
| Android core JVM | `gradle :app:testDebugUnitTest --tests ...` | **16/16 PASS** |
| Android DB/Robolectric | `gradle :app:testDebugUnitTest --tests com.pcwake.app.RoomDatabaseTest` | **2/2 PASS** |
| Wake Bridge | `cd wake-bridge && npm test` | **54/54 PASS** |
| Windows agent | `dotnet test PcWake.Agent.sln -c Release` | **51/51 PASS** |

Android coverage: HMAC protocol vectors, tamper rejection, Magic Packet bytes,
MAC/broadcast/port validation, pairing payloads, Crockford normalization,
backup encryption/wrong passphrase, Room profile uniqueness, history joins and
cascade delete.

Bridge coverage includes real HTTPS, pairing one-time/expiry/lockout,
replay/skew/tamper rejection, rate limits, allow-list enforcement, a real UDP
listener receiving the exact 102-byte packet, admin/CLI, state and audit.

Windows coverage includes protocol vectors, auth/replay/skew/rate limits, pairing
expiry and attempt lockout, fixed shutdown/restart/cancel arguments, grace
clamping, sleep/cancel state transitions, config/state validation, audit
redaction, and Kestrel pairing/status/power integration with a fake controller.
No test invokes real power operations.

## Build artifacts

| Artifact | Result | SHA-256 |
|---|---|---|
| `PC-Wake-1.0.0-debug.apk` | Built, `aapt2` manifest verified, installed on physical phone | `546bd2570ef8ddba818af0c3a3a1a65afc20bfc9fd738364416e76d15db031a3` |
| `PC-Wake-Agent-1.0.0.msi` | WiX v5 build; service registration, firewall and Start Menu shortcut included | `3ebeee245688da56f1286b3f214edcbe31086da3a6989c868a1a6fff9f00dc09` |

The APK is debug-signed and the MSI is not code-signed (no code-signing
certificate was available). Verify hashes before installing; a public release
should add a signing certificate.

## End-to-end verification performed

- Installed the APK through ADB on a physical Android phone and launched all
  main flows (dashboard, Add/Edit PC, pairing, details, diagnostics scaffolding).
- Installed the Windows agent MSI, started the service, and verified local
  `/health` over HTTPS.
- Paired the phone using host, one-time code and the exact certificate
  fingerprint; pairing succeeded.
- Linked the existing PC profile to the agent and observed **Online** with
  “Agent replied in ~200 ms” and the correct Windows build. This proves real
  TLS, pinning, HMAC request authentication, nonce/timestamp handling, and status
  parsing across Android and the agent.
- Verified the Windows wired NIC is wake-armed after installing the vendor driver
  (`powercfg /devicequery wake_armed` includes the wired NIC).
- The user reported the end-to-end WoL/power workflow as fully working after
  BIOS configuration.

## NOT independently verified

These require disruptive hardware actions or infrastructure not available to the
build session; they are not claimed as automatically tested:

- Wake from **Sleep (S3)** and from full **shutdown (S5)** in a controlled,
  repeatable test with packet capture.
- Actual **shutdown / restart / sleep** execution (the agent tests use a fake
  power controller; no real power action was triggered during development).
- Wake Bridge deployment on Raspberry Pi/Linux and mobile-data wake. The bridge
  test suite runs on Windows with real TLS and loopback UDP; Linux systemd/install
  scripts and real LAN broadcast delivery were not executed.
- Multiple phones / multiple PCs and revocation across a real network.
- Certificate renewal/re-pairing behaviour in production.

The agent MSI originally did not start the service immediately after install; it
was started manually during testing. The source/MSI in the release now includes
`Start="install"` and a Start Menu shortcut, verified by a successful WiX build.

## Reproduce

```powershell
node scripts/check-protocol.mjs
cd wake-bridge; npm test
cd ../windows-agent
$env:DOTNET_ROOT="$PWD\.tools\dotnet"   # only when using the workspace SDK
& "$env:DOTNET_ROOT\dotnet.exe" test PcWake.Agent.sln -c Release
cd ../android-app
gradle :app:testDebugUnitTest --no-daemon
gradle :app:assembleDebug --no-daemon
cd ../windows-agent
powershell -ExecutionPolicy Bypass -File installer/Build-Installer.ps1
```

## Definition-of-done status

| Requirement | Status |
|---|---|
| Android app builds | PASS |
| Windows agent builds | PASS |
| Local WoL on real hardware | User-confirmed; not independently captured |
| Remote wake via real bridge | Bridge tests PASS; hardware deployment not executed |
| Mobile data | Not executed |
| Authenticated shutdown/restart/sleep | Code/tests PASS; real power execution not performed |
| Honest monitoring | PASS (phone showed Online only from agent; Unknown/Offline semantics tested in code) |
| Device pairing + unauthorized rejection | PASS (pairing on device; auth rejection tests) |
| Multiple profiles | DB/UI implemented and tests; not exercised across multiple phones |
| No fake/placeholder core behavior | PASS by implementation review |
| Documentation | PASS (see `documentation/`) |
| APK + installer produced | PASS (unsigned debug APK/MSI) |

The remaining unchecked rows are hardware/lab validation tasks, not code stubs.
