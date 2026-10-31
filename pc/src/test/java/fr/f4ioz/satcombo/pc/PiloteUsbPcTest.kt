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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The rig's USB driver on Windows. No Windows here: the outputs below are
 * SYNTHETIC — written by hand after the shape of Get-PnpDevice's JSON and
 * pnputil's text (Windows 10/11, French and English), not captured on a PC.
 */
class PiloteUsbPcTest {
    @get:Rule val tmp = TemporaryFolder()

    // An IC-9700 without the Silicon Labs driver, French Windows: both bridges (A and B) code 28, the codec fine.
    private val JSON_FR = """
        [{"InstanceId":"USB\\VID_10C4&PID_EA60\\IC-9700_13012155_A","FriendlyName":"CP2102N USB to UART Bridge Controller","Status":"Error","Class":null,"Code":28},
         {"InstanceId":"USB\\VID_10C4&PID_EA60\\IC-9700_13012155_B","FriendlyName":"CP2102N USB to UART Bridge Controller","Status":"Error","Class":null,"Code":28},
         {"InstanceId":"USB\\VID_08BB&PID_2901\\5&1A2B3C4D&0&4","FriendlyName":"Périphérique audio USB","Status":"OK","Class":"MEDIA","Code":0},
         {"InstanceId":"USB\\VID_08BB&PID_2901&MI_00\\6&2B3C4D5E&0&0000","FriendlyName":"Haut-parleurs (USB Audio CODEC)","Status":"OK","Class":"MEDIA","Code":0}]
    """.trimIndent()

    // One device only: ConvertTo-Json without -InputObject gives an object, not an array; the problem as an enum's name.
    private val JSON_UN = """{"InstanceId":"USB\\VID_10C4&PID_EA60\\0001","FriendlyName":"Unknown device","Status":"Error","Problem":"CM_PROB_FAILED_INSTALL"}"""

    // English Windows, the driver there: the COM ports work, nothing to say.
    private val JSON_OK = """[{"InstanceId":"USB\\VID_10C4&PID_EA60\\IC-9700_13012155_A","FriendlyName":"Silicon Labs CP210x USB to UART Bridge (COM4)","Status":"OK","Class":"Ports","Code":0}]"""

    // pnputil /enum-devices /connected /problem, English.
    private val PNPUTIL_EN = """
        Microsoft PnP Utility

        Instance ID:                USB\VID_10C4&PID_EA60\IC-9700_13012155_A
        Device Description:         CP2102N USB to UART Bridge Controller
        Class Name:                 Other devices
        Class GUID:                 {00000000-0000-0000-0000-000000000000}
        Manufacturer Name:
        Status:                     Problem
        Problem Code:               28 (0x1C) [CM_PROB_FAILED_INSTALL]
        Problem Status:             0xC0000490 [STATUS_PNP_FUNCTION_DRIVER_REQUIRED]

        Instance ID:                HID\VID_046D&PID_C52B&MI_00\7&11111111&0&0000
        Device Description:         HID Keyboard Device
        Status:                     Problem
        Problem Code:               10 (0xA) [CM_PROB_FAILED_START]
    """.trimIndent()

    // The same in French (labels guessed; only the values are read).
    private val PNPUTIL_FR = """
        Utilitaire PnP Microsoft

        ID d’instance :                     USB\VID_10C4&PID_EA60\IC-9700_13012155_B
        Description du périphérique :       CP2102N USB to UART Bridge Controller
        Nom de la classe :                  Autres périphériques
        GUID de la classe :                 {00000000-0000-0000-0000-000000000000}
        État :                              Problème
        Code du problème :                  28 (0x1C) [CM_PROB_FAILED_INSTALL]

        ID d’instance :                     USB\VID_08BB&PID_2901\5&1A2B3C4D&0&4
        Description du périphérique :       USB Audio CODEC
        État :                              Problème
        Code du problème :                  10 (0xA) [CM_PROB_FAILED_START]
    """.trimIndent()

    @Test fun json_francais_les_deux_ponts_sans_pilote() {
        val l = DetectionPiloteUsb.depuisJson(JSON_FR)
        assertEquals(2, l.size)
        assertTrue(l.all { it.type == DetectionPiloteUsb.Type.PONT_SERIE && it.code == 28 && it.sansPilote })
        assertEquals("USB\\VID_10C4&PID_EA60\\IC-9700_13012155_B", l[1].instance)
    }

    @Test fun json_un_seul_objet_et_probleme_en_texte() {
        val l = DetectionPiloteUsb.depuisJson("WARNING: something\r\n" + JSON_UN)
        assertEquals(1, l.size)
        assertEquals(28, l[0].code)
        assertTrue(l[0].sansPilote)
    }

