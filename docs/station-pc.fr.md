# SatMe-PC — la station dans un navigateur

*Octobre 2026. L'application Android reste la référence ; le PC fait ce qu'il fait, et ce qui est fait sur le PC vient aussi sur le téléphone.*

SatMe tourne sur un PC (Linux, Windows, Raspberry Pi) et se pilote depuis un navigateur. Son cœur est **le même code que sur le téléphone** : boucle Doppler, suivi du rotor, journal des passages, SSTV, METEOR, APRS, FT8 et carnet viennent du module commun `:moteur`.

<p align="center"><img src="images/satme-ensemble-fr.svg" width="100%" alt="Les trois SatMe : le téléphone, SatMe-PC et SatMe-Node au pied du mât ; le téléphone rejoint le PC par un QR code ; le même moteur partout"></p>

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, onglet Station : les prochains passages, FO-29 suivi sur le ciel, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor avec leur coche verte"></p>

## L'installer

- **Linux** : [Installer SatMe-PC](installer-satme-pc.fr.md) — télécharger `satme-pc.tar`, le décompresser, le lancer, puis ouvrir **http://localhost:8090/**.
- **Windows** : [Installer SatMe-PC sous Windows](installer-satme-pc-windows.fr.md) — tout en un : `satme-pc-windows.msi`, double-clic, la page s'ouvre.
- **Le MiniTiouner près de l'antenne** : [Installer SatMe-Node sur un Raspberry Pi](installer-satme-node.fr.md).

| Option | Effet |
|---|---|
| `--port 8090` | le port de la page |
| `--reseau` | page ouverte aux autres machines du réseau (une station au pied du mât, pilotée depuis la maison) ; sans elle, ce PC seulement |
| `--dossier DIR` | où sont gardés réglages, carnet, journal et enregistrements (`~/.satme`, `%APPDATA%\SatMe` sous Windows) |
| `--noeud` | SatMe-Node : seulement le MiniTiouner, piloté par le réseau |

**La page sur les autres machines du réseau** (une tablette, le PC du salon) : lancer `satme --reseau`, puis ouvrir `http://<adresse du PC>:8090/` (`hostname -I` la donne). Sous Linux avec ufw, ouvrir le port au réseau local une fois, dans un terminal : `sudo ufw allow from 192.168.0.0/24 to any port 8090 proto tcp` (adapter la plage à sa box). La page n'a pas de mot de passe : réseau de la maison seulement, jamais ouvert sur internet.

## Les onglets

- **Station** : les passages des 24 prochaines heures (favoris, entendus ces jours-ci sur AMSAT, agenda), le satellite suivi sur le ciel ou la carte, à droite de son nom l'**encart AMSAT** (ce que les stations entendent, « Entendu » / « Pas entendu » en un clic), les fréquences corrigées du Doppler avec les calages RX/TX, **● Enregistrer le son**, et **le poste et le rotor par leur nom, une coche verte ✓ quand ils sont connectés** — un clic mène à leurs réglages.
- **Carnet** : un contact se saisit avec l'indicatif prédit d'après le carnet et QRZ (Tab passe d'une prédiction à l'autre, Entrée valide). À côté, la station en direct : le satellite, ses fréquences, le polar plot, le rotor, où l'on est dans la bande passante, l'enregistrement — lancé aussi d'ici. La fiche de l'indicatif : tous les contacts passés et leurs notes, la fiche QRZ.com (photo, distance, carte) si on la veut. Un contact oublié s'ajoute après le passage, à son heure.
- **Agenda** (les rendez-vous, et le **mode automatique**, voir plus bas), **Frise** (les passages sur une frise), **Carrés** (les carrés contactés sur une carte).
- **Sked** : les fenêtres où une autre station et vous voyez tous deux un satellite, rejouées sur la carte avec les deux ciels, et faites en vidéo.
- **QO-100** : le plan de bande du transpondeur bande étroite, le calage sur la balise, la chaîne de station (convertisseurs), et le transpondeur vu par la clé SDR.
- **DATV** : la bande large de QO-100 avec un MiniTiouner, voir plus bas.
- **SDR** : une clé RTL-SDR sur ce PC (rtl_tcp démarré pour vous) ou sur le réseau.
- **Sondes** (radiosondes) et **METEOR** (images LRPT) par la clé.
- **Son** (carte son, enregistrement), **SSTV** (décodage en direct, les images dans une visionneuse), **APRS**, **FT8 / FT4**.
- **Planche** : la planche SSTV, comme sur le téléphone — les images sur un modèle (une grille, autour du titre, ou un modèle ARISS importé dont les cases sont trouvées), remplies par ordre de réception ou en les glissant, les textes, puis la planche téléchargée en pleine taille (PNG).
- **Journal** : les passages rejoués avec leur son, faits en image, vidéo ou GIF.
- **Réglages** : **le poste (CAT) et le rotor** en tête (modèle, port, connexion, butées, parking), puis la station, les éléments orbitaux, QRZ.com, le carnet en ligne, les convertisseurs.

