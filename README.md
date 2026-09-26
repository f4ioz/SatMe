<p align="center"><img src="docs/images/icon.png" width="96" alt="SatMe"></p>

<h1 align="center">SatMe</h1>

<p align="center">
Amateur radio satellite tracking for Android — passes, pointing, Doppler, logging.<br>
<a href="https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo">Google Play</a> ·
<a href="README.fr.md">Français</a> ·
<a href="docs/API-v1.md">API</a>
</p>

<p align="center">
<img src="docs/images/passes.webp" width="24%" alt="Passes">
<img src="docs/images/tracking.webp" width="24%" alt="Live tracking">
<img src="docs/images/compass.webp" width="24%" alt="Bluetooth compass">
<img src="docs/images/grid.webp" width="24%" alt="Grid squares">
</p>
<p align="center">
<img src="docs/images/qrv-photo.webp" width="24%" alt="QRV photo">
<img src="docs/images/amsat-report.webp" width="24%" alt="AMSAT status report">
<img src="docs/images/usb-knob.webp" width="24%" alt="USB knob">
</p>

## Features

| | |
|---|---|
| **Passes** | Offline SGP4 predictions, AOS/LOS, max elevation, polar plot, alerts. AMSAT and SatNOGS status. |
| **Pointing** | Phone compass or WitMotion Bluetooth module on the boom, guided calibration. GS-232 and rotctld rotators. |
| **Frequencies** | RX/TX Doppler correction, linear and FM transponders, CAT control. QO-100. |
| **Log** | One-handed callsign keypad, Wavelog / Cloudlog sync, QRZ lookup, LoTW, ADIF, PDF QSL. |
| **Sharing** | Live web page for the audience, remote listening between two phones. |
| **Control desk** | Web page served by the phone, used from a PC: keyboard entry, polar dial, recording. See [API v1](docs/API-v1.md). |
| **Extras** | Grid squares map, QRV photo, SSTV, radiosondes, FT8/FT4. |

## Supported hardware

| Type | Models |
|---|---|
| Radios | Icom IC-9700 · pair of Yaesu FT-817 · FT-817 + SDR dongle |
| SDR | RTL2832U |
| Compass | WitMotion WT901BLE, WT9011DCL-BT50 |
| Rotators | Yaesu GS-232 · Hamlib rotctld (network) |
| USB serial | CP210x, FTDI, PL2303, CH340/341/9102, MCP2200/2221, CDC |
| Other | USB sound cards · USB knob/keypad (keys learned by pressing) |

## Documentation

- [Control desk API v1](docs/API-v1.md): HTTP protocol for third-party clients
- [Third-party components](THIRD-PARTY.md)
- [Privacy policy](docs/privacy-policy.html)

## Build

JDK 21, Android SDK 36.

```sh
./gradlew testDebugUnitTest assembleDebug
```

Release signing reads `keystore.properties` at the root (not committed): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## License

[GPL-2.0-or-later](LICENSE). Third-party licenses in [THIRD-PARTY.md](THIRD-PARTY.md).

## Credits

- T. S. Kelso — SGP4/SDP4 models and CelesTrak (orbital elements)
- John Magliacane KD2BD — PREDICT
- Neoklis Kyriazis 5B4AZ — C translation of SGP4/SDP4, used by PREDICT
- David Johnson G4DPZ — predict4java (pass and Doppler computation)
- Kārlis Goba — ft8_lib (FT8/FT4 error-correcting code tables)
- K9AN, G4WJS and K1JT — FT8 and FT4 protocols (QEX article)
- Peter Goodhall 2M0SQL — Cloudlog
- The Wavelog team — Wavelog, a Cloudlog fork
- IS0GRB — QO-100 WebSDR (frequency reference)
- John Morris G4ANB — Maidenhead locator squares
- AMSAT and SatNOGS — satellite status
- F6KMX radio club

73 de **F4IOZ**
