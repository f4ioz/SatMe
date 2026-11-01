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
 * Télémesure › « Signal »: the digital receivers seen as a modem scope —
 * the constellation (BPSK), the eye and the tones' histogram (FSK, AFSK,
 * GMSK, G3RUH), the bit stream with its sync words and the last good frame
 * marked. Read from `GET api/telemesure/visu` (the receivers' taps, see
 * fr.f4ioz.satcombo.visu.SondeSignal) about four times a second while the
 * panel is on screen, nothing otherwise; « Figer » stops the readings.
 *
 * Drawing: one render queue for the three canvases (a frame asked only
 * when one is dirty), each canvas at its on-screen size (device pixels,
 * ratio capped at 2), the axes and grids drawn once into an offscreen cache
 * per size and range, a reading redrawn only when its sequence changed,
 * text written only when it differs. Every buffer is bounded (the server's
 * rings; the page keeps the last reading only).
 *
 * [CODE], pure (no page, tested by node in VisuSignalJsTest): base64 to
 * bytes, a group of bits' value, the bytes' alignment on the last sync
 * word, each bit's mark, the bit rows' HTML, the figures line.
 * [PAGE], the page's side. Plain constants: no Kotlin template in here, and
 * the JS uses no template literals.
 */
object VisuSignalJs {

    const val CSS = """
  #tl-p-sig > .carte { height:100%; box-sizing:border-box; display:flex; flex-direction:column; margin:0; min-height:0; }
  .vs-tete { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
  .vs-src { display:flex; gap:4px; flex-wrap:wrap; }
  .vs-src button { padding:2px 9px; font-size:var(--fs-s); border-radius:12px; background:var(--s3); border:1px solid var(--ligne); color:var(--texte-2); }
  .vs-src button.actif { background:rgba(63,224,200,.14); border-color:var(--rx); color:var(--rx); }
  .vs-src button i { display:inline-block; width:7px; height:7px; border-radius:50%; margin-right:5px; background:var(--texte-3); vertical-align:1px; }
  .vs-src button i.vif { background:var(--ok); }
  .vs-grille { flex:1; min-height:0; display:grid; grid-template-columns:minmax(300px,1fr) minmax(380px,1.15fr); gap:12px; margin-top:8px; }
  .vs-vues { display:flex; flex-direction:column; gap:8px; min-height:0; }
  .vs-f { flex:1 1 0; min-height:120px; margin:0; display:flex; flex-direction:column; background:var(--creux); border:1px solid var(--ligne); border-radius:var(--r2); position:relative; }
  .vs-f[hidden] { display:none; }
  .vs-f canvas { flex:1; min-height:0; width:100%; display:block; }
  .vs-f figcaption { position:absolute; left:8px; top:5px; font-size:var(--fs-xs); font-weight:700; letter-spacing:.6px; text-transform:uppercase; color:var(--texte-3); pointer-events:none; }
  .vs-droite { display:flex; flex-direction:column; min-height:0; }
  .vs-bits { flex:1; min-height:0; overflow:auto; background:var(--creux); border:1px solid var(--ligne); border-radius:var(--r2); padding:6px 8px;
    font-family:var(--mono); font-size:11.5px; line-height:1.55; color:var(--texte-2); white-space:pre; }
  .vs-l { display:flex; gap:12px; }
  .vs-l .n { color:var(--texte-3); min-width:7ch; text-align:right; }
  .vs-l .b i { font-style:normal; }
  .vs-l .b i.s { color:#111; background:var(--warn); border-radius:2px; }
  .vs-l .b i.t { color:var(--texte); background:rgba(63,224,200,.16); }
  .vs-l .h { color:var(--rx); }
  .vs-l .a { color:var(--texte-3); }
  .vs-leg { display:flex; gap:12px; flex-wrap:wrap; font-size:var(--fs-xs); color:var(--texte-2); margin:6px 0 4px; }
  .vs-leg i { display:inline-block; width:12px; height:9px; border-radius:2px; margin-right:4px; vertical-align:-1px; }
  .vs-vide { position:absolute; inset:0; display:grid; place-items:center; padding:20px; text-align:center; color:var(--texte-3); font-size:var(--fs-s); pointer-events:none; }
  .vs-vide[hidden] { display:none; }
"""

