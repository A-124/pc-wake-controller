# Contributing

Thanks for helping improve PC Wake Controller. The project is intentionally
small, self-hosted and dependency-light.

## Before you start

- Read [ARCHITECTURE](documentation/ARCHITECTURE.md),
  [PROTOCOL](documentation/PROTOCOL.md) and [SECURITY](documentation/SECURITY.md).
- Never commit credentials, keystores, tokens, pairing codes, real MAC/IP/host
  details, screenshots containing them, or generated state/logs.
- Security changes must preserve the signed-request contract and the fixed
  power-command surface. No shell execution, arbitrary commands or UAC bypass.

## Layout

```
android-app/     Kotlin/Compose app + JVM tests
windows-agent/   .NET 8 service, tray, tests, MSI/installer
wake-bridge/     zero-dependency Node bridge + tests
documentation/   protocol and operator docs
scripts/         cross-component checks
```

## Build and test

```bash
node scripts/check-protocol.mjs
cd wake-bridge && npm test
cd ../android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
cd ../windows-agent && dotnet test PcWake.Agent.sln -c Release
```

For the Windows agent, use .NET 8 on Windows. The repository's CI runs protocol,
bridge and agent tests on Linux/Windows, and Android unit tests on Ubuntu.

## Pull requests

- Keep changes focused; describe the user-visible behavior and security impact.
- Update documentation and the test report when behavior or verification changes.
- Include tests for protocol, parsing, crypto, auth, persistence and power-state
  logic where applicable.
- Match the existing Kotlin/C#/JS style and avoid new runtime dependencies unless
  clearly justified (the Bridge must remain dependency-free).
- Do not weaken the “honest status” rules: wake packets are `sent`, never
  “booted”; online requires an authenticated agent reply.

## Commit and release

- Commit messages should explain *why*.
- Releases use annotated tags (`vX.Y.Z`) and include SHA-256 hashes for artifacts.
- APK release signing keys and Windows code-signing keys are private and must
  never be committed. See [RELEASE_SIGNING](documentation/RELEASE_SIGNING.md).
