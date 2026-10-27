/*
 * F4IOZ site — what the HF pages share: the page's data, escaping (every
 * callsign, locator or name comes from a third party: never put into HTML
 * unescaped), locators, great circles, the bands' colours, the maps
 * (Leaflet on the OpenStreetMap tiles the site fetches and keeps), the sun.
 */
(function () {
  'use strict';
  const D = (() => { try { return JSON.parse(document.getElementById('hf-donnees').textContent); } catch (e) { return {}; } })();
  const $ = id => document.getElementById(id);

  /** Text for HTML: & < > " ' escaped. */
  function esc(v) {
    return String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  const COULEURS = {
    '2190m': '#475569', '630m': '#64748b', '160m': '#7c83ff', '80m': '#a78bfa', '60m': '#c084fc', '40m': '#f472b6', '30m': '#fb7185',
    '20m': '#4dffa7', '17m': '#22d3ee', '15m': '#fbbf24', '12m': '#fb923c', '10m': '#ef4444', '6m': '#f87171',
    '4m': '#94a3b8', '2m': '#94a3b8', '70cm': '#94a3b8',
  };
  const couleur = b => COULEURS[b] || '#9ca3af';
  const ORDRE = ['160m', '80m', '60m', '40m', '30m', '20m', '17m', '15m', '12m', '10m', '6m'];

  const LOC = /^[A-R]{2}[0-9]{2}([A-X]{2})?$/;
  /** A locator's centre [lat, lon] (4/6 characters, 8/10 read to 6), null when not one. */
  function loc(l) {
    let u = String(l || '').trim().toUpperCase();
    if (u.length > 6 && /^[A-R]{2}[0-9]{2}[A-X]{2}[0-9]{2}/.test(u)) u = u.substring(0, 6);
    if (!LOC.test(u)) return null;
    let lon = (u.charCodeAt(0) - 65) * 20 - 180, lat = (u.charCodeAt(1) - 65) * 10 - 90;
    lon += +u[2] * 2; lat += +u[3];
    if (u.length === 6) { lon += (u.charCodeAt(4) - 65) / 12 + 1 / 24; lat += (u.charCodeAt(5) - 65) / 24 + 1 / 48; } else { lon += 1; lat += 0.5; }
    return [lat, lon];
  }

  const D2R = Math.PI / 180, R2D = 180 / Math.PI, R_KM = 6371.0;
  function angle(p1, p2) {
    const la1 = p1[0] * D2R, la2 = p2[0] * D2R, dla = (p2[0] - p1[0]) * D2R, dlo = (p2[1] - p1[1]) * D2R;
    const a = Math.sin(dla / 2) ** 2 + Math.cos(la1) * Math.cos(la2) * Math.sin(dlo / 2) ** 2;
    return 2 * Math.asin(Math.min(1, Math.sqrt(a)));
  }
  function cap(p1, p2) {
    const la1 = p1[0] * D2R, la2 = p2[0] * D2R, dlo = (p2[1] - p1[1]) * D2R;
    return (Math.atan2(Math.sin(dlo) * Math.cos(la2), Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dlo)) * R2D + 360) % 360;
  }
  function destination(p1, c, a) {
    const la1 = p1[0] * D2R, lo1 = p1[1] * D2R, th = c * D2R;
    const la2 = Math.asin(Math.sin(la1) * Math.cos(a) + Math.cos(la1) * Math.sin(a) * Math.cos(th));
    const lo2 = lo1 + Math.atan2(Math.sin(th) * Math.sin(a) * Math.cos(la1), Math.cos(a) - Math.sin(la1) * Math.sin(la2));
    return [la2 * R2D, ((lo2 * R2D + 540) % 360) - 180];
  }
  /** Points along the great circle from p1 (bearing c) between angular distances a0 and a1, longitudes unwrapped (no seam at 180°). */
  function arc(p1, c, a0, a1, n) {
    const pts = []; let prev = null;
    for (let i = 0; i <= n; i++) {
      const p = destination(p1, c, a0 + (a1 - a0) * i / n);
      if (prev !== null) { while (p[1] - prev > 180) p[1] -= 360; while (p[1] - prev < -180) p[1] += 360; }
      prev = p[1]; pts.push(p);
    }
    return pts;
  }
  /** The short great-circle path from p1 to p2. */
  function orthodromie(p1, p2, n) {
    const a = angle(p1, p2);
    if (a < 1e-9) return [p1, p2];
    return arc(p1, cap(p1, p2), 0, a, n || 48);
  }

  /** A Leaflet map on the site's OpenStreetMap tiles (zoom 1..6: the site keeps them, nobody's browser asks OSM). */
  function carte(id, attribution) {
    const m = L.map(id, { worldCopyJump: true, minZoom: 1, maxZoom: 6, zoomSnap: 1 }).setView([30, 5], 2);
    L.tileLayer('/journal/tuile?z={z}&x={x}&y={y}', {
      maxZoom: 6, maxNativeZoom: 6, keepBuffer: 1,
      attribution: '© <a href="https://www.openstreetmap.org/copyright" rel="noopener">les contributeurs d\'OpenStreetMap</a>' + (attribution ? ' · ' + attribution : ''),
    }).addTo(m);
    window.addEventListener('resize', () => m.invalidateSize());
    setTimeout(() => m.invalidateSize(), 120);
    return m;
  }

  /** A tooltip built from text only (Leaflet's tooltips render HTML: [lines] are escaped here, [gras] the first one). */
  function bulle(lignes) {
    const d = document.createElement('div');
    lignes.forEach((t, i) => { const e = document.createElement(i === 0 ? 'b' : 'div'); e.textContent = t; d.appendChild(e); });
    return d;
  }

  /** The sun's elevation (degrees) at lat/lon now (NOAA's fractional-year series). */
  function soleil(date, lat, lon) {
    const start = Date.UTC(date.getUTCFullYear(), 0, 0);
    const doy = Math.floor((date.getTime() - start) / 86400000);
    const h = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600;
    const g = (2 * Math.PI / 365) * (doy - 1 + (h - 12) / 24);
    const eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
    const decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
      - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
    const ha = ((h * 60 + eq + 4 * lon) / 4 - 180) * D2R, la = lat * D2R;
    return 90 - Math.acos(Math.max(-1, Math.min(1, Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha)))) * R2D;
  }

  /** The night's polygon and the terminator's line now (simple declination), into [couche]. */
  function terminateur(couche) {
    couche.clearLayers();
    const d = new Date();
    const doy = Math.floor((d.getTime() - Date.UTC(d.getUTCFullYear(), 0, 0)) / 86400000);
    const decl = 0.4093 * Math.sin(2 * Math.PI * (doy - 81) / 365);
    const h = d.getUTCHours() + d.getUTCMinutes() / 60;
    const sLon = -15 * (h - 12), pts = [];
    for (let lon = -180; lon <= 180; lon += 2) pts.push([Math.atan(-Math.cos((lon - sLon) * D2R) / Math.tan(decl)) * R2D, lon]);
    const pole = decl > 0 ? -90 : 90;
    L.polygon(pts.concat([[pole, 180], [pole, -180]]), { stroke: false, fillColor: '#000', fillOpacity: 0.28, interactive: false }).addTo(couche);
    L.polyline(pts, { color: '#fbbf24', weight: 1.2, opacity: 0.7, dashArray: '5 5', interactive: false }).addTo(couche);
  }

  async function json(url) {
    const r = await fetch(url, { cache: 'no-store', credentials: 'same-origin' });
    if (r.status === 429) throw new Error('trop de requêtes, patienter une minute');
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  }

  function etat(texte, classe, id) {
    const e = $(id || 'hf-etat'); if (!e) return;
    e.textContent = texte; e.className = 'hf-statut' + (classe ? ' ' + classe : '');
  }

  function age(ts) {
    const a = Math.max(0, Math.floor(Date.now() / 1000) - ts);
    return a < 60 ? a + ' s' : a < 3600 ? Math.floor(a / 60) + ' min' : Math.floor(a / 3600) + ' h';
  }

  /** A locator kept in this browser under [cle], else the station's. */
  function qthGarde(cle) { try { const l = localStorage.getItem(cle); if (l && LOC.test(l.toUpperCase())) return l.toUpperCase(); } catch (e) {} return D.locator || 'JN18FS'; }
  function qthGarder(cle, v) { try { localStorage.setItem(cle, v); } catch (e) {} }

  window.HF = { D, $, esc, couleur, ORDRE, LOC, loc, angle, cap, arc, orthodromie, carte, bulle, soleil, terminateur, json, etat, age, qthGarde, qthGarder, R_KM };
})();
