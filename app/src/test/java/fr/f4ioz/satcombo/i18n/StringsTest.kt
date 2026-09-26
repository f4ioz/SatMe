/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The two string tables must stay twins. A missing English key silently
 * falls back to French and ships unnoticed; this turns it into a test failure.
 */
class StringsTest {

    @Test
    fun les_deux_tables_ont_les_memes_cles() {
        val manquantEn = FR.keys - EN.keys
        val manquantFr = EN.keys - FR.keys
        assertTrue("clés absentes de EN : $manquantEn", manquantEn.isEmpty())
        assertTrue("clés absentes de FR : $manquantFr", manquantFr.isEmpty())
    }

    @Test
    fun aucun_texte_nest_vide() {
        assertTrue(FR.filterValues { it.isBlank() }.keys.toString(),
            FR.none { it.value.isBlank() })
        assertTrue(EN.filterValues { it.isBlank() }.keys.toString(),
            EN.none { it.value.isBlank() })
    }

    /**
     * A parameterised text must take the parameter in both languages: a "{0}"
     * on one side only, and the satellite name vanishes from the English
     * notification.
     */
    @Test
    fun les_parametres_se_correspondent() {
        val re = Regex("\\{\\d}|%[-0-9.]*[sdfx]")
        for ((k, fr) in FR) {
            val en = EN[k] ?: continue
            assertEquals("paramètres différents pour « $k »",
                re.findAll(fr).count(), re.findAll(en).count())
        }
    }

