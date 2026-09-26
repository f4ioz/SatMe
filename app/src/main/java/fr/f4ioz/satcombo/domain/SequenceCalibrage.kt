/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The calibration sequence: a series of poses to take, and the raw reading it
 * yields.
 *
 * **Why a sequence rather than one more sighting.** Six versions tried to
 * guess the module convention from one or two poses. Each guess held when
 * level and failed elsewhere: a single pose constrains only part of the
 * rotation. Two flat sightings say nothing about pitch; none says which way
 * roll counts.
 *
 * Nothing is guessed any more: **take every pose**, record the three raw
 * angles each time, and the table settles the convention — which axis carries
 * what, in which direction, where it saturates, and in which order rotations
 * compose.
 *
 * The reading is exportable on purpose: nobody analyses these triplets in
 * their head, and copying them by hand would corrupt them.
 */
object SequenceCalibrage {

    /**
     * A step: what the operator is asked to do, and why. [aide] says what the
     * step measures: an operator who understands the goal of a gesture does
     * it better, and notices when something is off.
     */
    data class Etape(val cle: String, val titre: String, val aide: String)

    /**
     * The poses.
     *
     * **All use the same edge of the box** — the lesson of the first reading:
     * flat poses referred to one edge, raised poses to another. Each set was
     * self-consistent and they described two axes 107° apart. No convention
     * could reconcile them, and elevation topped out at 53° instead of 90°.
     *
     * The four flat poses establish the arrow: azimuth known, elevation zero,
     * so each determines it exactly. The rest are only **checks**: the angle
     * is not known to the degree, but we know what must stay constant.
     */
    val ETAPES: List<Etape> = listOf(
        Etape("plat_n", "1 · À plat, arête vers le NORD",
            "Boîtier à plat, logo vers le haut. L'arête marquée vise le nord."),
        Etape("plat_e", "2 · À plat, arête vers l'EST",
            "Un quart de tour horaire, toujours à plat."),
        Etape("plat_s", "3 · À plat, arête vers le SUD",
            "Encore un quart de tour horaire."),
        Etape("plat_o", "4 · À plat, arête vers l'OUEST",
            "Encore un quart de tour horaire."),
        Etape("pol_h", "5 · Vers le NORD, roulé d'un quart HORAIRE",
            "L'arête vise toujours le nord à l'horizontale. Fais rouler le " +
                "boîtier d'un quart de tour AUTOUR de cette arête, dans le sens " +
                "horaire vu de derrière. C'est le geste de la polarisation."),
        Etape("pol_a", "6 · Vers le NORD, roulé d'un quart ANTIHORAIRE",
            "Le même geste dans l'autre sens, arête toujours vers le nord."),
        Etape("leve_bas", "7 · Arête vers le NORD, levée d'un tiers",
            "À plat de nouveau, puis lève l'arête d'environ trente degrés. " +
                "L'angle exact n'a pas d'importance."),
        Etape("leve_haut", "8 · Arête vers le NORD, levée aux deux tiers",
            "Continue de lever, vers soixante degrés."),
        Etape("leve_est", "9 · Arête vers l'EST, levée aux deux tiers",
            "Tourne-toi vers l'est, arête à l'est, et lève-la comme tu viens " +
                "de le faire au nord. **C'est la pose qui départage** : une " +
                "inclinaison qui ne suivrait pas le cap se démasque ici."),
        Etape("vertical", "10 · Arête à la VERTICALE",
            "L'arête marquée pointe droit vers le ciel. C'est la pose qui doit " +
                "donner quatre-vingt-dix degrés d'élévation.")
    )

    /** The four flat poses, which determine the arrow. */
    val AZIMUTS_A_PLAT = mapOf("plat_n" to 0f, "plat_e" to 90f, "plat_s" to 180f,
        "plat_o" to 270f)

