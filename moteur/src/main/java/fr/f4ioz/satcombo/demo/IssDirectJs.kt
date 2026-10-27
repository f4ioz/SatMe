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
 * « En direct de l'ISS »: a window floating over the Orbites tab with the
 * station's live video (NASA's public stream, embedded from YouTube's
 * no-cookie player) and, beside it, what the globe knows: where the ISS is
 * (over which sea, near which country), its height and speed, day or night
 * under it, why the picture is sometimes blue, grey or still. « Voir sur le
 * globe » puts the Passage view's « Vue du satellite » beside the real one.
 * Dragged, resized, folded, kept in this browser; docked at the bottom on a
 * phone; a corner tile on the dashboard (Vitrine) when ticked.
 *
 * The only part of the tab that needs the Internet: without it, a clear line
 * in the window and nothing else changes. Put into [PagePc.HTML]; no Kotlin
 * template in the script (its only dollars end regular expressions). Its pure
 * part ([PUR]) runs under node in IssDirectJsTest.
 */
object IssDirectJs {

    const val CSS = """
  /* ---------------------------------------------------------------- Orbites: the ISS live (IssDirectJs) */
  .iss-f { position:fixed; z-index:50; display:flex; flex-direction:column; min-width:240px; min-height:120px; box-sizing:border-box; overflow:hidden auto; overscroll-behavior:contain;
    background:rgba(10,16,22,.97); color:var(--texte, #E6EDF3); border:1px solid var(--ligne-forte, #2c3c4a); border-radius:12px; box-shadow:0 12px 32px rgba(0,0,0,.55); font-size:13px; }
  .iss-f[hidden] { display:none; }
  .iss-barre { display:flex; align-items:center; gap:4px; padding:4px 4px 4px 10px; background:rgba(255,255,255,.04); border-bottom:1px solid var(--ligne, #22303c); cursor:move; touch-action:none; user-select:none; flex:none; position:sticky; top:0; z-index:2; background-color:#121b24; }
  .iss-barre .iss-t { flex:1; min-width:0; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .iss-barre .iss-t i { font-style:normal; color:#FF5F5F; font-size:11px; margin-left:6px; letter-spacing:.06em; }
  .iss-barre button { border:0; background:transparent; color:var(--texte-2, #9AA7B4); width:28px; height:26px; border-radius:6px; font-size:14px; cursor:pointer; padding:0; }
  .iss-barre button:hover, .iss-barre button[aria-pressed="true"] { background:var(--s3, #24323f); color:var(--texte, #fff); }
  .iss-v { position:relative; flex:1 1 auto; min-height:130px; background:#000; }
  .iss-v iframe, .iss-v video { position:absolute; inset:0; width:100%; height:100%; border:0; background:#000; }
  .iss-hors { position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; padding:12px; text-align:center; color:#C9D4DE; background:#05080c; line-height:1.4; }
  .iss-hors[hidden] { display:none; }
  .iss-r { padding:8px 10px; display:flex; flex-direction:column; gap:6px; border-bottom:1px solid var(--ligne, #22303c); flex:none; }
  .iss-r[hidden] { display:none; }
  .iss-r input { width:100%; box-sizing:border-box; }
  .iss-r .iss-rb { display:flex; gap:6px; flex-wrap:wrap; }
  .iss-r .iss-err { color:var(--rose, #FF5FA2); }
  .iss-i { padding:6px 10px 2px; line-height:1.4; flex:none; }
  .iss-i b { color:var(--rx, #3FE0C8); }
  .iss-i .iss-ch { font-family:var(--mono, ui-monospace, monospace); font-size:12px; color:var(--texte-2, #9AA7B4); }
  .iss-i .iss-nuit { color:#B9A8FF; } .iss-i .iss-jour { color:#FFD34D; } .iss-i .iss-aube { color:#FFB454; }
  .iss-i .iss-ici { color:#7CF29A; }
  .iss-i .iss-perte { color:var(--texte-3, #6E7D8B); font-size:11.5px; }
  .iss-act { display:flex; gap:6px; flex-wrap:wrap; align-items:center; padding:4px 10px 0; flex:none; }
  .iss-act a { font-size:12px; }
  .iss-cr { padding:4px 26px 6px 10px; font-size:11px; color:var(--texte-3, #6E7D8B); flex:none; }
  .iss-poignee { position:absolute; right:0; bottom:0; width:22px; height:22px; cursor:nwse-resize; touch-action:none;
    background:linear-gradient(135deg, transparent 50%, rgba(154,167,180,.55) 50%, rgba(154,167,180,.55) 58%, transparent 58%, transparent 70%, rgba(154,167,180,.55) 70%, rgba(154,167,180,.55) 78%, transparent 78%); }
  .iss-f.iss-min { min-height:0; height:auto !important; }
  .iss-f.iss-min :is(.iss-v, .iss-r, .iss-i, .iss-act, .iss-cr, .iss-poignee) { display:none; }
  .iss-seg button[aria-pressed="true"] { color:#FF8A8A; }
  /* The dashboard's corner tile: no bar, the picture and two lines, readable from afar. */
  .iss-f.iss-tuile { left:auto !important; right:16px; top:16px !important; width:min(30vw, 520px) !important; height:auto !important; min-width:220px; border-radius:16px; border-width:2px; }
  .iss-f.iss-tuile :is(.iss-barre, .iss-r, .iss-act, .iss-poignee, .iss-ch, .iss-perte) { display:none; }
  .iss-f.iss-tuile .iss-v { flex:none; aspect-ratio:16 / 9; }
  .iss-f.iss-tuile .iss-i { font-size:clamp(13px, 1.1vw, 20px); }
  .iss-f.iss-tuile .iss-cr { padding-right:10px; }
  #v-orbites.ob-th-vitrine .iss-f.iss-tuile { top:auto !important; bottom:16px; right:16px; }
  #v-orbites.ob-th-vitrine.ob-avec-defile .iss-f.iss-tuile { bottom:calc(14px + 2.1 * clamp(14px, 1.25vw, 22px)); }
  @media (max-width:760px) {
    .iss-f:not(.iss-tuile) { left:6px !important; right:6px; top:auto !important; bottom:62px; width:auto !important; height:auto !important; max-height:calc(100vh - 120px); overflow:auto; }
    .iss-f:not(.iss-tuile) .iss-v { flex:none; aspect-ratio:16 / 9; min-height:0; }
    .iss-f .iss-poignee { display:none; }
    .iss-barre { cursor:default; }
    .iss-f .iss-perte { display:none; }
    .iss-f.iss-tuile { width:46vw !important; }
  }
"""

