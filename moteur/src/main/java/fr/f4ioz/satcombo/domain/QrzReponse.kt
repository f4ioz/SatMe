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
    ) {
        val vide: Boolean get() = carre.isBlank() && nom.isBlank() && pays.isBlank()
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
        )
    }
}
