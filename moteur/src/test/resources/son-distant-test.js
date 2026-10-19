// The « Mode distant » player's jitter buffer, run under node (SonDistantJsTest):
// a server making a 20 ms frame every 20 ms, a network that delays (in order, as TCP does),
// loses or stalls, a sound card slightly fast — and what comes out checked.
// By hand: node son-distant-test.js tampon.js (tampon.js: SonDistantJs.TAMPON + "module.exports = TamponSon;").
'use strict';
const TamponSon = require(process.argv[2]);

function alea(graine) { let s = graine >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }

/**
 * [duree] s of a [ton] Hz sine (or an impulse in frame [clic]); [retard](k) the network's delay of frame k (ms),
 * [perdue](k) true when lost; the card at [sr] Hz, [derive] faster than the server, read by blocks of [bloc].
 */
function simule(o) {
  const rate = o.rate || 8000, sr = o.sr || 48000, bloc = o.bloc || 128, duree = o.duree || 30, N = rate / 50;
  const t = new TamponSon(rate, sr), retard = o.retard || (() => 5), perdue = o.perdue || (() => false);
  const nTrames = Math.floor(duree * 50);
  // Arrivals, in order: a frame never overtakes the one before.
  const arrivees = []; let avant = 0;
  for (let k = 0; k < nTrames; k++) {
    const prod = (k + 1) * 20, a = Math.max(avant, prod + retard(k)); avant = a;
    if (!perdue(k)) arrivees.push({ k, prod, a });
  }
  const pasCarte = bloc / sr * 1000 / (1 + (o.derive || 0));
  const out = new Float32Array(Math.ceil(duree * sr * 1.01 / bloc + 2) * bloc);
  let i = 0, j = 0, tc = 0, pire = 0, niveaux = [];
  const echantillon = (k, n) => o.clic != null ? (k === o.clic && n === 0 ? 1 : 0) : 0.5 * Math.sin(2 * Math.PI * (o.ton || 440) * (k * N + n) / rate);
  while (tc < duree * 1000 - 200) {
    while (i < arrivees.length && arrivees[i].a <= tc) {
      const f = arrivees[i], x = new Float32Array(N);
      for (let n = 0; n < N; n++) x[n] = echantillon(f.k, n);
      t.recoit(f.k + (o.seq0 || 0), f.prod, f.a + 1000, x); i++;
    }
    const b = out.subarray(j * bloc, (j + 1) * bloc);
    t.lit(b, bloc);
    for (let n = 0; n < bloc; n++) if (!isFinite(b[n])) throw new Error('NaN');
    if (tc > 5000) niveaux.push(t.etat().niveau);
    j++; tc += pasCarte;
  }
  const e = t.etat();
  const moy = niveaux.reduce((a, b) => a + b, 0) / Math.max(1, niveaux.length);
  return { e, out: out.subarray(0, j * bloc), pasCarte, moy, sr, rate, perdues: nTrames - arrivees.length };
}

const resultats = []; let echec = false;
function verifie(nom, ok, info) { resultats.push({ nom, ok, info }); if (!ok) echec = true; }
const r1 = v => Math.round(v * 10) / 10;

