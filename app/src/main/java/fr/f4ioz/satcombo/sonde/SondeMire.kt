/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La mire radiosonde : un ballon de synthèse, entièrement fabriqué par le
 * téléphone.
 *
 * Le problème est le même que pour la SSTV, en pire. Un lâcher a lieu deux fois
 * par jour, à heure fixe, et une sonde ne passe à portée que si le vent le veut
 * bien. Quand on finit par en entendre une et que rien ne se décode, il est
 * trop tard pour chercher si le tort en revient au décodeur, au cordon, au
 * niveau d'entrée ou à l'accord — la sonde est déjà partie, et il faut attendre
 * douze heures pour réessayer. La mire supprime l'attente : elle fabrique le
 * son qu'un discriminateur rendrait sur une vraie sonde, à la demande, autant
 * de fois qu'on veut.
 *
 * Ce qui est produit ici n'est pas une imitation approximative. Ce sont de
 * vraies trames, au format exact du constructeur, brouillage et contrôles
 * compris, portant les coordonnées d'un vol plausible ; le décodeur ne peut
 * pas faire la différence avec l'air, et c'est bien le but. Trois emplois :
 *
 * 1. Par le haut-parleur, un téléphone contre l'autre, pour éprouver la chaîne
 *    micro d'un camarade.
 * 2. En fichier, à repasser sur une radio ou dans une carte son, pour éprouver
 *    tout le cordon.
 * 3. En démonstration pure, le son étant versé directement dans le décodeur :
 *    l'écran se remplit d'un vol complet — montée, éclatement, descente,
 *    trace sur la carte, journal exportable — sans radio du tout.
 *
 * Le vol simulé part du carré de l'opérateur, monte à cinq mètres par seconde
 * en dérivant avec un vent d'ouest, éclate vers trente kilomètres et redescend
 * de plus en plus lentement à mesure que l'air s'épaissit. C'est le profil
 * d'une vraie sonde, et il a l'avantage de faire passer la trace par tous les
 * cas que l'application doit savoir afficher.
 */
object SondeMire {

    /** Fréquence d'échantillonnage du signal produit — celle de la carte son. */
    const val RATE = 44_100

    /** Amplitude crête du carré, largement au-dessus du seuil du démodulateur. */
    const val AMPLITUDE = 9_000

    /** Facteur de sur-échantillonnage de la synthèse, avant filtrage et décimation. */
    const val OVERSAMPLE = 8

    /** Souffle de l'ambiance réaliste, en fraction de l'amplitude. */
    const val NOISE = 0.12

    /** Plancher de l'évanouissement : la sonde faiblit sans jamais disparaître. */
    const val FADE_FLOOR = 0.45

    /** Cadence de l'évanouissement, en hertz. */
    const val FADE_HZ = 0.07

    /** Fréquence annoncée dans les trames, en hertz. */
    const val DEMO_FREQ_HZ = 404_000_000L

    /** Durées proposées, en secondes : une trame par seconde comme sur l'air. */
    val DURATIONS = listOf(30, 60, 120, 300)

    // ------------------------------------------------------------------ vol

    /** Un instant du vol simulé. */
    data class Point(
        val lat: Double, val lon: Double, val altM: Double,
        val east: Double, val north: Double, val up: Double,
        val sats: Int,
        /**
         * Instant du vol représenté, en secondes depuis le lâcher.
         *
         * Il ne vaut pas le numéro de trame : une mire d'une minute raconte un
         * vol de deux heures, donc chaque pas de mire vaut plusieurs centaines
         * de secondes de vol. Ce chiffre-là est celui qu'il faut écrire dans
         * l'horloge GPS de la trame, faute de quoi la mire se contredit — elle
         * déplace le ballon de quarante kilomètres en annonçant une seconde,
         * et le contrôle de continuité du suivi de vol a bien raison de jeter
         * le point. C'est exactement ce qui se passait : la mire ne rendait
         * qu'une trame retenue sur six, et l'on a d'abord cru à un défaut du
         * récepteur.
         */
        val tSec: Double = 0.0)

