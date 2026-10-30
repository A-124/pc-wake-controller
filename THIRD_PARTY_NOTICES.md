# Third-party notices

PC Wake Controller is MIT-licensed. It builds on the following open-source
projects. Their licenses remain in effect for the respective components.

## Android

| Component | License |
|---|---|
| AndroidX Core, Activity, Lifecycle, Navigation, DataStore | Apache License 2.0 |
| Jetpack Compose (UI, Material 3, Material Icons) | Apache License 2.0 |
| Room | Apache License 2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| OkHttp / Okio | Apache License 2.0 |
| ZXing Android Embedded and ZXing core | Apache License 2.0 |

## Windows agent

| Component | License |
|---|---|
| .NET 8 / ASP.NET Core | MIT |
| Microsoft.Extensions.* packages | MIT |
| QRCoder | MIT |
| WiX Toolset (build/installer tooling) | Microsoft Reciprocal License (MS-RL) |

## Wake Bridge

Node.js built-in modules only; no runtime npm dependencies. Node.js is licensed
under the MIT-style Node.js license.

## Test-only material

`wake-bridge/test/fixtures/TEST-ONLY-*.pem` is a self-signed **test** certificate
and key generated solely for the automated test suite. It is not used by the
production service, is not trusted by clients, and must never be deployed.

## Not affiliated

Windows, Android, Tailscale, Raspberry Pi and other product names are
trademarks of their respective owners. This project is not endorsed by them.
