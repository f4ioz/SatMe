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
import fr.f4ioz.satcombo.sdr.GenerateurIq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scan run by SatMe-PC on its receiver, tried on the test generator (a
 * carrier put 25 kHz above its centre); the memories' folders and the band
 * stack through their routes.
 */
class BalayagePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-balayage-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sdr: SdrPc? = null

    @After fun fin() { runCatching { sdr?.arrete() }; portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 15_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(50) }
    }

    private fun recepteur(): SdrPc {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.sdrAudio = false
        val d = SdrPc(st, SonPc(), StockageMemoire(), portee).also { sdr = it }
        d.suitSatellite = false
        d.manuelleHz = 145_800_000L
        d.recepteur = SdrPc.GENERATEUR
        val rien = GenerateurIq.Signal(false, 0, 0.0)
        d.reglagesGenerateur = GenerateurIq.Reglages(porteuse = GenerateurIq.Signal(true, 25_000, 30.0), cw = rien, blu = rien, fm = rien, ft8 = rien, sstv = rien)
        return d
    }

    private fun BalayagePc.post(r: String, vararg p: Pair<String, String>): JSONObject =
        JSONObject(String(sert(r, "POST") { k -> p.firstOrNull { it.first == k }?.second.orEmpty() }.corps))

    @Test
    fun la_plage_trouve_la_porteuse_du_generateur() {
        val d = recepteur()
        val mem = MemoiresSdrPc(dossier)
        val b = BalayagePc(d, mem, StockageMemoire(), dossier)
        // The receiver stopped: refused.
        val refus = b.post("/api/balayage/demarrer", "genre" to "PLAGE", "debut" to "145,75", "fin" to "145,85")
        assertFalse(refus.toString(), refus.getBoolean("ok"))
        assertTrue(d.demarre())
        val o = b.post("/api/balayage/demarrer", "genre" to "PLAGE", "debut" to "145,75", "fin" to "145,85", "pas" to "12500", "seuil" to "12", "reprise" to "MANUELLE")
        assertTrue(o.toString(), o.getBoolean("ok"))
        attends { b.json().optString("etat") == "ARRETE" }
        val h = b.json().getJSONArray("touches").getJSONObject(0)
        assertEquals(145_825_000L, h.getLong("hz"))
        // Listening there now, by the offset (the generator's centre stays).
        assertEquals(145_825_000L, d.etat.value.cielHz + d.decalageHz)
        // Kept as a memory, in « Balayage ».
        assertTrue(b.post("/api/balayage/garde", "hz" to "145825000").getBoolean("ok"))
        assertEquals("Balayage", mem.memoires().single().dossier)
        // Passed by: on again, never stopped there any more.
        b.post("/api/balayage/passe", "hz" to "145825000")
        assertTrue(mem.memoires().single().passe || b.json().getJSONArray("passeGardees").length() == 1)
        attends { b.json().optString("etat") == "CHERCHE" }
        Thread.sleep(1_500)
        assertEquals("CHERCHE", b.json().optString("etat"))
        // A tuning by hand stops it.
        b.arrete("accord à la main")
        assertFalse(b.json().getBoolean("actif"))
    }

    @Test
    fun le_balayage_par_le_spectre_et_les_memoires_d_un_dossier() {
        val d = recepteur()
        val mem = MemoiresSdrPc(dossier)
        fun m(r: String, corps: String = "", vararg p: Pair<String, String>) =
            JSONObject(String(mem.sert(r, "POST", { k -> p.firstOrNull { it.first == k }?.second.orEmpty() }) { corps.toByteArray() }.corps))
        m("/api/memoires-sdr", """{"nom":"Rien","hz":145775000,"mode":"NFM","dossier":"Essai/Vide"}""")
        m("/api/memoires-sdr", """{"nom":"Porteuse","hz":145825000,"mode":"USB","dossier":"Essai"}""")
        m("/api/memoires-sdr", """{"nom":"Ailleurs","hz":145812500,"mode":"NFM","dossier":"Autre"}""")
        assertTrue(m("/api/memoires-sdr/dossier", "", "action" to "cree", "nom" to "Relais").getJSONArray("dossiers").toString().contains("Relais"))
        val b = BalayagePc(d, mem, StockageMemoire(), dossier)
        assertTrue(d.demarre())
        // The folder « Essai » and its sub-folder: the carrier's memory stopped on.
        assertTrue(b.post("/api/balayage/demarrer", "genre" to "MEMOIRES", "dossier" to "Essai", "reprise" to "MANUELLE").getBoolean("ok"))
        attends { b.json().optString("etat") == "ARRETE" }
        assertEquals("Porteuse", b.json().getJSONArray("touches").getJSONObject(0).getString("nom"))
        assertEquals(2, b.json().getInt("taille"))
        // The spectrum: a wide range judged at once.
        assertTrue(b.post("/api/balayage/demarrer", "genre" to "SPECTRE", "debut" to "145,4", "fin" to "146,2", "pas" to "12500", "reprise" to "MANUELLE").getBoolean("ok"))
        attends { b.json().optString("etat") == "ARRETE" }
        assertEquals(145_825_000L, b.json().getJSONArray("touches").getJSONObject(0).getLong("hz"))
        b.arrete("fin")
        // The folder removed: its memories to the top, none lost.
        val apres = m("/api/memoires-sdr/dossier", "", "action" to "supprime", "nom" to "Essai")
        assertEquals(3, apres.getJSONArray("memoires").length())
        assertEquals(listOf("Autre", "Relais"), (0 until apres.getJSONArray("dossiers").length()).map { apres.getJSONArray("dossiers").getString(it) })
        // Read again from its file: the same.
        assertEquals(3, MemoiresSdrPc(dossier).memoires().size)
    }

    /** A store counting its writes. */
    private class Compte : fr.f4ioz.satcombo.plateforme.Stockage {
        private val dedans = StockageMemoire()
        val ecritures = java.util.concurrent.atomic.AtomicInteger()
        override fun getString(cle: String, defaut: String?) = dedans.getString(cle, defaut)
        override fun getInt(cle: String, defaut: Int) = dedans.getInt(cle, defaut)
        override fun getLong(cle: String, defaut: Long) = dedans.getLong(cle, defaut)
        override fun getFloat(cle: String, defaut: Float) = dedans.getFloat(cle, defaut)
        override fun getBoolean(cle: String, defaut: Boolean) = dedans.getBoolean(cle, defaut)
        override fun getStringSet(cle: String, defaut: Set<String>?) = dedans.getStringSet(cle, defaut)
        override fun edit(): fr.f4ioz.satcombo.plateforme.Stockage.Edition {
            val e = dedans.edit()
            // Each put returns this one, so that apply() is counted.
            return object : fr.f4ioz.satcombo.plateforme.Stockage.Edition {
                override fun putString(cle: String, valeur: String?) = apply { e.putString(cle, valeur) }
                override fun putInt(cle: String, valeur: Int) = apply { e.putInt(cle, valeur) }
                override fun putLong(cle: String, valeur: Long) = apply { e.putLong(cle, valeur) }
                override fun putFloat(cle: String, valeur: Float) = apply { e.putFloat(cle, valeur) }
                override fun putBoolean(cle: String, valeur: Boolean) = apply { e.putBoolean(cle, valeur) }
                override fun putStringSet(cle: String, valeur: Set<String>?) = apply { e.putStringSet(cle, valeur) }
                override fun remove(cle: String) = apply { e.remove(cle) }
                override fun apply() { ecritures.incrementAndGet(); e.apply() }
            }
        }
    }

    /** A fake rtl_tcp: noise, and the PLL tunings it is asked. */
    private fun fauxRtl(serveur: java.net.ServerSocket, accords: java.util.concurrent.atomic.AtomicInteger, fini: () -> Boolean) = kotlin.concurrent.thread(isDaemon = true) {
        runCatching {
            serveur.accept().use { c ->
                val o = c.getOutputStream()
                o.write("RTL0".toByteArray() + byteArrayOf(0, 0, 0, 5, 0, 0, 0, 29)); o.flush()
                kotlin.concurrent.thread(isDaemon = true) {
                    val e = java.io.DataInputStream(c.getInputStream())
                    runCatching { while (true) { if (e.readUnsignedByte() == 0x01) accords.incrementAndGet(); e.readInt() } }
                }
                val r = java.util.Random(1)
                val bloc = ByteArray(16_384) { (127 + r.nextInt(41) - 20).toByte() }
                while (!fini()) { o.write(bloc); Thread.sleep(7) }
            }
        }
    }

    @Test
    fun le_balayage_n_ecrit_rien_dans_les_reglages_et_rend_la_frequence() {
        val reglagesStation = Compte(); val reglagesSdr = Compte()
        val st = StationPc(reglagesStation, Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.sdrAudio = false
        val d = SdrPc(st, SonPc(), reglagesSdr, portee).also { sdr = it }
        d.suitSatellite = false; d.manuelleHz = 145_800_000L; d.lance = false
        val serveur = java.net.ServerSocket(0)
        d.port = serveur.localPort
        val accords = java.util.concurrent.atomic.AtomicInteger()
        var fini = false
        fauxRtl(serveur, accords, { fini })
        try {
            assertTrue(d.demarre())
            attends { d.trames > 2 }
            val b = BalayagePc(d, null, StockageMemoire(), dossier)
            val avant = reglagesStation.ecritures.get() + reglagesSdr.ecritures.get()
            // 144–146 MHz, 25 kHz, in USB 3 kHz: the PLL moves every few channels.
            assertTrue(b.post("/api/balayage/demarrer", "genre" to "PLAGE", "debut" to "144", "fin" to "146", "pas" to "25000",
                "mode" to "USB", "bw" to "3000", "retour" to "avant").getBoolean("ok"))
            attends(20_000) { b.json().getLong("vus") >= 40 }
            assertTrue("${accords.get()} accords de la PLL", accords.get() >= 5)
            // Steps under way: the receiver far from 145.8, nothing written.
            assertEquals(0, reglagesStation.ecritures.get() + reglagesSdr.ecritures.get() - avant)
            assertEquals(145_800_000L, d.manuelleHz)
            // « Revenir à la fréquence d'avant »: given back, still nothing written.
            b.post("/api/balayage/arreter")
            assertEquals(null, d.passager)
            assertEquals(0, reglagesStation.ecritures.get() + reglagesSdr.ecritures.get() - avant)
            attends { d.etat.value.cielHz == 145_800_000L }
            // A tuning by hand stops it where it is: kept once.
            assertTrue(b.post("/api/balayage/demarrer", "genre" to "PLAGE", "debut" to "144", "fin" to "146", "pas" to "25000").getBoolean("ok"))
            attends(20_000) { b.json().getLong("vus") >= 10 }
            val ici = d.passager!!.hz
            b.arrete("accord à la main", main = true)
            assertEquals(ici, d.manuelleHz)
            val n = reglagesStation.ecritures.get() + reglagesSdr.ecritures.get() - avant
            assertTrue("$n écritures", n in 1..4)
        } finally { fini = true; runCatching { serveur.close() } }
    }

    @Test
    fun la_pile_de_bandes_note_et_rappelle() {
        val d = recepteur()
        var t = 1_000_000L
        val b = BalayagePc(d, null, StockageMemoire(), dossier, horloge = { t })
        assertTrue(d.demarre())
        attends { d.etat.value.cielHz == 145_800_000L }
        repeat(7) { b.ticPile(); t += 1_000 }
        val pile = b.pileJson().getJSONArray("bandes").getJSONObject(0)
        assertEquals("2m", pile.getString("cle"))
        assertEquals(145_800_000L, pile.getJSONArray("pile").getJSONObject(0).getLong("hz"))
        assertTrue(File(dossier, "pile-bandes.json").isFile)
        // 70 cm, empty: its usual frequency.
        val r = b.post("/api/pile-bandes/rappel", "bande" to "70cm")
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(435_000_000L, d.manuelleHz)
        assertEquals("NFM", d.modeChoisi)
    }
}