    /**
     * Le vol complet, en [frames] points régulièrement espacés.
     *
     * Le temps réel du vol est comprimé : une sonde met deux heures à monter et
     * à redescendre, et personne n'écoute une mire pendant deux heures. Une
     * minute de mire raconte donc le vol entier, ce qui donne à la trace sur la
     * carte l'allure qu'elle aura le jour venu.
     */
    fun flight(
        lat0: Double, lon0: Double, frames: Int,
        burstAltM: Double = 30_000.0, groundAltM: Double = 120.0,
        stepSec: Double = 0.0
    ): List<Point> {
        val out = ArrayList<Point>(frames)
        // Vol en temps réel quand on donne un pas : chaque trame vaut alors la
        // tranche de vol qu'elle annonce, ni plus ni moins. C'est ce que veut
        // le banc de mesure, qui compte les trames retenues et ne peut donc
        // rien mesurer si la mire se déplace de quarante kilomètres entre deux
        // trames espacées d'une seconde. La démo, elle, garde le vol comprimé.
        val realTime = stepSec > 0.0
        val step = if (realTime) stepSec
                   else (burstAltM - groundAltM) / 5.0 * 1.5 / frames
        // Deux tiers du vol en montée, un tiers en descente : c'est le rapport
        // habituel, la chute étant freinée par le parachute mais partant de
        // beaucoup plus haut et beaucoup plus vite. En temps réel, quelques
        // secondes de vol ne sont jamais que de la montée.
        val up = if (realTime) frames else (frames * 2) / 3
        var lat = lat0
        var lon = lon0
        for (k in 0 until frames) {
            val t = (k + 1) * step
            val ascending = k < up
            val f = if (ascending) k.toDouble() / maxOf(1, up)
                    else (k - up).toDouble() / maxOf(1, frames - up)
            val alt = if (realTime) groundAltM + 5.0 * t
                      else if (ascending) groundAltM + (burstAltM - groundAltM) * f
                      else burstAltM - (burstAltM - groundAltM) * f
            // Le vent forcit avec l'altitude, comme dans la vraie atmosphère :
            // presque rien au sol, une trentaine de mètres par seconde vers dix
            // kilomètres, ce qui emmène la sonde loin sous le vent.
            val windE = 4.0 + 26.0 * (alt / 12_000.0).coerceIn(0.0, 1.0)
            val windN = 2.0 + 6.0 * (alt / 12_000.0).coerceIn(0.0, 1.0)
            // Vitesse verticale : cinq mètres par seconde à la montée, et une
            // descente qui s'amortit à mesure que l'air porte davantage.
            val climb = if (ascending) 5.0
                        else -(4.0 + 26.0 * (alt / burstAltM).coerceIn(0.0, 1.0))
            // Un pas de mire vaut un pas de vol : on déplace la sonde du chemin
            // qu'elle ferait pendant la tranche de vol représentée.
            lat += windN * step / 111_320.0
            lon += windE * step / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            out += Point(lat, lon, alt, windE, windN, climb,
                sats = if (alt > 1_000.0) 10 else 7,
                tSec = t)
        }
        return out
    }

    // ------------------------------------------------------------- géodésie

    /** Géodésique vers ECEF : la RS41 transmet ses coordonnées sous cette forme. */
    fun geodeticToEcef(latDeg: Double, lonDeg: Double, hM: Double): DoubleArray {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val s = sin(la)
        val n = Geo.A / sqrt(1.0 - Geo.E2 * s * s)
        return doubleArrayOf(
            (n + hM) * cos(la) * cos(lo),
            (n + hM) * cos(la) * sin(lo),
            (n * (1.0 - Geo.E2) + hM) * s)
    }

    /** Vitesse locale est/nord/haut vers vitesse ECEF. */
    fun enuToEcefVel(latDeg: Double, lonDeg: Double,
                     e: Double, n: Double, u: Double): DoubleArray {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val sla = sin(la); val cla = cos(la)
        val slo = sin(lo); val clo = cos(lo)
        return doubleArrayOf(
            -slo * e - sla * clo * n + cla * clo * u,
            clo * e - sla * slo * n + cla * slo * u,
            cla * n + sla * u)
    }

