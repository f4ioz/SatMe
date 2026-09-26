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
 * Ce qu'on rapatrie du carnet en ligne pour nourrir le clavier.
 *
 * L'API de Wavelog impose la forme de cette règle plus qu'on ne le voudrait :
 * son filtre `band` n'accepte **qu'une seule bande**, et il n'existe **aucun
 * filtre par mode**. Seul le cas satellite peut donc être trié par le serveur ;
 * tout le reste doit être rapatrié puis trié ici.
 *
 * Cette asymétrie n'est pas un détail d'implémentation. En satellite, le
 * serveur n'envoie que ce qui sert. Dans les autres cas il envoie le journal
 * entier, et c'est le téléphone qui écarte — donc une première moisson bien
 * plus lourde. L'écran doit le dire, faute de quoi l'opérateur croira à une
 * panne devant une attente qu'il n'a pas demandée.
 */
object FiltreMoisson {

    const val SAT = "sat"
    const val PHONIE_HF = "phonie"
    const val CW = "cw"
    const val TOUT = "tout"

    val toutes: List<String> = listOf(SAT, PHONIE_HF, CW, TOUT)

    /**
     * La bande à demander au serveur, ou `null` pour tout demander.
     *
     * Seul `SAT` est une valeur que le filtre de Wavelog comprend. « HF » n'en
     * est pas une — c'est une famille de bandes, et le filtre n'en prend
     * qu'une.
     */
    fun bandeServeur(filtre: String): String? = if (filtre == SAT) "SAT" else null

    /** Le tri se fait-il ici plutôt que chez le serveur ? */
    fun triLocal(filtre: String): Boolean = filtre != SAT && filtre != TOUT

    private val BANDES_HF = setOf(
        "160m", "80m", "60m", "40m", "30m", "20m",
        "17m", "15m", "12m", "10m",
    )

    /**
     * Les modes de phonie.
     *
     * Le but énoncé est d'écarter le numérique — FT8, FT4 et leurs cousins.
     * On reconnaît donc ce qui est de la voix et l'on rejette le reste, plutôt
     * que d'énumérer les modes numériques : leur liste s'allonge à chaque
     * mode nouveau, et un mode inconnu passerait au travers. Une liste
     * fermée du côté qu'on veut garder vieillit mieux qu'une liste ouverte
     * du côté qu'on veut exclure.
     */
    private val PHONIE = setOf("SSB", "USB", "LSB", "AM", "FM", "DIGITALVOICE")

    /**
     * Ce contact a-t-il sa place dans la mémoire du clavier ?
     *
     * `propMode`, `mode` et `bande` sont ceux de l'enregistrement ADIF, tels
     * quels. Un champ absent vaut chaîne vide, et ne doit jamais faire retenir
     * un contact par défaut : mieux vaut une mémoire un peu courte qu'une
     * mémoire pleine de ce qu'on avait demandé d'écarter.
     */
    fun retient(filtre: String, propMode: String, mode: String, bande: String): Boolean {
        val sat = propMode.trim().equals("SAT", ignoreCase = true)
        val m = mode.trim().uppercase()
        val b = bande.trim().lowercase()
        return when (filtre) {
            SAT -> sat
            // La phonie HF exclut le satellite : il a son propre choix, et
            // les mélanger priverait l'opérateur de la distinction qu'il a
            // justement demandée.
            PHONIE_HF -> !sat && b in BANDES_HF && m in PHONIE
            CW -> !sat && m == "CW"
            TOUT -> true
            else -> sat
        }
    }
}
