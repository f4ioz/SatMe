/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.ClasseQo100
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SignalQo100
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The IC-9700 on QO-100 with the level guard, on the simulated rig: off by
 * default (measured, nothing touched), on it lowers the RF power and never
 * raises it, far above the beacon it releases the PTT. No port opened.
 */
class EmissionPosteGardePcTest {
    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-txgarde-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun attends(ms: Long = 8_000, quoi: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!quoi()) { check(System.currentTimeMillis() < fin) { "trop long" }; Thread.sleep(20) }
    }

    private class Fausse : EmissionPostePc.Sortie {
        override val nom = "fausse"
        override fun pousse(pcm: ShortArray, n: Int, taux: Int): String? = null
        override fun ferme() {}
    }

    @Test fun la_garde_baisse_la_puissance_puis_coupe() {
        var t = 20_000_000L
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        st.settings.manualLocator = "JN18FS"
        st.connecteCat("SIM", 0, 0)
        attends { st.etat.value.catConnecte && st.posteSimule != null }
        val p = EmissionPostePc(st, { null }, { t }, { _, _ -> Fausse() })
        st.qo100.auPoste(true)
        st.qo100.descente(10_489_800_000L)
        val nous = st.qo100.etat.value.monteeHz + Qo100.DECALAGE_HZ + 1_500
        var snr = 18.0
        p.signauxQo100 = { listOf(SignalQo100(9, Qo100.BALISE_MEDIANE_HZ, 2_400, ClasseQo100.BALISE, 1.0, 0, 100, 15.0, ""),
            SignalQo100(1, nous, 2_400, ClasseQo100.BLU, 0.9, 0, 100, snr, "")) }
        p.reglage(mapOf("lire" to "1"), local = true)
        attends { st.puissanceLue != null }
        val avant = st.puissanceLue!!
        // Off by default: measured, the power untouched.
        assertTrue(p.appuie("a", false, "") == null)
        repeat(15) { t += 200; p.vie("a"); p.tic() }
        assertTrue(p.json("a").getJSONObject("qo100").getBoolean("tropFort"))
        Thread.sleep(300)
        assertTrue(st.puissanceLue == avant)
        p.relache("fin")
        // On: lowered (3 dB over: 4 dB down), never raised.
        assertFalse(p.reglage(mapOf("garde" to "1"), local = false).getBoolean("ok"))
        p.reglage(mapOf("garde" to "1"), local = true)
        assertTrue(p.appuie("a", false, "") == null)
        repeat(8) { t += 200; p.vie("a"); p.tic() }
        attends { st.puissanceLue!! < avant }
        assertTrue(st.puissanceLue!! <= Math.round(avant * Math.pow(10.0, -0.4)) + 1)
        assertTrue(p.json("a").getJSONObject("qo100").getJSONObject("garde").getString("message").contains("puissance"))
        // Far above the beacon: the PTT released.
        snr = 22.0
        t += 200; p.vie("a"); p.tic()
        assertFalse(st.posteSimule!!.transmitting)
        assertTrue(p.json("a").getString("raison").contains("garde de niveau"))
    }
}
