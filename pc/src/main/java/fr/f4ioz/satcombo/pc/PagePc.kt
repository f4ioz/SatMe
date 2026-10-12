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
  [hidden] { display:none !important; }
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
  .discret { background:transparent; border:none; }
  nav { display:flex; gap:4px; }
  nav button { background:transparent; border:1px solid transparent; color:var(--gris); padding:6px 14px; }
  @media (max-width:1500px) { nav button { padding:6px 9px; } header { gap:10px; } }
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
  .o-puces { display:flex; gap:6px; flex-wrap:wrap; }
  .o-puces button { padding:4px 9px; font-size:12px; font-weight:600; }
  .o-puces button.ici { background:var(--cyan); color:#06131A; border-color:var(--cyan); }
  .o-puces button.perso { border-color:var(--ambre); }
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
    .vue > .grille2 { height:100%; }
    .vue > .grille2 > div { min-height:0; overflow:auto; }
    #k-grille { height:100%; }
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
  <h1>SatMe <span class="cyan">PC</span></h1>
  <span class="g" id="info-station"></span>
  <span class="g" id="cat-statut-court"></span>
  <nav id="onglets">
    <button data-v="station" class="actif">Station</button><button data-v="carnet">Carnet</button><button data-v="agenda">Agenda</button><button data-v="carres">Carrés</button><button data-v="frise">Frise</button><button data-v="sked">Sked</button><button data-v="qo100">QO-100</button><button data-v="son">Son</button><button data-v="sstv">SSTV</button><button data-v="aprs">APRS</button><button data-v="ft8">FT8</button><a href="/journal" style="text-decoration:none"><button title="Les passages enregistrés, rejoués">Journal</button></a><button data-v="reglages">Réglages</button>
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
      <div id="liste"><table><thead><tr><th></th><th></th><th>Début</th><th>Satellite</th><th>El max</th><th>Durée</th><th></th></tr></thead><tbody id="passages"></tbody></table></div>
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
      <div class="paire"><span class="gros" id="nom">—</span><button id="fav" class="discret" title="Favori" onclick="basculeFavori()" style="font-size:20px;padding:2px 8px">☆</button><span id="compte" class="val ambre"></span></div>
      <div class="ligne" style="margin-top:2px"><span class="petit" id="amsat"></span>
        <span style="margin-left:auto" class="petit">Rapport AMSAT :</span>
        <button onclick="rapportAmsat(1)" title="Dire à amsat.org que tu l'entends maintenant">Entendu</button>
        <button onclick="rapportAmsat(0)" title="Dire à amsat.org que tu ne l'entends pas">Pas entendu</button></div>
      <div class="paire" style="margin-top:6px">
        <span>Az <span class="val cyan" id="az">—</span></span>
        <span>El <span class="val cyan" id="el">—</span></span>
        <span class="petit" id="dist"></span>
      </div>
      <div class="ecran" id="s-ecran">
        <div class="bascule"><button id="b-ciel" onclick="montreEcran('ciel')">Ciel</button><button id="b-carte" onclick="montreEcran('carte')">Carte</button></div>
        <svg id="ciel" viewBox="-110 -110 220 220" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Le ciel : passage et position du satellite"></svg>
        <canvas id="s-carte" hidden title="Glisser pour déplacer, molette pour zoomer, double-clic pour revoir le monde"></canvas>
      </div>
      <div class="petit" id="infos-passage" style="text-align:center;margin-top:4px"></div>
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
      <div class="ligne"><button onclick="post('/api/sat/reglage?memorise=1')" title="Garder ces deux valeurs comme celles qui marchent pour ce satellite">Mémoriser</button>
        <button id="r-retour" onclick="post('/api/sat/reglage?retour=1')">Revenir à la référence</button><span class="petit" id="r-ref"></span></div>
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

  </div>
</div>
</div>
<div class="vue" id="v-carnet" hidden>
<div class="grille2">
  <div>
    <div class="carte">
      <div class="t">Nouveau contact <span class="petit" id="c-sat"></span></div>
      <input id="c-call" placeholder="INDICATIF" autocomplete="off" style="width:100%;font-size:26px;font-weight:800;letter-spacing:3px;text-transform:uppercase">
      <div id="c-qui" class="petit" style="min-height:20px;margin-top:4px"></div>
      <div class="ligne">
        <input id="c-loc" placeholder="Carré" style="text-transform:uppercase;max-width:120px">
        <span class="petit">RS ↑</span><input id="c-rs" value="59" style="max-width:60px">
        <span class="petit">↓</span><input id="c-rr" value="59" style="max-width:60px">
      </div>
      <div class="ligne"><input id="c-note" placeholder="Note"></div>
      <div class="ligne"><button class="primaire" onclick="enregistreContact()">Enregistrer (Entrée)</button><span class="petit" id="c-etat"></span></div>
      <div class="petit" style="margin-top:6px">Daté à l'enregistrement, sur le satellite suivi, avec son az/el, ton carré, le mode et les fréquences au repos.</div>
    </div>
    <div class="carte">
      <div class="t">Carnet</div>
      <div class="paire"><span><span class="val" id="c-nb">0</span> <span class="petit">contacts</span></span>
        <span><span class="val" id="c-carres">0</span> <span class="petit">carrés</span></span>
        <span><span class="val ambre" id="c-attente">0</span> <span class="petit">à déposer</span></span></div>
      <div class="ligne"><a href="/carnet.adi" download><button>Exporter en ADIF</button></a>
        <button onclick="depose()">Déposer au carnet en ligne</button></div>
      <div class="etat" id="c-depot"></div>
    </div>
    <div class="carte">
      <div class="t">Importer</div>
      <div class="ligne" style="margin-top:0"><button onclick="profilsWavelog()">Depuis le carnet en ligne (Wavelog)…</button></div>
      <div id="w-profils" class="petit"></div>
      <div class="petit" style="margin-top:10px">Ou un fichier ADIF (export Wavelog, LoTW, autre logiciel) :</div>
      <input type="file" id="i-adif" accept=".adi,.adif,.txt" style="width:100%;margin-top:4px" onchange="importeAdif(this)">
      <label class="petit"><input type="checkbox" id="i-enligne" checked> ces contacts sont déjà dans le carnet en ligne (ne pas les y redéposer)</label>
      <div class="petit" style="margin-top:6px">Les contacts satellite absents du carnet y entrent, avec la position du satellite à leur heure ; leurs passages sont reconstitués dans le Journal.</div>
      <div class="etat" id="i-etat"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="ligne" style="margin-top:0"><input id="c-filtre" placeholder="Filtrer : indicatif, carré, satellite…"></div>
      <div style="max-height:calc(100vh - 170px);overflow:auto;margin-top:6px">
        <table><thead><tr><th>Date (UTC)</th><th>Indicatif</th><th>Carré</th><th>Satellite</th><th>Mode</th><th>RS</th><th>Nom</th><th></th></tr></thead>
        <tbody id="c-liste"></tbody></table>
      </div>
    </div>
  </div>
</div>
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
      <div class="ligne"><label class="petit"><input type="checkbox" id="s-auto" onchange="post('/api/son/options?auto=' + (this.checked ? 1 : 0))"> Enregistrer chaque passage du satellite suivi (de l'AOS à 30 s après le LOS)</label></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="s-aprs" onchange="post('/api/son/options?aprs=' + (this.checked ? 1 : 0))"> Décoder l'APRS (AFSK 1200 bauds)</label></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="s-sstv" onchange="post('/api/son/options?sstv=' + (this.checked ? 1 : 0))"> Décoder la SSTV en direct</label></div>
      <div class="petit" style="margin-top:6px">WAV 16 bits, à la moitié de la fréquence de la carte (24 ou 22,05 kHz). Même nom que sur le téléphone.</div>
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
      <div class="etat" id="i-etat"></div>
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
      <div class="ligne" style="margin-top:0"><label class="petit"><input type="checkbox" id="g-suivi" onchange="post('/api/agenda/suivi?on=' + (this.checked ? 1 : 0))">
        Suivre tout seul le satellite d'un rendez-vous, 5 min avant (le poste et le rotor suivent)</label></div>
      <div class="petit" style="margin-top:6px">📅 dans la liste des passages (onglet Station) ajoute ou retire un passage. L'agenda du téléphone arrive avec sa sauvegarde (Réglages).</div>
    </div>
  </div>
  <div>
    <div class="carte">
      <table><thead><tr><th>Quand</th><th>Rendez-vous</th><th>Satellite</th><th></th></tr></thead><tbody id="g-liste"></tbody></table>
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
      <div class="paire" style="margin-top:8px"><b id="o-seg"></b><span class="petit" id="o-larg"></span><span id="o-interdit" class="rose" style="font-weight:700"></span></div>
      <div class="petit" id="o-avert" style="margin-top:2px"></div>
    </div>
    <div class="carte">
      <div class="t">Ce que voit le poste</div>
      <label><input type="checkbox" id="o-poste" onchange="qPost('poste?on=' + (this.checked ? 1 : 0))"> Appliquer au poste</label>
      <div class="paire" style="margin-top:8px">
        <span>Poste RX <span class="val cyan" id="o-prx">—</span></span><span>Poste TX <span class="val ambre" id="o-ptx">—</span></span></div>
      <div class="avert" id="o-hors"></div>
      <div class="etat" id="o-statut"></div>
      <div class="petit" style="margin-top:6px">Les fréquences de gauche sont celles du ciel ; le poste travaille en fréquence intermédiaire, après les convertisseurs de la chaîne. Pas de Doppler ici : le poste n'est écrit que quand tu bouges. Tant que la case est cochée, le Doppler du satellite suivi est en pause.</div>
      <div class="petit" style="margin-top:4px">Pas de clé SDR sur le PC pour l'instant : le panorama et la balise vue par la clé restent sur le téléphone.</div>
    </div>
  </div>
  <div class="col3">
    <div class="carte">
      <div class="t">Calage sur la balise</div>
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
<div class="vue" id="v-ft8" hidden>
<div class="grille2" style="grid-template-columns:minmax(380px,1fr) minmax(500px,1.5fr)">
  <div>
    <div class="carte">
      <div class="ligne" style="margin-top:0">
        <button class="primaire" id="8-marche" onclick="ft8Marche()">Écouter</button>
        <select id="8-mode" onchange="post('/api/ft8?mode=' + this.value)" style="max-width:90px"><option>FT8</option><option>FT4</option></select>
        <button onclick="post('/api/ft8?vide=1')">Effacer</button>
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
<div class="grille2">
  <div>
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
      <div class="t">Éléments et téléphone</div>
      <div class="ligne"><button onclick="post('/api/catalogue')">Retélécharger les éléments AMSAT</button></div>
      <div class="petit" style="margin-top:10px">Reprendre les réglages du téléphone (Réglages → Sauvegarde, fichier .json) :</div>
      <input type="file" id="import" accept=".json,application/json" onchange="importe(this)" style="width:100%;margin-top:4px">
      <div class="etat" id="catalogue"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">QRZ.com</div>
      <div class="ligne" style="margin-top:0"><input id="qrzUser" placeholder="Identifiant QRZ"><input id="qrzMdp" type="password" placeholder="Mot de passe"></div>
      <div class="petit">Abonnement XML de QRZ nécessaire. Le mot de passe reste sur ce PC et n'est jamais réaffiché.</div>
      <div class="ligne"><button onclick="enregistreReglages()">Enregistrer</button><button onclick="essaiQrz()">Essayer</button><span class="petit" id="qrz-etat"></span></div>
    </div>
    <div class="carte">
      <div class="t">Carnet en ligne (Wavelog / Cloudlog)</div>
      <div class="ligne" style="margin-top:0"><input id="carnetUrl" placeholder="https://wavelog.exemple.fr/"></div>
      <div class="ligne"><input id="carnetCle" type="password" placeholder="Clé d'API (lecture et écriture)"><input id="carnetProfil" placeholder="ID du profil" style="max-width:120px"></div>
      <div class="ligne"><button onclick="enregistreReglages()">Enregistrer</button></div>
    </div>
    <div class="carte">
      <div class="t">Convertisseurs</div>
      <div class="petit" id="conv-lu">—</div>
      <div class="petit" style="margin-top:6px">Ils se règlent dans l'onglet <a href="#qo100" onclick="vue('qo100')" style="color:var(--cyan)">QO-100</a>, par la chaîne de station : un seul endroit pour chaque oscillateur, comme sur le téléphone.</div>
    </div>
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
async function agendaPassage(n, aos, ev) { if (ev) ev.stopPropagation(); await post('/api/agenda/passage?catnum=' + n + '&aos=' + aos); chargeListe(); }
async function regle(k, d) { await post('/api/sat/reglage?' + k + '=' + d); }
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
    ' onchange="post(\'/api/agenda/active?id=' + a.id + '\')"> actif</label> <button onclick="post(\'/api/agenda/retire?id=' + a.id + '\')">✕</button></td></tr>').join('') ||
    '<tr><td colspan="4" class="petit">rien de prévu</td></tr>';
}
const hz = v => (v > 0 ? '+' : '') + v + ' Hz';
async function rapportAmsat(entendu) {
  if (!E || !E.suivi) return;
  if (!confirm((entendu ? 'Entendu' : 'Pas entendu') + ' : envoyer ce rapport sur la page publique d’amsat.org pour ' + E.suivi.nom + ', avec ton indicatif et ton carré ?')) return;
  const j = await post('/api/amsat/rapport?entendu=' + entendu);
  if (j && j.ok) alert('Rapport envoyé à amsat.org (' + j.nom + ').');
}
async function favori(n, on, ev) { if (ev) ev.stopPropagation(); await post('/api/favori?catnum=' + n + '&on=' + (on ? 1 : 0)); chargeListe(); }
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
    return '<tr class="' + cl + '" onclick="suivre(' + p.catnum + ')">' + celluleAgenda(p) + '<td><span title="Favori" style="cursor:pointer;color:' + (p.fav ? 'var(--ambre)' : 'var(--bord)') +
      '" onclick="favori(' + p.catnum + ',' + !p.fav + ',event)">★</span></td><td>' + hm(p.aos) + '</td><td>' +
      (a ? '<span title="AMSAT : ' + a[1] + '" style="display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px;background:' + a[0] + '"></span>' : '') +
      p.nom.replace(/</g, '&lt;') +
      '</td><td>' + Math.round(p.elMax) + '°</td><td>' + duree(p.los - p.aos) + '</td><td class="petit">' + quand + '</td></tr>';
  });
  g('passages').innerHTML = lignes.join('') || '<tr><td colspan="7" class="petit">' + (E && E.locator ? 'aucun passage' : 'renseigne ton locator') + '</td></tr>';
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
  txt('info-station', [E.indicatif, E.locator].filter(x => x).join(' · ') + ' · ' + E.nbSatellites + ' satellites');
  txt('calcul', E.calcul ? 'calcul des passages…' : '');
  txt('catalogue', E.catalogue);
  if (document.activeElement !== g('indicatif') && !g('indicatif').value) g('indicatif').value = E.indicatif || '';
  if (document.activeElement !== g('locator') && !g('locator').value) g('locator').value = E.locator || '';
  txt('nom', E.suivi ? E.suivi.nom : '— choisis un passage');
  g('fav').style.display = E.suivi ? '' : 'none';
  txt('fav', E.suivi && E.suivi.fav ? '★' : '☆'); g('fav').style.color = E.suivi && E.suivi.fav ? 'var(--ambre)' : 'var(--gris)';
  const am = E.suivi && E.suivi.amsat;
  txt('amsat', am ? 'AMSAT : ' + (AMSAT[am.statut] || AMSAT.UNKNOWN)[1] + (am.mode ? ' (' + am.mode + ')' : '') + ' · ' + am.actifs + ' rapport(s) « entendu »' : '');
  txt('c-sat', E.suivi ? '· ' + E.suivi.nom + (E.position ? ' · El ' + Math.round(E.position.el) + '°' : '') : '· suis d’abord un satellite (onglet Station)');
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
    g('suivants').innerHTML = E.passagesSuivi.map(p => '<tr>' + celluleAgenda(p) + '<td>' + jourHeure(p.aos) + '</td><td>' + Math.round(p.elMax) + '°</td><td>' +
      duree(p.los - p.aos) + '</td><td class="petit">' + Math.round(p.azAos) + '° → ' + Math.round(p.azLos) + '°</td></tr>').join('');
  }
  dessineAgenda();
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
    alert(j.ok ? j.n + ' réglages repris du téléphone (ports USB exceptés ; le rotor attend que tu coches « Suivre »), ' +
      j.contacts + ' contact(s) ajouté(s) au carnet.' : 'Import impossible : ' + j.erreur);
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
g('emetteur').onchange = () => post('/api/emetteur?i=' + g('emetteur').value);
// ------------------------------------------------------------------ tabs
function vue(n) {
  document.querySelectorAll('.vue').forEach(x => x.hidden = x.id !== 'v-' + n);
  document.querySelectorAll('#onglets button').forEach(b => b.classList.toggle('actif', b.dataset.v === n));
  try { localStorage.setItem('vue', n); } catch (x) {}
  if (n === 'carnet') { chargeCarnet(); g('c-call').focus(); }
  if (n === 'reglages') chargeReglages();
  if (n === 'son' || n === 'sstv') ticSon();
  if (n === 'aprs') ticAprs();
  if (n === 'ft8') ticFt8();
  if (n === 'carres') chargeCarres();
  if (n === 'frise') chargeFrise();
  if (n === 'sked') { prepareSked(); setTimeout(dessineSked); }
  if (n === 'qo100') chargeQo100(true);
}
document.querySelectorAll('#onglets button[data-v]').forEach(b => b.onclick = () => vue(b.dataset.v));

