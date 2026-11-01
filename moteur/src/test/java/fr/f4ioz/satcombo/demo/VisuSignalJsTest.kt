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

/** Télémesure › Signal (VisuSignalJs): bytes, alignment on the sync word, the marks in the bit rows, the figures line, run by node. */
class VisuSignalJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(code: String, check: Boolean = false) {
        val n = node()
        assumeTrue("node absent", n != null)
        val f = File.createTempFile("visu", ".js")
        try {
            f.writeText(code)
            val p = ProcessBuilder(listOfNotNull(n, if (check) "--check" else null, f.path)).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
    }

    @Test fun `le code passe node --check et la page l'a`() {
        lance(VisuSignalJs.CODE + VisuSignalJs.PAGE, check = true)
        assertTrue(PagePc.HTML.contains("id=\"tl-p-sig\""))
        assertTrue(PagePc.HTML.contains("function vsBitsHtml("))
        assertTrue(PagePc.HTML.contains("data-p=\"tl-p-sig\""))
    }

    @Test fun `octets, valeurs, calage et marques`() {
        lance(VisuSignalJs.CODE + """
            const assert = require('assert');
            assert.deepStrictEqual(Array.from(vsOctets(Buffer.from([1, 255, 128]).toString('base64'))), [1, 255, 128]);
            assert.strictEqual(vsS(255), -1); assert.strictEqual(vsS(127), 127);
            assert.strictEqual(vsValeur('01111110', true), 0x7E);
            assert.strictEqual(vsValeur('10111111', false), 0xBF);
            // HDLC is LSB first: 00010010 read LSB first is 0x48.
            assert.strictEqual(vsValeur('00010010', true), 0x48);
            assert.strictEqual(vsCalage(100, [[103, 8, '7E']], 8), 111 % 8);
            assert.strictEqual(vsCalage(100, [], 8), 100 % 8);
            const m = vsMarques(20, 100, [[104, 8, '7E']], [110, 6, 'trame']);
            assert.strictEqual(m[3], 0); assert.strictEqual(m[4], 1); assert.strictEqual(m[11], 1); assert.strictEqual(m[12], 2); assert.strictEqual(m[16], 0);
            // A flag then a byte: the row aligned on the flag's end, 7E then 48 in hex, the flag's bits marked.
            const v = { bits: '000' + '01111110' + '00010010' + '01', premier: 5, groupe: 8, lsb: true, reperes: [[8, 8, '7E']], trame: null };
            const h = vsBitsHtml(v, 4);
            assert.ok(h.indexOf('<i class="s">01111110</i>') >= 0, h);
            assert.ok(h.indexOf('7E 48') >= 0, h);
            assert.strictEqual((h.match(/class="vs-l"/g) || []).length, 1);
            assert.strictEqual(vsBitsHtml({ bits: '' }, 4), '');
            // ITA2: groups of five, values in decimal, no text column.
            const r = vsBitsHtml({ bits: '1000011111', premier: 0, groupe: 5, lsb: true, reperes: [] }, 6);
            assert.ok(r.indexOf(' 1 31') >= 0, r); assert.ok(r.indexOf('class="a"') < 0);
        """.trimIndent())
    }

    @Test fun `la ligne des figures et les vues selon le mode`() {
        lance(VisuSignalJs.CODE + """
            const assert = require('assert');
            const b = { debit: 1200, debitMesure: 1200.3, ppm: 250, qualite: { mer: 14.2, evm: 19.5 }, vues: ['CONSTELLATION', 'BITS'] };
            const l = vsInfos(b).map(x => x[0] + ' ' + x[1]);
            assert.deepStrictEqual(l, ['1 200 bit/s nominal', '1 200,3 (+250 ppm) mesuré', '14,2 dB MER', '19,5 % EVM']);
            assert.deepStrictEqual(vsFigures(b), { c: true, o: false, t: false });
            const f = { debit: 9600, unite: 'Hz', qualite: { ouverture: 42, q: 12.5 }, tons: { genre: 'niveaux', bas: -2350, haut: 2410, ecart: 4760 }, vues: ['OEIL', 'TONS', 'BITS'] };
            const lf = vsInfos(f).map(x => x[0]);
            assert.ok(lf.indexOf('−2 350 Hz / 2 410 Hz'.replace('−', '-')) >= 0, lf.join('|'));
            assert.ok(lf.indexOf('4 760 Hz') >= 0);
            assert.deepStrictEqual(vsFigures(f), { c: false, o: true, t: true });
            assert.deepStrictEqual(vsFigures(null), { c: false, o: false, t: false });
            assert.strictEqual(vsPas(4000, 5), 1000); assert.strictEqual(vsPas(3, 5), 0.5);
        """.trimIndent())
    }
}
