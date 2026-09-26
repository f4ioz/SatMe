/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

/**
 * Décodage des trames Vaisala RS41, la sonde la plus répandue en Europe.
 *
 * La RS41 émet en GFSK à 4800 bits par seconde, octets poids faible en tête.
 * La trame entière est brouillée par un OU exclusif avec un masque de
 * soixante-quatre octets qui se répète, appliqué dès le premier octet — en-tête
 * compris. On cherche donc l'en-tête *brouillé* dans le flux : c'est une
 * constante, et cela évite de désembrouiller tout ce qui passe pour découvrir
 * ensuite que ce n'était pas une sonde.
 *
 * Une fois désembrouillée, la trame se lit comme une suite de blocs
 * indépendants, chacun protégé par son propre CRC. Les quarante-huit octets de
 * parité Reed-Solomon qui suivent l'en-tête sont volontairement ignorés :
 * corriger les erreurs demanderait un décodeur RS(255,231) complet pour un gain
 * qui ne se voit que sur les trames déjà à moitié perdues, alors qu'un CRC par
 * bloc suffit à ne jamais afficher une position fausse. Mieux vaut sauter une
 * trame que mentir sur une coordonnée.
 */
object Rs41 {

    /** Débit binaire, en bits par seconde. */
    const val BAUD = 4800.0

    /** Largeur de filtre conseillée pour la démodulation, en hertz. */
    const val BANDWIDTH_HZ = 15_000

    /** Masque de désembrouillage, répété tous les soixante-quatre octets. */
    val MASK = intArrayOf(
        0x96, 0x83, 0x3E, 0x51, 0xB1, 0x49, 0x08, 0x98,
        0x32, 0x05, 0x59, 0x0E, 0xF9, 0x44, 0xC6, 0x26,
        0x21, 0x60, 0xC2, 0xEA, 0x79, 0x5D, 0x6D, 0xA1,
        0x54, 0x69, 0x47, 0x0C, 0xDC, 0xE8, 0x5C, 0xF1,
        0xF7, 0x76, 0x82, 0x7F, 0x07, 0x99, 0xA2, 0x2C,
        0x93, 0x7C, 0x30, 0x63, 0xF5, 0x10, 0x2E, 0x61,
        0xD0, 0xBC, 0xB4, 0xB6, 0x06, 0xAA, 0xF4, 0x23,
        0x78, 0x6E, 0x3B, 0xAE, 0xBF, 0x7B, 0x4C, 0xC1)

    /**
     * En-tête tel qu'il passe sur l'air.
     *
     * C'est bien celui-ci qui est émis tel quel : la RS41 envoie son en-tête en
     * clair, et le brouillage ne se voit que sur la suite. La version 18.6 a
     * corrigé une inversion des deux constantes — la mire et le décodeur
     * s'accordaient entre eux sur la convention inverse, si bien que les essais
     * passaient tous et qu'aucune sonde réelle n'était jamais reconnue. Les
     * enregistrements de référence de radiosonde_auto_rx ont tranché : cent
     * vingt en-têtes parfaits en cent vingt secondes avec celui-ci, aucun avec
     * l'autre.
     */
    val HEADER_RAW = intArrayOf(0x10, 0xB6, 0xCA, 0x11, 0x22, 0x96, 0x12, 0xF8)

    /** Le même en-tête une fois la trame désembrouillée. */
    val HEADER = IntArray(HEADER_RAW.size) { HEADER_RAW[it] xor MASK[it] }

    /** Longueur d'une trame standard, en octets (type 0x0F). */
    const val LEN_STD = 320

    /** Longueur d'une trame étendue, en octets (type 0xF0). */
    const val LEN_EXT = 518

    /**
     * Octet de type, juste avant les blocs.
     *
     * Il vient après les quarante-huit octets de parité Reed-Solomon, et non
     * juste après l'en-tête : la 18.5 le lisait en neuvième position, c'est-à-dire
     * au milieu de la parité, donc une valeur au hasard à chaque trame. Aucune
     * sonde réelle ne passait ce test.
     */
    const val TYPE_AT = 0x38

