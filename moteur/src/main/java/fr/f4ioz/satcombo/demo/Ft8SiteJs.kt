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
 * The FT8 tab's card « Passages FT8/FT4 sur le site » (SatMe-PC, « Publication web » ready, the kind
 * `ft8-passage` not « Jamais »): the journal's last passes with FT8 / FT4 messages, each with its 🌐
 * (`sitePublie`, [SiteWebJs]); hidden otherwise. Read from `api/site/ft8` while the tab is shown.
 * No `$` in the texts: they are Kotlin constants.
 */
object Ft8SiteJs {

    const val HTML = """
      <div class="carte f8-site-boite" id="8-site" hidden style="order:5">
        <div class="paire f8-t"><b>🌐 Passages FT8/FT4 sur le site</b><span class="petit" id="8-site-info"></span></div>
        <div id="8-site-liste" class="f8-site-liste"></div>
        <div class="petit aide">Les passages du journal où des messages FT8/FT4 ont été reçus : 🌐 envoie le passage (ses créneaux, les stations entendues, leurs carrés) vers votre site, qui peut le rejouer en spectrogramme. Réglages › Services en ligne › Envoyer vers un site web › « Passages FT8/FT4 ».</div>
      </div>
"""

    const val JS = """
// ------------------------------------------------------------ FT8/FT4 passes on the site (Publication web)
let f8SiteT = 0;
async function f8Site(force) {
  const boite = g('8-site'); if (!boite || g('v-ft8').hidden) return;
  if (!force && Date.now() - f8SiteT < 30000) return;
  f8SiteT = Date.now();
  let j = null;
  try { j = await (await fetch('api/site/ft8')).json(); } catch (x) {}
  if (!j || !j.ok || !j.pret || j.mode === 'jamais') { boite.hidden = true; return; }
  const l = j.passages || [];
  boite.hidden = !l.length;
  g('8-site-info').textContent = l.length + ' passage' + (l.length > 1 ? 's' : '');
  const jour = ms => { const d = new Date(ms); return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }); };
  g('8-site-liste').innerHTML = l.map(p => '<div style="display:flex;flex-wrap:wrap;align-items:center;gap:4px 8px;padding:3px 0;border-bottom:1px solid var(--bord, rgba(128,128,128,.2))">' +
    '<button class="site-b" data-site="ft8-passage" title="Envoyer ce passage vers le site web" onclick="sitePublie(\'ft8-passage\', ' + JSON.stringify(String(p.id)).replace(/"/g, '&quot;') + ', this)">🌐</button>' +
    '<span>' + jour(p.aos) + '</span><b>' + esc(p.satellite) + '</b><span class="petit">' + p.elMax + '° · ' + p.stations + ' stations · ' + p.decodes + ' messages</span>' +
    (p.publie ? '<span class="vert petit" title="' + escA(p.url || '') + '">✓ en ligne</span>' : '') + '</div>').join('');
}
setInterval(() => { f8Site(false); }, 5000);
"""
}
