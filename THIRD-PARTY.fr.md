# Composants tiers

*[English version](THIRD-PARTY.md)*

SatMe est distribué sous **GNU General Public License version 2** (voir
`LICENSE`). Le code propre au projet est licencié **GPL-2.0-ou-ultérieure** :
cela ne change rien aujourd'hui, mais laisse la porte ouverte à la version 3
si la bibliothèque de prédiction venait à changer. Ce choix n'en est pas tout à fait un : il découle de la
bibliothèque de prédiction orbitale, détaillée ci-dessous.

---

## Pourquoi la GPL

`predict4java`, qui calcule les passages, est un portage Java du cœur de
**PREDICT**, écrit par John A. Magliacane (KD2BD), lui-même fondé sur la
traduction en C de Neoklis Kyriazis (5B4AZ) publiée sous GNU GPL en 2002. Les
modèles SGP4/SDP4 sous-jacents sont du Dr T. S. Kelso, versés au domaine
public.

L'artefact employé — `com.github.davidmoten:predict4java:1.3.1` — déclare la
**GPL version 2** dans son descripteur Maven. La GPL étant à réciprocité,
l'œuvre combinée qu'est SatMe se distribue sous la même licence.

**Un point à connaître si la licence devait changer un jour.** Le dépôt amont
`uk.me.g4dpz/predict4java` a été relicencié en MIT en 2026. Passer à cet
artefact rendrait le choix de licence libre. Mais ce code descend d'une lignée
GPL, et un relicenciement aval ne purge pas nécessairement les droits des
auteurs antérieurs. Rester en GPL est la position défendable ; elle est aussi
celle de la tradition du logiciel radioamateur.

---

## Bibliothèques employées

| Composant | Licence | Usage dans SatMe |
|---|---|---|
| `com.github.davidmoten:predict4java` | GPL-2.0 | Prédiction des passages, SGP4/SDP4, Doppler |
| LAME (`libandroidlame.so`, embarqué) | LGPL-2.1 | Encodage MP3 des enregistrements |
| `com.naman14.androidlame` (enveloppe Java, dans `src/main/java`) | LGPL-2.1 | Interface Java vers LAME |
| `com.google.zxing:core` | Apache-2.0 | Génération des QR codes |
| `com.squareup.okhttp3:okhttp` | Apache-2.0 | Requêtes réseau |
| `org.apache.pdfbox:pdfbox` (avec `fontbox`, `pdfbox-io` ; `commons-logging`) | Apache-2.0 | SatMe-PC seulement : le rapport d'événement SSTV dessiné en PDF |
| Polices d'affichage de la planche SSTV : Orbitron Black, Audiowide, Exo 2 Black Italic, Russo One, Michroma, Bungee, Monoton (`moteur/src/main/resources/fr/f4ioz/satcombo/sstv/polices/`, chacune avec son `*-OFL.txt`) | SIL Open Font License 1.1 | Les titres et textes stylés de la planche, dessinés par le téléphone et le PC, servis à la page de la planche (voir plus bas) |
| DejaVu Sans, normale et grasse (`pc/src/main/resources/…/polices/`, licence à côté) | Licences Bitstream Vera et Arev ; modifications DejaVu dans le domaine public | SatMe-PC seulement : les lettres et symboles du rapport PDF, incluses dedans |
| `com.github.mik3y:usb-serial-for-android` | MIT | Ponts série USB (CAT, rotor) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | Interface et cycle de vie |
| AndroidX Media3 (ExoPlayer) | Apache-2.0 | DATV depuis un SatMe-Node, sur le téléphone |
| Material Symbols (dessins, dans la page de SatMe-PC) | Apache-2.0 | Icônes des onglets de SatMe-PC, celles du téléphone |
| `com.google.android.gms:play-services-code-scanner` | Propriétaire Google | Scan du QR code de SatMe-PC (sans permission caméra) |
| LongMynd (BATC), lancé comme programme à part, non inclus | GPL-3.0 | Pilotage du MiniTiouner (DATV), SatMe-PC et SatMe-Node |
| FFmpeg (`ffmpeg`, `ffplay`), programmes à part, joints au seul paquet Windows | GPL-3.0 | Vidéos, GIF, images et son DATV ; construction BtbN/FFmpeg-Builds, sources indiquées dans `outils/SOURCES.txt` |
| rtl-sdr (`rtl_tcp`, `rtl_test`), programmes à part, joints au seul paquet Windows | GPL-2.0 | Clé RTL-SDR ; github.com/rtlsdrblog/rtl-sdr-blog |
| Java (Eclipse Temurin), joint au seul paquet Windows | GPL-2.0 avec exception « Classpath » | Fait tourner SatMe-PC |
| `com.google.android.gms:play-services-location` | Propriétaire Google | Position GPS |
| `com.google.android.play:app-update-ktx` | Propriétaire Google | Détection de mise à jour |
| JUnit, Robolectric (essais seulement) | EPL-1.0, Apache-2.0 | Bancs d'essai |
| `ft8_lib` de Kārlis Goba (tables du code correcteur, recopiées) | MIT | Décodage FT8 et FT4 |
| Police Twemoji Country Flags (`drapeaux.woff2`, de country-flag-emoji-polyfill 0.1.10 ; dessins des drapeaux de Twemoji, Twitter, Inc. et contributeurs ; `drapeaux-LICENCE.txt` à côté) et ses drapeaux dessinés en images (`pc/src/main/resources/…/pc/drapeaux/drapeaux.png`, faite par `tools/drapeaux/genere-atlas.mjs`, `LICENCE.txt` à côté) | CC BY 4.0 | Les drapeaux des pays dans les pages ; les mêmes dans les vidéos et images de SatMe-PC (journal : cadre FT4/FT8, capitales du globe) |

