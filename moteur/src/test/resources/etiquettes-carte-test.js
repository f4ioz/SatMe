// The map labels' layout (EtiquettesCarteJs.JS), run under node (EtiquettesCarteJsTest).
// By hand: node etiquettes-carte-test.js etiquettes.js (etiquettes.js: EtiquettesCarteJs.JS + module.exports).
'use strict';
const { groupesCarte, placeEtiquettes, colonnesEtiquette } = require(process.argv[2]);
let echecs = 0;
function verifie(c, m) { if (!c) { echecs++; console.log('ÉCHEC : ' + m); } }
function alea(graine) { let s = graine >>> 0; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }
const recouvre = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

// Groups: the same square merged however far, close points merged, distant ones apart, order kept.
{
  const g = groupesCarte([{ x: 10, y: 10, cle: 'JN45PI' }, { x: 200, y: 200 }, { x: 14, y: 12 }, { x: 300, y: 50, cle: 'JN45PI' }, { x: 205, y: 200 }], 11);
  verifie(g.length === 2, 'deux groupes attendus, ' + g.length);
  verifie(JSON.stringify(g[0].membres) === '[0,2,3]', 'groupe 1 ' + JSON.stringify(g[0].membres));
  verifie(JSON.stringify(g[1].membres) === '[1,4]', 'groupe 2 ' + JSON.stringify(g[1].membres));
  verifie(Math.abs(g[1].x - 202.5) < 1e-9, 'centre du groupe 2');
  verifie(groupesCarte([{ x: 0, y: 0 }, { x: 30, y: 0 }], 11).length === 2, 'points éloignés séparés');
  // A chain of points: no two groups end closer than [d] (their markers would overlap), nobody lost.
  const r = alea(9), l = [];
  for (let i = 0; i < 60; i++) l.push({ x: r() * 80, y: r() * 50, cle: i % 7 === 0 ? 'JN18FS' : '' });
  const h = groupesCarte(l, 16);
  for (let a = 0; a < h.length; a++) for (let b = a + 1; b < h.length; b++)
    verifie(Math.hypot(h[a].x - h[b].x, h[a].y - h[b].y) > 16, 'groupes ' + a + ' et ' + b + ' trop proches');
  verifie(h.reduce((s, q) => s + q.membres.length, 0) === 60, 'membres perdus');
}

// The live case of 22/10: four contacts a few pixels apart in Europe, each alone (no grouping).
// Then many contacts close in time and place: 40 labels within 60 px, a few markers in the way.
for (const [nom, n, etendue, graine] of [['quatre', 4, 12, 1], ['vingt', 20, 40, 2], ['quarante', 40, 60, 3], ['quarante-serrés', 40, 20, 4]]) {
  const r = alea(graine), W = 650, H = 330;
  const pts = [];
  for (let i = 0; i < n; i++) pts.push({ x: 300 + r() * etendue, y: 150 + r() * etendue, r: 5, w: 60 + r() * 40, h: 16 });
  const obst = [{ x: 280, y: 130, w: 70, h: 16 }, { x: 400, y: 100, w: 10, h: 10 }];
  const pos = placeEtiquettes(pts, obst, W, H);
  const boites = pos.map((p, i) => ({ x: p.x, y: p.y, w: pts[i].w, h: pts[i].h }));
  let chevauche = 0, couvre = 0, dehors = 0;
  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) if (recouvre(boites[i], boites[j])) chevauche++;
    for (const p of pts) if (recouvre(boites[i], { x: p.x - p.r, y: p.y - p.r, w: 2 * p.r, h: 2 * p.r })) couvre++;
    for (const o of obst) if (recouvre(boites[i], o)) couvre++;
    const b = boites[i]; if (b.x < 0 || b.y < 0 || b.x + b.w > W || b.y + b.h > H) dehors++;
    // A label that left its point says so (leader line); one beside it touches its point's neighbourhood.
    const d = Math.hypot(Math.max(b.x - pts[i].x, 0, pts[i].x - b.x - b.w), Math.max(b.y - pts[i].y, 0, pts[i].y - b.y - b.h));
    if (!pos[i].trait) verifie(d <= pts[i].r + 6, nom + ' : étiquette ' + i + ' loin sans trait (' + d.toFixed(1) + ' px)');
  }
  verifie(chevauche === 0, nom + ' : ' + chevauche + ' étiquettes se chevauchent');
  verifie(couvre === 0, nom + ' : ' + couvre + ' marqueurs ou obstacles couverts');
  verifie(dehors === 0, nom + ' : ' + dehors + ' étiquettes hors de la carte');
}

// A group's columns: one column up to L names, then more columns, the rest counted in the last cell.
{
  const k = (n, L, c) => JSON.stringify(colonnesEtiquette(n, L, c));
  verifie(k(1, 8, 3) === '{"lignes":1,"colonnes":1,"montres":1}', 'un nom ' + k(1, 8, 3));
  verifie(k(8, 8, 3) === '{"lignes":8,"colonnes":1,"montres":8}', 'huit noms ' + k(8, 8, 3));
  verifie(k(9, 8, 3) === '{"lignes":5,"colonnes":2,"montres":9}', 'neuf noms ' + k(9, 8, 3));
  verifie(k(24, 8, 3) === '{"lignes":8,"colonnes":3,"montres":24}', 'vingt-quatre noms ' + k(24, 8, 3));
  verifie(k(30, 8, 3) === '{"lignes":8,"colonnes":3,"montres":23}', 'trente noms ' + k(30, 8, 3));
}
// A lone point keeps the usual place, up and right of its marker.
{
  const p = placeEtiquettes([{ x: 100, y: 100, r: 5, w: 50, h: 16 }], [], 400, 300)[0];
  verifie(p.x === 108 && p.y === 88 && !p.trait, 'place habituelle ' + JSON.stringify(p));
}
// By the right edge, the label goes to the left rather than off the map.
{
  const p = placeEtiquettes([{ x: 390, y: 100, r: 5, w: 50, h: 16 }], [], 400, 300)[0];
  verifie(p.x + 50 <= 400 && !p.trait, 'bord droit ' + JSON.stringify(p));
}
console.log(echecs ? echecs + ' échec(s)' : 'ok');
process.exit(echecs ? 1 : 0);
