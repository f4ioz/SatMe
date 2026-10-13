/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.FrequenceKiss
import fr.f4ioz.satcombo.aprs.LienKiss
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.portPttRigExpert

/**
 * A radio with a KISS TNC on one of the PC's serial ports (a TH-D72 on its
 * USB cable, a TM-D710, a Mobilinkd…), as on the phone: put in KISS and
 * tuned for APRS (the ground, the ISS, or the ISS when it is up, stepped
 * by its Doppler), its frames into the APRS tab; put back as it was when
 * left.
 *
 * **RTS and DTR low unless asked**: opened with them up, a rig interface
 * goes to transmit. A port in use by the rig or the rotator, or a
 * RigExpert's transmit-control port, is never taken.
 */
class KissPc(private val station: StationPc, private val aprs: AprsPc, private val horloge: () -> Long = System::currentTimeMillis) {

    val lien = LienKiss { t -> aprs.ajoute(t, horloge()) }

    /** Raise RTS/DTR on opening: only for a TNC that needs them, never on a rig interface. */
    var lignesLevees: Boolean
        get() = station.stockage.getBoolean("pc_kiss_lignes", false)
        set(v) { station.stockage.edit().putBoolean("pc_kiss_lignes", v).apply() }

    @Volatile private var changeMs = 0L

    /** The ISS's elevation and range rate at [t], when known. */
    private fun iss(t: Long) = station.positionSatellite(25544, t)

    /** Where the radio should listen now ([FrequenceKiss]), or null to leave it. */
    fun frequenceVoulue(): Long? {
        val maintenant = horloge()
        val p = iss(maintenant)
        val enVue = listOf(maintenant, maintenant + 60_000L).any { (iss(it)?.elevationDeg ?: -90.0) > 0.0 }
        return FrequenceKiss.voulue(station.settings.aprsTravail, enVue, station.settings.aprsKissDoppler, p?.elevationDeg, p?.rangeRateKmS)
    }

    /** The port [index] opened at [vitesse] and the radio put in KISS; null when done, else why not. */
    suspend fun connecte(index: Int, vitesse: Int, lever: Boolean): String? {
        val s = station.settings
        val p = station.ports.liste().firstOrNull { it.index == index } ?: return "port inconnu"
        if (p.portPttRigExpert()) return "c'est la commande d'émission d'un RigExpert : jamais ouvert"
        if (station.etat.value.catConnecte && index == s.civUsbIndex) return "c'est le port du poste (CAT) : choisir celui de la radio APRS"
        if (station.stationRotor.etat.value.rotorConnected && index == s.rotorUsbIndex) return "c'est le port du rotor"
        lien.deconnecte(sortirDuKiss = false)
        val o = station.ports.ouvre(p, vitesse, if (lever) Lignes.LEVEES else Lignes.BASSES)
        val l = o.lien ?: return o.erreur.ifBlank { "ouverture refusée" }
        s.aprsKissCle = p.cle; s.aprsKissVitesse = vitesse; lignesLevees = lever
        lien.attache(l, p.libelle, vitesse)
        changeMs = horloge()
        if (!lien.passeEnKiss(frequenceVoulue())) return "la radio ne passe pas en KISS (" + (lien.etat.value.erreur ?: "silence") + ")"
        return null
    }

    /** Back to the radio's normal mode, the line closed. Blocking about a second. */
    fun deconnecte() = lien.deconnecte()

    /** Working mode and Doppler; the radio retuned at once when connected. */
    suspend fun regle(travail: String?, doppler: Boolean?) {
        travail?.takeIf { it in setOf("TERRE", "ISS", "AUTO", "POSTE") }?.let { station.settings.aprsTravail = it }
        doppler?.let { station.settings.aprsKissDoppler = it }
        val hz = frequenceVoulue() ?: return
        if (lien.etat.value.connecte) { changeMs = horloge(); lien.regleFrequence(hz) }
    }

    /** Every few seconds: the ISS's arrival or its Doppler followed, one switch a minute at most (as on the phone). */
    suspend fun tic() {
        val s = station.settings
        if (!FrequenceKiss.suivie(s.aprsTravail, s.aprsKissDoppler)) return
        val k = lien.etat.value
        if (!k.connecte || !k.initialise || k.frequenceHz == null) return
        val hz = frequenceVoulue() ?: return
        if (k.frequenceHz == hz || horloge() - changeMs < FrequenceKiss.INTERVALLE_MS) return
        changeMs = horloge()
        lien.regleFrequence(hz)
    }
}
