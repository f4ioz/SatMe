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
 * « L'ISS visible à l'œil nu » in the Suivi tab (`api/iss/visible?jours=3`,
 * [fr.f4ioz.satcombo.domain.PassagesVisibles]): a chip in the title of
 * « Prochains passages » with the next one, a click opens the list over the
 * card (no height taken from the tab). Read every five minutes. No Kotlin
 * template in the script.
 */
object IssOeilNuJs {

    const val CSS = """
  .iv-boite { position:relative; float:right; font-weight:400; }
  .iv-b { padding:0 6px; font-size:var(--fs-s); border-radius:999px; white-space:nowrap; letter-spacing:0; text-transform:none; }
  .iv-pop { position:fixed; right:auto; width:560px; max-width:calc(100vw - 24px); max-height:340px; overflow:auto; font-size:var(--fs-s); z-index:60; text-transform:none; letter-spacing:0; font-weight:400; }
  .iv-pop table { width:100%; }
  .iv-pop td, .iv-pop th { padding:2px 4px; text-align:center; white-space:nowrap; }
  .iv-pop td:first-child, .iv-pop th:first-child { text-align:left; }
"""

    const val CHIP = """<span class="iv-boite"><button type="button" class="iv-b discret" id="iv-b" onclick="ivBascule(event)" title="Les passages de l'ISS visibles à l'œil nu d'ici : le ciel assez sombre (Soleil sous −6°), l'ISS encore au soleil, à 10° au moins">👁 ISS</button><div class="pop-carte iv-pop" id="iv-pop" hidden></div></span>"""

    const val JS = """
// ------------------------------------------------------------------ The ISS with the naked eye (IssOeilNuJs)
let IV = null, ivVu = 0;
const ivHm = ms => { const d = new Date(ms); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
const ivJour = ms => { const d = new Date(ms), a = new Date(); const j = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(a.getFullYear(), a.getMonth(), a.getDate())) / 86400000);
  return j === 0 ? 'auj.' : j === 1 ? 'demain' : d.toLocaleDateString('fr-FR', { weekday: 'short', day: '2-digit' }); };
const ivMag = m => (m > 0 ? '+' : '') + m.toFixed(1).replace('.', ',').replace('-', '−');
async function ivLit() {
  try { IV = await (await fetch('api/iss/visible?jours=3')).json(); } catch (x) { return; }
  ivVu = Date.now(); ivDessine();
}
function ivDessine() {
  const o = IV; if (!o || !g('iv-b')) return;
  const l = o.passages || [], p = l[0];
  txt('iv-b', p ? '👁 ' + ivHm(p.debut.t) : '👁 ISS');
  g('iv-b').title = p ? 'ISS visible à l’œil nu ' + ivJour(p.debut.t) + ' à ' + ivHm(p.debut.t) + ', magnitude ' + ivMag(p.magnitude) + ' (' + p.eclat + ') — cliquer pour les trois jours' : 'Les passages de l’ISS visibles à l’œil nu';
  let h = '<div class="t" style="margin:0 0 4px">L’ISS visible à l’œil nu <span class="petit">(3 jours)</span></div>';
  if (o.inconnu) h += '<div>Position de la station inconnue : renseigne ton locator (Réglages).</div>';
  else if (o.sansElements) h += '<div>Pas d’éléments orbitaux pour l’ISS (25544) : actualiser les satellites.</div>';
  else if (!l.length) h += '<div>Aucun passage visible dans les trois jours : l’ISS passe de jour, ou dans l’ombre de la Terre.</div>';
  else h += '<table><thead><tr><th>Jour</th><th>Apparaît</th><th>Au plus haut</th><th>Disparaît</th><th>Éclat</th></tr></thead><tbody>' + l.map(v =>
    '<tr><td>' + ivJour(v.debut.t) + '</td><td>' + ivHm(v.debut.t) + ' · ' + v.debut.dir + ' ' + v.debut.el + '°</td><td>' + ivHm(v.max.t) + ' · ' + v.max.dir + ' <b>' + v.max.el + '°</b></td><td>' +
    ivHm(v.fin.t) + ' · ' + v.fin.dir + ' ' + v.fin.el + '°' + (v.ombre ? ' 🌑' : '') + '</td><td title="Magnitude estimée (plus petit : plus brillant)">' + ivMag(v.magnitude) + ' · ' + esc(v.eclat) + '</td></tr>').join('') + '</tbody></table>' +
    '<div class="petit" style="margin-top:4px">🌑 : elle s’éteint en entrant dans l’ombre de la Terre, avant l’horizon. Magnitude estimée (−1,8 à 1000 km, mi-éclairée) : ses panneaux peuvent briller davantage.</div>';
  if (g('iv-pop').dataset.vu !== h) { g('iv-pop').dataset.vu = h; g('iv-pop').innerHTML = h; }
}
function ivBascule(ev) {
  if (ev) ev.stopPropagation();
  const p = g('iv-pop'); p.hidden = !p.hidden; if (p.hidden) return;
  // Over the page, under the chip, kept inside the window.
  const r = g('iv-b').getBoundingClientRect();
  p.style.left = Math.max(8, Math.min(r.left, innerWidth - Math.min(560, innerWidth - 24) - 12)) + 'px'; p.style.top = (r.bottom + 6) + 'px';
  ivLit();
}
document.addEventListener('click', ev => { const p = g('iv-pop'); if (p && !p.hidden && !p.contains(ev.target)) p.hidden = true; });
setInterval(() => { if (!g('v-station').hidden && Date.now() - ivVu > 300000) ivLit(); }, 2000);
"""
}
