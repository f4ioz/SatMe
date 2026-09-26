/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Le pointage de l'antenne, tiré de l'attitude du module.
 *
 * **Pourquoi ce fichier existe.** La première version lisait le lacet comme un
 * azimut et une inclinaison comme une élévation, chacun pris à part. Cela
 * marche tant que l'antenne reste en polarisation horizontale, et se défait dès
 * qu'on tourne la flèche sur son axe : les angles d'Euler ne sont pas trois
 * mesures indépendantes, ce sont trois étapes d'une même rotation, et elles se
 * mélangent. À 90° de polarisation, le tangage et le lacet ont échangé leurs
 * rôles — d'où les inversions constatées au terrain.
 *
 * **Ce qui ne se mélange pas**, c'est la direction dans laquelle la flèche
 * pointe. Une rotation de polarisation se fait *autour* de la flèche : elle
 * laisse donc cette direction rigoureusement inchangée. On reconstitue la
 * rotation complète du boîtier, on lui applique le vecteur de la flèche, et on
 * lit l'azimut et l'élévation sur le vecteur obtenu. La polarisation n'a plus
 * aucune prise : ce n'est pas un réglage qui la neutralise, c'est la géométrie.
 *
 * **Repère** : X vers le nord, Y vers l'est, Z vers le bas, et les angles
 * composés dans l'ordre lacet-tangage-roulis. Ce choix a une vertu de contrôle
 * : quand la flèche est portée par l'axe X du boîtier et la polarisation
 * horizontale, il redonne exactement azimut = lacet et élévation = tangage,
 * c'est-à-dire le comportement qui marchait déjà. Un essai le vérifie, plutôt
 * que d'en faire une promesse.
 */

/** Un vecteur à trois composantes. Rien de plus qu'un triplet nommé. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    val norme: Float get() = sqrt(x * x + y * y + z * z)

    fun normalise(): Vec3 {
        val n = norme
        // Un vecteur nul n'a pas de direction. Le rendre tel quel plutôt que de
        // diviser par zéro : l'appelant a une branche pour ça.
        return if (n < 1e-6f) this else Vec3(x / n, y / n, z / n)
    }

    fun produitScalaire(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)
}

/** Où pointe la flèche : azimut 0..360, élévation −90..90. */
data class Pointage(val azimutDeg: Float, val elevationDeg: Float)

object PointageAntenne {

    /**
     * La matrice de rotation du boîtier, du repère du boîtier vers celui du sol.
     *
     * Rendue à plat en neuf flottants, rangés par ligne. Une classe de matrice
     * complète ne servirait à rien ici : on n'en fait que deux choses, appliquer
     * et composer.
     */
    fun matrice(a: AttitudeWit, ordre: Ordre = Ordre.ZYX): FloatArray {
        if (ordre == Ordre.ZXY) return matriceZXY(a)
        val r = Math.toRadians(a.roulis.toDouble()).toFloat()
        val p = Math.toRadians(a.tangage.toDouble()).toFloat()
        val l = Math.toRadians(a.lacet.toDouble()).toFloat()
        val cr = cos(r); val sr = sin(r)
        val cp = cos(p); val sp = sin(p)
        val cl = cos(l); val sl = sin(l)
        // R = Rz(lacet) · Ry(tangage) · Rx(roulis)
        return floatArrayOf(
            cl * cp, cl * sp * sr - sl * cr, cl * sp * cr + sl * sr,
            sl * cp, sl * sp * sr + cl * cr, sl * sp * cr - cl * sr,
            -sp,     cp * sr,                cp * cr
        )
    }

