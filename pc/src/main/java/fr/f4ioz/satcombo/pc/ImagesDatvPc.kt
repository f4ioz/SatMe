/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The DATV tab's JPEG stream (`/api/datv/video.mjpeg`, the page's picture unless « Lecture fluide » is on), at the
 * « Qualité » the browser asks (`?q=480`, [NiveauDatvPc]).
 *
 * Source: the JPEG pictures as the receiver's decoder makes them (720 lines at most, quality 5, 25 a second).
 * A smaller level: one ffmpeg for all the pages asking it, fed with those same pictures (no second H.265 decoding,
 * no second deinterlacing) and giving them back smaller and lighter ([commande]), as many a second as the level
 * says at most. Two re-encoded levels at most at once ([PartageNiveauxDatvPc], with the « Lecture fluide »'s); a
 * level nobody watches for 10 s is stopped.
 *
 * Each page's stream is measured (bytes, pictures sent, pictures it could not take in time: skipped), by its `id`,
 * for its « Qualité » line and its « Auto » choice (`/api/datv/video/mesure`).
 */
class ImagesDatvPc(
    private val image: () -> ByteArray?,
    private val actif: () -> Boolean,
    /** The source pictures' count (the skipped ones counted from it), or -1 when unknown. */
    private val numeroSource: () -> Long = { -1L },
    val partage: PartageNiveauxDatvPc = PartageNiveauxDatvPc(),
    private val ffmpeg: () -> String? = { RenduPc.ffmpeg() },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** A page's stream, as measured. */
    class Mesure(val voulu: NiveauDatvPc, val servi: NiveauDatvPc, val raison: String, val debutMs: Long) {
        @Volatile var octets = 0L
        @Volatile var images = 0L
        @Volatile var sautees = 0L
        @Volatile var finMs = 0L
    }

    private val encodeurs = ConcurrentHashMap<NiveauDatvPc, Encodeur>()
    private val mesures = ConcurrentHashMap<String, Mesure>()
    @Volatile private var fil: Thread? = null

    /** Each second: the levels nobody watched for 10 s stopped, the old measures forgotten. */
    @Synchronized
    private fun demarre() {
        if (fil != null) return
        fil = Thread({ while (true) { runCatching { menage() }; Thread.sleep(1000L) } }, "satme-datv-images-niveaux").apply { isDaemon = true; start() }
    }

    internal fun menage() {
        partage.aArreter(SORTE).forEach { encodeurs.remove(it)?.arrete() }
        // An encoder stopped by itself (ffmpeg gone): started again by the next page asking.
        encodeurs.entries.removeIf { it.value.fini }
        val t = horloge()
        mesures.entries.removeIf { it.value.finMs > 0 && t - it.value.finMs > 60_000L }
    }

    /** The encoder of a level (started when missing), or null when ffmpeg cannot be found. */
    @Synchronized
    private fun encodeur(n: NiveauDatvPc): Encodeur? {
        encodeurs[n]?.takeIf { !it.fini }?.let { return it }
        val ff = ffmpeg() ?: return null
        demarre()
        return Encodeur(ff, n).also { runCatching { it.lance() }.onFailure { _ -> it.fini = true }; encodeurs[n] = it }
    }

    /**
     * The `multipart/x-mixed-replace` stream to the page, at the level [voulu] or the nearest one possible, until it
     * goes, or nothing new came for a long while with the reception stopped (the page asks again when it starts).
     */
    fun sert(sortie: OutputStream, id: String, voulu: NiveauDatvPc) {
        val n = partage.prend(SORTE, voulu)
        try {
            val e = if (n == NiveauDatvPc.SOURCE) null else encodeur(n)
            val servi = if (e == null) NiveauDatvPc.SOURCE else n
            val m = Mesure(voulu, servi, partage.raison(voulu, servi).ifEmpty { if (servi != n) "ffmpeg introuvable : la source" else "" }, horloge())
            if (id.isNotEmpty()) mesures[id.take(40)] = m
            sortie.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=satme\r\nX-Satme-Niveau: ${servi.code}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            try { boucle(sortie, m, { e?.fini == true }) { if (e == null) image() to numeroSource() else e.image to e.numero } } finally { m.finMs = horloge() }
        } finally { partage.rend(SORTE, n) }
    }

    private fun boucle(sortie: OutputStream, m: Mesure, fini: () -> Boolean, lit: () -> Pair<ByteArray?, Long>) {
        var vu: ByteArray? = null
        var numeroVu = -1L
        var calme = 0
        while (true) {
            val (img, numero) = lit()
            if (img != null && img !== vu) {
                // The pictures made meanwhile (the page too slow to take them): skipped.
                if (numero >= 0 && numeroVu >= 0 && numero - numeroVu > 1) m.sautees += numero - numeroVu - 1
                vu = img; numeroVu = numero; calme = 0
                val ok = runCatching {
                    val entete = ("--satme\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.size}\r\n\r\n").toByteArray()
                    sortie.write(entete); sortie.write(img); sortie.write("\r\n".toByteArray()); sortie.flush()
                    m.octets += entete.size + img.size + 2; m.images++
                }.isSuccess
                if (!ok) return
            } else {
                // Nothing new for a long while and the reception stopped, or the level's ffmpeg gone: the page asks again.
                if ((!actif() && ++calme > 1200) || fini()) return
                Thread.sleep(10)
            }
        }
    }

    /** `/api/datv/video/mesure?id=`: the page's stream so far (its level, bytes, pictures, skipped ones). */
    fun mesure(id: String): JSONObject {
        val m = mesures[id.take(40)] ?: return JSONObject().put("ok", false)
        return JSONObject().put("ok", true).put("voulu", m.voulu.code).put("niveau", m.servi.code).put("repli", m.raison)
            .put("octets", m.octets).put("images", m.images).put("sautees", m.sautees).put("ms", (if (m.finMs > 0) m.finMs else horloge()) - m.debutMs)
            .put("fini", m.finMs > 0).put("encodages", partage.actifs(LecteurFluideDatvPc.SORTE).size + partage.actifs(SORTE).size)
    }

    /** One level's ffmpeg: the source's pictures in (as many a second as the level says at most), smaller ones out. */
    private inner class Encodeur(val ff: String, val niveau: NiveauDatvPc) {
        @Volatile var image: ByteArray? = null
        @Volatile var numero = 0L
        @Volatile var fini = false
        @Volatile private var p: Process? = null

        fun lance() {
            val q = ProcessBuilder(commande(ff, niveau)).redirectError(JOURNAL).start()
            p = q
            Thread({ nourrit(q.outputStream) }, "satme-datv-images-" + niveau.code).apply { isDaemon = true; start() }
            Thread({ lit(q.inputStream) }, "satme-datv-images-" + niveau.code + "-lu").apply { isDaemon = true; start() }
        }

        private fun nourrit(o: OutputStream) {
            var vu: ByteArray? = null
            var dernierMs = 0L
            val ecartMs = 1000L / niveau.ipsJpeg - 5
            try {
                while (!fini) {
                    val img = image()
                    val t = horloge()
                    if (img != null && img !== vu && t - dernierMs >= ecartMs) { vu = img; dernierMs = t; o.write(img); o.flush() }
                    else Thread.sleep(10)
                }
            } catch (_: Exception) { fini = true }
            runCatching { o.close() }
        }

        private fun lit(e: InputStream) {
            val d = DatvPc.Decoupe()
            val b = ByteArray(65_536)
            try {
                while (!fini) {
                    val n = e.read(b)
                    if (n < 0) break
                    d.ajoute(b, n) { img -> image = img; numero++ }
                }
            } catch (_: Exception) {}
            fini = true
        }

        fun arrete() {
            fini = true
            p?.let { q -> runCatching { q.outputStream.close() }; q.destroy(); if (runCatching { !q.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) q.destroyForcibly() }
        }
    }

    companion object {
        /** The sort of [PartageNiveauxDatvPc] for these encoders. */
        const val SORTE = "jpeg"
        val JOURNAL = java.io.File(System.getProperty("java.io.tmpdir"), "satme-datv-images-ffmpeg.log")

        /**
         * ffmpeg's command for a level: JPEG pictures in, the same smaller ([NiveauDatvPc.lignes]) and lighter
         * ([NiveauDatvPc.qJpeg]) out, each one as it comes (no probing ahead: 4 s late otherwise, under 0.1 s so).
         */
        fun commande(ff: String, n: NiveauDatvPc): List<String> = listOf(ff, "-hide_banner", "-loglevel", "error",
            "-flags", "low_delay", "-probesize", "32", "-analyzeduration", "0", "-threads", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-i", "pipe:0",
            "-an", "-vf", "scale=-2:'min(${n.lignes},ih)'", "-q:v", n.qJpeg.toString(), "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
    }
}
