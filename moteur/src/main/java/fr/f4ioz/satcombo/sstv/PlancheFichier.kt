/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A sheet template as one file (`.satme-planche`), to give to another
 * station: a zip holding `manifeste.json` — the format, its version, the
 * template's own saved lines ([Planche.ecrit]) — and its pictures: the
 * imported background (an ARISS series), the background picture, the
 * patches already cut out (PNG with alpha), so they look the same anywhere.
 *
 * **Nothing personal**: no received picture, the callsign and the name @
 * locator left as fields the importer's station fills — unless asked
 * ([exporte] `indicatif`).
 *
 * **Read with care**: a file from anyone. Its size, its entries (plain
 * names, never a path), their size read (not the one claimed), the pictures
 * (PNG or JPEG, their size in pixels), the version; nothing it names is used
 * as a path — its pictures are written under new names.
 */
object PlancheFichier {

    const val EXTENSION = "satme-planche"
    const val FORMAT = "satme-planche"
    /** Its own type, for the systems that name one (the file is a zip). */
    const val MIME = "application/x-satme-planche"
    /** The version written; a file is read when its `lisiblePar` is at most this. */
    const val VERSION = 1
    const val MANIFESTE = "manifeste.json"
    /** A whole file, at most (what a page may send). */
    const val TAILLE_MAX = 25L * 1024 * 1024
    private const val ENTREE_MAX = 16 * 1024 * 1024
    private const val TOTAL_MAX = 48L * 1024 * 1024
    private const val ENTREES_MAX = 8
    private const val TEXTE_MAX = 64 * 1024
    private const val COTE_MAX = 12_000
    private const val PIXELS_MAX = 60_000_000L

    /** A file refused, and why: zip, manifeste, version, chemin, image, taille. */
    class Refus(val raison: String) : Exception(raison)

    /** Starts as a zip does: the first bytes of a `.satme-planche`. */
    fun estZip(b: ByteArray): Boolean = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
        b[2] == 3.toByte() && b[3] == 4.toByte()

    /** "png" or "jpg" by the first bytes; null for anything else. */
    fun genre(b: ByteArray): String? = when {
        b.size >= 8 && (b[0].toInt() and 0xFF) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
            b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() -> "png"
        b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 && (b[2].toInt() and 0xFF) == 0xFF -> "jpg"
        else -> null
    }

    /** Width and height of a PNG or JPEG from its header, without decoding it; null when not found. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        return when (genre(b)) {
            "png" -> if (b.size >= 24 && String(b, 12, 4, Charsets.ISO_8859_1) == "IHDR") {
                val w = u32(16); val h = u32(20)
                if (w in 1..Int.MAX_VALUE.toLong() && h in 1..Int.MAX_VALUE.toLong()) w.toInt() to h.toInt() else null
            } else null
            "jpg" -> {
                // The markers, one after the other, to the frame's (SOF0..SOF15 but DHT, JPG, DAC).
                var i = 2
                while (i + 9 < b.size) {
                    if ((b[i].toInt() and 0xFF) != 0xFF) return null
                    val mk = b[i + 1].toInt() and 0xFF
                    if (mk == 0xFF) { i++; continue }
                    if (mk == 0xD8 || mk in 0xD0..0xD7 || mk == 0x01) { i += 2; continue }
                    if (mk == 0xD9 || mk == 0xDA) return null
                    val l = u16(i + 2)
                    if (l < 2) return null
                    if (mk in 0xC0..0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC) {
                        val h = u16(i + 5); val w = u16(i + 7)
                        return if (w > 0 && h > 0) w to h else null
                    }
                    i += 2 + l
                }
                null
            }
            else -> null
        }
    }

    /** A picture fit to be read: PNG or JPEG ([permis]), its size in pixels sane. */
    private fun verifieImage(b: ByteArray, permis: Set<String> = setOf("png", "jpg")): String {
        val g = genre(b)?.takeIf { it in permis } ?: throw Refus("image")
        val (w, h) = dimensions(b) ?: throw Refus("image")
        if (w > COTE_MAX || h > COTE_MAX || w.toLong() * h > PIXELS_MAX) throw Refus("image")
        return g
    }

