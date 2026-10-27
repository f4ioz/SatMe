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
 *
 * **One editor for one pass or several** (a session groups passes; an opened
 * pass is a session of one): one replay, one set of options, the same files.
 * The sky and the map show the passes one after the other, the globe all of
 * them; the moment, the timeline, the sound, the SSTV pictures' versions and
 * the report's panels are the pass shown's; the files' frame (16:9 or a
 * phone's 9:16) and the pictures placed with the mouse or a finger are the
 * same for one pass or several, and so are the station's files (SatMe-PC:
 * `/fabrique-session`; the phone's page keeps its `/fabrique`).
 */
object PageJournal {

    private val CLES = listOf(
        "pj_titre", "pj_sous", "pj_tous", "pj_aucun", "pj_choisir", "pj_duree", "pj_lecture", "pj_pause", "pj_vitesse",
        "pj_sans_son", "pj_frise_aide", "pj_selection", "pj_effacer", "pj_moments", "pj_aucun_moment", "pj_choisir_morceau",
        "pj_fabriquer", "pj_de_quoi_sel", "pj_de_quoi_tout", "pj_titre_extrait", "pj_son", "pj_video", "pj_gif", "pj_image",
        "pj_paquet", "pj_paquet_qsos", "pj_vue", "pj_vue_ciel", "pj_vue_carte", "pj_en_cours", "pj_pret", "pj_telecharger", "pj_echec",
        "pj_sons", "pj_qso", "pj_aprs", "pj_via_iss", "pj_sstv", "pj_signet", "pj_activite", "pj_smetre", "pj_reconstitue",
        "pj_planche", "pj_rapport", "pj_onglet_rejeu", "pj_onglet_fabriquer", "pj_accelere", "pj_avant", "pj_apres", "pj_activite_opt",
        "pj_afficher", "pj_aff_sstv", "pj_aff_fiche", "pj_aff_smetre", "pj_aff_freq", "pj_aff_locator", "pj_resolution",
        "pj_res_xs", "pj_res_m", "pj_res_hd", "pj_ouverture", "pj_recap", "pj_avec_son", "pj_accelere_video",
        "pj_options_aide", "pj_accelere_actif", "pj_format", "pj_format_portrait", "pj_format_paysage", "pw_code", "pw_entrer", "pw_code_faux", "pw_hors_ligne", "pw_pupitre",
        "pj_doppler", "pj_n_bouton", "pj_n_rs", "pj_n_rr", "pj_n_note", "pj_n_carnet", "pj_n_sat_inconnu", "pj_n_mode_auto", "pj_n_dernier", "pj_n_titre", "pj_n_aide", "pj_n_sat", "pj_n_passage", "pj_n_autre", "pj_n_aucun", "pj_n_aos", "pj_n_los", "pj_n_creer", "pj_n_refus", "pj_n_trop_gros", "pj_n_indicatif", "pj_n_onglet", "pj_n_contact", "pj_n_son", "pj_n_images", "pj_n_mode", "pj_n_envoyer", "pj_n_envoi", "pj_n_fait", "pj_n_call", "pj_n_loc",
        "pj_sstv_cadre", "pj_sstv_cadre_aide", "pj_sstv_auto", "pj_sstv_auto_aide", "pj_n_echec", "pj_supprimer", "pj_supprimer_q",
        "pj_sstv_titre", "pj_sstv_defaut", "pj_v_auto", "pj_v_recue", "pj_v_corrigee", "pj_v_fusion", "pj_v_passage", "pj_v_note",
        "pj_creer_fusion", "pj_fusion_en_cours", "pj_marque_traitees", "pj_marque_aide", "pj_repli", "pj_sstv_aucune", "pj_sstv_aide",
        "pj_el_titre", "pj_el_aide", "pj_el_incruste", "pj_el_carte", "pj_el_absent", "pj_el_plan", "pj_el_plan_auto",
        "pj_el_evenement", "pj_el_sans_evenement", "pj_el_simple",
        "pj_vue_globe", "pj_globe_aide", "pj_cam_poursuite", "pj_cam_station", "pj_cam_ensemble", "pj_cam_satellite",
        "pj_session", "pj_session_aide", "pj_s_titre", "pj_s_sat", "pj_s_de", "pj_s_a", "pj_s_images", "pj_s_animer", "pj_s_chargement", "pj_s_aucun", "pj_s_resume", "pj_s_mode_passages", "pj_s_mode_continu", "pj_s_passage", "pj_s_recues", "pj_s_evenement", "pj_s_frise", "pj_villes", "pj_v_toutes", "pj_v_capitales", "pj_v_aucune", "pj_e_nom", "pj_e_drapeau", "pj_e_nomdrapeau", "pj_detail", "pj_d_0", "pj_d_1", "pj_d_2", "pj_globe_deplacer", "pj_s_vignettes", "pj_s_video_titre", "pj_s_video", "pj_s_sans_ffmpeg", "pj_s_entre_pause", "pj_s_entre_vite", "pj_police", "pj_s_aos_dans", "pj_s_apres_los", "pj_s_v_hors", "pj_s_v_passage", "pj_s_v_image",
        "pj_s_zoom_hors", "pj_s_zoom_passage", "pj_s_zoom_aide", "pj_s_autres", "pj_s_autres_aide", "pj_s_taches", "pj_s_son_aide", "pj_s_sans_son",
        "pj_onglet_options", "pj_marges", "pj_marge_avant", "pj_marge_apres", "pj_vitesses", "pj_vitesses_aide", "pj_accel_titre",
        "pj_s_passages", "pj_s_ouvrir", "pj_s_autour", "pj_s_mode", "pj_exp_unique", "pj_exp_plusieurs", "pj_s_mode_seul")

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
  .p .m .note { font-size:18px; line-height:12px; color:var(--cyan); font-weight:700; }
  .pastille { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:3px; vertical-align:middle; }
  .vue { background:var(--carte); border:1px solid var(--bord); border-radius:10px; padding:8px 10px; min-width:0; }
  .entete { display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; }
  .entete .s { font-size:18px; font-weight:800; }
  .entete .d { color:var(--gris); font-size:12px; flex:1; }
  .deux { margin-top:6px; }
  .cote { margin-top:8px; }
  .ecran { position:relative; height:calc(100vh - 335px); min-height:240px; background:#0E1720; border-radius:8px; overflow:hidden; }
  /* On a computer's screen the whole page fits it, nothing to scroll: the replay
     takes the height left, the lists scroll in their own column. */
  @media (min-width:1200px) and (min-height:560px) {
    #page { height:100vh; display:flex; flex-direction:column; overflow:hidden; }
    #page > header { flex:none; }
    .corps { flex:1; min-height:0; }
    .liste { max-height:none; min-height:0; }
    .vue { min-height:0; display:flex; flex-direction:column; overflow-y:auto; }
    #sZone { flex:1; min-height:0; display:flex; flex-direction:column; }
    .deux { flex:1; min-height:0; display:grid; grid-template-columns:minmax(0,1fr) 380px; gap:10px; }
    .gauche { display:flex; flex-direction:column; min-height:0; }
    .gauche .ecran { flex:1; height:auto; min-height:200px; }
    .cote { margin-top:0; display:flex; flex-direction:column; min-height:0; }
    .cote .onglets { flex-wrap:wrap; }
    .cote .panneau { flex:1; min-height:0; overflow:auto; }
    .cote .moment { align-self:stretch; }
    .cote .mo { flex-wrap:wrap; }
    .cote .mo .x { flex:1 1 150px; }
    #imageMoment { max-height:30vh; object-fit:contain; }
  }
  #ciel { position:absolute; inset:0; width:100%; height:100%; }
  #carte { position:absolute; inset:0; width:100%; height:100%; cursor:grab; display:none; }
  #globe { position:absolute; inset:0; width:100%; height:100%; cursor:grab; display:none; touch-action:none; }
  .globe-choix { position:absolute; right:8px; top:8px; z-index:4; display:none; gap:4px; }
  .globe-choix select { font-size:12px; padding:2px 4px; }
  .globe-choix { flex-wrap:wrap; justify-content:flex-end; max-width:60%; }
  @font-face { font-family:Drapeaux; src:url(api/police-drapeaux) format('woff2'); unicode-range:U+1F1E6-1F1FF; font-display:swap; }
  /* Several passes: chosen by satellite and dates above the editor, their strip, the pictures as they come */
  .session-f { display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin:2px 0 8px; }
  .session-f input[type=date] { padding:3px 6px; }
  #sCarte { position:absolute; left:50%; top:50%; transform:translate(-50%,-50%); z-index:4; display:none; pointer-events:none; text-align:center; max-width:86%;
    background:rgba(10,15,24,.9); border:2px solid #00E5FF; border-radius:14px; padding:12px 22px; font-size:calc(14px * var(--police, 1)); line-height:1.5; }
  #sCarte b { display:block; font-size:1.7em; } #sCarte .sat { color:#00E5FF; font-weight:700; } #sCarte .sstv { color:var(--sstv); font-weight:700; }
  #sCarte.mosaique { width:86%; max-height:86%; overflow:hidden; border-color:var(--bord); }
  #sCarte .grille { display:flex; flex-wrap:wrap; gap:6px; justify-content:center; margin-top:6px; }
  #sCarte figure { margin:0; width:var(--l, 120px); } #sCarte img { width:var(--l, 120px); height:calc(var(--l, 120px) * .75); object-fit:cover; outline:1.5px solid var(--sstv); }
  #sCarte figcaption { font-size:11px; color:var(--gris); }
  .s-curseur { display:flex; align-items:center; gap:6px; font-size:12px; color:var(--gris); }
  .s-curseur input { width:120px; }
  .s-autres { margin:6px 0; font-size:12px; } .s-autres summary { cursor:pointer; color:var(--clair); } .s-autres .colonnes label { white-space:nowrap; }
  #sFrise { width:100%; height:26px; display:block; margin-top:6px; cursor:pointer; background:#0E1720; border-radius:6px; }
  #sGalerie { display:flex; gap:6px; overflow-x:auto; padding:6px 0 0; flex:none; }
  #sGalerie figure { margin:0; flex:none; text-align:center; animation:arrive .6s ease-out; }
  #sGalerie img { height:56px; border-radius:4px; border:1px solid var(--bord); display:block; }
  #sGalerie figcaption { font-size:10px; color:var(--gris); }
  @keyframes arrive { from { transform:scale(.4); opacity:0; } to { transform:none; opacity:1; } }
  /* The file's frame over the replay (PC): its view clear, the rest dimmed, its caption below. */
  #cadreF { position:absolute; inset:0; pointer-events:none; z-index:1; }
  #cadreF .vueF { position:absolute; outline:2px dashed var(--cyan); box-shadow:0 0 0 9999px rgba(5,10,16,.62); }
  #cadreF .legF { position:absolute; background:#0A0F18; border-top:1px solid #22303B; padding:6px 10px; overflow:hidden; }
  #cadreF .legF b { display:block; font-size:15px; color:#fff; }
  #cadreF .legF span { font-size:11px; color:#A0AFC3; }
  #cadreF .legF.col { display:flex; justify-content:space-between; align-items:center; gap:10px; }
  #cadreF .legF.col span, #cadreF .legF .dr span { display:block; }
  #cadreF .legF .dr { text-align:right; }
  #cadreF .fmtF { position:absolute; right:8px; top:8px; font-size:11px; color:var(--cyan); background:rgba(5,10,16,.8); padding:2px 6px; border-radius:5px; }
  /* The SSTV picture where the video puts it: dragged, resized by its corner or the wheel. */
  #cadreSstv { position:absolute; z-index:3; display:none; cursor:move; touch-action:none; user-select:none; }
  #cadreSstv.possible.vu, .ecran:hover #cadreSstv.possible, #cadreSstv.possible.prise { display:block; }
  #cadreSstv img { position:absolute; inset:0; width:100%; height:100%; display:none; outline:2px solid var(--sstv); pointer-events:none; }
  #cadreSstv.vu img { display:block; }
  #cadreSstv .vide { position:absolute; inset:0; border:2px dashed var(--sstv); background:rgba(255,138,31,.12); color:var(--sstv);
    display:flex; flex-direction:column; align-items:center; justify-content:center; gap:3px; text-align:center; padding:6px; font-size:11px; pointer-events:none; }
  #cadreSstv .vide b { font-size:13px; }
  #cadreSstv.vu .vide { display:none; }
  #cadreSstv:hover, #cadreSstv.prise { outline:1px solid rgba(255,255,255,.75); outline-offset:2px; }
  #cadreSstv .poignee { position:absolute; right:0; bottom:0; width:16px; height:16px; background:var(--sstv); border:2px solid #0B1016; border-radius:3px; cursor:nwse-resize; display:none; }
  .ecran:hover #cadreSstv .poignee, #cadreSstv.prise .poignee { display:block; }
  #cadreSstv .auto { position:absolute; left:4px; top:4px; font-size:11px; padding:1px 7px; background:rgba(5,10,16,.85); display:none; }
  #cadreSstv.manuel:hover .auto { display:block; }
  /* The report's panels laid over the video (Make tab): dragged, resized by their corner or the wheel, as the SSTV picture. */
  .cadreEl { position:absolute; z-index:2; cursor:move; touch-action:none; user-select:none; }
  .cadreEl img { position:absolute; inset:0; width:100%; height:100%; pointer-events:none; }
  .cadreEl:hover, .cadreEl.prise { outline:1px solid rgba(255,255,255,.75); outline-offset:2px; }
  .cadreEl .poignee { position:absolute; right:0; bottom:0; width:14px; height:14px; background:var(--cyan); border:2px solid #0B1016; border-radius:3px; cursor:nwse-resize; display:none; }
  .cadreEl:hover .poignee, .cadreEl.prise .poignee { display:block; }
  .cadreEl .auto { position:absolute; left:3px; top:3px; font-size:10px; padding:1px 6px; background:rgba(5,10,16,.85); display:none; }
  .cadreEl.manuel:hover .auto { display:block; }
  .versions .vi { display:flex; gap:8px; align-items:center; padding:3px 0; flex-wrap:wrap; }
  .versions .vi img { width:64px; height:48px; object-fit:cover; border-radius:4px; background:#18232D; }
  .versions .vi .x { color:var(--gris); font-size:11px; }
  .versions .vi select { max-width:15em; }
  .bloc { border-top:1px solid var(--bord); margin-top:8px; padding-top:6px; }
  .bascule { position:absolute; left:8px; top:8px; display:flex; gap:4px; z-index:4; }
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
  .mo.actif { background:#13262A; }
  .titre-bloc { font-weight:700; color:var(--clair); margin:8px 0 2px; }
  .titre-bloc:first-child { margin-top:0; }
  .panneau .colonnes label.rangee { flex-wrap:nowrap; align-items:flex-start; }
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
  <button class="discret" id="bNouveau"></button>
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
// The globe's routes are the station's: under the journal's base.
window.SATME_RACINE = location.pathname.replace(/\/journal\/?.*/, '') + '/';
const GLOBE_MONDE = ${GlobeDonnees.json()};
${OrbitesJs.JS}
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

// ------------------------------------------------------------ the page's options (kept in this browser)
// Only what was changed here is kept (OM); the rest follows the station's journal settings (OD), read at each opening:
// a setting changed there reaches this page unless it was changed here.
let O = {}, OD = {}, OM = {};
function chargeOptions(defauts) {
  OD = Object.assign({ vue: 'ciel', accelere: false, rapide: 10, avant: 10, apres: 30, surActivite: true, sstv: true, fiches: true,
    smetre: true, freq: true, locator: true, res: 'M', ouverture: true, recap: true, avecSon: true, paquetQsos: false, format: 'paysage', vignettes: true,
    entre: 10, transition: 1.6, police: 1, vHors: 1000, vPassage: 20, vImage: 3, zHors: .9, zPassage: 1.8, autres: [], tachesAutres: false,
    mAvant: .5, mApres: .5, mode: 'passages' });
  for (const k in defauts) if (defauts[k] !== undefined) OD[k] = defauts[k];
  OM = {};
  try {
    const l = localStorage.getItem('satme-journal-choix');
    if (l) OM = JSON.parse(l) || {};
    else {
      // The page's older way kept every option: of those, only what differs from the station's now is taken as a choice.
      const v = JSON.parse(localStorage.getItem('satme-journal') || '{}');
      for (const k in v) if (k in OD && v[k] !== OD[k] && !(k in defauts)) OM[k] = v[k];
    }
  } catch (e) {}
  // The PC's « téléphone » format is a phone's whole screen (9:16), in place of the phone app's 3:4 it once was.
  if (OM.format === 'portrait') OM.format = 'telephone';
  O = Object.assign({}, OD, OM);
}
function garde() {
  for (const k in O) { if (O[k] === OD[k]) delete OM[k]; else OM[k] = O[k]; }
  try { localStorage.setItem('satme-journal-choix', JSON.stringify(OM)); localStorage.removeItem('satme-journal'); } catch (e) {}
}

// ------------------------------------------------------------ times
let utc = true;
function p2(n) { return String(n).padStart(2, '0'); }
function hms(t) { const d = new Date(t); return utc ? p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + ':' + p2(d.getUTCSeconds()) : p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()); }
function jour(t) { const d = new Date(t); return utc ? p2(d.getUTCDate()) + '/' + p2(d.getUTCMonth() + 1) + '/' + d.getUTCFullYear() + ' ' + p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + 'Z' : d.toLocaleDateString() + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); }
function mmss(ms) { const s = Math.round(Math.abs(ms) / 1000); return Math.floor(s / 60) + ':' + p2(s % 60); }
function duree(ms) { const s = Math.round(ms / 1000); return Math.floor(s / 60) + ' min ' + p2(s % 60); }
const COUL = { QSO: 'var(--qso)', APRS: 'var(--aprs)', ISS: 'var(--iss)', SSTV: 'var(--sstv)', SIGNET: 'var(--signet)' };
const COULHEX = { QSO: '#FF4FA3', APRS: '#4C8DFF', ISS: '#FFC21A', SSTV: '#FF8A1F', SIGNET: '#B98CFF' };
function couleur(m) { return m.k === 'APRS' && m.iss ? 'ISS' : m.k; }

// ------------------------------------------------------------ the list
let passages = [], filtre = '', fiches = {}, segments = [];
async function demarre() {
  g('porte').classList.add('cache'); g('page').classList.remove('cache');
  const [l, r] = await Promise.all([lire('/liste'), lire('/reglages')]);
  utc = l.utc; passages = l.passages;
  cadreSstv = lisCadre(r.cadreSstv); tailleSstv = r.tailleSstv || 0;
  chargeOptions({ accelere: r.accelere, rapide: r.rapide, avant: r.avant, apres: r.apres, surActivite: r.surActivite, sstv: r.sstv,
    fiches: r.fiches, smetre: r.smetre, freq: r.freq, locator: r.locator, res: r.res, ouverture: r.ouverture, recap: r.recap, paquetQsos: r.paquetQsos });
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
    // The sound: a note big enough to see at a glance in the list.
    if (p.son) m.appendChild(el('span', { class: 'note', text: '♪', title: T.sons }));
    tag('QSO', p.q, T.qso); tag('SSTV', p.i, 'SSTV'); tag('APRS', p.r, T.aprs); tag('SIGNET', p.s, '⚑');
    if (p.sm) m.appendChild(el('span', { text: T.smetre }));
    if (p.rec) m.appendChild(el('span', { style: 'color:var(--gris)', text: T.reconstitue }));
    d.appendChild(el('div', { class: 'p' + (P && P.id === p.id ? ' actif' : ''), onclick: () => ouvre(p.id) }, [
      el('div', { class: 's', text: p.sat }),
      el('div', { class: 'd', text: jour(p.de) + ' · ' + tf('duree', duree(p.a - p.de), p.el) }), m]));
  });
}

