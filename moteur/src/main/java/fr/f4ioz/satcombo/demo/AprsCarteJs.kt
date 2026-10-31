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
 * The APRS tab's map, list and station card (`api/aprs/stations`): each
 * station drawn with its symbol, its callsign, its heading and its trail,
 * ringed by how it was heard (direct, digipeater, satellite, internet);
 * the list beside it (sorted, filtered, the satellite passes grouped); the
 * header's figures. Offline: SatMe's coastlines, OpenStreetMap only when
 * ticked (fetched by the browser). The symbols are drawn here (simple
 * shapes of our own), no artwork from elsewhere. Pointer events: mouse,
 * pen and touch (drag, pinch, tap).
 */
object AprsCarteJs {

    const val CSS = """
  .aq-stats { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; margin-bottom:10px; }
  .aq-tuile { background:var(--s2); border:1px solid var(--ligne); border-radius:var(--r3); padding:8px 12px; box-shadow:var(--ombre); min-width:0; }
  .aq-tuile .aq-l { font-size:var(--fs-xs); color:var(--texte-2); text-transform:uppercase; letter-spacing:.04em; }
  .aq-tuile .val { font-size:22px; line-height:1.2; }
  .aq-tuile .aq-s { font-size:var(--fs-s); color:var(--texte-2); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .aq-tuile canvas { width:100%; height:26px; display:block; margin-top:2px; }
  .aq-etat { grid-column:1/-1; font-size:var(--fs-s); color:var(--texte-2); margin-top:-2px; }
  .aq-corps { display:grid; grid-template-columns:minmax(0,1fr) 380px; gap:10px; margin-bottom:10px; }
  .aq-boite { position:relative; height:calc(100vh - 230px); min-height:420px; border-radius:var(--r3); overflow:hidden; border:1px solid var(--ligne); background:#0D2433; }
  #aq-carte { width:100%; height:100%; display:block; touch-action:none; cursor:grab; }
  .aq-outils { position:absolute; left:8px; top:8px; display:flex; flex-direction:column; gap:6px; z-index:2; }
  .aq-outils button { width:34px; height:34px; padding:0; font-size:18px; line-height:1; background:rgba(15,22,29,.88); border:1px solid var(--ligne-forte); border-radius:8px; color:var(--texte); }
  .aq-outils button.on { border-color:var(--rx); color:var(--rx); }
  .aq-reglages { position:absolute; right:8px; top:8px; z-index:2; display:flex; gap:6px; flex-wrap:wrap; justify-content:flex-end; align-items:center;
    background:rgba(15,22,29,.86); border:1px solid var(--ligne); border-radius:8px; padding:4px 8px; font-size:var(--fs-s); color:var(--texte-2); max-width:calc(100% - 64px); }
  .aq-reglages select { padding:1px 4px; font-size:var(--fs-s); }
  .aq-legende { position:absolute; left:8px; bottom:8px; z-index:2; display:flex; gap:10px; flex-wrap:wrap; background:rgba(15,22,29,.86); border:1px solid var(--ligne);
    border-radius:8px; padding:4px 8px; font-size:var(--fs-xs); color:var(--texte-2); max-width:calc(100% - 16px); }
  .aq-legende i { display:inline-block; width:10px; height:10px; border-radius:50%; border:2px solid; margin-right:4px; vertical-align:-1px; }
  .aq-attrib { position:absolute; right:6px; bottom:4px; z-index:2; font-size:10px; background:rgba(255,255,255,.8); color:#06131A; padding:0 4px; border-radius:3px; }
  .aq-attrib a { color:#06131A; }
  .aq-fiche { position:absolute; right:8px; top:48px; width:340px; max-height:calc(100% - 96px); overflow:auto; z-index:3; background:var(--s2); border:1px solid var(--ligne-forte);
    border-radius:var(--r3); box-shadow:var(--ombre-pop); padding:10px 12px; font-size:var(--fs-m); }
  .aq-fiche .aq-ferme { position:sticky; top:0; float:right; padding:0 8px; font-size:16px; }
  .aq-fiche h3 { margin:0 0 2px; font-size:17px; display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  .aq-fiche .aq-ligne { display:flex; gap:8px; padding:3px 0; border-top:1px solid var(--ligne); }
  .aq-fiche .aq-ligne > b { flex:none; width:96px; font-weight:500; color:var(--texte-2); font-size:var(--fs-s); }
  .aq-fiche .aq-ligne > span { min-width:0; overflow-wrap:anywhere; }
  .aq-fiche pre { margin:2px 0 0; white-space:pre-wrap; word-break:break-all; font-family:var(--mono); font-size:11px; color:var(--texte-2); background:var(--creux); padding:6px; border-radius:6px; }
  .aq-fiche a { color:var(--rx); }
  .aq-cote { display:flex; flex-direction:column; min-width:0; height:calc(100vh - 230px); min-height:420px; }
  .aq-cote .carte { flex:1; display:flex; flex-direction:column; min-height:0; margin:0; }
  .aq-filtres { display:flex; gap:6px; flex-wrap:wrap; align-items:center; margin-bottom:6px; }
  .aq-filtres input[type=search] { flex:1 1 140px; min-width:0; }
  .aq-filtres select { flex:none; padding:2px 4px; }
  .aq-puce { padding:2px 9px; border-radius:20px; font-size:var(--fs-s); background:var(--s1); border:1px solid var(--ligne); color:var(--texte-2); }
  .aq-puce[aria-pressed=true] { border-color:var(--rx); color:var(--rx); background:rgba(63,224,200,.08); }
  .aq-seg { display:inline-flex; border:1px solid var(--ligne); border-radius:20px; overflow:hidden; }
  .aq-seg button { border:0; border-radius:0; padding:2px 8px; font-size:var(--fs-s); background:var(--s1); color:var(--texte-2); }
  .aq-seg button[aria-pressed=true] { background:rgba(63,224,200,.14); color:var(--rx); }
  .aq-liste { flex:1; overflow:auto; min-height:0; margin:0 -4px; }
  .aq-st { display:flex; gap:8px; align-items:center; padding:6px 4px; border-bottom:1px solid var(--ligne); cursor:pointer; border-radius:6px; }
  .aq-st:hover { background:var(--s3); }
  .aq-st.sel { background:rgba(63,224,200,.10); }
  .aq-st svg { flex:none; }
  .aq-st .aq-m { flex:1; min-width:0; }
  .aq-st .aq-m b { font-size:var(--fs-l); }
  .aq-st .aq-m .petit { display:block; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .aq-st .aq-d { flex:none; text-align:right; font-size:var(--fs-s); color:var(--texte-2); font-variant-numeric:tabular-nums; }
  .aq-voie { display:inline-block; font-size:10px; font-weight:700; padding:0 6px; border-radius:10px; border:1px solid; margin-left:4px; vertical-align:1px; }
  .aq-passe { font-size:var(--fs-s); font-weight:700; color:var(--sat); padding:8px 4px 4px; border-bottom:1px solid var(--ligne); }
  .aq-vide { padding:16px 4px; color:var(--texte-2); font-size:var(--fs-s); }
  @media (max-width:1100px) {
    .aq-corps { grid-template-columns:1fr; }
    .aq-boite { height:56vh; min-height:300px; }
    .aq-cote { height:auto; min-height:0; }
    .aq-cote .carte { max-height:none; }
    .aq-liste { max-height:70vh; }
    .aq-fiche { left:6px; right:6px; top:auto; bottom:6px; width:auto; max-height:62%; }
  }
  @media (max-width:520px) {
    .aq-stats { grid-template-columns:1fr 1fr; gap:6px; }
    .aq-tuile .val { font-size:18px; }
    .aq-legende { display:none; }
    .aq-reglages { top:auto; bottom:8px; right:8px; }
  }
"""

