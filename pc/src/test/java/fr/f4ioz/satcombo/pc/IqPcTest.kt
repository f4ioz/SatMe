/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.SigMF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SDR tab with no hardware: the test generator as the receiver, its last
 * seconds kept from the disk ring, a live recording, then played back as the
 * receiver — through the PC's routes.
 */
class IqPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-iq-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sdr: SdrPc? = null

    @After fun fin() { runCatching { sdr?.arrete() }; portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 10_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    @Test
    fun le_generateur_garde_enregistre_et_se_rejoue() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.sdrAudio = false
        val d = SdrPc(st, SonPc(), StockageMemoire(), portee).also { sdr = it }
        d.suitSatellite = false
        d.manuelleHz = 145_800_000L
        d.recepteur = SdrPc.GENERATEUR
        val iq = IqPc(d, st, dossier, stockage = StockageMemoire())
        fun post(r: String, vararg p: Pair<String, String>): JSONObject =
            JSONObject(String(iq.sert("/api/sdr-iq/$r", "POST") { k -> p.firstOrNull { it.first == k }?.second.orEmpty() }.corps))

        assertTrue(post("anneau", "on" to "1", "minutes" to "1").optBoolean("ok"))
        assertTrue(d.demarre())
        assertTrue(d.fictif)
        assertEquals(145_800_000L, d.etat.value.cielHz)
        attends { iq.etat().getJSONObject("anneau").getDouble("tenuS") > 2.5 }

        // « Garder »: the ring's seconds as SigMF, while the ring keeps running.
        assertTrue(post("garde").optBoolean("ok"))
        attends { iq.etat().isNull("copie") && iq.etat().getJSONArray("fichiers").length() == 1 }
        val f = iq.etat().getJSONArray("fichiers").getJSONObject(0)
        assertTrue(f.toString(), f.optBoolean("essai"))
        assertEquals("ci16_le", f.getString("format"))
        assertEquals(145_800_000L, f.getLong("frequence"))
        assertTrue(f.getDouble("duree") > 0.4)
        val data = File(iq.dossier, f.getString("nom"))
        val meta = SigMF.litMeta(SigMF.meta(data).readText())!!
        assertEquals(data.length() / 4, Math.round(f.getDouble("duree") * Dsp.RTL_RATE))
        assertNotNull(meta.debutMs)

        // « Enregistrer » a second, then stop.
        assertTrue(post("enregistre", "on" to "1", "limite" to "1").optBoolean("ok"))
        Thread.sleep(1_200)
        assertTrue(post("enregistre", "on" to "0").optBoolean("ok"))
        assertEquals(2, iq.etat().getJSONArray("fichiers").length())

        // Played back as the receiver: its frequency, paused, sought.
        val rejoue = post("rejoue", "nom" to data.name)
        assertTrue(rejoue.toString(), rejoue.optBoolean("ok"))
        assertNotNull(d.lecture)
        assertEquals(SdrPc.FICHIER, d.recepteur)
        attends { d.etat.value.statut == "lecture" }
        assertEquals(145_800_000L, d.etat.value.cielHz)
        assertTrue(d.fictif)
        assertTrue(post("lecture", "pause" to "1").optBoolean("ok"))
        attends { d.etat.value.statut == "en pause" }
        val j = JsonPc.sdr(d).getJSONObject("lecture")
        assertTrue(j.getBoolean("pause"))
        // A file being read is not deleted; once stopped, it is.
        assertFalse(post("supprime", "nom" to data.name).optBoolean("ok"))
        d.arrete()
        assertTrue(post("supprime", "nom" to data.name).optBoolean("ok"))
        assertFalse(data.exists()); assertFalse(SigMF.meta(data).exists())
        // Names outside the folder are refused.
        assertFalse(post("supprime", "nom" to "../reglages.properties").optBoolean("ok"))
    }

    @Test
    fun les_reglages_du_generateur_sont_gardes_et_bornes() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val stock = StockageMemoire()
        val d = SdrPc(st, SonPc(), stock, portee)
        val iq = IqPc(d, st, dossier, stockage = StockageMemoire())
        iq.sert("/api/sdr-iq/generateur", "POST") { mapOf("cwHz" to "-5000", "ftOn" to "0", "sstvMode" to "Martin M1", "bruit" to "-5")[it].orEmpty() }
        val r = SdrPc(st, SonPc(), stock, portee).reglagesGenerateur
        assertEquals(-5_000, r.cw.decalageHz)
        assertEquals("Martin M1", r.sstvMode)
        assertEquals(-10.0, r.bruitDbfs, 0.0)
        val e = JsonPc.sdr(d).getJSONObject("generateur").getJSONArray("ecoutes")
        assertTrue((0 until e.length()).any { e.getJSONObject(it).getString("cle") == "cw" && e.getJSONObject(it).getInt("hz") == -5_000 - 700 })
    }
}
