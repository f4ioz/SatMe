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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** « Qualité » of the DATV picture (QualiteDatvJs): the choice, Auto going down and up with its hysteresis, the measures, run by node. */
class QualiteDatvJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(essai: String, check: Boolean = false) {
        val n = node()
        assumeTrue("node absent", n != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-qd-" + System.nanoTime()).apply { mkdirs() }
        try {
            val f = File(d, "essai.js").apply { writeText(QualiteDatvJs.CODE + "\nconst assert = require('assert');\n" + essai) }
            val p = ProcessBuilder(listOfNotNull(n, if (check) "--check" else null, f.path)).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }

    @Test fun `le code de la page passe node --check`() {
        lance("", check = true)
        val n = node(); assumeTrue("node absent", n != null)
        val f = File.createTempFile("qualite-page", ".js")
        try {
            f.writeText(QualiteDatvJs.PAGE)
            val p = ProcessBuilder(n, "--check", f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS)); assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
        // In the page, beside the fluid playing.
        assertTrue(PagePc.HTML.contains("function dQualMesure()")); assertTrue(PagePc.HTML.contains("id=\"d-qualite\""))
    }

    @Test fun `le choix - lu, sinon Source, et le niveau demande`() {
        lance("""
            assert.strictEqual(qdvChoix('480'), '480');
            assert.strictEqual(qdvChoix('auto'), 'auto');
            assert.strictEqual(qdvChoix(null), 'source');
            assert.strictEqual(qdvChoix('1080'), 'source');
            const a = qdvAutoNeuf(0, '480');
            assert.strictEqual(qdvVoulu('auto', a), '480');
            assert.strictEqual(qdvVoulu('720', a), '720');
            assert.strictEqual(qdvAutoNeuf(0, 'n’importe').niveau, 'source');
            assert.strictEqual(qdvTexte('auto', a), 'Auto · 480p');
            assert.strictEqual(qdvTexte('source', a), 'Source');
            assert.strictEqual(qdvNom('360'), '360p');
        """)
    }

    @Test fun `ce qui est un probleme`() {
        lance("""
            const ok = { images: 50, perdues: 0, attentes: 0, sauts: 0, retard: 0.4, retardAvant: 0.4, longues: 0 };
            assert.ok(!qdvMauvais(ok));
            assert.ok(!qdvMauvais(null));
            // A couple of pictures dropped: not yet.
            assert.ok(!qdvMauvais(Object.assign({}, ok, { perdues: 2 })));
            assert.ok(!qdvMauvais(Object.assign({}, ok, { perdues: 3, images: 100 })));
            assert.ok(qdvMauvais(Object.assign({}, ok, { perdues: 6 })));
            assert.ok(qdvMauvais(Object.assign({}, ok, { attentes: 1 })));
            assert.ok(qdvMauvais(Object.assign({}, ok, { sauts: 1 })));
            // The lag: past 1.2 s and growing only.
            assert.ok(qdvMauvais(Object.assign({}, ok, { retard: 1.6, retardAvant: 1.0 })));
            assert.ok(!qdvMauvais(Object.assign({}, ok, { retard: 1.3, retardAvant: 1.25 })));
            assert.ok(!qdvMauvais(Object.assign({}, ok, { retard: 1.0, retardAvant: 0.2 })));
            assert.ok(qdvMauvais(Object.assign({}, ok, { longues: 3 })));
            assert.ok(!qdvMauvais(Object.assign({}, ok, { longues: 2 })));
        """)
    }

    @Test fun `Auto descend apres deux fenetres mauvaises, remonte apres le calme, attend plus apres une rechute`() {
        lance("""
            const bon = { images: 50, perdues: 0, attentes: 0, sauts: 0 }, mauvais = { images: 50, perdues: 10, attentes: 0, sauts: 0 };
            const a = qdvAutoNeuf(0, 'source');
            // Just started: nothing judged.
            assert.strictEqual(qdvAuto(a, mauvais, 2000), 'source');
            assert.strictEqual(qdvAuto(a, mauvais, 4000), 'source');
            // One bad window: not yet; two in a row: one level down.
            assert.strictEqual(qdvAuto(a, mauvais, 6000), 'source');
            assert.strictEqual(qdvAuto(a, bon, 8000), 'source');
            assert.strictEqual(qdvAuto(a, mauvais, 10000), 'source');
            assert.strictEqual(qdvAuto(a, mauvais, 12000), '720');
            // The stream starting again: not judged for 5 s.
            assert.strictEqual(qdvAuto(a, mauvais, 14000), '720');
            assert.strictEqual(qdvAuto(a, mauvais, 18000), '720');
            assert.strictEqual(qdvAuto(a, mauvais, 20000), '480');
            // Calm: up after 45 s without trouble, not before.
            let t = 20000;
            while (t < 20000 + 44000) { t += 2000; assert.strictEqual(qdvAuto(a, bon, t), '480', 't=' + t); }
            t += 2000; assert.strictEqual(qdvAuto(a, bon, t), '720');
            const montee = t;
            // Fallen again soon after: down, and the calm doubled (90 s).
            t += 6000; qdvAuto(a, mauvais, t); t += 2000;
            assert.strictEqual(qdvAuto(a, mauvais, t), '480');
            assert.ok(t - montee < QDV_RECHUTE);
            assert.strictEqual(a.calmeMs, 90000);
            const chute = t;
            while (t < chute + 88000) { t += 2000; assert.strictEqual(qdvAuto(a, bon, t), '480', 't=' + t); }
            t += 2000; assert.strictEqual(qdvAuto(a, bon, t), '720');
            // That rise holds: the next one waits less again (back towards 45 s).
            const t2 = t;
            while (t < t2 + 44000) { t += 2000; qdvAuto(a, bon, t); }
            assert.strictEqual(a.niveau, '720');
            while (a.niveau === '720') { t += 2000; qdvAuto(a, bon, t); }
            assert.strictEqual(a.niveau, 'source');
            assert.strictEqual(a.calmeMs, 45000);
            // Never above Source, never below 360p.
            for (let k = 0; k < 20; k++) { t += 60000; qdvAuto(a, bon, t); }
            assert.strictEqual(a.niveau, 'source');
            for (let k = 0; k < 40; k++) { t += 6000; qdvAuto(a, mauvais, t); }
            assert.strictEqual(a.niveau, '360');
            assert.ok(a.calmeMs <= QDV_CALME_MAX);
        """)
    }

    @Test fun `les mesures - debit et images perdues, un autre flux repart de zero`() {
        lance("""
            const p = { flux: 'f1', t: 10000, octets: 1000000, images: 100, perdues: 2, attentes: 0, sauts: 0 };
            const c = { flux: 'f1', t: 12000, octets: 1500000, images: 150, perdues: 5, attentes: 1, sauts: 0 };
            const m = qdvDelta(p, c);
            assert.strictEqual(m.kbps, 2000);
            assert.deepStrictEqual([m.images, m.perdues, m.attentes, m.sauts], [50, 3, 1, 0]);
            // Another stream: counted from its start.
            const n = qdvDelta(p, { flux: 'f2', t: 12000, debut: 11000, octets: 250000, images: 25, perdues: 0, attentes: 0, sauts: 0 });
            assert.strictEqual(n.kbps, 2000); assert.strictEqual(n.images, 25);
            // No earlier reading nor start: no rate.
            assert.strictEqual(qdvDelta(null, c).kbps, null);
            assert.strictEqual(qdvTexteMesure(1850, 3), '1 850 kbit/s · 3 images perdues');
            assert.strictEqual(qdvTexteMesure(640, 1), '640 kbit/s · 1 image perdue');
            assert.strictEqual(qdvTexteMesure(640, 0), '640 kbit/s · aucune image perdue');
            assert.strictEqual(qdvTexteMesure(null, 0), '');
        """)
    }
}
