/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The satellites' cards, without the network: Wikipedia's summaries and
 * Commons' licences read from samples, only free photos taken (with their
 * author), a picture held by Wikipedia itself refused, nothing kept when
 * nothing was reached, the card showing its facts meanwhile.
 */
class FichesSatPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-fiches-" + System.nanoTime()).apply { mkdirs() }
    @After fun fin() { dossier.deleteRecursively() }

    private fun echantillon(n: String) = FichesSatPcTest::class.java.getResourceAsStream("/fiches-sat/$n.json")!!.readBytes().toString(Charsets.UTF_8)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(400)

    @Test fun resumeEtFichierCommons() {
        val r = FichesSatPc.lisResume(echantillon("resume-iss-fr"), "fr")!!
        assertEquals("Station spatiale internationale", r.titre)
        assertTrue(r.extrait.isNotBlank())
        assertEquals("https://fr.wikipedia.org/wiki/Station_spatiale_internationale", r.lien)
        assertEquals("The_station_pictured_from_the_SpaceX_Crew_Dragon_5.jpg", r.fichier)
    }

    @Test fun imageDeWikipediaRefusee() {
        // AO-7's picture is held by the English Wikipedia (not free): no file to ask Commons about.
        val r = FichesSatPc.lisResume(echantillon("resume-ao7-en"), "en")!!
        assertEquals("AMSAT-OSCAR 7", r.titre)
        assertNull(r.fichier)
    }

    @Test fun homonymieIgnoree() {
        assertNull(FichesSatPc.lisResume(echantillon("resume-homonymie-en"), "en"))
    }

    @Test fun nomsDeFichiers() {
        assertEquals("Es'hail-2_Mission_(32040174268).jpg", FichesSatPc.fichierCommons(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/03/Es%27hail-2_Mission_%2832040174268%29.jpg/330px-Es%27hail-2_Mission_%2832040174268%29.jpg?utm_source=x"))
        assertEquals("NOAA-M.jpg", FichesSatPc.fichierCommons("https://upload.wikimedia.org/wikipedia/commons/a/a1/NOAA-M.jpg"))
        assertEquals("C++.png", FichesSatPc.fichierCommons("https://upload.wikimedia.org/wikipedia/commons/a/a1/C++.png"))
        assertNull(FichesSatPc.fichierCommons("https://upload.wikimedia.org/wikipedia/fr/a/a1/Affiche.jpg"))
        assertNull(FichesSatPc.fichierCommons("https://example.org/x.jpg"))
    }

    @Test fun licences() {
        val pd = FichesSatPc.lisLicence(echantillon("commons-iss"))!!
        assertTrue(pd.libre); assertEquals("Public domain", pd.nom); assertEquals("NASA", pd.auteur)
        assertTrue(pd.vignette.startsWith("https://"))
        val sa = FichesSatPc.lisLicence(echantillon("commons-css"))!!
        assertTrue(sa.libre); assertEquals("CC BY-SA 4.0", sa.nom); assertEquals("Shujianyang", sa.auteur)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0", sa.lienLicence)
        assertFalse(FichesSatPc.lisLicence(echantillon("commons-non-libre"))!!.libre)
        listOf("pd", "cc0", "cc-by-3.0", "cc-by-sa-4.0", "cc-by-sa-3.0-de", "cc-by-2.5").forEach { assertTrue(it, FichesSatPc.licenceLibre(it, "")) }
        listOf("cc-by-nc-2.0", "cc-by-nd-4.0", "cc-by-nc-sa-3.0", "fair use", "gfdl").forEach { assertFalse(it, FichesSatPc.licenceLibre(it, "")) }
        assertTrue(FichesSatPc.licenceLibre("", "Public domain")); assertFalse(FichesSatPc.licenceLibre("", "All rights reserved"))
    }

    @Test fun trouveParNumeroOuNom() {
        assertEquals("iss", FichesSat.cherche(25544, "")!!.cle)
        assertEquals("so50", FichesSat.cherche(0, "SO-50")!!.cle)
        assertEquals("iss", FichesSat.cherche(0, "ISS (ZARYA)")!!.cle)
        assertEquals("qo100", FichesSat.cherche(0, "QO-100")!!.cle)
        assertNull(FichesSat.cherche(99999, "OBJET INCONNU"))
        assertNull(FichesSat.cherche(0, "AO"))
    }

    @Test fun recupereEtGarde() {
        val demandes = CopyOnWriteArrayList<String>()
        val f = FichesSatPc(dossier, telecharge = { u ->
            demandes += u
            when {
                u.contains("fr.wikipedia.org") -> echantillon("resume-iss-fr").toByteArray()
                u.contains("commons.wikimedia.org/w/api.php") -> echantillon("commons-iss").toByteArray()
                u.contains("thumb.wikimedia.org") -> jpeg
                else -> throw IOException("non")
            }
        }, lance = { it() })
        val o = JSONObject(String(f.json(25544, "ISS").corps))
        assertTrue(o.getBoolean("connu"))
        assertEquals("NASA", o.getJSONObject("faits").getString("operateur").substringBefore(','))
        // Fetched at once here (the tests' launcher), then read from the disk.
        val o2 = JSONObject(String(f.json(25544, "ISS").corps))
        assertEquals("Station spatiale internationale", o2.getJSONObject("resume").getString("titre"))
        assertEquals("api/satfiche/photo?cle=iss", o2.getJSONObject("photo").getString("url"))
        assertEquals("NASA", o2.getJSONObject("photo").getString("auteur"))
        assertEquals("Public domain", o2.getJSONObject("photo").getString("licence"))
        val ph = f.sert("/api/satfiche/photo") { if (it == "cle") "iss" else "" }
        assertEquals("image/jpeg", ph.type)
        // No e-mail, callsign or station in what was asked: the article, the file, its picture.
        assertTrue(demandes.all { it.startsWith("https://") && (it.contains("wikipedia.org") || it.contains("wikimedia.org")) })
        assertEquals(3, demandes.size)
        // A new server reads the card from the disk without asking anything.
        demandes.clear()
        val f2 = FichesSatPc(dossier, telecharge = { demandes += it; throw IOException("hors ligne") }, lance = { it() })
        assertNotNull(JSONObject(String(f2.json(25544, "ISS").corps)).optJSONObject("photo"))
        assertTrue(demandes.isEmpty())
    }

    @Test fun horsLigneSansBlocage() {
        var n = 0
        val f = FichesSatPc(dossier, telecharge = { n++; throw IOException("hors ligne") }, lance = { it() })
        val o = JSONObject(String(f.json(7530, "AO-07").corps))
        assertTrue(o.getBoolean("ok")); assertTrue(o.has("faits")); assertFalse(o.has("photo"))
        // Nothing kept, and not tried again at once.
        assertFalse(File(dossier, "ao7.json").exists())
        val avant = n
        f.json(7530, "AO-07")
        assertEquals(avant, n)
    }

    @Test fun photoNonLibreRefusee() {
        val f = FichesSatPc(dossier, telecharge = { u ->
            when {
                u.contains("wikipedia.org") -> echantillon("resume-iss-fr").toByteArray()
                u.contains("api.php") -> echantillon("commons-non-libre").toByteArray()
                else -> jpeg
            }
        }, lance = { it() })
        f.json(25544, "ISS")
        val o = JSONObject(String(f.json(25544, "ISS").corps))
        assertTrue(o.has("resume")); assertFalse(o.has("photo"))
        assertFalse(File(dossier, "iss.img").exists())
    }

    @Test fun bandesAmateur() {
        assertTrue(FichesSatPc.amateur(145_800_000L)); assertTrue(FichesSatPc.amateur(437_800_000L)); assertTrue(FichesSatPc.amateur(137_100_000L))
        assertTrue(FichesSatPc.amateur(10_489_750_000L))
        assertFalse(FichesSatPc.amateur(121_750_000L)); assertFalse(FichesSatPc.amateur(143_625_000L)); assertFalse(FichesSatPc.amateur(null))
    }

    @Test fun satelliteInconnu() {
        val f = FichesSatPc(dossier, telecharge = { throw IOException("jamais") }, lance = { it() })
        val o = JSONObject(String(f.json(12345, "TRUC-1").corps))
        assertFalse(o.getBoolean("connu")); assertFalse(o.has("faits"))
    }
}
