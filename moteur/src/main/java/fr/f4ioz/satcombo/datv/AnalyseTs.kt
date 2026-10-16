/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/**
 * What a received MPEG transport stream says of its own health, read as it
 * flows (blocks of any size, cut anywhere): packets counted, sync losses,
 * transport errors (TEI), continuity-counter errors per PID (whole packets
 * missing), the tables seen (PAT, PMT, SDT) and the elementary streams.
 *
 * The PCR gives the stream's own clock: between two PCRs, the packets the
 * sender put out are the time elapsed × its rate; fewer received means
 * packets lost before us, whatever the wall clock or the network did.
 */
class AnalyseTs {
    /** One PID: packets, continuity errors, packets missing (a lower bound: modulo 16), duplicates. */
    data class Pid(val pid: Int, val paquets: Long, val erreursCc: Long, val manquants: Long, val doublons: Long, val pcr: Boolean)

    /** A PCR interval with packets missing: its length (s) and the packets received in it. */
    data class Intervalle(val secondes: Double, val paquets: Long)

    /** Everything counted since the start (cumulative). */
    data class Bilan(
        val octets: Long = 0,
        val paquets: Long = 0,
        val nuls: Long = 0,
        val tei: Long = 0,
        val pertesSync: Long = 0,
        val erreursCc: Long = 0,
        val manquants: Long = 0,
        val doublons: Long = 0,
        val pids: List<Pid> = emptyList(),
        /** Stream types by PID, from the PMT. */
        val types: Map<Int, Int> = emptyMap(),
        val pmt: Set<Int> = emptySet(),
        val pat: Boolean = false,
        val sdt: Boolean = false,
        val pcrPid: Int = -1,
        /** PCR time covered and the packets received in it. */
        val pcrSecondes: Double = 0.0,
        val pcrPaquets: Long = 0,
        /** The last intervals without a loss seen: the sender's rate. */
        val propres: List<Intervalle> = emptyList(),
        /** PCR jumps (a discontinuity, a gap over 2 s). */
        val pcrSauts: Long = 0,
        /** The last intervals with packets missing. */
        val trous: List<Intervalle> = emptyList(),
    ) {
        /** The sender's rate (bit/s, 188 bytes each) from the PCR over clean intervals, or null. */
        val debitPcr: Double? get() {
            // A loss is not always seen in its own interval (the PID that shows it comes later): those
            // look clean but slow. The sender's rate is the fastest intervals' (losses only ever slow).
            val l = propres.filter { it.secondes > 0.005 }
            if (l.sumOf { it.secondes } < 0.2) return null
            val max = l.maxOf { it.paquets / it.secondes }
            val bons = l.filter { it.paquets / it.secondes >= 0.95 * max }
            return bons.sumOf { it.paquets } * 1504.0 / bons.sumOf { it.secondes }
        }

        /** Packets received over packets sent, from the PCR, at [debit] bit/s (the sender's rate). */
        fun ratioPcr(debit: Double?): Double? =
            if (debit == null || debit <= 0 || pcrSecondes < 0.2) null else pcrPaquets / (pcrSecondes * debit / 1504.0)

        /** Packets missing in each lossy PCR interval, at [debit] bit/s. */
        fun manquesParTrou(debit: Double): List<Double> = trous.map { it.secondes * debit / 1504.0 - it.paquets }.filter { it >= 1.0 }
    }

    private val reste = ByteArray(188)
    private var nReste = 0
    private var cale = false

    private var octets = 0L
    private var paquets = 0L
    private var nuls = 0L
    private var tei = 0L
    private var pertesSync = 0L
    private var erreursCc = 0L
    private var manquants = 0L
    private var doublons = 0L

    private class P(val pid: Int) { var paquets = 0L; var cc = -1; var erreurs = 0L; var manquants = 0L; var doublons = 0L; var pcr = false }
    private val pids = HashMap<Int, P>()
    private val types = HashMap<Int, Int>()
    private val pmt = HashSet<Int>()
    private var pat = false
    private var sdt = false
    private var pcrPid = -1

    private var dernierPcr = -1L
    private var paquetDernierPcr = 0L
    private var erreursDernierPcr = 0L
    private var pcrSecondes = 0.0
    private var pcrPaquets = 0L
    private val propres = ArrayDeque<Intervalle>()
    private var pcrSauts = 0L
    private val trous = ArrayDeque<Intervalle>()

    /** A block of the stream, cut anywhere. */
    @Synchronized
    fun ajoute(b: ByteArray, n: Int = b.size) {
        octets += n
        val a: ByteArray
        val len: Int
        if (nReste > 0) { a = ByteArray(nReste + n); System.arraycopy(reste, 0, a, 0, nReste); System.arraycopy(b, 0, a, nReste, n); len = nReste + n }
        else { a = b; len = n }
        var i = 0
        while (len - i >= 188) {
            if (a[i].toInt() == 0x47 && (cale || len - i < 376 || a[i + 188].toInt() == 0x47)) {
                cale = true
                paquet(a, i)
                i += 188
            } else {
                if (cale) { pertesSync++; cale = false }
                i++
            }
        }
        nReste = len - i
        System.arraycopy(a, i, reste, 0, nReste)
    }