**Sur LAME et la LGPL.** La bibliothèque native est embarquée en tant que
`.so` distinct, non fusionné au reste de l'application. C'est ce qui permet à
un utilisateur de la remplacer par une version modifiée, condition posée par
la LGPL. Cette séparation doit être préservée.

**Sur les tables LDPC.** Le fichier `domain/LdpcTables.kt` reprend les tables
du code correcteur (174, 91) de `ft8_lib`, sous licence MIT. Il porte sa propre
mention de provenance et **n'est pas couvert par le copyright de SatMe** : la
licence MIT est compatible avec la GPL, mais son auteur doit rester nommé.

**Sur les polices de la planche.** Elles sont livrées sans modification, chacune
avec son texte OFL : Orbitron Black vient du dépôt de la League of Moveable
Type (github.com/theleagueof/orbitron, © The Orbitron Project Authors, Matt
McInerney, nom réservé « Orbitron ») ; Exo 2 Black Italic de
github.com/googlefonts/Exo-2.0 (© The Exo 2 Project Authors, Natanael Gama) ;
Audiowide (© Astigmatic, Brian J. Bonislawsky, nom réservé « Audiowide »),
Russo One (© Jovanny Lemonad, nom réservé « Russo »), Michroma (© The Michroma
Project Authors, Vernon Adams), Bungee (© The Bungee Project Authors, David
Jonathan Ross) et Monoton (© Vernon Adams, nom réservé « Monoton ») de
github.com/google/fonts. L'OFL permet de les livrer avec tout logiciel, GPL
compris, tant qu'elles ne sont pas vendues seules.

**Sur les composants Google.** Ils ne sont pas libres. Une distribution qui
voudrait s'en passer — F-Droid, par exemple — devrait remplacer la
localisation par le fournisseur de la plateforme et retirer la détection de
mise à jour.

---

## Algorithmes et données

**FT8 et FT4** sont implémentés d'après l'article *QEX* de K9AN, G4WJS et K1JT,
du domaine public. **Aucun code de WSJT-X n'est employé** : WSJT-X est sous GPL
et n'apparaît ni dans le code, ni dans les crédits de l'application.

**Les éléments orbitaux** proviennent de Celestrak et du bulletin AMSAT, aux
conditions de leurs éditeurs respectifs. La démonstration de SatMe-PC (`--demo`) emporte une copie
du bulletin AMSAT du 25 octobre 2026 (`pc/src/main/resources/…/demo/elements.json`) pour marcher
hors ligne ; elle est remplacée par un bulletin frais dès que le réseau répond.

**Les statuts de satellites** viennent de l'AMSAT Live OSCAR Status et de
SatNOGS.

**Les parcs POTA** proviennent de `pota.app` (liste des parcs) et de
`pota-map.fr` (contours des zones). Ce sont des points d'accès publics, sans
clé ni authentification. SatMe les interroge, ne les redistribue pas, et ne
contrôle ni leur disponibilité ni leurs conditions d'usage : quiconque
reprend ce code s'en remet aux règles de ces services.

---

## Images

La planche SSTV propose neuf fonds tirés d'images de la **NASA**, réduites à
la taille de la planche (2400 × 1697, ou 1600 × 1132 pour les plus récentes) et recompressées, dans
`moteur/src/main/resources/fr/f4ioz/satcombo/sstv/decors/`. Les images de la
NASA ne sont en général pas protégées par le droit d'auteur aux États-Unis
(NASA Images and Media Usage Guidelines, nasa.gov/nasa-brand-center/images-and-media) ;
elles sont créditées ici, et n'impliquent aucun soutien de la NASA. Seules
des images publiées par la NASA elle-même ont été retenues : aucune créditée à
un tiers seul.

