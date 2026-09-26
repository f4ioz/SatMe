/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.rotor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Une position de mât, en degrés, azimut compté depuis le nord vrai. */
data class RotorPos(val azDeg: Double, val elDeg: Double)

/**
 * Un rotor, vu de l'application : on lui donne un cap, on lui demande où il en
 * est, et on sait l'arrêter.
 *
 * Deux chemins mènent au mât et ils n'ont rien en commun — un câble série qui
 * porte de l'ASCII, une prise réseau qui parle à Hamlib — mais l'application ne
 * doit pas avoir à le savoir. Elle envoie un azimut et une élévation ; le reste
 * est une affaire de dialecte.
 */
interface RotorDriver {
    val isOpen: Boolean

    /** Envoie une consigne. Rend faux si elle n'est pas partie. */
    suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean

    /** Lit la position réelle du mât, ou null si le contrôleur n'a rien dit. */
    suspend fun readPosition(): RotorPos?

    /** Arrêt immédiat, tout de suite, sans discuter. */
    suspend fun stop(): Boolean

    fun close()
}

/**
 * Tout ce qui, dans le pilotage d'un rotor, peut casser quelque chose.
 *
 * Rien ici ne connaît Android, ni le série, ni le réseau : ce fichier se vérifie
 * au banc, en quelques millisecondes, et c'est délibéré. Un pilote de rotor est
 * la seule partie de SatMe qui déplace physiquement une antenne de trois mètres
 * au bout d'un mât ; une faute de signe n'y donne pas un affichage bizarre, elle
 * donne un câble arraché.
 *
 * Trois décisions vivent ici, et une seule d'entre elles est évidente.
 *
 * Le **recouvrement** d'abord. Un rotor qui tourne sur 450° peut représenter un
 * azimut de 10° aussi bien par 10 que par 370, et les deux visent rigoureusement
 * le même point du ciel. Le choix ne se voit pas sur l'écran : il se voit au
 * pied du mât, quand un passage qui traverse le nord fait faire au rotor une
 * quarantaine de degrés — ou trois cent cinquante.
 *
 * Le **retournement** ensuite. Les rotors d'élévation qui montent à 180° peuvent
 * viser un satellite au zénith sans faire faire demi-tour à l'azimut : viser
 * azimut 180 / élévation 80 revient exactement à viser azimut 0 / élévation 100.
 * Encore faut-il ne pas passer le passage à se retourner et à se redresser, d'où
 * les 90° d'hystérésis : on ne bascule que si l'autre branche fait économiser
 * plus que cela.
 *
 * Le **refus** enfin, et c'est le plus important. Une consigne hors course rend
 * `null`, et l'appelant s'abstient. Borner silencieusement une consigne
 * impossible ferait tourner le mât vers un endroit où le satellite n'est pas —
 * ce qui est pire que ne rien faire, parce que rien ne le dirait.
 */
object RotorMath {

    /**
     * Ce qu'il faut économiser pour avoir le droit de se retourner.
     *
     * Sans elle, un satellite qui passe tout près du zénith fait osciller le
     * choix d'une seconde à l'autre, et le mât fait des demi-tours à chaque
     * fois — au moment précis où le satellite est le plus haut et le signal le
     * meilleur.
     */
    const val FLIP_HYSTERESIS_DEG = 90.0

    /**
     * Ce qui pèse une erreur de pointage face à un degré de mât parcouru.
     *
     * Dix contre un, et ce n'est pas un réglage fin : quand il faut choisir
     * entre viser juste et bouger peu, on vise juste. Le mât s'use, mais un
     * passage ne se rattrape pas.
     */
    const val ERROR_WEIGHT = 10.0

