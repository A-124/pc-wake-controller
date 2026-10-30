# BIOS and Windows Wake-on-LAN guide

Wake-on-LAN (WoL) is a hardware feature. This project can send correct magic
packets, but the PC, NIC firmware, BIOS/UEFI and Windows driver must all keep the
NIC armed while the PC sleeps or shuts down.

Exact BIOS labels vary by motherboard and firmware version. This guide describes
the settings to look for, not a single board layout.

## Requirements

- **Wired Ethernet.** Onboard Ethernet is strongly preferred. Most Wi-Fi and USB
  Ethernet adapters do not stay armed in low-power states.
- A motherboard/NIC that supports Wake on Magic Packet from the desired state
  (S3 sleep and/or S5 shutdown).
- Standby power. The NIC LEDs and motherboard Ethernet LEDs must remain lit after
  shutdown. If they are dark, firmware/power settings are cutting standby power.
- Phone and PC on the same LAN/subnet for local wake. Guest Wi-Fi, AP isolation
  and separate VLANs can block broadcasts.

## 1. BIOS/UEFI

Open BIOS setup (usually `Del`, `F2`, or `F10` at boot) and look under advanced
chipset, power management, or onboard-device menus:

- Enable **Wake on LAN**, **Wake On PCI-E**, **Resume by PCI-E Device**, or
  similarly named power-on event.
- Disable **ErP Ready**, **ErP/EuP**, and deep-sleep modes that cut standby power.
  Some boards have a separate "Deep S5" or "LAN wake in S5" option.
- Disable **Fast Boot** while testing (it can skip device initialization).
- If present, keep the onboard LAN controller enabled and disable options that
  power it off in standby.
- Save and exit, then **remove AC power for ~10 seconds** so the NIC firmware
  reinitializes. Boot Windows and shut down to verify standby LEDs.

Some boards wake reliably from **Sleep (S3)** but not from **full shutdown
(S5)**. This is firmware/hardware-specific. Do not assume S5 wake works until
you test it on your board.

## 2. Install the vendor LAN driver

Windows' generic Ethernet driver often exposes **no** Wake-on-Magic-Packet
settings. If Device Manager shows no energy/wake properties:

1. Download the **Windows 10/11 x64 LAN driver** from your motherboard or NIC
   vendor's official support page. Do not install drivers from random download
   sites.
2. Install it and reboot. Verify in Device Manager that the driver provider is
   the NIC vendor, not Microsoft's generic driver.
3. Open Device Manager → Network adapters → wired NIC → Properties.

### Advanced tab (names vary)

- `Wake on Magic Packet` → **Enabled**
- `Wake on pattern match` → **Disabled** for the most reliable magic-packet wake
- `Shutdown Wake-On-Lan` / `Wake on LAN after shutdown` → **Enabled** if present
- `Green Ethernet`, `Energy-Efficient Ethernet`, `Gigabit Lite`,
  `Ultra Low Power Mode` → **Disabled** while testing
- `WOL & Shutdown Link Speed` → a low speed such as `10 Mbps First` is normal
  and can improve standby compatibility

### Power Management tab

- Enable **Allow this device to wake the computer**
- Enable **Only allow a magic packet to wake the computer**
- `Allow the computer to turn off this device to save power` should remain
  enabled.

### Verify from an elevated Command Prompt

```bat
powercfg /devicequery wake_armed
powercfg /devicequery wake_programmable
powercfg /a
```

The wired NIC should appear in the wake-armed list. If it does not, the driver or
BIOS is not exposing wake capability.

## 3. Windows power settings

- **Disable Fast Startup** while testing:
  Control Panel → Power Options → Choose what the power buttons do → Change
  settings that are currently unavailable → uncheck *Turn on fast startup*.
  Fast Startup can turn "shutdown" into a hybrid state where some NICs do not
  arm WoL.
- Check available sleep states with `powercfg /a`. Modern Standby (S0ix) behaves
  differently from S3; WoL support depends on firmware/driver.
- For testing, set PCI Express Link State Power Management to **Off** in the
  active power plan's advanced settings.
- After changing adapter settings, run `ipconfig /release` + `ipconfig /renew`
  or disable/enable the adapter; some drivers apply WoL changes only after a
  restart.

## 4. Find the correct MAC, IP and broadcast

```bat
ipconfig /all
```

Use the **Physical Address** of the wired Ethernet adapter, not Wi-Fi, Bluetooth,
Hyper-V, VPN, WAN Miniport or virtual adapters. A `/24` network example:
IP `192.168.1.50`, mask `255.255.255.0` → directed broadcast `192.168.1.255`.

In the Android app:
- MAC from the wired adapter
- PC IPv4 (optional, for reference)
- LAN broadcast (directed broadcast, or `255.255.255.255` only if your network
  routes it)
- UDP port 9 (standard; some setups use 7)

Set a DHCP reservation for the PC in your router so the agent's address remains
stable.

## 5. Test order

1. Wake the PC normally and confirm the agent is reachable in the app.
2. Put the PC to **Sleep** manually, wait ~10 seconds, then tap **Power on** in
   the app. Confirm the agent becomes Online.
3. Test **full shutdown** wake. If Sleep works but S5 does not, revisit ErP,
   Fast Startup, BIOS S5 options and vendor-driver settings.
4. Test remote wake through the Wake Bridge with the PC asleep/shut down and the
   bridge still online.

A sent magic packet is never proof of boot. Only an authenticated agent response
proves the PC is online.

## 6. Test record

| Test | Expected | Result |
|---|---|---|
| Wake from Sleep | Agent returns Online | ☐ |
| Wake from full shutdown (if supported) | Agent returns Online | ☐ |
| Wake from another subnet/bridge | Bridge sends, PC wakes | ☐ |
| Shutdown / restart / sleep commands | Agent accepts, PC acts | ☐ |
| Unauthorized request rejected | HTTP 401 | ☐ |

Record actual results on your hardware; see
[`TEST_REPORT.md`](TEST_REPORT.md) for the project's test scope.
