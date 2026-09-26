/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

/**
 * Dans quel ordre écrire les deux fréquences d'un couple satellite.
 *
 * L'IC-9700 pose une règle que le logiciel ignorait : **les deux bandes ne
 * peuvent jamais être sur la même à la fois**. MAIN sur 435 et SUB sur 435, le
 * poste refuse — et il refuse en silence, par un simple NAK que rien
 * n'affichait.
 *
 * Or passer d'un satellite en V/U (descente 435, montée 145) à un satellite en
 * U/V (descente 145, montée 435) est exactement un échange des deux bandes.
 * Quel que soit l'ordre choisi d'avance, la première des deux écritures amène
 * une bande là où l'autre se trouve encore : c'est la panne qu'Olivier voyait
 * en 18.18, deux fréquences en 435 dans le panneau POSTE et un poste qui ne
 * changeait pas de bande. « ça ne switch pas » : non, en effet, et aucun ordre
 * fixe ne pouvait le faire.
 *
 * D'où cette pièce, qui ne connaît ni Android ni port série et se juge donc
 * sans radio branchée : à partir de ce que le poste affiche aujourd'hui et de
 * ce qu'on veut lui faire afficher, elle rend la suite d'écritures dont aucune
 * étape intermédiaire ne met les deux bandes ensemble.
 *
 * Trois cas, et seulement trois :
 *
 * 1. Écrire la montée d'abord ne gêne personne — c'est l'ordre habituel, et
 *    celui qui finit sur la réception, molette utile sous la main.
 * 2. Il faut commencer par la descente, parce que la bande visée par la montée
 *    est occupée par la descente actuelle.
 * 3. C'est un échange pur : les deux ordres se heurtent. Il faut alors garer la
 *    montée sur une **troisième** bande le temps de libérer la place. Sur un
 *    IC-9700 il y en a toujours une — 144, 430 et 1200 — et le garage coûte une
 *    trame de plus, une seule fois, au changement de satellite.
 *
 * (Le poste sait aussi échanger MAIN et SUB d'un coup, par 0x07 0xB0. Ce serait
 * une trame au lieu de trois dans le cas 3 ; on s'en tient volontairement aux
 * commandes déjà éprouvées au banc, le gain étant d'une demi-seconde une fois
 * par passage.)
 */
object BandPlan {

    /** Les bandes du poste, plus « ailleurs » pour tout le reste. */
    enum class Band { V, U, L, AUTRE }

    /** Le 2 m, le 70 cm, le 23 cm — larges, car un Doppler déborde des bords. */
    fun band(hz: Long): Band = when (hz) {
        in 143_000_000L..149_000_000L -> Band.V
        in 420_000_000L..460_000_000L -> Band.U
        in 1_200_000_000L..1_320_000_000L -> Band.L
        else -> Band.AUTRE
    }

    /** Une écriture : dans quelle bande du poste, et quelle fréquence. */
    data class Step(val sub: Boolean, val hz: Long)

    /** Le milieu de chaque bande, où l'on gare une montée en transit. */
    private val garages = linkedMapOf(
        Band.V to 145_000_000L,
        Band.U to 435_000_000L,
        Band.L to 1_295_000_000L)

    /** Une bande libre, c'est-à-dire aucune de celles qu'on est en train de viser. */
    fun garageHz(vararg eviter: Band): Long =
        garages.entries.firstOrNull { it.key !in eviter }?.value ?: 145_000_000L

    /**
     * La suite d'écritures à envoyer, dans l'ordre.
     *
     * [mainNow] et [subNow] sont ce que le poste affiche aujourd'hui, ou null
     * si on ne le sait pas encore — auquel cas ils ne contraignent rien, et
     * l'on retombe sur l'ordre habituel, celui d'avant.
     */
    fun steps(mainNow: Long?, subNow: Long?, mainTarget: Long, subTarget: Long): List<Step> {
        val bm = band(mainTarget)
        val bs = band(subTarget)
        val descente = Step(sub = false, hz = mainTarget)
        val montee = Step(sub = true, hz = subTarget)

        // Une consigne qui demanderait elle-même les deux bandes ensemble ne
        // peut pas être satisfaite : aucun ordre ne la sauve. On écrit alors la
        // descente, qui est ce qui permet d'entendre, et l'on ne touche pas à
        // la montée plutôt que de la poser n'importe où.
        if (bm == bs) return listOf(descente)

        val monteeDabord = mainNow == null || band(mainNow) != bs
        val descenteDabord = subNow == null || band(subNow) != bm

        return when {
            monteeDabord -> listOf(montee, descente)
            descenteDabord -> listOf(descente, montee)
            // L'échange pur : on gare la montée ailleurs, on pose la descente à
            // la place qu'elle vient de libérer, puis la montée à la sienne.
            else -> listOf(Step(true, garageHz(bm, bs)), descente, montee)
        }
    }
}
