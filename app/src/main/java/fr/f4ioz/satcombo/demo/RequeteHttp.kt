/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The head of an HTTP request: its method, its path and the length of what
 * follows. Read byte by byte, so the body stays in the stream, untouched: a
 * template sent from the PC is a picture, not text.
 */
object RequeteHttp {

    class Requete(val methode: String, val chemin: String, val longueur: Long)

    /** A head larger than this is not a browser asking for a page. */
    private const val TETE_MAX = 16 * 1024

    /** The request line and the headers, up to the blank line; null when malformed or too long. */
    fun lit(e: InputStream): Requete? {
        val tete = ByteArrayOutputStream()
        // The last bytes read: the head ends on an empty line (CRLF CRLF, or LF LF).
        var b = 0; var c = 0
        while (true) {
            val x = e.read()
            if (x < 0) { if (tete.size() == 0) return null else break }
            tete.write(x)
            if (tete.size() > TETE_MAX) return null
            if (x == '\n'.code && (c == '\n'.code || (c == '\r'.code && b == '\n'.code))) break
            b = c; c = x
        }
        val lignes = tete.toByteArray().toString(Charsets.ISO_8859_1).split('\n').map { it.trimEnd('\r') }
        val premiere = lignes.firstOrNull()?.split(' ') ?: return null
        if (premiere.size < 2 || premiere[0].isBlank()) return null
        val longueur = lignes.drop(1).firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        return Requete(premiere[0].uppercase(), premiere[1], longueur.coerceAtLeast(0L))
    }

    /** The body announced by [r], read whole; null when larger than [max] or cut short. */
    fun corps(e: InputStream, r: Requete, max: Long): ByteArray? {
        if (r.longueur > max) return null
        val out = ByteArray(r.longueur.toInt())
        var lu = 0
        while (lu < out.size) {
            val n = e.read(out, lu, out.size - lu)
            if (n < 0) return null
            lu += n
        }
        return out
    }
}
