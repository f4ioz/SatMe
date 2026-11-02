/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.i18n.EN
import fr.f4ioz.satcombo.i18n.FR
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SatMe-PC's guided tours, assistant and light interface (GuidePagePc), and the
 * Suivi tab's rotor badge: the tours point at elements of the page, in two
 * languages, only show (never click); the light interface hides explanations
 * only; the rotor's badge connects (as Réglages' « Connecter ») and never moves it.
 */
class GuidePagePcTest {

    private val h = PagePc.HTML
    private val visites = GuidePagePc.SOURCE.getJSONArray("visites").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun deuxLangues(o: JSONObject, cle: String, ou: String) {
        val x = o.getJSONObject(cle)
        assertTrue("$ou.$cle fr", x.optString("fr").isNotBlank())
        assertTrue("$ou.$cle en", x.optString("en").isNotBlank())
    }

    /** The body of JS function [nom] in the page. */
    private fun corps(nom: String): String {
        val i = h.indexOf("function $nom(")
        assertTrue("function $nom", i >= 0)
        val j = h.indexOf("\n}", i)
        return h.substring(i, j)
    }

    @Test
    fun chaque_onglet_principal_a_sa_visite_et_le_tour_rapide_existe() {
        val ids = visites.map { it.getString("id") }
        assertEquals("ids uniques", ids.size, ids.toSet().size)
        assertTrue("tour" in ids)
        // Every tab of the page (the journal and the sheet are pages of their own).
        val onglets = PagePc.TOUS - setOf("journal", "planche")
        assertEquals(emptyList<String>(), onglets.filter { it !in ids })
        val groupes = GuidePagePc.SOURCE.getJSONArray("groupes").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        visites.forEach { assertTrue(it.getString("id"), it.getString("groupe") in groupes) }
    }

    @Test
    fun tout_est_en_francais_et_en_anglais() {
        visites.forEach { v ->
            val id = v.getString("id")
            deuxLangues(v, "titre", id); deuxLangues(v, "resume", id)
            val e = v.getJSONArray("etapes")
            assertTrue(id, e.length() >= 1)
            for (i in 0 until e.length()) {
                val et = e.getJSONObject(i)
                assertEquals("$id/$i : clés connues", emptySet<String>(), et.keySet() - GuidePagePc.CLES_ETAPE)
                deuxLangues(et, "titre", "$id/$i"); deuxLangues(et, "texte", "$id/$i")
                GuidePagePc.ENCADRES.filter { et.has(it) }.forEach { deuxLangues(et, it, "$id/$i") }
            }
        }
        // Served in one language: every pair made a string.
        val en = GuidePagePc.visites(true).getJSONArray("visites").getJSONObject(0)
        assertEquals("Quick tour of SatMe-PC", en.getString("titre"))
        assertEquals("Tour rapide de SatMe-PC", GuidePagePc.visites(false).getJSONArray("visites").getJSONObject(0).getString("titre"))
    }

    @Test
    fun les_visites_pointent_des_elements_qui_existent() {
        visites.forEach { v ->
            val id = v.getString("id")
            v.optString("onglet").takeIf { it.isNotEmpty() }?.let { assertTrue("$id : onglet $it", h.contains("id=\"v-$it\"")) }
            val e = v.getJSONArray("etapes")
            for (i in 0 until e.length()) {
                val et = e.getJSONObject(i)
                et.optString("cible").takeIf { it.isNotEmpty() }?.let { c ->
                    if (c.startsWith("v:")) assertTrue("$id/$i : $c", h.contains("data-v=\"${c.substring(2)}\"") || h.contains("href=\"${c.substring(2)}\""))
                    else assertTrue("$id/$i : id=\"$c\"", h.contains("id=\"$c\""))
                }
                et.optString("onglet").takeIf { it.isNotEmpty() }?.let { assertTrue("$id/$i : onglet $it", h.contains("id=\"v-$it\"")) }
                et.optString("pan").takeIf { it.isNotEmpty() }?.let { assertTrue("$id/$i : sous-onglet $it", h.contains("class=\"st-pan\" id=\"$it\"")) }
                et.optString("reglages").takeIf { it.isNotEmpty() }?.let { assertTrue("$id/$i : réglages $it", h.contains("{ id: '$it', nom:")) }
                et.optString("image").takeIf { it.isNotEmpty() }?.let { assertNotNull("$id/$i : image $it", GuidePagePc.image(it)) }
            }
            v.optString("image").takeIf { it.isNotEmpty() }?.let { assertNotNull("$id : image $it", GuidePagePc.image(it)) }
        }
    }

