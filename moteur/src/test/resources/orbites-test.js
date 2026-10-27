// The globes' pure part (OrbitesJs.JS), run under node (OrbitesJsTest).
// By hand: node orbites-test.js orbites.js (orbites.js: OrbitesJs.JS + module.exports).
const assert = require('assert');
const { obEmpreinte, obNuit, obPassages, obSoleil, obLocator, obPisteA, obGmst, obLune, obLunePath, obHauteurSoleil, obSoleilJour, obVueSat, obReculSat } = require(require('path').resolve(process.argv[2]));

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
// Locators: the square's centre and size; anything else refused.
{
  const a = obLocator('JN18fs');
  assert(Math.abs(a.lat - 48.770833) < 1e-5 && Math.abs(a.lon - 2.458333) < 1e-5, 'JN18FS ' + JSON.stringify(a));
  const b = obLocator('JN18');
  assert.deepStrictEqual([b.lat, b.lon, b.dlat, b.dlon], [48.5, 3, 1, 2]);
  assert.deepStrictEqual([obLocator('RR').lat, obLocator('RR').lon], [85, 170]);
  ['', 'JN1', 'JN18F', 'ZZ00', 'JN18FSX', 'F4IOZ', null].forEach(x => assert.strictEqual(obLocator(x), null, String(x)));
}
// A recorded track: interpolated in between, the short way over the date line, nothing outside.
{
  const l = [[0, 10, 170, 400], [1000, 12, -170, 420], [2000, 14, -160, 440]];
  const p = obPisteA(l, 500);
  assert(Math.abs(p.lat - 11) < 1e-9 && Math.abs(Math.abs(p.lon) - 180) < 1e-9 && Math.abs(p.alt - 410) < 1e-9, JSON.stringify(p));
  assert(Math.abs(obPisteA(l, 1500).lon + 165) < 1e-9);
  assert.strictEqual(obPisteA(l, -1), null); assert.strictEqual(obPisteA(l, 2001), null); assert.strictEqual(obPisteA([], 0), null);
  // Two passes of a session, hours apart: nothing between them, each one still found.
  const s = [[0, 10, 0, 400], [60000, 11, 1, 400], [7200000, 40, 50, 400], [7260000, 41, 51, 400]];
  assert.strictEqual(obPisteA(s, 3600000), null);
  assert(Math.abs(obPisteA(s, 30000).lat - 10.5) < 1e-9 && Math.abs(obPisteA(s, 7230000).lat - 40.5) < 1e-9);
  assert(obPisteA(s, 3600000, 9e6) !== null);
}
// The Moon: new, first quarter, full and last quarter of April 2024 (published times, UTC), its phase and lit share.
{
  const l = t => obLune(Date.parse(t)), pres = (a, b, e, m) => assert(Math.abs(a - b) < e, m + ' ' + a);
  pres(l('2024-04-08T18:21:00Z').lumiere, 0, .01, 'nouvelle lune');
  pres(l('2024-04-15T19:13:00Z').phase, .25, .015, 'premier quartier');
  const p = l('2024-04-23T23:49:00Z'); pres(p.phase, .5, .015, 'pleine lune'); assert(p.lumiere > .99, 'pleine ' + p.lumiere);
  // Full: opposite the Sun in the sky (right ascension 180° away from the Sun's, about 31° then).
  pres(Math.abs(p.ra - 211), 0, 3, 'ascension droite');
  pres(l('2024-05-01T11:27:00Z').phase, .75, .015, 'dernier quartier');
  for (let t = Date.parse('2024-01-01T00:00:00Z'); t < Date.parse('2025-01-01T00:00:00Z'); t += 86400000 * 3) {
    const m = obLune(t); assert(Math.abs(m.dec) < 29.5 && m.lon >= -180 && m.lon <= 180 && m.dist > 355000 && m.dist < 407000, JSON.stringify(m));
  }
  // The lit part: a closed path, the crescent's terminator on the lit side, the gibbous one on the other.
  assert(/^M0,-10\.00A10\.00,10\.00 0 0 1 0,10\.00A3\.09,10\.00 0 0 0 0,-10\.00Z$/.test(obLunePath(.2, 10)), obLunePath(.2, 10));
  assert(/ 0 0 1 0,-10\.00Z$/.test(obLunePath(.4, 10)) && / 0 0 0 0,10\.00A/.test(obLunePath(.8, 10)));
}
// The Sun at the station: Paris on 21 June 2024, sunrise 03:47 and sunset 19:58 UTC (published times), polar day at 80° N.
{
  const j = obSoleilJour(Date.parse('2024-06-21T00:00:00Z'), 48.857, 2.352), m = t => new Date(t).toISOString().slice(11, 16);
  assert(Math.abs(j.lever - Date.parse('2024-06-21T03:47:00Z')) < 180000, 'lever ' + m(j.lever));
  assert(Math.abs(j.coucher - Date.parse('2024-06-21T19:58:00Z')) < 180000, 'coucher ' + m(j.coucher));
  assert(j.aube < j.lever && j.crepuscule > j.coucher, 'aube et crépuscule');
  const p = obSoleilJour(Date.parse('2024-06-21T00:00:00Z'), 80, 0);
  assert(p.lever === null && p.coucher === null, 'jour polaire');
  // Noon at Greenwich on an equinox: the Sun high, near 90° − the latitude.
  assert(Math.abs(obHauteurSoleil(Date.parse('2024-03-20T12:07:00Z'), 45, 0) - 45) < 1.5);
  assert(obGmst(0) >= 0 && obGmst(0) < 2 * Math.PI);
}
// The Earth seen from the satellite: the ISS's footprint (≈ 2 250 km), a place on its edge sees it on the horizon, below it overhead.
{
  const v = obVueSat(420, 48, 2);
  assert(Math.abs(v.rayon - 2254) < 5, 'rayon ' + v.rayon);
  const bord = obVueSat(420, 48, 2, { lat: 48 + v.lam * 180 / Math.PI, lon: 2 });
  assert(Math.abs(bord.el) < 1e-6 && Math.abs(bord.marge) < 1e-6, 'bord ' + JSON.stringify(bord));
  const dessous = obVueSat(420, 48, 2, { lat: 48, lon: 2 });
  assert(Math.abs(dessous.el - 90) < 1e-6 && dessous.dist < 1e-6 && Math.abs(dessous.marge - v.rayon) < 1e-6);
  const loin = obVueSat(420, 48, 2, { lat: 10, lon: 2 });
  assert(loin.marge < 0 && loin.el < 0, 'hors de l’empreinte');
  // The camera: on the satellite (k 0), drawn back until the edge is at the view's half-angle (k 1); a geostationary one never.
  const r = 1 + 420 / 6378.137, a = .45, d = obReculSat(r, a, 1), lam = Math.acos(1 / r);
  assert.strictEqual(obReculSat(r, a, 0), r);
  assert(Math.abs(Math.atan2(Math.sin(lam), d - Math.cos(lam)) - a) < 1e-9, 'recul ' + d);
  assert.strictEqual(obReculSat(6.61, a, 1), 6.61);
}
console.log('ok');
