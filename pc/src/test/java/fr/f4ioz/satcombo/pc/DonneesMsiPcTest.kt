/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** An MSI install never keeps data in its own folder: told from the ZIP, a `donnees` folder there copied out, the folder refused. */
class DonneesMsiPcTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-msi-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { d.deleteRecursively() }

    private val jour = LocalDate.of(2026, 10, 28)

    /** An installed SatMe-PC (Windows' app image): SatMe-PC/app/pc.jar; with the MSI's mark when [msi]. */
    private fun programme(msi: Boolean): File {
        val app = File(d, "SatMe-PC/app").apply { mkdirs() }
        File(app, "pc.jar").writeText("jar")
        if (msi) File(app, DonneesMsiPc.MARQUEUR).writeText("msi")
        return File(app, "pc.jar")
    }

    private fun donnees(jar: File, port: Int? = null): File {
        val p = File(jar.parentFile.parentFile, "donnees")
        File(p, "sstv").mkdirs()
        File(p, "reglages.properties").writeText("indicatif=F4IOZ\n")
        File(p, "sstv/image.png").writeBytes(ByteArray(5000) { it.toByte() })
        port?.let { FichierLanceur(File(p, FichierLanceur.NOM)).ecrit(FichierLanceur.Options(port = it)) }
        return p
    }

    @Test
    fun le_msi_se_reconnait_a_sa_marque() {
        val zip = programme(msi = false)
        assertFalse(DonneesMsiPc.installeParMsi(zip))
        donnees(zip)
        assertEquals(File(d, "SatMe-PC/donnees"), dossierPortable(zip))
        File(zip.parentFile, DonneesMsiPc.MARQUEUR).writeText("msi")
        assertTrue(DonneesMsiPc.installeParMsi(zip))
        assertNull("jamais portable dans le dossier du MSI", dossierPortable(zip))
        assertFalse(DonneesMsiPc.installeParMsi(null))
    }

    @Test
    fun les_donnees_copiees_dans_appdata_quand_il_est_libre() {
        val jar = programme(msi = true)
        val src = donnees(jar, port = 8095)
        val appdata = File(d, "AppData/Roaming").apply { mkdirs() }
        val r = DonneesMsiPc.auDemarrage(jar, appdata, jour)
        assertNotNull(r); r!!
        assertTrue(r.copie)
        assertEquals(File(appdata, "SatMe").absoluteFile, r.dossier)
        assertTrue(r.message, r.message.startsWith("Vos données étaient dans le dossier du programme, qu'une mise à jour efface : elles ont été copiées dans "))
        assertTrue(DonneesMsiPc.memeContenu(src, r.dossier))
        // The portable start options came along: now the user's launcher file.
        assertEquals(8095, FichierLanceur(FichierLanceur.emplacement(env = { if (it == "APPDATA") appdata.path else null }, windows = true)).lit().port)
        // The original kept, marked; not copied twice.
        assertTrue(File(src, "sstv/image.png").isFile)
        assertTrue(File(src, DonneesMsiPc.COPIE_FAITE).isFile)
        assertNull(DonneesMsiPc.auDemarrage(jar, appdata, jour))
    }

    @Test
    fun appdata_deja_rempli_une_copie_datee_et_le_lanceur_y_pointe() {
        val jar = programme(msi = true)
        val src = donnees(jar, port = 8095)
        val appdata = File(d, "AppData/Roaming")
        val autre = File(appdata, "SatMe").apply { mkdirs() }
        File(autre, "reglages.properties").writeText("indicatif=AUTRE\n")
        File(appdata, "SatMe-portable-2026-10-28").mkdirs()
        val r = DonneesMsiPc.auDemarrage(jar, appdata, jour)!!
        assertTrue(r.copie)
        assertEquals(File(appdata, "SatMe-portable-2026-10-28-2").absoluteFile, r.dossier)
        assertTrue(DonneesMsiPc.memeContenu(src, r.dossier))
        assertEquals("indicatif=AUTRE\n", File(autre, "reglages.properties").readText())
        val o = FichierLanceur(File(autre, FichierLanceur.NOM)).lit()
        assertEquals(r.dossier.path, o.dossier)
        assertEquals(8095, o.port)
        // What Main makes of it: the copy is the data folder.
        val l = LanceurPc.resout(ArgumentsPc(), o, File(autre, FichierLanceur.NOM), File(appdata, "SatMe"))
        assertEquals(r.dossier, l.dossier)
    }

    @Test
    fun rien_a_faire_hors_msi_sans_donnees_ou_dossier_vide() {
        val appdata = File(d, "AppData").apply { mkdirs() }
        val zip = programme(msi = false)
        donnees(zip)
        assertNull("le ZIP reste portable", DonneesMsiPc.auDemarrage(zip, appdata, jour))
        File(zip.parentFile, DonneesMsiPc.MARQUEUR).writeText("msi")
        File(d, "SatMe-PC/donnees").deleteRecursively()
        assertNull(DonneesMsiPc.auDemarrage(zip, appdata, jour))
        File(d, "SatMe-PC/donnees/vide").mkdirs()
        assertNull("aucun fichier : rien à sauver", DonneesMsiPc.auDemarrage(zip, appdata, jour))
        assertTrue(appdata.list()!!.isEmpty())
    }

    @Test
    fun copie_impossible_l_ancien_dossier_garde_et_dit() {
        val jar = programme(msi = true)
        val src = donnees(jar)
        val r = DonneesMsiPc.auDemarrage(jar, null, jour)!!
        assertFalse(r.copie)
        assertEquals(src, r.dossier)
        assertTrue(r.message.contains("qu'une mise à jour efface"))
        assertFalse(File(src, DonneesMsiPc.COPIE_FAITE).exists())
        // APPDATA a file, not a folder: the copy fails, said, tried again next time.
        val bloque = File(d, "AppDataFichier").apply { writeText("pas un dossier") }
        val r2 = DonneesMsiPc.auDemarrage(jar, bloque, jour)!!
        assertFalse(r2.copie)
        assertFalse(File(src, DonneesMsiPc.COPIE_FAITE).exists())
    }

    @Test
    fun la_copie_verifiee() {
        val a = File(d, "a").apply { mkdirs() }; File(a, "x").writeText("12345")
        val b = File(d, "b").apply { mkdirs() }; File(b, "x").writeText("1234")
        assertFalse(DonneesMsiPc.memeContenu(a, b))
        File(b, "x").writeText("54321")
        assertTrue(DonneesMsiPc.memeContenu(a, b))
        File(a, "y").writeText("")
        assertFalse(DonneesMsiPc.memeContenu(a, b))
    }

    @Test
    fun le_dossier_du_programme_refuse_comme_dossier_des_donnees() {
        val jar = programme(msi = true)
        val racine = jar.parentFile.parentFile
        val actuel = File(d, "AppData/SatMe").apply { mkdirs() }
        assertNotNull(DonneesMsiPc.refus(File(racine, "donnees"), jar))
        assertNotNull(DonneesMsiPc.refus(racine, jar))
        assertNull(DonneesMsiPc.refus(File(d, "ailleurs"), jar))
        val raison = LanceurPc.refusDossier(File(racine, "mes-donnees").path, actuel, copier = true) { DonneesMsiPc.refus(it, jar) }
        assertNotNull(raison); assertTrue(raison!!.contains("dossier du programme") || raison.contains("celui du programme"))
        assertNull(LanceurPc.refusDossier(File(d, "ailleurs").path, actuel, copier = true) { DonneesMsiPc.refus(it, jar) })
        // The ZIP: its own portable folder allowed, anything else inside refused.
        File(jar.parentFile, DonneesMsiPc.MARQUEUR).delete()
        assertNull(DonneesMsiPc.refus(File(racine, "donnees"), jar))
        assertNotNull(DonneesMsiPc.refus(File(racine, "app/donnees"), jar))
        // Classes run from a build: no program folder.
        assertNull(DonneesMsiPc.refus(File(d, "x"), File(d, "classes")))
    }

    // ---- The installer: jpackage's WiX fragment without its RemoveFolderEx (pc/windows/SansNettoyage.java)

    private val util = "http://schemas.microsoft.com/wix/UtilExtension"
    /** Shaped like jpackage 21's bundle.wxf (WixAppImageFragmentBuilder): files, the cleaner, the « Files » group. */
    private fun fragment(nettoyage: Boolean) = """<?xml version="1.0" ?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi" xmlns:util="$util">
  <Fragment>
    <DirectoryRef Id="dirabc">
      <Component Win64="yes" Id="cfileabc" Guid="{11111111-2222-3333-4444-555555555555}">
        <RegistryKey Root="HKCU" Key="Software\F4IOZ\SatMe-PC\26.11.100"><RegistryValue Type="string" KeyPath="yes" Name="ProductCode" Value="[ProductCode]"></RegistryValue></RegistryKey>
        <RemoveFolder Id="rmabc_1" On="uninstall"></RemoveFolder>
        <File Id="fileabc" Source="C:\t\images\win-msi.image\SatMe-PC\app\pc.jar"></File>
      </Component>
    </DirectoryRef>
""" + (if (!nettoyage) "" else """    <Property Id="RM_RFDEF">
      <RegistrySearch Id="regsearchdef" Root="HKCU" Key="Software\F4IOZ\SatMe-PC\26.11.100" Type="raw" Name="RM_RFDEF"></RegistrySearch>
    </Property>
    <DirectoryRef Id="INSTALLDIR">
      <Component Win64="yes" Id="crm_rfdef" Guid="*">
        <RegistryKey Root="HKCU" Key="Software\F4IOZ\SatMe-PC\26.11.100"><RegistryValue Type="string" KeyPath="yes" Name="RM_RFDEF" Value="[INSTALLDIR]"></RegistryValue></RegistryKey>
        <util:RemoveFolderEx On="uninstall" Property="RM_RFDEF"></util:RemoveFolderEx>
      </Component>
    </DirectoryRef>
""") + """    <ComponentGroup Id="Files">
      <ComponentRef Id="cfileabc"></ComponentRef>
""" + (if (nettoyage) "      <ComponentRef Id=\"crm_rfdef\"></ComponentRef>\n" else "") + """    </ComponentGroup>
  </Fragment>
</Wix>
"""

    private fun sansNettoyage(entree: File, sortie: File): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val script = listOf(File("windows/SansNettoyage.java"), File("pc/windows/SansNettoyage.java")).first { it.isFile }
        val p = ProcessBuilder(java, script.path, entree.path, sortie.path).redirectErrorStream(true).start()
        val texte = p.inputStream.bufferedReader().readText()
        return p.waitFor() to texte
    }

    @Test
    fun l_installateur_sans_nettoyage_du_dossier() {
        val e = File(d, "bundle.wxf").apply { writeText(fragment(nettoyage = true)) }
        val s = File(d, "sortie.wxf")
        val (code, texte) = sansNettoyage(e, s)
        assertEquals(texte, 0, code)
        val x = s.readText()
        assertFalse(x.contains("RemoveFolderEx")); assertFalse(x.contains("RM_RFDEF")); assertFalse(x.contains("crm_rfdef"))
        // Everything else kept.
        listOf("cfileabc", "rmabc_1", "pc.jar", "ComponentGroup Id=\"Files\"", "[ProductCode]").forEach { assertTrue(it, x.contains(it)) }
        assertNotEquals(-1, x.indexOf("<DirectoryRef Id=\"dirabc\""))
        assertFalse(x.contains("DirectoryRef Id=\"INSTALLDIR\""))
    }

    @Test
    fun l_installateur_echoue_si_jpackage_a_change() {
        val e = File(d, "bundle.wxf").apply { writeText(fragment(nettoyage = false)) }
        val (code, texte) = sansNettoyage(e, File(d, "sortie.wxf"))
        assertNotEquals(0, code)
        assertTrue(texte, texte.contains("RemoveFolderEx"))
    }
}
