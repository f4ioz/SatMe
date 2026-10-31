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
 * A waterfall you can rewind, in SatMe-PC's page: the QO-100 narrow-band
 * waterfall, the DATV wide band (both strips) and the SDR waterfall.
 *
 * **Client-side, in the browser.** Each waterfall already gets its rows (the
 * page polls or streams them); this keeps the last ten minutes of them,
 * **8-bit quantized** (one byte a column, two numbers a row for the scale),
 * at most 512 columns (the strongest of each group kept, so a narrow signal
 * survives) and one row every 250 ms: about 1.3 MB per waterfall at worst.
 * Nothing new on the server, nothing kept when the page closes.
 *
 * [CODE], pure (no page, tested by node in RembobinageJsTest):
 * - rbNeuf(o): a history (ring); rbPousse(h, t, fBas, fHaut, vals): a row in (false when too soon after
 *   the last one); rbTaille, rbLigne(h, k) (k = 0 the newest); rbValeur(row, i): a column back (null: no data);
 *   rbIndex(h, t): the newest row not after t; rbColonne(row, f): the column under a frequency.
 * - rbRecul(ms): « −2 min 15 s »; rbHeure(t): « 14:03:21 UTC ».
 *
 * [PAGE], the page's side: ⏸ over each waterfall; paused, the history drawn
 * over it (an overlay canvas: the live drawing goes on below, untouched), a
 * time tape at the bottom (how far back, the UTC time of the top row; under the pointer or
 * the finger, the time and frequency of that row), a slider for the whole
 * history (touch, narrow screens), the wheel and a drag to scroll, « Direct »
 * to snap back. While paused, rows keep coming into the history: the view is
 * anchored to a time, not to a row, so it does not slide.
 *
 * Plain constants: no Kotlin template in here, and the JS uses no template literals.
 */
object RembobinageJs {

    const val CODE = """
/** A waterfall's history: [dureeMs] kept, a row every [pasMs] at most, [binsMax] columns at most. */
function rbNeuf(o) {
  o = o || {};
  const dureeMs = o.dureeMs || 600000, pasMs = o.pasMs || 250;
  return { dureeMs: dureeMs, pasMs: pasMs, binsMax: o.binsMax || 512, cap: Math.ceil(dureeMs / pasMs) + 2,
    lignes: [], tete: 0, n: 0, dernierMs: -1e15 };
}
function rbTaille(h) { return h.n; }
/** The [k]-th row back (0: the newest), or null. */
function rbLigne(h, k) {
  if (k < 0 || k >= h.n) return null;
  return h.lignes[(h.tete - 1 - k + h.cap * 2) % h.cap];
}
/**
 * A row in: [vals] the levels (dB or the source's units; null: no data there), spanning [fBas]..[fHaut].
 * Quantized to a byte: 0 no data, 1..255 the level between the row's lowest and highest.
 */
function rbPousse(h, t, fBas, fHaut, vals) {
  if (!vals || !vals.length || t - h.dernierMs < h.pasMs) return false;
  const n = vals.length, grp = Math.max(1, Math.ceil(n / h.binsMax)), m = Math.ceil(n / grp);
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < n; i++) { const v = vals[i]; if (v == null || v !== v) continue; if (v < lo) lo = v; if (v > hi) hi = v; }
  if (lo === Infinity) return false;
  const pas = hi > lo ? (hi - lo) / 254 : 1;
  let r = h.lignes[h.tete];
  if (!r || r.q.length !== m) r = { q: new Uint8Array(m) };
  for (let j = 0; j < m; j++) {
    let best = null;
    for (let i = j * grp, e = Math.min(n, i + grp); i < e; i++) { const v = vals[i]; if (v != null && v === v && (best == null || v > best)) best = v; }
    r.q[j] = best == null ? 0 : 1 + Math.round((best - lo) / pas);
  }
  r.t = t; r.fBas = fBas; r.fHaut = fHaut; r.lo = lo; r.pas = pas;
  h.lignes[h.tete] = r; h.tete = (h.tete + 1) % h.cap; if (h.n < h.cap) h.n++;
  h.dernierMs = t;
  // Older than the duration: out, even when the ring is not full (rows came slowly).
  while (h.n > 1 && rbLigne(h, h.n - 1).t < t - h.dureeMs) h.n--;
  return true;
}
/** A column of a row back in its units, null where there was no data. */
function rbValeur(r, i) { const q = r.q[i]; return q ? r.lo + (q - 1) * r.pas : null; }
/** The column of [r] under frequency [f], -1 outside it. */
function rbColonne(r, f) {
  if (f < r.fBas || f > r.fHaut || r.fHaut <= r.fBas) return -1;
  return Math.min(r.q.length - 1, Math.floor((f - r.fBas) / (r.fHaut - r.fBas) * r.q.length));
}
/** The index of the newest row not after [t] (the oldest one when all are after), -1 when empty. */
function rbIndex(h, t) {
  if (!h.n) return -1;
  let a = 0, b = h.n - 1;
  if (rbLigne(h, b).t > t) return b;
  while (a < b) { const m = (a + b) >> 1; if (rbLigne(h, m).t <= t) b = m; else a = m + 1; }
  return a;
}
/** How far back, said: « −45 s », « −2 min 15 s », « −10 min ». */
function rbRecul(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return '−' + s + ' s';
  const m = Math.floor(s / 60), r = s % 60;
  return '−' + m + ' min' + (r ? ' ' + (r < 10 ? '0' : '') + r + ' s' : '');
}
/** « 14:03:21 UTC ». */
function rbHeure(t) { return new Date(t).toISOString().slice(11, 19) + ' UTC'; }
/** A frequency in Hz, said in MHz: « 10 489,7505 MHz ». */
function rbMhz(hz) { return (hz / 1e6).toLocaleString('fr-FR', { minimumFractionDigits: 4, maximumFractionDigits: 4 }) + ' MHz'; }
/** The scale over rows [k0]..[k1]: the lowest and the highest level seen, at least [plageMin] apart. */
function rbEchelle(h, k0, k1, plageMin) {
  let lo = Infinity, hi = -Infinity;
  for (let k = Math.max(0, k0); k <= Math.min(h.n - 1, k1); k++) { const r = rbLigne(h, k); if (r.lo < lo) lo = r.lo; const top = r.lo + 254 * r.pas; if (top > hi) hi = top; }
  if (lo === Infinity) return [0, 1];
  if (hi < lo + (plageMin || 0)) hi = lo + (plageMin || 0);
  return [lo, hi];
}
"""

