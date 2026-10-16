/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.IndicatifsImage
import fr.f4ioz.satcombo.datv.VoteIndicatifs
import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * What is said and written in a received DATV picture, both off unless
 * chosen in the DATV tab:
 *
 * - **Subtitles**: the stream's sound to « Phonie → texte » while the
 *   reception runs (it then listens to nothing else; its own source comes
 *   back after).
 * - **Callsigns in the picture**: every few seconds while locked, the
 *   latest picture read by tesseract (an optional program), the plausible
 *   callsigns shown once read twice in a row.
 */
class TextesDatvPc(
    private val stockage: Stockage,
    private val source: Source,
    private val phonie: PhoniePc?,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** tesseract, when installed. */
    private val outil: () -> String? = { OutilsPc.trouve("tesseract") },
    private val periodeMs: Long = 3000L,
) {
    /** What is read of the DATV reception. */
    class Source(
        val actif: () -> Boolean,
        val verrouille: () -> Boolean,
        val image: () -> ByteArray?,
        val service: () -> String,
        val cielKhz: () -> Long,
        /** The sound decoded for the subtitles, or not. */
        val sousTitres: (Boolean) -> Unit,
    ) {
        companion object {
            fun de(d: DatvPc) = Source({ d.etat.actif }, { d.etat.verrouille }, { d.image }, { d.etat.service }, { d.reglage.cielKhz },
                { d.sousTitres = it })
        }
    }

    var sousTitres: Boolean
        get() = stockage.getBoolean("datv_soustitres", false)
        set(v) { stockage.edit().putBoolean("datv_soustitres", v).apply(); tic() }
    var ocr: Boolean
        get() = stockage.getBoolean("datv_ocr", false)
        set(v) { stockage.edit().putBoolean("datv_ocr", v).apply(); if (!v) vote.oublie() }

    /** The subtitles applied now (on, and the reception running). */
    @Volatile var sousTitresActifs = false
        private set

    // ------------------------------------------------------------------ each second

    @Synchronized
    fun tic() {
        val veut = sousTitres && phonie != null && source.actif()
        if (veut != sousTitresActifs) {
            sousTitresActifs = veut
            source.sousTitres(veut)
            phonie?.prendsDatv(veut)
        }
        val t = horloge()
        if (ocr && source.verrouille() && t - derniereMs >= periodeMs && !occupe.get()) {
            val img = source.image() ?: return
            val prog = outil() ?: return
            derniereMs = t
            occupe.set(true)
            thread(isDaemon = true, name = "satme-datv-ocr") { try { lisImage(prog, img) } finally { occupe.set(false) } }
        }
    }

    // ------------------------------------------------------------------ callsigns in the picture

    private val vote = VoteIndicatifs()
    private val occupe = AtomicBoolean(false)
    @Volatile private var derniereMs = 0L
    @Volatile var dernierTexte = ""
        private set
    @Volatile var erreur = ""
        private set
    @Volatile var lectures = 0L
        private set

    /** One read of [jpeg] (one tesseract at a time): its candidates, voted. */
    fun lisImage(prog: String, jpeg: ByteArray): List<String> {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return emptyList()
        val textes = ArrayList<String>()
        for ((vue, psm) in prepare(img)) {
            val r = tesseract(prog, vue, psm)
            if (r == null) { erreur = "tesseract ne répond pas"; continue }
            erreur = ""
            textes += r
        }
        val texte = textes.joinToString("\n")
        dernierTexte = texte.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").take(200)
        lectures++
        val c = IndicatifsImage.candidats(texte)
        vote.lecture(c, horloge())
        return c
    }

    fun confirmes(): List<VoteIndicatifs.Lu> = vote.confirmes()

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        val service = runCatching { source.service() }.getOrDefault("")
        val prog = outil()
        val ciel = runCatching { source.cielKhz() }.getOrDefault(0L)
        return JSONObject().put("ok", true).put("soustitres", sousTitres).put("soustitresActifs", sousTitresActifs)
            .put("phonie", phonie != null).put("ocr", ocr).put("outil", prog.orEmpty())
            .put("conseil", if (prog == null) OutilsPc.conseil("tesseract") else "").put("occupe", occupe.get())
            .put("lectures", lectures).put("texte", dernierTexte).put("erreur", erreur).put("service", service)
            .put("ciel", ciel).put("qo100", IndicatifsImage.qo100(ciel))
            .put("lus", JSONArray(confirmes().map { l ->
                JSONObject().put("c", l.indicatif).put("lectures", l.lectures).put("t", l.dernierMs)
                    .put("accord", IndicatifsImage.accord(l.indicatif, service)).also { j ->
                        Dxcc.entite(l.indicatif)?.let { d -> j.put("pays", d.nom).put("drapeau", d.drapeau) }
                    }
            }))
    }

    /** POST /api/datv/textes?soustitres=0|1&ocr=0|1&oublie=1 */
    fun action(q: Map<String, String>): JSONObject {
        q["soustitres"]?.let { sousTitres = it == "1" }
        q["ocr"]?.let { ocr = it == "1" }
        if (q["oublie"] == "1") vote.oublie()
        return JSONObject().put("ok", true)
    }

    companion object {
        const val LISTE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/"

        /**
         * The views read: the whole picture (sparse text), then its top and
         * bottom bands stacked (where callsigns usually are), each enlarged
         * for small pictures, grey, stretched, dark text on light.
         */
        fun prepare(img: BufferedImage): List<Pair<BufferedImage, Int>> {
            val k = if (img.height <= 600) 2 else 1
            val tout = net(img, k)
            val h = tout.height
            val b = (h * 0.22).toInt().coerceAtLeast(1)
            val bandes = BufferedImage(tout.width, 2 * b + 16, BufferedImage.TYPE_BYTE_GRAY)
            bandes.createGraphics().apply {
                color = java.awt.Color.WHITE; fillRect(0, 0, bandes.width, bandes.height)
                drawImage(tout.getSubimage(0, 0, tout.width, b), 0, 0, null)
                drawImage(tout.getSubimage(0, h - b, tout.width, b), 0, b + 16, null)
                dispose()
            }
            return listOf(tout to 11, bandes to 6)
        }

        /** [img] enlarged ×[k], grey, its levels stretched (2 %…98 %), light text made dark. */
        fun net(img: BufferedImage, k: Int): BufferedImage {
            val w = img.width * k; val h = img.height * k
            val g = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            g.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, w, h, null); dispose()
            }
            val r = g.raster
            val px = IntArray(w * h)
            r.getSamples(0, 0, w, h, 0, px)
            val hist = IntArray(256); px.forEach { hist[it]++ }
            fun quantile(q: Double): Int { var s = 0; val n = (px.size * q).toLong(); for (i in 0..255) { s += hist[i]; if (s >= n) return i }; return 255 }
            val bas = quantile(0.02); val haut = quantile(0.98).coerceAtLeast(bas + 1)
            // A picture mostly dark: its text is light, turned dark for the reader.
            val inverse = px.average() < 110
            for (i in px.indices) {
                var v = ((px[i] - bas) * 255 / (haut - bas)).coerceIn(0, 255)
                if (inverse) v = 255 - v
                px[i] = v
            }
            r.setSamples(0, 0, w, h, 0, px)
            return g
        }

        /** tesseract on [img] (page segmentation [psm], callsign characters only); its text, null when it fails. */
        fun tesseract(prog: String, img: BufferedImage, psm: Int, delaiS: Long = 15): String? {
            val png = File.createTempFile("satme-ocr-", ".png")
            val sortie = File.createTempFile("satme-ocr-", ".txt")
            try {
                ImageIO.write(img, "png", png)
                val p = ProcessBuilder(prog, png.absolutePath, "stdout", "--psm", psm.toString(), "-c", "tessedit_char_whitelist=$LISTE")
                    .redirectOutput(sortie).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!p.waitFor(delaiS, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
                if (p.exitValue() != 0) return null
                return sortie.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            } finally { png.delete(); sortie.delete() }
        }
    }
}