    const val HTML = """
  <div class="st-pan" id="tl-p-sig">
  <div class="carte">
    <div class="vs-tete"><div class="t" style="margin:0">Signal <span id="vs-pastille" class="pastille coupe">fermé</span></div>
      <div class="vs-src" id="vs-src"></div>
      <span class="h-esp"></span>
      <button id="vs-essai" onclick="vsEssai()" title="Un signal fabriqué passé dans le décodeur d’essai de cette source, au rythme réel tant que ce panneau le regarde ; rien n’est gardé">Essai</button>
      <button id="vs-fige" onclick="vsFige()" title="Arrêter les lectures et garder l’image telle qu’elle est">❚❚ Figer</button></div>
    <div class="paire" id="vs-infos" style="margin-top:6px"></div>
    <div class="vs-grille">
      <div class="vs-vues">
        <figure class="vs-f" id="vs-f-const" hidden><figcaption>Constellation</figcaption><canvas id="vs-const"></canvas></figure>
        <figure class="vs-f" id="vs-f-oeil" hidden><figcaption>Diagramme de l’œil</figcaption><canvas id="vs-oeil"></canvas></figure>
        <figure class="vs-f" id="vs-f-tons" hidden><figcaption id="vs-tons-t">Tons</figcaption><canvas id="vs-tons"></canvas></figure>
        <figure class="vs-f" id="vs-f-rien"><div class="vs-vide" id="vs-vide">Aucun décodeur numérique ne reçoit. Allume-en un (Paquets, SSDV en BLU, BPSK) ou lance « Essai » : les vues suivent le décodeur actif.</div></figure>
      </div>
      <div class="vs-droite">
        <div class="vs-leg"><b style="color:var(--texte)">Train binaire</b><span id="vs-lecture"></span><span><i style="background:var(--warn)"></i>mot de synchro</span><span><i style="background:rgba(63,224,200,.35)"></i><span id="vs-trame-t">dernière trame juste</span></span></div>
        <div class="vs-bits" id="vs-bits"></div>
        <div class="petit aide">Les vues suivent le décodeur qui reçoit (Auto), ou la source choisie. BPSK (FUNcube, balise QO-100) : la constellation après la boucle de Costas, deux nuages sur l’axe I à ±1, l’axe Q comme seuil de décision ; MER et EVM mesurés sur les 1024 derniers symboles. FSK, GMSK, G3RUH, AFSK : le diagramme de l’œil (la sortie du discriminateur ou l’écart entre les deux filtres de tons, sur deux bits, décision au milieu), et l’histogramme des tons (les niveaux aux instants de décision, ou les tons mesurés au milieu des bits) : deux bosses, leurs fréquences et leur écart. Le débit mesuré vient des instants de décision de l’horloge. Les bits sont ceux que lit le décodeur de trames, groupés en octets calés sur le dernier mot de synchro (HDLC 7E, GENESIS BF35, SSDV 55 66, Geoscan, Phase 3). Rien n’est calculé quand personne ne regarde. Réception seulement.</div>
      </div>
    </div>
  </div>
  </div>
"""