<p align="center">
<img src="images/pc-carnet.webp" width="49%" alt="Onglet Carnet : un nouveau contact, et à côté la station en direct — FO-29, ses fréquences, le polar plot, le rotor">
<img src="images/pc-carnet-contacts.webp" width="49%" alt="Onglet Carnet, les contacts : en cocher plusieurs pour les supprimer, l'état d'envoi de chacun">
</p>

## Mode automatique : ne manquer aucun passage

Onglet **Agenda**. On choisit ses satellites (un par un, ou tous les ★ d'un coup) et une élévation minimale ; SatMe-PC liste leurs passages des 24 prochaines heures. Chacun peut être écarté d'une coche.

- **5 minutes avant l'AOS**, le satellite du passage devient le satellite suivi : **le poste (CAT, Doppler) et le rotor le suivent** — le rotor va attendre à l'azimut du lever.
- **Le son est enregistré** de 5 s avant l'AOS à 30 s après le LOS (la carte son de l'onglet Son), et le passage arrive dans le **journal**, rejouable avec son son.
- **Deux passages en même temps** : le premier continue, l'autre est marqué « pendant … » ; écarter le premier garde le second.
- Changer de satellite à la main pendant un passage reste possible : le mode automatique ne reprend pas la main avant le passage suivant.
- La carte dit ce qui manque : poste non connecté, rotor sans « Suivre », carte son arrêtée.

Le même mode est sur le téléphone (*Réglages → Enregistrement / SSTV & NOAA → Mode automatique*), écran éteint compris ; ses choix et ceux du PC se règlent de la même façon.

<p align="center">
<img src="images/pc-agenda-auto.webp" width="66%" alt="Onglet Agenda : le mode automatique, les satellites choisis, les passages des 24 h à garder ou écarter, ceux perdus pendant un autre">
<img src="images/fr-veille.webp" width="24%" alt="Le mode automatique sur le téléphone : les satellites choisis, 16 passages armés">
</p>

## Enregistrer le son

- **● Enregistrer le son** dans Station, Carnet et Son : un WAV au nom du satellite (`SatMe_RS-44_20261013_203051Z.wav`), comme sur le téléphone.
- **Couper à la fin du passage** (option) : même lancé à la main, l'enregistrement s'arrête 30 s après le LOS du passage qu'il a pris.
- **Chaque passage du satellite suivi** (option de l'onglet Son), ou ceux du **mode automatique**.
- Un enregistrement arrêté à la main ne repart pas tout seul avant le passage suivant.

## Le carnet et le carnet en ligne

- **Supprimer** un contact depuis sa ligne (🗑), ou en cocher plusieurs et les supprimer d'un coup.
- **Les profils de station de Wavelog** : « Lire les profils » montre où l'on est (son carré, ou les deux d'une ligne de carrés) et le profil où cela part — choisi dans la liste, ou **créé** quand Wavelog n'en a pas (DXCC, zones CQ et ITU demandés). Chaque contact part au profil de l'endroit d'où il a été fait, comme sur le téléphone ; un profil choisi à la main pour un autre carré est signalé, Wavelog rangerait les contacts sous son carré.
- **Chaque nouveau contact envoyé tout seul à Wavelog / Cloudlog**, après un délai au choix (30 s à 30 min) : le temps de le corriger ou de le retenir (⏸, décompte dans le carnet). Un contact que le serveur refuse est mis en pause, avec la raison. « Essayer » liste les profils de station que donne la clé. Le téléphone a le même choix de délai.
- L'export **ADIF**, et le dépôt à la main (⬆) comme avant.

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

<p align="center"><img src="images/pc-journal-carte.webp" width="100%" alt="Journal : RS-44 rejoué sur la carte, la trace prévue en pointillés, l'empreinte, F5RRO à sa place sur la trace et à son carré"></p>

<p align="center">
<img src="images/pc-journal.webp" width="49%" alt="Journal des passages : un passage de l'ISS rejoué sur la carte">
<img src="images/pc-journal-cadre.webp" width="49%" alt="Une vidéo 3:4 : le cadre du fichier sur la carte, la légende dessous">
</p>