    /** The top of the APRS tab: the figures, the map with its card, the list. */
    const val HTML = """
  <div class="aq-stats">
    <div class="aq-tuile"><div class="aq-l">Stations aujourd’hui</div><div class="val" id="aq-jour">0</div><div class="aq-s" id="aq-jour-s">&nbsp;</div></div>
    <div class="aq-tuile"><div class="aq-l">Via satellite</div><div class="val" id="aq-sat" style="color:var(--sat)">0</div><div class="aq-s" id="aq-sat-s">&nbsp;</div></div>
    <div class="aq-tuile"><div class="aq-l">Plus loin en direct</div><div class="val" id="aq-loin">—</div><div class="aq-s" id="aq-loin-s">&nbsp;</div></div>
    <div class="aq-tuile"><div class="aq-l">Paquets / heure</div><div class="val" id="aq-ph">0</div><canvas id="aq-spark" aria-label="Paquets reçus par heure, sur 24 heures"></canvas></div>
    <div class="aq-etat" id="a-etat"></div>
  </div>
  <div class="aq-corps">
    <div class="aq-boite" id="aq-boite">
      <canvas id="aq-carte" aria-label="Carte des stations APRS entendues"></canvas>
      <div class="aq-outils">
        <button type="button" onclick="aqZoom(1)" title="Zoomer" aria-label="Zoomer">+</button>
        <button type="button" onclick="aqZoom(-1)" title="Dézoomer" aria-label="Dézoomer">−</button>
        <button type="button" id="aq-suivre" class="on" onclick="aqSuit()" title="Recadrer sur les stations (ou suivre la station choisie) à chaque nouvelle trame" aria-label="Recadrer et suivre">⤢</button>
      </div>
      <div class="aq-reglages">
        <label>Traces <select id="aq-trace"><option value="0">aucune</option><option value="1">1 h</option><option value="3">3 h</option><option value="6">6 h</option><option value="12">12 h</option><option value="24">24 h</option><option value="48">48 h</option></select></label>
        <label><input type="checkbox" id="aq-osm"> OpenStreetMap</label>
      </div>
      <div class="aq-legende" aria-label="Légende">
        <span><i style="border-color:#5BD68A"></i>direct</span><span><i style="border-color:#6F8CFF"></i>digipeater</span>
        <span><i style="border-color:#FFD166;box-shadow:0 0 0 2px #FFD16655"></i>satellite</span><span><i style="border-color:#9AA7B4;border-style:dashed"></i>Internet</span>
        <span><i style="border-color:#3FE0C8;background:#3FE0C8"></i>ma station</span>
      </div>
      <div class="aq-attrib" id="aq-attrib" hidden>© les contributeurs d’<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a></div>
      <div class="aq-fiche" id="aq-fiche" hidden></div>
    </div>
    <div class="aq-cote">
      <div class="carte">
        <div class="aq-filtres">
          <input type="search" id="aq-q" placeholder="Chercher : indicatif, commentaire, locator…" aria-label="Chercher une station">
          <select id="aq-tri" aria-label="Trier"><option value="dernier">Dernière écoute</option><option value="proche">Distance, proche d’abord</option><option value="loin">Distance, loin d’abord</option><option value="call">Indicatif</option></select>
        </div>
        <div class="aq-filtres">
          <button type="button" class="aq-puce" id="aq-rf" aria-pressed="false" title="Seulement les stations entendues par la radio (direct, digipeater ou satellite)">RF seulement</button>
          <button type="button" class="aq-puce" id="aq-viasat" aria-pressed="false" title="Seulement les stations reçues par un satellite, rangées par passage">Via satellite</button>
          <span class="aq-seg" id="aq-periode" role="group" aria-label="Période"><button type="button" data-h="1">1 h</button><button type="button" data-h="6">6 h</button><button type="button" data-h="24">24 h</button><button type="button" data-h="48">48 h</button></span>
          <select id="aq-genre" aria-label="Type de station"><option value="">Tous types</option><option value="mobile">Mobiles</option><option value="fixe">Fixes</option><option value="meteo">Météo</option><option value="digi">Digipeaters</option><option value="igate">iGates</option><option value="objet">Objets</option><option value="satellite">Satellites</option></select>
        </div>
        <div class="petit" id="aq-compte" style="margin-bottom:4px"></div>
        <div class="aq-liste" id="aq-liste" role="list"></div>
      </div>
    </div>
  </div>
"""