// A clean network: no frame made up, the depth at the 60 ms floor, the pace untouched.
{
  const r = simule({ duree: 30 });
  verifie('propre', r.e.masques === 0 && r.e.vides === 0 && Math.abs(r.moy - 60) < 6 && Math.abs(r.e.derive) < 0.001,
    { masques: r.e.masques, vides: r.e.vides, niveau: r1(r.moy), cible: r1(r.e.cible), derive: r.e.derive });
}
// Jitter up to 50 ms: the target follows (p95 + 20 ms, within 60–120), hardly a gap.
{
  const h = alea(7);
  const r = simule({ duree: 60, retard: () => 5 + 50 * h() });
  verifie('gigue 0-50 ms', r.e.cible >= 60 && r.e.cible <= 120 && r.e.gigue > 35 && r.e.vides <= 2 && r.e.masques <= 30,
    { cible: r1(r.e.cible), gigue: r1(r.e.gigue), vides: r.e.vides, masques: r.e.masques, niveau: r1(r.moy) });
}
// Jitter beyond what the ceiling covers: held at 120 ms, gaps made up rather than a growing delay.
{
  const h = alea(11);
  const r = simule({ duree: 60, retard: () => 5 + 200 * h() * h() * h() });
  verifie('gigue forte', r.e.cible <= 120.001 && r.moy < 180, { cible: r1(r.e.cible), gigue: r1(r.e.gigue), vides: r.e.vides, masques: r.e.masques, niveau: r1(r.moy) });
}
// 3 % of the frames lost: each made up, nothing else.
{
  const h = alea(3);
  const r = simule({ duree: 60, perdue: k => k > 10 && h() < 0.03 });
  verifie('pertes 3 %', Math.abs(r.e.masques - r.perdues) <= 2 && r.e.vides === 0 && r.e.tardifs === 0,
    { perdues: r.perdues, masques: r.e.masques, vides: r.e.vides, tardifs: r.e.tardifs });
}
// The card 0.3 % fast: the pace follows (≈ −0.3 %), the depth stays at the target, no gap.
{
  const r = simule({ duree: 180, derive: 0.003 });
  verifie('dérive +0,3 %', r.e.vides === 0 && Math.abs(r.e.derive + 0.003) < 0.0008 && Math.abs(r.e.niveau - r.e.cible) < 10,
    { derive: r.e.derive, niveau: r1(r.e.niveau), cible: r1(r.e.cible), vides: r.e.vides });
}
// …and 0.3 % slow.
{
  const r = simule({ duree: 180, derive: -0.003 });
  verifie('dérive −0,3 %', r.e.vides === 0 && r.e.sautes === 0 && Math.abs(r.e.derive - 0.003) < 0.0008 && Math.abs(r.e.niveau - r.e.cible) < 10,
    { derive: r.e.derive, niveau: r1(r.e.niveau), cible: r1(r.e.cible), sautes: r.e.sautes });
}
// A 1 s stall, then the frames all at once: back to the target, not 1 s late for ever.
{
  const r = simule({ duree: 30, retard: k => (k >= 500 && k < 550) ? 5 + (550 - k) * 20 : 5 });
  verifie('arrêt puis rafale', r.e.sautes + r.e.vides >= 1 && Math.abs(r.e.niveau - r.e.cible) < 15, { sautes: r.e.sautes, vides: r.e.vides, niveau: r1(r.e.niveau), cible: r1(r.e.cible) });
}
// A frame lost in a tone: made up from the one before, softer, never louder.
{
  const r = simule({ duree: 20, perdue: k => k === 400 });
  const parTrame = r.sr * 0.02, eff = (a, b) => { let s = 0; for (let n = a; n < b; n++) s += r.out[n] * r.out[n]; return Math.sqrt(s / (b - a)); };
  // Where frame 400 was played: find it by the depth (≈ 60 ms + 20 ms of its own), searched around.
  let mini = 1, ref = eff(r.out.length - 4 * parTrame, r.out.length - 3 * parTrame);
  for (let d = -6; d <= 6; d++) {
    const debut = Math.round((400 * 20 + 80 + d * 5) / r.pasCarte * 128);
    if (debut > 0 && debut + parTrame < r.out.length) mini = Math.min(mini, eff(debut, debut + parTrame) / ref);
  }
  verifie('masquage', r.e.masques === 1 && mini > 0.2 && mini < 0.95, { masques: r.e.masques, rapport: Math.round(mini * 100) / 100 });
}
// The delay of the buffer itself: an impulse in frame 300, its time out less its time made.
{
  const r = simule({ duree: 15, clic: 300, retard: () => 0 });
  let n = 0, m = 0; for (let i = 0; i < r.out.length; i++) if (Math.abs(r.out[i]) > m) { m = Math.abs(r.out[i]); n = i; }
  // Frame 300's first sample is made at 300·20 ms (its frame leaves at 301·20 ms).
  const latence = n / 128 * r.pasCarte - 300 * 20;
  verifie('latence du tampon', latence > 55 && latence < 90, { latenceMs: r1(latence) });
}
// Numbers that wrap or restart (server restarted): followed, not stuck.
{
  const r = simule({ duree: 10, seq0: 4294967000 });
  verifie('numéros élevés', r.e.vides === 0 && r.e.masques === 0, { vides: r.e.vides, masques: r.e.masques });
}

console.log(JSON.stringify(resultats, null, 1));
process.exit(echec ? 1 : 0);
