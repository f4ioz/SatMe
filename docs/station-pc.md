# SatMe on a PC — the station in a browser

*First version, October 2026 (branch `moteur-commun`). The Android app stays the reference; the PC is added to it.*

SatMe runs on a PC (Linux, Windows, Raspberry Pi) and is driven from a browser: the next passes, the satellite followed on the sky, Doppler-corrected frequencies, the rig (CAT) and the rotator. The core is **the same code as on the phone**: the Doppler loop (`StationCat`) and rotator tracking (`StationRotor`) come from the shared `:moteur` module.

## Running it

Java 17 or later is needed.

```
./gradlew :pc:installDist
pc/build/install/satme/bin/satme          # Linux, macOS
pc\build\install\satme\bin\satme.bat      # Windows
```

then open **http://localhost:8090/**.

| Option | Effect |
|---|---|
| `--port 8090` | the page's port |
| `--reseau` | page open to the other machines of the network (a station at the foot of the mast, driven from the house); without it, this PC only |
| `--dossier DIR` | where settings and elements are kept (`~/.satme`, `%APPDATA%\SatMe` on Windows) |

## What this version does

- **Passes** over the next 24 hours, for the satellites of the AMSAT bulletin; a click follows the satellite.
- **Satellite followed**: azimuth, elevation, range, AOS/LOS countdown, the pass drawn on the sky, the mast's position.
- **Frequencies**: SatNOGS active transmitters, Doppler-corrected RX and TX; on a linear transponder, ±100 Hz to ±5 kHz.
- **Rig (CAT)**: Icom IC-9700, IC-9100, IC-910H (CI-V) and Kenwood TS-2000 (SAT mode), with the phone's Doppler loop; a **simulated** IC-9700 and TS-2000 to try without plugging anything in.
- **Rotator**: GS-232 on a serial port, rotctld over the network, or simulated; tracking, park, waiting at the rise point, go to an azimuth; settings (end stop, maximum azimuth, flip, park…).
- **The phone's settings**: its backup (Settings → Backup, a `.json` file) is taken on the page — callsign, locator, rotator, rig. The phone's USB port numbers are **not** taken, and the rotator arrives **not tracking**: it moves only once "Follow the satellite" is ticked.

## Hardware safety

- **Nothing opens by itself.** A serial port opens only when it is chosen and "Connect" is clicked. No automatic search.
- **RTS and DTR low** by default. Icom CAT raises them (its USB bridges need it): **pick the right rig's port**. The TS-2000 keeps them low, and a port that echoes our own words (a RigExpert's PTT port) is closed at once.
- On Linux the system raises RTS/DTR for an instant on every port opening, before SatMe can lower them: one more reason to open only the port meant.
- **No transmit command** is ever sent (`TX`, `KY` refused by the TS-2000 driver).
- Elements come from **AMSAT** only; CelesTrak is never asked from the PC.

## Before the first try

- **Linux**: be in the `dialout` group (`sudo usermod -aG dialout $USER`, then log in again).
- **Windows**: install the adapter's driver (CP210x, FTDI, CH340); the rig shows as `COM3`, `COM4`…
- Start with the **simulated rig** and **simulated rotator**, then plug in the real hardware one piece at a time.

## Not yet on the PC

FT-817/818 and IC-705 pairs, TH-D72, APRS and KISS, sound recording, live SSTV and METEOR, SDR dongle, pass journal, contact log. The phone is still needed for those; the PC pages that go with the phone (desk, journal, contact sheet) are still served by the phone.
