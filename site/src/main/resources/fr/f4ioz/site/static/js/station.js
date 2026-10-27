/*
 * F4IOZ site — Station (the operator's only): the radio found on the LAN,
 * and the panadapter — spectrum and waterfall from the WebSocket, the audio
 * (12 kHz PCM through WebAudio), the IARU region 1 plan, the NCDXF beacons
 * and PSKReporter's spots as marks, click to tune, memories kept in this
 * browser. Receive only: the socket can tune, nothing else.
 */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  if (!$('panadapter')) return;

  // ── The radio's state ─────────────────────────────────────────────────────
  async function statut() {
    try {
      const r = await fetch('/api/sdr/status', { cache: 'no-store', credentials: 'same-origin' });
      const d = await r.json();
      $('sdr-led').className = 'sdr-led ' + (d.online ? (d.in_use ? 'occupe' : 'ok') : 'hs');
      $('sdr-etat').textContent = d.online ? (d.in_use ? 'En ligne, en utilisation' : 'En ligne') : 'Hors ligne' + (d.error ? ' (' + d.error + ')' : '');
      const g = $('sdr-grille'); g.textContent = '';
      if (d.online) [['Carte', d.board], ['Firmware', d.firmware], ['Adresse', d.ip], ['MAC', d.mac], ['Panadapter', d.session ? 'en marche' : 'arrêté']].forEach(([k, v]) => {
        const dt = document.createElement('dt'); dt.textContent = k; const dd = document.createElement('dd'); dd.textContent = v || '—'; dd.className = 'mono'; g.append(dt, dd);
      });
      $('sdr-maj').textContent = new Date().toISOString().substring(11, 19) + ' UTC';
    } catch (e) { $('sdr-etat').textContent = 'Inconnu'; }
  }
  statut(); setInterval(statut, 10000);

  // ── The panadapter ────────────────────────────────────────────────────────
  const BANDES = { '160m': 1840000, '80m': 3700000, '40m': 7100000, '30m': 10130000, '20m': 14100000, '17m': 18110000, '15m': 21150000, '12m': 24930000, '10m': 28200000 };
  const BALISES = [14100000, 18110000, 21150000, 24930000, 28200000];
  const PLAN = [[1810000, 1838000, 'CW'], [1838000, 1843000, 'DIGI'], [1843000, 2000000, 'SSB'], [3500000, 3570000, 'CW'], [3570000, 3600000, 'DIGI'], [3600000, 3800000, 'SSB'],
    [7000000, 7040000, 'CW'], [7040000, 7050000, 'DIGI'], [7050000, 7200000, 'SSB'], [10100000, 10130000, 'CW'], [10130000, 10150000, 'DIGI'],
    [14000000, 14070000, 'CW'], [14070000, 14099000, 'DIGI'], [14101000, 14350000, 'SSB'], [18068000, 18095000, 'CW'], [18095000, 18109000, 'DIGI'], [18111000, 18168000, 'SSB'],
    [21000000, 21070000, 'CW'], [21070000, 21149000, 'DIGI'], [21151000, 21450000, 'SSB'], [24890000, 24915000, 'CW'], [24915000, 24929000, 'DIGI'], [24931000, 24990000, 'SSB'],
    [28000000, 28070000, 'CW'], [28070000, 28190000, 'DIGI'], [28300000, 29700000, 'SSB']];
  const SEG = { CW: '#4d9eff', DIGI: '#fbbf24', SSB: '#4dffa7' };
  const MEM = 'f4ioz.sdr_mem';
  const cv = $('pan-canvas'), ctx = cv.getContext('2d');
  let wf = null, wfx = null, ws = null, freq = 7100000, rate = 48000, mode = 'USB', audio = false, volume = 0.6;
  let bas = -20, haut = 60, moy = null, k = 0.4, lum = 0, contraste = 1, smetre = -130, spots = [], sx = -1;
  const SPEC = 0.32;

  let actx = null, gain = null, prochain = 0;
  function audioInit() { if (actx) return; actx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 }); gain = actx.createGain(); gain.connect(actx.destination); }
  function joue(buf) {
    if (!actx) return;
    const i16 = new Int16Array(buf); if (!i16.length) return;
    const f = new Float32Array(i16.length); for (let i = 0; i < i16.length; i++) f[i] = i16[i] / 32768;
    const ab = actx.createBuffer(1, f.length, 12000); ab.copyToChannel(f, 0);
    const s = actx.createBufferSource(); s.buffer = ab; s.connect(gain);
    const t = actx.currentTime; if (prochain < t + 0.05) prochain = t + 0.15;
    s.start(prochain); prochain += ab.duration;
  }

  const etat = (t, c) => { const e = $('pan-status'); e.textContent = t; e.className = 'card-meta ' + (c || ''); };
  const mhz = hz => (hz / 1e6).toFixed(3);
  const xDeF = (f, W) => (f - (freq - rate / 2)) / rate * W, fDeX = (x, W) => freq - rate / 2 + x / W * rate;
  function lecture() { $('pan-center').textContent = mhz(freq) + ' MHz'; $('pan-span').textContent = rate / 1000 + ' kHz'; $('pan-edges').textContent = mhz(freq - rate / 2) + ' … ' + mhz(freq + rate / 2); $('pan-freq').value = mhz(freq); }
  function sLabel(db) { return db >= -66 ? 'S9+' + Math.round(db + 66) + ' dB' : 'S' + Math.max(0, Math.min(9, Math.floor((db + 120) / 6))); }
  function sm() { $('pan-smeter-bar').style.width = Math.max(0, Math.min(1, (smetre + 120) / 90)) * 100 + '%'; $('pan-smeter-txt').textContent = sLabel(smetre); }

  const PAL = [[0, [8, 14, 28]], [0.25, [26, 60, 150]], [0.45, [18, 160, 200]], [0.62, [40, 200, 120]], [0.8, [240, 200, 60]], [1, [255, 80, 60]]];
  function chaleur(t) { t = Math.max(0, Math.min(1, t)); for (let i = 1; i < PAL.length; i++) if (t <= PAL[i][0]) { const a = PAL[i - 1], b = PAL[i], f = (t - a[0]) / (b[0] - a[0]); return [0, 1, 2].map(j => a[1][j] + (b[1][j] - a[1][j]) * f); } return PAL[5][1]; }

  function taille() {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth || 600, h = 380;
    if (cv.width !== Math.round(w * dpr)) {
      cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
      wf = document.createElement('canvas'); wf.width = cv.width; wf.height = Math.round(cv.height * (1 - SPEC));
      wfx = wf.getContext('2d'); wfx.fillStyle = '#080e1c'; wfx.fillRect(0, 0, wf.width, wf.height);
    }
  }
  function pasJoli(span) { const r = span / 6, p = Math.pow(10, Math.floor(Math.log10(r))), m = r / p; return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p; }

  function dessine(brut) {
    taille();
    const W = cv.width, H = cv.height, sh = Math.round(H * SPEC), dpr = window.devicePixelRatio || 1, n = brut.length;
    if (!moy || moy.length !== n) moy = Float32Array.from(brut); else for (let i = 0; i < n; i++) moy[i] = k * moy[i] + (1 - k) * brut[i];
    let mn = Infinity, mx = -Infinity; for (let i = 0; i < n; i++) { if (moy[i] < mn) mn = moy[i]; if (moy[i] > mx) mx = moy[i]; }
    if (isFinite(mn)) { bas = bas * 0.92 + (mn - 3) * 0.08; haut = haut * 0.92 + (mx + 3) * 0.08; }
    const r = Math.max(6, haut - bas), norme = v => Math.max(0, Math.min(1, ((v - bas) / r - 0.5) * contraste + 0.5 + lum));
    wfx.drawImage(wf, 0, 1);
    const ligne = wfx.createImageData(W, 1);
    for (let x = 0; x < W; x++) { const c = chaleur(norme(moy[Math.min(n - 1, Math.floor(x / W * n))])), o = x * 4; ligne.data[o] = c[0]; ligne.data[o + 1] = c[1]; ligne.data[o + 2] = c[2]; ligne.data[o + 3] = 255; }
    wfx.putImageData(ligne, 0, 0);
    ctx.fillStyle = '#0a1020'; ctx.fillRect(0, 0, W, sh);
    // The scale.
    const pas = pasJoli(rate); ctx.font = (9 * dpr) + 'px "Noto Sans Mono", monospace'; ctx.fillStyle = 'rgba(150,170,200,0.75)'; ctx.strokeStyle = 'rgba(120,140,180,0.18)';
    for (let f = Math.ceil((freq - rate / 2) / pas) * pas; f <= freq + rate / 2; f += pas) { const x = xDeF(f, W); ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, sh); ctx.stroke(); ctx.fillText((f / 1e6).toFixed(3), x + 2 * dpr, sh - 3 * dpr); }
    const lo = freq - rate / 2, hi = freq + rate / 2;
    if ($('mk-band').checked) for (const [a, b, t] of PLAN) { if (b < lo || a > hi) continue; const x1 = Math.max(0, xDeF(a, W)), x2 = Math.min(W, xDeF(b, W)); ctx.globalAlpha = 0.22; ctx.fillStyle = SEG[t]; ctx.fillRect(x1, sh - 5 * dpr, x2 - x1, 5 * dpr); ctx.globalAlpha = 1; }
    if ($('mk-beacon').checked) { ctx.strokeStyle = '#22d3ee'; ctx.fillStyle = '#22d3ee'; for (const f of BALISES) { if (f < lo || f > hi) continue; const x = xDeF(f, W); ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, sh); ctx.stroke(); ctx.setLineDash([]); ctx.fillText('NCDXF', x + 2 * dpr, 22 * dpr); } }
    if ($('mk-dx').checked) { let px = -999; for (const s of spots) { const f = (s.freq_khz || 0) * 1000; if (f < lo || f > hi) continue; const x = xDeF(f, W); ctx.fillStyle = '#fbbf24'; ctx.beginPath(); ctx.moveTo(x, 24 * dpr); ctx.lineTo(x - 3 * dpr, 18 * dpr); ctx.lineTo(x + 3 * dpr, 18 * dpr); ctx.closePath(); ctx.fill(); if (x - px > 44 * dpr) { ctx.fillStyle = '#e4e4e7'; ctx.fillText(String(s.tx || '').substring(0, 12), x + 3 * dpr, 32 * dpr); px = x; } } }
    ctx.strokeStyle = 'rgba(255,80,60,0.7)'; ctx.lineWidth = dpr; ctx.beginPath(); ctx.moveTo(W / 2, 0); ctx.lineTo(W / 2, H); ctx.stroke(); ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(0, sh); for (let x = 0; x < W; x++) ctx.lineTo(x, sh - norme(moy[Math.min(n - 1, Math.floor(x / W * n))]) * sh);
    ctx.lineTo(W, sh); ctx.closePath(); ctx.fillStyle = 'rgba(77,255,167,0.16)'; ctx.fill(); ctx.strokeStyle = '#4dffa7'; ctx.stroke();
    ctx.drawImage(wf, 0, sh);
    if (sx >= 0) { ctx.strokeStyle = 'rgba(255,255,255,0.4)'; ctx.beginPath(); ctx.moveTo(sx, 0); ctx.lineTo(sx, H); ctx.stroke(); ctx.fillStyle = 'rgba(13,18,32,0.85)'; ctx.fillRect(sx + 4 * dpr, 2 * dpr, 64 * dpr, 14 * dpr); ctx.fillStyle = '#e4e4e7'; ctx.fillText(mhz(fDeX(sx, W)), sx + 7 * dpr, 12 * dpr); }
  }

  function envoie(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }
  function accorde(hz) { freq = Math.max(100000, Math.min(60000000, Math.round(hz))); lecture(); envoie({ freq }); }
  function connecte() {
    ws = new WebSocket((location.protocol === 'https:' ? 'wss' : 'ws') + '://' + location.host + '/ws/sdr/spectrum');
    ws.binaryType = 'arraybuffer'; etat('connexion…', 'attente');
    ws.onopen = () => { etat('en réception', 'ok'); $('pan-toggle').textContent = 'Arrêter'; envoie({ freq, rate, mode, volume, audio }); chargeSpots(); };
    ws.onmessage = e => {
      if (typeof e.data !== 'string') { joue(e.data); return; }
      let d; try { d = JSON.parse(e.data); } catch (x) { return; }
      if (d.error) { etat('erreur : ' + d.error, 'err'); return; }
      if (d.center) freq = d.center; if (d.span) rate = d.span;
      if (typeof d.smeter === 'number') { smetre = d.smeter; sm(); }
      lecture(); if (Array.isArray(d.bins)) dessine(d.bins);
    };
    ws.onclose = ev => { etat(ev.code === 1008 ? 'refusé (session admin expirée ?)' : 'arrêté', ev.code === 1008 ? 'err' : ''); $('pan-toggle').textContent = 'Démarrer'; ws = null; };
    ws.onerror = () => etat('connexion impossible', 'err');
  }
  let minuteur = null;
  async function chargeSpots() {
    try { const r = await fetch('/api/dx/spots?since=900&limit=400&mode=', { cache: 'no-store' }); spots = (await r.json()).spots || []; } catch (e) { /* no marks */ }
    clearTimeout(minuteur); minuteur = setTimeout(chargeSpots, 120000);
  }
  function memoires() { try { return JSON.parse(localStorage.getItem(MEM) || '[]').filter(x => typeof x.f === 'number' && /^(USB|LSB|CW|AM|FM)$/.test(x.mode)); } catch (e) { return []; } }
  function garde(m) { try { localStorage.setItem(MEM, JSON.stringify(m)); } catch (e) {} }
  function rendMem() {
    const l = $('pan-mem-list'); l.textContent = '';
    memoires().forEach((x, i) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'puce'; b.dataset.i = i; b.textContent = mhz(x.f) + ' ' + x.mode + ' ×'; b.title = 'Clic : accorder · Maj+clic : effacer'; l.appendChild(b); });
  }

  lecture(); sm(); taille(); rendMem();
  $('pan-toggle').addEventListener('click', () => { if (ws) ws.close(); else connecte(); });
  $('pan-bands').addEventListener('click', e => { const b = e.target.closest('[data-band]'); if (b) accorde(BANDES[b.dataset.band]); });
  $('pan-rate').addEventListener('change', e => { rate = parseInt(e.target.value, 10); lecture(); envoie({ rate }); });
  $('pan-freq').addEventListener('change', e => { const v = parseFloat(e.target.value); if (!isNaN(v)) accorde(v * 1e6); });
  $('pan-audio').addEventListener('click', () => { audio = !audio; if (audio) audioInit(); $('pan-audio').textContent = audio ? 'Audio actif' : 'Audio coupé'; $('pan-audio').setAttribute('aria-pressed', audio ? 'true' : 'false'); envoie({ audio }); });
  $('pan-mode').addEventListener('change', e => { mode = e.target.value; envoie({ mode }); });
  $('pan-vol').addEventListener('input', e => { volume = parseFloat(e.target.value); envoie({ volume }); });
  $('pan-bright').addEventListener('input', e => { lum = parseFloat(e.target.value); });
  $('pan-contrast').addEventListener('input', e => { contraste = parseFloat(e.target.value); });
  $('pan-avg').addEventListener('input', e => { k = parseFloat(e.target.value); });
  cv.addEventListener('click', e => { const r = cv.getBoundingClientRect(); accorde(fDeX((e.clientX - r.left) / r.width * cv.width, cv.width)); });
  cv.addEventListener('mousemove', e => { const r = cv.getBoundingClientRect(); sx = (e.clientX - r.left) / r.width * cv.width; });
  cv.addEventListener('mouseleave', () => { sx = -1; });
  $('pan-mem-save').addEventListener('click', () => { const m = memoires(); m.unshift({ f: freq, mode }); garde(m.slice(0, 24)); rendMem(); });
  $('pan-mem-list').addEventListener('click', e => {
    const b = e.target.closest('button[data-i]'); if (!b) return;
    const m = memoires(), x = m[+b.dataset.i]; if (!x) return;
    if (e.shiftKey) { m.splice(+b.dataset.i, 1); garde(m); rendMem(); return; }
    mode = x.mode; $('pan-mode').value = x.mode; envoie({ mode }); accorde(x.f);
  });
  window.addEventListener('beforeunload', () => { if (ws) ws.close(); });
  window.addEventListener('resize', taille);
})();
