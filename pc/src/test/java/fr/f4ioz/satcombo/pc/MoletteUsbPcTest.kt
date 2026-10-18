/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.MoletteUsb
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** The USB knob on SatMe-PC: the kernel's list, its events, the gestures, the reader. */
class MoletteUsbPcTest {

    private val texte = javaClass.getResource("/proc-bus-input-devices.txt")!!.readText()

    /** What the knob did, in order. */
    private class Monde(var ctx: MoletteUsb.Contexte = MoletteUsb.Contexte(null, catRelie = true, sdrEnMarche = false)) : MoletteUsbPc.Monde {
        val faits = mutableListOf<String>()
        override fun contexte(page: String?) = ctx.copy(contexte = page)
        override fun vfo(dHz: Long) { faits += "vfo $dHz" }
        override fun shiftRx(dHz: Long) { faits += "rx $dHz" }
        override fun shiftTx(dHz: Long) { faits += "tx $dHz" }
        override fun zeroRx() { faits += "rx 0" }
        override fun zeroTx() { faits += "tx 0" }
        override fun sdr(dHz: Long) { faits += "sdr $dHz" }
        override fun qo100(dHz: Long) { faits += "qo100 $dHz" }
    }

    private fun flux(vararg ev: Triple<Int, Int, Int>): ByteArray = ByteArrayOutputStream().apply {
        ev.forEach { (t, c, v) -> write(EntreesLinux.encode(t, c, v)) }
    }.toByteArray()

    private fun cran(code: Int) = arrayOf(Triple(1, code, 1), Triple(0, 0, 0), Triple(1, code, 0), Triple(0, 0, 0))

    @Test
    fun la_liste_du_noyau_de_ce_pc() {
        val l = EntreesLinux.lit(texte)
        assertEquals(9, l.size)
        val kbd = l.single { it.evenement == "event21" }
        assertEquals("Arduino LLC iWit USB Controller Keyboard", kbd.nom)
        assertEquals("2341:484d", kbd.id)
        assertTrue(kbd.volume); assertTrue(kbd.molette)
        // Its mouse half sends no volume: never chosen.
        val souris = l.single { it.evenement == "event7" }
        assertFalse(souris.volume); assertFalse(souris.molette)
        // The PC's keyboard has the volume keys too, but is no knob, and a whole keyboard.
        val clavier = l.single { it.evenement == "event3" }
        assertTrue(clavier.volume); assertFalse(clavier.molette); assertTrue(clavier.lettres)
        val conso = l.single { it.evenement == "event5" }
        assertTrue(conso.volume); assertFalse(conso.lettres)
        assertFalse(l.single { it.evenement == "event1" }.volume)
        // The picker offers those with the volume keys.
        assertEquals(listOf("event3", "event5", "event21"), l.filter { it.volume }.map { it.evenement })
    }

    @Test
    fun le_choix_de_l_appareil() {
        val l = EntreesLinux.lit(texte)
        assertEquals("event21", EntreesLinux.choisit(l, "")?.evenement)
        assertEquals("event5", EntreesLinux.choisit(l, "Fujitsu Technology Solutions GmbH Fujitsu Keyboard Consumer Control")?.evenement)
        assertNull(EntreesLinux.choisit(l, "rien de tel"))
        // Never the PC's keyboard by itself.
        assertNull(EntreesLinux.choisit(l.filter { !it.molette }, ""))
        val m = MoletteUsbPc(StockageMemoire(), SettingsStore(StockageMemoire()), Monde())
        assertTrue(m.captureAutorisee(l.single { it.evenement == "event21" }))
        assertTrue(m.captureAutorisee(l.single { it.evenement == "event5" }))
        assertFalse(m.captureAutorisee(l.single { it.evenement == "event3" }))
    }

    @Test
    fun les_bits_du_noyau() {
        assertTrue(EntreesLinux.bit("ffbeffdfffefffff fffffffffffffffe", 115))
        assertFalse(EntreesLinux.bit("ffbeffdfffefffff fffffffffffffffe", 0))
        assertTrue(EntreesLinux.bit("1 0", 64))
        assertFalse(EntreesLinux.bit("1 0", 200))
        assertFalse(EntreesLinux.bit("", 3))
    }

    @Test
    fun un_evenement_du_noyau() {
        val b = EntreesLinux.encode(1, 115, 1)
        assertEquals(24, b.size)
        assertEquals(EntreesLinux.Evenement(1, 115, 1), EntreesLinux.decode(b))
        assertEquals(EntreesLinux.Evenement(4, 4, -2), EntreesLinux.decode(EntreesLinux.encode(4, 4, -2)))
        // The bytes the kernel writes (little-endian), the time ignored.
        val brut = ByteArray(16) { 0x55 } + byteArrayOf(1, 0, 114, 0, 2, 0, 0, 0)
        assertEquals(EntreesLinux.Evenement(1, 114, 2), EntreesLinux.decode(brut))
    }

