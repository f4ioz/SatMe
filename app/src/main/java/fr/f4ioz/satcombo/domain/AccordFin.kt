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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * L'accord fin au doigt.
 *
 * Le défaut que ceci corrige se chiffre. La réglette de QO-100 étale les
 * 490 kHz du transpondeur étroit sur la largeur de l'écran, et le doigt y
 * désigne une position **absolue** : environ 1,4 kHz par dp, soit ±12 kHz sous
 * une pulpe de doigt. En bande latérale unique, un QSO se cale à 50 Hz près
 * avant que les voix ne deviennent des canards. Le geste est donc deux ordres
 * de grandeur trop grossier — ce n'est pas un accord, c'est une indication de
 * direction. Restaient les boutons ±100 Hz, et vingt appuis pour parcourir
 * deux kilohertz.
 *
 * Trois réponses, qui ne se remplacent pas :
 *
 *  - la **loupe** montre. Une seconde vue du spectre, large de quelques
 *    kilohertz seulement, où l'on voit les deux flancs de la bande latérale
 *    du correspondant et où l'on se pose dessus. Elle ne coûte aucun calcul
 *    nouveau : l'analyseur rend déjà 4096 raies sur 176 400 Hz, soit 43 Hz par
 *    raie, et c'est l'affichage qui jetait cette finesse.
 *  - le **vernier** déplace. Une molette déroulée : le doigt ne désigne plus
 *    une fréquence, il la pousse, à raison de tant de hertz par centimètre de
 *    glissement. Le doigt ne masque plus le signal qu'on vise, et le rapport
 *    se choisit.
 *  - le **calage** décide. En bande latérale, la vraie question n'est pas
 *    « quelle fréquence » mais « est-ce que la voix sonne juste » ; un
 *    centre de gravité du spectre reçu, posé au bon endroit de la bande audio,
 *    répond mieux que n'importe quel doigt.
 *
 * Ce fichier ne contient que la troisième : l'arithmétique. Elle se vérifie au
 * banc, contrairement à un geste.
 */
object AccordFin {

    // ---------------------------------------------------------------- loupe

    /**
     * Largeurs de loupe proposées, en hertz.
     *
     * La plus étroite fait trois kilohertz et non deux : un canal en bande
     * latérale en occupe 2,4, et une loupe plus serrée que le signal qu'elle
     * doit montrer coupe les deux flancs qu'on est venu regarder. Trois
     * kilohertz laissent le canal entier plus une marge de part et d'autre,
     * qui est exactement ce qu'il faut pour voir de quel côté on est décalé.
     */
    val LOUPES: List<Int> = listOf(3_000, 5_000, 10_000, 20_000)

    /**
     * Finesse réellement obtenue par la loupe, en hertz par dp d'écran.
     *
     * Sert surtout à répondre honnêtement à la question « est-ce que ça suffit
     * pour de la BLU ». Sur 360 dp de large, une loupe de 5 kHz donne 14 Hz par
     * dp : la pulpe du doigt couvre alors moins de 150 Hz, contre 12 kHz sur la
     * réglette entière.
     */
    fun hzParDp(fenetreHz: Int, largeurDp: Float): Double =
        if (largeurDp <= 0f) 0.0 else fenetreHz / largeurDp.toDouble()

    // -------------------------------------------------------------- vernier

    /**
     * Rapports du vernier, en hertz par centimètre de glissement.
     *
     * Le centimètre, et non le dp ni le pixel : c'est la seule unité qui rende
     * le même geste sur une tablette de 10 pouces et sur un téléphone. Un
     * rapport exprimé en pixels ferait un vernier deux fois plus rapide sur
     * l'écran deux fois plus dense, ce qui n'a aucun sens pour la main.
     *
     * Les trois valeurs couvrent les trois gestes réels : se poser au hertz
     * près sur une porteuse, remonter un QSO qu'on entend à côté, traverser un
     * bout de bande.
     */
    val RAPPORTS: List<Int> = listOf(20, 200, 2_000)

    /** Conversion d'un rapport en hertz par pixel, connaissant la densité. */
    fun hzParPixel(hzParCm: Int, dpi: Float): Double =
        if (dpi <= 0f) 0.0 else hzParCm * 2.54 / dpi

