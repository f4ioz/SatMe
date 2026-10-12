/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

/**
 * The PC station's page: passes on the left, the satellite followed in the
 * middle (sky, position, countdown), frequencies, rig and mast on the right.
 * Three columns on a large screen, one on a narrow one. No library: a page
 * that works without the internet, at the foot of the mast.
 */
object PagePc {
    val HTML = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe — station PC</title>
<style>
  :root { --fond:#0B1016; --carte:#131C24; --bord:#22303B; --cyan:#3FE0C8;
          --ambre:#FFB454; --rose:#E5484D; --vert:#5BD68A; --gris:#9AA7B4; --clair:#E8F0F7; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:14px;
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { display:flex; align-items:baseline; gap:16px; flex-wrap:wrap; padding:10px 16px;
           border-bottom:1px solid var(--bord); }
  header h1 { font-size:18px; margin:0; letter-spacing:.5px; }
  header .g { color:var(--gris); font-size:13px; }
  #heure { font-family:ui-monospace,Consolas,monospace; font-size:16px; margin-left:auto; }
  .grille { display:grid; grid-template-columns:minmax(300px,1fr) minmax(360px,1.2fr) minmax(340px,1fr);
            gap:12px; padding:12px 16px; }
  @media (max-width:1100px) { .grille { grid-template-columns:1fr 1fr; } .col3 { grid-column:1 / -1; } }
  @media (max-width:760px) { .grille { grid-template-columns:1fr; } }
  .carte { background:var(--carte); border:1px solid var(--bord); border-radius:12px; padding:12px 14px; margin-bottom:12px; }
  .t { color:var(--gris); font-size:11px; letter-spacing:1.6px; text-transform:uppercase; margin-bottom:8px; }
  .gros { font-size:30px; font-weight:800; letter-spacing:1px; }
  .val { font-family:ui-monospace,Consolas,monospace; font-size:20px; }
  .petit { font-size:12px; color:var(--gris); }
  .cyan { color:var(--cyan); } .ambre { color:var(--ambre); } .rose { color:var(--rose); } .vert { color:var(--vert); }
  .paire { display:flex; gap:22px; flex-wrap:wrap; align-items:baseline; }
  input, select, button { font-size:14px; font-family:inherit; border-radius:8px; border:1px solid var(--bord);
         background:#0E1720; color:var(--clair); padding:7px 9px; }
  input:focus, select:focus { outline:2px solid var(--cyan); }
  button { cursor:pointer; font-weight:700; }
  button:disabled { opacity:.45; cursor:default; }
  .primaire { background:var(--cyan); color:#06131A; border:none; }
  .rouge { background:var(--rose); color:#fff; border:none; }
  .ligne { display:flex; gap:8px; margin-top:8px; flex-wrap:wrap; align-items:center; }
  .ligne > select, .ligne > input { flex:1; min-width:80px; }
  table { width:100%; border-collapse:collapse; font-size:13px; }
  th { text-align:left; color:var(--gris); font-weight:600; font-size:11px; padding:4px; border-bottom:1px solid var(--bord); }
  td { padding:5px 4px; border-bottom:1px solid var(--bord); }
  tr.p { cursor:pointer; } tr.p:hover { background:#18242E; }
  tr.encours td { color:var(--vert); font-weight:700; }
  tr.choisi td:first-child { box-shadow:inset 3px 0 0 var(--cyan); }
  #liste { max-height:calc(100vh - 190px); overflow:auto; }
  svg text { font-family:system-ui,sans-serif; }
  .etat { margin-top:6px; font-size:13px; min-height:18px; color:var(--gris); }
  .avert { color:var(--ambre); font-size:12px; margin-top:6px; }
  .diag { font-family:ui-monospace,Consolas,monospace; font-size:11px; color:var(--gris); white-space:pre-wrap; margin-top:6px; max-height:90px; overflow:auto; }
  .bouton-gr { display:flex; gap:6px; flex-wrap:wrap; }
  .pastille { display:inline-block; padding:2px 8px; border-radius:20px; font-size:12px; font-weight:700; }
  .on { background:rgba(91,214,138,.18); color:var(--vert); } .off { background:rgba(154,167,180,.15); color:var(--gris); }
</style></head>
<body>
<header>
  <h1>SatMe <span class="cyan">PC</span></h1>
  <span class="g" id="station"></span>
  <span class="g" id="cat-statut-court"></span>
  <span id="heure"></span>
</header>
<div class="grille">
  <!-- Passes -->
  <div class="col1">
    <div class="carte">
      <div class="t">Prochains passages (24 h)</div>
      <div class="ligne" style="margin-top:0">
        <input id="filtre" placeholder="Filtrer : ISS, SO-50, RS-44…">
        <label class="petit"><input type="checkbox" id="utc"> UTC</label>
      </div>
      <div class="etat" id="calcul"></div>
      <div id="liste"><table><thead><tr><th>Début</th><th>Satellite</th><th>El max</th><th>Durée</th><th></th></tr></thead><tbody id="passages"></tbody></table></div>
    </div>
  </div>

  <!-- The satellite followed -->
  <div class="col2">
    <div class="carte">
      <div class="t">Satellite suivi</div>
      <div class="ligne" style="margin:0 0 8px">
        <input id="cherche" list="tous" placeholder="Suivre un satellite : QO-100, AO-7…">
        <datalist id="tous"></datalist>
        <button onclick="choisitParNom()">Suivre</button>
      </div>
      <div class="paire"><span class="gros" id="nom">—</span><span id="compte" class="val ambre"></span></div>
      <div class="paire" style="margin-top:6px">
        <span>Az <span class="val cyan" id="az">—</span></span>
        <span>El <span class="val cyan" id="el">—</span></span>
        <span class="petit" id="dist"></span>
      </div>
      <svg id="ciel" viewBox="-110 -110 220 220" style="width:100%;max-width:460px;display:block;margin:10px auto 0" role="img" aria-label="Le ciel : passage et position du satellite"></svg>
      <div class="petit" id="infos-passage" style="text-align:center"></div>
    </div>
  </div>

  <!-- Frequencies, rig, mast -->
  <div class="col3">
    <div class="carte">
      <div class="t">Fréquences (Doppler)</div>
      <select id="emetteur" style="width:100%"></select>
      <div class="paire" style="margin-top:10px">
        <span>RX <span class="val cyan" id="dl">—</span></span>
        <span>TX <span class="val ambre" id="ul">—</span></span>
      </div>
      <div class="ligne" id="repos-ligne" style="display:none">
        <span class="petit">Dans le transpondeur</span>
        <div class="bouton-gr">
          <button onclick="repos(-5000)">−5 k</button><button onclick="repos(-1000)">−1 k</button>
          <button onclick="repos(-100)">−100</button><button onclick="repos(100)">+100</button>
          <button onclick="repos(1000)">+1 k</button><button onclick="repos(5000)">+5 k</button>
        </div>
      </div>
    </div>

    <div class="carte">
      <div class="t">Poste (CAT) <span id="cat-pastille" class="pastille off">déconnecté</span></div>
      <div class="ligne" style="margin-top:0">
        <select id="cat-modele">
          <option value="IC9700">Icom IC-9700</option><option value="IC9100">Icom IC-9100</option>
          <option value="IC910">Icom IC-910H</option><option value="TS2000">Kenwood TS-2000</option>
          <option value="PAIRE">Deux postes (FT-817 / IC-705)</option><option value="THD72">Kenwood TH-D72</option>
          <option value="SIM">IC-9700 simulé (rien n'est ouvert)</option><option value="TS2000SIM">TS-2000 simulé (rien n'est ouvert)</option>
        </select>
        <select id="cat-baud"><option value="0">vitesse du poste</option><option>115200</option><option>57600</option><option>38400</option><option>19200</option><option>9600</option><option>4800</option></select>
      </div>
      <div class="ligne"><span class="petit" id="cat-port-nom" style="min-width:34px"></span><select id="cat-port"></select>
        <select id="cat-rx-type" style="max-width:110px"><option value="0">FT-817</option><option value="1">IC-705</option></select>
        <button onclick="chargePorts()" title="Relire les ports">↻</button></div>
      <div class="ligne" id="cat-tx-ligne"><span class="petit" style="min-width:34px">TX</span><select id="cat-port-tx"></select>
        <select id="cat-tx-type" style="max-width:110px"><option value="0">FT-817</option><option value="1">IC-705</option></select></div>
      <div class="ligne" id="cat-bande-ligne"><span class="petit">Émission sur la bande</span>
        <select id="cat-bande"><option value="0">A</option><option value="1">B</option></select></div>
      <div class="ligne">
        <button class="primaire" id="cat-co" onclick="catConnecte()">Connecter</button>
        <button id="cat-deco" onclick="post('/api/cat/deconnecter')">Déconnecter</button>
        <label class="petit"><input type="checkbox" id="maintien" onchange="post('/api/maintien?on='+(this.checked?1:0))"> Doppler en pause</label>
      </div>
      <div class="avert" id="cat-avert"></div>
      <div class="paire" style="margin-top:8px">
        <span class="petit">Poste RX <span class="val" id="cat-dl">—</span></span>
        <span class="petit">TX <span class="val" id="cat-ul">—</span></span>
        <span class="petit" id="cat-mode"></span>
      </div>
      <div class="etat" id="cat-statut"></div>
    </div>

    <div class="carte">
      <div class="t">Rotor <span id="rot-pastille" class="pastille off">déconnecté</span></div>
      <div class="ligne" style="margin-top:0">
        <select id="rot-lien" onchange="lienRotor()">
          <option value="GS232">GS-232 (port série)</option><option value="ROTCTLD">rotctld (réseau)</option><option value="SIM">Rotor simulé</option>
        </select>
        <select id="rot-baud"><option>9600</option><option>4800</option><option>19200</option><option>115200</option></select>
      </div>
      <div class="ligne" id="rot-serie"><select id="rot-port"></select><button onclick="chargePorts()" title="Relire les ports">↻</button></div>
      <div class="ligne" id="rot-tcp" style="display:none"><input id="rot-hote" placeholder="hôte"><input id="rot-tcpport" placeholder="4533" style="max-width:90px"></div>
      <div class="ligne">
        <button class="primaire" onclick="rotConnecte()">Connecter</button>
        <button onclick="post('/api/rotor/deconnecter')">Déconnecter</button>
        <label class="petit"><input type="checkbox" id="rot-suit" onchange="post('/api/rotor/suivre?on='+(this.checked?1:0))"> Suivre le satellite</label>
      </div>
      <div class="ligne">
        <button onclick="post('/api/rotor/parquer')">Parquer</button>
        <button class="rouge" onclick="post('/api/rotor/stop')">Stop</button>
        <input id="rot-az" placeholder="az" style="max-width:70px"><input id="rot-el" placeholder="el" style="max-width:70px">
        <button onclick="post('/api/rotor/aller?az='+enc(v('rot-az'))+'&el='+enc(v('rot-el')))">Aller</button>
      </div>
      <div class="paire" style="margin-top:8px">
        <span class="petit">Mât <span class="val" id="rot-pos">—</span></span>
        <span class="petit">Cible <span class="val" id="rot-cible">—</span></span>
      </div>
      <div class="etat" id="rot-statut"></div>
      <div class="diag" id="rot-diag"></div>
      <details id="rot-reg" style="margin-top:8px"><summary class="petit" style="cursor:pointer">Réglages du rotor</summary>
        <div class="ligne"><span class="petit" style="min-width:120px">Azimut max</span><select id="r-maxAz"><option>360</option><option>450</option><option>540</option></select>
          <span class="petit">Élévation max</span><select id="r-maxEl"><option>90</option><option>180</option></select></div>
        <div class="ligne"><span class="petit" style="min-width:120px">Butée</span><select id="r-butee"><option value="NORTH">au nord</option><option value="SOUTH">au sud</option></select>
          <label class="petit"><input type="checkbox" id="r-depuisButee"> compte depuis la butée</label></div>
        <div class="ligne"><label class="petit"><input type="checkbox" id="r-flip"> retournement (flip)</label>
          <label class="petit"><input type="checkbox" id="r-azSeul"> azimut seul</label></div>
        <div class="ligne"><span class="petit" style="min-width:120px">Parking az / el</span><input id="r-parkAz" style="max-width:70px"><input id="r-parkEl" style="max-width:70px"></div>
        <div class="ligne"><span class="petit" style="min-width:120px">Élévation min</span><input id="r-minEl" style="max-width:70px">
          <span class="petit">Écart avant de bouger</span><input id="r-ecart" style="max-width:60px"></div>
        <div class="ligne"><span class="petit" style="min-width:120px">Attente avant AOS (min)</span><input id="r-avantAos" style="max-width:60px">
          <button onclick="reglagesRotor()">Appliquer</button></div>
      </details>
    </div>

    <div class="carte">
      <div class="t">Station</div>
      <div class="ligne" style="margin-top:0">
        <input id="indicatif" placeholder="Indicatif" style="text-transform:uppercase">
        <input id="locator" placeholder="Locator (JN18FS)" style="text-transform:uppercase">
        <button onclick="station()">Enregistrer</button>
      </div>
      <details id="conv" style="margin-top:8px"><summary class="petit" style="cursor:pointer">Convertisseurs (LNB, transverter)</summary>
        <div class="ligne"><span class="petit" style="min-width:150px">Réception : OL (MHz)</span><input id="c-rxOl" placeholder="9750 ; 0 = aucun" style="max-width:130px">
          <label class="petit"><input type="checkbox" id="c-rxInv"> inverseur</label></div>
        <div class="ligne"><span class="petit" style="min-width:150px">Émission : OL (MHz)</span><input id="c-txOl" placeholder="0 = aucun" style="max-width:130px">
          <label class="petit"><input type="checkbox" id="c-txInv"> inverseur</label>
          <button onclick="convertisseurs()">Appliquer</button></div>
        <div class="petit">Le poste reçoit et émet sur la fréquence intermédiaire ; l'affichage reste celle du satellite.</div>
      </details>
      <div class="ligne"><button onclick="post('/api/catalogue')">Retélécharger les éléments AMSAT</button></div>
      <div class="petit" style="margin-top:10px">Reprendre les réglages du téléphone (Réglages → Sauvegarde, fichier .json) :</div>
      <input type="file" id="import" accept=".json,application/json" onchange="importe(this)" style="width:100%;margin-top:4px">
      <div class="etat" id="catalogue"></div>
    </div>
  </div>
</div>
<script>
const g = id => document.getElementById(id);
const v = id => g(id).value.trim();
const enc = encodeURIComponent;
let E = null, P = [], ports = [], dernierEmetteurs = '';
const utc = () => g('utc').checked;
try { g('utc').checked = localStorage.getItem('utc') === '1'; } catch (x) {}
g('utc').onchange = () => { try { localStorage.setItem('utc', utc() ? '1' : '0'); } catch (x) {} ; dessinePassages(); };
g('filtre').oninput = dessinePassages;

async function post(u) {
  try { const r = await fetch(u, { method:'POST' }); const j = await r.json(); if (j.ok === false && j.erreur) alert(j.erreur); setTimeout(tic, 150); return j; }
  catch (x) { return null; }
}
function hm(ms, s) {
  const d = new Date(ms);
  const f = utc() ? [d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds()] : [d.getHours(), d.getMinutes(), d.getSeconds()];
  return f.slice(0, s ? 3 : 2).map(n => String(n).padStart(2, '0')).join(':');
}
function duree(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), r = s % 60;
  return h > 0 ? h + ' h ' + String(m).padStart(2, '0') : m + ':' + String(r).padStart(2, '0');
}
function mhz(hz) { return hz == null ? '—' : (hz / 1e6).toFixed(hz % 1000 === 0 ? 3 : 4); }
function txt(id, t) { const e = g(id); if (e.textContent !== t) e.textContent = t; }

function dessinePassages() {
  const f = g('filtre').value.trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
  const n = Date.now();
  const suivi = E && E.suivi ? E.suivi.catnum : -1;
  const lignes = P.filter(p => !f || p.nom.toUpperCase().replace(/[^A-Z0-9]/g, '').includes(f)).slice(0, 150).map(p => {
    const enCours = n >= p.aos && n <= p.los;
    const cl = 'p' + (enCours ? ' encours' : '') + (p.catnum === suivi ? ' choisi' : '');
    const quand = enCours ? 'en cours' : 'dans ' + duree(p.aos - n);
    return '<tr class="' + cl + '" onclick="suivre(' + p.catnum + ')"><td>' + hm(p.aos) + '</td><td>' + p.nom.replace(/</g, '&lt;') +
      '</td><td>' + Math.round(p.elMax) + '°</td><td>' + duree(p.los - p.aos) + '</td><td class="petit">' + quand + '</td></tr>';
  });
  g('passages').innerHTML = lignes.join('') || '<tr><td colspan="5" class="petit">' + (E && E.locator ? 'aucun passage' : 'renseigne ton locator') + '</td></tr>';
}
async function suivre(n) { await post('/api/suivre?catnum=' + n); }
let tous = [];
async function chargeSatellites() {
  try { tous = await (await fetch('/api/satellites')).json(); } catch (x) { tous = []; }
  g('tous').innerHTML = tous.map(s => '<option value="' + s.nom.replace(/"/g, '&quot;') + '">').join('');
}
function choisitParNom() {
  const q = v('cherche').toUpperCase().replace(/[^A-Z0-9]/g, '');
  const s = tous.find(x => x.nom.toUpperCase().replace(/[^A-Z0-9]/g, '') === q) ||
            tous.find(x => x.nom.toUpperCase().replace(/[^A-Z0-9]/g, '').includes(q)) ||
            tous.find(x => String(x.catnum) === v('cherche'));
  if (!s) { alert('Satellite inconnu du bulletin AMSAT.'); return; }
  g('cherche').value = '';
  suivre(s.catnum);
}
g('cherche').addEventListener('keydown', e => { if (e.key === 'Enter') choisitParNom(); });
async function repos(d) { await post('/api/repos?d=' + d); }

function polaire(az, el) {
  const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90);
  const a = az * Math.PI / 180;
  return [r * Math.sin(a), -r * Math.cos(a)];
}
function dessineCiel() {
  let s = '';
  for (const el of [0, 30, 60]) s += '<circle r="' + (100 * (1 - el / 90)) + '" fill="none" stroke="#22303B"/>';
  s += '<line x1="-100" y1="0" x2="100" y2="0" stroke="#22303B"/><line x1="0" y1="-100" x2="0" y2="100" stroke="#22303B"/>';
  for (const [t, x, y] of [['N', 0, -103], ['E', 104, 4], ['S', 0, 110], ['O', -104, 4]])
    s += '<text x="' + x + '" y="' + y + '" fill="#9AA7B4" font-size="8" text-anchor="middle">' + t + '</text>';
  if (E && E.arc && E.arc.length > 1) {
    s += '<polyline fill="none" stroke="#3FE0C8" stroke-opacity=".55" stroke-width="1.6" points="' +
      E.arc.map(p => polaire(p[0], p[1]).map(x => x.toFixed(1)).join(',')).join(' ') + '"/>';
    const a = polaire(E.arc[0][0], E.arc[0][1]);
    s += '<text x="' + a[0].toFixed(1) + '" y="' + (a[1] - 4).toFixed(1) + '" fill="#3FE0C8" font-size="7" text-anchor="middle">AOS</text>';
  }
  if (E && E.rotor && E.rotor.az != null) {
    const p = polaire(E.rotor.az, E.rotor.el == null ? 0 : E.rotor.el);
    s += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="5" fill="none" stroke="#FFB454" stroke-width="1.5"/>';
  }
  if (E && E.position && E.position.el >= 0) {
    const p = polaire(E.position.az, E.position.el);
    s += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="4" fill="#3FE0C8"/>';
  }
  g('ciel').innerHTML = s;
}

function dessine() {
  if (!E) return;
  const n = Date.now();
  txt('heure', hm(n, true) + (utc() ? ' UTC' : ''));
  txt('station', [E.indicatif, E.locator].filter(x => x).join(' · ') + ' · ' + E.nbSatellites + ' satellites');
  txt('calcul', E.calcul ? 'calcul des passages…' : '');
  txt('catalogue', E.catalogue);
  if (document.activeElement !== g('indicatif') && !g('indicatif').value) g('indicatif').value = E.indicatif || '';
  if (document.activeElement !== g('locator') && !g('locator').value) g('locator').value = E.locator || '';
  txt('nom', E.suivi ? E.suivi.nom : '— choisis un passage');
  const p = E.position;
  txt('az', p ? p.az.toFixed(1) + '°' : '—');
  txt('el', p ? p.el.toFixed(1) + '°' : '—');
  txt('dist', p ? Math.round(p.distance) + ' km · ' + (p.vitesse > 0 ? 's’éloigne ' : 's’approche ') + Math.abs(p.vitesse).toFixed(2) + ' km/s' : '');
  const pa = E.passage;
  if (pa) {
    const enCours = n >= pa.aos && n <= pa.los;
    // "in", or 12:51 would read as a time of day.
    txt('compte', enCours ? 'LOS dans ' + duree(pa.los - n) : 'AOS dans ' + duree(pa.aos - n));
    txt('infos-passage', 'AOS ' + hm(pa.aos) + ' (' + Math.round(pa.azAos) + '°) · El max ' + Math.round(pa.elMax) + '° · LOS ' + hm(pa.los) + ' (' + Math.round(pa.azLos) + '°)');
  } else { txt('compte', ''); txt('infos-passage', ''); }
  dessineCiel();
  // Transmitters: the list rebuilt only when it changes (an open select would close).
  const cle = JSON.stringify(E.emetteurs.map(t => t.desc)) + (E.suivi ? E.suivi.catnum : '');
  if (cle !== dernierEmetteurs) {
    dernierEmetteurs = cle;
    g('emetteur').innerHTML = E.emetteurs.length ? E.emetteurs.map((t, i) =>
      '<option value="' + i + '">' + (t.desc + (t.mode ? ' · ' + t.mode : '')).replace(/</g, '&lt;') + '</option>').join('')
      : '<option>' + (E.suivi ? 'aucun émetteur actif connu' : '—') + '</option>';
  }
  if (document.activeElement !== g('emetteur')) g('emetteur').value = String(E.emetteur);
  const t = E.emetteurs[E.emetteur];
  g('repos-ligne').style.display = t && t.lineaire ? '' : 'none';
  txt('dl', mhz(E.ciel.dl)); txt('ul', mhz(E.ciel.ul));
  // CAT
  const c = E.cat;
  g('cat-pastille').className = 'pastille ' + (c.connecte ? 'on' : 'off');
  txt('cat-pastille', c.connecte ? (c.simule ? 'simulé' : 'connecté') : 'déconnecté');
  txt('cat-statut', c.statut);
  txt('cat-statut-court', c.connecte ? 'CAT ' + c.modele + (c.simule ? ' (simulé)' : '') : '');
  txt('cat-dl', mhz(c.dl)); txt('cat-ul', mhz(c.ul));
  txt('cat-mode', (c.mode || '') + (c.modeEcart ? ' (corrigé)' : '') + (c.connecte ? (c.pilote ? ' · Doppler' : ' · bouton') : ''));
  g('maintien').checked = c.maintien;
  // Rotor
  const r = E.rotor;
  g('rot-pastille').className = 'pastille ' + (r.connecte ? 'on' : 'off');
  txt('rot-pastille', r.connecte ? (r.simule ? 'simulé' : 'connecté') : 'déconnecté');
  txt('rot-statut', r.statut + (r.attente ? ' · attend le lever' : ''));
  txt('rot-pos', r.az == null ? '—' : Math.round(r.az) + '° / ' + Math.round(r.el || 0) + '°');
  txt('rot-cible', r.azCible == null ? '—' : Math.round(r.azCible) + '° / ' + Math.round(r.elCible || 0) + '°');
  txt('rot-diag', (r.diag || []).join('\n'));
  if (document.activeElement !== g('rot-suit')) g('rot-suit').checked = r.suit;
  // The mast's settings: filled from the station while the panel is closed (never under the fingers).
  if (!g('rot-reg').open && r.reglages) {
    const s = r.reglages;
    g('r-maxAz').value = String(s.maxAz); g('r-maxEl').value = String(s.maxEl); g('r-butee').value = s.butee;
    g('r-depuisButee').checked = s.depuisButee; g('r-flip').checked = s.flip; g('r-azSeul').checked = s.azSeul;
    g('r-parkAz').value = s.parkAz; g('r-parkEl').value = s.parkEl; g('r-minEl').value = s.minEl;
    g('r-ecart').value = s.ecart; g('r-avantAos').value = s.avantAos;
  }
  if (!g('conv').open && E.convertisseurs) {
    const k = E.convertisseurs, mhzOl = x => x ? String(x / 1e6) : '0';
    g('c-rxOl').value = mhzOl(k.rxOl); g('c-rxInv').checked = k.rxInv;
    g('c-txOl').value = mhzOl(k.txOl); g('c-txInv').checked = k.txInv;
  }
  // The rig's model and port as last used, until the operator picks others.
  if (!catTouche) {
    g('cat-modele').value = c.simule ? (c.modele === 'TS2000' ? 'TS2000SIM' : 'SIM') : c.modele;
    if (!g('cat-modele').value) g('cat-modele').value = 'IC9700';
    if (ports.length && c.port >= 0 && c.port < ports.length) g('cat-port').value = String(c.port);
    avertCat();
  }
  if (!rotTouche && ports.length && r.port >= 0 && r.port < ports.length) g('rot-port').value = String(r.port);
}
let catTouche = false, rotTouche = false;
['cat-modele', 'cat-port', 'cat-baud', 'cat-port-tx', 'cat-rx-type', 'cat-tx-type', 'cat-bande'].forEach(i => g(i).addEventListener('change', () => catTouche = true));
['rot-port', 'rot-baud', 'rot-lien'].forEach(i => g(i).addEventListener('change', () => rotTouche = true));
function convertisseurs() {
  post('/api/convertisseurs?rxOl=' + enc(v('c-rxOl')) + '&rxInv=' + (g('c-rxInv').checked ? 1 : 0) +
       '&txOl=' + enc(v('c-txOl')) + '&txInv=' + (g('c-txInv').checked ? 1 : 0));
  g('conv').open = false;
}
function reglagesRotor() {
  const b = id => g(id).checked ? 1 : 0;
  post('/api/rotor/reglages?maxAz=' + g('r-maxAz').value + '&maxEl=' + g('r-maxEl').value + '&butee=' + g('r-butee').value +
       '&depuisButee=' + b('r-depuisButee') + '&flip=' + b('r-flip') + '&azSeul=' + b('r-azSeul') +
       '&parkAz=' + enc(v('r-parkAz')) + '&parkEl=' + enc(v('r-parkEl')) + '&minEl=' + enc(v('r-minEl')) +
       '&ecart=' + enc(v('r-ecart')) + '&avantAos=' + enc(v('r-avantAos')));
  g('rot-reg').open = false;
}
async function importe(input) {
  const f = input.files[0]; if (!f) return;
  try {
    const r = await fetch('/api/import', { method:'POST', body: await f.text() });
    const j = await r.json();
    alert(j.ok ? j.n + ' réglages repris du téléphone (ports USB exceptés ; le rotor attend que tu coches « Suivre »).' : 'Import impossible : ' + j.erreur);
  } catch (x) { alert('Import impossible.'); }
  input.value = ''; g('indicatif').value = ''; g('locator').value = '';
  setTimeout(() => { tic(); chargeListe(); }, 1500);
}

async function tic() {
  try { E = await (await fetch('/api/etat')).json(); dessine(); } catch (x) {}
}
async function chargeListe() {
  try { P = (await (await fetch('/api/passages')).json()).liste; dessinePassages(); } catch (x) {}
}
async function chargePorts() {
  try { ports = await (await fetch('/api/ports')).json(); } catch (x) { ports = []; }
  const o = ports.length ? ports.map(p => '<option value="' + p.index + '">' + p.libelle.replace(/</g, '&lt;') + '</option>').join('')
                         : '<option value="-1">aucun port série</option>';
  const a = g('cat-port').value, b = g('rot-port').value, c = g('cat-port-tx').value;
  g('cat-port').innerHTML = '<option value="-1">aucun</option>' + o; g('rot-port').innerHTML = o;
  g('cat-port-tx').innerHTML = '<option value="-1">aucun</option>' + o;
  if (a) g('cat-port').value = a;
  if (c) g('cat-port-tx').value = c;
  if (b) g('rot-port').value = b; else if (E && E.rotor) g('rot-port').value = String(E.rotor.port);
}
function avertCat() {
  const m = g('cat-modele').value;
  const paire = m === 'PAIRE';
  g('cat-tx-ligne').style.display = paire ? '' : 'none';
  g('cat-rx-type').style.display = paire ? '' : 'none';
  g('cat-bande-ligne').style.display = m === 'THD72' ? '' : 'none';
  txt('cat-port-nom', paire ? 'RX' : '');
  txt('cat-avert', m === 'TS2000' ? 'TS-2000 : RTS/DTR tenus bas, aucun ordre d’émission. RigExpert : le port A (CAT), jamais le B (PTT). Vitesse : menu 56 (57600 par défaut ici).'
    : m === 'PAIRE' ? 'Un poste par côté : RX (descente) et TX (montée) ; un côté peut rester vide (« aucun »). FT-817 en 8N2, vitesse de son menu CAT.'
    : m === 'THD72' ? 'TH-D72 en duplex : une bande émet, l’autre reçoit. Son câble demande RTS/DTR levés.'
    : m.indexOf('SIM') >= 0 ? '' : 'Le CAT Icom lève RTS/DTR : choisis bien le port du poste, jamais celui d’un autre poste.');
}
g('cat-modele').onchange = avertCat;
function catConnecte() {
  const m = g('cat-modele').value;
  if (m.indexOf('SIM') < 0 && m !== 'PAIRE' && (!ports.length || g('cat-port').value === '-1')) { alert('Aucun port série choisi.'); return; }
  post('/api/cat/connecter?modele=' + m + '&port=' + enc(g('cat-port').value) + '&baud=' + g('cat-baud').value +
       '&portTx=' + enc(g('cat-port-tx').value) + '&rxIc705=' + g('cat-rx-type').value + '&txIc705=' + g('cat-tx-type').value +
       '&bandeTx=' + g('cat-bande').value);
}
function lienRotor() {
  const l = g('rot-lien').value;
  g('rot-serie').style.display = l === 'GS232' ? '' : 'none';
  g('rot-tcp').style.display = l === 'ROTCTLD' ? '' : 'none';
}
function rotConnecte() {
  const l = g('rot-lien').value;
  if (l === 'GS232' && (!ports.length || g('rot-port').value === '-1')) { alert('Aucun port série choisi.'); return; }
  post('/api/rotor/connecter?lien=' + (l === 'ROTCTLD' ? 'ROTCTLD' : 'GS232') + '&sim=' + (l === 'SIM' ? 1 : 0) +
       '&port=' + enc(g('rot-port').value) + '&baud=' + g('rot-baud').value + '&hote=' + enc(v('rot-hote')) + '&tcp=' + enc(v('rot-tcpport')));
}
async function station() {
  await post('/api/indicatif?c=' + enc(v('indicatif')));
  const j = await post('/api/locator?l=' + enc(v('locator')));
  if (j && j.ok === false) alert('Locator non reconnu.');
  setTimeout(chargeListe, 1500);
}
g('emetteur').onchange = () => post('/api/emetteur?i=' + g('emetteur').value);
lienRotor(); avertCat();
tic(); chargeListe(); chargePorts(); chargeSatellites();
setInterval(chargeSatellites, 600000);
setInterval(tic, 1000);
setInterval(chargeListe, 30000);
setInterval(dessinePassages, 5000);
</script>
</body></html>
"""
}
