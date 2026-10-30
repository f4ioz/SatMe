/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.SecoursDatv.Candidat
import fr.f4ioz.satcombo.datv.SecoursDatv.Cible
import fr.f4ioz.satcombo.datv.SecoursDatv.Mode
import fr.f4ioz.satcombo.datv.SecoursDatv.Origine
import fr.f4ioz.satcombo.datv.SecoursDatv.Phase
import fr.f4ioz.satcombo.datv.SecoursDatv.Reglages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Balayage auto »: when it moves, where to, and when it never does. */
class SecoursDatvTest {

    private var t = 1_000_000_000L
    private val BALISE = Cible(Datv.BALISE_KHZ, 1500, "balise")
    private val SUIVI = Cible(10_494_750L, 1500, "F4AAA")
    private val FORT = Cible(10_496_250L, 2000, "G0BBB")
    private val FAIBLE = Cible(10_497_250L, 333, "")
    private val RECENT = Cible(10_498_500L, 250, "ON4CCC")
    private val FAV = Cible(10_493_250L, 1000, "PA0FAV")

    /** A receiver driven by the machine: it goes where told, locks or not as the test says. */
    private inner class Banc(r: Reglages = Reglages(actif = true)) {
        val m = SecoursDatv().also { it.reglages = r }
        var ici = SUIVI
        var actif = true
        /** Which places give a good picture. */
        val bons = HashSet<Cible>()
        /** Which places lock but give a damaged stream. */
        val abimes = HashSet<Cible>()
        var refus: String? = null
        val accords = ArrayList<SecoursDatv.Action>()
        var frais = true
        var bloque: String? = null
        var candidats: List<Candidat> = spectre()

        fun bon(c: Cible) = bons.any { it.meme(c) }
        fun tic(): SecoursDatv.Action? {
            val verrou = bon(ici) || abimes.any { it.meme(ici) }
            val a = m.tic(t, SecoursDatv.Reception(actif, verrou, bon(ici), ici.khz, ici.srKs), candidats, frais, bloque) ?: return null
            accords += a
            val e = refus
            if (e == null) { ici = a.cible; m.accorde(t, a) } else m.refuse(t, a, e)
            return a
        }
        /** [s] seconds, a tic each. */
        fun avance(s: Int) = repeat(s) { t += 1000L; tic() }
    }

    /** The beacon, a strong station, a weak one, one received lately, the followed one: all on a fresh spectrum. */
    private fun spectre(sansSuivi: Boolean = false, sansBalise: Boolean = false) = listOfNotNull(
        Candidat(BALISE, Origine.BALISE, 30_000, 0L, !sansBalise),
        Candidat(FORT, Origine.SPECTRE, 28_000, 0L, true),
        Candidat(FAIBLE, Origine.SPECTRE, 17_000, 0L, true),
        Candidat(RECENT, Origine.SPECTRE, 16_000, t - 600_000L, true),
        if (sansSuivi) null else Candidat(SUIVI, Origine.SPECTRE, 25_000, 0L, true))

    @Test fun `tourner - x seconds on each in turn, the followed one included, even while all is well`() {
        // 30/10: « x secondes sur chacun » only moved when the followed signal failed; the owner wanted a scan.
        val b = Banc(Reglages(actif = true, mode = SecoursDatv.Mode.TOURNE, dureeS = 30, attenteS = 15))
        b.bons += listOf(SUIVI, BALISE, FORT, RECENT, FAIBLE)
        b.tic()
        b.avance(29); assertTrue(b.accords.isEmpty())
        // The order: the beacon, the one received lately, then the strongest — the followed one at its place.
        val vus = ArrayList<Cible>()
        repeat(5) { b.avance(30); vus += b.ici }
        assertEquals(listOf(FAIBLE, BALISE, RECENT, FORT, SUIVI).map { it.khz }, vus.map { it.khz })
        assertTrue(b.accords.last().versPrefere)
        // One without a picture is left after [attenteS], not [dureeS].
        b.bons.remove(FAIBLE)
        b.avance(1); assertEquals(FAIBLE.khz, b.ici.khz)
        b.avance(14); assertEquals(FAIBLE.khz, b.ici.khz)
        b.avance(1); assertEquals(BALISE.khz, b.ici.khz)
    }