    /** The check poses. */
    val CONTROLES = listOf("pol_h", "pol_a", "leve_bas", "leve_haut", "leve_est",
        "vertical")

    /** A reading: the step and the module's three raw angles. */
    data class Releve(
        val cle: String,
        val roulis: Float,
        val tangage: Float,
        val lacet: Float
    )

    /**
     * One line per pose. Text, not binary: readable by eye, pasteable into a
     * message. Semicolon separator, two decimals — more would record sensor
     * noise.
     */
    fun encode(releves: List<Releve>): String =
        releves.joinToString("\n") {
            "%s;%.2f;%.2f;%.2f".format(it.cle, it.roulis, it.tangage, it.lacet)
        }

    /**
     * Reads back what [encode] wrote. Damaged lines are skipped rather than
     * failing the whole read: a partial reading beats nothing, and the
     * operator sees at once which pose is missing.
     */
    fun decode(texte: String): List<Releve> =
        texte.lineSequence().mapNotNull { ligne ->
            val p = ligne.split(';')
            if (p.size != 4) return@mapNotNull null
            val r = p[1].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            val t = p[2].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            val l = p[3].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            if (ETAPES.none { e -> e.cle == p[0] }) return@mapNotNull null
            Releve(p[0], r, t, l)
        }.toList()

    /** Next step to do, or `null` when the reading is complete. */
    fun prochaine(releves: List<Releve>): Etape? =
        ETAPES.firstOrNull { e -> releves.none { it.cle == e.cle } }

    fun complete(releves: List<Releve>): Boolean = prochaine(releves) == null

    /**
     * The readable report, the one that gets sent. It carries its own header:
     * a table of numbers without a legend, received three days later, means
     * nothing.
     */
    fun rapport(releves: List<Releve>, module: String, version: String): String {
        val b = StringBuilder()
        b.append("SatMe — relevé de calibrage boussole\n")
        b.append("Module : ").append(module.ifBlank { "inconnu" }).append('\n')
        b.append("Version : ").append(version).append('\n')
        b.append("Angles bruts du module, en degrés.\n")
        b.append("pose;roulis;tangage;lacet\n")
        for (e in ETAPES) {
            val r = releves.firstOrNull { it.cle == e.cle }
            if (r == null) {
                b.append(e.cle).append(";—;—;—        (").append(e.titre).append(")\n")
            } else {
                b.append("%s;%.2f;%.2f;%.2f".format(e.cle, r.roulis, r.tangage, r.lacet))
                b.append("        (").append(e.titre).append(")\n")
            }
        }
        val manque = ETAPES.count { e -> releves.none { it.cle == e.cle } }
        if (manque > 0) b.append("\nRelevé incomplet : ").append(manque)
            .append(" pose(s) manquante(s).\n")
        return b.toString()
    }

    // ---- analysing the reading ----

    /**
     * What the reading establishes, and how far to trust it.
     *
     * [dispersionDeg] is the largest angle between the four arrows derived
     * from the four flat poses. It is **the** confidence measure: if the four
     * gestures used the same edge, they overlap within a few degrees. If they
     * diverge, the edge changed along the way and no maths will fix it.
     */
    data class Analyse(
        val fleche: Vec3?,
        /** Azimuth offset to compensate, in degrees. */
        val calageDeg: Float = 0f,
        val convention: PointageAntenne.ConventionLibre?,
        val dispersionDeg: Float,
        /** The worst error over all checks. This is the verdict. */
        val scoreDeg: Float,
        val controles: List<Controle>
    )

    /** A check: what was expected, what was read, and the verdict. */
    data class Controle(val cle: String, val attendu: String, val lu: String,
                        val bon: Boolean)

