/*
 * F4IOZ site — DX: PSKReporter's reports as the site last fetched them,
 * filtered here (call pattern, continent, LoTW), as a table or a map of
 * great-circle paths; HamQSL's conditions above. Every value from the
 * reports is put in as text.
 */
(function () {
  'use strict';
  const { $, esc, couleur, loc, orthodromie, carte, bulle, json, etat, age } = window.HF;
  const CONT = { EU: '#4d9eff', NA: '#4dffa7', SA: '#fbbf24', AS: '#ef4444', AF: '#22d3ee', OC: '#c084fc' };
  let spots = [], vus = new Set(), vue = 'table', m = null, couche = null, ctxAudio = null;

  function bip(n) {
    try {
      if (!ctxAudio) ctxAudio = new (window.AudioContext || window.webkitAudioContext)();
      const o = ctxAudio.createOscillator(), g = ctxAudio.createGain();
      o.frequency.value = 880 + Math.min(n * 40, 600); g.gain.value = 0.08;
      o.connect(g); g.connect(ctxAudio.destination);
      g.gain.linearRampToValueAtTime(0, ctxAudio.currentTime + 0.1);
      o.start(); o.stop(ctxAudio.currentTime + 0.1);
    } catch (e) { /* no sound */ }
  }

  /** « F4* », « EA?ABC »: a pattern on the callsign, case ignored; anything else in it taken literally. */
  function motif(p) {
    if (!p) return null;
    const s = p.toUpperCase().replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.');
    try { return new RegExp('^' + s + '$'); } catch (e) { return null; }
  }

  function filtres() {
    const re = motif($('f-call').value.trim()), c = $('f-cont').value, lotw = $('f-lotw').checked;
    return spots.filter(s => (!re || re.test(s.tx)) && (!c || s.tx_continent === c) && (!lotw || s.lotw));
  }

  function stats(l) {
    $('s-count').textContent = l.length;
    $('s-tx').textContent = new Set(l.map(s => s.tx)).size;
    $('s-dxcc').textContent = new Set(l.map(s => s.tx_dxcc).filter(Boolean)).size;
    $('s-age').textContent = l.length ? age(Math.max.apply(null, l.map(s => s.ts))) : '—';
  }

  function qrz(c) { return '<a href="https://www.qrz.com/db/' + encodeURIComponent(c) + '" rel="noopener">' + esc(c) + '</a>'; }

  function table(l) {
    const corps = $('dx-corps');
    if (!l.length) { corps.innerHTML = '<tr><td colspan="11" class="vide">Pas de report. Élargir la bande, le mode ou la fenêtre.</td></tr>'; return; }
    const now = Date.now() / 1000;
    corps.innerHTML = l.map(s => {
      const n = parseInt(s.snr, 10);
      const cls = isNaN(n) ? '' : n < -10 ? 'faible' : n > 0 ? 'fort' : '';
      return '<tr' + (now - s.ts < 90 ? ' class="frais"' : '') + '><td class="cont ' + esc(s.tx_continent) + '">' + esc(s.tx_continent) + '</td>' +
        '<td class="call">' + qrz(s.tx) + '</td><td class="dxcc">' + esc(s.tx_dxcc) + '</td><td class="loc num">' + esc(s.tx_loc) + '</td>' +
        '<td class="call-rx">' + qrz(s.rx) + '</td><td class="loc num">' + esc(s.rx_loc) + '</td>' +
        '<td class="freq num"><button type="button" class="copie" data-f="' + esc(s.freq_khz) + '" title="Copier">' + (s.freq_khz ? esc(Number(s.freq_khz).toFixed(3)) : '') + '</button></td>' +
        '<td class="band" style="color:' + couleur(s.band) + '">' + esc(s.band) + '</td><td class="mode">' + esc(s.mode) + '</td>' +
        '<td class="snr num ' + cls + '">' + esc(s.snr) + '</td><td class="age num">' + esc(age(s.ts)) + '</td></tr>';
    }).join('');
  }

  function dessineCarte(l) {
    if (!m) { m = carte('dx-carte'); couche = L.layerGroup().addTo(m); }
    couche.clearLayers();
    for (const s of l.slice(0, 500)) {
      const a = loc(s.tx_loc), b = loc(s.rx_loc);
      if (!a || !b) continue;
      const col = CONT[s.tx_continent] || '#888';
      L.polyline(orthodromie(a, b, 40), { color: col, weight: 1, opacity: 0.55 })
        .bindTooltip(bulle([s.tx + ' → ' + s.rx, s.freq_khz + ' kHz · ' + s.band + ' · ' + s.mode, 'SNR ' + s.snr + ' dB']), { className: 'hf-bulle', sticky: true }).addTo(couche);
      L.circleMarker(a, { radius: 4, color: '#0a1426', weight: 1, fillColor: col, fillOpacity: 0.9 })
        .bindTooltip(bulle([s.tx, s.tx_dxcc, s.tx_loc]), { className: 'hf-bulle' }).addTo(couche);
    }
    setTimeout(() => m.invalidateSize(), 60);
  }

  function rend() {
    const l = filtres();
    stats(l);
    $('dx-table-wrap').hidden = vue !== 'table';
    $('dx-carte').hidden = vue !== 'carte';
    if (vue === 'table') table(l); else dessineCarte(l);
  }

  async function charge() {
    etat('chargement…', 'attente');
    const p = new URLSearchParams({ since: $('f-since').value, mode: $('f-mode').value, band: $('f-band').value, limit: $('f-limit').value });
    try {
      const d = await json('/api/dx/spots?' + p);
      const neufs = d.spots || [];
      if ($('f-bip').checked && vus.size) { const n = neufs.filter(s => !vus.has(s.tx + '|' + s.ts)).length; if (n) bip(n); }
      vus = new Set(neufs.map(s => s.tx + '|' + s.ts));
      spots = neufs;
      rend();
      const quand = d.updated ? 'données de il y a ' + age(d.updated) : 'pas encore de données';
      etat(d.error ? quand + ' · PSKReporter : ' + d.error : d.count + ' spots · ' + quand, d.error ? 'attente' : 'ok');
    } catch (e) {
      etat('erreur : ' + e.message, 'err');
    }
  }

  async function solaire() {
    try {
      const d = await json('/api/dx/solar');
      const pose = (id, v, cls) => { const e = $('sol-' + id); if (e) { e.textContent = v || '—'; e.className = cls || ''; } };
      const a = parseInt(d.aindex, 10), k = parseInt(d.kindex, 10);
      pose('sfi', d.solarflux); pose('sn', d.sunspots);
      pose('a', d.aindex, isNaN(a) ? '' : a > 30 ? 'mauvais' : a > 15 ? 'moyen' : 'bon');
      pose('k', d.kindex, isNaN(k) ? '' : k >= 5 ? 'mauvais' : k >= 4 ? 'moyen' : 'bon');
      pose('xray', d.xray); pose('aurora', d.aurora); pose('muf', d.muf && d.muf !== 'NoRpt' ? d.muf + ' MHz' : '');
      const es = (d.vhf_conditions || {})['E-Skip|europe'];
      pose('es', es ? (es === 'Band Closed' ? 'fermé' : es) : '');
      pose('maj', d.updated ? String(d.updated).trim() : '');
    } catch (e) { /* the strip stays empty */ }
  }

  ['f-band', 'f-mode', 'f-since', 'f-limit'].forEach(id => $(id).addEventListener('change', charge));
  ['f-call', 'f-cont', 'f-lotw'].forEach(id => { $(id).addEventListener('input', rend); $(id).addEventListener('change', rend); });
  $('dx-rafraichir').addEventListener('click', charge);
  document.querySelectorAll('.hf-onglet[data-vue]').forEach(b => b.addEventListener('click', () => {
    vue = b.dataset.vue;
    document.querySelectorAll('.hf-onglet[data-vue]').forEach(x => { x.classList.toggle('actif', x === b); x.setAttribute('aria-selected', x === b ? 'true' : 'false'); });
    rend();
  }));
  $('dx-corps').addEventListener('click', e => {
    const b = e.target.closest('button.copie'); if (!b) return;
    if (navigator.clipboard) navigator.clipboard.writeText(b.dataset.f).catch(() => {});
    etat('fréquence copiée : ' + b.dataset.f + ' kHz', 'ok');
  });
  solaire(); setInterval(solaire, 15 * 60 * 1000);
  charge(); setInterval(charge, 90 * 1000);
})();
