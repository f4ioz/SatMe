/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * La séquence de calibrage : une suite de poses à prendre, et le relevé brut
 * qu'on en tire.
 *
 * **Pourquoi une séquence, et pas une visée de plus.** Six versions ont tenté
 * de deviner la convention du module à partir d'une ou deux poses. Chaque
 * hypothèse tenait à l'horizontale et tombait ailleurs, parce qu'une pose
 * unique ne contraint qu'une partie de la rotation. Deux visées à plat ne
 * disent rien du tangage ; aucune ne dit dans quel sens le roulis compte.
 *
 * Ici on ne devine plus rien : **on prend toutes les poses**, on note les trois
 * angles bruts à chaque fois, et le tableau obtenu détermine la convention sans
 * ambiguïté — quel axe porte quoi, dans quel sens, où il sature, et dans quel
 * ordre les rotations se composent.
 *
 * Le relevé est exportable. C'est délibéré : personne ne peut analyser neuf
 * triplets de tête, et les recopier à la main les corromprait.
 */
object SequenceCalibrage {

    /**
     * Une étape : ce qu'on demande à l'opérateur, et pourquoi.
     *
     * [aide] dit ce que l'étape sert à mesurer. Un opérateur qui comprend le
     * but d'un geste le fait mieux — et il remarque quand quelque chose cloche.
     */
    data class Etape(val cle: String, val titre: String, val aide: String)

    /**
     * Les neuf poses.
     *
     * Les quatre premières balaient le lacet sur un tour complet : elles
     * révèlent son sens et son origine. Les deux suivantes roulent le boîtier
     * dans un sens puis dans l'autre, ce qu'aucune visée à plat ne fait — c'est
     * là que se cachait le défaut d'élévation. Les trois dernières montent en
     * tangage jusqu'à la verticale, où les décompositions d'Euler se
     * distinguent enfin et où certaines saturent.
     */
    /**
     * Les neuf poses.
     *
     * **Toutes portent sur la même arête**, et c'est la leçon du premier
     * relevé : les quatre poses à plat y désignaient une arête du boîtier, les
     * poses levées une autre. Les deux jeux étaient cohérents entre eux et
     * décrivaient deux axes à cent sept degrés l'un de l'autre. Aucune
     * convention ne pouvait les concilier, et l'élévation plafonnait à
     * cinquante-trois degrés au lieu de quatre-vingt-dix.
     *
     * Les quatre premières établissent la flèche : l'azimut y est connu et
     * l'élévation nulle, donc chacune la détermine exactement. Les cinq
     * suivantes ne servent qu'à **contrôler** — on n'y connaît pas l'angle au
     * degré près, mais on sait ce qui doit rester constant.
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

    /** Les quatre poses à plat, celles qui déterminent la flèche. */
    val AZIMUTS_A_PLAT = mapOf("plat_n" to 0f, "plat_e" to 90f, "plat_s" to 180f,
        "plat_o" to 270f)

    /** Les poses de contrôle et ce qu'on attend d'elles. */
    val CONTROLES = listOf("pol_h", "pol_a", "leve_bas", "leve_haut", "leve_est",
        "vertical")

    /** Un relevé : l'étape, et les trois angles bruts du module. */
    data class Releve(
        val cle: String,
        val roulis: Float,
        val tangage: Float,
        val lacet: Float
    )

    /**
     * Range les relevés en une ligne par pose.
     *
     * Un format texte et non binaire : il se relit à l'œil, se recopie dans un
     * message, et survit à tout. Séparateur point-virgule, décimales à deux
     * chiffres — au-delà, on noterait le bruit du capteur.
     */
    fun encode(releves: List<Releve>): String =
        releves.joinToString("\n") {
            "%s;%.2f;%.2f;%.2f".format(it.cle, it.roulis, it.tangage, it.lacet)
        }

    /**
     * Relit ce que [encode] a écrit.
     *
     * Les lignes abîmées sont ignorées plutôt que de faire échouer la lecture
     * entière : un relevé de huit poses sur neuf vaut mieux que rien, et
     * l'opérateur verra tout de suite laquelle manque.
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

    /** L'étape suivante à faire, ou `null` quand le relevé est complet. */
    fun prochaine(releves: List<Releve>): Etape? =
        ETAPES.firstOrNull { e -> releves.none { it.cle == e.cle } }

    fun complete(releves: List<Releve>): Boolean = prochaine(releves) == null

    /**
     * Le rapport lisible, celui qu'on envoie.
     *
     * Il porte son propre entête : un tableau de chiffres sans légende, reçu
     * trois jours plus tard, ne veut plus rien dire.
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

    // ---- l'analyse du relevé ----

    /**
     * Ce que le relevé établit, et à quel point on peut s'y fier.
     *
     * [dispersionDeg] est l'écart angulaire maximal entre les quatre flèches
     * déduites des quatre poses à plat. C'est **la** mesure de confiance : si
     * les quatre gestes désignaient bien la même arête, elles se superposent à
     * quelques degrés. Si elles divergent, c'est que l'arête a changé en cours
     * de route, et aucun calcul ne rattrapera cela.
     */
    data class Analyse(
        val fleche: Vec3?,
        /** Le décalage d'azimut à compenser, en degrés. */
        val calageDeg: Float = 0f,
        val convention: PointageAntenne.ConventionLibre?,
        val dispersionDeg: Float,
        /** Le pire écart, tous contrôles confondus. C'est lui le verdict. */
        val scoreDeg: Float,
        val controles: List<Controle>
    )

