/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Qso

/**
 * The demonstration's FT8/FT4 band: made-up stations (Q prefixes, never
 * real callsigns) calling CQ in their slots, and answering what the
 * simulated transmitter « sent » ([Ft8EmissionPc.auEnvoi]) one slot later,
 * as a station would — report, R and report, RR73, 73. Their lines go into
 * the decoder's list ([Ft8Pc.injecte]) as if decoded, never to PSK Reporter.
 *
 * Nothing is transmitted: in the demonstration the transmitter only
 * simulates ([ModeDemo]); this gives the page something to answer.
 */
class Ft8DemoPc(
    private val ft8: Ft8Pc,
    private val moi: () -> String,
    private val horloge: () -> Long = System::currentTimeMillis,
    graine: Long = 4_242L,
) {
    private data class Fausse(val indicatif: String, val carre: String, val hz: Int, val pair: Boolean, val snr: Int)

    private val alea = java.util.Random(graine)
    private val stations = STATIONS.mapIndexed { i, (c, g) -> Fausse(c, g, 450 + (i * 337) % 2_100, i % 2 == 0, -4 - (i * 7) % 18) }
    /** Lines to show once their slot closes: slot start → lines. */
    private val aVenir = HashMap<Long, MutableList<Ft8Pc.Entendu>>()
    private var derniere = Long.MIN_VALUE

    private fun ft4() = ft8.mode == "FT4"
    private fun duree() = Ft8Qso.dureeTrancheMs(ft4())

    /** What we « sent » in the slot starting at [debut], at [hz]: heard back, and answered next slot. */
    @Synchronized fun envoye(texte: String, debut: Long, hz: Int) {
        val m = moi().trim().uppercase()
        if (m.isEmpty()) return
        val mots = texte.trim().uppercase().split(Regex("\\s+")).map { it.removePrefix("<").removeSuffix(">") }
        // On a satellite our own signal comes back on the downlink: shown in our slot, where it lands.
        ligne(debut, texte, m, mots.getOrNull(0)?.takeIf { it != "CQ" }, null, hz, -6, 0.2)
        val suivante = debut + duree()
        if (mots.getOrNull(0) == "CQ") {
            if (alea.nextDouble() < 0.75) {
                val s = stations.filter { it.pair == Ft8Qso.estPaire(Ft8Qso.tranche(suivante, ft4())) }.let { if (it.isEmpty()) stations else it }
                val f = s[alea.nextInt(s.size)]
                ligne(suivante, "$m ${f.indicatif} ${f.carre}", f.indicatif, m, f.carre, f.hz, f.snr + alea.nextInt(5) - 2)
            }
            return
        }
        val f = stations.firstOrNull { it.indicatif == mots.getOrNull(0) } ?: return
        if (mots.getOrNull(1) != m) return
        val bout = mots.drop(2).joinToString(" ")
        val nous = -8 - alea.nextInt(10)
        val reponse = when (Ft8Qso.bout(texte)) {
            is Ft8Qso.Bout.Grille, Ft8Qso.Bout.Rien -> "$m ${f.indicatif} " + Ft8Qso.rapport(nous)
            is Ft8Qso.Bout.Rapport -> "$m ${f.indicatif} R" + Ft8Qso.rapport(nous)
            is Ft8Qso.Bout.RRapport -> "$m ${f.indicatif} RR73"
            is Ft8Qso.Bout.Accuse -> if (bout == "73") null else "$m ${f.indicatif} 73"
            else -> null
        } ?: return
        // A station does not always hear us the first time.
        if (alea.nextDouble() < 0.85) ligne(suivante, reponse, f.indicatif, m, null, f.hz, f.snr + alea.nextInt(5) - 2)
    }

    private fun ligne(tranche: Long, texte: String, de: String?, a: String?, loc: String?, hz: Int, snr: Int, dt: Double = 0.5 + alea.nextGaussian() * 0.08) {
        aVenir.getOrPut(tranche) { ArrayList() } += Ft8Pc.Entendu(tranche, texte, de, a, loc, hz, snr, dt, ft8.mode)
    }

    /** Each second or so: the slot just closed gets its CQs and its answers. */
    @Synchronized fun tic(t: Long = horloge()) {
        if (!ModeDemo.actif || !ft8.enMarche) { aVenir.clear(); return }
        val d = duree()
        // The slot that closed (its lines « decoded » a moment after its end).
        val k = Ft8Qso.tranche(t - 50L, ft4()) - 1
        if (k <= derniere) return
        derniere = k
        val debut = k * d
        if (alea.nextDouble() < 0.7) {
            val s = stations.filter { it.pair == Ft8Qso.estPaire(k) }
            if (s.isNotEmpty()) { val f = s[alea.nextInt(s.size)]; ligne(debut, "CQ ${f.indicatif} ${f.carre}", f.indicatif, null, f.carre, f.hz, f.snr + alea.nextInt(7) - 3) }
        }
        val l = aVenir.remove(debut).orEmpty()
        aVenir.keys.filter { it < debut }.forEach { aVenir.remove(it) }
        if (l.isNotEmpty()) ft8.injecte(l.sortedByDescending { it.rapportDb })
    }

    companion object {
        /** Made-up callsigns (no Q prefix is ever issued to an amateur), with their grid squares. */
        val STATIONS = listOf("QF1ABE" to "IN88", "QF5LMN" to "JN03", "QG4ABC" to "IO91", "QF6KTR" to "JN23",
            "QI0ABC" to "JN61", "QF4HBX" to "JO10", "QG0RTV" to "IO83", "QF8DGW" to "JN38")
    }
}
