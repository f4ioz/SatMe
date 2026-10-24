/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.station.ActiviteMontage.Etat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DessinMontageTest {

    /** The owner's plan « IC-9700 ISS SSTV » as he drew it (pieces placed by hand; his photo shown on the rig). */
    private val PLAN = """{"v":1,"id":"p2","nom":"IC-9700 ISS SSTV","elements":[{"x":0,"y":5,"id":"e1","m":"yagi-2m","nom":""},
        {"x":0,"y":114,"id":"e2","m":"yagi-70cm","nom":""},{"x":25,"y":237,"id":"e3","m":"rotor-gs232","nom":""},
        {"x":291,"ph":true,"y":46,"id":"e6","m":"ic9700","nom":""},{"x":564,"y":35,"id":"e9","m":"pc","nom":""}],
        "liens":[{"de":"e3.moteur","note":"","c":"ROTOR","vers":"e1.mat","id":"l4"},{"de":"e2.mat","note":"","c":"ROTOR","vers":"e3.moteur","id":"l5"},
        {"de":"e1.ant","note":"25 m","c":"COAX_PL","vers":"e6.ant144","id":"l7"},{"de":"e2.ant","note":"25m","c":"COAX_N","vers":"e6.ant432","id":"l8"},
        {"de":"e6.usb","note":"","c":"USB","vers":"e9.usb","id":"l10"}]}"""

    private fun chaine() = Montage.lit(PLAN)
    private val base = FonctionsMontage.essai("sstv-carte")!!

    /** A small PNG (1×1, red), as a cut-out photo would be. */
    private val PNG = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")

    private fun dessin(photos: Map<String, DessinMontage.Photo> = emptyMap(), mhz: Double? = 437.396) =
        DessinMontage.pourSstv("IC-9700 ISS SSTV", chaine(), CampagneSstv.Chaine(), mhz, base, photos)

    @Test
    fun le_dessin_est_toujours_le_meme() {
        val d = dessin(mapOf("e6" to DessinMontage.Photo(PNG, 1, 1)))
        val a = SceneMontage.svg(DessinMontage.scene(d), "rm")
        val b = SceneMontage.svg(DessinMontage.scene(dessin(mapOf("e6" to DessinMontage.Photo(PNG, 1, 1)))), "rm")
        assertEquals(a, b)
        assertEquals(SceneMontage.svg(DessinMontage.scene(d, empile = true)), SceneMontage.svg(DessinMontage.scene(d, empile = true)))
        dump("montage-cote", a); dump("montage-empile", SceneMontage.svg(DessinMontage.scene(d, empile = true), "rm"))
    }

    @Test
    fun rien_n_est_cherche_dehors() {
        val svg = SceneMontage.svg(DessinMontage.scene(dessin(mapOf("e6" to DessinMontage.Photo(PNG, 1, 1)))))
        // No picture, font or sheet fetched, no address at all.
        assertFalse(Regex("(href|src)=\"(?!data:)").containsMatchIn(svg))
        assertFalse(svg.contains("url(http"))
        assertFalse(svg.contains("@import"))
        assertFalse(svg.contains("http"))
        assertTrue(svg.contains("href=\"data:image/png;base64,"))
    }

    @Test
    fun la_chaine_sstv_est_allumee() {
        val d = dessin()
        val al = ActiviteMontage.allume(d.chaine, d.activite)
        // 70 cm Yagi → IC-9700 → USB → the computer: lit receiving; the 2 m Yagi at rest; the mast following.
        assertEquals(Etat.RX, al.elements["e2"])
        assertEquals(Etat.RX, al.elements["e6"])
        assertEquals(Etat.RX, al.liens["l8"])
        assertEquals(Etat.REPOS, al.elements["e1"] ?: Etat.REPOS)
        assertTrue(al.elements["e9"] != Etat.REPOS)
        assertTrue(al.elements["e3"] != Etat.REPOS)
        val svg = SceneMontage.svg(DessinMontage.scene(d))
        assertTrue(svg.contains("437,396 MHz · N"))
        assertTrue(svg.contains("25 m · 2 m · PL"))
        assertTrue(svg.contains(">SSTV réception<"))
        assertTrue(svg.contains(">Poste (CAT)<"))
        assertTrue(svg.contains("▸ Poste (CAT)"))
        assertTrue(svg.contains("Carte son"))
        // Only what works in the columns: the decoders at rest summed up, never all drawn.
        val log = DessinMontage.logiciel(d.instantane!!)
        assertTrue(log.colonnes.all { it.size <= DessinMontage.PAR_COLONNE })
        val sstv = log.colonnes.flatten().first { it.id == "f:sstv-rx" }
        assertEquals(FonctionsMontage.Statut.OK, sstv.statut)
        assertTrue(log.colonnes.flatten().none { it.id == "f:ft8" && it.statut == FonctionsMontage.Statut.OK })
    }

    @Test
    fun sans_photo_le_dessin_de_satme() {
        // The owner chose his photo for the rig, but none came: SatMe's drawing in its place, nothing missing.
        val d = dessin(emptyMap())
        assertTrue(d.chaine.element("e6")!!.photo)
        val svg = SceneMontage.svg(DessinMontage.scene(d))
        assertFalse(svg.contains("<image"))
        assertTrue(svg.contains(">Icom IC-9700<") || svg.contains(">IC-9700<"))
        // A photo the owner did not choose to show stays the drawing too (only its badge says there is one).
        val e1 = mapOf("e1" to DessinMontage.Photo(PNG, 1, 1))
        assertFalse(SceneMontage.svg(DessinMontage.scene(dessin(e1))).contains("<image"))
    }

    @Test
    fun le_son_par_la_cle_sdr_et_sans_frequence() {
        val d = DessinMontage.pourSstv("x", chaine(), CampagneSstv.Chaine(source = CampagneSstv.SDR, rotor = false), null, null)
        val i = d.instantane!!
        assertTrue(i.sstvActif && i.sdrActif && !i.sonActif)
        assertFalse("rotor" in d.activite.relies)
        assertNotNull(DessinMontage.scene(d))
        // No plan at all: the computer's pill alone, never a failure.
        val vide = DessinMontage.pourSstv("vide", Montage.Chaine(), null, null, null)
        assertTrue(SceneMontage.svg(DessinMontage.scene(vide)).contains("SatMe-PC"))
    }

    @Test
    fun empile_reste_etroit() {
        val d = dessin()
        val cote = DessinMontage.scene(d); val empile = DessinMontage.scene(d, empile = true)
        assertTrue(empile.l < cote.l * 0.75)
        assertTrue(empile.h > cote.h)
    }

    @Test
    fun le_meilleur_plan_pour_l_evenement() {
        val autre = PlansMontage.Plan("p1", Montage.Bati("QO-100").run { ajoute("parabole-qo100"); ajoute("pc"); chaine() })
        val sstv = PlansMontage.Plan("p2", chaine())
        assertEquals("p2", DessinMontage.meilleurPlan(listOf(autre, sstv), dessin().activite)?.id)
    }

    @Test
    fun les_chemins_svg_sont_lus() {
        val ops = SceneMontage.chemin("M10 16h18l-4 -4 M32 24h-18l4 4 q 0 2 12 3z")
        assertEquals(SceneMontage.M(10.0, 16.0), ops[0])
        assertEquals(SceneMontage.L(28.0, 16.0), ops[1])
        assertEquals(SceneMontage.L(24.0, 12.0), ops[2])
        assertEquals(SceneMontage.M(32.0, 24.0), ops[3])
        assertTrue(ops.last() is SceneMontage.Z)
        assertEquals("0.5", SceneMontage.n(0.5)); assertEquals("-3", SceneMontage.n(-3.0)); assertEquals("0", SceneMontage.n(-0.001))
        assertEquals(0x733FE0C8, SceneMontage.couleur("rgba(63,224,200,.45)"))
    }

    /** With -Ddessin.dump=DIR (or DESSIN_DUMP): the drawings written there, to look at. */
    private fun dump(nom: String, svg: String) {
        val d = System.getProperty("dessin.dump") ?: System.getenv("DESSIN_DUMP") ?: return
        File(d).mkdirs()
        File(d, "$nom.html").writeText("<!doctype html><meta charset=utf-8><body style=\"margin:0;background:#222\">$svg</body>")
    }
}
