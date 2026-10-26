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

/** The DATV subtitles' cues (SousTitresJs), run by node: two lines at most, nothing lost, their pace. */
class SousTitresJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** [essai] run after the page's code; its output (it fails on its first false assertion). */
    private fun lance(essai: String): String {
        val n = node()
        assumeTrue("node absent", n != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-st-" + System.nanoTime()).apply { mkdirs() }
        try {
            val f = File(d, "essai.js").apply {
                writeText(SousTitresJs.CODE + "\nconst assert = require('assert');\n" + essai)
            }
            val p = ProcessBuilder(n, f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
            return sortie
        } finally { d.deleteRecursively() }
    }

    private val fr = "Bonjour à tous, ici F4IOZ depuis Rouen, je vous reçois très bien sur QO-100 avec une marge de six décibels. " +
        "Merci pour le rapport, la parabole fait un mètre vingt et l'émetteur est un Pluto avec un amplificateur de dix watts. Soixante-treize et à bientôt !"
    private val en = "Hello everyone, this is F4IOZ from Rouen, I receive you very well on QO-100 with a margin of six decibels. " +
        "Thanks for the report, the dish is one meter twenty and the transmitter is a Pluto with a ten watt amplifier. Seventy-three and see you soon!"

    @Test
    fun `deux lignes au plus et rien de perdu`() {
        lance("""
            const fr = ${org.json.JSONObject.quote(fr)}, en = ${org.json.JSONObject.quote(en)};
            const mots = t => t.replace(/\s+/g, ' ').trim();
            for (const [o, t] of [[fr, en], [fr, ''], [fr + ' ' + fr + ' ' + fr, en + ' ' + en]]) {
              const c = stCues(o, t, 42, false);
              assert.ok(c.length >= 2);
              // Every word kept, in order, in the original and in the translation.
              assert.strictEqual(c.map(x => x.o.join(' ')).join(' '), mots(o));
              if (t) assert.strictEqual(c.map(x => x.t.join(' ')).join(' '), mots(t));
              for (const x of c) {
                assert.ok(x.o.length <= 2, JSON.stringify(x));
                x.o.forEach(l => assert.ok(l.length <= 42, l));
                if (t) { assert.ok(x.t.length <= 2, JSON.stringify(x)); x.t.forEach(l => assert.ok(l.length <= 42, l)); }
                assert.ok(x.ms >= 1500 && x.ms <= 7000);
              }
            }
            // A sentence ends a cue: none starts in the middle of « Merci ».
            const c = stCues(fr, en, 42, false);
            assert.ok(c.some(x => x.o[0].startsWith('Merci')), JSON.stringify(c));
            // The translation alone, in place of the original.
            const s = stCues(fr, en, 42, true);
            assert.strictEqual(s.map(x => x.o.join(' ')).join(' '), mots(en));
            assert.ok(s.every(x => x.t === null));
            // Translation alone asked but none came: the original.
            assert.strictEqual(stCues('Hola', '', 42, true)[0].o[0], 'Hola');
            // A word longer than a line keeps a line of its own (the page wraps it: never cut).
            assert.deepStrictEqual(stLignes('x'.repeat(50) + ' court', 42), ['x'.repeat(50), 'court']);
        """)
    }

    @Test
    fun `deux lignes equilibrees, coupees apres la ponctuation`() {
        lance("""
            const l = stLignes('je vous reçois très bien sur QO-100 avec une marge de six décibels.', 42);
            assert.strictEqual(l.length, 2);
            assert.ok(Math.abs(l[0].length - l[1].length) <= 12, JSON.stringify(l));
            // The comma a little off the middle wins over the exact middle.
            assert.deepStrictEqual(stLignes('Merci pour le rapport, la parabole fait un mètre vingt', 42),
              ['Merci pour le rapport,', 'la parabole fait un mètre vingt']);
            assert.deepStrictEqual(stLignes('court', 42), ['court']);
            assert.deepStrictEqual(stLignes('  ', 42), []);
            // No punctuation at all: cut by words, two lines each.
            const sans = 'et alors on a parlé de la météo et du vent et des antennes et des câbles et de tout le reste sans jamais mettre un seul point';
            const c = stDecoupe(sans, 42);
            assert.ok(c.length >= 2);
            c.forEach(x => assert.ok(stLignes(x, 42).length <= 2, x));
        """)
    }

    @Test
    fun `chaque sous-titre a son temps puis le plus recent passe devant`() {
        lance("""
            const f = new FileSousTitres({ tenue: 5000, retardMax: 10000 });
            const a = stCues('Une phrase courte. Puis une deuxième phrase un peu plus longue que la première, pour voir.', '', 42, false);
            f.ajoute(a, 0, 0);
            assert.strictEqual(f.actuel(0).o.join(' '), a[0].o.join(' '));
            // The next cue when the first one's time is over; the last stays a while, then none.
            assert.strictEqual(f.actuel(a[0].ms + 1).o.join(' '), a[1].o.join(' '));
            const fin = a.reduce((x, c) => x + c.ms, 0);
            assert.ok(f.actuel(fin + 4000) !== null);
            assert.strictEqual(f.actuel(fin + 5001), null);
            // A long over follows a short one: its cues one after the other.
            const g2 = new FileSousTitres({ tenue: 5000, retardMax: 10000 });
            g2.ajoute(stCues('Premier passage.', '', 42, false), 0, 0);
            g2.ajoute(stCues('Second passage.', '', 42, false), 100, 0);
            assert.strictEqual(g2.actuel(200).o[0], 'Premier passage.');
            assert.strictEqual(g2.actuel(2200).o[0], 'Second passage.');
            // Late (more than 10 s waiting): what was not shown yet of the older over is dropped, the latest comes next.
            const h = new FileSousTitres({ tenue: 5000, retardMax: 10000 });
            const long = stCues('Un. ' + 'Phrase numéro deux qui dure. '.repeat(8), '', 42, false);
            h.ajoute(long, 0, 0);
            h.ajoute(stCues('Le plus récent.', '', 42, false), 500, 0);
            assert.strictEqual(h.actuel(600).o[0], long[0].o[0]);
            assert.strictEqual(h.actuel(long[0].ms + 1).o[0], 'Le plus récent.');
            // Paced on the speech: a 4 s over of 3 cues does not take 15 s (1.5 s a cue at least).
            const r = new FileSousTitres();
            const c3 = stCues('Bonjour à tous les amis de la télévision. Je vous reçois cinq sur cinq ici. À bientôt sur le satellite.', '', 42, false);
            r.ajoute(c3, 0, 4000);
            const total = r.q[r.q.length - 1].fin;
            assert.ok(total <= Math.max(4000, c3.length * 1500) + 5, total);
        """)
    }
}
