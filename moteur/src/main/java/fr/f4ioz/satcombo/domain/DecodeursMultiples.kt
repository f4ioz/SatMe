/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.sdr.ModeCanal
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Décodeurs multiples » on QO-100: besides the main receiver, up to [MAX]
 * channels, each a dial in the received band, a demodulation and a decoder
 * (FT8, FT4, CW, PSK31, RTTY, SSTV, voice to text, or none). This is the list and its
 * rules; the signal processing is [fr.f4ioz.satcombo.sdr.CanauxSecondaires].
 *
 * Pure logic: SatMe-PC and the phone keep the same list the same way.
 */
object DecodeursMultiples {

    const val MAX = 4

    /** The usual FT4 dial on the narrow transponder (the top of the narrow digital segment). */
    const val CADRAN_FT4_HZ = 10_489_580_000L

    /** Where a PSK31 channel hears its signal, Hz above the dial (as [TraficQo100.accord] tunes it). */
    const val AUDIO_PSK31_HZ = 1_000L
    /** Where an RTTY channel centres its two tones, Hz above the dial. */
    const val AUDIO_RTTY_HZ = 1_500L

    enum class Decodeur(val cle: String, val nom: String, val mode: ModeCanal, val couleur: String) {
        FT8("ft8", "FT8", ModeCanal.USB, "#B07CFF"),
        FT4("ft4", "FT4", ModeCanal.USB, "#D58CFF"),
        CW("cw", "CW", ModeCanal.CW, "#6F8CFF"),
        PSK31("psk31", "PSK31", ModeCanal.USB, "#5BD6E0"),
        RTTY("rtty", "RTTY", ModeCanal.USB, "#E0D45B"),
        SSTV("sstv", "SSTV", ModeCanal.USB, "#FFB454"),
        PHONIE("phonie", "Phonie", ModeCanal.USB, "#3FE0C8"),
        AUCUN("aucun", "Écoute", ModeCanal.USB, "#9AA7B4");

        /** One decoder of this kind at most (it has one engine: one picture, one voice helper). */
        val unique: Boolean get() = this == SSTV || this == PHONIE

        companion object {
            fun de(cle: String?): Decodeur? = entries.firstOrNull { it.cle == cle?.trim()?.lowercase() }
        }
    }

    /** A channel: its dial (QO-100 nominal sky Hz, the suppressed carrier), how it is heard, whether it replaces the main sound. */
    data class Canal(
        val id: Int,
        val cadranHz: Long,
        val decodeur: Decodeur,
        val mode: ModeCanal = decodeur.mode,
        val ecoute: Boolean = false,
    )

    /** The list changed, or why not ([erreur] in the page's words). */
    data class Resultat(val liste: List<Canal>, val erreur: String? = null, val id: Int = 0)

    /** The decoder a classifier's class calls for (« auto »). */
    fun decodeurDe(classe: String?): Decodeur {
        val c = classe?.trim()?.lowercase().orEmpty()
        if (c == "ft4") return Decodeur.FT4
        return Decodeur.de(TraficQo100.decodeurDe(c)) ?: Decodeur.AUCUN
    }

    /**
     * The dial for [decodeur] on a signal centred on [hz]: the classifier's
     * tuning when it named the signal ([classe]), else where that decoder
     * hears it (CW on its 700 Hz note, FT8/FT4 on the usual dial when the
     * signal falls in its window, PSK31 on 1 kHz, RTTY centred on 1.5 kHz,
     * SSTV around 1.9 kHz, a voice around 1.5).
     */
    fun cadranPour(decodeur: Decodeur, hz: Long, classe: String? = null): Long {
        val c = classe?.trim()?.lowercase()
        if (!c.isNullOrEmpty() && decodeurDe(c) == decodeur) return TraficQo100.accord(c, hz).cadranHz
        return when (decodeur) {
            Decodeur.CW -> arrondi(hz - TraficQo100.NOTE_CW_HZ, 10)
            Decodeur.FT8 -> if (hz - TraficQo100.CADRAN_FT8_HZ in 200L..2_900L) TraficQo100.CADRAN_FT8_HZ else arrondi(hz - 1_500, 10)
            Decodeur.FT4 -> if (hz - CADRAN_FT4_HZ in 200L..2_900L) CADRAN_FT4_HZ else arrondi(hz - 1_500, 10)
            Decodeur.PSK31 -> arrondi(hz - AUDIO_PSK31_HZ, 10)
            Decodeur.RTTY -> arrondi(hz - AUDIO_RTTY_HZ, 10)
            Decodeur.SSTV -> arrondi(hz - 1_900, 10)
            Decodeur.PHONIE, Decodeur.AUCUN -> arrondi(hz - 1_500, 10)
        }
    }

