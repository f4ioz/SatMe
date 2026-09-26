/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context

enum class LocationMode { AUTO, MANUAL }

/** Persists QTH settings: auto GPS or manual Maidenhead locator. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("satcombo_settings", Context.MODE_PRIVATE)

    /**
     * One-off migrations of settings already written.
     *
     * Changing a default only affects new installs: an existing value is read
     * as is. So the value must be rewritten — once only, or the operator's new
     * choice would be overwritten at every start. `reprises` counts how far we
     * got; a new migration is appended and increments it.
     */
    init {
        val faites = prefs.getInt("reprises", 0)
        if (faites < 1) {
            // 19.13: double tap opens the keypad. The gesture no longer writes
            // anything since 19.11, so three taps protect nothing.
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

    /** Capture route for the pass recorder: "MIC", "BT" (HFP/SCO) or "USB". */
    var recorderSource: String
        get() = prefs.getString("recorder_source", "MIC") ?: "MIC"
        set(v) { prefs.edit().putString("recorder_source", v).apply() }

    /** Capture with AudioSource.UNPROCESSED when the device supports it: no AGC
     *  and no noise suppression on audio the rig has already processed. */
    /** Show the audio spectrum while recording. */
    var monitorSpectre: Boolean
        get() = prefs.getBoolean("monitor_spectre", false)
        set(v) { prefs.edit().putBoolean("monitor_spectre", v).apply() }

    /** Loop captured audio back to the phone speaker (monitoring). */
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
     * SSTV mode forced on the decoder, or empty to follow the VIS header. A
     * weak signal's header can be misread with valid parity, giving a
     * scrambled picture; an operator who knows the mode can say so.
     */
    var sstvForcedMode: String
        get() = prefs.getString("sstv_forced_mode", "") ?: ""
        set(v) { prefs.edit().putString("sstv_forced_mode", v).apply() }

    /** Watch received audio for an APT picture (NOAA, 137 MHz). Unlike SSTV
     *  there is no header to wait for, so APT decodes continuously once on:
     *  off by default, switched on before a NOAA pass. */
    var aptEnabled: Boolean
        get() = prefs.getBoolean("apt_enabled", false)
        set(v) { prefs.edit().putBoolean("apt_enabled", v).apply() }

    /**
     * What the pass page's picture strip shows: "SSTV" or "NOAA". Both
     * decoders may run, but there is room for one picture. Remembered: NOAA
     * sessions span several passes.
     */
    var rxImageMode: String
        get() = prefs.getString("rx_image_mode", "SSTV") ?: "SSTV"
        set(v) { prefs.edit().putString("rx_image_mode", v).apply() }

    // --- RTL-SDR dongle (beta) ----------------------------------------------

    /** Tuner gain in tenths of dB; -1 = automatic. */
    var sdrGainTenthDb: Int
        get() = prefs.getInt("sdr_gain", -1)
        set(v) { prefs.edit().putInt("sdr_gain", v).apply() }

    /** RTL2832U digital AGC, on top of tuner gain. */
    var sdrAgc: Boolean
        get() = prefs.getBoolean("sdr_agc", false)
        set(v) { prefs.edit().putBoolean("sdr_agc", v).apply() }

    /** Dongle crystal error in ppm. Cheap dongles are off by tens of ppm,
     *  several kHz at UHF. */
    var sdrPpm: Int
        get() = prefs.getInt("sdr_ppm", 0)
        set(v) { prefs.edit().putInt("sdr_ppm", v).apply() }

    /** Decode SSTV straight from the dongle. */
    var sdrSstv: Boolean
        get() = prefs.getBoolean("sdr_sstv", true)
        set(v) { prefs.edit().putBoolean("sdr_sstv", v).apply() }

    /** Record an MP3 during SDR reception. */
    var sdrRecord: Boolean
        get() = prefs.getBoolean("sdr_record", true)
        set(v) { prefs.edit().putBoolean("sdr_record", v).apply() }

    /** Play demodulated audio on headphones / speaker. */
    var sdrAudio: Boolean
        get() = prefs.getBoolean("sdr_audio", true)
        set(v) { prefs.edit().putBoolean("sdr_audio", v).apply() }

    /** Demodulation mode: NFM, USB, LSB or AM. */
    var sdrMode: String
        get() = prefs.getString("sdr_mode", "NFM") ?: "NFM"
        set(v) { prefs.edit().putString("sdr_mode", v).apply() }

    /** Channel width in Hz; 0 lets the mode decide. */
    var sdrBandwidthHz: Int
        get() = prefs.getInt("sdr_bw", 0)
        set(v) { prefs.edit().putInt("sdr_bw", v).apply() }

    /** Squelch threshold in dBFS; -120 disables it. */
    var sdrSquelchDb: Int
        get() = prefs.getInt("sdr_squelch", -120)
        set(v) { prefs.edit().putInt("sdr_squelch", v).apply() }

    /** Spectrum display span, in Hz. */
    /** Small waterfall under the compass on the pass page. */
    var sdrInlineWaterfall: Boolean
        get() = prefs.getBoolean("sdr_inline_wf", true)
        set(v) { prefs.edit().putBoolean("sdr_inline_wf", v).apply() }

    /**
     * Last radiosonde frequency in Hz. Clamped to the meteo band: a corrupt
     * setting must not send the dongle onto distress beacons.
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

    // ------------------------------------------------------------ fine tuning
    // Three independent aids rather than one choice: the magnifier shows,
    // the vernier moves, voice netting decides. Each can be wanted without
    // the others (e.g. vernier alone on a narrow screen).

    /** Magnifier: a second spectrum view a few kHz wide. */
    var sdrLoupe: Boolean
        get() = prefs.getBoolean("sdr_loupe", true)
        set(v) { prefs.edit().putBoolean("sdr_loupe", v).apply() }

    /** Magnifier span, in Hz. */
    var sdrLoupeSpanHz: Int
        get() = prefs.getInt("sdr_loupe_span", 5_000)
        set(v) { prefs.edit().putInt("sdr_loupe_span", v).apply() }

    /** Vernier: scrolling dial for relative tuning by finger. */
    var sdrVernier: Boolean
        get() = prefs.getBoolean("sdr_vernier", true)
        set(v) { prefs.edit().putBoolean("sdr_vernier", v).apply() }

    /** Vernier ratio, Hz per centimetre of swipe. */
    var sdrVernierHzParCm: Int
        get() = prefs.getInt("sdr_vernier_ratio", 200)
        set(v) { prefs.edit().putInt("sdr_vernier_ratio", v).apply() }

    /** Voice netting button (sideband only). */
    var sdrCalageVoix: Boolean
        get() = prefs.getBoolean("sdr_calage_voix", true)
        set(v) { prefs.edit().putBoolean("sdr_calage_voix", v).apply() }

    /**
     * Callsign keypad for left-hand use. One-handed, the thumb reaches its own
     * edge easily and the far one poorly: Enter and Delete must sit on the
     * holding hand's side.
     */
    /** Idle time before the software takes the dial back, in ms. */
    /**
     * TX border indicator, and the CAT polling behind it. It shares the serial
     * link with Doppler tracking: off for the most responsive tracking, on to
     * see when you transmit. The operator's call.
     */
    var liseréEmission: Boolean
        get() = prefs.getBoolean("cat_liseret_tx", true)
        set(v) { prefs.edit().putBoolean("cat_liseret_tx", v).apply() }

    /** TX polling interval, in ms. */
    var sondeTxMs: Int
        get() = prefs.getInt("cat_sonde_tx_ms", 500)
        set(v) { prefs.edit().putInt("cat_sonde_tx_ms", v.coerceIn(250, 3000)).apply() }

    /** TX re-syncs without waiting for the takeover delay. */
    var txSuitVite: Boolean
        get() = prefs.getBoolean("cat_tx_suit_vite", true)
        set(v) { prefs.edit().putBoolean("cat_tx_suit_vite", v).apply() }

    /** Online log: Wavelog or Cloudlog, same API. */
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
     * Wavelog station profile id, for uploading contacts. Separate from key
     * and slug, which only query: grid painting works without uploading.
     */
    var carnetProfil: String
        get() = prefs.getString("carnet_profil", "") ?: ""
        set(v) { prefs.edit().putString("carnet_profil", v.trim()).apply() }

    /**
     * Last contact fetched from the online log: makes fetching incremental,
     * as the server asks (instances rate-limit). Reset to zero to reload
     * everything when the index looks incomplete.
     */
    /** The set of profiles the fetch cursor refers to. */
    var carnetProfilsVus: String
        get() = prefs.getString("carnet_profils_vus", "") ?: ""
        set(v) { prefs.edit().putString("carnet_profils_vus", v).apply() }

    var carnetDernierId: Long
        get() = prefs.getLong("carnet_dernier_id", 0L)
        set(v) { prefs.edit().putLong("carnet_dernier_id", v).apply() }

    /**
     * What to fetch: "sat", "phonie" (phone), "cw" or "tout" (all). Satellite
     * by default: a station met once on 40 m does not belong in the keypad.
     *
     * **Changing it resets the incremental cursor.** Otherwise switching from
     * "sat" to "tout" would only bring contacts newer than the last fetch, and
     * the HF history would silently stay missing.
     */
    /**
     * Operator's QO-100 memories, as JSON: unbounded count, and numbered
     * preference keys break when one is removed from the middle.
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
     * Named QO-100 conversion chains. Kept with the QO-100 settings, not the
     * general converters: a 10 345 MHz LO means nothing on RS-44.
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

    /** Name of the active chain. */
    /** Receive devices and their offset, as plain text. */
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
     * Sweep all of QO-100 rather than the chosen transponder only. The clamp
     * keeps the uplink inside the transponder, but also prevents **listening**
     * elsewhere (a beacon, the wideband transponder). Off by default: the
     * guard stays unless asked.
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

    /** LoTW: ARRL account callsign and password. */
    /** Paint worked and activated grid squares on the maps. */
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

    /** The TX dial acts as the shift control. */
    var catTxVfoShift: Boolean
        get() = prefs.getBoolean("cat_tx_vfo_shift", false)
        set(v) { prefs.edit().putBoolean("cat_tx_vfo_shift", v).apply() }

    var clavierMainGauche: Boolean
        get() = prefs.getBoolean("clavier_main_gauche", false)
        set(v) { prefs.edit().putBoolean("clavier_main_gauche", v).apply() }

    /**
     * Keypad layout: "abc", "azerty" or "qwerty". Alphabetical by default (no
     * habit needed); the others give familiar ten-key rows at the cost of
     * narrower keys.
     */
    /**
     * Does the USB knob drive the VFO? Off by default: these knobs send volume
     * keys, and users without one must lose nothing.
     */
    // ---- The three-button box ----

    /**
     * The three key codes, learned rather than typed. Zero means "not learned"
     * and never triggers: otherwise three unlearned keys would share one code
     * and the knob would switch target on every press.
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

    /** What each key selects: VFO, SHIFT_RX or SHIFT_TX. */
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
     * The knob push button and its action. Code 164 (Mute) by default: what
     * most of these boxes send, and the former fixed behaviour.
     */
    var macroCodeD: Int
        get() = prefs.getInt("macro_code_d", 164)
        set(v) { prefs.edit().putInt("macro_code_d", v).apply() }

    /** "PAS" (step), "CIBLE" (target) or "ZERO". */
    var macroActionD: String
        get() = prefs.getString("macro_action_d", "PAS") ?: "PAS"
        set(v) { prefs.edit().putString("macro_action_d", v).apply() }

    /** Current target, kept across sessions. */
    var moletteCible: String
        get() = prefs.getString("molette_cible", "VFO") ?: "VFO"
        set(v) { prefs.edit().putString("molette_cible", v).apply() }

    var moletteVfo: Boolean
        get() = prefs.getBoolean("molette_vfo", false)
        set(v) { prefs.edit().putBoolean("molette_vfo", v).apply() }

    /** Knob step in Hz: 10, 100 or 1000. */
    var molettePasHz: Long
        get() = prefs.getLong("molette_pas", 100L)
        set(v) { prefs.edit().putLong("molette_pas", v).apply() }

    var clavierDisposition: String
        get() = prefs.getString("clavier_disposition", "abc") ?: "abc"
        set(v) { prefs.edit().putString("clavier_disposition", v).apply() }

    /**
     * Automatic Doppler tracking on the dongle during a pass. On by default:
     * without it a LEO signal leaves the channel in about 90 s at 435 MHz.
     * Turn off for a fixed beacon or to hear the drift by ear.
     */
    var sdrDopplerTrack: Boolean
        get() = prefs.getBoolean("sdr_doppler_track", true)
        set(v) { prefs.edit().putBoolean("sdr_doppler_track", v).apply() }

    /**
     * FM de-emphasis. Off by default: it only makes sense for wideband FM; on
     * amateur narrow FM it muffles the highs (a 1750 Hz tone came out 11 dB
     * low).
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
     * Quick taps on the compass that open logging: **2 by default**, 3 for a
     * more deliberate gesture. Nothing else: a single tap would open it on
     * every touch. (It was 3 when the gesture wrote a contact; since 19.11 it
     * only opens the keypad, so a mistake costs one back press.)
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
     * Radiosonde audio source: "SDR" (RTL dongle), "MIC" (phone mic by the rig
     * speaker) or "USB" (sound card on the discriminator output). No
     * Bluetooth: the hands-free channel cannot carry 4800 baud.
     */
    var sondeSource: String
        get() = prefs.getString("sonde_source", "SDR") ?: "SDR"
        set(v) { prefs.edit().putString("sonde_source", v).apply() }

    /**
     * Sonde model: "AUTO", "RS41", "M20" or "M10". AUTO runs all three
     * decoders with the widest FM filter; naming the model narrows the filter
     * to its exact width — worth 2–3 dB, the difference between decoding at
     * 100 km and losing the sonde.
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
     * Which recognised USB-serial adapter is the rig. Zero (the first) is fine
     * with one adapter, but an SDR dongle also shows up as serial: depending on
     * plug order, CAT could open on the dongle.
     */
    var civUsbIndex: Int
        get() = prefs.getInt("civ_usb_index", 0)
        set(v) { prefs.edit().putInt("civ_usb_index", v.coerceIn(0, 15)).apply() }

    /**
     * Automatic port scan on connect: the chosen port first, then its
     * neighbours, so the operator need not know which of the rig's two ports
     * carries CI-V (nothing on the rig says). Can be disabled for full control.
     */
    var civUsbAuto: Boolean
        get() = prefs.getBoolean("civ_usb_auto", true)
        set(v) { prefs.edit().putBoolean("civ_usb_auto", v).apply() }

    /**
     * Simulated rig: the whole CAT chain runs (arming, satellite mode, frequency
     * pair, Doppler) against an in-memory IC-9700. For learning the app without
     * the radio, and for demonstrations.
     */
    var catSimulated: Boolean
        get() = prefs.getBoolean("cat_simulated", false)
        set(v) { prefs.edit().putBoolean("cat_simulated", v).apply() }

    /**
     * CAT frame log. Free when off; when on, keeps the last 200 frames with a
     * plain-language decode, to tell "not sent" from "sent wrong" from
     * "refused by the radio".
     */
    var catMonitor: Boolean
        get() = prefs.getBoolean("cat_monitor", false)
        set(v) { prefs.edit().putBoolean("cat_monitor", v).apply() }

    /** UI language: "auto", "fr" or "en". */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(v) { prefs.edit().putString("language", v).apply() }

    /**
     * Theme: 0 dark, 1 light, 2 sunlight. Separate from [darkTheme], which is
     * kept so existing installs fall back on their old choice after updating.
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
     * Source of the status shown next to the satellite: "AMSAT", "SATNOGS" or
     * both. AMSAT alone by default: SatNOGS gives the administrative status,
     * AMSAT whether it was heard this week — which is what decides whether to
     * take the antenna out. Side by side they often disagreed.
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

    // ---- Remote compass (WitMotion module over Bluetooth) ----

    /**
     * Heading source: "TEL" (phone sensors) or "BLE" (module on the boom).
     * The phone stays the default: it needs no purchase, and auto-switching to
     * an absent module would leave a silent dial.
     */
    var boussoleSource: String
        get() = prefs.getString("boussole_source", "TEL") ?: "TEL"
        set(v) { prefs.edit().putString("boussole_source", v).apply() }

    /** Address of the last module, to reconnect without scanning. */
    var boussoleAdresse: String
        get() = prefs.getString("boussole_adresse", "") ?: ""
        set(v) { prefs.edit().putString("boussole_adresse", v.trim()).apply() }

    var boussoleNom: String
        get() = prefs.getString("boussole_nom", "") ?: ""
        set(v) { prefs.edit().putString("boussole_nom", v).apply() }

    /**
     * Module offset in degrees, added to its yaw. Depends on how the case is
     * mounted, so it changes at every remount; measured by pointing at a known
     * azimuth.
     */
    var boussoleCalage: Float
        get() = prefs.getFloat("boussole_calage", 0f)
        set(v) { prefs.edit().putFloat("boussole_calage", v).apply() }

    /**
     * Does the module count backwards? Its frame may turn counter-clockwise
     * while azimuth turns clockwise, depending on firmware and mounting. Not
     * guessed: turn a quarter right and see whether the number rises.
     */
    /**
     * Module axis convention, **measured** by the two-sighting calibration.
     * There are four possible frame errors, not two, so a boolean is not
     * enough; the second sighting decides.
     *
     * New key: the old one held a boolean, and reading it as text would throw.
     */
    /**
     * Calibration readings, one line per pose. Persisted, not screen state:
     * nine poses mean walking to the antenna and back, and a screen change
     * must not lose them.
     */
    /**
     * Hotspot name and password, for the Wi-Fi join QR code. Typed by hand:
     * since Android 10 an app cannot read its own hotspot configuration.
     */
    var demoSsid: String
        get() = prefs.getString("demo_ssid", "") ?: ""
        set(v) { prefs.edit().putString("demo_ssid", v.trim()).apply() }

    var demoMotDePasse: String
        get() = prefs.getString("demo_mdp", "") ?: ""
        set(v) { prefs.edit().putString("demo_mdp", v).apply() }

    /**
     * Hotspot announced in demonstration mode. The password is stored in
     * clear: it is a temporary hotspot's, shown as a QR code to the whole room
     * anyway.
     */

    /** Last remote station listened to, so it need not be retyped. */
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
     * Module axis for elevation: "TANGAGE" (pitch), "ROULIS" (roll) or
     * "AUCUN" (none, default). Until the mounting is known only the phone's
     * elevation is trustworthy; a guessed axis gives a moving — so credible —
     * but wrong needle.
     */
    /**
     * Boom direction **in the case frame**, as "x,y,z". Empty until learned.
     *
     * Replaces the old axis/offset/sign trio, which read yaw and tilt
     * separately and broke as soon as the antenna was rotated for
     * polarisation: Euler angles are not independent. A direction does not
     * move when you rotate around it. The old setting is not kept alongside.
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
     * The "Extensions" field: keywords unlocking beta features (see
     * [fr.f4ioz.satcombo.data.Extensions]). Empty by default. The callsign no
     * longer unlocks anything.
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
     * Altitude on the QRV photo, when known (from GPS; nothing for a typed
     * position). Matters for summit activations, where it is part of the
     * announcement like the locator.
     */
    var photoShowAlt: Boolean
        get() = prefs.getBoolean("photo_alt", false)
        set(v) { prefs.edit().putBoolean("photo_alt", v).apply() }

    /**
     * Callsign colour on the QRV photo, ARGB. Amber vanishes on a sunset, so a
     * few solid colours are offered — all opaque, translucent would be
     * unreadable on a bright photo.
     */
    var photoCallColor: Int
        get() = prefs.getInt("photo_call_color", 0xFFFFC65C.toInt())
        set(v) { prefs.edit().putInt("photo_call_color", v).apply() }

    /**
     * Callsign size on the QRV photo, 1.0 = reference. Clamped: below 60 % it
     * is unreadable once a messenger shrinks the photo, above 250 % it eats the
     * top half. Rendering shrinks it further if it overflows.
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
     * How many of the eight touching squares are printed on the QRV photo,
     * nearest first. Four matches the original N/S/E/W.
     */
    var photoNearCount: Int
        get() = prefs.getInt("photo_near_count", 4)
        set(v) { prefs.edit().putInt("photo_near_count", v.coerceIn(0, 8)).apply() }

    /**
     * Unit system for distances, altitudes and speeds: metric, imperial or
     * nautical. See [fr.f4ioz.satcombo.data.Units].
     */
    var units: String
        get() = Units.normalize(prefs.getString("units", Units.METRIC))
        set(v) { prefs.edit().putString("units", Units.normalize(v)).apply() }

    /** POTA line on the QRV photo. */
    var photoShowPota: Boolean
        get() = prefs.getBoolean("photo_show_pota", false)
        set(v) { prefs.edit().putBoolean("photo_show_pota", v).apply() }

    /**
     * Country silhouette on the QRV photo. Forced on once, by the update that
     * made it follow the position: whoever turned it off earlier did so for a
     * reason that no longer exists. `carte_forcee_1` makes it one-time only.
     */
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

    /** POTA line size on the photo, 0.6 to 2.5. */
    var photoPotaTaille: Float
        get() = prefs.getFloat("photo_pota_taille", 1f)
        set(v) { prefs.edit().putFloat("photo_pota_taille", v.coerceIn(0.6f, 2.5f)).apply() }

    /** How far the POTA line is raised, as a fraction of the height. */
    var photoPotaMonte: Float
        get() = prefs.getFloat("photo_pota_monte", 0f)
        set(v) { prefs.edit().putFloat("photo_pota_monte", v.coerceIn(0f, 0.6f)).apply() }

    /** Uplink/downlink frequencies on the QRV photo. */
    var photoShowQrg: Boolean
        get() = prefs.getBoolean("photo_show_qrg", false)
        set(v) { prefs.edit().putBoolean("photo_show_qrg", v).apply() }

    /** Announced frequency, typed by hand (free text). */
    var photoQrgTexte: String
        get() = prefs.getString("photo_qrg_texte", "") ?: ""
        set(v) { prefs.edit().putString("photo_qrg_texte", v.trim().take(24)).apply() }

    /** Size of the date + frequency line. */
    var photoPassScale: Float
        get() = prefs.getFloat("photo_pass_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_pass_scale", v.coerceIn(0.6f, 2.5f)).apply() }

    var photoQrgScale: Float
        get() = prefs.getFloat("photo_qrg_scale", 1f)
        set(v) { prefs.edit().putFloat("photo_qrg_scale", v.coerceIn(0.5f, 2.5f)).apply() }

    /** Park name under the POTA reference (the number always stays). */
    var photoPotaNom: Boolean
        get() = prefs.getBoolean("photo_pota_nom", true)
        set(v) { prefs.edit().putBoolean("photo_pota_nom", v).apply() }

    /** QRV card background without a photo: plain colour (ARGB). */
    var photoFondUni: Int
        get() = prefs.getInt("photo_fond_uni", 0xFF102030.toInt())
        set(v) { prefs.edit().putInt("photo_fond_uni", v).apply() }

    /** The map edge fades into the photo. */
    var photoCarteFondu: Boolean
        get() = prefs.getBoolean("photo_carte_fondu", true)
        set(v) { prefs.edit().putBoolean("photo_carte_fondu", v).apply() }

    /** Map fill colour (ARGB). */
    var photoCarteCouleur: Int
        get() = prefs.getInt("photo_carte_couleur", 0x66FFFFFF)
        set(v) { prefs.edit().putInt("photo_carte_couleur", v).apply() }

    /** The "In POTA zone" banner on the home page. */
    var potaBandeauAccueil: Boolean
        get() = prefs.getBoolean("pota_bandeau_accueil", true)
        set(v) { prefs.edit().putBoolean("pota_bandeau_accueil", v).apply() }

    /** "PAYS" (country silhouette) or "ZONE" (POTA park outline). */
    var photoCarteContenu: String
        get() = prefs.getString("photo_carte_contenu", "PAYS") ?: "PAYS"
        set(v) { prefs.edit().putString("photo_carte_contenu", v).apply() }

    /** "DRAPEAU" (flag) or "UNI" (plain). */
    var photoCarteRemplissage: String
        get() = prefs.getString("photo_carte_remp", "DRAPEAU") ?: "DRAPEAU"
        set(v) { prefs.edit().putString("photo_carte_remp", v).apply() }

    // `baseInterneIndicatifs` was removed with the bundled callsign database.

    /** Flag code before the callsign on the QRV photo, empty = none. */
    var photoFlag: String
        get() = prefs.getString("photo_flag", "") ?: ""
        set(v) { prefs.edit().putString("photo_flag", v.trim().uppercase()).apply() }

    /** Flag code right of the callsign, empty = none. */
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

    // ===================== azimuth / elevation rotor =====================
    //
    // The only SatMe feature that physically moves something. These settings
    // describe mechanical limits: a wrong one does not give an odd display, it
    // rips a cable. Hence the bounds are enforced here, on write, not in the UI.

    /** Rotor control requested by the operator. */
    var rotorEnabled: Boolean
        get() = prefs.getBoolean("rotor_enabled", false)
        set(v) { prefs.edit().putBoolean("rotor_enabled", v).apply() }

    /** Link type: "GS232" (USB serial) or "ROTCTLD" (network, Hamlib). */
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
     * Where the mast's mechanical stop is: "NORTH" or "SOUTH". A G-5500 out of
     * the box stops at north, the worst place for polar orbits (half the passes
     * cross it), so many stations remount it south. The app cannot guess.
     */
    var rotorAzStop: String
        get() = prefs.getString("rotor_az_stop", "NORTH") ?: "NORTH"
        set(v) {
            prefs.edit().putString("rotor_az_stop", if (v == "SOUTH") "SOUTH" else "NORTH").apply()
        }

    /**
     * Does the controller count azimuth from its stop rather than north? Some
     * show zero at the stop. Converted only on frames in and out: everywhere
     * else an azimuth is a true azimuth.
     */
    var rotorAzFromStop: Boolean
        get() = prefs.getBoolean("rotor_az_from_stop", false)
        set(v) { prefs.edit().putBoolean("rotor_az_from_stop", v).apply() }

    /** Pointing error tolerated before the banner reports it, in degrees. */
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
     * The mast has no elevation axis. Control is unchanged (an azimuth-only
     * rotor ignores the second value), but the compass keeps taking elevation
     * from the phone.
     */
    var rotorAzOnly: Boolean
        get() = prefs.getBoolean("rotor_az_only", false)
        set(v) { prefs.edit().putBoolean("rotor_az_only", v).apply() }

    /**
     * Doppler-correct the receive side too, not only transmit. On by default,
     * as expected of satellite software; off for operators who want the RX
     * dial to themselves (narrow CW, where every jump shows).
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

    /** Below this elevation, the mast goes to its park position. */
    var rotorMinEl: Int
        get() = prefs.getInt("rotor_min_el", 0)
        set(v) { prefs.edit().putInt("rotor_min_el", v.coerceIn(0, 30)).apply() }

    /**
     * Minutes before AOS at which the mast goes to wait for the satellite. A
     * half-turn takes over a minute; starting at AOS arrives when the bird is
     * already high. Zero disables pre-positioning.
     */
    var rotorPreAos: Int
        get() = prefs.getInt("rotor_pre_aos", 3)
        set(v) { prefs.edit().putInt("rotor_pre_aos", v.coerceIn(0, 30)).apply() }

    /** Simulated rotor: everything works, nothing turns. */
    var rotorSim: Boolean
        get() = prefs.getBoolean("rotor_sim", false)
        set(v) { prefs.edit().putBoolean("rotor_sim", v).apply() }

    // ------------------------------------------------------------------
    // Converters (LNB on downlink, transverter on uplink)
    //
    // Two independent boxes, as they are wired: LNB before the receiver,
    // transverter after the transmitter. On QO-100 they often serve two
    // devices at once (10 GHz downlink into an SDR dongle, 13 cm uplink from
    // an IC-9700 on 432), hence [convRxPoste] / [convRxCle] saying which
    // chain the downlink applies to.
    // ------------------------------------------------------------------

    /** Downlink converter enabled. */
    var convRxActif: Boolean
        get() = prefs.getBoolean("conv_rx_actif", false)
        set(v) { prefs.edit().putBoolean("conv_rx_actif", v).apply() }

    /** Downlink local oscillator, in Hz. */
    var convRxOlHz: Long
        get() = prefs.getLong("conv_rx_ol", 9_750_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_ol", v.coerceIn(0L, 30_000_000_000L)).apply() }

    /** High-side injection on downlink: the received spectrum is inverted. */
    var convRxInverseur: Boolean
        get() = prefs.getBoolean("conv_rx_inv", false)
        set(v) { prefs.edit().putBoolean("conv_rx_inv", v).apply() }

    var convRxBasHz: Long
        get() = prefs.getLong("conv_rx_bas", 10_400_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_bas", v.coerceAtLeast(0L)).apply() }

    var convRxHautHz: Long
        get() = prefs.getLong("conv_rx_haut", 10_800_000_000L)
        set(v) { prefs.edit().putLong("conv_rx_haut", v.coerceAtLeast(0L)).apply() }

    /** Downlink goes through the CAT-controlled rig. */
    var convRxPoste: Boolean
        get() = prefs.getBoolean("conv_rx_poste", false)
        set(v) { prefs.edit().putBoolean("conv_rx_poste", v).apply() }

    /** Downlink goes through the SDR dongle (the usual QO-100 setup). */
    var convRxCle: Boolean
        get() = prefs.getBoolean("conv_rx_cle", true)
        set(v) { prefs.edit().putBoolean("conv_rx_cle", v).apply() }

    /** Uplink converter enabled. */
    var convTxActif: Boolean
        get() = prefs.getBoolean("conv_tx_actif", false)
        set(v) { prefs.edit().putBoolean("conv_tx_actif", v).apply() }

    /** Uplink local oscillator, in Hz. */
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
