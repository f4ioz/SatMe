/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.sdr.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClassifieurQo100Test {

    private val bavard = System.getenv("QO100_BAVARD") != null
    /** The report, also written under build/ (the build prints no test output). */
    private val rapport = StringBuilder()
    private fun println(s: String) { kotlin.io.println(s); rapport.append(s).append('\n') }
    private fun ecrit(nom: String) = runCatching { java.io.File("build/$nom").writeText(rapport.toString()) }

    /** One station of class [c] alone with the noise, [dureeS] long; what the analyser says of it. */
    private data class Essai(val vrai: ClasseQo100, val dit: ClasseQo100?, val confiance: Double, val parasites: Int, val trace: String)

    private fun essai(c: ClasseQo100, seed: Long, snrDb: Double, ailleurs: Boolean, dureeS: Double = 64.0): Essai {
        val sc = ScenesQo100(dureeS, seed)
        val (ciel, centre) = place(c, ailleurs)
        // The window somewhere around it, as the receiver happens to be tuned.
        val fenetre = ciel - 4_000.0 + sc.rnd.nextInt(8_000)
        val f = ciel - fenetre
        when (c) {
            ClasseQo100.PORTEUSE -> sc.porteuse(f, snrDb)
            ClasseQo100.CW -> sc.cw(f, snrDb, wpm = 14 + (seed % 4).toInt() * 4)
            ClasseQo100.FT8 -> sc.ft(f, snrDb, Ft8Signal.FT8)
            ClasseQo100.FT4 -> sc.ft(f, snrDb, Ft8Signal.FT4)
            ClasseQo100.PSK31 -> sc.bpsk(f, snrDb)
            ClasseQo100.RTTY -> sc.rtty(f, snrDb)
            ClasseQo100.SSTV -> sc.sstv(f, snrDb)
            ClasseQo100.NUMERIQUE_LARGE -> sc.ofdm(f, snrDb)
            ClasseQo100.BLU -> sc.blu(f, snrDb)
            ClasseQo100.BALISE -> if (ailleurs) sc.bpsk(f, snrDb, baud = 400.0, ralenti = 0.0) else sc.cw(f, snrDb, wpm = 20, texte = "QO100 BEACON")
            else -> {}
        }
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        sc.analyse(a)
        val l = a.signaux(sc.finMs, fenetre)
        val visee = ciel + centre
        val tol = if (c in setOf(ClasseQo100.BLU, ClasseQo100.SSTV, ClasseQo100.NUMERIQUE_LARGE)) 500.0 else 120.0
        val s = l.filter { abs(it.hz - visee) <= tol }.minByOrNull { abs(it.hz - visee) }
        val trace = buildString {
            append("$c snr=$snrDb ailleurs=$ailleurs → ${s?.classe} ${s?.confiance} « ${s?.details} » ${s?.largeurHz} Hz")
            if (bavard) {
                for (p in a.pistes()) a.mesures(p)?.let { m -> append("\n   #${p.id} $m\n      ${ClassifieurQo100.scores(m).entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}=${"%.2f".format(it.value)}" }}") }
                append("\n   tous: ${l.joinToString { "${it.hz - visee.toLong()}:${it.classe}" }}")
            }
        }
        return Essai(c, s?.classe, s?.confiance ?: 0.0, l.count { it !== s }, trace)
    }

    /** Where class [c] usually sits (its sky frequency, carrier or base) and its centre from there. */
    private fun place(c: ClasseQo100, ailleurs: Boolean): Pair<Double, Double> = when (c) {
        ClasseQo100.PORTEUSE -> (if (ailleurs) 10_489_720_000.0 else 10_489_530_000.0) to 0.0
        ClasseQo100.CW -> (if (ailleurs) 10_489_702_000.0 else 10_489_520_000.0) to 0.0
        ClasseQo100.FT8 -> (if (ailleurs) 10_489_900_000.0 else 10_489_541_000.0) to 22.0
        ClasseQo100.FT4 -> (if (ailleurs) 10_489_620_000.0 else 10_489_548_000.0) to 31.0
        ClasseQo100.PSK31 -> (if (ailleurs) 10_489_880_000.0 else 10_489_570_000.0) to 0.0
        ClasseQo100.RTTY -> (if (ailleurs) 10_489_800_000.0 else 10_489_600_000.0) to 0.0
        ClasseQo100.SSTV -> (if (ailleurs) 10_489_920_000.0 else 10_489_630_000.0) to 1_750.0
        ClasseQo100.NUMERIQUE_LARGE -> (if (ailleurs) 10_489_700_000.0 else 10_489_640_000.0) to 0.0
        ClasseQo100.BLU -> (if (ailleurs) 10_489_870_000.0 else 10_489_700_000.0) to 1_300.0
        ClasseQo100.BALISE -> (if (ailleurs) Qo100.BALISE_MEDIANE_HZ.toDouble() else Qo100.BALISE_BASSE_HZ.toDouble()) to 0.0
        else -> 0.0 to 0.0
    }

    private val classes = listOf(ClasseQo100.BLU, ClasseQo100.CW, ClasseQo100.FT8, ClasseQo100.FT4, ClasseQo100.PSK31,
        ClasseQo100.RTTY, ClasseQo100.SSTV, ClasseQo100.PORTEUSE, ClasseQo100.BALISE, ClasseQo100.NUMERIQUE_LARGE)

    @Test fun `matrice de confusion sur des signaux synthetiques`() {
        val snrs = listOf(4.0, 7.0, 10.0, 14.0, 20.0, 6.0)
        val choix = System.getenv("QO100_CLASSES")?.split(",")?.toSet()
        val cas = classes.filter { choix == null || it.cle in choix }.flatMap { c -> snrs.indices.map { c to it } }
        // Each scene on its own: three at once (a scene is ~100 MB while it is made).
        val pool = java.util.concurrent.ForkJoinPool(3)
        val essais = try {
            pool.submit<List<Essai>> {
                cas.parallelStream().map { (c, k) -> essai(c, 1000L * c.ordinal + k, snrs[k], ailleurs = k % 2 == 1) }
                    .collect(java.util.stream.Collectors.toList())
            }.get()
        } finally { pool.shutdown() }
        for (e in essais) if (bavard || e.dit != e.vrai) println(e.trace)
        val cols = classes + listOf(ClasseQo100.INCONNU)
        println("Matrice de confusion (lignes : vrai ; colonnes : dit ; « rien » : pas trouvé)")
        println("%-16s".format("") + cols.joinToString("") { "%6s".format(it.cle.take(6)) } + "  rien")
        for (c in classes) {
            val l = essais.filter { it.vrai == c }
            println("%-16s".format(c.cle) + cols.joinToString("") { d -> "%6d".format(l.count { it.dit == d }) } + "%6d".format(l.count { it.dit == null }))
        }
        val parasites = essais.sumOf { it.parasites }
        println("Signaux en trop sur ${essais.size} essais : $parasites")
        ecrit("qo100-matrice.txt")
        for (c in classes) {
            val l = essais.filter { it.vrai == c }
            val bons = l.count { it.dit == c }
            assertTrue("$c : $bons / ${l.size}", bons >= PLANCHER.getValue(c))
            // A wrong answer is worse than « inconnu ».
            assertTrue("$c pris pour autre chose", l.count { it.dit != null && it.dit != c && it.dit != ClasseQo100.INCONNU } <= 1)
        }
        assertTrue("signaux en trop : $parasites", parasites <= essais.size / 4)
    }

    /** A busy transponder: stations side by side, FT8 every 100 Hz, two CW 300 Hz apart, voices 3.5 kHz apart. */
    @Test fun `un transpondeur charge`() {
        val sc = ScenesQo100(64.0, 77)
        val centre = 10_489_560_000.0
        val vrais = listOf(
            10_489_530_000.0 to ClasseQo100.CW, 10_489_530_300.0 to ClasseQo100.CW,
            10_489_541_000.0 to ClasseQo100.FT8, 10_489_541_100.0 to ClasseQo100.FT8, 10_489_541_200.0 to ClasseQo100.FT8,
            10_489_541_300.0 to ClasseQo100.FT8, 10_489_548_000.0 to ClasseQo100.FT4,
            10_489_570_000.0 to ClasseQo100.PSK31, 10_489_570_120.0 to ClasseQo100.PSK31,
            10_489_575_000.0 to ClasseQo100.BLU, 10_489_578_500.0 to ClasseQo100.BLU,
            10_489_585_000.0 to ClasseQo100.RTTY, 10_489_590_000.0 to ClasseQo100.PORTEUSE)
        val snr = listOf(12.0, 8.0, 10.0, 6.0, 14.0, 9.0, 11.0, 10.0, 7.0, 15.0, 12.0, 12.0, 18.0)
        for ((k, v) in vrais.withIndex()) {
            val f = v.first - centre
            when (v.second) {
                ClasseQo100.CW -> sc.cw(f, snr[k], wpm = 16 + 6 * k)
                ClasseQo100.FT8 -> sc.ft(f, snr[k], Ft8Signal.FT8, parite = k % 2)
                ClasseQo100.FT4 -> sc.ft(f, snr[k], Ft8Signal.FT4)
                ClasseQo100.PSK31 -> sc.bpsk(f, snr[k])
                ClasseQo100.BLU -> sc.blu(f, snr[k])
                ClasseQo100.RTTY -> sc.rtty(f, snr[k])
                else -> sc.porteuse(f, snr[k])
            }
        }
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        sc.analyse(a)
        val l = a.signaux(sc.finMs, centre)
        var bons = 0; var faux = 0
        for ((f, c) in vrais) {
            val visee = f + when (c) { ClasseQo100.FT8 -> 22.0; ClasseQo100.FT4 -> 31.0; ClasseQo100.BLU -> 1_300.0; else -> 0.0 }
            val tol = if (c == ClasseQo100.BLU) 600.0 else 45.0
            // A block of FT8 stations side by side counts for each of them.
            val s = l.filter { abs(it.hz - visee) <= tol || (c == ClasseQo100.FT8 && abs(it.hz - visee) <= it.largeurHz / 2.0) }
                .minByOrNull { abs(it.hz - visee) }
            println("chargé : $c ${(f - centre).toLong()} → ${s?.classe} ${s?.confiance} « ${s?.details} »")
            if (s?.classe == c) bons++ else if (s != null && s.classe != ClasseQo100.INCONNU) faux++
        }
        println("chargé : $bons / ${vrais.size} justes, $faux faux, ${l.size} signaux : ${l.joinToString { "${(it.hz - centre).toLong()}:${it.classe.cle}" }}")
        ecrit("qo100-charge.txt")
        assertTrue("$bons justes", bons >= vrais.size - 3)
        assertTrue("$faux faux", faux <= 2)
    }

    @Test fun `rien sur du bruit seul`() {
        for (seed in 1L..3L) {
            val sc = ScenesQo100(40.0, seed)
            val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
            sc.analyse(a)
            val l = a.signaux(sc.finMs, 10_489_700_000.0)
            assertEquals("bruit $seed : $l", 0, l.size)
        }
    }

    /** A step in the noise floor (a passband edge, a slope) is not a station of any kind. */
    @Test fun `rien sur une marche de bruit`() {
        for ((seed, largeur) in listOf(4L to 20_000.0, 5L to 3_000.0, 6L to 1_200.0)) {
            val sc = ScenesQo100(40.0, seed)
            sc.marche(-5_000.0, -5_000.0 + largeur, 4.0)
            val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
            sc.analyse(a)
            val l = a.signaux(sc.finMs, 10_489_700_000.0)
            println("marche $largeur Hz : $l")
            assertTrue("marche $largeur : $l", l.all { it.classe == ClasseQo100.INCONNU })
            assertTrue("marche $largeur : $l", l.size <= 1)
        }
    }

    @Test fun `les numeros restent les memes`() {
        val sc = ScenesQo100(30.0, 7)
        sc.porteuse(3_000.0, 15.0)
        sc.cw(-5_000.0, 15.0)
        val a = AnalyseurQo100(ScenesQo100.CADENCE, ScenesQo100.TAILLE)
        val moitie = ScenesQo100(15.0, 7)
        System.arraycopy(sc.i, 0, moitie.i, 0, moitie.n); System.arraycopy(sc.q, 0, moitie.q, 0, moitie.n)
        moitie.analyse(a)
        val avant = a.signaux(moitie.finMs, 10_489_700_000.0).associate { it.hz / 100 to it.id }
        val reste = ScenesQo100(15.0, 7, t0Ms = moitie.finMs)
        System.arraycopy(sc.i, moitie.n, reste.i, 0, reste.n); System.arraycopy(sc.q, moitie.n, reste.q, 0, reste.n)
        reste.analyse(a)
        val apres = a.signaux(reste.finMs, 10_489_700_000.0)
        assertEquals(2, avant.size)
        for (s in apres) assertTrue("$s", s.id in avant.values)
    }

    /** What it costs at the receiver's real rate: a 65 536-point frame every 62 ms. */
    @Test fun `cout a la cadence du recepteur`() {
        val a = AnalyseurQo100(Dsp.RTL_RATE.toDouble())
        a.bande(65_536 / 2 - 15_800, 65_536 / 2 + 15_800)
        val rnd = java.util.Random(3)
        // Fresh noise (a block repeated would make a comb of lines), with a few carriers.
        val blocsBruit = Array(37) { ByteArray(16_384) { (127 + rnd.nextGaussian() * 6).toInt().coerceIn(0, 255).toByte() } }
        // Warm up first: the JIT.
        for (k in 0 until 300) a.pousse8(blocsBruit[k % blocsBruit.size], 16_384, 1_000L + k * 8)
        val trames0 = a.trames; val nanos0 = a.nanos
        val secondes = 10.0
        val blocs = (secondes * Dsp.RTL_RATE / 8192).toInt()
        val t0 = System.nanoTime()
        for (k in 0 until blocs) a.pousse8(blocsBruit[k % blocsBruit.size], 16_384, 3_400L + k * 8)
        val s = (System.nanoTime() - t0) / 1e9
        println("Analyse : ${"%.1f".format(s / secondes * 100)} % d'un cœur (${a.trames - trames0} trames, ${"%.2f".format((a.nanos - nanos0) / 1e6 / (a.trames - trames0))} ms par trame)")
        ecrit("qo100-cout.txt")
        assertTrue(s / secondes < 0.5)
    }

    companion object {
        /** Right answers wanted out of six, per class (all six today; one miss allowed for the noise's luck). */
        val PLANCHER = ClasseQo100.entries.associateWith { 5 }
    }
}
