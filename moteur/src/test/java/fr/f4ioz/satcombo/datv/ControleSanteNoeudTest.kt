/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One controller for the transmissions, whoever asks; a node's health read and its alerts. */
class ControleSanteNoeudTest {

    private var t = 1_000_000L
    private val c = ControleEmission({ t })
    private val pc = ControleEmission.Client("onglet-pc", distant = false)
    private val loin = ControleEmission.Client("tablette", distant = true, adresse = "192.168.1.40")

    @Test
    fun un_seul_pilote_les_autres_voient_et_peuvent_arreter() {
        assertNull(c.refus(pc, emet = false))
        c.prend(pc, "DATV")
        // Another client: refused while it transmits, told who holds it.
        val r = c.refus(ControleEmission.Client("autre-onglet", false), emet = true)!!
        assertTrue(r, r.contains("la page de ce PC") && r.contains("STOP"))
        // Its « still there » does not count; the controller's does.
        assertFalse(c.vie(ControleEmission.Client("autre-onglet", false)))
        assertTrue(c.vie(pc))
        val j = c.json(loin)
        assertTrue(j.getBoolean("tenue")); assertFalse(j.getBoolean("moi")); assertEquals("DATV", j.getString("quoi"))
        // The page on the PC: no link to lose, never stopped here.
        t += 60_000
        assertNull(c.perdu())
        c.libere()
        assertFalse(c.json(pc).getBoolean("tenue"))
    }

    @Test
    fun a_distance_une_confirmation_puis_le_chien_de_2_s() {
        assertEquals(ControleEmission.CONFIRMATION, c.refus(loin, emet = false))
        assertFalse(c.json(loin).getJSONObject("vous").getBoolean("confirme"))
        c.confirme(loin)
        assertNull(c.refus(loin, emet = false))
        c.prend(loin, "SSTV")
        t += 1_500; assertTrue(c.vie(loin)); assertNull(c.perdu())
        t += 1_900; assertNull(c.perdu())
        t += 200
        val p = c.perdu()!!
        assertTrue(p, p.contains("192.168.1.40") && p.contains("coupée"))
        // The PC's page cannot start over it while it still transmits, but may always stop it (not refused here).
        assertNotNull(c.refus(pc, emet = true))
    }

    @Test
    fun sante_lue_et_alertes() {
        assertEquals(3723.0, SanteNoeud.uptime("3723.45 7000.1\n")!!, 0.5)
        assertEquals(listOf(0.5, 0.25, 0.1), SanteNoeud.charge("0.50 0.25 0.10 1/234 5678"))
        assertEquals(81.234, SanteNoeud.temperature("81234\n")!!, 0.001)
        assertEquals(3_884_000L to 2_100_000L, SanteNoeud.memoire("MemTotal:  3884000 kB\nMemFree: 1000 kB\nMemAvailable:  2100000 kB\n"))
        val b = SanteNoeud.bridage("throttled=0x50005\n")!!
        assertEquals(listOf("sous-alimentation", "ralenti (bridé)"), b.maintenant)
        assertEquals(listOf("sous-alimentation", "ralenti (bridé)"), b.depuisDemarrage)
        assertNull(SanteNoeud.bridage("command not found"))
        assertEquals("USB 2", SanteNoeud.vitesseUsb("480")); assertEquals("USB 3", SanteNoeud.vitesseUsb("5000"))

        val s = JSONObject()
            .put("systeme", JSONObject().put("temperature", 81.2).put("bridage", JSONArray(b.maintenant)).put("disqueLibreMo", 80))
            .put("usb", JSONArray().put(JSONObject().put("nom", "bladeRF 2.0").put("vitesse", "USB 2")))
            .put("services", JSONObject().put("sdr", JSONObject().put("etat", "marche").put("pertesPct", 4.0))
                .put("datv", JSONObject().put("etat", "erreur").put("erreur", "LongMynd introuvable")))
        val a = SanteNoeud.alertes(s).map { it.texte }
        assertTrue(a.toString(), a.any { it.startsWith("surchauffe") })
        assertTrue(a.toString(), a.any { it.startsWith("Pi bridé") })
        assertTrue(a.toString(), a.any { it.contains("microSD") })
        assertTrue(a.toString(), a.any { it.contains("USB 3") })
        assertTrue(a.toString(), a.any { it.contains("trames perdues") })
        assertTrue(a.toString(), a.any { it.contains("LongMynd") })
        // The serious ones first.
        assertEquals(SanteNoeud.DANGER, SanteNoeud.alertes(s).first().niveau)
        // A healthy node: nothing; a silent one: said.
        assertTrue(SanteNoeud.alertes(JSONObject().put("systeme", JSONObject().put("temperature", 52.0))).isEmpty())
        assertTrue(SanteNoeud.alertes(null, "délai dépassé").single().texte.contains("injoignable"))
    }
}
