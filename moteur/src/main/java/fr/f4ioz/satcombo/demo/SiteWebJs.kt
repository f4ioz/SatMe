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
 * « Réglages › Site web » on SatMe-PC's page (the server's side is
 * SitePc, and DirectSitePc for « Direct sur le site »): the site's address, the publication token (sent once, in a
 * POST's body, never shown again), each kind Automatique / Sur clic /
 * Jamais, « Tester la connexion », the queue and its last answers; and the
 * « Publier sur le site » buttons the gallery, the log, the planche page
 * and the journal put beside their items (`sitePublie(type, ref)`).
 * No `$` in the texts: they are Kotlin constants.
 */
object SiteWebJs {

    /** The card, in « Services en ligne ». */
    const val CARTE = """
    <div class="carte" id="site-carte">
      <div class="t">Site web (publication)</div>
      <div class="petit aide">Votre site radioamateur (le module « site » de SatMe, celui de f4ioz.fr) reçoit ce que SatMe-PC publie : images SSTV, planches, rapports, contacts, vidéos du journal. Le jeton se crée sur le serveur du site (<code>f4ioz-site jeton</code>) ; il reste sur ce PC et n'est jamais réaffiché.</div>
      <div class="ligne"><input id="site-url" placeholder="https://f4ioz.fr" spellcheck="false" autocomplete="off"></div>
      <div class="ligne"><input id="site-jeton" type="password" placeholder="Jeton de publication" autocomplete="off">
        <button onclick="siteEnregistre()">Enregistrer</button><button onclick="siteEssai()" title="Demande au site s'il répond et s'il accepte le jeton (rien n'est publié)">Tester la connexion</button></div>
      <div class="petit" id="site-etat"></div>
      <table style="margin-top:8px"><thead><tr><th>Quoi</th><th>Publication</th></tr></thead><tbody id="site-modes"></tbody></table>
      <div class="petit" id="site-file" style="margin-top:8px"></div>
      <div class="ligne" id="site-file-b" hidden><button onclick="sitePost('maintenant')" title="Réessayer tout de suite ce qui attend">Réessayer maintenant</button><button onclick="if (confirm('Oublier tout ce qui attend d’être publié ?')) sitePost('vide')">Vider la file</button></div>
      <div class="petit" id="site-histo" style="margin-top:6px"></div>
      <div class="t" style="margin-top:12px">Direct sur le site</div>
      <div class="petit aide">Envoyé en direct au site, chaque partie seulement si elle est cochée (rien par défaut) : le site ne se connecte jamais à la station, c'est SatMe-PC qui envoie, avec le même jeton. Seulement ce qui s'affiche : noms, heures, mesures, spectre, image — jamais une adresse, un code d'appairage, un mot de passe ou une clé. Ce que le site ne prend pas est perdu (pas de file) ; il faut aussi l'activer sur le site (Admin › Paramètres).</div>
      <table style="margin-top:6px"><thead><tr><th>Quoi</th><th>Envoyer</th><th>Cadence</th><th>État</th></tr></thead><tbody id="site-direct"></tbody></table>
      <div class="ligne"><button onclick="sitePost('direct', 'efface=1')" title="Demande au site d'oublier tout de suite ce qu'il montre en direct">Effacer le direct du site</button></div>
    </div>"""

