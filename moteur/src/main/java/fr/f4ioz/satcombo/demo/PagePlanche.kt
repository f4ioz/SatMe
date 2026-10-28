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
 * moved with the mouse, the pictures dragged in, the texts typed; its
 * background chosen, patches laid on it, moved and resized; the template
 * given as one file (`.satme-planche`) and another's taken in.
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
        "par_defaut", "pupitre", "journal", "nb_images", "nb_modele", "ajustee", "aide_ajustee", "avant", "apres",
        "pleine", "decor", "fond_planche", "fond_choisir", "fond_aide", "fond_modele", "fond_perso", "ecussons", "ecusson",
        "ecusson_ajouter", "ecusson_aide", "ecusson_contour", "ecusson_contour_aide", "ecusson_supprimer", "ecusson_plein",
        "envoi", "image_illisible", "exporter", "exporter_titre", "exporter_aide", "exporter_indicatif", "exporter_ok",
        "annuler", "importer_aide", "modele_importe", "refus_version", "refus_fichier", "deposer",
        "renommer", "renommer_q", "evenement", "ev_auto", "ev_main", "ev_auto_revenir", "ev_aucun", "ev_campagne", "ev_activite",
        "ev_images", "filtre_ev", "remplir_diapos", "garder_vides", "rempli_diapos", "rempli_toutes", "sans_numero", "hors_cases",
        "relancer", "relancer_aide", "relancer_oublie", "relancer_confirme", "analyse_en_cours", "analyse_finie", "copies", "grouper",
        "note", "origine_lu", "origine_main", "origine_estime", "rapport", "rapport_titre", "rapport_aide", "rapport_chaine",
        "rapport_plan", "rapport_ouvrir", "rapport_telecharger", "rapport_sans_ev", "corrigees", "marque_traitees",
        "rapport_format", "rapport_web", "rapport_pdf", "version_titre", "version_auto", "version_recue", "version_corrigee",
        "version_fusion", "version_creer_fusion", "version_fusion_en_cours", "version_fusion_echec", "version_repli", "version_auto_aide",
        "version_copies", "version_main", "version_vide", "version_sans", "version_note", "tout_reinitialiser", "tout_reinitialiser_aide",
        "gardes", "version_indic", "refus_depot", "supprimer", "supprimer_q", "supprimee", "cases_max",
        "rapport_vue", "rapport_dessin", "rapport_simple", "affiche", "titres", "titres_aide", "titre_aucun", "style", "police",
        "police_defaut", "italique", "filets", "espacement", "couleur2", "cadre", "cadre_couleur")

    /** The keys whose words depend on who serves the page: SatMe-PC has its own (`pw_…_pc`), the phone the plain ones. */
    private val CLES_HOTE = setOf("sous", "code", "import_en_cours", "fabrication", "enregistre", "hors_ligne", "aucune_image")

    /** The string key of [k] on this host. */
    internal fun cle(k: String, surPc: Boolean): String = if (surPc && k in CLES_HOTE) "pw_${k}_pc" else "pw_$k"

    private fun js(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '<' -> append("\\u003c")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    /** The page in the app's language; [surPc] when SatMe-PC serves it (its pictures and sheets are the PC's, not a phone's). */
    fun html(surPc: Boolean = false): String = HTML
        .replace("/*T*/", CLES.joinToString(",") { "\"$it\":\"${js(t(cle(it, surPc)))}\"" })
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
  .zone.ecusson { border-color:rgba(229,72,229,.65); border-radius:6px; }
  .zone.ecusson.choisie { border-color:#E85BE8; background:rgba(229,72,229,.08); }
  .zone.ecusson .poignee { background:#E85BE8; }
  .zone.ecusson .etiq { display:none; top:-20px; left:0; white-space:nowrap; }
  .zone.ecusson:hover .etiq, .zone.ecusson.choisie .etiq { display:block; }
  .decors { display:grid; grid-template-columns:1fr 1fr 1fr; gap:8px; }
  .decors .img img { aspect-ratio:1.414; }
  h3 { font-size:14px; margin:14px 0 6px; }
  .etiq { position:absolute; left:2px; top:2px; font-size:11px; font-weight:700; padding:0 5px;
          border-radius:4px; background:rgba(0,0,0,.6); color:var(--clair); pointer-events:none; }
  .etiq.droite { left:auto; right:2px; }
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
  /* The slide a picture is (read, by hand, estimated) and its mark. */
  .img .diapo { position:absolute; left:4px; top:4px; background:rgba(0,0,0,.72); color:#fff; font-weight:800;
              font-size:12px; padding:0 6px; border-radius:5px; }
  .img .diapo.o-main { box-shadow:inset 0 -2px 0 var(--ambre); }
  .img .diapo.o-estime { font-style:italic; color:#cfd8e0; background:rgba(60,70,80,.8); }
  .img .note { position:absolute; right:4px; top:4px; background:rgba(0,0,0,.72); color:#FFD54F; font-weight:700; font-size:11px; padding:0 5px; border-radius:5px; }
  .img .note.bof { color:var(--ambre); } .img .note.mal { color:var(--rose); }
  .img .copies { color:var(--cyan); font-size:11px; padding:0 6px 4px; cursor:pointer; }
  .img.copie { opacity:.85; border-style:dashed; border-color:var(--bord); }
  .img.placee .num { left:auto; right:4px; top:auto; bottom:44px; }
  .ev { background:#0E1720; border:1px solid var(--bord); border-radius:8px; padding:8px; margin-bottom:8px; }
  .ev select { max-width:100%; }
  .champ { display:flex; flex-direction:column; gap:3px; margin:8px 0; }
  .champ label { color:var(--gris); font-size:12px; }
  .rangee { display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
  .rangee input[type=number] { width:80px; }
  .nuancier { display:flex; gap:6px; flex-wrap:wrap; }
  .nuance { width:26px; height:26px; border-radius:6px; border:2px solid var(--bord); cursor:pointer; }
  .nuance.choisie { border-color:var(--cyan); }
  #message { color:var(--ambre); font-size:13px; }
  #porte { max-width:420px; margin:60px auto; background:var(--carte); border:1px solid var(--bord);
           border-radius:14px; padding:22px; }
  #porte input { font-size:28px; letter-spacing:6px; width:100%; text-align:center; }
  /* Exporting the template: what goes in the file, said before it is made. */
  #dlgExport { position:fixed; inset:0; background:rgba(0,0,0,.6); display:flex; align-items:center; justify-content:center; z-index:20; }
  #dlgRapport { position:fixed; inset:0; background:rgba(0,0,0,.6); display:flex; align-items:center; justify-content:center; z-index:20; }
  #dlgRapport > div { max-width:540px; background:var(--carte); border:1px solid var(--bord); border-radius:14px; padding:18px; }
  #dlgExport > div { max-width:520px; background:var(--carte); border:1px solid var(--bord); border-radius:14px; padding:18px; }
  #depot { position:fixed; inset:12px; border:3px dashed var(--cyan); border-radius:16px; background:rgba(11,16,22,.82);
           display:flex; align-items:center; justify-content:center; font-size:20px; font-weight:700; color:var(--cyan);
           pointer-events:none; z-index:30; }
  /* A box's version: the slide's versions side by side, the one shown framed. */
  .versions { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:6px; margin-top:6px; }
  .version { position:relative; border:2px solid var(--bord); border-radius:8px; cursor:pointer; background:#0E1720; overflow:hidden; font-size:11px; }
  .version.choisie { border-color:var(--cyan); background:rgba(63,224,200,.10); }
  .version img { width:100%; aspect-ratio:4/3; object-fit:cover; display:block; pointer-events:none; }
  .version .lib { padding:2px 5px 0; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .version .n { color:#FFD54F; padding:0 5px 3px; }
  .version .vide { aspect-ratio:4/3; display:flex; align-items:center; justify-content:center; color:var(--gris); font-size:18px; }
  .copiesCase { display:flex; gap:6px; overflow-x:auto; margin-top:6px; }
  .copiesCase .version { flex:0 0 74px; }
  /* In the editor only (never on the sheet): a box whose version was chosen by hand. */
  .zone .vers { position:absolute; right:2px; bottom:2px; font-size:10px; font-weight:700; padding:0 4px; border-radius:4px;
                background:rgba(0,0,0,.6); color:var(--cyan); pointer-events:none; }
  .zone .vers.repli { color:var(--ambre); }
  /* A text's dress: fonts and effects shown as they look, the styled titles. */
  .choix { display:grid; grid-template-columns:repeat(auto-fill,minmax(118px,1fr)); gap:6px; margin:4px 0 8px; }
  .choix button { font-weight:400; padding:6px 4px; font-size:15px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .choix button.choisi { border-color:var(--cyan); background:rgba(63,224,200,.12); }
  .choix button small { display:block; font-family:system-ui,sans-serif; font-size:10px; color:var(--gris); }
  .titres button { font-weight:700; color:var(--cyan); }
  .st-neon { color:#bfefff; text-shadow:0 0 4px #2EC5FF, 0 0 10px #2EC5FF; }
  .st-chrome { color:#eef2f7; text-shadow:0 1px 0 #8796a8, 0 2px 0 #0b1e3e, 0 0 2px #000; }
  .st-or { color:#f7d25a; text-shadow:0 1px 0 #8a5a0e, 0 2px 0 #2a1702; }
  .st-contour { color:transparent; -webkit-text-stroke:1px #E8F0F7; }
  .st-relief { color:#E8F0F7; text-shadow:1px 1px 0 #555, 2px 2px 0 #444, 3px 3px 0 #333; }
  .st-degrade { color:#FFC21A; text-shadow:0 2px 0 #E5484D; }
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
  <button class="discret" id="renommer"></button>
  <button class="discret rouge" id="supprimer"></button>
  <button class="discret" id="grille"></button>
  <button class="discret" id="tour"></button>
  <button class="discret" id="affiche"></button>
  <button class="discret" id="importer"></button>
  <input type="file" id="fichier" accept="image/*,.satme-planche,.zip,application/zip" class="cache">
  <button class="discret" id="exporter"></button>
  <button class="discret" id="rapport"></button>
  <button class="discret cache" id="sitePlanche" title="Envoyer cette planche vers votre site web (SatMe-PC › Réglages › Envoyer vers un site web)">🌐 Planche vers le site</button>
  <button class="discret cache" id="siteRapport" title="Envoyer le rapport de son événement (PDF) vers votre site web">🌐 Rapport vers le site</button>
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
    <div class="onglets"><button id="oImages"></button><button id="oDispo"></button><button id="oTextes"></button><button id="oDecor"></button></div>
    <div id="pImages"></div>
    <div id="pDispo" class="cache"></div>
    <div id="pTextes" class="cache"></div>
    <div id="pDecor" class="cache"></div>
    <input type="file" id="fichierDecor" accept="image/*" class="cache">
    <input type="file" id="fichierEcusson" accept="image/*" class="cache">
  </div>
</div>
</div>
<div id="dlgExport" class="cache"><div>
  <h3 id="tExport" style="margin-top:0"></h3>
  <p class="aide" id="tExportAide"></p>
  <label class="rangee" id="lExportInd" style="margin:10px 0"><input type="checkbox" id="exportInd"><span id="tExportInd"></span></label>
  <div class="actions"><button class="primaire" id="exportOk"></button><button class="discret" id="exportNon"></button></div>
</div></div>
<div id="dlgRapport" class="cache dlg"><div>
  <h3 id="tRapport" style="margin-top:0"></h3>
  <p class="aide" id="tRapportAide"></p>
  <p id="rapportEv" style="font-weight:700"></p>
  <label class="rangee" style="margin:10px 0"><input type="checkbox" id="rapportChaine"><span id="tRapportChaine"></span></label>
  <div class="rangee" style="margin:0 0 10px"><span class="aide" id="tRapportFormat"></span>
    <label class="rangee"><input type="radio" name="rapportFormat" value="" checked><span id="tRapportWeb"></span></label>
    <label class="rangee"><input type="radio" name="rapportFormat" value="pdf"><span id="tRapportPdf"></span></label></div>
  <div class="rangee" id="lRapportPlan"><span class="aide" id="tRapportPlan"></span><select id="rapportPlan"></select></div>
  <div class="rangee" id="lRapportVue" style="margin:6px 0 0"><span class="aide" id="tRapportVue"></span>
    <label class="rangee"><input type="radio" name="rapportVue" value="dessin" checked><span id="tRapportDessin"></span></label>
    <label class="rangee"><input type="radio" name="rapportVue" value="simple"><span id="tRapportSimple"></span></label></div>
  <div class="actions"><button class="primaire" id="rapportOuvrir"></button><button class="discret" id="rapportDl"></button><button class="discret" id="rapportNon"></button></div>
</div></div>
<div id="depot" class="cache"></div>
<script>
const T = {/*T*/};
const CASES_MAX = ${fr.f4ioz.satcombo.sstv.Planche.CASES_MAX};
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
  const m = { cadre: '', cadreCouleur: 0xFF00E5FF | 0, nom: '', fond: '', ratio: 1.414, legende: false, numeros: false, titre: '', cases: [], textes: [], images: {}, origine: [], decor: '', ecussons: [], autres: [], vides: false, diapos: {}, versions: {}, indicatif: '', locator: '', dates: '' };
  txt.split('\n').forEach(l => {
    const i = l.indexOf('='); if (i < 0) return;
    const k = l.slice(0, i), v = l.slice(i + 1);
    if (k === 'nom') m.nom = unesc(v); else if (k === 'fond') m.fond = unesc(v);
    else if (k === 'ratio') m.ratio = parseFloat(v) || 1.414;
    else if (k === 'legende') m.legende = v === '1'; else if (k === 'numeros') m.numeros = v === '1';
    else if (k === 'titre') m.titre = unesc(v);
    else if (k === 'case') { const p = v.split(';').map(Number); m.cases.push({ x: p[0], y: p[1], w: p[2], h: p[3] }); }
    // A text, its dress after it when it has one (font, effect, second colour, italic, spacing, rules, styled title).
    else if (k === 'texte') { const p = v.split(';'); m.textes.push({ champ: p[0], x: +p[1], y: +p[2], w: +p[3], h: +p[4], couleur: parseInt(p[5]), fond: parseInt(p[6]), libre: unesc(p[7] || ''),
      police: p[8] || '', style: p[9] || '', couleur2: parseInt(p[10]) || 0, italique: p[11] === '1', espacement: parseFloat(p[12]) || 0, filets: p[13] === '1', groupe: p[14] || '' }); }
    else if (k === 'cadre') { const p = v.split(';'); m.cadre = p[0] || ''; m.cadreCouleur = parseInt(p[1]) || COULEURS[4]; }
    else if (k === 'image') { const j = v.indexOf(';'); m.images[+v.slice(0, j)] = v.slice(j + 1); }
    else if (k === 'origine') { const p = v.split(';').map(Number); m.origine.push({ x: p[0], y: p[1], w: p[2], h: p[3] }); }
    else if (k === 'decor') m.decor = unesc(v);
    else if (k === 'ecusson') { const p = v.split(';'); m.ecussons.push({ fichier: p[0], x: +p[1], y: +p[2], w: +p[3], h: +p[4], sans: p[5] === '1' }); }
    else if (k === 'vides') m.vides = v === '1';
    else if (k === 'diapo') { const j = v.indexOf(';'); if (j > 0) m.diapos[v.slice(0, j)] = v.slice(j + 1); }
    else if (k === 'corrigees') m.corrigees = v === '1';
    else if (k === 'marquetraitees') m.marqueTraitees = v === '1';
    // The callsign, locator and dates typed in « Textes »: kept with the sheet.
    else if (k === 'indicatif') m.indicatif = unesc(v); else if (k === 'locator') m.locator = unesc(v); else if (k === 'dates') m.dates = unesc(v);
    else if (k === 'versioncase') { const p = v.split(';'); if (['auto', 'recue', 'corrigee', 'fusion'].includes(p[1])) m.versions[+p[0]] = { v: p[1], main: p[2] === '1' }; }
    // What this page does not know (the texts' template places…) goes back as it came.
    else if (k !== 'planche') m.autres.push(l);
  });
  return m;
}
function ecrit(m) {
  let s = 'planche=1\nnom=' + esc(m.nom) + '\nfond=' + esc(m.fond) + '\nratio=' + m.ratio +
    '\nlegende=' + (m.legende ? 1 : 0) + '\nnumeros=' + (m.numeros ? 1 : 0) + '\ntitre=' + esc(m.titre) + '\n';
  m.cases.forEach(z => s += 'case=' + [r5(z.x), r5(z.y), r5(z.w), r5(z.h)].join(';') + '\n');
  m.textes.forEach(t => {
    s += 'texte=' + [t.champ, r5(t.x), r5(t.y), r5(t.w), r5(t.h), t.couleur | 0, t.fond | 0, esc(t.libre).split(';').join(',')].join(';');
    // Its dress, only when it has one: an older sheet stays written as it was.
    if (t.police || t.style || t.couleur2 || t.italique || t.espacement || t.filets || t.groupe)
      s += ';' + [t.police || '', t.style || '', t.couleur2 | 0, t.italique ? 1 : 0, +(t.espacement || 0), t.filets ? 1 : 0, t.groupe || ''].join(';');
    s += '\n';
  });
  Object.keys(m.images).forEach(i => s += 'image=' + i + ';' + m.images[i] + '\n');
  m.origine.forEach(z => s += 'origine=' + [r5(z.x), r5(z.y), r5(z.w), r5(z.h)].join(';') + '\n');
  if (m.decor) s += 'decor=' + esc(m.decor) + '\n';
  m.ecussons.forEach(e => s += 'ecusson=' + [e.fichier, r5(e.x), r5(e.y), r5(e.w), r5(e.h), e.sans ? 1 : 0].join(';') + '\n');
  if (m.vides) s += 'vides=1\n';
  Object.keys(m.diapos).forEach(f => { if (Object.values(m.images).includes(f)) s += 'diapo=' + f + ';' + m.diapos[f] + '\n'; });
  if (m.corrigees) s += 'corrigees=1\n';
  if (m.marqueTraitees) s += 'marquetraitees=1\n';
  Object.keys(m.versions).forEach(i => { const c = m.versions[i]; if (c && (c.v !== 'auto' || c.main) && +i < m.cases.length) s += 'versioncase=' + i + ';' + c.v + ';' + (c.main ? 1 : 0) + '\n'; });
  ['indicatif', 'locator', 'dates'].forEach(k => { if ((m[k] || '').trim()) s += k + '=' + esc(m[k]) + '\n'; });
  if (m.cadre) s += 'cadre=' + m.cadre + ';' + (m.cadreCouleur | 0) + '\n';
  m.autres.forEach(l => s += l + '\n');
  return s;
}

// ------------------------------------------------------------ state
const COULEURS = [0xFFFFFFFF | 0, 0xFF000000 | 0, 0xFF0B2D5B | 0, 0xFFFFC21A | 0, 0xFF00E5FF | 0, 0xFFE5484D | 0];
const FONDS = [0, 0xE6F2F4F8 | 0, 0x99000000 | 0];
const CHAMPS = ['INDICATIF', 'NOM_LOCATOR', 'DATES', 'TITRE', 'LIBRE'];
let id = '', M = null, ratio = 1.414, galerie = [], choix = null, onglet = 'images', filtre = '@ev', aFond = false, decors = [];
// The sheet's event, the events to choose from; copies grouped (one per slide), the groups opened.
let EV = { evenement: null, main: false, liste: [] }, grouper = true, analyse = '';
const ouverts = new Set();
try { grouper = localStorage.getItem('satme-planche-grouper') !== '0'; } catch (e) {}
let defauts = { indicatif: '', nom: '', locator: '', dates: '' };
// The fonts, effects, frames and styled titles the station offers (its own fonts, served by it: nothing from outside).
let STY = { polices: [], styles: [], cadres: [], titres: [], lueurs: [] };
async function chargeStyles() {
  try { STY = Object.assign(STY, await lire('/styles')); } catch (e) { return; }
  const f = STY.polices.map(x => "@font-face{font-family:'sp-" + x.id + "';src:url('" + url('/police', { f: x.id }) + "') format('truetype');font-display:swap}").join('\n');
  document.head.appendChild(el('style', { text: f }));
}
// The name is the station's (its settings); the callsign, locator and dates typed are the sheet's (M).
const saisie = { nom: '' };
function css(v) { const u = v >>> 0; return 'rgba(' + ((u >>> 16) & 255) + ',' + ((u >>> 8) & 255) + ',' + (u & 255) + ',' + (((u >>> 24) & 255) / 255).toFixed(3) + ')'; }
function zoneDe(c) { return c == null ? null : (c.k === 'c' ? M.cases[c.i] : c.k === 'e' ? M.ecussons[c.i] : M.textes[c.i]); }
// A patch keeps its picture's shape: within the sheet, never larger.
function borneEcusson(z) { const f = Math.min(1, 1 / Math.max(z.w, z.h, 0.001)); z.w = Math.max(0.005, z.w * f); z.h = Math.max(0.005, z.h * f); z.x = Math.min(1 - z.w, Math.max(0, z.x)); z.y = Math.min(1 - z.h, Math.max(0, z.y)); }
function bornePour(c, z) { if (c && c.k === 'e') borneEcusson(z); else borne(z); }
function echelle(z, f) { f = Math.max(0.03 / Math.max(z.w, 0.001), Math.min(f, 1 / Math.max(z.w, z.h, 0.001))); const w = z.w * f, h = z.h * f; z.x += (z.w - w) / 2; z.y += (z.h - h) / 2; z.w = w; z.h = h; borneEcusson(z); }
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
    const rp = j.replis || []; if (rp.join() !== REPLIS.join()) { REPLIS = rp; dessineCalque(); }
    if (onglet === 'textes' && !['INPUT', 'SELECT'].includes(document.activeElement.tagName)) panneau();
  } catch (e) {}
}
// A field typed in: saved (then the preview drawn again) a moment after, the panel left as it is (the cursor stays in the field).
function sauveBientot() { clearTimeout(minuteur); minuteur = setTimeout(sauve, 600); }
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
  // The box's number: the slide its picture went in for (« Fill by slide »), else its place.
  M.cases.forEach((z, i) => {
    const d = zoneEl(z, 'c', i, (M.images[i] !== undefined && M.diapos[M.images[i]]) || ((i + 1) + '/' + n));
    // The version chosen by hand, small, in the editor only (« ⚠ » when it is gone: Auto shown).
    const v = M.versions[i];
    if (REPLIS.includes(i)) d.appendChild(el('span', { class: 'vers repli', text: '⚠ ' + T.version_auto, title: T.version_repli }));
    else if (v && v.v !== 'auto') d.appendChild(el('span', { class: 'vers', text: T['version_' + v.v], title: T.version_indic }));
    else if (v && v.main) d.appendChild(el('span', { class: 'vers', text: '✋', title: T.version_main }));
    c.appendChild(d);
  });
  M.textes.forEach((z, i) => c.appendChild(zoneEl(z, 't', i, T['champ_' + z.champ.toLowerCase()] || z.champ)));
  // The patches over all the rest, as on the sheet.
  M.ecussons.forEach((z, i) => c.appendChild(zoneEl(z, 'e', i, T.ecusson + ' ' + (i + 1))));
}
function zoneEl(z, k, i, etiquette) {
  const d = el('div', { class: 'zone' + (k === 't' ? ' texte' : k === 'e' ? ' ecusson' : '') + (choix && choix.k === k && choix.i === i ? ' choisie' : '') });
  place(d, z);
  // When the sheet numbers its boxes itself (top left), the editing label goes to the right: not over it.
  d.appendChild(el('span', { class: 'etiq' + (k === 'c' && M.numeros ? ' droite' : ''), text: etiquette }));
  const h = el('div', { class: 'poignee' }); d.appendChild(h);
  d.addEventListener('pointerdown', ev => debutGeste(ev, k, i, ev.target === h));
  // The wheel over a patch: larger or smaller, round its centre.
  if (k === 'e') d.addEventListener('wheel', ev => {
    ev.preventDefault(); const z = M.ecussons[i]; if (!z) return;
    choix = { k: 'e', i: i }; echelle(z, ev.deltaY < 0 ? 1.08 : 1 / 1.08); change();
  }, { passive: false });
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
  choix = { k: k, i: i }; if (k === 'e') onglet = 'decor';
  // A box: its panel (the picture, its version) — unless its layout is being worked on.
  if (k === 'c' && onglet !== 'dispo') onglet = 'images';
  dessineCalque(); panneau();
  const z = zoneDe(choix), r = g('scene').getBoundingClientRect();
  geste = { x0: ev.clientX, y0: ev.clientY, z0: Object.assign({}, z), r: r, agrandit: agrandit, bouge: false };
}
window.addEventListener('pointermove', ev => {
  if (!geste) return;
  const z = zoneDe(choix), dx = (ev.clientX - geste.x0) / geste.r.width, dy = (ev.clientY - geste.y0) / geste.r.height;
  if (Math.abs(dx) + Math.abs(dy) > 0.001) geste.bouge = true;
  if (geste.agrandit && choix.k === 'e') { z.w = Math.max(0.005, geste.z0.w + dx); z.h = z.w * geste.z0.h / geste.z0.w; }
  else if (geste.agrandit) { z.w = geste.z0.w + dx; z.h = geste.z0.h + dy; } else { z.x = geste.z0.x + dx; z.y = geste.z0.y + dy; }
  bornePour(choix, z);
  const d = g('calque').querySelector('.choisie'); if (d) place(d, z);
});
window.addEventListener('pointerup', () => { if (geste && geste.bouge) change(); geste = null; });
g('scene').addEventListener('pointerdown', ev => { if (ev.target.id === 'calque' || ev.target.id === 'apercu') { choix = null; dessineCalque(); panneau(); } });
document.addEventListener('keydown', ev => {
  if (!choix || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
  const pas = ev.shiftKey ? 0.02 : 0.002, z = zoneDe(choix); if (!z) return;
  const d = { ArrowLeft: [-pas, 0], ArrowRight: [pas, 0], ArrowUp: [0, -pas], ArrowDown: [0, pas] }[ev.key];
  if (!d) return;
  ev.preventDefault(); z.x += d[0]; z.y += d[1]; bornePour(choix, z); change();
});

// ------------------------------------------------------------ pictures
function metImage(i, f) {
  if (!f || !M) return;
  for (const k in M.images) if (M.images[k] === f) delete M.images[k];
  M.images[i] = f;
  // A copy put in by hand: « Fill by slide » keeps it while it is of that box's slide.
  M.versions[i] = Object.assign({ v: 'auto' }, M.versions[i], { main: true });
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
  if (M.origine.length) { s.textContent = tf('ajustee', M.cases.length, M.origine.length); s.style.color = 'var(--cyan)'; return; }
  if (!manquent.length && recues > 0) { s.textContent = tf('serie_complete', recues); s.style.color = 'var(--cyan)'; }
  else { s.textContent = tf('serie', recues, M.cases.length, manquent.join(', ')); s.style.color = ''; }
}

// ------------------------------------------------------------ the side panel
function panneau() {
  ['images', 'dispo', 'textes', 'decor'].forEach(o => {
    g('p' + o.charAt(0).toUpperCase() + o.slice(1)).classList.toggle('cache', onglet !== o);
    g('o' + o.charAt(0).toUpperCase() + o.slice(1)).classList.toggle('actif', onglet === o);
  });
  if (!M) { g('pImages').innerHTML = ''; g('pImages').appendChild(el('p', { class: 'aide', text: T.aucun_modele })); return; }
  if (onglet === 'images') panneauImages(); else if (onglet === 'dispo') panneauDispo(); else if (onglet === 'decor') panneauDecor(); else panneauTextes();
}
function evNom(e) { return e ? e.nom + ' · ' + ({ campagne: T.ev_campagne, activite: T.ev_activite, images: T.ev_images }[e.genre] || '') : T.ev_aucun; }
// What « Fill » takes: the event's pictures, all of them, or one satellite's.
function filtreParams() { return filtre === '@ev' ? { sat: '', tous: 0 } : filtre === '' ? { sat: '', tous: 1 } : { sat: filtre, tous: 0 }; }
function classeNote(n) { return n < 40 ? ' mal' : n < 70 ? ' bof' : ''; }
function titreDiapo(x) { return x.o === 'lu' ? T.origine_lu : x.o === 'main' ? T.origine_main : x.o === 'estime' ? T.origine_estime : ''; }
function panneauImages() {
  const p = g('pImages'); p.innerHTML = '';
  // The sheet's event: chosen from its pictures, or by hand.
  const ev = el('div', { class: 'ev' });
  const sev = el('select', { onchange: async e => { await sauveMaintenant(); await lire('/evenement', { id: id, ev: e.target.value }); await chargeEvenement(); await chargeGalerie(); panneau(); } });
  sev.appendChild(el('option', { value: '', text: T.ev_auto_revenir + (EV.evenement && !EV.main ? ' : ' + evNom(EV.evenement) : '') }));
  EV.liste.forEach(e => sev.appendChild(el('option', { value: e.id, text: evNom(e) + (e.images ? ' (' + e.images + ')' : '') })));
  sev.appendChild(el('option', { value: 'aucun', text: T.ev_aucun }));
  sev.value = EV.main ? (EV.evenement ? EV.evenement.id : 'aucun') : '';
  ev.appendChild(el('div', { class: 'aide', text: T.evenement + ' — ' + (EV.main ? T.ev_main : T.ev_auto) }));
  ev.appendChild(sev);
  if (EV.evenement) ev.appendChild(el('div', { class: 'aide', text: heure(EV.evenement.de) + ' → ' + heure(EV.evenement.a) + ' · ' + EV.evenement.sat }));
  p.appendChild(ev);
  const sats = [...new Set(galerie.map(x => x.s).filter(s => s))];
  if (filtre === '@ev' && !EV.evenement) filtre = '';
  const opts = (EV.evenement ? [el('option', { value: '@ev', text: T.filtre_ev })] : []).concat([el('option', { value: '', text: T.tous })]).concat(sats.map(s => el('option', { value: s, text: s })));
  const sel = el('select', { onchange: ev => { filtre = ev.target.value; panneau(); } }, opts);
  sel.value = filtre;
  p.appendChild(el('div', { class: 'rangee' }, [sel,
    el('button', { class: 'discret', text: T.remplir, onclick: async () => { await sauveMaintenant(); await lire('/remplit', Object.assign({ id: id }, filtreParams())); await charge(id); } })]));
  // « Fill by slide »: slide n in box n, the best copy of each; a missing slide's box left empty.
  // What was chosen by hand (a version, a copy) kept, unless « Tout réinitialiser » is ticked.
  const reinit = el('input', { type: 'checkbox' });
  p.appendChild(el('div', { class: 'rangee', style: 'margin-top:6px' }, [
    el('button', { class: 'primaire', text: T.remplir_diapos, onclick: async () => {
      await sauveMaintenant();
      const j = await lire('/remplit-diapos', Object.assign({ id: id, reinit: reinit.checked ? 1 : 0 }, filtreParams()));
      await charge(id);
      if (j && j.ok) g('message').textContent = (j.manquantes.length ? tf('rempli_diapos', j.manquantes.join(', ')) : T.rempli_toutes) +
        (j.sansNumero ? ' ' + tf('sans_numero', j.sansNumero) : '') + (j.horsCases ? ' ' + tf('hors_cases', j.horsCases) : '') +
        (j.gardes ? ' ' + tf('gardes', j.gardes) : '');
    } }),
    el('span', { class: 'aide', text: T.garder_vides })]));
  p.appendChild(el('label', { class: 'rangee', title: T.tout_reinitialiser_aide }, [reinit, el('span', { class: 'aide', text: T.tout_reinitialiser })]));
  // How many pictures: the template's boxes, or the sheet fitted to fewer.
  const max = M.origine.length || M.cases.length, n = M.origine.length ? M.cases.length : 0;
  const rn = el('div', { class: 'rangee', style: 'margin-top:6px' }, [el('span', { class: 'aide', text: T.nb_images })]);
  [...new Set([0, 1, 2, 3, 4, 6, 8, 9, 12, 16, n])].filter(k => k <= max).sort((a, b) => a - b).forEach(k => {
    rn.appendChild(el('button', { class: k === n ? 'primaire' : 'discret', text: k ? String(k) : tf('nb_modele', max), onclick: async () => {
      await sauveMaintenant(); await lire('/nombre', Object.assign({ id: id, n: k }, filtreParams())); await charge(id); } }));
  });
  p.appendChild(rn);
  // The box chosen: which version of its slide it shows, which copy.
  if (choix && choix.k === 'c') panneauCase(p);
  const r2 = el('div', { class: 'rangee', style: 'margin-top:6px' });
  if (choix && choix.k === 'c' && M.images[choix.i] !== undefined) {
    const i = choix.i, va = j => { const a = M.images[i], b = M.images[j]; delete M.images[i]; delete M.images[j];
      if (a !== undefined) M.images[j] = a; if (b !== undefined) M.images[i] = b;
      // The choices made on them go with them.
      const va_ = M.versions[i], vb = M.versions[j]; delete M.versions[i]; delete M.versions[j];
      if (va_) M.versions[j] = va_; if (vb) M.versions[i] = vb;
      choix = { k: 'c', i: j }; change(); };
    if (i > 0) r2.appendChild(el('button', { class: 'discret', text: T.avant, onclick: () => va(i - 1) }));
    if (i < M.cases.length - 1) r2.appendChild(el('button', { class: 'discret', text: T.apres, onclick: () => va(i + 1) }));
  }
  if (choix && choix.k === 'c' && !M.origine.length) r2.appendChild(el('button', { class: 'discret', text: T.vider_case, onclick: () => {
    delete M.images[choix.i]; if (M.versions[choix.i]) M.versions[choix.i].main = false; change(); } }));
  r2.appendChild(el('button', { class: 'rouge', text: T.tout_vider, onclick: () => { M.images = {}; for (const k in M.versions) M.versions[k].main = false; change(); } }));
  p.appendChild(r2);
  // The pictures measured again, their numbers read again (what the hand set kept unless asked).
  const oublie = el('input', { type: 'checkbox' });
  p.appendChild(el('div', { class: 'rangee', style: 'margin-top:6px' }, [
    el('button', { class: 'discret', text: T.relancer, title: T.relancer_aide, onclick: async () => {
      if (oublie.checked && !confirm(T.relancer_confirme)) return;
      await lire('/relance', { oublie: oublie.checked ? 1 : 0 }); suitAnalyse();
    } }),
    el('label', { class: 'rangee' }, [oublie, el('span', { class: 'aide', text: T.relancer_oublie })])]));
  if (analyse) p.appendChild(el('p', { class: 'aide', text: analyse }));
  const gr = el('input', { type: 'checkbox' }); gr.checked = grouper;
  gr.addEventListener('change', () => { grouper = gr.checked; try { localStorage.setItem('satme-planche-grouper', grouper ? '1' : '0'); } catch (e) {} panneau(); });
  p.appendChild(el('label', { class: 'rangee', style: 'margin:6px 0' }, [gr, el('span', { text: T.grouper })]));
  p.appendChild(el('p', { class: 'aide', text: M.origine.length ? T.aide_ajustee : choix && choix.k === 'c' ? tf('choisir_case', choix.i + 1) : T.aide_images }));
  let liste = galerie.filter(x => filtre === '@ev' ? x.ev : !filtre || x.s === filtre);
  if (!liste.length) { p.appendChild(el('p', { class: 'aide', text: T.aucune_image })); return; }
  // Copies grouped: each slide's best, by slide number; its copies when its group is opened.
  if (grouper) {
    const parGroupe = {}; liste.forEach(x => (parGroupe[x.g] = parGroupe[x.g] || []).push(x));
    const tetes = liste.filter(x => x.best || !parGroupe[x.g].some(y => y.best));
    const vus = new Set(); const t2 = [];
    tetes.forEach(x => { if (!vus.has(x.g)) { vus.add(x.g); t2.push(x); } });
    t2.sort((a, b) => ((a.di || 99) - (b.di || 99)) || (b.t - a.t));
    liste = [];
    t2.forEach(x => { liste.push(x); if (ouverts.has(x.g)) parGroupe[x.g].filter(y => y !== x).sort((a, b) => b.n - a.n).forEach(y => liste.push(Object.assign({ copie: true }, y))); });
  }
  const ou = {}; for (const k in M.images) ou[M.images[k]] = +k;
  const grille = el('div', { class: 'galerie' });
  liste.forEach(x => {
    const placee = ou[x.f] !== undefined;
    const d = el('div', { class: 'img' + (placee ? ' placee' : '') + (x.copie ? ' copie' : ''), draggable: 'true' }, [
      el('img', { src: url('/vignette', { f: x.f, w: 320 }), loading: 'lazy' }),
      el('div', { class: 'l1', text: (x.s || 'SSTV') + ' · ' + x.m }),
      el('div', { class: 'l2', text: heure(x.t) + ' · ' + (x.d ? T.direct : T.redecodee) + (x.c ? '' : ' · ' + T.partielle) })]);
    if (x.b) d.appendChild(el('span', { class: 'diapo o-' + x.o, text: x.b, title: titreDiapo(x) }));
    if (x.n >= 0) d.appendChild(el('span', { class: 'note' + classeNote(x.n), text: String(x.n), title: tf('note', x.n) }));
    if (placee) d.appendChild(el('span', { class: 'num', text: (ou[x.f] + 1) + '/' + M.cases.length }));
    if (grouper && !x.copie && x.copies > 1) {
      const c = el('div', { class: 'copies', text: (ouverts.has(x.g) ? '▾ ' : '▸ ') + tf('copies', x.copies - 1) });
      c.addEventListener('click', ev => { ev.stopPropagation(); if (ouverts.has(x.g)) ouverts.delete(x.g); else ouverts.add(x.g); panneau(); });
      d.appendChild(c);
    }
    d.addEventListener('dragstart', ev => ev.dataTransfer.setData('text/plain', x.f));
    d.addEventListener('click', async () => {
      // A fitted sheet: the picture goes on it or comes off, the sheet fitted again.
      if (M.origine.length) {
        await sauveMaintenant(); const j = await lire('/bascule', { id: id, f: x.f });
        await charge(id); if (!j.ok) g('message').textContent = T.pleine; return;
      }
      let i = choix && choix.k === 'c' ? choix.i : -1;
      if (i < 0) i = M.cases.findIndex((_, j) => M.images[j] === undefined);
      if (i >= 0) metImage(i, x.f);
    });
    grille.appendChild(d);
  });
  p.appendChild(grille);
}
// ------------------------------------------------------- a box's version
// What the station knows of the box chosen (its slide's versions, its copies), asked once the sheet is saved.
let CASE = null, caseCle = '', caseSuit = null, caseV = 0, REPLIS = [];
function cleCase() { return choix && choix.k === 'c' ? [id, choix.i, M.images[choix.i] || '', (M.versions[choix.i] || {}).v || 'auto', M.corrigees ? 1 : 0].join('|') : ''; }
async function chargeCase() {
  const k = cleCase(); if (!k || caseCle === 'attente:' + k) return;
  caseCle = 'attente:' + k;
  try {
    await sauveMaintenant();
    const j = await lire('/case', { id: id, i: choix.i });
    if (cleCase() !== k) return;
    CASE = j; caseCle = k; caseV = Date.now(); if (onglet === 'images') panneau();
  } catch (e) { caseCle = ''; }
}
function choisitVersion(i, v) {
  const c = Object.assign({ v: 'auto', main: false }, M.versions[i], { v: v });
  if (c.v === 'auto' && !c.main) delete M.versions[i]; else M.versions[i] = c;
  REPLIS = REPLIS.filter(x => x !== i); change();
}
function carteVersion(lib, note, src, choisie, sur, titre) {
  const d = el('div', { class: 'version' + (choisie ? ' choisie' : ''), title: titre || lib, onclick: sur },
    [src ? el('img', { src: src, loading: 'lazy' }) : el('div', { class: 'vide', text: '＋' }), el('div', { class: 'lib', text: lib })]);
  if (note != null && note >= 0) d.appendChild(el('div', { class: 'n', text: tf('version_note', note) }));
  return d;
}
function vignetteVersion(fichier, f) { return fichier && fichier !== f ? url('/version-vignette', { f: fichier, v: caseV }) : url('/vignette', { f: f, w: 240 }); }
function panneauCase(p) {
  const i = choix.i, box = el('div', { class: 'ev' });
  box.appendChild(el('div', { style: 'font-weight:700', text: tf('version_titre', i + 1) }));
  p.appendChild(box);
  if (M.images[i] === undefined) { box.appendChild(el('p', { class: 'aide', text: T.version_vide })); return; }
  // Asked again when the box, its picture or its version changed; what was known shown meanwhile (same box, same picture).
  if (caseCle !== cleCase()) chargeCase();
  if (!CASE || CASE.i !== i || CASE.f !== M.images[i]) { box.appendChild(el('p', { class: 'aide', text: '…' })); return; }
  const c = CASE, f = c.f, choisie = (M.versions[i] || {}).v || 'auto';
  if (c.sans) { box.appendChild(el('p', { class: 'aide', text: T.version_sans })); return; }
  const par = {}; (c.versions || []).forEach(x => par[x.v] = x);
  const l = el('div', { class: 'versions' });
  // Auto: the best marked (what it shows now said under it).
  l.appendChild(carteVersion(T.version_auto, c.autoNote, vignetteVersion(c.autoFichier, f), choisie === 'auto', () => choisitVersion(i, 'auto'),
    T.version_auto + ' → ' + T['version_' + c.autoMontre] + (c.auto ? '' : ' · ' + T.version_auto_aide)));
  ['recue', 'corrigee', 'fusion'].forEach(v => {
    const x = par[v];
    if (x) l.appendChild(carteVersion(T['version_' + v] + (v === 'fusion' && x.copies ? ' ×' + x.copies : ''), x.note, vignetteVersion(x.fichier, f), choisie === v, () => choisitVersion(i, v)));
    // No fusion yet: made here, then shown.
    else if (v === 'fusion' && c.fusionPossible) {
      const enCours = c.enCours && c.enCours === c.groupe;
      l.appendChild(carteVersion(enCours ? T.version_fusion_en_cours : T.version_creer_fusion, null, null, false, () => { if (!enCours) creeFusion(i); }));
    }
  });
  box.appendChild(l);
  if (c.repli && choisie === c.choix) box.appendChild(el('p', { class: 'aide', style: 'color:var(--ambre)', text: T.version_repli }));
  if (!c.auto && choisie === 'auto') box.appendChild(el('p', { class: 'aide', text: T.version_auto_aide }));
  if (c.erreur && c.erreurSur === c.groupe) box.appendChild(el('p', { class: 'aide', style: 'color:var(--rose)', text: tf('version_fusion_echec', c.erreur) }));
  // Which copy received is the box's picture (as dragging one in does).
  if ((c.copies || []).length > 1) {
    box.appendChild(el('div', { class: 'aide', style: 'margin-top:8px', text: T.version_copies + ((M.versions[i] || {}).main ? ' · ' + T.version_main : '') }));
    const cs = el('div', { class: 'copiesCase' });
    c.copies.forEach(x => cs.appendChild(carteVersion(heure(x.t) + (x.corrigee ? ' ✨' : ''), x.note, url('/vignette', { f: x.f, w: 160 }), x.f === f,
      () => { if (x.f === f) return; if (M.diapos[f]) M.diapos[x.f] = M.diapos[f]; metImage(i, x.f); choix = { k: 'c', i: i }; change(); })));
    box.appendChild(cs);
  }
}
// « Créer la fusion »: the gallery's own fusion of the slide's copies, followed until it is made, then chosen.
async function creeFusion(i) {
  await sauveMaintenant();
  let j = null;
  try { const r = await fetch(url('/fusionner', { id: id, i: i }), { method: 'POST' }); if (r.status === 403) { porte(); return; } j = await r.json(); } catch (e) { g('message').textContent = T.hors_ligne; return; }
  if (!j || !j.ok) { g('message').textContent = tf('version_fusion_echec', (j && j.erreur) || '?'); return; }
  if (CASE) CASE.enCours = j.groupe;
  panneau();
  clearTimeout(caseSuit);
  const suit = async () => {
    let c = null; try { c = await lire('/case', { id: id, i: i }); } catch (e) { return; }
    if (c.enCours) { caseSuit = setTimeout(suit, 1500); return; }
    CASE = c; caseCle = cleCase(); caseV = Date.now();
    if ((c.versions || []).some(x => x.v === 'fusion') && choix && choix.k === 'c' && choix.i === i) choisitVersion(i, 'fusion');
    else panneau();
  };
  caseSuit = setTimeout(suit, 1500);
}
// « Relancer l'analyse »: how far it is, every second and a half, then the pictures read again.
let suivi2 = null;
async function suitAnalyse() {
  clearTimeout(suivi2);
  try {
    const j = await lire('/analyse');
    analyse = j.restent ? tf('analyse_en_cours', j.restent) : tf('analyse_finie', j.groupes, j.lues);
    if (onglet === 'images') panneau();
    if (j.restent) { suivi2 = setTimeout(suitAnalyse, 1500); return; }
    await chargeGalerie(); panneau();
  } catch (e) {}
}
async function chargeEvenement() { try { EV = await lire('/evenements', { id: id }); } catch (e) { EV = { evenement: null, main: false, liste: [] }; } }
async function chargeGalerie() { try { galerie = await lire('/galerie', { id: id }); } catch (e) {} }
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
    i.addEventListener('change', () => {
      const v = (parseFloat(i.value) || 0) / 100;
      // A patch's shape kept: its width or height sets the other.
      if (choix.k === 'e' && k === 'w') { z.h = z.h * v / z.w; z.w = v; } else if (choix.k === 'e' && k === 'h') { z.w = z.w * v / z.h; z.h = v; } else z[k] = v;
      bornePour(choix, z); change(); });
    r.appendChild(el('span', { text: k }));
    r.appendChild(i);
  });
  p.appendChild(r);
}
function panneauDispo() {
  const p = g('pDispo'); p.innerHTML = '';
  p.appendChild(champ(T.nom_modele, M.nom, v => { M.nom = v; clearTimeout(minuteur); minuteur = setTimeout(async () => { await sauve(); listeModeles(); }, 800); }));
  p.appendChild(caseACocher(T.legende, M.legende, async v => {
    M.legende = v; change();
    // The line under each picture changes the boxes' shape: a fitted sheet is fitted again.
    if (M.origine.length) { await sauveMaintenant(); await lire('/nombre', { id: id, n: M.cases.length, sat: filtre }); await charge(id); }
  }));
  p.appendChild(caseACocher(T.numeros, M.numeros, v => { M.numeros = v; change(); }));
  // The best of each slide's versions (as received, corrected, fused); said on the sheet only when asked.
  p.appendChild(caseACocher(T.corrigees, !!M.corrigees, v => { M.corrigees = v; change(); panneauDispo(); }));
  if (M.corrigees) p.appendChild(caseACocher(T.marque_traitees, !!M.marqueTraitees, v => { M.marqueTraitees = v; change(); }));
  const r = el('div', { class: 'rangee' });
  r.appendChild(el('button', { class: 'discret', text: T.ajouter_case, onclick: () => {
    // The station keeps CASES_MAX boxes of a sheet (Planche.CASES_MAX): said here, never one silently lost.
    if (M.cases.length >= CASES_MAX) { g('message').textContent = tf('cases_max', CASES_MAX); return; }
    const der = M.cases[M.cases.length - 1] || { x: 0.4, y: 0.4, w: 0.2, h: 0.2 * ratio * 0.75 };
    const z = { x: der.x + 0.03, y: der.y + 0.03, w: der.w, h: der.h }; borne(z);
    M.cases.push(z); choix = { k: 'c', i: M.cases.length - 1 }; change(); } }));
  if (choix && choix.k === 'c') r.appendChild(el('button', { class: 'rouge', text: T.retirer_case, onclick: () => {
    const i = choix.i; M.cases.splice(i, 1);
    const im = {}; for (const k in M.images) { const n = +k; if (n < i) im[n] = M.images[k]; else if (n > i) im[n - 1] = M.images[k]; }
    const vs = {}; for (const k in M.versions) { const n = +k; if (n < i) vs[n] = M.versions[k]; else if (n > i) vs[n - 1] = M.versions[k]; }
    M.images = im; M.versions = vs; choix = null; change(); } }));
  p.appendChild(r);
  p.appendChild(el('p', { class: 'aide', text: T.aide_dispo }));
  if (choix) positionChoisie(p);
}
function panneauTextes() {
  const p = g('pTextes'); p.innerHTML = '';
  const valeur = (cle, lib, defaut) => champ(lib, cle === 'nom' ? saisie.nom : M[cle], v => { if (cle === 'nom') saisie.nom = v; else M[cle] = v; sauveBientot(); },
    { placeholder: defaut ? tf('par_defaut', defaut) : '' });
  p.appendChild(valeur('indicatif', T.indicatif, defauts.indicatif));
  p.appendChild(valeur('nom', T.nom, defauts.nom));
  p.appendChild(valeur('locator', T.locator, defauts.locator));
  p.appendChild(valeur('dates', T.dates, defauts.dates));
  p.appendChild(champ(T.titre_planche, M.titre, v => { M.titre = v; sauveBientot(); }, { placeholder: defauts.titre ? tf('par_defaut', defauts.titre) : '' }));
  // « Titres stylés »: the title dressed as an ARISS series poster, in one click; its texts stay editable.
  if (STY.titres.length) {
    p.appendChild(el('h3', { text: T.titres }));
    const tt = el('div', { class: 'choix titres' });
    STY.titres.concat([{ id: 'aucun', nom: T.titre_aucun }]).forEach(x => tt.appendChild(el('button', { text: x.nom, onclick: async () => {
      await sauveMaintenant(); await lire('/titre-style', { id: id, t: x.id }); choix = null; await charge(id); } })));
    p.appendChild(tt);
    p.appendChild(el('p', { class: 'aide', text: T.titres_aide }));
  }
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
  p.appendChild(nuancier(T.couleur, COULEURS.concat(STY.lueurs.filter(c => !COULEURS.includes(c))), 'couleur'));
  p.appendChild(nuancier(T.fond + ' (' + [T.sans, T.voile_blanc, T.voile_noir].join(' · ') + ')', FONDS, 'fond'));
  // Its dress: the font and the effect shown as they look, the second colour, italic, spacing, rules.
  if (STY.styles.length) {
    const echantillon = (t.champ === 'LIBRE' && t.libre.trim() ? t.libre : 'SSTV 34').slice(0, 14);
    p.appendChild(el('div', { class: 'aide', text: T.police }));
    const fp = el('div', { class: 'choix' });
    [{ id: '', nom: T.police_defaut }].concat(STY.polices).forEach(x => fp.appendChild(el('button', {
      class: (t.police || '') === x.id ? 'choisi' : '', title: x.nom,
      style: x.id ? "font-family:'sp-" + x.id + "',sans-serif" : 'font-weight:700',
      onclick: () => { t.police = x.id; change(); } }, [document.createTextNode(echantillon), el('small', { text: x.nom })])));
    p.appendChild(fp);
    p.appendChild(el('div', { class: 'aide', text: T.style }));
    const fs = el('div', { class: 'choix' });
    STY.styles.forEach(x => fs.appendChild(el('button', { class: (t.style || '') === x.id ? 'choisi' : '', style: 'font-weight:800',
      onclick: () => { t.style = x.id; change(); } }, [el('span', { class: x.id ? 'st-' + x.id : '', text: x.nom })])));
    p.appendChild(fs);
    if (t.style === 'degrade' || t.style === 'contour') {
      const n = el('div', { class: 'nuancier' });
      [0].concat(COULEURS, STY.lueurs.filter(c => !COULEURS.includes(c))).forEach(c => n.appendChild(el('div', { class: 'nuance' + ((t.couleur2 | 0) === c ? ' choisie' : ''),
        style: 'background:' + (c === 0 ? 'transparent' : css(c)), title: c === 0 ? T.sans : '', onclick: () => { t.couleur2 = c; change(); } })));
      p.appendChild(el('div', { class: 'champ' }, [el('label', { text: T.couleur2 }), n]));
    }
    p.appendChild(caseACocher(T.italique, !!t.italique, v => { t.italique = v; change(); }));
    p.appendChild(caseACocher(T.filets, !!t.filets, v => { t.filets = v; change(); }));
    const esp = el('input', { type: 'range', min: '0', max: '0.6', step: '0.02', value: String(t.espacement || 0) });
    esp.addEventListener('change', () => { t.espacement = parseFloat(esp.value) || 0; change(); });
    p.appendChild(el('div', { class: 'champ' }, [el('label', { text: T.espacement }), esp]));
  }
  positionChoisie(p);
  p.appendChild(el('button', { class: 'rouge', style: 'margin-top:8px', text: T.retirer_texte, onclick: () => { M.textes.splice(choix.i, 1); choix = null; change(); } }));
}

function panneauDecor() {
  const p = g('pDecor'); p.innerHTML = '';
  p.appendChild(el('h3', { text: T.fond_planche }));
  if (aFond) p.appendChild(el('p', { class: 'aide', text: T.fond_modele }));
  else {
    const grille = el('div', { class: 'decors' });
    const vignette = (d, nom) => {
      const v = el('div', { class: 'img' + (M.decor === d ? ' placee' : ''), title: nom }, [
        el('img', { src: url('/decor-vignette', { d: d, w: 240 }), loading: 'lazy' }), el('div', { class: 'l2', text: nom })]);
      v.addEventListener('click', () => { M.decor = d; change(); });
      grille.appendChild(v);
    };
    decors.forEach(d => vignette(d.id, d.nom));
    if (M.decor && !decors.some(d => d.id === M.decor)) vignette(M.decor, T.fond_perso);
    p.appendChild(grille);
    p.appendChild(el('div', { class: 'rangee', style: 'margin-top:8px' }, [el('button', { class: 'discret', text: T.fond_choisir, onclick: () => g('fichierDecor').click() })]));
    p.appendChild(el('p', { class: 'aide', text: T.fond_aide }));
  }
  // The frame round each picture; a neon one takes a colour.
  if (STY.cadres.length) {
    p.appendChild(el('h3', { text: T.cadre }));
    const fc = el('div', { class: 'choix' });
    STY.cadres.forEach(x => fc.appendChild(el('button', { class: (M.cadre || '') === x.id ? 'choisi' : '', text: x.nom, onclick: () => { M.cadre = x.id; change(); } })));
    p.appendChild(fc);
    if (M.cadre === 'neon') {
      const n = el('div', { class: 'nuancier' });
      STY.lueurs.forEach(c => n.appendChild(el('div', { class: 'nuance' + ((M.cadreCouleur | 0) === c ? ' choisie' : ''), style: 'background:' + css(c), onclick: () => { M.cadreCouleur = c; change(); } })));
      p.appendChild(el('div', { class: 'champ' }, [el('label', { text: T.cadre_couleur }), n]));
    }
  }
  p.appendChild(el('h3', { text: tf('ecussons', M.ecussons.length, 3) }));
  const r = el('div', { class: 'rangee' });
  M.ecussons.forEach((e, i) => r.appendChild(el('button', { class: choix && choix.k === 'e' && choix.i === i ? 'primaire' : 'discret',
    text: T.ecusson + ' ' + (i + 1), onclick: () => { choix = { k: 'e', i: i }; dessineCalque(); panneau(); } })));
  if (M.ecussons.length < 3) r.appendChild(el('button', { class: 'discret', text: T.ecusson_ajouter, onclick: () => g('fichierEcusson').click() }));
  p.appendChild(r);
  p.appendChild(el('p', { class: 'aide', text: M.ecussons.length < 3 ? T.ecusson_aide : T.ecusson_plein + ' ' + T.ecusson_aide }));
  if (!choix || choix.k !== 'e' || !M.ecussons[choix.i]) return;
  const e = M.ecussons[choix.i];
  p.appendChild(caseACocher(T.ecusson_contour, e.sans, v => { e.sans = v; change(); }));
  p.appendChild(el('p', { class: 'aide', text: T.ecusson_contour_aide }));
  positionChoisie(p);
  p.appendChild(el('button', { class: 'rouge', style: 'margin-top:8px', text: T.ecusson_supprimer, onclick: () => { M.ecussons.splice(choix.i, 1); choix = null; change(); } }));
}
// A picture sent to the station: a background or a patch; the sheet read again.
async function envoie(route, f) {
  if (!f || !M) return null;
  await sauveMaintenant();
  g('message').textContent = T.envoi;
  try {
    const r = await fetch(url(route, { id: id }), { method: 'POST', body: f });
    if (r.status === 403) { porte(); return null; }
    const j = await r.json();
    if (!j.ok) { g('message').textContent = j.raison === 'plein' ? T.ecusson_plein : T.image_illisible; return null; }
    g('message').textContent = '';
    const o = onglet; await charge(id); onglet = o;
    return j;
  } catch (e) { g('message').textContent = T.image_illisible; return null; }
}

// ------------------------------------------------------------ templates
async function sauveMaintenant() { if (minuteur) { clearTimeout(minuteur); minuteur = null; await sauve(); } }
async function listeModeles() {
  const l = await lire('/liste');
  defauts.indicatif = l.indicatif; defauts.nom = l.nom;
  const s = g('modeles'); s.innerHTML = '';
  // Each sheet by its name, its event and how many pictures it holds.
  l.modeles.forEach(m => s.appendChild(el('option', { value: m.id, text: m.nom + (m.ev ? ' · ' + m.ev : '') + (m.n ? ' (' + m.n + ')' : '') })));
  if (id) s.value = id;
  return l.modeles;
}
async function charge(i) {
  id = i; g('modeles').value = i;
  const j = await lire('/modele', { id: i });
  M = litModele(j.texte); ratio = j.ratio || M.ratio; aFond = !!j.fond; REPLIS = j.replis || []; caseCle = ''; CASE = null;
  await chargeEvenement(); await chargeGalerie();
  defauts.locator = j.locator; defauts.dates = j.dates; defauts.titre = j.titre;
  g('scene').style.aspectRatio = String(ratio);
  g('scene').style.setProperty('--ratio', String(ratio));
  choix = null; change(true); apercu();
}
async function demarre() {
  g('porte').classList.add('cache'); g('editeur').classList.remove('cache');
  const l = await listeModeles();
  try { decors = await lire('/decors'); } catch (e) { decors = []; }
  await chargeStyles();
  if (l.length) await charge(l[l.length - 1].id); else panneau();
}
async function nouveau(type) { await sauveMaintenant(); const j = await lire('/nouveau', { type: type }); await listeModeles(); await charge(j.id); }

g('tPorte').textContent = T.titre; g('tCode').textContent = T.code; g('entrer').textContent = T.entrer;
g('tTitre').textContent = 'SatMe · ' + T.titre; g('tModele').textContent = T.modele; g('tSous').textContent = T.sous;
g('grille').textContent = T.grille; g('tour').textContent = T.tour; g('affiche').textContent = T.affiche;
g('affiche').onclick = () => nouveau('affiche'); g('importer').textContent = T.importer;
g('telecharger').textContent = T.telecharger; g('pupitre').textContent = T.pupitre; g('pupitre').href = base + '/'; g('journal').textContent = T.journal; g('journal').href = base + '/journal';
g('oImages').textContent = T.images; g('oDispo').textContent = T.disposition; g('oTextes').textContent = T.textes; g('oDecor').textContent = T.decor;
g('oDecor').onclick = () => { onglet = 'decor'; panneau(); };
g('fichierDecor').onchange = async () => { await envoie('/decor', g('fichierDecor').files[0]); g('fichierDecor').value = ''; };
g('fichierEcusson').onchange = async () => {
  const j = await envoie('/ecusson', g('fichierEcusson').files[0]); g('fichierEcusson').value = '';
  if (j && M.ecussons.length) { choix = { k: 'e', i: M.ecussons.length - 1 }; dessineCalque(); panneau(); }
};
g('oImages').onclick = () => { onglet = 'images'; panneau(); };
g('oDispo').onclick = () => { onglet = 'dispo'; panneau(); };
g('oTextes').onclick = () => { onglet = 'textes'; panneau(); };
g('entrer').onclick = entrer;
g('code').addEventListener('keydown', ev => { if (ev.key === 'Enter') entrer(); });
g('modeles').onchange = async ev => { await sauveMaintenant(); await charge(ev.target.value); };
g('grille').onclick = () => nouveau('grille');
g('renommer').textContent = T.renommer;
g('renommer').onclick = async () => {
  if (!M) return;
  const n = prompt(T.renommer_q, M.nom); if (n === null || !n.trim()) return;
  M.nom = n.trim().slice(0, 60); clearTimeout(minuteur); minuteur = null; await sauve(); await listeModeles(); panneau();
};
// A sheet deleted, asked first: its pictures stay in the gallery (the station never deletes one for it).
g('supprimer').textContent = T.supprimer;
g('supprimer').onclick = async () => {
  if (!M || !confirm(tf('supprimer_q', M.nom))) return;
  clearTimeout(minuteur); minuteur = null;
  const nom = M.nom;
  try {
    const r = await fetch(url('/supprime', { id: id }), { method: 'POST' });
    if (r.status === 403) { porte(); return; }
    const j = await r.json(); if (!j.ok) { g('message').textContent = T.hors_ligne; return; }
  } catch (e) { g('message').textContent = T.hors_ligne; return; }
  id = ''; M = null; choix = null;
  const l = await listeModeles();
  if (l.length) await charge(l[l.length - 1].id); else { g('apercu').removeAttribute('src'); dessineCalque(); suivi(); panneau(); }
  g('message').textContent = tf('supprimee', nom);
};
// The event's report: opened in a tab of its own, or downloaded; the page (to print to PDF) or the PDF made here.
g('rapport').textContent = T.rapport; g('tRapport').textContent = T.rapport_titre; g('tRapportAide').textContent = T.rapport_aide;
g('tRapportChaine').textContent = T.rapport_chaine; g('tRapportPlan').textContent = T.rapport_plan;
g('tRapportVue').textContent = T.rapport_vue; g('tRapportDessin').textContent = T.rapport_dessin; g('tRapportSimple').textContent = T.rapport_simple;
g('tRapportFormat').textContent = T.rapport_format; g('tRapportWeb').textContent = T.rapport_web; g('tRapportPdf').textContent = T.rapport_pdf;
g('rapportOuvrir').textContent = T.rapport_ouvrir; g('rapportDl').textContent = T.rapport_telecharger; g('rapportNon').textContent = T.annuler;
g('rapport').onclick = async () => {
  if (!M) return;
  await sauveMaintenant(); await chargeEvenement();
  g('rapportEv').textContent = EV.evenement ? evNom(EV.evenement) : T.rapport_sans_ev;
  g('rapportOuvrir').disabled = g('rapportDl').disabled = !EV.evenement;
  let plans = []; try { plans = await lire('/plans', { id: id }); } catch (e) {}
  g('rapportPlan').innerHTML = ''; plans.forEach(x => g('rapportPlan').appendChild(el('option', { value: x.id, text: x.nom })));
  // « Dessin du montage » (the default) where the station has a montage; the simple chain otherwise (the phone).
  RAPPORT_DESSIN = plans.some(x => x.dessin);
  rapportVues();
  g('dlgRapport').classList.remove('cache');
};
let RAPPORT_DESSIN = false;
function rapportVues() {
  g('lRapportPlan').classList.toggle('cache', !g('rapportPlan').options.length || !g('rapportChaine').checked);
  g('lRapportVue').classList.toggle('cache', !RAPPORT_DESSIN || !g('rapportChaine').checked);
}
g('rapportChaine').onchange = rapportVues;
function rapportFormat() { const r = document.querySelector('input[name=rapportFormat]:checked'); return r ? r.value : ''; }
function rapportVue() { const r = document.querySelector('input[name=rapportVue]:checked'); return RAPPORT_DESSIN && r ? r.value : 'simple'; }
function rapportParams(dl) { return { id: id, chaine: g('rapportChaine').checked ? 1 : 0, vue: rapportVue(), plan: g('rapportPlan').value || '', dl: dl ? 1 : 0, format: rapportFormat() }; }
g('rapportOuvrir').onclick = () => { g('dlgRapport').classList.add('cache'); window.open(url('/rapport', rapportParams(false)), '_blank'); };
g('rapportDl').onclick = async () => {
  g('dlgRapport').classList.add('cache');
  g('message').textContent = T.fabrication;
  try {
    const r = await fetch(url('/rapport', rapportParams(true)));
    if (r.status === 403) { porte(); return; }
    if (!r.ok) { g('message').textContent = T.hors_ligne; return; }
    const nom = ((r.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/) || [])[1] || ('SatMe_Rapport.' + (rapportFormat() || 'html'));
    const b = await r.blob();
    const a = el('a', { href: URL.createObjectURL(b), download: nom }); document.body.appendChild(a); a.click(); a.remove();
    g('message').textContent = nom;
  } catch (e) { g('message').textContent = T.hors_ligne; }
};
g('rapportNon').onclick = () => g('dlgRapport').classList.add('cache');
// SatMe-PC only: « Publier sur le site » (Réglages › Envoyer vers un site web, the module « Publication web », off by default), the planche and its event's report, through the PC's queue.
async function siteWeb() {
  if (!window.SATME_PC) return;
  let S = null; try { S = await (await fetch(base + '/api/site')).json(); } catch (e) {}
  const on = k => !!(S && S.ok && S.configure && S.modes[k] !== 'jamais');
  g('sitePlanche').classList.toggle('cache', !on('planche')); g('siteRapport').classList.toggle('cache', !on('rapport'));
}
async function sitePublie(type) {
  if (!M) return;
  await sauveMaintenant();
  let j = null;
  try { j = await (await fetch(base + '/api/site/publie', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'type=' + encodeURIComponent(type) + '&ref=' + encodeURIComponent(id) })).json(); } catch (e) {}
  g('message').textContent = j ? (j.ok ? '🌐 ' + j.message : '✗ ' + (j.erreur || '')) : T.hors_ligne;
}
g('sitePlanche').onclick = () => sitePublie('planche');
g('siteRapport').onclick = () => sitePublie('rapport');
siteWeb();
g('dlgRapport').addEventListener('pointerdown', ev => { if (ev.target.id === 'dlgRapport') g('dlgRapport').classList.add('cache'); });
g('tour').onclick = () => nouveau('tour');
g('importer').onclick = () => g('fichier').click();
g('importer').title = T.importer_aide;
// A SatMe template file (a zip), not a picture: by its name, else its type.
function estModeleSatme(f) { return /\.(satme-planche|zip)$/i.test(f.name) || f.type === 'application/zip'; }
async function importeModele(f) {
  await sauveMaintenant();
  g('message').textContent = T.import_en_cours;
  try {
    const r = await fetch(url('/modele-importe'), { method: 'POST', body: f });
    if (r.status === 403) { porte(); return; }
    const j = await r.json();
    if (!j.ok) { g('message').textContent = j.raison === 'version' ? T.refus_version : T.refus_fichier; return; }
    await listeModeles(); await charge(j.id);
    g('message').textContent = tf('modele_importe', j.nom);
  } catch (e) { g('message').textContent = T.refus_fichier; }
}
g('fichier').onchange = async () => { const f = g('fichier').files[0]; if (f) await importeFichier(f); g('fichier').value = ''; };
// A file given (chosen or dropped): a SatMe template, else a blank ARISS series picture made a template.
async function importeFichier(f) {
  if (estModeleSatme(f)) { await importeModele(f); return; }
  await sauveMaintenant();
  g('message').textContent = T.import_en_cours;
  try {
    const r = await fetch(url('/importe', { nom: f.name.replace(/\.[^.]*$/, '') }), { method: 'POST', body: f });
    const j = await r.json();
    if (!j.ok) { g('message').textContent = T.import_echec; return; }
    await listeModeles(); await charge(j.id);
    g('message').textContent = tf('import_ok', j.cases);
  } catch (e) { g('message').textContent = T.import_echec; }
}
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
// Exporting the template: said first what goes in the file (no picture, the callsign a field unless ticked).
g('exporter').textContent = T.exporter; g('tExport').textContent = T.exporter_titre; g('tExportAide').textContent = T.exporter_aide;
g('exportOk').textContent = T.exporter_ok; g('exportNon').textContent = T.annuler;
g('exporter').onclick = () => {
  if (!M) return;
  g('tExportInd').textContent = tf('exporter_indicatif', defauts.indicatif || '');
  g('lExportInd').classList.toggle('cache', !defauts.indicatif); g('exportInd').checked = false;
  g('dlgExport').classList.remove('cache');
};
g('exportNon').onclick = () => g('dlgExport').classList.add('cache');
g('dlgExport').addEventListener('pointerdown', ev => { if (ev.target.id === 'dlgExport') g('dlgExport').classList.add('cache'); });
g('exportOk').onclick = async () => {
  g('dlgExport').classList.add('cache');
  await sauveMaintenant();
  try {
    const r = await fetch(url('/modele-exporte', { id: id, indicatif: g('exportInd').checked ? 1 : 0 }));
    if (r.status === 403) { porte(); return; }
    const nom = ((r.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/) || [])[1] || 'SatMe_Modele.satme-planche';
    const b = await r.blob();
    const a = el('a', { href: URL.createObjectURL(b), download: nom }); document.body.appendChild(a); a.click(); a.remove();
    g('message').textContent = nom;
  } catch (e) { g('message').textContent = T.hors_ligne; }
};
// A template file or a blank ARISS picture dropped anywhere on the page: a new template. (Pictures dragged into boxes are not files.)
let depots = 0;
function porteFichiers(ev) { return ev.dataTransfer && Array.from(ev.dataTransfer.types || []).includes('Files'); }
g('depot').textContent = T.deposer;
window.addEventListener('dragenter', ev => { if (!porteFichiers(ev) || g('editeur').classList.contains('cache')) return; depots++; g('depot').classList.remove('cache'); });
window.addEventListener('dragleave', ev => { if (!porteFichiers(ev)) return; if (--depots <= 0) { depots = 0; g('depot').classList.add('cache'); } });
window.addEventListener('dragover', ev => { if (porteFichiers(ev)) ev.preventDefault(); });
window.addEventListener('drop', ev => {
  if (!porteFichiers(ev)) return;
  ev.preventDefault(); depots = 0; g('depot').classList.add('cache');
  const f = ev.dataTransfer.files[0];
  if (f && (estModeleSatme(f) || /^image\/(jpeg|png)$/.test(f.type) || /\.(jpe?g|png)$/i.test(f.name))) importeFichier(f);
  else if (f) g('message').textContent = T.refus_depot;
});
window.addEventListener('resize', apercuBientot);
if (cle) demarre().catch(() => {}); else porte();
</script>
</body>
</html>
"""
}
