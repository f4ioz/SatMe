/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

/**
 * The Icom RC-28 remote encoder, read by the browser through **WebHID**
 * (Chrome, Edge; a secure context: `https`, or `http://localhost`).
 *
 * The device: USB HID, vendor 0x0C26 (Icom), product 0x001E, « Icom RC-28
 * REMOTE ENCODER ». A big knob, three keys (F-1, F-2, TRANSMIT), four LEDs
 * (LINK, F-1, F-2, TRANSMIT). As published by those who traced it (USB
 * captures), no report id; the input report: byte 0 = 0x01, byte 1 = how many
 * steps (it grows with the speed), byte 3 = the direction (0x01 clockwise,
 * 0x02 the other way), byte 5 = the keys, active low (bit 0 TRANSMIT, bit 1
 * F-1, bit 2 F-2; 0x07 all up). The output report: byte 0 = 0x01, byte 1 = the
 * LEDs, active low (bit 0 TRANSMIT, bit 1 F-1, bit 2 F-2, bit 3 LINK). Should
 * the device number its reports after all, the id is taken as that first
 * byte: both ways read the same.
 *
 * **Never a transmission.** TRANSMIT is a key like the others to this code:
 * reported to the page, which may only lock the knob with it. The TRANSMIT LED
 * is never lit (no parameter can light it): nothing here keys a rig.
 *
 * [CODE], with no page in it (tested by node in Rc28JsTest, reusable by
 * another program built on this engine):
 * - rc28Dispo(window): '' or why WebHID cannot be used ('securite', 'navigateur').
 * - rc28Lit(reportId, data): an input report → { crans (signed), tx, f1, f2 (held) }, or null.
 * - rc28Appuis(avant, apres): the keys just pressed (['f1'…]).
 * - rc28Accel(n): the steps summed over one send, a fast spin made bigger (±50 at most).
 * - rc28OctetLeds({ link, f1, f2 }), rc28Sortie(device), rc28Trame(sortie, octet): the LED report.
 * - rc28Lisseur(envoie, ms): sums the steps and sends at most every [ms], one send at a time.
 * - rc28Pilote(hid, r, o): the link (connect, reconnect without a prompt, plug and unplug, LEDs),
 *   one tab at a time when the browser has Web Locks.
 *
 * Input events only: no timer runs while the knob rests. Plain constant: no
 * Kotlin template in here, and the JS uses no template literals.
 */
object Rc28Js {

