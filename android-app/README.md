# PC Wake — Android app

Kotlin + Jetpack Compose + Material 3 app for local Wake-on-LAN and authenticated
Windows power control (shutdown / restart / sleep) through the PC Wake Windows
agent, plus remote wake through the Wake Bridge.

## Build

Requirements: JDK 17, Android SDK platform 34 + build-tools 34.0.0. Helper
scripts expect SDK at `D:\Apps\Android\sdk` only through the local
`local.properties` file, which is not committed.

```bash
cd android-app
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:testDebugUnitTest        # JVM unit tests
```

Artifacts:

- `app/build/outputs/apk/debug/app-debug.apk` (package `io.pcwake.controller.debug`)
- Release: `./gradlew :app:assembleRelease` after signing is configured.

### Release signing

Create a keystore, then export environment variables before building:

```bash
keytool -genkeypair -v -keystore pcwake-release.jks -alias pcwake \
  -keyalg RSA -keysize 4096 -validity 10000
export PCWAKE_KEYSTORE_PATH=/absolute/path/pcwake-release.jks
export PCWAKE_KEYSTORE_PASSWORD='…'
export PCWAKE_KEY_ALIAS=pcwake
export PCWAKE_KEY_PASSWORD='…'
./gradlew :app:assembleRelease
```

Never commit the keystore or passwords. Release signing is intentionally not
preconfigured with secrets.

## Architecture

Single Gradle app module with strict packages:

- `core/` — models, validation, canonical request/HMAC signing, formatting.
- `data/db/` — Room entities, DAOs, schema export (`app/schemas/`).
- `data/` — repositories, encrypted SecretVault (Android Keystore AES-GCM),
  DataStore settings, encrypted backup/CSV export.
- `net/` — real UDP Magic Packet sender, UDP discovery, certificate-pinned
  OkHttp clients, agent/bridge APIs.
- `domain/` — `PowerCoordinator`, `DeviceMonitor` (honest status semantics).
- `ui/` — Material 3 screens, navigation, components, ViewModels.

The single-module layout is deliberate: it keeps incremental build times and
startup low, while package boundaries preserve the requested separation. The
codebase is small enough that separate Gradle modules would add overhead without
an isolation benefit.

## Behavior guarantees

- Magic packets are 6 × `FF` + MAC × 16 (102 bytes), broadcast via UDP with
  per-request retries (default 3).
- A wake result is recorded as **SENT**, never "booted". The app only shows a PC
  as online after an authenticated agent response.
- Unreachable agents are shown as **Unknown**; **Offline** is used only right
  after an accepted shutdown/sleep/restart.
- Shutdown/restart/sleep require a paired agent, signed commands, and explicit
  confirmation. No fake success states are used.
- All server credentials are stored as ciphertext under a non-exportable
  Android Keystore key. Backups intentionally exclude tokens and pins.

## Screens

Splash (Android 12 SplashScreen API), onboarding, dashboard, add/edit PC, PC
details, full power controls, Wake-on-LAN configuration, Wake Bridge setup,
device pairing (QR or manual), diagnostics, command history, settings, about.

## Tests

JVM tests cover:

- Normative HMAC protocol vectors shared with the agent and bridge.
- Magic packet bytes, MAC parsing/validation, broadcast math.
- Pairing payload validation, Crockford code normalization.
- Backup encryption round-trip, wrong passphrase, fresh IV/salt.
- Room entities, profile uniqueness, history joins, cascade delete (Robolectric).

No emulator/device is available in this build environment; hardware-dependent
behavior (BIOS WoL, shutdown execution) must be validated on the target PC as
documented in the repository's test report.
