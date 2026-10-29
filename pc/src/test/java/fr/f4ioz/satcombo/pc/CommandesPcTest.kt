/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SatPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** A made-up station for the commands' tests: its gestures counted, nothing real. */
internal class MondeFictif(var t: Long = T0) : CommandesPc.Monde {
    companion object {
        /** 29 October 2026, 12:00 UTC. */
        const val T0 = 1_793_275_200_000L
        fun passage(nom: String, catnum: Int, aos: Long, minutes: Int, el: Double) =
            SatPass(nom, catnum, aos, aos + minutes * 60_000L, el, 10.0, 200.0, false, false)
    }
    val gestes = ArrayList<String>()
    var maitre = false
    var sstv = false
    var datvOn = false
    var muetOn = false
    var refusDatv: CommandesPc.Refus? = null
    override fun maintenant() = t
    override fun satellites() = listOf("ISS (ZARYA)" to 25544, "SO-50" to 27607, "AO-91" to 43017, "RS-44" to 44909)
    override fun favoris() = setOf(27607, 43017)
    override fun passages() = listOf(
        passage("ISS (ZARYA)", 25544, T0 - 120_000L, 10, 60.0),
        passage("SO-50", 27607, T0 + 25 * 60_000L, 12, 47.0),
        passage("AO-91", 43017, T0 + 3 * 3_600_000L, 12, 20.0),
        passage("RS-44", 44909, T0 + 40 * 60_000L, 15, 3.0),
    )
    override fun passagesDe(catnum: Int, deMs: Long, aMs: Long) = passages().filter { it.catalogNumber == catnum }
    override fun elevation(catnum: Int, t: Long) = if (catnum == 25544) 35.4 else -10.0
    override fun elevationMin() = 5.0
    override fun autopilote() = CommandesPc.Pilote(maitre, 1, null, CommandesPc.Pilote.Prochain("ISS", "ARISS", T0 + 3_600_000L, 43.0))
    override fun derniereSstv() = Triple("ISS", T0 - 86_400_000L, "PD120")
    override fun datv() = CommandesPc.Datv(datvOn, datvOn, 7.4, 10_491_500L, "QO-100 Beacon", "")
    override fun balise() = CommandesPc.Balise(true, 18.2, 120L, T0 - 5_000L)
    override fun rotor() = CommandesPc.Rotor(true, 182.3, 35.0, false)
    override fun dernierQso() = Triple("f4xyz", "SO-50", T0 - 2 * 86_400_000L)
    override fun sstvActif() = sstv
    override fun muet() = muetOn
    override fun regleAutopilote(on: Boolean): CommandesPc.Refus? { gestes += "autopilote $on"; maitre = on; return null }
    override fun regleSstv(on: Boolean): CommandesPc.Refus? { gestes += "sstv $on"; sstv = on; return null }
    override fun regleDatv(on: Boolean): CommandesPc.Refus? { gestes += "datv $on"; refusDatv?.let { return it }; datvOn = on; return null }
    override fun regleSon(muet: Boolean): CommandesPc.Refus? { gestes += "son $muet"; muetOn = muet; return null }
}

class CommandesPcTest {

    private val monde = MondeFictif()
    private val c = CommandesPc(monde) { ZoneId.of("Europe/Paris") }

    @Test fun les_questions() {
        val pp = c.phrase("prochain passage de SO cinquante", "page")
        assertEquals(Intention.PROCHAIN_PASSAGE, pp.intention)
        assertEquals("Prochain passage de SO-50 à 13 h 25, dans 25 minutes, 47 degrés au plus haut.", pp.fr)
        assertEquals("Next SO-50 pass at 13:25, in 25 minutes, 47 degrees at most.", pp.en)
        assertEquals(MondeFictif.T0 + 25 * 60_000L, pp.aos)
        // The ISS is passing now.
        assertTrue(c.phrase("quand passe l'ISS", "voix").fr.startsWith("ISS (ZARYA) passe en ce moment, jusqu'à 13 h 08"))
        // The favourites: SO-50 first; RS-44 too low (3° < 5°) is never the answer.
        assertTrue(c.phrase("prochain passage", "voix").fr.contains("SO-50"))
        assertEquals("Prochain AOS : SO-50 à 13 h 25, dans 25 minutes.", c.phrase("prochain AOS", "voix").fr)
        assertEquals("Un satellite au-dessus de l'horizon : ISS (ZARYA) à 35 degrés.", c.phrase("quels satellites sont visibles", "voix").fr)
        assertEquals("L'auto-pilote SSTV est coupé.", c.phrase("état de l'autopilote", "voix").fr)
        monde.maitre = true
        assertEquals("L'auto-pilote est en marche. Prochain passage de campagne : ISS à 14 h 00, dans 60 minutes (ARISS).", c.phrase("état de l'autopilote", "voix").fr)
        assertEquals("Dernière image SSTV : ISS, reçue hier à 13 h 00 en PD120.", c.phrase("dernière image SSTV", "voix").fr)
        assertEquals("La réception DATV est arrêtée.", c.phrase("état de la DATV", "voix").fr)
        monde.datvOn = true
        assertEquals("DATV verrouillée sur 10491,5 mégahertz, MER 7,4 dB, station QO-100 Beacon.", c.phrase("état de la DATV", "voix").fr)
        assertEquals("DATV locked on 10491.5 megahertz, MER 7.4 dB, station QO-100 Beacon.", c.phrase("DATV status", "voix").en)
        assertEquals("Balise QO-100 reçue à 18 dB au-dessus du bruit, écart 120 hertz.", c.phrase("la balise", "voix").fr)
        assertEquals("L'antenne pointe à 182 degrés d'azimut et 35 degrés d'élévation.", c.phrase("où pointe l'antenne", "voix").fr)
        assertEquals("Dernier QSO : F4XYZ sur SO-50, le 27 octobre à 13 h 00.", c.phrase("dernier QSO", "voix").fr)
        assertEquals("Last QSO: F4XYZ on SO-50, on 27 October at 13:00.", c.phrase("dernier QSO", "voix").en)
        assertTrue(c.phrase("aide", "voix").fr.contains("Jamais d'émission"))
        assertEquals(Intention.INCONNU, c.phrase("fais-moi un café", "voix").intention)
        // Nothing was done by a question.
        assertTrue(monde.gestes.isEmpty())
    }

