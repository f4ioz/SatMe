/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.audio

/**
 * Le portier de l'encodeur MP3.
 *
 * ### Pourquoi ce fichier existe
 *
 * L'enveloppe Java de LAME que l'application embarque
 * (`com.naman14.androidlame.AndroidLame`) déclare **toutes** ses méthodes
 * natives en `static` : `initialize`, `lameEncode`, `lameFlush`, `lameClose`.
 * Derrière elles, une seule variable globale dans la bibliothèque native — le
 * symbole `glf` en zone `.bss` du `libandroidlame.so`. Autrement dit : peu
 * importe le nombre d'objets `AndroidLame` construits côté Kotlin, il n'y a
 * **qu'un seul encodeur dans tout le processus**.
 *
 * Deux utilisateurs à la fois, et la panne est silencieuse jusqu'à ce qu'elle
 * soit mortelle : le second `build()` réinitialise l'encodeur du premier, et le
 * premier `close()` libère celui du second. L'appel suivant à `encode()`
 * travaille alors sur une structure libérée, et le processus meurt dans
 * `lame_encode_mp3_frame` → `format_bitstream`, sans qu'une seule ligne de
 * Kotlin ne figure au sommet de la pile. C'est exactement la trace remontée par
 * le Play Console sur un Galaxy A35 : un plantage natif, que le ramasse-plantage
 * de la 18.28 ne peut pas attraper — un SIGSEGV ne passe jamais par
 * `Thread.setDefaultUncaughtExceptionHandler`.
 *
 * ### Ce que ce portier fait, et ce qu'il ne fait pas
 *
 * Il ne rend pas la bibliothèque réentrante : cela demanderait de la
 * recompiler avec un état par instance. Il garantit qu'un seul appelant la
 * tient à la fois.
 *
 * Et surtout il **refuse** le second au lieu de le faire attendre. C'est
 * délibéré : un enregistrement de passage tient l'encodeur dix minutes, une
 * réception SDR autant. Une fenêtre d'export bloquée dix minutes serait un
 * plantage de plus, simplement plus lent et plus difficile à raconter. Un refus
 * immédiat, lui, se dit en une phrase à l'écran.
 *
 * Ce fichier ne connaît pas Android et ne touche pas à LAME : il ne fait que
 * distribuer un droit de passage. C'est ce qui le rend vérifiable sur le banc
 * ordinaire, là où la bibliothèque native ne se charge pas.
 */
object EncodeurMp3 {

    /** Les tenants possibles, nommés une fois pour toutes. */
    const val ENREGISTREUR = "enregistrement"
    const val SDR = "SDR"
    const val MIRE_SSTV = "mire SSTV"
    const val MIRE_SONDE = "mire sonde"

    private val verrou = Any()
    private var occupant: String? = null

    /** Qui tient l'encodeur, ou `null` s'il est libre. */
    val occupePar: String? get() = synchronized(verrou) { occupant }

    /** Vrai si personne ne l'a pris. Indicatif seulement : voir [prend]. */
    val libre: Boolean get() = occupePar == null

    /**
     * Prend l'encodeur pour [qui], ou rend `false` s'il est déjà pris.
     *
     * Le test et la prise sont dans le même bloc synchronisé : deux appelants
     * simultanés ne peuvent pas conclure tous les deux qu'il est libre. C'est
     * tout l'intérêt de la manœuvre, et la raison pour laquelle [libre] ne doit
     * jamais servir à décider — seulement à renseigner.
     */
    fun prend(qui: String): Boolean = synchronized(verrou) {
        if (occupant != null) false else { occupant = qui; true }
    }

    fun rend(qui: String) = synchronized(verrou) {
        if (occupant == qui) occupant = null
    }

    /**
     * Exécute [bloc] avec l'encodeur, ou rend `null` sans rien faire s'il est
     * déjà pris.
     *
     * Pour les usages courts qui commencent et finissent au même endroit — les
     * exports de mire. L'enregistreur de passage et le SDR, eux, prennent
     * l'encodeur dans un fil et le rendent dans un autre bloc : ils appellent
     * [prend] et [rend] directement.
     *
     * La libération est dans un `finally` : une exception au milieu d'un export
     * ne doit pas condamner l'encodeur jusqu'au prochain démarrage de
     * l'application.
     */
    fun <T> avec(qui: String, bloc: () -> T): T? {
        if (!prend(qui)) return null
        return try { bloc() } finally { rend(qui) }
    }

    /**
     * Rend l'encodeur quel que soit son tenant.
     *
     * Réservé au banc d'essai : en production, un tenant qui ne rend pas son
     * tour est un défaut à corriger, pas à contourner.
     */
    internal fun forceLibere() = synchronized(verrou) { occupant = null }
}
