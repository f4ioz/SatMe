/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The page's start-up routes: who may change the startup options, the quick setup's writes, « Quoi de neuf » once. */
class DemarragePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-demarrage-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val NOTES = "## 2026.10.27\n### fr\n- vingt-sept\n### en\n- twenty-seven\n\n## 2026.10.26\n### fr\n- vingt-six\n### en\n- twenty-six\n"

    private class Monde(val d: DemarragePc, val station: StationPc, val fichier: FichierLanceur)

    private fun monde(version: String = "2026.10.27", stockage: StockageMemoire = StockageMemoire(), redemarrage: RedemarragePc? = null): Monde {
        val st = StationPc(stockage, Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val f = FichierLanceur(File(dossier, "conf/satme-pc.properties"))
        val l = LanceurPc.resout(ArgumentsPc(), f.lit(), f.fichier, File(dossier, "donnees").apply { mkdirs() })
        val maj = MiseAJourPc(stockage, File(dossier, "maj.json"), version, { MiseAJourPc.Reponse(404, "{}") })
        val d = DemarragePc(st, l, f, maj, NouveautesPc(NOTES), DeploiementPc(l.dossier, l, { emptyMap() }), redemarrage, version)
        return Monde(d, st, f)
    }

    private fun ReponseWeb.json() = JSONObject(String(corps, Charsets.UTF_8))

    private fun post(m: Monde, chemin: String, q: Map<String, String>, local: Boolean, session: Boolean = false, origine: String? = null, corps: ByteArray? = null) =
        m.d.sert(chemin, "POST", q, local, session, origine, "localhost:8090") { corps }!!

    @Test
    fun les_options_de_demarrage_seulement_depuis_ce_pc_ou_une_session() {
        val m = monde()
        val q = mapOf("reseau" to "1", "port" to "8095")
        val loin = post(m, "/api/demarrage/lanceur", q, local = false)
        assertFalse(loin.json().getBoolean("ok"))
        assertFalse("rien d'écrit", m.fichier.fichier.exists())
        for (route in listOf("/api/demarrage/redemarre", "/api/deploiement/importe", "/api/deploiement/apercu"))
            assertFalse(route, post(m, route, emptyMap(), local = false, corps = "{}".toByteArray()).json().getBoolean("ok"))
        assertFalse(m.d.sert("/deploiement.json", "GET", emptyMap(), false, false, null, null) { null }!!.json().getBoolean("ok"))
        // The network part of the quick setup too; its station part is written.
        val c = post(m, "/api/demarrage/configuration", mapOf("indicatif" to "f4ioz", "reseau" to "1"), local = false)
        assertFalse(c.json().getBoolean("ok"))
        assertFalse(m.fichier.fichier.exists())
        // From this PC: written, read back at the next start.
        val ici = post(m, "/api/demarrage/lanceur", q, local = true).json()
        assertTrue(ici.toString(), ici.getBoolean("ok"))
        assertEquals(FichierLanceur.Options(port = 8095, reseau = true), m.fichier.lit())
        assertTrue("l'avertissement du réseau", ici.getJSONArray("avertissements").length() > 0)
        // From a session opened with the password: allowed.
        assertTrue(post(m, "/api/demarrage/lanceur", mapOf("port" to ""), local = false, session = true).json().getBoolean("ok"))
        assertNull(m.fichier.lit().port)
    }

    @Test
    fun une_requete_venue_d_un_autre_site_est_refusee() {
        val m = monde()
        val r = post(m, "/api/demarrage/lanceur", mapOf("reseau" to "1"), local = true, origine = "http://piege.example")
        assertEquals("403 Forbidden", r.statut)
        assertFalse(m.fichier.fichier.exists())
        assertTrue(post(m, "/api/demarrage/lanceur", mapOf("reseau" to "1"), local = true, origine = "http://localhost:8090").json().getBoolean("ok"))
    }

    @Test
    fun un_port_ou_un_dossier_faux_est_refuse() {
        val m = monde()
        assertFalse(post(m, "/api/demarrage/lanceur", mapOf("port" to "80"), true).json().getBoolean("ok"))
        assertFalse(post(m, "/api/demarrage/lanceur", mapOf("port" to "65535"), true).json().getBoolean("ok"))
        assertFalse(post(m, "/api/demarrage/lanceur", mapOf("dossier" to "pas/absolu"), true).json().getBoolean("ok"))
        val neuf = File(dossier, "ailleurs").path
        assertTrue(post(m, "/api/demarrage/lanceur", mapOf("dossier" to neuf, "donnees" to "copier"), true).json().getBoolean("ok"))
        val o = m.fichier.lit()
        assertEquals(neuf, o.dossier)
        assertEquals(File(dossier, "donnees").absolutePath, o.copieDepuis)
    }

    @Test
    fun la_configuration_rapide_ecrit_les_reglages_de_la_station() {
        val st = StockageMemoire()
        val m = monde(stockage = st)
        m.d.auDemarrage(neuf = true)
        assertTrue(m.d.configRapideAFaire)
        assertTrue("pas de « Quoi de neuf » sur une installation neuve", m.d.quoiDeNeuf().isEmpty())
        assertTrue(m.d.json(true, false).getBoolean("configRapide"))
        val j = post(m, "/api/demarrage/configuration", mapOf("indicatif" to "f4ioz/p", "locator" to "jn18fs", "nom" to "Olivier",
            "utc" to "1", "langue" to "fr", "minEl" to "7", "reseau" to "1", "port" to "8100", "fin" to "1"), local = true).json()
        assertTrue(j.toString(), j.getBoolean("ok"))
        val s = m.station.settings
        assertEquals("F4IOZ/P", s.callsign); assertEquals("JN18FS", s.manualLocator); assertEquals("Olivier", s.plancheNom)
        assertTrue(s.useUtc); assertEquals("fr", s.language); assertEquals(7, s.minElevDeg)
        assertEquals(FichierLanceur.Options(port = 8100, reseau = true), m.fichier.lit())
        assertFalse(m.d.configRapideAFaire)
        // From latitude and longitude: the 6-character square and the exact point kept.
        assertTrue(post(m, "/api/demarrage/configuration", mapOf("lat" to "48,8566", "lon" to "2.3522"), true).json().getBoolean("ok"))
        assertEquals("JN18EU", s.manualLocator); assertEquals(48.8566, s.manualLat, 1e-9); assertEquals(2.3522, s.manualLon, 1e-9)
        // Refused, nothing changed.
        assertFalse(post(m, "/api/demarrage/configuration", mapOf("locator" to "ZZ99"), true).json().getBoolean("ok"))
        assertFalse(post(m, "/api/demarrage/configuration", mapOf("lat" to "95", "lon" to "2"), true).json().getBoolean("ok"))
        assertFalse(post(m, "/api/demarrage/configuration", mapOf("indicatif" to "pas un indicatif"), true).json().getBoolean("ok"))
        assertEquals("JN18EU", s.manualLocator); assertEquals("F4IOZ/P", s.callsign)
    }

    @Test
    fun passer_la_configuration_rapide_la_range() {
        val m = monde()
        m.d.auDemarrage(neuf = true)
        assertTrue(post(m, "/api/demarrage/configuration", mapOf("passe" to "1"), true).json().getBoolean("ok"))
        assertFalse(m.d.configRapideAFaire)
    }

    @Test
    fun quoi_de_neuf_une_fois_apres_une_mise_a_jour() {
        val st = StockageMemoire()
        st.edit().putString(CLE_VERSION_VUE, "2026.10.25").apply()
        val m = monde(stockage = st)
        m.d.auDemarrage(neuf = false)
        assertEquals(listOf("2026.10.27", "2026.10.26"), m.d.quoiDeNeuf().map { it.version })
        val j = m.d.sert("/api/demarrage", "GET", emptyMap(), true, false, null, null) { null }!!.json()
        assertEquals(2, j.getJSONArray("quoiDeNeuf").length())
        assertTrue(j.getJSONArray("quoiDeNeuf").getJSONObject(0).getString("html").contains("vingt-sept"))
        post(m, "/api/demarrage/vu", emptyMap(), true)
        assertTrue(m.d.quoiDeNeuf().isEmpty())
        assertEquals("2026.10.27", st.getString(CLE_VERSION_VUE, null))
        // Always there from the help menu.
        val t = m.d.sert("/api/demarrage/nouveautes", "GET", emptyMap(), true, false, null, null) { null }!!.json()
        assertEquals(2, t.getJSONArray("versions").length())
    }

    @Test
    fun une_version_de_developpement_ne_montre_rien_toute_seule() {
        val st = StockageMemoire()
        st.edit().putString(CLE_VERSION_VUE, "2026.10.25").apply()
        val m = monde("dev", st)
        m.d.auDemarrage(neuf = false)
        assertTrue(m.d.quoiDeNeuf().isEmpty())
        assertEquals("2026.10.25", st.getString(CLE_VERSION_VUE, null))
    }

    @Test
    fun les_sources_et_le_redemarrage_sont_dits() {
        val m = monde(redemarrage = RedemarragePc(arrayOf(), env = { if (it == "INVOCATION_ID") "x" else null }, console = { false }))
        val j = m.d.json(local = true, session = false)
        val l = j.getJSONObject("lanceur")
        assertEquals("defaut", l.getJSONObject("port").getString("source"))
        assertEquals(8090, l.getJSONObject("port").getInt("valeur"))
        assertFalse(j.getJSONObject("redemarrage").getBoolean("possible"))
        assertTrue(j.getJSONObject("redemarrage").getString("raison").contains("systemctl"))
        assertFalse("refusé sous systemd", post(m, "/api/demarrage/redemarre", emptyMap(), true).json().getBoolean("ok"))
        assertFalse(m.d.json(local = false, session = false).getBoolean("permis"))
    }
}
