/*
 * F4IOZ site — what SatMe's tabs (Orbites, Sked) expect of the page that
 * hosts them, as SatMe-PC's page gives it: a few helpers, and the station's
 * parts the site does not have (no rig, no SSTV auto-pilot, no space weather
 * here) answered empty. Read-only: nothing here posts anything.
 */
window.SATME_RACINE = '/';
window.SATME_SITE = true;
const g = id => document.getElementById(id);
const v = id => g(id).value.trim();
const enc = encodeURIComponent;
const esc = x => String(x == null ? '' : x).replace(/&/g, '&amp;').replace(/</g, '&lt;');
/* Times in the visitor's own time zone (UTC when asked: the « UTC » box of the page, if any). */
const utc = () => { try { return localStorage.getItem('site-utc') === '1'; } catch (x) { return false; } };
function hm(ms, s) {
  const d = new Date(ms);
  const f = utc() ? [d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds()] : [d.getHours(), d.getMinutes(), d.getSeconds()];
  return f.slice(0, s ? 3 : 2).map(n => String(n).padStart(2, '0')).join(':');
}
function duree(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), r = s % 60;
  return h > 0 ? h + ' h ' + String(m).padStart(2, '0') : m + ':' + String(r).padStart(2, '0');
}
function txt(id, t) { const e = g(id); if (e && e.textContent !== t) e.textContent = t; }
function jourHeure(ms) {
  const d = new Date(ms);
  return (utc() ? d.toISOString().slice(5, 10).split('-').reverse().join('/') : d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' })) + ' ' + hm(ms);
}
function polaire(az, el) {
  const r = 100 * (1 - Math.max(0, Math.min(90, el)) / 90), a = az * Math.PI / 180;
  return [r * Math.sin(a), -r * Math.cos(a)];
}
/* SatMe-PC's own parts, absent here. */
let ES = null; const ES_COUL = {}, ES_NIVEAU = {};
function apMarque() { return ''; }
function apMarqueSat() { return ''; }
function pliRedim() {}
async function post() { return null; }
/* The station: the site's, or the one the visitor chose (kept in this browser). */
const SITE = JSON.parse(document.getElementById('site-donnees').textContent);
let E = { indicatif: '', locator: SITE.locator, suivi: null };
try { const l = localStorage.getItem('site-loc'); if (l && /^[A-R]{2}[0-9]{2}([A-X]{2})?$/.test(l)) E.locator = l; } catch (x) {}
/* The satellites (the Sked's list). */
let tous = [];
async function chargeSatellites() { try { tous = await (await fetch('/api/satellites')).json(); } catch (x) { tous = []; } }
/* The map's tiles (OpenStreetMap, through the site). */
const qTuiles = new Map();
function wyq(lat) { const l = Math.max(-85, Math.min(85, lat)) * Math.PI / 180; return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2; }
function qTuile(z, x, y) {
  const n = 1 << z, xw = ((x % n) + n) % n, k = z + '/' + xw + '/' + y;
  let i = qTuiles.get(k);
  if (!i) {
    i = new Image(); i.onload = () => { if (typeof dessineSked === 'function') dessineSked(); };
    i.src = '/journal/tuile?z=' + z + '&x=' + xw + '&y=' + y; qTuiles.set(k, i);
    if (qTuiles.size > 500) qTuiles.delete(qTuiles.keys().next().value);
  }
  return i.complete && i.naturalWidth ? i : null;
}
/* The Sked's video is SatMe-PC's alone. */
function kfFormats() {}
function kfAnime() { return false; }
function filmeSked() {}
/* « My » station for the Sked: the visitor's. */
function skedMoi() { return '&moi=' + enc(E.locator); }