    // -------------------------------------------------------------- écriture

    private fun putU16(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun putI32(f: ByteArray, at: Int, v: Int) {
        f[at] = (v and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = ((v shr 16) and 0xff).toByte()
        f[at + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun putBe16(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 8) and 0xff).toByte()
        f[at + 1] = (v and 0xff).toByte()
    }

    private fun putBe24(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 16) and 0xff).toByte()
        f[at + 1] = ((v shr 8) and 0xff).toByte()
        f[at + 2] = (v and 0xff).toByte()
    }

    private fun putBe32(f: ByteArray, at: Int, v: Int) {
        f[at] = ((v shr 24) and 0xff).toByte()
        f[at + 1] = ((v shr 16) and 0xff).toByte()
        f[at + 2] = ((v shr 8) and 0xff).toByte()
        f[at + 3] = (v and 0xff).toByte()
    }

    // ------------------------------------------------------------------ RS41

    /** Écrit un bloc identifiant / longueur / données / CRC, rend la position suivante. */
    private fun block(f: ByteArray, at: Int, id: Int, data: ByteArray): Int {
        f[at] = id.toByte()
        f[at + 1] = data.size.toByte()
        System.arraycopy(data, 0, f, at + 2, data.size)
        putU16(f, at + 2 + data.size, Rs41.crc16(f, at + 2, data.size))
        return at + 2 + data.size + 2
    }

    /**
     * Une trame RS41 standard, désembrouillée, portant ce point de vol.
     *
     * Les quarante-huit octets de parité Reed-Solomon restent à zéro : le
     * décodeur de l'application les ignore, et les remplir demanderait un
     * codeur RS(255,231) pour un signal qui, ici, n'a de toute façon aucune
     * erreur à corriger.
     */
    fun rs41Frame(p: Point, frameNo: Int, serial: String = "S1234567",
                  week: Int = 2380, itowMs: Long = 43_200_000L,
                  batteryTenthV: Int = 27): ByteArray {
        val f = ByteArray(Rs41.LEN_STD)
        for (k in Rs41.HEADER.indices) f[k] = Rs41.HEADER[k].toByte()
        f[Rs41.TYPE_AT] = 0x0F               // type : trame standard
        var pos = Rs41.BLOCKS_AT

        val status = ByteArray(11)
        putU16(status, 0, frameNo and 0xffff)
        for (k in 0 until 8) status[2 + k] = serial.getOrElse(k) { ' ' }.code.toByte()
        status[10] = batteryTenthV.toByte()
        pos = block(f, pos, Rs41.BLK_STATUS, status)

        val time = ByteArray(6)
        putU16(time, 0, week)
        putI32(time, 2, (itowMs + Math.round(p.tSec * 1000.0)).toInt())
        pos = block(f, pos, Rs41.BLK_GPS_TIME, time)

        val ecef = geodeticToEcef(p.lat, p.lon, p.altM)
        val vel = enuToEcefVel(p.lat, p.lon, p.east, p.north, p.up)
        val gps = ByteArray(21)
        putI32(gps, 0, Math.round(ecef[0] * 100.0).toInt())
        putI32(gps, 4, Math.round(ecef[1] * 100.0).toInt())
        putI32(gps, 8, Math.round(ecef[2] * 100.0).toInt())
        putU16(gps, 12, Math.round(vel[0] * 100.0).toInt() and 0xffff)
        putU16(gps, 14, Math.round(vel[1] * 100.0).toInt() and 0xffff)
        putU16(gps, 16, Math.round(vel[2] * 100.0).toInt() and 0xffff)
        gps[18] = p.sats.toByte()
        block(f, pos, Rs41.BLK_GPS_POS, gps)

        // Sur l'air, la trame passe brouillée : c'est cet état-là qu'il faut
        // moduler, sinon le décodeur ne reconnaîtra même pas l'en-tête.
        Rs41.descramble(f)
        return f
    }

    // ------------------------------------------------------------ Meteomodem

