<p align="center"><img src="docs/images/icon.png" width="96" alt="SatMe"></p>

<h1 align="center">SatMe</h1>

<p align="center">
Poursuite de satellites radioamateurs sur Android — passages, pointage, Doppler, carnet.<br>
<a href="https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo">Google Play</a> ·
<a href="README.md">English</a> ·
<a href="docs/API-v1.fr.md">API</a>
</p>

<p align="center">
<img src="docs/images/passes.webp" width="24%" alt="Passages">
<img src="docs/images/tracking.webp" width="24%" alt="Suivi en temps réel">
<img src="docs/images/compass.webp" width="24%" alt="Boussole Bluetooth">
<img src="docs/images/grid.webp" width="24%" alt="Carrés locator">
</p>
<p align="center">
<img src="docs/images/qrv-photo.webp" width="24%" alt="Photo QRV">
<img src="docs/images/amsat-report.webp" width="24%" alt="Rapport de statut AMSAT">
<img src="docs/images/usb-knob.webp" width="24%" alt="Molette USB">
</p>

## Fonctions

| | |
|---|---|
| **Passages** | Prévisions SGP4 hors ligne, AOS/LOS, élévation max, tracé polaire, alertes. Statut AMSAT et SatNOGS. |
| **Pointage** | Boussole du téléphone ou module Bluetooth WitMotion sur la flèche, calibrage guidé. Rotors GS-232 et rotctld. |
| **Fréquences** | Correction Doppler RX/TX, transpondeurs linéaires et FM, pilotage CAT. QO-100. |
| **Carnet** | Clavier d'indicatifs à une main, synchro Wavelog / Cloudlog, QRZ, LoTW, ADIF, QSL PDF. |
| **Partage** | Page web en direct pour le public, écoute déportée entre deux téléphones. |
| **Poste de commande** | Page servie par le téléphone, utilisée depuis un PC : saisie au clavier, cadran polaire, enregistrement. Voir l'[API v1](docs/API-v1.fr.md). |
| **En plus** | Carte des carrés locator, photo QRV, SSTV, radiosondes, FT8/FT4. |

## Matériel pris en charge

| Type | Modèles |
|---|---|
| Postes | Icom IC-9700 · paire de Yaesu FT-817 · FT-817 + clé SDR |
| SDR | RTL2832U |
| Boussole | WitMotion WT901BLE, WT9011DCL-BT50 |
| Rotors | Yaesu GS-232 · Hamlib rotctld (réseau) |
| Série USB | CP210x, FTDI, PL2303, CH340/341/9102, MCP2200/2221, CDC |
| Divers | Cartes son USB · molette/clavier USB (touches apprises par appui) |

## Documentation

- [API du poste de commande v1](docs/API-v1.fr.md) : protocole HTTP pour d'autres clients
- [Composants tiers](THIRD-PARTY.fr.md)
- [Politique de confidentialité](docs/privacy-policy.html)

## Compiler

JDK 21, SDK Android 36.

```sh
./gradlew testDebugUnitTest assembleDebug
```

La signature de publication lit `keystore.properties` à la racine (non versionné) : `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Licence

[GPL-2.0-ou-ultérieure](LICENSE). Licences tierces dans [THIRD-PARTY.fr.md](THIRD-PARTY.fr.md).

## Remerciements

T. S. Kelso · John Magliacane KD2BD · Neoklis Kyriazis 5B4AZ · David Johnson G4DPZ · AMSAT · SatNOGS · Celestrak · radio-club F6KMX

73 de **F4IOZ**
