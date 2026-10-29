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
 * « Agenda › Diplômes »: the awards the station can claim, what it has for
 * each (pictures of the event, grids and entities of the log), the notices,
 * « Ajouter depuis une affiche… » and « Préparer la demande » (everything to
 * copy; nothing is sent, no form is filled). Put into [PagePc.HTML]; no
 * Kotlin template here (no dollar sign at all in these strings).
 */
object DiplomesJs {

    const val CSS = """
  /* ---------------------------------------------------------------- Agenda › Diplômes (DiplomesJs) */
  .dp-grille { display:grid; grid-template-columns:minmax(320px,1fr) minmax(480px,2.2fr); gap:12px; }
  @media (max-width:900px) { .dp-grille { grid-template-columns:minmax(0,1fr); } }
  .dp-grille > div { min-width:0; }
  .dp-src { display:grid; grid-template-columns:1fr; gap:3px; font-size:var(--fs-s); }
  .dp-src .err { color:var(--ambre); }
  .dp-f { display:grid; grid-template-columns:max-content minmax(0,1fr); gap:6px 8px; align-items:center; }
  .dp-f input, .dp-f textarea { width:100%; box-sizing:border-box; }
  #dp-texte { width:100%; box-sizing:border-box; min-height:70px; }
  .dp-sec { margin:12px 0 2px; font-size:var(--fs-s); font-weight:700; letter-spacing:.06em; text-transform:uppercase; color:var(--texte-2); }
  .dp-sec summary { cursor:pointer; }
  .dp-d { border:1px solid var(--ligne); border-radius:var(--r2); padding:8px 10px; margin-top:6px; background:var(--s1); }
  .dp-d.dp-OBTENABLE { border-color:var(--vert); }
  .dp-d.dp-urgent { border-color:var(--ambre); box-shadow:0 0 0 1px var(--ambre) inset; }
  .dp-d .dp-t { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .dp-d .dp-t b { font-size:var(--fs-l); }
  .dp-d .dp-l { font-weight:600; margin-top:2px; }
  .dp-d.dp-OBTENABLE .dp-l { color:var(--vert); }
  .dp-d.dp-urgent .dp-l { color:var(--ambre); }
  .dp-d .petit a { color:var(--rx); }
  .dp-act { display:flex; gap:4px; flex-wrap:wrap; margin-top:6px; }
  .dp-img { display:flex; gap:6px; flex-wrap:wrap; margin-top:4px; }
  .dp-img a { position:relative; display:block; width:96px; }
  .dp-img img { width:96px; height:72px; object-fit:cover; border-radius:6px; border:1px solid var(--ligne); display:block; }
  .dp-img span { position:absolute; right:3px; bottom:3px; font-size:10px; background:rgba(0,0,0,.65); color:#fff; border-radius:6px; padding:0 4px; }
  .dp-barre { height:6px; border-radius:3px; background:var(--s3); overflow:hidden; margin-top:4px; max-width:360px; }
  .dp-barre i { display:block; height:100%; background:var(--rx); }
  .dp-elems { font-family:var(--mono); font-size:var(--fs-xs); line-height:1.5; word-break:break-word; }
  .pastille.dp-st-OBTENABLE { background:rgba(91,214,138,.2); color:var(--vert); }
  .pastille.dp-st-EN_COURS, .pastille.dp-st-PENDANT { background:rgba(63,224,200,.14); color:var(--rx); }
  .pastille.dp-st-A_VENIR { background:rgba(120,160,255,.16); color:#8fb0ff; }
  .pastille.dp-st-DEMANDE { background:rgba(255,180,84,.2); color:var(--ambre); }
  .pastille.dp-st-RECU { background:rgba(91,214,138,.3); color:var(--vert); }
  .pastille.dp-st-EXPIRE, .pastille.dp-st-MANQUE, .pastille.dp-st-MASQUE { background:rgba(229,72,77,.12); color:var(--rose); }
  #dp-liste { max-height:calc(100vh - 200px); overflow:auto; }
  #dp-dlg { width:min(720px,96vw); }
  #dp-dlg .dp-champs { display:grid; grid-template-columns:max-content minmax(0,1fr) max-content; gap:4px 8px; align-items:center; margin:8px 0; }
  #dp-dlg .dp-champs .v { font-family:var(--mono); word-break:break-word; }
  #dp-dlg .dp-champs .manque { color:var(--ambre); font-family:inherit; }
  #dp-dlg pre { white-space:pre-wrap; max-height:180px; overflow:auto; background:var(--s1); padding:8px; border-radius:6px; font-size:var(--fs-xs); }
  #dp-dlg .dp-img label { display:block; width:96px; cursor:pointer; font-size:10px; text-align:center; }
  #dp-dlg .dp-img input { margin:2px 0 0; }
  @media (max-width:640px) { #dp-dlg .dp-champs { grid-template-columns:minmax(0,1fr) max-content; } #dp-dlg .dp-champs .lib { grid-column:1 / -1; margin-top:4px; } }
"""

