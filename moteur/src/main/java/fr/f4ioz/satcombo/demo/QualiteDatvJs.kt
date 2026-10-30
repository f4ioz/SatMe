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
 * « Qualité » of the DATV picture in SatMe-PC's DATV tab, chosen per browser (kept in its localStorage): Source,
 * 720p, 480p, 360p or Auto. A tablet that cannot keep up stays on 480p while the station PC keeps Source; SatMe-PC
 * re-encodes the smaller levels (ImagesDatvPc for the JPEG stream, LecteurFluideDatvPc for « Lecture fluide »).
 *
 * [CODE], pure (no page, tested by node in QualiteDatvJsTest):
 * - qdvAuto(a, m, t): Auto's choice from a window's measures (2 s): pictures dropped (the video's own count, or the
 *   JPEG pictures SatMe-PC had to skip for this browser), stalls, jumps back to the live edge, a growing lag, the
 *   page's frames late. Two bad windows in a row: one level down. Calm for a while (45 s, longer each time going
 *   up failed soon after): one level up. Nothing judged in the 5 s after a change (the stream starting again).
 * - qdvDelta(p, c): a window from two readings of the counters; qdvTexteMesure: « 1 850 kbit/s · 3 images perdues ».
 *
 * [PAGE], the page's side: the « Qualité » line of the frame (its choice, Auto's decision, the measures), the
 * level each player asks (dQualVoulu), the measures every 2 s.
 * Plain constants: no Kotlin template in here, and the JS uses no template literals.
 */
object QualiteDatvJs {

    const val CODE = """
const QDV_NIVEAUX = ['source', '720', '480', '360'];
const QDV_CHOIX = ['source', '720', '480', '360', 'auto'];
/** Nothing judged this long after a change; bad windows in a row to go down; calm before going up (doubled on a relapse, up to the max). */
const QDV_REPOS = 5000, QDV_SUITE = 2, QDV_CALME = 45000, QDV_CALME_MAX = 480000, QDV_RECHUTE = 90000;
function qdvNom(n) { return n === 'source' ? 'Source' : n === 'auto' ? 'Auto' : n + 'p'; }
function qdvChoix(x) { return QDV_CHOIX.indexOf(x) >= 0 ? x : 'source'; }
/** Auto's state, starting at [depart] (the level it ended on last time in this browser). */
function qdvAutoNeuf(t, depart) {
  return { niveau: QDV_NIVEAUX.indexOf(depart) >= 0 ? depart : 'source', changeMs: t, dernierMauvaisMs: t, monteeMs: 0, suite: 0, calmeMs: QDV_CALME };
}
/** The level a player asks: the one chosen, or Auto's. */
function qdvVoulu(choix, a) { return choix === 'auto' ? a.niveau : choix; }
/**
 * A window in trouble: more than 8 % of the pictures dropped (3 at least), a stall, a jump back to the live edge,
 * the lag past 1.2 s and growing, or the page's frames late three times or more.
 */
function qdvMauvais(m) {
  if (!m) return false;
  if (m.images > 0 && m.perdues >= 3 && m.perdues / m.images > 0.08) return true;
  if (m.attentes > 0 || m.sauts > 0) return true;
  if (m.retard != null && m.retardAvant != null && m.retard > 1.2 && m.retard > m.retardAvant + 0.3) return true;
  return (m.longues || 0) >= 3;
}
/** Auto's level after a window [m] at [t] (a changed in place). */
function qdvAuto(a, m, t) {
  if (t - a.changeMs < QDV_REPOS) return a.niveau;
  const i = QDV_NIVEAUX.indexOf(a.niveau);
  if (qdvMauvais(m)) {
    a.dernierMauvaisMs = t; a.suite++;
    if (a.suite >= QDV_SUITE && i < QDV_NIVEAUX.length - 1) {
      // Fallen soon after going up: wait longer before trying again.
      if (a.monteeMs && t - a.monteeMs < QDV_RECHUTE) a.calmeMs = Math.min(a.calmeMs * 2, QDV_CALME_MAX);
      a.niveau = QDV_NIVEAUX[i + 1]; a.changeMs = t; a.suite = 0; a.monteeMs = 0;
    }
    return a.niveau;
  }
  a.suite = 0;
  if (i > 0 && t - a.dernierMauvaisMs >= a.calmeMs && t - a.changeMs >= a.calmeMs) {
    // The last rise held: back towards the usual calm.
    if (a.monteeMs) a.calmeMs = Math.max(QDV_CALME, a.calmeMs / 2);
    a.niveau = QDV_NIVEAUX[i - 1]; a.changeMs = t; a.monteeMs = t;
  }
  return a.niveau;
}
/**
 * A window from two readings of a stream's counters ({ flux, t, octets, images, perdues, attentes, sauts }): the
 * kbit/s and what happened meanwhile; another stream ([flux] changed, counters gone back): from zero, at its
 * start [debut] (no kbit/s without it).
 */
function qdvDelta(p, c) {
  const z = !p || p.flux !== c.flux || c.octets < p.octets || c.images < p.images;
  const b = z ? { t: c.debut != null ? c.debut : c.t, octets: 0, images: 0, perdues: 0, attentes: 0, sauts: 0 } : p;
  const ms = c.t - b.t;
  return { kbps: ms > 0 ? Math.round((c.octets - b.octets) * 8 / ms) : null, images: c.images - b.images, perdues: Math.max(0, c.perdues - b.perdues),
    attentes: Math.max(0, (c.attentes || 0) - (b.attentes || 0)), sauts: Math.max(0, (c.sauts || 0) - (b.sauts || 0)) };
}
function qdvMilliers(n) { return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' '); }
/** The measures for the frame's line. */
function qdvTexteMesure(kbps, perdues) {
  if (kbps == null) return '';
  return qdvMilliers(kbps) + ' kbit/s · ' + (perdues ? qdvMilliers(perdues) + (perdues > 1 ? ' images perdues' : ' image perdue') : 'aucune image perdue');
}
/** The choice as shown: « Auto · 480p » (Auto's decision), or « 480p ». */
function qdvTexte(choix, a) {
  if (choix === 'auto') return 'Auto · ' + qdvNom(a.niveau);
  return qdvNom(choix);
}
"""

