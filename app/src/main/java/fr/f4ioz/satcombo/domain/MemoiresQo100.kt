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
 * Les raccourcis de fréquence du transpondeur étroit.
 *
 * Se poser au bon endroit dans 492 kHz est tout le travail sur QO-100 : le
 * satellite ne bouge pas, il n'y a pas de Doppler, et l'antenne est calée une
 * fois pour toutes. Reste à savoir où aller — et taper huit chiffres à chaque
 * fois pour retrouver la balise ou le coin FT8 n'est pas une façon de
 * travailler.
 *
 * Deux sortes de mémoires, et la distinction compte :
 *
 * - Les **repères du plan de bande**, déduits de [Qo100.SEGMENTS]. Ils ne se
 *   modifient pas et ne se suppriment pas : ce sont des faits publiés par
 *   AMSAT-DL, pas des préférences. Les laisser effaçables inviterait à
 *   reconstruire à la main ce que le domaine sait déjà.
 * - Les **mémoires de l'opérateur**, qu'il pose où il veut. Un rendez-vous
 *   hebdomadaire, le coin où l'on retrouve les copains, la fréquence d'un
 *   réseau.
 */
object MemoiresQo100 {

    /** Un raccourci. [cle] renvoie au libellé traduit pour les repères. */
    data class Memoire(
        val cle: String,
        val hz: Long,
        /** Vrai pour un repère du plan de bande, faux pour une mémoire posée. */
        val fixe: Boolean,
        /** Le nom donné par l'opérateur. Vide pour un repère. */
        val nom: String = "",
    ) {
        /** Ce qui s'affiche sur la touche. */
        fun libelle(traduit: (String) -> String): String =
            if (fixe) traduit("qo100_mem_$cle") else nom
    }

    /**
     * Les repères déduits du plan de bande.
     *
     * Un segment fournit un repère quand il en porte un explicitement — les
     * balises, la diffusion, l'urgence. Les autres donnent leur **début**,
     * qui est l'endroit où l'on entre dans le segment ; se poser au milieu
     * d'un segment n'aurait pas de sens, sa largeur n'ayant rien à voir avec
     * l'activité qu'on y trouve.
     */
    fun reperes(): List<Memoire> = Qo100.SEGMENTS.mapNotNull { s ->
        when {
            s.repereHz != null -> Memoire(s.cle, s.repereHz, fixe = true)
            // Les segments de travail : on entre par le bas, avec un petit
            // retrait pour ne pas se coller à la limite.
            s.usage == Qo100.Usage.CW ||
                s.usage == Qo100.Usage.NUMERIQUE ||
                s.usage == Qo100.Usage.PHONIE ||
                s.usage == Qo100.Usage.MIXTE ->
                Memoire(s.cle, s.basHz + 5_000L, fixe = true)
            else -> null
        }
    }

    /**
     * Toutes les mémoires, repères puis mémoires posées, dans l'ordre des
     * fréquences à l'intérieur de chaque groupe.
     */
    fun toutes(posees: List<Memoire>): List<Memoire> =
        reperes().sortedBy { it.hz } + posees.sortedBy { it.hz }

    /**
     * Range une mémoire nouvelle, ou remplace celle qui occupe déjà la place.
     *
     * **Deux mémoires à moins d'un kilohertz l'une de l'autre sont la même.**
     * Sur un transpondeur de 492 kHz avec des signaux de 2,7 kHz, deux
     * raccourcis distants de 300 Hz ne se distinguent pas à l'usage : ils
     * encombreraient la liste sans jamais rendre service.
     */
    fun pose(posees: List<Memoire>, hz: Long, nom: String): List<Memoire> {
        val propre = nom.trim().ifBlank { qoLibelleAuto(hz) }
        val sans = posees.filterNot { kotlin.math.abs(it.hz - hz) < 1_000L }
        return (sans + Memoire(cle = "", hz = hz, fixe = false, nom = propre))
            .sortedBy { it.hz }
    }

    fun retire(posees: List<Memoire>, hz: Long): List<Memoire> =
        posees.filterNot { it.hz == hz }

    /** Un nom par défaut quand l'opérateur n'en donne pas : « .688 ». */
    private fun qoLibelleAuto(hz: Long): String =
        "." + ((hz / 1_000L) % 1_000L).toString().padStart(3, '0')
}
