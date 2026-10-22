# Installing SatMe-Node on a Raspberry Pi

*[Version française](installer-satme-node.fr.md)*

**SatMe-Node** puts the MiniTiouner (QO-100's wide band, DATV) next to the antenna, on a Raspberry Pi, and drives it over the network from [SatMe-PC](station-pc.md) or the phone. The node only receives: LongMynd runs there and sends its status and the video stream (0.3 to 2 Mbit/s). Nothing is decoded on it, which is why a **Raspberry Pi 3** is enough.

## What you need

- A Raspberry Pi 3 (B or B+), 4 or 5, its power supply, a microSD card of 8 GB or more.
- **Ethernet** preferably (the Pi 3's Wi-Fi is weak by a dish).
- The MiniTiouner (Pro TS2 included), its own supply if it has one, and the LNB's cable.

## 1. Prepare the card

With **Raspberry Pi Imager** (raspberrypi.com/software):

- Device: your Pi. System: **Raspberry Pi OS Lite (64-bit)**.
- Settings (the gear): host name **`satme-node`**, a user and password, **SSH enabled**, Wi-Fi only if no cable.

Put the card in the Pi, plug in the network, power it up, wait a minute.

## 2. Install

From a PC on the same network, the guided install (it sees a Raspberry Pi without a desktop and offers SatMe-Node):

```
ssh <user>@satme-node.local
curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
bash installe-satme.sh
```

Or by hand:

```
ssh <user>@satme-node.local
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
tar xf satme-pc.tar
sudo ./satme/noeud/installe-noeud.sh
```

About ten minutes on a Pi 3. The script installs Java, builds LongMynd (BATC), lets it open the MiniTiouner (udev), installs the bladeRF tool and its FPGA image, copies SatMe to `/opt/satme` and starts the **satme-noeud** service (port 8090). It ends with the node's **address** and its **six-digit code**: write them down.

Then plug the MiniTiouner into the Pi (or unplug and plug it in again).

## 3. Join it

- **SatMe-PC**: DATV tab → MiniTiouner **Changer** → the node's address (`192.168.1.30` or `satme-node.local`) and its code → **Associer**.
- **The phone**: QO-100 screen → **DATV** → the node's address (its IP: phones do not always find `.local` names) and its code → **Join**.

Each keeps the key the node gave: the node, the PC or the phone can restart without joining again. The code again, on the Pi: `journalctl -u satme-noeud | grep Code`.

## Checking

- `http://satme-node.local:8090/` in a browser: what the node is and what its MiniTiouner does (never its code).
- On the Pi: `systemctl status satme-noeud`, `journalctl -u satme-noeud -f`.
- "MiniTiouner introuvable": plugged in? "pas d'accès": unplug and plug it in again (the udev rule applies on plugging).

## Update

On the Pi, the same three commands as in step 2. Settings, code and keys are kept in `/var/lib/satme-noeud`.

## Safety

- Without its key the node only says what it is; what changes it is a POST.
- Its **LNB supply stays off** unless chosen from SatMe-PC or the phone (13 V or 18 V): never when something else powers the LNB.
- Meant for your home network: do not open the port to the Internet (no encryption).

## Raspberry Pi 4 or 5

Installed the same way. They leave room for what can come to the node next — the rotator, the rig, the SDR dongle at the mast — the Pi 3 staying a MiniTiouner node.
