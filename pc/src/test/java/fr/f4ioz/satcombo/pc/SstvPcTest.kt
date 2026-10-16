/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvSon
import fr.f4ioz.satcombo.sstv.SstvTestSignal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** SSTV on the PC: which source feeds it, the settings, the pictures with their sound. */
class SstvPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sstvpc-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    private var maintenant = 1_800_000_000_000L
    private fun sstv(st: StockageMemoire = StockageMemoire()) =
        SstvPc(st, File(dossier, "sstv"), { SstvPc.Contexte("RS-44", "JN18FS", "F4IOZ", "") }) { maintenant }

    private val robot36 = SstvMode.byName("Robot 36")!!
    private fun signal(rate: Int): ShortArray {
        val m = robot36
        val img = IntArray(m.width * m.height) { i -> if (i / m.width < m.height / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return SstvTestSignal.encode(m, img, rate)
    }

    /** In blocks, the clock moving with the sound. */
    private fun verse(pcm: ShortArray, rate: Int, f: (ShortArray, Int) -> Unit) {
        val b = ShortArray(2048)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(b.size, pcm.size - i)
            System.arraycopy(pcm, i, b, 0, n)
            f(b, n)
            i += n
            maintenant += n * 1000L / rate
        }
    }

    @Test fun `auto prend la cle quand elle joue, sinon la carte`() {
        assertEquals("sdr", SstvPc.choisie("auto", sdrJoue = true))
        assertEquals("carte", SstvPc.choisie("auto", sdrJoue = false))
        assertEquals("carte", SstvPc.choisie("carte", sdrJoue = true))
        assertEquals("sdr", SstvPc.choisie("sdr", sdrJoue = false))
        assertEquals("carte", SstvPc.choisie("n'importe quoi", sdrJoue = false))
    }

    @Test fun `les reglages sont gardes, comme sur le telephone par defaut`() {
        val st = StockageMemoire()
        val s = sstv(st)
        assertEquals("auto", s.source)
        assertTrue(s.continu)
        assertTrue(s.nettoyage)
        assertNull(s.force)
        s.source = "sdr"; s.continu = false; s.nettoyage = false; s.force = robot36
        s.source = "inconnue"
        val t = sstv(st)
        assertEquals("sdr", t.source)
        assertFalse(t.continu)
        assertFalse(t.nettoyage)
        assertEquals(robot36, t.force)
    }

    @Test fun `le son de la cle donne une image, avec son propre son`() {
        val s = sstv()
        verse(signal(Dsp.AUDIO_RATE), Dsp.AUDIO_RATE) { b, n -> s.ecouteSdr(b, n, Dsp.AUDIO_RATE) }
        val i = s.images().single()
        assertEquals("Robot 36", i.mode)
        assertEquals("RS-44", i.satName)
        assertEquals("live", i.source)
        assertEquals("sdr", s.sourceActive)
        val png = s.fichier(i.fileName)!!
        assertTrue(SstvSon.fichier(png).isFile)
        assertEquals(1, s.etat.value.gardees)
        assertTrue(s.supprime(i.fileName))
        assertTrue(s.images().isEmpty())
        assertFalse(SstvSon.fichier(png).exists())
    }

    @Test fun `en auto, la carte se tait pendant que la cle joue - jamais deux sources dans le decodeur`() {
        val s = sstv()
        val bruit = ShortArray(2048) { ((it * 7919) % 2000 - 1000).toShort() }
        s.ecouteSdr(bruit, bruit.size, Dsp.AUDIO_RATE)
        // The card's picture arrives while the dongle plays: ignored.
        var carte = 0
        verse(signal(48_000), 48_000) { b, n ->
            s.ecouteSdr(bruit, bruit.size, Dsp.AUDIO_RATE)
            s.ecouteCarte(b, n, 48_000); carte++
        }
        assertTrue(carte > 0)
        assertEquals("sdr", s.sourceActive)
        assertTrue(s.images().isEmpty())
        // The dongle silent for a while: the card is listened to again, by a new decoder at its rate.
        maintenant += 5_000
        verse(signal(48_000), 48_000) { b, n -> s.ecouteCarte(b, n, 48_000) }
        assertEquals("carte", s.sourceActive)
        assertEquals(48_000, s.rateActif)
        assertEquals(1, s.images().size)
    }

    @Test fun `source carte - la cle est ignoree - source sdr - la carte est ignoree`() {
        val s = sstv()
        s.source = "carte"
        verse(signal(Dsp.AUDIO_RATE), Dsp.AUDIO_RATE) { b, n -> s.ecouteSdr(b, n, Dsp.AUDIO_RATE) }
        assertTrue(s.images().isEmpty())
        assertEquals("", s.sourceActive)
        s.source = "sdr"
        verse(signal(48_000), 48_000) { b, n -> s.ecouteCarte(b, n, 48_000) }
        assertTrue(s.images().isEmpty())
        assertNotNull(s.conseil(carteActive = true, sdrActive = false))
    }

    @Test fun `caler maintenant demande un mode et du son`() {
        val s = sstv()
        assertEquals("choisir un mode d'abord", s.cale())
        assertTrue(s.cale(robot36)!!.contains("aucun son"))
        val bruit = ShortArray(2048)
        s.ecouteCarte(bruit, bruit.size, 48_000)
        assertNull(s.cale(robot36))
        assertTrue(s.ecoute().decodage)
        s.abandonne()
        assertFalse(s.ecoute().decodage)
    }

    @Test fun `un conseil quand la source ne donne rien`() {
        val s = sstv()
        assertTrue(s.conseil(carteActive = false, sdrActive = false)!!.contains("Aucun son"))
        s.ecouteCarte(ShortArray(4096), 4096, 48_000)
        assertTrue(s.conseil(carteActive = true, sdrActive = false)!!.contains("muet"))
        s.actif = false
        assertTrue(s.conseil(carteActive = true, sdrActive = false)!!.contains("coupé"))
    }

    @Test fun `un enregistrement decode a nouveau donne ses images a l'heure de leur reception`() {
        val s = sstv()
        val pcm = ShortArray(22_050) + signal(22_050)
        val wav = File(dossier, "SatMe_ISS_20261003_134909Z.wav")
        SstvSon.ecritWav(wav, pcm, 22_050)
        assertEquals(1, s.decodeFichier(wav))
        val i = s.images().single()
        assertEquals("file", i.source)
        assertEquals(wav.name, i.recording)
        // Ends 1 + 36 s into a recording begun at 13:49:09 UTC: named at its end, as on the phone.
        assertTrue(i.fileName, i.fileName.contains("20261003_13494"))
    }
}
