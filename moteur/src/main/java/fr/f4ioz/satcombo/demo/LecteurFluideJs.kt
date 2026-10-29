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
 * « Lecture fluide » of SatMe-PC's DATV tab, in the page: the received picture and its sound together in a
 * `<video>`, fed through Media Source Extensions from `api/datv/fluide.mp4` (fragmented MP4 made by SatMe-PC's
 * LecteurFluideDatvPc), instead of the JPEG stream and the sound played apart.
 *
 * - Its box (« Lecture fluide »), its `<video>` and its sound button are added by the script itself beside the
 *   others (the JPEG `<img>` stays, and comes back whenever the fluid playing is off, not possible or failing).
 * - Kept near the live edge: more than 1.5 s behind, it jumps; a little behind, it plays 8 % faster; the played
 *   part trimmed (10 s kept). The stream ending (another station, another coding, ffmpeg restarted): asked again
 *   with a new MediaSource (a new init segment).
 * - The sound in the page, at its own volume, lowered while « Traduction parlée » speaks (paBaisse); a browser
 *   refusing to start it by itself plays muted, its button saying so. The page tells SatMe-PC whether it plays
 *   the sound (SatMe-PC's own player quiet meanwhile, when the page is on that PC).
 * - Hidden or another tab, and silent: the stream is let go.
 *
 * dFlTic(D, img), from ticDatv: true when it shows the picture (the JPEG one then left alone).
 * dFlSonJoue(): the page plays the DATV sound now. dFlBaisse(on, k): the sound lowered (k) or back.
 * Plain constant: no Kotlin template in here, and the JS uses no template literals.
 */
object LecteurFluideJs {

    const val CODE = """
const DFL = { on: false, son: true, vol: 1, baisse: 1, ok: false, demo: false, id: Math.random().toString(36).slice(2, 10),
  ctrl: null, ms: null, sb: null, file: [], joue: false, bloque: false, echecs: 0, panneJusque: 0, essaiMs: 0, etatMs: 0,
  msg: '', mode: '', retard: null, sauts: 0, pret: false };
try {
  DFL.on = localStorage.getItem('satme.datvFluide') === '1';
  DFL.son = localStorage.getItem('satme.datvFluideSon') !== '0';
  const vv = parseFloat(localStorage.getItem('satme.datvFluideVol'));
  if (vv >= 0 && vv <= 1) DFL.vol = vv;
} catch (x) {}
function dflPose(k, val) { try { localStorage.setItem(k, val); } catch (x) {} }
function dflSupporte() {
  return !!(window.MediaSource && MediaSource.isTypeSupported && MediaSource.isTypeSupported('video/mp4; codecs="avc1.4d401f,mp4a.40.2"'));
}
/** Its box, its video and its sound button, added once beside the page's own. */
function dflPrepare() {
  if (DFL.pret) return;
  const img = g('d-video'), son = g('d-son');
  if (!img || !son) return;
  DFL.pret = true;
  const st = document.createElement('style');
  st.textContent = '#d-video-f { width:100%; height:100%; object-fit:contain; display:none; background:#000; }' +
    '.d-fl-son { position:absolute; left:8px; bottom:8px; z-index:3; display:none; align-items:center; gap:6px; padding:1px 6px; background:rgba(0,0,0,.55); border-radius:6px; opacity:.6; }' +
    '.d-fl-son:hover, .d-fl-son:focus-within, .d-fl-son.bloque { opacity:1; }' +
    '.d-fl-son button { background:none; border:0; color:#fff; font-size:16px; line-height:1.3; padding:0 2px; cursor:pointer; }' +
    '.d-fl-son input { width:80px; } .d-fl-son .petit { color:#fff; }';
  document.head.appendChild(st);
  const vid = document.createElement('video');
  vid.id = 'd-video-f'; vid.playsInline = true; vid.muted = true; vid.setAttribute('aria-label', 'Image et son DATV reçus');
  vid.addEventListener('playing', () => { DFL.joue = true; DFL.echecs = 0; });
  img.insertAdjacentElement('afterend', vid);
  const b = document.createElement('div');
  b.className = 'd-fl-son'; b.id = 'd-fl-son';
  b.innerHTML = '<button type="button" id="d-fl-muet" onclick="dflBasculeSon()"></button>' +
    '<input type="range" id="d-fl-vol" min="0" max="100" title="Volume du son DATV dans ce navigateur" oninput="dflVolume(this.value)">' +
    '<span class="petit" id="d-fl-bloque" hidden>cliquer pour le son</span>';
  img.parentNode.appendChild(b);
  g('d-fl-vol').value = Math.round(DFL.vol * 100);
  const l = document.createElement('label');
  l.className = 'petit'; l.id = 'd-fl-l'; l.hidden = true;
  l.title = 'L’image et le son ensemble dans ce navigateur, comme une vraie vidéo (25 images par seconde, son synchronisé), au lieu des images JPEG une à une et du son à part. ' +
    'Le H.264 reçu passe tel quel ; le H.265 ou le MPEG-2 sont réencodés en H.264 par SatMe-PC. Retard d’environ une demi-seconde. ' +
    'Le son se règle sur l’image (bouton en bas à gauche) ; sur ce PC, celui de « Son sur ce PC » se tait pendant ce temps (vumètre, sous-titres et traduction continuent). ' +
    'Sans prise en charge par le navigateur, ou en cas d’échec : les images JPEG, comme avant.';
  l.innerHTML = '<input type="checkbox" id="d-fluide" onchange="dflCoche(this.checked)"> Lecture fluide (vidéo et son) <span id="d-fl-etat"></span>';
  son.closest('label').insertAdjacentElement('afterend', l);
  g('d-fluide').checked = DFL.on;
  dflBouton();
}
function dflCoche(on) {
  DFL.on = on; DFL.echecs = 0; DFL.panneJusque = 0;
  dflPose('satme.datvFluide', on ? '1' : '0');
  if (!on) dflArrete();
  ticDatv();
}
function dflSonVoulu() { return DFL.son && !DFL.bloque; }
function dflSonServeur(on) {
  if (!DFL.ctrl) return;
  fetch('api/datv/fluide/son?id=' + DFL.id + '&on=' + (on ? 1 : 0), { method: 'POST' }).catch(() => {});
}
function dflBasculeSon() {
  const vid = g('d-video-f');
  if (DFL.bloque || !DFL.son) { DFL.son = true; DFL.bloque = false; } else DFL.son = false;
  dflPose('satme.datvFluideSon', DFL.son ? '1' : '0');
  if (vid) { vid.muted = !DFL.son; if (DFL.son && vid.paused && DFL.ms) vid.play().catch(() => {}); }
  dflSonServeur(DFL.son);
  dflBouton();
}
function dflVolume(x) {
  DFL.vol = Math.max(0, Math.min(1, x / 100));
  dflPose('satme.datvFluideVol', String(DFL.vol));
  const vid = g('d-video-f'); if (vid) vid.volume = DFL.vol * DFL.baisse;
}
function dflBouton() {
  const m = g('d-fl-muet'); if (!m) return;
  const muet = !DFL.son || DFL.bloque;
  m.textContent = muet ? '🔇' : '🔊';
  m.title = DFL.bloque ? 'Le navigateur n’a pas laissé démarrer le son tout seul : cliquer pour l’entendre' :
    muet ? 'Son coupé dans ce navigateur : cliquer pour l’entendre' : 'Couper le son dans ce navigateur';
  g('d-fl-bloque').hidden = !DFL.bloque;
  g('d-fl-son').classList.toggle('bloque', DFL.bloque);
}
/** The state SatMe-PC gives (present, the demonstration), every 3 s; the box shown when it answers. */
async function dflEtat() {
  let j = null;
  try { j = await (await fetch('api/datv/fluide')).json(); } catch (x) {}
  DFL.ok = !!(j && j.ok);
  DFL.demo = !!(j && j.demo);
  const l = g('d-fl-l'); if (!l) return;
  l.hidden = !DFL.ok || !!window.SATME_TELEPHONE;
  const sup = dflSupporte();
  g('d-fluide').disabled = !sup;
  if (!sup) l.title = 'Ce navigateur ne sait pas lire de vidéo H.264 en direct (Media Source Extensions) : les images JPEG seulement.';
}
/** From ticDatv: true when the fluid playing shows the picture (the JPEG one then left alone). */
function dFlTic(D, img) {
  dflPrepare();
  if (!DFL.pret) return false;
  const t = Date.now();
  if (t - DFL.etatMs > 3000) { DFL.etatMs = t; dflEtat(); }
  const voulu = DFL.on && DFL.ok && dflSupporte() && !window.SATME_TELEPHONE && (!MD || dDirect) &&
    ((D.actif && D.images) || DFL.demo) && t >= DFL.panneJusque;
  dflEtatTexte(voulu);
  if (!voulu) { dflArrete(); return false; }
  if (img.getAttribute('src') || img.style.display === 'block') { img.removeAttribute('src'); img.style.display = 'none'; delete img.dataset.vign; }
  if (!DFL.ctrl && t - DFL.essaiMs > 1500) dflOuvre();
  g('d-video-f').style.display = 'block';
  g('d-fl-son').style.display = 'flex';
  g('d-vide').hidden = DFL.joue;
  if (!DFL.joue) g('d-vide').textContent = DFL.msg || 'lecture fluide : en attente de l’image…';
  g('d-direct').hidden = !(MD && D.actif && D.images);
  return true;
}
function dflEtatTexte(voulu) {
  const e = g('d-fl-etat'); if (!e) return;
  let s = '';
  if (DFL.on && voulu && DFL.joue) s = '· ' + (DFL.mode === 'copie' ? 'H.264 tel quel' : 'réencodé en H.264') +
    (DFL.retard != null ? ' · retard ' + DFL.retard.toFixed(1).replace('.', ',') + ' s' : '');
  else if (DFL.on && Date.now() < DFL.panneJusque) s = '· ' + (DFL.msg || 'indisponible') + ' (images JPEG pour l’instant)';
  if (e.textContent !== s) e.textContent = s;
}
async function dflOuvre() {
  DFL.essaiMs = Date.now();
  const ctrl = new AbortController();
  DFL.ctrl = ctrl; DFL.joue = false; DFL.msg = 'lecture fluide : connexion…';
  let rep;
  try { rep = await fetch('api/datv/fluide.mp4?id=' + DFL.id + '&son=' + (dflSonVoulu() ? 1 : 0), { signal: ctrl.signal, cache: 'no-store' }); }
  catch (x) { return dflFin(ctrl, 'lecture fluide : pas de réponse', true); }
  if (DFL.ctrl !== ctrl) return;
  if (!rep.ok || !rep.body) {
    let e = ''; try { e = (await rep.json()).erreur || ''; } catch (x) {}
    return dflFin(ctrl, 'lecture fluide : ' + (e || 'indisponible'), true);
  }
  const codecs = rep.headers.get('X-Satme-Codecs') || 'avc1.640028,mp4a.40.2', type = 'video/mp4; codecs="' + codecs + '"';
  DFL.mode = rep.headers.get('X-Satme-Mode') || '';
  if (!MediaSource.isTypeSupported(type)) return dflFin(ctrl, 'ce navigateur ne lit pas ' + codecs, true, 60000);
  const ms = new MediaSource(), vid = g('d-video-f');
  DFL.ms = ms; DFL.file = []; DFL.sb = null;
  vid.src = URL.createObjectURL(ms);
  await new Promise(r => ms.addEventListener('sourceopen', r, { once: true }));
  if (DFL.ctrl !== ctrl) return;
  let sb;
  try { sb = ms.addSourceBuffer(type); } catch (x) { return dflFin(ctrl, 'ce navigateur ne lit pas ' + codecs, true, 60000); }
  sb.mode = 'segments';
  sb.addEventListener('updateend', dflPousse);
  sb.addEventListener('error', () => dflFin(ctrl, 'lecture fluide : erreur de décodage, reprise…'));
  DFL.sb = sb;
  vid.muted = !dflSonVoulu(); vid.volume = DFL.vol * DFL.baisse;
  const lecteur = rep.body.getReader();
  try {
    while (DFL.ctrl === ctrl) {
      const r = await lecteur.read();
      if (r.done) break;
      DFL.file.push(r.value);
      dflPousse();
    }
  } catch (x) {}
  dflFin(ctrl, 'lecture fluide : flux interrompu, reprise…');
}
/** The bytes received appended (the SourceBuffer takes them in any cut), the played part trimmed. */
function dflPousse() {
  const sb = DFL.sb, vid = g('d-video-f');
  if (!sb || sb.updating || !DFL.ms || DFL.ms.readyState !== 'open') return;
  try {
    if (sb.buffered.length && vid.currentTime - sb.buffered.start(0) > 20) { sb.remove(0, vid.currentTime - 10); return; }
  } catch (x) {}
  if (!DFL.file.length) return;
  let n = 0; for (const b of DFL.file) n += b.length;
  const u = new Uint8Array(n); let o = 0;
  for (const b of DFL.file) { u.set(b, o); o += b.length; }
  DFL.file = [];
  try { sb.appendBuffer(u); }
  catch (x) {
    if (x && x.name === 'QuotaExceededError') { DFL.file.unshift(u); try { sb.remove(0, Math.max(0, vid.currentTime - 2)); } catch (y) {} }
    else dflFin(DFL.ctrl, 'lecture fluide : erreur de décodage, reprise…');
  }
}
/** Stopped (or ended): a failure counted; three in a row, or a codec this browser cannot play, and the JPEG pictures take over a while. */
function dflFin(ctrl, msg, panne, ms) {
  if (!ctrl || DFL.ctrl !== ctrl) return;
  try { ctrl.abort(); } catch (x) {}
  DFL.ctrl = null; DFL.sb = null; DFL.file = []; DFL.retard = null;
  const vid = g('d-video-f');
  if (DFL.ms) { try { if (DFL.ms.readyState === 'open') DFL.ms.endOfStream(); } catch (x) {} DFL.ms = null; }
  if (vid) { const s = vid.src; vid.removeAttribute('src'); try { vid.load(); } catch (x) {} if (s && s.startsWith('blob:')) URL.revokeObjectURL(s); }
  DFL.joue = false;
  DFL.msg = msg || '';
  if (panne) DFL.echecs++;
  if (ms || DFL.echecs >= 3) { DFL.panneJusque = Date.now() + (ms || 15000); DFL.echecs = 0; }
}
function dflArrete() {
  if (DFL.ctrl) dflFin(DFL.ctrl, '');
  const vid = g('d-video-f'); if (vid) vid.style.display = 'none';
  const b = g('d-fl-son'); if (b) b.style.display = 'none';
}
function dflJoue() {
  const vid = g('d-video-f');
  vid.muted = !dflSonVoulu(); vid.volume = DFL.vol * DFL.baisse;
  const p = vid.play();
  if (p) p.catch(e => {
    // Sound refused before the page was touched: muted then, the button says so.
    if (e && e.name === 'NotAllowedError' && !vid.muted) {
      DFL.bloque = true; vid.muted = true; dflSonServeur(false); dflBouton();
      vid.play().catch(() => {});
    }
  });
}
/** The page plays the DATV sound now (« Traduction parlée » then speaks here too). */
function dFlSonJoue() { const vid = g('d-video-f'); return !!(DFL.ctrl && DFL.joue && vid && !vid.muted); }
function dFlBaisse(on, k) {
  DFL.baisse = on ? (k != null ? k : 0.25) : 1;
  const vid = g('d-video-f'); if (vid) vid.volume = DFL.vol * DFL.baisse;
}
// Four times a second: near the live edge, started, let go when nobody looks nor listens.
setInterval(() => {
  const vid = g('d-video-f');
  if (!vid || !DFL.ctrl) return;
  if ((document.hidden || g('v-datv').hidden) && !dFlSonJoue()) { dflArrete(); return; }
  if (!DFL.ms) return;
  const bb = vid.buffered;
  if (!bb.length) return;
  const deb = bb.start(bb.length - 1), fin = bb.end(bb.length - 1), ret = fin - vid.currentTime;
  if (vid.currentTime < deb || ret > 1.5) { vid.currentTime = Math.max(deb, fin - 0.4); DFL.sauts++; }
  else vid.playbackRate = ret > 0.8 ? 1.08 : ret < 0.15 ? 0.96 : 1;
  DFL.retard = Math.max(0, fin - vid.currentTime);
  if (vid.paused && fin - deb > 0.2) dflJoue();
}, 250);
"""
}
