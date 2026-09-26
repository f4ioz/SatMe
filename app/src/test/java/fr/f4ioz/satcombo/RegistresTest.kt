/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * La falaise des 255 registres.
 *
 * La 18.22 ne demarrait pas, et rien ne le disait : ni le compilateur, ni R8,
 * ni les 439 essais. La cause tenait a un detail du format dex. Un appel
 * `invoke-direct/range` code le nombre de registres qu'il transmet sur un
 * seul octet. Le constructeur de `UiState` en reclamait 259 ; D8 a ecrit
 * 259 et 0xFF, soit 3. L'instruction produite disait « passe trois
 * registres » a une methode qui en attendait deux cent cinquante-neuf. Le
 * verificateur d'ART refuse la classe au chargement, et l'application meurt a
 * la seconde ou le `MainViewModel` fabrique son premier etat.
 *
 * Aucun essai unitaire ordinaire ne pouvait le voir : ils tournent sur la JVM,
 * ou la limite n'existe pas, et aucun d'eux ne construisait `UiState`. Il faut
 * donc compter a la source, comme on compte les cles de traduction repetees.
 *
 * Le compte : un `Double` ou un `Long` non nul occupe deux registres, tout le
 * reste en occupe un. La methode la plus gourmande d'un `data class` n'est pas
 * le constructeur mais `copy$default`, qui recoit en plus l'instance, un
 * masque de bits par tranche de 32 parametres, et un marqueur. C'est elle qui
 * sert de mesure ici.
 *
 * Le seuil est volontairement bas. Repasser de 240 a 255 demande une quinzaine
 * de champs : cela laisse le temps de voir venir, et l'echec arrive au bon
 * moment — a l'essai, pas sur le telephone d'Olivier.
 */
class RegistresTest {

    /** Au-dela, le prochain champ ajoute peut rendre l'application inutilisable. */
    private val seuil = 240

    private fun racine(): File? = listOf(
        "src/main/java/fr/f4ioz/satcombo",
        "app/src/main/java/fr/f4ioz/satcombo",
        "../app/src/main/java/fr/f4ioz/satcombo")
        .map { File(it) }.firstOrNull { it.isDirectory }

    /** Le cout en registres d'un parametre : deux pour un Double/Long non nul. */
    private fun cout(type: String): Int =
        if (type == "Double" || type == "Long") 2 else 1

    private data class Mesure(val nom: String, val champs: Int, val registres: Int)

    /** Mesure tous les `data class` de premier niveau declares dans un fichier. */
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
            // copy$default : l'instance, les parametres, un masque par tranche
            // de 32, et le marqueur de fin.
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
     * Le lecteur de source voit-il bien la classe qui a coute la 18.22 ?
     *
     * Sans cette verification, une faute de frappe dans l'expression reguliere
     * rendrait l'essai precedent vert pour toujours — et parfaitement inutile.
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
