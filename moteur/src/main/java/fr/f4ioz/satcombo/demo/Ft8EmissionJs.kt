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
 * SatMe-PC's FT8/FT4 transmit panel (the FT8 tab of the cockpit's
 * « Modes »), served with [PagePc] and inserted in it: the six messages
 * (Tx1–Tx6, editable), « Appeler CQ », enable and halt, the TX frequency
 * (its marker on the waterfall, Maj+clic to move it), the slot parity, the
 * automatic sequence, the attendance watchdog's countdown, where the signal
 * goes on the satellite, the finished contact to note; and a band on every
 * view while transmitting is armed, its « Arrêter » always there (Échap too).
 *
 * The server decides everything (`api/ft8/tx`); this only shows and asks.
 * Plain constants: no Kotlin template, no JS template literal, each well
 * under 64 KB.
 */
object Ft8EmissionJs {

    const val CSS = """
  .f8tx { margin:0; }
  .f8tx[hidden] { display:none; }
  .f8tx-tete { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
  .f8tx-tete b { font-size:15px; }
  .f8tx-grille { display:grid; grid-template-columns:minmax(0, 1.3fr) minmax(0, 1fr); gap:14px; margin-top:8px; }
  @media (max-width: 900px) { .f8tx-grille { grid-template-columns:1fr; } }
  .f8tx-ligne { display:flex; gap:6px; align-items:center; margin:3px 0; }
  .f8tx-ligne label { min-width:52px; font-weight:700; cursor:pointer; white-space:nowrap; }
  .f8tx-ligne input[type=text] { flex:1; min-width:0; font-family:ui-monospace,Consolas,monospace; text-transform:uppercase; }
  .f8tx-ligne.suivant input[type=text] { border-color:var(--tx); box-shadow:0 0 0 2px rgba(255,90,54,.25); }
  .f8tx-ligne.envoi input[type=text] { background:rgba(255,90,54,.18); }
  .f8tx-type { font-size:var(--fs-xs); color:var(--gris); min-width:64px; }
  .f8tx-type.non { color:var(--rose); }
  .f8tx-reglages .ligne { margin:4px 0; flex-wrap:wrap; }
  .f8tx-qso { margin-top:8px; padding:6px 10px; border-radius:8px; background:var(--s3); font-size:var(--fs-s); }
  .f8tx-qso b { color:var(--clair); }
  .f8tx-barre { height:4px; border-radius:2px; background:var(--s3); overflow:hidden; margin-top:6px; }
  .f8tx-barre i { display:block; height:100%; background:var(--tx); width:0; }
  .f8tx-noter { margin-top:10px; padding:8px 10px; border:1px solid var(--vert); border-radius:8px; }
  .f8tx-noter input { max-width:110px; }
  .f8tx-chien { font-variant-numeric:tabular-nums; }
  .f8tx-chien.bas { color:var(--rose); font-weight:700; }
  .f8tx-bandeau { position:fixed; left:50%; bottom:14px; transform:translateX(-50%); z-index:60; display:flex; gap:10px; align-items:center;
    background:var(--tx); color:#fff; padding:8px 14px; border-radius:22px; box-shadow:0 4px 18px rgba(0,0,0,.45); font-weight:700; max-width:calc(100vw - 32px); }
  .f8tx-bandeau[hidden] { display:none; }
  .f8tx-bandeau .petit { color:#fff; opacity:.9; font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .f8tx-bandeau button { background:#fff; color:var(--tx); border:none; font-weight:800; white-space:nowrap; }
  .f8tx-bandeau #f8tx-b-t { white-space:nowrap; }
  @media (max-width: 600px) { .f8tx-bandeau { top:58px; bottom:auto; left:16px; right:16px; transform:none; padding:6px 10px; justify-content:space-between; }
    .f8tx-bandeau .petit { display:none; } }
  .f8tx-pastille.tx { background:var(--tx); color:#fff; }
  .f8tx-pastille.arme { background:rgba(255,180,84,.22); color:var(--ambre); }
  .f8-rep { margin-left:4px; }
"""

