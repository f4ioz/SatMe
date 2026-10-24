/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * The SSTV options' settings, kept with the station's (the phone's
 * preferences, SatMe-PC's file): the missing slide's notice, the sound of
 * each pass and how long it is kept, the QSL picture's layout.
 */
class OptionsSstv(private val prefs: Stockage) {

    /** « Diapo manquante attendue »: on by default (it follows « Activités à venir »'s switch too). */
    var alerteDiapo: Boolean
        get() = prefs.getBoolean("sstvo_alerte", true)
        set(v) { prefs.edit().putBoolean("sstvo_alerte", v).apply() }
    var alerteAvantMin: Int
        get() = prefs.getInt("sstvo_alerte_avant", 15).coerceIn(1, 120)
        set(v) { prefs.edit().putInt("sstvo_alerte_avant", v.coerceIn(1, 120)).apply() }
    /** A slide received only under this mark is still wanted (0: only those missing). */
    var alerteNoteMin: Int
        get() = prefs.getInt("sstvo_alerte_note", 60).coerceIn(0, 100)
        set(v) { prefs.edit().putInt("sstvo_alerte_note", v.coerceIn(0, 100)).apply() }

    val reglagesAlerte: AlerteDiapo.Reglages get() = AlerteDiapo.Reglages(alerteDiapo, alerteAvantMin, alerteNoteMin)

    /**
     * « Enregistrer le son de chaque passage SSTV »: each pass of the
     * series' satellite followed is recorded (SatMe-PC; the phone's
     * automatic SSTV records anyway). The auto-pilot's campaigns record by
     * their own « Enregistrer », on by default.
     */
    var enregistrePassages: Boolean
        get() = prefs.getBoolean("sstvo_enreg", false)
        set(v) { prefs.edit().putBoolean("sstvo_enreg", v).apply() }

    /**
     * « Re-décoder chaque image à la fin » (on by default): each picture
     * received, once its own sound is kept, decoded again from it in the
     * background ([RedecodageAuto]); its corrected version kept beside when it
     * marks better (the picture as received untouched).
     */
    var redecodeFin: Boolean
        get() = prefs.getBoolean("sstvo_redecode_fin", true)
        set(v) { prefs.edit().putBoolean("sstvo_redecode_fin", v).apply() }

    /** Retention: days (0 for ever) and size (MB, 0 no limit); both off by default. */
    var retentionJours: Int
        get() = prefs.getInt("sstvo_ret_jours", 0).coerceIn(0, 3650)
        set(v) { prefs.edit().putInt("sstvo_ret_jours", v.coerceIn(0, 3650)).apply() }
    var retentionMaxMo: Int
        get() = prefs.getInt("sstvo_ret_mo", 0).coerceIn(0, 1_000_000)
        set(v) { prefs.edit().putInt("sstvo_ret_mo", v.coerceIn(0, 1_000_000)).apply() }

    /** The recordings the passage journal names are kept too (on by default). */
    var retentionJournal: Boolean
        get() = prefs.getBoolean("sstvo_ret_journal", true)
        set(v) { prefs.edit().putBoolean("sstvo_ret_journal", v).apply() }

    val reglagesRetention: RetentionSon.Reglages get() = RetentionSon.Reglages(retentionJours, retentionMaxMo)

    /** The QSL picture: banner below or over the picture, « SatMe » written, the format. */
    var qslDisposition: QslSstv.Disposition
        get() = runCatching { QslSstv.Disposition.valueOf(prefs.getString("sstvo_qsl_dispo", "BANDEAU") ?: "BANDEAU") }.getOrDefault(QslSstv.Disposition.BANDEAU)
        set(v) { prefs.edit().putString("sstvo_qsl_dispo", v.name).apply() }
    var qslSatme: Boolean
        get() = prefs.getBoolean("sstvo_qsl_satme", true)
        set(v) { prefs.edit().putBoolean("sstvo_qsl_satme", v).apply() }
    var qslJpeg: Boolean
        get() = prefs.getBoolean("sstvo_qsl_jpeg", false)
        set(v) { prefs.edit().putBoolean("sstvo_qsl_jpeg", v).apply() }
}
