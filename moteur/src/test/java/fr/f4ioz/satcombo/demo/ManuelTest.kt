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
import org.junit.Assert.assertTrue
import org.junit.Test

class ManuelTest {

    @Test
    fun les_titres_ont_les_ancres_de_github() {
        assertEquals("linstaller", Markdown.ancre("L'installer"))
        assertEquals("qo-100--le-cockpit", Markdown.ancre("QO-100 : le cockpit"))
        assertEquals("phonie--texte", Markdown.ancre("Phonie → texte"))
        assertEquals("ft8--ft4", Markdown.ancre("FT8 / FT4"))
        assertEquals("les-onglets-dun-coup-dœil", Markdown.ancre("Les onglets d'un coup d'œil"))
        assertEquals("la-fréquence-pour-les-carnets-rigctld", Markdown.ancre("La fréquence pour les carnets (rigctld)"))
        assertEquals("<h2 id=\"qo-100--le-cockpit\">QO-100 : le cockpit</h2>", Markdown.html("## QO-100 : le cockpit"))
    }

    @Test
    fun le_texte_est_echappe_et_mis_en_forme() {
        assertEquals("<p>a &lt;b&gt; &amp; <b>gras</b>, <i>italique</i>, <code>x &lt; 1</code></p>",
            Markdown.html("a <b> & **gras**, *italique*, `x < 1`"))
        assertEquals("<p>pas_un_italique, 2 * 3 * 4</p>", Markdown.html("pas_un_italique, 2 * 3 * 4"))
        assertEquals("<p><b>« Écouter » <i>ici</i></b></p>", Markdown.html("**« Écouter » _ici_**"))
        // Raw HTML (the guide's pictures) is left out.
        assertEquals("<p>avant</p><p>après</p>", Markdown.html("avant\n\n<p align=\"center\"><img src=\"x.webp\"></p>\n\naprès"))
    }

    @Test
    fun les_liens_internes_externes_et_vers_les_autres_fichiers() {
        assertEquals("<p><a href=\"#le-carnet\" data-ancre=\"le-carnet\">Le carnet</a></p>", Markdown.html("[Le carnet](#le-carnet)"))
        assertEquals("<p><a href=\"https://x.org/a?b=1&amp;c=2\" target=\"_blank\" rel=\"noopener\"><b>X</b></a></p>",
            Markdown.html("[**X**](https://x.org/a?b=1&c=2)"))
        assertEquals("<p><a href=\"${Markdown.DEPOT}installer-satme-pc.fr.md\" target=\"_blank\" rel=\"noopener\">Installer</a></p>",
            Markdown.html("[Installer](installer-satme-pc.fr.md)"))
        // Anything else (javascript:, a picture…): its text only.
        assertEquals("<p>piège</p>", Markdown.html("[piège](javascript:alert(1))"))
    }

    @Test
    fun les_listes_imbriquees_et_numerotees() {
        assertEquals("<ul><li>un</li><li>deux<ul><li>sous</li></ul></li><li>trois suite</li></ul><p>fin</p>",
            Markdown.html("- un\n- deux\n  - sous\n- trois\n  suite\n\nfin"))
        assertEquals("<ol><li><b>a</b></li><li>b</li></ol>", Markdown.html("1. **a**\n2. b"))
    }

    @Test
    fun le_code_en_bloc_garde_ses_lignes_meme_dans_une_liste() {
        assertEquals("<pre><code>echo &lt;a&gt;\n  ok</code></pre>", Markdown.html("```\necho <a>\n  ok\n```"))
        assertEquals("<ul><li>avant<pre><code>sudo x</code></pre></li></ul>", Markdown.html("- avant\n\n  ```\n  sudo x\n  ```"))
        // A « ## » inside code is not a title.
        assertEquals(2, Manuel.chapitres("# T\n\nintro\n\n## A\n\n```\n## pas un titre\n```\n\n## B\n\nb").size - 1)
    }

    @Test
    fun les_tableaux() {
        assertEquals("<table><thead><tr><th>Option</th><th style=\"text-align:right\">Effet</th></tr></thead><tbody>" +
            "<tr><td><code>--port 8090</code></td><td style=\"text-align:right\">le port</td></tr>" +
            "<tr><td><code>a|b</code></td><td style=\"text-align:right\">c</td></tr></tbody></table>",
            Markdown.html("| Option | Effet |\n|---|---:|\n| `--port 8090` | le port |\n| `a|b` | c |"))
    }