// ------------------------------------------------------------ the editor: one pass or several, the same
/*
 * One editor whatever the passes: S holds them (one when a pass is opened, several for a session) and how they
 * play; P is the one shown (its sky, map, moment, timeline, sound, pictures' versions, report). The time t runs over
 * them all: each from its margin before the AOS to its margin after the LOS, one after the other.
 */
let S = null, P = null, editions = 0;
let t = 0, joue = false, vitesse = 1, vue0 = 0, vue1 = 1, sel = null, activite = [], onglet = 'moments';
function ouvre(id) { return ouvreEditeur([id]); }
/**
 * The editor on the passes [ids], in [o.zone] (the session's, under its form) or the page's view; [o.recharge]: the
 * same passes read again (a contact, a sound added; one taken out), kept where they were ([o.t]).
 */
async function ouvreEditeur(ids, o) {
  o = o || {};
  arrete();
  const z = o.zone || g('vue'), vieux = o.recharge ? S : null;
  if (!vieux) { ajoutFait = ''; deQuoi = 'tout'; sel = null; }
  if (ids.length > 1) { z.innerHTML = ''; z.appendChild(el('div', { class: 'aide', id: 'chargement' })); }
  const ps = [];
  for (let k = 0; k < ids.length; k++) {
    if (g('chargement')) g('chargement').textContent = tf('s_chargement', k + 1, ids.length);
    try { const q = await lire('/passage', { id: ids[k] }); utc = q.utc; ps.push(q); } catch (x) {}
  }
  if (!ps.length) { if (ids.length > 1) z.textContent = T.s_aucun; return; }
  ps.sort((a, b) => a.de - b.de);
  // On the PC, each satellite's elements (it is seen coming before the AOS on the globe) and the companions to choose from.
  if (window.SATME_PC) { for (const q of ps) q.tle = await elementsDe(q.sat); await favorisStation(); }
  S = { ps: ps, ids: ps.map(q => q.id), sat: ps[0].sat, i: 0, segs: [], trans: 0, mode: O.mode === 'continu' ? 'continu' : 'passages',
    titre: vieux ? vieux.titre : '', vues: 0, fiches: {}, zone: z, version: ++editions };
  P = null; fiches = S.fiches;
  // The marks of all the passes, each once (a picture or a contact tied to two passes close together).
  const vus = new Set(); S.marques = [];
  ps.forEach(q => q.marques.forEach(m => { m.pid = q.id; const k = m.k + '|' + (m.f || m.t) + '|' + m.de; if (!vus.has(k)) { vus.add(k); S.marques.push(m); } }));
  S.images = S.marques.filter(m => m.k === 'SSTV' && m.f).sort((x, y) => x.a - y.a);
  // Where the time slows down (as the files): a picture coming, a contact of a pass with its sound — heard in real time with the sound.
  const avecSon = new Set(ps.filter(q => q.sons.length).map(q => q.id));
  const autour = m => m.a > m.de ? [m.de - 3000, m.a + 3000] : [m.de - 10000, m.de + 10000];
  S.actifs = S.marques.filter(m => m.k === 'SSTV' || (m.k === 'QSO' && avecSon.has(m.pid))).map(autour);
  S.ecoute = S.marques.filter(m => m.k === 'QSO' && avecSon.has(m.pid)).map(autour);
  ps.forEach(bornesPassage); fenetresSession();
  t = o.t != null ? o.t : S.segs[0][0];
  // No margin, one pass whose recording started after its AOS: from where its sound does, not on seconds of silence.
  if (o.t == null && ps.length === 1 && !marges()[0]) { const q = ps[0], d = q.sons.length ? Math.min(...q.sons.map(s => s.o + s.an)) : null; if (d && d > q.de && d < q.a) t = d; }
  S.i = indexA(t);
  dessineListe(); construitVue(z);
  passeA(S.i, true);
  ps.forEach(q => lire('/fiches', { id: q.id }).then(f => { if (S && S.ps.includes(q)) { Object.assign(S.fiches, f); maj(); } }).catch(() => {}));
  chargeSegments();
}
/** The pass shown becomes the [i]th: its sky, its timeline, its sound, its pictures' versions, its panels. */
function passeA(i, force) {
  i = Math.max(0, Math.min(S.ps.length - 1, i));
  if (!force && P === S.ps[i]) return;
  const autre = P !== S.ps[i];
  S.i = i; P = S.ps[i];
  if (autre) { vue0 = P.t0; vue1 = P.t1; activite = []; morceauJoue = ''; carteCadree = false; SS = null; imageVue = ''; }
  segments = P.segments || [];
  majEntete(); dessineCiel(); if (O.vue === 'carte') dessineCarte();
  const id = P.id;
  lire('/activite', { id: id }).then(a => { if (P && P.id === id) { activite = a; dessineFrise(); } }).catch(() => {});
  chargeSstv();
  panneau(); maj();
}
/** The pass whose window holds [tt] (the one before, between two). */
function indexA(tt) { let k = 0; S.segs.forEach((w, i) => { if (tt >= w[0]) k = i; }); return k; }
async function supprimePassage() {
  if (!P || !confirm(tf('supprimer_q', P.sat + ' · ' + jour(P.de)))) return;
  let j = null; try { j = await envoie('/supprime', { id: P.id }); } catch (e) { return; }
  if (!j || !j.ok) { g('message').textContent = T.hors_ligne; return; }
  arrete();
  const l = await lire('/liste'); passages = l.passages;
  // Several: the others stay together.
  const reste = S.ids.filter(x => x !== P.id);
  if (reste.length) { ouvreEditeur(reste, { zone: S.zone, recharge: true, t: t }); return; }
  S = null; P = null; g('vue').innerHTML = ''; g('vue').appendChild(el('p', { class: 'aide', text: T.choisir }));
  dessineListe();
}
/** The same passes read again (something added to the one shown), where they were. */
function rechargeEditeur() { return ouvreEditeur(S.ids, { zone: S.zone, recharge: true, t: t }); }
// ------------------------------------------------------------ the margins, the paces: the same for one pass or several
/** Before the AOS, after the LOS (ms): « Commencer X min avant l'AOS », « Finir X min après le LOS », 0 to 30 min. */
function marges() { return [Math.max(0, Math.min(30, +O.mAvant || 0)) * 60000, Math.max(0, Math.min(30, +O.mApres || 0)) * 60000]; }
/**
 * What a pass's replay covers: from its margin before the AOS to its margin after the LOS, and what is tied to it
 * a little before or after (a contact logged once the satellite had set, the recording started early).
 */
