# SatMe-PC — la station dans un navigateur

*Octobre 2026. L'application Android reste la référence ; le PC fait ce qu'il fait, et ce qui est fait sur le PC vient aussi sur le téléphone.*

SatMe tourne sur un PC (Linux, Windows, Raspberry Pi) et se pilote depuis un navigateur. Son cœur est **le même code que sur le téléphone** : boucle Doppler, suivi du rotor, journal des passages, SSTV, METEOR, APRS, FT8, QO-100, DATV et carnet viennent du module commun `:moteur`.

<p align="center"><img src="images/satme-ensemble-fr.svg" width="100%" alt="Les trois SatMe : le téléphone, SatMe-PC et SatMe-Node au pied du mât ; le téléphone rejoint le PC par un QR code ; le même moteur partout"></p>

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, onglet Station : les prochains passages, FO-29 suivi sur le ciel, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor avec leur coche verte"></p>

## Sommaire

1. [L'installer](#linstaller)
2. [Les onglets d'un coup d'œil](#les-onglets-dun-coup-dœil)
3. [Station et passages](#station-et-passages) — mode automatique, enregistrement, agenda, horizon
4. [Le carnet](#le-carnet)
5. [SatMe sur le téléphone](#satme-sur-le-téléphone)
6. [Journal des passages](#journal-des-passages)
7. [QO-100 : le cockpit](#qo-100--le-cockpit)
8. [DATV : réception](#datv--réception) — MiniTiouner, SatMe-Node, Pluto / bladeRF, bande large, qualité, sous-titres
9. [DATV : émission](#datv--émission) — la station, les règles, l'essai en intérieur, carte et chat
10. [SSTV](#sstv) — réception et émission
11. [FT8 / FT4](#ft8--ft4)
12. [Phonie → texte](#phonie--texte)
13. [Matériel SDR](#matériel-sdr) — RTL-SDR, ADALM-PLUTO, bladeRF 2.0, HackRF One
14. [Réglages](#réglages) — poste et rotor, éléments orbitaux, journal Doppler, lien satellite
15. [Encore sur le PC](#encore-sur-le-pc)
16. [Sécurité du matériel](#sécurité-du-matériel)
17. [Avant le premier essai](#avant-le-premier-essai)

## L'installer

- **Linux** : [Installer SatMe-PC](installer-satme-pc.fr.md) — télécharger `satme-pc.tar`, le décompresser, le lancer, puis ouvrir **http://localhost:8090/**.
- **Windows** : [Installer SatMe-PC sous Windows](installer-satme-pc-windows.fr.md) — tout en un : `satme-pc-windows.msi`, double-clic, la page s'ouvre.
- **Le MiniTiouner près de l'antenne** : [Installer SatMe-Node sur un Raspberry Pi](installer-satme-node.fr.md).

| Option | Effet |
|---|---|
| `--port 8090` | le port de la page |
| `--reseau` | page ouverte aux autres machines du réseau (une station au pied du mât, pilotée depuis la maison) ; sans elle, ce PC seulement |
| `--dossier DIR` | où sont gardés réglages, carnet, journal et enregistrements (`~/.satme`, `%APPDATA%\SatMe` sous Windows) |
| `--noeud` | SatMe-Node : seulement le récepteur DATV, piloté par le réseau |

**La page sur les autres machines du réseau** (une tablette, le PC du salon) : lancer `satme --reseau`, puis ouvrir `http://<adresse du PC>:8090/` (`hostname -I` la donne). Sous Linux avec ufw, ouvrir le port au réseau local une fois, dans un terminal : `sudo ufw allow from 192.168.0.0/24 to any port 8090 proto tcp` (adapter la plage à sa box). La page n'a pas de mot de passe : réseau de la maison seulement, jamais ouvert sur internet. **🔈 Écouter ici**, en haut, joue le son de SatMe-PC (SDR ou carte son) dans ce navigateur.

**Outils facultatifs**, seulement pour les fonctions qui s'en servent (Linux) :

| Paquet | Pour |
|---|---|
| `ffmpeg` | image et son DATV, émission DATV, sous-titres (fourni sous Windows) |
| `tesseract-ocr` | les indicatifs lus dans l'image DATV |
| `hackrf` | l'émetteur d'essai HackRF One |
| `bladerf` | le bladeRF 2.0 |
| `python3-venv` | phonie → texte (le moteur s'installe depuis Réglages) |

## Les onglets d'un coup d'œil

| Onglet | Ce qu'il fait |
|---|---|
| **Station** | les passages des 24 h, le satellite suivi sur le ciel ou la carte, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor par leur nom avec une coche verte |
| **Carnet** | la saisie avec l'indicatif prédit et QRZ, la station en direct à côté, la fenêtre du décodeur CW, phonie → texte |
| **Agenda** · **Frise** · **Carrés** | les rendez-vous et le **mode automatique** ; les passages sur une frise ; les carrés contactés sur une carte |
| **Sked** | les fenêtres où une autre station et vous voyez tous deux un satellite, rejouées sur la carte, faites en vidéo |
| **QO-100** | le cockpit de la bande étroite : la réception en un clic, les signaux nommés, un clic pour s'accorder avec le bon décodeur |
| **DATV** | la bande large de QO-100 : réception (MiniTiouner, node, Pluto ou bladeRF), qualité, sous-titres, émission |
| **SDR** | le récepteur : RTL-SDR, ADALM-PLUTO, bladeRF 2.0 ; cascade, vernier, loupe |
| **Sondes** · **METEOR** | radiosondes ; images LRPT de METEOR-M |
| **Son** | la carte son, l'enregistrement |
| **SSTV** | le décodage en direct, les images, la mire, l'émission |
| **Planche** | la planche SSTV : les images sur un modèle, téléchargée en pleine taille |
| **APRS** · **FT8** | l'APRS (radio KISS comprise) et son jeu ; le tableau FT8 / FT4 |
| **Journal** | les passages rejoués avec leur son, faits en image, vidéo ou GIF |
| **Réglages** | par catégorie : Ma station, Poste et rotor, Satellites, Services en ligne, Phonie → texte, Téléphone, Accord fin, Sauvegarde |

Les onglets QO-100, DATV et SSTV tiennent sur un écran (de 1920×970 à 1366×700) : la vue principale reste en vue, les cartes secondaires sont des **sous-onglets** à icônes, le dernier ouvert gardé.

## Station et passages

**Station** liste les passages des 24 prochaines heures (favoris, entendus ces jours-ci sur AMSAT, agenda), le satellite suivi sur le ciel ou la carte, à droite de son nom l'**encart AMSAT** (ce que les stations entendent, « Entendu » / « Pas entendu » en un clic), les fréquences corrigées du Doppler avec les calages RX/TX, **● Enregistrer le son**, et **le poste et le rotor par leur nom, une coche verte ✓ quand ils sont connectés** — un clic mène à leurs réglages.

- **⤓ Agenda (.ics)** : les passages de la liste (ses filtres gardés), ou ceux du satellite suivi, dans un fichier d'agenda (RFC 5545, UTC ; satellite, élévation maximale, azimuts d'AOS et de LOS) pour n'importe quel agenda.
- **Horizon** (Réglages › Ma station) : la hauteur des obstacles par secteur d'azimut (8 × 45° ou 12 × 30°), dessinée vue de dessus. Les passages commencent et finissent alors quand le satellite les dépasse (l'AOS/LOS géométrique sous ✱) ; ceux cachés de bout en bout sont écartés. Tout à 0 ne change rien. Même réglage sur le téléphone.

### Mode automatique : ne manquer aucun passage

Onglet **Agenda**. On choisit ses satellites (un par un, ou tous les ★ d'un coup) et une élévation minimale ; SatMe-PC liste leurs passages des 24 prochaines heures. Chacun peut être écarté d'une coche.

- **5 minutes avant l'AOS**, le satellite du passage devient le satellite suivi : **le poste (CAT, Doppler) et le rotor le suivent** — le rotor va attendre à l'azimut du lever.
- **Le son est enregistré** de 5 s avant l'AOS à 30 s après le LOS (la carte son de l'onglet Son), et le passage arrive dans le **journal**, rejouable avec son son.
- **Deux passages en même temps** : le premier continue, l'autre est marqué « pendant … » ; écarter le premier garde le second.
- Changer de satellite à la main pendant un passage reste possible : le mode automatique ne reprend pas la main avant le passage suivant.
- La carte dit ce qui manque : poste non connecté, rotor sans « Suivre », carte son arrêtée.

Le même mode est sur le téléphone (*Réglages → Enregistrement / SSTV & NOAA → Mode automatique*), écran éteint compris.

<p align="center">
<img src="images/pc-agenda-auto.webp" width="66%" alt="Onglet Agenda : le mode automatique, les satellites choisis, les passages des 24 h à garder ou écarter, ceux perdus pendant un autre">
<img src="images/fr-veille.webp" width="24%" alt="Le mode automatique sur le téléphone : les satellites choisis, 16 passages armés">
</p>

### Enregistrer le son

- **● Enregistrer le son** dans Station, Carnet et Son : un WAV au nom du satellite (`SatMe_RS-44_20261013_203051Z.wav`), comme sur le téléphone.
- **Couper à la fin du passage** (option) : même lancé à la main, l'enregistrement s'arrête 30 s après le LOS du passage qu'il a pris.
- **Chaque passage du satellite suivi** (option de l'onglet Son), ou ceux du **mode automatique**.
- Un enregistrement arrêté à la main ne repart pas tout seul avant le passage suivant.

## Le carnet

Un contact se saisit avec l'indicatif prédit d'après le carnet et QRZ (Tab passe d'une prédiction à l'autre, Entrée valide). À côté, la station en direct : le satellite, ses fréquences, le polar plot, le rotor, où l'on est dans la bande passante, l'enregistrement. La fiche de l'indicatif : tous les contacts passés et leurs notes, la fiche QRZ.com (photo, distance, carte). Un contact oublié s'ajoute après le passage, à son heure. Les **records** par satellite : le contact le plus lointain de tous, de chaque satellite, mode, continent et pays, le WAC.

- **Décodeur CW** (coupé tant qu'on ne le coche pas) : la note trouvée seule, la vitesse suivie, le Doppler suivi ; indicatifs, carrés et reports mis en couleur, un indicatif dans la saisie d'un clic.
- **Supprimer** un contact depuis sa ligne (🗑), ou en cocher plusieurs et les supprimer d'un coup.
- **Les profils de station de Wavelog** : « Lire les profils » montre où l'on est (son carré, ou les deux d'une ligne de carrés) et le profil où cela part — choisi dans la liste, ou **créé** quand Wavelog n'en a pas (DXCC, zones CQ et ITU demandés). Chaque contact part au profil de l'endroit d'où il a été fait ; un profil choisi à la main pour un autre carré est signalé.
- **Chaque nouveau contact envoyé tout seul à Wavelog / Cloudlog**, après un délai au choix (30 s à 30 min) : le temps de le corriger ou de le retenir (⏸, décompte dans le carnet). Un contact que le serveur refuse est mis en pause, avec la raison. Le téléphone a le même choix de délai.
- L'export **ADIF**, et le dépôt à la main (⬆).

<p align="center">
<img src="images/pc-carnet.webp" width="49%" alt="Onglet Carnet : un nouveau contact, et à côté la station en direct — FO-29, ses fréquences, le polar plot, le rotor">
<img src="images/pc-carnet-contacts.webp" width="49%" alt="Onglet Carnet, les contacts : en cocher plusieurs pour les supprimer, l'état d'envoi de chacun">
</p>

## SatMe sur le téléphone

Dans le carnet, **Importer → SatMe sur le téléphone** :

- **Par QR code** : le PC en affiche un ; sur le téléphone, *Réglages → Partage et écoute → Partage → Scanner le QR code du PC*. Le téléphone ouvre son poste de commande et le PC s'y connecte tout seul (même Wi-Fi). Sous Linux, le pare-feu du PC doit laisser passer le port de la page + 1 (`8091` d'origine), par exemple `sudo ufw allow from 192.168.0.0/16 to any port 8091 proto tcp`.
- **Ou à la main**, avec l'adresse et le code affichés par le téléphone.
- Les passages du téléphone sont listés ; ceux cochés arrivent avec leurs **images SSTV**, leurs **enregistrements** (en WAV, l'annonce parlée retirée) et leurs **contacts** (ajoutés au carnet s'ils n'y sont pas déjà).
- Un **fichier de passage (.zip)** fait sur le téléphone peut aussi être déposé sur la page.

<p align="center"><img src="images/satme-qr-fr.svg" width="100%" alt="Du téléphone au PC en trois gestes : le PC affiche un QR code, le téléphone le scanne, les passages cochés arrivent avec leurs contacts, images SSTV et sons"></p>

<p align="center"><img src="images/pc-telephone.webp" width="70%" alt="Le téléphone rejoint le PC en scannant un QR code"></p>

## Journal des passages

La page du journal du téléphone, sous les onglets du PC : les passages listés (♪ quand il y a du son), rejoués sur le ciel ou la carte avec le S-mètre, la fréquence, les contacts et les images à leur place. « Fabriquer » : son, image, vidéo, GIF ou fichier de passage. **Le cadre du fichier est dessiné sur le rejeu** (téléphone 3:4 ou écran 16:9) : ce qui est dans le cadre est ce que montre le fichier.

- **Le passage entier** sur la carte : la trace prévue en pointillés, la partie suivie en plein — même quand SatMe-PC a été lancé en cours de passage.
- **Chaque contact à sa place, comme dans la vidéo** : un point sur la trace au moment du QSO, avec l'indicatif, et la station à son carré, reliée à lui.
- **Le S-mètre** lu par le CAT (IC-9700, IC-9100, IC-910H, TS-2000), en réception seulement.
- **Ajouter** un contact au rejeu : en pause, l'heure est celle du son ; indicatif prédit et QRZ, mode et fréquences du satellite, RST (59 / 59 d'origine) et commentaire ; Entrée valide.
- **+ Passage** : un passage fait de rien — le satellite et ses heures, des contacts, un son, des images.
- Le **journal Doppler / CAT** du passage (quand il est tenu, voir [Réglages](#réglages)) y est relié.

<p align="center"><img src="images/pc-journal-carte.webp" width="100%" alt="Journal : RS-44 rejoué sur la carte, la trace prévue en pointillés, l'empreinte, F5RRO à sa place sur la trace et à son carré"></p>

<p align="center">
<img src="images/pc-journal.webp" width="49%" alt="Journal des passages : un passage de l'ISS rejoué sur la carte">
<img src="images/pc-journal-cadre.webp" width="49%" alt="Une vidéo 3:4 : le cadre du fichier sur la carte, la légende dessous">
</p>

## QO-100 : le cockpit

L'onglet **QO-100**, c'est le transpondeur bande étroite sur un écran : le récepteur, le décodeur et la saisie du carnet côte à côte, la cascade du transpondeur dessous.

1. **Démarrer la réception QO-100** : un clic démarre le récepteur (clé SDR, ADALM-PLUTO ou bladeRF derrière le LNB ou un convertisseur), le met sur QO-100 et envoie son son aux décodeurs et au carnet.
2. **Cliquer un signal** dans la cascade : SatMe s'y accorde dans son mode (BLU, CW, FT8, SSTV) et ouvre le bon décodeur en bas — phonie écrite, CW, FT8 / FT4 ou SSTV (*Auto*, ou choisi à la main).
3. **Noter le contact** à droite : le satellite QO-100, les fréquences et le mode sont déjà là.
4. La **balise médiane** est la référence : avec le *Calage automatique*, elle est mesurée toutes les 5 s et la dérive du LNB suivie par petits pas, jamais sur une mesure faible.

- **Classement des signaux** : chaque signal du transpondeur nommé sur la cascade (BLU, CW, FT8, FT4, PSK31, RTTY, SSTV, porteuse, balises, numérique large, inconnu), coloré par classe avec sa confiance ; une FFT à cases de 16 Hz sur le flux brut du récepteur, les peignes de raies parasites et les clics de manipulation écartés, les signaux oubliés une fois partis. Il ne tourne que tant que la page le regarde.
- **Plan de bande** (celui d'AMSAT-DL) sur la réglette et en tête de la cascade, la règle de chaque segment au survol. **Fréquence d'urgence** (10 489,858 – 10 489,865) : contour et bandeau rouges ; SatMe n'y envoie jamais la montée au poste de lui-même (mémoire, signal cliqué, retour DATV, session reprise) — seulement accordée à la main. La place libre FT8 n'est proposée que dans les segments numériques ; un mode hors de son segment est signalé discrètement.
- **LEILA** : quand la sirène de la station sol est entendue (la station écoutée émet plus fort que les balises et doit baisser sa puissance), un **⚠ LEILA** rouge s'affiche 10 s sous la descente.
- **Reprise après un arrêt** : un bandeau propose de reprendre QO-100 où il en était (récepteur, fréquence, mode) ; *Réglages › Satellites › QO-100* le fait tout seul au démarrage. Réception seulement : rien n'est jamais émis.
- Sous-onglets : **Calage** (sur la balise, à la main ou automatique), **Qualité** (balise sur le bruit, écart, erreur et dérive du LNB sur l'heure, plancher de bruit, la courbe du jour), **Mémoires**, **Poste & chaîne** (convertisseurs), **Pointage**.

<p align="center"><img src="images/pc-qo100-cockpit.webp" width="100%" alt="Cockpit QO-100, récepteur arrêté : les quatre étapes, le récepteur et son accord, le décodeur, la saisie du carnet, le plan de bande et ses segments sur la cascade, le calage automatique sur la balise"></p>

## DATV : réception

L'onglet **DATV** reçoit le DVB-S / DVB-S2 sur la bande large de QO-100 (ou toute fréquence que couvre le récepteur). L'image est taillée à la fenêtre, avec l'accrochage, la MER, le MODCOD, la **marge** et son verdict, et la carte de la station à côté ; **Réception**, **Émission**, **Qualité** et **Sous-titres** sont des sous-onglets.

<p align="center"><img src="images/pc-datv.webp" width="100%" alt="Onglet DATV : un essai en intérieur sur 23 cm — l'ADALM-PLUTO émet, le bladeRF 2.0 reçoit avec le démodulateur DVB-S2 de SatMe ; accroché DVB-S2, MER 9,5 dB, QPSK 2/3, marge +5,5 dB, image parfaite ; l'indicatif lu dans l'image avec sa fiche QRZ"></p>

**Trois récepteurs**, au choix dans la carte Réception :

- **MiniTiouner** (tuner et démodulateur sur USB), piloté par [LongMynd](https://github.com/BritishAmateurTelevisionClub/longmynd), le logiciel libre du BATC. SatMe le lance à part, comme rtl_tcp : le compiler, puis donner son chemin sur la page (ou le mettre dans le `PATH`). Son **alimentation du LNB reste coupée** tant qu'on ne la choisit pas (13 V ou 18 V), avec un avertissement. Sous Linux, une règle udev permet à LongMynd de l'ouvrir — le fichier de LongMynd oublie le Pro TS2 (nom USB `MiniTiouner_Pro_TS2`) ; celle-ci les couvre tous, puis le débrancher et le rebrancher :

  ```
  echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  ```

  Branché, le MiniTiouner apparaît aussi comme deux ports série (`/dev/ttyUSB…`) : ne jamais les choisir comme port du poste ou du rotor.
- **ADALM-PLUTO** ou **bladeRF 2.0** avec **le démodulateur DVB-S2 de SatMe** (écrit d'après la norme ETSI EN 302 307-1) : recherche de la porteuse sur le spectre, filtre adapté et récupération du rythme, recherche du PLHEADER, porteuse sur les pilotes ou par décision, décodage LDPC et BCH, paquets de transport vérifiés. QPSK et 8PSK, trames normales et courtes, avec ou sans pilotes. L'onglet SDR prête sa carte le temps de la réception et la reprend après ; une carte qui reçoit la DATV est refusée aux émetteurs. L'accrochage est lâché après 8 trames ratées d'affilée.
- **Un SatMe-Node** à côté de l'antenne (plus bas).

**Ce que montre l'onglet** : la balise (10491,5 MHz, 1,5 MS/s), les canaux étroits, de 125 à 2000 kS/s ; l'oscillateur du LNB (9750 MHz d'origine) et **Caler sur le signal** pour le corriger sur la balise ; accrochage (DVB-S / DVB-S2), MER, MODCOD, porteuse et écart, service et fournisseur, la constellation en direct. L'image dans la page (H.264 ou H.265, les lecteurs relancés quand une station change de codage), le son sur le PC, le même flux dans **VLC**, et l'**enregistrement** en fichier `.ts`.

### SatMe-Node : le récepteur à côté de l'antenne

Le MiniTiouner travaille mieux près du LNB. Un **SatMe-Node**, c'est SatMe sur un petit ordinateur là-bas — un Raspberry Pi (un **Pi 3 suffit** pour le MiniTiouner : le node ne fait que recevoir) — piloté par le réseau depuis SatMe-PC et depuis le téléphone (écran **QO-100** → **DATV** : les mêmes réglages, l'image par le décodeur du téléphone). Il fait tourner LongMynd, ou le démodulateur DVB-S2 de SatMe avec un Pluto ou un bladeRF ; son état et le flux de transport (0,3 à 2 Mbit/s) arrivent au PC, qui les montre, les joue et les enregistre comme si le récepteur y était branché.

L'installer sur le Pi : [Installer SatMe-Node sur un Raspberry Pi](installer-satme-node.fr.md) — trois commandes, une dizaine de minutes sur un Pi 3. Le script installe Java, compile LongMynd, lui donne accès au MiniTiouner (udev) et démarre le service **satme-noeud** (`satme --noeud`, port 8090). À son premier démarrage, le node tire un **code à six chiffres**, affiché à la fin de l'installation (le revoir : `journalctl -u satme-noeud | grep Code`). Dans SatMe-PC, onglet DATV, **Changer** : l'adresse du node et son code, **Associer**. Le PC garde la clé que le node lui a donnée : l'un comme l'autre peut redémarrer. Une adresse vide revient au récepteur du PC. Sans sa clé, le node ne dit que ce qu'il est ; son alimentation LNB reste coupée tant qu'on ne la choisit pas.

### La bande large d'un coup d'œil

*Spectre de la bande large (station sol)*, décoché d'origine : le spectre public de la bande large que publie la station sol de QO-100 (10490,5 – 10499,5 MHz, 4 images par seconde), lu tant que l'option est cochée et l'onglet ouvert.

- Les **signaux trouvés**, chacun avec sa largeur arrondie au débit de symboles le plus proche, son niveau face à la balise, et le **nom** de la station une fois lu (nom du service, carte de visite ou indicatif dans l'image ; gardé 24 h pour cette fréquence et ce débit).
- **Cliquer un signal** : le MiniTiouner s'y accorde (même OL, même entrée).
- **Veille** : les canaux occupés tour à tour, tenus tant qu'ils sont accrochés, un enregistrement au nom de la station en option ; tout accord à la main l'arrête.

### Qualité de réception

Le sous-onglet **Qualité** dit si l'image est bonne, et pourquoi :

- **La marge**, en grand et en couleur : la MER face au seuil quasi sans erreur du MODCOD (EN 302 307-1), avec un verdict en clair (« Image parfaite »…).
- Des tuiles : paquets reçus face aux attendus (d'après le PCR), erreurs de continuité et paquets manquants, erreurs de transport et pertes de synchro, correction d'erreurs (LDPC, BCH), trames DVB-S2 perdues entières, erreurs des décodeurs vidéo et son, tables vues (PAT, PMT, SDT, PCR) et part de vide du flux.
- Quatre courbes sur 2 minutes (MER, marge, paquets, erreurs de continuité), la table des PID, la constellation. Quand le récepteur tient notre propre émission, les paquets émis face aux reçus.

<p align="center">
<img src="images/pc-datv-qualite.webp" width="49%" alt="DATV, sous-onglet Qualité : marge +6,6 dB, seuil du MODCOD 3,1 dB, 856 paquets sur 858 par seconde, les tables, les courbes sur deux minutes, les PID, la constellation">
<img src="images/pc-datv-emission.webp" width="49%" alt="DATV, sous-onglet Émission pendant un essai en intérieur : ADALM-PLUTO, QPSK 2/3, 1000 kS/s, une vidéo en boucle en H.265, gain −40 dB, l'avertissement orange de l'essai en intérieur, les réglages du MiniTiouner pour l'essai">
</p>

### Sous-titres et indicatifs dans l'image

Tous deux coupés tant qu'on ne les choisit pas dans l'onglet DATV.

- **Sous-titres** : le son reçu (sa première piste audio, décodée par ffmpeg) va à [Phonie → texte](#phonie--texte) ; les deux derniers passages sur l'image avec le drapeau de la langue et la traduction, l'historique dans le sous-onglet, les puces d'indicatif qui remplissent le carnet en mode DATV.
- **Indicatifs lus dans l'image** : toutes les 3 s tant que c'est accroché, la dernière image lue par **tesseract** (`sudo apt install tesseract-ocr`) ; un indicatif s'affiche une fois lu deux fois, les signes qui se ressemblent réparés (A/4, O/0…), vérifié face au nom du service, à la carte de visite et au carnet. Sa **fiche QRZ.com** (nom, ville, pays, carré avec distance et azimut, LoTW / eQSL, photo) est cherchée par SatMe-PC, gardée 7 jours, une requête toutes les 5 s au plus. Seul ce qui a été lu depuis l'accrochage en cours s'affiche.
- **Noter le QSO** : QO-100 ou *DATV terrestre* selon la fréquence reçue, mode DATV (ATV en ADIF), la MER comme report, nom et QTH pris dans QRZ quand ils sont vides.

## DATV : émission

Le sous-onglet **Émission** de l'onglet DATV envoie un signal DVB-S2 au transpondeur large bande de QO-100 avec un **ADALM-PLUTO** ou un **bladeRF 2.0** (un **HackRF One** seulement pour les essais en intérieur et sur charge). SatMe fabrique lui-même le DVB-S2 (EN 302 307-1 : QPSK à tous les taux, 8PSK de 3/5 à 9/10, trames normales ou courtes, pilotes, roll-off 0,35 / 0,25 / 0,20) ; **ffmpeg** fait de l'image un flux de transport exactement au débit que porte le MODCOD (`-muxrate`, complété de paquets nuls), en H.264 ou H.265, le nom du service et du fournisseur étant l'indicatif de la station.

- **L'image** : la mire SatMe avec l'indicatif, une **mire animée** (une image qui bouge sur les barres, un bip chaque seconde), une image fixe, un **fichier vidéo en boucle**, ou une caméra (`/dev/video…`, seulement listée tant qu'aucune émission ne démarre).
- **Parcourir…** : les dossiers du PC (dossier personnel, supports amovibles, `datv-sources` de SatMe), en lecture seule, avec vignettes ; les fichiers envoyés depuis le navigateur (500 Mo au plus) sont gardés dans `datv-sources`. **Prévisualiser** fait une image avec la même commande, rien n'est émis.
- **Incrustation** (coupée d'origine) : indicatif, locator, une ligne libre, l'heure UTC et la marque SatMe, dans un coin ou un bandeau, sur l'image.
- **Adapter le MODCOD à la réception** (coupé d'origine) : tant que notre propre signal revient (service ou carte de visite à notre indicatif), SatMe choisit le MODCOD le plus efficace de la plage permise dont le seuil reste 2 dB sous la MER — il descend vite quand la marge tombe ou que des paquets se perdent, remonte lentement après 20 s de marge en trop, attend 8 s après chaque changement que le récepteur raccroche, prend le plus robuste si la boucle est perdue. Fréquence, débit de symboles et puissance ne sont jamais touchés ; une puissance plus basse est suggérée sur QO-100, jamais appliquée.

**La station.** PC —USB— port **TX** du Pluto → filtre passe-bande → amplificateur → source (montée 2,4 GHz). Contrôle en réception : parabole, LNB → MiniTiouner (*Réception*). Une carte qui émet ne reçoit pas (l'onglet SDR la refuse), et inversement ; les émissions DATV et SSTV s'excluent.

| kS/s | échantillons par symbole | débit du Pluto |
|---|---|---|
| 125 | 17 | 2,125 Méch/s |
| 250 | 9 | 2,25 Méch/s |
| 333 | 7 | 2,331 Méch/s |
| 500 | 5 | 2,5 Méch/s |
| 1000 | 3 | 3 Méch/s |
| 1500 / 2000 | 2 | 3 / 4 Méch/s |

Le bladeRF garde au moins 4 échantillons par symbole. Un débit de symboles ou un MODCOD non proposé est refusé, jamais remplacé en silence.

**Premier essai : sur charge, jamais sur l'antenne.** TX du Pluto → atténuateur de 30 dB (ou plus) → entrée du MiniTiouner (son tuner couvre 2,4 GHz en direct : *Ciel* = la fréquence de montée, OL du LNB 0, alimentation LNB **coupée**). Cocher *Test sur charge* (toute fréquence, gain bridé à −40 dB au plus, avertissement en rouge), choisir 333 kS/s QPSK 2/3, **ÉMETTRE**, confirmer : le MiniTiouner doit accrocher en DVB-S2, l'indicatif en service et la mire à l'image. Seulement ensuite l'amplificateur et la parabole.

**Les règles, dans le code.**
- Seulement sur action de l'opérateur : **ÉMETTRE** puis une confirmation ; jamais au démarrage, jamais reprise des réglages (seuls les paramètres sont gardés).
- Un **indicatif** (Réglages › Ma station) avant tout envoi.
- Sur l'air, toute la bande occupée (Rs × (1 + α)) entre **2403,000 et 2410,000 MHz** en montée : à l'écart de la balise (montée 2402,0 MHz, sa bande finit vers 2402,9 MHz), jusqu'au dernier canal du plan de bande (10499,25 MHz en descente, 2409,75 MHz en montée). La liste des canaux est celle du plan ; celui que tient le MiniTiouner est marqué occupé.
- **Durée** : 10 min d'origine, 30 min au plus, puis arrêt tout seul.
- Arrêt tout seul quand la carte ne prend plus d'échantillons ou est débranchée, quand les échantillons arrivent trop souvent en retard, quand ffmpeg s'arrête, quand la page se tait depuis 15 s (onglet fermé, réseau perdu), et à la fermeture de SatMe-PC.
- À chaque arrêt, l'**oscillateur d'émission du Pluto est coupé d'abord** ; au démarrage il reste coupé jusqu'aux premiers échantillons. Si le Pluto ne répond plus à ce moment-là, la page le dit : le débrancher.
- **Gain d'émission** (une atténuation, 0 dB = pleine puissance) : Pluto −30 dB d'origine, bladeRF −50 dB, jamais au-dessus du maximum de Réglages (−10 dB d'origine).
- **Boucle fermée** : la MER du récepteur sur notre propre signal comparée à celle de la balise (mesurée avant, bouton *Balise*) : si l'on n'est pas au moins 1 dB sous la balise, un avertissement, et le gain baisse de 1 dB toutes les 5 s jusqu'à y être. Il ne remonte jamais tout seul.

**Réglages › Poste et rotor › ADALM-PLUTO** : le gain d'émission maximal, et *Couper l'oscillateur d'émission du Pluto quand SatMe s'en sert en réception* (décoché d'origine). Un Pluto allumé laisse tourner son oscillateur d'émission (2450 MHz d'origine) : coché, SatMe le coupe à chaque démarrage de la réception par le Pluto.

### L'essai en intérieur

*Essai en intérieur* (Pluto, bladeRF ou HackRF) : deux petites antennes à quelques mètres, à l'intérieur, pour voir toute la chaîne marcher avant toute parabole.

- Une vraie émission radio : pour un **radioamateur titulaire d'une licence**, sous son indicatif (nom du service et mire), avec un avertissement orange dans la carte.
- Seulement dans les bandes amateur que couvre le MiniTiouner, **toute la bande occupée dedans** : 23 cm **1240–1300 MHz** ou 13 cm **2300–2450 MHz**. 1255 MHz d'origine ; 1260–1300 MHz est partagé avec les satellites de radionavigation et 2400–2450 MHz avec le Wi-Fi (la carte le dit).
- **Puissance minimale** : Pluto et bladeRF à −40 dB au plus ; le HackRF amplificateur coupé et VGA ≤ 20 dB.
- **5 min** d'origine, **10 min** au plus, puis arrêt tout seul. La boucle fermée de QO-100 ne sert pas.

**Côté réception.** La carte donne les réglages du MiniTiouner pour l'essai : *Ciel* = la fréquence d'émission, OL du LNB **0**, le même débit de symboles, alimentation LNB **coupée** — *Régler la réception* les remplit (sans démarrer). Avec une antenne sur l'entrée du MiniTiouner, l'alimentation LNB **doit être coupée**, sinon ses 13/18 V partent dans l'antenne. Un Pluto ou un bladeRF peut recevoir à sa place, avec le démodulateur de SatMe.

**Premier essai en intérieur, pas à pas.** Indicatif donné (Réglages › Ma station). TX du HackRF (ou du Pluto) → petite antenne 23 cm ; entrée du récepteur → une autre petite antenne, à 2–3 m. Carte : *Émetteur* HackRF One, *Détecter* ; cocher *Essai en intérieur*, 1255 MHz, 333 kS/s, QPSK 2/3, mire, VGA 0 dB (Pluto : −40 dB ou moins), 5 min. *Régler la réception*, puis *Recevoir* dans la carte Réception (alimentation LNB coupée). **ÉMETTRE**, confirmer : le récepteur doit accrocher en DVB-S2, l'indicatif en service. Pas d'accrochage : monter le VGA de quelques dB, ou rapprocher les antennes.

### Carte de visite et chat dans le flux

Pendant une émission, SatMe glisse dans le flux de transport une **carte de visite** de la station, et le **chat** tapé dans la carte Émission (une ligne de 140 caractères au plus, une toutes les 2 s ; taper n'émet jamais rien : le chat ne passe que dans une émission déjà lancée). Ils prennent la place de paquets nuls : le débit ne change pas, l'image et le son non plus, et un récepteur qui ne les connaît pas les ignore. À la réception, l'onglet DATV montre la carte de la station reçue (drapeau, locator, distance et azimut, message, logiciel ; marquée si elle est déjà au carnet), ses lignes de chat en bulles, et **Noter le QSO**. En recevant son propre signal, ses lignes se cochent *✓✓ reçu*. La carte se coupe dans *Carte de visite dans le flux*.

**Retour en bande étroite.** La carte Émission annonce aussi où la station écoute les réponses sur le transpondeur bande étroite (*Retour NB*, en MHz, BLU ou CW) : par défaut la fréquence du cockpit QO-100 quand le poste y est. À la réception, la carte de la station affiche « Retour en BLU : 10 489,xxx » et **Écouter le retour** : le cockpit QO-100 s'ouvre, le récepteur bande étroite s'y accorde et Phonie → texte s'allume s'il est installé. Un retour annoncé sur la fréquence d'urgence est signalé, jamais accordé de lui-même.

**Le format**, pour qui voudrait le lire ou l'émettre :

- **PID 0x1F00**, sections privées MPEG (ISO/IEC 13818-1, forme longue : `section_syntax_indicator` 1, `private_indicator` 1, CRC-32 MPEG-2 à la fin) ; une section par groupe de paquets, la première avec `payload_unit_start` et `pointer_field` 0, le dernier complété de 0xFF. La PMT n'est pas modifiée.
- **table_id 0xE5 : carte** (`table_id_extension` 0, `version_number` change avec le contenu), répétée toutes les 2 s. **table_id 0xE6 : ligne de chat** (`table_id_extension` = numéro de séquence sur 16 bits), envoyée 5 fois à 1 s d'intervalle ; le récepteur dédoublonne par indicatif et séquence.
- Charge utile : `SATM`, un octet de format (1), puis des champs TLV (étiquette 1 octet, longueur 1 octet, valeur UTF-8 de 255 octets au plus). Étiquettes : 0x01 indicatif (obligatoire), 0x02 locator, 0x03 nom, 0x04 QTH, 0x05 puissance, 0x06 antenne, 0x07 logiciel, 0x08 fréquence d'émission (kHz, en chiffres), 0x09 MODCOD, 0x0A débit de symboles (kS/s), 0x0B message, 0x0C fréquence de retour sur le transpondeur bande étroite de QO-100 (descente en Hz, en chiffres), 0x0D son mode (`USB` ou `CW`), 0x20 texte du chat. Une étiquette inconnue se saute ; un octet de format ≥ 0x80 annonce une disposition incompatible : la section s'ignore.

## SSTV

L'onglet **SSTV**, comme sur le téléphone : l'**image en direct** (dessinée à sa vraie taille dès la première ligne, les lignes à venir sombres, celle qui arrive marquée), l'état du décodeur et un raccourci d'émission restent en vue ; **Réception**, **Émission**, **Mire**, **Images** et **Réglages** sont des sous-onglets.

<p align="center"><img src="images/pc-sstv.webp" width="100%" alt="SSTV, sous-onglet Images : les images de l'ISS de la série ARISS en Robot 36, celles d'UmKA-1, chacune avec son satellite, son mode et son heure"></p>

**Réception**

- **Source** : *auto* (la clé SDR si elle joue, sinon la carte son), la carte son, ou la SDR écoutée directement.
- **Mode** : *Auto (VIS)* ou imposé ; le **décodage continu** démarre une image sur ses tops de synchro même sans en-tête (mode reconnu à la durée des lignes) ; **Caler maintenant** accroche l'image sur la prochaine impulsion de synchro.
- **Calage automatique** (coupé d'origine) : l'écart des tons mesuré sur l'en-tête (lu sur ±1 kHz) et les tops de synchro, et la réception recalée — la clé SDR, ou le poste quand la boucle CAT le pilote — de 20 Hz par seconde au plus, jusqu'à ±2 kHz.
- Un spectre avec les tons marqués (1200 synchro, 1500 noir, 1900 VIS, 2300 blanc), le niveau, un conseil quand la source se tait.
- **Images** : chaque image gardée dès un huitième de ses lignes, avec son propre son ; filtrées par satellite, ouvertes dans une visionneuse, redécodées, faites en GIF, supprimées.
- **Mire** : la mire SSTV (comme sur le téléphone), jouée sur une sortie au choix ou écrite en WAV.

**Émission**, avec l'**ADALM-PLUTO** ou le **bladeRF 2.0** :

- L'image : la mire SatMe avec l'indicatif, la planche SSTV, ou un fichier image ; tous les modes, envoyée 1 à 3 fois, l'**indicatif écrit en haut** de l'image, et en CW à la fin si on le demande.
- **Modulation** : FM étroite (±2,5 ou ±5 kHz, préaccentuation en option), BLU haute ou basse.
- **Les règles, dans le code** : **ÉMETTRE** puis une confirmation ; un indicatif ; toute la bande occupée dans une bande amateur (144–146, 430–440, 1240–1300, 2300–2450 MHz) ; les sous-bandes satellites (145,8–146, 435–438, 1260–1270 MHz) seulement en cochant *Vers un satellite* ; **jamais 145,800 MHz** (ISS) ; la bande étroite de QO-100 en BLU haute seulement, dans son segment numérique (2,7 kHz) ou mixte — jamais l'urgence ni les balises, et jamais plus fort que la balise (une boucle fermée sur la balise quand SatMe entend sa propre descente). 15 min au plus ; arrêt tout seul une fois l'image partie, ou quand la page se tait. *Test sur charge* et *Essai en intérieur* comme pour la DATV (−40 dB, 5 min en intérieur).
- **Régler la réception** met le récepteur de l'onglet SDR sur notre propre émission (fréquence, NFM ou BLU) pour la voir revenir.

## FT8 / FT4

Un onglet pensé pour voir la bande, pas seulement la lire :

- **L'anneau du créneau** : les 15 s en cercle, le compte à rebours, en cyan si le créneau est pair, en orange s'il est impair ; à côté, **l'horloge** lue d'après le DT des stations.
- **La cascade qui parle** : 46 s, les limites des créneaux et leur heure. **Chaque indicatif décodé est écrit avec son drapeau, à sa fréquence, dans son créneau.** Une **place libre** pour émettre est trouvée toute seule (le coin le plus calme entre 500 et 2 500 Hz, loin des stations décodées ; sur QO-100, seulement dans les segments numériques).
- **Le radar** : chaque station est un point à son azimut et à sa distance depuis ton QTH, qui pâlit avec le temps et pulse quand elle vient d'être entendue. Vert pour un CQ, ★ pour un pays nouveau pour ton carnet, rose si elle t'appelle.
- **Qui appeler maintenant** : les stations des 5 dernières minutes, classées par ce qu'elles apporteraient (nouveau pays, nouveau carré, distance, force, CQ, rareté) ; un clic prépare le contact dans le carnet.
- Des **marques** pareilles partout (liste, messages, cascade) : m'appelle, guettée, nouveau pays, nouveau carré, déjà contactée. **Stations guettées** : les indicatifs à surveiller. Un **bandeau** quand la réception va mal (pas de son, silence numérique, rien de décodé depuis 40 créneaux).
- **💬 Messages**, comme une messagerie : les conversations entre deux stations (drapeaux, dernier message, ✓ quand le QSO est bouclé), chacune en bulles, ou tout au fil de l'eau. Chaque message dit ce qu'il est : 📣 appel, 📍 carré, 📶 report, ✅ report reçu, 🤝 RR73 / 73.

**Le décodeur**, mesuré sur un corpus de créneaux entiers (stations faibles jusqu'à −22 dB, chevauchements, pentes Doppler, FT4) : la **dérive Doppler** connue du satellite retirée avant le décodage ; chaque candidat lu dans son propre canal étroit (début au 1/32 de symbole, fréquence au 1/16 de ton) ; les signaux décodés **soustraits** et le reste fouillé à nouveau ; la dérive résiduelle cherchée ; les **indicatifs hachés** retrouvés (`<INDICATIF>` quand il est connu) ; pendant un QSO, le **décodage par attente** des réponses possibles du correspondant (marqué *ap*). SatMe-PC creuse plus quand le satellite est bas ou qu'il reste du temps dans le créneau.

**PSK Reporter** (Réglages, coupé d'origine) : les stations FT8 / FT4 entendues envoyées toutes les 5 minutes avec leur carré, la fréquence et le satellite — seulement quand la fréquence est sûre (poste en CAT).

<p align="center">
<img src="images/pc-ft8.webp" width="49%" alt="FT8 sur QO-100 : l'anneau du créneau, la cascade avec les indicatifs à leur fréquence, le radar des stations, qui appeler maintenant">
<img src="images/pc-ft8-messages.webp" width="49%" alt="FT8, les messages : les conversations entre deux stations, et le fil au fil de l'eau en bulles">
</p>

## Phonie → texte

Ce que la station entend en BLU ou en FM — la carte son, le son de la clé SDR, ou celui d'un flux DATV — écrit passage par passage : la BLU de QO-100 d'abord. Une fenêtre **Phonie → texte** dans les onglets **SDR**, **QO-100** et **Carnet** montre chaque passage en bulle : heure, fréquence (la descente de QO-100 quand on y est), langue, texte (plus pâle quand le moteur est moins sûr), et sa traduction si on la demande. Les indicatifs (écrits ou épelés : « Sierra Papa five Alfa Bravo Charlie », « Fox quatre Italie Oscar Zoulou », en anglais, français, allemand, espagnol, italien, polonais), les carrés (« Juliette Novembre un huit ») et les reports (« cinq neuf », « five nine ») sont mis en couleur ; la puce d'un indicatif prépare le contact dans le carnet, avec le carré et le report entendus.

- **Coupé par défaut.** Rien n'écoute et rien ne démarre tant que la case de la fenêtre n'est pas cochée.
- **Le moteur** est faster-whisper, en Python, dans un processus à part sur ce PC : pendant l'écoute rien ne sort du PC. **Réglages › Phonie → texte › Installer le moteur** crée un environnement Python dans le dossier de SatMe (`stt/`), y installe faster-whisper depuis PyPI (environ 150 Mo ; avec une carte NVIDIA, environ 1,3 Go de plus pour cuBLAS et cuDNN) et télécharge le modèle choisi (large-v3 3,1 Go, turbo 1,6 Go, medium 1,5 Go, small 0,5 Go). Rien n'est téléchargé sans ce bouton. Il faut Python 3.9 ou plus récent (Debian/Ubuntu : `python3-venv` ; Windows : python.org, « Add python.exe to PATH »). On peut aussi donner un autre interpréteur où faster-whisper est installé.
- Une carte NVIDIA sert quand elle répond, sinon le processeur (y choisir *small* ou *medium*). Sur une RTX 3060 avec large-v3, 20 s de parole demandent environ 1,3 s.
- **Traduction, sans Internet** (*Traduire en*, français d'origine) : chaque passage traduit sur le PC avec des paires de langues OPUS-MT libres (CC BY 4.0), en passant par l'anglais quand il n'y a pas de paire directe. **Installer la traduction** récupère les langues cochées (polonais, allemand, anglais, espagnol, italien, néerlandais, portugais, russe, ukrainien, tchèque…) dans `stt/traduction`.
- La voix est découpée entre les silences (de 1 à 25 s, le silencieux respecté) ; le bruit, les porteuses et les phrases qu'un moteur invente sur le bruit (« Merci d'avoir regardé »…) sont écartés. Quand le moteur prend du retard, le plus ancien passage en attente part, et la fenêtre dit le retard.
- Le texte de chaque jour est gardé dans `phonie/AAAA-MM-JJ.jsonl` ; ⤓ le télécharge en texte.

<p align="center"><img src="images/pc-reglages-phonie.webp" width="100%" alt="Réglages, Phonie → texte : le modèle (large-v3), carte NVIDIA ou processeur, le moteur installé, la traduction sans Internet vers le français avec dix langues installées"></p>

## Matériel SDR

Le *Récepteur* de l'onglet **SDR** choisit le récepteur ; tout marche pareil avec chacun : Doppler, QO-100 (derrière le LNB, FI vers 739 MHz, ou derrière un convertisseur), le cockpit, la cascade et le panorama, le classement des signaux, le son pour les décodeurs (FT8, SSTV, APRS, phonie, LEILA), METEOR, les radiosondes. L'**accord fin** comme sur le téléphone : le vernier (N Hz par cm, avec élan), la loupe autour de la fréquence écoutée, le calage sur la voix en BLU (Réglages › Accord fin). La BLU et l'AM ont une CAG à maintien.

| Matériel | Liaison | Reçoit | Émet |
|---|---|---|---|
| Clé **RTL-SDR** | rtl_tcp, sur ce PC (démarré pour vous) ou sur le réseau | environ 24 MHz – 1,7 GHz | — |
| **ADALM-PLUTO** | réseau (câble USB : 192.168.2.1, port 30431 ; ou Wi-Fi / Ethernet) | 70 MHz – 6 GHz | DATV, SSTV |
| **bladeRF 2.0** micro | USB 3, par bladeRF-cli (Linux) | 70 MHz – 6 GHz | DATV, SSTV |
| **HackRF One** | USB, par hackrf_transfer | — | essais DATV seulement (intérieur, charge) |
| **MiniTiouner** | USB, par LongMynd (ou un SatMe-Node) | DVB-S / S2 | — |

### ADALM-PLUTO

Rien à installer : SatMe parle directement à son serveur IIO. Sur son câble USB il répond sur **192.168.2.1** (port 30431) ; en Wi-Fi ou Ethernet, donner son adresse.

- **Le récepteur ne fait que recevoir.** Il règle l'oscillateur RX, le débit, la bande analogique et le gain, et rien d'autre : il n'ouvre jamais l'émetteur, n'écrit jamais ses réglages, ne touche ni à son disque USB ni à son `config.txt`. Seul le code de l'émetteur (DATV, SSTV) écrit du côté émission.
- Il tourne à 2,1168 MS/s, ramenés aux 1,0584 MS/s de la clé ; ses 12 bits sont gardés jusqu'au démodulateur. Environ 8,5 Mo/s sur le câble.
- **Gain** : *automatique* est la CAG lente de l'AD936x ; sinon de 0 à 71 dB. La ligne d'état montre la carte et son micrologiciel. Débranché, ou muet trois secondes : la réception s'arrête et dit pourquoi.

### bladeRF 2.0 (USB)

Un **Nuand bladeRF 2.0** (micro xA4…) sur un port **USB 3**, piloté par l'outil **bladeRF-cli** en mode interactif, les échantillons (SC16 Q11) passant par un tube nommé.

- **Installation (Linux)** : `sudo apt install bladerf` (bladeRF-cli, libbladeRF et la règle udev). Le **FPGA** se charge tout seul à l'ouverture depuis `~/.config/Nuand/bladeRF/hostedxA4.rbf` (ou `/usr/share/Nuand/bladeRF/`) : y déposer le fichier `.rbf` de son modèle. La règle udev du paquet donne l'accès au groupe **plugdev** : `sudo usermod -aG plugdev $USER`, se reconnecter, débrancher et rebrancher. Vérifier avec `bladeRF-cli -p`.
- **Détecter** (onglet SDR ou carte Émission) lance `bladeRF-cli -e info -e version` : lecture seule, il affiche le modèle, le numéro de série, le FPGA et le micrologiciel ; ce numéro sert ensuite à ouvrir la carte. Le dossier de bladeRF-cli se donne dans l'onglet SDR (vide : le PATH).
- **En réception** : comme le Pluto, 2,1168 MS/s ramenés à 1,0584 MS/s, 12 bits gardés ; gain *automatique* (CAG de l'AD9361) ou de 0 à 60 dB. Seulement des commandes RX1 ; muet trop longtemps ou débranché, la réception s'arrête et dit pourquoi.
- **En émission** : les règles du Pluto (même AD9361), QO-100 compris. Le gain est une atténuation (bladeRF-cli reçoit 66 + gain), **−50 dB d'origine**, jamais au-dessus du maximum des Réglages, **bridé à −40 dB** sur charge et en intérieur. Chaque réglage est vérifié dans la réponse de bladeRF-cli avant `tx start` ; une fois le flux parti, le gain est relu. Arrêt : `tx stop`, tube fermé, `quit`, puis l'outil terminé. Un bladeRF qui reçoit n'émet pas, et inversement.
- **Windows** : bladeRF-cli.exe ne peut pas écrire dans un tube nommé : le bladeRF n'est pour l'instant utilisable que sous Linux ; la page le dit.

### HackRF One (émetteur d'essai)

L'*Émetteur* de la carte Émission DATV propose aussi un **HackRF One**, pour essayer un récepteur — **jamais vers QO-100** : son convertisseur prend des échantillons de 8 bits, ses harmoniques et raies parasites demandent un filtre, et QO-100 demande un amplificateur. Le HackRF n'émet qu'en **essai en intérieur** et en **test sur charge**.

SatMe le pilote par `hackrf_transfer`, les échantillons sur son entrée standard en I/Q signés 8 bits, aux débits du Pluto, avec le filtre de bande de base le plus étroit qui laisse passer le signal :

`hackrf_transfer [-d NUMÉRO] -t - -f FRÉQ_HZ -s DÉBIT -b FILTRE_HZ -x VGA -a 0 -p 0`

- **Installation.** Linux : `sudo apt install hackrf` (il apporte `hackrf_transfer`, `hackrf_info` et les règles udev : débrancher et rebrancher le HackRF ensuite). Windows : les outils HackRF pour Windows (`hackrf_transfer.exe`, `hackrf_info.exe`) et le pilote WinUSB du HackRF ; donner leur dossier dans *Outils HackRF* s'ils ne sont pas dans le PATH.
- **Détection, en lecture seule.** *Détecter* lance seulement `hackrf_info` (il liste les cartes, n'émet rien). Rien d'autre ne tourne avant **ÉMETTRE** confirmé.
- **Faible puissance seulement.** L'amplificateur RF est **toujours coupé** (`-a 0` sur chaque ligne de commande ; une ligne sans lui, ou avec toute autre option, est refusée par le code), l'alimentation du port d'antenne coupée (`-p 0`), le gain VGA d'émission de 0 à **20 dB** au plus (le HackRF va jusqu'à 47).
- **Oscillateur décalé** (coché d'origine) : l'OL est accordé à côté du signal et la bande de base décalée en numérique, si bien que la fuite de l'OL tombe hors de la bande occupée ; en intérieur, l'OL et l'image restent dans la même bande amateur.
- **Arrêt** : l'entrée de l'outil fermée d'abord, puis il est terminé, puis tué. Arrêt quand l'outil meurt, dit le HackRF absent ou pris, ne prend plus d'échantillons depuis 3 s, à la fin du temps permis, quand la page se tait depuis 15 s, et à la fermeture de SatMe-PC.

## Réglages

Par catégorie, une liste à gauche : **Ma station** (indicatif, carré, élévation minimale, horizon), **Poste et rotor** (CAT, TH-D72, rotor, convertisseurs, journal Doppler, ADALM-PLUTO), **Satellites** (éléments orbitaux, AMSAT, lien satellite, QO-100 au démarrage), **Services en ligne** (QRZ.com, Wavelog / Cloudlog, skeds hams.at), **Phonie → texte**, **Téléphone**, **Accord fin**, **Sauvegarde** (toute la station dans un fichier, restaurée au démarrage suivant).

### Le poste et le rotor

Le modèle du poste (IC-9700, IC-9100, IC-910H, TS-2000, deux postes FT-817 / IC-705, TH-D72, ou simulé), son port et sa vitesse, Connecter / Déconnecter ; le rotor (GS-232 en série, rotctld par le réseau, ou simulé), Suivre le satellite, Parquer, Stop, ses butées et son parking. L'onglet Station ne garde que leur nom et une coche verte.

<p align="center"><img src="images/pc-reglages-materiel.webp" width="100%" alt="Réglages : le poste (CAT) et le rotor, leurs ports et leur connexion"></p>

**Journal Doppler / CAT** (coupé d'origine) : pendant un passage suivi avec le poste connecté, toutes les 5 s l'heure, l'azimut, l'élévation, la distance, le Doppler prévu sur les deux voies, la fréquence que la boucle a écrite et celle relue — un CSV par passage dans `doppler/`, relié au passage dans le Journal. Seul ce que fait déjà la boucle CAT est noté.

### Éléments orbitaux

Comme sur le téléphone : le **serveur GP SatMe** d'abord (`https://gp.f4ioz.fr` d'origine), ou ce serveur seulement, et les sources au choix — le bulletin AMSAT, les groupes CelesTrak (par le serveur seulement : le PC ne demande jamais rien à CelesTrak), SatNOGS DB, et **les satellites d'images (SSTV, SSDV)** : LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… la plupart absents du bulletin AMSAT, demandés un par un par leur numéro.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Réglages : éléments orbitaux (serveur, sources), QRZ.com, carnet en ligne avec l'envoi automatique"></p>

### Le lien satellite (WebSocket)

Pour d'autres programmes du PC (un carnet, un tableau de bord, un script) : **Réglages › Satellites › Lien satellite**, coupé d'origine. Une fois coché, `ws://localhost:8095/` (le port se change) envoie des trames texte en JSON ; sur ce PC seulement, sauf si SatMe-PC est lancé avec `--reseau`. Rien de ce qu'envoie un client n'est exécuté (le ping a sa réponse, la fermeture son écho).

| `type` | Quand | Champs |
|---|---|---|
| `hello` | à la connexion | `app`, `version`, `callsign`, `locator` |
| `satellite` | chaque seconde tant qu'un satellite est suivi | `time`, `name`, `catnum`, `az`, `el`, `rangeKm`, `rangeRateKmS`, `transmitter`, `downlinkHz`, `uplinkHz` (Doppler compris), `dopplerDownHz`, `dopplerUpHz` |
| `passAlert` | le satellite suivi et les favoris ★ | `kind` (`aosSoon` 5 min avant, avec `inS` ; `aos` ; `los`), `name`, `catnum`, `aos`, `los` (ms UTC), `maxEl`, `aosAz`, `losAz` |
| `qsoLogged` | un contact entre au carnet | `time`, `call`, `grid`, `satellite`, `catnum`, `mode`, `rstSent`, `rstRcvd`, `downlinkMHz`, `uplinkMHz`, `az`, `el`, `myGrid` |

```
{"type":"satellite","time":1792089000000,"name":"ISS","catnum":25544,"az":212.4,"el":31.7,"rangeKm":812.3,"rangeRateKmS":-4.512,"transmitter":"FM voice repeater (V/U)","downlinkHz":437806580,"uplinkHz":145987810,"dopplerDownHz":6580,"dopplerUpHz":-2190}
```

## Encore sur le PC

<p align="center">
<img src="images/pc-sked.webp" width="49%" alt="Sked : les fenêtres communes avec une autre station, rejouées sur la carte">
<img src="images/pc-carres.webp" width="49%" alt="Carrés contactés sur la carte">
</p>
<p align="center">
<img src="images/pc-meteor.webp" width="49%" alt="Image LRPT de METEOR-M">
<img src="images/pc-sondes.webp" width="49%" alt="Radiosondes : les lâchers proches, le vol sur une carte">
</p>

## Sécurité du matériel

- **Rien ne s'ouvre tout seul.** Un port série ne s'ouvre que choisi, et « Connecter » cliqué. Pas de recherche automatique.
- **RTS et DTR bas** d'origine. Le CAT Icom les lève (ses ponts USB en ont besoin) : **choisir le port du bon poste**. Le TS-2000 les garde bas, et un port qui renvoie nos propres mots (le port PTT d'un RigExpert) est refermé aussitôt.
- Sous Linux le système lève RTS/DTR un instant à chaque ouverture de port, avant que SatMe puisse les baisser : une raison de plus de n'ouvrir que le port voulu.
- **Le CAT n'émet jamais** (`TX`, `KY` refusés par le pilote TS-2000). L'APRS par un TNC KISS n'émet que quand on envoie soi-même un message, sa position ou un statut.
- **L'alimentation LNB du MiniTiouner est coupée** tant qu'on ne la choisit pas.
- **L'ADALM-PLUTO et le bladeRF 2.0 n'émettent que depuis les onglets DATV et SSTV**, sur un clic confirmé, dans les règles ci-dessus ; leurs récepteurs n'écrivent jamais le côté émission. DATV et SSTV n'émettent jamais en même temps.
- **Le HackRF One n'émet que depuis l'onglet DATV**, sur un clic confirmé, en essai en intérieur ou sur charge, son amplificateur RF toujours coupé et son gain à 20 dB au plus ; jamais vers QO-100.

## Avant le premier essai

- **Linux** : être dans le groupe `dialout` (`sudo usermod -aG dialout $USER`, puis se reconnecter).
- **Windows** : installer le pilote de l'adaptateur (CP210x, FTDI, CH340) ; le poste apparaît en `COM3`, `COM4`…
- Commencer avec le **poste simulé** et le **rotor simulé**, puis brancher le vrai matériel un élément à la fois.
