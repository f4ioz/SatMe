/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat

import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.portsDe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The TS-2000's serial line, shared by both sides (one cable, one rig).
 * Finds the port that answers `ID019;` and the speed it answers at.
 */
class Ts2000Lien(
    private val ports: PortsSerie? = null,
    /** What the search tried and got (on the phone: `adb logcat -s SatMeTs2000`). */
    private val trace: (String) -> Unit = {}
) {
    @Volatile private var link: SerialLink? = null
    val isOpen: Boolean get() = link != null
    private val fil = Mutex()
    var pacingMs: Long = 20L
    /** The speed found, for the status line. */
    var vitesse: Int = 0
        private set
    /** The adapter it answered on (its key). */
    var cle: String? = null
        private set

    fun attach(l: SerialLink) { link = l; ouvertMs = System.currentTimeMillis(); derniereReponseMs = ouvertMs }

    /** When the rig last answered anything (a refusal too), ms; set to the opening at first. */
    @Volatile var derniereReponseMs: Long = 0L
        private set
    @Volatile var ouvertMs: Long = 0L
        private set
    /** The last command refused (`?;`, `E;`, `O;`) and when (ms, 0 never). */
    @Volatile var dernierRefus: String = ""
        private set
    @Volatile var dernierRefusMs: Long = 0L
        private set

    /**
     * Opens the adapter with key [cle]: each of its ports (the RigExpert Tiny
     * has two, CAT on the first) at each speed, [baud] first, until the rig
     * says `ID019;`. Port by port, so the one carrying PTT is never reached
     * once the CAT port has answered.
     */
    suspend fun open(cle: String?, baud: Int): Boolean = ouverture.withLock { ouvre(cle, baud) }

    /** One search at a time: both sides share this line. */
    private val ouverture = Mutex()

    private suspend fun ouvre(cle: String?, baud: Int): Boolean = withContext(Dispatchers.IO) {
        if (link != null) return@withContext true
        val pp = ports ?: return@withContext false
        val tous = pp.portsDe(cle)
        val premier = tous.firstOrNull() ?: return@withContext false
        val vitesses = (listOf(baud) + Ts2000.VITESSES).filter { it > 0 }.distinct()
        trace("adaptateur ${premier.produit}, ${tous.size} port(s)")
        // A RigExpert carries CAT on its first port only; the second is PTT/CW: never written to.
        val candidats = if (premier.produit?.contains("RigExpert", ignoreCase = true) == true) tous.take(1) else tous
        for (p in candidats) {
            for (v in vitesses) {
                when (essaie(pp, p, v)) {
                    Essai.TROUVE -> return@withContext true
                    Essai.ECHO -> break
                    Essai.RIEN -> Unit
                }
            }
        }
        false
    }

    private enum class Essai { TROUVE, ECHO, RIEN }

    /**
     * One port at one speed, RTS and DTR low before anything else (on a
     * RigExpert they may be PTT); 2 stop bits at 4800 baud or less, as Kenwood
     * wants. A port that sends our own words back is not a rig.
     */
    private suspend fun essaie(pp: PortsSerie, p: PortSerie, v: Int): Essai {
        val o = pp.ouvre(p, v, Lignes.BASSES, deuxArrets = v <= 4_800)
        val l = o.lien ?: run { trace("port ${p.port} à $v bauds : ouverture impossible (${o.erreur})"); return Essai.RIEN }
        link = l
        val r = identifie()
        trace("port ${p.port} à $v bauds : ID; → ${r ?: "rien"}")
        if (r == Ts2000.ID) { vitesse = v; cle = p.cle; ouvertMs = System.currentTimeMillis(); derniereReponseMs = ouvertMs; return Essai.TROUVE }
        link = null
        runCatching { l.close() }
        return if (r == "ID;") Essai.ECHO else Essai.RIEN
    }

    /**
     * Opens exactly [p] at [baud], nothing else tried: the PC, where the
     * operator picked the port. False when no TS-2000 answers there.
     */
    suspend fun ouvrePort(p: PortSerie, baud: Int): Boolean = ouverture.withLock {
        close()
        val pp = ports ?: return@withLock false
        essaie(pp, p, baud) == Essai.TROUVE
    }

    fun close() {
        runCatching { link?.close() }
        link = null
    }

    /**
     * `ID;` asked so the rig hears it whole: first a lone ';' ends whatever
     * the opening of the port left in its buffer (it answers `?;` to that,
     * thrown away), then the question — asked twice if it still says `?;`.
     */
    private suspend fun identifie(): String? {
        commande(";", 300)
        val r = commande("ID;", 700)
        return if (r == "?;") commande("ID;", 700) else r
    }

    /**
     * Sends [c] (ending in ';'). A question gets its answer (up to ';'), null
     * when nothing came; a setting gets "" (the rig answers nothing), or "?;"
     * when it refuses one at once. Never a command that keys the transmitter.
     */
    suspend fun commande(c: String, delaiMs: Int = 500): String? = withContext(Dispatchers.IO) {
        require(!Ts2000.emet(c)) { "no transmit command" }
        fil.withLock {
            val l = link ?: return@withLock null
            // At least one USB packet: an FTDI FT2232H (the RigExpert Tiny) sends 512 bytes at a
            // time, and a smaller buffer makes every read fail without a word.
            val tampon = ByteArray(4096)
            while (l.read(tampon, 5) > 0) { /* leftovers belong to an older question */ }
            if (!l.write(c.toByteArray(Charsets.US_ASCII), 500)) return@withLock null
            CatJournal.log(true, c.toByteArray(Charsets.US_ASCII), c)
            // A question is two letters (plus an optional digit for SM0;) then ';'.
            val question = Regex("^[A-Z]{2}\\d?;$").matches(c)
            val recu = StringBuilder()
            val fin = System.currentTimeMillis() + if (question) delaiMs else 40
            while (System.currentTimeMillis() < fin) {
                val n = l.read(tampon, if (question) 50 else 20)
                for (i in 0 until n) recu.append((tampon[i].toInt() and 0xFF).toChar())
                val k = recu.indexOf(";")
                if (k >= 0) {
                    val r = recu.substring(0, k + 1).trim()
                    CatJournal.log(false, r.toByteArray(Charsets.US_ASCII), r)
                    derniereReponseMs = System.currentTimeMillis()
                    if (Ts2000.refuse(r) && c != ";") { dernierRefus = c; dernierRefusMs = derniereReponseMs }
                    if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
                    return@withLock r
                }
            }
            if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
            if (question) null else ""
        }
    }

    suspend fun estUnTs2000(): Boolean = commande("ID;") == Ts2000.ID

    /** One toggle at a time: the side under control must never be left wrong. */
    private val bascule = Mutex()

    /** Puts control on the uplink ([montee]) or the downlink, by `DC01;` (a toggle), checked by `SA`. */
    private suspend fun controle(montee: Boolean): Boolean {
        repeat(2) {
            val ici = Ts2000.controleMontee(commande("SA;")) ?: return false
            if (ici == montee) return true
            commande("DC01;")
        }
        return Ts2000.controleMontee(commande("SA;")) == montee
    }

    /**
     * Runs [bloc] with control on the uplink, then gives control back to the
     * downlink whatever happened — the operator's knob and `MD` belong there.
     */
    suspend fun surLaMontee(bloc: suspend () -> Boolean): Boolean = bascule.withLock {
        try {
            controle(montee = true) && bloc()
        } finally {
            controle(montee = false)
        }
    }
    suspend fun satellite(): String? = commande("SA;")
    /** The S-meter on Icom's scale (see [Ts2000.versIcom]), null when not read. */
    suspend fun smetre(): Int? = Ts2000.smetre(commande("SM0;"))?.let { Ts2000.versIcom(it) }
    suspend fun emission(): Boolean? = Ts2000.emission(commande("IF;"))
}

