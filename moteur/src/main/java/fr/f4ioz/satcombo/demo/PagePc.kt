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

    /** The flags' font (Twemoji, CC BY 4.0), served at `api/police-drapeaux` by SatMe-PC and the phone. */
    val POLICE_DRAPEAUX: ByteArray? by lazy { PagePc::class.java.getResourceAsStream("drapeaux.woff2")?.use { it.readBytes() } }
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
    private fun icones(): String = org.json.JSONObject(listOf("person", "radio", "satellite_alt", "public", "smartphone", "save", "tune", "straighten", "mic")
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
  .dc-carte { margin-top:10px; border:1px solid var(--bord); border-radius:10px; padding:10px 12px; background:rgba(63,224,200,.05); }
  .dc-tete { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
  .dc-call { font-size:22px; letter-spacing:.5px; color:var(--cyan); }
  .dc-drapeau { font-size:24px; font-family:Drapeaux,system-ui; }
  .dc-message { margin-top:6px; font-style:italic; }
  .dc-chat { display:flex; flex-direction:column; gap:4px; margin-top:8px; max-height:220px; overflow-y:auto; }
  .dc-bulle { align-self:flex-start; max-width:85%; background:#1B2A36; border-radius:12px 12px 12px 3px; padding:5px 10px; overflow-wrap:anywhere; }
  .dc-bulle.moi { align-self:flex-end; background:rgba(63,224,200,.18); border-radius:12px 12px 3px 12px; }
  .dc-bulle .petit { margin-right:6px; }
  #d-cadre { position:relative; aspect-ratio:16/9; max-height:calc(100vh - 230px); background:#000; border-radius:8px; overflow:hidden; display:flex; align-items:center; justify-content:center; }
  #d-video { max-width:100%; max-height:100%; display:none; }
  #d-vide { position:absolute; color:var(--gris); font-size:15px; }
  #q-boite { margin-top:12px; border-top:1px solid var(--bord); padding-top:10px; }
  .q-haut { display:flex; gap:16px; align-items:center; flex-wrap:wrap; }
  #q-marge { font-family:ui-monospace,Consolas,monospace; font-size:30px; font-weight:800; white-space:nowrap; }
  #q-verdict { font-size:15px; font-weight:700; }
  .q-tuiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:6px; margin-top:10px; }
  .q-tuile { background:#0E1720; border-radius:8px; padding:6px 9px; font-size:12px; color:var(--gris); line-height:1.5; }
  .q-tuile b { color:var(--clair); font-family:ui-monospace,Consolas,monospace; font-size:15px; font-weight:600; }
  .q-courbes { display:grid; grid-template-columns:repeat(auto-fit,minmax(170px,1fr)); gap:6px; margin-top:8px; }
  .q-courbes canvas { width:100%; height:44px; background:#0E1720; border-radius:6px; display:block; }
  #q-pids table { border-collapse:collapse; width:100%; margin-top:6px; }
  #q-pids td, #q-pids th { padding:2px 6px; text-align:right; font-family:ui-monospace,Consolas,monospace; font-size:12px; }
  #q-pids th { color:var(--gris); font-weight:400; }
  #q-pids td:nth-child(2), #q-pids th:nth-child(2) { text-align:left; font-family:inherit; }
  .d-st { position:absolute; left:3%; right:3%; bottom:4%; display:flex; flex-direction:column; align-items:center; gap:4px; pointer-events:none; }
  .d-st div { background:rgba(0,0,0,.74); color:#fff; font-size:clamp(15px,2.3vw,27px); font-weight:600; line-height:1.3; padding:3px 12px; border-radius:6px; text-align:center; text-shadow:0 1px 2px #000; }
  .d-st .d-st-tr { font-size:clamp(13px,1.8vw,21px); font-style:italic; font-weight:500; color:#CFF6EE; }
  .d-st .vieux { opacity:.6; }
  .d-lu { position:absolute; top:8px; right:8px; background:rgba(0,0,0,.72); color:#fff; font-weight:800; letter-spacing:1px; padding:4px 10px; border-radius:6px; font-size:15px; pointer-events:none; }
  .d-lu.sur { box-shadow:0 0 0 2px var(--vert); }
  .dt-lu { margin-top:6px; padding:6px 9px; background:#0E1720; border-radius:10px; border-left:3px solid var(--cyan); }
  .dt-lu b { font-size:20px; letter-spacing:2px; }
  .dt-lu.sur { border-left-color:var(--vert); }
  #t-qr svg { width:100%; height:100%; display:block; border-radius:8px; }
  #visionneuse::backdrop { background:rgba(0,0,0,.75); }
  #vi-img { display:block; max-width:min(92vw,1280px); max-height:calc(92vh - 60px); min-width:320px; image-rendering:auto; border-radius:6px; cursor:zoom-out; background:#0E1720; }
  /* The flags drawn by the page itself: Windows (and a Linux without a colour emoji font) shows two letters instead. */
  @font-face { font-family:Drapeaux; src:url(api/police-drapeaux) format('woff2'); unicode-range:U+1F1E6-1F1FF,U+1F3F4,U+E0062-E007F; font-display:swap; }
  body { margin:0; background:var(--fond); color:var(--clair); font-size:14px;
         font-family:Drapeaux,system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }
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
  .f8 { padding:12px 16px; display:flex; flex-direction:column; gap:12px; }
  .f8-tete { display:flex; gap:16px; align-items:center; margin:0; }
  .f8-anneau { width:86px; height:86px; flex:none; }
  .f8-horloge-boite { display:flex; flex-direction:column; align-items:center; gap:3px; flex:none; }
  .f8-horloge { font-size:11px; font-weight:700; white-space:nowrap; cursor:help; color:var(--gris); }
  .f8-diag { display:flex; gap:10px; align-items:center; padding:9px 14px; border-radius:10px; font-size:13px;
    background:rgba(229,72,77,.12); border:1px solid rgba(229,72,77,.55); }
  .f8-diag.ambre { background:rgba(255,180,84,.12); border-color:rgba(255,180,84,.5); }
  .f8-diag b { color:var(--rose); } .f8-diag.ambre b { color:var(--ambre); }
  .f8-diag .ico { font-size:20px; }
  tr.f8-guettee td { background:rgba(63,224,200,.10); } tr.f8-guettee td:first-child { box-shadow:inset 3px 0 0 var(--cyan); }
  .m8-bulle.guettee { box-shadow:0 0 0 2px rgba(63,224,200,.55); }
  .f8-puce.guet { color:var(--cyan); border-color:rgba(63,224,200,.55); }
  .f8-puce.guet.frais { background:rgba(63,224,200,.14); }
  .f8-puces { display:flex; gap:6px; flex-wrap:wrap; margin-top:8px; }
  .f8-puce { padding:3px 10px; border-radius:20px; background:#0E1720; border:1px solid var(--bord); font-size:12px; color:var(--gris); }
  .f8-puce b { color:var(--clair); font-size:14px; }
  .f8-grille { display:grid; grid-template-columns:minmax(420px,1.7fr) minmax(300px,1fr) minmax(340px,1.15fr); gap:12px; align-items:start; }
  .f8-grille .carte { margin:0; }
  @media (max-width:1300px) { .f8-grille { grid-template-columns:1fr 1fr; } .f8-cascade-boite { grid-column:1 / -1; } }
  @media (max-width:800px) { .f8-grille { grid-template-columns:1fr; } }
  .f8-op { display:flex; gap:10px; align-items:center; padding:7px 8px; border-radius:10px; cursor:pointer; border:1px solid transparent; }
  .f8-op:hover { border-color:var(--cyan); background:rgba(63,224,200,.06); }
  .f8-dr { font-size:22px; line-height:1; }
  .f8-call { font-size:15px; letter-spacing:.5px; }
  .f8-tag { display:inline-block; font-size:10px; font-weight:700; padding:0 6px; border:1px solid; border-radius:10px; margin:3px 4px 0 0; }
  .f8-score { flex:none; width:44px; height:44px; border-radius:50%; display:grid; place-items:center; font-weight:800; font-size:13px;
    background:conic-gradient(var(--cyan) calc(var(--v) * 1%), #1C2A36 0); position:relative; }
  .f8-score::before { content:''; position:absolute; inset:5px; border-radius:50%; background:var(--carte); }
  .f8-score { isolation:isolate; } .f8-score::before { z-index:-1; }
  .f8-table td { padding:3px 6px; font-size:13px; white-space:nowrap; }
  .f8-sep td { border-top:2px solid var(--bord); }
  .f8-db { display:inline-block; width:34px; height:6px; background:#1C2A36; border-radius:3px; margin-right:4px; vertical-align:middle; overflow:hidden; }
  .f8-db i { display:block; height:100%; }
  .m8 { display:flex; flex-direction:column; gap:12px; min-width:0; }
  .m8 .carte { margin:0; }
  .m8-convs { max-height:240px; overflow:auto; }
  .m8-conv { display:flex; gap:10px; align-items:center; padding:9px 10px; border-radius:12px; cursor:pointer; border:1px solid transparent; }
  .m8-conv:hover { background:rgba(63,224,200,.05); }
  .m8-conv.actif { border-color:var(--cyan); background:rgba(63,224,200,.08); }
  .m8-av { position:relative; width:40px; height:40px; flex:none; border-radius:50%; background:#0E1720; display:grid; place-items:center; font-size:20px; }
  .m8-av i { position:absolute; right:-4px; bottom:-4px; font-size:14px; font-style:normal; }
  .m8-n { display:inline-block; min-width:20px; padding:0 6px; border-radius:10px; background:var(--cyan); color:#06131A; font-weight:800; font-size:11px; text-align:center; }
  .m8-fil { height:calc(100vh - 560px); min-height:260px; overflow:auto; padding:6px 4px; background:linear-gradient(180deg, rgba(14,23,32,.5), transparent); border-radius:10px; }
  .m8-heure { text-align:center; font-size:10px; color:var(--gris); margin:10px 0 4px; }
  .m8-ligne { display:flex; margin:3px 0; }
  .m8-ligne.droite { justify-content:flex-end; }
  .m8-bulle { max-width:78%; padding:7px 11px; border-radius:16px 16px 16px 4px; background:#16232E; border:1px solid var(--bord); }
  .m8-bulle.droite { border-radius:16px 16px 4px 16px; background:#12302D; border-color:rgba(63,224,200,.35); }
  .m8-bulle.moi { border-color:var(--rose); }
  .m8-qui { font-size:12px; margin-bottom:2px; }
  .m8-txt { font-family:ui-monospace,Consolas,monospace; font-size:15px; }
  .m8-ico { font-family:system-ui; }
  /* Messages shown: the radar only where there is room for three columns. */
  @media (max-width:1500px) { .f8-messages .f8-radar-boite { display:none; } .f8-messages .f8-grille { grid-template-columns:minmax(420px,1.3fr) minmax(360px,1fr); } }
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
  /* What is running or connected, at a glance: a green pill and edge; stopped, a red one. */
  .pastille.marche { background:rgba(91,214,138,.20); color:var(--vert); }
  .pastille.marche::before { content:'● '; }
  .pastille.coupe { background:rgba(229,72,77,.16); color:var(--rose); }
  .pastille.coupe::before { content:'○ '; }
  .allume { box-shadow:inset 4px 0 0 var(--vert); }
  .eteint { box-shadow:inset 4px 0 0 rgba(229,72,77,.55); }
  button.arret { background:transparent; border:1px solid var(--rose); color:var(--rose); }
  button.emettre { background:#C62828; color:#fff; border:none; font-weight:800; font-size:18px; padding:12px 26px; letter-spacing:1px; }
  button.emettre:disabled { opacity:.35; }
  button.arret-tx { background:#fff; color:#C62828; border:3px solid #C62828; font-weight:800; font-size:18px; padding:10px 26px; }
  .e-charge { background:#5A0E0E; color:#FFD7D7; border:2px solid #E5484D; border-radius:8px; padding:8px 10px; font-weight:700; margin-top:8px; }
  .e-legal { background:#4A2A06; color:#FFE2B8; border:2px solid #F08C1A; border-radius:8px; padding:8px 10px; margin-top:8px; font-size:12px; line-height:1.5; }
  .e-direct { background:rgba(229,72,77,.18); border:1px solid #E5484D; border-radius:8px; padding:8px 10px; margin-top:8px; }
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
  .o-leila { display:inline-block; margin:4px 0 2px; padding:2px 10px; border-radius:6px; background:#FF5A5A; color:#fff; font-weight:800; letter-spacing:2px; animation:o-leila 1s ease-in-out infinite alternate; }
  .o-leila[hidden] { display:none; }
  @keyframes o-leila { from { opacity:1; } to { opacity:.55; } }
  .o-balise b { font-family:ui-monospace,Consolas,monospace; font-size:16px; }
  .o-puces { display:flex; gap:6px; flex-wrap:wrap; }
  .ph-fil { margin-top:8px; max-height:340px; overflow-y:auto; display:flex; flex-direction:column; gap:6px; }
  .ph-b { background:#0E1720; border-radius:10px; padding:6px 9px; border-left:3px solid var(--bord); }
  .ph-t { font-size:12px; color:var(--gris); }
  .ph-x { font-size:15px; line-height:1.45; word-break:break-word; }
  .ph-tr { font-style:italic; color:var(--gris); font-size:13px; margin-top:2px; }
  .ph-ch { display:inline-block; margin:4px 4px 0 0; padding:2px 8px; border-radius:12px; border:1px solid var(--cyan); color:var(--cyan); font-weight:700; font-size:13px; cursor:pointer; background:none; }
  .ph-ch.connu { background:rgba(63,224,200,.16); }
  .ph-i { color:var(--cyan); font-weight:800; } .ph-c { color:var(--ambre); font-weight:700; } .ph-r { color:var(--vert); font-weight:700; }
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
  .tq { padding:12px 16px 0; }
  .tq .grille { padding-left:0; padding-right:0; }
  .tq-aide { border-color:rgba(63,224,200,.45); }
  .tq-etapes { margin:8px 0 0; padding-left:22px; display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:6px 22px; font-size:13px; }
  @media (max-width:1300px) { .tq-etapes { grid-template-columns:1fr 1fr; } }
  @media (max-width:640px) { .tq-etapes { grid-template-columns:1fr; } }
  .tq-bandeau { display:flex; align-items:center; gap:10px; flex-wrap:wrap; padding:10px 14px; margin-bottom:12px; border-radius:12px; background:rgba(255,180,84,.12); border:1px solid rgba(255,180,84,.55); }
  .tq-grille { display:grid; grid-template-columns:minmax(0,1fr) minmax(340px,420px); gap:12px; }
  @media (max-width:1100px) { .tq-grille { grid-template-columns:minmax(0,1fr); } }
  .tq-haut { display:grid; grid-template-columns:minmax(0,1.1fr) minmax(0,1fr); gap:0 12px; }
  @media (max-width:1500px) { .tq-haut { grid-template-columns:minmax(0,1fr); } }
  .tq-ligne1 { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
  .tq-go { font-size:16px; padding:10px 18px; font-weight:800; }
  .tq-etat { display:flex; gap:4px 16px; flex-wrap:wrap; margin-top:8px; font-size:12.5px; }
  .tq-etat span { color:var(--gris); } .tq-etat span.ok { color:var(--vert); } .tq-etat span.non { color:var(--ambre); }
  .tq-freq { display:flex; gap:12px 24px; flex-wrap:wrap; align-items:flex-end; margin-top:8px; }
  .tq-smetre-boite { flex:1; min-width:200px; }
  .tq-smetre { height:14px; border-radius:7px; background:#0E1720; overflow:hidden; margin-top:4px; }
  .tq-smetre i { display:block; height:100%; width:0; background:linear-gradient(90deg,var(--vert),var(--ambre) 75%,var(--rose)); transition:width .3s; }
  .tq-son { gap:8px 14px; flex-wrap:wrap; align-items:center; }
  .tq-pano { width:100%; height:clamp(220px, 34vh, 400px); display:block; margin-top:6px; cursor:crosshair; }
  .tq-legende { display:flex; flex-wrap:wrap; gap:4px 12px; margin-top:6px; font-size:11.5px; color:var(--gris); }
  .tq-legende i { display:inline-block; width:10px; height:10px; border-radius:3px; margin-right:4px; vertical-align:-1px; }
  .tq-signaux { display:flex; flex-wrap:wrap; gap:6px; margin-top:6px; }
  .tq-signaux button { padding:3px 9px; font-size:12px; border-width:1px; border-style:solid; background:transparent; }
  .tq-signaux button.ici { background:rgba(255,255,255,.10); font-weight:700; }
  .tq-onglets { display:inline-flex; gap:4px; flex-wrap:wrap; }
  .tq-onglets button { padding:3px 10px; font-size:12px; }
  .tq-onglets button.actif { border-color:var(--cyan); color:var(--cyan); background:rgba(63,224,200,.08); }
  .tq-onglets button.auto-choisi { box-shadow:inset 0 -2px 0 var(--cyan); color:var(--clair); }
  .tq-ft8 { max-height:250px; overflow:auto; margin-top:6px; }
  .tq-sstv-img { display:block; max-width:100%; max-height:260px; margin-top:8px; border-radius:6px; background:#0E1720; min-height:60px; min-width:120px; }
  .tq-conseil { padding:8px 10px; border-radius:8px; background:rgba(255,180,84,.14); border:1px solid rgba(255,180,84,.55); margin-bottom:8px; font-size:13px; }
  .tq-plus-t { color:var(--gris); font-size:11px; letter-spacing:1.6px; text-transform:uppercase; padding:10px 0 8px; border-top:1px solid var(--bord); margin-top:4px; }
  .tq-cote #cw-carte, .tq-main #cw-carte { margin-bottom:0; }
  @media (max-width:2300px) { header.a-ctx #info-station { display:none; } }
  /* A narrow screen: the tabs scroll sideways rather than widen the page. */
  @media (max-width:760px) { #onglets { max-width:100%; overflow-x:auto; } }
  .ctx-chip { border:1px solid var(--bord); background:var(--carte); color:var(--gris); border-radius:14px; padding:3px 10px; font-size:12.5px; cursor:pointer; white-space:nowrap; }
  .ctx-chip.qo { border-color:var(--cyan); color:var(--cyan); background:rgba(63,224,200,.08); font-weight:700; }
</style></head>
<body>
<header>
  <h1>SatMe-<span class="cyan">PC</span></h1>
  <span class="g" id="info-station"></span>
  <span class="g" id="cat-statut-court"></span>
  <button id="ctx-chip" class="ctx-chip" hidden onclick="vue(tqQo() ? 'qo100' : 'station')"></button>
  <nav id="onglets">
    ${boutons()}
  </nav>
  <span id="rec" class="pastille" style="display:none;background:rgba(229,72,77,.2);color:var(--rose)"></span>
  <span id="ec-boite" style="position:relative" hidden>
    <button id="ec-btn" onclick="g('ec-pop').hidden = !g('ec-pop').hidden" title="Entendre ici, dans ce navigateur, le son de SatMe-PC (la page ouverte sur un autre PC du réseau)" style="padding:4px 10px">🔈 Écouter ici</button>
    <div id="ec-pop" hidden style="position:absolute;right:0;top:110%;z-index:50;min-width:280px;padding:10px 12px;border-radius:12px;background:var(--carte);border:1px solid var(--bord);box-shadow:0 8px 24px rgba(0,0,0,.4)">
      <div class="t" style="margin:0 0 6px">Le son dans ce navigateur</div>
      <div class="ligne" style="margin:0"><select id="ec-source"><option value="sdr">Clé SDR (QO-100, SDR)</option><option value="carte">Carte son (le poste)</option></select></div>
      <div class="ligne"><button class="primaire" id="ec-go" onclick="ecouteIci()">▶ Écouter</button><button id="ec-stop" class="arret" hidden onclick="arreteEcoute()">■ Arrêter</button>
        <span class="petit">Volume</span><input type="range" id="ec-vol" min="0" max="200" value="100" style="flex:1" oninput="if (ecGain) ecGain.gain.value = this.value / 100"></div>
      <div class="petit" id="ec-etat">Le son de la clé SDR ou de la carte son de SatMe-PC, joué par cet ordinateur — utile quand la page est ouverte sur un autre PC (--reseau).</div>
    </div>
  </span>
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
        <label class="petit"><input type="checkbox" id="f-actifs"> entendus ces jours-ci (AMSAT)</label>
        <a class="bouton" id="ics-liste" href="api/passages.ics" download title="Les passages de la liste (avec ses filtres) dans un fichier d'agenda : Google Agenda, Outlook, Thunderbird…" style="margin-left:auto">⤓ Agenda (.ics)</a></div>
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
      <div class="t" style="margin-top:10px">Ses prochains passages <a class="petit" id="ics-suivi" href="api/passages.ics" download title="Ses prochains passages dans un fichier d'agenda" style="color:var(--cyan);font-weight:normal">⤓ .ics</a></div>
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
    <div id="c-saisie-slot"><div class="carte" id="c-saisie">
      <div class="t">Nouveau contact <span class="petit" id="c-sat"></span></div>
      <div class="ligne" id="c-satligne" style="margin-top:0;margin-bottom:6px"><span class="petit">Satellite</span>
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
    </div></div>
    <div id="cw-slot-carnet"><div class="carte" id="cw-carte" hidden>
      <div class="ligne" style="margin:0"><label><input type="checkbox" id="cw-on" onchange="post('api/cw?on=' + (this.checked ? 1 : 0)).then(ticCw)"> <b>Décodeur CW</b></label>
        <span id="cw-led" title="Manipulateur baissé" style="width:12px;height:12px;border-radius:6px;background:#22303C;display:inline-block"></span>
        <span class="petit" id="cw-info"></span>
        <button style="margin-left:auto;padding:3px 9px" onclick="post('api/cw?efface=1').then(ticCw)" title="Effacer le texte">✕</button></div>
      <div id="cw-corps" hidden>
        <div id="cw-texte" style="margin-top:8px;height:96px;overflow-y:auto;padding:6px 8px;border-radius:8px;background:#0E1720;font-family:ui-monospace,Consolas,monospace;font-size:15px;line-height:1.45;letter-spacing:1px;word-break:break-word"></div>
        <div class="ligne"><span class="petit">Note</span><select id="cw-ton" style="flex:none" onchange="post('api/cw?ton=' + this.value).then(ticCw)"><option value="0">trouvée seule</option><option>500</option><option>600</option><option>650</option><option>700</option><option>750</option><option>800</option><option>900</option></select>
          <span class="petit">Un indicatif décodé : un clic le met dans la saisie.</span></div>
      </div>
    </div></div>
    <div id="ph-slot-carnet"><div class="carte" id="ph-carte" hidden>
      <div class="ligne" style="margin:0"><label><input type="checkbox" id="ph-on" onchange="phRegle('on=' + (this.checked ? 1 : 0))"> <b>Phonie → texte</b></label>
        <span id="ph-led" title="Voix entendue" style="width:12px;height:12px;border-radius:6px;background:#22303C;display:inline-block"></span>
        <span class="petit" id="ph-info"></span>
        <span style="margin-left:auto;display:flex;gap:4px"><a id="ph-export" href="phonie/journal" download title="Le texte transcrit du jour"><button style="padding:3px 9px">⤓</button></a>
          <button style="padding:3px 9px" onclick="phRegle('efface=1')" title="Vider la fenêtre (le fichier du jour reste)">✕</button></span></div>
      <div class="avert" id="ph-erreur"></div>
      <div id="ph-corps" hidden>
        <div class="ligne"><select id="ph-langue" style="flex:none" onchange="phRegle('langue=' + this.value)"><option value="">langue trouvée seule</option><option value="fr">français</option><option value="en">anglais</option><option value="de">allemand</option><option value="es">espagnol</option><option value="it">italien</option><option value="pl">polonais</option></select>
          <select id="ph-source" style="flex:none" onchange="phRegle('source=' + this.value)"><option value="auto">clé SDR si elle joue, sinon carte son</option><option value="carte">carte son</option><option value="sdr">clé SDR</option></select>
          <label class="petit"><input type="checkbox" id="ph-trad" onchange="phRegle('traduire=' + (this.checked ? 1 : 0))"> traduire en anglais</label></div>
        <div id="ph-fil" class="ph-fil"></div>
        <div class="petit" style="margin-top:4px">Un indicatif trouvé : un clic prépare le contact dans le carnet, avec le carré et le report entendus. Plus le texte est pâle, moins le moteur est sûr.</div>
      </div>
    </div></div>
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
      <div class="bascule-c"><button id="cv-station" onclick="vueCarnet('station')">Station en direct</button><button id="cv-contacts" onclick="vueCarnet('contacts')">Contacts</button><button id="cv-records" onclick="vueCarnet('records')">🏆 Records</button></div>
      <div id="cr-records" hidden></div>
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
      <div class="ligne" style="margin-top:0"><select id="s-carte"></select><button class="primaire" data-marche="son" onclick="carteSon()">Démarrer</button><button class="arret" data-arret="son" hidden onclick="carteSon(true)">Arrêter</button></div>
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
      <div class="t">Réception SSTV <span class="petit" id="i-mode"></span></div>
      <div class="val" id="sstv-statut" style="font-size:15px"></div>
      <div class="avert" id="sstv-conseil" hidden></div>
      <div class="ligne" style="margin-top:6px"><span class="petit" style="min-width:52px">Niveau</span>
        <div style="flex:1;height:8px;background:#0E1720;border-radius:4px;overflow:hidden"><div id="sstv-niveau" style="height:100%;width:0;background:var(--vert)"></div></div>
        <span class="petit" id="sstv-source-active" style="min-width:120px;text-align:right"></span></div>
      <canvas id="sstv-spectre" width="600" height="56" style="width:100%;height:56px;background:#0E1720;border-radius:6px;margin-top:6px" title="Le son de 900 à 2600 Hz : synchro 1200, image 1500–2300, en-tête 1900"></canvas>
      <div class="petit" id="sstv-ecart"></div>
      <div class="ligne"><span class="petit" id="sstv-source-l">Source</span>
        <select id="sstv-source" style="flex:none" onchange="post('api/sstv/reglage?source=' + this.value)"><option value="auto">auto : la clé SDR si elle joue, sinon la carte son</option><option value="carte">carte son (onglet Son)</option><option value="sdr">clé SDR</option></select></div>
      <div class="ligne"><span class="petit">Mode</span><select id="sstv-force" style="flex:none" onchange="post('api/sstv/reglage?force=' + enc(this.value || 'auto'))"></select>
        <button id="sstv-caler" onclick="post('api/sstv/caler')" title="Commencer l'image tout de suite, dans le mode choisi, sans attendre l'en-tête">⚡ Caler maintenant</button>
        <button class="arret" id="sstv-abandon" hidden onclick="post('api/sstv/abandon')" title="Finir l'image en cours (gardée si assez de lignes) et écouter à nouveau">✕ Arrêter l'image</button></div>
      <div class="petit">Mode imposé : l'en-tête VIS n'est plus attendu. « Caler maintenant » accroche l'image sur la prochaine impulsion de synchro.</div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="sstv-actif" onchange="post('api/sstv/reglage?actif=' + (this.checked ? 1 : 0))"> Décoder la SSTV en direct</label>
      <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="sstv-continu" onchange="post('api/sstv/reglage?continu=' + (this.checked ? 1 : 0))"> Décodage continu : démarre une image sur ses tops de synchronisation, même sans en-tête (mode reconnu à la durée des lignes)</label>
      <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="sstv-nettoyage" onchange="post('api/sstv/reglage?nettoyage=' + (this.checked ? 1 : 0))"> Nettoyer les images : répare les lignes et les traits perdus dans le bruit</label>
      <img id="i-encours" style="width:100%;image-rendering:auto;background:#0E1720;min-height:200px;border-radius:8px;margin-top:8px" alt="">
      <div style="height:6px;background:#0E1720;border-radius:3px;margin-top:4px;overflow:hidden"><div id="sstv-barre" style="height:100%;width:0;background:var(--vert)"></div></div>
      <div class="etat" id="sstv-etat"></div>
    </div>
    <div class="carte" id="sstv-redec-carte">
      <div class="t">Décoder un enregistrement</div>
      <div class="petit">Repasse un enregistrement du PC dans le décodeur (avec le mode imposé et le décodage continu choisis ci-dessus). Les images retrouvent l'heure de leur réception.</div>
      <div class="ligne"><select id="sstv-enreg" style="flex:1"></select><button class="primaire" id="sstv-redec" onclick="post('api/sstv/redecode?nom=' + enc(g('sstv-enreg').value))">⟳ Décoder</button>
        <button id="sstv-annule" hidden onclick="post('api/sstv/annule')">Annuler</button></div>
      <div style="height:6px;background:#0E1720;border-radius:3px;margin-top:6px;overflow:hidden"><div id="sstv-redec-barre" style="height:100%;width:0;background:var(--cyan)"></div></div>
      <div class="petit" id="sstv-redec-etat"></div>
    </div>
    <div class="carte" id="mi-carte" hidden>
      <div class="t">Mire SSTV</div>
      <div class="petit">Une image de référence envoyée en SSTV, pour éprouver un décodeur sans satellite : celui de ce PC, d'un téléphone, ou toute une chaîne radio. Rien n'est émis : le son part sur la sortie choisie.</div>
      <div class="ligne"><select id="mi-mode" onchange="apercuMire()"></select><span class="petit" id="mi-duree"></span></div>
      <img id="mi-apercu" alt="" style="width:100%;max-width:420px;border-radius:6px;margin-top:8px;display:block">
      <div class="ligne"><span class="petit">Conditions</span>
        <select id="mi-bruit" title="Bruit (rapport signal sur bruit)"><option value="0">sans bruit</option><option value="20">bruit 20 dB</option><option value="15">bruit 15 dB</option><option value="12">bruit 12 dB</option><option value="8">bruit 8 dB</option></select>
        <select id="mi-fading" title="Évanouissements"><option value="NONE">sans fading</option><option value="LIGHT">fading léger</option><option value="STRONG">fading fort</option></select>
        <label class="petit"><input type="checkbox" id="mi-coupures"> coupures</label>
        <label class="petit"><input type="checkbox" id="mi-profil"> profil du passage</label></div>
      <div class="ligne"><button onclick="presetMire(1)">ISS typique</button><button onclick="presetMire(0)">Propre</button></div>
      <div class="ligne"><span class="petit">Sortie</span><select id="mi-sortie" style="flex:1"></select></div>
      <div class="avert">Jamais vers l'entrée micro d'un poste avec VOX, sauf essai voulu : il passerait en émission.</div>
      <div class="ligne"><button class="primaire" id="mi-joue" onclick="joueMire()">▶ Jouer</button><button onclick="post('api/mire/stop').then(ticMire)">■ Stop</button>
        <a class="bouton" id="mi-wav" href="#" download>⤓ WAV</a></div>
      <div style="height:8px;background:#0E1720;border-radius:4px;margin-top:8px;overflow:hidden"><div id="mi-barre" style="height:100%;width:0;background:var(--cyan)"></div></div>
      <div class="etat" id="mi-etat"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">Images reçues <span class="petit" id="i-nombre"></span></div>
      <div class="ligne" style="margin-top:0"><select id="i-filtre" style="flex:none" onchange="imagesVus = ''; rendSon()"><option value="">tous les satellites</option></select></div>
      <div id="i-galerie" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px;margin-top:8px"></div>
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
    <summary class="t" style="cursor:pointer;margin:0">Radio APRS en KISS (TH-D72, TM-D710, Mobilinkd…) <span id="kiss-pastille" class="pastille coupe">déconnectée</span></summary>
    <div class="ligne"><select id="kiss-port" style="flex:1"></select>
      <select id="kiss-vitesse" style="flex:none"><option>1200</option><option>4800</option><option selected>9600</option><option>19200</option><option>38400</option><option>57600</option></select>
      <button class="primaire" data-marche="kiss" onclick="kConnecte()">Connecter</button><button class="arret" data-arret="kiss" hidden onclick="kPost('deconnecter')">Déconnecter</button></div>
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
  <details class="carte" id="tr-carte" open>
    <summary class="t" style="cursor:pointer">🏆 Trophées APRS <span class="petit" id="tr-resume"></span></summary>
    <div class="petit">Le jeu du téléphone : chaque station entendue, chaque carré, chaque pays, ta trame revenue par l'ISS… Gardés à part de l'historique.</div>
    <div class="paire" style="margin-top:8px" id="tr-jour"></div>
    <div class="t" style="margin-top:12px">Records</div>
    <div class="paire" id="tr-records"></div>
    <div class="t" style="margin-top:12px">Badges</div>
    <div id="tr-badges" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:8px;margin-top:6px"></div>
    <div class="grille2" style="margin-top:10px;padding:0">
      <div><div class="t">Contacts APRS</div><div id="tr-contacts" class="petit"></div></div>
      <div><div class="t">Dernières fêtes</div><div id="tr-fetes" class="petit"></div></div>
    </div>
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
    <div id="d-cadre"><img id="d-video" alt=""><div id="d-vide">Réception arrêtée</div><div id="d-lu" class="d-lu" hidden></div><div id="d-st" class="d-st" hidden></div></div>
    <div class="paire" style="margin-top:8px;align-items:center">
      <span id="d-etat" class="pastille">arrêté</span>
      <span>MER <b class="val" id="d-mer">—</b></span>
      <span class="val" id="d-modcod" style="font-size:16px"></span>
      <span class="petit" id="d-porteuse"></span>
      <span class="petit" id="d-debit"></span>
      <span id="d-rec" class="pastille" style="display:none;background:rgba(229,72,77,.2);color:var(--rose)"></span>
    </div>
    <div class="petit" id="d-service" style="margin-top:4px"></div>
    <div id="q-boite" hidden>
      <div class="t">Qualité de réception</div>
      <div class="q-haut">
        <div id="q-marge" title="MER mesurée moins le MER qu'il faut au MODCOD pour une image sans erreur (DVB-S2 : EN 302 307-1, table 13)">Marge —</div>
        <div style="flex:1;min-width:220px"><div id="q-verdict"></div><div class="petit" id="q-conseil"></div></div>
      </div>
      <div class="q-tuiles" id="q-tuiles"></div>
      <div class="petit" id="q-boucle" style="margin-top:8px" hidden></div>
      <div class="q-courbes">
        <div><div class="petit">MER (2 min)</div><canvas id="q-c-mer" width="340" height="44"></canvas></div>
        <div><div class="petit">Marge</div><canvas id="q-c-marge" width="340" height="44"></canvas></div>
        <div><div class="petit">Paquets reçus / attendus</div><canvas id="q-c-ratio" width="340" height="44"></canvas></div>
        <div><div class="petit">Erreurs de continuité /s</div><canvas id="q-c-cc" width="340" height="44"></canvas></div>
      </div>
      <div id="q-pids"></div>
    </div>
    <div id="dc-carte" class="dc-carte" hidden>
      <div class="dc-tete"><span id="dc-drapeau" class="dc-drapeau"></span><b id="dc-call" class="dc-call"></b>
        <span id="dc-connu" class="pastille" style="background:rgba(91,214,138,.2);color:var(--vert)" hidden></span>
        <span class="petit" id="dc-age"></span>
        <button style="margin-left:auto" onclick="dcNote()" title="Dans le carnet : QO-100 (ou DATV terrestre), mode DATV, la fréquence, la MER reçue comme report">Noter le QSO</button></div>
      <div id="dc-info" style="margin-top:4px"></div>
      <div class="petit" id="dc-tech" style="margin-top:2px"></div>
      <div id="dc-message" class="dc-message" hidden></div>
      <div id="dc-chat" class="dc-chat"></div>
      <div class="etat" id="dc-msg"></div>
    </div>
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
      <div class="ligne"><button class="primaire" data-marche="d" onclick="dDemarre()">Recevoir</button><button class="arret" data-arret="d" hidden onclick="dPost('arrete')">Arrêter</button>
        <button id="d-benr" onclick="dEnregistre()">Enregistrer</button><button onclick="dPost('vlc')" title="Le même flux dans VLC, pleine qualité">VLC</button></div>
      <div class="etat" id="d-msg"></div>
    </div>
    <div class="carte" id="dt-carte" hidden>
      <div class="t">Sous-titres et indicatifs</div>
      <label class="petit" style="display:block"><input type="checkbox" id="dt-st" onchange="dtRegle('soustitres=' + (this.checked ? 1 : 0))"> Sous-titres : le son reçu écrit sous l’image (Phonie → texte)</label>
      <label class="petit" style="display:block;margin-left:22px"><input type="checkbox" id="dt-trad" onchange="dtTrad(this.checked)"> avec la traduction (anglais)</label>
      <div class="petit" id="dt-st-etat"></div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="dt-ocr" onchange="dtRegle('ocr=' + (this.checked ? 1 : 0))"> Lire les indicatifs dans l’image</label>
      <div class="petit" id="dt-ocr-etat"></div>
      <div id="dt-lus"></div>
      <div id="dt-fil" class="ph-fil" style="max-height:260px"></div>
    </div>
    <div class="carte">
      <div class="t">Constellation</div>
      <div style="display:flex;gap:12px;align-items:flex-start">
        <canvas id="d-const" width="200" height="200" style="width:200px;height:200px;background:#0E1720;border-radius:8px"></canvas>
        <div class="petit" style="flex:1;line-height:1.7" id="d-details"></div>
      </div>
    </div>
    <div class="carte" id="e-carte">
      <div class="t">Émission · <span id="e-titre">ADALM-PLUTO</span> <span id="e-pastille" class="pastille" style="color:var(--gris)">arrêtée</span></div>
      <div class="petit">DVB-S2 fait par SatMe, vers le transpondeur large bande de QO-100 ; le MiniTiouner ci-dessus vérifie le signal à la descente.</div>
      <div class="ligne"><span class="petit" style="min-width:86px">Émetteur</span><select id="e-emetteur" onchange="eRegle()" style="max-width:150px">
        <option value="pluto">ADALM-PLUTO</option><option value="hackrf">HackRF One</option></select>
        <span class="petit" id="e-hackrf-info" style="flex:1"></span><button id="e-hackrf-det" onclick="eDetecte()" title="hackrf_info : liste les HackRF branchés, n'émet rien">Détecter</button></div>
      <div id="e-hackrf-l" hidden>
        <div class="ligne"><span class="petit" style="min-width:86px">Outils HackRF</span><input id="e-hackrf-outil" placeholder="vide : hackrf_transfer du PATH" onchange="eRegle()"></div>
        <div class="petit" id="e-hackrf-prog"></div>
        <label class="petit" style="display:block;margin-top:4px" title="La fuite de l'oscillateur local du HackRF (une porteuse) tombait au centre du signal ; décalée, elle reste hors de la bande occupée"><input type="checkbox" id="e-hackrf-dec" onchange="eRegle()"> Oscillateur décalé : sa fuite hors de la bande occupée (le signal reste sur sa fréquence)</label>
        <div class="petit" id="e-hackrf-dec-info"></div>
        <div class="avert" id="e-hackrf-note"></div>
      </div>
      <div class="ligne"><span class="petit" style="min-width:86px">MODCOD</span><select id="e-modcod" onchange="eRegle()" style="max-width:120px"></select>
        <label class="petit"><input type="checkbox" id="e-courtes" onchange="eRegle()"> trames courtes</label>
        <label class="petit"><input type="checkbox" id="e-pilotes" onchange="eRegle()"> pilotes</label></div>
      <div class="ligne"><span class="petit" style="min-width:86px">kS/s</span><select id="e-sr" onchange="eRegle()" style="max-width:90px"></select>
        <span class="petit">roll-off</span><select id="e-ro" onchange="eRegle()" style="max-width:80px"></select></div>
      <div class="ligne" id="e-canal-l"><span class="petit" style="min-width:86px">Canal</span><select id="e-canal" onchange="if (this.value) { g('e-mhz').value = this.value; eRegle(); }"></select></div>
      <div class="ligne" id="e-mhz-l"><span class="petit" style="min-width:86px">Montée (MHz)</span><input id="e-mhz" style="max-width:120px" onchange="eRegle()">
        <span class="petit" id="e-descente"></span></div>
      <div class="avert" id="e-bande"></div>
      <div class="ligne"><span class="petit" style="min-width:86px">Source</span><select id="e-source" onchange="eRegle()" style="max-width:190px">
        <option value="mire">Mire SatMe + indicatif</option><option value="animee">Mire animée (vidéo de test, bip)</option><option value="image">Image fixe</option><option value="video">Fichier vidéo (en boucle)</option><option value="camera">Caméra</option></select>
        <select id="e-hevc" onchange="eRegle()" style="max-width:90px"><option value="0">H.264</option><option value="1">H.265</option></select>
        <label class="petit"><input type="checkbox" id="e-son" onchange="eRegle()"> son</label></div>
      <div class="ligne" id="e-image-l" hidden><span class="petit" style="min-width:86px">Image</span><input id="e-image" placeholder="/home/…/image.png" onchange="eRegle()"></div>
      <div class="ligne" id="e-video-l" hidden><span class="petit" style="min-width:86px">Vidéo</span><input id="e-video" placeholder="/home/…/essai.mp4" onchange="eRegle()"></div>
      <div class="ligne" id="e-camera-l" hidden><span class="petit" style="min-width:86px">Caméra</span><select id="e-camera" onchange="eRegle()"></select></div>
      <div class="ligne" id="e-vga-l" hidden><span class="petit" style="min-width:86px">Gain VGA (dB)</span><input id="e-vga" type="number" step="1" min="0" max="10" style="max-width:80px" onchange="eRegle()">
        <span class="petit">0 à 10 dB, amplificateur RF toujours coupé ; pris au démarrage</span></div>
      <div class="ligne" id="e-gain-l"><span class="petit" style="min-width:86px">Gain TX (dB)</span><input id="e-gain" type="number" step="1" min="-89" max="0" style="max-width:80px" onchange="eRegle()">
        <button onclick="eGain(-1)" title="1 dB de moins">−1</button><button onclick="eGain(1)" title="1 dB de plus">+1</button><span class="petit" id="e-gainmax"></span></div>
      <div class="ligne" id="e-duree-l"><span class="petit" style="min-width:86px">Durée (min)</span><input id="e-duree" type="number" min="1" max="30" style="max-width:80px" onchange="eRegle()">
        <span class="petit">puis arrêt automatique (30 min au plus)</span></div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="e-charge" onchange="eMode('e-charge')"> <b>Test sur charge</b> — toute fréquence, <span id="e-charge-gain">gain bridé à −40 dB</span></label>
      <div class="e-charge" id="e-charge-avert" hidden>⚠ TEST SUR CHARGE : la sortie TX de l'<span class="e-nom">ADALM-PLUTO</span> doit aller sur une charge fictive 50 Ω ou un atténuateur (vers le MiniTiouner), JAMAIS sur une antenne ni sur l'amplificateur.</div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="e-interieur" onchange="eMode('e-interieur')"> <b>Essai en intérieur</b> — deux petites antennes à quelques mètres, puissance minimale</label>
      <div id="e-int-l" hidden>
        <div class="e-legal">⚠ ESSAI EN INTÉRIEUR : une vraie émission radio. Réservé à un radioamateur titulaire d'une licence, sous son indicatif (dans le nom du service et sur la mire), dans une bande amateur (23 cm 1240–1300 MHz ou 13 cm 2300–2450 MHz), à la puissance minimale (<span id="e-int-p">−40 dB</span>), à l'intérieur, sur une petite antenne, quelques minutes. Jamais d'amplificateur.</div>
        <div class="ligne"><span class="petit" style="min-width:86px">Fréquence (MHz)</span><input id="e-int-mhz" style="max-width:120px" onchange="eRegle()">
          <button onclick="g('e-int-mhz').value = mhz3(EM ? EM.intDefautKhz : 1255000); eRegle()" title="23 cm, hors de la partie partagée">1255</button></div>
        <div class="ligne"><span class="petit" style="min-width:86px">Durée (min)</span><input id="e-int-duree" type="number" min="1" max="10" style="max-width:80px" onchange="eRegle()">
          <span class="petit">puis arrêt automatique (10 min au plus)</span></div>
        <div class="avert" id="e-int-refus" style="color:var(--rose)"></div>
        <div class="avert" id="e-int-avert"></div>
      </div>
      <div class="petit" id="e-rx" style="margin-top:6px;line-height:1.6" hidden></div>
      <div class="petit" id="e-calc" style="margin-top:6px"></div>
      <div class="ligne" style="margin-top:10px"><button class="emettre" id="e-go" onclick="eEmettre()">ÉMETTRE</button>
        <button class="arret-tx" id="e-stop" onclick="eArrete()">ARRÊT</button></div>
      <div id="e-chat-l" hidden>
        <div class="ligne"><input id="e-chat" maxlength="140" placeholder="Chat dans le flux émis (Entrée) — 140 caractères" onkeydown="if (event.key === 'Enter') eChat()">
          <button onclick="eChat()">Envoyer</button></div>
        <div id="e-chat-fil" class="dc-chat"></div>
      </div>
      <details style="margin-top:8px"><summary class="petit">Carte de visite dans le flux</summary>
        <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="e-carte-on" onchange="eRegleCarte()"> Envoyer la carte de visite : indicatif, locator, nom, logiciel, fréquence et MODCOD, avec ce qui suit (à la place de paquets nuls : les autres récepteurs l’ignorent, l’image ne change pas)</label>
        <div class="ligne"><span class="petit" style="min-width:86px">QTH</span><input id="e-qth" maxlength="48" placeholder="Ville" onchange="eRegleCarte()"></div>
        <div class="ligne"><span class="petit" style="min-width:86px">Puissance</span><input id="e-puissance" maxlength="24" placeholder="10 W" style="max-width:100px" onchange="eRegleCarte()">
          <span class="petit">Antenne</span><input id="e-antenne" maxlength="48" placeholder="Parabole 1,2 m" onchange="eRegleCarte()"></div>
        <div class="ligne"><span class="petit" style="min-width:86px">Message</span><input id="e-message" maxlength="140" placeholder="Retour par le chat, 73" onchange="eRegleCarte()"></div>
      </details>
      <div id="e-adapt" style="margin-top:10px;border-top:1px solid rgba(255,255,255,.08);padding-top:8px">
        <label class="petit" style="display:block"><input type="checkbox" id="e-adapt-on" onchange="eRegleAdapt()"> <b>Adapter le MODCOD à la réception</b> — boucle fermée : le MiniTiouner reçoit notre propre signal, SatMe choisit le MODCOD (jamais la fréquence, le débit de symboles ni la puissance)</label>
        <div class="ligne"><span class="petit" style="min-width:86px">Plage</span><select id="e-adapt-min" onchange="eRegleAdapt()" style="max-width:110px" title="Le plus robuste autorisé"></select>
          <span class="petit">à</span><select id="e-adapt-max" onchange="eRegleAdapt()" style="max-width:110px" title="Le plus efficace autorisé"></select>
          <span class="petit">marge</span><input id="e-adapt-marge" type="number" step="0.5" min="0.5" max="10" style="max-width:70px" onchange="eRegleAdapt()"><span class="petit">dB</span></div>
        <div class="petit" id="e-adapt-etat" style="line-height:1.7"></div>
        <div class="petit" id="e-adapt-histo" style="line-height:1.6;margin-top:4px"></div>
      </div>
      <div id="e-direct" class="e-direct" hidden></div>
      <div id="e-boucle" class="petit" style="margin-top:8px;line-height:1.7"></div>
      <div class="etat" id="e-msg"></div>
    </div>
    <div class="petit" id="d-prog"></div>
  </div>
</div>
</div>
<div class="vue" id="v-qo100" hidden>
<div class="tq">
  <div class="carte tq-aide" id="tq-aide" hidden>
    <div class="ligne" style="margin:0"><b class="cyan">Comment ça marche</b><span class="petit">le trafic sur QO-100, d'un seul écran</span>
      <button style="margin-left:auto;padding:3px 10px" onclick="tqAideFerme()">Compris ✕</button></div>
    <ol class="tq-etapes">
      <li><b>Démarrer la réception QO-100</b> : un clic démarre le récepteur, le met sur QO-100 et donne son son aux décodeurs.</li>
      <li><b>Cliquer un signal</b> dans la cascade : SatMe s'y accorde dans son mode (BLU, CW, FT8, SSTV) et ouvre le bon décodeur en bas.</li>
      <li><b>Noter le contact</b> à droite : le satellite QO-100, la fréquence et le mode sont déjà là.</li>
      <li>La <b>balise médiane</b> est la référence : si l'écart est grand, coche le calage automatique.</li>
    </ol>
  </div>
  <div class="tq-bandeau" id="tq-reprise" hidden>
    <span>📡 <b>Tu recevais QO-100</b> quand SatMe s'est arrêté.</span>
    <button class="primaire" onclick="tqReprise(1)">Reprendre QO-100</button><button onclick="tqReprise(0)">Non merci</button>
    <span class="petit">Pour la reprendre toute seule : Réglages › Satellites. Rien n'est jamais émis.</span>
  </div>
  <div class="tq-grille">
    <div class="tq-main">
      <div class="tq-haut">
      <div class="carte tq-rx" id="tq-rx">
        <div class="tq-ligne1">
          <button class="primaire tq-go" id="tq-go" onclick="tqDemarre()">▶ Démarrer la réception QO-100</button>
          <button class="arret tq-go" id="tq-stop" hidden onclick="tqArrete()">■ Arrêter</button>
          <span id="tq-pastille" class="pastille coupe">arrêtée</span>
          <span class="petit" id="tq-recepteur"></span>
          <button class="discret petit" style="margin-left:auto" onclick="g('tq-aide').hidden = !g('tq-aide').hidden" title="Comment ça marche">? Aide</button>
          <span id="o-leila" class="o-leila" hidden title="La sirène LEILA de la station sol : la station écoutée émet plus fort que les balises et doit baisser sa puissance.">⚠ LEILA</span>
        </div>
        <div class="tq-etat" id="tq-etapes"></div>
        <div class="avert" id="tq-msg"></div>
        <div class="tq-freq">
          <div>
            <div class="petit">Descente (ciel) <span id="o-bords"></span></div>
            <div id="o-dl" class="o-freq" title="Molette : ±100 Hz (Maj : ±1 kHz) · flèches ← → ±100 Hz, ↑ ↓ ±1 kHz"></div>
            <div class="petit">Montée <span id="o-ul" class="val ambre" style="font-size:16px"></span></div>
          </div>
          <div class="tq-smetre-boite">
            <div class="petit">Niveau <span id="tq-db"></span></div>
            <div class="tq-smetre"><i id="tq-smetre"></i></div>
            <div class="ligne" style="margin-top:6px"><span class="petit">Mode</span>
              <select id="tq-mode" style="flex:none" onchange="tqMode(this.value)" title="Le mode noté au carnet (celui du signal accordé, sinon le tien)">
                <option value="SSB">BLU (SSB)</option><option>CW</option><option>FT8</option><option>FT4</option><option>SSTV</option><option>PSK31</option><option>RTTY</option></select>
              <span class="petit" id="tq-demod"></span></div>
          </div>
        </div>
        <div class="ligne bouton-gr">
          <button onclick="qPas(-1000)">−1 k</button><button onclick="qPas(-100)">−100</button><button onclick="qPas(100)">+100</button><button onclick="qPas(1000)">+1 k</button>
          <button id="o-bbalise" onclick="qPost('balise')">Balise médiane</button><button onclick="qPost('centre')">Centre</button>
          <input id="o-aller" placeholder="10489,688" style="max-width:110px" title="Aller à (MHz ciel)"><button onclick="qAller()">Aller</button></div>
        <div class="ligne tq-son">
          <label class="petit"><input type="checkbox" id="tq-hp" onchange="rRegle('son=' + (this.checked ? 1 : 0)).then(tqTic)"> Haut-parleur du PC</label>
          <span id="tq-ec-boite"><button id="tq-ec" onclick="tqEcoute()" title="Le son du récepteur joué par ce navigateur">🔈 Écouter ici</button>
            <span class="petit">Volume</span><input type="range" id="tq-vol" min="0" max="200" value="100" style="width:90px;flex:none" oninput="g('ec-vol').value = this.value; if (ecGain) ecGain.gain.value = this.value / 100"></span>
          <span class="petit">Silencieux</span><input type="range" id="tq-sq" min="-120" max="-20" style="width:110px;flex:none" onchange="rRegle('squelch=' + this.value).then(tqTic)"><span class="petit" id="tq-sqv"></span>
          <label class="petit" id="tq-agc-l"><input type="checkbox" id="tq-agc" onchange="rRegle('agc=' + (this.checked ? 1 : 0)).then(tqTic)"> CAG</label>
        </div>
      </div>
      <div class="carte" id="tq-dock">
        <div class="ligne" style="margin:0 0 8px"><span class="t" style="margin:0">Décodeur</span>
          <span class="tq-onglets" id="tq-onglets">
            <button data-d="auto">Auto</button><button data-d="phonie">Phonie</button><button data-d="cw">CW</button><button data-d="ft8">FT8 / FT4</button><button data-d="sstv">SSTV</button></span>
          <span class="petit" id="tq-d-info" style="margin-left:auto"></span></div>
        <div id="tq-d-rien" class="petit">Clique un signal dans la cascade : le décodeur de son mode s'ouvre ici (phonie écrite, CW, FT8, image SSTV).</div>
        <div id="tq-d-phonie" hidden><div id="ph-slot-qo100"></div><div class="petit" id="tq-ph-absent" hidden>« Phonie → texte » n'est pas installé : Réglages › Phonie → texte.</div></div>
        <div id="tq-d-cw" hidden><div id="cw-slot-qo100"></div><div class="petit" id="tq-cw-absent" hidden>Le décodeur CW n'est pas disponible ici.</div></div>
        <div id="tq-d-ft8" hidden>
          <div class="ligne" style="margin:0"><button id="tq-ft8-go" onclick="tqFt8Marche()">▶ Décoder</button><span class="petit" id="tq-ft8-etat"></span>
            <button style="margin-left:auto" onclick="vue('ft8')">Onglet FT8 complet ›</button></div>
          <div class="tq-ft8"><table><tbody id="tq-ft8"></tbody></table></div>
        </div>
        <div id="tq-d-sstv" hidden>
          <div class="ligne" style="margin:0"><button onclick="post('api/sstv/caler').then(ticSon)" title="Commencer l'image maintenant, sans attendre son entête">Caler maintenant</button>
            <span class="petit" id="tq-sstv-etat"></span><button style="margin-left:auto" onclick="vue('sstv')">Onglet SSTV ›</button></div>
          <img id="tq-sstv-img" alt="" class="tq-sstv-img">
        </div>
      </div>
      </div>
      <div class="carte">
        <div class="t" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap"><span>Transpondeur à bande étroite</span>
          <span class="petit" id="tq-cl-etat" style="text-transform:none;letter-spacing:0"></span>
          <span style="margin-left:auto;text-transform:none;letter-spacing:0" class="petit">Largeur
            <select id="tq-zoom" style="flex:none" onchange="tqZoom(+this.value)"><option value="500000">500 kHz</option><option value="200000">200 kHz</option><option value="100000">100 kHz</option><option value="50000">50 kHz</option><option value="20000">20 kHz</option></select></span></div>
        <canvas id="o-reglette" style="width:100%;height:90px;display:block;cursor:crosshair" title="Cliquer ou glisser pour s'y poser · molette ±100 Hz"></canvas>
        <canvas id="o-pano" class="tq-pano" title="Le transpondeur vu par le récepteur : cliquer un signal pour s'y accorder dans son mode"></canvas>
        <div class="petit" id="o-pano-msg"></div>
        <div id="tq-legende" class="tq-legende"></div>
        <div id="tq-signaux" class="tq-signaux"></div>
        <canvas id="o-loupe" style="width:100%;height:64px;display:block;margin-top:6px;cursor:crosshair" title="± 25 kHz autour de l'accord : cliquer pour s'y poser"></canvas>
        <div class="paire" style="margin-top:8px"><b id="o-seg"></b><span class="petit" id="o-larg"></span><span id="o-interdit" class="rose" style="font-weight:700"></span></div>
        <div class="petit" id="o-avert" style="margin-top:2px"></div>
      </div>
    </div>
    <div class="tq-cote">
      <div id="tq-carnet-slot"></div>
      <div class="carte">
        <div class="t">Calage sur la balise</div>
        <div id="tq-conseil" class="tq-conseil" hidden></div>
        <div id="o-balise" class="o-balise">—</div>
        <div class="ligne" style="margin-top:4px"><button id="o-bmesure" onclick="qCaleMesure()">Caler sur la mesure</button></div>
        <label style="display:block;margin-top:6px" id="oq-auto-l"><input type="checkbox" id="oq-auto" onchange="post('api/qo100/auto?on=' + (this.checked ? 1 : 0)).then(ticQualite).then(tqTic)"> <b>Calage automatique</b>
          <span class="petit">— la balise médiane mesurée toutes les 5 s, la dérive du LNB suivie toute seule (par petits pas, jamais sur une mesure faible)</span></label>
        <div class="ligne"><span class="petit">Écart mémorisé</span><span class="val" id="o-calage">0 Hz</span></div>
        <details style="margin-top:4px"><summary class="petit" style="cursor:pointer">À la main</summary>
          <div class="petit">L'oscillateur d'un LNB dérive, surtout à froid. Accorde la balise médiane, lis où elle tombe vraiment, saisis-le : l'écart s'applique à toute la descente.</div>
          <div class="ligne"><input id="o-entendue" placeholder="MHz ciel" title="Où tombe vraiment la balise médiane, en MHz ciel : par exemple 10489,7523" style="max-width:170px"><button onclick="qCale()">Appliquer</button>
            <button onclick="qPost('calage-zero')">Remettre à zéro</button></div></details>
        <div class="etat" id="o-cmsg"></div>
      </div>
      <div class="carte" id="oq-carte" hidden>
        <div class="t">Qualité de la réception <span id="oq-verrou" class="pastille coupe">balise non vue</span></div>
        <div class="paire" style="margin-top:4px">
          <span><span class="val" id="oq-snr">—</span> <span class="petit">dB balise / bruit</span></span>
          <span><span class="val" id="oq-ecart">—</span> <span class="petit">écart</span></span>
          <span><span class="val ambre" id="oq-derive">—</span> <span class="petit">dérive</span></span></div>
        <div class="paire" style="margin-top:4px">
          <span><span class="val" id="oq-erreur">—</span> <span class="petit">erreur du LNB</span></span>
          <span><span class="val" id="oq-plancher">—</span> <span class="petit">plancher</span></span>
          <span><span class="val" id="oq-occ">—</span> <span class="petit">stations</span></span></div>
        <canvas id="oq-histo" style="width:100%;height:70px;display:block;margin-top:8px" title="L'erreur du LNB sur la dernière heure (la balise mesurée toutes les 5 s)"></canvas>
        <div class="petit"><span id="oq-histo-t">L'erreur du LNB sur la dernière heure.</span> Une pente, c'est la dérive (un LNB qui chauffe) ; un trait plat, un LNB stable (GPSDO).</div>
      </div>
      <div class="carte">
        <div class="t">Mémoires</div>
        <div id="o-memoires" class="o-puces"></div>
        <div class="ligne"><input id="o-nom" placeholder="Nom (facultatif)" style="max-width:180px"><button onclick="qMemoire()">Mémoriser ici</button>
          <button id="o-oublie" onclick="qOublie()">Oublier</button></div>
      </div>
    </div>
  </div>
  <div class="tq-plus-t">Plus de QO-100 · le poste, la clé, le transpondeur, la chaîne de station, le pointage</div>
  <div class="grille" style="padding-top:0">
    <div class="col1">
      <div class="carte">
        <div class="t">Ce que voient le poste et la clé</div>
        <label><input type="checkbox" id="o-poste" onchange="qPost('poste?on=' + (this.checked ? 1 : 0))"> Appliquer au poste</label>
        <div class="paire" style="margin-top:8px">
          <span>Poste RX <span class="val cyan" id="o-prx">—</span></span><span>Poste TX <span class="val ambre" id="o-ptx">—</span></span></div>
        <div class="avert" id="o-hors"></div>
        <label style="display:block;margin-top:8px"><input type="checkbox" id="o-cle" onchange="qPost('cle?on=' + (this.checked ? 1 : 0))"> Appliquer à la clé SDR</label>
        <div class="paire" style="margin-top:4px"><span>Clé RX <span class="val cyan" id="o-crx">—</span></span><span class="petit" id="o-cmsg2"></span></div>
        <div class="etat" id="o-statut"></div>
        <div class="petit" style="margin-top:6px">Les fréquences du cockpit sont celles du ciel ; le poste travaille en fréquence intermédiaire, après les convertisseurs de la chaîne. Pas de Doppler ici : le poste n'est écrit que quand tu bouges. Tant que « Appliquer au poste » est coché, le Doppler du satellite suivi est en pause et le carnet note sur QO-100.</div>
        <div class="petit" style="margin-top:4px">« Démarrer la réception QO-100 » coche « Appliquer à la clé » et démarre le récepteur ; l'onglet SDR garde tous ses réglages.</div>
      </div>
    </div>
    <div class="col2">
      <div class="carte" id="oq-sig-carte" hidden>
        <div class="t">Stations entendues <span class="petit" id="oq-sig-n"></span></div>
        <div style="max-height:260px;overflow:auto"><table><tbody id="oq-signaux"></tbody></table></div>
        <div class="petit" style="margin-top:4px">Ce que la clé voit au-dessus du bruit du transpondeur (balises exclues). Un clic : s'y accorder.</div>
      </div>
      <div class="carte">
        <div class="t">Transpondeur</div>
        <div class="ligne" style="margin-top:0" id="o-tps"></div>
        <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="o-balayage" onchange="qPost('balayage?on=' + (this.checked ? 1 : 0))"> Balayer tout QO-100 (écoute ; rien n'est émis là où le plan l'interdit)</label>
        <div class="ligne bouton-gr"><button onclick="qPas(-10000)">−10 k</button><button onclick="qPas(10000)">+10 k</button></div>
      </div>
    </div>
    <div class="col3">
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
</div>
<div class="vue" id="v-sdr" hidden>
<div class="grille2" id="r-grille">
  <div>
    <div class="carte">
      <div class="t"><span id="r-titre">Clé SDR (rtl_tcp)</span> <span id="r-pastille" class="pastille coupe">arrêtée</span></div>
      <div class="ligne" style="margin-top:0"><button class="primaire" data-marche="r" onclick="post('api/sdr/demarrer').then(ticSdr)">Démarrer</button>
        <button class="arret" data-arret="r" hidden onclick="post('api/sdr/arreter').then(ticSdr)">Arrêter</button><span class="petit" id="r-info"></span></div>
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
      <div class="t" id="r-cle-t">La clé</div>
      <div class="ligne" style="margin-top:0"><span class="petit" style="min-width:70px">Récepteur</span>
        <select id="r-recepteur" onchange="rRegle('recepteur=' + this.value)"><option value="rtl">Clé RTL-SDR (rtl_tcp)</option><option value="pluto">ADALM-PLUTO (réseau)</option></select></div>
      <div class="ligne"><span class="petit" style="min-width:70px">Gain</span><select id="r-gain" onchange="rRegle('gain=' + this.value)"></select>
        <label class="petit" id="r-agc-l"><input type="checkbox" id="r-agc" onchange="rRegle('agc=' + (this.checked ? 1 : 0))"> CAG numérique</label></div>
      <div class="ligne"><span class="petit" style="min-width:70px">Erreur</span><input id="r-ppm" style="max-width:70px"><span class="petit">ppm</span><button onclick="rRegle('ppm=' + enc(v('r-ppm')))">Appliquer</button></div>
      <details style="margin-top:6px" id="r-liaison"><summary class="petit" style="cursor:pointer">Liaison rtl_tcp</summary>
        <div class="ligne"><input id="r-hote" placeholder="127.0.0.1" style="max-width:160px"><input id="r-port" placeholder="1234" style="max-width:80px">
          <button onclick="rRegle('hote=' + enc(v('r-hote')) + '&port=' + enc(v('r-port')))">Enregistrer</button></div>
        <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="r-lance" onchange="rRegle('lance=' + (this.checked ? 1 : 0))"> Lancer rtl_tcp sur ce PC (la clé y est branchée)</label>
        <label class="petit" style="display:block;margin-top:4px"><input type="checkbox" id="r-bias" onchange="rRegle('bias=' + (this.checked ? 1 : 0))"> Alimenter l'antenne (bias-tee, préampli ou LNB ; clés qui l'ont)</label>
        <div class="petit" style="margin-top:4px">Une clé sur une autre machine : y lancer <code>rtl_tcp -a 0.0.0.0</code>, puis donner son adresse ici.</div>
      </details>
      <details style="margin-top:6px" id="r-liaison-pluto" hidden><summary class="petit" style="cursor:pointer">Liaison ADALM-PLUTO</summary>
        <div class="ligne"><input id="r-pluto-hote" placeholder="192.168.2.1" style="max-width:160px"><input id="r-pluto-port" placeholder="30431" style="max-width:80px">
          <button onclick="rRegle('plutoHote=' + enc(v('r-pluto-hote')) + '&plutoPort=' + enc(v('r-pluto-port')))">Enregistrer</button></div>
        <div class="petit" style="margin-top:4px">Branché en USB, l'ADALM-PLUTO répond sur 192.168.2.1 ; sur le réseau, donner son adresse. De 70 MHz à 6 GHz : derrière un LNB (FI vers 739 MHz) ou un convertisseur, comme la clé.</div>
        <div class="petit" style="margin-top:4px">Réception seulement : SatMe ne règle que son côté réception (oscillateur, débit, bande, gain) et n'émet jamais.</div>
      </details>
    </div>
    <div id="ph-slot-sdr"></div>
  </div>
  <div class="carte r-droite">
    <div class="paire"><span class="o-freq" id="r-freq">—</span><span class="petit" id="r-detail"></span></div>
    <div class="ligne" style="margin-top:4px"><span class="petit">Niveau</span><div class="r-barre"><i id="r-niveau"></i></div><span class="petit" id="r-db"></span>
      <span class="rose petit" id="r-sat-av"></span>
      <span style="margin-left:auto" class="petit">Largeur vue</span><select id="r-span" style="flex:none" onchange="rSpan = +this.value; rCascade = []; majFlux()"><option value="12000">12 kHz</option><option value="24000">24 kHz</option><option value="48000" selected>48 kHz</option><option value="96000">96 kHz</option><option value="176400">176 kHz</option></select>
      <button onclick="post('api/sdr/pic')" title="Le signal le plus fort à ±15 kHz">Caler sur le pic</button><button onclick="post('api/sdr/decalage?hz=0')">Recentrer</button></div>
    <div class="ligne" style="margin-top:6px">
      <button id="r-voix" onclick="caleVoix()" title="Le centre de la voix entendue (±3 kHz) mis à 1,5 kHz du bord, comme sur le téléphone">🗣 Caler sur la voix</button>
      <span class="petit" id="r-vernier-l" style="margin-left:auto">Vernier</span><span id="r-ratios" class="ligne" style="margin:0;gap:4px"></span></div>
    <canvas id="r-vernier" style="width:100%;height:44px;display:block;margin-top:6px;cursor:ew-resize;touch-action:none" title="Glisser pour pousser la fréquence (comme un cadran) ; molette aussi"></canvas>
    <div id="r-loupe-boite"><div class="ligne" style="margin-top:6px"><span class="petit">Loupe</span><select id="r-loupe-span" style="flex:none" onchange="post('api/sdr/accord?loupeSpan=' + this.value).then(ticSdr)"><option value="3000">3 kHz</option><option value="5000">5 kHz</option><option value="10000">10 kHz</option><option value="20000">20 kHz</option></select><span class="petit">autour de l'écoute · cliquer pour s'y poser</span></div>
      <canvas id="r-loupe" style="width:100%;height:80px;display:block;margin-top:4px;cursor:crosshair"></canvas></div>
    <canvas id="r-spectre" style="width:100%;height:110px;display:block;margin-top:8px;cursor:crosshair" title="Cliquer pour s'y accorder"></canvas>
    <div class="r-cascade"><canvas id="r-cascade" title="Cliquer pour s'y accorder"></canvas></div>
  </div>
</div>
</div>
<div class="vue" id="v-sondes" hidden>
<div class="grille2" id="s2-grille">
  <div>
    <div class="carte">
      <div class="t">Radiosondes · clé SDR <span id="s2-pastille" class="pastille coupe">arrêtée</span></div>
      <div class="ligne" style="margin-top:0"><span class="o-freq" style="font-size:26px" id="s2-freq">—</span><span class="petit">MHz</span>
        <button onclick="sPost('pas?n=-1')">−10 k</button><button onclick="sPost('pas?n=1')">+10 k</button></div>
      <div class="ligne"><input id="s2-khz" placeholder="403 000 (kHz)" style="max-width:150px"><button onclick="sPost('frequence?khz=' + enc(v('s2-khz').replace(/\s/g, '')))">Aller</button>
        <span class="petit" style="margin-left:auto">Modèle</span><select id="s2-modele" style="flex:none" onchange="sPost('modele?m=' + this.value)"></select></div>
      <div class="ligne"><button class="primaire" data-marche="s2" onclick="sPost('demarrer')">Démarrer</button><button class="arret" data-arret="s2" hidden onclick="sPost('arreter')">Arrêter</button>
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
      <div class="t">METEOR-M · images LRPT <span id="m-pastille" class="pastille coupe">arrêté</span></div>
      <div class="petit" id="m-prochain"></div>
      <div class="ligne"><button class="primaire" data-marche="m" onclick="mPost('demarrer')">Recevoir</button><button class="arret" data-arret="m" hidden onclick="mPost('arreter')">Arrêter</button>
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
<div class="f8">
  <div class="carte f8-tete">
    <div class="f8-horloge-boite">
      <svg id="8-anneau" viewBox="0 0 100 100" class="f8-anneau" role="img" aria-label="Le créneau en cours"></svg>
      <span class="f8-horloge" id="8-horloge"></span>
    </div>
    <div style="flex:1;min-width:0">
      <div class="ligne" style="margin-top:0">
        <button class="primaire" id="8-marche" data-marche="8" onclick="ft8Marche()">Écouter</button>
        <button class="arret" data-arret="8" hidden onclick="ft8Marche()">Arrêter</button>
        <select id="8-mode" onchange="post('api/ft8?mode=' + this.value)" style="max-width:90px"><option>FT8</option><option>FT4</option></select>
        <button onclick="post('api/ft8?vide=1').then(() => { f8Lignes = []; f8Total = 0; })">Effacer</button>
        <input id="8-filtre" placeholder="Filtrer : indicatif, pays, carré…" style="max-width:220px">
        <label class="petit"><input type="checkbox" id="8-cq"> CQ seulement</label>
        <span class="bascule-c" style="margin-left:auto"><button id="8-b-tableau" class="actif" onclick="f8Montre('tableau')">📊 Tableau</button><button id="8-b-messages" onclick="f8Montre('messages')">💬 Messages</button></span>
        <span class="petit" id="8-etat"></span>
      </div>
      <div class="f8-puces" id="8-puces"></div>
      <div class="ligne">
        <label class="petit" for="8-guet" title="Une ligne de ces stations est surlignée, et signalée ici. Réception seule : rien n’est émis.">👁 Stations guettées</label>
        <input id="8-guet" placeholder="indicatifs, ex. K1ABC EA8/F4XYZ" style="max-width:320px" onchange="f8Guette(this.value)">
        <span class="f8-puces" id="8-guet-alerte" style="margin-top:0"></span>
      </div>
    </div>
  </div>
  <div class="f8-diag" id="8-diag" role="status" hidden></div>
  <div class="f8-grille">
    <div class="carte f8-cascade-boite">
      <div class="paire" style="margin:0 0 6px"><b>Cascade</b><span class="petit" id="8-libre"></span><span class="petit" style="margin-left:auto">Les indicatifs décodés écrits à leur fréquence, dans leur créneau</span></div>
      <div style="position:relative"><canvas id="8-cascade" style="width:100%;height:420px;display:block;border-radius:8px;background:#05070D;cursor:crosshair"></canvas></div>
      <canvas id="8-spectre" style="width:100%;height:56px;display:block;margin-top:2px"></canvas>
      <div class="petit" style="margin-top:4px">Le son du poste par la carte son (onglet Son), ou la clé SDR. L'horloge du PC doit être juste à la seconde (NTP). Cliquer : la fréquence audio sous la souris.</div>
    </div>
    <div class="carte f8-radar-boite">
      <div class="paire" style="margin:0 0 6px"><b>Radar</b><span class="petit" id="8-radar-info"></span></div>
      <canvas id="8-radar" style="width:100%;aspect-ratio:1/1;display:block;border-radius:50%;background:radial-gradient(circle,#0B1A24 0%,#05070D 75%)"></canvas>
      <div class="petit" style="margin-top:6px">Chaque station à son azimut et sa distance depuis ton QTH. Elle pâlit avec le temps, pulse quand elle vient d'être entendue. <span style="color:#5BD68A">●</span> CQ <span style="color:#FFB454">★</span> nouveau pays <span style="color:#FF5FA2">●</span> m'appelle <span style="color:#6B7C8F">●</span> déjà contacté</div>
    </div>
    <div class="m8" id="8-messages" hidden>
      <div class="carte"><div class="paire" style="margin:0 0 6px"><b>Conversations</b><span class="petit">deux stations qui se parlent</span></div><div class="m8-convs" id="8-convs"></div></div>
      <div class="carte" style="flex:1;min-height:0"><div class="t" style="margin-top:0" id="8-fil-titre"></div><div class="m8-fil" id="8-fil"></div></div>
    </div>
    <div id="8-tableau" style="display:flex;flex-direction:column;gap:12px;min-width:0">
      <div class="carte">
        <div class="paire" style="margin:0 0 6px"><b>Qui appeler maintenant</b><span class="petit">ce que chaque station apporterait</span></div>
        <div id="8-opport"></div>
      </div>
      <div class="carte" style="flex:1;min-height:0">
        <div style="max-height:calc(100vh - 470px);min-height:200px;overflow:auto">
          <table class="f8-table"><thead><tr><th>UTC</th><th></th><th>Station</th><th>dB</th><th>Hz</th><th>Message</th><th>km</th><th></th></tr></thead><tbody id="8-liste"></tbody></table>
        </div>
      </div>
    </div>
  </div>
</div>
</div>
<div class="vue" id="v-reglages" hidden>
<div class="reg"><nav class="reg-nav" id="reg-nav"></nav><div class="reg-corps" id="reg-corps"></div></div>
<div class="grille2" id="reg-origine">
  <div>
    <div class="carte" id="af-carte" hidden>
      <div class="t">Accord fin (clé SDR)</div>
      <div class="petit">Les aides de l'onglet SDR, comme sur le téléphone : elles touchent le décalage logiciel de la clé, jamais le poste.</div>
      <label style="display:block;margin-top:8px"><input type="checkbox" id="af-loupe" onchange="post('api/sdr/accord?loupe=' + (this.checked ? 1 : 0)).then(ticSdr)"> <b>Loupe</b> <span class="petit">— une seconde vue du spectre, quelques kHz autour de l'écoute</span></label>
      <label style="display:block;margin-top:6px"><input type="checkbox" id="af-vernier" onchange="post('api/sdr/accord?vernier=' + (this.checked ? 1 : 0)).then(ticSdr)"> <b>Vernier</b> <span class="petit">— un cadran qu'on pousse : la fréquence avance de N Hz par centimètre glissé</span></label>
      <div class="ligne"><span class="petit">Rapport</span><select id="af-ratio" onchange="post('api/sdr/accord?ratio=' + this.value).then(ticSdr)"><option value="20">20 Hz / cm (fin)</option><option value="200">200 Hz / cm</option><option value="2000">2 kHz / cm (rapide)</option></select></div>
      <label style="display:block;margin-top:6px"><input type="checkbox" id="af-voix" onchange="post('api/sdr/accord?voix=' + (this.checked ? 1 : 0)).then(ticSdr)"> <b>Caler sur la voix</b> <span class="petit">— en BLU, la voix entendue mise à sa place d'un clic</span></label>
    </div>
    <div class="carte" id="rq-carte" hidden>
      <div class="t">QO-100 au démarrage</div>
      <label style="display:block"><input type="checkbox" id="rq-auto" onchange="post('api/qo100/reprise-auto?on=' + (this.checked ? 1 : 0)).then(tqTic)"> <b>Reprendre la réception QO-100 au démarrage</b></label>
      <div class="petit" style="margin-top:4px">Si QO-100 était reçu quand SatMe-PC s'est arrêté, il est repris tout seul au démarrage suivant : le récepteur, la fréquence et le mode d'alors. Sans cette case, un bandeau le propose dans l'onglet QO-100. Réception seulement : rien n'est jamais émis, et le poste n'est pas touché.</div>
    </div>
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
      <div class="t">Poste (CAT) <span id="cat-pastille" class="pastille coupe">déconnecté</span></div>
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
        <button class="primaire" id="cat-co" data-marche="cat" onclick="catConnecte()">Connecter</button>
        <button id="cat-deco" class="arret" data-arret="cat" hidden onclick="post('api/cat/deconnecter')">Déconnecter</button>
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
      <div class="t">Horizon</div>
      <div class="petit">La hauteur des obstacles (toits, arbres, relief) dans chaque direction, en degrés au-dessus de l'horizon. Les passages commencent et finissent alors quand le satellite les dépasse ; les heures sans obstacle restent en infobulle (✱). Tout à 0 : rien ne change.</div>
      <div class="ligne"><span class="petit">Secteurs</span><select id="hz-n" onchange="hzSecteurs(+this.value)"><option value="8">8 × 45°</option><option value="12">12 × 30°</option></select></div>
      <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start;margin-top:6px">
        <svg id="hz-dessin" viewBox="-62 -62 124 124" width="150" height="150" role="img" aria-label="Le masque d'horizon vu du dessus"></svg>
        <div id="hz-champs" style="display:grid;grid-template-columns:repeat(2, auto 64px);gap:4px 8px;align-items:center"></div></div>
      <div class="ligne"><button class="primaire" onclick="enregistreHorizon()">Enregistrer</button><button onclick="hzSecteurs(HZ.length, true)">Tout à 0</button><span class="petit" id="hz-etat"></span></div>
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
    <div class="carte" id="lien-carte">
      <div class="t">Lien satellite (WebSocket)</div>
      <label class="petit" style="display:block"><input type="checkbox" id="lien-on" onchange="regleLien('on=' + (this.checked ? 1 : 0))">
        Diffuser l'état de la station à d'autres programmes de ce PC</label>
      <div class="ligne"><span class="petit">Port</span><input id="lien-port" type="number" min="1024" max="65535" style="max-width:90px">
        <button onclick="regleLien('port=' + enc(v('lien-port')))">Appliquer</button><span class="petit" id="lien-etat"></span></div>
      <div class="petit" style="margin-top:6px">Chaque seconde le satellite suivi (azimut, élévation, distance, fréquences et Doppler), les alertes de passage (AOS dans 5 min, AOS, LOS) et chaque contact noté au carnet, en JSON. Rien n'est accepté en retour. Sur ce PC seulement, sauf si SatMe-PC est lancé avec --reseau.</div>
    </div>
    <div class="carte" id="phr-carte" hidden>
      <div class="t">Phonie → texte</div>
      <div class="petit">La voix entendue (carte son ou clé SDR) écrite au fil de l'écoute, avec les indicatifs, carrés et reports trouvés : une fenêtre dans les onglets SDR, QO-100 et Carnet. Le moteur (faster-whisper, en Python) tourne sur ce PC : rien ne part sur Internet pendant l'écoute. Coupé par défaut : rien ne démarre sans la case de la fenêtre.</div>
      <div class="ligne"><span class="petit" style="min-width:80px">Modèle</span><select id="phr-modele" onchange="phrRegle('modele=' + this.value)"></select></div>
      <div class="ligne"><span class="petit" style="min-width:80px">Calcul</span><select id="phr-calcul" onchange="phrRegle('calcul=' + this.value)"><option value="auto">carte NVIDIA si elle répond, sinon processeur</option><option value="cuda">carte NVIDIA (CUDA)</option><option value="cpu">processeur</option></select></div>
      <div class="ligne"><span class="petit" style="min-width:80px">Python</span><input id="phr-python" placeholder="celui de SatMe (stt/)"><button onclick="phrRegle('python=' + enc(g('phr-python').value.trim()))">Enregistrer</button></div>
      <div class="petit" id="phr-etat" style="margin-top:6px"></div>
      <div class="ligne"><button class="primaire" id="phr-installe" onclick="phrInstalle()">Installer le moteur</button><button id="phr-verifie" onclick="phrRegle('verifie=1')">Vérifier</button></div>
      <div class="petit" style="margin-top:4px">Le bouton télécharge depuis PyPI faster-whisper et ce qu'il lui faut (environ 150 Mo) et, si une carte NVIDIA répond, ses bibliothèques cuBLAS et cuDNN (environ 1,3 Go de plus) ; puis le modèle choisi depuis Hugging Face. Tout va dans <code>stt/</code> du dossier de SatMe ; rien n'est téléchargé sans ce bouton. Il faut Python 3.9 ou plus récent (sous Debian/Ubuntu : python3-venv).</div>
      <div class="etat" id="phr-etape"></div>
      <pre id="phr-journal" hidden style="max-height:180px;overflow:auto;font-size:11px;background:#0E1720;padding:6px;border-radius:8px;white-space:pre-wrap;word-break:break-all;margin:6px 0 0"></pre>
    </div>
    <div class="carte" id="carteTelephone">
      <div class="t">Téléphone</div>
      <div class="petit">Reprendre les réglages du téléphone (Réglages → Sauvegarde, fichier .json) :</div>
      <input type="file" id="import" accept=".json,application/json" onchange="importe(this)" style="width:100%;margin-top:4px">
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">Rotor <span id="rot-pastille" class="pastille coupe">déconnecté</span></div>
      <div class="ligne" style="margin-top:0">
        <select id="rot-lien" onchange="lienRotor()">
          <option value="GS232">GS-232 (port série)</option><option value="ROTCTLD">rotctld (réseau)</option><option value="SIM">Rotor simulé</option>
        </select>
        <select id="rot-baud"><option>9600</option><option>4800</option><option>19200</option><option>115200</option></select>
      </div>
      <div class="ligne" id="rot-serie"><select id="rot-port"></select><button onclick="chargePorts()" title="Relire les ports">↻</button></div>
      <div class="ligne" id="rot-tcp" style="display:none"><input id="rot-hote" placeholder="hôte"><input id="rot-tcpport" placeholder="4533" style="max-width:90px"></div>
      <div class="ligne">
        <button class="primaire" data-marche="rot" onclick="rotConnecte()">Connecter</button>
        <button class="arret" data-arret="rot" hidden onclick="post('api/rotor/deconnecter')">Déconnecter</button>
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
      <div class="t">PSK Reporter</div>
      <label class="petit" style="display:block"><input type="checkbox" id="pskReporter" onchange="post('api/reglages?pskReporter=' + (this.checked ? 1 : 0)).then(chargeReglages)">
        Envoyer à PSK Reporter les stations FT8 / FT4 entendues, toutes les 5 minutes : leur indicatif, leur carré, la fréquence et le satellite, avec mon indicatif et mon carré. Seulement quand la fréquence est sûre (poste relié en CAT)</label>
      <div class="petit" id="pskEtat" style="margin-top:6px"></div>
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
    <div class="carte" id="dop-carte">
      <div class="t">Journal Doppler / CAT</div>
      <label class="petit" style="display:block"><input type="checkbox" id="dop-on" onchange="post('api/doppler?on=' + (this.checked ? 1 : 0)).then(chargeDoppler)">
        Noter chaque passage suivi avec le poste connecté</label>
      <div class="petit" style="margin-top:6px">Toutes les 5 s : élévation, Doppler prévu à la descente et à la montée, fréquence écrite au poste et relue. Un fichier CSV par passage (AOS, montée, TCA, descente, LOS), avec le passage dans le Journal. SatMe ne demande rien de plus au poste.</div>
      <div id="dop-liste" class="petit" style="margin-top:6px"></div>
    </div>
    <div class="carte" id="px-carte">
      <div class="t">ADALM-PLUTO (émission DATV)</div>
      <div class="petit">L'émission se lance de l'onglet DATV, jamais toute seule. Ici, ce qui la borne et ce que fait le Pluto quand SatMe s'en sert en réception.</div>
      <label class="petit" style="display:block;margin-top:8px"><input type="checkbox" id="px-coupe" onchange="post('api/datv/emission/reglage?coupeTx=' + (this.checked ? 1 : 0))">
        Couper l'oscillateur d'émission du Pluto quand SatMe s'en sert en réception</label>
      <div class="petit" style="margin-top:4px">Allumé, le Pluto laisse son oscillateur d'émission tourner (une fuite sur la sortie TX). Coché : SatMe l'éteint à chaque démarrage de la réception par le Pluto.</div>
      <div class="ligne"><span class="petit">Gain d'émission maximal (dB)</span><input id="px-gainmax" type="number" step="1" min="-89" max="0" style="max-width:80px">
        <button onclick="post('api/datv/emission/reglage?gainMax=' + enc(v('px-gainmax'))).then(chargePx)">Enregistrer</button></div>
      <div class="petit">Jamais dépassé, quoi qu'on demande dans l'onglet DATV (0 dB = la pleine puissance du Pluto, environ +7 dBm).</div>
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
  <div class="ligne" id="vi-outils" style="margin-top:6px">
    <audio id="vi-son" controls style="flex:1;min-width:200px;height:32px"></audio>
    <button id="vi-redec" onclick="viAction('redecode?image=')" title="Repasser le son de cette image dans le décodeur">⟳ Redécoder son son</button>
    <button id="vi-gif" onclick="viAction('gif?nom=')" title="L'image qui arrive, en boucle, sans son">🎞 GIF</button><a id="vi-gif-lien" download hidden><button>⤓ GIF</button></a>
    <button class="arret" onclick="viSupprime()">🗑 Supprimer</button></div>
  <div class="petit" id="vi-sans-son"></div>
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
// The log's records: the farthest contacts, by satellite, mode, continent, country; the distances crossed.
const NOMS_CONT = { EU: 'Europe', AF: 'Afrique', AS: 'Asie', NA: 'Amérique du Nord', SA: 'Amérique du Sud', OC: 'Océanie' };
async function chargeRecords() {
  let R; try { R = await (await fetch('api/carnet/records')).json(); } catch (x) { return; }
  const quand = q => q ? new Date(q).toLocaleDateString() : '';
  const ligne = (x, nom) => '<tr><td><b>' + esc(nom) + '</b></td><td class="val cyan" style="font-size:16px">' + x.km.toLocaleString() + ' km</td><td><b>' + esc(x.call) + '</b> ' +
    (x.carre ? '<span class="petit">' + esc(x.carre) + '</span>' : '') + '</td><td class="petit">' + esc(x.sat) + ' · ' + esc(x.mode) + ' · ' + quand(x.quand) + '</td></tr>';
  const t = R.total;
  let h = '<div class="paire" style="margin-top:8px"><span><span class="gros cyan">' + (t ? t.km.toLocaleString() + ' km' : '—') + '</span> <span class="petit">au plus loin' +
    (t ? ' : <b>' + esc(t.call) + '</b> ' + esc(t.carre) + ' · ' + esc(t.sat) + ' · ' + quand(t.quand) : '') + '</span></span></div>';
  h += '<div class="paire" style="margin-top:6px"><span><span class="val">' + R.contacts + '</span> <span class="petit">contacts</span></span><span><span class="val">' + R.carres +
    '</span> <span class="petit">carrés</span></span><span><span class="val">' + R.pays + '</span> <span class="petit">pays</span></span><span><span class="val">' + R.satellites +
    '</span> <span class="petit">satellites</span></span><span><span class="val ambre">' + R.continents.filter(c => c.fait).length + ' / 6</span> <span class="petit">continents (WAC)</span></span></div>';
  h += '<div class="t" style="margin-top:12px">Continents</div><div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:6px;margin-top:4px">' +
    R.continents.map(c => '<div style="padding:8px 10px;border-radius:10px;border:1px solid var(--bord);' + (c.fait ? 'background:rgba(91,214,138,.10)' : 'opacity:.45') + '"><b>' +
      (c.fait ? '✓ ' : '') + NOMS_CONT[c.c] + '</b><br><span class="petit">' + (c.record ? c.record.km.toLocaleString() + ' km · ' + esc(c.record.call) : 'pas encore') + '</span></div>').join('') + '</div>';
  h += '<div class="t" style="margin-top:12px">Distances franchies</div><div class="ligne" style="margin-top:4px">' + R.paliers.map(p => '<span class="pastille" style="padding:4px 10px;' +
    (p.call ? 'background:rgba(63,224,200,.16);color:var(--cyan)' : 'opacity:.4') + '" title="' + (p.call ? esc(p.call) + ' · ' + quand(p.quand) : 'pas encore') + '">' +
    (p.call ? '🏅 ' : '') + p.km.toLocaleString() + ' km</span>').join('') + '</div>';
  const table = (titre, l) => '<div class="t" style="margin-top:12px">' + titre + '</div><table><tbody>' + (l.map(x => ligne(x, x.nom)).join('') || '<tr><td class="petit">aucun contact avec deux carrés</td></tr>') + '</tbody></table>';
  h += table('Par satellite', R.parSatellite) + table('Par mode', R.parMode) + table('Par pays', R.parPays.slice(0, 40));
  h += '<div class="petit" style="margin-top:8px">' + R.avecDistance + ' contact(s) sur ' + R.contacts + ' ont les deux carrés (le tien au moment du contact, le sien) : les autres comptent, sans distance.</div>';
  g('cr-records').innerHTML = h;
}
// The CW decoder: its window in the Carnet; a callsign in the text, clicked, goes into the entry.
let CW = null, cwVu = -1;
async function ticCw() {
  if (!(fluxOk && CW)) { try { CW = await (await fetch('api/cw')).json(); } catch (x) { return; } }
  rendCw();
}
function rendCw() {
  const c = CW; if (!c) return;
  g('cw-carte').hidden = !c.present; if (!c.present) return;
  if (document.activeElement !== g('cw-on')) g('cw-on').checked = c.on;
  g('cw-corps').hidden = !c.on;
  g('cw-led').style.background = c.on && c.marque ? 'var(--vert)' : '#22303C';
  txt('cw-info', !c.on ? 'coupé' : !c.carte ? 'carte son arrêtée (onglet Son)' : (c.wpm ? c.wpm + ' WPM' : '') + (c.ton ? ' · ' + c.ton + ' Hz' : '') + (c.ecart ? ' · ' + c.ecart + ' dB' : '') +
    ({ poste: ' · Doppler : le poste le suit', 'prédit': ' · Doppler : la note suit le passage', 'mesuré': ' · la note suivie à l’oreille' }[c.suivi] || ''));
  if (document.activeElement !== g('cw-ton')) g('cw-ton').value = c.auto ? '0' : String(c.ton);
  if (c.version === cwVu) return;
  cwVu = c.version;
  const t = g('cw-texte'), bas = t.scrollTop + t.clientHeight >= t.scrollHeight - 8;
  // Each word as what it is: a callsign that makes sense (clicked: into the entry), a square (into the square), a report, procedure.
  const vers = (id, m) => 'g(\'' + id + '\').value=\'' + m + '\';g(\'' + id + '\').dispatchEvent(new Event(\'input\'));return false';
  t.innerHTML = (c.mots || []).map(w => {
    const m = esc(w.m);
    if (w.g === 'INDICATIF' && w.c) return '<a href="#" title="Indicatif plausible (' + w.s + '/4) : un clic le met dans la saisie" style="color:var(--cyan);font-weight:800;' +
      (w.s >= 3 ? 'background:rgba(63,224,200,.16);border-radius:4px;padding:0 3px;' : '') + '" onclick="' + vers('c-call', m) + '">' + m + '</a>';
    if (w.g === 'CARRE') return '<a href="#" title="Carré : un clic le met dans la saisie" style="color:var(--ambre)" onclick="' + vers('c-loc', m) + '">' + m + '</a>';
    if (w.g === 'REPORT') return '<span style="color:var(--vert)">' + m + '</span>';
    if (w.g === 'PROCEDURE') return '<span style="color:var(--gris)">' + m + '</span>';
    return m;
  }).join(' ');
  if (bas) t.scrollTop = t.scrollHeight;
}
setInterval(() => { if (!g('v-carnet').hidden || !g('v-qo100').hidden) ticCw(); }, 1000);

// « Phonie → texte »: one window, moved into the tab shown (SDR, QO-100, Carnet); the voice heard, over by over.
let PH = null, phDernier = 0, phListe = [], phTour = 0;
const PH_DRAPEAU = { fr: 'FR', en: 'GB', de: 'DE', es: 'ES', it: 'IT', pl: 'PL', pt: 'PT', nl: 'NL', cs: 'CZ', sk: 'SK', ru: 'RU', uk: 'UA', ja: 'JP', hu: 'HU', ro: 'RO', el: 'GR', sv: 'SE', fi: 'FI', da: 'DK', no: 'NO', hr: 'HR', sl: 'SI', tr: 'TR', zh: 'CN', ca: 'ES' };
function phPlace() {
  const n = ['sdr', 'qo100', 'carnet'].find(t => !g('v-' + t).hidden);
  if (!n) return null;
  const slot = g('ph-slot-' + n), c = g('ph-carte');
  if (c.parentNode !== slot) slot.appendChild(c);
  return n;
}
async function ticPhonie(force) {
  if (!phPlace()) return;
  // Off: a look every 5 s is enough.
  if (!force && PH && !PH.on && (++phTour % 5)) return;
  let j = null;
  try { j = await (await fetch('api/phonie?depuis=' + phDernier)).json(); } catch (x) {}
  if (!j || !j.present) { g('ph-carte').hidden = true; return; }
  PH = j;
  if (j.dernier < phDernier) { phDernier = 0; phListe = []; return ticPhonie(true); }
  let neuf = false;
  if (j.bulles.length) { phListe = phListe.concat(j.bulles).slice(-80); phDernier = j.bulles[j.bulles.length - 1].id; neuf = true; }
  if (phListe.length && phListe[0].id < j.premier) { phListe = phListe.filter(b => b.id >= j.premier); neuf = true; }
  rendPhonie(neuf);
}
function rendPhonie(neuf) {
  const j = PH; if (!j) return;
  g('ph-carte').hidden = false;
  if (document.activeElement !== g('ph-on')) g('ph-on').checked = j.on;
  g('ph-corps').hidden = !j.on;
  g('ph-led').style.background = j.on && j.voix ? 'var(--vert)' : '#22303C';
  if (document.activeElement !== g('ph-langue')) g('ph-langue').value = j.langue;
  if (document.activeElement !== g('ph-source')) g('ph-source').value = j.source;
  g('ph-trad').checked = j.traduire;
  const etat = { 'coupé': 'coupé', 'démarrage': 'chargement du modèle…', 'prêt': j.info || 'prêt', 'erreur': 'en panne' }[j.etat] || j.etat;
  const lag = (j.file || j.perdus) ? ' · retard ' + Math.round(j.retard) + ' s · ' + j.file + ' en attente' + (j.perdus ? ' · ' + j.perdus + ' perdu(s)' : '') : '';
  txt('ph-info', etat + (j.on && j.sourceActive ? ' · ' + (j.sourceActive === 'sdr' ? 'clé SDR' : 'carte son') : '') + lag + (j.on && j.calcul ? ' · ' + String(j.calcul).replace('.', ',') + ' s par passage' : ''));
  txt('ph-erreur', j.erreur || (!j.installe && !j.on ? 'Moteur non installé : Réglages › Phonie → texte.' : ''));
  if (!neuf) return;
  const f = g('ph-fil'), bas = f.scrollTop + f.clientHeight >= f.scrollHeight - 8;
  f.innerHTML = phListe.map(phBulle).join('') || '<div class="petit" style="padding:10px">À l’écoute : chaque passage de voix s’écrira ici.</div>';
  if (bas) f.scrollTop = f.scrollHeight;
}
function phBulle(b) {
  // The text, with what was found in it lit.
  let h = '', i = 0;
  (b.elements || []).slice().sort((x, y) => x.d - y.d).forEach(e => {
    if (e.d < i) return;
    h += esc(b.texte.slice(i, e.d));
    const cl = e.g === 'INDICATIF' ? 'ph-i' : e.g === 'CARRE' ? 'ph-c' : 'ph-r';
    h += '<span class="' + cl + '" title="' + esc(e.v) + '">' + esc(b.texte.slice(e.d, e.f)) + '</span>';
    i = e.f;
  });
  h += esc(b.texte.slice(i));
  const carre = (b.elements || []).find(e => e.g === 'CARRE'), rep = (b.elements || []).find(e => e.g === 'REPORT');
  const vus = {};
  const puces = (b.elements || []).filter(e => e.g === 'INDICATIF' && !vus[e.v] && (vus[e.v] = 1)).map(e =>
    '<button class="ph-ch' + (e.k ? ' connu' : '') + '" title="' + esc(e.pays || '') + (e.k ? ' · déjà au carnet' : '') + ' · un clic : le contact préparé" onclick="phNote(\'' + esc(e.v) + '\',\'' +
    (carre ? esc(carre.v) : '') + '\',\'' + (rep ? esc(rep.v) : '') + '\')">' + (e.drapeau ? f8Drapeau(e.drapeau) + ' ' : '') + esc(e.v) + '</button>').join('');
  const conf = Math.max(0, Math.min(1, b.conf || 0));
  const fr = b.freq ? (b.freq / 1e6).toFixed(4).replace('.', ',') + ' MHz' : '';
  return '<div class="ph-b" style="opacity:' + (0.55 + 0.45 * conf).toFixed(2) + ';border-left-color:' + (conf > 0.7 ? 'var(--vert)' : conf > 0.5 ? 'var(--ambre)' : 'var(--rose)') + '">' +
    '<div class="ph-t">' + hm(b.t, true) + (utc() ? ' UTC' : '') + (fr ? ' · ' + fr : '') + (b.sat ? ' · ' + esc(b.sat) : '') + ' · ' +
    (PH_DRAPEAU[b.langue] ? f8Drapeau(PH_DRAPEAU[b.langue]) + ' ' : '') + esc(b.langue) + ' · ' + Math.round(b.duree) + ' s · sûr à ' + Math.round(conf * 100) + ' %</div>' +
    '<div class="ph-x">' + h + '</div>' + (b.traduction ? '<div class="ph-tr">' + esc(b.traduction) + '</div>' : '') + (puces ? '<div>' + puces + '</div>' : '') + '</div>';
}
function phNote(call, loc, rep) {
  versSaisie(); g('c-call').value = call;
  if (loc) g('c-loc').value = loc;
  if (rep) g('c-rr').value = rep;
  g('c-call').dispatchEvent(new Event('input')); g('c-call').focus();
}
async function phRegle(q) {
  if (q === 'efface=1') { phListe = []; }
  await post('api/phonie?' + q);
  ticPhonie(true);
}
setInterval(() => ticPhonie(false), 1000);
// Its settings: the engine, the model, the installation (followed while it runs).
let PHR = null, phrSuivi = null;
async function chargePhonieReglages() {
  let j = null;
  try { j = await (await fetch('api/phonie/moteur')).json(); } catch (x) {}
  if (!j || j.venv === undefined) { g('phr-carte').hidden = true; return; }
  PHR = j; g('phr-carte').hidden = false;
  const m = g('phr-modele');
  if (m.options.length !== j.modeles.length) m.innerHTML = j.modeles.map(x => '<option value="' + x.nom + '">' + x.nom + ' (' + x.taille + ')' +
    ({ 'large-v3': ' — le plus juste, carte NVIDIA conseillée', turbo: ' — presque aussi juste, bien plus rapide', medium: ' — moyen', small: ' — léger, pour le processeur' }[x.nom] || '') + '</option>').join('');
  if (document.activeElement !== m) m.value = j.modele;
  if (document.activeElement !== g('phr-calcul')) g('phr-calcul').value = j.calcul;
  if (document.activeElement !== g('phr-python')) g('phr-python').value = j.python;
  const v = j.verification;
  let e = j.pythonUtilise ? 'Python : ' + j.pythonUtilise + (j.python ? '' : ' (stt/ de SatMe)') : 'Moteur non installé (' + j.venv + ' absent).';
  if (j.gpu) e += ' · carte : ' + j.gpu;
  if (v) e += v.ok ? ' · faster-whisper ' + v.faster_whisper + ' · CUDA : ' + (v.cuda ? v.cuda + ' carte(s)' : 'non') + ' · modèles présents : ' + (v.modeles.join(', ') || 'aucun') : ' · ' + (v.erreur || 'vérification ratée');
  else if (j.verificationEnCours) e += ' · vérification…';
  txt('phr-etat', e);
  const i = j.installation;
  g('phr-installe').disabled = i.enCours;
  txt('phr-etape', i.erreur ? 'Échec : ' + i.erreur : i.enCours ? 'En cours : ' + i.etape + (i.mo ? ' · ' + Math.round(i.mo) + ' Mo' : '') : i.fini ? 'Moteur installé : la case « Phonie → texte » des onglets SDR, QO-100 ou Carnet le met en marche.' : '');
  g('phr-journal').hidden = !i.lignes.length;
  const pre = g('phr-journal'), bas = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 8;
  const t = i.lignes.join('\n');
  if (pre.textContent !== t) { pre.textContent = t; if (bas) pre.scrollTop = pre.scrollHeight; }
  clearTimeout(phrSuivi);
  if ((i.enCours || j.verificationEnCours) && !g('v-reglages').hidden) phrSuivi = setTimeout(chargePhonieReglages, 1000);
}
async function phrRegle(q) { await post('api/phonie/moteur?' + q); chargePhonieReglages(); if (q === 'verifie=1') setTimeout(chargePhonieReglages, 600); }
async function phrInstalle() {
  const m = g('phr-modele').value, t = (PHR && PHR.modeles.find(x => x.nom === m) || {}).taille || '';
  if (!confirm('Télécharger et installer le moteur de transcription ?\n\nfaster-whisper depuis PyPI (environ 150 Mo, et environ 1,3 Go de plus avec une carte NVIDIA), puis le modèle « ' + m + ' » (' + t + ') depuis Hugging Face, dans le dossier stt/ de SatMe.')) return;
  await post('api/phonie/moteur?installe=1');
  setTimeout(chargePhonieReglages, 300);
}
// APRS's game: the trophies, and a cheer when one falls (whatever the tab).
let TR = null, trVu = null;
async function ticTrophees() {
  try { TR = await (await fetch('api/aprs/trophees')).json(); } catch (x) { return; }
  if (!TR || !TR.present) { g('tr-carte').hidden = true; return; }
  const ev = TR.evenements[0];
  if (ev && trVu !== null && ev.quand > trVu) toast(ev.texte);
  if (ev) trVu = Math.max(trVu || 0, ev.quand); else if (trVu === null) trVu = 0;
  if (!ouverte('aprs')) return;
  const j = TR.jour, r = TR.records, n = (v, l, c) => '<span><span class="val ' + (c || '') + '">' + v + '</span> <span class="petit">' + l + '</span></span>';
  const gagnes = TR.badges.filter(b => b.quand).length;
  txt('tr-resume', ' · ' + gagnes + ' / ' + TR.badges.length + ' badges');
  g('tr-jour').innerHTML = '<span class="petit">Aujourd’hui</span>' + n(j.stations, 'stations') + n(j.pays, 'pays') + n(j.carres, 'carrés') + n(j.viaIss, 'par l’ISS', 'ambre') +
    n(j.contacts, 'contacts') + n(j.repetes, 'répétées') + (j.plusLoin ? n(j.plusLoin.km, 'km (' + esc(j.plusLoin.call) + ')', 'cyan') : '');
  g('tr-records').innerHTML = n(r.km ? r.km : '—', 'km au plus loin' + (r.kmCall ? ' (' + esc(r.kmCall) + ')' : ''), 'cyan') + n(r.carres, 'carrés') + n(r.pays, 'pays') +
    n(r.repetes, 'répétées par l’ISS', 'ambre') + n(r.contacts, 'contacts');
  g('tr-badges').innerHTML = TR.badges.map(b => '<div title="' + esc(b.desc) + '" style="display:flex;gap:8px;align-items:center;padding:8px 10px;border-radius:10px;border:1px solid var(--bord);' +
    (b.quand ? 'background:rgba(91,214,138,.10)' : 'opacity:.45') + '"><span style="font-size:24px">' + b.emoji + '</span><span><b>' + esc(b.nom) + '</b><br><span class="petit">' +
    (b.quand ? 'gagné le ' + new Date(b.quand).toLocaleDateString() : esc(b.desc)) + '</span></span></div>').join('');
  g('tr-contacts').innerHTML = TR.contacts.map(c => esc(c.call) + (c.carre ? ' · ' + esc(c.carre) : '') + (c.iss ? ' · 🛰 ISS' : '') + ' · ' + new Date(c.quand).toLocaleString()).join('<br>') || 'aucun contact (un message et son accusé)';
  g('tr-fetes').innerHTML = TR.evenements.map(e => new Date(e.quand).toLocaleString() + ' — ' + esc(e.texte)).join('<br>') || 'rien encore depuis le démarrage';
}
setInterval(ticTrophees, 15000);
function toast(texte) {
  const d = document.createElement('div');
  d.textContent = texte;
  d.style.cssText = 'position:fixed;right:18px;bottom:18px;z-index:99;padding:12px 16px;border-radius:12px;background:var(--carte);border:1px solid var(--cyan);font-weight:700;box-shadow:0 6px 24px rgba(0,0,0,.4)';
  document.body.appendChild(d); setTimeout(() => d.remove(), 8000);
}
// The SSTV test card.
let MI = null;
function paramsMire() {
  return 'mode=' + enc(g('mi-mode').value) + '&bruit=' + g('mi-bruit').value + '&fading=' + g('mi-fading').value +
    '&coupures=' + (g('mi-coupures').checked ? 1 : 0) + '&profil=' + (g('mi-profil').checked ? 1 : 0);
}
function apercuMire() {
  g('mi-apercu').src = 'api/mire/image.png?mode=' + enc(g('mi-mode').value);
  const m = MI && MI.modes.find(x => x.nom === g('mi-mode').value);
  txt('mi-duree', m ? m.w + '×' + m.h + ' · ' + duree(m.s * 1000) : '');
  g('mi-wav').href = 'mire.wav?' + paramsMire();
}
function presetMire(iss) {
  g('mi-bruit').value = iss ? '15' : '0'; g('mi-fading').value = iss ? 'LIGHT' : 'NONE';
  g('mi-coupures').checked = !!iss; g('mi-profil').checked = !!iss; apercuMire();
}
['mi-bruit', 'mi-fading', 'mi-coupures', 'mi-profil'].forEach(id => g(id).addEventListener('change', apercuMire));
async function joueMire() { await post('api/mire/joue?' + paramsMire() + '&sortie=' + enc(g('mi-sortie').value)); ticMire(); }
async function ticMire(premier) {
  try { MI = await (await fetch('api/mire')).json(); } catch (x) { MI = null; }
  g('mi-carte').hidden = !(MI && MI.present); if (!MI || !MI.present) return;
  if (premier || !g('mi-mode').options.length) {
    const avant = g('mi-mode').value;
    g('mi-mode').innerHTML = MI.modes.map(m => '<option>' + esc(m.nom) + '</option>').join('');
    g('mi-mode').value = avant || (MI.modes.some(m => m.nom === 'PD 120') ? 'PD 120' : MI.modes[0].nom);
    const s = g('mi-sortie').value;
    g('mi-sortie').innerHTML = MI.sorties.map(o => '<option value="' + esc(o.nom) + '">' + esc(o.nom ? o.nom + (o.desc ? ' — ' + o.desc.slice(0, 50) : '') : o.desc) + '</option>').join('');
    if (s) g('mi-sortie').value = s;
    apercuMire();
  }
  g('mi-barre').style.width = Math.round((MI.progres || 0) * 100) + '%';
  txt('mi-etat', MI.erreur || (MI.joue ? 'lecture de la mire ' + MI.mode + '… ' + Math.round(MI.progres * 100) + ' %' : ''));
  g('mi-joue').disabled = MI.joue;
  if (MI.joue) setTimeout(ticMire, 1000);
}
// Réglages by category: a list on the left (icon, name, what it holds), the chosen category's cards on the right.
const ICO = ${icones()};
const CATS_REG = [
  { id: 'station', nom: 'Ma station', ico: 'person', desc: 'Indicatif, carré, élévation minimale, horizon', cartes: ['Station', 'Horizon'] },
  { id: 'materiel', nom: 'Poste et rotor', ico: 'radio', desc: 'CAT, TH-D72, rotor, convertisseurs, ADALM-PLUTO', cartes: ['Poste', 'Kenwood TH-D72', 'Rotor', 'Convertisseurs', 'Journal Doppler', 'ADALM-PLUTO'] },
  { id: 'satellites', nom: 'Satellites', ico: 'satellite_alt', desc: 'Éléments orbitaux, statut AMSAT, lien WebSocket, QO-100 au démarrage', cartes: ['Éléments orbitaux', 'AMSAT', 'Lien satellite', 'QO-100'] },
  { id: 'enligne', nom: 'Services en ligne', ico: 'public', desc: 'QRZ.com, Wavelog / Cloudlog, skeds hams.at', cartes: ['QRZ.com', 'Carnet en ligne', 'Skeds'] },
  { id: 'phonie', nom: 'Phonie → texte', ico: 'mic', desc: 'La voix entendue écrite : moteur, modèle, installation', cartes: ['Phonie → texte'] },
  { id: 'telephone', nom: 'Téléphone', ico: 'smartphone', desc: 'SatMe sur le téléphone : réglages et contacts', cartes: ['Téléphone'] },
  { id: 'accord', nom: 'Accord fin', ico: 'tune', desc: 'Loupe, vernier, calage sur la voix (clé SDR)', cartes: ['Accord fin'] },
  { id: 'sauvegarde', nom: 'Sauvegarde', ico: 'save', desc: 'Toute la station dans un fichier, et la restaurer', cartes: ['Sauvegarde'] },
];
// The backup: its size, the link, a backup received waiting for the next start.
let SAUV = null;
async function majSauvegarde() {
  try { SAUV = await (await fetch('api/sauvegarde')).json(); } catch (x) { SAUV = null; }
  g('sv-carte').hidden = !(SAUV && SAUV.present);
  // A category whose cards are all hidden here (the page served by the phone) leaves the list.
  document.querySelectorAll('#reg-corps .reg-sec').forEach(sec => {
    const vide = ![...sec.querySelectorAll('.reg-cartes > .carte')].some(k => !k.hidden || k.id === 'thd72-carte');
    const b = document.querySelector('#reg-nav button[data-cat="' + sec.dataset.cat + '"]'); if (b) b.hidden = vide;
  });
  if (!SAUV || !SAUV.present) return;
  const m = g('sv-medias').checked;
  g('sv-lien').href = 'sauvegarde.zip' + (m ? '?medias=1' : '');
  txt('sv-taille', 'environ ' + ((m ? SAUV.tailleMedias : SAUV.taille) / 1048576).toFixed(1) + ' Mo');
  g('sv-etat').innerHTML = SAUV.attente ? 'Une sauvegarde du ' + new Date(SAUV.attente.date).toLocaleString() + ' sera restaurée au prochain démarrage de SatMe-PC. <button onclick="post(\'api/sauvegarde/annule\').then(majSauvegarde)">Annuler</button>' : '';
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
  x.font = 'bold 12px Drapeaux,system-ui'; x.textAlign = 'left'; x.lineWidth = 3; x.strokeStyle = 'rgba(255,255,255,.85)';
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
      '" onclick="favori(' + p.catnum + ',' + !p.fav + ',event)">★</span></td><td>' + hm(p.aos) + marqueHorizon(p) + '</td><td>' +
      (a ? '<span title="AMSAT : ' + a[1] + '" style="display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px;background:' + a[0] + '"></span>' : '') +
      p.nom.replace(/</g, '&lt;') + (p.skeds ? ' <span title="' + p.skeds + ' sked(s) annoncé(s) sur hams.at" style="color:var(--ambre)">📣' + (p.skeds > 1 ? p.skeds : '') + '</span>' : '') +
      '</td><td>' + Math.round(p.elMax) + '°</td><td>' + duree(p.los - p.aos) + '</td><td class="petit">' + quand + '</td></tr>';
  });
  g('ics-liste').href = 'api/passages.ics?f=' + enc(g('filtre').value.trim()) + (seulFav ? '&fav=1' : '') + (seulActifs ? '&actifs=1' : '');
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
  if (!tqQo()) txt('c-sat', E.suivi ? '· ' + E.suivi.nom + (E.position ? ' · El ' + Math.round(E.position.el) + '°' : '') : '· choisis le satellite ci-dessous');
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
  if (E.suivi) g('ics-suivi').href = 'api/passages.ics?catnum=' + E.suivi.catnum;
  const sv = JSON.stringify(E.passagesSuivi.map(p => [p.aos, p.agenda]));
  if (sv !== g('suivants').dataset.vu) {
    g('suivants').dataset.vu = sv;
    g('suivants').innerHTML = E.passagesSuivi.map(p => '<tr>' + celluleAgenda(p) + '<td class="rosace">' + rosace(p.trace, p.elMax, 40) + '</td><td>' + jourHeure(p.aos) + marqueHorizon(p) + '</td><td>' + Math.round(p.elMax) + '°</td><td>' +
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
  etatMarche('cat', c.connecte);
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
  etatMarche('rot', r.connecte);
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
  // The stream keeps E fresh: only drawn again (an action's result comes by the stream within a moment).
  if (fluxOk && E) { dessine(); return; }
  try { E = await (await fetch('api/etat')).json(); dessine(); } catch (x) {}
}
// The sound in this browser: SatMe-PC streams the dongle's or the sound card's audio; played here with a short buffer.
let ecCtx = null, ecGain = null, ecLecteur = null, ecArret = null, ecFile = [], ecLu = 0, ecRate = 22050, ecPos = 0;
async function ecouteIci() {
  arreteEcoute();
  const source = g('ec-source').value;
  try {
    ecCtx = new (window.AudioContext || window.webkitAudioContext)();
    ecGain = ecCtx.createGain(); ecGain.gain.value = v('ec-vol') / 100; ecGain.connect(ecCtx.destination);
    // Played from a queue, resampled to the card's rate; too far behind (more than a second), it catches up.
    ecLecteur = ecCtx.createScriptProcessor(2048, 0, 1);
    ecLecteur.onaudioprocess = ev => {
      const out = ev.outputBuffer.getChannelData(0), pas = ecRate / ecCtx.sampleRate;
      let enFile = ecFile.reduce((a, b) => a + b.length, 0) - ecPos;
      if (enFile > ecRate) { while (ecFile.length > 1 && enFile > ecRate * 0.3) { enFile -= ecFile[0].length - ecPos; ecFile.shift(); ecPos = 0; } }
      for (let i = 0; i < out.length; i++) {
        if (!ecFile.length) { out[i] = 0; continue; }
        const b = ecFile[0], k = Math.floor(ecPos);
        out[i] = b[k] || 0;
        ecPos += pas;
        if (ecPos >= b.length) { ecPos -= b.length; ecFile.shift(); }
      }
    };
    ecLecteur.connect(ecGain);
    ecArret = new AbortController();
    const r = await fetch('api/son/flux?source=' + source, { signal: ecArret.signal });
    const lecteur = r.body.getReader();
    let reste = new Uint8Array(0), entete = true;
    g('ec-go').hidden = true; g('ec-stop').hidden = false; g('ec-btn').textContent = '🔊 Écoute';
    txt('ec-etat', 'en écoute : ' + (source === 'sdr' ? 'clé SDR' : 'carte son'));
    while (true) {
      const { value, done } = await lecteur.read(); if (done) break;
      const t = new Uint8Array(reste.length + value.length); t.set(reste); t.set(value, reste.length);
      let o = 0;
      if (entete && t.length >= 4) { ecRate = t[0] | (t[1] << 8) | (t[2] << 16) | (t[3] << 24); o = 4; entete = false; }
      const n = Math.floor((t.length - o) / 2), f = new Float32Array(n), dv = new DataView(t.buffer, o, n * 2);
      for (let i = 0; i < n; i++) f[i] = dv.getInt16(2 * i, true) / 32768;
      if (n) ecFile.push(f);
      reste = t.slice(o + n * 2);
    }
  } catch (x) { if (ecArret && !ecArret.signal.aborted) txt('ec-etat', 'son impossible : ' + (x.message || x)); }
  if (ecArret && !ecArret.signal.aborted) arreteEcoute();
}
function arreteEcoute() {
  if (ecArret) ecArret.abort(); ecArret = null;
  if (ecLecteur) { try { ecLecteur.disconnect(); } catch (x) {} ecLecteur = null; }
  if (ecCtx) { try { ecCtx.close(); } catch (x) {} ecCtx = null; }
  ecFile = []; ecPos = 0;
  g('ec-go').hidden = false; g('ec-stop').hidden = true; g('ec-btn').textContent = '🔈 Écouter ici';
}
// Shown where SatMe-PC streams (not on the page served by the phone).
g('ec-boite').hidden = !!window.SATME_TELEPHONE;
// Connected or not, at a glance: one button at a time (Connect when off, Disconnect when on),
// the pill green or red, the card's edge green or red.
function etatMarche(cle, on, pastille) {
  document.querySelectorAll('[data-marche="' + cle + '"]').forEach(b => b.hidden = !!on);
  document.querySelectorAll('[data-arret="' + cle + '"]').forEach(b => b.hidden = !on);
  const p = g(pastille || cle + '-pastille');
  if (p) { p.classList.remove('on', 'off'); p.classList.toggle('marche', !!on); p.classList.toggle('coupe', !on); }
  const b = document.querySelector('[data-marche="' + cle + '"]');
  const carte = b && b.closest('.carte');
  if (carte) { carte.classList.toggle('allume', !!on); carte.classList.toggle('eteint', !on); }
}
// ------------------------------------------------------------------ the live stream (server-sent events)
// SatMe-PC sends the station, the sound, the dongle and its spectrum as they change; the page's questions
// stay as the fallback (the stream cut, or the page served by the phone, which has none).
let flux = null, fluxOk = false, fluxSujets = '';
function sujetsFlux() {
  const sdr = !g('v-sdr').hidden;
  return 'etat,son,cw' + (sdr || !g('v-carnet').hidden ? ',sdr' : '') + (sdr ? ',spectre&span=' + rSpan + '&n=' + Math.min(1024, Math.max(128, g('r-spectre').clientWidth || 512)) : '');
}
function majFlux() {
  if (!window.EventSource || window.SATME_TELEPHONE) return;
  const t = sujetsFlux();
  if (flux && t === fluxSujets && flux.readyState !== 2) return;
  if (flux) flux.close();
  fluxSujets = t; fluxOk = false;
  flux = new EventSource('api/flux?t=' + t);
  flux.addEventListener('etat', ev => { fluxOk = true; E = JSON.parse(ev.data); dessine(); });
  flux.addEventListener('son', ev => { S = JSON.parse(ev.data); rendSon(); });
  flux.addEventListener('cw', ev => { CW = JSON.parse(ev.data); rendCw(); });
  flux.addEventListener('sdr', ev => { R = JSON.parse(ev.data); rendSdr(); });
  flux.addEventListener('spectre', ev => { const j = JSON.parse(ev.data); if (j.db && j.trames !== rTrames) { rTrames = j.trames; rDernier = j; rCascade.unshift(j.db); if (rCascade.length > 600) rCascade.length = 600; dessineSdr(); } });
  // Cut: the questions take over until it comes back (the browser tries again by itself).
  flux.onerror = () => { fluxOk = false; };
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
  setTimeout(majFlux, 0);
  tqRange(n);
  document.querySelectorAll('.vue').forEach(x => x.hidden = x.id !== 'v-' + n);
  document.querySelectorAll('#onglets button').forEach(b => b.classList.toggle('actif', b.dataset.v === n));
  try { localStorage.setItem('vue', n); } catch (x) {}
  if (n === 'carnet') { chargeCarnet(); g('c-call').focus(); vueCarnet(vueC); ticCw(); }
  if (n === 'carnet' || n === 'sdr' || n === 'qo100') ticPhonie(true);
  if (n === 'reglages') chargePhonieReglages();
  if (n === 'reglages') { rangeReglages(); majSauvegarde(); majAccordReglages(); }
  if (n === 'reglages') { chargeReglages().then(() => { if (!wlLu && v('carnetUrl')) { wlLu = true; essaiCarnet(); } }); chargeSources(); }
  if (n === 'son' || n === 'sstv') ticSon();
  if (n === 'sstv') ticMire(true);
  if (n === 'agenda') ticVeille();
  if (n === 'aprs') { ticAprs(); ticTrophees(); }
  if (n === 'ft8') ticFt8();
  if (n === 'carres') chargeCarres();
  if (n === 'frise') chargeFrise();
  if (n === 'sked') { prepareSked(); setTimeout(dessineSked); }
  if (n === 'qo100') { chargeQo100(true); ticQualite(); tqAide(); tqTic(); ticCw(); ticPhonie(true); tqFt8(); }
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
  g('cr-station').hidden = q !== 'station'; g('cr-contacts').hidden = q !== 'contacts'; g('cr-records').hidden = q !== 'records';
  g('cv-station').classList.toggle('actif', q === 'station'); g('cv-contacts').classList.toggle('actif', q === 'contacts');
  g('cv-records').classList.toggle('actif', q === 'records');
  if (q === 'records') chargeRecords();
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
  const j = await post('api/carnet/ajoute?c=' + enc(c) + '&l=' + enc(v('c-loc')) + '&rs=' + enc(v('c-rs')) + '&rr=' + enc(v('c-rr')) + '&n=' + enc(v('c-note')) + apres +
    (dtQso ? '&datv=' + dtQso : ''));
  if (j && j.ok) {
    if (dtQso) { dtQso = 0; const a = g('c-aide'); if (a.dataset.avant) { a.textContent = a.dataset.avant; a.dataset.avant = ''; } }
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
    horizonDe(r); chargeDoppler(); chargeLien(); chargePx();
    g('qrzFiche').checked = r.fiche; ficheVoulue = r.fiche;
    g('qrzUser').value = r.qrzUser; g('qrzMdp').placeholder = r.qrzMdp ? 'mot de passe enregistré' : 'Mot de passe';
    g('carnetUrl').value = r.carnetUrl; g('carnetCle').placeholder = r.carnetCle ? 'clé enregistrée' : 'Clé d’API (lecture et écriture)';
    g('carnetProfil').value = r.carnetProfil;
    g('carnetAuto').checked = r.carnetAuto; g('carnetDelaiL').hidden = !r.carnetAuto;
    g('amsatAuto').checked = !!r.amsatAuto;
    txt('amsatEtat', r.amsatEtat || (r.amsatAuto ? 'Les contacts faits depuis que c’est coché seront signalés.' : 'Coupé : rien n’est envoyé sans toi (bouton « Entendu » de l’onglet Station).'));
    g('pskReporter').checked = !!r.pskReporter;
    txt('pskEtat', r.pskEtat || (r.pskReporter ? 'Activé : premier envoi 5 minutes après la première station entendue.' : 'Coupé : rien n’est envoyé.'));
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
// ------------------------------------------------------------------ satellite link (WebSocket)
async function chargeLien() {
  let j = null;
  try { j = await (await fetch('api/lien')).json(); } catch (x) {}
  if (!j || j.absent || j.on === undefined) { g('lien-carte').hidden = true; return; }
  g('lien-on').checked = !!j.on;
  if (document.activeElement !== g('lien-port')) g('lien-port').value = j.port;
  txt('lien-etat', j.erreur ? j.erreur : j.ecoute ? 'ws://' + (j.reseau ? location.hostname : 'localhost') + ':' + j.port + '/ · ' + j.clients + ' client(s)' : 'coupé');
}
async function regleLien(q) { await post('api/lien?' + q); chargeLien(); }
// ------------------------------------------------------------------ Doppler/CAT log
async function chargeDoppler() {
  let j = null;
  try { j = await (await fetch('api/doppler')).json(); } catch (x) {}
  if (!j || j.on === undefined) { g('dop-carte').hidden = true; return; }
  g('dop-on').checked = !!j.on;
  g('dop-liste').innerHTML = j.fichiers.length ? 'Derniers : ' + j.fichiers.map(f => '<a href="journal/doppler?nom=' + enc(f.nom) + '" download style="color:var(--cyan)">' +
    esc(f.nom) + '</a> (' + Math.max(1, Math.round(f.taille / 1024)) + ' ko)').join(' · ') : 'Aucun journal pour l’instant.';
}
// ------------------------------------------------------------------ horizon mask
let HZ = [0, 0, 0, 0, 0, 0, 0, 0];
function hzSecteurs(n, zero) {
  if (zero || HZ.length !== n) HZ = Array(n).fill(0);
  g('hz-n').value = String(HZ.length);
  const w = 360 / HZ.length;
  g('hz-champs').innerHTML = HZ.map((e, i) => '<span class="petit">' + Math.round(i * w) + '–' + Math.round((i + 1) * w) + '°</span>' +
    '<input type="number" min="0" max="90" step="1" value="' + e + '" oninput="HZ[' + i + '] = Math.max(0, Math.min(90, +this.value || 0)); hzDessin()">').join('');
  hzDessin();
}
// Seen from above: the ring is the horizon, the centre the zenith; the obstacles in amber.
function hzDessin() {
  const R = 55, w = 360 / HZ.length, pt = (az, r) => { const a = az * Math.PI / 180; return (r * Math.sin(a)).toFixed(1) + ' ' + (-r * Math.cos(a)).toFixed(1); };
  let d = 'M' + pt(0, R) + ' A' + R + ' ' + R + ' 0 1 1 ' + pt(180, R) + ' A' + R + ' ' + R + ' 0 1 1 ' + pt(360, R) + 'Z M';
  HZ.forEach((e, i) => { const r = R * (1 - e / 90); for (let k = 0; k <= 6; k++) d += (i || k ? ' L' : '') + pt(i * w + k * w / 6, r); });
  let h = '<circle r="' + R + '" fill="none" stroke="var(--bord)"/><circle r="' + (R * 2 / 3).toFixed(1) + '" fill="none" stroke="var(--bord)" stroke-dasharray="2 3"/>' +
    '<circle r="' + (R / 3).toFixed(1) + '" fill="none" stroke="var(--bord)" stroke-dasharray="2 3"/>' +
    '<path d="' + d + 'Z" fill="var(--ambre)" fill-opacity=".45" fill-rule="evenodd" stroke="var(--ambre)" stroke-width=".8"/>';
  [['N', 0], ['E', 90], ['S', 180], ['O', 270]].forEach(([l, a]) => { const [x, y] = pt(a, R + 5).split(' ');
    h += '<text x="' + x + '" y="' + y + '" font-size="8" fill="var(--gris)" text-anchor="middle" dominant-baseline="middle">' + l + '</text>'; });
  g('hz-dessin').innerHTML = h;
}
async function enregistreHorizon() {
  const j = await post('api/horizon?m=' + (HZ.some(e => e > 0) ? HZ.join(',') : ''));
  if (j && j.ok !== false) { txt('hz-etat', 'enregistré, passages recalculés'); setTimeout(chargeListe, 1500); }
}
function horizonDe(r) {
  if (document.activeElement && document.activeElement.closest && document.activeElement.closest('#hz-champs')) return;
  HZ = r.horizon && r.horizon.length ? r.horizon.slice() : Array(+g('hz-n').value || 8).fill(0);
  hzSecteurs(HZ.length);
}
// A pass moved by the horizon mask: its times over the bare horizon, in a tooltip.
function marqueHorizon(p) {
  return p.geoAos ? '<span class="petit" title="Au-dessus des obstacles de ' + hm(p.aos) + ' à ' + hm(p.los) + ' ; sans obstacle de ' +
    hm(p.geoAos) + ' à ' + hm(p.geoLos) + '"> ✱</span>' : '';
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
  etatMarche('d', D.actif, 'aucune');
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
  qualiteDatv();
}
// ------------------------------------------------------------------ DATV reception quality
let qEm = null, qEmPs = null;
const fr1 = x => x == null ? '—' : x.toFixed(1).replace('.', ',');
const pct = x => x == null ? '—' : Math.round(x * 100) + ' %';
function dqCase(titre, val, sous, couleur) {
  return '<div class="q-tuile">' + titre + '<br><b' + (couleur ? ' style="color:' + couleur + '"' : '') + '>' + val + '</b>' + (sous ? '<br>' + sous : '') + '</div>';
}
function qualiteDatv() {
  const Q = D.qualite, b = g('q-boite');
  b.hidden = !Q || !(D.actif || Q.secondes > 0);
  if (b.hidden) return;
  const m = Q.marge, mm = g('q-marge');
  txt('q-marge', m == null ? 'Marge —' : 'Marge ' + (m >= 0 ? '+' : '−') + fr1(Math.abs(m)) + ' dB');
  mm.style.color = m == null ? 'var(--gris)' : m < 1 ? 'var(--rose)' : m < 3 ? 'var(--ambre)' : 'var(--vert)';
  const vd = Q.verdict || {};
  txt('q-verdict', vd.texte || ''); g('q-verdict').style.color = vd.niveau === 0 ? 'var(--vert)' : vd.niveau === 1 ? 'var(--ambre)' : 'var(--rose)';
  txt('q-conseil', vd.conseil ? '→ ' + vd.conseil : '');
  const rc = Q.ratio == null ? null : Q.ratio < 0.9 ? 'var(--rose)' : Q.ratio < 0.98 ? 'var(--ambre)' : 'var(--vert)';
  const t = [];
  t.push(dqCase('Seuil du MODCOD', Q.seuil == null ? '—' : fr1(Q.seuil) + ' dB', 'MER ' + fr1(Q.mer) + ' dB'));
  t.push(dqCase('Paquets reçus / attendus', Math.round(Q.recus) + ' / ' + (Q.attendus == null ? '—' : Math.round(Q.attendus)) + ' /s',
    pct(Q.ratio) + (Q.ratioPcr != null ? ' · d’après le PCR ' + pct(Q.ratioPcr) : ''), rc));
  t.push(dqCase('Continuité (paquets perdus)', fr1(Q.cc) + ' err/s', Q.totalCc + ' erreurs, ≥ ' + Q.totalManquants + ' paquets manquants',
    Q.cc >= 0.2 ? 'var(--rose)' : 'var(--vert)'));
  t.push(dqCase('Erreurs de transport', 'TEI ' + Q.totalTei, 'pertes de synchro ' + Q.sync + (Q.doublons ? ' · doublons ' + Q.doublons : ''),
    Q.totalTei || Q.sync ? 'var(--ambre)' : ''));
  const fec = [];
  if (D.bchNonCorrige != null) fec.push('BCH non corrigé : ' + (D.bchNonCorrige ? 'oui' : 'non'));
  if (D.bch != null) fec.push('BCH ' + fr1(Q.bchS) + '/s');
  if (D.ldpc != null) fec.push('LDPC ' + fr1(Q.ldpcS) + '/s');
  if (D.viterbi != null && D.etat === 3) fec.push('Viterbi ' + D.viterbi.toFixed(2) + ' %');
  t.push(dqCase('Correction d’erreurs', D.ber != null ? 'BER ' + D.ber.toFixed(2) + ' %' : '—', fec.join(' · '),
    Q.fec === true ? 'var(--rose)' : Q.fec === false ? 'var(--vert)' : ''));
  if (Q.paquetsParTrame != null) t.push(dqCase('Trames DVB-S2', fr1(Q.paquetsParTrame) + ' paquets/trame',
    Q.tramesEntieres ? 'pertes par trames entières' : (Q.totalCc ? 'pertes éparses' : 'aucune perte'), Q.tramesEntieres ? 'var(--rose)' : ''));
  t.push(dqCase('Décodeurs (par minute)', 'vidéo ' + Math.round(Q.videoMin) + ' · son ' + Math.round(Q.audioMin),
    (Q.imagesS != null ? fr1(Q.imagesS) + ' images/s' : '') + (Q.fluxMin >= 1 ? ' · flux ' + Math.round(Q.fluxMin) : ''),
    Q.videoMin + Q.audioMin > 3 ? 'var(--ambre)' : ''));
  t.push(dqCase('Tables', ['PAT', 'PMT', 'SDT', 'PCR'].map(k => (Q[k.toLowerCase()] ? '✓' : '✗') + k).join(' '),
    'vide ' + Math.round(Q.nulsPct) + ' %' + (Q.debitPcr != null ? ' · flux ' + Math.round(Q.debitPcr / 1000) + ' kb/s (PCR)' : '')));
  const ht = t.join('');
  if (g('q-tuiles').dataset.h !== ht) { g('q-tuiles').dataset.h = ht; g('q-tuiles').innerHTML = ht; }
  // Our own signal received (closed loop): packets sent and received side by side.
  const bo = g('q-boucle');
  const nous = EM && EM.emet && D.verrouille && D.service && EM.indicatif && D.service.trim().toUpperCase() === EM.indicatif;
  if (nous && typeof EM.paquets === 'number') {
    const now = Date.now();
    if (!qEm || EM.paquets < qEm.p) qEm = { t: now, p: EM.paquets };
    else if (now - qEm.t >= 2000 && EM.paquets > qEm.p) { qEmPs = (EM.paquets - qEm.p) * 1000 / (now - qEm.t); qEm = { t: now, p: EM.paquets }; }
    bo.hidden = false;
    txt('q-boucle', 'Boucle sur notre signal : émis ' + (qEmPs == null ? '…' : Math.round(qEmPs)) + ' paquets/s · reçus ' + Math.round(Q.recus) +
      ' paquets/s' + (qEmPs ? ' (' + Math.round(100 * Q.recus / qEmPs) + ' %)' : '') + (EM.tsPerdus ? ' · perdus à l’émission ' + EM.tsPerdus : ''));
  } else { bo.hidden = true; qEm = null; qEmPs = null; }
  const h = Q.histo || {};
  dqCourbe('q-c-mer', h.mer, { ref: Q.seuil, couleur: '#3FE0C8' });
  dqCourbe('q-c-marge', h.marge, { ref: 0, couleur: '#FFB454' });
  dqCourbe('q-c-ratio', h.ratio, { ref: 1, min: 0, max: 1.1, couleur: '#5BD68A' });
  dqCourbe('q-c-cc', h.cc, { ref: 0, min: 0, couleur: '#E5484D' });
  const pids = (Q.pids || []).map(p => '<tr><td>' + p.pid + '</td><td>' + esc(p.role) + (p.pcr ? ' · PCR' : '') + '</td><td>' + Math.round(p.kbps) +
    '</td><td' + (p.cc ? ' style="color:var(--rose)"' : '') + '>' + p.cc + '</td><td>' + p.manquants + '</td></tr>').join('');
  const hp = pids ? '<table><tr><th>PID</th><th>contenu</th><th>kb/s</th><th>err. cont.</th><th>manquants</th></tr>' + pids + '</table>' : '';
  if (g('q-pids').dataset.h !== hp) { g('q-pids').dataset.h = hp; g('q-pids').innerHTML = hp; }
}
// A small curve over the last two minutes; nulls are gaps; ref a dashed line.
function dqCourbe(id, a, o) {
  const c = g(id), x = c.getContext('2d'), w = c.width, hh = c.height;
  x.clearRect(0, 0, w, hh);
  a = a || [];
  const vals = a.filter(v => v != null);
  if (!vals.length) return;
  let lo = o.min != null ? o.min : Math.min.apply(null, vals.concat(o.ref != null ? [o.ref] : []));
  let hi = o.max != null ? Math.max(o.max, Math.max.apply(null, vals)) : Math.max.apply(null, vals.concat(o.ref != null ? [o.ref] : []));
  if (hi - lo < 1e-6) { hi += 1; lo -= o.min != null ? 0 : 1; }
  const y = v => hh - 3 - (v - lo) / (hi - lo) * (hh - 6), n = 120;
  if (o.ref != null) { x.strokeStyle = '#4A5A68'; x.setLineDash([3, 3]); x.beginPath(); x.moveTo(0, y(o.ref)); x.lineTo(w, y(o.ref)); x.stroke(); x.setLineDash([]); }
  x.strokeStyle = o.couleur; x.lineWidth = 1.5; x.beginPath();
  let dedans = false;
  a.forEach((v, i) => {
    const px = w - (a.length - 1 - i) * w / (n - 1);
    if (v == null) { dedans = false; return; }
    if (dedans) x.lineTo(px, y(v)); else x.moveTo(px, y(v));
    dedans = true;
  });
  x.stroke();
  x.fillStyle = '#9AA7B4'; x.font = '10px sans-serif'; x.fillText(fr1(vals[vals.length - 1]), 4, 11);
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

// ------------------------------------------------------------------ DATV: the sound subtitled, the callsigns read in the picture
let DT = null, dtPh = null, dtDernier = 0, dtListe = [], dtQso = 0;
async function ticTextesDatv() {
  if (g('v-datv').hidden) return;
  let j = null;
  try { j = await (await fetch('api/datv/textes')).json(); } catch (x) { return; }
  if (!j || !j.ok) { g('dt-carte').hidden = true; return; }
  DT = j; g('dt-carte').hidden = false;
  if (document.activeElement !== g('dt-st')) g('dt-st').checked = j.soustitres;
  if (document.activeElement !== g('dt-ocr')) g('dt-ocr').checked = j.ocr;
  g('dt-st').disabled = !j.phonie;
  // The overs heard on the DATV sound (the phonie's, from this source only).
  if (j.phonie && (j.soustitres || !dtPh)) {
    let p = null;
    try { p = await (await fetch('api/phonie?depuis=' + dtDernier)).json(); } catch (x) {}
    if (p && p.present) {
      dtPh = p;
      if (p.dernier < dtDernier) { dtDernier = 0; dtListe = []; }
      const nv = p.bulles.filter(b => b.src === 'datv');
      if (p.bulles.length) dtDernier = p.bulles[p.bulles.length - 1].id;
      if (nv.length) { dtListe = dtListe.concat(nv).slice(-60); dtFil(); }
      else if (!g('dt-fil').innerHTML) dtFil();
    }
  }
  const ph = dtPh;
  if (ph && document.activeElement !== g('dt-trad')) g('dt-trad').checked = ph.traduire;
  g('dt-trad').disabled = !j.soustitres;
  const etat = !j.phonie ? 'Phonie → texte absent.' : !j.soustitres ? '' : !j.soustitresActifs ? 'en attente de la réception' :
    !ph ? '' : ({ 'démarrage': 'chargement du modèle…', 'prêt': 'à l’écoute' + (ph.voix ? ' · voix' : ''), 'erreur': 'en panne' }[ph.etat] || ph.etat) +
    (ph.erreur ? ' · ' + ph.erreur : '') + (ph.on && ph.source !== 'datv' ? ' · Phonie → texte écoute le DATV (sa source revient ensuite)' : '');
  txt('dt-st-etat', etat);
  dtSousTitres(j.soustitresActifs);
  // The callsigns read in the picture.
  txt('dt-ocr-etat', !j.ocr ? '' : !j.outil ? 'tesseract introuvable : ' + j.conseil :
    (j.occupe ? 'lecture… · ' : '') + j.lectures + ' lecture(s)' + (j.texte ? ' · lu : « ' + j.texte + ' »' : '') + (j.erreur ? ' · ' + j.erreur : ''));
  if (!j.ocr && !j.outil) txt('dt-ocr-etat', 'tesseract (facultatif) : ' + j.conseil);
  const lus = j.ocr ? j.lus : [];
  g('dt-lus').innerHTML = lus.map((l, i) => '<div class="dt-lu' + (l.accord ? ' sur' : '') + '"' + (i ? ' style="opacity:.7"' : '') + '>' +
    (i ? '' : '<span class="petit">Indicatif lu dans l’image : </span>') + '<b>' + esc(l.c) + '</b> ' + (l.drapeau ? f8Drapeau(l.drapeau) + ' ' : '') +
    '<span class="petit">' + esc(l.pays || '') + ' · lu ' + l.lectures + ' fois de suite' + (l.accord ? ' · <span style="color:var(--vert)">= nom du service, très probable</span>' : '') + '</span> ' +
    '<button onclick="dNote(\'' + esc(l.c) + '\',\'\',\'\')">Noter le QSO</button></div>').join('');
  const b = g('d-lu'), l0 = lus[0];
  b.hidden = !(l0 && D && D.actif);
  if (l0) { b.innerHTML = esc(l0.c) + (l0.drapeau ? ' ' + f8Drapeau(l0.drapeau) : ''); b.className = 'd-lu' + (l0.accord ? ' sur' : ''); }
}
setInterval(() => ticTextesDatv(), 1000);
// Under the picture: the last two overs (a minute at most), big, with their language's flag.
function dtSousTitres(on) {
  const o = g('d-st'), maintenant = Date.now();
  const l = on ? dtListe.filter(b => maintenant - (b.t + b.duree * 1000) < 60000).slice(-2) : [];
  o.hidden = !l.length;
  const trad = g('dt-trad').checked;
  // A long over: its end only, two lines or so each.
  const fin = (t, n) => { if (t.length <= n) return t; const c = t.slice(-n); return '…' + c.slice(c.indexOf(' ') + 1); };
  o.innerHTML = l.map((b, i) => { const ancien = i < l.length - 1, n = ancien ? 90 : 130;
    return '<div' + (ancien ? ' class="vieux"' : '') + '>' + (PH_DRAPEAU[b.langue] ? f8Drapeau(PH_DRAPEAU[b.langue]) + ' ' : '') + esc(fin(b.texte, n)) + '</div>' +
      (trad && b.traduction && b.traduction !== b.texte ? '<div class="d-st-tr' + (ancien ? ' vieux' : '') + '">' + esc(fin(b.traduction, n)) + '</div>' : ''); }).join('');
}
function dtFil() {
  const f = g('dt-fil'), bas = f.scrollTop + f.clientHeight >= f.scrollHeight - 8;
  // The phonie's overs, their chips noting a DATV contact.
  f.innerHTML = dtListe.map(b => phBulle(b).replace(/onclick="phNote\(/g, 'onclick="dNote(')).join('') ||
    '<div class="petit" style="padding:6px">Les passages de voix reçus en DATV s’écriront ici.</div>';
  if (bas) f.scrollTop = f.scrollHeight;
}
async function dtRegle(q) { try { await fetch('api/datv/textes?' + q, { method: 'POST' }); } catch (x) {} setTimeout(ticTextesDatv, 200); }
async function dtTrad(on) { try { await fetch('api/phonie?traduire=' + (on ? 1 : 0), { method: 'POST' }); } catch (x) {} dtPh = null; setTimeout(ticTextesDatv, 200); }
// A DATV contact: the log's form filled, saved in mode DATV on QO-100 or terrestrial (by the frequency received).
function dNote(call, loc, rep) {
  const k = (D && D.ciel) || (DT && DT.ciel) || 0;
  phNote(call, loc, rep);
  dtQso = k;
  const a = g('c-aide');
  if (!a.dataset.avant) a.dataset.avant = a.textContent;
  a.textContent = 'Contact DATV · ' + (DT && DT.qo100 ? 'QO-100 ' : 'terrestre ') + mhz3(k) + ' MHz : enregistré en mode DATV.';
}

// ------------------------------------------------------------------ DATV transmission (ADALM-PLUTO, HackRF One)
let EM = null, eVieW = null, ePremier = true;
async function chargePx() {
  try { const j = await (await fetch('api/datv/emission')).json(); if (!j.ok) { g('px-carte').hidden = true; return; }
    g('px-coupe').checked = !!j.coupeTx; g('px-gainmax').value = j.gainMax; } catch (x) {}
}
// The page says it is there while transmitting: from a worker, which a hidden tab does not slow down.
function eVie(on) {
  if (on && !eVieW) {
    const bat = () => fetch('api/datv/emission/vie', { method: 'POST' }).catch(() => {});
    try {
      eVieW = new Worker(URL.createObjectURL(new Blob(['setInterval(function () { postMessage(0); }, 1000);'], { type: 'text/javascript' })));
      eVieW.onmessage = bat;
    } catch (x) { eVieW = { t: setInterval(bat, 1000), terminate() { clearInterval(this.t); } }; }
    bat();
  } else if (!on && eVieW) { eVieW.terminate(); eVieW = null; }
}
window.addEventListener('pagehide', () => { if (EM && EM.emet) navigator.sendBeacon('api/datv/emission/arrete'); });
async function ticEmission() {
  if (g('v-datv').hidden && !(EM && EM.emet)) return;
  let j; try { j = await (await fetch('api/datv/emission')).json(); } catch (x) { return; }
  if (!j.ok) { g('e-carte').hidden = true; return; }
  EM = j; eVie(j.emet);
  const libre = !document.activeElement || !document.activeElement.id.startsWith('e-');
  if (ePremier || libre) {
    if (ePremier) {
      g('e-modcod').innerHTML = j.modcods.map(m => '<option>' + m + '</option>').join('');
      g('e-sr').innerHTML = (D && D.debits ? D.debits : [125, 250, 333, 500, 1000, 1500, 2000]).map(s => '<option value="' + s + '">' + s + '</option>').join('');
      g('e-ro').innerHTML = j.rolloffs.map(a => '<option value="' + a + '">' + String(a).replace('.', ',') + '</option>').join('');
      const mcs = j.modcods.map(m => '<option>' + m + '</option>').join('');
      g('e-adapt-min').innerHTML = mcs; g('e-adapt-max').innerHTML = mcs;
    }
    g('e-adapt-on').checked = j.adapt.on; g('e-adapt-min').value = j.adapt.min; g('e-adapt-max').value = j.adapt.max;
    g('e-adapt-marge').value = j.adapt.marge;
    g('e-modcod').value = j.modcod; g('e-sr').value = j.sr; g('e-ro').value = j.ro; g('e-courtes').checked = j.courtes; g('e-pilotes').checked = j.pilotes;
    g('e-mhz').value = mhz3(j.khz); g('e-gain').value = j.gain; g('e-duree').value = Math.round(j.duree / 60);
    g('e-source').value = j.source; g('e-image').value = j.image; g('e-video').value = j.video || ''; g('e-hevc').value = j.hevc ? '1' : '0'; g('e-son').checked = j.son;
    const cams = j.cameras.length ? j.cameras : [''];
    g('e-camera').innerHTML = cams.map(c => '<option value="' + esc(c) + '">' + (c ? esc(c) : 'aucune caméra trouvée') + '</option>').join('');
    g('e-camera').value = j.camera || cams[0];
    g('e-emetteur').value = j.emetteur; g('e-vga').value = j.vga; g('e-hackrf-outil').value = j.hackrfOutil; g('e-hackrf-dec').checked = !!j.hackrfDecalage;
    g('e-int-mhz').value = mhz3(j.intKhz); g('e-int-duree').value = Math.round(j.intDuree / 60);
    ePremier = false;
  }
  // The band plan's channels at this symbol rate; the one the MiniTiouner holds now marked busy.
  const occupe = D && D.verrouille && D.cielTrouve ? D.cielTrouve : 0;
  const can = j.canaux.filter(c => c.srMax >= j.sr);
  const html = '<option value="">—</option>' + can.map(c => '<option value="' + mhz3(c.montee) + '">' + mhz3(c.montee) + ' ↑ / ' + mhz3(c.descente) + ' ↓' +
    (occupe && Math.abs(occupe - c.descente) < j.sr / 2 && D.service !== j.indicatif ? ' — occupé (' + esc(D.service || 'signal') + ')' : '') + '</option>').join('');
  if (g('e-canal').dataset.vu !== html) { g('e-canal').dataset.vu = html; g('e-canal').innerHTML = html; }
  g('e-image-l').hidden = j.source !== 'image'; g('e-video-l').hidden = j.source !== 'video'; g('e-camera-l').hidden = j.source !== 'camera';
  txt('e-descente', '↓ ' + mhz3(j.descenteKhz) + ' MHz');
  txt('e-gainmax', 'max ' + j.gainMax + ' dB (Réglages)');
  const charge = g('e-charge').checked;
  txt('e-bande', charge ? '' : j.refusBande);
  // The transmitter: the Pluto, or the HackRF (tests only) with what hackrf_info found.
  const hk = j.emetteur === 'hackrf';
  txt('e-titre', j.emetteurNom); document.querySelectorAll('.e-nom').forEach(x => x.textContent = j.emetteurNom);
  g('e-hackrf-l').hidden = !hk; g('e-hackrf-det').hidden = !hk; g('e-vga-l').hidden = !hk; g('e-gain-l').hidden = hk;
  txt('e-hackrf-info', !hk ? '' : !j.hackrf.lu ? 'non détecté (bouton Détecter)' : (j.hackrf.cartes.length ? j.hackrf.cartes.map(c => c.libelle).join(', ') : j.hackrf.message));
  txt('e-hackrf-prog', j.hackrfProgramme ? 'hackrf_transfer : ' + j.hackrfProgramme : j.hackrfConseil);
  txt('e-hackrf-note', 'QO-100 : ' + j.hackrfPasQo100 + '.');
  const dc = j.decalage;
  g('e-hackrf-dec-info').style.color = dc && dc.refus ? 'var(--rose)' : '';
  txt('e-hackrf-dec-info', !hk || !j.hackrfDecalage ? '' : !dc ? '' : dc.refus ? dc.refus :
    'Essai en intérieur : oscillateur du HackRF sur ' + mhz3(dc.loKhz) + ' MHz, signal ' + (dc.decalageKhz > 0 ? '+' : '') + Math.round(dc.decalageKhz) + ' kHz plus loin');
  txt('e-charge-gain', hk ? 'gain VGA ≤ ' + j.vgaMax + ' dB, amplificateur RF coupé' : 'gain bridé à −40 dB');
  txt('e-int-p', hk ? 'HackRF : amplificateur RF coupé, gain VGA ≤ ' + j.vgaMax + ' dB' : 'ADALM-PLUTO : gain ≤ ' + j.intGainPluto + ' dB');
  txt('e-calc', 'TS ' + (j.debitTs / 1000).toFixed(1).replace('.', ',') + ' kbit/s · ' + Math.round(j.occupeKhz) + ' kHz occupés · ' + (hk ? 'HackRF ' : 'Pluto ') +
    (j.echantillons / 1e6).toFixed(3).replace('.', ',') + ' Méch/s (' + j.sps + ' par symbole)' + (hk ? ' · filtre ' + (j.filtreHackrf / 1e6).toFixed(2).replace('.', ',') + ' MHz' : '') +
    (j.indicatif ? ' · service « ' + j.indicatif + ' »' : ' · indicatif manquant (Réglages)'));
  const p = g('e-pastille');
  p.textContent = j.emet ? (j.charge ? 'SUR CHARGE' : j.interieur ? 'ESSAI INTÉRIEUR' : 'EN ÉMISSION') : 'arrêtée';
  p.style.background = j.emet ? '#C62828' : ''; p.style.color = j.emet ? '#fff' : 'var(--gris)';
  g('e-go').disabled = j.emet; g('e-stop').hidden = !j.emet;
  ['e-modcod', 'e-sr', 'e-ro', 'e-courtes', 'e-pilotes', 'e-canal', 'e-mhz', 'e-source', 'e-image', 'e-video', 'e-camera', 'e-hevc', 'e-son', 'e-duree', 'e-charge',
    'e-emetteur', 'e-interieur', 'e-int-mhz', 'e-int-duree', 'e-vga', 'e-hackrf-outil', 'e-hackrf-det', 'e-hackrf-dec'].forEach(i => g(i).disabled = j.emet);
  // A transmission running (the page reloaded): its mode shown.
  if (j.emet) { g('e-charge').checked = j.charge; g('e-interieur').checked = j.interieur; }
  const dir = g('e-direct');
  dir.hidden = !j.emet;
  if (j.emet) dir.innerHTML = '<b>' + (j.charge ? 'Sur charge' : j.interieur ? 'Essai en intérieur' : 'En émission') + '</b> sur ' + mhz3(j.khzEnCours) + ' MHz · reste <b>' + duree(j.restant * 1000) + '</b>' +
    (j.emetteurEnCours === 'hackrf' ? ' · HackRF, VGA ' + j.gainApplique + ' dB, amplificateur coupé' : ' · gain ' + j.gainApplique + ' dB') + '<br>TS reçu de ffmpeg ' + j.tsKbps + ' kbit/s · trames vides ' + Math.round(j.tramesVidesPct) + ' % · manques ' + j.manques +
    (j.tsPerdus ? ' · paquets perdus ' + j.tsPerdus : '') + (j.ecretages ? ' · écrêtages ' + j.ecretages : '') + ' · CPU ' + (j.cpu >= 0 ? Math.round(j.cpu) + ' %' : '—');
  if (!j.emet && (j.raison || j.erreur)) txt('e-msg', 'Dernière émission : ' + [j.raison, j.erreur].filter(x => x).join(' — '));
  // The closed loop: our signal and the beacon, both measured by the MiniTiouner.
  const b = j.boucle;
  const merNous = D && D.verrouille && D.mer != null ? D.mer.toFixed(1) + ' dB' : '—';
  g('e-boucle').innerHTML = '<b>Boucle MiniTiouner</b> · MER reçue ' + merNous + ' · MER balise ' + (j.merBalise == null ? 'inconnue' : j.merBalise.toFixed(1) + ' dB (il y a ' + duree(j.merBaliseAge * 1000) + ')') +
    (j.emet && !j.charge && !j.interieur ? '<br><span style="color:' + (b.tropFort ? 'var(--rose);font-weight:700' : 'var(--vert)') + '">' + esc(b.message || 'en attente de mesures') + '</span>' : '') +
    (j.charge ? '<br>Sur charge : pas de comparaison à la balise.' : '');
  eAdapt(j);
  eMontre();
}
// The MODCOD fitted to our reception: what is sent, what the MER allows, the next decision, the changes made.
function eAdapt(j) {
  const a = j.adapt, fr1 = x => x.toFixed(1).replace('.', ',');
  let h;
  if (!a.on) h = 'Désactivée : le MODCOD reste celui choisi (' + esc(j.modcod) + ').';
  else if (!j.emet) h = 'Agira pendant la prochaine émission, dès que le MiniTiouner reçoit notre propre signal (service ou carte de visite à l’indicatif ' + esc(j.indicatif || '?') + '). ' +
    'Descend vite (marge &lt; 0,5 dB ou paquets perdus), remonte après 20 s de marge ≥ ' + fr1(a.marge + 1.5) + ' dB ; boucle perdue 10 s : le plus robuste, figé.';
  else {
    const qm = D && D.qualite && D.qualite.marge != null ? fr1(D.qualite.marge) + ' dB' : '—';
    h = 'MODCOD émis <b>' + esc(j.modcodEnCours || j.modcod) + '</b>' + (a.cible ? ' · possible : ' + esc(a.cible) : '') +
      ' · marge ' + (a.margeMesuree != null ? '<b>' + fr1(a.margeMesuree) + ' dB</b>' : '—') + (a.seuil != null ? ' (seuil ' + fr1(a.seuil) + ' dB, cible ' + fr1(a.marge) + ' dB)' : '') +
      ' · panneau qualité ' + qm + '<br>' +
      (a.boucle ? '<span style="color:var(--vert)">● Boucle confirmée</span> — ' : '<span style="color:var(--ambre);font-weight:700">⚠ Boucle non confirmée' + (a.fige ? ' — MODCOD figé' : '') + '</span> — ') +
      esc(a.prochaine || 'en attente de mesures');
    if (a.suggestion) h += '<br><span style="color:var(--ambre)">💡 ' + esc(a.suggestion) + '</span>';
    if (a.erreur) h += '<br><span style="color:var(--rose)">Changement impossible : ' + esc(a.erreur) + '</span>';
  }
  if (g('e-adapt-etat').dataset.h !== h) { g('e-adapt-etat').dataset.h = h; g('e-adapt-etat').innerHTML = h; }
  const l = (a.historique || []).slice(-8).reverse();
  const hh = l.length ? '<b>Changements</b><br>' + l.map(c => new Date(c.t).toLocaleTimeString('fr-FR') + ' · ' + esc(c.de) + ' → <b>' + esc(c.vers) + '</b> — ' + esc(c.raison)).join('<br>') : '';
  if (g('e-adapt-histo').dataset.h !== hh) { g('e-adapt-histo').dataset.h = hh; g('e-adapt-histo').innerHTML = hh; }
}
async function eRegleAdapt() {
  const q = 'adapt=' + (g('e-adapt-on').checked ? 1 : 0) + '&adaptMin=' + enc(v('e-adapt-min')) + '&adaptMax=' + enc(v('e-adapt-max')) + '&adaptMarge=' + enc(v('e-adapt-marge'));
  try { await fetch('api/datv/emission/reglage?' + q, { method: 'POST' }); } catch (x) {}
  setTimeout(ticEmission, 100);
}
setInterval(ticEmission, 1000);
// The test's mode: QO-100 (the Pluto only), the dummy load, or the indoor test; one at a time.
function eMode(id) { if (g(id).checked) ['e-charge', 'e-interieur'].forEach(i => { if (i !== id) g(i).checked = false; }); eMontre(); }
function eMontre() {
  const charge = g('e-charge').checked, inter = g('e-interieur').checked, hk = EM && EM.emetteur === 'hackrf';
  g('e-charge-avert').hidden = !charge; g('e-int-l').hidden = !inter;
  g('e-canal-l').hidden = inter; g('e-mhz-l').hidden = inter; g('e-duree-l').hidden = inter;
  g('e-boucle').hidden = inter || hk;
  if (!EM) return;
  txt('e-bande', charge || inter ? '' : hk ? 'Avec le HackRF : cocher « Essai en intérieur » ou « Test sur charge ».' : EM.refusBande);
  txt('e-int-refus', inter ? EM.intRefus : ''); txt('e-int-avert', inter ? EM.intAvert : '');
  // What the MiniTiouner needs to receive this test: no LNB, so no oscillator and no supply.
  const rx = g('e-rx');
  rx.hidden = !(charge || inter);
  if (rx.hidden) return;
  const k = inter ? EM.intKhz : EM.khz;
  const html = k < 144000 || k > 2450000 ? 'Réception : ' + mhz3(k) + ' MHz est hors de la plage du MiniTiouner (144 à 2450 MHz).'
    : '<b>Réception MiniTiouner</b> : fréquence <b>' + mhz3(k) + ' MHz</b> (ciel = entrée), OL du LNB <b>0</b>, <b>' + EM.sr + ' kS/s</b>, alim. LNB <b>coupée</b> ' +
      '<button onclick="eRxRegle(' + k + ',' + EM.sr + ')">Régler la réception</button><br>' +
      (inter ? 'Une antenne sur l’entrée du MiniTiouner : alimentation LNB COUPÉE, sinon ses 13/18 V partent dans l’antenne.'
        : 'Sur charge : l’atténuateur vers l’entrée du MiniTiouner, alimentation LNB coupée.');
  if (rx.dataset.vu !== html) { rx.dataset.vu = html; rx.innerHTML = html; }
}
// The DATV receiver set for the test (not started): the frequency as is, no oscillator, no LNB supply.
function eRxRegle(k, sr) {
  g('d-ciel').value = mhz3(k); g('d-ol').value = '0,000'; g('d-lnb').value = ''; dLnb(); dSr = sr;
  txt('d-msg', 'réception réglée pour l’essai (OL 0, alimentation LNB coupée) : « Recevoir » pour démarrer');
  ticDatv();
}
async function eDetecte() {
  txt('e-hackrf-info', 'lecture (hackrf_info)…');
  try { await fetch('api/datv/emission/hackrf', { method: 'POST' }); } catch (x) {}
  setTimeout(ticEmission, 100);
}
function eParams() {
  return 'modcod=' + enc(v('e-modcod')) + '&sr=' + v('e-sr') + '&ro=' + v('e-ro') + '&courtes=' + (g('e-courtes').checked ? 1 : 0) + '&pilotes=' + (g('e-pilotes').checked ? 1 : 0) +
    '&mhz=' + enc(v('e-mhz').replace(',', '.')) + '&gain=' + enc(v('e-gain')) + '&duree=' + enc(v('e-duree')) + '&source=' + v('e-source') +
    '&image=' + enc(v('e-image')) + '&video=' + enc(v('e-video')) + '&camera=' + enc(g('e-camera').value) + '&hevc=' + v('e-hevc') + '&son=' + (g('e-son').checked ? 1 : 0) +
    '&emetteur=' + v('e-emetteur') + '&vga=' + enc(v('e-vga')) + '&hackrfOutil=' + enc(v('e-hackrf-outil')) + '&hackrfDecalage=' + (g('e-hackrf-dec').checked ? 1 : 0) +
    '&intMhz=' + enc(v('e-int-mhz').replace(',', '.')) + '&intDuree=' + enc(v('e-int-duree'));
}
async function eRegle() {
  try { await fetch('api/datv/emission/reglage?' + eParams(), { method: 'POST' }); } catch (x) {}
  setTimeout(ticEmission, 100);
}
async function eGain(d) { g('e-gain').value = Math.min(0, (parseFloat(v('e-gain')) || -30) + d); await eRegle(); }
async function eEmettre() {
  await eRegle();
  if (!EM) return;
  const charge = g('e-charge').checked, inter = g('e-interieur').checked, hk = EM.emetteur === 'hackrf';
  const gainTxt = hk ? 'gain VGA ' + Math.max(0, Math.min(parseInt(v('e-vga')) || 0, EM.vgaMax)) + ' dB, amplificateur RF coupé'
    : 'gain ' + Math.min(parseFloat(v('e-gain')), charge ? EM.gainCharge : EM.intGainPluto) + ' dB au plus';
  const q = inter ? 'ESSAI EN INTÉRIEUR avec le ' + EM.emetteurNom + ' à ' + v('e-int-mhz') + ' MHz, ' + v('e-sr') + ' kS/s, ' + gainTxt + ', ' + v('e-int-duree') + ' min au plus.\n\n' +
      'Indicatif ' + EM.indicatif + '. Radioamateur titulaire d’une licence, petite antenne à l’intérieur, aucun amplificateur ?'
    : charge ? 'TEST SUR CHARGE à ' + v('e-mhz') + ' MHz, ' + gainTxt + '.\n\nLa sortie TX du ' + (hk ? 'HackRF' : 'Pluto') + ' est-elle bien sur une charge fictive ou un atténuateur, et rien d’autre ?'
    : 'ÉMETTRE vers QO-100 à ' + v('e-mhz') + ' MHz (descente ' + mhz3(EM.descenteKhz) + ' MHz), ' + v('e-sr') + ' kS/s, ' + v('e-modcod') + ', gain ' + v('e-gain') + ' dB, ' + v('e-duree') + ' min au plus.\n\nCanal libre vérifié ? Indicatif ' + EM.indicatif + '.';
  if (!confirm(q)) return;
  txt('e-msg', 'démarrage : ffmpeg, puis ' + (hk ? 'hackrf_transfer…' : 'l’ADALM-PLUTO…'));
  let j = null;
  try { j = await (await fetch('api/datv/emission/demarre?confirme=1&charge=' + (charge ? 1 : 0) + '&interieur=' + (inter ? 1 : 0), { method: 'POST' })).json(); } catch (x) {}
  txt('e-msg', j && j.ok === false ? 'Refusé : ' + j.erreur : (j ? 'émission en cours' : 'pas de réponse de SatMe-PC'));
  if (j && j.ok !== false) eVie(true);
  setTimeout(ticEmission, 300);
}
async function eArrete() {
  try { await fetch('api/datv/emission/arrete', { method: 'POST' }); } catch (x) {}
  txt('e-msg', 'arrêtée'); setTimeout(ticEmission, 200);
}

// ------------------------------------------------------------------ DATV station card and chat (in the transport stream)
let eCartePremier = true;
function dcBulle(c, texte, t, moi, recu) {
  return '<div class="dc-bulle' + (moi ? ' moi' : '') + '"><span class="petit">' + hm(t) + '</span><b>' + esc(c) + '</b> ' + esc(texte) +
    (recu === undefined ? '' : recu ? ' <span style="color:var(--vert)" title="Reçu par le MiniTiouner">✓✓ reçu</span>' : ' <span class="petit" title="Envoyé, pas encore revu à la réception">✓</span>') + '</div>';
}
function ticCarteDatv() {
  if (g('v-datv').hidden) return;
  const k = D && D.carte, moi = EM ? EM.indicatif : '';
  const boite = g('dc-carte');
  boite.hidden = !(k && (k.recue || k.chat.length));
  if (k && !boite.hidden) {
    txt('dc-drapeau', k.drapeau ? f8Drapeau(k.drapeau) : '');
    txt('dc-call', k.indicatif || (k.chat.length ? k.chat[k.chat.length - 1].c : ''));
    const connu = g('dc-connu'); connu.hidden = !k.contacts; txt('dc-connu', k.contacts ? '✓ dans le carnet (' + k.contacts + ')' : '');
    txt('dc-age', k.recue ? (k.indicatif === moi ? 'notre propre signal · ' : '') + 'carte reçue il y a ' + duree(k.age * 1000) : '');
    g('dc-info').innerHTML = [k.pays ? esc(k.pays) : '', k.nom ? esc(k.nom) : '', k.qth ? esc(k.qth) : '', k.locator ? '<b>' + esc(k.locator) + '</b>' : '',
      k.km != null ? k.km.toLocaleString('fr-FR') + ' km · ' + k.azimut + '° ' + esc(k.rose) : ''].filter(x => x).join(' · ');
    txt('dc-tech', [k.khz ? mhz3(k.khz) + ' MHz' : '', k.modcod ? k.modcod + (k.sr ? ' · ' + k.sr + ' kS/s' : '') : '', k.puissance, k.antenne, k.logiciel].filter(x => x).join(' · '));
    const m = g('dc-message'); m.hidden = !k.message; txt('dc-message', k.message ? '« ' + k.message + ' »' : '');
    const html = k.chat.map(l => dcBulle(l.c, l.texte, l.t, l.c === moi)).join('');
    const f = g('dc-chat');
    if (f.dataset.vu !== html) { f.dataset.vu = html; f.innerHTML = html; f.scrollTop = f.scrollHeight; }
  }
  if (!EM) return;
  // Transmitting: the chat rides on it; our lines ticked once the MiniTiouner sees them come back.
  g('e-chat-l').hidden = !EM.emet;
  const vus = new Set(k ? k.chat.filter(l => l.c === EM.indicatif).map(l => l.seq) : []);
  const fil = EM.chat.map(d => dcBulle(EM.indicatif, d.texte, d.t, true, vus.has(d.seq))).join('');
  const ef = g('e-chat-fil');
  if (ef.dataset.vu !== fil) { ef.dataset.vu = fil; ef.innerHTML = fil; ef.scrollTop = ef.scrollHeight; }
  const libre = !document.activeElement || !['e-qth', 'e-puissance', 'e-antenne', 'e-message'].includes(document.activeElement.id);
  if (eCartePremier || libre) {
    g('e-carte-on').checked = EM.carteOn; g('e-qth').value = EM.qth; g('e-puissance').value = EM.puissance; g('e-antenne').value = EM.antenne; g('e-message').value = EM.message;
    eCartePremier = false;
  }
}
setInterval(ticCarteDatv, 1000);
async function eRegleCarte() {
  await post('api/datv/emission/reglage?carte=' + (g('e-carte-on').checked ? 1 : 0) + '&qth=' + enc(v('e-qth')) + '&puissance=' + enc(v('e-puissance')) +
    '&antenne=' + enc(v('e-antenne')) + '&message=' + enc(v('e-message')));
}
async function eChat() {
  const t = v('e-chat').trim(); if (!t) return;
  let j = null;
  try { j = await (await fetch('api/datv/emission/chat?texte=' + enc(t), { method: 'POST' })).json(); } catch (x) {}
  if (j && j.ok !== false) g('e-chat').value = '';
  else txt('e-msg', j && j.erreur ? j.erreur : 'pas de réponse de SatMe-PC');
  setTimeout(ticEmission, 200);
}
async function dcNote() {
  const k = D && D.carte; if (!k || !k.indicatif) { txt('dc-msg', 'aucune carte reçue'); return; }
  // QO-100: the downlink where the MiniTiouner holds it; terrestrial: the frequency received.
  const khz = D.verrouille && D.cielTrouve ? D.cielTrouve : D.ciel;
  const mer = D.verrouille && D.mer != null ? D.mer.toFixed(1) + 'dB' : '';
  const j = await post('api/datv/qso?c=' + enc(k.indicatif) + '&l=' + enc(k.locator) + '&rs=' + enc(mer) + '&qo100=' + (k.qo100 ? 1 : 0) + '&khz=' + khz +
    '&nm=' + enc(k.nom) + '&qth=' + enc(k.qth) + '&n=' + enc('DATV' + (k.modcod ? ' ' + k.modcod : '') + (mer ? ', MER ' + mer : '')));
  txt('dc-msg', j && j.ok ? k.indicatif + ' noté au carnet (' + (k.qo100 ? 'QO-100' : 'DATV terrestre') + ')' : (j && j.erreur ? j.erreur : 'pas de réponse'));
}

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
  if (!(fluxOk && S)) { try { S = await (await fetch('api/son')).json(); } catch (x) { return; } }
  rendSon();
}
function rendSon() {
  if (!S || !S.present) return;
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
  etatMarche('son', S.actif, 's-etat');
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
  rendSstv();
  tqSstv();
  txt('sstv-etat', !S.actif ? (window.SATME_TELEPHONE ? 'Le téléphone décode en enregistrant : « Enregistrer » (onglet Son).' : '') : !S.sstv ? 'Décodage SSTV coupé.' : S.sstvMode ? 'réception…' : S.sstvDerniere ? 'dernière image : ' + S.sstvDerniere : '');
  if (ouverte('sstv') && S.sstvVersion !== versionImage) {
    versionImage = S.sstvVersion;
    if (S.sstvMode) g('i-encours').src = 'api/sstv/encours.png?v=' + S.sstvVersion;
    else if (S.sstvDerniere) g('i-encours').src = 'sstv/' + enc(S.sstvDerniere);
  }
  // The gallery, by satellite when one is chosen.
  const sats = JSON.stringify([...new Set(S.images.map(i => i.sat).filter(x => x))].sort());
  if (sats !== satsVus) {
    satsVus = sats;
    const f = g('i-filtre').value;
    g('i-filtre').innerHTML = '<option value="">tous les satellites</option>' + JSON.parse(sats).map(x => '<option>' + esc(x) + '</option>').join('');
    g('i-filtre').value = f;
  }
  const filtre = g('i-filtre').value;
  imagesMontrees = S.images.filter(i => !filtre || i.sat === filtre);
  txt('i-nombre', String(imagesMontrees.length));
  const imgs = JSON.stringify(imagesMontrees.map(i => i.nom + (i.gif ? 'g' : '')));
  if (imgs !== imagesVus && ouverte('sstv')) {
    imagesVus = imgs;
    g('i-galerie').innerHTML = imagesMontrees.map((i, n) => '<a href="sstv/' + enc(i.nom) + '" onclick="voisImage(' + n + '); return false" style="text-decoration:none;color:inherit">' +
      '<img src="sstv/' + enc(i.nom) + '" style="width:100%;border-radius:6px" loading="lazy"><div class="petit">' + esc(i.sat) + ' · ' + esc(i.mode) +
      ' · ' + (i.t ? new Date(i.t).toISOString().slice(0, 16).replace('T', ' ') : '') + (i.complete ? '' : ' · partielle') +
      (i.redecode ? ' · redécodée' : '') + (i.son ? ' · ♪' : '') + '</div></a>').join('') ||
      '<div class="petit">aucune image</div>';
  }
  if (g('visionneuse').open) voisOutils();
}
let satsVus = '', imagesMontrees = [], modesSstvVus = '';
// The decoder in detail: what it hears, from where, its settings, the work under way.
function rendSstv() {
  const D = S.sstvDetail;
  g('sstv-source').hidden = g('sstv-source-l').hidden = !D || !D.source;
  if (document.activeElement !== g('sstv-actif')) g('sstv-actif').checked = S.sstv;
  if (!D) { txt('sstv-statut', ''); return; }
  if (D.modes && JSON.stringify(D.modes) !== modesSstvVus) {
    modesSstvVus = JSON.stringify(D.modes);
    g('sstv-force').innerHTML = '<option value="">Auto (VIS)</option>' + D.modes.map(m => '<option>' + esc(m) + '</option>').join('');
  }
  if (document.activeElement !== g('sstv-force')) g('sstv-force').value = D.force || '';
  if (D.source && document.activeElement !== g('sstv-source')) g('sstv-source').value = D.source;
  if (document.activeElement !== g('sstv-continu')) g('sstv-continu').checked = D.continu;
  if (document.activeElement !== g('sstv-nettoyage')) g('sstv-nettoyage').checked = D.nettoyage;
  g('sstv-caler').hidden = D.decodage; g('sstv-abandon').hidden = !D.decodage;
  g('sstv-caler').disabled = !D.force;
  g('sstv-caler').title = D.force ? 'Commencer l\'image tout de suite en ' + D.force + ', sans attendre l\'en-tête' : 'Choisir d\'abord un mode';
  const ecoute = D.sourceActive !== '';
  const st = !S.sstv ? 'Décodage coupé'
    : D.decodage ? 'Réception ' + (S.sstvMode || '') + (S.sstvHauteur ? ' · ' + S.sstvLignes + '/' + S.sstvHauteur + ' lignes' : '') + ' · ' + Math.round(D.progres * 100) + ' %'
    : ecoute ? 'À l’écoute' + (D.force ? ' · mode imposé ' + D.force : D.continu ? ' · en-tête VIS ou tops de synchro' : ' · en attente de l’en-tête VIS')
    : 'Pas de son';
  txt('sstv-statut', st + (D.gardees ? ' · ' + D.gardees + ' image' + (D.gardees > 1 ? 's' : '') + ' gardée' + (D.gardees > 1 ? 's' : '') : ''));
  g('sstv-statut').style.color = D.decodage ? 'var(--vert)' : ecoute ? 'var(--cyan)' : 'var(--ambre)';
  const conseil = [D.conseil, D.erreur].filter(x => x).join(' · ');
  g('sstv-conseil').hidden = !conseil; txt('sstv-conseil', conseil);
  txt('sstv-source-active', D.sourceActive);
  g('sstv-niveau').style.width = Math.round(Math.min(1, D.niveau) * 100) + '%';
  g('sstv-niveau').style.background = D.niveau > 0.95 ? 'var(--rose)' : D.niveau < 0.003 ? 'var(--ambre)' : 'var(--vert)';
  g('sstv-barre').style.width = (D.decodage ? Math.round(D.progres * 100) : 0) + '%';
  const e = [];
  if (D.ecartSyncHz != null) e.push('synchro vue à ' + (1200 + D.ecartSyncHz) + ' Hz (' + (D.ecartSyncHz > 0 ? '+' : '') + D.ecartSyncHz + ' Hz)');
  if (D.ecartHz != null) e.push('écart d’accord corrigé par le décodeur : ' + (D.ecartHz > 0 ? '+' : '') + D.ecartHz + ' Hz');
  txt('sstv-ecart', e.join(' · ') || (ecoute ? 'Accord : la synchro doit tomber sur le trait 1200 Hz (au-delà de ±250 Hz l’en-tête n’est plus reconnu).' : ''));
  if (ouverte('sstv')) spectreSstv(D);
  // Decoding a recording again.
  g('sstv-redec-carte').hidden = !D.peutRedecoder;
  const en = JSON.stringify(S.enregistrements.map(x => x.nom));
  if (en !== enregsSstvVus) {
    enregsSstvVus = en;
    const avant = g('sstv-enreg').value;
    g('sstv-enreg').innerHTML = S.enregistrements.map(x => '<option value="' + esc(x.nom).replace(/"/g, '&quot;') + '">' + esc(x.nom) + ' · ' + (x.taille / 1048576).toFixed(1) + ' Mo</option>').join('') || '<option value="">aucun enregistrement</option>';
    if (avant) g('sstv-enreg').value = avant;
  }
  const enCours = D.redecodeProgres >= 0;
  g('sstv-redec').hidden = enCours; g('sstv-annule').hidden = !enCours;
  g('sstv-redec-barre').style.width = (enCours ? Math.round(D.redecodeProgres * 100) : 0) + '%';
  txt('sstv-redec-etat', enCours ? D.redecode + ' · ' + Math.round(D.redecodeProgres * 100) + ' % · ' + D.redecodeImages + ' image(s)'
    : D.redecode ? D.redecode + ' : ' + D.redecodeImages + ' image(s) trouvée(s)' : '');
}
let enregsSstvVus = '';
// The sound from 900 to 2600 Hz, the SSTV tones marked: the sync line shows where the receiver is tuned.
function spectreSstv(D) {
  const c = g('sstv-spectre'), x = c.getContext('2d'), w = c.width, h = c.height;
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  const bas = D.spectreBas || 900, pas = D.spectrePas || 20, n = D.spectre ? D.spectre.length : Math.round((2600 - bas) / pas) + 1;
  const px = f => (f - bas) / (pas * (n - 1)) * w;
  [[1200, 'synchro'], [1500, 'noir'], [1900, 'VIS'], [2300, 'blanc']].forEach(([f, l]) => {
    x.strokeStyle = f === 1200 ? '#FFB454' : '#22303B'; x.lineWidth = 1; x.beginPath(); x.moveTo(px(f), 0); x.lineTo(px(f), h); x.stroke();
    x.fillStyle = '#9AA7B4'; x.font = '10px sans-serif'; x.fillText(f + ' ' + l, px(f) + 2, 10);
  });
  if (!D.spectre) return;
  x.strokeStyle = '#3FE0C8'; x.lineWidth = 1.5; x.beginPath();
  D.spectre.forEach((db, i) => { const y = h - Math.max(0, (db + 50) / 50) * (h - 12); i ? x.lineTo(i / (n - 1) * w, y) : x.moveTo(0, y); });
  x.stroke();
}
// A picture opened over the page, not in a tab: the arrows go through the gallery, Escape closes.
let viN = 0;
function voisImage(n) {
  const l = imagesMontrees.length ? imagesMontrees : ((S && S.images) || []); if (!l.length) return;
  viN = (n + l.length) % l.length; const i = l[viN];
  g('vi-img').src = 'sstv/' + enc(i.nom); g('vi-lien').href = 'sstv/' + enc(i.nom); g('vi-lien').download = i.nom;
  txt('vi-txt', (viN + 1) + '/' + l.length + ' · ' + [i.sat, i.mode, i.t ? new Date(i.t).toISOString().slice(0, 16).replace('T', ' ') + ' UTC' : '', i.complete ? '' : 'partielle',
    i.redecode ? 'redécodée' + (i.enregistrement ? ' de ' + i.enregistrement : '') : ''].filter(x => x).join(' · '));
  viNom = i.nom; viSonVu = '';
  voisOutils();
  if (!g('visionneuse').open) g('visionneuse').showModal();
}
let viNom = '', viSonVu = '';
// The picture's own sound, its GIF, deleting it: as the phone's viewer.
function voisOutils() {
  const i = ((S && S.images) || []).find(x => x.nom === viNom);
  const D = S && S.sstvDetail;
  g('vi-outils').hidden = !i || !D;
  if (!i || !D) return;
  const wav = viNom.replace(/\.png$/, '.wav'), gif = viNom.replace(/\.png$/, '.gif');
  g('vi-son').hidden = g('vi-redec').hidden = !i.son;
  if (i.son && viSonVu !== wav) { viSonVu = wav; g('vi-son').src = 'sstv/' + enc(wav); }
  g('vi-redec').disabled = D.redecodeProgres >= 0 || !D.peutRedecoder;
  g('vi-gif').hidden = !D.peutGif; g('vi-gif').disabled = !!D.gif;
  txt('vi-gif', D.gif === viNom ? '🎞 GIF en cours…' : i.gif ? '🎞 Refaire le GIF' : '🎞 GIF');
  g('vi-gif-lien').hidden = !i.gif; g('vi-gif-lien').href = 'sstv/' + enc(gif); g('vi-gif-lien').download = gif;
  txt('vi-sans-son', i.son ? '' : 'Le son de cette image n’est pas gardé (images reçues avant que SatMe ne le garde).');
}
async function viAction(a) { if (viNom) await post('api/sstv/' + a + enc(viNom)); }
async function viSupprime() {
  if (!viNom || !confirm('Supprimer ' + viNom + ' (avec son son et son GIF) ?')) return;
  const j = await post('api/sstv/supprime?nom=' + enc(viNom));
  if (j && j.ok !== false) { g('visionneuse').close(); imagesVus = ''; }
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
  x.font = 'bold ' + Math.max(9, Math.min(14, pasCarre / 4)) + 'px Drapeaux,system-ui'; x.textAlign = 'center';
  Q.carres.forEach(q => { const a = 0.25 + 0.5 * Math.log(1 + q.n) / Math.log(1 + max);
    const [xa, ya, xb, yb] = carre(q.c, 'rgba(63,224,200,' + a.toFixed(2) + ')', 'rgba(20,120,110,.9)', 1);
    if (pasCarre > 28) { x.fillStyle = '#06131A'; x.fillText(q.c, (xa + xb) / 2, (ya + yb) / 2 + 4); } });
  if (Q.mon && Q.mon.length === 4) { carre(Q.mon, 'rgba(255,180,84,.45)', '#FFB454', 2.5);
    if (Q.lat != null) { x.fillStyle = '#1a1f2a'; x.beginPath(); x.arc(px(Q.lon), py(Q.lat), 5, 0, 7); x.fill(); } }
  if (pasCarre <= 14) { x.fillStyle = 'rgba(20,30,45,.7)'; x.font = '11px Drapeaux,system-ui';
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
  x.font = '11px Drapeaux,system-ui'; x.fillStyle = '#9AA7B4'; x.textAlign = 'center';
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
    x.textAlign = 'left'; x.font = (E && E.suivi && E.suivi.catnum === l.catnum ? 'bold ' : '') + '13px Drapeaux,system-ui';
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
      x.fillStyle = '#E8F0F7'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
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
  x.font = 'bold 12px Drapeaux,system-ui'; x.textAlign = 'left'; x.lineWidth = 3; x.strokeStyle = 'rgba(255,255,255,.85)';
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
// The beacon followed: its quality, the LNB's error over the last hour, the stations heard.
let OQ = null, oqVu = '';
async function ticQualite() {
  if (g('v-qo100').hidden) return;
  try { OQ = await (await fetch('api/qo100/qualite')).json(); } catch (x) { OQ = null; }
  const q = OQ, ok = q && !q.absent;
  g('oq-auto-l').hidden = !ok; g('oq-carte').hidden = !ok || !q.cle; g('oq-sig-carte').hidden = !ok || !q.cle;
  g('o-leila').hidden = !(ok && q.leila);
  if (ok && q.leila) g('o-leila').title = 'La sirène LEILA de la station sol, entendue il y a ' + q.leilaIlYa + ' s (vers ' + q.leilaHz + ' Hz dans le son) : la station écoutée émet plus fort que les balises et doit baisser sa puissance.';
  if (!ok) return;
  if (document.activeElement !== g('oq-auto')) g('oq-auto').checked = q.auto;
  etatMarche('oq', q.verrou, 'oq-verrou'); txt('oq-verrou', q.verrou ? (q.auto ? 'calée sur la balise' : 'balise suivie') : 'balise non vue');
  const sg = (v, u) => v == null ? '—' : (v > 0 ? '+' : '') + v + (u || '');
  txt('oq-snr', q.rapport != null ? q.rapport.toFixed(1) : '—');
  txt('oq-ecart', sg(q.ecart, ' Hz'));
  txt('oq-derive', q.derive != null ? sg(q.derive, ' Hz/min') : '…');
  txt('oq-erreur', q.erreur != null ? sg(q.erreur, ' Hz') + ' · ' + q.ppm.toFixed(3) + ' ppm' : '—');
  txt('oq-plancher', q.plancher != null ? q.plancher + ' dB' : '—');
  txt('oq-occ', q.signaux ? q.signaux.length : '—');
  // The LNB's error over the hour.
  // The day (a point a minute) once there is more than an hour of it, else the last hour.
  const jour = (q.journee || []).length > 60, l = jour ? q.journee : (q.historique || []);
  const c = g('oq-histo'), [x, w, h] = toile(c);
  txt('oq-histo-t', (jour ? 'L’erreur du LNB depuis ' + Math.round((l[l.length - 1][0] - l[0][0]) / 3600000) + ' h (un point par minute)' : 'L’erreur du LNB sur la dernière heure') +
    (q.ecartees ? ' · ' + q.ecartees + ' mesure(s) écartée(s) : une autre raie que la balise' : '') + '.');
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  if (l.length > 1) {
    const t0 = l[0][0], t1 = Math.max(l[l.length - 1][0], t0 + 1);
    let lo = Math.min(...l.map(p => p[1])), hi = Math.max(...l.map(p => p[1])); if (hi - lo < 40) { const m = (hi + lo) / 2; lo = m - 20; hi = m + 20; }
    x.strokeStyle = '#3FE0C8'; x.lineWidth = 1.5; x.beginPath();
    l.forEach((p, i) => { const px = (p[0] - t0) / (t1 - t0) * (w - 4) + 2, py = h - 4 - (p[1] - lo) / (hi - lo) * (h - 8); i ? x.lineTo(px, py) : x.moveTo(px, py); }); x.stroke();
    x.fillStyle = '#9AA7B4'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'left';
    x.fillText(Math.round(hi) + ' Hz', 4, 10); x.fillText(Math.round(lo) + ' Hz', 4, h - 4);
    x.textAlign = 'right'; x.fillText((t1 - t0) > 7200000 ? Math.round((t1 - t0) / 3600000) + ' h' : Math.round((t1 - t0) / 60000) + ' min', w - 4, 10);
  }
  const cle = JSON.stringify(q.signaux);
  if (cle === oqVu) return;
  oqVu = cle;
  const seg = s => s ? (OP && OP.segments ? ((OP.segments.find(x => x.cle === s) || {}).nom || s) : s) : '';
  txt('oq-sig-n', q.signaux && q.signaux.length ? '· ' + q.signaux.length : '');
  g('oq-signaux').innerHTML = (q.signaux || []).map(s => '<tr style="cursor:pointer" onclick="qPost(\'frequence?hz=' + s.hz + '\')"><td class="val" style="font-size:15px">' +
    (s.hz / 1e6).toFixed(4).replace('.', ',') + '</td><td><div class="r-barre" style="width:80px"><i style="width:' + Math.min(100, s.snr * 3) + '%"></i></div></td><td class="petit">' +
    s.snr + ' dB</td><td class="petit">' + (s.largeur / 1000).toFixed(1) + ' kHz</td><td class="petit">' + esc(seg(s.seg)) + '</td></tr>').join('') ||
    '<tr><td class="petit">rien au-dessus du bruit</td></tr>';
}
setInterval(ticQualite, 2000);
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
let OPan = null, oPanHist = [], oPanTrames = -1, oPanZoom = '';
async function ticPano() {
  if (g('v-qo100').hidden) return;
  const fz = tqFenetre(), zq = fz && !fz.plein ? '&bas=' + fz.b + '&haut=' + fz.t : '';
  if (zq !== oPanZoom) { oPanZoom = zq; oPanHist = []; }
  try { OPan = await (await fetch('api/qo100/panorama?n=' + Math.max(128, Math.min(1024, g('o-pano').clientWidth)) + zq)).json(); } catch (x) { return; }
  if (zq !== oPanZoom) return;
  if (OPan.colonnes && OPan.trames !== oPanTrames) {
    oPanTrames = OPan.trames;
    if (oPanHist.length && oPanHist[0].length !== OPan.colonnes.length) oPanHist = [];
    oPanHist.unshift(OPan.colonnes); if (oPanHist.length > 200) oPanHist.length = 200;
  }
  const b = OPan.balise;
  g('o-balise').innerHTML = !OPan.sdr ? 'Le récepteur ne tourne pas : « Démarrer la réception QO-100 », en haut.'
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
  txt('o-pano-msg', oPanHist.length ? '' : (OPan && OPan.sdr ? 'La cascade arrive…' : 'La cascade du transpondeur : « Démarrer la réception QO-100 », en haut, et elle se remplit ici.'));
  if (!oPanHist.length) return;
  // The scale: at least 20 dB, or plain noise would paint as a busy transponder.
  let lo = 1e9, hi = -1e9;
  for (const r of oPanHist) for (const v of r) if (v != null) { if (v < lo) lo = v; if (v > hi) hi = v; }
  if (hi < lo + 20) hi = lo + 20;
  // Two pixels a row: the waterfall fills twice as fast.
  const n = oPanHist[0].length, rows = Math.min(oPanHist.length * 2, h), img = x.createImageData(Math.max(1, Math.round((w - 8) * dpr)), Math.max(1, Math.round(rows * dpr)));
  const W = img.width, H = img.height;
  for (let yy = 0; yy < H; yy++) {
    const r = oPanHist[Math.min(oPanHist.length - 1, Math.floor(yy / dpr / 2))];
    for (let xx = 0; xx < W; xx++) {
      const v = r[Math.min(n - 1, Math.floor(xx * n / W))];
      const k = 4 * (yy * W + xx);
      const [cr, cg, cb] = v == null ? [40, 40, 44] : rCouleur(v, lo, hi);
      img.data[k] = cr; img.data[k + 1] = cg; img.data[k + 2] = cb; img.data[k + 3] = 255;
    }
  }
  x.setTransform(1, 0, 0, 1, 0, 0); x.putImageData(img, Math.round(4 * dpr), 0); x.setTransform(dpr, 0, 0, dpr, 0, 0);
  // The beacons, the frequency listened to, the signals named.
  const fz = tqFenetre(), b = fz.b, t = fz.t, X = f => 4 + (w - 8) * (f - b) / (t - b);
  x.strokeStyle = 'rgba(229,72,229,.8)'; x.setLineDash([3, 3]);
  for (const s of OP.segments) if (s.usage === 'BALISE' && s.repere && s.repere >= b && s.repere <= t) { x.beginPath(); x.moveTo(X(s.repere), 0); x.lineTo(X(s.repere), h); x.stroke(); }
  x.setLineDash([]); x.strokeStyle = '#FFFFFF'; x.lineWidth = 1.5; x.beginPath(); x.moveTo(X(O.descente), 0); x.lineTo(X(O.descente), 10); x.stroke(); x.lineWidth = 1;
  // The scale, kHz above 10 489 MHz.
  const pas = t - b > 200000 ? 50000 : t - b > 60000 ? 10000 : 5000;
  x.fillStyle = 'rgba(232,240,247,.75)'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
  for (let f = Math.ceil(b / pas) * pas; f <= t; f += pas) if (X(f) > 14 && X(f) < w - 14) x.fillText(String((f - 10489000000) / 1000), X(f), h - 4);
  tqEtiquettes(x, w, X, b, t);
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
    if (X(s.haut) - X(s.bas) > 44) { x.fillStyle = '#06131A'; x.font = '600 10px Drapeaux,system-ui'; x.textAlign = 'center'; x.fillText(s.nom.slice(0, Math.floor((X(s.haut) - X(s.bas)) / 6)), (X(s.bas) + X(s.haut)) / 2, 39); }
  }
  // The waterfall's window when zoomed.
  const fz = tqFenetre();
  if (fz && !fz.plein) { x.strokeStyle = 'rgba(232,240,247,.7)'; x.lineWidth = 1; x.strokeRect(X(fz.b) + .5, 18.5, X(fz.t) - X(fz.b) - 1, 40); }
  // My memories, then the 50 kHz ticks.
  x.fillStyle = '#FFB454'; O.memoires.filter(m => !m.fixe).forEach(m => { x.beginPath(); x.moveTo(X(m.hz), 50); x.lineTo(X(m.hz) - 4, 57); x.lineTo(X(m.hz) + 4, 57); x.fill(); });
  x.fillStyle = '#9AA7B4'; x.strokeStyle = '#22303B'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
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
  x.strokeStyle = '#3B4B58'; x.fillStyle = '#9AA7B4'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
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
  // The waterfall: a signal named near the click is tuned in its mode; elsewhere, the frequency clicked.
  g('o-pano').addEventListener('click', ev => {
    if (!OP) return;
    const fz = tqFenetre(), bb = g('o-pano').getBoundingClientRect();
    const hz = fz.b + (fz.t - fz.b) * (ev.clientX - bb.left - 4) / (bb.width - 8);
    const tol = Math.max(600, (fz.t - fz.b) / Math.max(1, bb.width - 8) * 8);
    const k = tqCorr(), s = tqNommes().filter(q => Math.abs(q.hz + k - hz) <= q.largeurHz / 2 + tol).sort((p, q) => Math.abs(p.hz + k - hz) - Math.abs(q.hz + k - hz))[0];
    if (s) tqAccorde(s);
    else tqPost('api/qo100/accorde?hz=' + Math.round(hz / 100) * 100).then(() => chargeQo100());
  });
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

// ------------------------------------------------------------------ QO-100 cockpit
// One screen to operate the narrow transponder: the receiver in one gesture, the signals named on the waterfall,
// the decoder of the signal tuned docked below, the log on QO-100. Polled slowly elsewhere: the header says what SatMe follows.
let TQ = null, TQS = null, tqDecAuto = '', tqDock = 'auto', tqZ = 500000, tqFen = null, tqOccupe = false, tqErreur = '', tqN = 0, tqFt8Vu = '', tqSstvV = -1;
try { tqDock = localStorage.getItem('tq-dock') || 'auto'; tqZ = +(localStorage.getItem('tq-zoom') || 500000) || 500000; } catch (x) {}
g('tq-zoom').value = String(tqZ);
const TQ_COUL = { blu: '#3FE0C8', cw: '#6F8CFF', ft8: '#B07CFF', ft4: '#D58CFF', psk31: '#FF8FD0', rtty: '#FFD166', sstv: '#FFB454', porteuse: '#9AA7B4',
  balise: '#E5484D', numerique_large: '#8BC34A', inconnu: '#6B7C8F' };
const TQ_NOM = { blu: 'BLU', cw: 'CW', ft8: 'FT8', ft4: 'FT4', psk31: 'PSK31', rtty: 'RTTY', sstv: 'SSTV', porteuse: 'porteuse', balise: 'balise', numerique_large: 'numérique', inconnu: '?' };
const TQ_DEC_MODE = { SSB: 'phonie', CW: 'cw', FT8: 'ft8', FT4: 'ft8', SSTV: 'sstv' };
/** The signals worth a label: judged with some confidence (the doubtful ones would only clutter the waterfall). */
const TQ_SUR = 0.3;
/** Where the receiver hears a signal: its sky frequency plus what the calibration does not hold yet (0 once the LNB is calibrated). */
const tqCorr = () => (TQS && TQS.correction) || 0;
const tqNommes = () => (TQS && TQS.signaux ? TQS.signaux : []).filter(s => s.confiance >= TQ_SUR && s.classe !== 'inconnu');
g('tq-legende').innerHTML = Object.keys(TQ_NOM).filter(k => k !== 'inconnu').map(k => '<span><i style="background:' + TQ_COUL[k] + '"></i>' + TQ_NOM[k] + '</span>').join('');
/** A gesture without the alert box: the cockpit says what went wrong in its own words. */
async function tqPost(u) {
  try { const r = await fetch(u, { method: 'POST' }); const j = await r.json(); setTimeout(tic, 150); return j; } catch (x) { return null; }
}
async function tqTic() {
  let j = null;
  try { j = await (await fetch('api/qo100/trafic')).json(); } catch (x) {}
  TQ = j && !j.absent && j.contexte ? j : null;
  tqRend();
}
const tqQo = () => !!(TQ && TQ.contexte && TQ.contexte.qo100);
function tqRend() {
  const t = TQ, c = t && t.contexte, chip = g('ctx-chip');
  // The header, whatever the tab: what the log follows.
  chip.hidden = !c || !c.libelle;
  if (c && c.libelle) {
    txt('ctx-chip', (c.qo100 ? '📡 ' : '🛰 ') + c.libelle); chip.classList.toggle('qo', !!c.qo100);
    chip.title = c.qo100 ? 'SatMe suit QO-100 (' + (c.par === 'poste' ? 'le poste y est' : 'le récepteur y est') + ') : les contacts notés vont sur QO-100, avec cette fréquence et ce mode.'
      : 'Le satellite suivi : les contacts notés vont sur lui.';
  }
  // Room for it in the header: the station's line gives way.
  chip.parentNode.classList.toggle('a-ctx', !chip.hidden);
  g('tq-reprise').hidden = !(t && t.reprendre);
  // Réglages: shown where the setting exists (not on the phone).
  g('rq-carte').hidden = !(t && t.repriseAuto !== undefined);
  if (t && t.repriseAuto !== undefined && document.activeElement !== g('rq-auto')) g('rq-auto').checked = !!t.repriseAuto;
  // The log's entry, wherever it is: QO-100 said, the satellite list left aside.
  const qo = tqQo();
  g('c-satligne').hidden = qo && !g('ca-on').checked;
  if (!g('ca-on').checked) txt('c-aide', qo ? 'Daté à l’enregistrement, sur QO-100 (' + (c.par === 'poste' ? 'le poste y est' : 'le récepteur y est') + '), avec la fréquence du ciel, ton carré et le mode du signal accordé.'
    : 'Daté à l\'enregistrement, sur le satellite suivi, avec son az/el, ton carré, le mode et les fréquences au repos.');
  if (qo) txt('c-sat', '· ' + c.libelle);
  if (g('v-qo100').hidden) return;
  if (!t) { txt('tq-msg', 'Le cockpit QO-100 n’est pas disponible ici.'); return; }
  const r = t.recepteur, recoit = t.recoit;
  g('tq-go').hidden = recoit; g('tq-stop').hidden = !recoit;
  g('tq-go').disabled = tqOccupe;
  txt('tq-go', tqOccupe ? '… démarrage du récepteur' : r.actif && !t.cle ? '▶ Mettre le récepteur sur QO-100' : '▶ Démarrer la réception QO-100');
  const p = g('tq-pastille'); p.classList.toggle('marche', recoit); p.classList.toggle('coupe', !recoit);
  txt('tq-pastille', recoit ? 'réception QO-100' : r.actif ? 'récepteur sur autre chose' : 'arrêtée');
  g('tq-rx').classList.toggle('allume', recoit); g('tq-rx').classList.toggle('eteint', !recoit);
  txt('tq-recepteur', r.nom || '');
  // The steps of the gesture, each said done or not.
  const etapes = [[r.actif, 'récepteur en marche'], [t.cle, 'QO-100 appliqué au récepteur'], [recoit, 'son vers les décodeurs'], [qo, 'carnet sur QO-100']];
  const h = etapes.map(e => '<span class="' + (e[0] ? 'ok' : 'non') + '">' + (e[0] ? '✓ ' : '○ ') + e[1] + '</span>').join('');
  if (g('tq-etapes').innerHTML !== h) g('tq-etapes').innerHTML = h;
  // Why not, and what to do.
  let m = '';
  if (!recoit) {
    m = tqErreur || (r.erreur ? 'Le récepteur dit : ' + r.erreur + '.' : '');
    if (m) m += ' Vérifie qu’il est branché et allumé, et qu’il est bien choisi dans l’onglet SDR (carte « ' + (window.SATME_TELEPHONE ? 'La clé' : 'Le récepteur') + ' »).';
    else if (r.actif) m = 'Le récepteur écoute autre chose (onglet SDR) : « Mettre le récepteur sur QO-100 » le reprend.';
    else if (!tqOccupe) m = 'Le récepteur est arrêté : un clic sur « Démarrer la réception QO-100 » fait tout (récepteur, QO-100, son, carnet).';
  }
  txt('tq-msg', m);
  // The level: the receiver's, in dB.
  g('tq-smetre').style.width = (r.actif ? Math.max(0, Math.min(100, (r.niveau + 100) * 1.25)) : 0) + '%';
  txt('tq-db', r.actif ? r.niveau.toFixed(0) + ' dB' : '—');
  txt('tq-demod', recoit ? 'démodulé en ' + r.mode : '');
  if (document.activeElement !== g('tq-mode') && qo && c.mode) g('tq-mode').value = c.mode;
  // The sound.
  if (document.activeElement !== g('tq-hp')) g('tq-hp').checked = !!r.son;
  if (document.activeElement !== g('tq-sq')) g('tq-sq').value = String(r.squelch);
  txt('tq-sqv', r.squelch <= -120 ? 'ouvert' : r.squelch + ' dB');
  g('tq-agc-l').hidden = r.agc === null || r.agc === undefined;
  if (r.agc !== null && r.agc !== undefined && document.activeElement !== g('tq-agc')) g('tq-agc').checked = !!r.agc;
  g('tq-ec-boite').hidden = !!window.SATME_TELEPHONE;
  txt('tq-ec', ecArret && g('ec-source').value === 'sdr' ? '■ Arrêter l’écoute ici' : '🔈 Écouter ici');
  // The beacon far off and nothing following it: say so, with the remedy.
  const k = g('tq-conseil');
  k.hidden = !t.conseilCalage;
  const kh = t.conseilCalage ? 'La balise médiane tombe à <b>' + (t.ecartBalise > 0 ? '+' : '') + t.ecartBalise.toLocaleString('fr-FR') + ' Hz</b> de sa place : le LNB a dérivé, tout le transpondeur est décalé d’autant. ' +
    '<button class="primaire" style="margin-top:6px" onclick="tqCalageAuto()">Activer le calage automatique</button>' : '';
  if (k.innerHTML !== kh) k.innerHTML = kh;
  tqDockRend();
}
async function tqDemarre() {
  tqOccupe = true; tqErreur = ''; tqRend();
  const j = await tqPost('api/qo100/demarrer');
  tqOccupe = false;
  if (!j) tqErreur = 'SatMe ne répond pas.';
  else if (j.ok === false) tqErreur = 'Le récepteur n’a pas démarré : ' + (j.erreur || 'raison inconnue') + '.';
  await tqTic(); chargeQo100(); ticQualite();
}
async function tqArrete() {
  tqErreur = '';
  if (ecArret && g('ec-source').value === 'sdr') arreteEcoute();
  await tqPost('api/qo100/arreter'); await tqTic(); chargeQo100();
}
async function tqReprise(on) {
  if (on) { vue('qo100'); tqOccupe = true; tqRend(); }
  const j = await tqPost('api/qo100/reprise?on=' + on);
  tqOccupe = false;
  if (on && j && j.ok === false) tqErreur = 'Reprise impossible : ' + (j.erreur || 'raison inconnue') + '.';
  await tqTic(); chargeQo100();
}
async function tqMode(m) { await tqPost('api/qo100/mode?m=' + enc(m)); tqDecAuto = TQ_DEC_MODE[m] || ''; tqTic(); }
async function tqCalageAuto() { await tqPost('api/qo100/auto?on=1'); ticQualite(); tqTic(); }
function tqEcoute() {
  if (ecArret && g('ec-source').value === 'sdr') arreteEcoute();
  else { g('ec-source').value = 'sdr'; g('ec-vol').value = g('tq-vol').value; ecouteIci(); }
  setTimeout(tqRend, 400);
}
// « Comment ça marche »: the first visits, until closed.
function tqAide() {
  let n = 0, ferme = false;
  try { n = +(localStorage.getItem('tq-aide-n') || 0); ferme = localStorage.getItem('tq-aide-ferme') === '1'; localStorage.setItem('tq-aide-n', String(n + 1)); } catch (x) {}
  g('tq-aide').hidden = ferme || n >= 8;
}
function tqAideFerme() { g('tq-aide').hidden = true; try { localStorage.setItem('tq-aide-ferme', '1'); } catch (x) {} }
// The signals the receiver hears, each judged (BLU, CW, FT8…): asked only while the cockpit is shown (asking keeps the analysis on).
async function tqSignaux() {
  if (g('v-qo100').hidden) return;
  if (!TQ || !TQ.classifieur || !TQ.recoit) TQS = null;
  else { try { const j = await (await fetch('api/qo100/signaux')).json(); TQS = j && !j.absent ? j : null; } catch (x) { TQS = null; } }
  tqSignauxRend();
}
setInterval(tqSignaux, 1000);
function tqSignauxRend() {
  const tous = TQS && TQS.signaux ? TQS.signaux.length : 0, l = tqNommes().filter(s => s.classe !== 'balise').sort((a, b) => a.hz - b.hz);
  const cl = TQ && TQ.classifieur;
  g('tq-legende').hidden = !cl;
  txt('tq-cl-etat', !TQ ? '' : !cl ? 'les étiquettes des signaux ne sont pas encore là ici' : !TQ.recoit ? 'les signaux seront reconnus dès la réception'
    : TQS && TQS.actif ? l.length + ' signal(aux) reconnu(s)' + (tous > l.length ? ' (' + (tous - l.length) + ' douteux non étiquetés)' : '') + ' · un clic pour s’y accorder' : 'analyse des signaux en route…');
  const ici = (O ? O.descente : 0) - tqCorr();
  const h = l.map(s => {
    const c = TQ_COUL[s.classe] || '#9AA7B4', a = Math.abs(s.hz - ici) < 3000;
    return '<button class="' + (a ? 'ici' : '') + '" style="border-color:' + c + ';color:' + c + ';opacity:' + (0.5 + 0.5 * Math.min(1, s.confiance)).toFixed(2) + '" title="' +
      esc(s.libelle) + ' · sûr à ' + Math.round(s.confiance * 100) + ' % · ' + (s.largeurHz / 1000).toFixed(1) + ' kHz · ' + Math.round(s.snrBandeDb != null ? s.snrBandeDb : s.snrDb) + ' dB' + (s.details ? ' · ' + esc(s.details) : '') +
      '" onclick="tqAccordeId(' + s.id + ')">' + (TQ_NOM[s.classe] || esc(s.classe)) + ' · ' + (s.hz / 1e6).toFixed(4).replace('.', ',') + ' · ' + Math.round(s.confiance * 100) + ' %</button>';
  }).join('');
  if (g('tq-signaux').innerHTML !== h) g('tq-signaux').innerHTML = h;
}
function tqAccordeId(id) { const s = TQS && TQS.signaux ? TQS.signaux.find(x => x.id === id) : null; if (s) tqAccorde(s); }
/** A signal clicked: tuned in its mode, its decoder opened (and started) in the dock. */
async function tqAccorde(s) {
  const j = await tqPost('api/qo100/accorde?hz=' + (s.hz + tqCorr()) + '&classe=' + enc(s.classe));
  if (j && j.decodeur !== undefined) {
    tqDecAuto = j.decodeur;
    if (j.decodeur === 'ft8') await tqPost('api/ft8?on=1&mode=' + (j.ft8Mode || 'FT8'));
    else if (j.decodeur === 'cw') await tqPost('api/cw?on=1');
    else if (j.decodeur === 'sstv') await tqPost('api/sstv/reglage?actif=1');
    tqFt8Vu = '';
  }
  await chargeQo100(); tqTic(); ticCw(); ticSon(); tqFt8();
}
// The dock: the decoder chosen by the signal tuned (« Auto »), or by hand.
function tqDecodeur() { return tqDock !== 'auto' ? tqDock : (tqDecAuto || (tqQo() ? TQ_DEC_MODE[TQ.contexte.mode] || '' : '')); }
function tqDockRend() {
  const d = tqDecodeur();
  document.querySelectorAll('#tq-onglets button').forEach(b => { b.classList.toggle('actif', b.dataset.d === tqDock); b.classList.toggle('auto-choisi', tqDock === 'auto' && b.dataset.d === d); });
  for (const k of ['phonie', 'cw', 'ft8', 'sstv']) g('tq-d-' + k).hidden = d !== k;
  g('tq-d-rien').hidden = !!d;
  txt('tq-d-info', tqDock === 'auto' ? (d ? 'choisi d’après le signal accordé' : '') : 'choisi à la main');
  g('tq-ph-absent').hidden = !g('ph-carte').hidden;
  g('tq-cw-absent').hidden = !g('cw-carte').hidden;
}
document.querySelectorAll('#tq-onglets button').forEach(b => b.onclick = () => {
  tqDock = b.dataset.d; try { localStorage.setItem('tq-dock', tqDock); } catch (x) {}
  tqDockRend(); tqFt8(); tqSstv(); ticPhonie(true); ticCw();
});
// FT8/FT4 in the dock: the last messages, a click prepares the contact.
async function tqFt8() {
  if (g('v-qo100').hidden || tqDecodeur() !== 'ft8') return;
  let j = null;
  try { j = await (await fetch('api/ft8')).json(); } catch (x) {}
  if (!j || !j.present) { txt('tq-ft8-etat', 'FT8 n’est pas disponible ici'); return; }
  H = j;
  txt('tq-ft8-go', H.enMarche ? '■ Arrêter' : '▶ Décoder ' + (H.mode || 'FT8'));
  txt('tq-ft8-etat', !H.carte ? 'pas de son : démarrer la réception QO-100' : H.enMarche ? H.mode + ' · ' + H.tranches + ' créneaux · niveau ' + Math.round(H.niveau * 100) + ' %' : 'à l’arrêt');
  const l = (H.entendus || []).slice(0, 40);
  const cle = JSON.stringify([H.enMarche, l.length, l.length ? l[0].t + l[0].texte : '']);
  if (cle === tqFt8Vu) return;
  tqFt8Vu = cle;
  g('tq-ft8').innerHTML = l.map(e => '<tr><td class="petit">' + new Date(e.t).toISOString().slice(11, 19) + '</td><td>' + f8Drapeau(e.drapeau) + '</td><td class="petit">' + e.db +
    ' dB</td><td class="petit">' + e.hz + ' Hz</td><td style="font-family:ui-monospace,Consolas,monospace' + (e.moi ? ';color:#FF5FA2' : e.cq ? ';color:#5BD68A' : '') + '">' + esc(e.texte) +
    '</td><td>' + (e.de ? '<button title="Préparer ce contact" onclick="noteFt8(\'' + esc(e.de) + '\',\'' + esc(e.loc) + '\',' + e.db + ')">✎</button>' : '') + '</td></tr>').join('') ||
    '<tr><td class="petit">' + (H.enMarche ? 'rien encore décodé : un créneau dure ' + (H.mode === 'FT4' ? '7,5' : '15') + ' s' : 'à l’arrêt : « Décoder »') + '</td></tr>';
}
setInterval(tqFt8, 1500);
async function tqFt8Marche() { await tqPost('api/ft8?on=' + (H && H.enMarche ? 0 : 1) + '&mode=' + ((H && H.mode) || 'FT8')); tqFt8Vu = ''; tqFt8(); }
// SSTV in the dock: the picture arriving.
function tqSstv() {
  if (!S || g('v-qo100').hidden || tqDecodeur() !== 'sstv') return;
  txt('tq-sstv-etat', !S.actif ? 'pas de son : démarrer la réception QO-100' : !S.sstv ? 'décodage SSTV coupé (onglet SSTV)' : S.sstvMode ? S.sstvMode + ' · ' + S.sstvLignes + '/' + S.sstvHauteur + ' lignes'
    : S.sstvDerniere ? 'dernière image : ' + S.sstvDerniere : 'à l’écoute d’une image…');
  if (S.sstvVersion !== tqSstvV) {
    tqSstvV = S.sstvVersion;
    if (S.sstvMode) g('tq-sstv-img').src = 'api/sstv/encours.png?v=' + S.sstvVersion;
    else if (S.sstvDerniere) g('tq-sstv-img').src = 'sstv/' + enc(S.sstvDerniere);
  }
}
// The log's entry and the CW window live in the cockpit while it is shown, back in the Carnet when it is.
function tqRange(n) {
  if (n === 'qo100') { g('tq-carnet-slot').appendChild(g('c-saisie')); g('cw-slot-qo100').appendChild(g('cw-carte')); }
  else if (n === 'carnet') { g('c-saisie-slot').appendChild(g('c-saisie')); g('cw-slot-carnet').appendChild(g('cw-carte')); }
}
/** A callsign picked in a decoder: into the entry, where it is (the cockpit's, else the Carnet's). */
function versSaisie() { if (g('v-qo100').hidden) vue('carnet'); }
// The zoom: the window follows the tuning, moved only when it leaves the window's middle.
function tqFenetre() {
  if (!OP) return null;
  const B = OP.regletteBas, T = OP.regletteHaut;
  if (tqZ >= T - B) return { b: B, t: T, plein: true };
  const c = O ? O.descente : (B + T) / 2;
  let f = tqFen && tqFen.z === tqZ ? tqFen : null;
  if (!f || c < f.b + tqZ * 0.2 || c > f.t - tqZ * 0.2) {
    const b = Math.max(B, Math.min(T - tqZ, Math.round((c - tqZ / 2) / 1000) * 1000));
    f = { b: b, t: b + tqZ, z: tqZ };
  }
  tqFen = f;
  return f;
}
function tqZoom(z) { tqZ = z; tqFen = null; oPanHist = []; try { localStorage.setItem('tq-zoom', String(z)); } catch (x) {} dessineQo100(); ticPano(); }
/** The classifier's labels over the waterfall: colour by class, paler when less sure; the strongest first, four rows at most. */
function tqEtiquettes(x, w, X, b, t) {
  const l = tqNommes();
  if (!l.length) return;
  const rangs = [];
  x.font = '600 11px Drapeaux,system-ui'; x.textBaseline = 'top'; x.textAlign = 'center';
  l.slice().sort((p, q) => q.snrDb - p.snrDb).forEach(s => {
    if (s.hz + tqCorr() < b || s.hz + tqCorr() > t) return;
    const c = TQ_COUL[s.classe] || '#9AA7B4', cx = X(s.hz + tqCorr()), lw = Math.max(3, (w - 8) * s.largeurHz / (t - b));
    const e = (TQ_NOM[s.classe] || s.classe) + ' ' + Math.round(s.confiance * 100) + '%', tw = x.measureText(e).width + 8;
    let r = 0;
    while (r < 4 && (rangs[r] || []).some(a => cx - tw / 2 < a[1] + 2 && cx + tw / 2 > a[0] - 2)) r++;
    if (r >= 4) return;
    (rangs[r] = rangs[r] || []).push([cx - tw / 2, cx + tw / 2]);
    const y = 12 + r * 19;
    x.globalAlpha = 0.4 + 0.6 * Math.max(0, Math.min(1, s.confiance));
    x.fillStyle = 'rgba(6,19,26,.85)'; x.fillRect(cx - tw / 2, y, tw, 15);
    x.strokeStyle = c; x.lineWidth = 1; x.strokeRect(cx - tw / 2 + .5, y + .5, tw - 1, 14);
    x.fillStyle = c; x.fillText(e, cx, y + 2);
    x.fillRect(cx - lw / 2, y + 16, lw, 2);
    x.globalAlpha = 1;
  });
  x.textBaseline = 'alphabetic';
}
let tqTour = 0;
setInterval(() => { if (!g('v-qo100').hidden || ++tqTour % 3 === 0) tqTic(); }, 1000);
tqTic();

// ------------------------------------------------------------------ SDR
let R = null, rSpan = 48000, rCascade = [], rTrames = -1, rDernier = null;
const R820T = [0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496];
// The gains offered: the dongle's tuner table, or the Pluto's 0 to 71 dB (tenths, as the dongle's).
function remplitGain(pluto) {
  const l = pluto ? Array.from({ length: 72 }, (_, k) => k * 10) : R820T;
  g('r-gain').innerHTML = '<option value="-1">' + (pluto ? 'automatique (CAG lente)' : 'automatique') + '</option>' +
    l.map(x => '<option value="' + x + '">' + (x / 10).toLocaleString('fr-FR') + ' dB</option>').join('');
  g('r-gain').dataset.src = pluto ? 'pluto' : 'rtl';
}
remplitGain(false);
async function rRegle(q) { await post('api/sdr/reglage?' + q); ticSdr(); }
async function ticSdr() {
  // The log's live station shows the dongle too.
  if (g('v-sdr').hidden && g('v-carnet').hidden) return;
  if (!(fluxOk && R && fluxSujets.includes('sdr'))) { try { R = await (await fetch('api/sdr')).json(); } catch (x) { return; } }
  rendSdr();
}
function rendSdr() {
  if (!R) return;
  const r = R;
  etatMarche('r', r.actif); txt('r-pastille', r.actif ? 'réception' : 'arrêtée');
  txt('r-info', r.actif ? r.tuner + ' · ' + (r.debit / 1024).toFixed(1) + ' Mo/s' + (r.perdus ? ' · ' + r.perdus + ' blocs perdus' : '') : '');
  txt('r-erreur', r.erreur || '');
  txt('r-sat', r.satellite || (E && E.suivi ? E.suivi.nom : '(aucun)'));
  const coche = (id, b) => { if (document.activeElement !== g(id)) g(id).checked = b; };
  coche('r-suit', r.suit); coche('r-doppler', r.doppler); coche('r-deemph', r.deemph); coche('r-son', r.son); coche('r-agc', r.agc);
  coche('r-lance', r.lance); coche('r-bias', r.bias);
  const val = (id, x) => { if (document.activeElement !== g(id)) g(id).value = String(x); };
  // The receiver: the dongle (rtl_tcp) or the Pluto, each with its own settings shown.
  const pluto = r.recepteur === 'pluto';
  if (g('r-gain').dataset.src !== (pluto ? 'pluto' : 'rtl')) remplitGain(pluto);
  txt('r-titre', pluto ? 'ADALM-PLUTO (réseau)' : 'Clé SDR (rtl_tcp)'); txt('r-cle-t', pluto ? 'Le récepteur' : 'La clé');
  g('r-agc-l').hidden = pluto; g('r-liaison').hidden = pluto; g('r-liaison-pluto').hidden = !pluto;
  val('r-recepteur', r.recepteur || 'rtl');
  if (r.plutoHote !== undefined) { val('r-pluto-hote', r.plutoHote); val('r-pluto-port', r.plutoPort); }
  val('r-mode', r.mode); val('r-bw', r.bw); val('r-sq', r.squelch);
  val('r-gain', pluto && r.gain >= 0 ? Math.min(710, Math.round(r.gain / 10) * 10) : r.gain);
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
  // Fine tuning: shown as chosen (Réglages › Accord fin).
  g('af-carte').hidden = r.loupe === undefined;
  coche('af-loupe', r.loupe); coche('af-vernier', r.vernier); coche('af-voix', r.calageVoix); val('af-ratio', r.ratio); val('r-loupe-span', r.loupeSpan);
  g('r-loupe-boite').hidden = !r.loupe; g('r-vernier').hidden = !r.vernier; g('r-vernier-l').hidden = !r.vernier; g('r-ratios').hidden = !r.vernier;
  g('r-voix').hidden = !r.calageVoix; g('r-voix').disabled = !r.actif || !(r.modeEff === 'USB' || r.modeEff === 'LSB');
  g('r-ratios').innerHTML = [20, 200, 2000].map(k => '<button style="padding:2px 8px;font-size:11px' + (k === r.ratio ? ';border-color:var(--cyan);color:var(--cyan)' : '') +
    '" onclick="post(\'api/sdr/accord?ratio=' + k + '\').then(ticSdr)">' + (k >= 1000 ? k / 1000 + ' kHz' : k + ' Hz') + '/cm</button>').join('');
  dessineVernier();
}
// Réglages › Accord fin: shown when a dongle is known here (the phone's page sets these on the phone).
async function majAccordReglages() {
  let r; try { r = await (await fetch('api/sdr')).json(); } catch (x) { return; }
  const ok = r && !r.absent && r.loupe !== undefined;
  g('af-carte').hidden = !ok;
  if (!ok) { const b = document.querySelector('#reg-nav button[data-cat="accord"]'); if (b) b.hidden = true; return; }
  g('af-loupe').checked = r.loupe; g('af-vernier').checked = r.vernier; g('af-voix').checked = r.calageVoix; g('af-ratio').value = String(r.ratio);
}
async function caleVoix() { await post('api/sdr/voix'); setTimeout(ticSdr, 400); }
// The vernier: a dial pushed by the finger or the mouse; the frequency moves N Hz per centimetre, with momentum.
const PX_PAR_CM = 96 / 2.54;
let vnDecal = 0, vnEnvoi = 0, vnTenu = null, vnVitesse = 0, vnDernier = 0;
function hzParPx() { return ((R && R.ratio) || 200) / PX_PAR_CM; }
function pasGraduation(hpp) { const min = 14 * hpp; for (let d = 1; d <= 1e8; d *= 10) for (const m of [1, 2, 5]) if (d * m >= min) return d * m; return 1e8; }
function dessineVernier() {
  const c = g('r-vernier'); if (!c || c.hidden || !R) return;
  const [x, w, h] = toile(c), hpp = hzParPx(), centre = (R.ciel || 0) + (R.decalage || 0) + vnDecal, pas = pasGraduation(hpp);
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  const bas = centre - w / 2 * hpp;
  x.strokeStyle = '#5A6B7C'; x.fillStyle = '#9AA7B4'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
  for (let f = Math.floor(bas / pas) * pas; f <= centre + w / 2 * hpp; f += pas) {
    const px = w / 2 + (f - centre) / hpp, maj = Math.round(f / pas) % 5 === 0;
    x.beginPath(); x.moveTo(px, h); x.lineTo(px, h - (maj ? 18 : 9)); x.stroke();
    if (maj) x.fillText(((f % 1e6) / 1e3).toFixed(pas < 100 ? 2 : pas < 1000 ? 1 : 0), px, h - 22);
  }
  x.strokeStyle = '#FFB454'; x.lineWidth = 2; x.beginPath(); x.moveTo(w / 2, 0); x.lineTo(w / 2, h); x.stroke(); x.lineWidth = 1;
}
function vernierPousse(dxPx) {
  vnDecal += -dxPx * hzParPx();
  const n = Math.trunc(vnDecal);
  if (n && Date.now() - vnEnvoi > 80) { vnEnvoi = Date.now(); vnDecal -= n; R.decalage = (R.decalage || 0) + n; post('api/sdr/pas?hz=' + n); }
  dessineVernier();
}
(() => {
  const c = g('r-vernier');
  c.addEventListener('pointerdown', ev => { c.setPointerCapture(ev.pointerId); vnTenu = ev.clientX; vnVitesse = 0; vnDernier = performance.now(); });
  c.addEventListener('pointermove', ev => {
    if (vnTenu === null) return;
    const now = performance.now(), dx = ev.clientX - vnTenu; vnTenu = ev.clientX;
    vnVitesse = dx / Math.max(1, now - vnDernier) * 1000; vnDernier = now; vernierPousse(dx);
  });
  c.addEventListener('pointerup', () => {
    vnTenu = null;
    // Momentum: the dial keeps turning and slows down (τ = 0,32 s), as on the phone.
    let v = vnVitesse, t0 = performance.now();
    const elan = now => { const dt = (now - t0) / 1000; t0 = now; v *= Math.exp(-dt / 0.32); if (Math.abs(v) < 24 || vnTenu !== null) return; vernierPousse(v * dt); requestAnimationFrame(elan); };
    if (Math.abs(v) > 24) requestAnimationFrame(elan);
  });
  c.addEventListener('wheel', ev => { ev.preventDefault(); vernierPousse(ev.deltaY > 0 ? -PX_PAR_CM / 4 : PX_PAR_CM / 4); }, { passive: false });
})();
// The magnifier: a few kHz around where we listen.
let rLoupe = null;
async function loupeSdr() {
  if (g('v-sdr').hidden || !R || !R.actif || !R.loupe) return;
  const c = g('r-loupe');
  try { rLoupe = await (await fetch('api/sdr/spectre?span=' + R.loupeSpan + '&autour=ecoute&n=' + Math.min(512, Math.max(64, c.clientWidth)))).json(); } catch (x) { return; }
  if (!rLoupe.db) return;
  const [x, w, h] = toile(c), d = rLoupe.db, tri = d.slice().sort((a, b) => a - b), bruit = tri[Math.floor(tri.length * 0.3)], haut = Math.max(bruit + 20, tri[tri.length - 1]);
  x.fillStyle = '#0E1720'; x.fillRect(0, 0, w, h);
  const Y = v => h - 4 - (h - 8) * Math.max(0, Math.min(1, (v - (bruit - 8)) / (haut - bruit + 12)));
  x.strokeStyle = '#3FE0C8'; x.lineWidth = 1.4; x.beginPath(); d.forEach((v, i) => { const px = i * w / d.length; i ? x.lineTo(px, Y(v)) : x.moveTo(px, Y(v)); }); x.stroke();
  x.strokeStyle = '#FFB454'; x.beginPath(); x.moveTo(w / 2, 0); x.lineTo(w / 2, h); x.stroke();
  x.fillStyle = '#9AA7B4'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
  const pas = R.loupeSpan > 10000 ? 2000 : R.loupeSpan > 5000 ? 1000 : 500;
  for (let f = -Math.floor(R.loupeSpan / 2 / pas) * pas; f <= R.loupeSpan / 2; f += pas) if (f) x.fillText((f > 0 ? '+' : '') + (f / 1000).toFixed(1), w / 2 + f / R.loupeSpan * w, 10);
}
setInterval(loupeSdr, 200);
g('r-loupe').addEventListener('click', ev => {
  if (!R) return;
  const b = ev.currentTarget.getBoundingClientRect();
  const hz = Math.round(((ev.clientX - b.left) / b.width - 0.5) * R.loupeSpan / 10) * 10;
  post('api/sdr/pas?hz=' + hz).then(ticSdr);
});
setInterval(ticSdr, 700);
async function spectreSdr() {
  if (g('v-sdr').hidden || !R || !R.actif || (fluxOk && fluxSujets.includes('spectre'))) return;
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
  x.fillStyle = '#9AA7B4'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
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
  etatMarche('s2', s.actif); txt('s2-pastille', s.actif ? 'réception' : 'arrêtée');
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
  etatMarche('m', m.actif || enFichier);
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
// Four ideas: the slot's ring, the waterfall that speaks (callsigns written where they sent, the slots' edges,
// a free spot found), the radar (each station at its bearing and distance), and who to call now (what each brings).
let H = null, ft8Vu = '', f8Lignes = [], f8Total = 0, f8Pas = 128, f8Fin = 0, f8Complete = false, f8Libre = null, f8Clic = null;
// The flags' font, loaded before the canvases draw with it.
try { document.fonts.load('12px Drapeaux', '\u{1F1EB}\u{1F1F7}'); } catch (e) {}
const f8Drapeau = c => c && c.length === 2 ? String.fromCodePoint(...[...c.toUpperCase()].map(x => 0x1F1E6 + x.charCodeAt(0) - 65)) : '🏳';
const F8_CONT = { EU: 'Europe', AF: 'Afrique', AS: 'Asie', NA: 'Am. Nord', SA: 'Am. Sud', OC: 'Océanie' };
const f8Slot = () => (H && H.mode === 'FT4') ? 7500 : 15000;
// A colour table (dark blue, violet, orange, yellow, white): weak lines stay visible, strong ones do not burn.
const F8_PAL = (() => {
  const p = [[5, 7, 13], [20, 24, 70], [80, 30, 120], [170, 50, 110], [235, 110, 50], [250, 200, 60], [255, 255, 230]], t = [];
  for (let i = 0; i < 256; i++) { const k = i / 255 * (p.length - 1), a = Math.min(p.length - 2, Math.floor(k)), f = k - a;
    t.push(p[a].map((v, j) => Math.round(v + (p[a + 1][j] - v) * f))); }
  return t;
})();
async function ticFt8() {
  if (g('v-ft8').hidden) return;
  try { H = await (await fetch('api/ft8')).json(); } catch (x) { return; }
  if (!H.present) return;
  etatMarche('8', H.enMarche, 'aucune');
  if (document.activeElement !== g('8-mode')) g('8-mode').value = H.mode;
  txt('8-etat', !H.carte ? 'carte son arrêtée (onglet Son)' : H.enMarche ? H.tranches + ' créneaux · niveau ' + Math.round(H.niveau * 100) + ' %' : 'à l’arrêt');
  // The page served by the phone has no stream of rows: its whole waterfall each time.
  if (f8Complete && H.cascade) { f8Lignes = H.cascade.slice().reverse().map(b => Uint8Array.from(atob(b), ch => ch.charCodeAt(0))); f8Fin = Date.now(); f8Pas = 128; dessineCascade(); }
  for (const f of [dessineAnneau, horlogeFt8, diagFt8, guetFt8, puces, opportunites, listeFt8, messagesFt8]) { try { f(); } catch (x) { console.error(x); } }
}
// What a line is to me, said the same in the list, the messages and the waterfall: [word, short, colour, why].
function f8Marques(e) {
  const m = [];
  if (e.moi) m.push(['m’appelle', 'm’appelle', '#FF5FA2', 'Ce message contient ton indicatif']);
  if (e.guettee) m.push(['👁 guettée', '👁', '#3FE0C8', 'Une station que tu guettes']);
  if (e.nouveauPays) m.push(['nouveau pays', 'pays', '#FFB454', 'Aucun contact de ce pays au carnet']);
  if (e.nouveauCarre) m.push(['nouvelle grille', 'grille', '#FFB454', 'Aucun contact dans ce carré au carnet']);
  if (e.fait) m.push(['déjà contacté', '✓', '#6B7C8F', 'Déjà au carnet']);
  return m;
}
// [court]: the table's narrow column takes the short words, as the waterfall.
const f8Tags = (e, court) => f8Marques(e).map(x => ' <span class="f8-tag" style="color:' + x[2] + ';border-color:' + x[2] + '" title="' + x[0] + ' : ' + x[3] + '">' + x[court ? 1 : 0] + '</span>').join('');
// Our clock read from the stations' DT: the same lateness for all is ours.
function horlogeFt8() {
  const qui = window.SATME_TELEPHONE ? 'du téléphone' : 'du PC', o = H.horloge, el = g('8-horloge');
  if (!o) { el.textContent = H.enMarche ? 'Horloge : …' : ''; el.style.color = 'var(--gris)';
    el.title = 'L’horloge se lit sur le DT des stations : il faut quelques décodages récents.'; return; }
  const d = o.decalage, a = Math.abs(d);
  el.textContent = 'Horloge ' + qui + ' : ' + (d > 0 ? '+' : d < 0 ? '−' : '') + a.toFixed(1).replace('.', ',') + ' s';
  el.style.color = a < 0.3 ? 'var(--vert)' : a < 1 ? 'var(--ambre)' : 'var(--rose)';
  el.title = 'Le DT médian de ' + o.decodes + ' décodages sur les ' + o.tranches + ' dernières tranches' +
    (d > 0 ? ' : les stations arrivent en retard, notre horloge est en avance.' : d < 0 ? ' : les stations arrivent en avance, notre horloge est en retard.' : '.') +
    ' Les stations s’écartent de ±' + o.dispersion.toFixed(1).replace('.', ',') + ' s entre elles.\n' +
    'Si toutes montrent le même décalage, c’est notre horloge : la synchroniser par NTP' +
    (window.SATME_TELEPHONE ? ' (heure automatique dans les réglages du téléphone).' : ' (timedatectl sous Linux, « Régler l’heure automatiquement » sous Windows).') +
    ' Au-delà d’une seconde, le décodage se perd.';
}
// One problem with the reception at a time, gone once solved.
function diagFt8() {
  const d = H.diag, el = g('8-diag');
  el.hidden = !d;
  if (!d) return;
  el.classList.toggle('ambre', d.code === 'rien');
  el.innerHTML = '<span class="ico">' + (d.code === 'son' ? '🔇' : d.code === 'silence' ? '🔈' : '📭') + '</span><span><b>' + esc(d.titre) + '</b> — ' + esc(d.detail) + '</span>';
}
// The stations waited for: typed here, kept by the station; heard lately, said discreetly.
async function f8Guette(t) {
  const j = await post('api/ft8?guettees=' + enc(t.trim()) + (t.trim() ? '' : '&guettees_vide=1'));
  if (j && j.guettees) g('8-guet').value = j.guettees.join(' ');
  ft8Vu = ''; f8MsgVu = ''; ticFt8();
}
function guetFt8() {
  if (document.activeElement !== g('8-guet')) g('8-guet').value = (H.guettees || []).join(' ');
  const vus = new Map(), now = Date.now();
  for (const e of H.entendus) if (e.guettee && now - e.t < 10 * 60000 && !vus.has(e.de)) vus.set(e.de, e);
  g('8-guet-alerte').innerHTML = [...vus.values()].map(e => '<span class="f8-puce guet' + (now - e.t < 2 * f8Slot() + 3000 ? ' frais' : '') +
    '" title="Réception seule : rien n’est émis">👁 <b>' + esc(e.de) + '</b> ' + new Date(e.t).toISOString().slice(11, 19) + ' · ' + e.hz + ' Hz · ' + (e.db > 0 ? '+' : '') + e.db + ' dB</span>').join('');
}
async function ticCascade() {
  if (g('v-ft8').hidden || f8Complete) return;
  let j; try { const r = await fetch('api/ft8/cascade?depuis=' + f8Total); if (!r.ok) { f8Complete = true; return; } j = await r.json(); } catch (x) { return; }
  if (j.total < f8Total) f8Lignes = [];
  f8Total = j.total; f8Pas = j.pasMs || f8Pas; f8Fin = j.finMs || f8Fin;
  (j.lignes || []).forEach(b => f8Lignes.push(Uint8Array.from(atob(b), ch => ch.charCodeAt(0))));
  if (f8Lignes.length > 360) f8Lignes.splice(0, f8Lignes.length - 360);
  dessineCascade();
}
setInterval(ticCascade, 250);
// The slot's ring: the 15 s as a circle, the seconds left, even or odd.
function dessineAnneau() {
  const s = f8Slot(), now = Date.now(), dans = now % s, pair = Math.floor(now / s) % 2 === 0;
  const a = dans / s * 2 * Math.PI, R = 42, x = 50 + R * Math.sin(a), y = 50 - R * Math.cos(a), grand = a > Math.PI ? 1 : 0;
  g('8-anneau').innerHTML = '<circle cx="50" cy="50" r="42" fill="none" stroke="#1C2A36" stroke-width="9"/>' +
    (H && H.enMarche ? '<path d="M50 8 A42 42 0 ' + grand + ' 1 ' + x.toFixed(1) + ' ' + y.toFixed(1) + '" fill="none" stroke="' + (pair ? '#3FE0C8' : '#FFB454') + '" stroke-width="9" stroke-linecap="round"/>' : '') +
    '<text x="50" y="50" text-anchor="middle" font-size="22" font-weight="800" fill="#E8F0F7">' + Math.ceil((s - dans) / 1000) + '</text>' +
    '<text x="50" y="68" text-anchor="middle" font-size="10" fill="' + (pair ? '#3FE0C8' : '#FFB454') + '">' + (pair ? 'PAIR' : 'IMPAIR') + '</text>';
}
setInterval(() => { if (!g('v-ft8').hidden) dessineAnneau(); }, 200);
// The session at a glance.
function puces() {
  const l = H.entendus, de = new Set(l.map(e => e.de).filter(Boolean)), pays = new Set(l.map(e => e.pays).filter(Boolean)), cont = new Set(l.map(e => e.cont).filter(Boolean));
  const loin = l.filter(e => e.km != null).sort((a, b) => b.km - a.km)[0];
  const nv = new Set(l.filter(e => e.nouveauPays).map(e => e.pays)), cq = new Set(l.filter(e => e.cq).map(e => e.de));
  const p = (v, t, c) => '<span class="f8-puce"' + (c ? ' style="color:' + c + '"' : '') + '><b>' + v + '</b> ' + t + '</span>';
  g('8-puces').innerHTML = p(de.size, 'stations') + p(pays.size, 'pays') + p(cont.size + '/6', 'continents') + p(cq.size, 'en CQ', '#5BD68A') +
    (nv.size ? p(nv.size, 'pays nouveaux', '#FFB454') : '') + (loin ? p(loin.km.toLocaleString() + ' km', f8Drapeau(loin.drapeau) + ' ' + esc(loin.de), '#3FE0C8') : '');
}
// Who to call now: each station heard in the last few minutes, valued by what it would bring.
function valeur(e) {
  let s = 0; const r = [];
  if (e.nouveauPays) { s += 50; r.push(['nouveau pays', '#FFB454']); }
  if (e.nouveauCarre) { s += 20; r.push(['nouvelle grille', '#FFB454']); }
  if (e.guettee) { s += 30; r.push(['👁 guettée', '#3FE0C8']); }
  if (e.km != null) { s += Math.min(30, e.km / 300); if (e.km > 3000) r.push(['DX ' + e.km.toLocaleString() + ' km', '#3FE0C8']); }
  s += Math.max(0, e.db + 24) / 2;
  if (e.cq) { s += 15; r.push(['CQ', '#5BD68A']); }
  if (e.vu <= 2) { s += 5; r.push(['rare ici', '#9AA7B4']); }
  if (e.fait) { s -= 40; r.push(['déjà contacté', '#6B7C8F']); }
  if (e.moi) { s += 100; r.push(['m’appelle', '#FF5FA2']); }
  return [s, r];
}
function opportunites() {
  const recent = Date.now() - 5 * 60000, vus = new Map();
  for (const e of H.entendus) if (e.de && e.t >= recent && !vus.has(e.de)) vus.set(e.de, e);
  const l = [...vus.values()].map(e => [e, ...valeur(e)]).filter(x => x[1] > 0).sort((a, b) => b[1] - a[1]).slice(0, 7);
  g('8-opport').innerHTML = l.map(([e, s, r]) => '<div class="f8-op" onclick="noteFt8(\'' + esc(e.de) + '\',\'' + esc(e.loc) + '\',' + e.db + ')" title="Préparer ce contact dans le carnet">' +
    '<span class="f8-dr">' + f8Drapeau(e.drapeau) + '</span><span style="min-width:0;flex:1"><b class="f8-call">' + esc(e.de) + '</b> <span class="petit">' + esc(e.pays || '') +
    (e.loc ? ' · ' + esc(e.loc) : '') + (e.az != null ? ' · ' + e.az + '°' : '') + ' · ' + e.hz + ' Hz · ' + (e.pair ? 'pair' : 'impair') + '</span><br>' +
    r.map(x => '<span class="f8-tag" style="color:' + x[1] + ';border-color:' + x[1] + '">' + x[0] + '</span>').join('') + '</span>' +
    '<span class="f8-score" title="Anneau : ce qu’elle apporterait ; au centre : son signal (dB)" style="--v:' + Math.min(100, Math.round(s)) + '">' + (e.db > 0 ? '+' : '') + e.db + '</span></div>').join('') ||
    '<div class="petit">' + (H.enMarche ? 'personne depuis 5 minutes' : 'à l’écoute, les stations s’y classeront') + '</div>';
}
function listeFt8() {
  const f = v('8-filtre').toUpperCase(), cq = g('8-cq').checked;
  const l = H.entendus.filter(e => (!cq || e.cq) && (!f || [e.texte, e.loc, e.pays].join(' ').toUpperCase().includes(f)));
  const cle = JSON.stringify([f, cq, l.length, l.length ? l[0].t + l[0].texte : '']);
  if (cle === ft8Vu) { dessineRadar(); return; }
  ft8Vu = cle;
  let avant = 0;
  g('8-liste').innerHTML = l.slice(0, 250).map(e => {
    const cl = [avant && e.t !== avant ? 'f8-sep' : '', e.guettee ? 'f8-guettee' : ''].filter(Boolean).join(' '), sep = cl ? ' class="' + cl + '"' : ''; avant = e.t;
    const coul = e.moi ? '#FF5FA2' : e.nouveauPays ? '#FFB454' : e.cq ? '#5BD68A' : e.fait ? '#6B7C8F' : '#E8F0F7';
    const barre = '<span class="f8-db"><i style="width:' + Math.max(4, Math.min(100, (e.db + 24) * 3)) + '%;background:' + (e.db >= -5 ? '#5BD68A' : e.db >= -15 ? '#FFB454' : '#FF5FA2') + '"></i></span>';
    return '<tr' + sep + '><td class="petit">' + new Date(e.t).toISOString().slice(11, 19) + '</td><td title="' + esc(e.pays || '') + (e.cont ? ' · ' + (F8_CONT[e.cont] || e.cont) : '') + '">' + f8Drapeau(e.drapeau) +
      '</td><td style="color:' + coul + ';font-weight:700">' + esc(e.de || '') + (e.nouveauPays ? ' ★' : '') + '</td><td>' + barre + '<span class="petit">' + e.db + '</span></td><td class="petit">' + e.hz +
      '</td><td style="font-family:ui-monospace,Consolas,monospace;color:' + coul + '">' + esc(e.texte) + f8Tags(e, true) +
      '</td><td class="petit">' + (e.km == null ? '' : e.km.toLocaleString() + (e.az != null ? ' · ' + e.az + '°' : '')) + '</td><td>' + (e.de ? '<button title="Préparer ce contact dans le carnet" onclick="noteFt8(\'' + esc(e.de) + '\',\'' + esc(e.loc) + '\',' + e.db + ')">✎</button>' : '') + '</td></tr>';
  }).join('') || '<tr><td colspan="8" class="petit">' + (H.enMarche ? 'rien encore décodé' : 'prêt') + '</td></tr>';
  dessineRadar();
}
// The waterfall: newest on top; the slots' edges; the callsigns where they sent; the free spot.
function dessineCascade() {
  const c = g('8-cascade'), [x, w, h] = toile(c), n = f8Lignes.length;
  x.fillStyle = '#05070D'; x.fillRect(0, 0, w, h);
  if (!n || !H) return;
  const cols = f8Lignes[n - 1].length, img = x.createImageData(cols, n);
  for (let r = 0; r < n; r++) { const ligne = f8Lignes[n - 1 - r];
    for (let k = 0; k < cols; k++) { const p = F8_PAL[ligne[k]], o = 4 * (r * cols + k); img.data[o] = p[0]; img.data[o + 1] = p[1]; img.data[o + 2] = p[2]; img.data[o + 3] = 255; } }
  const tmp = document.createElement('canvas'); tmp.width = cols; tmp.height = n; tmp.getContext('2d').putImageData(img, 0, 0);
  x.imageSmoothingEnabled = false; x.drawImage(tmp, 0, 0, w, h * n / 360);
  const dureeVue = 360 * f8Pas, Y = t => (f8Fin - t) / dureeVue * h, X = hz => (hz - H.basse) / (H.haute - H.basse) * w, s = f8Slot();
  // The slots' edges, their time and parity.
  x.font = '10px Drapeaux,system-ui'; x.textAlign = 'left';
  for (let t = Math.floor(f8Fin / s) * s; t > f8Fin - dureeVue; t -= s) {
    const y = Y(t); x.strokeStyle = 'rgba(232,240,247,.35)'; x.setLineDash([4, 4]); x.beginPath(); x.moveTo(0, y); x.lineTo(w, y); x.stroke(); x.setLineDash([]);
    const pair = Math.floor(t / s) % 2 === 0; x.fillStyle = pair ? '#3FE0C8' : '#FFB454';
    x.fillText(new Date(t).toISOString().slice(11, 19) + (pair ? ' pair' : ' impair'), 4, y + 12);
  }
  // The callsigns, in the slot they sent in, at their audio frequency.
  x.font = 'bold 11px Drapeaux,system-ui';
  // Two stations close in frequency: the second label a line lower, not over the first.
  const places = [];
  for (const e of H.entendus) {
    if (!e.de || e.t < f8Fin - dureeVue - s) continue;
    const px = X(e.hz);
    let y = Y(e.t + s * 0.85);
    // Its marks after the callsign, short, in their colours.
    const mq = f8Marques(e), t = f8Drapeau(e.drapeau) + ' ' + e.de, lt = x.measureText(t).width;
    x.font = 'bold 9px Drapeaux,system-ui';
    const lm = mq.map(q => x.measureText(q[1]).width + 6), lw0 = lt + 8 + lm.reduce((a, b) => a + b + 3, 0);
    x.font = 'bold 11px Drapeaux,system-ui';
    while (places.some(p => Math.abs(p[1] - y) < 14 && px < p[0] + p[2] && p[0] < px + lw0)) y += 14;
    places.push([px, y, lw0]);
    if (y < 8 || y > h) continue;
    const coul = e.moi ? '#FF5FA2' : e.guettee ? '#3FE0C8' : e.nouveauPays ? '#FFB454' : e.cq ? '#5BD68A' : e.fait ? '#9AA7B4' : '#E8F0F7';
    x.fillStyle = e.guettee ? 'rgba(18,48,45,.9)' : 'rgba(5,7,13,.72)'; x.fillRect(px - 2, y - 11, lw0, 14); x.fillStyle = coul; x.fillText(t, px + 2, y);
    x.fillRect(px - 2, y - 11, 2, 14);
    let mx = px + lt + 6;
    x.font = 'bold 9px Drapeaux,system-ui';
    mq.forEach((q, i) => { x.strokeStyle = q[2]; x.fillStyle = q[2]; x.strokeRect(mx + 0.5, y - 9.5, lm[i], 11); x.fillText(q[1], mx + 3, y - 1); mx += lm[i] + 3; });
    x.font = 'bold 11px Drapeaux,system-ui';
  }
  // A free spot to send: the quietest 60 Hz of the last two slots between 500 and 2 500 Hz
  // (the rig's filter cuts the edges), away from every station decoded.
  const occupe = H.entendus.filter(e => e.t > f8Fin - 2 * s - 2000).map(e => e.hz);
  let meilleur = null, mini = 1e9;
  const recents = f8Lignes.slice(Math.max(0, n - Math.round(2 * s / f8Pas)));
  for (let hz = 500; hz <= 2500; hz += 10) {
    if (occupe.some(o => Math.abs(o - hz) < 70)) continue;
    const k0 = Math.floor((hz - 30 - H.basse) / (H.haute - H.basse) * cols), k1 = Math.ceil((hz + 30 - H.basse) / (H.haute - H.basse) * cols);
    let somme = 0; for (const l of recents) for (let k = k0; k <= k1; k++) somme += l[k] || 0;
    // As quiet: the nearer the middle of the passband, the better.
    if (somme < mini * 0.97 || (somme <= mini * 1.03 && meilleur != null && Math.abs(hz - 1500) < Math.abs(meilleur - 1500))) { mini = Math.min(mini, somme); meilleur = hz; }
  }
  f8Libre = meilleur;
  if (meilleur) { const px = X(meilleur); x.fillStyle = 'rgba(91,214,138,.22)'; x.fillRect(X(meilleur - 30), 0, X(meilleur + 30) - X(meilleur - 30), h);
    x.fillStyle = '#5BD68A'; x.font = 'bold 11px Drapeaux,system-ui'; x.fillText('▼ libre ' + meilleur + ' Hz', Math.min(w - 90, px + 4), 12); }
  txt('8-libre', meilleur ? '· place libre pour émettre : ' + meilleur + ' Hz' : '');
  // The audio frequencies.
  x.fillStyle = 'rgba(232,240,247,.75)'; x.font = '10px Drapeaux,system-ui';
  for (let f = 500; f < H.haute; f += 500) { const px = X(f); x.fillRect(px, h - 5, 1, 5); x.fillText(f, px + 2, h - 6); }
  if (f8Clic) { x.strokeStyle = '#FF5FA2'; x.beginPath(); x.moveTo(X(f8Clic), 0); x.lineTo(X(f8Clic), h); x.stroke(); x.fillStyle = '#FF5FA2'; x.fillText(f8Clic + ' Hz', X(f8Clic) + 3, 26); }
  // The spectrum: the newest row, as a line.
  const sp = g('8-spectre'), [y2, sw, sh] = toile(sp), der = f8Lignes[n - 1];
  y2.fillStyle = '#05070D'; y2.fillRect(0, 0, sw, sh); y2.strokeStyle = '#3FE0C8'; y2.lineWidth = 1.2; y2.beginPath();
  for (let k = 0; k < der.length; k++) { const px = sw * k / der.length, py = sh - 2 - der[k] / 255 * (sh - 4); if (k) y2.lineTo(px, py); else y2.moveTo(px, py); } y2.stroke();
}
g('8-cascade').addEventListener('click', ev => { if (!H) return; const b = ev.currentTarget.getBoundingClientRect(); f8Clic = Math.round((H.basse + (ev.clientX - b.left) / b.width * (H.haute - H.basse)) / 5) * 5; dessineCascade(); });
// The radar: an azimuthal map around the station — each one at its bearing and distance.
function dessineRadar() {
  const c = g('8-radar'); if (!c || !H) return;
  if (!c.clientWidth || c.offsetParent === null) return;
  const [x, w, h] = toile(c), R = Math.min(w, h) / 2 - 14, cx = w / 2, cy = h / 2, now = Date.now();
  if (R <= 20) return;
  x.clearRect(0, 0, w, h);
  const vus = new Map();
  for (const e of H.entendus) if (e.de && e.km != null && e.az != null && !vus.has(e.de) && now - e.t < 60 * 60000) vus.set(e.de, e);
  const l = [...vus.values()], maxKm = l.length ? Math.max(...l.map(e => e.km)) : 2000;
  const echelle = [2000, 5000, 10000, 15000, 20000].find(k => k >= maxKm) || 20000;
  x.strokeStyle = 'rgba(63,224,200,.18)'; x.fillStyle = 'rgba(154,167,180,.8)'; x.font = '10px Drapeaux,system-ui'; x.textAlign = 'center';
  // A square-root scale: the near stations (most of them) spread out, the far ones still fit.
  const rayon = km => Math.sqrt(Math.min(1, km / echelle)) * R;
  for (const f of [0.05, 0.25, 0.5, 1]) { const r = rayon(echelle * f); x.beginPath(); x.arc(cx, cy, r, 0, 7); x.stroke(); x.fillText(Math.round(echelle * f).toLocaleString() + ' km', cx, cy - r + 11); }
  for (let a = 0; a < 360; a += 30) { const r = a * Math.PI / 180; x.beginPath(); x.moveTo(cx, cy); x.lineTo(cx + R * Math.sin(r), cy - R * Math.cos(r)); x.stroke(); }
  x.fillStyle = '#E8F0F7'; x.font = 'bold 12px Drapeaux,system-ui';
  [['N', 0], ['E', 90], ['S', 180], ['O', 270]].forEach(([t, a]) => { const r = a * Math.PI / 180; x.fillText(t, cx + (R + 8) * Math.sin(r), cy - (R + 8) * Math.cos(r) + 4); });
  // The sweep: one turn per slot, as a radar's beam.
  const bal = (now % f8Slot()) / f8Slot() * 2 * Math.PI;
  const grad = x.createConicGradient ? x.createConicGradient(bal - Math.PI / 2 - 0.6, cx, cy) : null;
  if (grad && H.enMarche) { grad.addColorStop(0, 'rgba(63,224,200,0)'); grad.addColorStop(0.095, 'rgba(63,224,200,.16)'); grad.addColorStop(0.1, 'rgba(63,224,200,0)');
    x.fillStyle = grad; x.beginPath(); x.arc(cx, cy, R, 0, 7); x.fill(); }
  x.fillStyle = '#FFB454'; x.beginPath(); x.arc(cx, cy, 4, 0, 7); x.fill();
  const dernier = Math.max(0, ...H.entendus.map(e => e.t));
  l.sort((a, b) => a.t - b.t).forEach(e => {
    const r = e.az * Math.PI / 180, d = rayon(e.km), px = cx + d * Math.sin(r), py = cy - d * Math.cos(r);
    const age = Math.min(1, (now - e.t) / (60 * 60000)), alpha = 1 - age * 0.75, taille = 3 + Math.max(0, e.db + 22) / 5;
    const coul = e.moi ? '255,95,162' : e.nouveauPays ? '255,180,84' : e.cq ? '91,214,138' : e.fait ? '107,124,143' : '63,224,200';
    if (e.t === dernier && H.enMarche) { const ph = (now % 1500) / 1500; x.strokeStyle = 'rgba(' + coul + ',' + (1 - ph) + ')'; x.lineWidth = 2; x.beginPath(); x.arc(px, py, taille + ph * 14, 0, 7); x.stroke(); x.lineWidth = 1; }
    x.fillStyle = 'rgba(' + coul + ',' + alpha + ')';
    if (e.nouveauPays) { x.font = (taille * 3) + 'px Drapeaux,system-ui'; x.fillText('★', px, py + taille); } else { x.beginPath(); x.arc(px, py, taille, 0, 7); x.fill(); }
    if (now - e.t < 15 * 60000) { x.font = '10px Drapeaux,system-ui'; x.fillStyle = 'rgba(232,240,247,' + alpha + ')'; x.fillText(e.de, px, py - taille - 3); }
  });
  txt('8-radar-info', l.length ? l.length + ' stations placées · cercle extérieur ' + echelle.toLocaleString() + ' km' : '');
}
setInterval(() => { if (!g('v-ft8').hidden && H) dessineRadar(); }, 120);
// The messages, as a messenger: the conversations between two stations (both sides gathered),
// and the stream as it comes. Each message read for what it is: a call, a square, a report, the end.
let f8Vue = 'tableau', f8Conv = null, f8MsgVu = '';
function f8Montre(v) {
  f8Vue = v;
  g('8-tableau').hidden = v !== 'tableau'; g('8-messages').hidden = v !== 'messages';
  g('v-ft8').classList.toggle('f8-messages', v === 'messages');
  setTimeout(() => { try { dessineCascade(); dessineRadar(); } catch (x) {} }, 0);
  g('8-b-tableau').classList.toggle('actif', v === 'tableau'); g('8-b-messages').classList.toggle('actif', v === 'messages');
  try { localStorage.setItem('ft8-vue', v); } catch (x) {}
  f8MsgVu = ''; if (H) messagesFt8();
}
// What a message says, from its last word.
function f8Genre(e) {
  const m = e.texte.trim().split(/\s+/), fin = m[m.length - 1] || '';
  if (/^CQ\b/.test(e.texte)) return ['📣', 'appel', 'cq'];
  if (fin === 'RR73' || fin === '73') return ['🤝', fin === 'RR73' ? 'bien reçu, 73' : '73', 'fin'];
  if (fin === 'RRR') return ['✅', 'bien reçu', 'fin'];
  if (/^R[+-]\d+$/.test(fin)) return ['✅', 'report reçu ' + fin.slice(1) + ' dB', 'r'];
  if (/^[+-]\d+$/.test(fin)) return ['📶', 'report ' + fin + ' dB', 'rapport'];
  if (e.loc) return ['📍', 'carré ' + e.loc, 'carre'];
  return ['💬', '', 'autre'];
}
function f8Paire(e) { return e.de && e.a ? [e.de, e.a].sort().join('|') : null; }
function messagesFt8() {
  if (f8Vue !== 'messages' || !H) return;
  const l = H.entendus.slice().reverse();                               // oldest first
  const drap = new Map(); for (const e of l) if (e.de) drap.set(e.de, e);
  // The conversations: two stations, every message either sent; done when one says RR73 / 73 / RRR.
  const convs = new Map();
  for (const e of l) { const k = f8Paire(e); if (!k) continue;
    const c = convs.get(k) || { k: k, msgs: [], fin: false }; c.msgs.push(e); if (f8Genre(e)[2] === 'fin') c.fin = true; convs.set(k, c); }
  const liste = [...convs.values()].sort((a, b) => b.msgs[b.msgs.length - 1].t - a.msgs[a.msgs.length - 1].t);
  const cle = JSON.stringify([liste.length, l.length, l.length ? l[l.length - 1].t + l[l.length - 1].texte : '', f8Conv]);
  if (cle === f8MsgVu) return;
  f8MsgVu = cle;
  const fl = c => f8Drapeau((drap.get(c) || {}).drapeau);
  const heure = t => new Date(t).toISOString().slice(11, 16);
  g('8-convs').innerHTML = '<div class="m8-conv' + (f8Conv == null ? ' actif' : '') + '" onclick="f8Conv=null;f8MsgVu=\'\';messagesFt8()"><span class="m8-av">📡</span><span style="flex:1;min-width:0"><b>Au fil de l’eau</b><br><span class="petit">tous les messages, comme ils arrivent</span></span></div>' +
    liste.map(c => { const [a, b] = c.k.split('|'), der = c.msgs[c.msgs.length - 1], gr = f8Genre(der);
      return '<div class="m8-conv' + (f8Conv === c.k ? ' actif' : '') + '" onclick="f8Conv=\'' + c.k + '\';f8MsgVu=\'\';messagesFt8()">' +
        '<span class="m8-av">' + fl(a) + '<i>' + fl(b) + '</i></span><span style="flex:1;min-width:0"><b>' + esc(a) + ' ⇄ ' + esc(b) + '</b>' + (c.fin ? ' <span style="color:var(--vert)" title="QSO bouclé">✓</span>' : '') +
        (c.msgs.some(m => m.moi) ? ' <span class="f8-tag" style="color:#FF5FA2;border-color:#FF5FA2">m’appelle</span>' : '') +
        (c.msgs.some(m => m.guettee) ? ' <span class="f8-tag" style="color:#3FE0C8;border-color:#3FE0C8" title="Une station que tu guettes">👁</span>' : '') +
        '<br><span class="petit">' + gr[0] + ' ' + esc(der.de) + ' : ' + esc(gr[1] || der.texte) + '</span></span><span class="petit" style="text-align:right">' + heure(der.t) + '<br><span class="m8-n">' + c.msgs.length + '</span></span></div>'; }).join('');
  // The thread: one conversation as bubbles (one station left, the other right), or the stream.
  const c = f8Conv ? convs.get(f8Conv) : null;
  const msgs = c ? c.msgs : l.slice(-120);
  const gauche = c ? c.k.split('|')[0] : null;
  txt('8-fil-titre', c ? c.k.replace('|', ' ⇄ ') + (c.fin ? ' · QSO bouclé ✓' : ' · en cours') : 'Au fil de l’eau · ' + l.length + ' messages');
  let avant = 0;
  g('8-fil').innerHTML = msgs.map(e => {
    const gr = f8Genre(e), droite = c ? e.de !== gauche : false, coul = e.moi ? 'var(--rose)' : gr[2] === 'cq' ? 'var(--vert)' : gr[2] === 'fin' ? 'var(--cyan)' : 'var(--clair)';
    const sep = e.t !== avant ? '<div class="m8-heure">' + new Date(e.t).toISOString().slice(11, 19) + ' UTC</div>' : ''; avant = e.t;
    return sep + '<div class="m8-ligne' + (droite ? ' droite' : '') + '"><div class="m8-bulle' + (droite ? ' droite' : '') + (e.moi ? ' moi' : '') + (e.guettee ? ' guettee' : '') + '">' +
      '<div class="m8-qui">' + f8Drapeau(e.drapeau) + ' <b style="color:' + coul + '">' + esc(e.de || '?') + '</b>' + (e.a && !c ? ' <span class="petit">→ ' + esc(e.a) + '</span>' : '') +
      ' <span class="petit">' + (e.db > 0 ? '+' : '') + e.db + ' dB · ' + e.hz + ' Hz</span>' + f8Tags(e) + '</div>' +
      '<div class="m8-txt"><span class="m8-ico" title="' + esc(gr[1]) + '">' + gr[0] + '</span> ' + esc(e.texte) + '</div>' +
      (gr[1] ? '<div class="petit">' + esc(gr[1]) + (e.km != null && gr[2] !== 'fin' ? ' · ' + e.km.toLocaleString() + ' km' : '') + '</div>' : '') + '</div></div>';
  }).join('') || '<div class="petit" style="padding:20px">' + (H.enMarche ? 'rien encore' : 'à l’écoute, les messages arriveront ici') + '</div>';
  const fil = g('8-fil'); fil.scrollTop = fil.scrollHeight;
}
try { if (localStorage.getItem('ft8-vue') === 'messages') f8Montre('messages'); } catch (x) {}
async function ft8Marche() { await post('api/ft8?on=' + (H && H.enMarche ? 0 : 1) + '&mode=' + g('8-mode').value); ticFt8(); }
function noteFt8(call, loc, db) {
  versSaisie(); g('c-call').value = call; g('c-loc').value = loc || '';
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
  etatMarche('kiss', KS.connecte);
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
setTimeout(majFlux, 300);
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
