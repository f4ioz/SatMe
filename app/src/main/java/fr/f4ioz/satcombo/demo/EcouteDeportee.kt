/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Remote listening: hearing a SatMe station from another phone.
 *
 * **Why a native client when the web page exists.** The browser costs one to
 * two seconds of delay — playback buffer, audio API scheduling, autoplay
 * policy. Acceptable for an audience watching a demonstration, frustrating for
 * genuine remote ears: someone at the rig while you hold the antenna outside.
 * Here `AudioTrack` in stream mode plays the bytes as they arrive, and the
 * delay drops to a tenth or two.
 *
 * **What it deliberately does not do: the display.** The pass screen and the
 * web page already represent the same data; a third representation would end
 * up contradicting them, as the frequencies did for three versions. Audio
 * alone, and nothing else.
 */
object EcouteDeportee {

    data class Etat(
        val actif: Boolean = false,
        val connecte: Boolean = false,
        val koRecus: Long = 0,
        /** Connected, but the station has sent nothing for a while. */
        val muette: Boolean = false,
        val incident: String = ""
    )

    private val _etat = MutableStateFlow(Etat())
    val etat = _etat.asStateFlow()

    /**
     * What the station reports: its telemetry, exactly as sent.
     *
     * **Nothing is recomputed here, and that is the point.** The station stays
     * the only one computing position, frequencies and Doppler; the listening
     * screen merely shows what it receives. This is what prevents a third
     * representation of the same data — the one that made the frequencies
     * drift by five kilohertz between the pass screen and the web page.
     */
    data class Station(
        val nom: String = "",
        val satellite: String = "",
        val azimut: Double? = null,
        val elevation: Double? = null,
        val trace: List<Pair<Double, Double>> = emptyList(),
        val rxHz: Long? = null,
        val txHz: Long? = null,
        val enEmission: Boolean = false,
        val antenneAz: Double? = null,
        val antenneEl: Double? = null,
        val heure: String = "",
        val contacts: List<Contact> = emptyList()
    )

    data class Contact(val heure: String, val indicatif: String, val locator: String)

    private val _station = MutableStateFlow(Station())
    val station = _station.asStateFlow()

    @Volatile private var enMarche = false
    private var fil: Thread? = null

    /** The rate of the served stream: sixteen bit, mono. Set by the server. */
    private const val CADENCE = 22050

    /**
     * Connects to a station.
     *
     * [adresse] is the address shown by the operator, token included. The audio
     * route is appended here: asking the operator to type the full address of a
     * stream would be asking them to know our routes.
     */
    fun demarre(adresse: String) {
        if (enMarche) return
        val base = adresse.trim().trimEnd('/')
        if (base.isBlank()) {
            _etat.value = Etat(incident = "adresse vide")
            return
        }
        enMarche = true
        _etat.value = Etat(actif = true)
        fil = thread(name = "EcouteDeportee", isDaemon = true) { boucle("$base/son.pcm") }
        // Telemetry gets its own thread: audio must never wait for text to be
        // parsed, and a failure in one must not cut the other.
        thread(name = "TelemetrieDeportee", isDaemon = true) { suitEtat("$base/etat") }
    }

    fun arrete() {
        enMarche = false
        fil = null
        _etat.value = Etat()
        _station.value = Station()
    }

