# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Free software under the GNU GPL, version 2 or later. Without any warranty.
# The full licence text is in the LICENSE file.
"""
SatMe-PC's speech-to-text helper (« Phonie → texte »), on faster-whisper.

Started by SatMe-PC as a long-lived child process, only when the user turns
the feature on. Reads frames on stdin, writes one JSON object per line on
stdout (see fr.f4ioz.satcombo.phonie.ProtocolePhonie):

  in : [u32 BE header length][header JSON][u32 BE PCM length][PCM s16le 16 kHz mono]
  out: {"pret":true,...} once the model is loaded, then {"id":..,"segments":[..]} per frame.

Translation: the header's "cible" (a language code) translates the text
heard into it, offline, with Argos Translate's language packages (OPUS-MT
models run by CTranslate2, read with sentencepiece), through English when
no direct pair exists. An empty "cible" (or "whisper") keeps Whisper's own
« translate » task (English only, large-v3).

Other uses:
  --verifie            what is installed (imports, CUDA, models, language pairs present), then exit
  --telecharge         download the model into --modeles, with progress lines, then exit
  --installe-traduction --paires pl_en,en_fr
                       download those language pairs (Argos Translate's index) into --traduction, then exit

Never downloads anything unless --telecharge or --installe-traduction is given.
"""
import glob
import json
import os
import re
import shutil
import struct
import sys
import threading
import time
import zipfile


def bibliotheques_nvidia():
    """The NVIDIA libraries installed by pip (nvidia-cublas-cu12, nvidia-cudnn-cu12): their folders."""
    import site
    import sysconfig
    racines = set()
    for cle in ("purelib", "platlib"):
        p = sysconfig.get_paths().get(cle)
        if p:
            racines.add(p)
    try:
        racines.update(site.getsitepackages())
    except Exception:
        pass
    dossiers = []
    for r in racines:
        for sous in ("lib", "bin"):
            dossiers += glob.glob(os.path.join(r, "nvidia", "*", sous))
    return sorted(set(d for d in dossiers if os.path.isdir(d)))


def prepare_nvidia():
    """Makes pip's NVIDIA libraries findable: DLL folders on Windows, LD_LIBRARY_PATH (re-exec) on Linux."""
    dossiers = bibliotheques_nvidia()
    if not dossiers:
        return
    if os.name == "nt":
        for d in dossiers:
            try:
                os.add_dll_directory(d)
            except Exception:
                pass
        os.environ["PATH"] = os.pathsep.join(dossiers + [os.environ.get("PATH", "")])
        return
    if sys.platform == "darwin":
        return
    actuel = os.environ.get("LD_LIBRARY_PATH", "")
    if all(d in actuel.split(":") for d in dossiers) or os.environ.get("SATME_PHONIE_REEXEC") == "1":
        return
    # The loader reads LD_LIBRARY_PATH only at start: the same program again, with it set.
    os.environ["LD_LIBRARY_PATH"] = ":".join(dossiers + ([actuel] if actuel else []))
    os.environ["SATME_PHONIE_REEXEC"] = "1"
    sys.stdout.flush()
    os.execv(sys.executable, [sys.executable] + sys.argv)


def arguments():
    a = {"modele": "large-v3", "modeles": None, "calcul": "auto", "verifie": False, "telecharge": False,
         "traduction": None, "paires": "", "installe-traduction": False}
    i = 1
    while i < len(sys.argv):
        x = sys.argv[i]
        if x in ("--modele", "--modeles", "--calcul", "--traduction", "--paires") and i + 1 < len(sys.argv):
            a[x[2:]] = sys.argv[i + 1]
            i += 1
        elif x == "--verifie":
            a["verifie"] = True
        elif x == "--telecharge":
            a["telecharge"] = True
        elif x == "--installe-traduction":
            a["installe-traduction"] = True
        i += 1
    if not a["traduction"]:
        # Beside the models: stt/traduction.
        a["traduction"] = os.path.join(os.path.dirname(os.path.abspath(a["modeles"] or "modeles")), "traduction")
    return a


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


MODELES = ("large-v3", "turbo", "medium", "small")


def present(nom, modeles):
    from faster_whisper.utils import download_model
    try:
        download_model(nom, local_files_only=True, cache_dir=modeles)
        return True
    except Exception:
        return False


