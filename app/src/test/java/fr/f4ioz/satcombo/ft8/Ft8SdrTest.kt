/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ft8

import androidx.test.core.app.ApplicationProvider
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FT8 from the SDR dongle on the phone: a CQ in the dongle's sound (44.1 kHz,
 * as its reader hands it over), brought to 11 025 Hz, cut into its slot,
 * decoded — the PC's path, on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Ft8SdrTest {

    @After fun fin() { Ft8Hub.arrete(); Ft8Hub.horloge = System::currentTimeMillis }

    private fun cq(): FloatArray {
        val message = BooleanArray(Ft8.BITS_MESSAGE)
        Ft8.ecritEntier(message, 0, 28, 2L)
        Ft8.ecritEntier(message, 29, 28, Ft8.indicatifVers28("F4IOZ")!!)
        Ft8.ecritEntier(message, 59, 15, (('I' - 'A') * 1800 + ('N' - 'A') * 100 + 7 * 10 + 7).toLong())
        Ft8.ecritEntier(message, 74, 3, 1L)
        val bits = BooleanArray(Ft8.BITS)
        System.arraycopy(Ft8.avecControle(message), 0, bits, 0, Ft8.BITS_UTILES)
        val r = java.util.Random(1)
        for (i in Ft8.BITS_UTILES until Ft8.BITS) bits[i] = r.nextBoolean()
        return Ft8Signal.synthetise(Ft8.bitsVersSymboles(bits), Ft8Signal.FT8, 1500.0, 0.5)
    }

    @Test
    fun un_cq_dans_le_son_de_la_cle_est_decode() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The clock follows what the dongle has handed over.
        val t0 = 1_791_800_000_000L / 15_000L * 15_000L - 1_000L
        var verses = 0L
        Ft8Hub.horloge = { t0 + verses * 1000L / 44_100 }
        Ft8Hub.vide()
        Ft8Hub.choisitSource(ctx, "SDR")
        Ft8Hub.demarre(ctx, "FT8")
        val debut = System.currentTimeMillis()
        while (!Ft8Hub.ecouteSdr && System.currentTimeMillis() - debut < 5_000) Thread.sleep(10)
        assertTrue("listening to the dongle", Ft8Hub.ecouteSdr)
        val a = Ft8Signal.reechantillonne(cq(), Ft8Signal.FT8.cadenceHz, 44_100.0)
        val bloc = ShortArray(4096)
        fun verse(n: Int, ech: (Int) -> Float) {
            var i = 0
            while (i < n) {
                val k = minOf(bloc.size, n - i)
                for (j in 0 until k) bloc[j] = (ech(i + j) * 0.4f * 32767).toInt().toShort()
                Ft8Hub.feedLive(bloc, k)
                verses += k; i += k
                // As the dongle's reader: never faster than the decoding can queue.
                Thread.sleep(2)
            }
        }
        verse(44_100) { 0f }
        verse(15 * 44_100) { i -> if (i < a.size) a[i] else 0f }
        verse(8192) { 0f }
        val fin = System.currentTimeMillis() + 30_000
        while (Ft8Hub.state.value.entendus.isEmpty() && System.currentTimeMillis() < fin) Thread.sleep(50)
        val e = Ft8Hub.state.value.entendus.single()
        assertEquals("CQ F4IOZ IN77", e.texte)
        assertEquals(1500.0, e.frequenceHz.toDouble(), 10.0)
        assertEquals("SDR", Ft8Hub.state.value.source)
    }
}
