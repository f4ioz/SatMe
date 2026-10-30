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
 * The « Sked » tab as a part of its own: the windows two stations share
 * (`api/sked`), each replayed on the map or on the Orbites globe
 * (`api/sked/trace`), with both skies. SatMe-PC's page ([PagePc]) and the
 * F4IOZ web site (module `site`) both put it in.
 *
 * What the host page gives the script: `g`, `v`, `txt`, `enc`, `esc`, `hm`,
 * `jourHeure`, `duree`, `polaire`, `utc`, `post`; `E` (the station:
 * `indicatif`, `locator`, `suivi`), `tous` and `chargeSatellites()`; the map
 * tiles (`qTuile`, `wyq`); [OrbitesJs]; and [VideoSkedJs]'s `kfFormats`,
 * `kfAnime`, `filmeSked` (the video, SatMe-PC's alone). `skedAgenda(i)`,
 * when the page has one, adds the agenda's button to each window;
 * `skedMoi()`, when it has one, adds « my » station to the routes' query
 * (`&moi=JN18FS`: the web site, where anyone is « me »).
 */
object OngletSked {

    /** The tab's markup (`#v-sked`). */
    const val HTML = """<div class="vue" id="v-sked" hidden>
<div class="grille2" id="k-grille">
  <div class="k-gauche">
    <div class="carte">
      <div class="t">Sked avec une autre station</div>
      <div class="ligne" style="margin-top:0"><input id="k-loc" placeholder="Son locator (IN80, FN31…)" style="text-transform:uppercase"></div>
      <div class="ligne"><select id="k-sat"></select></div>
      <div class="ligne"><span class="petit">Il voit au-dessus de</span><input id="k-min" value="0" style="max-width:60px"><span class="petit">°</span>
        <button class="primaire" onclick="calculeSked()">Chercher</button></div>
      <div class="petit aide" style="margin-top:6px">Les fenêtres des 48 h où vous voyez tous deux le satellite (une minute au moins), avec l'élévation maximale de chaque côté. ▶ les rejoue sur la carte.</div>
      <div class="etat" id="k-etat"></div>
    </div>
    <div class="carte k-fenetres">
      <table><thead><tr><th>Début</th><th>Fin</th><th>Durée</th><th>Moi</th><th>Lui</th><th></th></tr></thead><tbody id="k-liste"></tbody></table>
    </div>
  </div>
  <div class="k-droite">
    <div class="carte" id="k-vide"><div class="petit k-vide-t">Cherche les fenêtres communes avec une station : la première se rejoue ici, les deux ciels et la carte.</div></div>
    <div class="carte" id="k-anim" hidden>
      <div class="ligne" style="margin-top:0;flex-wrap:wrap">
        <span class="t" id="k-titre" style="margin:0"></span>
        <button id="k-joue" onclick="basculeSked()" style="min-width:44px">⏸</button>
        <input type="range" id="k-curseur" min="0" max="1000" value="0" style="flex:1;min-width:160px">
        <span id="k-heure" style="font-variant-numeric:tabular-nums;min-width:70px"></span>
        <span id="k-ensemble" class="petit"></span>
        <span class="bascule-c" role="group" aria-label="Rejouer sur"><button id="k-b-carte" onclick="vueSked('carte')" title="Le sked rejoué sur la carte">Carte</button><button id="k-b-globe" onclick="vueSked('globe')" title="Le sked rejoué sur le globe de l’onglet Orbites, avec ses rendus et ses réglages">Globe</button></span>
        <button id="k-video" onclick="videoSked()" title="Le sked rejoué en vidéo (MP4), à envoyer à l'autre station : sur la carte ou sur le globe, comme affiché (le globe avec son rendu, sa caméra et ses réglages)">Vidéo</button>
        <select id="k-video-format" style="flex:none;width:auto" aria-label="Format de la vidéo" title="Le format de la vidéo : l'écran (16:9) ou un téléphone debout (9:16) ; le globe animé en 1920×1080, 1280×720 ou 1080×1920" onchange="try { localStorage.setItem('sked-video-format', this.value); } catch (x) {}"><option value="paysage">16:9</option><option value="paysage720" hidden disabled>16:9 · 720p</option><option value="telephone">9:16</option></select>
        <label id="k-anime-l" class="petit" style="flex:none;white-space:nowrap" hidden title="La vidéo filmée par le navigateur sur le vrai globe de la page (relief, nuages, monuments, décor animé, le rendu et la caméra des Réglages du globe) au lieu d’être dessinée par SatMe : 24 s du passage et 3 s de la fenêtre. Gardez l’onglet visible pendant l’enregistrement ; SatMe-PC en fait ensuite un MP4."><input type="checkbox" id="k-anime"> Globe animé (comme à l’écran)</label>
        <span class="petit" id="k-video-etat"></span>
      </div>
      <details class="k-reglages" id="k-reglages" hidden><summary>Réglages du globe</summary><div id="k-reglages-c"></div></details>
      <div class="petit" id="k-globe-etat"></div>
      <div class="k-corps">
        <canvas id="k-carte" title="Glisser pour déplacer, molette pour zoomer, double-clic pour recadrer"></canvas>
        <canvas id="k-globe" hidden title="Glisser pour tourner, molette pour zoomer, double-clic pour recadrer" aria-label="Le sked rejoué sur le globe"></canvas>
        <div class="k-ciels">
          <div><div class="petit" id="k-lmoi">Moi</div><svg id="k-cmoi" viewBox="-112 -112 224 224"></svg></div>
          <div><div class="petit" id="k-llui">Lui</div><svg id="k-clui" viewBox="-112 -112 224 224"></svg></div>
        </div>
      </div>
      <div class="petit aide" style="margin-top:6px">En vert, la fenêtre où vous voyez tous deux le satellite ; la zone claire autour du satellite est la partie de la Terre qui le voit à cet instant.</div>
    </div>
  </div>
</div>
</div>"""

    /** Its styles. */
    const val CSS = """  #k-grille { grid-template-columns:minmax(380px,1fr) minmax(420px,1.7fr); }
  .k-fenetres { max-height:360px; overflow:auto; }
  .k-corps { display:flex; gap:12px; flex-wrap:wrap; margin-top:10px; }
  #k-carte, #k-globe { flex:3; min-width:320px; height:460px; border-radius:8px; cursor:grab; touch-action:none; }
  .k-reglages { margin-top:8px; font-size:var(--fs-s); }
  .k-reglages summary { cursor:pointer; color:var(--texte2, #9AA7B4); }
  #k-reglages-c { display:flex; flex-wrap:wrap; gap:6px 14px; align-items:center; margin-top:6px; }
  #k-reglages-c label { display:flex; align-items:center; gap:4px; }
  #k-reglages-c .k-montypes { flex-basis:100%; display:flex; flex-wrap:wrap; gap:2px 12px; }
  @media (max-width:900px) { #k-grille { grid-template-columns:minmax(0,1fr); } }
  @media (max-width:760px) { #k-carte, #k-globe { min-width:0; flex-basis:100%; height:360px; } .k-ciels { max-width:none; flex-direction:row; } .k-ciels > div { flex:1; min-width:0; } }
  .k-ciels { flex:1; min-width:180px; max-width:240px; display:flex; flex-direction:column; gap:6px; }
  .k-ciels svg { width:100%; display:block; }
  .k-vide-t { padding:32px 16px; text-align:center; color:var(--texte-3); font-size:var(--fs-m); }"""

    /** The windows, their replay on the map and on the globe, the globe's settings. */
    const val JS = """// ------------------------------------------------------------------ skeds
let satsSked = '';
async function prepareSked() {
  if (!tous.length) await chargeSatellites();
  const cle = tous.map(s => s.catnum).join();
  if (cle !== satsSked) {
    satsSked = cle;
    g('k-sat').innerHTML = tous.map(s => '<option value="' + s.catnum + '">' + esc(s.nom) + '</option>').join('');
    if (E && E.suivi) g('k-sat').value = String(E.suivi.catnum);
  }
  try { const l = localStorage.getItem('sked-loc'); if (l && !v('k-loc')) g('k-loc').value = l; } catch (x) {}
}
let K = [];
/* « My » station when the page says it (the web site: any locator, its own field), else the station's own. */
const skedIci = () => typeof skedMoi === 'function' ? skedMoi() : '';
async function calculeSked() {
  const loc = v('k-loc').toUpperCase(); if (!loc) return;
  try { localStorage.setItem('sked-loc', loc); } catch (x) {}
  txt('k-etat', 'calcul…');
  let j = null;
  try { j = await (await fetch('api/sked?catnum=' + g('k-sat').value + '&loc=' + enc(loc) + '&minEl=' + enc(v('k-min')) + skedIci())).json(); } catch (x) {}
  if (!j || !j.ok) { txt('k-etat', j && j.erreur ? j.erreur : 'échec'); return; }
  K = j.fenetres;
  txt('k-etat', K.length + ' fenêtre(s) commune(s)');
  g('k-liste').innerHTML = K.map((w, i) => '<tr><td>' + jourHeure(w.de) + '</td><td>' + hm(w.a) + '</td><td>' + duree(w.a - w.de) +
    '</td><td>' + w.elMoi + '° · az ' + w.azMoi + '°</td><td>' + w.elAutre + '° · az ' + w.azAutre + '°</td><td style="white-space:nowrap"><button title="Rejouer sur la carte" onclick="rejoueSked(' + i + ')">▶</button> ' +
    (typeof skedAgenda === 'function' ? '<button title="Mettre à l’agenda" onclick="skedAgenda(' + i + ')">📅</button>' : '') + '</td></tr>').join('') ||
    '<tr><td colspan="6" class="petit">aucune fenêtre commune en 48 h</td></tr>';
  if (K.length) rejoueSked(0); else { g('k-anim').hidden = true; g('k-vide').hidden = false; KT = null; }
}
// The sked replayed: both skies and the map, as on the phone.
let KT = null, kF = 0, kJoue = false, kAvant = 0, kId = 0;
const K_DUREE = 15000;   // one full replay, ms
async function rejoueSked(i) {
  const w = K[i];
  let j = null;
  try { j = await (await fetch('api/sked/trace?catnum=' + g('k-sat').value + '&loc=' + enc(v('k-loc')) + '&de=' + w.debutTrace + '&a=' + w.finTrace + skedIci())).json(); } catch (x) {}
  if (!j || !j.ok || !j.points.length) return;
  // Longitudes made continuous (from mine), so a track over the date line stays one line.
  let ref = j.moi.lon;
  j.points.forEach(p => { while (p[6] - ref > 180) p[6] -= 360; while (p[6] - ref < -180) p[6] += 360; ref = p[6]; });
  while (j.autre.lon - j.moi.lon > 180) j.autre.lon -= 360; while (j.autre.lon - j.moi.lon < -180) j.autre.lon += 360;
  KT = j; KT.w = w; KT.loc = v('k-loc').toUpperCase(); KT.nom = g('k-sat').selectedOptions[0].textContent; KT.id = ++kId;
  [...g('k-liste').rows].forEach((r, k) => r.classList.toggle('choisi', k === i));
  txt('k-titre', KT.nom + ' · ' + jourHeure(w.de) + ' → ' + hm(w.a));
  txt('k-lmoi', 'Moi' + (E && E.locator ? ' (' + E.locator + ')' : '') + ' · max ' + w.elMoi + '°');
  txt('k-llui', KT.loc + ' · max ' + w.elAutre + '°');
  g('k-anim').hidden = false; g('k-vide').hidden = true;
  kF = 0; kJoue = true; kAvant = 0; txt('k-joue', '⏸');
  majVueSked();
  requestAnimationFrame(boucleSked);
}
async function videoSked() {
  if (!KT) return;
  // « Globe animé »: the page's own globe filmed by the browser (VideoSkedJs).
  if (kfAnime()) { filmeSked(); return; }
  const w = KT.w;
  // As shown: the map, or the globe with the look, camera and settings chosen (the server draws them itself).
  let q = '&format=' + (g('k-video-format').value === 'telephone' ? 'telephone' : 'paysage');
  if (kVue === 'globe') q += '&vue=globe&style=' + enc(Orbites.styleActif()) + '&camera=' + enc(Orbites.cameraActive()) + '&detail=' + Orbites.detailActif() +
    '&villes=' + enc(Orbites.villesActives()) + '&etiquettes=' + enc(Orbites.etiquettesActives()) + '&monuments=' + enc(Orbites.monumentsActifs()) +
    '&montaille=' + Orbites.monumentsTailleActive() + '&traces=' + Orbites.tracesActives();
  const j = await post('api/sked/video?catnum=' + g('k-sat').value + '&loc=' + enc(KT.loc) + '&de=' + w.de + '&a=' + w.a + '&debutTrace=' + w.debutTrace + '&finTrace=' + w.finTrace + q);
  if (j && j.ok) suisVideoSked();
}
async function suisVideoSked() {
  let e = null;
  try { e = await (await fetch('api/sked/video')).json(); } catch (x) { return; }
  g('k-video').disabled = e.enCours;
  if (e.enCours) { txt('k-video-etat', 'vidéo ' + Math.round(e.progression * 100) + ' %'); setTimeout(suisVideoSked, 500); return; }
  if (e.erreur) { txt('k-video-etat', e.erreur); return; }
  if (e.nom) g('k-video-etat').innerHTML = '<a style="color:var(--cyan)" href="sked-video/' + encodeURIComponent(e.nom) + '">' + esc(e.nom) + '</a>';
}
function basculeSked() { kJoue = !kJoue; txt('k-joue', kJoue ? '⏸' : '▶'); if (kJoue) { kAvant = 0; if (kF >= 1) kF = 0; requestAnimationFrame(boucleSked); } }
function boucleSked(ts) {
  if (!kJoue || !KT) return;
  if (!g('v-sked').hidden) {
    if (kAvant) kF += (ts - kAvant) / K_DUREE;
    if (kF >= 1) kF = 0;
    dessineSked();
  }
  kAvant = ts;
  requestAnimationFrame(boucleSked);
}
g('k-curseur').oninput = () => { kF = (+g('k-curseur').value) / 1000; kJoue = false; txt('k-joue', '▶'); dessineSked(); };
/** The instant at [f] (0..1): time, my az/el, his az/el, lat, lon, altitude — interpolated. */
function instantSked(f) {
  const P = KT.points, t = P[0][0] + f * (P[P.length - 1][0] - P[0][0]);
  let k = 0; while (k < P.length - 2 && P[k + 1][0] <= t) k++;
  const a = P[k], b = P[Math.min(k + 1, P.length - 1)], u = b[0] > a[0] ? Math.max(0, Math.min(1, (t - a[0]) / (b[0] - a[0]))) : 0;
  const az = (x, y) => { let d = y - x; if (d > 180) d -= 360; if (d < -180) d += 360; return ((x + d * u) % 360 + 360) % 360; };
  const li = (x, y) => x + (y - x) * u;
  return [t, az(a[1], b[1]), li(a[2], b[2]), az(a[3], b[3]), li(a[4], b[4]), li(a[5], b[5]), li(a[6], b[6]), li(a[7], b[7])];
}
function cielSked(id, iAz, iEl, cour) {
  let s = '';
  for (const el of [0, 30, 60]) s += '<circle r="' + (100 * (1 - el / 90)) + '" fill="none" stroke="#22303B"/>';
  s += '<line x1="-100" y1="0" x2="100" y2="0" stroke="#22303B"/><line x1="0" y1="-100" x2="0" y2="100" stroke="#22303B"/>';
  for (const [t, x, y] of [['N', 0, -103], ['E', 104, 4], ['S', 0, 110], ['O', -104, 4]])
    s += '<text x="' + x + '" y="' + y + '" fill="#9AA7B4" font-size="8" text-anchor="middle">' + t + '</text>';
  const pts = KT.points.filter(p => p[iEl] >= 0), com = pts.filter(p => p[0] >= KT.w.de && p[0] <= KT.w.a);
  const ligne = (l, c, w, o) => l.length > 1 ? '<polyline fill="none" stroke="' + c + '" stroke-opacity="' + o + '" stroke-width="' + w + '" points="' +
    l.map(p => polaire(p[iAz], p[iEl]).map(x => x.toFixed(1)).join(',')).join(' ') + '"/>' : '';
  s += ligne(pts, '#3FE0C8', 1.4, .45) + ligne(com, '#5BD68A', 3, .9);
  if (pts.length) { const a = polaire(pts[0][iAz], pts[0][iEl]);
    s += '<text x="' + a[0].toFixed(1) + '" y="' + (a[1] - 4).toFixed(1) + '" fill="#3FE0C8" font-size="7" text-anchor="middle">AOS</text>'; }
  const az = cour[iAz], el = cour[iEl];
  if (el >= 0) { const p = polaire(az, el); s += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="5" fill="#FFB454" stroke="#06131A"/>'; }
  s += '<text x="-110" y="108" fill="' + (el >= 0 ? '#E8F0F7' : '#9AA7B4') + '" font-size="9">' + (el >= 0 ? 'az ' + Math.round(az) + '° · él ' + Math.round(el) + '°' : 'sous l’horizon (' + Math.round(el) + '°)') + '</text>';
  g(id).innerHTML = s;
}
function wxk(lon) { return (lon + 180) / 360; }
/** OpenStreetMap under canvas [c] for view [v] ({cx, cy, z}): the tiles drawn; the context and projections handed back. */
function fondCarte(c, v) {
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.fillStyle = '#AAD3DF'; x.fillRect(0, 0, w, h);
  const z = Math.max(0, Math.min(16, Math.floor(v.z))), ts = 256 * Math.pow(2, v.z - z), s = 256 * Math.pow(2, v.z);
  const n = 1 << z, ox = w / 2 - v.cx * s, oy = h / 2 - v.cy * s;
  for (let ty = Math.max(0, Math.floor(-oy / ts)); ty <= Math.min(n - 1, Math.floor((h - oy) / ts)); ty++)
    for (let tx = Math.floor(-ox / ts); tx <= Math.floor((w - ox) / ts); tx++) { const i = qTuile(z, tx, ty); if (i) x.drawImage(i, ox + tx * ts, oy + ty * ts, ts + 0.5, ts + 0.5); }
  return { x, w, h, px: lon => ox + wxk(lon) * s, py: lat => oy + wyq(lat) * s, boites: [] };
}
/** Drag, wheel and double-click on a map canvas. */
function attacheCarte(c, v, redessine, recadre, zMin) {
  let pres = null;
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); pres = { x: ev.clientX, y: ev.clientY, cx: v.cx, cy: v.cy }; c.style.cursor = 'grabbing'; });
  c.addEventListener('pointermove', ev => { if (!pres) return; const s = 256 * Math.pow(2, v.z);
    v.cx = pres.cx - (ev.clientX - pres.x) / s; v.cy = Math.max(0, Math.min(1, pres.cy - (ev.clientY - pres.y) / s)); v.libre = true; redessine(); });
  c.addEventListener('pointerup', () => { pres = null; c.style.cursor = 'grab'; });
  c.addEventListener('wheel', ev => { ev.preventDefault(); const r = c.getBoundingClientRect(), s = 256 * Math.pow(2, v.z);
    const mx = v.cx + (ev.clientX - r.left - r.width / 2) / s, my = v.cy + (ev.clientY - r.top - r.height / 2) / s;
    v.z = Math.max(zMin || 0, Math.min(12, v.z + (ev.deltaY > 0 ? -0.35 : 0.35))); const s2 = 256 * Math.pow(2, v.z);
    v.cx = mx - (ev.clientX - r.left - r.width / 2) / s2; v.cy = my - (ev.clientY - r.top - r.height / 2) / s2; v.libre = true; redessine(); }, { passive: false });
  c.addEventListener('dblclick', () => { v.libre = false; recadre(); redessine(); });
  window.addEventListener('resize', redessine);
}
/** The earth that sees a satellite at [alt] km over [lat, lon], shaded, a turn each side too. */
function empreinte(m, lat, lon, alt) {
  const fp = cercle(lat, lon, Math.acos(6371 / (6371 + Math.max(150, alt)))), bord = fp.slice(0, 91), x = m.x;
  for (const d of [-360, 0, 360]) {
    x.fillStyle = 'rgba(63,224,200,.16)'; x.beginPath(); fp.forEach((p, k) => k ? x.lineTo(m.px(p[1] + d), m.py(p[0])) : x.moveTo(m.px(p[1] + d), m.py(p[0]))); x.closePath(); x.fill();
    traceCarte(m, bord.map(p => [p[0], p[1] + d]), 'rgba(20,120,110,.8)', 1.2);
  }
}
function traceCarte(m, l, couleur, larg, tirets) {
  if (l.length < 2) return; const x = m.x;
  x.strokeStyle = couleur; x.lineWidth = larg; x.setLineDash(tirets || []);
  x.beginPath(); l.forEach((p, k) => k ? x.lineTo(m.px(p[1]), m.py(p[0])) : x.moveTo(m.px(p[1]), m.py(p[0]))); x.stroke(); x.setLineDash([]);
}
function pointCarte(m, lat, lon, couleur, r, nom) {
  const x = m.x;
  x.fillStyle = couleur; x.strokeStyle = '#06131A'; x.lineWidth = 1.5; x.beginPath(); x.arc(m.px(lon), m.py(lat), r, 0, 7); x.fill(); x.stroke();
  if (m.boites) m.boites.push({ x: m.px(lon) - r, y: m.py(lat) - r, w: 2 * r, h: 2 * r });
  if (!nom) return;
  x.font = 'bold 12px Drapeaux,system-ui'; x.textAlign = 'left'; x.lineWidth = 3; x.strokeStyle = 'rgba(255,255,255,.85)';
  if (m.boites) m.boites.push({ x: m.px(lon) + r + 2, y: m.py(lat) - 18, w: x.measureText(nom).width + 2, h: 16 });
  x.strokeText(nom, m.px(lon) + r + 3, m.py(lat) - 5); x.fillStyle = '#06131A'; x.fillText(nom, m.px(lon) + r + 3, m.py(lat) - 5);
}
const KV = { cx: 0.5, cy: 0.35, z: 3 };
function cadreSked() {
  const c = g('k-carte'); if (!c.clientWidth || !KT) return;
  const pts = KT.points.filter(p => p[2] >= 0 || p[4] >= 0).map(p => [p[5], p[6]]);
  pts.push([KT.moi.lat, KT.moi.lon], [KT.autre.lat, KT.autre.lon]);
  const xs = pts.map(p => wxk(p[1])), ys = pts.map(p => wyq(p[0]));
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  KV.cx = (x0 + x1) / 2; KV.cy = (y0 + y1) / 2;
  KV.z = Math.max(1, Math.min(8, Math.log2(Math.min(c.clientWidth / 256 / Math.max(x1 - x0, 0.005), c.clientHeight / 256 / Math.max(y1 - y0, 0.005)) * 0.8)));
}
/** The points [lat, lon] at an angular radius [rho] (rad) around [lat0, lon0]: the footprint. */
function cercle(lat0, lon0, rho) {
  const f0 = lat0 * Math.PI / 180, out = [];
  for (let b = 0; b <= 360; b += 4) {
    const br = b * Math.PI / 180;
    const f = Math.asin(Math.sin(f0) * Math.cos(rho) + Math.cos(f0) * Math.sin(rho) * Math.cos(br));
    const dl = Math.atan2(Math.sin(br) * Math.sin(rho) * Math.cos(f0), Math.cos(rho) - Math.sin(f0) * Math.sin(f));
    let lon = lon0 + dl * 180 / Math.PI;
    // Continuous from the previous point: a footprint over a pole goes round the whole map.
    if (out.length) { const pr = out[out.length - 1][1]; while (lon - pr > 180) lon -= 360; while (lon - pr < -180) lon += 360; }
    out.push([f * 180 / Math.PI, lon]);
  }
  // Round the pole: closed along the map's top (or bottom) edge.
  const fin = out[out.length - 1][1], debut = out[0][1];
  if (Math.abs(fin - debut) > 180) { const pole = lat0 >= 0 ? 85 : -85; out.push([pole, fin], [pole, debut]); }
  return out;
}
function dessineSked() {
  if (!KT || g('v-sked').hidden) return;
  const cour = instantSked(kF);
  if (document.activeElement !== g('k-curseur')) g('k-curseur').value = Math.round(kF * 1000);
  txt('k-heure', hm(cour[0], true) + (utc() ? ' UTC' : ''));
  const moi = cour[2] >= 0, lui = cour[4] >= 0;
  g('k-ensemble').innerHTML = moi && lui ? '<b style="color:#5BD68A">vous le voyez tous deux</b>' : moi ? 'vous seul le voyez' : lui ? KT.loc + ' seul le voit' : 'personne ne le voit';
  cielSked('k-cmoi', 1, 2, cour); cielSked('k-clui', 3, 4, cour);
  if (kVue === 'globe') return;   // the globe draws itself, at the replay's time
  // The map.
  const m = fondCarte(g('k-carte'), KV), x = m.x;
  empreinte(m, cour[5], cour[6], cour[7]);
  // The ground track, the common window in green, the part already flown darker.
  const P = KT.points.map(p => [p[5], p[6], p[0]]);
  // As marked as « Traces » asks (shared with the globes): wider and more opaque, a light halo under the green.
  const kt = Orbites.tracesActives() / 100, ht = kt < .05 ? 0 : 1 + 2 * kt;
  traceCarte(m, P, 'rgba(30,40,55,' + (.4 + .45 * kt).toFixed(2) + ')', 1 + 2 * kt, [5, 4]);
  if (ht) traceCarte(m, P.filter(p => p[2] >= KT.w.de && p[2] <= KT.w.a), 'rgba(255,255,255,' + (.75 * kt).toFixed(2) + ')', 3 + 3 * kt + 2 * ht);
  traceCarte(m, P.filter(p => p[2] >= KT.w.de && p[2] <= KT.w.a), '#2E9E5B', 3 + 3 * kt);
  traceCarte(m, P.filter(p => p[2] <= cour[0]).concat([[cour[5], cour[6]]]), '#0B6E63', 1.5 + 2.5 * kt);
  // Each station, its line to the satellite while it sees it.
  const station = (st, vu, couleur, nom) => {
    if (vu) { x.strokeStyle = couleur; x.lineWidth = 1.5; x.beginPath(); x.moveTo(m.px(st.lon), m.py(st.lat)); x.lineTo(m.px(cour[6]), m.py(cour[5])); x.stroke(); }
    pointCarte(m, st.lat, st.lon, couleur, 6, nom);
  };
  station(KT.moi, moi, '#FFB454', E && E.indicatif ? E.indicatif : 'Moi');
  station(KT.autre, lui, '#E86FA8', KT.loc);
  pointCarte(m, cour[5], cour[6], moi && lui ? '#5BD68A' : '#3FE0C8', 7, KT.nom);
}
attacheCarte(g('k-carte'), KV, dessineSked, cadreSked, 1);
// The replay on the map or on the globe (the Orbites tab's: its looks and settings, kept with it), as last chosen.
let kVue = 'carte';
try { if (localStorage.getItem('sked-vue') === 'globe' && !window.SATME_TELEPHONE) kVue = 'globe'; } catch (x) {}
if (window.SATME_TELEPHONE) g('k-b-globe').hidden = true;
try { if (localStorage.getItem('sked-video-format') === 'telephone') g('k-video-format').value = 'telephone'; } catch (x) {}
if (window.SATME_TELEPHONE) g('k-video-format').hidden = true;
function vueSked(q) {
  kVue = q;
  try { localStorage.setItem('sked-vue', q); } catch (x) {}
  majVueSked();
}
async function majVueSked() {
  const globe = kVue === 'globe';
  g('k-b-carte').classList.toggle('actif', !globe); g('k-b-globe').classList.toggle('actif', globe);
  g('k-carte').hidden = globe; g('k-globe').hidden = !globe; g('k-reglages').hidden = !globe;
  g('k-anime-l').hidden = !globe; kfFormats();
  txt('k-globe-etat', '');
  if (!globe) { cadreSked(); dessineSked(); return; }
  if (!KT) return;
  reglagesGlobeSked();
  txt('k-globe-etat', 'Chargement du globe…');
  const ok = await Orbites.sked(g('k-globe'), donneesGlobeSked());
  if (kVue !== 'globe') return;
  if (!ok) { kVue = 'carte'; majVueSked(); txt('k-globe-etat', 'Le globe n’a pas pu se charger : le sked est rejoué sur la carte.'); return; }
  txt('k-globe-etat', '');
  dessineSked();
}
/** The window as the globe takes it: the track [t, lat, lon, alt], both stations, the replay's clock. */
function donneesGlobeSked() {
  return { id: 'sked:' + KT.id, sat: KT.nom, points: KT.points.map(p => [p[0], p[5], p[6], p[7]]), de: KT.w.de, a: KT.w.a,
    moi: { lat: KT.moi.lat, lon: KT.moi.lon, indicatif: E && E.indicatif ? E.indicatif : 'Moi', locator: E && E.locator ? E.locator : '' },
    autre: { lat: KT.autre.lat, lon: KT.autre.lon, locator: KT.loc }, horloge: () => KT ? instantSked(kF)[0] : Date.now() };
}
/** The globe's settings, the Orbites tab's own (shared with it): look, camera, details, cities, names, landmarks. */
function reglagesGlobeSked() {
  const box = g('k-reglages-c'); box.innerHTML = '';
  const choix = (lib, cls, l, val, change, titre) => {
    const lab = document.createElement('label'), s = document.createElement('select');
    if (cls) s.className = cls; if (titre) lab.title = titre;
    l.forEach(c => { const o = document.createElement('option'); o.value = c[0]; o.textContent = c[1]; s.appendChild(o); });
    s.value = String(val); s.onchange = () => change(s.value);
    lab.append(document.createTextNode(lib + ' '), s); box.appendChild(lab);
  };
  choix('Rendu', 'ob-style-choix', Orbites.styles(), Orbites.styleActif(), q => Orbites.style(q));
  choix('Caméra', 'ob-cam-choix', [['espace', 'les deux stations'], ['poursuite', 'poursuite du satellite'], ['sol', 'depuis ma station'], ['satellite', 'vue du satellite']], Orbites.cameraActive(),
    q => { Orbites.camera(q); Orbites.skedRecadre(); }, 'Glisser le globe le libère ; double-clic : le recadrer');
  choix('Détails', '', [['0', 'simples'], ['1', 'normaux'], ['2', 'riches']], Orbites.detailActif(), q => Orbites.detail(+q), 'Villes, montagnes, monuments et décor du dessin animé');
  choix('Villes', '', [['toutes', 'toutes'], ['capitales', 'capitales'], ['aucune', 'aucune']], Orbites.villesActives(), q => Orbites.villes(q));
  choix('Noms', '', [['nom', 'nom'], ['drapeau', 'drapeau'], ['nomdrapeau', 'nom et drapeau']], Orbites.etiquettesActives(), q => Orbites.etiquettes(q));
  choix('Monuments', 'ob-mon-choix', [['aucun', 'aucun'], ['principaux', 'principaux'], ['tous', 'tous']], Orbites.monumentsActifs(), q => Orbites.monuments(q));
  choix('Taille', 'ob-montaille-choix', [['0.7', 'petite'], ['1', 'normale'], ['1.6', 'grande'], ['2.4', 'très grande']], Orbites.monumentsTailleActive(), q => Orbites.monumentsTaille(+q),
    'La taille des monuments (plus grands : lisibles de loin)');
  box.appendChild(rangeTraces());
  box.appendChild(rangeTransparence());
  const ty = document.createElement('div'); ty.className = 'k-montypes'; box.appendChild(ty); Orbites.typesMonuments(ty);
}
/** « Transparence » 0 (opaque) … 80 %: the globe lets the orbits and satellites behind the Earth show; shared by the globes. */
function rangeTransparence() {
  const l = document.createElement('label'), r = document.createElement('input');
  r.type = 'range'; r.min = 0; r.max = 80; r.step = 5; r.className = 'ob-transparence-choix'; r.value = String(Orbites.transparenceActive());
  r.setAttribute('aria-label', 'Transparence du globe : opaque à très transparent'); l.title = 'Le globe transparent : ce qui est derrière la Terre se voit (à gauche opaque)';
  r.oninput = () => Orbites.transparence(+r.value);
  l.append(document.createTextNode('Transparence '), r); return l;
}
/** « Traces » 0 (discreet) … 100 (very marked): the ground tracks, shared by the globes and the maps; the Sked's map follows. */
function rangeTraces() {
  const l = document.createElement('label'), r = document.createElement('input');
  r.type = 'range'; r.min = 0; r.max = 100; r.step = 5; r.className = 'ob-traces-choix'; r.value = String(Orbites.tracesActives());
  r.setAttribute('aria-label', 'Traces au sol : discrètes à très marquées'); l.title = 'Les traces au sol : de discrètes (à gauche) à très marquées';
  r.oninput = () => { Orbites.traces(+r.value); dessineSked(); };
  l.append(document.createTextNode('Traces '), r); return l;
}"""
}