| Fichier | Titre | Source | Crédit, licence |
|---|---|---|---|
| `aurore.jpg` | Aurore australe au-dessus de l'océan Indien, vue de l'ISS (iss066e146457, 18 février 2022) | https://images.nasa.gov/details/iss066e146457 | NASA (photo de l'équipage de l'ISS, JSC) — domaine public |
| `limbe.jpg` | La lueur de la Terre et l'aurore australe sur l'océan Indien (iss062e148363, 13 avril 2020) | https://images.nasa.gov/details/iss062e148363 | NASA (photo de l'équipage de l'ISS, JSC) — domaine public |
| `lever.jpg` | Un lever de soleil orbital sur le Pacifique, au nord d'Auckland (iss071e439624, 6 août 2024) | https://images.nasa.gov/details/iss071e439624 | NASA (photo de l'équipage de l'ISS, JSC) — domaine public |
| `lagune.jpg` | Hubble reveals heart of Lagoon Nebula (publiée le 22 septembre 2010) | https://images.nasa.gov/details/GSFC_20171208_Archive_e001955 | NASA, ESA (publication NASA Goddard) — domaine public selon les règles de la NASA |
| `iss_espace.jpg` | L'ISS sur fond d'horizon terrestre et de noir de l'espace, vue de Discovery après le désamarrage (s131e011050, 17 avril 2010) | https://images.nasa.gov/details/s131e011050 | NASA (photo de l'équipage de STS-131, JSC) — domaine public |
| `iss_terre.jpg` | L'ISS vue d'Atlantis après le désamarrage (s132e012208, 23 mai 2010) | https://images.nasa.gov/details/s132e012208 | NASA (photo de l'équipage de STS-132, JSC) — domaine public |
| `panneaux.jpg` | Ailes de panneaux solaires de l'ISS sur fond d'espace et d'horizon terrestre (s118e07242, 14 août 2007), le numéro de cliché du bas coupé | https://images.nasa.gov/details/s118e07242 | NASA (photo de l'équipage de STS-118, JSC) — domaine public |
| `nuit.jpg` | Aurore boréale et lumières des villes du Midwest américain, vues de l'ISS (iss029e012564, 29 septembre 2011) | https://images.nasa.gov/details/iss029e012564 | NASA (photo de l'équipage de l'Expédition 29, JSC) — domaine public |
| `papillon.jpg` | « Space Butterfly » : la nébuleuse W40 en infrarouge, télescope spatial Spitzer (PIA23121, 27 mars 2019) | https://images.nasa.gov/details/PIA23121 | NASA/JPL-Caltech — domaine public selon les règles de la NASA |

Les autres fonds (ciel étoilé, crépuscule, abysse, ardoise, nébuleuse, orbite) sont dessinés par SatMe.

---

## Le site web F4IOZ (`:site`)

| Composant ou données | Licence / conditions | Usage |
|---|---|---|
| Leaflet 1.9.4 (`site/src/main/resources/fr/f4ioz/site/static/vendor/leaflet/`, non modifié, `LICENSE.txt` à côté ; © Volodymyr Agafonkin, CloudMade) | BSD-2-Clause | Les cartes des pages HF |
| Tuiles OpenStreetMap (demandées par le site, gardées un mois, 120 par minute au plus, zoom ≤ 12, User-Agent du site) | © les contributeurs d'OpenStreetMap, ODbL ; politique d'usage des tuiles de l'OSMF | Fond des cartes, attribution sur chaque carte |
| Traits de côte Natural Earth (`land.json`, celui du téléphone) | Domaine public | La carte du monde des balises |
| Reports de réception PSKReporter (retrieve.pskreporter.info) | Conditions de Philip Gladstone : une requête toutes les cinq minutes au plus, `appcontact` fourni | DX cluster, carte des bandes |
| Données solaires HamQSL (N0NBH, `solarxml.php`) | Libres avec mention de HamQSL.com ; lues toutes les heures | Conditions, bandes |
| Modèle ionosphérique KC2G (prop.kc2g.com) | CC BY 4.0, attribué sur la carte | Contours MUF et foF2 |
| wspr.live (données WSPRnet, ClickHouse) | Libre, non commercial, résultats accessibles à tous ; 20 requêtes/min (le site en envoie ≤ 10) | Page WSPR, carte des bandes |
| Reverse Beacon Network (telnet et archives quotidiennes) | Libre ; connexion telnet avec un indicatif | Carte des bandes, onglet RBN de WSPR, Ouvertures |

Le code propre au site (pages, scripts, styles) est en GPL-2.0-ou-ultérieure, comme SatMe.

---

## Ce que la publication rend visible

Deux mécanismes de l'application reposaient sur la discrétion de la source, et
cessent d'en bénéficier :

Le **trousseau d'extensions** (`data/Extensions.kt`) contient ses mots-clés en
clair. Toute fonction qu'il protège devient accessible à qui lit le fichier.

La fonction **`estAuteur()`** teste si l'indicatif contient « F4IOZ ». Elle se
contourne en changeant son indicatif.

Ce sont des ralentisseurs, pas des serrures, et ils l'étaient déjà. Mais mieux
vaut le savoir avant de publier que de le découvrir après.

---

*Dernière vérification des licences : 24 septembre 2026.*
