/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

/** A service of the SDT: its number, its provider's and its own name. */
data class ServiceDvb(val id: Int, val fournisseur: String, val nom: String, val type: Int)

/**
 * The names a DVB transport stream gives itself: the Service Description
 * Table (ETSI EN 300 468, 5.2.3), on PID 0x11, table_id 0x42 (this stream),
 * each service's service_descriptor (tag 0x48: provider and service names).
 * Sections are put together across packets and checked by their CRC-32.
 */
class LecteurSdt {
    @Volatile var services: List<ServiceDvb> = emptyList(); private set
    @Volatile var sections = 0L; private set

    /** The first service's name (a DATV station's callsign, mostly), or "". */
    val nom: String get() = services.firstOrNull { it.nom.isNotBlank() }?.nom.orEmpty()
    val fournisseur: String get() = services.firstOrNull { it.fournisseur.isNotBlank() }?.fournisseur.orEmpty()

    private val reste = ByteArray(188)
    private var nReste = 0
    private val section = java.io.ByteArrayOutputStream()
    private var dedans = false
    private var dernierCc = -1

    @Synchronized
    fun oublie() { services = emptyList(); nReste = 0; dedans = false; section.reset(); dernierCc = -1 }

    /** A block of the stream, cut anywhere. */
    @Synchronized
    fun bloc(b: ByteArray, n: Int = b.size) {
        var i = 0
        if (nReste > 0) {
            val k = minOf(188 - nReste, n)
            System.arraycopy(b, 0, reste, nReste, k)
            nReste += k; i = k
            if (nReste < 188) return
            nReste = 0
            if (reste[0].toInt() == 0x47) paquet(reste, 0)
        }
        while (i < n) {
            if (b[i].toInt() != 0x47 || (n - i > 188 && b[i + 188].toInt() != 0x47)) { i++; continue }
            if (n - i < 188) { System.arraycopy(b, i, reste, 0, n - i); nReste = n - i; return }
            paquet(b, i)
            i += 188
        }
    }