## Éléments orbitaux

Comme sur le téléphone : le **serveur GP SatMe** d'abord (`https://gp.f4ioz.fr` d'origine), ou ce serveur seulement, et les sources au choix — le bulletin AMSAT, les groupes CelesTrak (par le serveur seulement : le PC ne demande jamais rien à CelesTrak), SatNOGS DB, et **les satellites d'images (SSTV, SSDV)** : LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… la plupart absents du bulletin AMSAT, demandés un par un par leur numéro.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Réglages : éléments orbitaux (serveur, sources), QRZ.com, carnet en ligne avec l'envoi automatique"></p>

## Le poste et le rotor

Dans **Réglages**, en tête : le modèle du poste (IC-9700, IC-9100, IC-910H, TS-2000, deux postes FT-817 / IC-705, TH-D72, ou simulé), son port et sa vitesse, Connecter / Déconnecter ; le rotor (GS-232 en série, rotctld par le réseau, ou simulé), Suivre le satellite, Parquer, Stop, ses butées et son parking. L'onglet Station ne garde que leur nom et une coche verte.

<p align="center"><img src="images/pc-reglages-materiel.webp" width="100%" alt="Réglages : le poste (CAT) et le rotor, leurs ports et leur connexion"></p>

## FT8 / FT4 : la bande en un coup d'œil

Un onglet pensé pour voir la bande, pas seulement la lire :

- **L'anneau du créneau** : les 15 s en cercle, le compte à rebours, en cyan si le créneau est pair, en orange s'il est impair.
- **La cascade qui parle** : 46 s, environ 5,5 Hz par colonne, les limites des créneaux et leur heure. **Chaque indicatif décodé est écrit avec son drapeau, à sa fréquence, dans son créneau.** Une **place libre** pour émettre est trouvée toute seule (le coin le plus calme entre 500 et 2 500 Hz, loin des stations décodées).
- **Le radar** : chaque station est un point à son azimut et à sa distance depuis ton QTH. Il pâlit avec le temps, pulse quand la station vient d'être entendue ; un balayage fait un tour par créneau. Vert pour un CQ, ★ pour un pays nouveau pour ton carnet, rose si elle t'appelle.
- **Qui appeler maintenant** : les stations des 5 dernières minutes, classées par ce qu'elles apporteraient (nouveau pays, nouveau carré, distance, force, CQ, rareté) ; un clic prépare le contact dans le carnet.
- **💬 Messages**, comme une messagerie : les conversations entre deux stations (drapeaux, dernier message, ✓ quand le QSO est bouclé), chacune en bulles, ou tout au fil de l'eau. Chaque message dit ce qu'il est : 📣 appel, 📍 carré, 📶 report, ✅ report reçu, 🤝 RR73 / 73.

<p align="center">
<img src="images/pc-ft8.webp" width="49%" alt="FT8 sur QO-100 : l'anneau du créneau, la cascade avec les indicatifs à leur fréquence, le radar des stations, qui appeler maintenant">
<img src="images/pc-ft8-messages.webp" width="49%" alt="FT8, les messages : les conversations entre deux stations, et le fil au fil de l'eau en bulles">
</p>

## DATV — la bande large de QO-100 avec un MiniTiouner

L'onglet **DATV** reçoit le DVB-S/S2 avec un **MiniTiouner** (tuner et démodulateur sur USB), piloté par [LongMynd](https://github.com/BritishAmateurTelevisionClub/longmynd), le logiciel libre du BATC. SatMe le lance à part, comme rtl_tcp : le compiler, puis donner son chemin sur la page (ou le mettre dans le `PATH`).

- La balise (10491,5 MHz, 1,5 MS/s), les canaux étroits, de 125 à 2000 kS/s ; l'oscillateur du LNB (9750 MHz d'origine) et « Caler sur le signal » pour le corriger sur la balise.
- Accrochage (DVB-S / DVB-S2), MER, MODCOD, porteuse et écart, service et fournisseur, flux vidéo et audio, la constellation en direct.
- L'image dans la page, le son sur le PC, le même flux dans **VLC**, et l'**enregistrement** en fichier `.ts`.
- L'**alimentation du LNB** par le MiniTiouner **reste coupée** tant qu'on ne la choisit pas (13 V ou 18 V), avec un avertissement.
- Linux : une règle udev permet à LongMynd d'ouvrir le MiniTiouner. Le fichier de LongMynd oublie le Pro TS2 (son nom USB est `MiniTiouner_Pro_TS2`) ; celle-ci les couvre tous, puis le débrancher et le rebrancher :

  ```
  echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
  sudo udevadm control --reload-rules && sudo udevadm trigger
  ```
