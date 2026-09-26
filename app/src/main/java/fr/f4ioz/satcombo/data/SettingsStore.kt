/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context

enum class LocationMode { AUTO, MANUAL }

/** Persists QTH settings: auto GPS or manual Maidenhead locator. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("satcombo_settings", Context.MODE_PRIVATE)

    /**
     * Les reprises ponctuelles de réglages déjà écrits.
     *
     * Changer un défaut ne touche que les installations neuves : un réglage
     * déjà posé dans les préférences est relu tel quel, et l'opérateur qui a
     * l'application depuis six mois garde l'ancien comportement sans savoir
     * qu'un autre existe. Il faut donc réécrire la valeur — une fois, et une
     * seule, faute de quoi on écraserait à chaque démarrage le choix que
     * l'opérateur vient de faire.
     *
     * Le compteur retient jusqu'où on est allé. Une reprise neuve s'ajoute à
     * la suite et incrémente `reprises`.
     */
    init {
        val faites = prefs.getInt("reprises", 0)
        if (faites < 1) {
            // 19.13 — le double appui ouvre le clavier. Le geste n'écrit plus
            // rien depuis la 19.11 : trois appuis n'ont plus rien à protéger.
            prefs.edit().putInt("log_taps", 2).putInt("reprises", 1).apply()
        }
    }

    var locationMode: LocationMode
        get() = if (prefs.getString("loc_mode", "AUTO") == "MANUAL") LocationMode.MANUAL else LocationMode.AUTO
        set(v) { prefs.edit().putString("loc_mode", v.name).apply() }

    var manualLocator: String
        get() = prefs.getString("loc_locator", "JN18FS") ?: "JN18FS"
        set(v) { prefs.edit().putString("loc_locator", v.trim().uppercase()).apply() }

    /**
     * Exact point picked on the map, in degrees, or NaN when the operator only
     * typed a locator. The centre of a square is a fiction: a station near a
     * border, or a portable spot in a park, deserves its real coordinates —
     * they drive the distances, the neighbouring squares and the pass geometry.
     * Stored as text because SharedPreferences has no double and Float would
     * throw away metres.
     */
    var manualLat: Double
        get() = prefs.getString("loc_lat", null)?.toDoubleOrNull() ?: Double.NaN
        set(v) { prefs.edit().putString("loc_lat", if (v.isNaN()) null else v.toString()).apply() }

    var manualLon: Double
        get() = prefs.getString("loc_lon", null)?.toDoubleOrNull() ?: Double.NaN
        set(v) { prefs.edit().putString("loc_lon", if (v.isNaN()) null else v.toString()).apply() }

    var useUtc: Boolean
        get() = prefs.getBoolean("use_utc", false)
        set(v) { prefs.edit().putBoolean("use_utc", v).apply() }

    /** Show the coordinates + Field/Square/Subsquare breakdown on the locator page. */
    var locatorDetails: Boolean
        get() = prefs.getBoolean("locator_details", true)
        set(v) { prefs.edit().putBoolean("locator_details", v).apply() }

    /** Show the pass audio recorder (REC button) — visible by default. */
    var recorderEnabled: Boolean
        get() = prefs.getBoolean("recorder_enabled", true)
        set(v) { prefs.edit().putBoolean("recorder_enabled", v).apply() }

    /** Recorder audio source: "MIC" (phone mic) or "BT" (Bluetooth HFP headset link). */
    /** Capture route for the pass recorder: "MIC", "BT" (HFP/SCO) or "USB". */
    var recorderSource: String
        get() = prefs.getString("recorder_source", "MIC") ?: "MIC"
        set(v) { prefs.edit().putString("recorder_source", v).apply() }

    /** Capture with AudioSource.UNPROCESSED when the device supports it: no AGC
     *  and no noise suppression on audio the rig has already processed. */
    /** Afficher le spectre du son pendant l'enregistrement. */
    var monitorSpectre: Boolean
        get() = prefs.getBoolean("monitor_spectre", false)
        set(v) { prefs.edit().putBoolean("monitor_spectre", v).apply() }

    /** Renvoyer le son capté vers le haut-parleur du téléphone (contrôle). */
    var monitorSpeaker: Boolean
        get() = prefs.getBoolean("monitor_speaker", false)
        set(v) { prefs.edit().putBoolean("monitor_speaker", v).apply() }

    var recorderUnprocessed: Boolean
        get() = prefs.getBoolean("recorder_unprocessed", false)
        set(v) { prefs.edit().putBoolean("recorder_unprocessed", v).apply() }

    /** SAF tree URI of the user-chosen export folder for recordings ("" = none:
     *  files stay in the app's private recordings dir only). */
    var recordingsTreeUri: String
        get() = prefs.getString("rec_tree_uri", "") ?: ""
        set(v) { prefs.edit().putString("rec_tree_uri", v).apply() }

    /** Watch the recorded audio for an SSTV header and decode pictures live.
     *  Off by default: most passes carry no SSTV, and the operator who wants
     *  pictures turns it on knowingly. */
    var sstvEnabled: Boolean
        get() = prefs.getBoolean("sstv_enabled", false)
        set(v) { prefs.edit().putBoolean("sstv_enabled", v).apply() }

    /**
     * Mode SSTV imposé au décodeur, ou vide pour suivre l'en-tête VIS.
     *
     * L'en-tête d'un signal faible se lit parfois de travers : la parité
     * passe, le code ne correspond pas au mode émis, et l'image sort
     * mélangée. Quand l'opérateur sait ce qui est émis, il peut le dire.
     */
    var sstvForcedMode: String
        get() = prefs.getString("sstv_forced_mode", "") ?: ""
        set(v) { prefs.edit().putString("sstv_forced_mode", v).apply() }

    /** Surveiller le son reçu pour y trouver une image APT (NOAA, 137 MHz).
     *  Contrairement à la SSTV, le décodage APT tourne en permanence dès qu'il
     *  est actif : il n'y a pas d'en-tête à attendre, l'image commence dès que
     *  la synchronisation de ligne s'accroche. On le laisse donc éteint par
     *  défaut, et l'opérateur l'allume avant un passage NOAA. */
    var aptEnabled: Boolean
        get() = prefs.getBoolean("apt_enabled", false)
        set(v) { prefs.edit().putBoolean("apt_enabled", v).apply() }

    /**
     * Ce que la bande image de la page du passage montre : "SSTV" ou "NOAA".
     *
     * Les deux décodeurs peuvent tourner ensemble, mais on ne suit qu'un
     * satellite à la fois et l'écran du passage n'a pas la place d'afficher
     * deux images. Le choix se fait d'une touche sur la puce, et il est retenu :
     * celui qui fait du NOAA en fait plusieurs passages de suite.
     */
    var rxImageMode: String
        get() = prefs.getString("rx_image_mode", "SSTV") ?: "SSTV"
        set(v) { prefs.edit().putString("rx_image_mode", v).apply() }

    // --- Clé RTL-SDR (bêta) -------------------------------------------------

    /** Gain du tuner en dixièmes de dB ; -1 = gain automatique. */
    var sdrGainTenthDb: Int
        get() = prefs.getInt("sdr_gain", -1)
        set(v) { prefs.edit().putInt("sdr_gain", v).apply() }

    /** AGC numérique du RTL2832U, en plus du gain du tuner. */
    var sdrAgc: Boolean
        get() = prefs.getBoolean("sdr_agc", false)
        set(v) { prefs.edit().putBoolean("sdr_agc", v).apply() }

    /** Erreur du quartz de la clé, en ppm. Les clés bon marché dérivent de
     *  quelques dizaines de ppm, soit plusieurs kilohertz en UHF. */
    var sdrPpm: Int
        get() = prefs.getInt("sdr_ppm", 0)
        set(v) { prefs.edit().putInt("sdr_ppm", v).apply() }

    /** Décoder le SSTV directement depuis la clé. */
    var sdrSstv: Boolean
        get() = prefs.getBoolean("sdr_sstv", true)
        set(v) { prefs.edit().putBoolean("sdr_sstv", v).apply() }

    /** Enregistrer un MP3 pendant la réception SDR. */
    var sdrRecord: Boolean
        get() = prefs.getBoolean("sdr_record", true)
        set(v) { prefs.edit().putBoolean("sdr_record", v).apply() }

    /** Sortir l'audio démodulé sur le casque / le haut-parleur. */
    var sdrAudio: Boolean
        get() = prefs.getBoolean("sdr_audio", true)
        set(v) { prefs.edit().putBoolean("sdr_audio", v).apply() }

    /** Mode de démodulation : NFM, USB, LSB ou AM. */
    var sdrMode: String
        get() = prefs.getString("sdr_mode", "NFM") ?: "NFM"
        set(v) { prefs.edit().putString("sdr_mode", v).apply() }

    /** Largeur de canal en hertz ; 0 laisse le mode décider. */
    var sdrBandwidthHz: Int
        get() = prefs.getInt("sdr_bw", 0)
        set(v) { prefs.edit().putInt("sdr_bw", v).apply() }

    /** Seuil du silencieux en dBFS ; -120 le coupe. */
    var sdrSquelchDb: Int
        get() = prefs.getInt("sdr_squelch", -120)
        set(v) { prefs.edit().putInt("sdr_squelch", v).apply() }

    /** Largeur affichée par le spectre, en hertz. */
    /** Petite cascade sous la boussole, sur la page du passage. */
    var sdrInlineWaterfall: Boolean
        get() = prefs.getBoolean("sdr_inline_wf", true)
        set(v) { prefs.edit().putBoolean("sdr_inline_wf", v).apply() }

    /**
     * Dernière fréquence écoutée pour les radiosondes, en hertz.
     *
     * Elle est bornée à la bande météo : un réglage abîmé ne doit pas envoyer
     * la clé se promener sur les balises de détresse.
     */
    var sondeFreqHz: Long
        get() {
            val v = prefs.getLong("sonde_freq", 404_000_000L)
            return if (fr.f4ioz.satcombo.sonde.SondeSites.inBand(v)) v else 404_000_000L
        }
        set(v) {
            if (fr.f4ioz.satcombo.sonde.SondeSites.inBand(v)) {
                prefs.edit().putLong("sonde_freq", v).apply()
            }
        }

    var sdrSpanHz: Int
        get() = prefs.getInt("sdr_span", 48_000)
        set(v) { prefs.edit().putInt("sdr_span", v).apply() }

    // ------------------------------------------------------------ accord fin
    // Trois aides indépendantes plutôt qu'un choix unique : elles n'occupent
    // pas la même place et ne répondent pas à la même question. La loupe
    // montre, le vernier déplace, le calage décide. On peut vouloir la loupe
    // sans le vernier — voir le spectre et se poser au doigt — comme le
    // vernier sans la loupe, sur un écran étroit où la place manque.

    /** Loupe : seconde vue du spectre, large de quelques kilohertz. */
    var sdrLoupe: Boolean
        get() = prefs.getBoolean("sdr_loupe", true)
        set(v) { prefs.edit().putBoolean("sdr_loupe", v).apply() }

    /** Largeur de la loupe, en hertz. */
    var sdrLoupeSpanHz: Int
        get() = prefs.getInt("sdr_loupe_span", 5_000)
        set(v) { prefs.edit().putInt("sdr_loupe_span", v).apply() }

    /** Vernier : cadran à défilement, accord relatif au doigt. */
    var sdrVernier: Boolean
        get() = prefs.getBoolean("sdr_vernier", true)
        set(v) { prefs.edit().putBoolean("sdr_vernier", v).apply() }

    /** Rapport du vernier, en hertz par centimètre de glissement. */
    var sdrVernierHzParCm: Int
        get() = prefs.getInt("sdr_vernier_ratio", 200)
        set(v) { prefs.edit().putInt("sdr_vernier_ratio", v).apply() }

    /** Bouton de calage sur la voix reçue (bande latérale seulement). */
    var sdrCalageVoix: Boolean
        get() = prefs.getBoolean("sdr_calage_voix", true)
        set(v) { prefs.edit().putBoolean("sdr_calage_voix", v).apply() }

    /**
     * Clavier des indicatifs tenu de la main gauche.
     *
     * Trois lignes de code qui décident de l'utilisabilité réelle à une main :
     * sur un écran tenu d'une main, le pouce atteint bien son propre bord et
     * mal celui d'en face. Validation et effacement doivent tomber du côté de
     * la main qui tient, pas du côté choisi par le développeur.
     */
    /** Silence exigé avant que le logiciel ne reprenne la molette, en ms. */
    /**
     * Le liseré d'émission, et donc le sondage CAT qui l'alimente.
     *
     * Il partage la liaison série avec le Doppler : qui cherche la réactivité
     * maximale du suivi le coupe, qui veut voir qu'il émet le garde. Le choix
     * appartient à l'opérateur, il ne se devine pas.
     */
    var liseréEmission: Boolean
        get() = prefs.getBoolean("cat_liseret_tx", true)
        set(v) { prefs.edit().putBoolean("cat_liseret_tx", v).apply() }

    /** Cadence du sondage d'émission, en millisecondes. */
    var sondeTxMs: Int
        get() = prefs.getInt("cat_sonde_tx_ms", 500)
        set(v) { prefs.edit().putInt("cat_sonde_tx_ms", v.coerceIn(250, 3000)).apply() }

    /** L'émission se recale sans attendre la fin du délai de reprise. */
    var txSuitVite: Boolean
        get() = prefs.getBoolean("cat_tx_suit_vite", true)
        set(v) { prefs.edit().putBoolean("cat_tx_suit_vite", v).apply() }

    /** Le carnet en ligne : Wavelog ou Cloudlog, même API. */
    var carnetUrl: String
        get() = prefs.getString("carnet_url", "") ?: ""
        set(v) { prefs.edit().putString("carnet_url", v.trim()).apply() }

    var carnetCle: String
        get() = prefs.getString("carnet_cle", "") ?: ""
        set(v) { prefs.edit().putString("carnet_cle", v.trim()).apply() }

    var carnetSlug: String
        get() = prefs.getString("carnet_slug", "") ?: ""
        set(v) { prefs.edit().putString("carnet_slug", v.trim()).apply() }

    /**
     * L'identifiant du profil de station Wavelog, pour déposer les contacts.
     *
     * Séparé de la clé et du *slug* parce qu'il sert à autre chose : ceux-là
     * interrogent, celui-ci écrit. Un opérateur peut vouloir la peinture des
     * carrés sans jamais déposer, et l'inverse n'a pas de sens — d'où un champ
     * de plus plutôt qu'un réglage obligatoire de plus.
     */
    var carnetProfil: String
        get() = prefs.getString("carnet_profil", "") ?: ""
        set(v) { prefs.edit().putString("carnet_profil", v.trim()).apply() }

    /**
     * Le dernier contact rapatrié du carnet en ligne.
     *
     * C'est ce qui rend la moisson différentielle : l'appel suivant repart de
     * là plutôt que de redemander tout le journal. Le serveur le demande
     * expressément — les instances limitent le débit, et un carnet de
     * plusieurs milliers de contacts n'a pas à traverser le réseau chaque
     * fois qu'on veut les dix derniers.
     *
     * Remis à zéro, on recharge tout : c'est la sortie de secours quand
     * l'index paraît incomplet.
     */
    /** L'ensemble de profils auquel se rapporte le curseur de moisson. */
    var carnetProfilsVus: String
        get() = prefs.getString("carnet_profils_vus", "") ?: ""
        set(v) { prefs.edit().putString("carnet_profils_vus", v).apply() }

    var carnetDernierId: Long
        get() = prefs.getLong("carnet_dernier_id", 0L)
        set(v) { prefs.edit().putLong("carnet_dernier_id", v).apply() }

    /**
     * Ce qu'on moissonne : « sat », « phonie », « cw » ou « tout ».
     *
     * Satellite par défaut : c'est ce à quoi sert le clavier, et un
     * correspondant croisé une fois en quarante mètres n'a rien à faire dans
     * ses suggestions.
     *
     * **Changer ce réglage remet le compteur différentiel à zéro.** Sans cela,
     * passer de « sat » à « tout » ne rapporterait que les contacts postérieurs
     * au dernier appel : tout l'historique HF resterait invisible, et l'on
     * croirait le filtre inopérant. Le défaut serait silencieux et durable.
     */
    /**
     * Les mémoires QO-100 posées par l'opérateur.
     *
     * Rangées en JSON plutôt qu'en champs séparés : leur nombre n'est pas
     * borné, et une liste de préférences numérotées se corrompt dès qu'on en
     * retire une du milieu.
     */
    var qo100Memoires: List<fr.f4ioz.satcombo.domain.MemoiresQo100.Memoire>
        get() = runCatching {
            val a = org.json.JSONArray(prefs.getString("qo100_memoires", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                fr.f4ioz.satcombo.domain.MemoiresQo100.Memoire(
                    cle = "", hz = o.getLong("hz"), fixe = false,
                    nom = o.optString("nom"))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val a = org.json.JSONArray()
            v.forEach { m ->
                a.put(org.json.JSONObject().put("hz", m.hz).put("nom", m.nom))
            }
            prefs.edit().putString("qo100_memoires", a.toString()).apply()
        }

    /**
     * Les chaînes de conversion QO-100, nommées.
     *
     * Elles vivent avec les réglages QO-100 et non dans les convertisseurs
     * généraux : un oscillateur à 10 345 MHz n'a aucun sens sur RS-44, et le
     * réglage traînait dans un écran où personne n'allait le chercher.
     */
    var qo100Chaines: List<fr.f4ioz.satcombo.domain.ChaineQo100.Chaine>
        get() = runCatching {
            val a = org.json.JSONArray(prefs.getString("qo100_chaines", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                fr.f4ioz.satcombo.domain.ChaineQo100.Chaine(
                    nom = o.optString("nom"),
                    descenteOlHz = o.optLong("d"),
                    monteeOlHz = o.optLong("m"))
            }
        }.getOrDefault(emptyList())
            .ifEmpty { fr.f4ioz.satcombo.domain.ChaineQo100.PAR_DEFAUT }
        set(v) {
            val a = org.json.JSONArray()
            v.forEach { c ->
                a.put(org.json.JSONObject()
                    .put("nom", c.nom).put("d", c.descenteOlHz).put("m", c.monteeOlHz))
            }
            prefs.edit().putString("qo100_chaines", a.toString()).apply()
        }

    /** Le nom de la chaîne en service. */
    /** Les appareils de réception et leur écart, en texte simple. */
    var materielsRx: String
        get() = prefs.getString("materiels_rx", "") ?: ""
        set(v) { prefs.edit().putString("materiels_rx", v).apply() }

    var materielPoste: String
        get() = prefs.getString("materiel_poste", "FT-817 A") ?: "FT-817 A"
        set(v) { prefs.edit().putString("materiel_poste", v).apply() }

    var materielCle: String
        get() = prefs.getString("materiel_cle", "Clé SDR 1") ?: "Clé SDR 1"
        set(v) { prefs.edit().putString("materiel_cle", v).apply() }

    var qo100Chaine: String
        get() = prefs.getString("qo100_chaine", "Fixe") ?: "Fixe"
        set(v) { prefs.edit().putString("qo100_chaine", v).apply() }

    /**
     * Balayer tout QO-100 plutôt que le seul transpondeur choisi.
     *
     * La bride est une garde utile : au-delà des bords, la montée
     * correspondante sort du transpondeur et la porteuse part chez le voisin.
     * Mais elle empêche aussi d'**écouter** ce qu'il y a ailleurs — chercher
     * une balise, voir si le transpondeur large travaille, retrouver quelqu'un
     * qui s'est déplacé.
     *
     * Décochée par défaut : celui qui n'a rien demandé garde la garde.
     */
    var qo100SansBride: Boolean
        get() = prefs.getBoolean("qo100_sans_bride", false)
        set(v) { prefs.edit().putBoolean("qo100_sans_bride", v).apply() }

    var carnetFiltre: String
        get() = prefs.getString("carnet_filtre", "sat") ?: "sat"
        set(v) {
            if (v != carnetFiltre) carnetDernierId = 0L
            prefs.edit().putString("carnet_filtre", v).apply()
        }

    /** LoTW : indicatif et mot de passe du compte ARRL. */
    /** Peindre les carrés travaillés et activés sur les cartes. */
    var peindreCarres: Boolean
        get() = prefs.getBoolean("peindre_carres", true)
        set(v) { prefs.edit().putBoolean("peindre_carres", v).apply() }

    var lotwCall: String
        get() = prefs.getString("lotw_call", "") ?: ""
        set(v) { prefs.edit().putString("lotw_call", v.trim().uppercase()).apply() }

    var lotwMdp: String
        get() = prefs.getString("lotw_mdp", "") ?: ""
        set(v) { prefs.edit().putString("lotw_mdp", v).apply() }

    var catHoldMs: Int
        get() = prefs.getInt("cat_hold_ms", 2_000)
        set(v) { prefs.edit().putInt("cat_hold_ms", v).apply() }

    /** La molette d'émission tient lieu de commande de décalage. */
    var catTxVfoShift: Boolean
        get() = prefs.getBoolean("cat_tx_vfo_shift", false)
        set(v) { prefs.edit().putBoolean("cat_tx_vfo_shift", v).apply() }

    var clavierMainGauche: Boolean
        get() = prefs.getBoolean("clavier_main_gauche", false)
        set(v) { prefs.edit().putBoolean("clavier_main_gauche", v).apply() }

    /**
     * La disposition des touches : « abc », « azerty » ou « qwerty ».
     *
     * L'alphabétique reste le défaut : il ne demande aucune habitude, ce qui
     * est ce qu'il faut à qui découvre l'application. Les deux autres rendent
     * aux rangées leur vraie largeur — dix touches — et donc l'habitude du
     * clavier de tous les jours, au prix de touches plus étroites.
     */
    /**
     * La molette USB pilote-t-elle le VFO ?
     *
     * Fermé par défaut : ces molettes sont des touches de volume, et
     * quelqu'un qui n'en a pas ne doit rien perdre.
     */
    // ---- Le boîtier à trois touches ----

    /**
     * Les codes des trois touches, appris et non saisis.
     *
     * Zéro veut dire « pas encore apprise », et une touche à zéro ne déclenche
     * jamais rien : sans cette garde, trois touches non apprises répondraient
     * toutes au même code et la molette changerait de cible à chaque frappe.
     */
    var macroCodeA: Int
        get() = prefs.getInt("macro_code_a", 0)
        set(v) { prefs.edit().putInt("macro_code_a", v).apply() }
    var macroCodeB: Int
        get() = prefs.getInt("macro_code_b", 0)
        set(v) { prefs.edit().putInt("macro_code_b", v).apply() }
    var macroCodeC: Int
        get() = prefs.getInt("macro_code_c", 0)
        set(v) { prefs.edit().putInt("macro_code_c", v).apply() }

    /** Ce que chaque touche sélectionne : VFO, SHIFT_RX ou SHIFT_TX. */
    var macroCibleA: String
        get() = prefs.getString("macro_cible_a", "SHIFT_RX") ?: "SHIFT_RX"
        set(v) { prefs.edit().putString("macro_cible_a", v).apply() }
    var macroCibleB: String
        get() = prefs.getString("macro_cible_b", "SHIFT_TX") ?: "SHIFT_TX"
        set(v) { prefs.edit().putString("macro_cible_b", v).apply() }
    var macroCibleC: String
        get() = prefs.getString("macro_cible_c", "VFO") ?: "VFO"
        set(v) { prefs.edit().putString("macro_cible_c", v).apply() }

    /**
     * Le poussoir de la molette et ce qu'il fait.
     *
     * Le code vaut 164 — « Sourdine » — par défaut : c'est ce qu'envoient la
     * plupart de ces boîtiers, et c'était le comportement figé d'avant.
     */
    var macroCodeD: Int
        get() = prefs.getInt("macro_code_d", 164)
        set(v) { prefs.edit().putInt("macro_code_d", v).apply() }

    /** « PAS », « CIBLE » ou « ZERO ». */
    var macroActionD: String
        get() = prefs.getString("macro_action_d", "PAS") ?: "PAS"
        set(v) { prefs.edit().putString("macro_action_d", v).apply() }

    /** La cible courante, retenue d'une session à l'autre. */
    var moletteCible: String
        get() = prefs.getString("molette_cible", "VFO") ?: "VFO"
        set(v) { prefs.edit().putString("molette_cible", v).apply() }

    var moletteVfo: Boolean
        get() = prefs.getBoolean("molette_vfo", false)
        set(v) { prefs.edit().putBoolean("molette_vfo", v).apply() }

    /** Le pas de la molette, en hertz : 10, 100 ou 1000. */
    var molettePasHz: Long
        get() = prefs.getLong("molette_pas", 100L)
        set(v) { prefs.edit().putLong("molette_pas", v).apply() }

    var clavierDisposition: String
        get() = prefs.getString("clavier_disposition", "abc") ?: "abc"
        set(v) { prefs.edit().putString("clavier_disposition", v).apply() }

    /**
     * Suivi Doppler automatique de la clé pendant le passage.
     *
     * Ouvert par défaut : sans lui, le signal d'un satellite en orbite basse
     * sort du canal en une minute et demie sur 435 MHz. On le ferme pour
     * écouter une balise fixe, ou pour vérifier à l'oreille de combien le
     * satellite dérive tout seul.
     */
    var sdrDopplerTrack: Boolean
        get() = prefs.getBoolean("sdr_doppler_track", true)
        set(v) { prefs.edit().putBoolean("sdr_doppler_track", v).apply() }

    /**
     * Désaccentuation FM. Fermée par défaut : elle n'a de sens qu'en FM à
     * large bande, et sur les fréquences amateurs elle ne fait qu'étouffer les
     * aigus — une tonalité d'appel à 1 750 Hz en ressortait onze décibels trop
     * bas.
     */
    var sdrDeemph: Boolean
        get() = prefs.getBoolean("sdr_deemph", false)
        set(v) { prefs.edit().putBoolean("sdr_deemph", v).apply() }

    // --- Compass colours (ARGB ints). Defaults match the shipped palette. ---
    var compassTraceColor: Int
        get() = prefs.getInt("cc_trace", 0xFF6C8BFF.toInt())
        set(v) { prefs.edit().putInt("cc_trace", v).apply() }
    var needleFarColor: Int
        get() = prefs.getInt("cc_needle_far", 0xFFD6336C.toInt())
        set(v) { prefs.edit().putInt("cc_needle_far", v).apply() }
    var needleNearColor: Int
        get() = prefs.getInt("cc_needle_near", 0xFFF59F00.toInt())
        set(v) { prefs.edit().putInt("cc_needle_near", v).apply() }
    var needleCloseColor: Int
        get() = prefs.getInt("cc_needle_close", 0xFF2FB344.toInt())
        set(v) { prefs.edit().putInt("cc_needle_close", v).apply() }
    var bubbleFarColor: Int
        get() = prefs.getInt("cc_bubble_far", 0xFFD6336C.toInt())
        set(v) { prefs.edit().putInt("cc_bubble_far", v).apply() }
    var bubbleNearColor: Int
        get() = prefs.getInt("cc_bubble_near", 0xFFF59F00.toInt())
        set(v) { prefs.edit().putInt("cc_bubble_near", v).apply() }
    var bubbleCloseColor: Int
        get() = prefs.getInt("cc_bubble_close", 0xFF2FB344.toInt())
        set(v) { prefs.edit().putInt("cc_bubble_close", v).apply() }

    /** Thickness multiplier for the pass trace + direction arrows on the dial
     *  (0.7 = thin, 1 = normal, 1.6 = thick, 2.4 = extra thick). */
    var compassTraceWidth: Float
        get() = prefs.getFloat("cc_trace_width", 2.4f)
        set(v) { prefs.edit().putFloat("cc_trace_width", v).apply() }

    // Status rings around the dial: hidden when far, then near/close colours.
    var ringAzNearColor: Int
        get() = prefs.getInt("cc_ring_az_near", 0xFFF59F00.toInt())
        set(v) { prefs.edit().putInt("cc_ring_az_near", v).apply() }
    var ringAzCloseColor: Int
        get() = prefs.getInt("cc_ring_az_close", 0xFF2FB344.toInt())
        set(v) { prefs.edit().putInt("cc_ring_az_close", v).apply() }
    var ringElNearColor: Int
        get() = prefs.getInt("cc_ring_el_near", 0xFFF59F00.toInt())
        set(v) { prefs.edit().putInt("cc_ring_el_near", v).apply() }
    var ringElCloseColor: Int
        get() = prefs.getInt("cc_ring_el_close", 0xFF2FB344.toInt())
        set(v) { prefs.edit().putInt("cc_ring_el_close", v).apply() }

    fun resetCompassColors() {
        prefs.edit()
            .remove("cc_trace")
            .remove("cc_needle_far").remove("cc_needle_near").remove("cc_needle_close")
            .remove("cc_bubble_far").remove("cc_bubble_near").remove("cc_bubble_close")
            .remove("cc_ring_az_near").remove("cc_ring_az_close")
            .remove("cc_ring_el_near").remove("cc_ring_el_close")
            .apply()
    }

    var minElevDeg: Int
        get() = prefs.getInt("min_elev", 5)
        set(v) { prefs.edit().putInt("min_elev", v.coerceIn(0, 30)).apply() }

    var skedsToken: String
        get() = prefs.getString("skeds_token", "") ?: ""
        set(v) { prefs.edit().putString("skeds_token", v.trim()).apply() }

    /**
     * Combien d'appuis rapides sur la boussole ouvrent la saisie : **2 par
     * défaut**, 3 pour qui préfère un geste plus délibéré. Rien d'autre n'est
     * permis : un appui simple ouvrirait l'écran chaque fois qu'on touche la
     * boussole.
     *
     * Le défaut était à 3 du temps où le geste **écrivait** un contact : trois
     * appuis ne sont jamais un accident, et un contact posé par mégarde est
     * une ligne à retrouver et à effacer. Depuis la 19.11 le geste n'écrit
     * plus, il ouvre le clavier — se tromper ne coûte plus qu'une flèche de
     * retour. La prudence n'avait plus d'objet, et elle coûtait un appui à
     * chaque contact.
     */
    var logTaps: Int
        get() = prefs.getInt("log_taps", 2).coerceIn(2, 3)
        set(v) { prefs.edit().putInt("log_taps", v.coerceIn(2, 3)).apply() }

    var skedsMutualOnly: Boolean
        get() = prefs.getBoolean("skeds_mutual_only", false)
        set(v) { prefs.edit().putBoolean("skeds_mutual_only", v).apply() }

    var skedsEnabled: Boolean
        get() = prefs.getBoolean("skeds_on", false)
        set(v) { prefs.edit().putBoolean("skeds_on", v).apply() }

    /** Last locator entered on the mutual-sked page (the DX station). */
    var skedOtherLoc: String
        get() = prefs.getString("sked_other_loc", "") ?: ""
        set(v) { prefs.edit().putString("sked_other_loc", v.trim().uppercase()).apply() }

    var potaEnabled: Boolean
        get() = prefs.getBoolean("pota_on", false)
        set(v) { prefs.edit().putBoolean("pota_on", v).apply() }

    var potaRadiusKm: Int
        get() = prefs.getInt("pota_radius", 8)
        set(v) { prefs.edit().putInt("pota_radius", v).apply() }

    var potaToken: String
        get() = prefs.getString("pota_token", "") ?: ""
        set(v) { prefs.edit().putString("pota_token", v.trim()).apply() }

    /** "VECTOR" = offline coastlines+cities. "OSM" = online OpenStreetMap tiles. */
    var mapStyle: String
        get() = prefs.getString("map_style", "OSM") ?: "OSM"
        set(v) { prefs.edit().putString("map_style", v).apply() }

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_on", true)
        set(v) { prefs.edit().putBoolean("notify_on", v).apply() }

    /**
     * D'où vient le son des radiosondes : "SDR" (clé RTL), "MIC" (micro du
     * téléphone devant le haut-parleur du poste) ou "USB" (carte son câblée
     * sur la sortie discriminateur). Le Bluetooth est volontairement absent :
     * son canal mains-libres ne passe pas une modulation à 4800 bauds.
     */
    var sondeSource: String
        get() = prefs.getString("sonde_source", "SDR") ?: "SDR"
        set(v) { prefs.edit().putString("sonde_source", v).apply() }

    /**
     * Modèle de sonde écouté : "AUTO", "RS41", "M20" ou "M10".
     *
     * En automatique les trois décodeurs tournent en parallèle et le filtre FM
     * reste ouvert au plus large. Nommer le modèle éteint les décodeurs
     * inutiles et resserre le filtre sur la largeur exacte du modèle, ce qui
     * vaut deux à trois décibels — la différence entre une sonde décodée à cent
     * kilomètres et une sonde perdue.
     */
    var sondeModel: String
        get() = prefs.getString("sonde_model", "AUTO") ?: "AUTO"
        set(v) { prefs.edit().putString("sonde_model", v).apply() }

    /** Notification scope: "FAV" (all followed sats) or "TARGET" (only bell-marked
     *  passes). "TARGET" by default: only the passes the operator has rung the
     *  bell on are announced, so the phone stays quiet the rest of the time. */
    var notifyMode: String
        get() = prefs.getString("notify_mode", "TARGET") ?: "TARGET"
        set(v) { prefs.edit().putString("notify_mode", v).apply() }

    /** Individually bell-marked pass keys ("catnum@aosMs"). */
    var notifiedPassKeys: Set<String>
        get() = prefs.getStringSet("notify_keys", emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet("notify_keys", v).apply() }

    var notifyLeadMin: Int
        get() = prefs.getInt("notify_lead", 5)
        set(v) { prefs.edit().putInt("notify_lead", v).apply() }

    /** Selected rig model: "IC9700" or "FT817x2" (dual FT-817 full duplex). */
    var rigModel: String
        get() = prefs.getString("rig_model", "IC9700") ?: "IC9700"
        set(v) { prefs.edit().putString("rig_model", v).apply() }

    // Dual FT-817: FTDI adapter serial numbers assigned to each role, + CAT baud
    // (rig menu #14: 4800 default / 9600 / 38400).
    var ft817RxSerial: String
        get() = prefs.getString("ft817_rx_serial", "") ?: ""
        set(v) { prefs.edit().putString("ft817_rx_serial", v).apply() }
    var ft817TxSerial: String
        get() = prefs.getString("ft817_tx_serial", "") ?: ""
        set(v) { prefs.edit().putString("ft817_tx_serial", v).apply() }
    var ft817Baud: Int
        get() = prefs.getInt("ft817_baud", 4800)
        set(v) { prefs.edit().putInt("ft817_baud", v).apply() }

    var civAddress: Int
        get() = prefs.getInt("civ_addr", 0xA2)
        set(v) { prefs.edit().putInt("civ_addr", v).apply() }

    /** Auto-detect CTCSS from the transmitter name (e.g. "CTCSS 67.0"). */
    var ctcssAuto: Boolean
        get() = prefs.getBoolean("ctcss_auto", true)
        set(v) { prefs.edit().putBoolean("ctcss_auto", v).apply() }

    /** CTCSS tone in tenths of Hz (e.g. 670 = 67.0 Hz). 0 = off / use auto. */
    var ctcssTenthHz: Int
        get() = prefs.getInt("ctcss_tenth", 0)
        set(v) { prefs.edit().putInt("ctcss_tenth", v).apply() }

    var civBaud: Int
        get() = prefs.getInt("civ_baud", 115200)
        set(v) { prefs.edit().putInt("civ_baud", v).apply() }

    /**
     * Lequel des adaptateurs USB-série reconnus est le poste.
     *
     * Zéro par défaut, c'est-à-dire le premier, ce qui suffit tant qu'il n'y en
     * a qu'un. Mais une clé SDR branchée en même temps se présente elle aussi
     * comme un adaptateur série : selon l'ordre de branchement, le « premier »
     * n'est plus le poste, et la connexion CAT s'ouvrait sur la clé.
     */
    var civUsbIndex: Int
        get() = prefs.getInt("civ_usb_index", 0)
        set(v) { prefs.edit().putInt("civ_usb_index", v.coerceIn(0, 15)).apply() }

    /**
     * Balayage automatique des ports a la connexion.
     *
     * Le port designe est essaye en premier, puis ses voisins. Cela evite a
     * l'operateur d'avoir a savoir lequel des deux ports de son poste porte le
     * CI-V — il ne le sait generalement pas, et rien sur l'appareil ne le dit.
     * Se desactive pour ceux qui veulent maitriser exactement ce qui est ouvert.
     */
    var civUsbAuto: Boolean
        get() = prefs.getBoolean("civ_usb_auto", true)
        set(v) { prefs.edit().putBoolean("civ_usb_auto", v).apply() }

    /**
     * Poste simulé : le CAT tourne sur une radio qui n'existe pas.
     *
     * Toute la chaîne s'exécute — armement, mode satellite, écriture de la
     * paire, suivi Doppler — mais au bout du fil il y a un IC-9700 en mémoire au
     * lieu d'un câble. C'est fait pour apprendre l'application avant d'avoir la
     * radio devant soi, et pour montrer le suivi en démonstration.
     */
    var catSimulated: Boolean
        get() = prefs.getBoolean("cat_simulated", false)
        set(v) { prefs.edit().putBoolean("cat_simulated", v).apply() }

    /**
     * Journal des trames CAT.
     *
     * Fermé, il ne coûte rien. Ouvert, il garde les deux cents dernières trames
     * avec leur traduction en clair, ce qui permet de trancher entre « la trame
     * n'est pas partie », « elle est partie fausse » et « la radio l'a refusée ».
     */
    var catMonitor: Boolean
        get() = prefs.getBoolean("cat_monitor", false)
        set(v) { prefs.edit().putBoolean("cat_monitor", v).apply() }

    /** UI language: "auto", "fr" or "en". */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(v) { prefs.edit().putString("language", v).apply() }

    /**
     * Thème choisi : 0 sombre, 1 clair, 2 soleil.
     *
     * Distinct de [darkTheme], conservé pour ne pas perdre le choix des
     * installations existantes : au premier lancement après mise à jour, on
     * retombe sur l'ancien réglage.
     */
    var themeIndex: Int
        get() = prefs.getInt("theme_index", if (darkTheme) 0 else 1)
        set(v) {
            prefs.edit().putInt("theme_index", v.coerceIn(0, 2))
                .putBoolean("dark_theme", v == 0).apply()
        }

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(v) { prefs.edit().putBoolean("dark_theme", v).apply() }

    /**
     * D’où vient l’état affiché à côté du satellite : "AMSAT", "SATNOGS" ou
     * les deux.
     *
     * AMSAT par défaut, et seul. Les deux pastilles côte à côte disaient
     * souvent la même chose deux fois et parfois le contraire l’une de
     * l’autre : SatNOGS décrit l’état administratif du satellite, AMSAT dit
     * s’il a été entendu cette semaine. C’est la seconde qui décide si l’on
     * sort l’antenne, et c’est celle-là qu’on garde.
     */
    var statusSource: String
        get() = prefs.getString("status_source", "AMSAT") ?: "AMSAT"
        set(v) { prefs.edit().putString("status_source", v).apply() }

    // ---- Display scaling ----
    // Phones differ wildly in usable width (dp), and Android's own "display
    // size" / "font size" sliders change it again. Left alone, the same screen
    // is roomy on one handset and clipped on the next. When uniformUi is on the
    // whole app is laid out against a fixed reference width and simply scaled,
    // so every tester sees the identical arrangement.

    /** Lay the UI out at a fixed reference width instead of the device's own. */
    var uniformUi: Boolean
        get() = prefs.getBoolean("ui_uniform", true)
        set(v) { prefs.edit().putBoolean("ui_uniform", v).apply() }

    /** -1 = compact (more on screen), 0 = normal, 1 = large (bigger text). */
    var uiScaleStep: Int
        get() = prefs.getInt("ui_scale_step", 0)
        set(v) { prefs.edit().putInt("ui_scale_step", v.coerceIn(-1, 1)).apply() }

    /** Honour the system font-size slider. Off = SatMe keeps its own sizes, so
     *  a phone set to "huge text" no longer bursts the cards. */
    var uiFollowSystemFont: Boolean
        get() = prefs.getBoolean("ui_font_system", false)
        set(v) { prefs.edit().putBoolean("ui_font_system", v).apply() }

    /** Max age (hours) of the on-disk orbital-elements cache before a launch
     *  re-downloads. 0 = always download at startup. */
    var tleCacheHours: Int
        get() = prefs.getInt("tle_cache_hours", 24)
        set(v) { prefs.edit().putInt("tle_cache_hours", v.coerceIn(0, 96)).apply() }

    /** Aiming dial style: "CLASSIC" (bubble+arrow) or "NEEDLE" (big golden needle). */
    var compassStyle: String
        get() = prefs.getString("compass_style", "NEEDLE") ?: "NEEDLE"
        set(v) { prefs.edit().putString("compass_style", v).apply() }

    var compassHeadUp: Boolean
        get() = prefs.getBoolean("compass_head_up", true)
        set(v) { prefs.edit().putBoolean("compass_head_up", v).apply() }

    var showAimModeChips: Boolean
        get() = prefs.getBoolean("show_aim_chips", false)
        set(v) { prefs.edit().putBoolean("show_aim_chips", v).apply() }

    /** "EDGE" = aim with the top edge, screen up (default). "BACK" = camera/AR style. */
    var aimMode: String
        get() = prefs.getString("aim_mode", "EDGE") ?: "EDGE"
        set(v) { prefs.edit().putString("aim_mode", v).apply() }

    // ---- La boussole déportée (module WitMotion en Bluetooth) ----

    /**
     * D'où vient le cap : « TEL » pour les capteurs du téléphone, « BLE » pour
     * le module posé sur la flèche de l'antenne.
     *
     * Le téléphone reste le défaut, et le restera : c'est le seul qui marche
     * sans rien acheter, et une bascule automatique sur un module absent
     * laisserait un cadran muet sans explication.
     */
    var boussoleSource: String
        get() = prefs.getString("boussole_source", "TEL") ?: "TEL"
        set(v) { prefs.edit().putString("boussole_source", v).apply() }

    /** L'adresse du dernier module, pour se rebrancher sans rechercher. */
    var boussoleAdresse: String
        get() = prefs.getString("boussole_adresse", "") ?: ""
        set(v) { prefs.edit().putString("boussole_adresse", v.trim()).apply() }

    var boussoleNom: String
        get() = prefs.getString("boussole_nom", "") ?: ""
        set(v) { prefs.edit().putString("boussole_nom", v).apply() }

    /**
     * Le calage du module, en degrés, à ajouter à son lacet.
     *
     * Le zéro du module dépend de la façon dont le boîtier est vissé sur la
     * flèche. Il change à chaque démontage, exactement comme l'étalonnage que
     * la notice réclame — et il se relève en pointant un azimut connu.
     */
    var boussoleCalage: Float
        get() = prefs.getFloat("boussole_calage", 0f)
        set(v) { prefs.edit().putFloat("boussole_calage", v).apply() }

    /**
     * Le module compte-t-il à l'envers ?
     *
     * Le repère nord-est-ciel tourne dans le sens trigonométrique, un azimut
     * dans celui des aiguilles. Selon le micrologiciel et l'orientation du
     * boîtier, les deux peuvent coïncider ou s'opposer. On ne le devine pas :
     * l'opérateur tourne d'un quart de tour à droite et regarde si le nombre
     * monte.
     */
    /**
     * La convention du module, **mesurée** par le calibrage à deux visées.
     *
     * Un booléen ne suffisait plus : il n'y a pas deux façons de se tromper de
     * repère mais quatre, et laquelle est la bonne dépend du micrologiciel.
     * On les essaie toutes et la seconde visée tranche.
     *
     * Nouvelle clé : l'ancienne rangeait un booléen, et la relire en texte
     * lèverait une exception au premier lancement.
     */
    /**
     * Le relevé de calibrage, une ligne par pose.
     *
     * Rangé plutôt que gardé en mémoire d'écran : la séquence demande neuf
     * poses, donc de se lever, tourner l'antenne, revenir. Perdre le relevé
     * parce qu'on a changé d'écran entre deux gestes serait insupportable.
     */
    /**
     * Le nom et le mot de passe du partage de connexion, pour le QR code qui
     * fait rejoindre le Wi-Fi d'un scan.
     *
     * Saisis à la main, et il n'y a pas d'alternative : depuis Android 10, une
     * application ne peut plus lire la configuration de son propre point
     * d'accès. Ils changent rarement, on les saisit une fois.
     */
    var demoSsid: String
        get() = prefs.getString("demo_ssid", "") ?: ""
        set(v) { prefs.edit().putString("demo_ssid", v.trim()).apply() }

    var demoMotDePasse: String
        get() = prefs.getString("demo_mdp", "") ?: ""
        set(v) { prefs.edit().putString("demo_mdp", v).apply() }

    /**
     * Le point d'accès annoncé en mode démonstration.
     *
     * Le mot de passe est rangé en clair, comme tout ce que contiennent les
     * préférences d'une application : ce n'est pas un secret durable mais celui
     * d'un partage de connexion ouvert le temps d'une démonstration, et qui
     * sera de toute façon affiché en QR code à toute la salle.
     */

    /** La dernière station écoutée à distance : on ne la retape pas. */
    var ecouteAdresse: String
        get() = prefs.getString("ecoute_adresse", "") ?: ""
        set(v) { prefs.edit().putString("ecoute_adresse", v).apply() }

    var boussoleReleves: String
        get() = prefs.getString("boussole_releves", "") ?: ""
        set(v) { prefs.edit().putString("boussole_releves", v).apply() }

    var boussoleConvention: String
        get() = prefs.getString("boussole_convention", "AXES_ECHANGES") ?: "AXES_ECHANGES"
        set(v) { prefs.edit().putString("boussole_convention", v).apply() }

    /**
     * Sur quel axe du module se lit l'élévation : « TANGAGE », « ROULIS », ou
     * « AUCUN ».
     *
     * « AUCUN » par défaut, et ce n'est pas de la timidité : tant que
     * l'opérateur n'a pas dit comment le boîtier est vissé, la seule élévation
     * dont on soit sûr est celle du téléphone. Choisir un axe au hasard
     * donnerait une aiguille qui bouge — donc crédible — et fausse.
     */
    /**
     * La direction de la flèche **dans le repère du boîtier**, écrite « x,y,z ».
     * Vide tant qu'elle n'a pas été apprise.
     *
     * Elle remplace l'ancien trio axe/calage/sens, qui lisait le lacet et une
     * inclinaison séparément. Cette lecture-là se défaisait dès qu'on tournait
     * l'antenne sur son axe pour changer de polarisation : les angles d'Euler
     * ne sont pas trois mesures indépendantes. Une direction, elle, ne bouge
     * pas quand on tourne autour d'elle.
     *
     * L'ancien réglage n'est pas conservé à côté : deux façons de répondre à la
     * même question, dont une fausse, n'en font pas une de rechange.
     */
    var boussoleFleche: String
        get() = prefs.getString("boussole_fleche", "") ?: ""
        set(v) { prefs.edit().putString("boussole_fleche", v).apply() }

    // ---- Operator identity ----

    /** The operator's own callsign, stamped on QRV photos, activation sheets
     *  and the ADIF export. Empty = not set yet. */
    var callsign: String
        get() = prefs.getString("callsign", "") ?: ""
        set(v) { prefs.edit().putString("callsign", v.trim().uppercase()).apply() }

    /**
     * Le champ « Extensions » : les mots-clés qui déverrouillent les fonctions
     * en bêta (voir [fr.f4ioz.satcombo.data.Extensions]). Vide par défaut ; un
     * indicatif contenant F4IOZ ouvre tout sans rien taper.
     */
    var extensionsCode: String
        get() = prefs.getString("extensions_code", "") ?: ""
        set(v) { prefs.edit().putString("extensions_code", v.trim()).apply() }

    // ---- QRV photo overlay options (what gets burned into the picture) ----

    /** Big Maidenhead locator — the whole point of the picture, always on. */
    var photoShowCallsign: Boolean
        get() = prefs.getBoolean("photo_call", true)
        set(v) { prefs.edit().putBoolean("photo_call", v).apply() }

    var photoShowDate: Boolean
        get() = prefs.getBoolean("photo_date", true)
        set(v) { prefs.edit().putBoolean("photo_date", v).apply() }

    /** Distance to the nearest neighbouring grid squares (N/S/E/W borders). */
    var photoShowGrids: Boolean
        get() = prefs.getBoolean("photo_grids", true)
        set(v) { prefs.edit().putBoolean("photo_grids", v).apply() }

    var photoShowCoords: Boolean
        get() = prefs.getBoolean("photo_coords", false)
        set(v) { prefs.edit().putBoolean("photo_coords", v).apply() }

    /** Satellite worked, printed under the locator when an activation is running. */
    var photoShowSat: Boolean
        get() = prefs.getBoolean("photo_sat", false)
        set(v) { prefs.edit().putBoolean("photo_sat", v).apply() }

    /** Polar plot of the pass, drawn in the top-right corner of the picture. */
    var photoShowPolar: Boolean
        get() = prefs.getBoolean("photo_polar", false)
        set(v) { prefs.edit().putBoolean("photo_polar", v).apply() }

    /** Date and time of the pass being worked, always printed in UTC. */
    var photoShowPass: Boolean
        get() = prefs.getBoolean("photo_pass", true)
        set(v) { prefs.edit().putBoolean("photo_pass", v).apply() }

    /**
     * True = the picture carries the 4-character square only ("JN18"), false =
     * the full 6-character locator ("JN18fv"). Some operators announce the big
     * square and nothing else, and the shorter text reads better from afar.
     */
    var photoLoc4: Boolean
        get() = prefs.getBoolean("photo_loc4", false)
        set(v) { prefs.edit().putBoolean("photo_loc4", v).apply() }

    /** Size of the polar plot on the picture, 1.0 = the reference size. */
    var photoPolarScale: Float
        get() = prefs.getFloat("photo_polar_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_polar_scale", v.coerceIn(0.5f, 2.2f)).apply() }

    /** Size of the satellite name written under the polar plot, 1.0 = reference. */
    var photoSatLabelScale: Float
        get() = prefs.getFloat("photo_sat_label_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_sat_label_scale", v.coerceIn(0.5f, 2.5f)).apply() }

    /**
     * L’altitude du point de vue sur la photo QRV, quand on la connaît.
     *
     * Le GPS la rend avec la position ; en position saisie à la main il n’y a
     * rien à écrire et la ligne disparaît d’elle-même. Elle vaut surtout pour
     * les activations en altitude, où le mètre au-dessus de la mer fait partie
     * de l’annonce au même titre que le locator.
     */
    var photoShowAlt: Boolean
        get() = prefs.getBoolean("photo_alt", false)
        set(v) { prefs.edit().putBoolean("photo_alt", v).apply() }

    /**
     * La couleur de l’indicatif sur la photo QRV, en ARGB.
     *
     * L’ambre d’origine se lit sur presque tout, et presque n’est pas tout :
     * sur un coucher de soleil il disparaît. L’opérateur choisit donc parmi
     * quelques couleurs franches, toutes opaques — une couleur translucide sur
     * une photo claire ne donnerait rien de lisible.
     */
    var photoCallColor: Int
        get() = prefs.getInt("photo_call_color", 0xFFFFC65C.toInt())
        set(v) { prefs.edit().putInt("photo_call_color", v).apply() }

    /**
     * La taille de l’indicatif sur la photo QRV, 1.0 = la taille de référence.
     *
     * Bornée : en dessous de 60 % l’indicatif n’est plus lisible une fois la
     * photo réduite par une messagerie, au-dessus de 250 % il mange la moitié
     * du haut de l’image. Le rendu le rétrécit encore s’il dépasse la largeur.
     */
    var photoCallScale: Float
        get() = prefs.getFloat("photo_call_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_call_scale", v.coerceIn(0.6f, 2.5f)).apply() }

    /**
     * How close (in metres) a neighbouring grid square has to be before it is
     * announced after the main locator ("JN18cx / JN18cw"). 0 disables it.
     */
    var nearGridMeters: Int
        get() = prefs.getInt("near_grid_m", 100)
        set(v) { prefs.edit().putInt("near_grid_m", v.coerceIn(0, 5000)).apply() }

    /**
     * Combien des huit carrés qui touchent le nôtre sont imprimés sur la photo
     * QRV, du plus proche au plus lointain. Zéro n'en écrit aucun, huit les
     * écrit tous ; quatre reprend le nord / sud / est / ouest d'origine.
     */
    var photoNearCount: Int
        get() = prefs.getInt("photo_near_count", 4)
        set(v) { prefs.edit().putInt("photo_near_count", v.coerceIn(0, 8)).apply() }

    /**
     * Le système d'unités des distances, altitudes et vitesses : métrique,
     * anglo-saxon ou nautique. Voir [fr.f4ioz.satcombo.data.Units].
     */
    var units: String
        get() = Units.normalize(prefs.getString("units", Units.METRIC))
        set(v) { prefs.edit().putString("units", Units.normalize(v)).apply() }

    /** Code du drapeau placé devant l'indicatif sur la photo QRV, vide = aucun. */
    /** La silhouette du pays sur la photo QRV. */
    /**
     * Forcée à l'affichage une fois, à la mise à jour qui l'a rendue fiable :
     * qui l'avait éteinte pendant qu'elle ne suivait pas la position l'a
     * éteinte pour une raison qui n'existe plus. Le choix reste libre ensuite,
     * le drapeau `carte_forcee_1` n'étant posé qu'une fois.
     */
    var photoShowPota: Boolean
        get() = prefs.getBoolean("photo_show_pota", false)
        set(v) { prefs.edit().putBoolean("photo_show_pota", v).apply() }

    var photoShowCarte: Boolean
        get() {
            if (!prefs.getBoolean("carte_forcee_1", false)) {
                prefs.edit().putBoolean("carte_forcee_1", true)
                    .putBoolean("photo_show_carte", true).apply()
            }
            return prefs.getBoolean("photo_show_carte", true)
        }
        set(v) { prefs.edit().putBoolean("photo_show_carte", v).apply() }

    var photoCarteTaille: Float
        get() = prefs.getFloat("photo_carte_taille", 0.42f)
        set(v) { prefs.edit().putFloat("photo_carte_taille", v.coerceIn(0.15f, 0.9f)).apply() }

    var photoCarteX: Float
        get() = prefs.getFloat("photo_carte_x", 0.5f)
        set(v) { prefs.edit().putFloat("photo_carte_x", v.coerceIn(0.1f, 0.9f)).apply() }

    var photoCarteY: Float
        get() = prefs.getFloat("photo_carte_y", 0.52f)
        set(v) { prefs.edit().putFloat("photo_carte_y", v.coerceIn(0.1f, 0.9f)).apply() }

    /** Taille de la ligne POTA sur la photo, 0,6 à 2,5. */
    var photoPotaTaille: Float
        get() = prefs.getFloat("photo_pota_taille", 1f)
        set(v) { prefs.edit().putFloat("photo_pota_taille", v.coerceIn(0.6f, 2.5f)).apply() }

    /** De combien la ligne POTA remonte, en part de la hauteur. */
    var photoPotaMonte: Float
        get() = prefs.getFloat("photo_pota_monte", 0f)
        set(v) { prefs.edit().putFloat("photo_pota_monte", v.coerceIn(0f, 0.6f)).apply() }

    /** Les fréquences montée/descente sur la photo QRV. */
    var photoShowQrg: Boolean
        get() = prefs.getBoolean("photo_show_qrg", false)
        set(v) { prefs.edit().putBoolean("photo_show_qrg", v).apply() }

    /** La fréquence annoncée, saisie à la main (texte libre). */
    var photoQrgTexte: String
        get() = prefs.getString("photo_qrg_texte", "") ?: ""
        set(v) { prefs.edit().putString("photo_qrg_texte", v.trim().take(24)).apply() }

    /** Taille de la ligne date + fréquence. */
    var photoPassScale: Float
        get() = prefs.getFloat("photo_pass_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_pass_scale", v.coerceIn(0.6f, 2.5f)).apply() }

    var photoQrgScale: Float
        get() = prefs.getFloat("photo_qrg_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_qrg_scale", v.coerceIn(0.5f, 2.5f)).apply() }

    /** Le nom du parc sous la référence POTA (le numéro reste). */
    var photoPotaNom: Boolean
        get() = prefs.getBoolean("photo_pota_nom", true)
        set(v) { prefs.edit().putBoolean("photo_pota_nom", v).apply() }

    /** Sans photo, le fond de la carte QRV : couleur unie (ARGB). */
    var photoFondUni: Int
        get() = prefs.getInt("photo_fond_uni", 0xFF102030.toInt())
        set(v) { prefs.edit().putInt("photo_fond_uni", v).apply() }

    /** Le bord de la carte se fond dans la photo. */
    var photoCarteFondu: Boolean
        get() = prefs.getBoolean("photo_carte_fondu", true)
        set(v) { prefs.edit().putBoolean("photo_carte_fondu", v).apply() }

    /** Couleur de l'aplat de la carte (ARGB). */
    var photoCarteCouleur: Int
        get() = prefs.getInt("photo_carte_couleur", 0x66FFFFFF)
        set(v) { prefs.edit().putInt("photo_carte_couleur", v).apply() }

    /** Le bandeau « Dans la zone POTA » sur la page d'accueil. */
    var potaBandeauAccueil: Boolean
        get() = prefs.getBoolean("pota_bandeau_accueil", true)
        set(v) { prefs.edit().putBoolean("pota_bandeau_accueil", v).apply() }

    /** « PAYS » ou « ZONE » : la silhouette du pays, ou l'emprise du parc POTA. */
    var photoCarteContenu: String
        get() = prefs.getString("photo_carte_contenu", "PAYS") ?: "PAYS"
        set(v) { prefs.edit().putString("photo_carte_contenu", v).apply() }

    /** « DRAPEAU » ou « UNI ». */
    var photoCarteRemplissage: String
        get() = prefs.getString("photo_carte_remp", "DRAPEAU") ?: "DRAPEAU"
        set(v) { prefs.edit().putString("photo_carte_remp", v).apply() }

    /**
     * La base interne d'indicatifs : le carnet satellite de F4IOZ, embarqué
     * pour que le clavier propose noms et carrés dès la première installation.
     * Désactivable — notamment quand on importe son propre ADIF et qu'on ne
     * veut que lui.
     */
    // `baseInterneIndicatifs` a été retiré avec la base embarquée.

    var photoFlag: String
        get() = prefs.getString("photo_flag", "") ?: ""
        set(v) { prefs.edit().putString("photo_flag", v.trim().uppercase()).apply() }

    /** Code du drapeau placé à droite de l'indicatif, vide = aucun. */
    var photoFlagRight: String
        get() = prefs.getString("photo_flag_right", "") ?: ""
        set(v) { prefs.edit().putString("photo_flag_right", v.trim().uppercase()).apply() }

    /** True once the operator has asked not to be reminded about the callsign. */
    var callsignPromptOff: Boolean
        get() = prefs.getBoolean("callsign_prompt_off", false)
        set(v) { prefs.edit().putBoolean("callsign_prompt_off", v).apply() }

    /**
     * Absolute path of the file handed to the camera app, empty when no capture
     * is in flight. Kept on disk and not in memory: the camera app is a separate
     * process and Android is free to kill ours while it is on top — turning the
     * phone to shoot in landscape is enough. Without this the picture came back
     * and there was nothing left to say where it had been written.
     */
    var pendingCapture: String
        get() = prefs.getString("pending_capture", "") ?: ""
        set(v) { prefs.edit().putString("pending_capture", v).apply() }

    /**
     * The versionCode the operator answered "later" to. Anything newer asks
     * again; that same version never does. A refused update must stay refused
     * — a dialog on every launch is how an app gets uninstalled.
     */
    var updateSkipped: Int
        get() = prefs.getInt("update_skipped", 0)
        set(v) { prefs.edit().putInt("update_skipped", v).apply() }

    /**
     * How many hours of already-finished passes the lists keep showing (0, 3, 6
     * or 12). Handy after a session: the pass you have just worked is still
     * there, with its polar plot, to write up the log.
     */
    var pastPassHours: Int
        get() = prefs.getInt("past_pass_hours", 0)
        set(v) { prefs.edit().putInt("past_pass_hours", v.coerceIn(0, 12)).apply() }

    // ===================== rotor azimut / élévation =====================
    //
    // Le rotor est la seule fonction de SatMe qui déplace physiquement quelque
    // chose. Chacun de ces réglages décrit une limite mécanique, et un réglage
    // faux ne donne pas un affichage bizarre : il donne un câble arraché. D'où
    // les bornes posées ici, à l'écriture, plutôt que dans l'écran.

    /** Pilotage du rotor demandé par l'opérateur. */
    var rotorEnabled: Boolean
        get() = prefs.getBoolean("rotor_enabled", false)
        set(v) { prefs.edit().putBoolean("rotor_enabled", v).apply() }

    /** Type de liaison : "GS232" (série USB) ou "ROTCTLD" (réseau, Hamlib). */
    var rotorLink: String
        get() = prefs.getString("rotor_link", "GS232") ?: "GS232"
        set(v) { prefs.edit().putString("rotor_link", v).apply() }

    var rotorUsbIndex: Int
        get() = prefs.getInt("rotor_usb_index", 0)
        set(v) { prefs.edit().putInt("rotor_usb_index", v.coerceIn(0, 7)).apply() }

    var rotorBaud: Int
        get() = prefs.getInt("rotor_baud", 9600)
        set(v) { prefs.edit().putInt("rotor_baud", v).apply() }

    var rotorHost: String
        get() = prefs.getString("rotor_host", "192.168.1.10") ?: "192.168.1.10"
        set(v) { prefs.edit().putString("rotor_host", v).apply() }

    var rotorPort: Int
        get() = prefs.getInt("rotor_port", 4533)
        set(v) { prefs.edit().putInt("rotor_port", v.coerceIn(1, 65535)).apply() }

    var rotorMaxAz: Int
        get() = prefs.getInt("rotor_max_az", 450)
        set(v) { prefs.edit().putInt("rotor_max_az", v.coerceIn(360, 540)).apply() }

    /**
     * Où se trouve la butée mécanique du mât : « NORTH » ou « SOUTH ».
     *
     * Un G-5500 sorti du carton bute au nord, et c'est le pire endroit possible
     * pour un satellite en orbite polaire : la moitié des passages traversent
     * précisément là. Beaucoup de stations remontent le mât butée au sud, et
     * l'application n'a aucun moyen de le deviner — d'où ce réglage.
     */
    var rotorAzStop: String
        get() = prefs.getString("rotor_az_stop", "NORTH") ?: "NORTH"
        set(v) {
            prefs.edit().putString("rotor_az_stop", if (v == "SOUTH") "SOUTH" else "NORTH").apply()
        }

    /**
     * Le contrôleur compte-t-il ses azimuts depuis sa butée plutôt que du nord ?
     *
     * Certains boîtiers affichent zéro à la butée. La conversion ne se fait que
     * sur la trame qui part et sur celle qui revient : partout ailleurs, dans
     * les calculs comme à l'écran, un azimut est un azimut vrai.
     */
    var rotorAzFromStop: Boolean
        get() = prefs.getBoolean("rotor_az_from_stop", false)
        set(v) { prefs.edit().putBoolean("rotor_az_from_stop", v).apply() }

    /** Écart de pointage toléré avant que le bandeau ne le dise, en degrés. */
    var rotorMaxError: Int
        get() = prefs.getInt("rotor_max_error", 15)
        set(v) { prefs.edit().putInt("rotor_max_error", v.coerceIn(1, 60)).apply() }


    var rotorMaxEl: Int
        get() = prefs.getInt("rotor_max_el", 90)
        set(v) { prefs.edit().putInt("rotor_max_el", v.coerceIn(90, 180)).apply() }

    var rotorDeadband: Int
        get() = prefs.getInt("rotor_deadband", 2)
        set(v) { prefs.edit().putInt("rotor_deadband", v.coerceIn(1, 15)).apply() }

    /**
     * Le mât n'a pas d'axe d'élévation.
     *
     * Cela ne change rien au pilotage — un rotor d'azimut seul ignore
     * simplement la seconde consigne — mais cela change la boussole : c'est le
     * téléphone qui continue de donner l'élévation, puisque personne d'autre ne
     * la connaît.
     */
    var rotorAzOnly: Boolean
        get() = prefs.getBoolean("rotor_az_only", false)
        set(v) { prefs.edit().putBoolean("rotor_az_only", v).apply() }

    /**
     * Suivre le Doppler en réception, et pas seulement en émission.
     *
     * Allumé par défaut : c'est ce qu'on attend d'un logiciel de satellite, et
     * son absence était un défaut, non un choix. Le réglage existe pour
     * l'opérateur qui préfère garder la molette de réception entièrement à
     * lui — en CW étroite, par exemple, où le moindre saut se remarque.
     */
    var catRxDoppler: Boolean
        get() = prefs.getBoolean("cat_rx_doppler", true)
        set(v) { prefs.edit().putBoolean("cat_rx_doppler", v).apply() }


    var rotorFlip: Boolean
        get() = prefs.getBoolean("rotor_flip", false)
        set(v) { prefs.edit().putBoolean("rotor_flip", v).apply() }

    var rotorParkAz: Int
        get() = prefs.getInt("rotor_park_az", 0)
        set(v) { prefs.edit().putInt("rotor_park_az", v.coerceIn(0, 540)).apply() }

    var rotorParkEl: Int
        get() = prefs.getInt("rotor_park_el", 0)
        set(v) { prefs.edit().putInt("rotor_park_el", v.coerceIn(0, 180)).apply() }

    /** En dessous de cette élévation, le mât rentre au garage. */
    var rotorMinEl: Int
        get() = prefs.getInt("rotor_min_el", 0)
        set(v) { prefs.edit().putInt("rotor_min_el", v.coerceIn(0, 30)).apply() }

    /**
     * Combien de minutes avant l'acquisition le mât va attendre le satellite.
     *
     * « Il faut qu'il soit positionné avant le début du passage, x minutes en
     * paramètre. » Un mât met une bonne minute à faire un demi-tour ; parti au
     * moment du lever, il arrive quand le satellite est déjà haut. Zéro
     * désactive le pré-pointage.
     */
    var rotorPreAos: Int
        get() = prefs.getInt("rotor_pre_aos", 3)
        set(v) { prefs.edit().putInt("rotor_pre_aos", v.coerceIn(0, 30)).apply() }

    /** Rotor simulé : tout marche, sauf que rien ne tourne. */
    var rotorSim: Boolean
        get() = prefs.getBoolean("rotor_sim", false)
        set(v) { prefs.edit().putBoolean("rotor_sim", v).apply() }

    // ------------------------------------------------------------------
    // Convertisseurs (LNB en descente, transverter en montée)
    //
    // Deux boîtiers indépendants, parce que c'est ainsi qu'ils sont câblés :
    // le LNB devant le récepteur, le transverter derrière l'émetteur. Sur
    // QO-100 le montage courant les fait servir en même temps sur deux
    // appareils différents — la descente 10 GHz dans une clé SDR, la montée
    // 13 cm depuis le 432 d'un IC-9700 — d'où l'aiguillage [convRxPoste] /
    // [convRxCle], qui dit à quelle chaîne la descente s'applique.
    // ------------------------------------------------------------------

    /** Convertisseur de descente en service. */
    var convRxActif: Boolean
        get() = prefs.getBoolean("conv_rx_actif", false)
        set(v) { prefs.edit().putBoolean("conv_rx_actif", v).apply() }

    /** Oscillateur local de la descente, en hertz. */
    var convRxOlHz: Long
        get() = prefs.getLong("conv_rx_ol", 9_750_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_ol", v.coerceIn(0L, 30_000_000_000L)).apply() }

    /** Injection haute en descente : le spectre reçu est retourné. */
    var convRxInverseur: Boolean
        get() = prefs.getBoolean("conv_rx_inv", false)
        set(v) { prefs.edit().putBoolean("conv_rx_inv", v).apply() }

    var convRxBasHz: Long
        get() = prefs.getLong("conv_rx_bas", 10_400_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_bas", v.coerceAtLeast(0L)).apply() }

    var convRxHautHz: Long
        get() = prefs.getLong("conv_rx_haut", 10_800_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_haut", v.coerceAtLeast(0L)).apply() }

    /** La descente passe par le poste piloté en CAT. */
    var convRxPoste: Boolean
        get() = prefs.getBoolean("conv_rx_poste", false)
        set(v) { prefs.edit().putBoolean("conv_rx_poste", v).apply() }

    /** La descente passe par la clé SDR. C'est le cas courant sur QO-100. */
    var convRxCle: Boolean
        get() = prefs.getBoolean("conv_rx_cle", true)
        set(v) { prefs.edit().putBoolean("conv_rx_cle", v).apply() }

    /** Convertisseur de montée en service. */
    var convTxActif: Boolean
        get() = prefs.getBoolean("conv_tx_actif", false)
        set(v) { prefs.edit().putBoolean("conv_tx_actif", v).apply() }

    /** Oscillateur local de la montée, en hertz. */
    var convTxOlHz: Long
        get() = prefs.getLong("conv_tx_ol", 1_968_000_000L)
        set(v) { prefs.edit().putLong("conv_tx_ol", v.coerceIn(0L, 30_000_000_000L)).apply() }

    var convTxInverseur: Boolean
        get() = prefs.getBoolean("conv_tx_inv", false)
        set(v) { prefs.edit().putBoolean("conv_tx_inv", v).apply() }

    var convTxBasHz: Long
        get() = prefs.getLong("conv_tx_bas", 2_390_000_000L)
        set(v) { prefs.edit().putLong("conv_tx_bas", v.coerceAtLeast(0L)).apply() }

    var convTxHautHz: Long
        get() = prefs.getLong("conv_tx_haut", 2_450_000_000L)
        set(v) { prefs.edit().putLong("conv_tx_haut", v.coerceAtLeast(0L)).apply() }
}