    @Test
    fun les_images_restent_legeres() {
        val noms = visites.flatMap { v -> listOf(v.optString("image")) + (0 until v.getJSONArray("etapes").length()).map { v.getJSONArray("etapes").getJSONObject(it).optString("image") } }
            .filter { it.isNotEmpty() }.toSet()
        noms.forEach { assertTrue(it, GuidePagePc.image(it)!!.size < 150_000) }
        assertEquals(null, GuidePagePc.image("../guide-pc.json"))
    }

    @Test
    fun la_visite_ne_clique_rien_et_dit_les_garde_fous_de_l_emission() {
        // It only shows: a tab, a settings category, a sub-tab.
        assertFalse(GuidePagePc.JS.contains(".click("))
        assertFalse(GuidePagePc.JS.contains("post("))
        assertFalse(GuidePagePc.JS.contains("api/rotor") || GuidePagePc.JS.contains("api/cat/") || GuidePagePc.JS.contains("emission"))
        // The tours where one transmits say how it is bounded and stopped.
        for (id in listOf("tour", "station", "qo100", "datv", "sstv", "ft8")) {
            val v = visites.first { it.getString("id") == id }
            val e = v.getJSONArray("etapes")
            val att = (0 until e.length()).map { e.getJSONObject(it) }.filter { it.has("attention") }
            assertTrue("$id : un encadré « Attention »", att.isNotEmpty())
            assertTrue("$id : Échap ou ARRÊT", att.any { it.getJSONObject("attention").getString("fr").let { t -> "Échap" in t || "ARRÊT" in t } })
        }
        // Seen tours kept in this browser, never failing without storage.
        assertTrue(GuidePagePc.JS.contains("try { const x = localStorage.getItem('satme.guide.'"))
        assertTrue(GuidePagePc.JS.contains("try { localStorage.setItem('satme.guide.'"))
    }

    @Test
    fun les_textes_de_l_aide_existent_dans_les_deux_langues() {
        val js = GuidePagePc.JS + h
        val cles = Regex("GTF?\\('([a-z_]+)'[,)]").findAll(js).map { it.groupValues[1] }.toMutableSet()
        GuidePagePc.ENCADRES.forEach { cles += "gd_$it" }
        listOf("port", "acces", "ouverture", "muet", "modele", "demo", "delai", "autre").forEach { cles += "cx_$it" }
        listOf("port", "acces", "ouverture", "modele", "demo", "delai", "autre").forEach { cles += "cx_${it}_faire" }
        listOf("poste", "rotor").forEach { cles += "cx_titre_$it"; cles += "cx_muet_faire_$it" }
        cles.forEach { assertTrue("pcg_$it (fr)", FR.containsKey("pcg_$it")); assertTrue("pcg_$it (en)", EN.containsKey("pcg_$it")) }
        // The failure window's kinds are the ones cxGenre answers.
        Regex("return '([a-z]+)';").findAll(corps("cxGenre")).forEach { assertTrue(it.groupValues[1], FR.containsKey("pcg_cx_" + it.groupValues[1])) }
    }