    /** The band shown on every view while FT8/FT4 transmitting is armed. */
    const val BANDEAU = """
<div id="f8tx-bandeau" class="f8tx-bandeau" hidden role="status" aria-live="polite">
  <span id="f8tx-b-t">FT8 · émission armée</span><span class="petit" id="f8tx-b-d"></span>
  <button onclick="f8txArret()" title="Arrêter l'émission FT8/FT4 (Échap)">■ Arrêter l'émission</button>
</div>"""

    const val HTML = """
<div class="carte f8tx" id="f8tx" hidden>
  <div class="f8tx-tete">
    <b>Émission</b><span class="pastille f8tx-pastille" id="f8tx-pastille">réception seule</span>
    <button class="primaire" id="f8tx-cq" onclick="f8txCq()" title="Lancer un appel général dans le créneau choisi (pair ou impair). Il se répète jusqu'à une réponse, ou jusqu'au chien de garde.">📣 Appeler CQ</button>
    <button id="f8tx-actif" onclick="f8txActive()" title="Reprendre l'envoi du message choisi (Tx1–Tx6) dans ses créneaux">Activer l'émission</button>
    <button class="arret" id="f8tx-stop" onclick="f8txArret()" title="Couper l'émission maintenant, PTT relâché (Échap marche partout)">■ Arrêter l'émission</button>
    <span class="petit" id="f8tx-statut" style="flex:1;min-width:180px"></span>
  </div>
  <div class="f8tx-barre" id="f8tx-barre" hidden><i id="f8tx-progres"></i></div>
  <div class="f8tx-qso" id="f8tx-qso">Double-clic sur une ligne décodée (ou ↩) pour répondre à cette station ; « Appeler CQ » pour lancer un appel.</div>
  <div class="f8tx-grille">
    <div>
      <div class="paire" style="margin:0 0 4px"><b class="petit">Messages</b><span class="petit">le prochain envoyé est encadré ; clic sur Tx pour en choisir un autre</span></div>
      <div id="f8tx-msgs"></div>
    </div>
    <div class="f8tx-reglages">
      <div class="ligne"><span class="petit" style="min-width:110px">Fréquence TX</span>
        <input id="f8tx-hz" inputmode="numeric" style="max-width:80px" onchange="f8txPost('hz=' + enc(this.value))" aria-label="Fréquence audio d'émission (Hz)"><span class="petit">Hz</span>
        <button onclick="f8txLibre()" title="Mettre la fréquence d'émission sur la place libre vue dans la cascade">▼ place libre</button></div>
      <div class="ligne"><label class="petit" title="SatMe ne déplace jamais la fréquence d'émission, même vers une place libre au début d'un QSO"><input type="checkbox" id="f8tx-tient" onchange="f8txPost('tient=' + (this.checked ? 1 : 0))"> Garder la fréquence d'émission</label></div>
      <div class="ligne"><span class="petit" style="min-width:110px">CQ en créneau</span>
        <select id="f8tx-pair" onchange="f8txPost('pair=' + this.value)"><option value="1">pair (1er)</option><option value="0">impair (2e)</option></select>
        <span class="petit" title="En réponse à une station, SatMe émet dans le créneau opposé au sien">réponse : créneau opposé à la station</span></div>
      <div class="ligne"><label class="petit"><input type="checkbox" id="f8tx-auto" onchange="f8txPost('auto=' + (this.checked ? 1 : 0))"> Séquence automatique</label>
        <label class="petit" title="Tx4 envoie RRR (la station doit encore dire 73) au lieu de RR73"><input type="checkbox" id="f8tx-rrr" onchange="f8txPost('rrr=' + (this.checked ? 1 : 0))"> RRR</label></div>
      <div class="ligne"><span class="petit" style="min-width:110px" title="Sans geste de l'opérateur pendant ce temps, l'émission s'arrête (règle FT8/FT4 : pas de QSO sans opérateur)">Chien de garde</span>
        <select id="f8tx-chien" onchange="f8txPost('chien=' + this.value)"><option value="1">1 min</option><option value="2">2 min</option><option value="3">3 min</option><option value="5">5 min</option><option value="10">10 min</option></select>
        <span class="petit f8tx-chien" id="f8tx-chien-r"></span></div>
      <div class="ligne"><span class="petit" style="min-width:110px">Niveau audio</span>
        <input type="range" id="f8tx-niv" min="-30" max="0" step="1" style="flex:1;max-width:160px" onchange="f8txPost('niveau=' + this.value)" aria-label="Niveau audio envoyé au poste (dB)"><span class="petit" id="f8tx-niv-v"></span></div>
      <div class="ligne"><label class="petit" title="Pendant l'émission armée, SatMe met le poste en USB-D / LSB-D (les deux bandes en mode satellite) : le son vient alors de DATA MOD (USB). Le mode d'avant est remis à l'arrêt. Jamais pendant le PTT."><input type="checkbox" id="f8tx-donnees" onchange="f8txPost('donnees=' + (this.checked ? 1 : 0))"> Mode données (USB-D)</label>
        <span class="petit" id="f8tx-mode"></span></div>
      <div class="ligne"><span class="petit" id="f8tx-donnees-aide"></span></div>
      <div class="ligne"><span class="petit" id="f8tx-horloge"></span></div>
      <div class="ligne"><span class="petit" id="f8tx-ou"></span></div>
    </div>
  </div>
  <div class="f8tx-noter" id="f8tx-noter" hidden>
    <div><b>QSO terminé</b> <span class="petit" id="f8tx-n-titre"></span></div>
    <div class="ligne" style="flex-wrap:wrap">
      <input id="f8tx-nc" placeholder="INDICATIF" style="text-transform:uppercase" aria-label="Indicatif">
      <input id="f8tx-nl" placeholder="Carré" style="text-transform:uppercase" aria-label="Carré">
      <span class="petit">envoyé</span><input id="f8tx-nrs" style="max-width:60px" aria-label="Rapport envoyé">
      <span class="petit">reçu</span><input id="f8tx-nrr" style="max-width:60px" aria-label="Rapport reçu">
      <input id="f8tx-nn" placeholder="Note" style="max-width:200px" aria-label="Note">
      <button class="primaire" onclick="f8txNote()">✓ Noter le QSO</button>
      <button onclick="f8txPost('oublie=1')">Ignorer</button>
      <span class="petit" id="f8tx-n-etat"></span>
    </div>
  </div>
  <div class="petit aide" style="margin-top:8px">L'émission passe par le PTT de l'IC-9700 que SatMe tient déjà (Réglages › Émission) : ses gardes restent (minuterie, plafond de puissance, autorisation par le réseau, balises et LEILA sur QO-100). Sur un satellite, la montée suit le Doppler du transpondeur et le décalage d'émission du satellite (« Aligner… ») ; le signal revient sur la descente à la même fréquence audio. <b>Règle FT8/FT4 : pas de QSO robotisé ni sans opérateur.</b> Un QSO ne commence que par ton geste (double-clic, « Appeler CQ ») ; la séquence peut ensuite avancer seule, mais sans geste pendant la durée du chien de garde l'émission s'arrête, et la page fermée l'arrête aussi. L'horloge du PC doit être juste : au-delà de ±0,5 s (FT4) ou ±1 s (FT8), SatMe refuse d'émettre.</div>
</div>"""

