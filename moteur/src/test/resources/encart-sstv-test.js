// The SSTV inset's pure part (EncartSstvJs.PUR), run under node (EncartSstvJsTest), TZ=Europe/Paris.
// By hand: TZ=Europe/Paris node encart-sstv-test.js encart.js (encart.js: EncartSstvJs.PUR + module.exports).
const assert = require('assert');
const { esOrtho, esCentre, esTca, esEtat, esDescente, esHeures } = require(require('path').resolve(process.argv[2]));
const pres = (a, b, e) => assert(Math.abs(a - b) <= (e || 1e-6), a + ' ≠ ' + b);

// The globe: the centre faces the eye, east to the right, north up, the far side behind.
{
  const c = { lat: 48, lon: 2 };
  const m = esOrtho(c, 48, 2); pres(m[0], 0); pres(m[1], 0); pres(m[2], 1);
  assert(esOrtho(c, 48, 20)[0] > 0, 'l’est à droite');
  assert(esOrtho(c, 60, 2)[1] > 0, 'le nord en haut');
  assert(esOrtho(c, -48, -178)[2] < 0, 'les antipodes derrière');
  const q = esOrtho(c, 10, 100); pres(Math.hypot(q[0], q[1], q[2]), 1);
}
// Halfway between the station and the satellite, over the date line too; the station alone without a satellite.
{
  const m = esCentre({ lat: 0, lon: 0 }, { lat: 0, lon: 90 }); pres(m.lat, 0); pres(m.lon, 45);
  const d = esCentre({ lat: 10, lon: 170 }, { lat: 10, lon: -170 }); pres(Math.abs(d.lon), 180, 1e-6); assert(d.lat > 10);
  assert.deepStrictEqual(esCentre({ lat: 45, lon: 5 }, null), { lat: 45, lon: 5 });
  const n = esCentre({ lat: 48, lon: 2 }, { lat: 50, lon: 6 }); assert(n.lat > 48 && n.lat < 50.2 && n.lon > 2 && n.lon < 6);
}
// TCA: where the arc is highest, spread from AOS to LOS; the trace when no arc; halfway when neither.
{
  const pa = { aos: 1000000, los: 1600000, elMax: 40, trace: [[10, 0], [40, 40], [80, 10], [100, 0]] };
  const arc = [[0, 0], [10, 10], [20, 30], [30, 40], [40, 20], [50, 0], [60, 0]];
  assert.strictEqual(esTca(pa, arc), 1300000);
  assert.strictEqual(esTca(pa, null), 1200000);
  assert.strictEqual(esTca({ aos: 0, los: 1000 }, []), 500);
  assert.strictEqual(esTca(null, arc), null);
}
// Which pass: under way, else the next one (the station's or its list's), else none.
{
  const a = { aos: 100, los: 200 }, b = { aos: 300, los: 400 }, c = { aos: 500, los: 600 };
  assert.deepStrictEqual(esEtat(a, [b, c], 150), { quoi: 'encours', p: a });
  assert.deepStrictEqual(esEtat(a, [b, c], 250), { quoi: 'prochain', p: b });
  assert.deepStrictEqual(esEtat(null, [c, b], 50), { quoi: 'prochain', p: b });
  assert.deepStrictEqual(esEtat(a, [], 200), { quoi: 'aucun', p: null });
  assert.deepStrictEqual(esEtat(null, null, 0), { quoi: 'aucun', p: null });
}
// The SSTV downlink: the campaign under way, else the satellite's campaign, else a transmitter that says SSTV.
{
  const em = [{ desc: 'Mode V FM', mode: 'FM', dlBas: 145990000 }, { desc: 'SSTV', mode: 'FM', dlBas: 145800000 }];
  const ap = { campagnes: [{ id: 'x', catnum: 25544, mode: 'PD120', freqHz: 0, emetteur: 'SSTV', active: true }, { id: 'y', catnum: 1, mode: 'Robot 36', freqHz: 437000000 }],
    enCours: null };
  assert.deepStrictEqual(esDescente(25544, ap, em), { mode: 'PD120', hz: 145800000, emetteur: 'SSTV' });
  assert.deepStrictEqual(esDescente(25544, Object.assign({}, ap, { enCours: { campagne: 'x', mode: 'PD180', freqHz: 145801000 } }), em), { mode: 'PD180', hz: 145801000, emetteur: 'SSTV' });
  assert.deepStrictEqual(esDescente(1, ap, []), { mode: 'Robot 36', hz: 437000000, emetteur: '' });
  assert.deepStrictEqual(esDescente(99, null, em), { mode: '', hz: 145800000, emetteur: 'SSTV' });
  assert.deepStrictEqual(esDescente(99, { absent: true }, [em[0]]), { mode: '', hz: 0, emetteur: '' });
  assert.deepStrictEqual(esDescente(25544, { campagnes: [{ id: 'x', catnum: 25544, mode: 'PD120', active: false }] }, []), { mode: '', hz: 0, emetteur: '' });
}
// Times: local and UTC, the page's choice first (Paris: UTC+1 in winter, +2 in summer).
{
  const hiver = Date.UTC(2026, 0, 15, 12, 32), ete = Date.UTC(2026, 6, 15, 12, 32);
  assert.strictEqual(esHeures(hiver, false), '13:32 · 12:32 UTC');
  assert.strictEqual(esHeures(hiver, true), '12:32 UTC · 13:32 loc.');
  assert.strictEqual(esHeures(ete, false), '14:32 · 12:32 UTC');
}
console.log('ok');
