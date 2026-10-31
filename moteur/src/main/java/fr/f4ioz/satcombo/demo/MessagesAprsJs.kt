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
 * The APRS tab's « Messages » ([fr.f4ioz.satcombo.aprs.MessagesAprs],
 * `api/aprs/messages`): the messages to the station's callsign (any SSID)
 * and the bulletins, those via a satellite marked; the tab's badge counts
 * the unread (kept per browser); a browser notification for a new message
 * to me, off by default. Receive only: nothing is ever sent from here, no
 * acknowledgement. No Kotlin template in the script.
 */
object MessagesAprsJs {

    const val CSS = """
  .aq-badge { display:inline-block; min-width:16px; padding:0 5px; margin-left:3px; border-radius:9px; background:var(--ambre); color:#06131A; font-size:10px; font-weight:700; line-height:16px; }
  .aq-badge[hidden] { display:none; }
  .aq-msgs { flex:1; overflow:auto; min-height:0; }
  .aq-msg { padding:5px 4px; border-bottom:1px solid var(--ligne); font-size:var(--fs-s); }
  .aq-msg.moi { border-left:3px solid var(--rx); padding-left:6px; }
  .aq-msg.neuf { background:rgba(255,209,102,.07); }
  .aq-msg .aq-mt { display:flex; gap:6px; align-items:baseline; flex-wrap:wrap; color:var(--texte-2); }
  .aq-msg .aq-mt b { color:var(--texte); font-size:var(--fs-m); }
  .aq-msg .aq-mx { margin-top:2px; overflow-wrap:anywhere; color:var(--texte); font-size:var(--fs-m); }
"""

    const val HTML = """<div class="carte" style="flex:1;display:flex;flex-direction:column;min-height:0;margin:0">
        <div class="aq-filtres">
          <span class="aq-seg" id="aq-msg-f" role="group" aria-label="Quels messages"><button type="button" data-f="" aria-pressed="true">Tous</button><button type="button" data-f="moi" aria-pressed="false">Pour moi</button><button type="button" data-f="bulletin" aria-pressed="false">Bulletins</button></span>
          <label class="petit" title="Une notification du navigateur à l'arrivée d'un message pour toi (décoché d'origine)"><input type="checkbox" id="aq-msg-notif"> notifier</label>
        </div>
        <div class="petit" id="aq-msg-etat" style="margin-bottom:4px"></div>
        <div class="aq-msgs" id="aq-msgs"></div>
        <div class="petit aide" style="margin-top:4px">Lecture seule : SatMe n’envoie aucun accusé de réception. Pour répondre, ton poste ou ton logiciel APRS.</div>
      </div>"""

    const val JS = """
// ------------------------------------------------------------------ APRS: the messages for me (MessagesAprsJs)
let AQM = null, aqmVu = 0, aqmF = '', aqmNotifie = 0, aqmLu = 0;
try { aqmVu = +(localStorage.getItem('aq-msg-vu') || 0); aqmNotifie = aqmVu; aqmF = localStorage.getItem('aq-msg-f') || ''; g('aq-msg-notif').checked = localStorage.getItem('aq-msg-notif') === '1'; } catch (x) {}
async function aqmTic() {
  try { AQM = await (await fetch('api/aprs/messages')).json(); } catch (x) { return; }
  aqmLu = Date.now(); aqmDessine(); aqmNotif();
}
function aqmOuvert() { return ouverte('aprs') && !g('aq-p-msg').hidden; }
function aqmDessine() {
  const o = AQM; if (!o || !o.messages) return;
  const l = o.messages.filter(m => !aqmF || m.genre === aqmF);
  const neufs = o.messages.filter(m => m.t > aqmVu).length;
  const b = g('aq-msg-n'); b.hidden = !neufs; txt('aq-msg-n', String(neufs));
  txt('aq-msg-etat', !o.moi ? 'Ton indicatif n’est pas réglé (Réglages) : seuls les bulletins sont listés.'
    : o.pourMoi + ' message' + (o.pourMoi > 1 ? 's' : '') + ' pour ' + o.moi + ' (tous SSID) · ' + o.bulletins + ' bulletin' + (o.bulletins > 1 ? 's' : '') + ' · 7 jours');
  const jour = t => { const d = new Date(t); return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }) + ' ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
  const h = !l.length ? '<div class="aq-vide">Aucun message ' + (aqmF === 'bulletin' ? 'de bulletin' : 'pour toi') + ' entendu.</div>' : l.map(m =>
    '<div class="aq-msg' + (m.genre === 'moi' ? ' moi' : '') + (m.t > aqmVu ? ' neuf' : '') + '"><div class="aq-mt"><b>' + esc(m.de) + '</b><span>→ ' + esc(m.a) + '</span>' +
    (m.sat ? '<span class="aq-voie" style="color:#FFD166;border-color:#FFD166">via ' + esc(m.sat) + '</span>' : '') +
    (m.genre === 'bulletin' ? '<span class="aq-voie" style="color:var(--texte-2)">bulletin</span>' : '') +
    '<span style="margin-left:auto">' + jour(m.t) + (m.fois > 1 ? ' · ×' + m.fois : '') + (m.id ? ' · n° ' + esc(m.id) : '') + '</span></div><div class="aq-mx">' + esc(m.texte) + '</div></div>').join('');
  if (g('aq-msgs').dataset.vu !== h) { g('aq-msgs').dataset.vu = h; g('aq-msgs').innerHTML = h; }
  document.querySelectorAll('#aq-msg-f button').forEach(x => x.setAttribute('aria-pressed', x.dataset.f === aqmF ? 'true' : 'false'));
  // Read once the panel is shown (after the new ones were seen highlighted).
  if (aqmOuvert() && o.messages.length && o.messages[0].t > aqmVu) setTimeout(() => { if (aqmOuvert()) { aqmVu = o.messages[0].t; try { localStorage.setItem('aq-msg-vu', String(aqmVu)); } catch (x) {} } }, 4000);
}
function aqmNotif() {
  const o = AQM; if (!o || !o.messages || !g('aq-msg-notif').checked || !('Notification' in window) || Notification.permission !== 'granted') return;
  const l = o.messages.filter(m => m.genre === 'moi' && m.t > aqmNotifie);
  if (!l.length) return;
  aqmNotifie = Math.max(...l.map(m => m.t));
  const m = l[0];
  try { new Notification('APRS · message de ' + m.de, { body: m.texte + (m.sat ? ' (via ' + m.sat + ')' : ''), tag: 'satme-aprs-msg' }); } catch (x) {}
}
g('aq-msg-notif').onchange = async () => {
  const c = g('aq-msg-notif');
  if (c.checked && 'Notification' in window && Notification.permission !== 'granted') { try { if (await Notification.requestPermission() !== 'granted') c.checked = false; } catch (x) { c.checked = false; } }
  if (c.checked && AQM && AQM.messages && AQM.messages.length) aqmNotifie = Math.max(aqmNotifie, AQM.messages[0].t);
  try { localStorage.setItem('aq-msg-notif', c.checked ? '1' : '0'); } catch (x) {}
};
document.querySelectorAll('#aq-msg-f button').forEach(x => { x.onclick = () => { aqmF = x.dataset.f; try { localStorage.setItem('aq-msg-f', aqmF); } catch (e) {} aqmDessine(); }; });
// Every 10 s while the APRS tab is open; every minute otherwise, for the badge and the notification.
setInterval(() => { if (Date.now() - aqmLu > (ouverte('aprs') ? 10000 : 60000)) aqmTic(); }, 2000);
"""
}
