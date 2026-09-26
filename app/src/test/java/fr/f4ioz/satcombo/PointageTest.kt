/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le banc du pointage.
 *
 * Il tient une promesse précise, celle qui manquait à la première version :
 * **tourner l'antenne sur son axe ne change pas où elle pointe**. C'est le
 * défaut relevé au terrain par F4IOZ — azimut et élévation justes en
 * polarisation horizontale, faux dès qu'on passe en verticale — et il se
 * reproduit ici à la table.
 */
class PointageTest {

    // --- de quoi fabriquer des attitudes de synthèse ---

    /** L'écart absolu entre deux azimuts, par le plus court chemin. */
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

    /** Rotation d'angle [deg] autour de [axe], par la formule de Rodrigues. */
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

    /** La direction au sol correspondant à un azimut et une élévation. */
    private fun direction(az: Float, el: Float): Vec3 {
        val a = Math.toRadians(az.toDouble()).toFloat()
        val e = Math.toRadians(el.toDouble()).toFloat()
        return Vec3(cos(e) * cos(a), cos(e) * sin(a), -sin(e))
    }

    /** Les angles d'Euler que le module annoncerait pour cette rotation. */
    private fun attitude(m: FloatArray): AttitudeWit {
        val tangage = Math.toDegrees(asin((-m[6]).coerceIn(-1f, 1f).toDouble())).toFloat()
        val lacet = Math.toDegrees(atan2(m[3].toDouble(), m[0].toDouble())).toFloat()
        val roulis = Math.toDegrees(atan2(m[7].toDouble(), m[8].toDouble())).toFloat()
        return AttitudeWit(roulis, tangage, lacet)
    }

    /**
     * L'attitude d'un boîtier dont la flèche est portée par [fleche], visant
     * [az]/[el], tourné de [pol] degrés sur son axe.
     */
    private fun scene(fleche: Vec3, az: Float, el: Float, pol: Float): AttitudeWit {
        val cible = direction(az, el)
        val f = fleche.normalise()
        // Une rotation quelconque qui amène la flèche sur la cible.
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
        // Puis la polarisation, qui tourne autour de la flèche elle-même.
        return attitude(produit(amene, rotation(f, pol)))
    }

    private fun assertPointage(a: AttitudeWit, fleche: Vec3, az: Float, el: Float, tol: Float = 0.2f) {
        val p = PointageAntenne.pointage(a, fleche)
        var d = abs(p.azimutDeg - az) % 360f
        if (d > 180f) d = 360f - d
        assertTrue("azimut ${p.azimutDeg} attendu $az", d < tol)
        assertEquals(el, p.elevationDeg, tol)
    }

    // --- le défaut du terrain, reproduit puis corrigé ---

    @Test
    fun la_polarisation_ne_deplace_plus_le_pointage() {
        val fleche = Vec3(1f, 0f, 0f)
        for (pol in listOf(0f, 30f, 45f, 60f, 90f, 135f, 180f, -90f)) {
            assertPointage(scene(fleche, 125f, 32f, pol), fleche, 125f, 32f)
        }
    }

    @Test
    fun une_fleche_portee_par_x_ne_souffrait_pas_de_la_polarisation() {
        // À garder en tête : tourner autour de X ne touche ni le lacet ni le
        // tangage, parce que ce sont eux qui définissent où pointe X. Un
        // boîtier monté ainsi marchait déjà avec l'ancienne lecture — ce qui
        // explique que le défaut n'apparaisse pas sur tous les montages.
        val fleche = Vec3(1f, 0f, 0f)
        val plat = scene(fleche, 125f, 32f, 0f)
        val vertical = scene(fleche, 125f, 32f, 90f)
        assertEquals(32f, plat.tangage, 0.3f)
        assertEquals(32f, vertical.tangage, 0.3f)
    }

