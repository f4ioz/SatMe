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
- **Session du Journal** : même très vite hors des passages, la lecture ralentit à l'approche d'un passage ou d'une image ; plus aucun passage ni première image n'est sauté.
- **Session : deux zooms**, un de loin hors des passages, un de près pendant le passage ; la caméra passe de l'un à l'autre en douceur (la molette règle celui du moment).
- **Session : satellites en plus** : coche des favoris, ils tournent aussi autour du globe (leurs taches au sol en option), sur la page et dans la vidéo.
- **Session : exports comme un passage** : vidéo MP4 (avec le son des contacts entendus, passés en temps réel), GIF animé, image du moment, son WAV ; mêmes incrustations que le Journal.

### en
- **Journal session**: even very fast outside the passes, the replay slows down as a pass or a picture comes near; no pass and no first picture is skipped any more.
- **Session: two zooms**, one from afar outside the passes, one near in a pass; the camera eases from one to the other (the wheel sets the one of the moment).
- **Session: more satellites**: tick favourites, they go round the globe too (their footprints as an option), on the page and in the video.
- **Session: exports as a pass's**: MP4 video (with the sound of the contacts heard, played in real time), animated GIF, picture of the moment, WAV sound; the same overlays as the Journal.

## 2026.10.30

### fr
- **Molette en relais** : la molette de volume USB branchée sur un autre PC accorde ta station à distance. Sur ce PC, lance SatMe-PC, puis *Réglages › Interfaces › Molette USB › Relais vers une autre station* (cocher aussi « Accorder avec la molette ») : l'adresse de la station (https://… derrière nginx, ou http://192.168.…:8090) et son mot de passe d'accès à distance, qui sert une fois et n'est jamais gardé. Chaque cran est appliqué par la station avec ses propres réglages (pas, cible, touches apprises).
- **Molette sous Windows** : les touches de volume (molette USB comprise) sont lues aussi sous Windows, et peuvent être capturées pour que le volume du PC ne bouge plus.
- **SatMe-PC portable** : un dossier `donnees` à côté du programme (à côté de `SatMe-PC.exe` dans le zip Windows, ou de `bin` et `lib` sous Linux) garde tous les réglages et fichiers ; rien n'est écrit ailleurs sur le PC. Idéal sur une clé USB.
- Nouvel onglet **Orbites** (espace *Ciel*) : la **Terre en 3D** avec tes satellites favoris (ou ceux que tu coches dans *Tous*) qui tournent sur leur orbite, leur **empreinte** au sol et un trait vers la station quand ils sont levés. Six rendus : *Réaliste*, *Dessin animé*, *Hologramme*, *Globe à points*, *Radar rétro*, *Carte ancienne*. Le temps peut s'accélérer (×1 à ×1200).
- *Carte 2D* : les mêmes satellites à plat, leurs empreintes, la trace au sol passée et à venir, le jour et la nuit.
- *Passage* : les passages des 36 prochaines heures ; **▶ Jouer le passage** l'anime en accéléré, avec le compte à rebours de l'AOS, la culmination, le LOS, le ciel de la station, l'élévation, l'azimut, la distance et le Doppler. Trois caméras (*Poursuite*, *Depuis la station*, *Vue d'ensemble*) et une **hauteur** réglable (ou la molette) pour voir plus de Terre.
- Onglet *Suivi* : un bouton **Globe** à côté de *Ciel* et *Carte* montre le satellite suivi, son orbite et son empreinte, les favoris autour.
- Le rendu **Dessin animé** est celui par défaut. Le satellite choisi se distingue des autres : anneau qui tourne autour, nom marqué d'une ★, orbite épaisse, empreinte plus visible ; les autres restent en retrait.
- **Détails** (simples, normaux, riches) : des **villes** sur le globe et la carte ; le **relief** ombré de la Terre et, sur le dessin animé, des **montagnes** posées sur les vrais sommets (relief ETOPO1 de la NOAA), des **monuments** (tour Eiffel, statue de la Liberté, pyramides, Big Ben, Taj Mahal, Fuji, fusées de Kourou et de Baïkonour…), des **bateaux** qui naviguent, et en riche des baleines, des forêts et des manchots. Approcher avec la molette pour les voir.
- **Contacts** : les contacts du carnet sur le globe et la carte, en points, en points avec l'indicatif, ou en carrés locators.
- **Villes** : toutes, seulement les **capitales** (avec leur nom, le **drapeau** de leur pays, ou les deux), ou aucune. Le relief du dessin animé est plus marqué.
- **Journal › 🌍 Session** : choisis un satellite et deux dates, et tous ses passages s'animent à la suite sur le globe (passages enchaînés ou temps continu, rapide ou non), avec la frise de la session et les images SSTV qui arrivent une à une. Un bouton anime directement la session d'un événement SSTV. Dans le Journal, le globe se **déplace** en le glissant pour laisser de la place à l'image, et la vidéo le garde où il est.
- **Vitesses de la session** (page et vidéo) : trois curseurs, *Hors passage* (le satellite loin de la station, très vite, dès ×10 en temps continu), *Pendant le passage*, et *Image ou contact* (lent, pour voir l'image SSTV se dessiner ou suivre un contact enregistré). La vitesse passe de l'une à l'autre en douceur. La vidéo de la session affiche l'heure, AOS · TCA · LOS et « AOS + 3:12 », avec une taille de texte réglable.
- **Journal** : une vue **Globe** à côté de *Ciel* et *Carte* rejoue le passage autour de la Terre ; les contacts s'allument à leur heure avec un trait station → satellite → correspondant, et les images SSTV reçues viennent se poser près de la station.
- Tout est calculé dans le navigateur, sans Internet.

### en
- **Knob relay**: the USB volume knob plugged into another PC tunes your station from afar. On that PC, start SatMe-PC, then *Settings › Interfaces › USB knob › Relay to another station* (also tick « Tune with the knob »): the station's address (https://… behind nginx, or http://192.168.…:8090) and its remote access password, used once and never kept. Each step is applied by the station with its own knob settings (step, target, learned keys).
- **Knob on Windows**: the volume keys (the USB knob among them) are read on Windows too, and can be captured so the PC's volume no longer moves.
- **Portable SatMe-PC**: a `donnees` folder beside the program (beside `SatMe-PC.exe` in the Windows zip, or `bin` and `lib` on Linux) keeps all the settings and files; nothing is written elsewhere on the PC. Ideal on a USB stick.
- New **Orbites** tab (*Sky* workspace): the **Earth in 3D** with your favourite satellites (or those ticked in *All*) going round on their orbits, their **footprint** on the ground and a line to the station when they are up. Six looks: *Realistic*, *Cartoon*, *Hologram*, *Dotted globe*, *Retro radar*, *Old map*. Time can run faster (×1 to ×1200).
- *2D map*: the same satellites flat, their footprints, the ground track past and to come, day and night.
- *Pass*: the passes of the next 36 hours; **▶ Play the pass** animates it fast, with the countdown to AOS, the culmination, LOS, the station's sky, elevation, azimuth, distance and Doppler. Three cameras (*Chase*, *From the station*, *Overview*) and an adjustable **height** (or the wheel) to see more of the Earth.
- *Suivi* tab: a **Globe** button beside *Sky* and *Map* shows the satellite followed, its orbit and footprint, the favourites around.
- **Cartoon** is the default look. The satellite chosen stands out: a ring turning round it, its name marked with a ★, a thick orbit, a clearer footprint; the others stay in the background.
- **Details** (simple, normal, rich): **cities** on the globe and the map; the Earth's **relief** shaded and, on the cartoon, **mountains** set on the real summits (NOAA's ETOPO1 relief), **landmarks** (Eiffel Tower, Statue of Liberty, pyramids, Big Ben, Taj Mahal, Fuji, the rockets of Kourou and Baikonur…), **boats** sailing, and in rich, whales, forests and penguins. Come closer with the wheel to see them.
- **Contacts**: the log's contacts on the globe and the map, as dots, dots with the callsign, or locator squares.
- **Cities**: all, only the **capitals** (with their name, their country's **flag**, or both), or none. The cartoon's relief shows more.
- **Journal › 🌍 Session**: choose a satellite and two dates, and all its passes play one after another on the globe (passes in a row or continuous time, fast or not), with the session's timeline and the SSTV pictures arriving one by one. A button plays an SSTV event's session directly. In the Journal, drag the globe to make room for the picture; the video keeps it where it is.
- **Session speeds** (page and video): three sliders, *Outside the passes* (the satellite far from the station, very fast, from ×10 in continuous time), *During the pass*, and *Picture or contact* (slow, to watch the SSTV picture being drawn or follow a recorded contact). The speed goes from one to the other smoothly. The session video shows the time, AOS · TCA · LOS and « AOS + 3:12 », with an adjustable text size.
- **Journal**: a **Globe** view beside *Sky* and *Map* replays the pass round the Earth; the contacts light up at their time with a line station → satellite → correspondent, and the SSTV pictures received land beside the station.
- All worked out in the browser, without the Internet.

## 2026.10.29

### fr
- **Planche SSTV stylée**, comme l'affiche d'une série ARISS : dans *Textes*, **Titres stylés** (*Série ARISS*, *Néon rétro*, *Écusson doré*, *Titre chrome*) habille le titre en un clic, le numéro tiré de l'événement ; chaque texte reste modifiable.
- Pour chaque texte : une **police** (sept polices libres livrées avec SatMe), un **effet** (contour, relief, néon, chrome, or, dégradé), l'italique, l'espacement des lettres, des filets lumineux.
- *Décor* : le **cadre des images** (fin et blanc, arrondi et ombré, néon de la couleur choisie), sept nouveaux fonds (l'ISS au-dessus de la Terre, ses panneaux solaires, la Terre de nuit, une nébuleuse de Spitzer, et deux fonds dessinés).
- **+ Affiche ISS** : une planche stylée toute prête, fond NASA, titre chrome et douze cases au cadre néon.
- Avec moins d'images sur la planche, les cases se placent entre le titre et les textes du bas, plus jamais par-dessus.

### en
- **Stylish SSTV sheet**, like an ARISS series poster: in *Texts*, **Styled titles** (*ARISS series*, *Retro neon*, *Gold badge*, *Chrome title*) dresses the title in one click, the number taken from the event; each text stays editable.
- For each text: a **font** (seven free fonts bundled with SatMe), an **effect** (outline, relief, neon, chrome, gold, gradient), italic, letter spacing, glowing rules.
- *Background*: the **picture frame** (fine white, rounded with a shadow, neon in the colour chosen), seven new backgrounds (the ISS above the Earth, its solar arrays, the Earth at night, a Spitzer nebula, and two drawn ones).
- **+ ISS poster**: a stylish sheet ready made, NASA background, chrome title and twelve boxes in neon frames.
- With fewer pictures on the sheet, the boxes go between the title and the texts at the bottom, never over them.

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
