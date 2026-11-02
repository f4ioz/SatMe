#!/usr/bin/env python3
# SatMe — amateur radio satellite tracking
# Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Free software under the GNU GPL, version 2 or later. Without any warranty.
# The full licence text is in the LICENSE file.
"""A Codec2 700C voice message (.c2: 4 bytes a 40 ms frame) made into a WAV
(8 kHz, 16-bit, mono) with the system's libcodec2, through ctypes: nothing
to install but the library itself (Debian/Ubuntu: libcodec2-1.2; Windows:
codec2.dll next to this script or on the PATH). A frame of four zero bytes
(a packet not received) is silence.

    satme_codec2.py message.c2 message.wav

Exit 0 written, 2 no libcodec2, 1 any other error (the reason on stderr)."""
import ctypes
import ctypes.util
import os
import sys
import wave

MODE_700C = 8           # codec2.h: CODEC2_MODE_700C
OCTETS_TRAME = 4


def bibliotheque():
    noms = [ctypes.util.find_library("codec2"), "libcodec2.so.1.2", "libcodec2.so.1.1", "libcodec2.so", "libcodec2.dylib",
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "codec2.dll"), "codec2.dll", "libcodec2.dll"]
    for n in noms:
        if not n:
            continue
        try:
            return ctypes.CDLL(n)
        except OSError:
            pass
    return None


def main():
    if len(sys.argv) != 3:
        print("usage: satme_codec2.py message.c2 message.wav", file=sys.stderr)
        return 1
    lib = bibliotheque()
    if lib is None:
        print("libcodec2 introuvable", file=sys.stderr)
        return 2
    lib.codec2_create.restype = ctypes.c_void_p
    lib.codec2_create.argtypes = [ctypes.c_int]
    lib.codec2_samples_per_frame.argtypes = [ctypes.c_void_p]
    lib.codec2_decode.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p]
    lib.codec2_destroy.argtypes = [ctypes.c_void_p]
    c2 = lib.codec2_create(MODE_700C)
    if not c2:
        print("codec2_create(700C) a échoué", file=sys.stderr)
        return 1
    try:
        n = lib.codec2_samples_per_frame(c2)
        with open(sys.argv[1], "rb") as f:
            donnees = f.read()
        tampon = (ctypes.c_short * n)()
        muet = bytes(2 * n)
        sortie = bytearray()
        for i in range(0, len(donnees) - OCTETS_TRAME + 1, OCTETS_TRAME):
            t = donnees[i:i + OCTETS_TRAME]
            if t == bytes(OCTETS_TRAME):
                sortie += muet
                continue
            lib.codec2_decode(c2, tampon, t)
            sortie += bytes(tampon)
        provisoire = sys.argv[2] + ".tmp"
        with wave.open(provisoire, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(8000)
            w.writeframes(bytes(sortie))
        os.replace(provisoire, sys.argv[2])
    finally:
        lib.codec2_destroy(c2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
