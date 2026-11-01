/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.ControleAdif
import fr.f4ioz.satcombo.domain.ModifEnvoi
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contest and portable fields: disk, ADIF both ways, the check, a POTA activation. Fictitious contacts only. */
class ChampsAdifTest {

    private val t0 = 1_790_000_000_000L

    private fun base(c: String = "F5ZZZ", t: Long = t0) =
        LogEntry(t, "SO-50", 27607, 100.0, 20.0, myLocator = "JN18FS", callsign = c, theirLocator = "JN03",
            mode = "FM", uplinkMhz = 145.850, downlinkMhz = 436.795)

    /** Every field set, each to a valid value. */
    private fun complet() = base().copy(
        contestId = "CQ-WW-SSB", stx = 12, srx = 345, stxString = "59 14", srxString = "59 05",
        operateur = "F4XYZ", stationCallsign = "F4XYZ/P",
        mySig = "POTA", mySigInfo = "FR-0012", sig = "WWFF", sigInfo = "FFF-1234",
        myPotaRef = "FR-0012", potaRef = "K-0817,K-4566@US-WY", myWwffRef = "FFF-0001", wwffRef = "DLFF-0002",
        mySotaRef = "F/AB-123", sotaRef = "W2/WE-003", myIota = "EU-064", iota = "NA-001",
        cqz = 14, ituz = 27, precedence = "A", check = "92", classe = "2A", arrlSect = "ENY",
        state = "NY", cnty = "NY,Kings", myState = "75")

    private fun tmp() = java.io.File.createTempFile("qso_log", ".json").apply { deleteOnExit() }

    @Test
    fun `un ancien carnet se lit tel quel, sans aucun champ nouveau`() {
        val f = tmp()
        f.writeText("""[{"t":$t0,"s":"SO-50","c":27607,"az":100,"el":20,"n":"","ml":"JN18FS","cs":"F5ZZZ","tl":"JN03",
            "mg":"","md":"FM","rs":"59","rr":"59","dl":436.795,"ul":145.85,"ev":0,"lo":"SAISI","nm":"","qt":"","em":""}]""")
        val e = LogStore(f).load().single()
        assertEquals("F5ZZZ", e.callsign)
        assertTrue(ChampsAdif.valeurs(e).isEmpty())
        assertNull(e.stx); assertNull(e.cqz)
        // Saved again: still no "x" key, the file stays what an older version wrote.
        LogStore(f).save(listOf(e))
        assertFalse(JSONArray(f.readText()).getJSONObject(0).has("x"))
    }

    @Test
    fun `les champs nouveaux survivent a l-enregistrement et a la relecture`() {
        val f = tmp()
        val e = complet()
        LogStore(f).save(listOf(e, base(t = t0 - 60_000)))
        val l = LogStore(f).load()
        assertEquals(e, l[0])
        assertEquals(base(t = t0 - 60_000), l[1])
        val o = JSONArray(f.readText()).getJSONObject(0)
        assertEquals(12, o.getJSONObject("x").getInt("STX"))
        assertFalse(JSONArray(f.readText()).getJSONObject(1).has("x"))
    }

    @Test
    fun `une version plus ancienne lit le nouveau fichier en ignorant la cle x`() {
        val f = tmp()
        LogStore(f).save(listOf(complet()))
        // What an older version reads: the known keys only.
        val o = JSONArray(f.readText()).getJSONObject(0)
        assertEquals("F5ZZZ", o.optString("cs"))
        assertEquals(t0, o.getLong("t"))
        assertTrue(o.has("x"))
    }

    @Test
    fun `une modification garde les champs, et le lot telephone les transporte`() {
        val f = tmp()
        val s = LogStore(f)
        s.save(listOf(complet()))
        s.update(t0) { it.copy(note = "corrigé") }
        assertEquals("FR-0012", s.load().single().myPotaRef)
        val lot = LotContacts.lit(LotContacts.ecrit(s.load()))!!.single()
        assertEquals(ChampsAdif.valeurs(complet()), ChampsAdif.valeurs(lot))
        // An editor that carries them: all copied, a cleared one cleared.
        val m = complet().copy(potaRef = "", stx = 13)
        val r = ChampsAdif.reprend(m, base())
        assertEquals("", r.potaRef); assertEquals(13, r.stx); assertEquals("F/AB-123", r.mySotaRef)
    }

    @Test
    fun `l-export ADIF ecrit chaque champ et l-import les relit`() {
        val e = complet()
        val adif = Adif.export(listOf(e), "F4XYZ")
        val f = AdifImport.champs(adif.substringAfter("<EOH>"))
        ChampsAdif.valeurs(e).forEach { (tag, v) -> assertEquals(tag, v, f[tag]) }
        // The contact's own station callsign in a file; the station's is the operator unless the contact says one.
        assertEquals("F4XYZ/P", f["STATION_CALLSIGN"])
        assertEquals("F4XYZ", f["OPERATOR"])
        // The zones given beat the prefix's, written once.
        assertEquals(1, Regex("<CQZ:").findAll(adif).count())
        val q = AdifImport.contactsEnLigne(adif).single()
        val relu = q.complete(base())
        assertEquals(ChampsAdif.valeurs(e), ChampsAdif.valeurs(relu))
    }

    @Test
    fun `un contact sans champ nouveau donne le meme ADIF qu-avant`() {
        val r = Adif.enregistrement(base(), "F4XYZ/M", "F4XYZ")
        assertTrue(r.contains("<STATION_CALLSIGN:7>F4XYZ/M"))
        assertTrue(r.contains("<OPERATOR:5>F4XYZ"))
        ChampsAdif.TAGS.filter { it !in setOf("STATION_CALLSIGN", "OPERATOR", "CQZ", "ITUZ") }.forEach { assertFalse(it, r.contains("<$it:")) }
    }

