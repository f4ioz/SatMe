/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.station.ActiviteMontage
import fr.f4ioz.satcombo.station.DetectionMontage
import fr.f4ioz.satcombo.station.FonctionsMontage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MontagePlansPcTest {

    private val ANCIEN = """{"v":1,"nom":"SatMe-Node déporté","elements":[{"id":"e1","m":"parabole-qo100","nom":""},{"id":"e2","m":"lnb-pll","nom":""},
        {"id":"e3","m":"minitiouner","nom":""},{"id":"e4","m":"satme-node","nom":""},{"id":"e5","m":"pc","nom":""}],
        "liens":[{"id":"l1","de":"e1.lnb","vers":"e2.entree","c":"DIRECT","note":""},{"id":"l2","de":"e2.sortie","vers":"e3.lnb_a","c":"COAX_F","note":""},
        {"id":"l3","de":"e3.usb","vers":"e4.usb","c":"USB","note":""},{"id":"l4","de":"e4.lan","vers":"e5.lan","c":"ETHERNET","note":""}]}"""
    private val CODEC = "CODEC [plughw:2,0]"

    /** SSTV decoded from the IC-9700's codec, CAT off, the ISS followed; [sstv] false: nothing works. */
    private class Etat(var sstv: Boolean = true)

    private fun montage(d: File, e: Etat = Etat(false), t: () -> Long = { 1000L }) = MontagePc(d,
        { ActiviteMontage.Lectures(etat = JSONObject("""{"cat":{"connecte":false,"modele":"IC9700"},"suivi":{"nom":"ISS"},"ciel":{"dl":437550000}}""")) },
        { FonctionsMontage.Instantane(sonPresent = true, sonActif = e.sstv, sonAppareil = if (e.sstv) CODEC else "", cartes = listOf(CODEC), sstvActif = e.sstv,
            sstvSource = "carte", carteDuPoste = CODEC, catModele = "IC9700", libelles = mapOf(CODEC to "IC-9700 (USB Audio CODEC)")) },
        systeme = { emptyList<DetectionMontage.Usb>() to listOf(DetectionMontage.CarteSon(2, "CODEC", "USB Audio CODEC", "08bb:2901")) }, horloge = t)

    private fun MontagePc.appel(route: String, methode: String = "GET", corps: ByteArray? = null, vararg q: Pair<String, String>): JSONObject {
        val m = q.toMap()
        return JSONObject(String(sert(route, methode, { m[it].orEmpty() }) { corps }.corps, Charsets.UTF_8))
    }
    private fun dossier() = Files.createTempDirectory("montage").toFile()

    @Test fun lAncienMontageEstLePremierPlan() {
        val d = dossier(); File(d, "montage.json").writeText(ANCIEN)
        val m = montage(d)
        val e = m.appel("")
        assertEquals("p1", e.getString("planId"))
        assertEquals("SatMe-Node déporté", e.getJSONObject("chaine").getString("nom"))
        assertEquals(5, e.getJSONObject("chaine").getJSONArray("elements").length())
        val l = m.appel("/plans")
        assertEquals(1, l.getJSONArray("plans").length())
        assertTrue(l.getBoolean("suivre"))
        // An edit rewrites the file in version 2, the first plan still at its top.
        assertTrue(m.appel("/edition", "POST", null, "op" to "renomme", "id" to "e5", "nom" to "Station").getBoolean("ok"))
        val f = JSONObject(File(d, "montage.json").readText())
        assertEquals(2, f.getInt("v")); assertEquals(5, f.getJSONArray("elements").length())
    }

    @Test fun lesPlansSAjoutentSeCopientSeRenommentSeRetirent() {
        val d = dossier(); File(d, "montage.json").writeText(ANCIEN)
        val m = montage(d)
        // A photo on the first plan's MiniTiouner.
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0)
        assertTrue(m.appel("/photo", "POST", png, "id" to "e3").getBoolean("ok"))
        val n = m.appel("/plans", "POST", null, "op" to "nouveau", "nom" to "IC-9700 satellites")
        assertTrue(n.toString(), n.getBoolean("ok"))
        assertEquals("p2", n.getString("id")); assertEquals("p2", n.getString("affiche"))
        // Edits go to the plan named; the other is untouched.
        assertTrue(m.appel("/edition", "POST", null, "op" to "ajoute", "m" to "ic9700", "plan" to "p2").getBoolean("ok"))
        assertEquals(1, m.appel("", "GET", null, "plan" to "p2").getJSONObject("chaine").getJSONArray("elements").length())
        assertEquals(5, m.appel("", "GET", null, "plan" to "p1").getJSONObject("chaine").getJSONArray("elements").length())
        // Undo is per plan.
        assertFalse(m.appel("", "GET", null, "plan" to "p1").getJSONObject("historique").getBoolean("annuler"))
        assertTrue(m.appel("/annule", "POST", null, "plan" to "p2").getBoolean("ok"))
        assertEquals(0, m.appel("", "GET", null, "plan" to "p2").getJSONObject("chaine").getJSONArray("elements").length())
        // A copy keeps the photos.
        val c = m.appel("/plans", "POST", null, "op" to "duplique", "id" to "p1", "nom" to "DATV par le node")
        assertEquals("p3", c.getString("id"))
        val e3 = m.appel("", "GET", null, "plan" to "p3")
        assertEquals("DATV par le node", e3.getJSONObject("chaine").getString("nom"))
        assertEquals("e3", e3.getJSONArray("photos").getString(0))
        assertEquals("200 OK", m.sert("/photo", "GET", { mapOf("id" to "e3", "plan" to "p3")[it].orEmpty() }) { null }.statut)
        assertTrue(m.appel("/plans", "POST", null, "op" to "renomme", "id" to "p2", "nom" to "DATV par le node").getBoolean("ok"))
        assertEquals("DATV par le node (2)", m.appel("", "GET", null, "plan" to "p2").getJSONObject("chaine").getString("nom"))
        assertFalse(m.appel("/edition", "POST", null, "op" to "auto", "plan" to "p9").getBoolean("ok"))
        // Kept in the data folder.
        val r = montage(d).appel("/plans")
        assertEquals(3, r.getJSONArray("plans").length()); assertEquals("p3", r.getString("affiche"))
        // Deleted, with its photos; the last one stays.
        assertTrue(m.appel("/plans", "POST", null, "op" to "retire", "id" to "p3").getBoolean("ok"))
        assertFalse(File(d, "montage-photos/p3").exists())
        assertEquals("p1", m.appel("/plans").getString("affiche"))
        assertTrue(m.appel("/plans", "POST", null, "op" to "retire", "id" to "p2").getBoolean("ok"))
        assertFalse(m.appel("/plans", "POST", null, "op" to "retire", "id" to "p1").getBoolean("ok"))
    }

    @Test fun lePlanDeCeQuiTourneEtLaChaineDetectee() {
        val d = dossier(); File(d, "montage.json").writeText(ANCIEN)
        val e = Etat(true)
        val m = montage(d, e)
        // Without ?auto=1, the owner's plan as before.
        assertEquals("p1", m.appel("").getString("planId"))
        // SSTV from the IC-9700's codec: no plan draws it, the chain detected is shown.
        var a = m.appel("", "GET", null, "auto" to "1")
        assertTrue(a.toString(), a.getBoolean("detectee"))
        assertEquals("", a.getString("planId"))
        val modeles = (0 until a.getJSONObject("chaine").getJSONArray("elements").length()).map { a.getJSONObject("chaine").getJSONArray("elements").getJSONObject(it).getString("m") }.toSet()
        assertEquals(setOf("ic9700", "yagi-70cm", "pc"), modeles)
        assertTrue(a.getJSONObject("etat").getJSONArray("resume").toString().contains("SSTV : IC-9700"))
        assertEquals("IC-9700 · SSTV", a.getString("nomPropose"))
        // Kept as a plan: shown at once, followed.
        val g = m.appel("/plans", "POST", null, "op" to "garde", "nom" to "")
        assertTrue(g.toString(), g.getBoolean("ok"))
        assertEquals("IC-9700 · SSTV", g.getString("nom"))
        a = m.appel("", "GET", null, "auto" to "1")
        assertEquals("p2", a.getString("planId")); assertFalse(a.getBoolean("detectee")); assertTrue(a.getBoolean("suit"))
        // Chosen by hand: held while SSTV runs.
        m.appel("/plans", "POST", null, "op" to "affiche", "id" to "p1")
        a = m.appel("", "GET", null, "auto" to "1")
        assertEquals("p1", a.getString("planId")); assertTrue(a.getBoolean("epingle"))
        // The toggle used again: the plan of what works.
        m.appel("/plans", "POST", null, "op" to "suivre", "on" to "1")
        assertEquals("p2", m.appel("", "GET", null, "auto" to "1").getString("planId"))
        // Toggle off: the owner's plan.
        m.appel("/plans", "POST", null, "op" to "suivre", "on" to "0")
        assertEquals("p1", m.appel("", "GET", null, "auto" to "1").getString("planId"))
        // Nothing works: nothing to keep.
        e.sstv = false
        assertFalse(m.appel("/plans", "POST", null, "op" to "garde").getBoolean("ok"))
    }
}
