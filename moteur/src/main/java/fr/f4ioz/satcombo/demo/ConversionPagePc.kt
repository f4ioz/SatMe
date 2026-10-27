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
 * SatMe-PC's page, the converters' part: the converter table (Réglages ›
 * Poste et rotor), the SDR calibration assistant and the guided alignment of
 * a linear transponder (cockpit, « Aligner… »).
 *
 * Kept apart from [PagePc] (already huge) and inserted in it; each string
 * well under the 64 KB a constant may hold. Plain constants: no Kotlin
 * template in here, and the JS uses no template literals.
 */
object ConversionPagePc {

    /** Réglages › Poste et rotor: the table (replaces the read-only « Convertisseurs » card). */
    const val CARTE = """
    <div class="carte" id="cv-carte">
      <div class="t">Convertisseurs et transverters</div>
      <div class="petit">Une ligne par boîte entre l'antenne et un appareil : LNB, convertisseur descendant, transverter montant. Les fréquences s'écrivent <b>côté ciel</b> (RF) ; SatMe envoie à l'appareil la fréquence intermédiaire : <b>FI = RF − OL</b>, ou <b>OL − RF</b> quand le spectre est inversé. La <b>première ligne qui couvre</b> la fréquence gagne, appareil par appareil (clé SDR, réception du poste, émission du poste).</div>
      <div class="cv-boite"><table class="cv-table" id="cv-table">
        <thead><tr><th title="Ligne utilisée ou non">Active</th><th>Nom</th><th title="Bas de la bande, côté ciel">RF de (MHz)</th><th title="Haut de la bande, côté ciel">RF à (MHz)</th><th title="Oscillateur local">OL (MHz)</th>
          <th title="Oscillateur au-dessus de la bande : FI = OL − RF, le spectre retourné">Inversé</th><th title="Devant la clé SDR (réception)">Clé SDR</th><th title="Devant la réception du poste (CAT)">CAT RX</th><th title="Devant l'émission du poste (CAT)">CAT TX</th><th>FI</th><th></th></tr></thead>
        <tbody id="cv-lignes"></tbody></table></div>
      <div class="ligne"><button onclick="cvAjoute()">+ Ajouter une ligne</button>
        <button id="cv-enr" onclick="cvEnregistre()" disabled>Enregistrer</button>
        <button id="cv-annule" onclick="cvCharge(true)" disabled>Annuler les changements</button>
        <button onclick="cvRetablit()" title="Remettre les lignes proposées par SatMe ; celles de la chaîne QO-100 restent en tête">Rétablir les préréglages</button>
        <button onclick="calOuvre()" title="Mesurer l'erreur de la clé SDR (ppm) ou de l'oscillateur d'un LNB sur une fréquence connue">Calibrer la clé SDR…</button></div>
      <div class="petit" id="cv-etat" style="margin-top:6px"></div>
      <div class="aide">Exemples : LNB 9750 devant la clé SDR (10 489,750 → 739,750 MHz) ; LNB + convertisseur vers 2 m devant la réception de l'IC-9700 ; transverter 2400 attaqué en 432 (OL 1968) devant l'émission. Les lignes « Chaîne QO-100 » suivent la chaîne de station de l'onglet QO-100 (et la modifient en retour). Sans ligne pour une fréquence que le poste ne sait pas recevoir (10 GHz sur un IC-9700), SatMe n'envoie rien au poste et le dit en jaune dans la barre d'état. Au survol, les fréquences du cockpit et de l'onglet SDR montrent la FI réellement envoyée.</div>
    </div>"""

