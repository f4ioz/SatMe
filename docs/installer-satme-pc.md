# Installing SatMe-PC on Linux

*[Version française](installer-satme-pc.fr.md) · What SatMe-PC does: [SatMe-PC](station-pc.md)*

SatMe-PC is the whole station in a browser. It is one package, `satme-pc.tar`, for Debian, Ubuntu, Linux Mint, Raspberry Pi OS… Nothing to compile.

## 1. Java

SatMe-PC needs Java 17 or later:

```
sudo apt install default-jre
java -version
```

## 2. Download and start

```
cd ~
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS
tar xf satme-pc.tar
~/satme/bin/satme
```

Then open **http://localhost:8090/** in your browser. The first start downloads the orbital elements (from `gp.f4ioz.fr`), which takes a few seconds.

`sha256sum` must say `satme-pc.tar: OK`; otherwise download again.

| Option | Effect |
|---|---|
| `--port 8090` | the page's port |
| `--reseau` | the page open to the other machines of your network; without it, this PC only |
| `--dossier DIR` | where settings, log, journal and recordings are kept (`~/.satme` by default) |
| `--version` | the version installed |

## 3. What each function needs

Install only what you use.

| For | Install |
|---|---|
| **Rig (CAT) or rotator** on a serial/USB port | be in the `dialout` group: `sudo usermod -aG dialout $USER`, then log out and in again |
| **Videos, GIFs, the phone's recordings**, DATV pictures | `sudo apt install ffmpeg` |
| **RTL-SDR dongle** (SDR, radiosondes, METEOR, QO-100 panorama) | `sudo apt install rtl-sdr` (SatMe-PC starts `rtl_tcp` itself) |
| **DATV with a MiniTiouner** plugged into this PC | [LongMynd](#datv-on-this-pc) and its udev rule, below — or a [SatMe-Node](installer-satme-node.md) by the antenna |
| **The phone joined by QR code** | your firewall must let the page's port + 1 through: `sudo ufw allow from 192.168.0.0/16 to any port 8091 proto tcp` (only if `ufw` is active) |

Nothing opens by itself: a serial port opens only once chosen and "Connect" clicked, RTS/DTR stay low, and no transmit command is ever sent. Start with the simulated rig and rotator.

### DATV on this PC

```
sudo apt install build-essential libusb-1.0-0-dev libasound2-dev
cd ~ && curl -L https://github.com/BritishAmateurTelevisionClub/longmynd/archive/refs/heads/master.tar.gz | tar xz
make -C longmynd-master && mkdir -p ~/longmynd && cp longmynd-master/longmynd ~/longmynd/
echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
```

Unplug and plug the MiniTiouner in again. SatMe-PC finds LongMynd in `~/longmynd/` (or in the `PATH`). Plugged in, the MiniTiouner also shows as two serial ports (`/dev/ttyUSB…`): never choose them as the rig's or the rotator's port.

## 4. Start it with the session (optional)

```
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/satme-pc.service <<'EOF'
[Unit]
Description=SatMe-PC

[Service]
ExecStart=%h/satme/bin/satme
Restart=on-failure

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now satme-pc
```

## 5. Update, remove

- **Update**: the same commands as in step 2 (stop SatMe-PC first). Settings, log, journal and recordings stay in `~/.satme`.
- **Remove**: `rm -rf ~/satme` (and `~/.satme` to remove your data too).

## Building it yourself

```
git clone https://github.com/f4ioz/SatMe.git && cd SatMe
./gradlew :pc:installDist
pc/build/install/satme/bin/satme
```

Building needs a JDK 17 or later and the Android SDK (the project also holds the phone app).