    @Test fun `off, it never moves`() {
        val b = Banc(Reglages(actif = false))
        b.avance(600)
        assertTrue(b.accords.isEmpty())
        assertEquals(Phase.ARRET, b.m.phase)
    }

    @Test fun `the followed signal holding, it stays there`() {
        val b = Banc()
        b.bons += SUIVI
        b.avance(3600)
        assertTrue(b.accords.isEmpty())
        assertEquals(SUIVI, b.m.prefere)
        assertEquals(Phase.SUIVI, b.m.phase)
        assertTrue(b.m.etat, b.m.etat.contains("tout va bien"))
    }

    @Test fun `no lock for the wait, then the beacon (no favourite), saying why`() {
        val b = Banc()
        b.bons += BALISE
        b.avance(14)
        assertTrue(b.accords.isEmpty())
        assertTrue(b.m.etat, b.m.etat.contains("ne s’accroche pas") && b.m.etat.contains("repli dans"))
        b.avance(2)
        assertEquals(1, b.accords.size)
        assertEquals(BALISE, b.accords[0].cible)
        assertTrue(b.accords[0].raison, b.accords[0].raison.startsWith("le signal suivi ne s’accroche pas depuis 15 s"))
        assertFalse(b.accords[0].versPrefere)
        assertEquals(Phase.REPLI, b.m.phase)
        assertEquals(BALISE, b.m.courante)
        // The followed one is still the operator's.
        assertEquals(SUIVI, b.m.prefere)
    }

    @Test fun `a lost signal and a damaged stream count as no picture`() {
        val b = Banc()
        b.bons += SUIVI; b.bons += BALISE
        b.avance(30)
        b.bons -= SUIVI
        b.avance(14)
        assertTrue(b.m.etat, b.m.etat.contains("est perdu"))
        b.avance(2)
        assertEquals(BALISE, b.ici)
        // Locked but damaged (the beacon's encoder at fault): moved on too.
        val c = Banc()
        c.abimes += SUIVI; c.bons += BALISE
        c.avance(16)
        assertEquals(BALISE, c.ici)
        assertTrue(c.accords[0].raison, c.accords[0].raison.contains("a un flux abîmé"))
    }

    @Test fun `the order - favourites, the beacon, received lately, then the strongest`() {
        val l = SecoursDatv.ordonne(spectre() + Candidat(FAV, Origine.FAVORI, 0, 0L, true), Reglages(actif = true), SUIVI, true, t)
        assertEquals(listOf(FAV, BALISE, RECENT, FORT, SUIVI, FAIBLE), l.map { it.cible })
        // Without the beacon; an absent one at the end; a station received long ago is not « recent ».
        val l2 = SecoursDatv.ordonne(listOf(Candidat(FAV, Origine.FAVORI, 0, 0L, false), Candidat(BALISE, Origine.BALISE, 1, 0L, true),
            Candidat(FAIBLE, Origine.SPECTRE, 17_000, t - 7_200_000L, true), Candidat(FORT, Origine.SPECTRE, 28_000, 0L, true)),
            Reglages(actif = true, balise = false), null, true, t)
        assertEquals(listOf(FORT, FAIBLE, FAV), l2.map { it.cible })
        // A signal listed twice (a favourite seen on the spectrum): once, as the favourite.
        val l3 = SecoursDatv.ordonne(listOf(Candidat(FORT, Origine.FAVORI), Candidat(FORT.copy(khz = FORT.khz + 50), Origine.SPECTRE, 28_000, 0L, true)),
            Reglages(actif = true), null, false, t)
        assertEquals(1, l3.size); assertEquals(Origine.FAVORI, l3[0].origine)
    }

