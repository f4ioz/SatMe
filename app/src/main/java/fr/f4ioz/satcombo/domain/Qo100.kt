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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * QO-100 : le satellite qui ne bouge pas.
 *
 * Tout le reste de SatMe est bâti sur l'idée qu'un satellite passe. On calcule
 * une orbite, on en tire une acquisition et une perte de signal, une trace au
 * sol, un Doppler qui balaie plusieurs kilohertz en dix minutes, et un mât qui
 * court derrière. Es'hail-2 ne fait rien de tout cela : il est géostationnaire
 * par 25,9° est, à 36 000 km, et depuis l'Europe il est simplement *là*, au
 * même azimut et à la même élévation, jour et nuit.
 *
 * Cela retire trois choses d'un coup, et il vaut mieux les nommer :
 *
 * - **Le Doppler n'existe pas.** La vitesse radiale est nulle par
 *   construction. Ce n'est pas « un Doppler qu'on met en pause », c'est un
 *   Doppler qui n'a pas lieu d'être calculé. Le mettre à zéro par la vitesse
 *   radiale plutôt que par un interrupteur laisse toute la chaîne existante
 *   intacte, sans branche particulière.
 * - **Il n'y a pas de passage.** Pas d'AOS, pas de LOS, pas de compte à
 *   rebours, pas de trace. Un prédicteur SGP4 sur un géostationnaire ne rend
 *   rien d'exploitable, et il ne faut pas lui demander.
 * - **Le mât n'a rien à suivre.** La parabole se pointe une fois, à la
 *   boussole et à l'inclinomètre, et on n'y touche plus.
 *
 * Ce qui reste, en revanche, est nouveau : la descente est à 10 489 MHz et la
 * montée à 2 400 MHz, deux bandes qu'aucun poste d'amateur n'atteint. Il faut
 * donc des convertisseurs — voir [Convertisseur] —, et c'est là que se joue
 * l'essentiel du travail.
 *
 * ### Ce que cette pièce ne fait pas
 *
 * Elle ne connaît ni Android, ni le poste, ni les réglages : elle se juge au
 * banc. Elle ne sait rien des convertisseurs — elle raisonne uniquement en
 * fréquences du ciel, comme tout le reste du domaine, et la traduction vers le
 * poste se fait ailleurs, au dernier moment.
 *
 * Sources : AMSAT-DL, plan de fréquences du transpondeur étroit.
 */
object Qo100 {

    /** Le numéro du catalogue, pour retrouver l'étalonnage déjà rangé par satellite. */
    const val NORAD = 43700

    /** Ce qui s'affiche en tête d'écran. Les deux noms sont d'usage courant. */
    const val NOM = "QO-100 / Es'hail-2"

    /** Sa position sur l'arc géostationnaire, en degrés est. */
    const val LONGITUDE_DEG = 25.9

    /**
     * Le décalage montée/descente, fixe et garanti par le transpondeur.
     *
     * `descente = montée + 8 089,5 MHz`, toujours, sans inversion de spectre.
     * C'est ce qui rend QO-100 si confortable : une fois la montée réglée, la
     * descente s'en déduit exactement, et réciproquement. Sur un transpondeur
     * inverseur ordinaire il faut se souvenir que monter d'un kilohertz fait
     * descendre d'autant ; ici, non.
     */
    const val DECALAGE_HZ = 8_089_500_000L

    /** Les trois balises du transpondeur étroit, dans le ciel. */
    const val BALISE_BASSE_HZ = 10_489_500_000L

    /**
     * La balise du milieu, en BPSK 400 bit/s.
     *
     * C'est la référence d'étalonnage : elle est tenue par une horloge au sol,
     * donc sa fréquence est juste. Tout écart mesuré sur elle est la dérive de
     * l'oscillateur du convertisseur de descente, et se range dans le décalage
     * d'étalonnage du satellite. Un LNB de télévision ordinaire dérive de
     * plusieurs dizaines de kilohertz à la mise sous tension, puis se stabilise
     * en une demi-heure : sans elle, on chercherait ses correspondants à côté.
     */
    const val BALISE_MEDIANE_HZ = 10_489_750_000L

    const val BALISE_HAUTE_HZ = 10_490_000_000L

