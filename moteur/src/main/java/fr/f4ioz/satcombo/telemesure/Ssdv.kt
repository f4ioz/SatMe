/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.meteor.ReedSolomon

/**
 * SSDV, « slow scan digital video »: a JPEG cut into numbered packets of
 * 256 bytes, so that a lost packet costs a few blocks of the picture, not
 * the picture (published format):
 *
 * | bytes | |
 * |---|---|
 * | 0 | sync 0x55 |
 * | 1 | type: 0x66 with Reed-Solomon, 0x67 without |
 * | 2–5 | callsign, base 40, big-endian |
 * | 6 | image number |
 * | 7–8 | packet number |
 * | 9, 10 | width, height, in 16-pixel units |
 * | 11 | flags: quality ⊕ 4 (bits 5–3), last packet (bit 2), sampling (bits 1–0) |
 * | 12 | offset of the first block group (MCU) starting in this packet, 0xFF: none |
 * | 13–14 | that MCU's number, 0xFFFF: none |
 * | 15– | 205 bytes of picture (237 without Reed-Solomon) |
 * | | CRC-32 of bytes 1 to the end of the picture data |
 * | 224–255 | Reed-Solomon (255, 223), CCSDS, conventional basis, over bytes 1–223 |
 *
 * The picture data is the JPEG's scan re-coded with the standard Huffman
 * tables, without its 0xFF 00 stuffing, and the DC values of the first MCU
 * of each packet absolute (not differences): a packet can be read alone.
 * The first MCU of a packet starts on a byte; the bits before it end the
 * previous packet's last MCU. The JPEG is rebuilt from the packets, the
 * missing MCUs left mid-grey.
 */
object Ssdv {
    const val TAILLE = 256
    const val SYNC = 0x55
    const val TYPE_FEC = 0x66
    const val TYPE_SANS_FEC = 0x67
    const val ENTETE = 15
    const val DONNEES_FEC = 205
    const val DONNEES_SANS_FEC = 237

    /** Quality level 0..7 → the JPEG quality the tables are scaled to (4: the tables as printed). */
    val QUALITES = intArrayOf(5, 13, 18, 29, 50, 71, 86, 100)

    /** The luminance sampling of each mode (bits 1–0 of the flags): h × v. */
    val ECHANTILLONNAGE = arrayOf(2 to 2, 1 to 2, 2 to 1, 1 to 1)

    class Paquet(
        val type: Int,
        val indicatif: String,
        val image: Int,
        val numero: Int,
        /** In 16-pixel units. */
        val largeur: Int,
        val hauteur: Int,
        val qualite: Int,
        val dernier: Boolean,
        val mode: Int,
        val mcuDecalage: Int,
        val mcuIndice: Int,
        val donnees: ByteArray,
        /** Bytes put right by Reed-Solomon. */
        val corriges: Int,
        /** The whole packet as read (256 bytes, sync first, repaired), to be kept; empty when not known. */
        val brut: ByteArray = ByteArray(0),
    ) {
        val premiereMcu: Int? get() = if (mcuDecalage == 0xFF || mcuIndice == 0xFFFF) null else mcuIndice
    }

    // ---------------------------------------------------------------- callsign

    /** Up to 6 characters (A–Z, 0–9): base 40, the last character the most significant. */
    fun codeIndicatif(texte: String): Long {
        var x = 0L
        val t = texte.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(6)
        for (c in t.reversed()) {
            x *= 40
            x += if (c in 'A'..'Z') (c - 'A' + 14).toLong() else (c - '0' + 1).toLong()
        }
        return x
    }

