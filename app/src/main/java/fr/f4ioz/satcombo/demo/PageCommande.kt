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
 * The control desk, as it appears on the PC screen.
 *
 * **A page, no installation.** A real Windows program would have meant an
 * installer, a signature and an update for every SatMe version — and brought
 * nothing more here. A page served by the phone works on any computer on the
 * network, and follows the app version by itself.
 *
 * **Keyboard first.** The point of a PC during a pass is typing callsigns
 * with ten fingers while the phone stays wired to the rig. So the callsign
 * field takes focus on its own, Enter logs, and nothing forces you to touch
 * the mouse.
 */
object PageCommande {

    val HTML: String = """
<!doctype html>
<html lang="fr">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SatMe — poste de commande</title>
<style>
  :root { --fond:#0B1016; --carte:#131C24; --bord:#22303B; --cyan:#3FE0C8;
          --ambre:#FFB454; --rose:#E5484D; --gris:#9AA7B4; --clair:#E8F0F7; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:16px;
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  .page { max-width:1100px; margin:0 auto; padding:24px; }
  h1 { font-size:20px; margin:0 0 4px; letter-spacing:.5px; }
  .sub { color:var(--gris); font-size:13px; margin-bottom:20px; }
  .grille { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
  @media (max-width:820px) { .grille { grid-template-columns:1fr; } }
  .carte { background:var(--carte); border:1px solid var(--bord);
           border-radius:14px; padding:18px; }
  .t { color:var(--gris); font-size:11px; letter-spacing:1.6px;
       text-transform:uppercase; margin-bottom:10px; }
  .gros { font-size:34px; font-weight:800; letter-spacing:1px; }
  .cyan { color:var(--cyan); } .ambre { color:var(--ambre); }
  .paire { display:flex; gap:24px; flex-wrap:wrap; }
  .val { font-family:ui-monospace,Consolas,monospace; font-size:22px; }
  input, select, button { font-size:18px; font-family:inherit; border-radius:10px;
         border:1px solid var(--bord); background:#0E1720; color:var(--clair);
         padding:12px; }
  input:focus, select:focus { outline:2px solid var(--cyan); }
  #call { font-size:30px; font-weight:800; letter-spacing:3px;
          text-transform:uppercase; width:100%; }
  .ligne { display:flex; gap:10px; margin-top:10px; flex-wrap:wrap; }
  .ligne input { flex:1; min-width:90px; }
  button { cursor:pointer; font-weight:700; }
  .primaire { background:var(--cyan); color:#06131A; border:none; padding:14px 22px; }
  .rouge { background:var(--rose); color:#fff; border:none; }
  table { width:100%; border-collapse:collapse; font-size:14px; }
  td { padding:6px 4px; border-bottom:1px solid var(--bord); }
  td.c { font-weight:800; font-size:17px; }
  .etat { margin-top:10px; font-size:14px; min-height:22px; }
  /* Suggestions: three at most, one line each. Beyond that you read instead
     of glancing, and the seconds saved on typing are lost on choosing. */
  #qui { min-height:52px; margin-top:6px; }
  #qui .p { font-size:34px; font-weight:800; color:var(--cyan); line-height:1.1; }
  #qui .d { color:var(--gris); font-size:13px; }
  #qui .att { color:var(--gris); font-size:13px; }
  #props { display:flex; gap:8px; margin-top:8px; flex-wrap:wrap; min-height:44px; }
  .prop { background:#0E1720; border:1px solid var(--bord); border-radius:10px;
          padding:8px 12px; cursor:pointer; font-size:15px; }
  .prop.sel { border-color:var(--cyan); background:rgba(63,224,200,.12); }
  .prop b { font-size:17px; letter-spacing:1px; }
  .prop span { color:var(--gris); font-size:12px; margin-left:8px; }
  .ok { color:var(--cyan); } .ko { color:var(--rose); }
  #infos { min-height:22px; font-size:14px; margin-top:6px; }
  #infos .nouveau { color:#49D17F; font-weight:800; letter-spacing:1px; }
  #infos .doublon { color:var(--ambre); font-weight:700; }
  #edition { display:none; margin-top:10px; padding:8px 12px; border-radius:10px;
             background:rgba(255,180,84,.12); color:var(--ambre); font-size:14px; }
  #liste td.envoi { font-size:12px; color:var(--gris); }
  #liste td.envoi .ok { color:var(--cyan); } #liste td.envoi .ambre { color:var(--ambre); }
  #liste td.act { white-space:nowrap; text-align:right; }
  #liste td.act button { font-size:14px; padding:4px 9px; margin-left:4px; }
  .grille2 { margin-top:16px; }
  .puces { display:flex; flex-wrap:wrap; gap:8px; margin:10px 0; }
  .puce { border:1px solid var(--bord); border-radius:8px; padding:4px 10px;
          font-size:13px; color:var(--gris); }
  .puce.on { border-color:var(--cyan); color:var(--cyan); }
  .puce.rec { border-color:var(--rose); color:var(--rose); }
  .info { font-size:13px; color:var(--gris); margin-top:4px; }
  .info b { color:var(--clair); font-weight:600; }
  #ici { margin-top:12px; padding:10px 12px; border-radius:10px;
         background:rgba(63,224,200,.10); font-size:15px; }
  #profils, #passages { width:100%; border-collapse:collapse; margin-top:8px; }
  #profils td, #passages td { padding:7px 6px; border-bottom:1px solid var(--bord); font-size:14px; }
  #profils tr { cursor:pointer; }
  #profils tr:hover { background:rgba(255,255,255,.04); }
  #profils tr.defaut td:first-child { color:var(--cyan); font-weight:800; }
  #profils .marque { font-size:11px; color:var(--cyan); letter-spacing:1px; }
  #passages .encours { color:var(--cyan); font-weight:800; }
  #passages button { font-size:13px; padding:5px 10px; }
  #porte { max-width:420px; margin:12vh auto; }
</style>

<div class="page" id="porte">
  <h1>SatMe — poste de commande</h1>
  <div class="sub">Saisis le code affiché sur le téléphone.</div>
  <div class="carte">
    <input id="code" inputmode="numeric" maxlength="6" placeholder="123456"
           style="width:100%;font-size:28px;letter-spacing:8px;text-align:center">
    <div class="ligne"><button class="primaire" style="flex:1"
         onclick="entrer()">Entrer</button></div>
    <div class="etat" id="porte-etat"></div>
  </div>
</div>

<div class="page" id="poste" style="display:none">
  <h1>SatMe — poste de commande</h1>
  <div class="sub" id="station">—</div>

  <div class="grille">
    <div class="carte">
      <div class="t">Passage</div>
      <div class="gros cyan" id="sat">—</div>
      <!-- Le cadran : trace du passage, position du satellite en cyan, et le
           trait ambre de l'antenne quand la boussole est branchée. Tracé en
           SVG, redessiné à chaque seconde — aucune image à charger. -->
      <svg id="cadran" viewBox="0 0 220 220" width="220" height="220"
           style="display:block;margin:10px auto"></svg>
      <div class="paire" style="margin-top:12px">
        <div><div class="t">Azimut</div><div class="val" id="az">—</div></div>
        <div><div class="t">Élévation</div><div class="val" id="el">—</div></div>
        <div><div class="t">AOS / LOS</div><div class="val" id="aos">—</div></div>
        <div><div class="t">Antenne</div><div class="val ambre" id="ant">—</div></div>
        <div><div class="t">Écart</div><div class="val" id="ecart">—</div></div>
      </div>
      <div class="paire" style="margin-top:14px">
        <div><div class="t">Réception</div><div class="val cyan" id="rx">—</div></div>
        <div><div class="t">Émission</div><div class="val ambre" id="tx">—</div></div>
      </div>
      <div class="ligne">
        <select id="sats" style="flex:1"></select>
        <button onclick="changeSat()">Suivre</button>
      </div>
      <div class="ligne">
        <button id="rec" class="rouge" onclick="rec()">Enregistrer</button>
      </div>
      <div class="etat" id="etat-cmd"></div>
    </div>

    <div class="carte">
      <div class="t">Contact</div>
      <input id="call" placeholder="INDICATIF" autocomplete="off" spellcheck="false">
      <!-- Le prénom, en grand et tout de suite : c'est ce qu'on lance à la
           radio, et le chercher dans une ligne de détails coûte le temps qu'on
           n'a pas pendant un passage. -->
      <div id="qui"></div>
      <!-- Nouveau carré, doublon : ce qu'il faut savoir avant d'enregistrer. -->
      <div id="infos"></div>
      <div id="props"></div>
      <div class="ligne">
        <input id="loc" placeholder="Locator" autocomplete="off" spellcheck="false">
        <input id="rse" value="59" title="RST envoyé">
        <input id="rsr" value="59" title="RST reçu">
        <button onclick="qrz()" title="Chercher sur QRZ.com">QRZ</button>
        <button class="primaire" id="valider" onclick="qso()">Enregistrer</button>
      </div>
      <div id="edition"></div>
      <div class="etat" id="etat-qso">Tab complète et interroge QRZ, Entrée enregistre, Échap efface.</div>
      <div class="t" style="margin-top:18px">Journal des dernières 24 h (UTC)</div>
      <table id="liste"><tbody></tbody></table>
    </div>
  </div>

  <!-- Ce qu'on irait sinon vérifier sur le téléphone : où partent les
       contacts, l'état du poste, et les prochains passages. -->
  <div class="grille grille2">
    <div class="carte">
      <div class="t">Station</div>
      <div class="gros" id="st-ind" style="font-size:26px">—</div>
      <div class="puces" id="puces"></div>
      <div class="info" id="st-tp"></div>
      <div class="info" id="st-radio"></div>
      <div class="info" id="st-auto"></div>
      <div id="ici"></div>
      <div class="t" style="margin-top:14px">Profils Wavelog — un clic le met par défaut</div>
      <table id="profils"><tbody></tbody></table>
      <div class="ligne"><button onclick="releve()">Récupérer les profils</button></div>
      <div class="etat" id="etat-profils"></div>
    </div>
    <div class="carte">
      <div class="t">Prochains passages (satellites suivis, UTC)</div>
      <table id="passages"><tbody></tbody></table>
    </div>
  </div>
</div>

<script>
const base = location.pathname.replace(/\/$/, '');
let cle = localStorage.getItem('satme-cle') || '';
const g = id => document.getElementById(id);

async function appel(route, params) {
  const p = new URLSearchParams(params || {});
  if (cle) p.set('cle', cle);
  const r = await fetch(base + route + '?' + p.toString());
  return r.json();
}

async function entrer() {
  const r = await fetch(base + '/entrer?code=' + encodeURIComponent(g('code').value));
  const j = await r.json();
  if (j.ok) {
    cle = j.cle;
    localStorage.setItem('satme-cle', cle);
    ouvre();
  } else {
    g('porte-etat').className = 'etat ko';
    g('porte-etat').textContent = 'Code refusé.';
  }
}

function ouvre() {
  g('porte').style.display = 'none';
  g('poste').style.display = '';
  g('call').focus();
  chargeSats();
  dessineCadran(null);
  tic();
  chargeJournal();
  chargeStation();
  setInterval(tic, 1000);
}

function mhz(hz) {
  return (hz === null || hz === undefined) ? '—'
    : (hz / 1e6).toFixed(4).replace('.', ',') + ' MHz';
}

// --- polar plot ---
//
// Zenith at the centre, horizon at the edge: the phone screen's convention.
// Changing it here would make one operator read two different maps, which is
// worse than no map at all.
const SVG = 'http://www.w3.org/2000/svg';

function pointPolaire(az, el) {
  const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90);
  const a = (az - 90) * Math.PI / 180;
  return [110 + r * Math.cos(a), 110 + r * Math.sin(a)];
}

function el(nom, attrs) {
  const e = document.createElementNS(SVG, nom);
  for (const k in attrs) e.setAttribute(k, attrs[k]);
  return e;
}

function dessineCadran(e) {
  const c = g('cadran');
  c.innerHTML = '';
  [100, 66.7, 33.3].forEach(r =>
    c.appendChild(el('circle', { cx: 110, cy: 110, r: r,
      fill: 'none', stroke: '#22303B', 'stroke-width': 1 })));
  c.appendChild(el('line', { x1: 110, y1: 10, x2: 110, y2: 210,
    stroke: '#22303B', 'stroke-width': 1 }));
  c.appendChild(el('line', { x1: 10, y1: 110, x2: 210, y2: 110,
    stroke: '#22303B', 'stroke-width': 1 }));
  [['N', 110, 8], ['E', 214, 113], ['S', 110, 218], ['O', 6, 113]]
    .forEach(([t, x, y]) => {
      const n = el('text', { x: x, y: y, fill: '#9AA7B4', 'font-size': 11,
        'text-anchor': 'middle' });
      n.textContent = t;
      c.appendChild(n);
    });

  if (e && e.trace && e.trace.length) {
    const d = e.trace.map((p, i) =>
      (i ? 'L' : 'M') + pointPolaire(p[0], p[1]).map(v => v.toFixed(1)).join(' '));
    c.appendChild(el('path', { d: d.join(' '), fill: 'none',
      stroke: '#3FE0C8', 'stroke-width': 1.5, 'stroke-dasharray': '4 4',
      opacity: .7 }));
  }
  // Antenna first, satellite second: the dot must stay readable even when the
  // two overlap — which is precisely when it matters.
  if (e && e.antaz !== null && e.antaz !== undefined) {
    const [x, y] = pointPolaire(e.antaz, Math.max(0, e.antel || 0));
    c.appendChild(el('line', { x1: 110, y1: 110, x2: x, y2: y,
      stroke: '#FFB454', 'stroke-width': 2.5, 'stroke-linecap': 'round' }));
  }
  if (e && e.el !== null && e.el !== undefined && e.el > -2) {
    const [x, y] = pointPolaire(e.az, e.el);
    c.appendChild(el('circle', { cx: x, cy: y, r: 7, fill: '#3FE0C8',
      opacity: .25 }));
    c.appendChild(el('circle', { cx: x, cy: y, r: 4, fill: '#3FE0C8' }));
  }
}

/**
 * Countdown, from the timestamp and the phone's clock, said in words:
 * "AOS dans 9 h 19", "LOS dans 3:12". A bare "-9:19:09" had to be decoded.
 */
function rebours(quoi, cible, maintenant) {
  if (!cible || !maintenant) return '—';
  let s = Math.round((cible - maintenant) / 1000);
  if (s < 0) return quoi + ' passé';
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return quoi + ' dans ' + (h ? h + ' h ' + String(m).padStart(2, '0')
                              : m + ':' + String(s % 60).padStart(2, '0'));
}

async function tic() {
  let e;
  try { e = await appel('/etat'); } catch (x) { return; }
  // An expired session sends you back to the door: better ask for the code
  // again than leave buttons that no longer do anything.
  if (e.ok === false && e.raison === 'session') {
    localStorage.removeItem('satme-cle');
    location.reload();
    return;
  }
  g('station').textContent = (e.station || '') + ' · ' + (e.grille || '');
  g('sat').textContent = e.sat || '—';
  g('az').textContent = e.az === null ? '—' : Math.round(e.az) + '°';
  g('el').textContent = e.el === null ? '—' : Math.round(e.el) + '°';
  // **A countdown, not a timestamp.** Telemetry sends milliseconds since
  // 1970: showing them raw tells nobody anything. Before the rise we count to
  // AOS, afterwards to LOS — the question you ask yourself throughout a pass.
  const leve = e.el !== null && e.el !== undefined && e.el > 0;
  g('aos').textContent = rebours(leve ? 'LOS' : 'AOS', leve ? e.los : e.aos, e.now);
  g('ant').textContent = (e.antaz === null || e.antaz === undefined) ? '—'
    : Math.round(e.antaz) + '° · ' + Math.round(e.antel || 0) + '°';
  // Pointing error: the one value that says whether to move the antenna.
  if (e.antaz === null || e.antaz === undefined ||
      e.az === null || e.az === undefined) {
    g('ecart').textContent = '—';
    g('ecart').className = 'val';
  } else {
    let d = Math.abs(((e.az - e.antaz) % 360 + 540) % 360 - 180);
    g('ecart').textContent = Math.round(d) + '°';
    g('ecart').className = 'val ' + (d <= 15 ? 'cyan' : (d <= 25 ? 'ambre' : ''));
  }
  dessineCadran(e);
  g('rx').textContent = mhz(e.rx);
  g('tx').textContent = mhz(e.tx);
  // The log every two seconds: its waiting times count down in seconds.
  if (++tours % 2 === 0) chargeJournal();
  if (tours % 5 === 0) chargeStation(); else dessinePassages();
}

// --- station, Wavelog profiles, coming passes ---
//
// Every five seconds: none of it moves fast, and the countdowns are
// recomputed each second from the phone's clock offset.
let station = null, decalage = 0;

function hm(ms) {
  const d = new Date(ms);
  return String(d.getUTCHours()).padStart(2, '0') + ':' + String(d.getUTCMinutes()).padStart(2, '0');
}

async function chargeStation() {
  let st;
  try { st = await appel('/station'); } catch (x) { return; }
  if (!st || !st.indicatif && !st.profils) return;
  station = st;
  decalage = st.now - Date.now();
  g('st-ind').textContent = (st.indicatif || '—') + ' · ' + (st.locator || '');
  g('puces').innerHTML =
    '<span class="puce' + (st.cat ? ' on' : '') + '">CAT ' + (st.cat ? echappe(st.cat) : 'non connecté') + '</span>' +
    '<span class="puce' + (st.rotor ? ' on' : '') + '">Rotor ' + (st.rotor ? 'connecté' : '—') + '</span>' +
    '<span class="puce' + (st.rec ? ' rec' : '') + '">' + (st.rec ? '● Enregistrement' : 'Pas d\'enregistrement') + '</span>';
  g('st-tp').innerHTML = st.tp ? 'Transpondeur : <b>' + echappe(st.tp) + '</b>' : '';
  g('st-radio').innerHTML = st.radio ? 'Radio Wavelog : ' + echappe(st.radio) : 'Radio Wavelog : désactivée';
  g('st-auto').innerHTML = st.auto ? 'Envoi automatique : ' + echappe(st.auto) : 'Envoi automatique : désactivé';
  const parId = {};
  (st.profils || []).forEach(p => parId[p.id] = p);
  const ici = parId[st.ici];
  // Where the next contact goes, with the callsign it will carry: the one
  // thing that makes Wavelog accept or skip it.
  g('ici').innerHTML = !st.ici ? 'Aucun profil de station réglé : les contacts ne peuvent pas partir.'
    : 'Prochain contact → profil <b>' + echappe(st.ici) + '</b>' +
      (ici ? ' · ' + echappe(ici.nom) + ' · <b>' + echappe(ici.indicatif) + '</b> · ' + echappe(ici.carre)
           : ' (liste des profils non chargée)') +
      (st.ici !== st.defaut ? ' <span class="info">— choisi d\'après votre carré, le défaut est ' + echappe(st.defaut) + '</span>' : '');
  g('profils').querySelector('tbody').innerHTML = (st.profils || []).map(p =>
    '<tr data-id="' + echappe(p.id) + '" class="' + (p.id === st.defaut ? 'defaut' : '') + '">' +
    '<td>' + echappe(p.id) + '</td><td>' + echappe(p.nom) + '</td>' +
    '<td><b>' + echappe(p.indicatif) + '</b></td><td>' + echappe(p.carre) + '</td>' +
    '<td class="marque">' + (p.id === st.defaut ? 'PAR DÉFAUT' : '') +
      (p.id === st.ici ? (p.id === st.defaut ? ' · ' : '') + 'UTILISÉ ICI' : '') + '</td></tr>').join('');
  if (!(st.profils || []).length && st.profilsEtat) g('etat-profils').textContent = st.profilsEtat;
  dessinePassages();
}

function dessinePassages() {
  if (!station) return;
  const maintenant = Date.now() + decalage;
  g('passages').querySelector('tbody').innerHTML = (station.passages || []).map(p => {
    const encours = maintenant >= p.aos && maintenant < p.los;
    const quand = encours ? '<span class="encours">en cours · ' + rebours('LOS', p.los, maintenant) + '</span>'
                          : rebours('AOS', p.aos, maintenant);
    return '<tr><td><b>' + echappe(p.s) + '</b></td><td>' + hm(p.aos) + '–' + hm(p.los) + '</td>' +
      '<td>' + quand + '</td><td>él. ' + p.el + '°</td>' +
      '<td style="text-align:right"><button data-s="' + echappe(p.s) + '">Suivre</button></td></tr>';
  }).join('') || '<tr><td class="info">Aucun passage prévu pour les satellites suivis.</td></tr>';
}

g('profils').addEventListener('click', async ev => {
  const tr = ev.target.closest('tr');
  if (!tr || !station || tr.dataset.id === station.defaut) return;
  const j = await appel('/profil', { id: tr.dataset.id });
  dis('etat-profils', j.ok ? 'Profil ' + tr.dataset.id + ' mis par défaut.' : 'Profil inconnu.', j.ok);
  chargeStation();
});

g('passages').addEventListener('click', async ev => {
  const b = ev.target.closest('button');
  if (!b) return;
  const j = await appel('/sat', { nom: b.dataset.s });
  dis('etat-cmd', j.ok ? b.dataset.s + ' suivi.' : 'Satellite introuvable.', j.ok);
});

async function releve() {
  dis('etat-profils', 'Récupération…', true);
  await appel('/releve');
  setTimeout(async () => {
    await chargeStation();
    const n = station && station.profils ? station.profils.length : 0;
    dis('etat-profils', n ? n + ' profils récupérés.' : (station && station.profilsEtat) || 'Aucun profil reçu.', n > 0);
  }, 3000);
}

// --- the log: fix, hold back, delete ---
//
// The last 24 hours, not only this pass: a typo is often seen after LOS.
let tours = 0, lignes = [];

function echappe(t) {
  return String(t || '').replace(/[&<>"]/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
}

/** What the automatic upload does with this contact, in words. */
function envoiTexte(q) {
  switch (q.e) {
    case 'envoye': return '<span class="ok">✓ Wavelog</span>';
    case 'attente': return 'part dans ' + q.r + ' s';
    case 'pret': return 'envoi…';
    case 'pause': return '<span class="ambre">en pause' +
                         (q.x ? ' — refusé : ' + echappe(q.x) : '') + '</span>';
    default: return '';
  }
}

async function chargeJournal() {
  let l;
  try { l = await appel('/journal'); } catch (x) { return; }
  if (!Array.isArray(l)) return;
  lignes = l;
  const t = g('liste').querySelector('tbody');
  t.innerHTML = l.map((q, i) => {
    const attend = q.e === 'attente' || q.e === 'pret';
    const pause = q.e === 'pause'
      ? '<button data-i="' + i + '" data-a="reprend" title="Reprendre l\'envoi">▶</button>'
      : (attend ? '<button data-i="' + i + '" data-a="pause" title="Mettre en pause">⏸</button>' : '');
    return '<tr' + (q.t === edite ? ' style="background:rgba(255,180,84,.10)"' : '') + '>' +
      '<td>' + echappe(q.h) + '</td><td class="c">' + echappe(q.c) + '</td>' +
      '<td>' + echappe(q.l) + '</td><td>' + echappe(q.s) + '</td>' +
      '<td class="envoi">' + envoiTexte(q) + '</td>' +
      '<td class="act">' + pause +
      '<button data-i="' + i + '" data-a="modifie" title="Modifier">✎</button>' +
      '<button data-i="' + i + '" data-a="supprime" title="Supprimer">✕</button></td></tr>';
  }).join('');
}

g('liste').addEventListener('click', async ev => {
  const b = ev.target.closest('button');
  if (!b) return;
  const q = lignes[+b.dataset.i];
  if (!q) return;
  if (b.dataset.a === 'pause' || b.dataset.a === 'reprend') {
    await appel('/pause', { t: q.t, on: b.dataset.a === 'pause' ? '1' : '0' });
  } else if (b.dataset.a === 'modifie') {
    commenceEdition(q);
  } else if (b.dataset.a === 'supprime') {
    // Deleting here does not reach Wavelog: its API cannot delete.
    const deja = q.e === 'envoye'
      ? '\n\nIl est déjà dans Wavelog : supprimez-le aussi là-bas.' : '';
    if (!confirm('Supprimer ' + q.c + ' (' + q.h + ' UTC, ' + q.s + ') ?' + deja)) return;
    await appel('/supprime', { t: q.t });
    if (edite === q.t) finEdition();
  }
  chargeJournal();
});

// --- fixing a contact ---
//
// The fields take the contact; Enter saves the change. A contact still
// waiting for Wavelog is held back meanwhile, or its minute could run out
// before the fix — and Wavelog's API cannot change a contact once it has it.
let edite = null, repriseApres = false;

async function commenceEdition(q) {
  edite = q.t;
  repriseApres = q.e === 'attente' || q.e === 'pret';
  if (repriseApres) await appel('/pause', { t: q.t, on: '1' });
  g('call').value = q.c; g('loc').value = q.l || '';
  g('rse').value = q.rse || '59'; g('rsr').value = q.rsr || '59';
  props = []; choisi = -1; dessineProps();
  montreQui('', '', ''); g('qui').dataset.pour = q.c;
  g('valider').textContent = 'Modifier';
  g('edition').style.display = 'block';
  g('edition').textContent = 'Modification de ' + q.c + ' (' + q.h + ' UTC, ' + q.s + ')' +
    (q.e === 'envoye' ? ' — déjà dans Wavelog : corrigez-le aussi là-bas.' : '') +
    ' Échap annule.';
  planifieInfos();
  g('call').focus();
  chargeJournal();
}

async function finEdition(annule) {
  if (edite !== null && repriseApres) await appel('/pause', { t: edite, on: '0' });
  edite = null; repriseApres = false;
  g('valider').textContent = 'Enregistrer';
  g('edition').style.display = 'none';
  if (annule) {
    g('call').value = ''; g('loc').value = '';
    g('rse').value = '59'; g('rsr').value = '59';
    g('infos').innerHTML = '';
  }
  chargeJournal();
}

// --- new square, duplicate ---
//
// Asked after a pause in typing, like QRZ: the phone may have to ask the
// online log, and one question per letter would be too many.
let minuteurInfos = 0;

function planifieInfos() {
  clearTimeout(minuteurInfos);
  minuteurInfos = setTimeout(async () => {
    const call = g('call').value.trim().toUpperCase();
    const loc = g('loc').value.trim().toUpperCase();
    if (!call && loc.length < 4) { g('infos').innerHTML = ''; return; }
    let i;
    try { i = await appel('/infos', { call: call, loc: loc }); } catch (x) { return; }
    if (g('call').value.trim().toUpperCase() !== call) return;
    const k = loc.slice(0, 4);
    const morceaux = [];
    if (i.carre === 'nouveau') morceaux.push('<span class="nouveau">NOUVEAU CARRÉ ' + k + '</span>');
    else if (i.carre === 'nouveau_journal') morceaux.push(k + ' : absent du journal SatMe');
    else if (i.carre === 'travaille') morceaux.push(k + ' déjà travaillé');
    else if (i.carre === 'confirme') morceaux.push(k + ' confirmé');
    // In edit mode the contact being fixed is its own duplicate.
    if (i.doublon && edite === null)
      morceaux.push('<span class="doublon">Doublon : ' + echappe(call) +
                    ' déjà contacté sur ce satellite à ' + i.doublon + ' UTC</span>');
    g('infos').innerHTML = morceaux.join(' · ');
  }, 500);
}
g('loc').addEventListener('input', planifieInfos);

async function chargeSats() {
  const l = await appel('/sats');
  if (!Array.isArray(l)) return;
  g('sats').innerHTML = l.map(n => '<option>' + n + '</option>').join('');
}

async function changeSat() {
  const j = await appel('/sat', { nom: g('sats').value });
  dis('etat-cmd', j.ok ? 'Satellite suivi.' : 'Satellite introuvable.', j.ok);
}

let enregistre = false;
async function rec() {
  enregistre = !enregistre;
  await appel('/rec', { on: enregistre ? '1' : '0' });
  g('rec').textContent = enregistre ? 'Arrêter' : 'Enregistrer';
  dis('etat-cmd', enregistre ? 'Enregistrement en cours.' : 'Enregistrement arrêté.', true);
}

function dis(id, texte, ok) {
  g(id).className = 'etat ' + (ok ? 'ok' : 'ko');
  g(id).textContent = texte;
}

/**
 * Fetches the QRZ record for the typed callsign.
 *
 * The grid never replaces one already typed: QRZ gives the home square, not
 * the one you transmit from that day. A portable operation in the mountains
 * would be filed at home, and the error would only show up at award time.
 */
async function qrz(auto) {
  const call = g('call').value.trim().toUpperCase();
  if (!call) { if (!auto) g('call').focus(); return; }
  dis('etat-qso', 'QRZ…', true);
  let f;
  try { f = await appel('/qrz', { call: call }); } catch (x) { f = null; }
  if (!f || !f.ok) {
    dis('etat-qso', 'QRZ : ' + ((f && f.e) || 'pas de réponse'), false);
    return;
  }
  if (f.l && !g('loc').value.trim()) { g('loc').value = f.l; planifieInfos(); }
  g('qui').dataset.pour = call;
  montreQui(f.f || prenomDe(f.n), f.n, [f.v, f.p].filter(Boolean).join(' · '));
  dis('etat-qso', [f.c, f.n, f.v, f.p].filter(Boolean).join(' · '), true);
  // When automatic, the hand has already moved to the next field: stealing
  // the cursor back would lose whatever is being typed.
  if (!auto) g('call').focus();
}

/**
 * What Tab does once the callsign is settled.
 *
 * **QRZ is only queried when the locator is still empty.** When the keypad
 * memory already gave the square, one more request learns nothing and eats
 * into the subscription quota — and the worked square is the better one, since
 * it comes from a real contact with that callsign.
 */
function apresTab() {
  if (g('call').value.trim().length >= 3 && !g('loc').value.trim()) qrz(true);
}

async function qso() {
  const call = g('call').value.trim().toUpperCase();
  if (!call) { g('call').focus(); return; }
  if (edite !== null) {
    const m = await appel('/modifie', {
      t: edite, call: call, loc: g('loc').value.trim().toUpperCase(),
      rse: g('rse').value.trim(), rsr: g('rsr').value.trim()
    });
    dis('etat-qso', m.ok ? call + ' modifié.' : 'Modification refusée.', m.ok);
    if (m.ok) { await finEdition(true); g('call').focus(); }
    return;
  }
  const j = await appel('/qso', {
    call: call, loc: g('loc').value.trim().toUpperCase(),
    rse: g('rse').value.trim(), rsr: g('rsr').value.trim()
  });
  dis('etat-qso', j.ok ? call + ' enregistré.' : 'Refusé : ' + (j.raison || 'indicatif'), j.ok);
  if (j.ok) {
    // The field clears and takes focus back: during a pass, every reach for
    // the mouse is a lost contact.
    g('call').value = ''; g('loc').value = '';
    g('rse').value = '59'; g('rsr').value = '59';
    props = []; choisi = -1; dernierQ = ''; dessineProps();
    montreQui('', '', ''); g('qui').dataset.pour = '';
    g('infos').innerHTML = '';
    g('call').focus();
    chargeJournal();
  }
}

// --- callsign keypad suggestions ---
//
// The ranking comes from the phone: the very function that feeds its keypad.
// It is not replayed here, or the two screens would end up suggesting
// different things.
let props = [], choisi = -1, dernierQ = '';

function dessineProps() {
  const z = g('props');
  z.innerHTML = '';
  props.forEach((p, i) => {
    const d = document.createElement('div');
    d.className = 'prop' + (i === choisi ? ' sel' : '');
    d.innerHTML = '<b>' + p.c + '</b><span>' +
      (p.l || '') + (p.n ? ' · ' + p.n : '') + ' · ' + p.q + '×</span>';
    d.onclick = () => { choisi = i; prend(); };
    z.appendChild(d);
  });
}

function prend() {
  const p = props[choisi < 0 ? 0 : choisi];
  if (!p) return;
  g('call').value = p.c;
  // The locator follows the suggestion: that is where most time is saved, and
  // it is the square most often logged for that callsign.
  if (p.l) g('loc').value = p.l;
  props = []; choisi = -1; dessineProps();
  if (p.n) { g('qui').dataset.pour = p.c; montreQui(prenomDe(p.n), p.n, p.l || ''); }
  planifieInfos();
  g('call').focus();
}

/**
 * Shows who is on the other end.
 *
 * First name first, then town and country in small type. The keypad memory
 * comes before QRZ: it comes from a real contact, and answers without a
 * network.
 */
function montreQui(prenom, nom, details) {
  const z = g('qui');
  if (!prenom && !nom && !details) { z.innerHTML = ''; return; }
  // First name large, full name just below: one is what you say on the air,
  // the other confirms you have the right person.
  const bas = [nom, details].filter(Boolean).join(' · ');
  z.innerHTML = (prenom ? '<div class="p">' + prenom + '</div>' : '') +
                (bas ? '<div class="d">' + bas + '</div>' : '');
}

function prenomDe(nom) {
  return (nom || '').trim().split(/\s+/)[0] || '';
}

// **The automatic lookup waits for a pause in typing.**
//
// Firing on every letter would send ten requests for one callsign, and a QRZ
// subscription quota would not survive a pass. Six tenths of a second without
// a keystroke is when the operator has finished typing and is waiting. The QRZ
// cache does the rest — a callsign already seen never goes out again.
let minuteurQrz = 0;

function planifieQrz() {
  clearTimeout(minuteurQrz);
  const call = g('call').value.trim().toUpperCase();
  // Three characters and a digit: the minimum shape of a callsign. Below
  // that we would be querying for fragments that designate nobody.
  if (call.length < 4 || !/[0-9]/.test(call)) return;
  if (g('qui').dataset.pour === call) return;
  minuteurQrz = setTimeout(async () => {
    g('qui').innerHTML = '<div class="att">QRZ…</div>';
    let f;
    try { f = await appel('/qrz', { call: call }); } catch (x) { f = null; }
    if (g('call').value.trim().toUpperCase() !== call) return;
    if (!f || !f.ok) { montreQui('', '', ''); return; }
    g('qui').dataset.pour = call;
    montreQui(f.f || prenomDe(f.n), f.n,
              [f.v, f.p].filter(Boolean).join(' · '));
    if (f.l && !g('loc').value.trim()) { g('loc').value = f.l; planifieInfos(); }
  }, 600);
}

async function cherche() {
  const q = g('call').value.trim().toUpperCase();
  if (q === dernierQ) return;
  dernierQ = q;
  if (q.length < 2) { props = []; choisi = -1; dessineProps(); return; }
  try {
    const l = await appel('/propose', { q: q });
    props = Array.isArray(l) ? l : [];
  } catch (x) { props = []; }
  choisi = -1;
  dessineProps();

  // If the memory knows this exact callsign, the first name shows at once and
  // QRZ has nothing to add: no request, no wait.
  const exact = props.find(p => p.c === q);
  if (exact && exact.n) {
    clearTimeout(minuteurQrz);
    g('qui').dataset.pour = q;
    montreQui(prenomDe(exact.n), exact.n, exact.l || '');
  } else {
    montreQui('', '', '');
    g('qui').dataset.pour = '';
    planifieQrz();
  }
}

g('call').addEventListener('input', cherche);
g('call').addEventListener('input', planifieInfos);

document.addEventListener('keydown', ev => {
  if (g('poste').style.display === 'none') {
    if (ev.key === 'Enter') entrer();
    return;
  }
  // Tab and arrow down take the suggestion: the hand never leaves the home
  // row, which is the whole point of a real keyboard.
  if ((ev.key === 'Tab' || ev.key === 'ArrowDown') && props.length) {
    ev.preventDefault();
    choisi = (choisi + 1) % props.length;
    if (ev.key === 'Tab') { prend(); apresTab(); } else dessineProps();
    return;
  }
  // Tab with no suggestion: the callsign is unknown to the memory, which is
  // exactly when QRZ has something to teach.
  if (ev.key === 'Tab' && document.activeElement === g('call')) {
    apresTab();
    return;
  }
  if (ev.key === 'ArrowUp' && props.length) {
    ev.preventDefault();
    choisi = (choisi - 1 + props.length) % props.length;
    dessineProps();
    return;
  }
  if (ev.key === 'Enter') {
    ev.preventDefault();
    // A selected suggestion is taken first; with no selection, Enter logs
    // what was typed — the DX never worked before must get through.
    if (choisi >= 0) prend(); else qso();
    return;
  }
  if (ev.key === 'Escape') {
    if (edite !== null) { finEdition(true); g('call').focus(); return; }
    props = []; choisi = -1; dessineProps();
    g('infos').innerHTML = '';
    g('call').value = ''; g('loc').value = ''; g('call').focus();
  }
});

if (cle) ouvre(); else g('code').focus();
</script>
</html>
""".trimIndent()
}
