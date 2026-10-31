/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.ft.EnregistrementFt
import fr.f4ioz.satcombo.ft.EnregistrementFt.Emission
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The journal's digital zones: found in a pass's recording, read now by the FT4 and FT8 decoders, kept with the
 * pass, and ready for the web site as `ft8-passage` (FT4 included).
 */
class JournalNumeriquePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-journal-num-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    /** 27/10/2026 16:29:31 UTC, the recording's name. */
    private val origine = 1_793_118_571_000L
    private fun tranche(n: Int) = (origine / 7_500L + 1 + n) * 7_500L

    @Test
    fun une_zone_ft4_est_trouvee_decodee_gardee_et_prete_pour_le_site() {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.callsign = "F4IOZ"
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        val web = JournalWebPc(j, st, dossier)
        // 55 s at 24 kHz as SatMe-PC records: FT4 on alternate slots, the CAT's 4 Hz steps.
        val e1 = (0 until 3).flatMap { k ->
            listOf(Emission("CQ F5LMG IN88", "FT4", tranche(2 * k), 1744.0, -6.0, 0.2),
                Emission("YO2CMI DH5YBE R-13", "FT4", tranche(2 * k + 1), 2080.0, -8.0, 0.8))
        }
        val a = EnregistrementFt.fabrique(origine, 55.0, 24_000, e1, decalage = EnregistrementFt.dents(-20.0, 4.0))
        File(dossier, "enregistrements").mkdirs()
        val nom = "SatMe_RS-44_20261027_162931Z.wav"
        EnregistrementFt.wav(File(dossier, "enregistrements/$nom"), a, 24_000)
        val e = JournalPassage.Entree(44909, "RS-44", origine - 300_000L, origine + 600_000L, "JN18FS",
            transpondeur = "Mode V/U - Transponder", enregistrements = listOf(nom))
        j.rangement.enregistre(e)
        fun lit(route: String, p: Map<String, String> = emptyMap()) = String(web.sert(route) { p[it].orEmpty() }.corps)

        // The zone, worked out once and kept beside the recording.
        val zs = JSONArray(lit("/numerique", mapOf("id" to e.id)))
        assertEquals(zs.toString(), 1, zs.length())
        val z = zs.getJSONObject(0)
        assertEquals("FT4", z.getString("m")); assertEquals("FT4", z.getString("l"))
        assertTrue(File(dossier, "enregistrements/SatMe_RS-44_20261027_162931Z.numerique").isFile)

        // Read now, in the background.
        val n = JSONObject(lit("/numerique/decode", mapOf("id" to e.id, "de" to z.getLong("de").toString(), "a" to z.getLong("a").toString())))
            .getString("travail")
        var t: JSONObject
        val limite = System.currentTimeMillis() + 120_000
        do { Thread.sleep(200); t = JSONObject(lit("/travail", mapOf("n" to n))) } while (!t.getBoolean("fini") && System.currentTimeMillis() < limite)
        val l = t.getJSONArray("resultat")
        val textes = (0 until l.length()).map { l.getJSONObject(it).getString("x") }.toSet()
        assertEquals(setOf("CQ F5LMG IN88", "YO2CMI DH5YBE R-13"), textes)
        assertTrue((0 until l.length()).all { l.getJSONObject(it).getString("m") == "FT4" })
        assertEquals(l.length(), t.getInt("trouves"))

        // Kept with the pass: the page shows them, the site gets them as FT4.
        val gardes = j.ft.duPassage(e)
        assertEquals(6, gardes.size)
        assertEquals(setOf(tranche(0), tranche(1), tranche(2), tranche(3), tranche(4), tranche(5)), gardes.map { it.tMs }.toSet())
        val o = Ft8SitePc.passageJson(e, gardes, "F4IOZ")
        assertTrue(o.keySet().all { it in Ft8SitePc.CLES_PASSAGE })
        assertEquals(listOf("FT4"), (0 until o.getJSONArray("modes").length()).map { o.getJSONArray("modes").getString(it) })
        val tr = o.getJSONArray("tranches")
        assertEquals(6, tr.length())
        assertEquals("FT4", tr.getJSONObject(0).getString("mode"))
        assertEquals("CQ F5LMG IN88", tr.getJSONObject(0).getJSONArray("decodes").getJSONObject(0).getString("msg"))
        assertEquals(2, o.getInt("stations"))

        // Published by hand through « Publication web »: the pass's id handed over.
        val publies = ArrayList<String>()
        web.publieFt = { id -> publies += id; null }
        assertTrue(JSONObject(lit("/numerique/publie", mapOf("id" to e.id))).getBoolean("ok"))
        assertEquals(listOf(e.id), publies)
        web.publieFt = { "« Passages FT8/FT4 » : jamais envoyés" }
        val refus = JSONObject(lit("/numerique/publie", mapOf("id" to e.id)))
        assertFalse(refus.getBoolean("ok")); assertTrue(refus.getString("erreur").contains("jamais"))
        // A pass without messages: nothing to send.
        val vide = JournalPassage.Entree(44909, "RS-44", origine + 3_600_000L, origine + 4_200_000L, "JN18FS")
        j.rangement.enregistre(vide)
        assertFalse(JSONObject(lit("/numerique/publie", mapOf("id" to vide.id))).getBoolean("ok"))
        assertEquals(1, publies.size)
    }
}