    /**
     * The pure part: a source pasted by the user made an embed (or a direct video), the sea under a point, the distance
     * between two. Top-level functions, so node can run them.
     */
    const val PUR = """
/** NASA's channel (its live of the moment) and the last ID known, should SatMe-PC not find today's (IssDirectPc). */
const ISS_CHAINE = 'UCLA_DiR1FfKNvjuUpBHmylQ';
const ISS_DERNIER = 'ROboUHfPUrU';
const ISS_EMBED = 'https://www.youtube-nocookie.com/embed/';
const ISS_PARAMS = 'autoplay=1&mute=1&playsinline=1&rel=0';
/**
 * A source as pasted: a YouTube video's ID, its watch / live / embed / shorts / youtu.be link, a channel's live
 * (…/channel/UC…, …embed/live_stream?channel=UC…), or a direct video (.mp4, .webm, .ogv, .m3u8).
 * → {type: 'youtube', id | channel, url (the embed), page (to open on YouTube)}, {type: 'video', url, hls}, null when not understood.
 */
function issSource(s) {
  s = String(s == null ? '' : s).trim();
  if (!s) return null;
  const ID = /^[A-Za-z0-9_-]{11}$/;
  const yt = id => ({ type: 'youtube', id: id, url: ISS_EMBED + id + '?' + ISS_PARAMS, page: 'https://www.youtube.com/watch?v=' + id });
  if (ID.test(s)) return yt(s);
  let u;
  try { u = new URL(/^[a-z][a-z0-9+.-]*:/i.test(s) ? s : 'https://' + s); } catch (x) { return null; }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  const h = u.hostname.toLowerCase().replace(/^(www|m|music)\./, ''), p = u.pathname.split('/').filter(Boolean);
  if (h === 'youtu.be') return p[0] && ID.test(p[0]) ? yt(p[0]) : null;
  if (h === 'youtube.com' || h === 'youtube-nocookie.com') {
    const v = u.searchParams.get('v');
    if (v && ID.test(v)) return yt(v);
    // A channel's live first (« live_stream » is eleven letters, as an ID).
    const ch = u.searchParams.get('channel') || (p[0] === 'channel' ? p[1] : '');
    if (ch) return /^UC[A-Za-z0-9_-]{22}$/.test(ch)
      ? { type: 'youtube', channel: ch, url: ISS_EMBED + 'live_stream?channel=' + ch + '&' + ISS_PARAMS, page: 'https://www.youtube.com/channel/' + ch + '/live' } : null;
    if (['live', 'embed', 'shorts', 'v'].includes(p[0]) && p[1] && ID.test(p[1])) return yt(p[1]);
    return null;
  }
  if (/\.(mp4|webm|ogv|m3u8)$/i.test(u.pathname)) return { type: 'video', url: u.href, hls: /\.m3u8$/i.test(u.pathname), page: u.href };
  return null;
}
/** The automatic NASA source's choices, in order: today's live [id] (when SatMe-PC found it), the channel's live, the last ID known. */
function issAutos(id) {
  const l = [];
  if (id && /^[A-Za-z0-9_-]{11}$/.test(id)) l.push(id);
  l.push('https://www.youtube.com/embed/live_stream?channel=' + ISS_CHAINE);
  if (id !== ISS_DERNIER) l.push(ISS_DERNIER);
  return l;
}
/** The sea under [lat], [lon] (a point known to be off land), roughly: boxes good enough for the ISS's band (±52°). */
function issMer(lat, lon) {
  lon = ((lon + 540) % 360) - 180;
  if (lat < -60) return 'l’océan Austral';
  if (lat > 66) return 'l’océan Arctique';
  if (lat > 30 && lat < 46.5 && lon > -5.6 && lon < 36.5 && !(lat > 43.3 && lon < 3)) return lat > 40.5 && lon > 27 ? 'la mer Noire' : 'la Méditerranée';
  if (lat > 40.5 && lat < 47.5 && lon > 27 && lon < 42) return 'la mer Noire';
  if (lat > 36 && lat < 47.5 && lon > 46 && lon < 55.5) return 'la mer Caspienne';
  if (lat > 12 && lat < 30 && lon > 32 && lon < 44) return 'la mer Rouge';
  if (lat > 23.5 && lat < 30.5 && lon > 47.5 && lon < 57) return 'le golfe Persique';
  if (lat > 47 && lat < 66 && lon > -5 && lon < 31) return lon > 9.5 ? 'la mer Baltique' : 'la mer du Nord';
  if (lat > 17.5 && lat < 31 && lon > -98 && lon < -80.5) return 'le golfe du Mexique';
  if (lat > 8 && lat < 22.5 && lon > -88 && lon < -59.5) return 'la mer des Caraïbes';
  if (lat > 0 && lat < 23 && lon > 99 && lon < 121) return 'la mer de Chine méridionale';
  if (lon >= 20 && lon < 100 && lat < 30) return 'l’océan Indien';
  if (lon >= 100 && lon < 147 && lat < -9) return 'l’océan Indien';
  if (lon > -70 && lon < 20) return 'l’océan Atlantique';
  if (lon <= -70 && lon > -100 && lat >= 8 && !(lat < 15 && lon < -84)) return 'l’océan Atlantique';
  return 'l’océan Pacifique';
}
/** The distance in km between two points (degrees), along the Earth's surface. */
function issKm(la1, lo1, la2, lo2) {
  const r = Math.PI / 180, a = Math.sin((la2 - la1) * r / 2) ** 2 + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.sin((lo2 - lo1) * r / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a)));
}
"""

