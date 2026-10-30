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
 * The DATV tab's « Balises QO-100 » card (the beacons watched, their
 * verdicts, their history, the alert when the fault is at the source) and
 * « Balayage auto » card (always a picture: another signal when the one
 * followed does not lock), with their code. Read from `api/datv/balises` and
 * `api/datv/secours`; settings posted there.
 */
object BalisesQo100Js {
    const val HTML = """
    <div class="carte" id="bq-carte">
      <div class="t" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap"><span>Balises QO-100</span>
        <label class="petit" style="text-transform:none;letter-spacing:0" title="La balise DATV (10 491,500 MHz) sur le spectre de la station sol et, quand le récepteur est dessus, telle qu’elle arrive ici (accroche, MER, flux PID par PID, image) ; les balises bande étroite telles que le SDR les entend. Une alerte quand une balise s’éteint ou que son flux est abîmé à la source alors que votre réception est bonne. Rien n’est émis."><input type="checkbox" id="bq-on" onchange="bqRegle('on=' + (this.checked ? 1 : 0))"> surveiller</label></div>
      <div id="bq-zone" hidden>
        <div class="avert" id="bq-alerte" hidden></div>
        <div class="ligne" style="margin-top:0;align-items:flex-start"><span class="petit" style="min-width:86px">DATV</span>
          <div style="flex:1"><span id="bq-l-statut" class="pastille">—</span> <span id="bq-l-texte"></span><div class="petit" id="bq-l-detail"></div></div></div>
        <div class="ligne" style="align-items:flex-start"><span class="petit" style="min-width:86px">Bande étroite</span>
          <div style="flex:1"><span id="bq-e-statut" class="pastille">—</span> <span id="bq-e-texte"></span><div class="petit" id="bq-e-detail"></div></div></div>
        <canvas id="bq-histo" style="width:100%;height:46px;display:block;margin-top:6px" title="Une case toutes les 5 min : en haut la balise DATV, en bas la bande étroite (vert ok, ambre signal faible ou pertes ici, rouge absente ou flux abîmé à la source, gris pas de mesure)"></canvas>
        <div class="petit" id="bq-histo-leg"></div>
        <div class="ligne" style="flex-wrap:wrap;gap:4px 10px">
          <label class="petit" title="Seulement le MiniTiouner (ici ou sur un SatMe-Node), seulement quand personne ne s’en sert : accordé sur la balise 60 s, puis arrêté et ses réglages remis. Un réglage à la main l’emporte toujours."><input type="checkbox" id="bq-controle" onchange="bqRegle('controle=' + (this.checked ? 1 : 0))"> contrôler l’image de la balise avec le MiniTiouner libre, toutes les</label>
          <select id="bq-periode" onchange="bqRegle('periode=' + this.value)"><option value="15">15 min</option><option value="30">30 min</option><option value="60">1 h</option><option value="120">2 h</option></select>
          <span class="petit">historique</span><select id="bq-jours" onchange="bqRegle('jours=' + this.value)"><option value="1">24 h</option><option value="2">2 jours</option><option value="3">3 jours</option><option value="7">7 jours</option></select>
          <span class="petit" id="bq-sonde"></span></div>
        <div class="petit aide" id="bq-aide"></div>
        <details><summary class="petit" style="cursor:pointer">Changements</summary><div class="petit" id="bq-evts"></div>
          <button class="discret" onclick="if (confirm('Oublier l’historique des balises ?')) bqRegle('oublie=1')">Oublier l’historique</button></details>
      </div>
    </div>
    <div class="carte" id="ba-carte">
      <div class="t" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap"><span>Balayage auto</span>
        <label class="petit" style="text-transform:none;letter-spacing:0" title="Quand le signal suivi ne s’accroche pas, est perdu ou que son flux est abîmé, le récepteur passe à un autre : vos favoris ★, puis la balise, puis les stations présentes sur le spectre de la station sol (les plus fortes et les plus récemment reçues d’abord) ; et il revient au signal suivi quand il réapparaît. Un réglage à la main met le balayage en pause."><input type="checkbox" id="ba-on" onchange="baRegle('on=' + (this.checked ? 1 : 0))"> toujours une image</label>
        <span class="petit" id="ba-phase" style="text-transform:none;letter-spacing:0"></span></div>
      <div id="ba-zone" hidden>
        <div id="ba-etat"></div>
        <div class="petit" id="ba-suivi"></div>
        <div class="ligne" style="flex-wrap:wrap;gap:4px 10px">
          <select id="ba-mode" onchange="baRegle('mode=' + this.value)"><option value="coupure">rester sur un signal jusqu’à la coupure</option><option value="duree">x secondes sur chacun (en repli)</option><option value="tourne">tourner : x secondes sur chacun, même si tout va bien</option></select>
          <span class="petit" id="ba-duree-l"><input id="ba-duree" style="width:48px" onchange="baRegle('duree=' + enc(this.value))"> s sur chacun</span>
          <span class="petit" title="Sans image correcte (pas d’accroche, signal perdu, flux abîmé) pendant ce temps : signal suivant"><input id="ba-attente" style="width:40px" onchange="baRegle('attente=' + enc(this.value))"> s sans image</span>
          <span class="petit" title="Après un réglage à la main, le balayage attend ce temps"><input id="ba-pause" style="width:40px" onchange="baRegle('pause=' + enc(this.value))"> min de pause après un réglage à la main</span></div>
        <div class="ligne" style="flex-wrap:wrap;gap:4px 10px">
          <label class="petit"><input type="checkbox" id="ba-retour" onchange="baRegle('retour=' + (this.checked ? 1 : 0))"> revenir au signal suivi quand il revient</label>
          <label class="petit"><input type="checkbox" id="ba-balise" onchange="baRegle('balise=' + (this.checked ? 1 : 0))"> avec la balise</label>
          <button onclick="baRegle('favori=1')" title="Le signal reçu maintenant dans vos favoris : essayé en premier">★ le signal reçu</button>
          <button id="ba-reprends" onclick="baRegle('reprends=1')" hidden>Reprendre maintenant</button></div>
        <div class="petit" id="ba-aide"></div>
        <div id="ba-candidats" class="petit" style="margin-top:4px"></div>
        <details><summary class="petit" style="cursor:pointer">Derniers changements</summary><div class="petit" id="ba-journal"></div></details>
      </div>
    </div>
"""

