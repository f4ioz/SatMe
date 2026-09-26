/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.AttitudeWit
import fr.f4ioz.satcombo.domain.BoussoleWit
import fr.f4ioz.satcombo.domain.PointageAntenne
import fr.f4ioz.satcombo.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Antenna pointing from the IMU. **Rotating the antenna about its own axis
 * must not change where it points** — the field bug was right readings in
 * horizontal polarisation, wrong in vertical.
 */
class PointageTest {

    // --- building synthetic attitudes ---

    /** Absolute difference between two azimuths, shortest way. */
    private fun ecart(a: Float, b: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return abs(d)
    }

    private fun produit(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /** Rotation by [deg] about [axe], via Rodrigues' formula. */
    private fun rotation(axe: Vec3, deg: Float): FloatArray {
        val u = axe.normalise()
        val t = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(t); val s = sin(t); val k = 1f - c
        return floatArrayOf(
            c + u.x * u.x * k,        u.x * u.y * k - u.z * s,  u.x * u.z * k + u.y * s,
            u.y * u.x * k + u.z * s,  c + u.y * u.y * k,        u.y * u.z * k - u.x * s,
            u.z * u.x * k - u.y * s,  u.z * u.y * k + u.x * s,  c + u.z * u.z * k
        )
    }

    /** Ground-frame direction for an azimuth and elevation. */
    private fun direction(az: Float, el: Float): Vec3 {
        val a = Math.toRadians(az.toDouble()).toFloat()
        val e = Math.toRadians(el.toDouble()).toFloat()
        return Vec3(cos(e) * cos(a), cos(e) * sin(a), -sin(e))
    }

    /** Euler angles the module would report for this rotation. */
    private fun attitude(m: FloatArray): AttitudeWit {
        val tangage = Math.toDegrees(asin((-m[6]).coerceIn(-1f, 1f).toDouble())).toFloat()
        val lacet = Math.toDegrees(atan2(m[3].toDouble(), m[0].toDouble())).toFloat()
        val roulis = Math.toDegrees(atan2(m[7].toDouble(), m[8].toDouble())).toFloat()
        return AttitudeWit(roulis, tangage, lacet)
    }

    /**
     * Attitude of a box whose arrow is along [fleche], aimed at [az]/[el],
     * rotated [pol] degrees about its axis.
     */
    private fun scene(fleche: Vec3, az: Float, el: Float, pol: Float): AttitudeWit {
        val cible = direction(az, el)
        val f = fleche.normalise()
        // Any rotation bringing the arrow onto the target.
        val axe = Vec3(
            f.y * cible.z - f.z * cible.y,
            f.z * cible.x - f.x * cible.z,
            f.x * cible.y - f.y * cible.x
        )
        val cosinus = f.produitScalaire(cible).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(atan2(axe.norme.toDouble(), cosinus.toDouble())).toFloat()
        val amene = if (axe.norme < 1e-5f) {
            if (cosinus > 0f) rotation(Vec3(0f, 0f, 1f), 0f)
            else rotation(Vec3(0f, 0f, 1f), 180f)
        } else rotation(axe, angle)
        // Then polarisation, rotating about the arrow itself.
        return attitude(produit(amene, rotation(f, pol)))
    }

    private fun assertPointage(a: AttitudeWit, fleche: Vec3, az: Float, el: Float, tol: Float = 0.2f) {
        val p = PointageAntenne.pointage(a, fleche)
        var d = abs(p.azimutDeg - az) % 360f
        if (d > 180f) d = 360f - d
        assertTrue("azimut ${p.azimutDeg} attendu $az", d < tol)
        assertEquals(el, p.elevationDeg, tol)
    }

    // --- the field bug, reproduced then fixed ---

    @Test
    fun la_polarisation_ne_deplace_plus_le_pointage() {
        val fleche = Vec3(1f, 0f, 0f)
        for (pol in listOf(0f, 30f, 45f, 60f, 90f, 135f, 180f, -90f)) {
            assertPointage(scene(fleche, 125f, 32f, pol), fleche, 125f, 32f)
        }
    }

    @Test
    fun une_fleche_portee_par_x_ne_souffrait_pas_de_la_polarisation() {
        // Note: rotating about X touches neither yaw nor pitch, since they
        // define where X points. A box mounted this way already worked with the
        // old reading — which is why the bug does not show on every mounting.
        val fleche = Vec3(1f, 0f, 0f)
        val plat = scene(fleche, 125f, 32f, 0f)
        val vertical = scene(fleche, 125f, 32f, 90f)
        assertEquals(32f, plat.tangage, 0.3f)
        assertEquals(32f, vertical.tangage, 0.3f)
    }

    @Test
    fun lancienne_lecture_par_angles_separes_se_defait_hors_de_laxe_x() {
        // The field case: arrow along Y, where elevation learning had picked
        // "roll". Right in horizontal polarisation, wrong once rotated.
        val fleche = Vec3(0f, 1f, 0f)
        val plat = scene(fleche, 125f, 32f, 0f)
        val vertical = scene(fleche, 125f, 32f, 90f)
        // Flat, roll equals elevation, up to sign.
        assertEquals(32f, abs(plat.roulis), 1f)
        // Vertical, it no longer does, and yaw has drifted too.
        assertTrue("le roulis devrait cesser de valoir l'élévation",
            abs(abs(vertical.roulis) - 32f) > 15f)
        assertTrue("le lacet devrait cesser de valoir l'azimut",
            abs(abs(vertical.lacet) - 125f) > 15f)
        // The vector computation is right in both cases.
        assertPointage(plat, fleche, 125f, 32f)
        assertPointage(vertical, fleche, 125f, 32f)
    }

    @Test
    fun la_polarisation_ne_deplace_rien_non_plus_sur_un_boitier_de_travers() {
        val fleche = Vec3(0f, 1f, 0f)
        for (pol in listOf(0f, 45f, 90f, 150f)) {
            assertPointage(scene(fleche, 300f, 10f, pol), fleche, 300f, 10f)
        }
        val couche = Vec3(0f, 0f, -1f)
        for (pol in listOf(0f, 70f, 120f)) {
            assertPointage(scene(couche, 45f, 60f, pol), couche, 45f, 60f)
        }
    }

    // --- agreement with what already worked ---

    @Test
    fun a_plat_sur_laxe_x_on_retrouve_lacet_et_tangage() {
        // Behaviour that worked in horizontal polarisation must be unchanged,
        // or the fix would break as much as it repairs.
        val a = AttitudeWit(roulis = 0f, tangage = 25f, lacet = 140f)
        val p = PointageAntenne.pointage(a, Vec3(1f, 0f, 0f))
        assertEquals(140f, p.azimutDeg, 0.1f)
        assertEquals(25f, p.elevationDeg, 0.1f)
    }

    @Test
    fun lelevation_ne_sort_jamais_du_quart_de_tour() {
        for (el in listOf(-90f, -45f, 0f, 45f, 90f)) {
            val p = PointageAntenne.pointage(scene(Vec3(1f, 0f, 0f), 0f, el, 33f), Vec3(1f, 0f, 0f))
            assertTrue(p.elevationDeg in -90.5f..90.5f)
        }
    }

    @Test
    fun lazimut_reste_dans_le_tour() {
        for (az in listOf(0f, 1f, 179f, 181f, 359f)) {
            val p = PointageAntenne.pointage(scene(Vec3(1f, 0f, 0f), az, 5f, 20f), Vec3(1f, 0f, 0f))
            assertTrue(p.azimutDeg >= 0f && p.azimutDeg < 360f)
        }
    }

    @Test
    fun le_calage_dazimut_agit_comme_avant() {
        val a = AttitudeWit(0f, 0f, 100f)
        assertEquals(110f, PointageAntenne.pointage(a, Vec3(1f, 0f, 0f), calageAzimut = 10f).azimutDeg, 0.1f)
        assertEquals(350f, PointageAntenne.pointage(a, Vec3(1f, 0f, 0f), calageAzimut = -110f).azimutDeg, 0.1f)
    }

    // --- learning the axis ---

    @Test
    fun deux_polarisations_designent_laxe_de_la_fleche() {
        for (vrai in listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))) {
            val a1 = scene(vrai, 80f, 15f, 0f)
            val a2 = scene(vrai, 80f, 15f, 90f)
            val appris = PointageAntenne.apprendFleche(a1, a2)
            assertNotNull(appris)
            // Up to sign: both directions share the rotation axis.
            assertEquals(1f, abs(appris!!.normalise().produitScalaire(vrai)), 0.02f)
        }
    }

    @Test
    fun laxe_sapprend_aussi_sur_un_montage_de_biais() {
        val vrai = Vec3(0.6f, 0.8f, 0f)
        val appris = PointageAntenne.apprendFleche(
            scene(vrai, 200f, 40f, 10f), scene(vrai, 200f, 40f, 100f))!!
        assertEquals(1f, abs(appris.normalise().produitScalaire(vrai.normalise())), 0.02f)
    }

    @Test
    fun un_quart_de_tour_trop_timide_ne_decide_rien() {
        val vrai = Vec3(1f, 0f, 0f)
        assertNull(PointageAntenne.apprendFleche(
            scene(vrai, 80f, 15f, 0f), scene(vrai, 80f, 15f, 5f)))
    }

    @Test
    fun un_presque_demi_tour_ne_decide_rien_non_plus() {
        // Same antisymmetric magnitude as a tiny rotation: the axis is equally
        // ill-conditioned, and accepting it would give a well-presented wrong
        // pointing.
        val vrai = Vec3(1f, 0f, 0f)
        assertNull(PointageAntenne.apprendFleche(
            scene(vrai, 80f, 15f, 0f), scene(vrai, 80f, 15f, 178f)))
    }

    @Test
    fun le_sens_se_tranche_en_levant_lantenne() {
        val vrai = Vec3(1f, 0f, 0f)
        val appris = PointageAntenne.apprendFleche(
            scene(vrai, 80f, 0f, 0f), scene(vrai, 80f, 0f, 90f))!!
        val levee = scene(vrai, 80f, 40f, 25f)
        val oriente = PointageAntenne.resoutSens(appris, levee)
        assertNotNull(oriente)
        assertEquals(1f, oriente!!.produitScalaire(vrai), 0.02f)
        // Same result if learning had given the opposite sign.
        assertEquals(1f, PointageAntenne.resoutSens(-appris, levee)!!.produitScalaire(vrai), 0.02f)
    }

    @Test
    fun une_antenne_a_lhorizontale_ne_dit_rien_de_son_sens() {
        val vrai = Vec3(1f, 0f, 0f)
        val appris = PointageAntenne.apprendFleche(
            scene(vrai, 80f, 0f, 0f), scene(vrai, 80f, 0f, 90f))!!
        assertNull(PointageAntenne.resoutSens(appris, scene(vrai, 80f, 1f, 0f)))
    }

    // --- storage ---

    @Test
    fun une_fleche_presque_alignee_est_ramenee_sur_laxe() {
        val v = PointageAntenne.rangeSurAxe(Vec3(0.999f, 0.03f, -0.02f))
        assertEquals(1f, v.x, 0.001f)
        assertEquals(0f, v.y, 0.001f)
    }

    @Test
    fun un_montage_vraiment_de_biais_nest_pas_redresse_de_force() {
        val v = PointageAntenne.rangeSurAxe(Vec3(0.6f, 0.8f, 0f))
        assertEquals(0.6f, v.x, 0.01f)
        assertEquals(0.8f, v.y, 0.01f)
    }

    @Test
    fun une_fleche_survit_a_lecriture_et_a_la_relecture() {
        val v = Vec3(0.6f, -0.8f, 0f)
        val relu = PointageAntenne.depuisTexte(PointageAntenne.enTexte(v))!!
        assertEquals(1f, relu.produitScalaire(v.normalise()), 0.001f)
    }

    @Test
    fun une_fleche_illisible_vaut_pas_encore_apprise() {
        assertNull(PointageAntenne.depuisTexte(null))
        assertNull(PointageAntenne.depuisTexte(""))
        assertNull(PointageAntenne.depuisTexte("1,2"))
        assertNull(PointageAntenne.depuisTexte("0,0,0"))
        assertNull(PointageAntenne.depuisTexte("a,b,c"))
    }

    @Test
    fun la_trame_du_module_alimente_bien_le_pointage() {
        // End to end: valid bytes through to pointing.
        val o = ByteArray(BoussoleWit.LONGUEUR)
        o[0] = 0x55; o[1] = 0x61.toByte()
        fun pose(i: Int, deg: Float) {
            val brut = Math.round(deg / 180f * 32768f)
            o[i] = (brut and 0xFF).toByte(); o[i + 1] = ((brut shr 8) and 0xFF).toByte()
        }
        pose(14, 0f); pose(16, 30f); pose(18, 90f)
        val a = BoussoleWit.litAttitude(o)!!
        val p = PointageAntenne.pointage(a, Vec3(1f, 0f, 0f))
        assertEquals(90f, p.azimutDeg, 0.2f)
        assertEquals(30f, p.elevationDeg, 0.2f)
    }

    // ---- calibration on a known azimuth ----

    /**
     * Fix for the field bug: north and south swapped.
     *
     * Two polarisation poses give a line, not a direction, and the guessed
     * sense can be the wrong end. A known azimuth leaves no choice.
     */
    @Test
    fun un_azimut_connu_donne_la_fleche_sans_ambiguite() {
        for (vise in listOf(0f, 90f, 180f, 270f, 47f)) {
            val pose = AttitudeWit(roulis = 23f, tangage = 4f, lacet = vise)
            val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, vise)!!
            val p = PointageAntenne.pointage(pose, f)!!
            assertTrue("azimut $vise : ${p.azimutDeg}",
                ecart(vise, p.azimutDeg) < 0.5f)
            assertEquals(0f, p.elevationDeg, 0.5f)
        }
    }

    @Test
    fun letalonnage_au_nord_ne_confond_pas_le_sud() {
        // Box mounted backwards: yaw says 180 while the antenna points north.
        // The case that trapped the old method.
        val pose = AttitudeWit(0f, 0f, 180f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 0f)!!
        val p = PointageAntenne.pointage(pose, f)!!
        assertTrue("devrait pointer le nord, pas le sud : ${p.azimutDeg}",
            ecart(0f, p.azimutDeg) < 0.5f)
    }

    @Test
    fun letalonnage_reste_juste_quand_on_tourne_ensuite() {
        val pose = AttitudeWit(11f, 0f, 30f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 270f)
        // Turn by 60°: azimuth must follow by as much.
        val apres = AttitudeWit(11f, 0f, 90f)
        val p = PointageAntenne.pointage(apres, f!!)!!
        assertTrue("attendu 330, obtenu ${p.azimutDeg}",
            ecart(330f, p.azimutDeg) < 0.5f)
    }

    @Test
    fun letalonnage_survit_a_la_polarisation() {
        val pose = AttitudeWit(0f, 0f, 0f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 0f)!!
        val depart = PointageAntenne.pointage(pose, f)!!
        val axe = direction(depart.azimutDeg, depart.elevationDeg)
        for (angle in 0..350 step 30) {
            val vu = attitude(produit(rotation(axe, angle.toFloat()),
                PointageAntenne.matrice(pose)))
            val p = PointageAntenne.pointage(vu, f)!!
            assertTrue("à $angle° : ${p.azimutDeg}",
                ecart(depart.azimutDeg, p.azimutDeg) < 0.5f)
        }
    }

    @Test
    fun retourner_la_fleche_echange_le_nord_et_le_sud() {
        val pose = AttitudeWit(0f, 0f, 0f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 0f)!!
        val envers = PointageAntenne.retourne(f)
        val p = PointageAntenne.pointage(pose, envers)!!
        assertTrue("attendu 180, obtenu ${p.azimutDeg}",
            ecart(180f, p.azimutDeg) < 0.5f)
        // Two flips return to the start.
        val p2 = PointageAntenne.pointage(pose, PointageAntenne.retourne(envers))!!
        assertTrue(ecart(0f, p2.azimutDeg) < 0.5f)
    }

    @Test
    fun une_antenne_levee_setalonne_aussi() {
        val pose = AttitudeWit(0f, 35f, 120f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 120f, 35f)!!
        val p = PointageAntenne.pointage(pose, f)!!
        assertTrue(ecart(120f, p.azimutDeg) < 0.5f)
        assertEquals(35f, p.elevationDeg, 0.5f)
    }

    // ---- heading calibration from two sightings ----

    /** What the dial will show once calibration is applied. */
    private fun affiche(brut: Float, c: PointageAntenne.CalageCap): Float {
        val signe = if (c.inverse) -brut else brut
        var a = (signe + c.calageDeg) % 360f
        if (a < 0f) a += 360f
        return a
    }

    @Test
    fun un_module_bien_oriente_ne_demande_aucun_calage() {
        // North reads 0, west reads 270: nothing to correct.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 270f)!!
        assertFalse(c.inverse)
        assertEquals(0f, c.calageDeg, 0.5f)
        assertEquals(0f, affiche(0f, c), 0.5f)
        assertEquals(270f, affiche(270f, c), 0.5f)
    }

    @Test
    fun le_nord_et_le_sud_inverses_se_rattrapent() {
        // Field case: aiming north shows south. Pure 180° offset, same sense.
        val c = PointageAntenne.calageCapDepuisNordOuest(180f, 90f)!!
        assertEquals(0f, affiche(180f, c), 0.5f)    // north is north again
        assertEquals(270f, affiche(90f, c), 0.5f)   // and west is west
    }

    @Test
    fun un_sens_de_rotation_retourne_se_detecte() {
        // North reads 0, west reads 90: the module counts backwards.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 90f)!!
        assertTrue(c.inverse)
        assertEquals(0f, affiche(0f, c), 0.5f)
        assertEquals(270f, affiche(90f, c), 0.5f)
    }

    @Test
    fun un_decalage_quelconque_se_rattrape_aussi() {
        // Box mounted askew: north reads 37, west reads 307.
        val c = PointageAntenne.calageCapDepuisNordOuest(37f, 307f)!!
        assertFalse(c.inverse)
        assertEquals(0f, affiche(37f, c), 0.5f)
        assertEquals(270f, affiche(307f, c), 0.5f)
    }

    @Test
    fun le_calage_reste_lisible() {
        // A small offset must show small: −3, not 357.
        val c = PointageAntenne.calageCapDepuisNordOuest(3f, 273f)!!
        assertTrue("calage illisible : ${c.calageDeg}",
            c.calageDeg > -180f && c.calageDeg <= 180f)
        assertEquals(-3f, c.calageDeg, 0.5f)
    }

    @Test
    fun deux_releves_ambigus_nécrivent_rien() {
        // Same direction sighted twice: nothing to learn.
        assertNull(PointageAntenne.calageCapDepuisNordOuest(50f, 50f))
        // An opposite sighting does not tell the sense either.
        assertNull(PointageAntenne.calageCapDepuisNordOuest(0f, 180f))
    }

    @Test
    fun un_releve_approximatif_passe_quand_meme() {
        // West within 15°: this is not a theodolite.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 255f)
        assertNotNull(c)
        assertFalse(c!!.inverse)
    }

    // ---- lessons from the field ----

    /**
     * **The vector path must know no sense inversion.** The old "reverse sense"
     * flag was still applied to the vector result; flipping an azimuth is a
     * **reflection**, and polarisation invariance only holds for rotations.
     * Cardinals came out reversed and shifted in vertical polarisation.
     */
    @Test
    fun un_cardinal_appris_se_relit_juste() {
        val pose = AttitudeWit(roulis = 14f, tangage = -9f, lacet = 200f)
        for (calage in listOf(0f, 37f, -120f)) {
            for (vise in listOf(0f, 90f, 180f, 270f)) {
                val f = PointageAntenne.apprendFlecheDepuisAzimut(
                    pose, vise, calageAzimut = calage)
                assertNotNull("apprentissage impossible", f)
                val relu = PointageAntenne.pointage(pose, f!!, calageAzimut = calage)
                assertEquals("visé $vise avec un calage de $calage",
                    vise, relu.azimutDeg, 0.3f)
            }
        }
    }

    @Test
    fun un_cardinal_appris_resiste_a_la_polarisation() {
        val pose = AttitudeWit(roulis = 0f, tangage = 0f, lacet = 200f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 0f)!!
        val depart = PointageAntenne.pointage(pose, f)
        assertEquals(0f, depart.azimutDeg, 0.3f)
        for (pol in 0..330 step 30) {
            val p = PointageAntenne.pointage(
                scene(f, depart.azimutDeg, depart.elevationDeg, pol.toFloat()), f)
            assertEquals("pol=$pol", depart.azimutDeg, p.azimutDeg, 0.5f)
            assertEquals("élévation à pol=$pol",
                depart.elevationDeg, p.elevationDeg, 0.5f)
        }
    }

    @Test
    fun les_quatre_cardinaux_sortent_dans_le_bon_ordre() {
        // N, E, S, W: if two swap, a reflection has crept in somewhere.
        val pose = AttitudeWit(roulis = 5f, tangage = 3f, lacet = 77f)
        val f = PointageAntenne.apprendFlecheDepuisAzimut(pose, 0f)!!
        val lus = listOf(0f, 90f, 180f, 270f).map { az ->
            PointageAntenne.pointage(scene(f, az, 0f, 0f), f).azimutDeg
        }
        listOf(0f, 90f, 180f, 270f).forEachIndexed { i, attendu ->
            var d = kotlin.math.abs(lus[i] - attendu) % 360f
            if (d > 180f) d = 360f - d
            assertTrue("cardinal $attendu lu ${lus[i]}", d < 0.5f)
        }
    }

    // ---- module handedness, measured not assumed ----

    /**
     * Simulates a module reporting `-yaw`: east and west swap, north and south
     * stay right. (Trap: building scenes in one convention and reading them in
     * the other always fails, and once wrongly condemned the correction.)
     *
     * Reading in east-north-up frame undoes it: swapping the first two axes is
     * a half-turn about the horizontal diagonal, which inverts a yaw rotation.
     * Correcting at read time keeps polarisation invariance.
     */
    private fun vuParUnModuleOppose(a: AttitudeWit) =
        AttitudeWit(a.roulis, a.tangage, -a.lacet)

    @Test
    fun un_module_oppose_se_relit_juste_une_fois_le_repere_mesure() {
        val fleche = Vec3(0.2f, 0.9f, -0.3f).normalise()
        for (az in listOf(0f, 90f, 180f, 270f)) {
            val vraie = scene(fleche, az, 0f, 0f)
            val vue = vuParUnModuleOppose(vraie)
            val lu = PointageAntenne.pointage(
                vue, fleche, convention = PointageAntenne.Convention.LACET_OPPOSE).azimutDeg
            var d = kotlin.math.abs(lu - az) % 360f
            if (d > 180f) d = 360f - d
            assertTrue("azimut $az lu $lu", d < 0.5f)
        }
    }

    @Test
    fun deux_visees_reconnaissent_un_module_direct() {
        val fleche = Vec3(1f, 0.1f, 0f).normalise()
        val c = PointageAntenne.calibreDepuisDeuxVisees(
            scene(fleche, 0f, 0f, 0f), 0f,
            scene(fleche, 90f, 0f, 0f), 90f)
        assertNotNull("aucune hypothèse retenue", c)
        assertEquals("module direct pris pour opposé",
            PointageAntenne.Convention.DIRECTE, c!!.convention)
    }

    @Test
    fun deux_visees_demasquent_un_module_au_repere_oppose() {
        // Field case: north and south right, east and west swapped. A single
        // sighting would never see it.
        val fleche = Vec3(1f, 0.1f, 0f).normalise()
        val c = PointageAntenne.calibreDepuisDeuxVisees(
            vuParUnModuleOppose(scene(fleche, 0f, 0f, 0f)), 0f,
            vuParUnModuleOppose(scene(fleche, 90f, 0f, 0f)), 90f)
        assertNotNull("le sens inverse n'a pas été détecté", c)
        // Which of the four is chosen does not matter, as long as it is not
        // the direct one and it **reads back right** — required by the next
        // test on all four cardinals.
        assertNotEquals("module opposé pris pour direct",
            PointageAntenne.Convention.DIRECTE, c!!.convention)
    }

    @Test
    fun la_calibration_mesuree_tient_sur_les_quatre_cardinaux() {
        val fleche = Vec3(0.3f, 0.9f, 0.2f).normalise()
        for (inverse in listOf(false, true)) {
            fun vu(a: AttitudeWit) = if (inverse) vuParUnModuleOppose(a) else a
            val c = PointageAntenne.calibreDepuisDeuxVisees(
                vu(scene(fleche, 0f, 0f, 0f)), 0f,
                vu(scene(fleche, 90f, 0f, 0f)), 90f)!!
            if (!inverse) assertEquals("module direct mal mesuré",
                PointageAntenne.Convention.DIRECTE, c.convention)
            for (az in listOf(0f, 90f, 180f, 270f)) {
                val lu = PointageAntenne.pointage(
                    vu(scene(fleche, az, 0f, 0f)), c.fleche,
                    convention = c.convention).azimutDeg
                var d = kotlin.math.abs(lu - az) % 360f
                if (d > 180f) d = 360f - d
                assertTrue("inverse=$inverse, azimut $az lu $lu", d < 1f)
            }
        }
    }

    @Test
    fun la_calibration_mesuree_resiste_a_la_polarisation() {
        // What matters: once handedness is measured, rotating the antenna about
        // its axis changes nothing.
        val fleche = Vec3(0.3f, 0.9f, 0.2f).normalise()
        fun vu(a: AttitudeWit) = vuParUnModuleOppose(a)
        val c = PointageAntenne.calibreDepuisDeuxVisees(
            vu(scene(fleche, 0f, 0f, 0f)), 0f,
            vu(scene(fleche, 90f, 0f, 0f)), 90f)!!
        val depart = PointageAntenne.pointage(
            vu(scene(fleche, 120f, 25f, 0f)), c.fleche, convention = c.convention)
        for (pol in 0..330 step 30) {
            val p = PointageAntenne.pointage(
                vu(scene(fleche, 120f, 25f, pol.toFloat())), c.fleche,
                convention = c.convention)
            assertEquals("azimut à pol=$pol", depart.azimutDeg, p.azimutDeg, 1f)
            assertEquals("élévation à pol=$pol", depart.elevationDeg, p.elevationDeg, 1f)
        }
    }

    @Test
    fun deux_visees_trop_proches_ne_calibrent_rien() {
        val fleche = Vec3(1f, 0f, 0f)
        assertNull(PointageAntenne.calibreDepuisDeuxVisees(
            scene(fleche, 0f, 0f, 0f), 0f,
            scene(fleche, 20f, 0f, 0f), 20f))
    }

    @Test
    fun des_releves_incoherents_sont_refuses() {
        // Operator pressed the wrong button: no hypothesis fits.
        val fleche = Vec3(1f, 0f, 0f)
        assertNull(PointageAntenne.calibreDepuisDeuxVisees(
            scene(fleche, 0f, 0f, 0f), 0f,
            scene(fleche, 200f, 0f, 0f), 90f))
    }
}
