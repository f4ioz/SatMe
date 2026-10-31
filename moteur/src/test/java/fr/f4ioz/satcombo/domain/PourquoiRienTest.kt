/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.audio.MesureEntree
import fr.f4ioz.satcombo.domain.PourquoiRien.Gravite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One line per receiver naming the likely cause, dated, from the facts given and nothing else. */
class PourquoiRienTest {

    private val t = 1_000_000_000L
    private val sstvIss = PourquoiRien.Voie(1, "SSTV", 145_800_000L, null, "FM")
    private val voixIss = PourquoiRien.Voie(0, "Mode V/U FM", 437_800_000L, null, "FM")
    private val aprsIss = PourquoiRien.Voie(2, "APRS", 145_825_000L, null, "AFSK")

    private fun ciel(el: Double = 30.0, choisie: Int = 1, aos: Long? = null) =
        PourquoiRien.Ciel("ISS", 25544, el, aos, listOf(voixIss, sstvIss, aprsIss), choisie)

    private val carte = PourquoiRien.Entree(true, "IC-9700 USB", t - 100, -32.0, -12.0, 10_000L)
    private fun poste(dial: Long? = 145_800_000L, mode: String? = "FM", lu: Long = t - 3_000, muet: Long? = null, maintien: Boolean = false) =
        PourquoiRien.Poste("IC-9700", dial, lu, mode, lu, muet, maintien)

    private fun ctx(entree: PourquoiRien.Entree? = carte, poste: PourquoiRien.Poste? = poste(), ciel: PourquoiRien.Ciel? = ciel(),
                    parSdr: Boolean = false, sdr: PourquoiRien.Sdr? = null) = PourquoiRien.Contexte(t, entree, parSdr, sdr, poste, ciel)

    @Test
    fun le_poste_sur_une_autre_frequence_est_nomme_avec_l_heure_de_la_lecture_et_accorder() {
        val d = PourquoiRien.sstv(ctx(poste = poste(dial = 145_990_000L)), true, 0, "", sstvIss)
        assertEquals(Gravite.ATTENTION, d.gravite)
        assertEquals("IC-9700 sur 145,990 FM (lu il y a 3 s) — la SSTV de ISS est sur 145,800 FM.", d.texte)
        assertEquals("api/emetteur", d.action!!.chemin)
        assertEquals("1", d.action!!.params["i"])
        assertEquals("Accorder", d.action!!.libelle)
    }

    @Test
    fun un_releve_ancien_est_dit_ancien() {
        val d = PourquoiRien.sstv(ctx(poste = poste(dial = 145_990_000L, lu = t - 4 * 60_000)), true, 0, "", sstvIss)
        assertTrue(d.texte, d.texte.contains("lu il y a 4 min, relevé ancien"))
    }

    @Test
    fun doppler_en_maintien_le_geste_est_de_le_reprendre() {
        val d = PourquoiRien.sstv(ctx(poste = poste(dial = 145_990_000L, maintien = true)), true, 0, "", sstvIss)
        assertEquals("api/maintien", d.action!!.chemin)
        assertEquals("0", d.action!!.params["on"])
    }

    @Test
    fun le_doppler_ne_fait_pas_dire_hors_frequence() {
        // 145.8 MHz, the ISS approaching: +3.5 kHz.
        val d = PourquoiRien.sstv(ctx(poste = poste(dial = 145_803_500L)), true, 0, "", sstvIss)
        assertEquals(Gravite.INFO, d.gravite)
    }

    @Test
    fun le_mauvais_mode() {
        val d = PourquoiRien.sstv(ctx(poste = poste(mode = "USB")), true, 0, "", sstvIss)
        assertEquals(Gravite.ATTENTION, d.gravite)
        assertTrue(d.texte, d.texte.startsWith("IC-9700 en USB (lu il y a 3 s) — la SSTV de ISS se reçoit en FM"))
        // USB-D for a USB function is right.
        assertTrue(PourquoiRien.memeMode("USB-D", "USB"))
    }

    @Test
    fun l_emetteur_choisi_n_est_pas_celui_de_la_fonction() {
        val d = PourquoiRien.sstv(ctx(ciel = ciel(choisie = 0), poste = poste(dial = 437_800_000L)), true, 0, "", sstvIss)
        assertTrue(d.texte, d.texte.startsWith("Émetteur choisi : « Mode V/U FM » — la SSTV de ISS est sur 145,800 FM"))
        assertEquals("1", d.action!!.params["i"])
    }

