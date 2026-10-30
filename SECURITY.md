# Security policy

PC Wake Controller grants remote power control. Please treat vulnerabilities
seriously and report them privately.

## Supported versions

| Version | Supported |
|---|---|
| 1.x | Yes (current release) |
| older | No |

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** for this repository:
Security → Advisories → “Report a vulnerability”. Do not open a public issue for
security-sensitive reports before a fix is available.

Please include the affected component (Android app, Windows agent, Wake Bridge),
version, reproduction steps, impact, and any proof-of-concept you can share
safely. Remove your own pairing tokens, certificate pins, IP addresses and
hostnames from logs before attaching them.

This is a free, self-hosted project. Reports are handled on a best-effort basis;
there is no bug bounty or guaranteed response time. We aim to acknowledge valid
reports and credit reporters if desired.

## Scope

In scope:

- Authentication/signature bypass, replay, timestamp or nonce flaws.
- Pairing flaws, token disclosure, rate-limit bypass, certificate-pinning bypass.
- Arbitrary command execution or privilege escalation in the agent.
- Unauthenticated power actions.
- TLS/certificate validation mistakes in the Android app or bridge.
- Secret exposure in the repository or release artifacts.

Out of scope:

- Wake-on-LAN not working due to BIOS, NIC, router, VLAN or standby-power
  configuration. See the BIOS guide.
- Physical access attacks, compromised OS/root/admin, or a compromised phone that
  already holds valid credentials.
- Denial of service by flooding a local listener beyond documented rate limits,
  unless it bypasses authentication or corrupts state.
- Self-signed/TOFU risks that are already documented when pairing without a
  fingerprint.

## Security expectations for contributors

- Never commit pairing tokens, certificate private keys, keystores, passwords,
  real MAC/IP/hostname data or audit logs.
- Do not add arbitrary command execution, remote shells, silent installers or
  UAC bypasses.
- Do not weaken certificate pinning, signature canonicalization, replay
  protection or the fixed power-command surface.
- Add tests for security-relevant changes.

Full design and residual risks: [documentation/SECURITY.md](documentation/SECURITY.md).
