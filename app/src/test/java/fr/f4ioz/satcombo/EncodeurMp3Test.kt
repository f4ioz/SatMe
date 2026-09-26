/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.audio.EncodeurMp3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Le portier de l'encodeur MP3.
 *
 * Ce qu'il empêche ne se voit pas sur un banc : la panne qu'il ferme est un
 * SIGSEGV dans du code natif, et aucun essai JVM ne chargera jamais
 * `libandroidlame.so`. On ne peut donc pas éprouver ici que l'encodeur ne
 * plante plus. On éprouve la seule chose dont dépend cette garantie — qu'à
 * aucun moment deux appelants ne se croient tous les deux autorisés à
 * l'utiliser, et qu'un tour pris finit toujours par être rendu.
 */
class EncodeurMp3Test {

    @Before fun avant() = EncodeurMp3.forceLibere()
    @After fun apres() = EncodeurMp3.forceLibere()

    @Test
    fun au_depart_personne_ne_le_tient() {
        assertTrue(EncodeurMp3.libre)
        assertNull(EncodeurMp3.occupePar)
    }

    @Test
    fun le_premier_le_prend_le_second_est_refuse() {
        assertTrue(EncodeurMp3.prend(EncodeurMp3.ENREGISTREUR))
        assertFalse(
            "deux tenants à la fois, c'est exactement le plantage à éviter",
            EncodeurMp3.prend(EncodeurMp3.MIRE_SSTV))
        assertEquals(EncodeurMp3.ENREGISTREUR, EncodeurMp3.occupePar)
    }

    @Test
    fun le_tour_rendu_est_repris_par_le_suivant() {
        assertTrue(EncodeurMp3.prend(EncodeurMp3.SDR))
        EncodeurMp3.rend(EncodeurMp3.SDR)
        assertTrue(EncodeurMp3.libre)
        assertTrue(EncodeurMp3.prend(EncodeurMp3.MIRE_SONDE))
        assertEquals(EncodeurMp3.MIRE_SONDE, EncodeurMp3.occupePar)
    }

    @Test
    fun rendre_ce_qu_on_ne_tient_pas_ne_libere_pas_le_tour_d_un_autre() {
        // Un `finally` en retard — celui d'un export qui s'est terminé il y a
        // longtemps — ne doit pas ouvrir la porte pendant qu'un enregistrement
        // encode. Ce serait la panne d'origine, reconstituée par la correction.
        assertTrue(EncodeurMp3.prend(EncodeurMp3.ENREGISTREUR))
        EncodeurMp3.rend(EncodeurMp3.MIRE_SSTV)
        assertEquals(EncodeurMp3.ENREGISTREUR, EncodeurMp3.occupePar)
        assertFalse(EncodeurMp3.prend(EncodeurMp3.SDR))
    }

    @Test
    fun avec_execute_le_bloc_puis_rend_le_tour() {
        val r = EncodeurMp3.avec(EncodeurMp3.MIRE_SSTV) {
            assertEquals(EncodeurMp3.MIRE_SSTV, EncodeurMp3.occupePar)
            "le fichier"
        }
        assertEquals("le fichier", r)
        assertTrue("le tour n'a pas été rendu", EncodeurMp3.libre)
    }

    @Test
    fun avec_rend_null_et_n_execute_rien_quand_c_est_occupe() {
        EncodeurMp3.prend(EncodeurMp3.SDR)
        var passe = false
        val r = EncodeurMp3.avec(EncodeurMp3.MIRE_SONDE) { passe = true; "le fichier" }
        assertNull("l'export aurait dû être refusé", r)
        assertFalse("le bloc n'aurait pas dû tourner du tout", passe)
        assertEquals("le tenant a été délogé", EncodeurMp3.SDR, EncodeurMp3.occupePar)
    }

    @Test
    fun une_exception_dans_le_bloc_ne_condamne_pas_l_encodeur() {
        // Sans le `finally`, un seul export raté rendrait tout enregistrement
        // impossible jusqu'au prochain démarrage de l'application.
        runCatching {
            EncodeurMp3.avec(EncodeurMp3.MIRE_SSTV) { throw IllegalStateException("disque plein") }
        }
        assertTrue("l'encodeur est resté verrouillé", EncodeurMp3.libre)
    }

    @Test
    fun un_seul_gagnant_quand_tout_le_monde_se_precipite() {
        // Le test et la prise doivent être indivisibles. S'ils ne l'étaient
        // pas, deux fils pourraient conclure ensemble que la place est libre —
        // et c'est précisément ainsi que le processus meurt.
        val fils = 24
        val depart = CountDownLatch(1)
        val fini = CountDownLatch(fils)
        val gagnants = AtomicInteger(0)
        repeat(fils) { i ->
            Thread {
                depart.await()
                if (EncodeurMp3.prend("candidat $i")) gagnants.incrementAndGet()
                fini.countDown()
            }.start()
        }
        depart.countDown()
        fini.await()
        assertEquals("un seul devait passer", 1, gagnants.get())
    }

    @Test
    fun le_tour_circule_sans_se_perdre_sous_la_bousculade() {
        // Chaque fil prend, travaille un instant, rend. À la fin, personne ne
        // doit tenir l'encodeur, et personne ne doit avoir travaillé pendant
        // qu'un autre travaillait.
        val fils = 16
        val dedans = AtomicInteger(0)
        val collisions = AtomicInteger(0)
        val servis = AtomicInteger(0)
        val fini = CountDownLatch(fils)
        repeat(fils) { i ->
            Thread {
                repeat(50) {
                    EncodeurMp3.avec("candidat $i") {
                        if (dedans.incrementAndGet() != 1) collisions.incrementAndGet()
                        servis.incrementAndGet()
                        dedans.decrementAndGet()
                    }
                }
                fini.countDown()
            }.start()
        }
        fini.await()
        assertEquals("deux encodages simultanés ont eu lieu", 0, collisions.get())
        assertTrue("personne n'a jamais pu encoder", servis.get() > 0)
        assertTrue("le tour s'est perdu en route", EncodeurMp3.libre)
    }
}
