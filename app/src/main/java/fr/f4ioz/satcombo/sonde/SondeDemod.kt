/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.abs

/**
 * Du son démodulé en FM aux octets d'une radiosonde.
 *
 * La chaîne SDR rend déjà la sortie du discriminateur FM : une tension qui monte
 * quand la fréquence monte. Une modulation FSK à deux états y ressemble alors à
 * un carré bruité, et il ne reste que deux choses à faire : décider où est le
 * milieu (le zéro), et décider quand échantillonner (l'horloge).
 *
 * Le zéro est une moyenne glissante lente. Elle absorbe le décalage d'accord :
 * si l'opérateur est cinq cents hertz à côté, le carré est décentré, et sans
 * cette moyenne un bit sur deux serait faux. Lente exprès, pour qu'une longue
 * suite de bits identiques ne la déplace pas.
 *
 * L'horloge est un accumulateur de phase, avec un rattrapage à chaque
 * changement d'état. On ne dispose que de neuf échantillons par bit à 4800
 * bauds — et de quatre à peine à 9600 — donc rien de plus élaboré n'aurait de
 * sens : il faut un nombre fractionnaire d'échantillons par bit, et un
 * recalage doux sur les transitions.
 */
class SondeDemod(
    /** Débit d'échantillonnage du flux d'entrée, en hertz. */
    private val sampleRate: Double,
    /** Débit binaire attendu, en bauds. */
    private val baud: Double,
    /** Nombre d'octets conservés dans le tampon de recherche de trame. */
    bufferBytes: Int = 4096
) {

    /** Nombre d'échantillons par bit, en général pas entier. */
    val samplesPerBit: Double = sampleRate / baud

    /** Vrai si le débit d'échantillonnage est trop juste pour ce débit binaire. */
    val marginal: Boolean get() = samplesPerBit < 3.0

    private var dcLevel = 0.0
    private var smooth = 0.0
    private var amp = 1.0
    private var phase = 0.0
    private var last = 0
    private var haveLast = false
    private var corrected = false

    /** Bits reçus, un par octet, dans l'ordre d'arrivée. */
    private val chips = ByteArray(bufferBytes * 8 + 64)
    private var chipCount = 0

    /** Tampon d'octets reconstitués, poids faible en tête. */
    val bytes = ByteArray(bufferBytes)
    var byteCount = 0
        private set

    private var bitInByte = 0
    private var acc = 0

    /** Constante de la moyenne glissante qui suit le zéro du discriminateur. */
    private val dcAlpha = (baud / sampleRate / 400.0).coerceIn(1e-5, 1e-2)

    /**
     * Lissage d'entrée, de l'ordre de la largeur d'un symbole.
     *
     * C'est le filtre adapté du pauvre, et il change tout sur un signal réel :
     * sans lui, le souffle fait franchir le zéro plusieurs fois par symbole, et
     * chaque faux passage recale l'horloge un peu plus loin. Sur les
     * enregistrements de référence de radiosonde_auto_rx, la version 18.5
     * produisait quatre-vingt-dix-neuf virgule un pour cent des bits attendus —
     * un bit perdu sur cent, c'est-à-dire vingt-cinq bits perdus par trame de
     * trois cent vingt octets, donc aucune trame entière. Avec le lissage,
     * l'hystérésis et un seul recalage par symbole : cent virgule zéro zéro.
     */
    private val smoothK = (2.0 / samplesPerBit).coerceIn(0.05, 1.0)

    /** Suivi de l'amplitude, pour dimensionner l'hystérésis. */
    private val ampK = (dcAlpha * 20.0).coerceIn(1e-4, 0.2)

    /** Largeur de l'hystérésis, en fraction de l'amplitude observée. */
    private val hysteresis = 0.30

    fun reset() {
        dcLevel = 0.0
        smooth = 0.0
        amp = 1.0
        corrected = false
        phase = 0.0
        haveLast = false
        chipCount = 0
        byteCount = 0
        bitInByte = 0
        acc = 0
    }

    /**
     * Avale un bloc de son et produit des bits.
     *
     * Rend le nombre de bits produits. Les bits sont rangés dans [chipsOut]
     * quand il est fourni : c'est ce dont le décodage bi-phase a besoin, la
     * M10 travaillant sur les demi-bits.
     */
    fun feedBits(pcm: ShortArray, count: Int, chipsOut: ByteArray?): Int {
        var produced = 0
        for (k in 0 until count) {
            val x = pcm[k].toDouble()
            smooth += smoothK * (x - smooth)
            dcLevel += dcAlpha * (smooth - dcLevel)
            val d = smooth - dcLevel
            amp += ampK * (abs(d) - amp)
            val th = hysteresis * amp
            // Hystérésis : tant que le signal reste dans la bande morte, on
            // garde l'état précédent. Un souffle centré ne fabrique donc plus de
            // transitions.
            val bit = if (d > th) 1 else if (d < -th) 0 else last

            if (haveLast && bit != last && !corrected) {
                // Transition : on recale doucement l'horloge sur le milieu du
                // bit. Un recalage brutal ferait osciller la boucle sur le
                // bruit, un recalage nul la laisserait dériver. Un seul recalage
                // par symbole, sinon un front un peu mou en déclenche trois et
                // l'horloge prend du retard jusqu'à sauter un bit.
                phase += (samplesPerBit / 2.0 - phase) * 0.20
                corrected = true
            }
            last = bit
            haveLast = true

            phase += 1.0
            if (phase >= samplesPerBit) {
                phase -= samplesPerBit
                corrected = false
                if (chipsOut != null && produced < chipsOut.size) chipsOut[produced] = bit.toByte()
                if (chipCount < chips.size) chips[chipCount++] = bit.toByte()
                produced++
            }
        }
        return produced
    }

    /**
     * Assemble les bits accumulés en octets, poids faible en tête, en glissant
     * d'un bit à chaque appel infructueux : c'est l'appelant qui décide de
     * l'alignement en cherchant un en-tête.
     */
    fun packBytes(offsetBits: Int = 0): Int {
        byteCount = 0
        var k = offsetBits
        while (k + 8 <= chipCount && byteCount < bytes.size) {
            var v = 0
            for (j in 0 until 8) if (chips[k + j].toInt() != 0) v = v or (1 shl j)
            bytes[byteCount++] = v.toByte()
            k += 8
        }
        return byteCount
    }

    /** Assemble les bits en octets poids fort en tête (Meteomodem). */
    fun packBytesMsb(source: ByteArray, count: Int, offsetBits: Int = 0): Int {
        byteCount = 0
        var k = offsetBits
        while (k + 8 <= count && byteCount < bytes.size) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (source[k + j].toInt() and 1)
            bytes[byteCount++] = v.toByte()
            k += 8
        }
        return byteCount
    }

    /** Bits en attente. */
    val bitsAvailable: Int get() = chipCount

    /** Copie des bits accumulés, pour le décodage bi-phase. */
    fun chipsCopy(): ByteArray = chips.copyOf(chipCount)

    /**
     * Oublie les bits déjà consommés. On garde toujours de quoi contenir une
     * trame entière moins un bit, sinon une trame à cheval sur deux blocs
     * audio serait perdue à chaque fois.
     */
    fun consumeBits(n: Int) {
        val keep = chipCount - n
        if (keep <= 0) { chipCount = 0; return }
        System.arraycopy(chips, n, chips, 0, keep)
        chipCount = keep
    }

    /** Vide le tampon en gardant les [keep] derniers bits. */
    fun trimTo(keep: Int) {
        if (chipCount > keep) consumeBits(chipCount - keep)
    }

    /** Amplitude crête à crête vue par le discriminateur, indicateur de présence. */
    fun swing(pcm: ShortArray, count: Int): Int {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (k in 0 until count) {
            val v = pcm[k].toInt()
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (count == 0) 0 else abs(hi - lo)
    }
}
