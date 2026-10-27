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
- **Orbites en plein écran** : le logo et « SatMe » en haut à gauche (en bas à gauche en Classique radioamateur).
- **DATV › Pointage** (bouton en haut de l'onglet, ou `pc?pointage=1` sur le téléphone) : le **MER en très gros**, la marge D, la **tendance** (▲ mieux / ▼ moins bien qu'il y a 5 s), le **maximum** (remise à zéro), le niveau, 2 minutes de courbe et un **son dont la hauteur suit le MER** ; « DÉCROCHÉ depuis… » en rouge s'il lâche. Rappel : balise seule = référence, une retouche à la fois.
- **DATV › Qualité › Historique** : MER, marge D et niveau sur 10 min à 24 h, le temps décroché en rouge, des marques (station changée, arrêt, OL calé, **Marquer une retouche**).
- **Niveau du signal** estimé depuis les AGC du MiniTiouner (≈ dBm, échelle MiniTioune / Portsdown d'après les mesures du BATC) : bon de −70 à −35, trop faible ou trop fort dit en clair.
- **Calage auto sur la balise** (carte Réception, décoché d'origine) : l'OL réel du LNB et sa dérive mesurés sur la balise, toujours affichés ; coché, pris au prochain accord.
- **Mon spectre — SDR sur la même tête** (décoché d'origine) : le SDR de l'onglet SDR (RTL-SDR, Pluto, bladeRF, SatMe-Node) sur un répartiteur balaie la bande large, à l'échelle de la station sol ; un clic accorde le MiniTiouner. Avertit si la balise n'y est pas.
- **Réglages LongMynd et décodage** : largeur de recherche (`-S`), attente sans paquets (`-r`), transmises au SatMe-Node ; **décodage tolérant** pour les flux abîmés.
- **DATV › Sous-titres › Traduction parlée** (coupée d'origine) : la traduction de chaque passage reçu dite par la voix de synthèse de Phonie → texte, avec sa voix, son volume et le **son d'origine baissé** pendant qu'elle parle (réglable). Jamais par-dessus elle-même : seul le passage le plus récent attend, rien après 20 s de retard. Elle passe là où passe le son DATV (haut-parleur du PC, navigateur qui écoute), réception par un SatMe-Node comprise ; sans voix installée, la carte mène à l'installation.
- **Affichage des sous-titres** : largeur, nombre de lignes (1 à 4), longueur des lignes (≈ mots par sous-titre), taille (auto ou fixe), durée, et **Plus de texte** (4 lignes, 90 % de large, les sous-titres précédents gardés au-dessus). Aperçu immédiat, réglé par navigateur, rien n'est jamais coupé.
- **Résumé** de la station reçue (et des précédentes) : indicatifs entendus, lus dans l'image ou dans le nom du service, reports (5/9, MER, marge), locators, matériel cité, fréquences, phrases clés, dans ta langue. Fait sur le PC sans Internet ; **Copier** et **Noter le QSO** (préremplit le carnet sans enregistrer).
- **Sked › Carte / Globe** : la fenêtre commune rejouée aussi sur le **globe** de l'onglet Orbites, avec tous ses rendus (dessin animé, réaliste, hologramme…) et ses réglages (caméra, détails, villes, noms, monuments, leur taille et leurs familles) dans **Réglages du globe**. Les deux stations nommées, la trace du satellite, la partie où vous le voyez tous deux en vert, son empreinte ; le curseur et ▶ le pilotent. Glisser pour tourner, molette pour zoomer, double-clic pour recadrer. Le choix est gardé ; la carte reste celle d'origine.
- **Télémesure › Images SSDV** : une image renvoyée sur **plusieurs passages** (même indicatif, même numéro, dans les 3 jours) est **complétée** avec les nouveaux paquets ; « N/M paquets, X % », une **bande colorée par passage** (gris = manquant) et la liste des passages. Les paquets sont gardés dans `telemesure/ssdv/paquets` (une image en cours survit à un redémarrage, doublons écartés) ; complète, elle rejoint la galerie. En cours : **⤓ PNG / JPEG** ou **Abandonner**. **Importer des paquets…** : paquets bruts, KISS ou journal de trames d'un passage manqué. En démonstration : deux images faites sur deux passages.
- **SDR › échelle des satellites** entre le spectre et la cascade : chaque descente connue à sa fréquence entendue ici (Doppler et correction compris), verte au-dessus de l'horizon, blanche au lever dans les 5 min, grise couchée ; les transpondeurs linéaires en bandes qui suivent le Doppler. Clic sur un nom : suivre cet émetteur ; dans une bande : s'y accorder ; ailleurs : fréquence fixe. Molette sur la bande passante : accord fin (Alt : plus vite) ; **Ctrl + molette** sur un satellite : sa correction manuelle. Infobulle : mode, Doppler, él/az, AOS/LOS.
- **Orbites › Passage › Vue du satellite** (nouvelle caméra, aussi dans *Réglages du globe* du Sked et dans le Journal) : la Terre vue d'en haut, à la verticale du satellite — son empreinte (l'horizon radio), ta station et l'autre station du sked, les contacts du carnet s'ils sont affichés, la limite jour / nuit, sa trace au sol devant et derrière, les villes et monuments. À côté : altitude, rayon de l'empreinte, jour ou nuit sous lui, « ta station : dans l'empreinte, à X km du bord ». Glisser pour regarder ailleurs, retour seul ou au double-clic ; la Hauteur ou la molette rapprochent jusqu'au satellite. En *Vitrine*, le globe y passe 30 s toutes les 90 s.
- **Convertisseurs et transverters** (Réglages › Poste et rotor) : une table de bandes — RF de… à…, OL, inversé, et devant quoi (clé SDR, réception CAT, émission CAT) ; la première ligne qui couvre gagne. Préréglages QO-100 (LNB 9750, convertisseur vers 2 m / 70 cm, montée 2400 depuis 432 ou 144) et transverters FI 28 MHz ; l'ancien réglage repris en tête. Sans ligne, rien n'est envoyé au poste et la barre d'état le dit en jaune ; au survol des fréquences, la FI envoyée.
- **Calibrer la clé SDR…** : sur une balise QO-100, le ton FCCH d'un relais GSM ou une fréquence connue, SatMe trouve le pic, donne l'erreur en ppm (à enregistrer d'un clic) et, derrière un LNB, son oscillateur réel. **Aligner…** (cockpit) : la descente sur la balise, puis la montée avec tes points (tu émets toi-même), gardées par satellite.
- **Agenda › Activités à venir › Activer l'auto-pilote** : sur chaque activité SSTV, un clic, une confirmation (satellite, fréquence, mode, période, passages utiles, chaîne par défaut ; réception seulement, rien n'est émis) et la campagne est créée, activée, l'auto-pilote allumé s'il était coupé. La carte dit ensuite « Auto-pilote actif ✓ » avec le prochain passage reçu, **Suspendre** et **Voir la campagne** ; si l'activité change de dates, la campagne suit (sauf période modifiée à la main). Un satellite nommé sans son numéro (« UMKA-1 », « ERMINAZ-2U devient AO-129 ») est cherché dans le catalogue, sinon choisi une fois dans la confirmation et retenu.
- **Orbites › Traces** (curseur dans la barre d'options, et dans **Sked › Réglages du globe**) : les traces des satellites de discrètes à **très marquées** — plus épaisses, plus opaques, détourées (sombre sur dessin animé, réaliste et carte ancienne, clair sur les rendus sombres). Globe (orbites, trace au sol vue du satellite), carte 2D, ciel du passage, globe et carte du Sked ; devant toujours vif, derrière plus pâle. Plus marquées qu'avant d'origine ; gardé dans ce navigateur.
- **Orbites › 📺 ISS en direct** : la **vidéo en direct de l'ISS** (NASA, via YouTube ; Internet nécessaire) dans une fenêtre déplaçable, avec sa position calculée par SatMe (mer ou pays survolé, altitude, vitesse, jour ou nuit dessous) et **Voir sur le globe** (la *Vue du satellite* à côté de la vraie). source NASA automatique (le direct du jour retrouvé par SatMe-PC), ⚙ pour une autre ; en tuile dans un coin du *Tableau* (Vitrine).
- **Orbites › Tableau** : les fiches des satellites (photo et faits, tour à tour) ne sont plus coupées, dans tous les thèmes et à toutes les tailles d'écran : un coin trop plein est un peu réduit, la photo est montrée entière (jamais recadrée), l'anecdote passe sous la photo ; la fiche ne s'efface qu'en dernier recours. La photo de la fiche de l'onglet Orbites est aussi montrée entière.
- **Activer l'auto-pilote** (Agenda › Activités à venir) : la campagne part de l'**élévation la plus basse du rotor** (Réglages › Poste et rotor) ou de l'élévation minimale de la station si elle est plus haute ; **Élévation minimale** modifiable dans la confirmation, la liste des passages suit.

### en
- **Orbites full screen**: the logo and "SatMe" top left (bottom left in Classique radioamateur).
- **DATV › Pointage** (button at the top of the tab, or `pc?pointage=1` on the phone): the **MER very large**, the D margin, the **trend** (▲ better / ▼ worse than 5 s ago), the **best** (reset), the level, a 2-minute curve and a **tone whose pitch follows the MER**; « DÉCROCHÉ depuis… » in red if the lock goes. Reminder: the beacon alone is the reference, one touch-up at a time.
- **DATV › Quality › History**: MER, D margin and level over 10 min to 24 h, the time unlocked in red, marks (station changed, stop, LO calibrated, **Marquer une retouche**).
- **Signal level** estimated from the MiniTiouner's AGCs (≈ dBm, MiniTioune / Portsdown scale after BATC's measurements): good from −70 to −35, too weak or too strong said plainly.
- **Calage auto sur la balise** (Reception card, unticked by default): the LNB's real LO and its drift measured on the beacon, always shown; ticked, taken at the next tuning.
- **Mon spectre — SDR on the same LNB** (unticked by default): the SDR tab's receiver (RTL-SDR, Pluto, bladeRF, SatMe-Node) on a splitter sweeps the wide band, on the ground station's scale; a click tunes the MiniTiouner. Warns when the beacon is not in it.
- **LongMynd settings and decoding**: search width (`-S`), wait without packets (`-r`), passed to the SatMe-Node; **tolerant decoding** for damaged streams.
- **DATV › Subtitles › Spoken translation** (off by default): each received over's translation said by the voice-to-text synthetic voice, with its own voice, volume and the **original sound lowered** while it speaks (adjustable). Never over itself: only the latest over waits, nothing more than 20 s late. It plays where the DATV sound plays (this PC's speaker, the browser listening), through a SatMe-Node too; with no voice installed, the card leads to the installation.
- **Subtitle display**: width, number of lines (1 to 4), line length (≈ words per cue), size (auto or fixed), duration, and **More text** (4 lines, 90 % wide, the previous cues kept above). Instant preview, set per browser, nothing is ever cut off.
- **Summary** of the station received (and the previous ones): callsigns heard, read in the picture or in the service name, reports (5/9, MER, margin), locators, gear named, frequencies, key sentences, in your language. Made on the PC without Internet; **Copy** and **Log the QSO** (fills the log without saving).
- **Sked › Carte / Globe**: the common window also replayed on the Orbites tab's **globe**, with all its looks (cartoon, realistic, hologram…) and settings (camera, details, cities, names, landmarks, their size and families) under **Réglages du globe**. Both stations named, the satellite's track, the part you both see in green, its footprint; the slider and ▶ drive it. Drag to turn, wheel to zoom, double-click to frame it again. The choice is kept; the map stays the default.
- **Télémesure › Images SSDV**: a picture sent again over **several passes** (same callsign, same number, within 3 days) is **completed** with the new packets; "N/M packets, X %", a **strip coloured by pass** (grey = missing) and the list of passes. Packets are kept in `telemesure/ssdv/paquets` (a picture under way survives a restart, duplicates dropped); once complete it joins the gallery. Under way: **⤓ PNG / JPEG** or **Abandonner**. **Importer des paquets…**: raw packets, KISS or the frame log of a missed pass. In the demo: two pictures made over two passes.
- **SDR › satellite scale** between the spectrum and the waterfall: every known downlink at the frequency heard here (Doppler and correction included), green above the horizon, white rising within 5 min, grey below; linear transponders as bands following the Doppler. Click a name: follow that transmitter; inside a band: tune there; elsewhere: a fixed frequency. Wheel on the passband: fine tuning (Alt: faster); **Ctrl + wheel** on a satellite: its manual correction. Tooltip: mode, Doppler, el/az, AOS/LOS.
- **Orbites › Passage › Vue du satellite** (seen from the satellite; a new camera, also in the Sked's *Réglages du globe* and in the Journal): the Earth seen from above, straight below the satellite — its footprint (the radio horizon), your station and the sked's other station, the log's contacts when shown, the day / night line, its ground track ahead and behind, the cities and landmarks. Beside it: altitude, footprint radius, day or night below it, « your station: in view, X km from the edge ». Drag to look around, back by itself or on a double-click; the Hauteur or the wheel bring it nearer, down to the satellite. In *Vitrine*, the globe goes there 30 s every 90 s.
- **Converters and transverters** (Réglages › Poste et rotor): a table of bands — RF from… to…, LO, inverted, and in front of what (SDR dongle, CAT receive, CAT transmit); the first matching line wins. QO-100 presets (9750 LNB, down-converter to 2 m / 70 cm, 2400 uplink from 432 or 144) and 28 MHz IF transverters; the previous setting kept at the top. With no line nothing is sent to the rig and the status bar says so in yellow; hovering a frequency shows the IF sent.
- **Calibrer la clé SDR…**: on a QO-100 beacon, a GSM base station's FCCH tone or a known frequency, SatMe finds the peak, gives the error in ppm (saved in one click) and, behind an LNB, its real oscillator. **Aligner…** (cockpit): the downlink on the beacon, then the uplink with your own dots (you key the transmitter yourself), kept per satellite.
- **Agenda › Activités à venir › Activer l'auto-pilote**: on each SSTV activity, one click, a confirmation (satellite, frequency, mode, period, useful passes, default chain; receive only, nothing is transmitted) and the campaign is created and switched on, the auto-pilot turned on if it was off. The card then says "Auto-pilote actif ✓" with the next pass to be received, **Suspendre** (pause) and **Voir la campagne**; if the activity's dates change, the campaign follows (unless its period was edited by hand). A satellite named without its number ("UMKA-1", "ERMINAZ-2U becomes AO-129") is looked up in the catalogue, otherwise picked once in the confirmation and remembered.
- **Orbites › Traces** (slider in the options bar, and in **Sked › Réglages du globe**): the satellites' tracks from discreet to **very marked** — thicker, more opaque, outlined (dark on the cartoon, realistic and old-map looks, light on the dark ones). Globe (orbits, ground track seen from the satellite), 2D map, the pass's sky, the Sked's globe and map; ahead still vivid, behind paler. Stronger than before by default; kept in this browser.
- **Orbites › 📺 ISS en direct**: the **ISS's live video** (NASA, via YouTube; needs the Internet) in a movable window, with its position worked out by SatMe (sea or country below, altitude, speed, day or night under it) and **Voir sur le globe** (the *Vue du satellite* beside the real one). automatic NASA source (today's live looked up by SatMe-PC), ⚙ for another one; as a corner tile on the *Tableau* (Vitrine).
- **Orbites › Tableau**: the satellites' cards (photo and facts, one after another) are no longer cut off, in every theme and at every screen size: a corner too full is made a little smaller, the photo is shown whole (never cropped), the story goes under the photo; the card is left out only as a last resort. The Orbites tab's card shows its photo whole too.
- **Activer l'auto-pilote** (Agenda › Activités à venir): the campaign starts from the **rotator's lowest elevation** (Réglages › Poste et rotor) or the station's minimum elevation when higher; **Élévation minimale** editable in the confirmation, the list of passes follows.

## 2026.10.32

### fr
- **QO-100, émission bande étroite** (cockpit › sous-onglet **Émission**) : l'**ADALM-PLUTO en direct sur 2,4 GHz** en BLU (micro du navigateur, ou une carte son du PC pour FT8 depuis WSJT-X), en **CW** (texte ou touche) — son oscillateur garé sous le transpondeur, sa **correction de fréquence** saisie ou mesurée sur sa propre descente, la montée liée au cockpit, le plan de bande AMSAT-DL appliqué par mode. PTT tenu (ou maintenu pour FT8) avec chien de garde 2 s et minuterie, **essai sur charge** (−40 dB) à chaque démarrage, plafond de gain, **coupure immédiate si LEILA** est entendue, « ton signal vs balise » pendant l'émission.
- **Profils de montée Fixe / Portable** : chaîne, antenne (parabole 80 cm + POTY, parabole 35 cm, hélice 20 tours), ampli et câble → **PIRE estimée et puissance conseillée** sous la balise (ordre de grandeur), et **Voir au Montage** (hélice 2,4 GHz et parabole 35 cm ajoutées au catalogue).
- **IC-9700 + convertisseur sur QO-100** : PTT refusé sur une balise, relâché si LEILA est entendue, niveau comparé à la balise, et l'**OL du convertisseur mesuré sur sa propre descente**.
- **Polarisation d'antenne** (nouveau module) : un boîtier ESP32 en USB commande un commutateur WiMo — V, H, circulaire droite, circulaire gauche. Réglages › Poste et rotor › Polarisation d'antenne : le port, **Tester la liaison**, ce qui est équipé en UHF et en VHF (WiMo ou relais H/V), droite et gauche inversées si le câblage le veut. Rien n'est ouvert tant qu'aucun port n'est choisi ; le firmware et le schéma sont dans `materiel/esp32-polarisation`.
- **Suivi › Polarisation** : les boutons V / H / D / G, l'état réel (y compris « manuel (sélecteur) » et « bloqué : émission »), et la polarisation de chaque satellite **à l'AOS** (fixe, ou la meilleure connue). Le sélecteur en façade garde le dernier mot, et jamais de commutation sur l'antenne qui émet.
- **Sonder H/V** : en AUTO, l'antenne de réception essaie H puis V, mesurés au S-mètre du poste ou au SDR, et garde la meilleure (hystérésis). Chaque mesure est gardée : par satellite et par élévation, SatMe apprend la meilleure polarisation et en démarre le passage suivant.
- **ESP32 virtuel** : les mêmes règles sans le boîtier, sa façade à l'écran et un passage simulé, pour tout essayer avant de souder.
- **Démonstration : `satme --demo`.** Une station fictive (indicatif QF4DMO, tous les indicatifs en série Q, attribuée à personne) avec un carnet, un journal de passages et des images SSTV inventés mais crédibles, toujours aux dates des derniers jours ; poste, rotor, SDR, avions, navires et une mire SSTV virtuels. Rien de réel n'est ouvert, rien n'émet, rien n'est envoyé sous l'indicatif fictif ; un bandeau « DÉMO — données fictives » et son bouton **Remettre à neuf** sur chaque page. Dossier à part (`~/.satme-demo`), marche hors ligne.
- **Sur une VM ou un Raspberry Pi** : l'archive `satme-demo.tar.gz` et son `installe-demo.sh` (essai à sec avec `--sec`) installent le service `satme-demo` (port 8090, ouvert au réseau avec mot de passe).
- **Journal, vidéo 16:9 : la légende du bas ne prend plus que 19 % de l'image** (28 % au plus avec les plus grands textes, contre près de la moitié avant) : trois colonnes — le satellite, la date et l'heure, le pointage à gauche ; AOS, RX, AOS · TCA · LOS et S-mètre au milieu ; la marque, le passage et les images reçues, la station à droite. La vue (ciel, carte, globe) gagne la place, et l'aperçu de *Fabriquer* montre exactement le même cadre.
- **DATV, sous-titres comme à la télé** : chaque passage découpé en sous-titres de deux lignes au plus (par phrase), chacun affiché un temps proportionnel à sa longueur, la traduction dessous ; **plus rien de coupé** — la police s'ajuste à l'image (plein écran compris) jusqu'à tout faire tenir, avec un minimum lisible.
- **DATV, indicatifs lus dans l'image** : ils suivent la station — rangés au changement de fréquence ou de service et au décrochage, et après 5 min sans être relus ; « vu à l'instant » ou « vu il y a 3 min », les anciens à part sous **Vus avant**.
- **DATV, listes de textes le plus récent en haut** : passages sous-titrés et traduits, salon de la bande large, chat de la carte de visite et du flux émis — plus besoin de faire défiler, l'historique reste dessous.
- **DATV, réception plus sûre** : **Essayer aussi** d'autres débits de symboles (LongMynd les essaie à tour de rôle, plus longtemps pour les bas débits) ; **Relance automatique** de LongMynd s'il s'arrête seul en pleine réception, et du décodeur si l'image fige alors que le flux passe ; la marge s'appelle **Marge (D)** comme sur Ryde.

### en
- **QO-100, narrow-band transmitting** (cockpit › **Émission** sub-tab): the **ADALM-PLUTO straight on 2.4 GHz** in SSB (the browser's microphone, or a sound card of the PC for FT8 from WSJT-X), in **CW** (text or key) — its oscillator parked below the transponder, its **frequency correction** typed or measured on its own downlink, the uplink tied to the cockpit, the AMSAT-DL band plan applied per mode. PTT held (or latched for FT8) with a 2 s watchdog and a time-out, **dummy load** (−40 dB) at every start, a gain cap, **cut at once when LEILA** is heard, "your signal vs the beacon" while transmitting.
- **Fixed / Portable uplink profiles**: chain, antenna (80 cm dish + POTY, 35 cm dish, 20-turn helix), amplifier and cable → **estimated EIRP and suggested power** under the beacon (order of magnitude), and **Voir au Montage** (2.4 GHz helix and 35 cm dish added to the catalogue).
- **IC-9700 + up-converter on QO-100**: PTT refused on a beacon, released when LEILA is heard, level compared with the beacon, and the **converter's LO measured on its own downlink**.
- **Antenna polarisation** (new module): an ESP32 box on USB drives a WiMo switch — V, H, right-hand circular, left-hand circular. Réglages › Poste et rotor › Polarisation d'antenne: the port, **Tester la liaison** (test the link), what is fitted on UHF and VHF (WiMo or an H/V relay), right and left swapped if the wiring wants it. Nothing is opened until a port is chosen; the firmware and the drawing are in `materiel/esp32-polarisation`.
- **Suivi › Polarisation**: the V / H / D / G buttons, the real state (including « manuel (sélecteur) » and « bloqué : émission »), and each satellite's polarisation **at AOS** (fixed, or the best known). The front panel selector has the last word, and never a switch on the antenna that transmits.
- **Sonder H/V** (probe H/V): in AUTO, the receiving antenna tries H then V, measured by the rig's S-meter or the SDR, and keeps the best (hysteresis). Every measurement is kept: per satellite and elevation, SatMe learns the best polarisation and starts the next pass with it.
- **Virtual ESP32**: the same rules without the box, its front panel on screen and a simulated pass, to try everything before soldering.
- **Demonstration: `satme --demo`.** A made-up station (callsign QF4DMO, every callsign in the Q series, nobody's) with an invented but believable log, pass journal and SSTV pictures, always dated in the last few days; virtual rig, rotator, SDR, aircraft, ships and an SSTV test card. Nothing real is opened, nothing transmits, nothing is sent under the made-up callsign; a « DÉMO — données fictives » band and its **Remettre à neuf** button on every page. A folder of its own (`~/.satme-demo`), works offline.
- **On a VM or a Raspberry Pi**: the `satme-demo.tar.gz` archive and its `installe-demo.sh` (dry run with `--sec`) install the `satme-demo` service (port 8090, open to the network with a password).
- **Journal, 16:9 video: the caption at the bottom now takes 19 % of the picture** (28 % at most with the largest texts, against nearly half before): three columns — the satellite, date and time, pointing on the left; AOS, RX, AOS · TCA · LOS and S-meter in the middle; the mark, the pass and pictures received, the station on the right. The view (sky, map, globe) gets the room, and the *Fabriquer* preview shows exactly the same frame.
- **DATV, TV-style subtitles**: each over cut into cues of two lines at most (by sentence), each shown for a time proportional to its length, the translation under it; **nothing cut off any more** — the font fits the picture (full screen too) until everything shows, with a readable minimum.
- **DATV, callsigns read in the picture**: they follow the station — put away on a new frequency or service and on a lost lock, and after 5 min without being read again; "seen just now" or "seen 3 min ago", older ones apart under **Vus avant**.
- **DATV, text lists latest on top**: subtitled and translated overs, the wide-band chat, the station card's and transmitted stream's chat — no more scrolling, the history stays below.
- **DATV, steadier reception**: **Essayer aussi** other symbol rates (LongMynd tries them in turn, longer for low rates); **automatic restart** of LongMynd when it stops by itself mid-reception, and of the decoder when the picture freezes while the stream flows; the margin is called **Marge (D)** as on Ryde.

## 2026.10.31

### fr
- **Journal : un seul éditeur, pour un passage comme pour plusieurs.** Une session regroupe des passages ; un passage ouvert, c'est une session d'un passage. Même aperçu, mêmes onglets (**Moments**, **Options**, **Fabriquer**, **Ajouter**), mêmes réglages, mêmes fichiers. Le ciel et la carte montrent les passages l'un après l'autre, le globe tous ensemble. **🌍 Plusieurs passages** en tête d'un passage, **Ouvrir seul** depuis plusieurs.
- **Format écran 16:9 ou téléphone 9:16** : le cadre est dessiné sur l'aperçu (*Fabriquer*) ; l'**image SSTV** et les **incrustations** se placent à la souris ou au doigt, et la vidéo, le GIF et l'image les reprennent à la même place.
- **Commencer avant l'AOS, finir après le LOS** (0 à 30 min) : on voit arriver le satellite, sur la page comme dans les fichiers.
- **Deux zooms** (de loin hors des passages, de près pendant), la caméra passe de l'un à l'autre en douceur ; **satellites en plus** (favoris, avec leurs taches au sol en option) ; la lecture ralentit à l'approche d'un passage ou d'une image.
- **L'aperçu est le fichier** : la page joue aux vitesses de la vidéo (×20 dans un passage, temps réel avec le son là où un contact est entendu ; vitesse ×1 pour écouter tout le passage) et montre ce qui est coché pour la vidéo (RX, S-mètre, fiche du correspondant, cartes d'ouverture, du passage suivant, mosaïque).
- **Exports** d'un passage ou de plusieurs : vidéo MP4 avec le son des contacts, GIF animé, image du moment, son WAV (bout à bout), fichiers des passages dans un ZIP.
- **Orbites toujours à l'heure présente** : en ouvrant l'onglet ou le tableau, on est à ×1, jamais en pause ni sur un passage rejoué. La vue **Passage** suit **en direct** le passage en cours, sinon le prochain avec son compte à rebours (**▶ Rejouer en accéléré** reste à un clic).
- **⛶ Tableau** : Orbites en plein écran, six **thèmes** — *Dessin animé* (le globe tourne seul), *Classique radioamateur* (carte à plat, ligne grise), *Nuit*, *Rétro*, *Pédagogique* (bulles qui viennent une à une, fiches, « Le savais-tu ? ») et **Vitrine** pour un stand ou un forum des associations (grandes cartes qui tournent seules : « l'ISS passe au-dessus de nous dans 12 min — venez écouter ! », photo d'un satellite, le radioamateur en quatre phrases, le club et son lien en QR code). Panneaux au choix : heure, prochains passages, satellite choisi, station, lever et coucher du Soleil, **Lune**, météo spatiale, bandeau.
- **Fiches des satellites avec une vraie photo** (sous la liste d'Orbites, panneau du tableau) : pays, lancement, mission, orbite, anecdote, fréquences, résumé Wikipédia ; photo libre (auteur et licence affichés) demandée une fois à Wikimedia Commons puis gardée, un dessin sans Internet.
- **Près de 250 monuments**, dessinés selon leur famille : *aucun / principaux / tous*, **Types ▾** et **Taille** (petite à très grande).
- **Suivi à ta façon** (souris ou doigt) : panneaux **repliables**, **poignées** pour élargir les colonnes et grandir le ciel / la carte / le globe, *Fréquences* en **compact**, **⊞ Disposition**.
- **Modules** (Réglages › Modules) : coche ce qui t'intéresse — QO-100, DATV, SSTV, SDR, FT8, APRS, METEOR, avions, navires… — ou prends un profil. Un module décoché quitte le menu, ses réglages et la barre d'état. À la mise à jour, tout reste coché.
- **Nouvelle version** : sa fenêtre s'ouvre d'elle-même, une fois par version. **Portable** s'affiche dans le bandeau quand SatMe-PC tourne depuis son dossier `donnees`. Le bandeau de l'**auto-pilote SSTV** compte l'AOS et le LOS à la seconde.

### en
- **Journal: one editor, for one pass as for several.** A session groups passes; an opened pass is a session of one pass. The same preview, the same tabs (**Moments**, **Options**, **Fabriquer**, **Ajouter**), the same settings, the same files. The sky and the map show the passes one after the other, the globe all together. **🌍 Plusieurs passages** at the top of a pass, **Ouvrir seul** (open alone) from several.
- **Screen 16:9 or phone 9:16 format**: the frame is drawn over the preview (*Fabriquer*); the **SSTV picture** and the **overlays** are placed with the mouse or a finger, and the video, the GIF and the picture keep them in the same place.
- **Start before the AOS, end after the LOS** (0 to 30 min): you see the satellite coming, on the page as in the files.
- **Two zooms** (from afar outside the passes, near in a pass), the camera eases from one to the other; **more satellites** (favourites, their footprints as an option); the replay slows down as a pass or a picture comes near.
- **The preview is the file**: the page plays at the video's speeds (×20 in a pass, real time with the sound where a contact is heard; speed ×1 to hear the whole pass) and shows what is ticked for the video (RX, S-meter, the contact's record, opening card, next pass's card, mosaic).
- **Exports** of one pass or several: MP4 video with the contacts' sound, animated GIF, picture of the moment, WAV sound (end to end), the passes' files in a ZIP.
- **Orbites always at the present time**: opening the tab or the dashboard is at ×1, never paused nor on a replayed pass. The **Passage** view follows the pass under way **live**, else the next one with its countdown (**▶ Rejouer en accéléré** is a click away).
- **⛶ Tableau**: Orbites full screen, six **looks** — *Dessin animé* (the globe turns by itself), *Classique radioamateur* (flat map, grey line), *Nuit*, *Rétro*, *Pédagogique* (bubbles coming one at a time, cards, "did you know?") and **Vitrine** for a stand or a club fair (big cards turning by themselves: "the ISS passes over us in 12 min — come and listen!", a satellite's photo, amateur radio in four lines, the club and its link as a QR code). Panels as you choose: time, next passes, chosen satellite, station, sunrise and sunset, the **Moon**, space weather, ticker.
- **Satellite cards with a real photo** (under Orbites' list, a dashboard panel): country, launch, mission, orbit, a story, frequencies, Wikipedia summary; a free photo (author and licence shown) asked once from Wikimedia Commons then kept, a drawing without the Internet.
- **Nearly 250 landmarks**, drawn by family: *none / main / all*, **Types ▾** and **Size** (small to very big).
- **Suivi your way** (mouse or finger): panels **fold**, **handles** widen the columns and make the sky / map / globe bigger, *Fréquences* **compact**, **⊞ Disposition**.
- **Modules** (Réglages › Modules): tick what interests you — QO-100, DATV, SSTV, SDR, FT8, APRS, METEOR, planes, ships… — or pick a profile. A module unticked leaves the menu, its settings and the status bar. On updating, everything stays ticked.
- **New version**: its window opens by itself, once per version. **Portable** shows in the header when SatMe-PC runs from its `donnees` folder. The **SSTV auto-pilot** banner counts the AOS and LOS down every second.

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
