/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.sstv.RapportEssai
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** The event's report as a PDF on the PC: a valid file, read back, its text and its fonts inside, made without the network. */
class RapportPdfPcTest {

    @Test fun le_pdf_du_rapport_se_relit_et_se_fait_sans_reseau() {
        // Any connection asked for (a font, a picture fetched) goes through here: none may be.
        val demandes = ArrayList<URI>()
        val avant = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { synchronized(demandes) { demandes += uri }; throw IOException("pas de réseau : $uri") }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
        val octets = try {
            I18n.apply("fr", "fr")
            RapportPdfPc.pdf(RapportEssai.rapport())
        } finally { ProxySelector.setDefault(avant) }
        assertTrue("réseau demandé : $demandes", demandes.isEmpty())
        // To look at it: SATME_RAPPORT_PDF=/tmp/x.pdf ./gradlew :pc:test --tests '*RapportPdfPcTest*'
        System.getenv("SATME_RAPPORT_PDF")?.takeIf { it.isNotBlank() }?.let { java.io.File(it).writeBytes(octets) }

        assertTrue(String(octets, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-"))
        Loader.loadPDF(octets).use { doc ->
            assertTrue(doc.numberOfPages >= 2)
            val a4 = doc.getPage(0).mediaBox
            assertEquals(595.3f, a4.width, 0.5f); assertEquals(841.9f, a4.height, 0.5f)
            val texte = PDFTextStripper().getText(doc)
            assertTrue(texte.contains("ARISS série 34"))
            assertTrue(texte.contains("F4IOZ") && texte.contains("IC-9700"))
            assertTrue(texte.contains("page 1/${doc.numberOfPages}"))
            // The symbols of the report, in the embedded font.
            assertTrue(texte.contains("✓") && texte.contains("✗") && texte.contains("≈") && texte.contains("°"))
            assertTrue(doc.documentInformation.title.contains("ARISS série 34"))
            for (p in doc.pages) for (n in p.resources.fontNames) assertTrue(p.resources.getFont(n).isEmbedded)
            // The sheet and the slides: pictures in the first pages.
            assertTrue(doc.pages.take(2).sumOf { p -> p.resources.xObjectNames.count { p.resources.isImageXObject(it) } } >= 2)
        }
    }

    @Test fun le_pdf_vient_par_la_route_du_rapport() {
        I18n.apply("fr", "fr")
        val r = RapportEssai.rapport(avecChaine = false)
        val o = RapportPdfPc.pdf(r)
        Loader.loadPDF(o).use { assertTrue(PDFTextStripper().getText(it).contains("Les diapos")) }
    }
}