    const val HTML = """
  <div class="st-pan" id="ag-p-dip">
<div class="dp-grille">
  <div>
    <div class="carte">
      <div class="t">Diplômes</div>
      <div class="ligne" style="margin-top:0"><label><input type="checkbox" id="dp-actif" onchange="dpReglage('actif', this.checked)"> <b>Actif</b></label>
        <label><input type="checkbox" id="dp-sources-on" onchange="dpReglage('sources', this.checked)"> chercher en ligne</label>
        <button onclick="dpActualise()" title="Demander les sources maintenant (chacune au plus toutes les 5 min)">Actualiser</button></div>
      <div class="aide">Les diplômes permanents (AMSAT, ARRL : comptés dans le carnet), le diplôme ARISS SSTV de chaque session (sa page, l'agenda ARISS, tes campagnes) et les certificats annoncés dans les flux AMSAT, AMSAT-UK, AMSAT-F (toutes les 12 h au plus). Les images de la galerie et le carnet sont comparés ici, sur ce PC. Rien n'est envoyé, sauf vers ton site si tu coches « Diplômes » dans Publication web (la liste et ta progression) ou cliques « Envoyer le diplôme reçu ».</div>
      <div class="dp-src" id="dp-sources"></div>
    </div>
    <div class="carte">
      <div class="t">Prévenir</div>
      <div class="ap-c2" style="grid-template-columns:1fr">
        <label><input type="checkbox" id="dp-n-obtenable" onchange="dpReglage('obtenable', this.checked)"> Un diplôme devient obtenable (une fois)</label>
        <label><input type="checkbox" id="dp-n-echeance" onchange="dpReglage('echeance', this.checked)"> Sa date limite approche : <input id="dp-jours" type="number" min="1" max="60" style="max-width:56px" onchange="post('api/diplomes/reglage?k=jours&jours=' + (+this.value || 3)).then(dpCharge)"> jours avant</label>
        <label><input type="checkbox" id="dp-n-nouveau" onchange="dpReglage('nouveau', this.checked)"> Un nouveau diplôme d'événement est annoncé en ligne</label>
      </div>
    </div>
    <div class="carte">
      <div class="t">Pour les demandes</div>
      <div class="aide">Ce que les formulaires demandent et que les réglages n'ont pas. Gardé sur ce PC (diplomes.json) ; SatMe ne l'envoie nulle part : tu le copies toi-même.</div>
      <div class="dp-f">
        <span class="petit">Indicatif</span><span id="dp-id-ind" class="petit"></span>
        <span class="petit">Locator</span><span id="dp-id-loc" class="petit"></span>
        <label for="dp-nom" class="petit">Nom</label><input id="dp-nom" maxlength="80" placeholder="Prénom Nom (sur le diplôme)">
        <label for="dp-courriel" class="petit">Courriel</label><input id="dp-courriel" type="email" maxlength="120" placeholder="pour recevoir le diplôme">
      </div>
      <div class="ligne"><button onclick="dpIdentite()">Enregistrer</button><span class="petit" id="dp-id-etat" aria-live="polite"></span></div>
    </div>
    <div class="carte">
      <div class="t">Ajouter depuis une affiche…</div>
      <div class="aide">L'image d'une affiche (lue par tesseract sur ce PC) ou le texte d'une annonce collé : émetteur, événement, dates, date limite, formulaire. À vérifier avant d'ajouter.</div>
      <div class="ligne" style="margin-top:0"><input type="file" id="dp-fichier" accept="image/*" hidden onchange="dpAfficheFichier(this)"><button onclick="g('dp-fichier').click()">🖼 Choisir une image…</button></div>
      <textarea id="dp-texte" maxlength="20000" placeholder="ou colle ici le texte de l'annonce"></textarea>
      <div class="ligne"><button onclick="dpTexte()">Lire le texte</button><span class="petit" id="dp-imp-etat" aria-live="polite"></span></div>
      <div id="dp-prop" hidden>
        <div class="dp-f" style="margin-top:6px">
          <label for="dp-p-nom" class="petit">Diplôme</label><input id="dp-p-nom" maxlength="120">
          <label for="dp-p-em" class="petit">Émetteur</label><input id="dp-p-em" maxlength="80">
          <label for="dp-p-sat" class="petit">Satellite</label><input id="dp-p-sat" maxlength="40" list="tous" placeholder="ISS, UmKA-1… (vide : tout satellite)">
          <label for="dp-p-de" class="petit">Début</label><input id="dp-p-de" type="datetime-local">
          <label for="dp-p-a" class="petit">Fin</label><input id="dp-p-a" type="datetime-local">
          <label for="dp-p-lim" class="petit">Date limite</label><input id="dp-p-lim" type="datetime-local">
          <label for="dp-p-form" class="petit">Formulaire</label><input id="dp-p-form" maxlength="300" placeholder="https://…">
          <label for="dp-p-regl" class="petit">Règlement</label><input id="dp-p-regl" maxlength="300" placeholder="https://…">
        </div>
        <div class="petit" id="dp-p-trouve" style="margin-top:4px"></div>
        <div class="ligne"><button class="primaire" onclick="dpAjoute()">Ajouter le diplôme</button><button onclick="g('dp-prop').hidden = true">Annuler</button></div>
      </div>
      <details id="dp-ocr-d" hidden><summary class="petit">Texte lu</summary><pre id="dp-ocr"></pre></details>
    </div>
    <div class="carte">
      <div class="t">Dernières alertes</div>
      <div class="petit" id="dp-annonces"></div>
    </div>
  </div>
  <div>
    <div class="carte">
      <div class="t">Mes diplômes <span class="petit" id="dp-resume"></span></div>
      <div id="dp-liste"></div>
      <input type="file" id="dp-cert-f" accept="image/*" hidden onchange="dpCertificat(this)">
    </div>
  </div>
</div>
  </div>
"""

