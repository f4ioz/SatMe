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
 * The DATV subtitles' pure part (no DOM): an over cut into cues of two
 * lines at most, the way television subtitles are (by sentence, then by
 * clause, then by words, the two lines balanced), each shown for a time
 * that grows with its length, one after the other. The same text runs in
 * the page and under node in SousTitresJsTest.
 */
object SousTitresJs {

    /**
     * stLignes(t, n): [t] in lines of [n] signs at most, two balanced lines
     * when it fits in two (the cut after punctuation when one is near).
     * stDecoupe(t, n): [t] in cues of two lines at most.
     * stEnN(t, k, n): [t] in exactly [k] pieces (the translation cut as its original).
     * stDuree(t): how long a cue stays, in ms (about 15 signs a second, 1.5 to 7 s).
     * stCues(texte, traduction, n, seule): the over's cues, {o: lines, t: lines or null, ms}.
     * class FileSousTitres: the cues one after the other, the latest over first when late.
     */
    const val CODE = """
const ST_CAR = 42;
function stMots(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
function stLignes(t, n) {
  t = stMots(t); n = n || ST_CAR;
  if (!t) return [];
  if (t.length <= n) return [t];
  const mots = t.split(' ');
  if (t.length <= 2 * n + 1) {
    let mieux = -1, note = Infinity, pos = 0;
    for (let i = 0; i < mots.length - 1; i++) {
      pos += mots[i].length + (i ? 1 : 0);
      const a = pos, b = t.length - pos - 1;
      if (a > n || b > n) continue;
      let s = Math.abs(a - b);
      if (/[,;:.!?…]$/.test(mots[i])) s -= 10;
      if (s < note) { note = s; mieux = i; }
    }
    if (mieux >= 0) return [mots.slice(0, mieux + 1).join(' '), mots.slice(mieux + 1).join(' ')];
  }
  const l = []; let c = '';
  for (const m of mots) { if (c && c.length + 1 + m.length > n) { l.push(c); c = m; } else c = c ? c + ' ' + m : m; }
  if (c) l.push(c);
  return l;
}
function stPhrases(t) {
  const r = stMots(t).match(/[^.!?…]+(?:[.!?…]+["»”)]*)?/g);
  return (r || []).map(x => x.trim()).filter(x => x);
}
function stCoupe(t, k) {
  t = stMots(t);
  if (k <= 1 || !t) return t ? [t] : [];
  const mots = t.split(' ');
  if (mots.length <= k) return mots;
  const bornes = []; let pos = 0;
  for (let i = 0; i < mots.length - 1; i++) { pos += mots[i].length + (i ? 1 : 0); bornes.push({ i: i, pos: pos, ponct: /[,;:.!?…]$/.test(mots[i]) }); }
  const pris = []; let dernier = -1;
  for (let j = 1; j < k; j++) {
    const cible = t.length * j / k;
    let mieux = null, note = Infinity;
    for (const b of bornes) {
      if (b.i <= dernier || b.i > mots.length - 1 - (k - j)) continue;
      const s = Math.abs(b.pos - cible) - (b.ponct ? Math.min(12, t.length / k / 3) : 0);
      if (s < note) { note = s; mieux = b; }
    }
    if (!mieux) break;
    pris.push(mieux.i); dernier = mieux.i;
  }
  const r = []; let d = 0;
  for (const i of pris) { r.push(mots.slice(d, i + 1).join(' ')); d = i + 1; }
  r.push(mots.slice(d).join(' '));
  return r.filter(x => x);
}
function stDecoupe(t, n) {
  n = n || ST_CAR;
  const max = 2 * n, morceaux = [];
  for (const p of stPhrases(t)) {
    if (p.length <= max) { morceaux.push(p); continue; }
    const parts = p.split(/(?<=[,;:])\s+/);
    let c = '';
    for (const q of parts) {
      if (c && c.length + 1 + q.length <= max) { c += ' ' + q; continue; }
      if (c) morceaux.push(c);
      c = q;
      if (c.length > max) { const k = Math.ceil(c.length / (max - 4)); stCoupe(c, k).forEach(x => morceaux.push(x)); c = ''; }
    }
    if (c) morceaux.push(c);
  }
  const r = [];
  for (const m of morceaux) {
    const l = r.length ? r[r.length - 1] : null;
    if (l != null && l.length + 1 + m.length <= max && stLignes(l + ' ' + m, n).length <= 2 && (l.length < n / 2 || m.length < n / 2)) r[r.length - 1] = l + ' ' + m;
    else r.push(m);
  }
  return r;
}
function stEnN(t, k, n) {
  const d = stDecoupe(t, n);
  if (d.length === k) return d;
  return stCoupe(t, k);
}
function stDuree(t) { return Math.max(1500, Math.min(7000, 700 + 65 * stMots(t).length)); }
function stCues(texte, traduction, n, seule) {
  n = n || ST_CAR;
  texte = stMots(texte); traduction = stMots(traduction);
  const tr = traduction && traduction !== texte ? traduction : '';
  if (seule || !tr) {
    return stDecoupe(seule && tr ? tr : texte, n).map(x => ({ o: stLignes(x, n), t: null, ms: stDuree(x) }));
  }
  let a = stDecoupe(texte, n), b = stDecoupe(tr, n);
  if (a.length < b.length) a = stEnN(texte, b.length, n);
  else if (b.length < a.length) b = stEnN(tr, a.length, n);
  const k = Math.min(a.length, b.length), r = [];
  for (let i = 0; i < k; i++) {
    const o = i === k - 1 ? a.slice(i).join(' ') : a[i], t = i === k - 1 ? b.slice(i).join(' ') : b[i];
    r.push({ o: stLignes(o, n), t: stLignes(t, n), ms: Math.max(stDuree(o), stDuree(t)) });
  }
  return r;
}
class FileSousTitres {
  constructor(o) {
    o = o || {};
    this.tenue = o.tenue != null ? o.tenue : 8000;
    this.retardMax = o.retardMax != null ? o.retardMax : 12000;
    this.q = [];
  }
  ajoute(cues, t, dureeMs) {
    if (!cues.length) return;
    const somme = cues.reduce((x, c) => x + c.ms, 0);
    const f = dureeMs ? Math.min(1, Math.max(dureeMs, cues.length * 1500) / somme) : 1;
    const attente = this.q.filter(c => c.debut > t).reduce((x, c) => x + c.fin - c.debut, 0);
    if (attente > 0 && attente + somme * f > this.retardMax) this.q = this.q.filter(c => c.debut <= t);
    this.q = this.q.filter(c => c.fin + this.tenue > t);
    let d = t;
    for (const c of this.q) d = Math.max(d, c.fin);
    for (const c of cues) { const ms = Math.round(c.ms * f); this.q.push({ o: c.o, t: c.t, debut: d, fin: d + ms }); d += ms; }
  }
  actuel(t) {
    let r = null;
    for (const c of this.q) {
      if (c.debut <= t && t < c.fin) return c;
      if (c.fin <= t && t < c.fin + this.tenue) r = c;
    }
    return r;
  }
  vide() { this.q = []; }
}
"""
}
