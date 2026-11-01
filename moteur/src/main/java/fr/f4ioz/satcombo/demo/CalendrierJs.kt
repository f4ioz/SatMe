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
 * « Agenda › Calendrier »: a month, a week or a day (a list on a phone) of
 * what is planned — the appointments, the SSTV/SSDV campaigns as coloured
 * spans, every pass of the campaigns and of the followed satellites (AOS–LOS,
 * highest elevation), the activities announced online; the past greyed with
 * what became of it. A pass clicked: its details, its campaign, « Annuler ce
 * passage ». Data from `/api/calendrier?de=&a=`. Put into [PagePc.HTML]; no
 * Kotlin template here (no dollar sign at all in these strings).
 */
object CalendrierJs {

    const val CSS = """
  /* ---------------------------------------------------------------- Agenda › Calendrier (CalendrierJs) */
  .cal { display:flex; flex-direction:column; gap:8px; min-height:0; }
  .cal-barre { display:flex; flex-wrap:wrap; gap:6px 10px; align-items:center; flex:none; }
  .cal-barre .cal-titre { font-size:var(--fs-l); font-weight:700; min-width:170px; text-transform:capitalize; }
  .cal-vues { display:inline-flex; gap:2px; }
  .cal-vues button.actif { border-color:var(--rx); color:var(--rx); background:rgba(63,224,200,.08); }
  .cal-filtres { display:inline-flex; flex-wrap:wrap; gap:4px 10px; font-size:var(--fs-s); color:var(--texte-2); }
  .cal-corps { display:grid; grid-template-columns:minmax(0,1fr) 330px; gap:10px; min-height:0; flex:1; }
  .cal-grille { min-height:0; display:flex; flex-direction:column; border:1px solid var(--ligne); border-radius:var(--r2); background:var(--s1); overflow:hidden; }
  .cal-cote { min-height:0; overflow:auto; margin:0; }
  .cal-js { display:grid; grid-template-columns:repeat(7, minmax(0,1fr)); flex:none; border-bottom:1px solid var(--ligne); }
  .cal-js div { padding:4px 6px; font-size:var(--fs-xs); font-weight:700; letter-spacing:.06em; text-transform:uppercase; color:var(--texte-3); }
  .cal-mois { display:grid; grid-template-columns:repeat(7, minmax(0,1fr)); grid-template-rows:repeat(6, minmax(0,1fr)); flex:1; min-height:0; }
  .cal-j { border-right:1px solid var(--ligne); border-bottom:1px solid var(--ligne); padding:2px 3px 3px; min-height:0; overflow:auto; cursor:pointer; display:flex; flex-direction:column; gap:2px; }
  .cal-j:nth-child(7n) { border-right:none; }
  .cal-j.autre { background:var(--s0); }
  .cal-j.autre .cal-n { color:var(--texte-3); }
  .cal-j.passe { background:rgba(0,0,0,.12); }
  .cal-j.choisi { box-shadow:inset 0 0 0 2px var(--info); }
  .cal-n { font-size:var(--fs-s); font-weight:700; color:var(--texte-2); display:flex; justify-content:space-between; position:sticky; top:0; background:inherit; }
  .cal-j.auj .cal-n b { background:var(--rx); color:#06231E; border-radius:9px; padding:0 6px; }
  .cal-span { font-size:10.5px; line-height:15px; height:15px; padding:0 5px; border-radius:3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;
    background:color-mix(in srgb, var(--c) 26%, transparent); border-left:3px solid var(--c); color:var(--texte); cursor:pointer; flex:none; }
  .cal-span.act { background:transparent; border:1px dashed var(--c); border-left-width:3px; color:var(--texte-2); }
  .cal-span.rdv { --c:var(--info); }
  .cal-span.fini, .cal-span.off { opacity:.5; }
  .cal-span.supp { text-decoration:line-through; }
  .cal-p { font-size:10.5px; line-height:15px; padding:0 4px; border-radius:3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; cursor:pointer; flex:none;
    font-family:var(--mono); color:var(--texte); border:1px solid color-mix(in srgb, var(--c) 70%, transparent); background:color-mix(in srgb, var(--c) 16%, transparent); }
  .cal-p.fav { background:transparent; border-style:dotted; color:var(--texte-2); }
  .cal-p.passe { opacity:.5; }
  .cal-p.st-ANNULE, .cal-p.st-CHEVAUCHE, .cal-p.st-ECARTE { text-decoration:line-through; opacity:.6; }
  .cal-p.st-MANQUE { opacity:.45; border-style:dashed; }
  .cal-p.st-EN_COURS { box-shadow:0 0 0 1px var(--ambre); }
  .cal-p.susp { opacity:.55; border-style:dashed; }
  .cal-p.choisi, .cal-span.choisi { outline:2px solid var(--info); outline-offset:0; }
  .cal-p i { font-style:normal; }
  /* The week: the days' spans above, the hours below (scrolling inside). */
  .cal-sem { display:flex; flex-direction:column; flex:1; min-height:0; }
  .cal-sem-t { display:grid; grid-template-columns:44px repeat(7, minmax(0,1fr)); flex:none; border-bottom:1px solid var(--ligne); }
  .cal-sem-t > div { padding:3px 4px; min-width:0; display:flex; flex-direction:column; gap:2px; max-height:120px; overflow:auto; }
  .cal-sem-t .cal-n { cursor:pointer; }
  .cal-sem-c { flex:1; min-height:0; overflow:auto; }
  .cal-sem-h { display:grid; grid-template-columns:44px repeat(7, minmax(0,1fr)); position:relative; height:calc(24 * var(--h-cal, 30px)); }
  .cal-heures div { height:var(--h-cal, 30px); font-size:10px; color:var(--texte-3); text-align:right; padding-right:4px; box-sizing:border-box; border-top:1px solid var(--ligne); }
  .cal-col { position:relative; border-left:1px solid var(--ligne);
    background:repeating-linear-gradient(to bottom, var(--ligne) 0 1px, transparent 1px var(--h-cal, 30px)); }
  .cal-col.auj { background-color:rgba(63,224,200,.04); }
  .cal-col .cal-p { position:absolute; left:2px; right:2px; min-height:15px; white-space:normal; overflow:hidden; }
  .cal-now { position:absolute; left:0; right:0; height:0; border-top:2px solid var(--tx); z-index:2; pointer-events:none; }
  /* The day (and the phone): a list. */
  .cal-liste { flex:1; min-height:0; overflow:auto; padding:6px 8px; display:flex; flex-direction:column; gap:4px; }
  .cal-liste .cal-p, .cal-liste .cal-span { font-size:var(--fs-s); line-height:20px; height:auto; white-space:normal; }
  .cal-liste h4 { margin:6px 0 2px; font-size:var(--fs-s); color:var(--texte-2); text-transform:capitalize; }
  .cal-cote dl { display:grid; grid-template-columns:max-content minmax(0,1fr); gap:3px 10px; margin:6px 0; font-size:var(--fs-s); }
  .cal-cote dt { color:var(--texte-2); }
  .cal-cote dd { margin:0; }
  .cal-cote .cal-act { display:flex; flex-wrap:wrap; gap:4px; margin-top:8px; }
  .cal-cote .cal-pt { display:flex; align-items:center; gap:6px; }
  .cal-cote .cal-pt i { width:10px; height:10px; border-radius:50%; background:var(--c); flex:none; }
  .cal-leg { display:flex; flex-wrap:wrap; gap:4px 10px; font-size:var(--fs-xs); color:var(--texte-2); margin-top:8px; }
  .cal-leg span { display:inline-flex; align-items:center; gap:4px; }
  .cal-leg i { width:9px; height:9px; border-radius:2px; background:var(--c); }
  @media (min-width:1101px) and (min-height:600px) {
    #ag-p-cal:not([hidden]) { display:flex; flex-direction:column; }
    #ag-p-cal .cal { flex:1; }
  }
  @media (max-width:1100px) { .cal-corps { grid-template-columns:minmax(0,1fr); } .cal-grille { height:72vh; } }
  @media (max-width:700px) { .cal-vues button[data-v="mois"], .cal-vues button[data-v="semaine"] { display:none; } .cal-grille { height:auto; max-height:70vh; } }
"""

