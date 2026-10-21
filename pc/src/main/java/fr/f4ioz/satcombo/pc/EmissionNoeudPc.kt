/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.BladerfEmission
import fr.f4ioz.satcombo.sdr.EmetteurDatv
import fr.f4ioz.satcombo.sdr.EtatEmission
import fr.f4ioz.satcombo.sdr.FluxNoeud
import fr.f4ioz.satcombo.sdr.PlutoEmission
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.ReglesNoeud
import org.json.JSONObject
import java.io.OutputStream
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * On a SatMe-Node: its transmitter (the Pluto or the bladeRF) sending what
 * SatMe-PC modulates — DATV, or narrow band (SSB, FM) — the PC's rules
 * checked there first ([fr.f4ioz.satcombo.sdr.EmetteurNoeud]).
 *
 * Guards of its own, on the node:
 * - **« émission autorisée sur ce node »**, off by default, changed only
 *   from the node itself (its page opened on the node, or through an SSH
 *   tunnel): nothing transmits while it is off, and turning it off stops a
 *   transmission at once;
 * - its bands, its rate, its duration and its own gain ceiling ([ReglesNoeud]);
 * - **the watchdog**: nothing from the PC (samples or « still there ») for
 *   [chienMs] and the transmission stops; the connection closed stops it too;
 * - its device never transmits while it receives (the SDR service, DATV).
 *
 * The device's own class does the radio, with all its guards (LO powered
 * down first on every stop).
 */
