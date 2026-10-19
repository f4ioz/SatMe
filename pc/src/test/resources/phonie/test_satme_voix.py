# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Free software under the GNU GPL, version 2 or later. Without any warranty.
# The full licence text is in the LICENSE file.
"""satme_voix.py's own parts (no voice model, no network): run by VoixPythonTest when the environment exists."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.environ.get("SATME_VOIX_DIR", os.path.dirname(os.path.abspath(__file__))))
import satme_voix as v  # noqa: E402
import numpy as np  # noqa: E402


class Chemins(unittest.TestCase):
    def test_chemin(self):
        self.assertEqual(v.chemin_voix("fr_FR-siwis-medium"), "fr/fr_FR/siwis/medium/fr_FR-siwis-medium")
        self.assertEqual(v.chemin_voix("de_DE-eva_k-x_low"), "de/de_DE/eva_k/x_low/de_DE-eva_k-x_low")
        self.assertTrue(v.url_voix("pt_PT-tugão-medium", ".onnx").endswith("pt/pt_PT/tug%C3%A3o/medium/pt_PT-tug%C3%A3o-medium.onnx"))
        with self.assertRaises(ValueError):
            v.chemin_voix("../../etc/passwd")

    def test_presentes(self):
        with tempfile.TemporaryDirectory() as d:
            for f in ("a_AA-x-low.onnx", "a_AA-x-low.onnx.json", "b_BB-y-low.onnx"):
                open(os.path.join(d, f), "w").close()
            self.assertEqual(v.voix_presentes(d), ["a_AA-x-low"])
        self.assertEqual(v.voix_presentes(None), [])

    def test_arguments(self):
        a = v.arguments(["x", "--convertit", "--clone", "/c"])
        self.assertEqual((a["mode"], a["clone"]), ("convertit", "/c"))
        self.assertEqual(v.arguments(["x", "--voix", "/v"])["mode"], "parle")


class Son(unittest.TestCase):
    def test_base64(self):
        x = np.array([0.0, 0.5, -0.5, 0.999], dtype=np.float32)
        y = v.de_base64(v.en_base64(x))
        self.assertTrue(np.allclose(x, y, atol=1e-4))

    def test_reechantillonne(self):
        # 300 Hz for 1 s at 16 kHz → 22.05 kHz: same length in time, same tone.
        t = np.arange(16000) / 16000.0
        x = np.sin(2 * np.pi * 300 * t)
        y = v.reechantillonne(x, 16000, 22050)
        self.assertEqual(len(y), 22050)
        f = np.fft.rfftfreq(len(y), 1 / 22050.0)[np.argmax(np.abs(np.fft.rfft(y)))]
        self.assertAlmostEqual(f, 300.0, delta=2.0)
        self.assertAlmostEqual(float(np.std(y)), float(np.std(x)), delta=0.02)


class Demandes(unittest.TestCase):
    def test_reponse(self):
        class Faux:
            def dit(self, nom, locuteur, texte, longueur):
                self.vu = (nom, locuteur, texte, longueur)
                return np.full(100, 0.25, dtype=np.float32), 16000
        f = Faux()
        r = v.repond_parle(f, {"id": 3, "voix": "fr_FR-gilles-low", "phrases": ["Bonjour.", ""], "longueur": 9})
        self.assertEqual(r["id"], 3)
        self.assertEqual(r["rate"], 16000)
        self.assertEqual(len(r["phrases"]), 2)
        self.assertEqual(r["phrases"][1], "")
        self.assertEqual(len(v.de_base64(r["phrases"][0])), 100)
        # The length scale bounded.
        self.assertEqual(f.vu[3], 2.0)


if __name__ == "__main__":
    unittest.main()
