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
 * The page the audience sees.
 *
 * **Self-contained, and that is no detail.** No external resource: no
 * downloaded font, no library, no image. The phone's hotspot has no internet —
 * anything not inside this string would simply never appear.
 *
 * The polar plot is drawn by hand on a canvas: a charting library would weigh
 * a hundred times this page to draw one circle and a dot.
 */
object PageDemo {

    val HTML: String = """
<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Radio par satellite — en direct</title>
<style>
  :root { color-scheme:dark; --fond:#070B10; --carte:#111A24; --bord:#1E2A38;
          --cyan:#3FE0C8; --ambre:#FFB454; --rouge:#E5484D; --gris:#8496A8; }
  * { box-sizing:border-box; }
  body { margin:0; background:
           radial-gradient(1200px 600px at 50% -10%, #12212F 0%, var(--fond) 60%);
         color:#E8F0F7; font-family:system-ui,-apple-system,sans-serif;
         min-height:100vh; }
  .bandeau { padding:18px 18px 10px; text-align:center; }
  .sur { color:var(--cyan); font-size:11px; letter-spacing:2.2px;
         text-transform:uppercase; font-weight:700; }
  h1 { margin:4px 0 0; font-size:20px; font-weight:800; letter-spacing:.2px; }
  .station { color:var(--gris); font-size:13px; margin-top:4px; }
  .station b { color:#E8F0F7; }
  main { max-width:520px; margin:0 auto; padding:0 16px 30px; }
  .sat { text-align:center; margin:10px 0 2px; }
  .sat .nom { font-size:27px; font-weight:800; color:var(--cyan);
              letter-spacing:.5px; }
  .sat .etat { color:var(--gris); font-size:12px; margin-top:2px; }
  canvas { width:100%; display:block; margin:6px auto 4px; }
  .grille { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-top:8px; }
  .c { background:var(--carte); border:1px solid var(--bord); border-radius:14px;
       padding:11px 13px; }
  .lib { color:var(--gris); font-size:10px; text-transform:uppercase;
         letter-spacing:1.2px; }
  .val { font-size:20px; font-weight:700; margin-top:3px;
         font-variant-numeric:tabular-nums; }
  .rx { color:var(--cyan); } .tx { color:var(--ambre); }
  .emission { background:var(--rouge); color:#fff; text-align:center;
              border-radius:14px; padding:12px; font-weight:800; letter-spacing:2px;
              margin-top:10px; display:none; animation:bat 1s infinite; }
  .emission.on { display:block; }
  @keyframes bat { 50% { opacity:.55; } }
  h2 { font-size:12px; text-transform:uppercase; letter-spacing:1.4px;
       color:var(--gris); margin:22px 0 8px; }
  .qso { background:var(--carte); border:1px solid var(--bord); border-radius:14px;
         overflow:hidden; }
  .q { display:flex; align-items:center; gap:10px; padding:10px 13px;
       border-bottom:1px solid var(--bord); }
  .q:last-child { border-bottom:none; }
  .q .h { color:var(--gris); font-size:11px; font-variant-numeric:tabular-nums; }
  .q .c2 { font-weight:800; font-size:16px; letter-spacing:.5px; flex:1; }
  .q .l { color:var(--cyan); font-size:13px; font-weight:700;
          background:rgba(63,224,200,.12); padding:3px 8px; border-radius:7px; }
  .vide { color:var(--gris); font-size:12px; padding:14px; text-align:center; }
  #sstv { width:100%; border-radius:14px; border:1px solid var(--bord);
          display:none; }
  .son { margin-top:16px; }
  #ecouter { width:100%; padding:14px; border:none; border-radius:14px;
             background:var(--cyan); color:#06131A; font-size:16px;
             font-weight:800; letter-spacing:.4px; }
  #ecouter.on { background:var(--carte); color:var(--cyan);
                border:1px solid var(--bord); }
  .pied { color:var(--gris); font-size:11px; text-align:center;
          margin:22px 0 10px; line-height:1.7; }
  .pied b { color:var(--cyan); }
  .hs { color:var(--ambre); }
  .attente { color:var(--ambre); font-size:12px; text-align:center;
             margin-top:8px; }
  .diag { margin-top:18px; color:var(--gris); font-size:11px; }
  .diag summary { cursor:pointer; padding:6px 0; }
  .diag pre { background:var(--carte); border:1px solid var(--bord);
              border-radius:10px; padding:10px; overflow-x:auto;
              font-size:11px; line-height:1.6; color:#E8F0F7; }
  /* The new-contact announcement: full screen, two seconds. It is the
     moment fort d'une démonstration, et il passait inaperçu dans une liste. */
  .flash { position:fixed; inset:0; background:rgba(7,11,16,.94); z-index:9;
           display:none; flex-direction:column; align-items:center;
           justify-content:center; text-align:center; padding:24px;
           animation:entre .25s ease-out; }
  .flash.on { display:flex; }
  @keyframes entre { from { opacity:0; transform:scale(.94); } }
  .flash .t { color:var(--cyan); font-size:13px; letter-spacing:2.4px;
              text-transform:uppercase; font-weight:700; }
  .flash .c { font-size:clamp(38px,13vw,72px); font-weight:900;
              letter-spacing:1px; margin:10px 0; }
  .flash .l { font-size:clamp(20px,7vw,34px); font-weight:800; color:var(--cyan);
              background:rgba(63,224,200,.12); padding:6px 18px;
              border-radius:12px; }
</style>
</head>
<body>
<div class="flash" id="flash">
  <div class="t">contact établi</div>
  <div class="c" id="f-call">—</div>
  <div class="l" id="f-loc"></div>
</div>

<div class="bandeau">
  <div class="sur">Radioamateur · liaison par satellite</div>
  <h1>Écoute en direct</h1>
  <div class="station">station <b id="station">—</b> · carré <b id="grille">—</b></div>
</div>

<main>
  <div class="sat">
    <div class="nom" id="sat">…</div>
    <div class="etat" id="etat">connexion…</div>
  </div>

  <canvas id="polaire" width="900" height="900"></canvas>

  <div class="grille">
    <div class="c"><div class="lib">Azimut</div><div class="val" id="az">—</div></div>
    <div class="c"><div class="lib">Élévation</div><div class="val" id="el">—</div></div>
    <div class="c"><div class="lib">Réception</div><div class="val rx" id="rx">—</div></div>
    <div class="c"><div class="lib">Émission</div><div class="val tx" id="tx">—</div></div>
  </div>

  <div class="grille" style="margin-top:10px">
    <div class="c"><div class="lib" id="l-temps">Passage</div>
      <div class="val" id="temps">—</div></div>
    <div class="c"><div class="lib">Élévation maximale</div>
      <div class="val" id="elmax">—</div></div>
  </div>

  <div class="emission" id="emission">EN ÉMISSION</div>

  <h2 id="t-ant" style="display:none">Orientation de l'antenne</h2>
  <div class="grille" id="ant" style="display:none">
    <div class="c"><div class="lib">Antenne · azimut</div>
      <div class="val" id="antaz">—</div></div>
    <div class="c"><div class="lib">Antenne · élévation</div>
      <div class="val" id="antel">—</div></div>
  </div>

  <h2>Contacts établis</h2>
  <div class="qso" id="qso"><div class="vide">aucun contact pour le moment</div></div>

  <h2 id="t-sstv" style="display:none">Image reçue du satellite</h2>
  <img id="sstv" alt="image SSTV reçue">

  <h2>Le son de la station</h2>
  <div class="son">
    <button id="ecouter">▶  Écouter la station</button>
    <div class="attente" id="sans-son">Le son est diffusé pendant
      l'enregistrement du passage.</div>
  </div>

  <details class="diag">
    <summary>Diagnostic du son</summary>
    <pre id="diag">…</pre>
  </details>

  <div class="pied">
    Ces signaux viennent d'un satellite amateur en orbite basse, à quelques
    centaines de kilomètres, qui traverse le ciel en une dizaine de minutes.<br>
    Tout le monde peut s'y mettre : il suffit d'une licence, d'une antenne
    portable et d'un poste.<br>
    <b id="heure"></b>
  </div>
</main>

<script>
const base = location.pathname.replace(/\/$/, '');
// The source is set by the watchdog, further down.
const cv = document.getElementById('polaire'), cx = cv.getContext('2d');
let etat = null, versionImage = -1, dernierQso = null, minuteur = 0;

function polaire() {
  const w = cv.width, c = w / 2, r = w / 2 - 58;
  cx.clearRect(0, 0, w, w);
  // The sky, lighter at the zenith: the plot reads at a glance.
  const deg = cx.createRadialGradient(c, c, 0, c, c, r);
  deg.addColorStop(0, '#16283A'); deg.addColorStop(1, '#0C131B');
  cx.fillStyle = deg; cx.beginPath(); cx.arc(c, c, r, 0, 6.284); cx.fill();
  cx.strokeStyle = '#25384C'; cx.lineWidth = 3;
  for (const e of [30, 60]) {
    cx.beginPath(); cx.arc(c, c, r * (90 - e) / 90, 0, 6.284); cx.stroke();
  }
  cx.strokeStyle = '#2E465E'; cx.lineWidth = 4;
  cx.beginPath(); cx.arc(c, c, r, 0, 6.284); cx.stroke();
  cx.strokeStyle = '#25384C'; cx.lineWidth = 2;
  cx.beginPath(); cx.moveTo(c - r, c); cx.lineTo(c + r, c);
  cx.moveTo(c, c - r); cx.lineTo(c, c + r); cx.stroke();
  cx.font = 'bold 36px system-ui'; cx.textAlign = 'center';
  cx.textBaseline = 'middle';
  cx.fillStyle = '#E5484D'; cx.fillText('N', c, c - r - 28);
  cx.fillStyle = '#8496A8';
  cx.fillText('E', c + r + 28, c); cx.fillText('S', c, c + r + 28);
  cx.fillText('O', c - r - 28, c);
  if (!etat) return;
  const pt = (az, el) => {
    const d = r * (90 - el) / 90, a = az * Math.PI / 180;
    return [c + d * Math.sin(a), c - d * Math.cos(a)];
  };
  if (etat.trace && etat.trace.length > 1) {
    cx.strokeStyle = '#3FE0C8'; cx.globalAlpha = .4; cx.lineWidth = 5;
    cx.setLineDash([12, 12]); cx.beginPath();
    etat.trace.forEach((p, i) => {
      const [x, y] = pt(p[0], p[1]); i ? cx.lineTo(x, y) : cx.moveTo(x, y);
    });
    cx.stroke(); cx.setLineDash([]); cx.globalAlpha = 1;
  }
  // The antenna on the plot: a line from the centre, showing at a glance the
  // gap between where it points and where the satellite is.
  if (etat.antaz !== null && etat.antaz !== undefined) {
    const el = (etat.antel === null || etat.antel === undefined) ? 0 : etat.antel;
    const [x, y] = pt(etat.antaz, Math.max(0, el));
    cx.strokeStyle = '#FFB454'; cx.lineWidth = 6; cx.lineCap = 'round';
    cx.beginPath(); cx.moveTo(c, c); cx.lineTo(x, y); cx.stroke();
    cx.fillStyle = '#FFB454';
    cx.beginPath(); cx.arc(x, y, 10, 0, 6.284); cx.fill();
  }
  if (etat.el !== null && etat.el !== undefined && etat.el > -2) {
    const [x, y] = pt(etat.az, etat.el);
    cx.fillStyle = 'rgba(63,224,200,.18)';
    cx.beginPath(); cx.arc(x, y, 38, 0, 6.284); cx.fill();
    cx.fillStyle = '#3FE0C8';
    cx.beginPath(); cx.arc(x, y, 17, 0, 6.284); cx.fill();
  }
}

const mhz = v => (v === null || v === undefined) ? '—'
  : (v / 1e6).toFixed(4).replace('.', ',') + ' MHz';

function qsos(l) {
  const d = document.getElementById('qso');
  if (!l || !l.length) {
    d.innerHTML = '<div class="vide">aucun contact pour le moment</div>'; return;
  }
  d.innerHTML = l.map(q =>
    '<div class="q"><span class="h">' + q.h + '</span>' +
    '<span class="c2">' + q.c + '</span>' +
    (q.l ? '<span class="l">' + q.l + '</span>' : '') + '</div>').join('');
}

// The old MP3 player watchdog used to live here. It looked for the `<audio>`
// tag, removed along with MP3: `getElementById` returned `null`, the next line
// threw, and **the whole script stopped there**. No plot, no event stream, no
// audio — a whole page frozen on "connecting…" for one dead line. Deleted code
// must be deleted everywhere, not only where one is looking.

// --- audio, through the Web Audio API ---
//
// **No `<audio>` tag, no MP3.** The browser decoder refused the encoded
// stream, while the app counters showed everything going out: one hundred and
// eighty-eight kilobytes encoded, one hundred and eighty-seven sent, and
// nothing buffered on the page. The fault was entirely in the playback.
//
// So we receive raw PCM and play it ourselves: no format to negotiate, no
// length header, no bit reservoir. And the button is no ornament — a browser
// only opens an audio context after a
// geste de l'utilisateur.
const CADENCE = 22050;
let ctx = null, prochain = 0, lecture = false;
const bouton = document.getElementById('ecouter');
const journal = [];
let recus = 0, joues = 0, incident = '';

function note(m) {
  journal.push(new Date().toTimeString().slice(0, 8) + ' ' + m);
  while (journal.length > 8) journal.shift();
}

async function ecoute() {
  if (lecture) return;
  lecture = true;
  bouton.className = 'on';
  bouton.textContent = '■  Arrêter l\'écoute';
  ctx = new (window.AudioContext || window.webkitAudioContext)(
    { sampleRate: CADENCE });
  await ctx.resume();
  note('contexte ouvert ' + ctx.sampleRate + ' Hz');
  try {
    const r = await fetch(base + '/son.pcm?r=' + Date.now());
    note('flux ' + r.status);
    const lecteur = r.body.getReader();
    let reste = new Uint8Array(0);
    while (lecture) {
      const { done, value } = await lecteur.read();
      if (done) { note('flux terminé'); break; }
      recus += value.length;
      // A sample is two bytes: the orphan byte is kept for the next block
      // rather than shifting the whole stream by one.
      let brut = value;
      if (reste.length) {
        const j = new Uint8Array(reste.length + value.length);
        j.set(reste); j.set(value, reste.length); brut = j;
      }
      const n = brut.length >> 1;
      reste = brut.subarray(n << 1);
      if (!n) continue;
      const vue = new DataView(brut.buffer, brut.byteOffset, n << 1);
      const tampon = ctx.createBuffer(1, n, CADENCE);
      const canal = tampon.getChannelData(0);
      for (let i = 0; i < n; i++) canal[i] = vue.getInt16(i << 1, true) / 32768;
      const src = ctx.createBufferSource();
      src.buffer = tampon;
      src.connect(ctx.destination);
      // Two hundred milliseconds of lead: enough to absorb a hiccup on the
      // local network, short enough that the demonstration
      // reste vivante.
      const maintenant = ctx.currentTime;
      if (prochain < maintenant + 0.05) prochain = maintenant + 0.2;
      src.start(prochain);
      prochain += tampon.duration;
      joues += n;
    }
  } catch (e) {
    incident = String(e);
    note('erreur ' + incident);
  }
  arreteEcoute();
}

function arreteEcoute() {
  lecture = false;
  bouton.className = '';
  bouton.textContent = '▶  Écouter la station';
  if (ctx) { ctx.close().catch(() => {}); ctx = null; }
  prochain = 0;
}

bouton.addEventListener('click', () => lecture ? arreteEcoute() : ecoute());

setInterval(() => {
  document.getElementById('diag').textContent =
    'état      ' + (lecture ? 'en écoute' : 'arrêté') + '\n' +
    'contexte  ' + (ctx ? ctx.state + ' · ' + ctx.sampleRate + ' Hz' : '—') + '\n' +
    'reçus     ' + (recus / 1024).toFixed(0) + ' ko\n' +
    'joués     ' + (joues / CADENCE).toFixed(1) + ' s\n' +
    'avance    ' + (ctx ? Math.max(0, prochain - ctx.currentTime).toFixed(2) : '—') +
    ' s\n' +
    'incident  ' + (incident || 'aucun') + '\n\n' +
    journal.join('\n');
}, 1000);

const flux = new EventSource(base + '/etat');
flux.onmessage = e => {
  try { etat = JSON.parse(e.data); } catch (x) { return; }
  const g = id => document.getElementById(id);
  g('station').textContent = etat.station || '—';
  g('grille').textContent = etat.grille || '—';
  g('sat').textContent = etat.sat || '—';
  const vu = etat.el !== null && etat.el !== undefined;
  g('etat').textContent = vu ? 'satellite en vue' : 'sous l\'horizon';
  g('az').textContent = vu ? Math.round(etat.az) + '°' : '—';
  g('el').textContent = vu ? Math.round(etat.el) + '°' : '—';
  g('rx').textContent = mhz(etat.rx);
  g('tx').textContent = mhz(etat.tx);
  g('emission').className = 'emission' + (etat.ptt ? ' on' : '');
  g('heure').textContent = etat.heure || '';
  // Antenna heading only appears when the compass provides it: two empty
  // boxes would teach nobody anything.
  // The countdown: time left if the satellite is up, time to wait otherwise.
  // This is what makes an audience grasp that a pass lasts minutes.
  const compte = ms => {
    const t = Math.max(0, Math.round(ms / 1000));
    const m = Math.floor(t / 60), sec = t % 60;
    return m + ' min ' + String(sec).padStart(2, '0') + ' s';
  };
  if (etat.now && (etat.aos || etat.los)) {
    if (etat.los && etat.now >= etat.aos && etat.now < etat.los) {
      g('l-temps').textContent = 'Fin du passage dans';
      g('temps').textContent = compte(etat.los - etat.now);
    } else if (etat.aos && etat.aos > etat.now) {
      g('l-temps').textContent = 'Prochain passage dans';
      g('temps').textContent = compte(etat.aos - etat.now);
    } else { g('temps').textContent = '—'; }
  }
  g('elmax').textContent = (etat.elmax === null || etat.elmax === undefined)
    ? '—' : Math.round(etat.elmax) + '°';

  const aAnt = etat.antaz !== null && etat.antaz !== undefined;
  g('ant').style.display = aAnt ? 'grid' : 'none';
  g('t-ant').style.display = aAnt ? 'block' : 'none';
  if (aAnt) {
    g('antaz').textContent = Math.round(etat.antaz) + '°';
    g('antel').textContent = (etat.antel === null || etat.antel === undefined)
      ? '—' : Math.round(etat.antel) + '°';
  }
  // One more contact than last time: announce it. We compare the first of the
  // list, not its length — the list is capped at twelve, beyond which its size
  // no longer moves.
  const tete = (etat.qso && etat.qso.length) ? etat.qso[0] : null;
  const cle = tete ? (tete.h + tete.c) : '';
  // **The marker is set on the first frame, not the first contact.**
  //
  // It was only initialised when a contact was already in the list. A page
  // opened before the first QSO — the normal case for a demonstration — kept a
  // null marker, and the first contact was used to initialise it instead of
  // being announced. The viewer missed precisely the one we
  // voulait lui montrer.
  //
  // The state is now recorded on the first frame received, empty list
  // included. Any later change is a real contact, and is announced.
  if (dernierQso === null) dernierQso = cle;
  else if (cle && cle !== dernierQso) {
    {
      g('f-call').textContent = tete.c;
      g('f-loc').textContent = tete.l || '';
      g('flash').className = 'flash on';
      clearTimeout(minuteur);
      minuteur = setTimeout(() => { g('flash').className = 'flash'; }, 2000);
    }
    dernierQso = cle;
  }
  // Say when there is no audio, rather than leaving a silent player the
  // viewer cannot tell from a broken one.
  g('sans-son').style.display = etat.son ? 'none' : 'block';
  qsos(etat.qso);
  // The picture is only reloaded when its number changes: otherwise the
  // browser would request it every second for nothing.
  if (etat.img && etat.img !== versionImage) {
    versionImage = etat.img;
    const i = g('sstv');
    i.src = base + '/image.jpg?v=' + versionImage;
    i.style.display = 'block'; g('t-sstv').style.display = 'block';
  }
  polaire();
};
flux.onerror = () => {
  document.getElementById('etat').innerHTML =
    '<span class="hs">liaison perdue — reconnexion…</span>';
};
polaire();
</script>
</body>
</html>
""".trimIndent()
}