    /** The window and its button in the Orbites bar, built by the script; the page calls [IssDirect.menu] from the dashboard's menu. */
    const val MODULE = """
const IssDirect = (function () {
  const CAT = 25544, CLE = 'iss-direct';
  const etat = { ouvert: false, min: false, x: null, y: null, w: 440, h: 470, source: '', tuiles: {} };
  try { Object.assign(etat, JSON.parse(localStorage.getItem(CLE)) || {}); } catch (x) {}
  const garde = () => { try { localStorage.setItem(CLE, JSON.stringify(etat)); } catch (x) {} };
  const v = document.getElementById('v-orbites');
  if (!v) return { menu: () => {} };
  let f = null, bouton = null, charge = '', net = null, sondeT = 0, infoT = 0, reglage = false;
  const q = s => f.querySelector(s);
  const petit = () => window.matchMedia('(max-width:760px)').matches;
  const tableau = () => v.classList.contains('ob-tableau');
  const theme = () => typeof obTh !== 'undefined' ? obTh : '';
  const tuile = () => tableau() && !!etat.tuiles[theme()];
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
  // The automatic source: asked from SatMe-PC (null: not yet, 'attente': asked), then its choices one after the other (↻).
  let auto = null, autoN = 0, autoT = 0;
  const autos = () => issAutos(auto && auto.id);
  const source = () => issSource(etat.source) || issSource(autos()[autoN % autos().length]);
  function demandeAuto() {
    auto = 'attente'; autoT = Date.now();
    fetch((window.SATME_RACINE || '') + 'api/iss-direct/source', { cache: 'no-store' }).then(r => r.json())
      .then(j => { auto = j && j.ok ? j : { id: null }; }).catch(() => { auto = { id: null }; }).finally(() => maj(true));
  }
  const pays = (() => { let d = null; try { d = new Intl.DisplayNames(['fr'], { type: 'region' }); } catch (x) {} return iso => { try { return d && iso ? d.of(iso) : iso; } catch (x) { return iso; } }; })();

  function construit() {
    const seg = document.createElement('div');
    seg.className = 'ob-seg ob-tb iss-seg';
    seg.innerHTML = '<button type="button" id="iss-b" aria-pressed="false" title="La vidéo en direct de la Station spatiale internationale (NASA, via YouTube ; Internet nécessaire), avec sa position calculée ici">📺 ISS en direct</button>';
    const haut = v.querySelector('.ob-haut'), tb = haut && haut.querySelector('.ob-seg.ob-tb');
    if (!haut) return false;
    if (tb) tb.after(seg); else haut.appendChild(seg);
    bouton = seg.querySelector('button');
    bouton.onclick = () => { etat.ouvert = !etat.ouvert; if (etat.ouvert) etat.min = false; garde(); maj(true); };
    f = document.createElement('div');
    f.className = 'iss-f'; f.id = 'iss-f'; f.hidden = true;
    f.setAttribute('role', 'dialog'); f.setAttribute('aria-label', 'En direct de l’ISS');
    f.innerHTML = '<div class="iss-barre"><span class="iss-t">📺 En direct de l’ISS<i>● DIRECT</i></span>' +
      '<button type="button" class="iss-bs" aria-pressed="false" title="La source de la vidéo (un autre lien YouTube, une vidéo directe)">⚙</button>' +
      '<button type="button" class="iss-bm" title="Réduire (la vidéo s’arrête)">▁</button>' +
      '<button type="button" class="iss-bx" title="Fermer">✕</button></div>' +
      '<div class="iss-r" hidden><label class="petit" for="iss-src">Source : un lien YouTube (watch, live, youtu.be, embed, ou le direct d’une chaîne …/channel/UC…/live), son identifiant, ou une vidéo directe (.mp4, .webm ; .m3u8 seulement dans Safari)</label>' +
      '<input type="url" id="iss-src" placeholder="https://www.youtube.com/watch?v=…" spellcheck="false">' +
      '<div class="iss-rb"><button type="button" class="primaire iss-ok">Appliquer</button><button type="button" class="iss-nasa">Rétablir la source NASA</button></div>' +
      '<div class="petit iss-sa"></div><div class="petit iss-rm">D’origine, la source NASA est automatique : SatMe-PC cherche le direct du jour (son identifiant change parfois), sinon le direct de la chaîne NASA.</div></div>' +
      '<div class="iss-v"><div class="iss-hors" hidden></div></div>' +
      '<div class="iss-i" aria-live="off"></div>' +
      '<div class="iss-act"><button type="button" class="iss-autre" title="Si la vidéo est indisponible : la source NASA suivante (le direct du jour trouvé par SatMe-PC, le direct de la chaîne NASA, le dernier identifiant connu)">↻ Autre source NASA</button><button type="button" class="iss-voir" title="Choisit l’ISS, ouvre la vue Passage avec la caméra « Vue du satellite » : la Terre calculée par SatMe à côté de la vraie">🌍 Voir sur le globe</button><a class="iss-yt" target="_blank" rel="noopener noreferrer">Ouvrir sur YouTube ↗</a></div>' +
      '<div class="iss-cr">Images : NASA (domaine public), via YouTube</div>' +
      '<div class="iss-poignee" title="Redimensionner"></div>';
    v.appendChild(f);
    q('.iss-bx').onclick = () => { etat.ouvert = false; garde(); maj(true); };
    q('.iss-bm').onclick = () => { etat.min = !etat.min; garde(); maj(true); };
    q('.iss-bs').onclick = () => { reglage = !reglage; q('.iss-r').hidden = !reglage; q('.iss-bs').setAttribute('aria-pressed', reglage);
      if (reglage) { q('#iss-src').value = etat.source || ''; q('#iss-src').focus(); } };
    const applique = s => {
      const r = issSource(s), m = q('.iss-rm');
      if (s && !r) { m.className = 'petit iss-rm iss-err'; m.textContent = 'Lien non reconnu : un lien YouTube (watch, live, youtu.be, embed, chaîne …/channel/UC…) ou une vidéo .mp4 / .webm / .m3u8.'; return; }
      etat.source = s; garde(); m.className = 'petit iss-rm'; m.textContent = s ? 'Source enregistrée dans ce navigateur.' : 'Source NASA automatique rétablie.';
      if (!s) { auto = null; autoN = 0; }
      net = null; maj(true);
    };
    q('.iss-ok').onclick = () => applique(q('#iss-src').value.trim());
    q('#iss-src').onkeydown = e => { if (e.key === 'Enter') applique(q('#iss-src').value.trim()); };
    q('.iss-nasa').onclick = () => { q('#iss-src').value = ''; applique(''); };
    q('.iss-voir').onclick = voir;
    q('.iss-autre').onclick = () => { autoN++; maj(true); };
    deplace(q('.iss-barre'), false); deplace(q('.iss-poignee'), true);
    window.addEventListener('resize', () => { if (f && !f.hidden) place(); });
    return true;
  }
  /** Dragged by its bar ([coin] false) or resized by its corner: mouse, pen or finger. */
  function deplace(prise, coin) {
    let d = null;
    prise.addEventListener('pointerdown', e => {
      if (e.button > 0 || e.target.closest('button') || petit() || tuile()) return;
      const r = f.getBoundingClientRect();
      d = { x: e.clientX, y: e.clientY, l: r.left, t: r.top, w: r.width, h: r.height };
      prise.setPointerCapture(e.pointerId); e.preventDefault();
    });
    prise.addEventListener('pointermove', e => {
      if (!d) return;
      const dx = e.clientX - d.x, dy = e.clientY - d.y;
      if (coin) { etat.w = d.w + dx; etat.h = d.h + dy; } else { etat.x = d.l + dx; etat.y = d.t + dy; }
      place();
    });
    const fin = () => { if (d) { d = null; garde(); } };
    prise.addEventListener('pointerup', fin); prise.addEventListener('pointercancel', fin);
  }
  /** Its place and size, kept inside the window of the browser (the phone's and the tile's: the style sheet's). */
  function place() {
    if (petit()) return;
    const W = innerWidth, H = innerHeight;
    etat.w = Math.round(Math.max(240, Math.min(W - 16, etat.w || 440)));
    etat.h = Math.round(Math.max(200, Math.min(H - 16, etat.h || 470)));
    if (etat.x == null) etat.x = W - etat.w - 24;
    if (etat.y == null) etat.y = Math.min(H - etat.h - 8, 120);
    etat.x = Math.round(Math.max(4, Math.min(W - 80, etat.x)));
    etat.y = Math.round(Math.max(4, Math.min(H - 40, etat.y)));
    f.style.left = etat.x + 'px'; f.style.top = etat.y + 'px'; f.style.width = etat.w + 'px'; f.style.height = etat.h + 'px';
  }
  /** Is the Internet there (YouTube's player reachable)? Asked when the video is to show, not more than once a minute when it is not. */
  function sonde() {
    if (navigator.onLine === false) { net = false; return; }
    if (net === 'attente' || Date.now() - sondeT < (net === false ? 60000 : 600000)) return;
    sondeT = Date.now(); net = net === true ? true : 'attente';
    const ac = typeof AbortController !== 'undefined' ? new AbortController() : null, t = setTimeout(() => ac && ac.abort(), 8000);
    fetch('https://www.youtube-nocookie.com/favicon.ico', { mode: 'no-cors', cache: 'no-store', signal: ac ? ac.signal : undefined })
      .then(() => { net = true; }).catch(() => { net = false; }).finally(() => { clearTimeout(t); maj(true); });
  }
  function hors(texte, reessai) {
    const h = q('.iss-hors');
    h.hidden = !texte; q('.iss-t i').hidden = !!texte; if (!texte) return;
    h.innerHTML = '<div>' + texte + '</div>' + (reessai ? '<button type="button">Réessayer</button>' : '');
    const b = h.querySelector('button'); if (b) b.onclick = () => { net = null; sondeT = 0; maj(true); };
  }
  /** The video put in (or taken out: hidden, folded, another tab), as the source and the Internet allow. */
  function video(voulu) {
    const z = q('.iss-v'), s = source(), n = autoN % autos().length;
    q('.iss-yt').href = s.page; q('.iss-autre').hidden = !!etat.source;
    q('.iss-sa').textContent = etat.source ? 'Source : la tienne (' + etat.source + ').' : 'Source : NASA (automatique) — ' +
      (s.id && auto && auto.id === s.id ? 'le direct du jour' + (auto.titre ? ' (' + auto.titre + ')' : '') : s.channel ? 'le direct de la chaîne NASA' : 'le dernier identifiant connu') + '.';
    if (!voulu) { if (charge) { z.querySelectorAll('iframe, video').forEach(e => e.remove()); charge = ''; } return; }
    sonde();
    if (net === false) {
      if (charge) { z.querySelectorAll('iframe, video').forEach(e => e.remove()); charge = ''; }
      hors('Pas d’accès à Internet (ou YouTube est bloqué sur ce réseau) : la vidéo de l’ISS ne peut pas s’afficher.<br>Tout le reste de l’onglet Orbites marche sans Internet.', true);
      return;
    }
    if (net === 'attente' && !charge) { hors('Connexion à la vidéo…', false); return; }
    // The automatic source: today's live asked once the window shows, again each hour.
    if (!etat.source && (auto === null || (auto !== 'attente' && Date.now() - autoT > 3600000))) demandeAuto();
    if (!etat.source && auto === 'attente') { if (!charge) hors('Recherche du direct NASA du jour…', false); return; }
    if (charge === s.url) return;
    z.querySelectorAll('iframe, video').forEach(e => e.remove()); hors('', false);
    let e;
    if (s.type === 'youtube') {
      e = document.createElement('iframe');
      e.src = s.url; e.title = 'Vidéo en direct de l’ISS';
      e.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
      e.allowFullscreen = true; e.referrerPolicy = 'strict-origin-when-cross-origin';
    } else {
      e = document.createElement('video');
      e.src = s.url; e.muted = true; e.autoplay = true; e.playsInline = true; e.controls = true;
      e.onerror = () => hors('Cette vidéo ne se lit pas ici' + (s.hls ? ' (un flux .m3u8 ne se lit directement que dans Safari)' : '') + '. ⚙ pour changer de source.', false);
    }
    z.insertBefore(e, z.firstChild); charge = s.url;
  }
  /** What the globe knows about the ISS now. */
  function infos() {
    const box = q('.iss-i'), o = typeof Orbites !== 'undefined' && Orbites.sousSat ? Orbites.sousSat(CAT) : null;
    if (!o) { box.innerHTML = '<div class="petit">Position de l’ISS : en attente de ses éléments orbitaux (n° ' + CAT + ' dans la liste de la station)…</div>'; return; }
    let ou;
    if (o.terre) {
      const c = ((typeof GLOBE_MONDE !== 'undefined' && GLOBE_MONDE.capitales) || []).reduce((m, c) => { const k = issKm(o.lat, o.lon, c[1], c[2]); return !m || k < m.k ? { c: c, k: k } : m; }, null);
      ou = 'Au-dessus des terres' + (c ? ', vers <b>' + esc(c.c[0]) + '</b>' + (c.c[3] ? ' (' + esc(pays(c.c[3])) + ')' : '') + ' <span class="petit">≈ ' + Math.round(c.k / 10) * 10 + ' km</span>' : '');
    } else ou = 'Au-dessus de <b>' + issMer(o.lat, o.lon) + '</b>';
    const fr = (x, n) => x.toLocaleString('fr-FR', { maximumFractionDigits: n, minimumFractionDigits: n });
    const ch = fr(Math.abs(o.lat), 1) + '° ' + (o.lat >= 0 ? 'N' : 'S') + ' · ' + fr(Math.abs(o.lon), 1) + '° ' + (o.lon >= 0 ? 'E' : 'O') + ' · ' + fr(o.alt, 0) + ' km d’altitude' +
      (o.vitesse ? ' · ' + fr(o.vitesse * 3600, 0) + ' km/h' : '');
    const jn = o.soleil > 0 ? '<div class="iss-jour">☀ Jour sous l’ISS : nuages, mers et terres éclairés.</div>'
      : o.soleil > -12 ? '<div class="iss-aube">🌅 Aube ou crépuscule sous l’ISS : lumière rasante, le sol s’assombrit.</div>'
      : '<div class="iss-nuit">🌙 Nuit sous l’ISS : image noire, c’est normal (parfois des villes éclairées ou des éclairs).</div>';
    const ici = o.vu && o.el > 0 ? '<div class="iss-ici">📡 Au-dessus de ton horizon : az ' + Math.round(o.az) + '°, él ' + Math.round(o.el) + '° — elle passe en ce moment !</div>' : '';
    box.innerHTML = '<div>🛰 ' + ou + '</div><div class="iss-ch">' + ch + '</div>' + jn + ici +
      '<div class="iss-perte">Écran bleu ou gris, image figée ou mire : l’ISS est entre deux satellites relais ou la caméra a été changée (perte de signal côté NASA), pas une panne de SatMe.</div>';
  }
  /** « Voir sur le globe »: the ISS chosen, the Passage view from the satellite; the window to the right, beside it. */
  function voir() {
    if (typeof Orbites === 'undefined' || !Orbites.choisir || !Orbites.choisir(CAT)) {
      q('.iss-i').insertAdjacentHTML('afterbegin', '<div class="iss-err">L’ISS (n° ' + CAT + ') n’est pas dans les éléments orbitaux de la station.</div>'); return;
    }
    const b = v.querySelector('[data-ob-vue="passage"]'); if (b) b.click();
    Orbites.camera('satellite');
    if (!petit() && !tuile()) { etat.x = innerWidth - etat.w - 16; garde(); place(); }
  }
  /** Each second: shown or not, the video in or out, the figures (every two seconds). */
  function maj(force) {
    if (!f && !construit()) return;
    const t = tuile(), montre = !v.hidden && (etat.ouvert || t);
    bouton.setAttribute('aria-pressed', !!etat.ouvert);
    f.hidden = !montre;
    f.classList.toggle('iss-tuile', t); f.classList.toggle('iss-min', !t && !!etat.min);
    q('.iss-bm').textContent = etat.min ? '▢' : '▁'; q('.iss-bm').title = etat.min ? 'Agrandir' : 'Réduire (la vidéo s’arrête)';
    if (montre && !t) place();
    video(montre && (t || !etat.min));
    if (montre && (force || Date.now() - infoT > 1900)) { infoT = Date.now(); infos(); }
  }
  setInterval(() => maj(false), 1000);
  window.addEventListener('online', () => { net = null; sondeT = 0; maj(true); });
  window.addEventListener('offline', () => { net = false; maj(true); });
  return {
    /** The dashboard's menu: the corner tile, for the look shown. */
    menu: (titre, coche) => {
      titre('ISS en direct');
      coche('Vidéo de l’ISS en tuile dans un coin (Internet)', !!etat.tuiles[theme()], on => { etat.tuiles[theme()] = on; garde(); maj(true); });
    },
    /** Opened or closed (tests, a link). */
    ouvre: on => { etat.ouvert = on !== false; etat.min = false; garde(); maj(true); }
  };
})();
"""

    /** The page's script: the pure part, then the window. */
    val JS: String get() = PUR + MODULE
}