def verifie(a):
    r = {"ok": False, "python": sys.version.split()[0]}
    try:
        import faster_whisper
        r["faster_whisper"] = getattr(faster_whisper, "__version__", "?")
    except Exception as e:
        r["erreur"] = "faster-whisper absent : " + str(e)
        dit(r)
        return
    try:
        import ctranslate2
        r["cuda"] = ctranslate2.get_cuda_device_count()
    except Exception as e:
        r["cuda"] = 0
        r["cuda_erreur"] = str(e)
    r["nvidia"] = len(bibliotheques_nvidia()) > 0
    r["modeles"] = [m for m in MODELES if present(m, a["modeles"])]
    r["traduction"] = verifie_traduction(a)
    r["ok"] = True
    dit(r)


# ---------------------------------------------------------------------- translation (Argos Translate's packages)

INDEX_ARGOS = "https://raw.githubusercontent.com/argosopentech/argospm-index/main/index.json"
# Whisper's codes that Argos names otherwise.
CODES_ARGOS = {"no": "nb", "jw": "jv"}


def paires_installees(dossier):
    """The pairs unpacked in [dossier]: {"pl_en": path}."""
    r = {}
    if not dossier or not os.path.isdir(dossier):
        return r
    for n in sorted(os.listdir(dossier)):
        d = os.path.join(dossier, n)
        if re.fullmatch(r"[a-z]{2,3}_[a-z]{2,3}", n) and os.path.isfile(os.path.join(d, "model", "model.bin")) \
                and os.path.isfile(os.path.join(d, "sentencepiece.model")):
            r[n] = d
    return r


def verifie_traduction(a):
    r = {"paires": sorted(paires_installees(a["traduction"])), "dossier": a["traduction"]}
    try:
        import sentencepiece
        r["sentencepiece"] = getattr(sentencepiece, "__version__", "?")
    except Exception:
        r["sentencepiece"] = ""
    return r


def chemin_traduction(de, vers, paires):
    """The pairs to chain from [de] to [vers]: direct, else through English; None when one is missing."""
    de = CODES_ARGOS.get(de, de)
    if de == vers:
        return []
    if de + "_" + vers in paires:
        return [de + "_" + vers]
    if de != "en" and vers != "en" and de + "_en" in paires and "en_" + vers in paires:
        return [de + "_en", "en_" + vers]
    return None


class Traducteur:
    """Argos Translate's packages, read directly: sentencepiece in, CTranslate2, sentencepiece out."""

    def __init__(self, dossier, calcul):
        self.dossier = dossier
        self.calcul = calcul
        self.charges = {}

    def paire(self, nom):
        p = self.charges.get(nom)
        if p is not None:
            return p
        import ctranslate2
        import sentencepiece
        d = os.path.join(self.dossier, nom)
        meta = {}
        try:
            with open(os.path.join(d, "metadata.json"), encoding="utf-8") as f:
                meta = json.load(f)
        except Exception:
            pass
        sp = sentencepiece.SentencePieceProcessor(model_file=os.path.join(d, "sentencepiece.model"))
        essais = ([("cuda", "int8_float16")] if self.calcul == "cuda" else []) + [("cpu", "int8")]
        tr, raison = None, ""
        for calcul, type_ in essais:
            try:
                tr = ctranslate2.Translator(os.path.join(d, "model"), device=calcul, compute_type=type_)
                break
            except Exception as e:
                raison = str(e)
        if tr is None:
            raise RuntimeError("paire " + nom + " illisible : " + raison)
        p = (sp, tr, meta.get("target_prefix", ""))
        self.charges[nom] = p
        return p

    def une_etape(self, nom, phrases):
        sp, tr, prefixe = self.paire(nom)
        jetons = [sp.encode(x, out_type=str) for x in phrases]
        r = tr.translate_batch(jetons, target_prefix=[[prefixe]] * len(jetons) if prefixe else None,
                               replace_unknowns=True, beam_size=4, num_hypotheses=1, length_penalty=0.2)
        sortie = []
        for x in r:
            t = sp.decode_pieces(x.hypotheses[0]).replace("▁", " ")
            if prefixe and t.startswith(prefixe):
                t = t[len(prefixe):]
            sortie.append(t.strip())
        return sortie

    def traduit(self, textes, de, vers):
        """[textes] (one per segment) from [de] to [vers]: one translation each; LookupError when a pair is missing."""
        chemin = chemin_traduction(de, vers, paires_installees(self.dossier))
        if chemin is None:
            raise LookupError(de + " → " + vers)
        # Each segment cut into sentences: the models learnt on sentences.
        phrases, index = [], []
        for i, t in enumerate(textes):
            for ph in re.split(r"(?<=[.!?…])\s+", t.strip()):
                if ph.strip():
                    phrases.append(ph.strip())
                    index.append(i)
        for nom in chemin:
            if phrases:
                phrases = self.une_etape(nom, phrases)
        sortie = [[] for _ in textes]
        for i, m in zip(index, phrases):
            sortie[i].append(m)
        return [" ".join(x).strip() for x in sortie]


