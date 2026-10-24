/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.OptionsJournal
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.ClassementSstv
import fr.f4ioz.satcombo.sstv.CorrectionsGalerie
import fr.f4ioz.satcombo.sstv.EvenementSstv
import fr.f4ioz.satcombo.sstv.Planche
import fr.f4ioz.satcombo.sstv.RapportEvenement
import fr.f4ioz.satcombo.sstv.SstvMeta
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Which version of a picture the journal shows: the one chosen, Auto the
 * best marked, a version missing falling back to Auto (said so); the
 * pass's marks pointing at the file chosen, « corrigée » written only when
 * asked; the choices kept with the pass.
 */
class ApiJournalSstvTest {

    private val d = File(System.getProperty("java.io.tmpdir"), "satme-journal-sstv-" + System.nanoTime()).also { it.mkdirs() }
    @After fun nettoie() { d.deleteRecursively() }
    @Before fun langue() { I18n.apply("fr", "fr") }

    private val c = CorrectionsGalerie({ null }, { _, _, _, _ -> false })

    private fun png(nom: String) = File(d, nom).also { it.writeBytes(byteArrayOf(1, 2, 3)) }
    /** A processed version as the gallery keeps it: its PNG in corrigees/, its sidecar. */
    private fun traitee(nom: String, f: CorrectionsGalerie.Fiche) {
        val dir = CorrectionsGalerie.dossier(d).also { it.mkdirs() }
        File(dir, nom).writeBytes(byteArrayOf(4, 5, 6))
        File(dir, nom.removeSuffix(".png") + ".meta").writeText(CorrectionsGalerie.encode(f))
    }
    private fun shot(nom: String, t: Long) = SstvMeta.SstvShot(fileName = nom, satName = "ISS", timeMs = t, mode = "PD120")

    @Test fun la_version_choisie_puis_le_repli_sur_auto() {
        val a = png("a.png"); val b = png("b.png")
        val g = ClassementSstv.Groupe("g", listOf(shot("a.png", 1L), shot("b.png", 2L)), 3, 12, ClassementSstv.Origine.LU, mapOf("a.png" to 70, "b.png" to 60))
        traitee(CorrectionsGalerie.nomCorrigee("a.png"), CorrectionsGalerie.Fiche("a.png", CorrectionsGalerie.nomCorrigee("a.png"), CorrectionsGalerie.Methode.SON, 70, 85, 1L))
        traitee(CorrectionsGalerie.nomFusion("g"), CorrectionsGalerie.Fiche("g", CorrectionsGalerie.nomFusion("g"), CorrectionsGalerie.Methode.FUSION, 70, 92, 1L, listOf("a.png", "b.png")))
        fun v(f: File, n: Int, x: Planche.Version) = VersionsJournal.choisit(c, d, f, f.name, n, g, x)
        val corrigee = File(CorrectionsGalerie.dossier(d), CorrectionsGalerie.nomCorrigee("a.png"))
        val fusion = File(CorrectionsGalerie.dossier(d), CorrectionsGalerie.nomFusion("g"))

        assertEquals(a, v(a, 70, Planche.Version.RECUE).fichier)
        assertNull(v(a, 70, Planche.Version.RECUE).fiche)
        assertEquals(corrigee, v(a, 70, Planche.Version.CORRIGEE).fichier)
        assertEquals(fusion, v(a, 70, Planche.Version.FUSION).fichier)
        // Auto: the best marked of the slide's versions.
        assertEquals(fusion, v(a, 70, Planche.Version.AUTO).fichier)
        assertFalse(v(a, 70, Planche.Version.AUTO).repli)
        assertEquals("fusion de 2 copies", v(a, 70, Planche.Version.AUTO).etiquette)
        // b has no corrected version of its own: Auto shown, said so.
        val vb = v(b, 60, Planche.Version.CORRIGEE)
        assertTrue(vb.repli); assertEquals(fusion, vb.fichier)
        // The fusion deleted: chosen still, the best left shown instead.
        c.oublieFusion(d, "g")
        val va = v(a, 70, Planche.Version.FUSION)
        assertTrue(va.repli); assertEquals(corrigee, va.fichier)
        // Nothing processed and no group: as received.
        val seule = png("seule.png")
        assertEquals(seule, VersionsJournal.choisit(c, d, seule, "seule.png", 50, null, Planche.Version.AUTO).fichier)
        assertEquals(seule, VersionsJournal.choisit(c, d, seule, "seule.png", 50, null, Planche.Version.FUSION).fichier)
    }

    // ------------------------------------------------------------ through the page's routes

    private val t0 = 1_790_000_000_000L
    private val shots = listOf(shot("a.png", t0 + 200_000L), shot("x.png", t0 + 400_000L))

