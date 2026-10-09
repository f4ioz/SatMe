/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

/**
 * CCSDS packets out of the frames (VCDU). A frame carries 882 bytes of a
 * packet stream; a packet may start in one frame and end in the next. The
 * frame header says where the first packet starting in it begins (0x7FF:
 * none). A missing frame (the counter jumps) loses the packet that was being
 * put together, never more.
 *
 * VCDU: 6 bytes of header (virtual channel, counter), 2 of insert zone, 2 of
 * M_PDU header (first header pointer), 882 of data.
 */
class Paquets(private val surPaquet: (apid: Int, sequence: Int, donnees: ByteArray) -> Unit) {

    private class Canal {
        var compteur = -1
        val enCours = java.io.ByteArrayOutputStream()
        var attendu = -1           // total size of the packet being assembled, -1 unknown
        var actif = false
    }

    private val canaux = HashMap<Int, Canal>()
    var paquets = 0; private set
    var trous = 0; private set

    fun trame(v: ByteArray) {
        if (v.size < 892) return
        val vcid = v[1].toInt() and 0x3F
        if (vcid == 63) return   // fill
        val compteur = ((v[2].toInt() and 0xFF) shl 16) or ((v[3].toInt() and 0xFF) shl 8) or (v[4].toInt() and 0xFF)
        val c = canaux.getOrPut(vcid) { Canal() }
        if (c.compteur >= 0 && compteur != ((c.compteur + 1) and 0xFFFFFF)) {
            trous++
            c.actif = false; c.enCours.reset(); c.attendu = -1
        }
        c.compteur = compteur
        val fhp = ((v[8].toInt() and 0x07) shl 8) or (v[9].toInt() and 0xFF)
        val debut = 10
        val fin = 892
        if (fhp == 0x7FF) {
            if (c.actif) ajoute(c, v, debut, fin)
            return
        }
        // The end of the packet under way, then the packets starting here.
        if (c.actif) ajoute(c, v, debut, minOf(fin, debut + fhp))
        c.actif = true; c.enCours.reset(); c.attendu = -1
        ajoute(c, v, debut + fhp, fin)
    }

    private fun ajoute(c: Canal, v: ByteArray, de: Int, a: Int) {
        var i = de
        while (i < a) {
            if (c.attendu < 0) {
                // Header first: 6 bytes, possibly over two frames.
                val manque = 6 - c.enCours.size()
                val k = minOf(manque, a - i)
                c.enCours.write(v, i, k); i += k
                if (c.enCours.size() < 6) return
                val h = c.enCours.toByteArray()
                c.attendu = 6 + (((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)) + 1
            }
            val k = minOf(c.attendu - c.enCours.size(), a - i)
            c.enCours.write(v, i, k); i += k
            if (c.enCours.size() == c.attendu) {
                val p = c.enCours.toByteArray()
                c.enCours.reset(); c.attendu = -1
                val apid = ((p[0].toInt() and 0x07) shl 8) or (p[1].toInt() and 0xFF)
                val seq = ((p[2].toInt() and 0x3F) shl 8) or (p[3].toInt() and 0xFF)
                if (apid != 2047) {
                    paquets++
                    surPaquet(apid, seq, p.copyOfRange(6, p.size))
                }
                // Padding to the end of the zone? A header that cannot be is the end.
            }
        }
    }
}