    fun indicatif(code: Long): String {
        var x = code
        val sb = StringBuilder()
        while (x > 0 && sb.length < 6) {
            val s = (x % 40).toInt()
            x /= 40
            sb.append(when (s) { in 14..39 -> 'A' + (s - 14); in 1..10 -> '0' + (s - 1); 0 -> ' '; else -> '-' })
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- packets

    private fun crc32(b: ByteArray, debut: Int, n: Int): Long = java.util.zip.CRC32().apply { update(b, debut, n) }.value

    /**
     * A packet from [octets] at [debut]: 256 bytes with the sync, or 255
     * when the sync was left out (some satellites drop it). Reed-Solomon
     * repairs it when the CRC is wrong; null when it stays wrong.
     */
    fun lis(octets: ByteArray, debut: Int = 0): Paquet? {
        val p = ByteArray(TAILLE)
        val reste = octets.size - debut
        when {
            reste >= TAILLE && (octets[debut].toInt() and 0xFF) == SYNC -> System.arraycopy(octets, debut, p, 0, TAILLE)
            reste >= TAILLE - 1 -> { p[0] = SYNC.toByte(); System.arraycopy(octets, debut, p, 1, TAILLE - 1) }
            else -> return null
        }
        val type = p[1].toInt() and 0xFF
        var corriges = 0
        val n = if (type == TYPE_SANS_FEC) DONNEES_SANS_FEC else DONNEES_FEC
        if (!crcJuste(p, n)) {
            // The type byte itself may be the error: the code covers it.
            val c = IntArray(255) { p[it + 1].toInt() and 0xFF }
            val r = ReedSolomon.corrige(c)
            if (r <= 0) return null
            for (i in 0 until 255) p[i + 1] = c[i].toByte()
            if ((p[1].toInt() and 0xFF) != TYPE_FEC || !crcJuste(p, DONNEES_FEC)) return null
            corriges = r
        } else if (type != TYPE_FEC && type != TYPE_SANS_FEC) return null
        return analyse(p, corriges)
    }

    private fun crcJuste(p: ByteArray, n: Int): Boolean {
        val fin = ENTETE + n
        val lu = ((p[fin].toLong() and 0xFF) shl 24) or ((p[fin + 1].toLong() and 0xFF) shl 16) or
            ((p[fin + 2].toLong() and 0xFF) shl 8) or (p[fin + 3].toLong() and 0xFF)
        return crc32(p, 1, fin - 1) == lu
    }

    private fun analyse(p: ByteArray, corriges: Int): Paquet {
        fun u(i: Int) = p[i].toInt() and 0xFF
        val type = u(1)
        val n = if (type == TYPE_SANS_FEC) DONNEES_SANS_FEC else DONNEES_FEC
        val drapeaux = u(11)
        return Paquet(type, indicatif((u(2).toLong() shl 24) or (u(3).toLong() shl 16) or (u(4).toLong() shl 8) or u(5).toLong()),
            u(6), (u(7) shl 8) or u(8), u(9), u(10), ((drapeaux ushr 3) and 7) xor 4, drapeaux and 4 != 0, drapeaux and 3,
            u(12), (u(13) shl 8) or u(14), p.copyOfRange(ENTETE, ENTETE + n), corriges, p.copyOf())
    }

    /** The SSDV packets inside a frame's bytes (an AX.25 information field, or a frame of its own). */
    fun cherche(octets: ByteArray): List<Paquet> {
        val l = ArrayList<Paquet>()
        var i = 0
        while (i + TAILLE - 1 <= octets.size) {
            val b0 = octets[i].toInt() and 0xFF
            val b1 = if (i + 1 < octets.size) octets[i + 1].toInt() and 0xFF else -1
            val p = when {
                b0 == SYNC && (b1 == TYPE_FEC || b1 == TYPE_SANS_FEC) -> lis(octets, i)
                (b0 == TYPE_FEC || b0 == TYPE_SANS_FEC) && (i == 0 || octets[i - 1].toInt() and 0xFF != SYNC) -> lis(octets, i)
                else -> null
            }
            if (p != null) { l += p; i += if (b0 == SYNC) TAILLE else TAILLE - 1 } else i++
        }
        return l
    }

    // ---------------------------------------------------------------- coding

    fun tables(qualite: Int): Pair<IntArray, IntArray> {
        val q = QUALITES[qualite.coerceIn(0, 7)]
        return Jpeg.echelle(Jpeg.Q_LUMINANCE, q) to Jpeg.echelle(Jpeg.Q_CHROMINANCE, q)
    }

    /**
     * Packets from MCUs already quantised with [tables] ([qualite]): each
     * MCU hs·vs luminance blocks then Cb then Cr, zig-zag order, DC absolute.
     * [largeur], [hauteur] in pixels, multiples of 16.
     */
    fun paquets(mcus: List<Array<IntArray>>, largeur: Int, hauteur: Int, indicatif: String, image: Int,
                qualite: Int = 4, mode: Int = 0, fec: Boolean = true, premier: Int = 0): List<ByteArray> {
        require(largeur % 16 == 0 && hauteur % 16 == 0 && largeur in 16..4080 && hauteur in 16..4080)
        val n = if (fec) DONNEES_FEC else DONNEES_SANS_FEC
        val sortie = ArrayList<ByteArray>()
        var donnees = ByteArray(n); var pos = 0
        var mcuDecalage = 0xFF; var mcuIndice = 0xFFFF
        fun ferme(dernier: Boolean) {
            val p = ByteArray(TAILLE)
            p[0] = SYNC.toByte(); p[1] = (if (fec) TYPE_FEC else TYPE_SANS_FEC).toByte()
            val c = codeIndicatif(indicatif)
            for (k in 0 until 4) p[2 + k] = (c ushr (24 - 8 * k)).toByte()
            p[6] = image.toByte()
            val num = premier + sortie.size
            p[7] = (num ushr 8).toByte(); p[8] = num.toByte()
            p[9] = (largeur / 16).toByte(); p[10] = (hauteur / 16).toByte()
            p[11] = ((((qualite xor 4) and 7) shl 3) or (if (dernier) 4 else 0) or (mode and 3)).toByte()
            p[12] = mcuDecalage.toByte(); p[13] = (mcuIndice ushr 8).toByte(); p[14] = mcuIndice.toByte()
            System.arraycopy(donnees, 0, p, ENTETE, n)
            val crc = crc32(p, 1, ENTETE + n - 1)
            for (k in 0 until 4) p[ENTETE + n + k] = (crc ushr (24 - 8 * k)).toByte()
            if (fec) {
                val par = ReedSolomon.code(IntArray(223) { p[it + 1].toInt() and 0xFF })
                for (k in 0 until 32) p[224 + k] = par[k].toByte()
            }
            sortie += p
            donnees = ByteArray(n); pos = 0; mcuDecalage = 0xFF; mcuIndice = 0xFFFF
        }
        val w = Jpeg.Ecrivain(false) { o ->
            if (pos == n) ferme(false)
            donnees[pos++] = o.toByte()
        }
        val (hs, vs) = ECHANTILLONNAGE[mode and 3]
        val ny = hs * vs
        val dc = IntArray(3)
        mcus.forEachIndexed { m, blocs ->
            if (mcuIndice == 0xFFFF) {
                // The packet's first MCU: on a byte, its DC values absolute.
                w.aligne()
                if (pos == n) ferme(false)
                mcuDecalage = pos; mcuIndice = m
                dc.fill(0)
            }
            for (i in blocs.indices) {
                val c = if (i < ny) 0 else i - ny + 1
                val (dt, at) = if (c == 0) Jpeg.DC_LUMINANCE to Jpeg.AC_LUMINANCE else Jpeg.DC_CHROMINANCE to Jpeg.AC_CHROMINANCE
                Jpeg.ecrisBloc(w, blocs[i], dc[c], dt, at)
                dc[c] = blocs[i][0]
            }
        }
        w.aligne()
        for (k in pos until n) donnees[k] = 0xFF.toByte()
        pos = n
        ferme(true)
        return sortie
    }

    /** A picture ([rgb] 0xRRGGBB, [largeur] × [hauteur], multiples of 16) as packets. */
    fun codeImage(rgb: IntArray, largeur: Int, hauteur: Int, indicatif: String, image: Int, qualite: Int = 4, mode: Int = 0, fec: Boolean = true): List<ByteArray> {
        val (qy, qc) = tables(qualite)
        val (hs, vs) = ECHANTILLONNAGE[mode and 3]
        val mw = 8 * hs; val mh = 8 * vs
        val mcus = ArrayList<Array<IntArray>>()
        val bloc = DoubleArray(64)
        fun comp(x: Int, y: Int, c: Int): Double {
            val p = rgb[y.coerceIn(0, hauteur - 1) * largeur + x.coerceIn(0, largeur - 1)]
            val r = (p ushr 16 and 0xFF).toDouble(); val g = (p ushr 8 and 0xFF).toDouble(); val b = (p and 0xFF).toDouble()
            return when (c) {
                0 -> 0.299 * r + 0.587 * g + 0.114 * b
                1 -> -0.168736 * r - 0.331264 * g + 0.5 * b + 128
                else -> 0.5 * r - 0.418688 * g - 0.081312 * b + 128
            }
        }
        for (my in 0 until hauteur / mh) for (mx in 0 until largeur / mw) {
            val blocs = ArrayList<IntArray>()
            for (by in 0 until vs) for (bx in 0 until hs) {
                for (k in 0 until 64) bloc[k] = comp(mx * mw + bx * 8 + k % 8, my * mh + by * 8 + k / 8, 0) - 128
                blocs += Jpeg.dct(bloc, qy)
            }
            for (c in 1..2) {
                // Chroma averaged over hs × vs pixels.
                for (k in 0 until 64) {
                    var s = 0.0
                    for (dy in 0 until vs) for (dx in 0 until hs) s += comp(mx * mw + (k % 8) * hs + dx, my * mh + (k / 8) * vs + dy, c)
                    bloc[k] = s / (hs * vs) - 128
                }
                blocs += Jpeg.dct(bloc, qc)
            }
            mcus += blocs.toTypedArray()
        }
        return paquets(mcus, largeur, hauteur, indicatif, image, qualite, mode, fec)
    }

    // ---------------------------------------------------------------- one picture, rebuilt

    /** The packets of one picture (one callsign, one image number), and the JPEG they give. */
    class Image(val indicatif: String, val numero: Int) {
        private val paquets = java.util.TreeMap<Int, Paquet>()
        var largeur = 0; private set
        var hauteur = 0; private set
        var mode = 0; private set
        var qualite = 4; private set
        /** The last packet's number, once it came. */
        var dernier: Int? = null; private set
        var mcusLues = 0; private set
        var mcusTotal = 0; private set
        /** When a packet last came (ms). */
        var miseAJour = 0L

        val recus: Int get() = paquets.size
        val numeros: Set<Int> get() = paquets.keys

        /** Packet [n], when it came. */
        @Synchronized
        fun paquet(n: Int): Paquet? = paquets[n]

        /** The packets' numbers, a copy. */
        @Synchronized
        fun listeNumeros(): List<Int> = paquets.keys.toList()

        /** The highest first-MCU index among the packets, and the picture's MCU count (an estimate of the whole while the last is missing). */
        @Synchronized
        fun avancementMcu(): Pair<Int, Int>? {
            if (paquets.isEmpty()) return null
            val (hs, vs) = ECHANTILLONNAGE[mode]
            val total = (largeur / (8 * hs)) * (hauteur / (8 * vs))
            val m = paquets.values.mapNotNull { it.premiereMcu }.maxOrNull() ?: return null
            return m to total
        }

        /** Adds a packet; false when it was there already or does not belong (another size). */
        @Synchronized
        fun ajoute(p: Paquet): Boolean {
            if (paquets.isEmpty()) { largeur = p.largeur * 16; hauteur = p.hauteur * 16; mode = p.mode; qualite = p.qualite }
            else if (p.largeur * 16 != largeur || p.hauteur * 16 != hauteur || p.mode != mode) return false
            if (paquets.containsKey(p.numero)) return false
            paquets[p.numero] = p
            if (p.dernier) dernier = p.numero
            return true
        }

        /** Packets missing between the first number and the last (known or seen). */
        @Synchronized
        fun manquants(): List<Int> {
            if (paquets.isEmpty()) return emptyList()
            val fin = dernier ?: paquets.lastKey()
            return (0..fin).filter { it !in paquets }
        }

        private inner class Lecteur(var paquet: Int, var bit: Int) : Jpeg.Bits {
            override fun bit(): Int {
                var p = paquets[paquet] ?: throw Jpeg.Fin()
                if (bit >= p.donnees.size * 8) {
                    paquet++; bit = 0
                    p = paquets[paquet] ?: throw Jpeg.Fin()
                }
                val v = (p.donnees[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
                bit++
                return v
            }
        }

        /** The JPEG with what has come; missing MCUs mid-grey. Null before the first packet. */
        @Synchronized
        fun jpeg(): ByteArray? {
            if (paquets.isEmpty()) return null
            val (hs, vs) = ECHANTILLONNAGE[mode]
            val ny = hs * vs
            val total = (largeur / (8 * hs)) * (hauteur / (8 * vs))
            val debuts = HashMap<Int, Paquet>()
            for (p in paquets.values) p.premiereMcu?.let { if (it < total) debuts[it] = p }
            val gris = Array(ny + 2) { IntArray(64) }
            val mcus = ArrayList<Array<IntArray>>(total)
            var lecteur: Lecteur? = null
            val dc = IntArray(3)
            var lues = 0
            for (m in 0 until total) {
                debuts[m]?.let { p -> lecteur = Lecteur(p.numero, p.mcuDecalage * 8); dc.fill(0) }
                val l = lecteur
                if (l == null) { mcus += gris; continue }
                val blocs = runCatching {
                    Array(ny + 2) { i ->
                        val c = if (i < ny) 0 else i - ny + 1
                        val (dt, at) = if (c == 0) Jpeg.DC_LUMINANCE to Jpeg.AC_LUMINANCE else Jpeg.DC_CHROMINANCE to Jpeg.AC_CHROMINANCE
                        Jpeg.lisBloc(l, dt, at, dc[c]).also { dc[c] = it[0] }
                    }
                }.getOrNull()
                if (blocs == null) { lecteur = null; mcus += gris } else { mcus += blocs; lues++ }
            }
            mcusLues = lues; mcusTotal = total
            val (qy, qc) = tables(qualite)
            return Jpeg.fichier(largeur, hauteur, hs, vs, qy, qc, mcus)
        }
    }
}
