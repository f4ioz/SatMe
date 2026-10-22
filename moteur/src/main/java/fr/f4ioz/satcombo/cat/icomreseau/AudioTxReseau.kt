/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.phonie.Reechantillonneur

/**
 * The transmit audio over the network link, as designed from the receive
 * stream SatMe already decodes and from public descriptions of the
 * protocol: the same 0x18-byte header (length, type 0, the channel's
 * sequence, the ids), a mark at 0x10, the audio count at 0x12 and the
 * samples' length at 0x16 (u16 BE), then 16-bit samples at the receive
 * rate, 20 ms a packet.
 *
 * **Not verified on a radio.** The mark the host puts at 0x10, the packet
 * size and the byte order are the descriptions' and this design's; a
 * capture of the radio's own software transmitting into a dummy load
 * ([AnalyseTraceTx]) says what a real host sends, and what it says is
 * learnt (Réglages › Poste) before anything goes.
 */
object FormatTxReseau {
    /** The mark a host's audio packet carries at 0x10 (public descriptions); a capture corrects it. */
    const val MARQUE_DEFAUT = 0x0080
    const val MS_PAR_PAQUET = 20
    /** The codec byte asked for TX at login: 16-bit mono, as the receive side. */
    const val CODEC_TX = 0x04

    /** Bytes of samples per packet at [taux] (20 ms of 16-bit mono). */
    fun octetsDefaut(taux: Int) = taux / 1000 * MS_PAR_PAQUET * 2
}

/**
 * The page's microphone (48 kHz, any block size) cut into the network's
 * packets: resampled to the link's rate, 16-bit in the radio's byte order,
 * [octets] bytes each. Pure: no socket, testable.
 */
class PaquetiseurTx(val taux: Int, val format: FormatPcm16 = FormatPcm16.PETIT, val octets: Int = FormatTxReseau.octetsDefaut(taux)) {
    private var reech: Reechantillonneur? = null
    private var acc = ByteArray(maxOf(4096, octets * 4))
    private var nAcc = 0

    init { require(octets >= 2 && octets % 2 == 0) }

    /** [n] samples of [pcm] at [tauxEntree]: the packets' payloads now complete. */
    fun pousse(pcm: ShortArray, n: Int, tauxEntree: Int): List<ByteArray> {
        val r = reech?.takeIf { it.entree == tauxEntree } ?: Reechantillonneur(tauxEntree, taux).also { reech = it }
        val y = r.pousse(pcm, n)
        if (nAcc + 2 * y.size > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, nAcc + 2 * y.size))
        for (x in y) {
            val v = Math.round(x.coerceIn(-1f, 1f) * 32767f)
            val w = if (format.nonSigne) v + 32768 else v
            val hi = (w shr 8).toByte(); val lo = w.toByte()
            if (format.grosBout) { acc[nAcc] = hi; acc[nAcc + 1] = lo } else { acc[nAcc] = lo; acc[nAcc + 1] = hi }
            nAcc += 2
        }
        val sortie = ArrayList<ByteArray>()
        var lu = 0
        while (nAcc - lu >= octets) { sortie += acc.copyOfRange(lu, lu + octets); lu += octets }
        if (lu > 0) { System.arraycopy(acc, lu, acc, 0, nAcc - lu); nAcc -= lu }
        return sortie
    }

    /** What waits for the next packet dropped (a new over starts clean). */
    fun vide() { nAcc = 0; reech = null }
}

/**
 * A capture of the radio's own software transmitting (into a dummy load)
 * read for what SatMe cannot know without the radio: what the host asks
 * for its TX stream at login, and what its audio packets look like — the
 * mark at 0x10, their size, whether the length field and the audio count
 * hold as the receive side's do, the samples' byte order. Read from
 * memory, nothing kept but the figures.
 */
object AnalyseTraceTx {

    /** The TX part of the host's stream request (the 0x90 packet on the control port). */
    data class DemandeTx(val actif: Boolean, val codec: Int, val taux: Int, val tamponMs: Int)

