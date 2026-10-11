/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.cat.RxArbiter
import fr.f4ioz.satcombo.domain.Doppler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Receive Doppler ("the radio frequency does not move"). Arbitration: who
 * holds the RX VFO, never mistaking our own command for a gesture. And the
 * real fix, a frozen rest frequency: re-reading it from a motionless VFO made
 * it drift by the full Doppler. Steps are 100 ms, the CAT loop period.
 */
class CatRxDopplerTest {

    private val pasMs = 100L

    @Test
    fun la_molette_qui_tourne_garde_la_main() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        // The first reading proves nothing: nobody has touched anything since
        // we connected, and we lead — see `on_mene_avant_d_avoir_jamais_suivi`.
        a.observe(hz, t)
        // Then manual tuning: 50 Hz per step for five seconds. From the first
        // movement, the operator has control.
        repeat(50) {
            hz += 50L
            t += pasMs
            val geste = a.observe(hz, t)
            assertTrue("le geste n'a pas été vu à $t ms", geste)
            assertFalse("le logiciel a pris la main pendant l'accord", a.driven)
        }
    }

    /**
     * "Selecting the satellite does not set the frequency": the arbiter gave up
     * on the first reading and the app **adopted wherever the radio was left**
     * instead of the computed passband centre. It followed before ever leading.
     */
    @Test
    fun on_mene_avant_d_avoir_jamais_suivi() {
        val a = RxArbiter()
        assertTrue("l'arbitre doit mener au départ", a.driven)
        // A first reading, whatever it is, does not make us let go.
        assertFalse(a.observe(435_000_000L, 0L))
        assertTrue("une simple lecture n'est pas un geste", a.driven)
    }

    @Test
    fun un_changement_de_satellite_nous_rend_la_main() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        a.observe(hz, t)
        repeat(5) { hz += 100L; t += pasMs; a.observe(hz, t) }
        assertFalse("l'opérateur avait la main", a.driven)
        // New satellite: we know where to be.
        a.reset()
        assertTrue("après un changement de satellite, nous menons", a.driven)
    }

    @Test
    fun deux_secondes_de_silence_et_le_logiciel_prend_le_relais() {
        val a = RxArbiter()
        var hz = 435_856_800L
        var t = 0L
        a.observe(hz, t)
        // A real gesture is needed first: the arbiter leads until something
        // moves.
        hz += 200L
        t += pasMs
        assertTrue(a.observe(hz, t))
        assertFalse("le geste doit rendre la main à l'opérateur", a.driven)
        val tGeste = t
        // At 1.9 s after the gesture, still waiting: resuming earlier would
        // steal the dial mid-search.
        while (t - tGeste < 1_900L) {
            t += pasMs
            a.observe(hz, t)
            assertFalse("reprise trop tôt, à ${t - tGeste} ms après le geste", a.driven)
        }
        // After two seconds, the software takes over.
        while (t - tGeste < 2_100L) {
            t += pasMs
            a.observe(hz, t)
        }
        assertTrue("le logiciel n'a jamais pris la main", a.driven)
    }

    @Test
    fun nos_propres_consignes_ne_passent_pas_pour_un_geste() {
        val a = RxArbiter()
        var t = 0L
        var hz = 435_856_800L
        a.observe(hz, t)
        repeat(25) { t += pasMs; a.observe(hz, t) }
        assertTrue(a.driven)

        // From here we write: 30 Hz per step, well above the detection
        // threshold. Without remembering our commands, each of our own steps
        // would make us give up control.
        repeat(40) {
            hz -= 30L
            a.commanded(hz)
            t += pasMs
            val geste = a.observe(hz, t)
            assertFalse("notre propre consigne a été prise pour un geste", geste)
            assertTrue("nous avons lâché la molette tout seuls", a.driven)
        }
    }

    @Test
    fun un_geste_pendant_le_pilotage_rend_la_main_aussitot() {
        val a = RxArbiter()
        var t = 0L
        val hz = 435_856_800L
        a.observe(hz, t)
        repeat(25) { t += pasMs; a.observe(hz, t) }
        a.commanded(hz - 30L)
        t += pasMs
        a.observe(hz - 30L, t)
        assertTrue(a.driven)

        // The operator looks for a station higher in the transponder.
        t += pasMs
        assertTrue("le geste n'a pas été vu", a.observe(hz + 1_200L, t))
        assertFalse("le logiciel n'a pas lâché la molette", a.driven)

        // And the software resumes only after the required silence.
        val reprise = t + 2_000L
        while (t < reprise) {
            t += pasMs
            a.observe(hz + 1_200L, t)
            if (t < reprise) assertFalse("reprise trop tôt à $t ms", a.driven)
        }
        assertTrue("le logiciel n'a pas repris la main", a.driven)
    }

    @Test
    fun le_repos_relu_derive_alors_que_le_repos_fige_tient() {
        // The reported pass in numbers: RX at 435.8568 MHz, radio motionless,
        // satellite receding ever faster.
        val posteImmobile = 435_856_800L
        val repos = Doppler.restFromDownlink(posteImmobile, 0.0)

        var pireDerive = 0L
        var pireEcritureAttendue = 0L
        for (dixiemes in 0..600) {
            // Range rate from 0 to 6 km/s, typical of a LEO pass.
            val rr = 6.0 * dixiemes / 600.0

            // Old way: re-read the rest from a VFO that does not move.
            val reposRelu = Doppler.restFromDownlink(posteImmobile, rr)
            pireDerive = maxOf(pireDerive, abs(reposRelu - repos))

            // New way: freeze the rest and derive what the radio should show.
            // That command was missing.
            val aEcrire = Doppler.downlink(repos, rr)
            pireEcritureAttendue = maxOf(pireEcritureAttendue, abs(aEcrire - posteImmobile))
        }

        // The re-read rest drifted by several kHz: the station left the filter,
        // and TX followed the error.
        assertTrue("la dérive du repos relu n'est pas celle attendue : $pireDerive Hz",
            pireDerive > 8_000L)
        // The missing RX command is of the same order: exactly how far the
        // radio should have moved and did not.
        assertTrue("l'écriture attendue est trop faible : $pireEcritureAttendue Hz",
            pireEcritureAttendue > 8_000L)

        // The frozen rest never moves by construction — within the one hertz
        // of rounding the round trip can cost.
        val retour = Doppler.restFromDownlink(Doppler.downlink(repos, 4.2), 4.2)
        assertTrue("l'aller-retour a perdu le repos : $retour", abs(retour - repos) <= 1L)
    }

    @Test
    fun la_reception_et_l_emission_partent_du_meme_repos() {
        // On an inverting transponder both commands must come from the same
        // frozen rest: that keeps us aligned with the other station on both
        // sides of the satellite.
        val dlLow = 435_840_000L; val dlHigh = 435_860_000L
        val ulLow = 145_930_000L; val ulHigh = 145_950_000L
        val repos = 435_856_800L

        val ulRepos = Doppler.transponderUplinkRest(
            repos, dlLow, dlHigh, ulLow, ulHigh, invert = true)
        var precedentDl = Doppler.downlink(repos, 0.0)
        var precedentUl = Doppler.uplink(ulRepos, 0.0)
        for (i in 1..100) {
            val rr = 6.0 * i / 100.0
            val dl = Doppler.downlink(repos, rr)
            val ul = Doppler.uplink(ulRepos, rr)
            // Receding: RX goes down, TX goes up.
            assertTrue("la réception ne descend pas : $dl", dl < precedentDl)
            assertTrue("l'émission ne monte pas : $ul", ul > precedentUl)
            precedentDl = dl; precedentUl = ul
        }
        // The TX rest stays within the transponder band.
        assertTrue("repos d'émission hors bande : $ulRepos",
            ulRepos in ulLow..ulHigh)
    }

    @Test
    fun en_fm_l_arrondi_du_poste_ne_passe_pas_pour_un_geste() {
        // In FM the radio rounds to its tuning step (1 kHz on an IC-9700), so
        // it reads back not quite what was written. With the linear 20 Hz
        // threshold our own commands would look like gestures and the software
        // would let go for good.
        val a = RxArbiter(moveHz = 1_500L)
        var t = 0L
        val consigne = 145_959_400L
        val arrondi = 145_960_000L   // what the radio actually shows

        a.commanded(consigne)
        a.observe(arrondi, t)
        repeat(25) {
            t += pasMs
            val geste = a.observe(arrondi, t)
            assertFalse("l'arrondi du poste a été pris pour un geste à $t ms", geste)
        }
        assertTrue("le logiciel n'a pas pris la main", a.driven)

        // And keeps it while Doppler runs, rounding included.
        repeat(40) {
            val suivant = consigne - 100L
            a.commanded(suivant)
            t += pasMs
            assertFalse("notre propre consigne arrondie a été prise pour un geste",
                a.observe(arrondi, t))
            assertTrue("nous avons lâché la molette tout seuls", a.driven)
        }
    }

    @Test
    fun en_fm_un_changement_de_canal_rend_la_main_et_devient_le_nouveau_repos() {
        // The other half: when the operator really changes channel (at least
        // 5 kHz in FM) the software lets go at once, then resumes from the new
        // channel, not the old one. That last point matters: resuming on the
        // old frequency would undo the gesture two seconds later.
        val a = RxArbiter(moveHz = 1_500L)
        var t = 0L
        val canal = 145_960_000L
        a.commanded(canal)
        a.observe(canal, t)
        repeat(25) { t += pasMs; a.observe(canal, t) }
        assertTrue(a.driven)

        val nouveau = canal + 5_000L
        t += pasMs
        assertTrue("le changement de canal n'a pas été vu", a.observe(nouveau, t))
        assertFalse("le logiciel n'a pas lâché la molette", a.driven)

        // The adopted rest is the new channel corrected for current Doppler:
        // exactly what the CAT loop does on reading.
        val rr = 3.5
        val repos = Doppler.restFromDownlink(nouveau, rr)
        assertTrue("le nouveau repos est resté sur l'ancien canal",
            abs(repos - canal) > 4_000L)
        assertTrue("l'aller-retour a perdu le canal",
            abs(Doppler.downlink(repos, rr) - nouveau) <= 1L)

        // Then silence returns control to the software, as in linear.
        val reprise = t + 2_000L
        while (t < reprise) {
            t += pasMs
            a.observe(nouveau, t)
        }
        assertTrue("le Doppler n'a pas repris après le geste", a.driven)
    }
}