    const val JS = """
// ------------------------------------------------------------------ « Réglages › Site web » (SitePc): what is published on the operator's site
let SITE = null;
const SITE_MODES = [['auto', 'Automatique'], ['clic', 'Sur clic'], ['jamais', 'Jamais']];
async function siteCharge() {
  try { SITE = await (await fetch('api/site')).json(); } catch (x) { SITE = null; }
  if (!SITE || !SITE.ok) { const c = g('site-carte'); if (c) c.hidden = true; return; }
  if (document.activeElement !== g('site-url')) g('site-url').value = SITE.url || '';
  g('site-jeton').placeholder = SITE.jeton ? 'jeton enregistré' : 'Jeton de publication';
  const m = Object.keys(SITE.modes).map(k => '<tr><td>' + esc(SITE.libelles[k]) + '</td><td><select onchange="siteMode(\'' + k + '\', this.value)">' +
    SITE_MODES.map(o => '<option value="' + o[0] + '"' + (SITE.modes[k] === o[0] ? ' selected' : '') + '>' + o[1] + '</option>').join('') + '</select></td></tr>').join('');
  if (g('site-modes').dataset.vu !== m) { g('site-modes').dataset.vu = m; g('site-modes').innerHTML = m; }
  const f = SITE.file || [];
  g('site-file').innerHTML = f.length ? f.length + ' en attente d’envoi' + (f[0].erreur ? ' — dernier essai : <span class="ambre">' + esc(f[0].erreur) + '</span>, prochain à ' + hm(f[0].prochain) : '') : (SITE.configure ? 'Rien en attente · ' + SITE.publies + ' publié(s) depuis ce PC' : '');
  g('site-file-b').hidden = !f.length;
  g('site-histo').innerHTML = (SITE.historique || []).slice(0, 5).map(h => hm(h.t) + ' · ' + esc(h.texte)).join('<br>');
  if (SITE.demo) txt('site-etat', 'Démonstration : publication seulement vers un site de cette machine (http://127.0.0.1:…).');
  const d = SITE.direct && SITE.direct.canaux || [];
  const aides = { pilote: 'Prochain passage d’une campagne, réception en cours, prochaine image, dernière image reçue ; pendant un passage, l’image qui arrive toutes les 5 s', spectre: 'Le transpondeur étroit vu par le SDR (quand il est sur QO-100)', datv: 'Une image réduite et les mesures, tant que le récepteur DATV est verrouillé' };
  const dl = d.map(c => '<tr><td title="' + esc(aides[c.nom] || '') + '">' + esc(c.libelle) + '</td><td><input type="checkbox"' + (c.on ? ' checked' : '') + ' onchange="sitePost(\'direct\', \'on_' + c.nom + '=\' + (this.checked ? 1 : 0))"></td><td>' +
    (c.min === c.max ? c.min + ' s' : '<select onchange="sitePost(\'direct\', \'periode_' + c.nom + '=\' + this.value)">' + [1, 2, 3, 5, 10, 15, 30].filter(x => x >= c.min && x <= c.max).map(x => '<option value="' + x + '"' + (x === c.periode ? ' selected' : '') + '>' + x + ' s</option>').join('') + '</select>') +
    '</td><td>' + (!c.on ? '<span class="petit">—</span>' : c.erreur ? '<span class="ambre">' + esc(c.erreur) + '</span>' : c.ok ? '<span class="vert">envoyé ' + hm(c.ok) + '</span>' : '<span class="petit">en attente de données</span>') + '</td></tr>').join('');
  if (g('site-direct') && g('site-direct').dataset.vu !== dl && !(document.activeElement && document.activeElement.closest && document.activeElement.closest('#site-direct'))) { g('site-direct').dataset.vu = dl; g('site-direct').innerHTML = dl; }
  document.querySelectorAll('.site-b').forEach(b => { const k = b.dataset.site; b.hidden = !SITE.configure || (k && SITE.modes[k] === 'jamais'); });
}
async function sitePost(route, corps) {
  let j = null;
  try { j = await (await fetch('api/site/' + route, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: corps || '' })).json(); } catch (x) {}
  await siteCharge();
  return j;
}
async function siteEnregistre() {
  const b = 'url=' + enc(v('site-url')) + (v('site-jeton') ? '&jeton=' + enc(v('site-jeton')) : '');
  g('site-jeton').value = '';
  await sitePost('reglages', b);
  txt('site-etat', 'Enregistré.');
}
async function siteEssai() {
  txt('site-etat', 'Essai…');
  const j = await sitePost('essai');
  g('site-etat').innerHTML = j ? (j.ok ? '<span class="vert">✓ ' + esc(j.message) + '</span>' : '<span class="rose">✗ ' + esc(j.message || j.erreur) + '</span>') : 'pas de réponse';
}
async function siteMode(k, m) { await sitePost('reglages', 'mode_' + enc(k) + '=' + enc(m)); }
/** « Publier sur le site » beside an item: [type] the site's kind, [ref] SatMe's name for it. */
async function sitePublie(type, ref, bouton) {
  const j = await sitePost('publie', 'type=' + enc(type) + '&ref=' + enc(String(ref)));
  const dit = j ? (j.ok ? '🌐 ' + j.message : '✗ ' + (j.erreur || 'refusé')) : '✗ pas de réponse';
  if (bouton) { bouton.title = dit; bouton.textContent = j && j.ok ? '🌐 ✓' : '🌐 ✗'; }
  if (typeof toast === 'function') toast(dit); else if (!(j && j.ok)) alert(dit);
}
/** The button for a list's line (hidden when the site is not set, or this kind never published). */
function siteBouton(type, ref) {
  if (!SITE || !SITE.configure || SITE.modes[type] === 'jamais') return '';
  return '<button class="site-b" data-site="' + type + '" title="Publier sur le site web" onclick="event.stopPropagation(); sitePublie(\'' + type + '\', ' + JSON.stringify(String(ref)).replace(/"/g, '&quot;') + ', this)">🌐</button> ';
}
siteCharge();
setInterval(() => { if (!g('v-reglages').hidden) siteCharge(); }, 5000);
"""
}
