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
| Admin | `/admin` | privé : cacher, remontrer, supprimer ce que SatMe a publié ; relire les éléments et Wavelog |

Dans le menu, des emplacements grisés « bientôt » attendent la phase 2 :
DX cluster, Bandes, Balises, WSPR, Ouvertures, Station.

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
- **Satellites** : `sat/CatalogueSite.kt` lit le bulletin AMSAT (JSON et TLE,
  le plus frais par satellite, `FusionGp`, `LectureElements`) par le serveur GP
  de SatMe, toutes les 6 h, gardé sur disque ; passages par `PassPredictor`.
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
demandé à Internet), `cookies_securises = false` (HTTP local).

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
Captures de SatMe : dépôt SatMe, © F4IOZ. Globes : three.js et satellite.js
(MIT), traits de côte Natural Earth et relief ETOPO1 (domaine public),
drapeaux Twemoji (CC BY 4.0), comme dans SatMe-PC. Fond de carte du Sked ©
les contributeurs d'OpenStreetMap (tuiles demandées par le site et gardées
un mois).
