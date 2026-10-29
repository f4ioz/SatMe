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

/** The journal's timeline on the page (ChronologieJs), run by node; and the page asking it with the files' own words. */
class ChronologieJsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(essai: String): String {
        val n = node()
        assumeTrue("node absent", n != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-ch-" + System.nanoTime()).apply { mkdirs() }
        try {
            val f = File(d, "essai.js").apply { writeText(ChronologieJs.CODE + "\nconst assert = require('assert');\n" + essai) }
            val p = ProcessBuilder(n, f.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
            return sortie
        } finally { d.deleteRecursively() }
    }

    @Test
    fun la_chronologie_temps_reel_ordre_et_tours() {
        lance("""
            // 8 frames a second: pass 0 at ×4 (500 ms a frame) for 10 frames, a turn of 4 frames, pass 1 at ×1 for 9 frames.
            const k = 'p'.repeat(10) + 't'.repeat(4) + 'p'.repeat(9);
            const t0 = 1000000, dt = [], i = [];
            for (let n = 1; n < 10; n++) dt.push(500);
            const debut1 = 900000000;
            dt.push(debut1 - (t0 + 9 * 500)); dt.push(0, 0, 0);
            dt.push(0); for (let n = 1; n < 9; n++) dt.push(125);
            for (let n = 0; n < 10; n++) i.push(0); for (let n = 0; n < 4; n++) i.push(1); for (let n = 0; n < 9; n++) i.push(1);
            const c = chDecode({ ok: true, fps: 8, debut: 24, fin: 48, secondes: (24 + 23 + 48) / 8, t0: t0, dt: dt, i: i, k: k, pieces: [] });
            assert.strictEqual(c.tt.length, 23);
            // In a pass: between two frames in proportion, its pace ×4.
            let e = chEtat(c, 0.5 / 8);
            assert.strictEqual(e.t, t0 + 250); assert.strictEqual(e.kind, 'p'); assert.strictEqual(e.i, 0); assert.ok(Math.abs(e.allure - 4) < 1e-9);
            // Turning: the time held at the next window's start, the turn's ms still to go.
            e = chEtat(c, 11 / 8);
            assert.strictEqual(e.kind, 't'); assert.strictEqual(e.i, 1); assert.strictEqual(e.t, debut1); assert.ok(Math.abs(e.trans - 375) < 1e-6);
            // The last frame of a pass before the turn: held, not run to the next window.
            e = chEtat(c, 9.5 / 8);
            assert.strictEqual(e.t, t0 + 4500);
            // Pass 1 in real time: its sound heard.
            e = chEtat(c, 16.5 / 8);
            assert.ok(chReel(e.allure)); assert.strictEqual(e.i, 1);
            // The end.
            assert.ok(chEtat(c, 30).fin);
            // Never back in time; real time → video → real time again.
            let avant = -Infinity;
            for (let s = 0; s < 23 / 8; s += 0.01) { const x = chEtat(c, s).t; assert.ok(x >= avant, s); avant = x; }
            for (const s of [0, 0.3, 0.9, 2.2, 2.5]) { const x = chEtat(c, s); if (x.kind !== 't') assert.ok(Math.abs(chVideo(c, x.t) - s) < 1 / 8 + 1e-9, s); }
            assert.strictEqual(chVideo(c, t0 - 5), 0);
            assert.ok(Math.abs(chVideo(c, t0 + 1000) - 2 / 8) < 1e-9);
            assert.strictEqual(chDuree(102), '1 min 42 s'); assert.strictEqual(chDuree(42.4), '42 s'); assert.strictEqual(chDuree(600), '10 min 00 s');
        """)
    }

    @Test
    fun le_son_accelere_dans_l_apercu() {
        lance("""
            // « Son accéléré »: heard in real time, and sped up to ×4 (sonMax); faster, silent; off: real time only.
            const c = { sonAccelere: true, sonMax: 4 };
            assert.ok(chSon(c, 1)); assert.ok(chSon(c, 2.5)); assert.ok(chSon(c, 4)); assert.ok(!chSon(c, 4.5)); assert.ok(!chSon(c, 20));
            assert.ok(chSon({ sonAccelere: false, sonMax: 4 }, 1)); assert.ok(!chSon({ sonAccelere: false, sonMax: 4 }, 2));
            // The audio element at the frames' pace, its pitch kept (each browser's name for it).
            const a = { playbackRate: 1 };
            assert.strictEqual(chAudio(a, 3), 3);
            assert.strictEqual(a.playbackRate, 3); assert.strictEqual(a.preservesPitch, true);
            assert.strictEqual(a.webkitPreservesPitch, true); assert.strictEqual(a.mozPreservesPitch, true);
            chAudio(a, 1.00001); assert.strictEqual(a.playbackRate, 1);
            chAudio(a, 9); assert.strictEqual(a.playbackRate, 4);
        """)
    }

    @Test
    fun l_apercu_demande_ce_que_la_video_demande() {
        lance("""
            const fichier = { quoi: 'video', t: 123, de: 5, a: 9, ids: 'a,b', mode: 'continu', vHors: 20, vPassage: 4, vImage: 3, lecture: 2,
              cam: 'poursuite', accelere: '1', rapide: 10, accAvant: 10, accApres: 30, margeAvant: 30000, margeApres: 30000, transition: 1.6,
              avecSon: '1', ouverture: '1', recap: '1', vue: 'globe', zHors: .9, zPassage: 1.8 };
            const q = chApercu(fichier);
            // The moment shown and the stretch chosen left out (the preview plays everything); every speed, the camera, the cards kept.
            assert.ok(!('t' in q) && !('de' in q) && !('a' in q));
            for (const x of ['ids', 'mode', 'vHors', 'vPassage', 'vImage', 'lecture', 'cam', 'accelere', 'rapide', 'accAvant', 'accApres',
              'margeAvant', 'margeApres', 'transition', 'avecSon', 'ouverture', 'recap', 'vue', 'zHors', 'zPassage']) assert.strictEqual(q[x], fichier[x], x);
            assert.strictEqual(q.quoi, 'video');
            // The same words, the same text whatever their order; one speed changed, another.
            assert.strictEqual(chCle(q), chCle(Object.assign({}, q)));
            assert.notStrictEqual(chCle(q), chCle(Object.assign({}, q, { vPassage: 5 })));
        """)
    }

    @Test
    fun la_page_demande_l_apercu_et_les_fichiers_avec_les_memes_mots() {
        val h = PageJournal.html()
        // One builder of the words: the files (« /fabrique-session ») and the preview's timeline (« /chronologie »).
        assertTrue(h.contains("function parametresRendu(quoi)"))
        assertTrue(h.contains("const q = parametresRendu(quoi);"))
        assertTrue(h.contains("lire('/fabrique-session', q)"))
        assertTrue(h.contains("function chronoParams() { return chApercu(parametresRendu('video')); }"))
        assertTrue(h.contains("lire('/chronologie', q)"))
        // They carry the page's « Vitesse » and the globe's camera.
        assertTrue(h.contains("lecture: vitesse, cam: Orbites.cameraActive()"))
        // The timeline's code is in the page.
        assertTrue(h.contains("function chEtat(c, s)"))
    }
}
