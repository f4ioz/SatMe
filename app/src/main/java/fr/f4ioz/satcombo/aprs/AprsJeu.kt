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
 * What makes APRS a game, read from the frames heard and sent: our own frame
 * coming back through the ISS, who heard it, the stations and their squares,
 * the contacts made (a message and its ack), the conversations, the evening's
 * tally. Pure functions: the screen and the trophies call them.
 */
object AprsJeu {

    /** "F4IOZ-7" → "F4IOZ": the operator, whatever the SSID. */
    fun base(indicatif: String?): String = (indicatif ?: "").trim().uppercase().substringBefore('-')

    /** Same pass: frames heard within this time of each other. */
    const val PASSAGE_MS = 12 * 60_000L

    /** Our own frames, heard back after the ISS repeated them. */
    fun repetesParIss(paquets: List<Paquet>, moi: String): List<Paquet> {
        val b = base(moi)
        if (b.isEmpty()) return emptyList()
        return paquets.filter { !it.emis && it.viaIss && base(it.source) == b }
    }

    /** Other stations heard through the ISS within a pass of [quand]: who shared the bird with us. */
    fun entendusAutour(paquets: List<Paquet>, quand: Long, moi: String): List<String> =
        paquets.filter { it.viaIss && !it.emis && kotlin.math.abs(it.quand - quand) <= PASSAGE_MS &&
            base(it.source) != base(moi) }
            .map { it.source }.distinct()

    data class Station(
        val indicatif: String,
        val lat: Double,
        val lon: Double,
        val km: Double?,
        val carre: String,
        val dernier: Long,
        val viaIss: Boolean,
        val symbole: String?,
        val meteo: Meteo?,
    )

    /** Every station with a position (its latest one), nearest last-heard first. Objects keep their name. */
    fun stations(paquets: List<Paquet>, obsLat: Double?, obsLon: Double?): List<Station> =
        paquets.filter { it.lat != null && it.lon != null && !it.emis }
            .groupBy { it.nom ?: it.source }
            .map { (nom, ps) ->
                val p = ps.maxBy { it.quand }
                Station(nom, p.lat!!, p.lon!!,
                    if (obsLat != null && obsLon != null) Geo.distanceKm(obsLat, obsLon, p.lat, p.lon) else null,
                    Maidenhead.fromLatLon(p.lat, p.lon).take(4).uppercase(),
                    p.quand, ps.any { it.viaIss }, p.symbole,
                    ps.filter { it.meteo != null }.maxByOrNull { it.quand }?.meteo)
            }
            .sortedByDescending { it.dernier }

    /** A sent message is acknowledged when an "ack<number>" from its addressee comes back to us. */
    fun estAccuse(p: Paquet, paquets: List<Paquet>): Boolean =
        p.emis && p.type == TypeAprs.MESSAGE && p.idMessage != null && paquets.any {
            it.type == TypeAprs.ACCUSE && !it.emis && it.idMessage == p.idMessage &&
                base(it.destinataire) == base(p.source) &&
                base(it.source) == base(p.destinataire)
        }

    /** A contact: messages went both ways, at least one acknowledged. Worth a line in the log. */
    data class Contact(val indicatif: String, val quand: Long, val carre: String?, val viaIss: Boolean)

