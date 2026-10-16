/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The pictures and videos the DATV transmission may send, chosen from the
 * page: a folder browser on this PC (read only, within the user's home,
 * removable media and SatMe's own folder; links leading out are not
 * followed), and files sent from the browser's computer, kept in
 * [envois] for reuse.
 */
class FichiersDatvPc(
    /** Where files sent from the browser go (SatMe's data folder, `datv-sources`). */
    val envois: File,
    /** Thumbnails made once, kept here. */
    private val cache: File,
    /** The folders that may be browsed (each with what is under it), and the shortcuts shown. */
    private val racines: () -> List<Raccourci> = { racinesParDefaut(envois) },
    private val ffmpeg: () -> String? = { RenduPc.ffmpeg() },
    private val maxOctets: Long = MAX_OCTETS,
) {
    /** A folder offered: its label, its path, whether it is a root of what may be browsed. */
    data class Raccourci(val nom: String, val dossier: File, val racine: Boolean = true)

    private val vignettes = Semaphore(2)

    /** The real paths of the roots (links resolved), existing ones only. */
    private fun racinesReelles(): List<Path> = racines().filter { it.racine }.mapNotNull { reel(it.dossier.toPath()) }.distinct()

    /**
     * [chemin] as a real path (links and ".." resolved) when it exists and
     * lies under one of the roots; null otherwise.
     */
    fun permis(chemin: String): Path? {
        if (chemin.isBlank() || '\u0000' in chemin) return null
        val p = runCatching { Paths.get(chemin) }.getOrNull() ?: return null
        if (!p.isAbsolute) return null
        val r = reel(p) ?: return null
        return r.takeIf { x -> racinesReelles().any { x.startsWith(it) } }
    }

    /**
     * A folder's content for the page: its sub-folders and the files of
     * [type] ("image", "video", or both otherwise), hidden ones left out,
     * with the shortcuts; [chemin] empty: the home folder.
     */
    fun liste(chemin: String, type: String): JSONObject {
        envois.mkdirs()
        val raccourcis = racines().filter { it.dossier.isDirectory }
        val depart = chemin.ifBlank { raccourcis.firstOrNull()?.dossier?.path.orEmpty() }
        val o = JSONObject().put("raccourcis", JSONArray(raccourcis.map { JSONObject().put("nom", it.nom).put("chemin", it.dossier.path) }))
        val d = permis(depart)
        if (d == null || !Files.isDirectory(d)) return o.put("ok", false).put("erreur", "dossier hors des dossiers permis ou introuvable").put("chemin", depart)
        val roots = racinesReelles()
        val parent = d.parent?.takeIf { p -> roots.any { p.startsWith(it) } }
        val exts = extensions(type)
        val entrees = ArrayList<JSONObject>()
        runCatching {
            Files.newDirectoryStream(d).use { flux ->
                for (e in flux) {
                    if (entrees.size >= LISTE_MAX) break
                    val nom = e.fileName.toString()
                    if (nom.startsWith(".")) continue
                    val r = reel(e) ?: continue
                    if (roots.none { r.startsWith(it) }) continue
                    if (Files.isDirectory(r)) entrees += JSONObject().put("nom", nom).put("chemin", e.toString()).put("dossier", true)
                    else {
                        val ext = nom.substringAfterLast('.', "").lowercase()
                        if (ext !in exts || !Files.isRegularFile(r)) continue
                        entrees += JSONObject().put("nom", nom).put("chemin", e.toString()).put("dossier", false)
                            .put("genre", if (ext in IMAGES) "image" else "video")
                            .put("taille", runCatching { Files.size(r) }.getOrDefault(0L))
                            .put("date", runCatching { Files.getLastModifiedTime(r).toMillis() }.getOrDefault(0L))
                    }
                }
            }
        }.onFailure { return o.put("ok", false).put("erreur", "dossier illisible").put("chemin", d.toString()) }
        entrees.sortWith(compareBy<JSONObject>({ !it.getBoolean("dossier") }, { it.getString("nom").lowercase() }))
        return o.put("ok", true).put("chemin", d.toString()).put("parent", parent?.toString() ?: JSONObject.NULL)
            .put("entrees", JSONArray(entrees)).put("tronque", entrees.size >= LISTE_MAX)
    }

    /**
     * A small JPEG of a picture or a video (one frame, by ffmpeg) under the
     * roots, made once and kept; null when not allowed or not readable.
     */
    fun vignette(chemin: String): ByteArray? {
        val p = permis(chemin) ?: return null
        if (!Files.isRegularFile(p)) return null
        val ext = p.fileName.toString().substringAfterLast('.', "").lowercase()
        if (ext !in IMAGES && ext !in VIDEOS) return null
        val cle = runCatching { empreinte(p.toString() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis()) }.getOrNull() ?: return null
        cache.mkdirs()
        val f = File(cache, "$cle.jpg")
        if (f.isFile && f.length() > 0) return f.readBytes()
        if (!vignettes.tryAcquire(20, TimeUnit.SECONDS)) return null
        try {
            val ok = (ext in IMAGE_IO && parImageIo(p.toFile(), f)) || parFfmpeg(p.toFile(), f, ext in VIDEOS)
            if (!ok) return null
            range()
            return f.readBytes()
        } finally { vignettes.release() }
    }

    /** Read with a subsampling that keeps a large photo out of the memory. */
    private fun parImageIo(src: File, dst: File): Boolean = runCatching {
        ImageIO.createImageInputStream(src).use { iis ->
            val r = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return false
            try {
                r.input = iis
                val pas = maxOf(1, maxOf(r.getWidth(0), r.getHeight(0)) / (VIGNETTE * 2))
                val prm = r.defaultReadParam.apply { setSourceSubsampling(pas, pas, 0, 0) }
                val im = r.read(0, prm)
                val k = VIGNETTE.toDouble() / maxOf(im.width, im.height)
                val w = maxOf(1, (im.width * minOf(1.0, k)).toInt()); val h = maxOf(1, (im.height * minOf(1.0, k)).toInt())
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = out.createGraphics()
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.color = java.awt.Color(0x0B1016); g.fillRect(0, 0, w, h)
                g.drawImage(im, 0, 0, w, h, null); g.dispose()
                ImageIO.write(out, "jpg", dst)
            } finally { r.dispose() }
        }
    }.getOrDefault(false)

    private fun parFfmpeg(src: File, dst: File, video: Boolean): Boolean {
        val ff = ffmpeg() ?: return false
        // A second in, past a black start; from the first frame when the video is shorter.
        for (ss in if (video) listOf("1", "0") else listOf("0")) {
            val c = listOf(ff, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", ss, "-i", src.path, "-frames:v", "1",
                "-vf", "scale=$VIGNETTE:$VIGNETTE:force_original_aspect_ratio=decrease", "-q:v", "5", "-f", "image2", "-update", "1", "-y", dst.path)
            val ok = runCatching {
                val pr = ProcessBuilder(c).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                runCatching { pr.outputStream.close() }
                if (!pr.waitFor(15, TimeUnit.SECONDS)) { pr.destroyForcibly(); false } else pr.exitValue() == 0
            }.getOrDefault(false)
            if (ok && dst.isFile && dst.length() > 0) return true
        }
        dst.delete()
        return false
    }

    /** The cache kept to a few hundred thumbnails, the oldest gone first. */
    private fun range() {
        val l = cache.listFiles { f -> f.name.endsWith(".jpg") } ?: return
        if (l.size <= CACHE_MAX) return
        l.sortedBy { it.lastModified() }.take(l.size - CACHE_MAX).forEach { it.delete() }
    }

    // ------------------------------------------------------------ files sent from the browser

    /** The files sent before, newest first. */
    fun envoisJson(): JSONObject {
        val l = (envois.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in IMAGES + VIDEOS } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        return JSONObject().put("ok", true).put("dossier", envois.path).put("max", maxOctets).put("fichiers", JSONArray(l.map { f ->
            JSONObject().put("nom", f.name).put("chemin", f.path).put("taille", f.length()).put("date", f.lastModified())
                .put("genre", if (f.extension.lowercase() in IMAGES) "image" else "video")
        }))
    }

    /**
     * A file sent from the page ([longueur] bytes of [e]) kept as [nom]
     * (cleaned, made unique) in [envois]: its path, or why not.
     */
    fun recoit(nom: String, longueur: Long, e: InputStream): Result<File> {
        if (longueur <= 0) return Result.failure(IllegalArgumentException("fichier vide"))
        if (longueur > maxOctets) return Result.failure(IllegalArgumentException("fichier trop gros (" + maxOctets / (1024 * 1024) + " Mo au plus)"))
        val propre = nomSur(nom) ?: return Result.failure(IllegalArgumentException("type de fichier refusé : images " +
            IMAGES.joinToString(", ") + " ; vidéos " + VIDEOS.joinToString(", ")))
        envois.mkdirs()
        if (envois.usableSpace in 1 until longueur + RESERVE) return Result.failure(IllegalStateException("plus assez de place sur le disque"))
        val tmp = File.createTempFile(".envoi-", ".part", envois)
        var reste = longueur
        val ok = runCatching {
            tmp.outputStream().buffered().use { o ->
                val b = ByteArray(64 * 1024)
                while (reste > 0) {
                    val n = e.read(b, 0, minOf(b.size.toLong(), reste).toInt())
                    if (n < 0) break
                    o.write(b, 0, n); reste -= n
                }
            }
            reste == 0L
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); return Result.failure(IllegalStateException("envoi interrompu")) }
        synchronized(this) {
            val f = libre(propre)
            return runCatching { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE); f }
                .recoverCatching { Files.move(tmp.toPath(), f.toPath()); f }
                .onFailure { tmp.delete() }
        }
    }

    /** A file sent before, removed: only one of [envois], by its name. */
    fun supprime(nom: String): Boolean {
        if (nom.isBlank() || nom != File(nom).name || nom.startsWith(".")) return false
        val f = File(envois, nom)
        if (!f.isFile || Files.isSymbolicLink(f.toPath())) return false
        val dans = runCatching { f.canonicalFile.parentFile == envois.canonicalFile }.getOrDefault(false)
        return dans && f.delete()
    }

    private fun libre(nom: String): File {
        val base = nom.substringBeforeLast('.'); val ext = nom.substringAfterLast('.')
        var f = File(envois, nom)
        var i = 2
        while (f.exists()) { f = File(envois, "$base-$i.$ext"); i++ }
        return f
    }

    companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "webp", "bmp")
        val VIDEOS = setOf("mp4", "mkv", "mov", "avi", "webm", "ts", "m4v")
        private val IMAGE_IO = setOf("png", "jpg", "jpeg", "bmp")
        const val MAX_OCTETS = 500L * 1024 * 1024
        private const val RESERVE = 200L * 1024 * 1024
        const val LISTE_MAX = 2000
        const val VIGNETTE = 160
        private const val CACHE_MAX = 600

        fun extensions(type: String): Set<String> = when (type) { "image" -> IMAGES; "video" -> VIDEOS; else -> IMAGES + VIDEOS }

        /**
         * A name safe to keep: the last part of [nom] only, letters, digits,
         * dot, dash, underscore and space, an allowed extension; null when
         * the extension is not one.
         */
        fun nomSur(nom: String): String? {
            val dernier = nom.replace('\\', '/').substringAfterLast('/')
            val ext = dernier.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGES && ext !in VIDEOS) return null
            val base = dernier.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim(' ', '.').take(80).ifBlank { "fichier" }
            return "$base.$ext"
        }

        private fun reel(p: Path): Path? = runCatching { p.toRealPath() }.getOrNull()?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

        private fun empreinte(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

        /**
         * The home folder and its usual picture, video and desktop folders,
         * removable media (/media/<user>, /run/media/<user>) and each disk
         * on it, SatMe's own folder of files sent.
         */
        fun racinesParDefaut(envois: File): List<Raccourci> {
            val home = File(System.getProperty("user.home"))
            val user = System.getProperty("user.name").orEmpty()
            val l = ArrayList<Raccourci>()
            l += Raccourci("Dossier personnel", home)
            val xdg = dossiersXdg(home)
            fun ajoute(nom: String, cle: String, vararg noms: String) {
                val f = xdg[cle] ?: noms.map { File(home, it) }.firstOrNull { it.isDirectory } ?: return
                if (f.isDirectory) l += Raccourci(nom, f, racine = false)
            }
            ajoute("Images", "XDG_PICTURES_DIR", "Images", "Pictures")
            ajoute("Vidéos", "XDG_VIDEOS_DIR", "Vidéos", "Videos")
            ajoute("Bureau", "XDG_DESKTOP_DIR", "Bureau", "Desktop")
            l += Raccourci("Fichiers envoyés", envois)
            if (!OutilsPc.windows && user.isNotEmpty() && '/' !in user) {
                for (m in listOf(File("/media", user), File("/run/media", user))) {
                    if (!m.isDirectory) continue
                    l += Raccourci("Supports amovibles", m)
                    (m.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }.forEach { l += Raccourci(it.name, it, racine = false) }
                }
            }
            return l.distinctBy { it.dossier.path }
        }

        /** The user's folders as the desktop names them (~/.config/user-dirs.dirs). */
        private fun dossiersXdg(home: File): Map<String, File> = runCatching {
            File(home, ".config/user-dirs.dirs").readLines().mapNotNull { li ->
                val m = Regex("^(XDG_\\w+_DIR)=\"(.*)\"$").find(li.trim()) ?: return@mapNotNull null
                val v = m.groupValues[2].replace("\$HOME", home.path)
                m.groupValues[1] to File(v)
            }.filter { it.second.isDirectory && it.second.absolutePath != home.absolutePath }.toMap()
        }.getOrDefault(emptyMap())
    }
}
