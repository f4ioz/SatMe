/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import fr.f4ioz.satcombo.Screen

/**
 * Ce que le bouton retour du téléphone doit fermer, et dans quel ordre.
 *
 * La règle est simple à énoncer et facile à enfreindre : **on ferme ce que
 * l'on voit**. L'ordre des branches doit donc être exactement celui du `when`
 * qui choisit l'écran à dessiner, sans quoi le retour agit sur une couche
 * cachée pendant que la couche visible reste à l'écran.
 *
 * C'est la panne du 2 août, et elle coûtait un passage entier. L'écran Rotor
 * se dessine par-dessus la fiche du satellite : `screen == ROTOR` gagne contre
 * `selected != null` au moment de dessiner. Mais le bouton retour, lui,
 * essayait `selected != null` en premier — il appelait donc `backToList()`,
 * qui vide la sélection, la position calculée, le tour de mât et la trace, et
 * arrête la poursuite. À l'écran, rien ne bougeait : l'écran Rotor était
 * toujours là, il affichait simplement « Aucun satellite suivi » et une
 * consigne vide, et le mât s'arrêtait de suivre. Un opérateur qui fait un
 * aller-retour entre la fiche et l'écran du mât — c'est-à-dire tout le monde,
 * pendant un réglage — perdait la poursuite à chaque retour, sans qu'aucun
 * message ne le dise.
 *
 * D'où cette fonction, séparée du composable pour qu'un essai puisse relire
 * l'ordre. Un ordre de branches ne se voit pas à la relecture ; il se voit
 * quand le mât s'arrête.
 */
object RetourArriere {

    /** Ce que le retour ferme. [RIEN] laisse le système quitter l'application. */
    enum class Geste {
        RIEN,
        SECTION_REGLAGES,
        FERMER_REGLAGES,
        FERMER_LOCATOR,
        FERMER_GLOBE,
        FERMER_SKED,
        FERMER_TIMELINE,
        FERMER_PHOTO,
        FERMER_ACTIVATION,
        FERMER_SSTV,
        FERMER_SDR,
        FERMER_APT,
        FERMER_SONDE,
        FERMER_ROTOR,
        FERMER_QO100,
        FERMER_NOMMAGE,
        FERMER_AGENDA,
        FERMER_FT8,
        RETOUR_LISTE,
        QUITTER_SELECTION,
    }

    /**
     * @param sectionReglages vrai quand une sous-section des réglages est
     *                        ouverte : le retour ferme la sous-section avant
     *                        les réglages eux-mêmes.
     * @param selection       vrai quand une fiche satellite est ouverte.
     * @param modeSelection   vrai en mode de sélection multiple dans la liste.
     */
    fun geste(
        ecran: Screen,
        sectionReglages: Boolean,
        selection: Boolean,
        modeSelection: Boolean
    ): Geste = when {
        // Les écrans plein cadre d'abord : ce sont eux qui sont dessinés.
        ecran == Screen.SETTINGS && sectionReglages -> Geste.SECTION_REGLAGES
        ecran == Screen.SETTINGS -> Geste.FERMER_REGLAGES
        ecran == Screen.FT8 -> Geste.FERMER_FT8
        ecran == Screen.GLOBE -> Geste.FERMER_GLOBE
        ecran == Screen.LOCATOR -> Geste.FERMER_LOCATOR
        ecran == Screen.SKED -> Geste.FERMER_SKED
        ecran == Screen.TIMELINE -> Geste.FERMER_TIMELINE
        ecran == Screen.PHOTO -> Geste.FERMER_PHOTO
        ecran == Screen.ACTIVATION -> Geste.FERMER_ACTIVATION
        ecran == Screen.SSTV -> Geste.FERMER_SSTV
        ecran == Screen.SDR -> Geste.FERMER_SDR
        ecran == Screen.APT -> Geste.FERMER_APT
        ecran == Screen.SONDE -> Geste.FERMER_SONDE
        ecran == Screen.ROTOR -> Geste.FERMER_ROTOR
        ecran == Screen.QO100 -> Geste.FERMER_QO100
        ecran == Screen.NOMMAGE -> Geste.FERMER_NOMMAGE
        ecran == Screen.AGENDA -> Geste.FERMER_AGENDA
        // Puis seulement la fiche, qui est dessous.
        selection -> Geste.RETOUR_LISTE
        modeSelection -> Geste.QUITTER_SELECTION
        else -> Geste.RIEN
    }

    /** Vrai quand le retour a quelque chose à fermer dans l'application. */
    fun intercepte(
        ecran: Screen,
        sectionReglages: Boolean,
        selection: Boolean,
        modeSelection: Boolean
    ): Boolean = geste(ecran, sectionReglages, selection, modeSelection) != Geste.RIEN
}
