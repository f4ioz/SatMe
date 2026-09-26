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
 * À quel profil de station rattacher un contact déposé.
 *
 * **Wavelog range un contact d'après son profil de station et ignore le
 * `MY_GRIDSQUARE` du fichier.** C'est le piège le plus coûteux du dépôt, et
 * il est silencieux : un opérateur qui active plusieurs carrés et n'envoie
 * que sur un profil voit toutes ses sorties portables reclassées sous le
 * carré de la maison. Rien ne le signale, et c'est le carré du profil qui
 * compte pour les diplômes — la perte ne se découvre qu'au moment de
 * réclamer un VUCC, des mois plus tard.
 *
 * Le carnet d'Olivier porte sept carrés — IN77US, IN77UT, IN95MP, JN06WG,
 * JN06XJ, JN16AJ, JN16CI — pour un seul indicatif de station. Sans cette
 * règle, six d'entre eux partiraient de travers.
 */
object ProfilsStation {

    /** Un emplacement de station tel que Wavelog le déclare. */
    data class Profil(
        val id: String,
        val carre: String,
        val indicatif: String,
        val nom: String = "",
    )

    /**
     * Le carré tronqué à la maille voulue.
     *
     * Quatre caractères correspondent à la maille du VUCC, qui se compte en
     * carrés « JN06 » et non « JN06XJ ». Six sépare les emplacements voisins
     * quand on tient à les distinguer.
     */
    fun carreCourt(carre: String, precision: Int = 4): String =
        carre.trim().uppercase().take(precision)

    /**
     * Les carrés que couvre un profil.
     *
     * Le champ carré d'un profil peut en porter **plusieurs, séparés par des
     * virgules** : c'est ainsi que Wavelog déclare une station posée sur une
     * ligne. Son import le sait — quand le champ contient une virgule, il
     * l'écrit dans `MY_VUCC_GRIDS` et non dans `MY_GRIDSQUARE`.
     */
    fun carresDuProfil(p: Profil, precision: Int = 4): Set<String> =
        p.carre.split(",").mapNotNull { g ->
            carreCourt(g, precision).ifBlank { null }
        }.toSet()

    /**
     * L'emplacement d'où un contact a été fait, comme ensemble de carrés.
     *
     * Sur une ligne, l'opérateur est **dans les deux carrés à la fois**, et
     * chaque contact vaut pour les deux. Ces contacts-là ne peuvent donc pas
     * partager le profil de ceux faits dans un seul des deux : ils leur
     * donneraient une double revendication à laquelle ils n'ont pas droit,
     * ou la retireraient à ceux qui l'ont.
     *
     * D'où un emplacement décrit par un ensemble : `{JN16}` et
     * `{JN16, JN06}` sont deux emplacements distincts, et c'est bien ce
     * qu'ils sont sur le terrain. L'ordre des carrés n'y change rien.
     */
    fun emplacement(monCarre: String, mesCarres: String, precision: Int = 4): Set<String> {
        val revendiques = mesCarres.split(",").mapNotNull { g ->
            carreCourt(g, precision).ifBlank { null }
        }
        return if (revendiques.size >= 2) revendiques.toSet()
        else setOf(carreCourt(monCarre, precision))
    }

    /** Un ensemble de carrés, écrit pour être lu : « JN06,JN16 ». */
    fun nomEmplacement(cle: Set<String>): String =
        cle.filter { it.isNotBlank() }.sorted().joinToString(",")
            .ifBlank { "—" }

    /**
     * À chaque emplacement, le profil qui le couvre **exactement**.
     *
     * L'égalité porte sur l'ensemble entier et non sur l'appartenance : un
     * profil déclaré « JN16,JN06 » ne convient pas à un contact fait dans le
     * seul JN16, puisqu'il lui donnerait une revendication double.
     */
    fun apparieEmplacements(
        cles: List<Set<String>>,
        profils: List<Profil>,
        indicatif: String,
        precision: Int = 4,
    ): Map<Set<String>, String?> {
        val ind = indicatif.trim().uppercase()
        return cles.associateWith { cle ->
            profils.firstOrNull { p ->
                carresDuProfil(p, precision) == cle &&
                    (ind.isEmpty() || p.indicatif.trim().uppercase() == ind)
            }?.id
        }
    }

    /**
     * Le profil où déposer un contact, ligne de carrés comprise.
     *
     * Sans table relevée, on retombe sur le profil unique des réglages.
     */
    fun profilPourEmplacement(
        monCarre: String,
        mesCarres: String,
        table: Map<Set<String>, String?>,
        defaut: String,
        precision: Int = 4,
    ): String = table[emplacement(monCarre, mesCarres, precision)] ?: defaut

    // Les anciennes règles par carré simple — `apparie`, `profilPour`,
    // `sansProfil` — ont été retirées plutôt que gardées à côté des
    // nouvelles. Deux règles qui répondent à la même question finissent par
    // diverger, et l'appelant choisit la mauvaise sans que rien ne le dise.
}
