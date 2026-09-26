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
 * L'écart propre de chaque appareil de réception, en parties par million.
 *
 * Olivier l'a constaté au terrain : la même mesure — fréquence lue sur un
 * WebSDR de référence, moins fréquence affichée par l'appareil — ne donne pas
 * le même oscillateur selon qu'on la fait au FT-817 ou à la clé SDR. Il n'y a
 * pourtant qu'un LNB.
 *
 * **La différence n'est pas dans le convertisseur, elle est dans l'appareil.**
 * Chaque récepteur a sa propre référence de fréquence, et elle se trompe : un
 * FT-817 de quelques parties par million, une clé SDR ordinaire de plusieurs
 * dizaines. Ranger ce total dans la chaîne de station confond deux erreurs
 * indépendantes, et corriger l'une déplace alors l'autre.
 *
 * **Pourquoi des ppm et non des hertz.** L'erreur d'un oscillateur est une
 * proportion, pas une constante. Un appareil à 2 ppm se trompe de 288 Hz à
 * 144 MHz et de 864 Hz à 432 MHz — trois fois plus. Ranger des hertz donnerait
 * une correction juste à la seule fréquence où on l'a mesurée, et fausse
 * partout ailleurs, y compris en changeant de convertisseur de descente.
 *
 * **Pourquoi cela n'a aucun effet sur les satellites à défilement.** L'écart
 * vaut zéro par défaut, et le FT-817 A est la référence par convention : tant
 * qu'aucune mesure n'a été faite, la correction est nulle et le comportement
 * inchangé. Quand elle existe, elle s'applique à l'appareil partout où il sert
 * — ce qui est juste, un quartz ne se trompe pas seulement sur QO-100.
 */
object MaterielRx {

    /**
     * Un appareil de réception et son écart.
     *
     * L'écart est celui **de l'appareil**, mesuré contre une référence. Positif
     * quand l'appareil affiche moins que la vérité, c'est-à-dire quand il faut
     * lui demander une fréquence plus haute pour tomber au bon endroit.
     */
    data class Materiel(
        val nom: String,
        val ppm: Double = 0.0,
        /** Vrai pour celui qui sert d'étalon, et dont l'écart reste nul. */
        val reference: Boolean = false,
    )

    /**
     * Ce qu'on propose au premier lancement.
     *
     * Le premier FT-817 est la référence : c'est le plus stable des trois, et
     * il faut bien un point fixe. Sans référence déclarée, une première mesure
     * ne peut pas séparer l'erreur du LNB de celle de l'appareil — les deux
     * termes s'additionnent et rien ne dit lequel vaut quoi.
     */
    fun parDefaut(): List<Materiel> = listOf(
        Materiel("FT-817 A", 0.0, reference = true),
        Materiel("FT-817 B"),
        Materiel("Clé SDR 1"),
        Materiel("Clé SDR 2"),
    )

    /**
     * Un écart plus grand que cela n'est pas un quartz, c'est une faute de
     * frappe.
     *
     * Cent parties par million valent 14 kHz à 144 MHz : au-delà, aucun
     * récepteur du commerce ne fonctionnerait, et laisser passer la valeur
     * déplacerait les fréquences sans que rien ne l'explique.
     */
    const val PPM_MAX = 100.0

    fun credible(ppm: Double): Boolean = ppm.isFinite() && kotlin.math.abs(ppm) <= PPM_MAX

    /**
     * Ce que l'appareil doit afficher pour être réellement sur [freqHz].
     *
     * La correction porte sur la fréquence que l'appareil accorde — la
     * fréquence intermédiaire derrière un convertisseur, et non celle du ciel.
     * C'est là que son quartz travaille, et c'est donc là que son erreur se
     * mesure.
     */
    fun corrige(freqHz: Long, ppm: Double): Long {
        if (!credible(ppm) || ppm == 0.0 || freqHz <= 0L) return freqHz
        return freqHz + Math.round(freqHz * ppm / 1_000_000.0)
    }

    /** L'inverse : ce que l'appareil affiche, ramené à la vérité. */
    fun redresse(afficheHz: Long, ppm: Double): Long {
        if (!credible(ppm) || ppm == 0.0 || afficheHz <= 0L) return afficheHz
        return Math.round(afficheHz / (1.0 + ppm / 1_000_000.0))
    }

    /**
     * L'écart déduit d'une mesure : fréquence vraie contre fréquence affichée.
     *
     * On donne la fréquence que l'appareil aurait dû afficher — celle de la
     * référence, ramenée en intermédiaire — et celle qu'il affiche réellement.
     * Rend `null` si la mesure est absurde : mieux vaut ne rien ranger que de
     * ranger un nombre qui déplacera tout.
     */
    fun ppmDepuisMesure(attenduHz: Long, luHz: Long): Double? {
        if (attenduHz <= 0L || luHz <= 0L) return null
        val ppm = (luHz - attenduHz) * 1_000_000.0 / attenduHz
        return if (credible(ppm)) ppm else null
    }

    /** Range la valeur sous ce nom, sans jamais toucher à la référence. */
    fun range(liste: List<Materiel>, nom: String, ppm: Double): List<Materiel> =
        liste.map {
            if (it.nom == nom && !it.reference) it.copy(ppm = ppm) else it
        }

    fun choisi(liste: List<Materiel>, nom: String): Materiel =
        liste.firstOrNull { it.nom == nom }
            ?: liste.firstOrNull { it.reference }
            ?: parDefaut().first()

    // ------------------------------------------------------------ persistance
    //
    // En texte simple, et non en JSON : le domaine ne doit dépendre de rien
    // d'Android, or `org.json` en vient. La règle vaut pour la portabilité,
    // mais elle s'est payée tout de suite — le banc tourne sur une machine
    // virtuelle où `org.json` est un simulacre vide, et la persistance y
    // échouait sans rien dire de la logique qu'elle était censée éprouver.
    //
    // Une ligne par appareil, trois champs séparés par une barre verticale.
    // Le nom est nettoyé de ce séparateur : un nom qui casserait le format
    // rendrait la liste illisible au démarrage suivant.

    private const val SEP = '|'

    fun ecrit(liste: List<Materiel>): String =
        liste.joinToString("\n") { m ->
            "${m.nom.replace(SEP, ' ').replace('\n', ' ')}$SEP${m.ppm}$SEP${m.reference}"
        }

    /** Une liste vide ou illisible rend celle par défaut : jamais rien. */
    fun lit(texte: String): List<Materiel> {
        val out = texte.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ligne ->
                val p = ligne.split(SEP)
                if (p.size < 3) return@mapNotNull null
                val nom = p[0].trim().ifBlank { return@mapNotNull null }
                val ppm = p[1].trim().toDoubleOrNull() ?: 0.0
                Materiel(
                    nom = nom,
                    // Une valeur abîmée ne doit pas déplacer les fréquences :
                    // dans le doute, zéro.
                    ppm = if (credible(ppm)) ppm else 0.0,
                    reference = p[2].trim().equals("true", ignoreCase = true))
            }
            .toList()
        return out.ifEmpty { parDefaut() }
    }
}