    const val CODE = """
/** base64 → bytes. */
function vsOctets(s) {
  const b = atob(s || ''), u = new Uint8Array(b.length);
  for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
  return u;
}
/** A signed byte. */
function vsS(u) { return u > 127 ? u - 256 : u; }
/** A group of bits' value ('0'/'1' string), least significant first or not. */
function vsValeur(s, lsb) {
  let v = 0;
  for (let k = 0; k < s.length; k++) if (s.charCodeAt(k) === 49) v |= lsb ? 1 << k : 1 << (s.length - 1 - k);
  return v;
}
/** Where groups start (mod [groupe]): just after the last sync word, the bytes after it whole. */
function vsCalage(premier, reperes, groupe) {
  const c = reperes && reperes.length ? reperes[reperes.length - 1][0] + reperes[reperes.length - 1][1] : premier;
  return ((c % groupe) + groupe) % groupe;
}
/** Each bit's mark: 1 in a sync word, 2 in the last good frame, 0 else. */
function vsMarques(n, premier, reperes, trame) {
  const m = new Uint8Array(n);
  if (trame) for (let i = Math.max(0, trame[0] - premier); i < Math.min(n, trame[0] + trame[1] - premier); i++) m[i] = 2;
  for (const r of reperes || []) for (let i = Math.max(0, r[0] - premier); i < Math.min(n, r[0] + r[1] - premier); i++) m[i] = 1;
  return m;
}
function vsEsc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
/**
 * The bit rows: [par] groups a row, groups aligned on the last sync word;
 * each row its first bit's number, the bits (marks as runs), the values in
 * hex and as text (bytes only). Oldest first.
 */
function vsBitsHtml(v, par) {
  const bits = v.bits || '', n = bits.length, g = Math.max(1, v.groupe || 8), premier = v.premier || 0;
  if (!n) return '';
  const m = vsMarques(n, premier, v.reperes, v.trame), cal = vsCalage(premier, v.reperes, g), ligne = g * par;
  // The first row starts on a group boundary at or before the first bit.
  let debut = premier - ((((premier - cal) % ligne) + ligne) % ligne);
  const h = [];
  for (; debut < premier + n; debut += ligne) {
    let b = '', hx = '', tx = '';
    for (let q = 0; q < par; q++) {
      const a = debut + q * g;
      let grp = '', cour = -1, run = '';
      for (let k = 0; k < g; k++) {
        const i = a + k - premier;
        const c = i >= 0 && i < n ? bits[i] : ' ', mk = i >= 0 && i < n ? m[i] : 0;
        if (mk !== cour) { if (run) grp += cour ? '<i class="' + (cour === 1 ? 's' : 't') + '">' + run + '</i>' : run; run = ''; cour = mk; }
        run += c;
      }
      if (run) grp += cour ? '<i class="' + (cour === 1 ? 's' : 't') + '">' + run + '</i>' : run;
      b += (q ? ' ' : '') + grp;
      const s = a - premier >= 0 && a - premier + g <= n ? bits.substr(a - premier, g) : null;
      const val = s === null ? -1 : vsValeur(s, v.lsb);
      hx += (q ? ' ' : '') + (val < 0 ? (g > 4 ? '··' : '·') : g === 8 ? (val < 16 ? '0' : '') + val.toString(16).toUpperCase() : String(val).padStart(2, ' '));
      tx += val < 0 || g !== 8 ? '' : val >= 32 && val < 127 ? String.fromCharCode(val) : '·';
    }
    h.push('<div class="vs-l"><span class="n">' + Math.max(0, debut) + '</span><span class="b">' + b + '</span><span class="h">' + hx + '</span>' +
      (g === 8 ? '<span class="a">' + vsEsc(tx) + '</span>' : '') + '</div>');
  }
  return h.join('');
}
/** A number in French (decimal comma), [d] decimals. */
function vsNb(x, d) { return Number(x).toFixed(d || 0).replace('.', ',').replace(/\B(?=(\d{3})+(?!\d))/g, ' '); }
/** A frequency or level: Hz (kHz from 10 kHz on), or the unit given. */
function vsHz(x, u) {
  if (u === 'FS') return vsNb(x, 3) + ' FS';
  return Math.abs(x) >= 10000 ? vsNb(x / 1000, 2) + ' kHz' : vsNb(x, 0) + ' Hz';
}
/** The figures line: [value, label, emphasis] each. */
function vsInfos(v) {
  const l = [];
  l.push([v.debit >= 100 ? vsNb(v.debit, 0) : vsNb(v.debit, 2), 'bit/s nominal', '']);
  if (v.debitMesure != null) l.push([vsNb(v.debitMesure, v.debit >= 100 ? 1 : 2) + ' (' + (v.ppm > 0 ? '+' : '') + vsNb(v.ppm, 0) + ' ppm)', 'mesuré', Math.abs(v.ppm) < 2000 ? 'cyan' : '']);
  const q = v.qualite || {};
  if (q.mer != null) l.push([vsNb(q.mer, 1) + ' dB', 'MER', q.mer >= 10 ? 'cyan' : '']);
  if (q.evm != null) l.push([vsNb(q.evm, 1) + ' %', 'EVM', '']);
  if (q.ouverture != null) l.push([q.ouverture + ' %', 'ouverture de l’œil', q.ouverture >= 30 ? 'cyan' : '']);
  if (q.q != null) l.push([vsNb(q.q, 1) + ' dB', 'facteur Q', '']);
  const t = v.tons;
  if (t && t.ecart != null) {
    const u = t.genre === 'niveaux' ? v.unite : 'Hz';
    l.push([vsHz(t.bas, u) + ' / ' + vsHz(t.haut, u), t.genre === 'niveaux' ? 'niveaux bas / haut' : 'tons bas / haut', 'cyan']);
    l.push([vsHz(t.ecart, u), 'écart', '']);
  }
  return l;
}
/** Which figures a reading shows. */
function vsFigures(v) {
  const s = v && v.vues ? v.vues : [];
  return { c: s.indexOf('CONSTELLATION') >= 0, o: s.indexOf('OEIL') >= 0, t: s.indexOf('TONS') >= 0 };
}
/** A tidy step for an axis over [etendue], about [n] ticks. */
function vsPas(etendue, n) {
  const brut = etendue / Math.max(1, n), p = Math.pow(10, Math.floor(Math.log10(brut))), r = brut / p;
  return (r < 1.5 ? 1 : r < 3.5 ? 2 : r < 7.5 ? 5 : 10) * p;
}
"""

