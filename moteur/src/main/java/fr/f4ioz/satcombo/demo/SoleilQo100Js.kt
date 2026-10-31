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
 * QO-100 tab: the Sun outages (`api/qo100/soleil`,
 * [fr.f4ioz.satcombo.domain.EblouissementQo100]). [LIGNE] one compact line
 * under the reception's state, shown only when an outage is under way or in
 * the next ten days; [TABLEAU] the next days' minutes, local and UTC, in
 * « Réglage initial · pointage et Soleil ». Read once the tab is open, then
 * every 30 s. No Kotlin template in the script.
 */
object SoleilQo100Js {

    const val LIGNE = """        <div class="petit" id="tq-soleil" hidden style="margin-top:2px"></div>
"""

    const val TABLEAU = """        <div class="t" style="margin-top:10px">Éblouissement solaire (sun outage)</div>
        <div id="o-ebl" class="petit"></div>
"""

    const val JS = """
// ------------------------------------------------------------------ QO-100: the Sun outages (SoleilQo100Js)
let oEbl = null, oEblVu = 0;
const eblHm = (ms, u) => { const d = new Date(ms); const f = u ? [d.getUTCHours(), d.getUTCMinutes()] : [d.getHours(), d.getMinutes()]; return f.map(n => String(n).padStart(2, '0')).join(':'); };
const eblJour = ms => new Date(ms).toLocaleDateString('fr-FR', { weekday: 'short', day: '2-digit', month: '2-digit' }).replace('.', '');
async function eblTic() {
  if (g('v-qo100').hidden || Date.now() - oEblVu < 30000 && oEbl && !oEbl.calcul) return;
  oEblVu = Date.now();
  try { oEbl = await (await fetch('api/qo100/soleil')).json(); } catch (x) { return; }
  eblDessine();
}
function eblDessine() {
  const o = oEbl; if (!o) return;
  const p = o.parabole;
  const seuil = 'parabole ' + p.diametreCm + ' cm' + (p.parDefaut ? ' (par défaut : Réglages › Mesures › Bruit solaire pour la tienne)' : '') +
    ' · lobe ' + p.lobeDeg.toFixed(1).replace('.', ',') + '° à 10,5 GHz · Soleil à moins de ' + p.seuilDeg.toFixed(2).replace('.', ',') + '° du satellite';
  let h = '', l = '';
  if (o.inconnu) h = 'Position de la station inconnue : renseigne ton locator (Réglages).';
  else if (o.invisible) h = 'QO-100 est sous l’horizon d’ici : pas d’éblouissement.';
  else if (o.calcul) h = 'Calcul en cours…';
  else {
    const ec = o.enCours, pr = o.prochaine, t = Date.now();
    if (ec) l = '☀ <b style="color:var(--ambre)">Éblouissement solaire en cours</b> jusqu’à ' + eblHm(ec.fin) + ' (' + eblHm(ec.fin, 1) + ' UTC) — normal, pas une panne.';
    else if (pr && pr.debut - t < 10 * 86400000) l = '☀ Prochain éblouissement solaire : ' + eblJour(pr.debut) + ' ' + eblHm(pr.debut) + '–' + eblHm(pr.fin) +
      ' (' + eblHm(pr.debut, 1) + '–' + eblHm(pr.fin, 1) + ' UTC), ' + pr.minutes + ' min.';
    h = '<div>' + (ec ? l : pr ? 'Prochain : <b>' + eblJour(pr.debut) + ' ' + eblHm(pr.debut) + '</b>, ' + pr.minutes + ' min.' : 'Aucun dans l’année qui vient.') + '</div>';
    const rows = (o.periodes || []).slice(0, 12);
    if (rows.length) h += '<div style="max-height:180px;overflow:auto;margin-top:4px"><table class="ebl-t" style="width:100%;font-size:11px;white-space:nowrap;font-variant-numeric:tabular-nums"><thead><tr><th style="text-align:left">Jour</th><th>Locale</th><th>UTC</th><th>min</th><th title="L’heure (locale) où le Soleil est au plus près du satellite, et l’écart atteint">Max à</th></tr></thead><tbody>' +
      rows.map(r => '<tr' + (ec && r.debut === ec.debut ? ' style="color:var(--ambre)"' : '') + '><td>' + eblJour(r.debut) + '</td><td style="text-align:center">' + eblHm(r.debut) + '–' + eblHm(r.fin) +
        '</td><td style="text-align:center">' + eblHm(r.debut, 1) + '–' + eblHm(r.fin, 1) + '</td><td style="text-align:center">' + r.minutes + '</td><td style="text-align:center" title="écart ' + r.ecartDeg.toFixed(2).replace('.', ',') + '°">' + eblHm(r.max) + '</td></tr>').join('') + '</tbody></table></div>';
    h += '<div style="margin-top:4px">La réception s’efface quelques minutes : ce n’est pas une panne. C’est aussi un contrôle gratuit du pointage : ' +
      'le bruit culmine à l’heure « Max à » quand la parabole vise juste ; quelques minutes d’avance ou de retard, elle regarde un peu à l’est ou à l’ouest.</div>';
  }
  h += '<div style="margin-top:2px;opacity:.8">' + esc(seuil) + '</div>';
  if (g('o-ebl').dataset.vu !== h) { g('o-ebl').dataset.vu = h; g('o-ebl').innerHTML = h; }
  const tl = g('tq-soleil'); if (tl) { tl.hidden = !l; if (tl.dataset.vu !== l) { tl.dataset.vu = l; tl.innerHTML = l; } }
}
setInterval(eblTic, 1000);
"""
}
