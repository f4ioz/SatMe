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
 * The journal's timeline on the page (no DOM): the video's frames as SatMe-PC
 * makes them (« /chronologie », SessionRenduPc.Chronologie), played by the
 * preview. The preview and the video share it: the same paces, margins,
 * accelerated replay and « Vitesse », the same length, so they cannot differ.
 * The same text runs in the page and under node in ChronologieJsTest.
 *
 * chDecode(c): the answer made ready (c.tt: each frame's real time).
 * chEtat(c, s): what the preview shows [s] seconds into the passes' part (the cards left out):
 *   {t: real time, i: the pass, kind: « p » in a pass, « e » between two, « t » turning to pass i,
 *    trans: ms of the turn still to go, allure: real seconds per second of video, fin: the end reached}.
 * chVideo(c, t): the seconds into the passes' part where the real time [t] is first shown (its inverse).
 * chApercu(q): the words of a file asked (« /fabrique-session ») as the preview asks its timeline: the
 *   same, but the moment shown and the stretch chosen (the preview plays everything).
 * chCle(q): those words as one text (a timeline asked again only when it changed).
 * chDuree(s): « 1 min 42 s » (« 42 s » under a minute; the same in English).
 * chReel(allure): a pace that runs in real time (its sound heard), the video's rule.
 * chSon(c, allure): the sound heard at that pace: in real time, or sped up to c.sonMax (×4) with « Son accéléré ».
 * chAudio(a, allure): the audio element played at that pace, its pitch kept (preservesPitch and its old names).
 */
object ChronologieJs {

    const val CODE = """
function chDecode(c) {
  const n = (c.dt || []).length + 1, tt = new Array(n);
  tt[0] = c.t0;
  for (let k = 1; k < n; k++) tt[k] = tt[k - 1] + c.dt[k - 1];
  c.tt = (c.k || '').length ? tt : [];
  return c;
}
function chEtat(c, s) {
  const tt = c.tt, n = tt.length;
  if (!n) return { t: 0, i: 0, kind: 'p', trans: 0, allure: 1, fin: true };
  const f = Math.max(0, s) * c.fps, k = Math.floor(f);
  const pas = j => (tt[j + 1] - tt[j]) * c.fps / 1000;
  if (k >= n - 1) {
    const d = n - 1;
    return { t: tt[d], i: c.i[d], kind: c.k[d], trans: 0, allure: d > 0 && c.k[d] !== 't' && c.k[d - 1] !== 't' ? pas(d - 1) : 1, fin: true };
  }
  const ka = c.k[k], kb = c.k[k + 1], a = tt[k], b = tt[k + 1];
  if (ka === 't') {
    let e = k; while (e < n && c.k[e] === 't') e++;
    return { t: a, i: c.i[k], kind: 't', trans: (e - f) / c.fps * 1000, allure: 0, fin: false };
  }
  // The last frame of a pass before a turn: held, at the pace it came with.
  if (kb === 't' || b < a) return { t: a, i: c.i[k], kind: ka, trans: 0, allure: k > 0 && c.k[k - 1] !== 't' ? pas(k - 1) : 1, fin: false };
  return { t: a + (b - a) * (f - k), i: c.i[k], kind: ka, trans: 0, allure: pas(k), fin: false };
}
function chVideo(c, t) {
  const tt = c.tt, n = tt.length;
  if (!n || t <= tt[0]) return 0;
  for (let k = 1; k < n; k++) {
    if (tt[k] < t) continue;
    const a = tt[k - 1], b = tt[k], lisse = b > a && c.k[k - 1] !== 't' && c.k[k] !== 't';
    return (k - 1 + (lisse ? (t - a) / (b - a) : 1)) / c.fps;
  }
  return (n - 1) / c.fps;
}
function chApercu(q) {
  const r = Object.assign({}, q);
  delete r.t; delete r.de; delete r.a; delete r.quoi;
  r.quoi = 'video';
  return r;
}
function chCle(q) { return Object.keys(q).sort().map(k => k + '=' + q[k]).join('&'); }
function chDuree(s) {
  const r = Math.round(Math.max(0, s));
  return r < 60 ? r + ' s' : Math.floor(r / 60) + ' min ' + String(r % 60).padStart(2, '0') + ' s';
}
function chReel(allure) { return Math.abs(allure - 1) < 1e-3; }
function chSon(c, allure) { return chReel(allure) || (!!c && !!c.sonAccelere && allure > 0 && allure <= (c.sonMax || 4) + 1e-3); }
function chAudio(a, allure) {
  const r = chReel(allure) ? 1 : Math.max(.25, Math.min(4, allure));
  a.preservesPitch = true; a.mozPreservesPitch = true; a.webkitPreservesPitch = true;
  if (Math.abs((a.playbackRate || 1) - r) > 1e-3) a.playbackRate = r;
  return r;
}
"""
}
