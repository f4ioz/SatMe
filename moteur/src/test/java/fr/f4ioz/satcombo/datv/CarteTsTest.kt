/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The station card and the chat in the transport stream: the sections, the insertion, the reading back. */
class CarteTsTest {

    private val carte = CarteStation("f4ioz", "JN18fs", "Olivier", "Paris", "10 W", "Parabole 1,2 m", "SatMe-PC 2026.10.16",
        2_409_750L, "QPSK 2/3", 333, "Bonjour à tous — 73 ! 📡")

    @Test
    fun crc_mpeg2() {
        // CRC-32/MPEG-2's check value.
        assertEquals(0x0376E6E7, CarteTs.crc32("123456789".toByteArray()))
    }

    @Test
    fun carte_et_chat_aller_retour() {
        val s = CarteTs.section(carte)
        assertEquals(CarteTs.TABLE_CARTE, s[0].toInt() and 0xFF)
        assertEquals(0, CarteTs.crc32(s))
        val r = CarteTs.lis(s) as CarteStation
        assertEquals(carte.copy(indicatif = "F4IOZ", locator = "JN18FS"), r)
        val l = CarteTs.lis(CarteTs.section(LigneChat(0xBEEF, "F4IOZ", "Je te reçois 5/9, image parfaite"))) as LigneChat
        assertEquals(LigneChat(0xBEEF, "F4IOZ", "Je te reçois 5/9, image parfaite"), l)
        // Too long: cut at 140 characters, 255 bytes.
        assertEquals(140, (CarteTs.lis(CarteTs.section(LigneChat(1, "F4IOZ", "e".repeat(300)))) as LigneChat).texte.length)
        assertEquals(127, (CarteTs.lis(CarteTs.section(LigneChat(1, "F4IOZ", "é".repeat(300)))) as LigneChat).texte.length)
        // Version numbers kept.
        assertEquals(17, (CarteTs.lis(CarteTs.section(carte.copy(version = 17))) as CarteStation).version)
    }

    @Test
    fun corruption_et_formats_inconnus_ignores() {
        val s = CarteTs.section(carte)
        for (i in s.indices) {
            val c = s.copyOf(); c[i] = (c[i].toInt() xor 0x10).toByte()
            assertNull("octet $i", CarteTs.lis(c))
        }
        assertNull(CarteTs.lis(s.copyOf(s.size - 1)))
        assertNull(CarteTs.lis(ByteArray(0)))
        // A future layout (format ≥ 0x80): ignored. A new tag in format 1: skipped.
        assertNull(CarteTs.lis(avecFormat(s, 0x81)))
        val inconnu = CarteTs.section(CarteTs.TABLE_CARTE, 0, 0, listOf(0x7E to "plus tard", CarteTs.T_INDICATIF to "F1ABC"))
        assertEquals("F1ABC", (CarteTs.lis(inconnu) as CarteStation).indicatif)
        // Without a callsign: nothing.
        assertNull(CarteTs.lis(CarteTs.section(CarteTs.TABLE_CARTE, 0, 0, listOf(CarteTs.T_NOM to "x"))))
    }

