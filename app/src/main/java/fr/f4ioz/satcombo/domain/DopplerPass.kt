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
 * Le Doppler d'un passage entier, et non plus de l'instant présent.
 *
 * La demande de départ tenait en une phrase : sur un passage RX 435 / TX 145,
 * la fréquence de descente doit *baisser du début à la fin*. Le diagnostic a
 * surpris, parce que l'arithmétique était juste, et depuis longtemps : ce qui
 * manquait, c'est que la vitesse radiale n'atteignait l'affichage que lorsque
 * le satellite était déjà au-dessus de l'horizon. Ouvrir un passage **à venir**
 * ne montrait donc rien du tout — or c'est justement avant le passage qu'on
 * veut savoir où poser le VFO.
 *
 * D'où ce calcul, qui ne connaît pas d'horizon : on lui donne un échantillonneur
 * et deux fréquences de repos, il rend le tableau du passage.
 *
 * **Le sens du glissement, une fois pour toutes**, parce qu'il surprend
 * toujours : en descente, le satellite arrive, la vitesse radiale est négative,
 * la fréquence reçue est trop haute ; elle ne fait ensuite que baisser, traverse
 * le repos au point le plus haut de la trajectoire, et finit trop basse. La
 * montée fait l'inverse, et pour la même raison — c'est le satellite qui reçoit.
 * Une station qui suit un transpondeur voit donc ses deux VFO partir en sens
 * contraires : ce n'est pas un défaut, c'est la signature d'un suivi juste.
 *
 * **L'inversion du transpondeur ne change rien à ce sens-là.** Elle décide
 * seulement quelle voie de montée correspond à la descente choisie dans la bande
 * passante, et c'est [Doppler.transponderUplinkRest] qui s'en occupe, en amont.
 * Confondre les deux est l'erreur la plus répandue sur les transpondeurs
 * linéaires inverseurs, et elle coûte un passage entier à chercher son écho.
 */
object DopplerPass {

    /** Un instant du passage, tel que l'échantillonneur le rend. */
    data class Sample(
        val timeMs: Long,
        val elevationDeg: Double,
        /** Vitesse radiale en km/s, positive quand le satellite s'éloigne. */
        val rangeRateKmS: Double
    )

    /** Une ligne du tableau : repère et heure, élévation, RX ↓, TX ↑. */
    data class Row(
        val mark: String,
        val timeMs: Long,
        val elevationDeg: Double,
        val rxHz: Long,
        val txHz: Long?
    )

