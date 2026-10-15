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

Other uses:
  --verifie            what is installed (imports, CUDA, models present), then exit
  --telecharge         download the model into --modeles, with progress lines, then exit

Never downloads anything unless --telecharge is given.
"""
import glob
import json
import os
import struct
import sys
import threading
import time


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
    a = {"modele": "large-v3", "modeles": None, "calcul": "auto", "verifie": False, "telecharge": False}
    i = 1
    while i < len(sys.argv):
        x = sys.argv[i]
        if x in ("--modele", "--modeles", "--calcul") and i + 1 < len(sys.argv):
            a[x[2:]] = sys.argv[i + 1]
            i += 1
        elif x == "--verifie":
            a["verifie"] = True
        elif x == "--telecharge":
            a["telecharge"] = True
        i += 1
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
    r["ok"] = True
    dit(r)


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
    dit({"pret": True, "modele": a["modele"], "calcul": calcul, "type": type_, "charge_s": round(duree, 1), "repli": repli})
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
            traduction = ""
            if entete.get("traduire") and morceaux and info.language != "en":
                tr, _ = modele.transcribe(audio, language=info.language, task="translate", beam_size=5,
                                          vad_filter=True, condition_on_previous_text=False)
                traduction = " ".join(s.text.strip() for s in tr).strip()
            dit({"id": ident, "langue": info.language, "proba": round(info.language_probability, 3),
                 "calcul_s": round(time.time() - t, 2), "segments": morceaux, "traduction": traduction})
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
    else:
        boucle(a)


if __name__ == "__main__":
    main()