    @Test
    fun carte_morte_contre_carte_silencieuse() {
        val morte = PourquoiRien.sstv(ctx(entree = carte.copy(dernierBlocMs = t - 12_000)), true, 0, "", sstvIss)
        assertEquals(Gravite.PANNE, morte.gravite)
        assertTrue(morte.texte, morte.texte.contains("ne donne plus aucun échantillon depuis 12 s"))
        val jamais = PourquoiRien.aprs(ctx(entree = carte.copy(dernierBlocMs = 0L)), true, 0, aprsIss.copy())
        assertEquals(Gravite.PANNE, jamais.gravite)
        // Alive but quiet: a squelch, not a fault — with the level and its window.
        val calme = PourquoiRien.sstv(ctx(entree = carte.copy(rmsDbfs = -78.4, creteDbfs = -70.0)), true, 0, "", sstvIss)
        assertEquals(Gravite.INFO, calme.gravite)
        assertTrue(calme.texte, calme.texte.contains("−78,4 dBFS en moyenne sur 10 s"))
        assertTrue(calme.texte.contains("silencieux (squelch) fermé"))
        assertTrue(calme.texte.contains("pas une panne"))
        val sature = PourquoiRien.sstv(ctx(entree = carte.copy(creteDbfs = -0.1)), true, 0, "", sstvIss)
        assertEquals(Gravite.ATTENTION, sature.gravite)
        val aucune = PourquoiRien.sstv(ctx(entree = null), true, 0, "", sstvIss)
        assertEquals(Gravite.PANNE, aucune.gravite)
    }

    @Test
    fun sous_l_horizon_avec_le_prochain_passage() {
        val d = PourquoiRien.sstv(ctx(ciel = ciel(el = -12.0, aos = t + 42 * 60_000L), poste = poste(dial = 145_990_000L)), true, 0, "", sstvIss)
        assertEquals(Gravite.INFO, d.gravite)
        assertEquals("ISS est sous l'horizon (−12°) — prochain passage dans 42 min.", d.texte)
        assertNull(d.action)
    }

    @Test
    fun poste_muet_avant_tout_le_reste() {
        val d = PourquoiRien.sstv(ctx(poste = poste(dial = 145_990_000L, muet = t - 120_000)), true, 0, "", sstvIss)
        assertEquals(Gravite.PANNE, d.gravite)
        assertTrue(d.texte, d.texte.startsWith("IC-9700 muet depuis 2 min"))
    }

    @Test
    fun une_image_recente_ou_en_cours_suffit() {
        assertEquals(Gravite.OK, PourquoiRien.sstv(ctx(poste = poste(dial = 145_990_000L)), true, t - 30_000, "", sstvIss).gravite)
        assertEquals("Image en cours : PD120, ligne 200/496.", PourquoiRien.sstv(ctx(), true, 0, "PD120, ligne 200/496", sstvIss).texte)
        assertEquals(Gravite.INFO, PourquoiRien.sstv(ctx(), false, 0, "", sstvIss).gravite)
    }

    @Test
    fun par_la_cle_sdr() {
        val arretee = PourquoiRien.sstv(ctx(parSdr = true, sdr = PourquoiRien.Sdr(false, 0)), true, 0, "", sstvIss)
        assertEquals(Gravite.PANNE, arretee.gravite)
        val ailleurs = PourquoiRien.sstv(ctx(parSdr = true, sdr = PourquoiRien.Sdr(true, 137_100_000L)), true, 0, "", sstvIss)
        assertEquals("Clé SDR sur 137,100 MHz — la SSTV de ISS est sur 145,800 FM.", ailleurs.texte)
        // The rig's dial is not what is heard then.
        val ok = PourquoiRien.sstv(ctx(parSdr = true, sdr = PourquoiRien.Sdr(true, 145_801_000L), poste = poste(dial = 435_000_000L)), true, 0, "", sstvIss)
        assertEquals(Gravite.INFO, ok.gravite)
    }

    @Test
    fun ft8_en_fm_ou_hors_frequence() {
        val fm = PourquoiRien.ft8(ctx(ciel = null, poste = poste(dial = 145_990_000L, mode = "FM")), true, "FT8", 0, 12, false)
        assertEquals(Gravite.ATTENTION, fm.gravite)
        assertTrue(fm.texte, fm.texte.contains("FT8 se reçoit en USB (USB-D)"))
        val loin = PourquoiRien.ft8(ctx(ciel = null, poste = poste(dial = 145_990_000L, mode = "USB-D")), true, "FT8", 0, 3, false)
        assertEquals(Gravite.INFO, loin.gravite)
        assertTrue(loin.texte, loin.texte.contains("pas une fréquence FT8 habituelle"))
        val bon = PourquoiRien.ft8(ctx(ciel = null, poste = poste(dial = 144_174_000L, mode = "USB-D")), true, "FT8", 0, 3, false)
        assertTrue(bon.texte, bon.texte.contains("3 créneaux sans rien"))
        assertTrue(PourquoiRien.frequenceFt8(10_489_540_000L))
    }