    /** The two dialogs: calibration (SDR) and alignment (a linear transponder). */
    const val DIALOGUES = """
<dialog id="cal-dlg" class="dlg-mt cal-dlg" aria-labelledby="cal-t" onclose="calFerme()">
  <div class="paire" style="margin:0;align-items:center"><b id="cal-t" style="font-size:16px">Calibrer la clé SDR</b><button class="discret" onclick="g('cal-dlg').close()" aria-label="Fermer">✕</button></div>
  <div class="petit" style="margin-top:4px">SatMe accorde la clé SDR sur un signal dont la fréquence est connue, trouve son pic et en déduit l'erreur. Réception seulement : rien n'est émis.</div>
  <div class="cal-grille">
    <div>
      <div class="t" style="margin-top:8px">1. La référence</div>
      <select id="cal-ref" onchange="calChoix()" style="width:100%"></select>
      <div class="ligne" id="cal-gsm-l" hidden><span class="petit">Canal GSM (ARFCN)</span><input id="cal-arfcn" type="number" min="0" max="1023" style="max-width:90px" value="22" onchange="calChoix()"><span class="petit" id="cal-gsm-f"></span></div>
      <div class="ligne" id="cal-perso-l" hidden><span class="petit">Fréquence connue (MHz)</span><input id="cal-perso" inputmode="decimal" style="max-width:140px" placeholder="144,491" onchange="calChoix()"></div>
      <div class="petit" id="cal-expl" style="margin-top:6px"></div>
      <div class="ligne"><span class="petit">Chercher à</span><select id="cal-fen" onchange="calMesure()"><option value="2000">±2 kHz</option><option value="5000">±5 kHz</option><option value="20000">±20 kHz</option><option value="40000">±40 kHz</option><option value="80000">±80 kHz</option></select>
        <label class="petit"><input type="checkbox" id="cal-maintien" onchange="calMesure()"> garder le maximum</label></div>
      <div class="ligne"><button id="cal-go" onclick="calAccorde()">2. Accorder la clé</button><span class="petit" id="cal-msg"></span></div>
    </div>
    <div>
      <div class="t" style="margin-top:8px">3. Le pic <span class="petit" style="font-weight:400">clic sur le spectre : le choisir à la main</span></div>
      <canvas id="cal-sp" width="600" height="180" style="width:100%;height:180px;background:var(--creux);border-radius:8px;cursor:crosshair" onclick="calClic(event)"></canvas>
      <div class="ligne" style="margin-top:4px"><button onclick="calPic = null; calMesure()">Pic automatique</button><span class="petit" id="cal-pic"></span></div>
    </div>
  </div>
  <div class="cal-res" id="cal-res"></div>
  <details style="margin-top:8px"><summary class="petit" style="cursor:pointer">Comment ça marche</summary>
    <div class="petit" style="margin-top:4px;line-height:1.5">Le quartz d'une clé SDR se trompe de quelques ppm à quelques dizaines de ppm (parties par million) : 30 ppm, c'est 28 kHz à 940 MHz. SatMe compare où le signal devrait être et où il apparaît : l'écart divisé par la fréquence reçue par la clé donne les ppm à corriger.<br>
    <b>Derrière un LNB</b>, l'écart mêle deux erreurs : celle de l'oscillateur du LNB (quelques kHz à quelques dizaines de kHz à 10 GHz) et celle de la clé. Une seule mesure ne peut pas les séparer : calibre d'abord la clé sur une référence terrestre (GSM, balise locale), puis mesure la balise QO-100 — ce qui reste est l'oscillateur réel du LNB, à corriger dans la ligne de la table. Un LNB verrouillé sur un GPSDO est juste : l'écart est alors celui de la clé.<br>
    <b>GSM</b> : chaque relais émet un ton pur (FCCH) 67,708 kHz au-dessus du centre de son canal, par salves : coche « garder le maximum ». Trouver un canal actif près de chez soi : un balayage de 935–960 MHz dans l'onglet SDR. Les balises QO-100 sont générées au sol depuis un GPS.</div>
  </details>
</dialog>
<dialog id="al-dlg" class="dlg-mt al-dlg" aria-labelledby="al-t" onclose="alFerme()">
  <div class="paire" style="margin:0;align-items:center"><b id="al-t" style="font-size:16px">Aligner le transpondeur</b><button class="discret" onclick="g('al-dlg').close()" aria-label="Fermer">✕</button></div>
  <div class="petit" style="margin-top:4px">Pour <b id="al-sat">—</b> : le calage RX et le décalage TX sont gardés <b>pour ce satellite</b>. <span id="al-ref"></span></div>
  <ol class="al-etapes" id="al-etapes">
    <li data-e="1"><b>Balise : aligne la descente.</b>
      <div class="petit">Choisis la balise du satellite, écoute-la, puis déplace le calage RX jusqu'à l'entendre juste (la CW à sa tonalité habituelle, le pic au centre de la cascade).</div>
      <div class="ligne"><select id="al-balise" onchange="post('api/emetteur?i=' + this.value)" style="max-width:100%"></select></div>
      <div class="ligne"><span class="petit" style="min-width:90px">Calage RX</span>
        <div class="bouton-gr"><button onclick="regle('calib',-50)">−50</button><button onclick="regle('calib',-10)">−10</button></div>
        <span class="val" id="al-calib" style="min-width:80px;text-align:center">0</span>
        <div class="bouton-gr"><button onclick="regle('calib',10)">+10</button><button onclick="regle('calib',50)">+50</button></div></div>
      <div class="ligne"><button onclick="alVa(2)">C'est juste : étape suivante</button></div></li>
    <li data-e="2"><b>Points dans un coin libre : aligne la montée.</b>
      <div class="petit">Reviens sur le transpondeur, place-toi dans un coin libre (« Dans le transpondeur »), puis <b>passe toi-même en émission</b> (PTT, manipulateur) et envoie des points courts. Déplace le décalage TX jusqu'à t'entendre au centre de ta réception. Relâche l'émission entre deux essais.</div>
      <div class="avert al-tx">L'assistant ne passe jamais en émission : c'est toi qui émets, quand tu le décides.</div>
      <div class="ligne"><select id="al-tp" onchange="post('api/emetteur?i=' + this.value)" style="max-width:100%"></select></div>
      <div class="ligne"><span class="petit" style="min-width:90px">Dans le transpondeur</span>
        <div class="bouton-gr"><button onclick="repos(-1000)">−1 k</button><button onclick="repos(-100)">−100</button><button onclick="repos(100)">+100</button><button onclick="repos(1000)">+1 k</button></div></div>
      <div class="ligne"><span class="petit" style="min-width:90px">Décalage TX</span>
        <div class="bouton-gr"><button onclick="regle('tx',-50)">−50</button><button onclick="regle('tx',-10)">−10</button></div>
        <span class="val" id="al-tx" style="min-width:80px;text-align:center">0</span>
        <div class="bouton-gr"><button onclick="regle('tx',10)">+10</button><button onclick="regle('tx',50)">+50</button></div></div>
      <div class="ligne"><button onclick="alVa(1)">‹ Revenir à la balise</button><button onclick="alVa(3)">Je m'entends juste : étape suivante</button></div></li>
    <li data-e="3"><b>Mémoriser.</b>
      <div class="petit">Ces deux valeurs deviennent la référence de ce satellite : « Revenir à la référence » les retrouve au prochain passage.</div>
      <div class="ligne"><button onclick="post('api/sat/reglage?memorise=1').then(() => { txt('al-fin', 'Mémorisé pour ce satellite.'); })">Mémoriser pour ce satellite</button><span class="petit" id="al-fin"></span></div></li>
  </ol>
</dialog>"""

