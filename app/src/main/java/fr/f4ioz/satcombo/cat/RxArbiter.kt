/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

import kotlin.math.abs

/**
 * Qui, de l'opérateur ou du logiciel, tient le VFO de réception.
 *
 * Sur un transpondeur linéaire, les deux ont raison tour à tour. L'opérateur
 * cherche son correspondant : c'est lui qui décide où l'on écoute, et rien ne
 * doit lui reprendre la molette pendant qu'il la tourne. Puis il trouve, il
 * s'arrête, et à partir de cet instant la fréquence qu'il a choisie n'est plus
 * une fréquence de poste mais une fréquence de satellite : c'est au logiciel de
 * la maintenir, en descendant le VFO à mesure que le satellite s'éloigne. Sans
 * quoi le correspondant glisse hors du filtre en une minute, et l'opérateur
 * passe le passage à courir derrière lui.
 *
 * Jusqu'à la 18.17 la seconde moitié manquait : la réception était lue en
 * permanence et jamais écrite. Le repos étant recalculé à chaque lecture à
 * partir d'un VFO immobile, il dérivait de tout le Doppler — l'émission, elle,
 * bougeait, dans le mauvais sens et pour la mauvaise raison. C'est exactement
 * ce qu'Olivier a vu : « la fréquence poste ne bouge pas ».
 *
 * Toute la difficulté tient en une phrase : le poste ne dit pas *qui* a bougé
 * son VFO. Ce que l'on relit après avoir écrit ressemble trait pour trait à un
 * geste de l'opérateur. D'où la mémoire des dernières consignes : une lecture
 * qui retombe sur l'une d'elles est la nôtre, et ne rend la main à personne.
 *
 * Aucune ligne d'Android ici, et c'est voulu : l'arbitrage est la seule partie
 * de la chaîne CAT que l'on puisse juger sans radio branchée.
 */
class RxArbiter(
    /** En deçà, l'écart lu n'est pas un geste : c'est l'arrondi du poste. */
    private val moveHz: Long = 20L,
    /** Silence exigé après le dernier geste avant de reprendre la main. */
    private var holdMs: Long = 2_000L,
    /** Lectures tranquilles exigées en plus du silence. */
    private var stableSamples: Int = 8,
    /** Combien de consignes récentes restent reconnaissables. */
    private val memory: Int = 8
) {

    /** Vrai quand c'est le logiciel qui pose la fréquence de réception. */
    var driven: Boolean = true
        private set

    private val commands = ArrayList<Long>()
    private var lastObserved = 0L
    private var lastMoveMs = 0L
    private var stable = 0

    /** Combien de lectures tranquilles se sont enchaînées (pour l'affichage). */
    val stableCount: Int get() = stable

    /**
     * Change le délai de reprise en main, en cours de route.
     *
     * Les deux valeurs bougent **ensemble**, et c'est le point : le silence
     * exigé n'agit jamais seul, il est doublé d'un nombre de lectures
     * tranquilles. Régler le délai à une demi-seconde en laissant huit lectures
     * à attendre ne changerait rien du tout — selon la cadence d'interrogation
     * du poste, ces huit lectures durent souvent plus longtemps que le silence
     * lui-même. Un réglage sans effet est pire qu'un réglage absent : on le
     * tourne, on ne voit rien, et on cesse de croire à ce que dit l'écran.
     */
    fun regle(nouveauHoldMs: Long) {
        holdMs = nouveauHoldMs.coerceIn(200L, 5_000L)
        stableSamples = echantillonsPour(holdMs)
    }

    companion object {
        /**
         * Lectures tranquilles exigées pour un délai donné : une par quart de
         * seconde, jamais moins de deux.
         *
         * Deux au minimum parce qu'une seule lecture tranquille ne prouve rien
         * — c'est peut-être le creux entre deux crans de la molette.
         */
        fun echantillonsPour(holdMs: Long): Int =
            (holdMs / 250L).toInt().coerceAtLeast(2)
    }

    /** Retour à zéro : nouveau passage, nouveau satellite, ou lien rouvert. */
    fun reset() {
        // Vrai, et non faux. Après un changement de satellite, de transpondeur
        // ou une reconnexion, c'est **nous** qui savons où il faut être :
        // l'opérateur n'a encore rien touché. Partir en « il a la main »
        // faisait adopter la fréquence où traînait le poste au lieu d'imposer
        // le milieu de la bande passante qu'on venait de calculer.
        driven = true
        commands.clear()
        lastObserved = 0L
        lastMoveMs = 0L
        stable = 0
    }

    /**
     * Ce que nous venons d'écrire dans le poste ne doit pas nous surprendre
     * quand nous le relirons.
     */
    fun commanded(hz: Long) {
        commands += hz
        while (commands.size > memory) commands.removeAt(0)
    }

    /**
     * Une lecture du poste, datée.
     *
     * @return vrai si elle vient de la main de l'opérateur, c'est-à-dire si
     *   elle ne s'explique par aucune consigne que nous ayons donnée.
     */
    fun observe(hz: Long, nowMs: Long): Boolean {
        // La toute première lecture n'est pas un geste : personne n'a encore
        // rien touché depuis que nous nous sommes accrochés au poste.
        //
        // Elle laissait auparavant la main à l'opérateur (`driven = false`), et
        // c'était le défaut : à la connexion, le poste traîne où on l'avait
        // laissé, et l'application adoptait cette fréquence-là comme canal — au
        // lieu d'imposer le milieu de la bande passante qu'elle venait de
        // calculer pour le satellite choisi. On suivait avant d'avoir jamais
        // mené, et le satellite sélectionné n'y changeait rien.
        if (lastObserved == 0L) {
            lastObserved = hz
            lastMoveMs = nowMs
            stable = 0
            driven = true
            return false
        }
        val notre = commands.any { abs(hz - it) < moveHz }
        val bouge = !notre && abs(hz - lastObserved) >= moveHz
        if (bouge) {
            // L'opérateur reprend la molette : nos anciennes consignes ne
            // veulent plus rien dire, et masqueraient un accord lent qui
            // repasserait par l'une d'elles.
            driven = false
            stable = 0
            lastMoveMs = nowMs
            commands.clear()
        } else {
            stable++
        }
        lastObserved = hz
        if (!driven && stable >= stableSamples && nowMs - lastMoveMs >= holdMs) driven = true
        return bouge
    }
}