    @Test fun `favourites first, the followed one never offered as a fallback`() {
        val b = Banc()
        b.candidats = listOf(Candidat(SUIVI, Origine.FAVORI, 0, 0L, true), Candidat(FAV, Origine.FAVORI, 20_000, 0L, true)) + spectre()
        b.bons += FAV
        b.avance(16)
        assertEquals(FAV, b.ici)
    }

    @Test fun `until it drops - stays on a good fallback, moves on when it is lost`() {
        val b = Banc(Reglages(actif = true, mode = Mode.COUPURE, retour = false))
        b.bons += BALISE; b.bons += RECENT
        b.avance(16)
        assertEquals(BALISE, b.ici)
        b.avance(1800)
        assertEquals(BALISE, b.ici)
        assertEquals(1, b.accords.size)
        assertTrue(b.m.etat, b.m.etat.contains("jusqu’à la coupure"))
        // The beacon goes off: the next one in the order.
        b.bons -= BALISE
        b.avance(16)
        assertEquals(RECENT, b.ici)
        assertTrue(b.accords.last().raison, b.accords.last().raison.contains("balise") && b.accords.last().raison.contains("est perdu"))
    }

    @Test fun `x seconds on each - goes round the candidates, back to the first`() {
        val b = Banc(Reglages(actif = true, mode = Mode.DUREE, dureeS = 30, retour = false))
        b.bons += listOf(BALISE, RECENT, FORT, FAIBLE)
        b.avance(16)
        assertEquals(BALISE, b.ici)
        val vus = ArrayList<Cible>()
        repeat(4) { b.avance(30); vus += b.ici }
        assertEquals(listOf(RECENT, FORT, FAIBLE, BALISE), vus)
        // Never the followed one in the round (it is « retour »'s business).
        assertTrue(b.accords.none { it.cible == SUIVI })
        assertTrue(b.m.etat, b.m.etat.contains("encore"))
    }

    @Test fun `x seconds on each - one with no picture is left sooner`() {
        val b = Banc(Reglages(actif = true, mode = Mode.DUREE, dureeS = 60, attenteS = 15, retour = false))
        b.bons += listOf(BALISE, FORT)
        b.avance(16)
        assertEquals(BALISE, b.ici)
        b.avance(60)
        assertEquals(RECENT, b.ici)          // dwell over
        b.avance(16)
        assertEquals(FORT, b.ici)            // RECENT never locked: 15 s
    }

    @Test fun `a failed signal is left alone three minutes, unless nothing else is left`() {
        val b = Banc(Reglages(actif = true, mode = Mode.COUPURE, retour = false))
        b.candidats = listOf(Candidat(BALISE, Origine.BALISE, 30_000, 0L, true), Candidat(FORT, Origine.SPECTRE, 28_000, 0L, true), Candidat(SUIVI, Origine.SPECTRE, 1, 0L, true))
        // Nothing gives a picture: suivi → balise → fort → (all failed) balise again.
        b.avance(16); assertEquals(BALISE, b.ici)
        b.avance(16); assertEquals(FORT, b.ici)
        b.avance(16); assertEquals(BALISE, b.ici)
        assertTrue(b.m.json(t).getJSONArray("candidats").getJSONObject(0).getBoolean("echec"))
    }

    @Test fun `back to the followed signal when it reappears on the spectrum`() {
        val b = Banc()
        b.bons += BALISE
        b.avance(16)
        assertEquals(BALISE, b.ici)
        // Gone from the spectrum for a while.
        b.candidats = spectre(sansSuivi = true)
        b.avance(120)
        assertEquals(BALISE, b.ici)
        // Back: the receiver returns to it.
        b.candidats = spectre()
        b.bons += SUIVI
        b.avance(1)
        assertEquals(SUIVI, b.ici)
        assertTrue(b.accords.last().versPrefere)
        assertTrue(b.accords.last().raison.contains("revenu"))
        assertNull(b.m.courante)
        b.avance(600)
        assertEquals(SUIVI, b.ici)
        assertEquals(2, b.accords.size)
    }