    /**
     * Follows the station's event stream.
     *
     * The same one the web page reads: "data: { … }" lines over a connection
     * that never closes. The format is reused rather than a second one being
     * invented — two protocols for the same information would end up
     * diverging.
     */
    private fun suitEtat(url: String) {
        var connexion: HttpURLConnection? = null
        try {
            connexion = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 0
                useCaches = false
            }
            if (connexion.responseCode != 200) return
            connexion.inputStream.bufferedReader().use { lecteur ->
                while (enMarche) {
                    val ligne = lecteur.readLine() ?: break
                    if (!ligne.startsWith("data:")) continue
                    lit(ligne.removePrefix("data:").trim())
                }
            }
        } catch (e: Exception) {
            // Telemetry may be missing without harming the listening: audio is
            // what matters, the display is a comfort.
        } finally {
            runCatching { connexion?.disconnect() }
            _station.value = Station()
        }
    }

    private fun lit(json: String) {
        runCatching {
            val o = org.json.JSONObject(json)
            fun nombre(cle: String): Double? =
                if (o.isNull(cle)) null else o.optDouble(cle).takeIf { !it.isNaN() }
            val trace = ArrayList<Pair<Double, Double>>()
            val t = o.optJSONArray("trace")
            if (t != null) for (i in 0 until t.length()) {
                val p = t.optJSONArray(i) ?: continue
                trace.add(p.optDouble(0) to p.optDouble(1))
            }
            val contacts = ArrayList<Contact>()
            val q = o.optJSONArray("qso")
            if (q != null) for (i in 0 until q.length()) {
                val c = q.optJSONObject(i) ?: continue
                contacts.add(Contact(c.optString("h"), c.optString("c"), c.optString("l")))
            }
            _station.value = Station(
                nom = o.optString("station"),
                satellite = o.optString("sat"),
                azimut = nombre("az"),
                elevation = nombre("el"),
                trace = trace,
                rxHz = if (o.isNull("rx")) null else o.optLong("rx"),
                txHz = if (o.isNull("tx")) null else o.optLong("tx"),
                enEmission = o.optBoolean("ptt"),
                antenneAz = nombre("antaz"),
                antenneEl = nombre("antel"),
                heure = o.optString("heure"),
                contacts = contacts)
        }
    }

    private fun boucle(url: String) {
        var piste: AudioTrack? = null
        var connexion: HttpURLConnection? = null
        var recus = 0L
        // The last moment bytes arrived: this is what tells a silent station
        // from a broken link.
        var dernierOctet = System.currentTimeMillis()
        try {
            connexion = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                // No read timeout: an endless stream sometimes stays silent
                // for several seconds between transmissions, and cutting on
                // that would be cutting at the worst moment.
                readTimeout = 0
                useCaches = false
            }
            if (connexion.responseCode != 200) {
                _etat.value = Etat(actif = true, incident = "réponse ${connexion.responseCode}")
                return
            }

            // The minimum buffer sets the delay. We take the smallest the
            // system accepts, doubled — below that, the slightest network
            // hiccup becomes audible.
            val mini = AudioTrack.getMinBufferSize(
                CADENCE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            piste = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CADENCE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(mini * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            piste.play()
            _etat.value = Etat(actif = true, connecte = true)

            val entree = connexion.inputStream
            val bloc = ByteArray(2048)
            var depuis = System.currentTimeMillis()
            while (enMarche) {
                val n = entree.read(bloc)
                if (n < 0) break
                if (n > 0) {
                    piste.write(bloc, 0, n)
                    recus += n
                }
                // State is refreshed only once per second: the stream arrives
                // in small blocks, and notifying the interface each time would
                // be more work than playing the sound itself.
                val t = System.currentTimeMillis()
                if (n > 0) dernierOctet = t
                if (t - depuis >= 1000) {
                    depuis = t
                    // **Connected but silent is not a fault.** The station
                    // only sends audio while a recording runs. Without saying
                    // so, the screen showed "Connected · 0 kB" and sent the
                    // operator hunting a fault that did not exist.
                    _etat.value = Etat(actif = true, connecte = true,
                        koRecus = recus / 1024,
                        muette = t - dernierOctet > 3000)
                }
            }
        } catch (e: Exception) {
            if (enMarche) {
                _etat.value = Etat(actif = true,
                    incident = e.javaClass.simpleName + " " + (e.message ?: ""))
            }
        } finally {
            runCatching { piste?.stop() }
            runCatching { piste?.release() }
            runCatching { connexion?.disconnect() }
            if (enMarche) {
                // The stream stopped on its own: say so, rather than leaving
                // the screen showing "connected" in front of silence.
                enMarche = false
                _etat.value = _etat.value.copy(actif = false, connecte = false,
                    incident = _etat.value.incident.ifBlank { "flux interrompu" })
            }
        }
    }
}