    const val HTML = """
  <div class="st-pan" id="ag-p-cal">
<div class="cal" id="cal">
  <div class="cal-barre">
    <button onclick="calVa(-1)" title="Avant" aria-label="Avant">‹</button><button onclick="calAujourdhui()">Aujourd’hui</button><button onclick="calVa(1)" title="Après" aria-label="Après">›</button>
    <span class="cal-titre" id="cal-titre"></span>
    <span class="cal-vues" id="cal-vues"><button data-v="mois" onclick="calVue('mois')">Mois</button><button data-v="semaine" onclick="calVue('semaine')">Semaine</button><button data-v="jour" onclick="calVue('jour')">Jour</button></span>
    <span class="cal-filtres">
      <label><input type="checkbox" id="cal-f-rdv" checked onchange="calDessine()"> rendez-vous</label>
      <label><input type="checkbox" id="cal-f-camp" checked onchange="calDessine()"> campagnes</label>
      <label><input type="checkbox" id="cal-f-fav" checked onchange="calDessine()"> passages des favoris</label>
      <label><input type="checkbox" id="cal-f-act" checked onchange="calDessine()"> activités annoncées</label>
    </span>
    <span class="petit" id="cal-etat"></span>
  </div>
  <div class="cal-corps">
    <div class="cal-grille" id="cal-grille"></div>
    <div class="carte cal-cote" id="cal-cote"><div class="t">Détails</div><div class="petit">Un jour ou un passage : ses détails ici.</div></div>
  </div>
</div>
  </div>
"""