    /**
     * Analyses the reading: **searches for the convention** instead of
     * assuming it.
     *
     * The flat poses give the arrow for each candidate convention (azimuth
     * known, elevation zero); their spread eliminates inconsistent ones. The
     * other poses separate the rest: exact angles are unknown, but rolling
     * about the edge changes neither azimuth nor elevation, raising lifts
     * elevation without leaving north, and vertical gives 90°.
     *
     * The convention with the lowest **worst** error wins. Worst, not mean: a
     * convention that passes four checks and fails the fifth is wrong, not
     * "fairly good".
     */
    fun analyse(releves: List<Releve>): Analyse {
        var meilleure: Analyse? = null
        for (conv in PointageAntenne.ConventionLibre.toutes()) {
            val a = evalue(releves, conv) ?: continue
            if (meilleure == null || a.scoreDeg < meilleure!!.scoreDeg) meilleure = a
        }
        return meilleure ?: Analyse(null, 0f, null, 180f, 180f, emptyList())
    }

    private fun evalue(
        releves: List<Releve>,
        conv: PointageAntenne.ConventionLibre
    ): Analyse? {
        val fleches = ArrayList<Vec3>()
        for ((cle, az) in AZIMUTS_A_PLAT) {
            val r = releves.firstOrNull { it.cle == cle } ?: continue
            PointageAntenne.flecheLibre(
                AttitudeWit(r.roulis, r.tangage, r.lacet), az, 0f, conv)
                ?.let { fleches.add(it) }
        }
        if (fleches.size < 2) return null

        var sx = 0f; var sy = 0f; var sz = 0f
        for (f in fleches) { sx += f.x; sy += f.y; sz += f.z }
        val moyenne = Vec3(sx, sy, sz)
        if (moyenne.norme < 1e-5f) return null
        val m0 = moyenne.normalise()

        var dispersion = 0f
        for (f in fleches) {
            val cos = f.produitScalaire(m0).coerceIn(-1f, 1f)
            dispersion = maxOf(dispersion,
                Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat())
        }

        // **Residual magnetic calibration error, measured and cancelled.**
        //
        // The module yaw keeps an origin error after its own calibration (15°
        // on the reading used to write this). Azimuth absorbs it, but it
        // rotates the derived arrow **in the box plane**, so the arrow is no
        // longer perpendicular to the raising axis and elevation is capped: at
        // 92° of roll it gave only 74°.
        //
        // The vertical pose measures it: rotate the arrow in that plane until
        // vertical reads 90°. The resulting azimuth offset is constant, hence
        // compensable — that is `calageDeg`.
        val vert = releves.firstOrNull { it.cle == "vertical" }
        var m = m0
        var correction = 0f
        if (vert != null) {
            val aV = AttitudeWit(vert.roulis, vert.tangage, vert.lacet)
            var meilleurEcart = Float.MAX_VALUE
            var d = -45f
            while (d <= 45f) {
                val essai = tourneDansLePlan(m0, d)
                val el = PointageAntenne.pointageLibre(aV, essai, conv).elevationDeg
                val e = kotlin.math.abs(el - 90f)
                if (e < meilleurEcart) { meilleurEcart = e; correction = d; m = essai }
                d += 0.5f
            }
        }

        // Azimuth offset introduced by the correction, averaged over the four
        // flat poses whose azimuth is known exactly.
        var somme = 0f
        var combien = 0
        for ((cle, az) in AZIMUTS_A_PLAT) {
            val r = releves.firstOrNull { it.cle == cle } ?: continue
            val lu = PointageAntenne.pointageLibre(
                AttitudeWit(r.roulis, r.tangage, r.lacet), m, conv).azimutDeg
            var e = (lu - az) % 360f
            if (e > 180f) e -= 360f
            if (e < -180f) e += 360f
            somme += e; combien++
        }
        val calage = if (combien == 0) 0f else -somme / combien

        // **Checks are relative, never referred to the north pose.**
        //
        // They were, and then measured the operator's drift instead of the
        // maths: between flat and raised poses, yaw had moved by 32° on one
        // reading — nobody puts a small box back on north while holding it up.
        // The verdict condemned a correct calibration. Each group is compared
        // with itself: both roll directions give the same azimuth, whatever
        // it is; raising does not move the azimuth, whatever it is.
        fun pointe(cle: String) = releves.firstOrNull { it.cle == cle }?.let {
            PointageAntenne.pointageLibre(
                AttitudeWit(it.roulis, it.tangage, it.lacet), m, conv)
        }

        var pire = dispersion
        val controles = ArrayList<Controle>()

        val ph = pointe("pol_h"); val pa = pointe("pol_a")
        if (ph != null && pa != null) {
            // Rolling about the edge must change nothing; both directions are
            // compared with each other.
            val e = maxOf(ecartAzimut(ph.azimutDeg, pa.azimutDeg),
                kotlin.math.abs(ph.elevationDeg), kotlin.math.abs(pa.elevationDeg))
            // **Deliberately left out of the verdict**: rolling about the edge
            // puts pitch at ±88°, in gimbal lock. Roll and yaw are no longer
            // separable there and the numbers mean nothing, whatever the
            // convention. That would measure a singularity, not a calibration.
            controles.add(Controle("polarisation", "indicatif — proche du blocage",
                "écart %.0f°, élévations %.0f° et %.0f°".format(
                    ecartAzimut(ph.azimutDeg, pa.azimutDeg),
                    ph.elevationDeg, pa.elevationDeg),
                e < 25f))
        }

        val lb = pointe("leve_bas"); val lh = pointe("leve_haut")
        if (lb != null && lh != null) {
            val ecart = ecartAzimut(lb.azimutDeg, lh.azimutDeg)
            val monte = lh.elevationDeg > lb.elevationDeg + 5f && lb.elevationDeg > 3f
            val e = maxOf(ecart, if (monte) 0f else 30f)
            pire = maxOf(pire, e)
            controles.add(Controle("élévation", "monte sans déplacer l'azimut",
                "%.0f° puis %.0f°, azimut à %.0f° près".format(
                    lb.elevationDeg, lh.elevationDeg, ecart),
                e < 20f))
        }

        // **The missing check.** Raise to the north, then to the east: if tilt
        // follows heading, elevation is the same at both and azimuths differ
        // by a quarter turn. Otherwise elevation collapses in the east and the
        // convention is wrong, even if everything else passed. Relative again:
        // the two raised poses are compared with each other.
        val le = pointe("leve_est")
        if (le != null && lh != null) {
            val ecartAz = ecartAzimut(ecartAzimut(le.azimutDeg, lh.azimutDeg), 90f)
            val ecartEl = kotlin.math.abs(le.elevationDeg - lh.elevationDeg)
            val e = maxOf(ecartAz, ecartEl)
            pire = maxOf(pire, e)
            controles.add(Controle("cap et élévation",
                "même élévation à l'est qu'au nord",
                "%.0f° contre %.0f°, caps à %.0f° l'un de l'autre".format(
                    le.elevationDeg, lh.elevationDeg,
                    ecartAzimut(le.azimutDeg, lh.azimutDeg)),
                e < 20f))
        }

        pointe("vertical")?.let { v ->
            // Azimuth is meaningless at the zenith: only elevation counts.
            val e = kotlin.math.abs(v.elevationDeg - 90f)
            pire = maxOf(pire, e)
            controles.add(Controle("verticale", "élévation 90°",
                "%.0f°".format(v.elevationDeg), e < 15f))
        }

        return Analyse(m, calage, conv, dispersion, pire, controles)
    }

    /**
     * Rotates the arrow by [deg] in the box XY plane: the plane a residual yaw
     * error pushes it into, so the only one to bring it back in.
     */
    private fun tourneDansLePlan(v: Vec3, deg: Float): Vec3 {
        val a = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        return Vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z).normalise()
    }

    private fun ecartAzimut(a: Float, b: Float): Float {
        var d = kotlin.math.abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }
}
