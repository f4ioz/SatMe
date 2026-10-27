# Site F4IOZ (Kotlin)

Le site radioamateur de F4IOZ (f4ioz.fr), refait de zéro comme un module du
dépôt SatMe : `:site`, en Kotlin, bâti sur le moteur de SatMe (`:moteur`) et
sur rien d'autre de SatMe — ni poste, ni rotor, ni son, rien qui émette. Il
reprend l'allure de la refonte « SatMe » de l'ancien site (palette, rail à
gauche, cartes, marque F4IOZ, polices Noto servies par le site) et les outils
satellites de SatMe-PC, et reçoit ce que SatMe-PC publie (images SSTV,
planches, rapports, contacts, vidéos du journal).

## Ce qu'il contient

| Page | Adresse | D'où ça vient |
|---|---|---|
| Accueil | `/` | passages des 24 h depuis JN18FS (moteur SatMe), derniers QSO, dernier article, dernière image SSTV ou planche publiée, SatMe |
| Articles | `/articles`, `/articles/<année>/<slug>`, `/articles/feed.xml` | Markdown + en-tête YAML (mêmes adresses que l'ancien site), tags, pagination, flux Atom |
| Pages | `/<slug>` | `contenu/pages/<slug>.md` (« À propos »…) |
| Galeries | `/gallery`, `/gallery/sstv/<groupe>`, `/gallery/item/<id>`, `/gallery/album/<nom>` | publications de SatMe-PC (groupées par événement ou satellite), albums photo du dossier `contenu/galeries/` |
| Logbook | `/logbook` | Wavelog en lecture seule (cache, toutes les 15 min) + contacts publiés par SatMe ; filtre satellite / HF / VHF / indicatif |
| SatMe | `/satme` | la vitrine de SatMe (captures du dépôt) |
| Passages | `/sat/pass` | n'importe quel locator, favoris gardés dans le navigateur, trace dans le ciel, frise |
| Sked | `/sat/sked` | **l'onglet Sked de SatMe-PC** : fenêtres communes à deux stations, rejouées sur la carte ou le globe d'Orbites |
| Orbites | `/sat/orbites` | **l'onglet Orbites de SatMe-PC** : globe/carte/passage, six rendus, Tableau et ses thèmes jusqu'à la Vitrine, l'ISS en direct |
| Ciel | `/sat/radar` | le ciel d'une station en direct (satellite.js) |
| TLE | `/sat/tle`, `/sat/tle.txt` | les éléments AMSAT utilisés, leur âge, leur source |
| Grid | `/grid` | locator ↔ coordonnées, distance et azimut |
| DX cluster | `/dx` | reports PSKReporter en direct (tableau filtrable, carte des trajets), conditions HamQSL |
| Bandes | `/bands` | HamQSL expliqué (SFI, A, K, rayons X), jour/nuit au QTH, carte de propagation PSKReporter / WSPR / RBN, contours MUF/foF2 de KC2G, trajet court et long |
| Balises | `/beacons` | les 18 balises NCDXF/IARU : qui émet maintenant, ordre de passage, carte (calculé dans le navigateur) |
| WSPR | `/wspr` | activité des bandes (wspr.live ou RBN), empreinte d'un indicatif |
| Ouvertures | `/openings` | bande × heure UTC depuis le locator de la station, archives RBN ; où la région est entendue |
| Station | `/station` | **privé** : panadapter du Brick2 (openHPSDR, réception seule) ; les visiteurs voient « réservé » |
| Admin | `/admin` | privé : cacher, remontrer, supprimer ce que SatMe a publié ; relire les éléments et Wavelog |

Les anciennes adresses : `/sat/passes` → `/sat/sked`, `/sstv` → `/gallery`
(redirections 301) ; `/tm60louv`, `/cw`, `/activation`, `/login` répondent 410
(retirées).

## Architecture

- **Serveur HTTP** : celui du JDK (`com.sun.net.httpserver`), rien à ajouter ;
  un groupe borné de fils ; derrière nginx.
  `http/ServeurHttp.kt` : chemin vérifié (pas de `..`, de `\`, de `//`),
  adresse du visiteur lue dans X-Forwarded-For **seulement si la requête vient
  d'un proxy de `proxies`** (la dernière adresse qui n'est pas un proxy),
  HTTPS d'après X-Forwarded-Proto du même proxy, frein par adresse (300
  requêtes/min, plus serré pour les calculs de Sked, les tuiles, la connexion
  admin et la publication), en-têtes de sécurité, gzip, HEAD, fichiers par
  morceaux (`Range`).
