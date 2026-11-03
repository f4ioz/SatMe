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
 * SatMe-PC's appearance, as TellusMe's: put into [PagePc.HTML]; no `$` in the
 * script (a Kotlin template).
 *
 * - **Two interfaces**: simple (what a pass needs: the satellite, its passes,
 *   the rig, the rotor, the sound, what is decoded) and complete (everything).
 *   `html[data-ui="simple"]` hides what is marked `ui-c` (complete only) and
 *   shows what is marked `ui-s` (simple only); each tab's tiles have their own
 *   layout in each interface.
 * - **Themes** ([THEMES], shared with TellusMe): palettes (`data-theme`), the
 *   corners (`data-forme`) and the density (`data-densite`), on any element —
 *   the settings' swatches preview themselves. Only the design tokens of
 *   [PagePc.STYLE_JETONS] change: the transmit colours stay red-orange.
 * - **Tiles**: the cards of a tab (the Pli of [PagePc]) moved by their grip,
 *   removed, added back, and « Disposition par défaut ».
 *
 * Everything is kept by SatMe-PC (`api/apparence`); this browser keeps a copy
 * so the page is drawn right before the answer. The texts are
 * `apparence-pc.json` (French and English), as the guided tours.
 */
object ApparencePagePc {

    /** The palettes, the dark one first (the page's own); « auto » follows the system (dark or light). */
    val THEMES_NOMS = listOf("sombre", "clair", "aurore", "phosphore", "sable", "auto")
    val FORMES = listOf("arrondi", "carre", "galet")
    val DENSITES = listOf("compacte", "normale", "aeree")
    val INTERFACES = listOf("simple", "complete")

    /**
     * The tabs of the complete interface only: the simple one keeps what a pass needs (Suivi, Agenda, the decoders' tabs,
     * QO-100, DATV, METEOR, the log, the images, Son, SDR, Réglages). A tour opens them all the same.
     */
    val ONGLETS_COMPLETS = setOf("frise", "sked", "carres", "carte", "orbites", "avions", "ais", "espace", "sondes", "montage", "noeud", "journal", "planche")

    /** Each setting, its values (the first is the default). */
    val CHOIX: Map<String, List<String>> = mapOf("theme" to THEMES_NOMS, "forme" to FORMES, "densite" to listOf("normale", "compacte", "aeree"),
        "interface" to listOf("complete", "simple"))

    /** The default of setting [cle]. */
    fun defaut(cle: String): String = CHOIX.getValue(cle).first()

    /** [v] if it is one of setting [cle]'s values, else null. */
    fun valeur(cle: String, v: String?): String? = v?.takeIf { CHOIX[cle]?.contains(it) == true }

    /** A tab's layout key, as the page names it (« pli-station », « pli-station.simple »). */
    val CLE_DISPOSITION = Regex("^pli-[a-z0-9-]{1,30}(\\.simple)?$")
    private val CLE_TUILE = Regex("^[a-z0-9-]{1,32}$")
    private val PARTIES = setOf("plie", "compact", "cache", "fr", "h", "ordre", "retire", "avant")

    /**
     * A layout sent by the page, checked: an object of at most 8 000 characters
     * whose parts are the Pli's (folded, compact, hidden columns, widths,
     * heights, the tiles' order per column, the tiles removed), keyed by short
     * names; null when it is not one.
     */
    fun disposition(json: String?): JSONObject? {
        if (json == null || json.length > 8000) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        for (k in o.keySet()) {
            if (k !in PARTIES) return null
            val p = o.optJSONObject(k) ?: return null
            for (c in p.keySet()) {
                if (!CLE_TUILE.matches(c)) return null
                when (val x = p.get(c)) {
                    is JSONArray -> if (x.length() > 40 || (0 until x.length()).any { !CLE_TUILE.matches(x.optString(it)) }) return null
                    is Number, is Boolean -> {}
                    else -> return null
                }
            }
        }
        return o
    }

