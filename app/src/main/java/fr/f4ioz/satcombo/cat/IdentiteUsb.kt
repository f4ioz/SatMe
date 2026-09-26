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
 * Désigner un adaptateur série qui n'a pas de nom.
 *
 * Le défaut : `Ft817.open` retrouvait l'adaptateur **par son numéro de série
 * USB**, et refusait d'ouvrir quoi que ce soit quand ce numéro manquait. Le
 * commentaire d'origine disait « the adapter whose FTDI serial matches » — tout
 * est là. Un FTDI en porte toujours un ; un PL2303TA n'en porte aucun, et c'est
 * conforme à sa fiche : seuls les PL2303 récents (HXD, GC, GS) ont un
 * descripteur `iSerialNumber`. Le câble était donc reconnu par le pilote et
 * restait inutilisable, faute qu'on puisse le désigner.
 *
 * Ce fichier remplace le nom par une identité qui existe toujours, et — pour le
 * cas où deux câbles identiques sont branchés en duplex — par une méthode qui
 * ne se fie pas du tout à l'étiquette : on demande au poste sur quelle
 * fréquence il est, et sa réponse dit lequel est lequel.
 */
object IdentiteUsb {

    /**
     * La clé sous laquelle un adaptateur est mémorisé.
     *
     * Le numéro de série quand il existe — rien ne change alors pour un FTDI
     * déjà configuré, et les réglages enregistrés continuent de fonctionner.
     * Sinon, le couple constructeur/produit et la position dans l'arbre USB.
     *
     * Cette seconde forme est stable tant qu'on ne débranche rien, et peut
     * s'échanger entre deux branchements si deux câbles identiques sont
     * présents. C'est pourquoi elle ne suffit pas seule : elle sert à retrouver
     * un adaptateur, jamais à garantir qu'on tient le bon poste.
     */
    fun cle(serie: String?, vid: Int, pid: Int, position: String): String {
        val s = serie?.trim().orEmpty()
        return if (s.isNotEmpty()) s
        else "%04X:%04X@%s".format(vid, pid, position)
    }

    /** Cette clé désigne-t-elle un adaptateur dépourvu de numéro de série ? */
    fun sansNumeroDeSerie(cle: String): Boolean = cle.contains('@') && cle.contains(':')

    /**
     * Retrouve un adaptateur parmi les candidats.
     *
     * Deux règles, dans cet ordre. La clé exacte d'abord. Puis, **s'il n'y a
     * qu'un seul candidat, on le prend** : il n'y a rien à distinguer, et
     * refuser d'ouvrir le seul câble branché sous prétexte que sa position a
     * changé depuis la veille serait exactement le défaut qu'on corrige.
     */
    fun resout(cleMemorisee: String?, candidats: List<String>): String? {
        if (candidats.isEmpty()) return null
        if (cleMemorisee != null && candidats.contains(cleMemorisee)) return cleMemorisee
        if (candidats.size == 1) return candidats.first()
        return null
    }

    // ------------------------------------------- qui est en réception, qui en émission

    /**
     * Ce qu'un adaptateur a répondu quand on lui a demandé sa fréquence.
     * [freqHz] nul veut dire « rien de lisible » : pas de poste au bout, ou pas
     * la bonne vitesse.
     */
    class Sonde(val cle: String, val freqHz: Long?)

    /** L'attribution proposée. */
    class Attribution(val rx: String?, val tx: String?, val certaine: Boolean)

    /**
     * Attribue les adaptateurs d'après ce que les postes ont répondu.
     *
     * L'idée qui rend l'affaire simple : en duplex, les deux postes ne sont pas
     * sur la même bande. Celui de réception est sur la descente, celui
     * d'émission sur la montée, et l'application connaît déjà les deux bandes
     * par le transpondeur. La fréquence lue désigne donc le rôle sans ambiguïté,
     * là où une position dans l'arbre USB ne dit rien.
     *
     * [certaine] vaut faux quand les deux postes répondent dans la même bande ou
     * qu'un seul répond : on propose alors, on n'impose pas, et l'opérateur
     * tranche en voyant les fréquences lues — un discriminant qu'il comprend,
     * contrairement à « USB serial (1) » et « USB serial (2) ».
     */
    fun attribue(
        sondes: List<Sonde>,
        descenteHz: Long?,
        monteeHz: Long?,
        toleranceHz: Long = 2_000_000L,
    ): Attribution {
        val repondeurs = sondes.filter { it.freqHz != null }
        if (repondeurs.isEmpty()) return Attribution(null, null, false)

        if (descenteHz == null || monteeHz == null) {
            return Attribution(repondeurs.getOrNull(0)?.cle, repondeurs.getOrNull(1)?.cle, false)
        }

        fun pres(f: Long, cible: Long) = abs(f - cible) <= toleranceHz

        val versRx = repondeurs.filter { pres(it.freqHz!!, descenteHz) }
        val versTx = repondeurs.filter { pres(it.freqHz!!, monteeHz) }

        // Le cas net : un poste dans chaque bande, et ils diffèrent.
        if (versRx.size == 1 && versTx.size == 1 && versRx[0].cle != versTx[0].cle) {
            return Attribution(versRx[0].cle, versTx[0].cle, true)
        }

        // Sinon on propose sans prétendre : l'opérateur verra les fréquences.
        return Attribution(
            rx = versRx.firstOrNull()?.cle ?: repondeurs.getOrNull(0)?.cle,
            tx = versTx.firstOrNull()?.cle
                ?: repondeurs.firstOrNull { it.cle != versRx.firstOrNull()?.cle }?.cle,
            certaine = false)
    }

    /**
     * Une fréquence lue est-elle plausible pour un poste amateur portable ?
     *
     * Sert à distinguer une vraie réponse d'un octet de bruit interprété comme
     * une fréquence. Le FT-817 couvre de 100 kHz à 470 MHz ; au-delà de ces
     * bornes, ce n'est pas le poste qui a parlé.
     */
    fun freqPlausible(hz: Long): Boolean = hz in 100_000L..470_000_000L
}
