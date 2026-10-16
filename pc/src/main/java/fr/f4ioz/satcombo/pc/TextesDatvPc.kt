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
 *   callsigns shown once read twice in a row (at once when the service
 *   name, the station's card, the log or our own callsign vouches for them).
 */
class TextesDatvPc(
    private val stockage: Stockage,
    private val source: Source,
    private val phonie: PhoniePc?,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** tesseract, when installed. */
    private val outil: () -> String? = { OutilsPc.trouve("tesseract") },
    private val periodeMs: Long = 3000L,
    /** The log's callsigns, and ours: a word read as one of them is taken at once. */
    private val carnet: () -> Collection<String> = { emptyList() },
    private val moi: () -> String = { "" },
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
        /** The callsign on the station's card in the stream, or "". */
        val carte: () -> String = { "" },
    ) {
        companion object {
            fun de(d: DatvPc) = Source({ d.etat.actif }, { d.etat.verrouille }, { d.image }, { d.etat.service }, { d.reglage.cielKhz },
                { d.sousTitres = it }, { d.carte.carte?.indicatif.orEmpty() })
        }
    }

    var sousTitres: Boolean
        get() = stockage.getBoolean("datv_soustitres", false)
        set(v) { stockage.edit().putBoolean("datv_soustitres", v).apply(); tic() }
    var ocr: Boolean
        get() = stockage.getBoolean("datv_ocr", false)
        set(v) { stockage.edit().putBoolean("datv_ocr", v).apply(); if (!v) { vote.oublie(); garants.clear() } }

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

    /** Who vouched for each callsign shown (its last read). */
    private val garants = java.util.concurrent.ConcurrentHashMap<String, IndicatifsImage.Lu>()

    /** The callsigns expected on the picture: the service name, the station's card, the log, ours. */
    fun connus(): IndicatifsImage.Connus = IndicatifsImage.Connus(
        runCatching { source.service() }.getOrDefault(""), runCatching { source.carte() }.getOrDefault(""),
        runCatching { carnet() }.getOrDefault(emptyList()), runCatching { moi() }.getOrDefault(""))

    /** One read of [jpeg] (one tesseract at a time): its candidates, voted (one vouched for shown at once). */
    fun lisImage(prog: String, jpeg: ByteArray): List<String> {
        val img = runCatching { ImageIO.read(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return emptyList()
        val texte = lis(prog, img)
        erreur = if (texte == null) "tesseract ne répond pas" else ""
        val lus = IndicatifsImage.lis(texte.orEmpty(), connus())
        dernierTexte = IndicatifsImage.corrige(texte.orEmpty(), lus).lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(" · ").take(200)
        lectures++
        lus.forEach { garants[it.indicatif] = it }
        val c = lus.map { it.indicatif }
        vote.lecture(c, horloge(), lus.filter { it.garant != null }.map { it.indicatif })
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
                val g = garants[l.indicatif]
                JSONObject().put("c", l.indicatif).put("lectures", l.lectures).put("t", l.dernierMs)
                    .put("accord", IndicatifsImage.accord(l.indicatif, service)).put("garant", g?.garant?.code.orEmpty())
                    .put("mot", g?.mot?.takeIf { it != l.indicatif }.orEmpty()).also { j ->
                        Dxcc.entite(l.indicatif)?.let { d -> j.put("pays", d.nom).put("drapeau", d.drapeau) }
                    }
            }))
    }

    /** POST /api/datv/textes?soustitres=0|1&ocr=0|1&oublie=1 */
    fun action(q: Map<String, String>): JSONObject {
        q["soustitres"]?.let { sousTitres = it == "1" }
        q["ocr"]?.let { ocr = it == "1" }
        if (q["oublie"] == "1") { vote.oublie(); garants.clear() }
        return JSONObject().put("ok", true)
    }

    companion object {
        /** The characters read; the space too, else the words come out glued (DEEA4ABC). */
        const val LISTE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/ "
        /** The height a line's box is read at, in two levels (its capitals some 40 pixels high). */
        private const val HAUTEUR_LIGNE = 64
        private const val LIGNES_MAX = 12
        /** The lines' second reads: their height, in two levels or grey. */
        private val VUES = listOf(40 to false, HAUTEUR_LIGNE to true)

        /** A text line found in a picture: its box (picture pixels) and what the first pass read. */
        data class Ligne(val x: Int, val y: Int, val l: Int, val h: Int, val texte: String)

        /**
         * What tesseract reads in [img], one text per line: the whole picture
         * first (sparse text, to find its lines), then each line found read
         * again alone and enlarged, twice (each look errs elsewhere), text
         * dark on light. Null when tesseract fails.
         */
        fun lis(prog: String, img: BufferedImage): String? {
            val k = if (img.height <= 600) 2 else 1
            val tsv = tesseract(prog, net(img, k), 11, tsv = true) ?: return null
            val lignes = lignes(tsv, k, img.width, img.height)
            val textes = ArrayList(lignes.map { it.texte })
            if (lignes.isNotEmpty()) {
                // Two looks at each line (each errs elsewhere): small and grey, larger and in two levels.
                for ((hauteur, binaire) in VUES) {
                    tesseract(prog, empile(lignes.map { ligne(img, it, hauteur, binaire) }), 6)?.let { r -> textes += r.lines().filter { it.isNotBlank() } }
                }
            }
            return textes.joinToString("\n")
        }

        /** The lines of a tesseract [tsv] read on a picture enlarged ×[k], those with a word of 2 signs or more, top first. */
        fun lignes(tsv: String, k: Int, largeur: Int, hauteur: Int): List<Ligne> {
            class Boite(var x0: Int, var y0: Int, var x1: Int, var y1: Int, val mots: ArrayList<String> = ArrayList())
            val par = LinkedHashMap<String, Boite>()
            for (l in tsv.lines()) {
                val c = l.split('\t')
                if (c.size < 12 || c[0] != "5") continue
                val mot = c[11].trim()
                if (mot.isEmpty()) continue
                val x = c[6].toIntOrNull() ?: continue; val y = c[7].toIntOrNull() ?: continue
                val w = c[8].toIntOrNull() ?: continue; val h = c[9].toIntOrNull() ?: continue
                val b = par.getOrPut("${c[2]}.${c[3]}.${c[4]}") { Boite(x, y, x + w, y + h) }
                b.x0 = minOf(b.x0, x); b.y0 = minOf(b.y0, y); b.x1 = maxOf(b.x1, x + w); b.y1 = maxOf(b.y1, y + h)
                b.mots += mot
            }
            return par.values.filter { b -> b.mots.any { m -> m.count { it.isLetterOrDigit() } >= 2 } }
                .map { b ->
                    val x0 = b.x0 / k; val y0 = b.y0 / k
                    Ligne(x0, y0, (b.x1 / k - x0).coerceIn(1, largeur - x0), (b.y1 / k - y0).coerceIn(1, hauteur - y0), b.mots.joinToString(" "))
                }
                .sortedByDescending { it.texte.count { c -> c.isLetterOrDigit() } }.take(LIGNES_MAX).sortedBy { it.y }
        }

        /**
         * One [ligne] of [img] alone: its box with a margin, enlarged so that
         * the box is some [hauteur] pixels high (bicubic), seen through the
         * colour that parts text and background best (grey, red, green or
         * blue: the one whose best threshold separates its two populations
         * most), the text dark on light; in two levels at that threshold
         * when [binaire].
         */
        fun ligne(img: BufferedImage, ligne: Ligne, hauteur: Int = HAUTEUR_LIGNE, binaire: Boolean = true): BufferedImage {
            val mv = maxOf(2, ligne.h / 4); val mh = maxOf(4, ligne.h / 2)
            val x0 = (ligne.x - mh).coerceAtLeast(0); val y0 = (ligne.y - mv).coerceAtLeast(0)
            val x1 = (ligne.x + ligne.l + mh).coerceAtMost(img.width); val y1 = (ligne.y + ligne.h + mv).coerceAtMost(img.height)
            val f = (hauteur.toDouble() / ligne.h).coerceIn(1.0, 8.0)
            val w = maxOf(1, ((x1 - x0) * f).toInt()); val h = maxOf(1, ((y1 - y0) * f).toInt())
            val grand = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            grand.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, w, h, x0, y0, x1, y1, null); dispose()
            }
            val rgb = grand.getRGB(0, 0, w, h, null, 0, w)
            val canaux = listOf<(Int) -> Int>(
                { p -> ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000 },
                { p -> p shr 16 and 255 }, { p -> p shr 8 and 255 }, { p -> p and 255 })
            var mieux = canaux[0]; var seuilMieux = 128; var eta = -1.0
            for (c in canaux) {
                val (s, e) = otsu(rgb, c)
                if (e > eta + 1e-9) { eta = e; seuilMieux = s; mieux = c }
            }
            // The background: the side most of the box's edge is on.
            var clairs = 0; var bord = 0
            for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { bord++; if (mieux(rgb[y * w + x]) > seuilMieux) clairs++ }
            for (y in 0 until h) for (x in intArrayOf(0, w - 1)) { bord++; if (mieux(rgb[y * w + x]) > seuilMieux) clairs++ }
            val fondClair = 2 * clairs >= bord
            val sortie = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            val px = IntArray(w * h) { i -> if (binaire) { if ((mieux(rgb[i]) > seuilMieux) == fondClair) 255 else 0 } else { val v = mieux(rgb[i]); if (fondClair) v else 255 - v } }
            sortie.raster.setSamples(0, 0, w, h, 0, px)
            return sortie
        }

        /** Otsu's threshold of [px] seen through [canal], and how well it separates them (0…1). */
        private fun otsu(px: IntArray, canal: (Int) -> Int): Pair<Int, Double> {
            val hist = LongArray(256)
            for (p in px) hist[canal(p)]++
            val n = px.size.toDouble()
            var somme = 0.0; var somme2 = 0.0
            for (i in 0..255) { somme += i * hist[i].toDouble(); somme2 += i.toDouble() * i * hist[i] }
            val moy = somme / n
            val total = somme2 / n - moy * moy
            if (total < 1.0) return 128 to 0.0
            var wB = 0.0; var sB = 0.0; var meilleur = 0.0; var seuil = 128
            for (t in 0..254) {
                wB += hist[t]; sB += t * hist[t].toDouble()
                val wF = n - wB
                if (wB == 0.0 || wF == 0.0) continue
                val mB = sB / wB; val mF = (somme - sB) / wF
                val entre = wB * wF * (mB - mF) * (mB - mF) / (n * n)
                if (entre > meilleur) { meilleur = entre; seuil = t }
            }
            return seuil to meilleur / total
        }

        /** [lignes] one under the other on white, with room around each (one read for all). */
        fun empile(lignes: List<BufferedImage>): BufferedImage {
            val marge = HAUTEUR_LIGNE / 2
            val w = (lignes.maxOfOrNull { it.width } ?: 1) + 2 * marge
            val h = lignes.sumOf { it.height + marge } + marge
            val sortie = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            sortie.createGraphics().apply {
                color = java.awt.Color.WHITE; fillRect(0, 0, w, h)
                var y = marge
                for (l in lignes) { drawImage(l, marge, y, null); y += l.height + marge }
                dispose()
            }
            return sortie
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

        /**
         * tesseract on [img] (page segmentation [psm], callsign characters
         * only, its neural reader); its text, or its words with their boxes
         * when [tsv]; null when it fails.
         */
        fun tesseract(prog: String, img: BufferedImage, psm: Int, tsv: Boolean = false, delaiS: Long = 15): String? {
            val png = File.createTempFile("satme-ocr-", ".png")
            val sortie = File.createTempFile("satme-ocr-", ".txt")
            try {
                ImageIO.write(img, "png", png)
                val args = arrayListOf(prog, png.absolutePath, "stdout", "--oem", "1", "--psm", psm.toString(),
                    "-c", "tessedit_char_whitelist=$LISTE", "-c", "preserve_interword_spaces=1")
                if (tsv) args += "tsv"
                val p = ProcessBuilder(args).redirectOutput(sortie).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!p.waitFor(delaiS, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
                if (p.exitValue() != 0) return null
                return sortie.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            } finally { png.delete(); sortie.delete() }
        }
    }
}
