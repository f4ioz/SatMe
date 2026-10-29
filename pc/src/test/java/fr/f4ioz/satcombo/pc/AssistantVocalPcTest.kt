/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.ParoleTraduite
import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The voice assistant without a microphone: generated sound (a WAV written and read back), a fake speech engine,
 * the wake word, the push-to-talk button. Never the PC's own microphone.
 */
class AssistantVocalPcTest {

    @get:Rule val tmp = TemporaryFolder()

    /** [s] seconds of a voice-like sound (a 220 Hz buzz with harmonics, its loudness moving) at [rate]. */
    private fun voix(s: Double, rate: Int, amp: Double = 0.3) = ShortArray((s * rate).toInt()) { i ->
        val t = i.toDouble() / rate
        val env = 0.6 + 0.4 * Math.sin(2 * Math.PI * 3 * t)
        (amp * env * (Math.sin(2 * Math.PI * 220 * t) + 0.5 * Math.sin(2 * Math.PI * 440 * t) + 0.25 * Math.sin(2 * Math.PI * 660 * t)) / 1.75 * 32767).toInt().toShort()
    }
    /** A quiet room: a little noise. */
    private fun calme(s: Double, rate: Int, rnd: java.util.Random = java.util.Random(7)) = ShortArray((s * rate).toInt()) { (rnd.nextGaussian() * 40).toInt().toShort() }

    /** The sound as a WAV file, read back: what a test recording would be. */
    private fun parWav(pcm: ShortArray, rate: Int): ShortArray {
        val f = File(tmp.root, "essai-" + System.nanoTime() + ".wav")
        f.writeBytes(ParoleTraduite.wav(pcm, rate))
        val b = f.readBytes()
        val n = (b.size - 44) / 2
        return ShortArray(n) { i -> ((b[44 + 2 * i + 1].toInt() shl 8) or (b[44 + 2 * i].toInt() and 0xFF)).toShort() }
    }

    @Test fun le_segmenteur_coupe_les_morceaux_de_voix() {
        val rate = ProtocolePhonie.RATE
        val s = Segmenteur(rate)
        val son = calme(1.0, rate) + voix(1.2, rate) + calme(1.0, rate) + voix(0.1, rate) + calme(1.0, rate) + voix(0.8, rate) + calme(1.0, rate)
        val r = Reechantillonneur(rate, rate)
        val morceaux = ArrayList<FloatArray>()
        son.toList().chunked(640).forEach { c -> morceaux += s.ajoute(r.convertit(c.toShortArray(), c.size)) }
        // Two pieces: the 0.1 s click is too short.
        assertEquals(2, morceaux.size)
        // Each with 0.3 s before and the 0.7 s of quiet after.
        assertEquals(1.2 + 1.0, morceaux[0].size / rate.toDouble(), 0.15)
        assertEquals(0.8 + 1.0, morceaux[1].size / rate.toDouble(), 0.15)
    }

    @Test fun le_reechantillonneur() {
        val r = Reechantillonneur(48_000, 16_000)
        val x = voix(1.0, 48_000)
        var n = 0
        x.toList().chunked(1920).forEach { c -> n += r.convertit(c.toShortArray(), c.size).size }
        assertEquals(16_000.0, n.toDouble(), 3.0)
    }

    @Test fun le_mot_de_reveil() {
        assertEquals(true to "prochain passage de l'ISS", MotDeReveil.cherche("SatMe, prochain passage de l'ISS"))
        assertEquals(true to "état de la DATV ?", MotDeReveil.cherche("Satmi, état de la DATV ?"))
        assertEquals(true to "dernier QSO", MotDeReveil.cherche("Hey Sat Me dernier QSO"))
        assertEquals(true to "", MotDeReveil.cherche("Satmé."))
        assertEquals(true to "next pass", MotDeReveil.cherche("OK SatMe next pass"))
        assertFalse(MotDeReveil.cherche("Samedi on part en portable").first)
        assertFalse(MotDeReveil.cherche("prochain passage de l'ISS").first)
        assertFalse(MotDeReveil.cherche("satellite en vue").first)
        assertTrue(MotDeReveil.parasite("Sous-titres réalisés par la communauté d'Amara.org"))
        assertFalse(MotDeReveil.parasite("SatMe, dernier QSO"))
    }

    /** A speech engine that writes, one after the other, the sentences given. */
    private class MoteurFictif(val phrases: List<String>) : MoteurPhonie {
        var n = 0
        val durees = ArrayList<Double>()
        override fun demarre() = ProtocolePhonie.Message.Pret("base", "cpu", "int8", 0.1, "")
        override fun transcrit(id: Long, pcm: FloatArray, langue: String, traduire: Boolean): ProtocolePhonie.Message {
            durees += pcm.size / ProtocolePhonie.RATE.toDouble()
            val t = phrases.getOrElse(n++) { "" }
            return ProtocolePhonie.Message.Reponse(id, langue, 0.95, 0.1, listOf(ProtocolePhonie.Morceau(0.0, 1.0, t, -0.2, 0.02)), "")
        }
        override fun arrete() {}
    }

