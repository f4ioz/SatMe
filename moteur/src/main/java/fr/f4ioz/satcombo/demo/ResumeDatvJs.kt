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
 * The DATV « Résumé »: what was said on each station received, read at a
 * glance — on this machine, without Internet and without a language model.
 * The overs written out (the phonie's, their callsigns, squares and reports
 * already found by AnalysePhonie) are read again for what a contact needs:
 * who (heard, read in the picture, the service's name), the reports (MER,
 * margin, « 5/9 », dB), the squares, the gear named, the frequencies and
 * symbol rates, and a few key sentences (a plain score: the facts they
 * hold, the words of a contact, their length), in the user's language when
 * the over was translated. Pure (no DOM): run in the page and under node
 * in ResumeDatvJsTest.
 */
object ResumeDatvJs {

    /**
     * rsFaits(texte): the facts of one text {indicatifs, reports, carres, materiel, frequences}.
     * rsPhrases(passages, k): the [k] key sentences, oldest first.
     * rsStations(passages, ctx): the stations, the current one first, each summed up:
     *   {ciel, service, debut, fin, n, indicatifs: [{c, ou: ['entendu'|'image'|'service'], n}],
     *    reports, carres, materiel, frequences, phrases}; ctx: {ciel (kHz now), service, lus: [callsigns read now],
     *    vus: [{c, ciel, service}] (read before), moi: our callsign}.
     * rsTexte(station): the summary as plain text (« Copier »).
     * rsQso(station, moi): {call, loc, rep} for the log (the callsign first in the list that is not ours, its square when known).
     */
    const val CODE = """
const RS_NOMBRES = { zero: 0, 'zéro': 0, un: 1, une: 1, one: 1, deux: 2, two: 2, trois: 3, three: 3, quatre: 4, four: 4, cinq: 5, five: 5, six: 6,
  sept: 7, seven: 7, huit: 8, eight: 8, neuf: 9, nine: 9, dix: 10, ten: 10, onze: 11, eleven: 11, douze: 12, twelve: 12, treize: 13, thirteen: 13,
  quatorze: 14, fourteen: 14, quinze: 15, fifteen: 15, seize: 16, sixteen: 16, vingt: 20, twenty: 20, trente: 30, thirty: 30 };
const RS_NB = '(\\d+(?:[.,]\\d+)?|' + Object.keys(RS_NOMBRES).join('|') + ')';
function rsNombre(x) {
  const k = String(x).toLowerCase();
  if (k in RS_NOMBRES) return String(RS_NOMBRES[k]);
  return k.replace('.', ',');
}
// The gear a DATV station names (the word as found, a size or power next to it when there is one).
const RS_MATERIEL = [
  [/\b(?:ADALM[- ]?)?Pluto\b/i, 'Pluto'], [/\bMini ?Tiouner\b|\bMinitioune\b/i, 'MiniTiouner'], [/\bLong ?Mynd\b/i, 'LongMynd'],
  [/\bRyde\b/i, 'Ryde'], [/\bPortsdown\b/i, 'Portsdown'], [/\bHack ?RF\b/i, 'HackRF'], [/\bLime ?SDR\b/i, 'LimeSDR'], [/\bOBS\b/, 'OBS'],
  [/\bRaspberry\b/i, 'Raspberry Pi'], [/\bLNB\b/i, 'LNB'], [/\bPOTY\b/i, 'POTY'], [/\bh[ée]lice\b|\bhelix\b/i, 'hélice'], [/\bpatch\b/i, 'patch'],
  [/\bH\.?\s?26[45]\b|\bHEVC\b/i, null], [/\bcam[ée]ra\b/i, 'caméra'], [/\bDVB-?S2?\b/i, null],
  [/\b(?:parabole|antenne parabolique|dish|offset)\b/i, 'parabole', /(\d+(?:[.,]\d+)?)\s*(?:m\b|cm\b|mètres?|meters?|metres?)|un mètre (vingt|dix|cinquante)|one meter (twenty|ten|fifty)/i],
  [/\b(?:ampli(?:ficateur)?|amplifier|amp|PA)\b/i, 'ampli', /(\d+(?:[.,]\d+)?)\s*(?:W\b|watts?)|(\w+) watts?/i],
];
// The words of a contact, which make a sentence worth keeping.
const RS_MOTS = /\b(merci|thanks?|thank you|rapport|report|re[çc]ois|receive|copy|signal|image|picture|son|sound|audio|antenne|antenna|parabole|dish|watts?|ampli|amplifier|marge|margin|MER|locator|locat(?:eur|or)|QTH|carr[ée]|square|QSL|73|cinq sur|5\/9|five by)\b/gi;
const RS_FORMULES = /^(?:bonjour|hello|hi|salut|cq|ok|okay|oui|yes|non|no|voilà|bon|alors|well|so|73|au revoir|bye|à bientôt|see you)[\s,.!?…]*$/i;
const RS_INDICATIF = /\b(?:[A-Z]{1,2}|[0-9][A-Z]|[A-Z][0-9])[0-9][A-Z]{1,4}(?:\/[A-Z0-9]{1,4})?\b/g;
const RS_LOCATOR = /\b[A-R]{2}[0-9]{2}(?:[a-xA-X]{2})?\b/g;
function rsUnique(l) { const v = new Set(); return l.filter(x => { const k = String(x).toUpperCase(); if (v.has(k)) return false; v.add(k); return true; }); }
function rsCarre(x) { x = String(x); return x.slice(0, 4).toUpperCase() + x.slice(4, 6).toLowerCase(); }
function rsFaits(texte, elements) {
  const t = String(texte || '');
  const r = { indicatifs: [], reports: [], carres: [], materiel: [], frequences: [] };
  for (const e of elements || []) {
    if (e.g === 'INDICATIF') r.indicatifs.push(e.v);
    else if (e.g === 'CARRE') r.carres.push(rsCarre(e.v));
    else if (e.g === 'REPORT') r.reports.push('RS ' + e.v);
  }
  for (const m of t.matchAll(RS_INDICATIF)) if (!/^(?:QO|DVB|MPEG|H)\d/.test(m[0])) r.indicatifs.push(m[0]);
  for (const m of t.matchAll(RS_LOCATOR)) if (!/^(?:QO|DB|MB|KS)/i.test(m[0])) r.carres.push(rsCarre(m[0]));
  // Reports: « 5/9 », « cinq sur neuf », « five by nine », MER and margins in dB.
  for (const m of t.matchAll(new RegExp('\\b' + RS_NB + '\\s*(?:\\/|sur|by|par)\\s*' + RS_NB + '\\b', 'gi'))) {
    const a = rsNombre(m[1]), b = rsNombre(m[2]);
    if (/^[1-5]$/.test(a) && /^[1-9]$/.test(b)) r.reports.push('RS ' + a + b);
  }
  for (const m of t.matchAll(new RegExp(RS_NB + '\\s*(?:dB|décibels?|decibels?|db)\\b', 'gi'))) {
    const avant = t.slice(Math.max(0, m.index - 30), m.index);
    const v = rsNombre(m[1]) + ' dB';
    // Named by the word nearest before it: « MER », « marge » / « margin » (or « D » as on the MiniTiouner's screen).
    const iM = avant.search(/\bMER\b(?!.*\bMER\b)/i), iG = Math.max(avant.toLowerCase().lastIndexOf('marge'), avant.toLowerCase().lastIndexOf('margin'), /\bD\s*$/.test(avant) ? 99 : -1);
    r.reports.push(iM < 0 && iG < 0 ? v : iM > iG ? 'MER ' + v : 'marge ' + v);
  }
  for (const m of t.matchAll(new RegExp('\\bMER\\b[^0-9.]{0,14}\\b' + RS_NB + '(?!\\d|[.,]\\d|\\s*(?:dB|décibel|decibel))', 'gi'))) r.reports.push('MER ' + rsNombre(m[1]) + ' dB');
  for (const [re, nom, taille] of RS_MATERIEL) {
    const m = t.match(re);
    if (!m) continue;
    let x = nom || m[0].toUpperCase().replace(/\s+/g, '');
    if (taille) {
      const z = t.slice(Math.max(0, m.index - 30), m.index + m[0].length + 40).match(taille);
      if (z) x += ' ' + (z[1] ? rsNombre(z[1]) + (/\s*(?:W\b|watts?)/i.test(z[0]) ? ' W' : /cm/i.test(z[0]) ? ' cm' : ' m') :
        z[2] ? ({ vingt: '1,20', twenty: '1,20', dix: '1,10', ten: '1,10', cinquante: '1,50', fifty: '1,50' }[z[2].toLowerCase()] || '') + ' m' :
        z[3] ? (RS_NOMBRES[z[3].toLowerCase()] != null ? RS_NOMBRES[z[3].toLowerCase()] + ' W' : '') : '');
    }
    r.materiel.push(x.trim());
  }
  for (const m of t.matchAll(/\b(\d{1,5}(?:[.,]\d{1,4})?)\s*(MHz|GHz|kHz)\b/gi)) r.frequences.push(m[1].replace('.', ',') + ' ' + { mhz: 'MHz', ghz: 'GHz', khz: 'kHz' }[m[2].toLowerCase()]);
  for (const m of t.matchAll(/\b(?:SR|symbol rate|débit symbole|rapidité)\s*(?:de\s*|of\s*)?(\d{2,4})\b|\b(\d{2,4})\s*(?:ks(?:\/s)?|kS|kilo ?symbol(?:es|s)?|ksps)\b/gi)) r.frequences.push('SR ' + (m[1] || m[2]) + ' kS');
  for (const k of Object.keys(r)) r[k] = rsUnique(r[k]);
  return r;
}
/** The text of a passage in the reader's language: its translation when there is one. */
function rsLu(p) { return String((p.traduction && p.traduction.trim()) || p.texte || '').replace(/\s+/g, ' ').trim(); }
function rsPhrases(passages, k) {
  const tout = [];
  passages.forEach((p, i) => {
    const s = rsLu(p).match(/[^.!?…]+(?:[.!?…]+)?/g) || [];
    s.forEach((x, j) => {
      x = x.trim();
      if (x.length < 12 || RS_FORMULES.test(x)) return;
      const f = rsFaits(x, []);
      let note = 3 * f.indicatifs.length + 3 * f.carres.length + 3 * f.reports.length + 2 * f.materiel.length + 2 * f.frequences.length;
      note += Math.min(3, (x.match(RS_MOTS) || []).length);
      note += x.length >= 30 && x.length <= 160 ? 1 : x.length > 220 ? -2 : 0;
      tout.push({ x: x, note: note, ordre: i * 1000 + j, t: p.t });
    });
  });
  const vus = new Set();
  return tout.sort((a, b) => b.note - a.note || b.ordre - a.ordre).filter(e => {
    const c = e.x.toLowerCase().replace(/[^a-z0-9à-ÿ]+/g, ' ').trim();
    if (vus.has(c) || e.note <= 0) return false; vus.add(c); return true;
  }).slice(0, k || 5).sort((a, b) => a.ordre - b.ordre).map(e => e.x);
}
function rsCompte(l, x, ou) {
  const k = String(x).toUpperCase(), e = l.find(y => y.c === k);
  if (e) { e.n++; if (e.ou.indexOf(ou) < 0) e.ou.push(ou); } else l.push({ c: k, ou: [ou], n: 1 });
}
function rsStations(passages, ctx) {
  ctx = ctx || {};
  const groupes = [];
  // By the frequency received; the same one again after 15 min of silence: another visit.
  for (const p of passages.slice().sort((a, b) => a.t - b.t)) {
    const ciel = p.freq ? Math.round(p.freq / 1000) : (ctx.ciel || 0);
    let gr = groupes.find(x => x.ciel === ciel && p.t - x.fin < 15 * 60000);
    if (!gr) { gr = { ciel: ciel, debut: p.t, fin: p.t, passages: [] }; groupes.push(gr); }
    gr.passages.push(p); gr.fin = Math.max(gr.fin, p.t + (p.duree || 0) * 1000);
  }
  const actuel = ctx.ciel ? groupes.filter(x => Math.abs(x.ciel - ctx.ciel) <= 50).sort((a, b) => b.fin - a.fin)[0] : null;
  if (ctx.ciel && !actuel && ((ctx.lus || []).length || ctx.service)) groupes.push({ ciel: ctx.ciel, debut: Date.now(), fin: Date.now(), passages: [], vide: true });
  const moi = String(ctx.moi || '').toUpperCase();
  return groupes.map(gr => {
    const ici = Math.abs(gr.ciel - (ctx.ciel || -1e9)) <= 50 && (gr === actuel || gr.vide);
    const r = { ciel: gr.ciel, actuel: ici, service: ici ? (ctx.service || '') : '', debut: gr.debut, fin: gr.fin, n: gr.passages.length,
      indicatifs: [], reports: [], carres: [], materiel: [], frequences: [], phrases: rsPhrases(gr.passages, 5) };
    for (const p of gr.passages) {
      const f = rsFaits(p.texte, p.elements);
      if (p.traduction) { const g2 = rsFaits(p.traduction, []); for (const k of ['reports', 'materiel', 'frequences', 'carres']) f[k] = rsUnique(f[k].concat(g2[k])); }
      f.indicatifs.forEach(c => rsCompte(r.indicatifs, c, 'entendu'));
      // A square said in the same over as one callsign alone: taken as that station's.
      if (f.indicatifs.length === 1 && f.carres.length) { const e = r.indicatifs.find(x => x.c === String(f.indicatifs[0]).toUpperCase()); if (e && !e.carre) e.carre = f.carres[0]; }
      for (const k of ['reports', 'carres', 'materiel', 'frequences']) r[k] = rsUnique(r[k].concat(f[k]));
    }
    if (ici) (ctx.lus || []).forEach(c => rsCompte(r.indicatifs, c, 'image'));
    (ctx.vus || []).filter(a => Math.abs((a.ciel || 0) - gr.ciel) <= 50).forEach(a => { rsCompte(r.indicatifs, a.c, 'image'); if (!r.service && a.service) r.service = a.service; });
    // The service's name (the station's own, sent in the stream) when it holds a callsign.
    for (const m of String(r.service).toUpperCase().matchAll(RS_INDICATIF)) rsCompte(r.indicatifs, m[0], 'service');
    // A MER of 0 or a margin of 200 dB is a word misheard, not a report.
    r.reports = r.reports.filter(x => { const v = parseFloat(String(x).replace(/^\D+/, '').replace(',', '.')); return !/dB/.test(x) || (v > 0 && v < 60); });
    r.indicatifs.sort((a, b) => (b.c === moi ? -1 : 0) - (a.c === moi ? -1 : 0) || b.ou.length - a.ou.length || b.n - a.n);
    return r;
  }).sort((a, b) => (b.actuel ? 1 : 0) - (a.actuel ? 1 : 0) || b.fin - a.fin);
}
function rsQso(s, moi) {
  moi = String(moi || '').toUpperCase();
  const i = s.indicatifs.find(x => x.c !== moi && x.c.split('/').indexOf(moi) < 0);
  const rs = s.reports.find(x => /^RS /.test(x));
  // Its square when it said one, else (the only other callsign) a square heard that no other callsign said.
  const libre = s.carres.filter(c => !s.indicatifs.some(x => x !== i && x.carre === c));
  const loc = i && i.carre ? i.carre : i && s.indicatifs.filter(x => x.c !== moi).length === 1 ? libre[0] || '' : '';
  return { call: i ? i.c : '', loc: loc, rep: rs ? rs.slice(3) : '' };
}
function rsTexte(s) {
  const mhz = s.ciel ? (s.ciel / 1000).toFixed(3).replace('.', ',') + ' MHz' : '';
  const l = ['DATV ' + [mhz, s.service ? '« ' + s.service + ' »' : ''].filter(x => x).join(' ') + ' · ' + s.n + ' passage(s)'];
  if (s.indicatifs.length) l.push('Indicatifs : ' + s.indicatifs.map(x => x.c + ' (' + x.ou.join(', ') + ')').join(', '));
  if (s.reports.length) l.push('Reports : ' + s.reports.join(', '));
  if (s.carres.length) l.push('Locators : ' + s.carres.join(', '));
  if (s.materiel.length) l.push('Matériel : ' + s.materiel.join(', '));
  if (s.frequences.length) l.push('Fréquences : ' + s.frequences.join(', '));
  if (s.phrases.length) { l.push('Phrases clés :'); s.phrases.forEach(x => l.push('- ' + x)); }
  return l.join('\n');
}
"""
}
