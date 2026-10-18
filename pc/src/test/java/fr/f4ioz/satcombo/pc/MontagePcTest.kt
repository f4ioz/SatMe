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
import fr.f4ioz.satcombo.station.FonctionsMontage
import fr.f4ioz.satcombo.station.ProfilsFonction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MontagePcTest {

    private val CARTE = "USB PCM2902 Audio CODEC [plughw:1,0]"

    /** The station's state, changed by the steps done (as the real routes would). */
    private class Station(var i: FonctionsMontage.Instantane) : ProfilsFonction.Executant {
        val faits = ArrayList<String>()
        override fun emission() = i.emission()
        override fun fait(cle: String, valeur: String): String? {
            assertTrue(cle, cle in ProfilsFonction.CLES)
            faits += "$cle=$valeur"
            i = when (cle) {
                "sdr.actif" -> i.copy(sdrActif = valeur == "1", sonParSdr = valeur == "1" || (i.sonParSdr && false), sonActif = valeur == "1" || (i.sonActif && !i.sonParSdr))
                "son.entree" -> i.copy(sonActif = valeur.isNotEmpty(), sonAppareil = valeur, sonParSdr = false)
                "sstv.source" -> i.copy(sstvSource = valeur)
                "sstv.actif" -> i.copy(sstvActif = valeur == "1")
                "ft8.actif" -> i.copy(ft8Actif = valeur == "1")
                else -> i
            }
            return null
        }
    }

    private fun montage(st: Station): MontagePc {
        val d = Files.createTempDirectory("montage").toFile()
        return MontagePc(d, { ActiviteMontage.Lectures() }, { st.i }, st) { 1000L }
    }

    private fun MontagePc.appel(route: String, methode: String = "GET", vararg q: Pair<String, String>): JSONObject {
        val m = q.toMap()
        return JSONObject(String(sert(route, methode, { m[it].orEmpty() }) { null }.corps, Charsets.UTF_8))
    }

    @Test fun unProfilSeGardeSeMontreEtSApplique() {
        val st = Station(FonctionsMontage.Instantane(sdrPresent = true, sonPresent = true, cartes = listOf(CARTE), sonCarte = CARTE,
            catConnecte = true, sonActif = true, sonAppareil = CARTE, sstvActif = true, sstvSource = "carte"))
        val m = montage(st)
        val r = m.appel("/profils", "POST", "nom" to "SSTV par IC-9700")
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("sstv-par-ic-9700", r.getJSONObject("profil").getString("id"))
        // Now the SDR takes the sound: the profile would stop it and take the card back.
        st.i = st.i.copy(sdrActif = true, sonParSdr = true, sonAppareil = "Clé SDR (rtl_tcp)", sstvSource = "sdr")
        val l = m.appel("/profils")
        val apercu = l.getJSONArray("profils").getJSONObject(0).getJSONArray("apercu")
        assertEquals(listOf("sdr.actif", "sstv.source", "son.entree"), (0 until apercu.length()).map { apercu.getJSONObject(it).getString("cle") })
        val b = m.appel("/profils", "POST", "id" to "sstv-par-ic-9700", "applique" to "1")
        assertTrue(b.toString(), b.getBoolean("toutBon"))
        assertEquals(listOf("sdr.actif=0", "sstv.source=carte", "son.entree=$CARTE"), st.faits)
        // Kept on disk, dropped on request.
        assertEquals(1, m.appel("/profils").getJSONArray("profils").length())
        m.appel("/profils", "POST", "id" to "sstv-par-ic-9700", "retire" to "1")
        assertEquals(0, m.appel("/profils").getJSONArray("profils").length())
    }

    @Test fun rienNeSAppliquePendantUneEmission() {
        val st = Station(FonctionsMontage.Instantane(sdrPresent = true, sonPresent = true, cartes = listOf(CARTE)))
        val m = montage(st)
        m.appel("/profils", "POST", "nom" to "Écoute")
        st.i = st.i.copy(sdrActif = true, txSstv = "pluto")
        val b = m.appel("/profils", "POST", "id" to "ecoute", "applique" to "1")
        assertFalse(b.getBoolean("ok"))
        assertTrue(b.getString("erreur").contains("émission SSTV"))
        val g = m.appel("/action", "POST", "cle" to "ft8.actif", "valeur" to "1")
        assertFalse(g.getBoolean("ok"))
        assertTrue(st.faits.isEmpty())
    }

    @Test fun lesGestesSontDeReceptionSeulement() {
        val st = Station(FonctionsMontage.Instantane(sdrPresent = true, sonPresent = true, datvPresent = true))
        val m = montage(st)
        assertFalse(m.appel("/action", "POST", "cle" to "datv.emission", "valeur" to "1").getBoolean("ok"))
        assertFalse(m.appel("/action", "POST", "cle" to "sdr.bias", "valeur" to "1").getBoolean("ok"))
        assertFalse(m.appel("/action", "GET", "cle" to "sdr.actif", "valeur" to "1").getBoolean("ok"))
        // DATV is never started from here.
        assertFalse(m.appel("/action", "POST", "cle" to "datv.actif", "valeur" to "1").getBoolean("ok"))
        assertTrue(st.faits.isEmpty())
        assertTrue(m.appel("/action", "POST", "cle" to "sdr.actif", "valeur" to "1").getBoolean("ok"))
        assertEquals(listOf("sdr.actif=1"), st.faits)
    }

    @Test fun lEtatPorteLesFonctionsEtLaVue() {
        val st = Station(FonctionsMontage.Instantane())
        val m = montage(st)
        val o = m.appel("/", "GET", "essai" to "datv-pluto", "fonction" to "sdr")
        assertEquals("datv-pluto", o.getString("essai"))
        val f = o.getJSONObject("fonctions")
        assertTrue(f.getJSONArray("conflits").length() > 0)
        assertEquals("sdr", o.getJSONObject("vue").getString("id"))
    }
}