    @Test
    fun l_interface_allegee_cache_les_aides_jamais_les_avertissements() {
        assertTrue(GuidePagePc.CSS.contains("body.allegee .aide-texte"))
        val marques = Regex("<(\\w+)([^>]*class=\"[^\"]*\\baide-texte\\b[^\"]*\"[^>]*)>([^<]*)").findAll(h).toList()
        assertTrue("au moins 30 textes marqués : ${marques.size}", marques.size >= 30)
        val interdits = listOf("avert", "etat", "erreur", "e-legal", "e-charge", "pastille", "val", "rouge", "arret")
        marques.forEach { m ->
            val classes = Regex("class=\"([^\"]*)\"").find(m.groupValues[2])!!.groupValues[1].split(' ')
            assertTrue("$classes : ${m.groupValues[3]}", classes.none { it in interdits })
            assertFalse("pas un bouton : ${m.groupValues[3]}", m.groupValues[1] == "button")
            assertFalse("pas un avertissement : ${m.groupValues[3]}", m.groupValues[3].contains("⚠"))
        }
        // The transmit warnings stay as they are.
        listOf("TEST SUR CHARGE", "ESSAI EN INTÉRIEUR").forEach { w ->
            val i = h.indexOf(w); assertTrue(w, i > 0)
            assertFalse(w, h.substring(h.lastIndexOf('<', i), i).contains("aide-texte"))
        }
        // Kept by SatMe-PC; before the answer, as this browser saw it last.
        assertTrue(GuidePagePc.JS.contains("fetch('api/interface?' + quoi"))
        assertTrue(h.contains("{ id: 'aide', nom: 'Aide et affichage'"))
    }

    @Test
    fun le_badge_du_rotor_connecte_au_lieu_d_ouvrir_les_reglages() {
        // Suivi: the rotor's badge as the rig's, its own click (the card's click goes to Réglages).
        val b = Regex("<span id=\"st-rotor-b\" onclick=\"([^\"]*)\"").find(h)
        assertNotNull(b)
        assertTrue(b!!.groupValues[1].contains("event.stopPropagation(); rotPop(this);"))
        assertFalse(b.groupValues[1].contains("vue("))
        // The status bar too.
        assertTrue(h.contains("<button id=\"sb-rot\" class=\"sb-i\" onclick=\"rotPop(this)\""))
        // Its « Connecter » is the one of Réglages: the same request, followed to its end.
        assertTrue(corps("rotPopConnecte").contains("cxConnecte('rotor', rotPopU)"))
        assertTrue(corps("rotPop").contains("rotPopU = on ? '' : rotRequete();"))
        assertTrue(corps("rotConnecte").contains("const u = rotRequete();"))
        assertTrue(corps("rotConnecte").contains("cxConnecte('rotor', u)"))
        // Only the connection: no move, no « Suivre » ticked, no park.
        val req = corps("rotRequete") + corps("rotPop") + corps("rotPopConnecte") + corps("cxConnecte")
        assertTrue(req.contains("api/rotor/connecter?lien="))
        listOf("api/rotor/aller", "api/rotor/suivre", "api/rotor/parquer", "api/rotor/pointer", "api/rotor/astre").forEach { assertFalse(it, req.contains(it)) }
        // The rig's badge: Connecter offered even without a port (the window then says why).
        assertTrue(corps("catPopConnecte").contains("if (!catPopU) { catConnecte(); return; }"))
    }

    @Test
    fun un_echec_ouvre_la_fenetre_et_jamais_une_alerte() {
        assertTrue(h.contains("<dialog id=\"cx-dlg\""))
        listOf("catConnecte", "rotConnecte", "cxConnecte", "cxEchec", "catPopConnecte", "rotPopConnecte").forEach { assertFalse(it, corps(it).contains("alert(")) }
        assertTrue(corps("catConnecte").contains("cxEchec('poste'"))
        assertTrue(corps("rotConnecte").contains("cxEchec('rotor'"))
        assertTrue(corps("cxEchec").contains("g('cx-dlg').showModal()"))
        // « Aller aux réglages »: Réglages › Poste et rotor, the right card.
        assertTrue(corps("cxReglages").contains("montreReglages('materiel')"))
        assertTrue(corps("cxReglages").contains("cxQuoi === 'poste' ? 'cat-modele' : 'rot-lien'"))
        // The server's words sorted: no port, refused, not opened, silent, no model.
        assertTrue(corps("cxConnecte").contains("d.essaiFini >= n"))
    }

    @Test
    fun l_assistant_montre_et_ne_fait_rien() {
        val a = GuidePagePc.JS.substringAfter("the assistant: the next useful thing")
        assertFalse(a.contains("fetch(") || a.contains("post(") || a.contains(".click("))
        assertTrue(GuidePagePc.CARTE.contains("id=\"gd-assistant\""))
    }
}