    const val PAGE = """
// ---- Télémesure › Signal: constellation, eye, tones, bits of the digital receivers (receive only)
const VS = { src: 'auto', fige: false, v: null, seq: -1, j: null, t: 0, srcVu: '', bitsVu: '', infosVu: '', choisi: '' };
function vsVisible() { const p = g('tl-p-sig'); return !!p && !g('v-tlm').hidden && !p.hidden && !document.hidden; }
async function vsTic() {
  clearTimeout(VS.t);
  if (vsVisible() && !VS.fige) {
    let j = null;
    try { const r = await fetch('api/telemesure/visu?src=' + encodeURIComponent(VS.src)); if (r.ok) j = await r.json(); } catch (x) {}
    if (j && !VS.fige) vsRecoit(j);
  }
  VS.t = setTimeout(vsTic, vsVisible() ? 250 : 1000);
}
function vsSrc(k) { VS.src = k; VS.seq = -1; VS.srcVu = ''; vsTic(); }
function vsFige() {
  VS.fige = !VS.fige;
  txt('vs-fige', VS.fige ? '▶ Reprendre' : '❚❚ Figer');
  vsPastille();
  if (!VS.fige) vsTic();
}
function vsEssai() {
  const k = VS.src === 'auto' ? VS.choisi || 'paquets' : VS.src;
  const u = { paquets: 'paquets/essai', genesis: 'genesis/essai', funcube: 'essai?profil=funcube', balise: 'essai?profil=qo100' }[k];
  if (u) tlmPost(u);
}
function vsPastille() {
  const p = g('vs-pastille'), v = VS.v;
  const t = VS.fige ? 'figé' : !v ? 'rien reçu' : v.depuis >= 0 && v.depuis < 2000 ? (v.essai ? 'essai en cours' : 'reçoit') : 'à l’arrêt';
  if (p.textContent !== t) p.textContent = t;
  const c = 'pastille ' + (VS.fige || (v && v.depuis >= 0 && v.depuis < 2000) ? 'marche' : 'coupe');
  if (p.className !== c) p.className = c;
}
function vsRecoit(j) {
  VS.j = j; VS.choisi = j.choisi || '';
  // The sources' buttons: rebuilt only when their list or liveliness changes.
  const l = j.sources || [], cle = VS.src + '|' + VS.choisi + '|' + l.map(s => s.cle + (s.depuis >= 0 && s.depuis < 2000 ? '+' : '')).join(',');
  if (cle !== VS.srcVu) {
    VS.srcVu = cle;
    g('vs-src').innerHTML = '<button class="' + (VS.src === 'auto' ? 'actif' : '') + '" onclick="vsSrc(\'auto\')" title="Le décodeur qui a reçu en dernier">Auto' + (VS.src === 'auto' && VS.choisi ? ' · ' + esc((l.find(s => s.cle === VS.choisi) || {}).nom || '') : '') + '</button>' +
      l.map(s => '<button class="' + (VS.src === s.cle ? 'actif' : '') + '" onclick="vsSrc(\'' + s.cle + '\')"><i class="' + (s.depuis >= 0 && s.depuis < 2000 ? 'vif' : '') + '"></i>' + esc(s.nom) + '</button>').join('');
  }
  const k = VS.src === 'auto' ? VS.choisi : VS.src;
  g('vs-essai').disabled = !{ paquets: 1, genesis: 1, funcube: 1, balise: 1 }[k];
  const v = j.visu || null;
  if (!v) { if (VS.v) { VS.v = null; VS.seq = -1; vsMontre(); } vsPastille(); return; }
  if (v.seq === VS.seq && VS.v) { VS.v.depuis = v.depuis; vsPastille(); return; }
  VS.seq = v.seq;
  // Decoded once a reading: the clouds as typed arrays, bounded by the server's rings.
  v._p = v.points ? vsOctets(v.points) : null;
  v._o = v.oeil ? vsOctets(v.oeil) : null;
  VS.v = v;
  vsMontre();
  vsPastille();
}
/** The figures this reading has, the line of figures, the bits; the canvases queued. */
function vsMontre() {
  const v = VS.v, f = vsFigures(v);
  const montre = (id, on) => { const e = g(id); if (e.hidden === on) e.hidden = !on; };
  montre('vs-f-const', f.c); montre('vs-f-oeil', f.o); montre('vs-f-tons', f.t); montre('vs-f-rien', !v);
  const it = (x, l, c) => '<span><span class="val' + (c ? ' ' + c : '') + '">' + x + '</span> <span class="petit">' + l + '</span></span>';
  const inf = v ? '<span><b>' + esc(v.nom) + '</b>' + (v.essai ? ' <span class="tlm-b essai">essai</span>' : '') + '</span>' + vsInfos(v).map(a => it(a[0], a[1], a[2])).join('') : '';
  if (inf !== VS.infosVu) { VS.infosVu = inf; g('vs-infos').innerHTML = inf; }
  txt('vs-lecture', v && v.lecture ? 'lecture : ' + v.lecture : '');
  txt('vs-tons-t', v && v.tons ? (v.tons.genre === 'niveaux' ? 'Histogramme des niveaux · ' + (v.unite === 'FS' ? 'pleine échelle' : 'Hz, écart à la porteuse suivie') :
    (v.tons.genre === 'mesures' ? 'Tons mesurés aux décisions' : 'Spectre des tons') + ' · Hz dans le son') : 'Tons');
  txt('vs-trame-t', v && v.trame ? v.trame[2] + ' (il y a ' + vsNb(Math.max(0, (Date.now() - v.trame[3]) / 1000), 0) + ' s)' : 'dernière trame juste');
  const bx = g('vs-bits'), bh = v && v.bits ? vsBitsHtml(v, v.groupe === 8 ? 4 : 6) : '';
  if (bh !== VS.bitsVu) {
    // At the bottom: it follows the newest bits; scrolled up: it stays where the reader is.
    const bas = bx.scrollHeight - bx.scrollTop - bx.clientHeight < 30;
    VS.bitsVu = bh; bx.innerHTML = bh;
    if (bas) bx.scrollTop = bx.scrollHeight;
  }
  if (f.c) vsSale('c'); if (f.o) vsSale('o'); if (f.t) vsSale('t');
}

// One render queue for the three canvases: a frame asked only when one is dirty.
const VS_SALES = new Set();
let vsRaf = 0;
function vsSale(k) { VS_SALES.add(k); if (!vsRaf) vsRaf = requestAnimationFrame(vsDessine); }
function vsDessine() {
  vsRaf = 0;
  const l = Array.from(VS_SALES); VS_SALES.clear();
  if (!VS.v || !vsVisible()) return;
  for (const k of l) { if (k === 'c') vsConst(); else if (k === 'o') vsOeil(); else if (k === 't') vsTons(); }
}
// The theme's tokens, read once (and again when the page's colours change).
let VS_J = null;
function vsJetons() {
  if (VS_J) return VS_J;
  const s = getComputedStyle(document.documentElement), v = n => s.getPropertyValue(n).trim();
  VS_J = { rx: v('--rx') || '#3FE0C8', t2: v('--texte-2') || '#9AA7B4', t3: v('--texte-3') || '#6B7C8F', ligne: v('--ligne') || '#22303B',
    forte: v('--ligne-forte') || '#2F4150', warn: v('--warn') || '#FFB454', mark: v('--mark') || '#B07CFF', ok: v('--ok') || '#5BD68A' };
  VS_J.cle = Object.values(VS_J).join();
  return VS_J;
}
if (window.matchMedia) { try { matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { VS_J = null; vsSale('c'); vsSale('o'); vsSale('t'); }); } catch (x) {} }
/** A canvas at its on-screen size in device pixels (ratio capped at 2). */
function vsToile(id) {
  const c = g(id), r = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round(c.clientWidth * r)), h = Math.max(1, Math.round(c.clientHeight * r));
  if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
  return { x: c.getContext('2d'), w: w, h: h, r: r };
}
// The axes and grids, drawn once per size, range and theme into an offscreen canvas.
const VS_FONDS = {};
function vsFond(nom, cle, w, h, dessin) {
  const k = cle + '|' + w + 'x' + h + '|' + vsJetons().cle, f = VS_FONDS[nom];
  if (f && f.k === k) return f.c;
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  dessin(c.getContext('2d'));
  VS_FONDS[nom] = { k: k, c: c };
  return c;
}
if (window.ResizeObserver) {
  const ro = new ResizeObserver(() => { if (VS.v) { vsSale('c'); vsSale('o'); vsSale('t'); } });
  ['vs-const', 'vs-oeil', 'vs-tons'].forEach(id => { const e = g(id); if (e) ro.observe(e); });
}
/** Dots of a cloud, the older paler (four steps). [xy](k) → [px, py] or null. */
function vsNuage(x, n, taille, coul, xy) {
  x.fillStyle = coul;
  const pas = Math.ceil(n / 4);
  for (let a = 0; a < 4; a++) {
    x.globalAlpha = 0.18 + 0.2 * a;
    for (let k = a * pas; k < Math.min(n, (a + 1) * pas); k++) { const p = xy(k); if (p) x.fillRect(p[0] - taille / 2, p[1] - taille / 2, taille, taille); }
  }
  x.globalAlpha = 1;
}
function vsConst() {
  const v = VS.v, p = v._p;
  if (!p) return;
  const t = vsToile('vs-const'), x = t.x, J = vsJetons(), m = v.ordre || 2;
  const cote = Math.min(t.w, t.h) - 24 * t.r, cx = t.w / 2, cy = t.h / 2 + 6 * t.r, e = cote / 2 / 1.6;
  x.clearRect(0, 0, t.w, t.h);
  x.drawImage(vsFond('c', 'ordre' + m, t.w, t.h, f => {
    f.strokeStyle = J.ligne; f.lineWidth = t.r;
    f.beginPath(); f.arc(cx, cy, e, 0, 2 * Math.PI); f.setLineDash([3 * t.r, 4 * t.r]); f.stroke(); f.setLineDash([]);
    // The decision axes: Q for BPSK (its sign decides), both for QPSK and up.
    f.strokeStyle = J.forte; f.beginPath(); f.moveTo(cx - cote / 2, cy); f.lineTo(cx + cote / 2, cy); f.moveTo(cx, cy - cote / 2); f.lineTo(cx, cy + cote / 2); f.stroke();
    f.strokeStyle = J.warn; f.globalAlpha = .55; f.lineWidth = 1.5 * t.r; f.beginPath();
    // Rays half-way between the ideal points: BPSK's on the Q axis, QPSK's on both axes.
    for (let k = 0; k < m; k++) { const a = (m === 2 ? Math.PI / 2 : 0) + k * 2 * Math.PI / m; f.moveTo(cx, cy); f.lineTo(cx + Math.cos(a) * cote / 2, cy - Math.sin(a) * cote / 2); }
    f.stroke(); f.globalAlpha = 1;
    // The ideal points.
    f.strokeStyle = J.t2; f.lineWidth = t.r;
    for (let k = 0; k < m; k++) { const a = k * 2 * Math.PI / m + (m === 2 ? 0 : Math.PI / m); f.beginPath(); f.arc(cx + e * Math.cos(a), cy - e * Math.sin(a), 5 * t.r, 0, 2 * Math.PI); f.stroke(); }
    f.fillStyle = J.t3; f.font = (10 * t.r) + 'px system-ui'; f.fillText('I', cx + cote / 2 - 8 * t.r, cy - 4 * t.r); f.fillText('Q', cx + 4 * t.r, cy - cote / 2 + 10 * t.r);
    f.fillText('+1', cx + e - 6 * t.r, cy + 14 * t.r); f.fillText('−1', cx - e - 6 * t.r, cy + 14 * t.r);
  }), 0, 0);
  const n = p.length / 2;
  vsNuage(x, n, 2.8 * t.r, J.rx, k => [cx + vsS(p[2 * k]) / 64 * e, cy - vsS(p[2 * k + 1]) / 64 * e]);
}
function vsOeil() {
  const v = VS.v, p = v._o;
  if (!p) return;
  const t = vsToile('vs-oeil'), x = t.x, J = vsJetons();
  const g0 = 8 * t.r, g1 = 22 * t.r, W = t.w - 2 * g0, H = t.h - g1 - 6 * t.r, ey = H / 2 / 1.5, cy = g1 + H / 2;
  x.clearRect(0, 0, t.w, t.h);
  x.drawImage(vsFond('o', '', t.w, t.h, f => {
    f.strokeStyle = J.ligne; f.lineWidth = t.r; f.setLineDash([3 * t.r, 4 * t.r]);
    f.beginPath();
    for (const y of [-1, 1]) { f.moveTo(g0, cy - y * ey); f.lineTo(g0 + W, cy - y * ey); }
    f.stroke(); f.setLineDash([]);
    f.strokeStyle = J.forte; f.beginPath(); f.moveTo(g0, cy); f.lineTo(g0 + W, cy); f.stroke();
    // The decision instants, in the middle of each bit.
    f.strokeStyle = J.warn; f.globalAlpha = .55; f.beginPath();
    for (const u of [0.25, 0.75]) { f.moveTo(g0 + u * W, g1); f.lineTo(g0 + u * W, g1 + H); }
    f.stroke(); f.globalAlpha = 1;
    f.fillStyle = J.t3; f.font = (10 * t.r) + 'px system-ui';
    f.fillText('décision', g0 + 0.25 * W + 4 * t.r, g1 + 10 * t.r); f.fillText('seuil', g0 + 4 * t.r, cy - 4 * t.r);
    f.fillText('2 bits', g0 + W - 34 * t.r, g1 + H - 4 * t.r);
  }), 0, 0);
  const n = p.length / 2;
  // Each sample twice: in the bit it belongs to and the next one, a two-bit eye.
  vsNuage(x, 2 * n, 1.6 * t.r, J.rx, k => {
    const j = k >> 1, ph = p[2 * j] / 255, y = vsS(p[2 * j + 1]) / 48;
    return [g0 + (ph + (k & 1)) / 2 * W, cy - y * ey];
  });
}
function vsTons() {
  const v = VS.v, d = v.tons;
  if (!d || !d.v || !d.v.length) return;
  const t = vsToile('vs-tons'), x = t.x, J = vsJetons(), n = d.v.length;
  const g0 = 10 * t.r, gb = 18 * t.r, g1 = 22 * t.r, W = t.w - 2 * g0, H = t.h - g1 - gb;
  const de = d.de - d.pas / 2, a = de + d.pas * n, X = f => g0 + (f - de) / (a - de) * W;
  const u = d.genre === 'niveaux' ? v.unite : 'Hz';
  x.clearRect(0, 0, t.w, t.h);
  x.drawImage(vsFond('t', [d.genre, u, de.toPrecision(5), a.toPrecision(5)].join(), t.w, t.h, f => {
    f.strokeStyle = J.ligne; f.lineWidth = t.r; f.fillStyle = J.t3; f.font = (10 * t.r) + 'px system-ui'; f.textAlign = 'center';
    const pas = vsPas(a - de, Math.max(3, Math.floor(W / (80 * t.r))));
    f.beginPath();
    for (let q = Math.ceil(de / pas) * pas; q <= a; q += pas) {
      const px = X(q); f.moveTo(px, g1); f.lineTo(px, g1 + H);
      f.fillText(u === 'FS' ? vsNb(q, 2) : Math.abs(pas) >= 1000 ? vsNb(q / 1000, pas >= 10000 ? 0 : 1) + ' k' : vsNb(q, 0), px, t.h - 5 * t.r);
    }
    f.stroke(); f.textAlign = 'left';
    f.strokeStyle = J.forte; f.beginPath(); f.moveTo(g0, g1 + H); f.lineTo(g0 + W, g1 + H); f.stroke();
  }), 0, 0);
  // The distribution as a filled outline.
  x.beginPath(); x.moveTo(X(de), g1 + H);
  for (let i = 0; i < n; i++) x.lineTo(X(d.de + i * d.pas), g1 + H - d.v[i] / 1000 * (H - 4 * t.r));
  x.lineTo(X(a), g1 + H); x.closePath();
  x.fillStyle = J.rx; x.globalAlpha = .28; x.fill(); x.globalAlpha = 1; x.strokeStyle = J.rx; x.lineWidth = 1.2 * t.r; x.stroke();
  if (d.ecart != null) {
    const pb = X(d.bas), ph = X(d.haut), y = g1 + 14 * t.r;
    x.strokeStyle = J.warn; x.fillStyle = J.warn; x.lineWidth = t.r; x.setLineDash([4 * t.r, 3 * t.r]);
    x.beginPath(); x.moveTo(pb, g1); x.lineTo(pb, g1 + H); x.moveTo(ph, g1); x.lineTo(ph, g1 + H); x.stroke(); x.setLineDash([]);
    // The shift between them.
    x.beginPath(); x.moveTo(pb, y); x.lineTo(ph, y); x.stroke();
    x.font = '600 ' + (11 * t.r) + 'px system-ui'; x.textAlign = 'center';
    x.fillText(vsHz(d.ecart, u), (pb + ph) / 2, y - 4 * t.r);
    x.font = (10 * t.r) + 'px system-ui';
    x.fillText(vsHz(d.bas, u), pb, g1 + H - 6 * t.r); x.fillText(vsHz(d.haut, u), ph, g1 + H - 6 * t.r);
    x.textAlign = 'left';
  }
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) vsTic(); });
document.querySelectorAll('.tl-st .st-nav button[data-p="tl-p-sig"]').forEach(b => b.addEventListener('click', () => setTimeout(vsTic)));
vsTic();
"""
}
