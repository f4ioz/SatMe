/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CartesSonPcTest {

    @get:Rule val dossier = TemporaryFolder()

    private val CODEC = "CODEC [plughw:1,0]"
    private val PCH = "PCH [plughw:0,0]"
    private val PCH_ALT = "PCH [plughw:0,2]"
    private val HUB = "sys/devices/pci0000:00/0000:00:14.0/usb1/1-10/1-10.1/1-10.1.1"

    private fun ecrit(r: File, chemin: String, texte: String) = File(r, chemin).apply { parentFile.mkdirs(); writeText(texte + "\n") }
    private fun lien(r: File, lien: String, cible: String) {
        val l = File(r, lien).toPath(); val c = File(r, cible).toPath()
        Files.createDirectories(l.parent); Files.createDirectories(c)
        Files.createSymbolicLink(l, l.parent.relativize(c))
    }

    /**
     * An IC-9700 as Linux shows it: a hub holding two CP2102N bridges (their
     * serial numbers say « IC-9700 … A / B ») and its USB codec, plus the
     * motherboard's card. [serie]: the bridges' serial numbers written.
     */
    private fun ic9700(serie: Boolean = true, usbid: String = "08bb:2901"): File {
        val r = dossier.newFolder()
        ecrit(r, "proc/asound/card0/id", "PCH")
        ecrit(r, "proc/asound/card1/id", "CODEC")
        ecrit(r, "proc/asound/card1/usbid", usbid)
        ecrit(r, "proc/asound/card1/stream0", "Burr-Brown from TI USB Audio CODEC at usb-0000:00:14.0-10.1.1.4, full speed : USB Audio")
        lien(r, "sys/class/sound/card0/device", "sys/devices/pci0000:00/0000:00:1f.3")
        lien(r, "sys/class/sound/card1/device", "$HUB/1-10.1.1.4/1-10.1.1.4:1.0")
        ecrit(r, "$HUB/1-10.1.1.4/product", "USB Audio CODEC")
        for ((k, n) in listOf("A" to 2, "B" to 3)) {
            Files.createDirectories(File(r, "$HUB/1-10.1.1.$n/1-10.1.1.$n:1.0/ttyUSB${n - 2}").toPath())
            if (serie) ecrit(r, "$HUB/1-10.1.1.$n/serial", "IC-9700 13012155 $k")
            ecrit(r, "$HUB/1-10.1.1.$n/product", "CP2102N USB to UART Bridge Controller")
            lien(r, "sys/class/tty/ttyUSB${n - 2}/device", "$HUB/1-10.1.1.$n/1-10.1.1.$n:1.0")
        }
        for (d in listOf("1-10.1.1", "1-10.1.1.2", "1-10.1.1.3", "1-10.1.1.4", "1-10.1.1.4:1.0"))
            lien(r, "sys/bus/usb/devices/$d", if (d == "1-10.1.1") HUB else "$HUB/" + (if (d.contains(':')) "1-10.1.1.4/$d" else d))
        ecrit(r, "dev/ttyUSB0", "")
        Files.createDirectories(File(r, "dev/serial/by-id").toPath())
        Files.createSymbolicLink(File(r, "dev/serial/by-id/usb-Silicon_Labs_CP2102N_IC-9700_13012155_A-if00-port0").toPath(), File("../../ttyUSB0").toPath())
        return r
    }

    private val appareils = listOf(
        PCH to "Direct Audio Device: HDA Intel PCH, ALC897 Analog, ALC897 Analog",
        PCH_ALT to "Direct Audio Device: HDA Intel PCH, ALC897 Alt Analog, ALC897 Alt Analog",
        CODEC to "Direct Audio Device: USB Audio CODEC, USB Audio, USB Audio",
    )

    @Test fun lesCartesEtLeursPlacesUsb() {
        val c = CartesSonLinux.cartes(ic9700())
        assertEquals(listOf(0, 1), c.map { it.numero })
        assertEquals("", c[0].usb)
        assertEquals("1-10.1.1.4", c[1].usb)
        assertEquals("08bb:2901", c[1].usbId)
        assertEquals("USB Audio CODEC", c[1].produit)
        assertEquals("1-10.1.1", CartesSonLinux.parent("1-10.1.1.4"))
        assertEquals(1 to 0, CartesSonLinux.numero(CODEC))
        assertNull(CartesSonLinux.numero("alsa_playback.java [default]"))
    }

    @Test fun lIcomSeReconnaitParSesVoisinsSansCat() {
        val l = CartesSonLinux.tous(ic9700(), appareils, CartesSonLinux.Poste())
        assertEquals("IC-9700 (USB Audio CODEC)", l[CODEC]!!.libelle)
        assertEquals("IC-9700", l[CODEC]!!.poste)
        assertEquals("Carte mère (ALC897)", l[PCH]!!.libelle)
        assertEquals("Carte mère (ALC897) · entrée 2", l[PCH_ALT]!!.libelle)
        assertEquals("", l[PCH]!!.poste)
    }

    @Test fun parLePortCatQuandLesNumerosDeSerieManquent() {
        val r = ic9700(serie = false)
        // The CAT port as a /dev/serial/by-id link, followed by name only.
        assertEquals("1-10.1.1.2", CartesSonLinux.placeTty(r, "/dev/serial/by-id/usb-Silicon_Labs_CP2102N_IC-9700_13012155_A-if00-port0"))
        assertEquals("1-10.1.1.2", CartesSonLinux.placeTty(r, "/dev/ttyUSB0"))
        val l = CartesSonLinux.tous(r, appareils, CartesSonLinux.Poste("IC-9700", true, listOf("/dev/ttyUSB0")))
        assertEquals("IC-9700 (USB Audio CODEC)", l[CODEC]!!.libelle)
        assertEquals("IC-9700", l[CODEC]!!.poste)
    }

    @Test fun unCodecDIcomSansPreuveEstUnPosteIcomPeutEtre() {
        val r = ic9700(serie = false)
        val l = CartesSonLinux.tous(r, appareils, CartesSonLinux.Poste("", true, emptyList()))
        assertEquals("Poste Icom ? (USB Audio CODEC)", l[CODEC]!!.libelle)
        assertEquals("", l[CODEC]!!.poste)
        // Not an Icom set: a USB card, no more.
        assertEquals("Carte USB (USB Audio CODEC)", CartesSonLinux.tous(r, appareils, CartesSonLinux.Poste())[CODEC]!!.libelle)
        // A CAT port elsewhere (another hub): no claim either.
        val ailleurs = CartesSonLinux.libelle(CartesSonLinux.cartes(r)[1], 0, CODEC, CartesSonLinux.Poste("IC-9700", false, emptyList()), listOf("1-3.2"), "")
        assertEquals("Carte USB (USB Audio CODEC)", ailleurs!!.libelle)
    }

    @Test fun sansSysfsLaLigneDuFluxDonneLesPorts() {
        val r = dossier.newFolder()
        ecrit(r, "proc/asound/card1/id", "CODEC")
        ecrit(r, "proc/asound/card1/usbid", "08bb:2902")
        ecrit(r, "proc/asound/card1/stream0", "Burr-Brown from TI USB Audio CODEC at usb-0000:00:14.0-10.1.1.4, full speed : USB Audio")
        val c = CartesSonLinux.cartes(r).single()
        assertEquals("?-10.1.1.4", c.usb)
        assertEquals("Poste Icom ? (USB Audio)", CartesSonLinux.tous(r, appareils, CartesSonLinux.Poste("", true))[CODEC]!!.libelle)
    }

    @Test fun laLectureSeuleNEstPasUneSource() {
        assertTrue(CartesSonLinux.lectureSeule("alsa_playback.java [default]"))
        assertFalse(CartesSonLinux.lectureSeule(CODEC))
    }

    @Test fun lePosteEntendLeSatelliteDansSesBandes() {
        assertTrue(CartesSonLinux.posteEntend("IC9700", 145_923_735L))
        assertTrue(CartesSonLinux.posteEntend("IC9700", 435_800_000L))
        assertFalse(CartesSonLinux.posteEntend("IC9700", 10_489_750_000L))
        assertFalse(CartesSonLinux.posteEntend("IC9700", null))
        assertTrue(CartesSonLinux.posteEntend("TS2000", 29_400_000L))
    }
}
