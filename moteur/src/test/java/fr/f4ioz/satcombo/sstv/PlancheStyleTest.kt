/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.sstv.Planche.Champ
import fr.f4ioz.satcombo.sstv.Planche.Texte
import fr.f4ioz.satcombo.sstv.Planche.Zone
import fr.f4ioz.satcombo.sstv.PlancheStyle.Cadre
import fr.f4ioz.satcombo.sstv.PlancheStyle.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A sheet's dress — fonts, effects, frames, styled titles, the « Affiche
 * ISS » —: kept in its file and read back, an older sheet read and written
 * as it was, the styled titles placed clear of the pictures.
 */
class PlancheStyleTest {

    private val habille = Texte(Champ.LIBRE, Zone(0.1f, 0.2f, 0.3f, 0.1f), Planche.CYAN, 0, "SSTV; 34", "exo2", Style.NEON,
        0xFFFF3FD2.toInt(), italique = true, espacement = 0.14f, filets = true, groupe = "ariss")

    @Test
    fun le_style_d_un_texte_et_le_cadre_aller_retour() {
        val m = Planche.genereGrille("g", "g").let { it.copy(textes = it.textes + habille, cadre = Cadre.NEON, cadreCouleur = 0xFF39FF88.toInt()) }
        val lu = Planche.lit("g", Planche.ecrit(m))!!
        val t = lu.textes.last()
        assertEquals("SSTV, 34", t.libre)
        assertEquals(habille.copy(libre = "SSTV, 34"), t)
        assertEquals(Cadre.NEON, lu.cadre)
        assertEquals(0xFF39FF88.toInt(), lu.cadreCouleur)
        // Every effect and frame comes back as it went.
        Style.values().forEach { s -> assertEquals(s, Planche.lit("x", Planche.ecrit(m.copy(textes = listOf(habille.copy(style = s)))))!!.textes[0].style) }
        Cadre.values().forEach { c -> assertEquals(c, Planche.lit("x", Planche.ecrit(m.copy(cadre = c)))!!.cadre) }
    }

    @Test
    fun une_planche_d_avant_s_ecrit_et_se_lit_comme_avant() {
        // Written by the version before: eight fields a text, no frame.
        val avant = "planche=1\nnom=Vieille\nfond=\nratio=1.414\nlegende=1\nnumeros=1\ntitre=\n" +
            "case=0.1;0.1;0.2;0.2\ntexte=TITRE;0.02;0.05;0.29;0.16;-14700000;0;\ntexte=LIBRE;0.3;0.4;0.3;0.07;-1;-1728053248;73\n"
        val m = Planche.lit("v", avant)!!
        assertTrue(m.textes.all { PlancheStyle.estSimple(it) && it.couleur2 == 0 && it.groupe.isEmpty() })
        assertEquals(Cadre.AUCUN, m.cadre)
        assertEquals("73", m.textes[1].libre)
        // Written again: nothing new in it — an older version reads it unchanged.
        val e = Planche.ecrit(m)
        assertFalse(e, e.contains("cadre="))
        e.lines().filter { it.startsWith("texte=") }.forEach { assertEquals(it, 8, it.split(';').size) }
        // A styled text still has its own words where an older version looks for them (the 8th field).
        val l = Planche.ecrit(m.copy(textes = listOf(habille))).lines().first { it.startsWith("texte=") }
        assertEquals("SSTV, 34", l.substringAfter('=').split(';')[7])
    }

    @Test
    fun ce_qu_une_version_plus_recente_ajoute_est_laisse() {
        val l = "planche=1\nnom=x\ntexte=LIBRE;0.1;0.1;0.2;0.1;-1;0;Hi;police-inconnue;hologramme;12;1;9;1;../x;plus;encore\ncadre=etoile;abc\n"
        val m = Planche.lit("x", l)!!
        val t = m.textes.single()
        assertEquals("", t.police); assertEquals(Style.SIMPLE, t.style)
        assertEquals(PlancheStyle.ESPACEMENT_MAX, t.espacement)
        assertEquals("", t.groupe)
        assertEquals(Cadre.AUCUN, m.cadre)
    }

    @Test
    fun le_fichier_d_un_modele_garde_son_habit() {
        val d = File(System.getProperty("java.io.tmpdir"), "satme-style-" + System.nanoTime())
        try {
            val r = Planche.Rangement(d)
            val m = PlancheTitres.genereAffiche("a", "Affiche", PlancheTitres.Infos("33", "ARISS SSTV Series 33"))
            r.enregistre(m)
            val octets = PlancheFichier.exporte(m, r, { null })
            val d2 = File(d, "autre"); val r2 = Planche.Rangement(d2)
            val n = PlancheFichier.importe(octets, r2)
            assertEquals(m.cadre, n.cadre); assertEquals(m.cadreCouleur, n.cadreCouleur)
            assertEquals(m.decor, n.decor)
            assertEquals(m.textes.map { listOf(it.police, it.style, it.italique, it.espacement, it.filets, it.groupe, it.libre) },
                n.textes.map { listOf(it.police, it.style, it.italique, it.espacement, it.filets, it.groupe, it.libre) })
        } finally { d.deleteRecursively() }
    }