    const val CODE = """
const RC28_VID = 0x0C26, RC28_PID = 0x001E;
const RC28_FILTRES = [{ vendorId: RC28_VID, productId: RC28_PID }];
function rc28Est(a) { return !!a && a.vendorId === RC28_VID && a.productId === RC28_PID; }
/** Why WebHID cannot be used in [w] ('' : it can): 'securite' (plain http from another machine), 'navigateur' (no WebHID). */
function rc28Dispo(w) {
  if (!w || !w.isSecureContext) return 'securite';
  if (!w.navigator || !w.navigator.hid) return 'navigateur';
  return '';
}
/** The report's bytes as the device sent them: its id first when it numbers its reports. */
function rc28Octets(reportId, data) {
  const d = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data || []);
  if (!reportId) return d;
  const o = new Uint8Array(d.length + 1); o[0] = reportId; o.set(d, 1); return o;
}
/** One input report: the turn (signed steps, clockwise > 0) and the keys held; null when it is not the knob's report. */
function rc28Lit(reportId, data) {
  const b = rc28Octets(reportId, data);
  if (b.length < 6 || b[0] !== 0x01) return null;
  const sens = b[3] === 0x01 ? 1 : b[3] === 0x02 ? -1 : 0, t = b[5];
  return { crans: b[1] && sens ? sens * b[1] : 0, tx: !(t & 1), f1: !(t & 2), f2: !(t & 4) };
}
/** The keys pressed between two reports. */
function rc28Appuis(avant, apres) {
  return ['f1', 'f2', 'tx'].filter(k => apres[k] && !(avant && avant[k]));
}
/** Fast spins take bigger steps: [n] the steps summed over one send (50 ms); ±50 at most. */
function rc28Accel(n) {
  const a = Math.abs(n), k = a <= 3 ? 1 : a <= 8 ? 2 : a <= 16 ? 4 : 8;
  return Math.max(-50, Math.min(50, n * k));
}
/** The LED byte, active low (a cleared bit lights): LINK, F-1, F-2. TRANSMIT's LED stays dark: nothing here transmits. */
function rc28OctetLeds(l) {
  let o = 0x0F;
  if (l && l.link) o &= ~8;
  if (l && l.f1) o &= ~2;
  if (l && l.f2) o &= ~4;
  return o;
}
/** The output report as the device describes it: its id (0: none) and its length in bytes. */
function rc28Sortie(a) {
  for (const c of (a && a.collections) || []) for (const r of c.outputReports || []) {
    let bits = 0; for (const it of r.items || []) bits += (it.reportSize || 0) * (it.reportCount || 0);
    return { id: r.reportId || 0, taille: Math.ceil(bits / 8) };
  }
  return { id: 0, taille: 32 };
}
/** The LED report's data: [0x01, leds] padded with zeros; with a report id, the id is that 0x01 and the data starts at the LEDs. */
function rc28Trame(s, octet) {
  const b = new Uint8Array(Math.max(s.id ? 1 : 2, s.taille || 0));
  if (s.id) b[0] = octet; else { b[0] = 0x01; b[1] = octet; }
  return b;
}
/**
 * Sums the steps and calls [envoie](n) at most every [ms], one call at a time ([envoie] may return a promise):
 * the first step after a rest goes at once, a spin is sent as a few sums. A timer only while steps wait.
 */
function rc28Lisseur(envoie, ms, o) {
  o = o || {};
  const maint = o.horloge || (() => Date.now()), mint = o.minuterie || ((f, d) => setTimeout(f, d));
  const L = { acc: 0, dernier: -1e15, t: null, enCours: false };
  function part() {
    L.t = null;
    if (L.enCours || !L.acc) return;
    const reste = L.dernier + ms - maint();
    if (reste > 0) { L.t = mint(part, reste); return; }
    const n = L.acc; L.acc = 0; L.dernier = maint(); L.enCours = true;
    let p = null; try { p = envoie(n); } catch (x) {}
    const fin = () => { L.enCours = false; if (L.acc && !L.t) part(); };
    if (p && typeof p.then === 'function') p.then(fin, fin); else fin();
  }
  L.pousse = n => { if (!n) return; L.acc += n; if (!L.t && !L.enCours) part(); };
  L.vide = () => { L.acc = 0; };
  return L;
}
/**
 * The RC-28's link. [hid]: navigator.hid (or a stand-in). [r]: the page's side —
 * r.tourne(crans) each turn, r.appui(cle) a key pressed ('f1', 'f2', 'tx'), r.etat(P) the link changed,
 * r.leds() → { link, f1, f2 } (asked at each change). [o].verrous: navigator.locks, so that one tab
 * alone holds the device (the others wait, and take it when it closes); [o].nom: the lock's name.
 * P.etat: 'attente' (another tab has it), 'absent' (no permission given, or unplugged), 'ouvert', 'erreur'.
 */
function rc28Pilote(hid, r, o) {
  o = o || {};
  const P = { app: null, etat: 'absent', message: '', touches: {}, ecrit: -1, maitre: !o.verrous };
  const dit = (e, m) => { P.etat = e; P.message = m || ''; try { r.etat(P); } catch (x) {} };
  function recoit(ev) {
    const l = rc28Lit(ev.reportId, ev.data); if (!l) return;
    if (l.crans) try { r.tourne(l.crans); } catch (x) {}
    const av = P.touches; P.touches = l;
    for (const k of rc28Appuis(av, l)) try { r.appui(k); } catch (x) {}
  }
  P.ouvre = async a => {
    if (!P.maitre || (P.app === a && a.opened)) return;
    try {
      if (!a.opened) await a.open();
      if (P.app && P.app !== a) P.app.removeEventListener('inputreport', recoit);
      if (P.app !== a) a.addEventListener('inputreport', recoit);
      P.app = a; P.touches = {}; P.ecrit = -1;
      dit('ouvert');
      await P.leds();
    } catch (x) { dit('erreur', String((x && x.message) || x)); }
  };
  /** The devices this page may open already (no prompt): the first RC-28 found. */
  P.reprend = async () => {
    if (!P.maitre) return false;
    let l = [];
    try { l = await hid.getDevices(); } catch (x) {}
    const a = (l || []).find(rc28Est);
    if (a) await P.ouvre(a); else if (!P.app) dit('absent');
    return !!P.app;
  };
  /** The browser's chooser, filtered on the RC-28 (from a click). */
  P.connecte = async () => {
    const l = await hid.requestDevice({ filters: RC28_FILTRES });
    if (l && l[0]) { if (P.maitre) await P.ouvre(l[0]); else dit('attente'); }
    return !!P.app;
  };
  /** The LEDs written, only when they change. */
  P.leds = async () => {
    const a = P.app; if (!a || !a.opened) return;
    const octet = rc28OctetLeds(r.leds ? r.leds() : { link: true });
    if (octet === P.ecrit) return;
    P.ecrit = octet;
    const s = rc28Sortie(a);
    try { await a.sendReport(s.id, rc28Trame(s, octet)); } catch (x) { P.ecrit = -1; }
  };
  /** Closed (LEDs off first); [oublie]: the permission given back too, it will be asked again. */
  P.ferme = async oublie => {
    const a = P.app; P.app = null; P.touches = {};
    if (a) {
      a.removeEventListener('inputreport', recoit);
      try { const s = rc28Sortie(a); if (a.opened) await a.sendReport(s.id, rc28Trame(s, 0x0F)); } catch (x) {}
      try { if (a.opened) await a.close(); } catch (x) {}
      if (oublie && a.forget) try { await a.forget(); } catch (x) {}
    }
    P.ecrit = -1; dit('absent');
  };
  hid.addEventListener('connect', ev => { if (rc28Est(ev.device) && !P.app) P.ouvre(ev.device); });
  hid.addEventListener('disconnect', ev => {
    if (ev.device !== P.app) return;
    P.app.removeEventListener('inputreport', recoit);
    P.app = null; P.touches = {}; P.ecrit = -1; dit('absent', 'débranché');
  });
  /** Started: at once without Web Locks; else once this tab holds the lock (held until the page closes). */
  P.demarre = () => {
    if (!o.verrous) return P.reprend();
    dit('attente');
    return new Promise(pret => {
      o.verrous.request(o.nom || 'rc28', { ifAvailable: true }, verrou => {
        if (!verrou) {
          // Another tab has it: wait in line, without blocking this one.
          o.verrous.request(o.nom || 'rc28', () => { P.maitre = true; P.reprend(); return new Promise(() => {}); });
          pret(false); return null;
        }
        P.maitre = true; P.reprend().then(pret);
        return new Promise(() => {});
      });
    });
  };
  return P;
}
"""
}