    private fun paquet(a: ByteArray, o: Int) {
        paquets++
        val b1 = a[o + 1].toInt() and 0xFF
        val pid = (b1 and 0x1F) shl 8 or (a[o + 2].toInt() and 0xFF)
        if (b1 and 0x80 != 0) { tei++; return }   // its header may be wrong too: not looked into
        if (pid == 0x1FFF) { nuls++; return }
        val b3 = a[o + 3].toInt() and 0xFF
        val afc = (b3 shr 4) and 3
        val cc = b3 and 0x0F
        val p = pids.getOrPut(pid) { P(pid) }
        p.paquets++
        var debut = o + 4
        var discontinuite = false
        var avecPcr = false
        if (afc and 2 != 0) {
            val l = a[o + 4].toInt() and 0xFF
            if (l > 0 && l <= 183) {
                val f = a[o + 5].toInt() and 0xFF
                discontinuite = f and 0x80 != 0
                avecPcr = f and 0x10 != 0 && l >= 7
            }
            debut = o + 5 + l
        }
        if (afc and 1 != 0) {
            if (p.cc >= 0 && !discontinuite) {
                val attendu = (p.cc + 1) and 15
                when (cc) {
                    attendu -> {}
                    p.cc -> { p.doublons++; doublons++ }
                    else -> { val m = ((cc - attendu) and 15).toLong(); p.erreurs++; erreursCc++; p.manquants += m; manquants += m }
                }
            }
            p.cc = cc
            if (b1 and 0x40 != 0 && debut < o + 188) section(pid, a, debut, o + 188)
        }
        // After the continuity check: a gap ending on this packet belongs to the interval it closes.
        if (avecPcr) pcr(p, a, o + 6, discontinuite)
    }

    private fun pcr(p: P, a: ByteArray, k: Int, discontinuite: Boolean) {
        if (pcrPid < 0) pcrPid = p.pid
        p.pcr = true
        if (p.pid != pcrPid) return
        val base = ((a[k].toLong() and 0xFF) shl 25) or ((a[k + 1].toLong() and 0xFF) shl 17) or ((a[k + 2].toLong() and 0xFF) shl 9) or
            ((a[k + 3].toLong() and 0xFF) shl 1) or ((a[k + 4].toLong() and 0xFF) shr 7)
        val v = base * 300 + (((a[k + 4].toLong() and 1) shl 8) or (a[k + 5].toLong() and 0xFF))
        if (dernierPcr >= 0 && !discontinuite) {
            val dt = ((v - dernierPcr + PCR_MOD) % PCR_MOD) / 27e6
            val n = paquets - paquetDernierPcr
            // PCRs come every 100 ms at most: a longer gap is packets lost, over 2 s the stream jumped.
            if (dt > 0 && dt < 2.0) {
                pcrSecondes += dt; pcrPaquets += n
                if (erreursCc == erreursDernierPcr) { propres.addLast(Intervalle(dt, n)); while (propres.size > 128) propres.removeFirst() }
                else { trous.addLast(Intervalle(dt, n)); while (trous.size > 64) trous.removeFirst() }
            } else pcrSauts++
        }
        dernierPcr = v; paquetDernierPcr = paquets; erreursDernierPcr = erreursCc
    }

    /** A table starting in this packet (one-packet sections: PAT, PMT and SDT of a DATV stream fit). */
    private fun section(pid: Int, a: ByteArray, debut: Int, fin: Int) {
        val s = debut + 1 + (a[debut].toInt() and 0xFF)   // the pointer field
        if (s + 8 > fin) return
        val table = a[s].toInt() and 0xFF
        val long = ((a[s + 1].toInt() and 0x0F) shl 8) or (a[s + 2].toInt() and 0xFF)
        val finSection = minOf(fin, s + 3 + long - 4)   // the CRC left out
        when {
            pid == 0 && table == 0x00 -> {
                pat = true
                var k = s + 8
                while (k + 4 <= finSection) {
                    val prog = ((a[k].toInt() and 0xFF) shl 8) or (a[k + 1].toInt() and 0xFF)
                    val p = ((a[k + 2].toInt() and 0x1F) shl 8) or (a[k + 3].toInt() and 0xFF)
                    if (prog != 0) pmt += p
                    k += 4
                }
            }
            pid == 0x11 && (table == 0x42 || table == 0x46) -> sdt = true
            pid in pmt && table == 0x02 && s + 12 <= finSection -> {
                val info = ((a[s + 10].toInt() and 0x0F) shl 8) or (a[s + 11].toInt() and 0xFF)
                var k = s + 12 + info
                while (k + 5 <= finSection) {
                    val t = a[k].toInt() and 0xFF
                    val p = ((a[k + 1].toInt() and 0x1F) shl 8) or (a[k + 2].toInt() and 0xFF)
                    types[p] = t
                    k += 5 + (((a[k + 3].toInt() and 0x0F) shl 8) or (a[k + 4].toInt() and 0xFF))
                }
            }
        }
    }

    @Synchronized
    fun bilan(): Bilan = Bilan(octets, paquets, nuls, tei, pertesSync, erreursCc, manquants, doublons,
        pids.values.sortedBy { it.pid }.map { Pid(it.pid, it.paquets, it.erreurs, it.manquants, it.doublons, it.pcr) },
        types.toMap(), pmt.toSet(), pat, sdt, pcrPid, pcrSecondes, pcrPaquets, propres.toList(), pcrSauts, trous.toList())

    companion object {
        private const val PCR_MOD = (1L shl 33) * 300

        /** What a PID carries, in words. */
        fun role(pid: Int, b: Bilan): String = when {
            pid == 0 -> "PAT"
            pid == 0x10 -> "NIT"
            pid == 0x11 -> "SDT"
            pid == 0x12 -> "EIT"
            pid == 0x14 -> "TDT"
            pid in b.pmt -> "PMT"
            pid == 0x1FFF -> "vide"
            pid in b.types -> Datv.typeFlux(b.types.getValue(pid))
            else -> "?"
        }
    }
}
