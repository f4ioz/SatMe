/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Fusionner deux carnets sans rien perdre.
 *
 * Olivier trafique avec deux téléphones. Il veut envoyer de l'un à l'autre les
 * contacts qu'il choisit, et qu'à la fin les deux disent la même chose. La
 * difficulté n'est pas le transport — un fichier suffit — mais ce qui se passe
 * quand une entrée est déjà là.
 *
 * **Une fusion n'écrase jamais.** C'est la règle, et elle n'a rien d'un excès
 * de prudence : le carnet est la seule donnée de l'application qui ne se
 * reconstitue pas. Un import qui remplace transforme une erreur de manipulation
 * — le mauvais fichier, le mauvais sens — en perte définitive. Un import qui
 * ajoute ne peut, au pire, que faire du bruit.
 *
 * De cette règle découlent trois comportements :
 *
 * - **Absent : on ajoute.** C'est le cas ordinaire.
 * - **Présent et incomplet : on complète.** Un champ vide localement et rempli
 *   en face se remplit. C'est ce qui permet de nommer un contact sur un
 *   téléphone et de retrouver le nom sur l'autre.
 * - **Présent et différent : on garde le local, et on le dit.** Deux valeurs
 *   contradictoires ne se départagent pas toutes seules : rien dans une entrée
 *   ne dit laquelle a été corrigée en dernier. Le compte des désaccords est
 *   rendu pour que l'opérateur aille voir.
 *
 * L'identité d'un contact est **l'heure à la seconde, l'indicatif et le
 * satellite**. Deux stations ne se travaillent pas deux fois dans la même
 * seconde sur le même satellite ; et l'heure seule ne suffirait pas, puisque
 * deux téléphones peuvent enregistrer des contacts différents au même instant.
 */
object FusionCarnet {

    /** Ce qu'il faut d'une entrée pour la fusionner. */
    data class Fiche(
        val timeMs: Long,
        val indicatif: String,
        val catnum: Int,
        /** Les champs remplissables, par nom. Un champ absent ou vide est un trou. */
        val champs: Map<String, String> = emptyMap(),
    )

    data class Bilan(
        val fondu: List<Fiche>,
        val ajoutes: Int,
        val completes: Int,
        val identiques: Int,
        val desaccords: Int,
    )

    /** L'identité d'un contact : l'heure à la seconde, l'indicatif, le satellite. */
    private fun cle(f: Fiche): Triple<Long, String, Int> =
        Triple(f.timeMs / 1000L, f.indicatif.trim().uppercase(), f.catnum)

    fun fusionne(local: List<Fiche>, entrant: List<Fiche>): Bilan {
        val parCle = LinkedHashMap<Triple<Long, String, Int>, Fiche>()
        local.forEach { parCle[cle(it)] = it }

        var ajoutes = 0
        var completes = 0
        var identiques = 0
        var desaccords = 0

        entrant.forEach { neuf ->
            val k = cle(neuf)
            val ancien = parCle[k]
            if (ancien == null) {
                parCle[k] = neuf
                ajoutes++
                return@forEach
            }
            var aComplete = false
            var aDesaccord = false
            val champs = ancien.champs.toMutableMap()
            neuf.champs.forEach { (nom, valeur) ->
                if (valeur.isBlank()) return@forEach
                val actuel = champs[nom].orEmpty()
                when {
                    actuel.isBlank() -> { champs[nom] = valeur; aComplete = true }
                    actuel != valeur -> aDesaccord = true
                }
            }
            if (aComplete) parCle[k] = ancien.copy(champs = champs)
            when {
                aComplete -> completes++
                aDesaccord -> Unit
                else -> identiques++
            }
            if (aDesaccord) desaccords++
        }

        return Bilan(parCle.values.sortedByDescending { it.timeMs },
            ajoutes, completes, identiques, desaccords)
    }
}
