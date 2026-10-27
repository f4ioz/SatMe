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
 * The « Orbites » tab, the globe of the Suivi tab and the journal's globe:
 * the Earth in 3D with the satellites going round it, the map of their
 * footprints, a pass played as an animation, a pass of the journal replayed
 * with its contacts and pictures. Six looks drawn from SatMe's own coastline;
 * cities, mountains, landmarks and scenery from [GlobeDonnees] (the page's
 * `GLOBE_MONDE`); the contacts of the log as dots, callsigns or squares.
 * The Earth is drawn by three.js and the orbits worked out by satellite.js,
 * both bundled with SatMe-PC and loaded only when a globe is first shown
 * (`api/lib/three.js`, `api/lib/satellite.js`): no Internet.
 *
 * Its pure part (footprint, night, passes, locators) runs in the page and
 * under node in OrbitesJsTest. No `$` in the text: it is a Kotlin template.
 */
object OrbitesJs {

    /** The page's script, in five parts (a constant holds at most 64 KB). */
    val JS: String get() = DEBUT + MILIEU + MONUMENTS + TRACES + FIN

    private const val DEBUT = """
/* ------------------------------------------------------------ pure part (OrbitesJsTest) */
/**
 * The footprint of a satellite above [lat], [lon] (degrees) seeing an angular radius [lam] (radians):
 * [[lat, lon]…] in degrees, longitudes continuous (beyond ±180 when it crosses the date line); one over
 * a pole is closed along that pole, so it fills the right side of the map.
 */
function obEmpreinte(lat, lon, lam, n) {
  n = n || 120;
  const f = lat * Math.PI / 180, L = lon * Math.PI / 180, pts = [];
  let ref = null;
  for (let i = 0; i <= n; i++) {
    const az = i / n * 2 * Math.PI;
    const la = Math.asin(Math.sin(f) * Math.cos(lam) + Math.cos(f) * Math.sin(lam) * Math.cos(az));
    let lo = (L + Math.atan2(Math.sin(az) * Math.sin(lam) * Math.cos(f), Math.cos(lam) - Math.sin(f) * Math.sin(la))) * 180 / Math.PI;
    if (ref != null) { while (lo - ref > 180) lo -= 360; while (lo - ref < -180) lo += 360; }
    ref = lo; pts.push([la * 180 / Math.PI, lo]);
  }
  const deg = lam * 180 / Math.PI;
  if (lat + deg > 90 || lat - deg < -90) { const p = lat + deg > 90 ? 90 : -90; pts.push([p, pts[pts.length - 1][1]], [p, pts[0][1]]); }
  return pts;
}
/** The night on the map, the Sun at declination [dec] over longitude [lonSoleil] (degrees): a polygon [[lat, lon]…] from −180 to 180, closed by the pole in the dark. */
function obNuit(dec, lonSoleil, pas) {
  pas = pas || 2;
  const t = Math.tan(dec * Math.PI / 180) || 1e-6, l = [];
  for (let lo = -180; lo <= 180; lo += pas) l.push([Math.atan(-Math.cos((lo - lonSoleil) * Math.PI / 180) / t) * 180 / Math.PI, lo]);
  const pole = dec < 0 ? 90 : -90;
  l.push([pole, 180], [pole, -180]);
  return l;
}
/**
 * The passes between [de] and [fin] (ms) of a satellite whose elevation at a time is [elev](t) (degrees, null when unknown):
 * scanned every [pas] ms, AOS and LOS found to the second, the highest point within. [{aos, los, tca, max}].
 */
function obPassages(elev, de, fin, pas, max) {
  pas = pas || 30000; max = max || 12;
  const l = [];
  const bord = (a, b, monte) => { for (let i = 0; i < 16 && b - a > 500; i++) { const m = (a + b) / 2, e = elev(m); if ((e != null && e > 0) === monte) b = m; else a = m; } return Math.round(b); };
  let prec = elev(de), tPrec = de, aos = prec != null && prec > 0 ? de : null;
  for (let t = de + pas; t <= fin + pas && l.length < max; t += pas) {
    const e = elev(t);
    if (e == null || prec == null) { prec = e; tPrec = t; continue; }
    if (prec <= 0 && e > 0) aos = bord(tPrec, t, true);
    if (prec > 0 && e <= 0 && aos != null) {
      const los = bord(tPrec, t, false);
      let haut = -90, tca = aos;
      const p = Math.max(1000, (los - aos) / 200);
      for (let u = aos; u <= los; u += p) { const x = elev(u); if (x != null && x > haut) { haut = x; tca = u; } }
      l.push({ aos: aos, los: los, tca: Math.round(tca), max: haut });
      aos = null;
    }
    prec = e; tPrec = t;
  }
  return l;
}
/** The Sun at [t] (ms): its direction in the inertial frame (unit), its declination and the longitude below it (degrees), given the sidereal angle [gmst] (radians). */
function obSoleil(t, gmst) {
  const n = t / 86400000 + 2440587.5 - 2451545.0, r = Math.PI / 180;
  const L = (280.460 + 0.9856474 * n) * r, gm = (357.528 + 0.9856003 * n) * r;
  const lam = L + (1.915 * Math.sin(gm) + 0.020 * Math.sin(2 * gm)) * r, eps = (23.439 - 4e-7 * n) * r;
  const x = Math.cos(lam), y = Math.cos(eps) * Math.sin(lam), z = Math.sin(eps) * Math.sin(lam);
  let lon = (Math.atan2(y, x) - gmst) / r;
  lon = ((lon % 360) + 540) % 360 - 180;
  return { eci: { x: x, y: y, z: z }, dec: Math.asin(z) / r, lon: lon };
}
/** The sidereal angle at [t] (ms), radians: close enough to satellite.js's for the Sun and the Moon. */
function obGmst(t) {
  const d = t / 86400000 + 2440587.5 - 2451545.0;
  return (((280.46061837 + 360.98564736629 * d) % 360) + 360) % 360 * Math.PI / 180;
}
/**
 * The Moon at [t] (ms), to a few tenths of a degree (the main terms of its longitude): its declination and right ascension,
 * the longitude below it (degrees), its distance (km), its phase [phase] 0 new … 0.5 full … 1, the share lit [lumiere] 0 … 1, its age in days.
 */
function obLune(t) {
  const r = Math.PI / 180, d = t / 86400000 + 2440587.5 - 2451545.0;
  const L = 218.316 + 13.176396 * d, M = (134.963 + 13.064993 * d) * r, F = (93.272 + 13.229350 * d) * r;
  const D = (297.850 + 12.190749 * d) * r, Ms = (357.529 + 0.98560028 * d) * r;
  const lam = (L + 6.289 * Math.sin(M) + 1.274 * Math.sin(2 * D - M) + 0.658 * Math.sin(2 * D) + 0.214 * Math.sin(2 * M) - 0.186 * Math.sin(Ms) - 0.114 * Math.sin(2 * F)) * r;
  const bet = (5.128 * Math.sin(F) + 0.281 * Math.sin(M + F) + 0.278 * Math.sin(M - F)) * r, eps = 23.439 * r;
  const x = Math.cos(bet) * Math.cos(lam), y = Math.cos(eps) * Math.cos(bet) * Math.sin(lam) - Math.sin(eps) * Math.sin(bet);
  const z = Math.sin(eps) * Math.cos(bet) * Math.sin(lam) + Math.cos(eps) * Math.sin(bet);
  const ra = Math.atan2(y, x) / r, dec = Math.asin(z) / r;
  // The Sun's ecliptic longitude, as obSoleil works it out: the phase is the Moon's lead on it.
  const Ls = 280.460 + 0.9856474 * d, gs = (357.528 + 0.9856003 * d) * r, lamS = Ls + 1.915 * Math.sin(gs) + 0.020 * Math.sin(2 * gs);
  const phase = ((((lam / r - lamS) % 360) + 360) % 360) / 360;
  const elong = Math.acos(Math.cos(bet) * Math.cos(lam - lamS * r));
  let lon = ra - obGmst(t) / r; lon = ((lon % 360) + 540) % 360 - 180;
  return { ra: ((ra % 360) + 360) % 360, dec: dec, lon: lon, dist: 385001 - 20905 * Math.cos(M), phase: phase, lumiere: (1 - Math.cos(elong)) / 2, age: phase * 29.530588 };
}
/** The Moon's lit part as an SVG path in a disc of radius [r] centred on 0, 0 (north up, as seen from the northern half). */
function obLunePath(phase, r) {
  const c = Math.cos(2 * Math.PI * phase), croit = phase < .5, rx = (r * Math.abs(c)).toFixed(2), R = r.toFixed(2);
  const parDroite = croit ? c > 0 : c < 0;
  return 'M0,-' + R + 'A' + R + ',' + R + ' 0 0 ' + (croit ? 1 : 0) + ' 0,' + R + 'A' + rx + ',' + R + ' 0 0 ' + (parDroite ? 0 : 1) + ' 0,-' + R + 'Z';
}
/** The Sun's elevation (degrees) at [t] (ms) seen from [lat], [lon]. */
function obHauteurSoleil(t, lat, lon) {
  const s = obSoleil(t, obGmst(t)), r = Math.PI / 180, f = lat * r, dc = s.dec * r;
  return Math.asin(Math.sin(f) * Math.sin(dc) + Math.cos(f) * Math.cos(dc) * Math.cos((lon - s.lon) * r)) / r;
}
/**
 * The station's next sunrise and sunset (the Sun's top edge on the horizon, −0.833°), dawn and dusk (civil, −6°) within the
 * 24 hours from [t]: {lever, coucher, aube, crepuscule} (ms, null when none: polar day or night).
 */
function obSoleilJour(t, lat, lon) {
  const h = u => obHauteurSoleil(u, lat, lon), o = { lever: null, coucher: null, aube: null, crepuscule: null };
  const bord = (a, b, seuil) => { const monte = h(b) > seuil; for (let i = 0; i < 14; i++) { const m = (a + b) / 2; if ((h(m) > seuil) === monte) b = m; else a = m; } return Math.round(b); };
  const pas = 600000;
  let prec = h(t);
  for (let u = t + pas; u <= t + 86400000 + pas; u += pas) {
    const e = h(u);
    [[-0.833, 'lever', 'coucher'], [-6, 'aube', 'crepuscule']].forEach(q => {
      if (prec <= q[0] && e > q[0] && o[q[1]] == null) o[q[1]] = bord(u - pas, u, q[0]);
      if (prec > q[0] && e <= q[0] && o[q[2]] == null) o[q[2]] = bord(u - pas, u, q[0]);
    });
    prec = e;
  }
  return o;
}
/** A Maidenhead locator (2, 4 or 6 characters): its square's centre and size in degrees {lat, lon, dlat, dlon}, null when it is not one. */
function obLocator(l) {
  l = String(l || '').trim().toUpperCase();
  const m0 = l.match(/^[A-R]{2}(?:[0-9]{2}(?:[A-X]{2})?)?/);
  if (!m0 || m0[0].length !== l.length) return null;
  let lon = (l.charCodeAt(0) - 65) * 20 - 180, lat = (l.charCodeAt(1) - 65) * 10 - 90, dlon = 20, dlat = 10;
  if (l.length >= 4) { lon += (+l[2]) * 2; lat += +l[3]; dlon = 2; dlat = 1; }
  if (l.length >= 6) { lon += (l.charCodeAt(4) - 65) * 5 / 60; lat += (l.charCodeAt(5) - 65) * 2.5 / 60; dlon = 5 / 60; dlat = 2.5 / 60; }
  return { lat: lat + dlat / 2, lon: lon + dlon / 2, dlat: dlat, dlon: dlon };
}
/**
 * A position at [t] along a track [[t, lat, lon, altKm]…] (sorted by time), longitudes interpolated the short way;
 * null outside it, or between two points more than [ecart] ms apart (two passes of a session: nothing recorded between).
 */
function obPisteA(l, t, ecart) {
  if (!l || !l.length || t < l[0][0] || t > l[l.length - 1][0]) return null;
  let a = 0, b = l.length - 1;
  while (b - a > 1) { const m = (a + b) >> 1; if (l[m][0] <= t) a = m; else b = m; }
  const p = l[a], q = l[b], k = q[0] > p[0] ? (t - p[0]) / (q[0] - p[0]) : 0;
  if (q[0] - p[0] > (ecart || 600000)) return null;
  let dl = q[2] - p[2]; if (dl > 180) dl -= 360; if (dl < -180) dl += 360;
  let lon = p[2] + dl * k; lon = ((lon + 540) % 360) - 180;
  return { lat: p[1] + (q[1] - p[1]) * k, lon: lon, alt: p[3] + (q[3] - p[3]) * k };
}
/**
 * What a satellite [alt] km above [lat], [lon] (degrees) sees: {lam, rayon} its footprint (the radio horizon) as an angle at
 * the Earth's centre (radians) and a radius along the ground (km); for a place [p] {lat, lon}, also {dist} its distance from
 * the point below (km), {marge} how far inside the edge it is (km, negative outside) and {el} the satellite's elevation there.
 */
function obVueSat(alt, lat, lon, p) {
  const R = 6378.137, r = Math.PI / 180, lam = Math.acos(R / (R + alt)), o = { lam: lam, rayon: R * lam };
  if (p) {
    const c = Math.sin(lat * r) * Math.sin(p.lat * r) + Math.cos(lat * r) * Math.cos(p.lat * r) * Math.cos((p.lon - lon) * r), g = Math.acos(Math.max(-1, Math.min(1, c)));
    o.dist = R * g; o.marge = R * (lam - g); o.el = Math.atan2(Math.cos(g) - R / (R + alt), Math.sin(g)) / r;
  }
  return o;
}
/**
 * « Vue du satellite »: the camera's distance from the Earth's centre (Earth radii), the satellite [r] radii out. [k] 0: on
 * the satellite; 1: drawn back along its vertical until its whole footprint holds in a view of half-angle [a] (radians);
 * never nearer than the satellite (a high one already sees all of it).
 */
function obReculSat(r, a, k) {
  const lam = Math.acos(1 / r), d = Math.cos(lam) + Math.sin(lam) / Math.tan(a);
  return r + Math.max(0, d - r) * Math.max(0, k);
}

/* ------------------------------------------------------------ the globes */
const Orbites = (function () {
  const RE = 6378.137, D2R = Math.PI / 180, R2D = 180 / Math.PI, CL = 299792.458;
  const PALETTE = ['#FF6B6B', '#FFD166', '#3FE0C8', '#5AB8FF', '#C792FF', '#FF9F43', '#FF5FA2', '#9BE15D', '#7EF0FF', '#FFA8C5', '#F7F06D', '#B0C4FF'];
  /* A journal session's companions, in the order chosen (as SatMe-PC's videos draw them: GlobeRenduPc.COULEURS_AUTRES). */
  const COMPAGNONS = ['#7CF29A', '#FF6B9A', '#B48CFF', '#FFD84D', '#4CC3FF', '#FF9F45', '#9AE6FF', '#F2A7FF'];
  const el = id => document.getElementById(id);
  const lit = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch (x) { return d; } };
  const garde = (k, v) => { try { localStorage.setItem(k, v); } catch (x) {} };
  // The station's routes: beside the page, or under SATME_RACINE (the journal's page sets it).
  const api = p => (window.SATME_RACINE || '') + p;
  const monde = () => typeof GLOBE_MONDE !== 'undefined' ? GLOBE_MONDE : { villes: [], sommets: [], monuments: [], decor: [] };
  const OB = { donnees: null, sats: [], obs: null, terres: null, style: lit('ob-style2', 'cartoon'), vue: lit('ob-vue', 'globe'), filtre: 'favoris',
    choisi: null, temps: { base: Date.now(), reel: performance.now(), vitesse: 1, pause: false }, anim: null, passages: [], passage: null,
    cam: lit('ob-cam', 'poursuite'), hauteur: parseFloat(lit('ob-hauteur', '3.5')) || 3.5,
    detail: Math.max(0, Math.min(2, parseInt(lit('ob-detail', '1'), 10) || 0)), contacts: lit('ob-contacts', 'aucun'), carnet: null, carnetLu: 0,
    villes: lit('ob-villes', 'toutes'), etiq: lit('ob-etiq', 'nom'), monuments: lit('ob-monuments', 'principaux'), monOff: [], tb: null,
    monTaille: Math.max(.5, Math.min(3, parseFloat(lit('ob-montaille', '1')) || 1)), traces: Math.max(0, Math.min(100, parseInt(lit('ob-traces', '60'), 10))) };
  if (isNaN(OB.traces)) OB.traces = 60;
  try { OB.monOff = JSON.parse(lit('ob-montypes-off', '[]')) || []; } catch (x) {}
  let libs = null, donnees = null, scenes = [], boucle = false, tab = null, suivi = null, rejeu = null, sked = null;

  /* Libraries and the coastline: fetched once, when a globe is first shown; the station's elements for the tab and the Suivi. */
  function script(src) { return new Promise((ok, ko) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = ko; document.head.appendChild(s); }); }
  function chargeLibs() {
    if (libs) return libs;
    libs = (async () => {
      if (typeof THREE === 'undefined') await script(api('api/lib/three.js'));
      if (typeof satellite === 'undefined') await script(api('api/lib/satellite.js'));
      OB.terres = await fetch(api('api/carte/traits')).then(r => r.json());
      OB.relief = await chargeRelief().catch(() => null);
      // The flags' font, before any label is drawn with it.
      if (document.fonts) await document.fonts.load('26px Drapeaux', String.fromCodePoint(0x1F1EB, 0x1F1F7)).catch(() => null);
    })();
    libs.catch(() => { libs = null; });
    return libs;
  }
  /**
   * The relief (ETOPO1, NOAA: 1440 × 720 cells of 0.25°, north at the top): each land cell's height in metres and its
   * shading lit from the north-west (−1 in the shade … 1 in the light), as SatMe-PC's videos work it out.
   */
  async function chargeRelief() {
    const im = new Image(); im.src = api('api/lib/relief.png'); await im.decode();
    const W = 1440, H = 720, c = document.createElement('canvas'); c.width = W; c.height = H;
    const x = c.getContext('2d'); x.drawImage(im, 0, 0); const px = x.getImageData(0, 0, W, H).data;
    const alt = new Float32Array(W * H), ombre = new Float32Array(W * H);
    for (let k = 0; k < W * H; k++) { const v = px[k * 4]; alt[k] = v >= 128 ? (v - 128) * 40 : 0; }
    for (let i = 1; i < H - 1; i++) {
      const la = 90 - i * .25, dx = .25 * 111320 * Math.max(.05, Math.cos(la * D2R)), dy = .25 * 111320;
      for (let j = 0; j < W; j++) {
        const k = i * W + j; if (alt[k] <= 0) continue;
        const gx = (alt[i * W + (j + 1) % W] - alt[i * W + (j + W - 1) % W]) / (2 * dx) * 12, gy = (alt[k - W] - alt[k + W]) / (2 * dy) * 12;
        const hs = (gx * .5 - gy * .5 + .7071) / Math.sqrt(gx * gx + gy * gy + 1);
        ombre[k] = Math.max(-1, Math.min(1, (hs - .7071) / .35));
      }
    }
    return { alt: alt, ombre: ombre };
  }
  /* The relief over a look's land: the high ground browner, the highest white, slopes lit or shaded. */
  function reliefSur(x, w, h, teinte, neige, force, fort) {
    const R = OB.relief; if (!R) return;
    const W = 1440, H = 720, c = document.createElement('canvas'); c.width = W; c.height = H;
    const cx = c.getContext('2d'), d = cx.createImageData(W, H), p = d.data;
    for (let k = 0; k < W * H; k++) {
      const a = R.alt[k]; if (a <= 0) continue;
      let r = 0, g = 0, b = 0, al = 0;
      // The cartoon's [fort]: the same numbers as the journal's videos (GlobeRenduPc).
      const t0 = fort ? 600 : 800, tl = fort ? 2400 : 2600, n0 = fort ? 3500 : 3800;
      if (teinte && a > t0) { const t = Math.min(1, (a - t0) / tl) * (fort ? .75 : .55); r = teinte[0]; g = teinte[1]; b = teinte[2]; al = t; }
      if (neige && a > n0) { const n = Math.min(1, (a - n0) / 1200) * (fort ? .9 : .8); r += (250 - r) * n; g += (252 - g) * n; b += (255 - b) * n; al = al + (1 - al) * n; }
      const o = R.ombre[k] * force;
      if (o < 0) { const s = -o * (fort ? .6 : .45); r *= 1 - s; g *= 1 - s; b *= 1 - s; al = al + (1 - al) * s; }
      else if (o > 0) { const s = o * .3; r += (255 - r) * s; g += (255 - g) * s; b += (255 - b) * s; al = al + (1 - al) * s * .6; }
      p[k * 4] = r; p[k * 4 + 1] = g; p[k * 4 + 2] = b; p[k * 4 + 3] = Math.round(al * 255);
    }
    cx.putImageData(d, 0, 0);
    x.save(); x.imageSmoothingEnabled = true; x.drawImage(c, 0, 0, w, h); x.restore();
  }
  function charge() {
    if (donnees) return donnees;
    donnees = (async () => { await chargeLibs(); prend(await fetch(api('api/orbites')).then(r => r.json())); })();
    donnees.catch(() => { donnees = null; });
    return donnees;
  }
  function prend(d) {
    OB.donnees = d; OB.obs = d.obs ? { latitude: d.obs.lat * D2R, longitude: d.obs.lon * D2R, height: (d.obs.alt || 0) / 1000 } : null;
    let coches = null; try { coches = JSON.parse(lit('ob-coches', 'null')); } catch (x) {}
    const avant = new Map(OB.sats.map(s => [s.catnum, s]));
    const fav = d.favoris || [];
    OB.sats = [];
    (d.sats || []).forEach(s => {
      const a = avant.get(s.catnum);
      if (a && a.l1 === s.l1 && a.l2 === s.l2) { a.fav = fav.includes(s.catnum); a.suivi = s.catnum === d.suivi; OB.sats.push(a); return; }
      let satrec = null; try { satrec = satellite.twoline2satrec(s.l1, s.l2); } catch (x) {}
      if (!satrec) return;
      const mm = parseFloat(s.l2.slice(52, 63)) || 15;
      OB.sats.push({ nom: s.nom, catnum: s.catnum, l1: s.l1, l2: s.l2, satrec: satrec, mm: mm, periode: 1440 / mm * 60000, dl: s.dl || 0,
        fav: fav.includes(s.catnum), suivi: s.catnum === d.suivi, on: a ? a.on : (coches ? coches.includes(s.catnum) : (fav.includes(s.catnum) || s.catnum === d.suivi)) });
    });
    OB.sats.sort((a, b) => (b.suivi - a.suivi) || (b.fav - a.fav) || (fav.indexOf(a.catnum) - fav.indexOf(b.catnum)) || a.nom.localeCompare(b.nom));
    OB.sats.forEach((s, i) => { if (!s.couleur) s.couleur = PALETTE[i % PALETTE.length]; });
    if (!OB.choisi || !OB.sats.includes(OB.choisi)) OB.choisi = OB.sats.find(s => s.suivi) || OB.sats.find(s => s.on) || OB.sats[0] || null;
    if (OB.choisi) OB.choisi.on = true;
    scenes.forEach(sc => { if (sc.mode !== 'rejeu' && sc.mode !== 'sked') sc.synchro(); });
  }
  async function rafraichit() { try { prend(await (await fetch(api('api/orbites'))).json()); if (tab) listeSats(); } catch (x) {} }
  /* The log's contacts, for their dots, callsigns or squares: read when asked for, again every five minutes. */
  async function chargeCarnet() {
    if (OB.contacts === 'aucun' || Date.now() - OB.carnetLu < 300000) return;
    OB.carnetLu = Date.now();
    try {
      const j = await (await fetch(api('api/carnet'))).json();
      OB.carnet = (j.contacts || []).filter(c => c.l && obLocator(c.l)).map(c => ({ c: c.c, l: String(c.l).toUpperCase(), t: c.t || 0 }));
      scenes.forEach(sc => sc.contacts());
    } catch (x) { OB.carnetLu = 0; }
  }

  /* Where a satellite is: from its elements (SGP4), or along a journal pass's recorded track. */
  function etat(s, t, obs) {
    obs = obs === undefined ? OB.obs : obs;
    const gm = satellite.gstime(new Date(t));
    let eci, vel, lat, lon, alt, ecf;
    const pp = s.piste ? obPisteA(s.piste, t) : null;
    if (s.satrec && !pp) {
      const r = satellite.propagate(s.satrec, new Date(t));
      if (!r || !r.position || typeof r.position !== 'object') return null;
      eci = r.position; vel = r.velocity;
      const gd = satellite.eciToGeodetic(eci, gm); lat = gd.latitude * R2D; lon = gd.longitude * R2D; alt = gd.height;
      ecf = satellite.eciToEcf(eci, gm);
    } else {
      const p = pp; if (!p) return null;
      lat = p.lat; lon = p.lon; alt = p.alt;
      ecf = satellite.geodeticToEcf({ latitude: lat * D2R, longitude: lon * D2R, height: alt });
      eci = satellite.ecfToEci(ecf, gm);
      const apres = obPisteA(s.piste, t + 1000), q = apres || obPisteA(s.piste, t - 1000), sg = apres ? 1 : -1;
      if (q) { const g2 = satellite.gstime(new Date(t + sg * 1000)), e2 = satellite.ecfToEci(satellite.geodeticToEcf({ latitude: q.lat * D2R, longitude: q.lon * D2R, height: q.alt }), g2);
        vel = { x: (e2.x - eci.x) * sg, y: (e2.y - eci.y) * sg, z: (e2.z - eci.z) * sg }; }
      else vel = { x: 0, y: 0, z: 1 };
    }
    const o = { eci: eci, vel: vel, lat: lat, lon: lon, alt: alt, az: 0, el: -90, dist: 0 };
    if (obs) { const la = satellite.ecfToLookAngles(obs, ecf); o.az = la.azimuth * R2D; o.el = la.elevation * R2D; o.dist = la.rangeSat; }
    return o;
  }
  function maintenant() {
    if (OB.anim) return OB.anim.t;
    const T = OB.temps; return T.pause ? T.base : T.base + (performance.now() - T.reel) * T.vitesse;
  }
  function recale(v) { const T = OB.temps; T.base = maintenant(); T.reel = performance.now(); if (v != null) T.vitesse = v; }

  /* ------------------------------------------------------------ the looks, drawn from the coastline */
  function chemin(w, h) {
    const p = new Path2D();
    for (const l of OB.terres || []) {
      p.moveTo((l[0] + 180) / 360 * w, (90 - l[1]) / 180 * h);
      for (let i = 2; i < l.length; i += 2) p.lineTo((l[i] + 180) / 360 * w, (90 - l[i + 1]) / 180 * h);
      p.closePath();
    }
    return p;
  }
  function grille(x, w, h, c, pas, larg, tirets) {
    x.save(); x.strokeStyle = c; x.lineWidth = larg; if (tirets) x.setLineDash(tirets);
    for (let lo = -180; lo <= 180; lo += pas) { x.beginPath(); x.moveTo((lo + 180) / 360 * w, 0); x.lineTo((lo + 180) / 360 * w, h); x.stroke(); }
    for (let la = -90 + pas; la < 90; la += pas) { x.beginPath(); x.moveTo(0, (90 - la) / 180 * h); x.lineTo(w, (90 - la) / 180 * h); x.stroke(); }
    x.restore();
  }
  function grain(x, w, h, force, taille) {
    const c = document.createElement('canvas'); c.width = w / taille; c.height = h / taille;
    const cx = c.getContext('2d'), d = cx.createImageData(c.width, c.height);
    for (let i = 0; i < d.data.length; i += 4) { const v = Math.random() * 255; d.data[i] = d.data[i + 1] = d.data[i + 2] = v; d.data[i + 3] = 255; }
    cx.putImageData(d, 0, 0);
    x.save(); x.globalAlpha = force; x.globalCompositeOperation = 'overlay'; x.drawImage(c, 0, 0, w, h); x.restore();
  }
  let masque = null;
  function surTerre(lat, lon) {
    if (!masque) { const c = document.createElement('canvas'); c.width = 1024; c.height = 512; const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fill(chemin(1024, 512)); masque = x.getImageData(0, 0, 1024, 512).data; }
    const px = Math.min(1023, Math.max(0, Math.floor((lon + 180) / 360 * 1024))), py = Math.min(511, Math.max(0, Math.floor((90 - lat) / 180 * 512)));
    return masque[(py * 1024 + px) * 4] > 128;
  }
  function bandes(x, h, stops) { const gr = x.createLinearGradient(0, 0, 0, h); stops.forEach(s => gr.addColorStop((90 - s[0]) / 180, s[1])); return gr; }
  /* [ville]: the cities' dots and names; [relief]: the mountains' colours (null: none, the relief's shading alone); [carton]: the cartoon's landmarks and scenery. */
  const STYLES = {
    realiste: { nom: 'Réaliste', fond: '#000004', etoiles: 0xffffff, nuit: 'rgba(0,4,20,.55)', encre: null, ville: '#E8EEF4', relief: null,
      peint(x, w, h) {
        x.fillStyle = bandes(x, h, [[90, '#1d3d5c'], [45, '#0b3463'], [0, '#0c4d80'], [-45, '#0b3463'], [-90, '#1d3d5c']]); x.fillRect(0, 0, w, h);
        const t = chemin(w, h);
        x.save(); x.strokeStyle = 'rgba(80,170,200,.35)'; x.lineWidth = w / 300; x.stroke(t); x.restore();
        x.save(); x.clip(t);
        x.fillStyle = bandes(x, h, [[90, '#f2f6f8'], [72, '#e3ebee'], [64, '#6f7f5a'], [55, '#3d6331'], [44, '#567436'], [36, '#8f8a4f'], [28, '#c8a56b'], [20, '#bf9a5e'], [12, '#4f7a30'], [0, '#2d6526'],
          [-12, '#3c6f2b'], [-24, '#b99657'], [-32, '#a98f58'], [-44, '#557a3a'], [-56, '#6b7a5e'], [-63, '#e9eff2'], [-90, '#f4f7f9']]);
        x.fillRect(0, 0, w, h); x.restore();
        reliefSur(x, w, h, [140, 112, 82], true, 1);
        grain(x, w, h, .18, 8);
        const gl = x.createLinearGradient(0, 0, 0, h * .09); gl.addColorStop(0, 'rgba(240,246,250,.95)'); gl.addColorStop(1, 'rgba(240,246,250,0)');
        x.fillStyle = gl; x.fillRect(0, 0, w, h * .09);
      } },
    cartoon: { nom: 'Dessin animé', fond: '#0d1b4b', etoiles: 0xfff2a8, nuit: 'rgba(20,10,70,.35)', encre: null, ville: '#FFFFFF', relief: [0xa0703f, 0xffffff], carton: true,
      peint(x, w, h) {
        x.fillStyle = '#35bdf5'; x.fillRect(0, 0, w, h);
        const t = chemin(w, h);
        x.save(); x.lineJoin = 'round';
        x.strokeStyle = 'rgba(170,240,255,.55)'; x.lineWidth = w / 70; x.stroke(t);
        x.strokeStyle = 'rgba(210,250,255,.8)'; x.lineWidth = w / 160; x.stroke(t);
        x.fillStyle = bandes(x, h, [[90, '#ffffff'], [70, '#ffffff'], [64, '#8be06a'], [42, '#7bd85b'], [33, '#f4d77e'], [20, '#f2cf6c'], [11, '#5fc94a'], [-10, '#5fc94a'], [-22, '#f2cf6c'],
          [-32, '#9ddc62'], [-58, '#8be06a'], [-63, '#ffffff'], [-90, '#ffffff']]);
        x.fill(t);
        reliefSur(x, w, h, [186, 128, 72], true, 1.3, true);
        x.strokeStyle = '#173f2a'; x.lineWidth = w / 1100; x.stroke(t);
        x.restore();
      } },
    holo: { nom: 'Hologramme', fond: '#00060c', etoiles: 0x3fe0ff, nuit: 'rgba(0,0,0,0)', encre: null, ville: '#7FF4FF', relief: null,
      peint(x, w, h) {
        x.fillStyle = '#00121c'; x.fillRect(0, 0, w, h);
        grille(x, w, h, 'rgba(60,230,255,.22)', 10, w / 1400); grille(x, w, h, 'rgba(60,230,255,.4)', 30, w / 900);
        const t = chemin(w, h);
        x.fillStyle = 'rgba(60,230,255,.10)'; x.fill(t);
        x.save(); x.shadowColor = '#3fe8ff'; x.shadowBlur = w / 200; x.strokeStyle = '#7ff4ff'; x.lineWidth = w / 900; x.stroke(t); x.restore();
        x.fillStyle = 'rgba(0,0,0,.18)'; for (let y = 0; y < h; y += 4) x.fillRect(0, y, w, 1);
      } },
    points: { nom: 'Globe à points', fond: '#03050d', etoiles: 0x8fa8ff, nuit: 'rgba(0,0,10,.45)', encre: null, ville: '#C8D6FF', relief: null,
      peint(x, w, h) {
        x.fillStyle = '#060a1a'; x.fillRect(0, 0, w, h);
        const p = w / 220; x.fillStyle = '#86a6ff';
        for (let y = p / 2; y < h; y += p) for (let X = p / 2; X < w; X += p) if (surTerre(90 - y / h * 180, X / w * 360 - 180)) { x.beginPath(); x.arc(X, y, p * .32, 0, 7); x.fill(); }
      } },
    radar: { nom: 'Radar rétro', fond: '#000300', etoiles: 0x3cff7a, nuit: 'rgba(0,0,0,.35)', encre: '#5dff8f', ville: '#9DFFB8', relief: null,
      peint(x, w, h) {
        const gr = x.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, w * .6); gr.addColorStop(0, '#04260f'); gr.addColorStop(1, '#010c04');
        x.fillStyle = gr; x.fillRect(0, 0, w, h);
        grille(x, w, h, 'rgba(60,255,120,.25)', 15, w / 1200, [w / 300, w / 300]);
        const t = chemin(w, h);
        x.fillStyle = 'rgba(40,255,110,.07)'; x.fill(t);
        x.save(); x.shadowColor = '#2f6'; x.shadowBlur = w / 300; x.strokeStyle = '#5dff8f'; x.lineWidth = w / 1000; x.stroke(t); x.restore();
      } },
    vintage: { nom: 'Carte ancienne', fond: '#140e08', etoiles: 0xf0d9a0, nuit: 'rgba(60,35,10,.30)', encre: '#6b3f17', ville: '#4A2E14', relief: null,
      peint(x, w, h) {
        x.fillStyle = '#dcc79a'; x.fillRect(0, 0, w, h);
        const t = chemin(w, h);
        x.save(); x.lineJoin = 'round';
        for (let i = 6; i >= 1; i--) { x.strokeStyle = 'rgba(110,80,40,' + (.05 + .03 * (6 - i)) + ')'; x.lineWidth = i * w / 420; x.stroke(t); x.strokeStyle = '#dcc79a'; x.lineWidth = i * w / 420 - w / 900; x.stroke(t); }
        x.restore();
        grille(x, w, h, 'rgba(110,70,30,.35)', 15, w / 1500, [w / 200, w / 400]);
        x.fillStyle = '#efe0b8'; x.fill(t); reliefSur(x, w, h, null, false, 1); x.strokeStyle = '#5a3a1c'; x.lineWidth = w / 900; x.stroke(t);
        grain(x, w, h, .25, 2);
        const v = x.createRadialGradient(w / 2, h / 2, h * .3, w / 2, h / 2, w * .62); v.addColorStop(0, 'rgba(0,0,0,0)'); v.addColorStop(1, 'rgba(90,55,20,.35)');
        x.fillStyle = v; x.fillRect(0, 0, w, h);
      } }
  };
  const textures = {};
  function texture(k) {
    // 4096 × 2048: sharp enough when the globe is brought close.
    if (!textures[k]) { const c = document.createElement('canvas'); c.width = 4096; c.height = 2048; STYLES[k].peint(c.getContext('2d'), 4096, 2048); textures[k] = c; }
    return textures[k];
  }
  /* The cities shown at a detail level: the great ones always, the others as the detail grows. */
  /* The cities shown: [nom, lat, lon, rang, pays]; the capitals by population (40, 100 or all as the detail grows). */
  const villesVues = () => OB.villes === 'aucune' ? [] : OB.villes === 'capitales'
    ? (monde().capitales || []).slice(0, [40, 100, 999][OB.detail]).map((c, i) => [c[0], c[1], c[2], i < 40 ? 1 : 2, c[3]])
    : monde().villes.filter(v => v[3] <= OB.detail + 1).map(v => v.concat(['']));
  /** A country's flag (its ISO code as regional indicators, drawn by the page's « Drapeaux » font). */
  const drapeau = iso => iso && iso.length === 2 ? String.fromCodePoint(0x1F1E6 + iso.charCodeAt(0) - 65, 0x1F1E6 + iso.charCodeAt(1) - 65) : '';
  /** A city's label as asked: its name, its flag, or both. */
  const nomVille = v => { const d = drapeau(v[4]); return OB.etiq === 'drapeau' && d ? d : OB.etiq === 'nomdrapeau' && d ? d + ' ' + v[0] : v[0]; };

  /* ------------------------------------------------------------ 3D pieces */
  function eciVers3(p, f) { f = f || 1 / RE; return new THREE.Vector3(p.x * f, p.z * f, -p.y * f); }
  function local(lat, lon, r) { r = r || 1; const a = lat * D2R, b = lon * D2R; return new THREE.Vector3(r * Math.cos(a) * Math.cos(b), r * Math.sin(a), -r * Math.cos(a) * Math.sin(b)); }
  const HAUT = () => new THREE.Vector3(0, 1, 0);
  /** [o] stood up on the ground at [lat], [lon] (its y axis along the vertical), turned by [cap] radians. */
  function pose(o, lat, lon, cap, r) { const p = local(lat, lon, r || 1); o.position.copy(p); o.quaternion.setFromUnitVectors(HAUT(), p.clone().normalize()); if (cap) o.rotateY(cap); return o; }
  function dirCiel(stW, az, elv) {
    const z = stW.clone().normalize(), est = new THREE.Vector3(0, 1, 0).cross(z).normalize(), nord = z.clone().cross(est).normalize();
    return nord.multiplyScalar(Math.cos(elv * D2R) * Math.cos(az * D2R)).add(est.multiplyScalar(Math.cos(elv * D2R) * Math.sin(az * D2R))).add(z.multiplyScalar(Math.sin(elv * D2R))).multiplyScalar(.5);
  }
  let gradToon = null;
  function toon() { if (!gradToon) { gradToon = new THREE.DataTexture(new Uint8Array([70, 150, 255]), 3, 1, THREE.LuminanceFormat); gradToon.minFilter = gradToon.magFilter = THREE.NearestFilter; gradToon.needsUpdate = true; } return gradToon; }
  const matsToon = {};
  function mt(c) { if (!matsToon[c]) matsToon[c] = new THREE.MeshToonMaterial({ color: c, gradientMap: toon() }); return matsToon[c]; }
  function bord(couleur, puissance, force, r, dos) {
    const m = new THREE.ShaderMaterial({
      uniforms: { c: { value: new THREE.Color(couleur) }, p: { value: puissance }, f: { value: force } },
      vertexShader: 'varying vec3 n; varying vec3 v; void main(){ n = normalize(normalMatrix*normal); vec4 q = modelViewMatrix*vec4(position,1.0); v = normalize(-q.xyz); gl_Position = projectionMatrix*q; }',
      fragmentShader: dos ? 'uniform vec3 c; uniform float p; uniform float f; varying vec3 n; varying vec3 v; void main(){ float i = pow(max(0.0, 0.72 + dot(n, v)), p)*f; gl_FragColor = vec4(c*i, i); }'
        : 'uniform vec3 c; uniform float p; uniform float f; varying vec3 n; varying vec3 v; void main(){ float i = pow(1.0 - abs(dot(n, v)), p)*f; gl_FragColor = vec4(c*i, i); }',
      blending: THREE.AdditiveBlending, transparent: true, depthWrite: false, side: dos ? THREE.BackSide : THREE.FrontSide
    });
    return new THREE.Mesh(new THREE.SphereGeometry(r || 1.002, 96, 64), m);
  }
  function nuages() {
    const c = document.createElement('canvas'); c.width = 1024; c.height = 512; const x = c.getContext('2d');
    [[0, 8, 60], [45, 12, 50], [-45, 12, 50], [60, 10, 25], [-60, 10, 25], [25, 15, 18], [-25, 15, 18]].forEach(b => {
      for (let i = 0; i < b[2]; i++) {
        const cx = Math.random() * 1024, cy = (90 - (b[0] + (Math.random() - .5) * 2 * b[1])) / 180 * 512, r = 6 + Math.random() * 26;
        [-1024, 0, 1024].forEach(dx => { const gr = x.createRadialGradient(cx + dx, cy, 0, cx + dx, cy, r); gr.addColorStop(0, 'rgba(255,255,255,.75)'); gr.addColorStop(1, 'rgba(255,255,255,0)'); x.fillStyle = gr; x.beginPath(); x.ellipse(cx + dx, cy, r * 2.2, r, 0, 0, 7); x.fill(); });
      }
    });
    const m = new THREE.Mesh(new THREE.SphereGeometry(1.012, 96, 64), new THREE.MeshLambertMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false, opacity: .8 }));
    m.userData.tourne = .004; return m;
  }
  function nuagesCartoon() {
    const gp = new THREE.Group(), mat = mt(0xffffff);
    for (let i = 0; i < 9; i++) {
      const n = new THREE.Group();
      for (let j = 0; j < 5; j++) { const b = new THREE.Mesh(new THREE.SphereGeometry(.026 + Math.random() * .02, 16, 12), mat); b.position.set((j - 2) * .03, (Math.random() - .5) * .03, 0); b.scale.z = .45; n.add(b); }
      n.position.copy(local((Math.random() - .5) * 120, Math.random() * 360 - 180, 1.085)); n.lookAt(0, 0, 0); gp.add(n);
    }
    gp.userData.tourne = .01; return gp;
  }
  function pointsTerre() {
    const n = 26000, pos = [], or = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < n; i++) {
      const y = 1 - (i / (n - 1)) * 2, r = Math.sqrt(1 - y * y), th = or * i;
      const la = Math.asin(y) * R2D, lo = ((Math.atan2(-Math.sin(th) * r, Math.cos(th) * r) * R2D) + 540) % 360 - 180;
      if (surTerre(la, lo)) { const v = local(la, lo, 1.003); pos.push(v.x, v.y, v.z); }
    }
    const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    return new THREE.Points(gm, new THREE.PointsMaterial({ color: 0x8fb0ff, size: .0075 }));
  }
  /* A name over the globe. [genre]: 'sat' (a satellite), 'fort' (the satellite chosen), 'ville', 'contact', or nothing (the station). */
  function etiquette(texte, couleur, style, genre) {
    const c = document.createElement('canvas'), x = c.getContext('2d'), fs = genre === 'ville' || genre === 'contact' ? 26 : 30;
    const police = (genre === 'ville' && style === 'vintage' ? 'italic ' : '') + (genre === 'fort' ? '800 ' : '600 ') + fs + 'px Drapeaux, system-ui, -apple-system, "Segoe UI", sans-serif';
    x.font = police; const w = Math.ceil(x.measureText(texte).width) + 26; c.width = w; c.height = fs + 16; x.font = police;
    const H = c.height;
    if (genre === 'ville' || genre === 'contact') {
      x.lineJoin = 'round'; x.lineWidth = 6; x.strokeStyle = style === 'vintage' ? 'rgba(239,224,184,.9)' : 'rgba(0,0,0,.75)';
      x.textBaseline = 'middle'; x.strokeText(texte, 13, H / 2); x.fillStyle = couleur; x.fillText(texte, 13, H / 2);
    } else {
      if (genre === 'fort') { x.fillStyle = couleur; x.strokeStyle = style === 'cartoon' ? '#0a1230' : '#ffffff'; x.lineWidth = 4; x.beginPath(); if (x.roundRect) x.roundRect(2, 2, w - 4, H - 4, H / 2 - 2); else x.rect(2, 2, w - 4, H - 4); x.fill(); x.stroke(); x.fillStyle = '#0a1230'; }
      else if (style === 'cartoon') { x.fillStyle = '#fff'; x.strokeStyle = '#0a1230'; x.lineWidth = 4; x.beginPath(); if (x.roundRect) x.roundRect(2, 2, w - 4, H - 4, H / 2 - 2); else x.rect(2, 2, w - 4, H - 4); x.fill(); x.stroke(); x.fillStyle = '#0a1230'; }
      else if (style === 'vintage') { x.fillStyle = 'rgba(239,224,184,.9)'; x.fillRect(0, 4, w, H - 8); x.fillStyle = '#4a2e14'; }
      else { x.fillStyle = 'rgba(0,0,0,.55)'; x.fillRect(0, 4, w, H - 8); x.fillStyle = couleur; }
      x.textBaseline = 'middle'; x.fillText(texte, 13, H / 2 + 1);
    }
    const t = new THREE.CanvasTexture(c); t.minFilter = THREE.LinearFilter;
    const enSurface = genre === 'ville' || genre === 'contact';
    const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: enSurface, depthWrite: false, transparent: true, sizeAttenuation: false }));
    s.userData.ratio = w / H; s.renderOrder = enSurface ? 5 : 10; return s;
  }
  function rond(couleur, anneau) {
    const c = document.createElement('canvas'); c.width = c.height = 64; const x = c.getContext('2d');
    if (anneau) { x.strokeStyle = couleur; x.lineWidth = 5; x.beginPath(); x.arc(32, 32, 26, 0, 7); x.stroke(); x.lineWidth = 2; x.globalAlpha = .5; x.beginPath(); x.arc(32, 32, 20, 0, 7); x.stroke(); }
    else { const gr = x.createRadialGradient(32, 32, 0, 32, 32, 32); gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(.25, couleur); gr.addColorStop(1, 'rgba(0,0,0,0)'); x.fillStyle = gr; x.fillRect(0, 0, 64, 64); }
    return new THREE.CanvasTexture(c);
  }
  function lueur(couleur) { return new THREE.Sprite(new THREE.SpriteMaterial({ map: rond(couleur), blending: THREE.AdditiveBlending, depthWrite: false, transparent: true, sizeAttenuation: false })); }
  let texPoint = null;
  function point() { if (!texPoint) { const c = document.createElement('canvas'); c.width = c.height = 32; const x = c.getContext('2d'); x.fillStyle = '#fff'; x.beginPath(); x.arc(16, 16, 13, 0, 7); x.fill(); x.lineWidth = 3; x.strokeStyle = 'rgba(0,0,0,.6)'; x.stroke(); texPoint = new THREE.CanvasTexture(c); } return texPoint; }
  function engin(couleur) {
    const gp = new THREE.Group();
    gp.add(new THREE.Mesh(new THREE.BoxGeometry(.012, .012, .018), new THREE.MeshStandardMaterial({ color: 0xd8dde6, metalness: .6, roughness: .35, emissive: new THREE.Color(couleur), emissiveIntensity: .25 })));
    const pm = new THREE.MeshStandardMaterial({ color: 0x1d3f8f, metalness: .3, roughness: .4, emissive: 0x0a1a44, side: THREE.DoubleSide });
    [-1, 1].forEach(s => { const p = new THREE.Mesh(new THREE.PlaneGeometry(.03, .011), pm); p.position.x = s * .022; gp.add(p); });
    return gp;
  }
  const NC = 72, RC = 5;
  function calotte() {
    const pos = new Float32Array((1 + NC * RC) * 3), idx = [];
    for (let r = 0; r < RC; r++) for (let i = 0; i < NC; i++) {
      const b = 1 + r * NC + i, c = 1 + r * NC + (i + 1) % NC;
      if (r === 0) idx.push(0, b, c); else { const a = 1 + (r - 1) * NC + i, d = 1 + (r - 1) * NC + (i + 1) % NC; idx.push(a, b, c, a, c, d); }
    }
    const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.BufferAttribute(pos, 3)); gm.setIndex(idx); return gm;
  }
  function poseCalotte(gm, ligne, lat, lon, lam) {
    const p = gm.attributes.position.array, l = ligne.geometry.attributes.position.array, f = lat * D2R, L = lon * D2R, rr = 1.004;
    const pt = (d, az) => { const la = Math.asin(Math.sin(f) * Math.cos(d) + Math.cos(f) * Math.sin(d) * Math.cos(az)); const lo = L + Math.atan2(Math.sin(az) * Math.sin(d) * Math.cos(f), Math.cos(d) - Math.sin(f) * Math.sin(la)); return local(la * R2D, lo * R2D, rr); };
    const c0 = local(lat, lon, rr); p[0] = c0.x; p[1] = c0.y; p[2] = c0.z;
    for (let r = 1; r <= RC; r++) for (let i = 0; i < NC; i++) { const v = pt(lam * r / RC, i / NC * 2 * Math.PI); p.set([v.x, v.y, v.z], (1 + (r - 1) * NC + i) * 3); if (r === RC) l.set([v.x, v.y, v.z], i * 3); }
    gm.attributes.position.needsUpdate = true; ligne.geometry.attributes.position.needsUpdate = true; gm.computeBoundingSphere(); ligne.geometry.computeBoundingSphere();
  }
  /* A lat/lon rectangle laid on the globe (a locator's square). */
  function carreSol(la0, lo0, dla, dlo, r) {
    const n = 4, pos = [], idx = [];
    for (let i = 0; i <= n; i++) for (let j = 0; j <= n; j++) { const v = local(la0 + dla * i / n, lo0 + dlo * j / n, r); pos.push(v.x, v.y, v.z); }
    for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) { const a = i * (n + 1) + j; idx.push(a, a + 1, a + n + 1, a + 1, a + n + 2, a + n + 1); }
    const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3)); gm.setIndex(idx); return gm;
  }

"""