    /**
     * Est-il temps d'aller attendre le satellite ?
     *
     * « Il faut qu'il soit positionné avant le début du passage, x minutes en
     * paramètre. » Un rotor n'est pas un téléphone : il met une bonne minute à
     * traverser le ciel, et parti au moment de l'acquisition il arrive quand le
     * satellite est déjà ailleurs — puis court après lui jusqu'au bout, toujours
     * en retard du même temps de rotation. Le placer d'avance ne coûte rien et
     * rattrape le début du passage, qui est justement le moment où le satellite
     * est le plus loin et le signal le plus faible.
     *
     * Vrai seulement dans la fenêtre qui précède l'AOS : ni avant, pour ne pas
     * immobiliser le mât une heure durant, ni après, puisque le passage a
     * commencé et que la poursuite ordinaire reprend la main.
     *
     * @param aosMs début du passage suivi, ou null s'il n'y en a pas.
     * @param avanceMin minutes d'avance demandées ; 0 ne fait rien.
     */
    fun prePositionDue(nowMs: Long, aosMs: Long?, avanceMin: Int): Boolean {
        if (avanceMin <= 0 || aosMs == null) return false
        val reste = aosMs - nowMs
        return reste > 0L && reste <= avanceMin * 60_000L
    }

    /** La course mécanique du mât, sa butée, et la zone morte de l'opérateur. */
    data class Limits(
        /** Course d'azimut : 360, 450 ou 540 degrés selon le contrôleur. */
        val azMaxDeg: Double = 450.0,
        /** Course d'élévation : 90 pour la plupart, 180 pour ceux qui se retournent. */
        val elMaxDeg: Double = 90.0,
        /** En dessous de cet écart, on laisse le mât tranquille. */
        val deadbandDeg: Double = 2.0,
        /**
         * Où se trouve le point mort du mât, en azimut vrai : 0 pour une butée
         * au nord, 180 pour une butée au sud.
         *
         * Le mât couvre alors les azimuts dépliés de [azStopDeg] à
         * `azStopDeg + azMaxDeg`, et rien d'autre. C'est une butée mécanique :
         * le câble arrive en bout de course, et la couronne s'arrête.
         */
        val azStopDeg: Double = 0.0
    ) {
        /** Le premier azimut déplié que le mât sait atteindre. */
        val azMinReach: Double get() = azStopDeg

        /** Le dernier. */
        val azMaxReach: Double get() = azStopDeg + azMaxDeg
    }

    /**
     * Une consigne prête à partir, la branche dont elle vient, et ce qu'il a
     * fallu abandonner pour la former.
     *
     * [errorDeg] vaut zéro partout où l'on refuse plutôt que de borner — c'est
     * le cas de [aim] et de [park]. Seul [follow] le remplit, parce que lui ne
     * refuse jamais : quand le satellite passe derrière la butée, le mât reste
     * où il est et l'écart est dit, en degrés, plutôt que caché.
     */
    data class Aim(
        val azDeg: Double,
        val elDeg: Double,
        val flipped: Boolean,
        val errorDeg: Double = 0.0
    )

    /**
     * Ce qu'on a décidé avant le passage, et qu'on ne rediscutera plus.
     *
     * @property shiftDeg le nombre de tours complets ajoutés à la trajectoire,
     *   en degrés : 0, ±360 ou ±720.
     * @property startAzDeg l'azimut déplié du premier point, décalage compris —
     *   pas encore rabattu dans la course, c'est le rôle de [clampAz].
     * @property coverage la part du passage réellement couverte, de 0 à 1,
     *   pondérée par le sinus de l'élévation.
     * @property worstErrorDeg le plus grand écart de pointage attendu.
     */
    data class Plan(
        val shiftDeg: Double,
        val startAzDeg: Double,
        val coverage: Double,
        val worstErrorDeg: Double
    )

    /** Ramène un azimut dans [0, 360). */
    fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Choisit, parmi az, az+360, az+720…, la représentation qui tient dans la
     * course et qui est la plus proche de [near]. null si aucune n'y tient.
     *
     * C'est toute la valeur du recouvrement : sur un mât 450°, un passage qui
     * traverse le nord continue tout droit au lieu de revenir sur ses pas.
     */
    fun unwrapNear(azDeg: Double, near: Double, azMaxDeg: Double, azStopDeg: Double = 0.0): Double? {
        val base = azStopDeg + norm360(azDeg - azStopDeg)
        var best: Double? = null
        var k = 0
        while (base + k * 360.0 <= azStopDeg + azMaxDeg + 1e-9) {
            val cand = base + k * 360.0
            val b = best
            if (b == null || abs(cand - near) < abs(b - near)) best = cand
            k++
        }
        return best
    }