    const val PAGE = """
const QDV = { choix: 'source', auto: null, servi: '', repli: '', prec: null, kbps: null, perdues: 0, longues: 0, rafMs: 0,
  imgFlux: 0, imgQ: '', imgFin: false, imgMs: 0, mode: '' };
(function () {
  let depart = null;
  try { QDV.choix = qdvChoix(localStorage.getItem('satme.datvQualite')); depart = localStorage.getItem('satme.datvQualiteAuto'); } catch (x) {}
  QDV.auto = qdvAutoNeuf(Date.now(), depart);
})();
/** The level the players ask now. */
function dQualVoulu() { return qdvVoulu(QDV.choix, QDV.auto); }
function dQualite(x) {
  QDV.choix = qdvChoix(x);
  try { localStorage.setItem('satme.datvQualite', QDV.choix); } catch (y) {}
  QDV.auto.changeMs = Date.now(); QDV.auto.suite = 0;
  dQualAffiche();
  ticDatv();
}
/** The JPEG stream's address at the level asked (its own id, for its measures). */
function dQualMjpeg() {
  QDV.imgQ = dQualVoulu(); QDV.imgFlux++; QDV.imgFin = false; QDV.imgMs = Date.now(); QDV.prec = null;
  return 'api/datv/video.mjpeg?q=' + QDV.imgQ + '&id=' + DFL.id + '&t=' + Date.now();
}
/** The JPEG stream to ask again: another level wanted, or it ended (the level's encoder stopped). */
function dQualRelance() { return QDV.imgQ !== dQualVoulu() || QDV.imgFin; }
function dQualAffiche() {
  const s = g('d-qualite'); if (!s) return;
  if (document.activeElement !== s && s.value !== QDV.choix) s.value = QDV.choix;
  const o = s.querySelector('option[value="auto"]'), ta = QDV.choix === 'auto' ? qdvTexte('auto', QDV.auto) : 'Auto';
  if (o && o.textContent !== ta) o.textContent = ta;
  const servi = QDV.servi && QDV.servi !== dQualVoulu() ? 'servi en ' + qdvNom(QDV.servi) + (QDV.repli ? ' (' + QDV.repli + ')' : '') : '';
  const t = [QDV.mode ? qdvTexteMesure(QDV.kbps, QDV.perdues) : '', servi].filter(x => x).join(' · ');
  txt('d-qual-etat', t);
}
// The page's frames late (more than 150 ms between two): the browser short of time.
function dQualRaf(t) {
  if (QDV.rafMs && t - QDV.rafMs > 150 && t - QDV.rafMs < 2000) QDV.longues++;
  QDV.rafMs = t;
  if (QDV.mode) requestAnimationFrame(dQualRaf); else QDV.rafMs = 0;
}
/** Every 2 s while the picture plays: the counters read, the line written, Auto's choice. */
async function dQualMesure() {
  const l = g('d-qual'); if (!l) return;
  l.hidden = !!window.SATME_TELEPHONE;
  dQualAffiche();
  if (window.SATME_TELEPHONE || g('v-datv').hidden || document.hidden) { QDV.mode = ''; return; }
  const vid = g('d-video-f'), img = g('d-video'), t = Date.now();
  let c = null, retard = null;
  if (DFL.ctrl && DFL.joue && vid) {
    const pq = vid.getVideoPlaybackQuality ? vid.getVideoPlaybackQuality() : null;
    c = { flux: 'f' + DFL.flux, t: t, debut: DFL.debutMs, octets: DFL.octets, images: pq ? pq.totalVideoFrames : 0, perdues: pq ? pq.droppedVideoFrames : 0,
      attentes: DFL.attentes, sauts: DFL.sauts };
    retard = DFL.retard; QDV.servi = DFL.niveau; QDV.repli = DFL.repli;
    if (QDV.mode !== 'fluide') QDV.prec = null;
    QDV.mode = 'fluide';
  } else if (img && img.style.display === 'block' && !img.dataset.vign && QDV.imgQ) {
    let j = null;
    try { j = await (await fetch('api/datv/video/mesure?id=' + DFL.id, { cache: 'no-store' })).json(); } catch (x) {}
    // Just asked again: the measures may still be the stream before.
    if (!j || !j.ok || t - QDV.imgMs < 3000) return;
    if (j.fini && !QDV.imgFin) { QDV.imgFin = true; ticDatv(); }
    c = { flux: 'j' + QDV.imgFlux, t: t, debut: t - j.ms, octets: j.octets, images: j.images + j.sautees, perdues: j.sautees, attentes: 0, sauts: 0 };
    QDV.servi = j.niveau; QDV.repli = j.repli;
    if (QDV.mode !== 'jpeg') QDV.prec = null;
    QDV.mode = 'jpeg';
  } else { QDV.mode = ''; QDV.kbps = null; dQualAffiche(); return; }
  if (!QDV.rafMs) requestAnimationFrame(dQualRaf);
  const m = qdvDelta(QDV.prec, c);
  m.longues = QDV.longues; QDV.longues = 0;
  m.retard = retard; m.retardAvant = QDV.prec ? QDV.prec.retard : null;
  c.retard = retard;
  QDV.prec = c; QDV.kbps = m.kbps; QDV.perdues = c.perdues;
  if (QDV.choix === 'auto') {
    const avant = QDV.auto.niveau, n = qdvAuto(QDV.auto, m, t);
    if (n !== avant) { try { localStorage.setItem('satme.datvQualiteAuto', n); } catch (x) {} ticDatv(); }
  }
  dQualAffiche();
}
setInterval(dQualMesure, 2000);
"""
}
