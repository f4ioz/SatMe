/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Transmitter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le mode de SatNOGS ne vaut que pour ce qui n'est pas un transpondeur.
 *
 * La base ne porte qu'un mode par entrée. Pour une balise c'est exact et
 * utile ; pour un transpondeur linéaire il n'y a pas de mode, et le champ
 * contient ce que le premier contributeur a bien voulu mettre. Sur QO-100,
 * les segments réservés à la BLU sont étiquetés « FM » — le montrer serait
 * une instruction fausse, pas une imprécision.
 */
class TransmittersModeTest {

    private fun emetteur(
        bas: Long?, haut: Long?, mode: String?,
    ) = Transmitter(
        description = "x", mode = mode,
        uplinkLowHz = null, uplinkHighHz = null,
        downlinkLowHz = bas, downlinkHighHz = haut,
        invert = false, alive = true, type = "Transponder")

    @Test
    fun une_balise_garde_son_mode() {
        val b = emetteur(10_489_745_000L, 10_489_745_000L, "BPSK")
        assertFalse(b.isTransponder)
        assertTrue(b.modeSignifiant)
    }

    @Test
    fun un_transpondeur_ne_montre_pas_le_sien() {
        val t = emetteur(10_489_650_000L, 10_489_750_000L, "FM")
        assertTrue(t.isTransponder)
        assertFalse(t.modeSignifiant)
    }

    /** Sans plage de descente, on ne sait pas : on garde ce qu'on a. */
    @Test
    fun sans_plage_le_mode_reste_affiche() {
        assertTrue(emetteur(null, null, "CW").modeSignifiant)
        assertTrue(emetteur(145_950_000L, null, "CW").modeSignifiant)
    }
}
