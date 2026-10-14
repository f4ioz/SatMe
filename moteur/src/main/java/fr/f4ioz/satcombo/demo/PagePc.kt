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
 * The PC station's page: passes on the left, the satellite followed in the
 * middle (sky, position, countdown), frequencies, rig and mast on the right.
 * Three columns on a large screen, one on a narrow one. No library: a page
 * that works without the internet, at the foot of the mast.
 */
object PagePc {
    /**
     * The tabs, in their order, each with its icon — the phone's for the same
     * function (Explore for the station and its aiming, MenuBook for the log,
     * SatelliteAlt for QO-100…). The journal is a page of its own.
     */
    private val ONGLETS = listOf(
        Triple("station", "Station", "explore"), Triple("carnet", "Carnet", "menu_book"), Triple("agenda", "Agenda", "calendar_month"),
        Triple("carres", "Carrés", "grid_on"), Triple("frise", "Frise", "timeline"), Triple("sked", "Sked", "handshake"),
        Triple("qo100", "QO-100", "satellite_alt"), Triple("datv", "DATV", "tv"), Triple("sdr", "SDR", "settings_input_antenna"),
        Triple("sondes", "Sondes", "air"), Triple("meteor", "METEOR", "cloud"), Triple("son", "Son", "mic"),
        Triple("sstv", "SSTV", "live_tv"), Triple("planche", "Planche", "view_quilt"), Triple("aprs", "APRS", "place"), Triple("ft8", "FT8", "graphic_eq"),
        Triple("journal", "Journal", "history"), Triple("reglages", "Réglages", "settings"))

    /** Every tab's id: what a server offering all of them answers to `api/capacites`. */
    val TOUS: List<String> get() = ONGLETS.map { it.first }

    /** A tab's content: its icon above its name. */
    private fun contenu(nom: String, icone: String) = IconesPc.svg(icone) + "<span>$nom</span>"

    /** The settings categories' icons, for the page's script. */
    private fun icones(): String = org.json.JSONObject(listOf("person", "radio", "satellite_alt", "public", "smartphone", "save", "tune", "straighten")
        .associateWith { IconesPc.svg(it, "ico-reg") }).toString()

    /** The tabs that are pages of their own (served apart), with what they are. */
    private val PAGES = mapOf("journal" to "Les passages enregistrés, rejoués", "planche" to "La planche SSTV : les images sur un modèle, à télécharger")

    /** The tabs here: buttons, the pages of their own links. */
    private fun boutons() = ONGLETS.joinToString("") { (v, nom, ico) ->
        if (v in PAGES) "<a href=\"$v\" style=\"text-decoration:none\"><button title=\"${PAGES[v]}\">${contenu(nom, ico)}</button></a>"
        else "<button data-v=\"$v\"" + (if (v == "station") " class=\"actif\"" else "") + ">${contenu(nom, ico)}</button>"
    }

    /** The tabs' look, an icon above the name: shared by this page and the journal's header. */
    private const val ONGLET_STYLE = "nav button{display:inline-flex;flex-direction:column;align-items:center;gap:1px;line-height:1.1}" +
        "nav button .ico{width:20px;height:20px;fill:currentColor;opacity:.85}nav button.actif .ico{opacity:1}" +
        "nav button span{font-size:11.5px}nav button:hover{color:var(--clair)}"

    /**
     * The same header at the top of the journal's page, a page of its own:
     * the same title, station, tabs and clock, the same look (its styles
     * scoped under #pcBar, the journal's own left alone). The tabs are links
     * back to this page, opened on the tab (#carnet).
     */
    fun barre(actif: String): String = "<header id=\"pcBar\"><h1>SatMe-<span class=\"c\">PC</span></h1><span class=\"g\" id=\"pc-info\"></span><nav>" +
        ONGLETS.joinToString("") { (v, nom, ico) ->
            when {
                v == actif -> "<button class=\"actif\">${contenu(nom, ico)}</button>"
                v in PAGES -> "<a href=\"$v\"><button>${contenu(nom, ico)}</button></a>"
                else -> "<a href=\"pc#$v\"><button>${contenu(nom, ico)}</button></a>"
            }
        } + "</nav><span id=\"pc-heure\"></span></header>"

    /** The header's look, as on this page (sizes, gaps, the narrower screens). */
    val STYLE_BARRE = "#tTitre,#lienPlanche,#lienPupitre,#tSous{display:none}" +
        "#pcBar{display:flex;align-items:center;gap:16px;flex-wrap:wrap;padding:10px 16px;border-bottom:1px solid var(--bord);font-size:14px}" +
        "#pcBar h1{font-size:18px;margin:0;letter-spacing:.5px}#pcBar h1 .c{color:var(--cyan)}#pcBar .g{color:var(--gris);font-size:13px}" +
        "#pc-heure{font-family:ui-monospace,Consolas,monospace;font-size:16px;margin-left:auto}" +
        "#pcBar nav{display:flex;gap:4px}#pcBar nav a{text-decoration:none}" +
        "#pcBar nav button{font-size:14px;font-family:inherit;font-weight:700;border-radius:8px;background:transparent;border:1px solid transparent;color:var(--gris);padding:6px 14px;cursor:pointer}" +
        ONGLET_STYLE.replace("nav ", "#pcBar nav ") +
        "#pcBar nav button.actif{color:var(--cyan);border-color:var(--bord);background:var(--carte)}" +
        "@media (max-width:1900px){#pcBar nav button{padding:5px 8px;font-size:13px}#pcBar{gap:10px}#pcBar nav{gap:2px}}" +
        "@media (max-width:1500px){#pcBar nav button{padding:4px 6px;font-size:12.5px}#pcBar h1{font-size:16px}#pc-info{display:none}}" +
        // The journal's own bar below, only its filter now: lighter.
        "#page>header:not(#pcBar){padding:5px 16px;border-bottom:none}"

    /** Fills the header: the station from /api/etat, the clock each second (UTC as chosen on the station page). */
    const val SCRIPT_BARRE = "(function(){let E=null;const p2=n=>String(n).padStart(2,'0');" +
        "function utc(){try{return localStorage.getItem('utc')==='1'}catch(x){return false}}" +
        "async function etat(){try{E=await(await fetch('api/etat')).json();const i=document.getElementById('pc-info');" +
        "if(i)i.textContent=[E.indicatif,E.locator].filter(x=>x).join(' · ')+' · '+E.nbSatellites+' satellites'}catch(x){}}" +
        "function heure(){const d=new Date(),u=utc(),h=document.getElementById('pc-heure');if(!h)return;" +
        "h.textContent=(u?[d.getUTCHours(),d.getUTCMinutes(),d.getUTCSeconds()]:[d.getHours(),d.getMinutes(),d.getSeconds()]).map(p2).join(':')+(u?' UTC':'')}" +
        "etat();setInterval(etat,30000);heure();setInterval(heure,1000);" +
        // The tabs the server offers (the phone some of them): the others hidden.
        "fetch('api/capacites').then(r=>r.ok?r.json():null).then(j=>{if(!j||!j.onglets)return;" +
        "document.querySelectorAll('#pcBar nav a').forEach(a=>{const h=a.getAttribute('href').replace('pc#','');if(!j.onglets.includes(h))a.hidden=true})}).catch(()=>{})" +
        "})();"

