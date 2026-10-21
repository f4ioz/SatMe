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

    /** Reads the node's devices now (blocking, off the page's thread). */
    fun lit(): JSONObject? {
        if (!client.associe) { materiel = null; erreur = ""; return null }
        return runCatching { client.materiel() }.onSuccess { materiel = it; materielMs = horloge(); erreur = "" }
            .onFailure { erreur = it.message ?: "node injoignable" }.getOrNull()
    }

    /** Read again when older than a few seconds (a page asking). */
    fun litSiAncien(): JSONObject? = if (horloge() - materielMs > 4_000L) lit() else materiel

    /** The node's SDR, opened as a receiver at [centreHz] with [gain] (tenths of dB, null: AGC) and [ppm]. */
    fun ouvreSdr(centreHz: Long, gain: Int?, ppm: Int): SourceNoeudIq {
        if (!client.associe) throw java.io.IOException("aucun SatMe-Node associé : l'associer dans Station › SatMe-Node")
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
        if (client.associe && horloge() - materielMs > 4_000L && lecture?.isAlive != true)
            lecture = Thread({ lit() }, "satme-noeud-materiel").apply { isDaemon = true; start() }
        if (!client.associe) materiel = null
        return json().put("ok", true)
    }

    /** The page's settings: the stream's weight, « émettre par le node », the node's SDR device (asked to the node). */
    fun action(q: Map<String, String>): JSONObject {
        q["etages"]?.toIntOrNull()?.let { etages = it }
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
    }

    companion object {
        const val CLE_ETAGES = "pc_noeud_sdr_etages"
        const val CLE_TX = "pc_noeud_tx"
    }
}
