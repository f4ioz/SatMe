/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.data.SettingsStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Icom on the network as SatMe's other parts use it: the CAT takes its
 * CI-V ([lienCiv], a [SerialLink] like the USB cable), the sound card list
 * its receive audio ([NOM_SOURCE]). One session serves both; it opens with
 * the first user and logs out with the last.
 */
class PosteIcomReseau(
    private val settings: SettingsStore,
    private val fabrique: (String, Int) -> PriseUdp = { h, p -> PriseDatagramme(h, p) },
    /** Where the packet log goes when switched on (a file on the PC). */
    var ecrisJournal: ((String) -> Unit)? = null,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    enum class Usage { CAT, AUDIO }

    private val usages = HashSet<Usage>()
    @Volatile var session: SessionIcom? = null; private set
    @Volatile private var dernier = SessionIcom.Statut()
    @Volatile private var reprise: Thread? = null
    @Volatile var reprises = 0; private set

    /** The decoders' audio: 48 kHz 16-bit, (samples, count, rate). */
    val auditeurs = CopyOnWriteArrayList<(ShortArray, Int, Int) -> Unit>()
    val spectre = SpectrePoste()
    private var sur: Surechantillonneur? = null

    /** CI-V frames for the CAT (scope frames and our own scope acks kept out). */
    private val pourCat = LinkedBlockingQueue<ByteArray>(512)

    fun statut(): SessionIcom.Statut = session?.statut?.value ?: dernier
    fun utilise(u: Usage): Boolean = synchronized(this) { u in usages }

    /** The settings as a login: null and why when something is missing. */
    fun config(): Pair<ConfigReseau?, String> {
        val s = settings
        if (s.icomHote.isBlank()) return null to "adresse IP du poste à renseigner (Réglages › Poste)"
        val table = CodageIdentifiants().apply { lis(s.icomTableCodage) }
        val u = CodageIdentifiants.hex(s.icomUtilisateurCode) ?: table.code(s.icomUtilisateur)
        val m = CodageIdentifiants.hex(s.icomMdpCode) ?: table.code(s.icomMdp)
        if (s.icomUtilisateur.isBlank() && s.icomUtilisateurCode.isBlank()) return null to "identifiant réseau du poste à renseigner"
        if (u == null || m == null) return null to "identifiants à coder : donne leur forme codée (relevée dans une trace), voir « ? »"
        return ConfigReseau(s.icomHote, s.icomPortControle, s.icomPortCiv, s.icomPortAudio, u, m,
            codec = CodecReseau.de(s.icomCodec), latenceMs = s.icomLatenceMs) to ""
    }

    /** [u] starts using the rig; null when the session is up, else why not. */
    fun prends(u: Usage): String? {
        synchronized(this) {
            if (session?.connecte == true) { usages += u; if (u == Usage.CAT) pourCat.clear(); return null }
        }
        val (c, pourquoi) = config()
        if (c == null) { dernier = SessionIcom.Statut(SessionIcom.Etat.REFUSE, pourquoi); return pourquoi }
        synchronized(this) {
            if (session?.connecte == true) { usages += u; return null }
            val s = ouvreSession(c) ?: return dernier.message.ifBlank { "connexion impossible" }
            session = s
            usages += u
        }
        return null
    }

    private fun ouvreSession(c: ConfigReseau): SessionIcom? {
        val s = SessionIcom(c, fabrique, horloge, if (settings.icomJournal) ecrisJournal else null)
        sur = Surechantillonneur(c.codec.taux)
        s.civRecue = { f -> civRecue(f) }
        s.audioRecu = { pcm, _ -> audio(pcm) }
        if (!s.ouvre()) { dernier = s.statut.value; return null }
        dernier = s.statut.value
        if (settings.icomSpectre) spectreActif(s, true)
        surveille(s)
        return s
    }

    /** A session lost while still used: tried again, 2, 4, 8, 16 then every 30 s, until it is back or nobody wants it. */
    private fun surveille(s: SessionIcom) {
        thread(isDaemon = true, name = "satme-icom-garde") {
            while (true) {
                Thread.sleep(250)
                if (session !== s) return@thread
                val e = s.statut.value.etat
                if (e == SessionIcom.Etat.PERDU || (e == SessionIcom.Etat.ARRET && !s.connecte)) break
            }
            dernier = s.statut.value
            var attente = 2_000L
            while (true) {
                synchronized(this) { if (session !== s || usages.isEmpty()) return@thread }
                Thread.sleep(attente)
                val c = config().first ?: return@thread
                synchronized(this) {
                    if (session !== s || usages.isEmpty()) return@thread
                    reprises++
                    val n = ouvreSession(c)
                    if (n != null) { session = n; return@thread }
                }
                attente = minOf(30_000L, if (attente < 16_000L) attente * 2 else 30_000L)
            }
        }
    }

    /** [u] is done; the last one out logs out. */
    fun rends(u: Usage) {
        val s: SessionIcom
        synchronized(this) {
            usages -= u
            if (usages.isNotEmpty()) return
            s = session ?: return
            session = null
        }
        fermeSession(s)
    }

    /** Everything closed now (the program stops): the radio's one session handed back. */
    fun ferme() {
        val s = synchronized(this) { usages.clear(); session.also { session = null } } ?: return
        fermeSession(s)
    }

    private fun fermeSession(s: SessionIcom) {
        if (settings.icomSpectre && s.connecte) spectreActif(s, false)
        s.ferme()
        dernier = s.statut.value
    }

    /** Scope on or off on the rig (from controller E1: its acks never reach the CAT). */
    fun spectreActif(on: Boolean) { session?.let { spectreActif(it, on) } }

    private fun spectreActif(s: SessionIcom, on: Boolean) {
        val radio = s.statut.value.adresseCiv.takeIf { it > 0 } ?: 0xA2
        for (f in SpectrePoste.active(radio, CTRL_SPECTRE, on)) s.envoieCiv(f)
    }

    private fun civRecue(f: ByteArray) {
        if (spectre.recois(f)) return
        if (f.size >= 4 && (f[2].toInt() and 0xFF) == CTRL_SPECTRE) return
        if (!utilise(Usage.CAT)) return
        if (!pourCat.offer(f)) { pourCat.poll(); pourCat.offer(f) }
    }

    private fun audio(pcm: ShortArray) {
        if (auditeurs.isEmpty()) return
        val s = (sur ?: return).traite(pcm)
        for (a in auditeurs) runCatching { a(s, s.size, SORTIE_HZ) }
    }

    /** The CI-V seen by the CAT: what goes through the session, what comes back. */
    fun lienCiv(): SerialLink = LienCivReseau()

    /** Refusals of the TX guard, for the status line. */
    @Volatile var dernierRefus: String = ""; private set

    inner class LienCivReseau : SerialLink {
        private var reste: ByteArray = ByteArray(0)
        @Volatile private var ferme = false

        override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
            if (ferme) return false
            val s = session ?: return false
            val trames = CatDecode.splitCiv(bytes)
            if (trames.isEmpty()) return false
            // The guard first, on every frame: nothing goes when one is refused.
            trames.firstNotNullOfOrNull { GardeEmission.refus(it) }?.let { dernierRefus = it; return false }
            for (t in trames) if (s.envoieCiv(t) != null) return false
            return true
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (ferme) return 0
            if (reste.isEmpty()) reste = pourCat.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            // More frames already in: handed together, as a serial read would.
            while (reste.size < buf.size) reste += pourCat.poll() ?: break
            val n = minOf(buf.size, reste.size)
            reste.copyInto(buf, 0, 0, n)
            reste = reste.copyOfRange(n, reste.size)
            return n
        }

        override fun close() {
            if (ferme) return
            ferme = true
            rends(Usage.CAT)
        }
    }

    companion object {
        const val NOM_SOURCE = "IC-9700 (réseau)"
        const val SORTIE_HZ = 48_000
        /** The controller address the scope commands come from. */
        const val CTRL_SPECTRE = 0xE1
    }
}
