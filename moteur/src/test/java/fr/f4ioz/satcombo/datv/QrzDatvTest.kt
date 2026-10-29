/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.QrzReponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DATV's QRZ lookups: which callsigns, at what pace, and the card shown (synthetic XML, no network). */
class QrzDatvTest {

    private val xml = """<?xml version="1.0" encoding="utf-8" ?>
        <QRZDatabase version="1.34" xmlns="http://xmldata.qrz.com">
        <Callsign><call>G4ABC</call><fname>John</fname><name>Smith</name><addr2>Leeds</addr2><state>West Yorkshire</state>
        <country>England</country><grid>IO93fs</grid><lat>53.8</lat><lon>-1.55</lon><class>Full</class>
        <lotw>1</lotw><eqsl>0</eqsl><mqsl>1</mqsl><qslmgr>direct</qslmgr>
        <image>https://cdn-xml.qrz.com/c/g4abc/photo.jpg</image></Callsign>
        <Session><Key>k</Key></Session></QRZDatabase>"""

    private val absent = """<QRZDatabase xmlns="http://xmldata.qrz.com"><Session><Error>Not found: ZZ9ZZZ</Error><Key>k</Key></Session></QRZDatabase>"""
    private val refus = """<QRZDatabase xmlns="http://xmldata.qrz.com"><Session><Error>Username/password incorrect</Error></Session></QRZDatabase>"""

    private var t = 1_000_000_000L
    private val cache = QrzDatv.Cache { t }

    @Test
    fun candidats_carte_puis_service_puis_lecture() {
        assertEquals(listOf("F4IOZ", "G4ABC", "DL1XYZ"), QrzDatv.candidats("F4IOZ", "G4ABC QO-100", listOf("DL1XYZ", "f4ioz")))
        // Neither the beacon's name nor a word is a callsign.
        assertEquals(emptyList<String>(), QrzDatv.candidats("", "QO-100 BEACON", emptyList()))
        assertEquals(listOf("F4IOZ/P"), QrzDatv.candidats("F4IOZ/P", "F4IOZ", emptyList()))
        assertEquals("F4IOZ", QrzDatv.cle("f4ioz/p"))
    }

    @Test
    fun l_indicatif_dans_l_etiquette_du_spectre() {
        assertEquals("G4ABC", QrzDatv.indicatifDans("G4ABC Simon"))
        assertEquals("OZ9XYZ", QrzDatv.indicatifDans("OZ9XYZ_John"))
        assertEquals("G0XYZ", QrzDatv.indicatifDans("g0xyz-James"))
        assertEquals("F4XYZ/P", QrzDatv.indicatifDans("F4XYZ/P"))
        // A beacon, a channel number, a name alone, nothing: no callsign.
        for (n in listOf("QO-100 BEACON", "QARS", "0303", "Simon", "", "  ")) assertEquals(n, "", QrzDatv.indicatifDans(n))
    }

    @Test
    fun candidats_avec_la_selection_du_spectre() {
        // After the card and the service name, before the picture's reading; even alone (not locked yet).
        assertEquals(listOf("F4XYZ", "G4ABC", "DL1XYZ"), QrzDatv.candidats("F4XYZ", "", listOf("DL1XYZ"), spectre = "G4ABC Simon"))
        assertEquals(listOf("G4ABC"), QrzDatv.candidats("", "", emptyList(), spectre = "G4ABC Simon"))
        // Not twice; the beacon's label is no candidate.
        assertEquals(listOf("G4ABC/P"), QrzDatv.candidats("G4ABC/P", "", emptyList(), spectre = "G4ABC"))
        assertEquals(emptyList<String>(), QrzDatv.candidats("", "", emptyList(), spectre = "QO-100 BEACON"))
        // Kept within [max] even behind four others.
        assertTrue("G4ABC" in QrzDatv.candidats("F4XYZ", "F5AAA", listOf("DL1XYZ", "ON4ZZ"), spectre = "G4ABC"))
        // The list's own spelling of the selected callsign.
        assertEquals("G4ABC/P", QrzDatv.parmi(listOf("F4XYZ", "G4ABC/P"), "G4ABC"))
        assertEquals("", QrzDatv.parmi(listOf("F4XYZ"), "G4ABC"))
        assertEquals("", QrzDatv.parmi(listOf("F4XYZ"), ""))
    }

    @Test
    fun une_fois_par_indicatif_et_une_requete_toutes_les_5_s() {
        val l = listOf("F4IOZ", "G4ABC")
        assertEquals("F4IOZ", cache.prochain(l))
        // In flight: nothing else.
        assertNull(cache.prochain(l))
        cache.recu("F4IOZ", QrzReponse.lis(xml.replace("G4ABC", "F4IOZ")))
        // Too soon after the last request.
        t += 4_999; assertNull(cache.prochain(l))
        t += 1; assertEquals("G4ABC", cache.prochain(l))
        cache.recu("G4ABC", QrzReponse.lis(xml))
        // All known: never asked again, until the 7 days are over.
        t += 3600_000; assertNull(cache.prochain(l))
        t += QrzDatv.DUREE_MS; assertEquals("F4IOZ", cache.prochain(l))
    }

