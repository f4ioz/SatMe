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
 * lines at most (or the number chosen, 1 to 4), the way television
 * subtitles are (by sentence, then by clause, then by words, the lines
 * balanced), each shown for a time that grows with its length (longer or
 * shorter as chosen), one after the other. The same text runs in the page
 * and under node in SousTitresJsTest.
 */
object SousTitresJs {

    /**
     * stLignes(t, n, L): [t] in lines of [n] signs at most, two balanced lines
     * when it fits in two (the cut after punctuation when one is near), else
     * lines of even length; [L] (2) the lines a cue may have.
     * stDecoupe(t, n, L): [t] in cues of [L] lines at most.
     * stEnN(t, k, n, L): [t] in exactly [k] pieces (the translation cut as its original).
     * stDuree(t, k, L): how long a cue stays, in ms (about 15 signs a second, 1.5 to 3.5 s a line), × [k].
     * stCues(texte, traduction, n, seule, L, k): the over's cues, {o: lines, t: lines or null, ms}.
     * stReglages(r): the display settings made whole ({largeur, lignes, car, taille, duree, plus}),
     * « plus de texte » applied (4 lines, 90 % wide, longer lines, the previous cues kept above).
     * class FileSousTitres: the cues one after the other, the latest over first when late;
     * pile(t, k): the cue shown and up to k − 1 before it.
     */
    const val CODE = """
const ST_CAR = 42;
function stMots(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
function stGlouton(mots, n) {
  const l = []; let c = '';
  for (const m of mots) { if (c && c.length + 1 + m.length > n) { l.push(c); c = m; } else c = c ? c + ' ' + m : m; }
  if (c) l.push(c);
  return l;
}
function stLignes(t, n, L) {
  t = stMots(t); n = n || ST_CAR; L = L || 2;
  if (!t) return [];
  if (t.length <= n) return [t];
  const mots = t.split(' ');
  if (L >= 2 && t.length <= 2 * n + 1) {
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
  const l = stGlouton(mots, n);
  // Three lines or more: as narrow as they can be in as many lines (even, not a long one over a short one).
  if (l.length > 2) for (let w = Math.ceil(t.length / l.length); w < n; w++) { const b = stGlouton(mots, w); if (b.length <= l.length) return b; }
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
function stDecoupe(t, n, L) {
  n = n || ST_CAR; L = L || 2;
  const max = L * n, morceaux = [];
  // Fits in a cue: short enough, and in its lines once wrapped by words.
  const tient = x => x.length <= max && stLignes(x, n, L).length <= L;
  for (const p of stPhrases(t)) {
    if (tient(p)) { morceaux.push(p); continue; }
    const parts = p.split(/(?<=[,;:])\s+/);
    let c = '';
    for (const q of parts) {
      if (c && tient(c + ' ' + q)) { c += ' ' + q; continue; }
      if (c) morceaux.push(c);
      c = q;
      if (!tient(c)) {
        // Cut in more pieces until each fits (a word alone always does: the page wraps it).
        const nm = c.split(' ').length;
        let k = Math.ceil(c.length / (max - 4)), l;
        do { l = stCoupe(c, k); k++; } while (l.some(x => !tient(x)) && k <= nm);
        l.forEach(x => morceaux.push(x)); c = '';
      }
    }
    if (c) morceaux.push(c);
  }
  const r = [];
  for (const m of morceaux) {
    const l = r.length ? r[r.length - 1] : null;
    if (l != null && l.length + 1 + m.length <= max && stLignes(l + ' ' + m, n, L).length <= L && (L > 2 || l.length < n / 2 || m.length < n / 2)) r[r.length - 1] = l + ' ' + m;
    else r.push(m);
  }
  return r;
}
function stEnN(t, k, n, L) {
  const d = stDecoupe(t, n, L);
  if (d.length === k) return d;
  // Cut evenly; a piece then longer than its lines cut again (never made to overflow).
  const r = [];
  stCoupe(t, k).forEach(x => { if (stLignes(x, n, L).length <= (L || 2)) r.push(x); else stDecoupe(x, n, L).forEach(y => r.push(y)); });
  return r;
}
function stDuree(t, k, L) { return Math.round(Math.max(1500, Math.min(3500 * (L || 2), 700 + 65 * stMots(t).length)) * (k || 1)); }
function stCues(texte, traduction, n, seule, L, k) {
  n = n || ST_CAR; L = L || 2;
  texte = stMots(texte); traduction = stMots(traduction);
  const tr = traduction && traduction !== texte ? traduction : '';
  if (seule || !tr) {
    return stDecoupe(seule && tr ? tr : texte, n, L).map(x => ({ o: stLignes(x, n, L), t: null, ms: stDuree(x, k, L) }));
  }
  let a = stDecoupe(texte, n, L), b = stDecoupe(tr, n, L);
  if (a.length < b.length) a = stEnN(texte, b.length, n, L);
  else if (b.length < a.length) b = stEnN(tr, a.length, n, L);
  // Still not as many (a text too short to cut so finely): the extra cues alone, none made longer than its lines.
  const m = Math.max(a.length, b.length), r = [];
  for (let i = 0; i < m; i++) {
    const o = a[i] || '', t = b[i] || '';
    r.push({ o: stLignes(o, n, L), t: stLignes(t, n, L), ms: Math.max(stDuree(o, k, L), stDuree(t, k, L)) });
  }
  return r;
}
const ST_DEFAUT = { largeur: 96, lignes: 2, car: ST_CAR, taille: 0, duree: 1, plus: false };
function stReglages(r) {
  r = Object.assign({}, ST_DEFAUT, r || {});
  const borne = (x, a, b, d) => { x = Number(x); return isFinite(x) ? Math.max(a, Math.min(b, x)) : d; };
  const s = { largeur: Math.round(borne(r.largeur, 40, 100, 96)), lignes: Math.round(borne(r.lignes, 1, 4, 2)), car: Math.round(borne(r.car, 20, 90, ST_CAR)),
    taille: borne(r.taille, 0, 8, 0), duree: borne(r.duree, 0.5, 2.5, 1), plus: !!r.plus, pile: 1 };
  if (s.plus) { s.lignes = 4; s.largeur = Math.max(s.largeur, 90); s.car = Math.max(s.car, 56); s.pile = 3; }
  return s;
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
    // Kept 30 s after they went (the ones above in « plus de texte »).
    this.q = this.q.filter(c => c.fin + Math.max(this.tenue, 30000) > t);
    let d = t;
    for (const c of this.q) d = Math.max(d, c.fin);
    for (const c of cues) { const ms = Math.round(c.ms * f); this.q.push({ o: c.o, t: c.t, drapeau: c.drapeau, debut: d, fin: d + ms }); d += ms; }
  }
  actuel(t) {
    let r = null;
    for (const c of this.q) {
      if (c.debut <= t && t < c.fin) return c;
      if (c.fin <= t && t < c.fin + this.tenue) r = c;
    }
    return r;
  }
  pile(t, k) {
    const c = this.actuel(t);
    if (!c) return [];
    const i = this.q.indexOf(c), r = [];
    for (let j = Math.max(0, i - (k || 1) + 1); j <= i; j++) if (j === i || this.q[j].fin + 30000 > t) r.push(this.q[j]);
    return r;
  }
  vide() { this.q = []; }
}
"""
}
