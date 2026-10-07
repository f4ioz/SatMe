/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.demo.RequeteHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class RequeteHttpTest {

    @Test
    fun une_page_demandee() {
        val r = RequeteHttp.lit(ByteArrayInputStream("GET /c/abc/planche HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()))!!
        assertEquals("GET", r.methode); assertEquals("/c/abc/planche", r.chemin); assertEquals(0L, r.longueur)
    }

    @Test
    fun un_modele_envoye_reste_entier_dans_le_flux() {
        val image = ByteArray(3000) { (it * 7).toByte() }
        // Bytes that look like an end of head, inside the body: they stay the body's.
        image[10] = '\r'.code.toByte(); image[11] = '\n'.code.toByte(); image[12] = '\r'.code.toByte(); image[13] = '\n'.code.toByte()
        val tete = "POST /c/abc/planche/importe?nom=ARISS HTTP/1.1\r\ncontent-LENGTH: ${image.size}\r\n\r\n".toByteArray()
        val e = ByteArrayInputStream(tete + image)
        val r = RequeteHttp.lit(e)!!
        assertEquals("POST", r.methode); assertEquals(3000L, r.longueur)
        assertArrayEquals(image, RequeteHttp.corps(e, r, 10_000L))
    }

    @Test
    fun trop_gros_ou_coupe_rien() {
        val e = ByteArrayInputStream("POST /x HTTP/1.1\nContent-Length: 50\n\nabc".toByteArray())
        val r = RequeteHttp.lit(e)!!
        assertEquals(50L, r.longueur)
        assertNull(RequeteHttp.corps(e, r, 10L))
        assertNull(RequeteHttp.corps(e, r, 100L))
        assertNull(RequeteHttp.lit(ByteArrayInputStream(ByteArray(0))))
        assertNull(RequeteHttp.lit(ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() })))
    }
}