    /**
     * Un transpondeur de QO-100.
     *
     * Les bornes sont dans le ciel, et la montée s'en déduit par [DECALAGE_HZ]
     * — elles sont redites ici plutôt que calculées pour que le plan de
     * fréquences reste lisible d'un coup d'œil.
     *
     * ### Les sept kilohertz du bord haut, et ce qu'ils sont vraiment
     *
     * Le plan de fréquences d'AMSAT-DL donne la montée en 2 400,005 – 2 400,490
     * et la descente en 10 489,505 – 10 489,997. Le bord bas concorde
     * exactement avec le décalage de 8 089,5 MHz ; le bord haut, non : la
     * montée annoncée donnerait 10 489,990, soit 7 kHz de moins que la descente
     * annoncée.
     *
     * L'explication tenait dans une ligne du tableau détaillé, et elle est
     * rassurante : **10 489,990 – 10 489,997 est la balise multimédia**, en
     * 8APSK à 7 200 bit/s — voir [SEGMENTS]. Ces sept kilohertz font bien
     * partie de la descente du transpondeur, puisqu'une balise y est reçue,
     * mais personne n'y émet depuis le sol. Les deux bords du tableau sont
     * donc justes : ils ne décrivent simplement pas la même chose. Le bord de
     * montée 2 400,490 est la dernière fréquence sur laquelle *on transmet*,
     * le bord de descente 10 489,997 la dernière fréquence sur laquelle *on
     * reçoit quelque chose*. Le tableau publié n'était pas incohérent, il
     * était incomplet.
     *
     * Il reste que l'application ne doit jamais déduire une montée du bord
     * haut de la descente : elle la calcule toujours par
     * [monteeDepuisDescente], et vérifie [emissionAutorisee] avant de la
     * pousser vers le poste. Sept kilohertz suffisent à poser une porteuse sur
     * une balise.
     */
    data class Transpondeur(
        /** Sert à retrouver le libellé traduit : `qo100_tp_nb`, `qo100_tp_wb`. */
        val cle: String,
        val monteeBasHz: Long,
        val monteeHautHz: Long,
        val descenteBasHz: Long,
        val descenteHautHz: Long,
    ) {
        /** Le milieu de la bande passante, point de départ raisonnable. */
        val centreDescenteHz: Long get() = (descenteBasHz + descenteHautHz) / 2

        /** Cette descente est-elle dans le transpondeur ? */
        fun contientDescente(hz: Long): Boolean = hz in descenteBasHz..descenteHautHz

        /** Ramène une descente dans les bornes, sans jamais sortir du transpondeur. */
        fun brideDescente(hz: Long): Long = hz.coerceIn(descenteBasHz, descenteHautHz)

        /** La largeur utile, pour l'afficher. */
        val largeurHz: Long get() = descenteHautHz - descenteBasHz
    }

    /**
     * Le transpondeur étroit : la phonie, la CW, les modes numériques lents.
     *
     * Montée en 2 400,005 – 2 400,490 polarisée circulaire droite, descente en
     * 10 489,505 – 10 489,997 polarisée linéaire verticale. C'est celui-ci qui
     * intéresse une station de radioamateur ordinaire.
     */
    val NB = Transpondeur(
        cle = "nb",
        monteeBasHz = 2_400_005_000L, monteeHautHz = 2_400_490_000L,
        descenteBasHz = 10_489_505_000L, descenteHautHz = 10_489_997_000L)

    /**
     * Le transpondeur large : la télévision numérique d'amateur.
     *
     * Il demande une puissance et une parabole d'un autre ordre, et un
     * modulateur DVB-S2 que SatMe ne pilote pas. Il est là pour que l'écran
     * puisse l'afficher et pour que le plan de fréquences soit complet, pas
     * pour être exploité.
     */
    val WB = Transpondeur(
        cle = "wb",
        monteeBasHz = 2_401_500_000L, monteeHautHz = 2_409_500_000L,
        descenteBasHz = 10_491_000_000L, descenteHautHz = 10_499_000_000L)

    val TRANSPONDEURS: List<Transpondeur> = listOf(NB, WB)

    // --- Le plan de bande du transpondeur étroit -------------------------

    /**
     * Ce qu'on a le droit de faire dans un segment.
     *
     * Sert à deux choses et à rien d'autre : choisir la couleur du segment sur
     * la réglette, et choisir le ton de l'avertissement quand le curseur s'y
     * pose. La finesse du plan de bande — largeur maximale, fréquence repère —
     * vit dans [Segment], pas ici.
     */
    enum class Usage {
        /** Une balise. On l'écoute, on ne transmet pas dessus. */
        BALISE,

