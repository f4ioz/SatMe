/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.Ft8Cascade
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Base64

/**
 * FT8 / FT4 on the owner's web site, through « Publication web » (both off by default):
 *
 * - **`direct-ft8`** (the line « FT8/FT4 » of « Direct sur le site », [DirectSitePc.Canal.FT8]): each slot
 *   once it is over and decoded, what came out of it — its start, the mode, the satellite of the pass under
 *   way (or the band), and per message its audio frequency, time offset, report, text and what it says
 *   (callsign, square, CQ, for the owner) — at most [MAX_DECODES], the strongest; with a small strip of the
 *   waterfall ([BINS] × [LIGNES] bytes) so the site can draw it. Messages heard over the air: nothing else.
 * - **`ft8-passage`** (a kind of the queue, [SitePc.Genre.FT8]: Sur clic by default): a journal's pass with
 *   all its slots' messages, its satellite, AOS, LOS, its highest elevation and how many stations, squares
 *   and DXCC entities were heard ([contenu]); the 🌐 of the FT8 tab's list ([liste]).
 *
 * Only what [trancheJson] and [passageJson] write goes: the keys of [CLES_TRANCHE], [CLES_DECODE] and
 * [CLES_PASSAGE] — never a setting, an address, a path or anything of the station's but its satellite.
 */
class Ft8SitePc(private val horloge: () -> Long = System::currentTimeMillis) {

    /**
     * What the station listens through when a slot closes: the satellite of the pass under way (null between
     * passes, on the ground), its NORAD number, geostationary, the pass's AOS, LOS and highest elevation, the
     * dial (sky Hz, the rig's or QO-100's), the transponder chosen.
     */
    class Contexte(val satellite: String? = null, val norad: Int = 0, val geo: Boolean = false, val aos: Long? = null, val los: Long? = null,
                   val elMax: Double? = null, val dialHz: Long? = null, val transpondeur: String? = null)

    // Set by Main.
    /** The decoder's lines, newest first ([Ft8Pc.entendus], the demonstration's too). */
    @Volatile var entendus: () -> List<Ft8Pc.Entendu> = { emptyList() }
    /** FT8 / FT4 listening on. */
    @Volatile var ecoute: () -> Boolean = { false }
    @Volatile var mode: () -> String = { "FT8" }
    @Volatile var cascade: () -> Ft8Cascade? = { null }
    /** When the waterfall's newest row was made ([Ft8Pc.derniereLigneMs]). */
    @Volatile var derniereLigneMs: () -> Long = { 0L }
    @Volatile var contexte: () -> Contexte? = { null }
    /** The owner's callsign (« for me »). */
    @Volatile var moi: () -> String = { "" }
    /** The journal's passes and their messages ([JournalPc.passages], [JournalFtPc.duPassage]). */
    @Volatile var passages: () -> List<JournalPassage.Entree> = { emptyList() }
    @Volatile var decodesDe: (JournalPassage.Entree) -> List<JournalFt.Decode> = { emptyList() }