    private const val MILIEU = """  /* ------------------------------------------------------------ the cartoon's world: mountains, landmarks, scenery */
  const cone = (() => { let g0 = null; return () => { if (!g0) { g0 = new THREE.ConeGeometry(1, 1, 9); g0.translate(0, .5, 0); } return g0; }; })();
  const boite = (l, h, p, c, y) => { const m = new THREE.Mesh(new THREE.BoxGeometry(l, h, p), mt(c)); m.position.y = (y || 0) + h / 2; return m; };
  const cyl = (r1, r2, h, c, y, seg) => { const m = new THREE.Mesh(new THREE.CylinderGeometry(r1, r2, h, seg || 10), mt(c)); m.position.y = (y || 0) + h / 2; return m; };
  const pointe = (r, h, c, y, seg) => { const m = new THREE.Mesh(new THREE.ConeGeometry(r, h, seg || 8), mt(c)); m.position.y = (y || 0) + h / 2; return m; };
  const boule = (r, c, y) => { const m = new THREE.Mesh(new THREE.SphereGeometry(r, 14, 10), mt(c)); m.position.y = y || 0; return m; };
  /* Each landmark drawn small and simple, about 0.03 of the Earth's radius high: cartoon scale. */
  function monument(id) {
    const g = new THREE.Group(), k = .001;
    const add = (...l) => l.forEach(o => g.add(o));
    switch (id) {
      case 'tour_eiffel': {
        const p = new THREE.Mesh(new THREE.ConeGeometry(9 * k, 30 * k, 4, 1, true), mt(0x6b4a2b)); p.position.y = 15 * k; p.rotation.y = Math.PI / 4;
        add(p, boite(10 * k, 1.2 * k, 10 * k, 0x8a5f37, 8 * k), boite(6 * k, 1 * k, 6 * k, 0x8a5f37, 17 * k), pointe(.8 * k, 6 * k, 0x6b4a2b, 30 * k, 4));
        break; }
      case 'liberte':
        add(boite(8 * k, 8 * k, 8 * k, 0xc9b28a), cyl(2.6 * k, 3.4 * k, 14 * k, 0x5fb79a, 8 * k), boule(2.4 * k, 0x5fb79a, 24 * k), cyl(.7 * k, .7 * k, 8 * k, 0x5fb79a, 22 * k),
          boule(1.6 * k, 0xffc21a, 31 * k)); g.children[g.children.length - 2].position.x = 2.4 * k; g.children[g.children.length - 1].position.x = 2.4 * k; break;
      case 'pyramides':
        [[0, 0, 14], [-12, 8, 10], [10, 10, 8]].forEach(q => { const p = pointe(q[2] * k * .8, q[2] * k, 0xe2c27a, 0, 4); p.position.x = q[0] * k; p.position.z = q[1] * k; add(p); }); break;
      case 'big_ben': {
        add(boite(5 * k, 22 * k, 5 * k, 0xc8a46a), pointe(4 * k, 8 * k, 0x3d4f5f, 22 * k, 4));
        const c = new THREE.Mesh(new THREE.CircleGeometry(1.7 * k, 16), new THREE.MeshBasicMaterial({ color: 0xffffff })); c.position.set(0, 18 * k, 2.6 * k); add(c); break; }
      case 'taj_mahal':
        add(boite(16 * k, 7 * k, 16 * k, 0xf4f1ea), boule(5 * k, 0xf8f6f0, 10 * k), pointe(1 * k, 4 * k, 0xd9c48a, 14.5 * k));
        [[-1, -1], [1, -1], [-1, 1], [1, 1]].forEach(q => { const m = cyl(.8 * k, .9 * k, 13 * k, 0xf4f1ea); m.position.x = q[0] * 9 * k; m.position.z = q[1] * 9 * k; add(m); }); break;
      case 'opera_sydney':
        [0, 1, 2].forEach(i => { const s = new THREE.Mesh(new THREE.SphereGeometry(5 * k, 12, 8, 0, Math.PI, 0, Math.PI / 2), mt(0xffffff)); s.scale.set(.6, 1.4, 1); s.position.set((i - 1) * 6 * k, 0, 0); s.rotation.y = -Math.PI / 2; add(s); }); break;
      case 'christ': {
        add(pointe(10 * k, 10 * k, 0x5c8a3a, 0, 9), boite(2 * k, 12 * k, 2 * k, 0xf2efe6, 9 * k), boite(12 * k, 1.8 * k, 1.8 * k, 0xf2efe6, 17 * k), boule(1.3 * k, 0xf2efe6, 22 * k)); break; }
      case 'fuji':
        add(pointe(16 * k, 18 * k, 0x6f7fa6, 0, 12), pointe(6.5 * k, 7.3 * k, 0xffffff, 10.8 * k, 12)); break;
      case 'kremlin':
        add(boite(14 * k, 7 * k, 10 * k, 0xc0392b));
        [[-4, 0xf1c40f], [0, 0x27ae60], [4, 0x2980b9]].forEach(q => { const t = cyl(1.5 * k, 1.5 * k, 6 * k, 0xc0392b, 7 * k); t.position.x = q[0] * k; const b = boule(2.2 * k, q[1], 14.5 * k); b.position.x = q[0] * k; const p = pointe(1 * k, 4 * k, q[1], 15.5 * k); p.position.x = q[0] * k; add(t, b, p); }); break;
      case 'colisee': {
        const c = new THREE.Mesh(new THREE.CylinderGeometry(9 * k, 9 * k, 8 * k, 20, 1, true), mt(0xd8b27e)); c.position.y = 4 * k; c.material.side = THREE.DoubleSide; c.scale.z = .75; add(c); break; }
      case 'golden_gate':
        [-8, 8].forEach(x => { const t = boite(1.6 * k, 20 * k, 1.6 * k, 0xc0392b); t.position.x = x * k; add(t); });
        { const d = boite(30 * k, 1.2 * k, 3 * k, 0xc0392b, 6 * k); add(d); } break;
      case 'moulin': {
        add(cyl(3 * k, 4.5 * k, 12 * k, 0xe8d8b8), pointe(3.4 * k, 4 * k, 0x7a4a2a, 12 * k));
        const ailes = new THREE.Group(); [0, 1].forEach(i => { const a = new THREE.Mesh(new THREE.BoxGeometry(16 * k, 1.6 * k, .3 * k), mt(0xffffff)); a.rotation.z = i * Math.PI / 2; ailes.add(a); });
        ailes.position.set(0, 12 * k, 4 * k); ailes.userData.anime = (o, t) => { o.rotation.z = t / 900; }; add(ailes); break; }
      case 'muraille':
        for (let i = 0; i < 6; i++) { const b = boite(6 * k, 3 * k, 2 * k, 0xb59a6a); b.position.x = (i - 2.5) * 5.5 * k; b.position.z = (i % 2 ? 1.5 : -1.5) * k; b.rotation.y = (i % 2 ? .5 : -.5); add(b); if (i % 2 === 0) { const t = boite(3 * k, 5 * k, 3 * k, 0xa88c5c); t.position.copy(b.position); t.position.y = 0; add(t); } } break;
      case 'moai':
        add(boite(5 * k, 12 * k, 4 * k, 0x7d7d7d), boite(5.5 * k, 2 * k, 5 * k, 0x6a6a6a, 10 * k)); break;
      case 'fusee': {
        add(cyl(1.6 * k, 1.6 * k, 16 * k, 0xffffff, 2 * k), pointe(1.6 * k, 5 * k, 0xe74c3c, 18 * k));
        [0, 1, 2].forEach(i => { const f = boite(.4 * k, 4 * k, 3 * k, 0xe74c3c, 1.5 * k); f.rotation.y = i * 2.1; f.translateZ(1.6 * k); add(f); });
        const fl = pointe(1.3 * k, 4 * k, 0xffa020, -2 * k); fl.rotation.x = Math.PI; fl.position.y = 1 * k; fl.userData.anime = (o, t) => { o.scale.y = .8 + .4 * Math.abs(Math.sin(t / 90)); }; add(fl); break; }
      case 'phare': {
        [0, 1, 2, 3].forEach(i => add(cyl(2.4 * k - i * .3 * k, 2.4 * k - (i + 1) * .3 * k, 4 * k, i % 2 ? 0xffffff : 0xd63031, i * 4 * k)));
        const l = boule(1.4 * k, 0xffe066, 17.5 * k); l.material = new THREE.MeshBasicMaterial({ color: 0xffe066 }); add(l, pointe(1.8 * k, 2 * k, 0x2d3436, 18.5 * k)); break; }
    }
    return g;
  }
  function decor(type) {
    const g = new THREE.Group(), k = .001;
    if (type === 'bateau') {
      const b = new THREE.Group();
      const coque = new THREE.Mesh(new THREE.BoxGeometry(9 * k, 2.4 * k, 3.4 * k), mt(0x8e5a2e)); coque.position.y = 1.2 * k; b.add(coque);
      const voile = new THREE.Mesh(new THREE.ConeGeometry(3 * k, 8 * k, 3), mt(0xffffff)); voile.position.y = 6.4 * k; voile.scale.z = .25; b.add(voile);
      // It sails round a small circle: a pivot turning under it (the group itself stays stood on the sea).
      const piv = new THREE.Group(); b.position.x = 25 * k; piv.add(b); g.add(piv);
      g.userData.anime = (o, t) => { piv.rotation.y = t / 9000 + o.userData.phase; b.position.y = .4 * k * Math.sin(t / 400 + o.userData.phase); };
    } else if (type === 'baleine') {
      const c = new THREE.Mesh(new THREE.SphereGeometry(4 * k, 14, 10), mt(0x2c3e70)); c.scale.set(1.8, .8, 1); g.add(c);
      const q = new THREE.Mesh(new THREE.ConeGeometry(2.4 * k, 4 * k, 3), mt(0x2c3e70)); q.position.set(-8 * k, 1 * k, 0); q.rotation.z = Math.PI / 2; q.scale.z = .3; g.add(q);
      const jet = new THREE.Mesh(new THREE.ConeGeometry(1.2 * k, 5 * k, 6), new THREE.MeshBasicMaterial({ color: 0xbfefff, transparent: true, opacity: .8 })); jet.position.y = 5 * k; g.add(jet);
      g.userData.anime = (o, t) => { const s = Math.sin(t / 700 + o.userData.phase); c.position.y = q.position.y = s * 1.2 * k; jet.visible = s > .6; };
    } else if (type === 'foret') {
      for (let i = 0; i < 7; i++) { const a = new THREE.Group(); a.add(cyl(.5 * k, .6 * k, 2 * k, 0x6d4c2f)); a.add(pointe(2.6 * k, 7 * k, i % 2 ? 0x2e8b3d : 0x3faa4a, 2 * k)); a.position.set((Math.random() - .5) * 26 * k, 0, (Math.random() - .5) * 26 * k); a.scale.setScalar(.8 + Math.random() * .5); g.add(a); }
    } else if (type === 'manchot') {
      for (let i = 0; i < 4; i++) { const m = new THREE.Group(); m.add(boule(1.6 * k, 0x1e272e, 2 * k)); const v = boule(1.2 * k, 0xffffff, 2 * k); v.position.z = .7 * k; m.add(v); m.add(boule(1 * k, 0x1e272e, 4.2 * k)); const b = pointe(.4 * k, 1 * k, 0xf39c12, 4 * k); b.rotation.x = Math.PI / 2; b.position.z = 1 * k; m.add(b); m.position.set((i - 1.5) * 4 * k, 0, (i % 2) * 3 * k); g.add(m); }
    }
    return g;
  }

  /**
   * One globe on [canvas]. [mode]: 'tab' (the Orbites tab: the satellites ticked, the camera dragged or flying a pass),
   * 'suivi' (the Suivi tab: the satellite followed with its orbit and footprint, the favourites as small lights, real time),
   * 'rejeu' (the journal: one pass replayed from its recorded track, with its contacts and pictures at their time),
   * 'sked' (the Sked tab: a common window replayed, both stations, the track and where both see the satellite).
   */
  function Scene(canvas, mode) {
    const sc = { canvas: canvas, mode: mode, objets: new Map(), style: null, rj: null, sk: null };
    // The journal's and the Sked's: their own station and satellite, their own clock.
    const rejoue = mode === 'rejeu' || mode === 'sked';
    const rendu = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, logarithmicDepthBuffer: true });
    rendu.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(42, 1, .0002, 200);
    const terre = new THREE.Group(); scene.add(terre);
    const soleil = new THREE.DirectionalLight(0xffffff, 1.25), amb = new THREE.AmbientLight(0x9fb0d0, .18); scene.add(soleil, amb);
    let sol = null, extras = [], mondeGp = new THREE.Group(), contactsGp = new THREE.Group(), rejeuGp = new THREE.Group(), monGp = new THREE.Group(), animes = [];
    terre.add(mondeGp, contactsGp, rejeuGp, monGp);
    const etoiles = (() => {
      const n = 2500, p = new Float32Array(n * 3);
      for (let i = 0; i < n; i++) { const v = new THREE.Vector3(Math.random() * 2 - 1, Math.random() * 2 - 1, Math.random() * 2 - 1); if (v.lengthSq() < 1e-4) v.set(1, 0, 0); v.normalize().multiplyScalar(80 + Math.random() * 40); p.set([v.x, v.y, v.z], i * 3); }
      const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.BufferAttribute(p, 3));
      const m = new THREE.Points(gm, new THREE.PointsMaterial({ size: 1.4, sizeAttenuation: false, color: 0xffffff, transparent: true, opacity: .85 }));
      scene.add(m); return m;
    })();
    // The station: a pin and a ring breathing round it.
    const station = new THREE.Group(), posStation = new THREE.Vector3();
    let nomStation = etiquette('Station', '#FFB454');
    const pin = new THREE.Mesh(new THREE.ConeGeometry(.008, .03, 16), new THREE.MeshBasicMaterial({ color: 0xffb454 }));
    const anneau = new THREE.Mesh(new THREE.RingGeometry(.012, .016, 40), new THREE.MeshBasicMaterial({ color: 0xffb454, transparent: true, side: THREE.DoubleSide, depthWrite: false }));
    station.add(pin, anneau); terre.add(station); scene.add(nomStation);
    // The Sked's other station: its own pin, pink as on its map.
    const autre = new THREE.Group(), posAutre = new THREE.Vector3();
    let nomAutre = etiquette('Lui', '#E86FA8');
    const pin2 = new THREE.Mesh(new THREE.ConeGeometry(.008, .03, 16), new THREE.MeshBasicMaterial({ color: 0xe86fa8 }));
    const anneau2 = new THREE.Mesh(new THREE.RingGeometry(.012, .016, 40), new THREE.MeshBasicMaterial({ color: 0xe86fa8, transparent: true, side: THREE.DoubleSide, depthWrite: false }));
    const rayon2 = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), new THREE.LineBasicMaterial({ color: 0xe86fa8, transparent: true, opacity: .9 }));
    autre.add(pin2, anneau2); terre.add(autre); scene.add(nomAutre, rayon2); autre.visible = nomAutre.visible = rayon2.visible = false;
    const cardinaux = [['N', 0], ['E', 90], ['S', 180], ['O', 270]].map(c => { const s = etiquette(c[0], '#FFB454'); s.userData.az = c[1]; s.visible = false; scene.add(s); return s; });
    // The satellite chosen: a ring turning round it.
    const cible = new THREE.Sprite(new THREE.SpriteMaterial({ map: rond('#FFFFFF', true), depthTest: false, transparent: true, sizeAttenuation: false })); cible.renderOrder = 9; scene.add(cible);
    // « Vue du satellite »: the day / night line on the ground (a circle square to the Sun), and its figures over the globe.
    const terminateur = (() => {
      const pts = []; for (let i = 0; i < 180; i++) { const a = i / 180 * 2 * Math.PI; pts.push(new THREE.Vector3(Math.cos(a) * 1.004, Math.sin(a) * 1.004, 0)); }
      const l = new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(pts), new THREE.LineBasicMaterial({ color: 0xffc857, transparent: true, opacity: .75 }));
      l.visible = false; scene.add(l); return l;
    })();
    sc.regard = { x: 0, y: 0 }; sc.vueSat = false;
    let infoSat = null, infoT = 0;
    sc.cacheInfo = () => { if (infoSat && !infoSat.hidden) infoSat.hidden = true; };
    // [sobre]: the Vitrine's, nothing to touch (no hints).
    function vueSatInfo(o, t, now, D, sobre) {
      if (!infoSat) {
        infoSat = document.createElement('div'); infoSat.className = 'ob-vuesat'; infoSat.setAttribute('aria-live', 'off');
        Object.assign(infoSat.style, { position: 'absolute', zIndex: 3, pointerEvents: 'none', maxWidth: '300px', padding: '6px 9px', borderRadius: '8px',
          background: 'rgba(8,12,24,.74)', border: '1px solid rgba(255,255,255,.14)', color: '#E8EEF8', font: '12px/1.45 system-ui, sans-serif', boxSizing: 'border-box' });
        const p = canvas.parentElement; if (getComputedStyle(p).position === 'static') p.style.position = 'relative'; p.appendChild(infoSat);
      }
      infoSat.hidden = false;
      if (now - infoT < 400) return; infoT = now;
      // Beside the tab's globe, middle right (its tools above, its instruments below); over the others' bottom left corner (their own choices at the top).
      // A narrow globe (a phone): smaller, without the hints.
      const etroit = canvas.clientWidth < 560;
      Object.assign(infoSat.style, { fontSize: etroit ? '11px' : '12px', maxWidth: etroit ? '62%' : '300px' });
      if (mode === 'tab') Object.assign(infoSat.style, { right: '10px', left: 'auto', top: '50%', transform: 'translateY(-50%)' });
      else Object.assign(infoSat.style, { left: (canvas.offsetLeft + 8) + 'px', top: (canvas.offsetTop + canvas.offsetHeight - 8) + 'px', right: 'auto', transform: 'translateY(-100%)' });
      const en = /^en/i.test(document.documentElement.lang || ''), e = o.e, v = obVueSat(e.alt, e.lat, e.lon), km = x => Math.round(x).toLocaleString(en ? 'en-GB' : 'fr-FR') + ' km';
      const so = obHauteurSoleil(t, e.lat, e.lon), haut = (D - 1) * RE - e.alt;
      const lieu = (nom, coul, q) => {
        const w = obVueSat(e.alt, e.lat, e.lon, q);
        return '<div><span style="color:' + coul + '">●</span> ' + echappe(nom) + ' : ' + (w.marge >= 0 ? (en ? 'in view, ' : 'dans l’empreinte, ') + km(w.marge) + (en ? ' from the edge, el. ' : ' du bord, él. ') + w.el.toFixed(0) + '°'
          : (en ? 'out of view, ' : 'hors de l’empreinte, à ') + km(-w.marge) + (en ? ' beyond the edge' : ' du bord')) + '</div>';
      };
      let h = '<div><b>' + (en ? 'Seen from ' : 'Vue de ') + echappe(o.s.nom) + '</b></div>' +
        '<div>' + (en ? 'Altitude ' : 'Altitude ') + km(e.alt) + (en ? ' · footprint radius ' : ' · rayon de l’empreinte ') + km(v.rayon) + '</div>' +
        '<div>' + (en ? 'Below it: ' : 'Sous lui : ') + (so > 0 ? (en ? 'day' : 'jour') + ' (' + (en ? 'Sun ' : 'Soleil ') + so.toFixed(0) + '°)' : so > -6 ? (en ? 'twilight' : 'crépuscule') : (en ? 'night' : 'nuit')) + '</div>';
      const l = sc.lieu();
      if (l) h += lieu(l.indicatif || (en ? 'My station' : 'Ma station'), '#FFB454', l);
      if (mode === 'sked' && sc.sk && sc.sk.autre) h += lieu(sc.sk.autre.indicatif || sc.sk.autre.locator || (en ? 'The other station' : 'L’autre station'), '#E86FA8', sc.sk.autre);
      if (mode === 'tab' && OB.contacts !== 'aucun' && OB.carnet) {
        if (!sc.carnetPts || sc.carnetPts.src !== OB.carnet) { const m = new Map(); OB.carnet.forEach(c => { const q = obLocator(c.l); if (q && !m.has(c.c)) m.set(c.c, q); }); sc.carnetPts = { src: OB.carnet, l: [...m.values()] }; }
        const n = sc.carnetPts.l.filter(q => obVueSat(e.alt, e.lat, e.lon, q).marge >= 0).length;
        h += '<div><span style="color:#FF4FA3">●</span> ' + n + (en ? ' station' + (n > 1 ? 's' : '') + ' of the log in view' : ' station' + (n > 1 ? 's' : '') + ' du carnet dans l’empreinte') + '</div>';
      }
      if (!etroit && !sobre) h += '<div style="opacity:.7">' + (haut > 5 ? (en ? 'Camera ' : 'Caméra ') + km(haut) + (en ? ' above it (wheel: nearer)' : ' au-dessus de lui (molette : plus près)') : (en ? 'Camera on the satellite' : 'Caméra sur le satellite')) +
        (en ? ' · drag: look around, double-click: back' : ' · glisser : regarder ailleurs, double-clic : revenir') + '</div>';
      if (infoSat.texte !== h) { infoSat.texte = h; infoSat.innerHTML = h; }
    }
    sc.lieu = () => mode === 'rejeu' ? (sc.rj && sc.rj.station) : mode === 'sked' ? (sc.sk && sc.sk.moi) : (OB.donnees && OB.donnees.obs ? { lat: OB.donnees.obs.lat, lon: OB.donnees.obs.lon, indicatif: OB.donnees.indicatif, locator: OB.donnees.locator } : null);
    sc.obs = () => { if (!rejoue) return OB.obs; const l = sc.lieu(); return l ? { latitude: l.lat * D2R, longitude: l.lon * D2R, height: 0 } : null; };
    const epingle = (d, pos, pi, an) => {
      const p = local(d.lat, d.lon, 1);
      pos.copy(local(d.lat, d.lon, 1.0003));
      pi.position.copy(p.clone().multiplyScalar(1.015)); pi.lookAt(0, 0, 0); pi.rotateX(-Math.PI / 2);
      an.position.copy(p.clone().multiplyScalar(1.003)); an.lookAt(p.clone().multiplyScalar(2));
    };
    sc.placeStation = () => {
      const a = mode === 'sked' && sc.sk && sc.sk.autre;
      if (a) { epingle(a, posAutre, pin2, anneau2); scene.remove(nomAutre); nomAutre = etiquette(a.indicatif || a.locator || 'Lui', '#E86FA8', sc.style); scene.add(nomAutre); }
      const d = sc.lieu(); if (!d) { station.visible = nomStation.visible = false; return; }
      epingle(d, posStation, pin, anneau);
      scene.remove(nomStation); nomStation = etiquette((d.indicatif || 'Station') + (d.locator ? ' · ' + d.locator : ''), '#FFB454', sc.style); scene.add(nomStation);
    };

    /* Cities, mountains, landmarks and scenery, as the look and the detail ask. */
    sc.monde = () => {
      while (mondeGp.children.length) mondeGp.remove(mondeGp.children[0]);
      animes = [];
      const S = STYLES[sc.style], M = monde(), carton = !!S.carton;
      if (S.relief && OB.detail >= 1) {
        const [cr, cn] = S.relief, mr = sc.style === 'cartoon' ? mt(0xb07a45) : sc.style === 'realiste' ? new THREE.MeshPhongMaterial({ color: cr, flatShading: true }) : new THREE.MeshLambertMaterial({ color: cr, flatShading: true });
        const mn = cn == null ? null : sc.style === 'cartoon' ? mt(cn) : new THREE.MeshLambertMaterial({ color: cn, flatShading: true });
        let graine = 7; const hasard = () => { graine = (graine * 9301 + 49297) % 233280; return graine / 233280; };
        // The summits of the relief (GlobeDonnees.SOMMETS), sized by their height: 0.25 at 1 000 m … 1 at 5 000 m.
        M.sommets.forEach(p => {
          const k = Math.max(.25, Math.min(1, (p[2] - 900) / 4100));
          const h = (carton ? .012 + .03 * k : .006 + .016 * k) * (.85 + .3 * hasard()), ray = h * (carton ? .72 : .9);
          const m = new THREE.Group(), c = new THREE.Mesh(cone(), mr); c.scale.set(ray, h, ray); m.add(c);
          if (mn && p[2] >= 2500) { const n2 = new THREE.Mesh(cone(), mn); n2.scale.set(ray * .4, h * .4, ray * .4); n2.position.y = h * .6 + .0002; m.add(n2); }
          mondeGp.add(pose(m, p[0], p[1], hasard() * 3, .999));
        });
      }
      // The landmarks: as chosen (any look); the journal's globe its videos' own, on the cartoon.
      monumentsMonde(monGp, sc.style, mode !== 'rejeu' ? '' : carton && OB.detail >= 1 ? 'rejeu' : 'aucun', animes);
      if (carton && OB.detail >= 1) {
        M.decor.forEach((d, i) => {
          if (d[0] !== 'bateau' && OB.detail < 2) return;
          const o = decor(d[0]); o.userData.phase = i * 1.7; mondeGp.add(pose(o, d[1], d[2], i, d[0] === 'bateau' || d[0] === 'baleine' ? 1.001 : .999)); if (o.userData.anime) animes.push(o);
        });
      }
      // The cities: a dot, their name (hidden behind the Earth like the dot).
      const vs = villesVues();
      if (vs.length) {
        const pos = []; vs.forEach(v => { const p = local(v[1], v[2], 1.002); pos.push(p.x, p.y, p.z); });
        const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
        mondeGp.add(new THREE.Points(gm, new THREE.PointsMaterial({ map: point(), color: S.ville, size: carton ? 7 : 5, sizeAttenuation: false, transparent: true, alphaTest: .3 })));
        vs.forEach(v => { const s = etiquette(nomVille(v), S.ville, sc.style, 'ville'); s.position.copy(local(v[1], v[2], carton ? 1.012 : 1.006)); s.center.set(-.05, .5); s.userData.ville = v[3]; mondeGp.add(s); });
      }
    };
    /* The log's contacts: dots, dots and callsigns, or the squares worked. */
    sc.contacts = () => {
      while (contactsGp.children.length) contactsGp.remove(contactsGp.children[0]);
      if (rejoue || OB.contacts === 'aucun' || !OB.carnet) return;
      const C = '#FF4FA3';
      if (OB.contacts === 'locators') {
        const carres = new Map();
        OB.carnet.forEach(c => { const k = c.l.slice(0, 4); carres.set(k, (carres.get(k) || 0) + 1); });
        const mat = new THREE.MeshBasicMaterial({ color: C, transparent: true, opacity: .35, depthWrite: false, side: THREE.DoubleSide });
        carres.forEach((n, k) => {
          const q = obLocator(k); if (!q) return;
          contactsGp.add(new THREE.Mesh(carreSol(q.lat - q.dlat / 2, q.lon - q.dlon / 2, q.dlat, q.dlon, 1.0025), mat));
        });
        [...carres.keys()].slice(0, 400).forEach(k => { const q = obLocator(k); const s = etiquette(k, '#FFD1E8', sc.style, 'contact'); s.position.copy(local(q.lat, q.lon, 1.006)); s.userData.contact = 1; contactsGp.add(s); });
        return;
      }
      const vus = new Map();
      OB.carnet.forEach(c => { const q = obLocator(c.l); if (!q) return; const k = c.c + '|' + c.l; const a = vus.get(k); if (!a || a.t < c.t) vus.set(k, { c: c.c, q: q, t: c.t }); });
      const l = [...vus.values()].sort((a, b) => b.t - a.t);
      const pos = []; l.forEach(c => { const p = local(c.q.lat, c.q.lon, 1.003); pos.push(p.x, p.y, p.z); });
      const gm = new THREE.BufferGeometry(); gm.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
      contactsGp.add(new THREE.Points(gm, new THREE.PointsMaterial({ map: point(), color: C, size: 7, sizeAttenuation: false, transparent: true, alphaTest: .3 })));
      if (OB.contacts === 'indicatifs') l.slice(0, 400).forEach(c => { const s = etiquette(c.c, '#FFD1E8', sc.style, 'contact'); s.position.copy(local(c.q.lat, c.q.lon, 1.006)); s.center.set(-.05, .5); s.userData.contact = 1; contactsGp.add(s); });
    };

    sc.appliqueStyle = k => {
      if (!STYLES[k]) k = 'cartoon';
      sc.style = k;
      if (sol) { terre.remove(sol); sol.geometry.dispose(); sol.material.dispose(); }
      extras.forEach(e => (e.parent || scene).remove(e)); extras = [];
      const tex = new THREE.CanvasTexture(texture(k)); tex.anisotropy = Math.min(8, rendu.capabilities.getMaxAnisotropy());
      let mat; amb.intensity = .18; soleil.intensity = 1.25; soleil.color.set(0xffffff);
      if (k === 'realiste') { mat = new THREE.MeshPhongMaterial({ map: tex, specular: 0x223344, shininess: 18 }); amb.intensity = .34; }
      else if (k === 'cartoon') { mat = new THREE.MeshToonMaterial({ map: tex, gradientMap: toon() }); amb.intensity = .55; soleil.intensity = .8; }
      else if (k === 'vintage') { mat = new THREE.MeshLambertMaterial({ map: tex }); amb.intensity = .65; soleil.intensity = .55; soleil.color.set(0xffe6c0); }
      else if (k === 'points') mat = new THREE.MeshBasicMaterial({ color: 0x050915 });
      else mat = new THREE.MeshBasicMaterial({ map: tex });
      sol = new THREE.Mesh(new THREE.SphereGeometry(1, 128, 96), mat); terre.add(sol);
      const ajoute = (o, parent) => { (parent || scene).add(o); extras.push(o); };
      if (k === 'realiste') { ajoute(nuages(), terre); ajoute(bord('#5aa8ff', 3.0, 1.1)); ajoute(bord('#3f8cff', 2.2, 1.0, 1.09, true)); }
      if (k === 'cartoon') { ajoute(new THREE.Mesh(new THREE.SphereGeometry(1.022, 96, 64), new THREE.MeshBasicMaterial({ color: 0x0a1230, side: THREE.BackSide }))); ajoute(nuagesCartoon(), terre); }
      if (k === 'holo') { mat.transparent = true; mat.opacity = .92; ajoute(bord('#3fe8ff', 2.2, 1.4)); ajoute(bord('#3fe8ff', 2.5, .6, 1.06, true)); }
      if (k === 'points') { ajoute(pointsTerre(), terre); ajoute(bord('#5f86ff', 2.5, 1.2)); ajoute(bord('#3f5fff', 3, .5, 1.07, true)); }
      if (k === 'radar') ajoute(bord('#3cff7a', 2.4, 1.1));
      scene.background = new THREE.Color(STYLES[k].fond);
      etoiles.material.color.set(STYLES[k].etoiles);
      sc.objets.forEach(o => o.restyle());
      sc.placeStation(); sc.monde(); sc.contacts(); if (mode === 'rejeu') sc.rejeuPrepare(); if (mode === 'sked') sc.skedPrepare();
    };

    function objet(s) {
      const o = { s: s };
      o.engin = engin(s.couleur); o.lueur = lueur(s.couleur);
      o.orbite = ruban(sc, .6, true);
      o.tube = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ color: s.couleur, transparent: true, opacity: .95 }));
      o.cal = new THREE.Mesh(calotte(), new THREE.MeshBasicMaterial({ color: s.couleur, transparent: true, opacity: .14, depthWrite: false, side: THREE.DoubleSide }));
      const lg = new THREE.BufferGeometry(); lg.setAttribute('position', new THREE.BufferAttribute(new Float32Array(NC * 3), 3));
      o.bordCal = new THREE.LineLoop(lg, new THREE.LineBasicMaterial({ color: s.couleur, transparent: true, opacity: .8 }));
      o.rayon = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), new THREE.LineBasicMaterial({ color: 0xffb454, transparent: true, opacity: .9 }));
      // « Vue du satellite »: its ground track, ahead bright, behind faint.
      o.sol = ruban(sc); o.sol.visible = false; o.solT = [];
      o.solPts = new THREE.Points(new THREE.BufferGeometry(), new THREE.PointsMaterial({ map: point(), vertexColors: true, size: 6, sizeAttenuation: false, transparent: true, alphaTest: .3 })); o.sol.add(o.solPts);
      scene.add(o.engin, o.lueur, o.orbite, o.tube, o.rayon); terre.add(o.cal, o.bordCal, o.sol);
      o.restyle = () => {
        [o.nom, o.nomFort].forEach(n => { if (n) scene.remove(n); });
        o.nom = etiquette(s.nom, s.couleur, sc.style, 'sat'); o.nomFort = etiquette('★ ' + s.nom, s.couleur, sc.style, 'fort'); scene.add(o.nom, o.nomFort);
        const c = STYLES[sc.style].encre || s.couleur;
        o.tube.material.color.set(c); o.bordCal.material.color.set(c); o.cal.material.color.set(c); o.solC = new THREE.Color(c); o.orbite.uni(o.solC, 1);
      };
      o.retire = () => { scene.remove(o.engin, o.lueur, o.orbite, o.tube, o.rayon, o.nom, o.nomFort); terre.remove(o.cal, o.bordCal, o.sol); o.sol.jette(); o.orbite.jette(); };
      o.tOrbite = -1e15;
      if (sc.style) o.restyle();
      return o;
    }
    /* A journal's recorded track by pass (a session holds several): [[t0, t1]…], and the one at or after [t]. */
    function morceaux(s) {
      if (!s.morceaux) { const l = s.piste, m = []; let d = 0; for (let i = 1; i <= l.length; i++) if (i === l.length || l[i][0] - l[i - 1][0] > 600000) { m.push([d, i - 1]); d = i; } s.morceaux = m; }
      return s.morceaux;
    }
    function morceauA(s, t) { const m = morceaux(s), l = s.piste; let k = m.findIndex(q => t <= l[q[1]][0]); if (k < 0) k = m.length - 1; return k; }
    function orbite(o, t, lui) {
      const s = o.s, obs = sc.obs(), paires = [], trace = [];
      let tube = [];
      const ligne = pts => { for (let i = 1; i < pts.length; i++) paires.push(pts[i - 1], pts[i]); };
      if (s.piste) {
        // A journal's passes: their recorded track, each point where the satellite was then; the current pass thick.
        const l = s.piste, ici = morceauA(s, t);
        morceaux(s).forEach((q, k) => {
          const pts = [], pas = Math.max(1, Math.floor((q[1] - q[0] + 1) / 160));
          for (let i = q[0]; i <= q[1]; i += pas) { const e = etat(s, l[i][0], obs); if (e) { pts.push(eciVers3(e.eci)); trace.push([l[i][0], e.lat, e.lon]); } }
          if (k === ici && lui) tube = pts; else ligne(pts);
        });
        o.morceau = ici;
      } else {
        const P = s.periode, n = 240, pts = [];
        for (let i = 0; i <= n; i++) { const ti = t - P / 2 + P * i / n, e = etat(s, ti, null); if (!e) continue; pts.push(eciVers3(e.eci)); trace.push([ti, e.lat, e.lon]); }
        if (lui) tube = pts; else ligne(pts);
      }
      o.orbite.pose(paires); if (o.solC) o.orbite.uni(o.solC, 1);
      o.tube.geometry.dispose();
      o.tube.geometry = tube.length > 3 ? new THREE.TubeGeometry(new THREE.CatmullRomCurve3(tube, !s.piste && s.mm > 1.2), tube.length, (sc.style === 'cartoon' ? .0045 : .003) * (.7 + .8 * forceTrace()), 6, false) : new THREE.BufferGeometry();
      o.trace = trace; o.tOrbite = t; o.tubeLui = lui;
      // The ground track below (a journal's passes not joined across the gaps between them), its times to colour it.
      const seg = [], ts = [];
      for (let i = 1; i < trace.length; i++) if (!s.piste || trace[i][0] - trace[i - 1][0] < 600000) {
        seg.push(local(trace[i - 1][1], trace[i - 1][2], 1.003), local(trace[i][1], trace[i][2], 1.003)); ts.push(trace[i - 1][0], trace[i][0]);
      }
      o.sol.pose(seg); o.solT = ts;
      // Dots along it, some 150 km apart (a journal's track is recorded every few seconds).
      const pts = [], pt = []; let der = null;
      trace.forEach(q => { const v = local(q[1], q[2], 1.003); if (!der || der.distanceTo(v) > .024) { pts.push(v); pt.push(q[0]); der = v; } });
      o.solPts.geometry.dispose(); o.solPts.geometry = new THREE.BufferGeometry().setFromPoints(pts);
      o.solPts.geometry.setAttribute('color', new THREE.BufferAttribute(new Float32Array(pts.length * 3), 3)); o.solPT = pt;
    }
    sc.orbite = orbite;
    sc.choisi = () => mode === 'suivi' ? OB.sats.find(s => s.suivi) : mode === 'rejeu' ? (sc.rj && sc.rj.sat) : mode === 'sked' ? (sc.sk && sc.sk.sat) : OB.choisi;
    sc.montres = () => mode === 'rejeu' ? (sc.rj ? [sc.rj.sat].concat(sc.rj.autres || []) : []) : mode === 'sked' ? (sc.sk ? [sc.sk.sat] : []) : mode === 'suivi' ? OB.sats.filter(s => s.suivi || s.fav) : OB.sats.filter(s => s.on);
    sc.synchro = () => {
      const l = sc.montres(), ids = new Set(l.map(s => s.catnum));
      sc.objets.forEach((o, n) => { if (!ids.has(n) || !l.includes(o.s)) { o.retire(); sc.objets.delete(n); } });
      l.forEach(s => { if (!sc.objets.has(s.catnum)) sc.objets.set(s.catnum, objet(s)); });
      sc.placeStation();
    };

    /* The journal: the pass's contacts where they were, their pictures landing at the station once received. */
    sc.rejeuPrepare = () => {
      while (rejeuGp.children.length) { const c = rejeuGp.children[0]; if (c.jette) c.jette(); rejeuGp.remove(c); }
      if (mode !== 'rejeu' || !sc.rj) return;
      const rj = sc.rj;
      if (rj.marques) rj.marques.forEach(o => o.retire && o.retire());
      rj.marques = [];
      rj.images = (rj.donnees.marques || []).filter(m => m.k === 'SSTV' && m.f).sort((a, b) => a.a - b.a);
      (rj.donnees.marques || []).forEach(m => {
        const o = { m: m };
        if (m.lat != null && m.lon != null) {
          const c = m.k === 'APRS' ? (m.iss ? '#FFC21A' : '#4C8DFF') : m.k === 'SSTV' ? '#FF8A1F' : '#FF4FA3';
          o.point = new THREE.Mesh(new THREE.SphereGeometry(.007, 12, 8), new THREE.MeshBasicMaterial({ color: c })); o.point.position.copy(local(m.lat, m.lon, 1.004));
          o.onde = new THREE.Mesh(new THREE.RingGeometry(.01, .013, 32), new THREE.MeshBasicMaterial({ color: c, transparent: true, side: THREE.DoubleSide, depthWrite: false }));
          o.onde.position.copy(local(m.lat, m.lon, 1.005)); o.onde.lookAt(local(m.lat, m.lon, 2));
          o.nom = etiquette(String(m.t || m.k).split(' ')[0], c, sc.style, 'contact'); o.nom.position.copy(local(m.lat, m.lon, 1.012)); o.nom.center.set(-.05, .5);
          o.arc = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3(), new THREE.Vector3()]), new THREE.LineBasicMaterial({ color: c, transparent: true, opacity: .9 }));
          rejeuGp.add(o.point, o.onde, o.nom); scene.add(o.arc); o.retire = () => scene.remove(o.arc);
        }
        if (m.k === 'SSTV' && m.f && rj.donnees.image) {
          const t = new THREE.TextureLoader().load(rj.donnees.image(m));
          o.carte = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: false, transparent: true, sizeAttenuation: false })); o.carte.renderOrder = 11; o.carte.visible = false; scene.add(o.carte);
          const r = o.retire; o.retire = () => { if (r) r(); scene.remove(o.carte); };
        }
        rj.marques.push(o);
      });
    };
    /* The Sked: the ground track (faint), the common window on it and on the orbit (green, as on its map). */
    sc.skedPrepare = () => {
      if (mode !== 'sked') return;
      while (rejeuGp.children.length) { const c = rejeuGp.children[0]; if (c.jette) c.jette(); rejeuGp.remove(c); }
      if (sc.sk && sc.sk.tube) { scene.remove(sc.sk.tube); sc.sk.tube.geometry.dispose(); sc.sk.tube = null; }
      if (!sc.sk) return;
      const d = sc.sk.donnees, l = sc.sk.sat.piste, carton = !!STYLES[sc.style].carton;
      const sol = l.map(p => local(p[1], p[2], 1.002)), com = l.filter(p => p[0] >= d.de && p[0] <= d.a);
      rejeuGp.add(ruban(sc).pose(enSegments(sol)).uni(new THREE.Color(STYLES[sc.style].encre || '#3FE0C8'), .8));
      if (com.length > 1) {
        const vert = new THREE.MeshBasicMaterial({ color: 0x5bd68a, transparent: true, opacity: .95 }), kt = .7 + .8 * forceTrace();
        rejeuGp.add(new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(com.map(p => local(p[1], p[2], 1.003))), com.length, (carton ? .004 : .0028) * kt, 6, false), vert));
        const pts = []; com.forEach(p => { const e = etat(sc.sk.sat, p[0], null); if (e) pts.push(eciVers3(e.eci)); });
        if (pts.length > 1) { sc.sk.tube = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), pts.length, (carton ? .0065 : .0048) * kt, 6, false), vert); scene.add(sc.sk.tube); }
      }
    };
    /* The Sked: the other station's name, its ring, its line to the satellite while it sees it. */
    function skedImage(t, satP, now) {
      const a = sc.sk && sc.sk.autre, vu = !!a && !(passageSol());
      autre.visible = nomAutre.visible = vu; rayon2.visible = false;
      if (!vu) return;
      const aW = terre.localToWorld(posAutre.clone());
      if (sc.vueSat && aW.dot(cam.pos.clone().sub(aW)) < 0) nomAutre.visible = false;
      nomAutre.position.copy(aW.clone().multiplyScalar(1.02)); nomAutre.scale.set(.026 * nomAutre.userData.ratio, .026, 1); nomAutre.center.set(-.08, .5);
      const pulse = ((now + 700) / 1400) % 1; anneau2.scale.setScalar(1 + pulse * 2.5); anneau2.material.opacity = 1 - pulse;
      if (!a.obs) a.obs = { latitude: a.lat * D2R, longitude: a.lon * D2R, height: 0 };
      const e = satP && etat(sc.sk.sat, t, a.obs);
      rayon2.visible = !!e && e.el > 0;
      if (rayon2.visible) { const g = rayon2.geometry.attributes.position; g.setXYZ(0, aW.x, aW.y, aW.z); g.setXYZ(1, satP.x, satP.y, satP.z); g.needsUpdate = true; rayon2.material.opacity = .55 + .4 * Math.sin(now / 160 + 1); }
    }
    const passageSol = () => !!sc.enPassage && !sc.libre && OB.cam === 'sol';
    function rejeuImage(t, stW, satP, now) {
      const rj = sc.rj; if (!rj || !rj.marques) return;
      let rang = 0;
      rj.marques.forEach(o => {
        const m = o.m, vu = t >= m.de;
        if (o.point) {
          o.point.visible = o.nom.visible = vu;
          const age = (t - m.de) / 1000; o.onde.visible = vu && age < 30;
          if (o.onde.visible) { const k = (now / 900) % 1; o.onde.scale.setScalar(1 + k * 4); o.onde.material.opacity = 1 - k; }
          o.arc.visible = !!satP && vu && age < 25;
          if (o.arc.visible) { const a = o.arc.geometry.attributes.position, c = terre.localToWorld(o.point.position.clone()); a.setXYZ(0, stW.x, stW.y, stW.z); a.setXYZ(1, satP.x, satP.y, satP.z); a.setXYZ(2, c.x, c.y, c.z); a.needsUpdate = true; o.arc.material.opacity = .4 + .5 * Math.abs(Math.sin(now / 200)); }
        }
        if (o.carte) {
          // Received: the picture drops to the station and waits there with the others.
          o.carte.visible = (!rj.donnees.cartes || rj.donnees.cartes()) && t >= m.a && rj.images.filter(n => t >= n.a).slice(-3).includes(m);
          if (o.carte.visible) {
            const k = Math.min(1, (t - m.a) / 4000), haut = stW.clone().normalize();
            const ici = stW.clone().add(haut.clone().multiplyScalar(.06 + rang * .05));
            o.carte.position.copy(satP && k < 1 ? satP.clone().lerp(ici, k) : ici);
            const sz = .07 * (1.6 - .6 * k); o.carte.scale.set(sz * 4 / 3, sz, 1); o.carte.center.set(-.15, .5);
            rang++;
          }
        }
      });
    }

    // The camera: dragged round the Earth (turning with it), the wheel brings it nearer or further.
    const cam = { az: 0, pol: 1, dist: mode === 'suivi' ? 2.8 : 3.4, pos: new THREE.Vector3(0, 0, 4), cible: new THREE.Vector3(), cale: false, satK: 0, satD: 2 };
    sc.cam = cam;
    let tir = null;
    // The journal's globe: dragged, it moves on the screen (room for the picture); a double click puts it back.
    sc.decal = { x: 0, y: 0 };
    if (mode === 'rejeu') { try { const d = JSON.parse(lit('ob-decal', 'null')); if (d) sc.decal = d; } catch (x) {} }
    canvas.addEventListener('dblclick', () => { if (mode === 'rejeu') { sc.decal = { x: 0, y: 0 }; garde('ob-decal', JSON.stringify(sc.decal)); } });
    // The Sked's globe: framed by itself until dragged, then free (a double click frames it again).
    sc.skH = 3.5; sc.libre = false;
    canvas.addEventListener('dblclick', () => { if (mode === 'sked') sc.libre = false; sc.regard.x = sc.regard.y = 0; });
    canvas.addEventListener('pointerdown', e => {
      tir = { x: e.clientX, y: e.clientY }; sc.geste = performance.now(); canvas.setPointerCapture(e.pointerId);
      if (mode === 'sked' && !sc.libre && !sc.vueSat) {
        // From where the camera is: its angles without the Earth's turn.
        const q = cam.pos.clone().applyAxisAngle(new THREE.Vector3(0, 1, 0), -(sc.gm || 0)), n = Math.max(1.3, q.length());
        cam.az = Math.atan2(-q.z, q.x); cam.pol = Math.acos(Math.max(-1, Math.min(1, q.y / n))); cam.dist = Math.min(14, n); cam.cale = true; sc.libre = true;
      }
    });
    canvas.addEventListener('pointermove', e => {
      if (!tir) return;
      if (mode === 'rejeu') {
        const r = canvas.getBoundingClientRect();
        sc.decal.x = Math.max(-.45, Math.min(.45, sc.decal.x + (e.clientX - tir.x) / r.width)); sc.decal.y = Math.max(-.45, Math.min(.45, sc.decal.y + (e.clientY - tir.y) / r.height));
        tir = { x: e.clientX, y: e.clientY }; garde('ob-decal', JSON.stringify(sc.decal)); return;
      }
      if (sc.vueSat) {
        // « Vue du satellite »: a look aside, the Earth held by the hand (back below a few seconds after).
        const b = x => Math.max(-1.2, Math.min(1.2, x));
        sc.regard.x = b(sc.regard.x - (e.clientX - tir.x) * .004); sc.regard.y = b(sc.regard.y + (e.clientY - tir.y) * .004);
        tir = { x: e.clientX, y: e.clientY }; sc.geste = performance.now(); return;
      }
      const k = .005 * Math.min(1, cam.dist / 3);
      cam.az -= (e.clientX - tir.x) * k; cam.pol = Math.max(.05, Math.min(Math.PI - .05, cam.pol - (e.clientY - tir.y) * k));
      tir = { x: e.clientX, y: e.clientY }; sc.suit = null;
    });
    canvas.addEventListener('pointerup', () => { tir = null; });
    canvas.addEventListener('wheel', e => {
      e.preventDefault(); sc.geste = performance.now();
      // A session: its own zooms, the wheel changing the one shown.
      if (mode === 'rejeu' && sc.rj && sc.rj.donnees.molette) sc.rj.donnees.molette(Math.sign(e.deltaY));
      else if (mode === 'sked' && !sc.libre) sc.skH = Math.max(.6, Math.min(12, sc.skH * (1 + Math.sign(e.deltaY) * .1)));
      else if (sc.enPassage && mode !== 'sked') { OB.hauteur = Math.max(.6, Math.min(8, OB.hauteur * (1 + Math.sign(e.deltaY) * .1))); garde('ob-hauteur', OB.hauteur.toFixed(2)); document.querySelectorAll('.ob-hauteur-r').forEach(h => { h.value = OB.hauteur; }); }
      else cam.dist = Math.max(1.3, Math.min(14, cam.dist * (1 + Math.sign(e.deltaY) * .1)));
    }, { passive: false });
    function glisse(de, vers, k) {
      const r = de.length() + (vers.length() - de.length()) * k, d = de.clone().normalize().lerp(vers.clone().normalize(), k);
      if (d.lengthSq() < 1e-6) return vers.clone();
      return d.normalize().multiplyScalar(Math.max(1.02, r));
    }

    let derniere = performance.now();
    sc.image = (t, now) => {
      const r = canvas.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return;
      if (canvas.width !== Math.round(r.width * rendu.getPixelRatio()) || canvas.height !== Math.round(r.height * rendu.getPixelRatio())) { rendu.setSize(r.width, r.height, false); camera.aspect = r.width / r.height; camera.updateProjectionMatrix(); }
      // Its centre shifted on the screen and its size (the journal's; the dashboard's look's: OB.tb.place).
      const dc = mode === 'tab' && OB.tb && OB.tb.place ? OB.tb.place(r.width, r.height) : sc.decal, zs = dc.s || 1;
      if (dc.x || dc.y || zs !== 1) camera.setViewOffset(r.width, r.height, r.width / 2 - (.5 + dc.x) * r.width / zs, r.height / 2 - (.5 + dc.y) * r.height / zs, r.width / zs, r.height / zs);
      else if (camera.view && camera.view.enabled) camera.clearViewOffset();
      const dt = Math.min(.1, (now - derniere) / 1000); derniere = now;
      const gm = satellite.gstime(new Date(t)); sc.gm = gm;
      terre.rotation.y = gm;
      soleil.position.copy(eciVers3(obSoleil(t, gm).eci, 10));
      extras.forEach(e => { if (e.userData.tourne && !reduit) e.rotation.y += e.userData.tourne * dt; });
      if (!reduit) animes.forEach(o => o.userData.anime(o, now));
      if (mode === 'tab') astresScene(sc, terre, t);
      terre.updateMatrixWorld(true);
      monumentsImage(monGp, terre, cam.pos, sc.style, mode !== 'rejeu');
      const stW = terre.localToWorld(posStation.clone()), obs = sc.obs();
      const passage = !!sc.enPassage && !sc.libre, auSol = passage && OB.cam === 'sol', lieu = !!sc.lieu();
      const choisi = sc.choisi();
      extras.forEach(e => { e.visible = !auSol || !!e.isPoints; });
      mondeGp.visible = !auSol;
      station.visible = !auSol && lieu;
      // « Vue du satellite »: chosen for a pass (or the Sked's window), or now and then by itself on the Vitrine's dashboard.
      const vitSat = mode === 'tab' && !passage && !!OB.tb && !!OB.tb.vitrine && !tir && !reduit && now - (sc.geste || 0) > 12000 && now % 90000 > 60000;
      const satVoulu = (passage && OB.cam === 'satellite') || vitSat;
      const plusieurs = sc.objets.size > 1;
      cible.visible = false;
      let satP = null;
      sc.objets.forEach(o => {
        const s = o.s, e = etat(s, t, obs), lui = s === choisi;
        if (!e) { [o.engin, o.lueur, o.orbite, o.tube, o.cal, o.bordCal, o.nom, o.nomFort, o.rayon].forEach(x => { x.visible = false; }); return; }
        o.e = e;
        // The others, around the one chosen: quieter (the Suivi's and a pass's: lights and names only).
        const discret = (mode === 'suivi' || passage) && !lui, efface = !lui && plusieurs;
        if (!discret && (s.piste ? o.morceau !== morceauA(s, t) || o.tOrbite < -1e14 : Math.abs(t - o.tOrbite) > s.periode * .02 || o.tubeLui !== lui)) orbite(o, t, lui);
        const p = eciVers3(e.eci); o.p = p; if (lui) satP = p;
        o.engin.position.copy(p); o.engin.lookAt(0, 0, 0); o.engin.scale.setScalar(auSol ? .15 : satVoulu ? .5 : lui ? 1.6 : 1); o.engin.visible = !discret;
        o.lueur.position.copy(p); o.lueur.visible = true;
        const vu = e.el > 0, sz = (vu ? .06 : .04) * (sc.style === 'cartoon' ? 1.3 : 1) * (auSol && lui ? 1.8 : 1) * (discret ? .55 : 1) * (lui && plusieurs ? 1.5 : 1);
        o.lueur.scale.set(sz, sz, 1);
        const k = mode === 'suivi' ? (lui ? 1.5 : 1.1) : discret ? .5 : lui ? 1.25 : .85;
        const n = lui && plusieurs ? o.nomFort : o.nom, n2 = n === o.nom ? o.nomFort : o.nom;
        n2.visible = false; n.visible = true; n.position.copy(p.clone().multiplyScalar(1 + .06 / p.length()));
        n.scale.set(.028 * n.userData.ratio * k, .028 * k, 1); n.center.set(-.08, .5);
        n.material.opacity = efface && !discret ? .8 : 1;
        poseCalotte(o.cal.geometry, o.bordCal, e.lat, e.lon, Math.acos(RE / (RE + e.alt)));
        // A session's companions: their footprints when asked, faint.
        const tache = discret && mode === 'rejeu' && !!(sc.rj && sc.rj.taches);
        o.cal.visible = o.bordCal.visible = (!discret || tache) && !auSol;
        o.cal.material.opacity = lui ? (satVoulu ? .07 : sc.style === 'cartoon' ? .26 : .2) : tache ? .09 : efface ? .05 : .14;
        o.bordCal.material.opacity = lui ? 1 : tache ? .5 : efface ? .3 : .8;
        o.orbite.visible = !discret && (!lui || !!s.piste); o.orbite.op = efface ? .45 : lui ? .6 : .85;
        // Seen from above, its orbit would cross the view thick: its ground track instead.
        o.tube.visible = !discret && lui && !satVoulu;
        o.sol.visible = satVoulu && lui && !discret;
        if (o.sol.visible && o.solC) {
          // Ahead vivid, behind paler (both readable); the dots as big as the track is marked.
          const q = o.solC, k = forceTrace(), pa = reglageTrace(k).passe, c = o.solPts.geometry.attributes.color, pt = o.solPT || [];
          o.solT.forEach((u, i) => o.sol.teinte(i, q, u >= t ? 1 : pa));
          if (c) { for (let i = 0; i < pt.length; i++) { const f = pt[i] >= t ? 1 : .35 + .3 * k; c.setXYZ(i, q.r * f, q.g * f, q.b * f); } c.needsUpdate = true; }
          o.solPts.material.size = 4 + 6 * k;
        }
        o.rayon.visible = vu && !discret && !auSol && lieu;
        if (o.rayon.visible) { const a = o.rayon.geometry.attributes.position; a.setXYZ(0, stW.x, stW.y, stW.z); a.setXYZ(1, p.x, p.y, p.z); a.needsUpdate = true; o.rayon.material.opacity = .55 + .4 * Math.sin(now / 160); }
        if (lui && plusieurs && !auSol) { cible.visible = true; cible.position.copy(p); const q = .075 + .012 * Math.sin(now / 250); cible.scale.set(q, q, 1); cible.material.rotation = now / 1200; cible.material.color.set(STYLES[sc.style].encre || s.couleur); }
      });
      if (mode === 'rejeu') rejeuImage(t, stW, satP, now);
      if (sc.sk && sc.sk.tube) sc.sk.tube.visible = !satVoulu;
      if (mode === 'sked') skedImage(t, satP, now);
      // The journal's « mon indicatif et mon locator » unticked: the station's pin without its name, as in its videos.
      // Seen from the satellite, a station beyond the Earth's edge keeps its name hidden with it.
      nomStation.visible = station.visible && !(satVoulu && stW.dot(cam.pos.clone().sub(stW)) < 0) && !(mode === 'rejeu' && sc.rj && sc.rj.donnees.nomStation && !sc.rj.donnees.nomStation()); nomStation.position.copy(stW.clone().multiplyScalar(1.02)); nomStation.scale.set(.026 * nomStation.userData.ratio, .026, 1); nomStation.center.set(1.08, .5);
      const pulse = (now / 1400) % 1; anneau.scale.setScalar(1 + pulse * 2.5); anneau.material.opacity = 1 - pulse;
      cardinaux.forEach(c => { c.visible = auSol; if (auSol) { c.position.copy(stW.clone().add(dirCiel(stW, c.userData.az, 1).multiplyScalar(.04))); c.scale.set(.03 * c.userData.ratio, .03, 1); } });
      // The cities' and contacts' names: their size, smaller from far away.
      const kv = Math.max(.55, Math.min(1.1, 3.2 / Math.max(1.2, cam.pos.length())));
      mondeGp.children.forEach(s => { if (s.isSprite) { const z = .02 * kv * (s.userData.ville === 1 ? 1.1 : .9); s.scale.set(z * s.userData.ratio, z, 1); } });
      contactsGp.children.forEach(s => { if (s.isSprite) s.scale.set(.017 * kv * s.userData.ratio, .017 * kv, 1); });
      rejeuGp.children.forEach(s => { if (s.isSprite) s.scale.set(.02 * s.userData.ratio, .02, 1); });

      const oc = choisi && sc.objets.get(choisi.catnum);
      // The satellite whose view it is: the one chosen (the Vitrine: else the first shown).
      const ov = oc && oc.p ? oc : vitSat ? [...sc.objets.values()].find(o => o.p && o.lueur.visible) : null, vueSat = satVoulu && !!ov;
      sc.vueSat = vueSat; terminateur.visible = vueSat;
      if (vueSat) { terminateur.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), soleil.position.clone().normalize()); terminateur.material.color.set(STYLES[sc.style].encre || '#FFC857'); }
      if (!tir && now - (sc.geste || 0) > 4000) { const f = Math.pow(.15, dt); sc.regard.x *= f; sc.regard.y *= f; }
      let fov = auSol ? 75 : 42; if (!vueSat) cam.satK = 0;
      const lisse = 1 - Math.pow(.02, dt);
      if (vueSat) {
        // On the satellite's vertical, looking straight down; its track going up the screen (a geostationary one: north up).
        const p = ov.p, up = p.clone().normalize(), r = p.length(), asp = camera.aspect || 1;
        const H = mode === 'sked' ? sc.skH : mode === 'rejeu' && sc.rj && sc.rj.donnees.hauteur ? sc.rj.donnees.hauteur() : OB.hauteur, k = Math.min(2.5, H / 3.5);
        let haut = ov.s.mm != null && ov.s.mm < 1.2 ? new THREE.Vector3(0, 1, 0) : eciVers3(ov.e.vel);
        haut.sub(up.clone().multiplyScalar(haut.dot(up)));
        if (haut.lengthSq() < 1e-9) haut = new THREE.Vector3(1, 0, 0).sub(up.clone().multiplyScalar(up.x));
        haut.normalize();
        // Drawn back until the whole footprint holds in the view (the Hauteur nearer: down to the satellite itself).
        const a = asp >= 1 ? 30 * D2R : Math.atan(Math.tan(30 * D2R) * asp), D = obReculSat(r, a * .9, Math.min(1, k)), lam = Math.acos(1 / r);
        const m = Math.min(80 * D2R, Math.atan2(Math.sin(lam), D - Math.cos(lam)) / .9 * Math.max(1, k));
        fov = Math.max(6, Math.min(100, 2 * R2D * (asp >= 1 ? m : Math.atan(Math.tan(m) / asp))));
        const droite = new THREE.Vector3().crossVectors(haut, up);
        const vise = droite.clone().multiplyScalar(D * Math.tan(sc.regard.x)).add(haut.clone().multiplyScalar(D * Math.tan(sc.regard.y)));
        // Eased in for a second, then held on the satellite (a fast replay would leave it behind), its height eased.
        const deb = cam.satK < 1; cam.satK = Math.min(1, cam.satK + dt * .8); cam.satD = deb ? D : cam.satD + (D - cam.satD) * lisse;
        const kk = Math.max(lisse, cam.satK * cam.satK), pos = up.clone().multiplyScalar(cam.satD);
        if (deb) { cam.pos.copy(glisse(cam.pos, pos, kk)); cam.cible.lerp(vise, kk); } else { cam.pos.copy(pos); cam.cible.copy(vise); }
        camera.up.copy(haut);
      } else if (passage && oc && oc.p) {
        const e = oc.e, p = oc.p, up = p.clone().normalize(), H = mode === 'sked' ? sc.skH : mode === 'rejeu' && sc.rj && sc.rj.donnees.hauteur ? sc.rj.donnees.hauteur() : OB.hauteur;
        let pos, vise;
        if (OB.cam === 'poursuite') {
          const v = eciVers3(e.vel).normalize();
          pos = p.clone().add(up.clone().multiplyScalar(.22 * H)).add(v.clone().multiplyScalar(-.38 * H));
          vise = p.clone().lerp(lieu ? stW : p.clone().multiplyScalar(.9), .45); camera.up.copy(up);
        } else if (OB.cam === 'sol' && lieu) {
          const z = stW.clone().normalize();
          pos = stW.clone().add(z.clone().multiplyScalar(3 / RE));
          // A little under the satellite, so the horizon stays in view.
          vise = stW.clone().add(e.el > -3 ? dirCiel(stW, e.az, Math.max(10, e.el * .5)) : dirCiel(stW, sc.azAos != null ? sc.azAos : 180, 10));
          camera.up.copy(z);
        } else if (mode === 'sked' && sc.sk && sc.sk.autre) {
          // The Sked: between both stations and the satellite, far enough to hold them all.
          const aW = terre.localToWorld(posAutre.clone()).normalize(), sN = stW.clone().normalize(), z = sN.clone().add(aW).add(up.clone().multiplyScalar(.5)).normalize();
          const ang = Math.max(z.angleTo(sN), z.angleTo(aW), z.angleTo(up)), h = H * Math.max(1, ang / .42);
          pos = z.clone().multiplyScalar(1 + .64 * h).add(new THREE.Vector3(0, .12 * h, 0));
          vise = z.clone().multiplyScalar(.6); camera.up.set(0, 1, 0);
        } else {
          const z = (lieu ? stW.clone().lerp(p.clone().normalize(), .35) : p.clone()).normalize();
          pos = z.clone().multiplyScalar(1 + .64 * H).add(new THREE.Vector3(0, .24 * H, 0));
          vise = z.clone().multiplyScalar(.6); camera.up.set(0, 1, 0);
        }
        if (OB.cam === 'sol' && lieu) { cam.pos.copy(pos); cam.cible.copy(vise); } else { cam.pos.copy(glisse(cam.pos, pos, lisse)); cam.cible.lerp(vise, lisse); }
      } else {
        camera.up.set(0, 1, 0);
        let base;
        const suit = mode === 'suivi' ? null : sc.suit && sc.objets.get(sc.suit.catnum);
        if (suit && suit.p) base = suit.p.clone().normalize().multiplyScalar(cam.dist);
        else {
          const l = sc.lieu();
          if (!cam.cale && l) { cam.az = l.lon * D2R + .3; cam.pol = (90 - l.lat + 12) * D2R; cam.cale = true; }
          // The dashboard's fun looks: the Earth turns slowly by itself, until a hand or the wheel takes it (and again 12 s after).
          if (mode === 'tab' && OB.tb && OB.tb.tourne && !tir && !reduit && now - (sc.geste || 0) > 12000) cam.az += dt * .06;
          base = new THREE.Vector3(Math.sin(cam.pol) * Math.cos(cam.az), Math.cos(cam.pol), -Math.sin(cam.pol) * Math.sin(cam.az)).multiplyScalar(cam.dist);
          base.applyAxisAngle(new THREE.Vector3(0, 1, 0), gm); // turning with the Earth
        }
        const k = 1 - Math.pow(.01, dt);
        cam.pos.copy(glisse(cam.pos, base, k)); cam.cible.lerp(new THREE.Vector3(), k);
      }
      // The view's width and its top eased from one camera to the next (from the station's own: at once).
      const f0 = camera.fov; camera.fov = auSol || Math.abs(fov - f0) < .05 ? fov : f0 + (fov - f0) * lisse; if (camera.fov !== f0) camera.updateProjectionMatrix();
      if (!cam.haut || auSol) cam.haut = camera.up.clone();
      else { cam.haut.lerp(camera.up, lisse); if (cam.haut.lengthSq() < 1e-6) cam.haut.copy(camera.up); cam.haut.normalize(); camera.up.copy(cam.haut); }
      if (vueSat) vueSatInfo(ov, t, now, cam.pos.length(), vitSat); else sc.cacheInfo();
      camera.position.copy(cam.pos); camera.lookAt(cam.cible);
      rendu.render(scene, camera);
      if (mode === 'tab' && OB.tb && OB.tb.pedago) reperes3d(sc, camera, cam, terre, r.width, r.height, satP, oc, lieu ? stW : null, soleil.position.clone().normalize());
      // Where the satellite chosen shows (the journal's SSTV frame keeps away from it).
      if (satP) { const v = satP.clone().project(camera); sc.satX = (v.x + 1) / 2 * r.width; } else sc.satX = null;
    };
    sc.retire = () => { sc.objets.forEach(o => o.retire()); sc.objets.clear(); if (sc.rj && sc.rj.marques) sc.rj.marques.forEach(o => o.retire && o.retire()); if (sc.sk && sc.sk.tube) scene.remove(sc.sk.tube); rendu.dispose(); };
    sc.appliqueStyle(OB.style);
    sc.synchro();
    return sc;
  }

  /* ------------------------------------------------------------ the frames: every globe shown, then the tab's instruments */
  const reduit = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  let derniereListe = 0, dernier2d = 0, dernierInstr = 0;
  function tourne() {
    if (boucle) return; boucle = true;
    const f = () => {
      const vus = scenes.filter(sc => sc.canvas.isConnected && sc.canvas.offsetParent !== null && !sc.canvas.hidden);
      const carte = tab && tab.c2d.offsetParent !== null && !tab.c2d.hidden;
      // A globe put away (the map shown instead): its « Vue du satellite » figures with it.
      scenes.forEach(sc => { if (!vus.includes(sc)) sc.cacheInfo(); });
      if (!vus.length && !carte) { boucle = false; return; }
      requestAnimationFrame(f);
      const now = performance.now();
      if (OB.anim && OB.anim.joue) {
        OB.anim.t += (now - OB.anim.dernier) * OB.anim.vitesse;
        if (OB.anim.t > OB.anim.p.los + 60000) { OB.anim.t = OB.anim.p.los + 60000; OB.anim.joue = false; boutonJouer(); }
      }
      if (OB.anim) OB.anim.dernier = now;
      // The Suivi tab's satellite, as the page last heard it.
      if (suivi && vus.includes(suivi) && typeof E !== 'undefined' && E && E.suivi) changeSuivi(E.suivi.catnum);
      vus.forEach(sc => sc.image(sc.mode === 'suivi' ? Date.now() : sc.mode === 'rejeu' ? sc.rj.donnees.horloge() : sc.mode === 'sked' ? sc.sk.donnees.horloge() : maintenant(), now));
      if (!tab || tab.v.hidden) return;
      const t = maintenant();
      tab.horloge.textContent = new Date(t).toISOString().slice(0, 19).replace('T', ' ') + ' UTC';
      if (carte && now - dernier2d > 66) { dernier2d = now; dessine2d(t); }
      if (now - derniereListe > 400) { derniereListe = now; lignesSats(t); }
      if (OB.vue === 'passage' && now - dernierInstr > 80) { dernierInstr = now; instruments(t); }
      // Live, the pass over: on to the next one (the list worked out again every ten minutes without one).
      if (OB.vue === 'passage' && !OB.anim && OB.choisi && (OB.passage ? t > OB.passage.los + 60000 : now - (OB.passagesT || 0) > 600000)) calculePassages();
    };
    requestAnimationFrame(f);
  }
  /** The satellite followed changed (Suivi): its orbit and footprint move to it. */
  function changeSuivi(catnum) {
    if (!OB.donnees || OB.donnees.suivi === catnum) return;
    OB.donnees.suivi = catnum; OB.sats.forEach(s => { s.suivi = s.catnum === catnum; });
    scenes.forEach(sc => { if (sc.mode !== 'rejeu' && sc.mode !== 'sked') sc.synchro(); });
  }

"""