        /** Télégraphie seule. Une porteuse modulée en phonie y est un abus. */
        CW,

        /** Modes numériques. La largeur permise est dans [Segment.largeurMaxHz]. */
        NUMERIQUE,

        /** Phonie seule — en pratique la BLU. Le gros de l'activité. */
        PHONIE,

        /** La fréquence de diffusion, réservée aux émissions de club. */
        DIFFUSION,

        /** La fréquence d'urgence. À laisser libre. */
        URGENCE,

        /** Modes mixtes et usages particuliers. Tout y est toléré, à 2,7 kHz. */
        MIXTE,
        ;

        /** Peut-on émettre dans un segment de cet usage ? */
        val emissionPermise: Boolean get() = this != BALISE

        /**
         * Le suffixe de la clé d'avertissement : l'écran compose
         * `"qo100_warn_" + cleAvertissement`.
         *
         * Il est dérivé du nom plutôt que recopié pour qu'un usage ajouté un
         * jour n'ait aucune chance d'arriver sans son texte : l'essai des
         * textes parcourt [entries] et réclame la clé de chacun.
         */
        val cleAvertissement: String get() = name.lowercase()
    }

    /**
     * Une tranche du transpondeur étroit, telle qu'AMSAT-DL la publie.
     *
     * Les bornes sont des fréquences du ciel, en descente, et l'intervalle est
     * fermé en bas, ouvert en haut : `basHz <= f < hautHz`. C'est ce qui permet
     * de recoller les douze segments bout à bout sans trou ni recouvrement, et
     * l'essai de couverture y veille.
     */
    data class Segment(
        /**
         * Le suffixe de la clé de traduction : l'écran compose
         * `"qo100_seg_" + cle`. Un renommage ici vide donc un libellé, et
         * l'essai des textes le dit.
         */
        val cle: String,
        val basHz: Long,
        val hautHz: Long,
        val usage: Usage,
        /**
         * La largeur d'émission maximale admise, en hertz, ou zéro quand le
         * plan n'en fixe pas — le cas de la CW, où l'usage suffit.
         */
        val largeurMaxHz: Int = 0,
        /**
         * La fréquence qui donne son sens au segment, quand il y en a une :
         * le cœur d'une balise, la fréquence de diffusion, celle d'urgence.
         * Nulle pour les segments qui sont de simples plages.
         */
        val repereHz: Long? = null,
    ) {
        val largeurHz: Long get() = hautHz - basHz

        /** Cette descente tombe-t-elle dans ce segment ? */
        operator fun contains(hz: Long): Boolean = hz >= basHz && hz < hautHz

        /** Raccourci de lecture : peut-on émettre ici ? */
        val emissionPermise: Boolean get() = usage.emissionPermise
    }