- **Routeur et modules** : `Modules.kt` liste les pages ; chacune est un
  `ModuleSite` (`installe(routeur)`, ses `EntreeMenu`, ses chemins pour le
  sitemap). **Ajouter une page** (phase 2 : propagation HF, station SDR) :
  écrire un `ModuleSite`, l'ajouter à `Modules.liste`, retirer son emplacement
  de `Bientot.SLOTS`. La coque (rail, tiroir du téléphone, thème clair/sombre,
  horloge UTC, pied de page), le SEO, les en-têtes et les pages d'erreur
  viennent avec.
- **Gabarit** : `vue/Gabarit.kt` (head : titre, description, canonique, Open
  Graph, JSON-LD ; coque ; pied) ; tout texte venu d'ailleurs passe par
  `Html.h()`. Les styles et scripts du site sont dans
  `src/main/resources/fr/f4ioz/site/static/` (servis avec une version par
  empreinte, gardés un an par les navigateurs).
- **Onglets de SatMe-PC** : Orbites et Sked sont le **même code** que dans
  SatMe-PC — `OngletOrbites`, `OngletSked`, `OrbitesJs`, `IssDirectJs`,
  `GlobeDonnees` du moteur, assemblés au démarrage (`sat/AppliSatme.kt`) avec
  un petit hôte (`static/js/hote-satme.js`) qui donne ce qu'ils attendent de
  la page. Les routes qu'ils appellent (`api/orbites`, `api/sked`,
  `api/sked/trace`, `api/carte/traits`, `api/lib/…`, `journal/tuile`…) sont
  servies par `sat/ApiSat.kt` avec `ApiCalculs` du moteur. La vidéo du Sked
  et les fiches Wikipédia des satellites restent propres à SatMe-PC.
- **Satellites** : `sat/CatalogueSite.kt` lit les éléments **au seul serveur
  GP de SatMe** (`gp`, gp.f4ioz.fr par défaut ; `ServeurGp.adresses(…, seul =
  true)` du moteur : `/gp/amsat.json`, `/gp/amateur.json`, `/gp/satnogs.json`,
  `/gp/catnr/<n>.json` pour les satellites à images) — jamais CelesTrak, AMSAT
  ou SatNOGS directement —, toutes les 6 h, le plus frais par satellite
  (`FusionGp`), gardé sur disque. Un 403 ou un 429 met le serveur en pause une
  heure, puis deux… jusqu'à un jour (pause écrite sur disque) ; le disque sert
  pendant ce temps. User-Agent `f4ioz-site/<version> (+https://f4ioz.fr)`,
  pour que le serveur puisse l'autoriser. La page TLE nomme le serveur et
  l'âge de chaque élément. Passages par `PassPredictor`.
- **Propagation HF** (`hf/`) : `ApiHf` (les API, un frein par adresse pour
  chacune), `Amont` (le client HTTP, son User-Agent, un petit groupe de fils
  pour les téléchargements — une requête de visiteur n'attend jamais une
  source plus de quelques secondes) et `Memo` (une valeur par clé, une seule
  demande à la fois, la dernière bonne servie quand la source échoue, une
  pause après un refus, un nombre de clés borné). Sources : `PskReporter`,
  `Solaire` (HamQSL), `Kc2g`, `WsprLive`, `Rbn` (telnet), `RbnArchives`
  (archives quotidiennes, résumées sur disque). Les pages : `PagesHf.kt`,
  leurs scripts `static/js/{hf-commun,dx,bandes,balises,wspr,ouvertures}.js`
  (tout texte venu des sources posé en texte ou échappé), Leaflet 1.9.4 servi
  par le site, les tuiles OSM par `journal/tuile` (le site les demande et les
  garde un mois).
