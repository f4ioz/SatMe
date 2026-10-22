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
 * The page's map labels, their pure part (no canvas): points close on the
 * screen merged into one marker, then each label put where it covers no
 * other label and no marker, a leader line back to its point when it had
 * to move away. The same text runs in the page and under node in
 * EtiquettesCarteJsTest.
 */
object EtiquettesCarteJs {

    const val JS = """
/**
 * Points [{x, y, cle?}] in groups: a point joins the first group whose first point is within [d] px
 * (or has the same non-empty [cle], a locator), else starts one. Order kept; returns [{x, y, membres: [indexes]}].
 */
function groupesCarte(pts, d) {
  const gr = [];
  pts.forEach((p, i) => {
    const g0 = gr.find(q => (p.cle && q.cle === p.cle) || Math.hypot(q.x0 - p.x, q.y0 - p.y) <= d);
    if (g0) g0.membres.push(i); else gr.push({ x0: p.x, y0: p.y, cle: p.cle || '', membres: [i] });
  });
  const centre = q => { const m = q.membres.map(i => pts[i]);
    q.x = m.reduce((s, p) => s + p.x, 0) / m.length; q.y = m.reduce((s, p) => s + p.y, 0) / m.length; };
  gr.forEach(centre);
  // Groups whose centres came within [d] of each other made one (their markers would overlap).
  for (let fini = false; !fini;) {
    fini = true;
    for (let a = 0; a < gr.length && fini; a++) for (let b = a + 1; b < gr.length; b++)
      if (Math.hypot(gr[a].x - gr[b].x, gr[a].y - gr[b].y) <= d) {
        gr[a].membres = gr[a].membres.concat(gr[b].membres).sort((u, v) => u - v); gr.splice(b, 1); centre(gr[a]); fini = false; break;
      }
  }
  return gr.map(q => ({ x: q.x, y: q.y, membres: q.membres }));
}
/**
 * A group's label in columns: [n] names, [L] lines a column at most, [cmax] columns at most.
 * Returns {lignes, colonnes, montres}: the names shown (fewer than n: one more cell says how many are left), filled column by column.
 */
function colonnesEtiquette(n, L, cmax) {
  const c = Math.max(1, Math.min(cmax, Math.ceil(n / L))), cap = c * L, montres = n > cap ? cap - 1 : n;
  return { lignes: Math.ceil((montres + (n > cap ? 1 : 0)) / c), colonnes: c, montres };
}
/**
 * Labels without overlap. [pts]: [{x, y, r, w, h}] a marker (disc of radius r) and its label's size;
 * [obst]: boxes already on the map [{x, y, w, h}] (other labels, other markers); [W, H] the map.
 * Each label tries beside its point (right, left, above, below), then on rings further out;
 * the first place that covers nothing and stays on the map is kept, else the one that covers least.
 * Returns [{x, y, trait}] each label's top-left corner, [trait] when it stands away from its point.
 */
function placeEtiquettes(pts, obst, W, H) {
  const pris = (obst || []).slice(), M = 2;
  pts.forEach(p => pris.push({ x: p.x - p.r, y: p.y - p.r, w: 2 * p.r, h: 2 * p.r }));
  const recouvre = b => pris.reduce((s, o) => {
    const ox = Math.min(b.x + b.w + M, o.x + o.w) - Math.max(b.x - M, o.x), oy = Math.min(b.y + b.h + M, o.y + o.h) - Math.max(b.y - M, o.y);
    return s + (ox > 0 && oy > 0 ? ox * oy : 0);
  }, 0);
  const dehors = b => b.x < 0 || b.y < 0 || b.x + b.w > W || b.y + b.h > H;
  return pts.map(p => {
    const w = p.w, h = p.h, r = p.r, c = [];
    c.push([p.x + r + 3, p.y - h + 4, 0], [p.x + r + 3, p.y - 4, 0], [p.x - r - 3 - w, p.y - h + 4, 0], [p.x - r - 3 - w, p.y - 4, 0],
      [p.x - w / 2, p.y - r - 2 - h, 0], [p.x - w / 2, p.y + r + 2, 0]);
    for (const ray of [18, 28, 40, 54, 70, 90, 115, 145]) for (let k = 0; k < 16; k++) {
      const a = k * Math.PI / 8, cx = p.x + (r + ray) * Math.cos(a), cy = p.y + (r + ray) * Math.sin(a), co = Math.cos(a);
      c.push([Math.abs(co) < 0.2 ? cx - w / 2 : co > 0 ? cx : cx - w, cy - h / 2, 1]);
    }
    let mieux = null, cout = Infinity;
    for (const q of c) {
      const b = { x: q[0], y: q[1], w, h };
      const k = recouvre(b) * 10 + (dehors(b) ? 1e6 : 0) + q[2] * 0.5;
      if (k < cout) { cout = k; mieux = q; if (k < 1) break; }
    }
    pris.push({ x: mieux[0], y: mieux[1], w, h });
    return { x: mieux[0], y: mieux[1], trait: !!mieux[2] };
  });
}
"""
}
