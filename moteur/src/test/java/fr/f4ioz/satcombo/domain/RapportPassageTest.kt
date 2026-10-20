/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** The pass report: its quality summary, its HTML (pictures inside), its ZIP. */
class RapportPassageTest {

    private val t0 = 1_791_800_000_000L

    private fun base() = RapportPassage.Rapport("27607_$t0", "SO-50", 27607, t0, t0 + 600_000L, "JN18FS", "F4XYZ",
        points = (0..10).map { RapportPassage.Point(t0 + it * 60_000L, 100.0 + it * 20, if (it <= 5) it * 8.0 else (10 - it) * 8.0) })

    /** Lines with a signal [snr] dB above the noise for the middle [part] of the pass. */
    private fun cascade(part: Double, snr: Float) = SpectrePassage.Bande(27607, "SO-50", 25_000.0, (0 until 100).map { i ->
        val avec = i >= (50 - 50 * part).toInt() && i < (50 + 50 * part).toInt()
        SpectrePassage.Ligne(t0 + i * 6_000L, FloatArray(32) { k -> if (avec && k == 16) -100f + snr else -100f })
    })

    @Test
    fun la_qualite_pese_le_temps_avec_signal_le_pic_et_les_decodages() {
        val vide = RapportPassage.qualite(base())
        // Nothing measured, nothing decoded.
        assertNull(vide.partSignal); assertNull(vide.snrPicDb); assertEquals(0, vide.score); assertEquals("Faible", vide.libelle)
        val bon = RapportPassage.qualite(base().copy(cascade = cascade(0.6, 30f),
            contacts = listOf(RapportPassage.Contact(t0 + 200_000L, "F5ABC")),
            evenements = listOf(RapportPassage.Evenement(t0 + 100_000L, "APRS", "F1AAA"), RapportPassage.Evenement(t0 + 101_000L, "FT8", "CQ F1AAA JN18"))))
        assertEquals(0.6, bon.partSignal!!, 0.02)
        assertEquals(30.0, bon.snrPicDb!!, 0.01)
        assertEquals(3, bon.decodes)
        // 45 × 0.6 + 30 × 1 + 25 × 3/5 = 72.
        assertEquals(72, bon.score); assertEquals("Bon", bon.libelle)
        val sMetre = RapportPassage.qualite(base().copy(niveauUnite = "S",
            niveaux = (0 until 60).map { RapportPassage.Niveau(t0 + it * 10_000L, if (it in 20..39) 5.0 else 0.0) }))
        assertEquals(20.0 / 60, sMetre.partSignal!!, 0.01)
        // Its own scale: no dB claimed.
        assertNull(sMetre.snrPicDb)
    }

    @Test
    fun le_html_tient_tout_en_un_fichier() {
        val png = Png.encode(Png.Rgb(4, 3))
        val r = base().copy(cascade = cascade(0.4, 20f), kp = 4.3, kpMs = t0,
            station = listOf(RapportPassage.Materiel("Poste", "IC-9700"), RapportPassage.Materiel("Antenne", "Yagi 2 m <croisée>")),
            images = listOf(RapportPassage.Image(t0 + 240_000L, "PD120", "image/png", png, "a.png")),
            contacts = listOf(RapportPassage.Contact(t0 + 120_000L, "F5ABC", "JN03", "59", "57", "FM", "Jean")),
            evenements = listOf(RapportPassage.Evenement(t0 + 60_000L, "APRS", "F1AAA-7", "hello"),
                RapportPassage.Evenement(t0 + 300_000L, "PHONIE", "CQ CQ de F1AAA")),
            tramesTelemesure = 12, tramesTelemesureValides = 10,
            sons = listOf(RapportPassage.Fichier("SatMe_SO-50.wav", 3_000_000L, "sons/SatMe_SO-50.wav", t0, 590_000L)))
        val h = RapportPassage.html(r)
        assertTrue(h.startsWith("<!doctype html>"))
        // The header, the station from the Montage, the Kp.
        assertTrue(h.contains("SO-50") && h.contains("IC-9700") && h.contains("Yagi 2 m &lt;croisée&gt;") && h.contains("Kp"))
        assertTrue(h.contains("AOS") && h.contains("TCA") && h.contains("LOS") && h.contains("40.0°"))
        // The waterfall and the picture inside the file; no other address to fetch.
        assertTrue(h.contains("<image") && h.contains("data:image/png;base64,"))
        assertTrue(h.contains("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)))
        assertFalse(Regex("src=\"(https?:|/)").containsMatchIn(h))
        // The events on the timeline, the decodes, the contacts, the player.
        assertTrue(h.contains("F1AAA-7") && h.contains("CQ CQ de F1AAA") && h.contains("12 trames") && h.contains("F5ABC"))
        assertTrue(h.contains("<audio") && h.contains("sons/SatMe_SO-50.wav"))
        assertTrue(h.contains("Qualité du passage"))
        // Without players (the single file sent alone): the name only.
        assertFalse(RapportPassage.html(r.copy(sons = r.sons.map { it.copy(url = null) }), RapportPassage.Liens(lecteurs = false)).contains("<audio"))
    }

    @Test
    fun le_zip_range_le_rapport_et_ses_fichiers() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-rapport-" + System.nanoTime()).apply { mkdirs() }
        try {
            val wav = File(d, "a.wav").apply { writeBytes(ByteArray(100)) }
            val o = ByteArrayOutputStream()
            RapportPassage.zip("<html></html>", listOf("sons/a.wav" to wav, "sons/a.wav" to wav, "x/absent" to File(d, "non")), o, listOf("cascade.png" to byteArrayOf(1)))
            val noms = ArrayList<String>()
            ZipInputStream(ByteArrayInputStream(o.toByteArray())).use { z -> while (true) { noms += (z.nextEntry ?: break).name } }
            assertEquals(listOf("rapport.html", "cascade.png", "sons/a.wav"), noms)
        } finally { d.deleteRecursively() }
        assertEquals("SatMe_rapport_SO-50_20261012_1013Z.html", RapportPassage.nomFichier(base(), "html"))
    }
}