    /**
     * Le sens du geste : on glisse le **cadran**, pas l'aiguille.
     *
     * Tirer vers la gauche fait donc monter en fréquence, comme sur toute liste
     * qu'on fait défiler et comme sur la cascade d'un récepteur à écran. Le
     * signe est écrit ici une fois pour toutes plutôt qu'au fil des gestes, où
     * il finit toujours par différer d'un widget à l'autre.
     */
    fun deltaHz(dxPixels: Float, hzParPixel: Double): Double = -dxPixels * hzParPixel

    /**
     * L'accumulateur de restes.
     *
     * Au rapport le plus fin, un pixel vaut moins d'un hertz. Arrondir chaque
     * événement de glissement à l'entier rendrait alors zéro à chaque fois, et
     * le vernier serait mort précisément là où on en a le plus besoin : un
     * glissement lent ne produirait rien du tout. On garde donc la fraction
     * d'un événement au suivant.
     *
     * Le seuil de dix hertz de [DopplerTuner] impose la même prudence en aval,
     * mais lui filtre des consignes ; ici on filtrerait de l'intention.
     */
    class Aiguille {
        private var reste = 0.0

        /** Rend le nombre entier de hertz à appliquer, et garde la fraction. */
        fun pousse(dxPixels: Float, hzParPixel: Double): Long {
            reste += deltaHz(dxPixels, hzParPixel)
            val entier = if (reste >= 0) floor(reste).toLong() else -floor(-reste).toLong()
            reste -= entier
            return entier
        }

        fun oublie() { reste = 0.0 }
    }

    /**
     * Vitesse restante d'un lancer, [t] secondes après le lâcher.
     *
     * Décroissance exponentielle de constante [TAU]. Un lancer sert à traverser
     * quelques kilohertz sans vingt gestes ; au-delà de la vitesse d'arrêt on
     * s'arrête net, faute de quoi le vernier continue de ramper pendant
     * plusieurs secondes et l'opérateur ne sait plus où il en est.
     */
    fun vitesseApres(v0: Float, t: Double, tau: Double = TAU): Float =
        if (t < 0) v0 else (v0 * exp(-t / tau)).toFloat()

    /** Distance totale d'un lancer laissé libre, en pixels. */
    fun parcoursTotal(v0: Float, tau: Double = TAU): Double = v0 * tau

    const val TAU: Double = 0.32
    const val VITESSE_ARRET: Float = 24f

    /**
     * Pas de graduation à afficher pour un rapport donné.
     *
     * On cherche le plus petit pas de la suite 1-2-5 qui laisse au moins
     * [ecartMinPx] pixels entre deux traits. Un cadran plus serré que cela ne
     * se lit pas et scintille au défilement.
     */
    fun pasGraduation(hzParPixel: Double, ecartMinPx: Double = 14.0): Long {
        if (hzParPixel <= 0.0) return 1L
        val minimumHz = ecartMinPx * hzParPixel
        var decade = 1L
        while (decade <= 100_000_000L) {
            for (m in longArrayOf(1L, 2L, 5L)) {
                val pas = decade * m
                if (pas >= minimumHz) return pas
            }
            decade *= 10L
        }
        return decade
    }

    /** Un trait du cadran : sa position et ce qu'il vaut. */
    class Trait(val xPixels: Float, val hz: Long, val majeur: Boolean)

    /**
     * Les traits visibles d'un cadran centré sur [centreHz].
     *
     * Un trait sur cinq est majeur et porte son chiffre. On borne le nombre de
     * traits : au rapport le plus grossier sur un écran large, un pas mal
     * choisi en produirait des milliers, et la boucle de dessin s'en
     * apercevrait.
     */
    fun traits(
        centreHz: Long,
        hzParPixel: Double,
        largeurPixels: Float,
        pas: Long = pasGraduation(hzParPixel),
        maxTraits: Int = 400
    ): List<Trait> {
        if (hzParPixel <= 0.0 || largeurPixels <= 0f || pas <= 0L) return emptyList()
        val demi = largeurPixels / 2.0
        val etendue = demi * hzParPixel
        val bas = centreHz - etendue
        val haut = centreHz + etendue
        val premier = floor(bas / pas).toLong() * pas
        val out = ArrayList<Trait>()
        var f = premier
        while (f <= haut && out.size < maxTraits) {
            if (f >= bas) {
                val x = (demi + (f - centreHz) / hzParPixel).toFloat()
                out.add(Trait(x, f, Math.floorMod(f / pas, 5L) == 0L))
            }
            f += pas
        }
        return out
    }

