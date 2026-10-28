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
 * The SSTV tab's inset (« encart ») reminding the operator of the satellite
 * followed while pictures come in: its name, the auto-pilot's 🤖, the SSTV
 * downlink's mode and frequency, the Doppler-corrected RX; the pass's sky
 * (the Station tab's own `cielSvg()`, with LOS and the highest point marked);
 * a small globe; AOS, TCA, LOS (local time and UTC), the height, where it is
 * now, how long is left, or the next pass.
 *
 * Everything comes from what the page already has: the station's state
 * (`E`, pushed each second), the auto-pilot (`AP`, every 3 s), the ground
 * track (`api/trace`, the Station map's, once a minute) and the coastline
 * (`api/carte/traits`, once, cached by the browser). No new endpoint.
 *
 * The globe is drawn flat on a 2D canvas (orthographic, the coastline, the
 * track, the footprint by OrbitesJs's `obEmpreinte`, the light by its
 * `obSoleil`), not by the Orbites tab's three.js globe: that one owns a
 * single WebGL renderer, moved from canvas to canvas, and would be taken
 * from the Orbites or Station tab; its 600 KB of libraries and a GPU frame
 * each second would weigh on the decoder's page for a 150-pixel picture.
 * Its background is cached and drawn again only when the view moves by a
 * degree, the rest once a second, and only while the SSTV tab is open.
 *
 * Folded (« ▾ ») and hidden (SSTV › Réglages) per browser. Put into
 * [PagePc.HTML]; no Kotlin template in the script. Its pure part ([PUR])
 * runs under node in EncartSstvJsTest.
 */
object EncartSstvJs {

    const val CSS = """
  /* ---------------------------------------------------------------- SSTV: the satellite followed, reminded (EncartSstvJs) */
  .es { margin:0; min-width:0; }
  .es[hidden] { display:none; }
  .es-tete { display:flex; align-items:center; gap:8px; min-width:0; flex-wrap:wrap; }
  .es-tete .es-titre { color:var(--gris); font-size:11px; text-transform:uppercase; letter-spacing:.08em; }
  .es-tete .es-nom { font-weight:700; font-size:15px; color:var(--clair); white-space:nowrap; }
  .es-tete .es-mode { color:var(--gris); min-width:0; }
  .es-tete .es-mode b { color:var(--cyan); font-weight:600; }
  .es-tete .es-compte { margin-left:auto; font-size:14px !important; white-space:nowrap; font-variant-numeric:tabular-nums; }
  .es-tete button { background:none; border:1px solid transparent; color:var(--gris); padding:0 6px !important; line-height:22px; border-radius:6px; cursor:pointer; }
  .es-tete button:hover, .es-tete button:focus-visible { border-color:var(--bord); color:var(--clair); }
  .es-corps { display:grid; grid-template-columns:auto auto minmax(0,1fr); gap:10px; align-items:start; margin-top:6px; }
  .es-corps[hidden], .es-vide[hidden] { display:none; }
  .es-ciel, .es-globe { width:150px; height:150px; display:block; }
  .es-ciel { background:#0E1720; border-radius:8px; }
  .es-ciel .es-pouls { fill:none; stroke:#3FE0C8; stroke-width:1.4; animation:es-pouls 2s ease-out infinite; transform-box:fill-box; transform-origin:center; }
  @keyframes es-pouls { from { opacity:.9; transform:scale(.6); } to { opacity:0; transform:scale(1.8); } }
  @media (prefers-reduced-motion:reduce) { .es-ciel .es-pouls { animation:none; opacity:.6; } }
  .es-infos { display:grid; grid-template-columns:repeat(auto-fill, minmax(170px, 1fr)); gap:3px 14px; font-size:12.5px; align-content:start; min-width:0; }
  .es-infos > div { min-width:0; overflow-wrap:anywhere; }
  .es-infos .es-l { color:var(--gris); font-size:11px; margin-right:6px; }
  .es-infos .es-v { font-variant-numeric:tabular-nums; }
  .es-infos .es-u { color:var(--gris); font-size:11px; }
  .es-infos .es-t { grid-column:1 / -1; color:var(--cyan); font-weight:600; font-size:12px; }
  .es-vide { margin-top:6px; color:var(--gris); }
  .es.plie .es-corps, .es.plie .es-vide { display:none; }
  /* Beside the live picture, above the sub-tabs; on a short screen a little smaller. */
  @media (min-width:1101px) {
    .sx > .es { grid-column:2; grid-row:1; }
    .sx > .st { grid-column:2; grid-row:2; }
    .sx > .sx-direct { grid-row:1 / span 2; }
    .sx:has(> .es[hidden]) { row-gap:0; }
  }
  @media (min-width:1101px) and (min-height:600px) { #v-sstv .sx { grid-template-rows:auto minmax(0,1fr); } }
  @media (min-width:1101px) and (max-height:820px) { .es-ciel, .es-globe { width:118px; height:118px; } .es-infos { font-size:12px; gap:1px 12px; } }
  /* Stacked under the live picture on a narrow screen. */
  @media (max-width:600px) {
    .es-corps { grid-template-columns:1fr 1fr; }
    .es-ciel, .es-globe { width:100%; height:auto; aspect-ratio:1; }
    .es-infos { grid-column:1 / -1; grid-template-columns:1fr; }
    .es-tete .es-mode { flex-basis:100%; order:9; }
  }
"""

    const val HTML = """
  <section class="carte es" id="es" aria-label="Satellite suivi">
    <div class="es-tete">
      <button type="button" id="es-plie" aria-expanded="true" aria-controls="es-corps" onclick="esPlie()" title="Replier ou déplier l’encart (gardé sur ce navigateur)">▾</button>
      <span class="es-titre">Satellite suivi</span><span class="es-nom" id="es-nom"></span><span id="es-ap"></span>
      <span class="es-mode petit" id="es-mode"></span>
      <span class="es-compte val ambre" id="es-compte"></span>
      <button type="button" onclick="esCache(true)" title="Masquer l’encart (le remettre : SSTV › Réglages)" aria-label="Masquer l’encart du satellite suivi">✕</button>
    </div>
    <div class="es-corps" id="es-corps">
      <svg class="es-ciel" id="es-ciel" viewBox="-110 -110 220 220" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Le ciel : le passage, son AOS, son LOS, son point le plus haut et le satellite maintenant"></svg>
      <canvas class="es-globe" id="es-globe" role="img" aria-label="Le globe : le satellite, sa trace au sol, son empreinte et la station"></canvas>
      <div class="es-infos" id="es-infos" aria-live="off"></div>
    </div>
    <div class="es-vide" id="es-vide" hidden>Aucun satellite suivi · <a href="#" onclick="vue('station'); setTimeout(function () { g('cherche').focus(); }); return false">choisir un satellite</a></div>
  </section>
"""

    /** Its switch, in SSTV › Réglages. */
    const val REGLAGE = """
    <div class="carte">
      <div class="t">Affichage</div>
      <label class="petit" style="display:block;margin-top:0" title="Le satellite suivi rappelé à côté de l’image en direct : son ciel, le globe, les heures du passage (sur ce navigateur)"><input type="checkbox" id="es-montre" onchange="esCache(!this.checked)"> Rappeler le satellite suivi dans un encart (ciel, globe, passage)</label>
    </div>
"""

    /** The pure part: worked out under node. */
    const val PUR = """
/* ------------------------------------------------------------ SSTV inset: pure part (EncartSstvJsTest) */
/** [lat], [lon] (degrees) seen from above [c] {lat, lon}, orthographic: [x east, y north, z towards the eye], unit sphere (z < 0: behind). */
function esOrtho(c, lat, lon) {
  const r = Math.PI / 180, f = lat * r, l = (lon - c.lon) * r, f0 = c.lat * r;
  return [Math.cos(f) * Math.sin(l),
    Math.cos(f0) * Math.sin(f) - Math.sin(f0) * Math.cos(f) * Math.cos(l),
    Math.sin(f0) * Math.sin(f) + Math.cos(f0) * Math.cos(f) * Math.cos(l)];
}
/** Halfway between [a] and [b] {lat, lon} on the great circle (the globe turned to show both); [a] alone without [b]. */
function esCentre(a, b) {
  if (!b) return { lat: a.lat, lon: a.lon };
  const r = Math.PI / 180, v = p => [Math.cos(p.lat * r) * Math.cos(p.lon * r), Math.cos(p.lat * r) * Math.sin(p.lon * r), Math.sin(p.lat * r)];
  const A = v(a), B = v(b), m = [A[0] + B[0], A[1] + B[1], A[2] + B[2]], n = Math.hypot(m[0], m[1], m[2]);
  if (n < 1e-6) return { lat: a.lat, lon: a.lon };
  return { lat: Math.asin(m[2] / n) / r, lon: Math.atan2(m[1], m[0]) / r };
}
/** The time of the pass [pa]'s highest point (ms), from its arc [arc] ([[az, el]…] spread evenly from AOS to LOS), else its trace, else halfway. */
function esTca(pa, arc) {
  if (!pa) return null;
  const l = arc && arc.length > 2 ? arc : pa.trace;
  if (!l || l.length < 2) return Math.round((pa.aos + pa.los) / 2);
  let i = 0;
  for (let k = 1; k < l.length; k++) if (l[k][1] > l[i][1]) i = k;
  return Math.round(pa.aos + (pa.los - pa.aos) * i / (l.length - 1));
}
/** The pass to show at [n] (ms): the one under way ({quoi: 'encours'}), else the next one of [pa] and [suivants] ('prochain'), else none ('aucun'). */
function esEtat(pa, suivants, n) {
  if (pa && pa.aos <= n && n < pa.los) return { quoi: 'encours', p: pa };
  const l = [pa].concat(suivants || []).filter(p => p && p.aos > n).sort((a, b) => a.aos - b.aos);
  return l.length ? { quoi: 'prochain', p: l[0] } : { quoi: 'aucun', p: null };
}
/**
 * The SSTV downlink of satellite [catnum]: the campaign under way (auto-pilot [ap]), else an active campaign for it, else a transmitter
 * of [emetteurs] that says SSTV. {mode, hz, emetteur} ('' and 0 when unknown).
 */
function esDescente(catnum, ap, emetteurs) {
  const r = { mode: '', hz: 0, emetteur: '' };
  const cs = ap && !ap.absent && ap.campagnes ? ap.campagnes.filter(c => c.catnum === catnum && c.active !== false) : [];
  const ec = ap && !ap.absent && ap.enCours ? ap.enCours : null;
  const c = (ec && cs.find(x => x.id === ec.campagne)) || cs[0];
  if (c) { r.mode = (ec && ec.campagne === c.id ? ec.mode : c.mode) || ''; r.hz = (ec && ec.campagne === c.id ? ec.freqHz : c.freqHz) || 0; r.emetteur = c.emetteur || ''; }
  if (!r.hz) {
    const t = (emetteurs || []).find(x => /sstv/i.test((x.desc || '') + ' ' + (x.mode || '')));
    if (t) { r.hz = t.dlBas || 0; r.emetteur = r.emetteur || t.desc || ''; }
  }
  return r;
}
/** [ms] in this computer's time and in UTC, the one chosen ([enUtc]: the page's UTC box) first: « 14:32 · 12:32 UTC »; once when they agree. */
function esHeures(ms, enUtc) {
  const d = new Date(ms), p2 = x => String(x).padStart(2, '0');
  const loc = p2(d.getHours()) + ':' + p2(d.getMinutes()), u = p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes());
  if (loc === u && d.getTimezoneOffset() === 0) return u + ' UTC';
  return enUtc ? u + ' UTC · ' + loc + ' loc.' : loc + ' · ' + u + ' UTC';
}
"""

    private const val MODULE = """
/* ------------------------------------------------------------ SSTV inset: the satellite followed beside the live picture */
let esPlieV = false, esCacheV = false;
try { esPlieV = localStorage.getItem('sstv-encart-plie') === '1'; esCacheV = localStorage.getItem('sstv-encart-cache') === '1'; } catch (x) {}
function esPlie() {
  esPlieV = !esPlieV;
  try { localStorage.setItem('sstv-encart-plie', esPlieV ? '1' : '0'); } catch (x) {}
  esRend(true);
}
function esCache(c) {
  esCacheV = !!c;
  try { localStorage.setItem('sstv-encart-cache', esCacheV ? '1' : '0'); } catch (x) {}
  const b = g('es-montre'); if (b) b.checked = !esCacheV;
  esRend(true);
}
/* The coastline, asked once (the browser keeps it a day). */
let esCotes = null, esCotesDemande = false;
async function esChargeCotes() {
  if (esCotesDemande) return;
  esCotesDemande = true;
  try { esCotes = await (await fetch('api/carte/traits')).json(); } catch (x) { esCotes = null; }
  esGlobeCle = ''; esRend(true);
}
const esKm = x => Math.round(x).toLocaleString('fr-FR');
const esLigne = (l, v, u) => '<div><span class="es-l">' + l + '</span><span class="es-v">' + v + '</span>' + (u ? ' <span class="es-u">' + u + '</span>' : '') + '</div>';
/* The pass's extra marks on the Station tab's sky: the arc of a pass that is not the station's, LOS, the highest point, a ring round the satellite. */
function esCielPlus(et) {
  const p = et.p, f = q => q.map(x => x.toFixed(1));
  let s = '';
  const arc = p && E.passage && p.aos === E.passage.aos && E.arc && E.arc.length > 1 ? E.arc : p && p.trace && p.trace.length > 1 ? p.trace : null;
  if (arc && arc !== E.arc) {
    s += '<polyline fill="none" stroke="#3FE0C8" stroke-opacity=".55" stroke-width="1.6" stroke-dasharray="4 3" points="' + arc.map(q => f(polaire(q[0], q[1])).join(',')).join(' ') + '"/>';
    const a = f(polaire(arc[0][0], arc[0][1]));
    s += '<text x="' + a[0] + '" y="' + (a[1] - 4) + '" fill="#3FE0C8" font-size="7" text-anchor="middle">AOS</text>';
  }
  if (arc) {
    const z = f(polaire(arc[arc.length - 1][0], arc[arc.length - 1][1]));
    s += '<text x="' + z[0] + '" y="' + (+z[1] + 10) + '" fill="#9AA7B4" font-size="7" text-anchor="middle">LOS</text>';
    let m = arc[0]; arc.forEach(q => { if (q[1] > m[1]) m = q; });
    const t = f(polaire(m[0], m[1]));
    s += '<circle cx="' + t[0] + '" cy="' + t[1] + '" r="2.6" fill="#FFB454"/><text x="' + t[0] + '" y="' + (t[1] - 5) + '" fill="#FFB454" font-size="7" text-anchor="middle">' + Math.round(p.elMax) + '°</text>';
  }
  const q = E.position;
  if (q && q.el >= 0) { const c = f(polaire(q.az, q.el)); s += '<circle class="es-pouls" cx="' + c[0] + '" cy="' + c[1] + '" r="7"/>'; }
  return s;
}
/* The globe's background: the sea lit from the Sun's side, the graticule, the coastline (kept while the view does not move). */
let esGlobeFond = null, esGlobeCle = '', esGlobeT = 0;
function esFond(N, R, cr, n) {
  const o = document.createElement('canvas'); o.width = o.height = N;
  const x = o.getContext('2d'), h = N / 2;
  const so = obSoleil(n, obGmst(n)), q = esOrtho(cr, so.dec, so.lon);
  const k = q[2] >= 0 ? 1 : Math.max(0, 1 + q[2] * 2);
  const gr = x.createRadialGradient(h + q[0] * R * .7, h - q[1] * R * .7, R * .05, h, h, R * 1.25);
  gr.addColorStop(0, 'rgb(' + Math.round(14 + 20 * k) + ',' + Math.round(36 + 40 * k) + ',' + Math.round(54 + 50 * k) + ')'); gr.addColorStop(1, '#08111A');
  x.fillStyle = gr; x.beginPath(); x.arc(h, h, R, 0, 7); x.fill();
  const P = (la, lo) => { const v = esOrtho(cr, la, lo); return [h + v[0] * R, h - v[1] * R, v[2] > 0]; };
  const trait = (pts, c, lw) => {
    x.beginPath(); let en = false;
    pts.forEach(p => { const a = P(p[0], p[1]); if (!a[2]) { en = false; return; } if (en) x.lineTo(a[0], a[1]); else { x.moveTo(a[0], a[1]); en = true; } });
    x.strokeStyle = c; x.lineWidth = lw; x.stroke();
  };
  for (let la = -60; la <= 60; la += 30) { const l = []; for (let lo = -180; lo <= 180; lo += 4) l.push([la, lo]); trait(l, 'rgba(120,150,170,.18)', N / 300); }
  for (let lo = -180; lo < 180; lo += 30) { const l = []; for (let la = -90; la <= 90; la += 4) l.push([la, lo]); trait(l, 'rgba(120,150,170,.18)', N / 300); }
  if (esCotes) {
    x.beginPath();
    esCotes.forEach(a => { let en = false; for (let i = 0; i + 1 < a.length; i += 2) { const p = P(a[i + 1], a[i]); if (!p[2]) { en = false; continue; } if (en) x.lineTo(p[0], p[1]); else { x.moveTo(p[0], p[1]); en = true; } } });
    x.strokeStyle = '#5E8FA3'; x.lineWidth = N / 220; x.lineJoin = 'round'; x.stroke();
  }
  x.strokeStyle = '#2A3647'; x.lineWidth = N / 150; x.beginPath(); x.arc(h, h, R, 0, 7); x.stroke();
  return o;
}
function esGlobe(n, force) {
  const c = g('es-globe'), W = c.clientWidth;
  if (!W || (!force && n - esGlobeT < 1000)) return;
  esGlobeT = n;
  if (!esCotes) esChargeCotes();
  const dpr = Math.min(2, window.devicePixelRatio || 1), N = Math.round(W * dpr);
  if (c.width !== N) { c.width = N; c.height = N; }
  const p = E.position, nous = E.observateur, s = E.suivi;
  const ct = esCentre(nous || { lat: 0, lon: 0 }, p ? { lat: p.lat, lon: p.lon } : null), cr = { lat: Math.round(ct.lat), lon: Math.round(ct.lon) };
  const h = N / 2, R = h - 2 * dpr;
  const cle = N + ':' + cr.lat + ',' + cr.lon + ':' + Math.floor(n / 300000) + ':' + !!esCotes;
  if (cle !== esGlobeCle || !esGlobeFond) { esGlobeFond = esFond(N, R, cr, n); esGlobeCle = cle; }
  const x = c.getContext('2d');
  x.clearRect(0, 0, N, N); x.drawImage(esGlobeFond, 0, 0);
  const P = (la, lo) => { const v = esOrtho(cr, la, lo); return [h + v[0] * R, h - v[1] * R, v[2] > 0]; };
  const trait = (pts, c2, lw, tirets) => {
    x.beginPath(); let en = false;
    pts.forEach(q => { const a = P(q[0], q[1]); if (!a[2]) { en = false; return; } if (en) x.lineTo(a[0], a[1]); else { x.moveTo(a[0], a[1]); en = true; } });
    x.setLineDash(tirets || []); x.strokeStyle = c2; x.lineWidth = lw; x.stroke(); x.setLineDash([]);
  };
  // The ground track (the Station map's): to come solid, gone dashed; asked again each minute.
  if (s && (!traceSol || traceSol.catnum !== s.catnum || n - traceVu > 60000) && n - traceVu > 3000) { traceVu = n; chargeTraceSol(); }
  if (s && traceSol && traceSol.catnum === s.catnum && traceSol.points.length > 1) {
    trait(traceSol.points.slice(traceSol.ici), 'rgba(63,224,200,.85)', 1.4 * dpr);
    trait(traceSol.points.slice(0, traceSol.ici + 1), 'rgba(154,167,180,.5)', 1.2 * dpr, [3 * dpr, 3 * dpr]);
  }
  if (p) {
    const lam = Math.acos(6371 / (6371 + Math.max(1, p.alt))), em = obEmpreinte(p.lat, p.lon, lam, 72);
    x.beginPath(); let en = false;
    em.forEach(q => { const a = P(q[0], q[1]); if (!a[2]) { en = false; return; } if (en) x.lineTo(a[0], a[1]); else { x.moveTo(a[0], a[1]); en = true; } });
    x.fillStyle = p.el >= 0 ? 'rgba(91,214,138,.14)' : 'rgba(63,224,200,.08)'; x.fill();
    x.strokeStyle = p.el >= 0 ? 'rgba(91,214,138,.8)' : 'rgba(63,224,200,.5)'; x.lineWidth = dpr; x.stroke();
  }
  const point = (la, lo, c2, r) => { const a = P(la, lo); if (!a[2]) return null; x.fillStyle = c2; x.beginPath(); x.arc(a[0], a[1], r * dpr, 0, 7); x.fill(); return a; };
  const st = nous ? point(nous.lat, nous.lon, '#FFB454', 3) : null;
  if (p) {
    const a = P(p.lat, p.lon);
    if (st && a[2] && p.el >= 0) { x.strokeStyle = 'rgba(255,180,84,.8)'; x.lineWidth = dpr; x.beginPath(); x.moveTo(st[0], st[1]); x.lineTo(a[0], a[1]); x.stroke(); }
    point(p.lat, p.lon, p.el >= 0 ? '#5BD68A' : '#3FE0C8', 4);
  }
}
/* Drawn with the station's state (each second) and when the tab opens: nothing while the tab is closed, folded parts skipped. */
let esCielVu = '', esInfosVu = '', esModeVu = '';
function esRend(force) {
  const b = g('es'); if (!b) return;
  b.hidden = esCacheV;
  if (esCacheV || !ouverte('sstv') || document.hidden || !E) return;
  b.classList.toggle('plie', esPlieV);
  g('es-plie').setAttribute('aria-expanded', String(!esPlieV)); txt('es-plie', esPlieV ? '▸' : '▾');
  const n = Date.now(), s = E.suivi;
  g('es-vide').hidden = !!s; g('es-corps').hidden = !s;
  txt('es-nom', s ? s.nom : '');
  const h = s ? apMarqueSat(s.catnum) : '', ap = g('es-ap');
  if (ap.dataset.h !== h) { ap.dataset.h = h; ap.innerHTML = h; }
  if (!s) { txt('es-compte', ''); if (esModeVu) { esModeVu = ''; g('es-mode').innerHTML = ''; } return; }
  const d = esDescente(s.catnum, AP, E.emetteurs), rx = E.ciel && E.ciel.dl;
  const mo = [d.mode ? 'SSTV <b>' + esc(d.mode) + '</b>' : '', d.hz ? '<b>' + mhz(d.hz) + '</b> MHz' : '', rx ? 'RX corrigé <b>' + mhz(rx) + '</b>' : ''].filter(x => x).join(' · ');
  if (mo !== esModeVu) { esModeVu = mo; g('es-mode').innerHTML = mo; g('es-mode').title = d.emetteur ? 'Émetteur : ' + d.emetteur : ''; }
  const p = E.position, et = esEtat(E.passage, E.passagesSuivi, n), pa = et.p;
  txt('es-compte', et.quoi === 'encours' ? 'LOS dans ' + duree(pa.los - n) : et.quoi === 'prochain' ? 'AOS dans ' + duree(pa.aos - n)
    : p && p.el >= 0 ? 'toujours en vue' : 'aucun passage prévu');
  g('es-compte').style.color = et.quoi === 'encours' ? 'var(--vert)' : '';
  if (esPlieV) return;
  const ciel = cielSvg() + esCielPlus(et);
  if (ciel !== esCielVu) { esCielVu = ciel; g('es-ciel').innerHTML = ciel; }
  const u = utc(), l = [];
  if (pa) {
    const tca = esTca(pa, pa.aos === (E.passage && E.passage.aos) ? E.arc : null);
    l.push('<div class="es-t">' + (et.quoi === 'encours' ? 'Passage en cours' : 'Prochain passage · ' + jourHeure(pa.aos)) + apMarque(s.catnum, pa.aos, pa.los) + '</div>');
    l.push(esLigne('AOS', esHeures(pa.aos, u), pa.azAos != null ? 'az ' + Math.round(pa.azAos) + '°' : ''));
    l.push(esLigne('TCA', esHeures(tca, u), 'él max ' + Math.round(pa.elMax) + '°'));
    l.push(esLigne('LOS', esHeures(pa.los, u), pa.azLos != null ? 'az ' + Math.round(pa.azLos) + '°' : ''));
    l.push(et.quoi === 'encours' ? esLigne('Reste', duree(pa.los - n), 'sur ' + duree(pa.los - pa.aos)) : esLigne('Dans', duree(pa.aos - n), 'durée ' + duree(pa.los - pa.aos)));
  } else l.push('<div class="es-t">' + (p && p.el >= 0 ? 'Toujours au-dessus de l’horizon' : E.calcul ? 'Calcul des passages…' : 'Aucun passage dans les 48 h') + '</div>');
  if (p) {
    l.push(esLigne(p.el >= 0 ? 'Maintenant' : 'Sous l’horizon', 'az ' + p.az.toFixed(0) + '° · él ' + p.el.toFixed(1) + '°'));
    l.push(esLigne('Distance', esKm(p.distance) + ' km', (p.vitesse > 0 ? 's’éloigne ' : 's’approche ') + Math.abs(p.vitesse).toFixed(1) + ' km/s'));
  }
  const html = l.join('');
  if (html !== esInfosVu) { esInfosVu = html; g('es-infos').innerHTML = html; }
  esGlobe(n, force);
}
(function () { const b = g('es-montre'); if (b) b.checked = !esCacheV; })();
document.addEventListener('visibilitychange', () => { if (!document.hidden) esRend(true); });
"""

    /** The page's script (after the station's: it draws with `cielSvg`, `traceSol`, `AP`). */
    val JS: String get() = PUR + MODULE
}