    @Test fun `no return sooner than a minute after it failed, and back to the fallback if it fails again`() {
        val b = Banc()
        b.bons += BALISE
        b.avance(16)
        b.candidats = spectre(sansSuivi = true); b.avance(5)
        b.candidats = spectre(); b.avance(5)
        assertEquals(BALISE, b.ici)          // reappeared, but failed only 10 s ago
        b.avance(50)
        assertEquals(SUIVI, b.ici)           // a minute after its failure
        b.avance(16)                          // still no picture there
        assertEquals(BALISE, b.ici)
    }

    @Test fun `seen all the time on the spectrum but damaged - tried again every five minutes only`() {
        val b = Banc()
        b.abimes += SUIVI; b.bons += BALISE
        b.avance(16)
        assertEquals(BALISE, b.ici)
        b.avance(SecoursDatv.ESSAI_PREFERE_MS.toInt() / 1000 - 2)
        assertEquals(BALISE, b.ici)
        b.avance(3)
        assertEquals(SUIVI, b.ici)
        assertTrue(b.accords.last().raison.contains("nouvel essai"))
    }

    @Test fun `without a spectrum - the favourites and the beacon, the followed one tried every five minutes`() {
        val b = Banc()
        b.frais = false
        b.candidats = listOf(Candidat(FAV, Origine.FAVORI), Candidat(BALISE, Origine.BALISE))
        b.bons += BALISE
        b.avance(16); assertEquals(FAV, b.ici)
        b.avance(16); assertEquals(BALISE, b.ici)
        b.avance(283)
        assertEquals(BALISE, b.ici)
        b.avance(6)
        assertTrue(SUIVI.meme(b.ici))   // unnamed: not on any list
    }

    @Test fun `absent signals and the beacon when not wanted are never tried`() {
        val b = Banc(Reglages(actif = true, balise = false))
        b.candidats = listOf(Candidat(BALISE, Origine.BALISE, 30_000, 0L, true), Candidat(FORT, Origine.SPECTRE, 28_000, 0L, false),
            Candidat(FAIBLE, Origine.SPECTRE, 17_000, 0L, true))
        b.avance(16)
        assertEquals(FAIBLE, b.ici)
        // Nothing at all to go to: says so, stays.
        val c = Banc()
        c.candidats = listOf(Candidat(BALISE, Origine.BALISE, 30_000, 0L, false))
        c.avance(60)
        assertTrue(c.accords.isEmpty())
        assertTrue(c.m.etat, c.m.etat.startsWith("aucun autre signal"))
    }

    @Test fun `a tuning by hand pauses it, and becomes the followed signal`() {
        val b = Banc(Reglages(actif = true, pauseMin = 10))
        b.bons += BALISE
        b.avance(16)
        assertEquals(BALISE, b.ici)
        // The operator chooses FORT (which gives nothing): left alone ten minutes.
        b.m.manuel(t, FORT); b.ici = FORT
        b.avance(599)
        assertEquals(1, b.accords.size)
        assertEquals(Phase.PAUSE, b.m.phase)
        assertTrue(b.m.etat, b.m.etat.contains("en pause"))
        assertEquals(FORT, b.m.prefere)
        b.avance(2)
        assertEquals(2, b.accords.size)
        assertEquals(BALISE, b.ici)
        assertTrue(b.accords.last().raison.startsWith("le signal suivi"))
    }

    @Test fun `the receiver found elsewhere than where it was put - a tuning by hand too`() {
        val b = Banc()
        b.bons += BALISE
        b.avance(16)
        // Someone tuned elsewhere without telling (a phone, an old page).
        b.ici = FAIBLE
        b.avance(1)
        assertEquals(FAIBLE, b.m.prefere)
        assertEquals(Phase.PAUSE, b.m.phase)
        assertEquals(1, b.accords.size)
    }

