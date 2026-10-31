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
 * The rotor card's « Soleil » / « Lune » line (`api/rotor/astre`): where each
 * is now, a click points the rotor once (with « suivre », again every 20 s
 * until stopped). Only the buttons send anything; the poll reads. No Kotlin
 * template in the script.
 */
object AstreRotorJs {

    const val HTML = """      <div class="ligne" id="rot-astres" title="Pointer le rotor une fois sur le Soleil ou la Lune vus d'ici (pour aligner la parabole sur le bruit du Soleil). Seulement au clic, dans les limites du rotor ; « suivre » le recale toutes les 20 s jusqu'à l'arrêt">
        <button onclick="rotAstre('soleil')">☀ Soleil</button><button onclick="rotAstre('lune')">☾ Lune</button>
        <label class="petit"><input type="checkbox" id="rot-astre-suit"> suivre</label>
        <button class="discret" id="rot-astre-stop" hidden onclick="post('api/rotor/astre?arreter=1')">Arrêter le suivi</button>
        <span class="petit" id="rot-astre-pos"></span>
      </div>
      <div class="petit" id="rot-astre-msg"></div>
"""

    const val JS = """
// ------------------------------------------------------------------ Rotor: the Sun or the Moon, on a click (AstreRotorJs)
async function rotAstre(q) { await post('api/rotor/astre?quoi=' + q + '&suivre=' + (g('rot-astre-suit').checked ? 1 : 0)); rotAstreLit(); }
async function rotAstreLit() {
  let o; try { o = await (await fetch('api/rotor/astre')).json(); } catch (x) { return; }
  const f = (n, a) => !a ? '' : n + ' ' + a.az.toFixed(1).replace('.', ',') + '° / ' + a.el.toFixed(1).replace('.', ',').replace('-', '−') + '°' + (a.leve ? '' : ' (sous l’horizon)');
  txt('rot-astre-pos', [f('☀', o.soleil), f('☾', o.lune)].filter(x => x).join(' · '));
  txt('rot-astre-msg', o.message || '');
  g('rot-astre-stop').hidden = !o.suit;
}
setInterval(() => { if (ouverte('reglages') && g('rot-astres') && g('rot-astres').offsetParent) rotAstreLit(); }, 2000);
"""
}