- **Station** (`station/`) : `HpsdrProtocole` (trames openHPSDR Protocol 1,
  **réception seule** : Metis 0x01, MOX toujours à 0, charge TX nulle, la
  fréquence dans le mot C0 = 0x02 que suit RX1 sur ce Brick2), `HpsdrDecouverte`,
  `HpsdrSession` (IQ conjugué : le spectre de cette radio arrive inversé ;
  FFT 2048, S-mètre ; fin 12 s après le dernier client, 15 min au plus),
  `HpsdrDemod` (USB/LSB/CW/AM/FM avec le DSP de SatMe), `ServeurWs` (le
  WebSocket `/ws/sdr/spectrum`, sur son propre port : le serveur HTTP du JDK
  ne sait pas passer une connexion en WebSocket ; Origin du site et session
  admin exigés, deux clients au plus).
- **Contenu** : Markdown rendu par le `Markdown` du moteur (tout est échappé,
  le HTML brut est ignoré ; images `![texte](/images/x.jpg)` du site ou https).
- **Publications** : `publication/` (stockage, vignettes, API), voir plus bas.
- **Sécurité** : CSP (scripts du site + nonce par réponse ; `'unsafe-inline'`
  seulement sur Orbites et Sked, dont le balisage a des gestionnaires en
  ligne ; un rapport HTML publié est servi en `sandbox`), `frame-ancestors
  'self'`, `X-Frame-Options`, `nosniff`, `Referrer-Policy`,
  `Permissions-Policy` (position seulement pour « Ma position » de Grid),
  `Cross-Origin-Opener-Policy`, HSTS quand nginx dit HTTPS ; pages privées en
  `no-store`.

## Configuration

Clés de la phase 2 (toutes facultatives) :

| Clé | Défaut | Rôle |
|---|---|---|
| `gp` | `https://gp.f4ioz.fr` | le serveur GP de SatMe, seule source des éléments (`aucun` : rien demandé, le disque seulement) |
| `gp.sources` | `amsat` | groupes lus : `amsat`, `amateur`, `satnogs`, `images` |
| `club` | vide | un club nommé au pied de page et à l'accueil |
| `hf.contact` | l'adresse publique | contact donné à PSKReporter (`appcontact`) et dans le User-Agent |
| `wspr.serveur` | `https://db1.wspr.live` | l'interface HTTP de wspr.live |
| `rbn.actif` | `true` | le flux telnet du RBN (ouvert à la demande, fermé 10 min après la dernière) |
| `rbn.indicatif` | `indicatif` | l'indicatif de connexion au RBN |
| `ouvertures.locators` | `locator` | les locators dont les archives RBN sont lues (4 au plus) — personne d'autre ne peut déclencher un téléchargement |
| `ouvertures.jours` | `14` | jours d'archives lus (1 à 30), gardés 32 jours |
| `station` | `false` | le panadapter du SDR de la station (seulement si le site tourne sur le réseau de la radio) |
| `station.hote` | vide | l'adresse de la radio ; vide : découverte par diffusion |
| `station.port` | `1024` | le port openHPSDR |
| `station.diffusion` | `true` | découverte en diffusion (255.255.255.255) |
| `station.ws_port` | `8002` | le port du WebSocket du panadapter (nginx : `location /ws/sdr/ { proxy_pass http://<site>:8002; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_set_header Host $host; }`) |
| `station.websdr` | vide | un récepteur public à proposer aux visiteurs (OpenWebRX…) |

