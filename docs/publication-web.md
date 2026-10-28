# Web publishing: SatMe-PC pushes to your site

*[Version française](publication-web.fr.md)*

SatMe-PC can **push items to an outside web site**: SSTV pictures, planches,
event reports, contacts, the journal's videos, and as an option live data
(auto-pilot, QO-100 spectrum, DATV picture). Your site receives them **as it
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

This repository's `site` module (`site/`) is one complete receiver (gallery,
logbook, live block), with the address
`https://<your site>/api/publication/{type}`. It is not needed: the small
examples below are enough to start.

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
| `direct`, `direct-spectre`, `direct-datv-image`, `direct-sstv-image`, `direct-efface` | JSON or multipart | JPEG | [live data](#live-data-optional), only when ticked |

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
└── direct/direct.json, datv.jpg, sstv.jpg   (live data, rewritten in place)
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
- `direct/direct.json` (rewritten in place), `direct/datv.jpg`,
  `direct/sstv.jpg` when live data is ticked — no spectrum to a folder.

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