- Branché, le MiniTiouner apparaît aussi comme deux ports série (`/dev/ttyUSB…`) : ne jamais les choisir comme port du poste ou du rotor.

<p align="center"><img src="images/pc-datv.webp" width="100%" alt="Onglet DATV : un signal de test DVB-S2 accroché, MER, MODCOD, la constellation"></p>

### SatMe-Node : le MiniTiouner à côté de l'antenne

Le MiniTiouner travaille mieux près du LNB. Un **SatMe-Node**, c'est SatMe sur un petit ordinateur là-bas — un Raspberry Pi (un **Pi 3 suffit** : le node ne fait que recevoir, il ne décode rien) — piloté par le réseau depuis SatMe-PC et depuis le téléphone (écran **QO-100** → **DATV** : les mêmes réglages, l'image par le décodeur du téléphone). Il fait tourner LongMynd ; son état et le flux de transport (0,3 à 2 Mbit/s) arrivent au PC, qui les montre, les joue et les enregistre comme si le MiniTiouner y était branché.

L'installer sur le Pi : [Installer SatMe-Node sur un Raspberry Pi](installer-satme-node.fr.md) — trois commandes, une dizaine de minutes sur un Pi 3.

Le script installe Java, compile LongMynd, lui donne accès au MiniTiouner (udev) et démarre le service **satme-noeud** (`satme --noeud`, port 8090). À son premier démarrage, le node tire un **code à six chiffres**, affiché à la fin de l'installation (le revoir : `journalctl -u satme-noeud | grep Code`). Dans SatMe-PC, onglet DATV, MiniTiouner **Changer** : l'adresse du node et son code, **Associer**. Le PC garde la clé que le node lui a donnée : l'un comme l'autre peut redémarrer. Une adresse vide revient au MiniTiouner du PC.

Sans sa clé, le node ne dit que ce qu'il est ; ce qui le change est un POST ; son alimentation LNB reste coupée tant qu'on ne la choisit pas.

*Ci-dessus, un signal de test. Le MiniTiouner Pro TS2 lui-même est reconnu et cherche (tuner, démodulateur, LNA), sur le PC et par un node ; le premier signal sur l'air reste à venir.*

## Encore sur le PC

<p align="center">
<img src="images/pc-sked.webp" width="49%" alt="Sked : les fenêtres communes avec une autre station, rejouées sur la carte">
<img src="images/pc-qo100.webp" width="49%" alt="QO-100 : le plan de bande, le calage sur la balise, la chaîne de station">
</p>
<p align="center">
<img src="images/pc-carres.webp" width="49%" alt="Carrés contactés sur la carte">
<img src="images/pc-meteor.webp" width="49%" alt="Image LRPT de METEOR-M">
</p>
<p align="center">
<img src="images/pc-sondes.webp" width="49%" alt="Radiosondes : les lâchers proches, le vol sur une carte">
<img src="images/pc-sstv.webp" width="49%" alt="SSTV : une image ouverte dans la visionneuse">
</p>

## Le lien satellite (WebSocket)

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

## Sécurité du matériel

- **Rien ne s'ouvre tout seul.** Un port série ne s'ouvre que choisi, et « Connecter » cliqué. Pas de recherche automatique.
- **RTS et DTR bas** d'origine. Le CAT Icom les lève (ses ponts USB en ont besoin) : **choisir le port du bon poste**. Le TS-2000 les garde bas, et un port qui renvoie nos propres mots (le port PTT d'un RigExpert) est refermé aussitôt.
- Sous Linux le système lève RTS/DTR un instant à chaque ouverture de port, avant que SatMe puisse les baisser : une raison de plus de n'ouvrir que le port voulu.
- **Le CAT n'émet jamais** (`TX`, `KY` refusés par le pilote TS-2000). Seul l'APRS par un TNC KISS émet, et seulement quand on envoie soi-même un message, sa position ou un statut.
- **L'alimentation LNB du MiniTiouner est coupée** tant qu'on ne la choisit pas.

## Avant le premier essai

- **Linux** : être dans le groupe `dialout` (`sudo usermod -aG dialout $USER`, puis se reconnecter).
- **Windows** : installer le pilote de l'adaptateur (CP210x, FTDI, CH340) ; le poste apparaît en `COM3`, `COM4`…
- Commencer avec le **poste simulé** et le **rotor simulé**, puis brancher le vrai matériel un élément à la fois.
