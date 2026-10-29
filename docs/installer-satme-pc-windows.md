# Installing SatMe-PC on Windows

*[Version française](installer-satme-pc-windows.fr.md) · On Linux: [Installing SatMe-PC](installer-satme-pc.md) · What SatMe-PC does: [SatMe-PC](station-pc.md)*

All in one: Java, SatMe-PC, ffmpeg (videos, GIFs, DATV) and rtl_tcp (RTL-SDR dongle) in a single installer. Windows 10 or 11, 64-bit. Nothing else to install.

## 1. Download and install

From the [downloads page](https://github.com/f4ioz/SatMe/releases/latest):

- **`satme-pc-windows.msi`**: the installer. Double-click it. It installs for your account only, without administrator rights, with a Start menu entry (folder *SatMe*) and, if you wish, a desktop shortcut.
- or **`satme-pc-windows.zip`**: the same without installing. Unzip it anywhere, then run `SatMe-PC\SatMe-PC.exe`. This is the **portable mode** (a USB stick): a `donnees` folder beside `SatMe-PC.exe` then keeps all the settings and files.

**Windows may warn** ("Windows protected your PC"): SatMe-PC is not signed with a paid certificate. Click **More info**, then **Run anyway**. To check the file, compare its fingerprint with `SHA256SUMS`:

```
certutil -hashfile satme-pc-windows.msi SHA256
```

## 2. Start it

Start **SatMe-PC** from the Start menu. The page opens in the browser (**http://localhost:8090/**); the first start downloads the orbital elements (from `gp.f4ioz.fr`), a few seconds.

SatMe-PC has no window of its own: it runs in the background, with **its icon by the clock** (under the ^ arrow if hidden). A click opens the page again; a right click also offers the settings folder and **Quit SatMe-PC**. Starting it again while it runs simply opens its page.

Settings, log, journal and recordings are in `%APPDATA%\SatMe` (and what SatMe-PC writes while running: `satme-pc.log`) — **never in the program's folder**, which the installer replaces at each update. A `donnees` folder found in the program's folder (the ZIP's portable mode) is copied at start-up into `%APPDATA%\SatMe` (or `%APPDATA%\SatMe-portable-<date>` if that one is already in use), checked, then used; the original is kept and a window says where your data is. *Réglages › Démarrage* refuses a data folder inside the program's.

## 3. What each feature needs

| For | Do |
|---|---|
| **Rig (CAT) or rotator** | plug the interface in: it shows as a `COM…` port in the Station tab. Sometimes its maker's driver is needed (FTDI, CP210x, CH340); Windows often finds it by itself. |
| **RTL-SDR dongle** (SDR, radiosondes, METEOR, QO-100 panorama) | once: install the **WinUSB** driver with [Zadig](https://zadig.akeo.ie/) — plug the dongle in, *Options → List All Devices*, choose *Bulk-In, Interface (Interface 0)* (or *RTL2838UHIDIR*), *WinUSB*, **Replace Driver**. |
| **Sound card** (the rig's audio, SSTV, APRS, FT8) | nothing: it shows in the Sound tab. |
| **DATV with a MiniTiouner** | LongMynd runs on Linux only: the MiniTiouner goes on a [SatMe-Node](installer-satme-node.md) (a Raspberry Pi by the antenna), driven by SatMe-PC over the network. VLC (optional) opens the stream in its own window. |
| **The phone joining by QR code**, or the page open to other machines | the first time, Windows asks whether Java may use the network: allow it on **private networks**. |

Nothing opens by itself: a serial port opens only when chosen and "Connect" clicked, RTS/DTR stay low, and no transmit command is ever sent. Start with the simulated rig and rotator.

## 4. Options

The shortcut starts `SatMe-PC.exe` with no option. To add some (for instance to open the page to the other machines of the network), make a shortcut to `SatMe-PC.exe` with, at the end of its target:

| Option | Effect |
|---|---|
| `--port 8090` | the page's port |
| `--reseau` | the page open to the network's other machines; without it, this PC only |
| `--dossier DIR` | where settings, log, journal and recordings are kept |

## 5. Update, uninstall

- **Update**: quit SatMe-PC (icon by the clock → Quit), then install the new `.msi`: it replaces the old one, and removes only its own files. Your data stays in `%APPDATA%\SatMe`. **From 2026.10.33 or older**: first copy elsewhere a `donnees` folder kept in the program's folder — the old installer empties it one last time as it goes.
- **Uninstall**: *Settings → Apps → SatMe-PC → Uninstall*. Also delete `%APPDATA%\SatMe` to erase your data.
