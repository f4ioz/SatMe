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
 * The page's start-up side (SatMe-PC only: `api/demarrage` absent, all of
 * it stays hidden): « Configuration rapide » on a new install, « Quoi de
 * neuf » after an update, the « Nouvelle version » badge in the header,
 * Réglages › Démarrage (network, port, data folder, update check) and the
 * « Déploiement » card under Sauvegarde. Put into [PagePc.HTML]; no `$` in
 * the script (a Kotlin template).
 */
object DemarragePagePc {

    const val CSS = """
  /* ---------------------------------------------------------------- start-up: quick setup, what's new, update badge */
  .h-maj { border-color:rgba(255,180,84,.55); color:var(--ambre); cursor:pointer; }
  .h-maj:hover { background:rgba(255,180,84,.12); }
  .h-portable { border-color:rgba(63,224,200,.5); color:var(--rx); font-size:var(--fs-xs); font-weight:700; letter-spacing:.4px; }
  .dlg-dem { width:min(620px,94vw); max-height:88vh; overflow:auto; padding:14px 18px; border-radius:12px; background:var(--s2); border:1px solid var(--ligne-forte); box-shadow:var(--ombre-pop); }
  .dlg-dem h3 { margin:14px 0 4px; font-size:14px; color:var(--rx); }
  .dlg-dem h3:first-child { margin-top:0; }
  .dlg-dem ul { margin:4px 0 8px; padding-left:20px; line-height:1.5; }
  .dlg-dem li { margin:3px 0; }
  .dlg-dem .dem-pied { display:flex; gap:8px; align-items:center; margin-top:14px; flex-wrap:wrap; }
  .cfr-pas { display:flex; gap:6px; margin:0 0 12px; }
  .cfr-pas i { flex:1; height:4px; border-radius:2px; background:var(--s3); }
  .cfr-pas i.fait { background:var(--rx); }
  .cfr-etape label.cfr-l { display:block; margin:10px 0 3px; font-size:var(--fs-s); color:var(--texte-2); }
  .cfr-etape input[type=text], .cfr-etape input:not([type]), .cfr-etape input[type=number] { width:100%; box-sizing:border-box; }
  .cfr-ok { color:var(--vert); } .cfr-ko { color:var(--rose); }
  .dem-tab { width:100%; border-collapse:collapse; margin-top:6px; }
  .dem-tab td, .dem-tab th { padding:5px 6px; border-bottom:1px solid var(--ligne); text-align:left; vertical-align:middle; font-size:var(--fs-s); }
  .dem-tab th { color:var(--texte-3); font-weight:600; }
  .dem-v { font-family:var(--mono); font-size:var(--fs-s); word-break:break-all; }
  .dem-src { display:inline-block; padding:0 7px; border-radius:10px; background:var(--s3); color:var(--texte-2); font-size:var(--fs-xs); white-space:nowrap; }
  .dem-src.ligne { background:rgba(255,180,84,.15); color:var(--ambre); } .dem-src.fichier { background:rgba(63,224,200,.14); color:var(--rx); }
  .dem-avert { margin-top:8px; padding:8px 10px; border-radius:var(--r2); background:rgba(255,180,84,.10); border:1px solid rgba(255,180,84,.45); color:var(--texte); font-size:var(--fs-s); line-height:1.5; }
  .dep-pre { max-height:240px; overflow:auto; white-space:pre; font-family:var(--mono); font-size:var(--fs-xs); background:var(--creux); padding:8px; border-radius:var(--r1); }
  .dep-ch { max-height:260px; overflow:auto; }
"""

    /** The badge in the header, before « ? ». */
    const val ENTETE = """<span id="h-portable" class="h-puce h-portable" hidden>Portable</span><button id="h-maj" class="h-puce h-maj" hidden onclick="mjOuvre()" title="Une version plus récente de SatMe-PC est publiée : ses nouveautés et le téléchargement">⬆<span class="h-m" id="h-maj-t">Nouvelle version</span></button>"""

