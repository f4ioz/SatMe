/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ais

/**
 * One AIS message read from its bits (ITU-R M.1371): the fields SatMe
 * shows, null where the message does not carry them or says « not
 * available ». Read: 1, 2, 3 (class A position), 5 (class A static and
 * voyage), 18 and 19 (class B), 21 (aid to navigation), 24 (class B static,
 * parts A and B). Positions in degrees, speed in knots, course and heading
 * in degrees.
 */
data class MessageAis(
    val type: Int,
    val mmsi: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val vitesse: Double? = null,
    val route: Double? = null,
    val cap: Int? = null,
    /** Navigational status (types 1–3), 0–15. */
    val statut: Int? = null,
    /** Rate of turn as sent (−127…127, −128 none). */
    val rot: Int? = null,
    val nom: String? = null,
    val indicatif: String? = null,
    val imo: Int? = null,
    /** Ship and cargo type, 0–99 (type of aid for type 21, as [aide]). */
    val typeNavire: Int? = null,
    val destination: String? = null,
    /** Metres from the reference point to bow, stern, port, starboard. */
    val dimensions: IntArray? = null,
    /** Draught in metres. */
    val tirant: Double? = null,
    /** ETA as sent: month, day, hour, minute (0 / 24 / 60 when not given). */
    val eta: IntArray? = null,
    /** Type of aid to navigation (type 21), 0–31. */
    val aide: Int? = null,
    /** Type 21: the aid is off its charted position. */
    val horsPosition: Boolean? = null,
    /** Type 24: part 0 (A, the name) or 1 (B, the rest). */
    val partie: Int? = null,
    val horodatage: Int? = null,
) {
    /** "A", "B" or "AtoN" (aid to navigation). */
    val classe: String get() = when (type) { 18, 19, 24 -> "B"; 21 -> "AtoN"; else -> "A" }

    override fun equals(other: Any?) = other is MessageAis && other.type == type && other.mmsi == mmsi && other.lat == lat && other.lon == lon &&
        other.nom == nom && other.partie == partie && other.vitesse == vitesse && other.route == route
    override fun hashCode() = (type * 31 + mmsi) * 31 + (lat?.hashCode() ?: 0)

    companion object {
        /** The 6-bit characters of AIS text fields: value → character. */
        const val ALPHABET = "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#$%&'()*+,-./0123456789:;<=>?"

        /** The message these [bits] carry, or null when its type is not read here or it is too short. */
        fun lis(bits: BooleanArray): MessageAis? {
            if (bits.size < 38) return null
            val l = Lecteur(bits)
            val type = l.u(0, 6)
            val mmsi = l.u(8, 30)
            return when (type) {
                1, 2, 3 -> if (bits.size < 149) null else MessageAis(type, mmsi,
                    lat = l.lat(89), lon = l.lon(61), vitesse = l.vitesse(50), route = l.route(116), cap = l.cap(128),
                    statut = l.u(38, 4), rot = l.s(42, 8), horodatage = l.u(137, 6))
                5 -> if (bits.size < 420) null else MessageAis(type, mmsi,
                    imo = l.u(40, 30).takeIf { it != 0 }, indicatif = l.texte(70, 7), nom = l.texte(112, 20), typeNavire = l.u(232, 8),
                    dimensions = l.dims(240), eta = intArrayOf(l.u(274, 4), l.u(278, 5), l.u(283, 5), l.u(288, 6)),
                    tirant = l.u(294, 8) / 10.0, destination = l.texte(302, 20))
                18 -> if (bits.size < 139) null else MessageAis(type, mmsi,
                    lat = l.lat(85), lon = l.lon(57), vitesse = l.vitesse(46), route = l.route(112), cap = l.cap(124), horodatage = l.u(133, 6))
                19 -> if (bits.size < 301) null else MessageAis(type, mmsi,
                    lat = l.lat(85), lon = l.lon(57), vitesse = l.vitesse(46), route = l.route(112), cap = l.cap(124), horodatage = l.u(133, 6),
                    nom = l.texte(143, 20), typeNavire = l.u(263, 8), dimensions = l.dims(271))
                21 -> if (bits.size < 272) null else MessageAis(type, mmsi,
                    aide = l.u(38, 5),
                    nom = (l.texte(43, 20).orEmpty() + (if (bits.size >= 272 + 6) l.texte(272, (bits.size - 272) / 6).orEmpty() else "")).ifEmpty { null },
                    lon = l.lon(164), lat = l.lat(192), dimensions = l.dims(219), horodatage = l.u(253, 6), horsPosition = l.u(259, 1) == 1)
                24 -> {
                    val p = l.u(38, 2)
                    when {
                        p == 0 && bits.size >= 160 -> MessageAis(type, mmsi, partie = 0, nom = l.texte(40, 20))
                        p == 1 && bits.size >= 162 -> MessageAis(type, mmsi, partie = 1, typeNavire = l.u(40, 8),
                            indicatif = l.texte(90, 7), dimensions = l.dims(132))
                        else -> null
                    }
                }
                else -> null
            }
        }
    }

    /** Reads fields MSB first, as M.1371 numbers them. */
    class Lecteur(private val b: BooleanArray) {
        fun u(de: Int, n: Int): Int {
            var v = 0L
            for (i in de until de + n) v = (v shl 1) or (if (i < b.size && b[i]) 1L else 0L)
            return v.toInt()
        }
        fun s(de: Int, n: Int): Int { val v = u(de, n); return if (v and (1 shl (n - 1)) != 0) v - (1 shl n) else v }
        /** [n] 6-bit characters; trailing « @ » and spaces dropped; null when empty. */
        fun texte(de: Int, n: Int): String? {
            val sb = StringBuilder()
            for (k in 0 until n) { if (de + 6 * k + 6 > b.size) break; sb.append(ALPHABET[u(de + 6 * k, 6)]) }
            val t = sb.toString().substringBefore('@').trimEnd()
            return t.ifEmpty { null }
        }
        fun lon(de: Int): Double? = s(de, 28).let { if (it == 0x6791AC0 || it > 108_000_000 || it < -108_000_000) null else it / 600_000.0 }
        fun lat(de: Int): Double? = s(de, 27).let { if (it == 0x3412140 || it > 54_000_000 || it < -54_000_000) null else it / 600_000.0 }
        fun vitesse(de: Int): Double? = u(de, 10).let { if (it == 1023) null else it / 10.0 }
        fun route(de: Int): Double? = u(de, 12).let { if (it >= 3600) null else it / 10.0 }
        fun cap(de: Int): Int? = u(de, 9).let { if (it >= 360) null else it }
        fun dims(de: Int): IntArray? = intArrayOf(u(de, 9), u(de + 9, 9), u(de + 18, 6), u(de + 24, 6)).takeIf { d -> d.any { it != 0 } }
    }
}