// ------------------------------------------------------------------ log
let C = [], minuteurQrz = 0, quiPour = '', editT = 0;
const esc = x => String(x == null ? '' : x).replace(/&/g, '&amp;').replace(/</g, '&lt;');
const base = c => c.toUpperCase().split('/')[0];
async function chargeCarnet() {
  try {
    const j = await (await fetch('/api/carnet')).json();
    C = j.contacts;
    txt('c-nb', String(j.total)); txt('c-carres', String(j.carres)); txt('c-attente', String(j.nonDeposes));
    dessineCarnet();
  } catch (x) {}
}
function dessineCarnet() {
  const f = v('c-filtre').toUpperCase();
  const tous = C.filter(e => !f || [e.c, e.l, e.sat, e.nm, e.qth].join(' ').toUpperCase().includes(f));
  // A thousand lines at most: beyond that, the filter finds faster than the eye.
  const reste = tous.length > 1000 ? '<tr><td colspan="8" class="petit">… et ' + (tous.length - 1000) + ' autres : filtre pour les voir</td></tr>' : '';
  g('c-liste').innerHTML = tous.slice(0, 1000).map(e =>
    '<tr><td>' + new Date(e.t).toISOString().slice(0, 16).replace('T', ' ') + '</td><td><b>' + esc(e.c) + '</b></td><td>' + esc(e.l) +
    '</td><td>' + esc(e.sat) + '</td><td>' + esc(e.md) + '</td><td>' + esc(e.rs) + '/' + esc(e.rr) + '</td><td>' + esc(e.nm) +
    '</td><td class="act">' + (e.envoye ? '<span class="vert" title="déposé au carnet en ligne">✓</span> ' : '<button title="Déposer au carnet en ligne" onclick="depose(' + e.t + ')">⬆</button> ') +
    '<button title="Modifier" onclick="edite(' + e.t + ')">✎</button></td></tr>').join('') + reste ||
    '<tr><td colspan="8" class="petit">aucun contact</td></tr>';
}
g('c-filtre').oninput = dessineCarnet;
// QRZ after 0.6 s without a key, as on the phone and the desk: a callsign the log does not know by name.
g('c-call').addEventListener('input', () => {
  clearTimeout(minuteurQrz);
  const c = base(v('c-call'));
  if (c !== quiPour) txt('c-qui', '');
  if (c.length < 4 || !/[0-9]/.test(c)) return;
  const connu = C.find(e => base(e.c) === c && e.nm);
  if (connu) {
    quiPour = c; txt('c-qui', connu.nm + (connu.qth ? ' · ' + connu.qth : '') + ' (carnet)');
    if (!v('c-loc') && connu.l && v('c-call').indexOf('/') < 0) g('c-loc').value = connu.l;
    return;
  }
  minuteurQrz = setTimeout(async () => {
    txt('c-qui', 'QRZ…');
    let f = null;
    try { f = await (await fetch('/api/qrz?c=' + enc(c))).json(); } catch (x) {}
    if (base(v('c-call')) !== c) return;
    if (!f || !f.ok) { txt('c-qui', f && f.erreur ? f.erreur : ''); return; }
    quiPour = c;
    txt('c-qui', [f.nom, f.qth, f.pays].filter(x => x).join(' · '));
    // Its square where none was typed — never for a /P or /M (QRZ gives the home address).
    if (!v('c-loc') && f.carre && v('c-call').indexOf('/') < 0) g('c-loc').value = f.carre;
  }, 600);
});
['c-call', 'c-loc', 'c-rs', 'c-rr', 'c-note'].forEach(i => g(i).addEventListener('keydown', e => { if (e.key === 'Enter') enregistreContact(); }));
async function enregistreContact() {
  const c = v('c-call'); if (!c) return;
  const j = await post('/api/carnet/ajoute?c=' + enc(c) + '&l=' + enc(v('c-loc')) + '&rs=' + enc(v('c-rs')) + '&rr=' + enc(v('c-rr')) + '&n=' + enc(v('c-note')));
  if (j && j.ok) {
    txt('c-etat', c.toUpperCase() + ' enregistré');
    ['c-call', 'c-loc', 'c-note'].forEach(i => g(i).value = ''); g('c-rs').value = '59'; g('c-rr').value = '59';
    txt('c-qui', ''); quiPour = ''; g('c-call').focus(); chargeCarnet();
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
  try { f = await (await fetch('/api/qrz?c=' + enc(base(v('e-call'))))).json(); } catch (x) {}
  if (!f || !f.ok) { txt('e-qrz', f && f.erreur ? f.erreur : 'QRZ ne connaît pas cet indicatif.'); return; }
  if (!v('e-nm')) g('e-nm').value = f.nom;
  if (!v('e-qth')) g('e-qth').value = f.qth;
  if (!v('e-loc') && f.carre && v('e-call').indexOf('/') < 0) { g('e-loc').value = f.carre; g('e-loc').dataset.qrz = '1'; }
  txt('e-qrz', [f.nom, f.qth, f.carre, f.pays].filter(x => x).join(' · '));
}
async function enregistreEdit() {
  const j = await post('/api/carnet/modifie?t=' + editT + '&c=' + enc(v('e-call')) + '&l=' + enc(v('e-loc')) + '&md=' + enc(v('e-md')) +
    '&rs=' + enc(v('e-rs')) + '&rr=' + enc(v('e-rr')) + '&nm=' + enc(v('e-nm')) + '&qth=' + enc(v('e-qth')) + '&n=' + enc(v('e-n')) +
    (g('e-loc').dataset.qrz ? '&lo=QRZ' : ''));
  delete g('e-loc').dataset.qrz;
  if (j && j.ok !== false) { g('c-edit').close(); chargeCarnet(); }
}
async function supprimeEdit() {
  if (!confirm('Supprimer ce contact du carnet ?')) return;
  await post('/api/carnet/supprime?t=' + editT); g('c-edit').close(); chargeCarnet();
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
  try { j = await (await fetch('/api/wavelog/profils')).json(); } catch (x) {}
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
  try { j = await (await fetch('/api/wavelog/importe?profils=' + enc(ids), { method: 'POST' })).json(); } catch (x) {}
  txt('i-etat', bilanImport(j)); chargeCarnet();
}
async function importeAdif(input) {
  const f = input.files[0]; if (!f) return;
  txt('i-etat', 'lecture de ' + f.name + '…');
  let j = null;
  try { j = await (await fetch('/api/import/adif?enLigne=' + (g('i-enligne').checked ? 1 : 0), { method: 'POST', body: await f.text() })).json(); } catch (x) {}
  txt('i-etat', bilanImport(j)); input.value = ''; chargeCarnet();
}
async function depose(t) {
  txt('c-depot', 'envoi…');
  const j = await post('/api/carnet/depose' + (t ? '?t=' + t : ''));
  txt('c-depot', j && j.dit ? j.dit : '');
  chargeCarnet();
}

// ------------------------------------------------------------------ settings
async function chargeReglages() {
  try {
    const r = await (await fetch('/api/reglages')).json();
    g('indicatif').value = r.c; g('locator').value = r.l; g('minEl').value = r.minEl;
    g('qrzUser').value = r.qrzUser; g('qrzMdp').placeholder = r.qrzMdp ? 'mot de passe enregistré' : 'Mot de passe';
    g('carnetUrl').value = r.carnetUrl; g('carnetCle').placeholder = r.carnetCle ? 'clé enregistrée' : 'Clé d’API (lecture et écriture)';
    g('carnetProfil').value = r.carnetProfil;
  } catch (x) {}
}
async function enregistreReglages() {
  const j = await post('/api/reglages?c=' + enc(v('indicatif')) + '&l=' + enc(v('locator')) + '&minEl=' + enc(v('minEl')) +
    '&qrzUser=' + enc(v('qrzUser')) + '&qrzMdp=' + enc(g('qrzMdp').value) + '&carnetUrl=' + enc(v('carnetUrl')) +
    '&carnetCle=' + enc(g('carnetCle').value) + '&carnetProfil=' + enc(v('carnetProfil')));
  if (j && j.ok !== false) {
    txt('reg-etat', 'enregistré'); g('qrzMdp').value = ''; g('carnetCle').value = '';
    chargeReglages(); setTimeout(chargeListe, 1500);
  }
}
async function essaiQrz() {
  txt('qrz-etat', 'QRZ…');
  let f = null;
  try { f = await (await fetch('/api/qrz?c=' + enc(v('indicatif') || 'F4IOZ'))).json(); } catch (x) {}
  txt('qrz-etat', f && f.ok ? 'QRZ répond : ' + [f.nom, f.qth].filter(x => x).join(' · ') : (f && f.erreur ? f.erreur : 'pas de réponse'));
}

// ------------------------------------------------------------------ sound and SSTV
let S = null, versionImage = -1, cartesVues = '', imagesVus = '', enregsVus = '';
const ouverte = n => !g('v-' + n).hidden;
async function ticSon() {
  try { S = await (await fetch('/api/son')).json(); } catch (x) { return; }
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
  if (document.activeElement !== g('s-sstv')) g('s-sstv').checked = S.sstv;
  if (document.activeElement !== g('s-aprs')) g('s-aprs').checked = S.aprs;
  const enregs = JSON.stringify(S.enregistrements.map(e => e.nom + e.taille));
  if (enregs !== enregsVus && ouverte('son')) {
    enregsVus = enregs;
    g('s-liste').innerHTML = S.enregistrements.map(e => '<tr><td><a href="#" onclick="ecoute(\'' + enc(e.nom) + '\');return false">' + esc(e.nom) +
      '</a></td><td class="petit">' + (e.taille / 1048576).toFixed(1) + ' Mo</td><td><a href="/enregistrements/' + enc(e.nom) + '" download>⤓</a></td></tr>').join('') ||
      '<tr><td class="petit">aucun enregistrement</td></tr>';
  }
  // SSTV: the picture arriving, reloaded only when it moved.
  txt('i-mode', S.sstvMode ? S.sstvMode + ' · ' + S.sstvLignes + '/' + S.sstvHauteur + ' lignes' : '');
  txt('i-etat', !S.actif ? 'Démarre la carte son (onglet Son) pour décoder.' : !S.sstv ? 'Décodage SSTV coupé (onglet Son).' : S.sstvMode ? 'réception…' : 'à l’écoute');
  if (ouverte('sstv') && S.sstvVersion !== versionImage) {
    versionImage = S.sstvVersion;
    if (S.sstvMode) g('i-encours').src = '/api/sstv/encours.png?v=' + S.sstvVersion;
    else if (S.sstvDerniere) g('i-encours').src = '/sstv/' + enc(S.sstvDerniere);
  }
  const imgs = JSON.stringify(S.images.map(i => i.nom));
  if (imgs !== imagesVus && ouverte('sstv')) {
    imagesVus = imgs;
    g('i-galerie').innerHTML = S.images.map(i => '<a href="/sstv/' + enc(i.nom) + '" target="_blank" style="text-decoration:none;color:inherit">' +
      '<img src="/sstv/' + enc(i.nom) + '" style="width:100%;border-radius:6px" loading="lazy"><div class="petit">' + esc(i.sat) + ' · ' + esc(i.mode) +
      ' · ' + (i.t ? new Date(i.t).toISOString().slice(0, 16).replace('T', ' ') : '') + (i.complete ? '' : ' · partielle') + '</div></a>').join('') ||
      '<div class="petit">aucune image</div>';
  }
}
// ------------------------------------------------------------------ squares worked
let Q = null, qz = 4, qcx = 0.5, qcy = 0.35, qCadre = false;
const qTuiles = new Map();
const wxq = lon => (lon + 180) / 360;
function wyq(lat) { const l = Math.max(-85, Math.min(85, lat)) * Math.PI / 180; return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2; }
// A 4-character square: its south-west corner (2° of longitude, 1° of latitude).
function coin(c) { const A = 'A'.charCodeAt(0); return [(c.charCodeAt(0) - A) * 20 - 180 + (+c[2]) * 2, (c.charCodeAt(1) - A) * 10 - 90 + (+c[3])]; }
async function chargeCarres() {
  try { Q = await (await fetch('/api/carres')).json(); } catch (x) { return; }
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
  if (!i) { i = new Image(); i.onload = () => { dessineCarres(); dessineSked(); dessineCarteStation(); }; i.src = '/journal/tuile?z=' + z + '&x=' + xw + '&y=' + y; qTuiles.set(k, i);
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
  try { F = await (await fetch('/api/frise?h=' + g('f-h').value)).json(); } catch (x) { return; }
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
  try { j = await (await fetch('/api/sked?catnum=' + g('k-sat').value + '&loc=' + enc(loc) + '&minEl=' + enc(v('k-min')))).json(); } catch (x) {}
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
  try { j = await (await fetch('/api/sked/trace?catnum=' + g('k-sat').value + '&loc=' + enc(v('k-loc')) + '&de=' + w.debutTrace + '&a=' + w.finTrace)).json(); } catch (x) {}
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
  g('ciel').style.display = q === 'ciel' ? '' : 'none'; g('s-carte').hidden = q !== 'carte';
  g('b-ciel').classList.toggle('actif', q === 'ciel'); g('b-carte').classList.toggle('actif', q === 'carte');
  dessineCarteStation();
}
const SV = { cx: 0.5, cy: 0.4, z: 1, libre: false };
let traceSol = null, traceVu = 0;
async function chargeTraceSol() {
  try { traceSol = await (await fetch('/api/trace')).json(); } catch (x) { return; }
  // Continuous longitudes: a track over the date line stays one line.
  let ref = null;
  traceSol.points.forEach(p => { if (ref != null) { while (p[1] - ref > 180) p[1] -= 360; while (p[1] - ref < -180) p[1] += 360; } ref = p[1]; });
  traceVu = Date.now();
  dessineCarteStation();
}
function cadreStation() {
  const c = g('s-carte'); if (!c.clientWidth) return;
  // The whole world in width, the observer in the middle.
  SV.z = Math.max(0, Math.log2(c.clientWidth / 256));
  SV.cx = E && E.observateur ? wxk(E.observateur.lon) : 0.5;
  SV.cy = Math.max(0.5 - (1 - c.clientHeight / (256 * Math.pow(2, SV.z))) / 2, Math.min(0.5, wyq(25)));
}
function dessineCarteStation() {
  const c = g('s-carte'); if (!c || g('v-station').hidden || c.hidden || !c.clientWidth) return;
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
  // The track asked again each minute, and at once for another satellite.
  const n = E && E.suivi ? E.suivi.catnum : 0;
  if (n && (!traceSol || traceSol.catnum !== n || Date.now() - traceVu > 60000) && Date.now() - traceVu > 3000) { traceVu = Date.now(); chargeTraceSol(); }
}
attacheCarte(g('s-carte'), SV, dessineCarteStation, cadreStation, 0);
montreEcran(ecranStation)
async function skedAgenda(i) { const w = K[i]; await post('/api/agenda/sked?catnum=' + g('k-sat').value + '&loc=' + enc(v('k-loc')) + '&de=' + w.de + '&a=' + w.a); alert('Sked mis à l’agenda.'); }

// ------------------------------------------------------------------ QO-100
let O = null, OP = null;
/** 10 489 750 000 Hz → "10 489,750 000". */
function qFmt(hz) { const m = Math.floor(hz / 1e6), r = String(hz % 1e6).padStart(6, '0');
  return String(m).replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + ',' + r.slice(0, 3) + ' ' + r.slice(3); }
function qKhz(hz) { return (hz / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 1 }) + ' kHz'; }
async function chargeQo100(plan) {
  try { const j = await (await fetch('/api/qo100' + (plan || !OP ? '?plan=1' : ''))).json(); if (j.segments) OP = j; O = j; } catch (x) { return; }
  dessineQo100();
  // The Sun's dates take a second or two the first time.
  if (!O.ciel) setTimeout(() => { if (!g('v-qo100').hidden) chargeQo100(); }, 1500);
}
async function qPost(a) { const j = await post('/api/qo100/' + a); await chargeQo100(); return j; }
function qPas(d) { qPost('pas?hz=' + d); }
function qAller() {
  const x = parseFloat(v('o-aller').replace(',', '.').replace(/\s/g, ''));
  if (!x) return;
  qPost('frequence?hz=' + Math.round(x < 20000 ? x * 1e6 : x));
}
function qMemoire() { qPost('memoire?nom=' + enc(v('o-nom'))); g('o-nom').value = ''; }
function qOublie() { qPost('oublie?hz=' + O.descente); }
function qNouvelle() { const n = v('o-nouvelle'); if (!n) return; g('o-nouvelle').value = ''; qPost('nouvelle?nom=' + enc(n)); }
function qOl(sens, ol) { qPost('ol?sens=' + sens + '&hz=' + ol); }
async function qCale() {
  const j = await qPost('balise-entendue?mhz=' + enc(v('o-entendue')));
  txt('o-cmsg', j && j.ok === false ? j.message : '');
}
async function qMesure() {
  const j = await post('/api/qo100/mesure?sens=' + g('o-msens').value + '&ciel=' + enc(v('o-mciel')) + '&poste=' + enc(v('o-mposte')));
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
  dessineReglette(); dessineLoupe();
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
  const l = g('o-loupe');
  l.addEventListener('click', ev => { if (!O) return; const b = l.getBoundingClientRect();
    qPost('frequence?hz=' + Math.round((O.descente + LOUPE * (ev.clientX - b.left - b.width / 2) / (b.width / 2 - 4)) / 100) * 100); });
  // The wheel: on the frequency, the ruler or the loupe.
  for (const e of [g('o-dl'), r, l]) e.addEventListener('wheel', ev => { ev.preventDefault(); qPas((ev.deltaY < 0 ? 1 : -1) * (ev.shiftKey ? 1000 : 100)); }, { passive: false });
  // The arrows, when nothing is being typed.
  document.addEventListener('keydown', ev => {
    if (g('v-qo100').hidden || /INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) return;
    const d = { ArrowLeft: -100, ArrowRight: 100, ArrowDown: -1000, ArrowUp: 1000 }[ev.key];
    if (d) { ev.preventDefault(); qPas(d); }
  });
  window.addEventListener('resize', dessineQo100);
})();

