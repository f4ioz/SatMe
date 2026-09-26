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
 * La molette USB de volume, détournée en commande de VFO.
 *
 * Ces molettes — AIMOS AM-U001 et ses cousines — ne sont pas des périphériques
 * exotiques : ce sont des claviers HID qui n'envoient que trois touches,
 * volume plus, volume moins, sourdine. Android les traite donc comme le
 * bouton de volume du téléphone, et tourner la molette change le son.
 *
 * Une application au premier plan peut intercepter ces touches. Tout le
 * problème est de ne le faire qu'à bon escient.
 */
object MoletteUsb {

    /**
     * Ce que la molette commande.
     *
     * Une molette seule ne sait régler qu'une chose à la fois. Avec un petit
     * clavier à trois touches, chacune choisit la cible et la molette reste la
     * même — c'est le geste d'un poste, où l'on presse une touche puis l'on
     * tourne, et non celui d'un écran où l'on cherche un champ.
     */
    enum class Cible { VFO, SHIFT_RX, SHIFT_TX }

    /**
     * Ce que fait l'appui sur la molette.
     *
     * Le poussoir est la seule commande du boîtier qu'on actionne sans quitter
     * la molette des doigts : elle mérite mieux qu'un usage figé. Trois choix,
     * et chacun répond à un moment du trafic :
     *
     * - **PAS** : dix, cent, mille hertz. C'est le geste d'un poste, et c'est
     *   le défaut.
     * - **CIBLE** : faire défiler RX, TX, VFO sans lâcher la molette — utile
     *   quand les trois touches servent à autre chose.
     * - **ZERO** : remettre à zéro le décalage en cours. Après une poursuite
     *   Doppler manuelle, c'est ce qu'on veut faire, et le chercher dans un
     *   menu pendant un passage n'est pas envisageable.
     */
    enum class Action { PAS, CIBLE, ZERO }

    /**
     * Les trois touches du boîtier et ce que chacune sélectionne.
     *
     * Les codes sont appris, pas saisis : personne ne connaît par cœur les
     * codes de touches d'Android, et un boîtier programmable peut envoyer à
     * peu près n'importe quoi. Zéro veut dire « pas encore apprise ».
     */
    data class Touches(
        val codeA: Int = 0, val cibleA: Cible = Cible.SHIFT_RX,
        val codeB: Int = 0, val cibleB: Cible = Cible.SHIFT_TX,
        val codeC: Int = 0, val cibleC: Cible = Cible.VFO,
        /**
         * Le poussoir de la molette.
         *
         * Il vaut « Sourdine » par défaut, parce que c'est ce qu'envoient la
         * plupart de ces boîtiers et que c'était le comportement d'avant.
         * L'apprendre autrement ne change qu'une valeur : il n'y a toujours
         * qu'une seule règle pour l'appui, et non un cas particulier greffé à
         * côté de l'ancien.
         */
        val codeD: Int = VOLUME_MUTE, val actionD: Action = Action.PAS
    ) {
        /** La cible que désigne ce code, ou `null` si ce n'est pas une des trois. */
        fun cibleDe(code: Int): Cible? = when {
            code == 0 -> null                 // jamais : une touche non apprise
            code == codeA -> cibleA           //           ne doit rien déclencher
            code == codeB -> cibleB
            code == codeC -> cibleC
            else -> null
        }
    }

    /** Ce que fait un cran de molette. */
    sealed interface Geste {
        /** Déplacer le VFO de tant de hertz, signé. */
        data class Bouge(val deltaHz: Long) : Geste
        /** Changer de pas. */
        data object ChangePas : Geste
        /** Passer à la cible suivante, sans lâcher la molette. */
        data object CibleSuivante : Geste
        /** Remettre à zéro le décalage en cours. */
        data object RemetZero : Geste
        /** Choisir ce que la molette commande désormais. */
        data class ChoisitCible(val cible: Cible) : Geste
        /** Rien : la touche ne nous concerne pas, le système la reprend. */
        data object Ignore : Geste
    }

    /** Les pas offerts, dans l'ordre où l'appui les fait défiler. */
    val PAS = listOf(10L, 100L, 1_000L)

    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val VOLUME_MUTE = 164

    /**
     * Ce qu'il faut faire de cette touche.
     *
     * [externe] dit si l'événement vient d'un appareil branché plutôt que du
     * téléphone lui-même. **C'est la garde qui rend la fonction acceptable** :
     * sans elle, activer la molette confisquerait les boutons de volume du
     * téléphone, et l'opérateur ne pourrait plus régler l'écoute — sur une
     * application de trafic, ce serait absurde.
     *
     * Android distingue les deux : la molette USB a son propre identifiant
     * d'appareil, différent de celui des touches physiques du téléphone.
     *
     * [actif] est le réglage. Fermé, tout passe au système : quelqu'un qui
     * n'a pas de molette ne doit rien perdre.
     */
    fun geste(
        codeTouche: Int,
        actif: Boolean,
        externe: Boolean,
        pasHz: Long,
        // Le porteur par défaut est un vrai porteur, non `null` : ainsi
        // l'appui sur la molette garde son sens même sans réglage fourni, et
        // il n'y a toujours qu'une seule règle pour lui.
        touches: Touches? = Touches()
    ): Geste {
        if (!actif || !externe) return Geste.Ignore
        // Les touches du boîtier d'abord : si l'opérateur a appris « volume
        // plus » sur une touche, c'est qu'il veut qu'elle choisisse une cible,
        // pas qu'elle tourne le VFO.
        touches?.cibleDe(codeTouche)?.let { return Geste.ChoisitCible(it) }
        // L'appui sur la molette, avant les crans : si l'opérateur l'a appris
        // sur « volume plus », c'est qu'il veut son action, pas un cran.
        if (touches != null && touches.codeD != 0 && codeTouche == touches.codeD) {
            return when (touches.actionD) {
                Action.PAS -> Geste.ChangePas
                Action.CIBLE -> Geste.CibleSuivante
                Action.ZERO -> Geste.RemetZero
            }
        }
        return when (codeTouche) {
            VOLUME_UP -> Geste.Bouge(pasHz)
            VOLUME_DOWN -> Geste.Bouge(-pasHz)
            else -> Geste.Ignore
        }
    }

    /**
     * Une touche est-elle apprenable ?
     *
     * On refuse le retour arrière et l'accueil : les confisquer laisserait
     * l'opérateur enfermé dans l'application sans moyen d'en sortir. Tout le
     * reste est permis, y compris les touches de volume — c'est même le cas
     * courant, un boîtier de macros n'ayant souvent que celles-là à offrir.
     */
    fun apprenable(codeTouche: Int): Boolean =
        codeTouche != 0 && codeTouche != 4 && codeTouche != 3 && codeTouche != 187

    /**
     * Le pas suivant, en boucle.
     *
     * Une valeur inconnue rend le premier pas plutôt que rien : un réglage
     * abîmé ne doit pas figer la molette sur un pas dont on ne peut plus
     * sortir.
     */
    /** La cible suivante, en boucle : RX, TX, VFO. */
    fun cibleSuivante(nom: String): String {
        val ordre = listOf(Cible.SHIFT_RX, Cible.SHIFT_TX, Cible.VFO)
        val i = ordre.indexOfFirst { it.name == nom }
        return if (i < 0) ordre.first().name else ordre[(i + 1) % ordre.size].name
    }

    fun pasSuivant(pasHz: Long): Long {
        val i = PAS.indexOf(pasHz)
        return if (i < 0) PAS.first() else PAS[(i + 1) % PAS.size]
    }
}
