/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.sonde.Geo

/**
 * What APRS has given so far, kept for good (not the 7-day history): the
 * squares and countries heard, the distance record, how often the ISS
 * repeated us, the contacts, and the badges with the day they were won.
 *
 * [avec] is pure: a new frame in, the new state and what is worth a cheer
 * out. Saving and the cheering itself are done by the caller.
 */
object Trophees {

    enum class Badge(val cle: String) {
        PREMIER_PAQUET("premier_paquet"),
        PREMIER_ISS("premier_iss"),
        REPETE_ISS("repete_iss"),
        ISS_10("iss_10"),
        CONTACT("contact"),
        CONTACT_ISS("contact_iss"),
        CARRES_10("carres_10"),
        CARRES_50("carres_50"),
        PAYS_10("pays_10"),
        KM_1000("km_1000"),
        KM_2000("km_2000"),
        KM_3000("km_3000"),
        JEUDI("jeudi"),
        METEO("meteo"),
    }

    data class Etat(
        /** Square (4 characters) → when first heard. */
        val carres: Map<String, Long> = emptyMap(),
        val pays: Map<String, Long> = emptyMap(),
        val recordKm: Double = 0.0,
        val recordIndicatif: String = "",
        val recordQuand: Long = 0L,
        /** Our frames heard back through the ISS. */
        val repetesIss: Int = 0,
        /** Station + day already counted as a contact ("F4XYZ 2026-10-01"). */
        val contacts: Set<String> = emptySet(),
        val badges: Map<Badge, Long> = emptyMap(),
    )

    sealed class Evenement {
        /** Our frame came back through the ISS; [autres]: who else was heard on that pass. */
        data class RepeteIss(val paquet: Paquet, val autres: List<String>) : Evenement()
        data class NouveauCarre(val carre: String, val indicatif: String) : Evenement()
        data class NouveauPays(val pays: String, val indicatif: String) : Evenement()
        data class Record(val km: Int, val indicatif: String) : Evenement()
        data class Contact(val contact: AprsJeu.Contact) : Evenement()
        data class NouveauBadge(val badge: Badge) : Evenement()
    }

    private fun jour(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /**
     * [p] has just been added to [tous] (newest first or not, any order).
     * [moi]: our callsign; [obsLat]/[obsLon]: where we are; [pays]: a
     * callsign's country, or null.
     */
    fun avec(e: Etat, p: Paquet, tous: List<Paquet>, moi: String, obsLat: Double?, obsLon: Double?,
             pays: (String) -> String?): Pair<Etat, List<Evenement>> {
        var s = e
        val ev = ArrayList<Evenement>()
        fun badge(b: Badge) {
            if (b !in s.badges) { s = s.copy(badges = s.badges + (b to p.quand)); ev += Evenement.NouveauBadge(b) }
        }
        val b = AprsJeu.base(moi)
        val deNous = AprsJeu.base(p.source) == b && b.isNotEmpty()

        if (p.emis) {
            if (p.hotg) badge(Badge.JEUDI)
        } else {
            badge(Badge.PREMIER_PAQUET)
            if (p.viaIss) badge(Badge.PREMIER_ISS)
            if (p.viaIss && deNous) {
                s = s.copy(repetesIss = s.repetesIss + 1)
                ev += Evenement.RepeteIss(p, AprsJeu.entendusAutour(tous, p.quand, moi))
                badge(Badge.REPETE_ISS)
                if (s.repetesIss >= 10) badge(Badge.ISS_10)
            }
            if (p.meteo != null) badge(Badge.METEO)
            val qui = p.nom ?: p.source
            if (!deNous && AprsJeu.base(qui) !in AprsJeu.SERVEURS) {
                if (p.lat != null && p.lon != null) {
                    val carre = Maidenhead.fromLatLon(p.lat, p.lon).take(4).uppercase()
                    if (carre !in s.carres) {
                        s = s.copy(carres = s.carres + (carre to p.quand))
                        ev += Evenement.NouveauCarre(carre, qui)
                        if (s.carres.size >= 10) badge(Badge.CARRES_10)
                        if (s.carres.size >= 50) badge(Badge.CARRES_50)
                    }
                    if (obsLat != null && obsLon != null) {
                        val km = Geo.distanceKm(obsLat, obsLon, p.lat, p.lon)
                        if (km > s.recordKm && km < 22_000) {
                            // A record is cheered only from 100 km: the first frames would all be records.
                            if (km >= 100) ev += Evenement.Record(km.toInt(), qui)
                            s = s.copy(recordKm = km, recordIndicatif = qui, recordQuand = p.quand)
                        }
                        if (km >= 1000) badge(Badge.KM_1000)
                        if (km >= 2000) badge(Badge.KM_2000)
                        if (km >= 3000) badge(Badge.KM_3000)
                    }
                }
                pays(p.source)?.let { pa ->
                    if (pa !in s.pays) {
                        s = s.copy(pays = s.pays + (pa to p.quand))
                        ev += Evenement.NouveauPays(pa, p.source)
                        if (s.pays.size >= 10) badge(Badge.PAYS_10)
                    }
                }
            }
        }
        // A contact is complete when its ack arrives (ours or theirs): look again each time.
        if (p.type == TypeAprs.ACCUSE || p.type == TypeAprs.MESSAGE) {
            AprsJeu.contacts(tous, moi).forEach { c ->
                val cle = AprsJeu.base(c.indicatif) + " " + jour(c.quand)
                if (cle !in s.contacts) {
                    s = s.copy(contacts = s.contacts + cle)
                    ev += Evenement.Contact(c)
                    badge(Badge.CONTACT)
                    if (c.viaIss) badge(Badge.CONTACT_ISS)
                }
            }
        }
        return s to ev
    }

    // ------------------------------------------------------------- on disk

    /** One fact per line, tab-separated: kind, key, value. Readable, and survives a format change. */
    fun ecrit(e: Etat): String = buildString {
        e.carres.forEach { (k, v) -> append("carre\t$k\t$v\n") }
        e.pays.forEach { (k, v) -> append("pays\t${k.replace('\t', ' ')}\t$v\n") }
        if (e.recordKm > 0) append("record\t${e.recordIndicatif}\t${e.recordKm}\t${e.recordQuand}\n")
        append("repetes\t-\t${e.repetesIss}\n")
        e.contacts.forEach { append("contact\t$it\t0\n") }
        e.badges.forEach { (k, v) -> append("badge\t${k.cle}\t$v\n") }
    }

    fun lit(texte: String): Etat {
        var e = Etat()
        texte.lineSequence().forEach { l ->
            val f = l.split('\t')
            if (f.size < 3) return@forEach
            runCatching {
                e = when (f[0]) {
                    "carre" -> e.copy(carres = e.carres + (f[1] to f[2].toLong()))
                    "pays" -> e.copy(pays = e.pays + (f[1] to f[2].toLong()))
                    "record" -> e.copy(recordIndicatif = f[1], recordKm = f[2].toDouble(), recordQuand = f.getOrNull(3)?.toLong() ?: 0L)
                    "repetes" -> e.copy(repetesIss = f[2].toInt())
                    "contact" -> e.copy(contacts = e.contacts + f[1])
                    "badge" -> Badge.entries.firstOrNull { it.cle == f[1] }
                        ?.let { e.copy(badges = e.badges + (it to f[2].toLong())) } ?: e
                    else -> e
                }
            }
        }
        return e
    }
}
