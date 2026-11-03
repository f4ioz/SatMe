/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SatMe-PC's appearance (ApparencePagePc): the palettes redefine every colour
 * token and keep the transmission red-orange and readable; simple and complete
 * interfaces; the tiles of the Suivi tab and their layouts, checked before
 * being kept; the texts in two languages.
 */
class ApparencePagePcTest {

    private val h = PagePc.HTML

    /** The tokens of palette [nom] (its block in THEMES). */
    private fun palette(nom: String): Map<String, String> {
        val i = ApparencePagePc.THEMES.indexOf("[data-theme=\"$nom\"]")
        assertTrue(nom, i >= 0)
        val bloc = ApparencePagePc.THEMES.substring(i, ApparencePagePc.THEMES.indexOf('}', i))
        return Regex("--([a-z0-9-]+):(#[0-9A-Fa-f]{6})").findAll(bloc).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun luminance(hex: String): Double {
        val c = (0..2).map { Integer.parseInt(hex.substring(1 + 2 * it, 3 + 2 * it), 16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    private fun contraste(a: String, b: String): Double {
        val (x, y) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (x + 0.05) / (y + 0.05)
    }

    private fun teinte(hex: String): Double {
        val (r, g, b) = (0..2).map { Integer.parseInt(hex.substring(1 + 2 * it, 3 + 2 * it), 16) / 255.0 }
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0.0) return 0.0
        val t = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        return if (t < 0) t + 360 else t
    }

    @Test
    fun chaque_palette_redefinit_toutes_les_couleurs() {
        val jetons = Regex("--([a-z0-9-]+):#[0-9A-Fa-f]{6}").findAll(PagePc.STYLE_JETONS).map { it.groupValues[1] }.toSet()
        assertTrue(jetons.containsAll(listOf("s0", "s2", "texte", "rx", "tx", "danger", "warn", "sur-vif", "survol")))
        ApparencePagePc.THEMES_NOMS.filter { it != "auto" }.forEach { nom ->
            val p = palette(nom)
            assertEquals(nom, emptySet<String>(), jetons - p.keys)
            // Readable: the text on the page and on a card.
            assertTrue("$nom texte", contraste(p.getValue("texte"), p.getValue("s0")) >= 7.0)
            assertTrue("$nom texte-2", contraste(p.getValue("texte-2"), p.getValue("s2")) >= 4.5)
        }
        // TellusMe's light theme is the « clair » palette.
        assertTrue(ApparencePagePc.THEMES.contains("[data-theme=\"clair\"], html.clair {"))
    }

    @Test
    fun l_emission_reste_rouge_orange_et_visible_dans_tous_les_themes() {
        ApparencePagePc.THEMES_NOMS.filter { it != "auto" }.forEach { nom ->
            val p = palette(nom)
            listOf("tx", "danger").forEach { k ->
                val t = teinte(p.getValue(k))
                assertTrue("$nom $k rouge-orangé : $t", t <= 25 || t >= 340)
                assertTrue("$nom $k sur la carte", contraste(p.getValue(k), p.getValue("s2")) >= 3.0)
            }
            // The TX light: white on the transmission colour.
            assertTrue("$nom TX lisible", contraste("#FFFFFF", p.getValue("tx")) >= 3.0)
        }
        assertTrue(ApparencePagePc.CSS.contains(".h-tx, #h-tx { background:var(--tx) !important; color:#fff !important;"))
    }

    @Test
    fun les_reglages_ont_leurs_valeurs_et_leurs_defauts() {
        assertEquals("sombre", ApparencePagePc.defaut("theme"))
        assertEquals("arrondi", ApparencePagePc.defaut("forme"))
        assertEquals("normale", ApparencePagePc.defaut("densite"))
        assertEquals("complete", ApparencePagePc.defaut("interface"))
        assertEquals("simple", ApparencePagePc.valeur("interface", "simple"))
        assertNull(ApparencePagePc.valeur("theme", "rose"))
        assertNull(ApparencePagePc.valeur("couleur", "sombre"))
        // Every value the page offers is one the server takes, and the CSS draws.
        ApparencePagePc.FORMES.drop(1).forEach { assertTrue(it, ApparencePagePc.THEMES.contains("[data-forme=\"$it\"]")) }
        listOf("compacte", "aeree").forEach { assertTrue(it, ApparencePagePc.THEMES.contains("[data-densite=\"$it\"]")) }
        Regex("data-v=\"([a-z]+)\"").findAll(ApparencePagePc.CARTE).map { it.groupValues[1] }.forEach { v ->
            assertTrue(v, ApparencePagePc.CHOIX.values.any { v in it })
        }
    }

    @Test
    fun une_disposition_est_verifiee_avant_d_etre_gardee() {
        val bonne = """{"ordre":{"c1":["passages","materiel"],"c3":["frequences"]},"retire":{"ptx":1},"fr":{"c2":1.4},"plie":{"suivants":true},"cache":{}}"""
        assertNotNull(ApparencePagePc.disposition(bonne))
        assertNull(ApparencePagePc.disposition("pas du json"))
        assertNull(ApparencePagePc.disposition("""{"autre":{}}"""))
        assertNull(ApparencePagePc.disposition("""{"ordre":{"c1":["<script>"]}}"""))
        assertNull(ApparencePagePc.disposition("""{"ordre":{"c1":"passages"}}"""))
        assertNull(ApparencePagePc.disposition("{\"fr\":{\"c1\":1}," + "\"h\":{" + (1..2000).joinToString(",") { "\"k$it\":1" } + "}}"))
        assertTrue(ApparencePagePc.CLE_DISPOSITION.matches("pli-station"))
        assertTrue(ApparencePagePc.CLE_DISPOSITION.matches("pli-station.simple"))
        assertFalse(ApparencePagePc.CLE_DISPOSITION.matches("reglages"))
        assertFalse(ApparencePagePc.CLE_DISPOSITION.matches("pli-../x"))
    }

    @Test
    fun les_tuiles_du_suivi_sont_dans_ses_colonnes() {
        val v = h.substring(h.indexOf("<div class=\"vue\" id=\"v-station\">"), h.indexOf("<div class=\"vue\" id=\"v-carnet\""))
        val tuiles = Regex("data-tuile=\"([a-z0-9-]+)\"").findAll(v).map { it.groupValues[1] }.toList()
        assertEquals(tuiles.size, tuiles.toSet().size)
        assertTrue(tuiles.containsAll(listOf("passages", "suivi", "frequences", "materiel", "ecoute", "polar", "ptx", "leo", "noeud", "ipspectre")))
        // The simple interface removes only tiles that exist, and keeps what a pass needs.
        val simple = Regex("retire: \\{([^}]*)\\}").find(h.substringAfter("const PLI_ST = pliTab("))!!.groupValues[1]
        val retirees = Regex("([a-z]+): 1").findAll(simple).map { it.groupValues[1] }.toSet()
        assertTrue(tuiles.containsAll(retirees))
        listOf("passages", "suivi", "frequences", "materiel", "ecoute").forEach { assertFalse(it, it in retirees) }
        // Each tab's menu has its « Disposition par défaut », kept by SatMe-PC.
        listOf("st", "so", "es").forEach { assertTrue(it, h.contains("id=\"$it-pli-menu\"")) }
        assertTrue(h.contains("r.id = P.menu.id + '-defaut'"))
        assertTrue(h.contains("if (typeof apDispo === 'function') apDispo(P.cle, P.d);"))
        assertTrue(h.contains("if (typeof apDispo === 'function') apDispo(P.cle, null);"))
    }

    @Test
    fun simple_et_complete() {
        // The header's switch and the settings' card.
        assertTrue(h.contains("id=\"h-ui\""))
        assertTrue(h.contains("id=\"ap-carte\""))
        assertTrue(h.contains("cartes: ['Apparence', 'Aide et affichage']"))
        // The simple interface: the tabs of the complete one marked, Suivi always there.
        ApparencePagePc.ONGLETS_COMPLETS.filter { it !in setOf("journal", "planche") }.forEach { o ->
            assertTrue(o, h.contains("<button data-v=\"$o\" title=") && Regex("<button data-v=\"$o\" title=\"[^\"]*\" class=\"ui-c\"").containsMatchIn(h))
        }
        assertFalse("station" in ApparencePagePc.ONGLETS_COMPLETS)
        assertTrue(ApparencePagePc.CSS.contains("[data-ui=\"simple\"] .ui-c { display:none !important; }"))
        assertTrue(PagePc.STYLE_CADRE.contains("[data-ui=\"simple\"] #onglets .ui-c"))
        // Drawn before the answer, from this browser's copy, in try/catch; the journal's and the sheet's pages too.
        assertTrue(ApparencePagePc.TETE.startsWith("<script id=\"apparence-tete\">try {"))
        assertTrue(h.indexOf(ApparencePagePc.TETE) in 1 until h.indexOf("<body>"))
        assertTrue(PagePc.SCRIPT_BARRE.contains("localStorage.getItem('satme.apparence')"))
        assertTrue(PagePc.STYLE_BARRE.contains("[data-theme=\"aurore\"]"))
    }

    @Test
    fun les_textes_existent_dans_les_deux_langues() {
        val t = ApparencePagePc.SOURCE.getJSONObject("textes")
        val cles = (Regex("APT\\('([a-z_]+)'").findAll(h).map { it.groupValues[1] } + Regex("data-apt=\"([a-z_]+)\"").findAll(h).map { it.groupValues[1] })
            .filter { it != "ap_th_" }.toMutableSet()
        ApparencePagePc.THEMES_NOMS.forEach { cles += "ap_th_$it" }
        assertTrue(cles.size > 30)
        cles.forEach { k ->
            assertTrue(k, t.has(k))
            assertTrue("$k fr", t.getJSONObject(k).optString("fr").isNotBlank())
            assertTrue("$k en", t.getJSONObject(k).optString("en").isNotBlank())
        }
        assertEquals("Default layout", ApparencePagePc.textes(true).getString("ap_defaut"))
        assertEquals("Disposition par défaut", ApparencePagePc.textes(false).getString("ap_defaut"))
    }

    @Test
    fun la_visite_personnaliser_montre_tuiles_interfaces_et_themes() {
        val a = GuidePagePc.SOURCE.getJSONArray("visites")
        val v = (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "personnaliser" }
        val e = v.getJSONArray("etapes")
        val cibles = (0 until e.length()).map { e.getJSONObject(it).optString("cible") }
        assertTrue(cibles.containsAll(listOf("h-ui", "st-pli-b", "ap-carte")))
        cibles.forEach { assertTrue(it, h.contains("id=\"$it\"")) }
    }

    /**
     * The page's scripts share one global scope: a function declared twice keeps only the last one. The appearance's
     * loader once took the auto-pilot's name (apCharge), so Agenda › « SSTV · auto-pilote » stayed hidden (its zone
     * never shown, its sub-tab then left out) whatever the modules.
     */
    @Test
    fun aucuneFonctionDeLaPageDeclareeDeuxFois() {
        val noms = Regex("(?m)^(?:async )?function ([A-Za-z0-9_\\$]+)\\s*\\(").findAll(h).map { it.groupValues[1] }.toList()
        val doubles = noms.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("fonctions déclarées deux fois : $doubles", doubles.isEmpty())
        val ap = h.substring(h.indexOf("async function apCharge()"))
        assertTrue(ap.substring(0, 200).contains("api/autopilote"))
        assertTrue(h.contains("async function apparenceCharge()"))
    }

    /**
     * « SSTV · auto-pilote » with the station's modules (METEOR, sondes, avions, navires off): not masked; nor with SSTV
     * off while Télémesure (SSDV campaigns) is on; masked only without both.
     */
    @Test
    fun autopiloteSstvPasMasque() {
        val ap = { c: Set<String> -> ModulesStation.masques(c).any { it.contains("ag-p-sstv") } }
        assertFalse(ap(setOf("meteor", "sondes", "avions", "ais")))
        assertFalse(ap(setOf("meteor", "sondes", "avions", "ais", "sstv")))
        assertFalse(ap(setOf("tlm")))
        assertTrue(ap(setOf("sstv", "tlm")))
        assertTrue(h.contains("<button data-p=\"ag-p-sstv\""))
        assertTrue(h.contains(ModulesStation.AUTOPILOTE.removePrefix("#v-agenda .st-nav button[").removeSuffix("]")))
    }
}
