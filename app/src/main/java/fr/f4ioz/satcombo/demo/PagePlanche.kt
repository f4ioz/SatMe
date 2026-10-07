/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.t

/**
 * The SSTV sheet, on the PC screen: the template large, its boxes and texts
 * moved with the mouse, the pictures dragged in, the texts typed.
 *
 * **What is shown is the phone's drawing.** The page lays boxes and frames
 * over the preview the phone draws ([PlancheWeb]); it never draws a sheet of
 * its own, so what is downloaded is what was seen.
 *
 * **Nothing from outside**: no library, no font fetched — the PC and the
 * phone may share no internet at all (a USB cable).
 */
object PagePlanche {

    private val CLES = listOf(
        "titre", "sous", "code", "entrer", "code_faux", "modele", "grille", "tour", "importer", "import_en_cours",
        "import_ok", "import_echec", "images", "disposition", "textes", "tous", "remplir", "vider_case", "tout_vider",
        "aide_images", "direct", "redecodee", "partielle", "serie", "serie_complete", "legende", "numeros",
        "ajouter_case", "retirer_case", "aide_dispo", "position", "nom_modele", "indicatif", "nom", "locator", "dates",
        "titre_planche", "ajouter_texte", "retirer_texte", "texte_choisi", "couleur", "fond", "sans", "voile_blanc",
        "voile_noir", "libre", "champ_indicatif", "champ_nom_locator", "champ_dates", "champ_titre", "champ_libre",
        "telecharger", "fabrication", "enregistre", "hors_ligne", "aucun_modele", "aucune_image", "choisir_case",
        "par_defaut", "pupitre", "journal")

