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
 * SatMe-PC's TX window: on a satellite one transmits while listening to one's
 * own downlink, so the transmit controls come to the reception page in a
 * floating panel (moved, resized, folded) instead of the operator leaving it.
 * Put into [PagePc.HTML]; no `$` in the script (a Kotlin template).
 *
 * - **No new way to transmit**: the window holds the page's own transmit
 *   panels — the IC-9700 (`#ptx`), « Trafic satellite » (`#leo-carte`: its CW
 *   and voice memories, APRS) and the Pluto on QO-100 (`#btx`) — moved into
 *   it while it is open and put back after. Their buttons, their guards (the
 *   held PTT, the session's confirmation, the time-out, the watchdog) and the
 *   routes they call are unchanged; the window itself only reads and stops.
 * - **Only with the transmission on**: it opens when one of those panels is
 *   there and ready (the rig connected, the Pluto set up, the module on);
 *   otherwise its header button stays grey and says why: reception only.
 * - **No TX without an eye on the RX**: it opens from a reception tab
 *   ([VUES_RX]: Suivi, QO-100, SDR, Son, the decoders) and is drawn only
 *   there; its controls are dead while the browser hides the page or another
 *   tab is shown, and if that happens while it transmits, every transmission
 *   of the window stops at once (the panels' existing stop routes).
 * - **STOP first**: the window's STOP (and Échap) is the page's « tout
 *   arrêter »; for a moment after it, nothing in the window keys again.
 *
 * Its place, size and fold are kept in this browser (localStorage, in
 * try/catch). The rules are [JS]'s `txfRegles`, apart from the page so that
 * node tests them.
 */
object FenetreTxPc {

    /** The tabs from which one may transmit with the window: those showing the reception (the downlink, the waterfall, the decoders). */
    val VUES_RX = listOf("station", "qo100", "sdr", "son", "sstv", "aprs", "ft8", "tlm")

    /** The window's panels, the page's own: key, element id. */
    val PANNEAUX = listOf("ptx" to "ptx", "leo" to "leo-carte", "btx" to "btx")

    /** After a STOP, how long nothing in the window keys again (ms). */
    const val VERROU_STOP_MS = 1500

    /** In the header, beside the TX light: opens the window (grey, with why, when the transmission is off). */
    const val ENTETE = """<button id="h-txf" class="h-puce h-txf gris" type="button" onclick="txfBascule()" aria-haspopup="dialog" aria-expanded="false" aria-controls="txf" title="Fenêtre d'émission : émettre sans quitter la réception"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/></svg><span>Émettre</span></button>"""

    /** The window, empty: its panels are moved in when it opens. */
    const val HTML = """
<section id="txf" class="txf" hidden role="dialog" aria-modal="false" aria-labelledby="txf-titre">
  <div class="txf-tete" id="txf-tete" title="Glisser pour déplacer (double-clic : à sa place d'origine)">
    <span class="txf-poignee" aria-hidden="true">⠿</span>
    <b id="txf-titre">Émission</b>
    <span class="txf-etat" id="txf-etat" role="status">RX</span>
    <span class="txf-tot" id="txf-tot" title="Minuterie d'émission (TOT, Réglages › Émission)"></span>
    <span class="txf-esp"></span>
    <button type="button" class="txf-stop" id="txf-stop" title="Tout arrêter, retour en réception (Échap aussi)">■ STOP</button>
    <button type="button" class="txf-b" id="txf-plie" aria-expanded="true" title="Replier la fenêtre (l'état et STOP restent)" aria-label="Replier">▁</button>
    <button type="button" class="txf-b" id="txf-ferme" title="Fermer la fenêtre : retour en réception, les panneaux reprennent leur place" aria-label="Fermer">✕</button>
  </div>
  <div class="txf-onglets" id="txf-onglets" role="tablist" aria-label="Émetteurs"></div>
  <div class="txf-corps" id="txf-corps"></div>
  <div class="txf-pied" id="txf-oeil" aria-live="polite"></div>
</section>
"""

    const val CSS = """
  /* ---------------------------------------------------------------- the TX window (FenetreTxPc): over the reception, never instead of it */
  .h-txf { cursor:pointer; align-items:center; font-weight:700; color:var(--tx); border-color:color-mix(in srgb, var(--tx) 45%, var(--ligne)); }
  .h-txf .ico { width:15px; height:15px; fill:currentColor; flex:none; }
  .h-txf:hover { background:color-mix(in srgb, var(--tx) 12%, var(--s2)); }
  .h-txf.gris { color:var(--texte-3); border-color:var(--ligne); cursor:help; }
  .h-txf.gris:hover { background:var(--s2); }
  .h-txf.ouvert { color:#fff; background:var(--tx); border-color:var(--tx); }
  .txf { position:fixed; z-index:190; left:auto; top:auto; right:16px; bottom:44px; width:430px; height:auto;
    min-width:300px; min-height:120px; max-width:calc(100vw - 16px); max-height:calc(100vh - 16px);
    display:flex; flex-direction:column; overflow:hidden; resize:both;
    background:color-mix(in srgb, var(--s2) 93%, transparent); -webkit-backdrop-filter:blur(10px); backdrop-filter:blur(10px);
    border:1px solid var(--ligne-forte); border-radius:var(--r4); box-shadow:0 0 0 1px color-mix(in srgb, var(--rx) 18%, transparent), var(--ombre-pop); }
  .txf[hidden] { display:none; }
  .txf.emet { border-color:var(--tx); box-shadow:0 0 0 2px var(--tx), 0 0 30px color-mix(in srgb, var(--tx) 45%, transparent), var(--ombre-pop); }
  .txf-tete { display:flex; align-items:center; gap:7px; padding:6px 7px 6px 9px; cursor:move; user-select:none; touch-action:none; flex:none;
    background:linear-gradient(90deg, color-mix(in srgb, var(--rx) 20%, var(--s1)), var(--s1) 70%); border-bottom:1px solid var(--ligne); }
  .txf.emet .txf-tete { background:linear-gradient(90deg, var(--tx), color-mix(in srgb, var(--tx) 45%, var(--s1)) 75%); color:#fff; }
  .txf-poignee { color:var(--texte-3); font-size:14px; letter-spacing:-1px; }
  .txf.emet .txf-poignee { color:rgba(255,255,255,.75); }
  #txf-titre { font-size:var(--fs-m); letter-spacing:.3px; }
  .txf-etat { font:800 13px var(--mono); padding:2px 10px; border-radius:999px; border:2px solid var(--rx); color:var(--rx); background:color-mix(in srgb, var(--rx) 10%, transparent); letter-spacing:1px; }
  .txf-etat.tx { color:#fff; background:var(--tx); border-color:#fff; animation:txf-pouls .9s ease-in-out infinite alternate; }
  @keyframes txf-pouls { from { box-shadow:0 0 0 1px color-mix(in srgb, var(--tx) 40%, transparent); } to { box-shadow:0 0 0 5px color-mix(in srgb, var(--tx) 55%, transparent); } }
  .txf-tot { font:700 var(--fs-s) var(--mono); color:var(--texte-2); white-space:nowrap; }
  .txf.emet .txf-tot { color:#fff; }
  .txf-esp { flex:1; }
  .txf-stop { flex:none; padding:3px 12px; border-radius:var(--r3); border:2px solid var(--danger); background:var(--danger); color:#fff; font-weight:800; letter-spacing:.6px; }
  .txf-stop:hover { filter:brightness(1.12); border-color:#fff; }
  .txf.emet .txf-stop { background:#fff; color:var(--danger); border-color:#fff; box-shadow:0 0 0 2px var(--danger); }
  .txf-stop.flash { animation:txf-flash .5s ease-out; }
  @keyframes txf-flash { from { box-shadow:0 0 0 8px color-mix(in srgb, var(--danger) 60%, transparent); } to { box-shadow:0 0 0 0 transparent; } }
  .txf-b { flex:none; min-width:26px; padding:2px 6px; background:transparent; border-color:transparent; color:inherit; opacity:.8; }
  .txf-b:hover { opacity:1; border-color:var(--ligne-forte); }
  .txf-onglets { display:flex; gap:4px; padding:6px 8px 0; flex:none; flex-wrap:wrap; }
  .txf-onglets button { padding:3px 10px; border-radius:var(--r3) var(--r3) 0 0; border-bottom-color:transparent; font-size:var(--fs-s); color:var(--texte-2); background:var(--s1); }
  .txf-onglets button[aria-selected="true"] { color:var(--texte); background:var(--s3); border-color:var(--ligne-forte); border-bottom-color:var(--s3); box-shadow:inset 0 2px 0 var(--tx); }
  .txf-onglets button.emet::after { content:' ●'; color:var(--tx); }
  .txf-corps { flex:1; min-height:0; overflow:auto; padding:8px 10px; border-top:1px solid var(--ligne); background:color-mix(in srgb, var(--s3) 35%, transparent); transition:opacity var(--anim), filter var(--anim); }
  .txf-corps .carte { margin:0; border:0; box-shadow:none; background:transparent; padding:0; }
  .txf-corps .carte .aide { display:none; }
  .txf-corps .ptx-ptt { width:110px; min-height:84px; font-size:22px; }
  .txf-pan[hidden] { display:none; }
  .txf-vide { color:var(--texte-3); font-size:var(--fs-s); padding:12px 4px; }
  .txf.inerte .txf-corps { opacity:.42; filter:grayscale(.7); pointer-events:none; }
  .txf-pied { flex:none; display:flex; align-items:center; gap:6px; padding:4px 10px; font-size:var(--fs-xs); border-top:1px solid var(--ligne); background:var(--s1); color:var(--rx); }
  .txf-pied.perdu { color:#fff; background:var(--danger); font-weight:700; }
  .txf-pied::before { content:''; width:8px; height:8px; border-radius:50%; background:currentColor; flex:none; box-shadow:0 0 6px currentColor; }
  .txf.plie { height:auto !important; min-height:0; resize:none; }
  .txf.plie .txf-onglets, .txf.plie .txf-corps { display:none; }
  /* Where a panel was, while it is in the window. */
  .txf-ici { display:flex; align-items:center; gap:8px; font-size:var(--fs-s); color:var(--texte-2); border-style:dashed; }
  .txf-ici b { color:var(--tx); }
  .txf-ici button { margin-left:auto; }
  @media (max-width:700px) {
    .txf { left:8px !important; right:8px !important; top:auto !important; bottom:64px !important; width:auto !important; height:min(70vh, 520px) !important; resize:none; }
    .txf-tete { cursor:default; }
  }
"""

    const val JS = """
// ------------------------------------------------------------------ the TX window (FenetreTxPc): the page's transmit panels over the reception
const TXF_VUES_RX = ['station', 'qo100', 'sdr', 'son', 'sstv', 'aprs', 'ft8', 'tlm'];
const TXF_PANNEAUX = [{ k: 'ptx', id: 'ptx', nom: 'Phonie · IC-9700' }, { k: 'leo', id: 'leo-carte', nom: 'Trafic satellite' }, { k: 'btx', id: 'btx', nom: 'Pluto · QO-100' }];
const TXF_VERROU_STOP_MS = 1500;
/**
 * The window's rules, apart from the page (node tests them). [m] is what the page knows and does:
 * visible() the browser shows this page; vue() the tab shown; telephone(); panneaux() each panel's
 * { present, pret, emet, pourquoi }; emet() this page transmits by the window's panels; retourRx(raison)
 * those transmissions stopped (the panels' stop routes); arret() the page's « tout arrêter »; maintenant().
 */
function txfRegles(m) {
  const R = { ouverte: false, stopJusque: 0, derniere: '' };
  R.oeil = () => !!m.visible() && TXF_VUES_RX.indexOf(m.vue()) >= 0;
  /** May the window open: one panel there and ready (or transmitting); else why not. */
  R.etat = () => {
    if (m.telephone && m.telephone()) return { ok: false, pourquoi: 'Pas d’émission depuis la page du téléphone : réception seule.', onglets: [] };
    const p = m.panneaux() || {}, onglets = [], pq = [];
    TXF_PANNEAUX.forEach(x => { const q = p[x.k]; if (!q || !q.present) return; if (q.pret || q.emet) onglets.push(x.k); else if (q.pourquoi) pq.push(q.pourquoi); });
    if (onglets.length) return { ok: true, pourquoi: '', onglets: onglets };
    return { ok: false, onglets: onglets, pourquoi: pq.length ? 'Émission pas prête : ' + pq[0] + '. Réception seule.'
      : 'Émission non activée : aucun émetteur prêt (l’IC-9700 connecté en CAT dans Réglages › Poste, ou le Pluto dans QO-100 › Émission). Réception seule.' };
  };
  R.ouvre = () => {
    const e = R.etat();
    if (!e.ok) return e.pourquoi;
    if (!R.oeil()) return 'La fenêtre d’émission s’ouvre depuis une page de réception (Suivi, QO-100, SDR, Son, les décodeurs) : pas d’émission sans œil sur la réception.';
    R.ouverte = true; return '';
  };
  /** Why nothing in the window may key now ('' when it may). */
  R.refus = () => {
    if (m.maintenant() < R.stopJusque) return 'STOP à l’instant : réception.';
    if (!m.visible()) return 'Page cachée ou réduite : pas d’émission sans œil sur la réception.';
    if (R.ouverte && !R.oeil()) return 'La réception n’est plus affichée : pas d’émission.';
    return '';
  };
  /** The eye lost while the window transmits: back to reception at once. True when it stopped something. */
  R.surveille = () => {
    if (!R.ouverte || R.oeil() || !m.emet()) return false;
    R.derniere = !m.visible() ? 'page cachée ou réduite' : 'onglet de réception quitté';
    m.retourRx(R.derniere);
    return true;
  };
  /** STOP: the page's « tout arrêter » first, then a moment when nothing keys again. */
  R.stop = () => { try { m.arret(); } finally { R.stopJusque = m.maintenant() + TXF_VERROU_STOP_MS; } };
  R.verrou = () => { R.stopJusque = m.maintenant() + TXF_VERROU_STOP_MS; };
  /** Closed: what the window transmitted stops (its panels go back to their places). */
  R.ferme = () => { const e = R.ouverte && m.emet(); R.ouverte = false; if (e) m.retourRx('fenêtre d’émission fermée'); return e; };
  return R;
}
var TXF = null, TXF_D = { onglet: {}, plie: false };
function txfVue() { const v = document.querySelector('.vue:not([hidden])'); return v ? v.id.slice(2) : ''; }
function txfNomVue(n) { const b = document.querySelector('#onglets button[data-v="' + n + '"]'); const s = b && (b.querySelector('span') || b); return (s && s.textContent.trim()) || n; }
/** Each panel, read from what the page already polls (PX, BX, LEO). */
function txfPanneaux() {
  const px = typeof PX !== 'undefined' ? PX : null, bx = typeof BX !== 'undefined' ? BX : null, leo = typeof LEO !== 'undefined' ? LEO : null;
  return {
    ptx: { present: !!(px && px.ok && !px.absent && (px.poste || px.emet)), pret: !!(px && px.pret), emet: !!(px && px.emet), pourquoi: px && px.pourquoi || '' },
    btx: { present: !!(bx && bx.ok && !bx.absent && (bx.emet || (typeof bxPluto === 'function' && bxPluto()))), pret: !!(bx && bx.pret), emet: !!(bx && bx.emet), pourquoi: bx && bx.pourquoi || '' },
    leo: { present: !!(leo && leo.ok !== false && leo.module), pret: !!(leo && leo.module && !leo.refus), emet: !!(leo && (leo.emet || (leo.balise && leo.balise.armee))), pourquoi: leo && leo.refus || '' },
  };
}
/** This page transmits by one of the window's panels. */
function txfEmet() {
  const p = txfPanneaux();
  return !!((typeof pxTenu !== 'undefined' && pxTenu) || (typeof bxTenu !== 'undefined' && bxTenu) ||
    (p.ptx.emet && PX.moi) || (p.btx.emet && BX.moi) || p.leo.emet);
}
/** Back to reception: the panels' own stop routes (a beacon when the page goes away). Only stops. */
function txfRetourRx(raison) {
  try { pxTenu = false; pxVie(false); } catch (x) {}
  try { bxTenu = false; bxVerrouille = false; bxCwAuto = false; bxVie(false); } catch (x) {}
  ['ptx-ptt', 'btx-ptt'].forEach(i => { const b = g(i); if (b) b.classList.remove('on'); });
  const cache = document.visibilityState !== 'visible';
  ['api/poste/emission/arrete?client=' + TX_CLIENT, 'api/be/emission/arrete?client=' + TX_CLIENT, 'api/trafic-sat?arret=1&client=' + TX_CLIENT].forEach(u => {
    let parti = false;
    if (cache && navigator.sendBeacon) { try { parti = navigator.sendBeacon(u); } catch (x) {} }
    if (!parti) fetch(u, { method: 'POST', keepalive: true }).catch(() => {});
  });
  annonce('■ Retour en réception : ' + raison, true);
  try { HX.t = {}; } catch (x) {}
}
/** Asked by the panels before they key (pxAppuie, bxAppuie, leoJoue): '' when they may. */
function txfRefus() { return TXF ? TXF.refus() : ''; }
function txfGarde() { try { localStorage.setItem('satme.txf', JSON.stringify(TXF_D)); } catch (x) {} }
/** Inside the screen: the header always reachable. */
function txfDans() {
  const f = g('txf');
  if (f.hidden || matchMedia('(max-width:700px)').matches || TXF_D.x == null) return;
  const w = f.offsetWidth, h = f.offsetHeight;
  TXF_D.x = Math.max(8, Math.min(innerWidth - Math.min(w, innerWidth - 16) - 8, TXF_D.x));
  TXF_D.y = Math.max(8, Math.min(innerHeight - Math.min(h, 44) - 8, TXF_D.y));
  f.style.left = TXF_D.x + 'px'; f.style.top = TXF_D.y + 'px'; f.style.right = 'auto'; f.style.bottom = 'auto';
}
/** The panels moved in (a dashed note left where each was), or put back. */
function txfPrend() {
  const c = g('txf-corps');
  TXF_PANNEAUX.forEach(x => {
    const el = g(x.id); if (!el || el.closest('#txf')) return;
    const ici = document.createElement('div');
    ici.className = 'carte txf-ici'; ici.dataset.txfIci = x.k;
    ici.innerHTML = '<span><b>' + esc(x.nom) + '</b> : dans la fenêtre d’émission</span><button type="button">Montrer</button>';
    ici.querySelector('button').onclick = () => { TXF_D.plie = false; txfOnglet(x.k); txfRend(); };
    ici.hidden = el.hidden;
    el.parentNode.insertBefore(ici, el);
    const pan = document.createElement('div');
    pan.className = 'txf-pan'; pan.dataset.k = x.k; pan.setAttribute('role', 'tabpanel');
    pan.appendChild(el); c.appendChild(pan);
  });
}
function txfRemet() {
  TXF_PANNEAUX.forEach(x => {
    const el = g(x.id), ici = document.querySelector('[data-txf-ici="' + x.k + '"]');
    if (el && ici) { ici.parentNode.insertBefore(el, ici); ici.remove(); }
  });
  g('txf-corps').innerHTML = '';
  if (typeof pxPlace === 'function') pxPlace(txfVue());
}
function txfOnglet(k) { TXF_D.onglet[txfVue()] = k; txfGarde(); txfRend(); }
/** The window as the rules and the panels say: shown on a reception tab only, its tabs, its state, its eye. */
function txfRend() {
  const f = g('txf'), b = g('h-txf');
  if (!f || !TXF) return;
  const e = TXF.etat(), oeil = TXF.oeil(), vue = txfVue(), refus = TXF.refus(), p = txfPanneaux();
  // The header's button: grey with why when the transmission is off, or away from the reception.
  const peut = e.ok && TXF_VUES_RX.indexOf(vue) >= 0;
  b.classList.toggle('gris', !peut && !TXF.ouverte); b.classList.toggle('ouvert', TXF.ouverte);
  b.setAttribute('aria-expanded', TXF.ouverte ? 'true' : 'false');
  b.setAttribute('aria-disabled', !peut && !TXF.ouverte ? 'true' : 'false');
  b.title = TXF.ouverte ? 'Fenêtre d’émission ouverte : clic pour la fermer (retour en réception)' : !e.ok ? e.pourquoi
    : peut ? 'Fenêtre d’émission : émettre en gardant la réception sous les yeux' : 'La fenêtre d’émission s’ouvre depuis une page de réception (Suivi, QO-100, SDR, Son, les décodeurs).';
  f.hidden = !TXF.ouverte || TXF_VUES_RX.indexOf(vue) < 0;
  if (f.hidden) return;
  f.classList.toggle('plie', !!TXF_D.plie);
  txt('txf-plie', TXF_D.plie ? '▣' : '▁'); g('txf-plie').setAttribute('aria-expanded', TXF_D.plie ? 'false' : 'true');
  g('txf-plie').title = TXF_D.plie ? 'Déplier la fenêtre' : 'Replier la fenêtre (l’état et STOP restent)';
  document.querySelectorAll('[data-txf-ici]').forEach(x => { const el = g((TXF_PANNEAUX.find(y => y.k === x.dataset.txfIci) || {}).id); x.hidden = !el || el.hidden; });
  // Its tabs: the panels shown by the page; the one of the mode in use first.
  const vus = TXF_PANNEAUX.filter(x => { const el = g(x.id); return el && !el.hidden && getComputedStyle(el).display !== 'none'; });
  let k = TXF_D.onglet[vue];
  if (!vus.some(x => x.k === k)) k = (vue === 'qo100' && vus.some(x => x.k === 'btx')) ? 'btx' : (vus.find(x => p[x.k].emet) || vus.find(x => p[x.k].pret) || vus[0] || {}).k;
  const h = vus.map(x => '<button type="button" role="tab" data-k="' + x.k + '" aria-selected="' + (x.k === k) + '"' + (p[x.k].emet ? ' class="emet"' : '') + '>' + esc(x.nom) + '</button>').join('');
  if (g('txf-onglets').innerHTML !== h) g('txf-onglets').innerHTML = h;
  document.querySelectorAll('#txf-corps .txf-pan').forEach(x => { x.hidden = x.dataset.k !== k; });
  let vide = g('txf-vide');
  if (!vus.length && !vide) { vide = document.createElement('div'); vide.id = 'txf-vide'; vide.className = 'txf-vide'; g('txf-corps').appendChild(vide); }
  if (vide) { vide.hidden = !!vus.length; vide.textContent = e.pourquoi || 'Plus aucun émetteur ici.'; }
  // The state: TX loud, with the time-out counting down.
  const tx = p.ptx.emet || p.btx.emet || p.leo.emet;
  f.classList.toggle('emet', tx);
  const et = g('txf-etat'); et.textContent = tx ? '● TX' : 'RX'; et.classList.toggle('tx', tx);
  const q = k === 'btx' ? BX : k === 'ptx' ? PX : (p.ptx.emet ? PX : p.btx.emet ? BX : PX);
  const mn = s => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  txt('txf-tot', q && q.emet && q.restant != null ? 'TOT ' + mn(q.restant) : q && q.tot ? 'TOT ' + q.tot + ' min' : '');
  // The eye: the reception shown, else why the controls are dead.
  f.classList.toggle('inerte', !!refus);
  try { g('txf-corps').inert = !!refus; } catch (x) {}
  const pied = g('txf-oeil');
  pied.classList.toggle('perdu', !!refus || !oeil);
  txt('txf-oeil', refus || (oeil ? 'Réception sous les yeux : ' + txfNomVue(vue) + ' — STOP ou Échap : tout arrêter' : 'Réception pas affichée'));
}
function txfOuvre() {
  const r = TXF.ouvre();
  if (r) { if (typeof popMontre === 'function') popMontre(g('h-txf'), '<div class="aide-p">' + esc(r) + '</div>', true); else annonce(r); return false; }
  txfPrend(); txfRend(); txfDans();
  try { g('txf-stop').focus({ preventScroll: true }); } catch (x) {}
  return true;
}
function txfFerme() { TXF.ferme(); txfRemet(); txfRend(); }
function txfBascule() { if (TXF.ouverte) txfFerme(); else txfOuvre(); }
function txfStop() { const s = g('txf-stop'); TXF.stop(); s.classList.remove('flash'); void s.offsetWidth; s.classList.add('flash'); txfRend(); }
/** The eye checked now (a tab changed, the page hidden): the window's transmissions stopped when it is lost. */
function txfSurveille() { if (!TXF) return; TXF.surveille(); txfRend(); }
(function () {
  if (!g('txf')) return;
  TXF = txfRegles({
    visible: () => document.visibilityState === 'visible', vue: txfVue, telephone: () => !!window.SATME_TELEPHONE,
    panneaux: txfPanneaux, emet: txfEmet, retourRx: txfRetourRx, arret: () => arretEmissions(), maintenant: () => Date.now(),
  });
  try { const d = JSON.parse(localStorage.getItem('satme.txf') || 'null'); if (d && typeof d === 'object') TXF_D = Object.assign(TXF_D, d, { onglet: d.onglet || {} }); } catch (x) {}
  const f = g('txf');
  if (TXF_D.w) f.style.width = TXF_D.w + 'px';
  if (TXF_D.h) f.style.height = TXF_D.h + 'px';
  // STOP first: on the press itself, before anything else of the window.
  g('txf-stop').addEventListener('pointerdown', ev => { if (ev.button === 0) { ev.preventDefault(); ev.stopPropagation(); txfStop(); } });
  g('txf-stop').addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); txfStop(); } });
  g('txf-ferme').onclick = () => txfFerme();
  g('txf-plie').onclick = () => { TXF_D.plie = !TXF_D.plie; txfGarde(); txfRend(); txfDans(); };
  g('txf-onglets').onclick = ev => { const b = ev.target.closest('[data-k]'); if (b) txfOnglet(b.dataset.k); };
  // Échap stops everything (the page's own key): the window keys nothing for a moment after.
  document.addEventListener('keydown', ev => { if (ev.key === 'Escape' && TXF) { TXF.verrou(); txfRend(); } }, true);
  // Dragged by its header.
  const t = g('txf-tete');
  t.addEventListener('pointerdown', ev => {
    if (ev.button !== 0 || ev.target.closest('button') || matchMedia('(max-width:700px)').matches) return;
    ev.preventDefault();
    const r = f.getBoundingClientRect(), dx = ev.clientX - r.left, dy = ev.clientY - r.top;
    try { t.setPointerCapture(ev.pointerId); } catch (x) {}
    const mv = e => { TXF_D.x = e.clientX - dx; TXF_D.y = e.clientY - dy; txfDans(); };
    const fin = () => { t.removeEventListener('pointermove', mv); t.removeEventListener('pointerup', fin); t.removeEventListener('pointercancel', fin); txfGarde(); };
    t.addEventListener('pointermove', mv); t.addEventListener('pointerup', fin); t.addEventListener('pointercancel', fin);
  });
  t.addEventListener('dblclick', ev => { if (ev.target.closest('button')) return; delete TXF_D.x; delete TXF_D.y; delete TXF_D.w; delete TXF_D.h; f.removeAttribute('style'); txfGarde(); });
  // Resized by its corner (the browser then writes its size on it): the size kept.
  try { new ResizeObserver(() => { if (f.hidden || TXF_D.plie || (!f.style.width && !f.style.height) || matchMedia('(max-width:700px)').matches) return; const w = f.offsetWidth, h = f.offsetHeight;
    if (Math.abs((TXF_D.w || 0) - w) > 2 || Math.abs((TXF_D.h || 0) - h) > 2) { TXF_D.w = w; TXF_D.h = h; txfGarde(); } }).observe(f); } catch (x) {}
  window.addEventListener('resize', txfDans);
  // No TX without an eye on the RX: the page hidden, reduced, left.
  document.addEventListener('visibilitychange', txfSurveille);
  window.addEventListener('pagehide', () => { if (TXF.ouverte && txfEmet()) txfRetourRx('page quittée'); });
  setInterval(txfSurveille, 400);
  txfRend();
})();
"""
}
