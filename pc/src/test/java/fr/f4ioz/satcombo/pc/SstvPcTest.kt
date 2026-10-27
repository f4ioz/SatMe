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
        // The rig's own card hearing the satellite wins over the dongle in « auto », not over a choice.
        assertEquals("carte", SstvPc.choisie("auto", sdrJoue = true, posteEntend = true))
        assertEquals("sdr", SstvPc.choisie("sdr", sdrJoue = true, posteEntend = true))
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

    @Test fun `calage automatique - coupe par defaut, il recale la cle et revient a zero quand on le coupe`() {
        val st = StockageMemoire()
        val s = sstv(st)
        assertFalse(s.afc)
        // A dongle in USB, mistuned: the tones arrive 180 Hz high; its fine offset moves them.
        var decalage = 0L
        s.cibleAfc = { src ->
            if (src != SstvPc.SDR) null else object : fr.f4ioz.satcombo.sstv.CibleAfc {
                override fun sens() = 1
                override val couplee = true
                override fun position() = decalage
                override fun deplace(hz: Int) { decalage += hz }
            }
        }
        s.afc = true
        assertTrue(sstv(st).afc)
        val rate = Dsp.AUDIO_RATE
        val brut = signal(rate)
        val d = fr.f4ioz.satcombo.sstv.AfcBanc.Decaleur(rate)
        val y = ShortArray(2048)
        verse(brut, rate) { b, n -> d.traite(b, n, y, 180.0 - decalage); s.ecouteSdr(y, n, rate) }
        assertEquals(180.0, decalage.toDouble(), 10.0)
        val c = s.calage("")
        assertTrue(c.actif && c.applique)
        assertEquals(decalage.toInt(), c.totalHz)
        assertEquals("Robot 36", s.images().single().mode)
        // Off: the dongle back where it was.
        s.afc = false
        assertEquals(0L, decalage)
    }

    @Test fun `calage automatique - sans recepteur a bouger, un conseil`() {
        assertEquals("Accorder de +120 Hz.", SstvPc.conseilAfc(true, 120, false, 1))
        assertEquals("Accorder de −120 Hz.", SstvPc.conseilAfc(true, 120, false, -1))
        assertEquals("", SstvPc.conseilAfc(true, 120, true, 1))
        assertEquals("", SstvPc.conseilAfc(true, 10, false, 1))
        assertTrue(SstvPc.conseilAfc(true, -90, false, 0).contains("en FM"))
    }

    @Test fun `les images du generateur d'essai ne sont jamais gardees`() {
        val s = sstv()
        s.horsAntenne = { src -> if (src == SstvPc.SDR) SstvPc.HorsAntenne(SstvPc.ESSAI) else null }
        verse(signal(Dsp.AUDIO_RATE), Dsp.AUDIO_RATE) { b, n -> s.ecouteSdr(b, n, Dsp.AUDIO_RATE) }
        assertTrue(s.images().isEmpty())
        assertEquals(1, s.etat.value.essais)
        assertEquals(0, s.etat.value.gardees)
        assertEquals("", s.etat.value.derniere)
    }

    @Test fun `une image d'un enregistrement rejoue est marquee, a sa date, sans nouvelle image annoncee`() {
        val s = sstv()
        val quand = 1_790_000_000_000L
        s.horsAntenne = { SstvPc.HorsAntenne(SstvPc.RELECTURE, quand, "satme-iq-x.sigmf-data") }
        verse(signal(Dsp.AUDIO_RATE), Dsp.AUDIO_RATE) { b, n -> s.ecouteSdr(b, n, Dsp.AUDIO_RATE) }
        val i = s.images().single()
        assertEquals(SstvPc.RELECTURE, i.source)
        assertEquals(quand, i.timeMs)
        assertEquals("satme-iq-x.sigmf-data", i.recording)
        assertTrue(SstvPc.horsAntenne(i))
        assertEquals("", s.etat.value.derniere)
        assertEquals(1, s.etat.value.gardees)
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

    @Test fun `une image de la cle sur QO-100 porte QO-100, pas le satellite suivi - et se renomme sans changer de fichier`() {
        // The rig follows RS-44 for a campaign; the dongle is on QO-100 (22/10: its pictures came out « RS-44 »).
        val s = SstvPc(StockageMemoire(), File(dossier, "sstv"), { SstvPc.Contexte("RS-44", "JN18FS", "F4IOZ", "SatMe_RS-44_x.wav", "Campagne : RS-44") }) { maintenant }
        s.satelliteDe = { src -> SstvPc.satelliteDuSon(src == SstvPc.SDR, cleSurQo100 = true, satelliteCle = "QO-100", posteSurQo100 = false, suivi = "RS-44") }
        verse(signal(Dsp.AUDIO_RATE), Dsp.AUDIO_RATE) { b, n -> s.ecouteSdr(b, n, Dsp.AUDIO_RATE) }
        val i = s.images().single()
        assertEquals("QO-100", i.satName)
        assertTrue(i.fileName.startsWith("SatMe_SSTV_QO-100_"))
        assertEquals("sdr", i.entree)
        // Neither the rig's campaign note nor its pass recording.
        assertEquals("", i.note); assertEquals("", i.recording)
        // An older one, named wrongly: its sidecar says another satellite, the file stays.
        assertTrue(s.renommeSatellite(i.fileName, " ISS "))
        assertEquals("ISS", s.images().single().satName)
        assertEquals(i.fileName, s.images().single().fileName)
        assertFalse(s.renommeSatellite(i.fileName, ""))
        assertFalse(s.renommeSatellite("../x.png", "ISS"))
    }

    @Test fun `le satellite d'une image vient de son son`() {
        // By the dongle: QO-100 while QO-100's tab holds it, else what the dongle follows (on its own frequency: the one followed).
        assertEquals("QO-100", SstvPc.satelliteDuSon(true, true, "QO-100", false, "ISS"))
        assertEquals("SO-50", SstvPc.satelliteDuSon(true, false, "SO-50", false, "ISS"))
        assertEquals("ISS", SstvPc.satelliteDuSon(true, false, "", false, "ISS"))
        // By the rig: the satellite followed, QO-100 while the rig is on it.
        assertEquals("ISS", SstvPc.satelliteDuSon(false, true, "QO-100", false, "ISS"))
        assertEquals("QO-100", SstvPc.satelliteDuSon(false, false, "", true, "ISS"))
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

    @Test fun `deux images partielles d-une meme image se reunissent et se separent`() {
        // One Robot 36 picture whose sync was lost at row 110 and found again (before 27/10/2026: two partial pictures,
        // each with its own sound, overlapping by a second).
        val rate = 12_000
        val m = robot36
        val img = IntArray(m.width * m.height) { i -> val v = 30 + ((i / m.width / 10) * 67) % 200; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val son = ShortArray(rate / 2) + SstvTestSignal.encode(m, img, rate) + ShortArray(rate)
        val coupe = rate / 2 + ((120.0 + 910.0 + 110 * m.blockMs) * rate / 1000).toInt()
        val sa = son.copyOfRange(0, coupe + rate / 2); val sb = son.copyOfRange(coupe - rate / 2, son.size)
        fun decode(x: ShortArray): Pair<IntArray, Int> {
            var r: Pair<IntArray, Int>? = null
            val d = fr.f4ioz.satcombo.sstv.SstvDecoder(rate, object : fr.f4ioz.satcombo.sstv.SstvDecoder.Listener {
                override fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) { if (r == null) r = pixels.copyOf() to linesDone }
            }).also { it.continuous = true }
            d.feed(x, x.size); d.finish()
            return r!!
        }
        val g = File(dossier, "sstv").apply { mkdirs() }
        val t0 = 1_793_136_080_000L
        fun ecrit(nom: String, px: Pair<IntArray, Int>, pcm: ShortArray, fin: Long) {
            val b = java.awt.image.BufferedImage(m.width, px.second, java.awt.image.BufferedImage.TYPE_INT_RGB)
            b.setRGB(0, 0, m.width, px.second, px.first, 0, m.width)
            javax.imageio.ImageIO.write(b, "png", File(g, nom))
            File(g, fr.f4ioz.satcombo.sstv.SstvMeta.sidecarName(nom)).writeText(fr.f4ioz.satcombo.sstv.SstvMeta.encode(fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot(
                fileName = nom, satName = "UmKA 1 (RS40S)", timeMs = fin, mode = m.name, complete = false, source = "live", locator = "JN18FS")))
            SstvSon.ecritWav(SstvSon.fichier(File(g, nom)), pcm, rate)
        }
        val na = "SatMe_SSTV_UmKA-1--RS40S_20261027_212143Z_Robot36_partiel.png"
        val nb = "SatMe_SSTV_UmKA-1--RS40S_20261027_212204Z_Robot36_partiel.png"
        ecrit(na, decode(sa), sa, t0 + (sa.size - rate / 2) * 1000L / rate)
        ecrit(nb, decode(sb), sb, t0 + (coupe - rate / 2 + sb.size - rate / 2) * 1000L / rate + 700)
        val s = sstv()
        assertEquals(nb, s.partenaire(na)?.fileName)
        val nom = s.reunit(na, nb).getOrThrow()
        val vues = s.images().map { it.fileName }
        assertEquals(listOf(nom), vues)
        assertEquals(3, s.imagesToutes().size)
        val r = s.imagesToutes().first { it.fileName == nom }
        assertTrue(r.complete); assertEquals(listOf(na, nb), r.reunion); assertEquals("JN18FS", r.locator)
        assertEquals(m.height, javax.imageio.ImageIO.read(File(g, nom)).height)
        assertTrue("son gardé", SstvSon.fichier(File(g, nom)).isFile)
        // Undone: the partial ones back, and they can be put together again.
        assertTrue(s.separe(nom))
        assertEquals(setOf(na, nb), s.images().map { it.fileName }.toSet())
        assertEquals(nb, s.partenaire(na)?.fileName)
        // At a pass's end, on their own.
        assertEquals(1, s.reunitPartielles(t0 - 60_000L, t0 + 120_000L).size)
        assertEquals(1, s.images().size)
    }
}