    private fun paquet(b: ByteArray, o: Int) {
        val b1 = b[o + 1].toInt() and 0xFF
        if ((((b1 and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)) != PID) return
        if (b1 and 0x80 != 0) { dedans = false; section.reset(); return }
        val b3 = b[o + 3].toInt() and 0xFF
        val afc = (b3 shr 4) and 3
        if (afc == 0 || afc == 2) return
        val cc = b3 and 0x0F
        if (dernierCc >= 0 && cc == dernierCc) return
        if (dernierCc >= 0 && cc != ((dernierCc + 1) and 0x0F)) { dedans = false; section.reset() }
        dernierCc = cc
        var i = o + 4
        if (afc == 3) i += 1 + (b[o + 4].toInt() and 0xFF)
        val fin = o + 188
        if (i >= fin) return
        if (b1 and 0x40 != 0) {
            val pointeur = b[i].toInt() and 0xFF
            i++
            if (dedans) { section.write(b, i, minOf(pointeur, fin - i)); termine() }
            i += pointeur
            dedans = false; section.reset()
            while (i < fin && (b[i].toInt() and 0xFF) != 0xFF) {
                dedans = true
                val avant = section.size()
                section.write(b, i, fin - i)
                val pris = termine()
                if (pris < 0) break
                i += pris - avant
            }
        } else if (dedans) {
            section.write(b, i, fin - i)
            termine()
        }
    }

    /** A whole section in the buffer: read; the bytes it took, or −1 when not whole yet. */
    private fun termine(): Int {
        val s = section.toByteArray()
        if (s.size < 3) return -1
        val total = 3 + (((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF))
        if (total > 1024) { dedans = false; section.reset(); return -1 }
        if (s.size < total) return -1
        dedans = false; section.reset()
        lis(s.copyOf(total))?.let { l -> services = l; sections++ }
        return total
    }

    companion object {
        const val PID = 0x11
        const val TABLE_SDT = 0x42
        const val SERVICE_DESCRIPTOR = 0x48

        /** A whole SDT section (actual stream) read: its services, or null (another table, a bad CRC). */
        fun lis(s: ByteArray): List<ServiceDvb>? {
            if (s.size < 15 || (s[0].toInt() and 0xFF) != TABLE_SDT) return null
            val total = 3 + (((s[1].toInt() and 0x0F) shl 8) or (s[2].toInt() and 0xFF))
            if (total != s.size || CarteTs.crc32(s, 0, total) != 0) return null
            val l = ArrayList<ServiceDvb>()
            var k = 11
            while (k + 5 <= total - 4) {
                val id = ((s[k].toInt() and 0xFF) shl 8) or (s[k + 1].toInt() and 0xFF)
                val longueur = ((s[k + 3].toInt() and 0x0F) shl 8) or (s[k + 4].toInt() and 0xFF)
                var d = k + 5
                val finD = minOf(d + longueur, total - 4)
                var fournisseur = ""; var nom = ""; var type = 0
                while (d + 2 <= finD) {
                    val tag = s[d].toInt() and 0xFF
                    val ld = s[d + 1].toInt() and 0xFF
                    if (d + 2 + ld > finD) break
                    if (tag == SERVICE_DESCRIPTOR && ld >= 3) {
                        // type, provider length, provider, name length, name.
                        val o = d + 2
                        type = s[o].toInt() and 0xFF
                        val lp = minOf(s[o + 1].toInt() and 0xFF, ld - 2)
                        fournisseur = texte(s, o + 2, lp)
                        if (2 + lp < ld) nom = texte(s, o + 3 + lp, minOf(s[o + 2 + lp].toInt() and 0xFF, ld - 3 - lp))
                    }
                    d += 2 + ld
                }
                l += ServiceDvb(id, fournisseur, nom, type)
                k += 5 + longueur
            }
            return l
        }

        /**
         * A DVB string (EN 300 468, annex A): an optional first byte names the
         * character table (none: the Latin table, read here as ISO 8859-1);
         * the control codes 0x80–0x9F are dropped.
         */
        fun texte(s: ByteArray, de: Int, n: Int): String {
            if (n <= 0 || de < 0 || de >= s.size) return ""
            val fin = minOf(s.size, de + n)
            val t0 = s[de].toInt() and 0xFF
            var i = de
            val jeu = when {
                t0 in 0x01..0x0B -> { i++; runCatching { charset("ISO-8859-" + (t0 + 4)) }.getOrDefault(Charsets.ISO_8859_1) }
                t0 == 0x10 && fin - de >= 3 -> { i += 3; runCatching { charset("ISO-8859-" + (s[de + 2].toInt() and 0xFF)) }.getOrDefault(Charsets.ISO_8859_1) }
                t0 == 0x11 -> { i++; Charsets.UTF_16BE }
                t0 == 0x15 -> { i++; Charsets.UTF_8 }
                t0 < 0x20 -> { i++; Charsets.ISO_8859_1 }
                else -> Charsets.ISO_8859_1
            }
            if (i >= fin) return ""
            val b = s.copyOfRange(i, fin)
            val propre = if (jeu == Charsets.UTF_8 || jeu == Charsets.UTF_16BE) b
                else b.filter { (it.toInt() and 0xFF) !in 0x80..0x9F }.toByteArray()
            return String(propre, jeu).filter { it >= ' ' && it !in '\u0080'..'\u009F' }.trim()
        }

        /** An SDT section for one service (tests, and a stream sent by SatMe). */
        fun section(fournisseur: String, nom: String, serviceId: Int = 1, tsId: Int = 1, version: Int = 0): ByteArray {
            val p = fournisseur.toByteArray(Charsets.ISO_8859_1); val n = nom.toByteArray(Charsets.ISO_8859_1)
            val desc = byteArrayOf(SERVICE_DESCRIPTOR.toByte(), (3 + p.size + n.size).toByte(), 0x01, p.size.toByte()) + p + byteArrayOf(n.size.toByte()) + n
            val corps = byteArrayOf((serviceId shr 8).toByte(), serviceId.toByte(), 0xFC.toByte(),
                (0x80 or ((desc.size shr 8) and 0x0F)).toByte(), desc.size.toByte()) + desc
            val longueur = 8 + corps.size + 4
            val tete = byteArrayOf(TABLE_SDT.toByte(), (0xF0 or (longueur shr 8)).toByte(), longueur.toByte(), (tsId shr 8).toByte(), tsId.toByte(),
                (0xC1 or ((version and 31) shl 1)).toByte(), 0, 0, 0, 1, 0xFF.toByte())
            val sans = tete + corps
            val c = CarteTs.crc32(sans)
            return sans + byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte())
        }
    }
}