    @Test fun les_actions_sures() {
        assertTrue(c.phrase("active l'auto-pilote", "voix").ok)
        assertTrue(c.phrase("démarre la réception SSTV", "voix").ok)
        assertTrue(c.phrase("coupe le son", "voix").ok)
        assertEquals("Son rétabli.", c.phrase("remets le son", "voix").fr)
        monde.refusDatv = CommandesPc.Refus("Le module DATV est désactivé (Réglages › Modules).", "The DATV module is switched off (Settings › Modules).")
        val d = c.phrase("démarre la DATV", "voix")
        assertFalse(d.ok); assertTrue(d.fr.contains("désactivé"))
        assertEquals(listOf("autopilote true", "sstv true", "son true", "son false", "datv true"), monde.gestes)
        // Actions not allowed from this source: refused, nothing done.
        monde.gestes.clear()
        assertFalse(c.phrase("arrête la SSTV", "domotique", actions = false).ok)
        assertFalse(c.intention("sstv_off", "site", actions = false).ok)
        assertTrue(monde.gestes.isEmpty())
        assertTrue(c.intention("sstv_off", "site").ok)
        assertEquals(listOf("sstv false"), monde.gestes)
    }

    @Test fun jamais_d_emission() {
        listOf("passe en émission", "appuie sur le PTT", "émets en DATV", "envoie une image SSTV", "lance un appel CQ en FT8",
            "tourne l'antenne vers le nord", "start transmitting", "SatMe, active l'émission et l'autopilote").forEach { t ->
            val r = c.phrase(t, "voix")
            assertEquals(t, Intention.EMISSION, r.intention)
            assertFalse(t, r.ok)
            assertEquals(CommandesPc.EMISSION_FR, r.fr)
            assertEquals(CommandesPc.EMISSION_EN, r.en)
        }
        // Ids that are not safe actions, from the site or Home Assistant: refused.
        listOf("emission", "ptt", "datv_tx", "sstv_tx", "ft8_tx", "rotor_goto", "pluto_on", "").forEach { id ->
            assertFalse(id, c.intention(id, "site").ok)
        }
        assertTrue(monde.gestes.isEmpty())
        // Each command noted for the page, the latest last.
        val l = c.traces()
        assertEquals("site", l.last().source)
        assertTrue(l.size <= 30)
    }

    @Test fun l_etat_pour_le_site() {
        val r = c.reponses()
        Intention.QUESTIONS.forEach { assertTrue(it.id, r.has(it.id)) }
        assertEquals(MondeFictif.T0 + 25 * 60_000L, r.getJSONObject("prochain_aos").getLong("aos"))
        val p = c.passagesConnus()
        val noms = (0 until p.length()).map { p.getJSONObject(it).getString("nom") }
        // The favourites first, then the common satellites.
        assertEquals(listOf("SO-50", "AO-91"), noms.take(2))
        val iss = (0 until p.length()).map { p.getJSONObject(it) }.first { it.getInt("catnum") == 25544 }
        assertTrue(iss.getJSONArray("cles").toList().containsAll(listOf("iss", "isszarya", "stationspatiale")))
    }

    @Test fun les_durees() {
        assertEquals("dans 25 minutes", CommandesPc.dansFr(25 * 60_000L))
        assertEquals("dans 2 h 05", CommandesPc.dansFr(125 * 60_000L))
        assertEquals("in one minute", CommandesPc.dansEn(60_000L))
        assertEquals("dans moins d'une minute", CommandesPc.dansFr(10_000L))
    }
}