    @Test fun json_pilote_present_ou_vide_rien_a_dire() {
        assertTrue(DetectionPiloteUsb.depuisJson(JSON_OK).isEmpty())
        assertTrue(DetectionPiloteUsb.depuisJson("").isEmpty())
        assertTrue(DetectionPiloteUsb.depuisJson("[]").isEmpty())
        assertTrue(DetectionPiloteUsb.depuisJson("pas du json").isEmpty())
    }

    @Test fun json_codec_en_erreur_signale() {
        val l = DetectionPiloteUsb.depuisJson("""[{"InstanceId":"USB\\VID_08BB&PID_2901\\5&1&0&4","FriendlyName":"USB Audio CODEC","Status":"Error","Code":10}]""")
        assertEquals(DetectionPiloteUsb.Type.CODEC_SON, l.single().type)
        assertFalse(l.single().sansPilote)
    }

    @Test fun pnputil_anglais_et_francais() {
        val en = DetectionPiloteUsb.depuisPnputil(PNPUTIL_EN)
        assertEquals(1, en.size)
        assertEquals(28, en[0].code)
        assertEquals("CP2102N USB to UART Bridge Controller", en[0].nom)
        val fr = DetectionPiloteUsb.depuisPnputil(PNPUTIL_FR.replace("\n", "\r\n"))
        assertEquals(listOf(DetectionPiloteUsb.Type.PONT_SERIE, DetectionPiloteUsb.Type.CODEC_SON), fr.map { it.type })
        assertEquals(listOf(28, 10), fr.map { it.code })
    }

    @Test fun autres_appareils_ignores() {
        assertNull(DetectionPiloteUsb.type("USB\\VID_0403&PID_6001\\A1"))
        assertNull(DetectionPiloteUsb.type("HID\\VID_10C4&PID_EA60\\x"))
        assertEquals(DetectionPiloteUsb.Type.PONT_SERIE, DetectionPiloteUsb.type("usb\\vid_10c4&pid_ea70&mi_01\\x"))
        assertEquals(DetectionPiloteUsb.Type.PONT_SERIE, DetectionPiloteUsb.type("USB\\VID_10C4&PID_EA71\\x"))
    }

    @Test fun bilans_de_pnputil() {
        assertTrue(DetectionPiloteUsb.bilanInstallation(0).ok)
        assertTrue(DetectionPiloteUsb.bilanInstallation(3010).redemarrer)
        assertFalse(DetectionPiloteUsb.bilanInstallation(1223).ok)
        assertFalse(DetectionPiloteUsb.bilanInstallation(5).ok)
    }