    /** The windows: the update, « Quoi de neuf », « Configuration rapide ». */
    const val DIALOGUES = """
<dialog id="mj-dlg" class="dlg-dem" aria-labelledby="mj-titre">
  <div class="dlg-tete"><b id="mj-titre">Nouvelle version</b><button class="discret" onclick="g('mj-dlg').close()" aria-label="Fermer">✕</button></div>
  <div class="petit" id="mj-sous"></div>
  <div id="mj-notes" style="margin-top:8px"></div>
  <div class="petit" id="mj-comment" style="margin-top:8px"></div>
  <div class="dem-pied"><a class="bouton primaire" id="mj-tele" href="#" target="_blank" rel="noopener noreferrer">⤓ Télécharger</a>
    <a class="bouton" id="mj-page" href="#" target="_blank" rel="noopener noreferrer">La page de la version</a>
    <span class="h-esp"></span><button onclick="mjMasque()" title="Ne plus montrer le badge pour cette version, dans ce navigateur">Masquer</button></div>
</dialog>
<dialog id="avd-dlg" class="dlg-dem" aria-labelledby="avd-titre">
  <div class="dlg-tete"><b id="avd-titre">Vos données</b><button class="discret" onclick="avdFerme()" aria-label="Fermer">✕</button></div>
  <div class="dem-avert" id="avd-texte" style="word-break:break-word"></div>
  <div class="dem-pied"><button class="primaire" onclick="avdFerme()">J’ai compris</button></div>
</dialog>
<dialog id="qdn-dlg" class="dlg-dem" aria-labelledby="qdn-titre">
  <div class="dlg-tete"><b id="qdn-titre">Quoi de neuf</b><button class="discret" onclick="qdnFerme()" aria-label="Fermer">✕</button></div>
  <div class="petit" id="qdn-sous"></div>
  <div id="qdn-corps" style="margin-top:6px"></div>
  <div class="dem-pied"><button class="primaire" onclick="qdnFerme()">Fermer</button><span class="h-esp"></span><span class="petit">Toujours dans le menu d’aide (?)</span></div>
</dialog>
<dialog id="cfr-dlg" class="dlg-dem" aria-labelledby="cfr-titre">
  <div class="dlg-tete"><b id="cfr-titre">Configuration rapide</b><button class="discret" onclick="cfrPasse()" aria-label="Fermer">✕</button></div>
  <div class="cfr-pas" id="cfr-pas" aria-hidden="true"></div>
  <div class="cfr-etape" data-e="0">
    <h3>Ta station</h3>
    <div class="petit">Quelques réglages pour commencer ; tout se change ensuite dans Réglages. Rien n’est envoyé sur Internet.</div>
    <label class="cfr-l" for="cfr-indicatif">Indicatif</label><input id="cfr-indicatif" placeholder="F4ABC" style="text-transform:uppercase" autocomplete="off" oninput="cfrValide()">
    <label class="cfr-l" for="cfr-nom">Nom (écrit sous l’indicatif sur les planches SSTV, facultatif)</label><input id="cfr-nom" placeholder="Prénom" autocomplete="off">
  </div>
  <div class="cfr-etape" data-e="1" hidden>
    <h3>Où est la station</h3>
    <label class="cfr-l" for="cfr-locator">Locator (Maidenhead)</label><input id="cfr-locator" placeholder="JN18FS" style="text-transform:uppercase" autocomplete="off" oninput="cfrValide()">
    <div class="petit" id="cfr-loc-etat"></div>
    <details id="cfr-pos" style="margin-top:8px"><summary class="petit">ou le calculer depuis la latitude et la longitude (plus précis)</summary>
      <div class="ligne"><input id="cfr-lat" placeholder="latitude (48.85)" oninput="cfrLatLon()"><input id="cfr-lon" placeholder="longitude (2.35, ouest négatif)" oninput="cfrLatLon()"></div>
      <div class="petit">Degrés décimaux, par ex. relevés sur une carte. Les passages sont alors calculés depuis ce point exact.</div></details>
    <label class="cfr-l" for="cfr-minel">Passages au-dessus de (° d’élévation)</label><input id="cfr-minel" type="number" min="0" max="45" style="max-width:90px">
  </div>
  <div class="cfr-etape" data-e="2" hidden>
    <h3>Affichage</h3>
    <label class="cfr-l">Heures</label>
    <label class="petit" style="display:block"><input type="radio" name="cfr-utc" value="0"> heure locale de ce PC</label>
    <label class="petit" style="display:block"><input type="radio" name="cfr-utc" value="1"> UTC (comme le carnet)</label>
    <label class="cfr-l" for="cfr-langue">Langue des rapports PDF, des messages et des notes de version</label>
    <select id="cfr-langue"><option value="auto">celle du système</option><option value="fr">français</option><option value="en">anglais</option></select>
    <div class="petit">La page elle-même est en français. Distances et altitudes : en kilomètres et en mètres.</div>
    <h3>Le poste et le rotor</h3>
    <div class="petit">Le modèle du poste (CAT), son port, le rotor : <b>Réglages › Poste et rotor</b>, puis « Connecter ». Commencer avec le poste et le rotor <b>simulés</b> : rien n’est ouvert.
      <a href="#" onclick="event.preventDefault(); cfrFin(true).then(() => { vue('reglages'); montreReglages('materiel'); })">Terminer et y aller</a></div>
  </div>
  <div class="cfr-etape" data-e="3" hidden>
    <h3>Ce qui t’intéresse</h3>
    <div class="petit">Les onglets et les réglages des autres parties quittent la page ; tout se rallume, module par module, dans <b>Réglages › Modules</b>. Le suivi des passages, le poste, le rotor, le Carnet et le Journal sont toujours là.</div>
    <div id="cfr-profils" style="margin-top:6px"></div>
  </div>
  <div class="cfr-etape" data-e="4" hidden>
    <h3>Démarrage</h3>
    <div id="cfr-reseau-bloc">
      <label style="display:block"><input type="checkbox" id="cfr-reseau" onchange="cfrReseauAvert()"> <b>Accès depuis le réseau</b> <span class="petit">— la page ouverte aux autres machines (une tablette, le PC du salon)</span></label>
      <div class="dem-avert" id="cfr-reseau-avert" hidden></div>
      <label class="cfr-l" for="cfr-port">Port de la page</label><input id="cfr-port" type="number" min="1024" max="65534" placeholder="8090" style="max-width:110px">
      <div class="petit" style="margin-top:8px">Pris en compte au prochain démarrage de SatMe-PC. Le dossier des données et le reste : Réglages › Démarrage.</div>
    </div>
    <div class="petit" id="cfr-reseau-refus" hidden>Ces options ne se changent que depuis le PC de la station (ou une session ouverte avec le mot de passe).</div>
    <label style="display:block;margin-top:10px"><input type="checkbox" id="cfr-maj" checked> Me prévenir quand une nouvelle version de SatMe-PC est publiée <span class="petit">(GitHub, une fois par jour)</span></label>
  </div>
  <div class="etat" id="cfr-msg"></div>
  <div class="dem-pied"><button id="cfr-prec" onclick="cfrVa(-1)">‹ Précédent</button><button class="primaire" id="cfr-suiv" onclick="cfrVa(1)">Suivant ›</button>
    <span class="h-esp"></span><button class="discret" onclick="cfrPasse()" title="Fermer ; elle reste dans Réglages › Démarrage">Passer</button></div>
</dialog>
"""

