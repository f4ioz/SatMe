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
 * « Mon site web » on SatMe-PC's page (the server's side is SitePc, and
 * DirectSitePc for « Direct sur le site »), an optional module off by
 * default (Réglages › Modules): while it is off the card and every « Publier
 * sur le site » button stay hidden. Switched on, the card is an assistant:
 * 1. the site's address, 2. the publication token (sent once, in a POST's
 * body, never shown again), 3. « Tester la connexion » (only a test that
 * passes makes it ready), 4. what to send, each kind Sur clic (the default) /
 * Automatique / Jamais; then the queue and its last answers, and the live
 * parts. The gallery, the log, the planche page and the journal put the
 * buttons beside their items (`sitePublie(type, ref)`) only once it is ready.
 * No `$` in the texts: they are Kotlin constants.
 */
object SiteWebJs {

    /** Where the site module is explained (installing it, `f4ioz-site jeton`). */
    const val NOTICE = "https://github.com/f4ioz/SatMe/blob/main/site/README.md"

    /** The card, in « Services en ligne » (hidden while the module is off). */
    const val CARTE = """
    <div class="carte" id="site-carte" hidden>
      <div class="t">Mon site web</div>
      <div class="petit aide">Publier sur <b>votre</b> site radioamateur : le module « site » de SatMe, installé sur un serveur à vous. Rien n'est envoyé tant que la connexion n'a pas été testée, et rien ne part tout seul tant que vous ne choisissez pas « Automatique ». Pour ne plus rien envoyer : décochez « Mon site web » dans Réglages › Modules.</div>
      <div class="petit" id="site-pret" style="margin-top:6px"></div>
      <div class="petit" style="margin-top:8px"><b>1. Adresse de votre site</b></div>
      <div class="ligne"><input id="site-url" placeholder="https://mon-site-radioamateur.fr" spellcheck="false" autocomplete="off"></div>
      <div class="petit" style="margin-top:6px"><b>2. Jeton de publication</b> — une clé secrète qui autorise ce PC à publier sur votre site. Il se crée sur le serveur du site, avec la commande <code>f4ioz-site jeton</code> (elle donne aussi la ligne à mettre dans <code>site.conf</code>) : voir <a href="""" + NOTICE + """" target="_blank" rel="noopener">la notice du module site</a>. Collez-le ici : il reste sur ce PC et n'est jamais réaffiché.</div>
      <div class="ligne"><input id="site-jeton" type="password" placeholder="Jeton de publication" autocomplete="off"></div>
      <div class="petit" style="margin-top:6px"><b>3. Tester la connexion</b> — SatMe-PC demande au site s'il répond et s'il accepte le jeton, sans rien publier. Le site n'est prêt qu'après un essai réussi.</div>
      <div class="ligne"><button class="primaire" onclick="siteEssai()">Tester la connexion</button><button id="site-oublie" onclick="if (confirm('Oublier l’adresse et le jeton de ce site ?')) sitePost('reglages', 'efface=1')" hidden>Oublier ce site</button></div>
      <div class="petit" id="site-etat"></div>
      <div id="site-suite" hidden>
      <div class="petit" style="margin-top:10px"><b>4. Quoi envoyer</b> — « Sur clic » : seulement avec le bouton 🌐 de chaque élément ; « Automatique » : dès qu'il arrive ; « Jamais ». Tout commence à « Sur clic ».</div>
      <table style="margin-top:6px"><thead><tr><th>Quoi</th><th>Publication</th></tr></thead><tbody id="site-modes"></tbody></table>
      <div class="petit" id="site-file" style="margin-top:8px"></div>
      <div class="ligne" id="site-file-b" hidden><button onclick="sitePost('maintenant')" title="Réessayer tout de suite ce qui attend">Réessayer maintenant</button><button onclick="if (confirm('Oublier tout ce qui attend d’être publié ?')) sitePost('vide')">Vider la file</button></div>
      <div class="petit" id="site-histo" style="margin-top:6px"></div>
      <div class="t" style="margin-top:12px">Direct sur le site</div>
      <div class="petit aide">Envoyé en direct au site, chaque partie seulement si elle est cochée (rien par défaut) : le site ne se connecte jamais à la station, c'est SatMe-PC qui envoie, avec le même jeton. Seulement ce qui s'affiche : noms, heures, mesures, spectre, image — jamais une adresse, un code d'appairage, un mot de passe ou une clé. Ce que le site ne prend pas est perdu (pas de file) ; il faut aussi l'activer sur le site (Admin › Paramètres).</div>
      <table style="margin-top:6px"><thead><tr><th>Quoi</th><th>Envoyer</th><th>Cadence</th><th>État</th></tr></thead><tbody id="site-direct"></tbody></table>
      <div class="ligne"><button onclick="sitePost('direct', 'efface=1')" title="Demande au site d'oublier tout de suite ce qu'il montre en direct">Effacer le direct du site</button></div>
      </div>
    </div>"""

