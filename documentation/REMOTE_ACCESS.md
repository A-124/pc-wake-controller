# Remote wake and control over mobile data

A phone on mobile data cannot send a LAN broadcast to a powered-off PC behind a
home router. PC Wake solves this with an always-on **Wake Bridge** on the home
network.

```
Android (mobile data)
        │  HTTPS + pinned cert + HMAC-signed request
        ▼
Wake Bridge (Raspberry Pi / router / mini PC, always on)
        │  UDP Magic Packet, allow-listed MAC
        ▼
Target PC (asleep or shut down, wired Ethernet, WoL armed)
```

The bridge proves only that it accepted a request and wrote the UDP datagrams.
PC health still comes from the Windows agent after boot.

## Why not run Tailscale on the PC?

Tailscale/WireGuard need a running OS to maintain the tunnel. A shut-down PC
cannot be reached by Tailscale. The always-on bridge (or another home device)
must be the remote entry point. The PC's agent can also be reached directly over
Tailscale once the PC is awake; that is optional.

## Deployment options

| Option | Notes |
|---|---|
| Raspberry Pi (recommended) | Low power, always on, runs Node.js 20+, systemd |
| Always-on Linux PC/NAS | Same install, keep it patched |
| Router with WoL/API | Works only if it supports authenticated, allow-listed wake requests; prefer the bridge |
| mini PC | Same as Linux PC |

The project's bridge requires HTTPS, a target allow-list, pairing and signed
requests. Do not enable an unauthenticated router WoL endpoint on the internet.

## Step 1 — install the bridge

See [`wake-bridge/README.md`](../wake-bridge/README.md) and
[`wake-bridge/deploy/`](../wake-bridge/deploy/). Zero npm runtime dependencies.

1. Copy the repository's `wake-bridge/` directory to `/opt/pcwake-bridge`.
2. Create a dedicated user and state directory (mode `0700`).
3. Create `config.json` from `config.example.json`; set listen port, TLS paths,
   display name, targets:
   ```json
   {
     "targets": [
       {
         "id": "gaming-pc",
         "name": "Gaming PC",
         "mac": "AA:BB:CC:DD:EE:FF",
         "broadcast": "192.168.1.255",
         "port": 9,
         "repeats": 3
       }
     ]
   }
   ```
4. TLS options:
   - **Tailscale HTTPS:** `tailscale cert bridge-name.tailnet-name.ts.net`
     (subject to Tailscale's terms/free plan).
   - **Public DNS + Let's Encrypt:** reverse proxy or direct cert; only TCP 443/8443
     exposed.
   - **Self-signed for LAN/testing:** generate with `deploy/gen-cert.sh`; the
     Android app pins the certificate fingerprint from the pairing payload.
5. Start the systemd service. Check `GET https://host:8443/health` returns
   `{"ok":true,"service":"pcwake-bridge",...}`.

Firewall: allow **only** the bridge HTTPS port, ideally restricted to your
Tailscale interface or reverse proxy. Never forward UDP 9/7 Wake-on-LAN from the
internet. The bridge itself should not be publicly reachable except through its
TLS endpoint.

## Step 2 — pair the phone

On the bridge:

```bash
sudo -u pcwake node /opt/pcwake-bridge/src/cli.js pair --label "Pixel"
```

This starts a five-minute, single-use pairing window and prints a QR/payload
with host, port, certificate fingerprint and code. In the Android app use
**Device pairing → Wake Bridge**, scan or enter it. The app stores only a token
and the pin; tokens are encrypted with Android Keystore.

## Step 3 — use it

Wake from anywhere:

1. PC asleep/shut down; target wired Ethernet with WoL armed.
2. Bridge always on and reachable over HTTPS.
3. In the app choose the PC and select **Bridge**, then tap Power on.
4. The app reports the accepted/sent packet. It shows Online only if the agent
   answers after boot.

If you need shutdown/restart/sleep while remote, also pair the Windows agent and
make it reachable. Options:

- Put the agent and phone on the same Tailscale/WireGuard tailnet.
- Or use a reverse proxy/mesh that routes TCP 47101 to the agent.
- Do not expose the agent directly on the public internet without TLS, pinning,
  pairing and firewall restrictions.

## Security

- Only allow-listed MACs can be woken by the bridge.
- Requests are HMAC-signed with a per-device token, 60-second timestamp window,
  replay cache, and rate limits.
- The phone pins the bridge certificate; a changed certificate is a hard error.
- The bridge binds an administrator CLI to localhost only; pairing cannot be
  started over the public API.
- Audit logs record accepted and rejected wake requests without tokens/codes.

## Limitations and costs

- Bridge hardware, electricity, DNS/certificates and any Tailscale plan are your
  choices and may have costs. The software itself is free.
- The bridge does not know whether the PC booted.
- A compromised bridge can send packets to its allow-listed MACs; keep it
  patched, restrict SSH and use disk encryption when practical.
- Tailscale free-plan limits and terms can change; verify before relying on it.
