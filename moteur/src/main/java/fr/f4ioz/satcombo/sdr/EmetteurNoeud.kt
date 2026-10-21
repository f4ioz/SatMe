/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * What a SatMe-Node lets through to its transmitter, whatever SatMe-PC
 * asks — a second line behind the PC's own rules ([fr.f4ioz.satcombo.datv.ReglesEmission],
 * [fr.f4ioz.satcombo.sstv.ReglesSstvRf]), which stay the ones that decide.
 */
object ReglesNoeud {
    /** Amateur bands the node's transmitter may be tuned in (kHz): 2 m, 70 cm, 23 cm, 13 cm (QO-100's uplink). */
    val BANDES_KHZ = listOf(144_000L..146_000L, 430_000L..440_000L, 1_240_000L..1_300_000L, 2_300_000L..2_450_000L)
    /** Nothing from SatMe-PC for this long (samples or « still there »): the node stops transmitting. */
    const val CHIEN_MS = 1_500L
    /** The node's own ceiling when none was set there (dB). */
    const val GAIN_MAX_DEFAUT_DB = -10.0
    const val DEBIT_MAX = 61_440_000L
    const val DUREE_MAX_MS = 1_800_000L
    const val GAIN_MIN_DB = -89.75

    /** Why the node will not transmit this, or null. */
    fun refus(hz: Long, debit: Long, dureeMs: Long, gainDb: Double, paires: Int): String? = when {
        BANDES_KHZ.none { hz / 1000 in it } -> "le node n'émet que dans 144–146, 430–440, 1240–1300 ou 2300–2450 MHz (" +
            String.format(Locale.FRANCE, "%.3f", hz / 1e6) + " MHz demandés)"
        debit !in 520_834L..DEBIT_MAX -> "débit d'échantillons non prévu ($debit S/s)"
        dureeMs !in 1L..DUREE_MAX_MS -> "durée d'émission de 30 min au plus"
        gainDb.isNaN() -> "gain d'émission invalide"
        paires !in 1024..(1 shl 20) -> "taille de tampon non prévue"
        else -> null
    }

    /** The gain applied: below the asked one, the transmission's ceiling, the node's own, 0 dB; in 0.25 dB steps down. */
    fun gain(demande: Double, plafondDb: Double, gainMaxNoeudDb: Double): Double =
        (Math.floor(minOf(demande, plafondDb, gainMaxNoeudDb, 0.0) * 4) / 4).coerceAtLeast(GAIN_MIN_DB)
}

/**
 * SatMe-PC's transmitter when it is a SatMe-Node's (its Pluto or its
 * bladeRF): the samples made here exactly as for a transmitter on this
 * computer (the same modulators, the same rules before), sent to the
 * node's `/noeud/tx/flux` in frames ([FluxNoeud]) with « still there »
 * every [vieMs]; the node's state back as status lines.
 *
 * **Stops** on [arrete], at the time allowed, on [Reglage.garde] (checked
 * every 200 ms here, as the local transmitters do), when the node says it
 * stopped, or when the connection breaks — and the node stops by itself
 * within [ReglesNoeud.CHIEN_MS] when this side falls silent.
 */
