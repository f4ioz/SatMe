# TellusMe — the SatMe ecosystem's terrestrial station

*[Version française](tellusme.fr.md)*

**TellusMe** is to the terrestrial station what SatMe-PC is to the satellite station: a small program on the PC (Linux, Windows, Raspberry Pi) with its page in the browser, built on SatMe's own engine. HF first; VHF/UHF terrestrial later.

## Status: draft, receive only

This is a first **draft** (*ébauche*). It **cannot transmit**, by design:

- there is no transmit button, no PTT, no power setting anywhere;
- on the IC-7000, a guard in front of the serial line only lets out the CI-V commands a receiver needs (read/set frequency and mode, read the S-meter); anything else — PTT (`1C`), CW messages (`17`), levels, menus — is refused before it reaches the wire. The port is opened with RTS and DTR **low** (on many interfaces they key the transmitter);
- on the Brick2 (openHPSDR Protocol 1), every datagram is checked before it leaves: the MOX bit is always 0, the transmit payload always zero, only the receive stream is started; the drive level is never written.

## What it does

- **Rig**: Icom IC-7000 over CI-V (address 70h, 19 200 or 9 600 baud, reconnects by itself when the rig falls silent), the **Brick2 SDR** over the network, or none (a manual VFO, for the logbook).
- **VFO**: frequency, tuning step, mouse wheel, typed frequency, band buttons 160 m to 6 m (plus 2 m and 70 cm on the IC-7000), mode buttons, S-meter.
- **Band plan**: the IARU Region 1 HF plan, simplified (CW, digital, phone/all modes, beacons), coloured under the VFO and under the spectrum. Display only — your national rules decide.
- **Brick2**: spectrum and waterfall (48, 96 or 192 kHz), click to tune, SSB/CW/AM/FM sound in the browser.
- **Propagation**: solar flux, K and A indices and band conditions from HamQSL, fetched at most once an hour.
- **DX cluster**: telnet, read only (your callsign is sent once, at login, nothing else), spots filtered by the current band, click a spot to tune.
- **Logbook**: SatMe's logbook engine in TellusMe's own folder, ADIF export and the ADIF check.

TellusMe keeps everything in its own folder (`~/.tellusme`, or `%APPDATA%\TellusMe` on Windows, or `$TELLUSME_HOME`), never in SatMe's.

## Running it (from the sources)

```
./gradlew :tellus:installDist
tellus/build/install/tellusme/bin/tellusme            # http://localhost:8092/
tellus/build/install/tellusme/bin/tellusme --simulation --port 8093
```

`--simulation` runs an IC-7000 and a Brick2 in memory, a DX cluster on the loopback and a stored propagation answer: nothing reaches a real rig, a serial port or the network. `--reseau` makes the page reachable from the LAN (local only otherwise).

The Brick2 is found at the address given in Réglages; with no address, **Chercher le Brick2** sends a discovery broadcast on the local network (only when you click it). While TellusMe listens, the radio is taken from other openHPSDR programs (Thetis, piHPSDR).

## Next

Transmission with safeguards, FT8 on HF, other rigs, VHF/UHF terrestrial, a logbook shared with SatMe.
