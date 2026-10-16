/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the real transponder showed and the clean scenes did not: a keyed
 * signal's clicks, a beacon's second line, a comb of weak lines every
 * 100 Hz from the receiver chain, a sloped floor, signals gone.
 */
class ArtefactsQo100Test {

    private val rapport = StringBuilder()
    private fun note(s: String) { println(s); rapport.append(s).append('\n') }
    private fun ecrit(nom: String) = runCatching { java.io.File("build/$nom").writeText(rapport.toString()) }

    private fun analyse(sc: ScenesQo100, centre: Double, finMs: Long = sc.finMs): List<SignalQo100> {
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        sc.analyse(a)
        return a.signaux(finMs, centre)
    }

    private fun nomme(l: List<SignalQo100>) = l.filter { it.classe != ClasseQo100.INCONNU }

    private fun texte(l: List<SignalQo100>, centre: Double) = l.joinToString { "${(it.hz - centre).toLong()}:${it.classe.cle}/${it.snrDb}/${it.snrBandeDb}" }

    @Test fun `une CW manipulee sec avec du QSB est une seule CW`() {
        for (seed in 21L..23L) {
            val sc = ScenesQo100(48.0, seed)
            val centre = 10_489_540_000.0
            // Hard keying (0.1 ms edges): clicks over kilohertz; a 10 dB fade.
            sc.cw(-20_000.0, 28.0, wpm = 16 + seed.toInt() % 3 * 4, fronteS = 0.0001, qsbDb = 10.0)
            val l = analyse(sc, centre)
            note("CW sèche $seed : ${texte(l, centre)}")
            val cw = l.filter { abs(it.hz - (centre - 20_000)) < 120 }
            assertEquals("CW sèche $seed : $l", 1, cw.size)
            assertEquals(ClasseQo100.CW, cw[0].classe)
            assertEquals("rien d'autre $seed : $l", 1, l.size)
        }
        ecrit("qo100-artefacts-cw.txt")
    }

    @Test fun `une balise et ses raies voisines sont une seule balise`() {
        for (seed in 31L..33L) {
            val sc = ScenesQo100(48.0, seed)
            val centre = Qo100.BALISE_BASSE_HZ + 20_000.0
            // A second line 395 Hz above keyed with it, as heard; keyed hard (one piste over both), or soft (two pistes).
            sc.cw(-20_000.0, 22.0, wpm = 20, texte = "QO100 BEACON", fronteS = if (seed == 33L) 0.005 else 0.0002, pauseS = 0.3,
                copies = listOf(395.0 to -1.0))
            val l = analyse(sc, centre)
            note("balise $seed : ${texte(l, centre)}")
            val pres = l.filter { abs(it.hz - Qo100.BALISE_BASSE_HZ) < 2_000 }
            assertEquals("balise $seed : $l", 1, pres.size)
            assertEquals(ClasseQo100.BALISE, pres[0].classe)
            assertEquals("rien d'autre $seed : $l", 1, l.size)
        }
        ecrit("qo100-artefacts-balise.txt")
    }

    @Test fun `un peigne de raies faibles tous les 100 Hz n'est pas de la CW`() {
        for (seed in 41L..42L) {
            val sc = ScenesQo100(40.0, seed)
            val centre = 10_489_530_000.0
            sc.peigne(-18_000.0, 4_000.0, 100.0, -15.0, -6.0)
            val l = analyse(sc, centre)
            note("peigne $seed : ${l.size} : ${texte(l, centre)}")
            assertTrue("peigne $seed : CW ${texte(l, centre)}", l.none { it.classe == ClasseQo100.CW })
            assertTrue("peigne $seed : ${texte(l, centre)}", nomme(l).isEmpty())
            assertTrue("peigne $seed : ${texte(l, centre)}", l.size <= 2)
        }
        ecrit("qo100-artefacts-peigne.txt")
    }

    /** Stronger comb lines, well over the threshold: only their being on one grid tells. */
    @Test fun `un peigne fort n'est pas une foule de stations`() {
        val sc = ScenesQo100(40.0, 43)
        val centre = 10_489_530_000.0
        sc.peigne(-18_000.0, -6_000.0, 100.0, -7.0, 0.0)
        val l = analyse(sc, centre)
        note("peigne fort : ${l.size} : ${texte(l, centre)}")
        assertTrue("peigne fort : ${texte(l, centre)}", nomme(l).size <= 1)
        assertTrue("peigne fort : ${texte(l, centre)}", l.size <= 2)
    }

