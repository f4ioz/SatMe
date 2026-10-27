/*
 * F4IOZ site — « Ciel »: the station's sky now. Every second, each
 * satellite's position from its elements (satellite.js, SGP4, the site's
 * AMSAT elements): those above the horizon on the sky (north up, east to
 * the right as seen from below… on a map of the sky: east on the right),
 * with their track for the coming minutes; the next ones to rise below.
 */
(function () {
  const D = JSON.parse(document.getElementById('ciel-donnees').textContent);
  const $ = id => document.getElementById(id);
  const svg = $('ciel-svg'), liste = $('ciel-l'), bientot = $('ciel-bientot');
  const LOC = /^[A-R]{2}[0-9]{2}([A-X]{2})?$/;
  const r2d = 180 / Math.PI;
  let loc = D.locator, tous = false, sats = [], obs = null, favoris = null;
  try {
    const l = localStorage.getItem('site-loc'); if (l && LOC.test(l)) loc = l;
    tous = localStorage.getItem('site-ciel-tous') === '1';
    const p = JSON.parse(localStorage.getItem('site-pass') || '{}');
    if (p.sats) favoris = p.sats.split(',').map(x => x.trim().toUpperCase()).filter(Boolean);
  } catch (x) {}
  $('ciel-loc').value = loc; $('ciel-tous').checked = tous;
  $('ciel-form').onsubmit = e => { e.preventDefault(); change(); };
  $('ciel-loc').onchange = change;
  $('ciel-tous').onchange = () => { tous = $('ciel-tous').checked; try { localStorage.setItem('site-ciel-tous', tous ? '1' : '0'); } catch (x) {} tic(); };
  function change() {
    const l = $('ciel-loc').value.trim().toUpperCase();
    if (!LOC.test(l)) { $('ciel-loc').setCustomValidity('Locator : JN18FS'); $('ciel-loc').reportValidity(); return; }
    $('ciel-loc').setCustomValidity(''); loc = l;
    try { localStorage.setItem('site-loc', l); } catch (x) {}
    charge();
  }
  async function charge() {
    try {
      const d = await (await fetch('/api/orbites?loc=' + encodeURIComponent(loc))).json();
      obs = d.obs ? { latitude: d.obs.lat / r2d, longitude: d.obs.lon / r2d, height: 0 } : null;
      const fav = d.favoris || [];
      sats = (d.sats || []).map(s => { let rec = null; try { rec = satellite.twoline2satrec(s.l1, s.l2); } catch (x) {} return rec ? { nom: s.nom, catnum: s.catnum, rec: rec, fav: fav.includes(s.catnum) } : null; })
        .filter(Boolean);
      if (favoris) sats.forEach(s => { s.fav = favoris.includes(s.nom.toUpperCase()) || favoris.includes(String(s.catnum)) || (favoris.includes('ISS') && s.catnum === 25544); });
      tic();
    } catch (x) { liste.innerHTML = '<tr><td colspan="5" class="dim">Les satellites n’ont pas pu être chargés.</td></tr>'; }
  }
  function regarde(s, t) {
    const r = satellite.propagate(s.rec, t);
    if (!r || !r.position || typeof r.position !== 'object') return null;
    const la = satellite.ecfToLookAngles(obs, satellite.eciToEcf(r.position, satellite.gstime(t)));
    return { az: (la.azimuth * r2d + 360) % 360, el: la.elevation * r2d, km: la.rangeSat };
  }
  const pol = (az, el) => { const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90), a = az / r2d; return [r * Math.sin(a), -r * Math.cos(a)]; };
  const esc = x => String(x).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
  const p2 = n => String(n).padStart(2, '0');
  let prochainsT = 0, prochains = [];
  function tic() {
    if (!obs) return;
    const t = new Date();
    $('ciel-heure').textContent = p2(t.getHours()) + ':' + p2(t.getMinutes()) + ':' + p2(t.getSeconds());
    const choisis = tous ? sats : sats.filter(s => s.fav);
    const vus = [];
    for (const s of choisis) { const e = regarde(s, t); if (e && e.el > 0) vus.push({ s: s, e: e }); }
    vus.sort((a, b) => b.e.el - a.e.el);
    let h = '';
    for (const el of [0, 30, 60]) h += '<circle class="g" r="' + (100 * (1 - el / 90)) + '"/>';
    h += '<path class="g" d="M0-100V100M-100 0H100"/>';
    [['N', 0, -104], ['E', 106, 3], ['S', 0, 111], ['O', -106, 3]].forEach(c => { h += '<text class="c" x="' + c[1] + '" y="' + c[2] + '">' + c[0] + '</text>'; });
    for (const v of vus.slice(0, 40)) {
      // The coming ten minutes, a point a minute.
      let pts = '';
      for (let k = 0; k <= 10; k++) { const e = regarde(v.s, new Date(t.getTime() + k * 60000)); if (!e || e.el < 0) break; const p = pol(e.az, e.el); pts += p[0].toFixed(1) + ',' + p[1].toFixed(1) + ' '; }
      const p = pol(v.e.az, v.e.el);
      h += '<polyline class="tr" points="' + pts + '"/><circle class="s' + (v.s.fav ? ' fav' : '') + '" cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="3.2"/>' +
        '<text class="n" x="' + (p[0] + 5).toFixed(1) + '" y="' + (p[1] - 4).toFixed(1) + '">' + esc(v.s.nom) + '</text>';
    }
    svg.innerHTML = h;
    $('ciel-n').textContent = vus.length + (vus.length > 1 ? ' satellites' : ' satellite');
    liste.innerHTML = vus.length ? vus.slice(0, 60).map(v => '<tr><td class="name">' + esc(v.s.nom) + '</td><td class="num">' + v.e.az.toFixed(0) + '°</td><td class="num">' +
      v.e.el.toFixed(1) + '°</td><td class="num">' + Math.round(v.e.km).toLocaleString('fr-FR') + ' km</td><td><a href="/sat/sked?sat=' + v.s.catnum + '">Sked</a></td></tr>').join('')
      : '<tr><td colspan="5" class="dim">Aucun satellite ' + (tous ? '' : 'favori ') + 'au-dessus de l’horizon.</td></tr>';
    // The next to rise: looked for every thirty seconds, two hours ahead, a step of thirty seconds.
    if (Date.now() - prochainsT > 30000) {
      prochainsT = Date.now(); prochains = [];
      for (const s of choisis.slice(0, 60)) {
        const e0 = regarde(s, t); if (!e0 || e0.el > 0) continue;
        for (let k = 1; k <= 240; k++) { const tt = new Date(t.getTime() + k * 30000), e = regarde(s, tt); if (e && e.el > 0) { prochains.push({ nom: s.nom, t: tt }); break; } }
      }
      prochains.sort((a, b) => a.t - b.t);
      bientot.innerHTML = prochains.slice(0, 8).map(p => '<li><span class="name">' + esc(p.nom) + '</span><span class="num">' + p2(p.t.getHours()) + ':' + p2(p.t.getMinutes()) +
        '</span><span class="num dim">dans ' + Math.round((p.t - Date.now()) / 60000) + ' min</span></li>').join('') || '<li class="dim">Rien dans les deux heures.</li>';
    }
  }
  charge();
  setInterval(tic, 1000);
})();
