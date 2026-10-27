/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import java.util.Locale

/**
 * The FT8 / FT4 messages decoded during a pass, kept with it for the
 * journal's replay and its videos: one line each (tab-separated), the slot's
 * start, the mode, the report, the time offset, the audio frequency, the
 * message and what it says (who calls, who is called, the square).
 *
 * The journal shows them laid over the replay in a panel ([Affichage]):
 * placed and sized with the mouse ([Cadre], shares of the view as the SSTV
 * picture), its font following its width ([mise]), more or less visible
 * ([Affichage.opacite]), the newest at the bottom as WSJT-X or at the top.
 * The page and the station's videos lay it out with the same numbers, so
 * the file shows the panel as the page did.
 */
object JournalFt {

    /** A message heard: [tMs] its slot's start (UTC ms), [hz] its audio frequency. */
    data class Decode(
        val tMs: Long,
        val mode: String,
        val db: Int,
        val dt: Double,
        val hz: Int,
        val message: String,
        val appelant: String? = null,
        val appele: String? = null,
        val locator: String? = null,
        /** Read by expectation during a QSO. */
        val ap: Boolean = false,
        /** The satellite followed when it was heard (0: unknown), so a pass takes only its own. */
        val catnum: Int = 0,
    )

    /** A slot's length: 15 s, 7.5 s in FT4. */
    fun dureeTranche(mode: String): Long = if (mode.uppercase() == "FT4") 7_500L else 15_000L

    /** When it shows on the replay: once its slot is over (when the decoder gave it). */
    fun instant(d: Decode): Long = d.tMs + dureeTranche(d.mode)

    private fun propre(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ').trim()

    /** One line: t, mode, dB, dt, Hz, message, calling, called, square, ap, satellite. */
    fun ecrit(d: Decode): String = listOf(d.tMs.toString(), propre(d.mode), d.db.toString(), "%.1f".format(Locale.US, d.dt),
        d.hz.toString(), propre(d.message), propre(d.appelant), propre(d.appele), propre(d.locator), if (d.ap) "1" else "0",
        d.catnum.toString()).joinToString("\t")

    /** A line read back; null when it is not one (a comment, a broken line). */
    fun lit(l: String): Decode? = runCatching {
        if (l.isBlank() || l.startsWith("#")) return null
        val c = l.split('\t')
        if (c.size < 6) return null
        val msg = c[5].trim().ifEmpty { return null }
        val (de, a, loc) = analyse(msg)
        Decode(c[0].trim().toLong(), c[1].trim().ifEmpty { "FT8" }, c[2].trim().toInt(), c[3].trim().toDoubleOrNull() ?: 0.0,
            c[4].trim().toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0, msg,
            c.getOrNull(6)?.trim()?.ifEmpty { null } ?: de, c.getOrNull(7)?.trim()?.ifEmpty { null } ?: a,
            c.getOrNull(8)?.trim()?.ifEmpty { null } ?: loc, c.getOrNull(9)?.trim() == "1",
            c.getOrNull(10)?.trim()?.toIntOrNull() ?: 0)
    }.getOrNull()

    fun litTout(texte: String): List<Decode> = texte.lineSequence().mapNotNull { lit(it) }.toList()

    fun ecritTout(l: List<Decode>): String = l.joinToString("") { ecrit(it) + "\n" }

    /** Those of a pass: heard from [deMs] to [aMs], of its satellite [catnum] (or of none said), in time order. */
    fun dans(l: List<Decode>, deMs: Long, aMs: Long, catnum: Int = 0): List<Decode> =
        l.filter { it.tMs in deMs..aMs && (catnum == 0 || it.catnum == 0 || it.catnum == catnum) }.sortedWith(compareBy({ it.tMs }, { -it.db }))

    /** Two lists made one, each message of a slot once (the live decoder's kept over a re-reading). */
    fun fusionne(a: List<Decode>, b: List<Decode>): List<Decode> =
        (a + b).distinctBy { (it.tMs / 1000) to it.message.uppercase() }.sortedWith(compareBy({ it.tMs }, { -it.db }))

    private val CARRE = Regex("^[A-R]{2}[0-9]{2}$")
    private val INDICATIF = Regex("^[A-Z0-9/<>.]*[0-9][A-Z0-9/<>.]*$")

    /**
     * What a standard message says: who calls, who is called, the square — « CQ F4IOZ JN18 »
     * (F4IOZ calls everyone), « F4IOZ EA4XYZ IM79 » (EA4XYZ calls F4IOZ), « F4IOZ EA4XYZ -12 ».
     */
    fun analyse(message: String): Triple<String?, String?, String?> {
        val m = message.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (m.isEmpty()) return Triple(null, null, null)
        val loc = m.last().takeIf { m.size >= 3 && CARRE.matches(it) && it != "RR73" }
        fun ind(s: String?) = s?.trim('<', '>')?.takeIf { INDICATIF.matches(it) && it.length in 3..12 }
        return if (m[0] == "CQ") {
            // « CQ DX F4IOZ JN18 », « CQ POTA F4IOZ »: the first callsign after CQ.
            Triple(m.drop(1).firstNotNullOfOrNull { ind(it) }, null, loc)
        } else Triple(ind(m.getOrNull(1)), ind(m[0]), loc)
    }

    fun estCq(d: Decode): Boolean = d.message.trim().uppercase().let { it == "CQ" || it.startsWith("CQ ") }

    private fun base(s: String?) = s.orEmpty().uppercase().trim('<', '>').split('/').maxByOrNull { it.length }.orEmpty()

    /** My callsign in it (called or calling), its portable forms too. */
    fun pourMoi(d: Decode, moi: String): Boolean {
        val b = base(moi).ifEmpty { return false }
        return base(d.appele) == b || base(d.appelant) == b ||
            d.message.uppercase().split(' ').any { base(it) == b }
    }

    /** A station of the log's contacts of the pass in it ([qsos] their callsigns). */
    fun avecQso(d: Decode, qsos: Collection<String>): Boolean {
        if (qsos.isEmpty()) return false
        val l = qsos.map { base(it) }.toSet()
        return base(d.appelant) in l || base(d.appele) in l
    }

    // ------------------------------------------------------------------ the panel

    /**
     * The panel's place over the view: left [x], top [y], width [l], height [h], all shares of
     * the view's (so it fits both 16:9 and 9:16 and any resolution). Kept as "x,y,l,h".
     */
    data class Cadre(val x: Float, val y: Float, val l: Float, val h: Float) {
        /** In pixels in a view [w]×[hv]: within its bounds, inside the view. */
        fun place(w: Float, hv: Float): CadreSstv.Place {
            val lw = l.coerceIn(L_MIN, L_MAX) * w; val lh = h.coerceIn(H_MIN, H_MAX) * hv
            return CadreSstv.Place((x * w).coerceIn(0f, maxOf(0f, w - lw)), (y * hv).coerceIn(0f, maxOf(0f, hv - lh)), lw, lh)
        }
        fun texte(): String = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, x, y, l, h)

        companion object {
            const val L_MIN = 0.15f; const val L_MAX = 1f; const val H_MIN = 0.08f; const val H_MAX = 1f
            /** Where it lands at first: on the left, below the SSTV picture's corner, above the contacts' cards. */
            val DEFAUT = Cadre(0.02f, 0.30f, 0.42f, 0.42f)

            /** From "x,y,l,h"; null when empty, "auto" or unreadable (its place by default). */
            fun lit(s: String?): Cadre? {
                val v = s?.trim()?.split(',')?.map { it.trim().toFloatOrNull() }
                    ?.takeIf { it.size == 4 && it.all { f -> f != null && f.isFinite() } }?.map { it!! } ?: return null
                return Cadre(v[0].coerceIn(0f, 1f), v[1].coerceIn(0f, 1f), v[2].coerceIn(L_MIN, L_MAX), v[3].coerceIn(H_MIN, H_MAX))
            }
        }
    }

