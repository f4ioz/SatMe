/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** The « Mode distant » sound: its frames, µ-law, the resampling, and the page's jitter buffer (under node). */
class SonDistantTest {

    @Test
    fun ulaw_aller_retour_g711() {
        // Reference points of G.711: silence, the extremes.
        assertEquals(0xFF.toByte(), SonDistant.ulaw(0))
        assertEquals(0x80.toByte(), SonDistant.ulaw(32767))
        assertEquals(0x00.toByte(), SonDistant.ulaw(-32768))
        assertEquals(0, SonDistant.deUlaw(0xFF.toByte()).toInt())
        assertEquals(32124, SonDistant.deUlaw(0x80.toByte()).toInt())
        // The error stays within half a step of its segment (about 3 % of the value, 2 near zero… in 14 bits).
        for (v in -32000..32000 step 37) {
            val r = SonDistant.deUlaw(SonDistant.ulaw(v)).toInt()
            assertTrue("$v → $r", abs(r - v) <= maxOf(8, abs(v) / 16 + 4))
        }
    }

    @Test
    fun une_trame_se_relit() {
        val x = FloatArray(160) { (sin(2 * PI * it / 16) * 0.5).toFloat() }
        for (codec in listOf(SonDistant.PCM16, SonDistant.ULAW)) {
            val b = SonDistant.trame(codec, 8000, 4_000_000_123L, 1.7e12 + 0.25, x, x.size)
            assertEquals(SonDistant.ENTETE + if (codec == SonDistant.ULAW) 160 else 320, b.size)
            val l = SonDistant.lit(b)!!
            assertEquals(codec, l.codec); assertEquals(8000, l.rate); assertEquals(4_000_000_123L, l.seq); assertEquals(1.7e12 + 0.25, l.ms, 0.0)
            for (i in x.indices) assertEquals(x[i] * 32767, l.pcm[i].toFloat(), if (codec == SonDistant.ULAW) 600f else 1f)
        }
    }

    @Test
    fun les_messages_de_la_page() {
        val c = SonDistant.Config.de("carte", "16000", "pcm")
        assertEquals(SonDistant.Config("carte", 16_000, SonDistant.PCM16), c)
        // Unknown values: the previous ones kept.
        assertEquals(c, SonDistant.Config.de("micro", "44100", "opus", c))
        assertTrue(SonDistant.message("""{"t":"ping","id":3,"c":12.5}""") is SonDistant.Message.Ping)
        assertTrue(SonDistant.message("""{"t":"config","rate":8000}""") is SonDistant.Message.Reglage)
        // A PTT or a sound going up: refused (room kept in the protocol for later).
        for (t in listOf("ptt", "tx", "micro", "amont")) assertTrue(SonDistant.message("""{"t":"$t","on":true}""") is SonDistant.Message.Refuse)
        assertTrue(SonDistant.message("pas du json") is SonDistant.Message.Inconnu)
        assertTrue(SonDistant.bonjour(c).contains("\"amont\":false"))
    }

    private fun efficace(x: ShortArray, de: Int) = sqrt((de until x.size).sumOf { (x[it] / 32768.0) * (x[it] / 32768.0) } / (x.size - de))

    private fun passe(rate: Int, ton: Double, entree: Int = 44_100): ShortArray {
        val e = SonDistant.Emetteur(SonDistant.Config(rate = rate, codec = SonDistant.PCM16))
        val tout = ArrayList<Short>()
        var seq = -1L
        // Blocks of an odd size, as a dongle gives them: frames of exactly 20 ms come out, numbered in order.
        val bloc = 1023
        for (b in 0 until entree / bloc) {
            val pcm = ShortArray(bloc) { (sin(2 * PI * ton * (b * bloc + it) / entree) * 16000).toInt().toShort() }
            for (t in e.pousse(pcm, bloc, entree, 1000L + b * 23)) {
                val l = SonDistant.lit(t)!!
                assertEquals(rate / 50, l.pcm.size); assertEquals(seq + 1, l.seq); seq = l.seq
                l.pcm.forEach { tout += it }
            }
        }
        return tout.toShortArray()
    }

    @Test
    fun le_debit_choisi_sans_repliement() {
        for (rate in SonDistant.DEBITS) {
            // A voice tone passes whole…
            val voix = passe(rate, 1000.0)
            assertTrue(abs(voix.size - rate) <= rate / 50 * 2)
            assertEquals("$rate", 16000 / 32768.0 / sqrt(2.0), efficace(voix, 400), 0.03)
            // …a tone above the new Nyquist does not fold back into the band (a pair average would leave it).
            val haut = passe(rate, rate * 0.5 + 1500.0)
            assertTrue("$rate: ${efficace(haut, 400)}", efficace(haut, 400) < 0.01)
        }
    }

    @Test
    fun le_spectre_en_un_octet_par_case() {
        val o = org.json.JSONObject().put("trames", 3).put("db", org.json.JSONArray(listOf(-200, -160, -87, 0, 120)))
        val q = ApiSdr.octets(o)
        assertTrue(!q.has("db"))
        val b = java.util.Base64.getDecoder().decode(q.getString("db8")).map { it.toInt() and 255 }
        assertEquals(listOf(0, 0, 73, 160, 255), b)
        assertEquals(-160, q.getInt("db0"))
    }

    /** The page's jitter buffer, run by node with injected jitter, losses, a stall, a card's drift. */
    @Test
    fun le_tampon_de_la_page_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
            ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-tampon-" + System.nanoTime()).apply { mkdirs() }
        try {
            val tampon = File(d, "tampon.js").apply { writeText(SonDistantJs.TAMPON + "\nmodule.exports = TamponSon;\n") }
            val essai = File(d, "essai.js").apply { writeText(SonDistantTest::class.java.getResource("/son-distant-test.js")!!.readText()) }
            val p = ProcessBuilder(node, essai.path, tampon.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(120, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
