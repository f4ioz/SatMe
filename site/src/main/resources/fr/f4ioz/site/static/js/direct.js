/*
 * F4IOZ site — live from the station: the banner of the SSTV auto-pilot's
 * events, and the « En direct » block (QO-100's narrow band spectrum and
 * waterfall, the DATV picture, BATC's public pages). Everything is polled
 * from the site (which SatMe-PC pushes to), paused while the tab is hidden
 * or the block out of sight; every word from the station is put as text.
 */
(function () {
  'use strict';
  const bandeau = document.querySelector('[data-bandeau]');
  const bloc = document.querySelector('[data-direct]');
  if (!bandeau && !bloc) return;

  let etat = null, recuA = 0, ecart = 0;
  const maintenant = () => Date.now() + ecart;
  const p2 = n => (n < 10 ? '0' : '') + n;
  const nf = (x, d) => Number(x).toLocaleString('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d });

  function duree(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + ' s';
    const m = Math.floor(s / 60);
    if (m < 60) return m + ' min ' + p2(s % 60) + ' s';
    const h = Math.floor(m / 60);
    if (h < 48) return h + ' h ' + p2(m % 60) + ' min';
    return Math.floor(h / 24) + ' j';
  }
  function heure(t) { const d = new Date(t); return p2(d.getHours()) + ':' + p2(d.getMinutes()); }
  function el(tag, cls, texte) { const e = document.createElement(tag); if (cls) e.className = cls; if (texte != null) e.textContent = texte; return e; }

  async function charge() {
    if (document.hidden) return;
    try {
      const r = await fetch('/api/direct/etat', { cache: 'no-store' });
      if (!r.ok) return;
      etat = await r.json();
      recuA = Date.now();
      if (etat.t) ecart = etat.t - recuA;
    } catch (e) { return; }
    rendBloc();
    seconde();
  }

  // ------------------------------------------------------------------ the banner

  function rendBandeau() {
    if (!bandeau) return;
    const b = etat && etat.bandeau;
    const t = maintenant();
    const items = [];
    // « Passage en cours »: to the live block (on this page, or the live page, or the home page's).
    if (b && b.passageEnCours) {
      const a = el('a', 'bd-badge', 'Passage en cours');
      const d = etat.direct || {};
      a.href = bloc && !bloc.hidden ? '#direct' : (d.page ? '/direct' : '/#direct');
      a.prepend(el('span', 'bd-led'));
      items.push(a);
    }
    if (b && b.enCours) {
      const e = b.enCours, i = e.image || {};
      const it = el('span', 'bd-item bd-encours');
      it.appendChild(el('span', 'bd-led'));
      const nom = (e.sat || 'Satellite') + (e.mode ? ' (' + e.mode + ')' : '');
      if (e.phase === 'PRET' && e.aos > t) {
        it.appendChild(el('b', null, 'Préparation : ' + nom));
        it.appendChild(el('span', null, ' · AOS dans ' + duree(e.aos - t)));
      } else {
        it.appendChild(el('b', null, 'Réception en cours : ' + nom));
        if (i.phase === 'EN_COURS' && i.progression != null) it.appendChild(el('span', null, ' · image ' + Math.round(i.progression * 100) + ' %'));
        else if (i.phase === 'ATTENTE' && i.prochaine > t) it.appendChild(el('span', null, ' · prochaine image dans ' + duree(i.prochaine - t)));
        if (e.images) it.appendChild(el('span', null, ' · ' + e.images + (e.images > 1 ? ' images' : ' image')));
        if (e.los > t) it.appendChild(el('span', 'dim', ' · LOS ' + heure(e.los)));
      }
      if (e.campagne) it.appendChild(el('span', 'dim', ' · ' + e.campagne));
      items.push(it);
    }
    if (b && b.prochain && b.prochain.aos > t && !(b.enCours && b.enCours.aos === b.prochain.aos)) {
      const p = b.prochain, it = el('span', 'bd-item');
      it.appendChild(el('b', null, 'Prochain passage : ' + (p.sat || '')));
      it.appendChild(el('span', 'bd-compte', ' dans ' + duree(p.aos - t)));
      it.appendChild(el('span', 'dim', ' · AOS ' + heure(p.aos) + (p.el != null ? ' · ' + p.el + '°' : '') + (p.campagne ? ' · ' + p.campagne : '')));
      items.push(it);
    }
    if (b && b.derniere && b.derniere.utc) {
      const d = b.derniere;
      const it = el(d.url ? 'a' : 'span', 'bd-item');
      if (d.url) it.href = d.url;
      it.appendChild(el('b', null, 'Dernière image : ' + (d.sat || 'SSTV')));
      it.appendChild(el('span', 'dim', (d.mode ? ' · ' + d.mode : '') + ' · il y a ' + duree(t - d.utc)));
      items.push(it);
    }
    bandeau.replaceChildren.apply(bandeau, items);
    bandeau.hidden = items.length === 0;
  }

  // ------------------------------------------------------------------ the block

  const fig = bloc && bloc.querySelector('[data-direct-spectre]');
  const figDatv = bloc && bloc.querySelector('[data-direct-datv]');
  const batc = bloc && bloc.querySelector('[data-direct-batc]');
  const vide = bloc && bloc.querySelector('[data-direct-vide]');
  const meta = bloc && bloc.querySelector('[data-direct-etat]');
  const sstv = bloc && bloc.querySelector('[data-direct-sstv]');
  const titre = bloc && bloc.querySelector('[data-direct-titre]');
  let numeroSstv = -1;
  let spectreVivant = false, vu = true, numeroImage = -1;

  function rendBloc() {
    if (!bloc) return;
    const d = etat && etat.direct;
    if (!d) { bloc.hidden = true; spectreVivant = false; return; }
    bloc.hidden = false;
    // An SSTV pass of the auto-pilot: shown instead of QO-100, back by itself after LOS and the hold.
    const passage = d.vue === 'sstv' && d.sstv && sstv ? d.sstv : null;
    bloc.classList.toggle('vue-sstv', !!passage);
    if (sstv) sstv.hidden = !passage;
    if (titre) titre.textContent = passage ? 'Passage SSTV en cours' : 'En direct de la station';
    if (passage) {
      spectreVivant = false;
      [fig, figDatv, batc, vide].forEach(x => { if (x) x.hidden = true; });
      if (meta) { meta.textContent = '● ' + (passage.sat || 'SSTV'); meta.classList.add('vivant'); }
      rendSstv(passage);
      return;
    }
    const station = d.source !== 'batc';
    spectreVivant = station && d.spectre === true && !!fig;
    const v = station && d.datv && figDatv ? d.datv : null;
    const datvVivant = !!(v && v.enLigne);
    if (fig) fig.hidden = !spectreVivant && !aDesLignes;
    if (figDatv) figDatv.hidden = !v;
    if (v) rendDatv(v);
    const montrerBatc = d.source === 'batc' || (d.source === 'auto' && !spectreVivant && !datvVivant);
    if (batc) batc.hidden = !montrerBatc || batc.hasAttribute('data-jamais');
    if (vide) vide.hidden = !(d.source === 'station' && !spectreVivant && !v);
    if (meta) meta.textContent = spectreVivant || datvVivant ? '● en direct' : (montrerBatc ? 'pages du BATC' : 'hors ligne');
    if (meta) meta.classList.toggle('vivant', spectreVivant || datvVivant);
    if (spectreVivant) demarreSpectre();
  }

  function rendDatv(v) {
    const img = figDatv.querySelector('[data-datv-img]');
    if (v.image && v.image.numero !== numeroImage) { numeroImage = v.image.numero; img.src = '/api/direct/datv.jpg?n=' + numeroImage; }
    img.hidden = !v.image;
    figDatv.querySelector('[data-datv-nom]').textContent = v.service || 'DATV';
    const info = [];
    if (v.verrouille && v.mer != null) info.push('MER ' + nf(v.mer, 1) + ' dB');
    if (v.marge != null) info.push('marge ' + nf(v.marge, 1) + ' dB');
    if (v.cielKhz) info.push(nf(v.cielKhz / 1000, 3) + ' MHz');
    if (v.srKs) info.push(nf(v.srKs, 0) + ' kS/s');
    if (v.modcod) info.push(v.modcod);
    if (v.fournisseur && v.fournisseur !== v.service) info.push(v.fournisseur);
    figDatv.querySelector('[data-datv-info]').textContent = info.length ? '· ' + info.join(' · ') : '';
    ageDatv();
  }

  function ageDatv() {
    if (!figDatv || figDatv.hidden || !etat || !etat.direct || !etat.direct.datv) return;
    const v = etat.direct.datv;
    const age = v.image ? v.image.age + (Date.now() - recuA) : null;
    const hors = !v.enLigne || age == null || age > 30000;
    figDatv.querySelector('[data-datv-age]').textContent = age == null ? 'pas encore d’image' : 'dernière image il y a ' + duree(age);
    figDatv.querySelector('[data-datv-hors]').hidden = !hors;
    figDatv.classList.toggle('hors', hors);
  }

  function rendSstv(p) {
    const img = sstv.querySelector('[data-sstv-img]');
    if (p.image && p.image.numero !== numeroSstv) { numeroSstv = p.image.numero; img.src = '/api/direct/sstv.jpg?n=' + numeroSstv; }
    img.hidden = !p.image;
    sstv.querySelector('[data-sstv-attente]').hidden = !!p.image;
    sstv.querySelector('[data-sstv-sat]').textContent = (p.sat || 'Satellite') + (p.campagne ? ' · ' + p.campagne : '');
    const m = [];
    if (p.elMaintenant != null) m.push('élévation ' + p.elMaintenant + '°');
    if (p.azMaintenant != null) m.push('azimut ' + p.azMaintenant + '°');
    if (p.el != null) m.push('max ' + p.el + '°');
    if (p.mode) m.push(p.mode);
    if (p.images) m.push(p.images + (p.images > 1 ? ' images reçues' : ' image reçue'));
    sstv.querySelector('[data-sstv-mesures]').textContent = m.join(' · ');
    compteSstv();
  }

  /** The countdowns of the pass: the next picture, the LOS, the way back to QO-100. */
  function compteSstv() {
    if (!sstv || sstv.hidden || !etat || !etat.direct || !etat.direct.sstv) return;
    const p = etat.direct.sstv, i = p.prochaine || {}, t = maintenant();
    let c = '';
    if (t > p.los) c = 'Passage terminé';
    else if (i.phase === 'EN_COURS') c = 'Image en cours' + (i.progression != null ? ' · ' + Math.round(i.progression * 100) + ' %' : '');
    else if (i.prochaine > t) c = 'Prochaine image dans ' + duree(i.prochaine - t);
    else c = 'En écoute';
    sstv.querySelector('[data-sstv-compte]').textContent = c;
    const age = p.image ? p.image.age + (Date.now() - recuA) : null;
    sstv.querySelector('[data-sstv-etat]').textContent = (t <= p.los ? 'LOS dans ' + duree(p.los - t) + ' (' + heure(p.los) + ')' : 'retour au direct QO-100 bientôt') +
      (age != null ? ' · image il y a ' + duree(age) : '');
  }

  function seconde() { rendBandeau(); ageDatv(); compteSstv(); }

  // ------------------------------------------------------------------ QO-100's spectrum and waterfall

  const plan = fig ? JSON.parse(fig.getAttribute('data-plan') || '{}') : {};
  const cTrace = fig && fig.querySelector('[data-trace]');
  const cChute = fig && fig.querySelector('[data-chute]');
  const cEchelle = fig && fig.querySelector('[data-echelle]');
  const legende = fig && fig.querySelector('[data-spectre-legende]');
  let axe = null, seq = 0, aDesLignes = false, minuterie = null;

  // Night blue → cyan → amber → white: the waterfall's colours, 256 of them.
  const PALETTE = (function () {
    const pts = [[0, 4, 8, 20], [70, 10, 40, 110], [130, 20, 170, 200], [190, 240, 200, 60], [235, 255, 110, 50], [255, 255, 245, 235]];
    const p = new Uint8ClampedArray(256 * 3);
    for (let i = 0; i < 256; i++) {
      let k = 0; while (k < pts.length - 2 && i > pts[k + 1][0]) k++;
      const a = pts[k], b = pts[k + 1], f = (i - a[0]) / Math.max(1, b[0] - a[0]);
      for (let c = 0; c < 3; c++) p[i * 3 + c] = a[c + 1] + (b[c + 1] - a[c + 1]) * Math.min(1, Math.max(0, f));
    }
    return p;
  })();

  function demarreSpectre() { if (!minuterie) { minuterie = setInterval(tireSpectre, 1000); tireSpectre(); } }
  function arreteSpectre() { if (minuterie) { clearInterval(minuterie); minuterie = null; } }

  async function tireSpectre() {
    if (!spectreVivant || document.hidden || !vu) { if (!spectreVivant) arreteSpectre(); return; }
    let j;
    try {
      const r = await fetch('/api/direct/spectre?depuis=' + seq, { cache: 'no-store' });
      if (!r.ok) return;
      j = await r.json();
    } catch (e) { return; }
    if (!j.ok || !j.lignes) return;
    const nouvelAxe = !axe || axe.centreHz !== j.centreHz || axe.largeurHz !== j.largeurHz || axe.n !== j.n;
    if (nouvelAxe || j.seq < seq) { axe = { centreHz: j.centreHz, largeurHz: j.largeurHz, n: j.n, minDb: j.minDb, maxDb: j.maxDb }; videChute(); dessineEchelle(); }
    axe.minDb = j.minDb; axe.maxDb = j.maxDb;
    seq = j.seq;
    const lignes = j.lignes.map(b => { const s = atob(b), a = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i); return a; });
    if (!lignes.length) return;
    lignes.forEach(ajouteChute);
    dessineTrace(lignes[lignes.length - 1]);
    aDesLignes = true; fig.hidden = false;
    if (legende) legende.textContent = '· ' + nf((axe.centreHz - axe.largeurHz / 2) / 1e6, 3) + ' – ' + nf((axe.centreHz + axe.largeurHz / 2) / 1e6, 3) + ' MHz' + (j.actif ? '' : ' · en pause');
  }

  function videChute() {
    if (!cChute) return;
    cChute.width = axe.n; cTrace.width = axe.n;
    const g = cChute.getContext('2d'); g.fillStyle = '#040814'; g.fillRect(0, 0, cChute.width, cChute.height);
  }

  function ajouteChute(l) {
    const g = cChute.getContext('2d'), w = cChute.width, h = cChute.height;
    g.drawImage(cChute, 0, 0, w, h - 1, 0, 1, w, h - 1);
    const im = g.createImageData(w, 1);
    for (let x = 0; x < w; x++) { const v = l[x] | 0, o = x * 4; im.data[o] = PALETTE[v * 3]; im.data[o + 1] = PALETTE[v * 3 + 1]; im.data[o + 2] = PALETTE[v * 3 + 2]; im.data[o + 3] = 255; }
    g.putImageData(im, 0, 0);
  }

  function dessineTrace(l) {
    const g = cTrace.getContext('2d'), w = cTrace.width, h = cTrace.height;
    g.fillStyle = '#040814'; g.fillRect(0, 0, w, h);
    g.strokeStyle = 'rgba(154,167,180,.14)'; g.lineWidth = 1;
    for (let k = 1; k < 4; k++) { g.beginPath(); g.moveTo(0, k * h / 4 + .5); g.lineTo(w, k * h / 4 + .5); g.stroke(); }
    const grad = g.createLinearGradient(0, 0, 0, h);
    grad.addColorStop(0, 'rgba(63,224,200,.55)'); grad.addColorStop(1, 'rgba(63,224,200,.04)');
    g.beginPath(); g.moveTo(0, h);
    for (let x = 0; x < w; x++) g.lineTo(x, h - 2 - (l[x] / 255) * (h - 6));
    g.lineTo(w, h); g.closePath(); g.fillStyle = grad; g.fill();
    g.beginPath();
    for (let x = 0; x < w; x++) { const y = h - 2 - (l[x] / 255) * (h - 6); if (x) g.lineTo(x, y); else g.moveTo(x, y); }
    g.strokeStyle = '#3FE0C8'; g.lineWidth = 1.2; g.stroke();
  }

  const COULEURS = { balise: '#FFB454', cw: '#6F8CFF', numerique: '#B48CFF', phonie: '#3FE0C8', diffusion: '#5BD68A', urgence: '#E5484D', mixte: '#8091A3' };

  function dessineEchelle() {
    if (!cEchelle || !axe) return;
    const r = window.devicePixelRatio || 1, W = cEchelle.clientWidth || 600, H = 46;
    cEchelle.width = Math.round(W * r); cEchelle.height = Math.round(H * r);
    const g = cEchelle.getContext('2d');
    g.setTransform(r, 0, 0, r, 0, 0);
    g.clearRect(0, 0, W, H);
    const bas = axe.centreHz - axe.largeurHz / 2, x = f => (f - bas) / axe.largeurHz * W;
    g.font = '600 10px "Noto Sans", system-ui, sans-serif'; g.textBaseline = 'middle';
    // The transponders, then the narrow band's segments.
    (plan.transpondeurs || []).forEach(t => {
      const a = Math.max(0, x(t.bas)), b = Math.min(W, x(t.haut));
      if (b - a < 2) return;
      g.fillStyle = 'rgba(111,140,255,.16)'; g.fillRect(a, 0, b - a, 12);
      if (b - a > g.measureText(t.nom).width + 8) { g.fillStyle = '#C9D6FF'; g.fillText(t.nom, a + 4, 6.5); }
    });
    (plan.segments || []).forEach(s => {
      const a = Math.max(0, x(s.bas)), b = Math.min(W, x(s.haut));
      if (b - a < 1) return;
      const c = COULEURS[s.usage] || '#8091A3';
      g.fillStyle = c; g.globalAlpha = .28; g.fillRect(a, 14, b - a, 13); g.globalAlpha = 1;
      g.fillStyle = c; g.fillRect(a, 26, b - a, 1);
      if (b - a > g.measureText(s.nom).width + 6) { g.fillStyle = '#E8F0F7'; g.fillText(s.nom, a + 3, 20.5); }
    });
    (plan.balises || []).forEach(f => {
      const p = x(f); if (p < 0 || p > W) return;
      g.fillStyle = '#FFB454'; g.beginPath(); g.moveTo(p - 4, 14); g.lineTo(p + 4, 14); g.lineTo(p, 20); g.closePath(); g.fill();
    });
    // The ticks: a step that leaves 90 px or more between labels.
    const pas = [1e3, 2e3, 5e3, 1e4, 2.5e4, 5e4, 1e5, 2.5e5, 5e5, 1e6, 2e6].find(p => p / axe.largeurHz * W >= 90) || 5e6;
    g.fillStyle = '#9AA7B4'; g.strokeStyle = 'rgba(154,167,180,.5)'; g.font = '10px "Noto Sans Mono", ui-monospace, monospace';
    for (let f = Math.ceil(bas / pas) * pas; f <= bas + axe.largeurHz; f += pas) {
      const p = Math.round(x(f)) + .5;
      g.beginPath(); g.moveTo(p, 30); g.lineTo(p, 34); g.stroke();
      const t = nf(f / 1e6, pas >= 1e5 ? 1 : 3), m = g.measureText(t).width;
      g.fillText(t, Math.min(W - m, Math.max(0, p - m / 2)), 40.5);
    }
  }

  if (fig && 'IntersectionObserver' in window) new IntersectionObserver(es => { vu = es.some(e => e.isIntersecting); }).observe(fig);
  window.addEventListener('resize', () => { if (axe) dessineEchelle(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) charge(); });

  charge();
  setInterval(charge, 10000);
  setInterval(seconde, 1000);
})();