    /**
     * How the pass shows its messages: shown or not ([affiche]), where ([cadre], null its place by default),
     * how visible (0–100 %, the panel's background and text), the newest at the top or the bottom.
     */
    data class Affichage(val affiche: Boolean = true, val cadre: Cadre? = null, val opacite: Int = 85, val recentEnHaut: Boolean = false) {
        val place: Cadre get() = cadre ?: Cadre.DEFAUT

        companion object {
            val DEFAUT = Affichage()

            /** As kept with the pass: "1|0 \t x,y,l,h \t opacity \t haut|bas"; [DEFAUT] for what cannot be read. */
            fun lit(v: String): Affichage {
                val c = v.split('\t')
                return Affichage(affiche = c.getOrNull(0)?.trim() != "0", cadre = Cadre.lit(c.getOrNull(1)),
                    opacite = c.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: DEFAUT.opacite,
                    recentEnHaut = c.getOrNull(3)?.trim() == "haut")
            }

            /**
             * From the page's words: « ft » (1/0), « ftCadre » (x,y,l,h or auto), « ftOpacite » (0–100), « ftOrdre »
             * (haut/bas); each one not sent stays as in [base].
             */
            fun depuis(p: (String) -> String, base: Affichage = DEFAUT): Affichage {
                val a = p("ft").trim(); val c = p("ftCadre").trim(); val o = p("ftOpacite").trim(); val r = p("ftOrdre").trim()
                return Affichage(affiche = when (a) { "1" -> true; "0" -> false; else -> base.affiche },
                    cadre = if (c.isEmpty()) base.cadre else Cadre.lit(c),
                    opacite = o.toDoubleOrNull()?.let { Math.round(it).toInt().coerceIn(0, 100) } ?: base.opacite,
                    recentEnHaut = when (r) { "haut" -> true; "bas" -> false; else -> base.recentEnHaut })
            }
        }
        fun texte(): String = (if (affiche) "1" else "0") + "\t" + (cadre?.texte() ?: "") + "\t" + opacite + "\t" + (if (recentEnHaut) "haut" else "bas")
    }

    /** The panel's layout for a width [l] and height [h] (px): its font, its rows' height, its title's, how many rows. */
    data class Mise(val police: Float, val ligne: Float, val marge: Float, val titre: Float, val lignes: Int)

    /** About this many characters across: the font follows the panel's width. */
    const val COLONNES = 34f

    fun mise(l: Float, h: Float): Mise {
        val p = (l / COLONNES * 1.65f).coerceAtLeast(5f)
        val ligne = p * 1.32f; val marge = p * 0.45f; val titre = p * 1.45f
        return Mise(p, ligne, marge, titre, maxOf(0, ((h - 2 * marge - titre) / ligne).toInt()))
    }

    /** The messages shown at [t]: those whose slot is over, the [n] newest, in the order asked. */
    fun visibles(l: List<Decode>, t: Long, n: Int, recentEnHaut: Boolean): List<Decode> {
        if (n <= 0) return emptyList()
        val vus = l.filter { instant(it) <= t }.sortedWith(compareBy({ it.tMs }, { -it.db })).takeLast(n)
        return if (recentEnHaut) vus.sortedWith(compareBy({ -it.tMs }, { -it.db })) else vus
    }
}
