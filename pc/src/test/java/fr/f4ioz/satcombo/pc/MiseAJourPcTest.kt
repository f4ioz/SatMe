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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The update check: versions compared as numbers, GitHub's answers read (fixtures), errors and rate limits quiet. */
class MiseAJourPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-maj-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    private fun fixture(n: String) = MiseAJourPcTest::class.java.getResourceAsStream("/maj/$n")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun les_versions_se_comparent_en_nombres() {
        assertTrue(VersionPc.plusRecente("2026.10.27", "2026.10.9"))
        assertFalse(VersionPc.plusRecente("2026.10.9", "2026.10.27"))
        assertTrue(VersionPc.plusRecente("2026.10.13.1", "2026.10.13"))
        assertTrue(VersionPc.plusRecente("2026.11.1", "2026.10.27"))
        assertTrue(VersionPc.plusRecente("2027.1.1", "2026.12.31"))
        assertEquals(0, VersionPc.compare("2026.10.13", "2026.10.13.0"))
        assertEquals(0, VersionPc.compare("satme-pc-2026.10.27", "2026.10.27"))
        // A build by hand is never older nor newer: nothing to say.
        assertNull(VersionPc.compare("dev", "2026.10.27"))
        assertFalse(VersionPc.plusRecente("2026.10.27", "dev"))
        assertFalse(VersionPc.plusRecente("dev", "2026.10.27"))
        assertFalse(VersionPc.publiee("2026"))
        assertFalse(VersionPc.publiee("2026.10.27-rc1"))
        assertFalse(VersionPc.publiee(""))
    }

    @Test
    fun la_derniere_publication_est_lue() {
        val p = MiseAJourPc.publication(JSONObject(fixture("latest.json")))!!
        assertEquals("2026.10.27", p.version)
        assertEquals("https://github.com/f4ioz/SatMe/releases/tag/satme-pc-2026.10.27", p.page)
        assertEquals("https://github.com/f4ioz/SatMe/releases/download/satme-pc-2026.10.27/satme-pc-windows.msi", p.fichiers["satme-pc-windows.msi"])
        assertTrue(p.fichiers.containsKey("satme-pc.tar"))
        assertTrue(p.dateMs > 0)
        assertTrue(p.notes.contains("SatMe-PC"))
    }

    @Test
    fun dans_la_liste_la_plus_recente_de_satme_pc_publiee() {
        // The list holds another program's newer release, a draft and a pre-release: all left out.
        val p = MiseAJourPc.choisit(JSONArray(fixture("liste.json")))!!
        assertEquals("2026.10.28", p.version)
        assertEquals("https://github.com/f4ioz/SatMe/releases/download/satme-pc-2026.10.28/satme-pc-2026.10.28.tar", p.fichiers["satme-pc-2026.10.28.tar"])
    }

    private class Faux(val reponses: Map<String, MiseAJourPc.Reponse>) : MiseAJourPc.Http {
        val demandes = mutableListOf<String>()
        var panne = false
        override fun get(url: String): MiseAJourPc.Reponse {
            demandes += url
            if (panne) throw java.io.IOException("réseau coupé")
            return reponses.entries.firstOrNull { url.endsWith(it.key) }?.value ?: MiseAJourPc.Reponse(404, "{}")
        }
    }

    @Test
    fun une_version_plus_recente_est_annoncee_avec_le_bon_fichier() {
        var t = 1_000_000L
        val http = Faux(mapOf("/releases/latest" to MiseAJourPc.Reponse(200, fixture("latest.json"))))
        val m = MiseAJourPc(StockageMemoire(), File(dossier, "mise-a-jour.json"), "2026.10.9", http, { t }, windows = true)
        m.tic()
        assertTrue("pas avant le moment du premier essai", http.demandes.isEmpty())
        t += MiseAJourPc.PREMIER_MS
        m.tic()
        assertEquals(1, http.demandes.size)
        assertTrue(m.disponible)
        val e = m.etat()
        assertEquals("2026.10.27", e.getString("version"))
        assertEquals("satme-pc-windows.msi", e.getString("fichier"))
        assertTrue(e.getString("notesHtml").contains("<"))
        // Once a day, not more.
        t += 3_600_000L; m.tic()
        assertEquals(1, http.demandes.size)
        t += MiseAJourPc.JOUR_MS; m.tic()
        assertEquals(2, http.demandes.size)
        // Linux: the package.
        val l = MiseAJourPc(StockageMemoire(), File(dossier, "autre.json"), "2026.10.9", http, { t }, windows = false)
        assertEquals("satme-pc.tar", l.telechargement(m.derniere!!)!!.first)
    }

    @Test
    fun la_reponse_est_gardee_et_un_redemarrage_ne_redemande_pas() {
        var t = 5_000_000L
        val http = Faux(mapOf("/releases/latest" to MiseAJourPc.Reponse(200, fixture("latest.json"))))
        val f = File(dossier, "mise-a-jour.json")
        MiseAJourPc(StockageMemoire(), f, "2026.10.27", http, { t }).verifie()
        assertEquals(1, http.demandes.size)
        t += 60_000L
        val apres = MiseAJourPc(StockageMemoire(), f, "2026.10.27", http, { t })
        assertEquals("2026.10.27", apres.derniere!!.version)
        assertFalse("la même version : rien à dire", apres.disponible)
        t += MiseAJourPc.PREMIER_MS; apres.tic()
        assertEquals("pas de nouvelle question si tôt", 1, http.demandes.size)
    }

    @Test
    fun la_derniere_d_un_autre_programme_fait_lire_la_liste() {
        val autre = JSONObject(fixture("latest.json")).put("tag_name", "gp-server-2026.11.02").toString()
        val http = Faux(mapOf("/releases/latest" to MiseAJourPc.Reponse(200, autre),
            "/releases?per_page=30" to MiseAJourPc.Reponse(200, fixture("liste.json"))))
        val m = MiseAJourPc(StockageMemoire(), File(dossier, "m.json"), "2026.10.27", http, { 0L })
        m.verifie()
        assertEquals(2, http.demandes.size)
        assertEquals("2026.10.28", m.derniere!!.version)
        assertTrue(m.disponible)
        assertTrue(m.etat().getString("notesHtml").contains("Configuration rapide"))
    }

    @Test
    fun limite_et_panne_sont_silencieuses_et_redemandees_plus_tard() {
        var t = 10_000_000L
        val reset = (t + 2 * 3_600_000L) / 1000
        val http = Faux(mapOf("/releases/latest" to MiseAJourPc.Reponse(403, "{\"message\":\"API rate limit exceeded\"}", mapOf("x-ratelimit-reset" to reset.toString()))))
        val m = MiseAJourPc(StockageMemoire(), File(dossier, "m.json"), "2026.10.9", http, { t })
        m.verifie()
        assertFalse(m.disponible)
        assertTrue(m.etat().getString("erreur").contains("limite"))
        t += 3_600_000L; m.tic()
        assertEquals("pas avant la remise à zéro de GitHub", 1, http.demandes.size)
        t += 3_600_000L; m.tic()
        assertEquals(2, http.demandes.size)
        http.panne = true
        t += MiseAJourPc.JOUR_MS; m.tic()
        assertEquals(3, http.demandes.size)
        assertNotNull(m.etat())
        t += 60_000L; m.tic()
        assertEquals("une panne : redemandé dans quelques heures", 3, http.demandes.size)
    }

    @Test
    fun une_version_de_developpement_ne_demande_rien_et_le_reglage_coupe_tout() {
        val http = Faux(mapOf("/releases/latest" to MiseAJourPc.Reponse(200, fixture("latest.json"))))
        val dev = MiseAJourPc(StockageMemoire(), File(dossier, "d.json"), "dev", http, { 1_000_000_000L })
        dev.tic(); dev.verifie()
        assertTrue(http.demandes.isEmpty())
        assertTrue(dev.etat().getBoolean("dev"))
        val st = StockageMemoire()
        var t = 1_000_000_000L
        val m = MiseAJourPc(st, File(dossier, "o.json"), "2026.10.9", http, { t })
        m.actif = false
        t += MiseAJourPc.JOUR_MS; m.tic()
        assertTrue(http.demandes.isEmpty())
        assertFalse(st.getBoolean(MiseAJourPc.CLE_ACTIF, true))
    }

    @Test
    fun l_extrait_prend_la_langue_de_la_page() {
        val n = "Generic text.\n\n### fr\n- Bonjour\n\n### en\n- Hello\n"
        assertEquals("- Bonjour", MiseAJourPc.extrait(n, false))
        assertEquals("- Hello", MiseAJourPc.extrait(n, true))
        assertEquals("Juste du texte", MiseAJourPc.extrait("Juste du texte", false))
    }
}
