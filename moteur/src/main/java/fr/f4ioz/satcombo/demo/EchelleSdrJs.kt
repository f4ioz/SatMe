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
 * The SDR tab's satellite scale (between the spectrum and the waterfall),
 * in the page's script after the spectrum's code, whose view it shares
 * (rSpan, rDx, spPx, spOff…): every known downlink where this station hears
 * it now ([fr.f4ioz.satcombo.domain.EchelleSdr], `api/sdr/echelle`, once a
 * second, moved on by its drift between), the passband, a tooltip; the
 * click, the wheel, Ctrl for the « correction manuelle », a pinch to zoom.
 */
object EchelleSdrJs {

    const val CODE = """
// ---- the satellite scale: the downlinks where this station hears them (Doppler, correction), the passband, tuning by mouse
let ECH = null, echRecu = 0, echBoites = [], echGlisse = null, echSurvol = null;
const echPointeurs = new Map();
const ECH_COUL = { visible: '#3FD37A', bientot: '#F2F5F8', sous: '#6B7783' };
const ECH_BANDE = { visible: '#4C8DFF', bientot: '#6B7783', sous: '#6B7783' };
async function ticEchelle() {
  if (g('v-sdr').hidden || !R || !R.actif) { if (ECH) { ECH = null; echPeint(); } return; }
  let j = null;
  try { j = await (await fetch('api/sdr/echelle')).json(); } catch (x) { return; }
  if (!j || !j.ok) return;
  ECH = j; echRecu = Date.now(); echPeint();
}
setInterval(ticEchelle, 1000);
/** A marker's sky edges now: the server's, moved on by their drift since, and by a correction being set. */
function echF(m) {
  const d = (m.v || 0) * Math.min(3, (Date.now() - echRecu) / 1000) + (echGlisse && echGlisse.quoi === 'corr' && echGlisse.m.n === m.n ? echGlisse.d : 0);
  return [m.bas + d, m.haut + d];
}
const echKhz = hz => (hz > 0 ? '+' : '') + (hz / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 2 }) + ' kHz';
const echHz = hz => (hz > 0 ? '+' : '') + Math.round(hz).toLocaleString('fr-FR') + ' Hz';
/** A label's text: the name, with the mode (or the transmitter) when the satellite has several downlinks in view. */
function echNom(m, l) {
  const memes = l.filter(o => o.n === m.n);
  if (memes.length < 2) return m.nom;
  // The mode tells them apart, else the transmitter's own name (shortened).
  const k = m.mode && memes.filter(o => o.mode === m.mode).length < 2 ? m.mode : m.tx.length > 18 ? m.tx.slice(0, 17) + '…' : m.tx;
  return m.nom + ' · ' + k;
}
function echPeint() {
  const c = g('r-echelle'); if (!c || g('v-sdr').hidden) return;
  const [x, w, h] = toile(c), ciel = (R && R.ciel) || 0;
  x.fillStyle = '#0B131B'; x.fillRect(0, 0, w, h);
  echBoites = [];
  if (!ciel) return;
  // The sky frequency, every few kHz, at the bottom.
  x.fillStyle = 'rgba(154,167,180,.75)'; x.strokeStyle = 'rgba(154,167,180,.35)'; x.font = '9px Drapeaux,system-ui'; x.textAlign = 'center'; x.lineWidth = 1;
  const pas = pasGraduation(rSpan / w) * 5;
  for (let f = Math.ceil((ciel + spOff(0, w)) / pas) * pas; f <= ciel + spOff(w, w); f += pas) {
    const px = Math.round(spPx(f - ciel, w)) + .5;
    x.beginPath(); x.moveTo(px, h - 12); x.lineTo(px, h - 9); x.stroke();
    if (px > 28 && px < w - 28) x.fillText((f / 1e6).toLocaleString('fr-FR', { minimumFractionDigits: 3, maximumFractionDigits: 4 }), px, h - 1);
  }
  // The passband heard: a rectangle across the scale.
  if (R) {
    const [ba, bb] = spBande(), xa = spPx(ba, w), xb = spPx(bb, w);
    x.fillStyle = 'rgba(255,180,84,.18)'; x.fillRect(xa, 0, Math.max(2, xb - xa), h - 12);
    x.strokeStyle = 'rgba(255,180,84,.85)'; x.strokeRect(Math.round(xa) + .5, .5, Math.max(2, Math.round(xb - xa)), h - 13);
    echBoites.push({ quoi: 'bp', x0: xa - 4, x1: xb + 4, y0: 0, y1: h - 12 });
  }
  if (!ECH) return;
  const l = ECH.marques || [];
  // The bands first (down ones under the others), then the single frequencies.
  for (const m of l) {
    if (!m.bande) continue;
    const [b, t] = echF(m), xa = spPx(b - ciel, w), xb = spPx(t - ciel, w);
    if (xb < 0 || xa > w) continue;
    const coul = ECH_BANDE[m.etat] || '#6B7783';
    x.globalAlpha = m.etat === 'visible' ? .32 : .2; x.fillStyle = coul; x.fillRect(xa, 2, xb - xa, 8); x.globalAlpha = 1;
    x.fillStyle = coul; x.fillRect(xa, 2, 1.5, 12); x.fillRect(xb - 1.5, 2, 1.5, 12);
    if (m.suivi) { x.strokeStyle = '#FFB454'; x.lineWidth = 1.5; x.strokeRect(xa, 1.5, xb - xa, 9); x.lineWidth = 1; }
    echBoites.push({ quoi: 'bande', m, x0: xa, x1: xb, y0: 0, y1: 14 });
  }
  // The labels: placed by priority (followed, up, rising, down), two rows, a tick when no room.
  const ordre = l.slice().sort((a, b) => (b.satSuivi - a.satSuivi) || (b.suivi - a.suivi) || ({ visible: 0, bientot: 1, sous: 2 }[a.etat] - { visible: 0, bientot: 1, sous: 2 }[b.etat]));
  const rangs = [[], []], places = [];
  for (const m of ordre) {
    const [b, t] = echF(m), px = spPx((b + t) / 2 - ciel, w);
    if (px < -40 || px > w + 40) continue;
    x.font = (m.fav ? '700 ' : '400 ') + '10px Drapeaux,system-ui';
    const txt = echNom(m, l), tw = x.measureText(txt).width + 8;
    const x0 = Math.max(0, Math.min(w - tw, px - tw / 2)), x1 = x0 + tw;
    let rang = -1;
    for (let r = 0; r < 2 && rang < 0; r++) if (!rangs[r].some(([a, z]) => x0 < z + 2 && x1 > a - 2)) rang = r;
    if (rang >= 0) rangs[rang].push([x0, x1]);
    places.push({ m, px, txt, x0, x1, rang });
  }
  for (let i = places.length - 1; i >= 0; i--) {
    const p = places[i], m = p.m, coul = ECH_COUL[m.etat] || '#6B7783';
    // The tick, at the frequency (a band's tick at its middle).
    x.strokeStyle = coul; x.lineWidth = m.suivi ? 2 : 1;
    if (!m.bande) { x.beginPath(); x.moveTo(Math.round(p.px) + .5, 0); x.lineTo(Math.round(p.px) + .5, 13); x.stroke(); }
    x.lineWidth = 1;
    echBoites.push({ quoi: 'marque', m, x0: p.px - 5, x1: p.px + 5, y0: 0, y1: 14 });
    if (p.rang < 0) continue;
    const y = 16 + p.rang * 12;
    x.font = (m.fav ? '700 ' : '400 ') + '10px Drapeaux,system-ui';
    if (m.suivi || m.satSuivi) { x.fillStyle = m.suivi ? 'rgba(255,180,84,.95)' : 'rgba(255,180,84,.35)'; x.fillRect(p.x0, y, p.x1 - p.x0, 12); }
    else { x.fillStyle = 'rgba(6,19,26,.8)'; x.fillRect(p.x0, y, p.x1 - p.x0, 12); }
    x.fillStyle = m.suivi ? '#06131A' : coul; x.textAlign = 'left'; x.fillText(p.txt, p.x0 + 4, y + 10);
    echBoites.push({ quoi: 'marque', m, x0: p.x0, x1: p.x1, y0: y, y1: y + 12 });
  }
  if (echSurvol) echInfo(echSurvol.b, echSurvol.px);
}
/** On the waterfall's overlay: the bands' edges (faint, dashed) and the followed downlink. */
function echSur(x, w, h, ciel) {
  if (!ECH || !ciel) return;
  x.setLineDash([1, 5]);
  for (const m of ECH.marques || []) {
    if (!m.bande && !m.suivi) continue;
    const [b, t] = echF(m);
    x.strokeStyle = m.suivi ? 'rgba(255,180,84,.6)' : m.etat === 'visible' ? 'rgba(76,141,255,.55)' : 'rgba(107,119,131,.45)';
    for (const f of m.bande ? [b, t] : [b]) { const px = Math.round(spPx(f - ciel, w)) + .5; if (px < 0 || px > w) continue; x.beginPath(); x.moveTo(px, 0); x.lineTo(px, h); x.stroke(); }
  }
  x.setLineDash([]);
}
/** What lies under [px], [py]: a label or a tick before a band, a band before the passband. */
function echA(px, py) {
  let r = null;
  for (const b of echBoites) {
    if (px < b.x0 || px > b.x1 || py < b.y0 || py > b.y1) continue;
    const k = { marque: 3, bande: 2, bp: 1 }[b.quoi];
    if (!r || k > r.k || (k === r.k && b.quoi === 'marque' && Math.abs((b.x0 + b.x1) / 2 - px) < Math.abs((r.b.x0 + r.b.x1) / 2 - px))) r = { k, b };
  }
  return r && r.b;
}
function echInfo(b, px) {
  const p = g('r-ech-info');
  if (!b || b.quoi === 'bp') {
    if (b && R) { p.innerHTML = '<b>Bande passante</b> · ' + esc(spModeR()) + '<br><span class="gris">molette : accord fin (Alt : plus vite)</span>'; }
    else { p.hidden = true; return; }
  } else {
    const m = b.m, now = Date.now(), [lo, hi] = echF(m);
    const quand = m.etat === 'visible' ? (m.los ? 'LOS dans ' + duree(m.los - now) : 'au-dessus de l’horizon')
      : m.aos && m.aos > now ? 'AOS dans ' + duree(m.aos - now) : 'pas de passage dans les 24 h';
    const corr = m.corr + (echGlisse && echGlisse.quoi === 'corr' && echGlisse.m.n === m.n ? echGlisse.d : 0);
    p.innerHTML = '<b>' + esc(m.nom) + '</b>' + (m.fav ? ' ★' : '') + ' · ' + esc(m.tx) + (m.mode ? ' · ' + esc(m.mode) : '') +
      '<br>' + (m.bande ? mhzFr(lo) + ' – ' + mhzFr(hi) : mhzFr(lo)) + ' MHz · Doppler ' + echKhz(m.dop) + (m.v ? ' (' + echHz(m.v) + '/s)' : '') +
      '<br>él ' + m.el.toLocaleString('fr-FR') + '° · az ' + m.az + '° · ' + quand +
      (corr ? '<br>correction manuelle ' + echHz(corr) : '') +
      '<br><span class="gris">' + (m.suivi ? 'suivi par le récepteur' : m.bande ? 'clic : s’accorder ici dans la bande' : 'clic : suivre cet émetteur') + ' · Ctrl + molette : correction</span>';
  }
  p.hidden = false;
  const c = g('r-echelle'), w = c.clientWidth, pw = p.offsetWidth;
  p.style.left = Math.max(0, Math.min(w - pw, px + 10)) + 'px';
}
let echCorrT = 0, echCorrN = 0, echCorrD = 0;
/** The correction moved by [d] Hz on satellite [n]: shown at once, sent a moment later. */
function echCorrige(n, d) {
  if (!ECH || !d) return;
  for (const m of ECH.marques || []) if (m.n === n) { m.bas += d; m.haut += d; m.corr += d; }
  if (echCorrN !== n && echCorrD) { post('api/sdr/echelle/correction?n=' + echCorrN + '&dhz=' + echCorrD); echCorrD = 0; }
  echCorrN = n; echCorrD += d;
  clearTimeout(echCorrT);
  echCorrT = setTimeout(() => { const k = echCorrD; echCorrD = 0; if (k) post('api/sdr/echelle/correction?n=' + echCorrN + '&dhz=' + k).then(() => ticEchelle()); }, 250);
  echPeint();
}
/** A click (or a tap) at [px], [py]: the satellite, the band or the frequency there. */
function echClic(px, py, w) {
  if (!R) return;
  const b = echA(px, py), ciel = R.ciel || 0;
  let u = null;
  if (b && b.quoi === 'marque') {
    const [lo, hi] = echF(b.m);
    u = 'api/sdr/echelle/accorde?n=' + b.m.n + '&tx=' + enc(b.m.tx) + '&hz=' + Math.round(b.m.bande ? lo + (hi - lo) / 2 : lo);
  } else {
    const hz = Math.round((ciel + spOff(px, w)) / 10) * 10;
    u = 'api/sdr/echelle/accorde?hz=' + hz + '&tol=' + Math.round(6 * rSpan / w);
  }
  if (SP_ESSAI) return;
  post(u).then(j => { if (j && j.ok) { ticSdr(); ticEchelle(); } });
}
(function () {
  const c = g('r-echelle');
  const pos = ev => { const b = c.getBoundingClientRect(); return [ev.clientX - b.left, ev.clientY - b.top, b.width]; };
  c.addEventListener('pointerdown', ev => {
    if (ev.button !== 0) return;
    const [px, py, w] = pos(ev); c.setPointerCapture(ev.pointerId);
    echPointeurs.set(ev.pointerId, px);
    if (echPointeurs.size === 2) { const [a, z] = [...echPointeurs.values()]; echGlisse = { quoi: 'pince', ecart: Math.abs(z - a) || 1 }; return; }
    const b = echA(px, py);
    echGlisse = ev.ctrlKey && b && b.m ? { quoi: 'corr', m: b.m, x0: px, d: 0, bouge: false } : { quoi: 'vue', x0: px, dx0: rDx, bouge: false };
  });
  c.addEventListener('pointermove', ev => {
    const [px, py, w] = pos(ev), gl = echGlisse;
    if (echPointeurs.has(ev.pointerId)) echPointeurs.set(ev.pointerId, px);
    if (gl && gl.quoi === 'pince' && echPointeurs.size === 2) {
      const [a, z] = [...echPointeurs.values()], e = Math.abs(z - a) || 1;
      if (Math.abs(e - gl.ecart) > 4) { spZoom(gl.ecart / e, (a + z) / 2, w); gl.ecart = e; }
      return;
    }
    if (gl && Math.abs(px - gl.x0) > 3) gl.bouge = true;
    if (gl && gl.bouge && gl.quoi === 'vue') spVue(rSpan, gl.dx0 - (px - gl.x0) / w * rSpan);
    else if (gl && gl.quoi === 'corr') { gl.d = Math.round((px - gl.x0) / w * rSpan / 10) * 10; echPeint(); }
    const b = gl ? (gl.quoi === 'corr' ? { quoi: 'marque', m: gl.m } : null) : echA(px, py);
    echSurvol = b ? { b, px } : null;
    echInfo(b, px);
    c.style.cursor = gl && gl.bouge ? (gl.quoi === 'corr' ? 'ew-resize' : 'grabbing') : b ? (b.quoi === 'bp' ? 'ew-resize' : 'pointer') : 'grab';
  });
  c.addEventListener('pointerleave', () => { if (!echGlisse) { echSurvol = null; g('r-ech-info').hidden = true; } });
  const fin = ev => {
    const deux = echPointeurs.size > 1;
    echPointeurs.delete(ev.pointerId);
    const gl = echGlisse; if (!gl) return;
    if (gl.quoi === 'pince') { if (!echPointeurs.size) echGlisse = null; return; }
    echGlisse = null;
    if (ev.type === 'pointercancel' || deux) return;
    const [px, py, w] = pos(ev);
    if (gl.quoi === 'corr') { if (gl.d) echCorrige(gl.m.n, gl.d); return; }
    if (!gl.bouge) echClic(px, py, w);
  };
  c.addEventListener('pointerup', fin);
  c.addEventListener('pointercancel', fin);
  // The wheel: Ctrl on a satellite its correction; on the passband the fine tuning (Alt: ten times); Shift the pan; else the zoom.
  c.addEventListener('wheel', ev => {
    ev.preventDefault();
    const [px, py, w] = pos(ev), b = echA(px, py), sens = -Math.sign(ev.deltaY || ev.deltaX);
    if (!sens) return;
    if (ev.ctrlKey) {
      let m = b && b.m;
      if (!m && ECH) m = (ECH.marques || []).find(o => o.suivi) || null;
      if (!m) return;
      const p = (m.bande || /USB|LSB|CW|SSB/i.test(m.mode) ? 10 : 100) * (ev.altKey ? 10 : 1);
      echCorrige(m.n, sens * p);
      echSurvol = { b: { quoi: 'marque', m }, px }; echInfo(echSurvol.b, px);
    } else if (b && b.quoi === 'bp') spAccordePas(sens * (spBlu() ? 10 : 100) * (ev.altKey ? 10 : 1));
    else if (ev.shiftKey) spVue(rSpan, rDx - sens * rSpan / 10);
    else spZoom(sens < 0 ? 1.25 : 0.8, px, w);
  }, { passive: false });
  new ResizeObserver(() => echPeint()).observe(c);
})();
"""
}
