/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.diag.Plantage
import fr.f4ioz.satcombo.diag.PlantageDisque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le rapport de plantage est la seule chose qui parlera quand l'application se
 * taira. S'il est faux, il est pire qu'absent : on ira réparer ce qu'il montre
 * au lieu de ce qui casse. D'où ce banc.
 */
class PlantageTest {

    @get:Rule val dossier = TemporaryFolder()

    // ————— la mise en forme —————

    @Test
    fun le_rapport_nomme_la_panne_des_les_premieres_lignes() {
        val r = rapport(IllegalStateException("le mât ne répond pas"))
        assertTrue("la version manque", r.contains("SatMe 18.28 (1928)"))
        assertTrue("l'appareil manque", r.contains("Xiaomi 22041219NY"))
        assertTrue("l'exception manque", r.contains("IllegalStateException"))
        assertTrue("le message manque", r.contains("le mât ne répond pas"))
    }

    @Test
    fun la_cause_profonde_est_conservee() {
        val racine = java.io.IOException("tuyau rompu")
        val r = rapport(RuntimeException("échec de l'envoi", racine))
        assertTrue("la cause a été perdue", r.contains("Caused by"))
        assertTrue(r.contains("tuyau rompu"))
    }

    @Test
    fun la_ligne_des_modules_dit_aucun_quand_le_paquet_est_d_un_seul_bloc() {
        assertEquals("aucun", Plantage.modules(null))
        assertEquals("aucun", Plantage.modules(emptyArray()))
        assertEquals("aucun", Plantage.modules(arrayOf(null, "")))
    }

    @Test
    fun la_ligne_des_modules_les_nomme_quand_il_y_en_a() {
        assertEquals(
            "config.fr, config.arm64_v8a",
            Plantage.modules(arrayOf("config.fr", null, "config.arm64_v8a")))
    }

    @Test
    fun une_trace_trop_longue_est_coupee_et_le_dit() {
        val long = "x".repeat(Plantage.MAX + 500)
        val coupe = Plantage.tronque(long)
        assertTrue("la coupe n'est pas annoncée", coupe.contains("rapport coupé"))
        assertTrue("la coupe n'a pas eu lieu", coupe.length < long.length)
        assertTrue("le début a été jeté", coupe.startsWith("xxx"))
    }

    @Test
    fun une_trace_courte_n_est_pas_touchee() {
        assertEquals("court", Plantage.tronque("court"))
    }

    @Test
    fun le_mail_laisse_une_place_pour_ce_que_l_operateur_faisait() {
        val corps = Plantage.corpsDuMail("TRACE")
        assertTrue(corps.contains("Ce que je faisais"))
        assertTrue("la trace doit être dans le mail", corps.contains("TRACE"))
        assertTrue(
            "la place à remplir doit venir avant la trace",
            corps.indexOf("Ce que je faisais") < corps.indexOf("TRACE"))
    }

    // ————— le fichier —————

    @Test
    fun ce_qui_est_ecrit_se_relit() {
        val d = dossier.newFolder()
        assertTrue(PlantageDisque.ecrit(d, "le rapport"))
        assertEquals("le rapport", PlantageDisque.lit(d))
    }

    @Test
    fun sans_plantage_precedent_il_n_y_a_rien_a_lire() {
        assertNull(PlantageDisque.lit(dossier.newFolder()))
    }

    @Test
    fun un_rapport_vide_vaut_pas_de_rapport() {
        val d = dossier.newFolder()
        PlantageDisque.ecrit(d, "   \n  ")
        assertNull("une fenêtre vide vaudrait moins que rien", PlantageDisque.lit(d))
    }

    @Test
    fun le_rapport_efface_ne_revient_pas_au_lancement_suivant() {
        val d = dossier.newFolder()
        PlantageDisque.ecrit(d, "le rapport")
        assertTrue(PlantageDisque.efface(d))
        assertNull(PlantageDisque.lit(d))
    }

    @Test
    fun effacer_ce_qui_n_existe_pas_n_est_pas_un_echec() {
        assertTrue(PlantageDisque.efface(dossier.newFolder()))
    }

    @Test
    fun le_second_plantage_remplace_le_premier() {
        val d = dossier.newFolder()
        PlantageDisque.ecrit(d, "le premier")
        PlantageDisque.ecrit(d, "le second")
        assertEquals("le second", PlantageDisque.lit(d))
    }

    @Test
    fun un_dossier_absent_est_cree_plutot_que_de_perdre_le_rapport() {
        val d = java.io.File(dossier.newFolder(), "pas/encore/la")
        assertFalse(d.exists())
        assertTrue(PlantageDisque.ecrit(d, "le rapport"))
        assertEquals("le rapport", PlantageDisque.lit(d))
    }

    @Test
    fun ecrire_dans_un_endroit_impossible_echoue_sans_lever() {
        // Un fichier ordinaire là où l'on attend un dossier : l'écriture ne
        // peut pas aboutir. Elle doit rendre `false`, pas jeter une exception
        // par-dessus celle que l'on était en train de consigner.
        val f = dossier.newFile()
        assertFalse(PlantageDisque.ecrit(f, "le rapport"))
        assertNull(PlantageDisque.lit(f))
    }

    private fun rapport(t: Throwable) = Plantage.redige(
        t = t,
        version = "18.28",
        code = 1928,
        appareil = "Xiaomi 22041219NY (veux)",
        androidVersion = "13 — API 33",
        modules = "aucun",
        horodatage = "2026-08-02 21:14:33",
        fil = "main")
}