    /** The file's name: `SatMe_Modele_<name>.satme-planche`, the name kept to plain letters. */
    fun nomFichier(m: Planche.Modele): String {
        val n = java.text.Normalizer.normalize(m.nom, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(40).ifBlank { "planche" }
        return "SatMe_Modele_$n.$EXTENSION"
    }

    /**
     * What travels of [m]: no picture received (each box's version chosen
     * kept, not its copy); the callsign written as a
     * free text when [indicatif] is given (the importer sees it as typed).
     */
    fun sansPersonnel(m: Planche.Modele, indicatif: String? = null): Planche.Modele {
        fun dur(l: List<Planche.Texte>) = if (indicatif.isNullOrBlank()) l else l.map {
            if (it.champ == Planche.Champ.INDICATIF) it.copy(champ = Planche.Champ.LIBRE, libre = indicatif.trim().take(40)) else it
        }
        // The version chosen for each box travels (« Fusion » in box 3), never which picture was in it.
        return m.copy(images = emptyMap(), textes = dur(m.textes), textesOrigine = dur(m.textesOrigine),
            versions = m.versions.mapValues { it.value.copy(copieMain = false) }.filterValues { !it.auto })
    }

    /**
     * [m] as one file. [detoure]: a patch file with its surround made
     * transparent, as PNG (the platform decodes); null keeps the file as it
     * is, cut out where it is drawn. [indicatif]: the callsign kept written.
     */
    fun exporte(m: Planche.Modele, r: Planche.Rangement, detoure: (File) -> ByteArray?, indicatif: String? = null): ByteArray {
        val fichiers = LinkedHashMap<String, ByteArray>()
        val fond = r.fond(m)?.readBytes()?.let { b -> genre(b)?.let { "fond.$it" to b } }
        fond?.let { fichiers[it.first] = it.second }
        val decor = when {
            PlancheDecor.integre(m.decor) != null -> m.decor
            else -> r.decor(m)?.readBytes()?.let { b -> genre(b)?.let { g -> "decor.$g".also { fichiers[it] = b } } } ?: ""
        }
        val ecussons = m.ecussons.mapNotNull { e ->
            val f = r.fichier(e.fichier) ?: return@mapNotNull null
            val n = "ecusson${fichiers.keys.count { it.startsWith("ecusson") } + 1}.png"
            val coupe = if (e.sansContour) runCatching { detoure(f) }.getOrNull()?.takeIf { genre(it) == "png" } else null
            if (coupe != null) { fichiers[n] = coupe; e.copy(fichier = n, sansContour = false) }
            else {
                val b = f.readBytes(); val g = genre(b) ?: return@mapNotNull null
                val nn = n.removeSuffix(".png") + ".$g"
                fichiers[nn] = b; e.copy(fichier = nn)
            }
        }
        val x = sansPersonnel(m, indicatif).copy(fond = fond?.first ?: "", decor = decor, ecussons = ecussons)
        val manifeste = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("lisiblePar", 1)
            .put("logiciel", "SatMe").put("nom", x.nom)
            .put("indicatifEcrit", !indicatif.isNullOrBlank())
            .put("fichiers", JSONArray(fichiers.keys.toList()))
            .put("planche", Planche.ecrit(x))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(MANIFESTE)); z.write(manifeste.toString(2).toByteArray(Charsets.UTF_8)); z.closeEntry()
            fichiers.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    /** An entry read whole, no larger than [max] whatever its header says. */
    private fun litBorne(i: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(); val tampon = ByteArray(16 * 1024); var n = 0
        while (true) {
            val k = i.read(tampon); if (k < 0) break
            n += k; if (n > max) throw Refus("taille")
            out.write(tampon, 0, k)
        }
        return out.toByteArray()
    }

    /** The entries of a `.satme-planche`, checked: plain names, few, none twice, none too large. */
    fun entrees(octets: ByteArray): Map<String, ByteArray> {
        if (octets.size > TAILLE_MAX) throw Refus("taille")
        if (!estZip(octets)) throw Refus("zip")
        val l = LinkedHashMap<String, ByteArray>(); var total = 0L
        try {
            ZipInputStream(octets.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = e.name
                    // Zip-slip: a name only, never a folder, a path or a way up.
                    if (e.isDirectory || '/' in n || '\\' in n || ':' in n || !PlancheDecor.nomFichier(n)) throw Refus("chemin")
                    if (n in l) throw Refus("zip")
                    if (l.size >= ENTREES_MAX) throw Refus("zip")
                    val b = litBorne(z, if (n == MANIFESTE) TEXTE_MAX * 2 else ENTREE_MAX)
                    total += b.size; if (total > TOTAL_MAX) throw Refus("taille")
                    l[n] = b
                }
            }
        } catch (e: Refus) { throw e } catch (e: Exception) { throw Refus("zip") }
        if (l.isEmpty()) throw Refus("zip")
        return l
    }

    /** What the file holds, checked, not yet kept: the template (its pictures named as in the file) and the pictures. */
    class Lu(val modele: Planche.Modele, val images: Map<String, ByteArray>, val version: Int)

