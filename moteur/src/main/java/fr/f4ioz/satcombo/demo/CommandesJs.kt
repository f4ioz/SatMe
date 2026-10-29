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
 * « Réglages › Assistant et domotique » on SatMe-PC's page (the server's
 * side is CommandesWebPc): the voice assistant (module « Assistant vocal
 * »), Home Assistant by MQTT (module « Home Assistant ») and the site's
 * remote commands (part of « Publication web »), each card hidden while its
 * module is off; and the floating button — push-to-talk, the last answer
 * shown — while the assistant is on. Questions and safe actions only: no
 * transmission is ever asked from here. No `$` in the texts: Kotlin
 * constants.
 */
object CommandesJs {

    const val DOC = "https://github.com/f4ioz/SatMe/blob/main/docs/assistant-domotique.fr.md"

    private const val SECURITE = """<div class="petit" style="margin-top:6px;padding:6px 8px;border-left:3px solid var(--rose);background:var(--s2)"><b>Jamais d'émission.</b> Ni la voix, ni Home Assistant, ni votre site ne peuvent passer en émission (PTT, CAT, DATV, SSTV, FT8, ADALM-PLUTO) ni tourner l'antenne : seulement des questions et quelques gestes de réception. Une demande d'émission reçoit la réponse « à faire à l'écran ».</div>"""