    private const val MONUMENTS = """  /* ------------------------------------------------------------ landmarks by family, the Sun and the Moon, the dashboard's marks */
  /* Each family's colour (its 2D sign, the filter's swatch). */
  const COUL_MON = { tour: '#E8743B', pont: '#D64541', chateau: '#9B7BD8', religieux: '#F2B134', ancien: '#D9A55B', statue: '#36B38F', naturel: '#4CAF50', moderne: '#4A9BE8', espace: '#B8C4D0' };
  /** The landmarks shown: none, the most famous, or all of them, of the families ticked; the journal's globe ([q] 'rejeu') the videos' own (main ones with their own drawing). */
  function monumentsVus(q) {
    const l = monde().monuments || [];
    if (q === 'rejeu') return l.filter(m => m[7] && m[6] === 1);
    if (q === 'aucun' || OB.monuments === 'aucun') return [];
    return l.filter(m => (OB.monuments === 'tous' || m[6] === 1) && !OB.monOff.includes(m[4]));
  }
  let matDouble = null;
  const matFil = {};
  /* A family's drawing, small and simple like the others (about 0.02 of the Earth's radius high); made once, then cloned. */
  const gabarits = {};
  function monumentType(type) {
    if (gabarits[type]) return gabarits[type].clone();
    const g = new THREE.Group(), k = .001, add = (...l) => l.forEach(o => g.add(o)), a = (o, x, z) => { o.position.x = x * k; o.position.z = z * k; return o; };
    switch (type) {
      case 'tour': add(cyl(2 * k, 3.6 * k, 20 * k, 0xeee4d0), cyl(3.2 * k, 3.2 * k, 2 * k, 0xE8743B, 13 * k), pointe(2.6 * k, 7 * k, 0xE8743B, 20 * k)); break;
      case 'pont':
        [-9, 9].forEach(x => add(a(boite(1.8 * k, 15 * k, 1.8 * k, 0xb0bac4), x, 0), a(boite(1.8 * k, 1.2 * k, 3.6 * k, 0xb0bac4, 13 * k), x, 0)));
        add(boite(32 * k, 1.4 * k, 3.4 * k, 0xD64541, 5 * k)); break;
      case 'chateau':
        add(boite(14 * k, 7 * k, 10 * k, 0xe2d4b8));
        [[-7, -5], [7, -5], [-7, 5], [7, 5]].forEach(q => add(a(cyl(2 * k, 2 * k, 11 * k, 0xd4c3a2), q[0], q[1]), a(pointe(2.7 * k, 5 * k, 0x6a5acd, 11 * k), q[0], q[1]))); break;
      case 'religieux':
        add(boite(12 * k, 8 * k, 12 * k, 0xf6eedc), boule(5 * k, 0xF2B134, 9 * k), pointe(.8 * k, 4 * k, 0xF2B134, 13.6 * k),
          a(cyl(1 * k, 1.3 * k, 17 * k, 0xf6eedc), 8.5, 0), a(pointe(1.5 * k, 3.4 * k, 0xF2B134, 17 * k), 8.5, 0)); break;
      case 'ancien': {
        add(boite(17 * k, 1.6 * k, 10 * k, 0xe6cf98));
        for (let i = 0; i < 4; i++) [-3.5, 3.5].forEach(z => add(a(cyl(.9 * k, .9 * k, 8 * k, 0xf3e6c4, 1.6 * k, 8), (i - 1.5) * 4.8, z)));
        const t = new THREE.Mesh(new THREE.ConeGeometry(10 * k, 4 * k, 4), mt(0xD9A55B)); t.rotation.y = Math.PI / 4; t.scale.z = .62; t.position.y = 11.6 * k; add(t); break; }
      case 'statue': {
        add(boite(6 * k, 6 * k, 6 * k, 0xc9bfae), cyl(1.8 * k, 2.6 * k, 10 * k, 0x36B38F, 6 * k), boule(1.9 * k, 0x36B38F, 17.8 * k));
        const b = cyl(.6 * k, .6 * k, 7 * k, 0x36B38F, 13 * k); b.position.x = 2.2 * k; b.rotation.z = -.35; add(b, a(boule(1.2 * k, 0xffc21a, 21 * k), 3.6, 0)); break; }
      case 'naturel':
        add(pointe(9 * k, 13 * k, 0x9a7650, 0, 7), pointe(3.6 * k, 5 * k, 0xffffff, 8.2 * k, 7), a(boule(3.2 * k, 0x4CAF50, 2 * k), 8, 4), a(boule(2.6 * k, 0x3d9440, 2 * k), -7, 5)); break;
      case 'moderne':
        add(boite(6 * k, 24 * k, 6 * k, 0x7cc0f5), boite(4 * k, 7 * k, 4 * k, 0xa8d6fa, 24 * k), cyl(.3 * k, .3 * k, 6 * k, 0xdddddd, 31 * k), a(boite(5 * k, 13 * k, 5 * k, 0x4A9BE8), 7, 1)); break;
      case 'espace': {
        if (!matDouble) matDouble = new THREE.MeshToonMaterial({ color: 0xf4f7fa, gradientMap: toon(), side: THREE.DoubleSide });
        add(cyl(1 * k, 1.8 * k, 8 * k, 0xc4ccd6));
        const d = new THREE.Mesh(new THREE.SphereGeometry(8 * k, 18, 6, 0, Math.PI * 2, 0, Math.PI / 3.2), matDouble);
        d.rotation.x = Math.PI + .5; d.position.y = 16 * k; add(d); break; }
    }
    gabarits[type] = g; return g.clone();
  }
  /** The landmarks on a globe ([gp]); [q]: as monumentsVus. */
  function monumentsMonde(gp, style, q, animes) {
    while (gp.children.length) gp.remove(gp.children[0]);
    gp.userData.items = [];
    const terne = style === 'holo' || style === 'radar' || style === 'points';
    if (terne && !matFil[style]) matFil[style] = new THREE.MeshBasicMaterial({ color: STYLES[style].encre || STYLES[style].ville, wireframe: true, transparent: true, opacity: .8 });
    monumentsVus(q).forEach(m => {
      const o = m[7] ? monument(m[7]) : monumentType(m[4]);
      if (terne) o.traverse(c => { if (c.isMesh) c.material = matFil[style]; });
      pose(o, m[2], m[3], 0, .999); gp.add(o);
      o.traverse(c => { if (c.userData.anime) animes.push(c); });
      gp.userData.items.push({ o: o, m: m, dir: local(m[2], m[3], 1), nom: null, taille: m[7] ? 1 : 1.6 });
    });
  }
  /**
   * Each frame: the landmarks smaller as the camera comes near (growing less than the Earth does), the ones behind
   * the Earth hidden, their names once the camera is close (made when first needed, a few a frame).
   */
  function monumentsImage(gp, terre, camPos, style, noms) {
    const l = gp.userData.items; if (!l || !l.length) return;
    // The size chosen (Monuments › Taille) on the tab's and the Suivi's globes; the journal's as its videos draw them.
    const camL = terre.worldToLocal(camPos.clone()), d = camPos.length(), ech = Math.max(.6, Math.min(1, .45 + .55 * (d - 1) / 2.4)) * (noms ? OB.monTaille : 1);
    const proche = noms && d < (OB.tb && OB.tb.pedago ? 2.6 : 2.25);
    let neufs = 0;
    l.forEach(it => {
      const devant = it.dir.dot(camL) > 1.01;
      it.o.visible = devant; it.o.scale.setScalar(ech * it.taille);
      const montre = devant && proche && (it.m[6] === 1 || d < 1.9);
      if (montre && !it.nom && neufs < 6) {
        neufs++;
        it.nom = etiquette(it.m[1], style === 'vintage' ? '#5A2D0C' : '#FFE08A', style, 'ville');
        it.nom.position.copy(local(it.m[2], it.m[3], 1 + .03 * ech)); it.nom.center.set(.5, 0); gp.add(it.nom);
      }
      if (it.nom) {
        it.nom.visible = montre;
        if (montre) { const z = .017 * Math.max(.55, Math.min(1.1, 3.2 / Math.max(1.2, d))) * (OB.tb && OB.tb.pedago ? 1.3 : 1); it.nom.scale.set(z * it.nom.userData.ratio * Math.sqrt(noms ? OB.monTaille : 1), z * Math.sqrt(noms ? OB.monTaille : 1), 1); it.nom.position.copy(local(it.m[2], it.m[3], 1 + .03 * ech * it.taille)); }
      }
    });
  }
  /** A family's sign on the flat map, [s] px high, standing on [x], [y]. */
  function signeMon(x, type, fusee, X, Y, s) {
    const c = COUL_MON[type] || '#ccc', u = s / 10;
    x.save(); x.translate(X, Y); x.fillStyle = c; x.strokeStyle = 'rgba(10,18,40,.85)'; x.lineWidth = Math.max(1, u * .9); x.lineJoin = 'round';
    const p = new Path2D();
    const r = (a, b, w, h) => p.rect(a * u, b * u, w * u, h * u);
    switch (fusee ? 'fusee' : type) {
      case 'tour': p.moveTo(-2 * u, 0); p.lineTo(-1 * u, -8 * u); p.lineTo(0, -10 * u); p.lineTo(1 * u, -8 * u); p.lineTo(2 * u, 0); p.closePath(); break;
      case 'pont': r(-6, -4, 12, 1.6); r(-4.4, -8, 1.4, 8); r(3, -8, 1.4, 8); break;
      case 'chateau': p.moveTo(-5 * u, 0); p.lineTo(-5 * u, -7 * u); p.lineTo(-3.5 * u, -7 * u); p.lineTo(-3.5 * u, -5 * u); p.lineTo(3.5 * u, -5 * u); p.lineTo(3.5 * u, -7 * u); p.lineTo(5 * u, -7 * u); p.lineTo(5 * u, 0); p.closePath(); break;
      case 'religieux': r(-4, -4, 8, 4); p.moveTo(-3.4 * u, -4 * u); p.arc(0, -4 * u, 3.4 * u, Math.PI, 0); p.closePath(); r(4.6, -8, 1.2, 8); break;
      case 'ancien': p.moveTo(-5.5 * u, -6 * u); p.lineTo(0, -9 * u); p.lineTo(5.5 * u, -6 * u); p.closePath(); r(-5, -6, 1.4, 5); r(-.7, -6, 1.4, 5); r(3.6, -6, 1.4, 5); r(-5.5, -1.2, 11, 1.2); break;
      case 'statue': r(-2.4, -2.6, 4.8, 2.6); r(-1, -7.5, 2, 5); r(1, -10.5, 1, 4.5); p.moveTo(1.3 * u, -8.6 * u); p.arc(0, -8.6 * u, 1.3 * u, 0, 7); break;
      case 'naturel': p.moveTo(-6 * u, 0); p.lineTo(-1 * u, -9 * u); p.lineTo(1.5 * u, -5 * u); p.lineTo(3 * u, -7 * u); p.lineTo(6 * u, 0); p.closePath(); break;
      case 'moderne': r(-3.5, -10, 3.2, 10); r(.6, -7, 3, 7); break;
      case 'fusee': p.moveTo(0, -10 * u); p.lineTo(1.6 * u, -7 * u); p.lineTo(1.6 * u, -2 * u); p.lineTo(3 * u, 0); p.lineTo(-3 * u, 0); p.lineTo(-1.6 * u, -2 * u); p.lineTo(-1.6 * u, -7 * u); p.closePath(); break;
      // A dish on its mast (observatories, space centres).
      default: r(-.8, -5, 1.6, 5); p.moveTo(-5 * u, -9.5 * u); p.quadraticCurveTo(-1 * u, -2 * u, 5 * u, -7 * u); p.closePath();
    }
    x.fill(p); x.stroke(p);
    if (type === 'naturel') { x.fillStyle = '#fff'; x.beginPath(); x.moveTo(-2.4 * u, -6.5 * u); x.lineTo(-1 * u, -9 * u); x.lineTo(.4 * u, -6.6 * u); x.closePath(); x.fill(); }
    x.restore();
  }
  /** The landmarks on the flat map: their signs, their names where the map is big enough and they find room. */
  function monuments2d(x, X, Y, mw, S, vieux, boites) {
    const l = monumentsVus(''); if (!l.length) return;
    const s = Math.max(9, Math.min(18, mw / 110)) * OB.monTaille, pedago = !!(OB.tb && OB.tb.pedago), fz = Math.round((pedago ? 13 : 11) * Math.sqrt(OB.monTaille));
    l.forEach(m => signeMon(x, m[4], m[7] === 'fusee', X(m[3]), Y(m[2]), s));
    if (mw < 1300 / Math.sqrt(OB.monTaille)) return;
    x.font = (vieux ? 'italic ' : '') + '600 ' + fz + 'px system-ui, sans-serif'; x.textAlign = 'center'; x.textBaseline = 'top';
    l.filter(m => m[6] === 1 || mw > 2600).forEach(m => {
      const X0 = X(m[3]), Y0 = Y(m[2]) + 2, w = x.measureText(m[1]).width, b = [X0 - w / 2 - 2, Y0, w + 4, fz + 3];
      if (boites.some(q => b[0] < q[0] + q[2] && q[0] < b[0] + b[2] && b[1] < q[1] + q[3] && q[1] < b[1] + b[3])) return;
      boites.push(b);
      x.lineWidth = 3; x.strokeStyle = vieux ? 'rgba(239,224,184,.9)' : 'rgba(0,0,0,.7)'; x.strokeText(m[1], X0, Y0);
      x.fillStyle = vieux ? '#5A2D0C' : '#FFE08A'; x.fillText(m[1], X0, Y0);
    });
  }

  /* The night on the flat map, shaded by the Sun's height: the twilight (the grey line) a gradient, full night past −12°. */
  let nuitCache = null;
  function nuit2d(x, S, so, mx, my, mw, mh) {
    const cle = so.dec.toFixed(2) + '|' + so.lon.toFixed(1) + '|' + S.nuit;
    if (!nuitCache || nuitCache.cle !== cle) {
      const W = 240, H = 120, c = nuitCache ? nuitCache.c : document.createElement('canvas'); c.width = W; c.height = H;
      const cx = c.getContext('2d'), d = cx.createImageData(W, H), p = d.data, m = String(S.nuit).match(/[\d.]+/g) || [0, 0, 0, 0];
      const A = +m[3] ? Math.max(.42, +m[3]) : 0, r = Math.PI / 180, sd = Math.sin(so.dec * r), cd = Math.cos(so.dec * r);
      const ch = []; for (let j = 0; j < W; j++) ch.push(Math.cos(((j + .5) / W * 360 - 180 - so.lon) * r));
      for (let i = 0; i < H; i++) {
        const f = (90 - (i + .5) / H * 180) * r, a1 = Math.sin(f) * sd, b1 = Math.cos(f) * cd;
        for (let j = 0; j < W; j++) {
          const el = Math.asin(Math.max(-1, Math.min(1, a1 + b1 * ch[j]))) / r;
          let k = Math.max(0, Math.min(1, (0.8 - el) / 12.8)); k = k * k * (3 - 2 * k);
          const q = (i * W + j) * 4; p[q] = +m[0]; p[q + 1] = +m[1]; p[q + 2] = +m[2]; p[q + 3] = Math.round(255 * A * k);
        }
      }
      cx.putImageData(d, 0, 0); nuitCache = { cle: cle, c: c };
    }
    x.save(); x.imageSmoothingEnabled = true; x.drawImage(nuitCache.c, mx, my, mw, mh); x.restore();
  }
  /** The Moon's face, [phase] as obLune gives it, in a disc of radius [r] at [X], [Y]. */
  function lune2d(x, phase, X, Y, r) {
    x.save(); x.translate(X, Y);
    x.fillStyle = '#2b2f3a'; x.beginPath(); x.arc(0, 0, r, 0, 7); x.fill();
    x.fillStyle = '#F2F0E6'; x.shadowColor = 'rgba(240,240,220,.8)'; x.shadowBlur = r; x.fill(new Path2D(obLunePath(phase, r)));
    x.shadowBlur = 0; x.strokeStyle = 'rgba(0,0,0,.5)'; x.lineWidth = 1; x.beginPath(); x.arc(0, 0, r, 0, 7); x.stroke();
    x.restore();
  }
  /** Elevation and azimuth (degrees) of a body at declination [dec] over longitude [lonSous], seen from [lat], [lon]. */
  function azEl(lat, lon, dec, lonSous) {
    const f = lat * D2R, dc = dec * D2R, H = (lon - lonSous) * D2R;
    const e = Math.asin(Math.sin(f) * Math.sin(dc) + Math.cos(f) * Math.cos(dc) * Math.cos(H));
    const a = Math.atan2(-Math.cos(dc) * Math.sin(H), Math.sin(dc) * Math.cos(f) - Math.cos(dc) * Math.sin(f) * Math.cos(H));
    return { el: e * R2D, az: ((a * R2D) + 360) % 360 };
  }
  /* The dashboard's globe: the Sun and the Moon over the places they stand above (behind the Earth when it hides them). */
  function astresScene(sc, terre, t) {
    const on = !!(OB.tb && OB.tb.astres);
    if (!sc.astres) {
      if (!on) return;
      const c = document.createElement('canvas'); c.width = c.height = 64;
      const lu = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false, sizeAttenuation: false }));
      const so = lueur('#FFD34D'); so.material.depthTest = true;
      sc.astres = { so: so, lu: lu, c: c, phase: -1 }; terre.add(so, lu);
    }
    const A = sc.astres; A.so.visible = A.lu.visible = on; if (!on) return;
    const s = obSoleil(t, satellite.gstime(new Date(t))), l = obLune(t);
    A.so.position.copy(local(s.dec, s.lon, 1.03)); A.so.scale.set(.09, .09, 1);
    A.lu.position.copy(local(l.dec, l.lon, 1.03)); A.lu.scale.set(.045, .045, 1);
    if (Math.abs(l.phase - A.phase) > .005) {
      A.phase = l.phase; const x = A.c.getContext('2d'); x.clearRect(0, 0, 64, 64); lune2d(x, l.phase, 32, 32, 26); A.lu.material.map.needsUpdate = true;
    }
  }
  /** Where the explained things show on the globe's canvas (px): the satellite chosen, its footprint's edge, the station, the day–night line. */
  function reperes3d(sc, camera, cam, terre, w, h, satP, oc, stW, solDir) {
    const vu = p => p.dot(cam.pos.clone().sub(p)) > 0, ecran = p => { const v = p.clone().project(camera); return Math.abs(v.x) < 1 && Math.abs(v.y) < 1 ? [(v.x + 1) / 2 * w, (1 - v.y) / 2 * h] : null; };
    const R = {};
    if (satP) R.sat = ecran(satP);
    if (oc && oc.bordCal.visible) {
      const a = oc.bordCal.geometry.attributes.position, l = [];
      for (let i = 0; i < a.count; i += 6) { const p = terre.localToWorld(new THREE.Vector3(a.getX(i), a.getY(i), a.getZ(i))); if (vu(p)) l.push(p); }
      // The edge's point the nearest the camera's line of sight: well in view.
      l.sort((p, q) => q.dot(cam.pos) - p.dot(cam.pos));
      if (l.length) R.emp = ecran(l[Math.min(l.length - 1, 2)]);
    }
    if (stW && sc.lieu() && vu(stW)) R.station = ecran(stW);
    const c = cam.pos.clone().normalize(), q = c.clone().sub(solDir.clone().multiplyScalar(c.dot(solDir)));
    if (q.lengthSq() > 1e-4) { const p = q.normalize(); if (vu(p)) R.nuit = ecran(p); }
    sc.reperes = R;
  }

"""