def ouvre(url):
    import urllib.request
    # Python's default agent is refused by the packages' host.
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SatMe-PC (satme_phonie.py)"}), timeout=60)


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


def deballe(archive, cible):
    """An .argosmodel (a zip, one top folder) unpacked into [cible], without stanza's files (not used here)."""
    tmp = cible + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            parties = info.filename.split("/", 1)
            if len(parties) < 2 or not parties[1] or parties[1].startswith("stanza/") or ".." in parties[1].split("/"):
                continue
            dest = os.path.join(tmp, parties[1])
            if info.is_dir():
                os.makedirs(dest, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(info) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
    if not os.path.isfile(os.path.join(tmp, "sentencepiece.model")) or not os.path.isfile(os.path.join(tmp, "model", "model.bin")):
        shutil.rmtree(tmp, ignore_errors=True)
        raise RuntimeError("format non pris en charge (pas de sentencepiece.model)")
    shutil.rmtree(cible, ignore_errors=True)
    os.replace(tmp, cible)


def installe_traduction(a):
    """Downloads the pairs asked (--paires pl_en,en_fr) listed by Argos Translate's index, unpacked into --traduction."""
    dossier = a["traduction"]
    os.makedirs(dossier, exist_ok=True)
    voulues = [p for p in a["paires"].split(",") if re.fullmatch(r"[a-z]{2,3}_[a-z]{2,3}", p)]
    try:
        with ouvre(INDEX_ARGOS) as r:
            index = json.loads(r.read().decode("utf-8"))
        with open(os.path.join(dossier, "index.json"), "w", encoding="utf-8") as f:
            json.dump(index, f)
    except Exception as e:
        dit({"erreur": "index des paires de langues illisible : " + str(e), "fatal": True})
        sys.exit(2)
    offertes = {p["from_code"] + "_" + p["to_code"]: p for p in index if p.get("links")}
    deja = paires_installees(dossier)
    absentes = [p for p in voulues if p not in offertes]
    for nom in voulues:
        if nom in deja or nom not in offertes:
            continue
        archive = os.path.join(dossier, nom + ".argosmodel")
        try:
            liens, raison = offertes[nom]["links"], ""
            for i, lien in enumerate(liens):
                try:
                    telecharge_url(lien, archive, nom.replace("_", " → "))
                    break
                except Exception as e:
                    raison = str(e)
                    if i == len(liens) - 1:
                        raise RuntimeError("téléchargement impossible : " + raison)
            deballe(archive, os.path.join(dossier, nom))
            dit({"paire": nom, "mo": round(taille(os.path.join(dossier, nom)) / 1e6, 1)})
        except Exception as e:
            dit({"erreur": "paire " + nom + " : " + str(e), "fatal": True})
            sys.exit(2)
        finally:
            for f in (archive, archive + ".part"):
                try:
                    os.remove(f)
                except OSError:
                    pass
    dit({"fini": True, "paires": sorted(paires_installees(dossier)), "absentes": absentes,
         "mo": round(taille(dossier) / 1e6, 1)})


def taille(d):
    t = 0
    for racine, _, fichiers in os.walk(d):
        for f in fichiers:
            try:
                t += os.path.getsize(os.path.join(racine, f))
            except OSError:
                pass
    return t


def telecharge(a):
    from faster_whisper.utils import download_model
    os.makedirs(a["modeles"] or ".", exist_ok=True)
    fini = threading.Event()

    def progres():
        while not fini.wait(2.0):
            dit({"progres": "modèle " + a["modele"], "mo": round(taille(a["modeles"] or ".") / 1e6, 1)})

    threading.Thread(target=progres, daemon=True).start()
    try:
        download_model(a["modele"], cache_dir=a["modeles"])
        fini.set()
        dit({"fini": True, "modele": a["modele"], "mo": round(taille(a["modeles"] or ".") / 1e6, 1)})
    except Exception as e:
        fini.set()
        dit({"erreur": "téléchargement du modèle impossible : " + str(e), "fatal": True})
        sys.exit(2)


def charge(a):
    from faster_whisper import WhisperModel
    import numpy as np
    repli = ""
    essais = []
    if a["calcul"] in ("auto", "cuda"):
        try:
            import ctranslate2
            if ctranslate2.get_cuda_device_count() > 0:
                essais.append(("cuda", "float16"))
            elif a["calcul"] == "cuda":
                repli = "aucune carte NVIDIA vue par CUDA"
        except Exception as e:
            repli = str(e)
    if a["calcul"] != "cuda" or not essais:
        essais.append(("cpu", "int8"))
    t = time.time()
    for calcul, type_ in essais:
        try:
            m = WhisperModel(a["modele"], device=calcul, compute_type=type_, download_root=a["modeles"], local_files_only=True)
            # One second of silence: a missing CUDA library shows now, not on the first over.
            list(m.transcribe(np.zeros(16000, dtype=np.float32), beam_size=1, vad_filter=False)[0])
            return m, calcul, type_, time.time() - t, repli
        except Exception as e:
            texte = str(e)
            if "local_files_only" in texte or "snapshot" in texte.lower() or "Cannot find" in texte or "not found" in texte.lower():
                raise RuntimeError("modèle « " + a["modele"] + " » absent : Réglages › Phonie → texte › Installer le moteur")
            repli = calcul + " : " + texte
    raise RuntimeError("chargement impossible (" + repli + ")")


def lit_exact(f, n):
    b = bytearray()
    while len(b) < n:
        c = f.read(n - len(b))
        if not c:
            return None
        b += c
    return bytes(b)


def boucle(a):
    import numpy as np
    try:
        modele, calcul, type_, duree, repli = charge(a)
    except Exception as e:
        dit({"erreur": str(e), "fatal": True})
        sys.exit(2)
    traducteur = Traducteur(a["traduction"], calcul)
    dit({"pret": True, "modele": a["modele"], "calcul": calcul, "type": type_, "charge_s": round(duree, 1), "repli": repli,
         "paires": sorted(paires_installees(a["traduction"]))})
    entree = sys.stdin.buffer
    while True:
        b = lit_exact(entree, 4)
        if b is None:
            return
        (n,) = struct.unpack(">I", b)
        entete = json.loads(lit_exact(entree, n).decode("utf-8"))
        (p,) = struct.unpack(">I", lit_exact(entree, 4))
        brut = lit_exact(entree, p)
        if brut is None:
            return
        ident = entete.get("id")
        try:
            t = time.time()
            audio = np.frombuffer(brut, dtype="<i2").astype(np.float32) / 32768.0
            langue = entete.get("langue") or None
            segs, info = modele.transcribe(audio, language=langue, task="transcribe", beam_size=5,
                                           vad_filter=True, vad_parameters={"min_silence_duration_ms": 500},
                                           condition_on_previous_text=False)
            morceaux = [{"debut": round(s.start, 2), "fin": round(s.end, 2), "texte": s.text.strip(),
                         "avg_logprob": round(s.avg_logprob, 3), "no_speech_prob": round(s.no_speech_prob, 3),
                         "compression": round(s.compression_ratio, 2)} for s in segs]
            traduction, cible, erreur_tr, duree_tr = "", "", "", 0.0
            demandee = str(entete.get("cible") or "").strip().lower()
            if entete.get("traduire") and morceaux:
                t_tr = time.time()
                whisper = demandee in ("", "whisper")
                if not whisper and CODES_ARGOS.get(info.language, info.language) != demandee:
                    try:
                        par = traducteur.traduit([m["texte"] for m in morceaux], info.language, demandee)
                        for m, x in zip(morceaux, par):
                            m["traduction"] = x
                        traduction = " ".join(x for x in par if x).strip()
                        cible = demandee
                    except LookupError:
                        erreur_tr = "traduction " + info.language + " → " + demandee + " non installée (Réglages › Phonie → texte › Installer la traduction)"
                        # English asked: Whisper's own translation meanwhile.
                        whisper = demandee == "en"
                    except Exception as e:
                        erreur_tr = "traduction impossible : " + str(e)
                if whisper and info.language != "en":
                    tr, _ = modele.transcribe(audio, language=info.language, task="translate", beam_size=5,
                                              vad_filter=True, condition_on_previous_text=False)
                    traduction = " ".join(s.text.strip() for s in tr).strip()
                    cible = "en"
                    erreur_tr = ""
                duree_tr = time.time() - t_tr
            r = {"id": ident, "langue": info.language, "proba": round(info.language_probability, 3),
                 "calcul_s": round(time.time() - t, 2), "segments": morceaux, "traduction": traduction}
            if demandee:
                r["langue_cible"] = cible
                r["traduction_s"] = round(duree_tr, 2)
                if erreur_tr:
                    r["traduction_erreur"] = erreur_tr
            dit(r)
        except Exception as e:
            dit({"id": ident, "erreur": str(e)})


def main():
    a = arguments()
    prepare_nvidia()
    isole_sortie()
    if a["verifie"]:
        verifie(a)
    elif a["telecharge"]:
        telecharge(a)
    elif a["installe-traduction"]:
        installe_traduction(a)
    else:
        boucle(a)


if __name__ == "__main__":
    main()