    const val JS = """
// ------------------------------------------------------------------ « Mon site web » (SitePc): what is published on the operator's own site
let SITE = null;
const SITE_MODES = [['clic', 'Sur clic'], ['auto', 'Automatique'], ['jamais', 'Jamais']];
async function siteCharge() {
  try { SITE = await (await fetch('api/site')).json(); } catch (x) { SITE = null; }
  // The module off (or no site on this server): no card, no button anywhere.
  const on = !!(SITE && SITE.ok && SITE.module);
  const c = g('site-carte'); if (c) c.hidden = !on;
  if (!on) { document.querySelectorAll('.site-b').forEach(b => { b.hidden = true; }); return; }
  if (document.activeElement !== g('site-url')) g('site-url').value = SITE.url || '';
  g('site-jeton').placeholder = SITE.jeton ? 'jeton enregistré (en coller un autre pour le changer)' : 'Jeton de publication';
  g('site-suite').hidden = !SITE.configure;
  g('site-oublie').hidden = !(SITE.url || SITE.jeton);
  g('site-pret').innerHTML = SITE.configure ? '<span class="vert">✓ Prêt : ' + esc(SITE.url) + '</span>' :
    SITE.url && SITE.jeton ? '<span class="ambre">Pas encore prêt : testez la connexion (étape 3).</span>' : 'À régler : suivez les étapes 1 à 3.';
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
/** Step 3: the address and the token typed sent with the test; the site is ready only if it passes. */
async function siteEssai() {
  if (!v('site-url')) { txt('site-etat', 'Étape 1 : l’adresse de votre site.'); return; }
  if (!v('site-jeton') && !(SITE && SITE.jeton)) { txt('site-etat', 'Étape 2 : le jeton de publication.'); return; }
  txt('site-etat', 'Essai…');
  const b = 'url=' + enc(v('site-url')) + (v('site-jeton') ? '&jeton=' + enc(v('site-jeton')) : '');
  g('site-jeton').value = '';
  const j = await sitePost('essai', b);
  g('site-etat').innerHTML = j ? (j.ok ? '<span class="vert">✓ ' + esc(j.message) + ' — prêt. Choisissez maintenant quoi envoyer (étape 4).</span>' : '<span class="rose">✗ ' + esc(j.message || j.erreur) + '</span>') : 'pas de réponse';
}
async function siteMode(k, m) { await sitePost('reglages', 'mode_' + enc(k) + '=' + enc(m)); }
/** « Publier sur le site » beside an item: [type] the site's kind, [ref] SatMe's name for it. */
async function sitePublie(type, ref, bouton) {
  const j = await sitePost('publie', 'type=' + enc(type) + '&ref=' + enc(String(ref)));
  const dit = j ? (j.ok ? '🌐 ' + j.message : '✗ ' + (j.erreur || 'refusé')) : '✗ pas de réponse';
  if (bouton) { bouton.title = dit; bouton.textContent = j && j.ok ? '🌐 ✓' : '🌐 ✗'; }
  if (typeof toast === 'function') toast(dit); else if (!(j && j.ok)) alert(dit);
}
/** The button for a list's line (none while the module is off or the site not ready, or this kind never published). */
function siteBouton(type, ref) {
  if (!SITE || !SITE.module || !SITE.configure || SITE.modes[type] === 'jamais') return '';
  return '<button class="site-b" data-site="' + type + '" title="Publier sur le site web" onclick="event.stopPropagation(); sitePublie(\'' + type + '\', ' + JSON.stringify(String(ref)).replace(/"/g, '&quot;') + ', this)">🌐</button> ';
}
siteCharge();
setInterval(() => { if (!g('v-reglages').hidden) siteCharge(); }, 5000);
"""
}