    /**
     * Contacts made: we sent a message that [indicatif] acknowledged, or
     * [indicatif] sent us a message we acknowledged. One per station and pass.
     * Group servers (ANSRVR, APRSPH…) are not contacts.
     */
    fun contacts(paquets: List<Paquet>, moi: String): List<Contact> {
        val b = base(moi)
        if (b.isEmpty()) return emptyList()
        val faits = ArrayList<Contact>()
        fun ajoute(qui: String, quand: Long, iss: Boolean) {
            if (base(qui) in SERVEURS || base(qui) == b) return
            if (faits.any { base(it.indicatif) == base(qui) && kotlin.math.abs(it.quand - quand) <= PASSAGE_MS }) return
            val pos = paquets.filter { base(it.source) == base(qui) && it.lat != null }.maxByOrNull { it.quand }
            faits += Contact(qui, quand, pos?.let { Maidenhead.fromLatLon(it.lat!!, it.lon!!).take(4).uppercase() }, iss)
        }
        for (p in paquets.sortedBy { it.quand }) {
            when {
                // Our message, acknowledged by its addressee.
                estAccuse(p, paquets) -> {
                    val ack = paquets.first { it.type == TypeAprs.ACCUSE && it.idMessage == p.idMessage &&
                        base(it.source) == base(p.destinataire) }
                    ajoute(ack.source, ack.quand, ack.viaIss || p.trame.relais.any { it.indicatif == "ARISS" })
                }
                // Their message to us, which we acknowledged.
                p.type == TypeAprs.MESSAGE && !p.emis && base(p.destinataire) == b && p.idMessage != null &&
                    paquets.any { it.emis && it.type == TypeAprs.ACCUSE && it.idMessage == p.idMessage &&
                        base(it.destinataire) == base(p.source) } ->
                    ajoute(p.source, p.quand, p.viaIss)
            }
        }
        return faits.sortedByDescending { it.quand }
    }

    /** Group and bulletin servers: messages to them are not person-to-person. */
    val SERVEURS = setOf("ANSRVR", "APRSPH", "EMAIL", "EMAIL-2", "SMSGTE", "WXBOT", "BLN", "WHO-IS")

    /** A conversation: the messages with one station, oldest first. */
    data class Fil(val correspondant: String, val messages: List<Paquet>) {
        val dernier: Long get() = messages.maxOf { it.quand }
    }

    /** Person-to-person messages, by correspondent; the most recent conversation first. */
    fun fils(paquets: List<Paquet>, moi: String): List<Fil> {
        val b = base(moi)
        return paquets.filter { it.type == TypeAprs.MESSAGE }
            .mapNotNull { p ->
                val autre = when {
                    p.emis -> p.destinataire
                    base(p.destinataire) == b && b.isNotEmpty() -> p.source
                    else -> null
                }
                autre?.takeIf { base(it) !in SERVEURS }?.let { base(it) to p }
            }
            .groupBy({ it.first }, { it.second })
            .map { (qui, ps) -> Fil(qui, ps.distinctBy { it.quand to it.trame }.sortedBy { it.quand }) }
            .sortedByDescending { it.dernier }
    }

    /** APRS Thursday: stations taking part since [depuis] (their own "CQ HOTG" or what the server relays). */
    fun participantsHotg(paquets: List<Paquet>, depuis: Long, moi: String): List<String> =
        paquets.filter { it.quand >= depuis && it.hotg && !it.emis && base(it.source) !in SERVEURS }
            .map { base(it.source) }
            .filter { it != base(moi) && it.isNotEmpty() }
            .distinct()

    /** The evening in numbers. */
    data class Bilan(
        val stations: Int,
        val pays: Set<String>,
        val carres: Set<String>,
        val plusLoin: Station?,
        val viaIss: Int,
        val envoyes: Int,
        val accuses: Int,
        val contacts: Int,
        val repetes: Int,
    )

    fun bilan(paquets: List<Paquet>, depuis: Long, moi: String, obsLat: Double?, obsLon: Double?,
              pays: (String) -> String?): Bilan {
        val ps = paquets.filter { it.quand >= depuis }
        val entendus = ps.filter { !it.emis }
        val st = stations(ps, obsLat, obsLon).filter { base(it.indicatif) != base(moi) }
        val indicatifs = entendus.map { base(it.source) }.filter { it.isNotEmpty() && it !in SERVEURS }.toSet() - base(moi)
        return Bilan(
            stations = indicatifs.size,
            pays = indicatifs.mapNotNull(pays).toSet(),
            carres = st.map { it.carre }.toSet(),
            plusLoin = st.filter { it.km != null }.maxByOrNull { it.km!! },
            viaIss = entendus.count { it.viaIss },
            envoyes = ps.count { it.emis },
            accuses = ps.count { estAccuse(it, paquets) },
            contacts = contacts(ps, moi).size,
            repetes = repetesParIss(ps, moi).size,
        )
    }
}
