# Publication web : SatMe-PC envoie vers votre site

*[English version](publication-web.md)*

SatMe-PC peut **pousser des éléments vers un site web extérieur** : images
SSTV et SSDV, planches, rapports d'événement, contacts, vidéos du journal,
passages FT8 / FT4, et en option des données en direct (auto-pilote, spectre
QO-100, image DATV, station, FT8 / FT4), la liste de vos satellites favoris,
la description de votre montage et vos diplômes.
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
   Planche (planche et rapport), vidéos faites par l'éditeur du journal,
   images SSDV (*Télémesure › Images SSDV*), passages de la carte
   « Passages FT8/FT4 sur le site » (onglet FT8/FT4), et **Envoyer le
   diplôme reçu** (*Agenda › Diplômes*).

`http://` (sans S) n'est accepté que vers cette machine ou le réseau local
(`127.0.0.1`, `192.168.…`, `10.…`, `172.16–31.…`, `….local`, un nom sans
point) ; ailleurs, `https://` est obligatoire. Les redirections ne sont pas
suivies : mettez l'adresse finale.

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

Les types `sstv-image`, `ssdv-image`, `planche`, `rapport`, `qso`, `journal-video` (et les
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
| `station` | texte | `"F4XYZ · JN18FS"` | votre indicatif et locator |
| `evenement` | texte | `"ARISS octobre 2026"` | sstv-image (campagne), planche, rapport |
| `description` | texte | `"Diapo 3/12"` | sstv-image |
| `incomplete` | booléen | `true` | sstv-image reçue en partie |
| `traitement` | texte | `"corrigée · couleur corrigée · IA"` | sstv-image qui n'est pas telle que reçue : ce qui lui a été fait |
| `ia` | booléen | `true` | sstv-image passée par le débruitage appris (IA) : à dire à côté de l'image ; planche : une case au moins (`imagesIa` : combien) |
| `indicatif` | texte | `"EA4ABC"` | qso : la station contactée |
| `locator` | texte | `"IN80DD"` | qso : son locator |

#### SSTV : passages, auto-pilote, planches (facultatif)

Tous facultatifs : un SatMe-PC plus ancien
ne les envoie pas, et un récepteur peut les ignorer. Les heures sont des textes
ISO 8601 UTC. Un **identifiant de passage** est le numéro NORAD du satellite et
son AOS à la minute (`"25544-20261003T1349Z"`) : le même passage a le même
identifiant dans tous les éléments qui le nomment, tant qu'il est calculé avec
les mêmes éléments orbitaux (en cas de doute, comparer le satellite et les
heures). Une **clé** (`"sstv:…"`, `"journal:…"`, `"rapport:…"`, `"planche:…"`)
est le `cle` d'un autre élément : elle peut nommer un élément pas (encore)
publié sur votre site.

`sstv-image` et `journal-video` :

| Champ | Type | Exemple | Sens |
|---|---|---|---|
| `catnum` | nombre | `25544` | le numéro NORAD du satellite |
| `passage` | texte | `"25544-20261003T1349Z"` | le passage où il a été reçu (identifiant) |
| `aos`, `los` | texte | `"2026-10-03T13:49:30Z"` | l'AOS et le LOS de ce passage |
| `elMax` | nombre | `62.4` | son élévation la plus haute (degrés) |
| `autopilote` | booléen | `true` | reçu par l'auto-pilote SSTV (Agenda › SSTV) |
| `campagne` | texte | `"ARISS série 34"` | la campagne de l'auto-pilote qui l'a reçu |
| `diapo`, `diapos` | nombre | `3`, `12` | sstv-image : sa diapo, donnée à la main (3 sur 12) |
| `planches` | liste de clés | `["planche:serie34"]` | journal-video : les planches de l'événement dont elle fait partie |

`planche` :

| Champ | Type | Exemple | Sens |
|---|---|---|---|
| `satellite`, `catnum` | texte, nombre | `"ISS"`, `25544` | le satellite de son événement (sinon de la plupart de ses images) |
| `evenementId` | texte | `"c:ariss-34"` | l'identifiant (stable) de son événement |
| `evenementGenre` | texte | `"campagne"` | `campagne` (une campagne de l'auto-pilote), `activite` (une activité annoncée), `images` (une suite d'images) |
| `evenementDebut`, `evenementFin` | texte | | la période de l'événement |
| `campagne`, `campagneId` | texte | `"ARISS série 34"`, `"ariss-34"` | quand l'événement est une campagne de l'auto-pilote |
| `mode` | texte | `"PD120"` | le mode SSTV le plus fréquent de ses images |
| `premiere`, `derniere` | texte | | la première et la dernière image |
| `images` | nombre | `23` | les images de l'événement |
| `diapos` | nombre | `12` | les diapos attendues |
| `diaposRecues` | nombre | `10` | les diapos reçues |
| `diaposManquantes` | liste de nombres | `[5, 6]` | les diapos qui manquent |
| `meilleures` | liste | voir plus bas | la meilleure image de chaque diapo reçue |
| `passages` | liste | voir plus bas | les passages qui ont apporté des images, le plus ancien d'abord |
| `autopilote` | booléen | `true` | au moins un de ces passages a été reçu par l'auto-pilote |
| `videos` | liste de clés | `["journal:SatMe_ISS_…mp4"]` | les vidéos du journal de ces passages |
| `rapport` | clé | `"rapport:serie34"` | son rapport d'événement |

`meilleures[]` : `{"diapo": 3, "image": "sstv:<fichier>.png", "utc": "…", "note": 82, "copies": 4, "estimee": true}`
(`note` : la note de SatMe sur 100 quand elle est connue ; `estimee` : le
numéro de la diapo a été estimé d'après l'horaire).
`passages[]` : `{"id": "25544-20261003T1349Z", "aos": "…", "los": "…", "elMax": 62.4, "images": 5, "diapos": [3, 4],
"autopilote": true, "campagne": "ARISS série 34", "video": "journal:<fichier>.mp4"}`.

`ssdv-image` (une image SSDV : les paquets numérotés d'un JPEG, reçus en
un passage ou plusieurs — SO-127 et d'autres satellites) :

| Champ | Type | Exemple | Sens |
|---|---|---|---|
| `satellite`, `catnum` | texte, nombre | `"HADES-SA"`, `68446` | le satellite entendu, son numéro NORAD |
| `utc`, `premier` | texte | `"2026-10-30T09:12:04Z"` | son premier paquet reçu (les deux identiques) |
| `dernier` | texte | | son dernier paquet reçu jusqu'ici |
| `image` | nombre | `17` | le numéro de l'image à bord (0–255) |
| `indicatif` | texte | `"Q0SAT"` | le champ indicatif SSDV, tel que décodé (certains satellites y mettent des octets fixes) |
| `paquetsRecus` | nombre | `38` | paquets reçus |
| `paquetsAttendus` | nombre | `40` | paquets de l'image |
| `attendusEstime` | booléen | `true` | `paquetsAttendus` est une estimation (le dernier paquet n'est pas encore arrivé) |
| `manquants` | nombre | `2` | paquets manquants |
| `complete` | booléen | `false` | tous les paquets reçus |
| `largeur`, `hauteur` | nombre | `320`, `240` | pixels |
| `passages` | liste | voir dessous | chaque passage (ou enregistrement, ou import) qui a apporté des paquets, le plus ancien d'abord |
| `autopilote` | booléen | `true` | au moins un de ces passages reçu par le pilote automatique |
| `campagne` | texte | `"SO-127 octobre"` | la campagne du pilote automatique (la dernière) |

`passages[]` : `{"id": "68446-20261030T0910Z", "aos": "…", "los": "…", "elMax": 48.2, "paquets": 21,
"premier": "…", "dernier": "…", "source": "radio", "autopilote": true, "campagne": "SO-127 octobre"}`
(`id`, `aos`, `los`, `elMax` quand le passage est connu ; `source` : `radio`,
ou l'enregistrement ou le fichier d'où les paquets ont été lus).

**Une image SSDV grandit** : les blocs manquants sont gris dans le JPEG
envoyé. Quand d'autres paquets arrivent (plus tard dans le passage, ou à un
autre passage), SatMe-PC la renvoie avec **la même `cle`** (`"ssdv:<clé>"`),
une minute après son dernier paquet ou dès qu'elle est complète — en
Automatique, et en Sur clic une fois que vous l'avez envoyée à la main.
Remplacez la version précédente.

`rapport` : `planche` (la clé de la planche), `satellite`, `catnum`,
`evenementId`, `evenementGenre`, `campagne`, `campagneId`, `premiere`,
`derniere`, comme plus haut.

Les listes et les objets restent petits (une série compte quelques dizaines de
passages au plus) ; un récepteur qui ne garde que les champs simples ne perd
rien d'autre.

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
| `ssdv-image` | multipart | JPEG | une image SSDV telle qu'elle est (sur clic, ou en Automatique), renvoyée quand d'autres paquets arrivent (même `cle`) |
| `planche` | multipart | JPEG (1600 px) | une planche ; en Automatique, celles qui ont des images, 2 min après la dernière image reçue |
| `rapport` | multipart | PDF | le rapport d'événement d'une planche |
| `qso` | multipart | ADIF (`.adi`, un enregistrement) | un contact du carnet ; en Automatique, chaque contact ajouté |
| `journal-video` | multipart | MP4 | une vidéo faite par l'éditeur du journal (peut faire des centaines de Mo) |
| `retrait` | JSON | — | retirer un élément (pas encore de bouton dans SatMe-PC ; prévu dans l'API) |
| `direct`, `direct-spectre`, `direct-datv-image`, `direct-sstv-image`, `direct-station`, `direct-efface` | JSON ou multipart | JPEG | le [direct](#le-direct-facultatif), seulement si coché |
| `favoris` | JSON | — | les [satellites favoris](#les-satellites-favoris), seulement si coché |
| `montage` | JSON | — | la [description du montage](#la-description-du-montage), seulement si coché |
| `diplomes` | JSON | — | les [diplômes](#les-diplômes) suivis et votre progression, seulement si coché |
| `diplome-image` | multipart | PNG, JPEG, PDF | l'image d'un [diplôme reçu](#les-diplômes), seulement sur un clic |
| `direct-ft8` | JSON | — | chaque créneau [FT8 / FT4](#ft8--ft4) décodé, seulement si coché |
| `direct-video`, `direct-soustitres` (+ `PUT …/direct-video/<qualité>/<n>.ts`, et le WebSocket `/ws/direct/station`) | JSON, MPEG-TS | `video/mp2t` | le [direct rapide](#direct-rapide-facultatif-pour-une-connexion-à-haut-débit), seulement si coché |
| `ft8-passage` | multipart | JSON | un passage du journal avec ses messages [FT8 / FT4](#ft8--ft4) (sur clic, ou chaque passage en Automatique) |

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
`datv` : `{"verrouille","cielKhz","srKs","service","fournisseur","modcod","mer","marge","station"}`.

`datv.station` (facultatif, absent quand on ne sait pas) : la station regardée,
`{"indicatif","selection","nom","qth","carre","pays","km","azimut"}`.
`selection` vaut `true` quand c'est le signal sélectionné sur le spectre de la
bande large (celui sur lequel le récepteur est accordé, l'indicatif pris dans
son étiquette ; présent même avant l'accrochage), `false` quand c'est
l'indicatif confirmé par la réception (carte de station, nom du service, image ;
seulement une fois verrouillé). `nom`, `qth` (la ville), `carre` (le locator),
`pays` viennent de sa fiche QRZ.com quand elle est trouvée (compte QRZ dans
SatMe-PC), sinon il n'y a que l'indicatif ; `km` et `azimut` (°) depuis la
station, calculés sur le locator. Jamais la photo QRZ (elle appartient à son
auteur), l'adresse électronique, la rue ni les coordonnées. Un site qui ne
connaît pas `station` l'ignore.

Un récepteur qui ne veut pas du direct répond `403` (ou `404`) à ces types.

### Les satellites favoris

Sous **En direct**, la ligne **Satellites favoris** (décochée d'origine)
envoie la liste des satellites marqués ★ dans SatMe-PC, par exemple pour
qu'une page d'accueil les mette en avant. Elle part dès que la liste change,
sinon une fois par heure au plus, et seulement quand le module est activé et
la cible testée.

```json
{"type":"favoris","version":1,"satellites":[{"nom":"ISS","norad":25544},{"nom":"SO-50","norad":27607}],"t":1798000000000}
```

`satellites` est la liste complète (triée par nom ; vide quand il n'y a plus
de favori) : elle remplace la précédente. `t` : l'heure de l'envoi, en ms UTC.
Vers un dossier : `favoris.json` à la racine, même contenu.

Un récepteur qui ne connaît pas ce type répond `400`, `404` ou `415` :
SatMe-PC le note « non pris en charge par le site », sans erreur, et ne
réessaie que le lendemain (ou quand on recoche la ligne).

### La description du montage

Sous **En direct**, la ligne **Description du montage** (décochée d'origine)
envoie le matériel de la station tel que l'onglet **Montage** le dessine, et
les chemins que prend le signal — de chaque antenne à chaque récepteur, de
chaque émetteur à son antenne — par exemple pour qu'une page « Station »
dessine la chaîne QO-100 (parabole → LNB → convertisseur → MiniTiouner / SDR,
et la montée). Elle part dès que la description change (regardé une fois par
minute), sinon une fois par heure, et seulement quand le module est activé et
la cible testée. Le plan envoyé est celui qui dessine le plus de chemins
QO-100 (le plan affiché en cas d'égalité), sinon le plan affiché ; rien ne
part tant que le montage est vide.

```json
{"type":"montage","version":1,"t":1798000000000,"plan":"Station QO-100",
 "elements":[
  {"id":"e1","genre":"antenne","modele":"parabole-qo100","nom":"Parabole + source QO-100 (POTY / patch)","court":"Parabole QO-100",
   "bandesMhz":[2400,10489],"diametreCm":80,"gainDbi":23.5},
  {"id":"e2","genre":"lnb","modele":"lnb-twin","nom":"LNB Octagon Twin « Green »","court":"LNB Twin","bandesMhz":[739,10489],
   "options":[{"nom":"Référence externe 25 MHz","valeur":"oui"}],"references":[{"mhz":25,"de":"e12"}]},
  {"id":"e3","genre":"recepteur_datv","modele":"minitiouner","nom":"MiniTiouner Pro V2","court":"MiniTiouner","bandesMhz":[739]}],
 "voies":[
  {"sens":"rx","qo100":true,"etapes":[{"id":"e1"},{"id":"e2","mhz":10489,"cable":"Monté directement"},{"id":"e3","mhz":739,"cable":"Coaxial F (TV)"}]},
  {"sens":"tx","qo100":true,"etapes":[{"id":"e6"},{"id":"e7","mhz":435,"cable":"Coaxial SMA"},{"id":"e8","mhz":2400,"cable":"Coaxial SMA"},{"id":"e1","mhz":2400,"cable":"Coaxial N"}]}],
 "montee":{"chaine":"IC-9700 + convertisseur montant","antenne":"Parabole 80 cm + source POTY","diametreCm":80,"gainDbi":23.5,
  "ampliW":20,"pertesDb":1.5,"pireMaxDbw":35},
 "conversions":[{"sens":"rx","olMhz":10344.973,"inverse":false,"fiBasMhz":144.027,"fiHautMhz":155.027}]}
```

Les champs :

- `plan` : le nom du plan (absent s'il n'en a pas) ;
- `elements` : chaque appareil du plan (les « boîtes logicielles » non) —
  `id` (court, ne sert qu'à relier les chemins), `genre` (`antenne`, `lnb`,
  `ampli`, `reference`, `convertisseur`, `alimentation`, `filtre`, `charge`,
  `coupleur`, `rotor`, `poste`, `sdr`, `recepteur_datv`, `noeud`, `carte_son`,
  `interface`, `ordinateur`, `telephone`), `modele` (l'identifiant du
  catalogue de SatMe), `nom` (celui que vous lui avez donné, sinon celui du
  catalogue), `court` (le nom court du dessin), `bandesMhz` (les bandes de ses
  ports radio), `options` (`nom`, `valeur` : ses réglages de l'onglet
  Montage), `references` (`mhz`, `de` : la référence de fréquence qui
  l'alimente et l'appareil qui la donne), `diametreCm` et `gainDbi` pour
  l'antenne du profil de montée ;
- `voies` : les chemins, ceux de QO-100 d'abord (16 au plus) — `sens` (`rx` :
  de l'antenne au récepteur ; `tx` : de l'émetteur à l'antenne), `qo100`
  (10 GHz ou 2,4 GHz à l'antenne), `etapes` (`id` d'un élément, et pour
  chacune après la première `mhz`, la bande du câble qui y arrive, et `cable`,
  son genre) ;
- `montee` : le profil de montée QO-100 choisi (émission bande étroite),
  seulement quand le plan émet par son antenne — `chaine`, `antenne`,
  `diametreCm`, `gainDbi`, `ampliW`, `pertesDb`, `pireMaxDbw` (une
  estimation) ;
- `conversions` : les oscillateurs de la table de conversion pour QO-100 —
  `sens` (`rx`, `tx`), `olMhz`, `inverse`, `fiBasMhz`, `fiHautMhz`.

Seulement des noms et des caractéristiques : jamais une adresse, un port, un
chemin de fichier, un numéro de série, un code d'appairage ni un mot de passe.
Un nom donné qui ressemble à une adresse ou à un numéro de série est remplacé
par celui du catalogue. La description complète remplace la précédente ;
vers un dossier : `montage.json` à la racine, même contenu. Un récepteur qui
ne connaît pas ce type répond `400`, `404` ou `415` : SatMe-PC le note « non
pris en charge par le site », sans erreur, et ne réessaie que le lendemain
(ou quand on recoche la ligne).

### Les diplômes

Sous **En direct**, la ligne **Diplômes** (décochée d'origine) envoie les
diplômes suivis dans **Agenda › Diplômes** (qui doit être actif), par exemple
pour une page « Diplômes » de votre site : dès qu'ils changent (regardé une
fois par minute), sinon une fois par jour, seulement quand le module est
activé et la cible testée.

```json
{"type":"diplomes","version":1,"t":1798000000000,"diplomes":[
 {"id":"cat:vucc","nom":"VUCC Satellite","emetteur":"ARRL","sorte":"permanent","statut":"en_cours",
  "libelle":"En cours : 37/100 locators","progression":{"actuel":37,"objectif":100,"unite":"locators"},
  "reglement":"https://www.arrl.org/vucc"},
 {"id":"cat:amsat-scc","nom":"AMSAT Satellite Communicators Club","emetteur":"AMSAT","sorte":"permanent",
  "statut":"recu","recu":1790000000000,"progression":{"actuel":1,"objectif":1,"unite":"contacts"},"image":true},
 {"id":"pzk:75-2","nom":"ARISS SSTV …","emetteur":"ARISS (géré par le PZK)","sorte":"evenement","statut":"a_venir",
  "debut":1798100000000,"fin":1798300000000,"limite":1800500000000,"satellite":"ISS","mode":"PD120",
  "reglement":"https://ariss.pzk.org.pl/sstv/"}]}
```

`diplomes` est la liste complète, qui remplace la précédente. Par diplôme :
`id` (stable), `nom`, `emetteur`, `sorte` (`permanent` : compté dans le
carnet ; `evenement` : des images reçues pendant une fenêtre), `statut`
(`en_cours`, `obtenable` — objectif atteint, à demander —, `demande`, `recu`,
`a_venir`, `pendant` — l'événement a lieu), `libelle` (la phrase de SatMe),
`progression` (`actuel`, `objectif`, `unite` ; diplômes permanents),
`debut`, `fin`, `limite` (la date limite des demandes), `demande`, `recu`
(ms UTC), `reglement` (https), `satellite` et `mode` (événements), `image`
(`true` quand l'image du diplôme reçu a été envoyée). Un champ inconnu est à
ignorer ; un champ absent n'a pas de valeur.

**Jamais envoyé** : ce qui a été compté (locators, entités, États, indicatifs
contactés), les noms des images, les notes, le nom et le courriel de « Pour
les demandes », les formulaires, et les diplômes masqués, manqués, expirés ou
« à vérifier ».

**L'image d'un diplôme reçu** (`diplome-image`) ne part que sur un clic :
dans Agenda › Diplômes, **Envoyer le diplôme reçu** sur un diplôme dont
l'image est jointe (le bouton n'apparaît que si la publication web est
prête). Multipart comme les médias, `meta` : `{"diplome": "<id>", …}`, le
fichier : l'image telle que gardée (PNG ou JPEG ; réduite en JPEG de
3000 px au plus si elle dépasse 8 Mo) ou un PDF de 8 Mo au plus. Pas de file
d'attente : si le site ne répond pas, le bouton le dit ; on recommence.

Vers un dossier : `diplomes.json` à la racine (même contenu) et
`diplomes/<id>.png|jpg|pdf`. Un récepteur qui ne connaît pas `diplomes`
répond `400`, `404` ou `415` : laissé tranquille jusqu'au lendemain, sans
erreur.

### La station

Sous **En direct**, la ligne **Station** (décochée d'origine) envoie la
station telle qu'elle est maintenant, pour qu'une page en direct dessine le
satellite, les antennes et la chaîne de réception à côté des images : type
`direct-station`, JSON, environ 1 Ko. Elle part dès que quelque chose change,
au plus toutes les 2 s (2 à 10 s, au choix sur la ligne), et toutes les 30 s
quand rien ne change — un récepteur peut donc prendre un silence d'une ou deux
minutes pour « station hors ligne ».

```json
{"type":"direct-station","version":1,"t":1798000000000,
 "satellite":{"nom":"ISS","norad":25544,"geo":false,"az":212.4,"el":31.6,"distanceKm":812,"vitesseKmS":-5.84,
   "aos":1797999700000,"los":1798000300000,"elMax":61.3,"choisi":"Mode V/U FM",
   "emetteurs":[{"description":"Mode V/U FM","mode":"FM","descenteHz":437800000,"monteeHz":145990000}]},
 "antennes":{"rotor":{"connecte":true,"az":209.8,"el":30.9,"cibleAz":212.4,"cibleEl":31.6,"enMouvement":true,"horsPortee":false,"azSeul":false},
   "parabole":{"az":150.0,"el":29.7}},
 "reception":{"sdr":{"actif":true,"type":"RTL-SDR","frequenceHz":437800000},
   "datv":{"actif":true,"verrouille":true,"mer":7.4,"marge":2.1,"service":"F4XYZ","cielKhz":10494750},
   "poste":{"modele":"IC-9700","connecte":true,"descenteHz":437800000,"monteeHz":145990000,"mode":"FM"}},
 "enEmission":false,"mode":"phonie"}
```

| Clé | Sens |
|---|---|
| `satellite` | le satellite suivi, ou QO-100 quand la station y travaille (SDR ou récepteur DATV dessus) et que le satellite suivi est sous l'horizon ; `geo` : géostationnaire (alors ni `aos` ni `los`) ; `az`/`el` (°), `distanceKm`, `vitesseKmS` (+ : il s'éloigne) vus de la station ; `aos`, `los`, `elMax` : le passage en cours ou le prochain ; `emetteurs` : jusqu'à 6 de ses émetteurs (la liste publique de SatNOGS), `choisi` celui en service |
| `antennes.rotor` | seulement si un rotor est réglé : `az`/`el` relus au contrôleur, `cibleAz`/`cibleEl` où il est envoyé, `enMouvement`, `horsPortee` (hors de sa course), `azSeul` (sans élévation) |
| `antennes.parabole` | un satellite géostationnaire : le pointage de la parabole fixe depuis le locator de la station |
| `reception.sdr` | le SDR : en marche ou non, son genre (`RTL-SDR`, `ADALM-PLUTO`, `bladeRF`, `SatMe-Node`, `FI du poste`…), la fréquence (ciel) écoutée |
| `reception.datv` | le récepteur DATV : en marche, verrouillé ; verrouillé, `mer` et `marge` (dB), `service` |
| `reception.poste` | seulement quand le poste est connecté : le nom de son modèle, ses fréquences (ciel) et son mode relus |
| `enEmission` | quelque chose émet en ce moment (oui ou non, rien de plus) |
| `mode` | ce que fait la station, quand c'est connu : `DATV`, `FT8`, `FT4`, `SSTV`, `CW`, `phonie`, `numérique` |

Toutes les clés sont facultatives sauf `t` et `enEmission`. Jamais dedans :
une adresse, un port, un périphérique série, un chemin de fichier, un code
d'appairage, un jeton, un autre réglage. Vers un dossier :
`direct/station.json`, même contenu. Un récepteur qui ne connaît pas ce type
répond `400`, `404` ou `415` : SatMe-PC le note « non pris en charge par le
site », sans erreur, et réessaie dix minutes plus tard.

L'appel de réglage, pour un script (`POST /api/site/direct`, comme la case de
la ligne), paramètres dans l'adresse : `POST /api/site/direct?on_station=1` (ou `0`), `&periode_station=2` (2 à 10 s).

### FT8 / FT4

Sous **En direct**, la ligne **FT8/FT4** (décochée d'origine) envoie, tant que
l'écoute FT8 / FT4 est en marche, **chaque créneau une fois décodé** (FT8
15 s, FT4 7,5 s ; environ 9 s, resp. 4,5 s, après sa fin, le temps du
décodeur) : type `direct-ft8`, JSON, 1 à 10 Ko. Chaque créneau part une fois,
les vides aussi (pour qu'une cascade en direct continue) ; ce que le site ne
prend pas est perdu.

```json
{"type":"direct-ft8","version":1,"t":1798000005000,"mode":"FT8","duree":15000,
 "satellite":{"nom":"RS-44","norad":44909,"geo":false,"aos":1797999700000,"los":1798000600000,"elMax":54.4},
 "dialHz":435640000,"transpondeur":"Mode V/U linear",
 "decodes":[{"df":1210,"dt":0.3,"snr":-8,"msg":"CQ EA4XYZ IM79","de":"EA4XYZ","grid":"IM79","cq":true,"toMe":false},
            {"df":1530,"dt":0.2,"snr":-12,"msg":"F4IOZ DL1ABC JO62","de":"DL1ABC","grid":"JO62","cq":false,"toMe":true}],
 "n":2,
 "cascade":{"basHz":200,"hautHz":3000,"bins":256,"lignes":8,"data":"<base64, 2048 octets>"}}
```

| Clé | Sens |
|---|---|
| `t`, `mode`, `duree` | le début du créneau (UTC ms), `FT8` ou `FT4`, sa durée (ms) |
| `satellite` | le passage en cours (d'une minute avant l'AOS à une minute après le LOS), ou QO-100 au poste (`geo`, sans `aos`/`los`) ; absent entre les passages |
| `bande` | sans satellite, la bande de la fréquence (`2 m`, `70 cm`, `20 m`…), si connue |
| `dialHz`, `transpondeur` | la fréquence (Hz ciel) lue au poste ; l'émetteur choisi du satellite |
| `decodes` | 60 au plus, les plus forts, triés par fréquence audio : `df` fréquence audio (Hz), `dt` décalage (s), `snr` report (dB), `msg` le message (les caractères de FT8, 40 au plus), `de` l'indicatif qui l'envoie (quand on le sait), `grid` le locator qu'il donne (4 caractères), `cq` un CQ, `toMe` adressé à l'indicatif de la station |
| `n` | combien de messages avait le créneau (avant la limite de 60) |
| `cascade` | quand la cascade tourne : `lignes` rangées de `bins` octets (0 calme … 255 fort), la plus ancienne d'abord, de `basHz` à `hautHz` en travers |

Seulement des messages entendus sur l'air : aucun réglage, adresse, chemin,
carte son ni rien d'autre de la station. Vers un dossier : `direct/ft8.json`
(le dernier créneau). Un récepteur qui ne connaît pas le type répond `400`,
`404` ou `415` : laissé tranquille dix minutes, sans erreur. L'appel de
réglage : `POST /api/site/direct?on_ft8=1` (ou `0`).

**Un passage entier** (`ft8-passage`, un genre de la file : Sur clic
d'origine, Automatique envoie chaque passage du journal à sa fin s'il a des
messages) : le 🌐 de la carte « Passages FT8/FT4 sur le site » de l'onglet FT8
(SatMe-PC y liste les derniers passages du journal avec des messages).
Multipart comme les médias : `meta` (`titre`, `satellite`, `utc` = AOS,
`mode`, `stations`, `carres`, `cle` = `ft8:<catnum>_<AOS ms>`) et `fichier`,
un JSON (`application/json`, jusqu'à quelques centaines de Ko) :

```json
{"version":1,"satellite":{"nom":"RS-44","norad":44909},"aos":1797999700000,"los":1798000600000,"elMax":54.4,
 "transpondeur":"Mode V/U linear","dialHz":435640000,"modes":["FT4"],
 "tranches":[{"t":1797999707500,"mode":"FT4","decodes":[…],"n":3,"cascade":{…}}, …],
 "decodes":148,"stations":23,"carres":17,"dxcc":9}
```

`tranches` : chaque créneau avec des messages (480 au plus), ses `decodes`
comme plus haut (60 au plus), `cascade` seulement pour les créneaux que
SatMe-PC avait encore en mémoire (les trois dernières heures). `stations`,
`carres`, `dxcc` : combien d'indicatifs (pas celui de la station), de locators
et d'entités DXCC ont été entendus.

### Direct rapide (facultatif, pour une connexion à haut débit)

Sous **Direct rapide**, deux parties de plus, désactivées par défaut, pour une
station reliée en haut débit (fibre). Seul le signal reçu part : le spectre,
l'image et le son DATV, le texte de la parole entendue — jamais un réglage ni
une adresse. Le site ne se connecte jamais à la station. Vers une adresse web
seulement (pas vers un dossier).

**Spectre rapide** — le transpondeur étroit de QO-100 à 1, 2, 5, 10 ou 20
lignes par seconde (512, 1024 ou 2048 points), par **un WebSocket gardé
ouvert** vers l'hôte de votre site : `wss://<hôte de votre adresse>/ws/direct/station`
(`ws://` seulement sur le réseau local), avec `Authorization: Bearer <jeton>`,
`User-Agent` et `X-SatMe-Version: 1` dans la poignée de main. Chaque message
texte est une ligne, le JSON de `direct-spectre` plus `"t"` (ms UTC).
SatMe-PC envoie un ping toutes les 20 s. Le serveur répond `401` (jeton
refusé : nouvel essai après 10 min), `403` (refus : 2 min) ou `101` ; après
une coupure, SatMe-PC rouvre après 1 s, 2 s, 5 s, 10 s, 30 s, puis chaque
minute. Un site sans rien à cette adresse (`404`, ou pas de réponse trois
fois avant toute ouverture) reçoit les lignes **par HTTP**, une seconde par
requête `direct-spectre` `{"type","version","lignes":[{ligne}, …]}` (25 lignes
au plus), et le WebSocket est retenté toutes les 5 minutes. Un proxy inverse
doit laisser passer le WebSocket pour `/ws/direct/` (nginx :
`proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";` et un `proxy_read_timeout` de plus de
60 s). Envoi : environ 60 kbit/s à 5 lignes de 1024 points, 470 kbit/s à 20
lignes de 2048.

**Vidéo DATV en direct** — tant que le récepteur DATV est verrouillé, le flux
reçu en **HLS** : ffmpeg le découpe en segments de 1 ou 2 s (MPEG-TS, une
image clé au début de chacun), dans les qualités cochées — *Source* (l'image
copiée telle que reçue, H.264 ou H.265, le son en AAC), *720p* (~2 Mbit/s),
*480p* (~1 Mbit/s), *360p* (~500 kbit/s), réencodées en H.264 Main avec le
préréglage `veryfast`, `superfast` ou `ultrafast`. La page affiche le débit
montant nécessaire et prévient pour le processeur (chaque réencodage prend
environ un cœur).

- `direct-video`, JSON, au début puis toutes les 30 s :
  `{"type","version","t","actif":true,"segmentS":2,"service":"F4XYZ","rendus":[{"nom":"720p","libelle":"720p","debit":2305600,"largeur":1280,"hauteur":720,"codecs":"avc1.4d4020,mp4a.40.2"}, …],"audio":[{"nom":"trad","langue":"fr","libelle":"Traduction (fr)"}],"soustitres":[{"nom":"orig","langue":"","libelle":"Original"},{"nom":"trad","langue":"fr","libelle":"Traduction (fr)"}]}`
  (`debit` en bit/s ; `source` sans taille et `"codecs":""` ; `audio` et
  `soustitres` seulement s'ils sont choisis) ; `{"type","version","t","actif":false}`
  à l'arrêt (verrouillage perdu, option décochée) : le site affiche « hors ligne ».
- Chaque segment : `PUT <adresse de direct-video>/<qualité>/<n>.ts` (la forme
  `{type}` de l'adresse est nécessaire), corps `video/mp2t`, en-têtes
  `X-SatMe-Duree` (s), `X-SatMe-Debut` (ms UTC de sa première image),
  `X-SatMe-Discontinuite: 1` sur le premier segment après un redémarrage de
  ffmpeg ; `<n>` croît pour chaque qualité, y compris d'un redémarrage à
  l'autre. Tailles : un segment de 2 s fait environ 250 Ko à 1 Mbit/s ;
  autoriser 4 Mo par requête au proxy. Un `409` fait renvoyer l'état ; `401`
  et `403` arrêtent comme plus haut.
- Le récepteur fait lui-même les listes de lecture (liste maîtresse des
  qualités, courte fenêtre glissante) et les sert à ses visiteurs.

**Sous-titres** (avec la vidéo, ou avec « Image DATV » seule) : chaque
réplique que les sous-titres DATV écrivent (DATV › Sous-titres, parole → texte,
et sa traduction si elle est choisie), envoyée dès qu'elle est écrite :
`direct-soustitres`,
`{"type","version","t","service","repliques":[{"id":12,"debut":<ms UTC>,"fin":<ms UTC>,"texte":"…","langue":"en","traduction":"…","langueCible":"fr"}]}`
(texte brut, 500 caractères au plus, 20 répliques au plus par message).
`debut` et `fin` sont l'heure où elle a été dite, à l'horloge — la même que
`X-SatMe-Debut`, pour que le site la place sur la vidéo ; elle arrive
quelques secondes après (le temps de la transcription).

**Audio traduit** — quand « Traduction parlée » parle la DATV : une qualité de
plus, `trad`, son seul (AAC en MPEG-TS), annoncée dans `audio` : la voix de
synthèse sur le son reçu baissé pendant qu'elle parle (« mix »), ou la voix
seule (« seule ») ; le visiteur choisit la piste son dans son lecteur.
Seules les voix que SatMe-PC rend lui-même (Piper, eSpeak NG) y passent, pas
celle de speech-dispatcher.

### Commandes à distance (facultatif)

Pour qu'une skill Alexa ou une page de votre site pose des questions à la
station et demande quelques gestes **sans jamais s'y connecter** :
*Réglages › Assistant et domotique › Commandes à distance*, deux cases
décochées d'origine. C'est toujours SatMe-PC qui envoie, à la même adresse
(`{type}`) et avec le même jeton que le reste.

> **Jamais d'émission.** Seules huit actions existent, toutes de réception
> ou de son : `autopilote_on`, `autopilote_off`, `sstv_on`, `sstv_off`,
> `datv_on`, `datv_off`, `son_muet`, `son_remet`. SatMe-PC refuse tout le
> reste (PTT, CAT, émission DATV / SSTV / FT8 / ADALM-PLUTO, rotor) et
> répond « à faire à l'écran », quoi que le site envoie.

| Type | Corps envoyé | Réponse attendue | Quand |
|---|---|---|---|
| `commandes-etat` | JSON `{"type","version","t","reponses","passages","actions"}` | `{"ok":true}` | toutes les minutes (case **Questions** ou **Actions**), et juste après une action faite |
| `commandes-attente` | JSON `{"type","version","attente":25}` | `{"ok":true,"commandes":[{"id","intention","t","source"}]}` dès qu'une commande attend, sinon la liste vide au bout de `attente` secondes au plus | en boucle, case **Actions** cochée |
| `commandes-resultat` | JSON `{"type","version","id","ok","fr","en"}` | `{"ok":true}` | après chaque commande reçue |

- `reponses` : pour chaque question, la phrase prête à dire, en français et
  en anglais, à l'heure de la station — `prochain_passage` (vos favoris),
  `visibles`, `autopilote_etat`, `derniere_sstv`, `datv_etat`, `balise`,
  `rotor`, `prochain_aos`, `dernier_qso`, `aide` — chacune
  `{"fr","en"}` et, quand elle annonce un passage à venir, `"aos"` (ms UTC)
  pour que le site ajoute « dans 25 minutes » à l'heure où il répond.
- `passages` : le prochain passage (au-dessus de l'élévation minimale) des
  favoris, du satellite suivi, de ceux des campagnes et de quelques
  satellites courants (ISS, SO-50, AO-91, RS-44…), 40 au plus :
  `{"nom","catnum","cles","aos","los","el","fr","en"}`. `cles` : les noms du
  satellite normalisés (NFD sans accents, minuscules, lettres et chiffres
  seulement : « AO-91 » → `ao91`, « ISS (ZARYA) » → `isszarya`, `iss`,
  `stationspatiale`…) pour retrouver un nom dit.
- `actions` : les actions que la station accepte maintenant (vide quand la
  case **Actions** est décochée).
- Une commande : `intention` est l'une des huit actions ; une commande déjà
  vue (même `id`) n'est pas refaite. SatMe-PC garde la requête
  `commandes-attente` ouverte jusqu'à 25 s : votre site la tient tant qu'il
  n'a rien, et répond dès qu'une commande arrive (un délai côté proxy de
  60 s suffit).
- Un site qui ne connaît pas ces types répond `404` : SatMe-PC réessaie de
  moins en moins souvent (jusqu'à 5 min) et le dit sur la carte.


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
├── favoris.json                       les satellites favoris (si coché)
├── montage.json                       la description du montage (si coché)
├── diplomes.json, diplomes/…          les diplômes (si coché) et les images envoyées d'un clic
└── direct/direct.json, station.json, datv.jpg, sstv.jpg   (le direct, réécrit sur place)
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
- `direct/direct.json` (réécrit sur place), `direct/station.json`,
  `direct/datv.jpg`, `direct/sstv.jpg` si le direct est coché — pas de spectre vers un dossier ;
- `direct/ft8.json` (le dernier créneau) si **FT8/FT4** est coché ; un
  passage (`ft8-passage`) comme tout élément, `ft8-passage/<année>/<id>.data.json` (sa fiche `<id>.json`) ;
- `favoris.json` si **Satellites favoris** est coché ;
- `montage.json` si **Description du montage** est coché ;
- `diplomes.json` si **Diplômes** est coché, `diplomes/<id>.<ext>` pour
  chaque image envoyée par **Envoyer le diplôme reçu**.

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