    /**
     * La même chose, mais sans se soucier de la course : la représentation de
     * [azDeg] la plus proche de [near], même si le mât ne sait pas y aller.
     *
     * C'est volontaire, et c'est le cœur de la 18.10. Un dépliage borné par la
     * course ne peut, par construction, jamais désigner un point hors d'atteinte
     * — il rendrait donc toujours un écart nul, et la butée deviendrait
     * invisible. On déplie donc librement, puis on rabat, et l'écart entre les
     * deux est précisément ce qu'on veut savoir.
     */
    fun unwrapFree(azDeg: Double, near: Double): Double {
        val base = norm360(azDeg)
        val k = Math.round((near - base) / 360.0).toDouble()
        return base + k * 360.0
    }

    /**
     * Rabat un azimut déplié dans la course du mât, sans jamais l'enrouler.
     *
     * La différence avec [unwrapNear] tient en un mot : celui-ci ne cherche pas
     * une autre écriture du même point du ciel, il constate qu'on n'ira pas
     * plus loin. Un satellite derrière la butée laisse le mât en butée, et
     * l'écart se lit en soustrayant.
     */
    fun clampAz(azDeg: Double, limits: Limits): Double =
        azDeg.coerceIn(limits.azMinReach, limits.azMaxReach)

    /**
     * La consigne à envoyer pour viser [azTrueDeg] / [elTrueDeg], ou null quand
     * ce point du ciel est hors de portée du mât.
     *
     * [wasFlipped] est l'état retenu du coup précédent : c'est lui qui porte
     * l'hystérésis, et c'est pour cela que la fonction le rend dans son
     * résultat.
     */
    fun aim(
        azTrueDeg: Double,
        elTrueDeg: Double,
        current: RotorPos,
        limits: Limits,
        wasFlipped: Boolean = false
    ): Aim? {
        val direct = candidate(azTrueDeg, elTrueDeg, current, limits, flipped = false)
        val flipped = candidate(azTrueDeg + 180.0, 180.0 - elTrueDeg, current, limits, flipped = true)
        val held = if (wasFlipped) flipped else direct
        val other = if (wasFlipped) direct else flipped
        if (held == null) return other
        if (other == null) return held
        return if (cost(other, current) + FLIP_HYSTERESIS_DEG < cost(held, current)) other else held
    }

    private fun candidate(
        azDeg: Double, elDeg: Double, current: RotorPos,
        limits: Limits, flipped: Boolean
    ): Aim? {
        // Se retourner demande un rotor d'élévation qui monte à 180° ; les
        // autres n'ont tout simplement pas cette branche-là.
        if (flipped && limits.elMaxDeg < 180.0 - 1e-9) return null
        if (elDeg < -1e-9 || elDeg > limits.elMaxDeg + 1e-9) return null
        val az = unwrapNear(azDeg, current.azDeg, limits.azMaxDeg, limits.azStopDeg) ?: return null
        return Aim(az, elDeg, flipped)
    }

    /** Le coût mécanique d'une consigne : les degrés que le mât va parcourir. */
    fun cost(aim: Aim, current: RotorPos): Double =
        abs(aim.azDeg - current.azDeg) + abs(aim.elDeg - current.elDeg)

    /**
     * Vrai s'il vaut la peine de déranger le mât.
     *
     * Un rotor n'est pas un servo de modélisme : chaque départ use un relais et
     * fait grincer une couronne. En dessous de la zone morte, l'antenne vise
     * déjà juste — la largeur du lobe d'une antenne de satellite se compte en
     * dizaines de degrés.
     */
    fun needsMove(aim: Aim, current: RotorPos, deadbandDeg: Double): Boolean =
        abs(aim.azDeg - current.azDeg) >= deadbandDeg ||
            abs(aim.elDeg - current.elDeg) >= deadbandDeg

