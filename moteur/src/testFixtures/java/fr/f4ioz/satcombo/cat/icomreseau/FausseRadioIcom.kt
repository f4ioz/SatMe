/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A pretend Icom LAN server on localhost, built from the same public
 * descriptions as the client: three UDP ports, the login, the capabilities,
 * the streams. It keeps what it was sent, so a test can check that no
 * transmit command ever arrived and that the client logged out.
 */
class FausseRadioIcom(
    /** The credentials it accepts, as coded bytes. */
    val utilisateur: ByteArray,
    val motDePasse: ByteArray,
    /** Another session holds it already. */
    val occupe: Boolean = false,
    /** The audio packet (by count) it leaves out once, to be asked again. */
    val perdAudio: Int = -1,
) {
    private val ici = InetAddress.getLoopbackAddress()
    private val sControle = DatagramSocket(0, ici).apply { soTimeout = 50 }
    private val sCiv = DatagramSocket(0, ici).apply { soTimeout = 50 }
    private val sAudio = DatagramSocket(0, ici).apply { soTimeout = 50 }
    val portControle get() = sControle.localPort
    val portCiv get() = sCiv.localPort
    val portAudio get() = sAudio.localPort

    @Volatile var enMarche = true
    val jeton = 0x025ECDC9
    val adresseCiv = 0xA2

    // What it saw.
    val civRecues = CopyOnWriteArrayList<ByteArray>()
    @Volatile var connexions = 0
    @Volatile var connecte = false
    @Volatile var jetonRendu = false
    val deconnexions = CopyOnWriteArrayList<String>()
    @Volatile var civOuvert = false
    @Volatile var civFerme = false
    @Volatile var txActifDemande: Boolean? = null
    @Volatile var codecDemande = -1
    @Volatile var tauxDemande = -1
    val reprisesAudio = CopyOnWriteArrayList<Int>()
    @Volatile var renouvellements = 0
    @Volatile var frequenceHz = 145_800_000L
    /** The sound sent (sample number, rate → sample); null: a 1 kHz tone. Its IF, set to IF. */
    @Volatile var son: ((Long, Int) -> Short)? = null
    private var echantillon = 0L

    private inner class Port(val nom: String, val s: DatagramSocket) {
        val moi = 0x4B8B0000 or nom.hashCode().and(0xFFFF)
        @Volatile var lui = 0
        @Volatile var client: SocketAddress? = null
        var seq = 0
        var seqPing = 0
        val envoyes = HashMap<Int, ByteArray>()

        @Synchronized fun envoie(b: ByteArray) { client?.let { s.send(DatagramPacket(b, b.size, it)) } }
        @Synchronized fun suivi(f: (Int) -> ByteArray) { val q = seq; seq = (seq + 1) and 0xFFFF; val b = f(q); envoyes[q] = b; envoie(b) }
    }

    private val ctl = Port("contrôle", sControle)
    private val civ = Port("civ", sCiv)
    private val aud = Port("audio", sAudio)
    @Volatile private var audioEnCours = false

    init {
        for (p in listOf(ctl, civ, aud)) thread(isDaemon = true, name = "fausse-radio-" + p.nom) { boucle(p) }
        thread(isDaemon = true, name = "fausse-radio-tic") { tic() }
    }

    private fun boucle(p: Port) {
        val buf = ByteArray(2048)
        while (enMarche) {
            val d = DatagramPacket(buf, buf.size)
            try { p.s.receive(d) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { return }
            val b = d.data.copyOfRange(0, d.length)
            p.client = d.socketAddress
            runCatching { traite(p, b) }
        }
    }

    private fun traite(p: Port, b: ByteArray) {
        val e = Paquets.entete(b) ?: return
        when (e.type) {
            Paquets.T_ES_TU_LA -> {
                p.lui = e.emetteur
                p.envoie(Paquets.controle(Paquets.T_JE_SUIS_LA, 0, p.moi, p.lui))
                if (p === aud) audioEnCours = true
            }
            Paquets.T_PRET -> p.envoie(Paquets.controle(Paquets.T_PRET, 1, p.moi, p.lui))
            Paquets.T_DECONNEXION -> {
                deconnexions += p.nom
                if (p === aud) audioEnCours = false
                if (p === ctl) connecte = false
            }
            Paquets.T_PING -> if (!Paquets.estReponsePing(b)) p.envoie(Paquets.ping(e.seq, p.moi, p.lui, true, Paquets.valeurPing(b)))
            Paquets.T_REPRISE -> for (s in Paquets.reprises(b)) {
                if (p === aud) reprisesAudio += s
                p.envoyes[s]?.let { p.envoie(it) }
            }
            Paquets.T_DONNEES -> when (p) {
                ctl -> controle(b)
                civ -> civ(b)
                else -> {}
            }
        }
    }

    private fun controle(b: ByteArray) {
        val j = Paquets.jeton(b) ?: return
        when {
            b.size == Paquets.CONNEXION_LONGUEUR && j.genre == Paquets.G_CONNEXION -> {
                connexions++
                val bon = b.copyOfRange(Paquets.C_UTILISATEUR, Paquets.C_UTILISATEUR + 16).contentEquals(utilisateur) &&
                    b.copyOfRange(Paquets.C_MOT_DE_PASSE, Paquets.C_MOT_DE_PASSE + 16).contentEquals(motDePasse)
                ctl.suivi { Paquets.nouveauJeton(Paquets.REPONSE_CONNEXION_LONGUEUR, it, ctl.moi, ctl.lui,
                    Paquets.Jeton(Paquets.SENS_REPONSE, Paquets.G_CONNEXION, j.seqInterne, j.demande, if (bon) jeton else 0,
                        erreur = if (bon) 0 else 0xFEFFFFFF.toInt())).also { r -> Paquets.posTexte(r, Paquets.RC_RESEAU, 16, "FTTH") } }
                connecte = bon
            }
            j.genre == Paquets.G_CREATION && j.sens == Paquets.SENS_DEMANDE -> {
                ctl.suivi { capacites(it) }
                ctl.suivi { info(it, occupe) }
            }
            j.genre == Paquets.G_INFO && j.sens == Paquets.SENS_DEMANDE -> {
                txActifDemande = b[Paquets.I_TX_ACTIF].toInt() != 0
                codecDemande = b[Paquets.I_RX_CODEC].toInt() and 0xFF
                tauxDemande = Paquets.u32be(b, Paquets.I_RX_TAUX)
                ctl.suivi { q ->
                    Paquets.nouveauJeton(Paquets.STATUT_LONGUEUR, q, ctl.moi, ctl.lui,
                        Paquets.Jeton(Paquets.SENS_REPONSE, Paquets.G_INFO, j.seqInterne, j.demande, jeton)).also {
                        val libre = !occupe
                        Paquets.p32be(it, Paquets.S_PORT_CIV, if (libre) portCiv else 0)
                        Paquets.p32be(it, Paquets.S_PORT_AUDIO, if (libre) portAudio else 0)
                    }
                }
            }
            j.genre == Paquets.G_RENOUVELLEMENT -> { renouvellements++; repondJeton(j) }
            j.genre == Paquets.G_RETRAIT -> { jetonRendu = true; connecte = false; repondJeton(j) }
        }
    }

    private fun repondJeton(j: Paquets.Jeton) = ctl.suivi {
        Paquets.nouveauJeton(Paquets.JETON_LONGUEUR, it, ctl.moi, ctl.lui, j.copy(sens = Paquets.SENS_REPONSE))
    }

    private fun capacites(seq: Int): ByteArray {
        val b = Paquets.nouveauJeton(Paquets.K_PREMIERE + Paquets.CAPACITES_ENTREE, seq, ctl.moi, ctl.lui,
            Paquets.Jeton(Paquets.SENS_REPONSE, Paquets.G_CREATION, 1, 0, jeton))
        Paquets.p16be(b, Paquets.K_NOMBRE, 1)
        val o = Paquets.K_PREMIERE
        Paquets.p16le(b, o + 7, 0x8010)
        byteArrayOf(0x00, 0x90.toByte(), 0xC7.toByte(), 0x01, 0x02, 0x03).copyInto(b, o + 0x0A)
        Paquets.posTexte(b, o + Paquets.KE_NOM, 32, "IC-9700")
        Paquets.posTexte(b, o + Paquets.KE_AUDIO, 32, "ICOM_VAUDIO")
        Paquets.p16be(b, o + Paquets.KE_TYPE, 0x073F)
        b[o + Paquets.KE_CIV] = adresseCiv.toByte()
        Paquets.p16le(b, o + Paquets.KE_RX, 0x8B01)
        Paquets.p16le(b, o + Paquets.KE_TX, 0x8B01)
        Paquets.p32be(b, o + Paquets.KE_VITESSE, 115_200)
        return b
    }

    private fun info(seq: Int, occupe: Boolean): ByteArray {
        val b = Paquets.nouveauJeton(Paquets.INFO_LONGUEUR, seq, ctl.moi, ctl.lui,
            Paquets.Jeton(Paquets.SENS_AVIS, Paquets.G_CONNEXION, 0, 0, jeton))
        Paquets.posTexte(b, Paquets.I_NOM, 32, "IC-9700")
        b[Paquets.I_OCCUPE] = if (occupe) 1 else 0
        return b
    }

    private var seqCivRadio = 0
    /** Its scope sent on CI-V (27 11 01), one sweep every 200 ms. */
    @Volatile var spectre = false

    private fun balayage(n: Int): ByteArray {
        val tete = byteArrayOf(0x27, 0x00, 0x00, 0x01, 0x01, 0x00) + fr.f4ioz.satcombo.cat.CatDecode.freqToBcdLe(frequenceHz) +
            byteArrayOf(0x00, 0x00, 0x05, 0x00, 0x00, 0x00)
        val pts = ByteArray(475) { i ->
            val pic = Math.exp(-Math.pow((i - 237 - 60 * Math.sin(n / 20.0)) / 6.0, 2.0)) * 110
            (25 + (i * 7 + n * 13) % 17 + pic).toInt().coerceIn(0, 160).toByte()
        }
        return byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0xE0.toByte(), adresseCiv.toByte()) + tete + pts + byteArrayOf(0xFD.toByte())
    }

    private fun civ(b: ByteArray) {
        if (Paquets.estOuvreFerme(b)) { if (b[0x15].toInt() == Paquets.OUVRE) civOuvert = true else civFerme = true; return }
        val t = Paquets.trameCiv(b) ?: return
        civRecues += t
        // As the radio does: the frame echoed, then its answer.
        repondCiv(t)
        if (t.size < 6) return
        val de = t[3].toInt() and 0xFF
        fun reponse(vararg d: Byte) = byteArrayOf(0xFE.toByte(), 0xFE.toByte(), de.toByte(), adresseCiv.toByte()) + d + byteArrayOf(0xFD.toByte())
        val ok = reponse(0xFB.toByte())
        when (t[4].toInt() and 0xFF) {
            0x03 -> repondCiv(reponse(0x03, *fr.f4ioz.satcombo.cat.CatDecode.freqToBcdLe(frequenceHz)))
            0x05 -> { fr.f4ioz.satcombo.cat.CatDecode.bcdLeToFreq(t, 5)?.let { frequenceHz = it }; repondCiv(ok) }
            0x27 -> { if (t.size >= 8 && (t[5].toInt() and 0xFF) == 0x11) spectre = t[6].toInt() == 1; repondCiv(ok) }
            else -> repondCiv(ok)
        }
    }

    private fun repondCiv(t: ByteArray) {
        val s = seqCivRadio; seqCivRadio = (seqCivRadio + 1) and 0xFFFF
        civ.suivi { q -> Paquets.civ(q, civ.moi, civ.lui, 0, t).also { Paquets.p16be(it, 0x13, s) } }
    }

    /** Idles on each port, pings, and 20 ms of audio (a 1 kHz tone) per packet. */
    private fun tic() {
        var n = 0
        var seqAudio = 0
        var phase = 0.0
        var perdu = false
        while (enMarche) {
            Thread.sleep(20)
            n++
            if (n % 15 == 0) for (p in listOf(ctl, civ, aud)) if (p.lui != 0) {
                if (p !== aud) p.suivi { Paquets.controle(Paquets.T_DONNEES, it, p.moi, p.lui) }
                p.envoie(Paquets.ping(p.seqPing++, p.moi, p.lui, false, n))
            }
            if (spectre && n % 10 == 0 && civ.lui != 0) repondCiv(balayage(n))
            if (audioEnCours && aud.lui != 0) {
                val taux = if (tauxDemande > 0) tauxDemande else 16_000
                val k = taux / 50
                val d = ByteArray(2 * k)
                for (i in 0 until k) {
                    val v = son?.invoke(echantillon++, taux)?.toInt() ?: (8000 * Math.sin(phase)).toInt(); phase += 2 * Math.PI * 1000 / taux
                    d[2 * i] = v.toByte(); d[2 * i + 1] = (v shr 8).toByte()
                }
                val sa = seqAudio++
                synchronized(aud) {
                    val q = aud.seq; aud.seq = (aud.seq + 1) and 0xFFFF
                    val b = Paquets.nouvelAudio(q, aud.moi, aud.lui, sa, d)
                    aud.envoyes[q] = b
                    if (q == perdAudio && !perdu) perdu = true else aud.envoie(b)
                }
            }
        }
    }

    /** Says goodbye on its own (another session took the radio). */
    fun chasse() { ctl.envoie(Paquets.controle(Paquets.T_DECONNEXION, 0, ctl.moi, ctl.lui)) }

    fun arrete() {
        enMarche = false
        runCatching { sControle.close() }; runCatching { sCiv.close() }; runCatching { sAudio.close() }
    }
}
