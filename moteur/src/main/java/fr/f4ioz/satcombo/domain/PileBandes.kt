/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * The band stack: for each band (2 m, 70 cm, 23 cm, 13 cm, 3 cm / QO-100,
 * and « autre » for the rest) the last three frequencies listened to, with
 * their mode and width — noted by themselves once the receiver stays on one
 * for [TENUE_MS], given back by pressing the band's button again and again
 * (the first press: the last one used; each next press: the one before).
 *
 * Receive only: an entry tunes the receiver. The time is given by the
 * caller.
 */
object PileBandes {

    data class Bande(val cle: String, val nom: String, val basHz: Long, val hautHz: Long, val defaut: Entree?) {
        operator fun contains(hz: Long): Boolean = hz in basHz..hautHz
    }

    /** One remembered tuning: sky frequency, the receiver's mode ("AUTO", "NFM"…, "" unknown), width (0: the mode's). */
    data class Entree(val hz: Long, val mode: String = "", val largeurHz: Int = 0)

    const val AUTRE = "autre"
    const val PROFONDEUR = 3
    /** Stayed this long on a frequency: it is noted. */
    const val TENUE_MS = 5_000L
    /** Within this, the same frequency (the tuning wobbles, a satellite's Doppler too). */
    const val MEME_HZ = 100L
    /** Within this, the same entry of the stack (updated in place, not doubled). */
    const val VOISINE_HZ = 1_000L

    val BANDES = listOf(
        Bande("2m", "2 m", 144_000_000L, 146_000_000L, Entree(145_800_000L, "NFM", 0)),
        Bande("70cm", "70 cm", 430_000_000L, 440_000_000L, Entree(435_000_000L, "NFM", 0)),
        Bande("23cm", "23 cm", 1_240_000_000L, 1_300_000_000L, Entree(1_296_200_000L, "USB", 0)),
        Bande("13cm", "13 cm", 2_300_000_000L, 2_450_000_000L, Entree(2_320_200_000L, "USB", 0)),
        Bande("3cm", "3 cm / QO-100", 10_000_000_000L, 10_500_000_000L, Entree(10_489_750_000L, "USB", 0)),
    )
    val CLES = BANDES.map { it.cle } + AUTRE

    /** The band holding [hz], [AUTRE] outside them all. */
    fun bandeDe(hz: Long): String = BANDES.firstOrNull { hz in it }?.cle ?: AUTRE

    /** The band's place (for a sort by band); beyond them all for the others. */
    fun rang(hz: Long): Int = BANDES.indexOfFirst { hz in it }.let { if (it < 0) BANDES.size else it }

    fun nom(cle: String): String = BANDES.firstOrNull { it.cle == cle }?.nom ?: "autre"

    /** The stacks and where each band's button is; [observe] and [rappel] change them. */
    class Registre(piles: Map<String, List<Entree>> = emptyMap()) {
        private val piles = HashMap<String, MutableList<Entree>>().also { m ->
            piles.forEach { (k, v) -> if (k in CLES) m[k] = v.take(PROFONDEUR).toMutableList() }
        }
        private val curseurs = HashMap<String, Int>()
        private var vue: Entree? = null
        private var depuis = 0L
        private var notee = false

        fun pile(cle: String): List<Entree> = piles[cle].orEmpty()
        fun curseur(cle: String): Int = curseurs[cle] ?: 0

        /**
         * The receiver tuned on [e] now ([e] null: not receiving). Once it
         * stays [TENUE_MS] on the same one, noted: on top of its band's stack
         * (or in place, when that frequency is already there). True when the
         * stacks changed.
         */
        @Synchronized fun observe(maintenantMs: Long, e: Entree?): Boolean {
            if (e == null || e.hz <= 0) { vue = null; return false }
            val v = vue
            if (v == null || Math.abs(v.hz - e.hz) > MEME_HZ || v.mode != e.mode || v.largeurHz != e.largeurHz) {
                vue = e; depuis = maintenantMs; notee = false
                return false
            }
            if (notee || maintenantMs - depuis < TENUE_MS) return false
            notee = true
            return note(e)
        }

        private fun note(e: Entree): Boolean {
            val cle = bandeDe(e.hz)
            val l = piles.getOrPut(cle) { ArrayList() }
            val i = l.indexOfFirst { Math.abs(it.hz - e.hz) <= VOISINE_HZ }
            if (i >= 0) {
                curseurs[cle] = i
                if (l[i] == e) return false
                l[i] = e
                return true
            }
            l.add(0, e)
            while (l.size > PROFONDEUR) l.removeAt(l.size - 1)
            curseurs[cle] = 0
            return true
        }

        /**
         * The band's button pressed while listening at [ecouteHz]: from
         * another band, its last entry; from within, the next one (round).
         * An empty stack gives the band's usual frequency; « autre », none.
         */
        @Synchronized fun rappel(cle: String, ecouteHz: Long): Entree? {
            val l = piles[cle].orEmpty()
            if (l.isEmpty()) return BANDES.firstOrNull { it.cle == cle }?.defaut
            val dedans = ecouteHz > 0 && bandeDe(ecouteHz) == cle
            val k = if (dedans) (curseur(cle) + 1) % l.size else 0
            curseurs[cle] = k
            val e = l[k]
            // Taken back as it is: not noted again as a new one.
            vue = e; notee = true
            return e
        }

        @Synchronized fun json(ecouteHz: Long = 0L): JSONObject {
            val ici = if (ecouteHz > 0) bandeDe(ecouteHz) else ""
            return JSONObject().put("format", FORMAT).put("bandes", JSONArray((BANDES.map { it.cle to it.nom } + (AUTRE to "autre")).map { (k, n) ->
                JSONObject().put("cle", k).put("nom", n).put("ici", k == ici).put("curseur", curseur(k))
                    .put("pile", JSONArray(pile(k).map { JSONObject().put("hz", it.hz).put("mode", it.mode).put("largeur", it.largeurHz) }))
            }))
        }
    }

    const val FORMAT = "satme-pile-bandes"

    /** The stacks kept in a file (the [Registre.json] form); what cannot be read is left out. */
    fun lit(texte: String): Registre {
        val a = runCatching { JSONObject(texte.trim()).optJSONArray("bandes") }.getOrNull() ?: return Registre()
        val m = HashMap<String, List<Entree>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val p = o.optJSONArray("pile") ?: continue
            m[o.optString("cle")] = (0 until p.length()).mapNotNull { j ->
                p.optJSONObject(j)?.let { e -> Entree(e.optLong("hz"), e.optString("mode").take(8), e.optInt("largeur").coerceIn(0, 24_000)) }
            }.filter { it.hz in 100_000L..300_000_000_000L }
        }
        return Registre(m)
    }
}
