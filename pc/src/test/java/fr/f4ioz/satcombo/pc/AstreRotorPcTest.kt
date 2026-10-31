/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.Astres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections

/** « Soleil » / « Lune » in the rotor card: nothing moves without the click, below the horizon is refused, the follow stops. */
class AstreRotorPcTest {

    private val paris = Observer(48.85, 2.35, 50.0)
    private val midi = Instant.parse("2026-06-21T12:00:00Z").toEpochMilli()
    private val nuit = Instant.parse("2026-06-21T23:00:00Z").toEpochMilli()

    private class Banc(var t: Long, var connecte: Boolean = true, var elMin: Double = 0.0, var accepte: Boolean = true) {
        val envois: MutableList<Pair<Double, Double>> = Collections.synchronizedList(ArrayList())
        fun rotor(periodeMs: Long = 40L, obs: Observer? = Observer(48.85, 2.35, 50.0)) =
            AstreRotorPc({ obs }, { connecte }, { elMin }, { az, el -> envois += az to el; accepte }, { t }, periodeMs)
    }

    @Test
    fun rien_ne_bouge_sans_le_clic() {
        val b = Banc(midi)
        val r = b.rotor()
        // The page reads (the card's line), time passes: no command.
        repeat(5) { r.json() }
        Thread.sleep(200)
        r.arrete()
        assertTrue(b.envois.isEmpty())
        assertEquals(0, r.envois)
        assertFalse(r.suit)
    }

    @Test
    fun un_clic_un_seul_envoi_vers_le_soleil_calcule() {
        val b = Banc(midi)
        val r = b.rotor()
        val o = r.vise("soleil", false)
        assertTrue(o.toString(), o.getBoolean("ok"))
        Thread.sleep(200)
        assertEquals(1, b.envois.size)
        val (az, el) = Astres.azEl(Astres.Astre.SOLEIL, paris.latDeg, paris.lonDeg, paris.altMeters, midi)
        assertEquals(az, b.envois[0].first, 1e-9)
        assertEquals(el, b.envois[0].second, 1e-9)
        assertTrue(r.message.contains("Soleil"))
    }

    @Test
    fun sous_l_horizon_ou_trop_bas_ou_sans_rotor_rien_n_est_envoye() {
        val b = Banc(nuit)
        val r = b.rotor()
        val o = r.vise("soleil", true)
        assertFalse(o.getBoolean("ok"))
        assertTrue(o.getString("erreur").contains("sous l'horizon"))
        assertFalse(r.suit)
        b.t = midi; b.elMin = 70.0
        assertTrue(r.vise("soleil", false).getString("erreur").contains("trop bas"))
        b.elMin = 0.0; b.connecte = false
        assertTrue(r.vise("soleil", false).getString("erreur").contains("aucun rotor"))
        b.connecte = true
        assertFalse(r.vise("jupiter", false).getBoolean("ok"))
        assertFalse(b.rotor(obs = null).vise("lune", false).getBoolean("ok"))
        Thread.sleep(100)
        assertTrue(b.envois.isEmpty())
        // The rotor's limits: refused by the rotor, said so, nothing more sent.
        b.accepte = false
        assertTrue(r.vise("soleil", true).getString("erreur").contains("limites"))
        Thread.sleep(150)
        assertEquals(1, b.envois.size)
    }

    @Test
    fun suivre_recale_jusqu_a_l_arret() {
        val b = Banc(midi)
        val r = b.rotor(periodeMs = 30L)
        assertTrue(r.vise("soleil", true).getBoolean("ok"))
        assertTrue(r.suit)
        Thread.sleep(250)
        val n = b.envois.size
        assertTrue("$n", n >= 3)
        r.arrete()
        assertFalse(r.suit)
        Thread.sleep(150)
        val apres = b.envois.size
        Thread.sleep(150)
        assertEquals(apres, b.envois.size)
        assertTrue(apres <= n + 1)
        // The body sets during the follow: it stops by itself, and says why.
        assertTrue(r.vise("soleil", true).getBoolean("ok"))
        b.t = nuit
        Thread.sleep(200)
        assertFalse(r.suit)
        assertTrue(r.message, r.message.contains("arrêté") && r.message.contains("horizon"))
    }
}