    /**
     * Le garage : où le mât va attendre quand le satellite est passé.
     *
     * Rendu null si la position demandée ne tient pas dans la course, pour la
     * même raison que le reste — mieux vaut ne pas garer que garer ailleurs.
     */
    fun park(azDeg: Double, elDeg: Double, limits: Limits): Aim? {
        if (azDeg < limits.azMinReach - 1e-9 || azDeg > limits.azMaxReach + 1e-9) return null
        if (elDeg < -1e-9 || elDeg > limits.elMaxDeg + 1e-9) return null
        return Aim(azDeg, elDeg, false)
    }

    /**
     * L'angle saisi à la main, ramené dans la course du mât.
     *
     * La différence avec [park] tient en un tour. Le garage est une position
     * que l'opérateur a choisie une fois pour toutes dans les réglages, en
     * connaissance de la mécanique : s'il la met hors course, il faut le lui
     * dire et ne rien faire. Un angle tapé à l'instant, lui, désigne un point
     * du ciel — et un mât à butée sud, qui couvre 180 à 630, atteint
     * parfaitement le nord : il s'appelle 360 chez lui, voilà tout.
     *
     * On essaie donc le nombre tel quel, puis le même à un tour près, dans les
     * deux sens. Le premier qui tient dans la course gagne. Aucun tour n'est
     * ajouté quand le nombre passe déjà : sur un mât à butée nord, 90 reste 90.
     *
     * L'élévation, elle, n'a pas de tours : on la refuse quand elle sort.
     */
    fun manual(azDeg: Double, elDeg: Double, limits: Limits): Aim? {
        for (tour in listOf(0.0, 360.0, -360.0)) {
            val a = park(azDeg + tour, elDeg, limits)
            if (a != null) return a
        }
        return null
    }

    /** Les degrés de mât entre deux consignes d'azimut successives. */
    fun travel(fromDeg: Double, toDeg: Double): Double = abs(toDeg - fromDeg)

    // ------------------------------------------------------------------
    // Les butées : décider avant le passage plutôt que subir pendant.
    // ------------------------------------------------------------------

    /**
     * Déroule une trajectoire en continu, sans jamais sauter d'un tour.
     *
     * Le prédicteur rend des azimuts dans [0, 360) : un passage qui traverse le
     * nord y apparaît comme un saut de 359 à 1, alors que le mât, lui, continue
     * tout droit. On refait donc le chemin pas à pas, chaque point posé au plus
     * près du précédent, et l'on obtient la suite que le mât devrait réellement
     * parcourir — quitte à ce qu'elle sorte de la course, ce qui est justement
     * ce qu'on cherche à savoir.
     */
    fun unroll(track: List<Pair<Double, Double>>, startNearDeg: Double): List<Double> {
        if (track.isEmpty()) return emptyList()
        val out = ArrayList<Double>(track.size)
        var cur = unwrapFree(track[0].first, startNearDeg)
        out += cur
        for (i in 1 until track.size) {
            cur = unwrapFree(track[i].first, cur)
            out += cur
        }
        return out
    }