function bornesPassage(q) {
  const [ma, mp] = marges();
  q.t0 = Math.min(q.de - ma, ...q.sons.map(s => s.o + s.an), ...q.marques.map(m => m.de));
  q.t1 = Math.max(q.a + mp, ...q.marques.map(m => m.a + 5000));
}
/** The margins changed: the windows follow. */
function margesChangees() {
  if (!S) return;
  S.ps.forEach(bornesPassage); fenetresSession();
  vue0 = P.t0; vue1 = P.t1; t = Math.max(S.segs[0][0], Math.min(S.segs[S.segs.length - 1][1], t));
  if (indexA(t) !== S.i) passeA(indexA(t)); else maj();
}
/** The windows: each pass from its margin before the AOS to its margin after the LOS. */
function fenetresSession() {
  const [ma, mp] = marges();
  S.segs = S.ps.map(q => [q.de - ma, q.a + mp]);
  S.duree = S.segs.reduce((s, q) => s + q[1] - q[0], 0);
  S.i = Math.min(S.i, S.segs.length - 1);
  piecesAllure();
}
// The satellites' elements and the station's favourites (the companions), read once.
const TLE = {}; let FAV = null;
async function elementsDe(sat) { if (!(sat in TLE)) TLE[sat] = await Orbites.elements(sat); return TLE[sat]; }
async function favorisStation() { if (!FAV) FAV = await Orbites.favoris(); return FAV; }
function optionsSegments(q) { return { id: q.id, avant: O.avant, apres: O.apres, rapide: O.rapide, surActivite: O.surActivite ? '1' : '0', sstv: O.sstv ? '1' : '0' }; }
/** The accelerated replay's stretches of every pass (normal speed round what was logged or heard, faster elsewhere). */
function chargeSegments() {
  if (!S) return; const s = S;
  S.ps.forEach(q => lire('/segments', optionsSegments(q)).then(l => { if (S !== s) return; q.segments = l; if (q === P) segments = l; piecesAllure(); dessineFrise(); }).catch(() => {}));
}
function construitVue(z) {
  const v = z; v.innerHTML = '';
  v.appendChild(el('div', { class: 'entete', id: 'entete' }));
  const mo = el('div', { class: 'moment' }, [
    el('div', { class: 'heure', id: 'heure' }), el('div', { class: 'val', id: 'angles' }), el('div', { class: 'val', id: 'rx' }),
    el('div', { class: 'smetre', id: 'smetre' }), el('div', { class: 'graduation', id: 'grad' }), el('div', { id: 'lectureS' }),
    el('div', { class: 'aide', id: 'sInfo' }), el('img', { id: 'imageMoment', alt: '' }), el('div', { id: 'carteMoment' })]);
  const ecran = el('div', { class: 'ecran', id: 'ecran' }, [sv('svg', { id: 'ciel', viewBox: '0 0 440 440', preserveAspectRatio: 'xMidYMid meet' }),
    el('canvas', { id: 'carte' }), el('div', { class: 'attrib', id: 'attrib', text: '© OpenStreetMap' }),
    // The globe (SatMe-PC): the passes replayed round the Earth, their contacts and pictures at their time.
    el('canvas', { id: 'globe', title: T.globe_deplacer }),
    el('div', { class: 'bascule' }, [el('button', { id: 'bCiel', text: T.vue_ciel, onclick: () => montreVue('ciel') }),
      el('button', { id: 'bCarte', text: T.vue_carte, onclick: () => montreVue('carte') })].concat(
      window.SATME_PC ? [el('button', { id: 'bGlobe', text: T.vue_globe, onclick: () => montreVue('globe') })] : [])),
    window.SATME_PC ? choixGlobe('globeChoix', 'globeCam') : el('span'),
    el('div', { id: 'cadreSstv', title: T.sstv_cadre_aide }, [el('img', { alt: '' }),
      el('div', { class: 'vide' }, [el('b', { text: T.sstv_cadre }), el('span', { text: T.sstv_cadre_aide })]),
      el('button', { class: 'auto discret', text: T.sstv_auto, title: T.sstv_auto_aide }), el('div', { class: 'poignee' })]),
    // The files' cards, here too: the opening one, the next pass's while turning to it, the pictures at the end.
    el('div', { id: 'sCarte' })]);
  // Two columns on a wide screen (the replay on the left, the moment and the lists on the right), one below.
  const gauche = el('div', { class: 'gauche' }), cote = el('div', { class: 'cote' });
  v.appendChild(el('div', { class: 'deux' }, [gauche, cote]));
  gauche.appendChild(ecran); cote.appendChild(mo);
  const s = g('smetre'); for (let i = 0; i < 46; i++) s.appendChild(el('i', { class: (i + 0.5) / 46 > 0.6 ? 'r' : '' }));
  [1, 3, 5, 7, 9].forEach(n => g('grad').appendChild(el('span', { text: String(n), style: 'left:' + (n / 9 * 60) + '%' })));
  [20, 40, 60].forEach(db => g('grad').appendChild(el('span', { class: 'r', text: '+' + db, style: 'left:' + Math.min(97, 60 + db / 60 * 40) + '%' })));
  const vit = el('select', { id: 'vitesse', onchange: ev => { vitesse = +ev.target.value; g('son').playbackRate = vitesse; } },
    [1, 2, 4].map(x => el('option', { value: x, text: '×' + x })));
  vit.value = String(vitesse);
  const acc = el('input', { type: 'checkbox', id: 'accelere' }); acc.checked = O.accelere;
  acc.addEventListener('change', () => { O.accelere = acc.checked; garde(); piecesAllure(); if (joue) synchro(); maj(); if (onglet !== 'moments') panneau(); });
  gauche.appendChild(el('div', { class: 'commandes' }, [
    el('button', { class: 'primaire', id: 'lecture', onclick: bascule }),
    el('button', { class: 'discret', text: '−10 s', onclick: () => cherche(t - 10000) }),
    el('button', { class: 'discret', text: '+10 s', onclick: () => cherche(t + 10000) }),
    el('span', { class: 'aide', text: T.vitesse }), vit,
    el('label', { class: 'aide' }, [acc, document.createTextNode(' ' + T.accelere.split(':')[0])]),
    el('span', { class: 'aide', id: 'position' }), el('span', { class: 'aide', id: 'sansSon' })]));
  // Several passes: all of them end to end above the one shown's timeline, the pictures as ticks.
  if (S.ps.length > 1) {
    const fr = el('canvas', { id: 'sFrise', title: T.s_frise }); gauche.appendChild(fr);
    fr.addEventListener('click', ev => { const r = fr.getBoundingClientRect(); chercheSession((ev.clientX - r.left) / r.width); });
  }
  gauche.appendChild(el('canvas', { id: 'frise' }));
  gauche.appendChild(el('div', { class: 'aide', text: T.frise_aide }));
  gauche.appendChild(el('div', { class: 'rangee', id: 'selection' }));
  gauche.appendChild(el('div', { id: 'sGalerie' }));
  cote.appendChild(el('div', { class: 'onglets' }, [
    el('button', { id: 'oMoments', text: T.moments, onclick: () => { onglet = 'moments'; panneau(); } }),
    el('button', { id: 'oOptions', text: T.onglet_options, onclick: () => { onglet = 'options'; panneau(); } }),
    el('button', { id: 'oFabriquer', text: T.onglet_fabriquer, onclick: () => { onglet = 'fabriquer'; panneau(); } }),
    el('button', { id: 'oAjouter', text: T.n_onglet, onclick: () => { onglet = 'ajouter'; panneau(); } })]));
  cote.appendChild(el('div', { class: 'panneau', id: 'panneau' }));
  installeFrise(); installeCarte(); installeSstv();
  montreVue(O.vue === 'globe' && !window.SATME_PC ? 'ciel' : O.vue);
}
/** The heading: the pass (or the passes and the one shown), its report, the editor over several, taking it out. */
function majEntete() {
  const e = g('entete'); if (!e) return; e.innerHTML = '';
  const n = S.ps.length, pc = !!window.SATME_PC, ici = jour(P.de) + ' · ' + tf('duree', duree(P.a - P.de), P.el);
  if (n > 1) e.appendChild(el('span', { class: 's', text: S.sat + ' · ' + tf('s_resume', n, S.images.length) }));
  else e.appendChild(el('span', { class: 's', text: P.sat }));
  e.appendChild(el('span', { class: 'd', text: (n > 1 ? jour(S.ps[0].de) + ' → ' + jour(S.ps[n - 1].a) + ' — ' + tf('s_passage', S.i + 1, n) + ' : ' : '') +
    ici + (P.loc ? ' · ' + P.loc : '') + (P.tp ? ' · ' + P.tp : '') }));
  // The observation report (SatMe-PC): its own page, beside the replay — of the pass shown.
  if (pc) e.appendChild(el('a', { class: 'discret', id: 'lienRapport', target: '_blank', href: url('/rapport', { id: P.id }), text: T.rapport }));
  // The same editor over several passes: this satellite's, the day before to the day after.
  if (pc && n === 1) e.appendChild(el('button', { class: 'discret', text: '🌍 ' + T.s_autour, title: T.session_aide,
    onclick: () => construitSession({ sat: P.sat, de: P.de - 86400000, a: P.de + 86400000, images: false, lance: true }) }));
  if (n > 1) e.appendChild(el('button', { class: 'discret', text: T.s_ouvrir, onclick: () => ouvre(P.id) }));
  // Taken out of the journal, asked first: its recordings, pictures and contacts stay.
  e.appendChild(el('button', { class: 'discret', style: 'color:var(--rose)', text: T.supprimer, onclick: supprimePassage }));
  const ss = g('sansSon'); if (ss) ss.textContent = P.sons.length ? '' : T.sans_son;
}
// ------------------------------------------------------------ the SSTV picture over the replay
// Where the video puts it: placed by hand (shares of the view: left, top, width;
// 4:3, 15 to 90 % of the width, inside the view; kept by the station), else by
// itself, at the top, in the corner away from the satellite. The view: the
// file's frame on the Make tab (PC), else the replay (the phone's: its square).
let cadreSstv = null, tailleSstv = 0, satEcranX = null;
function lisCadre(s) { const v = String(s || '').split(',').map(x => x.trim() === '' ? NaN : +x); return v.length === 3 && v.every(isFinite) ? { x: v[0], y: v[1], l: v[2] } : null; }
function texteCadre(c) { return c ? [c.x, c.y, c.l].map(v => v.toFixed(4)).join(',') : 'auto'; }
function vueSstv() {
  const e = g('ecran'); if (!e) return null; const W = e.clientWidth, H = e.clientHeight; if (!W || !H) return null;
  const f = onglet === 'fabriquer' ? cadreFichier() : null;
  if (f) return { x: f.fx, y: f.fy, w: f.fw, h: f.vh, dp: Math.min(f.fw / 360, f.fh / 480) };
  if (window.SATME_PC) return { x: 0, y: 0, w: W, h: H, dp: Math.min(W / 360, H / 380) };
  const c = Math.min(W, H); return { x: (W - c) / 2, y: (H - c) / 2, w: c, h: c, dp: c / 360 };
}
function placeSstv(c, w, h) {
  const l = Math.min(Math.max(0.15, Math.min(0.9, c.l)) * w, h * 4 / 3), lh = l * 3 / 4;
  return { x: Math.max(0, Math.min(w - l, c.x * w)), y: Math.max(0, Math.min(h - lh, c.y * h)), l: l, h: lh };
}
function satX(v) {
  let x = null;
  if (O.vue === 'carte') x = satEcranX;
  else if (O.vue === 'globe') x = Orbites.satEcranX();
  else { const s = g('sat'), e = g('ecran'); if (s && e) { const a = s.getBoundingClientRect(); if (a.width) x = a.left + a.width / 2 - e.getBoundingClientRect().left; } }
  return x == null ? 0.5 : (x - v.x) / v.w;
}
function placeAuto(v) {
  const l = tailleSstv > 0 ? v.w * tailleSstv : Math.min(v.w * 0.42, v.h * 0.5 * 4 / 3), m = 8 * v.dp;
  return { x: satX(v) >= 0.5 ? m : v.w - l - m, y: m, l: l, h: l * 3 / 4 };
}
function borneSstv(c, v) { const r = placeSstv(c, v.w, v.h); return { x: r.x / v.w, y: r.y / v.h, l: r.l / v.w }; }
function majSstv() {
  const o = g('cadreSstv'); if (!o || !P) return;
  const v = vueSstv(), possible = !!(O.sstv && v && P.marques.some(m => m.k === 'SSTV' && m.f));
  o.classList.toggle('possible', possible);
  if (!possible) return;
  const r = cadreSstv ? placeSstv(cadreSstv, v.w, v.h) : placeAuto(v);
  Object.assign(o.style, { left: (v.x + r.x) + 'px', top: (v.y + r.y) + 'px', width: r.l + 'px', height: r.h + 'px' });
  o.classList.toggle('manuel', !!cadreSstv);
  const img = P.marques.find(m => m.k === 'SSTV' && m.f && t >= m.de && t <= m.a + 4000), im = o.firstChild;
  if (img) { if (im.dataset.f !== img.f + '#' + tourVersion) { im.src = url('/image', { f: img.f, id: P.id, n: tourVersion }); im.dataset.f = img.f + '#' + tourVersion; }
    im.style.clipPath = 'inset(0 0 ' + ((1 - Math.max(0, Math.min(1, (t - img.de) / Math.max(1, img.a - img.de)))) * 100).toFixed(1) + '% 0)'; }
  o.classList.toggle('vu', !!img);
}
function installeSstv() {
  const o = g('cadreSstv'); let pres = null, envoi = 0;
  const retiens = () => { clearTimeout(envoi); envoi = setTimeout(() => lire('/cadreSstv', { v: cadreSstv ? texteCadre(cadreSstv) : '' }).catch(() => {}), 300); };
  const ici = v => cadreSstv ? placeSstv(cadreSstv, v.w, v.h) : placeAuto(v);
  o.addEventListener('pointerdown', ev => {
    if (ev.target.classList.contains('auto')) return;
    ev.preventDefault(); ev.stopPropagation(); const v = vueSstv(); if (!v) return;
    o.setPointerCapture(ev.pointerId); o.classList.add('prise');
    pres = { x: ev.clientX, y: ev.clientY, r: ici(v), v: v, taille: ev.target.classList.contains('poignee') };
  });
  o.addEventListener('pointermove', ev => {
    if (!pres) return; const dx = ev.clientX - pres.x, dy = ev.clientY - pres.y, r = pres.r, v = pres.v;
    // The corner: the larger of the two pulls, the picture staying 4:3 from its top left.
    const c = pres.taille ? { x: r.x / v.w, y: r.y / v.h, l: Math.max(r.l + dx, (r.h + dy) * 4 / 3) / v.w }
      : { x: (r.x + dx) / v.w, y: (r.y + dy) / v.h, l: r.l / v.w };
    cadreSstv = borneSstv(c, v); majSstv();
  });
  const lache = () => { if (!pres) return; pres = null; o.classList.remove('prise'); retiens(); };
  o.addEventListener('pointerup', lache); o.addEventListener('pointercancel', lache);
  o.addEventListener('wheel', ev => {
    ev.preventDefault(); const v = vueSstv(); if (!v) return;
    const r = ici(v), l = r.l * (ev.deltaY > 0 ? 1 / 1.1 : 1.1);
    cadreSstv = borneSstv({ x: (r.x + (r.l - l) / 2) / v.w, y: (r.y + (r.h - l * 3 / 4) / 2) / v.h, l: l / v.w }, v); majSstv(); retiens();
  }, { passive: false });
  o.querySelector('.auto').addEventListener('click', ev => { ev.stopPropagation(); cadreSstv = null; majSstv(); retiens(); });
}
// ------------------------------------------------------------ the file's frame (PC)
// Making a file on the PC: where its view and its caption fall on the replay (16:9, or a phone's 9:16 upright,
// the caption under the view as high as the station draws it), the largest such frame the screen holds. The
// sky and the globe are drawn in its view, as the file shows them; the map's centre and scale are sent from it.
function cadreFichier() {
  const e = g('ecran'); if (!window.SATME_PC || !e) return null;
  const W = e.clientWidth, H = e.clientHeight; if (!W || !H) return null;
  const tel = O.format === 'telephone', A = tel ? 9 / 16 : 16 / 9;
  const fw = Math.min(W, H * A), fh = fw / A;
  // The caption's share of the frame at the texts' size: in 16:9 three rows (27 + 65 × size units of 1/480 of the
  // height, at most 28 %, as RenduPc and LegendePc.hauteurLarge draw it); upright about as high as its rows ask.
  const k = Math.max(.6, Math.min(2.5, +O.police || 1));
  const part = tel ? Math.min(.45, .045 + .2 * k) : Math.min(.28, (27 + 65 * k) / 480);
  return { fx: (W - fw) / 2, fy: (H - fh) / 2, fw: fw, fh: fh, vh: fh * (1 - part), W: W, H: H, vertical: tel };
}
function dessineCadre() {
  let o = g('cadreF'); const c = P && onglet === 'fabriquer' ? cadreFichier() : null;
  const vues = [g('ciel'), g('globe')].filter(x => x);
  if (!c) { if (o) o.remove(); vues.forEach(x => ['left', 'top', 'width', 'height'].forEach(k => x.style[k] = '')); return; }
  if (!o) { o = el('div', { id: 'cadreF' }, [el('div', { class: 'vueF' }), el('div', { class: 'legF' }), el('div', { class: 'fmtF' })]); g('ecran').appendChild(o); }
  const [v, l, f] = o.children;
  Object.assign(v.style, { left: c.fx + 'px', top: c.fy + 'px', width: c.fw + 'px', height: c.vh + 'px' });
  Object.assign(l.style, { left: c.fx + 'px', top: (c.fy + c.vh) + 'px', width: c.fw + 'px', height: (c.fh - c.vh) + 'px' });
  l.innerHTML = ''; l.classList.toggle('col', !c.vertical);
  // 16:9: the satellite and the time on the left, the station and the session's pass on the right, as in the file.
  if (c.vertical) { l.appendChild(el('b', { text: P.sat })); l.appendChild(el('span', { text: jour(t) + (P.loc ? ' · ' + P.loc : '') +
    (S.ps.length > 1 ? ' · ' + tf('s_passage', S.i + 1, S.ps.length) : '') })); }
  else { l.appendChild(el('div', {}, [el('b', { text: P.sat }), el('span', { text: jour(t) })]));
    l.appendChild(el('div', { class: 'dr' }, [el('span', { text: (S.ps.length > 1 ? tf('s_passage', S.i + 1, S.ps.length) + ' · ' : '') + 'SatMe' }),
      el('span', { text: [P.indicatif, P.loc].filter(v => v).join(' · ') })])); }
  f.textContent = c.vertical ? T.format_portrait : T.format_paysage;
  // The sky and the globe as the file draws them: in its view.
  vues.forEach(x => Object.assign(x.style, { left: c.fx + 'px', top: c.fy + 'px', width: c.fw + 'px', height: c.vh + 'px' }));
}

