/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The 255-register cliff. `invoke-direct/range` encodes its register count in
 * one byte: a `UiState` needing 259 became 3, ART rejected the class, and the
 * app died at startup with no warning from compiler, R8 or JVM tests. So we
 * count in the source.
 *
 * A non-null `Double`/`Long` takes two registers, anything else one. The
 * hungriest method is `copy$default` (plus instance, one mask per 32
 * parameters, a marker). The threshold is low on purpose, to fail in a test
 * rather than on a phone.
 */
class RegistresTest {

    /** Beyond this, the next field added may make the app unusable. */
    private val seuil = 240

    private fun racine(): File? = listOf(
        "src/main/java/fr/f4ioz/satcombo",
        "app/src/main/java/fr/f4ioz/satcombo",
        "../app/src/main/java/fr/f4ioz/satcombo")
        .map { File(it) }.firstOrNull { it.isDirectory }

    /** Register cost of a parameter: two for a non-null Double/Long. */
    private fun cout(type: String): Int =
        if (type == "Double" || type == "Long") 2 else 1

    private data class Mesure(val nom: String, val champs: Int, val registres: Int)

    /** Measures every top-level `data class` declared in a file. */
    private fun mesures(f: File): List<Mesure> {
        val lignes = f.readLines()
        val entete = Regex("^data class (\\w+)\\($")
        val param = Regex("^\\s{4}(?:val|var)\\s+\\w+\\s*:\\s*(.+)$")
        val out = ArrayList<Mesure>()
        var i = 0
        while (i < lignes.size) {
            val e = entete.find(lignes[i])
            if (e == null) { i++; continue }
            var n = 0
            var somme = 0
            var j = i + 1
            while (j < lignes.size && !lignes[j].startsWith(")")) {
                val p = param.find(lignes[j])
                if (p != null) {
                    val type = p.groupValues[1].substringBefore(" = ").trim().trimEnd(',')
                    n++
                    somme += cout(type)
                }
                j++
            }
            // copy$default: instance, parameters, one mask per 32, end marker.
            if (n > 0) out.add(Mesure(e.groupValues[1], n, 1 + somme + (n + 31) / 32 + 1))
            i = j + 1
        }
        return out
    }

    @Test
    fun aucun_data_class_ne_franchit_la_falaise_des_255_registres() {
        val racine = racine()
        assumeTrue("source introuvable depuis " + File(".").absolutePath, racine != null)

        val toutes = racine!!.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { mesures(it).asSequence() }.toList()
        assertTrue("aucun data class trouve : le lecteur de source est casse",
            toutes.size > 5)

        val trop = toutes.filter { it.registres > seuil }.sortedByDescending { it.registres }
        assertTrue(
            "au-dessus du seuil de " + seuil + " registres — la limite dure de Dalvik " +
                "est 255, et rien ne previent avant le plantage au demarrage :\n" +
                trop.joinToString("\n") {
                    "    " + it.nom + " : " + it.champs + " champs, " +
                        it.registres + " registres"
                },
            trop.isEmpty())
    }

    /**
     * Does the source reader actually see the classes that caused the crash?
     *
     * Without this, a regex typo would keep the previous test green forever,
     * and useless.
     */
    @Test
    fun le_compteur_voit_bien_UiState_et_RotorUi() {
        val racine = racine()
        assumeTrue("source introuvable", racine != null)
        val vm = File(racine, "MainViewModel.kt")
        assumeTrue("MainViewModel.kt introuvable", vm.isFile)

        val m = mesures(vm).associateBy { it.nom }
        assertTrue("UiState non vu par le compteur", m.containsKey("UiState"))
        assertTrue("RotorUi non vu par le compteur", m.containsKey("RotorUi"))
        assertTrue("UiState devrait avoir plus de 150 champs, vu " + m["UiState"]!!.champs,
            m["UiState"]!!.champs > 150)
        assertTrue("RotorUi devrait avoir plus de 40 champs, vu " + m["RotorUi"]!!.champs,
            m["RotorUi"]!!.champs > 40)
    }
}
