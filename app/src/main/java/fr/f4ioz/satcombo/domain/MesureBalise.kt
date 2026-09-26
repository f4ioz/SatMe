/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Retrouver une balise dans un spectre, et dire de combien elle a bougé.
 *
 * C'est la mesure la plus utile de toute la station QO-100, et elle tient en
 * deux nombres.
 *
 * **L'écart** est la dérive de l'oscillateur du convertisseur de descente, et
 * rien d'autre. La balise médiane est tenue au sol par une horloge : sa
 * fréquence dans le ciel est juste, définitivement. Tout ce qu'on mesure de
 * travers vient donc du LNB, qui part typiquement de plusieurs dizaines de
 * kilohertz à la mise sous tension puis se stabilise en une demi-heure. Sans
 * cette mesure on cherche ses correspondants à côté et on croit que le
 * transpondeur est vide.
 *
 * **Le rapport au plancher** est la qualité du pointage. La balise émet en
 * permanence à niveau constant : si le chiffre monte quand on tourne la
 * parabole d'un demi-degré, c'est que le demi-degré était bon. C'est le seul
 * indicateur qui permette de peaufiner un pointage tout seul, sans
 * correspondant en face pour dire « là c'est mieux ».
 *
 * ### Ce que cette pièce ne fait pas
 *
 * Elle ne sait ni d'où vient le spectre, ni ce qu'est un convertisseur, ni ce
 * qu'est une clé SDR. On lui donne un tableau de décibels et l'échelle qui va
 * avec, en fréquences du ciel, et elle rend une mesure. Cela la rend jugeable
 * au banc sur un spectre fabriqué à la main, ce qui est exactement ce qu'on
 * veut d'une pièce dont la sortie sert à corriger un étalonnage.
 */
object MesureBalise {

    /**
     * Ce qu'on a trouvé là où la balise devait être.
     *
     * Toutes les fréquences sont dans le ciel : l'appelant a déjà défait les
     * conversions, sans quoi l'écart mesuré n'aurait pas de sens.
     */
    data class Mesure(
        /**
         * Mesuré moins théorique, en hertz. Positif quand la balise est
         * entendue trop haut, c'est-à-dire quand l'oscillateur du
         * convertisseur est trop bas.
         */
        val ecartHz: Double,
        /** Le sommet de la raie, en dB pleine échelle. */
        val niveauDb: Float,
        /** Le bruit autour, en dB pleine échelle : la médiane de la fenêtre. */
        val plancherDb: Float,
    ) {
        /**
         * Ce qui dépasse du bruit, en décibels. C'est le chiffre à regarder
         * pendant qu'on tourne la parabole — l'autre, le niveau absolu, bouge
         * aussi avec le gain de la clé et ne veut donc rien dire tout seul.
         */
        val rapportDb: Float get() = niveauDb - plancherDb

        /** L'écart arrondi au hertz, pour l'écrire dans l'étalonnage. */
        val ecartArrondiHz: Long get() = Math.round(ecartHz)
    }