    /** The cards, in Réglages (each hidden while its module is off). */
    const val CARTES = """
    <div class="carte" id="asv-carte" hidden>
      <div class="t">Assistant vocal</div>
      <div class="petit aide">Dites « <b>SatMe</b>, prochain passage de l'ISS » au micro de ce PC : la phrase est écrite par Whisper <b>sur ce PC</b>, comprise, et la réponse est dite par la voix de « Traduction parlée » et affichée en bas à droite. Le bouton 🎙 en bas à droite (ou ci-dessous) écoute la phrase suivante sans le mot « SatMe ». Rien n'est envoyé nulle part ; rien n'est gardé que le texte des dernières commandes, en mémoire. <a href="""" + DOC + """" target="_blank" rel="noopener">Ce qu'on peut demander</a>.</div>
      """ + SECURITE + """
      <div class="petit" style="margin-top:8px"><b>1. Installer la reconnaissance</b> — un petit modèle Whisper pour les commandes, dans l'environnement Python de Phonie → texte (créé s'il manque, faster-whisper environ 120 Mo de plus).</div>
      <div class="ligne"><select id="asv-modele" style="flex:none" onchange="asvRegle('modele=' + this.value)"></select><button class="primaire" id="asv-installe" onclick="asvInstalle()">Installer</button><span class="petit" id="asv-inst-etat"></span></div>
      <pre class="petit" id="asv-inst-log" hidden style="max-height:140px;overflow:auto;white-space:pre-wrap"></pre>
      <div class="petit" style="margin-top:8px"><b>2. Écouter</b></div>
      <label class="petit" style="display:block"><input type="checkbox" id="asv-ecoute" onchange="asvRegle('ecoute=' + (this.checked ? 1 : 0))"> Écouter le micro pour le mot « SatMe » (sinon seulement le bouton 🎙)</label>
      <div class="ligne"><span class="petit">Micro</span><select id="asv-micro" onchange="asvRegle('microDonne=1&amp;micro=' + enc(this.value))"><option value="">celui du système</option></select>
        <span class="petit">Langue</span><select id="asv-langue" style="flex:none" onchange="asvRegle('langue=' + this.value)"><option value="auto">celle de SatMe</option><option value="fr">français</option><option value="en">anglais</option></select></div>
      <div class="ligne"><span class="petit">Sensibilité</span><input type="range" id="asv-sens" min="1" max="10" style="width:120px" onchange="asvRegle('sensibilite=' + this.value)">
        <span class="petit">niveau</span><span id="asv-niveau" style="display:inline-block;width:90px;height:8px;background:var(--s3);border-radius:4px;overflow:hidden"><span id="asv-niveau-b" style="display:block;height:100%;width:0;background:var(--vert)"></span></span></div>
      <label class="petit" style="display:block"><input type="checkbox" id="asv-parle" onchange="asvRegle('parle=' + (this.checked ? 1 : 0))"> Dire la réponse à voix haute sur ce PC (voix de Réglages › Phonie → texte › Traduction parlée, sinon celle du navigateur)</label>
      <div class="petit" id="asv-etat" style="margin-top:4px"></div>
      <div class="petit" style="margin-top:8px"><b>Essayer sans micro</b></div>
      <div class="ligne"><input id="asv-texte" placeholder="prochain passage de SO-50" onkeydown="if (event.key === 'Enter') asvTexte()"><button onclick="asvTexte()">Demander</button><button onclick="asvBouton()" title="Écouter la phrase suivante au micro (8 s)">🎙 Parler</button></div>
      <div class="petit" id="asv-traces" style="margin-top:6px"></div>
    </div>
    <div class="carte" id="ha-carte" hidden>
      <div class="t">Home Assistant</div>
      <div class="petit aide">SatMe-PC publie l'état de la station vers votre broker MQTT (celui de Home Assistant, Mosquitto…) avec la découverte automatique : prochain passage et AOS, satellites visibles, auto-pilote, dernière image SSTV, DATV (verrouillage, MER, station), balise, rotor, dernier QSO. Avec « Actions », des interrupteurs (auto-pilote, réception SSTV et DATV, son coupé) et un champ « Commande SatMe ». Alexa ou Google Home les reprennent par Home Assistant : voir <a href="""" + DOC + """" target="_blank" rel="noopener">la documentation</a>. C'est SatMe-PC qui se connecte au broker : rien n'est ouvert sur ce PC.</div>
      """ + SECURITE + """
      <div class="ligne" style="margin-top:6px"><input id="ha-hote" placeholder="Broker : 192.168.1.10 ou homeassistant.local" spellcheck="false" autocomplete="off"><input id="ha-port" type="number" min="1" max="65535" style="max-width:90px" placeholder="1883"><label class="petit"><input type="checkbox" id="ha-tls"> TLS</label></div>
      <div class="ligne"><input id="ha-utilisateur" placeholder="Utilisateur MQTT" autocomplete="off" spellcheck="false"><input id="ha-mdp" type="password" placeholder="Mot de passe (gardé sur ce PC, jamais réaffiché)" autocomplete="new-password"><button id="ha-sans-mdp" hidden onclick="if (confirm('Oublier le mot de passe MQTT ?')) haRegle('sansMdp=1')">Oublier le mot de passe</button></div>
      <div class="ligne"><span class="petit">Racine des sujets</span><input id="ha-racine" style="max-width:140px" spellcheck="false"><span class="petit">Préfixe de découverte</span><input id="ha-prefixe" style="max-width:160px" spellcheck="false"><button class="primaire" onclick="haEnregistre()">Enregistrer</button></div>
      <label class="petit" style="display:block"><input type="checkbox" id="ha-on" onchange="haRegle('on=' + (this.checked ? 1 : 0))"> Publier vers Home Assistant</label>
      <label class="petit" style="display:block"><input type="checkbox" id="ha-actions" onchange="haRegle('actions=' + (this.checked ? 1 : 0))"> Actions : interrupteurs et champ « Commande » (réception et son seulement)</label>
      <div class="petit" id="ha-etat" style="margin-top:4px"></div>
    </div>
    <div class="carte" id="cmds-carte" hidden>
      <div class="t">Commandes à distance</div>
      <div class="petit aide">Pour une skill Alexa ou une page de <b>votre site</b> : le site ne se connecte jamais à la station. SatMe-PC lui envoie, par l'adresse et le jeton de « Envoyer vers un site web », les réponses aux questions (toutes les minutes), et va chercher les commandes qu'il a reçues (une requête que le site garde jusqu'à 25 s), puis lui renvoie le résultat. Les types <code>commandes-etat</code>, <code>commandes-attente</code> et <code>commandes-resultat</code> sont décrits dans <a href="""" + SiteWebJs.DOC + """" target="_blank" rel="noopener">la documentation de l'API</a>.</div>
      """ + SECURITE + """
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="cmds-questions" onchange="cmdsRegle('questions=' + (this.checked ? 1 : 0))"> Questions : envoyer les réponses (passages, auto-pilote, SSTV, DATV, balise, rotor, dernier QSO)</label>
      <label class="petit" style="display:block"><input type="checkbox" id="cmds-actions" onchange="cmdsRegle('actions=' + (this.checked ? 1 : 0))"> Actions : accepter du site l'auto-pilote, la réception SSTV et DATV, le son coupé</label>
      <div class="petit" id="cmds-etat" style="margin-top:4px"></div>
    </div>"""