    const val DIALOGUE = """
<dialog id="dp-dlg" class="dlg-mt" aria-labelledby="dp-dlg-t">
  <div class="dlg-tete"><b id="dp-dlg-t">Préparer la demande</b><button class="discret" onclick="g('dp-dlg').close()" aria-label="Fermer">✕</button></div>
  <div id="dp-dlg-nom" style="font-weight:600"></div>
  <div class="petit" id="dp-dlg-lib"></div>
  <div class="dp-champs" id="dp-dlg-champs"></div>
  <div id="dp-dlg-imgs"></div>
  <details><summary class="petit">Tout le texte</summary><pre id="dp-dlg-texte"></pre></details>
  <div class="petit" id="dp-dlg-conseil" style="margin:4px 0"></div>
  <div class="petit" style="margin:6px 0"><b>Rien n'est envoyé :</b> SatMe prépare, tu remplis le formulaire de l'émetteur toi-même. Le remplissage automatique des formulaires d'autres sites viendra plus tard, et seulement avec ton accord explicite.</div>
  <div class="ligne"><button class="primaire" onclick="dpCopieTout()">Copier tout</button><a id="dp-dlg-form" class="bouton" target="_blank" rel="noopener noreferrer">Ouvrir le formulaire ↗</a>
    <a id="dp-dlg-pack" class="bouton" download>Télécharger le pack</a><button id="dp-dlg-dem" onclick="dpSuiviDlg()">Marquer demandé</button>
    <span class="petit" id="dp-dlg-msg" aria-live="polite"></span></div>
</dialog>
"""

