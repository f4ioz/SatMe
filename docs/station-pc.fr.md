# SatMe-PC — la station dans un navigateur

*Octobre 2026. L'application Android reste la référence ; le PC fait ce qu'il fait, et ce qui est fait sur le PC vient aussi sur le téléphone.*

SatMe tourne sur un PC (Linux, Windows, Raspberry Pi) et se pilote depuis un navigateur. Son cœur est **le même code que sur le téléphone** : boucle Doppler, suivi du rotor, journal des passages, SSTV, METEOR, APRS, FT8, QO-100, DATV et carnet viennent du module commun `:moteur`.

<p align="center"><img src="images/satme-ensemble-fr.svg" width="100%" alt="Les trois SatMe : le téléphone, SatMe-PC et SatMe-Node au pied du mât ; le téléphone rejoint le PC par un QR code ; le même moteur partout"></p>

<p align="center"><img src="images/pc-station.webp" width="100%" alt="SatMe-PC, onglet Station : les prochains passages, FO-29 suivi sur le ciel, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor avec leur coche verte"></p>

## Sommaire

1. [L'installer](#linstaller) — options, la page sur les autres machines et son mot de passe
2. [Les onglets d'un coup d'œil](#les-onglets-dun-coup-dœil) — espaces de travail, bandeau, « Son », raccourcis, manuel intégré (F1)
3. [Station et passages](#station-et-passages) — plan du rotor, mode automatique, campagnes SSTV et leur auto-pilote, activités à venir (sources en ligne), préparer un passage, enregistrement, agenda, horizon
4. [Le carnet](#le-carnet) — choix du satellite, correction d'un contact, indicatifs composés, le passage et le rotor d'un contact
5. [SatMe sur le téléphone](#satme-sur-le-téléphone)
6. [Journal des passages](#journal-des-passages) — rapport de passage
7. [QO-100 : le cockpit](#qo-100--le-cockpit) — décodeurs multiples, PSK31 et RTTY, balise, un LNB qui dérive, LNB et référence GPSDO
8. [DATV : réception](#datv--réception) — MiniTiouner, SatMe-Node, Pluto / bladeRF, bande large, qualité, sous-titres
9. [DATV : émission](#datv--émission) — DVB-S2 et DVB-S, la station, les règles, l'essai en intérieur, carte et chat
10. [SSTV](#sstv) — réception et émission
11. [FT8 / FT4](#ft8--ft4)
12. [Télémesure](#télémesure) — paquets 1200 à 9600 bit/s, images SSDV, FUNcube, SatNOGS
13. [Phonie → texte](#phonie--texte) — traduction écrite et parlée
14. [Le récepteur SDR](#le-récepteur-sdr) — mémoires, plan de bandes, affichage, nettoyage du son, CTCSS, balayage, I/Q, générateur d'essai, mesures
15. [Matériel SDR](#matériel-sdr) — RTL-SDR, ADALM-PLUTO, bladeRF 2.0, HackRF One, la FI de l'IC-9700
16. [Avions (ADS-B) et carte](#avions-ads-b-et-carte) — 1090 MHz, diffusion par avions, la carte commune
17. [Navires (AIS)](#navires-ais)
18. [Météo spatiale](#météo-spatiale)
19. [Montage : la chaîne de la station](#montage--la-chaîne-de-la-station) — schéma, modification, catalogue, fonctions, profils
20. [Réglages](#réglages) — poste et rotor, IC-9700 par le réseau, IC-9700 en émission (PTT, micro, puissance), éléments orbitaux, journal Doppler, lien satellite, rigctld, molette USB, annonces, correction I/Q, accès à distance et HTTPS
21. [Encore sur le PC](#encore-sur-le-pc)
22. [Sécurité du matériel](#sécurité-du-matériel)
23. [Avant le premier essai](#avant-le-premier-essai)

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
| `--bureau` · `--console` | ouvrir la page dans le navigateur, une icône près de l'horloge (d'origine sous Windows) ; ou pas |

**La page sur les autres machines du réseau** (une tablette, le PC du salon) : lancer `satme --reseau`, puis ouvrir `http://<adresse du PC>:8090/` (`hostname -I` la donne). Sous Linux avec ufw, ouvrir le port au réseau local une fois, dans un terminal : `sudo ufw allow from 192.168.0.0/24 to any port 8090 proto tcp` (adapter la plage à sa box). **Un mot de passe** est alors demandé aux autres machines (ce PC lui-même entre sans) : tiré au premier démarrage avec `--reseau` (quatre mots, écrits dans le journal de SatMe-PC et montrés dans *Réglages › Accès à distance*), à remplacer par le sien (voir [Accès à distance](#accès-à-distance)). Sans HTTPS, tout passe en clair : réseau de la maison seulement, jamais ouvert sur internet. **🔈 Écouter ici**, en haut, joue le son de SatMe-PC (SDR ou carte son) dans ce navigateur.

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
| **Suivi** | les passages des 24 h, le satellite suivi sur le ciel ou la carte, l'encart AMSAT, les fréquences corrigées du Doppler, le poste et le rotor par leur nom avec une coche verte |
| **Carnet** | la saisie avec l'indicatif prédit et QRZ, la station en direct à côté, la fenêtre du décodeur CW, phonie → texte |
| **Agenda** · **Frise** · **Carrés** | les rendez-vous et le **mode automatique** ; les passages sur une frise ; les carrés contactés sur une carte |
| **Sked** | les fenêtres où une autre station et vous voyez tous deux un satellite, rejouées sur la carte, faites en vidéo |
| **QO-100** | le cockpit de la bande étroite : la réception en un clic, les signaux nommés, un clic pour s'accorder avec le bon décodeur |
| **DATV** | la bande large de QO-100 : réception (MiniTiouner, node, Pluto ou bladeRF), qualité, sous-titres, émission |
| **Montage** | le schéma de la station, ce que tient chaque fonction de SatMe, les profils de réception |
| **SDR** | le récepteur : RTL-SDR, ADALM-PLUTO, bladeRF 2.0, la FI de l'IC-9700, fichier I/Q, générateur d'essai ; cascade, vernier, loupe, mémoires, balayage, nettoyage du son, mesures |
| **Carte** · **Avions** · **Navires** | la carte commune (satellites, APRS, sondes, avions, navires, DATV, carrés, aurore) ; l'ADS-B à 1090 MHz et la diffusion par avions ; l'AIS à 162 MHz |
| **Météo spatiale** | indice Kp, flux solaire, rayons X, vent solaire, aurore, alertes (téléchargées seulement si on le coche) |
| **Télémesure** | paquets FSK / GMSK de 1200 à 9600 bit/s, images SSDV, FUNcube, balise QO-100 |
| **Sondes** · **METEOR** | radiosondes ; images LRPT de METEOR-M |
| **Son** | la carte son, l'enregistrement |
| **SSTV** | le décodage en direct, les images, la mire, l'émission |
| **Planche** | la planche SSTV : les images sur un modèle — toutes ses cases, ou moins, disposées le plus grand possible —, un fond (étoiles, dégradés, images NASA ou la tienne) et jusqu'à trois écussons glissés à la souris, contour enlevé au besoin ; téléchargée en pleine taille. **Exporter le modèle…** le donne en un fichier `.satme-planche` (disposition, textes, fond, écussons déjà détourés — aucune image reçue ; l'indicatif et le nom restent des champs que remplit qui l'importe, sauf « garder mon indicatif écrit en dur » coché) ; **Importer un modèle…** (ou le fichier déposé sur la page) prend une image ARISS ou un tel fichier comme nouveau modèle, jamais par-dessus un autre |
| **APRS** · **FT8** | l'APRS (radio KISS comprise) et son jeu ; le tableau FT8 / FT4 |
| **Journal** | les passages rejoués avec leur son, faits en image, vidéo ou GIF |
| **Réglages** | par catégorie : Ma station, Poste et rotor, Satellites, Interfaces, Annonces, Services en ligne, Phonie → texte, Téléphone, Accord fin, Émission, Accès à distance, Sauvegarde |

Les onglets QO-100, DATV et SSTV tiennent sur un écran (de 1920×970 à 1366×700) : la vue principale reste en vue, les cartes secondaires sont des **sous-onglets** à icônes, le dernier ouvert gardé.

<p align="center"><img src="images/pc-espaces-palette.png" width="100%" alt="SatMe-PC : le rail des espaces de travail déplié à gauche, le bandeau en haut, la barre d'état en bas, et la palette Ctrl+K qui propose d'écouter 145,800 MHz sur le récepteur SDR"></p>

- **Espaces de travail**, dans le rail de gauche (touches **1** à **6**, chacun rouvert à son dernier onglet ; **[** le déplie ou le replie) : **Ciel** (Suivi, Agenda, Frise, Sked, Carrés, Carte, Avions, Navires, Météo spatiale, METEOR, Sondes), **QO-100**, **DATV**, **Modes** (SSTV, APRS, FT8, Télémesure), **Carnet** (Carnet, Journal, Planche), **Station** (Montage, SDR, Son, Réglages). Sur un téléphone, le rail devient une barre en bas. Avions, Navires et Sondes ne sont dans le rail que cochés (voir [Fonctions hors satellites](#fonctions-hors-satellites)).
- **Ctrl+K** : la palette de commandes — onglets et sous-onglets, catégories de réglages, actions (« Arrêter toute émission »…), un satellite, ou une fréquence tapée (« 145,8 ») que le récepteur SDR écoute.
- **Le manuel intégré** : **F1** l'ouvre, sur le chapitre de l'onglet en cours ; on y cherche un mot ; ses chapitres sont ceux de ce guide. **Échap** le ferme — un second **Échap** arrête les émissions, comme toujours. Les « ? » à côté des titres des cartes donnent l'explication courte, avec « En savoir plus » vers le manuel.

<p align="center"><img src="images/pc-manuel.png" width="100%" alt="Le manuel intégré (F1) ouvert sur le chapitre du récepteur SDR : le sommaire à gauche, la recherche en haut"></p>

### Le bandeau et la barre d'état

- **Le bandeau** (en haut) : l'indicatif et le carré, ce qui est suivi (un satellite, le cockpit QO-100), la fréquence et le mode du récepteur actif (le poste en CAT, sinon le SDR), la puce **Son**, le prochain passage avec son décompte, l'heure locale et UTC, **Distant** (voir [Mode distant](#mode-distant)), **🔈 Écouter ici**. Pendant une émission (DATV, SSTV, calibration I/Q), un voyant **TX** rouge-orange : un clic arrête tout.
- **La barre d'état** (en bas) : le SDR, le récepteur DATV, le poste, le rotor, l'enregistrement, le lien satellite, les clients rigctld, les erreurs (de la page et du serveur) — chacun mène d'un clic à son onglet. Un clic sur **Poste** ouvre un petit menu Connecter / Déconnecter qui envoie exactement le modèle et le port affichés.

### Le son des décodeurs (« Son »)

La puce **Son** du bandeau (touche **S**) dit ce qu'entendent SSTV, FT8, CW, APRS et phonie → texte, avec un petit vumètre. Son menu : les sources (les cartes son par leur nom — « IC-9700 (USB Audio CODEC) », « Carte mère (ALC897) » —, le récepteur SDR, l'IC-9700 par le réseau), démarrer ou arrêter, les décodeurs à l'écoute, **● Enregistrer le son**, **Préparer le passage**, **Écouter ici**. Un décodeur qui n'entend rien propose le geste qui le répare.

- Allumer un décodeur ou demander un enregistrement démarre la carte qu'il lui faut : celle choisie pour le satellite, la dernière utilisée, celle du poste — jamais une carte devinée. Réception seulement.
- Les cartes son sont reconnues et nommées d'après le système (`/proc/asound`, sysfs ; aucun port ouvert) : un codec sur le même concentrateur USB que le poste en CAT devient « IC-9700 (USB Audio CODEC) ».

<p align="center"><img src="images/pc-son.png" width="100%" alt="La puce Son du bandeau ouverte : la carte mère, la carte son de l'IC-9700 (le poste), le récepteur SDR en marche, les décodeurs à l'écoute SSTV et APRS, Enregistrer le son, Préparer le passage, Écouter ici"></p>

### Raccourcis clavier

| Touche | Effet |
|---|---|
| **Échap** | arrêt de toute émission (DATV, SSTV, calibration I/Q, mire) ; n'arrête que, ne démarre jamais rien |
| **Ctrl+K** | la palette de commandes |
| **1** … **6** | les espaces de travail |
| **?** | l'aide des raccourcis · **F1** le manuel · **[** le rail |
| **S** | le son des décodeurs |
| **E** | enregistrer le son, ou arrêter l'enregistrement |
| **P** | préparer le passage du satellite suivi |
| **N** | noter un contact (le carnet, le curseur sur l'indicatif) |
| ← → ↑ ↓ | QO-100 : accord par 100 Hz / 1 kHz |
| **+** **−** **0** · **E** · **V** | Montage : agrandir, réduire, tout le dessin · modifier · vue complète ou simplifiée |

## Station et passages

**Suivi** (espace **Ciel**) liste les passages des 24 prochaines heures (favoris, entendus ces jours-ci sur AMSAT, agenda), le satellite suivi sur le ciel ou la carte, à droite de son nom l'**encart AMSAT** (ce que les stations entendent, « Entendu » / « Pas entendu » en un clic), les fréquences corrigées du Doppler avec les calages RX/TX, **● Enregistrer le son**, et **le poste et le rotor par leur nom, une coche verte ✓ quand ils sont connectés** — un clic mène à leurs réglages.

- **⤓ Agenda (.ics)** : les passages de la liste (ses filtres gardés), ou ceux du satellite suivi, dans un fichier d'agenda (RFC 5545, UTC ; satellite, élévation maximale, azimuts d'AOS et de LOS) pour n'importe quel agenda.
- **⚡ Préparer le passage** (touche **P**) : pour le satellite suivi, la source son, les décodeurs de son émetteur (SSTV, APRS, FT8, CW…) et l'enregistrement armé pour l'AOS. Réception seulement.
- **Le poste suit tout de suite** : changer de satellite suivi réaccorde le poste en CAT aussitôt, sur le milieu de la bande passante d'un transpondeur linéaire. *Régler le poste même quand le satellite n'est pas visible* (Réglages › Poste et rotor, décoché d'origine) : le poste est prêt avant l'AOS, ou pour un essai à l'établi — accord seulement, jamais d'émission.
- **Horizon** (Réglages › Ma station) : la hauteur des obstacles par secteur d'azimut (8 × 45° ou 12 × 30°), dessinée vue de dessus. Les passages commencent et finissent alors quand le satellite les dépasse (l'AOS/LOS géométrique sous ✱) ; ceux cachés de bout en bout sont écartés. Tout à 0 ne change rien. Même réglage sur le téléphone.
- **Le plan du rotor** : avant l'AOS, SatMe prévoit tout le passage — de quel côté de la butée, droit ou **retourné** (un rotor qui monte à 180° suit par-dessus le zénith : az + 180°, 180° − el, sans tour), et s'il le faut le seul moment où le rotor change (retournement, ou **déroulement** : un tour, ~1 min sans réception), joué à la vitesse mesurée du rotor et placé quand le satellite est bas ou caché. Le plan est dessiné sur le ciel de Suivi — violet : droit ; ambre : retourné ; rouge pointillé : le changement, avec son heure — et dit en une phrase dessous. Le suivi prend la branche du plan d'après l'horloge : plus d'hésitation au bord de la butée. Les réglages : [Le poste et le rotor](#le-poste-et-le-rotor).

<p align="center"><img src="images/pc-rotor-plan.webp" width="49%" alt="Suivi, le ciel de CroCube avant l'AOS (rotor simulé, butée au sud, 90° d'élévation au plus) : le passage en bleu passe par la butée, le plan prévoit un déroulement à 21:58 en rouge pointillé ; la zone cachée au sud en gris ; dessous, « Passage par la butée : déroulement prévu à 21:58, ~46 s »"></p>

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

### Campagnes SSTV : l'auto-pilote

Onglet **Agenda › SSTV · auto-pilote**. Un événement SSTV — une série ARISS, un satellite qui envoie ses images quelques jours — s'entre comme un rendez-vous : le satellite, son émetteur ou une fréquence à part (FM ou BLU, largeur), le mode SSTV (ou *Auto* : l'en-tête VIS le dit), la période (« horaire à confirmer » : des journées entières), l'élévation minimale, le rythme (« 36 s on / 2 min off », pour mémoire), le lien et des notes.

- **Importer une affiche** : l'image de l'annonce (JPEG, PNG), ou son adresse, est lue sur ce PC par **tesseract** (`sudo apt install tesseract-ocr`) ; fréquence, mode, dates, satellite, rythme et lien remplissent le formulaire, à vérifier avant d'enregistrer. Rien n'est envoyé ; une adresse n'est demandée que si on la donne.
- **La chaîne de réception** de la campagne : un profil du [Montage](#les-fonctions-et-les-profils), ou case par case — le poste (CAT, Doppler), le rotor qui suit le satellite, le son (celui du poste, IC-9700 par le réseau ou carte USB ; une carte son ; le récepteur SDR ; ou ne pas y toucher), l'enregistrement, le décodeur SSTV tenu sur le mode, l'annonce vocale, le rotor parqué après le LOS. *Le connecter tout seul* : le poste seulement par la liaison réseau de l'IC-9700 ou le port USB de l'Icom lui-même, le rotor seulement par rotctld.
- **L'auto-pilote** (interrupteur général, coupé d'origine, aussi dans le bandeau) : quelques minutes avant l'AOS de chaque passage assez haut, il prépare la chaîne ; il enregistre de l'AOS à après le LOS, rattache les images reçues à la campagne, puis remet la réception comme avant. Pendant le passage, un bandeau avec **■ STOP**.
- **Réception seulement** : il n'émet jamais et ne touche à aucun réglage d'émission. Une émission en cours, la DATV qui tient le récepteur, ou une autre campagne au même moment : le passage est écarté et la raison notée dans l'**historique**.
- Les **passages 🤖** dans la liste de l'onglet, dans Suivi et sur la frise ; la campagne dans la palette **Ctrl+K**. Les campagnes sont gardées sur le PC (`sstv-campagnes.json`) et réarmées après un redémarrage.

<p align="center"><img src="images/pc-agenda-sstv.webp" width="100%" alt="Agenda › SSTV · auto-pilote (données simulées) : le formulaire d'une campagne et sa chaîne de réception, l'auto-pilote actif et le prochain passage, l'import d'une affiche, la campagne ARISS SSTV Series 34 sur l'ISS, ses prochains passages prévus, l'historique"></p>

### Activités à venir : les sources en ligne

Onglet **Agenda › Activités à venir**. Ce que les satellites vont émettre et que SatMe décode, d'après des sources publiques : la **SSTV de l'ISS** (et d'autres satellites), les **contacts ARISS** avec les écoles (phonie FM), le **SSDV**, les images **METEOR LRPT**. Les satellites seulement ; jamais NOAA APT (arrêtés en 2025).

- **Les sources**, chacune à son rythme : l'**agenda ARISS** (Google Agenda public, toutes les 6 h au plus), les actualités **AMSAT-UK** et **AMSAT-F** (RSS, toutes les 6 h au plus), **SatNOGS** pour METEOR-M2-3 et M2-4 (deux fois par jour : « vu récemment sur 137,900 MHz », une indication), les **écoutes AMSAT** (toutes les 30 min, seulement pendant une SSTV/SSDV annoncée). Actif d'origine ; un interrupteur général et un par source. Une source qui échoue le dit discrètement, avec sa dernière lecture ; tout est gardé sur le PC (`activites-cache.json`) et la liste reste hors ligne.
- **La liste**, par jour : satellite, type, période (heure locale et UTC), fréquence, mode, rythme, sources et liens, et **tes passages** pendant l'activité avec leur élévation maximale (au moins l'élévation minimale des Réglages). Deux sources qui disent la même chose (15 h 10 ici, 15 h 30 là) font une seule activité : le début le plus tôt, la fin la plus tardive, toutes les sources. *Annoncé*, *confirmé par écoutes* (des rapports AMSAT pendant la période) ou *vu récemment* (SatNOGS).
- **Prévenir** : une nouvelle activité (une fois ; un contact ARISS seulement s'il passe ici), avant chaque passage utile (10 min avant l'AOS d'origine) et « **ça émet maintenant** » quand des écoutes AMSAT arrivent ; chacun se coupe. Un bandeau en bas de la page et un compteur sur le sous-onglet.
- **Préparer la campagne SSTV** : ouvre [Agenda › SSTV](#campagnes-sstv--lauto-pilote) avec le formulaire rempli (satellite, fréquence, mode, période, rythme, lien) ; rien n'est enregistré avant « Enregistrer la campagne ».

<p align="center"><img src="images/pc-activites.webp" width="100%" alt="Agenda › Activités à venir : les sources lues, les alertes, METEOR-M2-3 et M2-4 vus récemment sur 137,900 MHz, la série ARISS SSTV 34 de l'ISS sur 437,550 MHz en Robot 36, confirmée par les écoutes, et les passages de la station"></p>

### Enregistrer le son

- **● Enregistrer le son** dans Suivi, Carnet et Son : un WAV au nom du satellite (`SatMe_RS-44_20261013_203051Z.wav`), comme sur le téléphone.
- **Couper à la fin du passage** (option) : même lancé à la main, l'enregistrement s'arrête 30 s après le LOS du passage qu'il a pris.
- **Chaque passage du satellite suivi** (option de l'onglet Son), ou ceux du **mode automatique**.
- Un enregistrement arrêté à la main ne repart pas tout seul avant le passage suivant.
- **Une carte son qui enregistre garde ses décodeurs** quand le récepteur SDR démarre : QO-100 allumé pour écouter est seulement entendu, jamais enregistré à sa place (et à la mauvaise cadence). Sinon, le SDR rend en s'arrêtant la carte son qu'il avait prise : le son de l'IC-9700 ne reste plus muet.

## Le carnet

Un contact se saisit avec l'indicatif prédit d'après le carnet et QRZ (Tab passe d'une prédiction à l'autre, Entrée valide). À côté, la station en direct : le satellite, ses fréquences, le polar plot, le rotor, où l'on est dans la bande passante, l'enregistrement. La fiche de l'indicatif : tous les contacts passés et leurs notes, la fiche QRZ.com (photo, distance, carte). Un contact oublié s'ajoute après le passage, à son heure. Les **records** par satellite : le contact le plus lointain de tous, de chaque satellite, mode, continent et pays, le WAC.

- **Décodeur CW** (coupé tant qu'on ne le coche pas) : la note trouvée seule, la vitesse suivie, le Doppler suivi ; indicatifs, carrés et reports mis en couleur, un indicatif dans la saisie d'un clic.
- **Supprimer** un contact depuis sa ligne (🗑), ou en cocher plusieurs et les supprimer d'un coup.
- **Les profils de station de Wavelog** : « Lire les profils » montre où l'on est (son carré, ou les deux d'une ligne de carrés) et le profil où cela part — choisi dans la liste, ou **créé** quand Wavelog n'en a pas (DXCC, zones CQ et ITU demandés). Chaque contact part au profil de l'endroit d'où il a été fait ; un profil choisi à la main pour un autre carré est signalé.
- **Chaque nouveau contact envoyé tout seul à Wavelog / Cloudlog**, après un délai au choix (30 s à 30 min) : le temps de le corriger ou de le retenir (⏸, décompte dans le carnet). Un contact que le serveur refuse est mis en pause, avec la raison. Le téléphone a le même choix de délai.
- L'export **ADIF**, et le dépôt à la main (⬆).
- **Sur quel satellite** : un contact est noté sur le satellite choisi dans la saisie, sinon celui du poste (le satellite suivi, Doppler par CAT), QO-100 quand le poste y est ou que le contact est saisi dans le cockpit. Un récepteur qui écoute QO-100 en arrière-plan ne change plus le carnet. La saisie dit où elle note et pourquoi, avec un bouton pour changer.
- **Corriger un contact** : le satellite (la liste, QO-100, un nom libre, terrestre), la **date et l'heure UTC** (l'heure locale à côté, « maintenant » ; ni dans le futur, ni avant 1961). Azimut, élévation, fréquences et ordre du carnet suivent ; un doublon (même indicatif, même satellite, 10 min) est signalé. Le téléphone fait de même.
- **Indicatifs composés** (`EA3/M5JFS`, `DL1ABC/P`, `F/ON4ABC/P`) : le pays est celui du préfixe d'opération (EA3/M5JFS = M5JFS depuis l'Espagne), avec son **drapeau** dans la liste, la saisie, la fiche et l'éditeur. Quand QRZ n'a pas la forme composée, la fiche est celle de l'opérateur, identité seulement (ni carré, ni position, ni adresse). Le carré pris d'une fiche est celui de l'indicatif tel qu'il est tapé (« F4IOZ/P » ne reprend pas le carré de « F4IOZ »). **La carte** suit la saisie en direct : le carré tapé (un carré invalide : pas de point), sinon le centre du pays, plus pâle ; l'éditeur d'un contact a sa propre carte.
- **⌖ sur la ligne d'un contact** : son passage sur le ciel et le chemin du rotor tel qu'il est allé, tirés du journal (cyan : le satellite, jaune : le moment du contact). Le téléphone sert la même vue.

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
- **L'image SSTV de la vidéo, à la souris** : sur le rejeu, la glisser pour la déplacer, tirer son coin (ou la molette) pour sa taille (4:3, de 15 à 90 % de la largeur) ; un cadre pointillé la montre au survol même quand aucune image n'arrive. Gardée par la station et reprise telle quelle par l'image, le GIF et la vidéo, en 16:9 comme en 3:4. **Auto** la remet à sa place d'origine : en haut, dans le coin opposé au satellite.
- **Le S-mètre** lu par le CAT (IC-9700, IC-9100, IC-910H, TS-2000), en réception seulement.
- **Ajouter** un contact au rejeu : en pause, l'heure est celle du son ; indicatif prédit et QRZ, mode et fréquences du satellite, RST (59 / 59 d'origine) et commentaire ; Entrée valide.
- **+ Passage** : un passage fait de rien — le satellite et ses heures, des contacts, un son, des images.
- Le **journal Doppler / CAT** du passage (quand il est tenu, voir [Réglages](#réglages)) y est relié.

### Rapport de passage

**Rapport**, dans un passage du journal : le compte rendu d'observation du passage, en une page.

- L'en-tête : AOS, TCA, LOS, élévation maximale, la station telle que dessinée dans le Montage, l'indice Kp du moment.
- **La cascade suivie du Doppler** : tirée de l'enregistrement I/Q SigMF du passage, ou du *Journal détaillé* (Réglages › Poste et rotor › Journal Doppler / CAT, décoché d'origine : le spectre autour de la descente noté pendant les passages suivis).
- La frise du niveau et de l'élévation, avec les décodages et les contacts marqués ; les décodages (SSTV, APRS, télémesure, phonie), les contacts, les sons à écouter, une note de qualité.
- **Enregistrer** en un seul fichier HTML, ou en ZIP avec ses fichiers ; **Partager** ne fait qu'un fichier local. Rien n'est envoyé.

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
4. La **balise médiane** est la référence : avec le *Calage automatique*, elle est mesurée toutes les 5 s et la dérive du LNB suivie par petits pas, jamais sur une mesure faible — de plus près quand le LNB dérive vite (voir [Un LNB qui dérive](#un-lnb-qui-dérive)).

- **Classement des signaux** : chaque signal du transpondeur nommé sur la cascade (BLU, CW, FT8, FT4, PSK31, RTTY, SSTV, porteuse, balises, numérique large, inconnu), coloré par classe avec sa confiance ; une FFT à cases de 16 Hz sur le flux brut du récepteur, les peignes de raies parasites et les clics de manipulation écartés, les signaux oubliés une fois partis. Il ne tourne que tant que la page le regarde.
- **Plan de bande** (celui d'AMSAT-DL) sur la réglette et en tête de la cascade, la règle de chaque segment au survol. **Fréquence d'urgence** (10 489,858 – 10 489,865) : contour et bandeau rouges ; SatMe n'y envoie jamais la montée au poste de lui-même (mémoire, signal cliqué, retour DATV, session reprise) — seulement accordée à la main. La place libre FT8 n'est proposée que dans les segments numériques ; un mode hors de son segment est signalé discrètement.
- **LEILA** : quand la sirène de la station sol est entendue (la station écoutée émet plus fort que les balises et doit baisser sa puissance), un **⚠ LEILA** rouge s'affiche 10 s sous la descente.
- **Décodeurs multiples** (bouton **Multiples**, coupé d'origine) : jusqu'à **4 canaux** pris dans ce que voit le récepteur, chacun avec son décodeur (FT8, FT4, CW, SSTV, phonie, balise BPSK, ou simple écoute ; une seule SSTV, une seule phonie, une seule balise). Préréglages « FT8 + CW + phonie » et « FT8 + FT4 » ; clic droit (ou Maj + clic) sur un signal de la cascade : « Ajouter un décodeur ici ». Le son reste celui de l'accord principal, sauf le canal coché 🔈 ; un clic sur la fréquence d'un canal y accorde le récepteur principal. De l'ordre de 1 % d'un cœur par canal.
- **PSK31 et RTTY** : deux décodeurs de plus (onglet *PSK31 / RTTY* du décodeur, ou un canal des *Décodeurs multiples*). PSK31 : varicode, rattrapage de ±50 Hz, silencieux sur la qualité, S/B et IMD au repos. RTTY : ITA2, 45,45 / 50 / 75 Bd, déplacement 170 / 425 / 850 Hz, inversion. Lignes horodatées, indicatifs mis en couleur, copier et enregistrer ; une étiquette PSK31 ou RTTY de la cascade les ouvre.
- **Affichage** (palette, contraste et luminosité de la cascade, drapeaux des mémoires), **Mémoires**, **Mesures** et le **nettoyage du son**, comme dans l'onglet SDR (voir [Le récepteur SDR](#le-récepteur-sdr)) ; les mémoires d'ici portent l'étiquette « QO-100 ».
- **Reprise après un arrêt** : un bandeau propose de reprendre QO-100 où il en était (récepteur, fréquence, mode) ; *Réglages › Satellites › QO-100* le fait tout seul au démarrage. Réception seulement : rien n'est jamais émis.
- **Balise** (sous-onglet) : la télémesure de la balise médiane décodée — BPSK 400 bit/s, Manchester, codage différentiel ; ses trames codées (256 octets en 13 s : code convolutif r=1/2 K=7, deux Reed-Solomon (160,128), embrouillage, entrelacement 80 × 65 et synchro répartie, le format codé proposé pour AO-40) et ses blocs non codés de 512 octets (synchro 39 15 ED 30, CRC). « Décoder la balise » pose un canal des *Décodeurs multiples* sur 10 489,7485 MHz (la balise à 1,5 kHz, cherchée à ±600 Hz : garder le calage sur la balise) ; chaque trame dit ses octets corrigés, ses symboles faux et son rapport signal/bruit, le texte ou les octets bruts. « Essai » fait passer un signal fabriqué (bruit, décalage, dérive) par le même décodeur. Les trames reçues sont gardées sur le PC (`telemesure/`), rien n'est envoyé.
- Sous-onglets : **Calage** (sur la balise, à la main ou automatique), **Qualité** (balise sur le bruit, écart, erreur et dérive du LNB sur l'heure, plancher de bruit, la courbe du jour), **Balise**, **Mémoires**, **Poste & chaîne** (convertisseurs), **Pointage**.

<p align="center"><img src="images/pc-qo100-multiples.png" width="49%" alt="Cockpit QO-100 : le menu Décodeurs multiples, jusqu'à quatre canaux, les préréglages FT8 + CW + phonie et FT8 + FT4"></p>

<p align="center"><img src="images/pc-qo100-cockpit.webp" width="100%" alt="Cockpit QO-100, récepteur arrêté : les quatre étapes, le récepteur et son accord, le décodeur, la saisie du carnet, le plan de bande et ses segments sur la cascade, le calage automatique sur la balise"></p>

### Un LNB qui dérive

Un LNB sans référence extérieure bouge avec la température : quelques hertz par seconde après sa mise sous tension ou au soleil, parfois des sautillements de ±25 Hz en une seconde. Les tons de FT8 sont à 6,25 Hz les uns des autres : un tel LNB suffit à ne plus rien décoder. SatMe-PC fait trois choses, toutes en réception :

- **Le calage qui suit** : quand le *Calage automatique* voit une dérive réelle (plus de 1 Hz/s, au-delà de trois écarts types), la balise est mesurée chaque seconde et l'erreur prise sur la droite des 30 dernières secondes plutôt que sur une moyenne. Après un pas, seules les mesures faites depuis comptent. Pendant que FT8 ou FT4 décode le son du récepteur, les pas ne tombent que dans la dernière seconde du créneau, entre deux émissions.
- **Verrouillage sur la balise** (sous-onglet *Calage*, coupé d'origine) : la balise médiane suivie par une boucle de phase dans le flux I/Q brut ; ses sautillements sont ôtés de tout le transpondeur, en continu, sans à-coup, et le calage garde l'écart lent. La correction est bornée (±250 Hz) et se surveille : butée atteinte ou correction trop agitée, elle lâche pour 30 s ; un pas du calage relance aussitôt la recherche, la correction gardée. État : *balise cherchée*, ou *tient · N Hz rms ôtés*.
- **FT8 qui tolère la dérive** : la dérive commune d'un créneau (jusqu'à ±15 Hz/s) est cherchée sur les synchros, puis ôtée avant une seconde lecture.

Sur des créneaux fabriqués comme en vrai (balise à 17 dB, gigue de 12 Hz rms, 5 à 7 Hz/s de dérive), FT8 passe de 0 à 4 ou 5 décodages sur 5. Cela **atténue** le problème sans le régler : le verrouillage a besoin d'une balise bien au-dessus du bruit, et un LNB qui saute plus vite que la boucle reste un LNB qui saute. **La vraie réponse est un LNB tenu par une référence.**

<p align="center"><img src="images/pc-qo100-calage.webp" width="40%" alt="Cockpit QO-100, sous-onglet Calage : le calage automatique et le verrouillage sur la balise cochés, « balise cherchée » tant que le récepteur ne tourne pas, l'écart mémorisé"></p>

### LNB et référence GPSDO

Un LNB dont l'oscillateur est verrouillé sur une référence tenue par le GPS ne dérive plus : plus de calage qui court, FT8 et la DATV tranquilles. Le plan de la station de F4IOZ, que le [Montage](#dessiner-et-modifier) sait dessiner (catalogue : LNB Twin à référence 25 MHz externe, GPSDO Leo Bodnar programmable, injecteur, bloqueur de continu) :

- un **LNB Twin** dont la PLL prend une référence externe de **25 MHz** ;
- ce 25 MHz donné par un **GPSDO Leo Bodnar**, câblé jusqu'à la PLL du LNB ;
- une sortie vers le **MiniTiouner** (DATV), qui alimente le LNB en **18 V** ;
- l'autre vers le **bladeRF 2.0** (bande étroite) par un **injecteur qui bloque le continu** : l'alimentation du LNB n'atteint jamais l'entrée du bladeRF.

Le calage sur la balise reste utile pour l'écart fixe ; le verrouillage peut alors rester coupé.

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

Le MiniTiouner travaille mieux près du LNB. Un **SatMe-Node**, c'est SatMe sur un petit ordinateur là-bas — un Raspberry Pi (un **Pi 3 suffit** pour le MiniTiouner : le node ne fait que recevoir) — piloté par le réseau depuis SatMe-PC et depuis le téléphone (écran **QO-100** → **DATV** : les mêmes réglages, l'image par le décodeur du téléphone, en plein écran d'un toucher ; les stations de la bande large, à choisir d'un toucher, si on coche le spectre de la station sol). Le téléphone et SatMe-PC reprennent du node l'oscillateur calé, l'entrée et **l'alimentation LNB** en cours : changer de station, depuis l'un ou l'autre, ne coupe plus le LNB que le node alimente ; SatMe-PC redémarré retrouve seul un node qui reçoit. Il fait tourner LongMynd, ou le démodulateur DVB-S2 de SatMe avec un Pluto ou un bladeRF ; son état et le flux de transport (0,3 à 2 Mbit/s) arrivent au PC, qui les montre, les joue et les enregistre comme si le récepteur y était branché.

L'installer sur le Pi : [Installer SatMe-Node sur un Raspberry Pi](installer-satme-node.fr.md) — trois commandes, une dizaine de minutes sur un Pi 3. Le script installe Java, compile LongMynd, lui donne accès au MiniTiouner (udev) et démarre le service **satme-noeud** (`satme --noeud`, port 8090). À son premier démarrage, le node tire un **code à six chiffres**, affiché à la fin de l'installation (le revoir : `journalctl -u satme-noeud | grep Code`). Dans SatMe-PC, onglet DATV, **Changer** : l'adresse du node et son code, **Associer**. Le PC garde la clé que le node lui a donnée : l'un comme l'autre peut redémarrer. Une adresse vide revient au récepteur du PC. Sans sa clé, le node ne dit que ce qu'il est ; son alimentation LNB reste coupée tant qu'on ne la choisit pas.

### SatMe-Node : son SDR, son émetteur, sa santé

Le node ne sert pas qu'à la DATV. Branchements conseillés, avec un **LNB à deux sorties** :

- **sortie 1 → MiniTiouner**, qui alimente le LNB en **18 V** (polarisation **horizontale**, la DATV large bande) ;
- **sortie 2 → un T de polarisation (DC block côté carte) → bladeRF** : le T alimente cette sortie en **13 V** (**verticale**, la bande étroite), la carte ne reçoit que la HF ;
- le **bladeRF sur un port USB 3** (bleu) d'un **Raspberry Pi 4 ou 5** ; un Pluto peut le remplacer, et/ou servir d'émetteur.

Le Montage propose ce câblage dès qu'un node associé annonce un MiniTiouner et un SDR.

<p align="center"><img src="images/pc-montage-node.webp" width="100%" alt="Montage, en direct : parabole QO-100, LNB, MiniTiouner, SatMe-Node, puis SatMe-PC par le réseau ; « Réception DATV : MiniTiouner, recherche (par SatMe-Node) », la FI 741,760 MHz sur le câble, le téléphone relié à SatMe-PC"></p>

**Le SDR du node, récepteur de ce PC.** Sur la page du node (ouverte sur le node), dans Station › SatMe-Node, ou dans l'onglet SDR de SatMe-PC : la carte prêtée comme SDR (bladeRF ou Pluto), indépendante de celle de la DATV — MiniTiouner et bladeRF reçoivent en même temps. Dans l'onglet SDR, récepteur **« SDR du SatMe-Node »** : il sert partout comme une carte branchée ici (cockpit QO-100, cascade, FT8, calage et verrouillage sur la balise, enregistrement). Le node accorde sa carte comme le PC le demande (fréquence, gain, correction), ramène le flux au débit choisi par demi-bandes successives (1,058 MS/s : tout le transpondeur bande étroite, environ 34 Mbit/s, câble conseillé ; 529 ou 265 kS/s pour un réseau lent), et l'envoie sur **une seule connexion** : un en-tête (débit, centre, format), des trames numérotées et datées (les trames perdues se comptent). Réseau coupé : la réception attend, dit pourquoi, et reprend seule.

**L'émetteur du node.** Station › SatMe-Node › **« Émettre par le SatMe-Node »** (décoché au départ) : les émissions DATV et SSTV (FM ou BLU) partent par l'émetteur du node (Pluto ou bladeRF, du même type que l'émetteur choisi ici). SatMe-PC module comme d'habitude et applique **toutes** ses règles (confirmation, indicatif, bandes, durée, plafond, boucle sur la balise) ; le node ajoute les siennes :

- **« émission autorisée sur ce node »**, décochée au départ, ne se coche **que sur le node lui-même** (sa page ouverte sur le Raspberry Pi, ou par un tunnel `ssh -L 18090:localhost:8090 pi@node` puis `http://localhost:18090`) ; la décocher coupe aussitôt une émission ; choisir une autre carte d'émission depuis SatMe-PC la décoche aussi ;
- ses bandes (144–146, 430–440, 1240–1300, 2300–2450 MHz), sa durée maximale et son propre plafond de gain ;
- un **chien de garde** : plus rien de SatMe-PC pendant 1,5 s (échantillons ou « toujours là »), ou la connexion fermée, et l'émission s'arrête ;
- jamais une carte qui reçoit déjà (SDR prêté, DATV).

**Un seul pilote pour l'émission.** La page de ce PC, la même page ouverte d'une autre machine (mode distant, mot de passe), demain le téléphone : tous passent par les mêmes routes de SatMe-PC et ses règles. Un seul client tient l'émission à la fois ; tous voient qui, depuis quand, quoi (le témoin TX du bandeau) ; **STOP** reste possible de partout. Seul le « toujours là » du client qui tient l'émission compte : depuis **une autre machine**, une liaison coupée plus de **2 s** arrête l'émission, et la première émission de la session demande une confirmation de plus.

**Sa page.** Station › **SatMe-Node** (aussi par la petite carte de l'onglet Suivi, la puce **Node** de la barre d'état et Ctrl+K) lit le node toutes les 3 s : température et bridage du Pi (`vcgencmd`), charge, mémoire, place sur la carte microSD, réseau et débit du flux I/Q, cartes USB et leur vitesse (un bladeRF en USB 2 est signalé), état de chaque service et son client, dernières lignes du journal, sa version face à celle de ce SatMe-PC (avec les commandes de mise à jour quand le node est plus ancien). On y choisit aussi le **rôle** de chaque carte (réception DATV, récepteur SDR, émetteur — une carte ne reçoit et n'émet jamais à la fois), « Utiliser comme récepteur SDR », et on peut arrêter un service, couper l'émission du node, ou **relancer SatMe sur le node** (seulement quand son service systemd le redémarre ; le Pi, lui, reste allumé).

<p align="center"><img src="images/pc-satme-node.webp" width="100%" alt="Station › SatMe-Node, un vrai node (Raspberry Pi, MiniTiouner en USB 2) : santé (31,6 °C, charge, mémoire, microSD), réseau et flux I/Q, rôles des cartes, cartes USB vues, émission (aucun émetteur, émission non autorisée), services avec Redémarrer le Pi et Éteindre le Pi, version, journal du node"></p>

**Redémarrer ou éteindre le Pi.** Services › **Redémarrer le Pi** / **Éteindre le Pi**, chacun après une confirmation qui dit ce qui s'arrête. Le node arrête d'abord tout — l'émission (s'il émet, le dialogue propose de l'arrêter ; sans cela il refuse), la réception DATV (LongMynd, et l'alimentation LNB), le flux SDR, les cartes rendues —, écrit ses réglages, répond, puis lance `systemctl reboot` ou `poweroff`. Redémarré, la page affiche « redémarrage… », le retrouve seule et dit son retour (au-delà de 3 min, elle prévient : alimentation, réseau). Éteint, il est dit **éteint**, sans alarme, lu toutes les 30 s seulement : il ne se rallume **que sur place** (alimentation coupée puis remise). Seulement pour un node lancé par son service systemd, avec le droit que l'installeur lui donne (`/etc/sudoers.d/satme-noeud`, ces deux commandes seulement) ; sans lui, la page dit de relancer l'installeur. Aussi sur la page du node ouverte sur le Pi ; jamais sur la page servie par le téléphone.

**Le téléphone, ensuite.** Il passera par SatMe-PC (son mode distant), pas directement par le node : une seule paire de règles d'émission, un seul pilote, le chien de garde de 2 s. Le node, lui, n'accepte qu'un client d'émission à la fois et garde ses propres gardes. Ces routes répondent sans rien faire quand c'est le téléphone qui sert la page.

### La bande large d'un coup d'œil

*Spectre de la bande large — station sol, par Internet (pas ma réception)*, décoché d'origine : le spectre public de la bande large que publie la station sol de QO-100 (10490,5 – 10499,5 MHz, 4 images par seconde), lu par Internet tant que l'option est cochée et l'onglet ouvert. **Ce n'est pas ce que reçoit votre antenne** : des porteuses s'y voient même parabole dépointée.

- **Ma réception superposée**, en violet (légende *Station sol (Internet)* / *Ma réception (MiniTiouner)*) : le MiniTiouner ne donne pas de spectre, seulement son état et son MER là où il écoute. Accroché, une barre à la fréquence et à la largeur trouvées, aussi haute au-dessus du bruit que son MER (≈ C/N, tracé en (C+N)/N sur la même échelle en dB que la station sol) ; en recherche ou arrêté, des hachures « rien reçu ici ». Une ligne compare les deux, par exemple « Balise : +8,2 dB à la station sol · chez moi : MER 5,9 dB (seuil QPSK 4/5 4,7 dB) » ou « chez moi : rien (recherche) — la balise est là : vérifier pointage / polarisation / LNB ». Le téléphone (écran DATV) montre la même chose.

- Les **signaux trouvés**, chacun avec sa largeur arrondie au débit de symboles le plus proche, son niveau face à la balise, et le **nom** de la station une fois lu (nom du service, carte de visite ou indicatif dans l'image ; gardé 24 h pour cette fréquence et ce débit).
- **Cliquer un signal** : le MiniTiouner s'y accorde (même OL, même entrée).
- **Veille** : les canaux occupés tour à tour, tenus tant qu'ils sont accrochés, un enregistrement au nom de la station en option ; tout accord à la main l'arrête.
- **Dans le cockpit QO-100 aussi** : *Bande large DATV — station sol, par Internet*, décoché d'origine, y montre la même bande sous le transpondeur étroit ; un clic sur un signal y accorde le récepteur DATV. La connexion ne reste ouverte que tant qu'un des deux onglets la montre.

<p align="center"><img src="images/pc-datv-bande-large.webp" width="100%" alt="Le spectre de la bande large reçu de la station sol (réception réelle, 23 octobre 2026) : la balise A71A à 1,5 MS/s, PA0WCH à 333 kS/s nommé au-dessus de son signal, le repère RX du récepteur, la ligne « balise − 1 dB »"></p>

### Son, salon et accord par UDP

Chacun coupé tant qu'on ne le choisit pas dans la carte Réception.

- **Vumètre et volume** : le son est décodé par SatMe (ffmpeg en PCM stéréo 48 kHz) au lieu de ffplay, joué par la sortie son du PC avec le volume choisi (0 à 200 %, appliqué tout de suite), son niveau gauche et droite (« M » quand les deux voies sont identiques) avec la crête tenue 1,5 s. **Écouter ici** › *Son DATV* l'envoie aussi au navigateur, avec son propre volume.
- **Salon de la bande large** : le salon public de la station sol (BATC), en lecture seule tant qu'on n'écrit pas : rien n'y est envoyé sans un clic sur **Envoyer**, le pseudo est l'indicatif de la station (posé juste avant le premier message), un message toutes les 3 s au plus ; sans indicatif, il reste en lecture seule. **Rapport** écrit dans la case le rapport de réception d'usage (« SigReport: indicatif - D marge (MER dB) - débit Ks - fréquence »), à relire puis envoyer. Aucun service de rapports de réception DATV en ligne n'est joignable aujourd'hui : la page le dit (« service indisponible ») et rien d'autre n'est envoyé.
- **Accord par UDP** : un autre programme demande un accord par un datagramme texte, sur le port choisi (9997 d'origine), de ce PC seulement — ou du réseau local, si SatMe-PC est lancé avec `--reseau` et que la case est cochée. Réception seulement : rien n'est jamais répondu ni émis, l'alimentation du LNB reste comme elle est, une demande toutes les 2 s au plus (10 par minute), la même redemandée pendant la réception ne relance rien. Deux formats :

  ```
  FREQ=10494750;SR=1500;OL=9750000;ENTREE=A
  [GlobalMsg],Freq=10491500,Offset=9750000,Srate=1500,FPlug=A
  ```

  Le premier : la fréquence ciel en kHz (ou en MHz avec un point : `FREQ=10494.75`), le débit en kS/s, l'oscillateur du LNB en kHz et l'entrée A ou B en option (gardés sinon). Le second, courant parmi les outils DATV : Freq en kHz, Offset l'oscillateur en kHz (0 : Freq est déjà la fréquence intermédiaire), Srate en kS/s ; ses autres clés (alimentation, 22 kHz…) sont ignorées. Par exemple : `echo -n 'FREQ=10494750;SR=333' | nc -u -w1 127.0.0.1 9997`.

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

Le sous-onglet **Émission** de l'onglet DATV envoie un signal DVB-S2 (ou DVB-S) au transpondeur large bande de QO-100 avec un **ADALM-PLUTO** ou un **bladeRF 2.0** (un **HackRF One** seulement pour les essais en intérieur et sur charge). SatMe fabrique lui-même le DVB-S2 (EN 302 307-1 : QPSK à tous les taux, 8PSK de 3/5 à 9/10, trames normales ou courtes, pilotes, roll-off 0,35 / 0,25 / 0,20) ; **ffmpeg** fait de l'image un flux de transport exactement au débit que porte le MODCOD (`-muxrate`, complété de paquets nuls), en H.264 ou H.265, le nom du service et du fournisseur étant l'indicatif de la station.

- **Norme** : DVB-S2, ou **DVB-S** (EN 300 421 : dispersion d'énergie, RS(204,188), entrelaceur I=12, code K=7 poinçonné de 1/2 à 7/8, QPSK) pour les récepteurs qui ne lisent que lui. Même carte de visite ; l'adaptation du MODCOD reste en DVB-S2.
- **L'image** : la mire SatMe avec l'indicatif, une **mire animée** (une image qui bouge sur les barres, un bip chaque seconde), une image fixe, un **fichier vidéo en boucle**, ou une caméra (`/dev/video…`, seulement listée tant qu'aucune émission ne démarre).
- **Parcourir…** : les dossiers du PC (dossier personnel, supports amovibles, `datv-sources` de SatMe), en lecture seule, avec vignettes ; les fichiers envoyés depuis le navigateur (500 Mo au plus) sont gardés dans `datv-sources`. **Prévisualiser** fait une image avec la même commande, rien n'est émis.
- **Incrustation** (coupée d'origine) : indicatif, locator, une ligne libre, l'heure UTC et la marque SatMe, dans un coin ou un bandeau, sur l'image.
- **Émis | Reçu** : pendant l'émission, l'image émise s'affiche à côté de l'image reçue de QO-100 (l'une sous l'autre sur un écran étroit), quel que soit le sous-onglet ; sous la première le MODCOD, la fréquence et le débit, sous la seconde l'accroche, la MER et si c'est notre signal. L'image émise est décodée par SatMe-PC, à basse priorité, depuis une copie du flux envoyé au modulateur (5 petites images par seconde, seulement quand une page la regarde) : rien de plus n'est émis, le flux émis n'attend jamais. Avec l'heure UTC incrustée, l'écart entre les deux horloges donne le retard aller-retour. Le bouton du titre les sépare ou les remet. Réception seule : rien ne change.
- **Agrandir** : ⛶ en bas à droite de chaque image, ou un double-clic ; ⛶, un double-clic ou Échap pour revenir (Échap arrête aussi toute émission, comme partout sur la page). Sans émission, plein écran du navigateur ; pendant l'émission, l'image remplit la fenêtre avec un bouton **ARRÊT** (le plein écran du navigateur garderait Échap pour lui).
- **Adapter le MODCOD à la réception** (coupé d'origine) : tant que notre propre signal revient (service ou carte de visite à notre indicatif), SatMe choisit le MODCOD le plus efficace de la plage permise dont le seuil reste 2 dB sous la MER — il descend vite quand la marge tombe ou que des paquets se perdent, remonte lentement après 20 s de marge en trop, attend 8 s après chaque changement que le récepteur raccroche, prend le plus robuste si la boucle est perdue. Fréquence, débit de symboles et puissance ne sont jamais touchés ; une puissance plus basse est suggérée sur QO-100, jamais appliquée.

<p align="center"><img src="images/pc-datv-emis-recu.webp" width="80%" alt="Émis | Reçu (états simulés pour la capture) : à gauche la mire émise, QPSK 2/3, 2405,250 MHz ; à droite l'image reçue, accrochée, MER 7,4 dB, « notre signal »"></p>

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

<p align="center"><img src="images/pc-sstv.webp" width="100%" alt="SSTV, sous-onglet Images : les images de RS-44, d'UmKA-1 et de l'ISS, chacune avec son satellite, son mode et son heure"></p>

**Réception**

- **Source** : *auto* (la clé SDR si elle joue, sinon la carte son), la carte son, ou la SDR écoutée directement.
- **Mode** : *Auto (VIS)* ou imposé ; le **décodage continu** démarre une image sur ses tops de synchro même sans en-tête (mode reconnu à la durée des lignes) ; **Caler maintenant** accroche l'image sur la prochaine impulsion de synchro.
- **Calage automatique** (coupé d'origine) : l'écart des tons mesuré sur l'en-tête (lu sur ±1 kHz) et les tops de synchro, et la réception recalée — la clé SDR, ou le poste quand la boucle CAT le pilote — de 20 Hz par seconde au plus, jusqu'à ±2 kHz.
- Un spectre avec les tons marqués (1200 synchro, 1500 noir, 1900 VIS, 2300 blanc), le niveau, un conseil quand la source se tait.
- **Images** : chaque image gardée dès un huitième de ses lignes, avec son propre son ; filtrées par satellite, ouvertes dans une visionneuse, redécodées, faites en GIF, supprimées. Rangées **par diapo** (ou par réception) — voir [Images par diapo, note et horaire de la série](#images-par-diapo-note-et-horaire-de-la-série).
- **Mire** : la mire SSTV (comme sur le téléphone), jouée sur une sortie au choix ou écrite en WAV.
- **Campagnes** : une série ARISS, ou un satellite qui envoie des images quelques jours, reçue toute seule passage après passage — voir [Campagnes SSTV : l'auto-pilote](#campagnes-sstv--lauto-pilote) ; les séries annoncées en ligne arrivent dans [Activités à venir](#activités-à-venir--les-sources-en-ligne). La SSTV de l'ISS est sur **437,550 MHz FM en Robot 36** depuis les séries de 2026.

**Émission**, avec l'**ADALM-PLUTO** ou le **bladeRF 2.0** :

- L'image : la mire SatMe avec l'indicatif, la planche SSTV, ou un fichier image ; tous les modes, envoyée 1 à 3 fois, l'**indicatif écrit en haut** de l'image, et en CW à la fin si on le demande.
- **Modulation** : FM étroite (±2,5 ou ±5 kHz, préaccentuation en option), BLU haute ou basse.
- **Les règles, dans le code** : **ÉMETTRE** puis une confirmation ; un indicatif ; toute la bande occupée dans une bande amateur (144–146, 430–440, 1240–1300, 2300–2450 MHz) ; les sous-bandes satellites (145,8–146, 435–438, 1260–1270 MHz) seulement en cochant *Vers un satellite* ; **jamais 145,800 MHz** (ISS) ; la bande étroite de QO-100 en BLU haute seulement, dans son segment numérique (2,7 kHz) ou mixte — jamais l'urgence ni les balises, et jamais plus fort que la balise (une boucle fermée sur la balise quand SatMe entend sa propre descente). 15 min au plus ; arrêt tout seul une fois l'image partie, ou quand la page se tait. *Test sur charge* et *Essai en intérieur* comme pour la DATV (−40 dB, 5 min en intérieur).
- **Régler la réception** met le récepteur de l'onglet SDR sur notre propre émission (fréquence, NFM ou BLU) pour la voir revenir.

### Images par diapo, note et horaire de la série

Une série ARISS envoie ses 12 diapos en boucle pendant des jours : une semaine de passages donne des dizaines de copies de chacune. Le sous-onglet **Images** les range (**par diapo**, ou **par réception** comme avant ; aussi sur le téléphone et sur la page qu'il sert) :

- **Les copies d'une même diapo ensemble** : « Diapo 7/12 · 5 réceptions », la meilleure en tête, un clic déplie le groupe. Les images sont comparées en petit (32×48 cases de luminosité et de couleur), sur les lignes saines des deux, le ciel sombre commun à toutes les diapos d'une série laissé de côté, avec un léger décalage sur le côté et n'importe quel décalage vertical (une image commencée en retard, sans son en-tête, est le bas de la diapo en haut) ; une image coupée tôt est comparée sur ce qu'elle a. Sur les 43 images de la série 34 (23–24 octobre 2026) : aucune diapo mêlée à une autre ; seules quatre images presque entièrement détruites restent seules.
- **Une note de 0 à 100** sur chaque image (pastille, étoiles dans l'en-tête) : 100, moins la part de lignes manquantes (image arrêtée tôt), moins 1,1 × la part de lignes abîmées (points qui ne ressemblent pas à leurs voisins — le moucheté vert d'un signal FM faible en Robot 36 —, bandes vertes, lignes sans rapport avec leurs voisines, traînées horizontales), moins 4 par glissement de synchro (20 au plus) ; dans un groupe, une image commencée en retard perd en plus la part du haut qui lui manque. Un fond étoilé (points d'un pixel) coûte quelques points à une image propre.
- **Le numéro de la diapo** : lu au bas des diapos ARISS (« SLIDE n/12 » de la série 34, par comparaison avec les chiffres vus sur ses vraies images — pas d'OCR, les lettres font 9 pixels de haut) ; 34 images lues justes sur 43, aucune fausse, les plus abîmées non lues ; les numéros 5 et 6 n'ont pas encore de modèle (pas reçus) et ne sont jamais lus, comme un numéro qui ne ressemble clairement à aucun : la main ou l'horaire le donnent. Une autre série (autre bas de diapo) : rien n'est lu. Le numéro donné à la main (visionneuse : *Diapo*, ou la carte de la série) vaut pour tout le groupe.
- **À la main** : la visionneuse range l'image *avec* un autre groupe, *à part*, ou la rend au *tri automatique* ; ce choix et le numéro sont gardés dans le fichier compagnon de l'image (lignes `slide=` et `group=`, que les versions plus anciennes ignorent).

**La carte « Diapos de la série »** (pendant une série annoncée dans [Activités à venir](#activités-à-venir--les-sources-en-ligne), ou une campagne de l'Agenda, et trois jours après) :

- les diapos reçues (vert, ≈ quand le numéro est estimé) et celles qui manquent (rouge) ;
- **l'horaire** : les diapos passent dans l'ordre, une toutes les 36 s + 2 min (156 s annoncées) ; chaque image numérotée est un repère (son heure de réception moins la durée de l'image), la période ajustée par moindres carrés, l'écart moyen affiché. Un repère loin de la droite (plus de 40 s) est une relance de la boucle à bord : l'horaire repart de là (la période reste ajustée sur tout), un repère isolé faux est écarté. Série 34 : **158,97 s ± 0,03**, 35 repères, écart moyen 7,4 s, deux relances vues (23/10 vers 07:45 UTC et 24/10 vers 00:36 UTC) ;
- **numéroter une image** choisie dans la liste (heure, passage de l'ISS et sa place dans le passage) : c'est le repère quand rien n'est lu ;
- **le tableau des passages à venir** jusqu'à la fin de la série : pour chacun, les diapos attendues — en entier quand elles sont envoyées pendant que l'ISS est au-dessus de 5° (ou de l'élévation minimale des réglages, si plus haute), entre parenthèses celles coupées par l'AOS ou le LOS —, celles qui manquent en rouge, et l'incertitude (±), qui grandit avec le temps ; puis, pour chaque diapo manquante, ses prochains bons passages.

C'est **une estimation** : ARISS fait des pauses, relance la boucle ou change l'ordre sans prévenir. Une nouvelle image numérotée (lue ou à la main) remet l'horaire à jour.

**La planche** : *Remplir* et le choix du nombre d'images prennent une image par groupe, la mieux notée.

<p align="center"><img src="images/pc-sstv-diapos.webp" width="100%" alt="SSTV, sous-onglet Images : la carte Diapos de la série (ARISS série 34, diapos reçues, horaire ajusté, passages à venir et diapos attendues) et la galerie rangée par diapo, chaque image notée"></p>

### Options SSTV

Dans **SSTV › Réglages › Options SSTV** (le téléphone : SSTV, onglet *Direct*), tout en réception seule.

- **« Diapo manquante attendue »** : d'après l'horaire de la série (ci-dessus), avant chaque passage qui envoie **en entier** une diapo encore manquante — ou reçue seulement avec une note sous un seuil (60 d'origine, 0 pour les manquantes seulement) —, une alerte par passage, *N* minutes avant la diapo (15 d'origine) : « Diapo 5 attendue à 04:32 UTC (≈ 06:32 heure locale), 49° — passage ISS (74° max) ». Sur SatMe-PC avec les [alertes des activités](#activités-à-venir-les-sources-en-ligne) (bulle, « Dernières alertes », et à voix haute quand les annonces parlent), sur le téléphone en notification sur le canal des activités. Suit l'interrupteur d'« Activités à venir » ; un passage dont l'estimation est plus lâche qu'un tiers de période, ou déjà commencé, n'est jamais annoncé.
- **Qualité de réception** pendant l'image, sous l'image en direct : le niveau du son (efficace et crête, dBFS) avec un avertissement quand il sature ou est trop faible ; la synchro de chaque ligne (vert nette, ambre bruitée, rouge perdue) et le S/B mesuré dans les tops de synchro (dB dans 3 kHz, ajusté sur du bruit blanc de 40 à 10 dB : bruit ≈ 350·10^(−S/B/20) Hz ; sous 10 dB les tops se noient) ; **la note vers laquelle va l'image** : chaque ligne abîmée de 0 à 1 (synchro perdue ou bruit au-delà de 130 Hz : 1, sinon (bruit − 10)/120), 100 − 110 × leur part − la part manquante. Sur les 49 images ISS des séries 33 et 34, elle suit la note du classement à 4 points près (écart quadratique). Mesuré à côté du décodeur : l'image est la même avec ou sans (testé).
- **« Enregistrer le son de chaque passage SSTV »** (SatMe-PC, coupé d'origine) : chaque passage du satellite de la série, quand il est suivi et la SSTV décodée, enregistré de l'AOS à 30 s après le LOS. *Pourquoi des WAV de l'ISS étaient déjà dans `enregistrements/`* : chaque campagne d'Agenda › SSTV a sa case « Enregistrer », cochée d'origine — l'auto-pilote enregistre chaque passage qu'il prend (son historique nomme le fichier). Le fichier compagnon de chaque image en direct nomme son enregistrement (`recording=`, désormais aussi sur le téléphone : le MP3 de l'enregistreur), pour la redécoder ; chaque image garde aussi son propre son.
- **Rétention** des enregistrements SSTV : *N* jours et/ou une taille, **les deux à 0 d'origine : rien n'est jamais supprimé** tant que tu ne les règles pas. Seul l'enregistrement d'un passage SSTV est candidat (nommé par une image, l'historique de l'auto-pilote, ou enregistré par l'option) ; jamais celui d'une image utilisée dans une planche ou marquée **« à garder »** (visionneuse), jamais celui que nomme le journal des passages (sauf case décochée), jamais celui de moins d'un jour. Appliquée chaque heure (PC) ou avec la vérification des activités (téléphone), ou à la main après la liste ; chaque suppression est notée dans `enregistrements/retention-sons.log` (téléphone : `recordings/retention-sons.log`).
- **Image QSL** (visionneuse : 🪪 QSL) : l'image doublée, un bandeau dessous ou incrusté en bas — indicatif · locator (ceux de l'image, sinon Réglages › Ma station), date et heure UTC, satellite, fréquence et mode (de l'activité ou de la campagne d'alors), élévation à la réception et maximum du passage, série et diapo n/12, « reçu avec SatMe » au choix ; PNG ou JPEG. Un nouveau fichier : l'originale n'est jamais touchée (testé). PC : téléchargement ; téléphone : partage.
- **Pack pour le diplôme ARISS** (visionneuse : 🏅 Pack ARISS, ou *Pack ARISS de la série* : la meilleure copie de chaque diapo) : un zip avec `original/` (les images octet pour octet), `qsl/`, `LISEZMOI.txt` et `resume.html` : indicatif, locator et région, date et heure UTC de chaque capture, satellite, fréquence, mode, série, diapo, le passage (AOS, LOS, élévations), la note, le matériel du plan « Montage » qui reçoit (sinon le poste choisi), et les étapes. **Rien n'est envoyé.**

Le diplôme ARISS SSTV, tel que lu le 24 octobre 2026 (vérifier la page de la série en cours, il change) : recevoir et décoder **au moins une image** de la session, partielle acceptée si reconnaissable ([diplôme ARISS SSTV, PZK](https://ariss.pzk.org.pl/sstv/)) ; la déposer par le [formulaire de la galerie ARISS SSTV](https://ariss-usa.org/ARISS_SSTV/submit.php) — nom, indicatif (SWL sans), e-mail (le diplôme y est envoyé), région, locator, campagne, date et heure UTC de la capture ; **images « propres » seulement, sans indicatif ajouté ni montage** (donc l'originale, pas la QSL) ; png, jpg ou gif, 800 ko et 800×800 au plus ; puis, page suivante, demander le diplôme personnalisé ; demandes acceptées « en général 72 heures » après l'événement ; le diplôme électronique (PNG) arrive par e-mail sous trois semaines ([événements SSTV ARISS](https://www.ariss.org/upcoming-sstv-events.html)).

## FT8 / FT4

Un onglet pensé pour voir la bande, pas seulement la lire :

- **L'anneau du créneau** : les 15 s en cercle, le compte à rebours, en cyan si le créneau est pair, en orange s'il est impair ; à côté, **l'horloge** lue d'après le DT des stations.
- **La cascade qui parle** : 46 s, les limites des créneaux et leur heure. **Chaque indicatif décodé est écrit avec son drapeau, à sa fréquence, dans son créneau.** Une **place libre** pour émettre est trouvée toute seule (le coin le plus calme entre 500 et 2 500 Hz, loin des stations décodées ; sur QO-100, seulement dans les segments numériques).
- **Le radar** : chaque station est un point à son azimut et à sa distance depuis ton QTH, qui pâlit avec le temps et pulse quand elle vient d'être entendue. Vert pour un CQ, ★ pour un pays nouveau pour ton carnet, rose si elle t'appelle.
- **Qui appeler maintenant** : les stations des 5 dernières minutes, classées par ce qu'elles apporteraient (nouveau pays, nouveau carré, distance, force, CQ, rareté) ; un clic prépare le contact dans le carnet.
- Des **marques** pareilles partout (liste, messages, cascade) : m'appelle, guettée, nouveau pays, nouveau carré, déjà contactée. **Stations guettées** : les indicatifs à surveiller. Un **bandeau** quand la réception va mal (pas de son, silence numérique, rien de décodé depuis 40 créneaux).
- **💬 Messages**, comme une messagerie : les conversations entre deux stations (drapeaux, dernier message, ✓ quand le QSO est bouclé), chacune en bulles, ou tout au fil de l'eau. Chaque message dit ce qu'il est : 📣 appel, 📍 carré, 📶 report, ✅ report reçu, 🤝 RR73 / 73.

**Le décodeur**, mesuré sur un corpus de créneaux entiers (stations faibles jusqu'à −22 dB, chevauchements, pentes Doppler, FT4) : la **dérive Doppler** connue du satellite retirée avant le décodage ; chaque candidat lu dans son propre canal étroit (début au 1/32 de symbole, fréquence au 1/16 de ton) ; les signaux décodés **soustraits** et le reste fouillé à nouveau ; la dérive résiduelle cherchée, et celle d'un LNB qui bouge (jusqu'à ±15 Hz/s, voir [Un LNB qui dérive](#un-lnb-qui-dérive)) ; les **indicatifs hachés** retrouvés (`<INDICATIF>` quand il est connu) ; pendant un QSO, le **décodage par attente** des réponses possibles du correspondant (marqué *ap*). SatMe-PC creuse plus quand le satellite est bas ou qu'il reste du temps dans le créneau. L'écoute FT8 (en marche ou non) et le mode FT8 / FT4 sont gardés d'un redémarrage à l'autre.

**PSK Reporter** (Réglages, coupé d'origine) : les stations FT8 / FT4 entendues envoyées toutes les 5 minutes avec leur carré, la fréquence et le satellite — seulement quand la fréquence est sûre (poste en CAT).

<p align="center">
<img src="images/pc-ft8.webp" width="49%" alt="FT8 sur QO-100 : l'anneau du créneau, la cascade avec les indicatifs à leur fréquence, le radar des stations, qui appeler maintenant">
<img src="images/pc-ft8-messages.webp" width="49%" alt="FT8, les messages : les conversations entre deux stations, et le fil au fil de l'eau en bulles">
</p>

## Télémesure

L'onglet **Télémesure** (espace *Modes*) décode ce que les satellites envoient d'eux-mêmes : paquets FSK / GMSK de 1200 à 9600 bit/s, images SSDV, télémesure BPSK au format FUNcube. Trois sous-onglets : **Paquets**, **Images SSDV**, **BPSK**. Tout est coupé d'origine ; réception seulement. Les trames reçues sont gardées sur le PC (`telemesure/`) ; rien ne part sur Internet sans la case *Partager avec SatNOGS DB*.

### Paquets FSK · GMSK

Le sous-onglet **Paquets** décode les cubesats en FSK ou GMSK de 1200 à 9600 bit/s, dont le « G3RUH » à 9600 bit/s (embrouilleur 1 + x¹² + x¹⁷, NRZI, trames HDLC avec CRC-16, le plus souvent en AX.25).

- Coché (coupé d'origine), il prend les **échantillons I/Q bruts** du récepteur SDR principal hors QO-100 (pas le son) : discriminateur FM, rattrapage de fréquence résiduel jusqu'à ±12 kHz autour du Doppler suivi, récupération d'horloge, puis deux lectures des bits (avec et sans embrouilleur).
- Le **débit** vient de l'émetteur SatNOGS DB du satellite suivi (FSK, GFSK, GMSK, MSK et sa vitesse) ou se choisit à la main. Les trames AX.100 « mode 5 » (ASM + Golay) et les protocoles propres à un satellite ne sont pas lus.
- Chaque trame : l'heure, l'en-tête AX.25 et son texte (ou les octets), la fréquence reçue et celle d'émission (Doppler retiré), l'écart rattrapé. Gardées par passage dans `telemesure/paquets/` (une ligne JSON par trame, et un fichier KISS si coché).
- **Essai** : une salve fabriquée (télémesure et paquets SSDV) passe par un décodeur à part. Le **générateur d'essai** du récepteur SDR a aussi un signal « Paquets 9600 » : salves de télémesure et image SSDV, de bout en bout.
- **Partager avec SatNOGS DB** (coupé d'origine) : chaque trame reçue d'un satellite suivi est envoyée par le protocole public SiDS, avec l'indicatif de la station, l'heure UTC et la position arrondie à 0,1° (≈ 10 km). Les trames de l'essai, du générateur et des enregistrements rejoués ne partent jamais.

<p align="center"><img src="images/pc-telemesure-paquets.png" width="100%" alt="Télémesure, sous-onglet Paquets après un essai : des trames AX.25 à 9600 bit/s (G3RUH) marquées essai, et l'image SSDV reconstituée paquet par paquet"></p>

### Images SSDV

Le sous-onglet **Images SSDV** reconstitue les images SSDV (JPEG en paquets de 256 octets, CRC-32 et Reed-Solomon) trouvées dans les trames décodées, au fil des paquets ; les blocs des paquets manquants restent gris. Les images sont gardées dans `telemesure/ssdv/`.

### BPSK : FUNcube et la balise QO-100

Le sous-onglet **BPSK** décode la télémesure au format FUNcube : AO-73 (FUNcube-1) et ceux qui le reprennent, BPSK à 1200 bit/s, les mêmes trames codées que la balise QO-100 (256 octets toutes les 5 s).

- Coché (*Décoder la télémesure 1200 bit/s*, coupé d'origine), il écoute le **récepteur SDR principal** hors QO-100 : l'accorder en BLU (USB) sur la descente de télémesure (AO-73 : 145,935 MHz), le Doppler suivi par SatMe ; la porteuse est cherchée dans toute la bande audio puis tenue par une boucle de Costas.
- Chaque trame : satellite et type (orbite entière, haute résolution, message), les **valeurs temps réel** lues d'après le document de descente publié par AMSAT-UK et ses équations (tensions et courants des panneaux et de la batterie, températures, radio, ampli, antennes, état du logiciel), le message s'il y en a un, ou les octets bruts.
- **Essai** : un signal fabriqué passe par le même décodeur (marqué *essai*). L'envoi à l'entrepôt de données FUNcube n'est pas fait.
- **Balise QO-100** : la balise médiane à 400 bit/s se décode dans le cockpit QO-100 (sous-onglet *Balise*) ; un lien y mène.

<p align="center"><img src="images/pc-telemesure-funcube.png" width="50%" alt="Télémesure BPSK après un essai : une trame FUNcube-1 décodée, ses valeurs d'énergie, de panneaux, de radio, d'ampli et d'antennes"></p>

## Phonie → texte

Ce que la station entend en BLU ou en FM — la carte son, le son de la clé SDR, ou celui d'un flux DATV — écrit passage par passage : la BLU de QO-100 d'abord. Une fenêtre **Phonie → texte** dans les onglets **SDR**, **QO-100** et **Carnet** montre chaque passage en bulle : heure, fréquence (la descente de QO-100 quand on y est), langue, texte (plus pâle quand le moteur est moins sûr), et sa traduction si on la demande. Les indicatifs (écrits ou épelés : « Sierra Papa five Alfa Bravo Charlie », « Fox quatre Italie Oscar Zoulou », en anglais, français, allemand, espagnol, italien, polonais), les carrés (« Juliette Novembre un huit ») et les reports (« cinq neuf », « five nine ») sont mis en couleur ; la puce d'un indicatif prépare le contact dans le carnet, avec le carré et le report entendus.

- **Coupé par défaut.** Rien n'écoute et rien ne démarre tant que la case de la fenêtre n'est pas cochée.
- **Le moteur** est faster-whisper, en Python, dans un processus à part sur ce PC : pendant l'écoute rien ne sort du PC. **Réglages › Phonie → texte › Installer le moteur** crée un environnement Python dans le dossier de SatMe (`stt/`), y installe faster-whisper depuis PyPI (environ 150 Mo ; avec une carte NVIDIA, environ 1,3 Go de plus pour cuBLAS et cuDNN) et télécharge le modèle choisi (large-v3 3,1 Go, turbo 1,6 Go, medium 1,5 Go, small 0,5 Go). Rien n'est téléchargé sans ce bouton. Il faut Python 3.9 ou plus récent (Debian/Ubuntu : `python3-venv` ; Windows : python.org, « Add python.exe to PATH »). On peut aussi donner un autre interpréteur où faster-whisper est installé.
- Une carte NVIDIA sert quand elle répond, sinon le processeur (y choisir *small* ou *medium*). Sur une RTX 3060 avec large-v3, 20 s de parole demandent environ 1,3 s.
- **Traduction, sans Internet** (*Traduire en*, français d'origine) : chaque passage traduit sur le PC avec des paires de langues OPUS-MT libres (CC BY 4.0), en passant par l'anglais quand il n'y a pas de paire directe. **Installer la traduction** récupère les langues cochées (polonais, allemand, anglais, espagnol, italien, néerlandais, portugais, russe, ukrainien, tchèque…) dans `stt/traduction`.
- La voix est découpée entre les silences (de 1 à 25 s, le silencieux respecté) ; le bruit, les porteuses et les phrases qu'un moteur invente sur le bruit (« Merci d'avoir regardé »…) sont écartés. Quand le moteur prend du retard, le plus ancien passage en attente part, et la fenêtre dit le retard.
- Le texte de chaque jour est gardé dans `phonie/AAAA-MM-JJ.jsonl` ; ⤓ le télécharge en texte.
- **🔊 Traduction parlée** (coupée par défaut, réception seulement : la voix n'est jamais émise) : la traduction de chaque passage dite par une **voix de synthèse**, sur le haut-parleur du PC (le son reçu baissé pendant ce temps), dans le navigateur, ou les deux ; ⚙ ouvre ses réglages (voix, intonation, sortie, volume, langues étrangères seulement). Deux passages au plus en attente, aucun de plus de 30 s. **Réglages › Phonie → texte › Installer la voix** ajoute Piper (piper-tts, environ 35 Mo) au moteur et une voix par langue (environ 63 Mo chacune, `stt/voix`). Sans Piper : eSpeak NG ou speech-dispatcher, sinon la voix du navigateur. Intonation **proche** : sur le passage entendu, la hauteur de la voix (100 fois par seconde), sa moyenne et son étendue, la vitesse (syllabes par seconde) et le volume de chaque phrase ; la voix de synthèse prend la même vitesse (à peu près), sa hauteur moyenne est amenée sur celle de la personne (8 demi-tons au plus) et ses montées et descentes de phrase sont remplacées à 70 % par celles entendues (une courbe de degré 2 par phrase), par un décalage de hauteur synchrone de la période qui garde la durée et les formants. **Voix clonée** (facultatif, environ 1 Go : torch pour le processeur et le convertisseur de timbre OpenVoice V2, licence MIT) : le timbre de la personne en plus, environ 2 s de calcul par passage de 10 s.

<p align="center"><img src="images/pc-reglages-phonie.webp" width="100%" alt="Réglages, Phonie → texte : le modèle (large-v3), carte NVIDIA ou processeur, le moteur installé, la traduction sans Internet vers le français avec dix langues installées"></p>

## Le récepteur SDR

<p align="center"><img src="images/pc-sdr.png" width="100%" alt="Onglet SDR avec le générateur d'essai : 145,800 MHz, la loupe, le spectre de 176 kHz et la cascade avec une porteuse, une CW, une BLU, une FM, un appel FT8 et une image SSTV fabriqués ; à gauche l'écoute, la démodulation et le nettoyage du son"></p>

L'onglet **SDR** (espace **Station**) : l'écoute (le satellite suivi, Doppler corrigé, ou une fréquence), la démodulation (NFM, USB, LSB, AM ; largeur, silencieux, désaccentuation), le spectre et la cascade, le vernier et la loupe. Le spectre : un clic s'y accorde, la molette zoome, glisser déplace la vue dans les 176 kHz que voit le récepteur, les bords de la bande passante se tirent ; la cascade : la molette accorde ; au clavier ← → un pas, ↑ ↓ dix pas. **Réception seulement.**

- **CTCSS** : en NFM, le sous-ton entendu (les 50 tons de 67,0 à 254,1 Hz) est lu sous la voix en moins d'une seconde et affiché à côté du niveau (« CTCSS 67,0 Hz », sa déviation au survol). **Sous-ton** (carte Démodulation, *aucun* d'origine) : le silencieux sur sous-ton — le son (haut-parleur, décodeurs, phonie) ne passe que tant que ce ton est entendu. Réception seulement ; le générateur d'essai peut en mettre un sous son signal FM.

### Mémoires et plan de bandes

**Mémoires** (et **＋** pour la fréquence écoutée) : des fréquences nommées avec leur mode, leur largeur, leur sous-ton CTCSS, une **étiquette** et une couleur, rangées dans des **dossiers** (un niveau de sous-dossiers).

- Un clic sur une mémoire y accorde le récepteur : dans la vue, par le décalage ; au-delà, le récepteur y va et ne suit plus le satellite.
- Glisser-déposer d'un dossier à l'autre, renommer, supprimer (les mémoires passent au dossier parent), trier, chercher, modifier dans la ligne ; une mémoire peut être **écartée du balayage**.
- Sur le spectre, de **petits drapeaux** : clic pour s'y accorder, clic droit pour la modifier. Filtre par étiquette.
- Gardées par SatMe-PC (`memoires-sdr.json`) ; **Exporter** / **Importer** en JSON, sans doublons, d'un PC à l'autre.
- **Plan de bandes** : un liseré coloré en haut du spectre — IARU Région 1 pour 2 m, 70 cm, 23 cm, 13 cm et 3 cm, et celui de QO-100. Il se remplace bande par bande avec un fichier `plan-bandes.json` dans le dossier de SatMe-PC (le plan d'origine se télécharge depuis **Affichage** comme modèle).
- **Pile de bandes** : les boutons de bande (2 m, 70 cm, 23 cm, 13 cm, 3 cm / QO-100) gardent les trois dernières fréquences, modes et largeurs restés plus de 5 s ; appuyer encore sur la bande passe de l'une à l'autre.

### Balayage

**Balayage** (onglet SDR), réception seulement :

- **Ce qui est balayé** : les mémoires d'un dossier, une plage canal par canal (pas et mode au choix), ou une plage jugée tranche par tranche sur le spectre large.
- **S'arrêter** sur un niveau au-dessus du bruit ou sur l'ouverture du silencieux ; **repartir** quand le signal tombe, après un temps, ou à la main ; un **canal prioritaire** revu toutes les N secondes ; les canaux écartés sautés ; les signaux trouvés gardés comme mémoires.
- Les pas du balayage ne sont jamais écrits dans les réglages ; à l'arrêt, la fréquence est gardée une fois, ou celle d'avant rendue (*revenir à la fréquence d'avant*, et toujours quand rien n'a été trouvé). Refusé quand la DATV, l'ADS-B, QO-100, une sonde ou METEOR tient le récepteur ; un accord à la main l'arrête.

<p align="center">
<img src="images/pc-sdr-balayage.png" width="49%" alt="Le menu Balayage : mémoires, plage canal par canal ou par le spectre, pas et mode, seuil, reprise, canal prioritaire, et à l'arrêt rester sur le signal ou revenir">
<img src="images/pc-sdr-mesures.png" width="49%" alt="Mesures du récepteur, la balise QO-100 sur 24 h : son niveau au-dessus du bruit et l'erreur du LNB, du soir au soir">
</p>

### Affichage

Le bouton **Affichage** règle le spectre et la cascade, gardé dans ce navigateur (**défaut** revient à l'origine) :

- **Moyenne** sur 2 à 16 trames ; **crêtes tenues** (fixes ou descendant de 1, 3 ou 10 dB/s) et **minimum tenu**, effacés par ⟲ ;
- **Niveaux** : *auto* (le bas sur le bruit, le haut sur le plus fort signal), ou une référence et une plage (30 à 120 dB) ;
- **Cascade** : sept palettes, contraste, luminosité, noir posé sur le bruit, vitesse ;
- **Accord** : molette *à inertie* (un tour rapide lance l'accord, qui ralentit), pas en BLU / CW (1 à 100 Hz) et en FM / AM (100 Hz à 12,5 kHz) ;
- **Repères** : le plan de bandes, les drapeaux des mémoires.

### Nettoyage du son

Sous la démodulation (onglet SDR et cockpit QO-100), réglé pour chaque mode (USB, LSB, NFM, AM) et gardé ; **tout est coupé au départ**.

- **NB** (antiparasite) : les claquements très courts (alimentation à découpage, alimentation du LNB) repérés sur les échantillons I/Q bruts et effacés avant tout filtre ; seuil et largeur dans *Réglages fins du nettoyage*.
- **Notch auto** : jusqu'à trois porteuses fixes qui sifflent dans la voix, creusées ; en CW une note doit tenir 2 s avant d'être creusée, la manipulation passe.
- **Notch** : un creux étroit à la fréquence audio donnée ; **Maj + clic** sur le spectre ou la loupe le pose sur une porteuse.
- **Réduction de bruit** légère, moyenne ou forte : le souffle baisse entre les mots et sous la voix (29 ms de retard, en pause silencieux fermé).
- **CAG** du son en BLU et AM : lente, moyenne ou rapide.

### I/Q : remonter le temps, enregistrer, rejouer

Le bouton **I/Q** garde les échantillons bruts du récepteur (1,0584 Méch/s : 127 Mo par minute depuis une clé RTL, 254 Mo depuis le Pluto, le bladeRF ou le générateur), au format **SigMF** (`.sigmf-data` et `.sigmf-meta` : fréquence, date, satellite, mode).

- **Remonter le temps** (coupé d'origine) : les 1 à 10 dernières minutes gardées en permanence dans un fichier de taille fixe sur le disque ; **Garder les dernières minutes** en fait un enregistrement — le signal entendu trop tard n'est pas perdu.
- **● Enregistrer** : de 1 à 60 minutes au plus.
- **Rejouer** : le *Récepteur* **Fichier I/Q** prend l'enregistrement comme récepteur ; spectre, démodulation et décodeurs (SSTV, CW, FT8, phonie) travaillent dessus. Pause, position, vitesse ×1 / ×2 / ×4, en boucle, et *tranches FT8* (repartir calé sur les créneaux de 15 s). Des fichiers bruts (`.cu8`, `.cs8`, `.cs16`, `.cf32`) déposés dans le dossier se rejouent aussi.
- Ce qui est décodé d'un enregistrement est marqué « relecture » (daté de l'enregistrement), jamais pris pour une vraie réception : ni journal, ni planche, ni PSK Reporter, ni SatNOGS, ni « nouvelle image » ; les trames APRS sont comptées, jamais gardées, et aucun enregistrement automatique de passage ne démarre.

### Générateur d'essai

Le *Récepteur* **Générateur d'essai** : un faux récepteur, sans antenne ni matériel — du bruit (fort, moyen, faible) et des signaux autour de la fréquence écoutée, chacun avec son décalage et son S/B : porteuse, CW manipulée (texte et vitesse au choix), BLU (voix de synthèse), ton FM (avec un sous-ton CTCSS au choix), appel FT8 dans les créneaux pairs, images SSTV en FM étroite, PSK31, RTTY, « Paquets 9600 » (télémesure et image SSDV). **Écouter** règle le mode et le décalage pour l'entendre. Rien n'est émis ; les images SSTV s'affichent sans être gardées, rien ne part à PSK Reporter.

### Mesures

Le bouton **Mesures** (onglets SDR et QO-100), réception seulement :

- **Niveau dans le temps** : le canal écouté, un **repère** posé à quelques kHz de l'écoute (il suit le Doppler), le plancher de bruit, ou la balise QO-100 au-dessus du bruit ; sur 10 min, 1 h ou 24 h, en **CSV** (moyenne, minimum, maximum, UTC).
- **Bruit solaire** (facteur Y) : viser le **ciel froid**, *Mesurer*, puis le **Soleil**, *Mesurer* ; le rapport Soleil / ciel froid face à l'attendu, calculé d'après le diamètre et le rendement de la parabole, la T<sub>sys</sub> et le flux solaire du jour (SFU à 10,7 cm, tapé à la main : rien n'est pris sur Internet). **Balayer** : une grille autour du Soleil par le rotor (rotctld ou simulé), après confirmation, dans ses butées ; « Arrêter » stoppe le rotor.
- **Balise QO-100 · 24 h** : un point par minute tant que la balise est suivie — son niveau au-dessus du bruit et l'erreur du LNB ; la dérive de la nuit au jour.

## Matériel SDR

Le *Récepteur* de l'onglet **SDR** choisit le récepteur ; tout marche pareil avec chacun : Doppler, QO-100 (derrière le LNB, FI vers 739 MHz, ou derrière un convertisseur), le cockpit, la cascade et le panorama, le classement des signaux, le son pour les décodeurs (FT8, SSTV, APRS, phonie, LEILA), METEOR, les radiosondes. L'**accord fin** comme sur le téléphone : le vernier (N Hz par cm, avec élan), la loupe autour de la fréquence écoutée, le calage sur la voix en BLU (Réglages › Accord fin). La BLU et l'AM ont une CAG à maintien.

| Matériel | Liaison | Reçoit | Émet |
|---|---|---|---|
| Clé **RTL-SDR** | rtl_tcp, sur ce PC (démarré pour vous) ou sur le réseau | environ 24 MHz – 1,7 GHz | — |
| **ADALM-PLUTO** | réseau (câble USB : 192.168.2.1, port 30431 ; ou Wi-Fi / Ethernet) | 70 MHz – 6 GHz | DATV, SSTV |
| **bladeRF 2.0** micro | USB 3, par bladeRF-cli (Linux) | 70 MHz – 6 GHz | DATV, SSTV |
| **HackRF One** | USB, par hackrf_transfer | — | essais DATV seulement (intérieur, charge) |
| **MiniTiouner** | USB, par LongMynd (ou un SatMe-Node) | DVB-S / S2 | — |
| **IC-9700 FI** | le son du poste réglé en FI (réseau ou carte son USB) | la FI de 12 kHz, ±3,6 ou ±7,5 kHz | — |

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

## Avions (ADS-B) et carte

L'onglet **Avions** reçoit l'**ADS-B** à 1090 MHz (2 MS/s), coupé tant qu'on ne clique pas **Recevoir**. Réception seulement.

- **Récepteur** : la clé de l'onglet SDR (prise seulement sur **Prendre la clé**, l'onglet SDR ne démarre alors plus sur elle), une **deuxième clé** (son propre rtl_tcp, `-d` index ou numéro de série, sur son port), ou l'**ADALM-PLUTO** (jamais pris à la DATV ni à l'émission). Gain, *réparer 1 bit faux*.
- **La liste des avions** : OACI, indicatif, altitude, vitesse, route, position, distance et azimut, vu il y a, messages, RSSI.
- **Sortie SBS-1** (lignes texte « MSG,… ») sur 127.0.0.1:30003, coupée d'origine, pour d'autres programmes de ce PC.
- **Diffusion par avions** (prévision, rien n'est émis) : un correspondant par son indicatif ou son locator, une bande (10 GHz, 13 cm, 23 cm, 2 m), un rayon, un horizon en minutes, les hauteurs des deux stations et une altitude minimale. Chaque avion est prolongé en ligne droite : la **fenêtre** est le temps qu'il passe dans le volume commun visible des deux stations (horizon radio, terre aux 4/3), avec sa durée, son altitude, les élévations et le Doppler. Un message quand une fenêtre s'ouvre.

<p align="center"><img src="images/pc-avions.png" width="100%" alt="Onglet Avions (avions simulés pour la capture) : les avions avec indicatif, altitude, vitesse, route, distance et azimut ; à gauche une fenêtre de diffusion par avions ouverte vers JO22 sur 10 GHz"></p>

L'onglet **Carte** : la carte commune, **sans Internet** (le trait de côte que SatMe porte). Couches : satellites (empreinte, trace au sol), APRS, la sonde suivie, les avions, les navires, les stations DATV dont on connaît le locator, les carrés contactés, sa station, jour et nuit, l'aurore prévue, la diffusion par avions, des cercles de distance. *Fond OpenStreetMap* (décoché d'origine) : les tuiles chargées par le navigateur, rien ne passe par SatMe-PC.

<p align="center"><img src="images/pc-carte.png" width="100%" alt="La carte commune : les avions autour de la station, le trajet de diffusion vers JO22, l'ovale d'aurore prévu au nord, les cercles de 500 km"></p>

## Navires (AIS)

L'onglet **Navires** reçoit l'**AIS** à 162 MHz, coupé tant qu'on ne clique pas **Recevoir**. Réception seulement.

- Les deux voies (161,975 et 162,025 MHz) d'une seule clé à 288 kéch/s : messages de position, de voyage, des classes A et B, des balises et aides à la navigation (ITU-R M.1371).
- **Récepteur** : la clé de l'onglet SDR (prise seulement sur demande) ou une deuxième clé (son propre rtl_tcp). L'AIS ne prend jamais la clé de l'ADS-B, ni l'inverse.
- **La liste** : MMSI, nom, destination, indicatif, type et taille, position, vitesse, route, distance et azimut, vu il y a, voies ; **Voir sur la carte**.
- **Sortie NMEA** (lignes `!AIVDM` en UDP vers 127.0.0.1:10110), coupée d'origine, pour un logiciel de navigation de ce PC.
- **Essai** : des navires simulés autour de la station.

<p align="center"><img src="images/pc-navires.png" width="100%" alt="Onglet Navires en essai : six navires simulés avec MMSI, nom, destination, indicatif, type, position, vitesse, route et distance"></p>

## Météo spatiale

L'onglet **Météo spatiale** (espace *Ciel*) : de quoi juger la propagation et l'aurore.

- **Rien n'est téléchargé** tant que *Récupérer la météo spatiale (Internet)* n'est pas coché. Alors seulement, les produits publics de la NOAA (services.swpc.noaa.gov, et lui seul) sont demandés toutes les 15 min (l'aurore toutes les 30 min) et gardés sur le disque ; au démarrage, les copies gardées sont montrées avec leur âge.
- **Indice Kp** sur un cadran, ses barres sur trois jours (observé et prévu), le **flux solaire** à 10,7 cm (proposé à la mesure du bruit solaire), les **rayons X** (GOES) sur 6 h, le **vent solaire** (vitesse, Bz, Bt), l'**aurore** prévue (OVATION) ici et à moins de 1200 km, et son ovale sur la carte.
- **Alertes** : orage géomagnétique (Kp ≥ 5) et éruption solaire (≥ M5), avec ce qu'elles changent pour le trafic ; dites à voix haute si on le coche.

<p align="center"><img src="images/pc-meteo-spatiale.png" width="100%" alt="Météo spatiale (données d'essai) : Kp 5+ sur le cadran, flux solaire 168 SFU, rayons X C2.0, vent solaire 642 km/s, aurore, l'alerte d'orage G1, les barres de Kp sur trois jours et la courbe des rayons X"></p>

## Montage : la chaîne de la station

L'onglet **Montage** (espace **Station**) dessine la station : l'antenne à gauche, l'ordinateur à droite, la réception en haut, l'émission en bas. Il s'allume avec ce que fait SatMe : en **cyan** ce qui reçoit, en **rouge** ce qui émet, le flux animé le long des câbles, les fréquences dessus (ciel, FI, montée). C'est un dessin : rien n'y démarre une émission, n'ouvre de port ni ne touche à l'alimentation du LNB.

<p align="center"><img src="images/pc-montage.png" width="100%" alt="Montage d'une station QO-100 (démonstration, émission simulée) : parabole, LNB, convertisseurs, ADALM-PLUTO, ampli 2,4 GHz, IC-9700, MiniTiouner, GPSDO et alimentation, câbles typés avec leurs fréquences ; à droite les fonctions de SatMe, qui tient quel appareil"></p>

- **Composer** : un *montage tout fait*, **Proposer d'après SatMe**, ou **✎ Modifier**. Un clic sur un appareil montre ses ports, ses notes et sa photo. *Complet* ou *Simplifié* (ce qui reçoit ou émet maintenant). Zoom (**+** **−** **0**, Ctrl + molette), déplacement à la souris, mini-carte ; **Exporter** en SVG, en PNG ou en page à imprimer ; une légende des câbles et des états, repliable.
- **Plusieurs plans** : le sélecteur **Plan** garde plusieurs dessins de la même station (« SatMe-Node déporté », « IC-9700 satellites », « DATV par le node »…) : **＋ Nouveau**, **Dupliquer** (avec les photos), **Renommer**, **Supprimer** ; chacun a ses photos et son Annuler / Rétablir. L'ancien montage devient le premier plan, sans rien perdre (`montage.json` le garde en tête, une version précédente de SatMe-PC le relit comme avant).
- **Afficher le plan de ce qui tourne** (coché par défaut) : le plan montré est celui qui dessine le mieux ce qui reçoit ou émet (la DATV par le node → le plan du node ; la SSTV par le codec de l'IC-9700 → un plan avec l'IC-9700). Un meilleur plan ne prend la place qu'après une dizaine de secondes, et le plan reste une demi-minute après la fin de l'activité : pas de va-et-vient. Un plan choisi à la main reste affiché jusqu'à la fin de l'activité ou jusqu'à ce que la case soit recochée. Si aucun plan ne dessine ce qui tourne, la **chaîne détectée** est montrée (par exemple Yagi 70 cm → IC-9700 → USB (son) → SatMe-PC → SSTV), avec **Garder comme plan**.
- **Les décodeurs par le son du poste** : SSTV, APRS, FT8, CW ou phonie nourris par la carte son du poste (le codec USB de l'IC-9700, ou son son réseau) allument le poste, son antenne et le PC, même sans CAT ; la fréquence est celle du CAT, sinon celle de l'émetteur du satellite suivi (ISS SSTV 437,550 MHz), et elle choisit l'antenne de la bande. Une autre carte son allume la carte son.
- **Ce qui vit** : le CAT et le rotor, les récepteurs SDR et DATV, l'IC-9700 par le réseau (son câble réseau allumé, le retard, le lien perdu), la molette USB, les écouteurs, la météo spatiale, les canaux QO-100, l'enregistrement et le générateur, l'ADS-B et l'AIS qui tiennent une clé. Les fonctions hors satellites décochées disparaissent du dessin.
- **L'IC-9700 par le réseau** dans les vues des fonctions : le son réseau du poste est son propre son (plus de conseil de prendre le codec USB, proposé seulement quand une autre carte tourne) ; le lien poste → PC dessiné comme il passe, en pointillé bleu avec son retard et « CAT + son » ; sans câble réseau dessiné, il est montré *détecté*, plus pâle, avec **Ajouter ce câble**. « Aucune antenne » propose **Ajouter une antenne 2 m / 70 cm** (une Yagi bibande sur les prises libres du poste) ; la bande de la fréquence du poste choisit la prise d'antenne, et une prise déjà prise (le 70 cm vers le convertisseur QO-100) est dite. SatMe-PC reste dans le dessin simplifié, à sa place, jamais par-dessus une pièce.
- **Ce qui manque** : chaque fonction vérifiée de l'antenne à l'écran ; un **danger** est dit fort — par exemple l'alimentation du LNB qui atteindrait une carte SDR à travers un répartiteur (un bloqueur de continu est alors proposé).

### Dessiner et modifier

**✎ Modifier** ouvre la **palette** du catalogue, par groupe : antennes (paraboles, Yagi, X-quad, colinéaire, antenne intérieure, antenne GPS), têtes et convertisseurs (LNB PLL, LNB Twin dont l'Octagon à référence 25 MHz externe, convertisseurs DX Patrol MK3 / MK4 montants et descendants avec leur entrée 10 MHz), amplificateurs (DX Patrol 2,4 GHz 20 / 15 W), filtres, bias-T, injecteur DC + référence, bloqueur de continu, répartiteur, références (GPSDO DX Patrol 10 MHz, GPSDO Leo Bodnar programmable), alimentation 13,8 V, séquenceur, postes, cartes SDR, MiniTiouner, SatMe-Node, carte son, molette USB, rotor, PC, téléphone.

- **Glisser** une pièce sur le dessin ; **tirer un câble** d'un port à l'autre (son type vient des ports : coaxial, réseau, USB, Wi-Fi, audio, CAT, rotor, IQ, référence, alimentation, GPS, Internet) ; la fiche du câble (type, étiquette, supprimer). Chaque pièce a ses options (sortie FI, référence, fréquences).
- Placement libre ou **Disposition auto** (plusieurs dispositions essayées, celle qui croise le moins gardée) ; **Annuler / Rétablir** (Ctrl+Z / Ctrl+Y), gardés par SatMe-PC ; tout se fait aussi au clavier.
- **Proposer d'après SatMe** : une chaîne tirée de l'état de SatMe (poste, rotor, cartes SDR, récepteur et émetteur DATV, convertisseurs, oscillateurs de la chaîne QO-100) et des listes du système (USB, cartes son) lues sans rien ouvrir ; chaque pièce *détectée* ou *supposée*, reprise pièce par pièce.
- Les **boîtes** des services : Wavelog, QRZ.com, PSK Reporter, salon de la bande large, clients rigctld, lien satellite, téléphone appairé, SatMe-Node, « Écouter ici », avec leur état.
- **Photos** : la photo d'un appareil (8 Mo au plus) est **détourée** toute seule (fond retiré, tolérance réglable, l'original gardé à côté) ; *Photo à la place du dessin* l'affiche dans le schéma, la lueur RX / TX suivant sa forme.

<p align="center"><img src="images/pc-montage-edition.png" width="100%" alt="Montage en modification : la palette du catalogue à gauche (paraboles, Yagi, antenne GPS, rotor, LNB, convertisseurs DX Patrol), le dessin au centre, la liste des appareils et des câbles à droite"></p>

### Les fonctions et les profils

- **Les fonctions** de SatMe (récepteur SDR, DATV, SSTV, FT8, CW, APRS, phonie → texte, METEOR, sondes, émissions) à droite de l'ordinateur, avec le matériel du son : **qui tient quel appareil** (« ADALM-PLUTO → DATV réception »), qui reçoit, qui émet, qui fait ou prend du son. Un **conflit** (une fonction qui voudrait un appareil ou une entrée son déjà pris) s'affiche en rouge avec sa raison et le geste qui le libère.
- **Fonction** : la chaîne d'une seule fonction, de l'antenne à son écran, chaque maillon en marche, prêt, manquant ou occupé, et ce qu'il manque. Les boutons ne font que de la réception (démarrer ou arrêter un récepteur, choisir une source son).
- **Profils** : les réglages de réception du moment gardés sous un nom (« SSTV par IC-9700 ») — récepteur SDR (carte, mode, fréquence, suivi, son), entrée son des décodeurs, SSTV, FT8 / FT4, CW, APRS, phonie → texte, récepteur DATV ; jamais rien de l'émission ni l'alimentation du LNB. **Aperçu** liste ce qui changera ; **Appliquer** le fait dans un ordre sûr (arrêts, réglages, récepteurs, carte son, décodeurs). **Refusé pendant une émission.** La réception DATV ne se démarre pas d'ici : l'étape est rappelée.
- **Schéma** (cockpit QO-100 et DATV, décoché d'origine) : le dessin simplifié par-dessus l'onglet.

## Réglages

Par catégorie, une liste à gauche : **Ma station** (indicatif, carré, élévation minimale, horizon, fonctions hors satellites), **Poste et rotor** (CAT par USB ou par le réseau, TH-D72, rotor, convertisseurs, journal Doppler, ADALM-PLUTO), **Satellites** (éléments orbitaux, AMSAT, QO-100 au démarrage), **Interfaces** (lien satellite, rigctld, molette USB), **Annonces** (annonces vocales), **Services en ligne** (QRZ.com, Wavelog / Cloudlog, skeds hams.at), **Phonie → texte**, **Téléphone**, **Accord fin**, **Émission** (IC-9700 : réseau autorisé, minuterie, puissance au plus, carte son ; correction I/Q des émetteurs), **Accès à distance** (mot de passe, HTTPS, application, écran allumé), **Sauvegarde** (toute la station dans un fichier, restaurée au démarrage suivant).

### Le poste et le rotor

Le modèle du poste (IC-9700, IC-9100, IC-910H, TS-2000, deux postes FT-817 / IC-705, TH-D72, ou simulé), son port et sa vitesse, Connecter / Déconnecter ; le rotor (GS-232 en série, rotctld par le réseau, ou simulé), Suivre le satellite, Parquer, Stop, ses butées et son parking. L'onglet Suivi ne garde que leur nom et une coche verte.

**Le plan du passage** (Réglages › Poste et rotor › Rotor) : la **course en azimut** (360°, ou 450° / 540° avec un recouvrement), **Monte à 180° ?** (seul un rotor qui monte à 180° peut se retourner), la butée nord ou sud, l'**attente avant l'AOS**, et les **zones cachées** (toit, arbre, colline : `170-190<20 ; 300-330<10`, ou tracées en glissant sur le petit ciel) — le plan n'y tient presque pas et y place ses déroulements. Il garde le plus de passage, compté selon l'élévation (une minute à 60° vaut bien plus qu'à 5°), puis la plus petite course, puis le moins de changements. Le petit ciel montre le plan du prochain passage avec les réglages appliqués. La vitesse du rotor est mesurée quand le mât court loin de sa consigne.

<p align="center"><img src="images/pc-reglages-materiel.webp" width="100%" alt="Réglages : le poste (CAT) et le rotor, leurs ports et leur connexion"></p>

### L'IC-9700 par le réseau

**Réglages › Poste et rotor › Liaison : Réseau (LAN de l'IC-9700)** : SatMe-PC rejoint le serveur réseau intégré de l'IC-9700 (trois ports UDP : contrôle 50001, CI-V 50002, son 50003 ; une seule session à la fois), sans câble USB. Il y prend le **CI-V** (le même pilotage Doppler que par USB) et le **son reçu**, proposé comme source « IC-9700 (réseau) » à l'onglet Son, au SSTV, au FT8, à la phonie et à « Écouter ici ».

- **Sur le poste** : *MENU › SET › Network* : DHCP (ou une adresse fixe), **Network Control : ON**, *Network User1 ID* et son mot de passe, *Network Radio Name*, les ports (50001 d'origine). L'adresse du poste s'affiche dans ce même menu ; la taper dans SatMe-PC avec les ports.
- **Réception seulement** d'origine : aucun son n'est envoyé, la voie d'émission n'est pas demandée, toute commande d'émission CI-V (PTT `1C 00`, accordeur `1C 01`, `17`, `28`, même derrière le préfixe `29`) est refusée. Seule exception, cochée sur ce PC : *Émission par le réseau autorisée* laisse passer le PTT (voir [L'IC-9700 en émission](#lic-9700-en-émission)). À la déconnexion, la session est toujours rendue au poste ; un lien perdu est repris en arrière-plan.
- **Son** : PCM 16 bits à 16 kHz d'origine, 8 kHz pour un lien lent ; un tampon de 40 à 300 ms remet les paquets dans l'ordre et redemande ceux qui manquent. La disposition des échantillons (ordre des octets, signé ou non) est reconnue d'après le son lui-même.
- **Identifiants** : le poste attend une forme codée de l'identifiant et du mot de passe. Le plus simple : capturer sur son réseau le début d'une connexion qui marche (port 50001, fichier `.pcap` ou `.pcapng`), taper l'identifiant et le mot de passe en clair, puis *Identifiants codés › Apprendre le codage depuis une trace* : SatMe-PC y relève les champs codés, apprend le codage et dit si le poste avait accepté cette connexion. Le fichier est lu en mémoire puis oublié ; ni les octets codés ni le texte en clair ne sont affichés ni écrits dans un journal. On peut aussi coller les 16 octets codés (hexadécimal).
- *Spectre du poste* (option) : le spectre que le poste envoie par CI-V, en bandeau dans l'onglet Suivi ; remis à OFF sur le poste à la déconnexion. *Journal des paquets* : les en-têtes seulement, dans `icom-reseau-trace.txt`.

**Son AF ou FI.** Le menu *Son* dit ce que le poste envoie sur sa sortie son (USB ou réseau) :

- **AF** (réglage d'usine du poste) : le poste démodule, SatMe reçoit son son. Si le poste envoie en fait sa FI, SatMe le remarque (l'énergie haut dans la bande, une FI repliée vers 4 kHz) et le dit : régler la sortie du poste sur AF.
- **FI** : le poste envoie sa dernière FI, centrée sur 12 kHz, large d'environ 15 kHz ; SatMe la démodule lui-même dans l'onglet SDR, récepteur **« IC-9700 FI »** : cascade, USB, LSB, CW, FM, AM, nettoyage, canaux, enregistrement et décodeurs. Sur le poste : **MENU › SET › Connectors › USB AF/IF Output › Output Select : IF** (AF pour revenir). Par le réseau, le son à 16 kHz replie la FI : ±3,6 kHz utiles autour du cadran ; par la carte son USB (48 kHz) : toute la FI, ±7,5 kHz. Le centre est le cadran RX du poste lu en CAT ; *Recentrer le poste* déplace le décalage RX du poste (réception seulement). *Sens inversé* si les stations montent quand le cadran descend.

<p align="center"><img src="images/pc-reglages-icom-reseau.png" width="50%" alt="Réglages, Poste : l'IC-9700 par le réseau — liaison réseau, son AF, adresse et ports 50001 à 50003, identifiant et mot de passe (vides ici), identifiants codés, son PCM 16 bits 16 kHz, tampon 80 ms"></p>

**Journal Doppler / CAT** (coupé d'origine) : pendant un passage suivi avec le poste connecté, toutes les 5 s l'heure, l'azimut, l'élévation, la distance, le Doppler prévu sur les deux voies, la fréquence que la boucle a écrite et celle relue — un CSV par passage dans `doppler/`, relié au passage dans le Journal. Seul ce que fait déjà la boucle CAT est noté. *Journal détaillé* (décoché d'origine) : le spectre autour de la descente noté pendant les passages suivis, pour le [rapport de passage](#rapport-de-passage).

### L'IC-9700 en émission

Le panneau **Émission IC-9700** (onglet Suivi, et cockpit QO-100 quand il est affiché) pilote l'émission de l'IC-9700 relié en CAT (USB, réseau ou simulé), depuis ce PC ou depuis une autre machine (mode distant, même mot de passe).

- **PTT tenu** : le bouton **PTT** reste enfoncé tant qu'on parle (ou la **barre d'espace**, le panneau sélectionné). SatMe envoie `1C 00 01` puis `1C 00 00` au relâcher. **Un seul client** tient le PTT, tous voient qui (bandeau **TX** rouge en haut de chaque page), et tous peuvent l'arrêter (le bandeau, Échap). Depuis une autre machine, une confirmation par session, comme pour la DATV.
- **Relâché tout seul** : **2 s** sans nouvelles de la page qui le tient (onglet caché, fenêtre quittée, liaison perdue, micro coupé), la **minuterie** écoulée (Réglages › Émission, 3 min d'origine, de 1 à 10), le poste qui ne répond plus (son état TX relu deux fois par seconde), le poste déconnecté (le PTT part avant la fermeture du CAT), SatMe-PC fermé. Un relâcher refusé est renvoyé dès que le poste répond. Rien n'émet au démarrage ni après une reconnexion. Garder aussi la **minuterie du poste** (son menu) : c'est la dernière garde si le PC s'arrête net.
- **Le micro du navigateur** : 48 kHz mono, un compresseur léger, un réglage de niveau, un vumètre, la CAG du navigateur en option ; des trames de 20 ms par une WebSocket à part (`/api/poste/micro/ws`), envoyées seulement pendant le PTT, et jetées par SatMe-PC si ce client ne tient pas le PTT. Vers le poste : sa **carte son USB** (« CODEC », reconnue ou choisie dans Réglages › Émission), ou **par le réseau**. Sur le poste, la source de modulation *DATA OFF MOD* doit être **USB** (ou **LAN** par le réseau), et le **VOX coupé**. Le navigateur ne donne le micro qu'à une page sûre : ce PC (http://localhost), ou **HTTPS** depuis une autre machine (voir [Accès à distance](#accès-à-distance)).
- **Puissance** : la puissance RF du poste (CI-V `14 0A`, 0 à 255) en % et en watts estimés pour la bande (100 W sur 144 MHz, 75 W sur 430 MHz, 10 W sur 1200 MHz), un curseur et des préréglages (5, 10, 25, 50, 100 %), lue et écrite sur la bande qui émet (SUB en mode satellite). **Puissance au plus** (Réglages › Émission) : jamais dépassée ; sous ce plafond, le PTT attend que le poste confirme une puissance en dessous.
- **Par le réseau** : **Émission par le réseau autorisée** (Réglages › Émission), décochée d'origine, ne se coche que sur le PC de la station (jamais depuis une autre machine ni par une sauvegarde du téléphone). Cochée, seul le PTT passe la garde (`1C 00`) : boîte d'accord, CW et mémoires vocales restent refusées ; le son d'émission est demandé à la connexion (reconnecter le poste après l'avoir cochée). Décochée pendant une émission : le PTT est relâché aussitôt.
- **Le son d'émission par le réseau n'est pas encore vérifié sur un poste** : il suit le format du son reçu et les descriptions publiques du protocole (paquets de 20 ms, 16 bits, à la cadence de réception). Pour le vérifier : capturer avec Wireshark le logiciel d'Icom qui émet quelques secondes **dans une charge fictive**, puis *Réglages › Émission › Vérifier sur une capture* : SatMe lit ce que ce logiciel demande à la connexion et la forme de ses paquets, compare, et retient la marque et la taille trouvées. Rien de la capture n'est gardé.
- **Accorder en BLU pendant l'émission** : la molette USB, la molette de la souris et le glisser (doigt ou souris) sur les fréquences RX / TX de l'onglet Suivi accordent comme en réception, au pas de la molette USB. Sur un satellite linéaire, la position dans le transpondeur bouge, montée et descente liées, et le Doppler continue. Sans satellite qui pilote le poste (BLU terrestre, Doppler en pause, satellite sous l'horizon), la molette accorde directement le cadran du poste, affiché à la place de la fréquence du satellite.

### Fonctions hors satellites

**Réglages › Ma station** : les onglets qui ne parlent pas de satellites — **Avions**, **Navires**, **Sondes** — ne sont montrés que cochés (décochés d'origine ; au premier démarrage, ceux que la station utilisait déjà restent). Décoché : l'onglet quitte le rail, la palette et les couches de la carte, son adresse affiche un avis et ses démarrages sont refusés ; décoché pendant qu'il reçoit, il s'arrête après confirmation et rend sa clé.

### Éléments orbitaux

Comme sur le téléphone : le **serveur GP SatMe** d'abord (`https://gp.f4ioz.fr` d'origine), ou ce serveur seulement, et les sources au choix — le bulletin AMSAT, les groupes CelesTrak (par le serveur seulement : le PC ne demande jamais rien à CelesTrak), SatNOGS DB, et **les satellites d'images (SSTV, SSDV)** : LAPAN-A2, ASRTU-1, HADES-SA, Geoscan-2/4/5, 239Alferov, UmKA-1, UTMN2, SONATE-2… la plupart absents du bulletin AMSAT, demandés un par un par leur numéro.

<p align="center"><img src="images/pc-reglages.webp" width="80%" alt="Réglages : éléments orbitaux (serveur, sources), QRZ.com, carnet en ligne avec l'envoi automatique"></p>

### Le lien satellite (WebSocket)

Pour d'autres programmes du PC (un carnet, un tableau de bord, un script) : **Réglages › Interfaces › Lien satellite**, coupé d'origine. Une fois coché, `ws://localhost:8095/` (le port se change) envoie des trames texte en JSON ; sur ce PC seulement, sauf si SatMe-PC est lancé avec `--reseau`. Rien de ce qu'envoie un client n'est exécuté (le ping a sa réponse, la fermeture son écho).

| `type` | Quand | Champs |
|---|---|---|
| `hello` | à la connexion | `app`, `version`, `callsign`, `locator` |
| `satellite` | chaque seconde tant qu'un satellite est suivi | `time`, `name`, `catnum`, `az`, `el`, `rangeKm`, `rangeRateKmS`, `transmitter`, `downlinkHz`, `uplinkHz` (Doppler compris), `dopplerDownHz`, `dopplerUpHz` |
| `passAlert` | le satellite suivi et les favoris ★ | `kind` (`aosSoon` 5 min avant, avec `inS` ; `aos` ; `los`), `name`, `catnum`, `aos`, `los` (ms UTC), `maxEl`, `aosAz`, `losAz` |
| `qsoLogged` | un contact entre au carnet | `time`, `call`, `grid`, `satellite`, `catnum`, `mode`, `rstSent`, `rstRcvd`, `downlinkMHz`, `uplinkMHz`, `az`, `el`, `myGrid` |

```
{"type":"satellite","time":1792089000000,"name":"ISS","catnum":25544,"az":212.4,"el":31.7,"rangeKm":812.3,"rangeRateKmS":-4.512,"transmitter":"FM voice repeater (V/U)","downlinkHz":437806580,"uplinkHz":145987810,"dopplerDownHz":6580,"dopplerUpHz":-2190}
```

### La fréquence pour les carnets (rigctld)

**Réglages › Interfaces › Fréquence pour les carnets**, coupé d'origine : un serveur au protocole réseau rigctld, port 4532 (il se change ; pas celui du rotor), sur ce PC seulement sauf si « Réseau local » est coché et SatMe-PC lancé avec `--reseau`. Un logiciel de carnet ou de modes numériques réglé sur « rigctld » à 127.0.0.1:4532 lit :

- `f` la fréquence du ciel de la réception en cours — le poste quand il est relié en CAT, sinon le récepteur SDR ; sur QO-100 la descente nominale (10 489 750 000), ou la montée si « Noter la montée » est coché ;
- `m` le mode et la bande passante, `v` VFOA, `s` / `i` le split et la fréquence d'émission quand il y en a une (la montée QO-100, l'émission du poste), `t` si SatMe émet en ce moment (DATV, SSTV, calibration I/Q) ;
- `\dump_state`, `\chk_vfo`, `\get_powerstat`, `\dump_caps`, les formes longues, et les réponses étendues (`+`, `;`).

`T` (PTT) et tout réglage d'émission sont toujours refusés (`RPRT -11`). `F` et `M` aussi, sauf si « Autoriser le changement de fréquence » est coché : ils n'accordent alors que le récepteur SDR — jamais le poste, ni quand QO-100 est au poste.

### La molette USB (VFO)

**Réglages › Interfaces › Molette USB**, coupé d'origine, **Linux seulement** : la molette de volume USB accorde, comme sur le téléphone. Elle est lue dans `/dev/input` ; illisible, la carte donne la règle udev (groupe **plugdev**) à copier dans un terminal.

- **Tourner** : un pas (10 Hz, 100 Hz ou 1 kHz). **L'appui** : changer le pas, cible suivante, ou décalage à zéro. Les touches A, B, C d'un petit clavier choisissent la cible (« Apprendre », puis presser la touche).
- **Accorde** : *Auto* (l'onglet SDR accorde le récepteur SDR, le cockpit QO-100 la bande étroite ; ailleurs le poste relié en CAT, sinon le SDR en marche), ou toujours le *Poste* (VFO, décalage RX ou TX), le *SDR*, *QO-100*.
- **Capturer** (option) : SatMe seul lit la molette, le volume du bureau ne bouge plus tant que SatMe-PC tourne ; jamais un clavier complet.
- **Réception seulement** : la molette ne passe jamais en émission et ne lance rien.
- **La règle udev** (Linux), si la carte dit la molette illisible — dans un terminal, puis débrancher et rebrancher la molette (le compte doit être dans le groupe `plugdev`) :

  ```
  echo 'SUBSYSTEM=="input", ATTRS{idVendor}=="2341", ATTRS{idProduct}=="484d", MODE="0660", GROUP="plugdev"' | sudo tee /etc/udev/rules.d/70-satme-molette.rules && sudo udevadm control --reload && sudo udevadm trigger
  ```

  (la carte donne la même ligne avec les numéros de la molette branchée). Une puce dans le bandeau montre la cible et le pas, et les change.

<p align="center"><img src="images/pc-reglages-interfaces.png" width="80%" alt="Réglages, Interfaces : le lien satellite (WebSocket, port 8095), la fréquence pour les carnets (rigctld, port 4532, réseau local, montée, changement de fréquence), la molette USB (cible, pas, appui, touches A B C)"></p>

### Annonces vocales

**Réglages › Annonces**, coupé d'origine : ce que fait la station, dit à voix haute en français.

- **Quoi** (chaque annonce a sa case) : le satellite suivi N minutes avant l'AOS, son AOS et son LOS (élévation maximale), une émission DATV / SSTV / I/Q qui commence ou s'arrête et « Émission en cours » toutes les N minutes, LEILA sur QO-100, une nouvelle image SSTV, son propre indicatif décodé en FT8 / FT4 ou en CW, la DATV accrochée ou perdue et l'indicatif lu dans l'image, une fenêtre de diffusion par avion, le rotor en butée ou perdu, le récepteur SDR arrêté de lui-même, le pas de la molette.
- **Comment** : les nombres en toutes lettres, les indicatifs épelés (Alfa, Bravo…), les fréquences chiffre par chiffre ; trois niveaux de détail ; une même phrase jamais deux fois en 30 s, trois en attente au plus.
- **Par où** : la voix du navigateur (une voix française locale), ou une voix du PC (speech-dispatcher, eSpeak NG, Piper), le haut-parleur SDR baissé pendant ce temps. **Tester** ; un haut-parleur dans le bandeau coupe tout de suite.

<p align="center"><img src="images/pc-reglages-annonces.png" width="80%" alt="Réglages, Annonces : voix, détail, vitesse, volume, minutes avant le passage, indicatifs épelés, et une case par événement"></p>

### Correction I/Q des émetteurs

**Réglages › Émission** : pour chaque carte d'émission (ADALM-PLUTO, bladeRF, HackRF, par son numéro de série), la fuite de l'oscillateur, l'image et l'erreur du quartz corrigées juste avant l'émetteur, pour la DATV comme pour la SSTV. Neutre d'origine : les échantillons et l'oscillateur exactement comme avant. Des curseurs (appliqués en direct à une émission de cette carte), un ton d'essai et une **calibration automatique en boucle fermée**, en intérieur seulement (1245 MHz, −40 dB au plus, VGA du HackRF à 0) : un autre récepteur SDR de la station entend le ton, la fuite et l'image, et SatMe cherche le fond ; le récepteur prêté par l'onglet SDR est rendu comme il était.

### Accès à distance

**Réglages › Accès à distance** : lancé avec `--reseau`, SatMe-PC demande un **mot de passe** à chaque autre machine (une page de connexion) ; ce PC lui-même entre sans.

- **Au premier démarrage** avec `--reseau`, un mot de passe de quatre mots est tiré, écrit dans le journal de SatMe-PC et montré ici jusqu'à ce qu'on le remplace (8 caractères au moins). Sans mot de passe, les autres machines n'ont rien.
- Une session dure **30 jours** (un cookie). **Cinq essais faux** depuis une adresse : une minute d'attente, puis le double après chaque série (une heure au plus).
- Le mot de passe n'est gardé qu'**haché** (PBKDF2) dans `acces-distant.properties`, jamais dans la sauvegarde ; le changer ferme les autres sessions (**Fermer les autres sessions** aussi). Une émission lancée à distance demande les mêmes confirmations qu'ici.
- **Sans HTTPS**, le mot de passe et la page passent en clair : un réseau de confiance seulement (ou un tunnel chiffré).
- **HTTPS** (carte *HTTPS*, décochée d'origine, réglable sur ce PC seulement) : la même page sur un second port (8443 d'origine), avec un certificat fait par ce PC au premier usage (EC P-256, environ un an, refait seul un mois avant la fin, gardé dans `https/`) pour ses noms et ses adresses. Le navigateur prévient une fois : comparer l'empreinte SHA-256 qu'il montre avec celle de la carte avant d'accepter. La page devient « sûre » : le **micro** (émission IC-9700), l'écran allumé et l'installation y marchent depuis une autre machine. Ouvrir le port : `sudo ufw allow from 192.168.1.0/24 to any port 8443 proto tcp`. *Nouveau certificat* en refait un (les navigateurs préviendront de nouveau). Le http reste comme avant.
- **Pare-feu** (Linux, ufw) : ouvrir le port de la page au réseau de la maison seulement, une fois, dans un terminal : `sudo ufw allow from 192.168.1.0/24 to any port 8090 proto tcp` (adapter la plage). Pour l'appairage du téléphone par QR code, le port suivant : `sudo ufw allow from 192.168.1.0/24 to any port 8091 proto tcp`. **De l'extérieur**, passer par un **VPN** vers la maison (et autoriser la plage du VPN de la même façon) ; ne jamais ouvrir le port sur Internet.
- **Sur cet appareil** : *Garder l'écran allumé* et **Installer l'application** (une fenêtre à part, une icône), pour ce navigateur ; les navigateurs ne les permettent que sur http://localhost ou en HTTPS.

### Mode distant

Pour la page ouverte de loin (un VPN, une autre maison) : le bouton **Distant** en haut, ou **Réglages › Accès à distance › Mode distant**, pour ce navigateur seulement (proposé quand la page s'ouvre depuis une autre machine). Le badge **Mode distant** et son « ? » disent ce qui est allégé.

- **🔈 Écouter ici** prend la voie à **faible latence** : une WebSocket (`/api/son/ws`, derrière la même session), des trames de 20 ms à 8, 12 ou 16 kHz (filtrées puis rééchantillonnées), en PCM 16 bits ou en **µ-law** (moitié moins de débit : 64 kbit/s à 8 kHz). Le lecteur garde un tampon de 60 à 120 ms réglé sur la gigue mesurée (95 % des écarts + 20 ms), remplace une trame perdue par la précédente atténuée, suit la dérive de la carte son (±0,5 %), et affiche la latence estimée, la gigue, l'aller-retour, les trames masquées. Sur ce PC, environ 0,11 s au lieu de 0,6 s par le flux HTTP, qui reste en secours (choix *Voie* : auto, faible latence, flux HTTP).
- En http depuis une autre machine, les navigateurs refusent l'AudioWorklet : le même tampon tourne alors dans un ScriptProcessor (environ 20 ms de plus). Pas d'Opus (il faudrait un codeur natif sur le PC).
- **Allégé** : spectre et cascade SDR à 5 images par seconde (256 points, niveaux sur un octet), loupe, panorama QO-100, bande large et vumètre DATV moins souvent, vidéo DATV en vignette toutes les 2 s (**▶ Vidéo en direct** sur l'image pour la demander), carte, avions et navires toutes les 5 à 10 s, le flux en direct réduit à l'onglet affiché, animations coupées.
- **Réception seule** : cette voie refuse tout son montant et toute commande d'émission (la connexion est fermée). Le micro et le PTT de l'IC-9700 ont leur propre voie (panneau *Émission IC-9700*, voir [L'IC-9700 en émission](#lic-9700-en-émission)), en HTTPS depuis une autre machine.

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
- **Le CAT n'émet que par le PTT tenu de l'IC-9700** (panneau *Émission IC-9700* : un client, 2 s de garde, minuterie, relâché à la déconnexion et à la fermeture) ; `TX`, `KY` refusés par le pilote TS-2000. L'APRS par un TNC KISS n'émet que quand on envoie soi-même un message, sa position ou un statut.
- **L'alimentation LNB du MiniTiouner est coupée** tant qu'on ne la choisit pas.
- **L'ADALM-PLUTO et le bladeRF 2.0 n'émettent que depuis les onglets DATV et SSTV**, sur un clic confirmé, dans les règles ci-dessus ; leurs récepteurs n'écrivent jamais le côté émission. DATV et SSTV n'émettent jamais en même temps.
- **L'IC-9700 par le réseau ne fait que recevoir**, sauf *Émission par le réseau autorisée* cochée sur ce PC : alors le PTT seul passe et la voie son d'émission est demandée ; boîte d'accord, CW et mémoires vocales restent refusées avant d'atteindre le poste.
- **Rien ne part sur Internet sans une case cochée** : météo spatiale, partage SatNOGS, PSK Reporter, fond OpenStreetMap, salon de la bande large, envoi au carnet en ligne — tous coupés d'origine.
- **Les interfaces ne passent jamais en émission** : rigctld refuse le PTT, la molette USB accorde sans jamais émettre, l'ADS-B, l'AIS, le balayage, les mesures et le générateur ne font que recevoir, les profils du Montage ne s'appliquent pas pendant une émission ; le balayage du Soleil ne bouge le rotor qu'après confirmation.
- **Le HackRF One n'émet que depuis l'onglet DATV**, sur un clic confirmé, en essai en intérieur ou sur charge, son amplificateur RF toujours coupé et son gain à 20 dB au plus ; jamais vers QO-100.

## Avant le premier essai

- **Linux** : être dans le groupe `dialout` (`sudo usermod -aG dialout $USER`, puis se reconnecter).
- **Windows** : installer le pilote de l'adaptateur (CP210x, FTDI, CH340) ; le poste apparaît en `COM3`, `COM4`…
- Commencer avec le **poste simulé** et le **rotor simulé**, puis brancher le vrai matériel un élément à la fois.
- Pour essayer **une deuxième copie** de SatMe-PC à côté de la station (autre port, autre dossier), la lancer avec `SATME_SANS_SERIE=1` (ou `-Dsatme.sansSerie=true`) : elle n'ouvre alors aucun port série, et ne peut pas lever RTS/DTR sur un poste ou une carte.
- Sans matériel du tout : le **générateur d'essai** du récepteur SDR, et les boutons **Essai** de la télémesure et des navires.
