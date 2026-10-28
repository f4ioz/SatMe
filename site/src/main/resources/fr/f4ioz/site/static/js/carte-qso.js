/*
 * F4IOZ site — the logbook in figures and on a map. The figures count up
 * when they come into view; the map draws an arc of great circle from the
 * station to each square worked (the filter's), the satellites' in the accent
 * colour, the others in blue, each square a dot as big as its contacts. The
 * coastline is the site's light one. Pointer or arrow keys: a square's
 * contacts, its last callsign, its distance. « Reduced motion »: no counting,
 * no drawing in, the map at once.
 */
(function () {
  'use strict';
  const reduit = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const fmt = n => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

  /* ------------------------------------------------------------ the figures */
  const chiffres = document.querySelector('.carnet-chiffres');
  if (chiffres && !reduit && 'IntersectionObserver' in window) {
    const l = Array.from(chiffres.querySelectorAll('[data-compte]'));
    l.forEach(b => { b.textContent = '0'; });
    const io = new IntersectionObserver(e => {
      if (!e[0].isIntersecting) return; io.disconnect();
      const t0 = performance.now(), d = 1300;
      const f = now => { const k = Math.min(1, (now - t0) / d), e2 = 1 - Math.pow(1 - k, 4); l.forEach(b => { b.textContent = fmt(+b.dataset.compte * e2); }); if (k < 1) requestAnimationFrame(f); };
      requestAnimationFrame(f);
    }, { threshold: .3 });
    io.observe(chiffres);
  }

  /* ------------------------------------------------------------ the map */
  const cv = document.querySelector('[data-carte-qso]'), brut = document.getElementById('carte-qso-donnees');
  if (!cv || !brut) return;
  const D = JSON.parse(brut.textContent), cadre = cv.parentNode, bulle = document.querySelector('[data-carte-bulle]');
  const ctx = cv.getContext('2d'), D2R = Math.PI / 180;
  let W = 0, H = 0, dpr = 1, vue = null, terres = null, C = null, debut = 0, arcs = [], choisi = -1;
  const carres = D.carres || [];

  function couleurs() {
    const cs = getComputedStyle(cv), v = k => cs.getPropertyValue(k).trim();
    C = { ocean: v('--m-ocean'), terre: v('--m-terre'), cote: v('--m-cote'), grille: v('--m-grille'), sat: v('--accent'), ter: v('--info'), station: v('--amber'), texte: v('--text'), halo: v('--g-halo') };
  }
  /** The view: the station and every square, a margin round them, at the canvas's shape (longitudes shrunk by the cosine of the middle latitude). */
  function cadrage() {
    let la0 = D.lat, la1 = D.lat, lo0 = D.lon, lo1 = D.lon;
    carres.forEach(c => { la0 = Math.min(la0, c.la); la1 = Math.max(la1, c.la); lo0 = Math.min(lo0, c.lo); lo1 = Math.max(lo1, c.lo); });
    let dla = Math.max(16, (la1 - la0) * 1.25), dlo = Math.max(26, (lo1 - lo0) * 1.2);
    const mla = (la0 + la1) / 2, mlo = (lo0 + lo1) / 2, k = Math.cos(Math.max(-70, Math.min(70, mla)) * D2R);
    if (dlo * k / dla > W / H) dla = dlo * k * H / W; else dlo = dla * W / H / k;
    dla = Math.min(dla, 170); dlo = Math.min(dlo, 360);
    vue = { la0: Math.max(-85, mla - dla / 2), la1: Math.min(85, mla + dla / 2), lo0: mlo - dlo / 2, lo1: mlo + dlo / 2 };
  }
  const X = lo => (lo - vue.lo0) / (vue.lo1 - vue.lo0) * W;
  const Y = la => (vue.la1 - la) / (vue.la1 - vue.la0) * H;
  /** The great circle from the station to a square, [n] points (longitudes kept continuous). */
  function arc(la, lo, n) {
    const v = (a, b) => [Math.cos(a * D2R) * Math.cos(b * D2R), Math.cos(a * D2R) * Math.sin(b * D2R), Math.sin(a * D2R)];
    const A = v(D.lat, D.lon), B = v(la, lo), w = Math.acos(Math.max(-1, Math.min(1, A[0] * B[0] + A[1] * B[1] + A[2] * B[2])));
    const l = [];
    let prec = D.lon;
    for (let i = 0; i <= n; i++) {
      const t = i / n, s = Math.sin(w) || 1, a = Math.sin((1 - t) * w) / s, b = Math.sin(t * w) / s;
      const p = w < 1e-6 ? A : [a * A[0] + b * B[0], a * A[1] + b * B[1], a * A[2] + b * B[2]];
      let o = Math.atan2(p[1], p[0]) / D2R; while (o - prec > 180) o -= 360; while (o - prec < -180) o += 360; prec = o;
      l.push([Math.asin(Math.max(-1, Math.min(1, p[2]))) / D2R, o]);
    }
    return l;
  }
  function taille() {
    const r = cadre.getBoundingClientRect();
    dpr = Math.min(2, window.devicePixelRatio || 1); W = Math.max(10, Math.round(r.width)); H = Math.max(10, Math.round(r.height));
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    cadrage();
    const max = Math.max(...carres.map(c => c.km), 1);
    arcs = carres.map(c => ({ c: c, l: arc(c.la, c.lo, 48), retard: c.km / max * .5 }));
  }
  function dessine(now) {
    if (!W || !C) return;
    const k = reduit ? 1 : !debut ? 0 : Math.min(1, (now - debut) / 2200);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = C.ocean; ctx.fillRect(0, 0, W, H);
    // The graticule, every 10°.
    ctx.strokeStyle = C.grille; ctx.lineWidth = 1; ctx.beginPath();
    for (let la = Math.ceil(vue.la0 / 10) * 10; la <= vue.la1; la += 10) { ctx.moveTo(0, Y(la)); ctx.lineTo(W, Y(la)); }
    for (let lo = Math.ceil(vue.lo0 / 10) * 10; lo <= vue.lo1; lo += 10) { ctx.moveTo(X(lo), 0); ctx.lineTo(X(lo), H); }
    ctx.stroke();
    if (terres) {
      ctx.beginPath();
      for (const dec of [-360, 0, 360]) {
        if (vue.lo1 < -180 + dec || vue.lo0 > 180 + dec) continue;
        terres.forEach(a => { for (let i = 0; i + 1 < a.length; i += 2) { const x = X(a[i] + dec), y = Y(a[i + 1]); if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y); } ctx.closePath(); });
      }
      ctx.fillStyle = C.terre; ctx.fill('evenodd'); ctx.strokeStyle = C.cote; ctx.lineWidth = .8; ctx.stroke();
    }
    // The arcs, drawn in from the station (the nearer first), then the squares.
    const ordre = arcs.slice().sort((a, b) => b.c.n - a.c.n);
    ordre.slice().reverse().forEach(a => {
      const p = Math.max(0, Math.min(1, (k - a.retard) / .5)); if (p <= 0) return;
      const n = Math.max(2, Math.round(a.l.length * p)), sat = a.c.s * 2 >= a.c.n;
      ctx.strokeStyle = sat ? C.sat : C.ter; ctx.globalAlpha = .28 + Math.min(.45, a.c.n * .06); ctx.lineWidth = 1 + Math.min(1.6, a.c.n * .2);
      ctx.beginPath(); a.l.slice(0, n).forEach((q, i) => { if (i) ctx.lineTo(X(q[1]), Y(q[0])); else ctx.moveTo(X(q[1]), Y(q[0])); }); ctx.stroke();
    });
    ctx.globalAlpha = 1;
    ordre.forEach((a, i) => {
      const p = Math.max(0, Math.min(1, (k - a.retard - .45) / .1)); if (p <= 0) return;
      const sat = a.c.s * 2 >= a.c.n, r = (2.4 + Math.sqrt(a.c.n) * 1.6) * p, x = X(a.c.lo), y = Y(a.c.la);
      ctx.fillStyle = sat ? C.sat : C.ter; ctx.globalAlpha = .25; ctx.beginPath(); ctx.arc(x, y, r * 2.2, 0, 7); ctx.fill();
      ctx.globalAlpha = 1; ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.fill();
      if (arcs.indexOf(a) === choisi) { ctx.strokeStyle = C.texte; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(x, y, r + 4, 0, 7); ctx.stroke(); }
    });
    // The station.
    const sx = X(D.lon), sy = Y(D.lat);
    ctx.fillStyle = C.station; ctx.beginPath(); ctx.arc(sx, sy, 5, 0, 7); ctx.fill();
    ctx.strokeStyle = C.station; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(sx, sy, 10, 0, 7); ctx.stroke();
    ctx.font = '700 12px ' + getComputedStyle(document.body).fontFamily; ctx.lineWidth = 3; ctx.strokeStyle = C.halo; ctx.lineJoin = 'round';
    ctx.strokeText(D.locator, sx + 13, sy + 4); ctx.fillStyle = C.station; ctx.fillText(D.locator, sx + 13, sy + 4);
    if (debut && k < 1) requestAnimationFrame(dessine);
  }
  function montre(i) {
    choisi = i;
    if (i < 0) { bulle.hidden = true; dessine(performance.now()); return; }
    const c = arcs[i].c;
    bulle.innerHTML = '<b>' + c.g + '</b> · ' + c.n + ' QSO' + (c.s ? ' (' + c.s + ' par satellite)' : '') + '<br>dernier : <span class="mono">' + String(c.c).replace(/[<>&"]/g, '') + '</span> · ' + fmt(c.km) + ' km';
    bulle.hidden = false;
    const x = X(c.lo), y = Y(c.la);
    bulle.style.left = Math.min(W - bulle.offsetWidth - 6, Math.max(6, x + 12)) + 'px';
    bulle.style.top = Math.max(6, Math.min(H - bulle.offsetHeight - 6, y - bulle.offsetHeight - 10)) + 'px';
    dessine(performance.now());
  }
  cv.addEventListener('pointermove', e => {
    const r = cv.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    let m = -1, d = 14 * 14;
    arcs.forEach((a, i) => { const dx = X(a.c.lo) - x, dy = Y(a.c.la) - y, q = dx * dx + dy * dy; if (q < d) { d = q; m = i; } });
    if (m !== choisi) montre(m);
  });
  cv.addEventListener('pointerleave', () => montre(-1));
  cv.addEventListener('keydown', e => {
    if (!arcs.length) return;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { e.preventDefault(); montre((choisi + 1) % arcs.length); }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { e.preventDefault(); montre((choisi - 1 + arcs.length) % arcs.length); }
    else if (e.key === 'Escape') montre(-1);
  });
  cv.addEventListener('blur', () => montre(-1));

  function lance() {
    couleurs(); taille();
    fetch('/api/carte/traits-legers').then(r => r.json()).then(t => { terres = t; dessine(performance.now()); }).catch(() => {});
    if (reduit || !('IntersectionObserver' in window)) { debut = performance.now() - 1e6; dessine(performance.now()); return; }
    dessine(performance.now());
    const io = new IntersectionObserver(e => { if (e[0].isIntersecting) { io.disconnect(); debut = performance.now(); requestAnimationFrame(dessine); } }, { threshold: .25 });
    io.observe(cv);
  }
  if ('ResizeObserver' in window) { let premier = true; new ResizeObserver(() => { if (premier) { premier = false; return; } taille(); dessine(performance.now()); }).observe(cadre); }
  new MutationObserver(() => { couleurs(); dessine(performance.now()); }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  lance();
})();