    /**
     * La balise médiane, mesurée dans [magDb].
     *
     * @param magDb puissance par raie, en dB pleine échelle, rangée de la
     *   fréquence la plus basse à la plus haute (spectre déjà recentré).
     * @param centreHz la fréquence du ciel qui tombe au milieu du tableau,
     *   c'est-à-dire à la raie `magDb.size / 2`.
     * @param etendueHz la largeur couverte par tout le tableau, en hertz du
     *   ciel. **Signée** : négative derrière un convertisseur à injection
     *   haute, qui retourne le spectre. C'est la seule façon de faire entrer
     *   un montage inverseur sans que cette pièce ait à savoir ce qu'est un
     *   convertisseur.
     * @param cibleHz où la balise devrait être, dans le ciel.
     * @param fenetreHz de combien on accepte de la chercher de part et
     *   d'autre. Vingt kilohertz par défaut : la dérive d'un LNB de
     *   télévision ordinaire à froid, et pas davantage — au-delà on
     *   attraperait la station SSB voisine plutôt que la balise.
     * @param seuilDb ce que la raie doit dépasser le plancher pour qu'on la
     *   déclare trouvée. En dessous, on rend `null` plutôt qu'un chiffre :
     *   une mesure de bruit écrite dans l'étalonnage ferait plus de dégâts
     *   qu'une absence de mesure.
     *
     * Rend `null` quand la fenêtre tombe hors du tableau, quand elle est trop
     * étroite pour qu'on y interpole quoi que ce soit, ou quand rien n'y
     * dépasse le bruit.
     */
    fun mesurer(
        magDb: FloatArray,
        centreHz: Double,
        etendueHz: Double,
        cibleHz: Double = Qo100.BALISE_MEDIANE_HZ.toDouble(),
        fenetreHz: Double = 20_000.0,
        seuilDb: Float = 6f,
    ): Mesure? {
        val n = magDb.size
        if (n < 8 || etendueHz == 0.0 || fenetreHz <= 0.0) return null

        val hzParRaie = etendueHz / n
        fun raie(f: Double): Double = n / 2.0 + (f - centreHz) / hzParRaie

        // Les deux bords de la fenêtre. Ils sont dans cet ordre-là en
        // fréquence, mais pas forcément en indice : derrière un inverseur, le
        // tableau est parcouru à l'envers.
        val a = raie(cibleHz - fenetreHz)
        val b = raie(cibleHz + fenetreHz)

        // On garde une raie de marge de chaque côté : l'interpolation
        // parabolique lit le voisin de gauche et celui de droite du sommet, et
        // un sommet collé au bord du tableau n'en a pas.
        val bas = max(1.0, ceil(min(a, b))).toInt()
        val haut = min((n - 2).toDouble(), floor(max(a, b))).toInt()
        if (haut - bas < 4) return null

        var sommet = bas
        var valeur = magDb[bas]
        for (i in bas..haut) {
            if (magDb[i] > valeur) { valeur = magDb[i]; sommet = i }
        }

        // Le plancher est la médiane de la fenêtre, pas sa moyenne. Une
        // moyenne se laisse tirer vers le haut par la balise elle-même et par
        // le premier correspondant qui passe ; la médiane, non, tant que le
        // signal n'occupe pas la moitié de la fenêtre — ce qu'une balise de
        // quelques centaines de hertz dans vingt kilohertz ne fait jamais.
        val copie = magDb.copyOfRange(bas, haut + 1)
        copie.sort()
        val plancher = copie[copie.size / 2]
        if (valeur - plancher < seuilDb) return null

        // Interpolation parabolique sur les trois raies du sommet. Sans elle
        // la mesure est quantifiée au pas de la FFT — soixante-cinq hertz sur
        // le panorama —, ce qui est déjà du même ordre que la dérive qu'on
        // cherche à suivre une fois le LNB chaud.
        val gauche = magDb[sommet - 1]
        val milieu = magDb[sommet]
        val droite = magDb[sommet + 1]
        val den = gauche - 2f * milieu + droite
        val d = if (den >= -1e-6f) 0.0 else (0.5 * (gauche - droite) / den)
        val fin = sommet + d.coerceIn(-0.5, 0.5)

        val mesuree = centreHz + (fin - n / 2.0) * hzParRaie
        return Mesure(
            ecartHz = mesuree - cibleHz,
            niveauDb = milieu,
            plancherDb = plancher,
        )
    }

    /**
     * Le calage est-il assez propre pour qu'on n'y touche plus ?
     *
     * Cent hertz sur une descente à 10 GHz, c'est un dixième de millionième :
     * bien au-delà de ce qu'un LNB à quartz ordinaire tient, mais c'est aussi
     * la largeur d'une note de télégraphie. En dessous, personne ne s'aperçoit
     * de rien à l'oreille, et il n'y a plus rien à gagner à corriger.
     */
    fun calageSuffisant(m: Mesure?, toleranceHz: Long = 100L): Boolean =
        m != null && abs(m.ecartHz) < toleranceHz
}
