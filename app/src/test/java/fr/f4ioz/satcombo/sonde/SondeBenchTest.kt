/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Le banc de mesure, et le garde-fou rapide qui l'accompagne.
 *
 * Les balayages complets prennent des minutes : ils ne tournent que sur demande,
 * par `-Dsatme.bench=1`, et ils impriment un tableau. Le seul essai qui tourne à
 * chaque compilation est le premier, qui vérifie en quelques secondes qu'une
 * sonde propre traverse bel et bien la chaîne radio complète — pas la mire
 * versée directement dans le décodeur, mais de l'IQ au format de la clé passé
 * par la décimation, le filtre de canal et le discriminateur.
 */
class SondeBenchTest {

    @After
    fun tearDown() {
        SondeHub.stop()
    }

    private fun benchEnabled(): Boolean =
        (System.getProperty("satme.bench") ?: "").isNotEmpty()

    @Test
    fun la_chaine_radio_complete_decode_une_rs41_propre() {
        val p = SondeBench.measure("RS41", ebn0Db = 25.0, seconds = 4)
        println("garde-fou : $p")
        assertTrue("aucune trame à travers la chaîne radio : $p", p.frames > 0)
    }

    @Test
    fun temoin_sans_radio() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== témoin : la mire versée directement dans le décodeur ===")
        for (model in listOf("RS41", "M20", "M10")) {
            println(SondeBench.measureAudio(model))
        }
    }

    @Test
    fun balayage_du_bruit() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== seuil de bruit, accord parfait ===")
        for (model in listOf("RS41", "M20", "M10")) {
            for (db in listOf(30.0, 20.0, 16.0, 14.0, 12.0, 10.0, 8.0, 6.0, 4.0)) {
                println(SondeBench.measure(model, ebn0Db = db, seconds = 6))
            }
        }
    }

    @Test
    fun balayage_du_desaccord() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== coût du désaccord, à bruit confortable ===")
        for (model in listOf("RS41", "M20")) {
            for (off in listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 5_000.0, 8_000.0)) {
                println(SondeBench.measure(model, ebn0Db = 16.0, seconds = 6, offsetHz = off))
            }
        }
    }

    @Test
    fun l_accord_automatique_rattrape_le_desaccord() {
        Assume.assumeTrue("banc désactivé (passer -Dsatme.bench=1)", benchEnabled())
        println("=== accord automatique par centre de gravité du spectre ===")
        for (off in listOf(0.0, 2_000.0, 5_000.0, 8_000.0)) {
            println("sans : " + SondeBench.measure("RS41", 14.0, 6, offsetHz = off))
            println("avec : " + SondeBench.measure("RS41", 14.0, 6, offsetHz = off, autoTune = true))
        }
    }
}
