/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.rotor.Gs232Codec
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's own calibration (`O`, `F`, `O2`, `F2`) against a fake
 * K3NG: its prompt, the keystroke it blocks on, "Wrote to memory" — and the
 * controllers that do not know these commands.
 */
class Gs232EtalonnageTest {

    private fun rotor(l: SerialLink) = Gs232Rotor().apply { pacingMs = 0L; attach(l) }

    @Test
    fun o2_puis_f2_recalent_l_elevation_comme_k3ng() = runBlocking {
        val sim = Gs232Simulator(azMaxDeg = 360.0, elMaxDeg = 180.0, k3ng = true)
        // A worn pot: the controller says 180 when the boom is really at 160.
        sim.deregleElevation(reelLuEnHaut = 160.0)
        val r = rotor(sim)
        // Up to the real 160 (the box says 180: the mast is told 180).
        r.moveTo(0.0, 180.0); repeat(40) { sim.advance(1000) }
        assertEquals(160.0, sim.elDeg, 1e-6)
        assertEquals(180.0, r.readPosition()!!.elDeg, 0.5)

        // Down to the real 0 by the box, then O2.
        r.moveTo(0.0, 0.0); repeat(40) { sim.advance(1000) }
        val o2 = r.etalonne(Gs232Codec.Etalonnage.EL_ZERO)
        assertEquals(Gs232Rotor.Issue.ECRIT, o2.issue)
        assertTrue(o2.lignes.toString(), o2.lignes.any { Gs232Codec.estInvite(it) })
        // Exactly: the command, the keystroke — nothing else on the line meanwhile.
        assertEquals(listOf("O2", "(touche)"), sim.received.takeLast(2))

        // The real top is 180: the box takes the mast there (the controller would not: it reads 202).
        sim.auBoitier(elReel = 180.0)
        assertTrue(r.readPosition()!!.elDeg > 181.0)
        val f2 = r.etalonne(Gs232Codec.Etalonnage.EL_PLEIN)
        assertEquals(Gs232Rotor.Issue.ECRIT, f2.issue)
        assertEquals(180.0, r.readPosition()!!.elDeg, 0.5)
        r.moveTo(0.0, 90.0); repeat(40) { sim.advance(1000) }
        assertEquals("calibrated: 90 is really 90", 90.0, sim.elDeg, 0.5)
        assertEquals(2, sim.ecrituresEeprom)
    }

    @Test
    fun o_et_f_recalent_l_azimut() = runBlocking {
        val sim = Gs232Simulator(azMaxDeg = 360.0, elMaxDeg = 180.0, k3ng = true)
        sim.deregleAzimut(reelCcw = 5.0, reelCw = 350.0)
        val r = rotor(sim)
        r.moveTo(0.0, 0.0); repeat(60) { sim.advance(1000) }
        assertEquals(5.0, sim.azDeg, 1e-6)
        assertEquals(Gs232Rotor.Issue.ECRIT, r.etalonne(Gs232Codec.Etalonnage.AZ_ZERO).issue)
        r.moveTo(360.0, 0.0); repeat(80) { sim.advance(1000) }
        assertEquals(Gs232Rotor.Issue.ECRIT, r.etalonne(Gs232Codec.Etalonnage.AZ_PLEIN).issue)
        assertEquals(listOf("F", "(touche)"), sim.received.takeLast(2))
    }

    /** A controller that knows none of it: `?>` to everything but C/W/S. */
    private class SansEtalonnage : SerialLink {
        private val sortie = ArrayDeque<Byte>()
        private val ligne = StringBuilder()
        val recu = ArrayList<String>()
        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (c == '\r') { recu += ligne.toString(); repond(ligne.toString()); ligne.setLength(0) } else ligne.append(c)
            }
            return true
        }
        private fun repond(l: String) {
            val r = when { l.startsWith("C") -> "+0180+0045\r"; l.startsWith("W") || l.startsWith("S") || l.isEmpty() -> ""; else -> "?>\r" }
            r.toByteArray().forEach { sortie.addLast(it) }
        }
        override fun read(buf: ByteArray, timeoutMs: Int): Int { var n = 0; while (n < buf.size && sortie.isNotEmpty()) buf[n++] = sortie.removeFirst(); return n }
        override fun close() {}
    }

    @Test
    fun commande_inconnue_dite_et_pas_de_touche_envoyee() = runBlocking {
        val l = SansEtalonnage()
        val r = rotor(l)
        val res = r.etalonne(Gs232Codec.Etalonnage.EL_PLEIN)
        assertEquals(Gs232Rotor.Issue.INCONNU, res.issue)
        assertEquals("no keystroke after a refusal", listOf("F2"), l.recu)
    }

    @Test
    fun un_controleur_muet_est_dit_muet() = runBlocking {
        val muet = object : SerialLink {
            override fun write(bytes: ByteArray, timeoutMs: Int) = true
            override fun read(buf: ByteArray, timeoutMs: Int) = 0
            override fun close() {}
        }
        assertEquals(Gs232Rotor.Issue.MUET, rotor(muet).etalonne(Gs232Codec.Etalonnage.EL_ZERO).issue)
    }

    @Test
    fun un_gs232_ordinaire_ignore_sans_rien_dire() = runBlocking {
        // The plain simulator (not K3NG): as a box that ignores O/F silently.
        val sim = Gs232Simulator(k3ng = false)
        assertEquals(Gs232Rotor.Issue.MUET, rotor(sim).etalonne(Gs232Codec.Etalonnage.AZ_ZERO).issue)
    }

    @Test
    fun le_journal_decrit_les_commandes() {
        assertTrue(Gs232Codec.describe("O2\r", out = true).contains("0°"))
        assertTrue(Gs232Codec.describe("F\r", out = true).contains("CW"))
        assertEquals(Gs232Codec.Etalonnage.EL_PLEIN, Gs232Codec.Etalonnage.de("f2"))
        assertEquals(null, Gs232Codec.Etalonnage.de("W180 045"))
    }
}