    const val JS = """
// ------------------------------------------------------------------ Agenda › Calendrier: month, week, day (read from api/calendrier)
const CLD = { vue: 'mois', ref: null, d: null, sel: null, jour: null, cle: '', charge: 0 };
try { const x = localStorage.getItem('cal-vue'); if (x === 'mois' || x === 'semaine' || x === 'jour') CLD.vue = x; } catch (e) {}
const CAL_COUL = ['#3FE0C8', '#FFB454', '#B07CFF', '#6F8CFF', '#FF7AA8', '#5BD68A', '#FFD166', '#4FC3F7', '#FF8A65', '#C5E1A5', '#E57373', '#80CBC4'];
const CAL_ST = { PREVU: 'prévu', EN_COURS: 'en cours', FAIT: 'reçu', ECARTE: 'écarté', ARRETE: 'arrêté', INTERROMPU: 'interrompu', CHEVAUCHE: 'perdu (autre passage)', ANNULE: 'annulé', MANQUE: 'manqué' };
const CAL_ICO = { FAIT: '✓', MANQUE: '✗', ECARTE: '⊘', ARRETE: '■', INTERROMPU: '⚠', ANNULE: '⊘', CHEVAUCHE: '⊘', EN_COURS: '●' };
/** A satellite's colour, the same everywhere (its NORAD number, else its name). */
function calCouleur(n, nom) {
  let h = Number(n) || 0;
  if (!h) for (const ch of String(nom || '')) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return CAL_COUL[h % CAL_COUL.length];
}
const calTel = () => matchMedia('(max-width:700px)').matches;
/** Midnight of [d]'s day (the page's clock: this browser's, or UTC when ticked). */
function calMinuit(d) { const x = new Date(d); return utc() ? new Date(Date.UTC(x.getUTCFullYear(), x.getUTCMonth(), x.getUTCDate())) : new Date(x.getFullYear(), x.getMonth(), x.getDate()); }
/** The day [n] days after [d] (00:00 that day: 23 or 25 h when the clocks change). */
function calPlus(d, n) { return utc() ? new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + n)) : new Date(d.getFullYear(), d.getMonth(), d.getDate() + n); }
const calJ = d => utc() ? d.getUTCDate() : d.getDate();
const calM = d => utc() ? d.getUTCMonth() : d.getMonth();
const calJs = d => ((utc() ? d.getUTCDay() : d.getDay()) + 6) % 7;
const CAL_JOURS = ['lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.'];
const CAL_MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
const calCle = d => calMinuit(d).getTime();
/** « jeu. 29/10 00:00 » on the page's clock. */
function calQuand(ms) { const d = new Date(ms); return CAL_JOURS[calJs(d)] + ' ' + String(calJ(d)).padStart(2, '0') + '/' + String(calM(d) + 1).padStart(2, '0') + ' ' + hm(ms); }
/** « heure de Paris », « UTC ». */
function calZone() {
  if (utc()) return 'UTC';
  let z = ''; try { z = Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) {}
  return z && z.indexOf('/') > 0 ? 'heure de ' + z.split('/').pop().replace(/_/g, ' ') : 'heure locale';
}
/** « du jeu. 29/10 00:00 au dim. 01/11 23:59 (heure de Paris) ». */
function calPeriode(de, a) { return 'du ' + calQuand(de) + ' au ' + calQuand(a) + ' (' + calZone() + ')'; }
/** The range the view shows: [start, end) and its days. */
function calPortee() {
  const r = calMinuit(CLD.ref || Date.now());
  let d0, n;
  if (CLD.vue === 'mois' && !calTel()) { const p = utc() ? new Date(Date.UTC(r.getUTCFullYear(), r.getUTCMonth(), 1)) : new Date(r.getFullYear(), r.getMonth(), 1); d0 = calPlus(p, -calJs(p)); n = 42; }
  else if (CLD.vue === 'semaine' && !calTel()) { d0 = calPlus(r, -calJs(r)); n = 7; }
  else { d0 = r; n = calTel() ? 3 : 1; }
  const jours = []; for (let i = 0; i < n; i++) jours.push(calPlus(d0, i));
  return { de: d0.getTime(), a: calPlus(d0, n).getTime(), jours: jours };
}
async function calCharge(force) {
  const p = calPortee(), cle = p.de + ':' + p.a;
  if (!force && cle === CLD.cle && Date.now() - CLD.charge < 60000) { calDessine(); return; }
  CLD.cle = cle; CLD.charge = Date.now();
  txt('cal-etat', 'lecture…');
  let j = null; try { j = await (await fetch('api/calendrier?de=' + p.de + '&a=' + p.a)).json(); } catch (e) {}
  if (CLD.cle !== cle) return;
  if (!j || !j.ok) { txt('cal-etat', (j && j.erreur) || 'calendrier indisponible'); return; }
  CLD.d = j; txt('cal-etat', '');
  calDessine();
}
function calVue(v) { CLD.vue = v; try { localStorage.setItem('cal-vue', v); } catch (e) {} calCharge(); }
function calVa(s) {
  const r = calMinuit(CLD.ref || Date.now());
  CLD.ref = CLD.vue === 'mois' && !calTel() ? (utc() ? new Date(Date.UTC(r.getUTCFullYear(), r.getUTCMonth() + s, 1)) : new Date(r.getFullYear(), r.getMonth() + s, 1))
    : calPlus(r, s * (CLD.vue === 'semaine' && !calTel() ? 7 : calTel() ? 3 : 1));
  calCharge();
}
function calAujourdhui() { CLD.ref = calMinuit(Date.now()); CLD.jour = CLD.ref.getTime(); calCharge(); }
/** Opened at [ms] (a campaign's start, a pass), [sel] chosen: from the campaign's editor, the lists. */
function calendrierOuvre(ms, sel) {
  vue('agenda');
  const b = document.querySelector('#v-agenda .st-nav button[data-p="ag-p-cal"]'); if (b) b.click();
  CLD.ref = calMinuit(ms || Date.now()); CLD.jour = CLD.ref.getTime(); CLD.sel = sel || null;
  calCharge(true);
}
/** The items kept by the filters. */
function calItems() {
  if (!CLD.d) return [];
  const f = id => g(id).checked;
  return CLD.d.items.filter(x => x.sorte === 'agenda' ? f('cal-f-rdv') : x.sorte === 'campagne' ? f('cal-f-camp') : x.sorte === 'activite' ? f('cal-f-act') :
    x.source === 'campagne' ? f('cal-f-camp') : f('cal-f-fav'));
}
const calDe = x => x.sorte === 'passage' ? x.aos : x.de;
const calA = x => x.sorte === 'passage' ? x.los : Math.max(x.a, x.de);
const calSur = (x, d0, d1) => calDe(x) < d1 && calA(x) >= d0;
function calIdent(x) { return x.sorte + ':' + (x.sorte === 'passage' ? x.catnum + ':' + x.aos + ':' + (x.campagne || x.source) : x.id); }
function calCoul(x) { return x.sorte === 'agenda' ? 'var(--info)' : calCouleur(x.catnum, x.sat); }
/** A pass's chip: « 23:25 RS18S 28° ». */
function calPuce(x, style) {
  const cl = 'cal-p' + (x.source !== 'campagne' ? ' fav' : '') + (x.passe ? ' passe' : '') + (x.statut ? ' st-' + x.statut : '') + (x.suspendue ? ' susp' : '') +
    (CLD.sel === calIdent(x) ? ' choisi' : '');
  const ico = x.statut && CAL_ICO[x.statut] ? '<i>' + CAL_ICO[x.statut] + '</i> ' : x.source === 'campagne' ? '<i>🤖</i> ' : '';
  const t = (x.nom ? x.nom + ' — ' : '') + x.sat + ' · ' + calQuand(x.aos) + ' → ' + hm(x.los) + ' · ' + x.el + '° max' + (x.statut ? ' · ' + (CAL_ST[x.statut] || x.statut) : '') +
    (x.images ? ' · ' + x.images + ' image' + (x.images > 1 ? 's' : '') : '') + (x.raison ? ' · ' + x.raison : '');
  return '<div class="' + cl + '" style="--c:' + calCoul(x) + (style || '') + '" title="' + escA(t) + '" data-k="' + escA(calIdent(x)) + '" onclick="calChoisit(event, this.dataset.k)">' +
    ico + hm(x.aos) + ' ' + esc(calCourt(x.sat)) + ' ' + x.el + '°' + (x.images ? ' · ' + x.images + '🖼' : '') + '</div>';
}
/** « SAKHACUBE-CHOLBON (RS18S) » → « RS18S »: the short name in a chip. */
function calCourt(s) { const m = /\(([^)]{2,12})\)\s*(?![\s\S])/.exec(s || ''); return m ? m[1] : String(s || '').slice(0, 14); }
/** A span (a campaign, an activity, an appointment): its words on its first day of the row only. */
function calBarre(x, premier) {
  const cl = 'cal-span' + (x.sorte === 'activite' ? ' act' : x.sorte === 'agenda' ? ' rdv' : '') + (x.terminee || x.passe ? ' fini' : '') + (x.sorte === 'campagne' && !x.active ? ' off' : '') +
    (x.campagneSupprimee ? ' supp' : '') + (CLD.sel === calIdent(x) ? ' choisi' : '');
  const ico = x.sorte === 'activite' ? '📡 ' : x.sorte === 'agenda' ? '📅 ' : '🤖 ';
  const t = (x.sorte === 'activite' ? 'Annoncé : ' : x.sorte === 'agenda' ? 'Rendez-vous : ' : 'Campagne : ') + x.titre + ' · ' + calPeriode(x.de, calA(x)) +
    (x.terminee ? ' · terminée' : '') + (x.campagneSupprimee ? ' · campagne supprimée' : '');
  return '<div class="' + cl + '" style="--c:' + calCoul(x) + '" title="' + escA(t) + '" data-k="' + escA(calIdent(x)) + '" onclick="calChoisit(event, this.dataset.k)">' +
    (premier ? ico + esc(x.sorte === 'agenda' && x.a <= x.de ? hm(x.de) + ' ' + x.titre : x.titre) : '&nbsp;') + '</div>';
}
function calTitre(p) {
  const r = calMinuit(CLD.ref || Date.now());
  if (CLD.vue === 'mois' && !calTel()) return CAL_MOIS[calM(r)] + ' ' + (utc() ? r.getUTCFullYear() : r.getFullYear());
  const a = p.jours[0], b = p.jours[p.jours.length - 1];
  return p.jours.length === 1 ? CAL_JOURS[calJs(a)] + ' ' + calJ(a) + ' ' + CAL_MOIS[calM(a)] : calJ(a) + ' ' + (calM(a) !== calM(b) ? CAL_MOIS[calM(a)] + ' ' : '') + '– ' + calJ(b) + ' ' + CAL_MOIS[calM(b)];
}
function calDessine() {
  const grille = g('cal-grille'); if (!grille) return;
  document.querySelectorAll('#cal-vues button').forEach(b => b.classList.toggle('actif', b.dataset.v === (calTel() ? 'jour' : CLD.vue)));
  const p = calPortee();
  txt('cal-titre', calTitre(p));
  if (!CLD.d) { grille.innerHTML = '<div class="petit" style="padding:10px">lecture…</div>'; return; }
  const l = calItems(), auj = calCle(Date.now()), now = Date.now();
  const spans = l.filter(x => x.sorte !== 'passage').sort((a, b) => a.de - b.de), pass = l.filter(x => x.sorte === 'passage').sort((a, b) => a.aos - b.aos);
  // Kept scrolled where it was (the week's hours).
  const defile = grille.querySelector('.cal-sem-c, .cal-liste'), haut = defile ? defile.scrollTop : null;
  if (CLD.vue === 'mois' && !calTel()) {
    let h = '<div class="cal-js">' + CAL_JOURS.map(j => '<div>' + j + '</div>').join('') + '</div><div class="cal-mois">';
    const mois = calM(calMinuit(CLD.ref || Date.now()));
    p.jours.forEach((d, i) => {
      const d0 = d.getTime(), d1 = calPlus(d, 1).getTime(), k = d0;
      const cl = 'cal-j' + (calM(d) !== mois ? ' autre' : '') + (k === auj ? ' auj' : '') + (d1 <= now ? ' passe' : '') + (CLD.jour === k ? ' choisi' : '');
      const sp = spans.filter(x => calSur(x, d0, d1) && (x.sorte !== 'agenda' || x.a > x.de)), ps = pass.filter(x => x.aos >= d0 && x.aos < d1);
      const rdv = spans.filter(x => x.sorte === 'agenda' && x.a <= x.de && x.de >= d0 && x.de < d1);
      h += '<div class="' + cl + '" data-j="' + k + '" onclick="calJour(+this.dataset.j)"><div class="cal-n"><b>' + calJ(d) + '</b>' + (ps.length ? '<span class="petit">' + ps.length + ' passage' + (ps.length > 1 ? 's' : '') + '</span>' : '') + '</div>' +
        sp.map(x => calBarre(x, calDe(x) >= d0 || i % 7 === 0)).join('') + rdv.map(x => calBarre(x, true)).join('') + ps.map(x => calPuce(x)).join('') + '</div>';
    });
    grille.innerHTML = h + '</div>';
  } else if (CLD.vue === 'semaine' && !calTel()) {
    const H = 30;
    let t = '<div class="cal-sem"><div class="cal-sem-t"><div></div>' + p.jours.map(d => {
      const d0 = d.getTime(), d1 = calPlus(d, 1).getTime();
      return '<div><div class="cal-n" data-j="' + d0 + '" onclick="calJour(+this.dataset.j)"><b>' + CAL_JOURS[calJs(d)] + ' ' + calJ(d) + '</b></div>' +
        spans.filter(x => calSur(x, d0, d1)).map(x => calBarre(x, true)).join('') + '</div>';
    }).join('') + '</div><div class="cal-sem-c"><div class="cal-sem-h" style="--h-cal:' + H + 'px"><div class="cal-heures">' +
      Array.from({ length: 24 }, (_, i) => '<div>' + String(i).padStart(2, '0') + ':00</div>').join('') + '</div>';
    p.jours.forEach(d => {
      const d0 = d.getTime(), d1 = calPlus(d, 1).getTime(), lg = (d1 - d0) / 3600000;
      const ps = pass.filter(x => x.aos < d1 && x.los >= d0);
      // Passes at the same time side by side.
      const voies = []; const place = ps.map(x => { let v = voies.findIndex(f => f <= x.aos); if (v < 0) { v = voies.length; voies.push(0); } voies[v] = x.los + 60000; return v; });
      const nv = Math.max(1, voies.length);
      t += '<div class="cal-col' + (d0 === auj ? ' auj' : '') + '" data-j="' + d0 + '" onclick="if (event.target === this) calJour(+this.dataset.j)">' + ps.map((x, i) => {
        const y = Math.max(0, (x.aos - d0) / 3600000) * H * 24 / lg, hh = Math.max(15, (Math.min(x.los, d1) - Math.max(x.aos, d0)) / 3600000 * H * 24 / lg);
        return calPuce(x, ';top:' + y.toFixed(1) + 'px;height:' + hh.toFixed(1) + 'px;left:calc(' + (place[i] * 100 / nv).toFixed(2) + '% + 2px);right:auto;width:calc(' + (100 / nv).toFixed(2) + '% - 4px)');
      }).join('') + (now >= d0 && now < d1 ? '<div class="cal-now" style="top:' + ((now - d0) / 3600000 * H * 24 / lg).toFixed(1) + 'px"></div>' : '') + '</div>';
    });
    grille.innerHTML = t + '</div></div></div>';
    const c = grille.querySelector('.cal-sem-c');
    if (haut != null) c.scrollTop = haut; else { const k = pass.find(x => x.los > now) || pass[0]; c.scrollTop = k ? Math.max(0, (new Date(k.aos).getHours() - 2) * H) : 7 * H; }
  } else {
    let h = '<div class="cal-liste">';
    p.jours.forEach(d => {
      const d0 = d.getTime(), d1 = calPlus(d, 1).getTime();
      const sp = spans.filter(x => calSur(x, d0, d1)), ps = pass.filter(x => x.aos >= d0 && x.aos < d1);
      h += '<h4>' + CAL_JOURS[calJs(d)] + ' ' + calJ(d) + ' ' + CAL_MOIS[calM(d)] + (d0 === auj ? ' · aujourd’hui' : '') + '</h4>' + sp.map(x => calBarre(x, true)).join('') +
        (ps.map(x => calPuce(x)).join('') || (sp.length ? '' : '<div class="petit">rien de prévu</div>'));
    });
    grille.innerHTML = h + '</div>';
    if (haut != null) grille.querySelector('.cal-liste').scrollTop = haut;
  }
  calCote();
}
function calJour(k) { CLD.jour = k; CLD.sel = null; calDessine(); }
function calChoisit(ev, k) { if (ev) ev.stopPropagation(); CLD.sel = k; calDessine(); }
/** The side panel: the item chosen, else the day chosen (its passes), else the legend. */
function calCote() {
  const c = g('cal-cote'); if (!c || !CLD.d) return;
  const x = CLD.sel ? CLD.d.items.find(i => calIdent(i) === CLD.sel) : null;
  let h = '';
  const dd = (t, v) => '<dt>' + t + '</dt><dd>' + v + '</dd>';
  if (x && x.sorte === 'passage') {
    const camp = x.campagne && AP && AP.campagnes ? AP.campagnes.find(k => k.id === x.campagne) : null;
    h = '<div class="t cal-pt" style="--c:' + calCoul(x) + '"><i></i>' + esc(x.sat) + '</div><dl>' +
      dd('Passage', esc(calQuand(x.aos)) + ' → ' + hm(x.los) + ' <span class="petit">(' + calZone() + ')</span>') + dd('Durée', duree(x.los - x.aos)) + dd('Élévation max', x.el + '°') +
      dd('Pour', x.source === 'campagne' ? '🤖 campagne « ' + esc(x.nom) + ' »' : x.source === 'veille' ? 'mode automatique' : 'satellite favori') +
      (x.statut ? dd('État', '<span class="pastille ap-st-' + esc(x.statut) + '">' + esc(CAL_ST[x.statut] || x.statut) + '</span>' + (x.suspendue ? ' <span class="petit">(campagne suspendue)</span>' : '') + (x.raison ? '<div class="petit">' + esc(x.raison) + '</div>' : '')) : '') +
      (x.images ? dd('Images', x.images + (x.ssdvImages ? ' <span class="petit">(dont ' + x.ssdvImages + ' SSDV, ' + x.ssdvPaquets + ' paquets)</span>' : '')) : '') + (x.enregistrement ? dd('Enregistrement', '<span class="petit">' + esc(x.enregistrement) + '</span>') : '') +
      (x.prep && !x.passe ? dd('Chaîne prête', hm(x.prep)) : '') + (camp ? dd('Chaîne', esc(apChaineTexte(camp))) : '') + '</dl>';
    const a = [];
    if (x.source === 'campagne' && !x.passe && (x.statut === 'PREVU' || x.statut === 'CHEVAUCHE' || x.statut === 'EN_COURS'))
      a.push('<button data-c="' + escA(x.campagne) + '" data-a="' + x.aos + '" onclick="apAnnulePassage(this.dataset.c, +this.dataset.a, true)" title="Ce passage seulement : la campagne continue">Annuler ce passage</button>');
    if (x.source === 'campagne' && !x.passe && x.statut === 'ANNULE')
      a.push('<button data-c="' + escA(x.campagne) + '" data-a="' + x.aos + '" onclick="apAnnulePassage(this.dataset.c, +this.dataset.a, false)">Reprendre ce passage</button>');
    if (x.campagne) a.push('<button data-c="' + escA(x.campagne) + '" onclick="acApVoir(this.dataset.c)">Ouvrir la campagne</button>');
    h += '<div class="cal-act">' + a.join('') + '</div>';
  } else if (x && x.sorte === 'campagne') {
    h = '<div class="t cal-pt" style="--c:' + calCoul(x) + '"><i></i>🤖 ' + esc(x.titre) + '</div><dl>' + dd('Satellite', esc(x.sat)) + dd('Période', esc(x.periode || calPeriode(x.de, x.a))) +
      dd('État', x.terminee ? '<span class="pastille off">terminée</span>' : x.active ? (CLD.d.maitre ? '<span class="pastille ap-st-PREVU">active</span>' : '<span class="pastille off">active, auto-pilote coupé</span>') : '<span class="pastille off">suspendue</span>') +
      dd('Reçus', x.recus + ' passage' + (x.recus > 1 ? 's' : '') + ', ' + x.images + ' image' + (x.images > 1 ? 's' : '')) + '</dl>' +
      '<div class="cal-act"><button data-c="' + escA(x.id) + '" onclick="acApVoir(this.dataset.c)">Ouvrir la campagne</button></div>';
  } else if (x && x.sorte === 'activite') {
    h = '<div class="t cal-pt" style="--c:' + calCoul(x) + '"><i></i>📡 ' + esc(x.sat || '?') + ' · ' + esc(x.libelle) + '</div><div>' + esc(x.titre) + '</div><dl>' +
      dd('Période', esc(calPeriode(x.de, x.a)) + (x.precision === 'JOURS' ? ' <span class="petit">(journées, horaire à confirmer)</span>' : x.precision === 'INCONNUE' ? ' <span class="petit">(dates non annoncées)</span>' : '')) +
      dd('Campagne', x.campagne ? 'oui' : x.campagneSupprimee ? 'campagne supprimée' : 'aucune') + '</dl><div class="cal-act">' +
      (x.campagne ? '<button data-c="' + escA(x.campagne) + '" onclick="acApVoir(this.dataset.c)">Ouvrir la campagne</button>' :
        (x.type === 'SSTV' || x.type === 'SSDV') && !x.passe ? '<button data-id="' + escA(x.id) + '" onclick="acAuto(this.dataset.id)">' + (x.campagneSupprimee ? 'Recréer la campagne' : 'Activer l’auto-pilote') + '</button>' : '') +
      (x.lien ? '<a href="' + escA(x.lien) + '" target="_blank" rel="noopener noreferrer">lien ↗</a>' : '') + '</div>';
  } else if (x && x.sorte === 'agenda') {
    h = '<div class="t">📅 ' + esc(x.titre) + '</div><dl>' + dd('Quand', x.a > x.de ? esc(calPeriode(x.de, x.a)) : esc(calQuand(x.de)) + ' <span class="petit">(' + calZone() + ')</span>') +
      (x.sat ? dd('Satellite', esc(x.sat)) : '') + (x.type ? dd('Type', esc(x.type)) : '') + (x.note ? dd('Note', esc(x.note)) : '') + dd('Rappel', x.actif ? 'actif' : 'coupé') + '</dl>' +
      '<div class="cal-act"><button data-id="' + x.id + '" onclick="rdvEdite(+this.dataset.id)">Modifier</button><button data-id="' + x.id + '" onclick="rdvRetire(+this.dataset.id)">Supprimer</button></div>';
  } else {
    const k = CLD.jour != null ? CLD.jour : calCle(Date.now()), d1 = calPlus(new Date(k), 1).getTime(), l = calItems();
    const ps = l.filter(i => i.sorte === 'passage' && i.aos >= k && i.aos < d1).sort((a, b) => a.aos - b.aos), sp = l.filter(i => i.sorte !== 'passage' && calSur(i, k, d1));
    h = '<div class="t">' + esc(CAL_JOURS[calJs(new Date(k))] + ' ' + calJ(new Date(k)) + ' ' + CAL_MOIS[calM(new Date(k))]) + '</div>' +
      (sp.length ? sp.map(i => calBarre(i, true)).join('') : '') +
      (ps.length ? '<table style="margin-top:6px"><tbody>' + ps.map(i => '<tr><td>' + calPuce(i) + '</td><td class="petit">' + esc(i.source === 'campagne' ? i.nom : i.source === 'veille' ? 'mode auto' : 'favori') +
        (i.statut ? ' · ' + esc(CAL_ST[i.statut] || i.statut) : '') + '</td></tr>').join('') + '</tbody></table>' : '<div class="petit" style="margin-top:6px">Aucun passage ce jour-là.</div>') +
      '<div class="cal-leg"><span><i style="--c:#3FE0C8"></i>une couleur par satellite</span><span>🤖 campagne</span><span>pointillés : favori</span><span>✓ reçu · ✗ manqué · ⊘ écarté/annulé</span><span>📡 annoncé en ligne</span><span>📅 rendez-vous</span></div>';
  }
  if (c.dataset.h !== h) { c.dataset.h = h; c.innerHTML = h; }
}
/** One pass of a campaign cancelled (or taken again): the campaign itself goes on. */
async function apAnnulePassage(id, aos, on) {
  const j = await post('api/autopilote/annule?id=' + enc(id) + '&aos=' + aos + '&on=' + (on ? 1 : 0));
  if (j && j.ok) annonce(on ? 'Passage annulé : la campagne continue' : 'Passage repris');
  apVu = ''; await apCharge(); if (AP && apEdit === id) apEditePassages(); calCharge(true);
}
setInterval(() => { if (ouverte('agenda') && g('ag-p-cal') && !g('ag-p-cal').hidden) calCharge(); }, 15000);
addEventListener('resize', () => { if (g('ag-p-cal') && !g('ag-p-cal').hidden) calDessine(); });
"""
}
