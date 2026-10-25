// The globes' pure part (OrbitesJs.JS), run under node (OrbitesJsTest).
// By hand: node orbites-test.js orbites.js (orbites.js: OrbitesJs.JS + module.exports).
const assert = require('assert');
const { obEmpreinte, obNuit, obPassages, obSoleil } = require(require('path').resolve(process.argv[2]));

const ecart = (la1, lo1, la2, lo2) => {
  const r = Math.PI / 180;
  return Math.acos(Math.min(1, Math.sin(la1 * r) * Math.sin(la2 * r) + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.cos((lo2 - lo1) * r))) / r;
};

// A footprint over Europe: every point at its radius from the satellite, nothing added.
{
  const lam = 20 * Math.PI / 180, e = obEmpreinte(48, 2, lam, 90);
  assert.strictEqual(e.length, 91);
  e.forEach(p => assert(Math.abs(ecart(48, 2, p[0], p[1]) - 20) < 1e-6, 'rayon ' + p));
}
// Over the date line: the longitudes stay continuous (no jump of 360°).
{
  const e = obEmpreinte(0, 179, 15 * Math.PI / 180);
  for (let i = 1; i < e.length; i++) assert(Math.abs(e[i][1] - e[i - 1][1]) < 10, 'saut ' + e[i - 1] + ' → ' + e[i]);
  assert(e.some(p => p[1] > 180), 'au-delà de 180°');
}
// Over a pole: closed along the pole, spanning the whole map.
{
  const e = obEmpreinte(80, 30, 20 * Math.PI / 180), n = e.length;
  assert.strictEqual(e[n - 1][0], 90); assert.strictEqual(e[n - 2][0], 90);
  const lo = e.map(p => p[1]);
  assert(Math.max(...lo) - Math.min(...lo) > 359, 'tout le tour');
  const s = obEmpreinte(-75, 0, 25 * Math.PI / 180);
  assert.strictEqual(s[s.length - 1][0], -90);
}
// The night: the Sun south of the equator, the north pole dark, the terminator at 90° from the Sun.
{
  const n = obNuit(-12, 40, 2);
  assert.deepStrictEqual(n[n.length - 1], [90, -180]);
  const midi = n.find(p => p[1] === 40), minuit = n.find(p => p[1] === -140);
  assert(Math.abs(midi[0] - 78) < .1, 'midi ' + midi);
  assert(Math.abs(minuit[0] + 78) < .1, 'minuit ' + minuit);
  n.slice(0, -2).forEach(p => assert(Math.abs(ecart(p[0], p[1], -12, 40) - 90) < 1e-6, 'terminateur ' + p));
  assert.deepStrictEqual(obNuit(10, 0, 2).slice(-1)[0], [-90, -180]);
}
// Passes of a made-up satellite: up while sin > 1/3, every 100 minutes.
{
  const P = 6000000, el = t => 30 * Math.sin(2 * Math.PI * t / P) - 10;
  const l = obPassages(el, 0, 3 * P, 30000);
  assert.strictEqual(l.length, 3);
  const a = Math.asin(1 / 3) / (2 * Math.PI) * P;
  l.forEach((p, k) => {
    assert(Math.abs(p.aos - (k * P + a)) < 1000, 'aos ' + p.aos);
    assert(Math.abs(p.los - (k * P + P / 2 - a)) < 1000, 'los ' + p.los);
    assert(Math.abs(p.tca - (k * P + P / 4)) < P / 150, 'tca ' + p.tca);
    assert(Math.abs(p.max - 20) < .01, 'max ' + p.max);
  });
  // Already up at the start: the pass counted from there; an unknown elevation breaks nothing.
  const m = obPassages(t => t < 60000 ? 5 : t < 120000 ? null : -5, 0, 600000, 30000);
  assert.strictEqual(m.length, 0);
  const d = obPassages(el, P / 4, P, 30000);
  assert.strictEqual(d[0].aos, P / 4);
}
// The Sun's declination at the June solstice and the March equinox of 2026.
{
  assert(Math.abs(obSoleil(Date.UTC(2026, 5, 21, 8, 24), 0).dec - 23.44) < .05);
  assert(Math.abs(obSoleil(Date.UTC(2026, 2, 20, 14, 46), 0).dec) < .05);
  // At noon in Greenwich (GMST at that instant), the Sun is near longitude 0 (the equation of time: a few degrees).
  const t = Date.UTC(2026, 3, 15, 12, 0), jd = t / 86400000 + 2440587.5, T = (jd - 2451545) / 36525;
  const gmst = ((280.46061837 + 360.98564736629 * (jd - 2451545) + 0.000387933 * T * T) % 360) * Math.PI / 180;
  assert(Math.abs(obSoleil(t, gmst).lon) < 3, 'midi ' + obSoleil(t, gmst).lon);
}
console.log('ok');
