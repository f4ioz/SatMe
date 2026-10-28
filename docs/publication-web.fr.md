# Publication web : SatMe-PC envoie vers votre site

*[English version](publication-web.md)*

SatMe-PC peut **pousser des éléments vers un site web extérieur** : images
SSTV, planches, rapports d'événement, contacts, vidéos du journal, et en
option des données en direct (auto-pilote, spectre QO-100, image DATV).
Votre site les reçoit **comme il veut** — un fichier PHP sur un hébergement
mutualisé, une extension WordPress, un petit serveur Python ou Node, n'importe
quoi qui sait répondre à une requête HTTP. Cette page décrit le réglage dans
SatMe-PC et **le format des envois (API v1)**, stable et versionné, avec des
récepteurs prêts à l'emploi.

Sans serveur du tout, SatMe-PC peut aussi **écrire dans un dossier** (voir
[Sans serveur : un dossier](#sans-serveur--un-dossier)).

C'est toujours SatMe-PC qui envoie : ça marche derrière la box de la maison,
et **rien n'est ouvert sur la station** (le site ne se connecte jamais au PC).

Sommaire : [Réglage](#réglage-dans-satme-pc) ·
[API v1](#lapi-v1) · [Types](#les-types) · [Réponses](#les-réponses) ·
[File et reprises](#la-file-et-les-reprises) · [Direct](#le-direct-facultatif) ·
[Sécurité](#sécurité) · [Exemples](#des-récepteurs-prêts-à-lemploi) ·
[Afficher](#afficher-ce-qui-est-reçu) · [Dossier](#sans-serveur--un-dossier)

---

## Réglage dans SatMe-PC

1. **Réglages › Modules** : cocher **Publication web**. Le module est
   **désactivé d'origine** et aucun profil ne l'active : tant qu'il est
   décoché, SatMe-PC n'envoie rien à personne (ni file, ni essai, ni direct)
   et n'affiche aucun bouton 🌐.
2. **Réglages › Services en ligne › Envoyer vers un site web** :
   - **Où envoyer** : **Adresse web (HTTPS)** ou **Dossier sur ce PC**.
   - **L'adresse** qui reçoit. Deux façons :
     - avec `{type}` dedans, remplacé par le type de chaque envoi :
       `https://example.org/api/satme/{type}` → `…/api/satme/sstv-image`,
       `…/api/satme/ping` ;
     - sans `{type}` : tout part à **cette même adresse** (style *webhook*),
       le type est dans l'en-tête `X-SatMe-Type` (et dans le champ `type` du
       formulaire) : `https://example.org/satme/recevoir.php`.
   - **Le jeton** (facultatif, conseillé) : une clé secrète que **vous**
     inventez (par exemple `openssl rand -hex 32`) et que votre site vérifie ;
     envoyé en `Authorization: Bearer <jeton>`. Il reste sur ce PC et n'est
     jamais réaffiché.
   - **Tester** : SatMe-PC envoie un `ping` (rien n'est publié). L'envoi n'est
     **prêt** qu'après un essai réussi ; une autre adresse, un autre jeton ou
     un autre dossier se testent à nouveau.
   - **Quoi envoyer** : pour chaque type **Sur clic** (le bouton 🌐 de chaque
     élément), **Automatique** (dès qu'il arrive) ou **Jamais**. **Tout
     commence à « Sur clic »** : rien ne part tout seul tant que vous ne
     choisissez pas « Automatique ».
3. Les boutons **🌐** apparaissent : visionneuse SSTV, lignes du carnet, page
   Planche (planche et rapport), vidéos faites par l'éditeur du journal.

`http://` (sans S) n'est accepté que vers cette machine ou le réseau local
(`127.0.0.1`, `192.168.…`, `10.…`, `172.16–31.…`, `….local`, un nom sans
point) ; ailleurs, `https://` est obligatoire. Les redirections ne sont pas
suivies : mettez l'adresse finale.

Le module `site` de ce dépôt (`site/`) est un exemple complet de récepteur
(galerie, carnet, bloc « en direct »), avec l'adresse
`https://<votre site>/api/publication/{type}`. Il n'est pas nécessaire : les
petits exemples plus bas suffisent pour commencer.

---

## L'API v1

Chaque envoi est **une requête `POST`** vers l'adresse réglée.

### En-têtes, toujours

| En-tête | Exemple | Sens |
|---|---|---|
| `User-Agent` | `SatMe-PC/2026.10.34 (+https://github.com/f4ioz/SatMe)` | le logiciel et sa version |
| `X-SatMe-Version` | `1` | la version du format ; un récepteur peut refuser une version qu'il ne connaît pas |
| `X-SatMe-Type` | `sstv-image` | le type de l'envoi (voir plus bas) |
| `X-SatMe-Id` | `0b6f7c1e-3d0a-4c9e-9a51-6f4f0c2b8e21` | identifiant unique **de cet envoi** : le même d'un essai à l'autre (voir [idempotence](#idempotence)) |
| `Authorization` | `Bearer 3f9a…` | seulement si un jeton est réglé |
| `Content-Type` | `multipart/form-data; boundary=…` ou `application/json; charset=utf-8` | selon le type |

La version ne change que si un récepteur écrit pour la v1 cesserait de
marcher : ajouter un type ou un champ ne la change pas. **Ignorez les champs
que vous ne connaissez pas.**

### Les médias : `multipart/form-data`

Les types `sstv-image`, `planche`, `rapport`, `qso`, `journal-video` (et les
images du direct) arrivent comme un formulaire HTML d'envoi de fichier, trois
champs dans cet ordre :

| Champ | Contenu |
|---|---|
| `type` | le type, en texte (`sstv-image`) — le même que `X-SatMe-Type` |
| `meta` | un JSON (`application/json`) : les mots qui vont avec (voir plus bas) |
| `fichier` | le fichier, avec son nom (`filename`) et son `Content-Type` |

En PHP : `$_POST['type']`, `json_decode($_POST['meta'], true)`,
`$_FILES['fichier']`. En Python : n'importe quel lecteur de multipart.

#### Le JSON `meta`

Toujours présents :

| Champ | Type | Exemple | Sens |
|---|---|---|---|
| `version` | nombre | `1` | version du format |
| `type` | texte | `"sstv-image"` | le type |
| `id` | texte | `"0b6f7c1e-…"` | le même que `X-SatMe-Id` |
| `cle` | texte | `"sstv:ISS_20261003_135119.png"` | **l'élément** côté SatMe-PC : une nouvelle version du même élément (une planche refaite) porte la même `cle` et remplace l'ancienne |
| `titre` | texte | `"ISS · PD120"` | un titre à afficher |
| `fichier.nom` | texte | `"ISS_20261003_135119.png"` | nom proposé (à ne jamais utiliser tel quel comme nom de fichier sur le serveur) |
| `fichier.type` | texte | `"image/png"` | type MIME |
| `fichier.taille` | nombre | `48213` | octets |
| `fichier.sha256` | texte | `"9f86d0…"` | empreinte SHA-256 (hex) du fichier |

Selon le type, quand SatMe-PC les connaît :

| Champ | Type | Exemple | Pour |
|---|---|---|---|
| `satellite` | texte | `"ISS"` | sstv-image, qso, journal-video |
| `utc` | texte ISO 8601 | `"2026-10-03T13:51:19Z"` | tous (pour une planche ou un rapport : l'heure de l'envoi) |
| `mode` | texte | `"PD120"`, `"FM"` | sstv-image, qso |
| `station` | texte | `"F4XYZ · JN18FS"` | votre indicatif et carré |
| `evenement` | texte | `"ARISS octobre 2026"` | sstv-image (campagne), planche, rapport |
| `description` | texte | `"Diapo 3/12"` | sstv-image |
| `incomplete` | booléen | `true` | sstv-image reçue en partie |
| `indicatif` | texte | `"EA4ABC"` | qso : la station contactée |
| `locator` | texte | `"IN80DD"` | qso : son carré |

Exemple complet (`sstv-image`) :

```http
POST /api/satme/sstv-image HTTP/1.1
Host: example.org
User-Agent: SatMe-PC/2026.10.34 (+https://github.com/f4ioz/SatMe)
X-SatMe-Version: 1
X-SatMe-Type: sstv-image
X-SatMe-Id: 0b6f7c1e-3d0a-4c9e-9a51-6f4f0c2b8e21
Authorization: Bearer 3f9a…
Content-Type: multipart/form-data; boundary=SatMe-5c1e…

--SatMe-5c1e…
Content-Disposition: form-data; name="type"
Content-Type: text/plain; charset=utf-8

sstv-image
--SatMe-5c1e…
Content-Disposition: form-data; name="meta"
Content-Type: application/json; charset=utf-8

{"satellite":"ISS","utc":"2026-10-03T13:51:19Z","mode":"PD120","station":"F4XYZ · JN18FS","evenement":"ARISS octobre 2026","titre":"ISS · PD120","version":1,"type":"sstv-image","id":"0b6f7c1e-3d0a-4c9e-9a51-6f4f0c2b8e21","cle":"sstv:ISS_20261003_135119.png","fichier":{"nom":"ISS_20261003_135119.png","type":"image/png","taille":48213,"sha256":"9f86d0…"}}
--SatMe-5c1e…
Content-Disposition: form-data; name="fichier"; filename="ISS_20261003_135119.png"
Content-Type: image/png

<les octets du PNG>
--SatMe-5c1e…--
```

### Les données seules : `application/json`

`ping`, `retrait` et le direct sont un JSON, qui porte lui aussi `type` et
`version`.

---

## Les types

| Type | Corps | Fichier | Envoyé |
|---|---|---|---|
| `ping` | JSON | — | par **Tester** |
| `sstv-image` | multipart | PNG | une image SSTV de la galerie (sur clic, ou chaque image reçue en direct en Automatique) |
| `planche` | multipart | JPEG (1600 px) | une planche ; en Automatique, celles qui ont des images, 2 min après la dernière image reçue |
| `rapport` | multipart | PDF | le rapport d'événement d'une planche |
| `qso` | multipart | ADIF (`.adi`, un enregistrement) | un contact du carnet ; en Automatique, chaque contact ajouté |
| `journal-video` | multipart | MP4 | une vidéo faite par l'éditeur du journal (peut faire des centaines de Mo) |
| `retrait` | JSON | — | retirer un élément (pas encore de bouton dans SatMe-PC ; prévu dans l'API) |
| `direct`, `direct-spectre`, `direct-datv-image`, `direct-sstv-image`, `direct-efface` | JSON ou multipart | JPEG | le [direct](#le-direct-facultatif), seulement si coché |

**`ping`** : `{"type":"ping","version":1,"logiciel":"SatMe-PC/…","types":["sstv-image","planche",…]}`
(`types` : ceux qui ne sont pas à « Jamais »). Le récepteur répond **n'importe
quel 2xx**. S'il répond un JSON, SatMe-PC lit en option : `nom` ou `indicatif`
et `site` (affichés : « ok : site de F4XYZ (https://…) »), `refuses` (une
liste de types que le site ne prend pas, nommés sur la carte).

**`retrait`** : `{"type":"retrait","version":1,"typeRetire":"sstv-image","cle":"sstv:…","id":"<l'id rendu par le site à la publication, ou vide>"}`.
2xx ou 404 : retiré.

### Idempotence

- `X-SatMe-Id` identifie **un envoi** : s'il revient (le réseau a coupé après
  que vous l'avez enregistré, avant que SatMe-PC ait lu la réponse), c'est le
  même. Le plus simple : si vous avez déjà un élément de cet id, répondez
  `200` sans le réenregistrer.
- `cle` identifie **l'élément** : le même élément renvoyé plus tard (une
  planche refaite) a un nouvel `X-SatMe-Id` mais la même `cle` ; remplacez
  l'ancien.

---

## Les réponses

| Réponse | SatMe-PC fait |
|---|---|
| **2xx** | accepté. En option, un JSON `{"id": "…", "url": "…", "deja": true}` : l'`id` sera renvoyé dans un `retrait` ; `deja` affiche « déjà en ligne » |
| **401** | jeton refusé : **tout s'arrête**, l'élément reste dans la file, la carte demande de retester la connexion |
| **403** | refusé (ce type n'est pas pris par le site) : l'élément est retiré de la file, le refus est noté sur la carte (avec `{"erreur": "…"}` s'il y en a un) |
| **413** | trop gros : retiré de la file, noté |
| **3xx** et autres **4xx** (400, 404, 415…) | refus définitif : retiré de la file, noté |
| **408**, **429**, **5xx**, pas de réponse | réessayé plus tard (voir la file) |

Un `{"erreur": "…"}` dans la réponse est montré tel quel sur la carte :
écrivez-le pour un humain.

---

## La file et les reprises

Rien ne part de la page : chaque élément est d'abord **copié dans une file sur
le disque** (dossier `site/` des données de SatMe-PC : `file.json` et
`envois/`; une vidéo est lue à sa place). Un fil d'envoi passe toutes les
5 secondes et envoie, **un à la fois, dans l'ordre**, ce qui est dû.

- Pas de réponse, 408, 429 ou 5xx : nouvel essai après **30 s, 1 min, 2 min,
  4 min, 8 min, 16 min, 32 min, puis toutes les heures**, avec le **même
  `X-SatMe-Id`** ; chaque élément suit son propre rythme.
- La file survit à un redémarrage ; 300 éléments au plus (les plus anciens
  partent).
- Le même élément redemandé alors qu'il attend : seule la nouvelle version
  reste.
- Sur la carte : ce qui attend, le dernier essai, les dernières réponses,
  **Réessayer maintenant**, **Vider la file**.

---

## Le direct (facultatif)

Sous **En direct**, chaque partie se coche à part (rien d'origine). Pas de
file : ce que le site ne prend pas est perdu, et l'essai suivant attend
(30 s, 1 min… 5 min ; 2 min après un 403, 10 min après un 401). Seulement des
noms, des heures, des mesures, un spectre et des images — jamais une adresse,
un code d'appairage, un mot de passe ou une clé.

| Type | Corps | Cadence |
|---|---|---|
| `direct` | JSON `{"type","version","t": <ms UTC>, "pilote": {…} \| null}` ou `{…, "datv": {…} \| null}` : chaque partie présente remplace la précédente, `null` l'efface | Auto-pilote : 10 s (une fois par minute si rien ne change) ; DATV : 3 à 30 s |
| `direct-spectre` | JSON `{"type","version","centreHz","largeurHz","minDb","maxDb","bins"}` : `bins` = 1024 octets en base64, 0 = `minDb`, 255 = `maxDb` | 1 à 10 s, quand le SDR est sur QO-100 |
| `direct-datv-image` | multipart, `fichier` = JPEG ≤ 190 Ko, 640 px | tant que le récepteur DATV est verrouillé |
| `direct-sstv-image` | multipart, `fichier` = JPEG | toutes les 5 s pendant un passage de l'auto-pilote, si l'image a changé |
| `direct-efface` | JSON `{"type","version","t"}` | bouton **Effacer le direct** : oubliez tout le direct |

`pilote` : `{"maitre": bool, "prochain": {"campagne","sat","aos","los","el"}, "enCours": {…, "phase","mode","images","elMaintenant","azMaintenant","image": {"phase","progression","mode","prochaine"}}, "derniere": {"sat","utc","mode"}}` (heures en ms UTC).
`datv` : `{"verrouille","cielKhz","srKs","service","fournisseur","modcod","mer","marge"}`.

Un récepteur qui ne veut pas du direct répond `403` (ou `404`) à ces types.

---

## Sécurité

- **HTTPS** sur Internet (SatMe-PC refuse `http://` hors réseau local).
- **Un jeton**, long et aléatoire, comparé en temps constant
  (`hash_equals` en PHP, `hmac.compare_digest` en Python). Ne le mettez
  jamais dans une page publique ni dans un dépôt.
- **Vérifiez le type et la taille** : une liste blanche de types et
  d'extensions, une taille maximale par type ; refusez le reste (`403`,
  `413`, `415`).
- **Ne faites jamais confiance au nom de fichier** : nommez vous-même (par
  exemple d'après `X-SatMe-Id`), sans `..` ni `/`.
- **Rangez hors de la racine web** si possible, et servez les fichiers par un
  script ou une règle qui ne les exécute pas. Sinon, dans le dossier de
  réception, interdisez l'exécution (Apache :
  `php_flag engine off` / `RemoveHandler .php .phtml` / `Options -ExecCGI`
  dans un `.htaccess`, ou mieux `Require all denied` et un script qui sert les
  fichiers). **N'exécutez jamais un fichier reçu.**
- Échappez tout le texte de `meta` à l'affichage (c'est du texte libre).
- Limitez le débit si votre hébergeur le permet.

---

## Des récepteurs prêts à l'emploi

Dans [`docs/exemples/publication-web/`](exemples/publication-web/)
(GPL-2.0-or-later, comme le reste du dépôt : copiez-les, adaptez-les) :

- **[`recevoir.php`](exemples/publication-web/recevoir.php)** — un seul
  fichier PHP (7.4+) pour un hébergement mutualisé : vérifie le jeton et le
  type, range le média et sa fiche JSON dans un dossier, tient `index.json` à
  jour, répond en JSON. Réglez `TOKEN` et `DIR` en tête ; dans SatMe-PC,
  l'adresse `https://votre-site/recevoir.php` (sans `{type}`). Pour les
  vidéos, `upload_max_filesize` et `post_max_size` de PHP doivent suivre.
- **[`recevoir.py`](exemples/publication-web/recevoir.py)** — Python 3,
  bibliothèque standard seulement : la même chose, plus `retrait` et le
  direct (`direct/direct.json`, les images). Il sert aussi le dossier en
  lecture (GET) :

  ```sh
  SATME_JETON=$(openssl rand -hex 32) python3 recevoir.py --dossier ./satme-donnees --port 8765
  ```

  Adresse dans SatMe-PC : `http://127.0.0.1:8765/satme/{type}` sur la même
  machine (`--ecoute 0.0.0.0` pour le réseau local). Sur Internet, mettez-le
  derrière un serveur HTTPS (nginx, Caddy).
- **[`index.html`](exemples/publication-web/index.html)** — une page HTML et
  JavaScript seule qui lit `index.json` et montre les images, les vidéos, les
  documents et les contacts.

Les trois partagent la même disposition que la cible
[Dossier](#sans-serveur--un-dossier) : une page faite pour l'un marche avec
les autres.

---

## Afficher ce qui est reçu

Tout est dans le dossier de réception :

```
satme-donnees/
├── index.json                         les derniers éléments, le plus récent d'abord
├── sstv-image/2026/0b6f7c1e-….png     le fichier
├── sstv-image/2026/0b6f7c1e-….json    sa fiche : le JSON meta + "chemin" + "ajoute"
├── qso/2026/….adi + ….json
└── direct/direct.json, datv.jpg, sstv.jpg   (le direct, réécrit sur place)
```

`index.json` :

```json
{
  "version": 1,
  "maj": "2026-10-03T13:52:02Z",
  "elements": [
    { "type": "sstv-image", "id": "0b6f7c1e-…", "cle": "sstv:ISS_20261003_135119.png",
      "titre": "ISS · PD120", "satellite": "ISS", "utc": "2026-10-03T13:51:19Z", "mode": "PD120",
      "chemin": "sstv-image/2026/0b6f7c1e-….png", "ajoute": "2026-10-03T13:52:02Z",
      "fichier": { "nom": "ISS_20261003_135119.png", "type": "image/png", "taille": 48213, "sha256": "9f86…" } }
  ]
}
```

- Une page statique : copiez `index.html` à côté de `index.json`, ou
  écrivez la vôtre (un `fetch('index.json')`, une boucle).
- WordPress, un CMS : lisez `index.json` (ou les fiches) depuis un gabarit ou
  une extension.
- Un générateur statique : Hugo lit un JSON comme donnée
  (`resources.Get` / `transform.Unmarshal`), Jekyll avec `_data/`.

---

## Sans serveur : un dossier

Choisissez **Dossier sur ce PC** à l'étape 1 et donnez un chemin complet
(`/home/moi/Nextcloud/satme`, `C:\Users\moi\Sync\satme`). **Tester** vérifie
qu'on peut y écrire. SatMe-PC y écrit alors chaque élément envoyé, avec la
même file, les mêmes choix **Sur clic / Automatique / Jamais** et les mêmes
fiches que l'API :

- `<dossier>/<type>/<AAAA>/<id>.<ext>` et `<id>.json` (le `meta` de l'API,
  plus `chemin` et `ajoute`) ; l'année vient de `utc` ;
- `index.json` : les 500 derniers, le plus récent d'abord (une nouvelle
  version du même élément remplace l'ancienne, fichiers compris) ;
- `flux.xml` : un flux Atom des 50 derniers (liens relatifs au dossier) ;
- `direct/direct.json` (réécrit sur place), `direct/datv.jpg`,
  `direct/sstv.jpg` si le direct est coché — pas de spectre vers un dossier.

Chaque fichier est écrit à côté puis renommé (atomique quand le système le
permet) : un lecteur ne voit jamais un fichier à moitié écrit, et l'index ne
nomme jamais un fichier pas encore là.

À quoi ça sert :

- un **site statique** (Hugo, Jekyll, HTML) : le dossier dans le site, puis
  votre publication habituelle ;
- un **outil de synchronisation** que vous avez déjà : Nextcloud, Syncthing,
  un miroir rsync ou FTP (`lftp mirror -R`), un dossier partagé. **SatMe-PC ne
  fait pas de FTP lui-même.**

Le dossier n'est pas disponible en démonstration.
