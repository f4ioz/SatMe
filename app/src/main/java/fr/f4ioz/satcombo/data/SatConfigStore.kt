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

/** Per-satellite operating config: chosen transponder + manual calibration shift. */
data class SatConfig(
    val txDescription: String? = null,  // identifies the chosen transmitter
    val calibShiftHz: Long = 0L,        // RX correction for this bird
    val txShiftHz: Long = 0L,           // TX (uplink) correction for this bird
    val rxOffsetVoiceHz: Long = 0L,     // linear RX offset in Voice (SSB) mode
    val rxOffsetCwHz: Long = 0L,        // linear RX offset in CW mode
    /**
     * Les décalages de référence : ceux qui marchaient.
     *
     * Un décalage se règle à l'oreille, pendant un passage, et il finit par
     * être juste. Puis un doigt glisse sur la molette d'émission — qui tient
     * lieu de commande de décalage — ou sur les boutons, et la valeur
     * patiemment trouvée est perdue sans que rien ne l'ait annoncé. Il n'y
     * avait alors aucun moyen de revenir en arrière : la seule sauvegarde
     * était celle de toute la configuration, et la restaurer pour un nombre
     * écraserait tout le reste.
     *
     * La référence est posée **explicitement** par l'opérateur, quand il juge
     * que c'est bon. Un enregistrement automatique se tromperait : il ne peut
     * pas distinguer le réglage qui converge de la fausse manœuvre, et
     * mémoriserait l'un pour l'autre.
     *
     * `null` tant que rien n'a été mémorisé — et zéro est une référence
     * valable, ce qu'un `0L` par défaut ne saurait pas dire.
     */
    val refCalibShiftHz: Long? = null,
    val refTxShiftHz: Long? = null,
)

/** Persists per-satellite config keyed by NORAD catalog number. */
class SatConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("satcombo_satconfig", Context.MODE_PRIVATE)

    fun load(catnum: Int): SatConfig = SatConfig(
        txDescription = prefs.getString("tx_$catnum", null),
        calibShiftHz = prefs.getLong("shift_$catnum", 0L),
        txShiftHz = prefs.getLong("txshift_$catnum", 0L),
        rxOffsetVoiceHz = prefs.getLong("rxoff_voice_$catnum", 0L),
        rxOffsetCwHz = prefs.getLong("rxoff_cw_$catnum", 0L),
        refCalibShiftHz = if (prefs.contains("refshift_$catnum"))
            prefs.getLong("refshift_$catnum", 0L) else null,
        refTxShiftHz = if (prefs.contains("reftxshift_$catnum"))
            prefs.getLong("reftxshift_$catnum", 0L) else null
    )

    fun saveTransmitter(catnum: Int, description: String?) {
        prefs.edit().putString("tx_$catnum", description).apply()
    }

    fun saveShift(catnum: Int, hz: Long) {
        prefs.edit().putLong("shift_$catnum", hz).apply()
    }

    fun saveTxShift(catnum: Int, hz: Long) {
        prefs.edit().putLong("txshift_$catnum", hz).apply()
    }

    fun saveRxOffsetVoice(catnum: Int, hz: Long) {
        prefs.edit().putLong("rxoff_voice_$catnum", hz).apply()
    }

    fun saveRxOffsetCw(catnum: Int, hz: Long) {
        prefs.edit().putLong("rxoff_cw_$catnum", hz).apply()
    }

    /** Mémorise les décalages courants comme référence pour ce satellite. */
    fun memoriseReference(catnum: Int, calibHz: Long, txHz: Long) {
        prefs.edit()
            .putLong("refshift_$catnum", calibHz)
            .putLong("reftxshift_$catnum", txHz)
            .apply()
    }

    // Il n'y a pas d'« oublier la référence » : mémoriser à nouveau la
    // remplace, et c'est le seul geste dont l'opérateur ait besoin. Un bouton
    // de plus dans un panneau déjà chargé, pour un cas qui ne se présente pas,
    // se paie en attention à chaque passage.

}
