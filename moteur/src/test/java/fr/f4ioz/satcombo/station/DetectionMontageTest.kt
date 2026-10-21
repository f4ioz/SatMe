/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.DetectionMontage.Confiance
import fr.f4ioz.satcombo.station.Montage.Bout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DetectionMontageTest {

    /** A pretend `/sys/bus/usb/devices`: one folder per device with its id files. */
    private fun sysfs(vararg d: List<String>): File {
        val r = Files.createTempDirectory("usb").toFile()
        d.forEachIndexed { i, x ->
            val f = File(r, "1-$i"); f.mkdirs()
            File(f, "idVendor").writeText(x[0] + "\n"); File(f, "idProduct").writeText(x[1] + "\n")
            if (x.size > 2) File(f, "product").writeText(x[2] + "\n")
            if (x.size > 3) File(f, "bDeviceClass").writeText(x[3] + "\n")
        }
        File(r, "usb1-port1").mkdirs()   // a port: no id files
        return r
    }

    private val USB = listOf(
        listOf("1d6b", "0002", "xHCI Host Controller", "09"),
        listOf("0456", "b673", "PlutoSDR (ADALM-PLUTO)"), listOf("2cf0", "5250", "bladeRF 2.0"),
        listOf("0403", "6010", "Dual RS232-HS"), listOf("10c4", "ea60", "IC-9700 12001234 A"), listOf("08bb", "2901", "USB Audio CODEC"),
        listOf("08bb", "2902", "USB Audio CODEC"), listOf("2341", "484d", "Volume knob"), listOf("1dd2", "2211", "Mini GPS Clock"))

    private val CARTES = """
 0 [PCH            ]: HDA-Intel - HDA Intel PCH
                      HDA Intel PCH at 0xf7f10000 irq 32
 1 [CODEC          ]: USB-Audio - USB Audio CODEC
                      Burr-Brown from TI USB Audio CODEC at usb-0000:00:14.0-2, full speed
 2 [CODEC_1        ]: USB-Audio - USB PCM2902 Audio CODEC
                      Burr-Brown from TI USB Audio CODEC at usb-0000:00:14.0-3, full speed
"""

    @Test fun lesFichiersDuSystemeSeLisentSansRienOuvrir() {
        val u = DetectionMontage.litUsb(sysfs(*USB.toTypedArray()))
        // The hub (class 09) and the port folder left out.
        assertEquals(USB.size - 1, u.size)
        assertEquals("0456:b673", u.first().id)
        assertEquals("PlutoSDR (ADALM-PLUTO)", u.first().nom)
        assertTrue(DetectionMontage.litUsb(File("/nulle/part")).isEmpty())
        val c = DetectionMontage.litCartes(CARTES) { n -> mapOf(1 to "08bb:2901", 2 to "08BB:2902")[n] }
        assertEquals(3, c.size)
        assertEquals(DetectionMontage.CarteSon(1, "CODEC", "USB Audio CODEC", "08bb:2901"), c[1])
        assertEquals("08bb:2902", c[2].usb)
        assertEquals("", c[0].usb)
        // From a folder as /proc/asound.
        val a = Files.createTempDirectory("asound").toFile()
        File(a, "cards").writeText(CARTES); File(a, "card2").mkdirs(); File(a, "card2/usbid").writeText("08bb:2902\n")
        assertEquals("08bb:2902", DetectionMontage.litCartes(a)[2].usb)
    }

    /** The owner's station as SatMe's routes say it: IC-9700 in CAT, Pluto receiving QO-100 through LNB + down-converter, the MiniTiouner. */
    private fun lectures() = ActiviteMontage.Lectures(
        etat = JSONObject("""{"cat":{"connecte":true,"simule":false,"modele":"IC9700"},"rotor":{"connecte":false},"convertisseurs":{"rxOl":0,"txOl":0}}"""),
        sdr = JSONObject("""{"recepteur":"pluto","actif":true,"plutoHote":"192.168.2.1"}"""),
        datv = JSONObject("""{"ok":true,"recepteur":"minitiouner","actif":false,"noeud":""}"""),
        emissionDatv = JSONObject("""{"ok":true,"emetteur":"bladerf","bladerf":{"lu":true}}"""),
        qo100 = JSONObject("""{"olRx":10345000000,"olTx":1968000000,"chaine":"Fixe","aLaCle":true}"""),
        boites = JSONObject("""{"wavelog":{"url":true,"cle":true},"qrz":{"configure":false},"lien":{"on":true,"clients":1},"psk":{"on":false},"ecoute":{"on":false}}"""))

    @Test fun laStationDuProprietaireEstProposee() {
        val usb = DetectionMontage.litUsb(sysfs(*USB.toTypedArray()))
        val cartes = DetectionMontage.litCartes(CARTES) { n -> mapOf(1 to "08bb:2901", 2 to "08bb:2902")[n] }
        val p = DetectionMontage.propose(lectures(), usb, cartes)
        val c = p.chaine
        fun un(m: String) = c.elements.single { it.modele == m }
        fun conf(m: String) = p.piece(un(m).id)!!.confiance
        assertEquals(c, Montage.nettoie(c))
        // Seen: the rig (CAT, its USB name and codec), the boards on the USB, the knob, the GPSDO, the PCM2902 card, the boîtes set up.
        for (m in listOf("pc", "ic9700", "pluto", "bladerf", "minitiouner", "molette-usb", "leo-bodnar", "carte-son", "boite-wavelog", "boite-lien"))
            assertEquals(m, Confiance.DETECTE, conf(m))
        assertTrue(p.piece(un("ic9700").id)!!.raison.contains("08bb:2901"))
        // Guessed from the QO-100 chain's oscillators: LNB 9750 + converter 595 → 144 MHz, two outputs for the MiniTiouner; the up-converter from 432.
        for (m in listOf("parabole-qo100", "lnb-twin", "dxp-down-mk4", "dxp-up-mk4", "pa-2400")) assertEquals(m, Confiance.SUPPOSE, conf(m))
        assertEquals("144", un("dxp-down-mk4").options["sortie"] ?: "144")
        assertTrue(p.piece(un("dxp-down-mk4").id)!!.raison.contains("595"))
        assertEquals("432", un("dxp-up-mk4").options["fi"] ?: "432")
        fun relie(a: String, pa: String, b: String, pb: String) = c.liens.any { l ->
            (l.de == Bout(un(a).id, pa) && l.vers == Bout(un(b).id, pb)) || (l.vers == Bout(un(a).id, pa) && l.de == Bout(un(b).id, pb)) }
        assertTrue(relie("lnb-twin", "sortie1", "minitiouner", "lnb_a"))
        assertTrue(relie("lnb-twin", "sortie2", "dxp-down-mk4", "entree"))
        assertTrue(relie("dxp-down-mk4", "sortie", "pluto", "rx"))
        assertTrue(relie("ic9700", "ant432", "dxp-up-mk4", "entree"))
        assertTrue(relie("boite-wavelog", "net", "pc", "logiciel"))
        // Not proposed: QRZ not set up, PSK Reporter off.
        assertNull(c.elements.firstOrNull { it.modele == "boite-qrz" || it.modele == "boite-psk" })
        // The JSON for the page.
        val j = DetectionMontage.json(p)
        assertEquals(c.elements.size, j.getJSONArray("pieces").length())
    }

    @Test fun sansRienQueLePc() {
        val p = DetectionMontage.propose(ActiviteMontage.Lectures())
        assertEquals(listOf("pc"), p.chaine.elements.map { it.modele })
        // An FTDI chip alone: a MiniTiouner only supposed.
        val u = listOf(DetectionMontage.Usb("0403", "6010", "Dual RS232-HS"))
        val q = DetectionMontage.propose(ActiviteMontage.Lectures(), u)
        val m = q.chaine.elements.single { it.modele == "minitiouner" }
        assertEquals(Confiance.SUPPOSE, q.piece(m.id)!!.confiance)
        // An LNB alone at 9750 MHz with an SDR: the LNB, its supply, the receiver.
        val r = DetectionMontage.propose(ActiviteMontage.Lectures(qo100 = JSONObject("""{"olRx":9750000000}"""),
            sdr = JSONObject("""{"recepteur":"pluto","actif":false}""")))
        assertNotNull(r.chaine.elements.firstOrNull { it.modele == "lnb-pll" })
        assertNotNull(r.chaine.elements.firstOrNull { it.modele == "bias-t" })
        assertNull(r.chaine.elements.firstOrNull { it.modele == "dxp-down-mk4" })
    }

    @Test fun lesPiecesGardeesSAjoutentAuMontage() {
        val p = DetectionMontage.propose(lectures(), DetectionMontage.litUsb(sysfs(*USB.toTypedArray())))
        val actuelle = Montage.Chaine(listOf(Montage.Element("e1", "pc", "Mon PC"), Montage.Element("e2", "ic9700")), emptyList())
        val garde = p.chaine.elements.filter { it.modele in setOf("pc", "ic9700", "dxp-up-mk4", "pa-2400", "parabole-qo100") }.map { it.id }.toSet()
        val f = DetectionMontage.fusionne(actuelle, p.chaine, garde)
        // The PC and the IC-9700 already there are reused (the owner's name kept); three pieces added.
        assertEquals(3, f.ajoutes)
        assertEquals("Mon PC", f.chaine.element("e1")!!.nom)
        assertEquals(5, f.chaine.elements.size)
        assertEquals(1, f.chaine.elements.count { it.modele == "ic9700" })
        // The cables between pieces kept: IC-9700 → up-converter → PA → dish, IC-9700 → PC.
        assertEquals(f.relies, f.chaine.liens.size)
        assertTrue(f.chaine.liens.any { it.de.element == "e2" || it.vers.element == "e2" })
        assertTrue(f.relies >= 4)
        // Merged twice: nothing new.
        val g = DetectionMontage.fusionne(f.chaine, p.chaine, garde)
        assertEquals(0, g.ajoutes); assertEquals(0, g.relies)
        assertFalse(DetectionMontage.fusionne(actuelle, p.chaine, emptySet()).chaine != actuelle)
    }

    /** The owner's node: MiniTiouner on LNB output 1, the bladeRF on output 2 through a T, a Pluto to transmit; all on the node. */
    @Test fun leSatMeNodeEtSesCartes() {
        val datv = JSONObject("""{"recepteur":"minitiouner","noeud":"192.168.1.30","nomNoeud":"pi-antenne",
            "noeudMateriel":{"datv":"minitiouner","sdr":"bladerf","tx":"pluto"}}""")
        for (c in listOf(DetectionMontage.propose(ActiviteMontage.Lectures(datv = datv, qo100 = JSONObject("""{"olRx":9750000000}"""))).chaine,
                ActiviteMontage.suggere(ActiviteMontage.Lectures(datv = datv, etat = JSONObject("""{"convertisseurs":{"rxOl":9750000000}}"""))).chaine)) {
            fun un(m: String) = c.elements.single { it.modele == m }
            fun relie(a: String, pa: String, b: String, pb: String) = c.liens.any { l ->
                (l.de == Bout(un(a).id, pa) && l.vers == Bout(un(b).id, pb)) || (l.vers == Bout(un(a).id, pa) && l.de == Bout(un(b).id, pb)) }
            assertTrue(c.toString(), relie("minitiouner", "usb", "satme-node", "usb"))
            assertTrue(relie("bladerf", "usb", "satme-node", "usb"))
            assertTrue(relie("pluto", "usb", "satme-node", "usb"))
            assertTrue(relie("lnb-twin", "sortie1", "minitiouner", "lnb_a"))
            assertTrue(relie("lnb-twin", "sortie2", "bias-t", "lnb"))
            assertTrue(relie("bias-t", "rx", "bladerf", "rx1"))
            assertTrue(relie("satme-node", "lan", "pc", "lan"))
            // Nothing of the node's plugged into the PC.
            assertFalse(relie("bladerf", "usb", "pc", "usb"))
        }
    }
}