Les sources et leurs règles : PSKReporter (une requête par mode toutes les
5 min au plus, toujours la même forme — 30 min, reports seuls —, `appcontact` ;
les fenêtres et bandes sont découpées ici ; au-delà de 10 000 reports, la
moitié la plus récente gardée et le reste échantillonné), HamQSL (relu toutes
les heures, mention HamQSL / N0NBH), KC2G (15 min, CC BY 4.0 sur la carte),
wspr.live (2 min, 10 requêtes/min pour tout le site, usage non commercial),
RBN (une connexion telnet, archives quotidiennes ~3 Mo pour les locators de
la station seulement), tuiles OSM (par le site, gardées un mois, attribution).

`/etc/f4ioz-site-kt/site.conf` (voir `deploiement/site.conf.exemple`), hors du
paquet et de Git : adresse publique, indicatif F4IOZ, locator JN18FS, fuseau
Europe/Paris, écoute (127.0.0.1:8001 par défaut), proxys de confiance,
dossiers des données et du contenu, Wavelog (adresse + clé **lecture seule**,
facultatif), empreinte du jeton de publication, mot de passe admin haché.

```
f4ioz-site jeton          # un jeton neuf (à coller dans SatMe-PC) et la ligne publication.jeton_sha256
f4ioz-site mot-de-passe   # la ligne admin.mot_de_passe (PBKDF2, 210 000 itérations)
```

Le contenu (`/var/lib/f4ioz-site-kt/contenu/`) :

```
articles/<année>/<slug>.md   # en-tête : title, date, tags, summary, hero_image, draft
pages/<slug>.md              # servie à /<slug>
galeries/<nom>/              # un album : ses photos .jpg/.png et un info.md facultatif (title, date, summary, texte)
images/…                     # les images des articles : /images/…
```

## API de publication (côté site)

