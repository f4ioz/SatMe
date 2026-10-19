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
import fr.f4ioz.satcombo.phonie.ParoleTraduite
import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * By hand only, with Piper installed: an over (16-bit mono WAV at 16 kHz,
 * and its segments in a .json beside it: debut, fin, texte, traduction)
 * said in each intonation by the real helpers; the sounds written beside it,
 * nothing played. Skipped unless SATME_VOIX_DOSSIER (where stt/ is) and
 * SATME_VOIX_OVER are set.
 */
class ParoleReelleTest {

    @Test fun `un passage dit par la vraie voix de synthese`() {
        val d = System.getenv("SATME_VOIX_DOSSIER")?.let(::File)
        val wav = System.getenv("SATME_VOIX_OVER")?.let(::File)
        assumeTrue(d != null && wav != null)
        val install = InstallPhoniePc(d!!)
        val (x, rate) = ParolePc.litWav(wav!!.readBytes())
        val segs = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
        val m = (0 until segs.length()).map { segs.getJSONObject(it) }.map {
            ProtocolePhonie.Morceau(it.getDouble("debut"), it.getDouble("fin"), it.getString("texte"), -0.2, 0.01, 1.0, it.getString("traduction"))
        }
        val p0 = HauteurVoix.profil(HauteurVoix.suit(x, rate))
        println("passage : ${"%.1f".format(x.size / rate.toDouble())} s, ${"%.0f".format(p0.f0Hz)} Hz, étendue ${"%.1f".format(p0.etendueDt)} demi-tons")
        val intonations = listOf("neutre", "proche") + if (install.clonageInstalle()) listOf("clonee") else emptyList()
        for (into in intonations) {
            var son: Pair<ShortArray, Int>? = null
            val p = ParolePc(StockageMemoire(), install, { install.pythonVenv().absolutePath }, { "fr" }, lecteur = { pcm, r -> son = pcm to r })
            p.intonation = into
            for (essai in 1..2) {
                // The clock as if the over had just ended.
                val b = BullePhonie(1, System.currentTimeMillis() - (x.size * 1000L / rate), x.size / rate.toDouble(), "sdr", null, "", "en", 0.99,
                    m.joinToString(" ") { it.texte }, m.joinToString(" ") { it.traduction }, 0.9, 0.3, emptyList(), "fr")
                val t = System.currentTimeMillis()
                p.dis(ParolePc.Entendu(b, x, m, ""))
                val (pcm, r) = son ?: error("rien de dit : ${p.erreur}")
                val y = FloatArray(pcm.size) { pcm[it] / 32768f }
                val pr = HauteurVoix.profil(HauteurVoix.suit(y, r))
                println("$into (essai $essai) : ${System.currentTimeMillis() - t} ms de synthèse et traitement, latence ${p.latenceMs} ms · ${p.moteurUtilise} · " +
                    "${"%.1f".format(y.size / r.toDouble())} s · ${"%.0f".format(pr.f0Hz)} Hz · étendue ${"%.1f".format(pr.etendueDt)} demi-tons")
                File(wav.parentFile, "dit-$into.wav").writeBytes(ParoleTraduite.wav(pcm, r))
            }
            p.arrete()
        }
    }
}