/* The globe's choices (look, camera, cities, their names, details). */
function choixGlobe(id, idCam) {
  const sel = (attrs, l, v) => { const s = el('select', attrs, l.map(c => el('option', { value: c[0], text: c[1] }))); s.value = v; return s; };
  return el('div', { class: 'globe-choix', id: id }, [
    sel({ class: 'ob-style-choix', onchange: ev => Orbites.style(ev.target.value) }, Orbites.styles(), Orbites.styleActif()),
    sel({ id: idCam, onchange: ev => Orbites.camera(ev.target.value) }, [['espace', T.cam_ensemble], ['poursuite', T.cam_poursuite], ['sol', T.cam_station], ['satellite', T.cam_satellite]], Orbites.cameraActive()),
    sel({ title: T.villes, onchange: ev => Orbites.villes(ev.target.value) }, [['toutes', T.villes + ' : ' + T.v_toutes], ['capitales', T.villes + ' : ' + T.v_capitales], ['aucune', T.villes + ' : ' + T.v_aucune]], Orbites.villesActives()),
    sel({ onchange: ev => Orbites.etiquettes(ev.target.value) }, [['nom', T.e_nom], ['drapeau', T.e_drapeau], ['nomdrapeau', T.e_nomdrapeau]], Orbites.etiquettesActives()),
    sel({ title: T.detail, onchange: ev => Orbites.detail(+ev.target.value) }, [['0', T.detail + ' : ' + T.d_0], ['1', T.detail + ' : ' + T.d_1], ['2', T.detail + ' : ' + T.d_2]], String(Orbites.detailActif()))]);
}
/** The view: the sky or the map of the pass shown (one after the other), or the globe round all of them (SatMe-PC). */
function montreVue(v) {
  if (v === 'globe' && !window.SATME_PC) v = 'ciel';
  O.vue = v; garde();
  g('ciel').style.display = v === 'ciel' ? 'block' : 'none';
  g('carte').style.display = v === 'carte' ? 'block' : 'none';
  g('attrib').style.display = v === 'carte' ? 'block' : 'none';
  g('globe').style.display = v === 'globe' ? 'block' : 'none';
  if (g('globeChoix')) g('globeChoix').style.display = v === 'globe' ? 'flex' : 'none';
  g('bCiel').classList.toggle('actif', v === 'ciel'); g('bCarte').classList.toggle('actif', v === 'carte');
  if (g('bGlobe')) g('bGlobe').classList.toggle('actif', v === 'globe');
  if (v === 'carte') dessineCarte();
  if (v === 'globe') {
    g('globeChoix').firstChild.value = Orbites.styleActif();
    g('globeCam').value = ['poursuite', 'sol', 'satellite'].includes(Orbites.cameraActive()) ? Orbites.cameraActive() : 'espace';
    if (g('globeCam').value === 'espace') Orbites.camera('espace');
    lanceGlobe();
  }
  dessineCadre(); majSstv();
}
/**
 * The globe round all the passes: their tracks, contacts and pictures; the satellite seen coming before each AOS, going
 * after each LOS (its elements); as large as the zooms say (as the files show it), the wheel changing the one of the moment.
 */
