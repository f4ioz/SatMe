/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.QrzReponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing QRZ.com responses. */
class QrzReponseTest {

    private val fiche = """<?xml version="1.0" encoding="utf-8" ?>
        <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
        <Callsign><call>F5RRO</call><grid>JN18fr</grid><fname>Olivier</fname>
        <name>Martin</name><country>France</country></Callsign>
        <Session><Key>abc123</Key><Count>42</Count></Session>
        </QRZDatabase>"""

    private val absent = """<?xml version="1.0" encoding="utf-8" ?>
        <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
        <Session><Error>Not found: XX1XXX</Error><Key>abc123</Key></Session>
        </QRZDatabase>"""

    private val sansAbonnement = """<?xml version="1.0" encoding="utf-8" ?>
        <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
        <Session><Key>abc123</Key>
        <Error>Username/password incorrect</Error></Session>
        </QRZDatabase>"""

    @Test
    fun le_carre_remonte_en_majuscules() {
        assertEquals("JN18FR", QrzReponse.lis(fiche).carre)
    }

    @Test
    fun le_prenom_et_le_nom_se_rejoignent() {
        assertEquals("Olivier Martin", QrzReponse.lis(fiche).nom)
    }

    @Test
    fun l_indicatif_et_le_pays_se_lisent() {
        val f = QrzReponse.lis(fiche)
        assertEquals("F5RRO", f.indicatif)
        assertEquals("France", f.pays)
    }

    @Test
    fun la_cle_de_session_se_lit() {
        assertEquals("abc123", QrzReponse.lis(fiche).cle)
    }

    /**
     * Errors are returned verbatim: "no XML subscription" and "unknown
     * callsign" call for different fixes.
     */
    @Test
    fun une_erreur_est_rendue_mot_pour_mot() {
        assertEquals("Not found: XX1XXX", QrzReponse.lis(absent).erreur)
        assertEquals("Username/password incorrect", QrzReponse.lis(sansAbonnement).erreur)
    }

    @Test
    fun une_reponse_sans_fiche_est_vide() {
        assertTrue(QrzReponse.lis(absent).vide)
        assertTrue(!QrzReponse.lis(fiche).vide)
    }

    /** An unreadable response does not crash the app. */
    @Test
    fun un_charabia_ne_leve_pas() {
        val f = QrzReponse.lis("<pas du xml du tout")
        assertTrue(f.vide)
        assertEquals("", f.erreur)
    }

    /**
     * City and email are read too. QRZ stores the city in `addr2`; both
     * fields are now kept in the log and sent along with the contact.
     */
    @Test
    fun la_ville_et_le_courriel_se_lisent() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
            <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
            <Callsign><call>DO1BEN</call><grid>JO31NB</grid>
            <fname>Clubstation</fname><addr2>Leverkusen</addr2>
            <email>qso@example.org</email><country>Germany</country></Callsign>
            </QRZDatabase>"""
        val f = QrzReponse.lis(xml)
        assertEquals("JO31NB", f.carre)
        assertEquals("Leverkusen", f.qth)
        assertEquals("qso@example.org", f.courriel)
        assertEquals("Clubstation", f.nom)
    }

    @Test
    fun une_fiche_sans_ville_reste_lisible() {
        val xml = """<QRZDatabase xmlns="http://xmldata.qrz.com">
            <Callsign><call>F6ABC</call><grid>JN18FR</grid></Callsign>
            </QRZDatabase>"""
        val f = QrzReponse.lis(xml)
        assertEquals("", f.qth)
        assertEquals("", f.courriel)
        assertEquals("JN18FR", f.carre)
    }
}