    private class Banc(val a: AssistantVocalPc, val monde: MondeFictif, val dits: MutableList<String>, val ouvertures: MutableList<String>,
                       val moteur: MoteurFictif, val c: CommandesPc)

    /** [tempsReel]: the sound fed at its own pace (SatMe's answer makes the microphone deaf a moment, as in a room). */
    private fun banc(phrases: List<String>, sons: List<ShortArray>, rate: Int = 48_000, tempsReel: Boolean = false): Banc {
        val st = StockageMemoire()
        val install = InstallPhoniePc(tmp.newFolder())
        // The model « base » there (a fake file: nothing is loaded by the fake engine).
        File(install.modeles, "models--Systran--faster-whisper-base/snapshots/abc").apply { mkdirs(); File(this, "model.bin").writeText("x") }
        val monde = MondeFictif()
        val c = CommandesPc(monde) { ZoneId.of("Europe/Paris") }
        val dits = CopyOnWriteArrayList<String>()
        val ouvertures = CopyOnWriteArrayList<String>()
        val moteur = MoteurFictif(phrases)
        val a = AssistantVocalPc(st, install, c, { "/usr/bin/python3" },
            ouvre = { nom, recoit ->
                ouvertures += nom
                val marche = java.util.concurrent.atomic.AtomicBoolean(true)
                thread(isDaemon = true) {
                    for (son in sons) {
                        val x = parWav(son, rate)
                        x.toList().chunked(rate / 25).forEach { b -> if (marche.get()) { recoit(b.toShortArray(), b.size, rate); if (tempsReel) Thread.sleep(40) } }
                    }
                }
                ({ marche.set(false); Unit }) to ""
            },
            fabriqueMoteur = { moteur },
            dit = { t, _ -> dits += t; true },
            module = { true })
        a.langue = "fr"
        return Banc(a, monde, dits, ouvertures, moteur, c)
    }

    private fun attend(f: () -> Boolean) { val fin = System.currentTimeMillis() + 10_000; while (!f() && System.currentTimeMillis() < fin) Thread.sleep(20) }

    @Test fun satme_puis_la_question_dans_la_meme_phrase() {
        val b = banc(listOf("Satmi, prochain passage de SO cinquante", "Il fait beau aujourd'hui"),
            listOf(calme(0.5, 48_000) + voix(1.5, 48_000) + calme(1.0, 48_000) + voix(1.0, 48_000) + calme(1.0, 48_000)))
        assertTrue(b.a.pret())
        b.a.ecoute = true
        attend { b.moteur.n >= 2 && b.dits.isNotEmpty() }
        assertEquals(listOf(""), b.ouvertures)
        // The first piece was a command, the second (no wake word) ignored.
        assertEquals(2, b.moteur.n)
        assertEquals(listOf("Prochain passage de SO-50 à 13 h 25, dans 25 minutes, 47 degrés au plus haut."), b.dits.toList())
        val t = b.c.traces()
        assertEquals(1, t.size)
        assertEquals("voix", t[0].source)
        assertEquals(Intention.PROCHAIN_PASSAGE, t[0].reponse.intention)
        b.a.ecoute = false
        assertFalse(b.a.json().getBoolean("micOuvert"))
    }

    @Test fun satme_seul_puis_la_commande() {
        val b = banc(listOf("SatMe.", "coupe le son"), listOf(calme(0.5, 48_000) + voix(0.8, 48_000) + calme(1.5, 48_000) + voix(1.0, 48_000) + calme(1.0, 48_000)),
            tempsReel = true)
        b.a.ecoute = true
        attend { b.dits.size >= 2 }
        assertEquals(listOf("Oui ?", "Son coupé."), b.dits.toList())
        assertEquals(listOf("son true"), b.monde.gestes)
        b.a.arrete()
    }

    @Test fun le_bouton_sans_mot_de_reveil_et_jamais_d_emission() {
        val b = banc(listOf("passe en émission sur QO-100"), listOf(calme(0.5, 48_000) + voix(1.2, 48_000) + calme(1.0, 48_000)))
        // Not listening: the button opens the microphone for the next sentence only.
        assertFalse(b.a.ecoute)
        assertTrue(b.a.bouton().getBoolean("ok"))
        attend { b.dits.isNotEmpty() }
        assertEquals(listOf(CommandesPc.EMISSION_FR), b.dits.toList())
        assertTrue(b.monde.gestes.isEmpty())
        b.a.arrete()
    }

    @Test fun rien_sans_le_module_ni_dans_la_demonstration() {
        val st = StockageMemoire()
        val a = AssistantVocalPc(st, InstallPhoniePc(tmp.newFolder()), CommandesPc(MondeFictif()), { null },
            ouvre = { _, _ -> throw AssertionError("micro ouvert") }, module = { false })
        a.ecoute = true
        a.revise()
        assertFalse(a.bouton().getBoolean("ok"))
        assertFalse(a.json().getBoolean("micOuvert"))
        assertFalse(a.installe().getBoolean("ok"))
    }
}
