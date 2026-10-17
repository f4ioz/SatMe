/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.BladerfSdr
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.SourceIq
import java.io.IOException

/**
 * The SDR tab's receiver lent to DATV: one program, one board at a time. If
 * the SDR tab (or QO-100's reception) is on the same receiver, it is stopped
 * while DATV uses it, and started again once DATV gives it back — unless DATV
 * takes it again within a moment (a new frequency, a new rate).
 */
class MaterielSdrPc(
    private val sdr: SdrPc,
    /** QO-100's reception session (the cockpit), if it was the one using the SDR. */
    private val reprendQo100: () -> String?,
    private val qo100EnCours: () -> Boolean,
    /** Why a transmitter holds that board now, or null. */
    private val emission: (String) -> String?,
) : MaterielSdrDatv {
    @Volatile private var aRendre: Pair<Boolean, Boolean>? = null
    @Volatile private var jeton = 0

    override fun ouvre(recepteur: String): SourceIq {
        jeton++
        emission(recepteur)?.let { throw IOException(it) }
        val surLuiAussi = (recepteur == Datv.PLUTO && sdr.plutoEnReception) || (recepteur == Datv.BLADERF && sdr.bladerfEnReception)
        if (surLuiAussi) {
            // The SDR tab gives way; remembered to be taken back.
            aRendre = true to qo100EnCours()
            sdr.arrete()
        }
        sdr.datvTient = recepteur
        return when (recepteur) {
            Datv.PLUTO -> PlutoSdr.connecte(sdr.plutoHote, sdr.plutoPort)
            Datv.BLADERF -> {
                val prog = sdr.programmeBladerf() ?: throw IOException(sdr.conseilBladerf())
                val c = sdr.infoBladerf.carte
                BladerfSdr.ouvre(prog, c?.serie.orEmpty(), c?.libelle ?: "bladeRF 2.0")
            }
            else -> throw IOException("récepteur inconnu : $recepteur")
        }.also { }
    }

    override fun rend(recepteur: String) {
        sdr.datvTient = null
        val r = aRendre ?: return
        val j = jeton
        Thread({
            // DATV starting again on it (a new setting) keeps it: nothing given back then.
            Thread.sleep(2500)
            if (jeton != j || sdr.datvTient != null) return@Thread
            aRendre = null
            if (r.second) reprendQo100() else sdr.demarre()
        }, "satme-datv-rend-sdr").apply { isDaemon = true; start() }
    }
}

/**
 * On a SatMe-Node: the ADALM-PLUTO (its address kept, 192.168.2.1 by
 * default) or the bladeRF (bladeRF-cli in the PATH) opened as they are.
 */
class MaterielNoeud(private val stockage: Stockage) : MaterielSdrDatv {
    override fun ouvre(recepteur: String): SourceIq = when (recepteur) {
        Datv.PLUTO -> PlutoSdr.connecte(stockage.getString("noeud_pluto_hote", PlutoSdr.HOTE) ?: PlutoSdr.HOTE,
            stockage.getInt("noeud_pluto_port", PlutoSdr.PORT))
        Datv.BLADERF -> BladerfSdr.ouvre(OutilsPc.trouve("bladeRF-cli") ?: throw IOException("bladeRF-cli introuvable sur le node : sudo apt install bladerf"))
        else -> throw IOException("récepteur inconnu : $recepteur")
    }

    override fun rend(recepteur: String) {}
}