    const val JS = """
// ------------------------------------------------------------------ Agenda › Diplômes (DiplomesJs)
let DP = null, dpVu = '', dpNVu = 0, dpNonLues = 0, dpCertId = '', dpDlg = null, dpProp = null;
try { dpNVu = +(localStorage.getItem('dp-vu') || 0); } catch (x) {}
const DP_GENRE = { IMAGE: 'image', GRILLES: 'locators', MES_GRILLES: 'locators activés', DXCC: 'entités', ETATS: 'États', OSCAR: 'États / entités / zones', CONTACTS: 'contacts', STATIONS: 'stations', LIBRE: '' };
async function dpCharge() {
  try { DP = await (await fetch('api/diplomes')).json(); } catch (x) { DP = null; }
  // A server without awards (the phone's page): no tab.
  const nav = document.querySelector('#v-agenda .st-nav button[data-p="ag-p-dip"]');
  if (nav) nav.hidden = !!(DP && DP.absent);
  if (!DP || !DP.ok) return;
  // The notices not seen yet: a toast each (three at most), the tab's count.
  const nouv = DP.annonces.filter(a => a.n > dpNVu);
  nouv.slice(-3).forEach(a => toast((a.sorte === 'echeance' ? '⏳ ' : '🏅 ') + a.texte));
  if (nouv.length) { dpNVu = Math.max.apply(null, nouv.map(a => a.n)); try { localStorage.setItem('dp-vu', dpNVu); } catch (x) {} dpNonLues += nouv.length; }
  if (ouverte('agenda') && g('ag-p-dip') && !g('ag-p-dip').hidden) dpNonLues = 0;
  g('dp-nb').hidden = !dpNonLues; txt('dp-nb', String(dpNonLues));
  if (ouverte('agenda')) dpDessine();
}
function dpLues() { dpNonLues = 0; g('dp-nb').hidden = true; }
const dpJour = ms => new Date(ms).toLocaleDateString(undefined, { day: '2-digit', month: '2-digit', year: 'numeric' });
const dpLocal = ms => ms > 0 ? new Date(ms - new Date(ms).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : '';
const dpMs = id => { const x = g(id).value; return x ? new Date(x).getTime() : 0; };
function dpReglages() {
  const r = DP.reglages, i = DP.identite;
  [['dp-actif', r.actif], ['dp-sources-on', r.sources], ['dp-n-obtenable', r.obtenable], ['dp-n-echeance', r.echeance], ['dp-n-nouveau', r.nouveau]]
    .forEach(([k, b]) => { if (document.activeElement !== g(k)) g(k).checked = b; });
  if (document.activeElement !== g('dp-jours')) g('dp-jours').value = r.jours;
  txt('dp-id-ind', i.indicatif || '— (Réglages › Station)');
  txt('dp-id-loc', i.locator || '—');
  if (document.activeElement !== g('dp-nom') && !g('dp-nom').dataset.touche) { g('dp-nom').value = i.nom; g('dp-nom').placeholder = i.nomPlanche ? i.nomPlanche + ' (planche SSTV)' : 'Prénom Nom (sur le diplôme)'; }
  if (document.activeElement !== g('dp-courriel') && !g('dp-courriel').dataset.touche) g('dp-courriel').value = i.courriel;
  g('dp-sources').innerHTML = DP.sources.map(s => '<span><a href="' + escA(s.lien) + '" target="_blank" rel="noopener noreferrer">' + esc(s.nom) + '</a> <span class="petit">' +
    (!r.sources ? 'coupée' : s.erreur ? '<span class="err" title="' + escA(s.erreur) + '">⚠ ' + esc(s.erreur.slice(0, 40)) + '</span>' + (s.succes ? ' · lu le ' + jourHeure(s.succes) : '') :
      s.succes ? 'lu le ' + jourHeure(s.succes) + (s.n ? ' · ' + s.n + ' diplôme' + (s.n > 1 ? 's' : '') : '') : 'pas encore lu') + '</span></span>').join('') ||
    (DP.demo ? '<span class="petit">Démonstration : diplômes fictifs seulement, rien n’est lu en ligne.</span>' : '');
}
const DP_SECTIONS = [
  ['À demander', ['OBTENABLE'], true], ['Événements en cours et à venir', ['PENDANT', 'A_VENIR'], true], ['En cours', ['EN_COURS', 'A_VERIFIER'], true],
  ['Demandés et reçus', ['DEMANDE', 'RECU'], true], ['Passés', ['EXPIRE', 'MANQUE'], false], ['Masqués', ['MASQUE'], false]];
function dpDessine() {
  if (!DP || !DP.ok) return;
  dpReglages();
  g('dp-annonces').innerHTML = DP.annonces.slice().reverse().slice(0, 10).map(a => jourHeure(a.quand) + ' — ' + esc(a.texte)).join('<br>') || 'aucune depuis le démarrage';
  const cle = JSON.stringify([DP.diplomes, DP.reglages.actif, utc()]);
  if (cle === dpVu) return;
  dpVu = cle;
  const l = DP.diplomes;
  const nOb = l.filter(d => d.statut === 'OBTENABLE').length;
  txt('dp-resume', !DP.reglages.actif ? 'coupé' : nOb + ' à demander · ' + l.length + ' suivis');
  if (!DP.reglages.actif) { g('dp-liste').innerHTML = '<div class="petit">Coupé : aucun diplôme n’est cherché.</div>'; return; }
  // Within a section: the nearest deadline first, then the nearest to its goal.
  const ordre = (a, b) => ((a.limite || 9e15) - (b.limite || 9e15)) || ((b.compte / b.objectif) - (a.compte / a.objectif));
  g('dp-liste').innerHTML = DP_SECTIONS.map(([titre, st, ouvert]) => {
    const s = l.filter(d => st.indexOf(d.statut) >= 0).sort(ordre);
    if (!s.length) return '';
    const corps = s.map(dpCarte).join('');
    return ouvert ? '<div class="dp-sec">' + esc(titre) + ' · ' + s.length + '</div>' + corps
      : '<details><summary class="dp-sec">' + esc(titre) + ' · ' + s.length + '</summary>' + corps + '</details>';
  }).join('') || '<div class="petit">Aucun diplôme pour l’instant.</div>';
}
function dpCarte(d) {
  const urgent = d.statut === 'OBTENABLE' && d.limite > 0 && d.limite - DP.maintenant < DP.reglages.jours * 86400000;
  const lien = (u, t) => u ? '<a href="' + escA(u) + '" target="_blank" rel="noopener noreferrer">' + esc(t) + ' ↗</a>' : '';
  let h = '<div class="dp-d dp-' + d.statut + (urgent ? ' dp-urgent' : '') + '" id="dp-' + escA(d.id) + '"><div class="dp-t"><b>' + esc(d.nom) + '</b>' +
    '<span class="pastille dp-st-' + d.statut + '">' + esc(d.statutLibelle) + '</span>' + (d.fictif ? '<span class="pastille off">fictif</span>' : '') + '</div>' +
    '<div class="petit">' + esc(d.emetteur) + (d.source ? ' · trouvé : ' + (d.sourceLien ? lien(d.sourceLien, d.source) : esc(d.source)) : '') + '</div>' +
    '<div class="dp-l">' + esc(d.libelle) + '</div>';
  if (d.genre === 'IMAGE') {
    if (d.de) h += '<div class="petit">Événement : du ' + dpJour(d.de) + ' au ' + dpJour(d.a) + (d.limite ? ' · <b>à demander avant le ' + dpJour(d.limite) + ' ' + hm(d.limite) + '</b>' : '') + '</div>';
    if (d.satNom || d.freqHz) h += '<div class="petit">' + esc(d.satNom || 'tout satellite') + (d.freqHz ? ' · <span class="ac-f">' + apFreq(d.freqHz) + ' MHz</span>' : '') + (d.mode ? ' · ' + esc(d.mode) : '') + '</div>';
    if (d.images.length) h += '<div class="dp-img">' + d.images.map(i => '<a href="sstv/' + enc(i.nom) + '" target="_blank" title="' + escA(i.nom + ' · ' + jourHeure(i.quand) + (i.note >= 0 ? ' · note ' + i.note + '/100' : '') + (i.complete ? '' : ' · partielle')) + '">' +
      '<img src="sstv/' + enc(i.nom) + '" loading="lazy" alt=""><span>' + (i.note >= 0 ? i.note : jourHeure(i.quand)) + '</span></a>').join('') + '</div>' +
      (d.nImages > d.images.length ? '<div class="petit">' + d.nImages + ' images reçues pendant l’événement (les meilleures d’abord).</div>' : '');
  } else if (d.genre !== 'LIBRE') {
    const pc = Math.min(100, Math.round(100 * d.compte / d.objectif));
    h += '<div class="dp-barre" title="' + d.compte + ' / ' + d.objectif + '"><i style="width:' + pc + '%"></i></div>';
    if (d.elements.length) h += '<details><summary class="petit">' + d.elements.length + ' ' + esc(DP_GENRE[d.genre] || '') + ' : la liste</summary><div class="dp-elems">' + d.elements.map(esc).join(' ') + '</div></details>';
    h += '<div class="petit">Contacts notés dans le carnet, pas les confirmations : l’émetteur vérifie les QSL / LoTW.</div>';
  }
  if (d.remarque) h += '<div class="petit ambre">' + esc(d.remarque) + '</div>';
  h += '<details><summary class="petit">Conditions' + (d.frais ? ' et frais' : '') + '</summary><div class="petit">' + esc(d.conditions) + (d.notes ? '<br>' + esc(d.notes) : '') +
    (d.frais ? '<br>Frais : ' + esc(d.frais) : '') + (d.verifie ? '<br>Règlement lu le ' + esc(d.verifie) + ' : le revérifier avant de demander.' : '') + '</div></details>';
  h += '<div class="petit">' + [lien(d.reglement, 'Règlement'), lien(d.formulaire, 'Formulaire'), d.courrielDemande ? 'demande par courriel : ' + esc(d.courrielDemande) : ''].filter(x => x).join(' · ') + '</div>';
  const b = (t, f, ti) => '<button data-id="' + escA(d.id) + '" onclick="' + f + '"' + (ti ? ' title="' + escA(ti) + '"' : '') + '>' + t + '</button>';
  const s = d.suivi.etat;
  let a = '';
  if (['OBTENABLE', 'DEMANDE', 'EN_COURS', 'EXPIRE', 'PENDANT'].indexOf(d.statut) >= 0) a += b(d.statut === 'OBTENABLE' ? '<b>Préparer la demande</b>' : 'Préparer la demande', 'dpPrepare(this.dataset.id)', 'Tout ce que le formulaire demande, à copier (rien n’est envoyé)');
  if (s !== 'DEMANDE' && s !== 'RECU') a += b('Marquer demandé', "dpSuivi(this.dataset.id, 'DEMANDE')");
  if (s !== 'RECU') a += b('Marquer reçu', "dpSuivi(this.dataset.id, 'RECU')");
  a += b(d.suivi.certificat ? 'Remplacer le diplôme reçu…' : 'Joindre le diplôme reçu…', 'dpChoisitCertificat(this.dataset.id)', 'L’image du diplôme reçu, gardée sur ce PC');
  if (d.suivi.certificat) a += '<a class="bouton" href="api/diplomes/certificat?id=' + enc(d.id) + '" target="_blank">Voir le diplôme</a>';
  if (d.suivi.certificat && DP.site) a += b(d.suivi.envoyeSite ? 'Renvoyer au site' : 'Envoyer le diplôme reçu', 'dpEnvoieSite(this.dataset.id)',
    (d.suivi.envoyeSite ? 'Envoyé le ' + dpJour(d.suivi.envoyeSite) + '. ' : '') + 'Son image vers ton site (Publication web), seulement sur ce clic');
  if (s !== 'AUCUN') a += b(s === 'MASQUE' ? 'Ne plus masquer' : 'Annuler (' + esc(d.statutLibelle) + ')', "dpSuivi(this.dataset.id, 'AUCUN')");
  else a += b('Masquer', "dpSuivi(this.dataset.id, 'MASQUE')", 'Ne plus le montrer parmi les autres');
  if (d.ajoute) a += b('Retirer', 'dpRetire(this.dataset.id)', 'Retirer ce diplôme ajouté à la main');
  return h + '<div class="dp-act">' + a + '</div></div>';
}
async function dpReglage(k, on) { await post('api/diplomes/reglage?k=' + k + '&on=' + (on ? 1 : 0)); dpVu = ''; dpCharge(); }
async function dpActualise() { await post('api/diplomes/rafraichit'); annonce('Sources des diplômes demandées'); setTimeout(dpCharge, 10000); }
async function dpSuivi(id, etat) { await post('api/diplomes/suivi?id=' + enc(id) + '&etat=' + etat); dpVu = ''; dpCharge(); }
async function dpRetire(id) { if (!confirm('Retirer ce diplôme ?')) return; await post('api/diplomes/retire?id=' + enc(id)); dpVu = ''; dpCharge(); }
['dp-nom', 'dp-courriel'].forEach(i => { const e = g(i); if (e) e.addEventListener('input', () => { e.dataset.touche = '1'; }); });
async function dpIdentite() {
  const j = await post('api/diplomes/identite?nom=' + enc(g('dp-nom').value.trim()) + '&courriel=' + enc(g('dp-courriel').value.trim()));
  if (j && j.ok) { delete g('dp-nom').dataset.touche; delete g('dp-courriel').dataset.touche; txt('dp-id-etat', 'Enregistré.'); dpCharge(); }
}
async function dpEnvoieSite(id) {
  let j = null; try { j = await (await fetch('api/diplomes/envoie-site?id=' + enc(id), { method: 'POST' })).json(); } catch (x) {}
  toast(j && j.ok ? '🏅 Diplôme envoyé au site' : 'Non envoyé' + (j && j.erreur ? ' : ' + j.erreur : ''));
  dpVu = ''; dpCharge();
}
function dpChoisitCertificat(id) { dpCertId = id; g('dp-cert-f').click(); }
async function dpCertificat(inp) {
  const f = inp.files && inp.files[0]; inp.value = ''; if (!f || !dpCertId) return;
  if (f.size > 10 * 1024 * 1024) { toast('Image trop grande (10 Mo au plus).'); return; }
  let j = null; try { j = await (await fetch('api/diplomes/certificat?id=' + enc(dpCertId), { method: 'POST', body: f })).json(); } catch (x) {}
  toast(j && j.ok ? '🏅 Diplôme reçu gardé' : 'Image refusée' + (j && j.erreur ? ' : ' + j.erreur : ''));
  dpVu = ''; dpCharge();
}
// « Ajouter depuis une affiche… »: the award read, shown in the form, added once checked.
async function dpAfficheFichier(inp) {
  const f = inp.files && inp.files[0]; inp.value = ''; if (!f) return;
  if (f.size > 10 * 1024 * 1024) { txt('dp-imp-etat', 'Image trop grande (10 Mo au plus).'); return; }
  txt('dp-imp-etat', 'Lecture de « ' + f.name + ' » par tesseract…');
  try { dpPropose(await (await fetch('api/diplomes/affiche', { method: 'POST', body: f })).json()); } catch (x) { txt('dp-imp-etat', 'Lecture impossible.'); }
}
async function dpTexte() {
  const t = g('dp-texte').value.trim(); if (!t) { txt('dp-imp-etat', 'Colle d’abord le texte de l’annonce.'); return; }
  try { dpPropose(await (await fetch('api/diplomes/texte', { method: 'POST', body: t })).json()); } catch (x) { txt('dp-imp-etat', 'Lecture impossible.'); }
}
function dpPropose(j) {
  if (!j || !j.ok) { txt('dp-imp-etat', (j && j.erreur) || 'Lecture impossible.'); return; }
  dpProp = j.proposition;
  g('dp-ocr-d').hidden = !j.texte; txt('dp-ocr', j.texte || '');
  const p = dpProp;
  g('dp-p-nom').value = p.nom; g('dp-p-em').value = p.emetteur; g('dp-p-sat').value = p.satNom;
  g('dp-p-de').value = dpLocal(p.de); g('dp-p-a').value = dpLocal(p.a); g('dp-p-lim').value = dpLocal(p.limite);
  g('dp-p-form').value = p.formulaire; g('dp-p-regl').value = p.reglement;
  g('dp-prop').hidden = false;
  txt('dp-imp-etat', j.estDiplome ? 'Diplôme trouvé : à vérifier puis « Ajouter ».' : 'Aucun mot « diplôme / award / certificat » lu : vérifie que c’est bien un diplôme.');
  txt('dp-p-trouve', j.trouve.length ? 'Lu : ' + j.trouve.join(', ') + '.' : 'Rien de sûr : remplis à la main.');
}
async function dpAjoute() {
  if (!dpProp) return;
  const p = Object.assign({}, dpProp, { nom: g('dp-p-nom').value.trim() || 'Diplôme', emetteur: g('dp-p-em').value.trim(), satNom: g('dp-p-sat').value.trim(),
    de: dpMs('dp-p-de'), a: dpMs('dp-p-a'), limite: dpMs('dp-p-lim'), formulaire: g('dp-p-form').value.trim(), reglement: g('dp-p-regl').value.trim() });
  if (p.satNom !== dpProp.satNom) p.catnum = 0;
  if (!p.de || !p.a || p.a < p.de) { txt('dp-imp-etat', 'Il faut le début et la fin de l’événement.'); return; }
  let j = null; try { j = await (await fetch('api/diplomes/ajoute', { method: 'POST', body: JSON.stringify(p) })).json(); } catch (x) {}
  if (!j || !j.ok) { txt('dp-imp-etat', 'Refusé' + (j && j.erreur ? ' : ' + j.erreur : '')); return; }
  g('dp-prop').hidden = true; g('dp-texte').value = ''; dpProp = null;
  txt('dp-imp-etat', 'Ajouté.'); dpVu = ''; await dpCharge();
  const c = g('dp-' + j.id); if (c) c.scrollIntoView({ block: 'center' });
}
// « Préparer la demande »: what the form asks, to copy; the pictures to choose; the pack (originals + QSL) to download.
async function dpPrepare(id, noms) {
  let j = null; try { j = await (await fetch('api/diplomes/demande?id=' + enc(id) + (noms ? '&noms=' + enc(noms.join(',')) : ''))).json(); } catch (x) {}
  if (!j || !j.ok) { toast('Demande impossible' + (j && j.erreur ? ' : ' + j.erreur : '')); return; }
  dpDlg = j;
  const d = j.diplome;
  txt('dp-dlg-nom', d.nom + ' — ' + d.emetteur); txt('dp-dlg-lib', j.libelle); txt('dp-dlg-msg', '');
  g('dp-dlg-champs').innerHTML = j.champs.map((c, k) => '<span class="petit lib">' + esc(c.libelle) + '</span>' +
    (c.manque ? '<span class="v manque">à remplir — ' + esc(c.aide) + '</span><span></span>' : '<span class="v">' + esc(c.valeur) + '</span><button onclick="dpCopieChamp(' + k + ')">Copier</button>')).join('');
  let h = '';
  if (d.genre === 'IMAGE') {
    h += '<div class="petit">Images à joindre (les meilleures cochées) :</div><div class="dp-img">' + j.toutes.map(i => {
      const on = j.images.some(x => x.nom === i.nom);
      return '<label><img src="sstv/' + enc(i.nom) + '" loading="lazy" alt=""><input type="checkbox" data-nom="' + escA(i.nom) + '"' + (on ? ' checked' : '') + ' onchange="dpChoixImages()"> ' + (i.note >= 0 ? i.note + '/100' : '') + '</label>';
    }).join('') + '</div>';
    h += j.images.map((i, k) => '<div class="petit" style="margin-top:4px"><b>' + esc(i.nom) + '</b> · ' + esc(i.date) + ' ' + esc(i.heure) + (i.frequence ? ' · ' + esc(i.frequence) : '') + (i.mode ? ' · ' + esc(i.mode) : '') +
      (i.passage ? ' · passage ' + esc(i.passage) : '') + ' <button onclick="dpCopieImage(' + k + ')">Copier</button></div>').join('');
    if (!j.toutes.length) h += '<div class="petit ambre">Aucune image de l’événement dans la galerie.</div>';
  } else if (j.elements.length) h += '<div class="petit">' + j.compte + ' ' + esc(DP_GENRE[d.genre] || '') + ' :</div><div class="dp-elems">' + j.elements.map(esc).join(' ') + '</div>';
  g('dp-dlg-imgs').innerHTML = h;
  txt('dp-dlg-texte', j.texte);
  txt('dp-dlg-conseil', j.conseil || '');
  const form = d.formulaire || d.reglement;
  g('dp-dlg-form').hidden = !form; if (form) g('dp-dlg-form').href = form;
  txt('dp-dlg-form', d.formulaire ? 'Ouvrir le formulaire ↗' : 'Ouvrir le règlement ↗');
  g('dp-dlg-pack').hidden = !(j.pack && j.images.length); if (j.pack) g('dp-dlg-pack').href = j.pack;
  g('dp-dlg-dem').hidden = j.statut === 'DEMANDE' || j.statut === 'RECU';
  if (!g('dp-dlg').open) g('dp-dlg').showModal();
}
function dpChoixImages() {
  const noms = Array.from(document.querySelectorAll('#dp-dlg-imgs input[data-nom]')).filter(e => e.checked).map(e => e.dataset.nom);
  if (dpDlg) dpPrepare(dpDlg.diplome.id, noms.length ? noms : null);
}
async function dpCopie(t) { txt('dp-dlg-msg', await ckCopieTexte(t) ? 'copié' : 'copie refusée par le navigateur'); }
function dpCopieChamp(k) { if (dpDlg) dpCopie(dpDlg.champs[k].valeur); }
function dpCopieImage(k) { if (!dpDlg) return; const i = dpDlg.images[k]; dpCopie([i.date, i.heure, i.sat, i.frequence, i.mode].filter(x => x).join(' · ')); }
function dpCopieTout() { if (dpDlg) dpCopie(dpDlg.texte); }
async function dpSuiviDlg() { if (!dpDlg) return; await dpSuivi(dpDlg.diplome.id, 'DEMANDE'); g('dp-dlg-dem').hidden = true; txt('dp-dlg-msg', 'marqué demandé'); }
dpCharge();
setInterval(dpCharge, 30000);
"""
}
