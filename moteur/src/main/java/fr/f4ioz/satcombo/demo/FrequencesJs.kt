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
 * Réglages › « Fréquences : SatPC32 et Gpredict » (`api/frequences…`): the
 * satellites' frequencies as SatPC32's `Doppler.SQF` or Gpredict's `.trsp`,
 * and such files imported into the transmitter lists (the names matched to
 * the catalogue, those not found listed). No Kotlin template in the script.
 */
object FrequencesJs {

    const val CARTE = """    <div class="carte" id="carteFrequences">
      <div class="t">Fréquences : SatPC32 et Gpredict</div>
      <div class="ligne" style="margin-top:0;flex-wrap:wrap">
        <span class="petit">Exporter</span>
        <select id="fq-quoi" title="Quels satellites"><option value="favoris">★ favoris</option><option value="suivi">le satellite suivi</option><option value="tous">tous (fréquences connues)</option></select>
        <button onclick="fqExporte('doppler.sqf')" title="Doppler.SQF de SatPC32 (une ligne par fréquence ; un transpondeur par son milieu)">Doppler.SQF</button>
        <button onclick="fqExporte('trsp.zip')" title="Un fichier NNNNN.trsp par satellite (Gpredict : ~/.config/Gpredict/trsp), dans un .zip">.trsp (zip)</button>
      </div>
      <div class="ligne" style="flex-wrap:wrap">
        <span class="petit">Importer</span>
        <input type="file" id="fq-fichier" multiple accept=".sqf,.SQF,.trsp,.txt" onchange="fqImporte(this)" style="flex:1;min-width:0">
        <button class="discret" onclick="if (confirm('Oublier toutes les fréquences importées ?')) post('api/frequences/oublie').then(fqLit)">Oublier</button>
      </div>
      <div class="petit" id="fq-etat"></div>
      <div class="petit" id="fq-res"></div>
    </div>
"""

    const val JS = """
// ------------------------------------------------------------------ Réglages: SatPC32 / Gpredict frequency files (FrequencesJs)
function fqExporte(f) { const a = document.createElement('a'); a.href = 'api/frequences/' + f + '?quoi=' + encodeURIComponent(v('fq-quoi')); a.download = ''; document.body.appendChild(a); a.click(); a.remove(); }
async function fqLit() {
  let o; try { o = await (await fetch('api/frequences')).json(); } catch (x) { return; }
  txt('fq-etat', o.n ? o.n + ' fréquence' + (o.n > 1 ? 's' : '') + ' importée' + (o.n > 1 ? 's' : '') + ' : ' + o.satellites.map(s => s.nom + ' (' + s.n + ')').join(', ')
    : 'Aucune fréquence importée. Elles s’ajoutent à la liste de leur satellite (Suivi › Fréquences), après celles de SatNOGS DB.');
}
async function fqImporte(inp) {
  const l = [];
  for (const f of inp.files) {
    let j; try { j = await (await fetch('api/frequences/import?nom=' + encodeURIComponent(f.name), { method: 'POST', body: f })).json(); } catch (x) { j = { ok: false, erreur: 'pas de réponse' }; }
    if (!j.ok) { l.push('<div>' + esc(f.name) + ' : ' + esc(j.erreur || 'refusé') + '</div>'); continue; }
    let h = '<div><b>' + esc(f.name) + '</b> : ' + j.n + ' fréquence' + (j.n > 1 ? 's' : '') + (j.satellites.length ? ' pour ' + j.satellites.map(s => esc(s.nom)).join(', ') : '');
    if (j.inconnus.length) h += ' · <span style="color:var(--ambre)">sans satellite trouvé : ' + j.inconnus.map(x => esc(x.nom) + (x.lignes > 1 ? ' (' + x.lignes + ')' : '')).join(', ') + '</span>';
    if (j.illisibles.length) h += ' · <span style="color:var(--ambre)">lignes illisibles : ' + j.illisibles.map(x => x.ligne).join(', ') + '</span>';
    l.push(h + '</div>');
  }
  g('fq-res').innerHTML = l.join(''); inp.value = ''; fqLit();
}
setInterval(() => { if (ouverte('reglages') && g('carteFrequences') && g('carteFrequences').offsetParent && !g('fq-etat').textContent) fqLit(); }, 2000);
"""
}
