/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.json.JSONObject

/**
 * The page's help for newcomers (SatMe-PC): the guided tours, the assistant
 * and the light interface. Put into [PagePc.HTML]; no `$` in the script (a
 * Kotlin template).
 *
 * - **Tours**: a coloured bubble on the real element (the rest dimmed), Back /
 *   Next / Skip and « 3 / 8 », typed boxes (what it is for, what to do now,
 *   tip, watch out, it worked if…). They are data: `guide-pc.json` beside
 *   this file, French and English, served at `api/guide` in the station's
 *   language. A quick tour proposed once (this browser), one tour per tab
 *   (« ? Guide » in the header), all of them in the help centre (« ? »).
 *   A tour only shows: it opens a tab, a settings category or a sub-tab
 *   (`stOuvre`), never clicks a button — nothing transmits, tunes or turns.
 * - **Assistant** (Réglages › Aide et affichage, off by default): the next
 *   useful thing, numbered on the page and in a small panel; it never does
 *   it in the operator's place.
 * - **Light interface** (same card, off by default): the explanation texts
 *   (class `aide-texte`) hidden; states, errors, warnings, values and
 *   buttons stay.
 *
 * Both options are kept by SatMe-PC (`api/interface`). Seen tours and the
 * welcome are kept in this browser (localStorage, in try/catch).
 */
object GuidePagePc {