    /**
     * Choisit, avant le passage, de quel côté de la butée on va le suivre.
     *
     * C'est la fonction qui répond au reproche d'Olivier, et il vaut la peine de
     * dire le problème avant la solution. Un mât n'est pas un plateau tournant :
     * il a un point mort, au nord ou au sud selon la marque, où le câble arrive
     * en bout de course. Un passage qui traverse ce point mort oblige le rotor à
     * dérouler un tour complet — une demi-minute de mât qui tourne dans le vide,
     * au moment précis où le satellite est haut et le signal le meilleur.
     *
     * On essaie donc cinq décalages, de moins deux tours à plus deux tours, et
     * l'on garde celui qui couvre le mieux le passage. Deux choix méritent
     * qu'on s'y arrête.
     *
     * **La couverture est pondérée par le sinus de l'élévation.** Trente
     * secondes ratées à trois degrés au-dessus de l'horizon, derrière les
     * arbres et dans le bruit, ne valent pas trente secondes ratées au zénith.
     * Compter les échantillons à égalité ferait préférer un plan qui protège le
     * lever du soleil au détriment du milieu du passage — l'inverse exact de ce
     * qu'on veut.
     *
     * **À couverture égale, le plus petit écart l'emporte** — mais seulement
     * ce qui dépasse la tolérance. Deux plans qui couvrent tout ne se valent
     * pas si l'un manque le premier point de vingt degrés et l'autre de cent ;
     * en revanche, trois degrés et zéro degré se valent exactement, et
     * préférer le second au prix d'un tour de mât serait le contraire de ce
     * qu'on cherche.
     *
     * **L'écart toléré compte comme couvert.** [toleranceDeg] est la demande
     * d'Olivier, et elle change le choix plus qu'il n'y paraît : un plan qui
     * manque le premier point de trois degrés, derrière la butée, ne vaut pas
     * un tour complet de mât pour aller le chercher. Le lobe d'une antenne de
     * satellite pardonne cette poignée de degrés ; la demi-minute de
     * déroulement, elle, ne se rattrape pas. Au-delà de la tolérance, en
     * revanche, un point manqué reste manqué, et [worstErrorDeg] dit toujours
     * le pire écart réel, sans indulgence — c'est lui qui alimente le bandeau.
     *
     * [startNearDeg] est l'endroit d'où le mât part — sa position au moment du
     * plan. Elle ne sert qu'à choisir l'écriture du premier point ; le décalage,
     * lui, est cherché ensuite.
     *
     * Rend null sur une trajectoire vide : il n'y a alors rien à planifier, et
     * ce n'est pas la même chose qu'un plan qui ne couvre rien.
     */
    fun plan(
        track: List<Pair<Double, Double>>,
        limits: Limits,
        startNearDeg: Double = limits.azStopDeg,
        toleranceDeg: Double = 0.0
    ): Plan? {
        if (track.isEmpty()) return null
        val unrolled = unroll(track, startNearDeg)
        // Le sinus de l'élévation, jamais négatif : sous l'horizon, un point ne
        // pèse rien du tout, et il n'a surtout pas le droit de peser à l'envers.
        val weights = track.map { max(0.0, sin(Math.toRadians(it.second))) }
        val total = weights.sum()

        var best: Plan? = null
        // Dans l'ordre du plus petit décalage au plus grand : à égalité
        // parfaite, on préfère ne pas ajouter de tours.
        for (shift in listOf(0.0, -360.0, 360.0, -720.0, 720.0)) {
            var covered = 0.0
            var count = 0
            var worst = 0.0
            for (i in unrolled.indices) {
                val want = unrolled[i] + shift
                val err = abs(want - clampAz(want, limits))
                if (err > worst) worst = err
                if (err <= toleranceDeg + 1e-9) { covered += weights[i]; count++ }
            }
            // Un passage entier sous l'horizon ne pèse rien : on retombe alors
            // sur le comptage brut, faute de mieux, plutôt que de diviser par
            // zéro et de rendre un plan qui ne veut rien dire.
            val cov = if (total > 1e-12) covered / total else count.toDouble() / unrolled.size
            // Ce qui dépasse la tolérance, et cela seul, départage à couverture
            // égale : sous la tolérance, deux plans se valent, et les séparer
            // sur l'écart ferait préférer un tour complet de mât pour gagner
            // trois degrés que le lobe de l'antenne ne distingue même pas.
            val exces = max(0.0, worst - toleranceDeg)
            val b = best
            val excesB = if (b == null) 0.0 else max(0.0, b.worstErrorDeg - toleranceDeg)
            val meilleur = b == null ||
                cov > b.coverage + 1e-9 ||
                (cov > b.coverage - 1e-9 && exces < excesB - 1e-9)
            if (meilleur) best = Plan(shift, unrolled[0] + shift, cov, worst)
        }
        return best
    }

