/*
 * F4IOZ site — Balises: the NCDXF/IARU International Beacon Project, worked
 * out from the browser's UTC clock. 18 beacons, 5 bands (14.100 … 28.200
 * MHz), 10 s each, a 3-minute cycle aligned on UTC: in slot s, band b is
 * beacon (s − b) mod 18. In its slot a beacon sends its call in CW at 22 WPM
 * (100 W), then four 1-s dashes at 100, 10, 1 and 0.1 W. Redrawn five times a
 * second (the old page rebuilt everything at every frame).
 */
(function () {
  'use strict';
  const H = window.HF, { $, esc } = H;
  const BALISES = [
    ['4U1UN', 'New York, ONU', 'FN30as', 40.75, -73.97], ['VE8AT', 'Eureka, Nunavut', 'EQ79ax', 79.99, -85.94], ['W6WX', 'Californie', 'CM97bd', 37.15, -121.90],
    ['KH6RS', 'Maui, Hawaï', 'BL10ts', 20.79, -156.46], ['ZL6B', 'Masterton, Nouvelle-Zélande', 'RE78tw', -40.95, 175.61], ['VK6RBP', 'Perth, Australie', 'OF87av', -32.10, 116.05],
    ['JA2IGY', 'Mont Asama, Japon', 'PM84jk', 34.45, 136.79], ['RR9O', 'Novossibirsk, Russie', 'NO14kx', 54.98, 82.89], ['VR2B', 'Hong Kong', 'OL72cq', 22.27, 114.16],
    ['4S7B', 'Colombo, Sri Lanka', 'NJ06cr', 6.90, 79.86], ['ZS6DN', 'Pretoria, Afrique du Sud', 'KG44dc', -25.90, 28.20], ['5Z4B', 'Nairobi, Kenya', 'KI88ks', -1.28, 36.82],
    ['4X6TU', 'Tel Aviv, Israël', 'KM72jb', 32.05, 34.78], ['OH2B', 'Lohja, Finlande', 'KP20eh', 60.33, 24.46], ['CS3B', 'Madère, Portugal', 'IM12jt', 32.68, -16.93],
    ['LU4AA', 'Buenos Aires, Argentine', 'GF05tj', -34.60, -58.40], ['OA4B', 'Lima, Pérou', 'FH17mw', -12.07, -77.05], ['YV5B', 'Caracas, Venezuela', 'FK60nk', 10.42, -66.97],
  ].map(b => ({ call: b[0], qth: b[1], loc: b[2], lat: b[3], lon: b[4] }));
  const BANDES = [{ mhz: 14.100, l: '20 m', c: '#4dffa7' }, { mhz: 18.110, l: '17 m', c: '#22d3ee' }, { mhz: 21.150, l: '15 m', c: '#fbbf24' },
    { mhz: 24.930, l: '12 m', c: '#f472b6' }, { mhz: 28.200, l: '10 m', c: '#c084fc' }];
  const CYCLE = 180, CRENEAU = 10, N = BALISES.length;
  const MORSE = { A: '.-', B: '-...', C: '-.-.', D: '-..', E: '.', F: '..-.', G: '--.', H: '....', I: '..', J: '.---', K: '-.-', L: '.-..', M: '--', N: '-.', O: '---', P: '.--.', Q: '--.-', R: '.-.', S: '...', T: '-', U: '..-', V: '...-', W: '.--', X: '-..-', Y: '-.--', Z: '--..', 0: '-----', 1: '.----', 2: '..---', 3: '...--', 4: '....-', 5: '.....', 6: '-....', 7: '--...', 8: '---..', 9: '----.' };
  const PUISSANCES = ['IND', '100 W', '10 W', '1 W', '0,1 W'];
  const CLE_QTH = 'f4ioz.beacon_qth';
  let qth = null;

  const balise = (creneau, b) => ((creneau - b) % N + N) % N;
  function dureeIndicatif(call) {
    const u = 1.2 / 22; let t = 0;
    for (const ch of call) { const c = MORSE[ch]; if (!c) continue; for (let j = 0; j < c.length; j++) { t += (c[j] === '-' ? 3 : 1) * u; if (j < c.length - 1) t += u; } t += 3 * u; }
    return t;
  }
  /** -1 idle, 0 the call, 1..4 the dashes. */
  function phase(call, s) { const t = dureeIndicatif(call); if (s < t) return 0; const d = s - t; return d < 4 ? 1 + Math.floor(d) : -1; }

  function azDist(b) {
    if (!qth) return '';
    const p = [qth.lat, qth.lon], q = [b.lat, b.lon];
    return Math.round(H.cap(p, q)) + '° · ' + Math.round(H.angle(p, q) * H.R_KM).toLocaleString('fr-FR') + ' km';
  }

  // The « now » cards: built once, their changing parts updated.
  function construit() {
    $('beacon-now').innerHTML = BANDES.map((b, i) => '<div class="bn" style="--bc:' + b.c + '" data-i="' + i + '"><div class="bn-bande">' + b.l + ' <span>' + b.mhz.toFixed(3) + '</span></div>' +
      '<div class="bn-call"></div><div class="bn-qth"></div><div class="bn-ad"></div><div class="bn-barre"><i></i></div><div class="bn-pw">' +
      PUISSANCES.map(p => '<span>' + p + '</span>').join('') + '</div></div>').join('');
  }
  let creneauVu = -1;
  function maintenant(cr, s) {
    document.querySelectorAll('#beacon-now .bn').forEach(e => {
      const i = +e.dataset.i, bk = BALISES[balise(cr, i)];
      if (cr !== creneauVu) {
        e.querySelector('.bn-call').textContent = bk.call; e.querySelector('.bn-qth').textContent = bk.qth + ' · ' + bk.loc; e.querySelector('.bn-ad').textContent = azDist(bk);
      }
      e.querySelector('.bn-barre i').style.width = (s / CRENEAU * 100) + '%';
      const ph = phase(bk.call, s);
      e.querySelectorAll('.bn-pw span').forEach((x, k) => x.classList.toggle('on', k === ph));
    });
    if (cr !== creneauVu) {
      $('beacon-grid').innerHTML = BANDES.map((b, i) => {
        const c = balise(cr, i); let h = '';
        for (let k = 0; k < 10; k++) { const bk = BALISES[(c + k) % N]; h += '<span class="seq' + (k ? '' : ' now') + '" title="' + esc(bk.qth) + '">' + esc(bk.call) + (k ? '' : ' <i></i>') + '</span>'; }
        return '<div class="seq-l"><b style="color:' + b.c + '">' + b.l + '</b><div class="seq-t">' + h + '</div></div>';
      }).join('');
      creneauVu = cr;
    }
    const reste = Math.ceil(CRENEAU - s) + ' s';
    document.querySelectorAll('#beacon-grid .seq.now i').forEach(x => { x.textContent = reste; });
  }

  // The map: an equirectangular world (Natural Earth's coasts, served by the site), the active beacons, the paths from the QTH.
  let terres = null, fond = null, fondCle = '';
  fetch('/api/carte/traits', { cache: 'force-cache' }).then(r => r.json()).then(t => { terres = t; fond = null; }).catch(() => {});
  const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  function dessine(cr) {
    const cv = $('beacon-map'), dpr = window.devicePixelRatio || 1;
    const W = cv.clientWidth || 600, w = Math.round(W * dpr), h = Math.round(W / 2 * dpr);
    if (cv.width !== w) { cv.width = w; cv.height = h; fond = null; }
    const proj = (la, lo) => [(lo + 180) / 360 * w, (90 - la) / 180 * h];
    const cle = document.documentElement.getAttribute('data-theme') + w;
    if (!fond || fondCle !== cle) {
      fond = document.createElement('canvas'); fond.width = w; fond.height = h; fondCle = cle;
      const x = fond.getContext('2d');
      x.fillStyle = css('--well') || '#0d151c'; x.fillRect(0, 0, w, h);
      if (terres) {
        x.fillStyle = css('--panel-2') || '#1a2530'; x.strokeStyle = css('--border-strong') || '#2f4150'; x.lineWidth = Math.max(1, w / 1200);
        x.beginPath();
        for (const r of terres) { for (let i = 0; i < r.length; i += 2) { const p = proj(r[i + 1], r[i]); if (i === 0) x.moveTo(p[0], p[1]); else x.lineTo(p[0], p[1]); } x.closePath(); }
        x.fill('evenodd'); x.stroke();
      }
      x.strokeStyle = css('--border') || '#22303b'; x.lineWidth = 1;
      for (let lo = -150; lo < 180; lo += 30) { const p = proj(0, lo); x.beginPath(); x.moveTo(p[0], 0); x.lineTo(p[0], h); x.stroke(); }
      for (let la = -60; la <= 60; la += 30) { const p = proj(la, 0); x.beginPath(); x.moveTo(0, p[1]); x.lineTo(w, p[1]); x.stroke(); }
    }
    const c = cv.getContext('2d');
    c.drawImage(fond, 0, 0);
    const actives = new Map(); BANDES.forEach((b, i) => actives.set(balise(cr, i), b.c));
    if (qth) {
      actives.forEach((col, n) => {
        const pts = H.orthodromie([qth.lat, qth.lon], [BALISES[n].lat, BALISES[n].lon], 72);
        c.strokeStyle = col; c.globalAlpha = 0.45; c.lineWidth = dpr; c.beginPath();
        let prev = null;
        for (const p of pts) { const lo = ((p[1] + 540) % 360) - 180, q = proj(p[0], lo); if (prev !== null && Math.abs(q[0] - prev) > w / 2) { c.stroke(); c.beginPath(); c.moveTo(q[0], q[1]); } else if (prev === null) c.moveTo(q[0], q[1]); else c.lineTo(q[0], q[1]); prev = q[0]; }
        c.stroke(); c.globalAlpha = 1;
      });
      const q = proj(qth.lat, qth.lon);
      c.fillStyle = css('--accent') || '#3fe0c8'; c.beginPath(); c.moveTo(q[0], q[1] - 6 * dpr); c.lineTo(q[0] + 5 * dpr, q[1] + 4 * dpr); c.lineTo(q[0] - 5 * dpr, q[1] + 4 * dpr); c.closePath(); c.fill();
    }
    BALISES.forEach((b, n) => {
      const p = proj(b.lat, b.lon), on = actives.has(n);
      c.fillStyle = on ? actives.get(n) : (css('--text-muted') || '#8091a3');
      if (on) { c.shadowColor = actives.get(n); c.shadowBlur = 8 * dpr; }
      c.beginPath(); c.arc(p[0], p[1], (on ? 5 : 2.5) * dpr, 0, 2 * Math.PI); c.fill(); c.shadowBlur = 0;
      if (on) { c.fillStyle = css('--text') || '#e8f0f7'; c.font = (10 * dpr) + 'px "Noto Sans Mono", monospace'; c.fillText(b.call, p[0] + 7 * dpr, p[1] + 3 * dpr); }
    });
  }

  function tic() {
    try {
      const ms = Date.now(), sec = (ms / 1000) % CYCLE, cr = Math.floor(sec / CRENEAU), s = sec - cr * CRENEAU;
      maintenant(cr, s); dessine(cr);
      const d = new Date(ms), p2 = n => String(n).padStart(2, '0');
      H.etat(p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + ':' + p2(d.getUTCSeconds()) + ' UTC · créneau ' + (cr + 1) + '/18', 'ok', 'bn-horloge');
    } catch (e) { if (window.console) console.error(e); }
  }

  function prendQth(v) {
    const p = H.loc(v);
    if (!p) { $('qth-err').textContent = 'Locator : 4 ou 6 caractères (JN18FS).'; return; }
    $('qth-err').textContent = ''; qth = { lat: p[0], lon: p[1] }; H.qthGarder(CLE_QTH, v.toUpperCase()); creneauVu = -1;
  }
  $('qth').value = H.qthGarde(CLE_QTH);
  prendQth($('qth').value);
  $('qth').addEventListener('change', () => prendQth($('qth').value.trim()));
  $('qth-form').addEventListener('submit', e => { e.preventDefault(); prendQth($('qth').value.trim()); });
  construit(); tic(); setInterval(tic, 200);
})();
