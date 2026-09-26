/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

/**
 * L'adresse d'un port série : quel appareil, et quel port dans cet appareil.
 *
 * La distinction n'est pas une coquetterie. « Sur le port USB du IC-9700 il y a
 * deux port com ou en plus une interface son » : une seule prise, un seul
 * appareil USB, mais deux ports série derrière — le A pour le CI-V, le B pour
 * les données — plus une carte son. Le pilote n'ouvrait jamais que le port 0 du
 * premier appareil reconnu. Quand le CI-V est sur le B, ou quand une clé SDR
 * s'est présentée avant le poste, on ouvrait un port bien réel qui ne répondait
 * simplement jamais.
 */
data class PortRef(val deviceIndex: Int, val portIndex: Int, val label: String)

/**
 * Le tri des ports à essayer, isolé de tout ce qui touche à Android pour
 * qu'il puisse être vérifié au banc.
 */
object CatScan {

    /**
     * L'ordre d'essai : le port choisi d'abord — c'est celui que l'opérateur a
     * désigné, on lui fait confiance —, puis ses frères du même appareil, puis
     * le reste.
     *
     * Les frères d'abord parce qu'ils sont, de très loin, les plus probables :
     * si l'opérateur a pointé le bon poste mais le mauvais des deux ports, la
     * bonne réponse est à un essai de là. Les autres appareils viennent après,
     * car ouvrir une clé SDR pour rien coûte une seconde de temporisation.
     */
    fun ordre(refs: List<PortRef>, choisi: Int): List<Int> {
        if (refs.isEmpty()) return emptyList()
        val c = choisi.coerceIn(0, refs.size - 1)
        val dev = refs[c].deviceIndex
        val freres = refs.indices.filter { it != c && refs[it].deviceIndex == dev }
        val autres = refs.indices.filter { it != c && refs[it].deviceIndex != dev }
        return listOf(c) + freres + autres
    }

    /**
     * L'étiquette montrée à l'opérateur : « IC-9700 · port A ».
     *
     * Le nom de produit quand il y en a un, sinon le chemin du noyau, qui est
     * laid mais qui a le mérite d'exister. La lettre n'apparaît que si
     * l'appareil expose vraiment plusieurs ports : sinon elle n'aiderait
     * personne à choisir.
     */
    fun etiquette(produit: String?, chemin: String, portIndex: Int, nbPorts: Int): String {
        val nom = produit?.trim().orEmpty().ifEmpty { chemin }
        return if (nbPorts <= 1) nom else nom + " \u00b7 port " + ('A' + portIndex)
    }
}