    /** The tours, as written in `guide-pc.json` (both languages). */
    val SOURCE: JSONObject by lazy {
        JSONObject(GuidePagePc::class.java.getResourceAsStream("guide-pc.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** The texts of the boxes that a step may carry, in their order. */
    val ENCADRES = listOf("but", "faire", "astuce", "attention", "reussi")

    /** What a step may ask of the page: its element, its tab, its settings category, its sub-tab, its picture. */
    val CLES_ETAPE = setOf("cible", "titre", "texte", "onglet", "reglages", "pan", "image") + ENCADRES

    /** The pictures of the tours (WebP), beside this file in `guide/`. */
    fun image(nom: String): ByteArray? {
        if (!Regex("^[a-z0-9][a-z0-9-]*\\.webp$").matches(nom)) return null
        return GuidePagePc::class.java.getResourceAsStream("guide/$nom")?.use { it.readBytes() }
    }

    /** The tours in one language ([anglais]: English, else French): every {fr, en} pair made a string. */
    fun visites(anglais: Boolean): JSONObject {
        fun lang(x: Any?): Any? = when (x) {
            is JSONObject -> if (x.has("fr") && x.length() <= 2 && x.keySet().all { it == "fr" || it == "en" })
                (if (anglais) x.optString("en", x.optString("fr")) else x.optString("fr"))
            else JSONObject().also { o -> x.keySet().forEach { k -> o.put(k, lang(x.get(k))) } }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(lang(x.get(i))) }
            else -> x
        }
        return lang(SOURCE) as JSONObject
    }

    const val CSS = """
  /* ---------------------------------------------------------------- the guided tours: a bright bubble on the real element, the rest dimmed */
  .gd-boite { --gd-b:#5B8CFF; --gd-t:#A9C1FF; --gd-f:rgba(91,140,255,.13); }
  .gd-boite.gd-faire { --gd-b:#3FE0C8; --gd-t:#86F2E1; --gd-f:rgba(63,224,200,.12); }
  .gd-boite.gd-astuce { --gd-b:#B07CFF; --gd-t:#D6BCFF; --gd-f:rgba(176,124,255,.14); }
  .gd-boite.gd-attention { --gd-b:#FF5A36; --gd-t:#FFB4A3; --gd-f:rgba(255,90,54,.16); }
  .gd-boite.gd-reussi { --gd-b:#5BD68A; --gd-t:#9AECB8; --gd-f:rgba(91,214,138,.13); }
  .gd-boite { display:flex; gap:8px; align-items:flex-start; border:1px solid var(--gd-b); border-left-width:5px; background:var(--gd-f);
    border-radius:var(--r2); padding:6px 9px; margin:6px 0; font-size:var(--fs-s); line-height:1.4; color:var(--texte); }
  .gd-boite > svg { flex:none; width:20px; height:20px; margin-top:1px; fill:var(--gd-b); }
  .gd-boite b.gd-bt { display:block; color:var(--gd-t); font-size:var(--fs-xs); text-transform:uppercase; letter-spacing:.7px; margin-bottom:1px; }
  .gd-texte b, .gd-boite div b { color:var(--texte); }
  .gd-texte code, .gd-boite code { font-family:var(--mono); font-size:.92em; background:var(--creux); border:1px solid var(--ligne); border-radius:4px; padding:0 4px; }
  /* The spotlight: a ring around the element, everything else dimmed by a veil with a hole (both let clicks through). */
  #gd-voile { position:fixed; inset:0; z-index:1999; pointer-events:none; background:rgba(4,9,14,.62); }
  #gd-spot { position:fixed; z-index:2000; pointer-events:none; border-radius:10px; border:3px solid var(--gd-c, #FFD166);
    box-shadow:0 0 18px 4px var(--gd-c, #FFD166); transition:left .25s, top .25s, width .25s, height .25s; animation:gd-pouls 1.6s ease-in-out infinite; }
  #gd-spot.centre { display:none; }
  @keyframes gd-pouls { 50% { border-color:#fff; } }
  dialog.gd-bulle { position:fixed; z-index:2001; margin:0; inset:auto; padding:0; width:min(400px, calc(100vw - 16px)); max-height:calc(100vh - 16px);
    display:flex; flex-direction:column; overflow:hidden; border:2px solid var(--gd-c, #FFD166); border-radius:14px; background:var(--s2); color:var(--texte);
    box-shadow:0 18px 50px rgba(0,0,0,.55); }
  dialog.gd-bulle:not([open]) { display:none; }
  #gd-fleche { position:fixed; z-index:2000; width:18px; height:18px; background:var(--gd-c, #FFD166); transform:rotate(45deg); pointer-events:none; border-radius:3px; }
  #gd-fleche[hidden] { display:none; }
  .gd-tete { display:flex; align-items:center; gap:8px; padding:7px 10px; color:#0B1218; font-weight:800; font-size:var(--fs-s);
    background:var(--gd-c, #FFD166); background:linear-gradient(90deg, var(--gd-c, #FFD166), color-mix(in srgb, var(--gd-c, #FFD166) 55%, #fff)); }
  .gd-tete .gd-prog { margin-left:auto; font:700 var(--fs-xs) var(--mono); background:rgba(11,18,24,.18); border-radius:10px; padding:1px 8px; white-space:nowrap; }
  .gd-tete button { background:transparent; border:0; color:#0B1218; font-size:17px; line-height:1; padding:0 4px; min-height:0; }
  .gd-avance { height:4px; background:var(--creux); } .gd-avance i { display:block; height:100%; background:var(--gd-c, #FFD166); transition:width .25s; }
  .gd-corps { padding:8px 12px 4px; overflow:auto; min-height:0; }
  .gd-corps h3 { margin:0 0 4px; font-size:15px; color:var(--texte); font-weight:800; }
  .gd-texte { font-size:var(--fs-s); line-height:1.45; }
  .gd-corps img, .gd-page img { display:block; max-width:100%; height:auto; border-radius:8px; border:1px solid var(--ligne-forte); margin:6px 0; background:var(--creux); }
  .gd-absent { font-size:var(--fs-xs); color:var(--warn); margin-top:6px; }
  .gd-pied { display:flex; gap:6px; align-items:center; padding:7px 10px; border-top:1px solid var(--ligne); background:var(--s1); flex-wrap:wrap; }
  .gd-pied .gd-passer { background:none; border-color:transparent; color:var(--texte-3); }
  .gd-pied .esp { flex:1; }
  button.gd-primaire { background:var(--gd-c, var(--rx)); border-color:var(--gd-c, var(--rx)); color:#0B1218; font-weight:800; }
  button.gd-primaire:hover { filter:brightness(1.1); border-color:#fff; }
  /* « ? Guide » in the header: coloured until the tab's tour is seen. */
  .h-guide { cursor:pointer; color:#0B1218; background:var(--gd-c, var(--sat)); border-color:var(--gd-c, var(--sat)); font-weight:800; }
  .h-guide.vu { color:var(--texte-2); background:var(--s2); border-color:var(--ligne); font-weight:500; }
  .h-guide:hover { border-color:#fff; }
  /* The help centre: the tours on the left, one page with pictures on the right; each side scrolls on its own. */
  #gd-dlg { width:min(1080px, calc(100vw - 24px)); height:min(760px, calc(100vh - 40px)); padding:12px 14px; background:var(--s2); border:1px solid var(--ligne-forte); border-radius:12px; }
  #gd-dlg[open] { display:flex; flex-direction:column; }
  .gd-centre { flex:1; min-height:0; display:grid; grid-template-columns:240px minmax(0, 1fr); gap:12px; }
  #gd-liste { overflow:auto; display:flex; flex-direction:column; gap:3px; padding-right:4px; }
  #gd-liste .gd-groupe { font-size:10px; text-transform:uppercase; letter-spacing:.9px; color:var(--texte-3); margin:6px 0 1px; }
  #gd-liste button { display:flex; align-items:center; gap:6px; text-align:left; white-space:normal; border-left:4px solid var(--gd-c); padding:4px 7px; }
  #gd-liste button.actif { background:color-mix(in srgb, var(--gd-c) 18%, var(--s3)); border-color:var(--gd-c); color:var(--texte); }
  #gd-liste button i { margin-left:auto; font-style:normal; font-size:10px; color:var(--texte-3); white-space:nowrap; }
  #gd-liste button i.nv { color:var(--gd-c); font-weight:700; }
  .gd-page { overflow:auto; min-height:0; padding-right:6px; }
  .gd-page .gd-bandeau { display:flex; align-items:center; gap:10px; padding:10px 12px; border-radius:var(--r3); color:#0B1218;
    background:var(--gd-c); background:linear-gradient(90deg, var(--gd-c), color-mix(in srgb, var(--gd-c) 45%, #fff)); }
  .gd-page .gd-bandeau h3 { margin:0; font-size:18px; color:#0B1218; }
  .gd-page .gd-bandeau .esp { flex:1; }
  .gd-page .gd-bandeau button { background:#fff; border-color:#0B1218; color:#0B1218; font-weight:800; }
  .gd-page .gd-resume { font-size:var(--fs-m); line-height:1.5; margin:8px 0; }
  .gd-page ol { margin:6px 0; padding-left:0; list-style:none; counter-reset:gd; }
  .gd-page ol > li { counter-increment:gd; position:relative; padding:6px 0 6px 38px; border-top:1px solid var(--ligne); }
  .gd-page ol > li::before { content:counter(gd); position:absolute; left:2px; top:7px; width:26px; height:26px; border-radius:50%; display:flex; align-items:center; justify-content:center;
    font-weight:800; color:#0B1218; background:var(--gd-c); }
  .gd-page ol > li h4 { margin:2px 0 3px; font-size:var(--fs-m); }
  .gd-page figure { margin:8px 0; } .gd-page figure img { max-height:min(420px, 50vh); width:auto; }
  @media (max-width:700px) {
    .gd-centre { grid-template-columns:minmax(0, 1fr); grid-template-rows:auto minmax(0, 1fr); }
    #gd-liste { flex-direction:row; overflow-x:auto; } #gd-liste .gd-groupe { display:none; } #gd-liste button { flex:none; max-width:170px; } #gd-liste button i { display:none; }
  }
  /* The welcome: a bright card, once in this browser, replayed from the help centre. */
  #gd-bv-dlg { width:min(680px, calc(100vw - 24px)); max-height:calc(100vh - 40px); padding:0; overflow:auto; border:2px solid var(--rx); border-radius:14px; background:var(--s2); }
  .gd-bv-tete { padding:14px 18px 10px; color:#0B1218; background:linear-gradient(120deg, #3FE0C8, #6F8CFF 55%, #B07CFF); }
  .gd-bv-tete h2 { margin:0; font-size:24px; font-weight:900; } .gd-bv-tete h2 span { color:#fff; }
  .gd-bv-tete p { margin:4px 0 0; font-size:var(--fs-m); font-weight:600; }
  #gd-bv-boites { padding:6px 18px 0; }
  .gd-bv-pied { display:flex; gap:8px; align-items:center; flex-wrap:wrap; padding:10px 18px 14px; }
  .gd-bv-pied .esp { flex:1; }
  /* A connection that failed: what went wrong, what to do, the way to the settings. */
  #cx-dlg { width:min(520px, calc(100vw - 24px)); padding:0; overflow:hidden; border:2px solid var(--warn); border-radius:14px; background:var(--s2); }
  .cx-tete { display:flex; align-items:center; gap:10px; padding:10px 14px; color:#0B1218; font-weight:800; font-size:15px;
    background:linear-gradient(100deg, #FFB454, #FF7A45 60%, #FF5A36); }
  .cx-tete .cx-ico { font-size:22px; line-height:1; }
  .cx-corps { padding:8px 14px 2px; }
  .cx-raison { font-family:var(--mono); font-size:var(--fs-s); word-break:break-word; }
  .cx-pied { display:flex; gap:8px; justify-content:flex-end; padding:8px 14px 12px; }
  .cx-eclair { animation:cx-eclair 1.2s ease-in-out 3; }
  @keyframes cx-eclair { 50% { box-shadow:0 0 0 3px var(--warn), 0 0 22px var(--warn); } }
  /* The assistant: the next useful thing, numbered on the page and in a small panel (it never does it itself). */
  #as-panneau { position:fixed; right:14px; bottom:40px; z-index:60; width:300px; max-width:calc(100vw - 28px); border-radius:12px; overflow:hidden;
    background:var(--s2); border:2px solid #6F8CFF; box-shadow:var(--ombre-pop); font-size:var(--fs-s); }
  #as-panneau[hidden] { display:none; }
  .as-tete { display:flex; align-items:center; gap:8px; padding:5px 8px 5px 10px; color:#0B1218; font-weight:800; background:linear-gradient(90deg, #6F8CFF, #3FE0C8); }
  .as-tete span { flex:1; }
  .as-tete button { background:transparent; border:0; color:#0B1218; font-size:15px; padding:0 4px; min-height:0; }
  .as-sous { padding:5px 10px 0; color:var(--texte-2); }
  #as-liste { list-style:none; margin:0; padding:4px 6px 8px; }
  #as-liste li { display:flex; gap:8px; align-items:flex-start; padding:4px; border-radius:var(--r1); cursor:pointer; color:var(--texte-2); }
  #as-liste li:hover { background:var(--s3); }
  #as-liste li b.as-n { flex:none; width:20px; height:20px; border-radius:50%; display:grid; place-items:center; font-size:11px; color:#0B1218; background:var(--texte-3); }
  #as-liste li.prochain { color:var(--texte); font-weight:600; }
  #as-liste li.prochain b.as-n { background:#FFD166; box-shadow:0 0 0 3px rgba(255,209,102,.3); }
  #as-liste li.fait { text-decoration:line-through; opacity:.6; }
  #as-liste li.fait b.as-n { background:var(--ok); }
  .as-pastille { position:fixed; z-index:59; pointer-events:none; width:22px; height:22px; border-radius:50%; display:grid; place-items:center;
    font-weight:900; font-size:12px; color:#0B1218; background:#FFD166; border:2px solid #fff; box-shadow:0 0 12px #FFD166; animation:as-pouls 1.4s ease-in-out infinite; }
  .as-pastille.autre { background:#6F8CFF; box-shadow:0 0 8px #6F8CFF; animation:none; opacity:.85; }
  @keyframes as-pouls { 50% { transform:scale(1.18); } }
  .as-montre { outline:3px solid #FFD166 !important; outline-offset:2px; transition:outline-color .3s; }
  /* The light interface: the explanation texts hidden (states, errors, warnings, values and buttons stay). */
  body.allegee .aide-texte, body.allegee .reg-tete .petit, body.allegee #reg-nav small { display:none !important; }
"""

    /** « ? Guide » in the header: the tour of the tab shown. */
    const val ENTETE = """<button id="h-guide" class="h-puce h-guide" hidden onclick="gdOnglet()" title="Visite guidée de cet onglet">? Guide</button>"""

    /** Lines of the shortcuts' window (the « ? »): the tours. */
    const val AIDE = """<div><span class="h-puce h-guide vu" style="display:inline-flex">? Guide</span></div><div>La visite guidée de l'onglet affiché · <a href="#" onclick="event.preventDefault(); g('aide-dlg').close(); gdCentre()">toutes les visites</a> · <a href="#" onclick="event.preventDefault(); g('aide-dlg').close(); lanceVisite('tour')">le tour rapide</a></div>"""

    /** Réglages › Aide et affichage. */
    const val CARTE = """
    <div class="carte" id="gd-carte" hidden>
      <div class="t">Aide et affichage</div>
      <label style="display:block"><input type="checkbox" id="gd-assistant" onchange="gdOption('assistant', this.checked)"> <b id="gd-assistant-l">Assistant</b></label>
      <div class="petit aide-texte" id="gd-assistant-d" style="margin:2px 0 8px 22px"></div>
      <label style="display:block"><input type="checkbox" id="gd-allegee" onchange="gdOption('allegee', this.checked)"> <b id="gd-allegee-l">Interface allégée</b></label>
      <div class="petit aide-texte" id="gd-allegee-d" style="margin:2px 0 8px 22px"></div>
      <div class="ligne"><button class="primaire" id="gd-b-tour" onclick="lanceVisite('tour')">Tour rapide</button><button id="gd-b-centre" onclick="gdCentre()">Visites guidées…</button>
        <button class="discret" id="gd-b-oublie" onclick="gdOublie()">Revoir les visites comme neuves</button></div>
      <div class="etat" id="gd-msg"></div>
    </div>
"""

    /** The windows: the help centre, the welcome, a failed connection; the assistant's panel. */
    const val DIALOGUES = """
<dialog id="gd-dlg" aria-labelledby="gd-titre">
  <div class="dlg-tete"><b id="gd-titre">Visites guidées</b><span class="h-esp"></span><button id="gd-dlg-tour" class="primaire" onclick="g('gd-dlg').close(); lanceVisite('tour')" style="margin-right:8px">Tour rapide</button><button class="discret" onclick="g('gd-dlg').close()" aria-label="Fermer">✕</button></div>
  <div class="gd-centre"><nav id="gd-liste" aria-label="Visites"></nav><div class="gd-page" id="gd-page"></div></div>
</dialog>
<dialog id="gd-bv-dlg" aria-labelledby="gd-bv-titre">
  <div class="gd-bv-tete"><h2 id="gd-bv-titre">SatMe-<span>PC</span></h2><p id="gd-bv-sous"></p></div>
  <div id="gd-bv-boites"></div>
  <label class="petit" style="display:block;padding:4px 18px 0"><input type="checkbox" id="gd-bv-assistant" onchange="gdOption('assistant', this.checked)"> <span id="gd-bv-assistant-l"></span></label>
  <div class="gd-bv-pied"><button class="gd-primaire" id="gd-bv-visite" style="--gd-c:#3FE0C8" onclick="g('gd-bv-dlg').close(); lanceVisite('tour')"></button>
    <button id="gd-bv-centre" onclick="g('gd-bv-dlg').close(); gdCentre()"></button><span class="esp"></span><button class="discret" id="gd-bv-tard" onclick="g('gd-bv-dlg').close()"></button></div>
</dialog>
<dialog id="cx-dlg" aria-labelledby="cx-titre">
  <div class="cx-tete"><span class="cx-ico" aria-hidden="true">⚠</span><span id="cx-titre"></span></div>
  <div class="cx-corps" id="cx-corps"></div>
  <div class="cx-pied"><button class="discret" id="cx-annule" onclick="g('cx-dlg').close()">Annuler</button><button class="primaire" id="cx-reglages" onclick="cxReglages()">Aller aux réglages</button></div>
</dialog>
<div id="as-panneau" hidden role="complementary" aria-live="polite">
  <div class="as-tete"><span id="as-titre">Assistant</span><button onclick="asFerme()" id="as-x" aria-label="Fermer">✕</button></div>
  <div class="as-sous" id="as-sous"></div>
  <ol id="as-liste"></ol>
</div>
"""

    const val JS = """
// ------------------------------------------------------------------ guided tours, assistant, light interface (GuidePagePc)
// The tours only show: a tab (vue), a settings category (montreReglages), a sub-tab (stOuvre). They never click a button.
const GD = { d: null, v: null, i: 0, minuterie: 0, page: '', textes: {} };
const GD_TYPES = ['but', 'faire', 'astuce', 'attention', 'reussi'];
// The boxes' icons (24 × 24): a target, an arrow, a bulb, a warning triangle, a tick.
const GD_ICONES = {
  but: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 3a7 7 0 1 1 0 14 7 7 0 0 1 0-14zm0 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm0 2.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z',
  faire: 'M3 10.5h12.2L10.6 5.9 12.7 3.8 21 12l-8.3 8.2-2.1-2.1 4.6-4.6H3z',
  astuce: 'M9 21.5h6V20H9v1.5zM12 2a7 7 0 0 0-4 12.7V17a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1v-2.3A7 7 0 0 0 12 2z',
  attention: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V9h2v5z',
  reussi: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-2 15-5-5 1.4-1.4 3.6 3.6 7.6-7.6L19 8l-9 9z',
};
/** A text of Strings.kt, in the station's language (the key itself when SatMe-PC did not send it). */
const GT = k => GD.textes['pcg_' + k] || k;
const GTF = (k, ...a) => a.reduce((s, x, i) => s.split('{' + i + '}').join(x), GT(k));
const gdEsc = x => String(x == null ? '' : x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
/** The tours' little markup: **bold**, `code`; everything else escaped. */
const gdFmt = x => gdEsc(x).replace(/\*\*(.+?)\*\*/g, (m, a) => '<b>' + a + '</b>').replace(/`(.+?)`/g, (m, a) => '<code>' + a + '</code>');
function gdBoites(o) {
  return GD_TYPES.filter(t => o && o[t]).map(t => '<div class="gd-boite gd-' + t + '"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill-rule="evenodd" d="' +
    GD_ICONES[t] + '"/></svg><div><b class="gd-bt">' + gdEsc(GT('gd_' + t)) + '</b><div>' + gdFmt(o[t]) + '</div></div></div>').join('');
}
const gdImage = n => n && /^[a-z0-9][a-z0-9-]*\.webp/.test(n) ? '<img src="api/guide/image/' + n + '" alt="" loading="lazy">' : '';
const gdVisite = id => GD.d ? GD.d.visites.find(v => v.id === id) || null : null;
const gdLit = (k, d) => { try { const x = localStorage.getItem('satme.guide.' + k); return x == null ? d : x; } catch (e) { return d; } };
const gdGarde = (k, x) => { try { localStorage.setItem('satme.guide.' + k, x); } catch (e) {} };
function gdVus() { try { return new Set(JSON.parse(gdLit('vus', '[]'))); } catch (e) { return new Set(); } }
function gdMarqueVue(id) { const s = gdVus(); s.add(id); gdGarde('vus', JSON.stringify([...s])); gdBouton(); }
function gdOublie() { gdGarde('vus', '[]'); gdGarde('bienvenue', ''); gdBouton(); txt('gd-msg', GT('gd_oublie_fait')); }
/** The tab shown (its id without « v- »). */
const gdOngletVu = () => { const x = document.querySelector('.vue:not([hidden])'); return x ? x.id.slice(2) : 'station'; };

// ------------------------------------------------------------------ the bubble, the spotlight, the arrow
const gdVoile = document.createElement('div'); gdVoile.id = 'gd-voile';
const gdSpot = document.createElement('div'); gdSpot.id = 'gd-spot';
const gdFleche = document.createElement('div'); gdFleche.id = 'gd-fleche';
const gdBulle = document.createElement('dialog'); gdBulle.id = 'gd-bulle'; gdBulle.className = 'gd-bulle'; gdBulle.setAttribute('aria-live', 'polite');

/** Starts tour [id] (at step [depart]). */
function lanceVisite(id, depart) {
  const v = gdVisite(id);
  if (!v || !v.etapes || !v.etapes.length) return;
  if (GD.v) fermeVisite(false);
  for (const d of ['gd-dlg', 'gd-bv-dlg', 'aide-dlg']) { const x = g(d); if (x && x.open) x.close(); }
  if (typeof popCache === 'function') popCache();
  GD.v = v; GD.i = Math.max(0, Math.min(v.etapes.length - 1, depart || 0));
  gdEtape();
  clearInterval(GD.minuterie);
  GD.minuterie = setInterval(gdPlace, 400);
}
/** Ends the tour (seen, unless [vue] is false). */
function fermeVisite(vue) {
  if (!GD.v) return;
  const v = GD.v;
  GD.v = null;
  clearInterval(GD.minuterie);
  if (gdBulle.open) gdBulle.close();
  gdVoile.remove(); gdSpot.remove(); gdFleche.remove(); gdBulle.remove();
  if (vue !== false) gdMarqueVue(v.id);
}
/** What step [e] needs on the screen: its tab, its settings category, its sub-tab (only those: nothing is clicked). */
function gdPrepare(e) {
  const o = e.onglet || (e.reglages ? 'reglages' : GD.v.onglet);
  if (o && gdOngletVu() !== o && g('v-' + o)) vue(o);
  if (e.reglages && typeof montreReglages === 'function') montreReglages(e.reglages);
  const p = e.pan && g(e.pan), st = p && p.parentElement;
  if (p && st && st.classList.contains('st') && typeof stOuvre === 'function') stOuvre(st, e.pan);
}
/** The element of step [e]: an id, or « v:tab » for the tab's entry in the menu; null when not on the screen. */
function gdElement(cible) {
  if (!cible) return null;
  if (cible.indexOf('v:') === 0) { const o = cible.slice(2); return document.querySelector('#onglets [data-v="' + o + '"], #onglets a[href="' + o + '"]'); }
  return g(cible);
}
function gdCible(e) {
  const c = e && gdElement(e.cible);
  if (!c || c.closest('[hidden]')) return null;
  const r = c.getBoundingClientRect();
  return r.width > 0 && r.height > 0 ? c : null;
}
/** Brings [c] into its scrolling frames (never the page itself: the tabs do not scroll). */
function gdDefile(c) {
  for (let p = c.parentElement; p && p !== document.body; p = p.parentElement) {
    const s = getComputedStyle(p);
    if (!/(auto|scroll)/.test(s.overflowY + s.overflowX) || (p.scrollHeight <= p.clientHeight && p.scrollWidth <= p.clientWidth)) continue;
    const r = c.getBoundingClientRect(), q = p.getBoundingClientRect();
    if (r.top < q.top) p.scrollTop -= q.top - r.top + 8;
    else if (r.bottom > q.bottom) p.scrollTop += Math.max(0, Math.min(r.bottom - q.bottom + 8, r.top - q.top - 8));
  }
}
function gdEtape() {
  const v = GD.v;
  if (!v) return;
  const n = v.etapes.length, e = v.etapes[GD.i], fin = GD.i === n - 1;
  gdPrepare(e);
  if (gdBulle.parentNode !== document.body) document.body.append(gdVoile, gdSpot, gdFleche, gdBulle);
  for (const x of [gdSpot, gdFleche, gdBulle]) x.style.setProperty('--gd-c', v.couleur || '#FFD166');
  const cible = gdCible(e);
  gdBulle.innerHTML = '<div class="gd-tete"><span>' + gdEsc(v.titre) + '</span><span class="gd-prog">' + (GD.i + 1) + ' / ' + n + '</span>' +
    '<button data-gd="x" title="' + gdEsc(GT('gd_fermer')) + '" aria-label="' + gdEsc(GT('gd_fermer')) + '">×</button></div>' +
    '<div class="gd-avance"><i style="width:' + Math.round((GD.i + 1) / n * 100) + '%"></i></div>' +
    '<div class="gd-corps"><h3>' + gdEsc(e.titre) + '</h3><div class="gd-texte">' + gdFmt(e.texte) + '</div>' + gdBoites(e) + gdImage(e.image) +
    (e.cible && !cible ? '<div class="gd-absent">' + gdEsc(GT('gd_absent')) + '</div>' : '') + '</div>' +
    '<div class="gd-pied"><button class="gd-passer" data-gd="passer">' + gdEsc(GT('gd_passer')) + '</button><span class="esp"></span>' +
    (GD.i ? '<button data-gd="prec">' + gdEsc(GT('gd_precedent')) + '</button>' : '') +
    '<button class="gd-primaire" data-gd="suiv">' + gdEsc(GT(fin ? 'gd_terminer' : 'gd_suivant')) + '</button></div>';
  gdBulle.querySelector('[data-gd=x]').onclick = () => fermeVisite();
  gdBulle.querySelector('[data-gd=passer]').onclick = () => fermeVisite();
  if (GD.i) gdBulle.querySelector('[data-gd=prec]').onclick = () => { GD.i--; gdEtape(); };
  gdBulle.querySelector('[data-gd=suiv]').onclick = () => { if (fin) fermeVisite(); else { GD.i++; gdEtape(); } };
  for (const im of gdBulle.querySelectorAll('img')) im.onload = gdPlace;
  if (!gdBulle.open) gdBulle.show();
  if (cible) gdDefile(cible);
  gdPlace();
  try { gdBulle.querySelector('[data-gd=suiv]').focus({ preventScroll: true }); } catch (x) {}
}
/** The spotlight on the element, the bubble beside it (below, above, right, left: the first that fits), the arrow between. */
function gdPlace() {
  if (!GD.v || !gdBulle.open) return;
  const c = gdCible(GD.v.etapes[GD.i]);
  const W = document.documentElement.clientWidth || innerWidth, H = document.documentElement.clientHeight || innerHeight, m = 8, gp = 16, a = 9;
  const bw = gdBulle.offsetWidth, bh = gdBulle.offsetHeight;
  const px = (el, o) => { for (const k in o) el.style[k] = Math.round(o[k]) + 'px'; };
  if (!c) {
    gdSpot.className = 'centre'; gdVoile.style.clipPath = '';
    gdFleche.hidden = true;
    px(gdBulle, { left: Math.max(m, (W - bw) / 2), top: Math.max(m, (H - bh) / 2) });
    return;
  }
  gdSpot.className = '';
  const r = c.getBoundingClientRect(), p = 5;
  const x0 = Math.max(3, r.left - p), y0 = Math.max(3, r.top - p), x1 = Math.min(W - 3, r.right + p), y1 = Math.min(H - 3, r.bottom + p);
  px(gdSpot, { left: x0, top: y0, width: Math.max(0, x1 - x0), height: Math.max(0, y1 - y0) });
  gdVoile.style.clipPath = 'polygon(evenodd, 0 0, ' + W + 'px 0, ' + W + 'px ' + H + 'px, 0 ' + H + 'px, 0 0, ' +
    x0 + 'px ' + y0 + 'px, ' + x1 + 'px ' + y0 + 'px, ' + x1 + 'px ' + y1 + 'px, ' + x0 + 'px ' + y1 + 'px, ' + x0 + 'px ' + y0 + 'px)';
  const ou = [['bas', y1 + gp + bh <= H - m], ['haut', y0 - gp - bh >= m], ['droite', x1 + gp + bw <= W - m], ['gauche', x0 - gp - bw >= m]].find(x => x[1]);
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, borne = (x, a0, a1) => Math.max(a0, Math.min(a1, x));
  let L, T;
  if (!ou) { L = x1 - bw - 14; T = y1 - bh - 14; }
  else if (ou[0] === 'bas' || ou[0] === 'haut') { L = cx - bw / 2; T = ou[0] === 'bas' ? y1 + gp : y0 - gp - bh; }
  else { T = cy - bh / 2; L = ou[0] === 'droite' ? x1 + gp : x0 - gp - bw; }
  L = borne(L, m, W - bw - m); T = borne(T, m, H - bh - m);
  px(gdBulle, { left: L, top: T });
  gdFleche.hidden = !ou;
  if (!ou) return;
  if (ou[0] === 'bas') px(gdFleche, { left: borne(cx, L + 20, L + bw - 20) - a, top: T - a });
  else if (ou[0] === 'haut') px(gdFleche, { left: borne(cx, L + 20, L + bw - 20) - a, top: T + bh - a });
  else if (ou[0] === 'droite') px(gdFleche, { left: L - a, top: borne(cy, T + 20, T + bh - 20) - a });
  else px(gdFleche, { left: L + bw - a, top: borne(cy, T + 20, T + bh - 20) - a });
}
addEventListener('resize', () => { if (GD.v) gdPlace(); });

// ------------------------------------------------------------------ « ? Guide » in the header: the tab's tour
function gdBouton() {
  const b = g('h-guide'); if (!b) return;
  const v = GD.d && gdVisite(gdOngletVu());
  b.hidden = !v;
  if (!v) return;
  b.textContent = GT('gd_guide');
  b.title = GT('gd_guide_titre') + ' — ' + v.titre;
  b.classList.toggle('vu', gdVus().has(v.id));
  b.style.setProperty('--gd-c', v.couleur || '');
}
function gdOnglet() { const v = gdVisite(gdOngletVu()); if (v) lanceVisite(v.id); else gdCentre(); }

// ------------------------------------------------------------------ the help centre: every tour, one page each
function gdCentre(id) {
  if (!GD.d) return;
  const vus = gdVus();
  if (id && gdVisite(id)) GD.page = id;
  if (!gdVisite(GD.page)) GD.page = gdVisite(gdOngletVu()) ? gdOngletVu() : 'tour';
  const bouton = v => '<button data-v="' + gdEsc(v.id) + '" style="--gd-c:' + gdEsc(v.couleur || '') + '" class="' + (v.id === GD.page ? 'actif' : '') + '">' +
    '<span>' + gdEsc(v.titre) + '</span><i class="' + (vus.has(v.id) ? '' : 'nv') + '">' + gdEsc(GT(vus.has(v.id) ? 'gd_vu' : 'gd_nouveau')) + '</i></button>';
  g('gd-liste').innerHTML = GD.d.groupes.map(gr => {
    const l = GD.d.visites.filter(v => v.groupe === gr.id && (v.id === 'tour' || !v.onglet || g('v-' + v.onglet)));
    return l.length ? '<div class="gd-groupe">' + gdEsc(gr.titre) + '</div>' + l.map(bouton).join('') : '';
  }).join('');
  for (const x of g('gd-liste').querySelectorAll('[data-v]')) x.onclick = () => {
    GD.page = x.dataset.v;
    for (const y of g('gd-liste').querySelectorAll('[data-v]')) y.classList.toggle('actif', y === x);
    gdPage(GD.page);
  };
  gdPage(GD.page);
  if (!g('gd-dlg').open) g('gd-dlg').showModal();
}
/** One tour's page: its summary, every step with its boxes and pictures, and the button that starts it. */
function gdPage(id) {
  const v = gdVisite(id), z = g('gd-page');
  if (!v) { z.innerHTML = ''; return; }
  z.style.setProperty('--gd-c', v.couleur || '#FFD166');
  z.innerHTML = '<div class="gd-bandeau"><h3>' + gdEsc(v.titre) + '</h3><span class="esp"></span><button data-lance>' + gdEsc(GT('gd_lancer')) + '</button></div>' +
    '<p class="gd-resume">' + gdFmt(v.resume) + '</p>' + (v.image ? '<figure>' + gdImage(v.image) + '</figure>' : '') +
    '<ol>' + v.etapes.map(e => '<li><h4>' + gdEsc(e.titre) + '</h4><div class="gd-texte">' + gdFmt(e.texte) + '</div>' + gdBoites(e) +
      (e.image && e.image !== v.image ? gdImage(e.image) : '') + '</li>').join('') + '</ol>';
  z.querySelector('[data-lance]').onclick = () => lanceVisite(v.id);
  z.scrollTop = 0;
}

// ------------------------------------------------------------------ the welcome: once in this browser, then from the help
function gdBienvenue() {
  if (!GD.d) return;
  txt('gd-bv-sous', GT('gd_bienvenue_sous'));
  g('gd-bv-boites').innerHTML = gdBoites({ but: GT('gd_bienvenue_but'), attention: GT('gd_bienvenue_attention'), astuce: GT('gd_bienvenue_astuce') });
  txt('gd-bv-assistant-l', GT('gd_bienvenue_assistant'));
  g('gd-bv-assistant').checked = !!GD.d.assistant;
  txt('gd-bv-visite', GT('gd_bienvenue_visite')); txt('gd-bv-centre', GT('gd_bienvenue_centre')); txt('gd-bv-tard', GT('gd_bienvenue_tard'));
  if (!g('gd-bv-dlg').open) g('gd-bv-dlg').showModal();
}
g('gd-bv-dlg').addEventListener('close', () => gdGarde('bienvenue', 'vue'));
/** The first start in this browser: the welcome, never over another window (quick setup, what's new…). */
function gdBienvenuePlusTard(n) {
  if (gdLit('bienvenue', '') === 'vue' || /[?&]bienvenue=non\b/.test(location.search) || n > 60) return;
  if (document.querySelector('dialog[open]') || GD.v) { setTimeout(() => gdBienvenuePlusTard(n + 1), 3000); return; }
  gdBienvenue();
}

// ------------------------------------------------------------------ the two options (kept by SatMe-PC)
function gdApplique() {
  const d = GD.d || {};
  document.body.classList.toggle('allegee', !!d.allegee);
  gdGarde('allegee', d.allegee ? '1' : '0');
  if (document.activeElement !== g('gd-assistant')) g('gd-assistant').checked = !!d.assistant;
  if (document.activeElement !== g('gd-allegee')) g('gd-allegee').checked = !!d.allegee;
  asTic();
}
async function gdOption(quoi, on) {
  try {
    const j = await (await fetch('api/interface?' + quoi + '=' + (on ? 1 : 0), { method: 'POST' })).json();
    if (GD.d && j && j.ok) { GD.d.assistant = !!j.assistant; GD.d.allegee = !!j.allegee; }
  } catch (x) {}
  if (quoi === 'assistant' && on) { try { sessionStorage.removeItem('satme.assistant.ferme'); } catch (x) {} }
  gdApplique();
}
// Before the answer: the light interface as this browser saw it last (no flash of the hidden texts).
if (gdLit('allegee', '0') === '1') document.body.classList.add('allegee');
async function gdCharge() {
  if (window.SATME_TELEPHONE) return;
  try { const r = await fetch('api/guide'); GD.d = r.ok ? await r.json() : null; } catch (x) { GD.d = null; }
  if (!GD.d || !GD.d.ok) { GD.d = null; document.body.classList.remove('allegee'); return; }
  GD.textes = GD.d.textes || {};
  g('gd-carte').hidden = false;
  txt('gd-assistant-l', GT('gd_assistant')); txt('gd-assistant-d', GT('gd_assistant_aide'));
  txt('gd-allegee-l', GT('gd_allegee')); txt('gd-allegee-d', GT('gd_allegee_aide'));
  txt('gd-b-tour', GT('gd_tour')); txt('gd-b-centre', GT('gd_centre')); txt('gd-b-oublie', GT('gd_oublier'));
  txt('gd-titre', GT('gd_centre_titre')); txt('gd-dlg-tour', GT('gd_tour'));
  txt('cx-annule', GT('cx_annuler')); txt('cx-reglages', GT('cx_reglages'));
  txt('as-titre', GT('as_titre'));
  if (typeof regNavMaj === 'function') regNavMaj();
  gdApplique(); gdBouton();
  setTimeout(() => gdBienvenuePlusTard(0), 1500);
}
setInterval(gdBouton, 1000);

// ------------------------------------------------------------------ the assistant: the next useful thing, shown (never done)
const AS = { vu: '' };
const asFermeLu = () => { try { return sessionStorage.getItem('satme.assistant.ferme') === '1'; } catch (x) { return false; } };
function asFerme() { try { sessionStorage.setItem('satme.assistant.ferme', '1'); } catch (x) {} asTic(); }
/** The steps before and during a pass, from the station's state: each with its element (on the Suivi tab, or the status bar). */
function asEtapes() {
  if (!E) return [];
  const suivi = gdOngletVu() === 'station', c = E.cat || {}, r = E.rotor || {};
  const l = [
    { cle: 'sat', fait: !!E.suivi, texte: GT('as_sat'), cible: 'cherche', onglet: 'station' },
    { cle: 'poste', fait: !!c.connecte, texte: GT('as_poste'), cible: suivi ? 'st-poste-ok' : 'sb-cat', onglet: null },
    { cle: 'rotor', fait: !!r.connecte, texte: GT('as_rotor'), cible: suivi ? 'st-rotor-ok' : 'sb-rot', onglet: null, facultatif: true },
  ];
  if (r.connecte) l.push({ cle: 'suivre', fait: !!r.suit, texte: GT('as_suivre'), cible: 'rot-suit', reglages: 'materiel' });
  const son = g('h-son');
  l.push({ cle: 'ecoute', fait: !!(son && son.classList.contains('on')), texte: GT('as_ecoute'), cible: 'h-son', onglet: null });
  return l;
}
function asTic() {
  const p = g('as-panneau');
  document.querySelectorAll('.as-pastille').forEach(x => x.remove());
  const on = !!(GD.d && GD.d.assistant) && !asFermeLu() && !window.SATME_TELEPHONE && !GD.v;
  p.hidden = !on;
  if (!on) return;
  const l = asEtapes(), proch = l.find(x => !x.fait && !x.facultatif) || l.find(x => !x.fait);
  const pa = E && E.passage, mtn = Date.now();
  let sous = '';
  if (E && E.passageEnCours) sous = GTF('as_en_cours', (pa && pa.nom) || '');
  else if (pa && pa.aos > mtn) sous = GTF('as_bientot', pa.nom || '', Math.max(0, Math.round((pa.aos - mtn) / 60000)));
  if (!proch) sous = (sous ? sous + ' · ' : '') + GT('as_pret');
  txt('as-sous', sous);
  const cle = JSON.stringify(l.map(x => [x.cle, x.fait, x.texte])) + (proch ? proch.cle : '');
  if (cle !== AS.vu) {
    AS.vu = cle;
    g('as-liste').innerHTML = l.map((x, i) => '<li data-i="' + i + '" class="' + (x.fait ? 'fait' : x === proch ? 'prochain' : '') + '"><b class="as-n">' + (x.fait ? '✓' : i + 1) + '</b><span>' +
      gdEsc(x.texte) + (x.facultatif && !x.fait ? ' <span class="petit">' + gdEsc(GT('as_facultatif')) + '</span>' : '') + '</span></li>').join('');
    g('as-liste').querySelectorAll('li').forEach(li => li.onclick = () => asMontre(asEtapes()[+li.dataset.i]));
  }
  // The numbers on the page itself: the next one bright, the others quieter; only where the element is on the screen.
  l.forEach((x, i) => {
    if (x.fait) return;
    const el = g(x.cible); if (!el || el.closest('[hidden]')) return;
    const r = el.getBoundingClientRect(); if (!r.width || !r.height) return;
    const b = document.createElement('div'); b.className = 'as-pastille' + (x === proch ? '' : ' autre'); b.textContent = i + 1;
    // At the element's top right corner: its text stays readable.
    b.style.left = Math.max(2, Math.min(innerWidth - 24, r.right - 8)) + 'px'; b.style.top = Math.max(2, r.top - 12) + 'px';
    document.body.appendChild(b);
  });
}
/** A line of the panel clicked: its tab or settings shown and the element outlined; nothing else is done. */
function asMontre(x) {
  if (!x) return;
  if (x.onglet && gdOngletVu() !== x.onglet) vue(x.onglet);
  if (x.reglages) { vue('reglages'); montreReglages(x.reglages); }
  const el = g(x.cible); if (!el) return;
  gdDefile(el);
  el.classList.add('as-montre'); setTimeout(() => el.classList.remove('as-montre'), 2500);
  setTimeout(asTic, 50);
}
setInterval(asTic, 1000);
gdCharge();
"""
}
