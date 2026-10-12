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
  .discret { background:transparent; border:none; }
  nav { display:flex; gap:4px; }
  nav button { background:transparent; border:1px solid transparent; color:var(--gris); padding:6px 14px; }
  nav button.actif { color:var(--cyan); border-color:var(--bord); background:var(--carte); }
  .grille2 { display:grid; grid-template-columns:minmax(320px,1fr) minmax(420px,1.6fr); gap:12px; padding:12px 16px; }
  @media (max-width:900px) { .grille2 { grid-template-columns:1fr; } }
  td.act { white-space:nowrap; text-align:right; }
  td.act button { padding:3px 8px; font-size:12px; }
</style></head>
<body>
<header>
  <h1>SatMe <span class="cyan">PC</span></h1>
  <span class="g" id="info-station"></span>
  <span class="g" id="cat-statut-court"></span>
  <nav id="onglets">
    <button data-v="station" class="actif">Station</button><button data-v="carnet">Carnet</button><button data-v="agenda">Agenda</button><button data-v="carres">Carrés</button><button data-v="son">Son</button><button data-v="sstv">SSTV</button><button data-v="aprs">APRS</button><a href="/journal" style="text-decoration:none"><button title="Les passages enregistrés, rejoués">Journal</button></a><button data-v="reglages">Réglages</button>
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
      <svg id="ciel" viewBox="-110 -110 220 220" style="width:100%;max-width:460px;display:block;margin:10px auto 0" role="img" aria-label="Le ciel : passage et position du satellite"></svg>
      <div class="petit" id="infos-passage" style="text-align:center"></div>
      <div class="t" style="margin-top:12px">Ses prochains passages</div>
      <table><tbody id="suivants"></tbody></table>
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
      <details id="conv" open><summary class="petit" style="cursor:pointer">Convertisseurs (LNB, transverter)</summary>
        <div class="ligne"><span class="petit" style="min-width:150px">Réception : OL (MHz)</span><input id="c-rxOl" placeholder="9750 ; 0 = aucun" style="max-width:130px">
          <label class="petit"><input type="checkbox" id="c-rxInv"> inverseur</label></div>
        <div class="ligne"><span class="petit" style="min-width:150px">Émission : OL (MHz)</span><input id="c-txOl" placeholder="0 = aucun" style="max-width:130px">
          <label class="petit"><input type="checkbox" id="c-txInv"> inverseur</label>
          <button onclick="convertisseurs()">Appliquer</button></div>
        <div class="petit">Le poste reçoit et émet sur la fréquence intermédiaire ; l'affichage reste celle du satellite.</div>
      </details>
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
  if (n === 'carres') chargeCarres();
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
    txt('c-nb', String(C.length)); txt('c-carres', String(j.carres)); txt('c-attente', String(j.nonDeposes));
    dessineCarnet();
  } catch (x) {}
}
function dessineCarnet() {
  const f = v('c-filtre').toUpperCase();
  g('c-liste').innerHTML = C.filter(e => !f || [e.c, e.l, e.sat, e.nm, e.qth].join(' ').toUpperCase().includes(f)).map(e =>
    '<tr><td>' + new Date(e.t).toISOString().slice(0, 16).replace('T', ' ') + '</td><td><b>' + esc(e.c) + '</b></td><td>' + esc(e.l) +
    '</td><td>' + esc(e.sat) + '</td><td>' + esc(e.md) + '</td><td>' + esc(e.rs) + '/' + esc(e.rr) + '</td><td>' + esc(e.nm) +
    '</td><td class="act">' + (e.envoye ? '<span class="vert" title="déposé au carnet en ligne">✓</span> ' : '<button title="Déposer au carnet en ligne" onclick="depose(' + e.t + ')">⬆</button> ') +
    '<button title="Modifier" onclick="edite(' + e.t + ')">✎</button></td></tr>').join('') ||
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
  if (!i) { i = new Image(); i.onload = () => dessineCarres(); i.src = '/journal/tuile?z=' + z + '&x=' + xw + '&y=' + y; qTuiles.set(k, i);
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
if (['station', 'carnet', 'agenda', 'carres', 'son', 'sstv', 'aprs', 'reglages'].includes(location.hash.slice(1))) vue(location.hash.slice(1));
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