    private const val TRACES = """  /* ------------------------------------------------------------ the tracks, as marked as asked (« Traces ») */
  /* Under a track, a halo [r, g, b, opacity]: dark on the light looks (and on the real Earth), light and fainter on the dark ones. */
  const HALO_TRACE = { cartoon: [.03, .06, .18, 1], vintage: [.18, .09, .02, 1], realiste: [0, .02, .07, 1], holo: [.86, 1, 1, .4], points: [.92, .95, 1, .4], radar: [.86, 1, .9, .4] };
  /** How marked the tracks are: [k] 0 (discreet, a thin line) … 1 (very marked, thick, haloed, opaque). */
  function reglageTrace(k) {
    return { larg: 1.2 + 3.6 * k, halo: k < .05 ? 0 : .8 + 2.4 * k, haloA: Math.min(.8, 1.2 * k), alpha: .7 + .3 * k, passe: .45 + .25 * k };
  }
  const forceTrace = () => Math.max(0, Math.min(100, OB.traces)) / 100;
  function haloCss(style, a) { const h = HALO_TRACE[style] || HALO_TRACE.realiste; return 'rgba(' + h.slice(0, 3).map(v => Math.round(v * 255)).join(',') + ',' + (a * h[3]).toFixed(3) + ')'; }
  /** Strokes [chemin] (it draws its path) on the 2D canvas [x]: its halo, then itself, [larg] px wide at [alpha]. */
  function traitTrace(x, chemin, couleur, larg, alpha, style) {
    const r = reglageTrace(forceTrace());
    x.save(); x.lineJoin = x.lineCap = 'round';
    if (r.halo > 0) { x.globalAlpha = 1; x.strokeStyle = haloCss(style, r.haloA * alpha); x.lineWidth = larg + 2 * r.halo; x.beginPath(); chemin(); x.stroke(); }
    x.globalAlpha = alpha; x.strokeStyle = couleur; x.lineWidth = larg; x.beginPath(); chemin(); x.stroke(); x.restore();
  }
  /* A track in 3D is a ribbon (a WebGL line is always 1 px), its width in screen pixels. On the ground: lying on the globe. */
  const RUBAN_VS = '#include <common>\n#include <logdepthbuf_pars_vertex>\n' +
    'attribute vec3 cote; attribute vec4 teinte; uniform float larg; uniform float haut; varying vec4 vT;\n' +
    'void main() {\n  vec4 m = modelViewMatrix * vec4(position, 1.0);\n  float w = larg * max(-m.z, 1e-5) / (projectionMatrix[1][1] * haut);\n' +
    '  gl_Position = projectionMatrix * modelViewMatrix * vec4(position + cote * w, 1.0); vT = teinte;\n#include <logdepthbuf_vertex>\n}';
  // Out in space (an orbit): across its own direction on the screen, whichever way it is seen.
  const RUBAN_ECRAN_VS = '#include <common>\n#include <logdepthbuf_pars_vertex>\n' +
    'attribute vec3 autre; attribute float sens; attribute vec4 teinte; uniform float larg; uniform float haut; uniform float aspect; varying vec4 vT;\n' +
    'void main() {\n  vec4 a = projectionMatrix * modelViewMatrix * vec4(position, 1.0), b = projectionMatrix * modelViewMatrix * vec4(autre, 1.0);\n' +
    '  vec2 d = (b.xy / b.w - a.xy / a.w) * vec2(aspect, 1.0), n = vec2(0.0);\n  if (a.w > 0.0 && b.w > 0.0 && length(d) > 1e-6) n = normalize(vec2(-d.y, d.x));\n' +
    '  n.x /= aspect; a.xy += n * sens * larg / haut * a.w; gl_Position = a; vT = teinte;\n#include <logdepthbuf_vertex>\n}';
  const RUBAN_FS = '#include <common>\n#include <logdepthbuf_pars_fragment>\nuniform vec3 couleur; uniform float opacite; uniform float uni; varying vec4 vT;\n' +
    'void main() {\n#include <logdepthbuf_fragment>\n  gl_FragColor = vec4(mix(vT.rgb, couleur, uni), vT.a * opacite);\n}';
  let tailleRendu = null;
  /**
   * A track on [sc]'s globe ([ecran]: in space), [epais] times as wide: [r].pose(seg) lays it along [seg] (pairs of points,
   * a segment each), [r].teinte(i, c, a) colours its point [i], [r].uni(c, a) all of it; [r].op its opacity.
   */
  function ruban(sc, epais, ecran) {
    const gp = new THREE.Group(), mat = halo => new THREE.ShaderMaterial({ vertexShader: ecran ? RUBAN_ECRAN_VS : RUBAN_VS, fragmentShader: RUBAN_FS,
      transparent: true, depthWrite: false, side: THREE.DoubleSide,
      uniforms: { larg: { value: 1 }, haut: { value: 600 }, aspect: { value: 1 }, couleur: { value: new THREE.Color() }, opacite: { value: 1 }, uni: { value: halo ? 1 : 0 } } });
    const halo = new THREE.Mesh(new THREE.BufferGeometry(), mat(true)), ligne = new THREE.Mesh(halo.geometry, mat(false));
    halo.renderOrder = 3; ligne.renderOrder = 4; gp.add(halo, ligne); gp.op = 1;
    // Each frame: the width and halo the « Traces » setting asks now, the drawing's size.
    halo.onBeforeRender = rendu => {
      const r = reglageTrace(forceTrace()), e = epais || 1, uh = halo.material.uniforms, ul = ligne.material.uniforms, l = Math.max(1, r.larg * e);
      tailleRendu = tailleRendu || new THREE.Vector2(); rendu.getSize(tailleRendu);
      uh.haut.value = ul.haut.value = Math.max(1, tailleRendu.y); uh.aspect.value = ul.aspect.value = tailleRendu.x / Math.max(1, tailleRendu.y);
      ul.larg.value = l; ul.opacite.value = r.alpha * gp.op;
      const h = HALO_TRACE[sc.style] || HALO_TRACE.realiste;
      halo.visible = r.halo > 0; uh.larg.value = l + 2 * r.halo * e; uh.opacite.value = r.haloA * h[3] * gp.op; uh.couleur.value.setRGB(h[0], h[1], h[2]);
    };
    ligne.onBeforeRender = rendu => { if (!halo.visible) halo.onBeforeRender(rendu); };
    gp.pose = seg => {
      const n = seg.length >> 1, pos = new Float32Array(n * 12), cote = new Float32Array(n * 12), autre = new Float32Array(n * 12), sens = new Float32Array(n * 4);
      const idx = [], d = new THREE.Vector3(), s = new THREE.Vector3();
      for (let i = 0; i < n; i++) {
        const a = seg[2 * i], b = seg[2 * i + 1];
        d.subVectors(b, a); s.crossVectors(a, d); if (s.lengthSq() > 1e-16) s.normalize(); else s.set(0, 0, 0);
        [a, a, b, b].forEach((p, j) => {
          const k = i * 12 + j * 3, g = j % 2 ? -1 : 1, q = j < 2 ? b : a;
          pos[k] = p.x; pos[k + 1] = p.y; pos[k + 2] = p.z; cote[k] = s.x * g; cote[k + 1] = s.y * g; cote[k + 2] = s.z * g;
          autre[k] = q.x; autre[k + 1] = q.y; autre[k + 2] = q.z; sens[i * 4 + j] = j === 0 || j === 3 ? 1 : -1;
        });
        idx.push(i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 2, i * 4 + 1, i * 4 + 3);
      }
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(pos, 3)); g.setAttribute('cote', new THREE.BufferAttribute(cote, 3));
      g.setAttribute('autre', new THREE.BufferAttribute(autre, 3)); g.setAttribute('sens', new THREE.BufferAttribute(sens, 1));
      g.setAttribute('teinte', new THREE.BufferAttribute(new Float32Array(n * 16).fill(1), 4)); g.setIndex(idx);
      halo.geometry.dispose(); halo.geometry = ligne.geometry = g;
      return gp;
    };
    gp.teinte = (i, c, a) => { const t = halo.geometry.attributes.teinte; if (!t) return; const v = (i >> 1) * 4 + (i & 1) * 2; t.setXYZW(v, c.r, c.g, c.b, a); t.setXYZW(v + 1, c.r, c.g, c.b, a); t.needsUpdate = true; };
    gp.uni = (c, a) => { const t = halo.geometry.attributes.teinte; if (t) { for (let v = 0; v < t.count; v++) t.setXYZW(v, c.r, c.g, c.b, a); t.needsUpdate = true; } return gp; };
    gp.jette = () => { halo.geometry.dispose(); halo.material.dispose(); ligne.material.dispose(); };
    return gp;
  }
  /** A polyline [pts] as segments, for [ruban]. */
  function enSegments(pts) { const seg = []; for (let i = 1; i < pts.length; i++) seg.push(pts[i - 1], pts[i]); return seg; }
  /** The « Traces » setting (0 … 100), kept in this browser; the boxes showing it follow, the orbits' tubes and the Sked's common window are redrawn. */
  function prendTraces(n) {
    OB.traces = Math.max(0, Math.min(100, Math.round(+n || 0))); garde('ob-traces', String(OB.traces));
    document.querySelectorAll('.ob-traces-choix').forEach(s => { if (+s.value !== OB.traces) s.value = String(OB.traces); });
    scenes.forEach(sc => { if (sc.objets) sc.objets.forEach(o => { o.tOrbite = -1e15; }); if (sc.mode === 'sked' && sc.skedPrepare) sc.skedPrepare(); });
  }

"""

