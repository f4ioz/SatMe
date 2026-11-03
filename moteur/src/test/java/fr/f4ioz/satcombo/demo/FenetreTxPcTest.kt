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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The TX window (FenetreTxPc): refused while the transmission is off, back to
 * reception when the reception is no longer in sight, STOP first, and no new
 * way to transmit — its rules run by node, its routes read in the page.
 */
class FenetreTxPcTest {

    private val h = PagePc.HTML

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(code: String, check: Boolean = false) {
        val n = node()
        assumeTrue("node absent", n != null)
        val f = File.createTempFile("txf", ".js")
        try {
            f.writeText(code)
            val p = ProcessBuilder(listOfNotNull(n, if (check) "--check" else null, f.path)).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
    }

    /** The window's code with no page (its wiring then does nothing), a stand-in page [m], and [js]. */
    private fun essai(js: String) = lance("const g = () => null;\n" + FenetreTxPc.JS + "\nconst assert = require('assert');\n" + """
        function page(o) {
          const m = Object.assign({ vis: true, v: 'station', tel: false, t: 1000, tx: false, arrets: 0, retours: [],
            p: { ptx: { present: true, pret: true, emet: false, pourquoi: '' } } }, o || {});
          m.visible = () => m.vis; m.vue = () => m.v; m.telephone = () => m.tel; m.panneaux = () => m.p;
          m.emet = () => m.tx; m.retourRx = r => { m.retours.push(r); m.tx = false; }; m.arret = () => { m.arrets++; m.tx = false; };
          m.maintenant = () => m.t;
          return m;
        }
        """ + js)

    /** The body of JS function [nom] in [code]. */
    private fun corps(code: String, nom: String): String {
        val i = code.indexOf("function $nom(")
        assertTrue("function $nom", i >= 0)
        return code.substring(i, code.indexOf("\n}", i))
    }

    @Test fun `le code passe node --check et la page l'embarque`() {
        lance(FenetreTxPc.JS, check = true)
        assertFalse("pas de modèle Kotlin dans le JS", FenetreTxPc.JS.contains("$"))
        listOf("id=\"txf\"", "id=\"h-txf\"", "id=\"txf-stop\"", "id=\"txf-etat\"", "id=\"txf-oeil\"", "function txfRegles(m)").forEach { assertTrue(it, h.contains(it)) }
        // The reception tabs exist, the same in Kotlin and in the script.
        FenetreTxPc.VUES_RX.forEach { assertTrue(it, h.contains("id=\"v-$it\"")) }
        assertTrue(FenetreTxPc.JS.contains("const TXF_VUES_RX = [" + FenetreTxPc.VUES_RX.joinToString(", ") { "'$it'" } + "];"))
        FenetreTxPc.PANNEAUX.forEach { (k, id) -> assertTrue(id, h.contains("id=\"$id\"")); assertTrue(k, FenetreTxPc.JS.contains("{ k: '$k', id: '$id'")) }
        assertTrue(FenetreTxPc.JS.contains("const TXF_VERROU_STOP_MS = ${FenetreTxPc.VERROU_STOP_MS};"))
    }

    @Test fun `la fenetre est refusee si l'emission n'est pas activee`() = essai("""
        // No transmitter: grey, reception only, the window stays closed.
        let m = page({ p: {} }), R = txfRegles(m);
        assert.match(R.ouvre(), /Émission non activée/);
        assert.strictEqual(R.ouverte, false);
        // A rig there but not ready: its reason said.
        m = page({ p: { ptx: { present: true, pret: false, emet: false, pourquoi: 'poste non connecté (Réglages › Poste)' } } }); R = txfRegles(m);
        assert.match(R.ouvre(), /pas prête : poste non connecté/);
        assert.strictEqual(R.ouverte, false);
        // The phone's page: never.
        m = page({ tel: true }); R = txfRegles(m);
        assert.match(R.ouvre(), /téléphone/); assert.strictEqual(R.ouverte, false);
        // Ready, but away from the reception (Réglages): refused too.
        m = page({ v: 'reglages' }); R = txfRegles(m);
        assert.match(R.ouvre(), /page de réception/); assert.strictEqual(R.ouverte, false);
        // Ready, on the Suivi tab: open; its tabs are the ready panels.
        m = page({ p: { ptx: { present: true, pret: true }, btx: { present: true, pret: false, pourquoi: 'x' }, leo: { present: false } } }); R = txfRegles(m);
        assert.deepStrictEqual(R.etat().onglets, ['ptx']);
        assert.strictEqual(R.ouvre(), ''); assert.strictEqual(R.ouverte, true); assert.strictEqual(R.refus(), '');
    """)

    @Test fun `retour en reception quand la page devient invisible ou l'onglet RX quitte`() = essai("""
        const m = page(), R = txfRegles(m);
        assert.strictEqual(R.ouvre(), '');
        // Transmitting, the browser hides the page: back to reception at once, the controls dead.
        m.tx = true; m.vis = false;
        assert.strictEqual(R.surveille(), true);
        assert.deepStrictEqual(m.retours, ['page cachée ou réduite']);
        assert.match(R.refus(), /Page cachée/);
        // Seen again: it may key again, nothing more stopped.
        m.vis = true;
        assert.strictEqual(R.surveille(), false); assert.strictEqual(R.refus(), '');
        // Another tab opened while transmitting: stopped too, and dead while there.
        m.tx = true; m.v = 'carnet';
        assert.strictEqual(R.surveille(), true);
        assert.strictEqual(m.retours[1], 'onglet de réception quitté');
        assert.match(R.refus(), /plus affichée/);
        // Nothing transmitting: nothing sent.
        m.v = 'reglages'; assert.strictEqual(R.surveille(), false); assert.strictEqual(m.retours.length, 2);
        // Back to a reception tab (QO-100): usable again.
        m.v = 'qo100'; assert.strictEqual(R.refus(), '');
        // Closed while transmitting: stopped as well.
        m.tx = true; assert.strictEqual(R.ferme(), true); assert.strictEqual(m.retours[2], 'fenêtre d’émission fermée');
        // Closed, the window watches nothing more (the panels' own rules stay), but a hidden page never keys.
        m.tx = true; m.vis = false; assert.strictEqual(R.surveille(), false); assert.match(R.refus(), /Page cachée/);
    """)

    @Test fun `STOP passe d'abord`() = essai("""
        const m = page(), R = txfRegles(m);
        R.ouvre(); m.tx = true;
        // STOP: the page's « tout arrêter », then nothing keys for a moment.
        R.stop();
        assert.strictEqual(m.arrets, 1); assert.strictEqual(m.tx, false);
        assert.match(R.refus(), /STOP/);
        m.t += TXF_VERROU_STOP_MS - 1; assert.match(R.refus(), /STOP/);
        m.t += 1; assert.strictEqual(R.refus(), '');
        // Even with the reception out of sight, STOP still stops.
        m.vis = false; R.stop(); assert.strictEqual(m.arrets, 2);
        // A failing stop still leaves the window dead for that moment.
        m.vis = true; m.t += 10000; m.arret = () => { throw new Error('réseau'); };
        assert.throws(() => R.stop()); assert.match(R.refus(), /STOP/);
        // Échap: the same moment.
        m.t += 10000; R.verrou(); assert.match(R.refus(), /STOP/);
    """)

    @Test fun `aucune nouvelle route d'emission - la fenetre ne fait que lire et arreter`() {
        val js = FenetreTxPc.JS
        // Its routes: the panels' stop routes only, those « tout arrêter » already calls.
        val routes = Regex("api/[a-z0-9/-]+").findAll(js).map { it.value }.toSet()
        assertEquals(setOf("api/poste/emission/arrete", "api/be/emission/arrete", "api/trafic-sat"), routes)
        assertTrue(js.contains("'api/trafic-sat?arret=1&client='"))
        val arret = corps(h, "arretEmissions")
        listOf("api/poste/emission/arrete", "api/be/emission/arrete", "api/trafic-sat?arret=1").forEach { assertTrue(it, arret.contains(it)) }
        // It starts nothing: no keying route, no panel's start function.
        listOf("demarre", "joue=", "/vie", "pxAppuie(", "bxAppuie(", "leoJoue(", "api/ft8", "api/datv", "api/sstv", "api/cat", "api/rotor", "confirme=").forEach { assertFalse(it, js.contains(it)) }
        assertFalse("pas de clic simulé", js.contains(".click("))
    }

    @Test fun `les panneaux demandent la regle avant d'emettre et restent dans la fenetre`() {
        listOf("pxAppuie", "bxAppuie").forEach { assertTrue(it, corps(h, it).contains("txfRefus()")) }
        assertTrue(corps(TraficLeoJs.CODE, "leoJoue").contains("txfRefus()"))
        // The rule asked first, before the session's confirmation.
        corps(TraficLeoJs.CODE, "leoJoue").let { assertTrue(it.indexOf("txfRefus()") < it.indexOf("txPeutDemarrer()")) }
        assertTrue(corps(h, "pxPlace").contains("p.closest('#txf')"))
        assertTrue(corps(h, "vue").contains("txfSurveille()"))
        // « Trafic satellite » is a tile by its slot, so that its card can leave and come back.
        assertTrue(h.contains("<div id=\"leo-slot\" data-tuile=\"leo\""))
        assertFalse(h.contains("id=\"leo-carte\" hidden data-tuile"))
    }

    @Test fun `la visite guidee de la fenetre dit ses garde-fous`() {
        val v = GuidePagePc.SOURCE.getJSONArray("visites").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("id") == "txf" }
        val e = v.getJSONArray("etapes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(listOf("h-txf", "txf", "txf-etat", "txf-oeil", "txf-stop"), e.map { it.getString("cible") })
        assertTrue(e.any { it.optJSONObject("attention")?.getString("fr")?.contains("Échap") == true })
        assertTrue(e.any { it.optJSONObject("attention")?.getString("fr")?.contains("voix") == true })
    }
}
