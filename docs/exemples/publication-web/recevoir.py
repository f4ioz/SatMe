#!/usr/bin/env python3
# SatMe-PC « Publication web » — a minimal receiver, API v1 (docs/publication-web.md).
# SPDX-License-Identifier: GPL-2.0-or-later — a short example: copy it, adapt it.
#
# Python 3.8 or later, standard library only:
#   SATME_JETON=change-me python3 recevoir.py --dossier ./satme-donnees --port 8765
# In SatMe-PC: address http://<this machine>:8765/satme/{type} (plain http only on your
# local network; behind a reverse proxy with HTTPS otherwise), the same token, « Tester ».
#
# POST /satme/<type> (or POST /satme with X-SatMe-Type): stores each item as
# <dossier>/<type>/<year>/<id>.<ext> + <id>.json, keeps <dossier>/index.json (newest first),
# takes « retrait » and the live state (direct.json, the pictures). GET serves the folder
# read-only, so index.html copied into it shows what was received.

import argparse
import email.parser
import email.policy
import hmac
import json
import os
import re
import secrets
import tempfile
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

JETON = os.environ.get("SATME_JETON", "")
MAX = 200 * 1024 * 1024
TYPES = {"sstv-image": {"png", "jpg", "gif"}, "planche": {"png", "jpg"}, "rapport": {"pdf"},
         "qso": {"adi"}, "journal-video": {"mp4", "webm"}}
VERROU = threading.Lock()


def ecrit(chemin, octets):
    """Written beside, then renamed: never half a file."""
    os.makedirs(os.path.dirname(chemin), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(chemin), prefix=".tmp-")
    with os.fdopen(fd, "wb") as f:
        f.write(octets)
    os.replace(tmp, chemin)


class Recepteur(SimpleHTTPRequestHandler):
    def repond(self, code, o):
        b = json.dumps(o, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        if JETON and not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + JETON):
            return self.repond(401, {"ok": False, "erreur": "jeton refusé"})
        m = re.fullmatch(r"/satme(?:/([a-z-]+))?/?", self.path.split("?")[0])
        if not m:
            return self.repond(404, {"ok": False, "erreur": "adresse inconnue"})
        type_ = m.group(1) or self.headers.get("X-SatMe-Type", "")
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX:
            return self.repond(413, {"ok": False, "erreur": "trop gros"})
        corps = self.rfile.read(n)
        racine = self.directory
        if type_ == "ping":
            return self.repond(200, {"ok": True, "nom": "Récepteur Python"})
        if type_ == "retrait":
            o = json.loads(corps or b"{}")
            with VERROU:
                liste = self.index()
                partis = [e for e in liste if e.get("type") == o.get("typeRetire") and e.get("cle") == o.get("cle")]
                for e in partis:
                    for c in (e["chemin"], e["chemin"].rsplit(".", 1)[0] + ".json"):
                        if ".." not in c and os.path.isfile(os.path.join(racine, c)):
                            os.remove(os.path.join(racine, c))
                self.ecrit_index([e for e in liste if e not in partis])
            return self.repond(200 if partis else 404, {"ok": bool(partis)})
        if type_ == "direct":
            with VERROU:
                p = os.path.join(racine, "direct", "direct.json")
                etat = json.load(open(p)) if os.path.isfile(p) else {}
                etat.update({k: v for k, v in json.loads(corps).items() if k not in ("type", "version")})
                ecrit(p, json.dumps(etat, ensure_ascii=False, indent=2).encode())
            return self.repond(200, {"ok": True})
        parties = self.multipart(corps)
        if type_ in ("direct-datv-image", "direct-sstv-image") and "fichier" in parties:
            ecrit(os.path.join(racine, "direct", type_[7:-6] + ".jpg"), parties["fichier"][1])
            return self.repond(200, {"ok": True})
        if type_ not in TYPES:
            return self.repond(403, {"ok": False, "erreur": "type non pris par ce site : " + type_})
        if "fichier" not in parties or "meta" not in parties:
            return self.repond(400, {"ok": False, "erreur": "parties meta et fichier attendues"})
        meta = json.loads(parties["meta"][1])
        nom = parties["fichier"][0] or ""
        ext = nom.rsplit(".", 1)[-1].lower() if "." in nom else ""
        if ext not in TYPES[type_]:
            return self.repond(415, {"ok": False, "erreur": "extension refusée : " + ext})
        id_ = re.sub(r"[^A-Za-z0-9-]", "", self.headers.get("X-SatMe-Id", "")) or secrets.token_hex(8)
        annee = str(meta.get("utc", ""))[:4]
        annee = annee if annee.isdigit() else time.strftime("%Y", time.gmtime())
        rel = f"{type_}/{annee}/{id_}"
        with VERROU:
            if os.path.isfile(os.path.join(racine, rel + ".json")):
                return self.repond(200, {"ok": True, "id": id_, "deja": True})
            ecrit(os.path.join(racine, f"{rel}.{ext}"), parties["fichier"][1])
            meta["chemin"] = f"{rel}.{ext}"
            meta["ajoute"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            ecrit(os.path.join(racine, rel + ".json"), json.dumps(meta, ensure_ascii=False, indent=2).encode())
            cle = meta.get("cle", "")
            liste = [e for e in self.index() if not (cle and e.get("cle") == cle and e.get("type") == type_)]
            self.ecrit_index([meta] + liste)
        return self.repond(201, {"ok": True, "id": id_, "url": meta["chemin"]})

    def multipart(self, corps):
        """{name: (filename, bytes)} of a multipart/form-data body (the standard library's email parser)."""
        ct = self.headers.get("Content-Type", "")
        if not ct.startswith("multipart/form-data"):
            return {}
        msg = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
            b"Content-Type: " + ct.encode() + b"\r\n\r\n" + corps)
        r = {}
        for part in msg.iter_parts():
            nom = part.get_param("name", header="content-disposition")
            if nom and nom not in r:
                r[nom] = (part.get_filename(), part.get_payload(decode=True) or b"")
        return r

    def index(self):
        p = os.path.join(self.directory, "index.json")
        return json.load(open(p, encoding="utf-8")).get("elements", []) if os.path.isfile(p) else []

    def ecrit_index(self, liste):
        o = {"version": 1, "maj": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "elements": liste[:500]}
        ecrit(os.path.join(self.directory, "index.json"), json.dumps(o, ensure_ascii=False, indent=2).encode())


if __name__ == "__main__":
    a = argparse.ArgumentParser(description="Receives what SatMe-PC sends (Publication web, API v1).")
    a.add_argument("--dossier", default="satme-donnees")
    a.add_argument("--port", type=int, default=8765)
    a.add_argument("--ecoute", default="127.0.0.1", help="0.0.0.0 to be reached from the local network")
    x = a.parse_args()
    os.makedirs(x.dossier, exist_ok=True)
    if not JETON:
        print("Attention : pas de jeton (SATME_JETON) — n'importe qui sur le réseau peut envoyer.")
    ThreadingHTTPServer((x.ecoute, x.port), partial(Recepteur, directory=os.path.abspath(x.dossier))).serve_forever()
