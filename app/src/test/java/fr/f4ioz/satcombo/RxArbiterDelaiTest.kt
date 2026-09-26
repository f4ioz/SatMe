/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.RxArbiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le banc du délai de reprise.
 *
 * Il garde une promesse simple : **le réglage doit se sentir**. Olivier a
 * réglé le délai, n'a vu aucune différence, et a conclu que l'option était
 * inopérante sur son IC-9700. Elle l'était — mais pas pour la raison qu'il
 * croyait : les arbitres repartaient à leurs deux secondes d'usine à chaque
 * lancement, parce que le réglage rangé n'était appliqué que depuis le
 * sélecteur.
 */
class RxArbiterDelaiTest {

    /** Fait tourner la molette, puis attend, et dit quand la main revient. */
    private fun msAvantReprise(delaiMs: Long, pasMs: Long = 100L): Long {
        val a = RxArbiter()
        a.regle(delaiMs)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, pasMs)      // un cran de molette
        assertFalse("la main doit passer à l'opérateur", a.driven)
        var t = pasMs
        repeat(60) {
            t += pasMs
            a.observe(435_001_000L, t)
            if (a.driven) return t - pasMs
        }
        return -1L
    }

    @Test
    fun un_delai_court_reprend_vraiment_plus_vite() {
        val court = msAvantReprise(250L)
        val long = msAvantReprise(2_000L)
        assertTrue("reprise à $court ms pour un délai de 250 ms", court in 1..800)
        assertTrue("reprise à $long ms pour un délai de 2 s", long >= 1_900)
        // Le cœur du banc : les deux réglages ne doivent pas se ressembler.
        assertTrue("le réglage ne se sent pas : $court contre $long", long > court * 2)
    }

    @Test
    fun le_delai_commande_aussi_le_nombre_dechantillons() {
        // Les deux doivent bouger ensemble. Un délai court avec huit
        // échantillons à attendre resterait long, et le réglage paraîtrait
        // sans effet — c'est exactement ce piège qu'on évite ici.
        val a = RxArbiter()
        a.regle(250L)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, 50L)
        var t = 50L
        var repris = false
        repeat(12) { t += 50L; a.observe(435_001_000L, t); if (a.driven) repris = true }
        assertTrue("pas de reprise après ${t} ms avec un délai de 250 ms", repris)
    }

    @Test
    fun un_delai_hors_bornes_ne_casse_rien() {
        // Ni zéro, qui reprendrait la main au milieu d'un geste, ni l'infini,
        // qui ne la rendrait jamais.
        val a = RxArbiter()
        a.regle(0L)
        a.observe(435_000_000L, 0L)
        a.observe(435_001_000L, 10L)
        assertFalse(a.driven)
        a.regle(99_000L)
    }

    @Test
    fun tant_que_la_molette_bouge_la_main_reste_a_loperateur() {
        val a = RxArbiter()
        a.regle(250L)
        var hz = 435_000_000L
        var t = 0L
        a.observe(hz, t)
        repeat(20) {
            t += 100L; hz -= 1_000L      // l'opérateur descend sans s'arrêter
            a.observe(hz, t)
        }
        assertFalse("la main ne doit pas être reprise pendant le geste", a.driven)
    }
}