    // --------------------------------------------------------------- calage

    /**
     * Où poser le centre de gravité de la voix reçue, en hertz audio.
     *
     * Une voix passe entre 300 et 2 700 hertz ; son centre de gravité tombe
     * vers 1 500. En bande latérale supérieure, l'accord doit donc se placer
     * 1 500 hertz **sous** ce centre de gravité pour que la voix retombe dans
     * la bande passante ; en inférieure, au-dessus, le spectre étant retourné.
     *
     * En FM ou en AM la question ne se pose pas : la porteuse est au milieu, et
     * la cible est zéro. Rendre zéro plutôt que de refuser évite d'avoir à se
     * demander, au point d'appel, si le mode s'y prête.
     */
    fun cibleVoixHz(mode: String): Int = when (mode.uppercase()) {
        "USB" -> 1_500
        "LSB" -> -1_500
        else -> 0
    }

    /** Le calage a-t-il un sens dans ce mode ? */
    fun calageUtile(mode: String): Boolean = cibleVoixHz(mode) != 0

    /**
     * Demi-largeur de recherche du centre de gravité, en hertz.
     *
     * Étroite, et c'est tout l'intérêt. Le recentrage automatique existant
     * ratisse ±25 kHz parce qu'il cherche une radiosonde dans une bande vide ;
     * ici on cale sur le correspondant qu'on écoute **déjà**, et ratisser large
     * ferait sauter sur la station voisine plus forte au premier silence. Trois
     * kilohertz, c'est un canal BLU et rien d'autre.
     */
    const val RECHERCHE_VOIX_HZ: Double = 3_000.0

    /**
     * Accord visé, en hertz relatifs à l'accord de la clé.
     *
     * [centreGraviteHz] est le centre de gravité mesuré, lui aussi relatif à
     * l'accord ; on se place à [cibleHz] en dessous pour que la voix tombe au
     * bon endroit de la bande audio.
     */
    fun accordVise(centreGraviteHz: Double, cibleHz: Int): Long =
        (centreGraviteHz - cibleHz).roundToLong()

    // ------------------------------------------------ ce que le vernier pilote

    /**
     * Ce que le vernier déplace, selon le satellite en cours.
     *
     * La distinction est le cœur du correctif. Sur un transpondeur, la
     * grandeur qui compte est le **canal** — le point d'écoute dans la bande
     * passante — parce que c'est lui qui se reflète en émission. Sur un
     * transpondeur inverse, monter de deux kilohertz en réception fait
     * descendre de deux kilohertz en émission, et l'on reste sur son
     * correspondant. Déplacer un simple décalage d'écoute ferait glisser la
     * réception sans bouger l'émission : on perdrait le QSO en croyant le
     * suivre.
     *
     * Sur un canal fixe — de la FM, pas de bande à parcourir — il n'y a rien à
     * déplacer d'autre que l'accord de la clé.
     */
    enum class Cible { CANAL, CLE }

    fun cibleDuVernier(estTranspondeur: Boolean, basHz: Long?, hautHz: Long?): Cible =
        if (estTranspondeur && basHz != null && hautHz != null && basHz != hautHz) Cible.CANAL
        else Cible.CLE

    /**
     * Le canal déplacé, borné à la bande passante.
     *
     * On borne, on ne boucle pas. Un vernier qui repasserait de l'autre côté
     * après le bord ferait sauter d'un bout à l'autre du transpondeur au
     * milieu d'un contact — et le lancer, qui peut parcourir plusieurs
     * kilohertz d'un geste, rendrait l'accident fréquent.
     */
    fun nouveauCanal(actuelHz: Long, deltaHz: Long, basHz: Long, hautHz: Long): Long =
        (actuelHz + deltaHz).coerceIn(minOf(basHz, hautHz), maxOf(basHz, hautHz))

    /**
     * Un déplacement mérite-t-il d'être envoyé à la clé ?
     *
     * Sous le seuil du planificateur Doppler, la consigne serait ignorée en
     * aval de toute façon ; l'envoyer ne ferait que du trafic. Au-dessus, elle
     * ne coûte qu'une multiplication complexe tant qu'elle reste dans la plage
     * du décalage logiciel.
     */
    fun vautLaPeine(deltaHz: Long): Boolean = abs(deltaHz) >= DopplerTuner.DEADBAND_HZ
}
