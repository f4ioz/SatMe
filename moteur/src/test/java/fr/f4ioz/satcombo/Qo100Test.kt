/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.Qo100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QO-100 frequency plan and dish pointing — neither checkable by eye.
 *
 * The 8089.5 MHz offset is a transponder fact; one MHz off and the app would
 * transmit outside the band, undetectable from home. Pinned on the published
 * narrowband edges.
 *
 * A 60 cm dish has a ~3° beamwidth. Reference values were computed
 * independently and match published French figures (Paris 150°/30°); pinned
 * to 0.01° as a change detector. Cape Town and Doha are included because the
 * textbook closed formula switches branch there — why the maths uses vectors.
 */
class Qo100Test {

    /** 0.01°: far below what anyone can point. */
    private val tol = 0.01

    private fun verifie(
        nom: String, lat: Double, lon: Double,
        az: Double, el: Double, skew: Double,
    ) {
        val p = Qo100.pointage(lat, lon)
        assertEquals("$nom azimut", az, p.azDeg, tol)
        assertEquals("$nom élévation", el, p.elDeg, tol)
        assertEquals("$nom skew", skew, p.skewDeg, tol)
        assertTrue("$nom devrait voir le satellite", p.visible)
    }

    // --- Pointing from reference cities ------------------------------------

    @Test
    fun paris_pointe_au_sud_sud_est_a_trente_degres() {
        verifie("Paris", 48.8566, 2.3522, az = 149.942, el = 29.533, skew = 19.242)
    }

    @Test
    fun les_quatre_coins_de_la_france_donnent_les_valeurs_publiees() {
        verifie("Brest", 48.39, -4.49, az = 141.890, el = 27.216, skew = 24.194)
        verifie("Nice", 43.70, 7.27, az = 153.990, el = 36.234, skew = 18.484)
        verifie("Marseille", 43.30, 5.37, az = 151.364, el = 35.929, skew = 20.413)
        verifie("Toulouse", 43.60, 1.44, az = 146.591, el = 34.036, skew = 23.500)
    }

    /**
     * From the southern hemisphere the satellite is to the *north* and skew
     * changes sign — where the closed formula lies.
     */
    @Test
    fun depuis_le_cap_le_satellite_est_au_nord() {
        verifie("Le Cap", -33.92, 18.42, az = 13.240, el = 49.753, skew = -10.956)
    }

    /**
     * Doha is east of the satellite: the dish faces south-west, azimuth past
     * 180°. The other half of the trap.
     */
    @Test
    fun depuis_doha_le_satellite_est_au_sud_ouest() {
        verifie("Doha", 25.29, 51.53, az = 228.317, el = 48.899, skew = -42.474)
    }

    /**
     * Directly below the satellite the dish looks at zenith. Azimuth is then
     * meaningless, so only elevation is checked.
     */
    @Test
    fun sous_le_satellite_la_parabole_regarde_en_haut() {
        val p = Qo100.pointage(0.0, Qo100.LONGITUDE_DEG)
        assertEquals(90.0, p.elDeg, tol)
        assertEquals(0.0, p.skewDeg, tol)
        assertTrue(p.visible)
    }

    /**
     * At the antipode the Earth is in the way: elevation is negative and
     * [Qo100.Pointage.visible] must say so. Showing "azimuth 42°" to someone
     * who cannot see it would be worse than nothing.
     */
    @Test
    fun aux_antipodes_le_satellite_est_sous_l_horizon() {
        val p = Qo100.pointage(0.0, Qo100.LONGITUDE_DEG - 180.0)
        assertTrue("élévation attendue négative, obtenue ${p.elDeg}", p.elDeg < 0.0)
        assertFalse(p.visible)
    }

    /**
     * Along one meridian, the further north, the lower the satellite. Geometry
     * guarantees it; a flipped sign anywhere would break it at once.
     */
    @Test
    fun l_elevation_baisse_quand_on_remonte_vers_le_nord() {
        val elevations = (0..70 step 10).map { Qo100.pointage(it.toDouble(), 25.9).elDeg }
        elevations.zipWithNext().forEach { (bas, haut) ->
            assertTrue("l'élévation devrait décroître : $bas puis $haut", haut < bas)
        }
    }

    // --- Frequency plan ---------------------------------------------------

