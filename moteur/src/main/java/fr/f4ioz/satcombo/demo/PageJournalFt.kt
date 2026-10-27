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
 * The journal page's FT8/FT4 panel ([PageJournal]): the pass shown's
 * messages received up to the replay's time, laid over the view where the
 * mouse or a finger put it, sized by its corner (its font following its
 * width), more or less visible — the same numbers as the station's videos
 * (`JournalFt.mise`, `PanneauFtPc`), so the file shows what the page did.
 * Kept with each pass (`/ft/choix`); read again from the recording on
 * SatMe-PC (`/ft/redecode`).
 */
object PageJournalFt {

    const val CSS = """
  /* The FT8/FT4 messages over the replay: dragged, sized by their corner, as visible as the slider says. */
  #cadreFt { position:absolute; z-index:3; display:none; cursor:move; touch-action:none; user-select:none; border-radius:.5em; overflow:hidden;
    font-family:ui-monospace,Consolas,monospace; color:#E8F0F7; }
  #cadreFt::before { content:''; position:absolute; inset:0; background:rgba(5,10,16,.82); border:1px solid rgba(63,224,200,.47); border-radius:inherit; opacity:var(--ftop, .85); }
  #cadreFt .ftT, #cadreFt .ftL { position:relative; opacity:var(--ftop, .85); pointer-events:none; }
  #cadreFt .ftT { font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; font-weight:700; color:#3FE0C8; white-space:nowrap; font-size:.95em; }
  #cadreFt .ftL > div { display:flex; white-space:nowrap; overflow:hidden; }
  #cadreFt .ftL .h { color:#9AA7B4; white-space:pre; }
  #cadreFt .ftL .x { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; }
  #cadreFt .ftL .dr { margin-left:.5em; font-family:Drapeaux,system-ui,sans-serif; color:#9AA7B4; }
  #cadreFt .ftL .c .x { color:#7CE38B; } #cadreFt .ftL .q .x { color:#FF4FA3; } #cadreFt .ftL .mi .x { color:#FF6B6B; font-weight:700; }
  #cadreFt:hover, #cadreFt.prise { outline:1px dashed rgba(255,255,255,.75); outline-offset:2px; }
  #cadreFt .poignee { position:absolute; right:0; bottom:0; width:14px; height:14px; background:var(--cyan); border:2px solid #0B1016; border-radius:3px; cursor:nwse-resize; display:none; }
  #cadreFt:hover .poignee, #cadreFt.prise .poignee { display:block; }
  #cadreFt .auto { position:absolute; right:3px; top:3px; font-size:10px; font-family:system-ui,sans-serif; padding:1px 6px; background:rgba(5,10,16,.85); display:none; }
  #cadreFt.manuel:hover .auto { display:block; }
  .ft-bloc .s-curseur input { width:140px; }
"""

