/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ready-made DATV transmit settings for QO-100's wide band — symbol rate,
 * standard, MODCOD, roll-off, frames, pilots and, for some, the band
 * plan's channel — as the BATC/AMSAT-DL band plan suggests them, plus the
 * operator's own.
 *
 * A preset only **fills the form**: it never starts anything and never
 * carries a gain (the power stays the one in force, or the one calibrated
 * for that symbol rate, taken on purpose). Applying one is refused while
 * transmitting; [PlanQo100.refus] and [ReglesEmission] still decide at the
 * start.
 */
object PrereglagesDatv {
    data class Prereglage(
        val nom: String,
        val norme: Norme = Norme.DVBS2,
        /** "QPSK 2/3" (DVB-S2) or DVB-S's code rate. */
        val modcod: String = "QPSK 2/3",
        val srKs: Int = 333,
        /** 0.35, 0.25 or 0.20. */
        val rolloff: Double = 0.25,
        val courtes: Boolean = false,
        val pilotes: Boolean = true,
        /** The uplink (kHz), null: the frequency kept. */
        val monteeKhz: Long? = null,
        /** A built-in one (not removable). */
        val integre: Boolean = false,
        val note: String = "",
    ) {
        fun json(): JSONObject = JSONObject().put("nom", nom).put("norme", norme.cle).put("modcod", modcod).put("sr", srKs).put("ro", rolloff)
            .put("courtes", courtes).put("pilotes", pilotes).put("khz", monteeKhz ?: JSONObject.NULL).put("integre", integre).put("note", note)

        /** The settings route's parameters ([fr.f4ioz.satcombo.pc] « /api/datv/emission/reglage »): never a gain. */
        fun parametres(): Map<String, String> = buildMap {
            put("norme", norme.cle); put("modcod", modcod); put("sr", srKs.toString()); put("ro", rolloff.toString())
            put("courtes", if (courtes) "1" else "0"); put("pilotes", if (pilotes) "1" else "0")
            monteeKhz?.let { put("mhz", (it / 1000.0).toString()) }
        }
    }

    /** Downlink (kHz) to uplink (kHz). */
    private fun m(descenteKhz: Long) = descenteKhz - PlanQo100.DECALAGE_KHZ

    /** The band plan's usual settings (AMSAT-DL / BATC wide band plan). */
    val INTEGRES = listOf(
        Prereglage("Réseau 333 kS (10 499,25)", srKs = 333, modcod = "QPSK 2/3", monteeKhz = m(10_499_250L), integre = true,
            note = "le canal des réseaux du plan de bande, 333 kS/s"),
        Prereglage("333 kS QPSK 2/3", srKs = 333, modcod = "QPSK 2/3", integre = true, note = "le plus courant ; la fréquence gardée"),
        Prereglage("250 kS QPSK 3/4", srKs = 250, modcod = "QPSK 3/4", integre = true),
        Prereglage("125 kS QPSK 2/3 (petite station)", srKs = 125, modcod = "QPSK 2/3", integre = true,
            note = "moins de puissance pour la même densité spectrale"),
        Prereglage("125 kS QPSK 1/2 (marge)", srKs = 125, modcod = "QPSK 1/2", integre = true, note = "le plus robuste, image plus pauvre"),
        Prereglage("500 kS QPSK 2/3", srKs = 500, modcod = "QPSK 2/3", integre = true, note = "canaux étroits jusqu'à 10 496,75 seulement"),
        Prereglage("1 MS QPSK 2/3 (10 494,75)", srKs = 1000, modcod = "QPSK 2/3", monteeKhz = m(10_494_750L), integre = true,
            note = "un canal large ; à réserver à une grosse station"),
        Prereglage("DVB-S 333 kS 3/4", norme = Norme.DVBS, modcod = "QPSK 3/4", srKs = 333, rolloff = 0.35, integre = true,
            note = "pour les récepteurs qui ne lisent que le DVB-S"),
    )

    const val PERSO_MAX = 12

    /** Why [p] cannot be applied (an unknown symbol rate, a MODCOD that does not exist), or null. */
    fun refus(p: Prereglage): String? = when {
        p.srKs !in Datv.DEBITS -> "débit de symboles non prévu : " + p.srKs + " kS/s"
        p.norme == Norme.DVBS2 && fr.f4ioz.satcombo.datv.s2.Modcod.de(p.modcod, p.courtes) == null -> "MODCOD inconnu : " + p.modcod
        p.norme == Norme.DVBS && fr.f4ioz.satcombo.datv.dvbs.TauxDvbs.de(p.modcod) == null -> "taux DVB-S inconnu : " + p.modcod
        p.monteeKhz != null && PlanQo100.refus(p.monteeKhz, p.srKs, p.rolloff) != null -> PlanQo100.refus(p.monteeKhz, p.srKs, p.rolloff)
        else -> null
    }

    fun lis(o: JSONObject): Prereglage? = runCatching {
        val nom = o.optString("nom").trim().take(40).ifBlank { return null }
        Prereglage(nom, Norme.de(o.optString("norme")) ?: Norme.DVBS2, o.optString("modcod", "QPSK 2/3"), o.optInt("sr", 333),
            o.optDouble("ro", 0.25), o.optBoolean("courtes"), o.optBoolean("pilotes", true),
            if (o.isNull("khz") || !o.has("khz")) null else o.optLong("khz"), false, o.optString("note").take(120))
    }.getOrNull()

    fun lisListe(t: String?): List<Prereglage> = runCatching {
        val a = JSONArray(t ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::lis) }.filter { refus(it) == null }.distinctBy { it.nom.lowercase() }.take(PERSO_MAX)
    }.getOrDefault(emptyList())

    fun ecrisListe(l: List<Prereglage>): String = JSONArray(l.map { it.json() }).toString()

    /** [p] kept in [l] by name (case ignored); a built-in name is not taken. */
    fun range(l: List<Prereglage>, p: Prereglage): List<Prereglage> {
        val i = l.indexOfFirst { it.nom.equals(p.nom, true) }
        return if (i < 0) (l + p).take(PERSO_MAX) else l.toMutableList().also { it[i] = p }
    }

    /** Every preset, built-in first; by name. */
    fun tous(perso: List<Prereglage>): List<Prereglage> = INTEGRES + perso.filter { p -> INTEGRES.none { it.nom.equals(p.nom, true) } }

    fun de(perso: List<Prereglage>, nom: String): Prereglage? = tous(perso).firstOrNull { it.nom.equals(nom.trim(), true) }
}
