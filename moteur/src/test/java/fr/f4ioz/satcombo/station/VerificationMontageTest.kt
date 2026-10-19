/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.VerificationMontage.Niveau
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationMontageTest {

    private fun prereglage(id: String) = CatalogueMateriel.PREREGLAGES.first { it.id == id }.chaine
    private fun dangers(c: Montage.Chaine) = VerificationMontage.verifie(c).filter { it.niveau == Niveau.DANGER }

    @Test fun lesMontagesToutFaitsNeSontPasDangereux() {
        for (p in CatalogueMateriel.PREREGLAGES) assertEquals(p.id, emptyList<VerificationMontage.Remarque>(), dangers(p.chaine))
    }

    @Test fun laTensionDuLnbParUnRepartiteurVersLePlutoEstUnDanger() {
        val c = Montage.Bati().run {
            val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val r = ajoute("repartiteur")
            val m = ajoute("minitiouner"); val p = ajoute("pluto"); val pc = ajoute("pc")
            relie(d, "lnb", l, "entree"); relie(l, "sortie", r, "entree")
            relie(r, "sortie1", m, "lnb_a"); relie(r, "sortie2", p, "rx")
            relie(m, "usb", pc, "usb"); relie(p, "usb", pc, "usb")
            chaine()
        }
        val d = dangers(c)
        assertEquals(1, d.size)
        val pluto = c.elements.first { it.modele == "pluto" }.id
        assertTrue(d[0].elements.contains(pluto))
        assertTrue(d[0].texte, d[0].texte.contains("DC block") && d[0].texte.contains("Répartiteur"))
        assertEquals(2, d[0].liens.size)
        // The DC block in between: no danger left.
        val k = Montage.Bati().run {
            val dd = ajoute("parabole-qo100"); val l = ajoute("lnb-pll"); val r = ajoute("repartiteur")
            val m = ajoute("minitiouner"); val b = ajoute("dc-block"); val p = ajoute("pluto")
            relie(dd, "lnb", l, "entree"); relie(l, "sortie", r, "entree")
            relie(r, "sortie1", m, "lnb_a"); relie(r, "sortie2", b, "entree"); relie(b, "sortie", p, "rx")
            chaine()
        }
        assertTrue(dangers(k).isEmpty())
    }

    @Test fun leMinitiounerDirectementSurUneCarteSdrEstUnDanger() {
        // A cable from the MiniTiouner's input straight to an RTL dongle (an odd drawing, still a fried dongle).
        val c = Montage.Bati().run { val m = ajoute("minitiouner"); val r = ajoute("rtl-sdr"); relie(m, "lnb_a", r, "rx"); chaine() }
        assertEquals(1, dangers(c).size)
        // A bias-T's receiver side is free of DC.
        val t = Montage.Bati().run { val b = ajoute("bias-t"); val r = ajoute("rtl-sdr"); relie(b, "rx", r, "rx"); chaine() }
        assertTrue(dangers(t).isEmpty())
    }

    @Test fun ceQuiManqueSurLaStationComplete() {
        val c = prereglage("qo100-station")
        assertTrue(VerificationMontage.verifie(c).isEmpty())
        // The GPSDO's antenna and the up-converter's reference removed: both said.
        val gps = c.liens.first { it.cable == Montage.Cable.GPS && c.element(it.vers.element)!!.modele == "dxp-gpsdo" }
        val ref = c.liens.first { c.element(it.vers.element)!!.modele == "dxp-up-mk4" && it.vers.port == "ref" }
        val l = VerificationMontage.verifie(c.copy(liens = c.liens - gps - ref))
        assertTrue(l.toString(), l.any { it.texte.contains("sans antenne GPS") })
        assertTrue(l.toString(), l.any { it.texte.contains("Référence externe 10 MHz") && it.niveau == Niveau.INFO })
    }

    @Test fun uneSortieDEmissionEnLAirEtUnLnbNonAlimente() {
        val c = Montage.Bati().run {
            val p = ajoute("pluto"); val a = ajoute("pa-2400"); val d = ajoute("parabole-qo100"); val l = ajoute("lnb-pll")
            relie(p, "tx", a, "entree"); relie(d, "lnb", l, "entree"); relie(l, "sortie", p, "rx")
            chaine()
        }
        val l = VerificationMontage.verifie(c)
        assertTrue(l.toString(), l.any { it.niveau == Niveau.ATTENTION && it.texte.contains("en l'air") })
        assertTrue(l.toString(), l.any { it.niveau == Niveau.ATTENTION && it.texte.contains("non alimenté") })
        val j = VerificationMontage.json(l)
        assertEquals("attention", (j[0] as JSONObject).getString("niveau"))
    }

    @Test fun laSuggestionMetUnDcBlockDevantLaCarteSdr() {
        val l = ActiviteMontage.Lectures(
            sdr = JSONObject("""{"actif":true,"recepteur":"pluto"}"""),
            datv = JSONObject("""{"ok":true,"recepteur":"minitiouner"}"""))
        val s = ActiviteMontage.suggere(l).chaine
        assertTrue(s.elements.any { it.modele == "dc-block" })
        assertTrue(dangers(s).isEmpty())
    }
}
