/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import fr.f4ioz.satcombo.domain.MemoiresSdr
import fr.f4ioz.satcombo.domain.MemoiresSdr.Memoire
import fr.f4ioz.satcombo.domain.PlanBandes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receiver's named frequencies and the band plans' strip. */
class MemoiresSdrTest {

    private val iss = Memoire("a", "ISS", 145_800_000L, "NFM", 16_000, "Satellites FM", "#3fe0c8")
    private val so50 = Memoire("b", "SO-50", 436_795_000L, "NFM", 0, "Satellites FM")
    private val balise = Memoire("c", "Balise médiane", 10_489_750_000L, "CW", 500, "QO-100")

    @Test
    fun aller_retour_json() {
        val l = MemoiresSdr.nettoie(listOf(so50, iss, balise))
        val relue = MemoiresSdr.lit(MemoiresSdr.ecrit(l).toString())
        assertEquals(l, relue)
        assertEquals("#3FE0C8", relue.first { it.id == "a" }.couleur)
        assertEquals(listOf("a", "b", "c"), relue.map { it.id })
    }

    @Test
    fun lit_un_tableau_nu_en_mhz() {
        val l = MemoiresSdr.lit("""[{"name":"AO-91","mhz":145.96,"mode":"fm","tag":"Satellites FM","color":"red"}]""")
        val m = MemoiresSdr.nettoie(l).single()
        assertEquals(145_960_000L, m.hz)
        assertEquals("NFM", m.mode)
        assertEquals("Satellites FM", m.etiquette)
        assertEquals("", m.couleur)
        assertTrue(m.id.isNotEmpty())
    }

    @Test
    fun un_texte_illisible_donne_une_liste_vide() {
        assertTrue(MemoiresSdr.lit("pas du json").isEmpty())
        assertTrue(MemoiresSdr.lit("{}").isEmpty())
    }

    @Test
    fun une_frequence_absurde_est_ecartee_et_les_ids_doubles_refaits() {
        val l = MemoiresSdr.nettoie(listOf(iss, iss.copy(nom = "ISS bis"), iss.copy(hz = -5)))
        assertEquals(2, l.size)
        assertEquals(2, l.map { it.id }.toSet().size)
    }

    @Test
    fun poser_remplace_le_meme_id() {
        var l = MemoiresSdr.nettoie(listOf(iss, so50))
        l = MemoiresSdr.pose(l, iss.copy(nom = "ISS phonie"))
        assertEquals(2, l.size)
        assertEquals("ISS phonie", l.first { it.id == "a" }.nom)
        l = MemoiresSdr.pose(l, Memoire("", "RS-44", 435_640_000L, "USB"))
        assertEquals(3, l.size)
        l = MemoiresSdr.retire(l, "b")
        assertEquals(listOf("ISS phonie", "RS-44"), l.map { it.nom })
    }

    @Test
    fun importer_ne_double_pas() {
        val l = MemoiresSdr.nettoie(listOf(iss, so50))
        val f = MemoiresSdr.fusionne(l, listOf(iss.copy(id = "zz"), balise, so50.copy(nom = "SO-50 nouveau")))
        // The ISS under another id: same frequency and name, already there; SO-50 by its id: replaced.
        assertEquals(3, f.size)
        assertEquals("SO-50 nouveau", f.first { it.id == "b" }.nom)
    }

    @Test
    fun la_plus_proche_et_celles_de_la_vue() {
        val l = MemoiresSdr.nettoie(listOf(iss, so50, balise))
        assertEquals("ISS", MemoiresSdr.proche(l, 145_801_500L, 2_000L)?.nom)
        assertNull(MemoiresSdr.proche(l, 145_810_000L, 2_000L))
        assertEquals(listOf("ISS"), MemoiresSdr.dans(l, 145_700_000L, 145_900_000L).map { it.nom })
        assertEquals(listOf("QO-100", "Satellites FM"), MemoiresSdr.etiquettes(l))
        assertEquals("USB", MemoiresSdr.modeSdr("CW"))
    }

    @Test
    fun un_fichier_de_version_1_range_chaque_memoire_dans_le_dossier_de_son_etiquette() {
        val v1 = """{"format":"satme-memoires-sdr","version":1,"memoires":[
            {"id":"a","nom":"ISS","hz":145800000,"mode":"NFM","largeur":0,"etiquette":"Satellites FM","couleur":""},
            {"id":"b","nom":"Rien","hz":145500000,"mode":"","largeur":0,"etiquette":"","couleur":""}]}"""
        val l = MemoiresSdr.nettoie(MemoiresSdr.lit(v1))
        assertEquals("Satellites FM", l.first { it.id == "a" }.dossier)
        assertEquals("Satellites FM", l.first { it.id == "a" }.etiquette)
        assertEquals("", l.first { it.id == "b" }.dossier)
        assertTrue(MemoiresSdr.litDossiers(v1).isEmpty())
        // Written again in version 2, read back as it is (a folder left empty by hand kept).
        val v2 = MemoiresSdr.ecrit(l, listOf("Relais")).toString()
        assertEquals(l, MemoiresSdr.lit(v2))
        assertEquals(listOf("Relais"), MemoiresSdr.litDossiers(v2))
    }