    data class Bilan(
        val ok: Boolean,
        val message: String,
        /** Host → radio audio packets found. */
        val paquets: Int = 0,
        /** Each mark seen at 0x10 and how often. */
        val marques: Map<Int, Int> = emptyMap(),
        /** Each payload size seen and how often. */
        val tailles: Map<Int, Int> = emptyMap(),
        /** The length field at 0x16 always equals the payload's size. */
        val longueurCoherente: Boolean = false,
        /** The audio count at 0x12 goes up by one from packet to packet (most of the time). */
        val compteSuit: Boolean = false,
        val demande: DemandeTx? = null,
        /** The samples' layout found from the sound itself (a voice or a tone sent), null: silence only. */
        val format: FormatPcm16? = null,
        /** The dominant mark and size, to be learnt; null when they are not clear. */
        val marque: Int? = null,
        val octets: Int? = null,
    ) {
        /** What SatMe would send by default agrees with the capture. */
        fun conforme(taux: Int): Boolean = marque == FormatTxReseau.MARQUE_DEFAUT && octets == FormatTxReseau.octetsDefaut(taux) && longueurCoherente
    }

    fun analyse(trace: ByteArray, portControle: Int, portAudio: Int): Bilan {
        val l = TracePcap.udp(trace) ?: return Bilan(false, "ce n'est pas une capture lisible (pcap ou pcapng)")
        // The radio: whoever answers from the control port.
        val radio = l.firstOrNull { it.portSource == portControle }?.source
            ?: return Bilan(false, "aucun paquet du poste sur le port de contrôle $portControle")
        var demande: DemandeTx? = null
        for (u in l) {
            if (u.destination != radio || u.portDestination != portControle || u.donnees.size != Paquets.INFO_LONGUEUR) continue
            val j = Paquets.jeton(u.donnees) ?: continue
            if (j.genre != Paquets.G_INFO || j.sens != Paquets.SENS_DEMANDE) continue
            val b = u.donnees
            demande = DemandeTx(b[Paquets.I_TX_ACTIF].toInt() != 0, b[Paquets.I_TX_CODEC].toInt() and 0xFF,
                Paquets.u32be(b, Paquets.I_TX_TAUX), Paquets.u32be(b, Paquets.I_TAMPON_TX))
        }
        // The audio port: the one asked, else the one the radio's status gave.
        val ports = setOf(portAudio) + l.filter { it.source == radio && it.portSource == portControle && it.donnees.size == Paquets.STATUT_LONGUEUR }
            .mapNotNull { Paquets.statut(it.donnees)?.portAudio?.takeIf { p -> p > 0 } }
        val marques = HashMap<Int, Int>(); val tailles = HashMap<Int, Int>()
        var coherent = true; var suit = 0; var sauts = 0; var precedent = -1
        val detecteur = DetecteurFormatPcm16(4_000)
        var n = 0
        for (u in l) {
            if (u.destination != radio || u.portDestination !in ports) continue
            val b = u.donnees
            val e = Paquets.entete(b) ?: continue
            if (e.type != Paquets.T_DONNEES || b.size <= Paquets.AUDIO_ENTETE) continue
            n++
            val marque = Paquets.u16be(b, 0x10)
            marques[marque] = (marques[marque] ?: 0) + 1
            val corps = b.size - Paquets.AUDIO_ENTETE
            tailles[corps] = (tailles[corps] ?: 0) + 1
            if (Paquets.u16be(b, Paquets.A_LONGUEUR) != corps) coherent = false
            val c = Paquets.u16be(b, Paquets.A_SEQ)
            if (precedent >= 0) { if (((c - precedent) and 0xFFFF) == 1) suit++ else sauts++ }
            precedent = c
            detecteur.decode(b.copyOfRange(Paquets.AUDIO_ENTETE, b.size))
        }
        if (n == 0) return Bilan(false, "aucun son envoyé au poste dans la capture (émettre quelques secondes pendant la capture)", demande = demande)
        fun dominant(m: Map<Int, Int>) = m.maxByOrNull { it.value }?.takeIf { it.value * 10 >= n * 9 }?.key
        val marque = dominant(marques)
        val octets = dominant(tailles)
        return Bilan(true, "$n paquets de son envoyés au poste", n, marques, tailles, coherent, suit > 0 && suit >= 9 * sauts,
            demande, if (detecteur.sur) detecteur.format else null, marque, octets)
    }
}
