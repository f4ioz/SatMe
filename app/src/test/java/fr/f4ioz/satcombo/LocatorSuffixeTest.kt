/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Indicatifs
import org.junit.Assert.assertEquals
import org.junit.Test

class ReproLocatorSuffixeTest {
    @Test
    fun le_carre_du_portable_ne_fuit_pas_vers_la_base() {
        val contacts = listOf(
            Indicatifs.Contact("F5RRO/P", "IN88", 1000L, "RS-44", ""),
            Indicatifs.Contact("F5RRO", "", 2000L, "SO-50", ""),
        )
        val memoire = Indicatifs.memoire(contacts)
        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle("F5RRO") }
        assertEquals("", Indicatifs.locatorPropose(connu, "F5RRO"))
    }

    @Test
    fun le_portable_seul_en_memoire_ne_propose_rien_pour_la_base() {
        val contacts = listOf(
            Indicatifs.Contact("F5RRO/P", "IN88", 1000L, "RS-44", ""),
        )
        val memoire = Indicatifs.memoire(contacts)
        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle("F5RRO") }
        assertEquals("", Indicatifs.locatorPropose(connu, "F5RRO"))
    }
}