class EmetteurNoeud(
    private val hote: String,
    private val port: Int,
    /** `/noeud/tx/flux?cle=…`: the node's path with the key. */
    private val chemin: String,
    /** The ceiling set on this PC (Réglages): never above. */
    private val gainMaxDb: Double = -10.0,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val attenteMs: Int = 3_000,
    private val vieMs: Long = 300L,
) : EmetteurDatv {

    fun interface Fabrique { fun cree(): EmetteurNoeud }

    data class Reglage(
        /** The device the PC's rules were checked for ("pluto", "bladerf"): the node refuses another. */
        val appareil: String,
        val frequenceHz: Long,
        val debit: Long,
        val gainDb: Double,
        val dureeMs: Long,
        val bandeHz: Long = 0L,
        val plafondDb: Double = 0.0,
        val paires: Int = 65_536,
        val garde: () -> String? = { null },
    )

    @Volatile var etat = EtatEmission(); private set
    /** The node's transmitter as it named itself. */
    @Volatile var libelle = ""; private set
    override val nom: String get() = "SatMe-Node" + (if (libelle.isNotBlank()) " · $libelle" else "")

    override fun etatCommun(): EtatEmission = etat

    @Volatile private var actif = false
    @Volatile private var socket: Socket? = null
    @Volatile private var ecrivain: FluxNoeud.Ecrivain? = null
    @Volatile private var plafond = 0.0
    private val fils = ArrayList<Thread>()

    fun gainApplique(demande: Double, plafondDb: Double = 0.0): Double = ReglesNoeud.gain(demande, plafondDb, gainMaxDb)

    /**
     * Connects to the node, which checks its own guards (« émission
     * autorisée », its device, its bands), and starts sending [producteur]'s
     * samples ([Reglage.paires] pairs per call). Null once transmitting, else why not.
     */
    @Synchronized
    fun demarre(r: Reglage, producteur: (ShortArray) -> Unit): String? {
        if (actif) return "déjà en émission"
        ReglesNoeud.refus(r.frequenceHz, r.debit, r.dureeMs, r.gainDb, r.paires)?.let { return it }
        plafond = minOf(r.plafondDb, 0.0)
        val gain = gainApplique(r.gainDb, plafond)
        val duree = r.dureeMs.coerceIn(1L, ReglesNoeud.DUREE_MAX_MS)
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(hote, port), attenteMs)
            s.soTimeout = maxOf(attenteMs, 5_000)
            val q = "&appareil=" + r.appareil + "&hz=" + r.frequenceHz + "&debit=" + r.debit + "&gain=" + String.format(Locale.US, "%.2f", gain) +
                "&duree=" + duree + "&bande=" + r.bandeHz + "&plafond=" + String.format(Locale.US, "%.2f", minOf(plafond, gainMaxDb)) + "&paires=" + r.paires
            s.getOutputStream().write(("POST " + chemin + q + " HTTP/1.1\r\nHost: $hote\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            val i = s.getInputStream()
            val code = ligne(i).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("le node ne répond pas en HTTP")
            var longueur = -1
            while (true) {
                val l = ligne(i)
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (code == 404) throw IOException("ce SatMe-Node ne sait pas émettre : le mettre à jour")
            if (code != 200) {
                val corps = if (longueur in 1..65_536) ByteArray(longueur).also { FluxNoeud.litTout(i, it, longueur) } else ByteArray(0)
                val e = runCatching { JSONObject(String(corps, Charsets.UTF_8)).optString("erreur") }.getOrNull()
                throw IOException(e?.ifBlank { null } ?: if (code == 403) "le node refuse la clé : l'associer à nouveau avec son code" else "le node répond $code")
            }
            val p = FluxNoeud.litPreambule(i)
            libelle = p.optString("libelle")
            s.soTimeout = 0
            socket = s
            val e = FluxNoeud.Ecrivain(s.getOutputStream(), horloge)
            ecrivain = e
            val t = horloge()
            actif = true
            etat = EtatEmission(emet = true, debutMs = t, finMs = t + duree, frequenceHz = r.frequenceHz, gainDb = p.optDouble("gain", gain))
            fils.clear()
            fils += Thread({ envoie(r, e, producteur) }, "satme-noeud-tx").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
            fils += Thread({ ecoute(i) }, "satme-noeud-tx-etat").apply { isDaemon = true }
            fils += Thread({ veille(r, e) }, "satme-noeud-tx-garde").apply { isDaemon = true }
            fils.forEach { it.start() }
            return null
        } catch (x: IOException) {
            runCatching { s.close() }
            return x.message ?: "SatMe-Node injoignable"
        }
    }

    private fun envoie(r: Reglage, e: FluxNoeud.Ecrivain, producteur: (ShortArray) -> Unit) {
        try {
            while (actif) {
                val b = ShortArray(2 * r.paires)
                producteur(b)
                if (!actif) break
                e.iq(b, b.size, r.frequenceHz, r.debit.toInt())
                etat = etat.copy(tampons = etat.tampons + 1)
            }
        } catch (x: Exception) {
            if (actif) coupe("", "liaison avec le node perdue : " + (x.message ?: "coupée"))
        }
    }

    /** The node's status lines: its gain, its counts, its stop and why. */
    private fun ecoute(i: InputStream) {
        try {
            val l = FluxNoeud.Lecteur(i)
            while (actif) {
                val t = l.lit()
                if (t.type != FluxNoeud.TEXTE) continue
                val j = runCatching { JSONObject(t.texte!!) }.getOrNull() ?: continue
                etat = etat.copy(gainDb = j.optDouble("gain", etat.gainDb), manques = j.optLong("manques", etat.manques))
                if (!j.optBoolean("emet", true)) { coupe(j.optString("raison").ifBlank { "arrêtée par le node" }, j.optString("erreur")); return }
            }
        } catch (x: Exception) {
            if (actif) coupe("", "liaison avec le node perdue : " + (x.message ?: "coupée"))
        }
    }

    private fun veille(r: Reglage, e: FluxNoeud.Ecrivain) {
        var derniereGarde = 0L
        while (actif) {
            try { Thread.sleep(vieMs) } catch (_: InterruptedException) { return }
            if (!actif) return
            runCatching { e.vie() }
            val t = horloge()
            if (t - derniereGarde < 200L) continue
            derniereGarde = t
            val raison = if (t >= etat.finMs) "durée maximale atteinte" else runCatching { r.garde() }.getOrElse { "garde : " + it.message }
            if (raison != null) coupe(raison, "")
        }
    }

    override fun arrete(raison: String) = coupe(raison, "")

    override fun regleGain(db: Double): Double? {
        val e = ecrivain ?: return null
        if (!actif || db.isNaN()) return null
        val g = gainApplique(db, plafond)
        return try { e.texte("gain " + String.format(Locale.US, "%.2f", g)); etat = etat.copy(gainDb = g); g } catch (x: IOException) { null }
    }

    /** The end: the node told to stop, the connection closed (which stops it anyway). */
    private fun coupe(raison: String, erreur: String) {
        synchronized(this) { if (!actif) return; actif = false }
        runCatching { ecrivain?.texte("stop") }
        runCatching { socket?.shutdownOutput() }
        runCatching { socket?.close() }
        ecrivain = null
        fils.filter { it !== Thread.currentThread() }.forEach { it.interrupt() }
        etat = etat.copy(emet = false, raison = raison.ifEmpty { "erreur" }, erreur = erreur)
    }

    private fun ligne(i: InputStream): String {
        val b = StringBuilder()
        while (b.length < 4096) {
            val c = i.read()
            if (c < 0) throw IOException("le node a fermé la connexion")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
        }
        return b.toString()
    }
}
