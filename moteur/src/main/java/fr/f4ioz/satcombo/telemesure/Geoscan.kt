/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Geoscan satellites' frames (Geoscan-2/4/5, 239Alferov…: GFSK 9600
 * bit/s on 435–437 MHz) and the pictures they carry, written from the
 * public descriptions of their format (the community's decoders and
 * SatNOGS DB), receive only:
 *
 * - a TI CC1125-like radio: the sync word **0x930B51DE**, a fixed frame of
 *   **74 bytes** whitened by **PN9** (x⁹ + x⁵ + 1 from 0x1FF, reset each
 *   frame), the last two a **CRC-16** (0x8005, from 0xFFFF, not reflected)
 *   over the 72 before, computed before the whitening;
 * - a picture is **not SSDV** but a JPEG file cut in chunks, each with its
 *   byte offset in the satellite's memory: v2 (the recent satellites) a
 *   marker « 1oko » (31 6F 6B 6F), the offset (32 bits), the file's number
 *   (16 bits), 54 bytes of the JPEG; v1 (older ones) a message type, a
 *   24-bit offset and 56 bytes. The file starts at the chunk beginning
 *   with FF D8 and ends at the one holding FF D9.
 *
 * Only checked on signals made here from that description: what a real
 * pass needs is said in the docs (a recording of the IQ).
 */
object Geoscan {

    const val SYNC = 0x930B51DEL
    const val TAILLE = 74
    const val DONNEES = TAILLE - 2
    /** Bit errors allowed in the sync word. */
    const val TOLERANCE_SYNC = 3

    /** Their number in the frames → name. */
    val SATELLITES = mapOf(0x01 to "Geoscan-Edelveis", 0x02 to "StratoSat", 0x03 to "Horizon", 0x04 to "RTU-MIREA-1", 0x05 to "TUSUR-GO",
        0x06 to "Colibri-S", 0x07 to "Vizard-ion", 0x09 to "239Alferov", 0x0A to "InnoSat-3", 0x0B to "Geoscan-1", 0x0C to "Geoscan-2",
        0x0D to "Geoscan-3", 0x0E to "Geoscan-4", 0x0F to "Geoscan-5", 0x10 to "Geoscan-6", 0x11 to "InnoSat-16", 0x12 to "Lobachevsky")

    /** The PN9 sequence's [n] first bytes (FF E1 1D 9A ED 85 33 24…). */
    fun pn9(n: Int): ByteArray {
        var s = 0x1FF
        return ByteArray(n) {
            val o = s and 0xFF
            repeat(8) { val b = (s xor (s ushr 5)) and 1; s = (s ushr 1) or (b shl 8) }
            o.toByte()
        }
    }
    private val PN9 = pn9(TAILLE)

    fun blanchit(b: ByteArray): ByteArray = ByteArray(b.size) { (b[it].toInt() xor PN9[it % TAILLE].toInt()).toByte() }

