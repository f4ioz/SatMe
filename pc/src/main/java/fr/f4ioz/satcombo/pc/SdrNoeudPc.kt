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
import fr.f4ioz.satcombo.sdr.BladerfSdr
import fr.f4ioz.satcombo.sdr.DecimateurDemiBande
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.sdr.FluxNoeud
import fr.f4ioz.satcombo.sdr.PlutoSdr
import fr.f4ioz.satcombo.sdr.SourceIq
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * On a SatMe-Node: its SDR (the bladeRF or the Pluto, whichever is set for
 * it, apart from the DATV receiver) lent to SatMe-PC as a receiver — the
 * MiniTiouner on one LNB output, the bladeRF on the other, both at once.
 *
 * One client at a time: the device opened when it connects, tuned as it
 * asks (centre, gain, correction on the same connection), its samples at
 * [Dsp.RTL_RATE] brought down 2^etages times when asked (half-band steps,
 * 16-bit), sent in frames ([FluxNoeud]). A newer connection takes over
 * (the PC coming back after a network loss); a client silent for
 * [silenceMs] or gone lets the device go.
 *
 * Receive only: the device is opened by its receiver's class, which never
 * writes the TX side.
 */
class SdrNoeudPc(
    private val stockage: Stockage,
    /** Opens the device ("bladerf", "pluto") as a receiver; throws with the reason. */
    private val ouvre: (String) -> SourceIq,
    /** Why the device is held elsewhere on the node (DATV, the transmitter), or null. */
    private val occupe: (String) -> String? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val silenceMs: Long = 10_000L,
) {
    /** The device the node lends as its SDR: [AUCUN], "bladerf" or "pluto" (kept). */
    var appareil: String
        get() = stockage.getString(CLE_APPAREIL, AUCUN).takeIf { it in APPAREILS } ?: AUCUN
        set(v) { stockage.edit().putString(CLE_APPAREIL, if (v in APPAREILS) v else AUCUN).apply() }

    data class Etat(
        val actif: Boolean = false,
        val client: String = "",
        val appareil: String = "",
        val libelle: String = "",
        val centreHz: Long = 0L,
        val debit: Int = 0,
        val gainDixiemes: Int? = null,
        val trames: Long = 0L,
        /** Frames dropped because the network did not keep up. */
        val sautees: Long = 0L,
        val erreur: String = "",
        val depuisMs: Long = 0L,
    )

    @Volatile var etat = Etat(); private set
    /** Bytes sent to SatMe-PC so far (the stream's throughput, for the health page). */
    val octets = java.util.concurrent.atomic.AtomicLong()

    /** The device held now ("bladerf", "pluto"), or null. */
    val tient: String? get() = etat.takeIf { it.actif }?.appareil

    private class Session(val socket: Socket) { @Volatile var fin = false; @Volatile var fini = false }
    @Volatile private var courante: Session? = null
    private val verrou = Object()

    /**
     * Serves one client on [c] (its request read, [q] its settings): the
     * device opened, the stream sent until the client leaves, falls silent,
     * or another takes over. Refused with a reason (HTTP 409) otherwise.
     */
    fun sert(c: Socket, q: Map<String, String>, o: OutputStream) {
        val app = appareil
        if (app == AUCUN) return refus(o, "aucun SDR choisi sur ce node : choisir le bladeRF ou le Pluto (SatMe-PC › Station › SatMe-Node)")
        val etages = q["etages"]?.toIntOrNull()?.coerceIn(0, 3) ?: 0
        val centre = q["centre"]?.toLongOrNull()?.takeIf { it in 1_000_000L..12_000_000_000L }
        val gain = q["gain"]?.let { if (it == "auto") null else it.toIntOrNull()?.coerceIn(0, 1000) }
        val ppm = q["ppm"]?.toIntOrNull()?.coerceIn(-200, 200) ?: 0
        // A newer connection takes the device over: the older one closed, its device let go.
        val s = Session(c)
        synchronized(verrou) {
            courante?.let { a -> a.fin = true; runCatching { a.socket.close() } }
            val fin = horloge() + 4_000
            while (courante != null && courante?.fini == false && horloge() < fin) verrou.wait(100)
            courante = s
        }
        try {
            occupe(app)?.let { return refus(o, it) }
            val src = try { ouvre(app) } catch (x: Exception) { return refus(o, x.message ?: "$app injoignable") }
            try { diffuse(s, src, app, etages, centre, gain, ppm, c, o) } finally { runCatching { src.close() } }
        } finally {
            synchronized(verrou) { s.fini = true; if (courante === s) courante = null; verrou.notifyAll() }
            if (etat.actif) println("SDR : " + etat.client + " parti, " + nomAppareil(app) + " rendu")
            etat = etat.copy(actif = false, client = "")
        }
    }

    private fun diffuse(s: Session, src: SourceIq, app: String, etages: Int, centre0: Long?, gain0: Int?, ppm0: Int, c: Socket, o: OutputStream) {
        val libelle = when (src) { is PlutoSdr -> src.description; is BladerfSdr -> src.description; else -> "" }.ifBlank { nomAppareil(app) }
        val centreA = java.util.concurrent.atomic.AtomicLong(centre0 ?: 0L)
        val centre = centreA.get()
        synchronized(src) {
            src.setSampleRate(Dsp.RTL_RATE)
            if (ppm0 != 0) src.setFreqCorrection(ppm0)
            src.setAgc(gain0 == null)
            src.setGain(gain0)
            if (centre > 0) src.setCenterFreq(centre)
        }
        val debit = Dsp.RTL_RATE shr etages
        c.soTimeout = 0
        c.tcpNoDelay = true
        o.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-satme-iq\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        FluxNoeud.ecritPreambule(o, JSONObject().put("debit", debit).put("centre", centre).put("format", FluxNoeud.FORMAT)
            .put("etages", etages).put("appareil", app).put("libelle", "SDR du SatMe-Node · $libelle").put("bloc", BLOC))
        val e = FluxNoeud.Ecrivain(o, horloge)
        etat = Etat(true, c.inetAddress?.hostAddress.orEmpty(), app, libelle, centre, debit, gain0, depuisMs = horloge())
        println("SDR : " + nomAppareil(app) + " prêté à " + etat.client + " (" + debit + " S/s)")
        val dernierVu = java.util.concurrent.atomic.AtomicLong(horloge())
        // The client's lines: tuning, gain, correction; its « still there ».
        val lecture = Thread({
            runCatching {
                val l = FluxNoeud.Lecteur(c.getInputStream())
                while (!s.fin) {
                    val t = l.lit()
                    dernierVu.set(horloge())
                    if (t.type != FluxNoeud.TEXTE) continue
                    val k = FluxNoeud.litCommande(t.texte.orEmpty()) ?: continue
                    synchronized(src) {
                        when (k.nom) {
                            "centre" -> { val hz = k.valeur!!; centreA.set(hz); src.setCenterFreq(hz); etat = etat.copy(centreHz = hz) }
                            "gain" -> { src.setAgc(k.valeur == null); src.setGain(k.valeur?.toInt()); etat = etat.copy(gainDixiemes = k.valeur?.toInt()) }
                            "ppm" -> src.setFreqCorrection(k.valeur!!.toInt())
                        }
                    }
                }
            }
            s.fin = true
        }, "satme-noeud-sdr-cmd").apply { isDaemon = true; start() }
        // Network slower than the radio: the oldest frame dropped (the PC sees the gap in the sequence).
        val file = ArrayBlockingQueue<Pair<ShortArray, Long>>(PROFONDEUR)
        val envoi = Thread({
            var dernierEtat = 0L
            try {
                while (!s.fin) {
                    val f = file.poll(200, TimeUnit.MILLISECONDS)
                    if (f != null) { e.iq(f.first, f.first.size, f.second, debit); octets.addAndGet(2L * f.first.size + FluxNoeud.ENTETE); etat = etat.copy(trames = e.ecrites) }
                    val t = horloge()
                    if (t - dernierEtat >= 1000) {
                        dernierEtat = t
                        val av = when (src) { is PlutoSdr -> src.avertissement; is BladerfSdr -> src.avertissement; else -> "" }
                        e.texte(JSONObject().put("avertissement", av).put("perdusAppareil", src.perdus).put("sautees", etat.sautees).toString())
                    }
                }
            } catch (_: Exception) { }
            s.fin = true
        }, "satme-noeud-sdr-envoi").apply { isDaemon = true; start() }
        val d = DecimateurDemiBande(etages)
        val lu = ShortArray(BLOC)
        var bloc = ShortArray(BLOC)
        var pos = 0
        val tmp = ShortArray(BLOC + 4)
        var raison = ""
        try {
            while (!s.fin) {
                if (horloge() - dernierVu.get() > silenceMs) { raison = ""; break }
                val n = src.read16(lu, 200)
                if (n < 0) {
                    raison = when (src) { is PlutoSdr -> src.erreur; is BladerfSdr -> src.erreur; else -> null } ?: "le récepteur s'est arrêté"
                    break
                }
                if (n == 0) continue
                val m = d.traite(lu, n, tmp)
                var k = 0
                while (k < m) {
                    val a = minOf(m - k, BLOC - pos)
                    System.arraycopy(tmp, k, bloc, pos, a)
                    pos += a; k += a
                    if (pos == BLOC) {
                        val f = bloc to centreA.get()
                        if (!file.offer(f)) { file.poll(); e.saute(); etat = etat.copy(sautees = etat.sautees + 1); file.offer(f) }
                        bloc = ShortArray(BLOC); pos = 0
                    }
                }
            }
        } finally {
            s.fin = true
            if (raison.isNotEmpty()) {
                etat = etat.copy(erreur = raison)
                // Said to the PC if it still reads; never waited for long.
                Thread({ runCatching { e.texte(JSONObject().put("erreur", raison).toString()) } }, "satme-noeud-sdr-fin")
                    .apply { isDaemon = true; start() }.join(500)
            }
            runCatching { c.close() }
            envoi.join(1000); lecture.join(500)
        }
    }

    private fun refus(o: OutputStream, pourquoi: String) {
        println("SDR refusé : $pourquoi")
        etat = etat.copy(erreur = pourquoi)
        val b = JSONObject().put("ok", false).put("erreur", pourquoi).toString().toByteArray()
        o.write("HTTP/1.1 409 Conflict\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b); o.flush()
    }

    /** Ends the stream now (the board let go); the PC's source tries again by itself. */
    fun arrete() {
        synchronized(verrou) { courante?.let { a -> a.fin = true; runCatching { a.socket.close() } } }
    }

    /** For the node's API and page. */
    fun json(): JSONObject {
        val e = etat
        return JSONObject().put("appareil", appareil).put("actif", e.actif).put("client", e.client).put("libelle", e.libelle)
            .put("centre", e.centreHz).put("debit", e.debit).put("trames", e.trames).put("sautees", e.sautees).put("erreur", e.erreur)
    }

    companion object {
        const val AUCUN = "aucun"
        val APPAREILS = setOf(AUCUN, "bladerf", "pluto")
        const val CLE_APPAREIL = "noeud_sdr_appareil"
        const val BLOC = 16_384
        const val PROFONDEUR = 48

        fun nomAppareil(a: String) = when (a) { "bladerf" -> "bladeRF 2.0"; "pluto" -> "ADALM-PLUTO"; "minitiouner" -> "MiniTiouner"; else -> "aucun" }
    }
}