    /** The waterfall's strips of the last slots (start → strip), for the live and the passes published soon after. */
    private val bandes = object : LinkedHashMap<Long, JSONObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, JSONObject>?) = size > MAX_BANDES
    }
    /** The last slot sent live (its start). */
    @Volatile private var envoyee = Long.MIN_VALUE

    private fun duree(m: String) = JournalFt.dureeTranche(m)

    /** Each second: the strip of the slot just over kept, while listening (nothing when the waterfall has no rows). */
    @Synchronized fun capture(t: Long = horloge()) {
        if (!ecoute()) return
        val m = mode(); val d = duree(m)
        val debut = (t - 500L) / d * d - d
        if (debut in bandes) return
        val c = cascade() ?: return
        bande(c, derniereLigneMs(), debut, debut + d)?.let { bandes[debut] = it }
    }

    /** A slot's strip, if kept. */
    @Synchronized fun bandeDe(debut: Long): JSONObject? = bandes[debut]?.let { JSONObject(it.toString()) }

    /**
     * The live slot to send now, if any: the last one over whose decodes are in (a little over half a slot after
     * its end: the decoder's budget), not sent yet; null while not listening.
     */
    fun prete(t: Long = horloge()): JSONObject? {
        if (!ecoute()) return null
        val m = mode(); val d = duree(m)
        val debut = (t - d - d * 6 / 10) / d * d
        if (debut <= envoyee) return null
        val l = runCatching { entendus() }.getOrDefault(emptyList()).filter { it.tranche == debut && it.mode.equals(m, true) }
        return trancheJson(debut, m, l, moi(), runCatching { contexte() }.getOrNull(), bandeDe(debut))
    }

    /** The slot starting at [debut] sent (or dropped): not again. */
    fun envoyee(debut: Long) { if (debut > envoyee) envoyee = debut }

    // ------------------------------------------------------------------ the passes

    /** The pass [id]'s messages ready for the site (`ft8-passage`), null when it has none. */
    fun contenu(id: String): SitePc.Contenu? {
        val e = runCatching { passages() }.getOrDefault(emptyList()).firstOrNull { it.id == id } ?: return null
        val l = runCatching { decodesDe(e) }.getOrDefault(emptyList())
        if (l.isEmpty()) return null
        val o = passageJson(e, l, moi(), synchronized(this) { bandes.toMap() })
        val modes = o.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().joinToString("/")
        val meta = JSONObject().put("titre", listOf(modes.ifEmpty { "FT8" }, e.satName.take(40), Instant.ofEpochMilli(e.debutMs).toString().take(16).replace('T', ' ') + " UTC").joinToString(" · "))
            .put("satellite", e.satName.take(40)).put("utc", Instant.ofEpochMilli(e.debutMs).toString()).put("mode", modes)
            .put("stations", o.optInt("stations")).put("carres", o.optInt("carres"))
        return SitePc.Contenu(o.toString().toByteArray(Charsets.UTF_8), null, "application/json", meta)
    }

    /**
     * The FT8 tab's list (`GET api/site/ft8`): the last passes with messages, newest first, each with its satellite,
     * times, counts and whether (where) it is on the site ([publie]).
     */
    fun liste(publie: (String) -> SitePc.Publie?, pret: Boolean, mode: String): JSONObject {
        val t = horloge()
        val l = runCatching { passages() }.getOrDefault(emptyList()).filter { it.finMs <= t }.sortedByDescending { it.debutMs }.take(60)
            .mapNotNull { e -> runCatching { decodesDe(e) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { e to it } }.take(MAX_LISTE)
        return JSONObject().put("ok", true).put("pret", pret).put("mode", mode).put("passages", JSONArray(l.map { (e, d) ->
            val p = publie(e.id)
            JSONObject().put("id", e.id).put("satellite", e.satName).put("aos", e.debutMs).put("los", e.finMs).put("elMax", Math.round(e.elMax))
                .put("decodes", d.size).put("stations", stations(d, moi()).size).put("publie", p?.quandMs ?: 0L).put("url", p?.url.orEmpty())
        }))
    }

    companion object {
        /** At most this many messages a slot (the strongest). */
        const val MAX_DECODES = 60
        /** A pass's slots at most (an hour of FT4). */
        const val MAX_TRANCHES = 480
        /** The waterfall's strip: columns and rows. */
        const val BINS = 256
        const val LIGNES = 8
        /** Strips kept: three hours of FT8. */
        const val MAX_BANDES = 720
        const val MAX_LISTE = 20

        /** The keys a live slot (`direct-ft8`, without `type`/`version`) may carry, and its `satellite`, `cascade`. */
        val CLES_TRANCHE = setOf("t", "mode", "duree", "satellite", "bande", "dialHz", "transpondeur", "decodes", "n", "cascade")
        val CLES_SATELLITE = setOf("nom", "norad", "geo", "aos", "los", "elMax")
        val CLES_CASCADE = setOf("basHz", "hautHz", "bins", "lignes", "data")
        /** A message's keys. */
        val CLES_DECODE = setOf("df", "dt", "snr", "msg", "de", "grid", "cq", "toMe")
        /** A pass's (`ft8-passage`) keys, and its slots'. */
        val CLES_PASSAGE = setOf("version", "satellite", "aos", "los", "elMax", "transpondeur", "dialHz", "modes", "tranches", "decodes", "stations", "carres", "dxcc")
        val CLES_TRANCHE_PASSAGE = setOf("t", "mode", "decodes", "n", "cascade")

        private val CARRE = Regex("^[A-R]{2}[0-9]{2}$")
        private val INDICATIF = Regex("^[A-Z0-9/]{3,12}$")

        /** A message as the site shows it: FT8's characters only, 40 at most. */
        fun texte(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9 /+\\-.?<>]"), " ").replace(Regex("\\s+"), " ").trim().take(40)

        private fun indicatif(s: String?): String? = s?.uppercase()?.trim('<', '>')?.takeIf { INDICATIF.matches(it) && it.any(Char::isDigit) && it.any(Char::isLetter) }
        private fun base(s: String?) = s.orEmpty().uppercase().trim('<', '>').split('/').maxByOrNull { it.length }.orEmpty()

        /** One message: its audio frequency, time offset, report, text, callsign, square, CQ, for me. */
        fun decodeJson(df: Int, dt: Double, snr: Int, message: String, de: String?, a: String?, carre: String?, moi: String): JSONObject {
            val msg = texte(message)
            val o = JSONObject().put("df", df.coerceIn(0, 5000)).put("dt", Math.round(dt.coerceIn(-5.0, 5.0) * 10) / 10.0)
                .put("snr", snr.coerceIn(-40, 40)).put("msg", msg)
            indicatif(de)?.let { o.put("de", it) }
            carre?.uppercase()?.takeIf { CARRE.matches(it) && it != "RR73" }?.let { o.put("grid", it) }
            o.put("cq", msg == "CQ" || msg.startsWith("CQ "))
            val m = base(moi)
            o.put("toMe", m.isNotEmpty() && base(a) == m)
            return o
        }

        /** The band of a dial frequency (sky Hz), for a slot heard without a satellite. */
        fun bande(hz: Long?): String? {
            val m = (hz ?: return null) / 1e6
            return when {
                m in 1.8..2.0 -> "160 m"; m in 3.5..4.0 -> "80 m"; m in 5.3..5.5 -> "60 m"; m in 7.0..7.3 -> "40 m"; m in 10.1..10.15 -> "30 m"
                m in 14.0..14.35 -> "20 m"; m in 18.068..18.168 -> "17 m"; m in 21.0..21.45 -> "15 m"; m in 24.89..24.99 -> "12 m"; m in 28.0..29.7 -> "10 m"
                m in 50.0..54.0 -> "6 m"; m in 70.0..70.5 -> "4 m"; m in 144.0..148.0 -> "2 m"; m in 430.0..440.0 -> "70 cm"; m in 1240.0..1300.0 -> "23 cm"
                m in 2400.0..2450.0 -> "13 cm"; m in 10450.0..10500.0 -> "3 cm"
                else -> null
            }
        }

        /** A slot's messages, the strongest [MAX_DECODES] (then by audio frequency). */
        private fun choisis(l: List<JSONObject>): List<JSONObject> =
            l.sortedByDescending { it.optInt("snr") }.take(MAX_DECODES).sortedBy { it.optInt("df") }

        /**
         * A live slot (`direct-ft8`, without `type`/`version`): its start [debut], [mode], its length, the satellite
         * of [ctx] (or the band), the dial, the transponder, its messages ([l], only this slot's), how many there
         * were, the strip [bande].
         */
        fun trancheJson(debut: Long, mode: String, l: List<Ft8Pc.Entendu>, moi: String, ctx: Contexte?, bande: JSONObject?): JSONObject {
            val m = if (mode.equals("FT4", true)) "FT4" else "FT8"
            val o = JSONObject().put("t", debut).put("mode", m).put("duree", JournalFt.dureeTranche(m))
            ctx?.let { c ->
                c.satellite?.takeIf { it.isNotBlank() }?.let { s ->
                    val j = JSONObject().put("nom", s.replace(Regex("\\p{Cntrl}"), " ").trim().take(40)).put("norad", c.norad.coerceAtLeast(0)).put("geo", c.geo)
                    if (!c.geo) { c.aos?.takeIf { it > 0 }?.let { j.put("aos", it) }; c.los?.takeIf { it > 0 }?.let { j.put("los", it) }
                        c.elMax?.let { j.put("elMax", Math.round(it * 10) / 10.0) } }
                    o.put("satellite", j)
                }
                c.dialHz?.takeIf { it > 0 }?.let { o.put("dialHz", it) }
                if (c.satellite.isNullOrBlank()) bande(c.dialHz)?.let { o.put("bande", it) }
                c.transpondeur?.replace(Regex("\\p{Cntrl}"), " ")?.trim()?.takeIf { it.isNotEmpty() && !c.satellite.isNullOrBlank() }?.let { o.put("transpondeur", it.take(60)) }
            }
            val d = l.filter { it.tranche == debut }.distinctBy { it.texte.uppercase() }
            o.put("decodes", JSONArray(choisis(d.map { decodeJson(it.frequenceHz, it.decalageS, it.rapportDb, it.texte, it.appelant, it.appele, it.locator, moi) })))
            o.put("n", d.size)
            bande?.let { o.put("cascade", it) }
            return o
        }

        /** The stations heard in [l] (their callsign, not mine), with the square each gave. */
        fun stations(l: List<JournalFt.Decode>, moi: String): Map<String, String?> {
            val m = base(moi)
            val r = LinkedHashMap<String, String?>()
            for (d in l) {
                val c = indicatif(d.appelant) ?: continue
                if (m.isNotEmpty() && base(c) == m) continue
                val g = d.locator?.uppercase()?.takeIf { CARRE.matches(it) && it != "RR73" }
                if (c !in r || (r[c] == null && g != null)) r[c] = g
            }
            return r
        }

        /**
         * A pass (`ft8-passage`'s file): its satellite, AOS, LOS, highest elevation, transponder, the dial at its
         * start (when the rig was read), its modes, each slot with its messages (and its strip when [bandes] has
         * it), and how many messages, stations, squares and DXCC entities.
         */
        fun passageJson(e: JournalPassage.Entree, l: List<JournalFt.Decode>, moi: String, bandes: Map<Long, JSONObject> = emptyMap()): JSONObject {
            val tri = l.sortedBy { it.tMs }
            val parTranche = tri.groupBy { it.tMs to (if (it.mode.equals("FT4", true)) "FT4" else "FT8") }.entries.toList().takeLast(MAX_TRANCHES)
            val tranches = JSONArray(parTranche.map { (k, d) ->
                val u = d.distinctBy { it.message.uppercase() }
                JSONObject().put("t", k.first).put("mode", k.second)
                    .put("decodes", JSONArray(choisis(u.map { decodeJson(it.hz, it.dt, it.db, it.message, it.appelant, it.appele, it.locator, moi) })))
                    .put("n", u.size).also { o -> bandes[k.first]?.let { b -> o.put("cascade", JSONObject(b.toString())) } }
            })
            val st = stations(tri, moi)
            val o = JSONObject().put("version", 1)
                .put("satellite", JSONObject().put("nom", e.satName.replace(Regex("\\p{Cntrl}"), " ").trim().take(40)).put("norad", e.catnum.coerceAtLeast(0)))
                .put("aos", e.debutMs).put("los", e.finMs).put("elMax", Math.round(e.elMax * 10) / 10.0)
            e.transpondeur.replace(Regex("\\p{Cntrl}"), " ").trim().takeIf { it.isNotEmpty() }?.let { o.put("transpondeur", it.take(60)) }
            e.points.firstNotNullOfOrNull { it.dlHz }?.takeIf { it > 0 }?.let { o.put("dialHz", it) }
            o.put("modes", JSONArray(parTranche.map { it.key.second }.distinct()))
            o.put("tranches", tranches).put("decodes", tri.size).put("stations", st.size)
                .put("carres", st.values.filterNotNull().map { it.uppercase() }.distinct().size)
                .put("dxcc", st.keys.mapNotNull { runCatching { Dxcc.entite(it)?.nom }.getOrNull() }.distinct().size)
            return o
        }

        /**
         * The strip of the slot [debut]..[fin] from the waterfall [c] (its newest row made at [derniere]): [LIGNES] rows of
         * [BINS] bytes, each the strongest of its cell, base64; null when the waterfall does not cover half the slot.
         */
        fun bande(c: Ft8Cascade, derniere: Long, debut: Long, fin: Long): JSONObject? {
            if (derniere <= 0L) return null
            val rangs = c.depuis(c.total - c.lignesMax)
            val n = rangs.size
            if (n == 0) return null
            val pas = c.pasMs
            val dans = rangs.withIndex().filter { (j, _) -> val tj = derniere - (n - 1 - j) * pas; tj >= debut && tj < fin }.map { it.value }
            if (dans.isEmpty() || dans.size * pas < (fin - debut) / 2.0) return null
            val cols = dans[0].size
            val out = ByteArray(BINS * LIGNES)
            for (r in 0 until LIGNES) {
                val a = r * dans.size / LIGNES; val b = maxOf(a + 1, (r + 1) * dans.size / LIGNES)
                for (k in 0 until BINS) {
                    val x0 = k * cols / BINS; val x1 = maxOf(x0 + 1, (k + 1) * cols / BINS)
                    var v = 0
                    for (i in a until minOf(b, dans.size)) for (x in x0 until minOf(x1, cols)) { val u = dans[i][x].toInt() and 0xFF; if (u > v) v = u }
                    out[r * BINS + k] = v.toByte()
                }
            }
            return JSONObject().put("basHz", c.basseHz).put("hautHz", c.hauteHz).put("bins", BINS).put("lignes", LIGNES)
                .put("data", Base64.getEncoder().encodeToString(out))
        }
    }
}
