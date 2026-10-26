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

/** The DATV « Résumé » (ResumeDatvJs), run by node on French and English overs as Whisper writes them. */
class ResumeDatvJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(essai: String) {
        val n = node()
        assumeTrue("node absent", n != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-rs-" + System.nanoTime()).apply { mkdirs() }
        try {
            val f = File(d, "essai.js").apply { writeText(ResumeDatvJs.CODE + "\nconst assert = require('assert');\n" + essai) }
            val p = ProcessBuilder(n, f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }

    private val passages = """
        const T = 1793000000000;
        const P = [
          { t: T, duree: 18, freq: 10494750000, langue: 'fr', traduction: '', elements: [{ g: 'INDICATIF', v: 'F4IOZ' }, { g: 'CARRE', v: 'JN19' }],
            texte: "CQ CQ DATV ici F4IOZ, Fox quatre India Oscar Zoulou, locator JN19, je transmets en 333 kilosymboles sur 10494,75 MHz." },
          { t: T + 60000, duree: 25, freq: 10494750000, langue: 'fr', traduction: '', elements: [{ g: 'INDICATIF', v: 'F1ABC' }, { g: 'REPORT', v: '59' }],
            texte: "Merci F1ABC pour le rapport, je te reçois cinq sur neuf avec une MER de 8,5 dB et une marge de six décibels. Ici la parabole fait un mètre vingt, l'émetteur est un Pluto avec un ampli de 10 watts." },
          { t: T + 90000, duree: 3, freq: 10494750000, langue: 'fr', traduction: '', elements: [], texte: "Bonjour." },
          { t: T - 3600000, duree: 20, freq: 10499250000, langue: 'en', elements: [{ g: 'INDICATIF', v: 'G4XYZ' }, { g: 'CARRE', v: 'IO91WM' }],
            texte: "This is G4XYZ in IO91wm, receiving you five by nine, MER 9.2 dB on the MiniTiouner with Ryde, symbol rate 1000. My margin is 4 dB.",
            traduction: "Ici G4XYZ en IO91wm, je vous reçois cinq sur neuf, MER 9,2 dB sur le MiniTiouner avec Ryde, débit symbole 1000. Ma marge est de 4 dB." },
        ];
        const ctx = { ciel: 10494750, service: 'F4IOZ-TV', lus: ['F4IOZ'], vus: [{ c: 'G4XYZ', ciel: 10499250, service: 'G4XYZ' }], moi: 'F1ABC' };
    """

    @Test
    fun `faits d un passage francais et anglais`() {
        lance(passages + """
            const f = rsFaits(P[1].texte, P[1].elements);
            assert.deepStrictEqual(f.indicatifs, ['F1ABC']);
            assert.deepStrictEqual(f.reports, ['RS 59', 'MER 8,5 dB', 'marge 6 dB']);
            assert.deepStrictEqual(f.materiel, ['Pluto', 'parabole 1,20 m', 'ampli 10 W']);
            const e = rsFaits(P[3].texte, P[3].elements);
            assert.deepStrictEqual(e.carres, ['IO91wm']);
            assert.deepStrictEqual(e.reports, ['RS 59', 'MER 9,2 dB', 'marge 4 dB']);
            assert.deepStrictEqual(e.materiel, ['MiniTiouner', 'Ryde']);
            assert.deepStrictEqual(e.frequences, ['SR 1000 kS']);
            const a = rsFaits(P[0].texte, P[0].elements);
            assert.deepStrictEqual(a.frequences, ['10494,75 MHz', 'SR 333 kS']);
            assert.deepStrictEqual(a.carres, ['JN19']);
            // Written « 5/9 », a MER without its unit, a 2.4 GHz uplink, a dish in cm and a 25 W amplifier.
            const b = rsFaits('Report 5/9 here, MER 7.5, uplink 2400,25 MHz, 90 cm dish and a 25 W amplifier', []);
            assert.deepStrictEqual(b.reports, ['RS 59', 'MER 7,5 dB']);
            assert.deepStrictEqual(b.frequences, ['2400,25 MHz']);
            assert.deepStrictEqual(b.materiel, ['parabole 90 cm', 'ampli 25 W']);
            // Not callsigns nor squares: QO-100, H264, DVB-S2.
            const c = rsFaits('QO-100 en H264 et DVB-S2, rien d’autre', []);
            assert.deepStrictEqual([c.indicatifs, c.carres], [[], []]);
        """)
    }

    @Test
    fun `stations, la courante d abord, et phrases cles`() {
        lance(passages + """
            const s = rsStations(P, ctx);
            assert.strictEqual(s.length, 2);
            assert.ok(s[0].actuel); assert.strictEqual(s[0].ciel, 10494750); assert.strictEqual(s[0].n, 3);
            // Heard, read in the picture and in the service's name: first; ours last.
            assert.deepStrictEqual(s[0].indicatifs.map(x => x.c), ['F4IOZ', 'F1ABC']);
            assert.deepStrictEqual(s[0].indicatifs[0].ou, ['entendu', 'image', 'service']);
            // The key sentences hold the facts; « Bonjour. » is not one; in the order said.
            assert.ok(s[0].phrases.length >= 2 && s[0].phrases.length <= 5);
            assert.ok(s[0].phrases.every(x => x !== 'Bonjour.'));
            assert.ok(s[0].phrases[0].startsWith('CQ CQ'));
            assert.ok(s[0].phrases.some(x => /MER de 8,5 dB/.test(x)));
            // The station before: in French (its translation), its service from the callsigns read then.
            assert.ok(!s[1].actuel); assert.strictEqual(s[1].service, 'G4XYZ');
            assert.ok(s[1].phrases[0].startsWith('Ici G4XYZ'));
            assert.deepStrictEqual(s[1].indicatifs[0].ou, ['entendu', 'image', 'service']);
            // For the log: the callsign that is not ours, its square and report.
            assert.deepStrictEqual(rsQso(s[0], 'F1ABC'), { call: 'F4IOZ', loc: 'JN19', rep: '59' });
            // From the other side: F1ABC said no square, JN19 was F4IOZ's: not given to F1ABC.
            assert.deepStrictEqual(rsQso(s[0], 'F4IOZ'), { call: 'F1ABC', loc: '', rep: '59' });
            const t = rsTexte(s[0]);
            assert.ok(/^DATV 10494,750 MHz « F4IOZ-TV » · 3 passage/.test(t), t);
            assert.ok(/Reports : RS 59, MER 8,5 dB, marge 6 dB/.test(t), t);
            assert.ok(/Matériel : Pluto, parabole 1,20 m, ampli 10 W/.test(t), t);
            // The same frequency after 15 min of silence: another visit; nothing heard yet, the station still shown.
            const s2 = rsStations(P.concat([{ t: T + 3600000, duree: 5, freq: 10494750000, langue: 'fr', texte: 'Retour de F4IOZ.', traduction: '', elements: [] }]), ctx);
            assert.strictEqual(s2.filter(x => x.ciel === 10494750).length, 2);
            assert.strictEqual(s2[0].n, 1);
            const v = rsStations([], { ciel: 10491500, service: 'F5XYZ', lus: [] });
            assert.strictEqual(v.length, 1); assert.strictEqual(v[0].indicatifs[0].c, 'F5XYZ');
            assert.deepStrictEqual(rsStations([], {}), []);
        """)
    }
}
