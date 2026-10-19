/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One UDP conversation with one of the radio's ports. */
interface PriseUdp {
    fun envoie(b: ByteArray)
    /** The next datagram, or null after [tempsMs] of silence. */
    fun recois(tempsMs: Int): ByteArray?
    fun ferme()
}

class PriseDatagramme(hote: String, port: Int) : PriseUdp {
    private val s = DatagramSocket().apply { connect(InetSocketAddress(hote, port)) }
    private val tampon = ByteArray(2048)
    override fun envoie(b: ByteArray) { s.send(DatagramPacket(b, b.size)) }
    override fun recois(tempsMs: Int): ByteArray? {
        s.soTimeout = maxOf(1, tempsMs)
        val p = DatagramPacket(tampon, tampon.size)
        return try { s.receive(p); p.data.copyOfRange(p.offset, p.offset + p.length) }
        catch (_: SocketTimeoutException) { null }
        catch (_: java.net.PortUnreachableException) { null }
    }
    override fun ferme() { runCatching { s.close() } }
}

/** What the link needs to log in and what it asks for. */
data class ConfigReseau(
    val hote: String,
    val portControle: Int = 50001,
    val portCiv: Int = 50002,
    val portAudio: Int = 50003,
    /** The user and password as the login carries them (see [CodageIdentifiants]). */
    val utilisateurCode: ByteArray,
    val motDePasseCode: ByteArray,
    val nomPc: String = "SatMe",
    val codec: CodecReseau = CodecReseau.DEFAUT,
    val latenceMs: Int = 80,
    val audio: Boolean = true,
)

/**
 * One session with an Icom's LAN server: the control port's login and
 * keepalive, the CI-V stream, the receive audio stream.
 *
 * Receive only: the connection request leaves the TX audio path off, no
 * audio is ever sent, and every CI-V frame passes [GardeEmission] first.
 *
 * The radio holds one remote session: [ferme] always hands the token back
 * and says goodbye on each port, also after a login that failed part-way,
 * or the radio would keep the slot and refuse the next attempt for a while.
 */
