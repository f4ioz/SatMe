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
 * The pass journal, on the PC screen: the passes kept, one replayed large —
 * the sky, the moment (time, angles, RX frequency, S-meter, the SSTV picture
 * arriving), a long timeline to click, drag and zoom, the sound — and what
 * the phone makes of it downloaded straight onto the PC.
 *
 * **The browser only replays.** The sky it draws is a reading of the pass
 * (its points, its marks); the pictures, videos, GIFs and sounds are made by
 * the phone ([JournalWeb]), the same as from its Share tab.
 *
 * **Nothing from outside**: no library, no map tile fetched by the PC.
 */
object PageJournal {

    private val CLES = listOf(
        "pj_titre", "pj_sous", "pj_tous", "pj_aucun", "pj_choisir", "pj_duree", "pj_lecture", "pj_pause", "pj_vitesse",
        "pj_sans_son", "pj_frise_aide", "pj_selection", "pj_effacer", "pj_moments", "pj_aucun_moment", "pj_choisir_morceau",
        "pj_fabriquer", "pj_de_quoi_sel", "pj_de_quoi_tout", "pj_titre_extrait", "pj_son", "pj_video", "pj_gif", "pj_image",
        "pj_paquet", "pj_vue", "pj_vue_ciel", "pj_vue_carte", "pj_en_cours", "pj_pret", "pj_telecharger", "pj_echec",
        "pj_sons", "pj_qso", "pj_aprs", "pj_via_iss", "pj_sstv", "pj_signet", "pj_activite", "pj_smetre", "pj_reconstitue",
        "pj_planche", "pj_onglet_rejeu", "pj_onglet_fabriquer", "pj_accelere", "pj_avant", "pj_apres", "pj_activite_opt",
        "pj_afficher", "pj_aff_sstv", "pj_aff_fiche", "pj_aff_smetre", "pj_aff_freq", "pj_aff_locator", "pj_resolution",
        "pj_res_xs", "pj_res_m", "pj_res_hd", "pj_ouverture", "pj_recap", "pj_avec_son", "pj_accelere_video",
        "pj_options_aide", "pj_accelere_actif", "pw_code", "pw_entrer", "pw_code_faux", "pw_hors_ligne", "pw_pupitre")