// ------------------------------------------------------------------ FT8
let H = null, ft8Vu = '';
async function ticFt8() {
  if (g('v-ft8').hidden) return;
  try { H = await (await fetch('/api/ft8')).json(); } catch (x) { return; }
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
async function ft8Marche() { await post('/api/ft8?on=' + (H && H.enMarche ? 0 : 1) + '&mode=' + g('8-mode').value); ticFt8(); }
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
async function ticAprs() {
  if (!ouverte('aprs')) return;
  try { A = await (await fetch('/api/aprs')).json(); } catch (x) { return; }
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
function ecoute(nom) { g('s-lecteur').src = '/enregistrements/' + nom; g('s-lecteur').play(); }
async function carteSon(arret) {
  const j = await post('/api/son/carte?nom=' + (arret ? '' : enc(g('s-carte').value)));
  ticSon();
}
async function enregistre() { await post('/api/son/enregistrer?on=' + (S && S.enregistrement ? 0 : 1)); ticSon(); }
setInterval(ticSon, 1000);

lienRotor(); avertCat();
// The tab from the address (#carnet, a bookmark), else the last one seen.
if (['station', 'carnet', 'agenda', 'carres', 'frise', 'sked', 'qo100', 'son', 'sstv', 'aprs', 'ft8', 'reglages'].includes(location.hash.slice(1))) vue(location.hash.slice(1));
else try { const n = localStorage.getItem('vue'); if (n) vue(n); } catch (x) {}
tic(); chargeListe(); chargePorts(); chargeSatellites();
setInterval(chargeSatellites, 600000);
setInterval(tic, 1000);
setInterval(chargeListe, 30000);
setInterval(dessinePassages, 5000);
</script>
</body></html>
"""
}
