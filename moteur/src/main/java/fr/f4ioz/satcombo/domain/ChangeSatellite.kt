/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Adif
import fr.f4ioz.satcombo.data.LogEntry

/**
 * A logged contact moved to another satellite (logged on the wrong one):
 * its name and number, and what belongs to the satellite — where it was
 * (az, el) and the frequencies, else the ADIF says SAT_MODE and bands of the
 * old one.
 *
 * Pure logic: SatMe-PC and the phone give what they know, the answers are the same.
 */
object ChangeSatellite {

    /** The name kept for a contact without satellite (« autre / terrestre »). */
    const val TERRESTRE = Adif.DATV_TERRESTRE

    /** QO-100's narrow transponder middle (sky), when the contact had no QO-100 frequency. */
    const val QO100_DESCENTE_HZ = Qo100.BALISE_MEDIANE_HZ

    /**
     * [e] on [nom] ([catnum] 0 when unknown); blank or « terrestre »: no
     * satellite. [azEl] where it was at the contact's time (null: unknown,
     * 0/0); [frequencesHz] its downlink and uplink at rest (sky Hz), when known.
     */
    fun applique(e: LogEntry, nom: String, catnum: Int, azEl: Pair<Double, Double>?, frequencesHz: Pair<Long?, Long?>?): LogEntry {
        val n = nom.trim()
        val qo = catnum == Qo100.NORAD || n.equals(TraficQo100.NOM, true)
        val terrestre = !qo && (n.isEmpty() || n.equals(TERRESTRE, true) || n.equals("terrestre", true))
        val nomFinal = when { qo -> TraficQo100.NOM; terrestre -> TERRESTRE; else -> n }
        val catFinal = when { qo -> Qo100.NORAD; terrestre -> 0; else -> catnum }
        if (nomFinal == e.satName && catFinal == e.catnum) return e
        val etaitQo = e.downlinkMhz >= 10_000.0
        val (dl, ul) = when {
            frequencesHz != null && frequencesHz.first != null -> (frequencesHz.first!! / 1e6) to ((frequencesHz.second ?: 0L) / 1e6)
            qo && etaitQo -> e.downlinkMhz to e.uplinkMhz
            qo -> QO100_DESCENTE_HZ / 1e6 to Qo100.monteeDepuisDescente(QO100_DESCENTE_HZ) / 1e6
            // Terrestrial: the frequency worked stays; another satellite: QO-100's are surely not its.
            terrestre -> e.downlinkMhz to e.uplinkMhz
            etaitQo -> 0.0 to 0.0
            else -> e.downlinkMhz to e.uplinkMhz
        }
        return e.copy(satName = nomFinal, catnum = catFinal,
            azimuthDeg = if (terrestre) 0.0 else azEl?.first ?: 0.0, elevationDeg = if (terrestre) 0.0 else azEl?.second ?: 0.0,
            downlinkMhz = dl, uplinkMhz = ul)
    }

    /** A transmitter's frequencies at rest, its passband's middle (sky Hz): downlink, uplink. */
    fun frequencesNominales(t: fr.f4ioz.satcombo.data.Transmitter?): Pair<Long?, Long?>? {
        t ?: return null
        val lo = t.downlinkLowHz ?: return null
        val hi = t.downlinkHighHz ?: lo
        val dl = (lo + hi) / 2
        val ul = if (t.isTransponder && t.uplinkLowHz != null)
            Doppler.transponderUplinkRest(dl, lo, hi, t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, t.invert)
        else t.uplinkLowHz
        return dl to ul
    }
}