/** Writes AIS fields MSB first: the trial vessels' messages, the tests'. */
class EcrivainAis(taille: Int) {
    val bits = BooleanArray(taille)
    private var p = 0
    fun u(v: Long, n: Int): EcrivainAis { for (i in n - 1 downTo 0) bits[p++] = (v shr i) and 1L == 1L; return this }
    fun u(v: Int, n: Int) = u(v.toLong() and ((1L shl n) - 1), n)
    fun texte(t: String, n: Int): EcrivainAis {
        val s = t.uppercase().padEnd(n, '@').take(n)
        for (c in s) u(MessageAis.ALPHABET.indexOf(c).let { if (it < 0) 32 else it }, 6)
        return this
    }
    fun lon(d: Double?) = u(if (d == null) 0x6791AC0 else Math.round(d * 600_000).toInt(), 28)
    fun lat(d: Double?) = u(if (d == null) 0x3412140 else Math.round(d * 600_000).toInt(), 27)
    fun vitesse(k: Double?) = u(if (k == null) 1023 else Math.round(k * 10).toInt().coerceIn(0, 1022), 10)
    fun route(d: Double?) = u(if (d == null) 3600 else Math.round(d * 10).toInt().mod(3600), 12)
    fun cap(d: Int?) = u(d ?: 511, 9)
    fun dims(d: IntArray?) = u(d?.get(0) ?: 0, 9).u(d?.get(1) ?: 0, 9).u(d?.get(2) ?: 0, 6).u(d?.get(3) ?: 0, 6)
}