    private fun js(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '<' -> append("\\u003c")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    /** The page in the app's language (keys without their prefix in the page). */
    fun html(): String = HTML
        .replace("/*T*/", CLES.joinToString(",") { "\"${it.substring(3)}\":\"${js(t(it))}\"" })
        .replace("lang=\"fr\"", "lang=\"${if (I18n.current() == fr.f4ioz.satcombo.i18n.Lang.EN) "en" else "fr"}\"")

    private val HTML = """
<!doctype html>
<html lang="fr">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SatMe — journal</title>
<style>
  :root { --fond:#0B1016; --carte:#131C24; --bord:#22303B; --cyan:#3FE0C8; --ambre:#FFB454; --rose:#E5484D;
          --gris:#9AA7B4; --clair:#E8F0F7; --qso:#FF4FA3; --aprs:#4C8DFF; --iss:#FFC21A; --sstv:#FF8A1F; --signet:#B98CFF; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:13px; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { display:flex; gap:12px; align-items:center; flex-wrap:wrap; padding:7px 14px; border-bottom:1px solid var(--bord); }
  h1 { font-size:16px; margin:0; }
  a { color:var(--cyan); }
  input, select, button { font-size:13px; font-family:inherit; border-radius:7px; border:1px solid var(--bord);
         background:#0E1720; color:var(--clair); padding:5px 8px; }
  input[type=number] { width:64px; }
  button { cursor:pointer; font-weight:600; }
  button.primaire { background:var(--cyan); color:#06131A; border:none; }
  button.discret { color:var(--cyan); }
  button.actif { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .corps { display:grid; grid-template-columns:250px minmax(0,1fr); gap:10px; padding:8px 14px; }
  @media (max-width:950px) { .corps { grid-template-columns:1fr; } }
  .liste { background:var(--carte); border:1px solid var(--bord); border-radius:10px; padding:6px; max-height:calc(100vh - 62px); overflow:auto; }
  .p { padding:6px 8px; border-radius:7px; cursor:pointer; border:1px solid transparent; }
  .p:hover { background:#18232D; }
  .p.actif { border-color:var(--cyan); background:#13262A; }
  .p .s { font-weight:700; } .p .d { color:var(--gris); font-size:11px; }
  .p .m { font-size:11px; margin-top:1px; display:flex; gap:7px; flex-wrap:wrap; }
  .pastille { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:3px; vertical-align:middle; }
  .vue { background:var(--carte); border:1px solid var(--bord); border-radius:10px; padding:8px 10px; min-width:0; }
  .entete { display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; }
  .entete .s { font-size:18px; font-weight:800; }
  .entete .d { color:var(--gris); font-size:12px; flex:1; }
  .haut { display:grid; grid-template-columns:minmax(0,1fr) 270px; gap:10px; margin-top:6px; }
  @media (max-width:1200px) { .haut { grid-template-columns:1fr; } }
  /* The view fits the screen: the whole replay without scrolling on a laptop. */
  .ecran { position:relative; height:calc(100vh - 335px); min-height:240px; background:#0E1720; border-radius:8px; overflow:hidden; }
  #ciel { position:absolute; inset:0; width:100%; height:100%; }
  #carte { position:absolute; inset:0; width:100%; height:100%; cursor:grab; display:none; }
  .bascule { position:absolute; left:8px; top:8px; display:flex; gap:4px; z-index:2; }
  .attrib { position:absolute; right:4px; bottom:2px; font-size:10px; color:#333; background:rgba(255,255,255,.7); padding:0 4px; display:none; }
  .moment { align-self:start; background:#0E1720; border:1px solid var(--bord); border-radius:8px; padding:9px; }
  .heure { font-family:ui-monospace,Consolas,monospace; font-size:24px; font-weight:700; }
  .val { font-family:ui-monospace,Consolas,monospace; font-size:13px; margin-top:2px; }
  .smetre { display:flex; gap:2px; height:14px; margin-top:6px; }
  .smetre i { flex:1; background:rgba(235,240,245,.18); border-radius:1px; }
  .smetre i.on { background:#EBF0F5; } .smetre i.r { background:rgba(255,70,60,.25); } .smetre i.r.on { background:#FF463C; }
  .smetre i.pic { background:#FFDC5A; }
  .graduation { position:relative; height:12px; font-size:9px; color:var(--gris); margin-top:2px; }
  .graduation span { position:absolute; transform:translateX(-50%); }
  .graduation span.r { color:#FF6A60; }
  #lectureS { font-family:ui-monospace,Consolas,monospace; font-size:13px; font-weight:700; margin-top:4px; }
  #imageMoment { width:100%; margin-top:6px; border-radius:5px; display:none; }
  #carteMoment { margin-top:6px; font-size:12px; }
  #carteMoment .nom { color:var(--gris); }
  .commandes { display:flex; gap:6px; align-items:center; flex-wrap:wrap; margin-top:6px; }
  #frise { width:100%; height:86px; display:block; margin-top:5px; background:#0E1720; border-radius:7px; cursor:crosshair; }
  .aide { color:var(--gris); font-size:11px; margin:3px 0; }
  .onglets { display:flex; gap:6px; margin-top:8px; }
  .panneau { background:#0E1720; border:1px solid var(--bord); border-radius:8px; padding:8px 10px; margin-top:6px; }
  .mo { display:flex; gap:8px; align-items:center; padding:3px 4px; border-radius:6px; cursor:pointer; }
  .mo:hover { background:#18232D; }
  .mo .h { font-family:ui-monospace,Consolas,monospace; color:var(--gris); }
  .mo .t { font-weight:700; } .mo .x { color:var(--gris); font-size:11px; flex:1; }
  .mo img { width:52px; height:39px; object-fit:cover; border-radius:4px; }
  .rangee { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:4px 0; }
  .colonnes { display:grid; grid-template-columns:1fr 1fr; gap:4px 18px; }
  @media (max-width:900px) { .colonnes { grid-template-columns:1fr; } }
  progress { width:100%; }
  #porte { max-width:420px; margin:60px auto; background:var(--carte); border:1px solid var(--bord); border-radius:14px; padding:22px; }
  #porte input { font-size:28px; letter-spacing:6px; width:100%; text-align:center; }
  .cache { display:none !important; }
</style>
<body>
<div id="porte" class="cache">
  <h1 id="tPorte"></h1><p class="aide" id="tCode"></p>
  <input id="code" inputmode="numeric" maxlength="6" autocomplete="off">
  <div class="rangee"><button class="primaire" id="entrer"></button><span id="porteMsg"></span></div>
</div>
<div id="page" class="cache">
<header>
  <h1 id="tTitre"></h1>
  <a id="lienPupitre"></a><a id="lienPlanche"></a>
  <select id="filtre"></select>
  <span class="aide" id="tSous"></span>
  <span id="message" style="color:var(--ambre)"></span>
</header>
<div class="corps">
  <div class="liste" id="liste"></div>
  <div class="vue" id="vue"><p class="aide" id="tChoisir"></p></div>
</div>
</div>
<audio id="son" preload="auto"></audio>
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
const SVG = 'http://www.w3.org/2000/svg';
function sv(tag, attrs) { const e = document.createElementNS(SVG, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; }
const base = location.pathname.replace(/\/journal\/?$/, '');
let cle = localStorage.getItem('satme-cle') || '';
function url(r, p) { const q = new URLSearchParams(p || {}); q.set('cle', cle); return base + '/journal' + r + '?' + q.toString(); }
async function lire(r, p) {
  let x;
  try { x = await fetch(url(r, p)); } catch (e) { g('message').textContent = T.hors_ligne; throw e; }
  if (x.status === 403) { porte(); throw 'session'; }
  return x.json();
}
function porte() { localStorage.removeItem('satme-cle'); cle = ''; g('page').classList.add('cache'); g('porte').classList.remove('cache'); g('code').focus(); }
async function entrer() {
  const j = await (await fetch(base + '/entrer?code=' + encodeURIComponent(g('code').value))).json();
  if (j.ok) { cle = j.cle; localStorage.setItem('satme-cle', cle); demarre(); } else g('porteMsg').textContent = T.code_faux;
}

// ------------------------------------------------------------ the page's options (kept on this PC)
let O = {};
function chargeOptions(defauts) {
  let gardees = {}; try { gardees = JSON.parse(localStorage.getItem('satme-journal') || '{}'); } catch (e) {}
  O = Object.assign({ vue: 'ciel', accelere: false, rapide: 10, avant: 10, apres: 30, surActivite: true, sstv: true, fiches: true,
    smetre: true, freq: true, locator: true, res: 'M', ouverture: true, recap: true, avecSon: true, accelVideo: false }, defauts, gardees);
}
function garde() { try { localStorage.setItem('satme-journal', JSON.stringify(O)); } catch (e) {} }

// ------------------------------------------------------------ times
let utc = true;
function p2(n) { return String(n).padStart(2, '0'); }
function hms(t) { const d = new Date(t); return utc ? p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + ':' + p2(d.getUTCSeconds()) : p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()); }
function jour(t) { const d = new Date(t); return utc ? p2(d.getUTCDate()) + '/' + p2(d.getUTCMonth() + 1) + '/' + d.getUTCFullYear() + ' ' + p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + 'Z' : d.toLocaleDateString() + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); }
function duree(ms) { const s = Math.round(ms / 1000); return Math.floor(s / 60) + ' min ' + p2(s % 60); }
const COUL = { QSO: 'var(--qso)', APRS: 'var(--aprs)', ISS: 'var(--iss)', SSTV: 'var(--sstv)', SIGNET: 'var(--signet)' };
const COULHEX = { QSO: '#FF4FA3', APRS: '#4C8DFF', ISS: '#FFC21A', SSTV: '#FF8A1F', SIGNET: '#B98CFF' };
function couleur(m) { return m.k === 'APRS' && m.iss ? 'ISS' : m.k; }

// ------------------------------------------------------------ the list
let passages = [], filtre = '', P = null, fiches = {}, segments = [];
async function demarre() {
  g('porte').classList.add('cache'); g('page').classList.remove('cache');
  const [l, r] = await Promise.all([lire('/liste'), lire('/reglages')]);
  utc = l.utc; passages = l.passages;
  chargeOptions({ accelere: r.accelere, rapide: r.rapide, avant: r.avant, apres: r.apres, surActivite: r.surActivite, sstv: r.sstv,
    fiches: r.fiches, smetre: r.smetre, freq: r.freq, locator: r.locator, res: r.res, ouverture: r.ouverture, recap: r.recap });
  const sats = [...new Set(passages.map(p => p.sat))].sort();
  const f = g('filtre'); f.innerHTML = '';
  f.appendChild(el('option', { value: '', text: T.tous })); sats.forEach(s => f.appendChild(el('option', { value: s, text: s })));
  f.value = filtre;
  dessineListe();
}
function dessineListe() {
  const d = g('liste'); d.innerHTML = '';
  const l = passages.filter(p => !filtre || p.sat === filtre);
  if (!l.length) { d.appendChild(el('p', { class: 'aide', text: T.aucun })); return; }
  l.forEach(p => {
    const m = el('div', { class: 'm' });
    const tag = (k, n, lib) => { if (n) m.appendChild(el('span', {}, [el('span', { class: 'pastille', style: 'background:' + COUL[k] }), document.createTextNode(n + ' ' + lib)])); };
    if (p.son) m.appendChild(el('span', { text: '♪' }));
    tag('QSO', p.q, T.qso); tag('SSTV', p.i, 'SSTV'); tag('APRS', p.r, T.aprs); tag('SIGNET', p.s, '⚑');
    if (p.sm) m.appendChild(el('span', { text: T.smetre }));
    if (p.rec) m.appendChild(el('span', { style: 'color:var(--gris)', text: T.reconstitue }));
    d.appendChild(el('div', { class: 'p' + (P && P.id === p.id ? ' actif' : ''), onclick: () => ouvre(p.id) }, [
      el('div', { class: 's', text: p.sat }),
      el('div', { class: 'd', text: jour(p.de) + ' · ' + tf('duree', duree(p.a - p.de), p.el) }), m]));
  });
}

// ------------------------------------------------------------ a pass
let t = 0, joue = false, vitesse = 1, vue0 = 0, vue1 = 1, sel = null, activite = [], onglet = 'moments';
async function ouvre(id) {
  arrete();
  P = await lire('/passage', { id: id }); utc = P.utc;
  // What the replay covers: the pass, and what is tied to it a little before or after
  // (a contact logged once the satellite had set, the recording started early).
  P.t0 = Math.min(P.de, ...P.sons.map(s => s.o + s.an), ...P.marques.map(m => m.de));
  P.t1 = Math.max(P.a, ...P.marques.map(m => m.a + 5000));
  t = P.de; vue0 = P.t0; vue1 = P.t1; sel = null; activite = []; morceauJoue = -1; fiches = {}; segments = []; carteCadree = false;
  dessineListe(); construitVue(); dessineCiel(); maj();
  lire('/activite', { id: id }).then(a => { if (P && P.id === id) { activite = a; dessineFrise(); } }).catch(() => {});
  lire('/fiches', { id: id }).then(f => { if (P && P.id === id) { fiches = f; maj(); } }).catch(() => {});
  chargeSegments();
}
function optionsSegments() { return { id: P.id, avant: O.avant, apres: O.apres, rapide: O.rapide, surActivite: O.surActivite ? '1' : '0' }; }
function chargeSegments() { if (!P) return; const id = P.id; lire('/segments', optionsSegments()).then(s => { if (P && P.id === id) { segments = s; dessineFrise(); } }).catch(() => {}); }
function construitVue() {
  const v = g('vue'); v.innerHTML = '';
  v.appendChild(el('div', { class: 'entete' }, [el('span', { class: 's', text: P.sat }),
    el('span', { class: 'd', text: jour(P.de) + ' · ' + tf('duree', duree(P.a - P.de), P.el) + (P.loc ? ' · ' + P.loc : '') + (P.tp ? ' · ' + P.tp : '') })]));
  const mo = el('div', { class: 'moment' }, [
    el('div', { class: 'heure', id: 'heure' }), el('div', { class: 'val', id: 'angles' }), el('div', { class: 'val', id: 'rx' }),
    el('div', { class: 'smetre', id: 'smetre' }), el('div', { class: 'graduation', id: 'grad' }), el('div', { id: 'lectureS' }),
    el('img', { id: 'imageMoment', alt: '' }), el('div', { id: 'carteMoment' })]);
  const ecran = el('div', { class: 'ecran', id: 'ecran' }, [sv('svg', { id: 'ciel', viewBox: '0 0 440 440', preserveAspectRatio: 'xMidYMid meet' }),
    el('canvas', { id: 'carte' }), el('div', { class: 'attrib', id: 'attrib', text: '© OpenStreetMap' }),
    el('div', { class: 'bascule' }, [el('button', { id: 'bCiel', text: T.vue_ciel, onclick: () => montreVue('ciel') }),
      el('button', { id: 'bCarte', text: T.vue_carte, onclick: () => montreVue('carte') })])]);
  v.appendChild(el('div', { class: 'haut' }, [ecran, mo]));
  const s = g('smetre'); for (let i = 0; i < 46; i++) s.appendChild(el('i', { class: (i + 0.5) / 46 > 0.6 ? 'r' : '' }));
  [1, 3, 5, 7, 9].forEach(n => g('grad').appendChild(el('span', { text: String(n), style: 'left:' + (n / 9 * 60) + '%' })));
  [20, 40, 60].forEach(db => g('grad').appendChild(el('span', { class: 'r', text: '+' + db, style: 'left:' + Math.min(97, 60 + db / 60 * 40) + '%' })));
  const vit = el('select', { id: 'vitesse', onchange: ev => { vitesse = +ev.target.value; g('son').playbackRate = vitesse; } },
    [1, 2, 4].map(x => el('option', { value: x, text: '×' + x })));
  vit.value = String(vitesse);
  const acc = el('input', { type: 'checkbox', id: 'accelere' }); acc.checked = O.accelere;
  acc.addEventListener('change', () => { O.accelere = acc.checked; garde(); if (joue) synchro(); maj(); });
  v.appendChild(el('div', { class: 'commandes' }, [
    el('button', { class: 'primaire', id: 'lecture', onclick: bascule }),
    el('button', { class: 'discret', text: '−10 s', onclick: () => cherche(t - 10000) }),
    el('button', { class: 'discret', text: '+10 s', onclick: () => cherche(t + 10000) }),
    el('span', { class: 'aide', text: T.vitesse }), vit,
    el('label', { class: 'aide' }, [acc, document.createTextNode(' ' + T.accelere.split(':')[0])]),
    el('span', { class: 'aide', id: 'position' }),
    P.sons.length ? el('span') : el('span', { class: 'aide', text: T.sans_son })]));
  v.appendChild(el('canvas', { id: 'frise' }));
  v.appendChild(el('div', { class: 'rangee', id: 'selection' }));
  v.appendChild(el('div', { class: 'onglets' }, [
    el('button', { id: 'oMoments', text: T.moments, onclick: () => { onglet = 'moments'; panneau(); } }),
    el('button', { id: 'oRejeu', text: T.onglet_rejeu, onclick: () => { onglet = 'rejeu'; panneau(); } }),
    el('button', { id: 'oFabriquer', text: T.onglet_fabriquer, onclick: () => { onglet = 'fabriquer'; panneau(); } }),
    el('span', { class: 'aide', text: T.frise_aide })]));
  v.appendChild(el('div', { class: 'panneau', id: 'panneau' }));
  installeFrise(); installeCarte(); montreVue(O.vue); panneau();
}
function montreVue(v) {
  O.vue = v; garde();
  g('ciel').style.display = v === 'ciel' ? 'block' : 'none';
  g('carte').style.display = v === 'carte' ? 'block' : 'none';
  g('attrib').style.display = v === 'carte' ? 'block' : 'none';
  g('bCiel').classList.toggle('actif', v === 'ciel'); g('bCarte').classList.toggle('actif', v === 'carte');
  if (v === 'carte') dessineCarte();
}

// ------------------------------------------------------------ the side panels
function caseOpt(cle, lib, apres) {
  const c = el('input', { type: 'checkbox' }); c.checked = !!O[cle];
  c.addEventListener('change', () => { O[cle] = c.checked; garde(); if (apres) apres(); maj(); });
  return el('label', { class: 'rangee' }, [c, el('span', { text: lib })]);
}
function nombreOpt(cle, lib, min, max, apres) {
  const i = el('input', { type: 'number', min: min, max: max, value: O[cle] });
  i.addEventListener('change', () => { O[cle] = Math.max(min, Math.min(max, +i.value || 0)); i.value = O[cle]; garde(); if (apres) apres(); });
  return el('label', { class: 'rangee' }, [el('span', { text: lib }), i]);
}
function panneau() {
  ['Moments', 'Rejeu', 'Fabriquer'].forEach(o => g('o' + o).classList.toggle('actif', onglet === o.toLowerCase()));
  const p = g('panneau'); p.innerHTML = '';
  if (onglet === 'moments') panneauMoments(p); else if (onglet === 'rejeu') panneauRejeu(p); else panneauFabriquer(p);
}
function panneauMoments(p) {
  if (!P.marques.length) p.appendChild(el('p', { class: 'aide', text: T.aucun_moment }));
  P.marques.slice().sort((a, b) => a.de - b.de).forEach(m => {
    const k = couleur(m), f = fiches[m.t] || fiches[(m.t || '').split('-')[0]];
    const kids = [el('span', { class: 'pastille', style: 'background:' + COUL[k] }), el('span', { class: 'h', text: hms(m.de) }),
      el('span', { class: 't', text: m.t || T.signet }),
      el('span', { class: 'x', text: [m.d || (m.k === 'SSTV' ? T.sstv : ''), f ? [f.nom, f.qth, f.loc].filter(x => x).join(' · ') : ''].filter(x => x).join(' — ') })];
    if (m.f) kids.splice(1, 0, el('img', { src: url('/vignette', { f: m.f, w: 160 }), loading: 'lazy' }));
    kids.push(el('button', { class: 'discret', text: T.choisir_morceau, onclick: ev => {
      ev.stopPropagation(); sel = [m.de - (m.k === 'SSTV' ? 1000 : O.avant * 1000), m.a + (m.k === 'QSO' ? O.apres * 1000 : 3000)];
      titre = m.k === 'SSTV' ? 'SSTV ' + hms(m.de) : (m.k === 'SIGNET' ? (m.t || T.signet) : (m.k === 'QSO' ? 'QSO ' : 'APRS ') + m.t);
      deQuoi = 'sel'; zoomSur(sel); maj(); } }));
    p.appendChild(el('div', { class: 'mo', onclick: () => { cherche(m.de - (m.k === 'SSTV' ? 1000 : O.avant * 1000)); if (!joue) bascule(); } }, kids));
  });
  if (P.sons.length) {
    p.appendChild(el('div', { class: 'aide', text: T.sons, style: 'margin-top:8px' }));
    P.sons.forEach(s => p.appendChild(el('div', { class: 'rangee' }, [
      el('a', { href: url('/son', { id: P.id, n: s.n }), download: s.nom, text: '⤓ ' + s.nom }),
      el('span', { class: 'aide', text: hms(s.o + s.an) + ' · ' + duree(s.du - s.an) })])));
  }
}
function panneauRejeu(p) {
  const c = el('div', { class: 'colonnes' });
  c.appendChild(caseOpt('accelere', T.accelere, () => { const a = g('accelere'); if (a) a.checked = O.accelere; if (joue) synchro(); }));
  const r = el('select', { onchange: ev => { O.rapide = +ev.target.value; garde(); chargeSegments(); } }, [5, 10, 20, 50].map(x => el('option', { value: x, text: '×' + x })));
  r.value = String(O.rapide); c.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.vitesse }), r]));
  c.appendChild(nombreOpt('avant', T.avant, 0, 60, chargeSegments));
  c.appendChild(nombreOpt('apres', T.apres, 0, 120, chargeSegments));
  c.appendChild(caseOpt('surActivite', T.activite_opt, chargeSegments));
  c.appendChild(el('span'));
  c.appendChild(el('div', { class: 'aide', text: T.afficher }));
  c.appendChild(el('span'));
  c.appendChild(caseOpt('sstv', T.aff_sstv)); c.appendChild(caseOpt('fiches', T.aff_fiche));
  c.appendChild(caseOpt('smetre', T.aff_smetre)); c.appendChild(caseOpt('freq', T.aff_freq));
  c.appendChild(caseOpt('locator', T.aff_locator, dessineCarte));
  p.appendChild(c);
  p.appendChild(el('p', { class: 'aide', text: T.options_aide }));
}
let deQuoi = 'tout', titre = '';
function panneauFabriquer(p) {
  const radio = (nom, val, lib, coche, sur) => { const r = el('input', { type: 'radio', name: nom, value: val }); r.checked = coche; r.addEventListener('change', sur); return el('label', {}, [r, document.createTextNode(' ' + lib)]); };
  p.appendChild(el('div', { class: 'rangee' }, [radio('quoi', 'sel', T.de_quoi_sel, deQuoi === 'sel', () => { deQuoi = 'sel'; }),
    radio('quoi', 'tout', T.de_quoi_tout, deQuoi === 'tout', () => { deQuoi = 'tout'; })]));
  const ti = el('input', { placeholder: T.titre_extrait, style: 'width:100%', value: titre }); ti.addEventListener('input', () => titre = ti.value);
  p.appendChild(ti);
  const c = el('div', { class: 'colonnes' });
  const res = el('select', { onchange: ev => { O.res = ev.target.value; garde(); } }, [['XS', T.res_xs], ['M', T.res_m], ['HD', T.res_hd]].map(([v, l]) => el('option', { value: v, text: l })));
  res.value = O.res;
  c.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.resolution }), res]));
  c.appendChild(el('div', { class: 'rangee' }, [el('span', { class: 'aide', text: T.vue }),
    radio('vueF', 'ciel', T.vue_ciel, O.vue !== 'carte', () => montreVue('ciel')), radio('vueF', 'carte', T.vue_carte, O.vue === 'carte', () => montreVue('carte'))]));
  c.appendChild(caseOpt('ouverture', T.ouverture)); c.appendChild(caseOpt('recap', T.recap));
  c.appendChild(caseOpt('avecSon', T.avec_son)); c.appendChild(caseOpt('accelVideo', T.accelere_video));
  p.appendChild(c);
  p.appendChild(el('div', { class: 'rangee' }, [
    el('button', { class: 'discret', text: T.son, onclick: () => fabrique('son') }),
    el('button', { class: 'discret', text: T.image, onclick: () => fabrique('image') }),
    el('button', { class: 'discret', text: T.video, onclick: () => fabrique('video') }),
    el('button', { class: 'discret', text: T.gif, onclick: () => fabrique('gif') }),
    el('button', { class: 'discret', text: T.paquet, onclick: () => fabrique('paquet') })]));
  p.appendChild(el('div', { id: 'fabrication' }));
}

// ------------------------------------------------------------ the sky
const CX = 220, CY = 220, R = 200;
function xy(az, elv) { const r = R * Math.max(0, 90 - elv) / 90, a = az * Math.PI / 180; return [CX + r * Math.sin(a), CY - r * Math.cos(a)]; }
function interpole(l, tt, i0, i1, angle) {
  if (!l.length) return null;
  if (tt <= l[0][0]) return l[0]; if (tt >= l[l.length - 1][0]) return l[l.length - 1];
  let i = 1; while (i < l.length && l[i][0] < tt) i++;
  const a = l[i - 1], b = l[i], f = (tt - a[0]) / Math.max(1, b[0] - a[0]);
  let d1 = b[i0] - a[i0]; if (angle && d1 > 180) d1 -= 360; if (angle && d1 < -180) d1 += 360;
  let d2 = b[i1] - a[i1]; if (!angle && d2 > 180) d2 -= 360; if (!angle && d2 < -180) d2 += 360;
  const r = a.slice(); r[0] = tt; r[i0] = angle ? (a[i0] + d1 * f + 360) % 360 : a[i0] + d1 * f; r[i1] = angle ? a[i1] + d2 * f : ((a[i1] + d2 * f + 540) % 360) - 180;
  return r;
}
function ptA(tt) { return interpole(P.pts, tt, 1, 2, true); }
function solA(tt) { return interpole(P.sol || [], tt, 1, 2, false); }
function chemin(l) { return l.map((q, i) => (i ? 'L' : 'M') + xy(q[0], q[1]).map(v => v.toFixed(1)).join(' ')).join(' '); }
function dessineCiel() {
  const c = g('ciel'); c.innerHTML = '';
  c.appendChild(sv('circle', { cx: CX, cy: CY, r: R, fill: '#101A23', stroke: '#2A3A47' }));
  [60, 30].forEach(e => c.appendChild(sv('circle', { cx: CX, cy: CY, r: R * (90 - e) / 90, fill: 'none', stroke: '#22303B' })));
  c.appendChild(sv('line', { x1: CX - R, y1: CY, x2: CX + R, y2: CY, stroke: '#22303B' }));
  c.appendChild(sv('line', { x1: CX, y1: CY - R, x2: CX, y2: CY + R, stroke: '#22303B' }));
  [['N', 0], ['E', 90], ['S', 180], ['W', 270]].forEach(([n, a]) => { const [x, y] = xy(a, -6); const tx = sv('text', { x: x, y: y + 5, fill: '#9AA7B4', 'font-size': 14, 'text-anchor': 'middle' }); tx.textContent = n; c.appendChild(tx); });
  if (P.prevue.length) c.appendChild(sv('path', { d: chemin(P.prevue), fill: 'none', stroke: '#3B5566', 'stroke-width': 2, 'stroke-dasharray': '6 6' }));
  c.appendChild(sv('path', { d: chemin(P.pts.map(q => [q[1], q[2]])), fill: 'none', stroke: 'rgba(63,224,200,.25)', 'stroke-width': 3 }));
  c.appendChild(sv('path', { id: 'suivi', fill: 'none', stroke: '#00E5FF', 'stroke-width': 4, 'stroke-linecap': 'round' }));
  P.marques.forEach(m => {
    const k = couleur(m);
    if (m.k === 'SSTV') {
      const l = P.pts.filter(q => q[0] >= m.de && q[0] <= m.a).map(q => [q[1], q[2]]);
      const a = ptA(m.de), b = ptA(m.a); if (a) l.unshift([a[1], a[2]]); if (b) l.push([b[1], b[2]]);
      c.appendChild(sv('path', { d: chemin(l), fill: 'none', stroke: COULHEX[k], 'stroke-width': 7, 'stroke-linecap': 'round', opacity: .85 }));
    } else {
      const q = ptA(m.de); if (!q) return; const [x, y] = xy(q[1], q[2]);
      c.appendChild(sv('circle', { cx: x, cy: y, r: 6, fill: COULHEX[k], stroke: '#0B1016', 'stroke-width': 2 }));
    }
  });
  c.appendChild(sv('circle', { id: 'sat', r: 9, fill: '#FFC21A', stroke: '#0B1016', 'stroke-width': 2 }));
}
function majCiel() {
  const l = P.pts.filter(q => q[0] <= t).map(q => [q[1], q[2]]); const q = ptA(t);
  if (q) l.push([q[1], q[2]]);
  g('suivi').setAttribute('d', l.length > 1 ? chemin(l) : '');
  if (q) { const [x, y] = xy(q[1], q[2]); g('sat').setAttribute('cx', x); g('sat').setAttribute('cy', y); }
}

// ------------------------------------------------------------ the map (tiles served by the phone)
let zc = 3, mcx = 0.5, mcy = 0.5, carteCadree = false;
const tuiles = new Map();
function wx(lon) { return (lon + 180) / 360; }
function wy(lat) { const l = Math.max(-85, Math.min(85, lat)) * Math.PI / 180; return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2; }
function tuile(z, x, y) {
  const n = 1 << z, xw = ((x % n) + n) % n, k = z + '/' + xw + '/' + y;
  let i = tuiles.get(k);
  if (!i) { i = new Image(); i.onload = () => dessineCarte(); i.src = url('/tuile', { z: z, x: xw, y: y }); tuiles.set(k, i);
    if (tuiles.size > 400) tuiles.delete(tuiles.keys().next().value); }
  return i.complete && i.naturalWidth ? i : null;
}
function cadreCarte(w, h) {
  const pts = (P.sol || []).map(s => [s[1], s[2]]);
  if (P.qth) pts.push(P.qth);
  P.marques.forEach(m => { if (m.lat != null) pts.push([m.lat, m.lon]); });
  if (!pts.length) return;
  // The footprint at its largest, so the whole pass and its stations show.
  const alt = Math.max(...(P.sol || [[0, 0, 0, 400]]).map(s => s[3])), rho = Math.acos(6371 / (6371 + alt)) * 180 / Math.PI;
  let xs = pts.map(q => wx(q[1])), ys = pts.map(q => wy(q[0]));
  if (P.qth) { xs.push(wx(P.qth[1] - rho), wx(P.qth[1] + rho)); ys.push(wy(P.qth[0] + rho), wy(P.qth[0] - rho)); }
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  mcx = (x0 + x1) / 2; mcy = (y0 + y1) / 2;
  zc = Math.max(1, Math.min(9, Math.log2(Math.min(w / 256 / Math.max(1e-4, x1 - x0), h / 256 / Math.max(1e-4, y1 - y0)) * 0.9)));
}
function installeCarte() {
  const c = g('carte'); let pres = null;
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); pres = { x: ev.clientX, y: ev.clientY, cx: mcx, cy: mcy }; c.style.cursor = 'grabbing'; });
  c.addEventListener('pointermove', ev => { if (!pres) return; const s = 256 * Math.pow(2, zc);
    mcx = pres.cx - (ev.clientX - pres.x) / s; mcy = Math.max(0, Math.min(1, pres.cy - (ev.clientY - pres.y) / s)); dessineCarte(); });
  c.addEventListener('pointerup', () => { pres = null; c.style.cursor = 'grab'; });
  c.addEventListener('wheel', ev => { ev.preventDefault(); const r = c.getBoundingClientRect(), s = 256 * Math.pow(2, zc);
    const mx = mcx + (ev.clientX - r.left - r.width / 2) / s, my = mcy + (ev.clientY - r.top - r.height / 2) / s;
    zc = Math.max(1, Math.min(16, zc + (ev.deltaY > 0 ? -0.35 : 0.35))); const s2 = 256 * Math.pow(2, zc);
    mcx = mx - (ev.clientX - r.left - r.width / 2) / s2; mcy = my - (ev.clientY - r.top - r.height / 2) / s2; dessineCarte(); }, { passive: false });
  c.addEventListener('dblclick', () => { carteCadree = false; dessineCarte(); });
}
function empreinte(lat, lon, rho) {
  const out = [], la = lat * Math.PI / 180, lo = lon * Math.PI / 180, d = rho * Math.PI / 180;
  for (let i = 0; i <= 72; i++) { const b = i * 5 * Math.PI / 180;
    const la2 = Math.asin(Math.sin(la) * Math.cos(d) + Math.cos(la) * Math.sin(d) * Math.cos(b));
    const lo2 = lo + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(la), Math.cos(d) - Math.sin(la) * Math.sin(la2));
    out.push([la2 * 180 / Math.PI, ((lo2 * 180 / Math.PI + 540) % 360) - 180]); }
  return out;
}
function dessineCarte() {
  const c = g('carte'); if (!c || c.style.display === 'none' || !P) return;
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  if (!carteCadree && w > 0) { cadreCarte(w, h); carteCadree = true; }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.fillStyle = '#AAD3DF'; x.fillRect(0, 0, w, h);
  const z = Math.max(0, Math.min(16, Math.floor(zc))), k = Math.pow(2, zc - z), ts = 256 * k, s = 256 * Math.pow(2, zc);
  const n = 1 << z, ox = w / 2 - mcx * s, oy = h / 2 - mcy * s;
  const tx0 = Math.floor(-ox / ts), tx1 = Math.floor((w - ox) / ts), ty0 = Math.max(0, Math.floor(-oy / ts)), ty1 = Math.min(n - 1, Math.floor((h - oy) / ts));
  for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) { const i = tuile(z, tx, ty); if (i) x.drawImage(i, ox + tx * ts, oy + ty * ts, ts + 0.5, ts + 0.5); }
  const ecran = (lat, lon) => { let px = ox + wx(lon) * s; const py = oy + wy(lat) * s; const larg = s;
    while (px < -larg / 2 + w / 2 - larg / 2) px += larg; while (px > w / 2 + larg / 2) px -= larg; return [px, py]; };
  const trace = (l, couleur, ep, tiret) => { x.strokeStyle = couleur; x.lineWidth = ep; x.setLineDash(tiret || []); x.beginPath(); let pr = null;
    l.forEach(q => { const p = ecran(q[0], q[1]); if (pr && Math.abs(p[0] - pr[0]) < s / 2) x.lineTo(p[0], p[1]); else x.moveTo(p[0], p[1]); pr = p; }); x.stroke(); x.setLineDash([]); };
  const sol = P.sol || [];
  trace(sol.map(q => [q[1], q[2]]), 'rgba(0,229,255,.35)', 3);
  const fait = sol.filter(q => q[0] <= t).map(q => [q[1], q[2]]); const ici = solA(t); if (ici) fait.push([ici[1], ici[2]]);
  trace(fait, '#00E5FF', 4);
  P.marques.filter(m => m.k === 'SSTV').forEach(m => { const l = sol.filter(q => q[0] >= m.de && q[0] <= m.a).map(q => [q[1], q[2]]);
    const a = solA(m.de), b = solA(m.a); if (a) l.unshift([a[1], a[2]]); if (b) l.push([b[1], b[2]]); trace(l, COULHEX.SSTV, 7); });
  if (ici) {
    const rho = Math.acos(6371 / (6371 + ici[3])) * 180 / Math.PI;
    const e = empreinte(ici[1], ici[2], rho); x.fillStyle = 'rgba(255,194,26,.12)'; x.beginPath(); let pr = null;
    e.forEach(q => { const p = ecran(q[0], q[1]); if (pr && Math.abs(p[0] - pr[0]) < s / 2) x.lineTo(p[0], p[1]); else x.moveTo(p[0], p[1]); pr = p; }); x.fill();
    trace(e, 'rgba(255,194,26,.9)', 2);
  }
  // The stations worked or heard, linked to the satellite at their moment.
  P.marques.filter(m => m.lat != null && m.de <= t).forEach(m => {
    const p = ecran(m.lat, m.lon), sat = solA(m.de);
    if (sat) { const q = ecran(sat[1], sat[2]); x.strokeStyle = COULHEX[couleur(m)]; x.globalAlpha = .6; x.lineWidth = 1.5; x.beginPath(); x.moveTo(p[0], p[1]); x.lineTo(q[0], q[1]); x.stroke(); x.globalAlpha = 1; }
    x.fillStyle = COULHEX[couleur(m)]; x.beginPath(); x.arc(p[0], p[1], 6, 0, 7); x.fill(); x.strokeStyle = '#0B1016'; x.lineWidth = 2; x.stroke();
    etiquette(x, m.t, p[0] + 9, p[1] - 9);
  });
  if (P.qth) { const p = ecran(P.qth[0], P.qth[1]); x.fillStyle = '#111'; x.beginPath(); x.arc(p[0], p[1], 7, 0, 7); x.fill(); x.strokeStyle = '#fff'; x.lineWidth = 2; x.stroke();
    if (O.locator) etiquette(x, [P.indicatif, P.loc].filter(v => v).join(' · '), p[0] - 12, p[1] + 4, true); }
  if (ici) { const p = ecran(ici[1], ici[2]); x.fillStyle = '#FFC21A'; x.beginPath(); x.arc(p[0], p[1], 9, 0, 7); x.fill(); x.strokeStyle = '#0B1016'; x.lineWidth = 2; x.stroke(); }
}
function etiquette(x, texte, px, py, gauche) {
  if (!texte) return; x.font = 'bold 12px system-ui'; const w = x.measureText(texte).width + 10;
  const ax = gauche ? px - w : px; x.fillStyle = 'rgba(255,255,255,.9)'; x.fillRect(ax, py - 9, w, 18);
  x.fillStyle = '#111'; x.fillText(texte, ax + 5, py + 4);
}

// ------------------------------------------------------------ the moment
function avant(l, tt) { let r = null; for (const x of l) { if (x[0] <= tt) r = x; else break; } return r; }
function libelleS(v) { return v <= 120 ? 'S' + Math.round(v * 9 / 120) : 'S9+' + Math.min(60, Math.max(10, Math.round((v - 120) * 60 / 121 / 10) * 10)); }
function part(n) { return Math.max(0, Math.min(1, n <= 120 ? n / 120 * 0.6 : 0.6 + (n - 120) / 121 * 0.4)); }
let imageVue = '';
function majMoment() {
  g('heure').textContent = hms(t) + (utc ? ' UTC' : '');
  const q = ptA(t); g('angles').textContent = q ? 'Az ' + Math.round(q[1]) + '°  ·  El ' + Math.round(q[2]) + '°' : '';
  const f = O.freq ? avant(P.freq, t + 2000) : null; g('rx').textContent = f ? 'RX ' + (f[1] / 1e6).toFixed(4) + ' MHz' : '';
  const avecS = O.smetre && P.sig.length;
  ['smetre', 'grad', 'lectureS'].forEach(id => g(id).classList.toggle('cache', !avecS));
  if (avecS) {
    const s = avant(P.sig, t); const v = s && t - s[0] <= 3000 ? s[1] : null;
    const pic = P.sig.filter(x => x[0] <= t && x[0] >= t - 2000).reduce((m, x) => Math.max(m, x[1]), -1);
    const lu = v == null ? -1 : part(v), pp = pic < 0 ? -1 : part(pic);
    g('lectureS').textContent = v == null ? '' : T.smetre + ' ' + libelleS(v);
    [...g('smetre').children].forEach((b, i) => { const fr = (i + 0.5) / 46; b.classList.toggle('on', fr <= lu); b.classList.toggle('pic', pp > lu && Math.abs(fr - pp) < 0.5 / 46); });
  }
  const img = O.sstv ? P.marques.find(m => m.k === 'SSTV' && m.f && t >= m.de && t <= m.a + 4000) : null;
  const im = g('imageMoment');
  if (img) { if (imageVue !== img.f) { im.src = url('/image', { f: img.f }); imageVue = img.f; } im.style.display = 'block';
    const fr = Math.max(0, Math.min(1, (t - img.de) / Math.max(1, img.a - img.de))); im.style.clipPath = 'inset(0 0 ' + ((1 - fr) * 100).toFixed(1) + '% 0)'; }
  else { im.style.display = 'none'; imageVue = ''; }
  const st = O.fiches ? P.marques.filter(m => (m.k === 'QSO' || m.k === 'APRS') && t >= m.de - 2000 && t <= m.a + 12000).pop() : null;
  const cm = g('carteMoment'); cm.innerHTML = '';
  if (st) { const fi = fiches[st.t] || fiches[(st.t || '').split('-')[0]];
    cm.appendChild(el('div', {}, [el('span', { class: 'pastille', style: 'background:' + COUL[couleur(st)] }), el('b', { text: st.t })]));
    if (fi) cm.appendChild(el('div', { class: 'nom', text: [fi.nom, fi.qth, fi.pays, fi.loc].filter(v => v).join(' · ') }));
    if (st.d) cm.appendChild(el('div', { class: 'nom', text: st.d })); }
}

// ------------------------------------------------------------ the timeline
function tx(tt, w) { return (tt - vue0) / (vue1 - vue0) * w; }
function tDeX(x, w) { return vue0 + x / w * (vue1 - vue0); }
function dessineFrise() {
  const c = g('frise'); if (!c || !P) return;
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0); x.clearRect(0, 0, w, h);
  x.fillStyle = '#9AA7B4'; x.font = '10px system-ui'; x.textAlign = 'center';
  const pas = [10, 30, 60, 120, 300, 600].map(s => s * 1000).find(s => (vue1 - vue0) / s < w / 70) || 600000;
  for (let tt = Math.ceil(vue0 / pas) * pas; tt <= vue1; tt += pas) { const px = tx(tt, w); x.fillRect(px, h - 14, 1, 3); x.fillText(hms(tt).slice(0, pas < 60000 ? 8 : 5), px, h - 2); }
  x.fillStyle = '#18232D'; x.fillRect(tx(P.de, w), 4, tx(P.a, w) - tx(P.de, w), h - 20);
  P.sons.forEach(s => { x.fillStyle = 'rgba(63,224,200,.12)'; x.fillRect(tx(s.o + s.an, w), 4, tx(s.o + s.du, w) - tx(s.o + s.an, w), h - 20); });
  // Accelerated: the fast stretches hatched.
  if (O.accelere) segments.forEach(s => { if (s[2] > 1) { x.fillStyle = 'rgba(0,0,0,.35)'; x.fillRect(tx(s[0], w), 4, tx(s[1], w) - tx(s[0], w), h - 20); } });
  x.fillStyle = 'rgba(205,215,230,.45)'; activite.forEach(r => x.fillRect(tx(r[0], w), 36, Math.max(2, tx(r[1], w) - tx(r[0], w)), 7));
  if (P.sig.length) { x.strokeStyle = 'rgba(235,240,245,.8)'; x.lineWidth = 1.3; x.beginPath();
    P.sig.forEach((s, i) => { const px = tx(s[0], w), py = h - 18 - part(s[1]) * 22; i ? x.lineTo(px, py) : x.moveTo(px, py); }); x.stroke(); }
  P.marques.forEach(m => { x.fillStyle = COULHEX[couleur(m)]; const a = tx(m.de, w), b = tx(m.a, w); x.fillRect(a, 8, Math.max(4, b - a), 24); });
  if (sel) { x.fillStyle = 'rgba(63,224,200,.18)'; x.fillRect(tx(sel[0], w), 0, tx(sel[1], w) - tx(sel[0], w), h - 16);
    x.fillStyle = '#3FE0C8'; x.fillRect(tx(sel[0], w), 0, 2, h - 16); x.fillRect(tx(sel[1], w) - 2, 0, 2, h - 16); }
  x.fillStyle = '#FFC21A'; x.fillRect(tx(t, w) - 1, 0, 3, h - 14);
}
function installeFrise() {
  const c = g('frise'); let pres = null;
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); pres = { x: ev.offsetX, t: tDeX(ev.offsetX, c.clientWidth), bouge: false }; });
  c.addEventListener('pointermove', ev => { if (!pres) return; if (Math.abs(ev.offsetX - pres.x) > 4) pres.bouge = true;
    if (pres.bouge) { const b = tDeX(ev.offsetX, c.clientWidth); sel = [Math.min(pres.t, b), Math.max(pres.t, b)]; maj(); } });
  c.addEventListener('pointerup', () => { if (pres && !pres.bouge) cherche(pres.t); else if (sel) deQuoi = 'sel'; pres = null; maj(); if (onglet === 'fabriquer') panneau(); });
  c.addEventListener('wheel', ev => { ev.preventDefault(); const w = c.clientWidth, m = tDeX(ev.offsetX, w), k = ev.deltaY > 0 ? 1.25 : 0.8;
    const l = Math.max(20000, Math.min((P.t1 - P.t0) + 1200000, (vue1 - vue0) * k));
    vue0 = m - (m - vue0) / (vue1 - vue0) * l; vue1 = vue0 + l; dessineFrise(); }, { passive: false });
  c.addEventListener('dblclick', () => { vue0 = P.t0; vue1 = P.t1; dessineFrise(); });
}
function zoomSur(r) { const l = r[1] - r[0]; vue0 = r[0] - l * 0.3; vue1 = r[1] + l * 0.3; }

// ------------------------------------------------------------ replay with the sound
let morceauJoue = -1, dernier = 0;
function morceauA(tt) { return P.sons.findIndex(s => tt >= s.o + s.an && tt < s.o + s.du); }
/** Accelerated: how much faster here (1: normal, with the sound). */
function rapideIci(tt) { if (!O.accelere) return 1; const s = segments.find(s => tt >= s[0] && tt < s[1]); return s ? s[2] : 1; }
function synchro() {
  const a = g('son'), i = morceauA(t);
  if (!joue || i < 0 || rapideIci(t) > 1) { if (!a.paused) a.pause(); return; }
  const s = P.sons[i], pos = (t - s.o) / 1000;
  if (morceauJoue !== i) { morceauJoue = i; a.src = url('/son', { id: P.id, n: s.n }); a.playbackRate = vitesse;
    a.addEventListener('loadedmetadata', () => { a.currentTime = (t - s.o) / 1000; a.play().catch(() => {}); }, { once: true }); return; }
  if (Math.abs(a.currentTime - pos) > 0.6) a.currentTime = pos;
  if (a.paused) a.play().catch(() => {});
}
function boucle(now) {
  if (!joue) return;
  const dt = now - (dernier || now); dernier = now;
  const a = g('son'), i = morceauA(t), r = rapideIci(t);
  if (r === 1 && i >= 0 && i === morceauJoue && !a.paused && a.readyState >= 2) t = P.sons[i].o + a.currentTime * 1000;
  else t += dt * vitesse * (r > 1 ? r : (P.sons.length ? 1 : 10));
  if (t >= P.t1) { arrete(); t = P.t1; }
  else { const ri = rapideIci(t), mi = morceauA(t);
    if ((ri > 1 && !a.paused) || (ri === 1 && (mi !== morceauJoue || (mi >= 0 && a.paused)))) synchro(); }
  maj();
  requestAnimationFrame(boucle);
}
function bascule() { if (joue) arrete(); else { joue = true; dernier = 0; if (t >= P.t1) t = P.de; synchro(); requestAnimationFrame(boucle); maj(); } }
function arrete() { joue = false; const a = g('son'); if (!a.paused) a.pause(); if (P) maj(); }
function cherche(tt) { t = Math.max(P.t0, Math.min(P.t1, tt)); if (t < vue0 || t > vue1) { const l = vue1 - vue0; vue0 = t - l / 2; vue1 = vue0 + l; } if (joue) synchro(); maj(); }
let carteMs = 0;
function maj() {
  if (!P) return;
  majCiel(); majMoment(); dessineFrise();
  // The map, redrawn a few times a second during the replay (its tiles are pictures).
  if (O.vue === 'carte' && performance.now() - carteMs > 120) { carteMs = performance.now(); dessineCarte(); }
  g('lecture').textContent = joue ? '⏸ ' + T.pause : '▶ ' + T.lecture;
  const r = rapideIci(t);
  g('position').textContent = duree(Math.max(0, t - P.de)) + ' / ' + duree(P.a - P.de) + (joue && r > 1 ? ' · ' + tf('accelere_actif', r) : '');
  const s = g('selection'); s.innerHTML = '';
  if (sel) { s.appendChild(el('span', { text: tf('selection', hms(sel[0]), hms(sel[1]), duree(sel[1] - sel[0])) }));
    s.appendChild(el('button', { class: 'discret', text: T.effacer, onclick: () => { sel = null; deQuoi = 'tout'; maj(); if (onglet === 'fabriquer') panneau(); } })); }
}
document.addEventListener('keydown', ev => {
  if (!P || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
  if (ev.code === 'Space') { ev.preventDefault(); bascule(); }
  else if (ev.key === 'ArrowLeft') { ev.preventDefault(); cherche(t - (ev.shiftKey ? 30000 : 5000)); }
  else if (ev.key === 'ArrowRight') { ev.preventDefault(); cherche(t + (ev.shiftKey ? 30000 : 5000)); }
});
window.addEventListener('resize', () => { if (P) { dessineFrise(); dessineCarte(); } });

// ------------------------------------------------------------ making on the phone
async function fabrique(quoi) {
  const b = v => v ? '1' : '0';
  const p = { id: P.id, quoi: quoi, carte: O.vue === 'carte' ? '1' : '0', t: Math.round(t), res: O.res, ouverture: b(O.ouverture), recap: b(O.recap),
    avecSon: b(O.avecSon), accelere: b(O.accelVideo), avant: O.avant, apres: O.apres, rapide: O.rapide, surActivite: b(O.surActivite),
    sstv: b(O.sstv), fiches: b(O.fiches), smetre: b(O.smetre), freq: b(O.freq), locator: b(O.locator) };
  if (deQuoi === 'sel' && sel && quoi !== 'image' && quoi !== 'paquet') { p.de = Math.round(sel[0]); p.a = Math.round(sel[1]); p.titre = titre; }
  const zone = g('fabrication'); zone.innerHTML = '';
  const barre = el('progress', { max: 100, value: 0 }), mot = el('div', { class: 'aide', text: tf('en_cours', 0) });
  zone.appendChild(mot); zone.appendChild(barre);
  const j = await lire('/fabrique', p); if (!j.ok) { mot.textContent = T.echec; return; }
  while (true) {
    await new Promise(r => setTimeout(r, 700));
    const e = await lire('/travail', { n: j.travail });
    barre.value = Math.round(e.progres * 100); mot.textContent = tf('en_cours', Math.round(e.progres * 100));
    if (!e.fini) continue;
    zone.innerHTML = '';
    if (!e.ok) { zone.appendChild(el('div', { class: 'aide', text: T.echec })); return; }
    const lien = url('/fichier', { n: j.travail });
    zone.appendChild(el('div', { class: 'rangee' }, [el('span', { text: tf('pret', e.nom, (e.taille / 1048576).toFixed(1) + ' Mo') }),
      el('a', { href: lien, download: e.nom }, [el('button', { class: 'primaire', text: '⤓ ' + T.telecharger })])]));
    if (/\.(png|gif)$/.test(e.nom)) zone.appendChild(el('img', { src: url('/fichier', { n: j.travail, voir: '1' }), style: 'max-width:100%;max-height:50vh;margin-top:6px;border-radius:6px' }));
    else if (/\.mp4$/.test(e.nom)) zone.appendChild(el('video', { src: url('/fichier', { n: j.travail, voir: '1' }), controls: '', style: 'max-width:100%;max-height:50vh;margin-top:6px' }));
    else if (/\.wav$/.test(e.nom)) zone.appendChild(el('audio', { src: url('/fichier', { n: j.travail, voir: '1' }), controls: '', style: 'width:100%;margin-top:6px' }));
    return;
  }
}

g('tPorte').textContent = T.titre; g('tCode').textContent = T.code; g('entrer').textContent = T.entrer;
g('tTitre').textContent = 'SatMe · ' + T.titre; g('tSous').textContent = T.sous; g('tChoisir').textContent = T.choisir;
g('lienPupitre').textContent = T.pupitre; g('lienPupitre').href = base + '/';
g('lienPlanche').textContent = T.planche; g('lienPlanche').href = base + '/planche';
g('entrer').onclick = entrer;
g('code').addEventListener('keydown', ev => { if (ev.key === 'Enter') entrer(); });
g('filtre').onchange = ev => { filtre = ev.target.value; dessineListe(); };
if (cle) demarre().catch(() => {}); else porte();
</script>
</body>
</html>
"""
}
