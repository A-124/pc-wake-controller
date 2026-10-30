# Troubleshooting

Start with Android **Settings → Connection diagnostics** (or a PC's *Diagnose*
button). It runs real checks; none are simulated.

## Wake packet sent, PC does not wake

| Check | What to do |
|---|---|
| MAC | Must be the wired adapter's unicast MAC. `ipconfig /all` → Physical Address. Not the Wi-Fi adapter, not a virtual/VPN adapter. |
| Broadcast | Directed broadcast of the PC's subnet, e.g. `192.168.1.255`. Not `255.255.255.255` unless the network routes it. |
| BIOS | ErP/EuP disabled; Wake on LAN / PCI-E wake enabled; see [BIOS guide](BIOS_WOL.md). |
| NIC driver | Wake on Magic Packet enabled; "Only allow a magic packet"; Green Ethernet/EEE disabled while testing. |
| Standby power | NIC LEDs must stay lit after shutdown. Dark LEDs = no standby power (ErP/power strip/PSU). |
| Fast Startup | Disable it while testing; it can make shutdown a hybrid state. |
| Network | No guest Wi-Fi, AP isolation, VLAN mismatch, or switch that drops broadcasts. |
| State | Test from **Sleep** first. S5/full-shutdown wake is firmware-dependent and not guaranteed. |
| Interface | If you bound WoL to a phone interface, ensure it's the Wi-Fi/LAN interface with an IPv4 address; otherwise set *Automatic*. |

The app deliberately reports a sent packet, not a boot. Only an agent response
proves online. Wait for the agent poll; use *Refresh*.

## Agent shows Unknown / unreachable

- The PC may genuinely be off. Unreachable is not proof; the app shows
  **Unknown** unless a shutdown/sleep was just accepted.
- Ping is not used for health. ICMP may be blocked while HTTPS works, or vice
  versa.
- Check phone network: on mobile data, a LAN agent address is unreachable by
  design. Use the bridge for wake, Tailscale for agent control.
- Verify service: `Get-Service PcWakeAgent`; port: `Test-NetConnection host -Port 47101`.
- Windows firewall: the installer adds rules for TCP 47101 on Private/Domain.
  Public profile is intentionally not opened.
- Certificate pin mismatch → the agent certificate changed (reinstall/new host).
  Re-pair after verifying the host. Never "ignore" a pin mismatch on an untrusted
  network.

## Pairing fails

- **Pairing closed**: start pairing from the tray (agent) or `node src/cli.js pair`
  (bridge); codes are valid for 5 minutes.
- **Invalid code**: codes are single-use and 40-bit; check the QR/manual text and
  Crockford ambiguities (I/L→1, O→0, U→V).
- **Too many attempts**: wait for the rate limit window, then create a new code.
- **TLS/pin error**: ensure host/port point to the HTTP**S** agent/bridge, the
  clock is correct (pairing uses ±60 s signed requests only after pairing; TLS
  itself must be valid), and the fingerprint matches the server's.
- **Wrong server kind**: an agent payload cannot pair as a bridge, and vice versa.

## Commands fail

- `command_pending`: wait for the current shutdown/restart to execute/cancel.
- `unauthorized`: revoked or re-paired server; re-pair in the app.
- `rate_limited`: wait; status polling is subject to 60 req/min.
- Clock skew > 60 seconds: enable automatic time on the phone and PC.
- Agent started but Windows rejects shutdown: service account/privilege issue;
  check Windows event log and the agent audit log.
- Sleep fails: driver/firmware may block `SetSuspendState`; test manually with
  `rundll32.exe powrprof.dll,SetSuspendState 0,1,0` (this also writes hiberfil;
  understand the difference between sleep/hibernate).

## Bridge fails

- `target_not_allowed`: MAC is not on the bridge allow-list; edit bridge config
  and restart the service.
- Bridge unreachable: service status, TLS port, firewall, Tailscale/DNS.
- 429: per-device wake limit (20/min) or auth failure limit.
- Cannot create a pairing code: use the local admin CLI on the bridge host; the
  public API never starts pairing.
- PC wakes via bridge but app never shows online: the agent may be unreachable
  over mobile data — this is expected. Online requires agent connectivity.

## Recovering

- Forgotten/removed server credential: revoke the old token if possible, then
  pair again. Android Settings → Pairing tokens never leave the phone.
- Lost Android phone: revoke via agent tray or bridge CLI.
- Reinstalled agent with a new hostname/cert: re-pair; old pins correctly fail.
- Reset app data: clears DB, settings, and the Keystore-wrapped vault. Back up
  PC profiles first (credentials are never exported).
- Bridge state loss: devices must re-pair. Keep state directory backed up if
  convenient (contains hashed tokens and audit only).

## Logs

- Agent: `%ProgramData%\PcWakeAgent\` audit/config/state (administrator only).
- Bridge: state directory `audit.log` (JSONL), systemd `journalctl -u pcwake-bridge`.
- Android: in-app command history, exportable to CSV. No tokens are stored in
  logs.
