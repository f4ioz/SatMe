# SatMe-PC — la station dans un navigateur

*Octobre 2026. L'application Android reste la référence ; le PC fait ce qu'il fait, et ce qui est fait sur le PC vient aussi sur le téléphone.*

SatMe tourne sur un PC (Linux, Windows, Raspberry Pi) et se pilote depuis un navigateur. Son cœur est **le même code que sur le téléphone** : boucle Doppler, suivi du rotor, journal des passages, SSTV, METEOR, APRS, FT8 et carnet viennent du module commun `:moteur`.

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, onglet Station : les prochains passages, FO-29 suivi sur le ciel, les fréquences corrigées du Doppler, un IC-9700 et un rotor simulés"></p>

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

## Les onglets

- **Station** : les passages des 24 prochaines heures (favoris, entendus ces jours-ci sur AMSAT, agenda), le satellite suivi sur le ciel ou la carte, les fréquences corrigées du Doppler avec les calages RX/TX, le poste (CAT) et le rotor.
- **Carnet** : un contact se saisit avec l'indicatif prédit d'après le carnet et QRZ (Tab passe d'une prédiction à l'autre, Entrée valide). À côté, la station en direct : le satellite, ses fréquences, le polar plot, le rotor, où l'on est dans la bande passante, l'enregistrement. La fiche de l'indicatif : tous les contacts passés et leurs notes, la fiche QRZ.com (photo, distance, carte) si on la veut.
- **Agenda**, **Frise** (les passages sur une frise), **Carrés** (les carrés contactés sur une carte).
- **Sked** : les fenêtres où une autre station et vous voyez tous deux un satellite, rejouées sur la carte avec les deux ciels, et faites en vidéo.
- **QO-100** : le plan de bande du transpondeur bande étroite, le calage sur la balise, la chaîne de station (convertisseurs), et le transpondeur vu par la clé SDR.
- **DATV** : la bande large de QO-100 avec un MiniTiouner, voir plus bas.
- **SDR** : une clé RTL-SDR sur ce PC (rtl_tcp démarré pour vous) ou sur le réseau.
- **Sondes** (radiosondes) et **METEOR** (images LRPT) par la clé.
- **Son** (carte son, enregistrement), **SSTV** (décodage en direct, les images dans une visionneuse), **APRS**, **FT8 / FT4**.
- **Planche** : la planche SSTV, comme sur le téléphone — les images sur un modèle (une grille, autour du titre, ou un modèle ARISS importé dont les cases sont trouvées), remplies par ordre de réception ou en les glissant, les textes, puis la planche téléchargée en pleine taille (PNG).
- **Journal** : les passages rejoués avec leur son, faits en image, vidéo ou GIF.
- **Réglages** : station, éléments orbitaux, QRZ.com, carnet en ligne, convertisseurs.

<p align="center">
<img src="images/pc-carnet.webp" width="49%" alt="Onglet Carnet : un nouveau contact, et à côté la station en direct — FO-29, ses fréquences, le polar plot, le rotor">
<img src="images/pc-carnet-contacts.webp" width="49%" alt="Onglet Carnet, les contacts : en cocher plusieurs pour les supprimer, l'état d'envoi de chacun">
</p>

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

<p align="center"><img src="images/pc-telephone.webp" width="70%" alt="Le téléphone rejoint le PC en scannant un QR code"></p>

## Journal des passages

La page du journal du téléphone, sous les onglets du PC : les passages listés (♪ quand il y a du son), rejoués sur le ciel ou la carte avec le S-mètre, la fréquence, les contacts et les images à leur place. « Fabriquer » : son, image, vidéo, GIF ou fichier de passage. **Le cadre du fichier est dessiné sur le rejeu** (téléphone 3:4 ou écran 16:9) : ce qui est dans le cadre est ce que montre le fichier.

<p align="center">
<img src="images/pc-journal.webp" width="49%" alt="Journal des passages : un passage de l'ISS rejoué sur la carte">
<img src="images/pc-journal-cadre.webp" width="49%" alt="Une vidéo 3:4 : le cadre du fichier sur la carte, la légende dessous">
</p>

## Éléments orbitaux

Comme sur le téléphone : le **serveur GP SatMe** d'abord (`https://gp.f4ioz.fr` d'origine), ou ce serveur seulement, et les sources au choix — le bulletin AMSAT, les groupes CelesTrak (par le serveur seulement : le PC ne demande jamais rien à CelesTrak), SatNOGS DB, et **les satellites d'images (SSTV, SSDV)** : LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… la plupart absents du bulletin AMSAT, demandés un par un par leur numéro.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Réglages : éléments orbitaux (serveur, sources), QRZ.com, carnet en ligne avec l'envoi automatique"></p>

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

## Sécurité du matériel

- **Rien ne s'ouvre tout seul.** Un port série ne s'ouvre que choisi, et « Connecter » cliqué. Pas de recherche automatique.
- **RTS et DTR bas** d'origine. Le CAT Icom les lève (ses ponts USB en ont besoin) : **choisir le port du bon poste**. Le TS-2000 les garde bas, et un port qui renvoie nos propres mots (le port PTT d'un RigExpert) est refermé aussitôt.
- Sous Linux le système lève RTS/DTR un instant à chaque ouverture de port, avant que SatMe puisse les baisser : une raison de plus de n'ouvrir que le port voulu.
- **Aucun ordre d'émission** n'est jamais envoyé (`TX`, `KY` refusés par le pilote TS-2000).
- **L'alimentation LNB du MiniTiouner est coupée** tant qu'on ne la choisit pas.

## Avant le premier essai

- **Linux** : être dans le groupe `dialout` (`sudo usermod -aG dialout $USER`, puis se reconnecter).
- **Windows** : installer le pilote de l'adaptateur (CP210x, FTDI, CH340) ; le poste apparaît en `COM3`, `COM4`…
- Commencer avec le **poste simulé** et le **rotor simulé**, puis brancher le vrai matériel un élément à la fois.