    const val PAGE = """
// Each history by source ('qo100', 'sdr', 'datv'); each view by canvas id (a source can show in two places).
const RBH = {}, RBV = {};
/**
 * A waterfall that can be rewound. [o]: source, toile (the canvas id), fenetre() → [fBas, fHaut] shown now,
 * rang (px a row), marge (px each side), hz (the source's unit in Hz: 1000 for kHz), bas/haut (a fixed scale) or
 * plageMin (an auto one), grandit (a class put on the canvas's parent while paused: a thin strip grows),
 * dessus (the ⏸ above the canvas, not over it: a strip too thin for it).
 */
function rbVue(o) {
  const c = g(o.toile); if (!c) return;
  if (!RBH[o.source]) RBH[o.source] = rbNeuf();
  const p = c.parentElement; if (getComputedStyle(p).position === 'static') p.style.position = 'relative';
  const b = document.createElement('button'); b.className = 'rb-b'; b.type = 'button'; b.textContent = '⏸';
  b.title = 'Pause : remonter dans les 10 dernières minutes de la cascade (elle continue d’enregistrer)';
  b.setAttribute('aria-label', 'Pause de la cascade'); b.disabled = true;
  const z = document.createElement('div'); z.className = 'rb-z'; z.hidden = true;
  z.innerHTML = '<canvas class="rb-c" tabindex="0" aria-label="Historique de la cascade : glisser ou molette pour remonter le temps"></canvas>' +
    '<div class="rb-bande"><span class="rb-t"></span><input type="range" class="rb-r" min="0" max="0" value="0" aria-label="Remonter le temps">' +
    '<button type="button" class="rb-d" title="Revenir au direct">Direct ▶</button></div><div class="rb-i" hidden></div>';
  p.appendChild(b); p.appendChild(z);
  const v = Object.assign({ rang: 1, marge: 0, hz: 1, plageMin: 20 }, o, { c: c, b: b, z: z, cv: z.querySelector('.rb-c'), t: z.querySelector('.rb-t'),
    r: z.querySelector('.rb-r'), i: z.querySelector('.rb-i'), pause: false, ancre: 0, info: null });
  RBV[o.toile] = v;
  b.addEventListener('click', ev => { ev.stopPropagation(); rbPause(v); });
  z.querySelector('.rb-d').addEventListener('click', ev => { ev.stopPropagation(); rbDirect(v); });
  v.r.addEventListener('input', () => { const h = RBH[v.source], l = rbLigne(h, +v.r.value); if (l) { v.ancre = l.t; v.info = null; rbPeint(v); } });
  // The wheel and a drag scroll the history; a tap (or the mouse over) says the row's time and frequency.
  v.cv.addEventListener('wheel', ev => { ev.preventDefault(); ev.stopPropagation(); rbDefile(v, Math.sign(ev.deltaY) * Math.max(3, Math.round(Math.abs(ev.deltaY) / 20))); }, { passive: false });
  let gl = null;
  v.cv.addEventListener('pointerdown', ev => { ev.stopPropagation(); gl = { y: ev.clientY, k: rbHaut(v), bouge: false }; try { v.cv.setPointerCapture(ev.pointerId); } catch (x) {} });
  v.cv.addEventListener('pointermove', ev => {
    if (gl) { const d = Math.round((gl.y - ev.clientY) / v.rang); if (Math.abs(gl.y - ev.clientY) > 4) gl.bouge = true;
      if (gl.bouge) { rbVa(v, gl.k + d); return; } }
    rbSurvol(v, ev);
  });
  v.cv.addEventListener('pointerup', ev => { const t = gl; gl = null; if (t && !t.bouge) rbSurvol(v, ev); });
  v.cv.addEventListener('pointerleave', () => { if (!gl && v.info) { v.info = null; v.i.hidden = true; } });
  v.cv.addEventListener('click', ev => ev.stopPropagation());
  v.cv.addEventListener('keydown', ev => {
    const d = { ArrowUp: -10, ArrowDown: 10, PageUp: -100, PageDown: 100 }[ev.key];
    if (d) { ev.preventDefault(); ev.stopPropagation(); rbDefile(v, d); }
    else if (ev.key === 'Escape' || ev.key === 'End') { ev.preventDefault(); rbDirect(v); }
  });
  rbPlace(v);
  new ResizeObserver(() => { rbPlace(v); if (v.pause) rbPeint(v); }).observe(c);
}
/** The ⏸ at the canvas's top right corner, or just above it for a thin strip ([dessus]). */
function rbPlace(v) { v.b.style.top = Math.max(0, v.c.offsetTop + (v.dessus ? -22 : 4)) + 'px'; }
/** A row from the source: into its history; the views paused on it say the time that passes. */
function rbRecoit(source, fBas, fHaut, vals) {
  const h = RBH[source]; if (!h || !rbPousse(h, Date.now(), fBas, fHaut, vals)) return;
  for (const id in RBV) { const v = RBV[id]; if (v.source === source) { if (v.pause) rbTexte(v); else v.b.disabled = false; } }
}
/** The row on top of the paused view (0: the newest). */
function rbHaut(v) { return Math.max(0, rbIndex(RBH[v.source], v.ancre)); }
function rbPause(v) {
  const h = RBH[v.source]; if (!h || !h.n) return;
  v.pause = true; v.ancre = rbLigne(h, 0).t; v.info = null;
  v.z.hidden = false; v.b.hidden = true; if (v.grandit) v.c.parentElement.classList.add(v.grandit);
  rbPeint(v); try { v.cv.focus({ preventScroll: true }); } catch (x) {}
}
function rbDirect(v) {
  v.pause = false; v.z.hidden = true; v.b.hidden = false; v.i.hidden = true;
  if (v.grandit) v.c.parentElement.classList.remove(v.grandit);
  rbPlace(v);
}
function rbDefile(v, d) { rbVa(v, rbHaut(v) + d); }
/** The view with row [k] on top (kept within the history). */
function rbVa(v, k) {
  const h = RBH[v.source]; if (!h.n) return;
  const l = rbLigne(h, Math.max(0, Math.min(h.n - 1, k))); v.ancre = l.t; v.info = null; v.i.hidden = true; rbPeint(v);
}
/** The tape: how far back the top row is, its UTC time; the slider at its place. */
function rbTexte(v) {
  const h = RBH[v.source], k = rbHaut(v), l = rbLigne(h, k); if (!l) return;
  v.t.textContent = rbRecul(Date.now() - l.t) + ' · ' + rbHeure(l.t);
  v.r.max = String(Math.max(0, h.n - 1)); v.r.value = String(k);
  v.r.title = 'Historique : ' + rbRecul(Date.now() - rbLigne(h, h.n - 1).t) + ' au plus loin';
}
/** The history drawn from the anchored row down, on the scale of the frequencies shown now. */
function rbPeint(v) {
  const c = v.c, cv = v.cv, w = c.offsetWidth, hh = c.offsetHeight; if (!w || !hh) return;
  v.z.style.left = c.offsetLeft + 'px'; v.z.style.top = c.offsetTop + 'px'; v.z.style.width = w + 'px'; v.z.style.height = hh + 'px';
  if (cv.width !== w || cv.height !== hh) { cv.width = w; cv.height = hh; }
  const x = cv.getContext('2d'), h = RBH[v.source], k0 = rbHaut(v), lignes = Math.ceil(hh / v.rang);
  const [fb, ft] = v.fenetre(), lut = palLut(AFF.pal), m = v.marge, W = Math.max(1, w - 2 * m);
  const [lo, hi] = v.bas != null ? [v.bas, v.haut] : rbEchelle(h, k0, k0 + lignes, v.plageMin), k1 = 255 / Math.max(1e-9, hi - lo);
  const im = x.createImageData(w, hh), u = new Uint32Array(im.data.buffer), vide = lut[256] || 0xFF282C2C;
  u.fill(vide);
  for (let y = 0; y < hh; y++) {
    const r = rbLigne(h, k0 + Math.floor(y / v.rang)); if (!r) break;
    for (let px = 0; px < W; px++) {
      const i = rbColonne(r, fb + (ft - fb) * (px + 0.5) / W); if (i < 0) continue;
      const val = rbValeur(r, i); if (val == null) continue;
      let k = (val - lo) * k1; k = k < 0 ? 0 : k > 255 ? 255 : k | 0; u[y * w + m + px] = lut[k];
    }
  }
  x.putImageData(im, 0, 0);
  rbTexte(v);
}
/** Under the pointer or the finger: the row's time, how far back, the frequency. */
function rbSurvol(v, ev) {
  const b = v.cv.getBoundingClientRect(), y = ev.clientY - b.top, px = ev.clientX - b.left - v.marge, W = Math.max(1, b.width - 2 * v.marge);
  const h = RBH[v.source], r = rbLigne(h, rbHaut(v) + Math.floor(y / v.rang));
  if (!r || px < 0 || px > W) { v.i.hidden = true; return; }
  const [fb, ft] = v.fenetre(), f = (fb + (ft - fb) * px / W) * v.hz;
  v.info = { t: r.t, f: f };
  v.i.textContent = rbHeure(r.t) + ' (' + rbRecul(Date.now() - r.t) + ') · ' + rbMhz(f);
  v.i.hidden = false;
  v.i.style.top = Math.max(0, Math.min(b.height - 22, y - 26)) + 'px';
  v.i.style.left = Math.max(0, Math.min(b.width - v.i.offsetWidth, px + v.marge - v.i.offsetWidth / 2)) + 'px';
}
"""

