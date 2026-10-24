/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SettingsStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** « Déploiement »: the secrets left out unless asked, the preview, the import put in at the next start. */
class DeploiementPcTest {

    private val racine = File(System.getProperty("java.io.tmpdir"), "satme-deploiement-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { racine.deleteRecursively() }

    private fun station(nom: String): Pair<File, StockageFichier> {
        val d = File(racine, nom).apply { mkdirs() }
        return d to StockageFichier(File(d, "reglages.properties"))
    }

    private fun lanceur(d: File, port: Int = 8090, reseau: Boolean = false) = LanceurPc(port, reseau, d,
        LanceurPc.Source.DEFAUT, LanceurPc.Source.DEFAUT, LanceurPc.Source.DEFAUT, File(racine, nomFichier()))
    private fun nomFichier() = "lanceur.properties"

    private fun remplit(s: StockageFichier) {
        val st = SettingsStore(s)
        st.callsign = "F4IOZ"; st.manualLocator = "JN18FS"; st.minElevDeg = 10
        st.qrzUser = "f4ioz"; st.qrzMdp = "secret-qrz"; st.carnetCle = "cle-wavelog"; st.lotwMdp = "lotw"
        st.icomMdp = "icom"; st.skedsToken = "jeton-hams"
        s.edit().putString("datv_noeud_cle", "code-du-node").putString("pc_version_vue", "2026.10.27").apply()
    }

    @Test
    fun les_secrets_restent_dehors_sauf_si_demandes() {
        val (d, s) = station("a"); remplit(s)
        val dep = DeploiementPc(d, lanceur(d, 8095, true), { s.brutes() })
        val sans = dep.exporte(false)
        val r = sans.getJSONObject("reglages")
        assertEquals("s:F4IOZ", r.getString("callsign"))
        assertEquals("i:10", r.getString("min_elev"))
        for (k in listOf("qrz_user", "qrz_mdp", "carnet_cle", "lotw_mdp", "icom_mdp", "skeds_token", "datv_noeud_cle"))
            assertFalse("$k exporté sans qu'on le demande", r.has(k))
        assertFalse("la version vue est de ce PC", r.has("pc_version_vue"))
        assertFalse(sans.toString().contains("secret-qrz"))
        assertEquals(8095, sans.getJSONObject("lanceur").getInt("port"))
        assertTrue(sans.getJSONObject("lanceur").getBoolean("reseau"))
        val avec = dep.exporte(true).getJSONObject("reglages")
        assertEquals("s:secret-qrz", avec.getString("qrz_mdp"))
        assertEquals("s:code-du-node", avec.getString("datv_noeud_cle"))
        assertTrue(DeploiementPc.secret("un_autre_token"))
        assertFalse(DeploiementPc.secret("rotor_usb_cle"))
    }

    @Test
    fun aller_retour_apercu_puis_repris_au_demarrage_suivant() {
        val (a, sa) = station("a"); remplit(sa)
        val fichier = DeploiementPc(a, lanceur(a, 8095, true), { sa.brutes() }).exporte(false).toString().toByteArray()
        // The other PC: its own callsign and QRZ login, which stay.
        val (b, sb) = station("b")
        SettingsStore(sb).apply { callsign = "F4XYZ"; qrzMdp = "le-mien"; manualLocator = "JN18FS" }
        val dep = DeploiementPc(b, lanceur(b), { sb.brutes() })
        val ap = dep.apercu(fichier, FichierLanceur.Options())
        assertTrue(ap.toString(), ap.getBoolean("ok"))
        val lignes = (0 until ap.getJSONArray("reglages").length()).map { ap.getJSONArray("reglages").getJSONObject(it) }
        val c = lignes.first { it.getString("cle") == "callsign" }
        assertEquals("F4XYZ", c.getString("avant")); assertEquals("F4IOZ", c.getString("apres"))
        assertTrue("le locator est déjà le même", lignes.none { it.getString("cle") == "loc_locator" })
        val opts = (0 until ap.getJSONArray("lanceur").length()).map { ap.getJSONArray("lanceur").getJSONObject(it).getString("nom") }
        assertEquals(listOf("port", "reseau", "dossier"), opts)
        // Imported: the startup options at once (here, a callback), the settings at the next start.
        var ecrit: DeploiementPc.Lu? = null
        assertNull(dep.importe(fichier, avecReglages = true, avecLanceur = true) { ecrit = it; null })
        assertEquals(8095, ecrit!!.port)
        assertTrue(dep.enAttente() > 0)
        assertEquals("rien ne change tant que SatMe-PC tourne", "F4XYZ", SettingsStore(StockageFichier(File(b, "reglages.properties"))).callsign)
        assertTrue(DeploiementPc.appliqueSiPret(b))
        val apres = SettingsStore(StockageFichier(File(b, "reglages.properties")))
        assertEquals("F4IOZ", apres.callsign)
        assertEquals(10, apres.minElevDeg)
        assertEquals("son mot de passe QRZ reste (le fichier n'en avait pas)", "le-mien", apres.qrzMdp)
        assertTrue("l'ancien fichier gardé", b.listFiles()!!.any { it.name.startsWith("avant-deploiement-") && File(it, "reglages.properties").readText().contains("F4XYZ") })
        assertEquals(0, dep.enAttente())
        assertFalse(DeploiementPc.appliqueSiPret(b))
    }

    @Test
    fun avec_les_secrets_ils_sont_masques_dans_l_apercu_et_repris() {
        val (a, sa) = station("a"); remplit(sa)
        val fichier = DeploiementPc(a, lanceur(a), { sa.brutes() }).exporte(true).toString().toByteArray()
        val (b, sb) = station("b")
        val dep = DeploiementPc(b, lanceur(b), { sb.brutes() })
        val ap = dep.apercu(fichier, FichierLanceur.Options())
        assertFalse(ap.toString().contains("secret-qrz"))
        val l = ap.getJSONArray("reglages")
        val mdp = (0 until l.length()).map { l.getJSONObject(it) }.first { it.getString("cle") == "qrz_mdp" }
        assertTrue(mdp.getBoolean("secret")); assertEquals("••••", mdp.getString("apres"))
        assertNull(dep.importe(fichier, true, false) { error("pas d'options demandées") })
        DeploiementPc.appliqueSiPret(b)
        assertEquals("secret-qrz", SettingsStore(StockageFichier(File(b, "reglages.properties"))).qrzMdp)
    }

    @Test
    fun un_autre_fichier_est_refuse() {
        val (b, sb) = station("b")
        val dep = DeploiementPc(b, lanceur(b), { sb.brutes() })
        assertNotNull(dep.importe("{\"app\":\"autre\"}".toByteArray(), true, true) { null })
        assertNotNull(dep.importe("pas du json".toByteArray(), true, true) { null })
        assertFalse(dep.apercu("[]".toByteArray(), FichierLanceur.Options()).getBoolean("ok"))
        // A key or a value not of SatMe's form is dropped.
        val o = JSONObject().put("app", "SatMe-PC").put("deploiement", 1)
            .put("reglages", JSONObject().put("callsign", "s:F4IOZ").put("../x", "s:y").put("brut", "sans type").put("reprises", "i:9"))
        val lu = dep.lit(o.toString().toByteArray()).getOrThrow()
        assertEquals(mapOf("callsign" to "s:F4IOZ"), lu.reglages)
    }

    @Test
    fun les_fichiers_de_lancement_avec_la_session() {
        val l = LanceurPc(8095, true, File("/srv/satme"), LanceurPc.Source.LIGNE, LanceurPc.Source.FICHIER, LanceurPc.Source.DEFAUT, File("/home/o/.config/satme/satme-pc.properties"))
        val u = DeploiementPc.uniteSystemd(l, "/home/o/satme/bin/satme")
        assertTrue(u, u.contains("ExecStart=/home/o/satme/bin/satme --port 8095\n"))
        assertTrue(u.contains("[Install]\nWantedBy=default.target"))
        assertTrue(u.contains("/home/o/.config/satme/satme-pc.properties"))
        val w = DeploiementPc.demarrageWindows(l, "C:\\Users\\o\\AppData\\Local\\SatMe-PC\\SatMe-PC.exe")
        assertTrue(w, w.contains("start \"\" \"C:\\Users\\o\\AppData\\Local\\SatMe-PC\\SatMe-PC.exe\" --port 8095\r\n"))
        assertTrue(w.contains("shell:startup"))
    }
}
