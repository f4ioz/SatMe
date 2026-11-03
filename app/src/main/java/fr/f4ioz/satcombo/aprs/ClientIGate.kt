/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.aprs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone's receive-only iGate: the APRS frames it hears on the air (its
 * sound, the SDR dongle, a KISS radio listening) passed to APRS-IS by the
 * rules of [IGate] — the same rules as SatMe-PC's.
 *
 * **Off by default**, and nothing without the operator's own settings: the
 * iGate's callsign, the passcode he typed, the server. The passcode lives in
 * memory and on the socket only, in the login line: never in a state, an
 * error or a log.
 *
 * **RF → IS only.** What the server sends is read and thrown away (its login
 * answer and its keep-alives only are looked at): nothing reaches a radio,
 * nothing here can make one transmit. No position beacon is sent, and no
 * filter is asked of the server (it then sends nothing but keep-alives).
 *
 * Without Android: tested on the JVM against a local fake server.
 */
class ClientIGate(
    /** The operator's settings, read at each turn of the loop. */
    private val reglages: () -> Reglages,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Opens the connection (a test's fake server is local). */
    private val ouvre: (String, Int) -> Socket = { h, p -> Socket().apply { connect(InetSocketAddress(h, p), 10_000) } },
    private val logiciel: String = "SatMe",
    private val version: () -> String = { "dev" },
    demarre: Boolean = true,
) {
    /** What the operator set. [passcode] stays in memory and on the socket. */
    class Reglages(val actif: Boolean, val serveur: String, val indicatif: String, val passcode: String) {
        override fun toString() = "Reglages(actif=$actif, serveur=$serveur, indicatif=$indicatif)"
    }

    /** What is missing to start, as a stable code (the page says it), or null. */
    fun manque(r: Reglages = reglages()): String? = when {
        !IGate.indicatifValide(r.indicatif) -> "indicatif"
        !IGate.passcodeValide(r.passcode) -> "passcode"
        IGate.serveur(r.serveur.ifBlank { IGate.SERVEUR_DEFAUT }) == null -> "serveur"
        else -> null
    }

    data class Etat(
        /** "coupe", "manque", "connexion", "identification", "connecte", "non-verifie", "attente". */
        val phase: String = "coupe",
        /** With [phase] "manque": what is missing ("indicatif", "passcode", "serveur"). */
        val manque: String = "",
        val serveur: String = "",
        val nomServeur: String = "",
        val depuisMs: Long = 0L,
        /** Why the last session ended ("fermee", "muet", "perdue:<class>", "impossible:<class>"); never a message. */
        val erreur: String = "",
        val prochainEssaiMs: Long = 0L,
        val transmis: Int = 0,
        /** Refusals by [IGate.Refus.code]. */
        val refuses: Map<String, Int> = emptyMap(),
        /** The last lines gone, newest first: (ms, line). */
        val derniers: List<Pair<Long, String>> = emptyList(),
        /** The last refusals, newest first: (ms, frame, reason code). */
        val derniersRefus: List<Triple<Long, String, String>> = emptyList(),
    ) {
        val connecte: Boolean get() = phase == "connecte" || phase == "non-verifie" || phase == "identification"
        val verifie: Boolean get() = phase == "connecte"
    }

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    @Volatile private var connecte = false
    @Volatile private var verifie = false

    private val filtre = IGate.Filtre()
    private val file = LinkedBlockingQueue<String>(FILE_MAX)
    @Volatile private var generation = 0

    /** The settings changed: the session in progress is left, the queue emptied. */
    fun change() { generation++; file.clear() }

    private fun maj(f: (Etat) -> Etat) { synchronized(_etat) { _etat.value = f(_etat.value) } }

    private fun refuse(t: Trame, r: IGate.Refus) = maj { e ->
        e.copy(refuses = e.refuses + (r.code to (e.refuses[r.code] ?: 0) + 1),
            derniersRefus = (listOf(Triple(horloge(), t.tnc2().take(200), r.code)) + e.derniersRefus).take(10))
    }

    /**
     * A frame heard on the air, at [quand]. Ignored when the iGate is off;
     * otherwise gated or refused, and counted. Only frames heard live on RF
     * come here: not what this phone sent, nor a recording read again.
     */
    fun recoit(t: Trame, quand: Long = horloge()) {
        val r = reglages()
        if (!r.actif) return
        val (ligne, refus) = filtre.passe(t, quand, r.indicatif)
        if (refus != null || ligne == null) { refuse(t, refus ?: IGate.Refus.TIERS); return }
        when {
            !connecte -> refuse(t, IGate.Refus.HORS_LIGNE)
            !verifie -> refuse(t, IGate.Refus.NON_VERIFIE)
            !file.offer(ligne) -> refuse(t, IGate.Refus.FILE_PLEINE)
        }
    }

    // ------------------------------------------------------------ the connection

    @Volatile private var socket: Socket? = null
    @Volatile private var arrete = false
    private var attenteMs = ATTENTE_MIN_MS
    @Volatile private var verifieTenu = false

    private val fil: Thread? = if (demarre) Thread({ boucle() }, "satme-igate").apply { isDaemon = true; start() } else null

    fun arrete() { arrete = true; ferme(); fil?.interrupt() }

    private fun ferme() {
        connecte = false; verifie = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun dort(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun boucle() {
        while (!arrete) {
            val r = runCatching { reglages() }.getOrNull()
            val m = if (r?.actif == true) manque(r) else null
            if (r == null || !r.actif || m != null) {
                if (connecte || socket != null) ferme()
                maj { it.copy(phase = if (r?.actif == true) "manque" else "coupe", manque = m.orEmpty(), prochainEssaiMs = 0L) }
                attenteMs = ATTENTE_MIN_MS
                dort(500); continue
            }
            val g = generation
            val debut = horloge()
            session(g, r)
            if (arrete) break
            // Held a minute, verified: the next call comes soon; else wait longer each time.
            if (verifieTenu && horloge() - debut >= 60_000L) attenteMs = ATTENTE_MIN_MS
            if (generation != g) { attenteMs = ATTENTE_MIN_MS; continue }
            val fin = horloge() + attenteMs
            maj { it.copy(phase = "attente", prochainEssaiMs = fin) }
            while (!arrete && horloge() < fin && generation == g && runCatching { reglages().actif }.getOrDefault(false)) dort(200)
            attenteMs = (attenteMs * 2).coerceAtMost(ATTENTE_MAX_MS)
        }
        ferme()
    }

    /** One connection, until it fails, the settings change or the iGate is switched off. */
    private fun session(g: Int, r: Reglages) {
        verifieTenu = false
        val (hote, port) = IGate.serveur(r.serveur.ifBlank { IGate.SERVEUR_DEFAUT }) ?: return
        maj { it.copy(phase = "connexion", manque = "", serveur = "$hote:$port", nomServeur = "") }
        val s = try { ouvre(hote, port) } catch (e: Exception) {
            maj { it.copy(erreur = "impossible:" + e.javaClass.simpleName) }; return
        }
        socket = s
        try {
            s.soTimeout = 250
            val sortie = s.getOutputStream()
            val ligneLue = Lecteur(s.getInputStream())
            connecte = true; verifie = false
            maj { it.copy(phase = "identification", depuisMs = horloge(), erreur = "") }
            ecrit(sortie, IGate.connexion(r.indicatif, r.passcode, logiciel, version()))
            var dernierRecuMs = horloge()
            while (!arrete && generation == g && runCatching { reglages().actif }.getOrDefault(false)) {
                // What the server says: its login answer and keep-alives only; anything else thrown away.
                while (true) {
                    val l = ligneLue.ligne() ?: break
                    dernierRecuMs = horloge()
                    IGate.reponseConnexion(l)?.let { ok ->
                        verifie = ok; verifieTenu = ok
                        val nom = IGate.nomServeur(l)
                        maj { it.copy(phase = if (ok) "connecte" else "non-verifie", nomServeur = nom) }
                    }
                }
                if (ligneLue.fini) { maj { it.copy(erreur = "fermee") }; break }
                if (horloge() - dernierRecuMs > SILENCE_MS) { maj { it.copy(erreur = "muet") }; break }
                val l = file.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!verifie) continue
                ecrit(sortie, l)
                maj { it.copy(transmis = it.transmis + 1, derniers = (listOf(horloge() to l) + it.derniers).take(10)) }
            }
        } catch (e: Exception) {
            // The class only: a message could quote what was written (the login line).
            maj { it.copy(erreur = "perdue:" + e.javaClass.simpleName) }
        } finally { ferme() }
    }

    private fun ecrit(o: OutputStream, l: String) {
        o.write((l + "\r\n").toByteArray(Charsets.ISO_8859_1))
        o.flush()
    }

    /** The server's lines, read without blocking longer than the socket's timeout. */
    private class Lecteur(private val e: InputStream) {
        private val tampon = StringBuilder()
        private val prets = ArrayDeque<String>()
        var fini = false; private set
        private val buf = ByteArray(4096)

        fun ligne(): String? {
            prets.removeFirstOrNull()?.let { return it }
            if (fini) return null
            val n = try { e.read(buf) } catch (_: SocketTimeoutException) { return null }
            if (n < 0) { fini = true; return null }
            for (i in 0 until n) {
                val c = (buf[i].toInt() and 0xFF).toChar()
                if (c == '\n') { prets.addLast(tampon.toString().trimEnd('\r')); tampon.setLength(0) }
                else if (tampon.length < 2048) tampon.append(c)
            }
            return prets.removeFirstOrNull()
        }
    }

    companion object {
        const val ATTENTE_MIN_MS = 5_000L
        const val ATTENTE_MAX_MS = 300_000L
        /** APRS-IS servers speak every 20 s or so: two minutes of silence is a dead line. */
        const val SILENCE_MS = 120_000L
        const val FILE_MAX = 200
    }
}
