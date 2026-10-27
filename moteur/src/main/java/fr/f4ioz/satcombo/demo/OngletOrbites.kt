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
 * The « Orbites » tab as a part of its own: its markup, its styles and the
 * script of its dashboard (Tableau and its looks, the satellites' cards, the
 * Vitrine), around the globes of [OrbitesJs]. SatMe-PC's page ([PagePc])
 * and the F4IOZ web site (module `site`) both put it in.
 *
 * What the host page gives the script: `g(id)`, `esc`, `hm`, `txt`,
 * `GLOBE_MONDE` and [OrbitesJs]; `pliRedim()` (the page's panels, a no-op
 * elsewhere); `ES`, `ES_COUL`, `ES_NIVEAU` (the space weather, null when
 * there is none); `apMarque()` (the SSTV auto-pilot's mark, '' without one);
 * and the routes `api/satfiche` and `api/qr` (answering `{"ok":false}` is
 * enough). No `$` in the texts: they are Kotlin constants.
 */
object OngletOrbites {

    /** The tab's markup: the satellites, the scene, the dashboard's corners (`#v-orbites`). */
    const val HTML = """<div class="vue" id="v-orbites" hidden>
<div class="ob-boite">
  <div class="carte ob-liste">
    <div class="t">Satellites</div>
    <div class="ob-seg" role="group" aria-label="Quels satellites"><button type="button" data-ob-filtre="favoris" aria-pressed="true">Favoris</button><button type="button" data-ob-filtre="tous" aria-pressed="false">Tous (filtre)</button></div>
    <input type="search" id="ob-recherche" placeholder="Filtrer : ISS, RS-44, AO-…" aria-label="Filtrer les satellites" hidden>
    <div class="ob-sats" id="ob-sats"></div>
    <div class="ob-fiche" id="ob-fiche" hidden></div>
    <div class="ob-passages" id="ob-passages" hidden></div>
    <button type="button" id="ob-suivre" title="Le satellite choisi devient celui de l'onglet Suivi (Doppler, rotor)">Suivre le satellite choisi</button>
    <div class="petit aide">Coche pour afficher ; clic sur un nom : le choisir (le globe le suit, la vue Passage montre ses passages). Positions calculées dans ce navigateur (SGP4) avec les éléments orbitaux de la station : rien ne part sur Internet (sauf, si coché, la photo et le résumé des satellites connus, demandés une fois à Wikipédia puis gardés). Glisser pour tourner, molette pour zoomer.</div>
  </div>
  <div class="ob-scene">
    <canvas id="ob-3d" aria-label="Le globe et les satellites"></canvas>
    <canvas id="ob-2d" hidden aria-label="La carte des satellites et de leurs empreintes"></canvas>
    <div class="ob-haut">
      <div class="ob-seg ob-tb-garde" role="group" aria-label="Vue"><button type="button" data-ob-vue="globe">Globe 3D</button><button type="button" data-ob-vue="carte">Carte 2D</button><button type="button" data-ob-vue="passage">Passage</button></div>
      <div class="ob-seg ob-tb"><button type="button" id="ob-tableau-b" onclick="obTableau(true)" title="Tableau de bord : le globe en plein écran, avec les informations choisies (heure, prochains passages, satellite, station, Soleil et Lune, météo spatiale). Échap pour sortir">⛶ Tableau</button><label id="ob-theme-l" hidden title="L'allure du tableau de bord">Thème <select id="ob-theme" onchange="obTheme(this.value)"></select></label><button type="button" id="ob-infos-b" hidden aria-expanded="false" aria-controls="ob-infos" title="Les informations affichées sur le globe">Informations ▾</button><button type="button" id="ob-sortir" hidden onclick="obTableau(false)" title="Quitter le tableau de bord (Échap)">✕ Quitter</button>
        <div class="ob-infos" id="ob-infos" role="dialog" aria-label="Informations du tableau de bord" hidden></div></div>
      <div class="ob-seg" id="ob-styles" role="group" aria-label="Rendu de la Terre"></div>
      <div class="ob-seg"><label title="Villes, montagnes, monuments et décor (bateaux, forêts…) du dessin animé">Détails <select id="ob-detail"><option value="0">simples</option><option value="1">normaux</option><option value="2">riches</option></select></label><label title="Toutes les villes, les capitales des pays seulement, ou aucune">Villes <select id="ob-villes"><option value="toutes">toutes</option><option value="capitales">capitales</option><option value="aucune">aucune</option></select></label><label title="Le nom des villes, le drapeau de leur pays (capitales), ou les deux">Noms <select id="ob-etiq"><option value="nom">nom</option><option value="drapeau">drapeau</option><option value="nomdrapeau">nom et drapeau</option></select></label><label title="Les contacts du carnet qui ont un locator">Contacts <select id="ob-contacts"><option value="aucun">aucun</option><option value="points">points</option><option value="indicatifs">points et indicatifs</option><option value="locators">carrés locators</option></select></label><label title="Les monuments célèbres : aucun, les plus connus, ou tous (un à quelques-uns par pays)">Monuments <select id="ob-monuments" class="ob-mon-choix"><option value="aucun">aucun</option><option value="principaux">principaux</option><option value="tous">tous</option></select></label><label title="La taille des monuments sur le globe et la carte (plus grands : lisibles de loin)">Taille <select id="ob-montaille" class="ob-montaille-choix"><option value="0.7">petite</option><option value="1">normale</option><option value="1.6">grande</option><option value="2.4">très grande</option></select></label><label title="Les traces au sol des satellites, sur le globe et la carte : de discrètes (à gauche) à très marquées (épaisses, foncées, détourées)">Traces <input type="range" id="ob-traces" class="ob-traces-choix" min="0" max="100" step="5" aria-label="Traces au sol : discrètes à très marquées"></label><span class="ob-tb"><button type="button" id="ob-montypes-b" aria-expanded="false" aria-controls="ob-montypes" title="Les familles de monuments montrées">Types ▾</button><div class="ob-infos" id="ob-montypes" role="dialog" aria-label="Familles de monuments" hidden></div></span></div>
      <div class="ob-seg ob-temps"><button type="button" id="ob-pause" title="Pause">⏸</button><select id="ob-vitesse" aria-label="Vitesse du temps"><option value="1" selected>×1 réel</option><option value="10">×10</option><option value="60">×60</option><option value="300">×300</option><option value="1200">×1200</option></select><button type="button" id="ob-maint" title="Revenir à l'heure présente">Maintenant</button><span id="ob-horloge"></span></div>
      <div class="ob-cadreur" id="ob-cadreur" hidden>
        <div class="ob-seg" role="group" aria-label="Caméra du passage"><button type="button" data-ob-cam="poursuite">Poursuite</button><button type="button" data-ob-cam="sol">Depuis la station</button><button type="button" data-ob-cam="espace">Vue d'ensemble</button><button type="button" data-ob-cam="satellite" title="La Terre vue du satellite : son empreinte (l'horizon radio), les stations qui le voient, le jour et la nuit, sa trace au sol. Glisser pour regarder ailleurs, double-clic pour revenir">Vue du satellite</button></div>
        <div class="ob-seg"><label title="Plus haut : la caméra s'éloigne et montre plus de Terre (la molette aussi)">Hauteur <input type="range" id="ob-hauteur" class="ob-hauteur-r" min="0.6" max="8" step="0.1"></label></div>
      </div>
    </div>
    <div class="ob-bulle" id="ob-bulle" hidden></div>
    <div class="ob-banniere" id="ob-banniere" hidden></div>
    <div class="ob-instruments" id="ob-instruments" hidden>
      <div><canvas id="ob-ciel" aria-label="Le ciel de la station : la trace du passage"></canvas></div>
      <div><canvas id="ob-profil" aria-label="L'élévation au cours du passage"></canvas>
        <div class="ob-lecteur"><button type="button" class="ob-jouer" id="ob-jouer">▶ Jouer le passage</button><select id="ob-pvitesse" aria-label="Vitesse de l'animation"><option value="10">×10</option><option value="20" selected>×20</option><option value="40">×40</option><option value="80">×80</option></select><button type="button" id="ob-rejouer">⟲ Depuis l'AOS</button><button type="button" id="ob-direct" hidden title="Revenir à l'heure présente : le passage en cours, sinon le prochain">● En direct</button><span class="ob-mode" id="ob-mode"></span></div></div>
      <div><dl class="ob-mesures" id="ob-mesures"></dl></div>
    </div>
    <div class="ob-marque" aria-hidden="true"><img src="icone-192.png" alt=""><span>SatMe</span></div>
    <div class="ob-coin ob-coin-hg"><div class="ob-w" data-w="horloge" hidden></div><div class="ob-w" data-w="astres" hidden></div></div>
    <div class="ob-coin ob-coin-hd"><div class="ob-w" data-w="station" hidden></div><div class="ob-w" data-w="espace" hidden></div><div class="ob-w ob-w-pedago" data-w="fiche" hidden></div></div>
    <div class="ob-coin ob-coin-b ob-coin-bg"><div class="ob-w" data-w="passages" hidden></div></div>
    <div class="ob-coin ob-coin-b ob-coin-bd"><div class="ob-w ob-w-photo" data-w="photo" hidden></div><div class="ob-w" data-w="sat" hidden></div><div class="ob-w ob-w-pedago" data-w="savais" hidden></div></div>
    <div class="ob-defile" data-w="defile" hidden><div class="ob-defile-t"></div></div>
    <div class="ob-bulles" id="ob-bulles" aria-hidden="true"></div>
    <div class="ob-vitrine" id="ob-vitrine" hidden aria-live="polite"></div>
    <div class="ob-attente" id="ob-attente" hidden></div>
  </div>
</div>
</div>"""

    /** Its styles (the page's design tokens, [PagePc.STYLE_JETONS]). */
    const val CSS = """  /* Orbites: the satellites on the left (their passes under them in the Passage view), the stage filling the rest. */
  .ob-boite { position:relative; display:grid; grid-template-columns:250px minmax(0, 1fr); gap:10px; height:calc(100vh - 110px); min-height:480px; margin:12px 16px; }
  @media (min-width:1101px) and (min-height:600px) { .ob-boite { height:auto; position:absolute; inset:12px 16px; margin:0; } #v-orbites { position:relative; } }
  .ob-liste { min-height:0; display:flex; flex-direction:column; gap:8px; margin:0; }
  .ob-liste .t { margin:0; }
  .ob-sats { overflow:auto; min-height:96px; flex:1 1 0; display:flex; flex-direction:column; gap:2px; }
  .ob-sat { display:grid; grid-template-columns:18px 1fr auto; align-items:center; gap:2px 8px; padding:4px 6px; border-radius:var(--r1); cursor:pointer; }
  .ob-sat:hover, .ob-sat.choisi { background:var(--s3); }
  .ob-sat input { margin:0; }
  .ob-nom { font-weight:600; display:flex; align-items:center; gap:6px; min-width:0; }
  .ob-nom span:last-child { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .ob-nom small { color:var(--texte-3); font-weight:400; }
  .ob-pastille { width:9px; height:9px; border-radius:50%; flex:none; }
  .ob-el { font-family:var(--mono); font-size:var(--fs-xs); color:var(--texte-3); text-align:right; }
  .ob-el.haut { color:var(--warn); font-weight:600; }
  .ob-det { grid-column:2 / 4; font-family:var(--mono); font-size:var(--fs-xs); color:var(--texte-3); }
  .ob-det:empty { display:none; }
  .ob-passages { flex:0 1 auto; max-height:45%; overflow:auto; display:flex; flex-direction:column; gap:3px; border-top:1px solid var(--ligne); padding-top:8px; }
  .ob-pas { display:grid; grid-template-columns:1fr auto; gap:0 8px; text-align:left; background:transparent; border:1px solid transparent; border-radius:var(--r1); padding:4px 7px; font-family:var(--mono); font-size:var(--fs-s); }
  .ob-pas:hover { border-color:var(--ligne-forte); }
  .ob-pas[aria-pressed="true"] { border-color:var(--rx); background:rgba(63,224,200,.08); }
  .ob-max { color:var(--warn); font-weight:600; }
  .ob-sous { grid-column:1 / 3; color:var(--texte-3); font-size:var(--fs-xs); }
  .ob-scene { position:relative; min-height:0; border-radius:var(--r3); overflow:hidden; border:1px solid var(--ligne); background:#000; }
  .ob-scene > canvas { position:absolute; inset:0; width:100%; height:100%; display:block; }
  .ob-scene > canvas[hidden] { display:none; }
  #ob-3d { cursor:grab; touch-action:none; }
  .ob-haut { position:absolute; left:10px; right:10px; top:10px; z-index:3; display:flex; gap:8px; flex-wrap:wrap; align-items:flex-start; pointer-events:none; }
  .ob-haut > * { pointer-events:auto; }
  .ob-seg { display:flex; align-items:center; gap:2px; padding:3px; border-radius:var(--r2); background:rgba(15,22,29,.86); border:1px solid var(--ligne); flex-wrap:wrap; }
  .ob-seg button { border:0; background:transparent; padding:4px 9px; border-radius:var(--r1); font-size:var(--fs-s); font-weight:600; color:var(--texte-2); }
  .ob-seg button[aria-pressed="true"] { background:var(--s3); color:var(--rx); }
  .ob-seg label { display:flex; align-items:center; gap:6px; padding:0 6px; font-size:var(--fs-s); color:var(--texte-2); }
  .ob-seg select { padding:2px 6px; font-size:var(--fs-s); }
  .ob-traces-choix { width:84px; min-width:0; flex:none; }
  .ob-temps { margin-left:auto; font-family:var(--mono); font-size:var(--fs-s); }
  .ob-temps button, .ob-temps select { padding:3px 8px; font-size:var(--fs-s); }
  .ob-temps span { padding:0 4px; color:var(--texte); }
  .ob-cadreur { flex-basis:100%; display:flex; gap:8px; flex-wrap:wrap; }
  .ob-cadreur[hidden] { display:none; }
  .ob-bulle { position:absolute; z-index:4; pointer-events:none; background:rgba(14,23,32,.96); border:1px solid var(--ligne-forte); border-radius:6px; padding:4px 8px; font-family:var(--mono); font-size:var(--fs-xs); white-space:nowrap; }
  .ob-banniere { position:absolute; left:50%; top:22%; transform:translateX(-50%); z-index:2; text-align:center; pointer-events:none; font-size:clamp(22px, 3.2vw, 40px); font-weight:800; letter-spacing:.06em; color:#fff; text-shadow:0 0 18px rgba(63,224,200,.8), 0 2px 0 rgba(0,0,0,.6); white-space:nowrap; }
  .ob-banniere small { display:block; font-family:var(--mono); font-size:var(--fs-m); font-weight:600; letter-spacing:0; color:var(--texte); text-shadow:0 1px 2px #000; margin-top:6px; }
  .ob-banniere.ob-flash { animation:ob-flash 1.4s ease-out; }
  @keyframes ob-flash { 0% { transform:translateX(-50%) scale(1.5); opacity:0; } 25% { opacity:1; } 100% { transform:translateX(-50%) scale(1); } }
  .ob-instruments { position:absolute; left:10px; right:10px; bottom:10px; z-index:3; display:grid; grid-template-columns:170px minmax(0, 1fr) 200px; gap:10px; }
  .ob-instruments[hidden] { display:none; }
  .ob-instruments > div { background:rgba(10,15,20,.82); border:1px solid var(--ligne); border-radius:var(--r3); padding:8px; min-width:0; }
  .ob-instruments canvas { display:block; width:100%; height:140px; }
  .ob-lecteur { display:flex; gap:6px; margin-top:6px; flex-wrap:wrap; }
  .ob-lecteur button, .ob-lecteur select { padding:3px 9px; font-size:var(--fs-s); }
  .ob-jouer { background:var(--rx); color:#06131A; border-color:var(--rx); font-weight:600; }
  .ob-mode { align-self:center; margin-left:auto; font-family:var(--mono); font-size:var(--fs-s); color:var(--warn); font-weight:600; }
  .ob-mode.ob-live { color:var(--ok); }
  .ob-mesures { display:grid; grid-template-columns:auto 1fr; gap:3px 10px; font-family:var(--mono); font-size:var(--fs-s); margin:0; }
  .ob-mesures dt { color:var(--texte-3); }
  .ob-mesures dd { margin:0; text-align:right; font-weight:600; font-variant-numeric:tabular-nums; }
  /* The dashboard (Tableau): the globe on the whole screen, its information in the corners, readable from across the room. */
  .ob-tb { position:relative; }
  .ob-infos { position:absolute; right:0; top:calc(100% + 6px); z-index:6; width:max-content; max-width:min(300px, 80vw); background:var(--s2); border:1px solid var(--ligne-forte); border-radius:var(--r2); box-shadow:var(--ombre-pop); padding:8px 12px; }
  .ob-infos label { display:flex; align-items:center; gap:8px; min-height:30px; font-size:var(--fs-s); color:var(--texte); padding:0; cursor:pointer; }
  #v-orbites.ob-tableau { position:relative; min-height:100vh; background:var(--s0); }
  #v-orbites.ob-pseudo { position:fixed; inset:0; z-index:1000; overflow:hidden; }
  #v-orbites.ob-tableau .ob-boite { position:absolute; inset:0; margin:0; height:auto; min-height:0; grid-template-columns:minmax(0, 1fr); grid-template-rows:minmax(0, 1fr); }
  #v-orbites.ob-tableau .ob-liste { display:none; }
  #v-orbites.ob-tableau .ob-scene { height:auto; min-height:0; border:0; border-radius:0; order:0; }
  #v-orbites.ob-tableau .ob-haut { justify-content:center; transition:opacity .4s; }
  #v-orbites.ob-tableau .ob-haut > :not(.ob-tb):not(.ob-tb-garde):not(.ob-cadreur) { display:none; }
  #v-orbites.ob-tableau.ob-calme .ob-haut:not(:hover):not(:focus-within) { opacity:.2; }
  #v-orbites.ob-tableau.ob-calme .ob-scene { cursor:none; }
  #v-orbites.ob-tableau.ob-deplace:not(.ob-calme) #ob-3d { cursor:move; }
  .ob-coin { display:none; }
  #v-orbites.ob-tableau .ob-coin { position:absolute; z-index:3; display:flex; flex-direction:column; gap:10px; max-width:min(38%, 440px); pointer-events:none; overflow:hidden; }
  /* The full screen's mark: the logo and « SatMe », top left (above the first corner). */
  .ob-marque { display:none; }
  #v-orbites.ob-tableau .ob-marque { display:flex; align-items:center; gap:.4em; position:absolute; left:14px; top:12px; z-index:3; pointer-events:none;
    font-size:clamp(20px, 2vw, 34px); font-weight:800; letter-spacing:.02em; color:#fff; text-shadow:0 1px 3px rgba(0,0,0,.7); }
  #v-orbites.ob-tableau .ob-marque img { height:1.35em; width:1.35em; border-radius:22%; box-shadow:0 1px 4px rgba(0,0,0,.5); }
  /* Classique: its top row is the cards, so the mark goes bottom left, above the ticker; too narrow a screen: none (the toolbar is there). */
  #v-orbites.ob-th-classique.ob-tableau .ob-marque { top:auto; bottom:calc(14px + 2.1 * clamp(14px, 1.25vw, 22px)); font-size:clamp(16px, 1.4vw, 26px); }
  @media (max-width:700px) { #v-orbites.ob-tableau .ob-marque { display:none; } }
  .ob-coin-hg { left:14px; top:60px; max-height:calc(50% - 70px); } .ob-coin-hd { right:14px; top:60px; max-height:calc(50% - 70px); align-items:flex-end; }
  .ob-coin-bg { left:14px; bottom:14px; max-height:calc(50% - 24px); } .ob-coin-bd { right:14px; bottom:14px; max-height:calc(50% - 24px); align-items:flex-end; }
  /* A corner whose cards do not fit is made smaller (--ob-k, obAjusteCoin) rather than cut; anchored at the bottom, its content flows downwards so that an overflow is seen. */
  #v-orbites.ob-tableau .ob-instruments:not([hidden]) ~ .ob-coin-b { display:none; }
  .ob-w { background:rgba(8,13,18,.6); border:1px solid rgba(255,255,255,.12); border-radius:var(--r3); padding:10px 16px; color:var(--texte); font-size:calc(clamp(14px, 1.3vw, 21px) * var(--ob-k, 1)); line-height:1.3;
          text-shadow:0 1px 2px rgba(0,0,0,.6); -webkit-backdrop-filter:blur(3px); backdrop-filter:blur(3px); min-width:0; max-width:100%; box-sizing:border-box; }
  .ob-w[hidden] { display:none; }
  .ob-w-t { font-size:.68em; font-weight:700; letter-spacing:.12em; text-transform:uppercase; color:var(--texte-2); margin-bottom:2px; }
  .ob-w-gros { font-family:var(--mono); font-size:calc(clamp(30px, 4.4vw, 68px) * var(--ob-k, 1)); font-weight:800; line-height:1.05; font-variant-numeric:tabular-nums; letter-spacing:.02em; white-space:nowrap; }
  .ob-w-gros small { font-size:.36em; font-weight:600; color:var(--texte-2); margin-left:.3em; letter-spacing:0; }
  .ob-w-moy { font-family:var(--mono); font-size:1.5em; font-weight:700; font-variant-numeric:tabular-nums; }
  .ob-w-mono { font-family:var(--mono); font-variant-numeric:tabular-nums; }
  .ob-w-g { display:grid; grid-template-columns:auto auto; gap:1px 16px; font-family:var(--mono); font-variant-numeric:tabular-nums; }
  .ob-w-g span { color:var(--texte-2); } .ob-w-g b { text-align:right; }
  .ob-w-pas { display:grid; grid-template-columns:auto minmax(0, 1fr) auto auto; gap:2px 12px; align-items:baseline; }
  .ob-w-pas i { width:.6em; height:.6em; border-radius:50%; display:inline-block; }
  .ob-w-pas .n { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-weight:700; }
  .ob-w-pas .d { font-family:var(--mono); text-align:right; font-variant-numeric:tabular-nums; } .ob-w-pas .m { font-family:var(--mono); color:var(--warn); text-align:right; }
  .ob-w-pas .la { color:var(--ok); }
  @media (max-width:700px) { #v-orbites.ob-tableau .ob-coin { max-width:calc(50% - 20px); } .ob-w { padding:6px 10px; } }
  @media (pointer:coarse) { .ob-tb button { min-height:36px; } .ob-infos label { min-height:40px; } }
  .ob-infos { max-height:calc(100vh - 140px); overflow:auto; }
  .ob-infos .ob-place-r { margin:4px 0; }
  .ob-infos .ob-menu-t { font-size:var(--fs-xs); font-weight:700; letter-spacing:.1em; text-transform:uppercase; color:var(--texte-3); margin:8px 0 2px; }
  .ob-infos .ob-menu-t:first-child { margin-top:0; }
  .ob-mon-pastille { display:inline-block; width:10px; height:10px; border-radius:3px; flex:none; }
  .ob-seg .ob-tb > button { border:0; }
  #ob-theme-l { display:flex; align-items:center; gap:6px; padding:0 6px; font-size:var(--fs-s); color:var(--texte-2); }
  #ob-theme-l[hidden] { display:none; }
  /* The dashboard's extras: the Moon's face, the Kp bars, the ticker, the explanations. */
  .ob-lune { width:2.2em; height:2.2em; flex:none; vertical-align:middle; }
  .ob-defile .ob-lune { width:1.2em; height:1.2em; vertical-align:-.2em; }
  .ob-w-astres { display:flex; gap:12px; align-items:center; margin-bottom:4px; }
  .ob-kp { display:flex; align-items:flex-end; gap:2px; height:2.2em; margin-top:6px; }
  .ob-kp i { flex:1; border-radius:2px 2px 0 0; min-width:3px; }
  .ob-defile, .ob-bulles { display:none; }
  #v-orbites.ob-tableau .ob-defile:not([hidden]) { display:block; position:absolute; left:0; right:0; bottom:0; z-index:2; height:2.1em; overflow:hidden; pointer-events:none;
    font-size:clamp(14px, 1.25vw, 22px); color:var(--texte); background:rgba(8,13,18,.62); border-top:1px solid rgba(255,255,255,.14); }
  .ob-defile-t { position:absolute; left:100%; white-space:nowrap; line-height:2.1em; font-weight:600; animation:ob-defile 40s linear infinite; }
  .ob-defile-t span + span::before { content:'✦'; margin:0 1.4em; opacity:.6; }
  @keyframes ob-defile { from { transform:translateX(0); } to { transform:translateX(calc(-100% - 100vw)); } }
  #v-orbites.ob-tableau.ob-avec-defile .ob-coin-bg, #v-orbites.ob-tableau.ob-avec-defile .ob-coin-bd { bottom:calc(14px + 2.1 * clamp(14px, 1.25vw, 22px)); }
  #v-orbites.ob-tableau.ob-th-pedago .ob-bulles { display:block; position:absolute; inset:0; z-index:2; pointer-events:none; overflow:hidden; }
  .ob-pb { position:absolute; left:0; top:0; transition:transform .25s linear, opacity .9s ease; }
  .ob-pb.ob-pb-off { opacity:0; }
  .ob-pb[hidden] { display:none; }
  .ob-pb .pt { position:absolute; left:-8px; top:-8px; width:16px; height:16px; border-radius:50%; background:#FFD34D; border:3px solid #0a1230; box-sizing:border-box; animation:ob-pouls 1.6s ease-in-out infinite; }
  .ob-pb-emp .pt { background:#7CF29A; } .ob-pb-nuit .pt { background:#B48CFF; } .ob-pb-station .pt { background:#FFB454; }
  .ob-pb .bx { position:absolute; left:16px; bottom:12px; width:max-content; max-width:min(280px, 36vw); background:#fff; color:#0a1230; border:3px solid #0a1230; border-radius:18px 18px 18px 4px;
    padding:7px 12px; font:700 clamp(13px, 1.15vw, 20px)/1.25 "Baloo 2", Nunito, "Arial Rounded MT Bold", "Trebuchet MS", system-ui, sans-serif; box-shadow:0 4px 0 rgba(10,18,48,.35); }
  .ob-pb.g .bx { left:auto; right:16px; border-radius:18px 18px 4px 18px; }
  .ob-pb.b .bx { bottom:auto; top:12px; border-radius:4px 18px 18px 18px; } .ob-pb.g.b .bx { border-radius:18px 4px 18px 18px; }
  @keyframes ob-pouls { 0%, 100% { transform:scale(1); } 50% { transform:scale(1.35); } }
  .ob-w-texte { line-height:1.35; max-width:30em; }
  .ob-points { display:flex; gap:5px; margin-top:8px; } .ob-points i { width:8px; height:8px; border-radius:50%; background:currentColor; opacity:.25; } .ob-points i.ici { opacity:.9; }
  .ob-w.ob-entre { animation:ob-entre .5s cubic-bezier(.3, 1.6, .5, 1); }
  @keyframes ob-entre { 0% { transform:scale(.6); opacity:0; } 100% { transform:scale(1); opacity:1; } }
  /* Dessin animé (and Pédagogique): white bubbly cards, coloured badges, floating gently. */
  #v-orbites.ob-th-cartoon .ob-w, #v-orbites.ob-th-pedago .ob-w { background:rgba(255,255,255,.94); color:#0a1230; border:3px solid #0a1230; border-radius:22px; box-shadow:0 5px 0 rgba(10,18,48,.45);
    text-shadow:none; -webkit-backdrop-filter:none; backdrop-filter:none; font-family:"Baloo 2", Nunito, Fredoka, "Arial Rounded MT Bold", "Trebuchet MS", system-ui, sans-serif; animation:ob-flotte 6s ease-in-out infinite; }
  #v-orbites.ob-th-cartoon .ob-w.ob-entre, #v-orbites.ob-th-pedago .ob-w.ob-entre { animation:ob-entre .5s cubic-bezier(.3, 1.6, .5, 1), ob-flotte 6s ease-in-out .5s infinite; }
  #v-orbites.ob-th-cartoon .ob-coin .ob-w + .ob-w, #v-orbites.ob-th-pedago .ob-coin .ob-w + .ob-w { animation-delay:-3s; }
  @keyframes ob-flotte { 0%, 100% { transform:translateY(0); } 50% { transform:translateY(-4px); } }
  :is(.ob-th-cartoon, .ob-th-pedago) .ob-w-t { display:inline-block; color:#fff; background:#FF6B6B; padding:2px 12px; border-radius:999px; letter-spacing:.06em; margin-bottom:6px; }
  :is(.ob-th-cartoon, .ob-th-pedago) [data-w="station"] .ob-w-t { background:#3FA7F5; } :is(.ob-th-cartoon, .ob-th-pedago) [data-w="astres"] .ob-w-t { background:#9B7BD8; }
  :is(.ob-th-cartoon, .ob-th-pedago) [data-w="passages"] .ob-w-t { background:#22B573; } :is(.ob-th-cartoon, .ob-th-pedago) [data-w="espace"] .ob-w-t { background:#F2A61A; }
  :is(.ob-th-cartoon, .ob-th-pedago) [data-w="sat"] .ob-w-t { background:#0a1230; } :is(.ob-th-cartoon, .ob-th-pedago) [data-w="savais"] .ob-w-t { background:#F2B134; color:#0a1230; }
  :is(.ob-th-cartoon, .ob-th-pedago) :is(.ob-w-g span, .ob-w-gros small, .ob-w-mono, .petit) { color:#4a5878; }
  :is(.ob-th-cartoon, .ob-th-pedago) .ob-w-pas .m { color:#E8743B; } :is(.ob-th-cartoon, .ob-th-pedago) .ob-w-pas .la { color:#1f9d55; }
  :is(.ob-th-cartoon, .ob-th-pedago) .ob-defile:not([hidden]) { background:#FFD34D; color:#0a1230; border-top:3px solid #0a1230; }
  #v-orbites.ob-th-pedago .ob-w { font-size:calc(clamp(15px, 1.45vw, 25px) * var(--ob-k, 1)); }
  #v-orbites.ob-tableau [data-w="sat"] .ob-w-gros { font-size:calc(clamp(26px, 3.2vw, 56px) * var(--ob-k, 1)); }
  #v-orbites.ob-th-pedago .ob-coin { max-width:min(40%, 520px); }
  /* Classique radioamateur: dense panels in a band over a flat map, monospace on black, as the station clocks. */
  #v-orbites.ob-th-classique.ob-tableau .ob-scene { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); grid-template-rows:auto minmax(0, 1fr); gap:6px; padding:6px; box-sizing:border-box; background:#000; }
  #v-orbites.ob-th-classique.ob-tableau .ob-scene > canvas { position:relative; inset:auto; grid-column:1 / -1; grid-row:2; width:100%; height:100%; min-height:0; }
  #v-orbites.ob-th-classique.ob-tableau .ob-coin { position:static; grid-row:1; max-width:none; max-height:none; align-items:stretch; justify-content:flex-start; gap:6px; }
  #v-orbites.ob-th-classique.ob-tableau .ob-coin-hd { grid-column:2; } #v-orbites.ob-th-classique.ob-tableau .ob-coin-bg { grid-column:3; } #v-orbites.ob-th-classique.ob-tableau .ob-coin-bd { grid-column:4; }
  #v-orbites.ob-th-classique .ob-w { background:#05080c; border:1px solid #233140; border-radius:4px; padding:6px 10px; font-family:var(--mono); font-size:calc(clamp(12px, .92vw, 17px) * var(--ob-k, 1)); text-shadow:none; -webkit-backdrop-filter:none; backdrop-filter:none; }
  #v-orbites.ob-th-classique .ob-w-t { color:#00E5FF; letter-spacing:.08em; }
  .ob-th-classique [data-w="station"] .ob-w-t { color:#FFD000; } .ob-th-classique [data-w="astres"] .ob-w-t { color:#C792FF; } .ob-th-classique [data-w="passages"] .ob-w-t { color:#7CF29A; }
  .ob-th-classique [data-w="espace"] .ob-w-t { color:#FF9F43; } .ob-th-classique [data-w="sat"] .ob-w-t { color:#FF6B9A; }
  #v-orbites.ob-th-classique .ob-w-gros { font-size:calc(clamp(24px, 2.8vw, 50px) * var(--ob-k, 1)); color:#9CFF57; }
  #v-orbites.ob-th-classique .ob-haut { top:auto; bottom:44px; }
  #v-orbites.ob-th-classique.ob-tableau.ob-avec-defile .ob-coin-bg, #v-orbites.ob-th-classique.ob-tableau.ob-avec-defile .ob-coin-bd { bottom:auto; }
  #v-orbites.ob-th-classique .ob-defile:not([hidden]) { font-family:var(--mono); color:#FFD000; background:#05080c; }
  @media (max-aspect-ratio:1/1) {
    #v-orbites.ob-th-classique.ob-tableau .ob-scene { grid-template-columns:repeat(2, minmax(0, 1fr)); grid-template-rows:auto auto minmax(0, 1fr); }
    #v-orbites.ob-th-classique.ob-tableau .ob-scene > canvas { grid-row:3; }
    #v-orbites.ob-th-classique.ob-tableau .ob-coin-hd { grid-column:2; } #v-orbites.ob-th-classique.ob-tableau .ob-coin-bg { grid-column:1; grid-row:2; } #v-orbites.ob-th-classique.ob-tableau .ob-coin-bd { grid-column:2; grid-row:2; }
  }
  /* Nuit: dim, warm and big, readable from across a dark room. */
  #v-orbites.ob-th-nuit .ob-scene > canvas { filter:brightness(.55) saturate(.6); }
  #v-orbites.ob-th-nuit .ob-w { background:rgba(0,0,0,.55); border-color:rgba(255,90,60,.16); color:#D27A6A; text-shadow:none; }
  #v-orbites.ob-th-nuit :is(.ob-w-t, .ob-w-g span, .ob-w-gros small, .petit) { color:#8A4A40; }
  #v-orbites.ob-th-nuit .ob-w-gros { color:#E08A70; font-weight:600; }
  #v-orbites.ob-th-nuit [data-w="horloge"] .ob-w-gros { font-size:calc(clamp(36px, 5.2vw, 100px) * var(--ob-k, 1)); }
  #v-orbites.ob-th-nuit.ob-tableau .ob-coin { max-width:min(46%, 640px); }
  #v-orbites.ob-th-nuit :is(.ob-w-pas .m, .ob-w-pas .la) { color:#C9785A; }
  #v-orbites.ob-th-nuit .ob-defile:not([hidden]) { color:#D27A6A; background:rgba(0,0,0,.6); border-top-color:rgba(255,90,60,.16); }
  /* Rétro: parchment cards, serif ink, an old map. */
  #v-orbites.ob-th-retro .ob-scene { background:#2b1d0e; }
  #v-orbites.ob-th-retro .ob-w { background:rgba(239,224,184,.95); color:#3B2410; border:2px solid #6B3F17; border-radius:6px; text-shadow:none; -webkit-backdrop-filter:none; backdrop-filter:none;
    box-shadow:inset 0 0 0 3px rgba(239,224,184,1), inset 0 0 0 4px #6B3F17, 0 3px 10px rgba(0,0,0,.4); font-family:Georgia, "Times New Roman", serif; }
  #v-orbites.ob-th-retro :is(.ob-w-gros, .ob-w-mono, .ob-w-g, .ob-w-moy, .ob-w-pas .d, .ob-w-pas .m) { font-family:Georgia, "Times New Roman", serif; }
  #v-orbites.ob-th-retro .ob-w-t { color:#8A2D12; font-variant:small-caps; text-transform:none; letter-spacing:.08em; font-size:.85em; }
  #v-orbites.ob-th-retro :is(.ob-w-g span, .ob-w-gros small, .petit) { color:#6B4A2A; } #v-orbites.ob-th-retro .ob-w-pas .m { color:#8A2D12; }
  #v-orbites.ob-th-retro .ob-defile:not([hidden]) { color:#3B2410; background:rgba(239,224,184,.92); border-top:2px solid #6B3F17; font-family:Georgia, serif; }
  /* The satellites' cards: the tab's (under the list), the dashboard's (a photo beside its facts), the Vitrine's. */
  .ob-fiche { flex:0 1 auto; max-height:38%; overflow:auto; border-top:1px solid var(--ligne); padding-top:6px; font-size:var(--fs-s); }
  .ob-fiche[hidden] { display:none; }
  .ob-fiche-h { display:flex; align-items:center; gap:6px; justify-content:space-between; }
  .ob-fiche-pli { background:transparent; border:0; padding:2px 0; font-weight:700; color:var(--texte); text-align:left; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; }
  .ob-fiche-h label { display:flex; align-items:center; gap:4px; font-size:var(--fs-xs); color:var(--texte-3); flex:none; }
  .ob-photo { margin:6px 0 4px; }
  .ob-photo img, .ob-photo svg { display:block; width:100%; max-height:120px; object-fit:contain; border-radius:var(--r2); background:#0a1230; }
  /* A photo whole (never cropped), as wide as its height allows. */
  .ob-photo img { width:auto; max-width:100%; height:auto; margin:0 auto; background:none; }
  .ob-photo.ob-dessin svg { object-fit:contain; background:linear-gradient(#16264f, #0a1230); padding:6px; box-sizing:border-box; }
  .ob-photo figcaption { font-size:10px; line-height:1.25; color:var(--texte-3); margin-top:2px; overflow-wrap:anywhere; }
  .ob-photo figcaption a, .ob-credit a { color:inherit; }
  .ob-fiche-t { font-weight:700; margin:2px 0; }
  .ob-fiche-g { display:grid; grid-template-columns:auto minmax(0, 1fr); gap:1px 8px; }
  .ob-fiche-g span { color:var(--texte-3); } .ob-fiche-g b { font-weight:600; }
  .ob-fiche-f { font-family:var(--mono); font-size:var(--fs-xs); margin-top:4px; }
  .ob-fiche-a { margin-top:4px; }
  .ob-fiche-r { margin-top:4px; color:var(--texte-2); } .ob-credit { font-size:var(--fs-xs); color:var(--texte-3); }
  .ob-w-photo { width:min(440px, 100%); }
  #v-orbites .ob-w-photo.ob-trop { display:none; }
  /* The dashboard's card: the photo beside the name and the facts, the story under both (the whole width, fewer lines). */
  .ob-fiche-tb { display:grid; grid-template-columns:minmax(0, 42%) minmax(0, 1fr); gap:4px 10px; align-items:start; font-size:max(11px, .78em); overflow-wrap:anywhere; }
  .ob-fiche-tb .ob-fiche-c { display:contents; }
  .ob-fiche-tb .ob-photo { margin:0; grid-row:1 / span 3; } .ob-fiche-tb .ob-photo img, .ob-fiche-tb .ob-photo svg { max-height:calc(min(18vh, 170px) * var(--ob-k, 1)); }
  .ob-fiche-tb .ob-fiche-c > * { grid-column:2; margin:0; } .ob-fiche-tb .ob-fiche-c > .ob-fiche-a { grid-column:1 / -1; }
  .ob-fiche-tb .ob-fiche-t { font-size:1.15em; } .ob-fiche-tb .ob-fiche-g { font-size:.92em; }
  .ob-fiche-tb .ob-fiche-a { font-size:.92em; display:-webkit-box; -webkit-line-clamp:4; -webkit-box-orient:vertical; overflow:hidden; }
  #v-orbites.ob-th-classique .ob-fiche-tb .ob-fiche-a { -webkit-line-clamp:3; }
  @media (max-width:700px) { .ob-fiche-tb { grid-template-columns:minmax(0, 1fr); } .ob-fiche-tb .ob-photo { grid-row:auto; } .ob-fiche-tb .ob-fiche-c > * { grid-column:1; } }
  /* Vitrine: big cards over the globe turning by itself, readable from across a hall; no menus once the mouse rests. */
  .ob-vitrine { display:none; }
  #v-orbites.ob-tableau.ob-th-vitrine .ob-vitrine:not([hidden]) { display:block; position:absolute; inset:0; z-index:2; pointer-events:none; overflow:hidden;
    font:700 clamp(17px, 1.85vw, 38px)/1.3 "Baloo 2", Nunito, Fredoka, "Arial Rounded MT Bold", "Trebuchet MS", system-ui, sans-serif; color:#0a1230; }
  #v-orbites.ob-th-vitrine.ob-calme .ob-haut:not(:hover):not(:focus-within) { opacity:0; }
  .ob-vit-tete { position:absolute; left:2.5vw; right:2.5vw; top:calc(52px + 1vh); display:flex; justify-content:space-between; align-items:center; gap:1em; color:#fff;
    font-size:1.35em; font-weight:800; text-shadow:0 3px 0 #0a1230, 0 0 18px rgba(0,0,0,.6); }
  .ob-vit-tete span:first-child { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; }
  .ob-vit-heure { font-family:var(--mono); flex:none; }
  /* The card's size (« Taille de l'encart »: --vit-k) and its side; the globe stands beside it (obPlace). */
  .ob-vit-carte { position:absolute; left:3vw; top:52%; transform:translateY(-50%); width:calc(min(48vw, 960px) * var(--vit-k, 1)); max-height:calc(100% - 15vh - 120px); overflow:hidden; box-sizing:border-box;
    font-size:calc(100% * var(--vit-k, 1));
    background:rgba(255,255,255,.96); border:4px solid #0a1230; border-radius:28px; padding:clamp(14px, 1.6vw, 34px); box-shadow:0 8px 0 rgba(10,18,48,.45); animation:ob-vit-entre .7s cubic-bezier(.3, 1.5, .5, 1); }
  @keyframes ob-vit-entre { 0% { opacity:0; transform:translateY(-50%) translateX(-6vw) scale(.92); } 100% { opacity:1; transform:translateY(-50%); } }
  .ob-vitrine.ob-vit-droite .ob-vit-carte:not(.ob-vit-legende) { left:auto; right:3vw; animation-name:ob-vit-entre-d; }
  @keyframes ob-vit-entre-d { 0% { opacity:0; transform:translateY(-50%) translateX(6vw) scale(.92); } 100% { opacity:1; transform:translateY(-50%); } }
  .ob-vit-carte.ob-vit-legende { top:auto; bottom:calc(2.6em + 2vh); left:50%; transform:translateX(-50%); width:max-content; max-width:92vw; text-align:center; border-radius:999px; padding:.4em 1.2em; animation:ob-entre .6s ease-out; }
  .ob-vit-t { display:inline-block; white-space:nowrap; background:#FF6B6B; color:#fff; border-radius:999px; padding:.1em .8em; font-size:.8em; margin-bottom:.4em; }
  .ob-vit-gros { font-size:2.3em; font-weight:800; line-height:1.05; color:#E8743B; overflow-wrap:anywhere; }
  .ob-vit-vivant { font-size:1.35em; font-weight:800; margin:.15em 0; }
  .ob-vit-sous { font-weight:600; }
  .ob-vit-ind { font-family:var(--mono); font-size:1.6em; font-weight:800; letter-spacing:.06em; }
  .ob-vit-lien { font-family:var(--mono); font-size:.8em; overflow-wrap:anywhere; margin-top:.3em; color:#3a4a6b; }
  .ob-vit-duo { display:grid; grid-template-columns:minmax(0, .8fr) minmax(0, 1.4fr); gap:1em; align-items:center; }
  .ob-vit-passage .ob-vit-gros { font-size:2.6em; } .ob-vit-passage .ob-vit-vivant { font-size:1.5em; line-height:1.15; }
  .ob-vit-contact .ob-vit-duo { grid-template-columns:minmax(0, 1fr) auto; }
  .ob-vit-qr { width:calc(min(26vh, 20vw) * var(--vit-k, 1)); height:auto; background:#fff; border-radius:12px; border:3px solid #0a1230; }
  .ob-vit-liste { margin:.2em 0 0; padding-left:1.1em; } .ob-vit-liste li { margin:.25em 0; }
  .ob-vitrine .ob-photo { margin:0; } .ob-vitrine .ob-photo img, .ob-vitrine .ob-photo svg { max-height:min(34vh, 9em); border-radius:18px; }
  .ob-vitrine .ob-photo figcaption { font-size:clamp(10px, .7vw, 14px); color:#4a5878; font-weight:500; }
  .ob-vit-sat .ob-photo { float:left; width:44%; margin:0 1em .4em 0; }
  .ob-vitrine .ob-fiche-t { font-size:1.5em; font-weight:800; color:#E8743B; line-height:1.1; }
  .ob-vitrine .ob-fiche-g { font-size:.78em; gap:0 .7em; } .ob-vitrine .ob-fiche-g span { color:#4a5878; } .ob-vitrine .ob-fiche-a { font-size:.85em; margin-top:.4em; }
  @media (max-aspect-ratio:1/1) {
    .ob-vit-carte { left:3vw; right:3vw; width:auto; top:auto; bottom:calc(2.6em + 2vh); transform:none; max-height:calc(52% * var(--vit-k, 1)); }
    .ob-vitrine.ob-vit-droite .ob-vit-carte:not(.ob-vit-legende) { left:3vw; right:3vw; animation-name:ob-vit-entre; }
    @keyframes ob-vit-entre { 0% { opacity:0; transform:translateY(4vh); } 100% { opacity:1; transform:none; } }
    .ob-vit-sat .ob-photo { width:40%; }
  }
  @media (prefers-reduced-motion:reduce) { .ob-vit-carte { animation:none !important; } }
  /* Classique: the ticker a row of its own under the map (nothing over the map's bottom), the bar over the map. */
  #v-orbites.ob-th-classique.ob-tableau .ob-scene { grid-template-rows:auto minmax(0, 1fr) auto; }
  #v-orbites.ob-th-classique.ob-tableau .ob-defile:not([hidden]) { position:relative; left:auto; right:auto; bottom:auto; grid-column:1 / -1; grid-row:3; margin:0 -6px -6px; }
  #v-orbites.ob-th-classique.ob-tableau .ob-haut { bottom:calc(14px + 2.1 * clamp(14px, 1.25vw, 22px)); }
  #v-orbites.ob-th-classique.ob-tableau.ob-calme .ob-haut:not(:hover):not(:focus-within) { opacity:0; }
  @media (max-aspect-ratio:1/1) {
    #v-orbites.ob-th-classique.ob-tableau .ob-scene { grid-template-rows:auto auto minmax(0, 1fr) auto; }
    #v-orbites.ob-th-classique.ob-tableau .ob-defile:not([hidden]) { grid-row:4; }
  }
  @media (prefers-reduced-motion:reduce) { #v-orbites .ob-w, #v-orbites .ob-w.ob-entre, .ob-pb .pt { animation:none !important; } .ob-defile-t { animation-duration:120s !important; } }
  .ob-attente { position:absolute; inset:0; display:grid; place-items:center; color:var(--texte-2); z-index:5; background:rgba(0,0,0,.6); padding:20px; text-align:center; }
  @media (max-width:900px) {
    .ob-boite { grid-template-columns:minmax(0, 1fr); height:auto; }
    .ob-scene { height:72vh; min-height:480px; order:-1; }
    .ob-liste { max-height:420px; }
    .ob-temps { margin-left:0; }
    .ob-instruments { grid-template-columns:1fr 1fr; }
    .ob-instruments > div:nth-child(2) { grid-column:1 / 3; order:3; }
    .ob-instruments canvas { height:110px; }
  }"""

    /** The dashboard (Tableau), the satellites' cards and the Vitrine. */
    const val JS = """// ------------------------------------------------------------------ the Orbites tab as a dashboard (Tableau)
/*
 * The globe (or the map) on the whole screen — the browser's full screen, or the window's when it is refused — and in
 * its corners the information chosen, each kept in this browser: the time, the next passes of the satellites shown,
 * the satellite chosen, the station and its Sun, the space weather the page already has (ES). Échap, or ✕, leaves.
 */
const OB_INFOS = [['horloge', 'Heure UTC et locale'], ['station', 'Station : lever et coucher du Soleil'], ['astres', 'Soleil et Lune (phase, sur la carte)'], ['passages', 'Prochains passages'],
  ['sat', 'Satellite choisi : az, él, distance, Doppler'], ['photo', 'Photo et fiche des satellites (tour à tour)'], ['espace', 'Météo spatiale (Kp, flux, rayons X)'], ['defile', 'Bandeau : le satellite du moment']];
/* The looks: the Earth's (a look of the globe), the view, the corners' own style; the panels each one hides at first. */
const OB_THEMES = {
  cartoon: { nom: 'Dessin animé', style: 'cartoon', vue: 'globe', tourne: true, off: [] },
  classique: { nom: 'Classique radioamateur', style: 'realiste', vue: 'carte', off: ['photo'] },
  nuit: { nom: 'Nuit (sobre)', style: 'points', vue: 'globe', off: ['astres', 'espace', 'defile', 'photo'] },
  retro: { nom: 'Rétro', style: 'vintage', vue: 'carte', off: ['espace'] },
  pedago: { nom: 'Pédagogique', style: 'cartoon', vue: 'globe', tourne: true, pedago: true, off: ['station', 'astres', 'espace', 'sat', 'photo'] },
  // A stand's poster (a club's day, a fair): big, turning by itself, its own cards one after another; nothing to touch.
  vitrine: { nom: 'Vitrine (stand, forum)', style: 'cartoon', vue: 'globe', tourne: true, vitrine: true, off: ['horloge', 'station', 'astres', 'passages', 'sat', 'photo', 'espace'] }
};
/* The explanations of the Pédagogique look: pointed at what they explain (the first four), or on cards turning by themselves. */
const OB_PEDAGO = [['sat', 'Le satellite et son orbite'], ['emp', 'L’empreinte'], ['nuit', 'La ligne jour/nuit'], ['station', 'La station'], ['aos', 'AOS et LOS'],
  ['terre', 'Pourquoi la trace se décale'], ['incl', 'L’inclinaison'], ['vitesse', 'Altitude et vitesse'], ['doppler', 'L’effet Doppler'], ['savais', 'Le savais-tu ?']];
const OB_SAVAIS = [
  'Le premier satellite, Spoutnik 1, a été lancé le 4 octobre 1957. Son « bip-bip » s’écoutait avec un simple récepteur radio.',
  'OSCAR 1, le premier satellite radioamateur, a été lancé en décembre 1961, quatre ans seulement après Spoutnik.',
  'La Station spatiale internationale fait environ 15 tours et demi de la Terre chaque jour.',
  'Des élèves parlent avec les astronautes de l’ISS par radioamateur : c’est le programme ARISS.',
  'Un satellite géostationnaire est à 35\u00a0786\u00a0km d’altitude : il tourne aussi vite que la Terre et semble immobile dans le ciel.',
  'QO-100 est le premier satellite radioamateur géostationnaire. Lancé en 2018, il est placé vers 26° Est, au-dessus de l’Afrique.',
  'Plus un satellite est haut, plus il va lentement : la Lune met environ 27\u00a0jours pour faire le tour de la Terre.',
  'Un CubeSat de base est un cube de 10\u00a0cm de côté. Beaucoup de satellites radioamateurs sont des CubeSats.',
  'La radio voyage à la vitesse de la lumière : le signal d’un satellite à 2\u00a0000\u00a0km arrive en moins de 7 millièmes de seconde.',
  'On voit l’ISS à l’œil nu, à l’aube ou au crépuscule : le Soleil l’éclaire encore alors que tu es déjà dans l’ombre.',
  'Vue de l’ISS, l’empreinte fait environ 4\u00a0500\u00a0km de large : de Paris, on peut parler à quelqu’un à Moscou ou au Maroc.',
  'L’orbite de l’ISS penche de 51,6° : on la voit depuis la France, mais jamais depuis le pôle Nord.',
  'À 435 MHz, l’effet Doppler d’un satellite en orbite basse peut décaler la fréquence d’environ 10\u00a0kHz.',
  'La ligne grise, le long de l’aube et du crépuscule, est souvent un bon moment pour les liaisons lointaines en ondes courtes.',
  'Un satellite ne tombe pas parce qu’il va très vite : il tombe sans cesse vers la Terre… et la rate à chaque fois !',
  'La Terre fait un tour sur elle-même en 23\u00a0h\u00a056\u00a0min : c’est pour ça que les satellites semblent se décaler vers l’ouest.'
];
let obInfos = {}, obTbT = 0, obCalmeT = 0, obBulT = 0, obTh = 'cartoon', obVues = {}, obPeda = {}, obFiche = 0, obSav = Math.floor(Math.random() * OB_SAVAIS.length);
try { obInfos = JSON.parse(localStorage.getItem('ob-infos2')) || {}; obVues = JSON.parse(localStorage.getItem('ob-th-vues')) || {}; obPeda = JSON.parse(localStorage.getItem('ob-pedago')) || {};
  obTh = localStorage.getItem('ob-theme') || 'cartoon'; } catch (x) {}
if (!OB_THEMES[obTh]) obTh = 'cartoon';
const obGarde = (k, v) => { try { localStorage.setItem(k, typeof v === 'string' ? v : JSON.stringify(v)); } catch (x) {} };
/** A panel shown in this look: as ticked, else the look's default. */
const obInfo = k => { const v = obInfos[obTh + '.' + k]; return v != null ? v : !OB_THEMES[obTh].off.includes(k); };
const obPedaOn = k => obPeda[k] !== false;
Object.keys(OB_THEMES).forEach(k => { const o = document.createElement('option'); o.value = k; o.textContent = OB_THEMES[k].nom; g('ob-theme').appendChild(o); });
g('ob-theme').value = obTh;
/** What the globe takes from the look: its Earth, its view (as last chosen in it), turning by itself, the Sun and Moon, the explanations. */
function obThOpts() {
  const T = OB_THEMES[obTh];
  return { style: T.style, vue: obVues[obTh] || T.vue, tourne: !!T.tourne, astres: obInfo('astres') || !!T.pedago, pedago: !!T.pedago, vitrine: !!T.vitrine, place: obPlace,
    surVue: v => { if (v !== 'passage') { obVues[obTh] = v; obGarde('ob-th-vues', obVues); } } };
}
function obTableau(on) {
  const v = g('v-orbites');
  if (on && !v.classList.contains('ob-tableau')) {
    v.classList.add('ob-tableau', 'ob-th-' + obTh);
    const fs = v.requestFullscreen ? v.requestFullscreen({ navigationUI: 'hide' }) : Promise.reject(new Error('non'));
    fs.catch(() => v.classList.add('ob-pseudo'));
    Orbites.tableauMode(obThOpts()); Orbites.tempsReel();
    clearInterval(obTbT); obTbT = setInterval(obTbMaj, 1000);
    clearInterval(obBulT); obBulT = setInterval(obBulles, 250);
    v.addEventListener('pointermove', obReveil); obReveil();
  } else if (!on && v.classList.contains('ob-tableau')) {
    if (document.fullscreenElement === v) document.exitFullscreen().catch(() => {});
    v.classList.remove('ob-tableau', 'ob-pseudo', 'ob-calme', 'ob-avec-defile', 'ob-deplace', ...Object.keys(OB_THEMES).map(k => 'ob-th-' + k)); obDeplace = false;
    clearInterval(obTbT); clearInterval(obBulT); clearTimeout(obCalmeT); v.removeEventListener('pointermove', obReveil);
    g('ob-infos').hidden = true; g('ob-bulles').innerHTML = ''; g('ob-vitrine').hidden = true; g('ob-vitrine').innerHTML = '';
    Orbites.tableauMode(null);
  }
  const t = v.classList.contains('ob-tableau');
  g('ob-tableau-b').hidden = t; g('ob-infos-b').hidden = g('ob-sortir').hidden = g('ob-theme-l').hidden = !t;
  g('ob-infos-b').setAttribute('aria-expanded', !g('ob-infos').hidden);
  obTbMaj();
  if (t) g('ob-sortir').focus(); else g('ob-tableau-b').focus();
  pliRedim();
}
/** Another look for the dashboard (kept in this browser). */
function obTheme(k) {
  if (!OB_THEMES[k]) return;
  const v = g('v-orbites');
  v.classList.remove('ob-th-' + obTh); obTh = k; obGarde('ob-theme', k); g('ob-theme').value = k;
  if (!v.classList.contains('ob-tableau')) return;
  v.classList.add('ob-th-' + k); g('ob-bulles').innerHTML = ''; g('ob-vitrine').innerHTML = ''; obVitT = 0;
  v.querySelectorAll('.ob-w').forEach(w => { w.dataset.h = ''; });
  Orbites.tableauMode(obThOpts()); obTbMaj(); pliRedim();
  if (!g('ob-infos').hidden) obMenu();
}
/** The bar fades after a few seconds without the mouse; a move brings it back. */
function obReveil() {
  const v = g('v-orbites'); v.classList.remove('ob-calme'); clearTimeout(obCalmeT);
  obCalmeT = setTimeout(() => { if (v.classList.contains('ob-tableau') && g('ob-infos').hidden) v.classList.add('ob-calme'); }, 4000);
}
document.addEventListener('fullscreenchange', () => {
  const v = g('v-orbites');
  if (document.fullscreenElement !== v && v.classList.contains('ob-tableau') && !v.classList.contains('ob-pseudo')) obTableau(false);
});
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape' || !g('v-orbites').classList.contains('ob-tableau')) return;
  if (!g('ob-infos').hidden) { g('ob-infos').hidden = true; g('ob-infos-b').setAttribute('aria-expanded', false); g('ob-infos-b').focus(); return; }
  obTableau(false);
});
/** A choice in the dashboard's menu: [[value, text]], the one chosen, what follows. */
function obChoix(texte, l, v, change) {
  const e = document.createElement('label'), s = document.createElement('select');
  l.forEach(o => { const p = document.createElement('option'); p.value = o[0]; p.textContent = o[1]; s.appendChild(p); });
  s.value = v; s.onchange = () => change(s.value); e.append(document.createTextNode(texte), s); g('ob-infos').appendChild(e);
}
/** The dashboard's menu: the panels, the landmarks, the explanations (Pédagogique), the globe's place. */
function obMenu() {
  const m = g('ob-infos'), titre = t => { const d = document.createElement('div'); d.className = 'ob-menu-t'; d.textContent = t; m.appendChild(d); };
  const coche = (texte, on, change) => { const l = document.createElement('label'), c = document.createElement('input');
    c.type = 'checkbox'; c.checked = on; c.onchange = () => change(c.checked); l.append(c, document.createTextNode(texte)); m.appendChild(l); };
  m.innerHTML = '';
  titre('Panneaux · ' + OB_THEMES[obTh].nom);
  OB_INFOS.forEach(i => coche(i[1], obInfo(i[0]), on => { obInfos[obTh + '.' + i[0]] = on; obGarde('ob-infos2', obInfos);
    if (i[0] === 'astres') Orbites.tableauMode(obThOpts()); obTbMaj(); }));
  coche('Photos et résumés de Wikipédia (demandés une fois, puis gardés)', obPhotos, on => obPhotosChoix(on));
  if (typeof IssDirect !== 'undefined') IssDirect.menu(titre, coche);
  if (OB_THEMES[obTh].vitrine) {
    titre('Vitrine : le club');
    [['club', 'Nom du club', 'Radio-club de…'], ['indicatif', 'Indicatif', 'F6KXX'], ['lien', 'Lien (site, page ; en QR code)', 'https://…'], ['message', 'Message', 'Venez nous voir !']].forEach(c => {
      const l = document.createElement('label'), i = document.createElement('input');
      l.className = 'ob-vit-champ'; i.type = 'text'; i.maxLength = c[0] === 'lien' ? 200 : 120; i.placeholder = c[2]; i.value = obVit[c[0]] || '';
      i.oninput = () => { obVit[c[0]] = i.value.trim(); obGarde('ob-vitrine', obVit); g('ob-vitrine').innerHTML = ''; };
      l.append(document.createTextNode(c[1]), i); m.appendChild(l);
    });
    titre('Vitrine : l’encart');
    obChoix('Taille ', [['petite', 'petite'], ['moyenne', 'moyenne'], ['grande', 'grande']], obVit.taille || 'moyenne', v => { obVit.taille = v; obGarde('ob-vitrine', obVit); g('ob-vitrine').innerHTML = ''; });
    // Its other side: the globe back beside it.
    obChoix('Côté ', [['gauche', 'à gauche'], ['droite', 'à droite']], obVit.cote || 'gauche', v => { obVit.cote = v; obGarde('ob-vitrine', obVit); obPlaceRemet(); g('ob-vitrine').innerHTML = ''; });
    coche('Réception sur le stand (« venez écouter ! »)', !!obVit.rx, on => { obVit.rx = on; obGarde('ob-vitrine', obVit); g('ob-vitrine').innerHTML = ''; });
  }
  titre('Le globe');
  coche('Le déplacer : glisser (ou Maj + glisser, deux doigts), molette : sa taille, double clic : à sa place', obDeplace, on => { obDeplace = on; g('v-orbites').classList.toggle('ob-deplace', on); });
  const rb = document.createElement('button'); rb.type = 'button'; rb.className = 'ob-place-r'; rb.textContent = 'Remettre le globe à sa place'; rb.onclick = obPlaceRemet; m.appendChild(rb);
  if (OB_THEMES[obTh].pedago) { titre('Explications'); OB_PEDAGO.forEach(i => coche(i[1], obPedaOn(i[0]), on => { obPeda[i[0]] = on; obGarde('ob-pedago', obPeda); obTbMaj(); obBulles(); })); }
  titre('Monuments');
  const l = document.createElement('label'), s = document.createElement('select');
  s.className = 'ob-mon-choix'; s.innerHTML = '<option value="aucun">aucun</option><option value="principaux">principaux</option><option value="tous">tous</option>';
  s.value = Orbites.monumentsActifs(); s.onchange = () => Orbites.monuments(s.value); l.append(document.createTextNode('Montrer '), s); m.appendChild(l);
  const l2 = document.createElement('label'), s2 = document.createElement('select');
  s2.className = 'ob-montaille-choix'; s2.innerHTML = '<option value="0.7">petite</option><option value="1">normale</option><option value="1.6">grande</option><option value="2.4">très grande</option>';
  s2.value = String(Orbites.monumentsTailleActive()); s2.onchange = () => Orbites.monumentsTaille(+s2.value); l2.append(document.createTextNode('Taille '), s2); m.appendChild(l2);
  m.appendChild(rangeTraces());
  const ty = document.createElement('div'); m.appendChild(ty); Orbites.typesMonuments(ty);
}
g('ob-infos-b').onclick = ev => {
  ev.stopPropagation();
  const m = g('ob-infos'), ouvre = m.hidden;
  m.hidden = !ouvre; g('ob-infos-b').setAttribute('aria-expanded', ouvre);
  if (!ouvre) return;
  obMenu(); m.querySelector('input').focus();
};
document.addEventListener('pointerdown', ev => { const m = g('ob-infos');
  if (!m.hidden && !m.contains(ev.target) && ev.target !== g('ob-infos-b')) { m.hidden = true; g('ob-infos-b').setAttribute('aria-expanded', false); } });
const obDuree = ms => { const s = Math.max(0, Math.round(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
  return h ? h + ' h ' + String(m).padStart(2, '0') : m ? m + ' min ' + String(s % 60).padStart(2, '0') : s + ' s'; };
const obDeux = n => String(n).padStart(2, '0');
const obNombre = (n, d) => n.toLocaleString('fr-FR', { maximumFractionDigits: d || 0, minimumFractionDigits: d || 0 });
/** The Moon's phase in words. */
function obPhaseNom(p) {
  return p < .03 || p > .97 ? 'nouvelle lune' : p < .22 ? 'premier croissant' : p < .28 ? 'premier quartier' : p < .47 ? 'gibbeuse croissante'
    : p < .53 ? 'pleine lune' : p < .72 ? 'gibbeuse décroissante' : p < .78 ? 'dernier quartier' : 'dernier croissant';
}
/** The Moon's face as a little picture (lit part from obLunePath). */
const obLuneSvg = p => '<svg class="ob-lune" viewBox="-11 -11 22 22" aria-hidden="true"><circle r="10" fill="#2b2f3a" stroke="rgba(0,0,0,.5)"/><path d="' + obLunePath(p, 10) + '" fill="#F2F0E6"/></svg>';
/** The nearest capital (and how far, km) to a place: what the satellite flies over, for the ticker. */
function obPres(lat, lon) {
  let best = null, dm = 1e9;
  const r = Math.PI / 180, km = (a, b) => 6371 * Math.acos(Math.min(1, Math.sin(lat * r) * Math.sin(a * r) + Math.cos(lat * r) * Math.cos(a * r) * Math.cos((b - lon) * r)));
  (GLOBE_MONDE.capitales || []).forEach(c => { const d = km(c[1], c[2]); if (d < dm) { dm = d; best = c; } });
  return best ? { nom: best[0], pays: best[3], km: dm } : null;
}
const obDrapeau = iso => iso && iso.length === 2 ? String.fromCodePoint(0x1F1E6 + iso.charCodeAt(0) - 65, 0x1F1E6 + iso.charCodeAt(1) - 65) : '';
/** The corners, once a second while the dashboard is shown. */
function obTbMaj() {
  const v = g('v-orbites');
  // Another tab opened (a shortcut, the menu): the dashboard leaves the screen with it.
  if (v.hidden && v.classList.contains('ob-tableau')) { obTableau(false); return; }
  const on = v.classList.contains('ob-tableau'), peda = on && !!OB_THEMES[obTh].pedago;
  const w = k => v.querySelector('[data-w="' + k + '"]'), montre = (k, html) => { const e = w(k), vu = on && (k === 'fiche' || k === 'savais' ? peda && obPedaOn(k === 'savais' ? 'savais' : 'fiche') : obInfo(k)) && html != null;
    if (vu && e.hidden) { e.classList.remove('ob-entre'); void e.offsetWidth; e.classList.add('ob-entre'); }
    e.hidden = !vu; if (vu && e.dataset.h !== html) { e.dataset.h = html; if (k === 'defile') obDefile(e, html); else e.innerHTML = html; } };
  if (!on) { v.querySelectorAll('[data-w]').forEach(e => { e.hidden = true; }); return; }
  const now = new Date(), d = Orbites.tableau(), A = Orbites.astres(), dense = obTh === 'classique';
  montre('horloge', '<div class="ob-w-t">Heure</div><div class="ob-w-gros">' + obDeux(now.getUTCHours()) + ':' + obDeux(now.getUTCMinutes()) + ':' + obDeux(now.getUTCSeconds()) + '<small>UTC</small></div>' +
    '<div class="ob-w-mono">' + now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) + ' locale · ' + now.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }) + '</div>');
  // The Sun and the Moon: their height at the station, the Moon's phase.
  const so = A.soleil, lu = A.lune;
  montre('astres', '<div class="ob-w-t">Soleil et Lune</div><div class="ob-w-astres">' + obLuneSvg(lu.phase) + '<div><b>' + obPhaseNom(lu.phase) + '</b><div class="ob-w-mono">éclairée à ' + Math.round(lu.lumiere * 100) + ' % · ' + obNombre(lu.age, 1) + ' j</div></div></div>' +
    '<div class="ob-w-g">' + (so.ici ? '<span>☀ Soleil</span><b>' + (so.ici.el >= 0 ? so.ici.el.toFixed(0) + '°' : 'couché') + ' · az ' + so.ici.az.toFixed(0) + '°</b>' +
      '<span>☾ Lune</span><b>' + (lu.ici.el >= 0 ? lu.ici.el.toFixed(0) + '°' : 'couchée') + ' · az ' + lu.ici.az.toFixed(0) + '°</b>' : '') +
    (dense ? '<span>Sous le Soleil</span><b>' + Math.abs(so.lat).toFixed(1) + '° ' + (so.lat >= 0 ? 'N' : 'S') + ' ' + Math.abs(so.lon).toFixed(1) + '° ' + (so.lon >= 0 ? 'E' : 'O') + '</b>' : '') + '</div>');
  if (!d) { ['passages', 'sat', 'photo', 'station', 'espace', 'fiche', 'defile'].forEach(k => montre(k, null)); obVitrine(null, []); return; }
  // The next passes: under way (green), then the countdowns.
  const l = d.vu ? Orbites.prochains(dense ? 8 : peda ? 4 : 6) : [];
  obVitrine(d, l);
  if (!d.vu) montre('passages', '<div class="ob-w-t">Prochains passages</div><div class="petit">Le locator de la station manque (Réglages › Station).</div>');
  else montre('passages', '<div class="ob-w-t">Prochains passages</div>' + (l.length ? '<div class="ob-w-pas">' + l.map(p => {
      const la = p.aos <= d.t;
      return '<i style="background:' + p.couleur + '"></i><span class="n">' + esc(p.nom) + '</span><span class="d' + (la ? ' la' : '') + '">' +
        (la ? '↑ encore ' + obDuree(p.los - d.t) : p.aos - d.t < 3600000 ? 'dans ' + obDuree(p.aos - d.t) : hm(p.aos)) + apMarque(p.catnum, p.aos, p.los) + '</span><span class="m">' + p.max.toFixed(0) + '°</span>';
    }).join('') + '</div>' : '<div class="petit">Aucun dans les 24 h (les satellites cochés, sinon les favoris).</div>'));
  // The satellite chosen in the list.
  const e = d.e, s = d.sat;
  montre('sat', s ? '<div class="ob-w-t"><span style="color:' + s.couleur + '">●</span> ' + esc(s.nom) + '</div>' + (e ? (d.vu ?
    '<div class="ob-w-gros" style="color:' + (e.el > 0 ? 'var(--ok)' : 'inherit') + '">' + e.el.toFixed(1) + '°<small>él · az ' + e.az.toFixed(0) + '°</small></div>' : '') +
    '<div class="ob-w-g">' + (d.vu ? '<span>Distance</span><b>' + Math.round(e.dist).toLocaleString('fr-FR') + ' km</b>' : '') +
    '<span>Altitude</span><b>' + Math.round(e.alt).toLocaleString('fr-FR') + ' km</b><span>Sous lui</span><b>' + e.lat.toFixed(1) + '° ' + e.lon.toFixed(1) + '°</b>' +
    (d.dop != null ? '<span>Descente</span><b>' + d.dop.toFixed(4) + ' MHz</b><span>Doppler</span><b>' + ((d.dop - s.dl) * 1e3 >= 0 ? '+' : '') + ((d.dop - s.dl) * 1e3).toFixed(1) + ' kHz</b>' : '') + '</div>'
    : '<div class="petit">Position inconnue.</div>') : null);
  // The photo card: the satellite chosen, then those of the next passes, in turn (15 s each).
  if (obInfo('photo')) {
    const lp = [];
    if (s) lp.push(s);
    l.forEach(p => { if (!lp.some(q => q.nom === p.nom)) lp.push(p); });
    const q = lp.length ? lp[Math.floor(now.getTime() / 15000) % lp.length] : null;
    montre('photo', q ? '<div class="ob-w-t">' + (q === s ? 'Le satellite choisi' : 'Bientôt au-dessus de nous') + '</div><div class="ob-fiche-tb">' + obCarteSat(q, obSatFiche(q), { court: true }) + '</div>' : null);
  } else montre('photo', null);
  // The station: its Sun now, its next sunrise and sunset, dawn and dusk.
  const sj = A.jour, quand = t => t ? hm(t) + (t - d.t < 3 * 3600000 ? ' <small>(dans ' + obDuree(t - d.t) + ')</small>' : '') : '—';
  montre('station', '<div class="ob-w-t">Station</div><div class="ob-w-moy">' + esc(d.indicatif || '—') + (d.locator ? ' <small class="petit">' + esc(d.locator) + '</small>' : '') + '</div>' +
    (d.soleil ? '<div class="ob-w-mono">' + (d.soleil.el > 0 ? '☀ jour' : d.soleil.el > -6 ? '◐ crépuscule' : d.soleil.el > -18 ? '◑ crépuscule (nuit proche)' : '☾ nuit') + ' · Soleil ' + d.soleil.el.toFixed(0) + '° az ' + d.soleil.az.toFixed(0) + '°</div>' : '') +
    (sj ? '<div class="ob-w-g"><span>Lever</span><b>' + quand(sj.lever) + '</b><span>Coucher</span><b>' + quand(sj.coucher) + '</b>' +
      (dense || obTh === 'retro' ? '<span>Aube civile</span><b>' + quand(sj.aube) + '</b><span>Crépuscule</span><b>' + quand(sj.crepuscule) + '</b>' : '') + '</div>'
      + (sj.lever == null && sj.coucher == null ? '<div class="petit">' + (d.soleil && d.soleil.el > 0 ? 'Jour polaire : le Soleil ne se couche pas.' : 'Nuit polaire : le Soleil ne se lève pas.') + '</div>' : '') : ''));
  // The space weather, as the page last read it (Météo spatiale tab).
  const k = typeof ES !== 'undefined' && ES ? ES.kp : null, f = ES && ES.f107, x = ES && ES.rayons, vt = ES && ES.vent, au = ES && ES.aurore;
  const barres = ES && ES.barres ? ES.barres.slice(-16) : [];
  montre('espace', '<div class="ob-w-t">Météo spatiale</div>' + (k || f || x ? '<div class="ob-w-g">' +
    (k ? '<span>Kp</span><b style="color:' + (ES_COUL[k.niveau] || '') + '">' + esc(k.texte) + ' · ' + esc(ES_NIVEAU[k.niveau] || '') + '</b>' : '') +
    (f ? '<span>Flux 10,7 cm</span><b>' + esc(f.sfu) + ' SFU</b>' : '') + (x ? '<span>Rayons X</span><b>' + esc(x.classe) + '</b>' : '') +
    (vt && vt.vitesse != null ? '<span>Vent solaire</span><b>' + Math.round(vt.vitesse) + ' km/s' + (vt.bz != null ? ' · Bz ' + vt.bz : '') + '</b>' : '') +
    (au && ES.station ? '<span>Aurore ici</span><b>' + esc(au.ici) + ' %</b>' : '') + '</div>' +
    (barres.length > 3 ? '<div class="ob-kp" title="Kp par 3 h, prévision en clair">' + barres.map(b => '<i style="height:' + Math.max(6, b.kp / 9 * 100).toFixed(0) + '%;background:' + (ES_COUL[b.niveau] || '#5BD68A') + (b.nature === 'prevu' ? ';opacity:.45' : '') + '"></i>').join('') + '</div>' : '')
    : '<div class="petit">Pas de données (onglet Météo spatiale).</div>'));
  // The ticker: the satellite of the moment, what it flies over, the next pass, the Moon.
  if (on && obInfo('defile')) {
    const bouts = [];
    if (s && e) { const p = obPres(e.lat, e.lon);
      bouts.push('🛰 ' + esc(s.nom) + ' file à ' + Math.round(e.alt) + ' km d’altitude' + (p ? (p.km < 600 ? ', au-dessus des environs de ' : ', à ' + obNombre(Math.round(p.km / 50) * 50) + ' km de ') + esc(p.nom) + ' ' + obDrapeau(p.pays) : '')); }
    l.slice(0, 3).forEach(p => bouts.push((p.aos <= d.t ? '📡 ' + esc(p.nom) + ' passe en ce moment, ' : '⏱ ' + esc(p.nom) + ' à ' + hm(p.aos) + ', ') + 'jusqu’à ' + p.max.toFixed(0) + '°'));
    bouts.push(obLuneSvg(lu.phase) + ' Lune : ' + obPhaseNom(lu.phase) + ', éclairée à ' + Math.round(lu.lumiere * 100) + ' %');
    if (k) bouts.push('☀ Kp ' + esc(k.texte) + (f ? ' · flux ' + esc(f.sfu) + ' SFU' : ''));
    montre('defile', '<span>' + bouts.join('</span><span>') + '</span>');
  } else montre('defile', null);
  v.classList.toggle('ob-avec-defile', on && !w('defile').hidden);
  // Pédagogique: a card explaining one thing at a time (the next one every 15 s), and a fact.
  if (peda) {
    const cartes = obCartes(d, l).filter(c => obPedaOn(c[0]));
    if (cartes.length) { if (now.getSeconds() % 15 === 0) obFiche++; const c = cartes[obFiche % cartes.length];
      montre('fiche', '<div class="ob-w-t">' + c[1] + '</div><div class="ob-w-texte">' + c[2] + '</div><div class="ob-points">' + cartes.map((q, i) => '<i' + (q === c ? ' class="ici"' : '') + '></i>').join('') + '</div>'); }
    else montre('fiche', null);
    if (now.getSeconds() % 12 === 0) obSav++;
    montre('savais', '<div class="ob-w-t">💡 Le savais-tu ?</div><div class="ob-w-texte">' + esc(OB_SAVAIS[obSav % OB_SAVAIS.length]) + '</div>');
  } else { montre('fiche', null); montre('savais', null); }
  // Every card whole: a corner too full made smaller; the photo card left out only when even that is not enough.
  v.querySelectorAll('.ob-coin').forEach(co => {
    if (getComputedStyle(co).display === 'none') return;
    const ph = co.querySelector('.ob-w-photo');
    if (ph && !ph.hidden && ph.classList.contains('ob-trop')) {
      // Room again for the photo? Tried now, given up at once if not (nothing drawn in between).
      const k = co.dataset.k;
      ph.classList.remove('ob-trop');
      if (!obAjusteCoin(co)) { ph.classList.add('ob-trop'); co.dataset.k = k || 1; co.style.setProperty('--ob-k', k || 1); }
    }
    if (!obAjusteCoin(co) && ph && !ph.hidden && !ph.classList.contains('ob-trop')) { ph.classList.add('ob-trop'); obAjusteCoin(co); }
  });
}
const OB_K_MIN = .75;
/**
 * A dashboard corner's cards made smaller (--ob-k, down to OB_K_MIN) until all of them fit, never cut; bigger again a
 * step a second when there is room. True when they fit.
 */
function obAjusteCoin(co) {
  const deborde = () => co.scrollHeight > co.clientHeight + 1 || co.scrollWidth > co.clientWidth + 1;
  const pose = x => { co.style.setProperty('--ob-k', x); co.dataset.k = x; };
  let k = +co.dataset.k || 1;
  if (k < 1 && !deborde()) { const k2 = Math.min(1, Math.round((k + .05) * 100) / 100); pose(k2); if (deborde()) pose(k); else k = k2; }
  while (k > OB_K_MIN && deborde()) { k = Math.max(OB_K_MIN, Math.round((k - .05) * 100) / 100); pose(k); }
  return !deborde();
}
/** The ticker's text, changed at the end of a run (not in the middle of a sentence). */
function obDefile(e, html) {
  const t = e.firstElementChild;
  if (!t.innerHTML) { t.innerHTML = html; t.style.animationDuration = Math.max(25, html.length / 18) + 's'; return; }
  t.dataset.suite = html;
  t.onanimationiteration = () => { if (t.dataset.suite) { t.innerHTML = t.dataset.suite; t.dataset.suite = ''; } };
}
/** The explanation cards, with the figures of the satellite chosen: [key, title, html]. */
function obCartes(d, l) {
  const s = d.sat, e = d.e, n = s ? '<b>' + esc(s.nom) + '</b>' : 'le satellite', o = [];
  const p = l[0];
  o.push(['aos', 'AOS et LOS', 'AOS : le satellite se lève à l’horizon, on commence à l’entendre. LOS : il se couche, c’est fini !' +
    (p ? '<br>' + (p.aos <= d.t ? '📡 <b>' + esc(p.nom) + '</b> est là : encore ' + obDuree(p.los - d.t) + '.' : '⏱ Prochain AOS : <b>' + esc(p.nom) + '</b> dans ' + obDuree(p.aos - d.t) + ', jusqu’à ' + p.max.toFixed(0) + '° de haut.') : '')]);
  if (s && !s.geo && d.decalage) o.push(['terre', 'Pourquoi la trace se décale ?', 'Pendant que ' + n + ' fait un tour (' + Math.round(s.periode) + ' min), la Terre tourne en dessous de ' +
    obNombre(d.decalage, 1) + '° vers l’est. Le tour suivant passe donc plus à l’ouest : c’est la Terre qui bouge !']);
  if (s && s.incl != null) { const m = s.incl > 90 ? 180 - s.incl : s.incl;
    o.push(['incl', 'L’inclinaison', s.geo ? n + ' tourne au-dessus de l’équateur, aussi vite que la Terre : il reste au même endroit du ciel.'
      : 'L’orbite de ' + n + ' penche de ' + obNombre(s.incl, 1) + '° par rapport à l’équateur : il ne survole jamais les régions au-delà de ' + obNombre(m, 0) + '° de latitude Nord ou Sud.' +
        (s.incl > 90 ? ' Au-delà de 90°, il tourne même « à l’envers », d’est en ouest : son orbite est dite rétrograde.' : '')]); }
  if (e && e.vitesse) { const kmh = e.vitesse * 3600, pm = 660 / e.vitesse;
    o.push(['vitesse', 'Altitude et vitesse', n + ' vole à <b>' + obNombre(Math.round(e.alt)) + ' km</b> de haut (' + obNombre(Math.round(e.alt * 1000 / 8849)) + ' fois l’Everest) à <b>' + obNombre(Math.round(kmh / 100) * 100) + ' km/h</b>.' +
      (s.geo ? '' : '<br>Paris–Marseille en ' + (pm >= 60 ? Math.floor(pm / 60) + ' min ' + obDeux(Math.round(pm % 60)) : Math.round(pm) + ' s') + ' ; le tour de la Terre en ' + Math.round(s.periode) + ' min.') +
      '<br>Sa zone d’écoute fait ' + obNombre(Math.round(e.rayon * 2 / 100) * 100) + ' km de large.']); }
  o.push(['doppler', 'L’effet Doppler', 'Comme la sirène d’une ambulance : la fréquence monte quand le satellite approche, et descend quand il s’éloigne.' +
    (d.dop != null && s ? '<br>En ce moment : <b>' + ((d.dop - s.dl) * 1e3 >= 0 ? '+' : '') + obNombre((d.dop - s.dl) * 1e3, 1) + ' kHz</b> sur ' + obNombre(s.dl, 3) + ' MHz.' : '')]);
  return o;
}
/*
 * The explanations pointed at the globe or the map (four times a second: they follow the camera), one at a time so the
 * map stays clear: each shown a few seconds, faded out, the next one after a pause, and round again.
 */
const OB_BUL_MONTRE = 7000, OB_BUL_PAUSE = 2500;
function obBulles() {
  const v = g('v-orbites'), b = g('ob-bulles'), on = v.classList.contains('ob-tableau') && !!OB_THEMES[obTh].pedago;
  if (!on) { if (b.firstChild) b.innerHTML = ''; return; }
  const R = Orbites.reperes() || {}, d = Orbites.tableau(), w = b.clientWidth, h = b.clientHeight;
  const s = d && d.sat, e = d && d.e;
  const textes = {
    sat: s ? '🛰 ' + esc(s.nom) + ' : le trait est son orbite, un tour en ' + Math.round(s.periode) + ' min' : null,
    emp: e ? 'La zone qui peut entendre le satellite (' + obNombre(Math.round(e.rayon * 2 / 100) * 100) + ' km de large)' : null,
    nuit: 'La ligne jour/nuit : ici, le Soleil se lève ou se couche',
    station: 'Ta station' + (d && d.indicatif ? ' ' + esc(d.indicatif) : '') + ' : elle entend le satellite quand sa zone la recouvre'
  };
  const cles = ['sat', 'emp', 'nuit', 'station'].filter(k => obPedaOn(k) && textes[k]);
  const vu = k => { const p = R[k]; return !!p && p[0] > 4 && p[1] > 4 && p[0] < w - 4 && p[1] < h - 4; };
  // Whose turn: the one of this round, else the next one in view.
  const n = Date.now(), tour = Math.floor(n / (OB_BUL_MONTRE + OB_BUL_PAUSE)), dans = n % (OB_BUL_MONTRE + OB_BUL_PAUSE);
  let ici = null;
  if (dans < OB_BUL_MONTRE) for (let i = 0; i < cles.length && !ici; i++) { const k = cles[(tour + i) % cles.length]; if (vu(k)) ici = k; }
  ['sat', 'emp', 'nuit', 'station'].forEach(k => {
    let el = b.querySelector('[data-p="' + k + '"]');
    const p = R[k];
    if (!cles.includes(k) || !vu(k)) { if (el) el.hidden = true; return; }
    if (!el) { el = document.createElement('div'); el.className = 'ob-pb ob-pb-off ob-pb-' + k; el.dataset.p = k; el.innerHTML = '<i class="pt"></i><span class="bx"></span>'; b.appendChild(el); }
    el.hidden = false; el.classList.toggle('ob-pb-off', k !== ici);
    if (el.dataset.t !== textes[k]) { el.dataset.t = textes[k]; el.querySelector('.bx').innerHTML = textes[k]; }
    el.classList.toggle('g', p[0] > w * .62); el.classList.toggle('b', p[1] < h * .3);
    el.style.transform = 'translate(' + Math.round(p[0]) + 'px,' + Math.round(p[1]) + 'px)';
  });
}
// ------------------------------------------------------------------ the satellites' cards: a real photo, what to know
/*
 * The well-known satellites (FichesSat on the station): SatMe's facts, the station's frequencies, Wikipédia's summary
 * and a free photo with its author and licence, asked once by the station and kept; without them, SatMe's drawing.
 */
const obFiches = new Map();
let obPhotos = true, obFicheSat = null, obFicheT = 0;
try { obPhotos = localStorage.getItem('ob-photos') !== '0'; } catch (x) {}
const obAttr = x => esc(x).replace(/"/g, '&quot;');
/** [s] = {nom, catnum}'s card as last read (null at first), read again while its photo is on its way. */
function obSatFiche(s) {
  if (!s) return null;
  const k = (s.catnum || 0) + '|' + s.nom, now = Date.now();
  let c = obFiches.get(k);
  const vieux = c && !c.encours && now - c.t > (c.d ? (c.d.attente ? 3000 : 3600000) : 60000);
  if (!c || vieux) {
    if (!c) { c = { d: null, t: 0 }; obFiches.set(k, c); }
    c.encours = true;
    fetch('api/satfiche?catnum=' + (s.catnum || 0) + '&nom=' + encodeURIComponent(s.nom) + (obPhotos ? '' : '&internet=0'))
      .then(r => r.json()).then(d => { if (d && d.ok) c.d = d; }).catch(() => {})
      .finally(() => { c.encours = false; c.t = Date.now(); if (obFicheSat && obFicheSat.nom === s.nom) obFicheTab(obFicheSat); });
  }
  return c.d;
}
/** Photos and summaries asked from Wikipédia or not (kept in this browser); what the station already keeps is shown either way. */
function obPhotosChoix(on) {
  obPhotos = !!on; obGarde('ob-photos', on ? '1' : '0'); obFiches.clear();
  if (obFicheSat) obFicheTab(obFicheSat);
  obTbMaj();
}
/** SatMe's own drawing of a satellite, when there is no photo. */
function obDessinSat(couleur) {
  const c = /^#[0-9a-fA-F]{3,8}$/.test(couleur || '') ? couleur : '#3FE0C8';
  return '<svg class="ob-dessin-sat" viewBox="0 0 160 100" role="img" aria-label="Dessin d’un satellite"><g stroke="#0a1230" stroke-width="3" stroke-linejoin="round" stroke-linecap="round">' +
    '<rect x="6" y="34" width="46" height="32" rx="3" fill="#3a6fd8"/><path d="M21 34v32M37 34v32M6 50h46" stroke-width="1.5"/>' +
    '<rect x="108" y="34" width="46" height="32" rx="3" fill="#3a6fd8"/><path d="M123 34v32M139 34v32M108 50h46" stroke-width="1.5"/>' +
    '<path d="M52 50h10M98 50h10"/><rect x="62" y="28" width="36" height="44" rx="6" fill="' + c + '"/><path d="M80 28V12"/>' +
    '<circle cx="80" cy="9" r="4.5" fill="#FFD34D"/><path d="M71 72l-7 18M89 72l7 18" stroke-width="2.5"/><circle cx="80" cy="45" r="7" fill="#fff"/></g></svg>';
}
/**
 * A satellite's card: its photo (author, licence) or the drawing, its facts, its frequencies, a story; [o.court] a short
 * one (the dashboard), [o.liens] with links (the tab), [o.grand] the Vitrine's.
 */
function obCarteSat(s, d, o) {
  o = o || {};
  const f = d && d.faits, ph = obPhotos && d && d.photo, lien = (u, t) => o.liens && u ? '<a href="' + obAttr(u) + '" target="_blank" rel="noopener">' + esc(t) + '</a>' : esc(t);
  const fig = ph ? '<figure class="ob-photo"><img src="' + obAttr(ph.url) + '" alt="' + obAttr((d.nom || s.nom) + ' (photo)') + '"><figcaption>📷 ' + esc(ph.auteur || 'auteur inconnu') + ' · ' +
      lien(ph.lienLicence, ph.licence || 'licence libre') + ' · ' + lien(ph.source, 'Wikimedia Commons') + '</figcaption></figure>'
    : '<figure class="ob-photo ob-dessin">' + obDessinSat(s.couleur) + '<figcaption>Dessin SatMe' + (d && d.attente ? ' · photo en chemin…' : '') + '</figcaption></figure>';
  const gr = [], ligne = (a, b) => { if (b) gr.push('<span>' + a + '</span><b>' + esc(b) + '</b>'); };
  const tout = !o.court && !o.grand;
  if (f) { if (!o.court) ligne('Pays', f.pays); if (tout) ligne('Opérateur', f.operateur); ligne('Lancement', f.lancement); ligne('Mission', f.mission); if (tout) ligne('Orbite', f.orbite); }
  const fq = o.court ? '' : ((d && d.frequences) || []).filter(q => q.dl).slice(0, 4)
    .map(q => esc(q.desc) + ' : ' + Number(q.dl).toFixed(3) + ' MHz' + (q.mode ? ' (' + esc(q.mode) + ')' : '')).join('<br>');
  const r = d && d.resume, ex = r && r.extrait ? (r.extrait.length > 320 ? r.extrait.slice(0, 300).replace(/\s+\S*$/, '') + '…' : r.extrait) : '';
  return fig + '<div class="ob-fiche-c"><div class="ob-fiche-t">' + esc(f ? d.nom : s.nom) + '</div>' + (gr.length ? '<div class="ob-fiche-g">' + gr.join('') + '</div>' : '') +
    (fq && !o.grand ? '<div class="ob-fiche-f">📻 ' + fq + '</div>' : '') + (f && f.anecdote ? '<div class="ob-fiche-a">💡 ' + esc(f.anecdote) + '</div>' : '') +
    (ex && !o.court && !o.grand ? '<div class="ob-fiche-r">' + esc(ex) + ' <span class="ob-credit">— ' + lien(r.lien, 'Wikipédia') + ', CC BY-SA</span></div>' : '') +
    (d && !f && !fq ? '<div class="petit">SatMe n’a pas de fiche pour ce satellite.</div>' : '') + '</div>';
}
/** The tab's card, under the list: the satellite chosen (folded or not, kept in this browser). */
function obFicheTab(s) {
  const b = g('ob-fiche'); if (!b) return;
  obFicheSat = s || null;
  if (!s) { b.hidden = true; return; }
  let pli = false; try { pli = localStorage.getItem('ob-fiche-pli') === '1'; } catch (x) {}
  const d = pli ? null : obSatFiche(s);
  const h = '<div class="ob-fiche-h"><button type="button" class="ob-fiche-pli" aria-expanded="' + !pli + '">' + (pli ? '▸' : '▾') + ' Fiche · ' + esc(s.nom) + '</button>' +
    '<label title="La photo et le résumé de Wikipédia, demandés une fois par la station puis gardés"><input type="checkbox"' + (obPhotos ? ' checked' : '') + '> Photo</label></div>' +
    (pli ? '' : '<div class="ob-fiche-corps">' + obCarteSat(s, d, { liens: true }) + '</div>');
  b.hidden = false;
  if (b.dataset.h !== h) {
    b.dataset.h = h; b.innerHTML = h;
    b.querySelector('.ob-fiche-pli').onclick = () => { obGarde('ob-fiche-pli', pli ? '0' : '1'); obFicheTab(s); };
    b.querySelector('input').onchange = e => obPhotosChoix(e.target.checked);
  }
  // The photo on its way: looked at again shortly.
  clearTimeout(obFicheT);
  if (!pli && (!d || d.attente)) obFicheT = setTimeout(() => { if (obFicheSat === s && !g('v-orbites').hidden) obFicheTab(s); }, 3500);
}
// ------------------------------------------------------------------ Vitrine: the dashboard as a stand's poster
/*
 * For a club's stand (a fair, an open day): the globe turning by itself beside big cards one after another — the
 * next pass over the stand (« come and listen! » only when the stand has a receiver), a satellite's photo and story, what amateur radio is, how to reach the
 * club (its link as a QR code, drawn by the station), a fact; the ticker below. Nothing to touch, the cursor hidden.
 */
const OB_VEDETTES = [25544, 43700, 27607, 43017, 44909, 48274, 7530, 24278, 53106, 39444, 57166];
const OB_VIT_DUREE = 12000;
let obVit = { club: '', indicatif: '', lien: '', message: '', taille: 'moyenne', cote: 'gauche', rx: false }, obVitN = 0, obVitT = 0;
try { Object.assign(obVit, JSON.parse(localStorage.getItem('ob-vitrine')) || {}); } catch (x) {}
/* The card's sizes (« Taille de l'encart »), its width and its letters. */
const OB_VIT_K = { petite: .72, moyenne: .86, grande: 1 };
/*
 * The dashboard's globe on the screen, kept for each look: {x, y} its centre's shift (a share of the width, of the height),
 * s its size. Moved by hand (« Le déplacer » ticked, Shift held, or two fingers; the wheel or the fingers' spread its size),
 * else the look's own: the Vitrine's beside its card (above it on a tall screen), the others' in the middle.
 */
let obPlaces = {}, obDeplace = false, obTirG = null;
try { obPlaces = JSON.parse(localStorage.getItem('ob-place')) || {}; } catch (x) {}
function obPlace(w, h) {
  const p = obPlaces[obTh];
  if (p) return p;
  if (!OB_THEMES[obTh].vitrine) return { x: 0, y: 0, s: 1 };
  const k = OB_VIT_K[obVit.taille] || OB_VIT_K.moyenne, d = .8 * h; // the globe's width at its own size
  if (h > w) {
    const haut = .07 * h + 60, bas = h * (1 - .52 * k) - 110, l = Math.max(120, bas - haut);
    return { x: 0, y: (haut + bas) / 2 / h - .5, s: Math.min(1, .94 * Math.min(w, l) / d) };
  }
  const bord = .03 * w + Math.min(.48 * w, 960) * k;
  return { x: (obVit.cote === 'droite' ? -1 : 1) * bord / 2 / w, y: .03, s: Math.min(1, .92 * Math.min(w - bord, .8 * h) / d) };
}
function obPlaceRemet() { delete obPlaces[obTh]; obGarde('ob-place', obPlaces); }
(() => {
  const cv = g('ob-3d'), sc = cv.parentNode, doigts = new Map();
  const libre = e => g('v-orbites').classList.contains('ob-tableau') && e.target === cv;
  const centre = () => { const l = [...doigts.values()], n = l.length;
    return { x: l.reduce((a, q) => a + q.x, 0) / n, y: l.reduce((a, q) => a + q.y, 0) / n, e: n > 1 ? Math.hypot(l[0].x - l[1].x, l[0].y - l[1].y) : 0 }; };
  const depart = () => { const r = cv.getBoundingClientRect(); obTirG = { c: centre(), p: Object.assign({ s: 1 }, obPlace(r.width, r.height)) }; };
  // Caught on the way down, before the globe's own turning by hand.
  sc.addEventListener('pointerdown', e => {
    if (!libre(e)) return;
    doigts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (!obTirG && !obDeplace && !e.shiftKey && doigts.size < 2) return;
    e.stopPropagation(); try { cv.setPointerCapture(e.pointerId); } catch (x) {} depart();
  }, true);
  sc.addEventListener('pointermove', e => {
    if (!doigts.has(e.pointerId)) return;
    doigts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (!obTirG) return;
    e.stopPropagation();
    const r = cv.getBoundingClientRect(), c = centre(), a = obTirG, b = v => Math.max(-.5, Math.min(.5, v));
    obPlaces[obTh] = { x: b(a.p.x + (c.x - a.c.x) / r.width), y: b(a.p.y + (c.y - a.c.y) / r.height),
      s: Math.max(.3, Math.min(3, a.p.s * (c.e && a.c.e ? c.e / a.c.e : 1))) };
  }, true);
  const fin = e => {
    if (!doigts.delete(e.pointerId) || !obTirG) return;
    if (doigts.size) depart(); else { obTirG = null; obGarde('ob-place', obPlaces); }
  };
  sc.addEventListener('pointerup', fin, true); sc.addEventListener('pointercancel', fin, true);
  sc.addEventListener('wheel', e => {
    if (!libre(e) || (!obDeplace && !e.shiftKey)) return;
    e.preventDefault(); e.stopPropagation();
    const r = cv.getBoundingClientRect(), p = Object.assign({ s: 1 }, obPlace(r.width, r.height));
    p.s = Math.max(.3, Math.min(3, p.s * (1 - Math.sign(e.deltaY || e.deltaX) * .08))); obPlaces[obTh] = p; obGarde('ob-place', obPlaces);
  }, { capture: true, passive: false });
  sc.addEventListener('dblclick', e => { if (libre(e) && (obDeplace || e.shiftKey)) { e.stopPropagation(); obPlaceRemet(); } }, true);
})();
/** The satellites shown on their cards: the station's well-known ones, the chosen one first. */
function obVitSats(d) {
  const l = Orbites.satellites(), o = [];
  if (d && d.sat) o.push(d.sat);
  OB_VEDETTES.forEach(n => { const s = l.find(x => x.catnum === n); if (s && !o.some(q => q.nom === s.nom)) o.push(s); });
  return o;
}
/** The cards that have something to say now: [{cle, html, vivant?}]. */
function obVitDiapos(d, l) {
  const o = [], ind = obVit.indicatif || (d && d.indicatif) || '';
  // The next pass over the stand, within three hours (the one under way first).
  const p = (l || []).find(q => q.aos - (d ? d.t : Date.now()) < 3 * 3600000);
  if (p && d) {
    // Facts only; « come and listen! » when the stand has a receiver (« Réception sur le stand »).
    const la = p.aos <= d.t, q = { nom: p.nom, catnum: p.catnum, couleur: p.couleur }, f = obSatFiche(q), rx = !!obVit.rx;
    o.push({ cle: 'passage-' + p.nom + '-' + p.aos + '-' + la + '-' + rx + (f && f.photo ? '-p' : ''), type: 'passage',
      html: '<div class="ob-vit-duo">' + obCarteSat(q, f, { grand: true, court: true }).split('<div class="ob-fiche-c">')[0] +
        '<div><div class="ob-vit-t">' + (la ? (rx ? '📡 En ce moment !' : '🛰 En ce moment') : '⏱ Bientôt ici') + '</div><div class="ob-vit-gros">' + esc(p.nom) + '</div>' +
        '<div class="ob-vit-vivant"></div><div class="ob-vit-sous">jusqu’à ' + p.max.toFixed(0) + '° au-dessus de l’horizon' + (rx ? ' · <b>venez écouter !</b>' : '') + '</div></div></div>',
      vivant: la ? 'passe au-dessus de nous, encore ' + obDuree(p.los - d.t)
        : 'passe au-dessus de nous à ' + new Date(p.aos).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) + ' (dans ' + obDuree(p.aos - d.t) + ')' });
  }
  const ls = obVitSats(d);
  if (ls.length) {
    // Another one each time its card comes round; the next one asked ahead, its photo ready when its turn comes.
    const i = Math.floor(obVitN / 5), s = ls[i % ls.length], f = obSatFiche(s);
    if (ls.length > 1) obSatFiche(ls[(i + 1) % ls.length]);
    o.push({ cle: 'sat-' + s.nom + (f ? '-f' : '') + (f && f.photo ? '-p' : ''), type: 'sat', html: '<div class="ob-vit-t">🛰 Le satellite à découvrir</div>' + obCarteSat(s, f, { grand: true }) });
  }
  o.push({ cle: 'direct', type: 'legende', html: '🌍 <b>En direct</b> : la Terre et les satellites radioamateurs, en ce moment même' + (ind ? ' · ' + esc(ind) : '') });
  o.push({ cle: 'radio', type: 'texte', html: '<div class="ob-vit-t">📻 Le radioamateur, c’est quoi ?</div><ul class="ob-vit-liste">' +
    '<li>Un loisir scientifique et technique, ouvert à tous, à tout âge.</li><li>Parler avec le monde entier… et avec l’espace : satellites, Station spatiale.</li>' +
    '<li>Construire ses antennes, recevoir les images des satellites météo.</li><li>Une licence qui s’obtient par un examen, et des clubs pour apprendre ensemble.</li></ul>' });
  if (obVit.club || obVit.lien || obVit.message || ind) {
    o.push({ cle: 'contact-' + obVit.club + obVit.lien + obVit.message + ind, type: 'contact', html: '<div class="ob-vit-duo"><div><div class="ob-vit-t">👋 Venez nous voir !</div>' +
      (obVit.club ? '<div class="ob-vit-gros">' + esc(obVit.club) + '</div>' : '') + (ind ? '<div class="ob-vit-ind">' + esc(ind) + '</div>' : '') +
      (obVit.message ? '<div class="ob-vit-sous">' + esc(obVit.message) + '</div>' : '') + (obVit.lien ? '<div class="ob-vit-lien">' + esc(obVit.lien) + '</div>' : '') + '</div>' +
      (obVit.lien ? '<img class="ob-vit-qr" src="api/qr?t=' + encodeURIComponent(obVit.lien) + '" alt="QR code : ' + obAttr(obVit.lien) + '">' : '') + '</div>' });
  }
  o.push({ cle: 'savais-' + obVitN, type: 'texte', html: '<div class="ob-vit-t">💡 Le savais-tu ?</div><div class="ob-vit-sous">' + esc(OB_SAVAIS[obVitN % OB_SAVAIS.length]) + '</div>' });
  return o;
}
/** The Vitrine's cards, once a second: the next one every twelve seconds (the pass card more often). */
function obVitrine(d, l) {
  const b = g('ob-vitrine'), on = g('v-orbites').classList.contains('ob-tableau') && !!OB_THEMES[obTh].vitrine;
  if (!on) { if (!b.hidden) { b.hidden = true; b.innerHTML = ''; } return; }
  b.hidden = false;
  b.style.setProperty('--vit-k', OB_VIT_K[obVit.taille] || OB_VIT_K.moyenne); b.classList.toggle('ob-vit-droite', obVit.cote === 'droite');
  const now = Date.now();
  if (now - obVitT > OB_VIT_DUREE) { obVitT = now; obVitN++; }
  const l2 = obVitDiapos(d, l), pas = l2.find(q => q.type === 'passage');
  // The pass, when there is one, every other card.
  const k = pas && obVitN % 2 === 0 ? pas : l2.filter(q => q !== pas)[Math.floor(obVitN / (pas ? 2 : 1)) % l2.filter(q => q !== pas).length];
  const tete = '<div class="ob-vit-tete"><span>' + esc([obVit.club, obVit.indicatif || (d && d.indicatif)].filter(Boolean).join(' · ') || 'Radioamateurs et satellites') + '</span><span class="ob-vit-heure">' +
    new Date(now).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }) + '</span></div>';
  let c = b.querySelector('.ob-vit-carte');
  if (b.dataset.tete !== tete || !b.querySelector('.ob-vit-tete')) { b.dataset.tete = tete; const t = b.querySelector('.ob-vit-tete'); if (t) t.outerHTML = tete; else b.insertAdjacentHTML('afterbegin', tete); }
  if (!c || b.dataset.k !== k.cle) {
    if (c) c.remove();
    b.dataset.k = k.cle; b.insertAdjacentHTML('beforeend', '<div class="ob-vit-carte ob-vit-' + k.type + '">' + k.html + '</div>');
    c = b.querySelector('.ob-vit-carte');
  }
  const v = c.querySelector('.ob-vit-vivant'), taille = innerWidth + 'x' + innerHeight;
  let refait = c.dataset.taille !== taille;
  if (v && k.vivant != null && v.textContent !== k.vivant) { v.textContent = k.vivant; refait = true; }
  if (refait) { c.dataset.taille = taille; obVitAjuste(c); c.querySelectorAll('img').forEach(i => { i.onload = () => obVitAjuste(c); }); }
}
/** A Vitrine card's text made smaller until all of it fits (never cut, in height or width), from its full size each time. */
function obVitAjuste(c) {
  c.style.fontSize = '';
  let f = parseFloat(getComputedStyle(c).fontSize), n = 0;
  while ((c.scrollHeight > c.clientHeight + 1 || c.scrollWidth > c.clientWidth + 1) && f > 11 && n++ < 40) { f *= .94; c.style.fontSize = f.toFixed(1) + 'px'; }
}"""
}
