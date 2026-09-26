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

/** Per-satellite operating config: chosen transponder + manual calibration shift. */
data class SatConfig(
    val txDescription: String? = null,  // identifies the chosen transmitter
    val calibShiftHz: Long = 0L,        // RX correction for this bird
    val txShiftHz: Long = 0L,           // TX (uplink) correction for this bird
    val rxOffsetVoiceHz: Long = 0L,     // linear RX offset in Voice (SSB) mode
    val rxOffsetCwHz: Long = 0L,        // linear RX offset in CW mode
    /**
     * Reference shifts: the ones that worked.
     *
     * A shift is tuned by ear during a pass; then a finger slips on the TX dial
     * (which doubles as shift control) and the value is silently lost. This gives
     * a way back without restoring the whole configuration.
     *
     * Set **explicitly** by the operator: automatic saving cannot tell converging
     * tuning from a slip. `null` until set — zero is a valid reference.
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

    /** Stores the current shifts as this satellite's reference. */
    fun memoriseReference(catnum: Int, calibHz: Long, txHz: Long) {
        prefs.edit()
            .putLong("refshift_$catnum", calibHz)
            .putLong("reftxshift_$catnum", txHz)
            .apply()
    }

    // No "forget reference": storing again replaces it, which is all that is
    // needed. An extra button in a busy panel costs attention on every pass.

}
