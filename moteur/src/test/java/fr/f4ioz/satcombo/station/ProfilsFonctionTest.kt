/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.FonctionsMontage.Instantane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilsFonctionTest {

    private val CARTE = "USB PCM2902 Audio CODEC [plughw:1,0]"
    private val base = Instantane(sdrPresent = true, datvPresent = true, sonPresent = true, phoniePresent = true, cartes = listOf(CARTE), sonCarte = CARTE)

    /** Records each step; fails the test on anything that is not a receive setting. */
    private class Faux(var tx: String? = null, val refuse: Set<String> = emptySet()) : ProfilsFonction.Executant {
        val faits = ArrayList<Pair<String, String>>()
        override fun emission() = tx
        override fun fait(cle: String, valeur: String): String? {
            assertTrue(cle, cle in ProfilsFonction.CLES)
            assertFalse(cle, Regex("tx|emission|emet|lnb|bias|serie|port|iq").containsMatchIn(cle))
            assertFalse("never a DATV start from here", cle == "datv.actif" && valeur == "1")
            faits += cle to valeur
            return if (cle in refuse) "non" else null
        }
    }

    @Test fun laCaptureGardeLaReceptionSeulement() {
        val i = base.copy(sdrActif = true, sdrRecepteur = "pluto", sdrMode = "USB", sdrManuelleHz = 10_489_750_000L, sonActif = true, sonParSdr = true,
            ft8Actif = true, txDatv = "bladerf", datvRecepteur = "minitiouner")
        val c = ProfilsFonction.capture(i)
        assertEquals("pluto", c["sdr.recepteur"]); assertEquals("1", c["sdr.actif"]); assertEquals("sdr", c["son.entree"])
        assertEquals("10489.750000", c["sdr.mhz"]); assertEquals("1", c["ft8.actif"])
        assertTrue(c.keys.all { it in ProfilsFonction.CLES })
        // A METEOR pass is not kept as a plain reception.
        assertEquals("0", ProfilsFonction.capture(i.copy(sdrPourMeteor = true))["sdr.actif"])
    }

    @Test fun lOrdreDApplicationEstSur() {
        // Now: the SDR on the Pluto feeds the decoders, FT8 on it; wanted: SSTV by the IC-9700's sound card.
        val avant = base.copy(sdrActif = true, sdrRecepteur = "pluto", sonActif = true, sonParSdr = true, sonAppareil = "Clé SDR (rtl_tcp)", ft8Actif = true)
        val voulu = mapOf("sdr.actif" to "0", "son.entree" to CARTE, "sstv.source" to "carte", "sstv.actif" to "1", "ft8.actif" to "1", "cw.actif" to "0")
        val plan = ProfilsFonction.plan(voulu, avant)
        assertEquals(listOf("sdr.actif", "sstv.source", "son.entree", "sstv.actif"), plan.map { it.cle })
        val f = Faux()
        val b = ProfilsFonction.applique(plan, f)
        assertTrue(b.ok)
        assertEquals(listOf("sdr.actif" to "0", "sstv.source" to "carte", "son.entree" to CARTE, "sstv.actif" to "1"), f.faits)
    }

    @Test fun laCarteSonVientApresLeSdrQuiLaPrend() {
        val voulu = mapOf("sdr.recepteur" to "pluto", "sdr.actif" to "1", "son.entree" to CARTE, "ft8.actif" to "1")
        val avant = base.copy(sonActif = true, sonAppareil = CARTE)
        val cles = ProfilsFonction.plan(voulu, avant).map { it.cle }
        assertEquals(listOf("sdr.recepteur", "sdr.actif", "son.entree", "ft8.actif"), cles)
    }

    @Test fun rienPendantUneEmission() {
        val plan = ProfilsFonction.plan(mapOf("sdr.actif" to "1", "ft8.actif" to "1"), base)
        val f = Faux(tx = "émission DATV en cours (ADALM-PLUTO)")
        val b = ProfilsFonction.applique(plan, f)
        assertFalse(b.ok)
        assertTrue(b.refus!!.contains("émission DATV"))
        assertTrue(f.faits.isEmpty())
    }

    @Test fun uneEmissionQuiDemarreEnRouteArrete() {
        val plan = ProfilsFonction.plan(mapOf("sdr.actif" to "1", "sstv.actif" to "1", "cw.actif" to "1"), base)
        val f = object : ProfilsFonction.Executant {
            var n = 0
            override fun emission() = if (n >= 1) "émission SSTV en cours" else null
            override fun fait(cle: String, valeur: String): String? { n++; return null }
        }
        val b = ProfilsFonction.applique(plan, f)
        assertEquals(1, f.n)
        assertTrue(b.refus!!.startsWith("Arrêté"))
    }

    @Test fun laDatvNeDemarreJamaisDIci() {
        val plan = ProfilsFonction.plan(mapOf("datv.recepteur" to "minitiouner", "datv.actif" to "1"), base.copy(datvRecepteur = "pluto"))
        val d = plan.single { it.cle == "datv.actif" }
        assertTrue(d.manuel)
        val f = Faux()
        val b = ProfilsFonction.applique(plan, f)
        assertEquals(listOf("datv.recepteur" to "minitiouner"), f.faits)
        assertTrue(b.resultats.single { it.etape.cle == "datv.actif" }.message.contains("onglet"))
        // Its stop on a MiniTiouner too (it feeds the LNB).
        val arret = ProfilsFonction.plan(mapOf("datv.actif" to "0"), base.copy(datvActif = true, datvRecepteur = "minitiouner")).single()
        assertTrue(arret.manuel)
        assertFalse(ProfilsFonction.plan(mapOf("datv.actif" to "0"), base.copy(datvActif = true, datvRecepteur = "pluto")).single().manuel)
    }

    @Test fun unEchecNEmpechePasLaSuite() {
        val plan = ProfilsFonction.plan(mapOf("son.entree" to CARTE, "cw.actif" to "1"), base)
        val f = Faux(refuse = setOf("son.entree"))
        val b = ProfilsFonction.applique(plan, f)
        assertFalse(b.ok)
        assertEquals(listOf(false, true), b.resultats.map { it.ok })
    }

    @Test fun leFichierNeGardeQueLesReglagesDeReception() {
        val texte = """{"profils":[{"id":"sstv-ic9700","nom":"SSTV par IC-9700<script>","reglages":{"sstv.source":"carte","sdr.bias":"1",
            "datv.emission.actif":"1","sdr.recepteur":"hackrf","ft8.actif":"1","son.entree":"$CARTE"}},{"id":"../x","nom":"mauvais"}]}"""
        val l = ProfilsFonction.lit(texte)
        assertEquals(1, l.size)
        assertEquals("SSTV par IC-9700script", l[0].nom)
        assertEquals(mapOf("son.entree" to CARTE, "sstv.source" to "carte", "ft8.actif" to "1"), l[0].reglages)
        assertEquals(l, ProfilsFonction.lit(ProfilsFonction.ecrit(l).toString()))
        assertEquals("sstv-par-ic-9700", ProfilsFonction.nouvelId("SSTV par IC-9700", emptyList()))
        assertEquals("qo-100-pluto-ft8-2", ProfilsFonction.nouvelId("QO-100 Pluto + FT8", listOf(ProfilsFonction.Profil("qo-100-pluto-ft8", "x", emptyMap()))))
        assertFalse(ProfilsFonction.admis("sdr.bias", "1"))
        assertFalse(ProfilsFonction.admis("sdr.mhz", "abc"))
    }

    @Test fun lApercuNeMontreQueCeQuiChange() {
        val i = base.copy(sstvActif = true, sstvSource = "carte")
        val p = ProfilsFonction.Profil("p", "P", mapOf("sstv.actif" to "1", "sstv.source" to "carte", "cw.actif" to "1"))
        val o = ProfilsFonction.json(p, i)
        assertEquals(1, o.getJSONArray("apercu").length())
        assertEquals("Décodeur CW activé", o.getJSONArray("apercu").getJSONObject(0).getString("texte"))
    }
}