    /** The overlay's look (the page's style sheet). */
    const val STYLE = """
  .rb-b { position:absolute; right:6px; z-index:3; padding:1px 7px; font-size:12px; line-height:18px; min-height:0; opacity:.8; background:rgba(6,19,26,.75); }
  .rb-b:hover, .rb-b:focus-visible { opacity:1; }
  .rb-b:disabled { display:none; }
  .rb-z { position:absolute; z-index:4; }
  .rb-c { position:absolute; inset:0; width:100%; height:100%; display:block; cursor:ns-resize; touch-action:none; outline:none; }
  .rb-bande { position:absolute; left:0; right:0; bottom:0; display:flex; align-items:center; gap:6px; flex-wrap:wrap; padding:3px 6px; background:rgba(6,19,26,.82); font-size:12px; }
  .rb-t { font-variant-numeric:tabular-nums; color:var(--ambre); font-weight:600; white-space:nowrap; }
  .rb-r { flex:1 1 90px; min-width:70px; direction:rtl; }
  .rb-d { padding:1px 8px; font-size:12px; min-height:0; }
  .rb-i { position:absolute; pointer-events:none; background:rgba(6,19,26,.9); color:#E8F0F7; font-size:11px; padding:2px 6px; border-radius:6px; white-space:nowrap; font-variant-numeric:tabular-nums; }
  .wb-bande.rb-grand .wb-chute { height:160px; }
"""
}