    /** The texts, as written in `apparence-pc.json` (both languages). */
    val SOURCE: JSONObject by lazy {
        JSONObject(ApparencePagePc::class.java.getResourceAsStream("apparence-pc.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /** The texts in one language ([anglais]: English, else French). */
    fun textes(anglais: Boolean): JSONObject {
        val t = SOURCE.getJSONObject("textes")
        return JSONObject().also { o -> t.keySet().forEach { k -> t.getJSONObject(k).let { o.put(k, if (anglais) it.optString("en", it.optString("fr")) else it.optString("fr")) } } }
    }

    /**
     * The palettes, the corners and the densities: the tokens of [PagePc.STYLE_JETONS] redefined on any element marked
     * `data-theme` (`html.clair` too: TellusMe's light theme), `data-forme`, `data-densite`. Put after the tokens.
     * In every palette: rx (reception) a cool colour, tx (transmission) red-orange, danger red, warn amber; sur-vif is
     * the text on a bright fill (a primary button, a chip).
     */
    const val THEMES = """
  /* ---------------------------------------------------------------- palettes (the dark one is :root's) */
  [data-theme="sombre"] { color-scheme:dark;
    --s0:#0A0F14; --s1:#0F161D; --s2:#131C24; --s3:#1A2530; --creux:#0D151C; --ligne:#22303B; --ligne-forte:#2F4150;
    --texte:#E8F0F7; --texte-2:#9AA7B4; --texte-3:#6B7C8F;
    --ok:#5BD68A; --warn:#FFB454; --danger:#E5484D; --info:#6F8CFF; --rx:#3FE0C8; --tx:#FF5A36; --mark:#B07CFF; --sat:#FFD166;
    --sur-vif:#06131A; --survol:#21303C; --voile:rgba(4,8,12,.62);
    --ombre:0 1px 0 rgba(255,255,255,.03) inset, 0 6px 18px rgba(0,0,0,.28); --ombre-pop:0 14px 40px rgba(0,0,0,.55); }
  [data-theme="clair"], html.clair { color-scheme:light;
    --s0:#E8EDF1; --s1:#F3F6F8; --s2:#FFFFFF; --s3:#E2E8ED; --creux:#EDF1F4; --ligne:#D2DBE2; --ligne-forte:#B6C3CE;
    --texte:#13202A; --texte-2:#485967; --texte-3:#748697;
    --ok:#1C9A54; --warn:#B26A00; --danger:#D23B40; --info:#3A57D4; --rx:#0A8B7A; --tx:#DD4419; --mark:#7646D2; --sat:#9E7100;
    --sur-vif:#FFFFFF; --survol:#D7E0E7; --voile:rgba(19,32,42,.45);
    --ombre:0 1px 2px rgba(19,32,42,.08), 0 4px 14px rgba(19,32,42,.07); --ombre-pop:0 14px 40px rgba(19,32,42,.25); }
  /* Aurora: a deep violet night, mint reception, pink-violet marks. */
  [data-theme="aurore"] { color-scheme:dark;
    --s0:#100C22; --s1:#16112E; --s2:#1C163B; --s3:#272052; --creux:#130E29; --ligne:#2E2658; --ligne-forte:#43397D;
    --texte:#F2EEFF; --texte-2:#B7ACDE; --texte-3:#8478B4;
    --ok:#6BE39A; --warn:#FFC15E; --danger:#FF4F6D; --info:#8FA2FF; --rx:#5CF2D6; --tx:#F2542D; --mark:#E07CFF; --sat:#FFD86B;
    --sur-vif:#100C22; --survol:#30286A; --voile:rgba(10,6,24,.66);
    --ombre:0 1px 0 rgba(255,255,255,.04) inset, 0 8px 22px rgba(6,2,20,.45); --ombre-pop:0 14px 40px rgba(6,2,20,.65); }
  /* Phosphor: an old green terminal; the transmission stays orange-red. */
  [data-theme="phosphore"] { color-scheme:dark;
    --s0:#040A05; --s1:#071108; --s2:#0B180D; --s3:#122516; --creux:#060E07; --ligne:#1A3220; --ligne-forte:#28492F;
    --texte:#D2FFDB; --texte-2:#86C994; --texte-3:#55905F;
    --ok:#7CFF8E; --warn:#FFD54A; --danger:#FF4D4D; --info:#6FD3FF; --rx:#39FF88; --tx:#F05A28; --mark:#C6FF4D; --sat:#FFE066;
    --sur-vif:#031A0A; --survol:#183520; --voile:rgba(2,8,3,.7);
    --ombre:0 0 0 1px rgba(57,255,136,.05) inset, 0 6px 18px rgba(0,0,0,.4); --ombre-pop:0 14px 40px rgba(0,0,0,.6);
    --mono:'VT323','Cascadia Mono','DejaVu Sans Mono',ui-monospace,monospace; }
  /* Sand: a warm light page, paper and ink. */
  [data-theme="sable"] { color-scheme:light;
    --s0:#EEE6D8; --s1:#F6F0E5; --s2:#FFFCF5; --s3:#E7DDCB; --creux:#F3EBDD; --ligne:#DACDB7; --ligne-forte:#C2AF93;
    --texte:#2A2118; --texte-2:#67574A; --texte-3:#8C7B68;
    --ok:#2E8B4A; --warn:#B1610A; --danger:#C8303A; --info:#3456C8; --rx:#0F7C84; --tx:#D9480F; --mark:#8A3FC4; --sat:#9C7000;
    --sur-vif:#FFFFFF; --survol:#E0D4BF; --voile:rgba(42,33,24,.42);
    --ombre:0 1px 2px rgba(60,40,20,.08), 0 4px 14px rgba(60,40,20,.08); --ombre-pop:0 14px 40px rgba(60,40,20,.25); }
  /* ---------------------------------------------------------------- corners */
  [data-forme="carre"] { --r1:2px; --r2:2px; --r3:3px; --r4:4px; }
  [data-forme="galet"] { --r1:10px; --r2:13px; --r3:18px; --r4:22px; }
  /* ---------------------------------------------------------------- densities */
  [data-densite="compacte"] { --sp2:6px; --sp3:8px; --sp4:10px; --pad-carte:6px 9px; --gap-carte:6px; --fs-m:12.5px; --fs-s:11.5px; }
  [data-densite="aeree"] { --sp2:10px; --sp3:16px; --sp4:20px; --pad-carte:14px 16px; --gap-carte:14px; }
"""

    /** The page's part: the light themes' fixes, the tiles, the two interfaces, the settings' swatches, the « À l'écoute » tile. */
    const val CSS = """
  /* ---------------------------------------------------------------- appearance (ApparencePagePc) */
  html, body { background:var(--s0); }
  /* A touch of colour: the name, the cards' titles. */
  #tete h1 { background:linear-gradient(90deg, var(--texte) 0 62%, var(--rx) 62%); -webkit-background-clip:text; background-clip:text; }
  #tete h1 .cyan, #tete h1 .c { background:linear-gradient(90deg, var(--rx), var(--info) 60%, var(--mark)); -webkit-background-clip:text; background-clip:text; color:transparent; }
  .vue .carte > .t:first-child::before { content:''; display:inline-block; flex:none; width:4px; height:11px; margin-right:7px; border-radius:2px; vertical-align:-1px;
    background:linear-gradient(180deg, var(--rx), var(--info)); }
  .vue .carte { transition:border-color var(--anim), box-shadow var(--anim); }
  .vue .carte:hover { border-color:color-mix(in srgb, var(--rx) 22%, var(--ligne)); }
  /* The states, the same everywhere: reception, transmission, error, warning. */
  .etat-rx { color:var(--rx); } .etat-tx { color:var(--tx); font-weight:800; } .etat-err { color:var(--danger); font-weight:700; } .etat-att { color:var(--warn); }
  /* The light palettes: what was drawn white on dark. */
  [data-theme="clair"] .primaire, [data-theme="sable"] .primaire { color:var(--sur-vif); }
  [data-theme="clair"] #tete, [data-theme="sable"] #tete, [data-theme="clair"] #sb, [data-theme="sable"] #sb { box-shadow:0 1px 3px rgba(0,0,0,.06); }
  [data-theme="clair"] .gd-bulle, [data-theme="sable"] .gd-bulle { box-shadow:0 18px 50px rgba(0,0,0,.25); }
  /* Phosphor: the values glow a little. */
  [data-theme="phosphore"] .val, [data-theme="phosphore"] .gros, [data-theme="phosphore"] #heure { text-shadow:0 0 6px color-mix(in srgb, var(--rx) 55%, transparent); }
  [data-theme="aurore"] #tete { background:linear-gradient(90deg, var(--s1), color-mix(in srgb, var(--mark) 10%, var(--s1)) 60%, color-mix(in srgb, var(--rx) 8%, var(--s1))); }
  /* The transmission stays red-orange and loud in every palette. */
  .h-tx, #h-tx { background:var(--tx) !important; color:#fff !important; border-color:var(--tx) !important; }

  /* ---------------------------------------------------------------- the two interfaces */
  [data-ui="simple"] .ui-c { display:none !important; }
  html:not([data-ui="simple"]) .ui-s { display:none !important; }
  [data-ui="simple"] { --fs-m:14px; --fs-s:13px; --fs-l:15px; }
  [data-ui="simple"][data-densite="compacte"] { --fs-m:13px; --fs-s:12px; }
  [data-ui="simple"] #v-station .gros { font-size:32px; }
  [data-ui="simple"] #v-station .val { font-size:20px; }
  .ui-bascule { display:inline-flex; border:1px solid var(--ligne-forte); border-radius:999px; overflow:hidden; background:var(--s2); flex:none; }
  .ui-bascule button { border:0; border-radius:0; background:transparent; padding:3px 10px; font-size:var(--fs-xs); font-weight:700; color:var(--texte-2); letter-spacing:.3px; }
  .ui-bascule button.actif { background:var(--rx); color:var(--sur-vif); }
  .ui-bascule button:not(.actif):hover { color:var(--texte); background:var(--s3); }
  @media (max-width:1300px) { .ui-bascule button { padding:3px 7px; } }

  /* ---------------------------------------------------------------- tiles: grip, removal, the place they will land */
  /* The grip and the removal: quiet until the tile is under the mouse (always there on a touch screen). */
  .pli-b.pli-poignee, .pli-b.pli-retirer { border-color:transparent; background:transparent; min-width:20px; padding:2px 3px; color:var(--texte-3); opacity:0; transition:opacity var(--anim), color var(--anim); }
  .pli-b.pli-poignee { cursor:grab; touch-action:none; font-size:14px; letter-spacing:-1px; }
  .pli-b.pli-poignee:active { cursor:grabbing; }
  .pli-b.pli-retirer:hover { color:var(--danger); border-color:transparent; }
  [data-tuile]:hover .pli-b.pli-poignee, [data-tuile]:hover .pli-b.pli-retirer, .pli-b.pli-poignee:focus-visible, .pli-b.pli-retirer:focus-visible { opacity:1; }
  @media (hover:none) { .pli-b.pli-poignee, .pli-b.pli-retirer { opacity:.8; } }
  .pli-retire-tuile { display:none !important; }
  .tuile-outils { position:absolute; top:4px; right:6px; display:flex; gap:2px; z-index:3; }
  [data-tuile] { position:relative; }
  .pli-tiree { opacity:.35; outline:2px dashed var(--rx); outline-offset:-2px; }
  .pli-marque { height:6px; margin:2px 0; border-radius:3px; background:var(--rx); box-shadow:0 0 12px var(--rx); flex:none; }
  .pli-col-cible { outline:2px dashed color-mix(in srgb, var(--rx) 55%, transparent); outline-offset:-2px; border-radius:var(--r3); }
  .pli-menu .pli-tuiles { display:grid; grid-template-columns:minmax(0, 1fr) auto; gap:3px 8px; align-items:center; margin:2px 0 6px; }
  .pli-menu .pli-tuiles select { padding:2px 4px; font-size:var(--fs-s); }
  .pli-menu .pli-tuiles .absente { color:var(--texte-3); font-style:italic; }

  /* ---------------------------------------------------------------- Réglages › Aide et affichage › Apparence */
  .ap-l { display:grid; grid-template-columns:96px minmax(0, 1fr); gap:6px 10px; align-items:center; margin:8px 0; }
  .ap-l > b { font-size:var(--fs-s); color:var(--texte-2); }
  .ap-seg { display:flex; flex-wrap:wrap; gap:4px; }
  .ap-seg button { display:flex; flex-direction:column; align-items:flex-start; gap:0; padding:4px 10px; }
  .ap-seg button small { font-size:var(--fs-xs); color:var(--texte-3); font-weight:400; }
  .ap-seg button[aria-pressed="true"] { border-color:var(--rx); background:color-mix(in srgb, var(--rx) 14%, var(--s3)); box-shadow:inset 0 0 0 1px var(--rx); }
  .ap-themes { display:grid; grid-template-columns:repeat(auto-fill, minmax(104px, 1fr)); gap:6px; }
  .ap-theme { display:flex; flex-direction:column; gap:5px; padding:6px; background:var(--s0); border:2px solid var(--ligne); border-radius:var(--r2); color:var(--texte); text-align:left; }
  .ap-theme[aria-pressed="true"] { border-color:var(--rx); box-shadow:0 0 0 2px color-mix(in srgb, var(--rx) 35%, transparent); }
  .ap-theme .ap-mini { display:flex; gap:4px; align-items:center; padding:5px; border-radius:var(--r1); background:var(--s2); border:1px solid var(--ligne); }
  .ap-theme .ap-mini i { width:12px; height:12px; border-radius:50%; flex:none; }
  .ap-theme .ap-mini b { flex:1; height:5px; border-radius:3px; background:var(--texte-3); }
  .ap-theme span { font-size:var(--fs-xs); font-weight:700; }
  .ap-theme.auto .ap-mini { background:linear-gradient(135deg, #131C24 50%, #FFFFFF 50%); }

  /* ---------------------------------------------------------------- the Suivi tab's « À l'écoute » tile */
  .ste-l { display:flex; align-items:center; gap:8px; min-width:0; }
  .ste-src { font-weight:700; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .ste-vu { flex:1; min-width:50px; height:8px; border-radius:4px; background:var(--creux); border:1px solid var(--ligne); overflow:hidden; }
  .ste-vu i { display:block; height:100%; width:0; background:var(--ok); transition:width .2s; }
  .ste-decs { display:flex; flex-wrap:wrap; gap:4px; margin-top:6px; }
  .ste-decs button { padding:2px 9px; border-radius:999px; font-size:var(--fs-s); color:var(--texte-3); background:var(--creux); }
  .ste-decs button.on { color:var(--sur-vif); background:var(--rx); border-color:var(--rx); }
  .ste-decs button.on::before { content:'● '; }
"""

    /** In the page's head: the appearance this browser saw last, before anything is drawn (no flash). */
    const val TETE = """<script id="apparence-tete">try { (function () { const a = JSON.parse(localStorage.getItem('satme.apparence') || 'null'), h = document.documentElement; if (!a) return;
  h.dataset.theme = a.theme === 'auto' ? (matchMedia('(prefers-color-scheme: light)').matches ? 'clair' : 'sombre') : a.theme || 'sombre';
  h.dataset.forme = a.forme || 'arrondi'; h.dataset.densite = a.densite || 'normale'; h.dataset.ui = a.interface || 'complete'; })(); } catch (x) {}</script>"""

    /** In the header: simple or complete. */
    const val ENTETE = """<span class="ui-bascule" id="h-ui" role="group" aria-label="Interface"><button type="button" data-ui="simple" data-apt="ap_simple" title="Interface simple : l'essentiel d'un passage">Simple</button><button type="button" data-ui="complete" data-apt="ap_complete" title="Interface complète : tout">Complète</button></span>"""

    /** At the rail's foot: the next theme. */
    val RAIL = "<button id=\"rail-theme\" type=\"button\" onclick=\"apThemeSuivant()\" title=\"Thème : le suivant (tous les réglages : Réglages › Aide et affichage)\" aria-label=\"Thème\"><svg class=\"ico\" viewBox=\"0 0 24 24\" aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2v16a8 8 0 0 1 0-16z\"/></svg><span>Thème</span></button>"

    /** Réglages › Aide et affichage › Apparence. */
    const val CARTE = """
    <div class="carte" id="ap-carte">
      <div class="t">Apparence</div>
      <div class="ap-l"><b data-apt="ap_interface">Interface</b><div class="ap-seg" id="ap-ui" role="group" aria-label="Interface">
        <button type="button" data-v="simple"><span data-apt="ap_simple">Simple</span><small data-apt="ap_simple_d">l'essentiel d'un passage</small></button>
        <button type="button" data-v="complete"><span data-apt="ap_complete">Complète</span><small data-apt="ap_complete_d">tous les onglets, toutes les tuiles</small></button></div></div>
      <div class="ap-l"><b data-apt="ap_theme">Thème</b><div class="ap-themes" id="ap-themes" role="group" aria-label="Thème"></div></div>
      <div class="ap-l"><b data-apt="ap_forme">Coins</b><div class="ap-seg" id="ap-forme" role="group" aria-label="Coins">
        <button type="button" data-v="arrondi" data-apt="ap_arrondi">Arrondis</button><button type="button" data-v="carre" data-apt="ap_carre">Carrés</button><button type="button" data-v="galet" data-apt="ap_galet">Galets</button></div></div>
      <div class="ap-l"><b data-apt="ap_densite">Densité</b><div class="ap-seg" id="ap-densite" role="group" aria-label="Densité">
        <button type="button" data-v="compacte" data-apt="ap_compacte">Compacte</button><button type="button" data-v="normale" data-apt="ap_normale">Normale</button><button type="button" data-v="aeree" data-apt="ap_aeree">Aérée</button></div></div>
      <div class="ligne"><button type="button" id="ap-defaut" onclick="apDispositionDefaut()" data-apt="ap_defaut">Disposition par défaut</button><span class="petit aide-texte" data-apt="ap_defaut_d">Toutes les tuiles des onglets reprennent leur place, dans les deux interfaces.</span></div>
      <div class="petit aide-texte" style="margin-top:6px" data-apt="ap_aide">Les tuiles se déplacent par leur poignée ⠿ (souris, doigt ou flèches du clavier) ; « ⊞ Disposition » en bas d'un onglet les retire ou les ajoute. L'émission reste rouge-orangé dans tous les thèmes. Gardé par SatMe-PC.</div>
      <div class="etat" id="ap-msg" aria-live="polite"></div>
    </div>
"""

    /** The Suivi tab's « À l'écoute » tile: the decoders' source, its level, the decoders listening (receive only). */
    const val TUILE_ECOUTE = """
    <div class="carte" id="st-ecoute" data-tuile="ecoute" data-pli="ecoute" data-pli-nom="À l'écoute : son et décodeurs">
      <div class="t" data-apt="ap_ecoute">À l'écoute</div>
      <div class="ste-l"><span class="ste-src" id="ste-src">—</span><span class="ste-vu" aria-hidden="true"><i id="ste-vu"></i></span>
        <button type="button" class="discret" onclick="hsBascule()" title="Changer la source, démarrer, enregistrer — touche S" data-apt="ap_ecoute_changer">Changer…</button></div>
      <div class="ste-decs" id="ste-decs" role="group" aria-label="Décodeurs"></div>
      <div class="petit" id="ste-msg"></div>
    </div>
"""

    const val JS = """
// ------------------------------------------------------------------ appearance: theme, corners, density, simple or complete, tiles (ApparencePagePc)
// Kept by SatMe-PC (api/apparence); a copy in this browser draws the page right before the answer.
// A var (not a const): the tabs' tiles are made before this part of the script runs, and ask APT and APP whether they are there.
var APP = { d: { theme: 'sombre', forme: 'arrondi', densite: 'normale', interface: 'complete', dispositions: {} }, textes: {}, plis: PLIS, envoi: {}, lu: false };
const AP_THEMES = ['sombre', 'clair', 'aurore', 'phosphore', 'sable', 'auto'];
/** A text of apparence-pc.json in the station's language, else the French one given. */
function APT(k, fr) { return (window.APP && APP.textes[k]) || fr || k; }
const apSimple = () => document.documentElement.dataset.ui === 'simple';
const apEffectif = t => t === 'auto' ? (matchMedia('(prefers-color-scheme: light)').matches ? 'clair' : 'sombre') : t;
function apGardeIci() {
  const d = APP.d;
  try { localStorage.setItem('satme.apparence', JSON.stringify({ theme: d.theme, forme: d.forme, densite: d.densite, interface: d.interface })); } catch (x) {}
}
/** Everything as APP.d says: the html's marks, the buttons, the tabs' layouts of this interface. */
function apApplique() {
  const h = document.documentElement, d = APP.d, avant = h.dataset.ui;
  h.dataset.theme = apEffectif(d.theme); h.dataset.forme = d.forme; h.dataset.densite = d.densite; h.dataset.ui = d.interface;
  const m = document.querySelector('meta[name="theme-color"]'); if (m) m.content = getComputedStyle(h).getPropertyValue('--s1').trim() || '#0F161D';
  document.querySelectorAll('#h-ui [data-ui]').forEach(b => { b.classList.toggle('actif', b.dataset.ui === d.interface); b.setAttribute('aria-pressed', b.dataset.ui === d.interface); });
  const seg = (id, v) => document.querySelectorAll('#' + id + ' [data-v]').forEach(b => b.setAttribute('aria-pressed', b.dataset.v === v));
  seg('ap-ui', d.interface); seg('ap-themes', d.theme); seg('ap-forme', d.forme); seg('ap-densite', d.densite);
  g('rail-theme').title = APT('ap_theme_suivant', 'Thème : le suivant') + ' — ' + APT('ap_th_' + d.theme, d.theme);
  APP.plis.forEach(P => pliMode(P));
  if (avant && avant !== d.interface) {
    // A tab of the complete interface only: back to the Suivi tab.
    const b = document.querySelector('#onglets button.actif');
    if (b && b.classList.contains('ui-c') && d.interface === 'simple') vue('station');
    window.dispatchEvent(new Event('resize'));
  }
}
matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => { if (APP.d.theme === 'auto') apApplique(); });
async function apPost(q) {
  if (window.SATME_TELEPHONE) return null;
  try { const r = await fetch('api/apparence?' + q, { method: 'POST' }); return r.ok ? await r.json() : null; } catch (x) { return null; }
}
/** Setting [cle] (theme, forme, densite, interface) to [v]: shown at once, kept by SatMe-PC. */
async function apRegle(cle, v) {
  APP.d[cle] = v; apApplique(); apGardeIci();
  const j = await apPost(cle + '=' + encodeURIComponent(v));
  txt('ap-msg', j && j.ok ? APT('ap_garde', 'Gardé par SatMe-PC.') : APT('ap_ici', 'Gardé dans ce navigateur (SatMe-PC ne répond pas).'));
}
function apThemeSuivant() { apRegle('theme', AP_THEMES[(AP_THEMES.indexOf(APP.d.theme) + 1) % (AP_THEMES.length - 1)]); }
/** A tab's layout kept by SatMe-PC (a second later, the last one only); [d] null: its default again. */
function apDispo(cle, d) {
  APP.d.dispositions = APP.d.dispositions || {};
  if (d) APP.d.dispositions[cle] = d; else delete APP.d.dispositions[cle];
  clearTimeout(APP.envoi[cle]);
  APP.envoi[cle] = setTimeout(() => apPost('dispo=' + encodeURIComponent(cle) + '&json=' + encodeURIComponent(d ? JSON.stringify(d) : '')), 800);
}
/** « Disposition par défaut »: every tab, both interfaces. */
async function apDispositionDefaut() {
  APP.d.dispositions = {};
  APP.plis.forEach(P => { [P.base, P.base + '.simple'].forEach(k => { try { localStorage.removeItem(k); } catch (x) {} }); P.d = pliDefaut(P); pliChange(P, true); });
  const j = await apPost('defaut=1');
  txt('ap-msg', j && j.ok ? APT('ap_defaut_fait', 'Toutes les tuiles ont repris leur place.') : APT('ap_ici', 'Gardé dans ce navigateur (SatMe-PC ne répond pas).'));
}
/** The settings' swatches: each one wears its own palette. */
function apNuancier() {
  g('ap-themes').innerHTML = AP_THEMES.map(t => '<button type="button" class="ap-theme' + (t === 'auto' ? ' auto' : '') + '" data-v="' + t + '"' + (t === 'auto' ? '' : ' data-theme="' + t + '"') + '>' +
    '<span class="ap-mini"><i style="background:var(--rx)"></i><i style="background:var(--tx)"></i><i style="background:var(--mark)"></i><b></b></span>' +
    '<span>' + esc(APT('ap_th_' + t, t)) + '</span></button>').join('');
}
function apTextes() {
  document.querySelectorAll('[data-apt]').forEach(e => { const t = APP.textes[e.dataset.apt]; if (t) e.textContent = t; });
  apNuancier();
}
async function apparenceCharge() {
  if (window.SATME_TELEPHONE) { g('h-ui').hidden = true; g('rail-theme').hidden = true; return; }
  let j = null;
  try { const r = await fetch('api/apparence'); j = r.ok ? await r.json() : null; } catch (x) {}
  if (j && j.ok) {
    APP.textes = j.textes || {};
    ['theme', 'forme', 'densite', 'interface'].forEach(k => { if (j[k]) APP.d[k] = j[k]; });
    APP.d.dispositions = j.dispositions || {};
    APP.lu = true;
    apTextes(); apGardeIci();
    // The layouts SatMe-PC keeps win over this browser's; one only this browser has (before this version) is given to it.
    APP.plis.forEach(P => { P.cle = ''; });
  }
  apApplique();
}
(function () {
  const h = document.documentElement;
  // Before the answer: as this browser saw it last.
  try { const a = JSON.parse(localStorage.getItem('satme.apparence') || 'null'); if (a) ['theme', 'forme', 'densite', 'interface'].forEach(k => { if (a[k]) APP.d[k] = a[k]; }); } catch (x) {}
  if (!h.dataset.ui) h.dataset.ui = APP.d.interface;
  apNuancier();
  document.querySelectorAll('#h-ui [data-ui]').forEach(b => b.onclick = () => apRegle('interface', b.dataset.ui));
  g('ap-ui').onclick = ev => { const b = ev.target.closest('[data-v]'); if (b) apRegle('interface', b.dataset.v); };
  g('ap-themes').onclick = ev => { const b = ev.target.closest('[data-v]'); if (b) apRegle('theme', b.dataset.v); };
  g('ap-forme').onclick = ev => { const b = ev.target.closest('[data-v]'); if (b) apRegle('forme', b.dataset.v); };
  g('ap-densite').onclick = ev => { const b = ev.target.closest('[data-v]'); if (b) apRegle('densite', b.dataset.v); };
})();

// ------------------------------------------------------------------ the Suivi tab's « À l'écoute » tile (receive only: it shows, the header's Son changes)
const STE_DEC = [['SSTV', 'SSTV', 'sstv'], ['APRS', 'APRS', 'aprs'], ['FT8', 'FT8/FT4', 'ft8'], ['CW', 'CW', ''], ['Phonie', 'Phonie', '']];
function steRend() {
  const s = S;
  if (!s || !s.present) { txt('ste-src', APT('ap_ecoute_absent', 'Pas de son ici')); return; }
  const ec = (s.ecoutent || []).map(d => (HS_DEC[d] || d));
  txt('ste-src', s.actif ? hsLib(s.appareil || s.carte) : APT('ap_ecoute_arret', 'Son arrêté'));
  g('ste-vu').style.width = (s.actif ? Math.round(Math.min(1, s.niveau || 0) * 100) : 0) + '%';
  g('ste-vu').style.background = s.niveau > 0.9 ? 'var(--danger)' : s.niveau < 0.003 ? 'var(--warn)' : 'var(--ok)';
  const ph = g('ph-carte');
  const h = STE_DEC.filter(x => x[0] !== 'Phonie' || (ph && !ph.hidden)).map(x => '<button type="button" data-d="' + x[0] + '" data-o="' + x[2] + '" class="' + (ec.includes(x[0]) ? 'on' : '') + '" title="' +
    escA((ec.includes(x[0]) ? APT('ap_ecoute_on', 'À l’écoute') : APT('ap_ecoute_off', 'Pas à l’écoute')) + (x[2] ? ' — ' + APT('ap_ecoute_ouvrir', 'clic : son onglet') : '')) + '">' + esc(x[1]) + '</button>').join('');
  if (g('ste-decs').innerHTML !== h) g('ste-decs').innerHTML = h;
  txt('ste-msg', s.conseil ? s.conseil.texte : s.enregistrement ? '● ' + APT('ap_ecoute_enr', 'Enregistrement du son en cours') : '');
}
g('ste-decs').onclick = ev => { const b = ev.target.closest('[data-d]'); if (!b) return; if (b.dataset.o) vue(b.dataset.o); else hsBascule(); };
setInterval(() => { if (!g('v-station').hidden) steRend(); }, 1000);
apparenceCharge();
"""
}