class SessionIcom(
    val config: ConfigReseau,
    private val fabrique: (String, Int) -> PriseUdp = { h, p -> PriseDatagramme(h, p) },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The packet log (headers only, no credential): null when off. */
    @Volatile var journal: ((String) -> Unit)? = null,
) {
    enum class Etat { ARRET, RECHERCHE, CONNEXION, CONNECTE, REFUSE, PERDU }

    data class Statut(
        val etat: Etat = Etat.ARRET,
        val message: String = "",
        val radio: String = "",
        val adresseCiv: Int = 0,
        val latenceMs: Int = -1,
        val audioRecus: Long = 0, val audioPerdus: Long = 0, val audioEnRetard: Long = 0,
        val manques: Long = 0, val renvoyes: Long = 0, val redemandes: Long = 0,
        val occupeAvant: Boolean = false,
        val tauxRx: List<Int> = emptyList(),
    )

    private val _statut = MutableStateFlow(Statut())
    val statut: StateFlow<Statut> = _statut.asStateFlow()
    private fun pose(f: Statut.() -> Statut) = _statut.update { it.f() }

    /** CI-V frames from the radio (FE FE … FD), in order, once each. */
    @Volatile var civRecue: ((ByteArray) -> Unit)? = null
    /** Receive audio, decoded to 16-bit at the wire's rate. */
    @Volatile var audioRecu: ((ShortArray, Int) -> Unit)? = null

    // ------------------------------------------------------------ channels

    inner class Canal(val nom: String, val prise: PriseUdp) {
        val moi: Int = (java.util.concurrent.ThreadLocalRandom.current().nextInt() or 0x01000000)
        @Volatile var lui = 0
        private var seq = 1
        private var seqPing = 0
        val reserve = ReserveEnvoyes()
        val suivi = SuiviRecus()
        val boite = LinkedBlockingQueue<ByteArray>()
        @Volatile var gestionnaire: ((ByteArray) -> Unit)? = null
        @Volatile var ici = false
        @Volatile var pret = false
        @Volatile var ferme = false
        @Volatile var parti = false
        @Volatile var dernierRecu = 0L
        @Volatile var dernierEnvoi = 0L
        @Volatile var latence = -1
        private val pings = HashMap<Int, Long>()
        @Volatile var redemandes = 0L
        private var lecteur: Thread? = null
        var compteJournal = 0

        @Synchronized fun envoieBrut(b: ByteArray) {
            if (ferme) return
            dernierEnvoi = horloge()
            journal?.let { j -> if (b.size < Paquets.AUDIO_ENTETE + 64 || compteJournal++ % 50 == 0) j(Paquets.resume(true, nom, b)) }
            runCatching { prise.envoie(b) }
        }

        /** A numbered packet: the next sequence, kept for a retransmit. */
        @Synchronized fun envoieSuivi(fabrique: (Int) -> ByteArray) {
            val s = seq; seq = (seq + 1) and 0xFFFF
            val b = fabrique(s)
            reserve.garde(s, b)
            envoieBrut(b)
        }

        fun controle(type: Int, s: Int = 0) = envoieBrut(Paquets.controle(type, s, moi, lui))
        fun idle() = envoieSuivi { Paquets.controle(Paquets.T_DONNEES, it, moi, lui) }

        @Synchronized fun ping() {
            val s = seqPing; seqPing = (seqPing + 1) and 0xFFFF
            pings[s] = horloge()
            if (pings.size > 32) pings.remove(pings.keys.first())
            envoieBrut(Paquets.ping(s, moi, lui, false, (horloge() and 0x7FFFFFFF).toInt()))
        }

        fun redemande(s: List<Int>) { for (x in s) { redemandes++; envoieBrut(Paquets.reprise(x, moi, lui)) } }

        fun demarre() {
            lecteur = thread(isDaemon = true, name = "satme-icom-$nom") {
                while (!ferme) {
                    val b = runCatching { prise.recois(200) }.getOrNull() ?: continue
                    runCatching { traite(b) }
                }
            }
        }

        private fun traite(b: ByteArray) {
            val e = Paquets.entete(b) ?: return
            // Another session's leftovers, or another program's: not for us.
            if (lui != 0 && e.emetteur != lui) return
            dernierRecu = horloge()
            val audio = nom == "audio" && b.size > Paquets.AUDIO_ENTETE + 8
            journal?.let { j -> if (!audio || compteJournal++ % 50 == 0) j(Paquets.resume(false, nom, b)) }
            when (e.type) {
                Paquets.T_JE_SUIS_LA -> { lui = e.emetteur; ici = true; return }
                Paquets.T_PRET -> { pret = true; return }
                Paquets.T_DECONNEXION -> { parti = true; return }
                Paquets.T_PING -> {
                    if (Paquets.estReponsePing(b)) synchronized(this) {
                        pings.remove(e.seq)?.let { latence = (horloge() - it).toInt() }
                    } else envoieBrut(Paquets.ping(e.seq, moi, lui, true, Paquets.valeurPing(b)))
                    return
                }
                Paquets.T_REPRISE -> {
                    for (s in Paquets.reprises(b)) envoieBrut(reserve.cherche(s) ?: Paquets.controle(Paquets.T_DONNEES, s, moi, lui))
                    return
                }
                Paquets.T_DONNEES -> {
                    if (audio) { gestionnaire?.invoke(b); return }
                    if (!suivi.recois(e.seq, horloge())) return
                    if (b.size == Paquets.ENTETE) return    // an idle
                    val g = gestionnaire
                    if (g != null) g(b) else boite.offer(b)
                }
            }
        }

        /** The handshake: are you there → I am here, are you ready → ready. */
        fun cherche(delaiMs: Long, aussiPret: Boolean = true): Boolean {
            val fin = horloge() + delaiMs
            while (!ici && horloge() < fin) { controle(Paquets.T_ES_TU_LA); attends(300) { ici } }
            if (!ici || !aussiPret) return ici
            while (!pret && horloge() < fin) { controle(Paquets.T_PRET, 1); attends(300) { pret } }
            return pret
        }

        fun attendsPaquet(delaiMs: Long, accepte: (ByteArray) -> Boolean): ByteArray? {
            val fin = horloge() + delaiMs
            while (true) {
                val reste = fin - horloge()
                if (reste <= 0) return null
                val b = boite.poll(minOf(reste, 100), TimeUnit.MILLISECONDS) ?: continue
                if (accepte(b)) return b
            }
        }

        fun auRevoir() { repeat(2) { controle(Paquets.T_DECONNEXION) } }

        fun arrete() { ferme = true; runCatching { prise.ferme() }; lecteur?.join(400) }
    }

    private fun attends(ms: Long, ok: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < fin) Thread.sleep(10)
    }

    @Volatile private var controle: Canal? = null
    @Volatile private var civ: Canal? = null
    @Volatile private var audio: Canal? = null
    @Volatile private var jeton = 0
    private var demande = 0
    private var seqInterne = 0
    private var seqCiv = 0
    @Volatile private var aJeton = false
    @Volatile private var radio: Paquets.Radio? = null
    private var tic: Thread? = null
    @Volatile private var enMarche = false
    val tampon = TamponAudio(config.latenceMs, horloge) { donnees -> sortieAudio(donnees) }

    private fun prochainInterne(): Int = seqInterne.also { seqInterne = (seqInterne + 1) and 0xFFFF }

    // ------------------------------------------------------------ opening

    /**
     * Logs in and opens the streams; true when connected. On any failure
     * everything already opened is closed properly first.
     */
    fun ouvre(): Boolean {
        pose { Statut(Etat.RECHERCHE, "recherche du poste sur " + config.hote + ":" + config.portControle) }
        val ok = runCatching { ouvreDedans() }.getOrElse { e -> echec(Etat.PERDU, "erreur : " + (e.message ?: e.javaClass.simpleName)) }
        if (!ok) ferme(garderMessage = true)
        return ok
    }

    private fun echec(etat: Etat, msg: String): Boolean { pose { copy(etat = etat, message = msg) }; return false }

    private fun ouvreDedans(): Boolean {
        val c = Canal("contrôle", fabrique(config.hote, config.portControle)).also { controle = it; it.demarre() }
        if (!c.cherche(3_000)) return echec(Etat.PERDU,
            if (c.ici) "le poste répond mais ne se dit pas prêt" else "rien ne répond sur " + config.hote + ":" + config.portControle + " (adresse, « Network Control » sur ON ?)")
        pose { copy(etat = Etat.CONNEXION, message = "connexion…") }
        demande = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 0xFFFF)
        val si = prochainInterne()
        c.envoieSuivi { Paquets.connexion(it, c.moi, c.lui, si, demande, config.utilisateurCode, config.motDePasseCode, config.nomPc) }
        val rep = c.attendsPaquet(3_000) { b -> Paquets.jeton(b)?.let { it.genre == Paquets.G_CONNEXION && it.sens == Paquets.SENS_REPONSE } == true }
            ?: return echec(Etat.PERDU, "le poste ne répond pas à la connexion")
        val j = Paquets.jeton(rep)!!
        if (j.erreur != 0) return echec(Etat.REFUSE, "identifiant ou mot de passe refusé par le poste")
        jeton = j.jeton; aJeton = true
        val sc = prochainInterne()
        c.envoieSuivi { Paquets.demandeJeton(Paquets.G_CREATION, it, c.moi, c.lui, sc, demande, jeton) }
        // The capabilities: the radio's identity, name, CI-V address, rates.
        var info: Paquets.InfoRadio? = null
        var radios: List<Paquets.Radio> = emptyList()
        val fin = horloge() + 3_000
        while (radios.isEmpty() && horloge() < fin) {
            val b = c.attendsPaquet(fin - horloge()) { true } ?: break
            if (b.size >= Paquets.K_PREMIERE + Paquets.CAPACITES_ENTREE) radios = Paquets.capacites(b)
            else if (b.size == Paquets.INFO_LONGUEUR) info = Paquets.infoRadio(b)
        }
        val r = radios.firstOrNull() ?: return echec(Etat.PERDU, "le poste n'a pas donné ses capacités")
        radio = r
        if (info == null) c.attendsPaquet(800) { it.size == Paquets.INFO_LONGUEUR }?.let { info = Paquets.infoRadio(it) }
        val occupe = info?.occupe == true
        pose { copy(radio = r.nom, adresseCiv = r.civ, occupeAvant = occupe, tauxRx = Paquets.tauxOfferts(r.tauxRx)) }
        val offerts = Paquets.tauxOfferts(r.tauxRx)
        if (offerts.isNotEmpty() && config.codec.taux !in offerts)
            return echec(Etat.REFUSE, "le poste n'offre pas " + config.codec.taux / 1000 + " kHz (il offre " + offerts.joinToString(", ") { (it / 1000).toString() } + " kHz)")
        val si2 = prochainInterne()
        val flux = Paquets.Flux(config.codec.octet, config.codec.taux, config.portCiv, config.portAudio)
        c.envoieSuivi { Paquets.infoHote(it, c.moi, c.lui, si2, demande, jeton, r, config.utilisateurCode, flux) }
        val st = c.attendsPaquet(3_000) { it.size == Paquets.STATUT_LONGUEUR }?.let { Paquets.statut(it) }
            ?: return echec(Etat.PERDU, "le poste n'a pas accordé les flux" + if (occupe) " (une autre session le tient)" else "")
        if (st.portCiv == 0 && st.portAudio == 0) return echec(Etat.REFUSE, "le poste refuse les flux" + if (occupe) " : une autre session le tient" else "")
        // From here the control channel is handled in the background.
        c.gestionnaire = { b -> controleRecu(b) }

        val v = Canal("civ", fabrique(config.hote, if (st.portCiv > 0) st.portCiv else config.portCiv)).also { civ = it; it.demarre() }
        if (!v.cherche(3_000)) return echec(Etat.PERDU, "le port CI-V du poste ne répond pas")
        v.gestionnaire = { b -> civRecu(b) }
        v.envoieSuivi { Paquets.ouvreFerme(it, v.moi, v.lui, prochainCiv(), true) }

        if (config.audio) {
            val a = Canal("audio", fabrique(config.hote, if (st.portAudio > 0) st.portAudio else config.portAudio)).also { audio = it; it.demarre() }
            a.gestionnaire = { b -> Paquets.audio(b)?.let { p -> tampon.recois(p.seq, p.donnees) } }
            // The radio sends audio as soon as it answers; "ready" only brings its pings.
            if (!a.cherche(3_000)) return echec(Etat.PERDU, "le port audio du poste ne répond pas")
        }
        enMarche = true
        pose { copy(etat = Etat.CONNECTE, message = "connecté à " + r.nom.ifBlank { "l'Icom" } + " (" + config.hote + ")") }
        tic = thread(isDaemon = true, name = "satme-icom-tic") { boucle() }
        return true
    }

    @Synchronized private fun prochainCiv(): Int = seqCiv.also { seqCiv = (seqCiv + 1) and 0xFFFF }

    private fun controleRecu(b: ByteArray) {
        val j = Paquets.jeton(b) ?: return
        // A renewal refused: the token is no longer good, the session will not last.
        if (j.genre == Paquets.G_RENOUVELLEMENT && j.sens == Paquets.SENS_REPONSE && j.erreur != 0)
            pose { copy(message = "le poste a refusé le renouvellement du jeton") }
    }

    private fun civRecu(b: ByteArray) {
        val t = Paquets.trameCiv(b) ?: return
        // One packet may carry more than one frame.
        for (f in fr.f4ioz.satcombo.cat.CatDecode.splitCiv(t)) civRecue?.invoke(f)
    }

    private fun sortieAudio(donnees: ByteArray) {
        val a = audioRecu ?: return
        a(DecodeAudio.decode(config.codec, donnees), config.codec.taux)
    }

    /** Keepalive: pings, idles, token renewal, retransmit requests, liveness. */
    private fun boucle() {
        var dernierPing = 0L
        var dernierRenouvellement = horloge()
        while (enMarche) {
            Thread.sleep(20)
            val t = horloge()
            val canaux = listOfNotNull(controle, civ, audio)
            tampon.vide()
            audio?.let { a -> val m = tampon.manquants(); if (m.isNotEmpty()) a.redemande(m) }
            if (t - dernierPing >= 500) {
                dernierPing = t
                for (k in canaux) {
                    k.ping()
                    k.suivi.aRedemander(t).takeIf { it.isNotEmpty() }?.let { k.redemande(it) }
                }
                controle?.let { if (t - it.dernierEnvoi > 500) it.idle() }
                civ?.let { if (t - it.dernierEnvoi > 500) it.idle() }
            }
            if (t - dernierRenouvellement >= 60_000) {
                dernierRenouvellement = t
                controle?.let { c -> val si = prochainInterne(); c.envoieSuivi { Paquets.demandeJeton(Paquets.G_RENOUVELLEMENT, it, c.moi, c.lui, si, demande, jeton) } }
            }
            val parti = canaux.any { it.parti }
            val muet = canaux.any { t - it.dernierRecu > 5_000 }
            pose { copy(latenceMs = controle?.latence ?: -1, audioRecus = tampon.recus, audioPerdus = tampon.perdus,
                audioEnRetard = tampon.enRetard, manques = canaux.sumOf { it.suivi.manques },
                renvoyes = canaux.sumOf { it.reserve.renvoyes }, redemandes = canaux.sumOf { it.redemandes }) }
            if (parti || muet) {
                enMarche = false
                pose { copy(etat = Etat.PERDU, message = if (parti) "le poste a fermé la session (une autre l'a prise ?)" else "le poste ne répond plus") }
                thread(isDaemon = true) { ferme(garderMessage = true) }
                return
            }
        }
    }

    // ------------------------------------------------------------ CI-V out

    /**
     * Sends one CI-V frame (FE FE … FD) to the rig. Null when sent, else why
     * not: a transmit command refused, or no session.
     */
    fun envoieCiv(trame: ByteArray): String? {
        GardeEmission.refus(trame)?.let { journal?.invoke("!! " + it); return it }
        val v = civ ?: return "pas de session"
        if (!enMarche) return "pas de session"
        v.envoieSuivi { Paquets.civ(it, v.moi, v.lui, prochainCiv(), trame) }
        return null
    }

    val connecte: Boolean get() = enMarche

    // ------------------------------------------------------------ closing

    /**
     * Logs out: the CI-V stream closed, goodbye on the audio and CI-V ports,
     * the token handed back (its answer awaited a moment), goodbye on
     * control. Safe to call twice.
     */
    @Synchronized fun ferme(garderMessage: Boolean = false) {
        enMarche = false
        tic?.let { if (it !== Thread.currentThread()) it.join(500) }
        tic = null
        civ?.let { v ->
            if (v.ici && !v.parti) { v.envoieSuivi { Paquets.ouvreFerme(it, v.moi, v.lui, prochainCiv(), false) }; v.auRevoir() }
            v.arrete()
        }
        civ = null
        audio?.let { a -> if (a.ici && !a.parti) a.auRevoir(); a.arrete() }
        audio = null
        controle?.let { c ->
            if (aJeton && c.ici && !c.parti) {
                c.gestionnaire = null
                val si = prochainInterne()
                c.envoieSuivi { Paquets.demandeJeton(Paquets.G_RETRAIT, it, c.moi, c.lui, si, demande, jeton) }
                c.attendsPaquet(1_000) { b -> Paquets.jeton(b)?.let { it.genre == Paquets.G_RETRAIT && it.sens == Paquets.SENS_REPONSE } == true }
            }
            if (c.ici && !c.parti) c.auRevoir()
            c.arrete()
        }
        controle = null
        aJeton = false
        tampon.remetAZero()
        if (!garderMessage) pose { Statut(Etat.ARRET, "session fermée") }
        else pose { copy(etat = if (etat == Etat.CONNECTE) Etat.ARRET else etat) }
    }
}