    const val JS = """
// ------------------------------------------------------------ the FT8/FT4 messages over the replay
// The pass shown's messages received up to the replay's time (a slot's once it is over), in a panel placed with
// the mouse or a finger (shares of the view as the SSTV picture: left, top, width, height), its font following its
// width as in the files (JournalFt.mise), more or less visible; kept with the pass (each pass of a session), and
// sent with the files so the video shows the same.
const FT_DEFAUT = { x: .02, y: .30, l: .42, h: .42 };
let ftCle = '', ftMsg = '';
function ftMise(l, h) {
  const p = Math.max(5, l / 34 * 1.65), ligne = p * 1.32, marge = p * .45, titre = p * 1.45;
  return { p: p, ligne: ligne, marge: marge, titre: titre, n: Math.max(0, Math.floor((h - 2 * marge - titre) / ligne)) };
}
function ftLisCadre(s) { const v = String(s || '').split(',').map(x => x.trim() === '' ? NaN : +x); return v.length === 4 && v.every(isFinite) ? { x: v[0], y: v[1], l: v[2], h: v[3] } : null; }
function ftTexteCadre(c) { return c ? [c.x, c.y, c.l, c.h].map(v => v.toFixed(4)).join(',') : 'auto'; }
function ftAff() { return (P && P.ftAff) || { aff: true, cadre: '', op: 85, haut: false }; }
function ftPlace(c, w, h) {
  const l = Math.max(.15, Math.min(1, c.l)) * w, hh = Math.max(.08, Math.min(1, c.h)) * h;
  return { x: Math.max(0, Math.min(w - l, c.x * w)), y: Math.max(0, Math.min(h - hh, c.y * h)), l: l, h: hh };
}
function ftBorne(r, v) { const c = ftPlace({ x: r.x / v.w, y: r.y / v.h, l: r.l / v.w, h: r.h / v.h }, v.w, v.h); return { x: c.x / v.w, y: c.y / v.h, l: c.l / v.w, h: c.h / v.h }; }
function ftDuree(m) { return String(m).toUpperCase() === 'FT4' ? 7500 : 15000; }
function ftHeure(tt) { const d = new Date(tt); return p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + ':' + p2(d.getUTCSeconds()); }
/** Those shown at [tt]: their slot over, the [n] newest, the newest at the top or the bottom. */
function ftVisibles(l, tt, n, haut) {
  if (n <= 0) return [];
  const vus = l.filter(d => d.t + ftDuree(d.m) <= tt).sort((a, b) => a.t - b.t || b.db - a.db).slice(-n);
  return haut ? vus.sort((a, b) => b.t - a.t || b.db - a.db) : vus;
}
function majFt() {
  const e = g('ecran'); if (!e) return;
  let o = g('cadreFt');
  const a = ftAff(), v = P && P.ft && P.ft.length && a.aff ? vueSstv() : null;
  if (!v) { if (o) o.style.display = 'none'; return; }
  if (!o) {
    o = el('div', { id: 'cadreFt', title: T.ft_cadre_aide }, [el('div', { class: 'ftT' }), el('div', { class: 'ftL' }),
      el('button', { class: 'auto discret', text: T.ft_defaut }), el('div', { class: 'poignee' })]);
    e.appendChild(o); installeFt(o);
  }
  const r = ftPlace(ftLisCadre(a.cadre) || FT_DEFAUT, v.w, v.h), m = ftMise(r.l, r.h);
  Object.assign(o.style, { display: 'block', left: (v.x + r.x) + 'px', top: (v.y + r.y) + 'px', width: r.l + 'px', height: r.h + 'px',
    fontSize: m.p + 'px', padding: m.marge + 'px' });
  o.style.setProperty('--ftop', String(Math.max(0, Math.min(100, a.op)) / 100));
  o.classList.toggle('manuel', !!a.cadre);
  const vus = ftVisibles(P.ft, t, m.n, a.haut), nb = P.ft.filter(d => d.t + ftDuree(d.m) <= t).length;
  const cle = P.id + '|' + nb + '|' + m.n + '|' + a.haut + '|' + m.p.toFixed(2);
  if (cle === ftCle) return; ftCle = cle;
  const [ti, li] = o.children;
  ti.style.height = ti.style.lineHeight = m.titre + 'px';
  ti.textContent = [...new Set(P.ft.map(d => String(d.m).toUpperCase()))].sort().join(' · ') + ' · ' + nb + ' · UTC';
  li.innerHTML = '';
  vus.forEach(d => li.appendChild(el('div', { class: d.mi ? 'mi' : d.q ? 'q' : d.c ? 'c' : '', style: 'height:' + m.ligne + 'px;line-height:' + m.ligne + 'px',
    title: [d.py, d.hz + ' Hz', 'dt ' + d.dt].filter(x => x).join(' · ') }, [
    el('span', { class: 'h', text: ftHeure(d.t) + ' ' + String(d.db).padStart(3, ' ') + ' ' }), el('span', { class: 'x', text: d.x }),
    el('span', { class: 'dr', text: d.dr || d.iso || '' })])));
}
/** The panel's choices changed: on every pass of the editor here, kept with them on the station. */
function ftChange(q, envoi) {
  if (!S) return;
  S.ps.forEach(x => { x.ftAff = Object.assign({}, x.ftAff || ftAff(), q); });
  ftCle = ''; majFt();
  if (envoi === false) return;
  const a = ftAff(), b = v => v ? '1' : '0';
  envoie('/ft/choix', { ids: S.ids.join(','), id: P.id, ft: b(a.aff), ftCadre: a.cadre || 'auto', ftOpacite: a.op, ftOrdre: a.haut ? 'haut' : 'bas' }).catch(() => {});
}
/** What the files are told of it (the session's other passes shown the same). */
function paramsFt() { const a = ftAff(); return { ft: a.aff ? '1' : '0', ftCadre: a.cadre || 'auto', ftOpacite: a.op, ftOrdre: a.haut ? 'haut' : 'bas' }; }
function installeFt(o) {
  let pres = null, minuteur = 0;
  const ici = v => ftPlace(ftLisCadre(ftAff().cadre) || FT_DEFAUT, v.w, v.h);
  o.addEventListener('pointerdown', ev => {
    if (ev.target.classList.contains('auto')) return;
    ev.preventDefault(); ev.stopPropagation(); const v = vueSstv(); if (!v) return;
    o.setPointerCapture(ev.pointerId); o.classList.add('prise');
    pres = { x: ev.clientX, y: ev.clientY, r: ici(v), v: v, taille: ev.target.classList.contains('poignee') };
  });
  o.addEventListener('pointermove', ev => {
    if (!pres) return; const dx = ev.clientX - pres.x, dy = ev.clientY - pres.y, r = pres.r, v = pres.v;
    // The corner: width and height each their own way, from the top left; elsewhere the whole panel moves.
    const n = pres.taille ? { x: r.x, y: r.y, l: Math.max(20, r.l + dx), h: Math.max(20, r.h + dy) } : { x: r.x + dx, y: r.y + dy, l: r.l, h: r.h };
    ftChange({ cadre: ftTexteCadre(ftBorne(n, v)) }, false);
  });
  const lache = () => { if (!pres) return; pres = null; o.classList.remove('prise'); ftChange({}); };
  o.addEventListener('pointerup', lache); o.addEventListener('pointercancel', lache);
  o.addEventListener('wheel', ev => {
    ev.preventDefault(); const v = vueSstv(); if (!v) return;
    const r = ici(v), k = ev.deltaY > 0 ? 1 / 1.1 : 1.1, l = r.l * k, h = r.h * k;
    ftChange({ cadre: ftTexteCadre(ftBorne({ x: r.x + (r.l - l) / 2, y: r.y + (r.h - h) / 2, l: l, h: h }, v)) }, false);
    clearTimeout(minuteur); minuteur = setTimeout(() => ftChange({}), 300);
  }, { passive: false });
  o.querySelector('.auto').addEventListener('click', ev => { ev.stopPropagation(); ftChange({ cadre: '' }); });
}
/** The Options tab's block: shown, how visible, which way; reading the recording again (SatMe-PC). */
function blocFt(p) {
  if (!S || !P) return;
  const avec = S.ps.some(q => q.ft && q.ft.length), relire = !!(window.SATME_PC && P.sons.length);
  if (!avec && !relire) return;
  const b = el('div', { class: 'ft-bloc' });
  b.appendChild(titreBloc(T.ft_titre));
  if (avec) {
    const a = ftAff();
    const k = el('input', { type: 'checkbox', id: 'ftAffiche' }); k.checked = !!a.aff;
    k.addEventListener('change', () => ftChange({ aff: k.checked }));
    b.appendChild(el('label', { class: 'rangee' }, [k, el('span', { text: T.ft_afficher + ' (' + tf('ft_n', P.ft.length) + ')' })]));
    const val = el('b', { text: a.op + ' %' });
    const r = el('input', { type: 'range', min: 0, max: 100, step: 5, value: a.op, id: 'ftVisibilite' });
    r.addEventListener('input', () => { val.textContent = r.value + ' %'; ftChange({ op: +r.value }, false); });
    r.addEventListener('change', () => ftChange({ op: +r.value }));
    const o = el('select', { onchange: ev => ftChange({ haut: ev.target.value === 'haut' }) },
      [['bas', T.ft_bas], ['haut', T.ft_haut]].map(c => el('option', { value: c[0], text: c[1] })));
    o.value = a.haut ? 'haut' : 'bas';
    b.appendChild(el('div', { class: 'commandes' }, [el('label', { class: 's-curseur' }, [el('span', { text: T.ft_visibilite }), r, val]),
      el('label', { class: 's-curseur' }, [el('span', { text: T.ft_ordre }), o])]));
    b.appendChild(el('div', { class: 'aide', text: T.ft_aide }));
  } else b.appendChild(el('div', { class: 'aide', text: T.ft_aucun }));
  if (relire) {
    const m = el('select', {}, ['FT8', 'FT4'].map(x => el('option', { value: x, text: x })));
    if (P.ft && P.ft.length && String(P.ft[0].m).toUpperCase() === 'FT4') m.value = 'FT4';
    const z = el('span', { class: 'aide', text: ftMsg });
    const bt = el('button', { class: 'discret', text: T.ft_redecoder, title: T.ft_redecode_aide, onclick: () => ftRedecode(m.value, z, bt) });
    b.appendChild(el('div', { class: 'rangee' }, [m, bt, z]));
  }
  p.appendChild(b);
}
async function ftRedecode(mode, z, bt) {
  if (!P) return; const id = P.id;
  bt.disabled = true; z.textContent = tf('ft_en_cours', 0);
  let j = null; try { j = await lire('/ft/redecode', { id: id, mode: mode }); } catch (e) {}
  if (!j || !j.ok) { z.textContent = T.echec; bt.disabled = false; return; }
  while (true) {
    await new Promise(r => setTimeout(r, 800));
    let e = null; try { e = await lire('/travail', { n: j.travail }); } catch (x) { z.textContent = T.echec; bt.disabled = false; return; }
    if (!e.fini) { z.textContent = tf('ft_en_cours', Math.round(e.progres * 100)); continue; }
    ftMsg = e.trouves >= 0 ? tf('ft_trouves', e.trouves) : T.echec;
    if (S && P && P.id === id) rechargeEditeur(); else { z.textContent = ftMsg; bt.disabled = false; }
    return;
  }
}
"""
}