    /** Réglages › Démarrage's cards, and « Déploiement » (under Sauvegarde). */
    const val CARTES = """
    <div class="carte" id="dem-carte" hidden>
      <div class="t">Options de démarrage</div>
      <div class="petit aide">Les options de la ligne de commande, réglées ici : <b>Accès depuis le réseau</b> (--reseau), <b>Port</b> (--port), <b>Dossier des données</b> (--dossier). Gardées dans un petit fichier à part, lu au lancement ; la ligne de commande passe toujours avant ce fichier, et ce fichier avant les valeurs par défaut. Changées seulement depuis ce PC (ou une session ouverte avec le mot de passe), prises en compte au prochain démarrage.</div>
      <table class="dem-tab"><thead><tr><th>Option</th><th>En ce moment</th><th>Au prochain démarrage</th></tr></thead><tbody>
        <tr><td><b>Accès depuis le réseau</b></td><td id="dem-reseau-v"></td><td><select id="dem-reseau" onchange="demAvert()"><option value="">par défaut (non)</option><option value="1">oui</option><option value="0">non</option></select></td></tr>
        <tr><td><b>Port</b></td><td id="dem-port-v"></td><td><input id="dem-port" type="number" min="1024" max="65534" placeholder="8090 (par défaut)" style="max-width:150px"></td></tr>
        <tr><td><b>Dossier des données</b></td><td id="dem-dossier-v" style="word-break:break-all"></td><td><input id="dem-dossier" placeholder="par défaut" oninput="demDossier()" style="min-width:180px"></td></tr>
      </tbody></table>
      <div id="dem-donnees" hidden style="margin-top:6px">
        <label class="petit" style="display:block"><input type="radio" name="dem-donnees" value="copier" checked> copier mes données dans le nouveau dossier au prochain démarrage (il doit être vide ; l’ancien dossier est gardé tel quel)</label>
        <label class="petit" style="display:block"><input type="radio" name="dem-donnees" value="vide"> commencer avec un dossier vide (l’ancien est gardé, rien n’est effacé)</label>
      </div>
      <div class="dem-avert" id="dem-avert" hidden></div>
      <div class="ligne"><button class="primaire" id="dem-enr" onclick="demEnregistre()">Enregistrer</button><button id="dem-redem" hidden onclick="demRedemarre()">⟳ Redémarrer SatMe-PC</button></div>
      <div class="etat" id="dem-msg"></div>
      <div class="petit" id="dem-fichier" style="margin-top:6px;word-break:break-all"></div>
    </div>
    <div class="carte" id="mj-carte" hidden>
      <div class="t">Mise à jour</div>
      <label style="display:block"><input type="checkbox" id="mj-on" onchange="mjRegle(this.checked)"> <b>Vérifier les mises à jour</b> <span class="petit">— la dernière version publiée sur GitHub, une fois par jour</span></label>
      <div class="petit aide">Une seule question à l’API publique de GitHub (sans compte), au démarrage puis chaque jour ; la réponse est gardée (mise-a-jour.json). Rien n’est téléchargé ni installé tout seul : un badge discret en haut, ses nouveautés et le bon fichier (l’installateur sous Windows, satme-pc.tar sous Linux). Une version de développement ne demande rien.</div>
      <div class="etat" id="mj-etat"></div>
      <div class="ligne"><button onclick="mjVerifie()">Vérifier maintenant</button><a class="petit" id="mj-lien" href="#" target="_blank" rel="noopener noreferrer" hidden>La page de la version</a></div>
    </div>
    <div class="carte" id="cfr-carte" hidden>
      <div class="t">Configuration rapide</div>
      <div class="petit">L’indicatif, le locator, le nom, l’heure, la langue et le démarrage en quelques étapes, comme au premier lancement.</div>
      <div class="ligne"><button class="primaire" onclick="cfrOuvre()">Configuration rapide…</button><button onclick="qdnOuvre(true)">Quoi de neuf…</button><span class="petit" id="cfr-version"></span></div>
    </div>
    <div class="carte" id="dep-carte" hidden>
      <div class="t">Déploiement</div>
      <div class="petit aide">Les réglages de la station et les options de démarrage dans un petit fichier (JSON), pour préparer un autre PC de la même façon. Pas le carnet ni les images : la sauvegarde générale ci-dessus les garde. Les secrets (mots de passe, clés d’API, identifiants QRZ.com, codes du SatMe-Node) n’y sont que si la case est cochée ; le mot de passe de l’accès à distance et le certificat HTTPS n’y sont jamais.</div>
      <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="dep-secrets" onchange="depLien()"> avec les secrets (mots de passe, clés, identifiants) — garder alors le fichier pour soi</label>
      <div class="ligne"><a class="bouton" id="dep-lien" href="deploiement.json" download>⤓ Fichier de déploiement</a></div>
      <div class="t" style="margin-top:12px">Importer sur ce PC</div>
      <div class="ligne"><input type="file" id="dep-fichier" accept=".json,application/json" onchange="depApercu(this)"></div>
      <div id="dep-apercu" hidden>
        <div class="petit" id="dep-resume"></div>
        <div class="dep-ch"><table class="dem-tab"><thead><tr><th>Réglage</th><th>Ici</th><th>Dans le fichier</th></tr></thead><tbody id="dep-lignes"></tbody></table></div>
        <label class="petit" style="display:block;margin-top:6px"><input type="checkbox" id="dep-reglages" checked> les réglages de la station (repris au prochain démarrage ; ceux remplacés gardés dans avant-deploiement-…)</label>
        <label class="petit" style="display:block"><input type="checkbox" id="dep-lanceur"> les options de démarrage (réseau, port, dossier)</label>
        <div class="ligne"><button class="primaire" onclick="depImporte()">Importer</button><button onclick="depFerme()">Annuler</button></div>
      </div>
      <div class="etat" id="dep-etat"></div>
      <div class="t" style="margin-top:12px">Lancer SatMe-PC avec la session</div>
      <div class="petit">Un fichier à installer soi-même (SatMe-PC n’installe rien) : sous Linux une unité systemd --user, sous Windows un petit lanceur à mettre dans le dossier Démarrage. Les options écrites ci-dessus y sont lues.</div>
      <div class="ligne"><a class="bouton" href="api/demarrage/autostart?quoi=systemd" download="satme-pc.service">⤓ satme-pc.service (Linux)</a><a class="bouton" href="api/demarrage/autostart?quoi=windows" download="SatMe-PC-demarrage.cmd">⤓ SatMe-PC-demarrage.cmd (Windows)</a>
        <button onclick="depVoir()">Voir</button></div>
      <pre class="dep-pre" id="dep-texte" hidden></pre>
    </div>
"""

