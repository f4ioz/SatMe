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
 * SatMe-PC's « Trafic satellite » card (the module of that name, off until
 * the owner switches it on), served with [PagePc] in the Suivi tab under
 * « Émission IC-9700 »: the satellite's preset (tone, lowest elevation,
 * power), SO-50's arming, the QSO assistant (the pass, the exchange, the
 * etiquette, a contact to the log), CW and voice memories, APRS through a
 * satellite digipeater and its beacon armed for one pass.
 *
 * The server decides everything (`api/trafic-sat`); this only shows and
 * asks. Every over is a click; « Arrêter » (and Échap, the TX light) stop
 * it. Plain constants: no Kotlin template, no JS template literal.
 */
object TraficLeoJs {

    const val CSS = """
  .leo[hidden] { display:none; }
  .leo .t { display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
  .leo-etat { font-size:11px; padding:1px 7px; border-radius:9px; background:var(--s3, rgba(127,127,127,.2)); }
  .leo-etat.tx { background:var(--danger); color:#fff; }
  .leo-etat.ok { background:var(--ok); color:#000; }
  .leo .leo-stop { margin-left:auto; }
  .leo .ligne { gap:4px 6px; flex-wrap:wrap; }
  .leo input[type=number] { flex:none; width:64px; padding:2px 4px; }
  .leo select { flex:none; max-width:100%; }
  .leo .leo-mem { display:flex; flex-wrap:wrap; gap:4px; margin:4px 0; }
  .leo .leo-mem button { padding:3px 7px; font-size:12px; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .leo .leo-ed { display:grid; grid-template-columns:auto 1fr; gap:3px 6px; align-items:center; margin:4px 0; }
  .leo .leo-ed input { width:100%; box-sizing:border-box; font-family:var(--mono, monospace); font-size:12px; }
  .leo .leo-voix { display:grid; grid-template-columns:1fr auto auto auto; gap:3px 4px; align-items:center; }
  .leo .leo-voix input { min-width:0; font-size:12px; }
  .leo .leo-voix button { padding:2px 6px; font-size:12px; }
  .leo .leo-voix button.rec { color:var(--danger); }
  .leo details { margin-top:6px; }
  .leo details > summary { cursor:pointer; font-weight:600; font-size:var(--fs-s, 13px); }
  .leo .leo-garde { color:var(--warn); }
  .leo .leo-echange { font-family:var(--mono, monospace); background:var(--s1, rgba(127,127,127,.1)); padding:3px 6px; border-radius:6px; display:inline-block; }
  .leo ul.leo-conseils { margin:4px 0 0 18px; padding:0; }
  .leo ul.leo-conseils li { margin:1px 0; }
  .leo .leo-prog { height:4px; background:var(--s1, rgba(127,127,127,.15)); border-radius:2px; overflow:hidden; margin-top:3px; }
  .leo .leo-prog i { display:block; height:100%; width:0; background:var(--danger); }
"""