    /**
     * Les douze segments du transpondeur étroit, de balise à balise.
     *
     * Source : AMSAT-DL, plan de bande détaillé du transpondeur étroit. La
     * liste couvre 10 489,500 à 10 490,000, soit 500 kHz — un peu plus que les
     * 492 kHz de [NB], parce qu'elle inclut les deux balises CW qui encadrent
     * la bande passante utile. C'est voulu : sur la réglette, ce sont ces deux
     * balises qui servent de butées visibles, et une réglette qui s'arrêterait
     * aux bornes de [NB] les couperait en deux.
     *
     * Trois choses méritent d'être signalées à qui relit ce tableau :
     *
     * - **Les quatre balises ne sont pas trois.** À la basse, la médiane et la
     *   haute s'ajoute la balise multimédia en 10 489,990 – 10 489,997, en
     *   8APSK à 7 200 bit/s. C'est elle qui explique les sept kilohertz du bord
     *   haut — voir la note de [Transpondeur]. On ne lui donne pas de constante
     *   au même titre que les trois autres parce qu'elle ne sert de repère à
     *   personne : elle sert de garde-fou.
     * - **La dernière fréquence sur laquelle on émet est 10 489,990**, pas
     *   10 489,997. C'est [DERNIERE_DESCENTE_EMISSIBLE_HZ], et c'est la seule
     *   valeur de ce fichier qui protège d'un vrai brouillage.
     * - **Diffusion et urgence font 7,5 kHz chacune**, ce qui place leur
     *   frontière commune sur un demi-kilohertz, 10 489,857 5. Ce n'est pas une
     *   coquille : le tableau publié donne bien deux tranches de 7,5 kHz autour
     *   de 10 489,855 et 10 489,860.
     */
    val SEGMENTS: List<Segment> = listOf(
        Segment("balise_basse", 10_489_500_000L, 10_489_505_000L, Usage.BALISE,
            repereHz = BALISE_BASSE_HZ),
        Segment("cw", 10_489_505_000L, 10_489_540_000L, Usage.CW),
        Segment("num_etroit", 10_489_540_000L, 10_489_580_000L, Usage.NUMERIQUE,
            largeurMaxHz = 500),
        Segment("num_large", 10_489_580_000L, 10_489_650_000L, Usage.NUMERIQUE,
            largeurMaxHz = 2_700),
        Segment("ssb_bas", 10_489_650_000L, 10_489_745_000L, Usage.PHONIE,
            largeurMaxHz = 2_700),
        Segment("balise_mediane", 10_489_745_000L, 10_489_755_000L, Usage.BALISE,
            repereHz = BALISE_MEDIANE_HZ),
        Segment("ssb_haut", 10_489_755_000L, 10_489_850_000L, Usage.PHONIE,
            largeurMaxHz = 2_700),
        Segment("diffusion", 10_489_850_000L, 10_489_857_500L, Usage.DIFFUSION,
            largeurMaxHz = 2_700, repereHz = 10_489_855_000L),
        Segment("urgence", 10_489_857_500L, 10_489_865_000L, Usage.URGENCE,
            largeurMaxHz = 2_700, repereHz = 10_489_860_000L),
        Segment("mixte", 10_489_865_000L, 10_489_990_000L, Usage.MIXTE,
            largeurMaxHz = 2_700),
        Segment("balise_multimedia", 10_489_990_000L, 10_489_997_000L, Usage.BALISE,
            repereHz = 10_489_993_500L),
        Segment("balise_haute", 10_489_997_000L, 10_490_000_000L, Usage.BALISE,
            repereHz = BALISE_HAUTE_HZ),
    )

    /** Le bas de la réglette : le début de la balise basse. */
    val REGLETTE_BAS_HZ: Long get() = SEGMENTS.first().basHz

    /** Le haut de la réglette : la balise haute, qui est incluse. */
    val REGLETTE_HAUT_HZ: Long get() = SEGMENTS.last().hautHz

    /**
     * La dernière descente sur laquelle il est permis d'émettre.
     *
     * Au-dessus commence la balise multimédia, puis la balise CW haute. Le
     * bord publié de la descente, 10 489,997, est *au-delà* de cette valeur :
     * c'est tout le piège, et c'est pour cela que la constante est ici et pas
     * déduite de [NB].
     */
    const val DERNIERE_DESCENTE_EMISSIBLE_HZ = 10_489_990_000L

    /**
     * Le segment qui contient cette descente, ou `null` hors de la réglette.
     *
     * Les intervalles sont ouverts en haut pour que les douze se recollent
     * sans recouvrement ; il faut donc rattraper la toute dernière fréquence à
     * la main, sans quoi la balise haute serait exactement le seul point de la
     * réglette à n'appartenir à rien.
     */
    fun segment(descenteHz: Long): Segment? =
        SEGMENTS.firstOrNull { descenteHz in it }
            ?: SEGMENTS.last().takeIf { descenteHz == it.hautHz }

    /**
     * A-t-on le droit d'émettre sur cette descente ?
     *
     * Faux hors de la réglette, et faux sur les quatre balises. C'est la
     * question que l'écran pose avant de pousser une fréquence d'émission vers
     * le poste, et la seule réponse qui vaille est celle-ci : on ne la déduit
     * ni des bornes de [NB], ni du bord du tableau publié.
     */
    fun emissionAutorisee(descenteHz: Long): Boolean =
        segment(descenteHz)?.emissionPermise == true

    /** La descente qui correspond à une montée. Sans inversion : une addition. */
    fun descenteDepuisMontee(monteeHz: Long): Long = monteeHz + DECALAGE_HZ

    /** La montée qui correspond à une descente. */
    fun monteeDepuisDescente(descenteHz: Long): Long = descenteHz - DECALAGE_HZ