    /** The floating button and the last answer (bottom right), outside the settings. */
    const val FLOTTANT = """
<div id="asv-flottant" hidden style="position:fixed;right:14px;bottom:44px;z-index:60;display:flex;flex-direction:column;align-items:flex-end;gap:6px;max-width:min(360px,calc(100vw - 28px))">
  <div id="asv-bulle" hidden style="background:var(--s1);border:1px solid var(--ligne-forte);border-radius:10px;padding:8px 10px;box-shadow:0 4px 18px rgba(0,0,0,.25);font-size:var(--fs-m)">
    <div class="petit" id="asv-entendu" style="opacity:.75"></div><div id="asv-reponse"></div></div>
  <button id="asv-micbtn" onclick="asvBouton()" title="Assistant vocal : poser une question (sans dire « SatMe »)" style="width:44px;height:44px;border-radius:50%;font-size:20px;padding:0">🎙</button>
</div>"""

    const val JS = """
// ------------------------------------------------------------------ « Assistant et domotique » (CommandesWebPc): voice, Home Assistant, the site's commands
let CMD = null, CMD_SEQ = 0, CMD_VU = 0, CMD_PAGE = 0, CMD_T = null;
const CMD_SOURCES = { voix: '🎙', page: '⌨', domotique: '🏠', site: '🌐' };
async function cmdPost(u, corps) {
  try { const j = await (await fetch(u, { method: 'POST', body: corps || '', headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })).json();
    if (j && j.ok === false && j.erreur) alert(j.erreur); return j; } catch (x) { return null; }
}
async function cmdCharge() {
  try { CMD = await (await fetch('api/commandes?depuis=' + CMD_SEQ)).json(); } catch (x) { CMD = null; }
  const a = CMD && CMD.assistant, h = CMD && CMD.domotique, s = CMD && CMD.site;
  const asvOn = !!(a && a.module), haOn = !!(h && h.module), sOn = !!(s && s.module);
  ['asv-carte', 'asv-flottant'].forEach(i => { const e = g(i); if (e) e.hidden = !asvOn; });
  g('ha-carte').hidden = !haOn; g('cmds-carte').hidden = !sOn;
  if (typeof regNavMaj === 'function') regNavMaj();
  if (!CMD) return;
  (CMD.traces || []).forEach(t => cmdTrace(t));
  CMD_SEQ = Math.max(CMD_SEQ, CMD.seq || 0);
  if (asvOn) asvMaj(a);
  if (haOn) haMaj(h);
  if (sOn) cmdsMaj(s);
}
const CMD_LISTE = [];
function cmdTrace(t) {
  if (CMD_LISTE.some(x => x.seq === t.seq)) return;
  CMD_LISTE.unshift(t); CMD_LISTE.length = Math.min(CMD_LISTE.length, 8);
  g('asv-traces').innerHTML = CMD_LISTE.map(x => '<div>' + (CMD_SOURCES[x.source] || '') + ' ' + hm(x.ms) + ' « ' + esc(x.texte) + ' » → ' + esc(x.reponse.fr) + '</div>').join('');
  // The floating answer: commands given on this PC (voice, page), and those of Home Assistant or the site, a moment.
  if (CMD_VU && t.seq > CMD_VU) cmdBulle(t);
  CMD_VU = Math.max(CMD_VU, t.seq);
}
function cmdBulle(t) {
  const b = g('asv-bulle'); if (!b || g('asv-flottant').hidden) return;
  txt('asv-entendu', (CMD_SOURCES[t.source] || '') + ' « ' + t.texte + ' »');
  txt('asv-reponse', t.reponse.fr);
  b.hidden = false; clearTimeout(CMD_T); CMD_T = setTimeout(() => { b.hidden = true; }, 12000);
}
function asvMaj(a) {
  const m = g('asv-modele');
  if (m.options.length !== (a.modeles || []).length) m.innerHTML = (a.modeles || []).map(x => '<option value="' + x + '">' + x + ' (' + (a.tailles[x] || '?') + ')</option>').join('');
  if (document.activeElement !== m) m.value = a.modele;
  g('asv-ecoute').checked = a.ecoute; g('asv-parle').checked = a.parle;
  if (document.activeElement !== g('asv-langue')) g('asv-langue').value = a.langue;
  if (document.activeElement !== g('asv-sens')) g('asv-sens').value = a.sensibilite;
  const mi = g('asv-micro');
  if (mi.dataset.charge !== '1') { mi.dataset.charge = '1'; asvMicros(a.micro); }
  const i = a.installation || {};
  g('asv-installe').textContent = a.pret ? 'Réinstaller' : 'Installer';
  g('asv-installe').disabled = !!i.enCours;
  txt('asv-inst-etat', i.enCours ? (i.etape || 'installation…') + (i.mo ? ' · ' + i.mo + ' Mo' : '') : i.erreur ? 'Échec : ' + i.erreur
    : a.pret ? 'Prêt : modèle « ' + a.modele + ' » présent.' : !a.python ? 'Pas encore installé (Python 3.9+ nécessaire sur ce PC).' : 'Modèle « ' + a.modele + ' » à télécharger.');
  const log = g('asv-inst-log'); log.hidden = !(i.enCours || i.erreur); if (!log.hidden) log.textContent = (i.lignes || []).join('\n');
  txt('asv-etat', 'État : ' + a.etat + (a.micOuvert ? ' · micro ouvert' : ' · micro fermé') + (a.attente ? ' · j’écoute la commande…' : '') + (a.erreur ? ' · ' + a.erreur : ''));
  g('asv-niveau-b').style.width = Math.min(100, Math.round((a.niveau || 0) * 300)) + '%';
  const btn = g('asv-micbtn'); btn.style.background = a.attente ? 'var(--rose)' : ''; btn.disabled = !a.pret;
  // A sentence the PC could not say: this browser says it (the page on this PC only).
  const p = a.pourPage;
  if (p && p.ms > CMD_PAGE) {
    if (CMD_PAGE && document.visibilityState === 'visible' && ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname) && window.speechSynthesis) {
      const u = new SpeechSynthesisUtterance(p.texte); u.lang = a.langue === 'en' ? 'en-GB' : (a.langue === 'fr' ? 'fr-FR' : document.documentElement.lang || 'fr-FR'); speechSynthesis.speak(u);
    }
    CMD_PAGE = p.ms;
  } else if (!CMD_PAGE) CMD_PAGE = 1;
  clearTimeout(window.CMD_VITE); if (a.attente || (i && i.enCours)) window.CMD_VITE = setTimeout(cmdCharge, 700);
}
async function asvMicros(choisi) {
  try { const j = await (await fetch('api/commandes/assistant/micros')).json();
    g('asv-micro').innerHTML = '<option value="">celui du système</option>' + (j.micros || []).map(m => '<option value="' + esc(m.nom).replace(/"/g, '&quot;') + '">' + esc(m.nom) + '</option>').join('');
    g('asv-micro').value = choisi || ''; } catch (x) {}
}
async function asvRegle(q) { await cmdPost('api/commandes/assistant', q); cmdCharge(); }
async function asvInstalle() {
  if (!confirm('Télécharger le modèle « ' + g('asv-modele').value + ' » (et faster-whisper s’il manque) dans l’environnement Python de SatMe ?')) return;
  await cmdPost('api/commandes/assistant/installe'); cmdCharge();
}
async function asvTexte() {
  const t = g('asv-texte').value.trim(); if (!t) return;
  const j = await cmdPost('api/commandes/texte', 'texte=' + enc(t));
  if (j && j.fr) { g('asv-texte').value = ''; cmdCharge(); }
}
async function asvBouton() { const j = await cmdPost('api/commandes/ecoute'); if (j && j.ok) cmdCharge(); }
function haMaj(h) {
  ['hote', 'port', 'utilisateur', 'racine', 'prefixe'].forEach(k => { const e = g('ha-' + k); if (document.activeElement !== e && !e.dataset.modifie) e.value = h[k] == null ? '' : h[k]; });
  g('ha-tls').checked = h.tls; g('ha-on').checked = h.on; g('ha-actions').checked = h.actions;
  g('ha-sans-mdp').hidden = !h.motDePasse;
  g('ha-mdp').placeholder = h.motDePasse ? 'Mot de passe enregistré (le remplacer)' : 'Mot de passe (gardé sur ce PC, jamais réaffiché)';
  txt('ha-etat', !h.hote ? 'Donner l’adresse du broker.' : !h.on ? 'Prêt ; cocher « Publier vers Home Assistant ».'
    : 'Broker : ' + h.etat + (h.erreur ? ' — ' + h.erreur : '') + (h.envoi ? ' · dernier état envoyé à ' + hm(h.envoi) : ''));
}
['hote', 'port', 'utilisateur', 'racine', 'prefixe'].forEach(k => { const e = g('ha-' + k); if (e) e.oninput = () => { e.dataset.modifie = '1'; }; });
async function haRegle(q) { await cmdPost('api/commandes/domotique', q); cmdCharge(); }
async function haEnregistre() {
  const v = k => enc(g('ha-' + k).value.trim());
  let q = 'hote=' + v('hote') + '&port=' + v('port') + '&tls=' + (g('ha-tls').checked ? 1 : 0) + '&utilisateurDonne=1&utilisateur=' + v('utilisateur') +
    '&racine=' + v('racine') + '&prefixe=' + v('prefixe');
  // The password only when typed, in the body; the field emptied at once.
  if (g('ha-mdp').value) q += '&mdp=' + enc(g('ha-mdp').value);
  g('ha-mdp').value = '';
  ['hote', 'port', 'utilisateur', 'racine', 'prefixe'].forEach(k => { delete g('ha-' + k).dataset.modifie; });
  await haRegle(q);
}
function cmdsMaj(s) {
  g('cmds-questions').checked = s.questions; g('cmds-actions').checked = s.actions;
  txt('cmds-etat', !s.possible ? 'D’abord régler et tester « Envoyer vers un site web » (une adresse web).'
    : (s.questions || s.actions) ? ((s.etat ? 'État envoyé à ' + hm(s.etat) : 'État pas encore envoyé') + (s.actions ? (s.attente ? ' · commandes demandées à ' + hm(s.attente) : '') : '') + (s.erreur ? ' · ' + s.erreur : ''))
    : 'Rien n’est envoyé tant qu’aucune case n’est cochée.');
}
async function cmdsRegle(q) { await cmdPost('api/commandes/site', q); cmdCharge(); }
cmdCharge();
setInterval(cmdCharge, 2500);
"""
}
