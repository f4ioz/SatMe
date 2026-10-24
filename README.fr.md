<p align="center"><img src="docs/images/icon.png" width="96" alt="SatMe"></p>

<h1 align="center">SatMe</h1>

<p align="center">
Poursuite de satellites radioamateurs sur Android et sur PC — passages, pointage, Doppler, carnet.<br>
<a href="https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo">Google Play</a> ·
<a href="https://github.com/f4ioz/SatMe/wiki/Pourquoi-SatMe">Pourquoi SatMe ?</a> ·
<a href="https://github.com/f4ioz/SatMe/wiki/Accueil">Documentation (wiki)</a> ·
<a href="README.md">English</a> ·
<a href="docs/API-v1.fr.md">API</a>
</p>

### Une station, trois SatMe

<p align="center"><img src="docs/images/satme-ensemble-fr.svg" width="100%" alt="Les trois SatMe : SatMe sur le téléphone, SatMe-PC sur un PC ou un Raspberry Pi, SatMe-Node au pied du mât ; le téléphone rejoint le PC par un QR code ; le même moteur partout"></p>

**SatMe** dans la poche pour trafiquer partout, **SatMe-PC** pour toute la station dans un navigateur, **SatMe-Node** sur un Raspberry Pi au pied du mât. Le même moteur partout : ce qui se fait sur le PC se fait aussi sur le téléphone. Gratuit, sans compte, sans publicité, logiciel libre.

**L'essayer en deux minutes, sans rien brancher** : poste et rotor simulés, les vrais passages du moment.
[▶ Google Play](https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo) · PC Linux ou Raspberry Pi : `curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh && bash installe-satme.sh` · [Windows, tout en un (.msi)](docs/installer-satme-pc-windows.fr.md)

- 🛰 **Ne plus rater un passage** — le mode automatique suit chaque passage des satellites choisis : le poste et le rotor se mettent en place 5 min avant l'AOS, le son est enregistré, le passage arrive dans le journal.
- 🎯 **Le Doppler fait tout seul** — IC-9700, IC-9100, IC-910H, TS-2000, FT-817 / IC-705, TH-D72 ; on tourne la molette, le poste suit le transpondeur.
- 🎞 **Chaque passage rejoué** — sur le ciel ou la carte, avec son son, le S-mètre, les contacts et les images SSTV à leur place ; fait en vidéo ou en GIF à partager.
- 📲 **Du téléphone au PC en un QR code** — les passages faits au portable arrivent sur le PC avec leurs contacts, images et sons.
- 📡 **Et tout le reste** — SSTV, METEOR, APRS (et KISS), FT8, radiosondes, un cockpit QO-100, la DATV en réception et en émission, phonie → texte, carnet avec QRZ et Wavelog / Cloudlog.

### Un vrai passage, tel que SatMe l'a gardé

L'ISS le 4 octobre : quatre images d'écoles en Robot 36, décodées pendant qu'elles arrivaient, chacune à sa place sur la trajectoire — puis RS-44 rejoué avec son S-mètre et la fréquence où était le poste, et l'image que SatMe fabrique pour partager le moment.

<p align="center">
<img src="docs/images/fr-journal-iss-moments.webp" width="24%" alt="Le passage de l'ISS : une image Robot 36 qui arrive sur le ciel, les quatre images en dessous">
<img src="docs/images/fr-journal-iss-map.webp" width="24%" alt="Le même passage de l'ISS sur la carte, l'empreinte sur l'Europe">
<img src="docs/images/fr-journal-smeter-sky.webp" width="24%" alt="RS-44 rejoué : le S-mètre et la fréquence RX">
<img src="docs/images/fr-journal-export-iss.webp" width="24%" alt="L'image que SatMe fabrique pour partager le moment">
</p>

### SatMe-PC — toute la station dans un navigateur

