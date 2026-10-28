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
 * « Publication web » on SatMe-PC's page (the server's side is SitePc, and
 * DirectSitePc for the live parts), an optional module off by default
 * (Réglages › Modules): while it is off the card « Envoyer vers un site web »
 * and every 🌐 button stay hidden. Switched on, the card is an assistant:
 * 1. where to send — a web address (HTTPS, `{type}` or one address; an
 * optional token, sent once in a POST's body, never shown again) or a folder
 * on this PC; 2. « Tester » (only a test that passes makes it ready); 3. what
 * to send, each kind Sur clic (the default) / Automatique / Jamais; then the
 * queue and its last answers, and the live parts. The gallery, the log, the
 * planche page and the journal put the buttons beside their items
 * (`sitePublie(type, ref)`) only once it is ready. The API is documented in
 * docs/publication-web(.fr).md. No `$` in the texts: they are Kotlin constants.
 */
object SiteWebJs {

    /** The documentation: the setup, the API, the receivers' examples. */
    const val DOC = "https://github.com/f4ioz/SatMe/blob/main/docs/publication-web.fr.md"

    /** The card, in « Services en ligne » (hidden while the module is off). */
    const val CARTE = """
    <div class="carte" id="site-carte" hidden>
      <div class="t">Envoyer vers un site web</div>
      <div class="petit aide">SatMe-PC envoie, par HTTPS, ce que vous choisissez — images SSTV, planches, rapports, contacts, vidéos du journal, et en option des données en direct — à une adresse de votre site. Votre site les reçoit comme il veut : voir <a href="""" + DOC + """" target="_blank" rel="noopener">la documentation de l'API</a> (avec des exemples prêts à l'emploi en PHP et en Python). Sans serveur, SatMe-PC peut aussi écrire dans un dossier (site statique, Nextcloud, Syncthing…). C'est toujours SatMe-PC qui envoie : rien n'est ouvert sur la station. Rien ne part tant que le test n'a pas réussi, ni tout seul tant que vous ne choisissez pas « Automatique ».</div>
      <div class="petit" id="site-pret" style="margin-top:6px"></div>
      <div class="petit" style="margin-top:8px"><b>1. Où envoyer</b></div>
      <div class="ligne"><label><input type="radio" name="site-cible" value="web" onchange="siteCible(this.value)"> Adresse web (HTTPS)</label><label><input type="radio" name="site-cible" value="dossier" onchange="siteCible(this.value)"> Dossier sur ce PC</label></div>
      <div id="site-web">
      <div class="petit" style="margin-top:6px">L'adresse qui reçoit. Avec <code>{type}</code>, remplacé par le genre de chaque envoi (<code>…/{type}</code> → <code>…/sstv-image</code>) ; sans, tout part à cette même adresse (le genre est dans l'en-tête <code>X-SatMe-Type</code>).</div>
      <div class="ligne"><input id="site-url" placeholder="https://example.org/satme/recevoir.php" spellcheck="false" autocomplete="off"></div>
      <div class="petit" style="margin-top:6px">Jeton (facultatif mais conseillé) : une clé secrète que votre site vérifie, envoyée en <code>Authorization: Bearer …</code>. Elle reste sur ce PC et n'est jamais réaffichée.</div>
      <div class="ligne"><input id="site-jeton" type="password" placeholder="Jeton (facultatif)" autocomplete="off"><button id="site-sans-jeton" onclick="if (confirm('Ne plus envoyer de jeton ?')) sitePost('reglages', 'sansJeton=1')" hidden>Sans jeton</button></div>
      </div>
      <div id="site-dos" hidden>
      <div class="petit" style="margin-top:6px">Un dossier où SatMe-PC écrit chaque élément (<code>type/année/id.ext</code> et sa fiche <code>.json</code>), un <code>index.json</code> et un flux <code>flux.xml</code> : pour un site statique ou un outil de synchronisation que vous avez déjà. SatMe-PC ne fait pas de FTP lui-même.</div>
      <div class="ligne"><input id="site-dossier" placeholder="/home/moi/Nextcloud/satme" spellcheck="false" autocomplete="off"></div>
      </div>
      <div class="petit" style="margin-top:6px"><b>2. Tester</b> — une requête <code>ping</code> à l'adresse (rien n'est publié), ou un essai d'écriture dans le dossier. Prêt seulement après un essai réussi.</div>
      <div class="ligne"><button class="primaire" onclick="siteEssai()">Tester</button><button id="site-oublie" onclick="if (confirm('Oublier l’adresse, le dossier et le jeton ?')) sitePost('reglages', 'efface=1')" hidden>Oublier ces réglages</button></div>
      <div class="petit" id="site-etat"></div>
      <div id="site-suite" hidden>
      <div class="petit" style="margin-top:10px"><b>3. Quoi envoyer</b> — « Sur clic » : seulement avec le bouton 🌐 de chaque élément ; « Automatique » : dès qu'il arrive ; « Jamais ». Tout commence à « Sur clic ».</div>
      <table style="margin-top:6px"><thead><tr><th>Quoi</th><th>Publication</th></tr></thead><tbody id="site-modes"></tbody></table>
      <div class="petit" id="site-file" style="margin-top:8px"></div>
      <div class="ligne" id="site-file-b" hidden><button onclick="sitePost('maintenant')" title="Réessayer tout de suite ce qui attend">Réessayer maintenant</button><button onclick="if (confirm('Oublier tout ce qui attend d’être publié ?')) sitePost('vide')">Vider la file</button></div>
      <div class="petit" id="site-histo" style="margin-top:6px"></div>
      <div class="t" style="margin-top:12px">En direct (facultatif)</div>
      <div class="petit aide">Envoyé en direct, chaque partie seulement si elle est cochée (rien par défaut) : le site ne se connecte jamais à la station, c'est SatMe-PC qui envoie, avec le même jeton. Seulement ce qui s'affiche : noms, heures, mesures, spectre, image — jamais une adresse, un code d'appairage, un mot de passe ou une clé. Ce que le site ne prend pas est perdu (pas de file). Vers un dossier : <code>direct/direct.json</code>, <code>direct/station.json</code> et les images seulement, pas le spectre ; les satellites favoris dans <code>favoris.json</code>.</div>
      <table style="margin-top:6px"><thead><tr><th>Quoi</th><th>Envoyer</th><th>Cadence</th><th>État</th></tr></thead><tbody id="site-direct"></tbody></table>
      <div class="ligne"><button onclick="sitePost('direct', 'efface=1')" title="Demande au site (type direct-efface) d'oublier tout de suite ce qu'il montre en direct">Effacer le direct</button></div>
      </div>
    </div>"""

    const val JS = """
// ------------------------------------------------------------------ « Publication web » (SitePc): what is sent to the owner's site, or a folder
let SITE = null;
const SITE_MODES = [['clic', 'Sur clic'], ['auto', 'Automatique'], ['jamais', 'Jamais']];
async function siteCharge() {
  try { SITE = await (await fetch('api/site')).json(); } catch (x) { SITE = null; }
  // The module off (or no site on this server): no card, no button anywhere.
  const on = !!(SITE && SITE.ok && SITE.module);
  const c = g('site-carte'); if (c) c.hidden = !on;
  if (!on) { document.querySelectorAll('.site-b').forEach(b => { b.hidden = true; }); return; }
  const dos = (SITE_CIBLE || SITE.cible) === 'dossier';
  document.querySelectorAll('input[name="site-cible"]').forEach(r => { r.checked = r.value === (dos ? 'dossier' : 'web'); });
  g('site-web').hidden = dos; g('site-dos').hidden = !dos;
  if (document.activeElement !== g('site-url')) g('site-url').value = SITE.url || '';
  if (document.activeElement !== g('site-dossier')) g('site-dossier').value = SITE.dossier || '';
  g('site-jeton').placeholder = SITE.jeton ? 'jeton enregistré (en coller un autre pour le changer)' : 'Jeton (facultatif)';
  g('site-sans-jeton').hidden = !SITE.jeton;
  g('site-suite').hidden = !SITE.configure;
  g('site-oublie').hidden = !(SITE.url || SITE.jeton || SITE.dossier);
  const ou = SITE.cible === 'dossier' ? SITE.dossier : SITE.url;
  g('site-pret').innerHTML = SITE.configure ? '<span class="vert">✓ Prêt : ' + esc(ou) + (SITE.cible === 'web' && !SITE.jeton ? ' (sans jeton)' : '') + '</span>' :
    ou ? '<span class="ambre">Pas encore prêt : testez (étape 2).</span>' : 'À régler : suivez les étapes 1 et 2.';
  const m = Object.keys(SITE.modes).map(k => '<tr><td>' + esc(SITE.libelles[k]) + '</td><td><select onchange="siteMode(\'' + k + '\', this.value)">' +
    SITE_MODES.map(o => '<option value="' + o[0] + '"' + (SITE.modes[k] === o[0] ? ' selected' : '') + '>' + o[1] + '</option>').join('') + '</select></td></tr>').join('');
  if (g('site-modes').dataset.vu !== m) { g('site-modes').dataset.vu = m; g('site-modes').innerHTML = m; }
  const f = SITE.file || [];
  g('site-file').innerHTML = f.length ? f.length + ' en attente d’envoi' + (f[0].erreur ? ' — dernier essai : <span class="ambre">' + esc(f[0].erreur) + '</span>, prochain à ' + hm(f[0].prochain) : '') : (SITE.configure ? 'Rien en attente · ' + SITE.publies + ' envoyé(s) depuis ce PC' : '');
  g('site-file-b').hidden = !f.length;
  g('site-histo').innerHTML = (SITE.historique || []).slice(0, 5).map(h => hm(h.t) + ' · ' + esc(h.texte)).join('<br>');
  if (SITE.demo) txt('site-etat', 'Démonstration : publication seulement vers un site de cette machine (http://127.0.0.1:…).');
  const d = SITE.direct && SITE.direct.canaux || [];
  const aides = { pilote: 'Prochain passage d’une campagne, réception en cours, prochaine image, dernière image reçue ; pendant un passage, l’image qui arrive toutes les 5 s', spectre: 'Le transpondeur étroit vu par le SDR (quand il est sur QO-100)', datv: 'Une image réduite et les mesures, tant que le récepteur DATV est verrouillé', favoris: 'La liste des satellites marqués ★ (nom et numéro NORAD), pour que votre site puisse les mettre en avant : dès qu’elle change, sinon une fois par heure', station: 'Le satellite suivi (position, prochain passage, fréquences), la position réelle des antennes (rotor) ou le pointage de la parabole fixe pour QO-100, le SDR, le récepteur DATV et le poste en service (modèle, fréquences, mode), « en émission » oui ou non : dès que ça change (au plus à la cadence choisie), sinon toutes les 30 s. Jamais une adresse, un port ou un chemin.' };
  const dl = d.map(c => '<tr><td title="' + esc(aides[c.nom] || '') + '">' + esc(c.libelle) + '</td><td><input type="checkbox"' + (c.on ? ' checked' : '') + ' onchange="sitePost(\'direct\', \'on_' + c.nom + '=\' + (this.checked ? 1 : 0))"></td><td>' +
    (c.cadence ? esc(c.cadence) : c.min === c.max ? c.min + ' s' : '<select onchange="sitePost(\'direct\', \'periode_' + c.nom + '=\' + this.value)">' + [1, 2, 3, 5, 10, 15, 30].filter(x => x >= c.min && x <= c.max).map(x => '<option value="' + x + '"' + (x === c.periode ? ' selected' : '') + '>' + x + ' s</option>').join('') + '</select>') +
    '</td><td>' + (!c.on ? '<span class="petit">—</span>' : c.nonPris ? '<span class="petit">non pris en charge par le site</span>' : c.erreur ? '<span class="ambre">' + esc(c.erreur) + '</span>' : c.ok ? '<span class="vert">envoyé ' + hm(c.ok) + '</span>' : '<span class="petit">en attente de données</span>') + '</td></tr>').join('');
  if (g('site-direct') && g('site-direct').dataset.vu !== dl && !(document.activeElement && document.activeElement.closest && document.activeElement.closest('#site-direct'))) { g('site-direct').dataset.vu = dl; g('site-direct').innerHTML = dl; }
  document.querySelectorAll('.site-b').forEach(b => { const k = b.dataset.site; b.hidden = !SITE.configure || (k && SITE.modes[k] === 'jamais'); });
}
async function sitePost(route, corps) {
  let j = null;
  try { j = await (await fetch('api/site/' + route, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: corps || '' })).json(); } catch (x) {}
  await siteCharge();
  return j;
}
/** The target chosen on the page (web or folder), before it is tested. */
let SITE_CIBLE = null;
function siteCible(c) { SITE_CIBLE = c; g('site-web').hidden = c === 'dossier'; g('site-dos').hidden = c !== 'dossier'; }
/** Step 2: the target typed (address and token, or folder) sent with the test; ready only if it passes. */
async function siteEssai() {
  const dos = (SITE_CIBLE || (SITE && SITE.cible)) === 'dossier';
  if (dos ? !v('site-dossier') : !v('site-url')) { txt('site-etat', dos ? 'Étape 1 : le dossier.' : 'Étape 1 : l’adresse de votre site.'); return; }
  txt('site-etat', 'Essai…');
  const b = 'cible=' + (dos ? 'dossier' : 'web') + (dos ? '&dossier=' + enc(v('site-dossier')) : '&url=' + enc(v('site-url')) + (v('site-jeton') ? '&jeton=' + enc(v('site-jeton')) : ''));
  g('site-jeton').value = '';
  const j = await sitePost('essai', b);
  SITE_CIBLE = null;
  g('site-etat').innerHTML = j ? (j.ok ? '<span class="vert">✓ ' + esc(j.message) + ' — prêt. Choisissez maintenant quoi envoyer (étape 3).</span>' : '<span class="rose">✗ ' + esc(j.message || j.erreur) + '</span>') : 'pas de réponse';
}
async function siteMode(k, m) { await sitePost('reglages', 'mode_' + enc(k) + '=' + enc(m)); }
/** 🌐 « Envoyer vers le site » beside an item: [type] the site's kind, [ref] SatMe's name for it. */
async function sitePublie(type, ref, bouton) {
  const j = await sitePost('publie', 'type=' + enc(type) + '&ref=' + enc(String(ref)));
  const dit = j ? (j.ok ? '🌐 ' + j.message : '✗ ' + (j.erreur || 'refusé')) : '✗ pas de réponse';
  if (bouton) { bouton.title = dit; bouton.textContent = j && j.ok ? '🌐 ✓' : '🌐 ✗'; }
  if (typeof toast === 'function') toast(dit); else if (!(j && j.ok)) alert(dit);
}
/** The button for a list's line (none while the module is off or the site not ready, or this kind never published). */
function siteBouton(type, ref) {
  if (!SITE || !SITE.module || !SITE.configure || SITE.modes[type] === 'jamais') return '';
  return '<button class="site-b" data-site="' + type + '" title="Envoyer vers le site web" onclick="event.stopPropagation(); sitePublie(\'' + type + '\', ' + JSON.stringify(String(ref)).replace(/"/g, '&quot;') + ', this)">🌐</button> ';
}
siteCharge();
setInterval(() => { if (!g('v-reglages').hidden) siteCharge(); }, 5000);
"""
}