    @Test
    fun les_gestes_comme_sur_le_telephone() {
        val st = SettingsStore(StockageMemoire()).apply { moletteVfo = true; molettePasHz = 100L }
        val w = Monde()
        val m = MoletteUsbPc(StockageMemoire(), st, w)
        m.recoit(115, 1); m.recoit(115, 0); m.recoit(114, 1); m.recoit(114, 2)
        assertEquals(listOf("vfo 100", "vfo -100", "vfo -100"), w.faits)
        // The press: the step (and a held press does not cycle it).
        m.recoit(113, 1); m.recoit(113, 2)
        assertEquals(1000L, st.molettePasHz)
        // A learned key picks the TX shift; the knob then moves it.
        m.regle(mapOf("apprendre" to "1"))
        m.recoit(30, 1)
        assertEquals(MoletteUsb.DECALAGE_LINUX + 30, st.macroCodeB)
        m.recoit(30, 1)
        assertEquals("SHIFT_TX", st.moletteCible)
        m.recoit(115, 1)
        assertEquals("tx 1000", w.faits.last())
        // Press as « zero »: only the current target.
        m.regle(mapOf("action" to "ZERO"))
        m.recoit(113, 1)
        assertEquals("tx 0", w.faits.last())
        // The SDR tab: the dongle, whatever the target.
        m.contextePage("sdr")
        m.recoit(114, 1)
        assertEquals("sdr -1000", w.faits.last())
        m.contextePage("qo100"); m.recoit(115, 1)
        assertEquals("qo100 1000", w.faits.last())
        // A fixed choice wins over the tab.
        m.regle(mapOf("accord" to "POSTE")); m.recoit(115, 1)
        assertEquals("tx 1000", w.faits.last())
        // Off: nothing moves.
        st.moletteVfo = false
        val n = w.faits.size
        m.recoit(115, 1)
        assertEquals(n, w.faits.size)
    }

    @Test
    fun le_lecteur_sur_un_flux_factice() {
        val dossier = kotlin.io.path.createTempDirectory("molette").toFile()
        val liste = File(dossier, "devices").apply { writeText(texte) }
        val st = SettingsStore(StockageMemoire()).apply { moletteVfo = true; molettePasHz = 10L }
        val w = Monde(MoletteUsb.Contexte(null, catRelie = false, sdrEnMarche = true))
        var ouverts = 0
        var chemin = ""
        var veut: Boolean? = null
        val m = MoletteUsbPc(StockageMemoire(), st, w, fichierAppareils = liste, dossierDev = dossier, ouvre = { f, v ->
            ouverts++; chemin = f.name; veut = v
            if (ouverts > 1) throw EntreesLinux.Refus("plus rien")
            EntreesLinux.SourceDirecte(ByteArrayInputStream(flux(*cran(115), *cran(115), Triple(4, 4, 7), *cran(114))))
        })
        m.demarre()
        val fin = System.currentTimeMillis() + 5_000
        while (w.faits.size < 3 && System.currentTimeMillis() < fin) Thread.sleep(20)
        assertEquals(listOf("sdr 10", "sdr 10", "sdr -10"), w.faits)
        assertEquals("event21", chemin)
        assertEquals(true, veut)        // the knob captured by default
        assertNotNull(m.json().optJSONObject("dernier"))
        m.arrete()
        dossier.deleteRecursively()
    }

    /** The helper itself on a file of events: no capture there (not a device), the keys read, the end seen. */
    @Test
    fun l_assistant_sur_un_fichier() {
        val py = EntreesLinux.cherchePython()
        assumeTrue(py != null)
        val f = File.createTempFile("molette", ".ev")
        f.writeBytes(flux(*cran(115), Triple(1, 113, 1)))
        val s = EntreesLinux.SourceAssistant(py!!, f.path, veut = true)
        assertFalse(s.capture)
        assertNotNull(s.refusCapture)
        val lus = generateSequence { s.lit() }.toList()
        assertEquals(listOf(EntreesLinux.Evenement(1, 115, 1), EntreesLinux.Evenement(1, 115, 0), EntreesLinux.Evenement(1, 113, 1)), lus)
        s.close()
        f.delete()
        // A missing device: refused, with the reason.
        val r = runCatching { EntreesLinux.SourceAssistant(py, "/nonexistent/event99", veut = false) }
        assertTrue(r.exceptionOrNull() is EntreesLinux.Refus)
    }

    @Test
    fun la_regle_udev() {
        assertEquals("echo 'SUBSYSTEM==\"input\", ATTRS{idVendor}==\"2341\", ATTRS{idProduct}==\"484d\", MODE=\"0660\", GROUP=\"plugdev\"' | " +
            "sudo tee /etc/udev/rules.d/70-satme-molette.rules && sudo udevadm control --reload && sudo udevadm trigger", EntreesLinux.regleUdev())
    }
}