/** Builds the messages M.1371 defines, from their fields. */
object EncodeurAis {
    fun position(mmsi: Int, lat: Double?, lon: Double?, vitesse: Double?, route: Double?, cap: Int?, statut: Int = 0, type: Int = 1, seconde: Int = 60): BooleanArray =
        EcrivainAis(168).u(type, 6).u(0, 2).u(mmsi, 30).u(statut, 4).u(-128, 8).vitesse(vitesse).u(0, 1).lon(lon).lat(lat)
            .route(route).cap(cap).u(seconde, 6).u(0, 2).u(0, 3).u(0, 1).u(0, 19).bits

    fun statique(mmsi: Int, nom: String, indicatif: String, typeNavire: Int, dims: IntArray?, destination: String,
                 imo: Int = 0, tirant: Double = 0.0, eta: IntArray = intArrayOf(0, 0, 24, 60)): BooleanArray =
        EcrivainAis(424).u(5, 6).u(0, 2).u(mmsi, 30).u(0, 2).u(imo, 30).texte(indicatif, 7).texte(nom, 20).u(typeNavire, 8).dims(dims)
            .u(1, 4).u(eta[0], 4).u(eta[1], 5).u(eta[2], 5).u(eta[3], 6).u(Math.round(tirant * 10).toInt(), 8).texte(destination, 20).u(0, 1).u(0, 1).bits

    fun classeB(mmsi: Int, lat: Double?, lon: Double?, vitesse: Double?, route: Double?, cap: Int?, seconde: Int = 60): BooleanArray =
        EcrivainAis(168).u(18, 6).u(0, 2).u(mmsi, 30).u(0, 8).vitesse(vitesse).u(0, 1).lon(lon).lat(lat).route(route).cap(cap)
            .u(seconde, 6).u(0, 2).u(1, 1).u(0, 1).u(0, 1).u(1, 1).u(0, 1).u(0, 1).u(0, 1).u(0, 20).bits

    fun classeBEtendu(mmsi: Int, lat: Double?, lon: Double?, vitesse: Double?, route: Double?, cap: Int?, nom: String, typeNavire: Int, dims: IntArray?): BooleanArray =
        EcrivainAis(312).u(19, 6).u(0, 2).u(mmsi, 30).u(0, 8).vitesse(vitesse).u(0, 1).lon(lon).lat(lat).route(route).cap(cap)
            .u(60, 6).u(0, 4).texte(nom, 20).u(typeNavire, 8).dims(dims).u(1, 4).u(0, 1).u(1, 1).u(0, 1).u(0, 4).bits

    fun statiqueA(mmsi: Int, nom: String): BooleanArray = EcrivainAis(160).u(24, 6).u(0, 2).u(mmsi, 30).u(0, 2).texte(nom, 20).bits

    fun statiqueB(mmsi: Int, typeNavire: Int, indicatif: String, dims: IntArray?): BooleanArray =
        EcrivainAis(168).u(24, 6).u(0, 2).u(mmsi, 30).u(1, 2).u(typeNavire, 8).texte("SAT", 3).u(0, 4).u(0, 20).texte(indicatif, 7).dims(dims).u(1, 4).u(0, 2).bits

    fun aide(mmsi: Int, aide: Int, nom: String, lat: Double, lon: Double, dims: IntArray? = null, horsPosition: Boolean = false): BooleanArray =
        EcrivainAis(272).u(21, 6).u(0, 2).u(mmsi, 30).u(aide, 5).texte(nom, 20).u(0, 1).lon(lon).lat(lat).dims(dims).u(7, 4).u(60, 6)
            .u(if (horsPosition) 1 else 0, 1).u(0, 8).u(0, 1).u(0, 1).u(0, 1).u(0, 1).bits
}