    /** Its styles. */
    const val CSS = """
  .reg-cartes > #cv-carte { grid-column:1 / -1; }
  .cv-boite { overflow-x:auto; margin-top:8px; }
  .cv-table th { font-size:var(--fs-xs); color:var(--texte-2); font-weight:600; text-align:left; padding:2px 4px; white-space:nowrap; }
  .cv-table td { padding:3px 4px; vertical-align:middle; }
  .cv-table td input[type=text], .cv-table td input:not([type]) { width:100%; min-width:70px; padding:3px 5px; font-variant-numeric:tabular-nums; }
  .cv-table td.cv-nom input { min-width:240px; }
  .cv-table td.cv-fi { font-size:var(--fs-xs); color:var(--texte-2); white-space:nowrap; }
  .cv-table td.cv-fi.pb { color:var(--warn); white-space:normal; min-width:120px; }
  .cv-table td.cv-act { white-space:nowrap; }
  .cv-table td.cv-act button { padding:2px 6px; }
  .cv-table tr.off td:not(.cv-c0) { opacity:.5; }
  .cv-table tr.chaine td.cv-nom input { border-color:rgba(63,224,200,.4); }
  .cal-grille { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1.4fr); gap:10px 16px; }
  .cal-res { margin-top:10px; padding:10px 12px; border:1px solid var(--ligne); border-radius:10px; background:var(--s1); font-size:var(--fs-m); line-height:1.55; }
  .cal-res:empty { display:none; }
  .cal-res .gros { font-size:18px; font-weight:700; font-variant-numeric:tabular-nums; }
  .al-dlg { width:min(640px,96vw); }
  .al-etapes { margin:10px 0 0; padding-left:22px; }
  .al-etapes li { margin-bottom:10px; opacity:.45; }
  .al-etapes li.ici { opacity:1; }
  .al-etapes li:not(.ici) .ligne, .al-etapes li:not(.ici) .al-tx { display:none; }
  @media (max-width:760px) {
    .cal-grille { grid-template-columns:1fr; }
    .cv-table thead { display:none; }
    .cv-table, .cv-table tbody, .cv-table tr, .cv-table td { display:block; width:100%; }
    .cv-table tr { border:1px solid var(--ligne); border-radius:8px; padding:6px; margin-bottom:8px; display:grid; grid-template-columns:1fr 1fr; gap:4px 8px; }
    .cv-table td { padding:0; }
    .cv-table td[data-l]::before { content:attr(data-l); display:block; font-size:var(--fs-xs); color:var(--texte-2); }
    .cv-table td.cv-nom, .cv-table td.cv-fi, .cv-table td.cv-act { grid-column:1 / -1; }
    .cv-table td.cv-nom input { min-width:0; }
  }
"""