function lanceGlobe() {
  const sol = []; S.ps.forEach(q => (q.sol || []).forEach(x => sol.push(x))); sol.sort((x, y) => x[0] - y[0]);
  const q0 = S.ps[0];
  Orbites.rejeu(g('globe'), { id: 'editeur:' + S.version + ':' + S.ids.join(','), sat: S.sat, sol: sol, qth: q0.qth, indicatif: q0.indicatif, locator: q0.loc,
    marques: S.marques, tle: q0.tle, image: m => url('/image', { f: m.f, id: m.pid, n: tourVersion }), horloge: () => t, cartes: () => !!O.vignettes,
    // The page's heights are the files' sizes the other way round (3.5 / size).
    hauteur: () => S ? 3.5 / zoomSession() : 3.5, molette: moletteSession, nomStation: () => !!O.locator }).then(() => compagnons());
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
  if (onglet === 'rejeu') onglet = 'options';
  ['Moments', 'Options', 'Fabriquer', 'Ajouter'].forEach(o => g('o' + o).classList.toggle('actif', onglet === o.toLowerCase()));
  const p = g('panneau'); p.innerHTML = '';
  if (onglet === 'moments') panneauMoments(p); else if (onglet === 'options') panneauOptions(p);
  else if (onglet === 'ajouter') panneauAjouter(p); else panneauExports(p);
  dessineCadre(); majSstv(); majElements();
}
function panneauMoments(p) {
  // Several passes: each to go to (or to open alone), then the moments of the one shown.
  if (S.ps.length > 1) {
    p.appendChild(titreBloc(T.s_passages));
    S.ps.forEach((q, i) => {
      const haut = Math.round((q.pts || []).reduce((m, x) => x[2] > m ? x[2] : m, 0)), n = S.images.filter(m => m.pid === q.id).length;
      p.appendChild(el('div', { class: 'mo pa' + (i === S.i ? ' actif' : ''), 'data-i': i, onclick: () => allerPassage(i) }, [
        el('span', { class: 'h', text: String(i + 1) }), el('span', { class: 't', text: jour(q.de) }),
        el('span', { class: 'x', text: haut + '° · ' + duree(q.a - q.de) + (n ? ' · ' + n + ' SSTV' : '') + (q.sons.length ? ' · ♪' : '') }),
        el('button', { class: 'discret', text: T.s_ouvrir, onclick: ev => { ev.stopPropagation(); ouvre(q.id); } })]));
    });
    p.appendChild(titreBloc(T.moments + ' · ' + tf('s_passage', S.i + 1, S.ps.length)));
  }
  if (!P.marques.length) p.appendChild(el('p', { class: 'aide', text: T.aucun_moment }));
  P.marques.slice().sort((a, b) => a.de - b.de).forEach(m => {
    const k = couleur(m), f = fiches[m.t] || fiches[(m.t || '').split('-')[0]];
    const kids = [el('span', { class: 'pastille', style: 'background:' + COUL[k] }), el('span', { class: 'h', text: hms(m.de) }),
      el('span', { class: 't', text: m.t || T.signet }),
      el('span', { class: 'x', text: [m.d || (m.k === 'SSTV' ? T.sstv : ''), f ? [f.nom, f.qth, f.loc].filter(x => x).join(' · ') : ''].filter(x => x).join(' — ') })];
    if (m.f) kids.splice(1, 0, el('img', { src: url('/vignette', { f: m.f, w: 160, id: P.id, n: tourVersion }), loading: 'lazy' }));
    kids.push(el('button', { class: 'discret', text: T.choisir_morceau, onclick: ev => {
      ev.stopPropagation(); sel = [m.de - (m.k === 'SSTV' ? 1000 : O.avant * 1000), m.a + (m.k === 'QSO' ? O.apres * 1000 : 3000)];
      S.titre = m.k === 'SSTV' ? 'SSTV ' + hms(m.de) : (m.k === 'SIGNET' ? (m.t || T.signet) : (m.k === 'QSO' ? 'QSO ' : 'APRS ') + m.t);
      deQuoi = 'sel'; zoomSur(sel); maj(); } }));
    p.appendChild(el('div', { class: 'mo', onclick: () => { cherche(m.de - (m.k === 'SSTV' ? 1000 : O.avant * 1000)); if (!joue) bascule(); } }, kids));
  });
  if (P.sons.length) {
    p.appendChild(el('div', { class: 'aide', text: T.sons, style: 'margin-top:8px' }));
    P.sons.forEach(s => p.appendChild(el('div', { class: 'rangee' }, [
      el('a', { href: url('/son', { id: P.id, n: s.n }), download: s.nom, text: '⤓ ' + s.nom }),
      el('span', { class: 'aide', text: hms(s.o + s.an) + ' · ' + duree(s.du - s.an) })])));
  }
  // The pass's Doppler/CAT log, when it was kept.
  if (P.doppler && P.doppler.length) {
    p.appendChild(el('div', { class: 'aide', text: T.doppler, style: 'margin-top:8px' }));
    P.doppler.forEach(n => p.appendChild(el('div', { class: 'rangee' }, [
      el('a', { href: url('/doppler', { nom: n }), download: n, text: '⤓ ' + n })])));
  }
}
// ------------------------------------------------------------ a pass from nothing, and what it holds
// Times typed in local time; sent as milliseconds.
function versLocal(tt) { const d = new Date(tt); return d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + 'T' + p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()); }
function depuisLocal(i) { const x = new Date(i.value).getTime(); return isNaN(x) ? null : x; }
function heure(tt) { return el('input', { type: 'datetime-local', step: '1', value: versLocal(tt) }); }
async function envoie(r, prm, corps) {
  const x = await fetch(url(r, prm), { method: 'POST', body: corps || '' });
  if (x.status === 403) { porte(); throw 'session'; }
  return x.json();
}
async function nouveau() {
  arrete(); P = null; S = null; dessineListe();
  const v = g('vue'); v.innerHTML = '';
  const sat = el('select'), pas = el('select'), aos = heure(Date.now() - 3600000), los = heure(Date.now() - 3000000);
  const heures = el('div', { class: 'colonnes', style: 'display:none' }, [el('span', { class: 'aide', text: T.n_aos }), aos, el('span', { class: 'aide', text: T.n_los }), los]);
  const msg = el('span', { class: 'aide' });
  let liste = [];
  async function chargePassages() {
    const j = await lire('/nouveau/passages', { catnum: sat.value }); liste = j.passages || [];
    pas.innerHTML = '';
    liste.forEach((q, i) => pas.appendChild(el('option', { value: i, text: jour(q.aos) + ' · ' + duree(q.los - q.aos) + ' · ' + q.elMax + '°' })));
    pas.appendChild(el('option', { value: -1, text: liste.length ? T.n_autre : T.n_aucun }));
    choix();
  }
  function choix() {
    const q = liste[+pas.value]; heures.style.display = q ? 'none' : '';
    if (q) { aos.value = versLocal(q.aos); los.value = versLocal(q.los); }
  }
  sat.addEventListener('change', chargePassages); pas.addEventListener('change', choix);
  v.appendChild(el('div', { class: 'entete' }, [el('span', { class: 's', text: T.n_titre })]));
  v.appendChild(el('p', { class: 'aide', text: T.n_aide }));
  v.appendChild(el('div', { class: 'colonnes' }, [el('span', { class: 'aide', text: T.n_sat }), sat, el('span', { class: 'aide', text: T.n_passage }), pas]));
  v.appendChild(heures);
  v.appendChild(el('div', { class: 'rangee' }, [el('button', { class: 'primaire', text: T.n_creer, onclick: async () => {
    const a = depuisLocal(aos), b = depuisLocal(los); if (a == null || b == null) return;
    msg.textContent = T.n_envoi;
    const j = await envoie('/nouveau/cree', { catnum: sat.value, aos: a, los: b });
    if (!j.ok) { msg.textContent = j.erreur || T.echec; return; }
    const l = await lire('/liste'); passages = l.passages;
    onglet = 'ajouter'; ouvre(j.id);
  } }), msg]));
  const sats = await lire('/nouveau/satellites');
  sats.forEach(s => sat.appendChild(el('option', { value: s.catnum, text: s.nom })));
  const iss = sats.find(s => s.catnum === 25544); if (iss) sat.value = '25544';
  chargePassages();
}
function panneauAjouter(p) {
  // Done: said where it stays seen (the panel is drawn again with the pass), the pass and the list read again.
  const fin = async (j, msg, quoi) => {
    if (!j.ok) { msg.textContent = j.erreur || tf('n_echec', j.raison || '?'); msg.style.color = 'var(--ambre)'; return; }
    ajoutFait = (quoi ? quoi + ' — ' : '') + T.n_fait + ' · ' + hms(Date.now()).slice(0, 5);
    const l = await lire('/liste'); passages = l.passages; rechargeEditeur();
  };
  if (ajoutFait) p.appendChild(el('div', { class: 'rangee', style: 'color:var(--vert);font-weight:700', text: '✓ ' + ajoutFait }));
  // A contact, at its time: where the sound is paused, else the middle of the pass; the mode the pass's (auto).
  const call = el('input', { placeholder: T.n_call, style: 'text-transform:uppercase;width:9em', autocomplete: 'off' }), loc = el('input', { placeholder: T.n_loc, style: 'text-transform:uppercase;width:5em' });
  const md = el('select', {}, [['', T.n_mode_auto]].concat(['FM', 'USB', 'LSB', 'CW', 'DATA'].map(m => [m, m])).map(([v, l]) => el('option', { value: v, text: l })));
  const tq = heure(heureContact()), m1 = el('span', { class: 'aide' }), qui = el('div', { class: 'aide' }), props = el('div', { class: 'rangee' });
  // The reports both ways (59 to start with) and a comment, as in the carnet.
  const rs = el('input', { value: '59', title: T.n_rs, style: 'width:3.5em' }), rr = el('input', { value: '59', title: T.n_rr, style: 'width:3.5em' });
  const note = el('input', { placeholder: T.n_note, style: 'flex:1;min-width:10em' });
  champHeureQso = tq; heureTouchee = false;
  tq.addEventListener('input', () => { heureTouchee = true; });
  // Who it is, as the carnet finds it: the log's predictions as typed, then QRZ; its square unless typed for this very callsign.
  let nm = '', qth = '', locPour = '', minuteur = null, minuteurQrz = null;
  const baseInd = c => { const p = c.toUpperCase().split('/'); return p.reduce((a, b) => b.length > a.length ? b : a, ''); };
  const api = (r, prm) => { const q = new URLSearchParams(prm || {}); q.set('cle', cle); return base + '/api/' + r + '?' + q.toString(); };
  const metLoc = (l, c) => { if (!l || call.value.indexOf('/') >= 0) return; if (loc.value.trim() && locPour === c) return; loc.value = l; locPour = c; };
  loc.addEventListener('input', () => { locPour = baseInd(call.value.trim()); });
  const prend = p => { call.value = p.c; nm = p.nm || ''; metLoc(p.l, baseInd(p.c)); props.innerHTML = ''; qui.textContent = [p.nm, p.l, '×' + p.n].filter(x => x).join(' · '); cherche(); };
  function cherche() {
    clearTimeout(minuteur); clearTimeout(minuteurQrz);
    const saisie = call.value.trim(), c = baseInd(saisie);
    minuteur = setTimeout(async () => {
      let j = null; try { j = await (await fetch(api('carnet/propositions', { c: saisie }))).json(); } catch (x) { return; }
      if (call.value.trim() !== saisie) return;
      props.innerHTML = '';
      (j.propositions || []).filter(p => p.c !== saisie.toUpperCase()).slice(0, 4).forEach(p =>
        props.appendChild(el('button', { class: 'discret', text: p.c + (p.l ? ' · ' + p.l : ''), title: [p.nm, p.sat ? T.n_dernier + ' ' + p.sat : ''].filter(x => x).join(' · '), onclick: () => prend(p) })));
    }, 150);
    if (c.length < 4 || !/[0-9]/.test(c)) return;
    minuteurQrz = setTimeout(async () => {
      let f = null; try { f = await (await fetch(api('qrz', { c: c }))).json(); } catch (x) { return; }
      if (baseInd(call.value.trim()) !== c || !f || !f.ok) { if (f && f.erreur && baseInd(call.value.trim()) === c) qui.textContent = f.erreur; return; }
      nm = [f.prenom, f.nom].filter(x => x).join(' ') || f.nom || ''; qth = f.qth || '';
      qui.textContent = [nm, f.qth, f.pays, f.km != null ? f.km + ' km' : ''].filter(x => x).join(' · ') + ' (QRZ)';
      metLoc(f.carre, c);
    }, 600);
  }
  call.addEventListener('input', () => { nm = ''; qth = ''; qui.textContent = ''; cherche(); });
  p.appendChild(el('div', { class: 'aide', text: T.n_contact }));
  p.appendChild(el('div', { class: 'rangee' }, [call, loc, md]));
  p.appendChild(props); p.appendChild(qui);
  p.appendChild(el('div', { class: 'rangee' }, [el('span', { class: 'aide', text: T.n_rs }), rs, el('span', { class: 'aide', text: T.n_rr }), rr, note]));
  async function enregistre() {
    if (!call.value.trim()) { m1.textContent = T.n_indicatif; call.focus(); return; }
    m1.textContent = T.n_envoi;
    const tc = depuisLocal(tq);
    fin(await envoie('/nouveau/contact', { id: P.id, c: call.value.trim(), l: loc.value.trim(), md: md.value, nm: nm, qth: qth,
      rs: rs.value.trim(), rr: rr.value.trim(), n: note.value.trim(), t: tc || '' }), m1,
      call.value.trim().toUpperCase() + (tc ? ' ' + hms(tc) : '') + ' · ' + T.n_carnet);
  }
  // Enter saves, from any field of the contact (the callsign first of all).
  [call, loc, rs, rr, note, tq].forEach(c => c.addEventListener('keydown', ev => { if (ev.key === 'Enter') { ev.preventDefault(); enregistre(); } }));
  p.appendChild(el('div', { class: 'rangee' }, [tq, el('button', { class: 'discret', text: T.n_envoyer, onclick: enregistre }), m1]));
  // Back on the tab after a contact: the callsign field ready for the next one.
  if (ajoutFait) setTimeout(() => call.focus(), 0);
  // Its recording, from when it started.
  const fs = el('input', { type: 'file', accept: '.wav,.mp3,audio/wav,audio/mpeg' }), ts = heure(P.de), m2 = el('span', { class: 'aide' });
  p.appendChild(el('div', { class: 'aide', text: T.n_son, style: 'margin-top:10px' }));
  p.appendChild(el('div', { class: 'rangee' }, [fs]));
  p.appendChild(el('div', { class: 'rangee' }, [ts, el('button', { class: 'discret', text: T.n_envoyer, onclick: async () => {
    const f = fs.files[0]; if (!f) return;
    m2.textContent = T.n_envoi;
    fin(await envoie('/nouveau/son', { id: P.id, nom: f.name, t: depuisLocal(ts) || '' }, f), m2, f.name);
  } }), m2]));
  // Its pictures, received at a time (each next one ten seconds later).
  const fi = el('input', { type: 'file', accept: 'image/png,image/jpeg', multiple: 'multiple' }), ti = heure((P.de + P.a) / 2);
  const mo = el('input', { placeholder: T.n_mode, style: 'width:8em' }), m3 = el('span', { class: 'aide' });
  p.appendChild(el('div', { class: 'aide', text: T.n_images, style: 'margin-top:10px' }));
  p.appendChild(el('div', { class: 'rangee' }, [fi]));
  p.appendChild(el('div', { class: 'rangee' }, [ti, mo, el('button', { class: 'discret', text: T.n_envoyer, onclick: async () => {
    const l = [...fi.files]; if (!l.length) return;
    const t0 = depuisLocal(ti) || ((P.de + P.a) / 2);
    let j = { ok: true };
    for (let i = 0; i < l.length && j.ok; i++) { m3.textContent = T.n_envoi + ' ' + (i + 1) + '/' + l.length; j = await envoie('/nouveau/image', { id: P.id, t: Math.round(t0 + i * 10000), mode: mo.value.trim() }, l[i]); }
    fin(j, m3, l.length + ' image(s)');
  } }), m3]));
}
// What was just added, said at the top of the Add tab (its panel is drawn again); forgotten with another pass.
let ajoutFait = '';
// The contact's time field: it follows the sound where it is paused, until typed in.
let champHeureQso = null, heureTouchee = false;
function heureContact() { return !joue && P && t > P.de && t <= P.a + 60000 ? t : (P.de + P.a) / 2; }
function majHeureContact() {
  if (!champHeureQso || heureTouchee || joue || onglet !== 'ajouter' || !document.body.contains(champHeureQso)) return;
  const v = versLocal(heureContact()); if (champHeureQso.value !== v) champHeureQso.value = v;
}
// ------------------------------------------------------------ the pass's SSTV pictures and the report's panels
// Kept with the pass (its file on the station), the same for the video, the GIF, the picture and the replay:
// which version of each picture (as received by default), « corrigée » written or not, the report's panels
// laid over the view (placed with the mouse, in shares of the view as the SSTV picture) or cards of their own.
let SS = null, tourVersion = 0, tourEl = 0, attenteFusion = 0;
async function chargeSstv() {
  if (!P) return; const id = P.id;
  try { const j = await lire('/sstv', { id: id }); if (!P || P.id !== id || !j.ok) return; SS = j; } catch (e) { return; }
  if (onglet === 'fabriquer') dessineSstvChoix();
  majElements();
}
async function choixSstv(prm) {
  if (!P) return; const id = P.id;
  let j = null; try { j = await envoie('/sstv/choix', Object.assign({ id: id }, prm)); } catch (e) { return; }
  if (!j || !j.ok || !P || P.id !== id) return;
  SS = j;
  if (prm.quoi !== 'element') { tourVersion++; imageVue = ''; }
  if (prm.quoi === 'plan') tourEl++;
  if (prm.quoi !== 'element' || prm.cadre === undefined) dessineSstvChoix();
  majElements(); majSstv(); maj();
}
function libVersion(v) { return { auto: T.v_auto, recue: T.v_recue, corrigee: T.v_corrigee, fusion: T.v_fusion }[v] || v; }
function dessineSstvChoix() {
  const z = g('blocSstv'); if (!z) return; z.innerHTML = '';
  if (!SS) return;
  const a = el('div', { class: 'bloc versions' });
  a.appendChild(el('div', { class: 'aide', style: 'font-weight:700;color:var(--clair)', text: T.sstv_titre }));
  if (!SS.images.length) a.appendChild(el('div', { class: 'aide', text: T.sstv_aucune }));
  else {
    const d = el('select', { onchange: ev => choixSstv({ quoi: 'defaut', v: ev.target.value }) },
      ['auto', 'recue', 'corrigee', 'fusion'].map(v => el('option', { value: v, text: libVersion(v) })));
    d.value = SS.defaut;
    a.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.sstv_defaut }), d]));
    SS.images.forEach(im => {
      // Only the versions there are, each with its mark.
      const s = el('select', { onchange: ev => choixSstv({ quoi: 'image', f: im.f, v: ev.target.value }) },
        [el('option', { value: '', text: T.v_passage + ' · ' + libVersion(SS.defaut) }), el('option', { value: 'auto', text: T.v_auto })].concat(
          im.versions.map(v => el('option', { value: v.v, text: libVersion(v.v) + (v.note >= 0 ? ' · ' + tf('v_note', v.note) : '') }))));
      // A version chosen once and gone since: still offered, so the choice shows.
      if (im.choix && ![...s.options].some(o => o.value === im.choix)) s.appendChild(el('option', { value: im.choix, text: libVersion(im.choix) }));
      s.value = im.choix || '';
      const kids = [el('img', { src: url('/vignette', { f: im.f, w: 160, id: P.id, n: tourVersion }), loading: 'lazy' }),
        el('span', { class: 'h', text: hms(im.t) }), el('span', { class: 'x', text: im.mode }), s,
        el('span', { class: 'x', text: '→ ' + libVersion(im.montre) + (im.montreNote >= 0 ? ' · ' + tf('v_note', im.montreNote) : '') + (im.repli ? ' · ' + T.repli : '') })];
      if (im.fusionPossible) kids.push(el('button', { class: 'discret', text: SS.enCours ? T.fusion_en_cours : T.creer_fusion, onclick: ev => fusionner(im.f, ev.target) }));
      a.appendChild(el('div', { class: 'vi' }, kids));
    });
    const c = el('input', { type: 'checkbox' }); c.checked = !!SS.marque;
    c.addEventListener('change', () => choixSstv({ quoi: 'marque', v: c.checked ? '1' : '0' }));
    a.appendChild(el('label', { class: 'rangee', title: T.marque_aide }, [c, el('span', { text: T.marque_traitees })]));
    a.appendChild(el('div', { class: 'aide', text: T.sstv_aide }));
  }
  z.appendChild(a);
  const b = el('div', { class: 'bloc' });
  b.appendChild(el('div', { class: 'aide', style: 'font-weight:700;color:var(--clair)', text: T.el_titre }));
  b.appendChild(el('div', { class: 'aide', text: SS.evenement ? tf('el_evenement', SS.evenement.nom) : T.el_sans_evenement }));
  // The event's days: all its passes animated on the globe (SatMe-PC).
  if (window.SATME_PC && SS.evenement && SS.evenement.de) b.appendChild(el('button', { class: 'discret', text: T.s_evenement,
    onclick: () => construitSession({ sat: SS.evenement.sat || P.sat, de: SS.evenement.de, a: SS.evenement.a, lance: true }) }));
  const c2 = el('div', { class: 'colonnes' });
  SS.elements.forEach(x => {
    const caseEl = (cle, lib) => { const k = el('input', { type: 'checkbox' }); k.checked = !!x[cle]; k.disabled = !x.dispo;
      k.addEventListener('change', () => { const q = { quoi: 'element', k: x.k }; q[cle] = k.checked ? '1' : '0'; choixSstv(q); });
      return el('label', {}, [k, document.createTextNode(' ' + lib)]); };
    c2.appendChild(el('div', { class: 'rangee', title: x.dispo ? '' : T.el_absent }, [el('span', { text: x.nom, style: x.dispo ? '' : 'color:var(--gris)' }),
      caseEl('incruste', T.el_incruste), caseEl('carte', T.el_carte)].concat(x.dispo ? [] : [el('span', { class: 'aide', text: T.el_absent })])));
  });
  b.appendChild(c2);
  if (SS.plans.length && SS.dessin) {
    const pl = el('select', { onchange: ev => choixSstv({ quoi: 'plan', v: ev.target.value }) },
      [el('option', { value: '', text: T.el_plan_auto })].concat(SS.plans.map(x => el('option', { value: x.id, text: x.nom }))));
    pl.value = SS.plan || '';
    b.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.el_plan }), pl]));
  } else if (!SS.dessin) b.appendChild(el('div', { class: 'aide', text: T.el_simple }));
  b.appendChild(el('div', { class: 'aide', text: T.el_aide }));
  z.appendChild(b);
}
async function fusionner(f, bouton) {
  if (!P) return;
  bouton.disabled = true; bouton.textContent = T.fusion_en_cours;
  try { await envoie('/sstv/fusionner', { id: P.id, f: f }); } catch (e) {}
  // Done in the background: read again until the station says it is over.
  clearTimeout(attenteFusion);
  const suite = async n => { await chargeSstv(); if (SS && SS.enCours && n < 60) attenteFusion = setTimeout(() => suite(n + 1), 1500); else { tourVersion++; imageVue = ''; dessineSstvChoix(); maj(); } };
  attenteFusion = setTimeout(() => suite(0), 1200);
}
// The panels over the view: where the file puts them, the view being the SSTV picture's.
function placeEl(c, x, w, h) {
  const l = Math.min(Math.max(x.min, Math.min(0.9, c.l)) * w, h * x.ratio), lh = l / x.ratio;
  return { x: Math.max(0, Math.min(w - l, c.x * w)), y: Math.max(0, Math.min(h - lh, c.y * h)), l: l, h: lh };
}
function majElements() {
  const e = g('ecran'); if (!e) return;
  const v = onglet === 'fabriquer' && SS ? vueSstv() : null;
  const voulus = v ? SS.elements.filter(x => x.incruste && x.dispo) : [];
  [...e.querySelectorAll('.cadreEl')].forEach(o => { if (!voulus.some(x => x.k === o.dataset.k)) o.remove(); });
  voulus.forEach(x => {
    let o = e.querySelector('.cadreEl[data-k="' + x.k + '"]');
    if (!o) { o = el('div', { class: 'cadreEl', 'data-k': x.k, title: x.nom + ' — ' + T.sstv_cadre_aide }, [el('img', { alt: x.nom }),
      el('button', { class: 'auto discret', text: T.sstv_auto }), el('div', { class: 'poignee' })]); e.appendChild(o); installeEl(o); }
    const src = url('/sstv/element.svg', { id: P.id, k: x.k, n: tourEl });
    if (o.dataset.src !== src) { o.firstChild.src = src; o.dataset.src = src; }
    const c = lisCadre(x.cadre) || lisCadre(x.defaut), r = placeEl(c, x, v.w, v.h);
    Object.assign(o.style, { left: (v.x + r.x) + 'px', top: (v.y + r.y) + 'px', width: r.l + 'px', height: r.h + 'px' });
    o.classList.toggle('manuel', !!x.cadre);
  });
}
function installeEl(o) {
  let pres = null;
  const x = () => SS && SS.elements.find(y => y.k === o.dataset.k);
  const garde = c => { const k = x(); if (!k) return; k.cadre = c ? texteCadre(c) : ''; majElements(); };
  const envoi = () => { const k = x(); if (k) choixSstv({ quoi: 'element', k: k.k, cadre: k.cadre || 'auto' }); };
  const borne = (c, k, v) => { const r = placeEl(c, k, v.w, v.h); return { x: r.x / v.w, y: r.y / v.h, l: r.l / v.w }; };
  const ici = (k, v) => placeEl(lisCadre(k.cadre) || lisCadre(k.defaut), k, v.w, v.h);
  o.addEventListener('pointerdown', ev => {
    if (ev.target.classList.contains('auto')) return;
    ev.preventDefault(); ev.stopPropagation(); const v = vueSstv(), k = x(); if (!v || !k) return;
    o.setPointerCapture(ev.pointerId); o.classList.add('prise');
    pres = { x: ev.clientX, y: ev.clientY, r: ici(k, v), v: v, taille: ev.target.classList.contains('poignee') };
  });
  o.addEventListener('pointermove', ev => {
    const k = x(); if (!pres || !k) return; const dx = ev.clientX - pres.x, dy = ev.clientY - pres.y, r = pres.r, v = pres.v;
    const c = pres.taille ? { x: r.x / v.w, y: r.y / v.h, l: Math.max(r.l + dx, (r.h + dy) * k.ratio) / v.w }
      : { x: (r.x + dx) / v.w, y: (r.y + dy) / v.h, l: r.l / v.w };
    garde(borne(c, k, v));
  });
  const lache = () => { if (!pres) return; pres = null; o.classList.remove('prise'); envoi(); };
  o.addEventListener('pointerup', lache); o.addEventListener('pointercancel', lache);
  let minuteur = 0;
  o.addEventListener('wheel', ev => {
    ev.preventDefault(); const v = vueSstv(), k = x(); if (!v || !k) return;
    const r = ici(k, v), l = r.l * (ev.deltaY > 0 ? 1 / 1.1 : 1.1);
    garde(borne({ x: (r.x + (r.l - l) / 2) / v.w, y: (r.y + (r.h - l / k.ratio) / 2) / v.h, l: l / v.w }, k, v));
    clearTimeout(minuteur); minuteur = setTimeout(envoi, 300);
  }, { passive: false });
  o.querySelector('.auto').addEventListener('click', ev => { ev.stopPropagation(); garde(null); envoi(); });
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
  const pts = (P.sol || []).concat(P.solp || []).map(s => [s[1], s[2]]);
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
  // The whole predicted pass, dashed, under the part followed (a pass followed only in part no longer looks cut short).
  if (P.solp && P.solp.length > 1) trace(P.solp.map(q => [q[1], q[2]]), 'rgba(0,150,170,.55)', 2, [6, 5]);
  trace(sol.map(q => [q[1], q[2]]), 'rgba(0,229,255,.35)', 3);
  const fait = sol.filter(q => q[0] <= t).map(q => [q[1], q[2]]); const ici = solA(t); if (ici) fait.push([ici[1], ici[2]]);
  trace(fait, '#00E5FF', 4);
  P.marques.filter(m => m.k === 'SSTV').forEach(m => { const l = sol.filter(q => q[0] >= m.de && q[0] <= m.a).map(q => [q[1], q[2]]);
    const a = solA(m.de), b = solA(m.a); if (a) l.unshift([a[1], a[2]]); if (b) l.push([b[1], b[2]]); trace(l, COULHEX.SSTV, 7); });
  if (ici) {
    // The footprint as one polygon: longitudes continuous, closed over the pole when it holds it, a turn each side too.
    const rho = Math.acos(6371 / (6371 + ici[3])) * 180 / Math.PI;
    const u = []; let ref = null;
    empreinte(ici[1], ici[2], rho).forEach(q => { let l = q[1]; if (ref != null) { while (l - ref > 180) l -= 360; while (l - ref < -180) l += 360; } ref = l; u.push([q[0], l]); });
    const tour = u.slice(), polaire = u.length > 1 && Math.abs(u[u.length - 1][1] - u[0][1]) > 180;
    if (polaire) { const pole = ici[1] >= 0 ? 85.05 : -85.05; u.push([pole, u[u.length - 1][1]], [pole, u[0][1]]); }
    const px = lon => ox + wx(lon) * s, py = lat => oy + wy(lat) * s;
    for (const d of [-360, 0, 360]) {
      x.beginPath(); u.forEach((q, k) => k ? x.lineTo(px(q[1] + d), py(q[0])) : x.moveTo(px(q[1] + d), py(q[0]))); x.closePath();
      x.fillStyle = 'rgba(255,194,26,.12)'; x.fill();
      // Its edge only: the way round over the pole is not one.
      x.beginPath(); tour.forEach((q, k) => k ? x.lineTo(px(q[1] + d), py(q[0])) : x.moveTo(px(q[1] + d), py(q[0]))); if (!polaire) x.closePath();
      x.strokeStyle = 'rgba(255,194,26,.9)'; x.lineWidth = 2; x.stroke();
    }
  }
  // The stations worked or heard, as the video draws them: a dot on the track at their moment, named;
  // where they are, small and grey, named, linked to it.
  P.marques.filter(m => m.k !== 'SSTV' && m.de <= t).forEach(m => {
    // On the part followed when the moment is in it, else on the predicted pass.
    const dansSol = sol.length > 1 && m.de >= sol[0][0] && m.de <= sol[sol.length - 1][0];
    const sat = dansSol ? solA(m.de) : (P.solp && P.solp.length > 1 ? interpole(P.solp, m.de, 1, 2, false) : solA(m.de));
    const q = sat ? ecran(sat[1], sat[2]) : null;
    if (m.lat != null) {
      const p = ecran(m.lat, m.lon);
      if (q) { x.strokeStyle = 'rgba(122,130,142,.75)'; x.lineWidth = 1.2; x.beginPath(); x.moveTo(p[0], p[1]); x.lineTo(q[0], q[1]); x.stroke(); }
      x.fillStyle = '#fff'; x.beginPath(); x.arc(p[0], p[1], 5, 0, 7); x.fill(); x.fillStyle = '#7A828E'; x.beginPath(); x.arc(p[0], p[1], 3.5, 0, 7); x.fill();
      etiquette(x, m.t, p[0] + 7, p[1] + 10);
    }
    if (q) {
      x.fillStyle = '#fff'; x.beginPath(); x.arc(q[0], q[1], 6, 0, 7); x.fill(); x.fillStyle = COULHEX[couleur(m)]; x.beginPath(); x.arc(q[0], q[1], 4.5, 0, 7); x.fill();
      etiquette(x, m.t, q[0] + 8, q[1] - (m.k === 'SIGNET' ? -22 : 10));
    }
  });
  if (P.qth) { const p = ecran(P.qth[0], P.qth[1]); x.fillStyle = '#111'; x.beginPath(); x.arc(p[0], p[1], 7, 0, 7); x.fill(); x.strokeStyle = '#fff'; x.lineWidth = 2; x.stroke();
    if (O.locator) etiquette(x, [P.indicatif, P.loc].filter(v => v).join(' · '), p[0] - 12, p[1] + 4, true); }
  satEcranX = ici ? ecran(ici[1], ici[2])[0] : null;
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
  const n = S.ps.length;
  g('heure').textContent = (n > 1 ? jour(t).replace(/ \d\d:\d\dZ?$/, '') + ' ' : '') + hms(t) + (utc ? ' UTC' : '');
  // The pass's times; several: which one, the pictures received so far.
  const haut = (P.pts || []).reduce((m, x) => x[2] > m[2] ? x : m, [P.de, 0, -90]);
  g('sInfo').textContent = (n > 1 ? tf('s_passage', S.i + 1, n) + ' · ' + tf('s_recues', S.images.filter(m => m.a <= t).length) + ' — ' : '') +
    'AOS ' + hms(P.de) + ' · TCA ' + hms(haut[0]).slice(0, 5) + ' (' + Math.round(haut[2]) + '°) · LOS ' + hms(P.a);
  // In the margins, before the AOS or after the LOS: how long to go or since, not the pass's end's pointing.
  const q = t >= P.de && t <= P.a ? ptA(t) : null;
  g('angles').textContent = q ? 'Az ' + Math.round(q[1]) + '°  ·  El ' + Math.round(q[2]) + '°' : t < P.de ? tf('s_aos_dans', mmss(P.de - t)) : T.s_apres_los + ' + ' + mmss(t - P.a);
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
  if (img) { if (imageVue !== img.f + '#' + tourVersion) { im.src = url('/image', { f: img.f, id: P.id, n: tourVersion }); imageVue = img.f + '#' + tourVersion; } im.style.display = 'block';
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
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); pres = { x: ev.offsetX, t: tDeX(ev.offsetX, c.clientWidth), bouge: false, avant: sel }; });
  // A stretch only from a real drag (12 px); one under 10 s is dropped (the stretch before it kept), the press read as a click.
  c.addEventListener('pointermove', ev => { if (!pres) return; if (Math.abs(ev.offsetX - pres.x) > 12) pres.bouge = true;
    if (pres.bouge) { const b = tDeX(ev.offsetX, c.clientWidth); sel = [Math.min(pres.t, b), Math.max(pres.t, b)]; maj(); } });
  c.addEventListener('pointerup', () => {
    if (pres && pres.bouge && sel && sel[1] - sel[0] < 10000) { sel = pres.avant; pres.bouge = false; }
    if (pres && !pres.bouge) cherche(pres.t); else if (sel) deQuoi = 'sel';
    pres = null; maj(); if (onglet === 'fabriquer') panneau(); });
  c.addEventListener('wheel', ev => { ev.preventDefault(); const w = c.clientWidth, m = tDeX(ev.offsetX, w), k = ev.deltaY > 0 ? 1.25 : 0.8;
    const l = Math.max(20000, Math.min((P.t1 - P.t0) + 1200000, (vue1 - vue0) * k));
    vue0 = m - (m - vue0) / (vue1 - vue0) * l; vue1 = vue0 + l; dessineFrise(); }, { passive: false });
  c.addEventListener('dblclick', () => { vue0 = P.t0; vue1 = P.t1; dessineFrise(); });
}
function zoomSur(r) { const l = r[1] - r[0]; vue0 = r[0] - l * 0.3; vue1 = r[1] + l * 0.3; }

