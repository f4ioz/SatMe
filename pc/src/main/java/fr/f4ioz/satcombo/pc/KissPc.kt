/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.AprsEmission
import fr.f4ioz.satcombo.aprs.EmissionKiss
import fr.f4ioz.satcombo.aprs.FrequenceKiss
import fr.f4ioz.satcombo.aprs.LienKiss
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.portPttRigExpert

/**
 * A radio with a KISS TNC on one of the PC's serial ports (a TH-D72 on its
 * USB cable, a TM-D710, a Mobilinkd…), as on the phone: put in KISS and
 * tuned for APRS (the ground, the ISS, or the ISS when it is up, stepped
 * by its Doppler), its frames into the APRS tab; put back as it was when
 * left. It sends what the operator asks (a message, the position, a
 * status) and, when chosen, the ISS beacon — every check of the phone's
 * before the radio keys ([EmissionKiss]).
 *
 * **RTS and DTR low unless asked**: opened with them up, a rig interface
 * goes to transmit. A port in use by the rig or the rotator, or a
 * RigExpert's transmit-control port, is never taken.
 */
class KissPc(private val station: StationPc, private val aprs: AprsPc, private val horloge: () -> Long = System::currentTimeMillis) {

    val lien = LienKiss { t -> aprs.ajoute(t, horloge()) }

    /** Raise RTS/DTR on opening: only for a TNC that needs them (a TH-D72's own adapter), never on a rig interface. */
    var lignesLevees: Boolean
        get() = station.stockage.getBoolean("pc_kiss_lignes", false)
        set(v) { station.stockage.edit().putBoolean("pc_kiss_lignes", v).apply() }

    /** The box ticked by default for the port known to be a TH-D72's (it answered as one on CAT). */
    fun lignesPour(cle: String?): Boolean = lignesLevees || (cle != null && cle.isNotBlank() && cle == station.cleThd72)

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
        ModeDemo.refus("le poste KISS")?.let { return it }
        val s = station.settings
        val p = station.ports.liste().firstOrNull { it.index == index } ?: return "port inconnu"
        if (p.portPttRigExpert()) return "c'est la commande d'émission d'un RigExpert : jamais ouvert"
        if (station.portDAutrePoste(p)) return "${station.nomPort(p)} est l'interface d'un autre poste : jamais ouvert pour le KISS"
        if (p.cle in station.clesPoste() || (station.etat.value.catConnecte && index == s.civUsbIndex))
            return "c'est le port du poste (CAT) : un seul câble — déconnecter le CAT pour passer la radio en KISS"
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

    // ------------------------------------------------------------ sending

    /** When the last frame went, and what became of the last one asked. */
    @Volatile private var derniereMs = 0L
    @Volatile var envoi = ""; private set

    fun issEnVue(): Boolean = horloge().let { m -> listOf(m, m + 60_000L).any { (iss(it)?.elevationDeg ?: -90.0) > 0.0 } }

    /** The path asked: the ISS digipeater, the terrestrial network, or where APRS works now. */
    fun chemin(choix: String): List<String> = when (choix) {
        "ARISS" -> listOf("ARISS")
        "WIDE" -> listOf("WIDE1-1", "WIDE2-1")
        else -> EmissionKiss.chemin(station.settings.aprsTravail, issEnVue())
    }

    /**
     * Sends through the radio: [type] "message" (to [dest], numbered for its
     * ack when [numero]), "position" (the QTH, approximate when chosen, with
     * [texte] as comment) or "statut". Null when the radio took it, else why
     * not. [frequenceConfirmee]: the operator vouches for the radio's
     * frequency when SatMe could not read it.
     */
    suspend fun emet(type: String, dest: String, texte: String, numero: Boolean, choixChemin: String, frequenceConfirmee: Boolean): String? {
        ModeDemo.refus("l'émission APRS")?.let { return it }
        val s = station.settings
        val info = when (type) {
            "message" -> {
                if (dest.isBlank()) return "à qui ? (son indicatif)"
                if (texte.isBlank()) return "le message est vide"
                AprsEmission.message(dest, texte, if (numero) EmissionKiss.numeroSuivant(s) else null)
            }
            "position" -> {
                val o = station.etat.value.observateur ?: return "position inconnue (locator dans Réglages)"
                val (la, lo) = EmissionKiss.position(s, o.latDeg, o.lonDeg)
                AprsEmission.position(la, lo, "/-", texte.ifBlank { "SatMe " + station.etat.value.locator.take(4) })
            }
            "statut" -> { if (texte.isBlank()) return "le statut est vide"; AprsEmission.statut(texte) }
            else -> return "quoi émettre ?"
        }
        return envoie(AprsEmission.trame(EmissionKiss.source(s), chemin(choixChemin), info), frequenceConfirmee)
    }

    private suspend fun envoie(t: fr.f4ioz.satcombo.aprs.Trame, frequenceConfirmee: Boolean): String? {
        val e = lien.etat.value
        EmissionKiss.refus(e, frequenceConfirmee, t.source.indicatif, derniereMs, horloge())?.let {
            return EmissionKiss.texte(it, e).also { m -> envoi = m }
        }
        if (!lien.envoie(t)) return fr.f4ioz.satcombo.i18n.t("aprs_kiss_echec").also { envoi = it }
        derniereMs = horloge()
        aprs.ajouteEmis(t, derniereMs)
        envoi = fr.f4ioz.satcombo.i18n.t("aprs_kiss_ok")
        return null
    }

    /** AOS of the ISS pass already beaconed: one position a pass, never more. */
    @Volatile private var baliseAos = 0L

    /**
     * The ISS beacon, when chosen: once the ISS is 15° up, one position a
     * pass through the ARISS digipeater — only when SatMe read the radio on
     * 145.825 itself. Every check still applies.
     */
    private suspend fun balise() {
        val s = station.settings
        if (!s.aprsBaliseIss || !EmissionKiss.balisePrete(lien.etat.value)) return
        val maintenant = horloge()
        if ((iss(maintenant)?.elevationDeg ?: -90.0) < 15.0) return
        val aos = station.passageEnCours(25544, maintenant)?.first ?: return
        if (aos == baliseAos || EmissionKiss.source(s).isBlank()) return
        baliseAos = aos
        val o = station.etat.value.observateur ?: return
        val (la, lo) = EmissionKiss.position(s, o.latDeg, o.lonDeg)
        envoie(AprsEmission.trame(EmissionKiss.source(s), listOf("ARISS"),
            AprsEmission.position(la, lo, "/-", "SatMe " + station.etat.value.locator.take(4))), frequenceConfirmee = true)
    }

    /** Every few seconds: the ISS's arrival or its Doppler followed, one switch a minute at most (as on the phone); the ISS beacon. */
    suspend fun tic() {
        runCatching { balise() }
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