    @Test
    fun frequence_de_retour_aller_retour_et_lecteurs_anciens() {
        val avec = carte.copy(retourHz = 10_489_650_000L, retourMode = "cw")
        val r = CarteTs.lis(CarteTs.section(avec)) as CarteStation
        assertEquals(10_489_650_000L, r.retourHz)
        assertEquals("CW", r.retourMode)
        assertTrue(r.retour)
        // Mode left out or unknown: USB.
        assertEquals("USB", (CarteTs.lis(CarteTs.section(avec.copy(retourMode = ""))) as CarteStation).retourMode)
        assertEquals("USB", (CarteTs.lis(CarteTs.section(avec.copy(retourMode = "FM"))) as CarteStation).retourMode)
        // Without it: no tag in the stream, nothing read.
        val sans = CarteTs.section(carte)
        val r0 = CarteTs.lis(sans) as CarteStation
        assertEquals(0L, r0.retourHz)
        assertEquals("", r0.retourMode)
        assertFalse(r0.retour)
        assertTrue(CarteTs.section(avec).size > sans.size)
        // An older reader (tags up to 0x0B): the new tags skipped, every other field the same.
        val anciens = tlv(CarteTs.section(avec)).filterKeys { it <= CarteTs.T_MESSAGE }
        assertEquals(tlv(sans), anciens)
        assertEquals(setOf(CarteTs.T_RETOUR, CarteTs.T_RETOUR_MODE), tlv(CarteTs.section(avec)).keys - anciens.keys)
        // A frequency outside the narrow band is carried, but is no talkback.
        assertFalse((CarteTs.lis(CarteTs.section(carte.copy(retourHz = 145_800_000L))) as CarteStation).retour)
        // Through a stream: the reader's card has it, the page too.
        var t = 0L
        val ins = InserteurCarte({ t })
        ins.carte = avec.copy(locator = "")
        val l = LecteurCarte({ t })
        flux(3000).forEachIndexed { k, p -> t = k.toLong(); l.bloc(ins.traite(p.copyOf())) }
        assertEquals(10_489_650_000L, l.carte?.retourHz)
        val j = l.json(t, null, { 0 }, 10_494_750L)
        assertEquals(10_489_650_000L, j.getLong("retourHz"))
        assertEquals("CW", j.getString("retourMode"))
        assertFalse(j.getBoolean("retourUrgence"))
        // Announced on the emergency frequency: flagged, the page offers no tuning.
        val urg = LecteurCarte({ 0L }).also { lu -> CarteTs.paquets(CarteTs.section(carte.copy(locator = "", retourHz = 10_489_860_000L))).forEachIndexed { k, p ->
            p[3] = (0x10 or k).toByte(); lu.bloc(p) } }
        assertTrue(urg.json(0L, null, { 0 }, 10_494_750L).getBoolean("retourUrgence"))
        // A card without talkback: no field at all.
        val sansJ = LecteurCarte({ 0L }).also { lu -> CarteTs.paquets(CarteTs.section(carte.copy(locator = ""))).forEachIndexed { k, p ->
            p[3] = (0x10 or k).toByte(); lu.bloc(p) } }.json(0L, null, { 0 }, 10_494_750L)
        assertFalse(sansJ.has("retourHz"))
    }

    /** A card section's fields, as a reader walking the TLV sees them. */
    private fun tlv(s: ByteArray): Map<Int, String> {
        val m = LinkedHashMap<Int, String>()
        var i = 13
        while (i + 2 <= s.size - 4) {
            val n = s[i + 1].toInt() and 0xFF
            m[s[i].toInt() and 0xFF] = String(s, i + 2, n, Charsets.UTF_8)
            i += 2 + n
        }
        return m
    }