    /** Applique une matrice à un vecteur. */
    fun applique(m: FloatArray, v: Vec3): Vec3 = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z
    )

    /** Le produit `aᵀ · b`, qui sert à comparer deux attitudes. */
    fun transposeePuis(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[k * 3 + i] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    /**
     * L'azimut et l'élévation d'une direction exprimée au sol.
     *
     * Z pointe vers le bas, donc la composante verticale vers le ciel est `−z`.
     */
    /**
     * Le repère du monde dans lequel le module compte.
     *
     * **C'est la seule chose qu'un calage ne peut pas rattraper.** Si l'est et
     * l'ouest sont échangés alors que le nord reste bon, ce n'est pas un
     * décalage mais un **miroir** — et un miroir ne vient pas d'un mauvais
     * réglage, il vient de la main du repère. Une visée ne corrige alors que la
     * pose où on l'a faite : dès qu'on s'en éloigne, le miroir reprend.
     *
     * Les modules d'attitude ne s'accordent pas là-dessus : les uns comptent en
     * nord-est-bas avec un lacet horaire, les autres en est-nord-haut avec un
     * lacet trigonométrique. La notice ne le dit pas toujours, et le deviner
     * serait un tirage au sort. On le **mesure**, avec une seconde visée.
     */
    enum class Repere { NED, ENU }

    /**
     * Les conventions candidates, essayées et **départagées par la mesure**.
     *
     * Deux ne suffisaient pas. Lire dans l'autre repère échange les deux
     * premiers axes — mais cet échange retourne aussi le tangage et le roulis,
     * si bien qu'il ne décrit **pas** le cas simple d'un module dont le seul
     * lacet tourne à l'envers. Le banc l'a réfuté, après que j'eus cru le
     * contraire par un raisonnement de conjugaison incomplet.
     *
     * Plutôt que de chercher laquelle est la bonne — trois tentatives, trois
     * erreurs — on les propose toutes et la seconde visée tranche. C'est le
     * principe même d'un calibre : on ne suppose pas la pièce, on la mesure.
     */
    /** L'ordre dans lequel les trois rotations se composent. */
    enum class Ordre { ZYX, ZXY }

    enum class Convention(
        val repere: Repere,
        val lacetOppose: Boolean,
        val ordre: Ordre = Ordre.ZYX
    ) {
        DIRECTE(Repere.NED, false),
        LACET_OPPOSE(Repere.NED, true),
        AXES_ECHANGES(Repere.ENU, false),
        AXES_ET_LACET(Repere.ENU, true),
        // **L'ordre de composition est la dimension qui manquait.**
        //
        // Les quatre premières ne diffèrent que par le traitement du lacet.
        // Or deux visées à l'horizontale ont le même roulis et le même
        // tangage : elles ne contraignent **que** le lacet. Plusieurs
        // conventions les satisfont donc également, et deux étalonnages
        // successifs en retenaient deux différentes — la campagne de mesure
        // d'Olivier le montre sans appel.
        //
        // Ce qui les départage se joue hors de l'horizontale, donc dans la
        // façon dont tangage et roulis se composent. D'où ces quatre-là, et
        // d'où la troisième visée antenne levée : sans elle, elles restent
        // indiscernables.
        DIRECTE_ZXY(Repere.NED, false, Ordre.ZXY),
        LACET_OPPOSE_ZXY(Repere.NED, true, Ordre.ZXY),
        AXES_ECHANGES_ZXY(Repere.ENU, false, Ordre.ZXY),
        AXES_ET_LACET_ZXY(Repere.ENU, true, Ordre.ZXY);

        companion object {
            fun depuisTexte(t: String): Convention =
                entries.firstOrNull { it.name == t } ?: DIRECTE
        }
    }

    /** L'attitude telle que la convention la fait lire. */
    fun selon(a: AttitudeWit, c: Convention): AttitudeWit =
        if (c.lacetOppose) AttitudeWit(a.roulis, a.tangage, -a.lacet) else a

    fun lis(direction: Vec3, repere: Repere = Repere.NED): Pointage {
        val v = direction.normalise()
        // Nord-est-bas : l'azimut tourne de x vers y, l'élévation monte quand z
        // descend. Est-nord-haut : les deux premiers axes sont échangés et le
        // troisième pointe vers le ciel. Rien d'autre ne distingue les deux, et
        // c'est bien assez pour échanger l'est et l'ouest.
        var az = if (repere == Repere.NED)
            Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()
        else
            Math.toDegrees(atan2(v.x.toDouble(), v.y.toDouble())).toFloat()
        if (az < 0f) az += 360f
        val haut = if (repere == Repere.NED) -v.z else v.z
        val el = Math.toDegrees(asin(haut.coerceIn(-1f, 1f).toDouble())).toFloat()
        return Pointage(az, el)
    }

    /**
     * La même attitude, composée dans l'ordre z-x-y.
     *
     * `R = Rz(lacet) · Rx(roulis) · Ry(tangage)`. Certains micrologiciels
     * publient leurs angles dans cet ordre-là, et la différence ne se voit
     * **jamais** tant qu'on reste à l'horizontale : à roulis et tangage nuls,
     * les deux formules donnent la même matrice. C'est précisément pourquoi
     * elle a échappé à tous les essais faits à plat.
     */
    private fun matriceZXY(a: AttitudeWit): FloatArray {
        val r = Math.toRadians(a.roulis.toDouble()).toFloat()
        val p = Math.toRadians(a.tangage.toDouble()).toFloat()
        val l = Math.toRadians(a.lacet.toDouble()).toFloat()
        val cr = cos(r); val sr = sin(r)
        val cp = cos(p); val sp = sin(p)
        val cl = cos(l); val sl = sin(l)
        return floatArrayOf(
            cl * cp - sl * sr * sp, -sl * cr, cl * sp + sl * sr * cp,
            sl * cp + cl * sr * sp,  cl * cr, sl * sp - cl * sr * cp,
            -cr * sp,                sr,      cr * cp
        )
    }

    /** Le vecteur monde d'un azimut et d'une élévation, dans le repère donné. */
    fun direction(azimutDeg: Float, elevationDeg: Float, repere: Repere = Repere.NED): Vec3 {
        val a = Math.toRadians(azimutDeg.toDouble())
        val e = Math.toRadians(elevationDeg.toDouble())
        val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
        val ce = kotlin.math.cos(e); val se = kotlin.math.sin(e)
        return if (repere == Repere.NED)
            Vec3((ce * ca).toFloat(), (ce * sa).toFloat(), (-se).toFloat())
        else
            Vec3((ce * sa).toFloat(), (ce * ca).toFloat(), se.toFloat())
    }

    /**
     * Le pointage complet, invariant à la polarisation.
     *
     * [fleche] est la direction de la flèche **dans le repère du boîtier** : ce
     * que l'apprentissage détermine. [calageAzimut] recale le nord — une
     * rotation, sans danger pour l'invariance.
     */
    fun pointage(
        a: AttitudeWit,
        fleche: Vec3,
        calageAzimut: Float = 0f,
        /**
         * Le repère du module, **mesuré et non deviné**.
         *
         * Il remplace l'ancien drapeau « sens inverse ». Celui-ci négiait le
         * lacet avant de construire la matrice, ce qui paraît équivalent et ne
         * l'est pas : passer d'un repère à l'autre change aussi l'ordre des
         * rotations, et le banc l'a réfuté. Le repère, lui, ne touche qu'à la
         * lecture du vecteur de sortie — un simple échange d'axes, qui préserve
         * l'invariance à la polarisation.
         */
        convention: Convention = Convention.DIRECTE
    ): Pointage {
        // **Il n'y a pas d'inversion ici, et c'est délibéré.**
        //
        // Le drapeau « sens inverse » appartient à l'ancienne méthode, celle
        // qui lisait le cap sur le seul lacet : là, il fallait bien choisir un
        // sens. Avec la flèche, le cap absolu est **entièrement déterminé** par
        // la matrice et le vecteur — il n'y a plus rien à choisir.
        //
        // Le garder revenait à poser deux corrections pour la même question. La
        // seconde, appliquée en sortie, retournait un azimut : une symétrie, pas
        // une rotation. Or l'invariance à la polarisation n'existe que pour les
        // rotations. D'où les deux défauts vus au terrain — les points cardinaux
        // à l'envers, et le calage qui se décale dès qu'on passe en
        // polarisation verticale.
        //
        // Si le cap sort à l'envers, c'est un calage cardinal qu'il faut
        // refaire, pas un sens à cocher.
        val brut = lis(
            applique(matrice(selon(a, convention), convention.ordre), fleche),
            convention.repere)
        var az = (brut.azimutDeg + calageAzimut) % 360f
        if (az < 0f) az += 360f
        return Pointage(az, brut.elevationDeg)
    }

    /** Ce que deux visées déterminent : la flèche, et le repère du module. */
    data class Calibration(val fleche: Vec3, val convention: Convention, val ecartDeg: Float)

    /**
     * Établit la calibration complète à partir de **deux visées connues**.
     *
     * **Pourquoi deux, et pas une.** Une seule visée fixe la flèche, mais elle
     * ne dit rien du repère dans lequel le module compte : les deux hypothèses
     * la satisfont exactement, puisque la flèche apprise absorbe la différence
     * *à cette pose-là*. C'est ce qui laissait l'est ressortir à l'ouest — le
     * nord tombait juste, parce que c'était sur lui qu'on avait calé, et seule
     * une seconde direction révélait le miroir.
     *
     * **Comment on tranche.** On apprend la flèche sur la première visée, sous
     * chacun des deux repères, puis on relit la **seconde** visée. Celui qui la
     * retrouve gagne. C'est une mesure, pas un choix : le repère n'est pas une
     * préférence de l'opérateur mais une propriété du micrologiciel, que la
     * notice ne donne pas toujours.
     *
     * Les deux visées doivent être franchement différentes — au moins
     * [ECART_VISEES] degrés — sans quoi les deux hypothèses restent également
     * plausibles et l'on ne tranche rien. Rend alors `null` : un calage tiré au
     * sort est pire que pas de calage.
     */
    /**
     * L'étalonnage complet : **trois visées**, dont une antenne levée.
     *
     * **Pourquoi trois.** Deux visées à l'horizontale partagent le même roulis
     * et le même tangage : elles ne contraignent que le lacet, et plusieurs
     * conventions les satisfont également. Deux étalonnages successifs en
     * retenaient alors deux différentes, toutes deux justes à plat et fausses
     * dès qu'on inclinait — d'où l'élévation aberrante et la dérive en
     * polarisation verticale.
     *
     * La troisième visée sort de l'horizontale. C'est elle, et elle seule, qui
     * met à l'épreuve la composition du tangage et du roulis.
     *
     * [elevationC] n'a pas besoin d'être précise : il suffit que l'antenne soit
     * franchement levée. On ne juge pas l'élévation rendue, on juge que
     * l'**azimut tienne** malgré elle — c'est ce que la mauvaise convention ne
     * sait pas faire.
     */
    fun calibreDepuisTroisVisees(
        poseA: AttitudeWit, azimutA: Float,
        poseB: AttitudeWit, azimutB: Float,
        poseC: AttitudeWit, azimutC: Float, elevationC: Float
    ): Calibration? {
        var separation = kotlin.math.abs(azimutB - azimutA) % 360f
        if (separation > 180f) separation = 360f - separation
        if (separation < ECART_VISEES) return null

        var meilleure: Calibration? = null
        for (convention in Convention.entries) {
            val f = apprendFlecheDepuisAzimut(poseA, azimutA, convention = convention)
                ?: continue
            fun ecartA(pose: AttitudeWit, vise: Float): Float {
                val relu = pointage(pose, f, convention = convention).azimutDeg
                var e = kotlin.math.abs(relu - vise) % 360f
                if (e > 180f) e = 360f - e
                return e
            }
            // Le pire des deux contrôles, pas leur moyenne : une convention qui
            // réussit l'horizontale et rate l'antenne levée doit être écartée,
            // non rattrapée par sa bonne moitié.
            val ecart = maxOf(ecartA(poseB, azimutB), ecartA(poseC, azimutC))
            if (meilleure == null || ecart < meilleure!!.ecartDeg) {
                meilleure = Calibration(f, convention, ecart)
            }
        }
        val c = meilleure ?: return null
        return if (c.ecartDeg > TOLERANCE_VISEES) null else c
    }

    fun calibreDepuisDeuxVisees(
        poseA: AttitudeWit, azimutA: Float,
        poseB: AttitudeWit, azimutB: Float
    ): Calibration? {
        var separation = kotlin.math.abs(azimutB - azimutA) % 360f
        if (separation > 180f) separation = 360f - separation
        if (separation < ECART_VISEES) return null

        var meilleure: Calibration? = null
        for (convention in Convention.entries) {
            val f = apprendFlecheDepuisAzimut(poseA, azimutA, convention = convention)
                ?: continue
            val relu = pointage(poseB, f, convention = convention).azimutDeg
            var ecart = kotlin.math.abs(relu - azimutB) % 360f
            if (ecart > 180f) ecart = 360f - ecart
            if (meilleure == null || ecart < meilleure!!.ecartDeg) {
                meilleure = Calibration(f, convention, ecart)
            }
        }
        // Une hypothèse qui se trompe de plus d'un quart de tour sur la seconde
        // visée n'en est pas une : c'est que les relevés eux-mêmes sont
        // incohérents, et il vaut mieux le dire que caler sur du vent.
        val c = meilleure ?: return null
        return if (c.ecartDeg > TOLERANCE_VISEES) null else c
    }

    /** En deçà, les deux visées ne permettent pas de mesurer le sens. */
    const val ECART_VISEES = 45f

    /** Au-delà, même la meilleure hypothèse ne colle pas : on refuse. */
    const val TOLERANCE_VISEES = 25f

    // ---- le calage du cap : deux directions connues ----

    /** Ce que deux relevés déterminent : de combien décaler, et dans quel sens. */
    data class CalageCap(val calageDeg: Float, val inverse: Boolean)

    /**
     * Déduit le calage du cap de deux visées connues : le **nord**, puis
     * l'**ouest**.
     *
     * **Pourquoi deux et pas une.** Un seul relevé ne distingue pas un décalage
     * de 180° d'un sens de rotation retourné : dans les deux cas, viser le nord
     * affiche le sud. Il faut une seconde direction pour lever le doute — et
     * l'ouest est la plus commode, parce qu'on la trouve sans instrument dès
     * qu'on sait où est le nord.
     *
     * Le raisonnement est court. Entre le nord et l'ouest, un azimut décroît de
     * 90° dans le sens trigonométrique, donc **croît de 270°** en azimut de
     * boussole. Si les deux relevés bruts montrent un écart de 270°, le module
     * compte dans le bon sens ; s'ils montrent 90°, il compte à l'envers. Le
     * décalage se lit ensuite sur le seul relevé du nord.
     *
     * Rend `null` quand l'écart ne ressemble ni à l'un ni à l'autre — antenne
     * mal orientée, ou relevé pris pendant que le module bougeait encore.
     * **Mieux vaut ne rien écrire que d'écrire un calage tiré au sort** : un
     * cadran faux qu'on croit réglé est pire qu'un cadran qu'on sait faux.
     */
    /**
     * L'écart toléré entre les deux visées et ce qu'on en attend.
     *
     * Quarante-cinq degrés : on vise une antenne à la main, pas au théodolite,
     * et un quart de tour approximatif reste sans ambiguïté. Au-delà, les deux
     * hypothèses redeviennent également plausibles et trancher serait un
     * tirage au sort.
     */
    const val TOLERANCE_CAP = 45f

    fun calageCapDepuisNordOuest(brutNordDeg: Float, brutOuestDeg: Float): CalageCap? {
        var ecart = (brutOuestDeg - brutNordDeg) % 360f
        if (ecart < 0f) ecart += 360f

        // **La distance doit être circulaire.** Une soustraction nue ignore le
        // passage par zéro : pour deux visées identiques, l'écart vaut 0, et
        // `|0 - 90|` faisait croire que l'hypothèse inversée n'était qu'à
        // quatre-vingt-dix degrés de la vérité. La garde laissait alors écrire
        // un calage tiré de rien.
        fun distance(a: Float, b: Float): Float {
            var d = kotlin.math.abs(a - b) % 360f
            if (d > 180f) d = 360f - d
            return d
        }
        val versDirect = distance(ecart, 270f)
        val versInverse = distance(ecart, 90f)

        // Les deux hypothèses sont à cent quatre-vingts degrés l'une de
        // l'autre : elles ne peuvent pas être proches toutes les deux. Mais
        // elles peuvent être **loin toutes les deux** — c'est le cas de deux
        // visées identiques ou opposées, et il faut alors refuser plutôt que
        // de retenir la moins mauvaise.
        if (minOf(versDirect, versInverse) > TOLERANCE_CAP) return null
        val inverse = versInverse < versDirect
        val signe = if (inverse) -brutNordDeg else brutNordDeg
        var calage = (-signe) % 360f
        if (calage > 180f) calage -= 360f
        if (calage < -180f) calage += 360f
        return CalageCap(calage, inverse)
    }

    // ---- l'apprentissage de l'axe de la flèche ----

    /**
     * En deçà de cet angle entre les deux polarisations, l'axe ne se lit pas.
     *
     * Ce n'est pas une précaution de confort : l'axe d'une rotation se déduit de
     * la partie antisymétrique de sa matrice, dont l'amplitude vaut `2 sin θ`.
     * Près de zéro, elle disparaît dans le bruit et l'axe devient n'importe
     * quelle direction.
     */
    const val ROTATION_MINIMALE = 25f

    /** Au-delà, `sin θ` redescend et l'axe redevient mal conditionné. */
    const val ROTATION_MAXIMALE = 155f

    /**
     * La direction de la flèche, déduite de deux relevés pris **au même
     * pointage** mais à deux polarisations différentes.
     *
     * Le geste est celui qu'un opérateur fait déjà sans y penser : viser
     * quelque part, relever, tourner la flèche d'un quart de tour sur elle-même
     * sans bouger le pointage, relever à nouveau. Entre les deux, la seule
     * chose qui n'a pas bougé est l'axe de la flèche — c'est donc l'axe de la
     * rotation qui sépare les deux attitudes, et il se lit directement.
     *
     * Rend `null` quand la rotation est trop faible ou trop proche du demi-tour
     * : dans ces deux cas l'axe est mal déterminé, et **le dire vaut mieux que
     * rendre une direction tirée du bruit**, qui donnerait un pointage faux
     * d'apparence soignée.
     *
     * Le signe reste indéterminé — une flèche et son opposé ont le même axe de
     * rotation. C'est [resoutSens] qui tranche.
     */
    fun apprendFleche(pol1: AttitudeWit, pol2: AttitudeWit): Vec3? {
        val m = transposeePuis(matrice(pol2), matrice(pol1))
        // L'axe se lit dans la partie antisymétrique ; sa norme vaut 2 sin θ.
        val axe = Vec3(m[7] - m[5], m[2] - m[6], m[3] - m[1])
        val deuxSinus = axe.norme
        val minimum = 2f * sin(Math.toRadians(ROTATION_MINIMALE.toDouble())).toFloat()
        if (deuxSinus < minimum) return null
        // La trace donne l'angle : 1 + 2 cos θ. Au-delà du seuil haut, le même
        // `2 sin θ` correspond à une rotation presque complète, mal posée.
        val cosinus = ((m[0] + m[4] + m[8]) - 1f) / 2f
        val angle = Math.toDegrees(
            atan2(deuxSinus.toDouble() / 2.0, cosinus.toDouble())).toFloat()
        if (abs(angle) > ROTATION_MAXIMALE) return null
        return axe.normalise()
    }

    /**
     * Lève l'ambiguïté de signe, avec le geste déjà en place : antenne levée.
     *
     * Si la flèche apprise donne une élévation négative alors que l'antenne
     * pointe le ciel, c'est qu'elle décrit l'arrière du boîtier. On la
     * retourne.
     *
     * Rend `null` si l'antenne n'était pas assez levée pour trancher — une
     * antenne à l'horizontale ne dit rien de son propre sens.
     */
    /**
     * Apprend la flèche à partir d'**un seul** relevé sur un azimut connu.
     *
     * **Pourquoi c'est meilleur que les deux poses de polarisation.** Faire
     * tourner l'antenne autour de sa flèche donne l'axe de rotation, donc une
     * *droite* — et une droite a deux bouts. Le sens est ensuite deviné par une
     * pose levée, qui peut se tromper : l'antenne pointe alors exactement à
     * l'opposé, nord et sud échangés. C'est ce qu'Olivier a constaté.
     *
     * Un azimut connu lève l'ambiguïté d'un coup : on sait où la flèche pointe
     * dans le monde, on sait comment le boîtier est tourné, la flèche dans le
     * repère du boîtier s'en déduit exactement — `b = Rᵀ · p`. Trois inconnues,
     * une équation vectorielle, aucun bout à choisir.
     *
     * L'antenne doit être **à l'horizontale** pour le relevé par défaut : c'est
     * la pose qu'on sait tenir sans instrument. Et la polarisation du moment
     * n'a aucune importance, l'invariance s'en charge.
     *
     * L'azimut attendu est celui du **nord magnétique**, comme tout ce que rend
     * le module ; SatMe applique la déclinaison en aval. Sous nos latitudes
     * l'écart est de l'ordre du degré, bien en deçà de ce qu'on sait pointer à
     * la main.
     */
    fun apprendFlecheDepuisAzimut(
        pose: AttitudeWit,
        azimutDeg: Float,
        elevationDeg: Float = 0f,
        /** Le calage est retiré de la visée : le vecteur ne doit pas le porter. */
        calageAzimut: Float = 0f,
        /** Le même repère qu'à la lecture, sans quoi rien ne se relit. */
        convention: Convention = Convention.DIRECTE
    ): Vec3? {
        val m = matrice(selon(pose, convention), convention.ordre)
        // La visée doit être bâtie **dans le repère de la convention**, sinon
        // l'apprentissage compense un monde et la lecture en décrit un autre :
        // c'est précisément ce qui faisait ressortir l'est à l'ouest.
        val p = direction(azimutDeg - calageAzimut, elevationDeg, convention.repere)
        // La transposée d'une rotation est son inverse : on ramène la visée du
        // monde vers le boîtier.
        val b = Vec3(
            m[0] * p.x + m[3] * p.y + m[6] * p.z,
            m[1] * p.x + m[4] * p.y + m[7] * p.z,
            m[2] * p.x + m[5] * p.y + m[8] * p.z
        )
        val n = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (n < 1e-6f) return null
        return Vec3(b.x / n, b.y / n, b.z / n)
    }

    /**
     * Retourne la flèche bout pour bout.
     *
     * Le remède en un geste quand l'étalonnage a choisi le mauvais bout et que
     * tout se retrouve à cent quatre-vingts degrés. Plus rapide que de tout
     * recommencer, et sans instrument.
     */
    fun retourne(fleche: Vec3): Vec3 = Vec3(-fleche.x, -fleche.y, -fleche.z)

    fun resoutSens(fleche: Vec3, leveeFranche: AttitudeWit, seuilDeg: Float = 10f): Vec3? {
        val el = lis(applique(matrice(leveeFranche), fleche)).elevationDeg
        if (abs(el) < seuilDeg) return null
        return if (el > 0f) fleche else -fleche
    }

    /**
     * La flèche ramenée à l'axe canonique le plus proche, quand elle en est
     * très près.
     *
     * Un boîtier se visse à plat sur un tube : sa flèche tombe presque toujours
     * sur ±X, ±Y ou ±Z. Coller à l'axe exact quand on en est à moins de huit
     * degrés enlève le bruit d'un relevé unique, sans rien imposer à un montage
     * réellement de biais.
     */
    fun rangeSurAxe(fleche: Vec3, toleranceDeg: Float = 8f): Vec3 {
        val candidats = listOf(
            Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f),
            Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f),
            Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f)
        )
        val v = fleche.normalise()
        val seuil = cos(Math.toRadians(toleranceDeg.toDouble())).toFloat()
        return candidats.firstOrNull { v.produitScalaire(it) >= seuil } ?: v
    }

    /** Écrit une flèche pour la ranger dans les préférences. */
    fun enTexte(v: Vec3): String = "%.6f,%.6f,%.6f".format(java.util.Locale.US, v.x, v.y, v.z)

    /**
     * Relit une flèche rangée. Rend `null` sur tout ce qui n'est pas lisible —
     * y compris la chaîne vide, qui est l'état « pas encore appris ».
     */
    fun depuisTexte(s: String?): Vec3? {
        val p = s?.split(",") ?: return null
        if (p.size != 3) return null
        val x = p[0].toFloatOrNull() ?: return null
        val y = p[1].toFloatOrNull() ?: return null
        val z = p[2].toFloatOrNull() ?: return null
        val v = Vec3(x, y, z)
        return if (v.norme < 1e-3f) null else v.normalise()
    }

    // ---- la convention mesurée, et non plus supposée ----

    /**
     * Une convention quelconque de la famille des angles d'Euler.
     *
     * **Pourquoi si général.** Sept versions ont tenté de deviner comment ce
     * module compose ses trois angles : miroir en sortie, lacet négié, repère
     * échangé, ordre z-y-x contre z-x-y… Chaque hypothèse tenait à
     * l'horizontale et tombait ailleurs, et aucune n'était la bonne. La
     * véritable convention du WT901BLE, mesurée sur neuf poses, est
     * `Rx(roulis) · Ry(−tangage) · Rz(lacet)` lue en est-nord-haut : un ordre
     * que je n'avais pas envisagé, avec un signe que je n'avais pas essayé.
     *
     * Plutôt que d'ajouter cette huitième hypothèse à la liste, on décrit
     * **toute la famille** — six ordres, six affectations, huit combinaisons de
     * signes, deux repères — et on laisse le relevé désigner la bonne. Cinq
     * cent soixante-seize candidates, neuf poses : le téléphone tranche en
     * quelques millisecondes ce que le raisonnement n'a pas su trouver en sept
     * tentatives.
     *
     * [ordre] : les trois axes dans l'ordre de composition, par exemple "xyz".
     * [assign] : quel angle porte chaque axe — 0 roulis, 1 tangage, 2 lacet.
     * [signes] : +1 ou −1 pour chacun.
     * [enu] : vrai pour est-nord-haut, faux pour nord-est-bas.
     */
    data class ConventionLibre(
        val ordre: String,
        val assign: IntArray,
        val signes: IntArray,
        val enu: Boolean
    ) {
        /** Rangée en texte : l'ordre, les rôles, les signes, le repère. */
        fun encode(): String =
            "$ordre|${assign.joinToString("")}|" +
                signes.joinToString("") { if (it > 0) "+" else "-" } +
                "|" + (if (enu) "ENU" else "NED")

        override fun equals(other: Any?) = other is ConventionLibre &&
            encode() == other.encode()
        override fun hashCode() = encode().hashCode()

        companion object {
            /**
             * Celle du WT901BLE, mesurée sur un relevé complet.
             *
             * Elle sert de valeur par défaut : la plupart de ces modules
             * partagent le même micrologiciel, et repartir d'une convention
             * juste vaut mieux que d'une convention arbitraire.
             */
            val PAR_DEFAUT = ConventionLibre("xyz", intArrayOf(0, 1, 2),
                intArrayOf(1, -1, 1), true)

            fun decode(t: String): ConventionLibre {
                val p = t.split('|')
                if (p.size != 4 || p[0].length != 3 || p[1].length != 3 ||
                    p[2].length != 3) return PAR_DEFAUT
                return runCatching {
                    ConventionLibre(
                        p[0],
                        IntArray(3) { p[1][it] - '0' },
                        IntArray(3) { if (p[2][it] == '+') 1 else -1 },
                        p[3] == "ENU")
                }.getOrDefault(PAR_DEFAUT)
            }

            /**
             * Les candidates — **le lacet composé en premier**, toujours.
             *
             * Ce n'est pas une hypothèse de plus : c'est une propriété de tout
             * capteur d'attitude. Le cap s'applique à l'extérieur, et
             * l'inclinaison se prend dans le repère déjà tourné. Une convention
             * où le roulis vient en premier applique l'inclinaison autour d'un
             * axe **fixe du monde** : elle est juste au nord par coïncidence,
             * et l'élévation s'évanouit dès qu'on vise ailleurs.
             *
             * C'est précisément ce qui est arrivé. Deux conventions à roulis
             * extérieur passaient tous les contrôles — parce qu'ils étaient
             * tous faits au nord — et donnaient une élévation de un degré au
             * lieu de cinquante-six dès qu'on pointait l'est. La contrainte les
             * élimine d'emblée, et divise le champ par trois.
             */
            fun toutes(): List<ConventionLibre> {
                val axes = listOf("xyz", "xzy", "yxz", "yzx", "zxy", "zyx")
                val roles = listOf(
                    intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
                    intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
                val out = ArrayList<ConventionLibre>(576)
                // `r[0] == 2` : le lacet en tête. Voir plus haut.
                for (a in axes) for (r in roles.filter { it[0] == 2 })
                    for (s0 in intArrayOf(1, -1)) for (s1 in intArrayOf(1, -1))
                        for (s2 in intArrayOf(1, -1)) for (enu in listOf(true, false))
                            out.add(ConventionLibre(a, r,
                                intArrayOf(s0, s1, s2), enu))
                return out
            }
        }
    }

    private fun elementaire(axe: Char, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(a); val s = sin(a)
        return when (axe) {
            'x' -> floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
            'y' -> floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
            else -> floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }
    }

    private fun produit(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var v = 0f
            for (k in 0..2) v += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = v
        }
        return r
    }

    /** La matrice corps → monde, selon la convention donnée. */
    fun matriceLibre(a: AttitudeWit, c: ConventionLibre): FloatArray {
        val angles = floatArrayOf(a.roulis, a.tangage, a.lacet)
        var m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (k in 0..2) {
            m = produit(m, elementaire(c.ordre[k], c.signes[k] * angles[c.assign[k]]))
        }
        return m
    }

    private fun appliqueM(m: FloatArray, v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z)

    private fun appliqueTM(m: FloatArray, v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z)

    /** Le pointage, selon une convention mesurée. */
    fun pointageLibre(a: AttitudeWit, fleche: Vec3, c: ConventionLibre): Pointage =
        lis(appliqueM(matriceLibre(a, c), fleche),
            if (c.enu) Repere.ENU else Repere.NED)

    /** La flèche qu'implique une visée connue, selon une convention. */
    fun flecheLibre(pose: AttitudeWit, azimutDeg: Float, elevationDeg: Float,
                    c: ConventionLibre): Vec3? {
        val p = direction(azimutDeg, elevationDeg,
            if (c.enu) Repere.ENU else Repere.NED)
        val b = appliqueTM(matriceLibre(pose, c), p)
        return if (b.norme < 1e-6f) null else b.normalise()
    }
}