    /** Premier octet des blocs de données, juste après la parité Reed-Solomon. */
    const val BLOCKS_AT = 0x39

    /** Bloc d'état : numéro de trame, numéro de série, tension de la pile. */
    const val BLK_STATUS = 0x79

    /** Bloc GPS « temps » : semaine et temps dans la semaine. */
    const val BLK_GPS_TIME = 0x7A

    /** Bloc GPS « position » : coordonnées et vitesse en ECEF. */
    const val BLK_GPS_POS = 0x7B

    private fun b(f: ByteArray, i: Int) = f[i].toInt() and 0xff

    /** Entier seize bits non signé, poids faible en tête. */
    fun u16(f: ByteArray, i: Int) = b(f, i) or (b(f, i + 1) shl 8)

    /** Entier seize bits signé, poids faible en tête. */
    fun i16(f: ByteArray, i: Int): Int {
        val v = u16(f, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /** Entier trente-deux bits signé, poids faible en tête. */
    fun i32(f: ByteArray, i: Int): Int =
        b(f, i) or (b(f, i + 1) shl 8) or (b(f, i + 2) shl 16) or (b(f, i + 3) shl 24)

    /** Entier trente-deux bits non signé, poids faible en tête. */
    fun u32(f: ByteArray, i: Int): Long = i32(f, i).toLong() and 0xFFFF_FFFFL

    /**
     * CRC-16-CCITT, polynôme 0x1021, registre initialisé à 0xFFFF, sans
     * inversion finale. C'est celui que la RS41 range en fin de bloc, poids
     * faible en tête.
     */
    fun crc16(data: ByteArray, off: Int, len: Int): Int {
        var crc = 0xFFFF
        for (k in off until off + len) {
            crc = crc xor ((data[k].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                      else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /** Applique (ou retire, c'est la même opération) le masque de brouillage. */
    fun descramble(frame: ByteArray) {
        for (k in frame.indices) {
            frame[k] = (frame[k].toInt() xor MASK[k % MASK.size]).toByte()
        }
    }

    /**
     * Cherche l'en-tête brouillé dans un tampon d'octets déjà alignés.
     * Rend l'indice du premier octet de la trame, ou -1.
     */
    fun findHeader(buf: ByteArray, from: Int = 0, to: Int = buf.size): Int {
        val last = to - HEADER_RAW.size
        var i = from
        while (i <= last) {
            var ok = true
            for (k in HEADER_RAW.indices) {
                if ((buf[i + k].toInt() and 0xff) != HEADER_RAW[k]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /** Longueur annoncée par l'octet de type, ou 0 si le type est inconnu. */
    fun frameLength(typeByte: Int): Int = when (typeByte and 0xff) {
        0x0F -> LEN_STD
        0xF0 -> LEN_EXT
        else -> 0
    }

    /** Un bloc reconnu dans la trame. */
    data class Block(val id: Int, val at: Int, val len: Int, val crcOk: Boolean)

    /**
     * Parcourt les blocs d'une trame désembrouillée.
     *
     * On avance de bloc en bloc en se fiant à la longueur annoncée, et on
     * s'arrête dès qu'elle ne tient plus dans la trame : sur une trame abîmée,
     * un octet de longueur farfelu enverrait la lecture n'importe où.
     */
    fun blocks(frame: ByteArray): List<Block> {
        val out = ArrayList<Block>(8)
        var pos = BLOCKS_AT
        while (pos + 4 <= frame.size) {
            val id = b(frame, pos)
            val len = b(frame, pos + 1)
            if (id == 0 && len == 0) break
            val data = pos + 2
            val crcAt = data + len
            if (crcAt + 2 > frame.size) break
            val want = u16(frame, crcAt)
            val got = crc16(frame, data, len)
            out += Block(id, data, len, want == got)
            pos = crcAt + 2
        }
        return out
    }

    /**
     * Décode une trame complète et désembrouillée.
     *
     * Rend null si l'en-tête ne correspond pas, ou si aucun bloc de position
     * valide n'a été trouvé : sans coordonnées, la trame n'apprend rien à un
     * chasseur, autant ne pas encombrer le journal.
     */
    fun parse(frame: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (frame.size < BLOCKS_AT + 4) return null
        for (k in HEADER.indices) if (b(frame, k) != HEADER[k]) return null

        var serial = ""
        var frameNo = 0
        var battery = 0.0
        var week = 0
        var itow = 0L
        var haveTime = false
        var fix: Geo.Fix? = null
        var enu: Geo.Enu? = null
        var sats = 0

        for (blk in blocks(frame)) {
            if (!blk.crcOk) continue
            when (blk.id) {
                BLK_STATUS -> if (blk.len >= 11) {
                    frameNo = u16(frame, blk.at)
                    val sb = StringBuilder()
                    for (k in 0 until 8) {
                        val c = b(frame, blk.at + 2 + k)
                        if (c in 0x20..0x7E) sb.append(c.toChar())
                    }
                    serial = sb.toString().trim()
                    battery = b(frame, blk.at + 10) / 10.0
                }
                BLK_GPS_TIME -> if (blk.len >= 6) {
                    week = u16(frame, blk.at)
                    itow = u32(frame, blk.at + 2)
                    haveTime = true
                }
                BLK_GPS_POS -> if (blk.len >= 21) {
                    // Les coordonnées sont en centimètres, les vitesses en
                    // centimètres par seconde : la sonde compte plus fin que ce
                    // dont on a besoin, on ramène tout en unités du système.
                    val x = i32(frame, blk.at).toDouble() / 100.0
                    val y = i32(frame, blk.at + 4).toDouble() / 100.0
                    val z = i32(frame, blk.at + 8).toDouble() / 100.0
                    if (x == 0.0 && y == 0.0 && z == 0.0) continue
                    val f = Geo.ecefToGeodetic(x, y, z)
                    val vx = i16(frame, blk.at + 12) / 100.0
                    val vy = i16(frame, blk.at + 14) / 100.0
                    val vz = i16(frame, blk.at + 16) / 100.0
                    fix = f
                    enu = Geo.ecefVelToEnu(f.lat, f.lon, vx, vy, vz)
                    sats = b(frame, blk.at + 18)
                }
            }
        }

        val f = fix ?: return null
        val v = enu ?: Geo.Enu(0.0, 0.0, 0.0)
        val timeMs = if (haveTime && week > 0) Geo.gpsToUnixMs(week, itow) else 0L
        val out = SondeFrame(
            type = "RS41",
            serial = serial,
            frameNo = frameNo,
            timeUtcMs = timeMs,
            lat = f.lat, lon = f.lon, altM = f.altM,
            speedMps = v.groundMps,
            headingDeg = v.headingDeg,
            climbMps = v.up,
            sats = sats,
            batteryV = battery,
            freqHz = freqHz,
            heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /**
     * Cherche, désembrouille et décode la première trame trouvée dans un
     * tampon. Rend la trame décodée et l'indice du premier octet qui suit,
     * pour que l'appelant sache où reprendre.
     */
    data class Hit(val frame: SondeFrame, val nextIndex: Int)

    fun scan(buf: ByteArray, from: Int, to: Int, freqHz: Long = 0L, nowMs: Long = 0L): Hit? {
        var at = from
        while (true) {
            val h = findHeader(buf, at, to)
            if (h < 0) return null
            // Le type suit la parité Reed-Solomon, et il est encore brouillé.
            val typeAt = h + TYPE_AT
            if (typeAt >= to) return null
            val type = (buf[typeAt].toInt() xor MASK[TYPE_AT % MASK.size]) and 0xff
            val len = frameLength(type)
            if (len == 0 || h + len > to) { at = h + 1; continue }
            val f = buf.copyOfRange(h, h + len)
            descramble(f)
            val parsed = parse(f, freqHz, nowMs)
            if (parsed != null) return Hit(parsed, h + len)
            at = h + 1
        }
    }
}