    @Test
    fun les_textes_de_la_dix_huit_quatre_sont_la() {
        for (k in listOf("sonde_model", "sonde_model_auto", "sonde_model_desc",
            "sonde_marginal", "sonde_notyet", "menu_sondemire", "sondemire_title",
            "sondemire_desc", "sondemire_model", "sondemire_duration",
            "sondemire_frames", "sondemire_demo", "sondemire_demo_running",
            "sondemire_playing", "sondemire_export", "sondemire_export_mp3",
            "sondemire_hint", "sondemire_models_title", "sondemire_notyet")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_de_la_dix_huit_trois_sont_la() {
        for (k in listOf("photo_opt_satlabel_size", "sonde_source", "sonde_src_sdr",
            "sonde_src_sdr_desc", "sonde_src_mic_desc", "sonde_src_usb_desc",
            "sonde_by_audio", "sonde_audio_channel", "sonde_audio_notif")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_du_rotor_sont_la() {
        // A half-translated rotator screen is more dangerous than elsewhere:
        // "Emergency stop" is the button you look for when the mast turns the
        // wrong way, and you do not find it by reading.
        for (k in listOf("menu_rotor", "rotor_title", "rotor_desc", "rotor_beta",
            "rotor_enable", "rotor_enable_hint", "rotor_link", "rotor_link_gs232",
            "rotor_link_rotctld", "rotor_usb_index", "rotor_usb_hint", "rotor_baud",
            "rotor_host", "rotor_port", "rotor_max_az", "rotor_max_az_hint",
            "rotor_max_el", "rotor_deadband", "rotor_deadband_hint", "rotor_flip",
            "rotor_flip_hint", "rotor_park_az", "rotor_park_el", "rotor_min_el",
            "rotor_min_el_hint", "rotor_sim", "rotor_sim_hint", "rotor_connect",
            "rotor_disconnect", "rotor_stop", "rotor_park_now", "rotor_status",
            "rotor_target", "rotor_actual", "rotor_no_target", "rotor_out_of_range",
            "rotor_devices", "rotor_none", "rotor_error", "rotor_connected",
            "rotor_offline", "rotor_sim_on",
            // Azimuth stops.
            "rotor_az_stop", "rotor_az_stop_hint", "rotor_az_stop_north",
            "rotor_az_stop_south", "rotor_az_from_stop", "rotor_az_from_stop_hint",
            "rotor_max_error", "rotor_max_error_hint", "rotor_off_travel",
            "rotor_coverage", "rotor_plan_hint",
            // Compass follows the mast's pointing.
            "rotor_az_only", "rotor_az_only_hint",
            "aim_source_rotor", "aim_source_rotor_az")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_du_qo100_sont_la() {
        // The geostationary screen. A missing text hurts more here: nothing on
        // this screen can be guessed from motion (no pass, no countdown, no
        // ground track). And "Apply to radio" pushes a TX frequency: that
        // button must be read before being pressed, in both languages.
        for (k in listOf("menu_qo100", "qo100_title", "qo100_beta", "qo100_desc",
            "qo100_downlink", "qo100_uplink", "qo100_go_beacon", "qo100_go_centre",
            "qo100_edges", "qo100_hardware", "qo100_hardware_desc",
            "qo100_to_rig", "qo100_rig_rx", "qo100_rig_tx", "qo100_rig_unreachable",
            "qo100_to_dongle", "qo100_dongle_rx", "qo100_dongle_unreachable",
            "qo100_calib", "qo100_calib_desc", "qo100_calib_current",
            "qo100_calib_heard", "qo100_calib_apply", "qo100_calib_clear",
            "qo100_transponder", "qo100_tp_nb", "qo100_tp_wb", "qo100_wb_note",
            "qo100_aim", "qo100_aim_desc", "qo100_aim_unknown",
            "qo100_aim_invisible", "qo100_aim_az", "qo100_aim_el",
            "qo100_aim_skew", "qo100_aim_skew_desc",
            // The downconverter preset putting the whole NB transponder in 2 m:
            // without it the screen is right and the radio is elsewhere.
            "preset_down145")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
        // Transponder keys must match the domain keys exactly: the screen builds
        // "qo100_tp_" + cle, so a rename in Qo100.kt would give an empty label
        // with no complaint.
        for (tp in fr.f4ioz.satcombo.domain.Qo100.TRANSPONDEURS) {
            assertTrue("libellé manquant pour le transpondeur ${tp.cle}",
                FR.containsKey("qo100_tp_" + tp.cle))
            assertTrue("libellé anglais manquant pour ${tp.cle}",
                EN.containsKey("qo100_tp_" + tp.cle))
        }
    }

    /**
     * Sun alignment. Some texts carry `{0}` (shadow azimuth, duration); a
     * dropped placeholder gives "heads towards °" and a dish pointed at nothing.
     */
    @Test
    fun les_textes_de_l_alignement_solaire_sont_la() {
        for (k in listOf("qo100_sun", "qo100_sun_desc", "qo100_sun_az",
            "qo100_sun_az_hint", "qo100_sun_transit", "qo100_sun_transit_hint",
            "qo100_sun_gap", "qo100_sun_dur", "qo100_sun_none", "qo100_sun_wait")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
        for (k in listOf("qo100_sun_az_hint", "qo100_sun_gap", "qo100_sun_dur")) {
            assertTrue("le trou {0} manque en français dans $k", FR[k]!!.contains("{0}"))
            assertTrue("le trou {0} manque en anglais dans $k", EN[k]!!.contains("{0}"))
        }
    }

    /**
     * Panorama and beacon indicator: shown *instead of* an image, so a missing
     * key leaves a blank screen and the operator blames the dongle.
     */
    @Test
    fun les_textes_du_panorama_qo100_sont_la() {
        for (k in listOf("qo100_pano", "qo100_pano_desc", "qo100_pano_off",
            "qo100_beacon", "qo100_beacon_none", "qo100_beacon_offset",
            "qo100_beacon_snr", "qo100_beacon_ok", "qo100_beacon_drift",
            "qo100_beacon_apply")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    /**
     * CAT and SDR are no longer beta. A warning that outlives its reason
     * teaches operators to ignore warnings. Checked in the texts, where the
     * label would silently creep back. APT, sondes, rotator and QO-100 keep
     * theirs on purpose.
     */
    @Test
    fun ni_le_cat_ni_le_sdr_ne_se_disent_plus_en_beta() {
        assertTrue("la clé sdr_beta devrait avoir disparu du français",
            !FR.containsKey("sdr_beta"))
        assertTrue("la clé sdr_beta devrait avoir disparu de l'anglais",
            !EN.containsKey("sdr_beta"))
        val surveilles = listOf("sdr_menu", "doc_sdr_t", "doc_sdr_b",
            "doc_wiring_sdr", "doc_freq_b", "cat_send_desc")
        for (k in surveilles) {
            for ((langue, table) in listOf("français" to FR, "anglais" to EN)) {
                val texte = table[k]?.lowercase()
                assertTrue("clé $k absente en $langue", texte != null)
                assertTrue("le texte $k en $langue reparle de bêta",
                    !texte!!.contains("bêta") && !texte.contains("beta"))
            }
        }
    }

    /**
     * Transponder ruler. Keys are built from the domain (`"qo100_seg_" + cle`);
     * a missing one shows the raw key, e.g. "qo100_warn_balise" just before
     * transmitting on a beacon. So all are checked from the domain.
     */
    @Test
    fun les_textes_de_la_reglette_qo100_sont_la() {
        for (k in listOf("qo100_ruler", "qo100_ruler_desc", "qo100_ruler_here",
            "qo100_seg_width", "qo100_tx_forbidden", "qo100_warn_hors")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
        for (s in fr.f4ioz.satcombo.domain.Qo100.SEGMENTS) {
            val k = "qo100_seg_" + s.cle
            assertTrue("libellé manquant pour le segment ${s.cle}", FR.containsKey(k))
            assertTrue("libellé anglais manquant pour ${s.cle}", EN.containsKey(k))
        }
        for (u in fr.f4ioz.satcombo.domain.Qo100.Usage.entries) {
            val k = "qo100_warn_" + u.cleAvertissement
            assertTrue("avertissement manquant pour l'usage $u", FR.containsKey(k))
            assertTrue("avertissement anglais manquant pour $u", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_du_doppler_en_reception_sont_la() {
        // Who holds the RX dial. A short line, but it answers "why does the
        // radio frequency not move" — asked only because the answer was not
        // visible.
        for (k in listOf("cat_rx_doppler", "cat_rx_doppler_desc",
            "cat_rx_driven", "cat_rx_manual")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_de_la_dix_huit_dix_neuf_sont_la() {
        // Radio mode readback, USB adapter choice, mast pre-positioning and
        // rotator badge. Each answers a reported problem, and a missing text
        // stands out all the more when someone went looking for it.
        for (k in listOf("cat_radio_mode", "cat_mode_fixed",
            "cat_usb_device", "cat_usb_none", "cat_usb_hint",
            "usb_permission_denied", "rotor_badge", "rotor_badge_pre",
            "rotor_pre_aos", "rotor_pre_aos_hint")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    /**
     * No key may appear twice in the source. `mapOf` silently keeps the last
     * one, so diverging repeats made the text depend on line order, and fixing
     * the first occurrence had no effect. Only the source reveals it.
     */
    @Test
    fun aucune_cle_nest_ecrite_deux_fois_dans_la_source() {
        val src = listOf(
            "src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt",
            "app/src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt",
            "../app/src/main/java/fr/f4ioz/satcombo/i18n/Strings.kt")
            .map { File(it) }.firstOrNull { it.isFile }
        assumeTrue("Strings.kt introuvable depuis " + File(".").absolutePath, src != null)

        val lignes = src!!.readLines()
        val debutFr = lignes.indexOfFirst { it.startsWith("val FR") }
        val debutEn = lignes.indexOfFirst { it.startsWith("val EN") }
        assertTrue("blocs FR/EN introuvables", debutFr >= 0 && debutEn > debutFr)

        val entree = Regex("^\\s*\"([A-Za-z0-9_]+)\" to ")
        fun repetees(de: Int, a: Int): List<String> =
            (de until a).mapNotNull { entree.find(lignes[it])?.groupValues?.get(1) }
                .groupingBy { it }.eachCount()
                .filterValues { it > 1 }.keys.sorted()

        assertEquals("clés répétées dans la table française",
            emptyList<String>(), repetees(debutFr, debutEn))
        assertEquals("clés répétées dans la table anglaise",
            emptyList<String>(), repetees(debutEn, lignes.size))
    }
}
