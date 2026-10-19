/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import java.io.ByteArrayOutputStream

/** Capture files for the tests, with made-up credentials and a made-up coding. */
object TraceEssai {
    const val PC = 0xC0A80A02.toInt()      // 192.168.10.2
    const val POSTE = 0xC0A80A63.toInt()   // 192.168.10.99

    /** A made-up coding: nothing to do with the radio's. */
    fun code(t: String): ByteArray {
        val b = ByteArray(Paquets.CHAMP)
        t.toByteArray(Charsets.ISO_8859_1).forEachIndexed { i, c -> b[i] = ((c.toInt() xor (0x5A + 7 * i)) or 0x80).toByte() }
        return b
    }

    fun connexion(u: String, m: String, demande: Int) =
        Paquets.connexion(1, 0x1111, 0x2222, 1, demande, code(u), code(m), "PC-ESSAI")

    fun reponse(demande: Int, erreur: Int) = Paquets.nouveauJeton(Paquets.REPONSE_CONNEXION_LONGUEUR, 1, 0x2222, 0x1111,
        Paquets.Jeton(Paquets.SENS_REPONSE, Paquets.G_CONNEXION, 1, demande, 0x7777, erreur = erreur))

    class Datagramme(val versPoste: Boolean, val donnees: ByteArray, val portPc: Int = 51000)

    private fun be16(o: ByteArrayOutputStream, v: Int) { o.write(v shr 8); o.write(v) }
    private fun be32(o: ByteArrayOutputStream, v: Int) { be16(o, v ushr 16); be16(o, v and 0xFFFF) }
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v); o.write(v shr 8) }
    private fun le32(o: ByteArrayOutputStream, v: Int) { le16(o, v and 0xFFFF); le16(o, v ushr 16) }

    fun ipUdp(d: Datagramme, port: Int = 50001): ByteArray {
        val o = ByteArrayOutputStream()
        val total = 20 + 8 + d.donnees.size
        o.write(0x45); o.write(0); be16(o, total); be16(o, 1); be16(o, 0x4000); o.write(64); o.write(17); be16(o, 0)
        be32(o, if (d.versPoste) PC else POSTE); be32(o, if (d.versPoste) POSTE else PC)
        be16(o, if (d.versPoste) d.portPc else port); be16(o, if (d.versPoste) port else d.portPc)
        be16(o, 8 + d.donnees.size); be16(o, 0)
        o.write(d.donnees)
        return o.toByteArray()
    }

    fun ethernet(ip: ByteArray) = ByteArray(12) { it.toByte() } + byteArrayOf(0x08, 0x00) + ip
    fun sll(ip: ByteArray) = byteArrayOf(0, 0, 0, 1, 0, 6, 1, 2, 3, 4, 5, 6, 0, 0, 0x08, 0x00) + ip

    fun pcap(lien: Int, trames: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        le32(o, 0xA1B2C3D4.toInt()); le16(o, 2); le16(o, 4); le32(o, 0); le32(o, 0); le32(o, 65535); le32(o, lien)
        trames.forEachIndexed { i, t -> le32(o, 1_700_000_000 + i); le32(o, 0); le32(o, t.size); le32(o, t.size); o.write(t) }
        return o.toByteArray()
    }

    /** The block format, big-endian, one Ethernet interface. */
    fun pcapng(trames: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        be32(o, 0x0A0D0D0A); be32(o, 28); be32(o, 0x1A2B3C4D); be16(o, 1); be16(o, 0); be32(o, -1); be32(o, -1); be32(o, 28)
        be32(o, 1); be32(o, 20); be16(o, 1); be16(o, 0); be32(o, 0); be32(o, 20)
        for (t in trames) {
            val pad = (4 - t.size % 4) % 4
            val n = 32 + t.size + pad
            be32(o, 6); be32(o, n); be32(o, 0); be32(o, 0); be32(o, 0); be32(o, t.size); be32(o, t.size)
            o.write(t); repeat(pad) { o.write(0) }; be32(o, n)
        }
        return o.toByteArray()
    }

    fun trace(lien: Int, vararg d: Datagramme): ByteArray = when (lien) {
        1 -> pcap(1, d.map { ethernet(ipUdp(it)) })
        113 -> pcap(113, d.map { sll(ipUdp(it)) })
        else -> pcapng(d.map { ethernet(ipUdp(it)) })
    }
}