    const val HTML = """
    <div class="carte leo" id="leo-carte" hidden aria-label="Trafic satellite : émettre par les satellites en orbite basse">
      <div class="t">Trafic satellite <span class="leo-etat" id="leo-etat">—</span><span class="petit" id="leo-sat"></span>
        <button type="button" class="leo-stop" id="leo-stop" onclick="leoArret()" title="Arrêter tout de suite (Échap aussi)">■ Arrêter</button></div>
      <div class="leo-prog" id="leo-prog-l" hidden><i id="leo-prog"></i></div>
      <div class="petit" id="leo-info"></div>
      <div class="petit leo-garde" id="leo-garde"></div>
      <div class="petit" id="leo-passage"></div>

      <details id="leo-d-preset" open><summary>Préréglage du satellite</summary>
        <div class="ligne"><span class="petit">Tonalité</span><select id="leo-ctcss" onchange="leoRegle('ctcss=' + this.value)" title="La sous-porteuse CTCSS que la boucle Doppler met au poste en FM. Auto : celle lue dans la description de l’émetteur (SatNOGS)"></select>
          <span class="petit" id="leo-ctcss-arme"></span>
          <button type="button" id="leo-so50" hidden onclick="leoJoue('so50=1')" title="SO-50 : une porteuse de 2,5 s avec 74,4 Hz démarre la minuterie de 10 min du relais, puis la tonalité du satellite revient">Armer SO-50 (74,4 Hz)</button></div>
        <div class="ligne"><span class="petit">Élévation min.</span><input type="number" id="leo-el" min="0" max="60" step="1" style="width:56px" onchange="leoRegle('el=' + this.value)" title="Sous cette élévation, SatMe refuse de passer le poste en émission pour ce satellite (0 : pas de garde)"><span class="petit">°</span>
          <span class="petit">Puissance</span><input type="number" id="leo-p" min="0" max="100" step="1" style="width:56px" onchange="leoRegle('p=' + this.value)" title="La puissance (en % du poste) de ce satellite, écrite au poste seulement par « Appliquer » (0 : aucune)"><span class="petit">%</span>
          <button type="button" class="discret" onclick="leoRegle('puissance=1')" title="Écrire cette puissance au poste maintenant (sous le plafond de Réglages › Émission). N’émet pas.">Appliquer</button></div>
      </details>

      <details id="leo-d-assist" open><summary>Assistant de QSO</summary>
        <div class="ligne"><span class="petit">Sa station</span><input id="leo-lui" style="width:90px" placeholder="indicatif" onchange="leoRegle('lui=' + enc(this.value))" title="L’indicatif de l’autre station : il remplit {LUI} dans les mémoires">
          <input id="leo-lui-loc" style="width:70px" placeholder="locator" title="Son carré locator, pour le carnet">
          <button type="button" onclick="leoNote()" title="Noter le contact au carnet (le satellite suivi, l’heure de maintenant)">Noter au carnet</button></div>
        <div class="petit">Échange proposé : <span class="leo-echange" id="leo-echange"></span></div>
        <ul class="petit leo-conseils" id="leo-conseils"></ul>
      </details>

      <details id="leo-d-cw"><summary>Mémoires CW</summary>
        <div class="leo-mem" id="leo-cw-b"></div>
        <div class="ligne"><input id="leo-cw-t" style="flex:1;min-width:120px" placeholder="texte libre ({MOI} {LUI} {LOC})" onkeydown="if (event.key === 'Enter') leoCwLibre()"><button type="button" onclick="leoCwLibre()">Envoyer</button></div>
        <div class="ligne"><span class="petit">Vitesse</span><input type="number" id="leo-wpm" min="8" max="35" style="width:50px" onchange="leoRegle('wpm=' + this.value)"><span class="petit">mots/min · note</span>
          <input type="number" id="leo-ton" min="400" max="1200" step="10" style="width:62px" onchange="leoRegle('ton=' + this.value)"><span class="petit">Hz · niveau</span>
          <input type="number" id="leo-niv" min="-30" max="-1" style="width:50px" onchange="leoRegle('niveau=' + this.value)"><span class="petit">dBFS</span></div>
        <details><summary class="petit">Modifier les mémoires</summary><div class="leo-ed" id="leo-cw-ed"></div></details>
        <div class="petit aide">La CW part en tonalité par le son du poste (BLU) : sur un transpondeur linéaire elle revient sur la descente à la même note, comme ta voix. Pas sur un relais FM.</div>
      </details>

      <details id="leo-d-voix"><summary>Mémoires vocales</summary>
        <div class="leo-voix" id="leo-voix"></div>
        <div class="petit" id="leo-voix-msg"></div>
        <div class="petit aide">« ● » enregistre ce micro (30 s au plus ; « ■ » pour finir), « ▶ » l’émet une fois. Le niveau est ramené à celui du panneau.</div>
      </details>

      <details id="leo-d-aprs" hidden><summary>APRS par le satellite</summary>
        <div class="ligne"><button type="button" onclick="leoAprs('position')" title="Ta position (approchée si choisie dans l’onglet APRS), chemin ARISS">Position</button>
          <input id="leo-aprs-st" style="flex:1;min-width:90px" placeholder="statut" maxlength="62"><button type="button" onclick="leoAprs('statut')">Statut</button></div>
        <div class="ligne"><input id="leo-aprs-dest" style="width:90px" placeholder="à (indicatif)"><input id="leo-aprs-msg" style="flex:1;min-width:110px" placeholder="message" maxlength="67"><button type="button" onclick="leoAprs('message')">Message</button></div>
        <div class="ligne"><span class="petit">Balise</span><select id="leo-bal-int" title="Une trame toutes les… (une par minute au plus)"><option value="60">60 s</option><option value="120" selected>2 min</option><option value="300">5 min</option></select>
          <select id="leo-bal-max" title="Au plus, sur ce passage"><option>1</option><option>2</option><option selected>4</option><option>6</option></select>
          <button type="button" id="leo-bal-b" onclick="leoBalise()">Armer pour ce passage</button><span class="petit" id="leo-bal-e"></span></div>
        <div class="petit aide">Chemin ARISS seulement. La balise ne vaut que pour le passage en cours (désarmée au LOS, page fermée, ou ARRÊT), au-dessus de 10°.</div>
      </details>
      <div class="petit aide">Chaque émission part d’un clic ; toutes les gardes du PTT de l’IC-9700 restent (minuterie, plafond, réseau), plus celles du préréglage. Rien ne part tout seul.</div>
    </div>
"""