    private fun avecFormat(s: ByteArray, f: Int): ByteArray {
        val c = s.copyOf(); c[12] = f.toByte()
        val crc = CarteTs.crc32(c, 0, c.size - 4)
        for (k in 0..3) c[c.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        return c
    }

    /** A stream: video packets (PID 0x100) and null packets, [n] in all. */
    private fun flux(n: Int, nulsSur: Int = 4): List<ByteArray> {
        val r = java.util.Random(3)
        var cc = 0
        return (0 until n).map { k ->
            ByteArray(188).also { p ->
                r.nextBytes(p); p[0] = 0x47
                if (k % nulsSur == 0) { p[1] = 0x1F; p[2] = 0xFF.toByte(); p[3] = 0x10 }
                else { p[1] = 0x01; p[2] = 0x00; p[3] = (0x10 or (cc++ and 15)).toByte() }
            }
        }
    }

    @Test
    fun insertion_debit_constant_compteurs_valides() {
        var t = 0L
        val ins = InserteurCarte({ t })
        ins.carte = carte
        val entree = flux(20_000)
        val sortie = entree.mapIndexed { k, p -> t = k.toLong(); ins.traite(p.copyOf()) }
        // As many packets; every packet not null is untouched and in its place.
        assertEquals(entree.size, sortie.size)
        for (k in entree.indices) {
            val pid = CarteTs.pid(entree[k])
            if (pid != CarteTs.PID_NUL) assertArrayEquals(entree[k], sortie[k])
            else assertTrue(CarteTs.pid(sortie[k]) in setOf(CarteTs.PID_NUL, CarteTs.PID))
        }
        // Our PID: counters following each other.
        val nous = sortie.filter { CarteTs.pid(it) == CarteTs.PID }
        assertTrue(nous.size >= 10)
        nous.zipWithNext().forEach { (a, b) -> assertEquals(((a[3].toInt() and 15) + 1) and 15, b[3].toInt() and 15) }
        assertTrue(ins.remplaces == nous.size.toLong())
        // Every 2 s (here 2000 packets): about ten cards.
        val l = LecteurCarte({ t })
        sortie.forEach { l.bloc(it) }
        assertEquals("F4IOZ", l.carte?.indicatif)
        assertEquals(10L, l.sections)
        assertEquals(0L, l.rejetees)
    }

    @Test
    fun sans_paquet_nul_rien_ne_change() {
        val ins = InserteurCarte({ 0L })
        ins.carte = carte
        val e = flux(500, nulsSur = Int.MAX_VALUE).drop(1)
        e.forEach { assertArrayEquals(it, ins.traite(it.copyOf())) }
        assertEquals(0L, ins.remplaces)
    }

    @Test
    fun chat_repete_lu_une_fois_blocs_quelconques() {
        var t = 0L
        val ins = InserteurCarte({ t }, repetitionsChat = 4)
        ins.carte = carte
        ins.ajoute(LigneChat(7, "F4IOZ", "premier"))
        ins.ajoute(LigneChat(8, "F4IOZ", "second"))
        val sortie = flux(8000).mapIndexed { k, p -> t = k.toLong(); ins.traite(p.copyOf()) }
        val tout = java.io.ByteArrayOutputStream().also { o -> o.write(byteArrayOf(0x12, 0x47, 0x00)); sortie.forEach { o.write(it) } }.toByteArray()
        // Blocks of odd sizes, a few bytes of rubbish first: the same lines, once each.
        val l = LecteurCarte({ t })
        val r = java.util.Random(5)
        var i = 0
        while (i < tout.size) { val n = minOf(1 + r.nextInt(700), tout.size - i); l.bloc(tout.copyOfRange(i, i + n)); i += n }
        assertEquals(listOf("premier", "second"), l.chat().map { it.ligne.texte })
        assertEquals(listOf(7, 8), l.chat().map { it.ligne.seq })
        assertNotNull(l.carte)
    }

    @Test
    fun paquet_perdu_section_abandonnee_la_suivante_lue() {
        var t = 0L
        val ins = InserteurCarte({ t }, periodeCarteMs = 100)
        ins.carte = carte.copy(message = "x".repeat(140), antenne = "a".repeat(48), nom = "n".repeat(48), qth = "q".repeat(48))
        val sortie = flux(4000, nulsSur = 2).mapIndexed { k, p -> t = k.toLong(); ins.traite(p.copyOf()) }
        val nous = sortie.withIndex().filter { CarteTs.pid(it.value) == CarteTs.PID }
        // The card takes several packets: one of the first card's lost.
        assertTrue(CarteTs.paquets(CarteTs.section(ins.carte!!)).size >= 2)
        val perdu = nous[1].index
        val l = LecteurCarte({ t })
        sortie.forEachIndexed { k, p -> if (k != perdu) l.bloc(p) }
        assertEquals(1L, l.rejetees)
        assertTrue(l.sections >= 30)
        assertEquals("F4IOZ", l.carte?.indicatif)
        // A damaged packet (transport error flag): its section dropped too.
        val l2 = LecteurCarte({ t })
        sortie.forEachIndexed { k, p -> l2.bloc(if (k == perdu) p.copyOf().also { it[1] = (it[1].toInt() or 0x80).toByte() } else p) }
        assertEquals(1L, l2.rejetees)
    }

    @Test
    fun version_suit_le_contenu() {
        val ins = InserteurCarte({ 0L })
        ins.carte = carte
        assertEquals(0, ins.carte!!.version)
        ins.carte = carte
        assertEquals(0, ins.carte!!.version)
        ins.carte = carte.copy(message = "QRT dans 5 min")
        assertEquals(1, ins.carte!!.version)
    }

    @Test
    fun adif_datv_qo100_et_terrestre() {
        val qo = fr.f4ioz.satcombo.data.LogEntry(timeMs = 1_760_000_000_000L, satName = "QO-100", catnum = 43700, azimuthDeg = 150.0, elevationDeg = 30.0,
            callsign = "F1ABC", mode = "DATV", rstSent = "8.5dB", downlinkMhz = 10_494.75, uplinkMhz = 2_405.25)
        val a = fr.f4ioz.satcombo.data.Adif.enregistrement(qo)
        assertTrue(a, a.contains("<SAT_NAME:6>QO-100") && a.contains("<MODE:3>ATV") && a.contains("<PROP_MODE:3>SAT"))
        val t = fr.f4ioz.satcombo.data.Adif.enregistrement(qo.copy(satName = fr.f4ioz.satcombo.data.Adif.DATV_TERRESTRE, catnum = 0,
            downlinkMhz = 1255.0, uplinkMhz = 1255.0, elevationDeg = 0.0))
        assertTrue(t, t.contains("<MODE:3>ATV") && t.contains("<FREQ:11>1255.000000") && !t.contains("SAT_") && !t.contains("PROP_MODE") && !t.contains("ANT_"))
    }
}
