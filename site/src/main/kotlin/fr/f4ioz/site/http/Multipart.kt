/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.http

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A `multipart/form-data` body read as it comes (SatMe-PC's publication API,
 * v1): the text fields kept in memory ([CHAMP_MAX] each), the file parts
 * written to temporary files with their SHA-256, [max] bytes in all at most
 * ([TropGros] beyond).
 */
class Multipart private constructor() {

    /** A file part: its name in the form, the file name sent, its type, where it was written, its size and SHA-256 (hex). */
    class Fichier(val nom: String, val nomFichier: String, val type: String, val f: File, val taille: Long, val sha256: String)

    val champs = LinkedHashMap<String, String>()
    val fichiers = ArrayList<Fichier>()

    /** Removes the temporary files. */
    fun efface() = fichiers.forEach { it.f.delete() }

    companion object {
        const val CHAMP_MAX = 64 * 1024

        /** The boundary of a `multipart/form-data` Content-Type, else null. */
        fun frontiere(contentType: String?): String? {
            val c = contentType ?: return null
            if (!c.trim().lowercase().startsWith("multipart/form-data")) return null
            return c.split(';').map { it.trim() }.firstOrNull { it.lowercase().startsWith("boundary=") }
                ?.substringAfter('=')?.trim('"')?.takeIf { it.isNotEmpty() && it.length <= 200 }
        }

        /** Reads [entree] (the body) cut by [frontiere]; files go to [dossier]. */
        fun lit(entree: InputStream, frontiere: String, max: Long, dossier: File): Multipart {
            val m = Multipart()
            val i = BufferedInputStream(Limite(entree, max), 64 * 1024)
            try {
                // The preamble, up to the first boundary.
                val premier = ("--$frontiere").toByteArray(Charsets.ISO_8859_1)
                if (!copieJusqua(i, premier, null)) throw IllegalArgumentException("multipart sans frontière")
                val delim = ("\r\n--$frontiere").toByteArray(Charsets.ISO_8859_1)
                while (true) {
                    // After a boundary: « -- » (the end) or CRLF (a part).
                    val a = i.read(); val b = i.read()
                    if (a == '-'.code && b == '-'.code) break
                    if (a != '\r'.code || b != '\n'.code) throw IllegalArgumentException("multipart mal formé")
                    val entetes = entetes(i)
                    val disp = entetes["content-disposition"].orEmpty()
                    val nom = parametre(disp, "name") ?: throw IllegalArgumentException("partie sans nom")
                    val nomFichier = parametre(disp, "filename")
                    if (nomFichier == null) {
                        val o = Plafond(CHAMP_MAX)
                        if (!copieJusqua(i, delim, o)) throw IllegalArgumentException("multipart tronqué")
                        if (m.champs.size < 32) m.champs.putIfAbsent(nom, String(o.toByteArray(), Charsets.UTF_8))
                    } else {
                        dossier.mkdirs()
                        val f = File.createTempFile("partie", ".part", dossier)
                        val md = MessageDigest.getInstance("SHA-256")
                        var taille = 0L
                        val ok = try {
                            f.outputStream().buffered().use { o ->
                                copieJusqua(i, delim, object : OutputStream() {
                                    override fun write(x: Int) { o.write(x); md.update(x.toByte()); taille++ }
                                })
                            }
                        } catch (e: Exception) { f.delete(); throw e }
                        if (!ok) { f.delete(); throw IllegalArgumentException("multipart tronqué") }
                        m.fichiers += Fichier(nom, nomFichier, entetes["content-type"].orEmpty(), f, taille, md.digest().joinToString("") { "%02x".format(it) })
                        if (m.fichiers.size > 4) throw IllegalArgumentException("trop de fichiers")
                    }
                }
                return m
            } catch (e: Exception) { m.efface(); throw e }
        }

        /** A part's headers (names in lower case), up to the empty line; 8 KB at most. */
        private fun entetes(i: InputStream): Map<String, String> {
            val r = HashMap<String, String>()
            val ligne = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val c = i.read()
                if (c < 0) throw IllegalArgumentException("multipart tronqué")
                if (++total > 8192) throw IllegalArgumentException("en-têtes de partie trop longs")
                if (c == '\n'.code) {
                    val l = String(ligne.toByteArray(), Charsets.UTF_8).trimEnd('\r')
                    ligne.reset()
                    if (l.isEmpty()) return r
                    val k = l.substringBefore(':', "").trim().lowercase()
                    if (k.isNotEmpty()) r[k] = l.substringAfter(':').trim()
                } else ligne.write(c)
            }
        }

        /** `name="meta"` in a Content-Disposition. */
        private fun parametre(disp: String, cle: String): String? = disp.split(';').map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim().equals(cle, true) && it.contains('=') }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")

        /**
         * Copies [i] to [o] (null: dropped) up to [d], which is consumed and not copied (a streaming
         * Knuth–Morris–Pratt): true when [d] was found, false at the end of the stream.
         */
        private fun copieJusqua(i: InputStream, d: ByteArray, o: OutputStream?): Boolean {
            val echec = IntArray(d.size)
            var k = 0
            for (x in 1 until d.size) {
                while (k > 0 && d[x] != d[k]) k = echec[k - 1]
                if (d[x] == d[k]) k++
                echec[x] = k
            }
            var j = 0
            while (true) {
                val c = i.read()
                if (c < 0) return false
                val b = c.toByte()
                val avant = j
                while (j > 0 && b != d[j]) j = echec[j - 1]
                if (b == d[j]) j++
                // What fell out of the partial match: the first bytes of d[0, avant) + b.
                val sortis = avant + 1 - j
                if (o != null) for (x in 0 until sortis) o.write(if (x < avant) d[x].toInt() else c)
                if (j == d.size) return true
            }
        }
    }

    /** At most [max] bytes read from [e], [TropGros] beyond. */
    private class Limite(private val e: InputStream, private val max: Long) : InputStream() {
        private var lus = 0L
        override fun read(): Int { val c = e.read(); if (c >= 0 && ++lus > max) throw TropGros(max); return c }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = e.read(b, off, len)
            if (n > 0) { lus += n; if (lus > max) throw TropGros(max) }
            return n
        }
    }

    /** A text field, [max] bytes at most. */
    private class Plafond(private val max: Int) : ByteArrayOutputStream() {
        override fun write(b: Int) { if (size() >= max) throw IllegalArgumentException("champ trop long"); super.write(b) }
    }
}