    @Test
    fun inconnu_garde_comme_une_reponse() {
        assertEquals("ZZ9ZZZ", cache.prochain(listOf("ZZ9ZZZ")))
        cache.recu("ZZ9ZZZ", QrzReponse.lis(absent))
        assertEquals(QrzDatv.Sorte.INCONNU, QrzDatv.sorte(cache.entree("ZZ9ZZZ")!!.fiche))
        t += 60_000; assertNull(cache.prochain(listOf("ZZ9ZZZ")))
        assertTrue(QrzDatv.json(cache, listOf("ZZ9ZZZ"), true, null).getJSONObject("fiches").getJSONObject("ZZ9ZZZ").getBoolean("inconnu"))
    }

    @Test
    fun echecs_espaces_de_plus_en_plus() {
        val l = listOf("G4ABC")
        assertEquals("G4ABC", cache.prochain(l))
        cache.recu("G4ABC", QrzReponse.lis(refus))
        assertEquals("Username/password incorrect", cache.erreur)
        assertTrue(QrzDatv.json(cache, l, true, null).getJSONObject("fiches").getJSONObject("G4ABC").has("echec"))
        // Not again within 10 minutes.
        t += QrzDatv.PAUSE_INDICATIF_MS - 1; assertNull(cache.prochain(l))
        t += 1; assertEquals("G4ABC", cache.prochain(l))
        cache.recu("G4ABC", null)
        // Second failure: 20 minutes per callsign.
        t += QrzDatv.PAUSE_INDICATIF_MS; assertNull(cache.prochain(l))
        t += QrzDatv.PAUSE_INDICATIF_MS; assertEquals("G4ABC", cache.prochain(l))
        cache.recu("G4ABC", null)
        // Three failures in a row: another callsign waits 40 s, not 5.
        t += 39_000; assertNull(cache.prochain(listOf("F4IOZ")))
        t += 1_000; assertEquals("F4IOZ", cache.prochain(listOf("F4IOZ")))
        cache.recu("F4IOZ", QrzReponse.lis(xml.replace("G4ABC", "F4IOZ")))
        assertEquals("", cache.erreur)
    }

    @Test
    fun la_fiche_garde_sur_le_disque() {
        cache.prochain(listOf("G4ABC")); cache.recu("G4ABC", QrzReponse.lis(xml))
        val autre = QrzDatv.Cache { t + 3600_000 }
        autre.importe(org.json.JSONObject(cache.exporte().toString()))
        val f = autre.entree("G4ABC/P")!!.fiche
        assertEquals("John Smith", f.nom); assertEquals("IO93FS", f.carre); assertEquals(53.8, f.lat!!, 1e-9)
        assertTrue(f.lotw); assertFalse(f.eqsl)
        // Past 7 days: dropped.
        val tard = QrzDatv.Cache { t + QrzDatv.DUREE_MS + 1 }
        tard.importe(org.json.JSONObject(cache.exporte().toString()))
        assertNull(tard.entree("G4ABC"))
    }

    @Test
    fun la_carte_affichee() {
        val o = QrzDatv.carte(QrzReponse.lis(xml), "G4ABC", Observer(48.85, 2.35))
        assertEquals("John Smith", o.getString("nom")); assertEquals("Leeds", o.getString("qth"))
        assertEquals("England", o.getString("pays")); assertEquals("IO93FS", o.getString("carre"))
        assertEquals("Full", o.getString("classe"))
        assertTrue(o.getBoolean("lotw")); assertFalse(o.getBoolean("eqsl")); assertTrue(o.getBoolean("qslPapier"))
        assertEquals("https://www.qrz.com/db/G4ABC", o.getString("lien"))
        assertEquals("api/datv/qrz/photo?c=G4ABC", o.getString("photo"))
        assertTrue(o.getString("drapeau").isNotEmpty())
        // Paris to Leeds: some 600 km, to the north-west.
        assertTrue(o.getLong("km") in 550..700); assertTrue(o.getLong("azimut") in 300..350)
        // A photo elsewhere than QRZ is not offered.
        assertFalse(QrzDatv.carte(QrzReponse.lis(xml.replace("cdn-xml.qrz.com", "exemple.org")), "G4ABC", null).has("photo"))
    }

    @Test
    fun sans_compte_rien_n_est_demande_ni_montre() {
        val o = QrzDatv.json(cache, listOf("G4ABC"), false, null)
        assertFalse(o.getBoolean("compte")); assertEquals(0, o.getJSONObject("fiches").length())
        assertTrue(QrzDatv.json(cache, listOf("G4ABC"), true, null).getJSONObject("fiches").getJSONObject("G4ABC").getBoolean("attente"))
    }

    @Test
    fun photo_seulement_de_qrz() {
        assertTrue(QrzDatv.photoPermise("https://cdn-xml.qrz.com/c/g4abc/photo.jpg"))
        assertTrue(QrzDatv.photoPermise("http://files.qrz.com/x.png"))
        assertFalse(QrzDatv.photoPermise("https://qrz.com.exemple.org/x.jpg"))
        assertFalse(QrzDatv.photoPermise("https://evilqrz.com/x.jpg"))
        assertFalse(QrzDatv.photoPermise("https://user@cdn-xml.qrz.com/x.jpg"))
        assertFalse(QrzDatv.photoPermise("https://cdn-xml.qrz.com:8443/x.jpg"))
        assertFalse(QrzDatv.photoPermise("file:///etc/passwd"))
        assertFalse(QrzDatv.photoPermise(""))
        assertEquals("image/jpeg", QrzDatv.typeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("image/png", QrzDatv.typeImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertNull(QrzDatv.typeImage("<html>".toByteArray()))
    }
}