    /**
     * Applique le plan, seconde après seconde.
     *
     * Rien ne se rediscute ici : on déplie l'azimut vrai au plus près de la
     * consigne précédente — c'est elle qui porte la branche choisie —, on le
     * rabat dans la course, et l'on dit de combien on a dû renoncer. Le mât ne
     * repart jamais en arrière chercher une autre écriture du même point : ce
     * serait exactement le tour complet qu'on cherche à éviter.
     *
     * Le retournement d'élévation reste possible sur les rotors qui montent à
     * 180°, avec la même hystérésis de [FLIP_HYSTERESIS_DEG] qu'en 18.9 — et
     * cette fois le coût compare aussi les écarts, dix fois plus lourds que les
     * degrés parcourus : se retourner pour viser juste vaut la peine, se
     * retourner pour bouger un peu moins ne la vaut pas.
     *
     * Ne rend jamais null. Une consigne impossible n'existe pas ici : il y a
     * toujours un endroit où le mât peut aller, même si ce n'est pas celui-là.
     */
    fun follow(
        azTrueDeg: Double,
        elTrueDeg: Double,
        current: RotorPos,
        limits: Limits,
        wasFlipped: Boolean = false
    ): Aim {
        val direct = reach(azTrueDeg, elTrueDeg, current.azDeg, limits, flipped = false)
        val flipped =
            if (limits.elMaxDeg >= 180.0 - 1e-9)
                reach(azTrueDeg + 180.0, 180.0 - elTrueDeg, current.azDeg, limits, flipped = true)
            else null
        if (flipped == null) return direct
        val held = if (wasFlipped) flipped else direct
        val other = if (wasFlipped) direct else flipped
        return if (followCost(other, current) + FLIP_HYSTERESIS_DEG < followCost(held, current))
            other else held
    }

    /** Ce que coûte une consigne : les degrés du mât, et l'erreur au prix fort. */
    fun followCost(aim: Aim, current: RotorPos): Double =
        cost(aim, current) + ERROR_WEIGHT * aim.errorDeg

    private fun reach(
        azDeg: Double, elDeg: Double, lastAzDeg: Double, limits: Limits, flipped: Boolean
    ): Aim {
        val want = unwrapFree(azDeg, lastAzDeg)
        val az = clampAz(want, limits)
        val el = elDeg.coerceIn(0.0, limits.elMaxDeg)
        return Aim(az, el, flipped, abs(want - az) + abs(elDeg - el))
    }

    /**
     * Où l'antenne pointe réellement, à partir de ce que le mât affiche.
     *
     * Deux corrections, et aucune n'est cosmétique. Un mât à recouvrement
     * annonce 380° là où le ciel n'a que 20 : la boussole doit montrer le
     * point du ciel, pas le tour de couronne. Et un rotor d'élévation retourné
     * — 100° d'élévation — pointe en réalité 80° dans la direction opposée ;
     * afficher la lecture brute mettrait l'aiguille à l'exact opposé de
     * l'antenne, ce qui est la seule erreur d'affichage qu'un opérateur ne
     * pardonne pas.
     */
    fun antennaAim(pos: RotorPos): RotorPos =
        if (pos.elDeg > 90.0 + 1e-9)
            RotorPos(norm360(pos.azDeg + 180.0), 180.0 - pos.elDeg)
        else RotorPos(norm360(pos.azDeg), pos.elDeg)

    /**
     * Traduit un azimut vrai vers l'origine du contrôleur, au tout dernier
     * moment.
     *
     * Dans toute l'application, un azimut est compté depuis le nord vrai — c'est
     * ce que dit le prédicteur, c'est ce que montre la boussole, c'est ce que
     * lit l'opérateur. Certains contrôleurs, eux, comptent depuis leur butée :
     * sur un mât à butée sud, leur « zéro » est notre 180.
     *
     * La conversion n'a donc lieu qu'à l'instant d'écrire la trame, et [trueAz]
     * la défait dès que la position revient. Un seul réglage change de
     * convention, et rien d'autre dans l'application n'a besoin de le savoir.
     */
    fun commandAz(azTrueDeg: Double, limits: Limits, fromStop: Boolean): Double =
        if (fromStop) azTrueDeg - limits.azStopDeg else azTrueDeg

    /** L'inverse de [commandAz] : ce que le contrôleur dit, en nord vrai. */
    fun trueAz(azCmdDeg: Double, limits: Limits, fromStop: Boolean): Double =
        if (fromStop) azCmdDeg + limits.azStopDeg else azCmdDeg
}
