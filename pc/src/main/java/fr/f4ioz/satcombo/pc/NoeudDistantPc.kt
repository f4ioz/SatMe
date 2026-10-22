/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.ClientNoeud
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.EmetteurNoeud
import fr.f4ioz.satcombo.sdr.SourceNoeudIq
import org.json.JSONObject

/**
 * The SatMe-Node joined (the DATV tab's), seen from SatMe-PC beyond DATV:
 * its devices and their roles (read every few seconds while a page asks),
 * its SDR as this PC's receiver « SDR du Node », and its transmitter when
 * the operator chose to transmit through it (off by default).
 */
class NoeudDistantPc(
    private val stockage: Stockage,
    val client: ClientNoeud,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** This PC's DATV receiver, the board the node receives DATV with at the next start ([DatvPc.recepteur]). */
    private val recepteurDatv: () -> String = { fr.f4ioz.satcombo.datv.Datv.MINITIOUNER },
    private val choisitRecepteurDatv: (String) -> Unit = {},
    /** This SatMe-PC's version, to tell an older node. */
    private val version: String = versionPc(),
) {
    /**
     * The stream from the node's SDR 2^etages times lighter than the
     * reception's rate (0: 1.0584 MS/s, the whole narrow band transponder
     * and its beacons; 1: 529 kS/s for a slow network).
     */
    var etages: Int
        get() = stockage.getInt(CLE_ETAGES, 0).coerceIn(0, 2)
        set(v) { stockage.edit().putInt(CLE_ETAGES, v.coerceIn(0, 2)).apply() }

    /** « Émettre par le SatMe-Node »: DATV and SSTV transmissions sent to the node's transmitter instead of one on this PC. */
    var emissionParNoeud: Boolean
        get() = stockage.getBoolean(CLE_TX, false)
        set(v) { stockage.edit().putBoolean(CLE_TX, v).apply() }

    /** The last read of the node's devices, and when; or why not. */
    @Volatile var materiel: JSONObject? = null; private set
    @Volatile var materielMs = 0L; private set
    @Volatile var erreur = ""; private set
    /** The node's last health ([fr.f4ioz.satcombo.datv.SanteNoeud]), null for an older node or before the first read. */
    @Volatile var sante: JSONObject? = null; private set

    /**
     * The node's Pi asked from here to restart ([REDEMARRAGE]) or switch off
     * ([ETEINT]), until it answers again as a new boot; "" otherwise. Kept,
     * with the node's address: a PC started again still knows it is off.
     */
    val arretPi: String
        get() = stockage.getString(CLE_ARRET, "").orEmpty().takeIf { stockage.getString(CLE_ARRET_ADRESSE, "") == client.adresse && client.associe }.orEmpty()
    private val arretPiMs: Long get() = stockage.getLong(CLE_ARRET_MS, 0L)
    private fun arretPi(etat: String) {
        stockage.edit().putString(CLE_ARRET, etat).putLong(CLE_ARRET_MS, horloge()).putString(CLE_ARRET_ADRESSE, client.adresse).apply()
    }
    /** The node unreachable at least once since the request (it went down). */
    @Volatile private var absentVu = false
    /** When the node came back after a restart asked from here (0: not lately). */
    @Volatile var revenuMs = 0L; private set

    /** Reads the node's devices and health now (blocking, off the page's thread). */
    fun lit(): JSONObject? {
        if (!client.associe) { materiel = null; sante = null; erreur = ""; return null }
        return runCatching { client.materiel() }.onSuccess { m ->
            materiel = m; materielMs = horloge(); erreur = ""
            sante = runCatching { client.sante() }.getOrNull()
            if (arretPi.isNotEmpty()) revenu(m)
        }.onFailure { erreur = it.message ?: "node injoignable"; if (arretPi.isNotEmpty()) absentVu = true }.getOrNull()
    }

    /** Back after a restart (or switched on again): gone meanwhile, or booted since the request; no longer going down. */
    private fun revenu(m: JSONObject) {
        if (m.optJSONObject("pi")?.optString("arret").orEmpty().isNotEmpty()) return
        val up = sante?.optJSONObject("systeme")?.optLong("uptimeS", -1L) ?: -1L
        val ecoule = (horloge() - arretPiMs) / 1000
        if (absentVu || (up in 0 until ecoule)) {
            println("SatMe-Node revenu après " + ecoule + " s")
            stockage.edit().putString(CLE_ARRET, "").apply()
            absentVu = false; revenuMs = horloge()
        }
    }

    /** Read again when older than a few seconds (a page asking). */
    fun litSiAncien(): JSONObject? = if (horloge() - materielMs > 4_000L) lit() else materiel

    /** The node's SDR, opened as a receiver at [centreHz] with [gain] (tenths of dB, null: AGC) and [ppm]. */
    fun ouvreSdr(centreHz: Long, gain: Int?, ppm: Int): SourceNoeudIq {
        if (!client.associe) throw java.io.IOException("aucun SatMe-Node associé : l'associer dans l'onglet DATV (Changer : son adresse et son code)")
        val (h, p) = client.hotePort()
        return SourceNoeudIq.connecte(h, p, client.cheminAvecCle("/noeud/sdr/flux"), etages, centreHz, gain, ppm, horloge)
    }

    /** The node's transmitter when « Émettre par le SatMe-Node » is chosen and a node is joined; null otherwise (cheap: no network). */
    fun fabriqueTx(gainMaxDb: () -> Double): EmetteurNoeud.Fabrique? {
        if (!emissionParNoeud || !client.associe) return null
        return EmetteurNoeud.Fabrique {
            val (h, p) = client.hotePort()
            EmetteurNoeud(h, p, client.cheminAvecCle("/noeud/tx/flux"), minOf(gainMaxDb(), 0.0), horloge)
        }
    }

    @Volatile private var lecture: Thread? = null

    /** For the page: what was last read, a new read started in the background when it is old (the page never waits for the node). */
    fun jsonPage(): JSONObject {
        val t = horloge()
        // Every 3 s while a page asks (a failed read too: « injoignable » until it answers again); every 30 s once switched off.
        if (client.associe && t - essaiMs > (if (arretPi == ETEINT) 30_000L else 3_000L) && lecture?.isAlive != true) {
            essaiMs = t
            lecture = Thread({ lit() }, "satme-noeud-materiel").apply { isDaemon = true; start() }
        }
        if (!client.associe) materiel = null
        return json().put("ok", true)
    }

    @Volatile private var essaiMs = 0L

    /**
     * The page's settings: the stream's weight, « émettre par le node », the
     * node's SDR device (asked to the node); a supervision action ([q] « quoi »:
     * a service stopped, the node's SatMe started again).
     */
    fun action(q: Map<String, String>): JSONObject {
        q["quoi"]?.let { quoi ->
            if (!client.associe) return JSONObject().put("ok", false).put("erreur", "aucun SatMe-Node associé")
            client.action(quoi)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            essaiMs = 0L
            return json().put("ok", true)
        }
        // The Pi itself restarted or switched off (by the node, once everything there is stopped).
        q["systeme"]?.let { s ->
            if (!client.associe) return JSONObject().put("ok", false).put("erreur", "aucun SatMe-Node associé")
            if (s != "redemarre" && s != "eteint") return JSONObject().put("ok", false).put("erreur", "action inconnue")
            val r = client.systeme(s == "eteint", q["arreteTx"] == "1")
            val ok = r.optBoolean("ok")
            if (ok) { arretPi(if (s == "eteint") ETEINT else REDEMARRAGE); absentVu = false; revenuMs = 0L; essaiMs = 0L }
            return json().put("ok", ok).put("erreur", r.optString("erreur")).put("emet", r.optBoolean("emet")).put("message", r.optString("message"))
        }
        q["etages"]?.toIntOrNull()?.let { etages = it }
        // The node's DATV receiver: this PC's choice, sent at each start (the DATV tab's « Récepteur »).
        q["datv"]?.let { a ->
            if (a !in fr.f4ioz.satcombo.datv.RolesNoeud.DATV) return JSONObject().put("ok", false).put("erreur", "carte inconnue")
            choisitRecepteurDatv(a)
        }
        q["emetteur"]?.let { a ->
            if (!client.associe) return JSONObject().put("ok", false).put("erreur", "aucun SatMe-Node associé")
            client.choisitTx(a)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            lit()
        }
        q["tx"]?.let { emissionParNoeud = it == "1" }
        q["sdr"]?.let { a ->
            if (!client.associe) return JSONObject().put("ok", false).put("erreur", "aucun SatMe-Node associé")
            client.choisitSdr(a)?.let { return JSONObject().put("ok", false).put("erreur", it) }
            lit()
        }
        return json().put("ok", true)
    }

    fun json(): JSONObject {
        val m = materiel
        return JSONObject().put("associe", client.associe).put("adresse", client.adresse).put("nom", client.nom)
            .put("etages", etages).put("emissionParNoeud", emissionParNoeud).put("erreur", erreur)
            .put("materiel", m ?: JSONObject.NULL).put("age", if (m == null) JSONObject.NULL else (horloge() - materielMs) / 1000)
            .put("sante", sante ?: JSONObject.NULL)
            .put("datvChoisi", recepteurDatv()).put("versionPc", version)
            .put("versionNoeud", versionCompare(sante?.optString("version").orEmpty()))
            .put("conflits", org.json.JSONArray(if (m == null) emptyList() else fr.f4ioz.satcombo.datv.RolesNoeud.conflits(recepteurDatv(),
                m.optString("sdr"), m.optString("tx"))))
            .put("pi", piJson())
            // Down on purpose: no alarm for it.
            .put("alertes", fr.f4ioz.satcombo.datv.SanteNoeud.json(if (!client.associe || arretPi.isNotEmpty()) emptyList()
                else if (erreur.isNotBlank()) fr.f4ioz.satcombo.datv.SanteNoeud.alertes(null, erreur)
                else fr.f4ioz.satcombo.datv.SanteNoeud.alertes(sante)))
    }

    /** The Pi's restart or power off asked from here: under way, how long, late; back lately. */
    private fun piJson(): JSONObject {
        val a = arretPi
        val depuis = if (a.isEmpty()) 0L else (horloge() - arretPiMs) / 1000
        val revenu = revenuMs.takeIf { it > 0 && horloge() - it < 120_000L }
        return JSONObject().put("arret", a).put("depuisS", depuis).put("enRetard", a == REDEMARRAGE && depuis > RETARD_S)
            .put("revenuS", revenu?.let { (horloge() - it) / 1000 } ?: JSONObject.NULL)
    }

    /** The node's version against this PC's: « ancienne », « egale », « recente », or "" (one built by hand, or not read). */
    private fun versionCompare(n: String): String = when (val c = fr.f4ioz.satcombo.datv.RolesNoeud.compareVersions(n, version)) {
        null -> ""; 0 -> "egale"; else -> if (c < 0) "ancienne" else "recente"
    }

    companion object {
        const val CLE_ETAGES = "pc_noeud_sdr_etages"
        const val CLE_TX = "pc_noeud_tx"
        private const val CLE_ARRET = "pc_noeud_arret_pi"
        private const val CLE_ARRET_MS = "pc_noeud_arret_pi_ms"
        private const val CLE_ARRET_ADRESSE = "pc_noeud_arret_pi_adresse"
        const val REDEMARRAGE = "redemarrage"
        const val ETEINT = "eteint"
        /** A restart not back after this long: said. */
        const val RETARD_S = 180L
    }
}
