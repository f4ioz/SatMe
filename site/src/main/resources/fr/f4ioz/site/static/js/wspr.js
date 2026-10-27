/*
 * F4IOZ site — WSPR: the bands' activity (wspr.live, or RBN's live skimmers)
 * and a callsign's footprint on the map: who hears it, whom it hears.
 */
(function () {
  'use strict';
  const H = window.HF, { $, esc, couleur, orthodromie, carte, bulle, json } = H;
  let source = 'wspr', direction = 'tx', m = null, couche = null;

  function onglets(id, attr, f) {
    $(id).addEventListener('click', e => {
      const b = e.target.closest('button[data-' + attr + ']'); if (!b) return;
      $(id).querySelectorAll('button[data-' + attr + ']').forEach(x => { x.classList.toggle('actif', x === b); x.setAttribute('aria-selected', x === b ? 'true' : 'false'); });
      f(b.dataset[attr]);
    });
  }

  function barres(lignes, tete) { $('wspr-bands').innerHTML = '<div class="wb wb-t">' + tete + '</div>' + lignes.join(''); }
  function vide(t) { $('wspr-bands').innerHTML = '<p class="vide">' + esc(t) + '</p>'; }

  async function bandes() {
    H.etat('chargement…', 'attente');
    const min = +$('wspr-minutes').value;
    try {
      if (source === 'rbn') {
        const d = await json('/api/rbn/bands?minutes=' + Math.min(30, min));
        $('wspr-note').textContent = min > 30 ? 'RBN : 30 min au plus (le site garde 35 min).' : '';
        if (!d.enabled) { vide('Le flux RBN est coupé sur ce site.'); H.etat('RBN coupé', 'attente'); return; }
        const l = d.bands || [];
        if (!l.length) { vide(d.connected ? 'Aucun spot RBN pour le moment.' : 'Connexion au Reverse Beacon Network… réessayer dans quelques secondes.'); H.etat(d.connected ? 'RBN : 0 spot' : 'connexion RBN…', 'attente'); return; }
        const max = Math.max.apply(null, l.map(b => b.spots));
        barres(l.map(b => '<div class="wb"><b style="color:' + couleur(b.band) + '">' + esc(b.band) + '</b><span class="barre"><i style="width:' + Math.round(b.spots / max * 100) + '%;background:' + couleur(b.band) + '"></i></span>' +
          '<span class="num">' + b.spots + '</span><span>' + b.skimmers + ' skimmers</span><span>' + b.dx + ' DX</span><span>' + b.snr_avg + ' / ' + b.snr_max + ' dB</span><span></span></div>'),
          '<span>Bande</span><span>Spots (' + d.minutes + ' min)</span><span></span><span>Skimmers</span><span>DX</span><span>SNR moy. / max</span><span></span>');
        H.etat(d.total + ' spots RBN', 'ok');
      } else {
        $('wspr-note').textContent = '';
        const d = await json('/api/wspr/bands?minutes=' + min);
        if (d.error) { vide('wspr.live indisponible (' + d.error + ')'); H.etat('wspr.live indisponible', 'err'); return; }
        const l = d.bands || [];
        if (!l.length) { vide('Aucune activité WSPR sur la fenêtre.'); H.etat('0 spot', 'attente'); return; }
        const max = Math.max.apply(null, l.map(b => b.spots));
        barres(l.map(b => {
          const portee = b.dist_max >= 8000 ? 'DX mondial' : b.dist_max >= 3000 ? 'DX' : b.dist_max >= 1000 ? 'régional' : 'local';
          const c = b.dist_max >= 3000 ? 'bon' : b.dist_max >= 1000 ? 'moyen' : 'mauvais';
          return '<div class="wb"><b style="color:' + couleur(b.band) + '">' + esc(b.band) + '</b><span class="barre"><i style="width:' + Math.round(b.spots / max * 100) + '%;background:' + couleur(b.band) + '"></i></span>' +
            '<span class="num">' + b.spots + '</span><span>' + b.reporters + ' rx</span><span>méd. ' + b.dist_med + ' · max ' + b.dist_max + ' km</span><span>' + b.snr_avg + ' dB</span><span class="' + c + '">' + portee + '</span></div>';
        }), '<span>Bande</span><span>Spots (' + d.minutes + ' min)</span><span></span><span>Récepteurs</span><span>Distance</span><span>SNR moy.</span><span>Portée</span>');
        H.etat(l.reduce((s, b) => s + b.spots, 0).toLocaleString('fr-FR') + ' spots WSPR', 'ok');
      }
    } catch (e) { vide('Erreur : ' + e.message); H.etat('erreur', 'err'); }
  }

  async function empreinte() {
    const call = $('wspr-call').value.trim().toUpperCase();
    $('wspr-fp-status').textContent = 'chargement…';
    if (!m) { m = carte('wspr-map'); couche = L.layerGroup().addTo(m); }
    try {
      const d = source === 'rbn'
        ? await json('/api/rbn/footprint?call=' + encodeURIComponent(call) + '&minutes=15')
        : await json('/api/wspr/footprint?call=' + encodeURIComponent(call) + '&hours=' + $('wspr-hours').value + '&direction=' + direction);
      couche.clearLayers();
      const res = $('wspr-summary');
      if (d.error) { res.textContent = (source === 'rbn' ? 'RBN' : 'wspr.live') + ' indisponible (' + d.error + ')'; $('wspr-fp-status').textContent = 'indisponible'; return; }
      const l = d.spots || [];
      if (!l.length) {
        res.textContent = 'Aucun spot ' + (source === 'rbn' ? 'RBN' : 'WSPR') + ' pour ' + (d.call || call) + (source === 'rbn' ? ' : la station émet-elle en CW ou RTTY en ce moment ?' : ' : la station émet-elle (ou reçoit-elle) en WSPR ?');
        $('wspr-fp-status').textContent = '0 spot'; return;
      }
      const home = d.home;
      if (home) L.circleMarker([home.lat, home.lon], { radius: 6, color: '#fff', weight: 1.5, fillColor: '#4d9eff', fillOpacity: 1 })
        .bindTooltip(bulle([d.call + (d.home_approx ? ' (centre du pays)' : '')]), { className: 'hf-bulle' }).addTo(couche);
      const vus = {};
      for (const s of l) {
        const col = couleur(s.band);
        if (home) L.polyline(orthodromie([home.lat, home.lon], [s.lat, s.lon], 40), { color: col, weight: Math.max(0.6, Math.min(3, (s.snr + 30) / 12)), opacity: 0.45, interactive: false }).addTo(couche);
        if (!vus[s.call + s.band]) {
          vus[s.call + s.band] = 1;
          L.circleMarker([s.lat, s.lon], { radius: 3, stroke: false, fillColor: col, fillOpacity: 0.9 })
            .bindTooltip(bulle([s.call + (s.loc ? ' ' + s.loc : ''), s.band + ' · ' + s.dist + ' km · ' + s.az + '° · SNR ' + s.snr + ' dB']), { className: 'hf-bulle' }).addTo(couche);
        }
      }
      if (home) m.setView([home.lat, home.lon], 2);
      const verbe = source === 'rbn' ? 'skimmers l\'entendent (CW/RTTY)' : d.direction === 'tx' ? 'stations l\'entendent' : 'stations entendues';
      res.innerHTML = '<b>' + d.count + '</b> spots · <b>' + d.reporters + '</b> ' + verbe + ' · bandes ' + (d.bands || []).map(b => '<span style="color:' + couleur(b) + '">' + esc(b) + '</span>').join(' ') +
        (d.best_dx ? ' · meilleur DX <b>' + esc(d.best_dx.dist) + ' km</b> (' + esc(d.best_dx.call) + ', ' + esc(d.best_dx.band) + ', ' + esc(d.best_dx.snr) + ' dB)' : '') +
        (d.home_approx ? ' <span class="dim">· distances depuis le centre du pays</span>' : '');
      $('wspr-fp-status').textContent = d.count + ' spots · ' + (source === 'rbn' ? '15 min' : d.hours + ' h');
    } catch (e) { $('wspr-fp-status').textContent = 'erreur'; $('wspr-summary').textContent = 'Erreur : ' + e.message; }
  }

  function sourceUi() {
    const rbn = source === 'rbn';
    $('wspr-dir').hidden = rbn; $('wspr-hours-fld').hidden = rbn;
  }

  onglets('wspr-src', 'src', v => { source = v; sourceUi(); bandes(); empreinte(); });
  onglets('wspr-dir', 'dir', v => { direction = v; empreinte(); });
  $('wspr-minutes').addEventListener('change', bandes);
  $('wspr-hours').addEventListener('change', empreinte);
  $('wspr-go').addEventListener('click', empreinte);
  $('wspr-call').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); empreinte(); } });
  sourceUi(); bandes(); empreinte();
  setInterval(bandes, 120 * 1000);
})();
