/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Cascade
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** « FT8/FT4 » on the site: what a slot and a pass carry (and only that), when slots go, the strip, the pass in the queue. */
class Ft8SitePcTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Envoi(val url: String, val entetes: Map<String, String>, val corps: ByteArray?)
    private val envois = ArrayList<Envoi>()
    private var reponse: (Envoi) -> Pair<Int, String> = { 200 to "{\"ok\":true}" }
    /** A slot's start (FT8 and FT4 alike). */
    private var t = 1_800_000_010_000L / 15_000L * 15_000L

    private fun entendu(debut: Long, texte: String, hz: Int, db: Int, mode: String = "FT8"): Ft8Pc.Entendu {
        val (de, a, loc) = JournalFt.analyse(texte)
        return Ft8Pc.Entendu(debut, texte, de, a, loc, hz, db, 0.3, mode)
    }

    private fun contexte() = Ft8SitePc.Contexte("RS-44", 44909, false, t - 300_000L, t + 600_000L, 54.36, 435_640_000L, "Mode V/U lineaire\u0007")

    @Test fun `a slot carries only its keys, its strongest messages, CQ and for me`() {
        val l = ArrayList<Ft8Pc.Entendu>()
        l += entendu(t, "CQ EA4XYZ IM79", 1200, -8)
        l += entendu(t, "F4IOZ DL1ABC JO62", 800, -15)
        l += entendu(t, "F4IOZ/P G4XYZ -12", 1500, -3)
        l += entendu(t - 15_000L, "CQ OLD1AB JN00", 900, 0)
        l += entendu(t, "CQ EA4XYZ IM79", 1210, -9)
        for (i in 0 until 80) l += entendu(t, "K${i}ABC W${i}XYZ R-0${i % 10}", 300 + i * 20, -24 + i % 30)
        l += entendu(t, "CQ BAD1\tX <script>", 2900, 10)
        val o = Ft8SitePc.trancheJson(t, "ft8", l, "F4IOZ", contexte(), null)
        assertTrue(Ft8SitePc.CLES_TRANCHE.containsAll(o.keySet()))
        assertEquals(t, o.getLong("t")); assertEquals("FT8", o.getString("mode")); assertEquals(15_000L, o.getLong("duree"))
        val sat = o.getJSONObject("satellite")
        assertTrue(Ft8SitePc.CLES_SATELLITE.containsAll(sat.keySet()))
        assertEquals("RS-44", sat.getString("nom")); assertEquals(54.4, sat.getDouble("elMax"), 1e-9)
        assertEquals("Mode V/U lineaire", o.getString("transpondeur"))
        assertFalse(o.has("bande"))
        val d = o.getJSONArray("decodes")
        assertEquals(Ft8SitePc.MAX_DECODES, d.length())
        // 84 of this slot (the duplicate once): the count says how many there were.
        assertEquals(84, o.getInt("n"))
        val tous = (0 until d.length()).map { d.getJSONObject(it) }
        tous.forEach { assertTrue(it.keySet().toString(), Ft8SitePc.CLES_DECODE.containsAll(it.keySet())) }
        // Sorted by audio frequency, the strongest kept: the -24 dB ones dropped first.
        assertEquals(tous.map { it.getInt("df") }.sorted(), tous.map { it.getInt("df") })
        assertTrue(tous.none { it.getInt("snr") < -20 })
        val cq = tous.first { it.getString("msg") == "CQ EA4XYZ IM79" }
        assertTrue(cq.getBoolean("cq")); assertFalse(cq.getBoolean("toMe")); assertEquals("EA4XYZ", cq.getString("de")); assertEquals("IM79", cq.getString("grid"))
        assertTrue(tous.first { it.getString("msg").startsWith("F4IOZ/P G4XYZ") }.getBoolean("toMe"))
        assertTrue(tous.first { it.getString("msg").startsWith("F4IOZ DL1ABC") }.getBoolean("toMe"))
        // The text: FT8's characters only.
        assertTrue(tous.none { it.getString("msg").contains('\t') || it.getString("msg").contains("script") && it.getString("msg").contains('"') })
        // The other slot's message is not in it.
        assertTrue(tous.none { it.getString("msg").contains("OLD1AB") })
    }

    @Test fun `without a satellite the band, FT4 slots half as long`() {
        val o = Ft8SitePc.trancheJson(t, "FT4", listOf(entendu(t, "CQ DL1ABC JO62", 1000, -10, "FT4")), "", Ft8SitePc.Contexte(dialHz = 144_174_000L), null)
        assertEquals("FT4", o.getString("mode")); assertEquals(7_500L, o.getLong("duree"))
        assertEquals("2 m", o.getString("bande")); assertFalse(o.has("satellite")); assertFalse(o.has("transpondeur"))
        assertFalse(o.getJSONArray("decodes").getJSONObject(0).getBoolean("toMe"))
        assertNull(Ft8SitePc.bande(null)); assertEquals("3 cm", Ft8SitePc.bande(10_489_540_000L))
    }

    @Test fun `the waterfall's strip of a slot`() {
        val c = Ft8Cascade(200, 3000, 512, 12_000, 360)
        // 16 s of a 1 kHz tone, rows every 128 ms.
        val bloc = ShortArray(1536)
        var n = 0L
        var derniere = 0L
        val debut = t
        val avant = debut - 500L
        for (k in 0 until 125) {
            for (i in bloc.indices) { bloc[i] = (8000 * Math.sin(2 * Math.PI * 1000.0 * n / 12_000.0)).toInt().toShort(); n++ }
            if (c.verse(bloc, bloc.size)) derniere = avant + Math.round(n * 1000.0 / 12_000.0)
        }
        val b = Ft8SitePc.bande(c, derniere, debut, debut + 15_000L)
        assertNotNull(b)
        assertTrue(Ft8SitePc.CLES_CASCADE.containsAll(b!!.keySet()))
        val octets = Base64.getDecoder().decode(b.getString("data"))
        assertEquals(Ft8SitePc.BINS * Ft8SitePc.LIGNES, octets.size)
        // The tone stands out at its column (200..3000 Hz over 256 bins).
        val col = ((1000 - 200) * Ft8SitePc.BINS / 2800)
        val ligne = octets.copyOfRange(3 * Ft8SitePc.BINS, 4 * Ft8SitePc.BINS).map { it.toInt() and 0xFF }
        assertTrue(ligne.subList(col - 2, col + 3).maxOrNull()!! > ligne.subList(0, 40).maxOrNull()!!)
        // A slot the waterfall does not cover: none.
        assertNull(Ft8SitePc.bande(c, derniere, debut + 60_000L, debut + 75_000L))
        assertNull(Ft8SitePc.bande(c, 0L, debut, debut + 15_000L))
    }

    private fun direct(): Triple<SitePc, DirectSitePc, Ft8SitePc> {
        val st = StockageFichier(tmp.newFile("r-" + System.nanoTime() + ".properties"))
        val site = SitePc(st, tmp.newFolder(), { false }, { t }, SitePc.Transport { _, u, e, c -> val x = Envoi(u, e, c?.octets()); envois += x; reponse(x) })
        fr.f4ioz.satcombo.demo.ModulesStation.pose(st, emptySet())
        site.sert("/api/site/essai", "POST") { k -> when (k) { "url" -> "https://example.org/api/publication/{type}"; "jeton" -> "le-jeton"; else -> "" } }
        envois.clear()
        val d = DirectSitePc(st, site, { t })
        site.direct = d
        val f = Ft8SitePc { t }
        d.ft8 = f
        return Triple(site, d, f)
    }

    @Test fun `each slot goes once, after its decoding, only while listening and ticked`() {
        val (site, d, f) = direct()
        var ecoute = true
        var mode = "FT8"
        val lignes = ArrayList<Ft8Pc.Entendu>()
        f.ecoute = { ecoute }; f.mode = { mode }; f.entendus = { lignes.sortedByDescending { it.tranche } }; f.moi = { "F4IOZ" }
        f.contexte = { contexte() }
        val base = t
        lignes += entendu(base, "CQ EA4XYZ IM79", 1200, -8)
        // Not ticked: nothing.
        t = base + 30_000L; d.tic()
        assertTrue(envois.isEmpty())
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_ft8") "1" else "" }
        fun ft8() = envois.filter { it.entetes["X-SatMe-Type"] == "direct-ft8" }
        fun de(x: Envoi) = JSONObject(String(x.corps!!)).getLong("t")
        // The slot is over at +15 s, its decodes in a little over half a slot later (the one before goes first, empty).
        t = base + 15_000L + 8_000L; d.tic()
        assertTrue(ft8().none { de(it) == base })
        t = base + 15_000L + 9_100L; d.tic()
        val ft8 = ft8().filter { de(it) == base }
        assertEquals(1, ft8.size)
        assertEquals("https://example.org/api/publication/direct-ft8", ft8[0].url)
        val o = JSONObject(String(ft8[0].corps!!))
        assertEquals("direct-ft8", o.getString("type")); assertEquals(1, o.getInt("version")); assertEquals(base, o.getLong("t"))
        assertTrue((Ft8SitePc.CLES_TRANCHE + setOf("type", "version")).containsAll(o.keySet()))
        assertEquals("CQ EA4XYZ IM79", o.getJSONArray("decodes").getJSONObject(0).getString("msg"))
        // Not twice.
        t += 1_000L; d.tic(); t += 1_000L; d.tic()
        assertEquals(1, ft8().count { de(it) == base })
        // The next slot, even empty (the live waterfall goes on).
        val n = ft8().size
        t = base + 30_000L + 9_100L; d.tic()
        assertEquals(n + 1, ft8().size)
        assertEquals(base + 15_000L, de(envois.last()))
        assertEquals(0, JSONObject(String(envois.last().corps!!)).getJSONArray("decodes").length())
        // FT4: every 7.5 s.
        mode = "FT4"
        val n0 = envois.size
        for (k in 0 until 8) { t += 3_750L; d.tic() }
        assertEquals(4, envois.size - n0)
        // Listening stopped: nothing.
        ecoute = false
        val n1 = envois.size
        for (k in 0 until 8) { t += 3_750L; d.tic() }
        assertEquals(n1, envois.size)
        // The card says it.
        val c = d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "ft8" }
        assertTrue(c.getBoolean("on")); assertTrue(c.getString("cadence").contains("créneau"))
    }

    @Test fun `a site that does not know direct-ft8 is left alone a while`() {
        val (site, d, f) = direct()
        f.ecoute = { true }; f.mode = { "FT8" }
        site.sert("/api/site/direct", "POST") { k -> if (k == "on_ft8") "1" else "" }
        reponse = { e -> if (e.entetes["X-SatMe-Type"] == "direct-ft8") 404 to "{\"erreur\":\"type inconnu\"}" else 200 to "{}" }
        val base = t
        t = base + 24_100L; d.tic()
        assertEquals(1, envois.size)
        for (k in 1..20) { t += 15_000L; d.tic() }
        assertEquals(1, envois.size)
        assertTrue(d.json().getJSONArray("canaux").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("nom") == "ft8" }.getBoolean("nonPris"))
        t += DirectSitePc.NON_PRIS_STATION_MS; d.tic()
        assertEquals(2, envois.size)
    }

    private fun passage(): Pair<JournalPassage.Entree, List<JournalFt.Decode>> {
        val aos = t - 1_200_000L
        val e = JournalPassage.Entree(44909, "RS-44", aos, aos + 900_000L, "JN18FS", transpondeur = "Mode V/U lineaire",
            points = listOf(JournalPassage.Point(aos, 10.0, 0.0, dlHz = 435_640_000L), JournalPassage.Point(aos + 400_000L, 180.0, 61.27)))
        fun d(k: Int, msg: String, hz: Int, db: Int): JournalFt.Decode {
            val (de, a, loc) = JournalFt.analyse(msg)
            return JournalFt.Decode(aos + k * 7_500L, "FT4", db, 0.2, hz, msg, de, a, loc, catnum = 44909)
        }
        val l = listOf(d(1, "CQ EA4XYZ IM79", 1000, -10), d(1, "F4IOZ DL1ABC JO62", 1500, -12), d(2, "EA4XYZ DL1ABC -05", 1500, -11),
            d(3, "CQ G4ABC IO91", 700, -3), d(3, "F4IOZ EA4XYZ R-07", 1000, -9), d(4, "CQ F4IOZ JN18", 1800, 2))
        return e to l
    }

    @Test fun `a pass carries only its keys, its slots, its counts`() {
        val (e, l) = passage()
        val o = Ft8SitePc.passageJson(e, l, "F4IOZ", mapOf(e.debutMs + 7_500L to JSONObject().put("basHz", 200).put("hautHz", 3000).put("bins", 1).put("lignes", 1).put("data", "AA==")))
        assertTrue(o.keySet().toString(), Ft8SitePc.CLES_PASSAGE.containsAll(o.keySet()))
        assertEquals(setOf("nom", "norad"), o.getJSONObject("satellite").keySet())
        assertEquals(e.debutMs, o.getLong("aos")); assertEquals(e.finMs, o.getLong("los")); assertEquals(61.3, o.getDouble("elMax"), 1e-9)
        assertEquals(435_640_000L, o.getLong("dialHz")); assertEquals(JSONArray().put("FT4").toString(), o.getJSONArray("modes").toString())
        val tr = o.getJSONArray("tranches")
        assertEquals(4, tr.length())
        (0 until tr.length()).forEach { assertTrue(Ft8SitePc.CLES_TRANCHE_PASSAGE.containsAll(tr.getJSONObject(it).keySet())) }
        assertTrue(tr.getJSONObject(0).has("cascade")); assertFalse(tr.getJSONObject(1).has("cascade"))
        assertEquals(6, o.getInt("decodes"))
        // EA4XYZ, DL1ABC, G4ABC (never me); IM79, JO62, IO91; Spain, Germany, England.
        assertEquals(3, o.getInt("stations")); assertEquals(3, o.getInt("carres")); assertEquals(3, o.getInt("dxcc"))
        assertFalse(o.toString().contains("JN18FS"))
    }

    @Test fun `a pass goes through the queue as ft8-passage, at a click, and is listed`() {
        val (site, _, f) = direct()
        val (e, l) = passage()
        f.passages = { listOf(e) }; f.decodesDe = { l }; f.moi = { "F4IOZ" }
        val sources = SourcesSitePcFaux(f)
        site.sources = { g, ref -> sources.contenu(g, ref) }
        site.ft8 = f
        val liste = JSONObject(String(site.sert("/api/site/ft8", "GET") { "" }.corps))
        assertTrue(liste.getBoolean("pret")); assertEquals("clic", liste.getString("mode"))
        val p = liste.getJSONArray("passages").getJSONObject(0)
        assertEquals(e.id, p.getString("id")); assertEquals(3, p.getInt("stations")); assertEquals(6, p.getInt("decodes")); assertEquals(0L, p.getLong("publie"))
        // Sur clic: nothing by itself.
        assertNull(site.publie(SitePc.Genre.FT8, e.id, false)); assertEquals(0, site.envoieDus())
        val r = JSONObject(String(site.sert("/api/site/publie", "POST") { k -> when (k) { "type" -> "ft8-passage"; "ref" -> e.id; else -> "" } }.corps))
        assertTrue(r.toString(), r.getBoolean("ok"))
        reponse = { 201 to "{\"ok\":true,\"id\":\"abc\",\"url\":\"https://example.org/ft8/passage/abc\"}" }
        assertEquals(1, site.envoieDus())
        val x = envois.last()
        assertEquals("https://example.org/api/publication/ft8-passage", x.url)
        val corps = String(x.corps!!)
        assertTrue(corps.contains("\"type\":\"ft8-passage\"") || corps.contains("ft8-passage"))
        assertTrue(corps.contains("\"cle\":\"ft8:${e.id}\""))
        assertTrue(corps.contains("application/json"))
        assertTrue(corps.contains("CQ EA4XYZ IM79"))
        assertEquals("https://example.org/ft8/passage/abc", JSONObject(String(site.sert("/api/site/ft8", "GET") { "" }.corps)).getJSONArray("passages").getJSONObject(0).getString("url"))
        // An unknown pass: said.
        assertTrue(site.publie(SitePc.Genre.FT8, "0_0", true)!!.startsWith("introuvable"))
    }

    /** [SourcesSitePc]'s branch for FT8 alone. */
    private class SourcesSitePcFaux(val f: Ft8SitePc) {
        fun contenu(g: SitePc.Genre, ref: String): SitePc.Contenu? = if (g == SitePc.Genre.FT8) f.contenu(ref) else null
    }
}