    private const val FIN = """  /* ------------------------------------------------------------ the tab */
  const hh = t => new Date(t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  const hhs = t => new Date(t).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const jour = t => new Date(t).toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' });
  const cardinal = a => ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO'][Math.round(((a % 360) + 360) % 360 / 45) % 8];
  const echappe = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

  function prepareTab() {
    if (tab) return;
    tab = { v: el('v-orbites'), c3d: el('ob-3d'), c2d: el('ob-2d'), horloge: el('ob-horloge') };
    tab.scene = Scene(tab.c3d, 'tab'); scenes.push(tab.scene);
    const styles = el('ob-styles');
    Object.keys(STYLES).forEach(k => {
      const b = document.createElement('button'); b.type = 'button'; b.dataset.style = k; b.textContent = STYLES[k].nom;
      b.onclick = () => prendStyle(k); styles.appendChild(b);
    });
    tab.v.querySelectorAll('[data-ob-vue]').forEach(b => { b.onclick = () => montreVue(b.dataset.obVue); });
    tab.v.querySelectorAll('[data-ob-filtre]').forEach(b => { b.onclick = () => {
      OB.filtre = b.dataset.obFiltre; tab.v.querySelectorAll('[data-ob-filtre]').forEach(x => x.setAttribute('aria-pressed', x === b));
      el('ob-recherche').hidden = OB.filtre !== 'tous'; if (OB.filtre === 'tous') el('ob-recherche').focus(); listeSats();
    }; });
    el('ob-recherche').oninput = listeSats;
    tab.v.querySelectorAll('[data-ob-cam]').forEach(b => { b.onclick = () => { OB.cam = b.dataset.obCam; garde('ob-cam', OB.cam); marqueCam(); }; });
    el('ob-hauteur').value = OB.hauteur;
    el('ob-hauteur').oninput = e => { OB.hauteur = +e.target.value; garde('ob-hauteur', String(OB.hauteur)); };
    el('ob-detail').value = String(OB.detail);
    el('ob-detail').onchange = e => prendDetail(+e.target.value);
    el('ob-villes').value = OB.villes; el('ob-villes').onchange = e => prendVilles(e.target.value);
    el('ob-etiq').value = OB.etiq; el('ob-etiq').onchange = e => prendEtiq(e.target.value);
    el('ob-contacts').value = OB.contacts;
    el('ob-contacts').onchange = e => prendContacts(e.target.value);
    el('ob-monuments').value = OB.monuments; el('ob-monuments').onchange = e => prendMonuments(e.target.value);
    el('ob-montaille').value = String(OB.monTaille); el('ob-montaille').onchange = e => prendMonTaille(+e.target.value);
    el('ob-traces').value = String(OB.traces); el('ob-traces').oninput = e => prendTraces(+e.target.value);
    el('ob-montypes-b').onclick = ev => {
      ev.stopPropagation(); const m = el('ob-montypes'), ouvre = m.hidden;
      m.hidden = !ouvre; el('ob-montypes-b').setAttribute('aria-expanded', ouvre); if (ouvre) { typesMon(m); const c = m.querySelector('input'); if (c) c.focus(); }
    };
    document.addEventListener('pointerdown', ev => { const m = el('ob-montypes'); if (!m.hidden && !m.contains(ev.target) && ev.target !== el('ob-montypes-b')) { m.hidden = true; el('ob-montypes-b').setAttribute('aria-expanded', false); } });
    el('ob-pause').onclick = () => { recale(); OB.temps.pause = !OB.temps.pause; el('ob-pause').textContent = OB.temps.pause ? '▶' : '⏸'; el('ob-pause').title = OB.temps.pause ? 'Reprendre' : 'Pause'; };
    el('ob-vitesse').onchange = e => recale(+e.target.value);
    // « Maintenant » and « En direct »: the present time at its real pace, the Passage view back on the pass under way or next.
    el('ob-maint').onclick = direct;
    el('ob-direct').onclick = direct;
    el('ob-jouer').onclick = () => {
      if (!OB.passage) return;
      if (!OB.anim) prendPassage(OB.passage, true);
      else { if (OB.anim.t >= OB.anim.p.los + 60000) OB.anim.t = OB.anim.p.aos - 90000; OB.anim.joue = !OB.anim.joue; OB.anim.dernier = performance.now(); }
      boutonJouer();
    };
    el('ob-rejouer').onclick = () => { if (!OB.passage) return; if (!OB.anim) prendPassage(OB.passage, true);
      OB.anim.t = OB.anim.p.aos - 20000; OB.anim.joue = true; OB.anim.dernier = performance.now(); boutonJouer(); };
    el('ob-pvitesse').onchange = e => { if (OB.anim) OB.anim.vitesse = +e.target.value; boutonJouer(); };
    el('ob-suivre').onclick = () => { if (OB.choisi && typeof suivre === 'function') suivre(OB.choisi.catnum); };
    tab.c2d.addEventListener('pointermove', ev => {
      const r = tab.c2d.getBoundingClientRect(), mx = ev.clientX - r.left, my = ev.clientY - r.top;
      const p = (tab.points || []).find(q => Math.hypot(q.x - mx, q.y - my) < 12), b = el('ob-bulle');
      if (!p) { b.hidden = true; return; }
      b.hidden = false; b.style.left = (mx + 14) + 'px'; b.style.top = (my + 10) + 'px';
      b.innerHTML = '<b style="color:' + p.s.couleur + '">' + echappe(p.s.nom) + '</b> · ' + p.s.catnum + '<br>' + p.e.lat.toFixed(1) + '° ' + p.e.lon.toFixed(1) + '° · ' + Math.round(p.e.alt) + ' km<br>vu d’ici : az ' + p.e.az.toFixed(0) + '° el ' + p.e.el.toFixed(1) + '°';
    });
    tab.c2d.addEventListener('click', ev => {
      const r = tab.c2d.getBoundingClientRect(), p = (tab.points || []).find(q => Math.hypot(q.x - (ev.clientX - r.left), q.y - (ev.clientY - r.top)) < 12);
      if (p) choisis(p.s);
    });
    marqueStyle(); marqueCam();
  }
  function prendStyle(k) {
    OB.style = k; garde('ob-style2', k);
    scenes.forEach(sc => sc.appliqueStyle(k)); marqueStyle();
  }
  // Chosen elsewhere (the Sked's settings): the tab's own box shows it too.
  function montreChoix(id, v) { const s = el(id); if (s && s.value !== v) s.value = v; }
  function prendDetail(n) { OB.detail = n; garde('ob-detail', String(n)); montreChoix('ob-detail', String(n)); scenes.forEach(sc => sc.monde()); }
  function prendVilles(v) { OB.villes = v; garde('ob-villes', v); montreChoix('ob-villes', v); scenes.forEach(sc => sc.monde()); }
  function prendEtiq(v) { OB.etiq = v; garde('ob-etiq', v); montreChoix('ob-etiq', v); scenes.forEach(sc => sc.monde()); }
  function prendMonTaille(n) {
    OB.monTaille = Math.max(.5, Math.min(3, n || 1)); garde('ob-montaille', String(OB.monTaille));
    document.querySelectorAll('.ob-montaille-choix').forEach(s => { s.value = String(OB.monTaille); });
  }
  function prendMonuments(v) { OB.monuments = v; garde('ob-monuments', v); document.querySelectorAll('.ob-mon-choix').forEach(s => { s.value = v; }); scenes.forEach(sc => sc.monde()); }
  function prendTypeMon(k, on) {
    OB.monOff = OB.monOff.filter(x => x !== k).concat(on ? [] : [k]); garde('ob-montypes-off', JSON.stringify(OB.monOff));
    clearTimeout(OB.monT); OB.monT = setTimeout(() => scenes.forEach(sc => sc.monde()), 250);
  }
  /** The landmarks' families as boxes to tick in [box], each with its colour. */
  function typesMon(box) {
    box.innerHTML = '';
    (monde().typesMonuments || []).forEach(q => {
      const l = document.createElement('label'), c = document.createElement('input'), i = document.createElement('i');
      c.type = 'checkbox'; c.checked = !OB.monOff.includes(q[0]); c.onchange = () => prendTypeMon(q[0], c.checked);
      i.className = 'ob-mon-pastille'; i.style.background = COUL_MON[q[0]] || '#ccc';
      l.append(c, i, document.createTextNode(q[1])); box.appendChild(l);
    });
  }
  function prendContacts(v) { OB.contacts = v; garde('ob-contacts', v); if (v !== 'aucun' && !OB.carnet) { OB.carnetLu = 0; chargeCarnet(); } scenes.forEach(sc => sc.contacts()); }
  function marqueStyle() { document.querySelectorAll('#ob-styles button, .ob-style-choix option').forEach(b => { if (b.tagName === 'OPTION') b.selected = b.value === OB.style; else b.setAttribute('aria-pressed', b.dataset.style === OB.style); }); }
  function marqueCam() { document.querySelectorAll('[data-ob-cam]').forEach(b => b.setAttribute('aria-pressed', b.dataset.obCam === OB.cam)); document.querySelectorAll('.ob-cam-choix').forEach(s => { s.value = OB.cam; }); }
  function temps1() {
    OB.anim = null; OB.temps = { base: Date.now(), reel: performance.now(), vitesse: 1, pause: false };
    if (tab) { el('ob-vitesse').value = '1'; el('ob-pause').textContent = '⏸'; el('ob-pause').title = 'Pause'; boutonJouer(); }
  }
  /** Back to the present (×1, not paused); the Passage view on the pass under way, else the next one. */
  function direct() { temps1(); if (tab && OB.vue === 'passage') calculePassages(); }
  function boutonJouer() {
    if (!tab) return;
    el('ob-jouer').textContent = OB.anim && OB.anim.joue ? '⏸ Pause' : OB.anim ? (OB.anim.t >= OB.anim.p.los + 60000 ? '▶ Rejouer' : '▶ Reprendre') : '▶ Rejouer en accéléré';
    // Live (the default) or a replay: said beside the buttons, « En direct » to come back.
    el('ob-direct').hidden = !OB.anim; el('ob-mode').textContent = OB.anim ? 'Rejeu ×' + OB.anim.vitesse : '● En direct';
    el('ob-mode').classList.toggle('ob-live', !OB.anim);
  }
  function gardeCoches() { garde('ob-coches', JSON.stringify(OB.sats.filter(s => s.on).map(s => s.catnum))); }

  function listeSats() {
    const q = el('ob-recherche').value.trim().toLowerCase();
    const l = OB.sats.filter(s => OB.filtre === 'favoris' ? (s.fav || s.suivi || s.on) : (!q || s.nom.toLowerCase().includes(q) || String(s.catnum).includes(q))).slice(0, 200);
    const box = el('ob-sats'); box.innerHTML = '';
    l.forEach(s => {
      const r = document.createElement('div'); r.className = 'ob-sat' + (s === OB.choisi ? ' choisi' : ''); r.dataset.n = s.catnum;
      r.innerHTML = '<input type="checkbox" aria-label="Afficher ' + echappe(s.nom) + '"' + (s.on ? ' checked' : '') + '><div class="ob-nom"><span class="ob-pastille" style="background:' + s.couleur + '"></span><span>' + echappe(s.nom) + (s.suivi ? ' <small>(suivi)</small>' : s.fav ? ' <small>★</small>' : '') + '</span></div><div class="ob-el"></div><div class="ob-det"></div>';
      r.querySelector('input').onclick = e => { e.stopPropagation(); s.on = e.target.checked; gardeCoches(); tab.scene.synchro(); };
      r.onclick = () => choisis(s);
      box.appendChild(r);
    });
    if (!l.length) box.innerHTML = '<div class="petit" style="padding:6px">' + (OB.sats.length ? 'Aucun satellite ne correspond.' : 'Aucun élément orbital chargé.') + '</div>';
  }
  function lignesSats(t) {
    tab.v.querySelectorAll('.ob-sat').forEach(r => {
      const s = OB.sats.find(x => x.catnum === +r.dataset.n), o = s && tab.scene.objets.get(s.catnum), e = o && o.e;
      const a = r.querySelector('.ob-el'), d = r.querySelector('.ob-det');
      if (!e) { a.textContent = ''; d.textContent = ''; return; }
      const vu = e.el > 0;
      a.textContent = vu ? '↑ ' + e.el.toFixed(0) + '°' : Math.round(e.alt) + ' km'; a.classList.toggle('haut', vu);
      d.textContent = vu ? 'az ' + e.az.toFixed(0) + '° · ' + Math.round(e.dist) + ' km' : '';
    });
  }
  function choisis(s) {
    OB.choisi = s; if (!s.on) { s.on = true; gardeCoches(); tab.scene.synchro(); listeSats(); }
    tab.v.querySelectorAll('.ob-sat').forEach(r => r.classList.toggle('choisi', +r.dataset.n === s.catnum));
    if (typeof obFicheTab === 'function') obFicheTab(s);
    if (OB.vue === 'globe') tab.scene.suit = s;
    if (OB.vue === 'passage') calculePassages();
  }
  function montreVue(v) {
    // The dashboard keeps its look's own view; the tab's comes back when it closes.
    OB.vue = v; if (OB.tb) { if (OB.tb.surVue) OB.tb.surVue(v); } else garde('ob-vue', v);
    tab.v.querySelectorAll('[data-ob-vue]').forEach(b => b.setAttribute('aria-pressed', b.dataset.obVue === v));
    tab.c3d.hidden = v === 'carte'; tab.c2d.hidden = v !== 'carte';
    ['ob-cadreur', 'ob-passages', 'ob-instruments', 'ob-banniere'].forEach(id => { el(id).hidden = v !== 'passage'; });
    el('ob-bulle').hidden = true;
    tab.scene.enPassage = v === 'passage';
    // A replay ends with its view: the globe and the map come back to the present, never to the replay's time.
    if (v !== 'passage' && OB.anim) temps1();
    if (v === 'passage') calculePassages();
    if (v === 'globe') tab.scene.suit = null;
    tourne();
  }

  /*
   * The passes of the satellite chosen, the next 36 hours, shown live: the one under way followed as it goes, else the
   * next one counted down. Replaying one (faster) is a button away, never the default.
   */
  function calculePassages() {
    const s = OB.choisi, box = el('ob-passages');
    OB.anim = null; OB.passage = null; OB.passagesT = performance.now(); boutonJouer();
    if (!s) { box.innerHTML = '<div class="petit">Choisis un satellite dans la liste.</div>'; return; }
    box.innerHTML = '<div class="t">' + echappe(s.nom) + ' · prochains passages</div>';
    if (!OB.obs) { box.innerHTML += '<div class="petit">La position de la station n’est pas connue : renseigne le locator (Réglages › Station).</div>'; instruments(maintenant()); return; }
    const de = maintenant();
    if (s.mm < 1.2) {
      const e = etat(s, de);
      box.innerHTML += '<div class="petit">Géostationnaire : toujours au même endroit du ciel' + (e ? ', az ' + e.az.toFixed(1) + '° el ' + e.el.toFixed(1) + '°' : '') + '. Pas de passage à animer.</div>';
      OB.passages = []; instruments(de); return;
    }
    // From a while ago: the pass under way keeps its real AOS.
    OB.passages = obPassages(t => { const e = etat(s, t); return e ? e.el : null; }, de - 20 * 60000, de + 36 * 3600000, 30000, 12).filter(p => p.los > de).slice(0, 10).map(p => {
      const piste = [];
      for (let t = p.aos; t <= p.los + 1; t += Math.max(2000, (p.los - p.aos) / 300)) { const e = etat(s, t); if (e) piste.push({ t: t, az: e.az, el: e.el }); }
      const a = etat(s, p.aos), b = etat(s, p.los);
      return Object.assign(p, { azAos: a ? a.az : 0, azLos: b ? b.az : 0, piste: piste });
    });
    if (!OB.passages.length) box.innerHTML += '<div class="petit">Aucun passage au-dessus de l’horizon dans les 36 h.</div>';
    OB.passages.forEach((p, i) => {
      const b = document.createElement('button'); b.type = 'button'; b.className = 'ob-pas'; b.setAttribute('aria-pressed', 'false'); b.dataset.i = i;
      b.innerHTML = '<b>' + (p.aos <= de ? 'en cours' : jour(p.aos) + ' ' + hh(p.aos)) + '</b><span class="ob-max">' + p.max.toFixed(0) + '°</span><span class="ob-sous">' + Math.round((p.los - p.aos) / 60000) + ' min · ' + cardinal(p.azAos) + ' → ' + cardinal(p.azLos) + (i ? ' · rejouer' : ' · en direct') + '</span>';
      // The first one live, the others replayed.
      b.onclick = () => { if (i) prendPassage(p, true); else direct(); };
      box.appendChild(b);
    });
    if (OB.passages[0]) suisPassage(OB.passages[0]);
    instruments(de);
  }
  /** [p] the pass shown: its sky track, its profile, the camera turned to its AOS. */
  function suisPassage(p) {
    OB.passage = p; tab.scene.azAos = p.azAos;
    el('ob-passages').querySelectorAll('.ob-pas').forEach(b => b.setAttribute('aria-pressed', OB.passages[+b.dataset.i] === p));
  }
  function prendPassage(p, jouer) {
    suisPassage(p);
    OB.anim = { p: p, t: p.aos - 90000, vitesse: +el('ob-pvitesse').value, joue: jouer, dernier: performance.now() };
    boutonJouer(); instruments(OB.anim.t);
  }

  function prepare(cv) {
    const r = cv.getBoundingClientRect(), d = Math.min(2, window.devicePixelRatio || 1);
    if (cv.width !== Math.round(r.width * d) || cv.height !== Math.round(r.height * d)) { cv.width = Math.round(r.width * d); cv.height = Math.round(r.height * d); }
    const x = cv.getContext('2d'); x.setTransform(d, 0, 0, d, 0, 0); return [x, r.width, r.height];
  }
  /* The pass's instruments: its sky, its elevation, the readouts, the banner over the scene. */
  function instruments(t) {
    const p = OB.passage, s = OB.choisi, e = s ? etat(s, t) : null, coul = s ? s.couleur : '#3FE0C8';
    {
      const r = prepare(el('ob-ciel')), x = r[0], w = r[1], h = r[2]; x.clearRect(0, 0, w, h);
      const cx = w / 2, cy = h / 2, R = Math.min(w, h) / 2 - 12;
      x.strokeStyle = 'rgba(154,167,180,.35)'; x.lineWidth = 1;
      [0, 30, 60].forEach(a => { x.beginPath(); x.arc(cx, cy, R * (90 - a) / 90, 0, 7); x.stroke(); });
      x.beginPath(); x.moveTo(cx - R, cy); x.lineTo(cx + R, cy); x.moveTo(cx, cy - R); x.lineTo(cx, cy + R); x.stroke();
      x.fillStyle = '#9AA7B4'; x.font = '10px ui-monospace, monospace'; x.textAlign = 'center'; x.textBaseline = 'middle';
      x.fillText('N', cx, cy - R - 7); x.fillText('S', cx, cy + R + 7); x.fillText('E', cx + R + 7, cy); x.fillText('O', cx - R - 7, cy);
      const xy = (az, a) => { const rr = R * (90 - Math.max(0, a)) / 90; return [cx + rr * Math.sin(az * D2R), cy - rr * Math.cos(az * D2R)]; };
      if (p) {
        traitTrace(x, () => p.piste.forEach((q, i) => { const c = xy(q.az, q.el); if (i) x.lineTo(c[0], c[1]); else x.moveTo(c[0], c[1]); }), coul, 1.5 + 2 * forceTrace(), 1, 'realiste');
        const a0 = xy(p.azAos, 0); x.fillStyle = '#3FE0C8'; x.fillRect(a0[0] - 3, a0[1] - 3, 6, 6);
      }
      if (e && e.el > -2) { const c = xy(e.az, e.el); x.fillStyle = '#fff'; x.shadowColor = coul; x.shadowBlur = 12; x.beginPath(); x.arc(c[0], c[1], 5, 0, 7); x.fill(); x.shadowBlur = 0; }
    }
    {
      const r = prepare(el('ob-profil')), x = r[0], w = r[1], h = r[2]; x.clearRect(0, 0, w, h);
      if (p) {
        const t0 = p.aos - 90000, t1 = p.los + 60000, X = u => 30 + (u - t0) / (t1 - t0) * (w - 40), Y = a => h - 18 - Math.max(0, a) / 90 * (h - 30);
        x.strokeStyle = 'rgba(154,167,180,.25)'; x.fillStyle = '#9AA7B4'; x.font = '10px ui-monospace, monospace'; x.textAlign = 'right'; x.textBaseline = 'middle';
        [0, 30, 60, 90].forEach(a => { x.beginPath(); x.moveTo(30, Y(a)); x.lineTo(w - 10, Y(a)); x.stroke(); x.fillText(a + '°', 25, Y(a)); });
        x.textAlign = 'center'; x.textBaseline = 'top';
        [[p.aos, 'AOS'], [p.tca, 'TCA'], [p.los, 'LOS']].forEach(q => x.fillText(q[1] + ' ' + hh(q[0]), Math.min(w - 40, Math.max(50, X(q[0]))), h - 14));
        x.beginPath(); x.moveTo(X(p.aos), Y(0)); p.piste.forEach(q => x.lineTo(X(q.t), Y(q.el))); x.lineTo(X(p.los), Y(0)); x.closePath();
        x.globalAlpha = .25; x.fillStyle = coul; x.fill(); x.globalAlpha = 1;
        x.strokeStyle = coul; x.lineWidth = 2; x.beginPath(); p.piste.forEach((q, i) => { if (i) x.lineTo(X(q.t), Y(q.el)); else x.moveTo(X(q.t), Y(q.el)); }); x.stroke();
        if (t >= t0 && t <= t1) { const tx = X(t); x.strokeStyle = OB.anim ? '#fff' : '#7CF29A'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(tx, 6); x.lineTo(tx, h - 18); x.stroke(); }
      }
    }
    let dop = '—';
    if (e && s.dl) { const e2 = etat(s, t + 1000); if (e2) dop = (s.dl * (1 - (e2.dist - e.dist) / CL)).toFixed(4) + ' MHz'; }
    el('ob-mesures').innerHTML = e && OB.obs ? '<dt>Azimut</dt><dd>' + e.az.toFixed(1) + '° ' + cardinal(e.az) + '</dd><dt>Élévation</dt><dd>' + e.el.toFixed(1) + '°</dd><dt>Distance</dt><dd>' + Math.round(e.dist) + ' km</dd><dt>Altitude</dt><dd>' + Math.round(e.alt) + ' km</dd><dt>Doppler</dt><dd>' + dop + '</dd><dt>Heure</dt><dd>' + hhs(t) + '</dd>' : '';
    banniere(t, s, e, p);
  }
  function banniere(t, s, e, p) {
    const b = el('ob-banniere');
    let texte = '', sous = '', flash = false;
    if (!s || !e) texte = '';
    else if (!p) { texte = s.nom; sous = OB.obs ? 'az ' + e.az.toFixed(1) + '° · el ' + e.el.toFixed(1) + '°' : ''; }
    else if (t < p.aos) { const n = Math.ceil((p.aos - t) / 1000);
      texte = n <= 10 ? String(n) : n >= 3600 ? 'AOS dans ' + Math.floor(n / 3600) + ' h ' + String(Math.floor(n / 60) % 60).padStart(2, '0') : 'AOS dans ' + Math.floor(n / 60) + ':' + String(n % 60).padStart(2, '0');
      sous = s.nom + ' se lève ' + (n >= 3600 ? 'à ' + hh(p.aos) + ' ' : '') + 'au ' + cardinal(p.azAos) + ' (az ' + p.azAos.toFixed(0) + '°)'; flash = n <= 10; }
    else if (t <= p.los) {
      if (Math.abs(t - p.tca) < (p.los - p.aos) * .08) { texte = 'Culmination ' + p.max.toFixed(0) + '°'; sous = hh(p.tca) + ' · ' + Math.round(e.dist) + ' km'; flash = true; }
      else if (t - p.aos < 25000) { texte = 'AOS !'; sous = s.nom + ' est là · az ' + e.az.toFixed(0) + '°'; flash = true; }
      else { texte = e.el.toFixed(0) + '°'; sous = s.nom + ' · az ' + e.az.toFixed(0) + '° · ' + Math.round(e.dist) + ' km'; }
    } else { texte = 'LOS · 73 !'; sous = s.nom + ' est passé · ' + Math.round((p.los - p.aos) / 60000) + ' min, ' + p.max.toFixed(0) + '° au plus haut'; flash = true; }
    if (b.dataset.t !== texte) {
      b.dataset.t = texte; b.textContent = texte;
      const sm = document.createElement('small'); sm.textContent = sous; b.appendChild(sm);
      if (flash) { b.classList.remove('ob-flash'); void b.offsetWidth; b.classList.add('ob-flash'); }
    } else { const sm = b.querySelector('small'); if (sm && sm.textContent !== sous) sm.textContent = sous; }
  }

  /* The flat map: the look's picture, the night, the mountains and cities, the contacts, the ground tracks, the footprints. */
  function dessine2d(t) {
    const r = prepare(tab.c2d), x = r[0], w = r[1], h = r[2], S = STYLES[OB.style], M = monde();
    x.fillStyle = S.fond; x.fillRect(0, 0, w, h);
    let mw = w, mh = w / 2; if (mh > h) { mh = h; mw = h * 2; }
    const mx = (w - mw) / 2, my = (h - mh) / 2, X = lo => mx + (lo + 180) / 360 * mw, Y = la => my + (90 - la) / 180 * mh;
    x.drawImage(texture(OB.style), mx, my, mw, mh);
    x.save(); x.beginPath(); x.rect(mx, my, mw, mh); x.clip();
    const vieux = OB.style === 'vintage', ech = mw / 1000;
    // The mountains: little peaks along their crests; the boats on the cartoon.
    if (S.relief && OB.detail >= 1) {
      const c = '#' + S.relief[0].toString(16).padStart(6, '0');
      M.sommets.forEach(p => {
        const k = Math.max(.25, Math.min(1, (p[2] - 900) / 4100)), hh2 = (5 + 9 * k) * ech, X0 = X(p[1]), Y0 = Y(p[0]);
        x.fillStyle = c; x.beginPath(); x.moveTo(X0 - hh2 * .7, Y0 + hh2 * .4); x.lineTo(X0, Y0 - hh2 * .6); x.lineTo(X0 + hh2 * .7, Y0 + hh2 * .4); x.closePath(); x.fill();
        if (S.relief[1] != null && p[2] >= 2500) { x.fillStyle = '#fff'; x.beginPath(); x.moveTo(X0 - hh2 * .25, Y0 - hh2 * .25); x.lineTo(X0, Y0 - hh2 * .6); x.lineTo(X0 + hh2 * .25, Y0 - hh2 * .25); x.closePath(); x.fill(); }
      });
    }
    if (S.carton && OB.detail >= 1) M.decor.forEach(d => { if (d[0] !== 'bateau') return; const X0 = X(d[2]), Y0 = Y(d[1]), k = 6 * ech;
      x.fillStyle = '#8e5a2e'; x.beginPath(); x.moveTo(X0 - k, Y0); x.lineTo(X0 + k, Y0); x.lineTo(X0 + k * .6, Y0 + k * .5); x.lineTo(X0 - k * .6, Y0 + k * .5); x.closePath(); x.fill();
      x.fillStyle = '#fff'; x.beginPath(); x.moveTo(X0, Y0 - k * 1.4); x.lineTo(X0, Y0 - k * .1); x.lineTo(X0 + k * .8, Y0 - k * .1); x.closePath(); x.fill(); });
    const so = obSoleil(t, satellite.gstime(new Date(t)));
    nuit2d(x, S, so, mx, my, mw, mh);
    const gros = OB.tb ? Math.max(1, mw / 1400) : 1;
    x.fillStyle = '#FFD34D'; x.shadowColor = '#FFB300'; x.shadowBlur = 14; x.beginPath(); x.arc(X(so.lon), Y(so.dec), 6 * gros, 0, 7); x.fill(); x.shadowBlur = 0;
    if (OB.tb && OB.tb.astres) { const l = obLune(t); lune2d(x, l.phase, X(l.lon), Y(l.dec), 7 * gros); }
    // The cities.
    x.font = (vieux ? 'italic ' : '') + '500 10px Drapeaux, system-ui, sans-serif'; x.textAlign = 'left'; x.textBaseline = 'middle';
    villesVues().forEach(v => { const X0 = X(v[2]), Y0 = Y(v[1]), n = nomVille(v);
      x.fillStyle = S.ville; x.beginPath(); x.arc(X0, Y0, v[3] === 1 ? 2.6 : 2, 0, 7); x.fill();
      x.lineWidth = 3; x.strokeStyle = vieux ? 'rgba(239,224,184,.9)' : 'rgba(0,0,0,.7)'; x.strokeText(n, X0 + 4, Y0); x.fillStyle = S.ville; x.fillText(n, X0 + 4, Y0); });
    monuments2d(x, X, Y, mw, S, vieux, []);
    // The log's contacts.
    if (OB.contacts !== 'aucun' && OB.carnet) {
      x.fillStyle = 'rgba(255,79,163,.35)'; x.strokeStyle = '#FF4FA3';
      if (OB.contacts === 'locators') {
        const vus = new Set(OB.carnet.map(c => c.l.slice(0, 4)));
        x.font = '600 9px ui-monospace, monospace'; x.textAlign = 'center';
        vus.forEach(k => { const q = obLocator(k); if (!q) return; const a = X(q.lon - q.dlon / 2), b = Y(q.lat + q.dlat / 2), cw = q.dlon / 360 * mw, ch = q.dlat / 180 * mh;
          x.fillStyle = 'rgba(255,79,163,.35)'; x.fillRect(a, b, cw, ch); x.lineWidth = 1; x.strokeRect(a, b, cw, ch);
          if (cw > 22) { x.fillStyle = '#FFD1E8'; x.fillText(k, a + cw / 2, b + ch / 2); } });
      } else {
        const vus = new Map(); OB.carnet.forEach(c => vus.set(c.c + '|' + c.l, c));
        x.font = '600 10px system-ui, sans-serif'; x.textAlign = 'left';
        vus.forEach(c => { const q = obLocator(c.l); const X0 = X(q.lon), Y0 = Y(q.lat);
          x.fillStyle = '#FF4FA3'; x.beginPath(); x.arc(X0, Y0, 3, 0, 7); x.fill(); x.lineWidth = 1; x.strokeStyle = 'rgba(0,0,0,.6)'; x.stroke();
          if (OB.contacts === 'indicatifs') { x.lineWidth = 3; x.strokeStyle = 'rgba(0,0,0,.7)'; x.strokeText(c.c, X0 + 4, Y0 - 5); x.fillStyle = '#FFD1E8'; x.fillText(c.c, X0 + 4, Y0 - 5); } });
      }
    }
    const pts = [], plusieurs = tab.scene.objets.size > 1;
    tab.scene.objets.forEach(o => {
      const s = o.s, e = etat(s, t); if (!e) return;
      const lui = s === OB.choisi, efface = plusieurs && !lui;
      if (!o.trace || Math.abs(t - o.tOrbite) > s.periode * .05) tab.scene.orbite(o, t, lui);
      const c = S.encre || s.couleur;
      // Its ground track, as marked as « Traces » asks: ahead dashed and vivid, behind paler.
      const k = forceTrace(), rt = reglageTrace(k), lg = lui ? 2 + 3 * k : 1 + 1.6 * k;
      [false, true].forEach(futur => {
        x.setLineDash(futur ? [5 + 4 * k, 4] : []);
        traitTrace(x, () => {
          let prec = null;
          o.trace.forEach(q => { if ((q[0] > t) !== futur) return; if (prec != null && Math.abs(q[2] - prec) < 180) x.lineTo(X(q[2]), Y(q[1])); else x.moveTo(X(q[2]), Y(q[1])); prec = q[2]; });
        }, c, lg, (futur ? rt.alpha : rt.passe) * (efface ? .55 : 1), OB.style);
      });
      x.setLineDash([]); x.globalAlpha = 1;
      const emp = obEmpreinte(e.lat, e.lon, Math.acos(RE / (RE + e.alt)));
      [-360, 0, 360].forEach(d => {
        x.beginPath(); emp.forEach((q, i) => { if (i) x.lineTo(X(q[1] + d), Y(q[0])); else x.moveTo(X(q[1] + d), Y(q[0])); }); x.closePath();
        x.globalAlpha = lui ? (OB.style === 'cartoon' ? .32 : .24) : efface ? .07 : .17; x.fillStyle = c; x.fill();
        x.globalAlpha = lui ? 1 : efface ? .45 : .9; x.lineWidth = lui ? 2.6 : 1.2; x.strokeStyle = c; x.stroke(); x.globalAlpha = 1;
      });
      pts.push({ s: s, e: e, x: X(e.lon), y: Y(e.lat), c: c, lui: lui });
    });
    const d = OB.donnees;
    if (d && d.obs) {
      x.fillStyle = '#FFB454'; x.strokeStyle = '#1b0f00'; x.lineWidth = 1.5;
      x.beginPath(); x.arc(X(d.obs.lon), Y(d.obs.lat), 5, 0, 7); x.fill(); x.stroke();
      x.font = '600 11px system-ui, sans-serif'; x.textAlign = 'left'; x.textBaseline = 'middle';
      x.fillStyle = vieux ? '#4a2e14' : '#FFCF8A'; x.fillText(d.indicatif || 'Station', X(d.obs.lon) + 8, Y(d.obs.lat) + 1);
    }
    pts.sort((a, b) => a.lui - b.lui).forEach(p => {
      const vu = p.e.el > 0, rr = p.lui ? 8 : vu ? 6 : 4.5;
      x.globalAlpha = p.lui || !plusieurs ? 1 : .75;
      x.fillStyle = p.c; x.shadowColor = p.c; x.shadowBlur = vu || p.lui ? 16 : 6;
      x.beginPath(); x.arc(p.x, p.y, rr, 0, 7); x.fill(); x.shadowBlur = 0;
      x.strokeStyle = vieux ? '#efe0b8' : 'rgba(0,0,0,.7)'; x.lineWidth = 1.5; x.stroke();
      if (p.lui && plusieurs) { x.strokeStyle = vieux ? '#4a2e14' : '#fff'; x.lineWidth = 2; x.beginPath(); x.arc(p.x, p.y, rr + 5 + 2 * Math.sin(performance.now() / 250), 0, 7); x.stroke(); }
      x.font = (p.lui ? '800 13px' : '600 12px') + ' system-ui, sans-serif'; x.textAlign = 'left'; x.textBaseline = 'middle';
      const txt = (p.lui && plusieurs ? '★ ' : '') + p.s.nom + (vu ? ' ↑' + p.e.el.toFixed(0) + '°' : ''), tw = x.measureText(txt).width;
      x.fillStyle = p.lui && plusieurs ? p.c : vieux ? 'rgba(239,224,184,.85)' : 'rgba(0,0,0,.55)'; x.fillRect(p.x + 10, p.y - 19, tw + 8, 18);
      x.fillStyle = p.lui && plusieurs ? '#0a1230' : vieux ? '#4a2e14' : p.s.couleur; x.fillText(txt, p.x + 14, p.y - 10);
      x.globalAlpha = 1;
    });
    x.restore();
    tab.points = pts;
    // The dashboard's explanations point at these.
    if (OB.tb && OB.tb.pedago) {
      const q = pts.find(p => p.lui), R = {};
      if (q) { R.sat = [q.x, q.y]; const e2 = obEmpreinte(q.e.lat, q.e.lon, Math.acos(RE / (RE + q.e.alt)), 8); R.emp = [X(e2[2][1]), Y(e2[2][0])]; }
      if (d && d.obs) R.station = [X(d.obs.lon), Y(d.obs.lat)];
      let ln = so.lon + 90; if (ln > 180) ln -= 360; if (Math.abs(ln) > 150) ln = so.lon - 90;
      R.nuit = [X(ln), Y(0)];
      tab.reperes2d = R;
    }
  }

  return {
    /** The Orbites tab shown. */
    montre: async function () {
      const v = el('v-orbites'), att = el('ob-attente');
      att.hidden = false; att.textContent = 'Chargement du globe…';
      // Each time the tab opens: the present time at its real pace (the speed can be raised again), never a replay or a pause.
      temps1();
      try { await charge(); } catch (x) { att.textContent = 'Le globe n’a pas pu se charger : ' + (x && x.message ? x.message : 'erreur') + '. Recharge la page.'; return; }
      if (v.hidden) return;
      prepareTab(); att.hidden = true;
      listeSats(); montreVue(OB.vue);
      if (typeof obFicheTab === 'function') obFicheTab(OB.choisi);
      rafraichit(); chargeCarnet();
    },
    /** The Suivi tab's globe shown in [canvas]. */
    suivi: async function (canvas) {
      try { await charge(); } catch (x) { return false; }
      if (!suivi) { suivi = Scene(canvas, 'suivi'); scenes.push(suivi); }
      if (Date.now() - (OB.lu || 0) > 60000) { OB.lu = Date.now(); rafraichit(); }
      chargeCarnet(); tourne(); return true;
    },
    /**
     * The journal's globe in [canvas]: the pass [d] = {id, sat, sol: [[t, lat, lon, altKm]…], qth: [lat, lon], indicatif, locator,
     * marques: [{k, de, a, t, lat?, lon?, f?}], image: m => the picture's address, horloge: () => the replay's time,
     * tle?: [line 1, line 2] (a session: the satellite between its passes)}.
     */
    rejeu: async function (canvas, d) {
      try { await chargeLibs(); } catch (x) { return false; }
      if (!rejeu || rejeu.canvas !== canvas) {
        if (rejeu) { rejeu.retire(); scenes.splice(scenes.indexOf(rejeu), 1); }
        rejeu = Scene(canvas, 'rejeu'); scenes.push(rejeu); rejeu.enPassage = true;
      }
      if (!rejeu.rj || rejeu.rj.donnees.id !== d.id) {
        if (rejeu.rj && rejeu.rj.marques) rejeu.rj.marques.forEach(o => o.retire && o.retire());
        const sol = (d.sol || []).slice().sort((a, b) => a[0] - b[0]);
        let satrec = null; if (d.tle) { try { satrec = satellite.twoline2satrec(d.tle[0], d.tle[1]); } catch (x) {} }
        rejeu.rj = { donnees: d, station: d.qth ? { lat: d.qth[0], lon: d.qth[1], indicatif: d.indicatif, locator: d.locator } : null,
          sat: { nom: d.sat, catnum: -1, piste: sol, satrec: satrec, couleur: '#FF8A1F', periode: 5400000, mm: 15 } };
        rejeu.synchro(); rejeu.rejeuPrepare();
      }
      rejeu.rj.donnees = d;
      tourne(); return true;
    },
    /**
     * The journal's session: other satellites going round with its own, [l] = [{nom, l1, l2}] (their colours in this order,
     * as its videos draw them), their footprints shown or not ([taches]).
     */
    compagnons: (l, taches) => {
      if (!rejeu || !rejeu.rj) return;
      const cle = (l || []).map(s => s.nom).join('|');
      rejeu.rj.taches = !!taches;
      if (rejeu.rj.cleAutres === cle) return;
      rejeu.rj.cleAutres = cle;
      rejeu.rj.autres = [];
      (l || []).forEach((s, i) => {
        let satrec = null; try { satrec = satellite.twoline2satrec(s.l1, s.l2); } catch (x) {}
        if (!satrec) return;
        const mm = parseFloat(s.l2.slice(52, 63)) || 15;
        rejeu.rj.autres.push({ nom: s.nom, catnum: 'c' + i + ':' + s.nom, satrec: satrec, mm: mm, periode: 1440 / mm * 60000, couleur: COMPAGNONS[i % COMPAGNONS.length] });
      });
      rejeu.synchro();
    },
    /** The station's favourites (then the others), with their elements: [{nom, catnum, fav, l1, l2}]. */
    favoris: async () => { try { const d = await (await fetch(api('api/orbites'))).json(); const f = d.favoris || [];
      return (d.sats || []).map(s => ({ nom: s.nom, catnum: s.catnum, fav: f.includes(s.catnum), l1: s.l1, l2: s.l2 })).sort((a, b) => (b.fav - a.fav) || a.nom.localeCompare(b.nom)); } catch (x) { return []; } },
    /**
     * The Sked tab's globe in [canvas]: the window [d] = {id, sat, points: [[t, lat, lon, altKm]…] (the track, both passes),
     * de, a (the common window, ms), moi: {lat, lon, indicatif, locator}, autre: {lat, lon, locator}, horloge: () => the replay's time}.
     * Its look and settings are the Orbites tab's; its camera the passes' (poursuite, from my station, or both stations framed).
     */
    sked: async function (canvas, d) {
      try { await chargeLibs(); } catch (x) { return false; }
      if (!sked || sked.canvas !== canvas) {
        if (sked) { sked.retire(); scenes.splice(scenes.indexOf(sked), 1); }
        sked = Scene(canvas, 'sked'); scenes.push(sked); sked.enPassage = true;
      }
      if (!sked.sk || sked.sk.donnees.id !== d.id) {
        const l = (d.points || []).slice().sort((a, b) => a[0] - b[0]);
        sked.sk = { donnees: d, moi: d.moi, autre: d.autre, sat: { nom: d.sat, catnum: 'sked', piste: l, couleur: '#3FE0C8', periode: 5400000, mm: 15 } };
        sked.libre = false; sked.synchro(); sked.skedPrepare();
      }
      sked.sk.donnees = d;
      tourne(); return true;
    },
    /** The Sked's globe framed by itself again (after a drag). */
    skedRecadre: () => { if (sked) sked.libre = false; },
    /** The journal's globe: where its satellite shows (px from the canvas's left), null when unknown. */
    satEcranX: () => rejeu ? rejeu.satX : null,
    /**
     * The dashboard's figures, at the present time (not the tab's): the station {indicatif, locator, lat, lon}, the Sun's
     * elevation and azimuth there, the satellite chosen {nom, couleur} and where it is {az, el, dist, alt, lat, lon}, its Doppler (MHz).
     */
    tableau: () => {
      const d = OB.donnees; if (!d || typeof satellite === 'undefined') return null;
      const t = Date.now(), s = OB.choisi, e = s ? etat(s, t) : null, so = obSoleil(t, satellite.gstime(new Date(t))), o = d.obs;
      let soleil = null, dop = null;
      if (o) {
        const f = o.lat * D2R, dc = so.dec * D2R, H = (o.lon - so.lon) * D2R;
        const el = Math.asin(Math.sin(f) * Math.sin(dc) + Math.cos(f) * Math.cos(dc) * Math.cos(H));
        const az = Math.atan2(-Math.cos(dc) * Math.sin(H), Math.sin(dc) * Math.cos(f) - Math.cos(dc) * Math.sin(f) * Math.cos(H));
        soleil = { el: el * R2D, az: ((az * R2D) + 360) % 360 };
      }
      if (e && s.dl && OB.obs) { const e2 = etat(s, t + 1000); if (e2) dop = s.dl * (1 - (e2.dist - e.dist) / CL); }
      // For the explanations: its orbit's tilt, its speed, one turn, how far it hears, how far west its next track falls.
      const v = e && e.vel ? Math.hypot(e.vel.x, e.vel.y, e.vel.z) : null;
      return { t: t, indicatif: d.indicatif || '', locator: d.locator || '', obs: o || null, soleil: soleil, sat: s ? { nom: s.nom, catnum: s.catnum, couleur: s.couleur, dl: s.dl,
          incl: s.satrec ? s.satrec.inclo * R2D : null, periode: s.periode / 60000, geo: s.mm < 1.2 } : null,
        e: e ? { az: e.az, el: e.el, dist: e.dist, alt: e.alt, lat: e.lat, lon: e.lon, vitesse: v, rayon: RE * Math.acos(RE / (RE + e.alt)) } : null, vu: !!OB.obs, dop: dop,
        decalage: s ? 360 * s.periode / 86164091 : null };
    },
    /**
     * The next passes, present time, of the satellites shown on the globe (else the favourites), up to [n]: [{nom, couleur, aos, los, max}],
     * the one under way first. Each satellite's passes worked out a few at a time and kept until they are over (or half an hour).
     */
    prochains: n => {
      if (!OB.obs || typeof satellite === 'undefined') return [];
      const t = Date.now(), cache = OB.prochains || (OB.prochains = new Map());
      let l = OB.sats.filter(s => s.on && s.mm >= 1.2);
      if (!l.length) l = OB.sats.filter(s => (s.fav || s.suivi) && s.mm >= 1.2);
      l = l.slice(0, 40);
      let budget = 3;
      l.forEach(s => {
        const c = cache.get(s.catnum);
        if (c && c.satrec === s.satrec && t - c.t < 1800000 && (!c.p.length || c.p[0].los > t)) return;
        if (budget-- <= 0) return;
        cache.set(s.catnum, { t: t, satrec: s.satrec, p: obPassages(u => { const e = etat(s, u); return e ? e.el : null; }, t, t + 86400000, 60000, 2) });
      });
      const out = [];
      l.forEach(s => { const c = cache.get(s.catnum); if (c) c.p.forEach(p => { if (p.los > t) out.push({ nom: s.nom, catnum: s.catnum, couleur: s.couleur, aos: p.aos, los: p.los, max: p.max }); }); });
      return out.sort((a, b) => a.aos - b.aos).slice(0, n || 6);
    },
    /** The tab's time back to the present, at its real pace (the dashboard shows now). */
    tempsReel: () => direct(),
    /**
     * The dashboard open ([o]: {style, vue, tourne, astres, pedago, vitrine, place(w, h) → the globe's {x, y, s} on the screen, surVue(v)}) or closed (null): its look and view taken without
     * being kept, the tab's own given back when it closes. The Vitrine's ([o.vitrine]): 30 s of every 90 the Earth seen from the satellite chosen.
     */
    tableauMode: o => {
      const avant = OB.tb; OB.tb = o || null;
      const style = o ? o.style : lit('ob-style2', 'cartoon'), vue = o ? o.vue : lit('ob-vue', 'globe');
      if (style && STYLES[style] && style !== OB.style) { OB.style = style; scenes.forEach(sc => sc.appliqueStyle(style)); marqueStyle(); }
      else if (!avant !== !o) scenes.forEach(sc => { if (sc.mode === 'tab') sc.monde(); });
      if (tab && vue && vue !== OB.vue) montreVue(vue);
      if (tab) { tab.scene.reperes = null; tab.reperes2d = null; tab.scene.geste = 0; }
    },
    /** Where the things explained show over the tab's globe or map (px): {sat, emp, station, nuit} → [x, y]; null when not drawn. */
    reperes: () => !tab ? null : OB.vue === 'carte' ? tab.reperes2d : OB.vue === 'globe' ? tab.scene.reperes : null,
    /**
     * The Sun and the Moon at the present time: where they stand above, their height at the station, the Moon's phase, the station's
     * next sunrise, sunset, dawn and dusk (worked out again every ten minutes).
     */
    astres: () => {
      const t = Date.now(), s = obSoleil(t, obGmst(t)), l = obLune(t), o = OB.donnees && OB.donnees.obs;
      if (o && (!OB.jour || t - OB.jour.t > 600000 || OB.jour.lat !== o.lat || OB.jour.lon !== o.lon)) OB.jour = { t: t, lat: o.lat, lon: o.lon, j: obSoleilJour(t, o.lat, o.lon) };
      return { soleil: { lat: s.dec, lon: s.lon, ici: o ? azEl(o.lat, o.lon, s.dec, s.lon) : null }, lune: Object.assign({ ici: o ? azEl(o.lat, o.lon, l.dec, l.lon) : null }, l),
        jour: o ? OB.jour.j : null };
    },
    /** The station's satellites [{nom, catnum, couleur, fav}] (the Vitrine's cards pick among them). */
    satellites: () => OB.sats.map(s => ({ nom: s.nom, catnum: s.catnum, couleur: s.couleur, fav: s.fav || s.suivi })),
    /**
     * Where satellite [n] is now, for the ISS's live window: {nom, lat, lon, alt, vitesse (km/s), soleil (the Sun's height
     * under it, °), terre (over land, from the coastline), az, el, vu (the station known)}; null when it has no elements.
     */
    sousSat: n => {
      const s = OB.sats.find(x => x.catnum === n); if (!s || typeof satellite === 'undefined') return null;
      const t = Date.now(), e = etat(s, t); if (!e) return null;
      // Over land: a ray to the east crossing the coastline's rings an odd number of times.
      let terre = false;
      (OB.terres || []).forEach(l => { for (let i = 0, j = l.length - 2; i < l.length; j = i, i += 2) {
        const yi = l[i + 1], yj = l[j + 1];
        if ((yi > e.lat) !== (yj > e.lat) && e.lon < l[j] + (e.lat - yj) / (yi - yj) * (l[i] - l[j])) terre = !terre; } });
      return { nom: s.nom, lat: e.lat, lon: e.lon, alt: e.alt, vitesse: e.vel ? Math.hypot(e.vel.x, e.vel.y, e.vel.z) : null,
        soleil: obHauteurSoleil(t, e.lat, e.lon), terre: terre, az: e.az, el: e.el, vu: !!OB.obs };
    },
    /** Satellite [n] chosen as from the tab's list (shown, followed by the views); false when the station has no elements for it. */
    choisir: n => { const s = OB.sats.find(x => x.catnum === n); if (!s || !tab) return false; choisis(s); listeSats(); return true; },
    monuments: v => prendMonuments(v),
    monumentsActifs: () => OB.monuments,
    /** The landmarks' size (0.7 small … 2.4 very big), kept in this browser. */
    monumentsTaille: n => prendMonTaille(n),
    monumentsTailleActive: () => OB.monTaille,
    /** How marked the tracks are (0 discreet … 100 very marked), kept in this browser. */
    traces: n => prendTraces(n),
    tracesActives: () => OB.traces,
    /** The landmarks' families to tick, in [box]. */
    typesMonuments: box => typesMon(box),
    /** The landmarks shown now [[nom, type, pays]], for the dashboard's ticker. */
    monumentsListe: () => monumentsVus('').map(m => [m[1], m[4], m[5], m[2], m[3]]),
    style: k => prendStyle(k),
    villes: v => prendVilles(v),
    etiquettes: v => prendEtiq(v),
    villesActives: () => OB.villes,
    etiquettesActives: () => OB.etiq,
    /** The journal's globe as framed (for its videos): moved by [gdx], [gdy] (shares of the view), scaled by [gz]. */
    cadrage: () => ({ gdx: rejeu ? +rejeu.decal.x.toFixed(3) : 0, gdy: rejeu ? +rejeu.decal.y.toFixed(3) : 0, gz: +Math.max(.5, Math.min(3, 3.5 / OB.hauteur)).toFixed(2) }),
    /** The station's elements of [nom] (a session's satellite between its passes), null when unknown. */
    elements: async nom => { try { const d = await (await fetch(api('api/orbites'))).json(); const s = (d.sats || []).find(x => x.nom === nom); return s ? [s.l1, s.l2] : null; } catch (x) { return null; } },
    detail: n => prendDetail(n),
    contacts: v => prendContacts(v),
    camera: c => { OB.cam = c; garde('ob-cam', c); marqueCam(); },
    styles: () => Object.keys(STYLES).map(k => [k, STYLES[k].nom]),
    styleActif: () => OB.style,
    detailActif: () => OB.detail,
    cameraActive: () => OB.cam
  };
})();
"""
}
