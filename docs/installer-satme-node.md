# Installing SatMe-Node on a Raspberry Pi

*[Version française](installer-satme-node.fr.md)*

**SatMe-Node** puts QO-100's receivers and transmitter next to the antenna, on a Raspberry Pi, and drives them over the network from [SatMe-PC](station-pc.md) or the phone:

- **DATV**: the MiniTiouner (LongMynd) — or SatMe's DVB-S2 demodulator with a Pluto or a bladeRF;
- **SDR**: a bladeRF or a Pluto, whose narrow band reaches SatMe-PC like a board plugged in there (QO-100 cockpit, waterfall, FT8, beacon, recording);
- **transmitting** (Pluto or bladeRF), DATV and SSTV, off at first;
- **its health**: temperature, load, memory, microSD, network, USB, services, log.

## Which Raspberry Pi

| What the node does | Pi |
|---|---|
| MiniTiouner only | a **Pi 3** (B or B+) is enough |
| MiniTiouner + bladeRF (SDR, transmitting) | **Pi 4 or Pi 5**, the bladeRF on a **USB 3** port (blue) |
| MiniTiouner + Pluto | Pi 4 or Pi 5 advised |

## What you need

- The Raspberry Pi, its **official power supply** (3 A for a Pi 4, 5 A for a Pi 5: the bladeRF draws current, a weak supply throttles the Pi), a microSD card of 16 GB or more.
- **Ethernet**: the SDR stream is about 34 Mbit/s at the full rate (1.058 MS/s); over Wi-Fi, choose 529 or 265 kS/s.
- The MiniTiouner (Pro TS2 included) and its supply if it has one; the bladeRF or the Pluto.
- With a twin-output LNB: **output 1 → MiniTiouner**, which powers the LNB at 18 V (horizontal, DATV); **output 2 → bladeRF** through a **13 V bias injector with a DC block** (vertical, narrow band) — the bladeRF must never get the LNB's voltage.

## 1. Prepare the card

With **Raspberry Pi Imager** (raspberrypi.com/software):

- Device: your Pi. System: **Raspberry Pi OS Lite (64-bit)**.
- Settings (the gear): host name **`satme-node`**, a user and password, **SSH enabled**, Wi-Fi only if no cable.

Put the card in the Pi, plug in the network, power it up, wait a minute.

## 2. Install

From a PC on the same network, log in to the Pi:

```
ssh <user>@satme-node.local
```

Then, **in the home folder** (where `ssh` lands), the guided install (it sees a Raspberry Pi without a desktop and offers SatMe-Node):

```
curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
bash installe-satme.sh
```

Or by hand:

```
cd ~
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
tar xf satme-pc.tar
sudo ./satme/noeud/installe-noeud.sh
```

The script is not in the home folder but in **`satme/noeud/`**, made by `tar` (it is already executable). "command not found": check with `ls satme/noeud/` that the archive was unpacked.

About ten minutes on a Pi 3, a little more on a Pi 4 (LongMynd is compiled). The script:

- installs **Java** (the first the system offers: 21 on a recent Raspberry Pi OS, Debian 13 "trixie");
- builds **LongMynd** (BATC) and opens the MiniTiouner to it (udev);
- installs the **bladeRF** tool (`bladeRF-cli`), its USB access and its **FPGA image** (xA4 and xA9);
- copies SatMe to `/opt/satme` and starts the **satme-noeud** service (port 8090), its user in the `plugdev` (bladeRF) and `video` (the Pi's temperature) groups.

It ends with the node's **address** and its **six-digit code**: write them down. Running it again breaks nothing: it redoes everything.

Then plug the MiniTiouner and the bladeRF (USB 3) into the Pi, or unplug and plug them in again. The **Pluto** needs nothing: plugged in over USB it shows up as a network card (192.168.2.1).

## 3. Join it

- **SatMe-PC**: DATV tab → MiniTiouner **Changer** → the node's address (`192.168.1.30` or `satme-node.local`) and its code → **Associer**. Then:
  - **SDR** tab → receiver **"SDR du SatMe-Node"**: the narrow band of the node's bladeRF or Pluto;
  - **Station › SatMe-Node**: its page — health, boards and their roles, services and their actions, version (and how to update it).
- **The phone**: QO-100 screen → **DATV** → the node's address (its IP: phones do not always find `.local` names) and its code → **Join**.

Each keeps the key the node gave: the node, the PC or the phone can restart without joining again. The code again, on the Pi: `journalctl -u satme-noeud | grep Code`.

## Checking

- `http://satme-node.local:8090/` in a browser: what the node is and what its boards do (never its code).
- On the Pi: `systemctl status satme-noeud`, `journalctl -u satme-noeud -f`.
- `bladeRF-cli -p`: is the bladeRF seen? The health card flags a bladeRF on **USB 2** (black port): move it to a blue one.
- "MiniTiouner introuvable": plugged in? "pas d'accès": unplug and plug it in again (the udev rule applies on plugging).
- High temperature or "throttled" in the health card: a heatsink or a fan, a better supply.

## Update

On the Pi, the same commands as in step 2 (latest published release). Settings, code and keys are kept in `/var/lib/satme-noeud`. **Relancer SatMe sur le node** from SatMe-PC (Station › SatMe-Node › Services) only restarts the service, not the Pi.

### Restarting or switching off the Pi

From SatMe-PC, Station › SatMe-Node › Services: **Redémarrer le Pi** and **Éteindre le Pi** (also on the node's own page opened on the Pi). The node first stops the transmission (only when asked in the dialog; otherwise it refuses while transmitting), DATV reception and its LNB supply, the SDR stream, writes its settings, answers, then runs `sudo -n /usr/bin/systemctl reboot` or `poweroff`. The installer grants **those two commands only** to the service's `satme` account, in `/etc/sudoers.d/satme-noeud` (checked by `visudo -cf`, mode 0440). If SatMe-PC says the right is missing: run the installer again (`sudo ./satme/noeud/installe-noeud.sh`). A node started by hand (not by its service) refuses. **Once off, the Pi only comes back on site**: remove and restore its power.

## Safety

- Without its key the node only says what it is; what changes it is a POST.
- Its **LNB supply stays off** unless chosen from SatMe-PC or the phone (13 V or 18 V): never when something else powers the LNB.
- **The node's transmitter** needs two boxes, both off at first: "Émettre par le SatMe-Node" in SatMe-PC, and **"émission autorisée"**, which can only be ticked on the node itself (its page opened on the Pi, or through an SSH tunnel). The node has its own bands, duration and power cap; it stops transmitting if SatMe-PC is silent for 1.5 s or disconnects. One board never receives and transmits at once.
- Meant for your home network: do not open the port to the Internet (no encryption).
