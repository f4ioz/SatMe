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

    /** The page's script, in two parts (a constant holds at most 64 KB). */
    val JS: String get() = DEBUT + FIN

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
/** A position at [t] along a track [[t, lat, lon, altKm]…] (sorted by time), longitudes interpolated the short way; null outside it. */
function obPisteA(l, t) {
  if (!l || !l.length || t < l[0][0] || t > l[l.length - 1][0]) return null;
  let a = 0, b = l.length - 1;
  while (b - a > 1) { const m = (a + b) >> 1; if (l[m][0] <= t) a = m; else b = m; }
  const p = l[a], q = l[b], k = q[0] > p[0] ? (t - p[0]) / (q[0] - p[0]) : 0;
  let dl = q[2] - p[2]; if (dl > 180) dl -= 360; if (dl < -180) dl += 360;
  let lon = p[2] + dl * k; lon = ((lon + 540) % 360) - 180;
  return { lat: p[1] + (q[1] - p[1]) * k, lon: lon, alt: p[3] + (q[3] - p[3]) * k };
}

/* ------------------------------------------------------------ the globes */
const Orbites = (function () {
  const RE = 6378.137, D2R = Math.PI / 180, R2D = 180 / Math.PI, CL = 299792.458;
  const PALETTE = ['#FF6B6B', '#FFD166', '#3FE0C8', '#5AB8FF', '#C792FF', '#FF9F43', '#FF5FA2', '#9BE15D', '#7EF0FF', '#FFA8C5', '#F7F06D', '#B0C4FF'];
  const el = id => document.getElementById(id);
  const lit = (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : v; } catch (x) { return d; } };
  const garde = (k, v) => { try { localStorage.setItem(k, v); } catch (x) {} };
  // The station's routes: beside the page, or under SATME_RACINE (the journal's page sets it).
  const api = p => (window.SATME_RACINE || '') + p;
  const monde = () => typeof GLOBE_MONDE !== 'undefined' ? GLOBE_MONDE : { villes: [], sommets: [], monuments: [], decor: [] };
  const OB = { donnees: null, sats: [], obs: null, terres: null, style: lit('ob-style2', 'cartoon'), vue: lit('ob-vue', 'globe'), filtre: 'favoris',
    choisi: null, temps: { base: Date.now(), reel: performance.now(), vitesse: 60, pause: false }, anim: null, passages: [], passage: null,
    cam: lit('ob-cam', 'poursuite'), hauteur: parseFloat(lit('ob-hauteur', '3.5')) || 3.5,
    detail: Math.max(0, Math.min(2, parseInt(lit('ob-detail', '1'), 10) || 0)), contacts: lit('ob-contacts', 'aucun'), carnet: null, carnetLu: 0 };
  let libs = null, donnees = null, scenes = [], boucle = false, tab = null, suivi = null, rejeu = null;

  /* Libraries and the coastline: fetched once, when a globe is first shown; the station's elements for the tab and the Suivi. */
  function script(src) { return new Promise((ok, ko) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = ko; document.head.appendChild(s); }); }
  function chargeLibs() {
    if (libs) return libs;
    libs = (async () => {
      if (typeof THREE === 'undefined') await script(api('api/lib/three.js'));
      if (typeof satellite === 'undefined') await script(api('api/lib/satellite.js'));
      OB.terres = await fetch(api('api/carte/traits')).then(r => r.json());
      OB.relief = await chargeRelief().catch(() => null);
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
  function reliefSur(x, w, h, teinte, neige, force) {
    const R = OB.relief; if (!R) return;
    const W = 1440, H = 720, c = document.createElement('canvas'); c.width = W; c.height = H;
    const cx = c.getContext('2d'), d = cx.createImageData(W, H), p = d.data;
    for (let k = 0; k < W * H; k++) {
      const a = R.alt[k]; if (a <= 0) continue;
      let r = 0, g = 0, b = 0, al = 0;
      if (teinte && a > 800) { const t = Math.min(1, (a - 800) / 2600) * .55; r = teinte[0]; g = teinte[1]; b = teinte[2]; al = t; }
      if (neige && a > 3800) { const n = Math.min(1, (a - 3800) / 1200) * .8; r += (250 - r) * n; g += (252 - g) * n; b += (255 - b) * n; al = al + (1 - al) * n; }
      const o = R.ombre[k] * force;
      if (o < 0) { const s = -o * .45; r *= 1 - s; g *= 1 - s; b *= 1 - s; al = al + (1 - al) * s; }
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
    scenes.forEach(sc => { if (sc.mode !== 'rejeu') sc.synchro(); });
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
    if (s.satrec) {
      const r = satellite.propagate(s.satrec, new Date(t));
      if (!r || !r.position || typeof r.position !== 'object') return null;
      eci = r.position; vel = r.velocity;
      const gd = satellite.eciToGeodetic(eci, gm); lat = gd.latitude * R2D; lon = gd.longitude * R2D; alt = gd.height;
      ecf = satellite.eciToEcf(eci, gm);
    } else {
      const p = obPisteA(s.piste, t); if (!p) return null;
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
        reliefSur(x, w, h, [196, 140, 84], true, 1.3);
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
  const villesVues = () => monde().villes.filter(v => v[3] <= OB.detail + 1);

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
    const police = (genre === 'ville' && style === 'vintage' ? 'italic ' : '') + (genre === 'fort' ? '800 ' : '600 ') + fs + 'px system-ui, -apple-system, "Segoe UI", sans-serif';
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

    private const val FIN = """  /* ------------------------------------------------------------ the cartoon's world: mountains, landmarks, scenery */
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
   * 'rejeu' (the journal: one pass replayed from its recorded track, with its contacts and pictures at their time).
   */
  function Scene(canvas, mode) {
    const sc = { canvas: canvas, mode: mode, objets: new Map(), style: null, rj: null };
    const rendu = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, logarithmicDepthBuffer: true });
    rendu.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(42, 1, .0002, 200);
    const terre = new THREE.Group(); scene.add(terre);
    const soleil = new THREE.DirectionalLight(0xffffff, 1.25), amb = new THREE.AmbientLight(0x9fb0d0, .18); scene.add(soleil, amb);
    let sol = null, extras = [], mondeGp = new THREE.Group(), contactsGp = new THREE.Group(), rejeuGp = new THREE.Group(), animes = [];
    terre.add(mondeGp, contactsGp, rejeuGp);
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
    const cardinaux = [['N', 0], ['E', 90], ['S', 180], ['O', 270]].map(c => { const s = etiquette(c[0], '#FFB454'); s.userData.az = c[1]; s.visible = false; scene.add(s); return s; });
    // The satellite chosen: a ring turning round it.
    const cible = new THREE.Sprite(new THREE.SpriteMaterial({ map: rond('#FFFFFF', true), depthTest: false, transparent: true, sizeAttenuation: false })); cible.renderOrder = 9; scene.add(cible);
    sc.lieu = () => mode === 'rejeu' ? (sc.rj && sc.rj.station) : (OB.donnees && OB.donnees.obs ? { lat: OB.donnees.obs.lat, lon: OB.donnees.obs.lon, indicatif: OB.donnees.indicatif, locator: OB.donnees.locator } : null);
    sc.obs = () => { if (mode !== 'rejeu') return OB.obs; const l = sc.lieu(); return l ? { latitude: l.lat * D2R, longitude: l.lon * D2R, height: 0 } : null; };
    sc.placeStation = () => {
      const d = sc.lieu(); if (!d) { station.visible = nomStation.visible = false; return; }
      const p = local(d.lat, d.lon, 1);
      posStation.copy(local(d.lat, d.lon, 1.0003));
      pin.position.copy(p.clone().multiplyScalar(1.015)); pin.lookAt(0, 0, 0); pin.rotateX(-Math.PI / 2);
      anneau.position.copy(p.clone().multiplyScalar(1.003)); anneau.lookAt(p.clone().multiplyScalar(2));
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
      if (carton && OB.detail >= 1) {
        M.monuments.forEach(m => { const o = monument(m[0]); mondeGp.add(pose(o, m[2], m[3], 0, .999)); o.traverse(c => { if (c.userData.anime) animes.push(c); }); });
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
        vs.forEach(v => { const s = etiquette(v[0], S.ville, sc.style, 'ville'); s.position.copy(local(v[1], v[2], carton ? 1.012 : 1.006)); s.center.set(-.05, .5); s.userData.ville = v[3]; mondeGp.add(s); });
      }
    };
    /* The log's contacts: dots, dots and callsigns, or the squares worked. */
    sc.contacts = () => {
      while (contactsGp.children.length) contactsGp.remove(contactsGp.children[0]);
      if (mode === 'rejeu' || OB.contacts === 'aucun' || !OB.carnet) return;
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
      sc.placeStation(); sc.monde(); sc.contacts(); if (mode === 'rejeu') sc.rejeuPrepare();
    };

    function objet(s) {
      const o = { s: s };
      o.engin = engin(s.couleur); o.lueur = lueur(s.couleur);
      o.orbite = new THREE.Line(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: s.couleur, transparent: true, opacity: .7 }));
      o.tube = new THREE.Mesh(new THREE.BufferGeometry(), new THREE.MeshBasicMaterial({ color: s.couleur, transparent: true, opacity: .95 }));
      o.cal = new THREE.Mesh(calotte(), new THREE.MeshBasicMaterial({ color: s.couleur, transparent: true, opacity: .14, depthWrite: false, side: THREE.DoubleSide }));
      const lg = new THREE.BufferGeometry(); lg.setAttribute('position', new THREE.BufferAttribute(new Float32Array(NC * 3), 3));
      o.bordCal = new THREE.LineLoop(lg, new THREE.LineBasicMaterial({ color: s.couleur, transparent: true, opacity: .8 }));
      o.rayon = new THREE.Line(new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(), new THREE.Vector3()]), new THREE.LineBasicMaterial({ color: 0xffb454, transparent: true, opacity: .9 }));
      scene.add(o.engin, o.lueur, o.orbite, o.tube, o.rayon); terre.add(o.cal, o.bordCal);
      o.restyle = () => {
        [o.nom, o.nomFort].forEach(n => { if (n) scene.remove(n); });
        o.nom = etiquette(s.nom, s.couleur, sc.style, 'sat'); o.nomFort = etiquette('★ ' + s.nom, s.couleur, sc.style, 'fort'); scene.add(o.nom, o.nomFort);
        const c = STYLES[sc.style].encre || s.couleur;
        o.orbite.material.color.set(c); o.tube.material.color.set(c); o.bordCal.material.color.set(c); o.cal.material.color.set(c);
      };
      o.retire = () => { scene.remove(o.engin, o.lueur, o.orbite, o.tube, o.rayon, o.nom, o.nomFort); terre.remove(o.cal, o.bordCal); };
      o.tOrbite = -1e15;
      if (sc.style) o.restyle();
      return o;
    }
    function orbite(o, t, lui) {
      const s = o.s, pts = [], trace = [], obs = sc.obs();
      if (s.piste) {
        // A journal pass: its recorded track, each point where the satellite was then.
        const l = s.piste, pas = Math.max(1, Math.floor(l.length / 240));
        for (let i = 0; i < l.length; i += pas) { const e = etat(s, l[i][0], obs); if (e) { pts.push(eciVers3(e.eci)); trace.push([l[i][0], e.lat, e.lon]); } }
      } else {
        const P = s.periode, n = 240;
        for (let i = 0; i <= n; i++) { const ti = t - P / 2 + P * i / n, e = etat(s, ti, null); if (!e) continue; pts.push(eciVers3(e.eci)); trace.push([ti, e.lat, e.lon]); }
      }
      o.orbite.geometry.dispose(); o.orbite.geometry = new THREE.BufferGeometry().setFromPoints(pts);
      o.tube.geometry.dispose();
      o.tube.geometry = lui && pts.length > 3 ? new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts, !s.piste && s.mm > 1.2), pts.length, sc.style === 'cartoon' ? .0045 : .003, 6, false) : new THREE.BufferGeometry();
      o.trace = trace; o.tOrbite = t; o.tubeLui = lui;
    }
    sc.orbite = orbite;
    sc.choisi = () => mode === 'suivi' ? OB.sats.find(s => s.suivi) : mode === 'rejeu' ? (sc.rj && sc.rj.sat) : OB.choisi;
    sc.montres = () => mode === 'rejeu' ? (sc.rj ? [sc.rj.sat] : []) : mode === 'suivi' ? OB.sats.filter(s => s.suivi || s.fav) : OB.sats.filter(s => s.on);
    sc.synchro = () => {
      const l = sc.montres(), ids = new Set(l.map(s => s.catnum));
      sc.objets.forEach((o, n) => { if (!ids.has(n) || !l.includes(o.s)) { o.retire(); sc.objets.delete(n); } });
      l.forEach(s => { if (!sc.objets.has(s.catnum)) sc.objets.set(s.catnum, objet(s)); });
      sc.placeStation();
    };

    /* The journal: the pass's contacts where they were, their pictures landing at the station once received. */
    sc.rejeuPrepare = () => {
      while (rejeuGp.children.length) rejeuGp.remove(rejeuGp.children[0]);
      if (mode !== 'rejeu' || !sc.rj) return;
      const rj = sc.rj;
      if (rj.marques) rj.marques.forEach(o => o.retire && o.retire());
      rj.marques = [];
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
          o.carte.visible = t >= m.a;
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
    const cam = { az: 0, pol: 1, dist: mode === 'suivi' ? 2.8 : 3.4, pos: new THREE.Vector3(0, 0, 4), cible: new THREE.Vector3(), cale: false };
    sc.cam = cam;
    let tir = null;
    canvas.addEventListener('pointerdown', e => { tir = { x: e.clientX, y: e.clientY }; canvas.setPointerCapture(e.pointerId); });
    canvas.addEventListener('pointermove', e => {
      if (!tir) return;
      const k = .005 * Math.min(1, cam.dist / 3);
      cam.az -= (e.clientX - tir.x) * k; cam.pol = Math.max(.05, Math.min(Math.PI - .05, cam.pol - (e.clientY - tir.y) * k));
      tir = { x: e.clientX, y: e.clientY }; sc.suit = null;
    });
    canvas.addEventListener('pointerup', () => { tir = null; });
    canvas.addEventListener('wheel', e => {
      e.preventDefault();
      if (sc.enPassage) { OB.hauteur = Math.max(.6, Math.min(8, OB.hauteur * (1 + Math.sign(e.deltaY) * .1))); garde('ob-hauteur', OB.hauteur.toFixed(2)); document.querySelectorAll('.ob-hauteur-r').forEach(h => { h.value = OB.hauteur; }); }
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
      const dt = Math.min(.1, (now - derniere) / 1000); derniere = now;
      const gm = satellite.gstime(new Date(t));
      terre.rotation.y = gm;
      soleil.position.copy(eciVers3(obSoleil(t, gm).eci, 10));
      extras.forEach(e => { if (e.userData.tourne && !reduit) e.rotation.y += e.userData.tourne * dt; });
      if (!reduit) animes.forEach(o => o.userData.anime(o, now));
      terre.updateMatrixWorld(true);
      const stW = terre.localToWorld(posStation.clone()), obs = sc.obs();
      const passage = !!sc.enPassage, auSol = passage && OB.cam === 'sol', lieu = !!sc.lieu();
      const choisi = sc.choisi();
      extras.forEach(e => { e.visible = !auSol || !!e.isPoints; });
      mondeGp.visible = !auSol;
      station.visible = !auSol && lieu;
      const fov = auSol ? 75 : 42; if (camera.fov !== fov) { camera.fov = fov; camera.updateProjectionMatrix(); }
      const plusieurs = sc.objets.size > 1;
      cible.visible = false;
      let satP = null;
      sc.objets.forEach(o => {
        const s = o.s, e = etat(s, t, obs), lui = s === choisi;
        if (!e) { [o.engin, o.lueur, o.orbite, o.tube, o.cal, o.bordCal, o.nom, o.nomFort, o.rayon].forEach(x => { x.visible = false; }); return; }
        o.e = e;
        // The others, around the one chosen: quieter (the Suivi's and a pass's: lights and names only).
        const discret = (mode === 'suivi' || passage) && !lui, efface = !lui && plusieurs;
        if (!discret && (Math.abs(t - o.tOrbite) > (s.piste ? 1e15 : s.periode * .02) || o.tubeLui !== lui)) orbite(o, t, lui);
        const p = eciVers3(e.eci); o.p = p; if (lui) satP = p;
        o.engin.position.copy(p); o.engin.lookAt(0, 0, 0); o.engin.scale.setScalar(auSol ? .15 : lui ? 1.6 : 1); o.engin.visible = !discret;
        o.lueur.position.copy(p); o.lueur.visible = true;
        const vu = e.el > 0, sz = (vu ? .06 : .04) * (sc.style === 'cartoon' ? 1.3 : 1) * (auSol && lui ? 1.8 : 1) * (discret ? .55 : 1) * (lui && plusieurs ? 1.5 : 1);
        o.lueur.scale.set(sz, sz, 1);
        const k = mode === 'suivi' ? (lui ? 1.5 : 1.1) : discret ? .5 : lui ? 1.25 : .85;
        const n = lui && plusieurs ? o.nomFort : o.nom, n2 = n === o.nom ? o.nomFort : o.nom;
        n2.visible = false; n.visible = true; n.position.copy(p.clone().multiplyScalar(1 + .06 / p.length()));
        n.scale.set(.028 * n.userData.ratio * k, .028 * k, 1); n.center.set(-.08, .5);
        n.material.opacity = efface && !discret ? .8 : 1;
        poseCalotte(o.cal.geometry, o.bordCal, e.lat, e.lon, Math.acos(RE / (RE + e.alt)));
        o.cal.visible = o.bordCal.visible = !discret && !auSol;
        o.cal.material.opacity = lui ? (sc.style === 'cartoon' ? .26 : .2) : efface ? .05 : .14;
        o.bordCal.material.opacity = lui ? 1 : efface ? .3 : .8;
        o.orbite.visible = !discret && !lui; o.orbite.material.opacity = efface ? .3 : .7;
        o.tube.visible = !discret && lui;
        o.rayon.visible = vu && !discret && !auSol && lieu;
        if (o.rayon.visible) { const a = o.rayon.geometry.attributes.position; a.setXYZ(0, stW.x, stW.y, stW.z); a.setXYZ(1, p.x, p.y, p.z); a.needsUpdate = true; o.rayon.material.opacity = .55 + .4 * Math.sin(now / 160); }
        if (lui && plusieurs && !auSol) { cible.visible = true; cible.position.copy(p); const q = .075 + .012 * Math.sin(now / 250); cible.scale.set(q, q, 1); cible.material.rotation = now / 1200; cible.material.color.set(STYLES[sc.style].encre || s.couleur); }
      });
      if (mode === 'rejeu') rejeuImage(t, stW, satP, now);
      nomStation.visible = station.visible; nomStation.position.copy(stW.clone().multiplyScalar(1.02)); nomStation.scale.set(.026 * nomStation.userData.ratio, .026, 1); nomStation.center.set(1.08, .5);
      const pulse = (now / 1400) % 1; anneau.scale.setScalar(1 + pulse * 2.5); anneau.material.opacity = 1 - pulse;
      cardinaux.forEach(c => { c.visible = auSol; if (auSol) { c.position.copy(stW.clone().add(dirCiel(stW, c.userData.az, 1).multiplyScalar(.04))); c.scale.set(.03 * c.userData.ratio, .03, 1); } });
      // The cities' and contacts' names: their size, smaller from far away.
      const kv = Math.max(.55, Math.min(1.1, 3.2 / Math.max(1.2, cam.pos.length())));
      mondeGp.children.forEach(s => { if (s.isSprite) { const z = .02 * kv * (s.userData.ville === 1 ? 1.1 : .9); s.scale.set(z * s.userData.ratio, z, 1); } });
      contactsGp.children.forEach(s => { if (s.isSprite) s.scale.set(.017 * kv * s.userData.ratio, .017 * kv, 1); });
      rejeuGp.children.forEach(s => { if (s.isSprite) s.scale.set(.02 * s.userData.ratio, .02, 1); });

      const oc = choisi && sc.objets.get(choisi.catnum);
      const lisse = 1 - Math.pow(.02, dt);
      if (passage && oc && oc.p) {
        const e = oc.e, p = oc.p, up = p.clone().normalize(), H = OB.hauteur;
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
          base = new THREE.Vector3(Math.sin(cam.pol) * Math.cos(cam.az), Math.cos(cam.pol), -Math.sin(cam.pol) * Math.sin(cam.az)).multiplyScalar(cam.dist);
          base.applyAxisAngle(new THREE.Vector3(0, 1, 0), gm); // turning with the Earth
        }
        const k = 1 - Math.pow(.01, dt);
        cam.pos.copy(glisse(cam.pos, base, k)); cam.cible.lerp(new THREE.Vector3(), k);
      }
      camera.position.copy(cam.pos); camera.lookAt(cam.cible);
      rendu.render(scene, camera);
      // Where the satellite chosen shows (the journal's SSTV frame keeps away from it).
      if (satP) { const v = satP.clone().project(camera); sc.satX = (v.x + 1) / 2 * r.width; } else sc.satX = null;
    };
    sc.retire = () => { sc.objets.forEach(o => o.retire()); sc.objets.clear(); if (sc.rj && sc.rj.marques) sc.rj.marques.forEach(o => o.retire && o.retire()); rendu.dispose(); };
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
      vus.forEach(sc => sc.image(sc.mode === 'suivi' ? Date.now() : sc.mode === 'rejeu' ? sc.rj.donnees.horloge() : maintenant(), now));
      if (!tab || tab.v.hidden) return;
      const t = maintenant();
      tab.horloge.textContent = new Date(t).toISOString().slice(0, 19).replace('T', ' ') + ' UTC';
      if (carte && now - dernier2d > 66) { dernier2d = now; dessine2d(t); }
      if (now - derniereListe > 400) { derniereListe = now; lignesSats(t); }
      if (OB.vue === 'passage' && now - dernierInstr > 80) { dernierInstr = now; instruments(t); }
    };
    requestAnimationFrame(f);
  }
  /** The satellite followed changed (Suivi): its orbit and footprint move to it. */
  function changeSuivi(catnum) {
    if (!OB.donnees || OB.donnees.suivi === catnum) return;
    OB.donnees.suivi = catnum; OB.sats.forEach(s => { s.suivi = s.catnum === catnum; });
    scenes.forEach(sc => { if (sc.mode !== 'rejeu') sc.synchro(); });
  }

  /* ------------------------------------------------------------ the tab */
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
    el('ob-contacts').value = OB.contacts;
    el('ob-contacts').onchange = e => prendContacts(e.target.value);
    el('ob-pause').onclick = () => { recale(); OB.temps.pause = !OB.temps.pause; el('ob-pause').textContent = OB.temps.pause ? '▶' : '⏸'; el('ob-pause').title = OB.temps.pause ? 'Reprendre' : 'Pause'; };
    el('ob-vitesse').onchange = e => recale(+e.target.value);
    el('ob-maint').onclick = () => { OB.anim = null; OB.temps.base = Date.now(); OB.temps.reel = performance.now(); boutonJouer(); };
    el('ob-jouer').onclick = () => {
      if (!OB.passage) return;
      if (!OB.anim) prendPassage(OB.passage, true);
      else { if (OB.anim.t >= OB.anim.p.los + 60000) OB.anim.t = OB.anim.p.aos - 90000; OB.anim.joue = !OB.anim.joue; OB.anim.dernier = performance.now(); }
      boutonJouer();
    };
    el('ob-rejouer').onclick = () => { if (OB.anim) { OB.anim.t = OB.anim.p.aos - 20000; OB.anim.joue = true; OB.anim.dernier = performance.now(); boutonJouer(); } };
    el('ob-pvitesse').onchange = e => { if (OB.anim) OB.anim.vitesse = +e.target.value; };
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
  function prendDetail(n) { OB.detail = n; garde('ob-detail', String(n)); scenes.forEach(sc => sc.monde()); }
  function prendContacts(v) { OB.contacts = v; garde('ob-contacts', v); if (v !== 'aucun' && !OB.carnet) { OB.carnetLu = 0; chargeCarnet(); } scenes.forEach(sc => sc.contacts()); }
  function marqueStyle() { document.querySelectorAll('#ob-styles button, .ob-style-choix option').forEach(b => { if (b.tagName === 'OPTION') b.selected = b.value === OB.style; else b.setAttribute('aria-pressed', b.dataset.style === OB.style); }); }
  function marqueCam() { document.querySelectorAll('[data-ob-cam]').forEach(b => b.setAttribute('aria-pressed', b.dataset.obCam === OB.cam)); }
  function boutonJouer() { if (tab) el('ob-jouer').textContent = OB.anim && OB.anim.joue ? '⏸ Pause' : (OB.anim && OB.anim.t >= OB.anim.p.los + 60000 ? '▶ Rejouer' : '▶ Jouer le passage'); }
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
    if (OB.vue === 'globe') tab.scene.suit = s;
    if (OB.vue === 'passage') calculePassages();
  }
  function montreVue(v) {
    OB.vue = v; garde('ob-vue', v);
    tab.v.querySelectorAll('[data-ob-vue]').forEach(b => b.setAttribute('aria-pressed', b.dataset.obVue === v));
    tab.c3d.hidden = v === 'carte'; tab.c2d.hidden = v !== 'carte';
    ['ob-cadreur', 'ob-passages', 'ob-instruments', 'ob-banniere'].forEach(id => { el(id).hidden = v !== 'passage'; });
    el('ob-bulle').hidden = true;
    tab.scene.enPassage = v === 'passage';
    if (v !== 'passage' && OB.anim) { OB.temps.base = OB.anim.t; OB.temps.reel = performance.now(); OB.anim = null; }
    if (v === 'passage') calculePassages();
    if (v === 'globe') tab.scene.suit = null;
    tourne();
  }

  /* The passes of the satellite chosen, the next 36 hours; the best one ready to play. */
  function calculePassages() {
    const s = OB.choisi, box = el('ob-passages');
    OB.anim = null; OB.passage = null; boutonJouer();
    if (!s) { box.innerHTML = '<div class="petit">Choisis un satellite dans la liste.</div>'; return; }
    box.innerHTML = '<div class="t">' + echappe(s.nom) + ' · prochains passages</div>';
    if (!OB.obs) { box.innerHTML += '<div class="petit">La position de la station n’est pas connue : renseigne le locator (Réglages › Station).</div>'; instruments(maintenant()); return; }
    const de = maintenant();
    if (s.mm < 1.2) {
      const e = etat(s, de);
      box.innerHTML += '<div class="petit">Géostationnaire : toujours au même endroit du ciel' + (e ? ', az ' + e.az.toFixed(1) + '° el ' + e.el.toFixed(1) + '°' : '') + '. Pas de passage à animer.</div>';
      OB.passages = []; instruments(de); return;
    }
    OB.passages = obPassages(t => { const e = etat(s, t); return e ? e.el : null; }, de, de + 36 * 3600000, 30000, 10).map(p => {
      const piste = [];
      for (let t = p.aos; t <= p.los + 1; t += Math.max(2000, (p.los - p.aos) / 300)) { const e = etat(s, t); if (e) piste.push({ t: t, az: e.az, el: e.el }); }
      const a = etat(s, p.aos), b = etat(s, p.los);
      return Object.assign(p, { azAos: a ? a.az : 0, azLos: b ? b.az : 0, piste: piste });
    });
    if (!OB.passages.length) box.innerHTML += '<div class="petit">Aucun passage au-dessus de l’horizon dans les 36 h.</div>';
    OB.passages.forEach((p, i) => {
      const b = document.createElement('button'); b.type = 'button'; b.className = 'ob-pas'; b.setAttribute('aria-pressed', 'false'); b.dataset.i = i;
      b.innerHTML = '<b>' + jour(p.aos) + ' ' + hh(p.aos) + '</b><span class="ob-max">' + p.max.toFixed(0) + '°</span><span class="ob-sous">' + Math.round((p.los - p.aos) / 60000) + ' min · ' + cardinal(p.azAos) + ' → ' + cardinal(p.azLos) + '</span>';
      b.onclick = () => prendPassage(p, true);
      box.appendChild(b);
    });
    const meilleur = OB.passages.find(p => p.max > 25) || OB.passages[0];
    if (meilleur) prendPassage(meilleur, false);
  }
  function prendPassage(p, jouer) {
    OB.passage = p; tab.scene.azAos = p.azAos;
    el('ob-passages').querySelectorAll('.ob-pas').forEach(b => b.setAttribute('aria-pressed', OB.passages[+b.dataset.i] === p));
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
        x.strokeStyle = coul; x.lineWidth = 2; x.beginPath();
        p.piste.forEach((q, i) => { const c = xy(q.az, q.el); if (i) x.lineTo(c[0], c[1]); else x.moveTo(c[0], c[1]); }); x.stroke();
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
        if (OB.anim) { const tx = X(OB.anim.t); x.strokeStyle = '#fff'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(tx, 6); x.lineTo(tx, h - 18); x.stroke(); }
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
    else if (t < p.aos) { const n = Math.ceil((p.aos - t) / 1000); texte = n <= 10 ? String(n) : 'AOS dans ' + Math.floor(n / 60) + ':' + String(n % 60).padStart(2, '0'); sous = s.nom + ' se lève au ' + cardinal(p.azAos) + ' (az ' + p.azAos.toFixed(0) + '°)'; flash = n <= 10; }
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
    x.beginPath(); obNuit(so.dec, so.lon).forEach((q, i) => { if (i) x.lineTo(X(q[1]), Y(q[0])); else x.moveTo(X(q[1]), Y(q[0])); }); x.closePath();
    x.fillStyle = S.nuit; x.fill();
    x.fillStyle = '#FFD34D'; x.shadowColor = '#FFB300'; x.shadowBlur = 14; x.beginPath(); x.arc(X(so.lon), Y(so.dec), 6, 0, 7); x.fill(); x.shadowBlur = 0;
    // The cities.
    x.font = (vieux ? 'italic ' : '') + '500 10px system-ui, sans-serif'; x.textAlign = 'left'; x.textBaseline = 'middle';
    villesVues().forEach(v => { const X0 = X(v[2]), Y0 = Y(v[1]);
      x.fillStyle = S.ville; x.beginPath(); x.arc(X0, Y0, v[3] === 1 ? 2.6 : 2, 0, 7); x.fill();
      x.lineWidth = 3; x.strokeStyle = vieux ? 'rgba(239,224,184,.9)' : 'rgba(0,0,0,.7)'; x.strokeText(v[0], X0 + 4, Y0); x.fillText(v[0], X0 + 4, Y0); });
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
      x.lineWidth = lui ? 3 : 1.3; x.strokeStyle = c;
      [false, true].forEach(futur => {
        x.globalAlpha = (futur ? .85 : .35) * (efface ? .5 : 1); x.setLineDash(futur ? [5, 4] : []); x.beginPath();
        let prec = null;
        o.trace.forEach(q => { if ((q[0] > t) !== futur) return; if (prec != null && Math.abs(q[2] - prec) < 180) x.lineTo(X(q[2]), Y(q[1])); else x.moveTo(X(q[2]), Y(q[1])); prec = q[2]; });
        x.stroke();
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
  }

  return {
    /** The Orbites tab shown. */
    montre: async function () {
      const v = el('v-orbites'), att = el('ob-attente');
      att.hidden = false; att.textContent = 'Chargement du globe…';
      try { await charge(); } catch (x) { att.textContent = 'Le globe n’a pas pu se charger : ' + (x && x.message ? x.message : 'erreur') + '. Recharge la page.'; return; }
      if (v.hidden) return;
      prepareTab(); att.hidden = true;
      listeSats(); montreVue(OB.vue);
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
     * marques: [{k, de, a, t, lat?, lon?, f?}], image: m => the picture's address, horloge: () => the replay's time}.
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
        rejeu.rj = { donnees: d, station: d.qth ? { lat: d.qth[0], lon: d.qth[1], indicatif: d.indicatif, locator: d.locator } : null,
          sat: { nom: d.sat, catnum: -1, piste: sol, couleur: '#FF8A1F', periode: 5400000, mm: 15 } };
        rejeu.synchro(); rejeu.rejeuPrepare();
      }
      rejeu.rj.donnees = d;
      tourne(); return true;
    },
    /** The journal's globe: where its satellite shows (px from the canvas's left), null when unknown. */
    satEcranX: () => rejeu ? rejeu.satX : null,
    style: k => prendStyle(k),
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