class EmissionNoeudPc(
    private val stockage: Stockage,
    /** A transmitter for the device ("pluto", "bladerf"), with its ceiling. */
    private val emetteur: (String, Double) -> EmetteurLocal,
    /** Why the device is held for reception on the node, or null. */
    private val occupe: (String) -> String? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val chienMs: Long = ReglesNoeud.CHIEN_MS,
) {
    /** A transmitter on the node, started with plain settings. */
    interface EmetteurLocal : EmetteurDatv {
        val libelle: String
        fun demarre(hz: Long, debit: Long, gainDb: Double, dureeMs: Long, bandeHz: Long, plafondDb: Double, paires: Int,
                    garde: () -> String?, producteur: (ShortArray) -> Unit): String?
    }

    /** « émission autorisée sur ce node »: off unless set on the node itself. */
    val autorisee: Boolean get() = stockage.getBoolean(CLE_AUTORISEE, false)
    /** The transmitter: [SdrNoeudPc.AUCUN], "pluto" or "bladerf". */
    val appareil: String get() = stockage.getString(CLE_APPAREIL, SdrNoeudPc.AUCUN).takeIf { it in SdrNoeudPc.APPAREILS } ?: SdrNoeudPc.AUCUN
    /** The node's own ceiling (dB, ≤ 0). */
    val gainMaxDb: Double get() = stockage.getFloat(CLE_GAIN_MAX, ReglesNoeud.GAIN_MAX_DEFAUT_DB.toFloat()).toDouble().coerceIn(ReglesNoeud.GAIN_MIN_DB, 0.0)

    /**
     * The node's settings, changed only from the node itself ([local]: the
     * request came from its own loopback). Null once done, else why not.
     */
    fun regleLocal(local: Boolean, autorise: Boolean?, appareil: String?, gainMax: Double?): String? {
        if (!local) return NoeudPc.SUR_PLACE
        val e = stockage.edit()
        autorise?.let { e.putBoolean(CLE_AUTORISEE, it) }
        appareil?.takeIf { it in SdrNoeudPc.APPAREILS }?.let { e.putString(CLE_APPAREIL, it) }
        gainMax?.takeIf { !it.isNaN() }?.let { e.putFloat(CLE_GAIN_MAX, it.coerceIn(ReglesNoeud.GAIN_MIN_DB, 0.0).toFloat()) }
        e.apply()
        if (autorise == false) arrete("émission désautorisée sur le node")
        if (appareil != null && appareil != tient) arrete("émetteur du node changé")
        return null
    }

    data class Etat(val emet: Boolean = false, val client: String = "", val appareil: String = "", val frequenceHz: Long = 0L,
                    val gainDb: Double = 0.0, val depuisMs: Long = 0L, val raison: String = "", val erreur: String = "")

    @Volatile var etat = Etat(); private set
    @Volatile private var tx: EmetteurLocal? = null
    @Volatile private var socket: Socket? = null
    /** The device transmitting now, or null. */
    val tient: String? get() = etat.takeIf { it.emet }?.appareil

    /** Why a transmission asked now would be refused before its own rules, or null. */
    fun refus(appareilDemande: String): String? = when {
        !autorisee -> "émission non autorisée sur ce node : « émission autorisée » se coche sur la page du node, depuis le node lui-même"
        appareil == SdrNoeudPc.AUCUN -> "aucun émetteur choisi sur ce node (sa page, depuis le node)"
        appareilDemande != appareil -> "ce node émet avec " + SdrNoeudPc.nomAppareil(appareil) + " : le choisir comme émetteur dans SatMe-PC"
        etat.emet -> "le node émet déjà"
        else -> occupe(appareil)
    }

    /**
     * One transmission from the PC on [c] ([q]: its settings): checked, the
     * device started, its samples taken from the connection until it stops.
     */
    fun sert(c: Socket, q: Map<String, String>, o: OutputStream) {
        val app = q["appareil"].orEmpty()
        val hz = q["hz"]?.toLongOrNull() ?: 0L
        val debit = q["debit"]?.toLongOrNull() ?: 0L
        val gain = q["gain"]?.toDoubleOrNull() ?: Double.NaN
        val duree = q["duree"]?.toLongOrNull() ?: 0L
        val bande = q["bande"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val plafondPc = q["plafond"]?.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: 0.0
        val paires = q["paires"]?.toIntOrNull() ?: 0
        val e: EmetteurLocal
        val gainNoeud: Double
        val etatSur = java.util.concurrent.atomic.AtomicLong(horloge())
        val file = ArrayBlockingQueue<ShortArray>(PROFONDEUR)
        val fini = java.util.concurrent.atomic.AtomicBoolean(false)
        synchronized(this) {
            (refus(app) ?: ReglesNoeud.refus(hz, debit, duree, gain, paires))?.let { return refuse(o, it) }
            val plafond = minOf(plafondPc, gainMaxDb, 0.0)
            gainNoeud = ReglesNoeud.gain(gain, plafond, gainMaxDb)
            e = emetteur(app, minOf(gainMaxDb, 0.0))
            // The device's samples: what came from the PC (late: the device counts an underrun); zeros only once it ends.
            var reste: ShortArray? = null; var pos = 0
            val producteur: (ShortArray) -> Unit = { b ->
                var k = 0
                while (k < b.size) {
                    val r = reste ?: file.poll(100, TimeUnit.MILLISECONDS)
                    if (r == null) { if (fini.get()) break else continue }
                    val a = minOf(b.size - k, r.size - pos)
                    System.arraycopy(r, pos, b, k, a)
                    k += a; pos += a
                    if (pos == r.size) { reste = null; pos = 0 } else reste = r
                }
                if (k < b.size) java.util.Arrays.fill(b, k, b.size, 0)
            }
            val garde: () -> String? = {
                when {
                    fini.get() -> "SatMe-PC a arrêté l'émission"
                    !autorisee -> "émission désautorisée sur le node"
                    horloge() - etatSur.get() > chienMs -> "plus rien de SatMe-PC depuis " + String.format(Locale.FRANCE, "%.1f", chienMs / 1000.0) + " s : émission coupée"
                    else -> null
                }
            }
            val refusTx = e.demarre(hz, debit, gainNoeud, duree, bande, plafond, paires, garde, producteur)
            if (refusTx != null) return refuse(o, refusTx)
            tx = e; socket = c
            etat = Etat(true, c.inetAddress?.hostAddress.orEmpty(), app, hz, gainNoeud, horloge())
        }
        c.soTimeout = 0
        c.tcpNoDelay = true
        o.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-satme-iq\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        FluxNoeud.ecritPreambule(o, JSONObject().put("appareil", app).put("libelle", e.libelle).put("gain", gainNoeud).put("hz", hz))
        val ec = FluxNoeud.Ecrivain(o, horloge)
        // The watchdog: a thread of its own that never writes to the network (a PC that stopped reading cannot hold it).
        val chien = Thread({
            while (!fini.get()) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                val t = horloge()
                if (!e.etatCommun().emet) break
                if (t - etatSur.get() > chienMs) { stoppe(e, "plus rien de SatMe-PC depuis " + String.format(Locale.FRANCE, "%.1f", chienMs / 1000.0) + " s : émission coupée"); break }
                if (!autorisee) { stoppe(e, "émission désautorisée sur le node"); break }
            }
            fini.set(true)
            // The last status said if the PC still reads, then the connection closed.
            Thread({ runCatching { ec.texte(statut(e.etatCommun())) } }, "satme-noeud-tx-fin").apply { isDaemon = true; start() }.join(500)
            runCatching { c.close() }
        }, "satme-noeud-tx-chien").apply { isDaemon = true; start() }
        // The status for the PC, twice a second.
        Thread({
            while (!fini.get()) {
                runCatching { ec.texte(statut(e.etatCommun())) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "satme-noeud-tx-statut").apply { isDaemon = true; start() }
        try {
            val l = FluxNoeud.Lecteur(c.getInputStream())
            while (!fini.get()) {
                val t = l.lit()
                etatSur.set(horloge())
                when (t.type) {
                    FluxNoeud.IQ -> {
                        val v = t.iq ?: continue
                        // The device sets the pace: the reader waits for room (the PC waits behind it).
                        while (!fini.get() && !file.offer(v, 200, TimeUnit.MILLISECONDS)) etatSur.set(horloge())
                    }
                    FluxNoeud.TEXTE -> {
                        val m = t.texte.orEmpty().trim()
                        if (m == "stop") { stoppe(e, "arrêt demandé"); break }
                        if (m.startsWith("gain ")) m.substringAfter(' ').toDoubleOrNull()?.let { g ->
                            e.regleGain(ReglesNoeud.gain(g, minOf(plafondPc, gainMaxDb, 0.0), gainMaxDb))?.let { etat = etat.copy(gainDb = it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            stoppe(e, "SatMe-PC s'est déconnecté : émission coupée")
        } finally {
            fini.set(true)
            if (e.etatCommun().emet) stoppe(e, "SatMe-PC s'est déconnecté : émission coupée")
            chien.join(1500)
            runCatching { c.close() }
        }
    }

    private fun statut(pe: EtatEmission): String = JSONObject().put("emet", pe.emet).put("gain", pe.gainDb).put("tampons", pe.tampons)
        .put("manques", pe.manques).put("raison", pe.raison).put("erreur", pe.erreur).toString()

    private fun stoppe(e: EmetteurLocal, raison: String) {
        if (e.etatCommun().emet) e.arrete(raison)
        synchronized(this) {
            if (tx === e) { tx = null; socket = null }
            val pe = e.etatCommun()
            if (etat.emet) etat = etat.copy(emet = false, raison = pe.raison.ifBlank { raison }, erreur = pe.erreur)
        }
    }

    /** Stops whatever transmits now (the node's page, its settings changed, its end). */
    fun arrete(raison: String = "arrêt demandé sur le node") {
        val e = tx ?: return
        stoppe(e, raison)
        runCatching { socket?.close() }
    }

    private fun refuse(o: OutputStream, pourquoi: String) {
        etat = etat.copy(raison = "", erreur = pourquoi)
        val b = JSONObject().put("ok", false).put("erreur", pourquoi).toString().toByteArray()
        o.write("HTTP/1.1 409 Conflict\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b); o.flush()
    }

    fun json(): JSONObject {
        val e = etat
        return JSONObject().put("appareil", appareil).put("autorisee", autorisee).put("gainMax", gainMaxDb).put("emet", e.emet)
            .put("client", e.client).put("hz", e.frequenceHz).put("gain", e.gainDb).put("raison", e.raison).put("erreur", e.erreur)
    }

    companion object {
        const val CLE_AUTORISEE = "noeud_tx_autorise"
        const val CLE_APPAREIL = "noeud_tx_appareil"
        const val CLE_GAIN_MAX = "noeud_tx_gain_max"
        const val PROFONDEUR = 8

        /** The node's real transmitters: the Pluto at its address, the bladeRF through bladeRF-cli. */
        fun reels(stockage: Stockage): (String, Double) -> EmetteurLocal = { app, gainMax ->
            when (app) {
                "pluto" -> {
                    val p = PlutoEmission(stockage.getString("noeud_pluto_hote", PlutoSdr.HOTE) ?: PlutoSdr.HOTE, stockage.getInt("noeud_pluto_port", PlutoSdr.PORT), gainMaxDb = gainMax)
                    object : EmetteurLocal, EmetteurDatv by p {
                        override val libelle = "ADALM-PLUTO"
                        override fun demarre(hz: Long, debit: Long, gainDb: Double, dureeMs: Long, bandeHz: Long, plafondDb: Double, paires: Int,
                                             garde: () -> String?, producteur: (ShortArray) -> Unit): String? =
                            p.demarre(PlutoEmission.Reglage(hz, debit, gainDb, dureeMs, bandeHz, plafondDb, paires, garde), producteur)
                    }
                }
                "bladerf" -> {
                    val prog = OutilsPc.trouve("bladeRF-cli")
                    val b = prog?.let { BladerfEmission(it, gainMaxDb = gainMax) }
                    object : EmetteurLocal {
                        override val libelle = "bladeRF 2.0"
                        override val nom = "bladeRF 2.0"
                        override fun etatCommun() = b?.etatCommun() ?: EtatEmission()
                        override fun arrete(raison: String) { b?.arrete(raison) }
                        override fun regleGain(db: Double) = b?.regleGain(db)
                        override fun demarre(hz: Long, debit: Long, gainDb: Double, dureeMs: Long, bandeHz: Long, plafondDb: Double, paires: Int,
                                             garde: () -> String?, producteur: (ShortArray) -> Unit): String? =
                            b?.demarre(BladerfEmission.Reglage(hz, debit, gainDb, dureeMs, bandeHz, plafondDb, paires, garde = garde), producteur)
                                ?: "bladeRF-cli introuvable sur le node : sudo apt install bladerf"
                    }
                }
                else -> throw IllegalArgumentException(app)
            }
        }
    }
}