// ------------------------------------------------------------ the replay: the files' paces, the sound where they run in real time
let morceauJoue = '', dernier = 0, allureVue = 1;
function morceauA(tt) { return P ? P.sons.findIndex(s => tt >= s.o + s.an && tt < s.o + s.du) : -1; }
/**
 * The paces (real time per time shown), as the station's files have them ([SessionRenduPc.Allure]): stretches each at
 * its pace — outside the windows (« continu » only), in a window, a picture or a contact coming, a contact heard with the
 * sound (real time); with the accelerated replay, its stretches' paces in the passes instead (normal: real time, else
 * faster), the margins at the pass's.
 */
function piecesAllure() {
  if (!S) return;
  const f = S.segs, continu = S.mode === 'continu', v = { hors: +O.vHors, passage: Math.max(1, +O.vPassage), image: Math.max(.5, +O.vImage) };
  let imposes = null;
  if (O.accelere) { imposes = []; S.ps.forEach(q => (q.segments || []).forEach(s => imposes.push(s))); }
  const ecoute = O.avecSon ? S.ecoute : [], dans = m => f.some(w => m >= w[0] && m <= w[1]);
  const bornes = new Set();
  f.forEach(w => { bornes.add(w[0]); bornes.add(w[1] + 1); });
  (imposes || S.actifs.concat(ecoute)).forEach(a => { if (f.some(w => a[0] <= w[1] && a[1] >= w[0])) { bornes.add(a[0]); bornes.add(a[1] + 1); } });
  const b = [...bornes].sort((x, y) => x - y), l = [];
  for (let k = 0; k < b.length - 1; k++) {
    const m = Math.floor((b[k] + b[k + 1]) / 2), de = (q => m >= q[0] && m <= q[1]);
    let r;
    if (!dans(m)) { if (!continu) continue; r = v.hors; }
    else if (imposes) { const s = imposes.find(de); r = s ? Math.max(1, s[2]) : v.passage; }
    else r = ecoute.some(de) ? Math.min(1, v.image) : S.actifs.some(de) ? v.image : v.passage;
    const d = l[l.length - 1];
    if (d && d[2] === r && d[1] === b[k]) d[1] = b[k + 1]; else l.push([b[k], b[k + 1], r]);
  }
  S.pieces = l;
}
/** The pace at [tt]: the lowest of each stretch's plus 2 per second away — slowing down before a slower one, so none is jumped over. */
function allureA(tt) {
  let r = Infinity;
  (S.pieces || []).forEach(q => { const d = tt < q[0] ? q[0] - tt : tt >= q[1] ? tt - q[1] + 1 : 0; r = Math.min(r, q[2] + 2 * d / 1000); });
  return isFinite(r) ? r : +O.vPassage;
}
/** The sound heard here: the pass shown's recording, where the time runs in real time, the sound asked. */
function ecouteIci(tt) { return !!(O.avecSon && P && !S.trans && morceauA(tt) >= 0 && allureA(tt) <= 1.0001); }
function synchro() {
  const a = g('son'), i = morceauA(t);
  if (!joue || !ecouteIci(t)) { if (!a.paused) a.pause(); return; }
  const s = P.sons[i], pos = (t - s.o) / 1000, cle = P.id + '#' + i;
  if (morceauJoue !== cle) { morceauJoue = cle; a.src = url('/son', { id: P.id, n: s.n }); a.playbackRate = vitesse;
    a.addEventListener('loadedmetadata', () => { a.currentTime = (t - s.o) / 1000; a.play().catch(() => {}); }, { once: true }); return; }
  if (Math.abs(a.currentTime - pos) > 0.6) a.currentTime = pos;
  if (a.paused) a.play().catch(() => {});
}
/**
 * The time runs: at the paces, × the speed chosen; where it runs in real time with the sound, on the audio's clock.
 * Passes in a row: at a window's end a short turn to the next one; continuous: the time runs on between them.
 */
function boucle(now) {
  if (!joue || !S) return;
  const dt = Math.min(now - (dernier || now), 100); dernier = now;
  const fin = S.segs[S.segs.length - 1][1];
  if (S.mode !== 'continu' && S.trans) {
    S.trans -= dt; if (S.trans <= 0) { S.trans = 0; t = S.segs[S.i + 1][0]; passeA(S.i + 1); }
  } else {
    const a = g('son'), mi = morceauA(t);
    allureVue = allureA(t);
    if (ecouteIci(t) && morceauJoue === P.id + '#' + mi && !a.paused && a.readyState >= 2) t = P.sons[mi].o + a.currentTime * 1000;
    else t += dt * vitesse * allureVue;
    if (S.mode !== 'continu') {
      if (t >= S.segs[S.i][1] && S.i < S.segs.length - 1) { t = S.segs[S.i][1]; S.trans = O.transition * 1000; }
    } else if (indexA(t) !== S.i) passeA(indexA(t));
  }
  if (t >= fin && !S.trans) { t = fin; arrete(); return; }
  synchro();
  maj();
  requestAnimationFrame(boucle);
}
function bascule() {
  if (joue) { arrete(); return; }
  if (t >= S.segs[S.segs.length - 1][1]) { S.trans = 0; S.vues = 0; if (g('sGalerie')) g('sGalerie').innerHTML = ''; t = S.segs[0][0]; passeA(0); }
  joue = true; dernier = 0; synchro(); requestAnimationFrame(boucle); maj();
}
function arrete() { joue = false; const a = g('son'); if (a && !a.paused) a.pause(); if (S && P) maj(); }
function cherche(tt) {
  const lo = Math.min(S.segs[0][0], S.ps[0].t0), hi = Math.max(S.segs[S.segs.length - 1][1], S.ps[S.ps.length - 1].t1);
  t = Math.max(lo, Math.min(hi, tt)); S.trans = 0; S.vues = 0; if (g('sGalerie')) g('sGalerie').innerHTML = '';
  if (indexA(t) !== S.i) passeA(indexA(t));
  if (t < vue0 || t > vue1) { const l = vue1 - vue0; vue0 = t - l / 2; vue1 = vue0 + l; }
  if (joue) synchro(); maj();
}
let carteMs = 0;
function maj() {
  if (!S || !P || !g('ecran')) return;
  majHeureContact();
  majCiel(); majMoment(); dessineFrise(); majSstv();
  // The map, redrawn a few times a second during the replay (its tiles are pictures).
  if (O.vue === 'carte' && performance.now() - carteMs > 120) { carteMs = performance.now(); dessineCarte(); }
  g('lecture').textContent = joue ? '⏸ ' + T.pause : '▶ ' + T.lecture;
  g('position').textContent = (S.ps.length > 1 ? tf('s_passage', S.i + 1, S.ps.length) + ' · ' : '') +
    (t < P.de ? tf('s_aos_dans', mmss(P.de - t)) : t > P.a ? T.s_apres_los + ' + ' + mmss(t - P.a) : duree(t - P.de) + ' / ' + duree(P.a - P.de)) +
    (joue && !S.trans ? (allureVue <= 1.0001 ? '' : ' · ×' + Math.round(allureVue * vitesse)) : '');
  const s = g('selection'); s.innerHTML = '';
  if (sel) { s.appendChild(el('span', { text: tf('selection', hms(sel[0]), hms(sel[1]), duree(sel[1] - sel[0])) }));
    s.appendChild(el('button', { class: 'discret', text: T.effacer, onclick: () => { sel = null; deQuoi = 'tout'; maj(); if (onglet === 'fabriquer') panneau(); } })); }
  majSession();
}
document.addEventListener('keydown', ev => {
  if (!S || !P || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
  if (ev.code === 'Space') { ev.preventDefault(); bascule(); }
  else if (ev.key === 'ArrowLeft') { ev.preventDefault(); cherche(t - (ev.shiftKey ? 30000 : 5000)); }
  else if (ev.key === 'ArrowRight') { ev.preventDefault(); cherche(t + (ev.shiftKey ? 30000 : 5000)); }
});
window.addEventListener('resize', () => { if (S && P) { dessineFrise(); dessineCarte(); dessineCadre(); majSstv(); majElements(); maj(); } });

// ------------------------------------------------------------ several passes: chosen by satellite and dates
const jourLocal = tt => { const d = new Date(tt); return d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()); };
/** The session's form (satellite, dates), filled from [pre] (an SSTV event, a pass's days) or the last week of the satellite. */
function construitSession(pre) {
  arrete(); P = null; S = null; dessineListe();
  const v = g('vue'); v.innerHTML = '';
  const sats = [...new Set(passages.map(p => p.sat))].sort();
  const sat = el('select', { id: 'sSat' }, sats.map(s => el('option', { value: s, text: s })));
  sat.value = pre && pre.sat && sats.includes(pre.sat) ? pre.sat : (filtre || (sats.includes('ISS') ? 'ISS' : sats[0] || ''));
  const dernier = Math.max(0, ...passages.filter(p => p.sat === sat.value).map(p => p.de)) || Date.now();
  const de = el('input', { type: 'date', id: 'sDe', value: jourLocal(pre && pre.de ? pre.de : dernier - 6 * 86400000) });
  const a = el('input', { type: 'date', id: 'sA', value: jourLocal(pre && pre.a ? pre.a : dernier) });
  const img = el('input', { type: 'checkbox', id: 'sImg' }); img.checked = !(pre && pre.images === false);
  v.appendChild(el('div', { class: 'session-f' }, [el('b', { text: T.s_titre }), el('span', { class: 'aide', text: T.s_sat }), sat, el('span', { class: 'aide', text: T.s_de }), de,
    el('span', { class: 'aide', text: T.s_a }), a, el('label', { class: 'aide' }, [img, document.createTextNode(' ' + T.s_images)]),
    el('button', { class: 'primaire', text: T.s_animer, onclick: () => chargeSession() })]));
  v.appendChild(el('div', { id: 'sZone' }, [el('p', { class: 'aide', text: T.session_aide })]));
  if (pre && pre.lance) chargeSession();
}
/** The passes chosen, in the editor under the form. */
function chargeSession() {
  const sat = g('sSat').value, de = new Date(g('sDe').value + 'T00:00:00').getTime(), a = new Date(g('sA').value + 'T23:59:59').getTime();
  const l = passages.filter(p => p.sat === sat && p.de >= de && p.de <= a && (!g('sImg').checked || p.i > 0)).sort((x, y) => x.de - y.de).slice(0, 150);
  const z = g('sZone'); z.innerHTML = '';
  if (!l.length) { z.appendChild(el('p', { class: 'aide', text: T.s_aucun })); return; }
  return ouvreEditeur(l.map(p => p.id), { zone: z });
}
/* The companions chosen (the station's favourites), going round with the satellite. */
function compagnons() {
  const sat = S ? S.sat : null; if (!sat || !window.SATME_PC) return;
  const l = (FAV || []).filter(s => O.autres.includes(s.nom) && s.nom !== sat);
  Orbites.compagnons(l, O.tachesAutres);
}
function allerPassage(i) { cherche(S.segs[i][0]); }
/** A zoom's slider (the globe's size as the files have it: 1, 85 % of the view's height), its value beside it. */
function curseurZoom(cle, lib) {
  const v = el('b'), r = el('input', { type: 'range', min: .5, max: 3, step: .05 });
  const l = el('label', { class: 's-curseur' }, [el('span', { text: lib }), r, v]);
  l.montre = () => { r.value = O[cle]; v.textContent = '×' + (+O[cle]).toFixed(2); };
  r.oninput = () => { O[cle] = +r.value; garde(); l.montre(); };
  l.montre(); zoomsAffiches.push(l);
  return l;
}
let zoomsAffiches = [];
function montreZooms() { zoomsAffiches = zoomsAffiches.filter(z => document.body.contains(z)); zoomsAffiches.forEach(z => z.montre()); }
/**
 * How much of the pass's zoom at [tt] (1 in a pass, 0 far from it): by the seconds of play still to go to the nearest
 * pass, the speed slowing down as it comes (its speed plus 2 per second away), over 1.5 s — as the files work it out.
 */
