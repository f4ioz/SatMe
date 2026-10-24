/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The startup options: the command line, the launcher file, the defaults, in that order; the data folder checked and copied. */
class LanceurPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-lanceur-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    @Test
    fun la_ligne_de_commande_est_lue() {
        val a = ArgumentsPc.lit(arrayOf("--port", "8095", "--reseau", "--dossier", "/srv/satme", "--console", "--apres", "1234"))
        assertEquals(8095, a.port); assertEquals(true, a.reseau); assertEquals(File("/srv/satme"), a.dossier)
        assertEquals(false, a.bureau); assertEquals(1234L, a.apres)
        val vide = ArgumentsPc.lit(arrayOf())
        assertNull(vide.port); assertNull(vide.reseau); assertNull(vide.dossier); assertNull(vide.bureau)
        assertEquals(false, ArgumentsPc.lit(arrayOf("--local")).reseau)
        // A port out of range or missing: as if not given.
        assertNull(ArgumentsPc.lit(arrayOf("--port", "99999")).port)
        assertNull(ArgumentsPc.lit(arrayOf("--port")).port)
        assertTrue(ArgumentsPc.lit(arrayOf("--version")).version)
        assertTrue(ArgumentsPc.lit(arrayOf("--aide")).aide)
    }

    @Test
    fun le_fichier_s_ecrit_et_se_relit_meme_un_chemin_windows() {
        val f = FichierLanceur(File(dossier, "conf/satme-pc.properties"))
        assertEquals(FichierLanceur.Options(), f.lit())
        val o = FichierLanceur.Options(port = 8100, reseau = true, dossier = "C:\\Users\\Olivier\\SatMe données", copieDepuis = "/home/o/.satme")
        f.ecrit(o)
        assertEquals(o, f.lit())
        assertTrue(f.fichier.readText().contains("ligne de commande"))
        // Only what is chosen is written: the rest stays the default.
        f.ecrit(FichierLanceur.Options(reseau = false))
        assertEquals(FichierLanceur.Options(reseau = false), f.lit())
        assertFalse(f.fichier.readText().contains("port="))
    }

    @Test
    fun un_fichier_ecrit_a_la_main_est_lu_sans_surprise() {
        val fic = File(dossier, "satme-pc.properties")
        fic.writeText("# à la main\nport = 9000\nreseau=oui\ndossier=/data/satme\n")
        assertEquals(FichierLanceur.Options(9000, true, "/data/satme"), FichierLanceur(fic).lit())
        fic.writeText("port=abc\nreseau=peut-être\n")
        assertEquals(FichierLanceur.Options(), FichierLanceur(fic).lit())
    }

    @Test
    fun la_ligne_de_commande_passe_avant_le_fichier_qui_passe_avant_les_defauts() {
        val defaut = File(dossier, "defaut")
        val fic = File(dossier, "f.properties")
        val f = FichierLanceur.Options(port = 8100, reseau = true, dossier = File(dossier, "fichier").path)
        val rien = LanceurPc.resout(ArgumentsPc(), FichierLanceur.Options(), fic, defaut)
        assertEquals(8090, rien.port); assertFalse(rien.reseau); assertEquals(defaut.absoluteFile, rien.dossier)
        assertEquals(LanceurPc.Source.DEFAUT, rien.sourcePort)
        val parFichier = LanceurPc.resout(ArgumentsPc(), f, fic, defaut)
        assertEquals(8100, parFichier.port); assertTrue(parFichier.reseau); assertEquals(File(dossier, "fichier").absoluteFile, parFichier.dossier)
        assertEquals(LanceurPc.Source.FICHIER, parFichier.sourceReseau)
        val parLigne = LanceurPc.resout(ArgumentsPc(port = 8200, reseau = false), f, fic, defaut)
        assertEquals(8200, parLigne.port); assertEquals(LanceurPc.Source.LIGNE, parLigne.sourcePort)
        assertFalse("--local l'emporte sur reseau=true du fichier", parLigne.reseau)
        assertEquals(LanceurPc.Source.FICHIER, parLigne.sourceDossier)
    }

    @Test
    fun l_emplacement_ne_depend_pas_du_dossier_des_donnees() {
        val env = mapOf("APPDATA" to "C:\\Users\\o\\AppData\\Roaming", "XDG_CONFIG_HOME" to "/home/o/.cfg")
        assertEquals(File(File("C:\\Users\\o\\AppData\\Roaming", "SatMe"), "satme-pc.properties"),
            FichierLanceur.emplacement({ env[it] }, windows = true, maison = "/x"))
        assertEquals(File("/home/o/.cfg/satme/satme-pc.properties"), FichierLanceur.emplacement({ env[it] }, windows = false, maison = "/x"))
        assertEquals(File("/home/o/.config/satme/satme-pc.properties"), FichierLanceur.emplacement({ null }, windows = false, maison = "/home/o"))
        // A relative XDG_CONFIG_HOME is ignored, as the specification says.
        assertEquals(File("/home/o/.config/satme/satme-pc.properties"), FichierLanceur.emplacement({ if (it == "XDG_CONFIG_HOME") "rel" else null }, false, "/home/o"))
    }

    @Test
    fun le_nouveau_dossier_des_donnees_est_verifie() {
        val actuel = File(dossier, "actuel").apply { mkdirs(); File(this, "reglages.properties").writeText("x") }
        assertNull(LanceurPc.refusDossier(File(dossier, "nouveau").path, actuel, copier = true))
        assertTrue(File(dossier, "nouveau").isDirectory)
        assertNotNull("un chemin relatif", LanceurPc.refusDossier("relatif/satme", actuel, false))
        assertNotNull("dans le dossier actuel", LanceurPc.refusDossier(File(actuel, "sous").path, actuel, false))
        val plein = File(dossier, "plein").apply { mkdirs(); File(this, "a.txt").writeText("a") }
        assertNotNull("copier dans un dossier rempli", LanceurPc.refusDossier(plein.path, actuel, copier = true))
        assertNull("commencer vide dans un dossier existant", LanceurPc.refusDossier(plein.path, actuel, copier = false))
        val fichier = File(dossier, "un-fichier").apply { writeText("x") }
        assertNotNull(LanceurPc.refusDossier(fichier.path, actuel, false))
        assertNull("le même dossier", LanceurPc.refusDossier(actuel.path, actuel, true))
    }

    @Test
    fun les_donnees_sont_copiees_et_l_ancien_dossier_garde() {
        val a = File(dossier, "a").apply { mkdirs() }
        File(a, "reglages.properties").writeText("callsign=s:F4IOZ")
        File(a, "journal/2026/p.json").apply { parentFile.mkdirs(); writeText("{}") }
        val b = File(dossier, "b")
        assertTrue(LanceurPc.copieDonnees(a, b))
        assertEquals("callsign=s:F4IOZ", File(b, "reglages.properties").readText())
        assertTrue(File(b, "journal/2026/p.json").isFile)
        assertTrue("l'ancien dossier reste", File(a, "reglages.properties").isFile)
        // Never over a folder already filled, never into itself.
        assertFalse(LanceurPc.copieDonnees(a, b))
        assertFalse(LanceurPc.copieDonnees(a, File(a, "dedans")))
    }
}
