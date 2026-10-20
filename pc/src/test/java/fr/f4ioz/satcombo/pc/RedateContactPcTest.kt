/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A contact given another time on SatMe-PC: az/el where the satellite was then, the log in order, the ADIF. */
class RedateContactPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-redate-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    @Test
    fun heure_changee_az_el_ordre_adif() {
        // RS-44's elements, in the cache (no network in a test).
        File(dossier, "amsat-tle.txt").writeText("RS-44\n" +
            "1 44909U 98067A   26284.17638745  .00009430  00000-0  18014-3 0  9996\n" +
            "2 44909  51.6312  86.0933 0006713 248.1316 111.8959 15.48821882589734\n")
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.settings.callsign = "F4IOZ"
        st.demarre()
        // The bundled elements and the station's place, loaded in the background.
        val fin = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < fin && (st.etat.value.observateur == null || st.etat.value.satellites.none { it.catalogNumber == 44909 })) Thread.sleep(50)
        val maintenant = System.currentTimeMillis()
        val t0 = maintenant - 3_600_000L
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso.json"))) { maintenant }
        carnet.store.save(listOf(
            LogEntry(t0, "RS-44", 44909, 1.0, 2.0, callsign = "F5ABC", mode = "USB"),
            LogEntry(t0 - 600_000L, "QO-100", fr.f4ioz.satcombo.domain.Qo100.NORAD, 160.0, 32.0, callsign = "G4AAA", mode = "USB")))
        val nouveau = fr.f4ioz.satcombo.domain.RedateContact.lis(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t0 - 7_200_000L)),
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t0 - 7_200_000L)))!!
        val r = carnet.redate(t0, nouveau)!!
        assertEquals(nouveau, r.contact.timeMs)
        // Where RS-44 was at the new time (the engine's own answer), not the old 1°/2°.
        val attendu = st.contactApres(44909, nouveau, "-", "", "", "", "", "")!!
        assertEquals(attendu.azimuthDeg, r.contact.azimuthDeg, 1e-9)
        assertEquals(attendu.elevationDeg, r.contact.elevationDeg, 1e-9)
        assertNotEquals(1.0, r.contact.azimuthDeg, 1e-9)
        // Now older than the QO-100 one: after it, newest first.
        assertEquals(listOf("G4AAA", "F5ABC"), carnet.contacts().map { it.callsign })
        assertEquals(listOf("G4AAA", "F5ABC"), carnet.store.load().map { it.callsign })
        val adif = carnet.adif(setOf(nouveau))
        val hhmmss = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(nouveau))
        assertTrue(adif, adif.contains("<TIME_ON:6>$hhmmss"))
        // The old key is gone.
        assertTrue(carnet.contacts().none { it.timeMs == t0 })
    }
}
