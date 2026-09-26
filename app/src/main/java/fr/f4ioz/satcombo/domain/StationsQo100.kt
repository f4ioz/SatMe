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
 * Les chaînes de conversion de QO-100, et la façon de les étalonner.
 *
 * Ces réglages n'appartiennent qu'à QO-100 : aucun autre satellite ne demande
 * de convertisseur. Les ranger dans un menu général obligeait à sortir de
 * l'écran pour y revenir, et laissait croire qu'ils s'appliquaient partout.
 *
 * Deux idées portent ce fichier.
 *
 * **L'étalonnage se fait par deux fréquences lues, pas par un oscillateur
 * saisi.** Personne ne connaît l'OL de sa chaîne ; tout le monde sait lire ce
 * qu'affiche un WebSDR et ce qu'affiche son poste. La soustraction est le
 * travail de la machine.
 *
 * **Une station est un tout, et l'on en a plusieurs.** Le montage fixe et le
 * montage portable n'ont pas le même LNB, donc pas le même OL, donc pas le
 * même étalonnage. Confondre les deux ferait chercher ses correspondants à
 * côté chaque fois qu'on change de montage — et l'on croirait à une dérive.
 */
object StationsQo100 {

    /**
     * Une chaîne complète, nommée.
     *
     * [descenteOlHz] et [monteeOlHz] valent zéro quand l'étage n'existe pas :
     * une clé SDR branchée derrière le LNB n'a pas d'upconverter, et une
     * écoute directe en 10 GHz n'a pas de downconverter.
     */
    data class Station(
        val nom: String,
        val descenteOlHz: Long = 0L,
        val monteeOlHz: Long = 0L,
        /** Ce qu'on a mesuré, pour pouvoir le relire : ciel et poste. */
        val mesureCielHz: Long = 0L,
        val mesurePosteHz: Long = 0L,
    ) {
        val descenteReglee: Boolean get() = descenteOlHz > 0L
        val monteeReglee: Boolean get() = monteeOlHz > 0L

        /** La FI de descente pour une fréquence du ciel donnée. */
        fun posteRx(cielHz: Long): Long =
            if (descenteReglee) cielHz - descenteOlHz else cielHz

        /** La FI de montée pour une fréquence du ciel donnée. */
        fun posteTx(cielHz: Long): Long =
            if (monteeReglee) cielHz - monteeOlHz else cielHz
    }

    /** Ce que rend un étalonnage : l'oscillateur, ou la raison du refus. */
    sealed class Etalonnage {
        data class Trouve(val olHz: Long) : Etalonnage()
        /** [motif] est une clé de traduction, pas une phrase. */
        data class Refuse(val motif: String) : Etalonnage()
    }

    /** Les bornes du crédible pour un oscillateur de descente, en hertz. */
    private const val OL_MIN = 100_000_000L
    private const val OL_MAX = 12_000_000_000L

    /**
     * L'oscillateur local, déduit de deux fréquences lues.
     *
     * `OL = ciel − poste`. C'est tout, et c'est le calcul que fait l'opérateur
     * sur un coin de table — sauf qu'ici il ne se trompe pas de sens.
     *
     * Les deux gardes ne sont pas décoratives. **Intervertir les deux champs
     * est l'erreur naturelle** : on lit d'abord son poste, qui est devant soi,
     * puis le WebSDR. Une différence négative le trahit immédiatement, et le
     * dire vaut mieux que d'enregistrer un oscillateur absurde qui ne se
     * verrait qu'à la première écoute ratée.
     */
    fun etalonne(cielHz: Long, posteHz: Long): Etalonnage {
        if (cielHz <= 0L || posteHz <= 0L) return Etalonnage.Refuse("qo100_cal_vide")
        val ol = cielHz - posteHz
        if (ol <= 0L) return Etalonnage.Refuse("qo100_cal_inverse")
        if (ol < OL_MIN || ol > OL_MAX) return Etalonnage.Refuse("qo100_cal_absurde")
        return Etalonnage.Trouve(ol)
    }

    /**
     * L'écart entre l'oscillateur mesuré et sa valeur nominale, en hertz.
     *
     * C'est ce chiffre qui dit si la chaîne est saine. Quelques dizaines de
     * kilohertz sur un LNB de télévision sont normales — c'est son TCXO, et
     * un GPSDO n'y changera rien puisqu'il ne touche pas au LNB. Quelques
     * mégahertz désignent autre chose : mauvais LNB, mauvaise bande, ou champs
     * intervertis.
     */
    fun ecartAuNominal(olHz: Long, nominalHz: Long): Long = olHz - nominalHz

    /** Le même écart en parties par million, rapporté à l'oscillateur. */
    fun ecartPpm(olHz: Long, nominalHz: Long): Double =
        if (nominalHz <= 0L) 0.0
        else (olHz - nominalHz) * 1_000_000.0 / nominalHz

    /**
     * Les deux montages qu'on a par défaut.
     *
     * Ils ne portent aucun oscillateur : une station non étalonnée doit se
     * dire telle, plutôt que de proposer une valeur nominale qui aurait l'air
     * juste et ne le serait pas.
     */
    fun parDefaut(): List<Station> = listOf(
        Station(nom = "fixe"),
        Station(nom = "portable"),
    )

    /**
     * Range une station modifiée dans la liste, sans la réordonner.
     *
     * L'ordre est celui que l'opérateur voit ; le changer sous ses yeux parce
     * qu'il vient d'étalonner serait déroutant.
     */
    fun remplace(liste: List<Station>, index: Int, station: Station): List<Station> =
        if (index !in liste.indices) liste
        else liste.toMutableList().also { it[index] = station }
}
