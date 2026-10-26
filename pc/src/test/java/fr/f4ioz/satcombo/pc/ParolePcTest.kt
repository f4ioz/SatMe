/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.BullePhonie
import fr.f4ioz.satcombo.phonie.HauteurVoix
import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** « Traduction parlée » without Python: a fake synthetic voice, a fake speaker. */
class ParolePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-parole-" + System.nanoTime()).apply { mkdirs() }
    private val ouverts = ArrayList<ParolePc>()
    @After fun fin() { ouverts.forEach { it.arrete() }; dossier.deleteRecursively() }

    @Volatile private var horloge = 1_760_000_000_000L

    private fun voisee(rate: Int, s: Double, f0: (Double) -> Double): FloatArray {
        var ph = 0.0
        return FloatArray((rate * s).toInt()) { i ->
            val t = i.toDouble() / rate
            ph += 2 * PI * f0(t) / rate
            var v = 0.0; var k = 1
            while (k * f0(t) < 3500) { v += sin(k * ph) / k; k++ }
            (0.25 * v).toFloat()
        }
    }

    /** A voice at 200 Hz saying each phrase in 1 s per 10 characters. */
    private inner class Synthese : SyntheseVoix {
        val demandes = ArrayList<Triple<String, List<String>, Double>>()
        override fun dit(voix: String, locuteur: String, phrases: List<String>, longueur: Double): Pair<List<FloatArray>, Int> {
            demandes += Triple(voix, phrases, longueur)
            return phrases.map { voisee(22_050, (it.length / 10.0).coerceAtLeast(0.6) * longueur) { 200.0 } } to 22_050
        }
        override fun arrete() {}
    }

    private class Lecteur : ParolePc.Lecteur {
        val joues = ArrayList<Pair<ShortArray, Int>>()
        override fun joue(pcm: ShortArray, rate: Int) { synchronized(joues) { joues += pcm to rate } }
    }

    private val niveaux = ArrayList<Float>()

    private fun parole(s: SyntheseVoix? = Synthese(), l: Lecteur = Lecteur(), voix: Boolean = true, trouve: (String) -> String? = { null }): ParolePc {
        val install = InstallPhoniePc(dossier)
        if (voix) { install.voix.mkdirs(); listOf("fr_FR-siwis-medium", "fr_FR-gilles-low").forEach { File(install.voix, "$it.onnx").writeText(""); File(install.voix, "$it.onnx.json").writeText("{}") } }
        return ParolePc(StockageMemoire(), install, { "/bin/false" }, { "fr" }, { horloge }, trouve, { synchronized(niveaux) { niveaux += it } }, l,
            fabriqueSynthese = { s }, fabriqueClone = { null }).also { ouverts += it }
    }

    /** A Polish over heard just now: a man at 110 Hz rising, two segments, each translated. */
    private fun entendu(langue: String = "pl", ilYaMs: Long = 0, traduit: Boolean = true, source: String = "sdr"): ParolePc.Entendu {
        val pcm = voisee(16_000, 3.0) { t -> 105.0 + 15 * t }
        val m = listOf(ProtocolePhonie.Morceau(0.0, 1.4, "dzień dobry wszystkim", -0.2, 0.01, 1.0, if (traduit) "bonjour à tous" else ""),
            ProtocolePhonie.Morceau(1.6, 3.0, "tu SP9ABC", -0.2, 0.01, 1.0, if (traduit) "ici SP9ABC" else ""))
        val b = BullePhonie(1, horloge - 3000 - ilYaMs, 3.0, source, null, "", langue, 0.98, m.joinToString(" ") { it.texte },
            if (traduit) m.joinToString(" ") { it.traduction } else "", 0.9, 0.3, emptyList(), if (traduit) "fr" else "")
        return ParolePc.Entendu(b, pcm, m, "")
    }

    @Test fun `coupee par defaut, rien n est mis en file`() {
        val p = parole()
        assertFalse(p.actif)
        p.entendu(entendu())
        assertEquals(0, p.json().getInt("attente"))
    }

    @Test fun `un passage traduit est dit par phrase, rapproche de la voix entendue, le recepteur baisse`() {
        val s = Synthese(); val l = Lecteur()
        val p = parole(s, l)
        p.dis(entendu())
        // Phrase by phrase, the auto voice nearest a man's pitch.
        assertEquals("fr_FR-gilles-low", s.demandes[0].first)
        assertEquals(listOf("bonjour à tous", "ici SP9ABC"), s.demandes[0].second)
        assertEquals(1, l.joues.size)
        val (pcm, rate) = l.joues[0]
        val f0 = HauteurVoix.profil(HauteurVoix.suit(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)).f0Hz
        // From 200 Hz towards 112 Hz: 8 semitones at most (126 Hz), at least well below 200.
        assertTrue("hauteur $f0", f0 in 110.0..140.0)
        assertEquals(listOf(ParolePc.BAISSE, 1f), synchronized(niveaux) { niveaux.toList() })
        val d = p.depuis(0).single()
        assertTrue(d.getBoolean("audio")); assertEquals("bonjour à tous ici SP9ABC", d.getString("texte"))
        assertNotNull(p.son(d.getLong("seq")))
        assertTrue(p.latenceMs < 30_000)
    }

    @Test fun `intonation neutre sans retouche et longueur 1`() {
        val s = Synthese(); val l = Lecteur()
        val p = parole(s, l)
        p.intonation = "neutre"
        p.dis(entendu())
        assertEquals(1.0, s.demandes[0].third, 0.0)
        val (pcm, rate) = l.joues[0]
        assertEquals(200.0, HauteurVoix.profil(HauteurVoix.suit(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)).f0Hz, 4.0)
    }

    @Test fun `seulement le navigateur, rien sur le haut-parleur`() {
        val l = Lecteur()
        val p = parole(l = l)
        p.sortie = "navigateur"
        p.dis(entendu())
        assertTrue(l.joues.isEmpty())
        assertTrue(p.depuis(0).single().getBoolean("navigateur"))
    }

    @Test fun `langue etrangere seulement`() {
        val p = parole()
        p.actif = true
        assertNull(p.aDire(entendu(langue = "fr", traduit = false)))
        p.etrangere = false
        assertEquals(listOf("dzień dobry wszystkim", "tu SP9ABC"), p.aDire(entendu(langue = "fr", traduit = false))!!.map { it.second })
    }

    @Test fun `file de deux, perimees au-dela de 30 s`() {
        val s = Synthese(); val l = Lecteur()
        val p = parole(s, l)
        p.actif = true
        // Heard 40 s ago: not said.
        p.entendu(entendu(ilYaMs = 40_000))
        val fin = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < fin && p.json().getLong("perimees") == 0L) Thread.sleep(20)
        assertEquals(1L, p.json().getLong("perimees"))
        assertTrue(l.joues.isEmpty())
        p.entendu(entendu())
        val fin2 = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < fin2 && synchronized(l.joues) { l.joues.isEmpty() }) Thread.sleep(20)
        assertEquals(1, synchronized(l.joues) { l.joues.size })
    }

    @Test fun `sans voix ni moteur, le navigateur parle avec des indices`() {
        val l = Lecteur()
        val p = parole(s = null, l = l, voix = false)
        p.dis(entendu())
        val d = p.depuis(0).single()
        assertFalse(d.getBoolean("audio"))
        assertEquals("navigateur", d.getString("moteur"))
        // A man: a lower browser pitch.
        assertTrue(d.getDouble("hauteur") < 1.0)
        assertTrue(l.joues.isEmpty())
    }

    @Test fun `baisse partagee entre annonces et traduction`() {
        val vus = ArrayList<Float>()
        val b = BaisseSon { vus += it }
        b.regle("annonces", 0.3f); b.regle("parole", 0.25f); b.regle("annonces", 1f); b.regle("parole", 1f)
        assertEquals(listOf(0.3f, 0.25f, 0.25f, 1f), vus)
    }

    @Test fun `wav relu`() {
        val (x, rate) = ParolePc.litWav(fr.f4ioz.satcombo.phonie.ParoleTraduite.wav(shortArrayOf(0, 16384, -16384), 16_000))
        assertEquals(16_000, rate); assertEquals(3, x.size); assertEquals(0.5f, x[1], 1e-4f)
    }

    @Test fun `DATV a part, coupee d origine, seulement la traduction`() {
        val p = parole()
        p.actif = true
        assertFalse(p.datvActif)
        // The phonie's spoken translation on does not say the DATV's.
        p.arrete()
        p.entendu(entendu(source = "datv"))
        assertEquals(0, p.json().getInt("attente"))
        // Ours, heard in our language, is not said back on the DATV even when « every language » is chosen.
        p.etrangere = false
        assertNull(p.aDire(entendu(langue = "fr", traduit = false, source = "datv")))
        assertNotNull(p.aDire(entendu(langue = "fr", traduit = false)))
    }

    @Test fun `DATV, sa voix, son volume, la ou passe son son, le son d origine baisse`() {
        val s = Synthese(); val l = Lecteur()
        parole(s, l)   // the voices in place
        val gains = ArrayList<Double>()
        var p2: ParolePc? = null
        val l2 = object : ParolePc.Lecteur { override fun joue(pcm: ShortArray, rate: Int) { gains += p2!!.gainDatv; l.joue(pcm, rate) } }
        p2 = ParolePc(StockageMemoire(), InstallPhoniePc(dossier), { "/bin/false" }, { "fr" }, { horloge }, { null }, { synchronized(niveaux) { niveaux += it } }, l2,
            fabriqueSynthese = { s }, fabriqueClone = { null }).also { ouverts += it }
        p2.datvVoix = "fr_FR-siwis-medium"; p2.datvVolume = 40; p2.datvBaisse = 80
        p2.sortiesDatv = { setOf("pc", "navigateur") }
        assertEquals(1.0, p2.gainDatv, 0.0)
        p2.dis(entendu(source = "datv"))
        // The voice chosen for the DATV, not the phonie's « auto ».
        assertEquals("fr_FR-siwis-medium", s.demandes.last().first)
        // Played here, the picture's sound at 20 % meanwhile, back after; the SDR speaker untouched.
        assertEquals(listOf(0.2), gains.map { Math.round(it * 100) / 100.0 })
        assertEquals(1.0, p2.gainDatv, 0.0)
        assertTrue(synchronized(niveaux) { niveaux.isEmpty() })
        // Its level: 40 % of the phonie's 85 % peak.
        val crete = l.joues.last().first.maxOf { kotlin.math.abs(it.toInt()) } / 32767.0
        assertEquals(0.85 * 0.4, crete, 0.02)
        val d = p2.depuis(0).single()
        assertTrue(d.getBoolean("datv")); assertTrue(d.getBoolean("navigateur")); assertEquals(0.2, d.getDouble("baisse"), 1e-9)
        // Heard in a browser only: nothing on this PC.
        p2.sortiesDatv = { setOf("navigateur") }
        val n = l.joues.size
        p2.dis(entendu(source = "datv"))
        assertEquals(n, l.joues.size)
        // Heard nowhere (the sound cut): on this PC.
        p2.sortiesDatv = { emptySet() }
        p2.dis(entendu(source = "datv"))
        assertEquals(n + 1, l.joues.size)
        assertFalse(p2.depuis(0).last().getBoolean("navigateur"))
    }

    @Test fun `DATV, jamais par-dessus soi, seulement la plus recente, rien au-dela de 20 s`() {
        val s = Synthese()
        val enCours = java.util.concurrent.atomic.AtomicInteger(0)
        val maxEnsemble = java.util.concurrent.atomic.AtomicInteger(0)
        val joues = java.util.Collections.synchronizedList(ArrayList<Int>())
        val porte = java.util.concurrent.CountDownLatch(1)
        val l = object : ParolePc.Lecteur {
            override fun joue(pcm: ShortArray, rate: Int) {
                maxEnsemble.accumulateAndGet(enCours.incrementAndGet()) { a, b -> maxOf(a, b) }
                porte.await(5, java.util.concurrent.TimeUnit.SECONDS)
                joues += pcm.size
                enCours.decrementAndGet()
            }
        }
        val install = InstallPhoniePc(dossier).apply { voix.mkdirs(); listOf("fr_FR-siwis-medium").forEach { File(voix, "$it.onnx").writeText(""); File(voix, "$it.onnx.json").writeText("{}") } }
        val p = ParolePc(StockageMemoire(), install, { "/bin/false" }, { "fr" }, { horloge }, { null }, {}, l, fabriqueSynthese = { s }, fabriqueClone = { null }).also { ouverts += it }
        p.datvActif = true
        // Heard 25 s ago: too late for the DATV (it would be for the phonie's 30 s).
        p.entendu(entendu(source = "datv", ilYaMs = 22_000))
        val f0 = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < f0 && p.json().getLong("perimees") == 0L) Thread.sleep(20)
        assertEquals(1L, p.json().getLong("perimees"))
        // One speaks (held); three more come: only the last waits.
        p.entendu(entendu(source = "datv"))
        val f1 = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < f1 && enCours.get() == 0) Thread.sleep(20)
        assertEquals(1, enCours.get())
        repeat(3) { p.entendu(entendu(source = "datv")) }
        assertEquals(1, p.json().getInt("attente"))
        assertEquals(2L, p.json().getLong("sautees"))
        porte.countDown()
        val f2 = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < f2 && joues.size < 2) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(2, joues.size)
        assertEquals(1, maxEnsemble.get())
        // Off: the worker stops (the phonie's spoken translation is off too).
        p.datvActif = false
        assertEquals("coupé", p.etat)
    }
}
