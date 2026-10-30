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

    companion object {
        const val IA_FILS_MAX = 32
    }

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

    /**
     * « Corriger les couleurs » ([CouleurSstv], [RetouchesGalerie]): every
     * picture without a switch of its own has its colour set right where it
     * goes out (viewer, sheets, web site) — off by default; and which steps.
     * The « IA » is never on by default: picture by picture, or every noisy
     * picture once the owner switches [iaAuto] on.
     */
    var couleurDefaut: Boolean
        get() = prefs.getBoolean("sstvo_couleur", false)
        set(v) { prefs.edit().putBoolean("sstvo_couleur", v).apply() }
    var couleurDominante: Boolean
        get() = prefs.getBoolean("sstvo_couleur_dominante", true)
        set(v) { prefs.edit().putBoolean("sstvo_couleur_dominante", v).apply() }
    var couleurChroma: Boolean
        get() = prefs.getBoolean("sstvo_couleur_chroma", true)
        set(v) { prefs.edit().putBoolean("sstvo_couleur_chroma", v).apply() }
    var couleurNiveaux: Boolean
        get() = prefs.getBoolean("sstvo_couleur_niveaux", true)
        set(v) { prefs.edit().putBoolean("sstvo_couleur_niveaux", v).apply() }
    var couleurSaturation: Boolean
        get() = prefs.getBoolean("sstvo_couleur_saturation", false)
        set(v) { prefs.edit().putBoolean("sstvo_couleur_saturation", v).apply() }

    /**
     * « Débruitage IA automatique » ([IaAuto]): off by default, and never on
     * by itself — the owner switches it on. Only with the model installed;
     * with [iaSeuilActif] (on), only the pictures whose noise is at least
     * [iaSeuil] (luma σ, levels).
     */
    var iaAuto: Boolean
        get() = prefs.getBoolean("sstvo_ia_auto", false)
        set(v) { prefs.edit().putBoolean("sstvo_ia_auto", v).apply() }
    var iaSeuilActif: Boolean
        get() = prefs.getBoolean("sstvo_ia_seuil_on", true)
        set(v) { prefs.edit().putBoolean("sstvo_ia_seuil_on", v).apply() }
    /** Kept in tenths of a level. */
    var iaSeuil: Double
        get() = prefs.getInt("sstvo_ia_seuil", (IaAuto.SEUIL_DEFAUT * 10).toInt()).coerceIn(0, (IaAuto.SEUIL_MAX * 10).toInt()) / 10.0
        set(v) { prefs.edit().putInt("sstvo_ia_seuil", Math.round(v.coerceIn(IaAuto.SEUIL_MIN, IaAuto.SEUIL_MAX) * 10).toInt()).apply() }

    val regleIaAuto: IaAuto.Regle get() = IaAuto.Regle(iaAuto, iaSeuilActif, iaSeuil)

    /** How the IA is run, whoever asked it ([IaSstv.Reglages]: strength, tiles, before or after the colour), each kept in its range. */
    var iaForce: Int
        get() = prefs.getInt("sstvo_ia_force", 100).coerceIn(0, 100)
        set(v) { prefs.edit().putInt("sstvo_ia_force", v.coerceIn(0, 100)).apply() }
    var iaTuile: Int
        get() = IaSstv.Reglages(tuile = prefs.getInt("sstvo_ia_tuile", IaSstv.TUILE)).borne().tuile
        set(v) { prefs.edit().putInt("sstvo_ia_tuile", IaSstv.Reglages(tuile = v).borne().tuile).apply() }
    var iaRecouvrement: Int
        get() = IaSstv.Reglages(tuile = iaTuile, recouvrement = prefs.getInt("sstvo_ia_recouvrement", IaSstv.RECOUVREMENT)).borne().recouvrement
        set(v) { prefs.edit().putInt("sstvo_ia_recouvrement", IaSstv.Reglages(tuile = iaTuile, recouvrement = v).borne().recouvrement).apply() }
    var iaAvantCouleur: Boolean
        get() = prefs.getBoolean("sstvo_ia_avant", false)
        set(v) { prefs.edit().putBoolean("sstvo_ia_avant", v).apply() }
    /** The processor's threads for the IA: 0 automatic (half the processor's, 8 at most), else 1 to [IA_FILS_MAX]. */
    var iaFils: Int
        get() = prefs.getInt("sstvo_ia_fils", 0).coerceIn(0, IA_FILS_MAX)
        set(v) { prefs.edit().putInt("sstvo_ia_fils", v.coerceIn(0, IA_FILS_MAX)).apply() }

    val reglagesIa: IaSstv.Reglages get() = IaSstv.Reglages(iaForce, iaTuile, iaRecouvrement, iaAvantCouleur).borne()

    val defautsRetouche: RetouchesGalerie.Defauts get() =
        RetouchesGalerie.Defauts(couleurDefaut, CouleurSstv.Options(couleurDominante, couleurChroma, couleurNiveaux, couleurSaturation), reglagesIa)

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
