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

/** The Icom RC-28 through WebHID (Rc28Js): synthetic reports and a stand-in device, run by node. */
class Rc28JsTest {

    private fun node(): String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: runCatching { ProcessBuilder("which", "node").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun lance(code: String, check: Boolean = false) {
        val n = node()
        assumeTrue("node absent", n != null)
        val f = File.createTempFile("rc28", ".js")
        try {
            f.writeText(code)
            val p = ProcessBuilder(listOfNotNull(n, if (check) "--check" else null, f.path)).redirectErrorStream(true).start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
        } finally { f.delete() }
    }

    /** A stand-in for navigator.hid and an RC-28, and an async test body. */
    private fun essai(js: String) = lance(Rc28Js.CODE + "\nconst assert = require('assert');\n" + """
        function faux(o) {
          o = o || {};
          const a = new EventTarget();
          Object.assign(a, { vendorId: 0x0C26, productId: 0x001E, productName: 'Icom RC-28 REMOTE ENCODER', opened: false, envoyes: [], oublie: false,
            collections: o.collections || [{ outputReports: [{ reportId: 0, items: [{ reportSize: 8, reportCount: 32 }] }] }],
            open: async () => { a.opened = true; }, close: async () => { a.opened = false; },
            forget: async () => { a.oublie = true; },
            sendReport: async (id, d) => { a.envoyes.push([id, Array.from(d)]); } });
          return a;
        }
        function hidFaux(liste) {
          const h = new EventTarget();
          h.liste = liste; h.demandes = [];
          h.getDevices = async () => h.liste.slice();
          h.requestDevice = async o => { h.demandes.push(o); return h.liste.slice(0, 1); };
          return h;
        }
        function rapport(a, id, octets) { const e = new Event('inputreport'); e.reportId = id; e.data = new DataView(new Uint8Array(octets).buffer); a.dispatchEvent(e); }
        function branche(h, a, type) { const e = new Event(type); e.device = a; h.dispatchEvent(e); }
        const pause = () => new Promise(r => setTimeout(r, 0));
        (async () => {
        """ + js + """
        })().catch(e => { console.error(e); process.exit(1); });
        """)

    @Test fun `le code passe node --check, et la page l'embarque`() {
        lance(Rc28Js.CODE, check = true)
        assertFalse("pas de modèle Kotlin dans le JS", Rc28Js.CODE.contains("$"))
        assertTrue(PagePc.HTML.contains("function rc28Pilote(hid, r, o)"))
        assertTrue(PagePc.HTML.contains("id=\"rc-carte\""))
        assertTrue(PagePc.HTML.contains("id=\"h-rc28\""))
    }

    @Test fun `un rapport - le sens, le nombre de crans, les touches actives a l'etat bas`() {
        essai("""
            // Clockwise, 3 steps, all keys up (0x07).
            assert.deepStrictEqual(rc28Lit(0, new Uint8Array([1, 3, 0, 1, 0, 7, 0, 0])), { crans: 3, tx: false, f1: false, f2: false });
            // The other way, 5 steps.
            assert.strictEqual(rc28Lit(0, new Uint8Array([1, 5, 0, 2, 0, 7])).crans, -5);
            // At rest, or no direction: no turn.
            assert.strictEqual(rc28Lit(0, new Uint8Array([1, 0, 0, 1, 0, 7])).crans, 0);
            assert.strictEqual(rc28Lit(0, new Uint8Array([1, 4, 0, 0, 0, 7])).crans, 0);
            // Keys, active low: 0x06 TRANSMIT, 0x05 F-1, 0x03 F-2, 0x01 F-1 and F-2.
            assert.deepStrictEqual(rc28Lit(0, [1, 0, 0, 0, 0, 6]), { crans: 0, tx: true, f1: false, f2: false });
            assert.deepStrictEqual(rc28Lit(0, [1, 0, 0, 0, 0, 5]), { crans: 0, tx: false, f1: true, f2: false });
            assert.deepStrictEqual(rc28Lit(0, [1, 0, 0, 0, 0, 3]), { crans: 0, tx: false, f1: false, f2: true });
            assert.deepStrictEqual(rc28Lit(0, [1, 0, 0, 0, 0, 1]), { crans: 0, tx: false, f1: true, f2: true });
            // Numbered reports: the id is the first byte, the same reading.
            assert.deepStrictEqual(rc28Lit(1, new DataView(new Uint8Array([2, 0, 2, 0, 7]).buffer)), { crans: -2, tx: false, f1: false, f2: false });
            // Not the knob's report (another type, too short).
            assert.strictEqual(rc28Lit(0, [2, 1, 0, 1, 0, 7]), null);
            assert.strictEqual(rc28Lit(0, [1, 1, 0]), null);
            assert.strictEqual(rc28Lit(0, null), null);
            // The keys pressed, on their edge only.
            assert.deepStrictEqual(rc28Appuis({}, { f1: true, f2: false, tx: true }), ['f1', 'tx']);
            assert.deepStrictEqual(rc28Appuis({ f1: true }, { f1: true }), []);
            assert.deepStrictEqual(rc28Appuis(null, { f2: true }), ['f2']);
        """)
    }

    @Test fun `acceleration, LEDs a l'etat bas, TRANSMIT jamais allumee`() {
        essai("""
            assert.strictEqual(rc28Accel(1), 1); assert.strictEqual(rc28Accel(-3), -3);
            assert.strictEqual(rc28Accel(5), 10); assert.strictEqual(rc28Accel(-12), -48);
            assert.strictEqual(rc28Accel(40), 50); assert.strictEqual(rc28Accel(-40), -50);
            assert.strictEqual(rc28OctetLeds({}), 0x0F);
            assert.strictEqual(rc28OctetLeds({ link: true }), 0x07);
            assert.strictEqual(rc28OctetLeds({ link: true, f1: true }), 0x05);
            assert.strictEqual(rc28OctetLeds({ link: true, f2: true }), 0x03);
            // Asked for, the TRANSMIT LED stays dark (bit 0 always set).
            assert.strictEqual(rc28OctetLeds({ link: true, tx: true }) & 1, 1);
            // The report as described: no id, 32 bytes; or an id, the data after it.
            const s = rc28Sortie({ collections: [{ outputReports: [{ reportId: 0, items: [{ reportSize: 8, reportCount: 32 }] }] }] });
            assert.deepStrictEqual(s, { id: 0, taille: 32 });
            const t = rc28Trame(s, 0x07); assert.strictEqual(t.length, 32); assert.strictEqual(t[0], 1); assert.strictEqual(t[1], 7);
            const s1 = rc28Sortie({ collections: [{ outputReports: [{ reportId: 1, items: [{ reportSize: 8, reportCount: 2 }] }] }] });
            assert.deepStrictEqual(Array.from(rc28Trame(s1, 0x05)), [5, 0]);
            assert.deepStrictEqual(rc28Sortie({ collections: [] }), { id: 0, taille: 32 });
            assert.strictEqual(rc28Trame({ id: 0, taille: 0 }, 0x0F).length, 2);
        """)
    }

    @Test fun `le lisseur - le premier cran tout de suite, puis des sommes, un envoi a la fois`() {
        essai("""
            let t = 0; const minut = []; const envois = []; let fini = null;
            const L = rc28Lisseur(n => { envois.push(n); return new Promise(r => { fini = r; }); }, 50,
              { horloge: () => t, minuterie: (f, d) => { minut.push([f, d]); return minut.length; } });
            L.pousse(1);
            assert.deepStrictEqual(envois, [1]);
            // While it is sent: summed, no timer.
            L.pousse(2); L.pousse(3);
            assert.deepStrictEqual(envois, [1]); assert.strictEqual(minut.length, 0);
            t = 20; fini(); await pause();
            // Done before 50 ms: one timer for the rest.
            assert.strictEqual(minut.length, 1); assert.strictEqual(minut[0][1], 30);
            L.pousse(-1);
            assert.strictEqual(minut.length, 1);
            t = 50; minut[0][0]();
            assert.deepStrictEqual(envois, [1, 4]);
            t = 60; fini(); await pause();
            // Nothing left: no timer.
            assert.strictEqual(minut.length, 1);
            // Emptied (the knob locked): nothing sent.
            t = 200; L.vide(); L.pousse(0);
            assert.deepStrictEqual(envois, [1, 4]);
            // A send that throws does not stop the next ones.
            const M = rc28Lisseur(n => { envois.push(n); throw new Error('x'); }, 50, { horloge: () => t, minuterie: () => 0 });
            M.pousse(2); t = 300; M.pousse(3);
            assert.deepStrictEqual(envois, [1, 4, 2, 3]);
        """)
    }

    @Test fun `le pilote - reprise sans invite, tours et touches, LEDs, debranche puis rebranche`() {
        essai("""
            const a = faux(), h = hidFaux([a]), tours = [], appuis = [], etats = [];
            let leds = { link: true };
            const P = rc28Pilote(h, { tourne: n => tours.push(n), appui: k => appuis.push(k), etat: p => etats.push(p.etat), leds: () => leds });
            assert.ok(await P.demarre());
            assert.ok(a.opened); assert.strictEqual(P.etat, 'ouvert');
            assert.strictEqual(h.demandes.length, 0, 'no chooser at start');
            // LINK lit at once.
            assert.deepStrictEqual(a.envoyes.length, 1); assert.strictEqual(a.envoyes[0][0], 0); assert.strictEqual(a.envoyes[0][1][0], 1); assert.strictEqual(a.envoyes[0][1][1], 7);
            await P.leds(); assert.strictEqual(a.envoyes.length, 1, 'unchanged: not written again');
            rapport(a, 0, [1, 2, 0, 1, 0, 7]); rapport(a, 0, [1, 1, 0, 2, 0, 7]);
            assert.deepStrictEqual(tours, [2, -1]);
            rapport(a, 0, [1, 0, 0, 0, 0, 5]); rapport(a, 0, [1, 0, 0, 0, 0, 5]); rapport(a, 0, [1, 0, 0, 0, 0, 7]);
            rapport(a, 0, [1, 0, 0, 0, 0, 6]); rapport(a, 0, [1, 0, 0, 0, 0, 3]);
            assert.deepStrictEqual(appuis, ['f1', 'tx', 'f2']);
            // TRANSMIT is reported like any key: nothing else is reachable from here.
            leds = { link: false, f1: true }; await P.leds();
            assert.strictEqual(a.envoyes[1][1][1], 0x0D);
            // Unplugged: absent; plugged again (permission kept): reopened, LEDs written again.
            branche(h, a, 'disconnect'); a.opened = false;
            assert.strictEqual(P.etat, 'absent'); assert.strictEqual(P.message, 'débranché');
            rapport(a, 0, [1, 9, 0, 1, 0, 7]); assert.deepStrictEqual(tours, [2, -1], 'nothing heard once unplugged');
            leds = { link: true };
            branche(h, a, 'connect'); await pause(); await pause();
            assert.strictEqual(P.etat, 'ouvert'); assert.ok(a.opened);
            assert.strictEqual(a.envoyes[a.envoyes.length - 1][1][1], 7);
            // Another device plugged: not ours.
            const b = faux(); b.productId = 0x1234; branche(h, b, 'connect'); await pause(); assert.ok(!b.opened);
            // Forgotten: LEDs off, closed, permission given back.
            await P.ferme(true);
            assert.ok(!a.opened); assert.ok(a.oublie); assert.strictEqual(P.etat, 'absent');
            assert.strictEqual(a.envoyes[a.envoyes.length - 1][1][1], 0x0F);
            // Never a TRANSMIT LED written, whatever happened.
            for (const e of a.envoyes) assert.strictEqual(e[1][1] & 1, 1);
        """)
    }

    @Test fun `le pilote - le choix du navigateur filtre sur le RC-28, rien sans permission`() {
        essai("""
            const h = hidFaux([]), etats = [];
            const P = rc28Pilote(h, { tourne() {}, appui() {}, etat: p => etats.push(p.etat) });
            assert.ok(!(await P.demarre())); assert.strictEqual(P.etat, 'absent');
            const a = faux(); h.liste = [a];
            assert.ok(await P.connecte());
            assert.deepStrictEqual(h.demandes[0], { filters: [{ vendorId: 0x0C26, productId: 0x001E }] });
            assert.strictEqual(P.etat, 'ouvert');
        """)
    }

    @Test fun `un seul onglet tient le RC-28 - les autres attendent leur tour`() {
        essai("""
            // A stand-in for Web Locks: one holder, a queue.
            let tient = null; const file = [];
            const verrous = { request(nom, o, f) {
              if (typeof o === 'function') { f = o; o = {}; }
              if (!tient) { tient = f; f({ name: nom }); return; }
              if (o.ifAvailable) { f(null); return; }
              file.push(f);
            } };
            const libere = () => { tient = null; const f = file.shift(); if (f) { tient = f; f({}); } };
            const a = faux(), h = hidFaux([a]);
            const r = () => ({ tourne() {}, appui() {}, etat() {} });
            const P1 = rc28Pilote(h, r(), { verrous, nom: 'satme-rc28' });
            assert.ok(await P1.demarre()); assert.strictEqual(P1.etat, 'ouvert');
            const b = faux(), h2 = hidFaux([b]);
            const P2 = rc28Pilote(h2, r(), { verrous, nom: 'satme-rc28' });
            assert.ok(!(await P2.demarre())); assert.strictEqual(P2.etat, 'attente'); assert.ok(!b.opened);
            // Its chooser gives the permission, but the device stays with the first tab.
            await P2.connecte(); assert.ok(!b.opened); assert.strictEqual(P2.etat, 'attente');
            // The first tab closes: the second one takes it, without a prompt.
            libere(); await pause(); await pause();
            assert.ok(b.opened); assert.strictEqual(P2.etat, 'ouvert');
        """)
    }
}
