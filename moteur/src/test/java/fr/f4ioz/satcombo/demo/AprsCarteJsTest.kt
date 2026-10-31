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

/** The APRS tab's map and list: in the page, and their pure part (symbols, filters, sort) under node. */
class AprsCarteJsTest {

    @Test
    fun la_page_porte_la_carte_aprs() {
        listOf("id=\"aq-carte\"", "id=\"aq-liste\"", "id=\"aq-fiche\"", "function aqDessine(", "api/aprs/stations",
            // What was there stays reachable: the KISS radio, the trophies, the raw frames.
            "id=\"kiss-carte\"", "id=\"tr-carte\"", "id=\"a-liste\"", "id=\"a-etat\"").forEach { assertTrue(it, PagePc.HTML.contains(it)) }
        assertFalse("pas de gabarit Kotlin dans le JS", AprsCarteJs.CODE.contains("$"))
        assertFalse(AprsCarteJs.HTML.contains("$"))
    }

    @Test
    fun la_partie_pure_sous_node() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        assumeTrue("node absent", node != null)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-aprs-carte-" + System.nanoTime()).apply { mkdirs() }
        try {
            val essai = File(d, "essai.js").apply { writeText(AprsCarteJs.PUR + """
const assert = require('assert');
assert.strictEqual(aqPicto('/>'), 'voiture');
assert.strictEqual(aqPicto('\\_'), 'meteo');
assert.strictEqual(aqPicto('I&'), 'losange');
assert.strictEqual(aqPicto('/?'), 'point');
assert.strictEqual(aqPicto(''), 'point');
assert.strictEqual(aqSurcharge('I&'), 'I');
assert.strictEqual(aqSurcharge('c>'), '2');
assert.strictEqual(aqSurcharge('/>'), '');
assert.strictEqual(aqRose(0), 'N'); assert.strictEqual(aqRose(135), 'SE'); assert.strictEqual(aqRose(350), 'N'); assert.strictEqual(aqRose(270), 'O');
const T = 1793000000000, H = 3600000;
const l = [
  { id: 'QF1ABC', call: 'QF1ABC', dernier: T - 10 * 60000, km: 4, genre: 'fixe', vus: { direct: T - 600000 }, com: 'QTH' },
  { id: 'QE4ABC', call: 'QE4ABC', dernier: T - 3 * H, km: 1050, genre: 'fixe', vus: { satellite: T - 3 * H }, com: 'hola' },
  { id: 'QF3NET-5', call: 'QF3NET-5', dernier: T - 20 * 60000, km: 108, genre: 'fixe', vus: { internet: T - 1200000 }, com: '' },
  { id: 'QF8MOB-9', call: 'QF8MOB-9', dernier: T - 30 * 60000, genre: 'mobile', vus: { digi: T - 1800000 }, com: 'En route' },
];
const f = (o) => Object.assign({ periode: 24, rf: 0, sat: 0, genre: '', q: '' }, o);
const ids = (o) => l.filter(s => aqVisible(s, f(o), T)).map(s => s.id).join(',');
assert.strictEqual(ids({}), 'QF1ABC,QE4ABC,QF3NET-5,QF8MOB-9');
assert.strictEqual(ids({ periode: 1 }), 'QF1ABC,QF3NET-5,QF8MOB-9');
assert.strictEqual(ids({ rf: 1 }), 'QF1ABC,QE4ABC,QF8MOB-9');
assert.strictEqual(ids({ sat: 1 }), 'QE4ABC');
assert.strictEqual(ids({ genre: 'mobile' }), 'QF8MOB-9');
assert.strictEqual(ids({ q: 'hola' }), 'QE4ABC');
assert.strictEqual(aqTrie(l.slice(), 'proche').map(s => s.id).join(','), 'QF1ABC,QF3NET-5,QE4ABC,QF8MOB-9');
assert.strictEqual(aqTrie(l.slice(), 'loin').map(s => s.id).join(','), 'QE4ABC,QF3NET-5,QF1ABC,QF8MOB-9');
assert.strictEqual(aqTrie(l.slice(), 'call').map(s => s.id)[0], 'QE4ABC');
assert.strictEqual(aqTrie(l.slice(), 'dernier').map(s => s.id).join(','), 'QF1ABC,QF3NET-5,QF8MOB-9,QE4ABC');
console.log('ok');
""") }
            val p = ProcessBuilder(node, essai.path).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
