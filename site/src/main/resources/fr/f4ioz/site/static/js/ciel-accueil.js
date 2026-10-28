/*
 * F4IOZ site — « Au-dessus de JN18FS », the home page's window on space.
 *
 * The Earth seen from space, centred on the station: the land lit by the Sun
 * (the night and its twilight worked out pixel by pixel), the coastline, the
 * home page's satellites where they really are now (satellite.js, the
 * site's elements), the footprint of each one the station can hear, their
 * tracks for the coming half hour, the other amateur satellites as faint
 * lights. Beside it the sky dial (drawn by the server, kept alive here), the
 * pass under way or the next AOS with its countdown, the Sun and the Moon.
 *
 * SatMe's own pure functions come first in this file (OrbitesJs.PUR: obSoleil,
 * obGmst, obLune, obLunePath, obEmpreinte, obSoleilJour). Drag, or the arrow
 * keys, to turn the Earth; Enter (or a double click) brings the station back.
 * Nothing moves by itself with « reduced motion » beyond the satellites'
 * real positions, refreshed every few seconds. Drawn only while seen.
 */
(function () {
  'use strict';
  const carte = document.querySelector('[data-ciel]');
  const brut = document.getElementById('ciel-donnees');
  if (!carte || !brut || typeof satellite === 'undefined') return;
  let D = JSON.parse(brut.textContent);
  const D2R = Math.PI / 180, R2D = 180 / Math.PI, RE = 6378.137;
  const reduit = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };
  const p2 = n => (n < 10 ? '0' : '') + n;
  const esc = x => String(x).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
  const obs = { latitude: D.obs.lat * D2R, longitude: D.obs.lon * D2R, height: 0 };

  /* ------------------------------------------------------------ the satellites */
  let favoris = [], autres = [];
  function prendSats(l, fav) {
    return l.map(s => { let rec = null; try { rec = satellite.twoline2satrec(s.l1, s.l2); } catch (x) {} return rec ? { nom: s.nom, catnum: s.catnum, rec: rec, fav: fav } : null; })
      .filter(s => s && (parseFloat(String(s.rec.no)) > 0));
  }
  favoris = prendSats(D.sats || [], true);
  /** Where [s] is at [t]: over the Earth (lat, lon, alt km) and in the station's sky (az, el, km). */
  function etat(s, t) {
    const d = new Date(t), r = satellite.propagate(s.rec, d);
    if (!r || !r.position || typeof r.position !== 'object') return null;
    const gm = satellite.gstime(d), gd = satellite.eciToGeodetic(r.position, gm);
    const la = satellite.ecfToLookAngles(obs, satellite.eciToEcf(r.position, gm));
    return { lat: gd.latitude * R2D, lon: gd.longitude * R2D, alt: gd.height, az: (la.azimuth * R2D + 360) % 360, el: la.elevation * R2D, km: la.rangeSat };
  }
  /** An object at [dec], [lonSous] (degrees) seen from the station: {az, el}. */
  function azEl(dec, lonSous) {
    const f = D.obs.lat * D2R, dc = dec * D2R, H = (D.obs.lon - lonSous) * D2R;
    const el = Math.asin(Math.sin(f) * Math.sin(dc) + Math.cos(f) * Math.cos(dc) * Math.cos(H));
    const az = Math.atan2(-Math.cos(dc) * Math.sin(H), Math.sin(dc) * Math.cos(f) - Math.cos(dc) * Math.sin(f) * Math.cos(H));
    return { az: (az * R2D + 360) % 360, el: el * R2D };
  }

  /* ------------------------------------------------------------ the globe */
  const zone = carte.querySelector('.ciel-globe'), cv = carte.querySelector('[data-ciel-globe]'), attente = carte.querySelector('[data-ciel-attente]');
  const ctx = cv.getContext('2d');
  const vue = { lat: Math.max(-60, Math.min(60, D.obs.lat - 8)), lon: D.obs.lon, cible: null };
  let W = 0, H = 0, dpr = 1, R = 0, cx = 0, cy = 0;
  let base = null, baseCle = '', masque = null, anneaux = null, couleurs = null;
  let positions = [], positionsT = 0, traces = new Map(), tracesT = 0;

  function lisCouleurs() {
    const cs = getComputedStyle(carte), v = k => cs.getPropertyValue(k).trim();
    const rgb = h => { const m = /^#?([0-9a-f]{6})$/i.exec(h); if (!m) return [0, 0, 0]; const n = parseInt(m[1], 16); return [n >> 16, (n >> 8) & 255, n & 255]; };
    couleurs = { ocean: rgb(v('--g-ocean')), ocean2: rgb(v('--g-ocean-2')), terre: rgb(v('--g-terre')), terre2: rgb(v('--g-terre-2')), nuit: rgb(v('--g-nuit')),
      nuitForce: parseFloat(v('--g-nuit-force')) || .8, cote: v('--g-cote'), grille: v('--g-grille'), atmo: v('--g-atmo'), accent: v('--accent'), ambre: v('--amber'),
      texte: v('--text'), dim: v('--text-dim'), autres: v('--g-autres'), empreinte: v('--g-empreinte'), halo: v('--g-halo') };
    baseCle = '';
  }
  /** The view's axes: centre c, east e, north n (unit vectors in the Earth's frame). */
  function axes() {
    const f = vue.lat * D2R, l = vue.lon * D2R;
    return { c: [Math.cos(f) * Math.cos(l), Math.cos(f) * Math.sin(l), Math.sin(f)], e: [-Math.sin(l), Math.cos(l), 0], n: [-Math.sin(f) * Math.cos(l), -Math.sin(f) * Math.sin(l), Math.cos(f)] };
  }
  const vec = (lat, lon) => { const f = lat * D2R, l = lon * D2R; return [Math.cos(f) * Math.cos(l), Math.cos(f) * Math.sin(l), Math.sin(f)]; };
  const pt = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  /** [lat, lon] at [k] Earth radii from its centre → [x, y, devant] on the canvas. */
  function proj(A, lat, lon, k) {
    const p = vec(lat, lon); k = k || 1;
    const x = pt(p, A.e) * k, y = pt(p, A.n) * k, z = pt(p, A.c) * k;
    return [cx + x * R, cy - y * R, z > 0 || x * x + y * y > 1];
  }
  /** The land as a mask (equirectangular, 1024 × 512), from the coastline's rings. */
  function faisMasque(l) {
    const c = document.createElement('canvas'); c.width = 1024; c.height = 512;
    const x = c.getContext('2d'); x.fillStyle = '#fff';
    l.forEach(a => { x.beginPath(); for (let i = 0; i + 1 < a.length; i += 2) { const X = (a[i] + 180) / 360 * 1024, Y = (90 - a[i + 1]) / 180 * 512; if (i) x.lineTo(X, Y); else x.moveTo(X, Y); } x.closePath(); x.fill('evenodd'); });
    const d = x.getImageData(0, 0, 1024, 512).data, m = new Uint8Array(1024 * 512);
    for (let i = 0; i < m.length; i++) m[i] = d[i * 4 + 3] > 100 ? 1 : 0;
    return m;
  }
  /**
   * The Earth's disc, lit: ocean or land at each pixel, darker towards the rim, the night side dark with a soft twilight
   * (the Sun's direction from SatMe's obSoleil), then the graticule and the coastline. Made again when the view, the size
   * or the theme changes, and every minute (the night moves).
   */
  function faisBase(t, rapide) {
    const N = Math.max(64, Math.round(2 * R * dpr * (rapide ? .5 : 1)));
    const c = document.createElement('canvas'); c.width = c.height = N;
    const x = c.getContext('2d'), im = x.createImageData(N, N), px = im.data;
    const A = axes(), so = obSoleil(t, obGmst(t)), S = vec(so.dec, so.lon), C = couleurs;
    const h = N / 2;
    for (let j = 0; j < N; j++) {
      const v = (h - j - .5) / h;
      for (let i = 0; i < N; i++) {
        const u = (i + .5 - h) / h, r2 = u * u + v * v;
        if (r2 > 1) continue;
        const z = Math.sqrt(1 - r2);
        const P0 = u * A.e[0] + v * A.n[0] + z * A.c[0], P1 = u * A.e[1] + v * A.n[1] + z * A.c[1], P2 = u * A.e[2] + v * A.n[2] + z * A.c[2];
        const lat = Math.asin(Math.max(-1, Math.min(1, P2))), lon = Math.atan2(P1, P0);
        let mi = Math.floor((lon * R2D + 180) / 360 * 1024), mj = Math.floor((90 - lat * R2D) / 180 * 512);
        if (mi >= 1024) mi = 1023; if (mi < 0) mi = 0; if (mj >= 512) mj = 511; if (mj < 0) mj = 0;
        const terre = masque ? masque[mj * 1024 + mi] : 0;
        const a = terre ? C.terre : C.ocean, b = terre ? C.terre2 : C.ocean2;
        // Lit side: brighter where the Sun is high; the rim darker; night: towards the night colour, a soft twilight.
        const s = P0 * S[0] + P1 * S[1] + P2 * S[2];
        const jour = Math.max(0, Math.min(1, (s + .1) / .22));
        const mix = Math.max(0, Math.min(1, .25 + .75 * Math.max(0, s))) * jour;
        const bord = .72 + .28 * z;
        let R0 = (a[0] + (b[0] - a[0]) * mix) * bord, G0 = (a[1] + (b[1] - a[1]) * mix) * bord, B0 = (a[2] + (b[2] - a[2]) * mix) * bord;
        const n = (1 - jour) * C.nuitForce;
        R0 += (C.nuit[0] - R0) * n; G0 += (C.nuit[1] - G0) * n; B0 += (C.nuit[2] - B0) * n;
        const k = (j * N + i) * 4;
        px[k] = R0; px[k + 1] = G0; px[k + 2] = B0;
        // Antialiased rim.
        px[k + 3] = r2 > (1 - 2 / N) ? Math.max(0, Math.min(255, (1 - r2) * N / 2 * 255)) : 255;
      }
    }
    x.putImageData(im, 0, 0);
    // The graticule (every 30°) and the coastline, on the near side only.
    const s = N / (2 * R);
    const P = (lat, lon) => { const p = vec(lat, lon), X = pt(p, A.e), Y = pt(p, A.n), Z = pt(p, A.c); return [h + X * h, h - Y * h, Z > 0]; };
    const ligne = (pts, couleur, larg) => {
      x.beginPath(); let en = false;
      pts.forEach(q => { const p = P(q[0], q[1]); if (!p[2]) { en = false; return; } if (en) x.lineTo(p[0], p[1]); else { x.moveTo(p[0], p[1]); en = true; } });
      x.strokeStyle = couleur; x.lineWidth = larg * s; x.stroke();
    };
    for (let la = -60; la <= 60; la += 30) { const l = []; for (let lo = -180; lo <= 180; lo += 3) l.push([la, lo]); ligne(l, C.grille, la === 0 ? 1 : .7); }
    for (let lo = -180; lo < 180; lo += 30) { const l = []; for (let la = -90; la <= 90; la += 3) l.push([la, lo]); ligne(l, C.grille, .7); }
    if (anneaux) {
      x.beginPath();
      anneaux.forEach(a => { let en = false; for (let i = 0; i + 1 < a.length; i += 2) { const p = P(a[i + 1], a[i]); if (!p[2]) { en = false; continue; } if (en) x.lineTo(p[0], p[1]); else { x.moveTo(p[0], p[1]); en = true; } } });
      x.strokeStyle = C.cote; x.lineWidth = .8 * s; x.lineJoin = 'round'; x.stroke();
    }
    return c;
  }
  function taille() {
    const r = zone.getBoundingClientRect();
    dpr = Math.min(2, window.devicePixelRatio || 1);
    W = Math.max(10, Math.round(r.width)); H = Math.max(10, Math.round(r.height));
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    R = Math.max(20, Math.min(W, H) * .42); cx = W / 2; cy = H / 2 + Math.min(W, H) * .01;
    baseCle = '';
  }
  /** Each satellite's place now (once a second is enough: a pixel moves in seconds) and its track for the coming half hour (every 30 s). */
  function positionsA(t) {
    if (t - positionsT < (anime() ? 1000 : 4000) && positions.length) return;
    positionsT = t;
    positions = favoris.concat(autres).map(s => { const e = etat(s, t); return e ? { s: s, e: e } : null; }).filter(Boolean);
    if (t - tracesT > 30000) {
      tracesT = t; traces = new Map();
      favoris.forEach(s => { const l = []; for (let k = -6; k <= 40; k++) { const e = etat(s, t + k * 45000); if (e) l.push([e.lat, e.lon, e.alt, k]); } traces.set(s.catnum, l); });
    }
  }
  const prochainEnCours = t => (D.passages || []).filter(p => p.aos <= t && p.los > t).sort((a, b) => b.max - a.max)[0] || null;
  const prochain = t => (D.passages || []).filter(p => p.aos > t)[0] || null;
  let pulse = 0;
  function dessineGlobe(t, rapide) {
    if (!W) return;
    const A = axes();
    const cle = W + 'x' + H + ':' + vue.lat.toFixed(2) + ',' + vue.lon.toFixed(2) + ':' + Math.floor(t / 60000) + ':' + !!masque + (rapide ? 'r' : '');
    if (cle !== baseCle) { base = faisBase(t, rapide); baseCle = cle; }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    poses = [];
    // The atmosphere.
    const g = ctx.createRadialGradient(cx, cy, R * .96, cx, cy, R * 1.16);
    g.addColorStop(0, couleurs.atmo); g.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, R * 1.16, 0, 7); ctx.fill();
    ctx.imageSmoothingEnabled = true; ctx.drawImage(base, cx - R, cy - R, 2 * R, 2 * R);
    positionsA(t);
    const en = prochainEnCours(t), pr = prochain(t);
    const vedette = en ? en.catnum : pr ? pr.catnum : null;
    // The other amateur satellites: faint lights.
    ctx.fillStyle = couleurs.autres;
    positions.forEach(o => { if (o.s.fav) return; const p = proj(A, o.e.lat, o.e.lon, (RE + o.e.alt) / RE); if (!p[2] || o.e.alt > 40000) return; ctx.beginPath(); ctx.arc(p[0], p[1], 1.3, 0, 7); ctx.fill(); });
    // The favourites' footprints: filled when the station hears the satellite, a dotted outline for the next one.
    positions.forEach(o => {
      if (!o.s.fav || o.e.alt > 40000) return;
      const vu = o.e.el > 0, lui = o.s.catnum === vedette;
      if (!vu && !lui) return;
      const lam = Math.acos(RE / (RE + o.e.alt)), pts = obEmpreinte(o.e.lat, o.e.lon, lam, 96);
      ctx.beginPath(); let enT = false;
      pts.forEach(q => { const p = proj(A, q[0], q[1], 1); if (!p[2]) { enT = false; return; } if (enT) ctx.lineTo(p[0], p[1]); else { ctx.moveTo(p[0], p[1]); enT = true; } });
      if (vu) { ctx.fillStyle = couleurs.empreinte; ctx.fill(); }
      ctx.setLineDash(vu ? [] : [3, 4]); ctx.strokeStyle = vu ? couleurs.accent : couleurs.ambre; ctx.globalAlpha = vu ? .9 : .7; ctx.lineWidth = 1.2; ctx.stroke();
      ctx.setLineDash([]); ctx.globalAlpha = 1;
    });
    // Their tracks: the coming half hour, fading; a little of the past, fainter.
    favoris.forEach(s => {
      const l = traces.get(s.catnum); if (!l) return;
      const lui = s.catnum === vedette;
      for (let i = 1; i < l.length; i++) {
        const a = proj(A, l[i - 1][0], l[i - 1][1], (RE + l[i - 1][2]) / RE), b = proj(A, l[i][0], l[i][1], (RE + l[i][2]) / RE);
        if (!a[2] || !b[2] || Math.abs(l[i][1] - l[i - 1][1]) > 90) continue;
        const k = l[i][3];
        ctx.globalAlpha = k <= 0 ? .18 : Math.max(.08, (lui ? .85 : .5) * (1 - k / 42));
        ctx.strokeStyle = lui ? couleurs.ambre : couleurs.dim; ctx.lineWidth = lui ? 1.6 : 1;
        ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
      }
      ctx.globalAlpha = 1;
    });
    // The station: a dot and a ring breathing round it.
    const st = proj(A, D.obs.lat, D.obs.lon, 1);
    if (st[2]) {
      const k = reduit.matches ? .5 : (pulse % 2400) / 2400;
      ctx.strokeStyle = couleurs.ambre; ctx.lineWidth = 1.4;
      ctx.globalAlpha = .9 * (1 - k); ctx.beginPath(); ctx.arc(st[0], st[1], 4 + 14 * k, 0, 7); ctx.stroke(); ctx.globalAlpha = 1;
      ctx.fillStyle = couleurs.ambre; ctx.beginPath(); ctx.arc(st[0], st[1], 3.4, 0, 7); ctx.fill();
      etiquette(D.locator, st[0] + 8, st[1] + 4, couleurs.ambre, true);
    }
    // The favourites themselves, those the station hears in front.
    positions.filter(o => o.s.fav).sort((a, b) => a.e.el - b.e.el).forEach(o => {
      const p = proj(A, o.e.lat, o.e.lon, (RE + Math.min(o.e.alt, 3000)) / RE); if (!p[2]) return;
      const vu = o.e.el > 0, lui = o.s.catnum === vedette, c = vu ? couleurs.accent : lui ? couleurs.ambre : couleurs.texte;
      if (vu || lui) { const h = ctx.createRadialGradient(p[0], p[1], 0, p[0], p[1], 11); h.addColorStop(0, c); h.addColorStop(1, 'rgba(0,0,0,0)'); ctx.globalAlpha = .45; ctx.fillStyle = h; ctx.beginPath(); ctx.arc(p[0], p[1], 11, 0, 7); ctx.fill(); ctx.globalAlpha = 1; }
      ctx.fillStyle = c; ctx.beginPath(); ctx.arc(p[0], p[1], vu || lui ? 3.4 : 2.4, 0, 7); ctx.fill();
      if (vu || lui || W > 420) etiquette(o.s.nom, p[0] + 7, p[1] - 6, c, vu || lui);
    });
  }
  // The labels drawn in this image: a new one that would cover another goes below its point instead, or is left out.
  let poses = [];
  function etiquette(t, x, y, c, gras) {
    const w = t.length * 7, libre = (X, Y) => poses.every(b => X + w < b[0] || X > b[0] + b[2] || Y < b[1] - 12 || Y - 12 > b[1]);
    if (!libre(x, y)) { if (libre(x, y + 18)) y += 18; else if (!gras) return; }
    poses.push([x, y, w]);
    ctx.font = (gras ? '700 ' : '600 ') + (W < 360 ? 10.5 : 11.5) + 'px ' + getComputedStyle(document.body).fontFamily;
    ctx.lineWidth = 3; ctx.strokeStyle = couleurs.halo; ctx.lineJoin = 'round'; ctx.strokeText(t, x, y);
    ctx.fillStyle = c; ctx.fillText(t, x, y);
  }

  /* ------------------------------------------------------------ the sky dial and the countdown */
  const cadran = carte.querySelector('[data-ciel-cadran]'), aos = carte.querySelector('[data-ciel-aos]'), nb = carte.querySelector('[data-ciel-nb]');
  const astres = carte.querySelector('[data-ciel-astres]');
  const SVG = 'http://www.w3.org/2000/svg';
  let dyn = null;
  const pol = (az, el) => { const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90), a = az * D2R; return [r * Math.sin(a), -r * Math.cos(a)]; };
  const f1 = n => n.toFixed(1);
  function compte(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    if (s >= 3600) return Math.floor(s / 3600) + ' h ' + p2(Math.floor(s % 3600 / 60));
    return p2(Math.floor(s / 60)) + ':' + p2(s % 60);
  }
  const caps = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSO', 'SO', 'OSO', 'O', 'ONO', 'NO', 'NNO'];
  const cap = az => caps[Math.floor(((az % 360) + 371.25) / 22.5) % 16];
  const hm = t => { const d = new Date(t); return p2(d.getHours()) + ':' + p2(d.getMinutes()); };
  let dernierAos = '';
  function dessineCiel(t) {
    if (!cadran) return;
    if (!dyn) {
      cadran.querySelectorAll('.cc-sat,.cc-trace,.cc-aos,.cc-soleil').forEach(n => n.remove());
      dyn = document.createElementNS(SVG, 'g'); cadran.appendChild(dyn);
    }
    const en = prochainEnCours(t), pr = prochain(t), p = en || pr;
    let h = '';
    if (p && p.trace && p.trace.length > 1) {
      h += '<polyline class="cc-trace' + (en ? ' encours' : '') + '" points="' + p.trace.map(q => pol(q[0], q[1]).map(f1).join(',')).join(' ') + '"/>';
      const a = pol(p.trace[0][0], p.trace[0][1]); h += '<circle class="cc-aos" cx="' + f1(a[0]) + '" cy="' + f1(a[1]) + '" r="2.6"/>';
    }
    // The Sun and the Moon (SatMe's obSoleil, obLune, obLunePath).
    const so = obSoleil(t, obGmst(t)), sa = azEl(so.dec, so.lon), lu = obLune(t), la = azEl(lu.dec, lu.lon);
    if (sa.el > 0) { const q = pol(sa.az, sa.el); h += '<g class="cc-soleil" transform="translate(' + f1(q[0]) + ' ' + f1(q[1]) + ')"><circle r="5"/><title>Soleil · ' + Math.round(sa.el) + '°</title></g>'; }
    if (la.el > 0) { const q = pol(la.az, la.el); h += '<g class="cc-lune" transform="translate(' + f1(q[0]) + ' ' + f1(q[1]) + ')"><circle r="4.6"/><path d="' + obLunePath(lu.phase, 4.6) + '"/><title>Lune · ' + Math.round(la.el) + '°</title></g>'; }
    // The satellites up now.
    let n = 0;
    favoris.map(s => ({ s: s, e: etat(s, t) })).filter(o => o.e && o.e.el > 0).sort((a, b) => a.e.el - b.e.el).forEach(o => {
      n++;
      const q = pol(o.e.az, o.e.el), lui = p && o.s.catnum === p.catnum;
      h += '<g class="cc-sat' + (lui ? ' lui' : '') + '" transform="translate(' + f1(q[0]) + ' ' + f1(q[1]) + ')"><circle r="' + (lui ? 4.4 : 3.6) + '"/><text x="6" y="-5">' + esc(o.s.nom) + '</text>' +
        '<title>' + esc(o.s.nom) + ' · az ' + Math.round(o.e.az) + '° · él ' + Math.round(o.e.el) + '° · ' + Math.round(o.e.km).toLocaleString('fr-FR') + ' km</title></g>';
    });
    dyn.innerHTML = h;
    if (nb) nb.textContent = n === 0 ? 'aucun satellite en vue' : n + (n > 1 ? ' satellites en vue' : ' satellite en vue');
    // The countdown.
    if (aos) {
      let html;
      if (!p) html = '<span class="ciel-aos-l">Aucun passage au-dessus de 5° dans les 24 heures</span>';
      else {
        const e = en ? favoris.find(s => s.catnum === en.catnum) : null, ee = e ? etat(e, t) : null;
        html = '<span class="ciel-aos-l">' + (en ? '<span class="bd-led"></span>Passage en cours' : 'Prochain passage') + '</span><b class="ciel-aos-nom">' + esc(p.nom) + '</b>' +
          '<span class="ciel-aos-compte">' + (en ? '<small>LOS dans</small>' + compte(en.los - t) : '<small>dans</small>' + compte(p.aos - t)) + '</span>' +
          '<span class="ciel-aos-det">' + (ee ? 'él ' + Math.round(ee.el) + '° · az ' + Math.round(ee.az) + '° · ' : hm(p.aos) + '–' + hm(p.los) + ' · ') + 'max ' + Math.round(p.max) + '° · ' + cap(p.azAos) + ' → ' + cap(p.azLos) + '</span>';
      }
      if (html !== dernierAos) { aos.innerHTML = html; dernierAos = html; }
    }
    if (astres) {
      const ls = astres.querySelector('.soleil b'), ll = astres.querySelector('.lune'), j = obSoleilJour(t, D.obs.lat, D.obs.lon);
      if (ls) ls.textContent = sa.el > 0 ? Math.round(sa.el) + '°' + (j && j.coucher ? ' · coucher ' + hm(j.coucher) : '') : 'sous l’horizon' + (j && j.lever ? ' · lever ' + hm(j.lever) : '');
      if (ll) { ll.hidden = false; ll.querySelector('b').textContent = Math.round(lu.lumiere * 100) + ' % · ' + (la.el > 0 ? Math.round(la.el) + '°' : 'couchée'); }
    }
  }

  /* ------------------------------------------------------------ life: when seen, the intro, the hands */
  let vu = true, boucle = false, intro = null, geste = 0, rapide = false;
  const anime = () => !reduit.matches;
  function image(now) {
    const t = Date.now();
    if (intro) {
      const k = Math.min(1, (now - intro.t0) / intro.duree), e = 1 - Math.pow(1 - k, 3);
      vue.lon = intro.lon0 + (intro.lon1 - intro.lon0) * e; vue.lat = intro.lat0 + (intro.lat1 - intro.lat0) * e;
      rapide = k < 1; if (k >= 1) intro = null;
    }
    // A minute after a hand let go, the Earth turns back to the station by itself.
    if (!intro && geste && now - geste > 60000) { geste = 0; retour(); }
    pulse = now;
    dessineGlobe(t, rapide);
  }
  let derniere = 0;
  function tic() {
    if (!vu || document.hidden) { boucle = false; return; }
    const now = performance.now();
    // About 30 images a second during a gesture or the intro, 15 otherwise: the ring breathes, the rest moves slowly.
    if (now - derniere >= (intro || tire ? 30 : 64)) { derniere = now; image(now); }
    if (anime()) requestAnimationFrame(tic); else boucle = false;
  }
  function relance() { if (!boucle && vu && !document.hidden) { boucle = true; requestAnimationFrame(tic); } }
  function retour() {
    const lat1 = Math.max(-60, Math.min(60, D.obs.lat - 8));
    let dl = ((D.obs.lon - vue.lon) % 360 + 540) % 360 - 180;
    if (!anime()) { vue.lat = lat1; vue.lon = D.obs.lon; image(performance.now()); return; }
    intro = { t0: performance.now(), duree: 1100, lon0: vue.lon, lon1: vue.lon + dl, lat0: vue.lat, lat1: lat1 }; relance();
  }
  // Hands: drag to turn; double click or Enter back to the station; arrows turn.
  let tire = null;
  cv.addEventListener('pointerdown', e => { tire = { x: e.clientX, y: e.clientY, lat: vue.lat, lon: vue.lon }; intro = null; cv.setPointerCapture(e.pointerId); cv.classList.add('tire'); });
  cv.addEventListener('pointermove', e => {
    if (!tire) return;
    vue.lon = tire.lon - (e.clientX - tire.x) / R * R2D; vue.lat = Math.max(-80, Math.min(80, tire.lat + (e.clientY - tire.y) / R * R2D));
    geste = performance.now(); rapide = true; if (!anime()) image(performance.now()); else relance();
  });
  const lache = () => { if (!tire) return; tire = null; rapide = false; cv.classList.remove('tire'); geste = performance.now(); image(performance.now()); };
  cv.addEventListener('pointerup', lache); cv.addEventListener('pointercancel', lache);
  cv.addEventListener('dblclick', retour);
  cv.addEventListener('keydown', e => {
    const d = { ArrowLeft: [0, -10], ArrowRight: [0, 10], ArrowUp: [10, 0], ArrowDown: [-10, 0] }[e.key];
    if (d) { e.preventDefault(); vue.lat = Math.max(-80, Math.min(80, vue.lat + d[0])); vue.lon += d[1]; geste = performance.now(); image(performance.now()); }
    else if (e.key === 'Enter' || e.key === 'Home') { e.preventDefault(); retour(); }
  });

  // The sky dial and the countdown: every second, while the card is seen.
  function seconde() { if (vu && !document.hidden) { const t = Date.now(); dessineCiel(t); if (!anime()) image(performance.now()); } }
  if ('IntersectionObserver' in window) new IntersectionObserver(l => { vu = l[0].isIntersecting; if (vu) { seconde(); relance(); } }, { threshold: .05 }).observe(carte);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { seconde(); relance(); } });
  new MutationObserver(() => { lisCouleurs(); image(performance.now()); }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  if ('ResizeObserver' in window) new ResizeObserver(() => { taille(); image(performance.now()); }).observe(zone);
  if (reduit.addEventListener) reduit.addEventListener('change', relance);

  // The passes again every ten minutes (the server's, from SatMe's engine).
  async function relit() {
    try { const r = await fetch('/api/ciel', { headers: { Accept: 'application/json' } }); if (r.ok) { const n = await r.json(); D = n; } } catch (x) {}
  }
  setInterval(() => { if (!document.hidden) relit(); }, 600000);
  setInterval(seconde, 1000);

  // The coastline (light), then the other satellites, when the browser is idle.
  const plusTard = f => (window.requestIdleCallback ? requestIdleCallback(f, { timeout: 2500 }) : setTimeout(f, 400));
  async function chargeTerre() {
    try {
      anneaux = await (await fetch('/api/carte/traits-legers')).json();
      masque = faisMasque(anneaux); baseCle = '';
      zone.classList.add('pret'); if (attente) attente.hidden = true;
      image(performance.now());
    } catch (x) { if (attente) attente.textContent = 'La carte n’a pas pu se charger.'; }
    plusTard(async () => {
      try {
        const o = await (await fetch('/api/orbites')).json(), deja = new Set(favoris.map(s => s.catnum));
        autres = prendSats((o.sats || []).filter(s => !deja.has(s.catnum)), false);
        positionsT = 0; image(performance.now());
      } catch (x) {}
    });
  }

  lisCouleurs(); taille();
  dessineCiel(Date.now());
  if (anime()) intro = { t0: performance.now(), duree: 2200, lon0: vue.lon - 75, lon1: vue.lon, lat0: vue.lat - 18, lat1: vue.lat };
  image(performance.now()); relance();
  plusTard(chargeTerre);
})();
