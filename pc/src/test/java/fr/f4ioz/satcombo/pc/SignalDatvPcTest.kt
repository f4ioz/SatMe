/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Norme
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a DATV start would send, without sending: the summary and its bit
 * budget, the clock card, the encoder test (a fake ffmpeg), the ramp
 * refused when nothing transmits.
 */
class SignalDatvPcTest {

    private fun emission(st: StockageMemoire = StockageMemoire()) =
        EmissionDatvPc(st, File(System.getProperty("java.io.tmpdir"), "satme-test-datv-signal"), { "F4IOZ" }, { "JN18" })

    @Test
    fun budget_comme_la_commande_ffmpeg() {
        val b = EmissionDatvPc.budget(400_000L, true, "camera")
        assertEquals(32_000L, b.audioBps)
        assertEquals(((400_000L - 32_000L) * 0.78 - 8_000).toLong(), b.videoBps)
        assertEquals(640 to 360, b.largeur to b.hauteur)
        assertEquals(25, b.ips)
        assertEquals(10, EmissionDatvPc.budget(400_000L, false, "horloge").ips)
        assertEquals(0L, EmissionDatvPc.budget(400_000L, false, "mire").audioBps)
        // The command uses exactly these figures.
        val c = EmissionDatvPc.commande("ffmpeg", EmissionDatvPc.ParametresTs(400_000L, "camera", null, "/dev/video0", "F4IOZ", true, true))
        assertEquals(b.videoBps.toString(), c[c.indexOf("-b:v") + 1])
        assertEquals("32k", c[c.indexOf("-b:a") + 1])
    }

    @Test
    fun resume_avec_le_mer_minimum() {
        val e = emission()
        e.srKs = 333; e.modcodTexte = "QPSK 1/2"; e.hevc = true; e.son = true; e.source = "mire"
        val r = e.resume()
        assertEquals("DVB-S2", r.getString("norme"))
        assertEquals("QPSK 1/2", r.getString("modcod"))
        assertEquals(1.0, r.getDouble("merMin"), 1e-9)
        assertEquals("H.265", r.getString("codec"))
        assertEquals(e.debitTs(), r.getLong("ts"))
        assertTrue(r.getLong("video") + r.getLong("audio") < r.getLong("ts"))
        e.courtes = true
        assertEquals(1.3, e.resume().getDouble("merMin"), 1e-9)
        e.norme = Norme.DVBS; e.tauxDvbsTexte = "QPSK 3/4"
        assertEquals(6.9, e.resume().getDouble("merMin"), 1e-9)
        assertFalse(e.resume().getBoolean("courtes"))
        assertTrue(e.json().has("resume"))
    }

    @Test
    fun horloge_source_et_commande() {
        val e = emission()
        e.source = "horloge"
        assertEquals("horloge", e.source)
        val png = File(System.getProperty("java.io.tmpdir"), "satme-test-horloge.png")
        val direct = IncrustationDatvPc.IncrustationTs(png, true, { ByteArray(0) })
        val c = EmissionDatvPc.commande("ffmpeg", EmissionDatvPc.ParametresTs(300_000L, "horloge", png, "", "F4IOZ", false, true, horloge = direct))
        // The card through ffmpeg's input, a beep each second.
        assertEquals("pipe:0", c[c.indexOf("image2pipe") + 4])
        assertTrue(c.any { it.startsWith("sine=") })
        // The preview: its PNG looped instead.
        val a = EmissionDatvPc.commande("ffmpeg", EmissionDatvPc.ParametresTs(300_000L, "horloge", png, "", "F4IOZ", false, false))
        assertFalse(a.contains("pipe:0"))
        assertEquals(png.path, a[a.indexOf("-i") + 1])
    }

    @Test
    fun horloge_dessinee_chaque_seconde() {
        val a = HorlogeDatvPc.rendu(640, 360, "F4IOZ", "JN18", 1_700_000_000_000L)
        val b = HorlogeDatvPc.rendu(640, 360, "F4IOZ", "JN18", 1_700_000_001_000L)
        assertEquals(640, a.width); assertEquals(360, a.height)
        val pa = a.getRGB(0, 0, 640, 360, null, 0, 640)
        val pb = b.getRGB(0, 0, 640, 360, null, 0, 640)
        assertFalse(pa.contentEquals(pb))
        // The overlay never shows a frozen time over the clock.
        assertTrue(IncrustationDatvPc.surMire("horloge"))
    }

    /** A fake ffmpeg: each codec's seconds for 4 s of picture, or a failure. */
    private class FauxMesureur(val secondes: Map<String, Double?>, val liste: String = "libx264 libx265") : BancEncodeurDatvPc.Mesureur {
        val commandes = ArrayList<List<String>>()
        override fun encoders(ff: String) = liste
        override fun mesure(commande: List<String>, delaiS: Long): Pair<Double?, String> {
            commandes += commande
            val c = commande[commande.indexOf("-c:v") + 1]
            return secondes[c] to (if (secondes[c] == null) "No capable devices found" else "")
        }
    }

