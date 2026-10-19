# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Free software under the GNU GPL, version 2 or later. Without any warranty.
# The full licence text is in the LICENSE file.
"""
SatMe-PC's voice helper for « Traduction parlée »: the translation of an
over said by a synthetic voice, on this PC only (never transmitted, nothing
sent anywhere).

Two long-lived uses, one JSON object per line each way:

  --parle --voix DIR    Piper (piper-tts) with the voice models in DIR.
      in : {"id":7,"voix":"fr_FR-siwis-medium","locuteur":"","phrases":["…","…"],"longueur":1.0}
      out: {"pret":true,"piper":"1.8.0"} once, then
           {"id":7,"rate":22050,"phrases":["<base64 s16le>",…],"calcul_s":0.2} or {"id":7,"erreur":"…"}

  --convertit --clone DIR   the tone colour converter (OpenVoice V2, MIT) in its own environment (torch, CPU).
      in : {"id":7,"ref":"<base64 s16le>","ref_rate":16000,"src":"<base64 s16le>","src_rate":22050,"tau":0.3}
      out: {"pret":true,...} once, then {"id":7,"rate":22050,"pcm":"<base64 s16le>","calcul_s":1.2}

Others, then exit:
  --verifie --voix DIR             piper's version, the voice models present
  --installe-voix --voix DIR --liste fr_FR-siwis-medium,…   download those models (Hugging Face), progress lines
  --verifie-clone --clone DIR      the converter's imports and checkpoint
  --installe-clone --clone DIR     download the converter's checkpoint (Hugging Face)

Never downloads anything unless --installe-voix or --installe-clone is given.
"""
import base64
import json
import os
import re
import sys
import time
import types
import urllib.parse

VOIX_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/"
CLONE_URL = "https://huggingface.co/myshell-ai/OpenVoiceV2/resolve/main/converter/"

_sortie = sys.stdout


def isole_sortie():
    """stdout carries only our lines: whatever a library prints goes to stderr."""
    global _sortie
    _sortie = os.fdopen(os.dup(1), "w", encoding="utf-8", buffering=1)
    os.dup2(2, 1)
    sys.stdout = sys.stderr


def dit(obj):
    _sortie.write(json.dumps(obj, ensure_ascii=False) + "\n")
    _sortie.flush()


def arguments(argv):
    a = {"voix": None, "clone": None, "liste": "", "mode": "parle"}
    i = 1
    while i < len(argv):
        x = argv[i]
        if x in ("--voix", "--clone", "--liste") and i + 1 < len(argv):
            a[x[2:]] = argv[i + 1]
            i += 1
        elif x in ("--parle", "--convertit", "--verifie", "--installe-voix", "--verifie-clone", "--installe-clone"):
            a["mode"] = x[2:]
        i += 1
    return a


# ---------------------------------------------------------------------- voices

def chemin_voix(ident):
    """A model's path in the voices' repository: fr_FR-siwis-medium → fr/fr_FR/siwis/medium/fr_FR-siwis-medium."""
    m = re.fullmatch(r"([a-z]{2,3})_([A-Z]{2})-(.+)-(x_low|low|medium|high)", ident)
    if not m:
        raise ValueError("nom de voix inattendu : " + ident)
    langue, pays, nom, qualite = m.groups()
    loc = langue + "_" + pays
    return "/".join([langue, loc, nom, qualite, ident])


def url_voix(ident, extension):
    return VOIX_URL + urllib.parse.quote(chemin_voix(ident) + extension)


def voix_presentes(dossier):
    """The models with their settings beside them: their names."""
    if not dossier or not os.path.isdir(dossier):
        return []
    return sorted(f[:-5] for f in os.listdir(dossier)
                  if f.endswith(".onnx") and os.path.isfile(os.path.join(dossier, f + ".json")))


def version_piper():
    try:
        from importlib.metadata import version
        return version("piper-tts")
    except Exception:
        return ""


def verifie(a):
    r = {"ok": False, "python": sys.version.split()[0], "voix": voix_presentes(a["voix"])}
    try:
        import piper  # noqa: F401
        r["piper"] = version_piper() or "?"
        r["ok"] = True
    except Exception as e:
        r["erreur"] = "piper-tts absent : " + str(e)
    dit(r)


def ouvre(url):
    import urllib.request
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SatMe-PC (satme_voix.py)"}), timeout=60)


def telecharge_url(url, chemin, etiquette):
    tmp = chemin + ".part"
    with ouvre(url) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        lu, dernier = 0, 0.0
        while True:
            b = r.read(1 << 16)
            if not b:
                break
            f.write(b)
            lu += len(b)
            if time.time() - dernier > 1.0:
                dernier = time.time()
                dit({"progres": etiquette, "mo": round(lu / 1e6, 1), "total": round(total / 1e6, 1)})
    os.replace(tmp, chemin)


