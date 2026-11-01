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

/** « Agenda › Calendrier » (CalendrierJs) in the page, its days counted across the end of summer time, run by node in Paris. */
class CalendrierJsTest {

    @Test fun `le calendrier est dans la page, sous l'agenda, et le formulaire garde l'heure tapee`() {
        val h = PagePc.HTML
        listOf("data-p=\"ag-p-cal\"", "id=\"ag-p-cal\"", "id=\"cal-grille\"", "id=\"cal-cote\"", "api/calendrier?de=", "function calendrierOuvre(",
            "Aujourd’hui", "Annuler ce passage", "Recréer la campagne", "campagne supprimée", "id=\"ap-f-pass\"", "id=\"ap-f-periode\"",
            "api/autopilote/annule?id=", "Supprimer la campagne", "id=\"rdv-form\"", "api/agenda/garde?", "function rdvEdite(").forEach {
            assertTrue(it, h.contains(it))
        }
        // The calendar's pane among the Agenda's.
        assertTrue(h.indexOf("id=\"ag-p-cal\"") in h.indexOf("id=\"v-agenda\"")..h.indexOf("id=\"v-carres\""))
        // Saving no longer puts 00:00 / 23:59 back over the times typed, and deleting asks no browser dialog.
        val garde = h.substring(h.indexOf("async function apGarde()"), h.indexOf("async function apActive("))
        assertFalse(garde.contains("apAconf()"))
        val retire = h.substring(h.indexOf("async function apRetire("), h.indexOf("/** A flyer's proposal"))
        assertFalse(retire.contains("confirm("))
        assertFalse(CalendrierJs.JS.contains("$"))
        assertFalse(CalendrierJs.CSS.contains("f4ioz.fr") || CalendrierJs.JS.contains("f4ioz.fr"))
    }

    @Test fun `mois, semaine et periode a Paris de part et d'autre du 25 octobre`() {
        val node = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        assumeTrue("node absent", node != null)
        val code = """
const assert = require('assert');
const utc = () => false;
function hm(ms) { const d = new Date(ms); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); }
const esc = x => String(x), escA = x => String(x), g = () => null, txt = () => {}, ouverte = () => false;
global.matchMedia = () => ({ matches: false }); global.addEventListener = () => {}; global.setInterval = () => 0;
global.localStorage = { getItem: () => null, setItem: () => {} };
""" + CalendrierJs.JS + """
// October 2026: the grid from Monday 28 Sept, 42 days, each at 00:00 here (25 Oct is 25 h long).
CLD.vue = 'mois'; CLD.ref = new Date(2026, 9, 29);
let p = calPortee();
assert.strictEqual(p.jours.length, 42);
assert.strictEqual(p.jours[0].getDate(), 28); assert.strictEqual(p.jours[0].getMonth(), 8);
assert.ok(p.jours.every(d => d.getHours() === 0 && d.getMinutes() === 0));
const i25 = p.jours.findIndex(d => d.getMonth() === 9 && d.getDate() === 25);
assert.strictEqual(p.jours[i25 + 1].getTime() - p.jours[i25].getTime(), 25 * 3600000);
// The week of the change, Monday 19 → Monday 26.
CLD.vue = 'semaine'; CLD.ref = new Date(2026, 9, 25, 15, 0);
p = calPortee();
assert.strictEqual(p.jours[0].getDate(), 19); assert.strictEqual(p.jours.length, 7);
assert.strictEqual(p.a - p.de, 7 * 86400000 + 3600000);
// The owner's campaign said in full.
assert.strictEqual(calPeriode(Date.UTC(2026, 9, 28, 23, 0), Date.UTC(2026, 10, 1, 22, 59)), 'du jeu. 29/10 00:00 au dim. 01/11 23:59 (heure de Paris)');
assert.strictEqual(calCourt('SAKHACUBE-CHOLBON (RS18S)'), 'RS18S');
assert.strictEqual(calCouleur(67290), calCouleur(67290));
// A pass over at 23:35, the campaign's chip, and a day's items.
CLD.d = { items: [{ sorte: 'passage', source: 'campagne', catnum: 67290, sat: 'SAKHACUBE-CHOLBON (RS18S)', aos: Date.UTC(2026, 10, 1, 22, 25), los: Date.UTC(2026, 10, 1, 22, 35), el: 28, statut: 'FAIT', images: 3, passe: true, nom: 'x', campagne: 'c' }] };
const puce = calPuce(CLD.d.items[0]);
assert.ok(puce.includes('23:25 RS18S 28°') && puce.includes('st-FAIT') && puce.includes('3🖼'), puce);
console.log('ok');
"""
        val f = File.createTempFile("calendrier", ".js")
        try {
            f.writeText(code)
            val pb = ProcessBuilder(node, f.path).redirectErrorStream(true)
            pb.environment()["TZ"] = "Europe/Paris"
            val p = pb.start()
            val sortie = p.inputStream.bufferedReader().readText()
            assertTrue("node trop long", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(sortie, 0, p.exitValue())
            assertTrue(sortie, sortie.contains("ok"))
        } finally { f.delete() }
    }
}