    private fun js(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '<' -> append("\\u003c")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    /** The page in the app's language. */
    fun html(): String = HTML
        .replace("/*T*/", CLES.joinToString(",") { "\"$it\":\"${js(t("pw_$it"))}\"" })
        .replace("lang=\"fr\"", "lang=\"${if (I18n.current() == fr.f4ioz.satcombo.i18n.Lang.EN) "en" else "fr"}\"")

    private val HTML = """
<!doctype html>
<html lang="fr">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SatMe — SSTV</title>
<style>
  :root { --fond:#0B1016; --carte:#131C24; --bord:#22303B; --cyan:#3FE0C8;
          --ambre:#FFB454; --rose:#E5484D; --gris:#9AA7B4; --clair:#E8F0F7; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:14px;
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { display:flex; gap:10px; align-items:center; flex-wrap:wrap; padding:12px 18px;
           border-bottom:1px solid var(--bord); }
  h1 { font-size:18px; margin:0 12px 0 0; }
  .sous { color:var(--gris); font-size:12px; padding:6px 18px 0; }
  input, select, button, textarea { font-size:14px; font-family:inherit; border-radius:8px;
         border:1px solid var(--bord); background:#0E1720; color:var(--clair); padding:7px 9px; }
  input:focus, select:focus { outline:2px solid var(--cyan); }
  button { cursor:pointer; font-weight:600; }
  button.primaire { background:var(--cyan); color:#06131A; border:none; }
  button.discret { color:var(--cyan); }
  button.rouge { color:var(--rose); }
  .corps { display:grid; grid-template-columns:minmax(0,1fr) 420px; gap:16px; padding:14px 18px; }
  @media (max-width:1000px) { .corps { grid-template-columns:1fr; } }
  /* The sheet seen whole, as a poster in its frame: never taller than the screen. */
  #cadre { background:#1A232C; border:1px solid var(--bord); border-radius:12px; padding:22px;
           display:flex; justify-content:center; }
  #scene { position:relative; background:#000; overflow:visible; user-select:none; touch-action:none;
           width:min(100%, calc((100vh - 230px) * var(--ratio, 1.414)));
           box-shadow:0 0 0 1px #000, 0 10px 34px rgba(0,0,0,.65); }
  #apercu { position:absolute; inset:0; width:100%; height:100%; }
  #calque { position:absolute; inset:0; }
  .zone { position:absolute; border:1.5px dashed rgba(63,224,200,.55); cursor:move; }
  .zone.texte { border-color:rgba(255,180,84,.6); }
  .zone.choisie { border:2.5px solid var(--cyan); background:rgba(63,224,200,.10); }
  .zone.texte.choisie { border-color:var(--ambre); background:rgba(255,180,84,.10); }
  .zone.survol { background:rgba(63,224,200,.30); }
  .etiq { position:absolute; left:2px; top:2px; font-size:11px; font-weight:700; padding:0 5px;
          border-radius:4px; background:rgba(0,0,0,.6); color:var(--clair); pointer-events:none; }
  /* A text's label hides the text itself: shown above it, only when pointed at or picked. */
  .zone.texte .etiq { display:none; top:-20px; left:0; white-space:nowrap; }
  .zone.texte:hover .etiq, .zone.texte.choisie .etiq { display:block; }
  .poignee { position:absolute; right:-6px; bottom:-6px; width:14px; height:14px; border-radius:3px;
             background:var(--cyan); cursor:nwse-resize; display:none; }
  .zone.texte .poignee { background:var(--ambre); }
  .zone.choisie .poignee { display:block; }
  .suivi { margin-top:8px; font-weight:700; }
  .actions { display:flex; gap:8px; flex-wrap:wrap; margin-top:10px; align-items:center; }
  .panneau { background:var(--carte); border:1px solid var(--bord); border-radius:12px; padding:12px;
             max-height:calc(100vh - 120px); overflow:auto; }
  .onglets { display:flex; gap:6px; margin-bottom:10px; }
  .onglets button.actif { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .aide { color:var(--gris); font-size:12px; margin:6px 0; }
  .galerie { display:grid; grid-template-columns:1fr 1fr; gap:8px; }
  .img { background:#0E1720; border:2px solid transparent; border-radius:8px; overflow:hidden; cursor:grab; position:relative; }
  .img.placee { border-color:var(--cyan); }
  .img img { width:100%; aspect-ratio:4/3; object-fit:cover; display:block; pointer-events:none; }
  .img .l1 { font-weight:700; font-size:12px; padding:3px 6px 0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .img .l2 { color:var(--gris); font-size:11px; padding:0 6px 4px; }
  .img .num { position:absolute; left:4px; top:4px; background:var(--cyan); color:#06131A; font-weight:800;
              font-size:12px; padding:0 6px; border-radius:5px; }
  .champ { display:flex; flex-direction:column; gap:3px; margin:8px 0; }
  .champ label { color:var(--gris); font-size:12px; }
  .rangee { display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
  .rangee input[type=number] { width:80px; }
  .nuancier { display:flex; gap:6px; }
  .nuance { width:26px; height:26px; border-radius:6px; border:2px solid var(--bord); cursor:pointer; }
  .nuance.choisie { border-color:var(--cyan); }
  #message { color:var(--ambre); font-size:13px; }
  #porte { max-width:420px; margin:60px auto; background:var(--carte); border:1px solid var(--bord);
           border-radius:14px; padding:22px; }
  #porte input { font-size:28px; letter-spacing:6px; width:100%; text-align:center; }
  .cache { display:none !important; }
</style>
<body>
<div id="porte" class="cache">
  <h1 id="tPorte"></h1>
  <p class="aide" id="tCode"></p>
  <input id="code" inputmode="numeric" maxlength="6" autocomplete="off">
  <div class="actions"><button class="primaire" id="entrer"></button><span id="porteMsg"></span></div>
</div>
<div id="editeur" class="cache">
<header>
  <h1 id="tTitre"></h1>
  <a id="pupitre" href="./" style="color:var(--cyan)"></a>
  <a id="journal" href="journal" style="color:var(--cyan)"></a>
  <label id="tModele"></label><select id="modeles"></select>
  <button class="discret" id="grille"></button>
  <button class="discret" id="tour"></button>
  <button class="discret" id="importer"></button>
  <input type="file" id="fichier" accept="image/*" class="cache">
  <button class="primaire" id="telecharger"></button>
  <span id="message"></span>
</header>
<div class="sous" id="tSous"></div>
<div class="corps">
  <div>
    <div id="cadre"><div id="scene"><img id="apercu" alt=""><div id="calque"></div></div></div>
    <div class="suivi" id="suivi"></div>
    <div class="actions"><span class="aide" id="etat"></span></div>
  </div>
  <div class="panneau">
    <div class="onglets"><button id="oImages"></button><button id="oDispo"></button><button id="oTextes"></button></div>
    <div id="pImages"></div>
    <div id="pDispo" class="cache"></div>
    <div id="pTextes" class="cache"></div>
  </div>
</div>
</div>
<script>
const T = {/*T*/};
function tf(k) { let s = T[k] || k; for (let i = 1; i < arguments.length; i++) s = s.split('{' + (i - 1) + '}').join(arguments[i]); return s; }
function g(id) { return document.getElementById(id); }
function el(tag, attrs, kids) {
  const e = document.createElement(tag);
  for (const k in (attrs || {})) { if (k === 'text') e.textContent = attrs[k]; else if (k.startsWith('on')) e.addEventListener(k.slice(2), attrs[k]); else e.setAttribute(k, attrs[k]); }
  (kids || []).forEach(c => e.appendChild(c));
  return e;
}
const base = location.pathname.replace(/\/planche\/?$/, '');
let cle = localStorage.getItem('satme-cle') || '';
function url(r, p) { const q = new URLSearchParams(p || {}); q.set('cle', cle); return base + '/planche' + r + '?' + q.toString(); }
async function lire(r, p) {
  let x;
  try { x = await fetch(url(r, p)); } catch (e) { g('message').textContent = T.hors_ligne; throw e; }
  if (x.status === 403) { porte(); throw 'session'; }
  return x.json();
}

// ------------------------------------------------------------ the door
function porte() {
  localStorage.removeItem('satme-cle'); cle = '';
  g('editeur').classList.add('cache'); g('porte').classList.remove('cache'); g('code').focus();
}
async function entrer() {
  const r = await fetch(base + '/entrer?code=' + encodeURIComponent(g('code').value));
  const j = await r.json();
  if (j.ok) { cle = j.cle; localStorage.setItem('satme-cle', cle); demarre(); }
  else g('porteMsg').textContent = T.code_faux;
}

// --------------------------------------------- the template, as the phone writes it
function unesc(s) { let b = '', i = 0; while (i < s.length) { const c = s[i]; if (c === '\\' && i + 1 < s.length) { b += s[i + 1] === 'n' ? '\n' : s[i + 1]; i += 2; } else { b += c; i++; } } return b; }
function esc(s) { return String(s).split('\\').join('\\\\').split('\n').join('\\n').split('\t').join(' '); }
function r5(v) { return Math.round(v * 100000) / 100000; }
function litModele(txt) {
  const m = { nom: '', fond: '', ratio: 1.414, legende: false, numeros: false, titre: '', cases: [], textes: [], images: {} };
  txt.split('\n').forEach(l => {
    const i = l.indexOf('='); if (i < 0) return;
    const k = l.slice(0, i), v = l.slice(i + 1);
    if (k === 'nom') m.nom = unesc(v); else if (k === 'fond') m.fond = unesc(v);
    else if (k === 'ratio') m.ratio = parseFloat(v) || 1.414;
    else if (k === 'legende') m.legende = v === '1'; else if (k === 'numeros') m.numeros = v === '1';
    else if (k === 'titre') m.titre = unesc(v);
    else if (k === 'case') { const p = v.split(';').map(Number); m.cases.push({ x: p[0], y: p[1], w: p[2], h: p[3] }); }
    else if (k === 'texte') { const p = v.split(';'); m.textes.push({ champ: p[0], x: +p[1], y: +p[2], w: +p[3], h: +p[4], couleur: parseInt(p[5]), fond: parseInt(p[6]), libre: unesc(p.slice(7).join(';')) }); }
    else if (k === 'image') { const j = v.indexOf(';'); m.images[+v.slice(0, j)] = v.slice(j + 1); }
  });
  return m;
}
function ecrit(m) {
  let s = 'planche=1\nnom=' + esc(m.nom) + '\nfond=' + esc(m.fond) + '\nratio=' + m.ratio +
    '\nlegende=' + (m.legende ? 1 : 0) + '\nnumeros=' + (m.numeros ? 1 : 0) + '\ntitre=' + esc(m.titre) + '\n';
  m.cases.forEach(z => s += 'case=' + [r5(z.x), r5(z.y), r5(z.w), r5(z.h)].join(';') + '\n');
  m.textes.forEach(t => s += 'texte=' + [t.champ, r5(t.x), r5(t.y), r5(t.w), r5(t.h), t.couleur | 0, t.fond | 0, esc(t.libre).split(';').join(',')].join(';') + '\n');
  Object.keys(m.images).forEach(i => s += 'image=' + i + ';' + m.images[i] + '\n');
  return s;
}

// ------------------------------------------------------------ state
const COULEURS = [0xFFFFFFFF | 0, 0xFF000000 | 0, 0xFF0B2D5B | 0, 0xFFFFC21A | 0, 0xFF00E5FF | 0, 0xFFE5484D | 0];
const FONDS = [0, 0xE6F2F4F8 | 0, 0x99000000 | 0];
const CHAMPS = ['INDICATIF', 'NOM_LOCATOR', 'DATES', 'TITRE', 'LIBRE'];
let id = '', M = null, ratio = 1.414, galerie = [], choix = null, onglet = 'images', filtre = '';
let defauts = { indicatif: '', nom: '', locator: '', dates: '' };
const saisie = { ind: '', nom: '', loc: '', dates: '' };
function css(v) { const u = v >>> 0; return 'rgba(' + ((u >>> 16) & 255) + ',' + ((u >>> 8) & 255) + ',' + (u & 255) + ',' + (((u >>> 24) & 255) / 255).toFixed(3) + ')'; }
function zoneDe(c) { return c == null ? null : (c.k === 'c' ? M.cases[c.i] : M.textes[c.i]); }
function borne(z) { z.w = Math.min(1, Math.max(0.02, z.w)); z.h = Math.min(1, Math.max(0.02, z.h)); z.x = Math.min(1 - z.w, Math.max(0, z.x)); z.y = Math.min(1 - z.h, Math.max(0, z.y)); }
function heure(t) { const d = new Date(t); const p = n => String(n).padStart(2, '0'); return p(d.getUTCDate()) + '/' + p(d.getUTCMonth() + 1) + ' ' + p(d.getUTCHours()) + ':' + p(d.getUTCMinutes()) + 'Z'; }

// ----------------------------------------------------- saving and the preview
let minuteur = null, apercuMinuteur = null;
function change(sansSauver) {
  dessineCalque(); suivi(); panneau();
  if (sansSauver) { apercuBientot(); return; }
  clearTimeout(minuteur);
  minuteur = setTimeout(sauve, 600);
}
async function sauve() {
  if (!M) return;
  try {
    const p = { id: id }; if (saisie.nom) p.nom_station = saisie.nom;
    const r = await fetch(url('/enregistre', p), { method: 'POST', body: ecrit(M) });
    if (r.status === 403) { porte(); return; }
    g('etat').textContent = T.enregistre + ' · ' + new Date().toLocaleTimeString();
  } catch (e) { g('message').textContent = T.hors_ligne; return; }
  apercu();
  // What the texts say by default follows the pictures placed (dates, title, locator).
  try {
    const j = await lire('/modele', { id: id });
    defauts.locator = j.locator; defauts.dates = j.dates; defauts.titre = j.titre;
    if (onglet === 'textes' && !['INPUT', 'SELECT'].includes(document.activeElement.tagName)) panneau();
  } catch (e) {}
}
function apercuBientot() { clearTimeout(apercuMinuteur); apercuMinuteur = setTimeout(apercu, 400); }
function valeurs() { const p = {}; for (const k in saisie) if (saisie[k]) p[k] = saisie[k]; return p; }
function apercu() {
  const w = Math.min(1800, Math.round(g('scene').clientWidth * (window.devicePixelRatio || 1)));
  const p = Object.assign({ id: id, w: w, v: Date.now() }, valeurs());
  const im = new Image();
  im.onload = () => { g('apercu').src = im.src; };
  im.src = url('/apercu', p);
}

// ------------------------------------------------- boxes and texts over the preview
function dessineCalque() {
  const c = g('calque'); c.innerHTML = '';
  if (!M) return;
  const n = M.cases.length;
  M.cases.forEach((z, i) => c.appendChild(zoneEl(z, 'c', i, (i + 1) + '/' + n)));
  M.textes.forEach((z, i) => c.appendChild(zoneEl(z, 't', i, T['champ_' + z.champ.toLowerCase()] || z.champ)));
}
function zoneEl(z, k, i, etiquette) {
  const d = el('div', { class: 'zone' + (k === 't' ? ' texte' : '') + (choix && choix.k === k && choix.i === i ? ' choisie' : '') });
  place(d, z);
  d.appendChild(el('span', { class: 'etiq', text: etiquette }));
  const h = el('div', { class: 'poignee' }); d.appendChild(h);
  d.addEventListener('pointerdown', ev => debutGeste(ev, k, i, ev.target === h));
  if (k === 'c') {
    d.addEventListener('dragover', ev => { ev.preventDefault(); d.classList.add('survol'); });
    d.addEventListener('dragleave', () => d.classList.remove('survol'));
    d.addEventListener('drop', ev => { ev.preventDefault(); d.classList.remove('survol'); metImage(i, ev.dataTransfer.getData('text/plain')); });
  }
  return d;
}
function place(d, z) { d.style.left = (z.x * 100) + '%'; d.style.top = (z.y * 100) + '%'; d.style.width = (z.w * 100) + '%'; d.style.height = (z.h * 100) + '%'; }
let geste = null;
function debutGeste(ev, k, i, agrandit) {
  ev.preventDefault(); ev.stopPropagation();
  choix = { k: k, i: i }; dessineCalque(); panneau();
  const z = zoneDe(choix), r = g('scene').getBoundingClientRect();
  geste = { x0: ev.clientX, y0: ev.clientY, z0: Object.assign({}, z), r: r, agrandit: agrandit, bouge: false };
}
window.addEventListener('pointermove', ev => {
  if (!geste) return;
  const z = zoneDe(choix), dx = (ev.clientX - geste.x0) / geste.r.width, dy = (ev.clientY - geste.y0) / geste.r.height;
  if (Math.abs(dx) + Math.abs(dy) > 0.001) geste.bouge = true;
  if (geste.agrandit) { z.w = geste.z0.w + dx; z.h = geste.z0.h + dy; } else { z.x = geste.z0.x + dx; z.y = geste.z0.y + dy; }
  borne(z);
  const d = g('calque').querySelector('.choisie'); if (d) place(d, z);
});
window.addEventListener('pointerup', () => { if (geste && geste.bouge) change(); geste = null; });
g('scene').addEventListener('pointerdown', ev => { if (ev.target.id === 'calque' || ev.target.id === 'apercu') { choix = null; dessineCalque(); panneau(); } });
document.addEventListener('keydown', ev => {
  if (!choix || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
  const pas = ev.shiftKey ? 0.02 : 0.002, z = zoneDe(choix); if (!z) return;
  const d = { ArrowLeft: [-pas, 0], ArrowRight: [pas, 0], ArrowUp: [0, -pas], ArrowDown: [0, pas] }[ev.key];
  if (!d) return;
  ev.preventDefault(); z.x += d[0]; z.y += d[1]; borne(z); change();
});

// ------------------------------------------------------------ pictures
function metImage(i, f) {
  if (!f || !M) return;
  for (const k in M.images) if (M.images[k] === f) delete M.images[k];
  M.images[i] = f;
  choix = { k: 'c', i: Math.min(i + 1, M.cases.length - 1) };
  change();
}
function suivi() {
  if (!M) { g('suivi').textContent = ''; return; }
  const noms = new Set(galerie.map(x => x.f));
  const manquent = [];
  M.cases.forEach((_, i) => { if (!noms.has(M.images[i])) manquent.push(i + 1); });
  const recues = M.cases.length - manquent.length;
  const s = g('suivi');
  if (!manquent.length && recues > 0) { s.textContent = tf('serie_complete', recues); s.style.color = 'var(--cyan)'; }
  else { s.textContent = tf('serie', recues, M.cases.length, manquent.join(', ')); s.style.color = ''; }
}

// ------------------------------------------------------------ the side panel
function panneau() {
  ['images', 'dispo', 'textes'].forEach(o => {
    g('p' + o.charAt(0).toUpperCase() + o.slice(1)).classList.toggle('cache', onglet !== o);
    g('o' + o.charAt(0).toUpperCase() + o.slice(1)).classList.toggle('actif', onglet === o);
  });
  if (!M) { g('pImages').innerHTML = ''; g('pImages').appendChild(el('p', { class: 'aide', text: T.aucun_modele })); return; }
  if (onglet === 'images') panneauImages(); else if (onglet === 'dispo') panneauDispo(); else panneauTextes();
}
function panneauImages() {
  const p = g('pImages'); p.innerHTML = '';
  const sats = [...new Set(galerie.map(x => x.s).filter(s => s))];
  const sel = el('select', { onchange: ev => { filtre = ev.target.value; panneau(); } }, [el('option', { value: '', text: T.tous })].concat(sats.map(s => el('option', { value: s, text: s }))));
  sel.value = filtre;
  p.appendChild(el('div', { class: 'rangee' }, [sel,
    el('button', { class: 'discret', text: T.remplir, onclick: async () => { await sauveMaintenant(); await lire('/remplit', { id: id, sat: filtre }); await charge(id); } })]));
  const r2 = el('div', { class: 'rangee', style: 'margin-top:6px' });
  if (choix && choix.k === 'c') r2.appendChild(el('button', { class: 'discret', text: T.vider_case, onclick: () => { delete M.images[choix.i]; change(); } }));
  r2.appendChild(el('button', { class: 'rouge', text: T.tout_vider, onclick: () => { M.images = {}; change(); } }));
  p.appendChild(r2);
  p.appendChild(el('p', { class: 'aide', text: choix && choix.k === 'c' ? tf('choisir_case', choix.i + 1) : T.aide_images }));
  const liste = galerie.filter(x => !filtre || x.s === filtre);
  if (!liste.length) { p.appendChild(el('p', { class: 'aide', text: T.aucune_image })); return; }
  const ou = {}; for (const k in M.images) ou[M.images[k]] = +k;
  const grille = el('div', { class: 'galerie' });
  liste.forEach(x => {
    const placee = ou[x.f] !== undefined;
    const d = el('div', { class: 'img' + (placee ? ' placee' : ''), draggable: 'true' }, [
      el('img', { src: url('/vignette', { f: x.f, w: 320 }), loading: 'lazy' }),
      el('div', { class: 'l1', text: (x.s || 'SSTV') + ' · ' + x.m }),
      el('div', { class: 'l2', text: heure(x.t) + ' · ' + (x.d ? T.direct : T.redecodee) + (x.c ? '' : ' · ' + T.partielle) })]);
    if (placee) d.appendChild(el('span', { class: 'num', text: (ou[x.f] + 1) + '/' + M.cases.length }));
    d.addEventListener('dragstart', ev => ev.dataTransfer.setData('text/plain', x.f));
    d.addEventListener('click', () => {
      let i = choix && choix.k === 'c' ? choix.i : -1;
      if (i < 0) i = M.cases.findIndex((_, j) => M.images[j] === undefined);
      if (i >= 0) metImage(i, x.f);
    });
    grille.appendChild(d);
  });
  p.appendChild(grille);
}
function caseACocher(lib, val, sur) {
  const c = el('input', { type: 'checkbox' }); c.checked = val; c.addEventListener('change', () => sur(c.checked));
  return el('label', { class: 'rangee', style: 'margin:6px 0' }, [c, el('span', { text: lib })]);
}
function champ(lib, valeur, sur, attrs) {
  const i = el('input', Object.assign({ value: valeur }, attrs || {}));
  i.addEventListener('input', () => sur(i.value));
  return el('div', { class: 'champ' }, [el('label', { text: lib }), i]);
}
function positionChoisie(p) {
  const z = zoneDe(choix); if (!z) return;
  p.appendChild(el('div', { class: 'aide', text: T.position }));
  const r = el('div', { class: 'rangee' });
  ['x', 'y', 'w', 'h'].forEach(k => {
    const i = el('input', { type: 'number', step: '0.1', value: (z[k] * 100).toFixed(1), title: k });
    i.addEventListener('change', () => { z[k] = (parseFloat(i.value) || 0) / 100; borne(z); change(); });
    r.appendChild(el('span', { text: k }));
    r.appendChild(i);
  });
  p.appendChild(r);
}
function panneauDispo() {
  const p = g('pDispo'); p.innerHTML = '';
  p.appendChild(champ(T.nom_modele, M.nom, v => { M.nom = v; clearTimeout(minuteur); minuteur = setTimeout(async () => { await sauve(); listeModeles(); }, 800); }));
  p.appendChild(caseACocher(T.legende, M.legende, v => { M.legende = v; change(); }));
  p.appendChild(caseACocher(T.numeros, M.numeros, v => { M.numeros = v; change(); }));
  const r = el('div', { class: 'rangee' });
  r.appendChild(el('button', { class: 'discret', text: T.ajouter_case, onclick: () => {
    const der = M.cases[M.cases.length - 1] || { x: 0.4, y: 0.4, w: 0.2, h: 0.2 * ratio * 0.75 };
    const z = { x: der.x + 0.03, y: der.y + 0.03, w: der.w, h: der.h }; borne(z);
    M.cases.push(z); choix = { k: 'c', i: M.cases.length - 1 }; change(); } }));
  if (choix && choix.k === 'c') r.appendChild(el('button', { class: 'rouge', text: T.retirer_case, onclick: () => {
    const i = choix.i; M.cases.splice(i, 1);
    const im = {}; for (const k in M.images) { const n = +k; if (n < i) im[n] = M.images[k]; else if (n > i) im[n - 1] = M.images[k]; }
    M.images = im; choix = null; change(); } }));
  p.appendChild(r);
  p.appendChild(el('p', { class: 'aide', text: T.aide_dispo }));
  if (choix) positionChoisie(p);
}
function panneauTextes() {
  const p = g('pTextes'); p.innerHTML = '';
  const valeur = (cle, lib, defaut) => champ(lib, saisie[cle], v => { saisie[cle] = v; if (cle === 'nom') change(); else apercuBientot(); },
    { placeholder: defaut ? tf('par_defaut', defaut) : '' });
  p.appendChild(valeur('ind', T.indicatif, defauts.indicatif));
  p.appendChild(valeur('nom', T.nom, defauts.nom));
  p.appendChild(valeur('loc', T.locator, defauts.locator));
  p.appendChild(valeur('dates', T.dates, defauts.dates));
  p.appendChild(champ(T.titre_planche, M.titre, v => { M.titre = v; change(); }, { placeholder: defauts.titre ? tf('par_defaut', defauts.titre) : '' }));
  const r = el('div', { class: 'rangee', style: 'margin:8px 0' });
  r.appendChild(el('button', { class: 'discret', text: T.ajouter_texte, onclick: () => {
    M.textes.push({ champ: 'LIBRE', x: 0.35, y: 0.45, w: 0.3, h: 0.07, couleur: COULEURS[0], fond: FONDS[2], libre: T.libre });
    choix = { k: 't', i: M.textes.length - 1 }; change(); } }));
  p.appendChild(r);
  if (!choix || choix.k !== 't') return;
  const t = M.textes[choix.i];
  p.appendChild(el('div', { class: 'aide', text: T.texte_choisi + ' — ' + (T['champ_' + t.champ.toLowerCase()] || t.champ) }));
  const s = el('select', { onchange: ev => { t.champ = ev.target.value; change(); } }, CHAMPS.map(c => el('option', { value: c, text: T['champ_' + c.toLowerCase()] })));
  s.value = t.champ; p.appendChild(s);
  if (t.champ === 'LIBRE') p.appendChild(champ(T.libre, t.libre, v => { t.libre = v; change(); }));
  const nuancier = (lib, liste, cle) => {
    const n = el('div', { class: 'nuancier' });
    liste.forEach(c => n.appendChild(el('div', { class: 'nuance' + ((t[cle] | 0) === c ? ' choisie' : ''),
      style: 'background:' + (c === 0 ? 'transparent' : css(c)), title: c === 0 ? T.sans : '', onclick: () => { t[cle] = c; change(); } })));
    return el('div', { class: 'champ' }, [el('label', { text: lib }), n]);
  };
  p.appendChild(nuancier(T.couleur, COULEURS, 'couleur'));
  p.appendChild(nuancier(T.fond + ' (' + [T.sans, T.voile_blanc, T.voile_noir].join(' · ') + ')', FONDS, 'fond'));
  positionChoisie(p);
  p.appendChild(el('button', { class: 'rouge', style: 'margin-top:8px', text: T.retirer_texte, onclick: () => { M.textes.splice(choix.i, 1); choix = null; change(); } }));
}

// ------------------------------------------------------------ templates
async function sauveMaintenant() { if (minuteur) { clearTimeout(minuteur); minuteur = null; await sauve(); } }
async function listeModeles() {
  const l = await lire('/liste');
  defauts.indicatif = l.indicatif; defauts.nom = l.nom;
  const s = g('modeles'); s.innerHTML = '';
  l.modeles.forEach(m => s.appendChild(el('option', { value: m.id, text: m.nom })));
  if (id) s.value = id;
  return l.modeles;
}
async function charge(i) {
  id = i; g('modeles').value = i;
  const j = await lire('/modele', { id: i });
  M = litModele(j.texte); ratio = j.ratio || M.ratio;
  defauts.locator = j.locator; defauts.dates = j.dates; defauts.titre = j.titre;
  g('scene').style.aspectRatio = String(ratio);
  g('scene').style.setProperty('--ratio', String(ratio));
  choix = null; change(true); apercu();
}
async function demarre() {
  g('porte').classList.add('cache'); g('editeur').classList.remove('cache');
  const l = await listeModeles();
  galerie = await lire('/galerie');
  if (l.length) await charge(l[l.length - 1].id); else panneau();
}
async function nouveau(type) { await sauveMaintenant(); const j = await lire('/nouveau', { type: type }); await listeModeles(); await charge(j.id); }

g('tPorte').textContent = T.titre; g('tCode').textContent = T.code; g('entrer').textContent = T.entrer;
g('tTitre').textContent = 'SatMe · ' + T.titre; g('tModele').textContent = T.modele; g('tSous').textContent = T.sous;
g('grille').textContent = T.grille; g('tour').textContent = T.tour; g('importer').textContent = T.importer;
g('telecharger').textContent = T.telecharger; g('pupitre').textContent = T.pupitre; g('pupitre').href = base + '/'; g('journal').textContent = T.journal; g('journal').href = base + '/journal';
g('oImages').textContent = T.images; g('oDispo').textContent = T.disposition; g('oTextes').textContent = T.textes;
g('oImages').onclick = () => { onglet = 'images'; panneau(); };
g('oDispo').onclick = () => { onglet = 'dispo'; panneau(); };
g('oTextes').onclick = () => { onglet = 'textes'; panneau(); };
g('entrer').onclick = entrer;
g('code').addEventListener('keydown', ev => { if (ev.key === 'Enter') entrer(); });
g('modeles').onchange = async ev => { await sauveMaintenant(); await charge(ev.target.value); };
g('grille').onclick = () => nouveau('grille');
g('tour').onclick = () => nouveau('tour');
g('importer').onclick = () => g('fichier').click();
g('fichier').onchange = async () => {
  const f = g('fichier').files[0]; if (!f) return;
  await sauveMaintenant();
  g('message').textContent = T.import_en_cours;
  try {
    const r = await fetch(url('/importe', { nom: f.name.replace(/\.[^.]*$/, '') }), { method: 'POST', body: f });
    const j = await r.json();
    if (!j.ok) { g('message').textContent = T.import_echec; return; }
    await listeModeles(); await charge(j.id);
    g('message').textContent = tf('import_ok', j.cases);
  } catch (e) { g('message').textContent = T.import_echec; }
  g('fichier').value = '';
};
g('telecharger').onclick = async () => {
  await sauveMaintenant();
  g('message').textContent = T.fabrication;
  try {
    const r = await fetch(url('/exporte', Object.assign({ id: id }, valeurs())));
    const nom = ((r.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/) || [])[1] || 'SatMe_Planche.png';
    const b = await r.blob();
    const a = el('a', { href: URL.createObjectURL(b), download: nom }); document.body.appendChild(a); a.click(); a.remove();
    g('message').textContent = nom;
  } catch (e) { g('message').textContent = T.hors_ligne; }
};
window.addEventListener('resize', apercuBientot);
if (cle) demarre().catch(() => {}); else porte();
</script>
</body>
</html>
"""
}