    val HTML = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SatMe — station PC</title>
<style>
  :root { --fond:#0B1016; --carte:#131C24; --bord:#22303B; --cyan:#3FE0C8;
          --ambre:#FFB454; --rose:#E5484D; --vert:#5BD68A; --gris:#9AA7B4; --clair:#E8F0F7; }
  * { box-sizing:border-box; }
  [hidden] { display:none !important; }
  #visionneuse { background:var(--carte); color:var(--clair); border:1px solid var(--bord); border-radius:12px; padding:10px; max-width:96vw; }
  #c-liste td.act button { padding:3px 5px; }
  #c-liste .c-nm { max-width:96px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:var(--gris); font-size:11px; font-weight:400; }
  #c-liste td.act { white-space:nowrap; }
  .gp-ct { color:var(--gris); font-size:11px; }
  .d-grille { display:grid; grid-template-columns:minmax(0,1fr) 430px; gap:12px; padding:12px 16px; }
  @media (max-width:1100px) { .d-grille { grid-template-columns:1fr; } }
  #d-cadre { position:relative; aspect-ratio:16/9; max-height:calc(100vh - 230px); background:#000; border-radius:8px; overflow:hidden; display:flex; align-items:center; justify-content:center; }
  #d-video { max-width:100%; max-height:100%; display:none; }
  #d-vide { position:absolute; color:var(--gris); font-size:15px; }
  #t-qr svg { width:100%; height:100%; display:block; border-radius:8px; }
  #visionneuse::backdrop { background:rgba(0,0,0,.75); }
  #vi-img { display:block; max-width:min(92vw,1280px); max-height:calc(92vh - 60px); min-width:320px; image-rendering:auto; border-radius:6px; cursor:zoom-out; background:#0E1720; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:14px;
         font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
  header { display:flex; align-items:center; gap:16px; flex-wrap:wrap; padding:10px 16px;
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
  .reg { display:flex; gap:16px; align-items:flex-start; padding:14px 16px; }
  .reg-nav { flex:0 0 250px; display:flex; flex-direction:column; gap:6px; position:sticky; top:0; }
  #v-reglages .reg-nav button { display:flex; flex-direction:row; justify-content:flex-start; width:100%; gap:12px; align-items:center; text-align:left; padding:10px 12px; background:var(--carte); border:1px solid var(--bord); border-radius:12px; }
  #v-reglages .reg-nav button svg { margin:0; width:26px; height:26px; flex:none; fill:var(--gris); }
  .reg-nav button b { display:block; font-size:14px; }
  .reg-nav button small { display:block; font-size:11px; color:var(--gris); font-weight:400; margin-top:2px; }
  .reg-nav button.actif { border-color:var(--cyan); background:rgba(63,224,200,.08); }
  .reg-nav button.actif svg { fill:var(--cyan); }
  .reg-corps { flex:1; min-width:0; }
  .reg-tete { display:flex; gap:12px; align-items:center; margin:2px 0 12px; }
  .reg-tete svg { width:34px; height:34px; fill:var(--cyan); flex:none; }
  .reg-nom { font-size:22px; font-weight:800; }
  .reg-cartes { display:grid; grid-template-columns:repeat(auto-fit, minmax(440px, 1fr)); gap:12px; align-items:start; }
  .reg-cartes .carte { margin:0; }
  @media (max-width:900px) { .reg { flex-direction:column; } .reg-nav { flex:none; width:100%; position:static; flex-direction:row; flex-wrap:wrap; } .reg-nav button small { display:none; } .reg-cartes { grid-template-columns:1fr; } }
  a.bouton { font-size:13px; font-weight:700; border-radius:8px; border:1px solid var(--bord); background:#0E1720; color:var(--clair); padding:6px 9px; text-decoration:none; white-space:nowrap; }
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
  td.rosace { padding:2px 4px; width:1px; }
  .grille3c { display:grid; grid-template-columns:minmax(320px,0.9fr) minmax(360px,1fr) minmax(420px,1.3fr); gap:12px; padding:12px 16px; }
  @media (max-width:1250px) { .grille3c { grid-template-columns:1fr 1fr; } }
  @media (max-width:800px) { .grille3c { grid-template-columns:1fr; } }
  .bascule-c { display:flex; gap:4px; }
  .bascule-c button { padding:4px 10px; font-size:12px; }
  .bascule-c button.actif { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .cr-avance { position:relative; height:34px; margin-top:8px; background:#0E1720; border-radius:6px; overflow:hidden; }
  .cr-profil { position:absolute; inset:0; }
  .cr-avance i { position:absolute; top:0; bottom:0; width:2px; background:#FFB454; }
  .cr-corps { display:flex; gap:14px; margin-top:8px; flex-wrap:wrap; }
  .cr-ciel { width:min(100%, 280px); flex:none; }
  .cr-infos { flex:1; min-width:200px; }
  .amsat-boite { display:flex; align-items:center; gap:4px 6px; flex-wrap:wrap; justify-content:flex-end; margin-left:auto; max-width:230px;
    padding:4px 8px; border:1px solid rgba(234,3,3,.6); border-radius:10px; background:rgba(234,3,3,.06); font-size:12px; }
  .amsat-boite .amsat-logo { width:18px; height:18px; flex:none; background:#fff; border-radius:50%; padding:1px; }
  .amsat-boite button { padding:2px 8px; font-size:11px; }
  .amsat-boite .petit { font-size:11px; }
  .materiel { cursor:pointer; }
  .materiel b { font-size:14px; }
  .coche { font-weight:800; }
  .coche.on { color:var(--vert); }
  .coche.off { color:var(--gris, #6B7C8F); font-weight:400; font-size:12px; }
  .amsat-boite b { color:#FF6B6B; letter-spacing:.5px; }
  .cr-carte { position:relative; height:calc(100vh - 640px); min-height:260px; margin-top:10px; border-radius:8px; overflow:hidden; }
  .sked-bandeau { margin-top:6px; padding:6px 10px; border-radius:8px; background:rgba(255,180,84,.14); border:1px solid rgba(255,180,84,.45); font-size:12px; }
  .sked-bandeau b.k { color:var(--ambre); letter-spacing:1px; font-size:11px; margin-right:6px; }
  .sked-bandeau a { color:var(--clair); }
  .qso-passage { margin-top:6px; font-size:12px; display:flex; flex-wrap:wrap; gap:4px 6px; align-items:center; }
  .qso-passage span.q { padding:1px 8px; border-radius:10px; background:rgba(255,95,162,.16); color:#FF8CC0; font-weight:700; cursor:pointer; }
  .cr-carte canvas { position:absolute; inset:0; width:100%; height:100%; cursor:grab; }
  .cr-bornes { display:flex; justify-content:space-between; font-size:11px; color:var(--gris); margin-top:2px; }
  .cr-bande { position:relative; height:14px; margin-top:6px; background:linear-gradient(90deg, rgba(63,224,200,.15), rgba(63,224,200,.35), rgba(63,224,200,.15)); border-radius:7px; }
  .cr-bande i { position:absolute; top:-3px; bottom:-3px; width:4px; margin-left:-2px; background:#FFB454; border-radius:2px; }
  .cs-tete { display:flex; gap:12px; align-items:flex-start; }
  .cs-tete img { width:120px; max-height:150px; object-fit:cover; border-radius:8px; flex:none; }
  .cs-call { font-family:ui-monospace,Consolas,monospace; font-size:28px; font-weight:800; letter-spacing:2px; color:var(--cyan); }
  .cs-qsl { display:flex; gap:6px; flex-wrap:wrap; margin-top:4px; }
  .cs-qsl span { font-size:11px; padding:1px 7px; border-radius:10px; background:rgba(91,214,138,.15); color:var(--vert); }
  .cs-qsl span.non { background:rgba(154,167,180,.12); color:var(--gris); text-decoration:line-through; }
  .cs-carte { position:relative; height:190px; margin-top:8px; border-radius:8px; overflow:hidden; }
  .cs-carte canvas { position:absolute; inset:0; width:100%; height:100%; }
  .cs-hist td { font-size:12px; vertical-align:top; }
  .cs-hist .note { color:var(--ambre); font-style:italic; }
  .c-props { display:flex; gap:6px; margin-top:6px; min-height:44px; }
  .c-props button { flex:1; min-width:0; padding:4px 6px; background:rgba(63,224,200,.15); border-color:rgba(63,224,200,.35); text-align:center; }
  .c-props button.choisie { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .c-props button.choisie span { color:#06131A; }
  .c-props b { display:block; font-family:ui-monospace,Consolas,monospace; font-size:15px; }
  .c-props span { display:block; font-size:11px; color:var(--gris); font-weight:400; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  #liste { max-height:calc(100vh - 190px); overflow:auto; }
  svg text { font-family:system-ui,sans-serif; }
  .etat { margin-top:6px; font-size:13px; min-height:18px; color:var(--gris); }
  .avert { color:var(--ambre); font-size:12px; margin-top:6px; }
  .diag { font-family:ui-monospace,Consolas,monospace; font-size:11px; color:var(--gris); white-space:pre-wrap; margin-top:6px; max-height:90px; overflow:auto; }
  .bouton-gr { display:flex; gap:6px; flex-wrap:wrap; }
  .pastille { display:inline-block; padding:2px 8px; border-radius:20px; font-size:12px; font-weight:700; }
  .on { background:rgba(91,214,138,.18); color:var(--vert); } .off { background:rgba(154,167,180,.15); color:var(--gris); }
  .discret { background:transparent; border:none; }
  nav { display:flex; gap:4px; }
  nav button { background:transparent; border:1px solid transparent; color:var(--gris); padding:6px 14px; }
  ${ONGLET_STYLE}
  @media (max-width:1900px) { nav button { padding:5px 8px; font-size:13px; } header { gap:10px; } nav { gap:2px; } }
  @media (max-width:1500px) { nav button { padding:4px 6px; font-size:12.5px; } header h1 { font-size:16px; } #info-station { display:none; } }
  nav button.actif { color:var(--cyan); border-color:var(--bord); background:var(--carte); }
  .grille2 { display:grid; grid-template-columns:minmax(320px,1fr) minmax(420px,1.6fr); gap:12px; padding:12px 16px; }
  @media (max-width:900px) { .grille2 { grid-template-columns:1fr; } }
  td.act { white-space:nowrap; text-align:right; }
  td.act button { padding:3px 8px; font-size:12px; }
  /* The sky or the map, one at a time, as on the phone. */
  .ecran { position:relative; height:min(380px, 45vh); margin-top:8px; background:#0E1720; border-radius:8px; overflow:hidden; }
  .ecran svg, .ecran canvas { position:absolute; inset:0; width:100%; height:100%; display:block; }
  .ecran canvas { cursor:grab; touch-action:none; }
  .ecran canvas[hidden] { display:none; }
  .bascule { position:absolute; left:8px; top:8px; display:flex; gap:4px; z-index:2; }
  .bascule button { padding:4px 10px; font-size:12px; }
  .bascule button.actif { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  #suivants-boite { max-height:220px; overflow:auto; }
  #k-grille { grid-template-columns:minmax(380px,1fr) minmax(420px,1.7fr); }
  .k-fenetres { max-height:360px; overflow:auto; }
  .k-corps { display:flex; gap:12px; flex-wrap:wrap; margin-top:10px; }
  #k-carte { flex:3; min-width:320px; height:460px; border-radius:8px; cursor:grab; touch-action:none; }
  .k-ciels { flex:1; min-width:180px; max-width:240px; display:flex; flex-direction:column; gap:6px; }
  .k-ciels svg { width:100%; display:block; }
  .o-freq { font-family:ui-monospace,Consolas,monospace; font-size:34px; font-weight:800; color:var(--cyan); cursor:ns-resize; user-select:none; }
  .o-balise { font-size:13px; }
  .o-balise b { font-family:ui-monospace,Consolas,monospace; font-size:16px; }
  .o-puces { display:flex; gap:6px; flex-wrap:wrap; }
  .o-puces button { padding:4px 9px; font-size:12px; font-weight:600; }
  .o-puces button.ici { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .o-puces button.perso { border-color:var(--ambre); }
  .r-barre { width:140px; height:8px; background:#0E1720; border-radius:4px; overflow:hidden; }
  .r-barre i { display:block; height:100%; width:0; background:var(--vert); }
  .r-cascade { position:relative; height:360px; }
  .r-cascade canvas { position:absolute; inset:0; width:100%; height:100%; display:block; cursor:crosshair; }
  .r-droite { display:flex; flex-direction:column; min-width:0; }
  .s2-droite { display:flex; flex-direction:column; min-width:0; }
  .s2-carte { position:relative; height:420px; margin-top:8px; border-radius:8px; overflow:hidden; }
  .s2-carte canvas { position:absolute; inset:0; width:100%; height:100%; cursor:grab; touch-action:none; }
  .s2-gros { font-family:ui-monospace,Consolas,monospace; font-size:30px; font-weight:800; }
  .m-droite { display:flex; flex-direction:column; min-width:0; }
  .m-image { flex:1; min-height:200px; height:460px; margin-top:8px; background:#0E1720; border-radius:8px; overflow:hidden; display:flex; align-items:center; justify-content:center; }
  .m-image img { max-width:100%; max-height:100%; object-fit:contain; }
  .m-galerie { display:flex; gap:6px; overflow-x:auto; margin-top:8px; flex:none; }
  .m-galerie img { height:72px; border-radius:4px; cursor:pointer; border:2px solid transparent; }
  .m-galerie img.choisi { border-color:var(--cyan); }
  /* On a computer's screen each tab fits the window: what is longer scrolls in its own box. */
  @media (min-width:1101px) and (min-height:600px) {
    body { height:100vh; display:flex; flex-direction:column; overflow:hidden; }
    header { flex:none; }
    .vue:not([hidden]) { flex:1; min-height:0; overflow:auto; }
    #v-station .grille { height:100%; }
    #v-station .col1, #v-station .col2, #v-station .col3 { min-height:0; overflow:auto; }
    #v-station .col1 .carte { height:100%; display:flex; flex-direction:column; margin:0; }
    #v-station #liste { flex:1; max-height:none; min-height:0; }
    #v-station .col2 .carte { height:100%; display:flex; flex-direction:column; margin:0; }
    #v-station .ecran { flex:1; height:auto; min-height:220px; }
    #v-station #suivants-boite { flex:none; max-height:24vh; }
    #v-station .col3 .carte { margin-bottom:8px; padding:10px 12px; }
    #v-qo100 .grille { height:100%; }
    #v-qo100 .col1, #v-qo100 .col2, #v-qo100 .col3 { min-height:0; overflow:auto; }
    .vue > .grille2, .vue > .grille3c { height:100%; }
    .vue > .grille2 > div, .vue > .grille3c > div { min-height:0; overflow:auto; }
    #k-grille { height:100%; }
    .r-droite { height:100%; margin:0; }
    .s2-droite { height:100%; margin:0; }
    .m-droite { height:100%; margin:0; }
    .m-image { height:auto; }
    .s2-carte { flex:1; height:auto; min-height:200px; }
    .r-cascade { flex:1; min-height:120px; height:auto; }
    .k-gauche { display:flex; flex-direction:column; min-height:0; }
    .k-fenetres { flex:1; max-height:none; min-height:0; margin-bottom:0; }
    .k-droite { min-height:0; }
    #k-anim { height:100%; display:flex; flex-direction:column; margin:0; }
    .k-corps { flex:1; min-height:0; flex-wrap:nowrap; }
    #k-carte { height:auto; }
    .k-ciels { justify-content:flex-start; }
    .k-ciels svg { max-height:calc((100vh - 230px) / 2); }
  }
</style></head>
<body>
<header>
  <h1>SatMe-<span class="cyan">PC</span></h1>
  <span class="g" id="info-station"></span>
  <span class="g" id="cat-statut-court"></span>
  <nav id="onglets">
    ${boutons()}
  </nav>
  <span id="rec" class="pastille" style="display:none;background:rgba(229,72,77,.2);color:var(--rose)"></span>
  <span id="heure"></span>
</header>
<div class="vue" id="v-station">
<div class="grille">
  <!-- Passes -->
  <div class="col1">
    <div class="carte">
      <div class="t">Prochains passages (24 h)</div>
      <div class="ligne" style="margin-top:0">
        <input id="filtre" placeholder="Filtrer : ISS, SO-50, RS-44…">
        <label class="petit"><input type="checkbox" id="utc"> UTC</label>
      </div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="f-fav"> ★ favoris seulement</label>
        <label class="petit"><input type="checkbox" id="f-actifs"> entendus ces jours-ci (AMSAT)</label></div>
      <div class="etat" id="calcul"></div>
      <div id="liste"><table><thead><tr><th></th><th></th><th></th><th>Début</th><th>Satellite</th><th>El max</th><th>Durée</th><th></th></tr></thead><tbody id="passages"></tbody></table></div>
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
      <div class="paire" style="flex-wrap:nowrap;align-items:center"><span class="gros" id="nom" style="white-space:nowrap">—</span><button id="fav" class="discret" title="Favori" onclick="basculeFavori()" style="font-size:20px;padding:2px 8px">☆</button><span id="compte" class="val ambre" style="white-space:nowrap"></span>
        <div class="amsat-boite" title="AMSAT Live OSCAR Status : ce que les stations entendent, et tes rapports"><svg class="amsat-logo" viewBox="0 0 24 24" role="img" aria-label="AMSAT"><path d="M12,6.7 a5.3,5.3 0 1,0 0,10.6 a5.3,5.3 0 1,0 0,-10.6" fill="#EA0303"/><path d="M6.9,12 L17.1,12 M12,6.9 L12,17.1 M7.6,9.1 Q12,7.3 16.4,9.1 M7.6,14.9 Q12,16.7 16.4,14.9" stroke="#fff" stroke-width=".9" fill="none"/><path d="M14.4,20.3 A8.6,8.6 0 1,0 5.92,5.92" stroke="#EA0303" stroke-width="2.1" fill="none"/><path d="M3.7,8.14 L7.27,7.27 L4.5,4.5 Z" fill="#EA0303"/></svg>
          <b>AMSAT</b><span class="petit" id="amsat"></span>
          <button onclick="rapportAmsat(1)" title="Dire à amsat.org que tu l'entends maintenant">Entendu</button>
          <button onclick="rapportAmsat(0)" title="Dire à amsat.org que tu ne l'entends pas">Pas entendu</button>
          <span class="petit" id="amsat-auto"></span></div></div>
      <div class="sked-bandeau" id="st-skeds" hidden></div>
      <div class="paire" style="margin-top:6px">
        <span>Az <span class="val cyan" id="az">—</span></span>
        <span>El <span class="val cyan" id="el">—</span></span>
        <span class="petit" id="dist"></span>
      </div>
      <div class="ecran" id="s-ecran">
        <div class="bascule"><button id="b-ciel" onclick="montreEcran('ciel')">Ciel</button><button id="b-carte" onclick="montreEcran('carte')">Carte</button></div>
        <svg id="ciel" viewBox="-110 -110 220 220" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Le ciel : passage et position du satellite"></svg>
        <canvas id="st-carte" hidden title="Glisser pour déplacer, molette pour zoomer, double-clic pour revoir le monde"></canvas>
      </div>
      <div class="petit" id="infos-passage" style="text-align:center;margin-top:4px"></div>
      <div class="ligne rec-l"><button class="rouge rec-b" onclick="enregistre()">● Enregistrer le son</button><span class="val rec-d"></span><span class="petit rec-m"></span>
        <label class="petit rec-los-l" style="margin-left:auto"><input type="checkbox" class="rec-los" onchange="coupeLos(this.checked)"> couper à la fin du passage</label></div>
      <div class="t" style="margin-top:10px">Ses prochains passages</div>
      <div id="suivants-boite"><table><tbody id="suivants"></tbody></table></div>
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
      <div class="ligne"><span class="petit" style="min-width:90px">Calage RX</span>
        <div class="bouton-gr"><button onclick="regle('calib',-50)">−50</button><button onclick="regle('calib',-10)">−10</button></div>
        <span class="val" id="r-calib" style="min-width:80px;text-align:center">0</span>
        <div class="bouton-gr"><button onclick="regle('calib',10)">+10</button><button onclick="regle('calib',50)">+50</button></div></div>
      <div class="ligne"><span class="petit" style="min-width:90px">Décalage TX</span>
        <div class="bouton-gr"><button onclick="regle('tx',-50)">−50</button><button onclick="regle('tx',-10)">−10</button></div>
        <span class="val" id="r-tx" style="min-width:80px;text-align:center">0</span>
        <div class="bouton-gr"><button onclick="regle('tx',10)">+10</button><button onclick="regle('tx',50)">+50</button></div></div>
      <div class="ligne"><button onclick="post('api/sat/reglage?memorise=1')" title="Garder ces deux valeurs comme celles qui marchent pour ce satellite">Mémoriser</button>
        <button id="r-retour" onclick="post('api/sat/reglage?retour=1')">Revenir à la référence</button><span class="petit" id="r-ref"></span></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="maintien" onchange="post('api/maintien?on='+(this.checked?1:0))"> Doppler en pause</label></div>
      <div class="ligne" id="repos-ligne" style="display:none">
        <span class="petit">Dans le transpondeur</span>
        <div class="bouton-gr">
          <button onclick="repos(-5000)">−5 k</button><button onclick="repos(-1000)">−1 k</button>
          <button onclick="repos(-100)">−100</button><button onclick="repos(100)">+100</button>
          <button onclick="repos(1000)">+1 k</button><button onclick="repos(5000)">+5 k</button>
        </div>
      </div>
    </div>

    <div class="carte materiel" onclick="vue('reglages')" title="Poste et rotor : leurs réglages sont dans l'onglet Réglages">
      <div class="paire" style="margin:0">
        <span><span class="petit">Poste</span> <b id="st-poste">—</b> <span id="st-poste-ok" class="coche"></span></span>
        <span><span class="petit">Rotor</span> <b id="st-rotor">—</b> <span id="st-rotor-ok" class="coche"></span></span>
      </div>
    </div>

  </div>
</div>
</div>
<div class="vue" id="v-carnet" hidden>
<div class="grille3c">
  <div>
    <div class="carte">
      <div class="t">Nouveau contact <span class="petit" id="c-sat"></span></div>
      <div class="ligne" style="margin-top:0;margin-bottom:6px"><span class="petit">Satellite</span>
        <select id="c-satsel" style="flex:1" onchange="if (this.value) suivre(+this.value).then(tic)" title="Le satellite suivi : le carnet, la carte, le poste et le rotor le suivent"></select></div>
      
      <input id="c-call" placeholder="INDICATIF" autocomplete="off" style="width:100%;font-size:26px;font-weight:800;letter-spacing:3px;text-transform:uppercase">
      <div id="c-props" class="c-props"></div>
      <div id="c-qui" class="petit" style="min-height:20px;margin-top:4px"></div>
      <div class="ligne">
        <input id="c-loc" placeholder="Carré" style="text-transform:uppercase;max-width:120px">
        <span class="petit">RS ↑</span><input id="c-rs" value="59" style="max-width:60px">
        <span class="petit">↓</span><input id="c-rr" value="59" style="max-width:60px">
      </div>
      <div class="ligne"><input id="c-note" placeholder="Note"></div>
      <div class="ligne rec-l"><button class="rouge rec-b" onclick="enregistre()">● Enregistrer le son</button><span class="val rec-d"></span><span class="petit rec-m"></span>
        <label class="petit rec-los-l" style="margin-left:auto"><input type="checkbox" class="rec-los" onchange="coupeLos(this.checked)"> couper à la fin du passage</label></div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="ca-on" onchange="apresBascule()"> Après le passage : un passage déjà fini, à son heure</label>
      <div id="ca" hidden style="margin-top:4px">
        <div class="ligne" style="margin-top:0"><select id="ca-sat" onchange="apresPassages()" style="flex:1"></select>
          <select id="ca-md" title="Mode"></select></div>
        <div class="ligne"><select id="ca-pas" onchange="apresPassage()" style="flex:1"></select></div>
        <div class="ligne"><input id="ca-heure" type="datetime-local" step="60"><span class="petit" id="ca-utc"></span></div>
      </div>
      <div class="ligne"><button class="primaire" onclick="enregistreContact()">Enregistrer (Entrée)</button><span class="petit" id="c-etat"></span></div>
      <div class="petit" style="margin-top:6px" id="c-aide">Daté à l'enregistrement, sur le satellite suivi, avec son az/el, ton carré, le mode et les fréquences au repos.</div>
    </div>
    <div class="carte">
      <div class="t">Carnet</div>
      <div class="paire"><span><span class="val" id="c-nb">0</span> <span class="petit">contacts</span></span>
        <span><span class="val" id="c-carres">0</span> <span class="petit">carrés</span></span>
        <span><span class="val ambre" id="c-attente">0</span> <span class="petit">à déposer</span></span></div>
      <div class="ligne"><a href="carnet.adi" download><button>Exporter en ADIF</button></a>
        <button onclick="depose()">Déposer au carnet en ligne</button></div>
      <div class="petit" id="c-auto"></div>
      <div class="etat" id="c-depot"></div>
    </div>
    <details class="carte">
      <summary class="t" style="cursor:pointer;margin:0">Importer</summary>
      <div class="ligne"><button onclick="profilsWavelog()">Depuis le carnet en ligne (Wavelog)…</button></div>
      <div id="w-profils" class="petit"></div>
      <div class="petit" style="margin-top:10px">Ou un fichier ADIF (export Wavelog, LoTW, autre logiciel) :</div>
      <input type="file" id="i-adif" accept=".adi,.adif,.txt" style="width:100%;margin-top:4px" onchange="importeAdif(this)">
      <label class="petit"><input type="checkbox" id="i-enligne" checked> ces contacts sont déjà dans le carnet en ligne (ne pas les y redéposer)</label>
      <div class="petit" style="margin-top:6px">Les contacts satellite absents du carnet y entrent, avec la position du satellite à leur heure ; leurs passages sont reconstitués dans le Journal.</div>
      <div class="etat" id="i-etat"></div>
      <div class="t" style="margin-top:12px">SatMe sur le téléphone</div>
      <div class="petit">Ses passages viennent au Journal du PC, leurs contacts au carnet, leurs images SSTV aux images.</div>
      <div class="ligne"><button onclick="ouvreTelephone()">Depuis le téléphone (Wi-Fi)…</button></div>
      <div class="petit" style="margin-top:6px">Ou ses fichiers de passage (.zip, « Fichier du passage » dans le Journal du téléphone) :</div>
      <input type="file" id="t-zip" accept=".zip" multiple style="width:100%;margin-top:4px" onchange="importeZips(this)">
      <div class="etat" id="t-etat"></div>
    </details>
  </div>
  <div>
    <div class="carte" id="c-station">
      <div id="cs-vide" class="petit">Tape un indicatif : la station s'affiche ici — sa fiche QRZ (photo, où elle est, distance, carte), et tous vos contacts passés avec leurs notes.</div>
      <div id="cs-plein" hidden>
        <div class="cs-tete">
          <img id="cs-photo" alt="" hidden onerror="this.hidden = true">
          <div style="min-width:0">
            <div class="cs-call" id="cs-call"></div>
            <div id="cs-nom" style="font-weight:700"></div>
            <div class="petit" id="cs-lieu"></div>
            <div class="petit" id="cs-info"></div>
            <div class="cs-qsl" id="cs-qsl"></div>
          </div>
        </div>
        <div class="paire" id="cs-dist" style="margin-top:8px"><span class="s2-gros cyan" id="cs-km"></span><span class="s2-gros ambre" id="cs-az"></span></div>
        <div class="cs-carte" id="cs-carte-boite"><canvas id="cs-carte"></canvas></div>
        <div class="t" style="margin-top:10px">Vos contacts avec cette station</div>
        <div id="cs-resume" class="petit"></div>
        <div class="cs-hist"><table><tbody id="cs-hist"></tbody></table></div>
      </div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="bascule-c"><button id="cv-station" onclick="vueCarnet('station')">Station en direct</button><button id="cv-contacts" onclick="vueCarnet('contacts')">Contacts</button></div>
      <div id="cr-station">
        <div class="paire" style="margin-top:8px"><span class="gros" id="cr-sat">—</span><span class="val ambre" id="cr-compte"></span></div>
        <div class="paire"><span>Az <span class="val cyan" id="cr-az">—</span></span><span>El <span class="val cyan" id="cr-el">—</span></span><span class="petit" id="cr-dist"></span></div>
        <div class="cr-avance" title="L'avancée du passage, de l'AOS au LOS"><div class="cr-profil" id="cr-profil"></div><i id="cr-curseur"></i></div>
        <div class="petit" id="cr-passage"></div>
        <div class="sked-bandeau" id="cr-skeds" hidden></div>
        <div class="qso-passage" id="cr-qso"></div>
        <div class="cr-corps">
          <svg id="cr-ciel" viewBox="-110 -110 220 220" class="cr-ciel" role="img" aria-label="Le ciel : passage et position du satellite"></svg>
          <div class="cr-infos">
            <div class="t">Fréquences</div>
            <select id="cr-tx" style="width:100%;margin-top:2px" onchange="post('api/emetteur?i=' + this.value).then(tic)" title="L'émetteur écouté : un transpondeur linéaire montre sa bande"></select>
            <div class="paire"><span>RX <span class="val cyan" id="cr-dl">—</span></span></div>
            <div class="paire"><span>TX <span class="val ambre" id="cr-ul">—</span></span></div>
            <div class="petit" id="cr-poste"></div>
            <div id="cr-bande-boite"><div id="cr-bande" class="cr-bande" title="Où tu es dans la bande du transpondeur"><i id="cr-repos"></i></div>
              <div class="cr-bornes"><span id="cr-bas"></span><span id="cr-haut"></span></div></div>
            <div class="petit" id="cr-bande-txt"></div>
            <div class="t" style="margin-top:10px">Station</div>
            <div class="petit" id="cr-rotor"></div>
            <div class="petit" id="cr-son"></div>
            <div class="petit" id="cr-sdr"></div>
          </div>
        </div>
        <div class="cr-carte" title="Sa trace au sol : glisser, molette, double-clic pour recadrer"><canvas id="cr-carte"></canvas></div>
      </div>
      <div id="cr-contacts" hidden>
      <div class="ligne"><input id="c-filtre" placeholder="Filtrer : indicatif, carré, satellite…">
        <span id="c-sel" hidden style="display:flex;gap:6px;align-items:center">
          <span class="petit" id="c-sel-n"></span>
          <a class="bouton" id="c-sel-adif" href="#" download title="Les contacts cochés en ADIF (Wavelog, LoTW, un autre carnet)">⤓ ADIF</a>
          <a class="bouton" id="c-sel-lot" href="#" download title="Les contacts cochés pour SatMe sur un téléphone : Réglages › Sauvegarde › Fusionner un lot reçu">⤓ Lot pour le téléphone</a>
          <button class="rouge" id="c-suppr" onclick="supprimeSelection()">Supprimer</button></span></div>
      <div style="max-height:calc(100vh - 170px);overflow:auto;margin-top:6px">
        <table><thead><tr><th><input type="checkbox" id="c-tout" title="Tout cocher (ce que le filtre montre)" onchange="cocheTout(this.checked)"></th><th>Date (UTC)</th><th>Indicatif</th><th>Carré</th><th>Satellite</th><th>Mode</th><th>RS</th><th></th></tr></thead>
        <tbody id="c-liste"></tbody></table>
      </div>
      </div>
    </div>
  </div>
</div>
<dialog id="t-dlg" style="background:var(--carte);color:var(--clair);border:1px solid var(--bord);border-radius:12px;width:min(760px,94vw)">
  <div class="t">SatMe sur le téléphone</div>
  <div id="t-qr-boite" style="display:flex;gap:14px;align-items:flex-start">
    <div id="t-qr" style="width:190px;height:190px;flex:none;background:#fff;border-radius:8px;padding:0"></div>
    <div style="flex:1">
      <div><b>Scannez ce QR code avec SatMe</b> sur le téléphone : Réglages → Partage et écoute → Partage → <b>Scanner le QR code du PC</b>.</div>
      <div class="petit" style="margin-top:6px">Le téléphone et ce PC doivent être sur le même réseau (Wi-Fi). Le téléphone ouvre son poste de commande et le PC s'y connecte tout seul.</div>
      <div class="ligne"><span class="petit">adresse de ce PC</span><select id="t-ip" onchange="montreQr(this.value)" style="max-width:180px"></select></div>
      <div class="petit" id="t-qr-etat"></div>
    </div>
  </div>
  <details id="t-main" style="margin-top:8px"><summary class="petit">ou à la main : l'adresse et le code affichés par le téléphone</summary>
  <div class="petit">Sur le téléphone (le même Wi-Fi que ce PC) : Réglages → Partage et écoute → Partage → « Poste de commande sur PC ». Recopiez l'adresse qu'il affiche (http://…/c/…/) et le « code à saisir sur le PC ».</div>
  <div class="ligne"><input id="t-adresse" placeholder="Adresse affichée (http://192.168.1.23:8080/c/…/)" style="flex:3;min-width:300px"><input id="t-code" placeholder="Code" style="max-width:110px">
    <button class="primaire" onclick="connecteTelephone()">Connecter</button></div>
  </details>
  <div class="petit" id="t-cnx" style="margin-top:6px"></div>
  <div id="t-liste-boite" hidden>
    <div class="ligne"><label class="petit"><input type="checkbox" id="t-tout" onchange="document.querySelectorAll('.t-coche').forEach(c => c.checked = this.checked)"> tout</label>
      <label class="petit"><input type="checkbox" id="t-enligne" checked> contacts déjà au carnet en ligne (envoyés par le téléphone)</label></div>
    <div style="max-height:46vh;overflow:auto"><table><thead><tr><th></th><th>Début</th><th>Satellite</th><th>El</th><th>Durée</th><th>Contenu</th></tr></thead><tbody id="t-liste"></tbody></table></div>
    <div class="ligne"><button class="primaire" onclick="rapatrie()">Importer la sélection</button><span class="petit" id="t-prog"></span></div>
    <div class="petit" id="t-bilan"></div>
  </div>
  <div class="ligne"><button onclick="g('t-dlg').close(); chargeCarnet()">Fermer</button></div>
</dialog>
<dialog id="c-edit" style="background:var(--carte);color:var(--clair);border:1px solid var(--bord);border-radius:12px;min-width:360px">
  <div class="t">Modifier le contact <span class="petit" id="e-quand"></span></div>
  <div class="ligne"><input id="e-call" placeholder="Indicatif" style="text-transform:uppercase"><input id="e-loc" placeholder="Carré" style="text-transform:uppercase;max-width:110px"></div>
  <div class="ligne"><input id="e-md" placeholder="Mode" style="max-width:80px"><input id="e-rs" placeholder="RS ↑" style="max-width:70px"><input id="e-rr" placeholder="RS ↓" style="max-width:70px"></div>
  <div class="ligne"><input id="e-nm" placeholder="Nom"><input id="e-qth" placeholder="QTH"></div>
  <div class="ligne"><input id="e-n" placeholder="Note"></div>
  <div class="ligne"><button onclick="qrzEdit()">QRZ</button><span class="petit" id="e-qrz"></span></div>
  <div class="ligne"><button class="primaire" onclick="enregistreEdit()">Enregistrer</button><button onclick="g('c-edit').close()">Annuler</button>
    <button class="rouge" onclick="supprimeEdit()" style="margin-left:auto">Supprimer</button></div>
</dialog>
</div>
<div class="vue" id="v-son" hidden>
<div class="grille2">
  <div>
    <div class="carte">
      <div class="t">Carte son (le son du poste)</div>
      <div class="ligne" style="margin-top:0"><select id="s-carte"></select><button onclick="carteSon()">Démarrer</button><button onclick="carteSon(true)">Arrêter</button></div>
      <div style="height:10px;background:#0E1720;border-radius:5px;margin-top:10px;overflow:hidden"><div id="s-niveau" style="height:100%;width:0;background:var(--vert)"></div></div>
      <div class="etat" id="s-etat"></div>
    </div>
    <div class="carte">
      <div class="t">Enregistrement</div>
      <div class="ligne" style="margin-top:0"><button class="rouge" id="s-rec" onclick="enregistre()">● Enregistrer</button><span class="val" id="s-duree"></span></div>
      <div class="ligne rec-los-l"><label class="petit"><input type="checkbox" class="rec-los" onchange="coupeLos(this.checked)"> Couper l'enregistrement à la fin du passage, même lancé à la main (30 s après le LOS du satellite suivi)</label></div>
      <div class="ligne" id="s-auto-l"><label class="petit"><input type="checkbox" id="s-auto" onchange="post('api/son/options?auto=' + (this.checked ? 1 : 0))"> Enregistrer chaque passage du satellite suivi (de l'AOS à 30 s après le LOS)</label></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="s-aprs" onchange="post('api/son/options?aprs=' + (this.checked ? 1 : 0))"> Décoder l'APRS (AFSK 1200 bauds)</label></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="s-sstv" onchange="post('api/son/options?sstv=' + (this.checked ? 1 : 0))"> Décoder la SSTV en direct</label></div>
      <div class="petit" style="margin-top:6px" id="s-note">WAV 16 bits, à la moitié de la fréquence de la carte (24 ou 22,05 kHz). Même nom que sur le téléphone.</div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">Enregistrements</div>
      <audio id="s-lecteur" controls style="width:100%;margin-bottom:8px"></audio>
      <div style="max-height:calc(100vh - 230px);overflow:auto"><table><tbody id="s-liste"></tbody></table></div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-sstv" hidden>
<div class="grille2">
  <div>
    <div class="carte">
      <div class="t">En cours <span class="petit" id="i-mode"></span></div>
      <img id="i-encours" style="width:100%;image-rendering:auto;background:#0E1720;min-height:200px;border-radius:8px" alt="">
      <div class="etat" id="sstv-etat"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">Images reçues</div>
      <div id="i-galerie" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px"></div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-aprs" hidden>
<div style="padding:12px 16px">
  <div class="carte">
    <div class="paire">
      <span><span class="val" id="a-n">0</span> <span class="petit">trames</span></span>
      <span><span class="val" id="a-stations">0</span> <span class="petit">stations</span></span>
      <span><span class="val ambre" id="a-iss">0</span> <span class="petit">par l'ISS</span></span>
      <span><span class="val" id="a-loin">—</span> <span class="petit">km au plus loin</span></span>
      <span class="petit" id="a-etat" style="margin-left:auto"></span>
    </div>
    <div class="ligne"><input id="a-filtre" placeholder="Filtrer : indicatif, message…"><label class="petit"><input type="checkbox" id="a-isss"> par l'ISS seulement</label></div>
  </div>
  <details class="carte" id="kiss-carte">
    <summary class="t" style="cursor:pointer;margin:0">Radio APRS en KISS (TH-D72, TM-D710, Mobilinkd…) <span id="kiss-pastille" class="pastille off">déconnectée</span></summary>
    <div class="ligne"><select id="kiss-port" style="flex:1"></select>
      <select id="kiss-vitesse" style="flex:none"><option>1200</option><option>4800</option><option selected>9600</option><option>19200</option><option>38400</option><option>57600</option></select>
      <button class="primaire" onclick="kConnecte()">Connecter</button><button onclick="kPost('deconnecter')">Déconnecter</button></div>
    <label class="petit" style="display:block;margin-top:6px" id="kiss-lignes-l"><input type="checkbox" id="kiss-lignes"> Lever RTS/DTR à l'ouverture — seulement si ce TNC l'exige ; jamais sur l'interface d'un poste (il passerait en émission)</label>
    <div class="ligne"><span class="petit">Écoute</span>
      <select id="kiss-travail" style="flex:none" onchange="kPost('reglage?travail=' + this.value)"><option value="AUTO">l'ISS quand elle passe, sinon 144,800</option><option value="ISS">l'ISS (145,825)</option><option value="TERRE">au sol (144,800)</option><option value="POSTE">la fréquence de la radio, sans y toucher</option></select>
      <label class="petit"><input type="checkbox" id="kiss-doppler" onchange="kPost('reglage?doppler=' + (this.checked ? 1 : 0))"> Doppler de l'ISS (pas de 5 kHz)</label></div>
    <div class="petit" id="kiss-etat" style="margin-top:6px"></div>
    <div class="petit" style="margin-top:4px">La radio décode elle-même ; ses trames arrivent ici avec les autres. Elle n'émet que ce qui est demandé ci-dessous. Déconnectée, elle revient comme avant.</div>
    <div class="t" style="margin-top:12px">Émettre par la radio <span class="petit" id="kiss-source"></span></div>
    <div class="ligne" style="margin-top:0">
      <select id="kiss-type" style="flex:none" onchange="kType()"><option value="message">Message</option><option value="position">Ma position</option><option value="statut">Statut</option></select>
      <input id="kiss-dest" placeholder="À (indicatif)" style="max-width:130px;text-transform:uppercase">
      <input id="kiss-texte" placeholder="Texte (67 caractères)" maxlength="67" style="flex:1"></div>
    <div class="ligne"><span class="petit">Chemin</span>
      <select id="kiss-chemin" style="flex:none"><option value="">selon l'écoute</option><option value="ARISS">ISS (ARISS)</option><option value="WIDE">au sol (WIDE1-1,WIDE2-1)</option></select>
      <label class="petit" id="kiss-numero-l"><input type="checkbox" id="kiss-numero" checked> numéroté (accusé de réception)</label>
      <button class="rouge" onclick="kEmet()">Émettre</button></div>
    <label class="petit" style="display:block;margin-top:6px" id="kiss-confirme-l"><input type="checkbox" id="kiss-confirme"> La radio est sur 145,825 MHz (ISS) ou 144,800 MHz, en FM (SatMe n'a pas pu lire sa fréquence)</label>
    <div class="petit" id="kiss-envoi" style="margin-top:6px"></div>
    <div class="ligne"><span class="petit">SSID</span><select id="kiss-ssid" style="flex:none" onchange="kPost('reglage?ssid=' + this.value)"></select>
      <label class="petit"><input type="checkbox" id="kiss-floue" onchange="kPost('reglage?floue=' + (this.checked ? 1 : 0))"> position approchée (à moins de 500 m)</label></div>
    <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="kiss-balise" onchange="kPost('reglage?balise=' + (this.checked ? 1 : 0))">
      Balise ISS : ma position une fois par passage, l'ISS à 15° — seulement quand SatMe a lu la radio sur 145,825</label>
    <div class="petit" style="margin-top:4px">Une trame toutes les 20 s au plus : le digipeater de l'ISS est partagé par tout un continent.</div>
  </details>
  <div class="carte">
    <div style="max-height:calc(100vh - 250px);overflow:auto">
      <table><thead><tr><th>Heure</th><th>Station</th><th>Type</th><th>Carré</th><th>km</th><th>Chemin</th><th>Texte</th></tr></thead><tbody id="a-liste"></tbody></table>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-agenda" hidden>
<div class="grille2">
  <div>
    <div class="carte">
      <div class="t">Agenda</div>
      <div class="ligne" style="margin-top:0"><label class="petit"><input type="checkbox" id="g-suivi" onchange="post('api/agenda/suivi?on=' + (this.checked ? 1 : 0))">
        Suivre tout seul le satellite d'un rendez-vous, 5 min avant (le poste et le rotor suivent)</label></div>
      <div class="petit" style="margin-top:6px">📅 dans la liste des passages (onglet Station) ajoute ou retire un passage. L'agenda du téléphone arrive avec sa sauvegarde (Réglages).</div>
    </div>
    <div class="carte va">
      <div class="t">Mode automatique : ne manquer aucun passage</div>
      <div class="ligne" style="margin-top:0"><label><input type="checkbox" id="va-on" onchange="veille('on=' + (this.checked ? 1 : 0))"> <b>Actif</b></label><span class="petit" id="va-etat"></span></div>
      <div class="petit" style="margin-top:6px">Chaque passage gardé est suivi tout seul 5 min avant l'AOS : le poste (CAT, Doppler) et le rotor le suivent, le son est enregistré de 5 s avant l'AOS à 30 s après le LOS, et le passage va au journal. Deux passages en même temps : le premier continue (écarte-le pour garder l'autre). Changer de satellite à la main pendant un passage reste possible.</div>
      <div class="ligne"><input id="va-sat" list="tous" placeholder="Ajouter un satellite : RS-44, SO-50…"><button onclick="veilleAjoute()">Ajouter</button><button onclick="veille('favoris=1')" title="Ajouter tous les satellites ★">★ favoris</button></div>
      <div id="va-sats" style="display:flex;flex-wrap:wrap;gap:6px;margin-top:8px"></div>
      <div class="ligne"><span class="petit">Passages d'au moins</span><input id="va-el" type="number" min="0" max="90" style="max-width:70px" onchange="veille('el=' + (+this.value || 0))"><span class="petit">° d'élévation max</span></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="va-son" onchange="veille('son=' + (this.checked ? 1 : 0))"> Enregistrer le son de chaque passage (carte son de l'onglet Son)</label></div>
      <div class="etat" id="va-alerte"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <table><thead><tr><th>Quand</th><th>Rendez-vous</th><th>Satellite</th><th></th></tr></thead><tbody id="g-liste"></tbody></table>
    </div>
    <div class="carte va">
      <div class="t">Passages du mode automatique (24 h)</div>
      <table><thead><tr><th>Quand</th><th>Satellite</th><th>El max</th><th>Durée</th><th></th></tr></thead><tbody id="va-plan"></tbody></table>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-carres" hidden>
<div class="grille2" style="grid-template-columns:minmax(300px,1fr) minmax(500px,2.2fr)">
  <div>
    <div class="carte">
      <div class="paire">
        <span><span class="val" id="q-n">0</span> <span class="petit">carrés</span></span>
        <span><span class="val" id="q-champs">0</span> <span class="petit">champs</span></span>
        <span><span class="val ambre" id="q-loin">—</span> <span class="petit">km au plus loin</span></span>
      </div>
      <div class="ligne"><input id="q-filtre" placeholder="Filtrer : carré, indicatif, satellite…"></div>
      <div style="max-height:calc(100vh - 230px);overflow:auto;margin-top:6px">
        <table><thead><tr><th>Carré</th><th>QSO</th><th>km</th><th>Stations</th></tr></thead><tbody id="q-liste"></tbody></table>
      </div>
    </div>
  </div>
  <div>
    <div class="carte" style="padding:0;overflow:hidden;position:relative">
      <canvas id="q-carte" style="width:100%;height:calc(100vh - 110px);display:block;cursor:grab"></canvas>
      <div class="petit" style="position:absolute;right:6px;bottom:4px;background:rgba(255,255,255,.75);color:#333;padding:0 4px;border-radius:3px">© OpenStreetMap</div>
      <div class="petit" style="position:absolute;left:8px;top:6px;background:rgba(11,16,22,.75);padding:2px 8px;border-radius:6px">glisser, molette ; double-clic : recadrer · <span style="color:#3FE0C8">■</span> contacté · <span style="color:#FFB454">■</span> mon carré</div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-frise" hidden>
<div style="padding:12px 16px">
  <div class="carte">
    <div class="ligne" style="margin-top:0"><span class="t" style="margin:0">Frise des passages</span>
      <span class="petit">les ★ favoris, le satellite suivi et ceux de l'agenda · clic : le suivre</span>
      <select id="f-h" style="margin-left:auto;max-width:120px" onchange="chargeFrise()"><option value="6">6 h</option><option value="12" selected>12 h</option><option value="24">24 h</option><option value="48">48 h</option></select></div>
    <canvas id="f-canvas" style="width:100%;display:block;margin-top:8px;cursor:pointer"></canvas>
  </div>
</div>
</div>
<div class="vue" id="v-sked" hidden>
<div class="grille2" id="k-grille">
  <div class="k-gauche">
    <div class="carte">
      <div class="t">Sked avec une autre station</div>
      <div class="ligne" style="margin-top:0"><input id="k-loc" placeholder="Son carré (IN80, FN31…)" style="text-transform:uppercase"></div>
      <div class="ligne"><select id="k-sat"></select></div>
      <div class="ligne"><span class="petit">Il voit au-dessus de</span><input id="k-min" value="0" style="max-width:60px"><span class="petit">°</span>
        <button class="primaire" onclick="calculeSked()">Chercher</button></div>
      <div class="petit" style="margin-top:6px">Les fenêtres des 48 h où vous voyez tous deux le satellite (une minute au moins), avec l'élévation maximale de chaque côté. ▶ les rejoue sur la carte.</div>
      <div class="etat" id="k-etat"></div>
    </div>
    <div class="carte k-fenetres">
      <table><thead><tr><th>Début</th><th>Fin</th><th>Durée</th><th>Moi</th><th>Lui</th><th></th></tr></thead><tbody id="k-liste"></tbody></table>
    </div>
  </div>
  <div class="k-droite">
    <div class="carte" id="k-vide"><div class="petit">Cherche les fenêtres communes avec une station : la première se rejoue ici, les deux ciels et la carte.</div></div>
    <div class="carte" id="k-anim" hidden>
      <div class="ligne" style="margin-top:0;flex-wrap:wrap">
        <span class="t" id="k-titre" style="margin:0"></span>
        <button id="k-joue" onclick="basculeSked()" style="min-width:44px">⏸</button>
        <input type="range" id="k-curseur" min="0" max="1000" value="0" style="flex:1;min-width:160px">
        <span id="k-heure" style="font-variant-numeric:tabular-nums;min-width:70px"></span>
        <span id="k-ensemble" class="petit"></span>
        <button id="k-video" onclick="videoSked()" title="Le sked rejoué en vidéo (MP4), à envoyer à l'autre station">Vidéo</button>
        <span class="petit" id="k-video-etat"></span>
      </div>
      <div class="k-corps">
        <canvas id="k-carte" title="Glisser pour déplacer, molette pour zoomer, double-clic pour recadrer"></canvas>
        <div class="k-ciels">
          <div><div class="petit" id="k-lmoi">Moi</div><svg id="k-cmoi" viewBox="-112 -112 224 224"></svg></div>
          <div><div class="petit" id="k-llui">Lui</div><svg id="k-clui" viewBox="-112 -112 224 224"></svg></div>
        </div>
      </div>
      <div class="petit" style="margin-top:6px">En vert, la fenêtre où vous voyez tous deux le satellite ; la zone claire autour du satellite est la partie de la Terre qui le voit à cet instant.</div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-datv" hidden>
<div class="d-grille">
  <div class="carte d-ecran">
    <div class="t">DATV · MiniTiouner <span id="d-titre" class="petit"></span></div>
    <div id="d-cadre"><img id="d-video" alt=""><div id="d-vide">Réception arrêtée</div></div>
    <div class="paire" style="margin-top:8px;align-items:center">
      <span id="d-etat" class="pastille">arrêté</span>
      <span>MER <b class="val" id="d-mer">—</b></span>
      <span class="val" id="d-modcod" style="font-size:16px"></span>
      <span class="petit" id="d-porteuse"></span>
      <span class="petit" id="d-debit"></span>
      <span id="d-rec" class="pastille" style="display:none;background:rgba(229,72,77,.2);color:var(--rose)"></span>
    </div>
    <div class="petit" id="d-service" style="margin-top:4px"></div>
  </div>
  <div class="d-cote">
    <div class="carte">
      <div class="t">Réception</div>
      <div class="ligne" style="margin-top:0"><span class="petit" style="min-width:86px">MiniTiouner</span><span id="d-ou" class="petit" style="flex:1"></span>
        <button onclick="g('d-noeud-boite').hidden = !g('d-noeud-boite').hidden" title="Le MiniTiouner sur ce PC, ou sur un SatMe-Node près de l'antenne">Changer</button></div>
      <div id="d-noeud-boite" hidden>
        <div class="petit">Un <b>SatMe-Node</b> (Raspberry Pi près de l'antenne, <code>satme --noeud</code>) : son adresse et le code qu'il a donné au démarrage. Adresse vide : le MiniTiouner de ce PC.</div>
        <div class="ligne"><input id="d-noeud" placeholder="192.168.1.30 (ou :port)" style="max-width:190px"><input id="d-noeud-code" placeholder="Code" style="max-width:90px">
          <button onclick="dNoeud()">Associer</button></div>
      </div>
      <div class="ligne"><span class="petit" style="min-width:86px">Ciel (MHz)</span><input id="d-ciel" style="max-width:130px">
        <button onclick="dBalise()" title="Balise large bande : 10491,500 MHz à 1500 kS/s">Balise</button></div>
      <div class="ligne"><span class="petit" style="min-width:86px">Canal</span><select id="d-canal" onchange="if (this.value) g('d-ciel').value = this.value"></select></div>
      <div class="ligne"><span class="petit" style="min-width:86px">kS/s</span><div id="d-debits" class="o-puces" style="flex:1"></div></div>
      <div class="ligne"><span class="petit" style="min-width:86px">OL du LNB (MHz)</span><input id="d-ol" style="max-width:130px">
        <button onclick="dCale()" title="Accrocher la balise, puis : l'oscillateur est corrigé pour qu'elle tombe à sa fréquence">Caler sur le signal</button></div>
      <div class="ligne"><span class="petit" style="min-width:86px">Entrée</span><select id="d-entree" style="max-width:150px"><option value="a">A (haut)</option><option value="b">B (bas)</option></select>
        <span class="petit">Alim. LNB</span><select id="d-lnb" style="max-width:150px" onchange="dLnb()"><option value="">coupée</option><option value="v">13 V (V)</option><option value="h">18 V (H)</option></select></div>
      <div class="avert" id="d-lnb-avert" hidden>Le MiniTiouner alimentera le LNB : seulement si rien d'autre ne l'alimente (pas de séparateur qui passe le continu vers un autre récepteur).</div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="d-son"> Son sur ce PC</label>
      <div class="ligne"><button class="primaire" onclick="dDemarre()">Recevoir</button><button onclick="dPost('arrete')">Arrêter</button>
        <button id="d-benr" onclick="dEnregistre()">Enregistrer</button><button onclick="dPost('vlc')" title="Le même flux dans VLC, pleine qualité">VLC</button></div>
      <div class="etat" id="d-msg"></div>
    </div>
    <div class="carte">
      <div class="t">Constellation</div>
      <div style="display:flex;gap:12px;align-items:flex-start">
        <canvas id="d-const" width="200" height="200" style="width:200px;height:200px;background:#0E1720;border-radius:8px"></canvas>
        <div class="petit" style="flex:1;line-height:1.7" id="d-details"></div>
      </div>
    </div>
    <div class="petit" id="d-prog"></div>
  </div>
</div>
</div>
<div class="vue" id="v-qo100" hidden>
<div class="grille">
  <div class="col1">
    <div class="carte">
      <div class="t">QO-100 · géostationnaire, 25,9° est</div>
      <div class="petit">Descente (ciel)</div>
      <div id="o-dl" class="o-freq" title="Molette : ±100 Hz (Maj : ±1 kHz) · flèches ← → ±100 Hz, ↑ ↓ ±1 kHz"></div>
      <div class="petit" style="margin-top:4px">Montée <span id="o-ul" class="val ambre" style="font-size:18px"></span></div>
      <div class="ligne bouton-gr" style="margin-top:10px">
        <button onclick="qPas(-10000)">−10 k</button><button onclick="qPas(-1000)">−1 k</button><button onclick="qPas(-100)">−100</button>
        <button onclick="qPas(100)">+100</button><button onclick="qPas(1000)">+1 k</button><button onclick="qPas(10000)">+10 k</button></div>
      <div class="ligne"><button id="o-bbalise" onclick="qPost('balise')">Balise médiane</button><button onclick="qPost('centre')">Centre</button>
        <span class="petit" id="o-bords"></span></div>
      <div class="ligne"><span class="petit">Aller à (MHz ciel)</span><input id="o-aller" placeholder="10489,688" style="max-width:130px">
        <button onclick="qAller()">Aller</button></div>
    </div>
    <div class="carte">
      <div class="t">Mémoires</div>
      <div id="o-memoires" class="o-puces"></div>
      <div class="ligne"><input id="o-nom" placeholder="Nom (facultatif)" style="max-width:180px"><button onclick="qMemoire()">Mémoriser ici</button>
        <button id="o-oublie" onclick="qOublie()">Oublier</button></div>
    </div>
  </div>
  <div class="col2">
    <div class="carte">
      <div class="t">Réglette du transpondeur</div>
      <canvas id="o-reglette" style="width:100%;height:96px;display:block;cursor:crosshair" title="Cliquer ou glisser pour s'y poser · molette ±100 Hz"></canvas>
      <canvas id="o-loupe" style="width:100%;height:70px;display:block;margin-top:6px;cursor:crosshair" title="± 25 kHz autour de l'accord : cliquer pour s'y poser"></canvas>
      <canvas id="o-pano" style="width:100%;height:130px;display:block;margin-top:6px;cursor:crosshair" title="Le transpondeur vu par la clé SDR, à l'échelle de la réglette : cliquer pour s'y poser"></canvas>
      <div class="petit" id="o-pano-msg"></div>
      <div class="paire" style="margin-top:8px"><b id="o-seg"></b><span class="petit" id="o-larg"></span><span id="o-interdit" class="rose" style="font-weight:700"></span></div>
      <div class="petit" id="o-avert" style="margin-top:2px"></div>
    </div>
    <div class="carte">
      <div class="t">Ce que voient le poste et la clé</div>
      <label><input type="checkbox" id="o-poste" onchange="qPost('poste?on=' + (this.checked ? 1 : 0))"> Appliquer au poste</label>
      <div class="paire" style="margin-top:8px">
        <span>Poste RX <span class="val cyan" id="o-prx">—</span></span><span>Poste TX <span class="val ambre" id="o-ptx">—</span></span></div>
      <div class="avert" id="o-hors"></div>
      <label style="display:block;margin-top:8px"><input type="checkbox" id="o-cle" onchange="qPost('cle?on=' + (this.checked ? 1 : 0))"> Appliquer à la clé SDR</label>
      <div class="paire" style="margin-top:4px"><span>Clé RX <span class="val cyan" id="o-crx">—</span></span><span class="petit" id="o-cmsg2"></span></div>
      <div class="etat" id="o-statut"></div>
      <div class="petit" style="margin-top:6px">Les fréquences de gauche sont celles du ciel ; le poste travaille en fréquence intermédiaire, après les convertisseurs de la chaîne. Pas de Doppler ici : le poste n'est écrit que quand tu bouges. Tant que la case est cochée, le Doppler du satellite suivi est en pause.</div>
      <div class="petit" style="margin-top:4px">La clé se démarre dans l'onglet SDR ; elle suit alors cette fréquence, en BLU, et montre tout le transpondeur dans le panorama.</div>
    </div>
  </div>
  <div class="col3">
    <div class="carte">
      <div class="t">Calage sur la balise</div>
      <div id="o-balise" class="o-balise">—</div>
      <div class="ligne" style="margin-top:4px"><button id="o-bmesure" onclick="qCaleMesure()">Caler sur la mesure</button></div>
      <div class="petit">L'oscillateur d'un LNB dérive, surtout à froid. Accorde la balise médiane, lis où elle tombe vraiment, saisis-le : l'écart s'applique à toute la descente.</div>
      <div class="ligne"><span class="petit">Écart mémorisé</span><span class="val" id="o-calage">0 Hz</span></div>
      <div class="ligne"><input id="o-entendue" placeholder="MHz ciel" title="Où tombe vraiment la balise médiane, en MHz ciel : par exemple 10489,7523" style="max-width:170px"><button onclick="qCale()">Appliquer</button>
        <button onclick="qPost('calage-zero')">Remettre à zéro</button></div>
      <div class="etat" id="o-cmsg"></div>
    </div>
    <div class="carte">
      <div class="t">Transpondeur</div>
      <div class="ligne" style="margin-top:0" id="o-tps"></div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="o-balayage" onchange="qPost('balayage?on=' + (this.checked ? 1 : 0))"> Balayer tout QO-100 (écoute ; rien n'est émis là où le plan l'interdit)</label>
    </div>
    <div class="carte">
      <div class="t">Chaîne de station</div>
      <div class="ligne" style="margin-top:0"><select id="o-chaine" onchange="qPost('chaine?nom=' + enc(this.value))"></select>
        <input id="o-nouvelle" placeholder="Nouvelle chaîne…" style="max-width:150px"><button onclick="qNouvelle()">Ajouter</button></div>
      <div class="ligne"><span class="petit" style="min-width:95px">OL descente</span><select id="o-olrx" onchange="qOl('descente', this.value)"></select></div>
      <div class="ligne"><span class="petit" style="min-width:95px">OL montée</span><select id="o-oltx" onchange="qOl('montee', this.value)"></select></div>
      <div class="avert" id="o-beta"></div>
      <details style="margin-top:6px"><summary class="petit" style="cursor:pointer">Mesurer un oscillateur</summary>
        <div class="petit" style="margin-top:4px">Écoute un même signal sur une référence (WebSDR sur GPSDO) et sur ton poste ; recopie les deux fréquences : l'écart est l'oscillateur.</div>
        <div class="ligne"><select id="o-msens" style="max-width:120px"><option value="descente">Descente</option><option value="montee">Montée</option></select>
          <input id="o-mciel" placeholder="Référence (MHz)"><input id="o-mposte" placeholder="Poste (MHz)"><button onclick="qMesure()">Mesurer</button></div>
        <div class="etat" id="o-mmsg"></div>
      </details>
    </div>
    <div class="carte">
      <details><summary class="t" style="cursor:pointer;margin:0">Réglage initial · pointage et Soleil</summary>
        <div id="o-ciel" class="petit" style="margin-top:8px"></div>
      </details>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-sdr" hidden>
<div class="grille2" id="r-grille">
  <div>
    <div class="carte">
      <div class="t">Clé SDR (rtl_tcp) <span id="r-pastille" class="pastille off">arrêtée</span></div>
      <div class="ligne" style="margin-top:0"><button class="primaire" onclick="post('api/sdr/demarrer').then(ticSdr)">Démarrer</button>
        <button onclick="post('api/sdr/arreter').then(ticSdr)">Arrêter</button><span class="petit" id="r-info"></span></div>
      <div class="avert" id="r-erreur"></div>
    </div>
    <div class="carte">
      <div class="t">Écoute</div>
      <label><input type="checkbox" id="r-suit" onchange="rRegle('suit=' + (this.checked ? 1 : 0))"> Le satellite suivi <b id="r-sat"></b></label>
      <label style="display:block;margin-top:4px"><input type="checkbox" id="r-doppler" onchange="rRegle('doppler=' + (this.checked ? 1 : 0))"> Corriger le Doppler</label>
      <div class="ligne"><span class="petit">Sinon (MHz)</span><input id="r-mhz" style="max-width:130px" placeholder="145,800"><button onclick="rRegle('mhz=' + enc(v('r-mhz')))">Écouter</button></div>
    </div>
    <div class="carte">
      <div class="t">Démodulation</div>
      <div class="ligne" style="margin-top:0"><span class="petit" style="min-width:70px">Mode</span>
        <select id="r-mode" onchange="rRegle('mode=' + this.value)"><option value="AUTO">celui de l'émetteur</option><option>NFM</option><option>USB</option><option>LSB</option><option>AM</option></select></div>
      <div class="ligne"><span class="petit" style="min-width:70px">Largeur</span>
        <select id="r-bw" onchange="rRegle('bw=' + this.value)"><option value="0">celle du mode</option><option value="2400">2,4 kHz</option><option value="3000">3 kHz</option><option value="6000">6 kHz</option><option value="12000">12 kHz</option><option value="16000">16 kHz</option><option value="20000">20 kHz</option></select></div>
      <div class="ligne"><span class="petit" style="min-width:70px">Silencieux</span><input type="range" id="r-sq" min="-120" max="-20" style="flex:1" onchange="rRegle('squelch=' + this.value)"><span class="petit" id="r-sqv"></span></div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="r-deemph" onchange="rRegle('deemph=' + (this.checked ? 1 : 0))"> Désaccentuation (phonie FM ; à couper pour la SSTV et la télémesure)</label>
      <label style="display:block;margin-top:6px"><input type="checkbox" id="r-son" onchange="rRegle('son=' + (this.checked ? 1 : 0))"> Haut-parleur</label>
      <div class="petit" style="margin-top:4px">Le son de la clé va aussi aux décodeurs (onglets SSTV, APRS, FT8) et à l'enregistrement, à la place de la carte son.</div>
    </div>
    <div class="carte">
      <div class="t">La clé</div>
      <div class="ligne" style="margin-top:0"><span class="petit" style="min-width:70px">Gain</span><select id="r-gain" onchange="rRegle('gain=' + this.value)"></select>
        <label class="petit"><input type="checkbox" id="r-agc" onchange="rRegle('agc=' + (this.checked ? 1 : 0))"> CAG numérique</label></div>
      <div class="ligne"><span class="petit" style="min-width:70px">Erreur</span><input id="r-ppm" style="max-width:70px"><span class="petit">ppm</span><button onclick="rRegle('ppm=' + enc(v('r-ppm')))">Appliquer</button></div>
      <details style="margin-top:6px" id="r-liaison"><summary class="petit" style="cursor:pointer">Liaison rtl_tcp</summary>
        <div class="ligne"><input id="r-hote" placeholder="127.0.0.1" style="max-width:160px"><input id="r-port" placeholder="1234" style="max-width:80px">
          <button onclick="rRegle('hote=' + enc(v('r-hote')) + '&port=' + enc(v('r-port')))">Enregistrer</button></div>
        <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="r-lance" onchange="rRegle('lance=' + (this.checked ? 1 : 0))"> Lancer rtl_tcp sur ce PC (la clé y est branchée)</label>
        <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="r-bias" onchange="rRegle('bias=' + (this.checked ? 1 : 0))"> Alimenter l'antenne (bias-tee, préampli ou LNB ; clés qui l'ont)</label>
        <div class="petit" style="margin-top:4px">Une clé sur une autre machine : y lancer <code>rtl_tcp -a 0.0.0.0</code>, puis donner son adresse ici.</div>
      </details>
    </div>
  </div>
  <div class="carte r-droite">
    <div class="paire"><span class="o-freq" id="r-freq">—</span><span class="petit" id="r-detail"></span></div>
    <div class="ligne" style="margin-top:4px"><span class="petit">Niveau</span><div class="r-barre"><i id="r-niveau"></i></div><span class="petit" id="r-db"></span>
      <span class="rose petit" id="r-sat-av"></span>
      <span style="margin-left:auto" class="petit">Largeur vue</span><select id="r-span" style="flex:none" onchange="rSpan = +this.value; rCascade = []"><option value="12000">12 kHz</option><option value="24000">24 kHz</option><option value="48000" selected>48 kHz</option><option value="96000">96 kHz</option><option value="176400">176 kHz</option></select>
      <button onclick="post('api/sdr/pic')" title="Le signal le plus fort à ±15 kHz">Caler sur le pic</button><button onclick="post('api/sdr/decalage?hz=0')">Recentrer</button></div>
    <canvas id="r-spectre" style="width:100%;height:110px;display:block;margin-top:8px;cursor:crosshair" title="Cliquer pour s'y accorder"></canvas>
    <div class="r-cascade"><canvas id="r-cascade" title="Cliquer pour s'y accorder"></canvas></div>
  </div>
</div>
</div>
<div class="vue" id="v-sondes" hidden>
<div class="grille2" id="s2-grille">
  <div>
    <div class="carte">
      <div class="t">Radiosondes · clé SDR <span id="s2-pastille" class="pastille off">arrêtée</span></div>
      <div class="ligne" style="margin-top:0"><span class="o-freq" style="font-size:26px" id="s2-freq">—</span><span class="petit">MHz</span>
        <button onclick="sPost('pas?n=-1')">−10 k</button><button onclick="sPost('pas?n=1')">+10 k</button></div>
      <div class="ligne"><input id="s2-khz" placeholder="403 000 (kHz)" style="max-width:150px"><button onclick="sPost('frequence?khz=' + enc(v('s2-khz').replace(/\s/g, '')))">Aller</button>
        <span class="petit" style="margin-left:auto">Modèle</span><select id="s2-modele" style="flex:none" onchange="sPost('modele?m=' + this.value)"></select></div>
      <div class="ligne"><button class="primaire" onclick="sPost('demarrer')">Démarrer</button><button onclick="sPost('arreter')">Arrêter</button>
        <span class="petit" id="s2-info"></span></div>
      <div class="avert" id="s2-erreur"></div>
      <div class="petit" style="margin-top:6px">La clé reçoit en FM étroite, à la largeur du modèle, sans désaccentuation ni silencieux. Nommer le modèle resserre le filtre : quelques dB sur une sonde lointaine.</div>
    </div>
    <div class="carte">
      <div class="t">Réception</div>
      <div class="ligne" style="margin-top:0"><span class="petit" style="min-width:80px">Excursion</span><div class="r-barre"><i id="s2-exc"></i></div>
        <span class="petit" style="min-width:80px;margin-left:10px">Niveau</span><span class="petit" id="s2-niv"></span></div>
      <div class="ligne"><span class="petit" id="s2-trames"></span></div>
      <div class="ligne"><button onclick="sPost('recentre')">Recentrer sur le signal</button>
        <label class="petit"><input type="checkbox" id="s2-auto" onchange="sPost('auto?on=' + (this.checked ? 1 : 0))"> toutes les 2 s</label><span class="petit" id="s2-dec"></span></div>
    </div>
    <div class="carte">
      <div class="t">Lâchers près d'ici</div>
      <table><tbody id="s2-sites"></tbody></table>
      <div class="petit" style="margin-top:4px">Un clic sur une station règle sa fréquence. Heures des lâchers en UTC.</div>
    </div>
    <div class="carte">
      <div class="t">Journaux</div>
      <div style="max-height:160px;overflow:auto"><table><tbody id="s2-journaux"></tbody></table></div>
    </div>
  </div>
  <div class="carte s2-droite">
    <div id="s2-fix" class="petit">Aucune trame encore : démarre la réception sur la fréquence d'une sonde en vol.</div>
    <div class="ligne"><button onclick="sExporte(0)">GPX</button><button onclick="sExporte(1)">KML</button><button onclick="if (confirm('Oublier ce vol ?')) sPost('oublie')">Oublier le vol</button></div>
    <div class="s2-carte"><canvas id="s2-carte" title="Glisser, molette, double-clic pour recadrer"></canvas></div>
  </div>
</div>
</div>
<div class="vue" id="v-meteor" hidden>
<div class="grille2">
  <div>
    <div class="carte">
      <div class="t">METEOR-M · images LRPT <span id="m-pastille" class="pastille off">arrêté</span></div>
      <div class="petit" id="m-prochain"></div>
      <div class="ligne"><button class="primaire" onclick="mPost('demarrer')">Recevoir</button><button onclick="mPost('arreter')">Arrêter</button>
        <button onclick="mPost('enregistre')" title="Écrire ce qui est reçu, sans arrêter">Enregistrer maintenant</button></div>
      <div class="avert" id="m-erreur"></div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="m-auto" onchange="mPost('auto?on=' + (this.checked ? 1 : 0))"> Recevoir tout seul : la clé démarre une minute avant chaque passage METEOR (si elle est libre) et s'arrête après</label>
      <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="m-cotes" onchange="mPost('cotes?on=' + (this.checked ? 1 : 0))"> Tracer les côtes et les frontières</label>
      <div class="petit" style="margin-top:6px">La clé (onglet SDR) reçoit les 120 kHz du signal numérique sur 137,9 ou 137,1 MHz avec le Doppler ; rien à écouter. Les images sont écrites à l'arrêt, nord en haut.</div>
    </div>
    <div class="carte">
      <div class="t">Décodage</div>
      <div class="paire"><span>Synchro <b id="m-verrou">—</b></span><span>Qualité <b class="val" id="m-qualite">—</b></span><span class="petit" id="m-freq"></span></div>
      <div class="ligne"><span class="petit" id="m-trames"></span></div>
      <div class="ligne"><span class="petit" id="m-lignes"></span></div>
    </div>
    <div class="carte">
      <div class="t">Un enregistrement</div>
      <div class="petit">Le signal brut d'un passage (WAV I/Q, 8, 16 ou 32 bits, 150 à 3 000 kS/s), sur ce PC : son chemin.</div>
      <div class="ligne"><input id="m-chemin" placeholder="/home/…/meteor-m2-4-20260305.wav"><button onclick="mPost('fichier?chemin=' + enc(v('m-chemin')))">Décoder</button>
        <button onclick="mPost('annule')">Annuler</button></div>
      <progress id="m-prog" max="1" value="0" style="margin-top:6px" hidden></progress>
      <div class="petit" id="m-fichier"></div>
    </div>
  </div>
  <div class="carte m-droite">
    <div class="paire"><b id="m-titre">—</b><span class="petit" id="m-note"></span><a id="m-lien" style="margin-left:auto;color:var(--cyan)" download></a></div>
    <div class="m-image"><img id="m-img" alt=""></div>
    <div class="m-galerie" id="m-galerie"></div>
  </div>
</div>
</div>
<div class="vue" id="v-ft8" hidden>
<div class="grille2" style="grid-template-columns:minmax(380px,1fr) minmax(500px,1.5fr)">
  <div>
    <div class="carte">
      <div class="ligne" style="margin-top:0">
        <button class="primaire" id="8-marche" onclick="ft8Marche()">Écouter</button>
        <select id="8-mode" onchange="post('api/ft8?mode=' + this.value)" style="max-width:90px"><option>FT8</option><option>FT4</option></select>
        <button onclick="post('api/ft8?vide=1')">Effacer</button>
        <span class="petit" id="8-etat" style="margin-left:auto"></span>
      </div>
      <div style="height:6px;background:#0E1720;border-radius:3px;margin-top:10px;overflow:hidden"><div id="8-tranche" style="height:100%;width:0;background:var(--cyan)"></div></div>
      <canvas id="8-cascade" style="width:100%;height:260px;display:block;margin-top:10px;border-radius:6px;background:#06131A"></canvas>
      <canvas id="8-spectre" style="width:100%;height:60px;display:block;margin-top:2px"></canvas>
      <div class="petit" style="margin-top:6px">Le son du poste par la carte son (onglet Son). L'horloge du PC doit être à l'heure à la seconde près (NTP).</div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="ligne" style="margin-top:0"><input id="8-filtre" placeholder="Filtrer : indicatif, carré…">
        <label class="petit"><input type="checkbox" id="8-cq"> CQ seulement</label></div>
      <div style="max-height:calc(100vh - 190px);overflow:auto;margin-top:6px">
        <table><thead><tr><th>UTC</th><th>dB</th><th>DT</th><th>Hz</th><th>Message</th><th>km</th><th></th></tr></thead><tbody id="8-liste"></tbody></table>
      </div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-reglages" hidden>
<div class="reg"><nav class="reg-nav" id="reg-nav"></nav><div class="reg-corps" id="reg-corps"></div></div>
<div class="grille2" id="reg-origine">
  <div>
    <div class="carte" id="sv-carte" hidden>
      <div class="t">Sauvegarde générale</div>
      <div class="petit">Toute la station dans un fichier, pour la retrouver sur un autre PC ou après une réinstallation : réglages (poste, rotor, QRZ, carnet en ligne…), carnet, journal des passages, APRS et ses trophées, planches SSTV. Elle contient tes mots de passe et clés : garde-la pour toi.</div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="sv-medias" onchange="majSauvegarde()"> avec les enregistrements, images SSTV, METEOR et radiosondes (plus lourd)</label>
      <div class="ligne"><a class="bouton" id="sv-lien" href="sauvegarde.zip" download>⤓ Télécharger la sauvegarde</a><span class="petit" id="sv-taille"></span></div>
      <div class="t" style="margin-top:14px">Restaurer</div>
      <div class="petit">Choisis une sauvegarde : elle est reprise au prochain démarrage de SatMe-PC. Rien n'est perdu : les réglages remplacés sont gardés à part (dossier avant-restauration-…), le carnet est fusionné, les fichiers déjà là restent.</div>
      <div class="ligne"><input type="file" id="sv-fichier" accept=".zip,application/zip" onchange="restaureSauvegarde(this)"></div>
      <div class="etat" id="sv-etat"></div>
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
        <button id="cat-deco" onclick="post('api/cat/deconnecter')">Déconnecter</button>
      </div>
      <div class="avert" id="cat-avert"></div>
      <div class="paire" style="margin-top:8px">
        <span class="petit">Poste RX <span class="val" id="cat-dl">—</span></span>
        <span class="petit">TX <span class="val" id="cat-ul">—</span></span>
        <span class="petit" id="cat-mode"></span>
      </div>
      <div class="etat" id="cat-statut"></div>
      <div class="petit" id="cat-thd72-aide" hidden style="margin-top:6px">Le TH-D72 se présente comme « CP2102 USB to UART Bridge Controller » ; une fois connecté, SatMe le nomme « Kenwood TH-D72 » dans les listes. Sur ce câble : le CAT ou le KISS (onglet APRS), pas les deux à la fois.</div>
    </div>

    <div class="carte" id="thd72-carte" hidden>
      <div class="t">Kenwood TH-D72 <span class="petit" id="thd72-msg"></span></div>
      <table><tbody id="thd72-bandes"></tbody></table>
      <div class="ligne"><span class="petit">Émission sur la bande</span>
        <select id="thd72-tx" style="flex:none" onchange="thPost('bandetx?b=' + this.value)"><option value="0">A</option><option value="1">B</option></select>
        <button onclick="thPost('lire')">Relire</button></div>
      <div class="petit" style="margin-top:4px">Chaque changement part au poste quand tu le demandes ; la lecture ne change rien. Puissance : H haute, L basse, EL très basse.</div>
    </div>

    <div class="carte">
      <div class="t">Station</div>
      <div class="ligne" style="margin-top:0">
        <input id="indicatif" placeholder="Indicatif" style="text-transform:uppercase">
        <input id="locator" placeholder="Locator (JN18FS)" style="text-transform:uppercase">
      </div>
      <div class="ligne"><span class="petit">Passages au-dessus de</span><input id="minEl" style="max-width:60px"><span class="petit">° d'élévation</span></div>
      <div class="ligne"><button class="primaire" onclick="enregistreReglages()">Enregistrer</button></div>
      <div class="etat" id="reg-etat"></div>
    </div>
    <div class="carte">
      <div class="t">Skeds annoncés (hams.at)</div>
      <label class="petit"><input type="checkbox" id="sk-on" onchange="post('api/skeds/reglages?on=' + (this.checked ? 1 : 0))"> Montrer les skeds annoncés sur hams.at : sur les passages (📣), dans Station et le Carnet, sur la carte</label>
      <div class="ligne"><input id="sk-cle" type="password" placeholder="Clé API hams.at (facultative : la fenêtre commune avec toi)" autocomplete="off">
        <button onclick="post('api/skeds/reglages?cle=' + enc(v('sk-cle'))); g('sk-cle').value = ''">Enregistrer</button></div>
      <div class="petit">Comme sur le téléphone ; demandés toutes les 5 minutes. La clé reste sur ce PC.</div>
    </div>
    <div class="carte">
      <div class="t">Éléments orbitaux</div>
      <div class="petit">Serveur GP SatMe : il interroge AMSAT, CelesTrak et SatNOGS pour tout le monde, et ce PC lui demande tout. Comme sur le téléphone.</div>
      <div class="ligne"><input id="gpServeur" placeholder="https://gp.f4ioz.fr"><button onclick="essaiGp()">Essayer</button><button onclick="g('gpServeur').value = SRC.defaut; majSources()" id="gpDefaut">Par défaut</button></div>
      <div class="petit" id="gp-essai"></div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="gpSeul" onchange="majSources()"> Uniquement ce serveur : jamais AMSAT ni SatNOGS directement</label>
      <div class="petit" style="margin-top:10px">Sources :</div>
      <div id="gpSources" style="display:grid;grid-template-columns:1fr 1fr;gap:2px 12px;margin-top:4px"></div>
      <div class="ligne"><button class="primaire" onclick="enregistreSources()">Enregistrer et télécharger</button></div>
      <div class="etat" id="catalogue"></div>
    </div>
    <div class="carte" id="carteTelephone">
      <div class="t">Téléphone</div>
      <div class="petit">Reprendre les réglages du téléphone (Réglages → Sauvegarde, fichier .json) :</div>
      <input type="file" id="import" accept=".json,application/json" onchange="importe(this)" style="width:100%;margin-top:4px">
    </div>
  </div>
  <div>
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
        <button onclick="post('api/rotor/deconnecter')">Déconnecter</button>
        <label class="petit"><input type="checkbox" id="rot-suit" onchange="post('api/rotor/suivre?on='+(this.checked?1:0))"> Suivre le satellite</label>
      </div>
      <div class="ligne">
        <button onclick="post('api/rotor/parquer')">Parquer</button>
        <button class="rouge" onclick="post('api/rotor/stop')">Stop</button>
        <input id="rot-az" placeholder="az" style="max-width:70px"><input id="rot-el" placeholder="el" style="max-width:70px">
        <button onclick="post('api/rotor/aller?az='+enc(v('rot-az'))+'&el='+enc(v('rot-el')))">Aller</button>
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
      <div class="t">QRZ.com</div>
      <div class="ligne" style="margin-top:0"><input id="qrzUser" placeholder="Identifiant QRZ"><input id="qrzMdp" type="password" placeholder="Mot de passe"></div>
      <div class="petit">Abonnement XML de QRZ nécessaire. Le mot de passe reste sur ce PC et n'est jamais réaffiché.</div>
      <div class="ligne"><button onclick="enregistreReglages()">Enregistrer</button><button onclick="essaiQrz()">Essayer</button><span class="petit" id="qrz-etat"></span></div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="qrzFiche" onchange="post('api/reglages?fiche=' + (this.checked ? 1 : 0)).then(() => { ficheVoulue = this.checked; csPour = ''; })"> Fiche QRZ dans le carnet : photo, distance, carte</label>
    </div>
    <div class="carte">
      <div class="t">AMSAT Live OSCAR Status</div>
      <label class="petit" style="display:block"><input type="checkbox" id="amsatAuto" onchange="post('api/reglages?amsatAuto=' + (this.checked ? 1 : 0)).then(chargeReglages)">
        Signaler tout seul chaque passage où j'ai fait un contact : « Heard » sur la page publique d'amsat.org, à mon indicatif et mon carré, à l'heure du premier contact, une fois le passage fini</label>
      <div class="petit" id="amsatEtat" style="margin-top:6px"></div>
    </div>
    <div class="carte">
      <div class="t">Carnet en ligne (Wavelog / Cloudlog)</div>
      <div class="ligne" style="margin-top:0"><input id="carnetUrl" placeholder="https://wavelog.exemple.fr/"></div>
      <div class="ligne"><input id="carnetCle" type="password" placeholder="Clé d'API (lecture et écriture)"><input id="carnetProfil" type="hidden"></div>
      <div class="ligne"><button onclick="enregistreReglages().then(essaiCarnet)">Enregistrer</button><button onclick="essaiCarnet()" title="Demande au carnet en ligne ses profils de station">Lire les profils</button><span class="petit" id="carnet-essai"></span></div>
      <div id="wl-lieux" hidden style="margin-top:8px">
        <div class="ligne" style="margin-top:0"><span>Ici <b class="cyan" id="wl-ici"></b></span><span class="petit">→</span>
          <select id="carnetProfils" onchange="choisitProfil(this.value)" style="flex:1"></select></div>
        <div class="avert" id="wl-sans" hidden></div>
        <div class="petit" style="margin-top:8px">Chaque contact part au profil de l'endroit d'où il a été fait (un carré, ou les deux d'une ligne de carrés), comme sur le téléphone :</div>
        <table style="margin-top:4px"><tbody id="wl-table"></tbody></table>
        <div id="wl-creation" hidden style="margin-top:6px">
          <div class="ligne"><span class="petit">Pour créer : DXCC</span><input id="wl-dxcc" style="max-width:64px"><input id="wl-pays" placeholder="Pays" style="max-width:110px">
            <span class="petit">CQ</span><input id="wl-cq" style="max-width:50px"><span class="petit">ITU</span><input id="wl-itu" style="max-width:50px"></div>
          <div class="ligne"><button onclick="creeProfils()" id="wl-bcree"></button><span class="petit" id="wl-msg"></span></div>
        </div>
      </div>
      <label class="petit" style="display:block;margin-top:10px"><input type="checkbox" id="carnetAuto" onchange="post('api/reglages?carnetAuto=' + (this.checked ? 1 : 0)).then(chargeReglages)">
        Envoyer chaque nouveau contact au carnet en ligne</label>
      <div class="ligne" id="carnetDelaiL"><span class="petit">après</span><select id="carnetDelai" onchange="post('api/reglages?carnetDelai=' + this.value)"></select>
        <span class="petit">le temps de le corriger ou de le mettre en pause (⏸ dans le carnet). Seuls les contacts saisis après l'activation partent.</span></div>
    </div>
    <div class="carte">
      <div class="t">Convertisseurs</div>
      <div class="petit" id="conv-lu">—</div>
      <div class="petit" style="margin-top:6px">Ils se règlent dans l'onglet <a href="#qo100" onclick="vue('qo100')" style="color:var(--cyan)">QO-100</a>, par la chaîne de station : un seul endroit pour chaque oscillateur, comme sur le téléphone.</div>
    </div>
  </div>
</div>
</div>
<dialog id="visionneuse" onclick="if (event.target === this) this.close()">
  <img id="vi-img" alt="" onclick="g('visionneuse').close()">
  <div class="ligne" style="margin-top:6px"><button onclick="voisImage(viN - 1)" title="Précédente (←)">‹</button><button onclick="voisImage(viN + 1)" title="Suivante (→)">›</button>
    <span class="petit" id="vi-txt" style="flex:1"></span><a id="vi-lien" download><button>⤓ Enregistrer</button></a><button onclick="g('visionneuse').close()">Fermer</button></div>
</dialog>
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

// amsat.org's colours for its statuses, and their words.
const AMSAT = { ACTIVE: ['var(--vert)', 'entendu'], BEACON: ['var(--ambre)', 'balise seulement'], NOT_HEARD: ['var(--rose)', 'pas entendu'],
  CONFLICT: ['#fe6100', 'avis partagés'], UNKNOWN: ['var(--gris)', 'pas de nouvelles'] };
['f-fav', 'f-actifs'].forEach(i => {
  try { g(i).checked = localStorage.getItem(i) === '1'; } catch (x) {}
  g(i).onchange = () => { try { localStorage.setItem(i, g(i).checked ? '1' : '0'); } catch (x) {} dessinePassages(); };
});
function celluleAgenda(p) {
  return '<td><span title="' + (p.agenda ? 'Retirer de l’agenda' : 'Mettre à l’agenda') + '" style="cursor:pointer;opacity:' + (p.agenda ? 1 : 0.25) +
    '" onclick="agendaPassage(' + p.catnum + ',' + p.aos + ',event)">📅</span></td>';
}
async function agendaPassage(n, aos, ev) { if (ev) ev.stopPropagation(); await post('api/agenda/passage?catnum=' + n + '&aos=' + aos); chargeListe(); }
async function regle(k, d) { await post('api/sat/reglage?' + k + '=' + d); }
function jourHeure(ms) {
  const d = new Date(ms);
  return (utc() ? d.toISOString().slice(5, 10).split('-').reverse().join('/') : d.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit' })) + ' ' + hm(ms);
}
let agendaVu = '';
function dessineAgenda() {
  if (!E || !E.agenda) return;
  if (document.activeElement !== g('g-suivi')) g('g-suivi').checked = E.agendaSuivi;
  const cle = JSON.stringify(E.agenda);
  if (cle === agendaVu) return;
  agendaVu = cle;
  g('g-liste').innerHTML = E.agenda.map(a => '<tr' + (a.enCours ? ' class="encours"' : '') + '><td>' + jourHeure(a.de) + (a.a > a.de ? ' → ' + hm(a.a) : '') +
    '</td><td>' + esc(a.titre) + (a.type ? ' <span class="pastille off">' + esc(a.type) + '</span>' : '') + (a.note ? '<div class="petit">' + esc(a.note) + '</div>' : '') +
    '</td><td>' + esc(a.sat) + '</td><td class="act"><label class="petit"><input type="checkbox" ' + (a.actif ? 'checked' : '') +
    ' onchange="post(\'api/agenda/active?id=' + a.id + '\')"> actif</label> <button onclick="post(\'api/agenda/retire?id=' + a.id + '\')">✕</button></td></tr>').join('') ||
    '<tr><td colspan="4" class="petit">rien de prévu</td></tr>';
}
const hz = v => (v > 0 ? '+' : '') + v + ' Hz';
// Réglages by category: a list on the left (icon, name, what it holds), the chosen category's cards on the right.
const ICO = ${icones()};
const CATS_REG = [
  { id: 'station', nom: 'Ma station', ico: 'person', desc: 'Indicatif, carré, élévation minimale', cartes: ['Station'] },
  { id: 'materiel', nom: 'Poste et rotor', ico: 'radio', desc: 'CAT, TH-D72, rotor, convertisseurs', cartes: ['Poste', 'Kenwood TH-D72', 'Rotor', 'Convertisseurs'] },
  { id: 'satellites', nom: 'Satellites', ico: 'satellite_alt', desc: 'Éléments orbitaux, statut AMSAT', cartes: ['Éléments orbitaux', 'AMSAT'] },
  { id: 'enligne', nom: 'Services en ligne', ico: 'public', desc: 'QRZ.com, Wavelog / Cloudlog, skeds hams.at', cartes: ['QRZ.com', 'Carnet en ligne', 'Skeds'] },
  { id: 'telephone', nom: 'Téléphone', ico: 'smartphone', desc: 'SatMe sur le téléphone : réglages et contacts', cartes: ['Téléphone'] },
  { id: 'sauvegarde', nom: 'Sauvegarde', ico: 'save', desc: 'Toute la station dans un fichier, et la restaurer', cartes: ['Sauvegarde'] },
];
// The backup: its size, the link, a backup received waiting for the next start.
let SV = null;
async function majSauvegarde() {
  try { SV = await (await fetch('api/sauvegarde')).json(); } catch (x) { SV = null; }
  g('sv-carte').hidden = !(SV && SV.present);
  // A category whose cards are all hidden here (the page served by the phone) leaves the list.
  document.querySelectorAll('#reg-corps .reg-sec').forEach(sec => {
    const vide = ![...sec.querySelectorAll('.reg-cartes > .carte')].some(k => !k.hidden || k.id === 'thd72-carte');
    const b = document.querySelector('#reg-nav button[data-cat="' + sec.dataset.cat + '"]'); if (b) b.hidden = vide;
  });
  if (!SV || !SV.present) return;
  const m = g('sv-medias').checked;
  g('sv-lien').href = 'sauvegarde.zip' + (m ? '?medias=1' : '');
  txt('sv-taille', 'environ ' + ((m ? SV.tailleMedias : SV.taille) / 1048576).toFixed(1) + ' Mo');
  g('sv-etat').innerHTML = SV.attente ? 'Une sauvegarde du ' + new Date(SV.attente.date).toLocaleString() + ' sera restaurée au prochain démarrage de SatMe-PC. <button onclick="post(\'api/sauvegarde/annule\').then(majSauvegarde)">Annuler</button>' : '';
}
async function restaureSauvegarde(input) {
  const f = input.files[0]; if (!f) return;
  if (!confirm('Restaurer « ' + f.name + ' » au prochain démarrage de SatMe-PC ?')) { input.value = ''; return; }
  txt('sv-etat', 'envoi…');
  try {
    const j = await (await fetch('api/sauvegarde/restaure', { method: 'POST', body: f })).json();
    txt('sv-etat', j.ok ? j.fichiers + ' fichier(s) reçus. Arrête puis relance SatMe-PC pour les reprendre.' : 'Refusée : ' + j.erreur);
  } catch (x) { txt('sv-etat', 'Envoi impossible.'); }
  input.value = ''; majSauvegarde();
}
let catReg = 'station';
try { catReg = localStorage.getItem('reglages') || 'station'; } catch (x) {}
function rangeReglages() {
  const corps = g('reg-corps');
  if (!corps.childElementCount) {
    const cartes = [...document.querySelectorAll('#reg-origine .carte')];
    CATS_REG.forEach(c => {
      const sec = document.createElement('section'); sec.className = 'reg-sec'; sec.dataset.cat = c.id;
      sec.innerHTML = '<div class="reg-tete">' + (ICO[c.ico] || '') + '<div><div class="reg-nom">' + c.nom + '</div><div class="petit">' + c.desc + '</div></div></div><div class="reg-cartes"></div>';
      c.cartes.forEach(n => cartes.filter(k => { const t = k.querySelector('.t'); return t && t.textContent.trim().startsWith(n); })
        .forEach(k => sec.querySelector('.reg-cartes').appendChild(k)));
      corps.appendChild(sec);
    });
    // A card no category names still shows, under the last one.
    cartes.filter(k => k.closest('#reg-origine')).forEach(k => corps.lastChild.querySelector('.reg-cartes').appendChild(k));
    g('reg-origine').hidden = true;
    g('reg-nav').innerHTML = CATS_REG.map(c => '<button data-cat="' + c.id + '" onclick="montreReglages(\'' + c.id + '\')">' + (ICO[c.ico] || '') +
      '<span><b>' + c.nom + '</b><small>' + c.desc + '</small></span></button>').join('');
  }
  montreReglages(catReg);
}
function montreReglages(id) {
  if (!CATS_REG.some(c => c.id === id)) id = 'station';
  catReg = id; try { localStorage.setItem('reglages', id); } catch (x) {}
  document.querySelectorAll('#reg-corps .reg-sec').forEach(s => s.hidden = s.dataset.cat !== id);
  document.querySelectorAll('#reg-nav button').forEach(b => b.classList.toggle('actif', b.dataset.cat === id));
}
// The log's pass: hams.at's skeds announced for it, the contacts made during it.
function bandeauSkeds(id) {
  const b = g(id); if (!b) return;
  const l = (E && E.passageCarnet && E.passageCarnet.skeds) || [];
  b.hidden = !l.length;
  b.innerHTML = l.length ? '<b class="k">📣 SKED' + (l.length > 1 ? 'S' : '') + ' ANNONCÉ' + (l.length > 1 ? 'S' : '') + '</b>' + l.map(k =>
    (k.url ? '<a href="' + esc(k.url) + '" target="_blank" rel="noopener"><b>' + esc(k.call) + '</b></a>' : '<b>' + esc(k.call) + '</b>') +
    ' ' + hm(k.de) + '–' + hm(k.a) + (k.mode ? ' · ' + esc(k.mode) : '') + (k.carres ? ' · ' + esc(k.carres) : '') +
    (k.commun === false ? ' · <span style="color:var(--rose)">pas de fenêtre commune</span>' : '') +
    (k.note ? ' <span class="petit">« ' + esc(k.note.slice(0, 80)) + ' »</span>' : '')).join(' &nbsp;·&nbsp; ') : '';
}
function majPassageCarnet() {
  bandeauSkeds('cr-skeds');
  const pc = E && E.passageCarnet, l = (pc && pc.contacts) || [];
  g('cr-qso').innerHTML = l.length ? '<span class="petit">Ce passage :</span>' + l.map(c =>
    '<span class="q" title="' + hm(c.t) + (c.carre ? ' · ' + esc(c.carre) : '') + (c.mode ? ' · ' + esc(c.mode) : '') + '" onclick="g(\'c-call\').value=\'' + esc(c.call) +
    '\';g(\'c-call\').dispatchEvent(new Event(\'input\'))">' + esc(c.call) + '</span>').join('') : (pc ? '<span class="petit">Aucun contact sur ce passage pour l’instant.</span>' : '');
}
// The pass's contacts (pink) and the announced stations (amber ring) on a map, at their squares.
function pointsDuPassage(m) {
  const pc = E && E.passageCarnet; if (!pc) return;
  const x = m.x;
  for (const d of [-360, 0, 360]) {
    (pc.skeds || []).forEach(k => { if (k.lat == null) return;
      x.strokeStyle = '#FFB454'; x.lineWidth = 3; x.beginPath(); x.arc(m.px(k.lon + d), m.py(k.lat), 6, 0, 7); x.stroke();
      etiquetteCarte(m, k.lat, k.lon + d, '📣 ' + k.call, '#8A5300'); });
    (pc.contacts || []).forEach(c => { if (c.lat == null) return; pointCarte(m, c.lat, c.lon + d, '#FF5FA2', 5, c.call); });
  }
}
function etiquetteCarte(m, lat, lon, nom, coul) {
  const x = m.x;
  x.font = 'bold 12px system-ui'; x.textAlign = 'left'; x.lineWidth = 3; x.strokeStyle = 'rgba(255,255,255,.85)';
  x.strokeText(nom, m.px(lon) + 9, m.py(lat) + 14); x.fillStyle = coul; x.fillText(nom, m.px(lon) + 9, m.py(lat) + 14);
}
async function rapportAmsat(entendu) {
  if (!E || !E.suivi) return;
  if (!confirm((entendu ? 'Entendu' : 'Pas entendu') + ' : envoyer ce rapport sur la page publique d’amsat.org pour ' + E.suivi.nom + ', avec ton indicatif et ton carré ?')) return;
  const j = await post('api/amsat/rapport?entendu=' + entendu);
  if (j && j.ok) alert('Rapport envoyé à amsat.org (' + j.nom + ').');
}
async function favori(n, on, ev) { if (ev) ev.stopPropagation(); await post('api/favori?catnum=' + n + '&on=' + (on ? 1 : 0)); chargeListe(); }
function basculeFavori() { if (E && E.suivi) favori(E.suivi.catnum, !E.suivi.fav); }
function dessinePassages() {
  const f = g('filtre').value.trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
  const seulFav = g('f-fav').checked, seulActifs = g('f-actifs').checked;
  const n = Date.now();
  const suivi = E && E.suivi ? E.suivi.catnum : -1;
  const lignes = P.filter(p => (!f || p.nom.toUpperCase().replace(/[^A-Z0-9]/g, '').includes(f)) && (!seulFav || p.fav) &&
      (!seulActifs || p.amsat === 'ACTIVE')).slice(0, 150).map(p => {
    const a = AMSAT[p.amsat];
    const enCours = n >= p.aos && n <= p.los;
    const cl = 'p' + (enCours ? ' encours' : '') + (p.catnum === suivi ? ' choisi' : '');
    const quand = enCours ? 'en cours' : 'dans ' + duree(p.aos - n);
    return '<tr class="' + cl + '" title="Suivre ' + p.nom.replace(/"/g, '') + ' : le carnet, la carte, le poste et le rotor le suivent" onclick="suivre(' + p.catnum + ')">' + celluleAgenda(p) + '<td class="rosace">' + rosace(p.trace, p.elMax, 30) + '</td><td><span title="Favori" style="cursor:pointer;color:' + (p.fav ? 'var(--ambre)' : 'var(--bord)') +
      '" onclick="favori(' + p.catnum + ',' + !p.fav + ',event)">★</span></td><td>' + hm(p.aos) + '</td><td>' +
      (a ? '<span title="AMSAT : ' + a[1] + '" style="display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px;background:' + a[0] + '"></span>' : '') +
      p.nom.replace(/</g, '&lt;') + (p.skeds ? ' <span title="' + p.skeds + ' sked(s) annoncé(s) sur hams.at" style="color:var(--ambre)">📣' + (p.skeds > 1 ? p.skeds : '') + '</span>' : '') +
      '</td><td>' + Math.round(p.elMax) + '°</td><td>' + duree(p.los - p.aos) + '</td><td class="petit">' + quand + '</td></tr>';
  });
  g('passages').innerHTML = lignes.join('') || '<tr><td colspan="8" class="petit">' + (E && E.locator ? 'aucun passage' : 'renseigne ton locator') + '</td></tr>';
}
async function suivre(n) { await post('api/suivre?catnum=' + n); }
// The carnet's satellite: the one followed, then the passes under way and to come (the Station list's), once each.
function menuSatCarnet() {
  const m = g('c-satsel'); if (!m || document.activeElement === m) return;
  const n = Date.now(), vus = new Set(), l = [];
  if (E && E.suivi) { vus.add(E.suivi.catnum); l.push([E.suivi.catnum, E.suivi.nom + ' (suivi)']); }
  (P || []).filter(p => p.los > n).sort((a, b) => a.aos - b.aos).forEach(p => {
    if (vus.has(p.catnum) || l.length >= 40) return; vus.add(p.catnum);
    l.push([p.catnum, p.nom + ' · ' + (n >= p.aos ? 'en cours, El max ' + Math.round(p.elMax) + '°' : hm(p.aos) + ', El max ' + Math.round(p.elMax) + '°')]);
  });
  const h = (E && E.suivi ? '' : '<option value="">— choisir un satellite —</option>') + l.map(x => '<option value="' + x[0] + '">' + esc(x[1]) + '</option>').join('');
  if (m.dataset.vu !== h) { m.dataset.vu = h; m.innerHTML = h; }
  m.value = E && E.suivi ? String(E.suivi.catnum) : '';
}
let tous = [];
async function chargeSatellites() {
  try { tous = await (await fetch('api/satellites')).json(); } catch (x) { tous = []; }
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
async function repos(d) { await post('api/repos?d=' + d); }

/**
 * A pass's arc in the sky, small, as beside each pass on the phone: zenith in
 * the middle, the horizon round it, 30° and 60° rings, north up; the arc in
 * the colour of its height (cyan from 50°, green from 25°), its AOS dot and
 * an arrow at its middle for the way it goes.
 */
function rosace(t, elMax, px) {
  if (!t || t.length < 2) return '';
  const c = elMax >= 50 ? '#3FE0C8' : elMax >= 25 ? '#5BD68A' : '#9AA7B4';
  const xy = (az, el) => { const r = 46 * (1 - Math.max(0, Math.min(90, el)) / 90), a = az * Math.PI / 180; return [50 + r * Math.sin(a), 50 - r * Math.cos(a)]; };
  const pts = t.map(p => xy(p[0], p[1]));
  const m = Math.max(1, Math.floor(pts.length / 2)), A = pts[m - 1], B = pts[m];
  const ang = Math.atan2(B[1] - A[1], B[0] - A[0]), s = 9;
  const pt = (a, d) => (B[0] + d * Math.cos(a)).toFixed(1) + ',' + (B[1] + d * Math.sin(a)).toFixed(1);
  return '<svg viewBox="0 0 100 100" width="' + px + '" height="' + px + '" style="display:block">' +
    '<circle cx="50" cy="50" r="46" fill="#101824" stroke="#2A3647" stroke-width="3"/>' +
    '<circle cx="50" cy="50" r="30.7" fill="none" stroke="#2A3647" stroke-width="1.5" opacity=".6"/>' +
    '<circle cx="50" cy="50" r="15.3" fill="none" stroke="#2A3647" stroke-width="1.5" opacity=".6"/>' +
    '<line x1="50" y1="4" x2="50" y2="14" stroke="#2A3647" stroke-width="3"/>' +
    '<polyline fill="none" stroke="' + c + '" stroke-width="5" stroke-linejoin="round" stroke-linecap="round" points="' + pts.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ') + '"/>' +
    '<circle cx="' + pts[0][0].toFixed(1) + '" cy="' + pts[0][1].toFixed(1) + '" r="6" fill="' + c + '"/>' +
    '<polygon points="' + pt(ang, s) + ' ' + pt(ang + 2.5, s) + ' ' + pt(ang - 2.5, s) + '" fill="' + c + '" stroke="#fff" stroke-opacity=".5" stroke-width="1.5"/></svg>';
}
function polaire(az, el) {
  const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90);
  const a = az * Math.PI / 180;
  return [r * Math.sin(a), -r * Math.cos(a)];
}
/** The sky (SVG inside, -110..110): the pass's arc, the rotor, the satellite now. */
function cielSvg() {
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
  return s;
}
function dessineCiel() { g('ciel').innerHTML = cielSvg(); dessineStationDirect(); }

function dessine() {
  if (!E) return;
  const n = Date.now();
  txt('heure', hm(n, true) + (utc() ? ' UTC' : ''));
  txt('info-station', [E.indicatif, E.locator].filter(x => x).join(' · ') + ' · ' + E.nbSatellites + ' satellites');
  txt('calcul', E.calcul ? 'calcul des passages…' : '');
  txt('catalogue', E.catalogue);
  if (document.activeElement !== g('indicatif') && !g('indicatif').value) g('indicatif').value = E.indicatif || '';
  if (document.activeElement !== g('locator') && !g('locator').value) g('locator').value = E.locator || '';
  txt('nom', E.suivi ? E.suivi.nom : '— choisis un passage');
  g('fav').style.display = E.suivi ? '' : 'none';
  txt('fav', E.suivi && E.suivi.fav ? '★' : '☆'); g('fav').style.color = E.suivi && E.suivi.fav ? 'var(--ambre)' : 'var(--gris)';
  const am = E.suivi && E.suivi.amsat;
  txt('amsat', am ? (AMSAT[am.statut] || AMSAT.UNKNOWN)[1] + (am.mode ? ' (' + am.mode + ')' : '') + ' · ' + am.actifs : 'pas de statut');
  g('amsat').title = am ? am.actifs + ' rapport(s) « entendu » sur amsat.org ces derniers jours' : 'amsat.org n’a pas de statut pour ce satellite';
  txt('amsat-auto', E.amsatAuto ? 'auto ✓' : 'auto ✕');
  g('amsat-auto').dataset.etat = E.amsatEtat || '';
  g('amsat-auto').title = (E.amsatAuto ? 'Signalement automatique à amsat.org activé' + (E.amsatEtat ? ' : ' + E.amsatEtat : '') : 'Signalement automatique à amsat.org coupé') + ' (Réglages)';
  txt('c-sat', E.suivi ? '· ' + E.suivi.nom + (E.position ? ' · El ' + Math.round(E.position.el) + '°' : '') : '· choisis le satellite ci-dessous');
  menuSatCarnet();
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
  dessineCarteStation();
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
  const rs = E.reglageSat;
  if (rs) {
    txt('r-calib', hz(rs.calib)); txt('r-tx', hz(rs.tx));
    txt('r-ref', rs.refCalib == null ? 'pas de référence' : 'réf. RX ' + hz(rs.refCalib) + ' · TX ' + hz(rs.refTx));
    g('r-retour').disabled = rs.refCalib == null;
  }
  const sv = JSON.stringify(E.passagesSuivi.map(p => [p.aos, p.agenda]));
  if (sv !== g('suivants').dataset.vu) {
    g('suivants').dataset.vu = sv;
    g('suivants').innerHTML = E.passagesSuivi.map(p => '<tr>' + celluleAgenda(p) + '<td class="rosace">' + rosace(p.trace, p.elMax, 40) + '</td><td>' + jourHeure(p.aos) + '</td><td>' + Math.round(p.elMax) + '°</td><td>' +
      duree(p.los - p.aos) + '</td><td class="petit">' + Math.round(p.azAos) + '° → ' + Math.round(p.azLos) + '°</td></tr>').join('');
  }
  dessineAgenda();
  bandeauSkeds('st-skeds');
  if (document.activeElement !== g('sk-on')) g('sk-on').checked = !!E.skedsOn;
  // CAT
  const c = E.cat;
  // Station shows the rig and the mast by name, a green tick when connected; their settings are in Réglages.
  const om = [...g('cat-modele').options].find(o => o.value === c.modele);
  txt('st-poste', om ? om.text.replace(/ \(rien n'est ouvert\)/, '') : c.modele);
  g('st-poste-ok').className = 'coche ' + (c.connecte ? 'on' : 'off');
  txt('st-poste-ok', c.connecte ? '✓' : 'non connecté');
  g('cat-pastille').className = 'pastille ' + (c.connecte ? 'on' : 'off');
  txt('cat-pastille', c.connecte ? (c.simule ? 'simulé' : 'connecté') : 'déconnecté');
  txt('cat-statut', c.statut);
  txt('cat-statut-court', c.connecte ? 'CAT ' + c.modele + (c.simule ? ' (simulé)' : '') : '');
  txt('cat-dl', mhz(c.dl)); txt('cat-ul', mhz(c.ul));
  txt('cat-mode', (c.mode || '') + (c.modeEcart ? ' (corrigé)' : '') + (c.connecte ? (c.pilote ? ' · Doppler' : ' · bouton') : ''));
  g('maintien').checked = c.maintien;
  // Rotor
  const r = E.rotor;
  const rl = { GS232: 'GS-232', ROTCTLD: 'rotctld' }[r.lien] || r.lien || '—';
  txt('st-rotor', r.simule ? 'simulé' : rl);
  g('st-rotor-ok').className = 'coche ' + (r.connecte ? 'on' : 'off');
  txt('st-rotor-ok', r.connecte ? '✓' : 'non connecté');
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
  if (E.convertisseurs) {
    const k = E.convertisseurs, ol = x => x ? (x / 1e6).toFixed(3) + ' MHz' : 'aucun';
    txt('conv-lu', 'Réception : ' + ol(k.rxOl) + (k.rxInv ? ' (inverseur)' : '') + ' · émission : ' + ol(k.txOl) + (k.txInv ? ' (inverseur)' : ''));
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
function reglagesRotor() {
  const b = id => g(id).checked ? 1 : 0;
  post('api/rotor/reglages?maxAz=' + g('r-maxAz').value + '&maxEl=' + g('r-maxEl').value + '&butee=' + g('r-butee').value +
       '&depuisButee=' + b('r-depuisButee') + '&flip=' + b('r-flip') + '&azSeul=' + b('r-azSeul') +
       '&parkAz=' + enc(v('r-parkAz')) + '&parkEl=' + enc(v('r-parkEl')) + '&minEl=' + enc(v('r-minEl')) +
       '&ecart=' + enc(v('r-ecart')) + '&avantAos=' + enc(v('r-avantAos')));
  g('rot-reg').open = false;
}
async function importe(input) {
  const f = input.files[0]; if (!f) return;
  try {
    const r = await fetch('api/import', { method:'POST', body: await f.text() });
    const j = await r.json();
    alert(j.ok ? j.n + ' réglages repris du téléphone (ports USB exceptés ; le rotor attend que tu coches « Suivre »), ' +
      j.contacts + ' contact(s) ajouté(s) au carnet.' : 'Import impossible : ' + j.erreur);
  } catch (x) { alert('Import impossible.'); }
  input.value = ''; g('indicatif').value = ''; g('locator').value = '';
  setTimeout(() => { tic(); chargeListe(); }, 1500);
}

async function tic() {
  try { E = await (await fetch('api/etat')).json(); dessine(); } catch (x) {}
}
async function chargeListe() {
  try { P = (await (await fetch('api/passages')).json()).liste; dessinePassages(); } catch (x) {}
}
async function chargePorts() {
  try { ports = await (await fetch('api/ports')).json(); } catch (x) { ports = []; }
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
  post('api/cat/connecter?modele=' + m + '&port=' + enc(g('cat-port').value) + '&baud=' + g('cat-baud').value +
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
  post('api/rotor/connecter?lien=' + (l === 'ROTCTLD' ? 'ROTCTLD' : 'GS232') + '&sim=' + (l === 'SIM' ? 1 : 0) +
       '&port=' + enc(g('rot-port').value) + '&baud=' + g('rot-baud').value + '&hote=' + enc(v('rot-hote')) + '&tcp=' + enc(v('rot-tcpport')));
}
g('emetteur').onchange = () => post('api/emetteur?i=' + g('emetteur').value);
// ------------------------------------------------------------------ tabs
function vue(n) {
  document.querySelectorAll('.vue').forEach(x => x.hidden = x.id !== 'v-' + n);
  document.querySelectorAll('#onglets button').forEach(b => b.classList.toggle('actif', b.dataset.v === n));
  try { localStorage.setItem('vue', n); } catch (x) {}
  if (n === 'carnet') { chargeCarnet(); g('c-call').focus(); vueCarnet(vueC); }
  if (n === 'reglages') { rangeReglages(); majSauvegarde(); }
  if (n === 'reglages') { chargeReglages().then(() => { if (!wlLu && v('carnetUrl')) { wlLu = true; essaiCarnet(); } }); chargeSources(); }
  if (n === 'son' || n === 'sstv') ticSon();
  if (n === 'agenda') ticVeille();
  if (n === 'aprs') ticAprs();
  if (n === 'ft8') ticFt8();
  if (n === 'carres') chargeCarres();
  if (n === 'frise') chargeFrise();
  if (n === 'sked') { prepareSked(); setTimeout(dessineSked); }
  if (n === 'qo100') chargeQo100(true);
  if (n === 'datv') ticDatv(true);
  if (n === 'sdr') ticSdr();
  if (n === 'sondes') { SV2.libre = false; ticSondes(); }
  if (n === 'meteor') ticMeteor();
}
document.querySelectorAll('#onglets button[data-v]').forEach(b => b.onclick = () => vue(b.dataset.v));

// ------------------------------------------------------------------ log
let C = [], minuteurQrz = 0, quiPour = '', editT = 0;
const esc = x => String(x == null ? '' : x).replace(/&/g, '&amp;').replace(/</g, '&lt;');
const base = c => c.toUpperCase().split('/')[0];
async function chargeCarnet() {
  try {
    const j = await (await fetch('api/carnet')).json();
    C = j.contacts;
    txt('c-nb', String(j.total)); txt('c-auto', j.auto || ''); txt('c-carres', String(j.carres)); txt('c-attente', String(j.nonDeposes));
    dessineCarnet();
  } catch (x) {}
}
function dessineCarnet() {
  const f = v('c-filtre').toUpperCase();
  const tous = C.filter(e => !f || [e.c, e.l, e.sat, e.nm, e.qth].join(' ').toUpperCase().includes(f));
  // A thousand lines at most: beyond that, the filter finds faster than the eye.
  const reste = tous.length > 1000 ? '<tr><td colspan="8" class="petit">… et ' + (tous.length - 1000) + ' autres : filtre pour les voir</td></tr>' : '';
  vusCarnet = tous.slice(0, 1000).map(e => e.t);
  g('c-liste').innerHTML = tous.slice(0, 1000).map(e =>
    '<tr><td><input type="checkbox" class="c-coche" data-t="' + e.t + '"' + (coches.has(e.t) ? ' checked' : '') + ' onchange="coche(' + e.t + ', this.checked)"></td><td style="white-space:nowrap">' + new Date(e.t).toISOString().slice(0, 16).replace('T', ' ') + '</td><td><b>' + esc(e.c) + '</b>' + (e.nm ? '<div class="c-nm" title="' + esc(e.nm).replace(/"/g, '&quot;') + '">' + esc(e.nm) + '</div>' : '') + '</td><td>' + esc(e.l) +
    '</td><td>' + esc(e.sat) + '</td><td>' + esc(e.md) + '</td><td>' + esc(e.rs) + '/' + esc(e.rr) +
    '</td><td class="act">' + envoiCarnet(e) +
    '<button title="Modifier" onclick="edite(' + e.t + ')">✎</button> <button title="Supprimer ce contact" onclick="supprimeContacts([' + e.t + '])">🗑</button></td></tr>').join('') + reste ||
    '<tr><td colspan="8" class="petit">aucun contact</td></tr>';
  majSelection();
}
// Online: sent (✓), waiting for its delay (⏱ and the pause), held back (▶ to release), or to send by hand (⬆).
function envoiCarnet(e) {
  if (e.envoye) return '<span class="vert" title="déposé au carnet en ligne">✓</span> ';
  if (e.ea === 'attente') return '<span class="petit" id="ea-' + e.t + '" title="part au carnet en ligne à la fin du décompte">⏱ ' + e.reste + ' s</span> <button title="Mettre en pause : il ne partira pas" onclick="retiens(' + e.t + ', 1)">⏸</button> ';
  if (e.pause) return '<button title="' + esc(e.refus ? 'Refusé : ' + e.refus + ' — reprendre' : 'En pause — reprendre l’envoi') + '" onclick="retiens(' + e.t + ', 0)" style="color:var(--ambre)">▶</button> ';
  return '<button title="Déposer au carnet en ligne" onclick="depose(' + e.t + ')">⬆</button> ';
}
// The countdowns, each second, without redrawing the list; reloaded once one is over (the contact left).
setInterval(() => {
  if (g('v-carnet').hidden || !C.length) return;
  let fini = false;
  C.forEach(e => { if (e.ea !== 'attente') return; e.reste = Math.max(0, e.reste - 1); const s = g('ea-' + e.t); if (s) s.textContent = '⏱ ' + e.reste + ' s'; if (e.reste === 0) fini = true; });
  if (fini) { C.forEach(e => { if (e.ea === 'attente' && e.reste === 0) e.ea = 'pret'; }); setTimeout(chargeCarnet, 3000); }
}, 1000);
async function retiens(t, on) { await post('api/carnet/retiens?t=' + t + '&on=' + on); chargeCarnet(); }
// The contacts ticked, to delete at once; kept while the filter changes.
const coches = new Set();
let vusCarnet = [];
function coche(t, on) { if (on) coches.add(t); else coches.delete(t); majSelection(); }
function cocheTout(on) { vusCarnet.forEach(t => on ? coches.add(t) : coches.delete(t)); dessineCarnet(); }
function majSelection() {
  [...coches].forEach(t => { if (!C.some(e => e.t === t)) coches.delete(t); });
  // The selection: exported (ADIF, or the phone's batch) or deleted.
  g('c-sel').hidden = coches.size === 0;
  g('c-sel').style.display = coches.size ? 'flex' : 'none';
  txt('c-sel-n', coches.size + ' coché' + (coches.size > 1 ? 's' : '') + ' :');
  const ts = [...coches].join(',');
  g('c-sel-adif').href = 'api/carnet/adif?t=' + ts; g('c-sel-lot').href = 'api/carnet/lot?t=' + ts;
  g('c-tout').checked = vusCarnet.length > 0 && vusCarnet.every(t => coches.has(t));
}
async function supprimeContacts(l) {
  const qui = l.length === 1 ? (C.find(e => e.t === l[0]) || {}).c || 'ce contact' : l.length + ' contacts';
  if (!confirm('Supprimer ' + qui + ' du carnet ?\n\nCeux déjà déposés au carnet en ligne y restent.')) return false;
  await post('api/carnet/supprime?t=' + l.join(','));
  l.forEach(t => coches.delete(t)); chargeCarnet(); return true;
}
function supprimeSelection() { if (coches.size) supprimeContacts([...coches]); }
g('c-filtre').oninput = dessineCarnet;
// QRZ after 0.6 s without a key, as on the phone and the desk: a callsign the log does not know by name.
// ------------------------------------------------------------------ SatMe on the phone
function bilanTel(b) {
  if (!b.ok) return b.erreur || 'échec';
  return b.sat + ' ' + (b.de ? jourHeure(b.de) : '') + ' : ' + b.contacts + ' contact(s) au carnet' + (b.dejaLa ? ' (' + b.dejaLa + ' déjà là)' : '') +
    ' · ' + b.images + ' image(s) · ' + (b.sons ? b.sons + ' son(s)' : 'sans son');
}
async function importeZips(input) {
  const l = [...input.files]; if (!l.length) return;
  const out = [];
  for (let i = 0; i < l.length; i++) {
    txt('t-etat', 'import ' + (i + 1) + '/' + l.length + ' : ' + l[i].name + '…');
    let b = null;
    try { b = await (await fetch('api/telephone/zip?enLigne=1', { method: 'POST', body: l[i] })).json(); } catch (x) { b = { ok: false, erreur: 'envoi impossible' }; }
    out.push(l[i].name + ' — ' + bilanTel(b));
  }
  g('t-etat').innerHTML = out.map(esc).join('<br>'); input.value = ''; chargeCarnet();
}
function ouvreTelephone() {
  try { g('t-adresse').value = localStorage.getItem('tel-adresse') || ''; } catch (x) {}
  g('t-qr-boite').hidden = false;
  g('t-dlg').showModal();
  montreQr();
}
// The QR code: drawn by the PC with its address and a fresh token; the phone scanning it joins, and the list comes.
let minuteurQr = 0, qrDepuis = 0, qrPort = '';
async function montreQr(ip) {
  clearTimeout(minuteurQr);
  let j = null;
  try { j = await (await fetch('api/telephone/qr' + (ip ? '?adresse=' + enc(ip) : ''), { method: 'POST' })).json(); } catch (x) {}
  if (!j || !j.ok) { g('t-qr').innerHTML = ''; txt('t-qr-etat', j && j.erreur ? j.erreur : 'QR code impossible'); g('t-main').open = true; return; }
  g('t-qr').innerHTML = j.svg;
  const s = g('t-ip'); s.innerHTML = j.adresses.map(a => '<option>' + esc(a) + '</option>').join(''); s.value = j.ip;
  s.parentElement.hidden = j.adresses.length < 2;
  txt('t-qr-etat', 'en attente du téléphone…');
  qrDepuis = Date.now(); qrPort = j.url.split(':')[2].split('/')[0];
  suisQr();
}
async function suisQr() {
  if (!g('t-dlg').open) { post('api/telephone/qr/ferme'); return; }
  let e = null;
  try { e = await (await fetch('api/telephone/qr')).json(); } catch (x) {}
  if (e && e.joint && e.connecte) { g('t-qr-boite').hidden = true; await listeTelephone(); return; }
  if (e && e.erreur) txt('t-qr-etat', e.erreur);
  // Nothing after half a minute: most often this PC's firewall, which the phone cannot get through.
  else if (Date.now() - qrDepuis > 30000) txt('t-qr-etat', 'toujours rien… Si le téléphone dit « SatMe-PC injoignable », le pare-feu de ce PC bloque le port ' + qrPort +
    ' (Linux avec ufw : sudo ufw allow from 192.168.0.0/16 to any port ' + qrPort + ' proto tcp).');
  if (e && !e.ouvert) return;
  minuteurQr = setTimeout(suisQr, 1000);
}
g('t-dlg').addEventListener('close', () => { clearTimeout(minuteurQr); post('api/telephone/qr/ferme'); });
async function connecteTelephone() {
  txt('t-cnx', 'connexion…');
  try { localStorage.setItem('tel-adresse', v('t-adresse')); } catch (x) {}
  const j = await post('api/telephone/connecte?adresse=' + enc(v('t-adresse')) + '&code=' + enc(v('t-code')));
  if (!j || j.ok === false) { txt('t-cnx', j && j.erreur ? j.erreur : 'échec'); return; }
  clearTimeout(minuteurQr); post('api/telephone/qr/ferme'); g('t-qr-boite').hidden = true;
  await listeTelephone();
}
async function listeTelephone() {
  let l = null;
  try { l = await (await fetch('api/telephone/liste')).json(); } catch (x) {}
  if (!l || !l.ok) { txt('t-cnx', l && l.erreur ? l.erreur : 'liste illisible'); return; }
  txt('t-cnx', 'connecté à ' + l.adresse + (l.version ? ' (SatMe ' + l.version + ')' : '') + ' · ' + l.passages.length + ' passage(s)');
  g('t-liste-boite').hidden = false;
  g('t-liste').innerHTML = l.passages.map(p => '<tr><td><input type="checkbox" class="t-coche" value="' + esc(p.id).replace(/"/g, '') + '"' + (p.q || p.i ? ' checked' : '') + '></td><td>' +
    jourHeure(p.de) + '</td><td>' + esc(p.sat) + '</td><td>' + p.el + '°</td><td>' + duree(p.a - p.de) + '</td><td class="petit">' +
    [p.q ? p.q + ' QSO' : '', p.i ? p.i + ' SSTV' : '', p.r ? p.r + ' APRS' : '', p.son ? '♪' : ''].filter(x => x).join(' · ') + '</td></tr>').join('') ||
    '<tr><td colspan="6" class="petit">aucun passage dans le journal du téléphone</td></tr>';
}
async function rapatrie() {
  const ids = [...document.querySelectorAll('.t-coche')].filter(c => c.checked).map(c => c.value);
  if (!ids.length) return;
  const j = await post('api/telephone/rapatrie?enLigne=' + (g('t-enligne').checked ? 1 : 0) + '&ids=' + enc(ids.join(',')));
  if (!j || j.ok === false) return;
  const suit = async () => {
    let e = null;
    try { e = await (await fetch('api/telephone/etat')).json(); } catch (x) { return; }
    txt('t-prog', e.enCours ? 'import ' + e.fait + '/' + e.total + '…' : 'terminé : ' + e.total + ' passage(s)');
    g('t-bilan').innerHTML = e.bilans.map(b => esc(bilanTel(b))).join('<br>');
    if (e.enCours) setTimeout(suit, 800); else chargeCarnet();
  };
  suit();
}

// ------------------------------------------------------------------ the log's live station
// While naming the stations of a pass, the pass in sight: where the satellite is, the sky,
// the frequencies, where in the transponder, the mast, the recording. Contacts on a toggle.
let vueC = 'station';
try { vueC = localStorage.getItem('vue-carnet') === 'contacts' ? 'contacts' : 'station'; } catch (x) {}
function vueCarnet(q) {
  vueC = q;
  try { localStorage.setItem('vue-carnet', q); } catch (x) {}
  g('cr-station').hidden = q !== 'station'; g('cr-contacts').hidden = q !== 'contacts';
  g('cv-station').classList.toggle('actif', q === 'station'); g('cv-contacts').classList.toggle('actif', q === 'contacts');
  if (q === 'station') dessineStationDirect();
}
function dessineStationDirect() {
  if (!E || g('v-carnet').hidden || vueC !== 'station') return;
  const n = Date.now(), p = E.position, pa = E.passage;
  txt('cr-sat', E.suivi ? E.suivi.nom : '— aucun satellite suivi (onglet Station)');
  txt('cr-az', p ? p.az.toFixed(1) + '°' : '—'); txt('cr-el', p ? p.el.toFixed(1) + '°' : '—');
  g('cr-el').style.color = p && p.el >= 0 ? '' : 'var(--gris)';
  txt('cr-dist', p ? Math.round(p.distance) + ' km · ' + (p.vitesse > 0 ? 's’éloigne ' : 's’approche ') + Math.abs(p.vitesse).toFixed(2) + ' km/s' : '');
  const enCours = pa && n >= pa.aos && n <= pa.los;
  txt('cr-compte', pa ? (enCours ? 'LOS dans ' + duree(pa.los - n) : 'AOS dans ' + duree(pa.aos - n)) : '');
  majPassageCarnet();
  txt('cr-passage', pa ? 'AOS ' + hm(pa.aos) + ' (' + Math.round(pa.azAos) + '°) · El max ' + Math.round(pa.elMax) + '° · LOS ' + hm(pa.los) + ' (' + Math.round(pa.azLos) + '°)' : '');
  // The pass's elevation profile, now on it.
  const cle = pa ? pa.aos + ':' + pa.los : '';
  if (g('cr-profil').dataset.vu !== cle) {
    g('cr-profil').dataset.vu = cle;
    const t = pa && pa.trace ? pa.trace : [];
    g('cr-profil').innerHTML = t.length > 1 ? '<svg viewBox="0 0 100 34" preserveAspectRatio="none" style="width:100%;height:100%"><polygon fill="rgba(63,224,200,.25)" stroke="#3FE0C8" stroke-width=".6" points="0,34 ' +
      t.map((q, i) => (i * 100 / (t.length - 1)).toFixed(2) + ',' + (34 - 32 * Math.max(0, q[1]) / 90).toFixed(2)).join(' ') + ' 100,34"/></svg>' : '';
  }
  const f = pa ? Math.max(0, Math.min(1, (n - pa.aos) / (pa.los - pa.aos))) : 0;
  g('cr-curseur').style.left = (f * 100) + '%'; g('cr-curseur').style.display = pa && enCours ? '' : 'none';
  g('cr-ciel').innerHTML = cielSvg();
  // Frequencies: the sky's (Doppler in), the rig's.
  const tr = E.emetteurs[E.emetteur];
  // The transmitters, to choose from here too (a beacon, a transponder…).
  const sel = g('cr-tx'), opts = (E.emetteurs || []).map((x, i) => '<option value="' + i + '">' + esc(x.desc + (x.mode ? ' · ' + x.mode : '')) + '</option>').join('') || '<option>aucun émetteur connu</option>';
  if (sel.dataset.vu !== opts) { sel.dataset.vu = opts; sel.innerHTML = opts; }
  if (document.activeElement !== sel) sel.value = String(E.emetteur);
  txt('cr-dl', mhz(E.ciel.dl)); txt('cr-ul', mhz(E.ciel.ul));
  const c = E.cat;
  txt('cr-poste', c.connecte ? 'Poste ' + c.modele + (c.simule ? ' (simulé)' : '') + ' : RX ' + mhz(c.dl) + ' · TX ' + mhz(c.ul) + ' · ' + (c.mode || '') + (c.pilote ? ' · Doppler' : ' · bouton') + (c.maintien ? ' · en pause' : '') : 'Poste non connecté');
  // Where in a linear transponder: the rest between its edges.
  // Where in a linear transponder, as the knob moves it (the rig read on CAT): its edges, the frequency at the cursor.
  const lin = tr && tr.lineaire && tr.dlBas && tr.dlHaut && tr.dlHaut > tr.dlBas;
  g('cr-bande-boite').hidden = !lin;
  if (lin) {
    const repos = E.repos != null ? E.repos : (tr.dlBas + tr.dlHaut) / 2;
    g('cr-repos').style.left = (100 * Math.max(0, Math.min(1, (repos - tr.dlBas) / (tr.dlHaut - tr.dlBas)))) + '%';
    txt('cr-bas', mhz(tr.dlBas)); txt('cr-haut', mhz(tr.dlHaut));
    txt('cr-bande-txt', mhz(repos) + ' au satellite · ' + Math.round((repos - tr.dlBas) / 1000) + ' kHz du bas, ' + Math.round((tr.dlHaut - repos) / 1000) + ' kHz du haut' +
      (E.repos == null ? ' (le milieu : le poste suit le curseur une fois connecté)' : ''));
  } else txt('cr-bande-txt', tr ? 'Une seule fréquence (pas de transpondeur linéaire)' : '');
  dessineCarteCarnet();
  // The mast, the recording, the dongle.
  const r = E.rotor;
  txt('cr-rotor', r.connecte ? 'Rotor : ' + (r.az == null ? '—' : Math.round(r.az) + '° / ' + Math.round(r.el || 0) + '°') + (r.azCible != null ? ' → ' + Math.round(r.azCible) + '° / ' + Math.round(r.elCible || 0) + '°' : '') + (r.attente ? ' · attend le lever' : '') : 'Rotor non connecté');
  txt('cr-son', S && S.present ? (S.enregistrement ? '● Enregistrement ' + duree(S.secondes * 1000) : 'Pas d’enregistrement') + (S.actif ? ' · son ' + Math.round((S.niveau || 0) * 100) + ' %' : ' · carte son arrêtée') : '');
  g('cr-son').style.color = S && S.enregistrement ? 'var(--rose)' : '';
  txt('cr-sdr', R && R.actif ? 'Clé SDR : ' + qFmt(R.ciel + R.decalage) + ' Hz · ' + (R.niveau || 0).toFixed(0) + ' dB' : '');
}

// ------------------------------------------------------------------ the station's card
let ficheVoulue = true, csPour = '', CSH = null, CSQ = null, CSQPour = '', minuteurStation = null;
const CSV = { cx: 0.5, cy: 0.4, z: 3 };
/** The card of the station typed: its past contacts (the log, at once), its QRZ card (when it comes). */
function stationPour(brut) {
  clearTimeout(minuteurStation);
  const b = base(brut || '');
  if (b.length < 3 || !/[0-9]/.test(b)) { csPour = ''; g('cs-vide').hidden = false; g('cs-plein').hidden = true; return; }
  if (b === csPour) return;
  minuteurStation = setTimeout(async () => {
    let h = null;
    try { h = await (await fetch('api/carnet/station?c=' + enc(b))).json(); } catch (x) { return; }
    if (base(v('c-call')) !== b) return;
    csPour = b; CSH = h; ficheVoulue = h.fiche;
    if (CSQPour !== b) CSQ = null;
    dessineStation();
  }, 150);
}
function dessineStation() {
  const h = CSH; if (!h || !csPour) return;
  const q = CSQ && CSQPour === csPour ? CSQ : null;
  g('cs-vide').hidden = true; g('cs-plein').hidden = false;
  const der = h.contacts[0];
  txt('cs-call', q && q.indicatif ? q.indicatif : csPour);
  txt('cs-nom', q ? q.nom + (q.surnom ? ' « ' + q.surnom + ' »' : '') : der && der.nm ? der.nm : '');
  txt('cs-lieu', q ? [q.adresse, q.qth, q.etat, q.pays].filter(x => x).join(' · ') : der && der.qth ? der.qth : '');
  txt('cs-info', q ? [q.carre, q.zoneCq ? 'CQ ' + q.zoneCq : '', q.zoneItu ? 'ITU ' + q.zoneItu : '', q.classe ? 'classe ' + q.classe : '', q.depuis ? 'licence ' + q.depuis : ''].filter(x => x).join(' · ') : '');
  g('cs-qsl').innerHTML = q ? '<span' + (q.lotw ? '' : ' class="non"') + '>LoTW</span><span' + (q.eqsl ? '' : ' class="non"') + '>eQSL</span><span' + (q.qslPapier ? '' : ' class="non"') + '>QSL papier</span>' +
    (q.qslManager ? '<span>' + esc(q.qslManager) + '</span>' : '') + (q.site ? '<a href="' + esc(q.site).replace(/"/g, '') + '" target="_blank" rel="noopener" style="color:var(--cyan);font-size:11px">site</a>' : '') : '';
  const ph = g('cs-photo');
  if (q && q.image && ficheVoulue) { if (ph.getAttribute('src') !== q.image) ph.src = q.image; ph.hidden = false; } else { ph.hidden = true; ph.removeAttribute('src'); }
  // Where: QRZ's place, else the log's square.
  const ou = q && q.lat != null ? q : (h.lat != null ? h : null);
  const voir = ficheVoulue && ou && ou.km != null;
  g('cs-dist').hidden = !voir; g('cs-carte-boite').hidden = !voir;
  if (voir) { txt('cs-km', ou.km.toLocaleString('fr-FR') + ' km'); txt('cs-az', ou.azimut + '° ' + ou.rose); dessineCarteStation2(ou); }
  // The log: how many, since when, on which satellites; each contact and its note.
  const jour = t => new Date(t).toISOString().slice(0, 10);
  txt('cs-resume', h.total ? h.total + ' contact' + (h.total > 1 ? 's' : '') + ' · premier ' + jour(h.premier) + ' · dernier ' + jour(h.dernier) +
    (h.sats.length ? ' · ' + h.sats.join(', ') : '') + (h.carres.length ? ' · carrés ' + h.carres.join(' ') : '') : 'Jamais contacté : un nouveau !');
  g('cs-resume').style.color = h.total ? '' : 'var(--vert)';
  g('cs-hist').innerHTML = h.contacts.map(e => '<tr><td style="white-space:nowrap">' + new Date(e.t).toISOString().slice(0, 16).replace('T', ' ') + '</td><td>' + esc(e.sat) +
    '</td><td>' + esc(e.md) + '</td><td>' + esc(e.rs) + '/' + esc(e.rr) + '</td><td>' + esc(e.l) + (e.c !== csPour ? ' <span class="petit">' + esc(e.c) + '</span>' : '') +
    '</td></tr>' + (e.n ? '<tr><td></td><td colspan="4" class="note">' + esc(e.n) + '</td></tr>' : '')).join('');
}
/** The two stations and the great circle between them, on the map. */
function dessineCarteStation2(ou) {
  const c = g('cs-carte'); if (!c.clientWidth || !ou.moi) return;
  const a = ou.moi, b = { lat: ou.lat, lon: ou.lon };
  // The great circle, a point every few degrees, longitudes continuous from mine.
  const rad = Math.PI / 180, p1 = [a.lat * rad, a.lon * rad], p2 = [b.lat * rad, b.lon * rad];
  const d = 2 * Math.asin(Math.sqrt(Math.sin((p2[0] - p1[0]) / 2) ** 2 + Math.cos(p1[0]) * Math.cos(p2[0]) * Math.sin((p2[1] - p1[1]) / 2) ** 2));
  const arc = []; let ref = a.lon;
  for (let i = 0; i <= 64; i++) {
    const f = i / 64; if (d === 0) break;
    const A = Math.sin((1 - f) * d) / Math.sin(d), B = Math.sin(f * d) / Math.sin(d);
    const x = A * Math.cos(p1[0]) * Math.cos(p1[1]) + B * Math.cos(p2[0]) * Math.cos(p2[1]);
    const y = A * Math.cos(p1[0]) * Math.sin(p1[1]) + B * Math.cos(p2[0]) * Math.sin(p2[1]);
    const z = A * Math.sin(p1[0]) + B * Math.sin(p2[0]);
    let lon = Math.atan2(y, x) / rad; while (lon - ref > 180) lon -= 360; while (lon - ref < -180) lon += 360; ref = lon;
    arc.push([Math.atan2(z, Math.sqrt(x * x + y * y)) / rad, lon]);
  }
  const lb = arc.length ? arc[arc.length - 1][1] : b.lon;
  const xs = [wxk(a.lon), wxk(lb)].concat(arc.map(p => wxk(p[1]))), ys = [wyq(a.lat), wyq(b.lat)].concat(arc.map(p => wyq(p[0])));
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  CSV.cx = (x0 + x1) / 2; CSV.cy = (y0 + y1) / 2;
  CSV.z = Math.max(1, Math.min(10, Math.log2(Math.min(c.clientWidth / 256 / Math.max(x1 - x0, 1e-4), c.clientHeight / 256 / Math.max(y1 - y0, 1e-4)) * 0.75)));
  const m = fondCarte(c, CSV);
  traceCarte(m, arc, '#E86FA8', 2.5, [6, 4]);
  pointCarte(m, a.lat, a.lon, '#FFB454', 6, E && E.indicatif ? E.indicatif : 'Moi');
  pointCarte(m, b.lat, lb, '#E86FA8', 7, csPour);
}

// The stations it may be, as typed (the phone's prediction: the log's contacts, how recent, the same
// satellite first), and the field's edge in the colour of what is typed: worked before, plausible, unusual.
let propositions = [], minuteurProps = null;
// Going through the predictions with Tab: which one is in the field (-1: what was typed), and what was typed.
let iProp = -1, tape = '', accepte = '';
const COUL_ETAT = { DEJA_CONTACTE: 'var(--vert)', PLAUSIBLE: 'var(--cyan)', INHABITUEL: 'var(--ambre)', VIDE: '' };
function proposeIndicatifs() {
  clearTimeout(minuteurProps);
  iProp = -1; tape = v('c-call');
  minuteurProps = setTimeout(async () => {
    const saisie = v('c-call');
    let j = null;
    try { j = await (await fetch('api/carnet/propositions?c=' + enc(saisie))).json(); } catch (x) { return; }
    if (v('c-call') !== saisie || iProp >= 0) return;
    g('c-call').style.borderColor = COUL_ETAT[j.etat] || '';
    g('c-call').title = { DEJA_CONTACTE: 'déjà contacté', PLAUSIBLE: 'indicatif plausible', INHABITUEL: 'indicatif inhabituel (rien n’empêche de l’enregistrer)' }[j.etat] || '';
    // A prediction just taken: no more to offer.
    propositions = saisie.toUpperCase() === accepte ? [] : j.propositions.filter(p => p.c !== saisie.toUpperCase());
    dessinePropositions();
  }, 120);
}
function dessinePropositions() {
  g('c-props').innerHTML = propositions.map((p, i) => '<button tabindex="-1"' + (i === iProp ? ' class="choisie"' : '') + ' onclick="prendProposition(' + i + ')" title="' + esc(p.c) + (p.sat ? ' · dernier contact sur ' + esc(p.sat) : '') + '"><b>' + esc(p.c) + '</b><span>' +
    [p.nm ? esc(p.nm.split(' ')[0]) : '', esc(p.l), '×' + p.n].filter(x => x).join(' · ') + '</span></button>').join('');
}
/** Prediction [i] in the field (its square too), not yet taken: Tab goes on, Enter takes it, Esc gives back what was typed. */
function montreProposition(i) {
  iProp = i;
  const p = propositions[i];
  g('c-call').value = p ? p.c : tape;
  stationPour(g('c-call').value);
  if (p && p.l && (!g('c-loc').dataset.tape || locPour !== base(p.c))) { g('c-loc').value = p.l; g('c-loc').dataset.tape = ''; locPour = base(p.c); }
  if (!p && !g('c-loc').dataset.tape) g('c-loc').value = '';
  dessinePropositions();
}
function prendProposition(i) {
  const p = propositions[i]; if (!p) return;
  accepte = p.c;
  g('c-call').value = p.c;
  if (p.l) { g('c-loc').value = p.l; g('c-loc').dataset.tape = ''; locPour = base(p.c); }
  g('c-props').innerHTML = ''; propositions = []; iProp = -1;
  // The name from the log or QRZ, as for a callsign typed whole.
  g('c-call').dispatchEvent(new Event('input'));
  g('c-call').focus();
}
// A square typed by hand is kept while going through the predictions.
// The callsign the square belongs to: typed for it, or filled from it. Another callsign that knows its square replaces it.
let locPour = '';
g('c-loc').addEventListener('input', () => { g('c-loc').dataset.tape = v('c-loc') ? '1' : ''; locPour = base(v('c-call')); });
function locPourIndicatif(l, c) {
  if (!l || v('c-call').indexOf('/') >= 0) return;
  if (v('c-loc') && locPour === c) return;
  g('c-loc').value = l; g('c-loc').dataset.tape = ''; locPour = c;
}
// Tab: the next prediction (Shift+Tab: the one before); Enter takes it; Esc: back to what was typed.
// Before the other keys of the field (capture): Enter here takes the prediction, it does not save yet.
g('c-call').addEventListener('keydown', e => {
  if (e.key === 'Tab' && propositions.length) {
    e.preventDefault();
    const n = propositions.length;
    montreProposition(e.shiftKey ? (iProp <= 0 ? n - 1 : iProp - 1) : (iProp + 1) % n);
  } else if (e.key === 'Enter' && iProp >= 0) {
    e.preventDefault(); e.stopImmediatePropagation();
    prendProposition(iProp);
  } else if (e.key === 'Escape' && iProp >= 0) {
    e.preventDefault(); montreProposition(-1);
  }
}, true);
g('c-call').addEventListener('input', proposeIndicatifs);
g('c-call').addEventListener('input', () => {
  clearTimeout(minuteurQrz);
  const c = base(v('c-call'));
  if (c !== quiPour) txt('c-qui', '');
  // Another callsign: a square filled for the previous one goes (one typed by hand stays).
  if (locPour && locPour !== c && !g('c-loc').dataset.tape) { g('c-loc').value = ''; locPour = ''; }
  stationPour(v('c-call'));
  if (c.length < 4 || !/[0-9]/.test(c)) return;
  // Its square from the log's last contact that has one (with or without a name).
  const avecCarre = C.find(e => base(e.c) === c && e.l);
  if (avecCarre) locPourIndicatif(avecCarre.l, c);
  const connu = C.find(e => base(e.c) === c && e.nm);
  if (connu) {
    quiPour = c; txt('c-qui', connu.nm + (connu.qth ? ' · ' + connu.qth : '') + ' (carnet)');
    locPourIndicatif(connu.l, c);
    // Known by name: QRZ only for the station's card, when it is wanted.
    if (!ficheVoulue) return;
  }
  minuteurQrz = setTimeout(async () => {
    if (!connu) txt('c-qui', 'QRZ…');
    let f = null;
    try { f = await (await fetch('api/qrz?c=' + enc(c))).json(); } catch (x) {}
    if (base(v('c-call')) !== c) return;
    CSQ = f && f.ok ? f : null; CSQPour = c; dessineStation();
    if (connu) return;
    if (!f || !f.ok) { txt('c-qui', f && f.erreur ? f.erreur : ''); return; }
    quiPour = c;
    txt('c-qui', [f.nom, f.qth, f.pays].filter(x => x).join(' · '));
    // Its square, unless one is there for this very callsign — never for a /P or /M (QRZ gives the home address).
    locPourIndicatif(f.carre, c);
  }, 600);
});
['c-call', 'c-loc', 'c-rs', 'c-rr', 'c-note'].forEach(i => g(i).addEventListener('keydown', e => { if (e.key === 'Enter') enregistreContact(); }));
// A contact typed afterwards: a satellite, one of its passes of the last three days (its middle), the time and the mode editable.
let apresListe = [];
async function apresBascule() {
  const on = g('ca-on').checked; g('ca').hidden = !on;
  txt('c-aide', on ? 'Daté à l’heure choisie, sur ce satellite, avec son az/el d’alors, ton carré et le mode choisi (sans fréquences).' :
    'Daté à l\'enregistrement, sur le satellite suivi, avec son az/el, ton carré, le mode et les fréquences au repos.');
  if (!on) return;
  if (!tous.length) await chargeSatellites();
  const s = g('ca-sat');
  if (!s.options.length) s.innerHTML = tous.slice().sort((a, b) => a.nom.localeCompare(b.nom)).map(x => '<option value="' + x.catnum + '">' + esc(x.nom) + '</option>').join('');
  if (E && E.suivi) s.value = String(E.suivi.catnum);
  apresPassages();
}
const p2c = n => String(n).padStart(2, '0');
function versLocal(t) { const d = new Date(t); return d.getFullYear() + '-' + p2c(d.getMonth() + 1) + '-' + p2c(d.getDate()) + 'T' + p2c(d.getHours()) + ':' + p2c(d.getMinutes()); }
async function apresPassages() {
  let j = null;
  try { j = await (await fetch('api/carnet/passages?catnum=' + v('ca-sat'))).json(); } catch (x) {}
  apresListe = (j && j.passages) || [];
  const md = g('ca-md');
  if (j && j.modes && md.options.length !== j.modes.length) md.innerHTML = j.modes.map(m => '<option>' + m + '</option>').join('');
  const hm = t => { const d = new Date(t); return p2c(d.getHours()) + ':' + p2c(d.getMinutes()); };
  g('ca-pas').innerHTML = apresListe.map((p, i) => '<option value="' + i + '">' + new Date(p.aos).toLocaleDateString() + ' ' + hm(p.aos) + '–' + hm(p.los) + ' · ' + p.elMax + '°</option>').join('') +
    '<option value="-1">' + (apresListe.length ? 'autre heure…' : 'aucun passage ces trois jours : l’heure à la main') + '</option>';
  apresPassage();
}
function apresPassage() {
  const p = apresListe[+v('ca-pas')];
  if (p) g('ca-heure').value = versLocal((p.aos + p.los) / 2);
  apresUtc();
}
function apresUtc() { const t = new Date(v('ca-heure')).getTime(); txt('ca-utc', isNaN(t) ? '' : new Date(t).toISOString().slice(11, 16) + ' UTC'); }
g('ca-heure').addEventListener('input', apresUtc);
async function enregistreContact() {
  const c = v('c-call'); if (!c) return;
  let apres = '';
  if (g('ca-on').checked) {
    const t = new Date(v('ca-heure')).getTime();
    if (isNaN(t)) { txt('c-etat', 'l’heure du contact ?'); return; }
    apres = '&catnum=' + v('ca-sat') + '&t=' + t + '&md=' + enc(v('ca-md'));
  }
  const j = await post('api/carnet/ajoute?c=' + enc(c) + '&l=' + enc(v('c-loc')) + '&rs=' + enc(v('c-rs')) + '&rr=' + enc(v('c-rr')) + '&n=' + enc(v('c-note')) + apres);
  if (j && j.ok) {
    txt('c-etat', c.toUpperCase() + ' enregistré');
    ['c-call', 'c-loc', 'c-note'].forEach(i => g(i).value = ''); g('c-rs').value = '59'; g('c-rr').value = '59';
    txt('c-qui', ''); quiPour = ''; stationPour(''); g('c-props').innerHTML = ''; propositions = []; iProp = -1; accepte = ''; g('c-loc').dataset.tape = ''; locPour = ''; g('c-call').style.borderColor = ''; g('c-call').focus(); chargeCarnet();
  }
}
function edite(t) {
  const e = C.find(x => x.t === t); if (!e) return;
  editT = t;
  txt('e-quand', new Date(t).toISOString().slice(0, 19).replace('T', ' ') + ' UTC · ' + e.sat);
  g('e-call').value = e.c; g('e-loc').value = e.l; g('e-md').value = e.md; g('e-rs').value = e.rs; g('e-rr').value = e.rr;
  g('e-nm').value = e.nm; g('e-qth').value = e.qth; g('e-n').value = e.n; txt('e-qrz', '');
  g('c-edit').showModal();
}
// QRZ on demand: fills what is empty (a square heard during the contact beats the directory).
async function qrzEdit() {
  txt('e-qrz', 'QRZ…');
  let f = null;
  try { f = await (await fetch('api/qrz?c=' + enc(base(v('e-call'))))).json(); } catch (x) {}
  if (!f || !f.ok) { txt('e-qrz', f && f.erreur ? f.erreur : 'QRZ ne connaît pas cet indicatif.'); return; }
  if (!v('e-nm')) g('e-nm').value = f.nom;
  if (!v('e-qth')) g('e-qth').value = f.qth;
  if (!v('e-loc') && f.carre && v('e-call').indexOf('/') < 0) { g('e-loc').value = f.carre; g('e-loc').dataset.qrz = '1'; }
  txt('e-qrz', [f.nom, f.qth, f.carre, f.pays].filter(x => x).join(' · '));
}
async function enregistreEdit() {
  const j = await post('api/carnet/modifie?t=' + editT + '&c=' + enc(v('e-call')) + '&l=' + enc(v('e-loc')) + '&md=' + enc(v('e-md')) +
    '&rs=' + enc(v('e-rs')) + '&rr=' + enc(v('e-rr')) + '&nm=' + enc(v('e-nm')) + '&qth=' + enc(v('e-qth')) + '&n=' + enc(v('e-n')) +
    (g('e-loc').dataset.qrz ? '&lo=QRZ' : ''));
  delete g('e-loc').dataset.qrz;
  if (j && j.ok !== false) { g('c-edit').close(); chargeCarnet(); }
}
async function supprimeEdit() {
  if (await supprimeContacts([editT])) g('c-edit').close();
}
function bilanImport(j) {
  if (!j) return 'échec';
  if (j.ok === false && j.erreur) return j.erreur;
  return j.lus + ' contact(s) lus · ' + j.ajoutes + ' ajouté(s) au carnet · ' + j.passages + ' passage(s) reconstitué(s) dans le journal' +
    (j.inconnus ? ' · dont ' + j.inconnus + ' sur des satellites absents du bulletin AMSAT (gardés au carnet, sans passage)' : '') +
    ' · le journal garde ses 500 passages les plus récents';
}
async function profilsWavelog() {
  txt('i-etat', 'relevé des profils…');
  let j = null;
  try { j = await (await fetch('api/wavelog/profils')).json(); } catch (x) {}
  if (!j || j.erreur) { txt('i-etat', j && j.erreur ? j.erreur : 'échec'); return; }
  txt('i-etat', '');
  g('w-profils').innerHTML = j.profils.map(p => '<label style="display:block"><input type="checkbox" class="w-p" value="' + esc(p.id) + '" checked> ' +
    esc(p.nom || p.indicatif) + ' · ' + esc(p.carre) + '</label>').join('') +
    '<button class="primaire" style="margin-top:6px" onclick="importeWavelog()">Importer les contacts satellite</button>';
}
async function importeWavelog() {
  const ids = [...document.querySelectorAll('.w-p')].filter(c => c.checked).map(c => c.value).join(',');
  txt('i-etat', 'import en cours (tout le carnet en ligne : patience)…');
  let j = null;
  try { j = await (await fetch('api/wavelog/importe?profils=' + enc(ids), { method: 'POST' })).json(); } catch (x) {}
  txt('i-etat', bilanImport(j)); chargeCarnet();
}
async function importeAdif(input) {
  const f = input.files[0]; if (!f) return;
  txt('i-etat', 'lecture de ' + f.name + '…');
  let j = null;
  try { j = await (await fetch('api/import/adif?enLigne=' + (g('i-enligne').checked ? 1 : 0), { method: 'POST', body: await f.text() })).json(); } catch (x) {}
  txt('i-etat', bilanImport(j)); input.value = ''; chargeCarnet();
}
async function depose(t) {
  txt('c-depot', 'envoi…');
  const j = await post('api/carnet/depose' + (t ? '?t=' + t : ''));
  txt('c-depot', j && j.dit ? j.dit : '');
  chargeCarnet();
}

// ------------------------------------------------------------------ settings
async function chargeReglages() {
  try {
    const r = await (await fetch('api/reglages')).json();
    g('indicatif').value = r.c; g('locator').value = r.l; g('minEl').value = r.minEl;
    g('qrzFiche').checked = r.fiche; ficheVoulue = r.fiche;
    g('qrzUser').value = r.qrzUser; g('qrzMdp').placeholder = r.qrzMdp ? 'mot de passe enregistré' : 'Mot de passe';
    g('carnetUrl').value = r.carnetUrl; g('carnetCle').placeholder = r.carnetCle ? 'clé enregistrée' : 'Clé d’API (lecture et écriture)';
    g('carnetProfil').value = r.carnetProfil;
    g('carnetAuto').checked = r.carnetAuto; g('carnetDelaiL').hidden = !r.carnetAuto;
    g('amsatAuto').checked = !!r.amsatAuto;
    txt('amsatEtat', r.amsatEtat || (r.amsatAuto ? 'Les contacts faits depuis que c’est coché seront signalés.' : 'Coupé : rien n’est envoyé sans toi (bouton « Entendu » de l’onglet Station).'));
    const d = g('carnetDelai');
    if (d.options.length !== r.delais.length) d.innerHTML = r.delais.map(s => '<option value="' + s + '">' + (s < 60 ? s + ' s' : s / 60 + ' min') + '</option>').join('');
    d.value = String(r.carnetDelai);
  } catch (x) {}
}
async function enregistreReglages() {
  const j = await post('api/reglages?c=' + enc(v('indicatif')) + '&l=' + enc(v('locator')) + '&minEl=' + enc(v('minEl')) +
    '&qrzUser=' + enc(v('qrzUser')) + '&qrzMdp=' + enc(g('qrzMdp').value) + '&carnetUrl=' + enc(v('carnetUrl')) +
    '&carnetCle=' + enc(g('carnetCle').value) + '&carnetProfil=' + enc(v('carnetProfil')));
  if (j && j.ok !== false) {
    txt('reg-etat', 'enregistré'); g('qrzMdp').value = ''; g('carnetCle').value = '';
    chargeReglages(); setTimeout(chargeListe, 1500);
  }
}
// ------------------------------------------------------------------ DATV (MiniTiouner, through LongMynd)
let D = null, dSr = 0;
const mhz3 = k => (k / 1000).toFixed(3).replace('.', ',');
async function ticDatv(premier) {
  if (g('v-datv').hidden) return;
  try { D = await (await fetch('api/datv')).json(); } catch (x) { return; }
  if (!D.ok) return;
  if (premier || !document.activeElement || !document.activeElement.id.startsWith('d-')) {
    if (premier) {
      g('d-ciel').value = mhz3(D.ciel); g('d-ol').value = mhz3(D.ol); g('d-entree').value = D.entree; g('d-son').checked = D.son; dSr = D.sr;
      g('d-canal').innerHTML = '<option value="">—</option><option value="' + mhz3(D.balise) + '">Balise ' + mhz3(D.balise) + '</option>' +
        D.canaux.map(k => '<option value="' + mhz3(k) + '">' + mhz3(k) + '</option>').join('');
    }
  }
  g('d-debits').innerHTML = D.debits.map(s => '<button class="' + (s === dSr ? 'ici' : '') + '" onclick="dSr=' + s + ';ticDatv()">' + s + '</button>').join('');
  const e = g('d-etat'); e.textContent = D.actif ? D.libelle : 'arrêté';
  e.style.background = D.verrouille ? 'rgba(91,214,138,.2)' : (D.actif ? 'rgba(255,180,84,.2)' : ''); e.style.color = D.verrouille ? 'var(--vert)' : (D.actif ? 'var(--ambre)' : 'var(--gris)');
  txt('d-mer', D.mer == null || !D.verrouille ? '—' : D.mer.toFixed(1) + ' dB');
  txt('d-modcod', D.verrouille ? D.modcod : '');
  txt('d-porteuse', D.cielTrouve ? mhz3(D.cielTrouve) + ' MHz' + (D.srTrouve ? ' · ' + D.srTrouve + ' kS/s' : '') + (D.verrouille ? ' · écart ' + (D.fiTrouvee - D.fi) + ' kHz' : '') : '');
  txt('d-debit', D.kbps ? Math.round(D.kbps) + ' kb/s' + (D.nuls ? ' · ' + D.nuls + ' % de vide' : '') : '');
  txt('d-service', [D.fournisseur, D.service].filter(x => x).join(' · ') + (D.flux.length ? '  —  ' + D.flux.map(f => f.type + ' (PID ' + f.pid + ')').join(', ') : ''));
  txt('d-ou', D.noeud ? 'sur le SatMe-Node ' + (D.nomNoeud ? '« ' + D.nomNoeud + ' » ' : '') + '(' + D.noeud + ')' : 'sur ce PC');
  g('d-prog').hidden = !!D.noeud;
  txt('d-titre', D.actif ? '· ' + mhz3(D.ciel) + ' MHz · ' + D.sr + ' kS/s · FI ' + mhz3(D.fi) + ' MHz' : '');
  const r = g('d-rec'); r.style.display = D.enregistrement ? '' : 'none'; r.textContent = '● ' + D.enregistrement;
  g('d-benr').textContent = D.enregistrement ? 'Arrêter l’enregistrement' : 'Enregistrer';
  if (D.erreur) txt('d-msg', D.erreur);
  txt('d-details', D.actif ? '' : '');
  // BER, frames and pilots are measured once locked only: shown then.
  const vr = D.verrouille;
  g('d-details').innerHTML = D.actif ? ['AGC1 ' + D.agc1, 'AGC2 ' + D.agc2, vr && D.ber != null ? 'BER ' + D.ber.toFixed(2) + ' %' : '',
    vr && D.courtes != null ? (D.courtes ? 'trames courtes' : 'trames normales') : '', vr && D.pilotes != null ? (D.pilotes ? 'pilotes' : 'sans pilotes') : '',
    D.images ? D.images + ' images' : ''].filter(x => x).join('<br>') : '';
  const pr = D.programme ? 'ok:' + D.programme : 'non';
  if (g('d-prog').dataset.vu !== pr) {
    g('d-prog').dataset.vu = pr;
    g('d-prog').innerHTML = D.programme ? 'LongMynd : ' + esc(D.programme) :
      D.telephone ? 'Le téléphone pilote le MiniTiouner par un SatMe-Node (donner son adresse et son code) ; l’image se regarde sur son écran DATV.' :
      D.windows ? 'Sous Windows, le MiniTiouner se pilote par un SatMe-Node (un Raspberry Pi près de l’antenne) : donner son adresse et son code.' :
      'LongMynd introuvable : le compiler (BATC, github.com/BritishAmateurTelevisionClub/longmynd), puis donner ici son chemin :' +
      '<div class="ligne"><input id="d-chemin" placeholder="/home/…/longmynd/longmynd"><button onclick="dPost(\'chemin?p=\' + enc(v(\'d-chemin\')))">Utiliser</button></div>';
  }
  // The video: asked once the stream runs, dropped when it stops.
  const v = g('d-video');
  if (D.actif && D.images && v.style.display !== 'block') { v.src = 'api/datv/video.mjpeg?t=' + Date.now(); v.style.display = 'block'; g('d-vide').hidden = true; }
  if (!D.actif && v.style.display === 'block') { v.removeAttribute('src'); v.style.display = 'none'; }
  if (v.style.display !== 'block') { g('d-vide').hidden = false; g('d-vide').textContent = !D.actif ? 'Réception arrêtée' :
    D.telephone ? (D.verrouille ? 'accroché : l’image est sur l’écran DATV du téléphone' : D.libelle + '…') : (D.verrouille ? 'accroché, en attente des images…' : D.libelle + '…'); }
  dessineConstellation();
}
function dessineConstellation() {
  const c = g('d-const'), x = c.getContext('2d'), w = c.width;
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, w);
  x.strokeStyle = '#22303B'; x.beginPath(); x.moveTo(w / 2, 0); x.lineTo(w / 2, w); x.moveTo(0, w / 2); x.lineTo(w, w / 2); x.stroke();
  if (!D || !D.points) return;
  x.fillStyle = D.verrouille ? 'rgba(63,224,200,.85)' : 'rgba(255,180,84,.7)';
  D.points.forEach(p => x.fillRect(w / 2 + p[0] * w / 256 - 1, w / 2 - p[1] * w / 256 - 1, 2.5, 2.5));
}
setInterval(() => ticDatv(), 500);
function dParams() {
  return 'ciel=' + enc(v('d-ciel').replace(',', '.')) + '&sr=' + dSr + '&ol=' + enc(v('d-ol').replace(',', '.')) + '&entree=' + g('d-entree').value +
    '&lnb=' + g('d-lnb').value + '&son=' + (g('d-son').checked ? 1 : 0);
}
async function dDemarre() { txt('d-msg', 'démarrage de LongMynd…'); const j = await dPost('demarre?' + dParams()); if (j && j.ok !== false) txt('d-msg', ''); }
async function dPost(q) {
  let j = null;
  try { j = await (await fetch('api/datv/' + q, { method: 'POST' })).json(); } catch (x) {}
  if (j && j.ok === false && j.erreur) txt('d-msg', j.erreur);
  setTimeout(ticDatv, 200); return j;
}
async function dNoeud() {
  txt('d-msg', v('d-noeud') ? 'association au node…' : 'retour au MiniTiouner de ce PC…');
  const j = await dPost('noeud?adresse=' + enc(v('d-noeud')) + '&code=' + enc(v('d-noeud-code')));
  if (j && j.ok !== false) { txt('d-msg', v('d-noeud') ? 'associé' : 'MiniTiouner de ce PC'); g('d-noeud-boite').hidden = true; g('d-noeud-code').value = ''; }
}
function dBalise() { g('d-ciel').value = mhz3(D ? D.balise : 10491500); dSr = 1500; ticDatv(); }
async function dCale() { const j = await dPost('cale'); if (j && j.ok) { txt('d-msg', 'oscillateur corrigé de ' + (-j.ecart) + ' kHz'); setTimeout(() => ticDatv(true), 600); } }
async function dEnregistre() { await dPost('enregistre?on=' + (D && D.enregistrement ? 0 : 1)); }
function dLnb() { g('d-lnb-avert').hidden = !g('d-lnb').value; }

// ------------------------------------------------------------------ orbital elements
let SRC = null;
async function chargeSources() {
  try { SRC = await (await fetch('api/sources')).json(); } catch (x) { return; }
  g('gpServeur').value = SRC.serveur; g('gpSeul').checked = SRC.seul;
  g('gpSources').innerHTML = SRC.sources.map(s => '<label class="petit" title="' + esc(s.detail).replace(/"/g, '&quot;') + '"><input type="checkbox" class="gp-src" value="' + s.id + '"' +
    (s.actif ? ' checked' : '') + ' data-celestrak="' + (s.celestrak ? 1 : 0) + '"> ' + esc(s.nom) + (s.celestrak ? ' <span class="gp-ct">(par le serveur)</span>' : '') + '</label>').join('');
  majSources();
}
// CelesTrak refuses this PC: its groups only through the server, so without one they cannot be ticked.
function majSources() {
  const sans = !v('gpServeur');
  g('gpSeul').disabled = sans; if (sans) g('gpSeul').checked = false;
  document.querySelectorAll('.gp-src').forEach(c => { if (c.dataset.celestrak === '1') { c.disabled = sans; if (sans) c.checked = false; } });
  g('gpDefaut').hidden = !SRC || v('gpServeur') === SRC.defaut;
}
g('gpServeur').addEventListener('input', majSources);
async function enregistreSources() {
  const ids = [...document.querySelectorAll('.gp-src')].filter(c => c.checked).map(c => c.value);
  if (!ids.length) { alert('Au moins une source.'); return; }
  await post('api/sources?ids=' + enc(ids.join(',')) + '&serveur=' + enc(v('gpServeur')) + '&seul=' + (g('gpSeul').checked ? 1 : 0));
  txt('gp-essai', '');
}
async function essaiGp() {
  txt('gp-essai', 'serveur…');
  let j = null;
  try { j = await (await fetch('api/sources/essai?serveur=' + enc(v('gpServeur')), { method: 'POST' })).json(); } catch (x) {}
  txt('gp-essai', j && j.ok ? 'il répond : ' + j.groupes : (j && j.erreur ? j.erreur : 'pas de réponse'));
}
// Wavelog's station locations: the one for here (chosen, or created), and where each place of the log goes.
let WL = null;
async function essaiCarnet() {
  txt('carnet-essai', 'carnet en ligne…');
  let j = null;
  try { j = await (await fetch('api/carnet/profils', { method: 'POST' })).json(); } catch (x) {}
  if (!j || !j.ok) { txt('carnet-essai', j && j.erreur ? j.erreur : 'pas de réponse'); g('wl-lieux').hidden = true; return; }
  WL = j;
  txt('carnet-essai', 'il répond : ' + j.profils.length + ' profil(s) de station');
  g('wl-lieux').hidden = false;
  txt('wl-ici', j.ici);
  const nomP = p => p.id + ' · ' + [p.nom, p.carre, p.indicatif].filter(x => x).join(' · ');
  const s = g('carnetProfils');
  s.innerHTML = '<option value="">— aucun —</option>' + j.profils.map(p => '<option value="' + esc(p.id) + '">' + esc(nomP(p)) + '</option>').join('');
  s.value = j.pourIci || '';
  // A profile chosen by hand for another square: Wavelog would file these contacts under its square.
  const pIci = j.profils.find(x => x.id === j.pourIci);
  const sans = g('wl-sans'); sans.hidden = j.iciCouvert;
  sans.textContent = !j.pourIci ? 'Aucun profil Wavelog ne couvre ' + j.ici + ' : en choisir un ci-dessus, ou le créer ci-dessous.'
    : 'Le profil choisi est en ' + (pIci ? pIci.carre : '?') + ', pas en ' + j.ici + ' : Wavelog rangerait les contacts d’ici sous son carré. Créer le profil de ' + j.ici + ' ci-dessous ?';
  const manquants = j.lieux.filter(l => !l.couvert).map(l => l.lieu);
  if (!j.iciCouvert && manquants.indexOf(j.ici) < 0) manquants.unshift(j.ici);
  g('wl-table').innerHTML = j.lieux.map(l => {
    const p = j.profils.find(x => x.id === l.profil);
    return '<tr><td><b>' + esc(l.lieu) + '</b></td><td class="petit">' + l.n + ' contact(s)</td><td>' +
      (p && l.couvert ? '<span class="vert">→ ' + esc(nomP(p)) + '</span>'
        : p ? '<span class="ambre">→ ' + esc(nomP(p)) + ' (choisi à la main, son carré est ' + esc(p.carre) + ')</span>'
        : '<span class="ambre">aucun profil</span>') + '</td></tr>';
  }).join('') || '<tr><td class="petit">aucun contact au carnet</td></tr>';
  g('wl-creation').hidden = manquants.length === 0;
  g('wl-bcree').textContent = manquants.length === 1 ? 'Créer le profil « ' + manquants[0] + ' »' : 'Créer les ' + manquants.length + ' profils manquants';
  g('wl-bcree').dataset.lieux = manquants.join('|');
  ['dxcc', 'pays', 'cq', 'itu'].forEach(k => { if (!v('wl-' + k) && j.creation[k]) g('wl-' + k).value = j.creation[k]; });
}
async function choisitProfil(id) {
  await post('api/carnet/profil?id=' + enc(id)); g('carnetProfil').value = id; essaiCarnet();
}
async function creeProfils() {
  txt('wl-msg', 'création…');
  const j = await post('api/carnet/profils/cree?lieux=' + enc(g('wl-bcree').dataset.lieux) + '&dxcc=' + enc(v('wl-dxcc')) + '&pays=' + enc(v('wl-pays')) +
    '&cq=' + enc(v('wl-cq')) + '&itu=' + enc(v('wl-itu')));
  txt('wl-msg', j && j.dit ? j.dit : 'échec'); essaiCarnet();
}
let wlLu = false;
async function essaiQrz() {
  txt('qrz-etat', 'QRZ…');
  let f = null;
  try { f = await (await fetch('api/qrz?c=' + enc(v('indicatif') || 'F4IOZ'))).json(); } catch (x) {}
  txt('qrz-etat', f && f.ok ? 'QRZ répond : ' + [f.nom, f.qth].filter(x => x).join(' · ') : (f && f.erreur ? f.erreur : 'pas de réponse'));
}

// ------------------------------------------------------------------ sound and SSTV
let S = null, versionImage = -1, cartesVues = '', imagesVus = '', enregsVus = '';
const ouverte = n => !g('v-' + n).hidden;
async function ticSon() {
  try { S = await (await fetch('api/son')).json(); } catch (x) { return; }
  if (!S.present) return;
  // The header says it whatever the tab: a recording under way.
  g('rec').style.display = S.enregistrement ? '' : 'none';
  txt('rec', S.enregistrement ? '● REC ' + duree(S.secondes * 1000) : '');
  const cartes = JSON.stringify(S.cartes);
  if (cartes !== cartesVues) {
    cartesVues = cartes;
    g('s-carte').innerHTML = S.cartes.map(c => '<option value="' + esc(c.nom).replace(/"/g, '&quot;') + '">' + esc(c.nom) +
      (c.desc ? ' — ' + esc(c.desc.slice(0, 60)) : '') + '</option>').join('') || '<option value="">aucune carte son</option>';
  }
  if (document.activeElement !== g('s-carte') && S.carte) g('s-carte').value = S.carte;
  g('s-niveau').style.width = Math.round(Math.min(1, S.niveau) * 100) + '%';
  g('s-niveau').style.background = S.niveau > 0.9 ? 'var(--rose)' : 'var(--vert)';
  txt('s-etat', S.erreur || (S.actif ? S.carte + ' · ' + S.rate + ' Hz' : 'arrêtée'));
  txt('s-rec', S.enregistrement ? '■ Arrêter' : '● Enregistrer');
  txt('s-duree', S.enregistrement ? duree(S.secondes * 1000) + (S.auto ? ' (passage)' : '') : '');
  if (document.activeElement !== g('s-auto')) g('s-auto').checked = S.enregAuto;
  // The recording buttons of Station and Carnet, and the option to stop after the pass.
  document.querySelectorAll('.rec-b').forEach(b => b.textContent = S.enregistrement ? '■ Arrêter l’enregistrement' : '● Enregistrer le son');
  document.querySelectorAll('.rec-d').forEach(d => d.textContent = S.enregistrement ? duree(S.secondes * 1000) : '');
  document.querySelectorAll('.rec-m').forEach(d => d.textContent = S.enregistrement ? (S.auto ? 'passage' : '') : S.actif ? '' : 'carte son arrêtée (onglet Son)');
  document.querySelectorAll('.rec-los').forEach(c => { if (document.activeElement !== c) c.checked = !!S.coupeLos; });
  document.querySelectorAll('.rec-los-l').forEach(l => l.hidden = S.coupeLos === null || S.coupeLos === undefined);
  if (document.activeElement !== g('s-sstv')) g('s-sstv').checked = S.sstv;
  if (document.activeElement !== g('s-aprs')) g('s-aprs').checked = S.aprs;
  const enregs = JSON.stringify(S.enregistrements.map(e => e.nom + e.taille));
  if (enregs !== enregsVus && ouverte('son')) {
    enregsVus = enregs;
    g('s-liste').innerHTML = S.enregistrements.map(e => '<tr><td><a href="#" onclick="ecoute(\'' + enc(e.nom) + '\');return false">' + esc(e.nom) +
      '</a></td><td class="petit">' + (e.taille / 1048576).toFixed(1) + ' Mo</td><td><a href="enregistrements/' + enc(e.nom) + '" download>⤓</a></td></tr>').join('') ||
      '<tr><td class="petit">aucun enregistrement</td></tr>';
  }
  // SSTV: the picture arriving, reloaded only when it moved.
  txt('i-mode', S.sstvMode ? S.sstvMode + ' · ' + S.sstvLignes + '/' + S.sstvHauteur + ' lignes' : '');
  txt('sstv-etat', !S.actif ? (window.SATME_TELEPHONE ? 'Le téléphone décode en enregistrant : « Enregistrer » (onglet Son).' : 'Démarre la carte son (onglet Son) pour décoder.') : !S.sstv ? 'Décodage SSTV coupé (onglet Son).' : S.sstvMode ? 'réception…' : 'à l’écoute');
  if (ouverte('sstv') && S.sstvVersion !== versionImage) {
    versionImage = S.sstvVersion;
    if (S.sstvMode) g('i-encours').src = 'api/sstv/encours.png?v=' + S.sstvVersion;
    else if (S.sstvDerniere) g('i-encours').src = 'sstv/' + enc(S.sstvDerniere);
  }
  const imgs = JSON.stringify(S.images.map(i => i.nom));
  if (imgs !== imagesVus && ouverte('sstv')) {
    imagesVus = imgs;
    g('i-galerie').innerHTML = S.images.map((i, n) => '<a href="sstv/' + enc(i.nom) + '" onclick="voisImage(' + n + '); return false" style="text-decoration:none;color:inherit">' +
      '<img src="sstv/' + enc(i.nom) + '" style="width:100%;border-radius:6px" loading="lazy"><div class="petit">' + esc(i.sat) + ' · ' + esc(i.mode) +
      ' · ' + (i.t ? new Date(i.t).toISOString().slice(0, 16).replace('T', ' ') : '') + (i.complete ? '' : ' · partielle') + '</div></a>').join('') ||
      '<div class="petit">aucune image</div>';
  }
}
// A picture opened over the page, not in a tab: the arrows go through the gallery, Escape closes.
let viN = 0;
function voisImage(n) {
  const l = (S && S.images) || []; if (!l.length) return;
  viN = (n + l.length) % l.length; const i = l[viN];
  g('vi-img').src = 'sstv/' + enc(i.nom); g('vi-lien').href = 'sstv/' + enc(i.nom); g('vi-lien').download = i.nom;
  txt('vi-txt', (viN + 1) + '/' + l.length + ' · ' + [i.sat, i.mode, i.t ? new Date(i.t).toISOString().slice(0, 16).replace('T', ' ') + ' UTC' : '', i.complete ? '' : 'partielle'].filter(x => x).join(' · '));
  if (!g('visionneuse').open) g('visionneuse').showModal();
}
g('visionneuse').addEventListener('keydown', e => {
  if (e.key === 'ArrowLeft') { e.preventDefault(); voisImage(viN - 1); } else if (e.key === 'ArrowRight') { e.preventDefault(); voisImage(viN + 1); }
});
g('i-encours').style.cursor = 'zoom-in';
g('i-encours').onclick = () => {
  if (!S || S.sstvMode || !S.sstvDerniere) return;
  const n = (S.images || []).findIndex(i => i.nom === S.sstvDerniere); if (n >= 0) voisImage(n);
};
// ------------------------------------------------------------------ squares worked
let Q = null, qz = 4, qcx = 0.5, qcy = 0.35, qCadre = false;
const qTuiles = new Map();
const wxq = lon => (lon + 180) / 360;
function wyq(lat) { const l = Math.max(-85, Math.min(85, lat)) * Math.PI / 180; return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2; }
// A 4-character square: its south-west corner (2° of longitude, 1° of latitude).
function coin(c) { const A = 'A'.charCodeAt(0); return [(c.charCodeAt(0) - A) * 20 - 180 + (+c[2]) * 2, (c.charCodeAt(1) - A) * 10 - 90 + (+c[3])]; }
async function chargeCarres() {
  try { Q = await (await fetch('api/carres')).json(); } catch (x) { return; }
  txt('q-n', String(Q.carres.length)); txt('q-champs', String(Q.champs));
  const kms = Q.carres.map(c => c.km).filter(k => k != null);
  txt('q-loin', kms.length ? String(Math.max(...kms)) : '—');
  dessineListeCarres();
  if (!qCadre) cadreCarres();
  dessineCarres();
}
function dessineListeCarres() {
  const f = v('q-filtre').toUpperCase();
  g('q-liste').innerHTML = Q.carres.filter(c => !f || [c.c, c.indicatifs.join(' '), c.sats.join(' ')].join(' ').toUpperCase().includes(f))
    .sort((a, b) => b.dernier - a.dernier).map(c => '<tr class="p" onclick="centreSur(\'' + c.c + '\')"><td><b>' + c.c + '</b></td><td>' + c.n +
    '</td><td>' + (c.km == null ? '' : c.km) + '</td><td class="petit">' + esc(c.indicatifs.join(', ')) + '<br>' + esc(c.sats.join(', ')) + '</td></tr>').join('') ||
    '<tr><td colspan="4" class="petit">aucun carré dans le carnet</td></tr>';
}
g('q-filtre').oninput = () => Q && dessineListeCarres();
function cadreCarres() {
  const c = g('q-carte'); if (!c.clientWidth) return;
  const pts = Q.carres.map(q => { const [lo, la] = coin(q.c); return [la + 0.5, lo + 1]; });
  if (Q.lat != null) pts.push([Q.lat, Q.lon]);
  if (!pts.length) return;
  const xs = pts.map(p => wxq(p[1])), ys = pts.map(p => wyq(p[0]));
  const x0 = Math.min(...xs) - 0.01, x1 = Math.max(...xs) + 0.01, y0 = Math.min(...ys) - 0.01, y1 = Math.max(...ys) + 0.01;
  qcx = (x0 + x1) / 2; qcy = (y0 + y1) / 2;
  qz = Math.max(2, Math.min(9, Math.log2(Math.min(c.clientWidth / 256 / (x1 - x0), c.clientHeight / 256 / (y1 - y0)) * 0.9)));
  qCadre = true;
}
function centreSur(k) { const [lo, la] = coin(k); qcx = wxq(lo + 1); qcy = wyq(la + 0.5); qz = Math.max(qz, 6); dessineCarres(); }
function qTuile(z, x, y) {
  const n = 1 << z, xw = ((x % n) + n) % n, k = z + '/' + xw + '/' + y;
  let i = qTuiles.get(k);
  if (!i) { i = new Image(); i.onload = () => { dessineCarres(); dessineSked(); dessineCarteStation(); if (!g('v-carnet').hidden && CSH) dessineStation(); }; i.src = 'journal/tuile?z=' + z + '&x=' + xw + '&y=' + y; qTuiles.set(k, i);
    if (qTuiles.size > 500) qTuiles.delete(qTuiles.keys().next().value); }
  return i.complete && i.naturalWidth ? i : null;
}
function dessineCarres() {
  const c = g('q-carte'); if (!c || !Q || g('v-carres').hidden) return;
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.fillStyle = '#AAD3DF'; x.fillRect(0, 0, w, h);
  const z = Math.max(0, Math.min(16, Math.floor(qz))), ts = 256 * Math.pow(2, qz - z), s = 256 * Math.pow(2, qz);
  const n = 1 << z, ox = w / 2 - qcx * s, oy = h / 2 - qcy * s;
  for (let ty = Math.max(0, Math.floor(-oy / ts)); ty <= Math.min(n - 1, Math.floor((h - oy) / ts)); ty++)
    for (let tx = Math.floor(-ox / ts); tx <= Math.floor((w - ox) / ts); tx++) { const i = qTuile(z, tx, ty); if (i) x.drawImage(i, ox + tx * ts, oy + ty * ts, ts + 0.5, ts + 0.5); }
  const px = lon => ox + wxq(lon) * s, py = lat => oy + wyq(lat) * s;
  const carre = (k, fond, bord, l) => { const [lo, la] = coin(k); const xa = px(lo), xb = px(lo + 2), ya = py(la + 1), yb = py(la);
    x.fillStyle = fond; x.fillRect(xa, ya, xb - xa, yb - ya); x.strokeStyle = bord; x.lineWidth = l; x.strokeRect(xa, ya, xb - xa, yb - ya); return [xa, ya, xb, yb]; };
  // The squares' grid when there is room for it, the fields' always.
  const pasCarre = px(2) - px(0);
  x.lineWidth = 1;
  for (let lon = -180; lon <= 180; lon += pasCarre > 14 ? 2 : 20) {
    x.strokeStyle = lon % 20 === 0 ? 'rgba(30,40,55,.55)' : 'rgba(30,40,55,.18)'; x.beginPath(); x.moveTo(px(lon), 0); x.lineTo(px(lon), h); x.stroke(); }
  for (let lat = -80; lat <= 80; lat += pasCarre > 14 ? 1 : 10) {
    x.strokeStyle = lat % 10 === 0 ? 'rgba(30,40,55,.55)' : 'rgba(30,40,55,.18)'; x.beginPath(); x.moveTo(0, py(lat)); x.lineTo(w, py(lat)); x.stroke(); }
  // Worked: the more contacts, the deeper the colour.
  const max = Math.max(1, ...Q.carres.map(q => q.n));
  x.font = 'bold ' + Math.max(9, Math.min(14, pasCarre / 4)) + 'px system-ui'; x.textAlign = 'center';
  Q.carres.forEach(q => { const a = 0.25 + 0.5 * Math.log(1 + q.n) / Math.log(1 + max);
    const [xa, ya, xb, yb] = carre(q.c, 'rgba(63,224,200,' + a.toFixed(2) + ')', 'rgba(20,120,110,.9)', 1);
    if (pasCarre > 28) { x.fillStyle = '#06131A'; x.fillText(q.c, (xa + xb) / 2, (ya + yb) / 2 + 4); } });
  if (Q.mon && Q.mon.length === 4) { carre(Q.mon, 'rgba(255,180,84,.45)', '#FFB454', 2.5);
    if (Q.lat != null) { x.fillStyle = '#1a1f2a'; x.beginPath(); x.arc(px(Q.lon), py(Q.lat), 5, 0, 7); x.fill(); } }
  if (pasCarre <= 14) { x.fillStyle = 'rgba(20,30,45,.7)'; x.font = '11px system-ui';
    for (let lon = -180; lon < 180; lon += 20) for (let lat = -90; lat < 90; lat += 10) {
      const f = String.fromCharCode(65 + (lon + 180) / 20) + String.fromCharCode(65 + (lat + 90) / 10); x.fillText(f, px(lon + 10), py(lat + 5)); } }
}
(function () {
  const c = g('q-carte'); let pres = null;
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); pres = { x: ev.clientX, y: ev.clientY, cx: qcx, cy: qcy }; c.style.cursor = 'grabbing'; });
  c.addEventListener('pointermove', ev => { if (!pres) return; const s = 256 * Math.pow(2, qz);
    qcx = pres.cx - (ev.clientX - pres.x) / s; qcy = Math.max(0, Math.min(1, pres.cy - (ev.clientY - pres.y) / s)); dessineCarres(); });
  c.addEventListener('pointerup', () => { pres = null; c.style.cursor = 'grab'; });
  c.addEventListener('wheel', ev => { ev.preventDefault(); const r = c.getBoundingClientRect(), s = 256 * Math.pow(2, qz);
    const mx = qcx + (ev.clientX - r.left - r.width / 2) / s, my = qcy + (ev.clientY - r.top - r.height / 2) / s;
    qz = Math.max(1, Math.min(12, qz + (ev.deltaY > 0 ? -0.35 : 0.35))); const s2 = 256 * Math.pow(2, qz);
    qcx = mx - (ev.clientX - r.left - r.width / 2) / s2; qcy = my - (ev.clientY - r.top - r.height / 2) / s2; dessineCarres(); }, { passive: false });
  c.addEventListener('dblclick', () => { qCadre = false; cadreCarres(); dessineCarres(); });
  window.addEventListener('resize', dessineCarres);
})();

// ------------------------------------------------------------------ timeline
let F = null;
async function chargeFrise() {
  try { F = await (await fetch('api/frise?h=' + g('f-h').value)).json(); } catch (x) { return; }
  dessineFrise();
}
function dessineFrise() {
  const c = g('f-canvas'); if (!F || g('v-frise').hidden) return;
  const ligneH = 44, haut = 26, gauche = 120, n = F.lignes.length;
  const w = c.clientWidth, h = haut + Math.max(1, n) * ligneH + 6, dpr = window.devicePixelRatio || 1;
  c.style.height = h + 'px'; c.width = Math.round(w * dpr); c.height = Math.round(h * dpr);
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.fillStyle = '#131C24'; x.fillRect(0, 0, w, h);
  const minutes = F.lignes.length ? F.lignes[0].el.length : (+g('f-h').value) * 60;
  const tx = k => gauche + (w - gauche - 8) * k / minutes;
  // The hours.
  x.font = '11px system-ui'; x.fillStyle = '#9AA7B4'; x.textAlign = 'center';
  for (let k = 0; k <= minutes; k++) {
    const t = F.debut + k * 60000, d = new Date(t), mn = utc() ? d.getUTCMinutes() : d.getMinutes(), hh = utc() ? d.getUTCHours() : d.getHours();
    if (mn !== 0) continue;
    const pas = minutes > 1440 ? 6 : minutes > 720 ? 3 : 1;
    if (hh % pas) continue;
    x.strokeStyle = '#22303B'; x.beginPath(); x.moveTo(tx(k), haut - 6); x.lineTo(tx(k), h); x.stroke();
    x.fillText(String(hh).padStart(2, '0') + 'h', tx(k), 14);
  }
  if (!n) { x.textAlign = 'left'; x.fillText('Aucun satellite : marque des ★ favoris (onglet Station).', gauche, haut + 24); return; }
  F.lignes.forEach((l, i) => {
    const y0 = haut + i * ligneH, base = y0 + ligneH - 6;
    x.textAlign = 'left'; x.font = (E && E.suivi && E.suivi.catnum === l.catnum ? 'bold ' : '') + '13px system-ui';
    x.fillStyle = E && E.suivi && E.suivi.catnum === l.catnum ? '#3FE0C8' : '#E8F0F7'; x.fillText(l.nom.slice(0, 16), 8, base - 10);
    x.strokeStyle = '#22303B'; x.beginPath(); x.moveTo(gauche, base); x.lineTo(w - 8, base); x.stroke();
    // Each pass as the hill of its elevation, its top written.
    let k = 0;
    while (k < l.el.length) {
      if (l.el[k] <= 0) { k++; continue; }
      let j = k, top = 0, kTop = k;
      while (j < l.el.length && l.el[j] > 0) { if (l.el[j] > top) { top = l.el[j]; kTop = j; } j++; }
      x.beginPath(); x.moveTo(tx(k), base);
      for (let m = k; m < j; m++) x.lineTo(tx(m), base - (ligneH - 12) * l.el[m] / 90);
      x.lineTo(tx(j - 1), base); x.closePath();
      x.fillStyle = top >= 40 ? 'rgba(91,214,138,.75)' : top >= 15 ? 'rgba(63,224,200,.6)' : 'rgba(154,167,180,.45)'; x.fill();
      x.fillStyle = '#E8F0F7'; x.font = '10px system-ui'; x.textAlign = 'center';
      if (tx(j) - tx(k) > 18) x.fillText(Math.round(top) + '°', tx(kTop), base - (ligneH - 12) * top / 90 - 2);
      k = j;
    }
  });
  // Now.
  x.strokeStyle = '#FFB454'; x.lineWidth = 2; x.beginPath(); x.moveTo(tx((Date.now() - F.debut) / 60000), haut - 6); x.lineTo(tx((Date.now() - F.debut) / 60000), h); x.stroke(); x.lineWidth = 1;
  c.onclick = ev => { const r = c.getBoundingClientRect(), i = Math.floor((ev.clientY - r.top - haut) / ligneH); if (F.lignes[i]) suivre(F.lignes[i].catnum); };
}
setInterval(() => { if (!g('v-frise').hidden) chargeFrise(); }, 60000);
window.addEventListener('resize', dessineFrise);

// ------------------------------------------------------------------ skeds
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
async function calculeSked() {
  const loc = v('k-loc').toUpperCase(); if (!loc) return;
  try { localStorage.setItem('sked-loc', loc); } catch (x) {}
  txt('k-etat', 'calcul…');
  let j = null;
  try { j = await (await fetch('api/sked?catnum=' + g('k-sat').value + '&loc=' + enc(loc) + '&minEl=' + enc(v('k-min')))).json(); } catch (x) {}
  if (!j || !j.ok) { txt('k-etat', j && j.erreur ? j.erreur : 'échec'); return; }
  K = j.fenetres;
  txt('k-etat', K.length + ' fenêtre(s) commune(s)');
  g('k-liste').innerHTML = K.map((w, i) => '<tr><td>' + jourHeure(w.de) + '</td><td>' + hm(w.a) + '</td><td>' + duree(w.a - w.de) +
    '</td><td>' + w.elMoi + '° · az ' + w.azMoi + '°</td><td>' + w.elAutre + '° · az ' + w.azAutre + '°</td><td style="white-space:nowrap"><button title="Rejouer sur la carte" onclick="rejoueSked(' + i + ')">▶</button> ' +
    '<button title="Mettre à l’agenda" onclick="skedAgenda(' + i + ')">📅</button></td></tr>').join('') ||
    '<tr><td colspan="6" class="petit">aucune fenêtre commune en 48 h</td></tr>';
  if (K.length) rejoueSked(0); else { g('k-anim').hidden = true; g('k-vide').hidden = false; KT = null; }
}
// The sked replayed: both skies and the map, as on the phone.
let KT = null, kF = 0, kJoue = false, kAvant = 0;
const K_DUREE = 15000;   // one full replay, ms
async function rejoueSked(i) {
  const w = K[i];
  let j = null;
  try { j = await (await fetch('api/sked/trace?catnum=' + g('k-sat').value + '&loc=' + enc(v('k-loc')) + '&de=' + w.debutTrace + '&a=' + w.finTrace)).json(); } catch (x) {}
  if (!j || !j.ok || !j.points.length) return;
  // Longitudes made continuous (from mine), so a track over the date line stays one line.
  let ref = j.moi.lon;
  j.points.forEach(p => { while (p[6] - ref > 180) p[6] -= 360; while (p[6] - ref < -180) p[6] += 360; ref = p[6]; });
  while (j.autre.lon - j.moi.lon > 180) j.autre.lon -= 360; while (j.autre.lon - j.moi.lon < -180) j.autre.lon += 360;
  KT = j; KT.w = w; KT.loc = v('k-loc').toUpperCase(); KT.nom = g('k-sat').selectedOptions[0].textContent;
  [...g('k-liste').rows].forEach((r, k) => r.classList.toggle('choisi', k === i));
  txt('k-titre', KT.nom + ' · ' + jourHeure(w.de) + ' → ' + hm(w.a));
  txt('k-lmoi', 'Moi' + (E && E.locator ? ' (' + E.locator + ')' : '') + ' · max ' + w.elMoi + '°');
  txt('k-llui', KT.loc + ' · max ' + w.elAutre + '°');
  g('k-anim').hidden = false; g('k-vide').hidden = true;
  kF = 0; kJoue = true; kAvant = 0; txt('k-joue', '⏸');
  cadreSked(); dessineSked();
  requestAnimationFrame(boucleSked);
}
async function videoSked() {
  if (!KT) return;
  const w = KT.w;
  const j = await post('api/sked/video?catnum=' + g('k-sat').value + '&loc=' + enc(KT.loc) + '&de=' + w.de + '&a=' + w.a + '&debutTrace=' + w.debutTrace + '&finTrace=' + w.finTrace);
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
  return { x, w, h, px: lon => ox + wxk(lon) * s, py: lat => oy + wyq(lat) * s };
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
  if (!nom) return;
  x.font = 'bold 12px system-ui'; x.textAlign = 'left'; x.lineWidth = 3; x.strokeStyle = 'rgba(255,255,255,.85)';
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
  // The map.
  const m = fondCarte(g('k-carte'), KV), x = m.x;
  empreinte(m, cour[5], cour[6], cour[7]);
  // The ground track, the common window in green, the part already flown darker.
  const P = KT.points.map(p => [p[5], p[6], p[0]]);
  traceCarte(m, P, 'rgba(30,40,55,.55)', 1.5, [5, 4]);
  traceCarte(m, P.filter(p => p[2] >= KT.w.de && p[2] <= KT.w.a), '#2E9E5B', 4);
  traceCarte(m, P.filter(p => p[2] <= cour[0]).concat([[cour[5], cour[6]]]), '#0B6E63', 2);
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

// ------------------------------------------------------------------ the followed satellite's map
let ecranStation = 'ciel';
try { ecranStation = localStorage.getItem('ecran-station') === 'carte' ? 'carte' : 'ciel'; } catch (x) {}
function montreEcran(q) {
  ecranStation = q;
  try { localStorage.setItem('ecran-station', q); } catch (x) {}
  g('ciel').style.display = q === 'ciel' ? '' : 'none'; g('st-carte').hidden = q !== 'carte';
  g('b-ciel').classList.toggle('actif', q === 'ciel'); g('b-carte').classList.toggle('actif', q === 'carte');
  dessineCarteStation();
}
const SV = { cx: 0.5, cy: 0.4, z: 1, libre: false };
let traceSol = null, traceVu = 0;
async function chargeTraceSol() {
  try { traceSol = await (await fetch('api/trace')).json(); } catch (x) { return; }
  // Continuous longitudes: a track over the date line stays one line.
  let ref = null;
  traceSol.points.forEach(p => { if (ref != null) { while (p[1] - ref > 180) p[1] -= 360; while (p[1] - ref < -180) p[1] += 360; } ref = p[1]; });
  traceVu = Date.now();
  dessineCarteStation(); dessineCarteCarnet();
}
function cadreStation() {
  const c = g('st-carte'); if (!c.clientWidth) return;
  // The whole world in width, the observer in the middle.
  SV.z = Math.max(0, Math.log2(c.clientWidth / 256));
  SV.cx = E && E.observateur ? wxk(E.observateur.lon) : 0.5;
  SV.cy = Math.max(0.5 - (1 - c.clientHeight / (256 * Math.pow(2, SV.z))) / 2, Math.min(0.5, wyq(25)));
}
function dessineCarteStation() {
  const c = g('st-carte'); if (!c || g('v-station').hidden || c.hidden || !c.clientWidth) return;
  if (!SV.libre) cadreStation();
  const m = fondCarte(c, SV), x = m.x, p = E && E.position;
  const nous = E && E.observateur;
  for (const d of [-360, 0, 360]) {
    if (traceSol && E && E.suivi && traceSol.catnum === E.suivi.catnum && traceSol.points.length > 1) {
      const t = traceSol.points.map(q => [q[0], q[1] + d]);
      traceCarte(m, t, 'rgba(11,110,99,.9)', 2);
      traceCarte(m, t.slice(0, traceSol.ici + 1), 'rgba(30,40,55,.45)', 2, [4, 4]);
    }
  }
  if (p) {
    empreinte(m, p.lat, p.lon, p.alt);
    for (const d of [-360, 0, 360]) {
      if (nous && p.el >= 0) { x.strokeStyle = '#FFB454'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(m.px(nous.lon + d), m.py(nous.lat)); x.lineTo(m.px(p.lon + d), m.py(p.lat)); x.stroke(); }
      if (nous) pointCarte(m, nous.lat, nous.lon + d, '#FFB454', 5, E.indicatif || 'Moi');
      pointCarte(m, p.lat, p.lon + d, p.el >= 0 ? '#5BD68A' : '#3FE0C8', 6, E.suivi ? E.suivi.nom : '');
    }
  } else if (nous) pointCarte(m, nous.lat, nous.lon, '#FFB454', 5, E.indicatif || 'Moi');
  pointsDuPassage(m);
  // The track asked again each minute, and at once for another satellite.
  const n = E && E.suivi ? E.suivi.catnum : 0;
  if (n && (!traceSol || traceSol.catnum !== n || Date.now() - traceVu > 60000) && Date.now() - traceVu > 3000) { traceVu = Date.now(); chargeTraceSol(); }
}
attacheCarte(g('st-carte'), SV, dessineCarteStation, cadreStation, 0);
// The carnet's small map: the same track, the satellite and its footprint, centred on it.
const CV = { cx: 0.5, cy: 0.4, z: 1, libre: false };
function cadreCarnet() {
  const c = g('cr-carte'); if (!c || !c.clientWidth) return;
  const p = E && E.position;
  CV.z = Math.max(0, Math.log2(c.clientWidth / 256) - 0.3);
  CV.cx = p ? wxk(p.lon) : (E && E.observateur ? wxk(E.observateur.lon) : 0.5);
  CV.cy = p ? Math.max(0.2, Math.min(0.8, wyq(p.lat))) : 0.4;
}
function dessineCarteCarnet() {
  const c = g('cr-carte'); if (!c || g('v-carnet').hidden || vueC !== 'station' || !c.clientWidth) return;
  if (!CV.libre) cadreCarnet();
  const m = fondCarte(c, CV), x = m.x, p = E && E.position, nous = E && E.observateur;
  for (const d of [-360, 0, 360]) {
    if (traceSol && E && E.suivi && traceSol.catnum === E.suivi.catnum && traceSol.points.length > 1) {
      const t = traceSol.points.map(q => [q[0], q[1] + d]);
      traceCarte(m, t, 'rgba(11,110,99,.9)', 2);
      traceCarte(m, t.slice(0, traceSol.ici + 1), 'rgba(30,40,55,.45)', 2, [4, 4]);
    }
  }
  if (p) {
    empreinte(m, p.lat, p.lon, p.alt);
    for (const d of [-360, 0, 360]) {
      if (nous && p.el >= 0) { x.strokeStyle = '#FFB454'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(m.px(nous.lon + d), m.py(nous.lat)); x.lineTo(m.px(p.lon + d), m.py(p.lat)); x.stroke(); }
      if (nous) pointCarte(m, nous.lat, nous.lon + d, '#FFB454', 4, '');
      pointCarte(m, p.lat, p.lon + d, p.el >= 0 ? '#5BD68A' : '#3FE0C8', 5, E.suivi ? E.suivi.nom : '');
    }
  }
  pointsDuPassage(m);
  // The track, asked as the Station's map asks it.
  const n = E && E.suivi ? E.suivi.catnum : 0;
  if (n && (!traceSol || traceSol.catnum !== n || Date.now() - traceVu > 60000) && Date.now() - traceVu > 3000) { traceVu = Date.now(); chargeTraceSol(); }
}
attacheCarte(g('cr-carte'), CV, dessineCarteCarnet, cadreCarnet, 0);
montreEcran(ecranStation)
async function skedAgenda(i) { const w = K[i]; await post('api/agenda/sked?catnum=' + g('k-sat').value + '&loc=' + enc(v('k-loc')) + '&de=' + w.de + '&a=' + w.a); alert('Sked mis à l’agenda.'); }

// ------------------------------------------------------------------ QO-100
let O = null, OP = null;
/** 10 489 750 000 Hz → "10 489,750 000". */
function qFmt(hz) { const m = Math.floor(hz / 1e6), r = String(hz % 1e6).padStart(6, '0');
  return String(m).replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + ',' + r.slice(0, 3) + ' ' + r.slice(3); }
function qKhz(hz) { return (hz / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 1 }) + ' kHz'; }
async function chargeQo100(plan) {
  try { const j = await (await fetch('api/qo100' + (plan || !OP ? '?plan=1' : ''))).json(); if (j.segments) OP = j; O = j; } catch (x) { return; }
  dessineQo100();
  // The Sun's dates take a second or two the first time.
  if (!O.ciel) setTimeout(() => { if (!g('v-qo100').hidden) chargeQo100(); }, 1500);
}
async function qPost(a) { const j = await post('api/qo100/' + a); await chargeQo100(); return j; }
function qPas(d) { qPost('pas?hz=' + d); }
function qAller() {
  const x = parseFloat(v('o-aller').replace(',', '.').replace(/\s/g, ''));
  if (!x) return;
  qPost('frequence?hz=' + Math.round(x < 20000 ? x * 1e6 : x));
}
function qMemoire() { qPost('memoire?nom=' + enc(v('o-nom'))); g('o-nom').value = ''; }
function qOublie() { qPost('oublie?hz=' + O.descente); }
function qNouvelle() { const n = v('o-nouvelle'); if (!n) return; g('o-nouvelle').value = ''; qPost('nouvelle?nom=' + enc(n)); }
async function qCaleMesure() { const j = await qPost('caler-mesure'); if (j && j.ok === false && j.erreur) txt('o-cmsg', j.erreur); }
function qOl(sens, ol) { qPost('ol?sens=' + sens + '&hz=' + ol); }
async function qCale() {
  const j = await qPost('balise-entendue?mhz=' + enc(v('o-entendue')));
  txt('o-cmsg', j && j.ok === false ? j.message : '');
}
async function qMesure() {
  const j = await post('api/qo100/mesure?sens=' + g('o-msens').value + '&ciel=' + enc(v('o-mciel')) + '&poste=' + enc(v('o-mposte')));
  if (j) { g('o-mmsg').textContent = j.message; g('o-mmsg').style.color = j.ok ? 'var(--vert)' : 'var(--ambre)'; }
  chargeQo100();
}
const COUL_USAGE = { BALISE: '#E5484D', CW: '#6F8CFF', NUMERIQUE: '#B07CFF', PHONIE: '#3FE0C8', DIFFUSION: '#FFB454', URGENCE: '#FF6A60', MIXTE: '#9AA7B4' };
function dessineQo100() {
  if (!O || !OP || g('v-qo100').hidden) return;
  txt('o-dl', qFmt(O.descente)); txt('o-ul', qFmt(O.montee));
  g('o-bbalise').className = O.surBalise ? 'primaire' : '';
  txt('o-bords', '↓ ' + qKhz(O.descente - O.tp.bas) + ' · ↑ ' + qKhz(O.tp.haut - O.descente));
  g('o-bords').style.color = Math.min(O.descente - O.tp.bas, O.tp.haut - O.descente) < 5000 ? 'var(--ambre)' : '';
  // Memories: the band plan's markers, then mine.
  const cle = JSON.stringify(O.memoires) + O.descente;
  if (g('o-memoires').dataset.vu !== cle) {
    g('o-memoires').dataset.vu = cle;
    g('o-memoires').innerHTML = O.memoires.map(m => '<button class="' + (Math.abs(m.hz - O.descente) < 500 ? 'ici' : '') + (m.fixe ? '' : ' perso') +
      '" title="' + qFmt(m.hz) + '" onclick="qPost(\'frequence?hz=' + m.hz + '\')">' + esc(m.nom) + '</button>').join('');
  }
  g('o-oublie').disabled = !O.memoires.some(m => !m.fixe && m.hz === O.descente);
  // Segment.
  txt('o-seg', O.segment.nom || 'Hors du transpondeur');
  txt('o-larg', O.segment.largeur ? O.segment.largeur + ' Hz au plus' : '');
  txt('o-interdit', O.emission ? '' : 'Émission interdite ici');
  txt('o-avert', O.segment.avert);
  // The rig.
  g('o-poste').checked = O.auPoste;
  txt('o-prx', mhz(O.posteRx)); txt('o-ptx', mhz(O.posteTx));
  txt('o-hors', O.atteignable ? '' : 'Hors des bandes du poste : vérifie les oscillateurs de la chaîne de station.');
  txt('o-statut', O.statut || (O.auPoste ? (O.catConnecte ? 'Le poste suit.' : 'Poste non connecté (onglet Station).') : ''));
  // Calibration.
  txt('o-calage', (O.calage > 0 ? '+' : '') + O.calage.toLocaleString('fr-FR') + ' Hz');
  // Transponders.
  const tps = OP.transpondeurs.map(t => '<label class="petit"><input type="radio" name="o-tp"' + (t.cle === O.transpondeur ? ' checked' : '') +
    ' onchange="qPost(\'transpondeur?cle=' + t.cle + '\')"> ' + esc(t.nom) + '</label>').join(' ');
  if (g('o-tps').dataset.vu !== tps) { g('o-tps').dataset.vu = tps; g('o-tps').innerHTML = tps; }
  g('o-balayage').checked = O.sansBride;
  // The chain.
  const ch = O.chaines.map(n => '<option' + (n === O.chaine ? ' selected' : '') + '>' + esc(n) + '</option>').join('');
  if (g('o-chaine').dataset.vu !== ch && document.activeElement !== g('o-chaine')) { g('o-chaine').dataset.vu = ch; g('o-chaine').innerHTML = ch; }
  const ols = (desc, ol) => {
    const l = OP.presets.filter(p => p.descente === desc);
    const conn = l.some(p => p.ol === ol);
    return '<option value="0"' + (ol ? '' : ' selected') + '>direct (pas de convertisseur)</option>' +
      l.map(p => '<option value="' + p.ol + '"' + (p.ol === ol ? ' selected' : '') + '>' + esc(p.nom) + ' · ' + (p.ol / 1e6).toFixed(3) + ' MHz</option>').join('') +
      (ol && !conn ? '<option value="' + ol + '" selected>mesuré · ' + (ol / 1e6).toFixed(6) + ' MHz</option>' : '');
  };
  for (const [id, d, ol] of [['o-olrx', true, O.olRx], ['o-oltx', false, O.olTx]]) {
    const h = ols(d, ol); if (g(id).dataset.vu !== h && document.activeElement !== g(id)) { g(id).dataset.vu = h; g(id).innerHTML = h; }
  }
  txt('o-beta', O.olTx ? 'Oscillateur de montée : vérifie ce que le poste affiche avant d’émettre.' : '');
  // The dish and the Sun.
  const k = O.ciel;
  let h = '';
  if (!k) h = 'Calcul en cours…';
  else if (k.inconnu) h = 'Position de la station inconnue : renseigne ton locator (Réglages).';
  else if (!k.visible) h = 'Le satellite est sous l’horizon depuis cette position.';
  else {
    h = '<div class="paire"><span>Azimut <b class="val">' + k.az.toFixed(1) + '°</b></span><span>Élévation <b class="val">' + k.el.toFixed(1) + '°</b></span>' +
      '<span>Skew (LNB) <b class="val">' + (k.skew > 0 ? '+' : '') + k.skew.toFixed(1) + '°</b></span></div>' +
      '<div style="margin-top:6px">Rotation du LNB vue de derrière la parabole, positive dans le sens des aiguilles d’une montre.</div>';
    if (k.soleilAz) h += '<div style="margin-top:8px"><b>Passage du Soleil en azimut</b> : ' + jourHeure(k.soleilAz) + '. À cette heure-là, l’ombre d’un piquet vertical part vers ' +
      Math.round(k.ombre) + '° : la parabole regarde exactement à l’opposé. Il ne reste que l’élévation, au niveau à bulle.</div>';
    h += '<div style="margin-top:8px"><b>Passages du Soleil derrière le satellite</b></div>' + (k.transits.length ? k.transits.map(t =>
      '<div>' + jourHeure(t.t) + ' · à ' + t.ecart.toFixed(2) + '° · pendant ' + Math.max(1, Math.round(t.duree / 60)) + ' min</div>').join('') +
      '<div style="margin-top:4px">La réception s’effondre pendant ces minutes : ce n’est pas une panne. L’ombre de la source se centre au fond de la parabole quand le pointage est juste.</div>'
      : '<div>Aucun dans l’année qui vient.</div>');
  }
  if (g('o-ciel').dataset.vu !== h) { g('o-ciel').dataset.vu = h; g('o-ciel').innerHTML = h; }
  g('o-cle').checked = O.aLaCle;
  txt('o-crx', mhz(O.cleRx));
  txt('o-cmsg2', !O.cleAtteignable ? 'hors de la plage de la clé : vérifie le LNB' : O.aLaCle && !(OPan && OPan.sdr) ? 'clé arrêtée (onglet SDR)' : '');
  dessineReglette(); dessineLoupe(); dessinePano();
}
// The panorama: the dongle's whole band on the ruler's scale, newest row on top.
let OPan = null, oPanHist = [], oPanTrames = -1;
async function ticPano() {
  if (g('v-qo100').hidden) return;
  try { OPan = await (await fetch('api/qo100/panorama?n=' + Math.max(128, Math.min(1024, g('o-pano').clientWidth)))).json(); } catch (x) { return; }
  if (OPan.colonnes && OPan.trames !== oPanTrames) {
    oPanTrames = OPan.trames;
    if (oPanHist.length && oPanHist[0].length !== OPan.colonnes.length) oPanHist = [];
    oPanHist.unshift(OPan.colonnes); if (oPanHist.length > 200) oPanHist.length = 200;
  }
  const b = OPan.balise;
  g('o-balise').innerHTML = !OPan.sdr ? 'La clé SDR ne tourne pas : la démarrer (onglet SDR) et cocher « Appliquer à la clé ».'
    : !b ? 'Balise non vue. Vérifier le pointage, ou attendre quelques secondes.'
    : 'Balise médiane vue par la clé : écart <b style="color:' + (b.ok ? 'var(--vert)' : 'var(--ambre)') + '">' + (b.ecart > 0 ? '+' : '') + b.ecart + ' Hz</b>, ' +
      '<b style="color:' + (b.rapport >= 15 ? 'var(--vert)' : 'var(--ambre)') + '">' + b.rapport.toFixed(1) + ' dB</b> au-dessus du bruit' +
      (b.ok ? ' — le calage est bon.' : ' — le LNB a dérivé : le bouton écrit l’écart dans le calage.');
  g('o-bmesure').disabled = !b || b.ok;
  dessinePano();
}
setInterval(ticPano, 300);
function dessinePano() {
  if (!OP || g('v-qo100').hidden) return;
  const c = g('o-pano'), w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  const [x] = toile(c);
  x.fillStyle = '#06131A'; x.fillRect(0, 0, w, h);
  txt('o-pano-msg', oPanHist.length ? '' : (OPan && OPan.sdr ? 'Le panorama arrive…' : 'Le panorama : la clé SDR démarrée (onglet SDR) et réglée sur QO-100 (« Appliquer à la clé »).'));
  if (!oPanHist.length) return;
  // The scale: at least 20 dB, or plain noise would paint as a busy transponder.
  let lo = 1e9, hi = -1e9;
  for (const r of oPanHist) for (const v of r) if (v != null) { if (v < lo) lo = v; if (v > hi) hi = v; }
  if (hi < lo + 20) hi = lo + 20;
  const n = oPanHist[0].length, rows = Math.min(oPanHist.length, h), img = x.createImageData(Math.max(1, Math.round((w - 8) * dpr)), Math.max(1, Math.round(rows * dpr)));
  const W = img.width, H = img.height;
  for (let yy = 0; yy < H; yy++) {
    const r = oPanHist[Math.min(oPanHist.length - 1, Math.floor(yy / dpr))];
    for (let xx = 0; xx < W; xx++) {
      const v = r[Math.min(n - 1, Math.floor(xx * n / W))];
      const k = 4 * (yy * W + xx);
      const [cr, cg, cb] = v == null ? [40, 40, 44] : rCouleur(v, lo, hi);
      img.data[k] = cr; img.data[k + 1] = cg; img.data[k + 2] = cb; img.data[k + 3] = 255;
    }
  }
  x.setTransform(1, 0, 0, 1, 0, 0); x.putImageData(img, Math.round(4 * dpr), 0); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  // The beacons, the frequency listened to.
  const b = OP.regletteBas, t = OP.regletteHaut, X = f => 4 + (w - 8) * (f - b) / (t - b);
  x.strokeStyle = 'rgba(229,72,229,.8)'; x.setLineDash([3, 3]);
  for (const s of OP.segments) if (s.usage === 'BALISE' && s.repere) { x.beginPath(); x.moveTo(X(s.repere), 0); x.lineTo(X(s.repere), h); x.stroke(); }
  x.setLineDash([]); x.strokeStyle = '#FFFFFF'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(X(O.descente), 0); x.lineTo(X(O.descente), 8); x.stroke(); x.lineWidth = 1;
}
/** The canvas at its displayed size; its 2D context. */
function toile(c) {
  const w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0); return [x, w, h];
}
function dessineReglette() {
  const [x, w, h] = toile(g('o-reglette')), b = OP.regletteBas, t = OP.regletteHaut, X = f => 4 + (w - 8) * (f - b) / (t - b);
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  // The transponder chosen.
  x.fillStyle = 'rgba(63,224,200,.07)'; x.fillRect(X(Math.max(b, O.tp.bas)), 0, X(Math.min(t, O.tp.haut)) - X(Math.max(b, O.tp.bas)), h);
  for (const s of OP.segments) {
    x.fillStyle = COUL_USAGE[s.usage] || '#9AA7B4'; x.globalAlpha = .75;
    x.fillRect(X(s.bas), 22, Math.max(1.5, X(s.haut) - X(s.bas) - 1), 26); x.globalAlpha = 1;
    if (X(s.haut) - X(s.bas) > 44) { x.fillStyle = '#06131A'; x.font = '600 10px system-ui'; x.textAlign = 'center'; x.fillText(s.nom.slice(0, Math.floor((X(s.haut) - X(s.bas)) / 6)), (X(s.bas) + X(s.haut)) / 2, 39); }
  }
  // My memories, then the 50 kHz ticks.
  x.fillStyle = '#FFB454'; O.memoires.filter(m => !m.fixe).forEach(m => { x.beginPath(); x.moveTo(X(m.hz), 50); x.lineTo(X(m.hz) - 4, 57); x.lineTo(X(m.hz) + 4, 57); x.fill(); });
  x.fillStyle = '#9AA7B4'; x.strokeStyle = '#22303B'; x.font = '10px system-ui'; x.textAlign = 'center';
  for (let f = Math.ceil(b / 50000) * 50000; f <= t; f += 50000) {
    x.beginPath(); x.moveTo(X(f), 60); x.lineTo(X(f), f % 100000 === 0 ? 70 : 66); x.stroke();
    if (f % 100000 === 0 && X(f) > 14 && X(f) < w - 14) x.fillText(((f - 10489000000) / 1000) + '', X(f), 84);
  }
  x.textAlign = 'left'; x.fillText('10 489 MHz +kHz', 4, 12);
  // The cursor.
  x.strokeStyle = '#FFFFFF'; x.lineWidth = 2; x.beginPath(); x.moveTo(X(O.descente), 16); x.lineTo(X(O.descente), 56); x.stroke(); x.lineWidth = 1;
  x.fillStyle = '#FFFFFF'; x.beginPath(); x.moveTo(X(O.descente), 16); x.lineTo(X(O.descente) - 5, 8); x.lineTo(X(O.descente) + 5, 8); x.fill();
}
const LOUPE = 25000;
function dessineLoupe() {
  const [x, w, h] = toile(g('o-loupe')), c = O.descente, X = f => w / 2 + (w / 2 - 4) * (f - c) / LOUPE;
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  for (const s of OP.segments) {
    if (s.haut < c - LOUPE || s.bas > c + LOUPE) continue;
    x.fillStyle = COUL_USAGE[s.usage] || '#9AA7B4'; x.globalAlpha = .35;
    x.fillRect(Math.max(0, X(s.bas)), 0, Math.min(w, X(s.haut)) - Math.max(0, X(s.bas)), 30); x.globalAlpha = 1;
  }
  x.strokeStyle = '#3B4B58'; x.fillStyle = '#9AA7B4'; x.font = '10px system-ui'; x.textAlign = 'center';
  for (let f = Math.ceil((c - LOUPE) / 1000) * 1000; f <= c + LOUPE; f += 1000) {
    const k = f % 5000 === 0; x.beginPath(); x.moveTo(X(f), 30); x.lineTo(X(f), k ? 42 : 36); x.stroke();
    if (k && X(f) > 12 && X(f) < w - 12) x.fillText(((f % 1e6) / 1000).toFixed(0), X(f), 56);
  }
  x.fillStyle = '#FFB454'; O.memoires.forEach(m => { if (Math.abs(m.hz - c) < LOUPE) { x.fillRect(X(m.hz) - 1, 0, 2, 30); } });
  x.strokeStyle = '#FFFFFF'; x.lineWidth = 2; x.beginPath(); x.moveTo(w / 2, 0); x.lineTo(w / 2, 44); x.stroke(); x.lineWidth = 1;
  x.fillStyle = '#9AA7B4'; x.textAlign = 'left'; x.fillText('± 25 kHz', 4, 66);
}
(function () {
  // The ruler: a click, a drag, the wheel. Rounded to 100 Hz, as the knob.
  const r = g('o-reglette'); let tire = false, dernier = 0;
  const f = ev => { const b = r.getBoundingClientRect(); return Math.round((OP.regletteBas + (OP.regletteHaut - OP.regletteBas) * (ev.clientX - b.left - 4) / (b.width - 8)) / 100) * 100; };
  r.addEventListener('pointerdown', ev => { if (!OP) return; tire = true; r.setPointerCapture(ev.pointerId); qPost('frequence?hz=' + f(ev)); });
  r.addEventListener('pointermove', ev => { if (!tire || Date.now() - dernier < 120) return; dernier = Date.now(); qPost('frequence?hz=' + f(ev)); });
  r.addEventListener('pointerup', ev => { if (tire) qPost('frequence?hz=' + f(ev)); tire = false; });
  g('o-pano').addEventListener('click', ev => { if (!OP) return; qPost('frequence?hz=' + f(ev)); });
  const l = g('o-loupe');
  l.addEventListener('click', ev => { if (!O) return; const b = l.getBoundingClientRect();
    qPost('frequence?hz=' + Math.round((O.descente + LOUPE * (ev.clientX - b.left - b.width / 2) / (b.width / 2 - 4)) / 100) * 100); });
  // The wheel: on the frequency, the ruler or the loupe.
  for (const e of [g('o-dl'), r, l, g('o-pano')]) e.addEventListener('wheel', ev => { ev.preventDefault(); qPas((ev.deltaY < 0 ? 1 : -1) * (ev.shiftKey ? 1000 : 100)); }, { passive: false });
  // The arrows, when nothing is being typed.
  document.addEventListener('keydown', ev => {
    if (g('v-qo100').hidden || /INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) return;
    const d = { ArrowLeft: -100, ArrowRight: 100, ArrowDown: -1000, ArrowUp: 1000 }[ev.key];
    if (d) { ev.preventDefault(); qPas(d); }
  });
  window.addEventListener('resize', dessineQo100);
})();

// ------------------------------------------------------------------ SDR
let R = null, rSpan = 48000, rCascade = [], rTrames = -1, rDernier = null;
const R820T = [0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496];
g('r-gain').innerHTML = '<option value="-1">automatique</option>' + R820T.map(x => '<option value="' + x + '">' + (x / 10).toLocaleString('fr-FR') + ' dB</option>').join('');
async function rRegle(q) { await post('api/sdr/reglage?' + q); ticSdr(); }
async function ticSdr() {
  // The log's live station shows the dongle too.
  if (g('v-sdr').hidden && g('v-carnet').hidden) return;
  try { R = await (await fetch('api/sdr')).json(); } catch (x) { return; }
  const r = R;
  g('r-pastille').className = 'pastille ' + (r.actif ? 'on' : 'off'); txt('r-pastille', r.actif ? 'réception' : 'arrêtée');
  txt('r-info', r.actif ? r.tuner + ' · ' + (r.debit / 1024).toFixed(1) + ' Mo/s' + (r.perdus ? ' · ' + r.perdus + ' blocs perdus' : '') : '');
  txt('r-erreur', r.erreur || '');
  txt('r-sat', r.satellite || (E && E.suivi ? E.suivi.nom : '(aucun)'));
  const coche = (id, b) => { if (document.activeElement !== g(id)) g(id).checked = b; };
  coche('r-suit', r.suit); coche('r-doppler', r.doppler); coche('r-deemph', r.deemph); coche('r-son', r.son); coche('r-agc', r.agc);
  coche('r-lance', r.lance); coche('r-bias', r.bias);
  const val = (id, x) => { if (document.activeElement !== g(id)) g(id).value = String(x); };
  val('r-mode', r.mode); val('r-bw', r.bw); val('r-sq', r.squelch); val('r-gain', r.gain);
  txt('r-sqv', r.squelch <= -120 ? 'ouvert' : r.squelch + ' dB');
  if (!v('r-mhz') && document.activeElement !== g('r-mhz')) g('r-mhz').placeholder = (r.manuelle / 1e6).toFixed(4).replace('.', ',');
  if (document.activeElement !== g('r-ppm')) g('r-ppm').value = r.ppm;
  if (document.activeElement !== g('r-hote')) g('r-hote').value = r.hote;
  if (document.activeElement !== g('r-port')) g('r-port').value = r.port;
  // The frequency heard: the sky's, the click's offset in.
  const ecoute = r.ciel + r.decalage;
  txt('r-freq', r.actif || r.ciel ? qFmt(ecoute) : '—');
  txt('r-detail', r.actif ? 'Doppler ' + ((r.ciel - r.repos) >= 0 ? '+' : '') + (r.ciel - r.repos) + ' Hz' + (r.decalage ? ' · décalage ' + (r.decalage > 0 ? '+' : '') + r.decalage + ' Hz' : '') : '');
  const pc = Math.max(0, Math.min(100, (r.niveau + 100) * 1.25));
  g('r-niveau').style.width = pc + '%'; txt('r-db', r.actif ? r.niveau.toFixed(0) + ' dB' : '');
  txt('r-sat-av', r.saturation > 0.002 ? 'saturation : baisser le gain' : '');
}
setInterval(ticSdr, 700);
async function spectreSdr() {
  if (g('v-sdr').hidden || !R || !R.actif) return;
  let j = null;
  try { j = await (await fetch('api/sdr/spectre?span=' + rSpan + '&n=' + Math.min(1024, Math.max(128, g('r-spectre').clientWidth)))).json(); } catch (x) { return; }
  if (!j.db || j.trames === rTrames) return;
  rTrames = j.trames; rDernier = j;
  rCascade.unshift(j.db); if (rCascade.length > 600) rCascade.length = 600;
  dessineSdr();
}
setInterval(spectreSdr, 120);
/** dB to a colour: dark blue for noise, yellow then white for strong signals. */
function rCouleur(db, bas, haut) {
  const t = Math.max(0, Math.min(1, (db - bas) / (haut - bas)));
  const c = [[6, 19, 26], [20, 60, 120], [30, 160, 200], [250, 220, 60], [255, 255, 255]];
  const k = t * (c.length - 1), i = Math.min(c.length - 2, Math.floor(k)), f = k - i;
  return c[i].map((x, j) => Math.round(x + (c[i + 1][j] - x) * f));
}
function dessineSdr() {
  const j = rDernier; if (!j) return;
  const tri = j.db.slice().sort((a, b) => a - b), bruit = tri[Math.floor(tri.length * 0.3)], haut = Math.max(bruit + 25, tri[tri.length - 1]);
  // Spectrum.
  const [x, w, h] = toile(g('r-spectre')), n = j.db.length;
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  const Y = d => h - 6 - (h - 12) * Math.max(0, Math.min(1, (d - (bruit - 10)) / (haut - bruit + 15)));
  x.strokeStyle = '#3FE0C8'; x.lineWidth = 1.2; x.beginPath();
  j.db.forEach((d, i) => { const px = i * w / n; i ? x.lineTo(px, Y(d)) : x.moveTo(px, Y(d)); }); x.stroke();
  // The channel heard, its width.
  const bw = (R.bw || ({ NFM: 16000, USB: 2400, LSB: 2400, AM: 6000 }[R.mode] || 16000)) ;
  const X = hz => w / 2 + (hz / j.span) * w;
  const dec = R.decalage || 0;
  const usb = R.mode === 'USB', lsb = R.mode === 'LSB';
  const a = usb ? dec : lsb ? dec - bw : dec - bw / 2, b = usb ? dec + bw : lsb ? dec : dec + bw / 2;
  x.fillStyle = 'rgba(255,180,84,.14)'; x.fillRect(X(a), 0, X(b) - X(a), h);
  x.strokeStyle = '#FFB454'; x.beginPath(); x.moveTo(X(dec), 0); x.lineTo(X(dec), h); x.stroke();
  // kHz ticks around the frequency heard.
  x.fillStyle = '#9AA7B4'; x.font = '10px system-ui'; x.textAlign = 'center';
  const pas = j.span > 100000 ? 20000 : j.span > 40000 ? 10000 : j.span > 20000 ? 5000 : 2000;
  for (let f = -Math.floor(j.span / 2 / pas) * pas; f <= j.span / 2; f += pas) if (f) x.fillText((f > 0 ? '+' : '') + f / 1000, X(f), 10);
  // Waterfall: one line a frame, the newest on top.
  const c = g('r-cascade'), cw = c.clientWidth, ch = c.clientHeight;
  if (c.width !== cw || c.height !== ch) { c.width = cw; c.height = ch; }
  const cx = c.getContext('2d'), img = cx.createImageData(cw, 2);
  // Scroll down two pixels, then the new line (twice, so the waterfall fills in a minute).
  cx.drawImage(c, 0, 2);
  for (let i = 0; i < cw; i++) {
    const [r, gg, bb] = rCouleur(j.db[Math.min(n - 1, Math.floor(i * n / cw))], bruit - 3, haut);
    for (const k of [i, i + cw]) { img.data[4 * k] = r; img.data[4 * k + 1] = gg; img.data[4 * k + 2] = bb; img.data[4 * k + 3] = 255; }
  }
  cx.putImageData(img, 0, 0);
}
for (const id of ['r-spectre', 'r-cascade']) g(id).addEventListener('click', ev => {
  if (!rDernier) return;
  const b = ev.currentTarget.getBoundingClientRect();
  const hz = Math.round(((ev.clientX - b.left) / b.width - 0.5) * rDernier.span / 10) * 10;
  post('api/sdr/decalage?hz=' + hz).then(ticSdr);
});

// ------------------------------------------------------------------ radiosondes
let S2 = null;
const SV2 = { cx: 0.5, cy: 0.35, z: 7, libre: false };
async function sPost(a) { const j = await post('api/sondes/' + a); ticSondes(); return j; }
async function sExporte(kml) { const j = await sPost('export?kml=' + kml); if (j && j.ok) location.href = 'sondes/' + encodeURIComponent(j.nom); }
async function ticSondes() {
  if (g('v-sondes').hidden) return;
  try { S2 = await (await fetch('api/sondes')).json(); } catch (x) { return; }
  const s = S2;
  g('s2-pastille').className = 'pastille ' + (s.actif ? 'on' : 'off'); txt('s2-pastille', s.actif ? 'réception' : 'arrêtée');
  txt('s2-freq', (s.freq / 1e6).toFixed(3).replace('.', ','));
  const mod = s.modeles.map(m => '<option value="' + m.id + '">' + esc(m.nom) + '</option>').join('');
  if (g('s2-modele').dataset.vu !== mod) { g('s2-modele').dataset.vu = mod; g('s2-modele').innerHTML = mod; }
  if (document.activeElement !== g('s2-modele')) g('s2-modele').value = s.modele;
  txt('s2-info', s.sdrActif ? 'la clé écoute un satellite : Démarrer la passe sur la sonde' : '');
  txt('s2-erreur', s.erreur || '');
  g('s2-exc').style.width = (s.actif ? s.excursion : 0) + '%';
  txt('s2-niv', s.actif ? s.niveau.toFixed(0) + ' dB' : '');
  txt('s2-trames', s.actif || s.trames ? s.trames + ' trame(s) gardée(s) · ' + s.rejetees + ' rejetée(s)' + (s.type ? ' · ' + s.type + ' ' + s.serie : '') : '');
  if (document.activeElement !== g('s2-auto')) g('s2-auto').checked = s.auto;
  txt('s2-dec', s.actif && s.decalage ? 'accord ' + (s.decalage > 0 ? '+' : '') + (s.decalage / 1000).toFixed(1) + ' kHz' : '');
  const sites = (s.sites || []).map(x => '<tr class="p" onclick="sPost(\'frequence?khz=' + x.hz / 1000 + '\')"><td>' + esc(x.nom) + '</td><td class="petit">' + x.km + ' km</td><td>' +
    (x.hz / 1e6).toFixed(3) + '</td><td class="petit">' + esc(x.sonde) + '</td><td class="petit">' + x.lachers.join(' · ') + '</td></tr>').join('') ||
    '<tr><td class="petit">renseigne ton locator (Réglages)</td></tr>';
  if (g('s2-sites').dataset.vu !== sites) { g('s2-sites').dataset.vu = sites; g('s2-sites').innerHTML = sites; }
  const jx = s.journaux.map(f => '<tr><td><a style="color:var(--cyan)" href="sondes/' + encodeURIComponent(f.nom) + '">' + esc(f.nom) + '</a></td><td class="petit">' +
    Math.max(1, Math.round(f.taille / 1024)) + ' Ko</td></tr>').join('') || '<tr><td class="petit">aucun</td></tr>';
  if (g('s2-journaux').dataset.vu !== jx) { g('s2-journaux').dataset.vu = jx; g('s2-journaux').innerHTML = jx; }
  // The last fix: distance and bearing large, what one reads while walking.
  const f = s.derniere;
  let h = 'Aucune trame encore : démarre la réception sur la fréquence d’une sonde en vol.';
  if (f) {
    const t = new Date(f.heure);
    h = '<div class="paire"><b style="font-size:16px">' + esc(s.type + ' ' + s.serie) + '</b>' + (f.origine ? '<span class="petit">partie sans doute de ' + esc(f.origine) + '</span>' : '') + '</div>' +
      (f.km != null ? '<div class="paire" style="margin-top:6px"><span class="s2-gros cyan">' + f.km.toLocaleString('fr-FR') + ' km</span><span class="s2-gros ambre">' + f.azimut + '° ' + f.rose + '</span></div>' : '') +
      '<div class="paire" style="margin-top:6px"><span>Altitude <b class="val">' + f.alt.toLocaleString('fr-FR') + ' m</b></span><span>Montée <b class="val">' + (f.montee > 0 ? '+' : '') + f.montee + ' m/s</b></span>' +
      '<span>Vitesse <b class="val">' + f.vitesse + ' km/h</b> vers ' + f.cap + '°</span>' + (f.sats ? '<span>GPS ' + f.sats + ' sat.</span>' : '') + '</div>' +
      '<div class="petit" style="margin-top:4px">' + f.lat.toFixed(5) + ' ' + f.lon.toFixed(5) + (f.heure ? ' · ' + t.toISOString().slice(11, 19) + ' UTC' : '') + ' · ' + (f.freq / 1e6).toFixed(3) + ' MHz' +
      (s.eclatement ? ' · éclatement à ' + s.eclatement.alt.toLocaleString('fr-FR') + ' m' : '') + '</div>' +
      (f.sure ? '' : '<div class="avert">Position incertaine (GPS sans assez de satellites).</div>');
  }
  if (g('s2-fix').dataset.vu !== h) { g('s2-fix').dataset.vu = h; g('s2-fix').innerHTML = h; }
  dessineSondes();
}
setInterval(ticSondes, 1000);
function cadreSondes() {
  const c = g('s2-carte'); if (!c.clientWidth || !S2) return;
  const pts = (S2.trace || []).slice();
  if (S2.moi) pts.push([S2.moi.lat, S2.moi.lon]);
  if (S2.atterrissage) pts.push([S2.atterrissage.lat, S2.atterrissage.lon]);
  if (!pts.length) return;
  const xs = pts.map(p => wxk(p[1])), ys = pts.map(p => wyq(p[0]));
  const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  SV2.cx = (x0 + x1) / 2; SV2.cy = (y0 + y1) / 2;
  // No flight yet: the region around the station (a sonde lands within 100–200 km).
  if (pts.length < 2) { SV2.z = 8; return; }
  SV2.z = Math.max(3, Math.min(13, Math.log2(Math.min(c.clientWidth / 256 / Math.max(x1 - x0, 1e-4), c.clientHeight / 256 / Math.max(y1 - y0, 1e-4)) * 0.8)));
}
function dessineSondes() {
  const c = g('s2-carte'); if (!c || g('v-sondes').hidden || !c.clientWidth || !S2) return;
  if (!SV2.libre) cadreSondes();
  const m = fondCarte(c, SV2), x = m.x;
  const t = S2.trace || [];
  traceCarte(m, t, '#E86FA8', 3);
  if (S2.atterrissage && t.length) traceCarte(m, [t[t.length - 1], [S2.atterrissage.lat, S2.atterrissage.lon]], '#E86FA8', 2, [5, 4]);
  if (S2.eclatement) pointCarte(m, S2.eclatement.lat, S2.eclatement.lon, '#FFFFFF', 5, 'éclatement');
  if (S2.atterrissage) pointCarte(m, S2.atterrissage.lat, S2.atterrissage.lon, '#E5484D', 6, 'atterrissage estimé');
  if (S2.moi) {
    if (S2.derniere) { x.strokeStyle = '#FFB454'; x.lineWidth = 1.5; x.setLineDash([3, 3]); x.beginPath(); x.moveTo(m.px(S2.moi.lon), m.py(S2.moi.lat)); x.lineTo(m.px(S2.derniere.lon), m.py(S2.derniere.lat)); x.stroke(); x.setLineDash([]); }
    pointCarte(m, S2.moi.lat, S2.moi.lon, '#FFB454', 6, E && E.indicatif ? E.indicatif : 'Moi');
  }
  if (S2.derniere) pointCarte(m, S2.derniere.lat, S2.derniere.lon, '#3FE0C8', 7, S2.serie || S2.type);
}
attacheCarte(g('s2-carte'), SV2, dessineSondes, cadreSondes, 2);

// ------------------------------------------------------------------ METEOR
let M = null, mChoisie = null, mApercu = -1;
async function mPost(a) { const j = await post('api/meteor/' + a); if (j && j.ok === false && j.erreur) txt('m-erreur', j.erreur); ticMeteor(); return j; }
async function ticMeteor() {
  if (g('v-meteor').hidden) return;
  try { M = await (await fetch('api/meteor')).json(); } catch (x) { return; }
  const m = M, n = Date.now();
  const enFichier = m.progression >= 0;
  g('m-pastille').className = 'pastille ' + (m.actif || enFichier ? 'on' : 'off');
  txt('m-pastille', m.actif ? 'réception' : enFichier ? 'fichier' : 'arrêté');
  const p = m.prochain;
  txt('m-prochain', p ? (n >= p.aos && n <= p.los ? 'En cours : ' + p.sat + ', fin à ' + hm(p.los) : 'Prochain passage : ' + p.sat + ' ' + jourHeure(p.aos) + ' (dans ' + duree(p.aos - n) + ')') +
    ' · élévation max ' + p.el + '°' : 'Aucun passage METEOR-M LRPT connu dans les 48 h.');
  if (document.activeElement !== g('m-auto')) g('m-auto').checked = m.auto;
  if (document.activeElement !== g('m-cotes')) g('m-cotes').checked = m.cotes;
  if (!m.actif && m.sdrErreur) txt('m-erreur', m.sdrErreur); else if (m.actif) txt('m-erreur', '');
  txt('m-verrou', m.actif || enFichier ? (m.verrou ? 'tenue' : 'cherchée') : '—');
  g('m-verrou').style.color = m.verrou ? 'var(--vert)' : '';
  txt('m-qualite', m.actif || enFichier ? m.qualite.toFixed(1) + ' dB' : '—');
  txt('m-freq', m.actif ? 'porteuse ' + (m.frequence > 0 ? '+' : '') + m.frequence + ' Hz' + (m.perdus ? ' · ' + m.perdus + ' blocs perdus' : '') : '');
  txt('m-trames', m.trames || m.ratees ? m.trames + ' trames bonnes · ' + m.ratees + ' perdues' : '');
  txt('m-lignes', m.lignes ? m.lignes + ' lignes · canaux ' + m.canaux.map(c => c - 63).join(', ') : '');
  g('m-prog').hidden = !enFichier; if (enFichier) g('m-prog').value = m.progression;
  txt('m-fichier', m.source === 'fichier' ? m.fichier + (m.erreur ? ' : ' + m.erreur : enFichier ? '' : ' : terminé') : '');
  // The gallery.
  const gal = m.images.map(i => '<img src="meteor/' + encodeURIComponent(i.nom) + '" title="' + esc(i.sat + ' · ' + i.note) + '" data-nom="' + esc(i.nom) + '"' +
    (i.nom === mChoisie ? ' class="choisi"' : '') + ' onclick="mChoisis(this.dataset.nom)">').join('');
  if (g('m-galerie').dataset.vu !== gal) { g('m-galerie').dataset.vu = gal; g('m-galerie').innerHTML = gal || '<span class="petit">Aucune image encore.</span>'; }
  // The picture shown: the preview while receiving, else the one chosen, else the newest.
  if ((m.actif || enFichier) && m.apercus > 0) {
    if (m.apercus !== mApercu) { mApercu = m.apercus; g('m-img').src = 'api/meteor/apercu.png?' + m.apercus; }
    txt('m-titre', m.satellite + ' · en cours'); txt('m-note', m.lignes + ' lignes'); g('m-lien').textContent = '';
  } else {
    const i = m.images.find(x => x.nom === mChoisie) || m.images[0];
    if (i) {
      const src = 'meteor/' + encodeURIComponent(i.nom);
      if (g('m-img').getAttribute('src') !== src) g('m-img').src = src;
      txt('m-titre', i.sat + ' · ' + (i.t ? jourHeure(i.t) : '')); txt('m-note', i.note + (i.source === 'file' ? ' · d’un enregistrement' : ''));
      g('m-lien').href = src; g('m-lien').textContent = 'Télécharger';
    } else { g('m-img').removeAttribute('src'); txt('m-titre', 'Aucune image'); txt('m-note', ''); g('m-lien').textContent = ''; }
  }
}
function mChoisis(nom) { mChoisie = nom; g('m-galerie').dataset.vu = ''; ticMeteor(); }
setInterval(ticMeteor, 1000);

// ------------------------------------------------------------------ FT8
let H = null, ft8Vu = '';
async function ticFt8() {
  if (g('v-ft8').hidden) return;
  try { H = await (await fetch('api/ft8')).json(); } catch (x) { return; }
  if (!H.present) return;
  txt('8-marche', H.enMarche ? 'Arrêter' : 'Écouter');
  if (document.activeElement !== g('8-mode')) g('8-mode').value = H.mode;
  txt('8-etat', !H.carte ? 'carte son arrêtée (onglet Son)' : H.enMarche ? H.tranches + ' créneaux · niveau ' + Math.round(H.niveau * 100) + ' %' : 'à l’arrêt');
  g('8-tranche').style.width = H.enMarche ? Math.round(H.avancement * 100) + '%' : '0';
  dessineCascade();
  const f = v('8-filtre').toUpperCase(), cq = g('8-cq').checked;
  const l = H.entendus.filter(e => (!cq || e.cq) && (!f || [e.texte, e.loc].join(' ').toUpperCase().includes(f)));
  const cle = JSON.stringify([f, cq, l.length, l.length ? l[0].t + l[0].texte : '']);
  if (cle === ft8Vu) return;
  ft8Vu = cle;
  let avant = 0;
  g('8-liste').innerHTML = l.map(e => {
    // A line between slots, as in a log of the band.
    const sep = avant && e.t !== avant ? ' style="border-top:2px solid var(--bord)"' : ''; avant = e.t;
    const coul = e.moi ? 'color:var(--rose);font-weight:800' : e.cq ? 'color:var(--vert);font-weight:700' : '';
    return '<tr' + sep + '><td>' + new Date(e.t).toISOString().slice(11, 19) + '</td><td>' + e.db + '</td><td>' + e.dt + '</td><td>' + e.hz +
      '</td><td style="font-family:ui-monospace,Consolas,monospace;' + coul + '">' + esc(e.texte) + (e.fait ? ' <span class="pastille on" title="déjà au carnet">✓</span>' : '') +
      '</td><td>' + (e.km == null ? '' : e.km) + '</td><td>' + (e.de ? '<button title="Préparer ce contact dans le carnet" onclick="noteFt8(\'' + esc(e.de) + '\',\'' + esc(e.loc) + '\',' + e.db + ')">✎</button>' : '') + '</td></tr>';
  }).join('') || '<tr><td colspan="7" class="petit">' + (H.enMarche ? 'rien encore décodé' : 'prêt') + '</td></tr>';
}
function dessineCascade() {
  const c = g('8-cascade'), w = c.clientWidth, h = c.clientHeight, dpr = window.devicePixelRatio || 1;
  if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
  const x = c.getContext('2d'); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  x.fillStyle = '#06131A'; x.fillRect(0, 0, w, h);
  const rows = H.cascade.map(b => Uint8Array.from(atob(b), ch => ch.charCodeAt(0)));
  const rh = h / 60;
  rows.forEach((r, i) => { const cw = w / r.length;
    for (let k = 0; k < r.length; k++) { const v = r[k]; if (v < 8) continue;
      x.fillStyle = 'rgb(' + Math.min(255, v * 2) + ',' + Math.min(255, v * 1.4) + ',' + (60 + v / 2) + ')'; x.fillRect(k * cw, i * rh, cw + 0.5, rh + 0.5); } });
  // The audio frequencies, each 500 Hz.
  x.fillStyle = 'rgba(232,240,247,.7)'; x.font = '10px system-ui';
  for (let f = 500; f < H.haute; f += 500) { const px = w * (f - H.basse) / (H.haute - H.basse); x.fillRect(px, 0, 1, 4); x.fillText(f, px + 2, 11); }
  const s = g('8-spectre'), sw = s.clientWidth, sh = s.clientHeight;
  if (s.width !== Math.round(sw * dpr)) { s.width = Math.round(sw * dpr); s.height = Math.round(sh * dpr); }
  const y = s.getContext('2d'); y.setTransform(dpr, 0, 0, dpr, 0, 0); y.clearRect(0, 0, sw, sh);
  if (H.spectre.length) { y.strokeStyle = '#3FE0C8'; y.beginPath();
    H.spectre.forEach((v, k) => { const px = sw * k / H.spectre.length, py = sh - 2 - v * (sh - 4); if (k) y.lineTo(px, py); else y.moveTo(px, py); }); y.stroke(); }
}
async function ft8Marche() { await post('api/ft8?on=' + (H && H.enMarche ? 0 : 1) + '&mode=' + g('8-mode').value); ticFt8(); }
function noteFt8(call, loc, db) {
  vue('carnet'); g('c-call').value = call; g('c-loc').value = loc || '';
  // An FT8 report is in dB, as it is said: "-12".
  g('c-rr').value = (db > 0 ? '+' : '') + db;
  g('c-call').dispatchEvent(new Event('input')); g('c-call').focus();
}
g('8-filtre').oninput = () => { ft8Vu = ''; ticFt8(); };
g('8-cq').onchange = () => { ft8Vu = ''; ticFt8(); };
setInterval(ticFt8, 1000);

// ------------------------------------------------------------------ APRS
let A = null, aVus = '';
// The KISS radio: its port, its state, where it listens.
let KS = null;
async function kPost(a) { const j = await post('api/kiss/' + a); if (j && j.ok === false && j.erreur) txt('kiss-etat', j.erreur); ticKiss(); return j; }
async function kConnecte() {
  txt('kiss-etat', 'connexion, passage en KISS…');
  await kPost('connecter?port=' + v('kiss-port') + '&vitesse=' + v('kiss-vitesse') + '&lignes=' + (g('kiss-lignes').checked ? 1 : 0));
}
function kType() {
  const t = v('kiss-type');
  g('kiss-dest').hidden = t !== 'message'; g('kiss-numero-l').hidden = t !== 'message';
  g('kiss-texte').placeholder = t === 'message' ? 'Texte (67 caractères)' : t === 'position' ? 'Commentaire (facultatif)' : 'Statut';
}
async function kEmet() {
  const t = v('kiss-type'), quoi = t === 'message' ? 'le message à ' + v('kiss-dest').toUpperCase() : t === 'position' ? 'ta position' : 'le statut';
  if (!confirm('Émettre maintenant ' + quoi + ' par la radio KISS (' + (KS && KS.source ? KS.source : '?') + ') ?')) return;
  txt('kiss-envoi', 'envoi…');
  const j = await post('api/kiss/emet?type=' + t + '&dest=' + enc(v('kiss-dest')) + '&texte=' + enc(v('kiss-texte')) +
    '&numero=' + (g('kiss-numero').checked ? 1 : 0) + '&chemin=' + v('kiss-chemin') + '&confirme=' + (g('kiss-confirme').checked ? 1 : 0));
  txt('kiss-envoi', j && j.ok ? (j.message || 'émis') : (j && j.erreur ? j.erreur : 'échec'));
  if (j && j.ok) g('kiss-texte').value = '';
  ticKiss(); ticAprs();
}
async function ticKiss() {
  try { KS = await (await fetch('api/kiss')).json(); } catch (x) { return; }
  if (KS.absent) { g('kiss-carte').hidden = true; return; }
  const p = g('kiss-port'), l = JSON.stringify(KS.ports);
  if (p.dataset.vu !== l) { p.dataset.vu = l; p.innerHTML = KS.ports.map(x => '<option value="' + x.index + '">' + esc(x.libelle) + '</option>').join('') || '<option value="-1">aucun port série</option>'; if (KS.port != null) p.value = String(KS.port); }
  if (document.activeElement !== g('kiss-vitesse') && KS.connecte) g('kiss-vitesse').value = String(KS.vitesse);
  if (document.activeElement !== g('kiss-travail')) g('kiss-travail').value = KS.travail;
  if (document.activeElement !== g('kiss-doppler')) g('kiss-doppler').checked = KS.doppler;
  if (document.activeElement !== g('kiss-lignes')) g('kiss-lignes').checked = KS.lignes;
  g('kiss-lignes-l').hidden = !KS.lignesAuChoix;
  g('kiss-pastille').className = 'pastille ' + (KS.connecte ? 'on' : 'off');
  txt('kiss-pastille', !KS.connecte ? 'déconnectée' : KS.muette ? 'muette' : KS.initialise ? 'en KISS' : 'connectée');
  if (KS.connecte) txt('kiss-etat', KS.nom + ' · ' + KS.vitesse + ' bauds' + (KS.frequence ? ' · ' + (KS.frequence / 1e6).toFixed(3).replace('.', ',') + ' MHz' : '') +
    ' · ' + KS.recues + ' trame(s) reçue(s)' + (KS.erreur ? ' · ' + KS.erreur : '') +
    (KS.muette ? ' — la radio n’a rien répondu : déjà en KISS, ou elle n’entend pas la ligne. Un TH-D72 veut RTS/DTR levés : coche la case ci-dessous (pour son port seulement), puis Déconnecter et Connecter.' : ''));
  else if (KS.erreur) txt('kiss-etat', KS.erreur);
  // Sending: who, from which path now, the options.
  txt('kiss-source', KS.source ? '· de ' + KS.source + (KS.chemin ? ' par ' + KS.chemin : '') : '· indicatif à renseigner (Réglages)');
  const s = g('kiss-ssid'); if (!s.options.length) s.innerHTML = Array.from({ length: 16 }, (_, i) => '<option value="' + i + '">' + (i ? '-' + i : 'aucun') + '</option>').join('');
  if (document.activeElement !== s) s.value = String(KS.ssid || 0);
  if (document.activeElement !== g('kiss-floue')) g('kiss-floue').checked = !!KS.floue;
  if (document.activeElement !== g('kiss-balise')) g('kiss-balise').checked = !!KS.balise;
  const lue = KS.frequence != null && (KS.fenetres || []).some(f => KS.frequence >= f[0] && KS.frequence <= f[1]);
  g('kiss-confirme-l').hidden = lue;
  if (KS.envoi && !g('kiss-envoi').textContent) txt('kiss-envoi', KS.envoi);
}
async function ticAprs() {
  if (!ouverte('aprs')) return;
  ticKiss();
  try { A = await (await fetch('api/aprs')).json(); } catch (x) { return; }
  if (!A.present) return;
  txt('a-n', String(A.paquets.length)); txt('a-stations', String(A.nbStations)); txt('a-iss', String(A.viaIss));
  txt('a-loin', A.loin == null ? '—' : String(Math.round(A.loin)));
  txt('a-etat', A.actif ? 'à l’écoute' : 'décodage arrêté (onglet Son : carte son et « Décoder l’APRS »)');
  dessineAprs();
}
function dessineAprs() {
  const f = v('a-filtre').toUpperCase(), iss = g('a-isss').checked;
  const l = A.paquets.filter(p => (!iss || p.iss) && (!f || [p.src, p.txt, p.dest, p.carre].join(' ').toUpperCase().includes(f)));
  const cle = JSON.stringify([f, iss, l.length, l.length ? l[0].t : 0]);
  if (cle === aVus) return;
  aVus = cle;
  g('a-liste').innerHTML = l.map(p => '<tr><td>' + hm(p.t, true) + '</td><td><b>' + esc(p.src) + '</b>' + (p.iss ? ' <span class="pastille on">ISS</span>' : '') +
    '</td><td class="petit">' + esc(p.type) + (p.dest ? ' → ' + esc(p.dest) : '') + '</td><td>' + esc(p.carre) + '</td><td>' + (p.km == null ? '' : p.km) +
    '</td><td class="petit">' + esc(p.chemin) + '</td><td>' + esc(p.txt) + '</td></tr>').join('') || '<tr><td colspan="7" class="petit">rien entendu</td></tr>';
}
g('a-filtre').oninput = () => { aVus = ''; dessineAprs(); };
g('a-isss').onchange = () => { aVus = ''; dessineAprs(); };
setInterval(ticAprs, 2000);
// Served by the phone: it hears while it records (MP3), its passes recorded through its agenda.
if (window.SATME_TELEPHONE) {
  g('s-note').textContent = 'MP3, comme sur le téléphone : il écoute en enregistrant. La source se choisit ici ; SatMe doit être à l’écran du téléphone pour prendre le micro.';
  g('s-auto-l').hidden = true;
  // The phone serves this page: no phone to join; its dongle on its USB port, following the satellite followed.
  g('carteTelephone').hidden = true;
  g('r-liaison').hidden = true;
  g('r-mhz').parentElement.hidden = true;
  g('m-chemin').parentElement.hidden = true;
  g('s2-auto').parentElement.hidden = true;
}
function ecoute(nom) { g('s-lecteur').src = 'enregistrements/' + nom; g('s-lecteur').play(); }
async function carteSon(arret) {
  const j = await post('api/son/carte?nom=' + (arret ? '' : enc(g('s-carte').value)));
  ticSon();
}
async function enregistre() { await post('api/son/enregistrer?on=' + (S && S.enregistrement ? 0 : 1)); ticSon(); }
async function coupeLos(on) { await post('api/son/options?coupe=' + (on ? 1 : 0)); ticSon(); }
// ------------------------------------------------------------------ automatic mode
let VA = null, vaVu = '';
async function veille(q) { if (q) await post('api/veille?' + q); ticVeille(); }
function veilleAjoute() {
  const q = v('va-sat').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!q) return;
  const s = tous.find(x => x.nom.toUpperCase().replace(/[^A-Z0-9]/g, '') === q) ||
            tous.find(x => x.nom.toUpperCase().replace(/[^A-Z0-9]/g, '').includes(q)) ||
            tous.find(x => String(x.catnum) === v('va-sat'));
  if (!s) { alert('Satellite inconnu du bulletin AMSAT.'); return; }
  g('va-sat').value = '';
  veille('ajoute=' + s.catnum);
}
g('va-sat').addEventListener('keydown', e => { if (e.key === 'Enter') veilleAjoute(); });
async function ticVeille() {
  try { VA = await (await fetch('api/veille')).json(); } catch (x) { VA = null; }
  document.querySelectorAll('.va').forEach(c => c.hidden = !VA);
  if (!VA) return;
  if (document.activeElement !== g('va-on')) g('va-on').checked = VA.on;
  if (document.activeElement !== g('va-son')) g('va-son').checked = VA.son;
  if (document.activeElement !== g('va-el')) g('va-el').value = VA.el;
  const p = VA.plan.find(l => l.cle === VA.enCours);
  const proch = VA.plan.find(l => l.garde);
  txt('va-etat', !VA.on ? '' : p ? ' · ' + p.sat + ' suivi (LOS ' + hm(p.los) + ')' : proch ? ' · prochain : ' + proch.sat + ' à ' + hm(proch.aos) : ' · aucun passage');
  const a = [];
  if (VA.on && !VA.sats.length) a.push('Ajoute au moins un satellite.');
  if (VA.on && !VA.cat) a.push('Poste non connecté : la fréquence ne suivra pas (onglet Station).');
  if (VA.on && !VA.rotor) a.push('Rotor non connecté.');
  else if (VA.on && !VA.rotorSuit) a.push('Rotor connecté mais « Suivre » n’est pas coché.');
  if (VA.on && VA.son && !VA.carte) a.push('Carte son arrêtée : pas d’enregistrement (onglet Son).');
  txt('va-alerte', a.join(' '));
  const cle = JSON.stringify([VA.sats, VA.plan, VA.enCours, VA.on]);
  if (cle === vaVu) return;
  vaVu = cle;
  g('va-sats').innerHTML = VA.sats.map(s => '<span class="pastille">' + esc(s.nom) + ' <a href="#" onclick="veille(\'retire=' + s.n + '\');return false" title="Retirer" style="color:var(--rose);text-decoration:none">✕</a></span>').join('') ||
    '<span class="petit">aucun satellite choisi</span>';
  g('va-plan').innerHTML = VA.plan.map(l => '<tr' + (l.cle === VA.enCours ? ' class="encours"' : '') + (l.garde ? '' : ' style="opacity:.55"') + '><td>' + jourHeure(l.aos) + ' → ' + hm(l.los) +
    '</td><td>' + esc(l.sat) + (l.perdu ? '<div class="petit">pendant ' + esc(l.perdu) + '</div>' : '') + '</td><td>' + l.el + '°</td><td>' + duree(l.los - l.aos) +
    '</td><td class="act"><label class="petit"><input type="checkbox" ' + (l.garde ? 'checked' : '') + (l.perdu ? ' disabled' : '') +
    ' onchange="veille((this.checked ? \'garde=\' : \'ecarte=\') + \'' + l.cle + '\')"> garder</label></td></tr>').join('') ||
    '<tr><td colspan="5" class="petit">' + (VA.sats.length ? (VA.calcul ? 'calcul…' : 'aucun passage dans les 24 h') : 'choisis des satellites') + '</td></tr>';
}
setInterval(() => { if (ouverte('agenda')) ticVeille(); }, 2000);
setInterval(ticSon, 1000);

lienRotor(); avertCat();
// The tab from the address (#carnet, a bookmark), else the last one seen.
// The tabs the server offers (the phone serves some of them): the others hidden, and left if open.
fetch('api/capacites').then(r => r.ok ? r.json() : null).then(j => {
  if (!j || !j.onglets) return;
  document.querySelectorAll('#onglets button[data-v]').forEach(b => { if (!j.onglets.includes(b.dataset.v)) b.hidden = true; });
  document.querySelectorAll('#onglets a').forEach(a => { if (!j.onglets.includes(a.getAttribute('href'))) a.hidden = true; });
  const ouvert = document.querySelector('#onglets button.actif');
  if (ouvert && ouvert.hidden) vue('station');
}).catch(() => {});
if (['station', 'carnet', 'agenda', 'carres', 'frise', 'sked', 'qo100', 'datv', 'sdr', 'sondes', 'meteor', 'son', 'sstv', 'aprs', 'ft8', 'reglages'].includes(location.hash.slice(1))) vue(location.hash.slice(1));
else try { const n = localStorage.getItem('vue'); if (n) vue(n); } catch (x) {}
tic(); chargeListe(); chargePorts(); chargeSatellites();
setInterval(chargeSatellites, 600000);
setInterval(tic, 1000);
// The TH-D72 panel: shown while it is the rig connected.
let TH = null;
async function thPost(a) { const j = await post('api/thd72/' + a); if (j && j.ok === false && j.erreur) txt('thd72-msg', j.erreur); setTimeout(ticThd72, 600); return j; }
async function ticThd72() {
  const montre = E && E.cat && E.cat.modele === 'THD72';
  g('cat-thd72-aide').hidden = g('cat-modele').value !== 'THD72';
  if (!montre) { g('thd72-carte').hidden = true; return; }
  try { TH = await (await fetch('api/thd72')).json(); } catch (x) { return; }
  g('thd72-carte').hidden = !TH.actif;
  if (!TH.actif) return;
  if (document.activeElement !== g('thd72-tx')) g('thd72-tx').value = String(TH.bandeTx);
  if (TH.message) txt('thd72-msg', TH.message);
  const ouvert = document.activeElement && document.activeElement.closest && document.activeElement.closest('#thd72-bandes');
  if (ouvert) return;
  const P2 = ['H', 'L', 'EL'];
  g('thd72-bandes').innerHTML = [0, 1].map(b => {
    const hz = TH.hz[b], pw = TH.puissance[b];
    return '<tr><td><b>' + (b ? 'B' : 'A') + '</b>' + (TH.bandeTx === b ? ' <span class="petit ambre">TX</span>' : ' <span class="petit cyan">RX</span>') +
      (TH.bandePtt === b ? ' <span class="petit">PTT</span>' : '') + '</td>' +
      '<td class="val">' + (hz ? (hz / 1e6).toFixed(4).replace('.', ',') : '—') + '</td>' +
      '<td><button onclick="thPost(\'pas?b=' + b + '&sens=-1\')">−</button><button onclick="thPost(\'pas?b=' + b + '&sens=1\')">+</button></td>' +
      '<td><input id="thd72-f' + b + '" placeholder="MHz" style="max-width:100px"><button onclick="thPost(\'frequence?b=' + b + '&mhz=\' + enc(v(\'thd72-f' + b + '\')))">Aller</button></td>' +
      '<td><select onchange="thPost(\'puissance?b=' + b + '&p=\' + this.value)">' + P2.map((n, i) => '<option value="' + i + '"' + (pw === i ? ' selected' : '') + '>' + n + '</option>').join('') + '</select></td></tr>';
  }).join('');
}
setInterval(ticThd72, 3000);
// The serial ports again every few seconds while the Station tab is open (a radio plugged in shows up), unless a list is open.
setInterval(() => {
  if (g('v-station').hidden) return;
  const a = document.activeElement;
  if (a && ['cat-port', 'cat-port-tx', 'rot-port'].includes(a.id)) return;
  chargePorts();
}, 5000);
setInterval(chargeListe, 30000);
setInterval(dessinePassages, 5000);
</script>
</body></html>
"""
}