    private fun zip(f: File, vararg entrees: Pair<String, String>): File {
        ZipOutputStream(f.outputStream()).use { z -> entrees.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return f
    }

    @Test fun archive_du_pilote_depliee() {
        val z = zip(tmp.newFile("cp.zip"), "CP210x_Universal_Windows_Driver/silabser.inf" to "[Version]",
            "CP210x_Universal_Windows_Driver/silabser.cat" to "cat", "CP210x_Universal_Windows_Driver/x64/silabser.sys" to "sys")
        val inf = DetectionPiloteUsb.deplie(z, File(tmp.root, "d"))
        assertEquals("silabser.inf", inf.name)
        assertTrue(File(inf.parentFile, "x64/silabser.sys").isFile)
    }

    private fun refuse(z: File, mot: String) {
        try { DetectionPiloteUsb.deplie(z, File(tmp.root, "r")); fail("accepté") }
        catch (e: IllegalStateException) { assertTrue(e.message, e.message!!.contains(mot)) }
    }

    @Test fun archives_refusees() {
        refuse(zip(tmp.newFile("a.zip"), "silabser.inf" to "x"), ".cat")
        refuse(zip(tmp.newFile("b.zip"), "lisezmoi.txt" to "x"), ".inf")
        refuse(zip(tmp.newFile("c.zip"), "../evade.inf" to "x", "x.cat" to "x"), "hors du dossier")
        refuse(tmp.newFile("d.zip").apply { writeText("<html>403</html>") }, ".zip")
        assertFalse(File(tmp.root, "evade.inf").exists())
    }

    // ------------------------------------------------------------------ with a fake Windows

    private class FauxWindows(val reponses: (List<String>) -> Pair<Int, String>?) {
        val appels = mutableListOf<Pair<List<String>, Map<String, String>>>()
        fun execute(c: List<String>, env: Map<String, String>, s: Long): Pair<Int, String>? { synchronized(appels) { appels += c to env }; return reponses(c) }
    }

    @Test fun hors_windows_rien_ne_tourne() {
        val f = FauxWindows { fail("rien ne doit tourner"); null }
        val p = PiloteUsbPc(tmp.root, windows = false, executeur = f::execute, telecharge = { _, _ -> fail("rien à télécharger") })
        assertTrue(p.analyse().isEmpty())
        assertFalse(p.json().getBoolean("windows"))
        assertEquals("seulement sous Windows", p.installe())
        assertTrue(f.appels.isEmpty())
    }

    @Test fun powershell_puis_pnputil_en_secours() {
        val f = FauxWindows { c -> if (c[0] == "powershell.exe") 1 to "Get-PnpDevice : introuvable" else 0 to PNPUTIL_EN }
        val p = PiloteUsbPc(tmp.root, windows = true, executeur = f::execute)
        assertEquals(1, p.analyse().size)
        assertEquals(listOf("powershell.exe", "pnputil.exe"), f.appels.map { it.first[0] })
        val f2 = FauxWindows { 0 to JSON_FR }
        assertEquals(2, PiloteUsbPc(tmp.root, windows = true, executeur = f2::execute).analyse().size)
        assertEquals(1, f2.appels.size)
    }

    @Test fun la_page_voit_aussi_les_appareils_qui_marchent() {
        val f = FauxWindows { 0 to JSON_FR }
        val p = PiloteUsbPc(tmp.root, windows = true, executeur = f::execute)
        p.analyse()
        val j = p.json()
        assertEquals("powershell", j.getString("methode"))
        assertEquals(2, j.getJSONArray("appareils").length())
        assertEquals(4, j.getJSONArray("vus").length())
        assertEquals(28, j.getJSONArray("vus").getJSONObject(0).getInt("code"))
        assertEquals(0, j.getJSONArray("vus").getJSONObject(2).getInt("code"))
        val rien = PiloteUsbPc(tmp.root, windows = true, executeur = { _, _, _ -> null })
        rien.analyse()
        assertEquals("PowerShell et pnputil ne répondent pas", rien.json().getString("methode"))
    }

    private fun attends(p: PiloteUsbPc) { val fin = System.currentTimeMillis() + 10_000; while (p.installation.enCours && System.currentTimeMillis() < fin) Thread.sleep(20) }

    @Test fun installation_depuis_un_zip_choisi() {
        val f = FauxWindows { c -> if (c.last() == PiloteUsbPc.PS_INSTALLE) 3010 to "" else 0 to "[]" }
        val p = PiloteUsbPc(tmp.root, windows = true, executeur = f::execute, telecharge = { _, _ -> fail("pas de téléchargement") })
        val octets = zip(tmp.newFile("cp.zip"), "silabser.inf" to "[Version]", "silabser.cat" to "c").readBytes()
        assertNull(p.installeZip(octets))
        attends(p)
        assertTrue(p.installation.erreur, p.installation.fini)
        assertTrue(p.installation.redemarrer)
        val (c, env) = f.appels.first { it.first.last() == PiloteUsbPc.PS_INSTALLE }
        assertEquals("powershell.exe", c[0])
        assertTrue(env["SATME_INF"]!!.endsWith("silabser.inf"))
        assertFalse("aucun guillemet double dans la commande", PiloteUsbPc.PS_INSTALLE.contains('"'))
    }

    @Test fun telechargement_puis_refus_uac() {
        var adresse = ""
        val f = FauxWindows { c -> if (c.last() == PiloteUsbPc.PS_INSTALLE) 1223 to "The operation was canceled by the user." else 0 to "[]" }
        val p = PiloteUsbPc(tmp.root, windows = true, executeur = f::execute, telecharge = { u, dest ->
            adresse = u; zip(dest, "d/silabser.inf" to "[Version]", "d/silabser.cat" to "c") })
        assertNull(p.installe())
        attends(p)
        assertEquals(PiloteUsbPc.URL_ZIP, adresse)
        assertFalse(p.installation.fini)
        assertTrue(p.installation.erreur, p.installation.erreur.contains("refusée"))
    }

    @Test fun telechargement_refuse_par_le_site() {
        val f = FauxWindows { 0 to "[]" }
        val p = PiloteUsbPc(tmp.root, windows = true, executeur = f::execute, telecharge = { _, _ -> throw IllegalStateException("silabs.com refuse le téléchargement (HTTP 403)") })
        p.installe(); attends(p)
        assertTrue(p.installation.erreur.contains("403"))
        assertTrue(f.appels.none { it.first.last() == PiloteUsbPc.PS_INSTALLE })
    }
}
