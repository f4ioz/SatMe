/*
 * F4IOZ site — Bandes: HamQSL's indices read in words (NOAA's G and R
 * scales), the HF bands by period at the visitor's QTH (day or night from the
 * sun's height there), the VHF phenomena; then the propagation map —
 * PSKReporter, WSPR or RBN blobs, the paths touching the QTH, the gray line,
 * KC2G's MUF/foF2 contours, a point-to-point path — and a verdict per band
 * crossing what is measured with HamQSL's model.
 */
(function () {
  'use strict';
  const H = window.HF, { $, esc, couleur, loc, orthodromie, arc, angle, cap, carte, bulle, soleil, terminateur, json, R_KM } = H;
  const CLE_QTH = 'f4ioz.bands_qth';
  let qth = H.qthGarde(CLE_QTH), solaire = null;

  // ── The indices, in words ─────────────────────────────────────────────────
  function sfi(v) {
    const n = parseInt(v, 10);
    if (isNaN(n)) return { l: '—', c: '', n: '' };
    if (n < 70) return { l: 'très bas', c: 'mauvais', n: 'Bandes hautes (10–15 m) souvent fermées : 40 et 80 m.' };
    if (n < 90) return { l: 'bas', c: 'mauvais', n: '20 m correct le jour, bandes hautes capricieuses.' };
    if (n < 120) return { l: 'modéré', c: 'moyen', n: '20 et 17 m bons, 12 et 10 m par moments.' };
    if (n < 150) return { l: 'bon', c: 'bon', n: '15, 12 et 10 m s\'ouvrent le jour, DX possible.' };
    if (n < 200) return { l: 'très bon', c: 'bon', n: 'Toutes les bandes hautes actives.' };
    return { l: 'exceptionnel', c: 'bon', n: 'Conditions remarquables sur 10 et 12 m.' };
  }
  function kIdx(v) {
    const k = parseInt(v, 10);
    if (isNaN(k)) return { l: '—', c: '', n: '', s: '' };
    if (k <= 2) return { l: 'calme', c: 'bon', s: 'G0', n: 'Champ géomagnétique calme : HF stable, chemins polaires ouverts.' };
    if (k === 3) return { l: 'instable', c: 'moyen', s: 'G0', n: 'Légère agitation, chemins de haute latitude un peu dégradés.' };
    if (k === 4) return { l: 'actif', c: 'moyen', s: 'G0', n: 'Chemins nord affaiblis.' };
    const G = { 5: 'G1', 6: 'G2', 7: 'G3', 8: 'G4' }[k] || 'G5', sev = { 5: 'mineure', 6: 'modérée', 7: 'forte', 8: 'sévère' }[k] || 'extrême';
    return { l: 'tempête ' + sev, c: 'mauvais', s: G, n: 'Tempête ' + G + ' : HF dégradée (surtout polaire), aurore possible en VHF.' };
  }
  function aIdx(v) {
    const a = parseInt(v, 10);
    if (isNaN(a)) return { l: '—', c: '', n: '' };
    if (a <= 7) return { l: 'calme', c: 'bon', n: 'Activité géomagnétique faible sur 24 h.' };
    if (a <= 15) return { l: 'instable', c: 'moyen', n: 'Conditions un peu perturbées.' };
    if (a <= 29) return { l: 'actif', c: 'moyen', n: 'DX vers le nord plus difficile.' };
    if (a <= 49) return { l: 'tempête mineure', c: 'mauvais', n: 'Propagation HF perturbée.' };
    return { l: 'tempête majeure', c: 'mauvais', n: 'Forte dégradation HF.' };
  }
  function xray(v) {
    const m = String(v || '').match(/^([ABCMX])([\d.]+)?/i);
    if (!m) return { l: v || '—', c: '', n: '', s: '' };
    const k = m[1].toUpperCase(), g = parseFloat(m[2] || '0');
    if (k === 'A' || k === 'B') return { l: 'calme', c: 'bon', s: 'R0', n: 'Pas d\'éruption notable.' };
    if (k === 'C') return { l: 'faible', c: 'bon', s: 'R0', n: 'Activité éruptive faible, impact négligeable.' };
    if (k === 'M') return { l: 'éruption M', c: 'moyen', s: g >= 5 ? 'R2' : 'R1', n: 'Coupure HF possible côté jour (bandes basses).' };
    return { l: 'éruption X', c: 'mauvais', s: g >= 10 ? 'R4' : 'R3', n: 'Coupure HF marquée côté jour.' };
  }
  const COND = { Good: 'Bonnes', Fair: 'Moyennes', Poor: 'Faibles', 'Band Closed': 'Fermée', 'Band Open': 'Ouverte' };
  function condCls(s) { s = String(s || '').toLowerCase(); return s.includes('good') || s.includes('open') ? 'bon' : s.includes('fair') ? 'moyen' : s.includes('poor') || s.includes('closed') ? 'mauvais' : ''; }

  function carteIdx(titre, v, i, u) {
    return '<div class="idx"><div class="idx-t">' + esc(titre) + '</div><div class="idx-v ' + i.c + '">' + esc(v == null || v === '' ? '—' : v) +
      (u ? '<span class="u">' + esc(u) + '</span>' : '') + (i.s ? '<span class="idx-s ' + i.c + '">' + esc(i.s) + '</span>' : '') + '</div>' +
      '<div class="idx-l ' + i.c + '">' + esc(i.l || '') + '</div><div class="idx-n">' + esc(i.n || '') + '</div></div>';
  }

  const GROUPES = [
    { k: '80m-40m', b: '80 / 60 / 40 m', f: '3,5–7 MHz' }, { k: '30m-20m', b: '30 / 20 m', f: '10–14 MHz' },
    { k: '17m-15m', b: '17 / 15 m', f: '18–21 MHz' }, { k: '12m-10m', b: '12 / 10 m', f: '24–28 MHz' },
  ];
  const GROUPE_DE = { '80m': '80m-40m', '60m': '80m-40m', '40m': '80m-40m', '30m': '30m-20m', '20m': '30m-20m', '17m': '17m-15m', '15m': '17m-15m', '12m': '12m-10m', '10m': '12m-10m' };

  function periode() {
    const p = loc(qth);
    if (!p) return 'day';
    const el = soleil(new Date(), p[0], p[1]);
    const jour = el >= 0;
    $('daynight').innerHTML = '<b class="' + (jour ? 'jour' : 'nuit') + '">' + (jour ? '☀ Jour' : '☾ Nuit') + '</b>' + (Math.abs(el) <= 6 ? ' <span class="gris">· gray line</span>' : '') +
      ' <span class="dim">soleil ' + (el >= 0 ? '+' : '') + el.toFixed(0) + '° à ' + esc(qth) + '</span>';
    return jour ? 'day' : 'night';
  }

  function rendSolaire(d, per) {
    $('solar-indices').innerHTML = [
      carteIdx('Flux solaire (SFI)', d.solarflux, sfi(d.solarflux), ' SFU'),
      carteIdx('Taches (SN)', d.sunspots, { l: '', c: parseInt(d.sunspots, 10) > 100 ? 'bon' : 'moyen', n: 'Plus de taches, ionisation plus forte, bandes hautes meilleures.' }),
      carteIdx('Indice K', d.kindex, kIdx(d.kindex)),
      carteIdx('Indice A', d.aindex, aIdx(d.aindex)),
      carteIdx('Rayons X', d.xray, xray(d.xray)),
      carteIdx('Vent solaire', d.solarwind, { l: parseFloat(d.solarwind) > 500 ? 'rapide' : 'nominal', c: parseFloat(d.solarwind) > 600 ? 'moyen' : 'bon', n: 'Champ Bz ' + (d.magneticfield || '?') + ' nT ; très négatif : orages.' }, ' km/s'),
      carteIdx('Bruit', d.signalnoise, { l: '', c: '', n: 'Niveau de bruit de bande estimé.' }),
      carteIdx('Aurore', d.aurora, { l: parseInt(d.aurora, 10) > 3 ? 'active' : 'faible', c: parseInt(d.aurora, 10) > 5 ? 'mauvais' : parseInt(d.aurora, 10) > 3 ? 'moyen' : 'bon', n: 'Ovale jusqu\'à ' + (d.latdegree || '?') + '° de latitude.' }),
    ].join('');
    const hf = d.hf_conditions || {};
    $('hf-matrix').innerHTML = '<table class="hf-table hf-cond"><thead><tr><th>Bandes</th><th class="' + (per === 'day' ? 'now' : '') + '">☀ Jour</th><th class="' + (per === 'night' ? 'now' : '') + '">☾ Nuit</th></tr></thead><tbody>' +
      GROUPES.map(g => { const j = (hf[g.k] || {}).day || '', n = (hf[g.k] || {}).night || '';
        return '<tr><td><b>' + g.b + '</b> <span class="dim">' + g.f + '</span></td><td class="cond ' + condCls(j) + (per === 'day' ? ' now' : '') + '">' + esc(COND[j] || j || '—') + '</td>' +
          '<td class="cond ' + condCls(n) + (per === 'night' ? ' now' : '') + '">' + esc(COND[n] || n || '—') + '</td></tr>'; }).join('') + '</tbody></table>';
    const v = GROUPES.map(g => ({ g, v: (hf[g.k] || {})[per] || '' }));
    const bons = v.filter(x => x.v === 'Good'), moyens = v.filter(x => x.v === 'Fair');
    $('best-bands').innerHTML = (bons.length ? '<p class="bon"><b>À privilégier</b> ' + bons.map(x => x.g.b).join(' · ') + '</p>' : '') +
      (moyens.length ? '<p class="moyen"><b>Possibles</b> ' + moyens.map(x => x.g.b).join(' · ') + '</p>' : '') +
      (!bons.length && !moyens.length ? '<p class="mauvais"><b>Difficile</b> conditions faibles sur toutes les bandes ' + (per === 'day' ? 'de jour' : 'de nuit') + '.</p>' : '');
    const vh = d.vhf_conditions || {};
    const l = [['Aurore VHF (hémisphère nord)', vh['vhf-aurora|northern_hemi']], ['E sporadique Europe', vh['E-Skip|europe']], ['Es 6 m Europe', vh['E-Skip|europe_6m']],
      ['Es 4 m Europe', vh['E-Skip|europe_4m']], ['Es Amérique du Nord', vh['E-Skip|north_america']]].filter(x => x[1] != null);
    $('vhf-cond').innerHTML = l.map(x => '<div class="vhf"><span>' + esc(x[0]) + '</span><b class="' + condCls(x[1]) + '">' + esc(COND[x[1]] || x[1]) + '</b></div>').join('') || '<p class="dim">Pas de données VHF.</p>';
    $('bands-maj').textContent = d.updated ? 'HamQSL ' + String(d.updated).trim() : '';
  }

  async function chargeSolaire() {
    try {
      const d = await json('/api/dx/solar');
      if (!d || !d.solarflux) throw new Error('pas encore de données');
      solaire = d;
      rendSolaire(d, periode());
    } catch (e) { $('bands-maj').textContent = 'HamQSL indisponible'; periode(); }
  }

  // ── The map ───────────────────────────────────────────────────────────────
  const BANDES = ['', '80m', '40m', '30m', '20m', '17m', '15m', '12m', '10m', '6m'];
  let bande = '', source = 'psk', iono = '', dx = null;
  const m = carte('prop-map', 'modèle ionosphérique © <a href="https://prop.kc2g.com/" rel="noopener">KC2G</a> CC BY 4.0');
  const cIono = L.layerGroup().addTo(m), cNuit = L.layerGroup().addTo(m), cTaches = L.layerGroup().addTo(m),
    cChemins = L.layerGroup().addTo(m), cP2p = L.layerGroup().addTo(m), cQth = L.layerGroup().addTo(m);

  function dominante(b) { let best = '', n = -1; for (const k in b) if (b[k] > n) { n = b[k]; best = k; } return best; }

  function taches(l) {
    cTaches.clearLayers();
    if (!l.length) return;
    const max = Math.max.apply(null, l.map(b => b.count));
    for (const b of l) {
      const col = couleur(bande || dominante(b.bands || {}));
      const lignes = [b.count + ' reports' + (b.snr != null ? ' · SNR moyen ' + b.snr + ' dB' : ''),
        Object.entries(b.bands || {}).sort((x, y) => y[1] - x[1]).map(e => e[0] + ' : ' + e[1]).join(' · ')];
      if (b.dxcc && b.dxcc.length) lignes.push(b.dxcc.join(', '));
      L.circleMarker([b.lat, b.lon], { radius: Math.min(40, 5 + Math.sqrt(b.count) * 2.4), stroke: false, fillColor: col, fillOpacity: 0.15 + 0.5 * b.count / max })
        .bindTooltip(bulle(lignes), { className: 'hf-bulle' }).addTo(cTaches);
      L.circleMarker([b.lat, b.lon], { radius: 2, stroke: false, fillColor: col, fillOpacity: 0.95, interactive: false }).addTo(cTaches);
    }
  }

  function chemins(l) {
    cChemins.clearLayers(); cQth.clearLayers();
    const q = loc(qth);
    if (q) L.circleMarker(q, { radius: 6, color: '#fff', weight: 1.5, fillColor: '#4d9eff', fillOpacity: 1 }).bindTooltip(bulle([qth, 'votre QTH']), { className: 'hf-bulle' }).addTo(cQth);
    for (const p of l) {
      // heard: a station of the QTH's region heard far away (solid) ; hearing: a distant one heard here (dashed).
      const entendu = p.dir === 'heard';
      L.polyline(orthodromie([p.lat1, p.lon1], [p.lat2, p.lon2], 40), { color: couleur(p.band), weight: 1.2, opacity: 0.6, dashArray: entendu ? null : '3 4' })
        .bindTooltip(bulle([entendu ? p.call + ' vous entend' : 'vous entendez ' + p.call, p.band + ' · SNR ' + p.snr + ' dB']), { className: 'hf-bulle', sticky: true }).addTo(cChemins);
    }
  }

  function analyse(d) {
    const parBande = d.by_band || {}, hf = (d.solar && d.solar.hf_conditions) || {}, per = periode();
    const ordre = ['80m', '40m', '30m', '20m', '17m', '15m', '12m', '10m', '6m'].filter(b => parBande[b]);
    const max = Math.max.apply(null, [1].concat(ordre.map(b => parBande[b])));
    const nomSource = { psk: 'PSKReporter', wspr: 'WSPR', rbn: 'RBN' }[source];
    const lignes = ordre.map(b => {
      const n = parBande[b], mod = GROUPE_DE[b] ? (hf[GROUPE_DE[b]] || {})[per] : '', ouvert = n >= max * 0.15;
      let v, c;
      if (ouvert && mod === 'Good') { v = 'Ouverte ✓✓'; c = 'bon'; } else if (ouvert || mod === 'Good') { v = 'Ouverte'; c = 'bon'; }
      else if (n > 0 || mod === 'Fair') { v = 'Marginale'; c = 'moyen'; } else { v = 'Calme'; c = 'mauvais'; }
      return '<div class="pa-l"><b style="color:' + couleur(b) + '">' + b + '</b><span class="barre"><i style="width:' + Math.round(n / max * 100) + '%;background:' + couleur(b) + '"></i></span>' +
        '<span class="num">' + n + '</span><span class="' + condCls(mod) + '">' + esc(mod ? COND[mod] : '—') + '</span><span class="' + c + '">' + v + '</span></div>';
    }).join('');
    $('prop-analysis').innerHTML = '<div class="pa-l pa-t"><span>Bande</span><span>Activité mesurée (' + (source === 'psk' ? 'derniers reports PSKReporter' : nomSource + ', ' + Math.round((d.since || 900) / 60) + ' min') + ')</span><span>Spots</span><span>Modèle (' + (per === 'day' ? 'jour' : 'nuit') + ')</span><span>Verdict</span></div>' +
      (lignes || '<p class="dim">Aucune activité reçue.</p>');
  }

  async function chargeCarte() {
    $('prop-status').textContent = 'chargement…';
    try {
      const d = await json('/api/prop/map?band=' + encodeURIComponent(bande) + '&since=900&qth=' + encodeURIComponent(qth) + '&source=' + source);
      taches(d.blobs || []); chemins(d.qth_paths || []); analyse(d);
      let t = (d.total || 0) + ' reports · ' + (d.blobs || []).length + ' taches';
      if (d.error) t += ' · ' + d.error;
      if (source === 'rbn' && d.enabled === false) t += ' · flux RBN coupé sur ce site';
      else if (source === 'rbn' && !d.connected) t += ' · connexion RBN…';
      $('prop-status').textContent = t;
    } catch (e) { $('prop-status').textContent = 'indisponible (' + e.message + ')'; }
  }

  function couleurMuf(mhz) {
    const bords = [[1.8, '160m'], [3.5, '80m'], [5.3, '60m'], [7, '40m'], [10.1, '30m'], [14, '20m'], [18, '17m'], [21, '15m'], [24.8, '12m'], [28, '10m'], [50, '6m']];
    let b = '40m'; for (const [f, n] of bords) if (mhz >= f - 0.5) b = n; return couleur(b);
  }
  async function chargeIono() {
    cIono.clearLayers();
    if (!iono) return;
    try {
      const d = await json('/api/prop/iono?layer=' + iono);
      for (const f of (d.features || [])) {
        if (!f.geometry || f.geometry.type !== 'LineString') continue;
        const ll = f.geometry.coordinates.map(c => [c[1], c[0]]), v = f.properties && f.properties['level-value'];
        L.polyline(ll, { color: '#000', weight: 3.5, opacity: 0.35, interactive: false }).addTo(cIono);
        L.polyline(ll, { color: couleurMuf(v || 0), weight: 1.8, opacity: 0.95 }).bindTooltip(bulle([(iono === 'muf' ? 'MUF ' : 'foF2 ') + v + ' MHz']), { className: 'hf-bulle', sticky: true }).addTo(cIono);
      }
    } catch (e) { /* the model is optional */ }
  }

  function traceP2p() {
    cP2p.clearLayers();
    const q = loc(qth), info = $('p2p-info');
    if (!dx || !q) { info.textContent = ''; return; }
    const p2 = [dx.lat, dx.lon], a = angle(q, p2), c = Math.round(cap(q, p2)), sp = Math.round(a * R_KM), lp = Math.round((2 * Math.PI - a) * R_KM);
    L.polyline(arc(q, c, 0, a, 96), { color: '#4dffa7', weight: 2, opacity: 0.9 }).bindTooltip(bulle(['Trajet court', sp + ' km · cap ' + c + '°']), { className: 'hf-bulle', sticky: true }).addTo(cP2p);
    L.polyline(arc(q, c, a, 2 * Math.PI, 160), { color: '#4dffa7', weight: 1.6, opacity: 0.7, dashArray: '6 6' }).bindTooltip(bulle(['Trajet long', lp + ' km · cap ' + ((c + 180) % 360) + '°']), { className: 'hf-bulle', sticky: true }).addTo(cP2p);
    L.circleMarker(p2, { radius: 6, color: '#fff', weight: 1.5, fillColor: '#4dffa7', fillOpacity: 1 }).bindTooltip(bulle([dx.label]), { className: 'hf-bulle' }).addTo(cP2p);
    info.textContent = dx.label + ' — court ' + sp + ' km à ' + c + '° · long ' + lp + ' km à ' + ((c + 180) % 360) + '°';
  }

  async function p2p() {
    const t = $('p2p-dx').value.trim().toUpperCase(), info = $('p2p-info');
    dx = null;
    if (!t) { traceP2p(); return; }
    const p = loc(t);
    if (p) dx = { lat: p[0], lon: p[1], label: t };
    else {
      info.textContent = '…';
      try {
        const d = await json('/api/hf/indicatif?call=' + encodeURIComponent(t));
        if (typeof d.lat === 'number') dx = { lat: d.lat, lon: d.lon, label: d.call + ' (centre : ' + (d.entity || 'pays') + ')' };
      } catch (e) { /* not found */ }
    }
    if (!dx) { cP2p.clearLayers(); info.textContent = 'introuvable (un locator donne la position exacte)'; return; }
    traceP2p();
  }

  function puces() {
    $('prop-bands').innerHTML = '<span class="hf-puces-titre">Bande</span>' + BANDES.map(b => '<button type="button" class="puce' + (b === bande ? ' actif' : '') + '" data-band="' + b + '">' + (b || 'Toutes') + '</button>').join('');
  }

  function groupe(id, attr, f) {
    $(id).addEventListener('click', e => {
      const b = e.target.closest('button[data-' + attr + ']'); if (!b) return;
      $(id).querySelectorAll('button[data-' + attr + ']').forEach(x => x.classList.toggle('actif', x === b));
      f(b.dataset[attr]);
    });
  }

  $('qth').value = qth;
  $('qth').addEventListener('change', () => {
    const v = $('qth').value.trim().toUpperCase();
    if (!H.LOC.test(v)) { $('qth-err').textContent = 'Locator : 4 ou 6 caractères (JN18FS).'; return; }
    $('qth-err').textContent = ''; qth = v; $('qth').value = v; H.qthGarder(CLE_QTH, v);
    if (solaire) rendSolaire(solaire, periode()); chargeCarte(); traceP2p();
  });
  $('qth-form').addEventListener('submit', e => { e.preventDefault(); $('qth').dispatchEvent(new Event('change')); });
  groupe('prop-src', 'src', v => { source = v; chargeCarte(); });
  groupe('prop-iono', 'iono', v => { iono = v; chargeIono(); });
  $('prop-bands').addEventListener('click', e => { const b = e.target.closest('button[data-band]'); if (!b) return; bande = b.dataset.band; puces(); chargeCarte(); });
  $('p2p-go').addEventListener('click', p2p);
  $('p2p-dx').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); p2p(); } });
  $('p2p-clear').addEventListener('click', () => { $('p2p-dx').value = ''; dx = null; traceP2p(); });

  puces(); periode(); terminateur(cNuit);
  chargeSolaire(); chargeCarte();
  setInterval(chargeSolaire, 15 * 60 * 1000);
  setInterval(chargeCarte, 150 * 1000);
  setInterval(() => terminateur(cNuit), 60 * 1000);
  setInterval(chargeIono, 15 * 60 * 1000);
  H.etat('', '');
})();
