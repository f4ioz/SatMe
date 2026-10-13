/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.cat.SerialLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmissionKissTest {

    private val maintenant = 1_000_000_000L
    private fun etat(hz: Long?, connecte: Boolean = true) = LienKiss.Etat(connecte = connecte, frequenceHz = hz, initialise = true)

    @Test fun `refuse sans poste, sans frequence sure, sans indicatif, trop tot`() {
        assertEquals("aprs_kiss_non_connecte", EmissionKiss.refus(etat(145_825_000L, connecte = false), true, "F4IOZ", 0L, maintenant))
        assertEquals("aprs_kiss_confirmer", EmissionKiss.refus(etat(null), false, "F4IOZ", 0L, maintenant))
        assertEquals("aprs_tx_refus_frequence", EmissionKiss.refus(etat(145_500_000L), true, "F4IOZ", 0L, maintenant))
        assertEquals("aprs_tx_refus_indicatif", EmissionKiss.refus(etat(145_825_000L), false, "", 0L, maintenant))
        assertEquals("aprs_tx_refus_ecart", EmissionKiss.refus(etat(145_825_000L), false, "F4IOZ", maintenant - 5_000L, maintenant))
    }

    @Test fun `accepte une frequence lue, ou garantie par l'operateur`() {
        assertNull(EmissionKiss.refus(etat(145_830_000L), false, "F4IOZ", 0L, maintenant))
        assertNull(EmissionKiss.refus(etat(null), true, "F4IOZ", 0L, maintenant))
        assertTrue(EmissionKiss.balisePrete(etat(145_825_000L)))
        assertTrue(!EmissionKiss.balisePrete(etat(144_800_000L)))
    }

    @Test fun `une trame part en une trame KISS`() {
        val ecrit = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val faux = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrit += bytes; return true }
            override fun read(buf: ByteArray, timeoutMs: Int): Int { Thread.sleep(20); return 0 }
            override fun close() {}
        }
        val l = LienKiss { }
        l.attache(faux, "essai", 9600)
        val t = AprsEmission.trame("F4IOZ-7", listOf("ARISS"), AprsEmission.statut("SatMe essai"))
        assertTrue(runBlocking { l.envoie(t) })
        val k = ecrit.last()
        assertEquals(0xC0, k.first().toInt() and 0xFF)
        assertEquals(0xC0, k.last().toInt() and 0xFF)
        assertEquals(1, l.etat.value.envoyees)
        l.deconnecte(sortirDuKiss = false)
    }

    @Test fun `une radio qui ne repond rien est dite muette`() {
        val muet = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int): Boolean = true
            override fun read(buf: ByteArray, timeoutMs: Int): Int { Thread.sleep(20); return 0 }
            override fun close() {}
        }
        val l = LienKiss { }
        l.attache(muet, "essai", 9600)
        assertTrue(runBlocking { l.passeEnKiss(144_800_000L) })
        assertTrue(l.etat.value.muette)
        assertNull(l.etat.value.frequenceHz)
        l.deconnecte(sortirDuKiss = false)
    }
}