    @Test
    fun lancienne_lecture_par_angles_separes_se_defait_hors_de_laxe_x() {
        // Le cas relevé au terrain par F4IOZ : flèche portée par Y — celui pour
        // lequel l'apprentissage d'élévation avait retenu « roulis ». Juste en
        // polarisation horizontale, faux dès qu'on tourne l'antenne.
        val fleche = Vec3(0f, 1f, 0f)
        val plat = scene(fleche, 125f, 32f, 0f)
        val vertical = scene(fleche, 125f, 32f, 90f)
        // À plat, le roulis vaut bien l'élévation, au signe près.
        assertEquals(32f, abs(plat.roulis), 1f)
        // En verticale, il ne la vaut plus, et le lacet a lui aussi dérivé.
        assertTrue("le roulis devrait cesser de valoir l'élévation",
            abs(abs(vertical.roulis) - 32f) > 15f)
        assertTrue("le lacet devrait cesser de valoir l'azimut",
            abs(abs(vertical.lacet) - 125f) > 15f)
        // Et le calcul vectoriel, lui, ne bronche ni dans un cas ni dans l'autre.
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

    // --- l'accord avec ce qui marchait déjà ---

    @Test
    fun a_plat_sur_laxe_x_on_retrouve_lacet_et_tangage() {
        // Le comportement qui fonctionnait en polarisation horizontale doit
        // sortir inchangé du nouveau calcul, sinon la correction en casserait
        // autant qu'elle en répare.
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

    // --- l'apprentissage de l'axe ---

    @Test
    fun deux_polarisations_designent_laxe_de_la_fleche() {
        for (vrai in listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))) {
            val a1 = scene(vrai, 80f, 15f, 0f)
            val a2 = scene(vrai, 80f, 15f, 90f)
            val appris = PointageAntenne.apprendFleche(a1, a2)
            assertNotNull(appris)
            // Au signe près : les deux sens ont le même axe de rotation.
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
        // Même amplitude antisymétrique qu'une rotation minuscule : l'axe y est
        // aussi mal posé, et le taire donnerait un pointage faux bien présenté.
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
        // Et il tranche pareil si l'apprentissage avait donné l'inverse.
        assertEquals(1f, PointageAntenne.resoutSens(-appris, levee)!!.produitScalaire(vrai), 0.02f)
    }

    @Test
    fun une_antenne_a_lhorizontale_ne_dit_rien_de_son_sens() {
        val vrai = Vec3(1f, 0f, 0f)
        val appris = PointageAntenne.apprendFleche(
            scene(vrai, 80f, 0f, 0f), scene(vrai, 80f, 0f, 90f))!!
        assertNull(PointageAntenne.resoutSens(appris, scene(vrai, 80f, 1f, 0f)))
    }

    // --- le rangement ---

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
        // Bout à bout : des octets conformes jusqu'au pointage.
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

    // ---- l'étalonnage sur un azimut connu ----

    /**
     * Le remède au défaut du terrain : nord et sud inversés.
     *
     * Deux poses de polarisation donnent une droite, pas une direction, et le
     * sens deviné peut se tromper de bout. Un azimut connu ne laisse aucun
     * choix à faire.
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
        // Le boîtier est vissé à l'envers : le lacet dit 180 alors que
        // l'antenne pointe le nord. C'est le cas qui piégeait l'ancienne
        // méthode.
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
        // On tourne de 60° : l'azimut doit suivre d'autant.
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
        // Et deux retournements ramènent au point de départ.
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

    // ---- le calage du cap par deux visées ----

    /** Ce que le cadran affichera, une fois le calage posé. */
    private fun affiche(brut: Float, c: PointageAntenne.CalageCap): Float {
        val signe = if (c.inverse) -brut else brut
        var a = (signe + c.calageDeg) % 360f
        if (a < 0f) a += 360f
        return a
    }

    @Test
    fun un_module_bien_oriente_ne_demande_aucun_calage() {
        // Nord lu 0, ouest lu 270 : rien à corriger.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 270f)!!
        assertFalse(c.inverse)
        assertEquals(0f, c.calageDeg, 0.5f)
        assertEquals(0f, affiche(0f, c), 0.5f)
        assertEquals(270f, affiche(270f, c), 0.5f)
    }

    @Test
    fun le_nord_et_le_sud_inverses_se_rattrapent() {
        // Le cas d'Olivier : viser le nord affiche le sud.
        // Décalage pur de 180°, sens conservé.
        val c = PointageAntenne.calageCapDepuisNordOuest(180f, 90f)!!
        assertEquals(0f, affiche(180f, c), 0.5f)    // le nord redevient le nord
        assertEquals(270f, affiche(90f, c), 0.5f)   // et l'ouest, l'ouest
    }

    @Test
    fun un_sens_de_rotation_retourne_se_detecte() {
        // Nord lu 0, ouest lu 90 : le module compte à l'envers.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 90f)!!
        assertTrue(c.inverse)
        assertEquals(0f, affiche(0f, c), 0.5f)
        assertEquals(270f, affiche(90f, c), 0.5f)
    }

    @Test
    fun un_decalage_quelconque_se_rattrape_aussi() {
        // Boîtier vissé de travers : nord lu 37, ouest lu 307.
        val c = PointageAntenne.calageCapDepuisNordOuest(37f, 307f)!!
        assertFalse(c.inverse)
        assertEquals(0f, affiche(37f, c), 0.5f)
        assertEquals(270f, affiche(307f, c), 0.5f)
    }

    @Test
    fun le_calage_reste_lisible() {
        // Un petit écart doit s'afficher petit : −3, pas 357.
        val c = PointageAntenne.calageCapDepuisNordOuest(3f, 273f)!!
        assertTrue("calage illisible : ${c.calageDeg}",
            c.calageDeg > -180f && c.calageDeg <= 180f)
        assertEquals(-3f, c.calageDeg, 0.5f)
    }

    @Test
    fun deux_releves_ambigus_nécrivent_rien() {
        // Même direction visée deux fois : rien à en tirer.
        assertNull(PointageAntenne.calageCapDepuisNordOuest(50f, 50f))
        // Et une visée à l'opposé ne dit pas non plus le sens.
        assertNull(PointageAntenne.calageCapDepuisNordOuest(0f, 180f))
    }

    @Test
    fun un_releve_approximatif_passe_quand_meme() {
        // L'ouest à quinze degrés près : on ne vise pas au théodolite.
        val c = PointageAntenne.calageCapDepuisNordOuest(0f, 255f)
        assertNotNull(c)
        assertFalse(c!!.inverse)
    }

    // ---- ce que le terrain a appris ----

    /**
     * **L'essai qui manquait.** Le chemin vectoriel ne doit connaître aucune
     * inversion de sens.
     *
     * Le drapeau « sens inverse » appartenait à l'ancienne méthode scalaire,
     * mais il continuait de s'appliquer à la sortie du calcul vectoriel :
     * retourner un azimut est une **symétrie**, pas une rotation, et
     * l'invariance à la polarisation n'existe que pour les rotations. Olivier
     * voyait ses points cardinaux à l'envers, puis son calage se décaler dès
     * qu'il passait en polarisation verticale.
     *
     * On vérifie ici qu'un cardinal appris se relit juste, et qu'il résiste
     * ensuite à un tour complet de polarisation — les deux symptômes du
     * terrain, réunis.
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
        // Nord, est, sud, ouest : si deux d'entre eux se croisent, c'est qu'une
        // symétrie s'est glissée quelque part.
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

    // ---- le sens du module, mesuré et non supposé ----

    /**
     * Simule un module qui compte le lacet à l'envers.
     *
     * **C'est ici que je m'étais trompé la première fois.** Mon banc précédent
     * fabriquait ses scènes dans la convention directe et les relisait dans la
     * convention inverse : forcément faux, et j'en avais conclu à tort que la
     * correction elle-même était mauvaise, avant de la retirer.
     *
     * Un module au repère opposé, c'est un module qui rapporte `-lacet` là où
     * la réalité dit `lacet`. Rien d'autre ne change — et c'est bien assez pour
     * échanger l'est et l'ouest en laissant le nord et le sud justes.
     *
     * Lire dans le repère est-nord-haut revient exactement à cela : l'échange
     * des deux premiers axes est une rotation d'un demi-tour autour de la
     * diagonale horizontale, et conjuguer une rotation de lacet par elle rend
     * son inverse. D'où le choix de corriger à la lecture plutôt qu'en
     * retouchant le lacet — l'invariance à la polarisation y survit.
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
        // Le cas d'Olivier : nord et sud tombent juste, est et ouest sont
        // échangés. Une seule visée ne l'aurait jamais vu.
        val fleche = Vec3(1f, 0.1f, 0f).normalise()
        val c = PointageAntenne.calibreDepuisDeuxVisees(
            vuParUnModuleOppose(scene(fleche, 0f, 0f, 0f)), 0f,
            vuParUnModuleOppose(scene(fleche, 90f, 0f, 0f)), 90f)
        assertNotNull("le sens inverse n'a pas été détecté", c)
        // Peu importe laquelle des quatre est retenue : ce qui compte est
        // qu'elle ne soit pas la directe, et surtout qu'elle **relise juste**.
        // C'est l'essai suivant qui l'exige sur les quatre cardinaux.
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
        // La propriété qui compte : une fois le sens mesuré, tourner l'antenne
        // sur son axe ne doit plus rien changer.
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
        // L'opérateur s'est trompé de bouton : aucune hypothèse ne colle.
        val fleche = Vec3(1f, 0f, 0f)
        assertNull(PointageAntenne.calibreDepuisDeuxVisees(
            scene(fleche, 0f, 0f, 0f), 0f,
            scene(fleche, 200f, 0f, 0f), 90f))
    }
}
