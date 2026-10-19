/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.domain.DecodeursMultiples.Decodeur
import fr.f4ioz.satcombo.sdr.ModeCanal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeursMultiplesTest {

    private val D = DecodeursMultiples

    @Test fun `quatre canaux au plus, un seul SSTV et une seule phonie`() {
        var l = emptyList<DecodeursMultiples.Canal>()
        for ((hz, d) in listOf(10_489_540_000L to Decodeur.FT8, 10_489_520_000L to Decodeur.CW, 10_489_700_000L to Decodeur.PHONIE)) {
            val r = D.ajoute(l, hz, d); assertNull(r.erreur); l = r.liste
        }
        assertNotNull(D.ajoute(l, 10_489_800_000L, Decodeur.PHONIE).erreur)
        l = D.ajoute(l, 10_489_600_000L, Decodeur.SSTV).liste
        assertEquals(4, l.size)
        assertNotNull(D.ajoute(l, 10_489_530_000L, Decodeur.CW).erreur)
        assertEquals(listOf(1, 2, 3, 4), l.map { it.id })
        // Two CW on two stations are fine; the same CW twice is not.
        val m = D.retire(l, 4)
        assertNull(D.ajoute(m, 10_489_530_000L, Decodeur.CW).erreur)
        assertNotNull(D.ajoute(m, 10_489_520_050L, Decodeur.CW).erreur)
    }

    @Test fun `un seul canal ecoute a la fois`() {
        var l = D.prereglage("ft8-cw-phonie", null, null, 10_489_750_000L)!!
        l = D.ecoute(l, 2, true)
        assertEquals(listOf(false, true, false), l.map { it.ecoute })
        l = D.ecoute(l, 3, true)
        assertEquals(listOf(false, false, true), l.map { it.ecoute })
        l = D.ecoute(l, 3, false)
        assertTrue(l.none { it.ecoute })
    }

    @Test fun `le cadran selon le decodeur et le signal`() {
        assertEquals(10_489_524_300L, D.cadranPour(Decodeur.CW, 10_489_525_000L))
        assertEquals(TraficQo100.CADRAN_FT8_HZ, D.cadranPour(Decodeur.FT8, 10_489_541_200L))
        assertEquals(10_489_610_500L, D.cadranPour(Decodeur.FT8, 10_489_612_000L))
        assertEquals(10_489_598_100L, D.cadranPour(Decodeur.SSTV, 10_489_600_000L))
        // The classifier's own tuning when it named the signal for that decoder.
        assertEquals(TraficQo100.accord("blu", 10_489_701_234L).cadranHz, D.cadranPour(Decodeur.PHONIE, 10_489_701_234L, "blu"))
        assertEquals(Decodeur.FT4, D.decodeurDe("ft4"))
        assertEquals(Decodeur.PHONIE, D.decodeurDe("blu"))
        assertEquals(Decodeur.AUCUN, D.decodeurDe("porteuse"))
        // PSK31 on 1 kHz, RTTY centred on 1.5 kHz: the classifier's labels open them.
        assertEquals(Decodeur.PSK31, D.decodeurDe("psk31"))
        assertEquals(Decodeur.RTTY, D.decodeurDe("rtty"))
        assertEquals(10_489_550_000L, D.cadranPour(Decodeur.PSK31, 10_489_551_000L))
        assertEquals(10_489_550_000L, D.cadranPour(Decodeur.RTTY, 10_489_551_500L))
        assertEquals(TraficQo100.accord("psk31", 10_489_551_234L).cadranHz, D.cadranPour(Decodeur.PSK31, 10_489_551_234L, "psk31"))
    }

    @Test fun `le prereglage FT8 CW phonie`() {
        val l = D.prereglage("ft8-cw-phonie", 10_489_525_000L, 10_489_801_500L, 10_489_750_000L)!!
        assertEquals(listOf(Decodeur.FT8, Decodeur.CW, Decodeur.PHONIE), l.map { it.decodeur })
        assertEquals(TraficQo100.CADRAN_FT8_HZ, l[0].cadranHz)
        assertEquals(10_489_524_300L, l[1].cadranHz)
        assertEquals(ModeCanal.CW, l[1].mode)
        assertEquals(10_489_800_000L, l[2].cadranHz)
        // No voice heard: where the main receiver listens when that is a voice segment.
        assertEquals(10_489_720_000L, D.prereglage("ft8-cw-phonie", null, null, 10_489_720_000L)!![2].cadranHz)
        assertEquals(10_489_700_000L, D.prereglage("ft8-cw-phonie", null, null, 10_489_545_000L)!![2].cadranHz)
        assertNull(D.prereglage("rien", null, null, 0L))
    }

    @Test fun `la liste gardee se relit, les regles appliquees`() {
        var l = D.prereglage("ft8-cw-phonie", null, null, 10_489_720_000L)!!
        l = D.ecoute(l, 3, true)
        assertEquals(l, D.lit(D.json(l).toString()))
        // A hand-edited file with two voices and two sounds: one of each kept.
        val t = "[{\"id\":7,\"hz\":10489700000,\"decodeur\":\"phonie\",\"mode\":\"USB\",\"ecoute\":true}," +
            "{\"id\":8,\"hz\":10489710000,\"decodeur\":\"phonie\",\"mode\":\"USB\",\"ecoute\":true}," +
            "{\"id\":9,\"hz\":10489520000,\"decodeur\":\"cw\",\"mode\":\"CW\",\"ecoute\":true},{\"decodeur\":\"??\"}]"
        val r = D.lit(t)
        assertEquals(listOf(7, 9), r.map { it.id })
        assertEquals(listOf(true, false), r.map { it.ecoute })
        assertTrue(D.lit("pas du json").isEmpty())
    }

    @Test fun `regler un canal garde les regles`() {
        val l = D.prereglage("ft8-cw-phonie", null, null, 10_489_720_000L)!!
        assertNotNull(D.regle(l, 1, decodeur = Decodeur.PHONIE).erreur)
        val r = D.regle(l, 2, decodeur = Decodeur.SSTV)
        assertNull(r.erreur)
        assertEquals(ModeCanal.USB, r.liste[1].mode)
        assertEquals(10_489_600_000L, D.regle(l, 1, cadranHz = 10_489_600_000L).liste[0].cadranHz)
    }

    @Test fun `le decalage dans la bande du recepteur`() {
        assertEquals(20_000.0, D.decalage(10_489_770_000L, 10_489_750_000.0, 1), 0.0)
        assertEquals(-20_000.0, D.decalage(10_489_770_000L, 10_489_750_000.0, -1), 0.0)
    }
}
