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
 * The page's low-latency player, its pure part: the jitter buffer of the
 * « Mode distant » sound (no DOM, no audio API). The same text runs in the
 * page (AudioWorklet, or ScriptProcessor on a page that is not « sûre »)
 * and under node in SonDistantJsTest.
 */
object SonDistantJs {

    /**
     * class TamponSon(rate, sr, options): frames of 20 ms placed by their
     * number; read at the card's rate [sr], linearly interpolated, the pace
     * nudged (±0.5 %) to hold the target depth; the target is the network's
     * jitter (95th percentile over the last 5 s) plus 20 ms, within 60–120 ms.
     * A missing frame: the last one repeated, fading (three at most, then
     * silence until the target is back). Too deep (a stall, then a burst):
     * back to the target at once.
     */
    const val TAMPON = """
class TamponSon {
  constructor(rate, sr, o) {
    o = o || {};
    this.rate = rate; this.sr = sr; this.N = Math.round(rate * 0.02);
    this.min = o.min != null ? o.min : 60; this.max = o.max != null ? o.max : 120; this.marge = o.marge != null ? o.marge : 20;
    this.T = 1 << 18; this.m = this.T - 1; this.a = new Float32Array(this.T);
    this.F = 1024; this.slot = new Float64Array(this.F).fill(-1); this.masque = new Uint8Array(this.F);
    this.W = 250; this.tr = new Float64Array(this.W); this.nTr = 0;
    this.cible = this.min; this.gigue = 0;
    this.st = { recus: 0, masques: 0, tardifs: 0, vides: 0, sautes: 0 };
    this.raz();
  }
  raz() {
    this.k0 = null; this.lu = 0; this.kmax = -1; this.amorce = true; this.moy = 0; this.suite = 0; this.derive = 0; this.integ = 0;
    this.slot.fill(-1); this.nTr = 0;
  }
  // The buffer's depth, in ms: what is held ahead of the reader (gaps counted, they will be filled).
  niveau() { return this.k0 === null ? 0 : Math.max(0, ((this.kmax + 1) * this.N - this.lu) * 1000 / this.rate); }
  // A frame: [seq] its number, [ms] the server's clock, [t] this side's on arrival (ms), [x] its samples (-1..1).
  recoit(seq, ms, t, x) {
    if (this.k0 === null || seq < this.k0 || seq - this.k0 > this.kmax + this.F / 2) { this.raz(); this.k0 = seq; }
    this.st.recus++;
    this.mesure(t - ms);
    const k = seq - this.k0, s = k % this.F;
    if (this.slot[s] === k || (k + 1) * this.N <= this.lu) { this.st.tardifs++; return; }
    const p = k * this.N;
    for (let i = 0; i < this.N; i++) this.a[(p + i) & this.m] = i < x.length ? x[i] : 0;
    this.slot[s] = k; this.masque[s] = 0;
    if (k > this.kmax) this.kmax = k;
  }
  // The transit's spread over the last W frames (the clocks' offset cancels out): the target follows it.
  mesure(d) {
    this.tr[this.nTr % this.W] = d; this.nTr++;
    if (this.nTr % 25 !== 0 && this.nTr > 2) return;
    const n = Math.min(this.nTr, this.W), v = Array.from(this.tr.subarray(0, n)).sort((a, b) => a - b);
    this.gigue = v[Math.min(n - 1, Math.floor(n * 0.95))] - v[0];
    const c = Math.max(this.min, Math.min(this.max, this.gigue + this.marge));
    // Up at once, down slowly.
    this.cible = c > this.cible ? c : this.cible + (c - this.cible) * 0.2;
  }
  // Frame k ready to be read: received, or made up (the last one repeated, fading) when allowed.
  pret(k) {
    const s = k % this.F;
    if (this.slot[s] === k) { if (!this.masque[s]) this.suite = 0; return true; }
    // Nothing after it yet: the network late, three frames made up at most before waiting again.
    if (k > this.kmax && this.suite >= 3) return false;
    this.suite++;
    const g0 = Math.pow(0.6, this.suite - 1), g1 = this.suite >= 3 ? 0 : Math.pow(0.6, this.suite);
    const p = k * this.N, q = p - this.N;
    for (let i = 0; i < this.N; i++) this.a[(p + i) & this.m] = this.a[(q + i) & this.m] * (g0 + (g1 - g0) * i / this.N);
    this.slot[s] = k; this.masque[s] = 1; this.st.masques++;
    if (k > this.kmax) this.kmax = k;
    return true;
  }
  // Fills out[0..n) at the card's rate.
  lit(out, n) {
    if (this.k0 === null) { out.fill(0, 0, n); return; }
    let niv = this.niveau();
    const q = this.cible * this.rate / 1000;
    if (this.amorce) {
      if (niv < this.cible) { out.fill(0, 0, n); return; }
      // Start at the target's depth, not deeper.
      this.lu = Math.max(this.lu, (this.kmax + 1) * this.N - q);
      this.amorce = false; niv = this.niveau(); this.moy = niv;
    }
    if (niv > this.cible * 2 + 60) { this.lu = (this.kmax + 1) * this.N - q; this.st.sautes++; niv = this.niveau(); this.moy = niv; }
    const a = Math.min(1, n / this.sr / 0.5);
    this.moy += (niv - this.moy) * a;
    // Proportional and integral: a card a little faster or slower than the server's clock is followed without a standing offset.
    const e = (this.moy - this.cible) / this.cible;
    this.integ = Math.max(-0.005, Math.min(0.005, this.integ + e * (n / this.sr) * 0.002));
    this.derive = Math.max(-0.005, Math.min(0.005, e * 0.01 + this.integ));
    const pas = this.rate / this.sr * (1 + this.derive);
    for (let i = 0; i < n; i++) {
      const i0 = Math.floor(this.lu);
      if (!this.pret(Math.floor(i0 / this.N)) || !this.pret(Math.floor((i0 + 1) / this.N))) {
        out.fill(0, i, n); this.amorce = true; this.st.vides++; return;
      }
      const x = this.lu - i0, u = this.a[i0 & this.m], w = this.a[(i0 + 1) & this.m];
      out[i] = u + (w - u) * x;
      this.lu += pas;
    }
  }
  etat() {
    return { niveau: this.moy, cible: this.cible, gigue: this.gigue, derive: this.derive, amorce: this.amorce,
      recus: this.st.recus, masques: this.st.masques, tardifs: this.st.tardifs, vides: this.st.vides, sautes: this.st.sautes };
  }
}
"""
}
