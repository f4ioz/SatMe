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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The listening-only KISS link: frames in, nothing out but the operator's own command. */
class LienKissEcouteTest {

    private class Cable : SerialLink {
        val ecrits = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val aLire = LinkedBlockingQueue<ByteArray>()
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean { ecrits += bytes.copyOf(); return true }
        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val b = aLire.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            b.copyInto(buf); return b.size
        }
        override fun close() {}
    }

    private val trame = Trame(Adresse("APRS"), Adresse("F4IOZ", 9), emptyList(), ">écoute".toByteArray(Charsets.ISO_8859_1))

    @Test
    fun ecoute_seule_n_ecrit_rien_d_elle_meme() = runBlocking {
        val cable = Cable()
        val recues = LinkedBlockingQueue<Trame>()
        val bruts = LinkedBlockingQueue<ByteArray>()
        val k = LienKiss(ecouteSeule = true) { recues += it }
        k.surOctets = { bruts += it }
        k.attache(cable, "essai", 9600)
        assertTrue(k.etat.value.ecouteSeule)
        cable.aLire += Kiss.trame(Ax25.encodeSansFcs(trame))
        assertEquals(trame, recues.poll(3, TimeUnit.SECONDS))
        assertArrayEquals(Ax25.encodeSansFcs(trame), bruts.poll(1, TimeUnit.SECONDS))

        assertFalse(k.passeEnKiss(144_800_000L))
        assertFalse(k.envoie(trame))
        assertFalse(k.regleFrequence(145_825_000L))
        assertTrue(cable.ecrits.isEmpty())

        // The operator's own command goes out, as it is.
        assertTrue(k.commande(LienKiss.CommandeKiss.ENTREE_KENWOOD))
        assertArrayEquals("\rKISS ON\rRESTART\r".toByteArray(), cable.ecrits.single())
        k.deconnecte()  // leaves the radio as it is
        assertEquals(1, cable.ecrits.size)
    }

    @Test
    fun lien_ordinaire_inchange() = runBlocking {
        val cable = Cable()
        val k = LienKiss { }
        k.attache(cable, "essai", 9600)
        assertFalse(k.etat.value.ecouteSeule)
        assertTrue(k.envoie(trame))
        assertArrayEquals(Kiss.trame(Ax25.encodeSansFcs(trame)), cable.ecrits.single())
        k.deconnecte()  // KISS left, TNC switched off, as before
        assertArrayEquals(Kiss.SORTIE, cable.ecrits[1])
    }
}