    /** The help window's line. */
    const val AIDE = """<div><span class="h-rond" style="display:inline-grid">⬆</span></div><div><a href="#" onclick="event.preventDefault(); g('aide-dlg').close(); qdnOuvre(true)">Quoi de neuf</a> : ce qui a changé, version par version · <a href="#" onclick="event.preventDefault(); g('aide-dlg').close(); cfrOuvre()">Configuration rapide</a> (indicatif, locator, démarrage)</div>"""

    const val JS = """
// ------------------------------------------------------------------ start-up: quick setup, what's new, update, Réglages › Démarrage, deployment
// All of it from api/demarrage (SatMe-PC only: absent on the phone's page, the cards and windows stay hidden).
let DEM = null, CFR_E = 0;
async function demCharge() {
  try { const r = await fetch('api/demarrage'); DEM = r.ok ? await r.json() : null; } catch (x) { DEM = null; }
  const ok = !!(DEM && DEM.present);
  ['dem-carte', 'mj-carte', 'cfr-carte', 'dep-carte'].forEach(i => { const e = g(i); if (e) e.hidden = !ok; });
  const nav = document.querySelector('#reg-nav button[data-cat="demarrage"]'); if (nav) nav.hidden = !ok;
  if (!ok) return null;
  demMontre(); mjMontre(DEM.maj); depEtat();
  // A portable SatMe-PC (its « donnees » folder beside the program): said in the header.
  const po = g('h-portable');
  if (po) { po.hidden = !DEM.portable; po.title = DEM.portable ? 'SatMe-PC portable : réglages et fichiers dans ' + DEM.portable + ', rien ailleurs sur ce PC' : ''; }
  return DEM;
}
function demSource(o) { return '<span class="dem-src ' + o.source + '" title="D’où vient cette valeur">' + esc(o.sourceTexte) + '</span>'; }
function demMontre() {
  const L = DEM.lanceur, a = document.activeElement;
  g('dem-reseau-v').innerHTML = (L.reseau.valeur ? '<b>oui</b>' : 'non') + ' ' + demSource(L.reseau);
  g('dem-port-v').innerHTML = '<span class="dem-v">' + L.port.valeur + '</span> ' + demSource(L.port);
  g('dem-dossier-v').innerHTML = '<span class="dem-v">' + esc(L.dossier.valeur) + '</span> ' + demSource(L.dossier);
  if (a !== g('dem-reseau')) g('dem-reseau').value = L.reseau.enregistre == null ? '' : (L.reseau.enregistre ? '1' : '0');
  if (a !== g('dem-port')) g('dem-port').value = L.port.enregistre == null ? '' : L.port.enregistre;
  if (a !== g('dem-dossier')) g('dem-dossier').value = L.dossier.enregistre == null ? '' : L.dossier.enregistre;
  g('dem-dossier').placeholder = 'par défaut : ' + L.defauts.dossier;
  ['dem-reseau', 'dem-port', 'dem-dossier', 'dem-enr'].forEach(i => g(i).disabled = !DEM.permis);
  txt('dem-fichier', 'Fichier de démarrage : ' + L.fichier + (L.existe ? '' : ' (pas encore écrit)') + (L.copieDepuis ? ' · copie des données de ' + L.copieDepuis + ' au prochain démarrage' : '') +
    (DEM.permis ? '' : ' · Ces options ne se changent que depuis le PC de la station, ou une session ouverte avec le mot de passe.'));
  demDossier(); demAvert(); demRedem(L.change);
  txt('cfr-version', 'SatMe-PC ' + DEM.version);
}
function demDossier() {
  const d = v('dem-dossier'), L = DEM && DEM.lanceur;
  g('dem-donnees').hidden = !L || !d || d === L.dossier.valeur;
}
function demAvert() {
  const on = g('dem-reseau').value === '1' && !(DEM && DEM.lanceur.reseau.valeur);
  g('dem-avert').hidden = !on;
  if (on) g('dem-avert').innerHTML = demTexteReseau();
}
function demTexteReseau() {
  return '<b>Page ouverte aux autres machines du réseau.</b> ' + (DEM && DEM.motDePasse ? 'Elles devront donner le mot de passe de ' : 'Aucun mot de passe n’est encore défini : un mot de passe sera tiré au démarrage (dans le journal et dans ') +
    '<a href="#" onclick="event.preventDefault(); g(\'cfr-dlg\').open && g(\'cfr-dlg\').close(); vue(\'reglages\'); montreReglages(\'acces\')">Réglages › Accès à distance</a>' + (DEM && DEM.motDePasse ? '.' : ') ; le remplacer par le sien.') +
    ' Sans HTTPS, tout passe en clair : réseau de la maison seulement, jamais ouvert sur Internet (un VPN pour l’extérieur). Sous Linux avec ufw, ouvrir le port au réseau local.';
}
// « Redémarrer SatMe-PC » only where the launcher allows it; otherwise the reason (systemd, a terminal) said.
function demRedem(montre) {
  const R = DEM.redemarrage;
  g('dem-redem').hidden = !(montre && R.possible && DEM.permis);
  if (montre && !R.possible) txt('dem-msg', 'Pris en compte au prochain démarrage. ' + R.raison);
}
async function demPost(u, corps) {
  try { const r = await fetch(u, { method: 'POST', body: corps || '' }); return await r.json(); } catch (x) { return null; }
}
async function demEnregistre() {
  const d = v('dem-dossier'), L = DEM.lanceur;
  if (d && d !== L.dossier.valeur && document.querySelector('input[name=dem-donnees]:checked').value === 'vide' &&
      !confirm('Commencer avec un dossier vide au prochain démarrage ? Les données actuelles restent dans ' + L.dossier.valeur + ' (rien n’est effacé).')) return;
  if (g('dem-reseau').value === '1' && !L.reseau.valeur && !confirm('Ouvrir la page aux autres machines du réseau au prochain démarrage ?')) return;
  const q = 'reseau=' + enc(g('dem-reseau').value) + '&port=' + enc(v('dem-port')) + '&dossier=' + enc(d) +
    '&donnees=' + enc((document.querySelector('input[name=dem-donnees]:checked') || {}).value || 'vide');
  const j = await demPost('api/demarrage/lanceur?' + q);
  if (!j || j.ok === false) { txt('dem-msg', 'Refusé : ' + (j && j.erreur || 'pas de réponse')); return; }
  await demCharge();
  txt('dem-msg', j.message + (j.avertissements && j.avertissements.length ? ' ' + j.avertissements.join(' ') : ''));
  demRedem(DEM.lanceur.change);
}
async function demRedemarre() {
  if (!confirm('Redémarrer SatMe-PC maintenant ? Toute émission, le poste, le rotor et les réceptions en cours sont arrêtés comme à la fermeture.')) return;
  const j = await demPost('api/demarrage/redemarre');
  if (!j || j.ok === false) { txt('dem-msg', 'Refusé : ' + (j && j.erreur || 'pas de réponse')); return; }
  txt('dem-msg', j.message);
  demRevient();
}
// After a restart: the page comes back by itself once SatMe-PC answers again (at its new port when the page is on the plain port and it changed).
function demRevient() {
  const L = DEM && DEM.lanceur;
  const neuf = L && L.port.source !== 'ligne' && L.port.enregistre ? String(L.port.enregistre) : null;
  const surPort = L && (location.port || (location.protocol === 'https:' ? '443' : '80')) === String(L.port.valeur);
  const base = neuf && surPort && neuf !== String(L.port.valeur) ? location.protocol + '//' + location.hostname + ':' + neuf + '/' : null;
  let tombe = false, n = 0;
  const t = setInterval(async () => {
    n++;
    try {
      await fetch(base ? base + 'api/capacites' : 'api/capacites', base ? { mode: 'no-cors', cache: 'no-store' } : { cache: 'no-store' });
      if (tombe || base || n > 10) { clearInterval(t); location.href = base || location.pathname; }
    } catch (x) { tombe = true; }
    if (n > 90) { clearInterval(t); txt('dem-msg', 'SatMe-PC ne répond pas encore : recharger la page dans un moment.'); }
  }, 1500);
}
// ---- the update badge and its window
function mjMontre(M) {
  if (!M) return;
  let masquee = ''; try { masquee = localStorage.getItem('maj-masquee') || ''; } catch (x) {}
  g('h-maj').hidden = !(M.disponible && M.version !== masquee);
  if (M.disponible) { txt('h-maj-t', M.version); g('h-maj').title = M.texte || ''; }
  if (document.activeElement !== g('mj-on')) g('mj-on').checked = !!M.actif;
  txt('mj-etat', M.dev ? 'Version de développement (' + M.actuelle + ') : aucune vérification.' :
    'SatMe-PC ' + M.actuelle + (M.version ? ' · dernière publiée : ' + M.version + (M.disponible ? ' (plus récente)' : ' (à jour)') : '') +
    (M.verifie ? ' · vérifié le ' + new Date(M.verifie).toLocaleString() : M.actif ? ' · pas encore vérifié' : '') + (M.erreur && M.actif ? ' · ' + M.erreur : ''));
  g('mj-lien').hidden = !M.page; if (M.page) g('mj-lien').href = M.page;
}
function mjOuvre() {
  const M = DEM && DEM.maj; if (!M || !M.version) return;
  txt('mj-titre', M.texte || ('SatMe-PC ' + M.version));
  txt('mj-sous', 'Ici : SatMe-PC ' + M.actuelle + (M.date ? ' · publiée le ' + new Date(M.date).toLocaleDateString() : ''));
  g('mj-notes').innerHTML = M.notesHtml || '';
  g('mj-tele').hidden = !M.telechargement; if (M.telechargement) { g('mj-tele').href = M.telechargement; g('mj-tele').textContent = '⤓ ' + M.fichier; }
  g('mj-page').href = M.page;
  txt('mj-comment', /\.msi$/.test(M.fichier || '') ? 'Windows : fermer SatMe-PC (icône près de l’horloge), lancer l’installateur ; les réglages restent.' :
    'Linux : relancer le script d’installation (bash installe-satme.sh), ou décompresser satme-pc.tar à la place de l’ancien ; les réglages restent dans le dossier des données.');
  g('mj-dlg').showModal();
}
/* A newer version, once per version in this browser: its window opens by itself (not over another window, not when hidden with « Masquer »). */
function mjAuto(M) {
  if (!M || !M.disponible || !M.version) return;
  let vue = '', masquee = ''; try { vue = localStorage.getItem('maj-vue') || ''; masquee = localStorage.getItem('maj-masquee') || ''; } catch (x) {}
  if (vue === M.version || masquee === M.version) return;
  if (document.querySelector('dialog[open]')) { setTimeout(() => mjAuto(M), 5000); return; }
  try { localStorage.setItem('maj-vue', M.version); } catch (x) {}
  mjOuvre();
}
function mjMasque() { try { localStorage.setItem('maj-masquee', DEM.maj.version); } catch (x) {} g('mj-dlg').close(); mjMontre(DEM.maj); }
async function mjRegle(on) { const j = await demPost('api/maj/reglage?on=' + (on ? 1 : 0)); if (j) { DEM.maj = j; mjMontre(j); } }
async function mjVerifie() { txt('mj-etat', 'question à GitHub…'); const j = await demPost('api/maj/verifie'); if (j) { DEM.maj = j; mjMontre(j); } }
// ---- « Quoi de neuf »: once after an update (marked seen when closed), then from the help menu
let QDN_AUTO = false;
async function qdnOuvre(tout) {
  let l = DEM && DEM.quoiDeNeuf;
  if (tout) { try { const j = await (await fetch('api/demarrage/nouveautes')).json(); l = j.versions; } catch (x) { l = null; } }
  QDN_AUTO = !tout;
  txt('qdn-sous', tout ? 'Ici : SatMe-PC ' + (DEM ? DEM.version : '') : 'SatMe-PC a été mis à jour : ' + (l && l.length ? l[0].version : ''));
  g('qdn-corps').innerHTML = l && l.length ? l.map(n => '<h3>' + esc(n.version) + '</h3>' + n.html).join('') : '<div class="petit">Pas de notes de version dans ce SatMe-PC.</div>';
  g('qdn-dlg').showModal();
}
function qdnFerme() { g('qdn-dlg').close(); if (QDN_AUTO) { QDN_AUTO = false; demPost('api/demarrage/vu'); if (DEM) DEM.quoiDeNeuf = []; } }
// ---- The data found in the program's folder (MSI install) and copied out: said once, until read
function avdOuvre() { txt('avd-texte', DEM.avisDonnees); g('avd-dlg').showModal(); }
function avdFerme() {
  g('avd-dlg').close(); demPost('api/demarrage/avis-vu');
  const q = DEM && DEM.quoiDeNeuf; if (DEM) DEM.avisDonnees = null;
  if (q && q.length) setTimeout(() => qdnOuvre(false), 300);
}
// ---- « Configuration rapide »: four steps, written to the same settings as Réglages
const CFR_N = 5;
const MAIDENHEAD = /^[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?$/i;
function cfrLocDe(la, lo) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWX'; lo += 180; la += 90;
  let s = A[Math.min(17, Math.floor(lo / 20))] + A[Math.min(17, Math.floor(la / 10))]; lo %= 20; la %= 10;
  s += Math.min(9, Math.floor(lo / 2)) + '' + Math.min(9, Math.floor(la)); lo %= 2; la %= 1;
  return s + A[Math.min(23, Math.floor(lo * 12))] + A[Math.min(23, Math.floor(la * 24))];
}
function cfrNombre(id) { const t = v(id).replace(',', '.'); return t === '' ? null : Number(t); }
function cfrLatLon() {
  const la = cfrNombre('cfr-lat'), lo = cfrNombre('cfr-lon');
  if (la != null && lo != null && isFinite(la) && isFinite(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180) { g('cfr-locator').value = cfrLocDe(la, lo); }
  cfrValide();
}
function cfrValide() {
  const l = v('cfr-locator'), la = v('cfr-lat'), lo = v('cfr-lon');
  const e = g('cfr-loc-etat');
  if (la || lo) { const a = cfrNombre('cfr-lat'), b = cfrNombre('cfr-lon'); const ok = a != null && b != null && Math.abs(a) <= 90 && Math.abs(b) <= 180;
    e.className = 'petit ' + (ok ? 'cfr-ok' : 'cfr-ko'); e.textContent = ok ? '✓ ' + cfrLocDe(a, b) + ' d’après la latitude et la longitude' : 'latitude de -90 à 90, longitude de -180 à 180'; return ok; }
  const ok = !l || MAIDENHEAD.test(l);
  e.className = 'petit ' + (ok ? 'cfr-ok' : 'cfr-ko'); e.textContent = !l ? '' : ok ? '✓ locator reconnu' : 'pas un locator (2 lettres A–R, 2 chiffres, puis 2 lettres A–X : JN18FS)';
  return ok;
}
async function cfrOuvre() {
  if (!DEM) await demCharge();
  if (!DEM) return;
  const S = DEM.station;
  g('cfr-indicatif').value = S.indicatif || ''; g('cfr-nom').value = S.nom || ''; g('cfr-locator').value = S.locator || '';
  g('cfr-lat').value = S.lat == null ? '' : S.lat; g('cfr-lon').value = S.lon == null ? '' : S.lon; g('cfr-pos').open = S.lat != null;
  g('cfr-minel').value = S.minEl;
  let u = S.utc; try { const b = localStorage.getItem('utc'); if (b != null) u = b === '1'; } catch (x) {}
  document.querySelectorAll('input[name=cfr-utc]').forEach(r => r.checked = r.value === (u ? '1' : '0'));
  g('cfr-langue').value = S.langue || 'auto';
  const L = DEM.lanceur;
  g('cfr-reseau').checked = L.reseau.enregistre == null ? !!L.reseau.valeur : !!L.reseau.enregistre;
  g('cfr-port').value = L.port.enregistre == null ? '' : L.port.enregistre;
  g('cfr-reseau-bloc').hidden = !DEM.permis; g('cfr-reseau-refus').hidden = DEM.permis;
  g('cfr-maj').checked = !!DEM.maj.actif;
  cfrProfils();
  txt('cfr-msg', ''); cfrReseauAvert(); cfrValide(); cfrMontre(0);
  if (!g('cfr-dlg').open) g('cfr-dlg').showModal();
}
function cfrReseauAvert() {
  const on = g('cfr-reseau').checked && !(DEM && DEM.lanceur.reseau.valeur);
  g('cfr-reseau-avert').hidden = !on; if (on) g('cfr-reseau-avert').innerHTML = demTexteReseau();
}
function cfrMontre(e) {
  CFR_E = Math.max(0, Math.min(CFR_N - 1, e));
  document.querySelectorAll('#cfr-dlg .cfr-etape').forEach(d => d.hidden = +d.dataset.e !== CFR_E);
  g('cfr-pas').innerHTML = Array.from({ length: CFR_N }, (_, i) => '<i class="' + (i <= CFR_E ? 'fait' : '') + '"></i>').join('');
  g('cfr-prec').disabled = CFR_E === 0;
  g('cfr-suiv').textContent = CFR_E === CFR_N - 1 ? 'Terminer' : 'Suivant ›';
}
function cfrVa(d) {
  if (d > 0 && CFR_E === 0 && v('cfr-indicatif') && !/^(?=.*[0-9])(?=.*[A-Za-z])[A-Za-z0-9]{1,8}(\/[A-Za-z0-9]{1,8}){0,2}$/.test(v('cfr-indicatif'))) { txt('cfr-msg', 'Indicatif non reconnu (lettres, chiffres, « / »).'); return; }
  if (d > 0 && CFR_E === 1 && !cfrValide()) { txt('cfr-msg', 'Locator ou position à corriger.'); return; }
  txt('cfr-msg', '');
  if (d > 0 && CFR_E === CFR_N - 1) { cfrFin(false); return; }
  cfrMontre(CFR_E + d);
}
// The profiles of Réglages › Modules (api/capacites): « Tout » the first time, else the one in use (or the modules kept as they are).
function cfrProfils() {
  const b = g('cfr-profils');
  if (!MOD) { b.innerHTML = '<div class="petit">Le choix des modules n’est pas proposé ici.</div>'; return; }
  const choix = DEM.configRapide ? 'tout' : MOD.profil || 'garder';
  const l = MOD.profils.map(p => ({ id: p.id, nom: p.nom, quoi: p.quoi }));
  if (!DEM.configRapide && !MOD.profil) l.unshift({ id: 'garder', nom: 'Garder mes modules', quoi: 'ceux cochés dans Réglages › Modules' });
  b.innerHTML = l.map(p => '<label class="petit" style="display:block;margin-top:6px"><input type="radio" name="cfr-profil" value="' + p.id + '"' + (p.id === choix ? ' checked' : '') + '> <b>' + esc(p.nom) + '</b> — ' + esc(p.quoi) + '</label>').join('');
}
async function cfrFin(aller) {
  const u = (document.querySelector('input[name=cfr-utc]:checked') || {}).value || '0';
  let q = 'indicatif=' + enc(v('cfr-indicatif')) + '&nom=' + enc(v('cfr-nom')) + '&utc=' + u + '&langue=' + enc(g('cfr-langue').value) + '&minEl=' + enc(v('cfr-minel'));
  const la = v('cfr-lat'), lo = v('cfr-lon');
  q += (la || lo) ? '&lat=' + enc(la) + '&lon=' + enc(lo) : '&locator=' + enc(v('cfr-locator'));
  const L = DEM.lanceur, reseau = g('cfr-reseau').checked;
  if (DEM.permis) {
    const avant = L.reseau.enregistre == null ? !!L.reseau.valeur : !!L.reseau.enregistre;
    if (reseau !== avant) q += '&reseau=' + (reseau ? 1 : 0);
    if (v('cfr-port') !== (L.port.enregistre == null ? '' : String(L.port.enregistre))) q += '&port=' + enc(v('cfr-port'));
  }
  const j = await demPost('api/demarrage/configuration?' + q + '&fin=1');
  if (!j || j.ok === false) { txt('cfr-msg', 'Refusé : ' + (j && j.erreur || 'pas de réponse')); if (j && /ocator|atitude/.test(j.erreur || '')) cfrMontre(1); else if (j && /ndicatif/.test(j.erreur || '')) cfrMontre(0); return; }
  try { localStorage.setItem('utc', u); } catch (x) {}
  if (g('utc')) { g('utc').checked = u === '1'; try { dessinePassages(); } catch (x) {} }
  if (g('cfr-maj').checked !== !!DEM.maj.actif) await demPost('api/maj/reglage?on=' + (g('cfr-maj').checked ? 1 : 0));
  const pr = (document.querySelector('input[name=cfr-profil]:checked') || {}).value;
  if (MOD && pr && pr !== 'garder' && pr !== MOD.profil) await modPost('profil=' + pr);
  g('cfr-dlg').close();
  await demCharge();
  try { chargeReglages(); } catch (x) {}
  toast(j.lanceur && j.lanceur.ok ? 'Station enregistrée · réseau et port au prochain démarrage' : 'Station enregistrée');
  return j;
}
async function cfrPasse() {
  if (g('cfr-dlg').open) g('cfr-dlg').close();
  if (DEM && DEM.configRapide) { await demPost('api/demarrage/configuration?passe=1'); DEM.configRapide = false; }
}
// ---- Déploiement: the file, its preview, its import; the autostart files shown
function depLien() { g('dep-lien').href = 'deploiement.json' + (g('dep-secrets').checked ? '?secrets=1' : ''); }
function depEtat() {
  const n = DEM && DEM.deploiement ? DEM.deploiement.attente : 0;
  g('dep-etat').innerHTML = n ? n + ' réglage(s) d’un fichier de déploiement repris au prochain démarrage de SatMe-PC. <button onclick="demPost(\'api/deploiement/annule\').then(demCharge)">Annuler</button>' : '';
  g('dep-carte').querySelectorAll('input,button,a.bouton').forEach(e => { if (e.tagName !== 'A') e.disabled = !(DEM && DEM.permis); });
}
let DEP_F = null;
async function depApercu(input) {
  const f = input.files[0]; if (!f) return;
  DEP_F = f; txt('dep-etat', 'lecture…');
  const j = await demPost('api/deploiement/apercu', f);
  if (!j || j.ok === false) { txt('dep-etat', 'Refusé : ' + (j && j.erreur || 'pas de réponse')); depFerme(); return; }
  txt('dep-etat', '');
  const nom = { port: 'Port', reseau: 'Accès depuis le réseau', dossier: 'Dossier des données' };
  g('dep-lignes').innerHTML = j.lanceur.map(o => '<tr><td><b>' + esc(nom[o.nom] || o.nom) + '</b> <span class="dem-src">démarrage</span></td><td>' + esc(o.avant) + '</td><td>' + esc(o.apres) + '</td></tr>').join('') +
    j.reglages.map(o => '<tr><td>' + esc(o.cle) + (o.secret ? ' 🔒' : '') + '</td><td>' + esc(o.avant) + '</td><td>' + esc(o.apres) + '</td></tr>').join('');
  txt('dep-resume', 'Fichier de SatMe-PC ' + (j.version || '?') + (j.secrets ? ', avec ses secrets' : ', sans secrets') + ' : ' + j.reglages.length + ' réglage(s) changeraient, ' + j.pareils + ' déjà pareils' +
    (j.lanceur.length ? ', ' + j.lanceur.length + ' option(s) de démarrage.' : '.'));
  g('dep-lanceur').checked = false; g('dep-lanceur').disabled = !j.lanceur.length;
  g('dep-reglages').checked = j.reglages.length > 0; g('dep-reglages').disabled = !j.reglages.length;
  g('dep-apercu').hidden = false;
}
function depFerme() { g('dep-apercu').hidden = true; g('dep-fichier').value = ''; DEP_F = null; }
async function depImporte() {
  if (!DEP_F) return;
  const r = g('dep-reglages').checked, l = g('dep-lanceur').checked;
  if (!r && !l) { txt('dep-etat', 'Rien de coché.'); return; }
  if (!confirm('Importer ' + (r ? 'les réglages (au prochain démarrage)' : '') + (r && l ? ' et ' : '') + (l ? 'les options de démarrage' : '') + ' ?')) return;
  const j = await demPost('api/deploiement/importe?reglages=' + (r ? 1 : 0) + '&lanceur=' + (l ? 1 : 0), DEP_F);
  depFerme();
  await demCharge();
  txt('dep-etat', j && j.ok ? 'Importé : à reprendre au prochain démarrage de SatMe-PC (Réglages › Démarrage › Redémarrer, ou l’arrêter et le relancer).' : 'Refusé : ' + (j && j.erreur || 'pas de réponse'));
}
async function depVoir() {
  const p = g('dep-texte');
  if (!p.hidden) { p.hidden = true; return; }
  try { const a = await (await fetch('api/demarrage/autostart?quoi=systemd')).text(), b = await (await fetch('api/demarrage/autostart?quoi=windows')).text();
    p.textContent = '# satme-pc.service\n' + a + '\n:: SatMe-PC-demarrage.cmd\n' + b.replace(/\r/g, ''); p.hidden = false; } catch (x) {}
}
// At load: the quick setup on a new install, else « Quoi de neuf » once after an update; the badge; then every 10 minutes.
demCharge().then(d => {
  if (!d) return;
  const q = new URLSearchParams(location.search);
  if (d.avisDonnees) setTimeout(avdOuvre, 400);
  else if (q.has('configuration') || d.configRapide) setTimeout(cfrOuvre, 400);
  else if (q.has('nouveautes')) setTimeout(() => qdnOuvre(true), 400);
  else if (d.quoiDeNeuf && d.quoiDeNeuf.length) setTimeout(() => qdnOuvre(false), 600);
  if (q.has('nouvelle-version')) setTimeout(mjOuvre, 500);
  else setTimeout(() => mjAuto(d.maj), 1500);
});
// The check (GitHub, once a day) may answer after the page opened: asked again in a minute, then every 10 minutes.
function mjSuit() { if (!document.hidden) fetch('api/maj').then(r => r.ok ? r.json() : null).then(j => { if (j && DEM) { DEM.maj = j; mjMontre(j); mjAuto(j); } }).catch(() => {}); }
setTimeout(mjSuit, 60000);
setInterval(mjSuit, 600000);
"""
}