    @Test
    fun `l-envoi en ligne garde la station du profil et transporte les champs`() {
        val r = AdifImport.champs(Adif.enregistrement(complet(), "F4XYZ/M", "F4XYZ"))
        assertEquals("F4XYZ/M", r["STATION_CALLSIGN"])
        assertEquals("F4XYZ", r["OPERATOR"])
        assertEquals("FR-0012", r["MY_POTA_REF"])
        assertEquals("12", r["STX"])
    }

    @Test
    fun `un numero de serie illisible n-est pas perdu a l-import`() {
        val e = ChampsAdif.avec(base(), mapOf("STX" to "5NN-001", "SRX" to "007"))
        assertNull(e.stx); assertEquals("5NN-001", e.stxString)
        assertEquals(7, e.srx)
    }

    @Test
    fun `un contact deja envoye sans champ nouveau ne devient pas modifie`() {
        val e = base()
        val envoye = e.copy(envoyeMs = t0 + 1000, envoiAdif = ModifEnvoi.ecrit(ModifEnvoi.empreinte(e)))
        assertFalse(ModifEnvoi.modifie(envoye))
        val ref = envoye.copy(potaRef = "FR-0012")
        assertEquals("référence POTA", ModifEnvoi.ecarts(ref).single().libelle)
    }

    @Test
    fun `les formats de reference`() {
        listOf("FR-0012", "K-10000", "VE-5082@CA-AB", "8P-0012", "K-0817,K-4566@US-WY", "k-4562@us-ca")
            .forEach { assertTrue(it, ChampsAdif.potaValide(it)) }
        listOf("FR-12", "FR0012", "K-0817,", "FRANCE-0001").forEach { assertFalse(it, ChampsAdif.potaValide(it)) }
        listOf("KFF-4655", "3DAFF-0002", "fff-1234").forEach { assertTrue(it, ChampsAdif.wwffValide(it)) }
        listOf("FF-123", "FFF-12345", "FR-0012").forEach { assertFalse(it, ChampsAdif.wwffValide(it)) }
        listOf("W2/WE-003", "G/LD-003", "F/AB-123").forEach { assertTrue(it, ChampsAdif.sotaValide(it)) }
        listOf("F-AB-123", "F/AB123", "F/AB-12").forEach { assertFalse(it, ChampsAdif.sotaValide(it)) }
        listOf("EU-064", "NA-001", "oc-999").forEach { assertTrue(it, ChampsAdif.iotaValide(it)) }
        listOf("EU-64", "XX-001", "EU-000", "EU-1000").forEach { assertFalse(it, ChampsAdif.iotaValide(it)) }
    }

    @Test
    fun `le controle ADIF avertit sans bloquer`() {
        val e = base().copy(myPotaRef = "FR12", wwffRef = "FFF-12", sotaRef = "F-AB-1", iota = "EU64", cqz = 55, ituz = 0, stx = -1)
        val l = ControleAdif.controle(listOf(e), t0 + 86_400_000L)
        val codes = l.map { it.code }
        listOf("pota-invalide", "wwff-invalide", "sota-invalide", "iota-invalide", "zone-invalide", "numero-invalide")
            .forEach { assertTrue(it, it in codes) }
        assertTrue(l.filter { it.code in setOf("pota-invalide", "wwff-invalide", "sota-invalide", "iota-invalide", "zone-invalide", "numero-invalide") }
            .all { it.gravite == ControleAdif.Gravite.AVERTISSEMENT })
        // Valid ones say nothing.
        val ok = ControleAdif.controle(listOf(complet()), t0 + 86_400_000L).map { it.code }
        listOf("pota-invalide", "wwff-invalide", "sota-invalide", "iota-invalide", "zone-invalide", "numero-invalide")
            .forEach { assertFalse(it, it in ok) }
    }

    @Test
    fun `une activation POTA marque ses contacts`() {
        val l = listOf(base(), base("F6AAA", t0 + 60_000).copy(myPotaRef = "FR-0345"),
            base("F6BBB", t0 + 120_000).copy(mySig = "WWFF", mySigInfo = "FFF-0001"))
        val r = ChampsAdif.enActivationPota(l, "fr-0012")
        assertEquals("FR-0012", r[0].myPotaRef); assertEquals("POTA", r[0].mySig); assertEquals("FR-0012", r[0].mySigInfo)
        // A contact with its own park keeps it.
        assertEquals("FR-0345", r[1].myPotaRef); assertEquals("", r[1].mySig)
        // Another activity's MY_SIG stays; the park still goes in MY_POTA_REF.
        assertEquals("FR-0012", r[2].myPotaRef); assertEquals("WWFF", r[2].mySig); assertEquals("FFF-0001", r[2].mySigInfo)
        assertEquals(l, ChampsAdif.enActivationPota(l, " "))
        val adif = Adif.export(r, "F4XYZ")
        assertTrue(adif.contains("<MY_POTA_REF:7>FR-0012"))
        assertTrue(adif.contains("<MY_SIG:4>POTA"))
        assertTrue(adif.contains("<MY_SIG_INFO:7>FR-0012"))
    }

    @Test
    fun `une reference POTA trouvee dans le nom de l-activation`() {
        assertEquals("FR-0012", ChampsAdif.potaDans("Parc FR-0012 au sommet"))
        assertEquals("FR-0012,FR-0345", ChampsAdif.potaDans("fr-0012 / FR-0345"))
        assertEquals("", ChampsAdif.potaDans("Colline de JN18, QO-100"))
    }
}
