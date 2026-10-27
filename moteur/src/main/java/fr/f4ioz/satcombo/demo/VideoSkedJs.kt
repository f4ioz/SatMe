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
 * The Sked's « Globe animé » video: the page's own WebGL globe (relief, clouds,
 * landmarks, the cartoon's scenery, the look and camera chosen) filmed in the
 * browser. Each image of the replay, stepped at a fixed pace, is laid with both
 * skies and the caption band of SatMe-PC's own sked video on a canvas recorded
 * by MediaRecorder (MP4 when the browser can, else WebM), then sent to
 * `api/sked/video/navigateur`, where ffmpeg makes it an MP4 beside the others.
 * Put into [PagePc.HTML] after the Sked's code (KT, kF, instantSked, Orbites);
 * no Kotlin template here (no dollar sign at all in this string).
 */
object VideoSkedJs {

    const val JS = """
// ------------------------------------------------------------------ the Sked's « Globe animé » video (VideoSkedJs)
// The real globe filmed in the browser: 24 s of the pass, the window's middle held 3 s, 30 images a second.
const KF_FPS = 30, KF_DUREE = 24, KF_FIN = 3, KF_AVANT = 30, KF_BANDE = 86;
const KF_MIMES = ['video/mp4;codecs=avc1.640028', 'video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
let KF = null, kfIcone = null, kfPourquoi = '';
/** Why this browser cannot film the globe; '' when it can. */
function kfPossible() {
  if (window.SATME_TELEPHONE) return 'le globe 3D n’est que sur le PC';
  if (typeof MediaRecorder === 'undefined' || !MediaRecorder.isTypeSupported) return 'ce navigateur ne sait pas enregistrer de vidéo (MediaRecorder)';
  if (!HTMLCanvasElement.prototype.captureStream) return 'ce navigateur ne sait pas filmer un dessin (captureStream)';
  if (!KF_MIMES.some(m => MediaRecorder.isTypeSupported(m))) return 'ce navigateur n’enregistre ni MP4 ni WebM';
  try {
    const c = document.createElement('canvas'), gl = c.getContext('webgl2') || c.getContext('webgl');
    if (!gl) return 'pas de WebGL : le globe 3D ne peut pas être filmé';
    const p = gl.getExtension('WEBGL_lose_context'); if (p) p.loseContext();
  } catch (x) { return 'pas de WebGL : le globe 3D ne peut pas être filmé'; }
  return '';
}
/** The size asked: 16:9 1920×1080 or 1280×720, 9:16 1080×1920; [s] from the layout's units (1280×720 or 720×1280). */
function kfTaille(f) {
  return f === 'telephone' ? { W: 1080, H: 1920, v: true, s: 1.5 } : f === 'paysage720' ? { W: 1280, H: 720, v: false, s: 1 } : { W: 1920, H: 1080, v: false, s: 1.5 };
}
/** The 720p choice offered only for the animated globe (SatMe's own video is 1280×720 anyway). */
function kfFormats() {
  const s = g('k-video-format'), o = s.querySelector('option[value="paysage720"]'), anime = kfAnime();
  o.hidden = o.disabled = !anime;
  if (!anime && s.value === 'paysage720') s.value = 'paysage';
}
function kfAnime() { return kVue === 'globe' && g('k-anime').checked && !g('k-anime').disabled; }
(function () {
  const c = g('k-anime'), l = g('k-anime-l'), pourquoi = kfPourquoi = kfPossible();
  try { c.checked = localStorage.getItem('sked-video-anime') === '1'; if (localStorage.getItem('sked-video-format') === 'paysage720') g('k-video-format').value = 'paysage720'; } catch (x) {}
  if (pourquoi) {
    c.checked = false; c.disabled = true; l.style.opacity = '.55';
    l.title = 'Indisponible ici : ' + pourquoi + '. Le bouton Vidéo fait alors celle que dessine SatMe.';
  }
  c.onchange = () => { try { localStorage.setItem('sked-video-anime', c.checked ? '1' : '0'); } catch (x) {} kfFormats(); };
  kfFormats();
})();
function kfLogo() {
  if (kfIcone) return Promise.resolve(kfIcone);
  return new Promise(ok => { const i = new Image(); i.onload = () => { kfIcone = i; ok(i); }; i.onerror = () => ok(null); i.src = 'icone-192.png'; });
}
const kfHeure = ms => new Date(ms).toISOString().slice(11, 19);
const kfDate = ms => { const s = new Date(ms).toISOString(); return s.slice(8, 10) + '/' + s.slice(5, 7) + '/' + s.slice(0, 4) + ' ' + s.slice(11, 16); };
/** The controls held still while it films (the replay, the window, the view, the settings, the globe under the mouse). */
function kfVerrou(on) {
  ['k-video', 'k-video-format', 'k-joue', 'k-curseur', 'k-b-carte', 'k-b-globe'].forEach(id => { const e = g(id); if (e) e.disabled = on; });
  g('k-anime').disabled = on || !!kfPourquoi;
  document.querySelectorAll('#k-liste button, #k-reglages-c select, #k-reglages-c input').forEach(e => { e.disabled = on; });
  g('k-globe').style.pointerEvents = on ? 'none' : '';
}
/** « Vidéo » with « Globe animé » ticked: the replay filmed from the start. */
async function filmeSked() {
  if (KF || !KT || kVue !== 'globe') return;
  if (kfPourquoi) { txt('k-video-etat', kfPourquoi); return; }
  const fmt = g('k-video-format').value, T = kfTaille(fmt), vw = T.v ? 720 : 880, vh = T.v ? 780 : 720;
  const sortie = document.createElement('canvas'); sortie.width = T.W; sortie.height = T.H;
  const mime = KF_MIMES.find(m => MediaRecorder.isTypeSupported(m));
  await kfLogo();
  const P = KT.points, t0 = P[0][0], t1 = P[P.length - 1][0];
  const f = { T: T, fmt: fmt, vw: vw, vh: vh, x: sortie.getContext('2d'), sortie: sortie, mime: mime, kt: KT, k: -KF_AVANT, N: KF_DUREE * KF_FPS, F: KF_FIN * KF_FPS,
    milieu: Math.max(0, Math.min(1, ((KT.w.de + KT.w.a) / 2 - t0) / Math.max(1, t1 - t0))), morceaux: [], depart: 0, fini: false,
    moi: (E && E.indicatif ? E.indicatif : E && E.locator ? E.locator : 'Moi').toUpperCase() };
  // The panel: the picture as it is made, how far, a way out.
  const pn = document.createElement('div');
  Object.assign(pn.style, { position: 'fixed', right: '16px', bottom: '16px', zIndex: 60, width: '340px', maxWidth: 'calc(100vw - 32px)', boxSizing: 'border-box', padding: '10px 12px',
    background: '#0B1016', color: '#E8F0F7', border: '1px solid #2A3A48', borderRadius: '10px', boxShadow: '0 6px 24px rgba(0,0,0,.45)', font: '13px/1.4 system-ui, sans-serif' });
  pn.innerHTML = '<div style="font-weight:700;margin-bottom:6px">Vidéo du globe animé · ' + T.W + '×' + T.H + '</div>' +
    '<div class="kf-apercu" style="display:flex;justify-content:center;background:#000;border-radius:6px;overflow:hidden"></div>' +
    '<progress class="kf-prog" max="1" value="0" style="width:100%;margin-top:8px"></progress>' +
    '<div class="kf-txt">préparation…</div>' +
    '<div class="kf-avis" style="color:#FFB454">Gardez cet onglet Sked visible : ailleurs (autre onglet, fenêtre réduite), le navigateur met l’enregistrement en pause.</div>' +
    '<div style="text-align:right;margin-top:6px"><button class="kf-annule">Annuler</button></div>';
  Object.assign(sortie.style, T.v ? { height: '240px', width: 'auto' } : { width: '100%', height: 'auto' });
  pn.querySelector('.kf-apercu').appendChild(sortie);
  pn.querySelector('.kf-annule').onclick = () => kfFin(false);
  document.body.appendChild(pn); f.panneau = pn;
  f.flux = sortie.captureStream(0); f.piste = f.flux.getVideoTracks()[0];
  try { f.rec = new MediaRecorder(f.flux, { mimeType: mime, videoBitsPerSecond: T.W * T.H >= 1920 * 1080 ? 12000000 : 6000000 }); }
  catch (x) { pn.remove(); txt('k-video-etat', 'enregistrement impossible : ' + (x && x.message || x)); return; }
  f.rec.ondataavailable = e => { if (e.data && e.data.size) f.morceaux.push(e.data); };
  KF = f;
  kJoue = false; txt('k-joue', '▶'); kF = 0; kfVerrou(true);
  document.addEventListener('visibilitychange', kfVisibilite);
  // The globe drawn at the video's size, its scenery on the video's clock, each image handed over at once.
  f.film = { w: vw, h: vh - KF_BANDE, k: T.s, now: performance.now(), apres: kfImage };
  if (!Orbites.skedFilm(f.film)) { kfFin(false); txt('k-video-etat', 'le globe n’est pas prêt'); return; }
  dessineSked();
}
function kfVisibilite() {
  const f = KF; if (!f || !f.panneau) return;
  f.panneau.querySelector('.kf-avis').textContent = document.hidden ? 'Onglet caché : enregistrement en pause, il reprendra ici.' :
    'Gardez cet onglet Sked visible : ailleurs (autre onglet, fenêtre réduite), le navigateur met l’enregistrement en pause.';
  // Back: the pace taken again from here, not caught up in a rush.
  if (!document.hidden && f.k > 0) f.depart = performance.now() - f.k * 1000 / KF_FPS;
}
/** Right after the globe drew an image: laid in the video when its time has come, then the next instant set. */
function kfImage(c) {
  const f = KF; if (!f || f.fini) return;
  const ici = performance.now();
  if (f.k < 0) {
    // A second for the camera to settle on the start (not filmed).
    f.k++; f.film.now += 1000 / KF_FPS;
    if (f.k === 0) { f.depart = ici; f.rec.start(1000); }
    return;
  }
  // Ahead of the pace (a fast PC): the same image drawn again; behind it, every image all the same, the server times them again.
  if (ici < f.depart + f.k * 1000 / KF_FPS) return;
  kfCompose(c, f);
  if (f.piste.requestFrame) f.piste.requestFrame();
  f.k++;
  const n = f.N + f.F;
  if (f.k % 10 === 0 || f.k >= n) {
    f.panneau.querySelector('.kf-prog').value = f.k / n;
    f.panneau.querySelector('.kf-txt').textContent = 'enregistrement ' + Math.round(100 * f.k / n) + ' % · ' + (f.k / KF_FPS).toFixed(1) + ' s sur ' + (n / KF_FPS) + ' s';
  }
  if (f.k >= n) { kfFin(true); return; }
  kF = f.k < f.N ? f.k / (f.N - 1) : f.milieu;
  f.film.now += 1000 / KF_FPS;
  dessineSked();
}
/** One image: the globe under the band, the clock and both skies beside it (16:9) or under it (9:16), as SatMe's own sked video. */
function kfCompose(c, f) {
  const x = f.x, s = f.T.s;
  x.setTransform(1, 0, 0, 1, 0, 0);
  x.fillStyle = '#0B1016'; x.fillRect(0, 0, f.T.W, f.T.H);
  x.drawImage(c, 0, Math.round(KF_BANDE * s), Math.round(f.vw * s), Math.round((f.vh - KF_BANDE) * s));
  x.setTransform(s, 0, 0, s, 0, 0);
  x.textAlign = 'left'; x.textBaseline = 'alphabetic';
  const cour = instantSked(kF);
  kfBande(x, f); kfPanneau(x, f, cour);
}
function kfBande(x, f) {
  const k = f.kt, w = k.w, lui = k.loc, larg = f.vw - 200;
  x.fillStyle = 'rgba(11,16,22,.84)'; x.fillRect(0, 0, f.vw, KF_BANDE);
  x.font = 'bold 24px system-ui, sans-serif'; x.fillStyle = '#FFFFFF'; x.fillText(k.nom + ' · sked ' + f.moi + ' ↔ ' + lui, 20, 30, larg);
  x.font = '17px system-ui, sans-serif'; x.fillStyle = '#9AA7B4';
  x.fillText('fenêtre commune ' + kfDate(w.de) + ' → ' + kfHeure(w.a).slice(0, 5) + ' UTC · ' + Math.floor((w.a - w.de) / 60000) + ' min ' + Math.floor((w.a - w.de) / 1000 % 60) + ' s', 20, 54, larg);
  x.font = '15px system-ui, sans-serif';
  x.fillText('él. max ' + f.moi + ' ' + Math.round(w.elMoi) + '° · ' + lui + ' ' + Math.round(w.elAutre) + '°', 20, 76, larg);
  kfMarque(x, f.vw - 16, 42, 5);
}
/** SatMe's mark (its icon, its name), right-aligned on [xd], as on every picture SatMe exports. */
function kfMarque(x, xd, base, u) {
  x.font = 'bold ' + (4.6 * u * 1.33).toFixed(1) + 'px system-ui, sans-serif';
  const cote = 6.4 * u, ecart = u, gauche = xd - (cote + ecart + x.measureText('SatMe').width), haut = base - 1.6 * u - cote / 2;
  if (kfIcone) {
    x.save(); x.beginPath();
    if (x.roundRect) x.roundRect(gauche, haut, cote, cote, cote * .24); else x.rect(gauche, haut, cote, cote);
    x.clip(); x.drawImage(kfIcone, gauche, haut, cote, cote); x.restore();
  }
  x.fillStyle = 'rgba(255,255,255,.84)'; x.fillText('SatMe', gauche + cote + ecart, base);
}
function kfPanneau(x, f, c) {
  const v = f.T.v, x0 = v ? 0 : f.vw, y0 = v ? f.vh : 0, L = v ? 720 : 1280, H = v ? 1280 : 720, lui = f.kt.loc;
  x.fillStyle = '#0B1016'; x.fillRect(x0, y0, L - x0, H - y0);
  x.font = 'bold 40px ui-monospace, Menlo, Consolas, monospace'; x.fillStyle = '#FFFFFF'; x.fillText(kfHeure(c[0]), x0 + 30, y0 + 56);
  x.font = '14px system-ui, sans-serif'; x.fillStyle = '#9AA7B4'; x.fillText('UTC', x0 + 230, y0 + 56);
  const moi = c[2] >= 0, il = c[4] >= 0;
  x.font = 'bold 19px system-ui, sans-serif'; x.fillStyle = moi && il ? '#5BD68A' : '#9AA7B4';
  x.fillText(moi && il ? 'vous le voyez tous deux' : moi ? f.moi + ' seul le voit' : il ? lui + ' seul le voit' : 'personne ne le voit', x0 + 30, y0 + 90);
  if (v) { kfCiel(x, f, 40, y0 + 125, 300, f.moi, 1, 2, c); kfCiel(x, f, 380, y0 + 125, 300, lui, 3, 4, c); }
  else { kfCiel(x, f, x0 + 60, 125, 260, f.moi, 1, 2, c); kfCiel(x, f, x0 + 60, 425, 260, lui, 3, 4, c); }
}
/** A sky: the visible arc, the common window green, the satellite now. */
function kfCiel(x, f, x0, y0, taille, nom, iAz, iEl, c) {
  const r = taille / 2 - 22, cx = x0 + taille / 2, cy = y0 + taille / 2 + 4, w = f.kt.w;
  x.font = 'bold 16px system-ui, sans-serif'; x.fillStyle = '#E8F0F7'; x.fillText(nom, x0, y0 + 2);
  x.strokeStyle = '#22303B'; x.lineWidth = 1.2;
  for (const el of [0, 30, 60]) { const rr = r * (1 - el / 90); x.beginPath(); x.arc(cx, cy, rr, 0, 2 * Math.PI); x.stroke(); }
  x.beginPath(); x.moveTo(cx - r, cy); x.lineTo(cx + r, cy); x.moveTo(cx, cy - r); x.lineTo(cx, cy + r); x.stroke();
  x.font = '13px system-ui, sans-serif'; x.fillStyle = '#9AA7B4';
  x.fillText('N', cx - 4, cy - r - 4); x.fillText('S', cx - 4, cy + r + 15); x.fillText('E', cx + r + 4, cy + 5); x.fillText('O', cx - r - 14, cy + 5);
  const xy = (az, el) => { const rr = r * (1 - Math.max(0, Math.min(90, el)) / 90), a = az * Math.PI / 180; return [cx + rr * Math.sin(a), cy - rr * Math.cos(a)]; };
  const arc = (filtre, couleur, lw) => {
    x.strokeStyle = couleur; x.lineWidth = lw; x.lineCap = 'round'; x.lineJoin = 'round'; x.beginPath();
    let dedans = false;
    for (const q of f.kt.points) {
      if (q[iEl] < 0 || !filtre(q)) { dedans = false; continue; }
      const p = xy(q[iAz], q[iEl]); if (dedans) x.lineTo(p[0], p[1]); else x.moveTo(p[0], p[1]); dedans = true;
    }
    x.stroke();
  };
  arc(() => true, 'rgba(63,224,200,.43)', 2);
  arc(q => q[0] >= w.de && q[0] <= w.a, '#5BD68A', 5);
  const az = c[iAz], el = c[iEl];
  x.font = '15px system-ui, sans-serif';
  if (el >= 0) {
    const p = xy(az, el);
    x.fillStyle = '#FFB454'; x.beginPath(); x.arc(p[0], p[1], 8, 0, 2 * Math.PI); x.fill();
    x.fillStyle = '#E8F0F7'; x.fillText('az ' + Math.round(az) + '° · él ' + Math.round(el) + '°', x0, y0 + taille + 22);
  } else { x.fillStyle = '#9AA7B4'; x.fillText('sous l’horizon', x0, y0 + taille + 22); }
}
/** Over (or given up): the globe back to the screen, the replay going again; the film sent to be made an MP4. */
function kfFin(ok) {
  const f = KF; if (!f || f.fini) return;
  f.fini = true;
  Orbites.skedFilm(null);
  document.removeEventListener('visibilitychange', kfVisibilite);
  const apres = () => kfApres(f, ok);
  if (f.rec && f.rec.state !== 'inactive') { f.rec.onstop = apres; f.rec.stop(); } else apres();
}
async function kfApres(f, ok) {
  try { f.piste.stop(); } catch (x) {}
  if (f.panneau) f.panneau.remove();
  KF = null; kfVerrou(false);
  kJoue = true; kAvant = 0; txt('k-joue', '⏸'); requestAnimationFrame(boucleSked);
  if (!ok) { txt('k-video-etat', 'vidéo annulée'); return; }
  const blob = new Blob(f.morceaux, { type: f.mime.split(';')[0] });
  if (!blob.size) { txt('k-video-etat', 'le navigateur n’a rien enregistré'); return; }
  txt('k-video-etat', 'envoi de la vidéo (' + (blob.size / 1048576).toFixed(1) + ' Mo)…');
  const k = f.kt, q = 'sat=' + enc(k.nom) + '&autre=' + enc(k.loc) + '&de=' + k.w.de + '&format=' + (f.T.v ? 'telephone' : 'paysage') + '&fps=' + KF_FPS + '&images=' + (f.N + f.F);
  let j = null;
  try { j = await (await fetch('api/sked/video/navigateur?' + q, { method: 'POST', headers: { 'Content-Type': blob.type }, body: blob })).json(); } catch (x) {}
  if (j && j.ok) { kfSuis(blob); return; }
  kfBrut(blob, (j && j.erreur) || 'envoi impossible');
}
/** SatMe-PC making it an MP4: how far, then its link (or the browser's own file). */
async function kfSuis(blob) {
  let e = null;
  try { e = await (await fetch('api/sked/video')).json(); } catch (x) { kfBrut(blob, 'SatMe-PC ne répond plus'); return; }
  if (e.enCours) { txt('k-video-etat', 'conversion en MP4 ' + Math.round(e.progression * 100) + ' %'); setTimeout(() => kfSuis(blob), 500); return; }
  if (e.erreur || !e.nom) { kfBrut(blob, e.erreur || 'conversion impossible'); return; }
  g('k-video-etat').innerHTML = '<a style="color:var(--cyan)" href="sked-video/' + encodeURIComponent(e.nom) + '">' + esc(e.nom) + '</a>';
}
/** Sending or making the MP4 failed: the file as the browser recorded it, to keep all the same. */
function kfBrut(blob, raison) {
  const n = 'SatMe_Sked_globe-anime.' + (blob.type.indexOf('mp4') >= 0 ? 'mp4' : 'webm');
  g('k-video-etat').innerHTML = esc(raison) + ' · <a style="color:var(--cyan)" download="' + n + '" href="' + URL.createObjectURL(blob) + '">la vidéo telle qu’enregistrée</a>';
}
"""
}