def installe_voix(a):
    dossier = a["voix"]
    os.makedirs(dossier, exist_ok=True)
    echecs = []
    for ident in [x for x in a["liste"].split(",") if x]:
        if ident in voix_presentes(dossier):
            continue
        try:
            # The settings first: a model without them is not counted as there.
            telecharge_url(url_voix(ident, ".onnx.json"), os.path.join(dossier, ident + ".onnx.json.tmp"), ident)
            telecharge_url(url_voix(ident, ".onnx"), os.path.join(dossier, ident + ".onnx"), ident)
            os.replace(os.path.join(dossier, ident + ".onnx.json.tmp"), os.path.join(dossier, ident + ".onnx.json"))
            dit({"voix": ident, "mo": round(os.path.getsize(os.path.join(dossier, ident + ".onnx")) / 1e6, 1)})
        except Exception as e:
            echecs.append(ident)
            dit({"erreur": "voix " + ident + " : " + str(e)})
            for f in (".onnx", ".onnx.part", ".onnx.json.tmp", ".onnx.json.tmp.part"):
                try:
                    os.remove(os.path.join(dossier, ident + f))
                except OSError:
                    pass
    dit({"fini": True, "voix": voix_presentes(dossier), "echecs": echecs})
    if echecs:
        sys.exit(2)


def en_base64(x):
    """Float samples (−1…1) as base64 s16le."""
    import numpy as np
    return base64.b64encode(np.clip(np.asarray(x) * 32767.0, -32768, 32767).astype("<i2").tobytes()).decode("ascii")


def de_base64(b):
    import numpy as np
    return np.frombuffer(base64.b64decode(b), dtype="<i2").astype(np.float32) / 32768.0


class Synthese:
    """Piper's voices, loaded once each."""

    def __init__(self, dossier):
        self.dossier = dossier
        self.charges = {}

    def voix(self, nom):
        v = self.charges.get(nom)
        if v is None:
            from piper import PiperVoice
            chemin = os.path.join(self.dossier, nom + ".onnx")
            if not os.path.isfile(chemin):
                raise LookupError("voix " + nom + " absente (Réglages › Phonie → texte › Traduction parlée)")
            v = PiperVoice.load(chemin)
            self.charges[nom] = v
        return v

    def dit(self, nom, locuteur, texte, longueur):
        """One phrase: its samples (float32) and their rate. Sentences inside it joined by 0.12 s of silence."""
        import numpy as np
        from piper import SynthesisConfig
        v = self.voix(nom)
        sid = None
        if locuteur:
            sid = v.config.speaker_id_map.get(locuteur) if getattr(v.config, "speaker_id_map", None) else None
        cfg = SynthesisConfig(speaker_id=sid, length_scale=float(longueur), normalize_audio=True, volume=0.9)
        rate = v.config.sample_rate
        morceaux = []
        for c in v.synthesize(texte, cfg):
            if morceaux:
                morceaux.append(np.zeros(int(0.12 * rate), dtype=np.float32))
            morceaux.append(c.audio_float_array)
        return (np.concatenate(morceaux) if morceaux else np.zeros(0, dtype=np.float32)), rate


def repond_parle(synthese, demande):
    """One request of the speaking loop: its answer (a dict)."""
    t = time.time()
    phrases, rate = [], 22050
    for texte in demande.get("phrases") or []:
        texte = str(texte).strip()
        if not texte:
            phrases.append("")
            continue
        x, rate = synthese.dit(str(demande.get("voix") or ""), str(demande.get("locuteur") or ""), texte,
                               max(0.5, min(2.0, float(demande.get("longueur") or 1.0))))
        phrases.append(en_base64(x))
    return {"id": demande.get("id"), "rate": rate, "phrases": phrases, "calcul_s": round(time.time() - t, 3)}


def boucle(repondre):
    """One JSON line in, one answer out, until the input closes."""
    for ligne in sys.stdin:
        ligne = ligne.strip()
        if not ligne:
            continue
        ident = None
        try:
            d = json.loads(ligne)
            ident = d.get("id")
            dit(repondre(d))
        except Exception as e:
            dit({"id": ident, "erreur": str(e)})


def parle(a):
    try:
        import piper  # noqa: F401
    except Exception as e:
        dit({"erreur": "piper-tts absent : " + str(e), "fatal": True})
        sys.exit(2)
    s = Synthese(a["voix"])
    dit({"pret": True, "piper": version_piper(), "voix": voix_presentes(a["voix"])})
    boucle(lambda d: repond_parle(s, d))


# ---------------------------------------------------------------------- the tone colour converter

def reechantillonne(x, de, vers):
    """[x] from [de] Hz to [vers] Hz, by its spectrum (whole sounds of a few seconds: exact enough, no extra library)."""
    import numpy as np
    x = np.asarray(x, dtype=np.float64)
    if de == vers or len(x) == 0:
        return x.astype(np.float32)
    n = int(round(len(x) * vers / de))
    s = np.fft.rfft(x)
    m = n // 2 + 1
    o = np.zeros(m, dtype=complex)
    k = min(m, len(s))
    o[:k] = s[:k]
    return (np.fft.irfft(o, n) * (n / len(x))).astype(np.float32)