    /** A transmitter's spurious lines (250 Hz off 12 dB down, 520 Hz off 15 dB down) keyed with it: one CW. */
    @Test fun `une CW et sa raie parasite sont une seule CW`() {
        val sc = ScenesQo100(48.0, 81)
        val centre = 10_489_520_000.0
        sc.cw(3_000.0, 12.0, wpm = 22, copies = listOf(-250.0 to -12.0, 520.0 to -15.0))
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        sc.analyse(a)
        val l = a.signaux(sc.finMs, centre)
        note("CW et parasites : ${a.pistes().size} pistes : ${texte(l, centre)}")
        // Seen apart, then put together.
        assertTrue("${a.pistes().size} pistes", a.pistes().size >= 2)
        assertEquals("${texte(l, centre)}", 1, l.size)
        assertEquals(ClasseQo100.CW, l[0].classe)
        assertTrue("${texte(l, centre)}", abs(l[0].hz - (centre + 3_000)) < 60)
    }

    @Test fun `une vraie CW au milieu du peigne reste seule`() {
        val sc = ScenesQo100(48.0, 51)
        val centre = 10_489_530_000.0
        sc.peigne(-18_000.0, 4_000.0, 100.0, -15.0, -6.0)
        sc.cw(-9_040.0, 10.0, wpm = 18)
        sc.porteuse(-4_960.0, 12.0)
        val l = analyse(sc, centre)
        note("peigne et CW : ${texte(l, centre)}")
        val cw = l.filter { abs(it.hz - (centre - 9_040)) < 60 }
        assertEquals("$l", 1, cw.size)
        assertEquals(ClasseQo100.CW, cw[0].classe)
        assertTrue("porteuse : ${texte(l, centre)}", l.any { abs(it.hz - (centre - 4_960)) < 60 && it.classe == ClasseQo100.PORTEUSE })
        assertTrue("trop : ${texte(l, centre)}", nomme(l).size == 2)
        assertTrue("trop : ${texte(l, centre)}", l.size <= 3)
        ecrit("qo100-artefacts-peigne-cw.txt")
    }

    @Test fun `un plancher en pente n'est rien`() {
        val sc = ScenesQo100(40.0, 61)
        // The floor rising in steps of 1.5 dB every 3 kHz: a passband's slope.
        for (k in 0 until 4) sc.marche(-12_000.0 + 3_000.0 * k, 12_000.0, 1.5)
        val l = analyse(sc, 10_489_700_000.0)
        note("pente : ${texte(l, 10_489_700_000.0)}")
        assertTrue("pente : $l", nomme(l).isEmpty())
        assertTrue("pente : $l", l.size <= 1)
    }

    @Test fun `un signal parti disparait de la liste en quelques secondes`() {
        val sc = ScenesQo100(40.0, 71)
        val centre = 10_489_520_000.0
        sc.cw(2_000.0, 15.0, wpm = 18)
        // The CW stops at 20 s.
        for (k in (20 * ScenesQo100.CADENCE).toInt() until sc.n) { sc.i[k] = (sc.sigma * sc.rnd.nextGaussian()).toFloat(); sc.q[k] = (sc.sigma * sc.rnd.nextGaussian()).toFloat() }
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        val moitie = ScenesQo100(19.0, 71)
        System.arraycopy(sc.i, 0, moitie.i, 0, moitie.n); System.arraycopy(sc.q, 0, moitie.q, 0, moitie.n)
        moitie.analyse(a)
        val avant = a.signaux(moitie.finMs, centre)
        val reste = ScenesQo100(21.0, 71, t0Ms = moitie.finMs)
        System.arraycopy(sc.i, moitie.n, reste.i, 0, reste.n); System.arraycopy(sc.q, moitie.n, reste.q, 0, reste.n)
        reste.analyse(a)
        val apres = a.signaux(reste.finMs, centre)
        assertTrue("avant : $avant", avant.any { it.classe == ClasseQo100.CW })
        assertTrue("après : $apres", apres.isEmpty())
    }
}