HTTPS par nginx ; `Authorization: Bearer <jeton>`, comparé en temps constant
à son empreinte (dix jetons faux d'une adresse : un quart d'heure d'attente).

| Requête | Effet |
|---|---|
| `GET /api/publication/ping` | le jeton est bon : le site, l'indicatif, les types et leurs limites |
| `POST /api/publication/{type}` | le fichier en corps ; ses mots dans `X-SatMe-Meta` (JSON en base64url) : `satellite`, `utc`, `frequence`, `mode`, `passage`, `evenement`, `station`, `titre`, `description`, `cle` |
| `GET /api/publication[?type=]` | ce qui est publié |
| `DELETE /api/publication/{id}` ou `?type=…&cle=…` | retiré |

Types : `sstv-image` (PNG/JPEG/GIF, 12 Mo), `planche` (PNG/JPEG, 30 Mo),
`rapport` (PDF ou HTML, 40 Mo ; le HTML servi en bac à sable), `qso` (ADIF ou
JSON, 1 Mo), `journal-video` (MP4/WebM, `publication.video_max_mo`, écrit sur
disque au fil de l'eau). Le genre est lu dans les premiers octets, jamais
pris de l'expéditeur ; une image annonçant plus de 48 Mpx est refusée avant
d'être décodée ; les fichiers sont nommés par le site
(`publications/<id>/fichier.<ext>`, `vignette.jpg`, `meta.json`). L'id est le
début du SHA-256 du contenu : **le même contenu deux fois est une seule
publication** (200 au lieu de 201). Une nouvelle version d'une même `cle`
(une planche qui se remplit) remplace l'ancienne.

## Construire, essayer

```
./gradlew :site:test :site:installDist     # tests ; le programme dans site/build/install/f4ioz-site
./gradlew :site:siteTar                    # site/build/distributions/f4ioz-site-kt.tar.gz
site/build/install/f4ioz-site/bin/f4ioz-site --config essai.conf --port 8201
```

Un `essai.conf` minimal : `donnees`, `contenu`, `hors_ligne = true` (rien
demandé à Internet), `cookies_securises = false` (HTTP local), `gp = aucun`
(ne jamais faire interroger gp.f4ioz.fr par un essai).

## Déploiement : à côté de l'ancien site, puis bascule de nginx

Java : `openjdk-21-jre-headless` (17 suffit aussi).

1. **Construire** le paquet : `./gradlew :site:siteTar`.
2. **Copier** `f4ioz-site-kt.tar.gz` sur la LXC du site, puis :
   ```
   tar xzf f4ioz-site-kt.tar.gz && cd f4ioz-site-kt && sudo ./installe-site.sh
   ```
   Le script installe Java si besoin, crée l'utilisateur `f4ioz`, met le
   programme dans `/opt/f4ioz-site-kt/`, crée `/etc/f4ioz-site-kt/site.conf`
   (la première fois seulement) et `/var/lib/f4ioz-site-kt/`, installe et
   démarre `f4ioz-site-kt.service`. L'ancien site (uvicorn sur :8000) n'est
   pas touché.
3. **Configurer** `/etc/f4ioz-site-kt/site.conf` : `f4ioz-site jeton`,
   `f4ioz-site mot-de-passe`, la clé Wavelog **lecture seule**. Migrer le
   contenu : `content/articles/` → `contenu/articles/`, `content/pages/` →
   `contenu/pages/`, `content/gallery/<nom>/` → `contenu/galeries/<nom>/`,
   les images des articles dans `contenu/images/` (et leurs liens en
   `/images/…`). `sudo systemctl restart f4ioz-site-kt`.
4. **Aperçu** côte à côte, sur 127.0.0.1:8001 :
   `ssh -L 8001:127.0.0.1:8001 <lxc>` puis http://127.0.0.1:8001/. Régler
   SatMe-PC (Réglages › Site web) sur l'adresse de l'aperçu par le même
   tunnel pour essayer la publication.
5. **Bascule** : nginx est sur une autre machine du réseau. Dans `site.conf` :
   `ecoute = <adresse de la LXC>` (ou `0.0.0.0` avec un pare-feu qui n'ouvre
   :8001 qu'à nginx) et `proxies = <adresse de nginx>` ; redémarrer. Dans le
   site nginx de f4ioz.fr, changer l'amont `:8000` → `:8001` (en gardant
   `X-Forwarded-For $proxy_add_x_forwarded_for`, `X-Forwarded-Proto $scheme`,
   `Host`), et pour les vidéos `client_max_body_size 210m;` sur
   `/api/publication/` ; `nginx -t && systemctl reload nginx`.
6. Vérifier `https://f4ioz.fr/`, `/sat/orbites`, `/sitemap.xml`, puis
   « Tester la connexion » dans SatMe-PC avec `https://f4ioz.fr`.

**Retour arrière** : remettre `:8000` dans nginx et le recharger (l'ancien
site tourne toujours) ; pour une mise à jour ratée du nouveau seulement,
`sudo ./installe-site.sh --retour` remet la version précédente du programme.

## Licences

Code GPL-2.0-ou-ultérieure (celle de SatMe). Polices Noto Sans / Noto Sans
Mono : SIL OFL 1.1 (`static/fonts/OFL.txt`). Marque F4IOZ : dessin maison.
Captures de SatMe : dépôt SatMe, © F4IOZ. Leaflet 1.9.4 (BSD-2-Clause,
`static/vendor/leaflet/LICENSE.txt`). Données des pages HF : PSKReporter,
HamQSL / N0NBH, KC2G (CC BY 4.0), wspr.live / WSPRnet, Reverse Beacon Network,
chacune créditée sur sa page (voir `THIRD-PARTY.md`). Globes : three.js et satellite.js
(MIT), traits de côte Natural Earth et relief ETOPO1 (domaine public),
drapeaux Twemoji (CC BY 4.0), comme dans SatMe-PC. Fond de carte du Sked ©
les contributeurs d'OpenStreetMap (tuiles demandées par le site et gardées
un mois).
