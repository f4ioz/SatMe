/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.PskReporter
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PSK Reporter on the PC: off by default, nothing sent without a sure frequency. Never on the network. */
class PskReporterPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-psk-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val entendu = Ft8Pc.Entendu(0L, "CQ K1ABC FN42", "K1ABC", null, "FN42", 1500, -10, 0.2, "FT8")

    @Test fun `coupe par defaut, et rien sans frequence sure`() {
        var t = 1_760_000_000_000L
        val envoyes = ArrayList<ByteArray>()
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        st.settings.callsign = "F4IOZ"
        val ft8 = Ft8Pc { t }
        val p = PskReporterPc(st, ft8, { envoyes += it }, { t })
        assertFalse(p.actif)
        assertFalse(JsonPc.reglages(st).getBoolean("pskReporter"))
        ft8.auDecode?.invoke(listOf(entendu))
        assertEquals(0, p.psk.enAttente)

        p.actif = true
        // The rig is not on CAT: the frequency is not known, nothing kept.
        ft8.auDecode?.invoke(listOf(entendu))
        assertEquals(0, p.psk.enAttente)
        t += PskReporter.LOT_MS
        p.tic()
        assertTrue(envoyes.isEmpty())
        assertTrue(p.etat().isNotBlank())
        assertTrue(JsonPc.reglages(st).getBoolean("pskReporter"))
    }
}
