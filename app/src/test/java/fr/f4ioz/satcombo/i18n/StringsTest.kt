/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Les deux tables de textes doivent rester jumelles.
 *
 * Le mécanisme de traduction retombe sur le français quand une clé manque en
 * anglais, ce qui est un filet discret : l'écran s'affiche, personne ne voit
 * l'erreur, et l'application part sur le store avec une phrase française au
 * milieu de l'anglais. Ce test transforme l'oubli en échec de compilation, ce
 * qui est exactement ce qu'on veut d'un oubli.
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
     * Un texte qui attend un paramètre doit l'attendre dans les deux langues :
     * un « {0} » présent d'un côté et absent de l'autre, et c'est le nom du
     * satellite qui disparaît de la notification anglaise.
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
        // Un écran de rotor à moitié traduit est plus dangereux qu'ailleurs :
        // « Arrêt immédiat » est le bouton qu'on cherche quand le mât part dans
        // le mauvais sens, et on ne le cherche pas en lisant.
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
            // 18.16 : les butées d'azimut.
            "rotor_az_stop", "rotor_az_stop_hint", "rotor_az_stop_north",
            "rotor_az_stop_south", "rotor_az_from_stop", "rotor_az_from_stop_hint",
            "rotor_max_error", "rotor_max_error_hint", "rotor_off_travel",
            "rotor_coverage", "rotor_plan_hint",
            // 18.17 : la boussole prend la visée du mât.
            "rotor_az_only", "rotor_az_only_hint",
            "aim_source_rotor", "aim_source_rotor_az")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_du_qo100_sont_la() {
        // 18.31 : l'écran du géostationnaire. Un texte manquant y est plus
        // gênant qu'ailleurs, parce que rien sur cet écran ne se devine au
        // mouvement : il n'y a ni passage, ni compte à rebours, ni trace au
        // sol pour rattraper une étiquette absente. Et « Appliquer au poste »
        // pousse une fréquence d'émission : ce bouton-là se lit avant de se
        // presser, dans les deux langues.
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
            // Le préréglage du downconverter qui met tout le transpondeur
            // étroit dans les 2 m : sans lui, l'écran est juste et le poste
            // est ailleurs.
            "preset_down145")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
        // Les deux clés de transpondeur doivent suivre exactement les clés du
        // domaine : l'écran compose "qo100_tp_" + cle, donc un renommage dans
        // Qo100.kt donnerait un libellé vide sans que rien ne proteste.
        for (tp in fr.f4ioz.satcombo.domain.Qo100.TRANSPONDEURS) {
            assertTrue("libellé manquant pour le transpondeur ${tp.cle}",
                FR.containsKey("qo100_tp_" + tp.cle))
            assertTrue("libellé anglais manquant pour ${tp.cle}",
                EN.containsKey("qo100_tp_" + tp.cle))
        }
    }

    /**
     * 18.32 : la réglette du transpondeur.
     *
     * Chaque segment du plan de bande compose sa clé — `"qo100_seg_" + cle` —
     * et chaque usage compose la sienne. Une clé absente ne casse rien : le
     * mécanisme retombe sur la clé elle-même, et l'opérateur lit
     * « qo100_warn_balise » au moment précis où il s'apprête à émettre sur une
     * balise. C'est le pire endroit de l'application pour un texte manquant,
     * donc on les vérifie tous, en partant du domaine plutôt que d'une liste
     * recopiée à la main.
     */
    /**
     * 18.32 : l'alignement par le Soleil.
     *
     * Deux de ces textes portent un trou, `{0}` : l'azimut de l'ombre et la
     * durée du transit. Ce sont les seuls du lot qui puissent s'afficher faux
     * sans que rien ne casse — un trou oublié à la traduction donne « part
     * vers ° », et l'opérateur tourne sa parabole vers rien. L'essai de parité
     * des paramètres les couvre déjà ; on vérifie ici leur simple présence.
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
     * 18.32 : le panorama de la clé et le témoin de balise.
     *
     * Aucun de ces textes ne porte de trou. Ce qui les rend fragiles est
     * ailleurs : ce sont les seuls textes de l'écran QO-100 qui s'affichent
     * *à la place* d'une image. Quand le panorama ne peut rien montrer, il ne
     * reste que la phrase — une clé manquante laisserait un écran vide sans
     * explication, et l'opérateur conclurait à une panne de la clé.
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
     * 18.33 : le CAT et la clé SDR ne sont plus des fonctions en bêta.
     *
     * Les deux ont servi en vrai — le CAT sur un IC-9700, la clé sur l'air — et
     * Olivier a levé la réserve. Un avertissement qui survit à la raison qui
     * l'a fait naître est pire que pas d'avertissement du tout : il apprend à
     * l'opérateur à ne pas les lire, et le jour où il en reste un qui compte,
     * celui-là non plus ne sera pas lu.
     *
     * L'essai regarde les textes plutôt que l'écran, parce que c'est là que la
     * mention se réinstallerait sans bruit : une clé oubliée dans la table, un
     * titre de menu recopié d'une version antérieure. Les autres fonctions en
     * rodage — APT, radiosondes, rotor, QO-100 — gardent la leur, et c'est
     * volontaire : aucune n'a encore été confrontée au terrain.
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
        // 18.18 : qui tient la molette de réception. La ligne est courte, mais
        // c'est elle qui répond à « pourquoi la fréquence du poste ne bouge
        // pas » — la question ne se pose que parce qu'on ne voyait pas la
        // réponse.
        for (k in listOf("cat_rx_doppler", "cat_rx_doppler_desc",
            "cat_rx_driven", "cat_rx_manual")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    @Test
    fun les_textes_de_la_dix_huit_dix_neuf_sont_la() {
        // 18.19 : le mode relu du poste, le choix de l'adaptateur USB, le
        // pré-pointage du mât et le badge rotor. Chacun de ces textes répond à
        // une panne signalée, et un texte manquant se voit d'autant plus qu'on
        // vient d'aller le chercher.
        for (k in listOf("cat_radio_mode", "cat_mode_fixed",
            "cat_usb_device", "cat_usb_none", "cat_usb_hint",
            "usb_permission_denied", "rotor_badge", "rotor_badge_pre",
            "rotor_pre_aos", "rotor_pre_aos_hint")) {
            assertTrue("clé $k absente en français", FR.containsKey(k))
            assertTrue("clé $k absente en anglais", EN.containsKey(k))
        }
    }

    /**
     * Aucune clé ne doit être écrite deux fois dans le fichier source.
     *
     * L'histoire : en 18.20, la table française comptait 1097 entrées pour 986
     * clés — soixante et onze clés écrites de deux à cinq fois, héritées de
     * patchs successifs qui rajoutaient un bloc de textes déjà présent. Rien ne
     * plantait : `mapOf` garde silencieusement la dernière occurrence. Mais
     * certaines répétitions divergeaient — « Consigne » puis « Cible », « %d %% »
     * puis « {0} % » — si bien que le texte affiché dépendait de l'ordre des
     * lignes, et qu'une correction faite sur la première occurrence n'avait
     * aucun effet à l'écran. C'est exactement ce qui a été signalé sur les
     * copies d'écran du rotor.
     *
     * Aucun essai sur les tables compilées ne peut voir la faute, puisqu'à
     * l'exécution la table est déjà dédoublonnée. Il faut donc relire la
     * source.
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
