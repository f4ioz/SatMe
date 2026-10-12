/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTelephoneTest {

    /** A phone's backup as ConfigBackup writes it (a few keys). */
    private val sauvegarde = """{"app":"SatMe","version":2,"stores":{"satcombo_settings":{
        "callsign":{"t":"s","v":"F4IOZ"},
        "loc_locator":{"t":"s","v":"JN18FS"},
        "rotor_max_az":{"t":"i","v":450},
        "rotor_az_stop":{"t":"s","v":"SOUTH"},
        "rotor_enabled":{"t":"b","v":true},
        "rotor_usb_index":{"t":"i","v":3},
        "rotor_usb_cle":{"t":"s","v":"A10KX9ZB"},
        "civ_usb_index":{"t":"i","v":1},
        "ts2000_cle":{"t":"s","v":"FT2232H"}
      },"satcombo_favorites":{}}}"""

    @Test
    fun les_reglages_passent_sauf_les_ports_du_telephone_et_le_rotor_attend() {
        val st = StockageMemoire()
        val n = ImportTelephone.importe(sauvegarde, st).getOrThrow()
        assertEquals(5, n)
        val s = SettingsStore(st)
        assertEquals("F4IOZ", s.callsign)
        assertEquals("JN18FS", s.manualLocator)
        assertEquals(450, s.rotorMaxAz)
        assertEquals("SOUTH", s.rotorAzStop)
        assertFalse("the mast waits to be asked", s.rotorEnabled)
        assertEquals("", s.rotorUsbCle)
        assertEquals(-1, st.getInt("civ_usb_index", -1))
        assertEquals(null, st.getString("ts2000_cle", null))
    }

    @Test
    fun un_autre_fichier_est_refuse() {
        assertTrue(ImportTelephone.importe("""{"app":"Autre"}""", StockageMemoire()).isFailure)
        assertTrue(ImportTelephone.importe("pas du json", StockageMemoire()).isFailure)
    }

    @Test
    fun le_carnet_du_telephone_s_ajoute_sans_ecraser_celui_du_pc() {
        val f = java.io.File.createTempFile("satme-carnet-pc", ".json")
        try {
            val carnet = fr.f4ioz.satcombo.data.LogStore(f)
            carnet.add(fr.f4ioz.satcombo.data.LogEntry(1000L, "SO-50", 27607, 10.0, 20.0, callsign = "F4PC", note = "du PC"))
            val json = """{"app":"SatMe","stores":{"satcombo_settings":{}},"log":[
                {"t":1000,"s":"SO-50","c":27607,"az":10,"el":20,"cs":"F4PC","n":"du téléphone"},
                {"t":2000,"s":"RS-44","c":44909,"az":30,"el":40,"cs":"EA4ABC","tl":"IN80"}]}"""
            assertEquals(1, ImportTelephone.importeCarnet(json, carnet))
            val l = carnet.load()
            assertEquals(listOf(1000L, 2000L), l.map { it.timeMs })
            assertEquals("du PC", l[0].note)
            assertEquals("IN80", l[1].theirLocator)
            // Again: nothing new.
            assertEquals(0, ImportTelephone.importeCarnet(json, carnet))
        } finally { f.delete() }
    }
}
