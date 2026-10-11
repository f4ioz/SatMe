/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.DispositionSat
import fr.f4ioz.satcombo.cat.ModePoste
import fr.f4ioz.satcombo.cat.PosteCat
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.Ts2000
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.PortSerie
import fr.f4ioz.satcombo.plateforme.PortsSerie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The TS-2000 on a PC's serial port, in SAT mode: the downlink on VFO A,
 * the uplink on VFO B (see [Ts2000] for what the rig answers).
 *
 * The phone's link (`Ts2000Lien`) searches its USB adapters at several
 * speeds; here **only the port and the speed chosen** are opened, once, RTS
 * and DTR low: on a RigExpert the second port is PTT, and every opening on
 * Linux raises the lines for an instant. A port that sends our own words
 * back (that second port) is closed at once.
 *
 * **Never transmits**: no `TX`, no `KY` ([Ts2000.emet] refuses them).
 */
class LienTs2000Pc(private val ports: PortsSerie?) {
    @Volatile private var link: SerialLink? = null
    val isOpen: Boolean get() = link != null
    private val fil = Mutex()
    var pacingMs: Long = 20L
    var lastError: String = ""

    fun attach(l: SerialLink) { link = l }

    /** Opens [p] at [baud] and checks a TS-2000 answers there. */
    suspend fun ouvre(p: PortSerie, baud: Int): Boolean {
        close()
        val pp = ports ?: return false
        val o = pp.ouvre(p, baud, Lignes.BASSES)
        val l = o.lien ?: run { lastError = o.erreur; return false }
        link = l
        val id = identifie()
        if (id == Ts2000.ID) { lastError = ""; return true }
        close()
        lastError = when (id) {
            null -> "pas de réponse à ID; (vitesse ${baud} ? menu 56 du poste)"
            "ID;" -> "ce port renvoie nos mots : ce n'est pas le port CAT (port B d'un RigExpert ?)"
            else -> "réponse inattendue : $id"
        }
        return false
    }

    fun close() {
        runCatching { link?.close() }
        link = null
    }

    /** As the phone: a lone ';' ends what the opening left, then the question (twice if `?;`). */
    private suspend fun identifie(): String? {
        commande(";", 300)
        val r = commande("ID;", 700)
        return if (r == "?;") commande("ID;", 700) else r
    }

    /**
     * Sends [c]. A question gets its answer (up to ';'), null when nothing
     * came; a setting gets "" or "?;". Never a command that keys the
     * transmitter. (The phone's `Ts2000Lien.commande`, on a PC's port.)
     */
    suspend fun commande(c: String, delaiMs: Int = 500): String? = withContext(Dispatchers.IO) {
        require(!Ts2000.emet(c)) { "no transmit command" }
        fil.withLock {
            val l = link ?: return@withLock null
            val tampon = ByteArray(4096)
            while (l.read(tampon, 5) > 0) { /* leftovers belong to an older question */ }
            if (!l.write(c.toByteArray(Charsets.US_ASCII), 500)) return@withLock null
            val question = Regex("^[A-Z]{2}\\d?;$").matches(c)
            val recu = StringBuilder()
            val fin = System.currentTimeMillis() + if (question) delaiMs else 40
            while (System.currentTimeMillis() < fin) {
                val n = l.read(tampon, if (question) 50 else 20)
                for (i in 0 until n) recu.append((tampon[i].toInt() and 0xFF).toChar())
                val k = recu.indexOf(";")
                if (k >= 0) {
                    if (pacingMs > 0) delay(pacingMs)
                    return@withLock recu.substring(0, k + 1).trim()
                }
            }
            if (pacingMs > 0) delay(pacingMs)
            if (question) null else ""
        }
    }

    private val bascule = Mutex()

    private suspend fun controle(montee: Boolean): Boolean {
        repeat(2) {
            val ici = Ts2000.controleMontee(commande("SA;")) ?: return false
            if (ici == montee) return true
            commande("DC01;")
        }
        return Ts2000.controleMontee(commande("SA;")) == montee
    }

    /** [bloc] with control on the uplink, then control back on the downlink whatever happened. */
    suspend fun surLaMontee(bloc: suspend () -> Boolean): Boolean = bascule.withLock {
        try { controle(montee = true) && bloc() } finally { controle(montee = false) }
    }

    suspend fun emission(): Boolean? = Ts2000.emission(commande("IF;"))
}

/** The TS-2000 as the Doppler loop sees it: one rig, each leg its own VFO (a pair, nothing to juggle). */
class PosteTs2000Pc(private val lien: LienTs2000Pc) : PosteCat {
    override val paire = true

    private suspend fun regleMode(n: Int): Boolean {
        if (lien.commande("MD;") == "MD$n;") return true
        lien.commande("MD$n;")
        return lien.commande("MD;") == "MD$n;"
    }

    override suspend fun arme(disposition: DispositionSat, descente: ModePoste, montee: ModePoste, ctcssDixiemes: Int) {
        Ts2000.codeMode(descente.nom)?.let { regleMode(it) }
        if (disposition == DispositionSat.BEACON) return
        Ts2000.codeMode(montee.nom)?.let { n -> lien.surLaMontee { regleMode(n) } }
        lien.surLaMontee {
            if (ctcssDixiemes <= 0) lien.commande("TO0;") != "?;"
            else lien.commande("TN%02d;".format(Ts2000.numeroTon(ctcssDixiemes))) != "?;" && lien.commande("TO1;") != "?;"
        }
    }

    override suspend fun lisDescente(memeBande: Boolean): Long? = Ts2000.frequence(lien.commande("FA;"), "A")

    override suspend fun lisMontee(): Long? =
        if (lien.emission() == true) null else Ts2000.frequence(lien.commande("FB;"), "B")

    private suspend fun ecris(vfo: String, hz: Long) {
        if (hz <= 0) return
        if (lien.commande(Ts2000.ecrit(vfo, hz)) == "?;") return
        lien.commande("F$vfo;")
    }

    override suspend fun ecrisDescente(hz: Long) = ecris("A", hz)

    override suspend fun ecrisCouple(descente: Long, montee: Long, memeBande: Boolean) {
        ecris("A", descente)
        // Not while transmitting: nothing to correct while talking, and the rig answers badly.
        if (lien.emission() != true) ecris("B", montee)
    }

    override suspend fun ecrisMontee(montee: Long, memeBande: Boolean) {
        if (lien.emission() != true) ecris("B", montee)
    }
}