/**
 * One side of the TS-2000 in SAT mode: the downlink on VFO A, the uplink on
 * VFO B, the rig in full duplex. Each write is read back.
 */
class Ts2000Cote(val lien: Ts2000Lien, val descente: Boolean) : PosteSimple {
    private val vfo = if (descente) "A" else "B"
    override val isOpen: Boolean get() = lien.isOpen
    override var pacingMs: Long
        get() = lien.pacingMs
        set(v) { lien.pacingMs = v }
    override fun attach(l: SerialLink) = lien.attach(l)
    override suspend fun open(cle: String?, baud: Int): Boolean = lien.open(cle, baud)
    override fun close() = lien.close()

    override suspend fun setFrequency(hz: Long): Boolean {
        if (hz <= 0) return false
        if (Ts2000.refuse(lien.commande(Ts2000.ecrit(vfo, hz)))) return false
        return Ts2000.frequence(lien.commande("F$vfo;"), vfo) == hz
    }

    override suspend fun readFrequency(): Long? = Ts2000.frequence(lien.commande("F$vfo;"), vfo)

    /** This side's mode: the downlink directly, the uplink with control toggled onto it. */
    override suspend fun setMode(mode: String): Boolean {
        val n = Ts2000.codeMode(mode) ?: return false
        suspend fun regle(): Boolean {
            if (lien.commande("MD;") == "MD$n;") return true
            lien.commande("MD$n;")
            return lien.commande("MD;") == "MD$n;"
        }
        return if (descente) regle() else lien.surLaMontee { regle() }
    }

    /** The uplink's tone (TO / TN), with control on the uplink. *The tone itself not yet heard on the air.* */
    override suspend fun setCtcss(tenthHz: Int): Boolean {
        if (descente) return true
        return lien.surLaMontee {
            if (tenthHz <= 0) !Ts2000.refuse(lien.commande("TO0;"))
            else !Ts2000.refuse(lien.commande("TN%02d;".format(Ts2000.numeroTon(tenthHz)))) && !Ts2000.refuse(lien.commande("TO1;"))
        }
    }

    override suspend fun isTransmitting(): Boolean? = lien.emission()
}
