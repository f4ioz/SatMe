/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * The QRZ.com answer, stripped down.
 *
 * The QRZ XML interface needs a **separate subscription**: without it login
 * succeeds and lookups fail. The server message is passed on unchanged rather
 * than turned into "not found", so nobody hunts for a bug where there is only
 * a subscription missing.
 *
 * Parsing is deliberately crude: QRZ declares a namespace
 * (`xmlns="http://xmldata.qrz.com"`) that a strict parser forces you to
 * qualify, and the answer is a few flat tags. A regex on the local name is
 * enough and survives QRZ adding fields.
 */
object QrzReponse {

    data class Fiche(
        val indicatif: String = "",
        val carre: String = "",
        val nom: String = "",
        /**
         * First name alone. It is what you say on air ("hello Jean"), and
         * splitting it back out of [nom] fails when the surname comes alone or
         * the first name is compound.
         */
        val prenom: String = "",
        /** The station's town: QRZ puts it in `addr2`. */
        val qth: String = "",
        val courriel: String = "",
        val pays: String = "",
        val cle: String = "",
        val erreur: String = "",
        /** The station's photo on QRZ (an address of theirs), or empty. */
        val image: String = "",
        /** Where the station is, as QRZ has it (degrees), or null. */
        val lat: Double? = null,
        val lon: Double? = null,
        /** Street (QRZ's `addr1`), state or region, county. */
        val adresse: String = "",
        val etat: String = "",
        val comte: String = "",
        val zoneCq: String = "",
        val zoneItu: String = "",
        /** Licence class, as the country words it. */
        val classe: String = "",
        /** How the station confirms: LoTW, eQSL, paper card; its QSL manager. */
        val lotw: Boolean = false,
        val eqsl: Boolean = false,
        val qslPapier: Boolean = false,
        val qslManager: String = "",
        val site: String = "",
        val surnom: String = "",
        /** Licensed since (a year, as QRZ gives it). */
        val depuis: String = "",
        /**
         * Set when the compound callsign was not in the directory and its
         * operator's record was taken instead (EA3/M5JFS → M5JFS): who, not
         * where. [lieuOpere] is where they operate from (EA3, /P…).
         */
        val identiteDe: String = "",
        val lieuOpere: String = "",
    ) {
        val vide: Boolean get() = carre.isBlank() && nom.isBlank() && pays.isBlank()
    }

    /** The directory does not know the callsign (as opposed to no session, no subscription, no network). */
    fun introuvable(f: Fiche): Boolean =
        f.erreur.contains("not found", ignoreCase = true) || (f.erreur.isBlank() && f.vide)

    /**
     * The operator's record [b] for compound callsign [d]: the identity only.
     * Their home square, coordinates, address, zones and country are not
     * this contact's place; the country becomes the operating prefix's.
     */
    fun identiteSeule(b: Fiche, d: IndicatifCompose.Decoupe): Fiche {
        val lieu = d.prefixe.ifEmpty { (d.suffixes.map { "/$it" } + listOfNotNull(d.chiffre.ifEmpty { null }?.let { "/$it" })).joinToString("") }
        if (!d.deplace) return b.copy(indicatif = d.complet, identiteDe = d.base, lieuOpere = lieu)
        return b.copy(indicatif = d.complet, identiteDe = d.base, lieuOpere = lieu,
            carre = "", lat = null, lon = null, adresse = "", qth = "", etat = "", comte = "", zoneCq = "", zoneItu = "",
            pays = if (d.maritime) "maritime mobile" else if (d.aeronautique) "aéronautique mobile" else Dxcc.entite(d.complet)?.nom.orEmpty())
    }

    private fun balise(xml: String, nom: String): String =
        Regex("<(?:[A-Za-z0-9]+:)?$nom>(.*?)</(?:[A-Za-z0-9]+:)?$nom>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    fun lis(xml: String): Fiche {
        val prenom = balise(xml, "fname")
        val nom = balise(xml, "name")
        return Fiche(
            indicatif = balise(xml, "call").uppercase(),
            carre = balise(xml, "grid").uppercase(),
            nom = listOf(prenom, nom).filter { it.isNotBlank() }.joinToString(" "),
            prenom = prenom,
            qth = balise(xml, "addr2"),
            courriel = balise(xml, "email"),
            pays = balise(xml, "country"),
            cle = balise(xml, "Key"),
            erreur = balise(xml, "Error"),
            image = balise(xml, "image"),
            lat = balise(xml, "lat").toDoubleOrNull(),
            lon = balise(xml, "lon").toDoubleOrNull(),
            adresse = balise(xml, "addr1"),
            etat = balise(xml, "state"),
            comte = balise(xml, "county"),
            zoneCq = balise(xml, "cqzone"),
            zoneItu = balise(xml, "ituzone"),
            classe = balise(xml, "class"),
            lotw = balise(xml, "lotw") == "1",
            eqsl = balise(xml, "eqsl") == "1",
            qslPapier = balise(xml, "mqsl") == "1",
            qslManager = balise(xml, "qslmgr"),
            site = balise(xml, "url"),
            surnom = balise(xml, "nickname"),
            depuis = balise(xml, "efdate").take(4),
        )
    }
}