    /**
     * The NB low edge matches the offset exactly: the best proof the 8089.5 MHz
     * constant is right.
     */
    @Test
    fun le_decalage_relie_exactement_le_bord_bas_du_transpondeur_etroit() {
        assertEquals(Qo100.NB.descenteBasHz, Qo100.descenteDepuisMontee(Qo100.NB.monteeBasHz))
        assertEquals(Qo100.NB.monteeBasHz, Qo100.monteeDepuisDescente(Qo100.NB.descenteBasHz))
    }

    /**
     * The published high edges (up 2400.490, down 10489.997) differ by 7 kHz
     * from the offset: that is the multimedia beacon, not an error — last TX
     * vs last RX frequency. Pinned so nobody "fixes" an edge.
     */
    @Test
    fun les_sept_kilohertz_du_bord_haut_sont_la_balise_multimedia() {
        val ecart = Qo100.NB.descenteHautHz - Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz)
        assertEquals(7_000L, ecart)

        // Those exact 7 kHz are a beacon: same width, same bounds.
        val multi = Qo100.SEGMENTS.first { it.cle == "balise_multimedia" }
        assertEquals(7_000L, multi.largeurHz)
        assertEquals(Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz), multi.basHz)
        assertEquals(Qo100.NB.descenteHautHz, multi.hautHz)
        assertEquals(Qo100.Usage.BALISE, multi.usage)

        // The published uplink edge is the last transmittable frequency.
        assertEquals(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ,
            Qo100.descenteDepuisMontee(Qo100.NB.monteeHautHz))

        // An uplink computed from the downlink high edge falls outside the
        // transponder: the computation never to do.
        val monteeCalculee = Qo100.monteeDepuisDescente(Qo100.NB.descenteHautHz)
        assertTrue("la montée déduite sort du transpondeur publié",
            monteeCalculee > Qo100.NB.monteeHautHz)
    }

    // --- Band plan, segment by segment ------------------------------------

    /**
     * The twelve segments join end to end from 10489.500 to 10490.000. A gap
     * would give an unnamed zone where TX is refused without reason; an
     * overlap would let list order pick the label.
     */
    @Test
    fun les_douze_segments_couvrent_la_reglette_sans_trou_ni_recouvrement() {
        assertEquals(12, Qo100.SEGMENTS.size)
        assertEquals(Qo100.BALISE_BASSE_HZ, Qo100.REGLETTE_BAS_HZ)
        assertEquals(Qo100.BALISE_HAUTE_HZ, Qo100.REGLETTE_HAUT_HZ)
        assertEquals(500_000L, Qo100.REGLETTE_HAUT_HZ - Qo100.REGLETTE_BAS_HZ)

        Qo100.SEGMENTS.zipWithNext().forEach { (a, b) ->
            assertEquals("« ${a.cle} » et « ${b.cle} » ne se touchent pas", a.hautHz, b.basHz)
        }
        Qo100.SEGMENTS.forEach {
            assertTrue("le segment « ${it.cle} » est vide ou à l'envers", it.largeurHz > 0)
        }
        // Keys build translation keys: they must be unique, or two segments
        // share a label.
        assertEquals(12, Qo100.SEGMENTS.map { it.cle }.toSet().size)
    }

    /**
     * Fine sweep: every ruler frequency belongs to exactly one segment, and
     * [Qo100.segment] returns it.
     */
    @Test
    fun chaque_frequence_de_la_reglette_appartient_a_un_seul_segment() {
        var hz = Qo100.REGLETTE_BAS_HZ
        while (hz <= Qo100.REGLETTE_HAUT_HZ) {
            val trouves = Qo100.SEGMENTS.filter { hz in it }
            // The very last frequency is the upper bound, closed by hand.
            val attendu = if (hz == Qo100.REGLETTE_HAUT_HZ) 0 else 1
            assertEquals("$hz appartient à ${trouves.size} segments", attendu, trouves.size)
            assertTrue("aucun segment rendu pour $hz", Qo100.segment(hz) != null)
            hz += 500L
        }
    }

    @Test
    fun hors_de_la_reglette_il_n_y_a_pas_de_segment() {
        assertNull(Qo100.segment(Qo100.REGLETTE_BAS_HZ - 1))
        assertNull(Qo100.segment(Qo100.REGLETTE_HAUT_HZ + 1))
        assertNull(Qo100.segment(145_800_000L))
        // Hence no transmission either: the default answer is "no".
        assertFalse(Qo100.emissionAutorisee(Qo100.REGLETTE_BAS_HZ - 1))
        assertFalse(Qo100.emissionAutorisee(2_400_100_000L))
    }

    /**
     * No transmitting on any of the four beacons — the reason for this table:
     * otherwise a carrier could land on the middle beacon everyone uses.
     */
    @Test
    fun on_n_emet_sur_aucune_des_quatre_balises() {
        val balises = Qo100.SEGMENTS.filter { it.usage == Qo100.Usage.BALISE }
        assertEquals(4, balises.size)
        assertEquals(listOf("balise_basse", "balise_mediane", "balise_multimedia", "balise_haute"),
            balises.map { it.cle })
        balises.forEach {
            assertFalse("« ${it.cle} » ne devrait pas permettre l'émission", it.emissionPermise)
        }
        listOf(Qo100.BALISE_BASSE_HZ, Qo100.BALISE_MEDIANE_HZ, Qo100.BALISE_HAUTE_HZ).forEach {
            assertFalse("émission autorisée sur la balise $it", Qo100.emissionAutorisee(it))
        }
        // And the multimedia one, which has no constant of its own.
        assertFalse(Qo100.emissionAutorisee(10_489_993_500L))
    }

    /**
     * The upper boundary, to the kilohertz. The one place here where 1 kHz off
     * means interference.
     */
    @Test
    fun la_derniere_frequence_emissible_est_dix_mille_quatre_cent_quatre_vingt_neuf_neuf_cent_quatre_vingt_dix() {
        assertEquals(10_489_990_000L, Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ)
        assertTrue(Qo100.emissionAutorisee(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ - 1))
        assertFalse(Qo100.emissionAutorisee(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ))
        assertFalse(Qo100.emissionAutorisee(Qo100.NB.descenteHautHz))
        // The matching uplink is exactly the published high edge.
        assertEquals(Qo100.NB.monteeHautHz,
            Qo100.monteeDepuisDescente(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ))
    }

    /**
     * The usable passband starts at the first transmit segment: [Qo100.NB] and
     * [Qo100.SEGMENTS] describe the same band.
     */
    @Test
    fun les_bornes_du_transpondeur_etroit_concordent_avec_les_segments() {
        val premierEmissible = Qo100.SEGMENTS.first { it.emissionPermise }
        assertEquals(Qo100.NB.descenteBasHz, premierEmissible.basHz)
        assertEquals("cw", premierEmissible.cle)
        val dernierEmissible = Qo100.SEGMENTS.last { it.emissionPermise }
        assertEquals(Qo100.DERNIERE_DESCENTE_EMISSIBLE_HZ, dernierEmissible.hautHz)
    }

    /**
     * Published maximum bandwidths. The 500 Hz narrow digital segment is the
     * only one not at 2.7 kHz, and where a stray SSB signal would wipe out the
     * most stations.
     */
    @Test
    fun les_largeurs_maximales_suivent_le_plan_publie() {
        fun seg(cle: String) = Qo100.SEGMENTS.first { it.cle == cle }
        assertEquals(500, seg("num_etroit").largeurMaxHz)
        assertEquals(2_700, seg("num_large").largeurMaxHz)
        assertEquals(2_700, seg("ssb_bas").largeurMaxHz)
        assertEquals(2_700, seg("ssb_haut").largeurMaxHz)
        assertEquals(2_700, seg("mixte").largeurMaxHz)
        // CW has no width in the plan: practice is the rule.
        assertEquals(0, seg("cw").largeurMaxHz)
    }

    /**
     * Broadcast and emergency: two 7.5 kHz slices around 10489.855 and
     * 10489.860. The boundary falls on a half kilohertz — looks like a typo,
     * is not.
     */
    @Test
    fun la_diffusion_et_l_urgence_font_sept_kilohertz_et_demi_chacune() {
        val diff = Qo100.SEGMENTS.first { it.cle == "diffusion" }
        val urg = Qo100.SEGMENTS.first { it.cle == "urgence" }
        assertEquals(7_500L, diff.largeurHz)
        assertEquals(7_500L, urg.largeurHz)
        assertEquals(10_489_855_000L, diff.repereHz!!)
        assertEquals(10_489_860_000L, urg.repereHz!!)
        assertEquals(10_489_857_500L, diff.hautHz)
        // Transmitting is allowed: reserved uses, not prohibitions.
        assertTrue(diff.emissionPermise)
        assertTrue(urg.emissionPermise)
    }

    /**
     * A segment's marker, if any, lies within that segment; otherwise the screen
     * would draw a beacon mark beside its beacon.
     */
    @Test
    fun les_reperes_tombent_dans_leur_propre_segment() {
        Qo100.SEGMENTS.forEach { s ->
            val r = s.repereHz ?: return@forEach
            assertTrue("le repère $r sort du segment « ${s.cle} »",
                r in s || r == s.hautHz)
        }
        // Six segments carry a marker: four beacons, broadcast, emergency. The
        // count catches one disappearing.
        assertEquals(6, Qo100.SEGMENTS.count { it.repereHz != null })
    }

    @Test
    fun le_decalage_relie_aussi_les_bords_du_transpondeur_large() {
        assertEquals(Qo100.WB.descenteBasHz, Qo100.descenteDepuisMontee(Qo100.WB.monteeBasHz))
        assertEquals(Qo100.WB.descenteHautHz, Qo100.descenteDepuisMontee(Qo100.WB.monteeHautHz))
    }

    /**
     * Non-inverting: +1 kHz up is +1 kHz down. Unlike most linear
     * transponders; forgetting it would flip the band.
     */
    @Test
    fun le_transpondeur_n_inverse_pas_le_spectre() {
        val a = Qo100.descenteDepuisMontee(2_400_100_000L)
        val b = Qo100.descenteDepuisMontee(2_400_101_000L)
        assertEquals(1_000L, b - a)
    }

    @Test
    fun l_aller_retour_montee_descente_ne_perd_rien() {
        listOf(2_400_005_000L, 2_400_250_000L, 2_400_490_000L).forEach { m ->
            assertEquals(m, Qo100.monteeDepuisDescente(Qo100.descenteDepuisMontee(m)))
        }
    }

    /** The three CW beacons: the middle one inside NB, the others just outside. */
    @Test
    fun les_balises_encadrent_le_transpondeur_etroit() {
        assertTrue(Qo100.BALISE_BASSE_HZ < Qo100.NB.descenteBasHz)
        assertTrue(Qo100.NB.contientDescente(Qo100.BALISE_MEDIANE_HZ))
        assertTrue(Qo100.BALISE_HAUTE_HZ > Qo100.NB.descenteHautHz)
        // 250 kHz apart: the landmarks one listens for.
        assertEquals(250_000L, Qo100.BALISE_MEDIANE_HZ - Qo100.BALISE_BASSE_HZ)
        assertEquals(250_000L, Qo100.BALISE_HAUTE_HZ - Qo100.BALISE_MEDIANE_HZ)
    }

    /**
     * The middle beacon sits at the NB centre, within 1 kHz: a sensible
     * starting point when opening the screen.
     */
    @Test
    fun le_centre_du_transpondeur_etroit_est_sur_la_balise_mediane() {
        assertEquals(Qo100.BALISE_MEDIANE_HZ.toDouble(),
            Qo100.NB.centreDescenteHz.toDouble(), 1_000.0)
    }

    @Test
    fun le_transpondeur_etroit_fait_bien_492_kilohertz() {
        assertEquals(492_000L, Qo100.NB.largeurHz)
        assertEquals(8_000_000L, Qo100.WB.largeurHz)
    }

    @Test
    fun la_bride_ne_laisse_jamais_sortir_du_transpondeur() {
        assertEquals(Qo100.NB.descenteBasHz, Qo100.NB.brideDescente(10_000_000_000L))
        assertEquals(Qo100.NB.descenteHautHz, Qo100.NB.brideDescente(11_000_000_000L))
        assertEquals(10_489_750_000L, Qo100.NB.brideDescente(10_489_750_000L))
    }

    @Test
    fun les_deux_transpondeurs_ne_se_chevauchent_pas() {
        assertFalse(Qo100.NB.contientDescente(Qo100.WB.descenteBasHz))
        assertFalse(Qo100.WB.contientDescente(Qo100.NB.descenteHautHz))
        assertEquals(2, Qo100.TRANSPONDEURS.size)
        assertEquals(setOf("nb", "wb"), Qo100.TRANSPONDEURS.map { it.cle }.toSet())
    }

    /**
     * The NORAD number keys the stored calibration offset: if it changed, the
     * beacon calibration would be silently lost.
     */
    @Test
    fun le_numero_de_catalogue_est_celui_d_es_hail_2() {
        assertEquals(43700, Qo100.NORAD)
        assertEquals(25.9, Qo100.LONGITUDE_DEG, 1e-9)
        assertEquals(8_089_500_000L, Qo100.DECALAGE_HZ)
    }
}
