# SatMe-PC

**Français** · [English below](#english)

SatMe-PC suit les satellites radioamateurs depuis votre ordinateur (Linux, Windows, Raspberry Pi) : passages, pointage, Doppler, carnet de trafic, décodage SSTV / SSDV / FT8 / APRS / télémesure / CW en réception, DATV QO-100. Il s'utilise dans le navigateur, sur ce PC ou sur votre réseau. Une application Android existe aussi : [Google Play](https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo).

## Dernière version : 2026.11.6

- **Horloge de bureau AWTRIX (Ulanzi TC001)** comme afficheur de la station (module facultatif, coupé d'origine) : passage et compte à rebours, fréquence, contacts du jour, les mascottes ; affichage seul, sans son.
- **Suivi** : la liste des passages s'affiche instantanément même avec SatNOGS coché (elle mettait plus de 20 s) ; un satellite suivi ne démarre plus sur un préréglage FT4 ; un même satellite sous deux numéros n'apparaît qu'une fois ; fiche RS-90 (Luča-1).
- **FT4 / FT8** : « Caler mon émission », contact en cours, file « Qui m'appelle », stations déjà notées, **LOS** de chaque station ; **Journal** : liaisons par le satellite et tableau « Contacts et CQ » ; **APRS OTA** ; **EME**.
- Les notes complètes de la version sont reprises : ce qui avait été omis de la 2026.11.5 y figure.

Le détail est dans la [page de la version](https://github.com/f4ioz/SatMe/releases/tag/satme-pc-2026.11.6).

## Télécharger

Les versions sont dans l'onglet **[Releases](https://github.com/f4ioz/SatMe/releases/latest)** :

- Linux / Raspberry Pi : `satme-pc.tar` (Java 17 ou plus) ;
- Windows : `satme-pc-windows.msi` (installeur) ou `satme-pc-windows.zip` (sans installer) ;
- `SHA256SUMS` pour vérifier le téléchargement, et le manuel en PDF.

Pour essayer sans matériel : `satme --demo`. Le guide complet est dans le programme (touche **F1**) et sur **[f4ioz.fr](https://f4ioz.fr)**.

## Licence et avertissement

SatMe est un logiciel libre, **GNU GPL version 2 ou ultérieure**. Le développement a lieu dans un dépôt privé ; chaque version publiée ici est accompagnée de son **code source complet** (`satme-pc-<version>-source.tar.gz`). Les composants tiers sont listés dans `THIRD-PARTY.md`, joint à chaque source. Aucune garantie.

SatMe peut piloter un émetteur : l'opérateur reste seul responsable de ses émissions et du respect de sa licence et de la réglementation radioamateur. Aucune émission ne part sans un geste de sa part.

## Contact

Olivier Gouyen, **F4IOZ** : [f4ioz.fr](https://f4ioz.fr) · [Issues](https://github.com/f4ioz/SatMe/issues) pour signaler un défaut.

---

## English

SatMe-PC tracks amateur radio satellites from your computer (Linux, Windows, Raspberry Pi): passes, pointing, Doppler, logbook, and receive-only decoding of SSTV / SSDV / FT8 / APRS / telemetry / CW, plus QO-100 DATV. It runs in your browser, on this PC or across your network. An Android app exists too: [Google Play](https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo).

### Latest version: 2026.11.6

- **AWTRIX desk clock (Ulanzi TC001)** as the station's display (optional module, off by default): pass countdown, frequency, the day's contacts, the mascots; display only, no sound.
- **Tracking**: the passes list appears instantly even with SatNOGS ticked (it took over 20 s); a satellite followed no longer starts on an FT4 preset; one satellite under two numbers shows once; RS-90 (Luča-1) card.
- **FT4 / FT8**: « Align my transmission », current contact, « Who is calling me » list, stations already logged, each station's **LOS**; **Journal**: links through the satellite and the « Contacts and CQs » table; **APRS OTA**; **EME**.
- The release notes are complete: what was left out of 2026.11.5 is in them.

Details are on the [release page](https://github.com/f4ioz/SatMe/releases/tag/satme-pc-2026.11.6).

### Download

Versions are under **[Releases](https://github.com/f4ioz/SatMe/releases/latest)**:

- Linux / Raspberry Pi: `satme-pc.tar` (Java 17 or later);
- Windows: `satme-pc-windows.msi` (installer) or `satme-pc-windows.zip` (no install);
- `SHA256SUMS` to check the download, and the PDF manual.

To try it without any hardware: `satme --demo`. The full guide is inside the program (key **F1**) and on **[f4ioz.fr](https://f4ioz.fr)**.

### Licence and disclaimer

SatMe is free software, **GNU GPL version 2 or later**. Development takes place in a private repository; every version published here comes with its **complete corresponding source code** (`satme-pc-<version>-source.tar.gz`). Third-party components are listed in `THIRD-PARTY.md`, included in each source archive. No warranty.

SatMe can drive a transmitter: the operator alone is responsible for what is transmitted and for complying with their licence and amateur radio regulations. Nothing is ever transmitted without the operator's own action.

### Contact

Olivier Gouyen, **F4IOZ**: [f4ioz.fr](https://f4ioz.fr) · [Issues](https://github.com/f4ioz/SatMe/issues) to report a bug.
