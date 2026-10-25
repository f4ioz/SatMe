# Nouveautés de SatMe-PC · What's new in SatMe-PC

<!--
Ce fichier est mis dans SatMe-PC à la compilation : la fenêtre « Quoi de neuf »
le lit (au premier démarrage d'une nouvelle version, et depuis le menu d'aide),
et la publication GitHub prend la section de sa version.

Avant de poser l'étiquette satme-pc-AAAA.MM.NN :
  1. renommer « ## Prochaine version » en « ## AAAA.MM.NN » (le numéro de l'étiquette) ;
  2. remettre une section « ## Prochaine version » vide au-dessus pour la suite.
Une section par version, la plus récente en haut, une partie « ### fr » et une
partie « ### en ». Des listes courtes : ce que l'utilisateur voit, où cliquer.
Markdown simple (gras, italique, `code`, listes, liens https).
-->

## Prochaine version

### fr
- **Planche SSTV stylée**, comme l'affiche d'une série ARISS : dans *Textes*, **Titres stylés** (*Série ARISS*, *Néon rétro*, *Écusson doré*, *Titre chrome*) habille le titre en un clic, le numéro tiré de l'événement ; chaque texte reste modifiable.
- Pour chaque texte : une **police** (sept polices libres livrées avec SatMe), un **effet** (contour, relief, néon, chrome, or, dégradé), l'italique, l'espacement des lettres, des filets lumineux.
- *Décor* : le **cadre des images** (fin et blanc, arrondi et ombré, néon de la couleur choisie), sept nouveaux fonds (l'ISS au-dessus de la Terre, ses panneaux solaires, la Terre de nuit, une nébuleuse de Spitzer, et deux fonds dessinés).
- **+ Affiche ISS** : une planche stylée toute prête, fond NASA, titre chrome et douze cases au cadre néon.

### en
- **Stylish SSTV sheet**, like an ARISS series poster: in *Texts*, **Styled titles** (*ARISS series*, *Retro neon*, *Gold badge*, *Chrome title*) dresses the title in one click, the number taken from the event; each text stays editable.
- For each text: a **font** (seven free fonts bundled with SatMe), an **effect** (outline, relief, neon, chrome, gold, gradient), italic, letter spacing, glowing rules.
- *Background*: the **picture frame** (fine white, rounded with a shadow, neon in the colour chosen), seven new backgrounds (the ISS above the Earth, its solar arrays, the Earth at night, a Spitzer nebula, and two drawn ones).
- **+ ISS poster**: a stylish sheet ready made, NASA background, chrome title and twelve boxes in neon frames.

## 2026.10.28

### fr
- **Configuration rapide** au premier lancement : indicatif, locator (ou latitude et longitude), nom, heure UTC ou locale, langue, élévation minimale, accès depuis le réseau et port, en quelques étapes. Toujours là dans *Réglages › Démarrage*.
- **Quoi de neuf** : cette fenêtre, au premier démarrage d'une nouvelle version ; ensuite dans le menu d'aide (**?**).
- **Nouvelle version disponible** : SatMe-PC regarde une fois par jour la dernière version publiée sur GitHub et le dit discrètement en haut de la page, avec ses notes et le bon téléchargement. Rien n'est téléchargé ni installé tout seul ; se coupe dans *Réglages › Démarrage*.
- **Réglages › Démarrage** : *Accès depuis le réseau* (`--reseau`), *Port* (`--port`) et *Dossier des données* (`--dossier`) se règlent dans la page, avec d'où vient chaque valeur ; pris en compte au prochain démarrage, **Redémarrer SatMe-PC** quand le lanceur le permet. La ligne de commande passe toujours avant.
- **Déploiement** (*Réglages › Sauvegarde*) : les réglages et les options de démarrage dans un fichier, sans les mots de passe ni les clés (sauf si on le demande), à importer sur un autre PC après un aperçu ; un fichier systemd ou Windows pour lancer SatMe-PC avec la session, à installer soi-même.

### en
- **Quick setup** at the first start: callsign, locator (or latitude and longitude), name, UTC or local time, language, minimum elevation, access from the network and port, in a few steps. Always there in *Réglages › Démarrage*.
- **What's new**: this window, at the first start of a new version; later from the help menu (**?**).
- **New version available**: once a day SatMe-PC looks at the latest release on GitHub and says so quietly at the top of the page, with its notes and the right download. Nothing is downloaded or installed by itself; turned off in *Réglages › Démarrage*.
- **Réglages › Démarrage**: *access from the network* (`--reseau`), *port* (`--port`) and *data folder* (`--dossier`) set from the page, with where each value comes from; taken into account at the next start, **Redémarrer SatMe-PC** where the launcher allows it. The command line always wins.
- **Deployment** (*Réglages › Sauvegarde*): the settings and the startup options in one file, without passwords or keys (unless asked), imported on another PC after a preview; a systemd or Windows file to start SatMe-PC with the session, to install yourself.

## 2026.10.27

### fr
- **SSTV, la galerie par diapo** : les copies d'une même diapo ensemble, une note de 0 à 100 par image, le numéro lu au bas des diapos ARISS ; **Brute | Corrigée | Fusion** dans la visionneuse — l'image reçue n'est jamais modifiée.
- **Le décodeur SSTV** plus solide : chaque image **re-décodée à la fin** depuis son propre son (gardée seulement si elle est mieux notée), le décodeur en direct ne se piège plus après un moment bruité.
- **Planche SSTV** liée à son événement, *Remplir par diapo*, la version de chaque case, le **rapport de l'événement** en page web ou en PDF avec le dessin du Montage.
- **Journal** : les versions SSTV et les éléments du rapport dans les images, GIF et vidéos du passage ; *Supprimer* un passage.
- **Options SSTV** : qualité de réception en direct, alerte de diapo manquante, son de chaque passage et sa rétention, image QSL, pack du diplôme ARISS.
- **Montage** : plusieurs plans de la même station. **Rotor** : *Calibrer le rotor…*, un assistant pas à pas (K3NG) et une correction logicielle.

### en
- **SSTV gallery by slide**: copies of one slide together, a mark from 0 to 100 per picture, the number read at the bottom of ARISS slides; **Brute | Corrigée | Fusion** in the viewer — the received picture is never changed.
- **A sturdier SSTV decoder**: each picture **decoded again at its end** from its own sound (kept only when it marks better); the live decoder no longer gets stuck after a noisy stretch.
- **SSTV sheet** tied to its event, *Remplir par diapo*, each box's version, the **event report** as a web page or a PDF with the Montage drawing.
- **Journal**: SSTV versions and report elements in the pass's pictures, GIFs and videos; *Supprimer* a pass.
- **SSTV options**: live reception quality, missing slide alert, each pass's sound and its retention, QSL picture, ARISS award pack.
- **Montage**: several plans of the same station. **Rotator**: *Calibrer le rotor…*, a step-by-step assistant (K3NG) and a software correction.

## 2026.10.26

### fr
- **Station › SatMe-Node** : une page pour le Raspberry Pi près de l'antenne — santé, rôle de chaque carte (réception DATV, SDR, émetteur), services, version, journal.
- Installation du node : Debian 13, LongMynd en 64 bits, accès au bladeRF.

### en
- **Station › SatMe-Node**: a page for the Raspberry Pi by the antenna — health, each board's role (DATV receiver, SDR, transmitter), services, version, log.
- Node install: Debian 13, 64-bit LongMynd, bladeRF access.

## 2026.10.25

### fr
- Les images SSTV portent le nom du satellite dont vient leur son (QO-100 par la clé SDR, pas le satellite suivi) ; une image se termine quand il ne reste que du bruit.
- Vignettes SSTV dans le journal, contacts du passage lisibles dans le carnet ; installation du node sur Debian 13 et le bladeRF.

### en
- SSTV pictures are named after the satellite their sound came from (QO-100 by the SDR dongle, not the satellite followed); a picture ends when only noise remains.
- SSTV thumbnails in the journal, the pass's contacts readable in the log; node install on Debian 13 and the bladeRF.

## 2026.10.24

### fr
- **L'IC-9700 émet depuis SatMe-PC** : PTT tenu par la page (un seul client, 2 s de garde, minuterie), le micro de la page vers le poste, puissance plafonnée.
- **SatMe-Node** : son SDR comme récepteur du PC, son émetteur, sa santé.
- **HTTPS** pour la page ouverte depuis une autre machine (*Réglages › Accès à distance*).

### en
- **The IC-9700 transmits from SatMe-PC**: PTT held by the page (one client, 2 s watchdog, time-out), the page's microphone to the rig, power capped.
- **SatMe-Node**: its SDR as the PC's receiver, its transmitter, its health.
- **HTTPS** for the page opened from another machine (*Réglages › Accès à distance*).

## 2026.10.23

### fr
- **Agenda › SSTV** : les campagnes et leur auto-pilote (réception seulement) ; il sait reconnecter un rotor GS-232, seulement celui connecté avant.
- **Rotor** : le plan de tout le passage (côté, retournement, déroulement), les zones cachées.
- **QO-100** : calage sur la balise du milieu, suivi d'un LNB qui dérive. **SDR** : le récepteur « IC-9700 FI ».
- **Télémesure** : paquets FSK / GMSK de 1200 à 9600 bit/s, images SSDV, FUNcube. **Journal** : le rapport d'observation de chaque passage.

### en
- **Agenda › SSTV**: campaigns and their auto-pilot (receive only); it can reconnect a GS-232 rotator, only the one connected before.
- **Rotator**: the whole pass planned (side, flip, unwinding), hidden zones.
- **QO-100**: lock on the middle beacon, a drifting LNB followed. **SDR**: the « IC-9700 FI » receiver.
- **Telemetry**: 1200 to 9600 bit/s FSK / GMSK packets, SSDV pictures, FUNcube. **Journal**: each pass's observation report.