    @Test fun `the « reprendre » button ends the pause`() {
        val b = Banc()
        b.bons += BALISE
        b.m.manuel(t, SUIVI)
        b.avance(30)
        assertTrue(b.accords.isEmpty())
        b.m.reprends()
        b.avance(1)
        assertEquals(BALISE, b.ici)
    }

    @Test fun `stopped, it stays stopped`() {
        val b = Banc()
        b.actif = false
        b.avance(600)
        assertTrue(b.accords.isEmpty())
        assertTrue(b.m.etat.contains("arrêté"))
        // Started again on the followed one: taken up without a pause.
        b.actif = true; b.bons += BALISE
        b.avance(16)
        assertEquals(BALISE, b.ici)
    }

    @Test fun `held by the veille or a recording - nothing, and where they left it is the operator's`() {
        val b = Banc()
        b.bons += BALISE
        b.avance(3)
        b.bloque = "la veille du spectre mène"
        b.avance(120)
        assertTrue(b.accords.isEmpty())
        assertTrue(b.m.etat, b.m.etat.contains("la veille"))
        // The veille ends on FORT: followed from now, after the pause.
        b.ici = FORT; b.bloque = null
        b.avance(1)
        assertEquals(FORT, b.m.prefere)
        assertEquals(Phase.PAUSE, b.m.phase)
        // A hold ending where it began: simply taken up.
        val c = Banc()
        c.bons += BALISE
        c.avance(3)
        c.bloque = "enregistrement en cours"; c.avance(60)
        c.bloque = null; c.avance(1)
        assertEquals(Phase.SUIVI, c.m.phase)
        c.avance(15)
        assertEquals(BALISE, c.ici)
    }

    @Test fun `a refused tuning - that signal left alone, the next one tried, the receiver not taken as moved`() {
        val b = Banc()
        b.bons += listOf(BALISE, RECENT)
        b.refus = "LongMynd introuvable"
        b.avance(16)
        assertEquals(SUIVI, b.ici)
        assertTrue(b.m.etat.startsWith("refusé"))
        b.refus = null
        b.avance(1)
        assertEquals(RECENT, b.ici)
        assertTrue(b.m.phase != Phase.PAUSE)
    }

    @Test fun `switched on - the signal received now becomes the followed one`() {
        val b = Banc(Reglages(actif = false))
        b.ici = FORT
        b.avance(5)
        b.m.active(true)
        b.avance(1)
        assertEquals(FORT, b.m.prefere)
        b.m.active(false)
        assertNull(b.m.courante)
        assertEquals(Phase.ARRET, b.m.phase)
    }

    @Test fun `settings kept within bounds, the page's view`() {
        val r = Reglages(actif = true, dureeS = 1, attenteS = 1000, pauseMin = 0).borne()
        assertEquals(10, r.dureeS); assertEquals(120, r.attenteS); assertEquals(1, r.pauseMin)
        assertEquals(Mode.DUREE, Mode.de("duree")); assertEquals(Mode.COUPURE, Mode.de("n'importe"))
        val b = Banc()
        b.bons += BALISE
        b.avance(17)
        val j = b.m.json(t)
        assertTrue(j.getBoolean("on"))
        assertEquals("repli", j.getString("phase"))
        assertEquals(SUIVI.khz, j.getJSONObject("prefere").getLong("khz"))
        assertEquals(BALISE.khz, j.getJSONObject("courante").getLong("khz"))
        assertEquals(1, j.getJSONArray("journal").length())
        assertTrue(j.getJSONArray("candidats").getJSONObject(0).getBoolean("ici"))
        assertEquals("10491,500 MHz · 1500 kS/s", Cible(Datv.BALISE_KHZ, 1500).libelle)
        assertEquals("balise · 10491,500 MHz · 1500 kS/s", BALISE.libelle)
    }

    @Test fun `the same station measured a little off is the same place`() {
        assertTrue(SUIVI.meme(SUIVI.khz + 200, 1500))
        assertFalse(SUIVI.meme(SUIVI.khz + 600, 1500))
        assertFalse(SUIVI.meme(SUIVI.khz, 1000))
    }
}