    private val guide = """
        # Le guide

        *Octobre.* Une **présentation**.

        ## Sommaire

        1. [A](#a)

        ## Le récepteur SDR

        Le récepteur lit le CTCSS : « CTCSS 67,0 Hz ».

        ### Mémoires et plan de bandes

        Les mémoires du récepteur, le plan de bandes.

        ## Réglages

        Les réglages du récepteur et de la station.
    """.trimIndent()

    @Test
    fun le_guide_se_coupe_en_chapitres_sans_le_sommaire() {
        val c = Manuel.chapitres(guide)
        assertEquals(listOf("presentation", "le-récepteur-sdr", "réglages"), c.map { it.id })
        assertEquals("Le guide", c[0].titre)
        assertEquals(listOf("mémoires-et-plan-de-bandes" to "Mémoires et plan de bandes"), c[1].sections)
        assertTrue(c[1].html.contains("<h3 id=\"mémoires-et-plan-de-bandes\">"))
        assertFalse(c[1].texte.contains("#"))
    }

    @Test
    fun la_recherche_compte_classe_et_ignore_les_accents() {
        val c = Manuel.chapitres(guide)
        val r = Manuel.cherche("recepteur", c)
        assertEquals(listOf("le-récepteur-sdr", "réglages"), r.map { it.id })
        assertEquals(3, r[0].nombre)
        assertEquals(1, r[1].nombre)
        // Every word must be there.
        assertEquals(listOf("le-récepteur-sdr"), Manuel.cherche("Récepteur CTCSS", c).map { it.id })
        assertTrue(Manuel.cherche("ctcss", c)[0].extraits[0].contains("CTCSS 67,0 Hz"))
        assertTrue(Manuel.cherche("introuvable", c).isEmpty())
        assertTrue(Manuel.cherche(" a ", c).isEmpty())
        val j = Manuel.chercheJson("récepteur", c)
        assertEquals(4, j.getInt("total"))
        assertEquals("recepteur", j.getJSONArray("mots").getString(0))
    }

    @Test
    fun les_extraits_sont_courts_et_coupes_aux_espaces() {
        val long = "## Long\n\n" + (1..80).joinToString(" ") { "mot$it" } + " cible " + (1..80).joinToString(" ") { "fin$it" }
        val e = Manuel.cherche("cible", Manuel.chapitres(long))[0].extraits[0]
        assertTrue(e, e.startsWith("… mot") && e.endsWith(" …") && e.length < 200 && e.contains("cible"))
    }

    @Test
    fun le_guide_livre_se_lit_et_chaque_onglet_a_son_chapitre() {
        assertNotNull("le guide est dans les ressources", Manuel.SOURCE)
        val c = Manuel.CHAPITRES
        assertTrue(c.size > 10)
        val ancres = c.flatMap { listOf(it.id) + it.sections.map { s -> s.first } }.toSet()
        for ((onglet, a) in Manuel.ONGLETS) assertTrue("$onglet → $a", a in ancres)
        assertTrue(Manuel.PAR_DEFAUT in c.map { it.id })
        assertEquals(Manuel.ONGLETS.size + 1, Manuel.json().getJSONObject("onglets").length())
        // Every internal link of the guide leads to a title of it.
        for (ch in c) Regex("data-ancre=\"([^\"]+)\"").findAll(ch.html).forEach { assertTrue("lien #${it.groupValues[1]} dans ${ch.id}", it.groupValues[1] in ancres) }
        val j = Manuel.json()
        assertTrue(j.getBoolean("ok"))
        assertEquals(Manuel.PAR_DEFAUT, j.getJSONObject("onglets").getString("*"))
        assertTrue(Manuel.cherche("rigctld").isNotEmpty())
    }

    @Test
    fun les_images_et_les_liens_du_site_seulement_s_ils_sont_surs() {
        // The F4IOZ site's articles: a picture of the site or https, a link to another page of the site.
        assertEquals("<p><img src=\"/images/antenne.jpg\" alt=\"Mon antenne\" loading=\"lazy\"></p>", Markdown.html("![Mon **antenne**](/images/antenne.jpg)"))
        assertEquals("<p><a href=\"/articles/2026/essai\">un essai</a></p>", Markdown.html("[un essai](/articles/2026/essai)"))
        // Anything else is left as text: no script, no other scheme, no quote breaking out.
        assertEquals("<p>!x</p>", Markdown.html("![x](javascript:alert(1))"))
        assertFalse(Markdown.html("![x](/a\" onerror=\"alert(1))").contains("onerror=\""))
        assertEquals("<p>x</p>", Markdown.html("[x](//autre.example/page)"))
        assertEquals("<p>x</p>", Markdown.html("[x](data:text/html,abc)"))
    }
}