    private inner class Source : ApiPlancheEvenement.Source {
        override val dossier: File get() = d
        override val classement = ClassementGalerie { null }
        override fun images() = shots
        override fun evenements(): List<EvenementSstv.Evenement> = emptyList()
        override fun catnumDe(sat: String) = 25544
        override fun passes(catnum: Int, deMs: Long, aMs: Long): List<SatPass> = emptyList()
        override fun positions(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>? = null
        override fun epoqueElements(catnum: Int): Long? = null
        override fun elMin() = 10.0
        override fun station() = RapportEvenement.Station("F4IOZ", "JN18FS")
        override fun chaine(plan: String) = "Téléphone" to listOf(listOf(RapportEvenement.Etape("Antenne", "Yagi")))
        override fun planche(m: Planche.Modele): ByteArray? = null
        override fun vignette(nom: String): ByteArray? = null
        override fun corrections() = api
        override fun maintenant() = t0 + 3_600_000L
    }
    private val groupes = listOf(ClassementSstv.Groupe("a.png", listOf(shots[0]), 3, 12, ClassementSstv.Origine.LU, mapOf("a.png" to 70)),
        ClassementSstv.Groupe("x.png", listOf(shots[1]), 0, 12, ClassementSstv.Origine.AUCUNE, mapOf("x.png" to 40)))
    private val api = ApiCorrectionSstv(c, { d }, { shots }, { groupes })

    private var garde = JournalPassage.Entree(25544, "ISS", t0, t0 + 600_000L, "JN18FS",
        points = (0..12).map { JournalPassage.Point(t0 + it * 50_000L, 200.0 + it * 10, 50 * kotlin.math.sin(Math.PI * it / 12)) })
    private val plateforme = object : ApiJournalSstv.Plateforme {
        override fun source() = Source()
        override fun passage(id: String) = garde.takeIf { it.id == id }
        override fun enregistre(e: JournalPassage.Entree) { garde = e }
        override fun images(e: JournalPassage.Entree) = shots.map { File(d, it.fileName) to it }
    }

    @Test fun les_choix_de_la_page_vont_au_passage_et_aux_marques() {
        png("a.png"); png("x.png")
        traitee(CorrectionsGalerie.nomCorrigee("a.png"), CorrectionsGalerie.Fiche("a.png", CorrectionsGalerie.nomCorrigee("a.png"), CorrectionsGalerie.Methode.IMAGE, 70, 81, 1L))
        val j = ApiJournalSstv(plateforme)
        val marques = shots.map { JournalPassage.Marque(JournalPassage.TypeMarque.SSTV, it.timeMs - 60_000L, it.timeMs, it.mode, fichier = File(d, it.fileName).absolutePath) }
        val corrigee = File(CorrectionsGalerie.dossier(d), CorrectionsGalerie.nomCorrigee("a.png")).absolutePath

        // An older pass: the pictures as received, the marks untouched.
        assertEquals(marques, j.marques(garde, marques))
        assertEquals(File(d, "a.png"), j.image(garde.id, "a.png"))

        // The page sets Auto for the pass: a.png's corrected version shows, x.png as received.
        val p = mapOf("id" to garde.id, "quoi" to "defaut", "v" to "auto")
        val r = j.sert("/sstv/choix", { p[it] ?: "" }, "POST")!!
        val o = JSONObject(String(r.corps))
        assertTrue(o.getBoolean("ok"))
        assertEquals(Planche.Version.AUTO, garde.options.version)
        val m = j.marques(garde, marques)
        assertEquals(corrigee, m[0].fichier); assertEquals(marques[1].fichier, m[1].fichier)
        assertEquals(File(corrigee), j.image(garde.id, "a.png"))
        // Nothing written on it unless asked.
        assertTrue(j.etiquettes(garde).isEmpty())
        j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "marque", "v" to "1")[it] ?: "" }, "POST")
        assertEquals(mapOf(corrigee to "corrigée"), j.etiquettes(garde))

        // a.png back to as received by itself: the pass's Auto no longer for it.
        j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "image", "f" to "a.png", "v" to "recue")[it] ?: "" }, "POST")
        assertEquals(marques, j.marques(garde, marques))
        // Its choice taken back: the pass's again.
        j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "image", "f" to "a.png", "v" to "")[it] ?: "" }, "POST")
        assertEquals(corrigee, j.marques(garde, marques)[0].fichier)

        // A panel laid over the replay, then moved with the mouse.
        j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "element", "k" to "ciel", "incruste" to "1")[it] ?: "" }, "POST")
        j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "element", "k" to "ciel", "cadre" to "0.1,0.2,0.3")[it] ?: "" }, "POST")
        val ciel = garde.options.reglage(OptionsJournal.Element.CIEL)
        assertTrue(ciel.incruste); assertFalse(ciel.carte); assertEquals("0.1000,0.2000,0.3000", ciel.cadre?.texte())

        // What the page reads: the versions there are, with their marks; a GET never changes anything.
        val etat = JSONObject(String(j.sert("/sstv", { if (it == "id") garde.id else "" })!!.corps))
        val ia = etat.getJSONArray("images").getJSONObject(0)
        assertEquals("a.png", ia.getString("f"))
        assertEquals(2, ia.getJSONArray("versions").length())
        assertEquals(81, ia.getJSONArray("versions").getJSONObject(1).getInt("note"))
        assertEquals("corrigee", ia.getString("montre"))
        assertFalse(JSONObject(String(j.sert("/sstv/choix", { mapOf("id" to garde.id, "quoi" to "marque", "v" to "0")[it] ?: "" }, "GET")!!.corps)).optBoolean("ok", false))
        assertTrue(garde.options.marqueTraitees)
        // Without an event, the pass's own sky still offered; the slides and the figures not.
        val el = (0 until etat.getJSONArray("elements").length()).map { etat.getJSONArray("elements").getJSONObject(it) }.associateBy { it.getString("k") }
        assertTrue(el.getValue("ciel").getBoolean("dispo"))
        assertTrue(el.getValue("montage").getBoolean("dispo"))
        assertFalse(el.getValue("planche").getBoolean("dispo"))
        // The pass written and read back holds all of it.
        assertEquals(garde.options, JournalPassage.lit(JournalPassage.ecrit(garde))!!.options)
    }
}