    const val CODE = """
// ------------------------------------------------------------------ « Trafic satellite » (api/trafic-sat)
var LEO = null, leoCwVu = '', leoVoixVu = '', leoRec = null;
async function leoPost(q, corps) {
  let j = null;
  try { const r = await fetch('api/trafic-sat?' + q + '&client=' + TX_CLIENT, { method: 'POST', body: corps }); j = await r.json(); } catch (x) { return null; }
  if (j && j.module !== undefined) { LEO = j; leoRend(); }
  if (j && j.ok === false && j.erreur) { txt('leo-info', 'Refusé : ' + j.erreur); g('leo-info').classList.add('avert'); }
  return j;
}
function leoRegle(q) { return leoPost(q); }
async function leoJoue(q) {
  if (typeof txPeutDemarrer === 'function' && !(await txPeutDemarrer())) return;
  return leoPost('joue=1&' + q);
}
async function leoArret() { await leoPost('arret=1'); annonce('■ Trafic satellite arrêté', true); }
function leoCwLibre() { const t = g('leo-cw-t').value.trim(); if (t) leoJoue('cw=texte&texte=' + enc(t)); }
function leoAprs(type) {
  const q = type === 'message' ? '&dest=' + enc(g('leo-aprs-dest').value.trim()) + '&texte=' + enc(g('leo-aprs-msg').value) : type === 'statut' ? '&texte=' + enc(g('leo-aprs-st').value) : '';
  leoJoue('aprs=' + type + q);
}
function leoBalise() {
  if (LEO && LEO.balise && LEO.balise.armee) { leoPost('balise=0'); return; }
  leoJoue('balise=1&intervalle=' + g('leo-bal-int').value + '&max=' + g('leo-bal-max').value);
}
async function leoNote() {
  const c = g('leo-lui').value.trim();
  if (!c) { txt('leo-info', 'Indicatif de l’autre station d’abord.'); return; }
  let j = null;
  try { j = await (await fetch('api/carnet/ajoute?c=' + enc(c) + '&l=' + enc(g('leo-lui-loc').value.trim()), { method: 'POST' })).json(); } catch (x) {}
  txt('leo-info', j && j.ok !== false ? 'Contact noté au carnet : ' + c.toUpperCase() : 'Carnet : ' + ((j && j.erreur) || 'refusé'));
}
async function leoTic() {
  const vu = !g('v-station').hidden && !g('leo-carte').hidden;
  const actif = LEO && (LEO.emet || (LEO.balise && LEO.balise.armee));
  if (window.SATME_TELEPHONE || (!vu && !actif && LEO)) return;
  try { const r = await fetch('api/trafic-sat?vie=1&client=' + TX_CLIENT); if (!r.ok) return; LEO = await r.json(); } catch (x) { return; }
  leoRend();
}
setInterval(leoTic, 1000);
function leoRend() {
  const j = LEO, k = g('leo-carte');
  if (!k) return;
  // Hidden while the module is off (and by the modules' mask too).
  const vu = !!(j && j.ok !== false && j.module) && !window.SATME_TELEPHONE;
  k.hidden = !vu;
  if (!vu) return;
  const s = j.sat || {};
  const e = g('leo-etat');
  e.textContent = j.emet ? '● TX' : j.refus ? 'indisponible' : 'prêt';
  e.className = 'leo-etat' + (j.emet ? ' tx' : j.refus ? '' : ' ok');
  e.title = j.refus || '';
  txt('leo-sat', s.nom ? s.nom + (s.el != null ? ' · ' + Math.round(s.el) + '°' : '') + (s.montee ? ' · montée ' + (s.montee / 1e6).toFixed(4).replace('.', ',') + ' MHz' : '') + (j.simule ? ' · simulé' : '') : 'aucun satellite suivi');
  g('leo-stop').disabled = !j.emet && !(j.balise && j.balise.armee);
  const ec = j.enCours;
  g('leo-prog-l').hidden = !ec;
  if (ec) g('leo-prog').style.width = Math.round((ec.progres || 0) * 100) + '%';
  const info = ec ? 'En émission : ' + ec.quoi : (j.erreur ? 'Erreur : ' + j.erreur : j.raison);
  txt('leo-info', info || ''); g('leo-info').classList.toggle('avert', !ec && !!j.erreur);
  txt('leo-garde', j.garde ? 'Garde : ' + j.garde : (!j.emet && j.refus && j.refus !== j.garde ? j.refus : ''));
  // The pass: AOS / LOS countdown and its highest point.
  const t = Date.now();
  let p = '';
  if (s.aos && s.los) p = t < s.aos ? 'AOS dans ' + leoDuree(s.aos - t) : t < s.los ? 'LOS dans ' + leoDuree(s.los - t) : 'passage fini';
  if (p && s.elMax != null) p += ' · culmine à ' + Math.round(s.elMax) + '°';
  if (s.emetteur) p += (p ? ' · ' : '') + s.emetteur;
  txt('leo-passage', p);
  // Preset.
  const c = g('leo-ctcss');
  if (c.options.length !== (j.tons || []).length + 2) c.innerHTML = '<option value="-1">Auto (description)</option><option value="0">Aucune</option>' +
    (j.tons || []).map(x => '<option value="' + x + '">' + (x / 10).toFixed(1).replace('.', ',') + ' Hz</option>').join('');
  const pr = j.preset || {};
  if (document.activeElement !== c) c.value = String(pr.ctcss != null ? pr.ctcss : -1);
  if (document.activeElement !== g('leo-el')) g('leo-el').value = pr.el || 0;
  if (document.activeElement !== g('leo-p')) g('leo-p').value = pr.p || 0;
  txt('leo-ctcss-arme', j.ctcssArme > 0 ? 'au poste : ' + (j.ctcssArme / 10).toFixed(1).replace('.', ',') + ' Hz' : j.ctcssArme === 0 ? 'au poste : aucune' : '');
  g('leo-so50').hidden = !s.armement;
  g('leo-d-preset').querySelectorAll('select,input,button').forEach(x => { x.disabled = s.catnum == null || s.geo; });
  // Assistant.
  if (document.activeElement !== g('leo-lui') && g('leo-lui').value.toUpperCase() !== (j.lui || '')) g('leo-lui').value = j.lui || '';
  txt('leo-echange', j.echange || '');
  const cs = (j.conseils || []).map(x => '<li>' + esc(x) + '</li>').join('');
  if (g('leo-conseils').innerHTML !== cs) g('leo-conseils').innerHTML = cs;
  // CW memories: buttons (their text filled), editors.
  const cwVu = JSON.stringify(j.cw || []);
  if (cwVu !== leoCwVu) {
    leoCwVu = cwVu;
    g('leo-cw-b').innerHTML = (j.cw || []).map(m => '<button type="button" data-cw="' + m.n + '" title="' + escA(m.rendu || '(vide)') + '">' + m.n + ' · ' + esc((m.rendu || '—').slice(0, 22)) + '</button>').join('');
    const ed = g('leo-cw-ed');
    if (!ed.contains(document.activeElement)) ed.innerHTML = (j.cw || []).map(m => '<span class="petit">' + m.n + '</span><input data-cwe="' + m.n + '" value="' + escA(m.texte) + '" maxlength="200">').join('');
  }
  ['wpm', 'ton', 'niv'].forEach(id => { const x = g('leo-' + id), v = id === 'niv' ? j.niveau : j[id]; if (document.activeElement !== x && v != null) x.value = v; });
  g('leo-cw-b').querySelectorAll('button').forEach(b => { b.disabled = !!j.emet || s.genre === 'FM'; });
  // Voice memories.
  const vVu = JSON.stringify(j.voix || []) + (leoRec ? leoRec.n : '');
  if (vVu !== leoVoixVu && !g('leo-voix').contains(document.activeElement)) {
    leoVoixVu = vVu;
    g('leo-voix').innerHTML = (j.voix || []).map(v => '<input data-vn="' + v.n + '" value="' + escA(v.nom) + '" maxlength="40" title="Son nom">' +
      '<button type="button" class="rec" data-vr="' + v.n + '" title="' + (leoRec && leoRec.n === v.n ? 'Finir l’enregistrement' : 'Enregistrer ce micro (30 s au plus)') + '">' + (leoRec && leoRec.n === v.n ? '■' : '●') + '</button>' +
      '<button type="button" data-vp="' + v.n + '" ' + (v.dureeS == null ? 'disabled' : '') + ' title="Émettre une fois' + (v.dureeS != null ? ' (' + String(v.dureeS).replace('.', ',') + ' s)' : '') + '">▶' + (v.dureeS != null ? ' ' + Math.round(v.dureeS) + ' s' : '') + '</button>' +
      '<button type="button" class="discret" data-vx="' + v.n + '" ' + (v.dureeS == null ? 'disabled' : '') + ' title="Effacer">✕</button>').join('');
  }
  // APRS.
  g('leo-d-aprs').hidden = !s.aprs;
  const b = j.balise || {};
  txt('leo-bal-b', b.armee ? 'Désarmer' : 'Armer pour ce passage');
  txt('leo-bal-e', b.armee ? 'armée : ' + b.envoyees + '/' + b.max + (b.prochaineS != null ? ', prochaine dans ' + b.prochaineS + ' s' : '') : '');
}
function leoDuree(ms) { const s = Math.max(0, Math.round(ms / 1000)); return s >= 60 ? Math.floor(s / 60) + ' min ' + String(s % 60).padStart(2, '0') + ' s' : s + ' s'; }
(function () {
  const k = g('leo-carte');
  if (!k) return;
  k.addEventListener('click', ev => {
    const b = ev.target.closest('button'); if (!b || b.disabled) return;
    if (b.dataset.cw) leoJoue('cw=' + b.dataset.cw);
    else if (b.dataset.vp) leoJoue('voix=' + b.dataset.vp);
    else if (b.dataset.vx) { if (confirm('Effacer cette mémoire vocale ?')) leoPost('voixEfface=' + b.dataset.vx); }
    else if (b.dataset.vr) leoEnregistre(+b.dataset.vr);
  });
  k.addEventListener('change', ev => {
    const x = ev.target;
    if (x.dataset.cwe) leoPost('cw' + x.dataset.cwe + '=' + enc(x.value));
    else if (x.dataset.vn) leoPost('n=' + x.dataset.vn + '&voixNom=' + enc(x.value));
  });
})();
// ---- a voice memory: this browser's microphone, 16-bit WAV, 30 s at most, sent to the server (never transmitted here)
async function leoEnregistre(n) {
  if (leoRec) { const r = leoRec; leoRec = null; r.fin(); return; }
  if (!window.isSecureContext || !navigator.mediaDevices) { txt('leo-voix-msg', 'Micro refusé par le navigateur : page non sûre (HTTPS depuis une autre machine).'); return; }
  let flux;
  try { flux = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false } }); }
  catch (x) { txt('leo-voix-msg', 'Micro : ' + (x.message || x.name)); return; }
  const ctx = new AudioContext(), src = ctx.createMediaStreamSource(flux), sp = ctx.createScriptProcessor(4096, 1, 1), morceaux = [];
  let n0 = 0;
  sp.onaudioprocess = ev => { const x = ev.inputBuffer.getChannelData(0); morceaux.push(new Float32Array(x)); n0 += x.length; if (n0 > ctx.sampleRate * 30 && leoRec) { const r = leoRec; leoRec = null; r.fin(); } };
  src.connect(sp); sp.connect(ctx.destination);
  const taux = ctx.sampleRate;
  leoRec = { n, fin: async () => {
    try { sp.disconnect(); src.disconnect(); flux.getTracks().forEach(t => t.stop()); ctx.close(); } catch (x) {}
    const m = Math.min(n0, taux * 30), b = new ArrayBuffer(44 + 2 * m), dv = new DataView(b);
    const s = (o, t) => { for (let i = 0; i < t.length; i++) dv.setUint8(o + i, t.charCodeAt(i)); };
    s(0, 'RIFF'); dv.setUint32(4, 36 + 2 * m, true); s(8, 'WAVE'); s(12, 'fmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true);
    dv.setUint32(24, taux, true); dv.setUint32(28, taux * 2, true); dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); s(36, 'data'); dv.setUint32(40, 2 * m, true);
    let k = 0;
    for (const x of morceaux) for (let i = 0; i < x.length && k < m; i++, k++) dv.setInt16(44 + 2 * k, Math.max(-32768, Math.min(32767, Math.round(x[i] * 32767))), true);
    txt('leo-voix-msg', 'envoi de l’enregistrement…');
    let j = null;
    try { j = await (await fetch('api/trafic-sat/voix?n=' + n + '&client=' + TX_CLIENT, { method: 'POST', body: b })).json(); } catch (x) {}
    txt('leo-voix-msg', j && j.ok !== false ? 'Mémoire ' + n + ' enregistrée (' + (m / taux).toFixed(1).replace('.', ',') + ' s).' : 'Refusé : ' + ((j && j.erreur) || 'pas de réponse'));
    if (j && j.module !== undefined) { LEO = j; }
    leoVoixVu = ''; leoRend();
  } };
  txt('leo-voix-msg', 'Enregistrement de la mémoire ' + n + '… « ■ » pour finir.');
  leoVoixVu = ''; leoRend();
}
"""
}