    @Test
    fun les_dossiers_un_niveau_de_sous_dossiers() {
        assertEquals("Satellites FM/ISS", MemoiresSdr.dossier(" Satellites FM / ISS / phonie "))
        val l = MemoiresSdr.nettoie(listOf(iss.copy(dossier = "Satellites FM/ISS"), so50.copy(dossier = "Satellites FM"), balise.copy(dossier = "QO-100")))
        assertEquals(listOf("Balises", "QO-100", "Satellites FM", "Satellites FM/ISS"), MemoiresSdr.dossiers(l, listOf("Balises")))
        // Renamed: the sub-folder and its memories follow.
        val (r, c) = MemoiresSdr.renommeDossier(l, listOf("Satellites FM/ISS"), "Satellites FM", "Sat FM")
        assertEquals("Sat FM/ISS", r.first { it.id == "a" }.dossier)
        assertEquals("Sat FM", r.first { it.id == "b" }.dossier)
        assertEquals(listOf("Sat FM/ISS"), c)
        // Removed: its memories to the parent folder, none lost.
        val (s, c2) = MemoiresSdr.supprimeDossier(r, c, "Sat FM/ISS")
        assertEquals(3, s.size)
        assertEquals("Sat FM", s.first { it.id == "a" }.dossier)
        assertTrue(c2.isEmpty())
        val (s2, _) = MemoiresSdr.supprimeDossier(s, emptyList(), "Sat FM")
        assertEquals("", s2.first { it.id == "a" }.dossier)
        assertEquals("QO-100", s2.first { it.id == "c" }.dossier)
        // Moved.
        assertEquals("Balises", MemoiresSdr.deplace(l, setOf("c"), "Balises").first { it.id == "c" }.dossier)
    }

    @Test
    fun trier_chercher_et_garder_la_date_et_la_tonalite() {
        var l = MemoiresSdr.nettoie(listOf(iss, so50, balise))
        l = MemoiresSdr.pose(l, Memoire("", "Relais F5ZXX", 145_712_500L, "NFM", ctcssHz = 123.04), maintenantMs = 5_000L)
        val r = l.first { it.nom == "Relais F5ZXX" }
        assertEquals(123.0, r.ctcssHz, 0.0)
        assertEquals(5_000L, r.creeMs)
        // Edited: its date stays.
        l = MemoiresSdr.pose(l, r.copy(nom = "Relais", creeMs = 0L), maintenantMs = 9_000L)
        assertEquals(5_000L, l.first { it.id == r.id }.creeMs)
        assertEquals(0.0, MemoiresSdr.ctcss(30.0), 0.0)
        assertEquals(listOf("Balise médiane", "ISS", "Relais", "SO-50"), MemoiresSdr.trie(l, MemoiresSdr.Tri.NOM).map { it.nom })
        assertEquals(listOf("SO-50", "Relais", "ISS", "Balise médiane"), MemoiresSdr.trie(l, MemoiresSdr.Tri.NOM, inverse = true).map { it.nom })
        assertEquals(listOf("Relais", "ISS", "SO-50", "Balise médiane"), MemoiresSdr.trie(l, MemoiresSdr.Tri.FREQUENCE).map { it.nom })
        assertEquals("Balise médiane", MemoiresSdr.trie(l, MemoiresSdr.Tri.BANDE).last().nom)
        assertEquals("Relais", MemoiresSdr.trie(l, MemoiresSdr.Tri.DATE).last().nom)
        assertEquals(listOf("ISS"), MemoiresSdr.cherche(l, "145,8").map { it.nom })
        assertEquals(listOf("SO-50"), MemoiresSdr.cherche(l, "so-5").map { it.nom })
        assertEquals(2, MemoiresSdr.cherche(l, "satellites").size)
        // The lockout is kept through the file.
        val p = MemoiresSdr.lit(MemoiresSdr.ecrit(listOf(iss.copy(passe = true))).toString()).single()
        assertTrue(p.passe)
    }

    @Test
    fun le_plan_couvre_les_bandes_satellites() {
        val p = PlanBandes.defaut()
        assertEquals(listOf("2m", "70cm", "23cm", "13cm", "3cm", "qo100"), PlanBandes.bandes(p))
        assertEquals(PlanBandes.Usage.SATELLITE, PlanBandes.segment(p, 145_900_000L)?.usage)
        assertEquals(PlanBandes.Usage.SATELLITE, PlanBandes.segment(p, 436_000_000L)?.usage)
        assertEquals(PlanBandes.Usage.BALISE, PlanBandes.segment(p, 144_450_000L)?.usage)
        assertNull(PlanBandes.segment(p, 100_000_000L))
        p.forEach { assertTrue(it.nom, it.hautHz > it.basHz) }
    }

    @Test
    fun le_segment_le_plus_fin_l_emporte() {
        val p = PlanBandes.defaut()
        // QO-100's middle beacon lies in the 3 cm satellite band: the beacon is what is said.
        assertEquals("qo100", PlanBandes.segment(p, 10_489_750_000L)?.bande)
        assertEquals(PlanBandes.Usage.BALISE, PlanBandes.segment(p, 10_489_750_000L)?.usage)
        assertEquals("QO-100 montée bande étroite", PlanBandes.segment(p, 2_400_100_000L)?.nom)
        // In view order: the wide one first.
        val v = PlanBandes.dans(p, 10_489_000_000L, 10_490_500_000L)
        assertEquals(PlanBandes.Usage.SATELLITE, v.first().usage)
        assertEquals(13, v.size)
    }

    @Test
    fun un_fichier_remplace_seulement_ses_bandes() {
        val f = PlanBandes.lit("""{"segments":[{"bande":"2m","basMhz":145.8,"hautMhz":146,"usage":"satellite","nom":"Sat"},
            {"bande":"2m","bas":145000000,"haut":144000000,"usage":"FM"},{"bande":"","bas":1,"haut":2}]}""")
        assertEquals(1, f.size)
        val p = PlanBandes.avecSurcharge(PlanBandes.defaut(), f)
        assertEquals(1, p.count { it.bande == "2m" })
        assertNull(PlanBandes.segment(p, 144_050_000L))
        assertTrue(p.any { it.bande == "70cm" })
        assertEquals(PlanBandes.lit(PlanBandes.json(p).toString()), p)
    }
}
