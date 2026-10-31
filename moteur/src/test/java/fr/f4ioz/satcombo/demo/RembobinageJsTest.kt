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

/** The rewindable waterfall's history (RembobinageJs): the ring, the quantizing, the time tape, run by node. */
class RembobinageJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(code: String, check: Boolean = false) {
        val n = node()
        assumeTrue("node absent", n != null)
        val f = File.createTempFile("rembobinage", ".js")
        try {
            f.writeText(code)
            val p = ProcessBuilder(listOfNotNull(n, if (check) "--check" else null, f.path)).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
    }

    private fun essai(js: String) = lance(RembobinageJs.CODE + "\nconst assert = require('assert');\n" + js)

    @Test fun `le code passe node --check, et il est dans la page`() {
        lance(RembobinageJs.CODE, check = true)
        lance(RembobinageJs.CODE + RembobinageJs.PAGE, check = true)
        assertTrue(PagePc.HTML.contains("function rbVue(o)"))
        assertTrue(PagePc.HTML.contains("rbRecoit('qo100'"))
        assertTrue(PagePc.HTML.contains("rbRecoit('sdr'"))
        assertTrue(PagePc.HTML.contains("rbRecoit('datv'"))
        assertTrue(PagePc.HTML.contains(".rb-b {"))
    }

    @Test fun `une ligne entre, quantifiee sur un octet, et revient a peu pres`() {
        essai("""
            const h = rbNeuf();
            assert.ok(rbPousse(h, 1000, 100, 200, [-120, -100, null, -80]));
            assert.strictEqual(rbTaille(h), 1);
            const r = rbLigne(h, 0);
            assert.ok(r.q instanceof Uint8Array);
            assert.ok(Math.abs(rbValeur(r, 0) - -120) < 0.2);
            assert.ok(Math.abs(rbValeur(r, 1) - -100) < 0.2);
            assert.strictEqual(rbValeur(r, 2), null);
            assert.ok(Math.abs(rbValeur(r, 3) - -80) < 0.2);
            // Too soon after the last: not kept (one row every 250 ms at most).
            assert.ok(!rbPousse(h, 1100, 100, 200, [1, 2]));
            // Nothing but holes: not a row.
            assert.ok(!rbPousse(h, 2000, 100, 200, [null, null]));
            assert.strictEqual(rbTaille(h), 1);
        """)
    }

    @Test fun `trop de colonnes - regroupees, le plus fort garde`() {
        essai("""
            const h = rbNeuf({ binsMax: 4 });
            const v = new Array(16).fill(-100); v[5] = -40;
            rbPousse(h, 0, 0, 16, v);
            const r = rbLigne(h, 0);
            assert.strictEqual(r.q.length, 4);
            assert.ok(Math.abs(rbValeur(r, 1) - -40) < 0.3);
            assert.ok(Math.abs(rbValeur(r, 0) - -100) < 0.3);
            assert.strictEqual(rbColonne(r, 5.5), 1);
            assert.strictEqual(rbColonne(r, 17), -1);
            assert.strictEqual(rbColonne(r, -1), -1);
        """)
    }

    @Test fun `l'anneau garde la duree et oublie le reste`() {
        essai("""
            const h = rbNeuf({ dureeMs: 10000, pasMs: 1000 });
            for (let t = 0; t <= 30000; t += 1000) rbPousse(h, t, 0, 1, [t]);
            // Ten seconds back from the newest, both ends.
            assert.strictEqual(rbLigne(h, 0).t, 30000);
            assert.strictEqual(rbLigne(h, rbTaille(h) - 1).t, 20000);
            assert.strictEqual(rbTaille(h), 11);
            assert.strictEqual(rbLigne(h, rbTaille(h)), null);
            // Slow rows: the duration still bounds it.
            const s = rbNeuf({ dureeMs: 10000, pasMs: 100 });
            [0, 4000, 8000, 12000, 16000].forEach(t => rbPousse(s, t, 0, 1, [1]));
            assert.strictEqual(rbTaille(s), 3);
        """)
    }

    @Test fun `ancre dans le temps - la vue ne glisse pas quand des lignes arrivent`() {
        essai("""
            const h = rbNeuf({ pasMs: 1000 });
            for (let t = 0; t <= 10000; t += 1000) rbPousse(h, t, 0, 1, [t]);
            assert.strictEqual(rbIndex(h, 7000), 3);
            assert.strictEqual(rbIndex(h, 7500), 3);
            assert.strictEqual(rbIndex(h, 99999), 0);
            assert.strictEqual(rbIndex(h, -5), 10);
            rbPousse(h, 11000, 0, 1, [11000]); rbPousse(h, 12000, 0, 1, [12000]);
            // Two rows later the same moment is two rows further back.
            assert.strictEqual(rbIndex(h, 7000), 5);
            assert.strictEqual(rbLigne(h, rbIndex(h, 7000)).t, 7000);
            assert.strictEqual(rbIndex(rbNeuf(), 0), -1);
        """)
    }

    @Test fun `la bande du temps et l'echelle`() {
        essai("""
            assert.strictEqual(rbRecul(0), '−0 s');
            assert.strictEqual(rbRecul(45000), '−45 s');
            assert.strictEqual(rbRecul(135000), '−2 min 15 s');
            assert.strictEqual(rbRecul(65000), '−1 min 05 s');
            assert.strictEqual(rbRecul(600000), '−10 min');
            assert.strictEqual(rbHeure(Date.UTC(2026, 9, 31, 14, 3, 21)), '14:03:21 UTC');
            const h = rbNeuf({ pasMs: 1 });
            rbPousse(h, 1, 0, 1, [-110, -105]); rbPousse(h, 2, 0, 1, [-100, -50]);
            const e = rbEchelle(h, 0, 1, 20);
            assert.ok(Math.abs(e[0] - -110) < 0.01 && Math.abs(e[1] - -50) < 0.01);
            // Plain noise: at least the minimum span, or it paints as a busy band.
            const n = rbNeuf(); rbPousse(n, 0, 0, 1, [-100, -99]);
            const f = rbEchelle(n, 0, 0, 20);
            assert.ok(Math.abs(f[1] - f[0] - 20) < 0.01);
        """)
    }
}
