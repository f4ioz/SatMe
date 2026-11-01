/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.aprs.IGate
import fr.f4ioz.satcombo.aprs.Trame
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A receive-only iGate: the APRS frames SatMe hears on the air (the sound
 * card, a KISS radio listening, through a satellite or on 144.800) passed to
 * APRS-IS, by the rules of [IGate].
 *
 * **Off by default.** Its settings are the operator's: the server
 * ([IGate.SERVEUR_DEFAUT] unless changed), the iGate's callsign with its
 * SSID, the passcode typed in. The passcode is kept with the settings (a
 * secret: never exported without asking, never shown back, never in a log
 * nor in an error message) and written only on the socket, in the login line.
 *
 * **RF → IS only.** What the server sends is read and thrown away (only its
 * login answer and its keep-alives are looked at): nothing reaches a radio,
 * nothing here can make one transmit. No filter is asked of the server.
 *
 * Reconnection waits longer each time (5 s, 10 s, 20 s… 5 min at most),
 * back to 5 s once a session has held a minute. A server silent for
 * [SILENCE_MS] is left and called again.
 */
class IGatePc(
    private val stockage: Stockage,
    private val indicatifStation: () -> String = { "" },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Opens the connection (a test's fake server is local). */
    private val ouvre: (String, Int) -> Socket = { h, p -> Socket().apply { connect(InetSocketAddress(h, p), 10_000) } },
    private val version: String = "dev",
    demarre: Boolean = true,
) {
    // ------------------------------------------------------------ settings

    var actif: Boolean
        get() = stockage.getBoolean("pc_igate_actif", false)
        set(v) { stockage.edit().putBoolean("pc_igate_actif", v).apply(); change() }

    var serveur: String
        get() = stockage.getString("pc_igate_serveur", "")?.ifBlank { null } ?: IGate.SERVEUR_DEFAUT
        set(v) { stockage.edit().putString("pc_igate_serveur", v.trim()).apply(); change() }

    /** The iGate's callsign with its SSID (« F4XYZ-10 »); the station's callsign when left empty. */
    var indicatif: String
        get() = stockage.getString("pc_igate_indicatif", "")?.ifBlank { null } ?: indicatifStation().trim().uppercase()
        set(v) { stockage.edit().putString("pc_igate_indicatif", v.trim().uppercase()).apply(); change() }

    /** Write only: read back by [passcode] for the login alone. */
    fun posePasscode(p: String) { stockage.edit().putString(CLE_PASSCODE, p.trim()).apply(); change() }
    fun effacePasscode() { stockage.edit().putString(CLE_PASSCODE, "").apply(); change() }
    private val passcode: String get() = stockage.getString(CLE_PASSCODE, "") ?: ""
    val passcodePose: Boolean get() = passcode.isNotEmpty()

    /** What is missing to start, or null. */
    fun manque(): String? = when {
        !IGate.indicatifValide(indicatif) -> "indicatif de l'iGate à renseigner (F4XYZ-10)"
        !IGate.passcodeValide(passcode) -> "passcode APRS-IS à renseigner"
        IGate.serveur(serveur) == null -> "serveur illisible (hôte:port)"
        else -> null
    }

    // ------------------------------------------------------------ state

    @Volatile var etat = "coupé"; private set
    @Volatile var connecte = false; private set
    @Volatile var verifie = false; private set
    @Volatile var nomServeur = ""; private set
    @Volatile var depuisMs = 0L; private set
    @Volatile var erreur = ""; private set
    @Volatile var prochainEssaiMs = 0L; private set
    @Volatile var transmis = 0; private set
    private val refuses = java.util.concurrent.ConcurrentHashMap<IGate.Refus, Int>()
    /** The last lines gone, newest first: (ms, line). */
    @Volatile var derniers: List<Pair<Long, String>> = emptyList(); private set
    /** The last refusals, newest first: (ms, frame, reason). */
    @Volatile var derniersRefus: List<Triple<Long, String, IGate.Refus>> = emptyList(); private set

    private val filtre = IGate.Filtre()
    private val file = LinkedBlockingQueue<String>(FILE_MAX)
    @Volatile private var generation = 0

    private fun change() { generation++; file.clear() }

    private fun refuse(t: Trame, r: IGate.Refus) {
        refuses.merge(r, 1, Int::plus)
        derniersRefus = (listOf(Triple(horloge(), t.tnc2().take(200), r)) + derniersRefus).take(10)
    }

    /**
     * A frame heard on the air, at [quand]. Ignored when the iGate is off;
     * otherwise gated or refused, and counted.
     */
    fun recoit(t: Trame, quand: Long = horloge()) {
        if (!actif || ModeDemo.actif) return
        val (ligne, r) = filtre.passe(t, quand, indicatif)
        if (r != null || ligne == null) { refuse(t, r ?: IGate.Refus.TIERS); return }
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
            val m = if (actif) manque() else null
            if (!actif || ModeDemo.actif || m != null) {
                if (connecte || socket != null) ferme()
                etat = when { !actif -> "coupé"; ModeDemo.actif -> "désactivé dans la démonstration"; else -> m!! }
                attenteMs = ATTENTE_MIN_MS; prochainEssaiMs = 0L
                dort(500); continue
            }
            val g = generation
            val debut = horloge()
            session(g)
            if (arrete) break
            // Held a minute, verified: the next call comes soon; else wait longer each time.
            attenteMs = if (verifieTenu && horloge() - debut >= 60_000L) ATTENTE_MIN_MS else attenteMs
            if (generation != g) { attenteMs = ATTENTE_MIN_MS; continue }
            prochainEssaiMs = horloge() + attenteMs
            etat = "nouvel essai dans ${attenteMs / 1000} s" + (erreur.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            val fin = prochainEssaiMs
            while (!arrete && horloge() < fin && generation == g && actif) dort(200)
            attenteMs = (attenteMs * 2).coerceAtMost(ATTENTE_MAX_MS)
        }
        ferme()
    }

    /** One connection, until it fails, the settings change or the iGate is switched off. */
    private fun session(g: Int) {
        verifieTenu = false
        val (hote, port) = IGate.serveur(serveur) ?: return
        etat = "connexion à $hote:$port…"
        val s = try { ouvre(hote, port) } catch (e: Exception) {
            erreur = "connexion impossible : " + (e.javaClass.simpleName); return
        }
        socket = s
        try {
            s.soTimeout = 250
            val sortie = s.getOutputStream()
            val entree = s.getInputStream()
            val ligneLue = Lecteur(entree)
            connecte = true; verifie = false; nomServeur = ""; depuisMs = horloge(); erreur = ""
            etat = "connecté, identification…"
            ecrit(sortie, IGate.connexion(indicatif, passcode, "SatMe-PC", version))
            var dernierRecuMs = horloge()
            while (!arrete && generation == g && actif) {
                // What the server says: its login answer and keep-alives only; packets thrown away.
                while (true) {
                    val l = ligneLue.ligne() ?: break
                    dernierRecuMs = horloge()
                    IGate.reponseConnexion(l)?.let { ok ->
                        verifie = ok; verifieTenu = ok
                        nomServeur = IGate.nomServeur(l)
                        etat = if (ok) "connecté" else "connecté, non vérifié : passcode refusé, rien n'est transmis"
                    }
                }
                if (ligneLue.fini) { erreur = "le serveur a fermé la connexion"; break }
                if (horloge() - dernierRecuMs > SILENCE_MS) { erreur = "serveur muet depuis ${SILENCE_MS / 1000} s"; break }
                val l = file.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!verifie) continue
                ecrit(sortie, l)
                transmis++
                derniers = (listOf(horloge() to l) + derniers).take(10)
            }
        } catch (e: Exception) {
            // The class only: a message could quote what was written.
            erreur = "connexion perdue (" + e.javaClass.simpleName + ")"
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

    // ------------------------------------------------------------ for the page

    fun json(): JSONObject = JSONObject()
        .put("present", true)
        .put("actif", actif)
        .put("serveur", serveur)
        .put("serveurDefaut", IGate.SERVEUR_DEFAUT)
        .put("indicatif", indicatif)
        .put("indicatifPose", (stockage.getString("pc_igate_indicatif", "") ?: "").isNotBlank())
        // Whether one is set, never what it is.
        .put("passcode", passcodePose)
        .put("manque", if (actif) manque() ?: "" else "")
        .put("etat", etat)
        .put("connecte", connecte)
        .put("verifie", verifie)
        .put("nomServeur", nomServeur)
        .put("depuis", if (connecte) depuisMs else 0L)
        .put("erreur", erreur)
        .put("prochainEssai", prochainEssaiMs)
        .put("transmis", transmis)
        .put("refuses", JSONObject().also { o -> IGate.Refus.values().forEach { r -> refuses[r]?.let { o.put(r.code, it) } } })
        .put("raisons", JSONObject().also { o -> IGate.Refus.values().forEach { o.put(it.code, it.texte) } })
        .put("derniers", JSONArray(derniers.map { (t, l) -> JSONObject().put("t", t).put("ligne", l) }))
        .put("derniersRefus", JSONArray(derniersRefus.map { (t, l, r) -> JSONObject().put("t", t).put("trame", l).put("raison", r.code) }))
        .put("demo", ModeDemo.actif)

    /** The page's settings: actif, serveur, indicatif, passcode (empty: kept), passcodeEfface. */
    fun action(q: Map<String, String>): JSONObject {
        q["serveur"]?.let { v ->
            if (v.isNotBlank() && IGate.serveur(v) == null) return JSONObject().put("ok", false).put("erreur", "serveur illisible : hôte:port")
            serveur = v
        }
        q["indicatif"]?.let { v ->
            if (v.isNotBlank() && !IGate.indicatifValide(v)) return JSONObject().put("ok", false).put("erreur", "indicatif de l'iGate illisible (F4XYZ-10)")
            indicatif = v
        }
        q["passcode"]?.takeIf { it.isNotBlank() }?.let { v ->
            if (!IGate.passcodeValide(v)) return JSONObject().put("ok", false).put("erreur", "le passcode est un nombre de 1 à 5 chiffres")
            posePasscode(v)
        }
        if (q["passcodeEfface"] == "1") effacePasscode()
        q["actif"]?.let { v ->
            if (v == "1") ModeDemo.refus("l'iGate")?.let { return JSONObject().put("ok", false).put("erreur", it) }
            actif = v == "1"
        }
        return json().put("ok", true)
    }

    companion object {
        const val CLE_PASSCODE = "pc_igate_passcode"
        const val ATTENTE_MIN_MS = 5_000L
        const val ATTENTE_MAX_MS = 300_000L
        /** APRS-IS servers speak every 20 s or so: two minutes of silence is a dead line. */
        const val SILENCE_MS = 120_000L
        const val FILE_MAX = 200
    }
}