    /** Le tableau complet, avec les excursions totales des deux voies. */
    data class Table(
        val rows: List<Row> = emptyList(),
        val rxRestHz: Long = 0L,
        val txRestHz: Long? = null,
        /** Écart entre la fréquence reçue la plus haute et la plus basse. */
        val rxExcursionHz: Long = 0L,
        /** Idem sur la montée ; nul quand il n'y a pas de voie montante. */
        val txExcursionHz: Long = 0L
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    const val MARK_AOS = "AOS"
    const val MARK_MAX = "MAX"
    const val MARK_LOS = "LOS"

    /** Pas du balayage grossier : la demi-minute suffit à trouver la bosse. */
    const val COARSE_MS = 30_000L

    /** Pas de l'affinage autour du sommet. */
    const val FINE_MS = 2_000L

    /**
     * Le repos de réception à employer, ou null s'il n'y en a pas.
     *
     * L'ordre est celui qu'on attend : un accord manuel passe devant le
     * catalogue, et le centre de la bande passante devant son bord bas. Ce qui
     * manquait, c'est la **première** condition : sans émetteur choisi, il n'y
     * a pas de repos du tout, quoi qu'on ait accordé auparavant.
     *
     * C'est le défaut « la fréquence TX n'apparaît pas ». En changeant de
     * satellite, la liste des émetteurs se vidait le temps de la recharge, mais
     * le repos accordé à la main sur le satellite précédent restait, lui, bien
     * en place. Le tableau se construisait donc avec une colonne de réception
     * pleine — héritée d'un autre satellite — et une colonne d'émission
     * entièrement vide, faute d'émetteur pour en donner la voie montante. Une
     * demi-page de chiffres justes à côté d'une demi-page de tirets : c'est la
     * pire forme d'erreur, celle qui a l'air de marcher.
     */
    fun rxRest(reposAccorde: Long?, centreEmetteur: Long?, basEmetteur: Long?): Long? {
        if (centreEmetteur == null && basEmetteur == null) return null
        return reposAccorde ?: centreEmetteur ?: basEmetteur
    }

    /**
     * Construit le tableau d'un passage.
     *
     * [sample] reçoit (début, fin, pas) et rend les instants correspondants :
     * le calcul ne connaît donc ni TLE ni SGP4, ce qui le rend vérifiable sur
     * une trajectoire écrite à la main.
     *
     * Le point le plus haut est cherché à la demi-minute puis affiné à deux
     * secondes. Le placer au milieu du temps, comme on est tenté de le faire,
     * serait faux dès que la trajectoire est dissymétrique — et elle l'est
     * presque toujours.
     */
    fun build(
        aosMs: Long,
        losMs: Long,
        rxRestHz: Long,
        txRestHz: Long? = null,
        rows: Int = 7,
        sample: (Long, Long, Long) -> List<Sample>
    ): Table {
        if (losMs <= aosMs || rxRestHz <= 0L) return Table(rxRestHz = rxRestHz, txRestHz = txRestHz)
        val coarse = sample(aosMs, losMs, COARSE_MS).sortedBy { it.timeMs }
        if (coarse.isEmpty()) return Table(rxRestHz = rxRestHz, txRestHz = txRestHz)

        val top = coarse.maxByOrNull { it.elevationDeg }!!
        val fine = sample(
            (top.timeMs - COARSE_MS).coerceAtLeast(aosMs),
            (top.timeMs + COARSE_MS).coerceAtMost(losMs),
            FINE_MS
        )
        val best = (fine + top).maxByOrNull { it.elevationDeg }!!

        // L'excursion se mesure sur tout ce qu'on sait du passage, et non sur
        // les seules lignes affichées : sinon la valeur annoncée dépendrait du
        // nombre de lignes qui tiennent à l'écran.
        val all = (coarse + fine).sortedBy { it.timeMs }
        val rx = all.map { Doppler.downlink(rxRestHz, it.rangeRateKmS) }
        val rxExc = (rx.max() - rx.min())
        val txExc = if (txRestHz != null && txRestHz > 0L) {
            val tx = all.map { Doppler.uplink(txRestHz, it.rangeRateKmS) }
            tx.max() - tx.min()
        } else 0L

        val picked = LinkedHashMap<Long, Sample>()
        picked[coarse.first().timeMs] = coarse.first()
        val inner = (rows - 3).coerceAtLeast(0)
        for (k in 1..inner) {
            val t = aosMs + (losMs - aosMs) * k / (inner + 1)
            val s = coarse.minByOrNull { kotlin.math.abs(it.timeMs - t) } ?: continue
            picked[s.timeMs] = s
        }
        picked[best.timeMs] = best
        picked[coarse.last().timeMs] = coarse.last()

        val ordered = picked.values.sortedBy { it.timeMs }
        val out = ordered.mapIndexed { i, s ->
            val mark = when {
                i == 0 -> MARK_AOS
                i == ordered.size - 1 -> MARK_LOS
                s.timeMs == best.timeMs -> MARK_MAX
                else -> ""
            }
            Row(
                mark = mark,
                timeMs = s.timeMs,
                elevationDeg = s.elevationDeg,
                rxHz = Doppler.downlink(rxRestHz, s.rangeRateKmS),
                txHz = if (txRestHz != null && txRestHz > 0L)
                    Doppler.uplink(txRestHz, s.rangeRateKmS) else null
            )
        }
        return Table(out, rxRestHz, txRestHz, rxExc, txExc)
    }

    /** L'excursion en kilohertz, arrondie au dixième, pour l'affichage. */
    fun kHz(hz: Long): String = "%.1f kHz".format(hz / 1000.0)
}
