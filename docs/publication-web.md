# Web publishing: SatMe-PC pushes to your site

*[Version française](publication-web.fr.md)*

SatMe-PC can **push items to an outside web site**: SSTV pictures, planches,
event reports, contacts, the journal's videos, and as an option live data
(auto-pilot, QO-100 spectrum, DATV picture), your favourite satellites and your awards. Your site receives them **as it
likes** — a PHP file on shared hosting, a WordPress plugin, a small Python or
Node server, anything that can answer an HTTP request. This page covers the
setting in SatMe-PC and **the wire format (API v1)**, stable and versioned,
with ready-to-use receivers.

With no server at all, SatMe-PC can also **write into a folder** (see
[No server: a folder](#no-server-a-folder)).

SatMe-PC always does the sending: it works from behind a home router, and
**nothing is opened on the station** (the site never connects to the PC).

Contents: [Setup](#setup-in-satme-pc) · [API v1](#api-v1) ·
[Types](#types) · [Responses](#responses) · [Queue and retries](#queue-and-retries) ·
[Live data](#live-data-optional) · [Security](#security) ·
[Examples](#ready-to-use-receivers) · [Displaying](#displaying-what-was-received) ·
[Folder](#no-server-a-folder)

The settings' names below are the French ones shown by SatMe-PC.

---

## Setup in SatMe-PC

1. **Réglages › Modules**: tick **Publication web**. The module is **off by
   default** and no profile switches it on: while it is unticked, SatMe-PC
   sends nothing to anyone (no queue, no test, no live data) and shows no 🌐
   button.
2. **Réglages › Services en ligne › Envoyer vers un site web**:
   - **Où envoyer** (where): **Adresse web (HTTPS)** or **Dossier sur ce PC**
     (a folder).
   - **The address** that receives. Two ways:
     - with `{type}` in it, replaced by each request's type:
       `https://example.org/api/satme/{type}` → `…/api/satme/sstv-image`,
       `…/api/satme/ping`;
     - without `{type}`: everything goes to **that one address** (webhook
       style), the type in the `X-SatMe-Type` header (and the form's `type`
       field): `https://example.org/satme/receive.php`.
   - **The token** (optional, advised): a secret **you** make up (for instance
     `openssl rand -hex 32`) and your site checks; sent as
     `Authorization: Bearer <token>`. It stays on this PC and is never shown
     again.
   - **Tester** (test): SatMe-PC sends a `ping` (nothing is published).
     Sending is **ready** only after a test that passes; another address,
     token or folder is tested again.
   - **Quoi envoyer** (what to send): each type **Sur clic** (at a click: the
     🌐 button of each item), **Automatique** (as it comes) or **Jamais**
     (never). **Everything starts at « Sur clic »**: nothing goes by itself
     until you choose « Automatique ».
3. The **🌐** buttons appear: SSTV viewer, log lines, Planche page (planche
   and report), videos made by the journal's editor.

`http://` (no S) is accepted only towards this machine or the local network
(`127.0.0.1`, `192.168.…`, `10.…`, `172.16–31.…`, `….local`, a name without a
dot); elsewhere `https://` is required. Redirects are not followed: give the
final address.

---

## API v1

Every request is **one `POST`** to the address set.

### Headers, always

| Header | Example | Meaning |
|---|---|---|
| `User-Agent` | `SatMe-PC/2026.10.34 (+https://github.com/f4ioz/SatMe)` | the software and its version |
| `X-SatMe-Version` | `1` | the format's version; a receiver may refuse a version it does not know |
| `X-SatMe-Type` | `sstv-image` | the request's type (below) |
| `X-SatMe-Id` | `0b6f7c1e-3d0a-4c9e-9a51-6f4f0c2b8e21` | unique id of **this delivery**: the same from one try to the next (see [idempotency](#idempotency)) |
| `Authorization` | `Bearer 3f9a…` | only when a token is set |
| `Content-Type` | `multipart/form-data; boundary=…` or `application/json; charset=utf-8` | depending on the type |

The version changes only if a receiver written for v1 would stop working:
adding a type or a field does not change it. **Ignore the fields you do not
know.**

### Media: `multipart/form-data`

The types `sstv-image`, `planche`, `rapport`, `qso`, `journal-video` (and the
live pictures) arrive like an HTML file-upload form, three fields in this
order:

| Field | Content |
|---|---|
| `type` | the type, as text (`sstv-image`) — the same as `X-SatMe-Type` |
| `meta` | a JSON (`application/json`): the words that go with it (below) |
| `fichier` | the file, with its name (`filename`) and its `Content-Type` |

In PHP: `$_POST['type']`, `json_decode($_POST['meta'], true)`,
`$_FILES['fichier']`. In Python: any multipart reader.

#### The `meta` JSON

Always there:

| Field | Type | Example | Meaning |
|---|---|---|---|
| `version` | number | `1` | format version |
| `type` | string | `"sstv-image"` | the type |
| `id` | string | `"0b6f7c1e-…"` | the same as `X-SatMe-Id` |
| `cle` | string | `"sstv:ISS_20261003_135119.png"` | **the item** on SatMe-PC's side: a new version of the same item (a planche redone) has the same `cle` and replaces the old one |
| `titre` | string | `"ISS · PD120"` | a title to show |
| `fichier.nom` | string | `"ISS_20261003_135119.png"` | suggested name (never use it as is as a file name on the server) |
| `fichier.type` | string | `"image/png"` | MIME type |
| `fichier.taille` | number | `48213` | bytes |
| `fichier.sha256` | string | `"9f86d0…"` | SHA-256 (hex) of the file |

Depending on the type, when SatMe-PC knows them:

| Field | Type | Example | For |
|---|---|---|---|
| `satellite` | string | `"ISS"` | sstv-image, qso, journal-video |
| `utc` | ISO 8601 string | `"2026-10-03T13:51:19Z"` | all (for a planche or a report: the time it was sent) |
| `mode` | string | `"PD120"`, `"FM"` | sstv-image, qso |
| `station` | string | `"F4XYZ · JN18FS"` | your call sign and locator |
| `evenement` | string | `"ARISS octobre 2026"` | sstv-image (campaign), planche, rapport |
| `description` | string | `"Diapo 3/12"` | sstv-image |
| `incomplete` | boolean | `true` | sstv-image partly received |
| `indicatif` | string | `"EA4ABC"` | qso: the station worked |
| `locator` | string | `"IN80DD"` | qso: its locator |

#### SSTV: passes, auto-pilot, planches (optional)

All optional: an older SatMe-PC does not send
them, and a receiver may ignore them. Times are ISO 8601 UTC strings. A **pass
id** is the satellite's NORAD number and its AOS to the minute
(`"25544-20261003T1349Z"`): the same pass has the same id in every item that
names it, as long as it is worked out from the same elements (when in doubt,
compare the satellite and the times). A **key** (`"sstv:…"`, `"journal:…"`,
`"rapport:…"`, `"planche:…"`) is another item's `cle`: it may name an item
not published (yet) on your site.

`sstv-image` and `journal-video`:

| Field | Type | Example | Meaning |
|---|---|---|---|
| `catnum` | number | `25544` | the satellite's NORAD number |
| `passage` | string | `"25544-20261003T1349Z"` | the pass it came on (pass id) |
| `aos`, `los` | string | `"2026-10-03T13:49:30Z"` | that pass's AOS and LOS |
| `elMax` | number | `62.4` | its highest elevation (degrees) |
| `autopilote` | boolean | `true` | received by the SSTV auto-pilot (Agenda › SSTV) |
| `campagne` | string | `"ARISS série 34"` | the auto-pilot's campaign that received it |
| `diapo`, `diapos` | number | `3`, `12` | sstv-image: its slide, set by hand (3 of 12) |
| `planches` | list of keys | `["planche:serie34"]` | journal-video: the planches of the event it belongs to |

`planche`:

| Field | Type | Example | Meaning |
|---|---|---|---|
| `satellite`, `catnum` | string, number | `"ISS"`, `25544` | the satellite of its event (else of most of its pictures) |
| `evenementId` | string | `"c:ariss-34"` | its event's id (stable) |
| `evenementGenre` | string | `"campagne"` | `campagne` (an auto-pilot campaign), `activite` (an activity announced), `images` (a run of pictures) |
| `evenementDebut`, `evenementFin` | string | | the event's period |
| `campagne`, `campagneId` | string | `"ARISS série 34"`, `"ariss-34"` | when the event is an auto-pilot campaign |
| `mode` | string | `"PD120"` | the most frequent SSTV mode of its pictures |
| `premiere`, `derniere` | string | | the first and the last picture |
| `images` | number | `23` | pictures of the event |
| `diapos` | number | `12` | slides expected |
| `diaposRecues` | number | `10` | slides received |
| `diaposManquantes` | list of numbers | `[5, 6]` | the slides missing |
| `meilleures` | list | see below | the best picture of each slide received |
| `passages` | list | see below | the passes that brought pictures, oldest first |
| `autopilote` | boolean | `true` | at least one of those passes was received by the auto-pilot |
| `videos` | list of keys | `["journal:SatMe_ISS_…mp4"]` | the journal's videos of those passes |
| `rapport` | key | `"rapport:serie34"` | its event report |

`meilleures[]`: `{"diapo": 3, "image": "sstv:<file>.png", "utc": "…", "note": 82, "copies": 4, "estimee": true}`
(`note`: SatMe's mark 0–100 when known; `estimee`: the slide number was
estimated from the timetable).
`passages[]`: `{"id": "25544-20261003T1349Z", "aos": "…", "los": "…", "elMax": 62.4, "images": 5, "diapos": [3, 4],
"autopilote": true, "campagne": "ARISS série 34", "video": "journal:<file>.mp4"}`.

`rapport`: `planche` (the planche's key), `satellite`, `catnum`,
`evenementId`, `evenementGenre`, `campagne`, `campagneId`, `premiere`,
`derniere`, as above.

Lists and objects are small (a series has a few dozen passes at most); a
receiver that keeps only flat fields loses nothing else.

A whole example (`sstv-image`):

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

<the PNG's bytes>
--SatMe-5c1e…--
```

### Data only: `application/json`

`ping`, `retrait` and the live data are a JSON, which carries `type` and
`version` too.

---

## Types

| Type | Body | File | Sent |
|---|---|---|---|
| `ping` | JSON | — | by **Tester** |
| `sstv-image` | multipart | PNG | an SSTV picture of the gallery (at a click, or each picture received live in Automatique) |
| `planche` | multipart | JPEG (1600 px) | a planche; in Automatique, those with pictures, 2 min after the last picture received |
| `rapport` | multipart | PDF | a planche's event report |
| `qso` | multipart | ADIF (`.adi`, one record) | a contact of the log; in Automatique, each contact added |
| `journal-video` | multipart | MP4 | a video made by the journal's editor (can be hundreds of MB) |
| `retrait` | JSON | — | withdraw an item (no button in SatMe-PC yet; part of the API) |
| `direct`, `direct-spectre`, `direct-datv-image`, `direct-sstv-image`, `direct-station`, `direct-efface` | JSON or multipart | JPEG | [live data](#live-data-optional), only when ticked |
| `favoris` | JSON | — | the [favourite satellites](#favourite-satellites), only when ticked |
| `diplomes` | JSON | — | the [awards](#awards) followed and your progress, only when ticked |
| `diplome-image` | multipart | PNG, JPEG, PDF | the picture of an [award received](#awards), only on a click |

**`ping`**: `{"type":"ping","version":1,"logiciel":"SatMe-PC/…","types":["sstv-image","planche",…]}`
(`types`: those not set to « Jamais »). The receiver answers **any 2xx**. If
it answers a JSON, SatMe-PC optionally reads `nom` or `indicatif` and `site`
(shown: « ok : site de F4XYZ (https://…) »), and `refuses` (a list of types
the site does not take, named on the card).

**`retrait`**: `{"type":"retrait","version":1,"typeRetire":"sstv-image","cle":"sstv:…","id":"<the id the site gave back when publishing, or empty>"}`.
2xx or 404: withdrawn.

### Idempotency

- `X-SatMe-Id` identifies **a delivery**: if it comes again (the network
  dropped after you stored it, before SatMe-PC read the answer), it is the
  same one. Simplest: if you already have an item with this id, answer `200`
  without storing it again.
- `cle` identifies **the item**: the same item sent again later (a planche
  redone) has a new `X-SatMe-Id` but the same `cle`; replace the old one.

---

## Responses

| Response | SatMe-PC does |
|---|---|
| **2xx** | accepted. Optionally a JSON `{"id": "…", "url": "…", "deja": true}`: the `id` is sent back in a `retrait`; `deja` shows « déjà en ligne » (already online) |
| **401** | token refused: **everything stops**, the item stays queued, the card asks to test the connection again |
| **403** | refused (this type is not taken by the site): the item leaves the queue, the refusal is noted on the card (with `{"erreur": "…"}` if given) |
| **413** | too large: leaves the queue, noted |
| **3xx** and other **4xx** (400, 404, 415…) | final refusal: leaves the queue, noted |
| **408**, **429**, **5xx**, no answer | tried again later (see the queue) |

An `{"erreur": "…"}` in the response is shown as is on the card: write it for
a human.

---

## Queue and retries

Nothing goes from the page itself: each item is first **copied into a queue
on disk** (SatMe-PC's data folder, `site/`: `file.json` and `envois/`; a video
is read where it is). A sender thread wakes every 5 seconds and sends what is
due, **one at a time, in order**.

- No answer, 408, 429 or 5xx: tried again after **30 s, 1 min, 2 min, 4 min,
  8 min, 16 min, 32 min, then every hour**, with the **same `X-SatMe-Id`**;
  each item keeps its own pace.
- The queue survives a restart; 300 items at most (the oldest go).
- The same item asked again while it waits: only the new version stays.
- On the card: what waits, the last try, the last answers, **Réessayer
  maintenant** (retry now), **Vider la file** (empty the queue).

---

## Live data (optional)

Under **En direct**, each part is ticked on its own (nothing by default). No
queue: what the site does not take is dropped, and the next try waits (30 s,
1 min… 5 min; 2 min after a 403, 10 min after a 401). Only names, times,
measurements, a spectrum and pictures — never an address, a pairing code, a
password or a key.

| Type | Body | Pace |
|---|---|---|
| `direct` | JSON `{"type","version","t": <UTC ms>, "pilote": {…} \| null}` or `{…, "datv": {…} \| null}`: each part present replaces the previous one, `null` clears it | Auto-pilot: 10 s (once a minute when unchanged); DATV: 3 to 30 s |
| `direct-spectre` | JSON `{"type","version","centreHz","largeurHz","minDb","maxDb","bins"}`: `bins` = 1024 bytes in base64, 0 = `minDb`, 255 = `maxDb` | 1 to 10 s, while the SDR is on QO-100 |
| `direct-datv-image` | multipart, `fichier` = JPEG ≤ 190 KB, 640 px | while the DATV receiver is locked |
| `direct-sstv-image` | multipart, `fichier` = JPEG | every 5 s during an auto-pilot pass, when the picture changed |
| `direct-efface` | JSON `{"type","version","t"}` | the **Effacer le direct** button: forget all live data |

`pilote`: `{"maitre": bool, "prochain": {"campagne","sat","aos","los","el"}, "enCours": {…, "phase","mode","images","elMaintenant","azMaintenant","image": {"phase","progression","mode","prochaine"}}, "derniere": {"sat","utc","mode"}}` (times in UTC ms).
`datv`: `{"verrouille","cielKhz","srKs","service","fournisseur","modcod","mer","marge"}`.

A receiver that does not want live data answers `403` (or `404`) to these
types.

### Favourite satellites

Under **En direct**, the **Satellites favoris** line (unticked by default)
sends the list of the satellites marked ★ in SatMe-PC, for instance for a
home page to feature them. It goes as soon as the list changes, otherwise at
most once an hour, and only while the module is on and the target tested.

```json
{"type":"favoris","version":1,"satellites":[{"nom":"ISS","norad":25544},{"nom":"SO-50","norad":27607}],"t":1798000000000}
```

`satellites` is the whole list (sorted by name; empty when there is no
favourite left): it replaces the previous one. `t`: when it was sent, UTC ms.
To a folder: `favoris.json` at the root, same content.

A receiver that does not know this type answers `400`, `404` or `415`:
SatMe-PC shows « non pris en charge par le site », without an error, and
tries again only the next day (or when the line is ticked again).

### Awards

Under **En direct**, the **Diplômes** line (unticked by default) sends the
awards followed in **Agenda › Diplômes** (which must be active), for instance
for an « Awards » page on your site: as soon as they change (looked at once a
minute), otherwise once a day, only when the module is on and the target
tested.

```json
{"type":"diplomes","version":1,"t":1798000000000,"diplomes":[
 {"id":"cat:vucc","nom":"VUCC Satellite","emetteur":"ARRL","sorte":"permanent","statut":"en_cours",
  "libelle":"En cours : 37/100 carrés","progression":{"actuel":37,"objectif":100,"unite":"carrés"},
  "reglement":"https://www.arrl.org/vucc"},
 {"id":"cat:amsat-scc","nom":"AMSAT Satellite Communicators Club","emetteur":"AMSAT","sorte":"permanent",
  "statut":"recu","recu":1790000000000,"progression":{"actuel":1,"objectif":1,"unite":"contacts"},"image":true},
 {"id":"pzk:75-2","nom":"ARISS SSTV …","emetteur":"ARISS (géré par le PZK)","sorte":"evenement","statut":"a_venir",
  "debut":1798100000000,"fin":1798300000000,"limite":1800500000000,"satellite":"ISS","mode":"PD120",
  "reglement":"https://ariss.pzk.org.pl/sstv/"}]}
```

`diplomes` is the whole list and replaces the previous one. Per award: `id`
(stable), `nom` (name), `emetteur` (issuer), `sorte` (`permanent`: counted
from the log; `evenement`: pictures received during a window), `statut`
(`en_cours` in progress, `obtenable` goal reached, to ask for, `demande`
asked, `recu` received, `a_venir` to come, `pendant` the event is on),
`libelle` (SatMe's sentence, in French), `progression` (`actuel`, `objectif`,
`unite`; standing awards), `debut`, `fin`, `limite` (deadline to ask),
`demande`, `recu` (UTC ms), `reglement` (rules, https), `satellite` and
`mode` (events), `image` (`true` once the received award's picture was sent).
Ignore an unknown field; a missing one has no value.

**Never sent**: what was counted (squares, entities, states, callsigns
worked), the pictures' names, the notes, the name and e-mail of « Pour les
demandes », the forms, and the awards hidden, missed, expired or « to check ».

**The picture of an award received** (`diplome-image`) goes only on a click:
in Agenda › Diplômes, **Envoyer le diplôme reçu** on an award whose picture is
attached (the button shows only when web publishing is ready). Multipart like
the media, `meta`: `{"diplome": "<id>", …}`, the file: the picture as kept
(PNG or JPEG; made a JPEG of 3000 px at most when over 8 MB) or a PDF of 8 MB
at most. No queue: when the site does not answer, the button says so; click
again.

To a folder: `diplomes.json` at the root (same content) and
`diplomes/<id>.png|jpg|pdf`. A receiver that does not know `diplomes` answers
`400`, `404` or `415`: left alone until the next day, without an error.

### The station

Under **En direct**, the **Station** line (unticked by default) sends the
station as it is now, for a live page to draw the satellite, the antennas and
the receiving chain beside the pictures: type `direct-station`, JSON, about
1 KB. It goes when something changed, at most every 2 s (2 to 10 s, chosen on
the line), and every 30 s when nothing changed — so a receiver can take a
silence of a minute or two as « station off line ».

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

| Key | Meaning |
|---|---|
| `satellite` | the satellite followed, or QO-100 while the station works it (SDR or DATV receiver on it) and the one followed is below the horizon; `geo`: geostationary (then no `aos`/`los`); `az`/`el` (°), `distanceKm`, `vitesseKmS` (+ moving away) from the station; `aos`, `los`, `elMax`: the pass under way or the next one; `emetteurs`: up to 6 of its transmitters (SatNOGS' public list), `choisi` the one in use |
| `antennes.rotor` | only when a rotor is set: `az`/`el` as the controller reads them back, `cibleAz`/`cibleEl` where it is sent, `enMouvement`, `horsPortee` (beyond its range), `azSeul` (no elevation) |
| `antennes.parabole` | a geostationary satellite: the fixed dish's pointing from the station's locator |
| `reception.sdr` | the SDR: on or off, its kind (`RTL-SDR`, `ADALM-PLUTO`, `bladeRF`, `SatMe-Node`, `FI du poste`, …), the sky frequency heard |
| `reception.datv` | the DATV receiver: on, locked; while locked, `mer` and `marge` (dB), `service` |
| `reception.poste` | only while the rig is connected: its model's name, its sky frequencies and mode as read back |
| `enEmission` | something transmits now (yes or no, nothing more) |
| `mode` | what the station does, when known: `DATV`, `FT8`, `FT4`, `SSTV`, `CW`, `phonie`, `numérique` |

Every key is optional except `t` and `enEmission`. Never in it: an address, a
port, a serial device, a file path, a pairing code, a token, another setting.
To a folder: `direct/station.json`, same content. A receiver that does not know
the type answers `400`, `404` or `415`: SatMe-PC shows « non pris en charge par
le site », without an error, and tries again ten minutes later.

The settings call, for a script (`POST /api/site/direct`, the same as the
line's box), parameters in the query string: `POST /api/site/direct?on_station=1` (or `0`), `&periode_station=2` (2 to 10 s).

---

## Security

- **HTTPS** on the Internet (SatMe-PC refuses `http://` outside the local
  network).
- **A token**, long and random, compared in constant time (`hash_equals` in
  PHP, `hmac.compare_digest` in Python). Never put it in a public page or a
  repository.
- **Check the type and the size**: an allow-list of types and extensions, a
  maximum size per type; refuse the rest (`403`, `413`, `415`).
- **Never trust the file name**: name files yourself (for instance after
  `X-SatMe-Id`), with no `..` or `/`.
- **Store outside the web root** when you can, and serve the files through a
  script or a rule that does not execute them. Otherwise, forbid execution in
  the receiving folder (Apache: `php_flag engine off` /
  `RemoveHandler .php .phtml` / `Options -ExecCGI` in an `.htaccess`, or better
  `Require all denied` and a script serving the files). **Never execute an
  uploaded file.**
- Escape every text of `meta` when displaying it (it is free text).
- Rate-limit if your host allows it.

---

## Ready-to-use receivers

In [`docs/exemples/publication-web/`](exemples/publication-web/)
(GPL-2.0-or-later, like the rest of the repository: copy them, adapt them):

- **[`recevoir.php`](exemples/publication-web/recevoir.php)** — a single PHP
  file (7.4+) for shared hosting: checks the token and the type, stores the
  media and its JSON card in a folder, keeps `index.json` up to date, answers
  JSON. Set `TOKEN` and `DIR` at the top; in SatMe-PC, the address
  `https://your-site/recevoir.php` (no `{type}`). For videos, PHP's
  `upload_max_filesize` and `post_max_size` must follow.
- **[`recevoir.py`](exemples/publication-web/recevoir.py)** — Python 3,
  standard library only: the same, plus `retrait` and the live data
  (`direct/direct.json`, the pictures). It also serves the folder read-only
  (GET):

  ```sh
  SATME_JETON=$(openssl rand -hex 32) python3 recevoir.py --dossier ./satme-donnees --port 8765
  ```

  Address in SatMe-PC: `http://127.0.0.1:8765/satme/{type}` on the same
  machine (`--ecoute 0.0.0.0` for the local network). On the Internet, put it
  behind an HTTPS server (nginx, Caddy).
- **[`index.html`](exemples/publication-web/index.html)** — a plain HTML and
  JavaScript page that reads `index.json` and shows the pictures, videos,
  documents and contacts.

All three share the layout of the [folder](#no-server-a-folder) target: a page
made for one works with the others.

---

## Displaying what was received

Everything is in the receiving folder:

```
satme-donnees/
├── index.json                         the latest items, newest first
├── sstv-image/2026/0b6f7c1e-….png     the file
├── sstv-image/2026/0b6f7c1e-….json    its card: the meta JSON + "chemin" + "ajoute"
├── qso/2026/….adi + ….json
├── favoris.json                       the favourite satellites (when ticked)
├── diplomes.json, diplomes/…          the awards (when ticked) and the pictures sent with a click
└── direct/direct.json, station.json, datv.jpg, sstv.jpg   (live data, rewritten in place)
```

`index.json`:

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

- A static page: copy `index.html` beside `index.json`, or write your own
  (one `fetch('index.json')`, one loop).
- WordPress, a CMS: read `index.json` (or the cards) from a template or a
  plugin.
- A static site generator: Hugo reads JSON as data
  (`resources.Get` / `transform.Unmarshal`), Jekyll through `_data/`.

---

## No server: a folder

Choose **Dossier sur ce PC** at step 1 and give a full path
(`/home/me/Nextcloud/satme`, `C:\Users\me\Sync\satme`). **Tester** checks it
can be written. SatMe-PC then writes each item sent there, with the same
queue, the same **Sur clic / Automatique / Jamais** choices and the same cards
as the API:

- `<folder>/<type>/<YYYY>/<id>.<ext>` and `<id>.json` (the API's `meta`, plus
  `chemin` and `ajoute`); the year comes from `utc`;
- `index.json`: the last 500, newest first (a new version of the same item
  replaces the old one, files included);
- `flux.xml`: an Atom feed of the last 50 (links relative to the folder);
- `direct/direct.json` (rewritten in place), `direct/station.json`,
  `direct/datv.jpg`, `direct/sstv.jpg` when live data is ticked — no spectrum
  to a folder;
- `favoris.json` when **Satellites favoris** is ticked;
- `diplomes.json` when **Diplômes** is ticked, `diplomes/<id>.<ext>` for each
  picture sent with **Envoyer le diplôme reçu**.

Each file is written beside its place then renamed (atomic where the system
allows): a reader never sees half a file, and the index never names a file not
yet there.

What for:

- a **static site** (Hugo, Jekyll, HTML): the folder inside the site, then
  your usual publishing;
- a **sync tool** you already have: Nextcloud, Syncthing, an rsync or FTP
  mirror (`lftp mirror -R`), a shared folder. **SatMe-PC never does FTP
  itself.**

The folder is not available in the demonstration.