    const val JS = """
// ------------------------------------------------------------------ DATV: QO-100's beacons watched, the automatic scan
let BQ = null, BA = null, bqN = -1;
const BQ_COUL = { ok: 'var(--vert)', faible: 'var(--ambre)', 'pertes-ici': 'var(--ambre)', 'flux-abime': 'var(--rose)', absente: 'var(--rose)', inconnu: 'var(--gris)' };
const BQ_CAN = { ok: '#5BD68A', faible: '#FFB454', 'pertes-ici': '#FFB454', 'flux-abime': '#E5484D', absente: '#E5484D', inconnu: '#4A5A68' };
function bqPastille(id, a) {
  const e = g(id); const c = BQ_COUL[a.statut] || 'var(--gris)';
  e.textContent = a.libelle || a.statut; e.style.color = c; e.style.background = 'color-mix(in srgb, ' + c + ' 18%, transparent)';
}
const bq1 = x => x == null ? '—' : String(Math.round(x * 10) / 10).replace('.', ',');
async function ticBalises(force) {
  const vu = !g('v-datv').hidden;
  if (window.SATME_TELEPHONE || (!vu && !force)) return;
  let j; try { j = await (await fetch('api/datv/balises?depuis=' + bqN + (vu ? '' : '&court=1'))).json(); } catch (x) { return; }
  if (!j || !j.ok) { g('bq-carte').hidden = true; return; }
  // The alerts since the last look: a toast whatever the tab (the first reading only sets where we are).
  if (bqN >= 0) (j.alertes || []).forEach(a => toast(a.texte));
  bqN = j.n; BQ = j;
  if (!vu) return;
  g('bq-carte').hidden = false;
  g('bq-on').checked = j.on; g('bq-zone').hidden = !j.on;
  if (!j.on) return;
  g('bq-controle').checked = j.controle; g('bq-periode').value = String(j.periode); g('bq-jours').value = String(j.jours);
  const l = j.large, e = j.etroites;
  bqPastille('bq-l-statut', l); txt('bq-l-texte', l.texte + (l.depuis ? ' (depuis ' + hm(l.depuis) + ')' : ''));
  const r = l.reception;
  txt('bq-l-detail', [l.spectre == null ? 'spectre de la station sol : ' + (j.spectreOn ? 'pas de trame récente' : 'non coché') : 'station sol : ' + (l.spectre ? 'présente' + (l.spectreDb != null ? ', ' + bq1(l.spectreDb) + ' dB au-dessus du bruit' : '') : 'absente'),
    r ? 'reçue ici depuis ' + r.depuis + ' s : ' + (r.verrouille ? 'MER ' + bq1(r.mer) + ' dB' + (r.marge != null ? ', marge ' + bq1(r.marge) + ' dB' : '') + ' · image ' + (r.images == null ? '?' : r.images >= 0.1 ? 'oui' : 'non') +
      ' · vidéo/son ' + r.ccEs + ' trous sur ' + r.paquetsEs + ' paquets (' + bq1(r.tauxEs * 100) + ' %) · tables ' + r.ccAutres + ' · TEI ' + r.tei + ' · FEC ' + (r.fec == null ? '?' : r.fec ? 'en échec' : 'propre') : 'pas accrochée') : ''].filter(x => x).join(' · '));
  bqPastille('bq-e-statut', e); txt('bq-e-texte', e.texte);
  txt('bq-e-detail', e.sdr ? e.liste.map(b => b.nom + ' : ' + (b.db == null ? 'pas entendue' : bq1(b.db) + ' dB' + (b.ecart != null ? ', ' + (b.ecart >= 0 ? '+' : '') + b.ecart + ' Hz' : ''))).join(' · ') : '');
  const al = [l, e].find(a => a.cause === 'source' && (a.statut === 'absente' || a.statut === 'flux-abime'));
  g('bq-alerte').hidden = !al; if (al) txt('bq-alerte', '⚠ ' + al.texte);
  txt('bq-sonde', j.sonde.enCours ? 'contrôle en cours (' + j.sonde.reste + ' s)' : j.sonde.erreur ? 'contrôle refusé : ' + j.sonde.erreur : j.controle && j.sonde.prochaine ? 'prochain contrôle vers ' + hm(j.sonde.prochaine) : '');
  txt('bq-aide', (!j.spectreOn ? 'Cocher « Spectre de la bande large » pour voir la balise sur le spectre de la station sol (il reste connecté tant que la surveillance est cochée). ' : '') +
    (!j.minitiouner ? 'Le contrôle de l’image ne se fait qu’avec le MiniTiouner (ici ou sur un SatMe-Node). ' : '') +
    'La bande étroite se mesure quand le SDR est sur le transpondeur QO-100.');
  g('bq-evts').innerHTML = j.evenements.slice(0, 20).map(v => jourHeure(v.t) + ' — ' + (v.quoi === 'large' ? 'DATV' : 'bande étroite') + ' : ' + esc(v.texte)).join('<br>') || 'aucun';
  bqHisto(j);
}
function bqHisto(j) {
  const c = g('bq-histo'); const w = Math.max(200, Math.round(c.clientWidth)), h = 46;
  if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
  const x = c.getContext('2d'); x.clearRect(0, 0, w, h);
  const fin = Date.now(), debut = fin - j.jours * 86400000, pas = 300000;
  const X = t => (t - debut) / (fin - debut) * w, lw = Math.max(1, pas / (fin - debut) * w);
  x.fillStyle = 'rgba(74,90,104,.25)'; x.fillRect(0, 4, w, 16); x.fillRect(0, 24, w, 16);
  (j.points || []).forEach(p => {
    if (p[0] < debut) return;
    x.fillStyle = BQ_CAN[p[1]] || '#4A5A68'; x.fillRect(X(p[0]), 4, lw, 16);
    x.fillStyle = BQ_CAN[p[2]] || '#4A5A68'; x.fillRect(X(p[0]), 24, lw, 16);
  });
  // A tick a day.
  x.fillStyle = '#9AA7B4';
  for (let d = new Date(debut); d.getTime() < fin; d.setHours(24, 0, 0, 0)) { const px = X(d.getTime()); if (px > 0) x.fillRect(px, 0, 1, h); }
  const pts = (j.points || []).filter(p => p[0] >= debut);
  const mal = pts.filter(p => p[1] === 'absente' || p[1] === 'flux-abime').length;
  txt('bq-histo-leg', (j.jours > 1 ? j.jours + ' derniers jours' : '24 dernières heures') + ' · en haut DATV, en bas bande étroite' +
    (pts.length ? ' · balise DATV en défaut à la source ' + Math.round(mal * 5) + ' min sur ' + Math.round(pts.length * 5 / 60 * 10) / 10 + ' h mesurées' : ' · pas encore de mesure'));
}
async function bqRegle(q) {
  try { await fetch('api/datv/balises?' + q, { method: 'POST' }); } catch (x) {}
  setTimeout(() => ticBalises(true), 150);
}
async function ticSecours() {
  if (g('v-datv').hidden || window.SATME_TELEPHONE) return;
  let j; try { j = await (await fetch('api/datv/secours')).json(); } catch (x) { return; }
  if (!j || !j.ok) { g('ba-carte').hidden = true; return; }
  BA = j; g('ba-carte').hidden = false;
  g('ba-on').checked = j.on; g('ba-zone').hidden = !j.on;
  txt('ba-phase', !j.on ? '' : { pause: 'en pause', suivi: 'sur le signal suivi', repli: 'en repli', attente: 'en attente', arret: '' }[j.phase] || '');
  if (!j.on) return;
  ['duree', 'attente', 'pause'].forEach(k => { if (document.activeElement !== g('ba-' + k)) g('ba-' + k).value = j[k]; });
  g('ba-mode').value = j.mode; g('ba-duree-l').hidden = j.mode !== 'duree' && j.mode !== 'tourne';
  g('ba-retour').checked = j.retour; g('ba-balise').checked = j.balise;
  g('ba-reprends').hidden = j.phase !== 'pause';
  txt('ba-etat', j.etat || '');
  txt('ba-suivi', (j.prefere ? 'Signal suivi : ' + j.prefere.libelle : '') + (j.courante ? ' · en ce moment : ' + j.courante.libelle : ''));
  txt('ba-aide', j.spectre ? '' : 'Sans spectre de la station sol récent, seuls vos favoris ★ et la balise sont essayés.');
  g('ba-candidats').innerHTML = (j.candidats || []).map(c => '<div' + (c.ici ? ' style="color:var(--cyan)"' : c.present === false ? ' style="opacity:.5"' : '') + '>' +
    '<span style="cursor:pointer" title="' + (c.favori ? 'Retirer des favoris' : 'Mettre dans les favoris') + '" onclick="baRegle(\'favori=' + (c.favori ? 0 : 1) + '&khz=' + c.khz + '&sr=' + c.sr + '&nom=' + enc(c.nom || '') + '\')">' + (c.favori ? '★' : '☆') + '</span> ' +
    esc(c.libelle) + ' <span class="petit">' + ({ favori: 'favori', balise: 'balise', spectre: 'spectre' }[c.origine] || '') + (c.suivi ? ' · suivi' : '') +
    (c.present === false ? ' · absent' : '') + (c.echec ? ' · en échec, laissé 3 min' : '') + (c.ici ? ' · reçu' : '') + '</span></div>').join('') ||
    (j.favoris || []).map(f => '★ ' + esc(f.libelle)).join('<br>');
  g('ba-journal').innerHTML = (j.journal || []).map(p => hm(p.t, true) + ' — ' + esc(p.raison)).join('<br>') || 'aucun';
}
async function baRegle(q) {
  let j = null;
  try { j = await (await fetch('api/datv/secours?' + q, { method: 'POST' })).json(); } catch (x) {}
  if (j && j.ok === false && j.erreur) toast(j.erreur);
  setTimeout(ticSecours, 150);
}
setInterval(() => { if (!document.hidden) { ticBalises(false); ticSecours(); } }, 2000);
// The beacons' alerts in any tab: a lighter look every 15 s while the DATV tab is not shown.
setInterval(() => { if (!document.hidden && g('v-datv').hidden && (!BQ || BQ.on)) ticBalises(true); }, 15000);
"""
}
