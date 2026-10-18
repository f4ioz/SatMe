/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.LectureRigctld
import fr.f4ioz.satcombo.cat.RigctldProtocole
import fr.f4ioz.satcombo.cat.ServeurRigctld
import fr.f4ioz.satcombo.cat.StationRigctld
import fr.f4ioz.satcombo.domain.GardeQo100
import fr.f4ioz.satcombo.domain.Qo100
import java.net.InetAddress
import kotlin.math.abs

/**
 * The station's frequency for logging programs, by the rigctld network
 * protocol (Réglages › Interfaces). Off by default; on this PC only unless
 * the owner ticks « réseau local » and SatMe-PC runs with --reseau.
 *
 * What it tells: the receive path in use — the rig when connected by CAT,
 * else the SDR receiver — its sky frequency (QO-100: the nominal downlink,
 * or the uplink when chosen), its mode, the transmit frequency when one goes
 * with it, and whether SatMe transmits now. Setting the frequency or mode
 * retunes the SDR receiver only, and only when allowed; never the rig,
 * never PTT.
 */
class RigctldPc(private val station: StationPc, private val sdr: SdrPc?, private val reseau: Boolean,
                private val emet: () -> Boolean = { false }) : StationRigctld {

    private val stockage get() = station.stockage
    private val serveur = ServeurRigctld(this)

    var actif: Boolean
        get() = stockage.getBoolean(CLE_ACTIF, false)
        private set(v) { stockage.edit().putBoolean(CLE_ACTIF, v).apply() }
    var port: Int
        get() = stockage.getInt(CLE_PORT, RigctldProtocole.PORT_DEFAUT)
        private set(v) { stockage.edit().putInt(CLE_PORT, v).apply() }
    /** Open to the local network (only when SatMe-PC itself is, --reseau). */
    var surReseau: Boolean
        get() = stockage.getBoolean(CLE_RESEAU, false)
        private set(v) { stockage.edit().putBoolean(CLE_RESEAU, v).apply() }
    /** Clients may retune the SDR receiver (frequency, mode); never the rig. */
    var accordPermis: Boolean
        get() = stockage.getBoolean(CLE_ACCORD, false)
        private set(v) { stockage.edit().putBoolean(CLE_ACCORD, v).apply() }
    /** Report the uplink instead of the downlink, when there is one. */
    var montee: Boolean
        get() = stockage.getBoolean(CLE_MONTEE, false)
        private set(v) { stockage.edit().putBoolean(CLE_MONTEE, v).apply() }

    @Volatile var erreur: String = ""; private set
    val ecoute: Boolean get() = serveur.ecoute
    val nbClients: Int get() = serveur.nbClients
    val ouvertAuReseau: Boolean get() = reseau && surReseau

    /** Set from the page. */
    fun regle(on: Boolean? = null, nouveauPort: Int? = null, reseauLocal: Boolean? = null, accord: Boolean? = null, enMontee: Boolean? = null) {
        if (nouveauPort != null && nouveauPort !in 1024..65535) { erreur = "port entre 1024 et 65535"; return }
        val avant = Pair(port, ouvertAuReseau)
        nouveauPort?.let { port = it }
        reseauLocal?.let { surReseau = it }
        accord?.let { accordPermis = it }
        enMontee?.let { montee = it }
        on?.let { actif = it }
        when {
            !actif -> { serveur.arrete(); erreur = "" }
            avant != Pair(port, ouvertAuReseau) || !ecoute -> { serveur.arrete(); demarre() }
        }
    }

    fun demarreSiActif() { if (actif) demarre() }

    private fun demarre() {
        erreur = ""
        conflitRotor()?.let { erreur = it; return }
        runCatching { serveur.demarre(if (ouvertAuReseau) null else InetAddress.getLoopbackAddress(), port) }
            .onFailure { erreur = "port $port : " + (it.message ?: "occupé") }
    }

    fun arrete() = serveur.arrete()

    /** The rotor's rotctld client aimed at this very port here: it would talk to us. */
    private fun conflitRotor(): String? {
        val s = station.settings
        if (s.rotorLink != "ROTCTLD" || s.rotorPort != port) return null
        val h = s.rotorHost.trim().lowercase()
        val ici = h == "localhost" || h == "::1" || h.startsWith("127.") ||
            runCatching { InetAddress.getByName(h).let { it.isAnyLocalAddress || it.isLoopbackAddress || java.net.NetworkInterface.getByInetAddress(it) != null } }.getOrDefault(false)
        return if (ici) "port $port : c'est celui du rotor (rotctld sur ce PC), en choisir un autre" else null
    }

    // ------------------------------------------------------------- the station read

    override fun lecture(): LectureRigctld {
        val e = station.etat.value
        val q = station.qo100.etat.value
        val cat = station.stationCat.etat.value
        val d = sdr?.etat?.value
        var rx: Long
        var tx: Long? = null
        val mode: String
        var bande = 0
        when {
            // The rig by CAT: what it receives (QO-100: the nominal pair, what is logged).
            e.catConnecte && q.auPoste -> {
                rx = q.descenteHz; tx = q.monteeHz.takeIf { q.emissionPermise }
                mode = RigctldProtocole.modeProtocole(cat.mode ?: "USB")
            }
            e.catConnecte && cat.descenteHz != null -> {
                rx = cat.descenteHz!!; tx = cat.monteeHz
                mode = RigctldProtocole.modeProtocole(cat.mode ?: station.emetteurChoisi()?.mode)
            }
            // The SDR receiver (running, or where it will listen).
            sdr != null && d != null -> {
                if (q.aLaCle) {
                    rx = q.descenteHz + sdr.decalageHz
                    tx = Qo100.monteeDepuisDescente(rx).takeIf { Qo100.emissionAutorisee(rx) }
                } else rx = (if (d.cielHz > 0) d.cielHz else sdr.manuelleHz) + sdr.decalageHz
                mode = RigctldProtocole.modeProtocole(sdr.modeEffectif().replace("NFM", "FM"))
                bande = station.settings.sdrBandwidthHz
            }
            else -> { rx = q.descenteHz; mode = "USB" }
        }
        if (montee && tx != null) rx = tx
        return LectureRigctld(rx, mode, bande.takeIf { it > 0 } ?: RigctldProtocole.bandeParDefaut(mode),
            tx, runCatching { emet() }.getOrDefault(false), accordPermis && recepteurSeul() == null)
    }

    /** Why a client may not retune now (null: the SDR receiver may be). */
    private fun recepteurSeul(): String? = when {
        sdr == null -> "pas de récepteur SDR"
        station.etat.value.catConnecte -> "le poste est en CAT"
        station.qo100.etat.value.auPoste -> "QO-100 est au poste"
        else -> null
    }

    override fun accorde(hz: Long): Int {
        if (!accordPermis || recepteurSeul() != null) return RigctldProtocole.ENAVAIL
        val s = sdr ?: return RigctldProtocole.ENAVAIL
        val q = station.qo100.etat.value
        if (q.aLaCle) {
            // QO-100 on the dongle: the downlink (an uplink given is turned into its downlink).
            val dl = if (hz < 3_000_000_000L) Qo100.descenteDepuisMontee(hz) else hz
            if (dl !in Qo100.REGLETTE_BAS_HZ..Qo100.REGLETTE_HAUT_HZ) return RigctldProtocole.EINVAL
            station.qo100.descente(dl, GardeQo100.Origine.MAIN)
            s.decalageHz = 0
            return 0
        }
        if (hz !in 24_000_000L..12_000_000_000L) return RigctldProtocole.EINVAL
        val d = s.etat.value
        // Following a satellite: a nearby frequency is the offset from it, Doppler kept.
        if (d.actif && s.suitSatellite && d.satellite.isNotBlank() && abs(hz - d.cielHz) <= 80_000L) s.decalageHz = (hz - d.cielHz).toInt()
        else { s.suitSatellite = false; s.manuelleHz = hz; s.decalageHz = 0 }
        return 0
    }

    override fun mode(mode: String, bandeHz: Int): Int {
        if (!accordPermis || recepteurSeul() != null) return RigctldProtocole.ENAVAIL
        val s = sdr ?: return RigctldProtocole.ENAVAIL
        val voulu = when (mode) {
            "USB", "PKTUSB", "CW" -> "USB"
            "LSB", "PKTLSB", "CWR" -> "LSB"
            "AM" -> "AM"
            "FM", "PKTFM" -> "NFM"
            else -> return RigctldProtocole.EINVAL
        }
        if (s.modeEffectif() != voulu) s.modeChoisi = voulu
        if (bandeHz > 0) station.settings.sdrBandwidthHz = bandeHz.coerceIn(0, 24_000)
        return 0
    }

    /** For the page. */
    fun json(): org.json.JSONObject = org.json.JSONObject().put("on", actif).put("port", port).put("ecoute", ecoute)
        .put("clients", nbClients).put("erreur", erreur).put("reseau", reseau).put("reseauLocal", surReseau)
        .put("ouvert", ouvertAuReseau).put("accord", accordPermis).put("montee", montee)
        .put("lecture", runCatching { lecture() }.getOrNull()?.let {
            org.json.JSONObject().put("hz", it.frequenceHz).put("mode", it.mode).put("txHz", it.emissionHz ?: org.json.JSONObject.NULL).put("ptt", it.emet)
        } ?: org.json.JSONObject.NULL)

    companion object {
        const val CLE_ACTIF = "pc_rigctld"
        const val CLE_PORT = "pc_rigctld_port"
        const val CLE_RESEAU = "pc_rigctld_reseau"
        const val CLE_ACCORD = "pc_rigctld_accord"
        const val CLE_MONTEE = "pc_rigctld_montee"
    }
}