def sans_librosa():
    """The converter's module imports librosa for mel filters it does not use here: a stand-in, when it is not installed."""
    try:
        import librosa  # noqa: F401
    except Exception:
        lf = types.ModuleType("librosa.filters")
        lf.mel = None
        lb = types.ModuleType("librosa")
        lb.filters = lf
        sys.modules["librosa"] = lb
        sys.modules["librosa.filters"] = lf


class Convertisseur:
    def __init__(self, dossier):
        sans_librosa()
        import torch
        torch.set_num_threads(max(1, min(4, (os.cpu_count() or 2) // 2)))
        from openvoice.models import SynthesizerTrn
        from openvoice.mel_processing import spectrogram_torch
        from openvoice.utils import get_hparams_from_file
        self.torch = torch
        self.spectrogramme = spectrogram_torch
        self.hps = get_hparams_from_file(os.path.join(dossier, "config.json"))
        h = self.hps
        self.modele = SynthesizerTrn(len(getattr(h, "symbols", [])), h.data.filter_length // 2 + 1,
                                     n_speakers=h.data.n_speakers, **h.model)
        self.modele.eval()
        ck = torch.load(os.path.join(dossier, "checkpoint.pth"), map_location="cpu")
        self.modele.load_state_dict(ck["model"], strict=False)
        self.rate = h.data.sampling_rate

    def spec(self, x):
        h = self.hps
        y = self.torch.FloatTensor(x).unsqueeze(0)
        return self.spectrogramme(y, h.data.filter_length, self.rate, h.data.hop_length, h.data.win_length, center=False)

    def timbre(self, x):
        with self.torch.no_grad():
            return self.modele.ref_enc(self.spec(x).transpose(1, 2)).unsqueeze(-1)

    def convertit(self, ref, ref_rate, src, src_rate, tau):
        import numpy as np
        r = reechantillonne(ref, ref_rate, self.rate)
        s = reechantillonne(src, src_rate, self.rate)
        with self.torch.no_grad():
            sp = self.spec(s)
            y = self.modele.voice_conversion(sp, self.torch.LongTensor([sp.size(-1)]), sid_src=self.timbre(s),
                                             sid_tgt=self.timbre(r), tau=tau)[0][0, 0].data.cpu().float().numpy()
        m = float(np.max(np.abs(y))) if len(y) else 0.0
        return (y / m * 0.9 if m > 1e-6 else y), self.rate


def verifie_clone(a):
    r = {"ok": False, "python": sys.version.split()[0]}
    d = a["clone"] or ""
    r["modele"] = os.path.isfile(os.path.join(d, "checkpoint.pth")) and os.path.isfile(os.path.join(d, "config.json"))
    try:
        sans_librosa()
        import torch
        r["torch"] = torch.__version__
        import openvoice.models  # noqa: F401
        r["ok"] = r["modele"]
        if not r["modele"]:
            r["erreur"] = "modèle du convertisseur absent"
    except Exception as e:
        r["erreur"] = "convertisseur absent : " + str(e)
    dit(r)


def installe_clone(a):
    d = a["clone"]
    os.makedirs(d, exist_ok=True)
    try:
        for f in ("config.json", "checkpoint.pth"):
            if not os.path.isfile(os.path.join(d, f)):
                telecharge_url(CLONE_URL + f, os.path.join(d, f), "convertisseur " + f)
        dit({"fini": True, "mo": round(os.path.getsize(os.path.join(d, "checkpoint.pth")) / 1e6, 1)})
    except Exception as e:
        dit({"erreur": "téléchargement du convertisseur impossible : " + str(e), "fatal": True})
        sys.exit(2)


def clone(a):
    try:
        t = time.time()
        c = Convertisseur(a["clone"])
    except Exception as e:
        dit({"erreur": "convertisseur illisible : " + str(e), "fatal": True})
        sys.exit(2)
    dit({"pret": True, "charge_s": round(time.time() - t, 1), "rate": c.rate})

    def repond(d):
        t = time.time()
        y, rate = c.convertit(de_base64(d["ref"]), int(d.get("ref_rate") or 16000), de_base64(d["src"]),
                              int(d.get("src_rate") or 22050), float(d.get("tau") or 0.3))
        return {"id": d.get("id"), "rate": rate, "pcm": en_base64(y), "calcul_s": round(time.time() - t, 3)}

    boucle(repond)


def main():
    a = arguments(sys.argv)
    isole_sortie()
    {"verifie": verifie, "installe-voix": installe_voix, "verifie-clone": verifie_clone, "installe-clone": installe_clone,
     "convertit": clone}.get(a["mode"], parle)(a)


if __name__ == "__main__":
    main()
