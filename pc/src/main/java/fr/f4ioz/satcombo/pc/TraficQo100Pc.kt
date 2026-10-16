/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ApiQo100
import fr.f4ioz.satcombo.domain.GardeQo100
import fr.f4ioz.satcombo.domain.TraficQo100
import org.json.JSONObject

/**
 * Operating QO-100 from one screen: the reception started in one gesture
 * (the receiver started, QO-100 applied to it, its sound to the decoders),
 * a signal tuned in its mode, the session kept through a restart.
 *
 * Receives only: nothing here ever transmits nor writes the rig.
 */
class TraficQo100Pc(private val station: StationPc, private val sdr: SdrPc, private val qo100: Qo100Pc? = null) {

    private val s get() = station.settings

    /** The receiver running, on QO-100. */
    val recoit: Boolean get() = sdr.etat.value.actif && station.qo100.etat.value.aLaCle

    /**
     * « Démarrer la réception QO-100 »: QO-100 applied to the receiver, a
     * QO-100 mode (USB), the offset back to zero, the receiver started when
     * stopped (or taken back from radiosondes or METEOR). Its sound goes to
     * the decoders by itself. Null when done, else why not.
     */
    @Synchronized fun demarre(): String? {
        station.qo100.aLaCle(true)
        if (sdr.modeChoisi !in setOf("AUTO", "USB")) sdr.modeChoisi = "AUTO"
        sdr.decalageHz = 0
        val e = sdr.etat.value
        if (!e.actif || sdr.pourSonde || sdr.pourMeteor) {
            if (!sdr.demarre()) return sdr.etat.value.erreur.ifBlank { "le récepteur ne démarre pas" }
        }
        s.qo100EnCours = true
        s.qo100DernierHz = station.qo100.etat.value.descenteHz
        return null
    }

    /** Stopped by the operator: the receiver, QO-100 off it, nothing to take back. */
    @Synchronized fun arrete() {
        s.qo100EnCours = false
        sdr.arrete()
        station.qo100.aLaCle(false)
    }

    /** Stopped elsewhere by the operator (SDR tab, the box unticked), or « Non merci ». */
    fun oublie() { s.qo100EnCours = false }

    /** The session as it was (dial, mode), then started. */
    fun reprend(): String? {
        s.qo100DernierHz.takeIf { it > 0 }?.let { station.qo100.descente(it, GardeQo100.Origine.PROPOSEE) }
        return demarre()
    }

    /** At SatMe-PC's start: taken back by itself only when chosen in Réglages. */
    fun repriseAuDemarrage(): Boolean {
        if (!TraficQo100.repriseAuto(s.qo100RepriseAuto, s.qo100EnCours)) return false
        val erreur = reprend()
        println("  QO-100    : " + (erreur?.let { "reprise impossible ($it)" } ?: "réception reprise"))
        return erreur == null
    }

    /** A signal clicked on: its dial, USB, its log mode; [classe] null tunes [hz] as given. */
    fun accorde(hz: Long, classe: String?): TraficQo100.Accord? {
        val a = classe?.takeIf { it.isNotBlank() }?.let { TraficQo100.accord(it, hz) }
        // Clicked, not tuned by hand: an emergency frequency is heard, its uplink not written.
        station.qo100.descente(a?.cadranHz ?: hz, GardeQo100.Origine.PROPOSEE)
        sdr.decalageHz = 0
        a?.let { s.qo100ModeCarnet = it.modeCarnet }
        return a
    }

    /** The log mode chosen by hand (SSB, CW, FT8…). */
    fun mode(m: String) {
        val v = m.trim().uppercase()
        if (v.matches(Regex("[A-Z0-9]{2,8}"))) s.qo100ModeCarnet = v
    }

    /** Every second: the session kept while QO-100 is received (dial included). */
    fun tic() {
        if (!recoit) return
        if (!s.qo100EnCours) s.qo100EnCours = true
        val hz = station.qo100.etat.value.descenteHz
        if (s.qo100DernierHz != hz) s.qo100DernierHz = hz
    }

    fun contexte(): TraficQo100.Contexte {
        val q = station.qo100.etat.value
        val e = station.etat.value
        return TraficQo100.contexte(q.aLaCle, sdr.etat.value.actif, q.auPoste, q.descenteHz, s.qo100ModeCarnet,
            e.suivi?.name, e.suivi?.catalogNumber ?: 0)
    }

    fun json(): JSONObject {
        val q = station.qo100.etat.value
        val e = sdr.etat.value
        val nom = if (sdr.recepteur == SdrPc.PLUTO) "ADALM-PLUTO" else "clé RTL-SDR"
        val r = ApiQo100.Recepteur(true, e.actif, if (e.tuner.isNotBlank()) "$nom · ${e.tuner}" else nom, e.statut, e.erreur,
            Math.round(e.niveauDb * 10) / 10.0, sdr.modeEffectif(), s.sdrAudio, s.sdrSquelchDb,
            if (sdr.recepteur == SdrPc.PLUTO) null else s.sdrAgc)
        val ecart = qo100?.suivi?.dernier?.takeIf { System.currentTimeMillis() - it.t < 60_000L }?.let { Math.round(it.ecartHz) }
        return ApiQo100.trafic(contexte(), q.aLaCle, q.auPoste, r, s.qo100EnCours, s.qo100RepriseAuto,
            classifieur = qo100 != null, calageAuto = qo100?.auto, ecartBaliseHz = ecart, serveur = "pc")
    }
}