    private val b333 = EmissionDatvPc.budget(400_000L, true, "camera")

    @Test
    fun decision_du_banc() {
        fun e(c: String, v: Double?, m: Boolean = false) = BancEncodeurDatvPc.Essai(c, m, v)
        val h265 = BancEncodeurDatvPc.decide(listOf(e("libx264", 6.0), e("libx265", 2.0)), b333)
        assertEquals(true, h265.hevc); assertTrue(h265.tient)
        val h264 = BancEncodeurDatvPc.decide(listOf(e("libx264", 3.0), e("libx265", 0.9)), b333)
        assertEquals(false, h264.hevc); assertTrue(h264.tient); assertTrue(h264.texte.contains("trop lent"))
        val non = BancEncodeurDatvPc.decide(listOf(e("libx264", 1.1), e("libx265", 0.5)), b333)
        assertFalse(non.tient); assertTrue(non.texte.contains("débit de symboles plus bas"))
        val mat = BancEncodeurDatvPc.decide(listOf(e("libx264", 3.0), e("libx265", 2.0), e("hevc_nvenc", 12.0, true)), b333)
        assertTrue(mat.texte.contains("hevc_nvenc"))
        assertNull(BancEncodeurDatvPc.decide(listOf(e("libx264", null), e("libx265", null)), b333).hevc)
    }

    @Test
    fun banc_execute_garde_et_refuse_pendant_l_emission() {
        val st = StockageMemoire()
        val m = FauxMesureur(mapOf("libx264" to 1.0, "libx265" to 2.0, "hevc_nvenc" to null), "libx264 libx265 hevc_nvenc")
        val banc = BancEncodeurDatvPc(st, { "ffmpeg" }, m, { 42L }, { true })
        assertNotNull(banc.lance("333 kS/s · QPSK 1/2 · 25 i/s", b333, emet = true))
        assertNull(banc.resultat("333 kS/s · QPSK 1/2 · 25 i/s"))
        assertNull(banc.lance("333 kS/s · QPSK 1/2 · 25 i/s", b333, emet = false, attend = true))
        val r = banc.resultat("333 kS/s · QPSK 1/2 · 25 i/s")!!
        assertEquals(42L, r.ms)
        assertEquals(listOf("libx264", "libx265", "hevc_nvenc"), r.essais.map { it.codec })
        // 4 s coded in 2 s: twice real time; the hardware one failed (no card).
        assertEquals(2.0, r.essais[1].vitesse!!, 1e-9)
        assertNull(r.essais[2].vitesse)
        assertEquals(true, r.recommandation.hevc)
        // Coded to nothing, at the transmission's size and rate, nothing else opened.
        val c = m.commandes[0]
        assertTrue(c.containsAll(listOf("-f", "null", "-")))
        assertTrue(c.contains("testsrc2=size=640x360:rate=25"))
        assertEquals(b333.videoBps.toString(), c[c.indexOf("-b:v") + 1])
        assertFalse(c.any { it.startsWith("/dev/") })
        // Kept per key: another key not tested yet.
        assertNull(banc.resultat("125 kS/s · QPSK 1/2 · 25 i/s"))
        assertTrue(BancEncodeurDatvPc(st, { "ffmpeg" }, m).json("333 kS/s · QPSK 1/2 · 25 i/s").has("resultat"))
        // The demonstration: the hardware encoders left alone.
        val d = FauxMesureur(mapOf("libx264" to 1.0, "libx265" to 2.0), "libx264 libx265 hevc_nvenc")
        BancEncodeurDatvPc(StockageMemoire(), { "ffmpeg" }, d, avecMateriel = { false }).lance("k", b333, false, attend = true)
        assertEquals(2, d.commandes.size)
        // No ffmpeg: said so.
        assertNotNull(BancEncodeurDatvPc(st, { null }, m).lance("x", b333, false))
    }

    @Test
    fun cle_du_banc_et_rampe_sans_emission() {
        val e = emission()
        e.srKs = 333; e.modcodTexte = "QPSK 1/2"; e.source = "camera"; e.courtes = false
        assertEquals("333 kS/s · QPSK 1/2 · 25 i/s", e.cleBanc())
        e.source = "mire"
        assertNotEquals("333 kS/s · QPSK 1/2 · 25 i/s", e.cleBanc())
        // Nothing transmits: no ramp, the calibration taken back as a setting only.
        assertEquals("aucune émission en cours", e.lanceRampe("haut"))
        assertEquals("aucune émission en cours", e.lanceRampe("bas"))
        assertNull(e.lanceRampe("stop"))
        assertNotNull(e.appliqueCalibration())
        val j = e.json()
        assertTrue(j.isNull("calibration"))
        assertEquals("", j.getJSONObject("rampe").getString("sens"))
    }
}