    /** Une trame M20 portant ce point de vol. */
    fun m20Frame(p: Point, frameNo: Int, serial: Int = 4321): ByteArray {
        val f = ByteArray(Meteomodem.M20_LEN)
        f[0] = Meteomodem.M20_HEADER[0].toByte()
        f[1] = Meteomodem.M20_HEADER[1].toByte()
        putBe24(f, Meteomodem.M20.ALT, Math.round(p.altM * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VE, Math.round(p.east * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VN, Math.round(p.north * 100.0).toInt())
        putBe16(f, Meteomodem.M20.VU, Math.round(p.up * 100.0).toInt())
        putBe32(f, Meteomodem.M20.LAT, Math.round(p.lat * 1e6).toInt())
        putBe32(f, Meteomodem.M20.LON, Math.round(p.lon * 1e6).toInt())
        putBe16(f, Meteomodem.M20.SERIAL, serial)
        f[Meteomodem.M20.SATS] = p.sats.toByte()
        return f
    }

    /**
     * Une trame M10 portant ce point de vol.
     *
     * Les décalages et les échelles sont ceux qui ont été relevés sur un vrai
     * enregistrement en 18.7 : vitesses au deux-centième de mètre par seconde,
     * coordonnées sur un tour complet en trente-deux bits, altitude en
     * millimètres. La somme de contrôle est calculée en dernier, sinon la mire
     * fabriquerait des trames que son propre décodeur refuserait.
     */
    fun m10Frame(p: Point, frameNo: Int, week: Int = 2380,
                 itowMs: Long = 43_200_000L, serial: Int = 10732): ByteArray {
        val f = ByteArray(Meteomodem.M10_LEN)
        for (k in Meteomodem.M10_HEADER.indices) f[k] = Meteomodem.M10_HEADER[k].toByte()
        putBe16(f, Meteomodem.M10.VE, Math.round(p.east * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VN, Math.round(p.north * 200.0).toInt())
        putBe16(f, Meteomodem.M10.VU, Math.round(p.up * 200.0).toInt())
        putBe32(f, Meteomodem.M10.TOW, (itowMs + Math.round(p.tSec * 1000.0)).toInt())
        putBe32(f, Meteomodem.M10.LAT, Math.round(p.lat * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.LON, Math.round(p.lon * Meteomodem.M10_DEG).toInt())
        putBe32(f, Meteomodem.M10.ALT, Math.round(p.altM * 1000.0).toInt())
        putBe16(f, Meteomodem.M10.WEEK, week)
        // Numéro de série : le même codage que celui que rend le décodeur, de
        // sorte que la mire s'annonce sous un nom lisible et stable.
        f[Meteomodem.M10.SN] = 0x02
        f[Meteomodem.M10.SN + 1] = 0x14
        f[Meteomodem.M10.SN + 2] = 0x83.toByte()
        val v = (2 shl 13) or (serial and 0x1FFF)
        f[Meteomodem.M10.SN + 3] = (v and 0xff).toByte()
        f[Meteomodem.M10.SN + 4] = ((v shr 8) and 0xff).toByte()
        f[Meteomodem.M10.CNT] = (frameNo and 0xff).toByte()
        Meteomodem.stampCheckM10(f)
        return f
    }

    /** La trame du modèle demandé pour ce point. */
    fun frameFor(model: String, p: Point, frameNo: Int): ByteArray = when (model) {
        "M20" -> m20Frame(p, frameNo)
        "M10" -> m10Frame(p, frameNo)
        else -> rs41Frame(p, frameNo)
    }

    // ------------------------------------------------------------ modulation

    /** Les bits d'une suite d'octets, poids faible ou poids fort en tête. */
    fun bitsOf(bytes: ByteArray, lsbFirst: Boolean): ByteArray {
        val out = ByteArray(bytes.size * 8)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            for (j in 0 until 8) {
                out[i * 8 + j] =
                    (if (lsbFirst) (v shr j) and 1 else (v shr (7 - j)) and 1).toByte()
            }
        }
        return out
    }

    /**
     * Codage bi-phase à marque de la M10, l'exact inverse de
     * [Meteomodem.biphase].
     *
     * Le niveau bascule à chaque frontière de bit ; un zéro laisse les deux
     * demi-bits égaux, un un les fait différer. Ce n'est pas du Manchester, et
     * la confusion a coûté trois versions : le codeur Manchester produisait un
     * flux que le nouveau décodeur lit comme une suite ininterrompue de uns.
     */
    fun biphaseEncode(bits: ByteArray, firstChip: Int = 1): ByteArray {
        val out = ByteArray(bits.size * 2)
        var cur = firstChip and 1
        for (i in bits.indices) {
            val b = bits[i].toInt() and 1
            out[2 * i] = cur.toByte()
            val second = cur xor b
            out[2 * i + 1] = second.toByte()
            cur = second xor 1
        }
        return out
    }

    /**
     * Le préambule de la M10 : le motif 1001 répété, qui n'est rien d'autre que
     * le codage bi-phase d'une suite de uns. Les seize premiers demi-bits du
     * motif de synchronisation le prolongent sans rupture, ce qui explique
     * pourquoi la recherche de synchronisation doit regarder les seize suivants
     * pour ne pas se déclencher dans le préambule.
     */
    private fun m10Preamble(n: Int): ByteArray {
        val out = ByteArray(n)
        for (k in 0 until n) out[k] = (if ((k and 3) == 0 || (k and 3) == 3) 1 else 0).toByte()
        return out
    }

    /**
     * La suite de symboles à émettre pour une trame du modèle demandé,
     * préambule d'alternances compris.
     *
     * Le préambule n'est pas décoratif : le démodulateur suit le zéro du
     * discriminateur par une moyenne glissante lente et cale son horloge sur
     * les transitions. Sans une bonne centaine d'alternances pour s'installer,
     * les premiers octets de la trame sortent faux et l'en-tête est manqué.
     * Une vraie sonde émet ce préambule pour la même raison.
     */
    fun chipsFor(model: String, p: Point, frameNo: Int, preamble: Int = 128): ByteArray {
        val frame = frameFor(model, p, frameNo)
        if (model == "M10") {
            // La M10 se cale sur les demi-bits, pas sur les octets : il faut
            // donc émettre son vrai motif de synchronisation, et la trame
            // commence au trente et unième demi-bit de ce motif — le
            // trente-deuxième appartient déjà au corps.
            val head = m10Preamble(M10_PREAMBLE)
            val sync = Meteomodem.M10_SYNC
            val cut = Meteomodem.M10_SYNC_TO_FRAME
            val body = biphaseEncode(bitsOf(frame, lsbFirst = false),
                sync[cut].toInt() and 1)
            val out = ByteArray(head.size + cut + body.size)
            System.arraycopy(head, 0, out, 0, head.size)
            for (k in 0 until cut) out[head.size + k] = sync[k]
            System.arraycopy(body, 0, out, head.size + cut, body.size)
            return out
        }
        val body = when (model) {
            "M20" -> bitsOf(frame, lsbFirst = false)
            else -> bitsOf(frame, lsbFirst = true)
        }
        val out = ByteArray(preamble + body.size)
        for (k in 0 until preamble) out[k] = (k and 1).toByte()
        System.arraycopy(body, 0, out, preamble, body.size)
        return out
    }

    /** Longueur du préambule M10, en demi-bits : ce qu'émet une vraie sonde. */
    const val M10_PREAMBLE = 400

    /** Le débit de symboles du modèle, en symboles par seconde. */
    fun chipRate(model: String): Double = SondeModel.byId(model).let {
        if (it.chipRate > 0.0) it.chipRate else Rs41.BAUD
    }

    /**
     * Le signal de la mire, rendu par tranches.
     *
     * Une mire de cinq minutes fait plus de vingt-cinq mégaoctets de PCM : on
     * ne la garde pas en mémoire, on la fabrique au fil de la lecture, comme la
     * mire SSTV. Chaque seconde du signal porte une trame suivie d'alternances
     * qui comblent le reste — c'est ce que fait une vraie sonde, dont la
     * porteuse ne s'interrompt jamais entre deux trames.
     */
    class Source(
        /** Modèle émis : "RS41", "M20" ou "M10". */
        val model: String,
        /** Point de départ du vol. */
        lat: Double, lon: Double,
        /** Durée voulue, en secondes. */
        val seconds: Int,
        val sampleRate: Int = RATE,
        val amplitude: Int = AMPLITUDE,
        /**
         * Pas de vol demandé, en secondes réelles ; zéro pour le vol comprimé
         * de la démonstration. Le banc de mesure met une seconde, pour que
         * l'horloge de la mire dise la même chose que sa position.
         */
        val stepSec: Double = 0.0,
        /**
         * Ambiance réaliste : souffle et évanouissement, comme une sonde
         * lointaine. Décochée par défaut, et ce n'est pas de la timidité — la
         * mire sert d'abord à éprouver une chaîne, et quand on cherche à savoir
         * si un cordon marche, un signal propre est le seul qui réponde sans
         * ambiguïté. L'ambiance est pour la démonstration en club.
         */
        val ambience: Boolean = false
    ) {
        private val points = flight(lat, lon, maxOf(1, seconds), stepSec = stepSec)
        private val rate = chipRate(model)

        /**
         * Facteur de suréchantillonnage de la synthèse.
         *
         * Le carré était fabriqué directement à 44 100 Hz, en prenant le
         * symbole le plus proche de chaque échantillon. À quatre virgule six
         * échantillons par demi-bit, cela déplace chaque front jusqu'à un demi
         * échantillon au hasard : une gigue de dix pour cent de la durée d'un
         * symbole, entièrement fabriquée par la mire, qui n'existe sur aucune
         * vraie sonde. On synthétise donc huit fois plus vite, on filtre, et
         * l'on décime : les fronts tombent alors où ils doivent, et ils sont
         * arrondis comme le sont ceux d'un vrai discriminateur.
         */
        private val over = 8

        /**
         * Filtre de mise en forme : deux cellules du premier ordre en cascade,
         * coupant à neuf dixièmes du débit de symboles. C'est à peu près ce que
         * rend la chaîne d'une radio : le signal ne saute pas d'un état à
         * l'autre, il y va en un sixième de symbole.
         */
        private val lpA = exp(-2.0 * PI * (rate * 0.9) / (sampleRate.toDouble() * over))
        private var z1 = 0.0
        private var z2 = 0.0

        /** Graine fixe : deux mires identiques doivent l'être au bit près. */
        private val noise = java.util.Random(20_260_731L)

        /** Nombre d'échantillons dans une tranche d'une seconde. */
        private val perSlot = sampleRate

        /** Symboles de la tranche en cours, complétés d'alternances. */
        private var slot = ByteArray(0)
        private var slotIndex = -1
        private var sampleInSlot = 0

        /** Longueur totale du signal, en échantillons. */
        val totalSamples: Int = points.size * perSlot

        private var produced = 0

        /** Avancement de l'émission, de 0 à 1. */
        val progress: Float
            get() = if (totalSamples == 0) 1f else produced.toFloat() / totalSamples

        private fun buildSlot(i: Int) {
            val body = chipsFor(model, points[i], i + 1)
            // Nombre de symboles que dure une seconde à ce débit : la trame
            // occupe le début, les alternances comblent jusqu'au bout.
            val n = Math.round(rate).toInt()
            val out = ByteArray(maxOf(n, body.size))
            System.arraycopy(body, 0, out, 0, body.size)
            if (model == "M10") {
                // Le remplissage reprend le motif du préambule : c'est ce que
                // fait la sonde entre deux trames, et cela évite d'inventer une
                // alternance qui n'existe pas sur l'air.
                val fill = m10Preamble(out.size - body.size)
                System.arraycopy(fill, 0, out, body.size, fill.size)
            } else {
                for (k in body.size until out.size) out[k] = ((k - body.size) and 1).toByte()
            }
            slot = out
            slotIndex = i
            sampleInSlot = 0
        }

        /**
         * L'enveloppe du fading lent, entre un demi et un.
         *
         * Deux périodes incommensurables, l'une de six secondes et demie,
         * l'autre d'une seconde sept : cela suffit à ce que l'oreille n'entende
         * pas de cycle, et à ce que l'écran montre des trames qui tombent puis
         * reviennent, comme sur une sonde qui descend derrière une colline.
         */
        private fun qsb(tSec: Double): Double {
            val slow = 0.55 + 0.45 * (0.5 + 0.5 * cos(2.0 * PI * tSec / 6.5))
            val fast = 0.85 + 0.15 * sin(2.0 * PI * tSec / 1.7)
            return slow * fast
        }

        /**
         * Remplit [chunk] et rend le nombre d'échantillons écrits, ou 0 quand
         * la mire est finie.
         */
        fun read(chunk: ShortArray): Int {
            if (produced >= totalSamples) return 0
            var n = 0
            while (n < chunk.size && produced < totalSamples) {
                val i = produced / perSlot
                if (i != slotIndex) buildSlot(i)
                val posInSlot = produced % perSlot
                // Sur-échantillonnage à huit fois : on regarde le symbole à huit
                // instants dans l'intervalle, on lisse, et on ne garde que le
                // dernier. C'est ce qui arrondit les fronts.
                var v = 0.0
                for (sub in 0 until OVERSAMPLE) {
                    val fine = posInSlot.toLong() * OVERSAMPLE + sub
                    val idx = (fine * slot.size / (perSlot.toLong() * OVERSAMPLE)).toInt()
                    val b = slot[idx.coerceIn(0, slot.size - 1)].toInt()
                    val raw = if (b != 0) amplitude.toDouble() else -amplitude.toDouble()
                    lp1 += alpha * (raw - lp1)
                    lp2 += alpha * (lp1 - lp2)
                    v = lp2
                }
                if (ambience) {
                    // Évanouissement lent, puis souffle. Dans cet ordre : le
                    // bruit d'un récepteur ne s'évanouit pas avec le signal.
                    val t = produced.toDouble() / sampleRate
                    v *= FADE_FLOOR + (1.0 - FADE_FLOOR) *
                        (0.5 + 0.5 * kotlin.math.cos(2.0 * Math.PI * FADE_HZ * t))
                    v += rnd.nextGaussian() * amplitude * NOISE
                }
                chunk[n++] = v.coerceIn(-32_000.0, 32_000.0)
                    .let { Math.round(it).toInt().toShort() }
                produced++
            }
            return n
        }

        /** Les deux cellules passe-bas du premier ordre, en cascade. */
        private var lp1 = 0.0
        private var lp2 = 0.0

        /**
         * Constante des cellules, calée sur 0,9 fois la cadence chip.
         *
         * Conséquence à dire tout haut, parce qu'elle ressemble à une
         * régression et n'en est pas une : la crête ne touche plus tout à fait
         * la consigne d'amplitude. C'est le filtre qui fait son travail. Un
         * essai exige donc désormais que la crête reste *sous* la consigne —
         * sinon le WAV écrête — et *au-dessus de 75 %* — sinon le signal a
         * fondu.
         */
        private val alpha: Double = run {
            val fc = 0.9 * rate
            val fs = sampleRate.toDouble() * OVERSAMPLE
            (1.0 - Math.exp(-2.0 * Math.PI * fc / fs)).coerceIn(1e-4, 0.999)
        }

        /** Graine fixe : une mire doit être reproductible, ambiance comprise. */
        private val rnd = java.util.Random(20_240_907L)
    }

    /**
     * Tout le signal d'un coup, pour les essais et pour la démonstration.
     * À n'employer que sur des durées courtes : une seconde pèse déjà
     * quatre-vingt-huit kilooctets.
     */
    fun render(model: String, lat: Double, lon: Double, seconds: Int,
               ambience: Boolean = false): ShortArray {
        val src = Source(model, lat, lon, seconds, ambience = ambience)
        val out = ShortArray(src.totalSamples)
        val chunk = ShortArray(8192)
        var at = 0
        while (at < out.size) {
            val n = src.read(chunk)
            if (n <= 0) break
            System.arraycopy(chunk, 0, out, at, minOf(n, out.size - at))
            at += n
        }
        return out
    }
}