    @Test
    fun telemesure_signal_vu_mais_crc_faux() {
        val p = PourquoiRien.Paquets(0, 14, 0, -88.0, -100.0, true, 9600)
        val v = PourquoiRien.Voie(3, "9k6 GMSK", 435_400_000L, null, "GMSK")
        val c = PourquoiRien.Ciel("SAT", 1, 40.0, null, listOf(v), 3)
        val d = PourquoiRien.telemesure(t, true, p, PourquoiRien.Sdr(true, 435_401_000L), c, v)
        assertEquals(Gravite.ATTENTION, d.gravite)
        assertTrue(d.texte, d.texte.contains("12,0 dB au-dessus du bruit") && d.texte.contains("14 CRC faux"))
        val sansSdr = PourquoiRien.telemesure(t, true, p, null, c, v)
        assertEquals(Gravite.PANNE, sansSdr.gravite)
    }

    @Test
    fun datv_noeud_lnb_balise() {
        val base = PourquoiRien.Datv(true, true, 1, "recherche", null, t - 120_000, 0, 10_494_750L, 333, "h", "")
        assertEquals(Gravite.PANNE, PourquoiRien.datv(t, base.copy(noeudMuetMs = t - 30_000)).gravite)
        val v = PourquoiRien.datv(t, base.copy(lnb = "v"))
        assertTrue(v.texte, v.texte.contains("13 V") && v.texte.contains("horizontale (18 V)"))
        val b = PourquoiRien.datv(t, base.copy(balise = 7.4 to t - 25 * 60_000))
        assertTrue(b.texte, b.texte.contains("Pas d'accroche sur 10 494,750 MHz, 333 kS/s depuis 2 min") && b.texte.contains("MER 7,4 dB, il y a 25 min"))
        val ok = PourquoiRien.datv(t, base.copy(etat = 4, libelle = "accroché DVB-S2", merDb = 6.8, verrouDepuisMs = t - 60_000))
        assertEquals(Gravite.OK, ok.gravite)
        assertEquals(Gravite.INFO, PourquoiRien.datv(t, base.copy(actif = false)).gravite)
    }

    @Test
    fun qo100_balise() {
        val s = PourquoiRien.Sdr(true, 10_489_750_000L)
        assertEquals(Gravite.OK, PourquoiRien.qo100(t, s, true, 21.0, t - 2_000).gravite)
        val vieille = PourquoiRien.qo100(t, s, true, 21.0, t - 4 * 60_000)
        assertEquals(Gravite.ATTENTION, vieille.gravite)
        assertTrue(vieille.texte, vieille.texte.startsWith("Balise non vue depuis 4 min"))
        assertEquals(Gravite.INFO, PourquoiRien.qo100(t, s, false, null, 0).gravite)
        assertEquals(Gravite.INFO, PourquoiRien.large(t, true, false, "en attente de l’onglet DATV ou QO-100", 0).gravite)
        assertEquals(Gravite.ATTENTION, PourquoiRien.large(t, true, false, "indisponible (refusé), nouvel essai dans 4 s", t - 60_000).gravite)
    }

    @Test
    fun aucun_geste_ne_vise_une_route_d_emission() {
        for (r in listOf("api/poste/emission/demarre", "api/ft8/tx", "api/datv/emission/demarre", "api/sstv/emission/demarre", "api/be/emission/demarre"))
            try { PourquoiRien.Action(r, emptyMap(), "x"); fail(r) } catch (e: IllegalArgumentException) { }
        assertTrue(PourquoiRien.CHEMINS_PERMIS.none { "emission" in it || "tx" in it || "ptt" in it })
    }

    @Test
    fun la_mesure_de_l_entree() {
        val m = MesureEntree()
        val bruit = ShortArray(4800) { if (it % 2 == 0) 33 else -33 }
        var u = t
        repeat(20) { m.bloc(bruit, bruit.size, u); u += 500 }
        val i = m.instantane(u)
        assertEquals(u - 500, i.dernierBlocMs)
        assertEquals(-60.0, i.rmsDbfs!!, 0.1)
        assertEquals(10_000L, i.fenetreMs)
        // Nothing since: the window empties, the last block stays dated.
        val tard = m.instantane(u + 30_000)
        assertNull(tard.rmsDbfs)
        assertEquals(u - 500, tard.dernierBlocMs)
    }
}
