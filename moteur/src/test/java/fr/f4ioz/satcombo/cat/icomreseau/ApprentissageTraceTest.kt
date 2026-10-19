/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.Datagramme
import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.code
import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.connexion
import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.reponse
import fr.f4ioz.satcombo.cat.icomreseau.TraceEssai.trace

/** Capture files built by [TraceEssai], with made-up credentials and a made-up coding. */
class ApprentissageTraceTest {

    private val u = "testeur"
    private val m = "Faux-Mdp9"

    private fun secret(b: ApprentissageTrace.Bilan) =
        listOf(b.message, b.toString()).forEach { t -> assertFalse(t.contains(u)); assertFalse(t.contains(m)); assertFalse(t.contains(CodageIdentifiants.versHex(code(m)))) }

    @Test fun pcap_ethernet_connexion_acceptee() {
        val t = trace(1, Datagramme(true, connexion(u, m, 0x1234)), Datagramme(false, reponse(0x1234, 0)))
        val b = ApprentissageTrace.apprends(t, 50001, u, m, "")
        assertTrue(b.message, b.ok)
        assertEquals(1, b.connexions)
        assertEquals(ApprentissageTrace.Reponse.ACCEPTEE, b.reponse)
        assertEquals(u.length + m.length, b.appris)
        assertEquals((m.length until 16).toList(), b.placesManquantes)
        val (uc, mc, table) = b.aGarder()!!
        assertEquals(CodageIdentifiants.versHex(code(u)), uc)
        assertEquals(CodageIdentifiants.versHex(code(m)), mc)
        assertArrayEquals(code(m), CodageIdentifiants().apply { lis(table) }.code(m))
        secret(b)
    }

    @Test fun pcap_sll_et_pcapng() {
        for (lien in listOf(113, 0)) {
            val t = trace(lien, Datagramme(true, connexion(u, m, 7)), Datagramme(false, reponse(7, 0)))
            val b = ApprentissageTrace.apprends(t, 50001, u, m, "")
            assertTrue(b.message, b.ok)
            assertEquals(ApprentissageTrace.Reponse.ACCEPTEE, b.reponse)
        }
    }

    @Test fun plusieurs_connexions_la_bonne_est_l_acceptee() {
        val t = trace(1,
            Datagramme(true, connexion(u, "mauvais", 1)), Datagramme(false, reponse(1, 0xFEFFFFFF.toInt())),
            Datagramme(true, connexion(u, m, 2), portPc = 51001), Datagramme(false, reponse(2, 0), portPc = 51001),
            Datagramme(true, connexion(u, "autre", 3), portPc = 51002))
        val b = ApprentissageTrace.apprends(t, 50001, u, m, "")
        assertTrue(b.message, b.ok)
        assertEquals(3, b.connexions)
        assertEquals(ApprentissageTrace.Reponse.ACCEPTEE, b.reponse)
        assertEquals(CodageIdentifiants.versHex(code(m)), b.aGarder()!!.second)
    }

    @Test fun reponse_absente_ou_refusee() {
        val sans = ApprentissageTrace.apprends(trace(1, Datagramme(true, connexion(u, m, 5))), 50001, u, m, "")
        assertTrue(sans.ok)
        assertEquals(ApprentissageTrace.Reponse.INCONNUE, sans.reponse)
        assertTrue(sans.message.contains("non reconnue"))
        val refus = ApprentissageTrace.apprends(trace(1, Datagramme(true, connexion(u, m, 5)), Datagramme(false, reponse(5, 1))), 50001, u, m, "")
        assertFalse(refus.ok)
        assertEquals(ApprentissageTrace.Reponse.REFUSEE, refus.reponse)
        assertNull(refus.aGarder())
    }

    @Test fun desaccord() {
        val t = trace(1, Datagramme(true, connexion(u, m, 1)), Datagramme(false, reponse(1, 0)))
        val avant = ApprentissageTrace.apprends(t, 50001, u, m, "").aGarder()!!.third
        // A trace of another password, the old one still typed: the table already knows otherwise.
        val t2 = trace(1, Datagramme(true, connexion(u, "Faux-Mdp8", 1)), Datagramme(false, reponse(1, 0)))
        val b = ApprentissageTrace.apprends(t2, 50001, u, m, avant)
        assertFalse(b.ok); assertNull(b.aGarder())
        // A length that is not the trace's.
        val c = ApprentissageTrace.apprends(t, 50001, u, "court", "")
        assertFalse(c.ok); assertNull(c.aGarder())
        secret(b); secret(c)
    }

    @Test fun clair_manquant_fichier_inconnu_rien_dedans() {
        val t = trace(1, Datagramme(true, connexion(u, m, 1)))
        assertFalse(ApprentissageTrace.apprends(t, 50001, "", m, "").ok)
        assertFalse(ApprentissageTrace.apprends(t, 50001, u, "", "").ok)
        assertFalse(ApprentissageTrace.apprends("pas une trace du tout".toByteArray(), 50001, u, m, "").ok)
        // Another control port: nothing found.
        val b = ApprentissageTrace.apprends(t, 50011, u, m, "")
        assertFalse(b.ok); assertEquals(0, b.connexions)
        // Cut short: what is whole is read, no error.
        assertNotNull(TracePcap.udp(t.copyOf(t.size - 10)))
    }

    @Test fun table_ajoutee_a_l_existante() {
        val t = trace(1, Datagramme(true, connexion(u, m, 1)), Datagramme(false, reponse(1, 0)))
        val premiere = ApprentissageTrace.apprends(t, 50001, u, m, "").aGarder()!!.third
        val b = ApprentissageTrace.apprends(t, 50001, u, m, premiere)
        assertTrue(b.ok); assertEquals(0, b.appris)
    }
}