    // ------------------------------------------------------------ styled titles

    private fun touche(a: Zone, b: Zone) = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

    private fun dansLaPlanche(z: Zone) = z.x >= 0f && z.y >= 0f && z.x + z.w <= 1.0001f && z.y + z.h <= 1.0001f

    private val infos = PlancheTitres.Infos("33", "ARISS SSTV Series 33", "SÉRIE")

    @Test
    fun le_numero_de_la_serie_vient_du_nom_de_l_evenement() {
        assertEquals("33", PlancheTitres.numero("ARISS SSTV Series 33"))
        assertEquals("34", PlancheTitres.numero("ARISS série 34; « Artemis »"))
        assertEquals("12", PlancheTitres.numero("MAI-75 12"))
        assertEquals("", PlancheTitres.numero("ISS · 2026-10-23 – 2026-10-24".substringBefore(" ·")))
        assertEquals("", PlancheTitres.numero(null))
        val ev = EvenementSstv.Evenement("c:s33", EvenementSstv.Genre.CAMPAGNE, "ARISS SSTV Series 33", "ISS", 25544, 0L, 1L)
        assertEquals("33", PlancheTitres.infos(Planche.genereGrille("g", "g").copy(evenement = ev), "SERIES").numero)
    }

    @Test
    fun un_titre_style_se_pose_a_la_place_du_titre_sans_toucher_les_images() {
        for (base in listOf(Planche.genereGrille("g", "g"), Planche.genereTour("t", "t"))) {
            for (titre in PlancheTitres.LISTE) {
                val m = PlancheTitres.applique(base, titre.id, infos)
                val siens = m.textes.filter { it.groupe == titre.id }
                assertTrue(titre.id, siens.isNotEmpty())
                assertTrue(titre.id, siens.all { !PlancheStyle.estSimple(it) })
                // The plain title gave its place; the other texts are all still there, none over a picture.
                assertTrue(m.textes.none { it.champ == Champ.TITRE && it.groupe.isEmpty() })
                assertEquals(base.textes.count { it.champ != Champ.TITRE }, m.textes.count { it.groupe.isEmpty() })
                m.textes.forEach { t ->
                    assertTrue(titre.id + " " + t, dansLaPlanche(t.zone))
                    assertTrue(titre.id + " " + t, m.cases.none { touche(it, t.zone) })
                }
                // The texts of a title do not overlap the sheet's other texts.
                siens.forEach { s -> m.textes.filter { it.groupe.isEmpty() }.forEach { o -> assertFalse("${titre.id}: $s / $o", touche(s.zone, o.zone)) } }
            }
        }
    }

    private fun proche(a: Zone, b: Zone) = listOf(a.x - b.x, a.y - b.y, a.w - b.w, a.h - b.h).forEach { assertEquals("$a / $b", 0f, it, 1e-4f) }

    @Test
    fun un_titre_en_remplace_un_autre_et_le_titre_simple_revient() {
        val g = Planche.genereGrille("g", "g")
        val a = PlancheTitres.applique(g, "ariss", infos)
        val bloc = PlancheTitres.bloc(a)
        assertEquals(listOf("SSTV", "SÉRIE", "33"), a.textes.filter { it.groupe == "ariss" }.map { it.libre })
        assertTrue(a.textes.single { it.libre == "SÉRIE" }.filets)
        val n = PlancheTitres.applique(a, "neon", infos)
        assertTrue(n.textes.none { it.groupe == "ariss" })
        proche(bloc, PlancheTitres.bloc(n))
        // The neon title says the event's name, its number left to the big figures.
        assertTrue(n.textes.any { it.libre == "ARISS SSTV SERIES" })
        val s = PlancheTitres.applique(n, "aucun", infos)
        assertTrue(s.textes.none { it.groupe.isNotEmpty() })
        proche(bloc, s.textes.single { it.champ == Champ.TITRE }.zone)
        assertTrue(PlancheStyle.estSimple(s.textes.single { it.champ == Champ.TITRE }))
        // No number known: the title says SSTV and the series' word only.
        assertEquals(2, PlancheTitres.applique(g, "ariss", PlancheTitres.Infos()).textes.count { it.groupe == "ariss" })
    }

    @Test
    fun le_titre_pousse_les_dates_vers_le_bas_pour_grandir() {
        val g = Planche.genereGrille("g", "g")
        val m = PlancheTitres.applique(g, "ecusson", infos)
        val avant = g.textes.single { it.champ == Champ.DATES }.zone
        val apres = m.textes.single { it.champ == Champ.DATES }.zone
        assertTrue(apres.y > avant.y)
        assertTrue(PlancheTitres.bloc(m).h > g.textes.single { it.champ == Champ.TITRE }.zone.h * 1.5f)
    }

