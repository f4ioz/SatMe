/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DiplomesEnLigneTest {

    private fun date(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
    private fun res(n: String) = javaClass.getResource("/diplomes/$n")!!.readText()

    @Test fun `lu une fois, gardé hors ligne, redemandé à son rythme`() {
        val d = Files.createTempDirectory("dip").toFile()
        var t = date("2026-10-27 08:00")
        val demandes = ArrayList<String>()
        var enLigne = true
        val tel = ActivitesEnLigne.Telechargeur { url, _, _ ->
            demandes += url
            if (!enLigne) throw java.io.IOException("hors ligne")
            when {
                url.contains("pzk") -> ActivitesEnLigne.Reponse(200, res("pzk-extrait.html"), "\"e1\"")
                url.contains("amsat.org/feed") -> ActivitesEnLigne.Reponse(200, res("amsat-ans-extrait.rss"))
                else -> ActivitesEnLigne.Reponse(200, "<html>pas un flux</html>")
            }
        }
        val a = DiplomesEnLigne(d, { t }, tel)
        assertTrue(a.rafraichit())
        assertEquals(4, demandes.size)
        assertEquals(listOf("pzk:75-2", "pzk:75"), a.liste().filter { it.id.startsWith("pzk:") }.map { it.id })
        assertTrue(a.etats().first { it.src == DiplomesEnLigne.Src.AMSAT_UK }.erreur.contains("RSS"))
        // An hour later: nothing due but the source in error.
        t += 3_600_000L; demandes.clear(); enLigne = false
        a.rafraichit()
        assertEquals(2, demandes.size)
        // A new start offline: the list read from the disk.
        val b = DiplomesEnLigne(d, { t }, tel)
        assertEquals(a.liste(), b.liste())
        assertTrue(b.liste().isNotEmpty())
        d.deleteRecursively()
    }
}