    /** [decodeur] added on [cadranHz], when the rules allow. */
    fun ajoute(liste: List<Canal>, cadranHz: Long, decodeur: Decodeur, mode: ModeCanal = decodeur.mode): Resultat {
        if (liste.size >= MAX) return Resultat(liste, "$MAX décodeurs au plus : en retirer un d’abord")
        if (decodeur.unique && liste.any { it.decodeur == decodeur })
            return Resultat(liste, "un seul canal ${decodeur.nom} à la fois")
        if (liste.any { it.decodeur == decodeur && kotlin.math.abs(it.cadranHz - cadranHz) < 100 })
            return Resultat(liste, "ce décodeur est déjà là")
        val id = (liste.maxOfOrNull { it.id } ?: 0) + 1
        return Resultat(liste + Canal(id, cadranHz, decodeur, mode), id = id)
    }

    fun retire(liste: List<Canal>, id: Int): List<Canal> = liste.filter { it.id != id }

    /** [id] heard in place of the main receiver ([on]), the others not: one sound at a time. */
    fun ecoute(liste: List<Canal>, id: Int, on: Boolean): List<Canal> =
        liste.map { it.copy(ecoute = on && it.id == id) }

    /** A channel's decoder, mode or dial changed; the same rules as adding it. */
    fun regle(liste: List<Canal>, id: Int, decodeur: Decodeur? = null, mode: ModeCanal? = null, cadranHz: Long? = null): Resultat {
        val c = liste.firstOrNull { it.id == id } ?: return Resultat(liste, "canal inconnu")
        val d = decodeur ?: c.decodeur
        if (d != c.decodeur && d.unique && liste.any { it.id != id && it.decodeur == d })
            return Resultat(liste, "un seul canal ${d.nom} à la fois")
        val n = c.copy(decodeur = d, mode = mode ?: (if (decodeur != null && decodeur != c.decodeur) d.mode else c.mode),
            cadranHz = cadranHz ?: c.cadranHz)
        return Resultat(liste.map { if (it.id == id) n else it }, id = id)
    }

    /** The ready-made sets offered, by key: their name on the page. */
    val PREREGLAGES = linkedMapOf("ft8-cw-phonie" to "FT8 + CW + phonie", "ft8-ft4" to "FT8 + FT4")

    /**
     * A ready-made set, replacing the list. [cwHz] and [bluHz] are the
     * strongest CW and voice signals named on the waterfall (null: none);
     * without one, CW goes to the middle of its segment and the voice to
     * where the main receiver listens when that is a voice segment, else
     * just under the middle beacon.
     */
    fun prereglage(nom: String, cwHz: Long?, bluHz: Long?, ecouteHz: Long): List<Canal>? {
        var l = emptyList<Canal>()
        fun plus(cadran: Long, d: Decodeur) { l = ajoute(l, cadran, d).liste }
        when (nom) {
            "ft8-cw-phonie" -> {
                plus(TraficQo100.CADRAN_FT8_HZ, Decodeur.FT8)
                plus(cwHz?.let { cadranPour(Decodeur.CW, it, "cw") } ?: (10_489_522_000L - TraficQo100.NOTE_CW_HZ), Decodeur.CW)
                val voix = bluHz?.let { cadranPour(Decodeur.PHONIE, it, "blu") }
                    ?: ecouteHz.takeIf { Qo100.segment(it)?.usage == Qo100.Usage.PHONIE }
                    ?: 10_489_700_000L
                plus(voix, Decodeur.PHONIE)
            }
            "ft8-ft4" -> { plus(TraficQo100.CADRAN_FT8_HZ, Decodeur.FT8); plus(CADRAN_FT4_HZ, Decodeur.FT4) }
            else -> return null
        }
        return l
    }

    /**
     * Where [cadranHz] sits in the receiver's raw band, Hz from its PLL: the
     * panorama's axis ([centreHz] the nominal sky at the PLL, [sens] −1
     * behind an inverting converter).
     */
    fun decalage(cadranHz: Long, centreHz: Double, sens: Int): Double = sens * (cadranHz - centreHz)

    fun json(liste: List<Canal>): JSONArray = JSONArray(liste.map {
        JSONObject().put("id", it.id).put("hz", it.cadranHz).put("decodeur", it.decodeur.cle).put("mode", it.mode.name)
            .put("ecoute", it.ecoute)
    })

    /** The list as kept; anything unreadable dropped, the rules applied again. */
    fun lit(texte: String?): List<Canal> {
        val a = runCatching { JSONArray(texte ?: return emptyList()) }.getOrNull() ?: return emptyList()
        var l = emptyList<Canal>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val d = Decodeur.de(o.optString("decodeur")) ?: continue
            val m = runCatching { ModeCanal.valueOf(o.optString("mode")) }.getOrDefault(d.mode)
            val r = ajoute(l, o.optLong("hz"), d, m)
            if (r.erreur != null) continue
            // Its id kept when free, so the page's selection survives a restart.
            val id = o.optInt("id", r.id).takeIf { k -> k > 0 && l.none { x -> x.id == k } } ?: r.id
            l = l + Canal(id, o.optLong("hz"), d, m, o.optBoolean("ecoute"))
        }
        // One sound at a time, even from a hand-edited file.
        val e = l.firstOrNull { it.ecoute }?.id
        return l.map { it.copy(ecoute = it.id == e) }
    }

    private fun arrondi(hz: Long, pas: Long): Long = Math.floorDiv(hz + pas / 2, pas) * pas
}