    fun lit(octets: ByteArray): Lu {
        val l = entrees(octets)
        val j = l[MANIFESTE]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: throw Refus("manifeste")
        if (j.optString("format") != FORMAT) throw Refus("manifeste")
        val version = j.optInt("version", 0)
        if (version < 1) throw Refus("manifeste")
        // A later version still readable says so: what it adds, this one leaves aside.
        if (j.optInt("lisiblePar", version) > VERSION) throw Refus("version")
        val texte = j.optString("planche").takeIf { it.isNotBlank() && it.length <= TEXTE_MAX } ?: throw Refus("manifeste")
        val m = Planche.lit("import", texte) ?: throw Refus("manifeste")
        val images = HashMap<String, ByteArray>()
        fun prend(n: String): Boolean {
            if (n.isBlank()) return false
            val b = l[n] ?: throw Refus("image")
            verifieImage(b); images[n] = b
            return true
        }
        val fond = m.fond.takeIf { prend(it) } ?: ""
        val decor = when {
            PlancheDecor.integre(m.decor) != null -> m.decor
            m.decor.isNotBlank() && prend(m.decor) -> m.decor
            else -> ""
        }
        val ecussons = m.ecussons.take(Planche.ECUSSONS_MAX).filter { prend(it.fichier) }
        return Lu(borne(m.copy(fond = fond, decor = decor, ecussons = ecussons)), images, version)
    }

    /** A template from outside kept within what SatMe makes: as many boxes and texts, inside the sheet. */
    fun borne(m: Planche.Modele): Planche.Modele = m.copy(
        nom = m.nom.take(60), titre = m.titre.take(120),
        ratio = m.ratio.takeIf { it.isFinite() && it in 0.2f..5f } ?: 1.414f,
        cases = m.cases.take(60).map { it.bornee() },
        textes = m.textes.take(20).map { it.copy(zone = it.zone.bornee(), libre = it.libre.take(200)) },
        images = emptyMap(),
        versions = m.versions.filterKeys { it in 0 until minOf(60, m.cases.size) }.mapValues { it.value.copy(copieMain = false) }.filterValues { !it.auto },
        origine = m.origine.take(60).map { it.bornee() },
        textesOrigine = m.textesOrigine.take(20).map { it.copy(zone = it.zone.bornee(), libre = it.libre.take(200)) },
        ecussons = m.ecussons.take(Planche.ECUSSONS_MAX).map { it.copy(zone = Planche.ecussonBorne(it.zone)) })

    /** [nom], or "[nom] (2)", "(3)"… when a template of [existants] has it already. */
    fun nomLibre(nom: String, existants: Collection<String>): String {
        val n = nom.trim().ifBlank { "SatMe" }.take(54)
        if (existants.none { it.equals(n, ignoreCase = true) }) return n
        val base = n.replace(Regex("\\s*\\(\\d+\\)$"), "")
        var k = 2
        while (existants.any { it.equals("$base ($k)", ignoreCase = true) }) k++
        return "$base ($k)"
    }

    /**
     * A `.satme-planche` made a new template of [r]: its pictures written
     * under new names, its name made unique, never over another template.
     * Throws [Refus] when the file is not one SatMe can read.
     */
    fun importe(octets: ByteArray, r: Planche.Rangement): Planche.Modele {
        val lu = lit(octets)
        val existants = r.modeles()
        var id = r.nouvelId(); var k = 0
        while (existants.any { it.id == id } || File(r.dossier, "$id.planche").exists()) id = r.nouvelId() + "-" + (++k)
        val ecrits = ArrayList<File>()
        fun garde(n: String, nouveau: (String) -> String): String {
            val b = lu.images.getValue(n)
            var f = File(r.dossier, nouveau(genre(b)!!))
            while (f.exists()) f = File(r.dossier, nouveau(genre(b)!!))
            f.writeBytes(b); ecrits += f
            return f.name
        }
        try {
            val m = lu.modele
            val fond = if (m.fond.isNotBlank()) garde(m.fond) { "fond_$id.$it" } else ""
            val decor = if (m.decor.isNotBlank() && PlancheDecor.integre(m.decor) == null) garde(m.decor) { r.nouveauNom("decor", it) } else m.decor
            val ecussons = m.ecussons.map { it.copy(fichier = garde(it.fichier) { g -> r.nouveauNom("ecusson", g) }) }
            val n = m.copy(id = id, nom = nomLibre(m.nom, existants.map { it.nom }), fond = fond, decor = decor, ecussons = ecussons)
            r.enregistre(n)
            return n
        } catch (e: Exception) {
            ecrits.forEach { it.delete() }
            throw if (e is Refus) e else Refus("zip")
        }
    }
}