    /**
     * Où pointer la parabole, et de combien la tourner sur elle-même.
     *
     * [azDeg] est l'azimut vrai, compté depuis le nord géographique dans le
     * sens des aiguilles d'une montre — pas le nord magnétique : la
     * déclinaison est ajoutée à l'affichage, comme partout ailleurs dans
     * l'application.
     */
    data class Pointage(
        val azDeg: Double,
        val elDeg: Double,
        /**
         * L'angle de rotation de la source, en degrés, compté positivement
         * dans le sens des aiguilles d'une montre vu de derrière la parabole.
         *
         * La descente du transpondeur étroit est polarisée linéairement, donc
         * la source doit être tournée pour s'y aligner. Depuis la France
         * l'angle vaut une vingtaine de degrés : l'ignorer coûte quelques
         * décibels et laisse croire à un problème d'antenne.
         */
        val skewDeg: Double,
    ) {
        /** Le satellite est-il au-dessus de l'horizon depuis ce point ? */
        val visible: Boolean get() = elDeg > 0.0
    }

    /**
     * Le pointage depuis un point du globe, sans propagation d'orbite.
     *
     * Le calcul est géométrique et tient en quelques lignes : on place
     * l'observateur et le satellite dans un repère lié à la Terre, on prend le
     * vecteur de l'un vers l'autre, et on le projette sur le trièdre local est
     * / nord / haut. C'est plus long à écrire que la formule fermée qu'on
     * trouve dans les manuels, mais c'est juste dans les deux hémisphères et
     * aux longitudes extrêmes, là où la formule fermée change de branche sans
     * prévenir.
     *
     * La Terre est prise sphérique. L'écart avec l'ellipsoïde est de l'ordre
     * du dixième de degré, très en dessous de ce qu'on sait pointer avec une
     * boussole de téléphone et un niveau à bulle.
     */
    fun pointage(latDeg: Double, lonDeg: Double): Pointage {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg)
        val lamSat = Math.toRadians(LONGITUDE_DEG)

        // Observateur et satellite, en rayons terrestres.
        val ox = cos(phi) * cos(lam)
        val oy = cos(phi) * sin(lam)
        val oz = sin(phi)
        val sx = RAPPORT_ORBITE * cos(lamSat)
        val sy = RAPPORT_ORBITE * sin(lamSat)

        // Le vecteur qui va de l'antenne au satellite.
        val vx = sx - ox
        val vy = sy - oy
        val vz = -oz

        // Trièdre local. Le « haut » est la verticale du lieu, qui est aussi
        // la direction de l'observateur depuis le centre de la Terre.
        val est = -sin(lam) * vx + cos(lam) * vy
        val nord = -sin(phi) * cos(lam) * vx - sin(phi) * sin(lam) * vy + cos(phi) * vz
        val haut = ox * vx + oy * vy + oz * vz

        val az = (Math.toDegrees(atan2(est, nord)) + 360.0) % 360.0
        val el = Math.toDegrees(atan2(haut, hypot(est, nord)))

        return Pointage(azDeg = az, elDeg = el, skewDeg = skew(latDeg, lonDeg))
    }

    /**
     * La rotation de la source, ramenée dans le quadrant utile.
     *
     * Une polarisation linéaire est la même à 180° près : tourner de 170°
     * revient à tourner de −10°, et personne ne visse une source à l'envers
     * pour rien. On ramène donc toujours l'angle dans ±90°.
     */
    private fun skew(latDeg: Double, lonDeg: Double): Double {
        val phi = Math.toRadians(latDeg)
        val delta = Math.toRadians(LONGITUDE_DEG - lonDeg)
        // Pile sur l'équateur, la tangente s'envole et l'angle n'a plus de
        // sens : la source est alors alignée, point.
        if (abs(latDeg) < 1e-9) return 0.0
        var s = Math.toDegrees(atan2(sin(delta), tan(phi)))
        while (s > 90.0) s -= 180.0
        while (s <= -90.0) s += 180.0
        return s
    }

    /**
     * Rayon de l'orbite géostationnaire divisé par le rayon terrestre :
     * 42 164 / 6 378,137, soit 6,6107. Tout le calcul se fait en rayons
     * terrestres, donc c'est la seule constante de distance nécessaire.
     *
     * Attention au sens : les manuels donnent souvent le rapport inverse,
     * 0,15127, parce qu'il apparaît tel quel dans la formule fermée de
     * l'élévation. Ici on place réellement le satellite dans le repère, il
     * faut donc sa distance au centre de la Terre, pas son inverse.
     */
    private const val RAPPORT_ORBITE = 42_164.0 / 6_378.137
}