    @Test
    fun l_affiche_iss() {
        val a = PlancheTitres.genereAffiche("a", "Affiche ISS", infos)
        assertEquals(12, a.cases.size)
        assertNotNull(PlancheDecor.integre(a.decor)?.ressource)
        assertEquals(Cadre.NEON, a.cadre)
        assertTrue(a.textes.count { it.groupe == "ariss" } == 3)
        listOf(Champ.INDICATIF, Champ.NOM_LOCATOR, Champ.DATES).forEach { c -> assertTrue(a.textes.any { it.champ == c }) }
        a.textes.forEach { t -> assertTrue(dansLaPlanche(t.zone)); assertTrue(t.toString(), a.cases.none { touche(it, t.zone) }) }
        a.cases.forEach { assertTrue(dansLaPlanche(it)) }
        // Kept and read back, the same sheet.
        assertEquals(a.copy(id = "x"), Planche.lit("x", Planche.ecrit(a))!!.copy(nom = a.nom))
    }

    // ------------------------------------------------------------ drawing helpers

    @Test
    fun le_flou_etale_sans_perdre_la_couleur() {
        val w = 41; val h = 41
        val px = IntArray(w * h); px[20 * w + 20] = 0xFFFFFFFF.toInt()
        for (y in 18..22) for (x in 18..22) px[y * w + x] = 0xFFFFFFFF.toInt()
        PlancheStyle.flou(px, w, h, 6, 0x802EC5FF.toInt())
        // The colour everywhere, the alpha spread round the centre, highest there, halved by the colour's.
        assertTrue(px.all { (it and 0xFFFFFF) == 0x2EC5FF })
        val a = { x: Int, y: Int -> (px[y * w + x] ushr 24) and 0xFF }
        assertTrue(a(20, 20) in 1..128)
        assertTrue(a(20, 20) > a(26, 20)); assertTrue(a(26, 20) > 0); assertEquals(0, a(0, 0))
    }

    @Test
    fun ce_qu_une_police_ne_sait_pas_dessiner_est_remplace() {
        val ascii = { c: Int -> c in 0x20..0x7E }
        assertEquals("ISS - SSTV", PlancheStyle.lisible("ISS · SSTV", ascii))
        assertEquals("Serie 34", PlancheStyle.lisible("Série 34", ascii))
        assertEquals("a\nb", PlancheStyle.lisible("a\nb", ascii))
        assertEquals("Série 34 · F4IOZ", PlancheStyle.lisible("Série 34 · F4IOZ") { true })
    }

    @Test
    fun les_couches_de_chaque_effet() {
        Style.values().forEach { s ->
            val c = PlancheStyle.couches(habille.copy(style = s))
            assertTrue(s.name, c.isNotEmpty())
            c.forEach { k -> assertTrue(s.name, k.positions == null || k.positions!!.size == k.couleurs.size) }
            assertTrue(PlancheStyle.marge(habille.copy(style = s)) in 0f..0.35f)
        }
        assertTrue(PlancheStyle.estSimple(Texte(Champ.TITRE, Zone(0f, 0f, 1f, 1f))))
        assertFalse(PlancheStyle.estSimple(Texte(Champ.TITRE, Zone(0f, 0f, 1f, 1f), police = "exo2")))
    }

    @Test
    fun les_polices_et_leur_licence_sont_la() {
        assertTrue(PlanchePolices.LISTE.size in 5..8)
        PlanchePolices.LISTE.forEach { p ->
            val o = PlanchePolices.octets(p)
            assertNotNull(p.id, o)
            assertTrue(p.id, o!!.size in 20_000..250_000)
            val l = PlanchePolices::class.java.getResourceAsStream("polices/" + p.licence)!!.use { String(it.readBytes()) }
            assertTrue(p.id, l.contains("SIL OPEN FONT LICENSE", ignoreCase = true))
        }
        assertNull(PlanchePolices.de("")); assertNull(PlanchePolices.de("../x"))
    }
    @Test fun affiche_avec_moins_d_images_les_cases_evitent_titre_et_pied() {
        val a = PlancheTitres.genereAffiche("a", "Affiche ISS", PlancheTitres.Infos("34", "ARISS SSTV Series 34"))
        for (n in 1..12) {
            val m = Planche.ajusteA(a, (1..n).map { "img$it.png" })
            assertEquals(n, m.cases.size)
            for (c in m.cases) for (t in m.textes) {
                val chevauche = c.x < t.zone.x + t.zone.w && t.zone.x < c.x + c.w && c.y < t.zone.y + t.zone.h && t.zone.y < c.y + c.h
                assertFalse("$n images : case $c sur ${t.champ}", chevauche)
            }
        }
    }
}