    /** Un contrôle : ce qu'on attend, ce qu'on a lu, et le verdict. */
    data class Controle(val cle: String, val attendu: String, val lu: String,
                        val bon: Boolean)

    /**
     * Analyse le relevé : **cherche la convention** plutôt que de la supposer.
     *
     * Les quatre poses à plat donnent la flèche pour chaque convention
     * candidate — l'azimut y est connu, l'élévation nulle. La dispersion entre
     * ces quatre flèches élimine d'emblée les conventions incohérentes.
     *
     * Les cinq autres poses départagent celles qui restent. On n'y connaît pas
     * l'angle au degré près, mais on sait ce qui doit être vrai : rouler autour
     * de l'arête ne change ni l'azimut ni l'élévation, lever fait monter
     * l'élévation sans quitter le nord, et la verticale donne quatre-vingt-dix.
     *
     * On retient la convention dont le **pire** écart est le plus faible. Le
     * pire et non la moyenne : une convention qui réussit quatre contrôles et
     * en rate un cinquième est fausse, pas « plutôt bonne ».
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

        // **Le reliquat d'étalonnage magnétique, mesuré et annulé.**
        //
        // Le lacet du module garde une erreur d'origine après son étalonnage —
        // quinze degrés sur le relevé qui a servi à écrire ceci. Elle n'abîme
        // pas l'azimut, qui l'absorbe : elle fait pivoter la flèche déduite
        // **dans le plan du boîtier**, si bien qu'elle n'est plus perpendiculaire
        // à l'axe autour duquel on lève. L'élévation s'en trouve bridée — à
        // quatre-vingt-douze degrés de roulis elle ne rendait que soixante-
        // quatorze.
        //
        // La pose verticale la mesure : on fait tourner la flèche dans ce plan
        // jusqu'à ce que la verticale donne bien quatre-vingt-dix. Le décalage
        // d'azimut qui en résulte est constant, donc compensable — et c'est ce
        // que rend `calageDeg`.
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

        // Le décalage d'azimut que la correction introduit, moyenné sur les
        // quatre poses à plat dont on connaît l'azimut exact.
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

        // **Les contrôles sont relatifs, jamais rapportés à la pose nord.**
        //
        // Ils l'étaient, et ils mesuraient alors la dérive de l'opérateur au
        // lieu du calcul : entre les poses à plat et les poses levées, le lacet
        // d'un relevé avait bougé de trente-deux degrés — on ne repose pas une
        // petite boîte au nord près quand on la tient en l'air. Le verdict
        // condamnait un étalonnage juste.
        //
        // Comparer chaque groupe **à lui-même** supprime la question : rouler
        // dans un sens puis dans l'autre doit donner le même azimut, quel qu'il
        // soit ; lever ne doit pas déplacer l'azimut, quel qu'il soit.
        fun pointe(cle: String) = releves.firstOrNull { it.cle == cle }?.let {
            PointageAntenne.pointageLibre(
                AttitudeWit(it.roulis, it.tangage, it.lacet), m, conv)
        }

        var pire = dispersion
        val controles = ArrayList<Controle>()

        val ph = pointe("pol_h"); val pa = pointe("pol_a")
        if (ph != null && pa != null) {
            // Rouler autour de l'arête ne doit rien changer. Les deux sens sont
            // comparés l'un à l'autre : c'est la même propriété, sans référence
            // extérieure.
            val e = maxOf(ecartAzimut(ph.azimutDeg, pa.azimutDeg),
                kotlin.math.abs(ph.elevationDeg), kotlin.math.abs(pa.elevationDeg))
            // **Ce contrôle n'entre pas dans le verdict**, et c'est délibéré :
            // rouler autour de l'arête met le tangage à ±88°, donc en plein
            // blocage de cardan. Le roulis et le lacet n'y sont plus déterminés
            // séparément, et les nombres rapportés y sont ininterprétables —
            // quelle que soit la convention. Le mesurer serait mesurer une
            // singularité, pas un étalonnage.
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

        // **Le contrôle qui manquait.** Lever au nord puis lever à l'est : si
        // l'inclinaison suit bien le cap, l'élévation est la même aux deux
        // endroits et les azimuts diffèrent d'un quart de tour. Sinon
        // l'élévation s'effondre à l'est, et la convention est fausse — même
        // si tout le reste passait.
        //
        // Le contrôle est relatif : on compare les deux poses levées entre
        // elles, jamais à une référence extérieure que l'opérateur devrait
        // reproduire.
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
            // L'azimut n'a plus de sens au zénith : seule l'élévation compte.
            val e = kotlin.math.abs(v.elevationDeg - 90f)
            pire = maxOf(pire, e)
            controles.add(Controle("verticale", "élévation 90°",
                "%.0f°".format(v.elevationDeg), e < 15f))
        }

        return Analyse(m, calage, conv, dispersion, pire, controles)
    }

    /**
     * Fait tourner la flèche de [deg] dans le plan XY du boîtier.
     *
     * C'est le plan dans lequel un reliquat de lacet la déporte, et donc le
     * seul dans lequel il faut la ramener.
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