    /** Its script (inside the page's own, which has g, txt, post, regle, repos, E). */
    const val JS = """
// ---------------------------------------------------------------- converter table (Réglages › Poste et rotor)
let CVT = null, cvModif = false;
const cvMhz = hz => hz ? (hz / 1e6).toFixed(6).replace(/0{1,3}$/, '').replace('.', ',') : '';
const cvHz = s => { const x = parseFloat(String(s || '').trim().replace(/\s/g, '').replace(',', '.')); return isFinite(x) ? Math.round(x * 1e6) : 0; };
const cvF3 = hz => (hz / 1e6).toLocaleString('fr-FR', { minimumFractionDigits: 3, maximumFractionDigits: 6 });
async function cvCharge(force) {
  if (window.SATME_TELEPHONE) { const k = g('cv-carte'); if (k) k.hidden = true; return; }
  if (cvModif && !force) return;
  try { const r = await fetch('api/conversion'); CVT = r.ok ? await r.json() : null; } catch (x) { CVT = null; }
  if (!CVT || !CVT.bandes) { g('cv-carte').hidden = true; return; }
  cvModif = false; cvDessine(); cvBoutons();
}
function cvBoutons() { g('cv-enr').disabled = !cvModif; g('cv-annule').disabled = !cvModif; }
function cvFi(b) {
  if (!b.ol || !b.bas || !b.haut) return { pb: 'à compléter' };
  if (b.haut <= b.bas) return { pb: 'RF haute au-dessous de la basse' };
  const fi = f => b.inv ? b.ol - f : f - b.ol, a = fi(b.bas), c = fi(b.haut);
  if (a <= 0 || c <= 0) return { pb: 'FI négative : cocher « Inversé » ?' };
  return { txt: cvF3(Math.min(a, c)) + ' – ' + cvF3(Math.max(a, c)) + ' MHz' + (b.inv ? ' (inversé)' : '') };
}
function cvDessine() {
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
  const cb = (i, k, t) => '<td data-l="' + t + '"><input type="checkbox" data-k="' + k + '" data-i="' + i + '"' + (CVT.bandes[i][k] ? ' checked' : '') + ' aria-label="' + t + '"></td>';
  const n = CVT.bandes.length;
  g('cv-lignes').innerHTML = CVT.bandes.map((b, i) => { const f = cvFi(b);
    return '<tr class="' + (b.actif ? '' : 'off') + (b.chaine ? ' chaine' : '') + '">' +
    '<td class="cv-c0" data-l="Active"><input type="checkbox" data-k="actif" data-i="' + i + '"' + (b.actif ? ' checked' : '') + ' aria-label="Active"></td>' +
    '<td class="cv-nom" data-l="Nom"><input data-k="nom" data-i="' + i + '" value="' + esc(b.nom) + '"' + (b.chaine ? ' title="Suit la chaîne de station de l’onglet QO-100"' : '') + '></td>' +
    '<td data-l="RF de (MHz)"><input data-k="bas" data-i="' + i + '" inputmode="decimal" value="' + cvMhz(b.bas) + '"></td>' +
    '<td data-l="RF à (MHz)"><input data-k="haut" data-i="' + i + '" inputmode="decimal" value="' + cvMhz(b.haut) + '"></td>' +
    '<td data-l="OL (MHz)"><input data-k="ol" data-i="' + i + '" inputmode="decimal" value="' + cvMhz(b.ol) + '"></td>' +
    cb(i, 'inv', 'Inversé') + cb(i, 'sdr', 'Clé SDR') + cb(i, 'catRx', 'CAT RX') + cb(i, 'catTx', 'CAT TX') +
    '<td class="cv-fi' + (f.pb ? ' pb' : '') + '" data-l="FI">' + (f.pb || f.txt) + '</td>' +
    '<td class="cv-act"><button data-m="-1" data-i="' + i + '"' + (i ? '' : ' disabled') + ' title="Monter (la première ligne qui couvre gagne)" aria-label="Monter">↑</button>' +
    '<button data-m="1" data-i="' + i + '"' + (i < n - 1 ? '' : ' disabled') + ' title="Descendre" aria-label="Descendre">↓</button>' +
    '<button data-x="1" data-i="' + i + '" title="Supprimer cette ligne" aria-label="Supprimer">✕</button></td></tr>'; }).join('');
  cvResume();
}
function cvResume() {
  const t = [], k = CVT && CVT.cle;
  if (k && k.fi !== k.ciel) t.push('La clé SDR reçoit en ce moment ' + cvF3(k.fi) + ' MHz pour ' + cvF3(k.ciel) + ' MHz.');
  const c = E && E.conversion;
  if (c && c.dl && c.dl.bande) t.push('Poste RX : ' + cvF3(c.dl.fi) + ' MHz pour ' + cvF3(c.dl.ciel) + ' (« ' + c.dl.bande + ' »).');
  if (c && c.ul && c.ul.bande) t.push('Poste TX : ' + cvF3(c.ul.fi) + ' MHz pour ' + cvF3(c.ul.ciel) + ' (« ' + c.ul.bande + ' »).');
  if (c && c.avert) t.push('⚠ ' + c.avert);
  txt('cv-etat', (cvModif ? 'Modifications non enregistrées. ' : '') + t.join(' '));
}
function cvLit(e) {
  const i = +e.dataset.i, k = e.dataset.k; if (!CVT || !CVT.bandes[i] || !k) return;
  const b = CVT.bandes[i];
  if (e.type === 'checkbox') b[k] = e.checked;
  else if (k === 'nom') b.nom = e.value;
  else b[k] = cvHz(e.value);
  cvModif = true; cvBoutons();
  const tr = e.closest('tr'); if (tr) { tr.classList.toggle('off', !b.actif); const f = cvFi(b), td = tr.querySelector('.cv-fi'); td.textContent = f.pb || f.txt; td.classList.toggle('pb', !!f.pb); }
  cvResume();
}
function cvAjoute() {
  if (!CVT) return;
  if (CVT.bandes.length >= CVT.max) { txt('cv-etat', 'La table est pleine.'); return; }
  CVT.bandes.push({ nom: 'Nouvelle ligne', bas: 0, haut: 0, ol: 0, inv: false, sdr: false, catRx: false, catTx: false, actif: true, chaine: '' });
  cvModif = true; cvDessine(); cvBoutons();
}
async function cvEnregistre() {
  if (!CVT) return;
  const r = await post('api/conversion/table?t=' + encodeURIComponent(JSON.stringify(CVT.bandes)));
  if (r && r.ok !== false) { cvModif = false; await cvCharge(true); txt('cv-etat', 'Enregistré. ' + g('cv-etat').textContent); }
}
async function cvRetablit() {
  if (!confirm('Remettre les préréglages de SatMe ? Les lignes ajoutées ou modifiées sont remplacées (celles de la chaîne QO-100 restent).')) return;
  await post('api/conversion/retablir'); cvModif = false; cvCharge(true);
}
document.addEventListener('DOMContentLoaded', () => {
  const tb = g('cv-lignes'); if (!tb) return;
  tb.addEventListener('change', ev => { if (ev.target.dataset.k) cvLit(ev.target); });
  tb.addEventListener('input', ev => { if (ev.target.dataset.k && ev.target.type !== 'checkbox') { cvModif = true; cvBoutons(); } });
  tb.addEventListener('click', ev => {
    const b = ev.target.closest('button'); if (!b || !CVT) return;
    const i = +b.dataset.i;
    if (b.dataset.x) { if (!confirm('Supprimer « ' + CVT.bandes[i].nom + ' » ?')) return; CVT.bandes.splice(i, 1); }
    else if (b.dataset.m) { const j = i + +b.dataset.m; if (j < 0 || j >= CVT.bandes.length) return; const t = CVT.bandes[i]; CVT.bandes[i] = CVT.bandes[j]; CVT.bandes[j] = t; }
    else return;
    cvModif = true; cvDessine(); cvBoutons();
  });
});
// The IF actually sent, on the frequencies (cockpit) and the status bar's warning.
function cvTitres() {
  const c = E && E.conversion; if (!c) return;
  const t = (x, qui) => !x ? '' : x.refus ? 'Rien n’est envoyé au poste : ' + x.refus
    : 'FI envoyée au poste : ' + cvF3(x.fi) + ' MHz' + (x.bande ? ' (« ' + x.bande + ' »)' : ' (sans convertisseur)');
  const dl = g('dl'), ul = g('ul');
  if (dl) dl.title = t(c.dl); if (ul) ul.title = t(c.ul);
  if (g('cv-carte') && !g('cv-carte').hidden && g('cv-etat')) cvResume();
}
function cvBarre() {
  const c = E && E.conversion, b = g('sb-cat'); if (!b) return;
  if (c && c.avert) { b.className = 'sb-i warn'; b.title = 'Le poste : ' + c.avert; }
  else b.title = 'Le poste : connecter ou déconnecter le CAT d’un clic (réglages : Réglages › Poste et rotor)';
}

// ---------------------------------------------------------------- calibration assistant (receive only)
let CAL = null, calPic = null, calMinuteur = 0, calRefHz = 0, calDernier = null;
async function calOuvre() {
  if (window.SATME_TELEPHONE) return;
  if (!CAL) { try { CAL = await (await fetch('api/calibration')).json(); } catch (x) { CAL = null; } }
  if (!CAL || !CAL.references) { alert('Calibration indisponible ici.'); return; }
  const s = g('cal-ref');
  if (!s.options.length) s.innerHTML = CAL.references.map(r => '<option value="' + r.cle + '">' + r.nom + ' — ' + cvF3(r.hz) + ' MHz</option>').join('') +
    '<option value="gsm">GSM : ton FCCH d’un relais (canal + 67,708 kHz)</option><option value="perso">Une fréquence connue (balise locale…)</option>';
  calChoix();
  if (!g('cal-dlg').open) g('cal-dlg').showModal();
}
function calFerme() { clearInterval(calMinuteur); calMinuteur = 0; }
async function calChoix() {
  const k = v('cal-ref');
  g('cal-gsm-l').hidden = k !== 'gsm'; g('cal-perso-l').hidden = k !== 'perso';
  calPic = null; calRefHz = 0;
  const r = CAL.references.find(x => x.cle === k);
  if (r) { calRefHz = r.hz; txt('cal-expl', r.explication + ' Derrière le LNB : sa ligne de la table (clé SDR) donne la FI.'); g('cal-fen').value = '40000'; g('cal-maintien').checked = false; }
  else if (k === 'gsm') {
    g('cal-fen').value = '40000'; g('cal-maintien').checked = true;
    try { const j = await (await fetch('api/calibration/fcch?arfcn=' + encodeURIComponent(v('cal-arfcn')))).json();
      if (j.ok) { calRefHz = j.hz; txt('cal-gsm-f', '→ ' + cvF3(j.hz) + ' MHz'); } else txt('cal-gsm-f', j.erreur); } catch (x) {}
    txt('cal-expl', 'Le ton FCCH d’un relais GSM : précis à quelques centièmes de ppm. Choisis un canal entendu fort chez toi (balayage 935–960 MHz dans l’onglet SDR).');
  } else {
    calRefHz = cvHz(v('cal-perso')); g('cal-fen').value = '5000'; g('cal-maintien').checked = false;
    txt('cal-expl', 'Une balise ou un émetteur dont la fréquence est sûre (calé sur un GPS de préférence).');
  }
  g('cal-go').disabled = !calRefHz;
}
async function calAccorde() {
  if (!calRefHz) return;
  txt('cal-msg', 'accord…');
  try { const j = await (await fetch('api/calibration/accorde?hz=' + calRefHz, { method: 'POST' })).json();
    txt('cal-msg', j.ok ? 'clé sur ' + cvF3(calRefHz) + ' MHz' + (j.fi !== calRefHz ? ' (FI ' + cvF3(j.fi) + ' MHz)' : '') : j.erreur);
    if (!j.ok) return; } catch (x) { txt('cal-msg', 'pas de réponse'); return; }
  clearInterval(calMinuteur); calMinuteur = setInterval(calMesure, 700); calMesure();
}
function calClic(ev) {
  const z = calDernier && calDernier.zoom; if (!z) return;
  const c = g('cal-sp'), r = c.getBoundingClientRect();
  calPic = Math.round(z.de + (z.a - z.de) * (ev.clientX - r.left) / r.width); calMesure();
}
async function calMesure() {
  if (!calRefHz || !g('cal-dlg').open || !calMinuteur) return;
  const lobes = v('cal-ref') === 'qo100-psk' ? 1 : 0;
  let j = null;
  try { j = await (await fetch('api/calibration/mesure?hz=' + calRefHz + '&fenetre=' + v('cal-fen') + '&maintien=' + (g('cal-maintien').checked ? 1 : 0) +
    '&lobes=' + lobes + (calPic != null ? '&pic=' + calPic : ''))).json(); } catch (x) { return; }
  calDernier = j; calTrace(j); calResultat(j);
}
function calTrace(j) {
  const c = g('cal-sp'), x = c.getContext('2d'), W = c.width = Math.round(c.clientWidth * (devicePixelRatio || 1)) || 600, H = c.height = Math.round(180 * (devicePixelRatio || 1));
  x.clearRect(0, 0, W, H);
  const z = j && j.zoom; if (!z) return;
  const d = z.db, n = d.length, mn = Math.min(...d.filter(a => a > -199)), mx = Math.max(...d);
  const bas = Math.min(mn, mx - 20) - 3, haut = mx + 3, y = a => H - (Math.max(a, bas) - bas) / (haut - bas) * (H - 14) - 4;
  const fx = hz => (hz - z.de) / (z.a - z.de) * W;
  x.fillStyle = 'rgba(255,180,84,.08)'; x.fillRect(fx(j.ciel - z.fenetre), 0, fx(j.ciel + z.fenetre) - fx(j.ciel - z.fenetre), H);
  x.strokeStyle = '#3FE0C8'; x.lineWidth = 1.5 * (devicePixelRatio || 1); x.beginPath();
  d.forEach((a, i) => { const px = i / (n - 1) * W; if (i) x.lineTo(px, y(a)); else x.moveTo(px, y(a)); }); x.stroke();
  x.setLineDash([5, 4]); x.strokeStyle = '#9AA7B4'; x.beginPath(); x.moveTo(fx(j.ciel), 0); x.lineTo(fx(j.ciel), H); x.stroke(); x.setLineDash([]);
  if (j.pic) { x.strokeStyle = j.pic.manuel ? '#B07CFF' : '#FFD166'; x.beginPath(); x.moveTo(fx(j.pic.ciel), 0); x.lineTo(fx(j.pic.ciel), H); x.stroke(); }
  x.fillStyle = '#9AA7B4'; x.font = (11 * (devicePixelRatio || 1)) + 'px system-ui';
  x.fillText(cvF3(z.de), 4, H - 4); const t = cvF3(z.a); x.fillText(t, W - x.measureText(t).width - 4, H - 4);
  x.fillText('attendu', fx(j.ciel) + 4, 12 * (devicePixelRatio || 1));
}
function calResultat(j) {
  const sg = (a, u) => (a > 0 ? '+' : a < 0 ? '−' : '±') + Math.abs(a).toLocaleString('fr-FR') + u;
  if (!j || !j.ok) { g('cal-res').textContent = j && j.erreur || ''; txt('cal-pic', ''); return; }
  if (!j.pic) { g('cal-res').textContent = j.conseil || ''; txt('cal-pic', ''); return; }
  txt('cal-pic', (j.pic.manuel ? 'choisi à la main : ' : 'pic à ') + cvF3(j.pic.ciel) + ' MHz' + (j.pic.manuel ? '' : ', ' + String(j.pic.snr).replace('.', ',') + ' dB au-dessus du bruit'));
  let h = '<div>Écart : <span class="gros">' + sg(j.ecart, ' Hz') + '</span> <span class="petit">(la clé reçoit ' + cvF3(j.fi) + ' MHz, correction actuelle ' + j.ppm + ' ppm)</span></div>';
  if (j.fictif) h += '<div class="avert">Générateur d’essai ou enregistrement : la mesure montre la marche à suivre, pas l’erreur d’une vraie clé.</div>';
  const cle = '<div style="margin-top:6px">' + (j.bande ? 'Si le LNB est juste (verrouillé sur un GPSDO), c’est la clé qui se trompe' : 'La clé se trompe') +
    ' de <b>' + sg(j.ppmEcart, ' ppm') + '</b> : sa correction devient <b>' + j.ppmCleEntier + ' ppm</b> (' + String(j.ppmCle).replace('.', ',') + ').' +
    ' <button onclick="calPpm(' + j.ppmCleEntier + ')"' + (j.ppmCleEntier === j.ppm ? ' disabled' : '') + '>Enregistrer ' + j.ppmCleEntier + ' ppm dans la clé</button></div>';
  if (j.bande) {
    const b = j.bande;
    h += '<div style="margin-top:6px">Derrière « ' + b.nom + ' » : si la clé est déjà calibrée, l’oscillateur réel est <b>' + cvF3(b.olReel) + ' MHz</b> (' +
      sg(b.erreur, ' Hz') + ', ' + sg(b.ppmOl, ' ppm') + ' de ' + cvF3(b.ol) + ').' +
      (b.credible ? ' <button onclick="calOl(' + b.i + ', ' + JSON.stringify(b.nom).replace(/"/g, '&quot;') + ', ' + b.olReel + ')">Corriger l’OL de cette ligne</button>' : ' <span class="avert">Plus de 1 MHz : mauvaise référence ou mauvais préréglage ?</span>') + '</div>';
    h += cle + '<div class="petit" style="margin-top:6px">Une seule mesure ne sépare pas les deux erreurs : choisis selon ta station. Après une correction de l’OL, remets à zéro le calage de l’onglet QO-100.</div>';
  } else h += cle;
  g('cal-res').innerHTML = h;
}
async function calPpm(n) {
  const j = await (await fetch('api/calibration/ppm?v=' + n, { method: 'POST' })).json();
  txt('cal-msg', j.ok ? 'correction de la clé : ' + j.ppm + ' ppm' : j.erreur); calMesure();
}
async function calOl(i, nom, hz) {
  const j = await (await fetch('api/calibration/ol?i=' + i + '&nom=' + encodeURIComponent(nom) + '&hz=' + hz, { method: 'POST' })).json();
  txt('cal-msg', j.ok ? 'OL de la ligne : ' + cvF3(j.ol) + ' MHz' : j.erreur); cvCharge(true); calMesure();
}

// ---------------------------------------------------------------- guided alignment of a linear transponder (never transmits)
let alEtape = 1, alMinuteur = 0;
function alOuvre() {
  if (!E || !E.suivi) { alert('Suis d’abord un satellite (à transpondeur linéaire).'); return; }
  alVa(1); alMaj();
  clearInterval(alMinuteur); alMinuteur = setInterval(alMaj, 1000);
  if (!g('al-dlg').open) g('al-dlg').showModal();
}
function alFerme() { clearInterval(alMinuteur); alMinuteur = 0; }
function alVa(n) { alEtape = n; document.querySelectorAll('#al-etapes > li').forEach(li => li.classList.toggle('ici', +li.dataset.e === n)); if (n === 3) txt('al-fin', ''); }
function alMaj() {
  if (!E) return;
  txt('al-sat', E.suivi ? E.suivi.nom : '—');
  const rs = E.reglageSat || {}, hz = a => (a > 0 ? '+' : '') + (a || 0) + ' Hz';
  txt('al-calib', hz(rs.calib)); txt('al-tx', hz(rs.tx));
  txt('al-ref', rs.refCalib == null ? 'Pas encore de référence.' : 'Référence : RX ' + hz(rs.refCalib) + ' · TX ' + hz(rs.refTx) + '.');
  const l = E.emetteurs || [], cle = JSON.stringify(l.map(t => t.desc)) + E.emetteur;
  if (g('al-balise').dataset.vu !== cle) {
    g('al-balise').dataset.vu = cle;
    const balise = t => /balise|beacon|\bcw\b|telem/i.test((t.desc || '') + ' ' + (t.mode || ''));
    const opt = f => l.map((t, i) => f(t) ? '<option value="' + i + '"' + (i === E.emetteur ? ' selected' : '') + '>' + String(t.desc + (t.mode ? ' · ' + t.mode : '')).replace(/</g, '&lt;') + '</option>' : '').join('');
    g('al-balise').innerHTML = opt(balise) || '<option value="' + E.emetteur + '">aucune balise connue : l’émetteur actuel</option>';
    g('al-tp').innerHTML = opt(t => t.lineaire) || opt(() => true);
  }
}
"""
}
