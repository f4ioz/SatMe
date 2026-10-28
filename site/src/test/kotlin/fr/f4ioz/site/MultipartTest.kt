/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site

import fr.f4ioz.site.http.Multipart
import fr.f4ioz.site.http.TropGros
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** The multipart reader: fields, a file whose bytes look like the boundary, the limits. */
class MultipartTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun corps(f: String, fichier: ByteArray): ByteArray =
        ("preambule\r\n--$f\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json\r\n\r\n{\"a\":1}\r\n" +
            "--$f\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"x.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray() +
            fichier + "\r\n--$f--\r\n".toByteArray()

    @Test fun `a file that almost contains the boundary comes back whole`() {
        val f = "SatMe-abc"
        val piege = "\r\n--SatMe-ab\r\n--SatMe-a\r\r\n-\r\n--SatMe-abX--\r\n--".toByteArray() + ByteArray(70_000) { (it * 7).toByte() } + "\r\n-".toByteArray()
        val m = Multipart.lit(corps(f, piege).inputStream(), f, 1 shl 20, tmp.root)
        assertEquals("{\"a\":1}", m.champs["meta"])
        val x = m.fichiers.single()
        assertEquals("fichier", x.nom); assertEquals("x.bin", x.nomFichier); assertEquals("application/octet-stream", x.type)
        assertArrayEquals(piege, x.f.readBytes())
        assertEquals(piege.size.toLong(), x.taille)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(piege).joinToString("") { "%02x".format(it) }, x.sha256)
        m.efface()
    }

    @Test fun `the boundary from the content type, the limits`() {
        assertEquals("abc", Multipart.frontiere("multipart/form-data; boundary=abc"))
        assertEquals("a b", Multipart.frontiere("Multipart/Form-Data; charset=utf-8; boundary=\"a b\""))
        assertNull(Multipart.frontiere("application/json"))
        try { Multipart.lit(corps("F", ByteArray(5000)).inputStream(), "F", 1000, tmp.root); throw AssertionError() } catch (e: TropGros) {}
        try { Multipart.lit(corps("F", ByteArray(10)).copyOf(150).inputStream(), "F", 1 shl 20, tmp.root); throw AssertionError() } catch (e: IllegalArgumentException) {}
        assertEquals(0, tmp.root.listFiles()!!.size)
    }
}