    /** The part without the page (no DOM): pictograms, filters, sort, compass words. */
    const val PUR = """
// ------------------------------------------------------------------ APRS map: its pure part (also run under node by AprsCarteJsTest)
// The pictograms: simple shapes of our own, in a 24 × 24 box (filled, even-odd).
const AQ_PICTO = {
  voiture: 'M3 15l2.2-5.4A2 2 0 0 1 7 8.5h10a2 2 0 0 1 1.8 1.1L21 15v4h-2.2a2.3 2.3 0 0 1-4.6 0H9.8a2.3 2.3 0 0 1-4.6 0H3zM6.8 13h10.4l-1.2-3H8z',
  camion: 'M1.5 6h12v10h-12zM14 9h4.5l3.5 4v3h-8zM5.5 16.2a2 2 0 1 0 .01 0zM17.5 16.2a2 2 0 1 0 .01 0z',
  maison: 'M12 2.5l9.5 8.5h-3v10h-5v-6h-3v6h-5V11h-3z',
  etoile: 'M12 1.5l3.1 6.6 7.2.9-5.3 5 1.4 7.1L12 17.6l-6.4 3.5L7 14l-5.3-5 7.2-.9z',
  losange: 'M12 1.5L22.5 12 12 22.5 1.5 12zM12 6L6 12l6 6 6-6z',
  meteo: 'M9 4.5a3 3 0 0 1 6 0v8.2a5 5 0 1 1-6 0zM11 6v8l-.6.4a3 3 0 1 0 3.2 0L13 14V6z',
  ballon: 'M12 1.5a7.5 7.5 0 0 1 7.5 7.5c0 4.3-4 7.6-6.3 8.5h-2.4C8.5 16.6 4.5 13.3 4.5 9A7.5 7.5 0 0 1 12 1.5zM10 19h4v3.5h-4z',
  avion: 'M21.5 16v-2.2L13.5 9V3.6a1.5 1.5 0 0 0-3 0V9l-8 4.8V16l8-2.4V19l-2 1.5V22l3.5-1 3.5 1v-1.5l-2-1.5v-5.4z',
  bateau: 'M2.5 15.5h19l-3.2 5H5.7zM11 2v12H4.5zM13 5.5l6 8.5h-6z',
  velo: 'M5.5 12.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8zm0 2a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM18.5 12.5a4 4 0 1 0 0 8a4 4 0 1 0 0-8zm0 2a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM9 9h5l-.6 1.5H11l1.5 3.5 2.4-4.5H13.5L14 8h3l1.8 5.6-1.6.5-1.1-3.4-3.2 6h-2.2z',
  pieton: 'M12 1.5a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM7.5 9h9l-1.2 7h-2v6.5h-2.6V16h-2z',
  relais: 'M10.8 3h2.4l4.6 19h-2.4l-1.1-5H9.7l-1.1 5H6.2zM10.2 14.5h3.6L12 6.6z',
  satellite: 'M9.5 9.5h5v5h-5zM1.5 8.5h6.5v7H1.5zM16 8.5h6.5v7H16zM11 3h2v5h-2z',
  drapeau: 'M5 2.5h2v19H5zM7.5 3.5h12l-3.3 4.2 3.3 4.3h-12z',
  point: 'M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12z',
};
/** A symbol ("/>", "\\&", "I&"…) → its pictogram. */
function aqPicto(sym) {
  if (!sym || sym.length < 2) return 'point';
  const p = sym[0] === '/', c = sym[1];
  const P = { '>': 'voiture', 'j': 'voiture', 'R': 'voiture', 'a': 'voiture', 'v': 'voiture', '=': 'camion', 'k': 'camion', 'u': 'camion', 'U': 'camion', 'f': 'camion', 'F': 'camion',
    '-': 'maison', 'y': 'maison', 'H': 'maison', 'K': 'maison', 'h': 'maison', 'd': 'maison', '!': 'maison', '#': 'etoile', '&': 'losange', '_': 'meteo', 'W': 'meteo',
    'O': 'ballon', "'": 'avion', '^': 'avion', 'X': 'avion', 'g': 'avion', 'S': 'satellite', '(': 'satellite', 's': 'bateau', 'Y': 'bateau', 'C': 'bateau',
    'b': 'velo', '<': 'velo', '[': 'pieton', 'e': 'pieton', 'p': 'pieton', 'r': 'relais', 'm': 'relais', 'n': 'relais', '`': 'relais', 'I': 'losange', ';': 'drapeau' };
  const A = { '>': 'voiture', 'k': 'voiture', 'u': 'camion', 'v': 'voiture', '-': 'maison', '#': 'etoile', '&': 'losange', '_': 'meteo', 'W': 'meteo', 'O': 'ballon', '^': 'avion',
    "'": 'avion', 's': 'bateau', 'C': 'bateau', 'N': 'bateau', 'S': 'satellite', '0': 'relais', 'L': 'relais', 'a': 'drapeau', 'i': 'drapeau', '/': 'drapeau' };
  return (p ? P[c] : A[c]) || 'point';
}
/** The overlay character a symbol carries (its table when it is not '/' or '\\'), or ''. */
function aqSurcharge(sym) { if (!sym || sym.length < 2 || sym[0] === '/' || sym[0] === '\\') return ''; const t = sym[0]; return t >= 'a' && t <= 'j' ? String(t.charCodeAt(0) - 97) : t; }
const AQ_ROSE = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSO', 'SO', 'OSO', 'O', 'ONO', 'NO', 'NNO'];
const aqRose = d => AQ_ROSE[Math.round(((d % 360) + 360) % 360 / 22.5) % 16];
/** Shown with filters [f] ({periode h, rf, sat, genre, q}) at [maintenant]. */
function aqVisible(s, f, maintenant) {
  if (s.dernier < maintenant - f.periode * 3600000) return false;
  const v = s.vus || {};
  if (f.rf && !(v.direct || v.digi || v.satellite)) return false;
  if (f.sat && !v.satellite) return false;
  if (f.genre && s.genre !== f.genre) return false;
  if (f.q) { const q = f.q.toUpperCase(); if (![s.id, s.call, s.com, s.statut, s.loc, s.symNom, s.sat].join(' ').toUpperCase().includes(q)) return false; }
  return true;
}
/** Sorted in place by [tri]: 'dernier' (newest first), 'proche', 'loin', 'call'. Unknown distances last. */
function aqTrie(l, tri) {
  const km = s => s.km == null ? 1e9 : s.km;
  if (tri === 'proche') l.sort((a, b) => km(a) - km(b));
  else if (tri === 'loin') l.sort((a, b) => (b.km == null ? -1 : b.km) - (a.km == null ? -1 : a.km));
  else if (tri === 'call') l.sort((a, b) => a.id.localeCompare(b.id));
  else l.sort((a, b) => b.dernier - a.dernier);
  return l;
}
"""

