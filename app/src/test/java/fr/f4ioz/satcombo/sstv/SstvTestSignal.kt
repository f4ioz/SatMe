/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sstv

/**
 * L'émetteur utilisé par les essais.
 *
 * Il vivait ici tant que rien d'autre n'en avait besoin ; il est passé dans
 * les sources de l'application le jour où les mires SSTV ont eu à produire du
 * son. Ce n'est plus qu'un nom, gardé pour que les essais continuent de dire
 * ce qu'ils vérifient : une table de modes relue par un chemin indépendant du
 * décodeur.
 */
object SstvTestSignal {

    fun encode(
        mode: SstvMode,
        image: IntArray,
        sampleRate: Int,
        blocks: Int = mode.blocks,
        leadMs: Double = 120.0,
        trailMs: Double = 60.0
    ): ShortArray = SstvEncoder.encode(mode, image, sampleRate, blocks, leadMs, trailMs)
}