SatMe tourne aussi sur un PC (Linux, Windows, Raspberry Pi), avec le moteur même du téléphone : passages, Doppler, poste et rotor, le carnet avec la station en direct à côté, skeds, le **cockpit QO-100**, la **DATV** en réception et en émission, SDR (RTL-SDR, ADALM-PLUTO, bladeRF 2.0), phonie → texte, radiosondes, METEOR, SSTV, APRS, FT8, le journal des passages — et SatMe sur le téléphone le rejoint en scannant un QR code, ses passages, images, sons et contacts arrivant avec. → [Guide de SatMe-PC, chaque fonction et où cliquer](https://github.com/f4ioz/SatMe/wiki/SatMe-PC-fr) (wiki, en douze chapitres)

**Installer**, guidé (il voit ce qui est branché) : `curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh && bash installe-satme.sh` · [SatMe-PC sous Linux](docs/installer-satme-pc.fr.md) · [SatMe-PC sous Windows](docs/installer-satme-pc-windows.fr.md) (tout en un) · [SatMe-Node sur un Raspberry Pi 3](docs/installer-satme-node.fr.md) (le MiniTiouner à côté de l'antenne) · [téléchargements](https://github.com/f4ioz/SatMe/releases/latest)

<p align="center">
<img src="docs/images/pc-station.webp" width="49%" alt="SatMe-PC, Station : les prochains passages, le satellite suivi sur le ciel, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor avec leur coche verte">
<img src="docs/images/pc-carnet.webp" width="49%" alt="Carnet de SatMe-PC : un nouveau contact, et à côté la station en direct, où l'on est dans la bande passante">
</p>
<p align="center">
<img src="docs/images/pc-journal-carte.webp" width="49%" alt="Journal : RS-44 rejoué sur la carte, la trace prévue en pointillés, l'empreinte, F5RRO à sa place sur la trace et à son carré">
<img src="docs/images/pc-agenda-auto.webp" width="49%" alt="Le mode automatique : les satellites choisis, les passages des 24 h à garder ou écarter">
</p>
<p align="center">
<img src="docs/images/pc-datv-emission.webp" width="49%" alt="SatMe-PC, émission DATV : un essai en intérieur avec l'ADALM-PLUTO, QPSK 2/3, une vidéo en boucle, l'avertissement de l'essai en intérieur">
<img src="docs/images/pc-journal-cadre.webp" width="49%" alt="SatMe-PC, journal des passages : le cadre d'une vidéo 3:4 dessiné sur la carte">
</p>

### Du téléphone au PC : un QR code

<p align="center"><img src="docs/images/satme-qr-fr.svg" width="100%" alt="Du téléphone au PC en trois gestes : le PC affiche un QR code, le téléphone le scanne, les passages cochés arrivent avec leurs contacts, images SSTV et sons"></p>

Le téléphone sert aussi des pages au navigateur du PC (câble USB ou Wi-Fi, rien à installer) : le journal des passages rejoué en grand sur une carte avec son son, et la planche SSTV faite à la souris.

<p align="center">
<img src="docs/images/fr-journal-pc.webp" width="49%" alt="Le journal des passages sur le PC : le passage de l'ISS sur la carte, une image SSTV qui arrive, la frise">
<img src="docs/images/fr-planche-pc.webp" width="49%" alt="La planche SSTV sur le PC : le modèle en grand, les images de l'ISS à glisser">
</p>

<p align="center"><a href="https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo"><b>▶ SatMe sur Google Play</b></a> — gratuit, sans compte, sans publicité</p>

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
<p align="center">
<img src="docs/images/fr-pass.webp" width="24%" alt="Page d'un passage et Doppler">
<img src="docs/images/fr-cat-sides.webp" width="24%" alt="Choix des postes">
<img src="docs/images/fr-sstv-auto.webp" width="24%" alt="SSTV automatique">
<img src="docs/images/fr-log.webp" width="24%" alt="Carnet de trafic">
</p>
<p align="center">
<img src="docs/images/fr-aprs-map.webp" width="24%" alt="Carte APRS">
<img src="docs/images/fr-aprs-hunt.webp" width="24%" alt="Chasse à une station APRS">
<img src="docs/images/fr-aprs-messages.webp" width="24%" alt="Messages APRS">
<img src="docs/images/fr-aprs-badges.webp" width="24%" alt="Trophées APRS">
</p>
<p align="center">
<img src="docs/images/fr-passes-prepare.webp" width="24%" alt="Préparer n'importe quel passage">
<img src="docs/images/fr-prepare.webp" width="24%" alt="Préparer le passage">
<img src="docs/images/fr-sstv-sound.webp" width="24%" alt="Image SSTV avec son son et sa vidéo">
<img src="docs/images/fr-sstv-sheet.webp" width="24%" alt="Planche SSTV">
</p>
<p align="center">
<img src="docs/images/fr-journal-sky.webp" width="24%" alt="Journal des passages : le ciel, une image SSTV qui arrive">
<img src="docs/images/fr-journal-map.webp" width="24%" alt="Journal des passages : la carte, l'empreinte, RS0ISS entendu">
<img src="docs/images/fr-journal-export.webp" width="24%" alt="Journal des passages : export en image, vidéo ou GIF">
<img src="docs/images/fr-sstv-sheet-iss.webp" width="24%" alt="Une planche des images de l'ISS">
</p>

## Nouveau sur SatMe-PC (octobre 2026)

SatMe-PC est passé d'une station satellites à toute une station dans un navigateur : QO-100 et DATV avec son propre modem DVB-S2, un récepteur SDR tout confort, la télémesure, les avions et les navires, la station dessinée telle qu'elle est câblée. Tout ce qui suit est détaillé, onglet par onglet et bouton par bouton, dans le [guide de SatMe-PC du wiki](https://github.com/f4ioz/SatMe/wiki/SatMe-PC-fr) ; le [guide intégré](docs/station-pc.fr.md) s'ouvre aussi dans SatMe-PC (**F1**).

<p align="center"><img src="docs/images/pc-espaces-palette.png" width="98%" alt="SatMe-PC : le rail des espaces de travail, le bandeau, la barre d'état, et la palette Ctrl+K qui propose d'écouter 145,800 MHz sur le récepteur SDR"></p>

**🔧 SatMe-Node depuis la page** *(en cours, après la 20.83)* — Station › **SatMe-Node** : la santé du Pi, le rôle de chaque carte (réception DATV, récepteur SDR, émetteur), les services, la version et le journal ; **redémarrer ou éteindre le Pi** après une confirmation. L'alimentation LNB du node est gardée quand on change de station, depuis le PC comme depuis le téléphone ; SatMe-PC redémarré retrouve seul un node qui reçoit ; le téléphone liste les stations de la bande large à recevoir d'un toucher, l'image en plein écran. [Installer SatMe-Node](docs/installer-satme-node.fr.md)

**📺 Émis | Reçu** *(en cours)* — pendant une émission DATV, l'image émise à côté de l'image reçue de QO-100, chacune agrandie d'un clic.

**🎙 L'IC-9700 en émission** *(en cours)* — PTT tenu depuis la page (un seul client, 2 s de garde, minuterie), le micro du navigateur vers le poste par sa carte son USB ou par le réseau, puissance plafonnée ; HTTPS pour la page ouverte d'une autre machine.

**🖼 La planche SSTV** *(en cours)* — moins d'images que le modèle, disposées le plus grand possible, une copie de chaque diapo (la mieux notée, les images rangées par diapo) ; une série ARISS suivie diapo par diapo : celles qui manquent, et les passages où les attendre ; un fond (intégré, images NASA ou la vôtre) et jusqu'à trois écussons de mission, leur contour uni enlevé. Un modèle s'exporte en un fichier `.satme-planche` et s'importe sur un autre téléphone ou PC — sans vos images, l'indicatif et le nom remplis par la station qui l'importe. La planche a son **événement** (campagne, activité annoncée ou série de la galerie), se **remplit par diapo** strictement à sa place (diapo 5 manquante : case 5 vide), choisit **case par case** la version montrée (Auto, reçue, corrigée, fusion), et donne le **rapport de l'événement** — planche, diapos, passages et leur ciel, horaire — en page web ou en **PDF A4**.

<p align="center">
<img src="docs/images/pc-planche-decor.webp" width="58%" alt="La planche SSTV dans SatMe-PC, onglet Décor : six images de l'ISS ajustées en grand sur un modèle de douze cases, le fond NASA « Lueur de la Terre », l'écusson de l'Expédition 75 placé à gauche, son contour enlevé">
<img src="docs/images/pc-datv-emis-recu.webp" width="40%" alt="DATV, Émis | Reçu (états simulés pour la capture) : la mire émise à gauche, l'image reçue de retour à droite, accrochée, MER 7,4 dB">
</p>
<p align="center"><img src="docs/images/pc-satme-node.webp" width="98%" alt="Station › SatMe-Node, un vrai node : la santé du Pi, le réseau et le flux I/Q, les rôles des cartes, les cartes USB vues, l'émission (non autorisée), les services avec redémarrer et éteindre, la version, le journal du node"></p>

**🤖 Campagnes SSTV en auto-pilote** — une série ARISS ou un satellite qui envoie des images quelques jours s'entre comme un rendez-vous dans l'**Agenda**, ou depuis l'**affiche** de l'annonce lue sur le PC ; l'auto-pilote (coupé d'origine) prépare le poste, le rotor, le son et le décodeur avant chaque passage, enregistre, garde les images et remet tout comme avant. Réception seulement, jamais d'émission.

**📣 Activités à venir** — dans l'**Agenda**, ce que les sources en ligne annoncent et que SatMe décode : la SSTV de l'ISS (437,550 MHz, Robot 36 depuis 2026) et d'autres satellites, les contacts ARISS, le SSDV, METEOR vu récemment ; tes passages pendant, un bandeau pour chaque nouveauté, « ça émet maintenant » d'après les écoutes AMSAT, et **Préparer la campagne SSTV** qui remplit l'auto-pilote, à vérifier. Actif d'origine, chaque source se coupe.

**🖼 La galerie SSTV par diapo** — les copies d'une même diapo ensemble, une **note** de 0 à 100 par image, le numéro lu au bas des diapos ARISS, l'**horaire de la série** et les passages où attendre les diapos manquantes ; dans la visionneuse, **Brute | Corrigée | Fusion** : l'image re-décodée depuis son enregistrement, ses taches retirées, ses copies fusionnées — la réception jamais modifiée.

**🛰 Options SSTV** — une **alerte avant un passage qui doit envoyer une diapo encore manquante** (ou reçue seulement avec une mauvaise note) : « Diapo 5 attendue à 04:32 UTC, 49° — passage ISS », une fois par passage, sur le téléphone et SatMe-PC ; la **qualité de réception pendant l'image** (niveau du son, saturation, synchro et S/B de chaque ligne, la note vers laquelle va l'image) ; **le son de chaque passage SSTV** enregistré et relié à ses images, gardé aussi longtemps que tu veux (jamais celui d'une image d'une planche ou marquée « à garder ») ; une **image façon QSL** signée de ton indicatif, ton locator, l'heure UTC, le satellite, la fréquence, le mode et la diapo ; et un **pack pour le diplôme ARISS SSTV** (les images originales, leur QSL, un résumé prêt pour le formulaire de la galerie — rien n'est envoyé). → [détails](docs/station-pc.fr.md#options-sstv)

**🧭 Le plan du rotor** — tout le passage prévu avant l'AOS : de quel côté de la butée, **retourné à 180°** par-dessus le zénith ou un seul déroulement placé quand le satellite est bas ; des **zones cachées** (toit, arbre) ; le plan dessiné sur le ciel de Suivi, et le chemin du rotor de chaque contact du carnet.

**🎯 Calibrer le rotor** — un assistant pas à pas : les calibrations du contrôleur (O2, F2, O, F, à la manière du firmware K3NG), une vérification à l'inclinomètre et à la boussole, une **correction logicielle** par axe ; STOP toujours à l'écran, rien ne bouge sans un clic.

**🛰 QO-100 avec un LNB qui dérive** — le calage sur la balise suit une dérive de quelques Hz/s, un **verrouillage sur la balise** ôte les sautillements de tout le transpondeur, et FT8 tolère la dérive. Cela atténue, sans remplacer un **LNB tenu par une référence GPSDO** ([la marche à suivre](docs/station-pc.fr.md#lnb-et-référence-gpsdo)).

<p align="center">
<img src="docs/images/pc-agenda-sstv.webp" width="64%" alt="Agenda › SSTV · auto-pilote (données simulées) : une campagne ARISS sur l'ISS, sa chaîne de réception, ses prochains passages">
<img src="docs/images/pc-rotor-plan.webp" width="34%" alt="Le plan du rotor sur le ciel de Suivi : un passage par la butée, le déroulement prévu en rouge pointillé">
</p>

**🧭 Une nouvelle page** — six espaces de travail dans un rail (Ciel, QO-100, DATV, Modes, Carnet, Station ; touches 1 à 6), un bandeau (ce qui est suivi, le récepteur actif, le prochain passage, un voyant **TX** rouge qui ne fait qu'arrêter), une barre d'état, la palette **Ctrl+K** (un onglet, un satellite, un indicatif, une fréquence tapée), des « ? » sur chaque carte, le **manuel intégré (F1)** avec recherche, des raccourcis clavier. La puce **« Son »** du bandeau : ce qu'entendent les décodeurs, la carte son du poste reconnue par son nom, **Préparer le passage** et enregistrer d'une touche. Les onglets hors satellites (avions, navires, sondes) seulement cochés.

**🔌 Montage : la station telle qu'elle est câblée** — la station dessinée de l'antenne au PC, allumée en direct (cyan reçoit, rouge émet, les fréquences sur les câbles) ; modifiée par glisser-déposer depuis un catalogue (paraboles, LNB, convertisseurs et amplis DX Patrol, GPSDO dont Leo Bodnar, bias-T, bloqueur de continu…), photos détourées toutes seules, **ce qui manque** vérifié avec les dangers signalés (une alimentation LNB qui atteindrait une carte SDR) ; quelle fonction de SatMe tient quelle carte, les conflits et comment les lever, des profils de réception appliqués dans un ordre sûr. **Plusieurs plans** de la même station, le plan de ce qui tourne montré tout seul, la chaîne détectée gardée comme plan.

<p align="center">
<img src="docs/images/pc-montage.png" width="49%" alt="Montage d'une station QO-100 : parabole, LNB, convertisseurs, ADALM-PLUTO, ampli, IC-9700, MiniTiouner, GPSDO, allumés pour une émission simulée ; les fonctions de SatMe à droite">
<img src="docs/images/pc-sdr.png" width="49%" alt="Onglet SDR avec le générateur d'essai : spectre, loupe et cascade avec une CW, une BLU, une FM, un FT8 et une SSTV fabriqués">
</p>

**📻 Récepteur SDR** — mémoires en dossiers avec drapeaux sur le spectre, plans de bandes, pile de bandes, **balayage** en réception seulement (mémoires, plages, canal prioritaire), affichage réglable (moyenne, crêtes, sept palettes), **nettoyage du son** (antiparasite, notch automatique et manuel, réduction de bruit, CAG), lecture du **CTCSS** et silencieux sur sous-ton, **I/Q** « remonter le temps », enregistrement SigMF et relecture, un **générateur d'essai** sans matériel, des **mesures** (niveau dans le temps, bruit solaire, la balise QO-100 sur 24 h). Récepteurs : RTL-SDR, ADALM-PLUTO, bladeRF 2.0 — et la **FI de l'IC-9700** démodulée par SatMe.

**📡 L'IC-9700 par le réseau** — CAT et son reçu par le LAN du poste (Network Control sur ON), sans câble USB ; réception seulement, toute commande d'émission refusée ; sortie AF ou FI ; les identifiants codés appris d'une capture de sa propre connexion.

**📡 Cockpit QO-100** — la cascade **nomme chaque signal**, un clic s'y accorde et ouvre le bon décodeur ; **jusqu'à quatre décodeurs à la fois** (FT8, FT4, CW, SSTV, phonie, PSK31, RTTY, balise) ; le plan de bande et une garde sur la **fréquence d'urgence**, le calage et le verrouillage sur la balise, **LEILA** signalée ; la **télémesure BPSK de la balise médiane** décodée.

**📺 DATV** — MiniTiouner, SatMe-Node ou le **démodulateur DVB-S2** de SatMe (Pluto, bladeRF) ; la qualité en clair, la bande large d'un coup d'œil, sous-titres, indicatifs lus dans l'image ; **émission en DVB-S2 ou DVB-S** avec toutes les règles de sécurité dans le code, essais en intérieur et sur charge, **correction I/Q** par carte.

**🛰 Télémesure** — paquets FSK / GMSK de 1200 à 9600 bit/s (G3RUH, AX.25), **images SSDV** reconstituées, valeurs temps réel de **FUNcube** ; partage SatNOGS DB au choix (coupé d'origine).

<p align="center">
<img src="docs/images/pc-telemesure-paquets.png" width="49%" alt="Télémesure : des trames AX.25 à 9600 bit/s d'un essai et l'image SSDV reconstituée">
<img src="docs/images/pc-meteo-spatiale.png" width="49%" alt="Météo spatiale : le cadran Kp et trois jours de barres, flux solaire, rayons X, vent solaire, aurore, alertes">
</p>

**✈ Avions, navires, météo spatiale** — l'**ADS-B** à 1090 MHz avec les fenêtres de **diffusion par avions**, l'**AIS** à 162 MHz, sur une **carte commune sans Internet** avec l'aurore prévue ; la **météo spatiale** (Kp, flux, rayons X, vent solaire, aurore, alertes), téléchargée seulement si on le coche.

**📒 Carnet et journal** — le contact noté sur le satellite que travaille le poste ; date, heure et satellite modifiables ; **indicatifs composés** (EA3/M5JFS) avec leur pays et leur drapeau, le carré propre à l'indicatif tapé, la carte qui suit la saisie ; un **rapport par passage** (cascade suivie du Doppler, niveau, décodages, contacts) en un fichier HTML.

**🔗 Interfaces** — un serveur **rigctld** pour les logiciels de carnet (réception seulement, PTT refusé), la **molette de volume USB** en VFO (Linux), des **annonces vocales** (passages, émissions, son indicatif décodé…), la **traduction parlée** de phonie → texte, un lien WebSocket, le CAT qui suit tout de suite le changement de satellite et, au choix, règle le poste même quand le satellite n'est pas visible.

**🔒 À distance** — la page **derrière un mot de passe** pour les autres machines, installable comme une application, un **mode distant** au son à faible latence pour un lien VPN. Réception seulement de loin.

<p align="center">
<img src="docs/images/pc-ft8.webp" width="49%" alt="FT8 sur QO-100 : l'anneau du créneau, la cascade avec les indicatifs à leur fréquence, le radar, qui appeler maintenant">
<img src="docs/images/pc-datv.webp" width="49%" alt="SatMe-PC, DATV : un essai en intérieur sur 23 cm, l'ADALM-PLUTO émet et le bladeRF 2.0 reçoit avec le démodulateur DVB-S2 de SatMe">
</p>

## Nouveautés de la 20.83

- **CAT** : le poste se cale aussitôt sur le nouveau satellite (linéaire ou FM).
- **Rotor** : le passage planifié avant l'AOS, retournement à 180° plutôt que le grand tour.
- **Carnet** : le satellite noté d'après le poste ; satellite, date et heure modifiables ; indicatifs composés (EA3/M5JFS) et drapeaux.
- **QO-100** : le calage sur la balise suit un LNB qui dérive ; FT8 tolère la dérive.
- **SatMe-PC** : nombreuses nouveautés (Montage, IC-9700 par le réseau, télémesure…), voir plus haut.

### Et dans la 20.82

- **Images météo METEOR (LRPT)**, en bêta : METEOR-M2-3 et M2-4 décodés sur le téléphone depuis une clé RTL-SDR et une antenne 137 MHz — couleur de jour et infrarouge, nord en haut, un aperçu pendant le passage ; démarrage à la main ou tout seul avant chaque passage ; un enregistrement WAV I/Q brut décodé aussi. Côtes et frontières tracées d'après l'orbite. Sur la page du satellite, un bouton : **Recevoir les images**. Vérifié sur l'enregistrement d'un vrai passage, pas encore en direct.
- **Icom IC-910H et IC-9100** en mode satellite, à côté de l'IC-9700 : leur adresse CI-V et leur vitesse (19 200 bauds), et la commande de mode satellite propre à l'IC-910H. Éprouvés au banc, pas encore sur les vrais postes.
- **Rotor : plus de claquements de relais en continu** — un axe à la fois, le mât un peu en avance du satellite, au plus une consigne toutes les 6 s, et jamais deux fois la même tant que le mât reste où le contrôleur l'a laissé ; une consigne que le contrôleur n'a pas prise repart au bout de 10 s.
- Corrigé : un enregistrement arrêté à la main pendant que le rotor était connecté pouvait rester affiché en cours.

<p align="center"><img src="docs/images/meteor-lrpt.webp" width="600" alt="Un passage de METEOR-M2-4 décodé par SatMe : l'Alaska et la mer de Béring en couleur"></p>

### Et dans la 20.81

- **Rotor : un passage par la butée est planifié** — les antennes passent de l'autre côté du zénith, ou basculent par-dessus avec 180° d'élévation, au lieu de repartir à la butée ; un tour seulement s'il vaut la peine, jamais pendant une image SSTV attendue ni en émission. Un cadran avec la butée, la zone morte, le câble et le chemin prévu ; le trajet aussi dessiné en blanc sur la boussole du satellite.
- **Kenwood TS-2000** en mode SAT : Doppler sur les deux VFO, mode de chaque côté, CTCSS, S-mètre.

### Et dans la 20.80

- **Le journal des passages sur le PC** : les passages rejoués en grand avec leur son, sur le ciel ou une carte OpenStreetMap (empreinte, ta station, les stations contactées ou entendues), le S-mètre et l'image SSTV qui arrive, une frise à cliquer, glisser (un QSO entier) et zoomer, la lecture accélérée ; le téléphone fabrique le son, l'image, la vidéo, le GIF ou le fichier du passage, et le PC le télécharge.
- **La planche SSTV sur le PC** : le modèle en grand, cases et textes déplacés à la souris, images glissées dans les cases, modèle importé depuis le PC, un fond (étoiles, dégradés, images NASA, ou la vôtre ajustée à la planche) et jusqu'à trois écussons de mission placés et redimensionnés à la souris, leur contour uni enlevé ; planche téléchargée en pleine résolution ; le modèle exporté en fichier `.satme-planche`, celui d'un autre importé (bouton ou déposé sur la page).
- **Une version choisie case par case** sur la planche (page du PC, page du téléphone et écran) : un clic sur une case — *Auto (la mieux notée)*, *Reçue*, *Corrigée* ou *Fusion*, celles qui existent avec leur note et une vignette, *Créer la fusion* s'il n'y en a pas encore, et la copie reçue dans la case ; *Remplir par diapo* garde ces choix (sauf si *Tout réinitialiser* est coché), le fichier modèle garde la version de chaque case mais jamais une image, le pack ARISS garde les originales.
- Une page du poste de commande plus compacte ; les trois pages reliées, sous un seul code.

## Fonctions

### Passages et prévisions
- Prévisions SGP4/SDP4 hors ligne : AOS, LOS, élévation maximale, azimuts, durée, et un tracé polaire pour chaque passage.
- Alerte avant l'AOS, et un passage ajouté à l'agenda du téléphone d'un appui.
- **Activités à venir** (menu, et depuis les pages SSTV et METEOR) : la SSTV de l'ISS et d'autres satellites, les contacts ARISS (phonie FM), le SSDV et METEOR LRPT annoncés par des sources en ligne (agenda ARISS, AMSAT-UK, AMSAT-F, SatNOGS, écoutes AMSAT), chacune à son rythme et gardée hors ligne ; par jour, avec la fréquence, le mode et tes passages pendant. Une notification pour une nouvelle activité, avant chaque passage utile et quand « ça émet maintenant » — chacune se coupe, comme chaque source. **Préparer la campagne SSTV** ouvre l'agenda sur un rendez-vous rempli, à vérifier.
- Fiches de passages en PDF à imprimer : coche les passages des satellites, puis choisis combien de prochains passages de chacun imprimer (jusqu'à 20, sur deux semaines).
- Suivi en direct : élévation, azimut, Doppler et compte à rebours, lisibles à bout de bras.
- Journal des passages : chaque passage suivi gardé sur le téléphone (trajectoire réelle, fréquences du poste, antennes, enregistrement), sur le ciel ou une carte OpenStreetMap avec l'empreinte ; contacts, stations APRS et images SSTV à leur place ; rejoué avec son son, avec les fiches des stations ; exporté en image, vidéo, GIF ou son. Signets, S-mètre de l'IC-9700, lecture accélérée, contacts saisis depuis le rejeu. Anciens passages retrouvés d'après le carnet, les images, les enregistrements et Wavelog / Cloudlog ; un passage donné à une autre station en un fichier.
- Préparer le passage : depuis l'icône de n'importe quel passage, neuf voyants vérifient la station selon tes profils (Fixe, Portable…, chacun avec ses postes, sa boussole et sa source du son), chacun menant à ce qui le corrige. Un test en lecture seule interroge le matériel ; avec le CAT connecté, le poste se met sur le satellite préparé. Les passages simultanés sont signalés.
- Globe avec les traces au sol, timeline des prochains passages, et skeds mutuels — les fenêtres où un satellite est visible à la fois chez vous et chez une autre station.
- État AMSAT et SatNOGS de chaque satellite, rapport AMSAT en quelques appuis ; les satellites muets restent hors de la liste. Les satellites portent leur nom court AMSAT (« AO-07 », « ISS ») et leur statut AMSAT, quelle que soit la source des éléments.
- Éléments orbitaux d'AMSAT, de CelesTrak et de SatNOGS (environ 1 700 satellites), ou par un [serveur GP SatMe](https://github.com/f4ioz/SatMe-serveur) ; gardés sur le téléphone pour servir hors ligne. Quand les sources diffèrent, les éléments du moment les plus frais l'emportent.

### Pointage
- Boussole du téléphone avec calibrage guidé, ou module Bluetooth WitMotion fixé sur la flèche d'antenne.
- Rotors : Yaesu GS-232 en série USB, Hamlib rotctld par le réseau. Un passage par la butée planifié avant lui (autre côté du zénith, par-dessus, un tour seulement s'il vaut la peine), le trajet montré sur la boussole. Retournement au zénith, rangement après le passage, un assistant de calibration (O2/F2/O/F du contrôleur, tableau de vérification, correction logicielle par axe), et des antennes simulées pour regarder le suivi avant de faire tourner les vraies.

### Poste et Doppler
- Correction Doppler en réception et en émission, transpondeurs linéaires (inverseurs ou non) et FM, avec un calibrage mémorisé par satellite.
- CAT : Icom IC-9700 en mode satellite, Kenwood TS-2000 en mode SAT, Kenwood TH-D72 en full duplex avec son pupitre, un Yaesu FT-817 ou un Icom IC-705 de chaque côté (deux FT-817, deux IC-705 ou un de chaque), ou l'un d'eux en émission pendant qu'une clé RTL-SDR reçoit.
- Le poste est réglé avant l'AOS, prêt dès le lever du satellite. Une molette ou un clavier USB peut le piloter, ses touches apprises en les pressant.
- Le ton CTCSS est pris dans le nom du transpondeur (SO-50, ISS…), ou réglé à la main.
- Un banc d'essai dans les réglages CAT : un poste simulé qui refuse ce que le vrai refuse, les trames échangées traduites en clair, et la séquence de début de passage rejouée sans aucune radio branchée.
- QO-100 : le transpondeur géostationnaire à bande étroite avec vos convertisseurs, et l'erreur de chaque oscillateur mesurée sur une référence.

### Carnet
- Clavier d'indicatifs à une main, recherche QRZ.com, nouveau carré et doublon signalés pendant la saisie.
- Wavelog et Cloudlog : contacts envoyés, au besoin tout seuls une minute après la saisie, avec vos profils de station. SatMe apparaît aussi comme une radio dans Wavelog et Cloudlog : satellite, mode et fréquences corrigées du Doppler se remplissent.
- Confirmations LoTW, carte des carrés locator, export ADIF, QSL et fiches d'activation en PDF.

### Enregistrement et décodage
- Chaque passage enregistré en MP3 — micro du téléphone, Bluetooth mains-libres ou carte son USB du poste — avec une annonce vocale au début (satellite, heure UTC, locator) et une copie dans le dossier de votre choix.
- SSTV décodée en direct pendant l'enregistrement (Robot, Martin, Scottie, PD), ou après coup depuis un enregistrement ou un WAV de la carte SD du poste. SSTV automatique : l'ISS ou un satellite favori et son transpondeur choisis, chaque passage enregistré et décodé tout seul jusqu'au passage choisi, écran éteint compris. Une mire SSTV à émettre, avec des évanouissements simulés pour éprouver un décodeur.
- Chaque passage enregistré tout seul quand le poste est piloté en CAT (option), de 5 s avant l'AOS à 5 s après le LOS, l'entrée audio vérifiée à l'armement.
- Chaque image SSTV garde son propre son : l'écouter, la re-décoder seule, la partager ou l'enregistrer ; une vidéo de l'image qui arrive avec son son, légère ou HD ; images nettoyées des lignes perdues dans le bruit, exportées avec ou sans bandeau ; le re-décodage garde l'heure et le lieu de réception. Une planche SSTV place les images d'une série, dans l'ordre choisi, sur un modèle importé (ARISS…) ou un fond SatMe, avec indicatif, logo, nom @ locator et dates ; elle peut porter moins d'images que le modèle, choisies une à une ou par nombre (1, 2, 4, 6…), la disposition ajustée pour qu'elles soient les plus grandes ; le remplissage ne prend qu'une copie de chaque diapo, la mieux notée, et **par diapo** met la diapo n dans la case n (case vide pour une diapo manquante). Une planche se **renomme** et appartient à un **événement** (la campagne du pilote auto, l'activité ARISS, ou la série vue dans la galerie), choisi d'après ses images : le choix des images montre d'abord celles de l'événement, une par diapo, avec leur note et leur numéro — lu, donné à la main (✎) ou estimé (≈) ; **Relancer l'analyse** refait le tri sans perdre ce qui a été donné à la main. Le **rapport de l'événement**, une page HTML ou, au choix, un PDF A4 fait par SatMe lui-même (sans navigateur ; une publication sur le site f4ioz.fr est prévue) : la planche, les diapos reçues et manquantes, chaque passage (heure, élévation, azimuts, diapos prévues et reçues, bilan), son ciel avec les images sur la trace, et si on veut la chaîne de réception. La galerie se range **par diapo** : les copies d'une même diapo ensemble (même bruitées, coupées ou décalées), chacune notée de 0 à 100 (bruit, lignes abîmées, image incomplète, glissements), la meilleure d'abord ; une **version corrigée** à côté de l'image reçue, jamais à sa place : re-décodée de son son toute d'un coup (lignes recalées sur toutes les synchros, niveaux recalés), ses taches retirées, ou les copies d'une diapo **fusionnées**, comparées « Brute | Corrigée », la planche prenant la version la mieux notée (sans marque sauf si on le demande) (série 34 : note moyenne 91,6 → 95,8, des images ratées en direct 24 → 91) ; le numéro « SLIDE n/12 » lu au bas des diapos ARISS ou donné à la main, et pour la série en cours un **tableau des passages** qui dit quelles diapos chaque passage devrait envoyer et quand écouter pour celles qui manquent (une estimation).
- APRS (bêta) : trames AFSK 1200 décodées pendant l'enregistrement — le digipeater de l'ISS sur 145,825 MHz — ou reçues par un poste KISS, et les stations décodées par un Yaesu FT3D. Positions (Mic-E compris), messages, statuts. Émission par l'IC-9700 ou par un poste KISS comme le Kenwood TH-D72 : APRS Thursday (HOTG) prêt, accusés affichés. On travaille sur l'ISS (145,825 MHz, Doppler suivi) ou sur le réseau terrestre (144,800 MHz, WIDE1-1,WIDE2-1) — ou les deux, l'ISS pendant ses passages et le terrestre le reste du temps. Un bouton « Écouter » règle l'IC-9700 par le CAT ; un Kenwood est passé en KISS et réglé par SatMe lui-même. La position émise peut être approximative (décalage fixe de moins de 500 m).
- L'APRS pour le plaisir : quand l'ISS répète ta propre trame, SatMe le fête (bannière, vibration, notification) et dit qui d'autre était sur le passage. Une carte sur fond OpenStreetMap (zoom et déplacement au doigt) avec les stations entendues, la trace de l'ISS, et chaque station reliée à l'endroit où était l'ISS quand on l'a entendue. Les messages en conversations, accusés dans les deux sens, avec les boutons APRS Thursday et les participants du jour. La chasse à une station : distance, cap et flèche qui suit le téléphone. Le bilan du jour en image à partager, les contacts APRS par l'ISS directement au carnet, des records et 14 badges. Les stations météo décodées, et une balise au passage de l'ISS (désactivée par défaut).
- Images météo METEOR-M2-3 / M2-4 (LRPT, bêta) depuis une clé RTL-SDR : couleur et infrarouge, en direct, toutes seules à chaque passage, ou depuis un enregistrement I/Q brut.
- Décodage FT8 / FT4, radiosondes météo, et clé RTL-SDR avec son spectre.

### Partage
- Démonstration en direct : le public suit le passage sur son propre téléphone, en scannant un QR code.
- Écoute déportée depuis un second téléphone sur le même réseau.
- Images, vidéos, sons, planches et cartes : partagés, ou enregistrés sur le téléphone.
- Poste de commande sur PC : des pages servies par le téléphone, par câble USB ou Wi-Fi — saisie au clavier, cadran polaire, enregistrement, votre station et les prochains passages ; le journal des passages rejoué en grand (carte, S-mètre, frise, lecture accélérée, vidéos fabriquées par le téléphone) ; la planche SSTV faite à la souris — et une [API HTTP](docs/API-v1.fr.md) pour d'autres clients.
- SatMe-PC rejoint en scannant son QR code : les passages, images SSTV, enregistrements et contacts du téléphone apportés au PC. Chaque nouveau contact peut partir tout seul vers Wavelog après un délai au choix, sur le téléphone comme sur le PC.

Français et anglais. Commandes lisibles par les lecteurs d'écran.

## Matériel pris en charge

| Type | Modèles |
|---|---|
| Postes | Icom IC-9700 (SatMe-PC : USB ou le réseau du poste) · Icom IC-910H¹ · Icom IC-9100¹ · Kenwood TS-2000 (mode SAT ; RigExpert Tiny essayé) · Kenwood TH-D72 (FM, full duplex) · Yaesu FT-817 ou Icom IC-705¹ de chaque côté · FT-817 ou IC-705¹ + clé SDR |
| APRS | Poste KISS en USB : Kenwood TH-D72 (essayé) ; autres TNC KISS sur une ligne série USB¹ · Yaesu FT3D : les stations qu'il décode (positions, sortie WAY.P) |
| SDR | RTL2832U (phonie, SSTV, images METEOR avec une antenne 137 MHz) · SatMe-PC en plus : ADALM-PLUTO (réseau), Nuand bladeRF 2.0 micro (USB 3, Linux) — en réception, et en émission DATV et SSTV · HackRF One (émetteur d'essai, en intérieur ou sur charge seulement) · la FI de l'IC-9700 · ADS-B et AIS sur une RTL-SDR |
| DATV | MiniTiouner (par LongMynd, sur le PC ou un SatMe-Node) · ADALM-PLUTO ou bladeRF 2.0 avec le démodulateur DVB-S2 de SatMe |
| Boussole | WitMotion WT901BLE, WT9011DCL-BT50 |
| Rotors | Yaesu GS-232 · Hamlib rotctld (réseau) |
| Série USB | CP210x, FTDI, PL2303, CH340/341/9102, MCP2200/2221, CDC |
| Divers | Cartes son USB · molette/clavier USB (touches apprises par appui ; aussi SatMe-PC, Linux) |

¹ Écrit et testé au banc, pas encore essayé sur le matériel réel.

### Câblage

Le téléphone n'a qu'un port USB-C. Un hub alimenté branché dessus porte tout : postes, carte son, clé SDR, interface du rotor, molette. Par son entrée USB-C PD, il peut aussi recharger le téléphone pendant le passage. Une clé RTL-SDR tire à elle seule environ 300 mA : le hub doit donc être alimenté. Le pupitre PC passe alors par le Wi-Fi.

<p align="center"><img src="docs/images/station-wiring-fr.svg" width="760" alt="Câblage d'une station SatMe : le téléphone sur un hub USB alimenté, avec les postes, la carte son, la clé SDR, l'interface du rotor et la molette USB"></p>

## Documentation

- **[Wiki SatMe](https://github.com/f4ioz/SatMe/wiki/Accueil)** : le guide complet, pas à pas, avec captures — installation, passages, CAT, enregistrement, SSTV, APRS, carnet, FAQ
- **[Guide de SatMe-PC (wiki)](https://github.com/f4ioz/SatMe/wiki/SatMe-PC-fr)** : chaque fonction de SatMe-PC et où cliquer, en douze chapitres, avec captures — aussi en manuel PDF
- [Guide intégré de SatMe-PC](docs/station-pc.fr.md) : le texte du manuel que SatMe-PC ouvre sur **F1**
- [API du poste de commande v1](docs/API-v1.fr.md) : protocole HTTP pour d'autres clients
- [Serveur GP SatMe](https://github.com/f4ioz/SatMe-serveur) : relais d'éléments orbitaux (OMM/GP) pour SatMe, à installer sur un Raspberry Pi, un serveur Linux ou Proxmox
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

- T. S. Kelso — modèles SGP4/SDP4 et CelesTrak (éléments orbitaux)
- John Magliacane KD2BD — PREDICT
- Neoklis Kyriazis 5B4AZ — traduction en C de SGP4/SDP4, reprise par PREDICT
- David Johnson G4DPZ — predict4java (calcul des passages et du Doppler)
- Kārlis Goba — ft8_lib (tables du code correcteur FT8/FT4)
- K9AN, G4WJS et K1JT — protocoles FT8 et FT4 (article QEX)
- Peter Goodhall MM9SQL — Cloudlog
- L'équipe Wavelog — Wavelog, fork de Cloudlog
- IS0GRB — WebSDR QO-100 (référence de fréquence)
- John Morris G4ANB — carrés locateurs Maidenhead
- AMSAT et SatNOGS — état des satellites ; SatNOGS DB — éléments orbitaux
- Bob Bruninga WB4APR (SK) — APRS
- Stephen Smith WA8LMF — CD de test des TNC (essais du décodeur APRS)
- radio-club F6KMX

73 de **F4IOZ**
