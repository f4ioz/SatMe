/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.audio

import fr.f4ioz.satcombo.sdr.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Analyseur de spectre de la voie basse fréquence.
 *
 * Rien à voir avec le panoramique de la clé SDR, qui regarde une bande de
 * radio : ici on regarde le son lui-même, celui qui part vers le fichier MP3.
 * Cela répond à une question qu'on se pose tout le temps pendant un passage —
 * « est-ce que ça entre vraiment ? » — sans avoir à décoller l'oreille du
 * haut-parleur, et cela montre du premier coup d'œil si la modulation est
 * centrée, si le souffle mange le signal, ou si les tonalités SSTV sont là.
 *
 * La classe est du Kotlin pur : pas un import Android, donc elle se vérifie à
 * l'établi. Elle emprunte la [Fft] de la chaîne SDR — une transformée de mille
 * points ne s'écrit pas deux fois dans la même application.
 *
 * Le signal est réel, donc seule la première moitié du spectre porte de
 * l'information : les raies au-delà de la moitié de la fréquence
 * d'échantillonnage sont l'image en miroir des premières. On les ignore.
 *
 * @param rate fréquence d'échantillonnage de la capture (44 100 Hz, ou
 *        16 000 Hz sur une liaison Bluetooth).
 * @param taille longueur de la fenêtre d'analyse, puissance de deux.
 * @param nbBandes nombre de barres affichées.
 * @param freqMaxHz haut de l'échelle affichée. 3 500 Hz couvre la parole, la
 *        BLU et les trois repères SSTV (1 200, 1 500 et 2 300 Hz) ; monter
 *        plus haut ne ferait qu'écraser tout ce qu'on veut lire.
 * @param plancherDb niveau qui correspond à une barre vide.
 */
class AnalyseurSpectre(
    val rate: Int = 44_100,
    val taille: Int = 1024,
    val nbBandes: Int = 32,
    val freqMaxHz: Double = 3_500.0,
    val plancherDb: Double = -78.0
) {

    init {
        require(taille > 1 && taille and (taille - 1) == 0) { "taille non puissance de deux : $taille" }
        require(nbBandes in 1..(taille / 2 - 1)) { "nombre de bandes hors limites : $nbBandes" }
        require(plancherDb < 0.0) { "plancher positif : $plancherDb" }
    }

    private val re = DoubleArray(taille)
    private val im = DoubleArray(taille)

    /** Fenêtre de Hann : sans elle une porteuse entre deux raies s'étale sur
     *  tout le spectre et l'affichage devient un mur uniforme. */
    private val fen = DoubleArray(taille) { 0.5 - 0.5 * cos(2.0 * PI * it / (taille - 1)) }

    /**
     * Bornes des bandes, en numéros de raies. La raie zéro est écartée : c'est
     * la composante continue, que tout étage d'entrée traîne un peu et qui
     * ferait une première barre toujours pleine.
     */
    private val bornes = IntArray(nbBandes + 1).also { b ->
        val haut = ((freqMaxHz * taille / rate).toInt()).coerceIn(nbBandes + 1, taille / 2)
        for (i in 0..nbBandes) b[i] = 1 + ((haut - 1).toLong() * i / nbBandes).toInt()
    }

    private var rempli = 0
    private var creteBrute = 0.0

    /** Niveau de chaque bande, de 0 (plancher) à 1 (pleine échelle). */
    val bandes = FloatArray(nbBandes)

    /** Crête du signal sur la dernière fenêtre, de 0 à 1. Sert de vumètre. */
    var crete: Float = 0f
        private set

    /** Nombre de fenêtres calculées depuis le dernier [reset]. */
    var trames: Long = 0
        private set

    /** Fréquence du milieu de la bande [i], en hertz. */
    fun centreHz(i: Int): Double = (bornes[i] + bornes[i + 1]) * 0.5 * rate / taille

    /** Numéro de la bande la plus forte, ou −1 si le spectre est vide. */
    fun bandeLaPlusForte(): Int {
        var meilleur = -1
        var val0 = 0f
        for (i in bandes.indices) if (bandes[i] > val0) { val0 = bandes[i]; meilleur = i }
        return meilleur
    }

    fun reset() {
        rempli = 0; creteBrute = 0.0; crete = 0f; trames = 0
        bandes.fill(0f)
    }

    /**
     * Empile [n] échantillons entiers signés. Renvoie vrai si au moins une
     * fenêtre vient d'être calculée, auquel cas [bandes] et [crete] sont à jour.
     */
    fun pousser(pcm: ShortArray, n: Int): Boolean {
        var fait = false
        var k = 0
        val fin = minOf(n, pcm.size)
        while (k < fin) {
            val prend = minOf(taille - rempli, fin - k)
            for (t in 0 until prend) {
                val e = pcm[k + t] / 32768.0
                re[rempli + t] = e
                val a = abs(e)
                if (a > creteBrute) creteBrute = a
            }
            rempli += prend
            k += prend
            if (rempli == taille) { calculer(); rempli = 0; fait = true }
        }
        return fait
    }

    private fun calculer() {
        for (i in 0 until taille) { re[i] *= fen[i]; im[i] = 0.0 }
        Fft.transform(re, im)
        // Deux facteurs : la moitié de l'énergie d'un signal réel part dans les
        // fréquences négatives qu'on n'affiche pas, et la fenêtre de Hann a un
        // gain moyen de 0,5. Une sinusoïde pleine échelle rend ainsi 0 dB.
        val norm = 2.0 / (taille * 0.5)
        for (b in 0 until nbBandes) {
            var max = 0.0
            for (i in bornes[b] until bornes[b + 1]) {
                val m = sqrt(re[i] * re[i] + im[i] * im[i]) * norm
                if (m > max) max = m
            }
            val db = if (max <= 1e-9) -140.0 else 20.0 * log10(max)
            bandes[b] = ((db - plancherDb) / -plancherDb).toFloat().coerceIn(0f, 1f)
        }
        crete = creteBrute.toFloat().coerceIn(0f, 1f)
        creteBrute = 0.0
        trames++
    }
}
