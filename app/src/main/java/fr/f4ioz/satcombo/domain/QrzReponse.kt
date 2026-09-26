/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Ce que QRZ.com répond, dépouillé.
 *
 * L'interface XML de QRZ demande un **abonnement séparé** de l'inscription :
 * sans lui la connexion réussit et les recherches échouent. On rend donc le
 * message du serveur tel quel plutôt que de le traduire en « introuvable » —
 * confondre les deux ferait chercher un défaut là où il n'y a qu'un
 * abonnement à prendre.
 *
 * L'analyse est volontairement grossière : QRZ déclare un espace de noms
 * (`xmlns="http://xmldata.qrz.com"`) qu'un analyseur strict impose de
 * qualifier, et la réponse tient en quelques balises sans imbrication. Une
 * expression régulière sur le nom local suffit, et ne casse pas le jour où
 * QRZ ajoute un champ.
 */
object QrzReponse {

    data class Fiche(
        val indicatif: String = "",
        val carre: String = "",
        val nom: String = "",
        /**
         * Le prénom seul.
         *
         * QRZ le rend séparément et on le fusionnait aussitôt dans [nom]. Or
         * c'est le prénom qu'on lance à la radio — « bonjour Jean » — et le
         * redécouper après coup se trompe dès que le nom de famille arrive
         * seul, ou que le prénom est composé.
         */
        val prenom: String = "",
        /** La ville du correspondant : QRZ la met dans `addr2`. */
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