    const val CODE = PUR + """
// ------------------------------------------------------------------ APRS: the map, the list, the station card
let AQ = null, aqSel = null, aqPts = [], aqVu = 0, aqOuvert = false, aqAppel = false;
const AQV = { cx: 0.5, cy: 0.35, z: 2, libre: false };
const AQF = { tri: 'dernier', rf: 0, sat: 0, periode: 24, genre: '', q: '', trace: 6 };
try { Object.assign(AQF, JSON.parse(localStorage.getItem('aq-filtres') || '{}')); } catch (x) {}
AQF.q = '';
const AQ_VOIE = { direct: '#5BD68A', digi: '#6F8CFF', satellite: '#FFD166', internet: '#9AA7B4' };
const AQ_VOIE_NOM = { direct: 'direct', digi: 'digi', satellite: 'satellite', internet: 'Internet' };
const AQ_GENRE = { mobile: 'mobile', fixe: 'fixe', meteo: 'météo', digi: 'digipeater', igate: 'iGate', objet: 'objet', satellite: 'satellite' };
const aqCouleur = s => s.moi ? '#3FE0C8' : (AQ_VOIE[s.voie] || '#9AA7B4');
/** The symbol as a small SVG (the list, the card). */
function aqSvg(s, t) {
  t = t || 30;
  const c = aqCouleur(s), o = aqSurcharge(s.sym), sat = s.vus && s.vus.satellite;
  return '<svg width="' + t + '" height="' + t + '" viewBox="0 0 32 32" aria-hidden="true">' +
    (sat ? '<circle cx="16" cy="16" r="15" fill="none" stroke="#FFD166" stroke-width="1.5" stroke-dasharray="3 2"/>' : '') +
    '<circle cx="16" cy="16" r="12" fill="#0F161D" stroke="' + c + '" stroke-width="2.5"' + (s.voie === 'internet' ? ' stroke-dasharray="4 3"' : '') + '/>' +
    '<path transform="translate(8.5 8.5) scale(.625)" fill="#E8F0F7" fill-rule="evenodd" d="' + AQ_PICTO[aqPicto(s.sym)] + '"/>' +
    (o ? '<rect x="20" y="20" width="11" height="11" rx="2" fill="#E8F0F7"/><text x="25.5" y="29" font-size="9" font-weight="700" text-anchor="middle" fill="#06131A" font-family="system-ui">' + esc(o) + '</text>' : '') +
    '</svg>';
}
const aqPath = {};
function aqPath2D(k) { return aqPath[k] || (aqPath[k] = new Path2D(AQ_PICTO[k])); }
/** The same symbol on the map: ring by the way it was heard, its heading, selected or not. */
function aqBadge(x, X, Y, s, sel) {
  const c = aqCouleur(s), r = 10;
  x.save(); x.translate(X, Y);
  if (s.cap != null && (s.kmh || 0) > 0) {
    x.save(); x.rotate(s.cap * Math.PI / 180); x.fillStyle = c; x.strokeStyle = '#06131A'; x.lineWidth = 1;
    x.beginPath(); x.moveTo(0, -r - 9); x.lineTo(5, -r - 1); x.lineTo(-5, -r - 1); x.closePath(); x.fill(); x.stroke(); x.restore();
  }
  if (sel) { x.fillStyle = 'rgba(255,255,255,.18)'; x.beginPath(); x.arc(0, 0, r + 8, 0, 7); x.fill(); x.strokeStyle = '#FFFFFF'; x.lineWidth = 2; x.beginPath(); x.arc(0, 0, r + 8, 0, 7); x.stroke(); }
  if (s.vus && s.vus.satellite) { x.strokeStyle = '#FFD166'; x.lineWidth = 1.5; x.setLineDash([3, 2]); x.beginPath(); x.arc(0, 0, r + 4, 0, 7); x.stroke(); x.setLineDash([]); }
  x.fillStyle = '#0F161D'; x.beginPath(); x.arc(0, 0, r, 0, 7); x.fill();
  x.strokeStyle = c; x.lineWidth = 2.5; if (s.voie === 'internet' && !s.moi) x.setLineDash([4, 3]);
  x.beginPath(); x.arc(0, 0, r, 0, 7); x.stroke(); x.setLineDash([]);
  x.save(); x.translate(-6.5, -6.5); x.scale(13 / 24, 13 / 24); x.fillStyle = '#E8F0F7'; x.fill(aqPath2D(aqPicto(s.sym)), 'evenodd'); x.restore();
  const o = aqSurcharge(s.sym);
  if (o) { x.fillStyle = '#E8F0F7'; x.fillRect(3, 3, 9, 9); x.fillStyle = '#06131A'; x.font = '700 8px system-ui'; x.textAlign = 'center'; x.fillText(o, 7.5, 10.5); }
  x.restore();
}
// The background: SatMe's coastlines, or the OpenStreetMap tiles when ticked (the same choice as the Carte tab's).
const aqTuiles = new Map();
function aqOsm(z, x, y) {
  const n = 1 << z, xw = ((x % n) + n) % n, k = z + '/' + xw + '/' + y;
  let i = aqTuiles.get(k);
  if (!i) {
    i = new Image(); i.onload = () => aqDessine(); i.src = 'https://tile.openstreetmap.org/' + z + '/' + xw + '/' + y + '.png'; aqTuiles.set(k, i);
    if (aqTuiles.size > 300) aqTuiles.delete(aqTuiles.keys().next().value);
  }
  return i.complete && i.naturalWidth ? i : null;
}
function aqFond(c, vw) {
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  const s = 256 * Math.pow(2, vw.z), ox = w / 2 - vw.cx * s, oy = h / 2 - vw.cy * s, osm = g('aq-osm').checked;
  x.fillStyle = osm ? '#AAD3DF' : '#0D2433'; x.fillRect(0, 0, w, h);
  if (osm) {
    const z = Math.max(0, Math.min(18, Math.floor(vw.z))), ts = 256 * Math.pow(2, vw.z - z), n = 1 << z;
    for (let ty = Math.max(0, Math.floor(-oy / ts)); ty <= Math.min(n - 1, Math.floor((h - oy) / ts)); ty++)
      for (let tx = Math.floor(-ox / ts); tx <= Math.floor((w - ox) / ts); tx++) { const i = aqOsm(z, tx, ty); if (i) x.drawImage(i, ox + tx * ts, oy + ty * ts, ts + 0.5, ts + 0.5); }
  } else {
    for (let k = Math.floor(-ox / s); k <= Math.floor((w - ox) / s); k++) {
      x.setTransform(dpr * s, 0, 0, dpr * s, dpr * (ox + k * s), dpr * oy);
      if (cgTraits) { x.fillStyle = '#1E3B30'; x.strokeStyle = '#4F8068'; x.lineWidth = 1 / s; for (const p of cgTraits) { x.fill(p); x.stroke(p); } }
    }
    x.setTransform(dpr, 0, 0, dpr, 0, 0);
    const pas = vw.z >= 7 ? 1 : vw.z >= 4 ? 10 : 30;
    x.strokeStyle = 'rgba(160,200,230,.10)'; x.lineWidth = 1; x.beginPath();
    for (let lo = -180; lo <= 180; lo += pas) for (let k = Math.floor(-ox / s); k <= Math.floor((w - ox) / s); k++) { const X = ox + (k + wxk(lo)) * s; if (X >= 0 && X <= w) { x.moveTo(X, 0); x.lineTo(X, h); } }
    for (let la = -80; la <= 80; la += pas) { const Y = oy + wyq(la) * s; if (Y >= 0 && Y <= h) { x.moveTo(0, Y); x.lineTo(w, Y); } }
    x.stroke();
  }
  g('aq-attrib').hidden = !osm;
  const cLon = vw.cx * 360 - 180, L = lon => lon + 360 * Math.round((cLon - lon) / 360);
  return { x, w, h, px: lon => ox + wxk(L(lon)) * s, py: lat => oy + wyq(lat) * s, osm };
}
// ---- what is shown: the filters
function aqGarde() { try { localStorage.setItem('aq-filtres', JSON.stringify(Object.assign({}, AQF, { q: '' }))); } catch (x) {} }
function aqListe() { return AQ && AQ.stations ? aqTrie(AQ.stations.filter(s => aqVisible(s, AQF, AQ.t)), AQF.tri) : []; }
// ---- words
function aqIlYa(t, maintenant) {
  const s = Math.max(0, Math.round(((maintenant || Date.now()) - t) / 1000));
  if (s < 60) return 'à l’instant';
  if (s < 3600) return 'il y a ' + Math.floor(s / 60) + ' min';
  if (s < 86400) return 'il y a ' + Math.floor(s / 3600) + ' h ' + String(Math.floor(s % 3600 / 60)).padStart(2, '0');
  return 'il y a ' + Math.round(s / 86400) + ' j';
}
function aqDate(t) {
  const d = new Date(t), a = new Date(), hier = new Date(Date.now() - 86400000);
  const meme = (u, v) => u.getFullYear() === v.getFullYear() && u.getMonth() === v.getMonth() && u.getDate() === v.getDate();
  return (meme(d, a) ? 'aujourd’hui' : meme(d, hier) ? 'hier' : String(d.getDate()).padStart(2, '0') + '/' + String(d.getMonth() + 1).padStart(2, '0')) + ' ' + hm(t) + (utc() ? ' UTC' : '');
}
const aqKm = k => k == null ? '' : k < 10 ? k.toFixed(1).replace('.', ',') + ' km' : Math.round(k) + ' km';
function aqVoiePastille(v) { const c = AQ_VOIE[v] || '#9AA7B4'; return '<span class="aq-voie" style="color:' + c + ';border-color:' + c + '">' + AQ_VOIE_NOM[v] + '</span>'; }
// ---- the list
function aqLigne(s) {
  const d = [s.km != null ? aqKm(s.km) + (s.az != null ? ' ' + aqRose(s.az) : '') : '', aqIlYa(s.dernier, AQ.t)].filter(Boolean).join('<br>');
  const sous = [AQ_GENRE[s.genre] + (s.symNom && s.symNom !== AQ_GENRE[s.genre] ? ' · ' + s.symNom : ''), s.statut || s.com].filter(Boolean).join(' — ');
  return '<div class="aq-st' + (s.id === aqSel ? ' sel' : '') + '" role="listitem" tabindex="0" data-id="' + esc(s.id).replace(/"/g, '&quot;') + '">' + aqSvg(s) +
    '<div class="aq-m"><b>' + esc(s.id) + '</b>' + (s.moi ? ' <span class="aq-voie" style="color:#3FE0C8;border-color:#3FE0C8">moi</span>' : '') + aqVoiePastille(s.voie) +
    (s.vus && s.vus.satellite && s.voie !== 'satellite' ? aqVoiePastille('satellite') : '') +
    '<span class="petit">' + esc(sous) + '</span></div><div class="aq-d">' + d + '</div></div>';
}
function aqDessineListe() {
  const l = aqListe(), el = g('aq-liste');
  txt('aq-compte', l.length + ' station' + (l.length > 1 ? 's' : '') + ' sur ' + (AQ ? AQ.stations.length : 0) + ' en ' + (AQ ? AQ.heures : 48) + ' h');
  if (!l.length) { el.innerHTML = '<div class="aq-vide">' + (AQ && AQ.stations.length ? 'Aucune station ne répond à ces filtres.' : 'Rien entendu encore. Le décodeur APRS écoute la carte son (onglet Son) ou une radio KISS (ci-dessous).') + '</div>'; return; }
  let h = '';
  if (AQF.sat && AQ.passages && AQ.passages.length) {
    // Grouped by satellite pass: « Reçus via ISS pendant le passage de 14:32 ».
    const parId = new Map(l.map(s => [s.id, s])), vus = new Set();
    AQ.passages.forEach(p => {
      const ss = p.stations.map(i => parId.get(i)).filter(Boolean);
      if (!ss.length) return;
      h += '<div class="aq-passe">Reçus via ' + esc(p.sat) + ' pendant le passage de ' + hm(p.debut) + (new Date(p.debut).toDateString() !== new Date().toDateString() ? ' (' + aqDate(p.debut).split(' ')[0] + ')' : '') +
        ' · ' + ss.length + ' station' + (ss.length > 1 ? 's' : '') + ', ' + p.n + ' paquet' + (p.n > 1 ? 's' : '') + '</div>';
      ss.forEach(s => { vus.add(s.id); h += aqLigne(s); });
    });
    const reste = l.filter(s => !vus.has(s.id));
    if (reste.length) { h += '<div class="aq-passe">Autres</div>'; reste.forEach(s => { h += aqLigne(s); }); }
  } else l.slice(0, 600).forEach(s => { h += aqLigne(s); });
  el.innerHTML = h;
}
g('aq-liste').addEventListener('click', ev => { const r = ev.target.closest('.aq-st'); if (r) aqChoisit(r.dataset.id, true); });
g('aq-liste').addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { const r = ev.target.closest('.aq-st'); if (r) { ev.preventDefault(); aqChoisit(r.dataset.id, true); } } });
// ---- the station card
function aqFiche() {
  const el = g('aq-fiche'), s = AQ && aqSel ? AQ.stations.find(q => q.id === aqSel) : null;
  if (!s) { el.hidden = true; return; }
  const L = (k, v) => v === '' || v == null ? '' : '<div class="aq-ligne"><b>' + k + '</b><span>' + v + '</span></div>';
  const base = s.call.split('-')[0];
  const liens = (s.objet ? '' : '<a href="https://www.qrz.com/db/' + encodeURIComponent(base) + '" target="_blank" rel="noopener">QRZ.com</a> · ') +
    '<a href="https://aprs.fi/#!call=a%2F' + encodeURIComponent(s.id) + '" target="_blank" rel="noopener">aprs.fi</a>';
  const voies = [['direct', 'En direct (RF, sans digipeater)'], ['digi', 'Par digipeater (RF)'], ['satellite', 'Par satellite' + (s.sat ? ' (' + esc(s.sat) + ')' : '')], ['internet', 'Par Internet (APRS-IS)']]
    .filter(v => s.vus && s.vus[v[0]]).map(v => '<span style="color:' + AQ_VOIE[v[0]] + '">●</span> ' + v[1] + ' : ' + aqDate(s.vus[v[0]]) + ' <span class="petit">(' + aqIlYa(s.vus[v[0]]) + ')</span>').join('<br>');
  const mouv = [s.cap != null ? 'cap ' + s.cap + '°' : '', s.kmh != null ? s.kmh + ' km/h' : '', s.alt != null ? 'altitude ' + s.alt + ' m' : ''].filter(Boolean).join(' · ');
  const m = s.meteo, meteo = m ? [m.tempC != null ? m.tempC.toFixed(1).replace('.', ',') + ' °C' : '', m.hum != null ? m.hum + ' %' : '', m.hpa != null ? m.hpa.toFixed(1).replace('.', ',') + ' hPa' : '',
    m.ventKmh != null ? 'vent ' + m.ventKmh + ' km/h' + (m.ventDeg != null ? ' du ' + aqRose(m.ventDeg) + ' (' + m.ventDeg + '°)' : '') : '', m.rafaleKmh != null ? 'rafales ' + m.rafaleKmh + ' km/h' : '',
    m.pluie1h != null ? 'pluie ' + String(m.pluie1h).replace('.', ',') + ' mm/h' : ''].filter(Boolean).join(' · ') + (m.t ? ' <span class="petit">(' + aqIlYa(m.t) + ')</span>' : '') : '';
  const ou = s.km != null ? aqKm(s.km) + (s.az != null ? ', azimut ' + s.az + '° (' + aqRose(s.az) + ')' : '') : '';
  el.innerHTML = '<button type="button" class="aq-ferme" onclick="aqChoisit(null)" aria-label="Fermer">×</button>' +
    '<h3>' + aqSvg(s, 34) + '<span>' + esc(s.id) + '</span>' + aqVoiePastille(s.voie) + '</h3>' +
    '<div class="petit" style="margin-bottom:6px">' + esc([s.symNom || 'symbole ' + (s.sym || 'absent'), AQ_GENRE[s.genre]].filter((v, i, a) => v && a.indexOf(v) === i).join(' · ')) +
    (s.objet ? ' · émis par <b>' + esc(s.call) + '</b>' : '') + ' · ' + liens + '</div>' +
    L('Commentaire', esc(s.com)) + L('Statut', esc(s.statut)) +
    L('Distance', ou) + L('Locator', s.loc ? esc(s.loc) : 'position inconnue') + L('Mouvement', mouv) +
    L('Entendue', voies) +
    L('Chemin', s.digis && s.digis.length ? esc(s.digis.join(' → ')) : s.voie === 'direct' ? 'direct, aucun digipeater' : esc(s.chemin)) +
    L('Paquets', s.n + ' en ' + AQ.heures + ' h · premier ' + aqDate(s.premier)) +
    L('Météo', meteo) +
    L('Message', s.msg ? 'à ' + esc(s.msg.a) + ' : « ' + esc(s.msg.txt) + ' » <span class="petit">(' + aqIlYa(s.msg.t) + ')</span>' : '') +
    L('Télémétrie', s.tlm ? esc(s.tlm.txt) + ' <span class="petit">(' + aqIlYa(s.tlm.t) + ')</span>' : '') +
    '<div class="aq-ligne" style="display:block"><b>Dernier paquet brut</b><pre>' + esc(s.brut) + '</pre></div>';
  el.hidden = false;
}
/** The view centred on [lat, lon]; on a narrow screen a little higher, above the card. */
function aqCentre(lat, lon) {
  const c = g('aq-carte'), etroit = c.clientWidth < 700 && aqSel;
  AQV.cx = wxk(lon); AQV.cy = wyq(lat) + (etroit ? c.clientHeight * 0.28 / (256 * Math.pow(2, AQV.z)) : 0);
}
function aqChoisit(id, centre) {
  aqSel = id;
  if (id && centre) { const s = AQ.stations.find(q => q.id === id); if (s && s.lat != null) { AQV.z = Math.max(AQV.z, 9); aqCentre(s.lat, s.lon); AQV.libre = true; } }
  aqFiche(); aqDessineListe(); aqDessine();
}
// ---- the map
function aqCadre() {
  const c = g('aq-carte'); if (!c.clientWidth || !AQ) return;
  if (aqSel && g('aq-suivre').classList.contains('on')) {
    const s = AQ.stations.find(q => q.id === aqSel);
    if (s && s.lat != null) { aqCentre(s.lat, s.lon); return; }
  }
  const pts = aqListe().filter(s => s.lat != null).map(s => [s.lat, s.lon]);
  if (AQ.moi) pts.push([AQ.moi.lat, AQ.moi.lon]);
  if (!pts.length) { AQV.cx = 0.5; AQV.cy = 0.35; AQV.z = 2; return; }
  // Longitudes kept around the station's (one copy of the world).
  const l0 = AQ.moi ? AQ.moi.lon : pts[0][1];
  const xs = pts.map(p => wxk(p[1] + 360 * Math.round((l0 - p[1]) / 360))), ys = pts.map(p => wyq(p[0]));
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  AQV.cx = (x0 + x1) / 2; AQV.cy = (y0 + y1) / 2;
  AQV.z = Math.max(1.5, Math.min(12, Math.log2(Math.min(Math.max(80, c.clientWidth - 130) / 256 / Math.max(x1 - x0, 1e-4), Math.max(80, c.clientHeight - 130) / 256 / Math.max(y1 - y0, 1e-4)) * 0.9)));
}
function aqDessine() {
  const c = g('aq-carte'); if (!c || g('v-aprs').hidden || !c.clientWidth) return;
  if (!AQV.libre) aqCadre();
  const m = aqFond(c, AQV), x = m.x;
  aqPts = [];
  if (!AQ) return;
  const l = aqListe(), depuis = AQ.t - AQF.trace * 3600000;
  // Trails first, under the symbols.
  if (AQF.trace > 0) l.forEach(s => {
    const t = (s.trace || []).filter(p => p[0] >= depuis);
    if (t.length < 2) return;
    const c0 = aqCouleur(s);
    x.strokeStyle = c0; x.globalAlpha = s.id === aqSel ? 0.95 : 0.6; x.lineWidth = s.id === aqSel ? 3 : 2; x.setLineDash(s.voie === 'internet' ? [5, 4] : []);
    x.beginPath(); t.forEach((p, k) => { const X = m.px(p[2]), Y = m.py(p[1]); if (k) x.lineTo(X, Y); else x.moveTo(X, Y); }); x.stroke(); x.setLineDash([]);
    x.fillStyle = c0; t.slice(0, -1).forEach(p => { x.beginPath(); x.arc(m.px(p[2]), m.py(p[1]), 2, 0, 7); x.fill(); });
    x.globalAlpha = 1;
  });
  // My station.
  if (AQ.moi) {
    const X = m.px(AQ.moi.lon), Y = m.py(AQ.moi.lat);
    x.fillStyle = 'rgba(63,224,200,.18)'; x.beginPath(); x.arc(X, Y, 16, 0, 7); x.fill();
    x.fillStyle = '#3FE0C8'; x.strokeStyle = '#06131A'; x.lineWidth = 1.5; x.beginPath(); x.arc(X, Y, 10, 0, 7); x.fill(); x.stroke();
    x.save(); x.translate(X - 6.5, Y - 6.5); x.scale(13 / 24, 13 / 24); x.fillStyle = '#06131A'; x.fill(aqPath2D('maison'), 'evenodd'); x.restore();
    aqPts.push([X, Y, null]);
  }
  // The stations, the oldest first so that the newest lie on top; the selected one last.
  const pos = l.filter(s => s.lat != null).sort((a, b) => (a.id === aqSel) - (b.id === aqSel) || a.dernier - b.dernier);
  const boites = [];
  if (AQ.moi) boites.push([m.px(AQ.moi.lon) - 12, m.py(AQ.moi.lat) - 12, 24, 24]);
  pos.forEach(s => { const X = m.px(s.lon), Y = m.py(s.lat); aqBadge(x, X, Y, s, s.id === aqSel); aqPts.push([X, Y, s.id]); boites.push([X - 11, Y - 11, 22, 22]); });
  // Labels where they fit: the selected and the newest first.
  const libre = (b) => !boites.some(q => b[0] < q[0] + q[2] && b[0] + b[2] > q[0] && b[1] < q[1] + q[3] && b[1] + b[3] > q[1]);
  x.font = '600 12px system-ui'; x.textAlign = 'left'; x.textBaseline = 'middle';
  const etiq = (t, X, Y, couleur, force) => {
    const w = x.measureText(t).width + 4, b = [X + 13, Y - 8, w, 16];
    if (!force && !libre(b)) return;
    boites.push(b); x.lineWidth = 3; x.strokeStyle = m.osm ? 'rgba(255,255,255,.9)' : 'rgba(6,19,26,.92)'; x.strokeText(t, X + 15, Y);
    x.fillStyle = m.osm ? '#06131A' : couleur; x.fillText(t, X + 15, Y);
  };
  pos.slice().reverse().forEach(s => etiq(s.id, m.px(s.lon), m.py(s.lat), s.id === aqSel ? '#FFFFFF' : '#E8F0F7', s.id === aqSel));
  if (AQ.moi) etiq(AQ.moi.call || 'ma station', m.px(AQ.moi.lon), m.py(AQ.moi.lat), '#3FE0C8', true);
  x.textBaseline = 'alphabetic';
}
/** Zoom about the centre (buttons) or a point (wheel, pinch). */
function aqZoomEn(dz, mx, my) {
  const c = g('aq-carte'), r = c.getBoundingClientRect(), s = 256 * Math.pow(2, AQV.z);
  const px = mx == null ? r.width / 2 : mx, py = my == null ? r.height / 2 : my;
  const wx = AQV.cx + (px - r.width / 2) / s, wy = AQV.cy + (py - r.height / 2) / s;
  AQV.z = Math.max(1, Math.min(17, AQV.z + dz)); const s2 = 256 * Math.pow(2, AQV.z);
  AQV.cx = wx - (px - r.width / 2) / s2; AQV.cy = wy - (py - r.height / 2) / s2; AQV.libre = true;
  g('aq-suivre').classList.remove('on'); aqDessine();
}
function aqZoom(k) { aqZoomEn(k * 0.75); }
function aqSuit() { AQV.libre = false; g('aq-suivre').classList.add('on'); aqDessine(); }
(function () {
  const c = g('aq-carte'), doigts = new Map();
  let geste = null;
  const local = ev => { const r = c.getBoundingClientRect(); return [ev.clientX - r.left, ev.clientY - r.top]; };
  c.addEventListener('pointerdown', ev => {
    c.setPointerCapture(ev.pointerId); doigts.set(ev.pointerId, local(ev));
    const p = [...doigts.values()];
    if (p.length === 1) geste = { x: p[0][0], y: p[0][1], cx: AQV.cx, cy: AQV.cy, t: Date.now(), bouge: false };
    else if (p.length === 2) geste = { pince: Math.hypot(p[0][0] - p[1][0], p[0][1] - p[1][1]), z: AQV.z, bouge: true };
  });
  c.addEventListener('pointermove', ev => {
    if (!doigts.has(ev.pointerId) || !geste) return;
    doigts.set(ev.pointerId, local(ev));
    const p = [...doigts.values()];
    if (p.length === 1 && geste.x != null) {
      const dx = p[0][0] - geste.x, dy = p[0][1] - geste.y;
      if (!geste.bouge && Math.hypot(dx, dy) < 6) return;
      geste.bouge = true; c.style.cursor = 'grabbing';
      const s = 256 * Math.pow(2, AQV.z);
      AQV.cx = geste.cx - dx / s; AQV.cy = Math.max(0, Math.min(1, geste.cy - dy / s)); AQV.libre = true; g('aq-suivre').classList.remove('on'); aqDessine();
    } else if (p.length === 2 && geste.pince) {
      const d = Math.hypot(p[0][0] - p[1][0], p[0][1] - p[1][1]);
      aqZoomEn(geste.z + Math.log2(d / geste.pince) - AQV.z, (p[0][0] + p[1][0]) / 2, (p[0][1] + p[1][1]) / 2);
    }
  });
  const fin = ev => {
    if (!doigts.has(ev.pointerId)) return;
    const pt = doigts.get(ev.pointerId); doigts.delete(ev.pointerId); c.style.cursor = 'grab';
    if (geste && !geste.bouge && doigts.size === 0 && ev.type === 'pointerup') {
      // A tap: the nearest station within reach, else the card closed.
      let best = null, bd = (ev.pointerType === 'touch' ? 26 : 16) ** 2;
      aqPts.forEach(q => { const d = (q[0] - pt[0]) ** 2 + (q[1] - pt[1]) ** 2; if (d < bd) { bd = d; best = q; } });
      aqChoisit(best ? best[2] : null, false);
    }
    if (doigts.size === 0) geste = null;
    else { const p = [...doigts.values()]; geste = { x: p[0][0], y: p[0][1], cx: AQV.cx, cy: AQV.cy, bouge: true }; }
  };
  c.addEventListener('pointerup', fin); c.addEventListener('pointercancel', fin);
  c.addEventListener('wheel', ev => { ev.preventDefault(); const p = local(ev); aqZoomEn(ev.deltaY > 0 ? -0.35 : 0.35, p[0], p[1]); }, { passive: false });
  c.addEventListener('dblclick', () => aqSuit());
  window.addEventListener('resize', () => aqDessine());
})();
// ---- the figures
function aqSpark() {
  const c = g('aq-spark'), [x, w, h] = toile(c), l = (AQ && AQ.stats.parHeure) || [];
  x.clearRect(0, 0, w, h);
  const mx = Math.max(1, ...l), bw = w / Math.max(1, l.length);
  l.forEach((v, i) => { const bh = v ? Math.max(2, v / mx * (h - 2)) : 1; x.fillStyle = i === l.length - 1 ? '#3FE0C8' : 'rgba(63,224,200,.45)'; x.fillRect(i * bw + 0.5, h - bh, Math.max(1, bw - 1.5), bh); });
}
function aqStats() {
  const st = AQ.stats;
  txt('aq-jour', String(st.jour)); txt('aq-jour-s', st.paquets + ' paquets en ' + AQ.heures + ' h');
  txt('aq-sat', String(st.viaSat));
  const p = AQ.passages && AQ.passages[0];
  txt('aq-sat-s', p ? 'dernier passage : ' + p.sat + ' ' + hm(p.debut) : 'aucun passage entendu');
  txt('aq-loin', st.loinDirect ? String(st.loinDirect.km) + ' km' : '—'); txt('aq-loin-s', st.loinDirect ? st.loinDirect.call : AQ.moi ? 'aucune position en direct' : 'position de la station inconnue');
  txt('aq-ph', String(st.derniereHeure)); aqSpark();
}
async function aqTic() {
  if (!ouverte('aprs') || aqAppel) return;
  if (typeof mdSaute === 'function' && mdSaute('aprs-carte', 15000)) return;
  aqAppel = true;
  try { const j = await (await fetch('api/aprs/stations?heures=48')).json(); if (j && j.present) AQ = j; } catch (x) {} finally { aqAppel = false; aqVu = Date.now(); }
  if (!AQ) return;
  if (!cgTraits) { await cgChargeTraits(); }
  aqStats(); aqDessineListe(); aqFiche(); aqDessine();
}
// The filters, kept per browser.
function aqPuces() {
  g('aq-rf').setAttribute('aria-pressed', AQF.rf ? 'true' : 'false'); g('aq-viasat').setAttribute('aria-pressed', AQF.sat ? 'true' : 'false');
  document.querySelectorAll('#aq-periode button').forEach(b => b.setAttribute('aria-pressed', +b.dataset.h === AQF.periode ? 'true' : 'false'));
  g('aq-tri').value = AQF.tri; g('aq-genre').value = AQF.genre; g('aq-trace').value = String(AQF.trace);
}
function aqChange() { aqGarde(); aqPuces(); aqDessineListe(); aqDessine(); }
g('aq-rf').onclick = () => { AQF.rf = AQF.rf ? 0 : 1; aqChange(); };
g('aq-viasat').onclick = () => { AQF.sat = AQF.sat ? 0 : 1; aqChange(); };
document.querySelectorAll('#aq-periode button').forEach(b => { b.onclick = () => { AQF.periode = +b.dataset.h; aqChange(); }; });
g('aq-tri').onchange = () => { AQF.tri = v('aq-tri'); aqChange(); };
g('aq-genre').onchange = () => { AQF.genre = v('aq-genre'); aqChange(); };
g('aq-trace').onchange = () => { AQF.trace = +v('aq-trace'); aqChange(); };
g('aq-q').oninput = () => { AQF.q = g('aq-q').value.trim(); aqDessineListe(); aqDessine(); };
try { g('aq-osm').checked = localStorage.getItem('cg-osm') === '1'; } catch (x) {}
g('aq-osm').onchange = () => { try { localStorage.setItem('cg-osm', g('aq-osm').checked ? '1' : '0'); } catch (x) {} if (g('cg-osm')) g('cg-osm').checked = g('aq-osm').checked; aqDessine(); };
aqPuces();
// Fetched when the tab opens, then every 5 s while it is open.
setInterval(() => { const o = ouverte('aprs'); if (o && (!aqOuvert || Date.now() - aqVu > 5000)) { aqTic(); if (!aqOuvert) setTimeout(aqDessine, 50); } aqOuvert = o; }, 500);
"""
}
