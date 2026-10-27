/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * « Tester l'encodeur »: a few seconds of a moving test picture coded by
 * ffmpeg, to nothing (`-f null`), at the size, frame rate and video rate a
 * transmission at this symbol rate and MODCOD would use; H.264 and H.265
 * in software, and the hardware encoders ffmpeg lists. How much faster than
 * real time each goes says whether this PC keeps up. Nothing is opened but
 * ffmpeg (no camera, no radio); never while transmitting. The result is kept
 * per symbol rate and MODCOD.
 */
class BancEncodeurDatvPc(
    private val stockage: Stockage,
    private val ffmpeg: () -> String? = { RenduPc.ffmpeg() },
    private val mesureur: Mesureur = MesureurFfmpeg(),
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The hardware encoders tried too (not in the demonstration: nothing real opened there). */
    private val avecMateriel: () -> Boolean = { !ModeDemo.actif },
) {
    /** Runs a command; the seconds it took, or null when it failed (and why). */
    interface Mesureur {
        fun encoders(ff: String): String
        fun mesure(commande: List<String>, delaiS: Long): Pair<Double?, String>
    }

    data class Essai(val codec: String, val materiel: Boolean, val vitesse: Double?, val erreur: String = "")

    data class Recommandation(val hevc: Boolean?, val tient: Boolean, val texte: String)

    data class Resultat(val cle: String, val ms: Long, val largeur: Int, val hauteur: Int, val ips: Int, val videoBps: Long,
                        val essais: List<Essai>, val recommandation: Recommandation)

    @Volatile var enCours: String? = null; private set
    @Volatile var message = ""; private set

    /** The result kept for [cle], or null when never tested. */
    fun resultat(cle: String): Resultat? = runCatching { JSONObject(stockage.getString(CLE, "{}") ?: "{}").optJSONObject(cle)?.let { lis(cle, it) } }.getOrNull()

    /**
     * Starts the test for [cle] (« 333 kS/s · QPSK 1/2 ») with [b]'s
     * picture and rate, in the background; null once started, else why not.
     */
    @Synchronized
    fun lance(cle: String, b: EmissionDatvPc.Budget, emet: Boolean, attend: Boolean = false): String? {
        if (emet) return "pendant une émission : l'arrêter d'abord (l'encodeur et le modulateur tournent déjà)"
        if (enCours != null) return "un test est déjà en cours"
        val ff = ffmpeg() ?: return "ffmpeg introuvable (" + OutilsPc.conseil("ffmpeg") + ")"
        enCours = cle
        message = "test en cours : " + cle
        val t = thread(isDaemon = true, name = "satme-datv-banc") {
            try {
                val r = execute(ff, cle, b)
                garde(r)
                message = ""
            } catch (x: Exception) {
                message = "test impossible : " + (x.message ?: x.javaClass.simpleName)
            } finally {
                enCours = null
            }
        }
        if (attend) t.join()
        return null
    }

    private fun execute(ff: String, cle: String, b: EmissionDatvPc.Budget): Resultat {
        val liste = mesureur.encoders(ff)
        val materiels = if (avecMateriel()) MATERIELS.filter { Regex("\\b" + it + "\\b").containsMatchIn(liste) } else emptyList()
        val essais = ArrayList<Essai>()
        for (c in listOf("libx264", "libx265") + materiels) {
            if (c.startsWith("lib") && !Regex("\\b$c\\b").containsMatchIn(liste) && liste.isNotBlank()) {
                essais += Essai(c, false, null, "absent de ce ffmpeg"); continue
            }
            val (s, err) = mesureur.mesure(commande(ff, c, b), DELAI_S)
            essais += Essai(c, c !in setOf("libx264", "libx265"), s?.let { Math.round(DUREE_S / it.coerceAtLeast(0.01) * 100) / 100.0 }, err)
        }
        return Resultat(cle, horloge(), b.largeur, b.hauteur, b.ips, b.videoBps, essais, decide(essais, b))
    }

    private fun garde(r: Resultat) {
        val o = runCatching { JSONObject(stockage.getString(CLE, "{}") ?: "{}") }.getOrElse { JSONObject() }
        o.put(r.cle, ecrit(r))
        // The 40 most recent only.
        val cles = o.keySet().toList().sortedByDescending { o.optJSONObject(it)?.optLong("t") ?: 0L }
        cles.drop(40).forEach { o.remove(it) }
        stockage.edit().putString(CLE, o.toString()).apply()
    }

    fun json(cle: String): JSONObject {
        val r = resultat(cle)
        val o = JSONObject().put("cle", cle).put("enCours", enCours ?: JSONObject.NULL).put("message", message)
        if (r != null) o.put("resultat", ecrit(r))
        return o
    }

    companion object {
        private const val CLE = "datv_tx_banc"
        /** Seconds of picture coded. */
        const val DUREE_S = 4.0
        const val DELAI_S = 40L
        /** Faster than real time by this at least: the modulator and the rest need the processor too. */
        const val MARGE = 1.3
        /** The hardware encoders tried when ffmpeg lists them (NVIDIA, Intel, AMD, Apple). */
        val MATERIELS = listOf("hevc_nvenc", "h264_nvenc", "hevc_qsv", "h264_qsv", "hevc_amf", "h264_amf", "hevc_videotoolbox", "h264_videotoolbox")

        /** ffmpeg's command: [DUREE_S] s of a moving test picture coded by [codec] at [b]'s size and rates, to nothing. */
        fun commande(ff: String, codec: String, b: EmissionDatvPc.Budget): List<String> {
            val l = arrayListOf(ff, "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "testsrc2=size=" + b.largeur + "x" + b.hauteur + ":rate=" + b.ips, "-t", DUREE_S.toString(), "-pix_fmt", "yuv420p")
            l += when (codec) {
                "libx264" -> listOf("-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-g", b.ips.toString())
                "libx265" -> listOf("-c:v", "libx265", "-preset", "veryfast", "-tune", "zerolatency", "-x265-params", "log-level=error:keyint=" + b.ips)
                else -> listOf("-c:v", codec)
            }
            l += listOf("-b:v", b.videoBps.toString(), "-maxrate", b.videoBps.toString(), "-bufsize", (b.videoBps / 2).toString(), "-an", "-f", "null", "-")
            return l
        }

        private fun x(v: Double) = "×" + String.format(Locale.FRANCE, "%.1f", v)

        /**
         * What the measures say: H.265 when it keeps up (half the rate for
         * the same picture), else H.264, else neither (a lower symbol rate
         * or a more robust MODCOD makes the picture smaller). A hardware
         * encoder that keeps up is named: SatMe codes in software still.
         */
        fun decide(essais: List<Essai>, b: EmissionDatvPc.Budget): Recommandation {
            fun v(c: String) = essais.firstOrNull { it.codec == c }?.vitesse
            val h265 = v("libx265")
            val h264 = v("libx264")
            // H.265 first among the hardware ones that keep up.
            val mat = essais.filter { it.materiel && (it.vitesse ?: 0.0) >= MARGE }.maxWithOrNull(compareBy({ it.codec.startsWith("hevc") }, { it.vitesse!! }))
            val noteMat = mat?.let { " Encodeur matériel " + it.codec + " présent (" + x(it.vitesse!!) + ") : SatMe code encore en logiciel." } ?: ""
            val taille = "" + b.largeur + "×" + b.hauteur + " à " + b.ips + " i/s"
            return when {
                h265 != null && h265 >= MARGE -> Recommandation(true, true,
                    "H.265 en logiciel tient : " + x(h265) + " le temps réel en " + taille + (h264?.let { " (H.264 " + x(it) + ")" } ?: "") + "." + noteMat)
                h264 != null && h264 >= MARGE -> Recommandation(false, true,
                    "H.264 : " + x(h264) + " le temps réel en " + taille + " ; H.265 " + (h265?.let { "trop lent (" + x(it) + ")" } ?: "indisponible") + "." + noteMat)
                h264 == null && h265 == null -> Recommandation(null, false, "ffmpeg n'a pu coder ni en H.264 ni en H.265." + noteMat)
                else -> Recommandation(if ((h265 ?: 0.0) > 0 && (h265 ?: 0.0) >= (h264 ?: 0.0)) true else false, false,
                    "Ce processeur ne tient pas le temps réel en " + taille + " (H.264 " + (h264?.let { x(it) } ?: "—") + ", H.265 " + (h265?.let { x(it) } ?: "—") +
                        ") : un débit de symboles plus bas ou un MODCOD plus robuste donne une image plus petite." + noteMat)
            }
        }

        private fun ecrit(r: Resultat): JSONObject = JSONObject().put("t", r.ms).put("w", r.largeur).put("h", r.hauteur).put("ips", r.ips).put("video", r.videoBps)
            .put("essais", JSONArray(r.essais.map { e -> JSONObject().put("codec", e.codec).put("materiel", e.materiel).put("vitesse", e.vitesse ?: JSONObject.NULL).put("erreur", e.erreur) }))
            .put("hevc", r.recommandation.hevc ?: JSONObject.NULL).put("tient", r.recommandation.tient).put("texte", r.recommandation.texte)

        private fun lis(cle: String, o: JSONObject): Resultat {
            val a = o.optJSONArray("essais") ?: JSONArray()
            val essais = (0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
                Essai(e.optString("codec"), e.optBoolean("materiel"), if (e.isNull("vitesse")) null else e.optDouble("vitesse"), e.optString("erreur")) } }
            return Resultat(cle, o.optLong("t"), o.optInt("w"), o.optInt("h"), o.optInt("ips"), o.optLong("video"), essais,
                Recommandation(if (o.isNull("hevc")) null else o.optBoolean("hevc"), o.optBoolean("tient"), o.optString("texte")))
        }
    }

    /** ffmpeg itself. */
    class MesureurFfmpeg : Mesureur {
        override fun encoders(ff: String): String = runCatching {
            val pr = ProcessBuilder(ff, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val t = pr.inputStream.bufferedReader().readText()
            pr.waitFor(10, TimeUnit.SECONDS)
            t
        }.getOrDefault("")

        override fun mesure(commande: List<String>, delaiS: Long): Pair<Double?, String> = runCatching {
            val t0 = System.nanoTime()
            val pr = ProcessBuilder(commande).redirectErrorStream(true).start()
            runCatching { pr.outputStream.close() }
            val lu = StringBuilder()
            val th = thread(isDaemon = true, name = "satme-datv-banc-lit") { runCatching { lu.append(pr.inputStream.bufferedReader().readText()) } }
            if (!pr.waitFor(delaiS, TimeUnit.SECONDS)) { pr.destroyForcibly(); return@runCatching null to "trop lent (plus de $delaiS s)" }
            val s = (System.nanoTime() - t0) / 1e9
            th.join(1000)
            if (pr.exitValue() != 0) null to synchronized(lu) { lu.toString() }.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
            else s to ""
        }.getOrElse { null to (it.message ?: "erreur") }
    }
}
