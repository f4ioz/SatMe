/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.EnvoiUdp
import fr.f4ioz.satcombo.domain.PskReporter

/**
 * The FT8 / FT4 stations heard on the card, sent to PSK Reporter when chosen
 * (Réglages): the frequency from the rig on CAT (QO-100's downlink when the
 * rig is on it), the satellite followed as the antenna.
 */
class PskReporterPc(
    private val station: StationPc,
    ft8: Ft8Pc,
    envoie: (ByteArray) -> Unit = EnvoiUdp(),
    horloge: () -> Long = System::currentTimeMillis,
) {
    val psk = PskReporter(envoie, horloge)

    var actif: Boolean
        get() = station.settings.pskReporter
        set(v) { station.settings.pskReporter = v; if (!v) psk.vide() }

    init {
        ft8.auDecode = { l -> if (actif) recoit(l) }
        kotlin.concurrent.thread(isDaemon = true, name = "satme-psk-lot") {
            while (true) {
                runCatching { tic() }
                Thread.sleep(10_000L)
            }
        }
    }

    /** True while what is heard is not on the air (a recording played back, the test generator): nothing sent then. */
    @Volatile var bloque: () -> Boolean = { false }

    fun recoit(l: List<Ft8Pc.Entendu>) {
        if (bloque()) return
        val e = station.etat.value
        val q = station.qo100.etat.value
        val qo = q.auPoste
        val dl = station.stationCat.etat.value.descenteHz
        val antenne = PskReporter.antenne(qo, e.suivi?.name)
        for (d in l) {
            val f = PskReporter.frequence(e.catConnecte, dl, qo, q.descenteHz, e.suivi != null, d.frequenceHz)
            psk.ajoute(d.appelant, d.locator, d.mode, d.rapportDb, f, d.tranche, antenne, e.indicatif.ifBlank { station.settings.callsign })
        }
    }

    fun tic() = psk.tic(actif, station.settings.callsign, station.etat.value.locator, "SatMe-PC " + versionPc())

    /** The settings' line. */
    fun etat(): String = when {
        !actif -> ""
        station.settings.callsign.isBlank() -> "Indicatif manquant : rien n'est envoyé"
        psk.etat.isNotBlank() -> psk.etat + (if (psk.enAttente > 0) " · ${psk.enAttente} en attente" else "")
        psk.enAttente > 0 -> "${psk.enAttente} station(s) en attente, envoi toutes les 5 min"
        else -> "Rien d'entendu à envoyer pour l'instant (FT8 en marche, poste relié en CAT)"
    }
}
