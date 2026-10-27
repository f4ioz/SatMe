// SatMe — builds the flag atlas the server's renderers draw (videos, pictures):
// every flag of the page's « Drapeaux » font (drapeaux.woff2, Twemoji Country
// Flags, CC BY 4.0) drawn by a headless Chromium, packed in one PNG with an
// index, so that a video shows the very flags the page shows.
//
//   node tools/drapeaux/genere-atlas.mjs [hauteur px, default 48]
//
// Writes pc/src/main/resources/fr/f4ioz/satcombo/pc/drapeaux/drapeaux.png and
// drapeaux.json: { em: {h, haut, avance}, d: { "fr": [x, y, w, h], "gb-sct": … } }
// (em: the flag's height, its top above the baseline and its advance, shares
// of the font's size, to set it in a line of text as the page does).
// Needs `chromium` (snap or not), Node 22+ (WebSocket, fetch) and Python's Pillow (palette PNG).
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';

const racine = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const police = readFileSync(join(racine, 'moteur/src/main/resources/fr/f4ioz/satcombo/demo/drapeaux.woff2')).toString('base64');
const sortie = join(racine, 'pc/src/main/resources/fr/f4ioz/satcombo/pc/drapeaux');
const hauteur = Number(process.argv[2] || 48);

const port = 9800 + Math.floor(Math.random() * 150);
const chrome = spawn('chromium', ['--headless=new', '--no-sandbox', '--remote-debugging-port=' + port,
  '--user-data-dir=' + join(homedir(), 'snap/chromium/common/atlas-' + port), 'about:blank'], { stdio: 'ignore' });
const pause = ms => new Promise(r => setTimeout(r, ms));

// What the page runs: load the font, find the sequences it draws as one flag, draw, crop, pack.
const script = `(async () => {
  const b = Uint8Array.from(atob('${police}'), c => c.charCodeAt(0));
  const f = new FontFace('Drapeaux', b.buffer); await f.load(); document.fonts.add(f);
  const H = ${hauteur};
  const ri = c => String.fromCodePoint(0x1F1E6 + c.charCodeAt(0) - 65);
  const tag = s => String.fromCodePoint(0x1F3F4, ...[...s].map(c => 0xE0000 + c.charCodeAt(0)), 0xE007F);
  const t = document.createElement('canvas').getContext('2d');
  const P = 200; t.font = P + 'px Drapeaux';
  const seul = {}; for (let i = 0; i < 26; i++) { const c = String.fromCharCode(65 + i); seul[c] = t.measureText(ri(c)).width; }
  const liste = [];
  for (let i = 0; i < 26; i++) for (let j = 0; j < 26; j++) {
    const a = String.fromCharCode(65 + i), z = String.fromCharCode(65 + j);
    // One glyph (a ligature) is narrower than its two letters side by side.
    if (t.measureText(ri(a) + ri(z)).width < (seul[a] + seul[z]) * 0.75) liste.push([(a + z).toLowerCase(), ri(a) + ri(z)]);
  }
  for (const s of ['gbeng', 'gbsct', 'gbwls']) liste.push([s.slice(0, 2) + '-' + s.slice(2), tag(s)]);
  // Each flag drawn big, cropped to its pixels; the median gives the em's numbers.
  const c = document.createElement('canvas'); c.width = 3 * P; c.height = 2 * P;
  const x = c.getContext('2d', { willReadFrequently: true }); x.font = P + 'px Drapeaux'; x.textBaseline = 'alphabetic';
  const bruts = [];
  for (const [k, s] of liste) {
    x.clearRect(0, 0, c.width, c.height); x.fillStyle = '#000'; x.fillText(s, P / 2, 1.4 * P);
    const d = x.getImageData(0, 0, c.width, c.height).data;
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
    for (let y = 0; y < c.height; y++) for (let xx = 0; xx < c.width; xx++) if (d[(y * c.width + xx) * 4 + 3] > 8) { if (xx < x0) x0 = xx; if (xx > x1) x1 = xx; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    if (x1 < 0) continue;
    bruts.push({ k, s, x0, y0, w: x1 - x0 + 1, h: y1 - y0 + 1, avance: x.measureText(s).width });
  }
  const med = a => a.slice().sort((p, q) => p - q)[a.length >> 1];
  const hP = med(bruts.map(r => r.h)), em = { h: hP / P, haut: (1.4 * P - med(bruts.map(r => r.y0))) / P, avance: med(bruts.map(r => r.avance)) / P };
  // Drawn again at the atlas' scale (the flag ${hauteur} px high), packed in rows.
  const k = H / hP, F = P * k, larg = 1024;
  const atlas = document.createElement('canvas'); atlas.width = larg; atlas.height = 4096;
  const a = atlas.getContext('2d'); a.font = F + 'px Drapeaux'; a.textBaseline = 'alphabetic';
  const idx = {}; let cx = 1, cy = 1, ligne = 0;
  for (const r of bruts) {
    const w = Math.ceil(r.w * k) + 2, h = Math.ceil(r.h * k) + 2;
    if (cx + w > larg) { cx = 1; cy += ligne + 1; ligne = 0; }
    a.save(); a.beginPath(); a.rect(cx, cy, w, h); a.clip();
    a.fillText(r.s, cx + 1 + (P / 2 - r.x0) * k, cy + 1 + (1.4 * P - r.y0) * k); a.restore();
    idx[r.k] = [cx, cy, w, h]; cx += w + 1; ligne = Math.max(ligne, h);
  }
  const fin = document.createElement('canvas'); fin.width = larg; fin.height = cy + ligne + 1;
  fin.getContext('2d').drawImage(atlas, 0, 0);
  return { png: fin.toDataURL('image/png'), json: { em, d: idx } };
})()`;

let ws, id = 0; const attente = new Map();
const cmd = (method, params = {}) => new Promise(r => { const i = ++id; attente.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
try {
  let cible;
  for (let k = 0; k < 60 && !cible; k++) { await pause(500); try { cible = (await (await fetch('http://127.0.0.1:' + port + '/json')).json()).find(t => t.type === 'page'); } catch (x) {} }
  ws = new WebSocket(cible.webSocketDebuggerUrl);
  await new Promise(r => ws.onopen = r);
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && attente.has(d.id)) { attente.get(d.id)(d.result || d); attente.delete(d.id); } };
  const r = await cmd('Runtime.evaluate', { expression: script, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
  const { png, json } = r.result.value;
  mkdirSync(sortie, { recursive: true });
  writeFileSync(join(sortie, 'drapeaux.png'), Buffer.from(png.split(',')[1], 'base64'));
  const lignes = Object.entries(json.d).map(([k, v]) => '"' + k + '":' + JSON.stringify(v));
  writeFileSync(join(sortie, 'drapeaux.json'), '{"em":' + JSON.stringify(json.em) + ',\n"d":{\n' + lignes.join(',\n') + '\n}}\n');
  // Chromium's PNG is big: 256 colours with their alpha make it 40 %, the flags' flat colours unharmed (palette.py).
  const q = spawnSync('python3', ['-I', join(racine, 'tools/drapeaux/palette.py'), join(sortie, 'drapeaux.png')], { stdio: 'inherit' });
  if (q.status !== 0) console.warn('Pillow missing: the atlas stays in full colour (bigger).');
  console.log(Object.keys(json.d).length + ' flags, em ' + JSON.stringify(json.em));
} finally { chrome.kill(); }