function partPassage(tt, l) {
  let d = Infinity;
  (l || S.ps.map(q => [q.de, q.a])).forEach(q => { d = Math.min(d, tt < q[0] ? q[0] - tt : tt > q[1] ? tt - q[1] : 0); });
  if (!isFinite(d)) return 1;
  const vp = +O.vPassage, s = Math.log((vp + 2 * d / 1000) / vp) / 2;
  return Math.max(0, Math.min(1, 1 - s / 1.5));
}
/** The globe's size now: eased from the zoom outside to the pass's; going from one pass to the next (« passages »), out and back in. */
function zoomSession() {
  let k;
  if (S.mode !== 'continu' && S.trans) {
    // Turning to the next pass: out, then back in to where its window starts (far still when its margin is long).
    const f = Math.max(0, Math.min(1, 1 - S.trans / (O.transition * 1000))), n = S.segs[S.i + 1] ? S.i + 1 : S.i;
    k = (1 - Math.sin(Math.PI * f)) * partPassage(f < .5 ? S.segs[S.i][1] : S.segs[n][0]);
  } else k = partPassage(t);
  return zoomDe(k);
}
function zoomDe(k) { const dx = (1 - Math.cos(Math.PI * k)) / 2; return O.zHors * Math.pow(O.zPassage / O.zHors, dx); }
/* The wheel on the globe: the zoom of the moment (the pass's in one, the other outside). */
function moletteSession(sg) {
  if (!S) return;
  const cle = partPassage(t) >= .5 && !(S.mode !== 'continu' && S.trans) ? 'zPassage' : 'zHors';
  O[cle] = Math.max(.5, Math.min(3, Math.round(O[cle] * (1 - sg * .08) * 20) / 20)); garde();
  montreZooms();
}
/* Several passes' strip: the passes end to end (the hours between them left out), the pictures as ticks. */
function xSession(tt) { let x = 0; for (const q of S.segs) { if (tt <= q[1]) return (x + Math.max(0, tt - q[0])) / S.duree; x += q[1] - q[0]; } return 1; }
function chercheSession(f) {
  let reste = f * S.duree;
  for (let i = 0; i < S.segs.length; i++) { const q = S.segs[i], l = q[1] - q[0]; if (reste <= l || i === S.segs.length - 1) { cherche(q[0] + Math.min(l, reste)); return; } reste -= l; }
}
/** A speed's slider, from [min] to [max] on a log scale (×10 000 and ×2 both within reach), its value beside it. */
function curseurVitesse(cle, lib, min, max) {
  const lmin = Math.log(min), lmax = Math.log(max), v = el('b'), r = el('input', { type: 'range', min: 0, max: 1000, step: 1 });
  const montre = () => { const x = +O[cle]; v.textContent = '×' + (x < 10 ? x.toFixed(1).replace(/\.0$/, '') : Math.round(x)); };
  r.value = Math.round((Math.log(Math.max(min, Math.min(max, +O[cle]))) - lmin) / (lmax - lmin) * 1000);
  r.oninput = () => { const x = Math.exp(lmin + (lmax - lmin) * r.value / 1000); O[cle] = x < 10 ? Math.round(x * 2) / 2 : x < 100 ? Math.round(x) : Math.round(x / 10) * 10; garde(); montre(); piecesAllure(); };
  montre();
  return el('label', { class: 's-curseur' }, [el('span', { text: lib }), r, v]);
}
function curseurPause() {
  const v = el('b'), r = el('input', { type: 'range', min: .5, max: 6, step: .1, value: O.transition });
  const montre = () => { v.textContent = (+O.transition).toFixed(1) + ' s'; };
  r.oninput = () => { O.transition = +r.value; garde(); montre(); }; montre();
  return el('label', { class: 's-curseur' }, [el('span', { text: T.s_entre_pause }), r, v]);
}
/**
 * The files' cards, here too: the opening one before the start (« Ouvrir sur un titre »: the pass's, or the passes'),
 * the next pass's while turning to it (passes in a row), the pictures once it is over (« Finir sur un bilan »).
 */
function carteSession() {
  const c = g('sCarte'); if (!c) return;
  c.style.setProperty('--police', O.police);
  const debut = S.segs[0][0], fin = S.segs[S.segs.length - 1][1], q0 = S.ps[0], qn = S.ps[S.ps.length - 1], seul = S.ps.length === 1;
  const jourSeul = tt => jour(tt).replace(/ \d\d:\d\dZ?$/, '');
  let cle = '', kids = null, mosaique = false;
  if (O.ouverture && !joue && t <= debut) {
    cle = 'o|' + S.titre + '|' + O.locator;
    kids = [el('b', { text: S.titre || (seul ? q0.sat : 'Session SSTV · ' + S.sat) }), S.titre ? el('div', { class: 'sat', text: S.sat }) : null,
      el('div', { text: seul ? jour(q0.de) + ' · ' + tf('duree', duree(q0.a - q0.de), q0.el) : jourSeul(q0.de) + ' → ' + jourSeul(qn.a) }),
      seul ? null : el('div', { class: 'sstv', text: tf('s_resume', S.ps.length, S.images.length) }),
      O.locator ? el('div', { class: 'sat', text: [q0.indicatif, q0.loc].filter(v => v).join(' · ') }) : null].filter(x => x);
  } else if (S.mode !== 'continu' && S.trans && S.ps[S.i + 1]) {
    const q = S.ps[S.i + 1], haut = (q.pts || []).reduce((m, x) => x[2] > m ? x[2] : m, 0);
    cle = 't|' + S.i;
    kids = [el('b', { text: tf('s_passage', S.i + 2, S.ps.length) }), el('div', { text: jour(q.de) + ' · ' + Math.round(haut) + '°' })];
  } else if (O.recap && !joue && t >= fin && S.images.length) {
    cle = 'm|' + S.images.length; mosaique = true;
    kids = [el('b', { text: S.titre || (seul ? q0.sat : 'Session SSTV · ' + S.sat) }), el('div', { class: 'grille' }, S.images.map(m =>
      el('figure', {}, [el('img', { src: url('/image', { f: m.f, id: m.pid, n: tourVersion }), alt: '' }), el('figcaption', { text: hms(m.a).slice(0, 5) })])))];
  }
  if (c.dataset.cle === cle) return;
  c.dataset.cle = cle; c.innerHTML = ''; c.className = mosaique ? 'mosaique' : '';
  c.style.display = kids ? 'block' : 'none';
  if (kids) kids.forEach(k => c.appendChild(k));
  if (mosaique) {
    // The pictures as large as they can all be (4:3, their time under each), as the files' mosaic.
    const e = g('ecran'), W = e.clientWidth * .86 - 44, H = e.clientHeight * .86 - 70, n = S.images.length;
    let l = 40;
    for (let k = 1; k <= n; k++) { const r = Math.ceil(n / k), x = Math.min((W - 6 * (k - 1)) / k, ((H - r * 20) / r) * 4 / 3); if (x > l) l = x; }
    c.style.setProperty('--l', Math.floor(l) + 'px');
  }
}
/* The size of the files' texts (the caption band's times, the cards): a slider, its value beside it. */
function curseurPolice(apres) {
  const v = el('b', { text: '×' + (+O.police).toFixed(1) });
  const r = el('input', { type: 'range', min: .6, max: 2.5, step: .1, value: O.police, oninput: ev => { O.police = +ev.target.value; v.textContent = '×' + O.police.toFixed(1); garde(); if (apres) apres(); } });
  return el('label', { class: 'rangee s-curseur' }, [el('span', { text: T.police }), r, v]);
}
/** A margin's slider (0 to 30 min, by half minutes then minutes), its value beside it. */
function curseurMarge(cle, lib) {
  const pas = [0, .25, .5, 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30], v = el('b');
  const r = el('input', { type: 'range', min: 0, max: pas.length - 1, step: 1 });
  const montre = () => { const x = +O[cle] || 0; v.textContent = x < 1 ? Math.round(x * 60) + ' s' : String(x).replace('.', document.documentElement.lang === 'en' ? '.' : ',') + ' min'; };
  r.value = pas.reduce((b, x, i) => Math.abs(x - O[cle]) < Math.abs(pas[b] - O[cle]) ? i : b, 0);
  r.oninput = () => { O[cle] = pas[+r.value]; garde(); montre(); margesChangees(); };
  montre();
  return el('label', { class: 's-curseur' }, [el('span', { text: lib }), r, v]);
}
function titreBloc(texte) { return el('div', { class: 'aide titre-bloc', text: texte }); }
function rafraichit() { if (S) { piecesAllure(); dessineCadre(); maj(); } }
/**
 * The options, the same for one pass and for several: the margins before the AOS and after the LOS, how the passes
 * follow each other, the paces, the zooms, the companions, what shows, the texts' size, the accelerated replay.
 */
function panneauOptions(p) {
  const pc = !!window.SATME_PC, seul = S.ps.length === 1;
  p.appendChild(titreBloc(T.marges));
  p.appendChild(el('div', { class: 'commandes' }, [curseurMarge('mAvant', T.marge_avant), curseurMarge('mApres', T.marge_apres)]));
  // Three paces (outside the passes: continuous time only; passes in a row: the pause between them), in a pass, on a picture or a contact.
  p.appendChild(titreBloc(T.vitesses));
  const v = el('div', { class: 'commandes' });
  const vHors = curseurVitesse('vHors', T.s_v_hors, 10, 20000), pause = curseurPause();
  const montre = () => { vHors.style.display = S.mode === 'continu' ? '' : 'none'; pause.style.display = S.mode === 'continu' ? 'none' : ''; };
  const mode = el('select', { onchange: ev => { S.mode = O.mode = ev.target.value; garde(); S.trans = 0; montre(); fenetresSession(); maj(); } },
    [['passages', T.s_mode_passages], ['continu', T.s_mode_continu]].map(c => el('option', { value: c[0], text: c[1] })));
  mode.value = S.mode;
  v.appendChild(el('label', { class: 's-curseur' }, [el('span', { text: T.s_mode }), mode])); v.appendChild(vHors); v.appendChild(pause); montre();
  v.appendChild(curseurVitesse('vPassage', T.s_v_passage, 1, 500)); v.appendChild(curseurVitesse('vImage', T.s_v_image, .5, 100));
  p.appendChild(v);
  // One pass: nothing to follow on — said rather than hidden, the editor the same.
  if (seul) p.appendChild(el('div', { class: 'aide', text: T.s_mode_seul }));
  p.appendChild(el('div', { class: 'aide', text: T.vitesses_aide }));
  if (pc) {
    // On the globe: two zooms, from afar outside the passes (a long margin: the satellite seen coming), near from the AOS to the LOS.
    p.appendChild(titreBloc(T.vue_globe));
    p.appendChild(el('div', { class: 'commandes', title: T.s_zoom_aide }, [curseurZoom('zHors', T.s_zoom_hors), curseurZoom('zPassage', T.s_zoom_passage)]));
    // More satellites going round: the station's favourites (all of them when it has none).
    const favs = (FAV || []).filter(s => s.nom !== S.sat), l = favs.some(s => s.fav) ? favs.filter(s => s.fav) : favs;
    if (l.length) {
      const compte = () => T.s_autres + (O.autres.length ? ' (' + O.autres.filter(n => l.some(s => s.nom === n)).length + ')' : '');
      const d = el('details', { class: 's-autres' }, [el('summary', { text: compte() }), el('div', { class: 'aide', text: T.s_autres_aide })]);
      const cases = el('div', { class: 'colonnes' });
      l.forEach(s => {
        const k = el('input', { type: 'checkbox' }); k.checked = O.autres.includes(s.nom);
        k.onchange = () => { O.autres = O.autres.filter(n => n !== s.nom).concat(k.checked ? [s.nom] : []); garde(); compagnons(); d.firstChild.textContent = compte(); };
        cases.appendChild(el('label', {}, [k, document.createTextNode(' ' + s.nom)]));
      });
      d.appendChild(cases);
      d.appendChild(caseOpt('tachesAutres', T.s_taches, compagnons));
      p.appendChild(d);
    }
  }
  p.appendChild(titreBloc(T.afficher));
  const c = el('div', { class: 'colonnes' });
  [['sstv', T.aff_sstv, chargeSegments], ['fiches', T.aff_fiche], ['smetre', T.aff_smetre], ['freq', T.aff_freq], ['locator', T.aff_locator, dessineCarte]]
    .concat(pc ? [['vignettes', T.s_vignettes]] : []).forEach(q => c.appendChild(caseOpt(q[0], q[1], () => { if (q[2]) q[2](); rafraichit(); })));
  p.appendChild(c);
  p.appendChild(curseurPolice(rafraichit));
  // The accelerated replay, quick and silent where nothing was logged nor heard: the page and the files.
  p.appendChild(titreBloc(T.accel_titre));
  const a = el('div', { class: 'colonnes' });
  a.appendChild(caseOpt('accelere', T.accelere, () => { const x = g('accelere'); if (x) x.checked = O.accelere; piecesAllure(); if (joue) synchro(); }));
  const r = el('select', { onchange: ev => { O.rapide = +ev.target.value; garde(); chargeSegments(); } }, [5, 10, 20, 50].map(x => el('option', { value: x, text: '×' + x })));
  r.value = String(O.rapide); a.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.vitesse }), r]));
  a.appendChild(nombreOpt('avant', T.avant, 0, 60, chargeSegments));
  a.appendChild(nombreOpt('apres', T.apres, 0, 120, chargeSegments));
  a.appendChild(caseOpt('surActivite', T.activite_opt, chargeSegments));
  p.appendChild(a);
  if (S.images.length) p.appendChild(el('p', { class: 'aide', text: T.sstv_cadre + ' — ' + T.sstv_cadre_aide }));
  p.appendChild(el('p', { class: 'aide', text: T.options_aide }));
}
/**
 * The files, the same for one pass and for several, made as the page plays them: the video, a GIF, the moment as a
 * picture, the sound, the pass file; the whole or the stretch chosen; the sky, the map (each pass in turn) or the globe;
 * 16:9 or a phone's 9:16; the SSTV picture and the report's panels where they were placed here.
 */