    const val CODE = """
// ------------------------------------------------------------------ FT8/FT4 transmit (api/ft8/tx)
var F8TX = null, f8txVu = '', f8txNoteVu = '', f8txLignes = [];
async function f8txPost(q) {
  let j = null;
  try { const r = await fetch('api/ft8/tx?' + q + '&client=' + TX_CLIENT, { method: 'POST' }); j = await r.json(); } catch (x) { return null; }
  if (j && j.ok === false && j.erreur) { txt('f8tx-statut', j.erreur); g('f8tx-statut').style.color = 'var(--rose)'; }
  if (j && j.ok !== false) { F8TX = j; f8txRend(); }
  else if (j && j.arme !== undefined) { F8TX = j; f8txRend(); txt('f8tx-statut', j.erreur || ''); g('f8tx-statut').style.color = 'var(--rose)'; }
  return j;
}
async function f8txTic() {
  const vu = !g('v-ft8').hidden;
  if (!vu && !(F8TX && F8TX.arme)) return;
  try { const r = await fetch('api/ft8/tx?vie=1&client=' + TX_CLIENT); F8TX = await r.json(); } catch (x) { return; }
  f8txRend();
}
setInterval(f8txTic, 1000);
// A start: the controller's rules first (another client holding, a remote page's confirmation).
async function f8txDemarre(q) {
  if (typeof txPeutDemarrer === 'function' && !(await txPeutDemarrer())) return;
  await f8txPost(q);
}
function f8txCq() { f8txDemarre('cq=1'); }
function f8txActive() { if (F8TX && F8TX.arme) f8txPost('actif=0'); else f8txDemarre('actif=1'); }
async function f8txArret() { await f8txPost('arret=1'); annonce('■ Émission FT8/FT4 arrêtée', true); }
function f8txChoisit(n) { f8txPost('tx=' + n); }
function f8txEdite(n, t) { f8txPost('msg' + n + '=' + enc(t.trim())); }
function f8txLibre() { if (typeof f8Libre === 'number' && f8Libre) f8txPost('hz=' + f8Libre); }
// Answer a decoded line: the server makes the partner, the slot and the messages.
function f8txRepond(e) {
  if (!e || !e.de) return;
  f8txDemarre('repond=' + enc(e.texte) + '&de=' + enc(e.de) + '&a=' + enc(e.a || '') + '&loc=' + enc(e.loc || '') + '&db=' + e.db + '&hz=' + e.hz + '&t=' + e.t);
}
async function f8txNote() {
  const j = await f8txPost('noter=1&c=' + enc(v('f8tx-nc')) + '&l=' + enc(v('f8tx-nl')) + '&rs=' + enc(v('f8tx-nrs')) + '&rr=' + enc(v('f8tx-nrr')) + '&n=' + enc(v('f8tx-nn')));
  if (j && j.ok !== false) { annonce('QSO noté au carnet'); f8txNoteVu = ''; }
  else txt('f8tx-n-etat', (j && j.erreur) || 'non noté');
}
const f8txMhz = hz => (hz / 1e6).toLocaleString('fr-FR', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
function f8txRend() {
  const X = F8TX;
  // A server without FT8 transmit (the phone's): no card at all.
  if (!X || X.ok === false && X.arme === undefined) { if (X && X.absent !== undefined || X && X.arme === undefined) g('f8tx').hidden = true; return; }
  g('f8tx').hidden = false;
  const arme = !!X.arme, emet = !!X.emet;
  // The band on every view, and the header's pill.
  g('f8tx-bandeau').hidden = !arme;
  txt('f8tx-b-t', X.mode + (emet ? (X.simule ? ' · émission simulée' : ' · ÉMISSION') : ' · émission armée'));
  txt('f8tx-b-d', (X.enCours ? X.enCours.texte : (X.messages[X.prochain - 1] || {}).texte || '') + (X.chienS != null ? ' · chien ' + Math.floor(X.chienS / 60) + ':' + String(X.chienS % 60).padStart(2, '0') : ''));
  if (g('v-ft8').hidden) return;
  const p = g('f8tx-pastille');
  p.className = 'pastille f8tx-pastille' + (emet ? ' tx' : arme ? ' arme' : '');
  p.textContent = emet ? (X.simule ? 'TX simulée (démo)' : 'TX') : arme ? 'armée' : 'réception seule';
  txt('f8tx-actif', arme ? 'Désactiver' : 'Activer l’émission');
  g('f8tx-stop').disabled = !arme && !emet;
  const st = g('f8tx-statut');
  if (X.erreur && !arme) { st.style.color = 'var(--rose)'; txt('f8tx-statut', X.erreur); }
  else if (!arme && X.refus) { st.style.color = 'var(--ambre)'; txt('f8tx-statut', 'Émission impossible pour l’instant : ' + X.refus); }
  else {
    st.style.color = '';
    const n = X.prochaineMs ? Math.max(0, Math.ceil((X.prochaineMs - Date.now()) / 1000)) : null;
    txt('f8tx-statut', emet ? 'Envoi : ' + X.enCours.texte + ' sur ' + X.enCours.hz + ' Hz' + (X.simule ? ' (démonstration : rien n’est émis)' : '')
      : arme ? (n != null ? 'Prochaine émission dans ' + n + ' s (créneau ' + (X.pair ? 'pair' : 'impair') + ')' : 'armée') + (X.simule ? ' · démonstration : émission simulée' : '')
      : (X.raison ? 'Arrêtée : ' + X.raison : 'Réception seule : rien n’est émis.'));
  }
  g('f8tx-barre').hidden = !emet;
  if (X.enCours) g('f8tx-progres').style.width = Math.round(X.enCours.progres * 100) + '%';
  // The contact.
  const q = g('f8tx-qso');
  const lui = X.partenaire ? '<b>' + esc(X.partenaire) + '</b>' + (X.sonCarre ? ' ' + esc(X.sonCarre) : '') : '';
  q.innerHTML = (X.etape === 'REPOS' && !lui) ? 'Double-clic sur une ligne décodée (ou ↩) pour répondre à cette station ; « Appeler CQ » pour lancer un appel.'
    : esc(X.etapeTexte) + (lui ? ' · ' + lui : '') + (X.rapportEnvoye ? ' · envoyé ' + esc(X.rapportEnvoye) : '') + (X.rapportRecu ? ' · reçu ' + esc(X.rapportRecu) : '') +
      (X.dernier ? '<br><span class="petit">' + esc(X.dernier) + '</span>' : '') + (X.auto ? '' : ' <span class="petit">(séquence manuelle)</span>');
  // The six messages: rebuilt when they change, an input being typed in kept.
  const cle = JSON.stringify([X.messages.map(m => m.texte + m.type), X.prochain, X.enCours && X.enCours.tx]);
  if (cle !== f8txVu) {
    f8txVu = cle;
    const actif = document.activeElement && document.activeElement.id && document.activeElement.id.indexOf('f8tx-m') === 0 ? document.activeElement.id : '';
    g('f8tx-msgs').innerHTML = X.messages.map(m => '<div class="f8tx-ligne' + (m.n === X.prochain ? ' suivant' : '') + (X.enCours && X.enCours.tx === m.n ? ' envoi' : '') + '">' +
      '<label title="Envoyer celui-ci au prochain créneau"><input type="radio" name="f8tx-n" ' + (m.n === X.prochain ? 'checked ' : '') + 'onchange="f8txChoisit(' + m.n + ')"> Tx' + m.n + '</label>' +
      '<input type="text" id="f8tx-m' + m.n + '" value="' + escA(m.texte) + '" placeholder="' + escA(m.genere || (m.n === 6 ? 'CQ …' : 'choisir une station')) + '" onchange="f8txEdite(' + m.n + ', this.value)" spellcheck="false" autocomplete="off">' +
      '<span class="f8tx-type' + (m.type === 'impossible' ? ' non' : '') + '">' + esc(m.type === 'impossible' ? 'non codable' : m.type) + '</span></div>').join('');
    if (actif && g(actif)) g(actif).focus();
  }
  const val = (id, x) => { const e = g(id); if (e && document.activeElement !== e) e.value = String(x); };
  val('f8tx-hz', X.hz); val('f8tx-pair', X.pairCq ? '1' : '0'); val('f8tx-chien', X.chienMin); val('f8tx-niv', X.niveau);
  g('f8tx-tient').checked = !!X.tient; g('f8tx-auto').checked = !!X.auto; g('f8tx-rrr').checked = !!X.rrr;
  // The rig's data mode: switched by SatMe on an IC-9700 when ticked; otherwise the rig's menus decide.
  g('f8tx-donnees').checked = !!X.donnees;
  txt('f8tx-mode', X.modePoste ? 'poste : ' + X.modePoste + (X.donneesActif ? ' ✓' : '') : '');
  txt('f8tx-donnees-aide', !X.donneesPossible
      ? 'Ce poste : mode données non commuté par SatMe (IC-9700 seulement) ; régler DATA OFF MOD = USB (ou MIC,USB) pour que la BLU prenne le son USB.'
    : X.donnees
      ? 'USB-D pendant l’émission armée, mode d’avant remis à l’arrêt ; sur le poste : DATA MOD = USB (LAN par le réseau).'
      : 'Mode données coupé : SatMe ne touche pas au mode ; régler DATA OFF MOD = USB (ou MIC,USB) sur l’IC-9700.');
  txt('f8tx-niv-v', X.niveau + ' dB');
  const c = g('f8tx-chien-r');
  c.textContent = X.chienS != null ? 'reste ' + Math.floor(X.chienS / 60) + ':' + String(X.chienS % 60).padStart(2, '0') : '';
  c.classList.toggle('bas', X.chienS != null && X.chienS < 30);
  const h = X.horloge;
  txt('f8tx-horloge', h == null ? 'Horloge : pas encore lue (il faut quelques décodages)' : 'Horloge : ' + (h > 0 ? '+' : '') + String(h).replace('.', ',') + ' s ' +
    (Math.abs(h) <= X.horlogeMax ? '✓' : '✗ au-delà de ±' + String(X.horlogeMax).replace('.', ',') + ' s : émission refusée'));
  const o = X.ou || {};
  txt('f8tx-ou', o.satellite ? o.satellite + (o.monteeHz ? ' · montée ' + f8txMhz(o.monteeHz) + ' MHz' + (o.inverse ? ' (inversé, LSB)' : '') : '') +
    (o.descenteHz ? ' · revient à ' + f8txMhz(o.descenteHz) + ' MHz' : '') : 'Terrestre : sur la fréquence du poste, à ' + X.hz + ' Hz au-dessus de son cadran (USB)');
  // The contact to note, once.
  const n = X.aNoter, nk = n ? n.indicatif + n.t : '';
  g('f8tx-noter').hidden = !n;
  if (n && nk !== f8txNoteVu) {
    f8txNoteVu = nk;
    g('f8tx-nc').value = n.indicatif; g('f8tx-nl').value = n.carre; g('f8tx-nrs').value = n.rs; g('f8tx-nrr').value = n.rr; g('f8tx-nn').value = X.mode;
    txt('f8tx-n-titre', 'avec ' + n.indicatif + ' en ' + X.mode + (n.satellite ? ' par ' + n.satellite + ' (mode de propagation SAT)' : ' (terrestre)') + ' : vérifier puis noter');
    txt('f8tx-n-etat', '');
  }
  if (typeof dessineCascade === 'function' && f8Lignes.length) dessineCascade();
}
// The TX frequency on the waterfall: the band it will take, red while sending.
function f8txMarque(x, X, h) {
  const t = F8TX; if (!t || !t.hz) return;
  const x0 = X(t.hz), x1 = X(t.hz + (t.largeur || 50));
  x.fillStyle = t.emet ? 'rgba(255,90,54,.35)' : 'rgba(255,90,54,.14)'; x.fillRect(x0, 0, Math.max(2, x1 - x0), h);
  x.strokeStyle = '#FF5A36'; x.setLineDash([]); x.beginPath(); x.moveTo(x0, 0); x.lineTo(x0, h); x.stroke();
  x.fillStyle = '#FF5A36'; x.font = 'bold 11px Drapeaux,system-ui'; x.fillText('TX ' + t.hz + ' Hz', x0 + 3, 40);
}
// The decoded lines: a double-click answers.
g('8-liste').addEventListener('dblclick', ev => {
  const tr = ev.target.closest('tr[data-i]'); if (!tr || !f8txLignes) return;
  f8txRepond(f8txLignes[+tr.dataset.i]);
});
document.addEventListener('keydown', e => { if (e.key === 'Escape' && F8TX && (F8TX.arme || F8TX.emet)) f8txPost('arret=1'); });
"""
}
