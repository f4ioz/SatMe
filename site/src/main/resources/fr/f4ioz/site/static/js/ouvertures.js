/*
 * F4IOZ site — Ouvertures: the band × UTC hour grid from the RBN archives
 * the site summed for its locator, over the days chosen (only those: the old
 * page counted every day it had), and the map of where the region is heard.
 */
(function () {
  'use strict';
  const H = window.HF, { $, esc, couleur, carte, bulle, json } = H;
  const PALETTE = [[0, [10, 18, 36]], [0.15, [26, 60, 150]], [0.4, [18, 160, 200]], [0.6, [40, 200, 120]], [0.8, [240, 200, 60]], [1, [255, 70, 60]]];
  function chaleur(t) {
    t = Math.max(0, Math.min(1, t));
    for (let i = 1; i < PALETTE.length; i++) if (t <= PALETTE[i][0]) {
      const a = PALETTE[i - 1], b = PALETTE[i], f = (t - a[0]) / (b[0] - a[0]);
      return 'rgb(' + [0, 1, 2].map(k => Math.round(a[1][k] + (b[1][k] - a[1][k]) * f)).join(',') + ')';
    }
    return 'rgb(255,70,60)';
  }
  let bande = '', heure = -1, attente = null, m = null, couche = null, bandes = [];

  function plages(h) {
    const out = []; let d = h[0], p = h[0];
    for (let i = 1; i < h.length; i++) { if (h[i] === p + 1) { p = h[i]; continue; } out.push(d === p ? d + ' h' : d + '–' + p + ' h'); d = p = h[i]; }
    out.push(d === p ? d + ' h' : d + '–' + p + ' h');
    return out.join(', ');
  }

  function grille(d) {
    const g = $('oh-grid');
    if (d.error && !(d.bands || []).length) { g.innerHTML = '<p class="vide">' + esc(d.error) + '</p>'; H.etat('erreur', 'err'); return; }
    bandes = d.bands || [];
    if (!bandes.length) {
      g.innerHTML = '<p class="vide">' + (d.ingesting ? 'Lecture des archives RBN en cours… (la première fois, une minute environ)' : 'Pas encore de données pour ce locator.') + '</p>';
      H.etat(d.ingesting ? 'lecture des archives…' : 'vide', 'attente'); return;
    }
    let h = '<div class="oh-l oh-t"><span></span>';
    for (let k = 0; k < 24; k++) h += '<span>' + (k % 3 === 0 ? k : '') + '</span>';
    h += '</div>';
    const resume = [];
    for (const b of bandes) {
      const l = d.matrix[b] || {}, max = Math.max.apply(null, [1].concat(Object.values(l).map(x => x.spots)));
      const ouvertes = [];
      h += '<div class="oh-l"><b style="color:' + couleur(b) + '">' + esc(b) + '</b>';
      for (let k = 0; k < 24; k++) {
        const c = l[k], v = c ? c.spots / max : 0;
        if (v >= 0.45) ouvertes.push(k);
        const tip = c ? b + ' ' + k + ' h UTC : ' + c.spots + ' spots · ' + c.skimmers + ' skimmers · SNR ' + c.snr + ' dB' : b + ' ' + k + ' h : —';
        h += '<button type="button" class="oh-c" style="background:' + (c ? chaleur(v) : 'transparent') + '" data-b="' + esc(b) + '" data-h="' + k + '" title="' + esc(tip) + '" aria-label="' + esc(tip) + '"></button>';
      }
      h += '</div>';
      if (ouvertes.length) resume.push('<span style="color:' + couleur(b) + '">' + esc(b) + '</span> ' + plages(ouvertes));
    }
    g.innerHTML = h;
    $('oh-summary').innerHTML = resume.length ? '<b>Ouvertures estimées (UTC) :</b> ' + resume.join(' · ') : '';
    $('oh-jours').textContent = d.days + ' jour' + (d.days > 1 ? 's' : '') + ' lu' + (d.days > 1 ? 's' : '') + (d.days < d.days_wanted ? ' sur ' + d.days_wanted + ' demandés' : '');
    H.etat(d.total.toLocaleString('fr-FR') + ' spots' + (d.ingesting ? ' · lecture en cours…' : '') + (d.error ? ' · ' + d.error : ''), d.ingesting ? 'attente' : 'ok');
  }

  function puces() {
    $('oh-bands').innerHTML = '<span class="hf-puces-titre">Bande</span>' + [''].concat(bandes).map(b => '<button type="button" class="puce' + (b === bande ? ' actif' : '') + '" data-band="' + esc(b) + '">' + (esc(b) || 'Toutes') + '</button>').join('');
  }

  async function charge() {
    clearTimeout(attente);
    try {
      const d = await json('/api/rbn/openings?loc=' + encodeURIComponent($('oh-loc').value) + '&days=' + $('oh-days').value);
      grille(d); puces();
      if (d.ingesting) attente = setTimeout(charge, 5000);
      carteChaleur();
    } catch (e) { H.etat('erreur : ' + e.message, 'err'); }
  }

  async function carteChaleur() {
    if (!m) { m = carte('oh-map'); couche = L.layerGroup().addTo(m); }
    try {
      const d = await json('/api/rbn/heat?loc=' + encodeURIComponent($('oh-loc').value) + '&band=' + encodeURIComponent(bande) + '&hour=' + heure + '&days=' + $('oh-days').value);
      couche.clearLayers();
      const l = d.cells || [];
      if (d.center) L.circleMarker(d.center, { radius: 6, color: '#fff', weight: 1.5, fillColor: '#4d9eff', fillOpacity: 1 }).bindTooltip(bulle([d.loc])).addTo(couche);
      const max = Math.max.apply(null, [1].concat(l.map(c => c.count)));
      for (const c of l) {
        const t = c.count / max;
        L.circleMarker([c.lat, c.lon], { radius: 4 + 18 * Math.sqrt(t), weight: 0.5, color: '#000', opacity: 0.25, fillColor: chaleur(t), fillOpacity: 0.12 + 0.43 * t })
          .bindTooltip(bulle([c.count + ' spots', 'SNR moyen ' + c.snr + ' dB']), { className: 'hf-bulle' }).addTo(couche);
      }
      $('oh-heat-status').textContent = l.length + ' zones' + (bande ? ' · ' + bande : '') + (heure >= 0 ? ' · ' + heure + ' h UTC' : '');
    } catch (e) { $('oh-heat-status').textContent = 'indisponible'; }
  }

  $('oh-loc').addEventListener('change', charge);
  $('oh-days').addEventListener('change', charge);
  $('oh-bands').addEventListener('click', e => { const b = e.target.closest('button[data-band]'); if (!b) return; bande = b.dataset.band; puces(); carteChaleur(); });
  $('oh-hour').addEventListener('input', e => { heure = parseInt(e.target.value, 10); $('oh-hour-lbl').textContent = heure < 0 ? 'toutes' : heure + ' h'; carteChaleur(); });
  $('oh-grid').addEventListener('click', e => {
    const c = e.target.closest('.oh-c'); if (!c) return;
    bande = c.dataset.b; heure = parseInt(c.dataset.h, 10);
    $('oh-hour').value = heure; $('oh-hour-lbl').textContent = heure + ' h';
    puces(); carteChaleur();
  });
  charge();
})();