let deQuoi = 'tout';
function panneauExports(p) {
  const pc = !!window.SATME_PC, n = S.ps.length;
  const radio = (nom, val, lib, coche, sur) => { const r = el('input', { type: 'radio', name: nom, value: val }); r.checked = coche; r.addEventListener('change', sur); return el('label', {}, [r, document.createTextNode(' ' + lib)]); };
  // The stretch's times and length beside its choice, so a tiny one is seen before making it.
  const libSel = sel ? T.de_quoi_sel + ' (' + hms(sel[0]) + ' → ' + hms(sel[1]) + ', ' + duree(sel[1] - sel[0]) + ')' : T.de_quoi_sel;
  p.appendChild(el('div', { class: 'rangee' }, [radio('quoi', 'sel', libSel, deQuoi === 'sel', () => { deQuoi = 'sel'; panneau(); }),
    radio('quoi', 'tout', n > 1 ? tf('s_resume', n, S.images.length) : T.de_quoi_tout, deQuoi === 'tout', () => { deQuoi = 'tout'; panneau(); })]));
  const ti = el('input', { placeholder: n > 1 ? T.s_video_titre : T.titre_extrait, style: 'width:100%', value: S.titre || '' });
  ti.addEventListener('input', () => { S.titre = ti.value; carteSession(); });
  p.appendChild(ti);
  const c = el('div', { class: 'colonnes' });
  const res = el('select', { onchange: ev => { O.res = ev.target.value; garde(); } }, [['XS', T.res_xs], ['M', T.res_m], ['HD', T.res_hd]].map(([v, l]) => el('option', { value: v, text: l })));
  res.value = O.res;
  c.appendChild(el('label', { class: 'rangee' }, [el('span', { text: T.resolution }), res]));
  c.appendChild(el('div', { class: 'rangee' }, [el('span', { class: 'aide', text: T.vue }),
    radio('vueF', 'ciel', T.vue_ciel, O.vue === 'ciel', () => { montreVue('ciel'); panneau(); }), radio('vueF', 'carte', T.vue_carte, O.vue === 'carte', () => { montreVue('carte'); panneau(); })].concat(
    pc ? [radio('vueF', 'globe', T.vue_globe, O.vue === 'globe', () => { montreVue('globe'); panneau(); })] : [])));
  // On the PC: the file's format, its frame drawn over the replay (the phone's own files keep its 3:4).
  if (pc) c.appendChild(el('div', { class: 'rangee' }, [el('span', { class: 'aide', text: T.format }),
    radio('formatF', 'paysage', T.format_paysage, O.format !== 'telephone', () => { O.format = 'paysage'; garde(); dessineCadre(); majSstv(); majElements(); }),
    radio('formatF', 'telephone', T.format_portrait, O.format === 'telephone', () => { O.format = 'telephone'; garde(); dessineCadre(); majSstv(); majElements(); })]));
  c.appendChild(caseOpt('ouverture', T.ouverture, rafraichit)); c.appendChild(caseOpt('recap', T.recap, rafraichit));
  // The sound: where the time runs in real time (a contact heard, the accelerated replay's normal stretches), here and in the video.
  c.appendChild(el('div', { title: T.s_son_aide }, [caseOpt('avecSon', T.avec_son, () => { piecesAllure(); if (joue) synchro(); })]));
  c.appendChild(caseOpt('accelere', T.accelere_video, () => { const x = g('accelere'); if (x) x.checked = O.accelere; piecesAllure(); if (joue) synchro(); }));
  c.appendChild(caseOpt('paquetQsos', T.paquet_qsos));
  p.appendChild(c);
  p.appendChild(el('div', { class: 'rangee' }, [el('button', { class: 'primaire', text: n > 1 ? T.s_video : T.video, onclick: () => fabrique('video') }),
    el('button', { class: 'discret', text: T.gif, onclick: () => fabrique('gif') }), el('button', { class: 'discret', text: T.image, onclick: () => fabrique('image') }),
    el('button', { class: 'discret', text: T.son, onclick: () => fabrique('son') }), el('button', { class: 'discret', text: T.paquet, onclick: () => fabrique('paquet') })]));
  if (pc) p.appendChild(el('div', { class: 'aide', text: T.exp_unique }));
  if (n > 1) p.appendChild(el('div', { class: 'aide', text: T.exp_plusieurs }));
  p.appendChild(el('div', { class: 'aide', text: T.s_son_aide }));
  p.appendChild(el('div', { id: 'fabrication' }));
  // The pass shown's SSTV pictures and report panels (each pass keeps its own).
  if (n > 1) p.appendChild(titreBloc(tf('s_passage', S.i + 1, n) + ' · ' + jour(P.de)));
  p.appendChild(el('div', { id: 'blocSstv' })); dessineSstvChoix();
}
/** The editor's words for the station, the same for one pass or several. */
function optionsSession(sat) {
  const b = v => v ? '1' : '0', [ma, mp] = marges();
  return { mode: S ? S.mode : 'passages', vitesse: O.vPassage, vHors: O.vHors, vPassage: O.vPassage, vImage: O.vImage, vignettes: b(O.vignettes),
    zHors: O.zHors, zPassage: O.zPassage, autres: O.autres.filter(n => n !== sat).join(','), tachesAutres: b(O.tachesAutres), avecSon: b(O.avecSon),
    entre: O.entre, transition: O.transition, margeAvant: ma, margeApres: mp };
}
/** What the page shows, made by the station: the same engine for one pass or several (the phone's own files: [fabriqueTelephone]). */
async function fabrique(quoi) {
  if (!window.SATME_PC) return fabriqueTelephone(quoi);
  const b = v => v ? '1' : '0';
  const q = Object.assign({ quoi: quoi, t: Math.round(t), ids: S.ids.join(','), titre: S.titre || '', police: O.police, utc: b(utc),
    res: O.res, format: O.format === 'telephone' ? 'telephone' : 'paysage', vue: O.vue, sstv: b(O.sstv), fiches: b(O.fiches), smetre: b(O.smetre), freq: b(O.freq),
    locator: b(O.locator), ouverture: b(O.ouverture), recap: b(O.recap), cadreSstv: texteCadre(cadreSstv), style: Orbites.styleActif(), villes: Orbites.villesActives(),
    etiquettes: Orbites.etiquettesActives(), detail: Orbites.detailActif(), accelere: b(O.accelere), rapide: O.rapide, accAvant: O.avant, accApres: O.apres,
    surActivite: b(O.surActivite), contacts: b(O.paquetQsos) }, optionsSession(S.sat), Orbites.cadrage());
  if (deQuoi === 'sel' && sel && quoi !== 'image' && quoi !== 'paquet') { q.de = Math.round(sel[0]); q.a = Math.round(sel[1]); }
  // The map as framed here, in the file's frame: its view's centre and width — for the pass it shows, the others framed to themselves.
  if (O.vue === 'carte') { const c = g('carte'), f = cadreFichier(); q.mcx = mcx; q.mcy = mcy; q.zc = zc; q.cw = c.clientWidth; q.ch = c.clientHeight; q.carteId = P.id;
    if (f) { const s = 256 * Math.pow(2, zc); q.mcx = mcx + (f.fx + f.fw / 2 - f.W / 2) / s; q.mcy = mcy + (f.fy + f.vh / 2 - f.H / 2) / s; q.cw = f.fw; q.ch = f.vh; } }
  const zone = g('fabrication'); zone.innerHTML = '';
  const barre = el('progress', { max: 100, value: 0 }), mot = el('div', { class: 'aide', text: tf('en_cours', 0) });
  zone.appendChild(mot); zone.appendChild(barre);
  const j = await lire('/fabrique-session', q).catch(() => ({ ok: false }));
  if (!j.ok) { mot.textContent = j.raison === 'ffmpeg' ? T.s_sans_ffmpeg : T.echec; return; }
  await suitTravail(j, zone, barre, mot);
  if (quoi === 'son' && zone.textContent === T.echec) zone.firstChild.textContent = T.s_sans_son;
}
/* Several passes' strip, the pictures as they come, the cards: drawn with each moment. */
function majSession() {
  carteSession();
  document.querySelectorAll('#panneau .mo.pa').forEach(r => r.classList.toggle('actif', +r.dataset.i === S.i));
  // The pictures as they come, in the strip under the timeline.
  const gal = g('sGalerie');
  if (gal) {
    const recues = S.images.filter(m => m.a <= t);
    gal.style.display = O.vignettes && S.images.length ? 'flex' : 'none';
    if (S.vues > recues.length) { gal.innerHTML = ''; S.vues = 0; }
    while (S.vues < recues.length) { const m = recues[S.vues++];
      gal.appendChild(el('figure', {}, [el('img', { src: url('/image', { f: m.f, id: m.pid, n: tourVersion }), alt: '', loading: 'lazy' }), el('figcaption', { text: hms(m.a) })])); gal.scrollLeft = gal.scrollWidth; }
  }
  const c = g('sFrise'); if (!c) return;
  const w = c.clientWidth, h = c.clientHeight, d = Math.min(2, devicePixelRatio || 1);
  if (c.width !== Math.round(w * d)) { c.width = Math.round(w * d); c.height = Math.round(h * d); }
  const x = c.getContext('2d'); x.setTransform(d, 0, 0, d, 0, 0); x.clearRect(0, 0, w, h);
  let x0 = 0;
  S.segs.forEach((q, i) => { const l = (q[1] - q[0]) / S.duree * w; x.fillStyle = i === S.i ? 'rgba(63,224,200,.35)' : 'rgba(154,167,180,.18)'; x.fillRect(x0 + 1, 8, Math.max(1, l - 2), h - 16); x0 += l; });
  x.fillStyle = COULHEX.SSTV; S.images.forEach(m => x.fillRect(xSession(m.a) * w - 1, 4, 3, h - 8));
  x.fillStyle = '#fff'; x.fillRect(xSession(t) * w - 1, 0, 2, h);
}

// ------------------------------------------------------------ making on the phone
// A size in the page's language: « 12,3 Mo », « 12.3 MB ».
function taille(o) { const en = document.documentElement.lang === 'en'; return (o / 1048576).toFixed(1).replace('.', en ? '.' : ',') + (en ? ' MB' : ' Mo'); }
/** The phone's own files (its journal page): its words, one pass. */
async function fabriqueTelephone(quoi) {
  const b = v => v ? '1' : '0';
  const p = { id: P.id, quoi: quoi, carte: O.vue === 'carte' ? '1' : '0', vue: O.vue, t: Math.round(t), police: O.police, utc: b(utc), res: O.res, ouverture: b(O.ouverture), recap: b(O.recap),
    avecSon: b(O.avecSon), accelere: b(O.accelere), avant: O.avant, apres: O.apres, rapide: O.rapide, surActivite: b(O.surActivite),
    sstv: b(O.sstv), fiches: b(O.fiches), smetre: b(O.smetre), freq: b(O.freq), locator: b(O.locator) };
  // The box as it is here (ticked or not), whatever the station's setting.
  if (quoi === 'paquet') p.contacts = b(O.paquetQsos);
  if (deQuoi === 'sel' && sel && quoi !== 'image' && quoi !== 'paquet') { p.de = Math.round(sel[0]); p.a = Math.round(sel[1]); p.titre = S.titre || ''; }
  // The map as framed here (centre, zoom, the width it is shown at): the export shows the same.
  if (O.vue === 'carte') { const c = g('carte'); p.mcx = mcx; p.mcy = mcy; p.zc = zc; p.cw = c.clientWidth; p.ch = c.clientHeight; }
  // The SSTV picture where it shows here (or "auto").
  p.cadreSstv = texteCadre(cadreSstv);
  const zone = g('fabrication'); zone.innerHTML = '';
  const barre = el('progress', { max: 100, value: 0 }), mot = el('div', { class: 'aide', text: tf('en_cours', 0) });
  zone.appendChild(mot); zone.appendChild(barre);
  const j = await lire('/fabrique', p); if (!j.ok) { mot.textContent = T.echec; return; }
  await suitTravail(j, zone, barre, mot);
}
/** A file being made on the station: its progress, then its link and a look at it. */
async function suitTravail(j, zone, barre, mot) {
  while (true) {
    await new Promise(r => setTimeout(r, 700));
    const e = await lire('/travail', { n: j.travail });
    barre.value = Math.round(e.progres * 100); mot.textContent = tf('en_cours', Math.round(e.progres * 100));
    if (!e.fini) continue;
    zone.innerHTML = '';
    if (!e.ok) { zone.appendChild(el('div', { class: 'aide', text: T.echec })); return; }
    const lien = url('/fichier', { n: j.travail });
    zone.appendChild(el('div', { class: 'rangee' }, [el('span', { text: tf('pret', e.nom, taille(e.taille)) }),
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
g('bNouveau').textContent = T.n_bouton; g('bNouveau').onclick = () => nouveau().catch(() => {});
// A session of several passes on the globe (SatMe-PC).
if (window.SATME_PC) { const b = el('button', { class: 'discret', text: '🌍 ' + T.session, title: T.session_aide, onclick: () => construitSession(null) }); g('bNouveau').after(b); }
g('code').addEventListener('keydown', ev => { if (ev.key === 'Enter') entrer(); });
g('filtre').onchange = ev => { filtre = ev.target.value; dessineListe(); };
if (cle) demarre().catch(() => {}); else porte();
</script>
</body>
</html>
"""
}
