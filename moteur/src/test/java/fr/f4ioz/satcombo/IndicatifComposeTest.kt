/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.data.Qrz
import fr.f4ioz.satcombo.demo.ApiStation
import fr.f4ioz.satcombo.domain.Dxcc
import fr.f4ioz.satcombo.domain.IndicatifCompose
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compound callsigns: who (the directory's record) and where (the entity, the flag). */
class IndicatifComposeTest {

    private fun d(c: String) = IndicatifCompose.decoupe(c)

    @Test
    fun prefixe_devant() {
        d("EA3/M5JFS").let { assertEquals("M5JFS", it.base); assertEquals("EA3", it.prefixe); assertTrue(it.deplace) }
        d("OH0/DL1ABC").let { assertEquals("DL1ABC", it.base); assertEquals("OH0", it.prefixe) }
        d("KH6/W1AW").let { assertEquals("W1AW", it.base); assertEquals("KH6", it.prefixe) }
        d("3D2/K1ABC").let { assertEquals("K1ABC", it.base); assertEquals("3D2", it.prefixe) }
        d("VP2E/K1ABC").let { assertEquals("K1ABC", it.base); assertEquals("VP2E", it.prefixe) }
        d("F/ON4ABC").let { assertEquals("ON4ABC", it.base); assertEquals("F", it.prefixe) }
    }

    @Test
    fun prefixe_et_suffixe() {
        d("F/ON4ABC/P").let { assertEquals("ON4ABC", it.base); assertEquals("F", it.prefixe); assertEquals(listOf("P"), it.suffixes) }
        d("LA/DF2ET/P").let { assertEquals("DF2ET", it.base); assertEquals("LA", it.prefixe) }
    }

    @Test
    fun prefixe_derriere() {
        d("DL1ABC/OH0").let { assertEquals("DL1ABC", it.base); assertEquals("OH0", it.prefixe) }
        d("F4IOZ/FG").let { assertEquals("F4IOZ", it.base); assertEquals("FG", it.prefixe) }
    }

    @Test
    fun suffixes_d_exploitation() {
        d("M5JFS/P").let { assertEquals("M5JFS", it.base); assertEquals("", it.prefixe); assertTrue(it.deplace) }
        d("G4ABC/MM").let { assertTrue(it.maritime); assertEquals("G4ABC", it.base) }
        d("N1XYZ/AM").let { assertTrue(it.aeronautique) }
        d("F4IOZ/QRP").let { assertEquals("F4IOZ", it.base); assertFalse(it.deplace) }
        d("W1AW/4").let { assertEquals("W1AW", it.base); assertEquals("4", it.chiffre); assertTrue(it.deplace) }
        d("K1ABC/A").let { assertEquals("K1ABC", it.base); assertTrue(it.deplace) }
    }

    @Test
    fun indicatif_simple() {
        d("f4ioz ").let { assertEquals("F4IOZ", it.base); assertFalse(it.compose); assertFalse(it.deplace) }
        assertEquals("", d("").base)
    }

    @Test
    fun le_pays_est_celui_du_lieu() {
        assertEquals("Espagne", Dxcc.entite("EA3/M5JFS")?.nom)
        assertEquals("Angleterre", Dxcc.entite("M5JFS/P")?.nom)
        assertEquals("Åland", Dxcc.entite("DL1ABC/OH0")?.nom)
        assertEquals("Åland", Dxcc.entite("OH0/DL1ABC")?.nom)
        assertEquals("Hawaï", Dxcc.entite("KH6/W1AW")?.nom)
        assertEquals("Fidji", Dxcc.entite("3D2/K1ABC")?.nom)
        assertEquals("Anguilla", Dxcc.entite("VP2E/K1ABC")?.nom)
        assertEquals("France", Dxcc.entite("F/ON4ABC/P")?.nom)
        assertEquals("Guadeloupe", Dxcc.entite("F4IOZ/FG")?.nom)
        assertEquals("États-Unis", Dxcc.entite("W1AW/4")?.nom)
        // At sea or in the air: no entity.
        assertNull(Dxcc.entite("G4ABC/MM"))
        assertNull(Dxcc.entite("N1XYZ/AM"))
    }

    @Test
    fun le_drapeau() {
        assertEquals("🇪🇸", Dxcc.emoji(Dxcc.entite("EA3/M5JFS")))
        // England: the black flag and the subdivision tags g b e n g.
        val eng = Dxcc.emoji(Dxcc.entite("M5JFS/P"))
        assertEquals(0x1F3F4, eng.codePointAt(0))
        assertEquals("gbeng", eng.codePoints().toArray().drop(1).dropLast(1).map { (it - 0xE0000).toChar() }.joinToString(""))
        assertEquals(0xE007F, eng.codePoints().toArray().last())
        assertEquals("", Dxcc.emoji(Dxcc.entite("G4ABC/MM")))
        assertEquals("⚓", ApiStation.drapeau(JSONObject(), "G4ABC/MM").optString("dr"))
        assertEquals("Espagne", ApiStation.drapeau(JSONObject(), "EA3/M5JFS").optString("py"))
        // Jersey has its own flag, not the Union's.
        assertEquals("🇯🇪", Dxcc.emoji(Dxcc.entite("MJ0ABC")))
    }

    @Test
    fun le_lieu_saisi_donne_le_centre_du_pays() {
        val o = ApiStation.lieuSaisi(JSONObject(), "EA3/M5JFS")
        assertEquals("M5JFS", o.getString("base")); assertEquals("EA3", o.getString("op"))
        assertEquals(40.3, o.getDouble("clat"), 0.01)
        assertFalse(ApiStation.lieuSaisi(JSONObject(), "G4ABC/MM").has("clat"))
    }

    /** Every entity of the table has its centre and its flag code. */
    @Test
    fun chaque_entite_a_son_centre() {
        listOf("F4IOZ", "XE1ABC", "ZL1ABC", "VK2ABC", "JA1ABC", "PY2ABC", "5B4ABC", "A71ABC", "KP2ABC", "KH0ABC").forEach {
            val e = Dxcc.entite(it); assertNotNull(it, e?.lat); assertEquals(it, 2, e!!.iso.length)
        }
    }

    // ------------------------------------------------------------ QRZ fallback

    private fun xml(call: String, grid: String, fname: String, country: String, lat: String = "52.1", lon: String = "-1.2") =
        """<QRZDatabase xmlns="http://xmldata.qrz.com"><Callsign><call>$call</call><grid>$grid</grid><fname>$fname</fname><name>Smith</name>
           <addr1>1 High St</addr1><addr2>Leicester</addr2><country>$country</country><lat>$lat</lat><lon>$lon</lon><email>j@x.uk</email>
           <image>https://x/p.jpg</image><cqzone>14</cqzone></Callsign><Session><Key>k</Key></Session></QRZDatabase>"""

    private val absent = """<QRZDatabase xmlns="http://xmldata.qrz.com"><Session><Error>Not found: X</Error><Key>k</Key></Session></QRZDatabase>"""

    /** A fake directory: [connus] callsign → XML; counts the queries. */
    private class Faux(val connus: Map<String, String>, val absent: String) {
        val demandes = ArrayList<String>()
        fun repond(q: String): String {
            if (q.startsWith("username=")) return "<QRZDatabase><Session><Key>k</Key></Session></QRZDatabase>"
            val c = java.net.URLDecoder.decode(q.substringAfter("callsign="), "UTF-8")
            demandes.add(c)
            return connus[c] ?: absent
        }
    }

    private fun qrz(f: Faux) = Qrz(f::repond).also { assertEquals("", it.connecte("u", "p")) }

    @Test
    fun indicatif_compose_inconnu_reprend_l_identite_du_titulaire() {
        val f = Faux(mapOf("M5JFS" to xml("M5JFS", "IO92kp", "John", "England")), absent)
        val q = qrz(f)
        val r = q.cherche("EA3/M5JFS")
        assertEquals(listOf("EA3/M5JFS", "M5JFS"), f.demandes)
        assertEquals("", r.erreur)
        assertEquals("EA3/M5JFS", r.indicatif)
        assertEquals("John Smith", r.nom)
        assertEquals("j@x.uk", r.courriel)
        assertEquals("https://x/p.jpg", r.image)
        assertEquals("M5JFS", r.identiteDe); assertEquals("EA3", r.lieuOpere)
        // Not the home position.
        assertEquals("", r.carre); assertNull(r.lat); assertNull(r.lon); assertEquals("", r.adresse); assertEquals("", r.qth); assertEquals("", r.zoneCq)
        assertEquals("Espagne", r.pays)
        // Cached: asking again costs nothing.
        q.cherche("EA3/M5JFS")
        assertEquals(2, f.demandes.size)
        // The JSON says so, and gives no place.
        val o = ApiStation.qrz(r, null, true)
        assertEquals("M5JFS", o.getString("identiteDe")); assertFalse(o.has("lat")); assertTrue(o.getString("dr").isNotEmpty())
    }

    @Test
    fun indicatif_compose_connu_garde_sa_fiche() {
        val f = Faux(mapOf("EA3/M5JFS" to xml("EA3/M5JFS", "JN11cj", "John", "Spain", "41.4", "2.2")), absent)
        val r = qrz(f).cherche("EA3/M5JFS")
        assertEquals(listOf("EA3/M5JFS"), f.demandes)
        assertEquals("JN11CJ", r.carre); assertEquals("", r.identiteDe)
    }

    /** QRZ may answer the compound call with the home record: its square is not taken either. */
    @Test
    fun la_fiche_du_titulaire_rendue_pour_le_compose_perd_sa_position() {
        val f = Faux(mapOf("M5JFS/P" to xml("M5JFS", "IO92kp", "John", "England")), absent)
        val r = qrz(f).cherche("M5JFS/P")
        assertEquals(listOf("M5JFS/P"), f.demandes)
        assertEquals("", r.carre); assertEquals("M5JFS", r.identiteDe); assertEquals("/P", r.lieuOpere); assertEquals("Angleterre", r.pays)
    }

    @Test
    fun personne_ne_le_connait() {
        val f = Faux(emptyMap(), absent)
        val r = qrz(f).cherche("EA3/M5JFS")
        assertTrue(r.erreur.contains("Not found"))
        assertEquals(listOf("EA3/M5JFS", "M5JFS"), f.demandes)
    }

    /** QRP does not move the station: its record stays whole. */
    @Test
    fun qrp_garde_la_position() {
        val f = Faux(mapOf("M5JFS" to xml("M5JFS", "IO92kp", "John", "England")), absent)
        val r = qrz(f).cherche("M5JFS/QRP")
        assertEquals("IO92KP", r.carre); assertEquals("M5JFS", r.identiteDe)
    }

    @Test
    fun un_simple_indicatif_ne_cherche_qu_une_fois() {
        val f = Faux(emptyMap(), absent)
        qrz(f).cherche("M5JFS")
        assertEquals(listOf("M5JFS"), f.demandes)
    }
}