    /** CRC-16 as the CC11xx compute it: 0x8005, from 0xFFFF, MSB first, no final xor. */
    fun crc16(b: ByteArray, n: Int = b.size): Int {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** A frame on the air (the generator, the tests): [donnees] (72 bytes) with its CRC, whitened, the sync before. */
    fun code(donnees: ByteArray): ByteArray {
        require(donnees.size == DONNEES)
        val crc = crc16(donnees)
        val clair = donnees + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
        return byteArrayOf((SYNC ushr 24).toByte(), (SYNC ushr 16).toByte(), (SYNC ushr 8).toByte(), SYNC.toByte()) + blanchit(clair)
    }

    /** A v2 picture chunk (72 bytes): satellite [sat], file [fichier], [offset], 54 bytes of [jpeg] (padded). */
    fun morceauV2(sat: Int, fichier: Int, offset: Long, jpeg: ByteArray): ByteArray {
        val b = ByteArray(DONNEES)
        b[0] = sat.toByte(); b[2] = 0x3C
        byteArrayOf(0x31, 0x6F, 0x6B, 0x6F).copyInto(b, 5)
        for (k in 0 until 4) b[9 + k] = (offset ushr (8 * k)).toByte()
        b[13] = fichier.toByte(); b[14] = (fichier ushr 8).toByte()
        jpeg.copyInto(b, 15, 0, minOf(54, jpeg.size))
        return b
    }

    /** [jpeg] as the v2 frames on the air (72 bytes each, before [code]), from offset [base]. */
    fun morceaux(sat: Int, fichier: Int, jpeg: ByteArray, base: Long = 0x10000L): List<ByteArray> =
        (jpeg.indices step 54).map { k -> morceauV2(sat, fichier, base + k, jpeg.copyOfRange(k, minOf(jpeg.size, k + 54))) }

    /** Frames as bits (MSB first), each after 8 bytes of 0xAA preamble. */
    fun bits(trames: List<ByteArray>): IntArray {
        val o = trames.flatMap { (ByteArray(8) { 0xAA.toByte() } + code(it)).toList() }.toByteArray()
        return IntArray(o.size * 8) { (o[it / 8].toInt() ushr (7 - it % 8)) and 1 }
    }

    /** A small test picture as a JPEG (the SSDV test chart decoded), for the generator. */
    fun jpegEssai(): ByteArray {
        val im = Ssdv.Image(SignalPaquets.INDICATIF, 0)
        Ssdv.codeImage(SignalPaquets.mire(160, 112), 160, 112, SignalPaquets.INDICATIF, 0).forEach { b -> Ssdv.lis(b)?.let { im.ajoute(it) } }
        return im.jpeg() ?: ByteArray(0)
    }

    // ---------------------------------------------------------------- frames in the bits

    /**
     * Frames in a bit stream (MSB first): the sync word with up to
     * [TOLERANCE_SYNC] bits wrong, in either polarity; the 74 bytes after it
     * de-whitened, kept when the CRC is right (the 72 bytes to [surTrame]).
     */
    class Deframeur(private val surTrame: (ByteArray) -> Unit) {
        private class Lecture(val inverse: Boolean) { var reg = 0L; var n = -1; var bit = 0; var cour = 0; val o = ByteArray(TAILLE) }
        private val lectures = listOf(Lecture(false), Lecture(true))
        var trames = 0; private set
        var crcFaux = 0; private set

        fun bit(b: Int) { for (l in lectures) lis(l, if (l.inverse) b xor 1 else b) }

        private fun lis(l: Lecture, x: Int) {
            if (l.n < 0) {
                l.reg = ((l.reg shl 1) or x.toLong()) and 0xFFFFFFFFL
                if (java.lang.Long.bitCount(l.reg xor SYNC) <= TOLERANCE_SYNC) { l.n = 0; l.bit = 0; l.cour = 0 }
                return
            }
            l.cour = (l.cour shl 1) or x
            if (++l.bit < 8) return
            l.o[l.n++] = l.cour.toByte(); l.bit = 0; l.cour = 0
            if (l.n < TAILLE) return
            l.n = -1; l.reg = 0
            val c = blanchit(l.o)
            val lu = ((c[DONNEES].toInt() and 0xFF) shl 8) or (c[DONNEES + 1].toInt() and 0xFF)
            if (crc16(c, DONNEES) == lu) { trames++; surTrame(c.copyOf(DONNEES)) }
            else crcFaux++
        }
    }

    // ---------------------------------------------------------------- picture chunks

    /** A picture's chunk read from a frame: satellite, file (−1: v1, not numbered), offset, bytes, v1's « start » flag. */
    class Morceau(val sat: Int, val fichier: Int, val offset: Long, val octets: ByteArray, val debutV1: Boolean = false)

    /** The chunk in a frame's 72 bytes, or null (a beacon, telemetry). */
    fun morceau(d: ByteArray): Morceau? {
        if (d.size < DONNEES) return null
        fun u(i: Int) = d[i].toInt() and 0xFF
        // AX.25 beacon (« BEACON »): not a picture.
        if (u(0) == 0x84 && u(1) == 0x8A) return null
        if (u(5) == 0x31 && u(6) == 0x6F && u(7) == 0x6B && u(8) == 0x6F) {
            val off = u(9).toLong() or (u(10).toLong() shl 8) or (u(11).toLong() shl 16) or (u(12).toLong() shl 24)
            return Morceau(u(0), u(13) or (u(14) shl 8), off, d.copyOfRange(15, 15 + 54))
        }
        val type = u(3) or (u(4) shl 8)
        if (type in setOf(0x0901, 0x0905, 0x0920, 0x9820, 0x411C)) {
            val n = (u(2) - 6).coerceIn(0, DONNEES - 8)
            val off = u(5).toLong() or (u(6).toLong() shl 8) or (u(7).toLong() shl 16)
            return Morceau(u(0), -1, off, d.copyOfRange(8, 8 + n), debutV1 = type == 0x0901)
        }
        return null
    }

    /**
     * The pictures put together from their chunks (receive only): per
     * satellite and file, each chunk at its offset from the one starting
     * with FF D8; finished once FF D9 came and nothing is missing before
     * it. Written to [dossier]`/telemesure/geoscan` (finished pictures as
     * `.jpg`, the chunks of the ones under way as lines of hex).
     */
    class Images(private val dossier: File?, private val horloge: () -> Long = System::currentTimeMillis) {
        class Image(val id: String, val sat: Int, val fichier: Int, val debut: Long) {
            /** Offset → bytes, before the start is known as well. */
            val morceaux = java.util.TreeMap<Long, ByteArray>()
            var base: Long? = null
            var miseAJour = debut
            var satellite = ""
            val nomSat: String get() = SATELLITES[sat] ?: satellite.ifBlank { "Geoscan #$sat" }

            /** The JPEG's bytes from its start to the last chunk received (holes as zeros); null before FF D8 came. */
            fun jpeg(): ByteArray? {
                val b = base ?: return null
                val l = morceaux.tailMap(b)
                if (l.isEmpty()) return null
                val fin = l.entries.maxOf { it.key + it.value.size } - b
                val o = ByteArray(fin.toInt().coerceAtMost(4 * 1024 * 1024))
                for ((k, v) in l) { val p = (k - b).toInt(); if (p + v.size <= o.size) v.copyInto(o, p) }
                // Cut after the end marker when it came.
                val f = finJpeg(o)
                return if (f > 0) o.copyOf(f) else o
            }

            /** Bytes missing between the start and the last chunk (or the end marker). */
            fun manquants(): Int {
                val b = base ?: return -1
                var attendu = b; var trous = 0L
                for ((k, v) in morceaux.tailMap(b)) { if (k > attendu) trous += k - attendu; attendu = maxOf(attendu, k + v.size) }
                return trous.toInt()
            }
            val complete: Boolean get() = base != null && manquants() == 0 && jpeg()?.let { finJpeg(it) > 0 } == true
            val octets: Int get() = morceaux.values.sumOf { it.size }
        }

        private val images = LinkedHashMap<String, Image>()
        @Volatile var surNouveau: ((Image) -> Unit)? = null

        @Synchronized fun vide() = images.clear()
        @Synchronized fun liste(): List<Image> = images.values.sortedByDescending { it.miseAJour }
        @Synchronized fun image(id: String): Image? = images[id]

        /** A chunk received at [t] from [satellite] (the one followed). */
        @Synchronized fun ajoute(m: Morceau, t: Long = horloge(), satellite: String = ""): Image {
            // The same file of the same satellite within a day; v1 (no number): the last one, unless it says « start ».
            var im = images.values.filter { it.sat == m.sat && it.fichier == m.fichier && t - it.miseAJour < 86_400_000L }.maxByOrNull { it.miseAJour }
            if (im != null && m.debutV1 && im.base != null) im = null
            if (im != null && im.morceaux[m.offset]?.contentEquals(m.octets) == true) return im
            val i = im ?: nouvelle(m, t)
            i.morceaux[m.offset] = m.octets
            if (m.octets.size >= 2 && (m.octets[0].toInt() and 0xFF) == 0xFF && (m.octets[1].toInt() and 0xFF) == 0xD8) i.base = m.offset
            i.miseAJour = t
            if (satellite.isNotBlank()) i.satellite = satellite
            ecrit(i, m)
            while (images.size > 60) images.remove(images.values.minByOrNull { it.miseAJour }!!.id)
            runCatching { surNouveau?.invoke(i) }
            return i
        }

        private fun nouvelle(m: Morceau, t: Long): Image {
            val f = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val id = "geoscan-" + f.format(java.util.Date(t)) + "-" + m.sat + "-" + (if (m.fichier >= 0) m.fichier else "v1")
            return Image(id, m.sat, m.fichier, t).also { images[id] = it }
        }

        private fun ecrit(i: Image, m: Morceau) {
            val d = File(dossier ?: return, "telemesure/geoscan").apply { mkdirs() }
            runCatching {
                File(d, i.id + ".jsonl").appendText(JSONObject().put("t", i.miseAJour).put("sat", m.sat).put("fichier", m.fichier)
                    .put("offset", m.offset).put("hex", AssemblageSsdv.hex(m.octets)).toString() + "\n")
                if (i.complete) i.jpeg()?.let { File(d, i.id + ".jpg").writeBytes(it) }
            }
        }

        @Synchronized fun json(): JSONArray = JSONArray(liste().take(24).map { i ->
            JSONObject().put("cle", i.id).put("satellite", i.nomSat).put("sat", i.sat).put("fichier", i.fichier).put("octets", i.octets)
                .put("morceaux", i.morceaux.size).put("debut", i.base != null).put("manquants", i.manquants()).put("complete", i.complete)
                .put("t", i.miseAJour).put("premier", i.debut)
        })
    }

    /** Where FF D9 ends the JPEG in [o] (the index after it), or −1. */
    fun finJpeg(o: ByteArray): Int {
        for (i in o.size - 2 downTo 2) if ((o[i].toInt() and 0xFF) == 0xFF && (o[i + 1].toInt() and 0xFF) == 0xD9) return i + 2
        return -1
    }
}
