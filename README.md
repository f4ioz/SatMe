# SatMe-PC

**Français** · [English below](#english)

SatMe-PC suit les satellites radioamateurs depuis votre ordinateur (Linux, Windows, Raspberry Pi) : passages, pointage, Doppler, carnet de trafic, décodage SSTV / SSDV / FT8 / APRS / télémesure / CW en réception, DATV QO-100. Il s'utilise dans le navigateur, sur ce PC ou sur votre réseau. Une application Android existe aussi : [Google Play](https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo).

## Dernière version : 2026.11.5

- **FT4 / FT8 sur satellite, bien plus complet** : « Contact en cours » (la frise du QSO), file « Qui m'appelle » classée (nouveau pays, temps restant avant le LOS), stations déjà notées retirées des listes, **LOS** de chaque station, carte et radar avec la trace du satellite, préréglages de fréquence **TX + RX** modifiables (RS-44 FT4), calage de l'émission d'après son propre signal reçu.
- **Journal** : les indicatifs FT4/FT8 sur la carte et le globe, les **liaisons entre les stations par le satellite**, le tableau **« Contacts et CQ »** (CSV, en images, en carte de fin de vidéo).
- **APRS OTA** ([aprsota.org](https://aprsota.org/)) : un assistant PING → CQ → CHASE → position → QSL, un clic pour une trame.
- **EME** : largeur du Doppler et libration, séparations Lune–Soleil et Lune–Sgr A\*, STOP partout ; **Mes satellites** (TLE/OMM collé, émetteurs à la main) et le groupe « extra » du serveur GP.
- **Station** : interrupteur général « Tout déverrouiller » (décoché au démarrage), barre du haut allégée, le poste lu même sans satellite suivi, rotor **S.A.T. (CSN)**, liste des passages 1 000 fois plus rapide avec SatNOGS coché.

Le détail est dans la [page de la version](https://github.com/f4ioz/SatMe/releases/tag/satme-pc-2026.11.5).

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

### Latest version: 2026.11.5

- **FT4 / FT8 on satellites, much fuller**: « Current contact » (the QSO's strip), a ranked « Who is calling me » list (new country, time left before LOS), stations already logged taken out of the lists, each station's **LOS**, a map and radar with the satellite's track, editable **TX + RX** frequency presets (RS-44 FT4), and transmit timing aligned from your own received signal.
- **Journal**: FT4/FT8 callsigns on the map and the globe, the **links between stations through the satellite**, the **« Contacts and CQs »** table (CSV, as pictures, as a video end card).
- **APRS OTA** ([aprsota.org](https://aprsota.org/)): a PING → CQ → CHASE → position → QSL assistant, one click per frame.
- **EME**: Doppler width and libration, Moon–Sun and Moon–Sgr A\* separations, STOP everywhere; **My satellites** (pasted TLE/OMM, transmitters by hand) and the GP server's « extra » group.
- **Station**: a master « Unlock all » switch (unticked at start), a lighter top bar, the rig read even with no satellite followed, the **S.A.T. (CSN)** rotator, a passes list 1,000 times faster with SatNOGS ticked.

Details are on the [release page](https://github.com/f4ioz/SatMe/releases/tag/satme-pc-2026.11.5).

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
