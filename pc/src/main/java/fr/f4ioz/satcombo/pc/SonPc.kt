/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The PC's sound card: the rig's audio in (a TS-2000's USB codec, an
 * IC-9700's), handed block by block to whoever listens — the recorder, the
 * SSTV decoder, the level meter.
 *
 * 16-bit mono at 48 kHz, else 44.1 kHz: what USB codecs offer. Nothing is
 * opened until the operator picks a card and starts it.
 */
class SonPc {

    data class Appareil(val index: Int, val nom: String, val description: String)

    data class Etat(
        val actif: Boolean = false,
        val appareil: String = "",
        val rate: Int = 0,
        /** Peak of the last block, 0..1. */
        val niveau: Float = 0f,
        val erreur: String = "",
        /** Sound lost since the card started (an overloaded PC): holes, their total (ms), the last one (UTC ms). */
        val perdus: Int = 0,
        val perduMs: Long = 0L,
        val dernierePerteMs: Long = 0L,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /** The input's level and liveness over the last 10 s (« Pourquoi rien ? »): reset when another source starts. */
    val mesure = fr.f4ioz.satcombo.audio.MesureEntree()

    /** Who gets each block: (samples, count, rate). */
    val auditeurs = CopyOnWriteArrayList<(ShortArray, Int, Int) -> Unit>()

    @Volatile private var ligne: TargetDataLine? = null
    @Volatile private var lecture: CaptureSon? = null

    /** Every loss of the card's sound, the last hours of them (« son perdu »). */
    val pertes = fr.f4ioz.satcombo.audio.SonPerdu()
    /** When the card running started (UTC ms; 0: none). */
    @Volatile var debutMs = 0L; private set

    /**
     * Told of each hole in the sound handed out, once, on the decoders'
     * thread, right before it: (samples, filled, rate) — the silence about to
     * come, or a cut's samples that will not (too long, [fr.f4ioz.satcombo.audio.TrousCapture.MAX_MS]).
     */
    val trous = CopyOnWriteArrayList<(Int, Boolean, Int) -> Unit>()

    /** The capture's own figures (the Son tab's detail, the tests); null when no card runs. */
    val capture: CaptureSon? get() = lecture

    @Volatile var horloge: () -> Long = System::currentTimeMillis
    /** Where a warning is written (the PC's log). */
    @Volatile var journal: (String) -> Unit = { System.err.println(it) }
    @Volatile private var dernierAvisMs = 0L
    @Volatile private var avisTus = 0

    /**
     * A source that is not a card but is listed and started as one (the
     * Icom's receive audio by the network): [demarre] gets the hand-out and
     * says why it could not start, null when it runs.
     */
    class SourceVirtuelle(
        val nom: String, val description: String,
        val disponible: () -> Boolean,
        val demarre: ((ShortArray, Int, Int) -> Unit) -> String?,
        val arrete: () -> Unit,
    )

    val virtuelles = CopyOnWriteArrayList<SourceVirtuelle>()
    @Volatile private var virtuelle: SourceVirtuelle? = null

    private fun formats() = listOf(48_000f, 44_100f).map { AudioFormat(it, 16, 1, true, false) }

    /** The cards that can record in one of [formats] (the sound server's playback-only entry left out). */
    fun appareils(): List<Appareil> = runCatching {
        // The demonstration: no real card listed, the virtual sources only.
        if (ModeDemo.actif) return@runCatching emptyList()
        AudioSystem.getMixerInfo().toList().filter { info ->
            if (CartesSonLinux.lectureSeule(info.name)) return@filter false
            val m = AudioSystem.getMixer(info)
            formats().any { m.isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) }
        }.mapIndexed { i, info -> Appareil(i, info.name, info.description) }
    }.getOrDefault(emptyList()).let { cartes ->
        cartes + virtuelles.filter { runCatching { it.disponible() }.getOrDefault(false) }
            .mapIndexed { i, v -> Appareil(cartes.size + i, v.nom, v.description) }
    }

    /** Opens card [nom] and starts handing blocks out. */
    fun demarre(nom: String): Boolean {
        reprendre = null
        arrete()
        mesure.remet()
        virtuelles.firstOrNull { it.nom == nom }?.let { v ->
            _etat.update { Etat(actif = false, appareil = nom, erreur = "connexion…") }
            val err = runCatching { v.demarre { pcm, n, rate -> if (virtuelle === v) distribue(pcm, n, rate) } }
                .getOrElse { it.message ?: "erreur" }
            if (err != null) { runCatching { v.arrete() }; _etat.update { Etat(actif = false, appareil = nom, erreur = err) }; return false }
            virtuelle = v
            _etat.update { Etat(actif = true, appareil = nom, rate = fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.SORTIE_HZ) }
            return true
        }
        ModeDemo.refus("une vraie carte son")?.let { e -> _etat.update { it.copy(erreur = e) }; return false }
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == nom }
            ?: run { _etat.update { it.copy(erreur = "carte son introuvable : $nom") }; return false }
        val m = AudioSystem.getMixer(info)
        for (f in formats()) {
            val di = DataLine.Info(TargetDataLine::class.java, f)
            if (!m.isLineSupported(di)) continue
            // A second of buffer: the capture thread may be held back that long without losing anything.
            val l = runCatching { (m.getLine(di) as TargetDataLine).also { ouvre(it, f) } }.getOrNull() ?: continue
            ligne = l
            val rate = f.sampleRate.toInt()
            _etat.update { Etat(actif = true, appareil = nom, rate = rate) }
            lance(LigneJava(l), rate, nom)
            return true
        }
        _etat.update { it.copy(actif = false, erreur = "$nom n'accepte ni 48 ni 44,1 kHz en 16 bits mono") }
        return false
    }

    /**
     * Card [nom] opened for one listener of its own, beside the decoders' card
     * (the rig's IF for the SDR tab): the same formats and reading as [demarre].
     * The rate and a closer, or null and why.
     */
    fun capture(nom: String, recoit: (ShortArray, Int, Int) -> Unit): Pair<Pair<Int, () -> Unit>?, String> {
        ModeDemo.refus("une vraie carte son")?.let { return null to it }
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == nom } ?: return null to "carte son introuvable : $nom"
        // The decoders' card on the same card: let go first (one line a card on some systems).
        if (_etat.value.appareil == nom && lecture != null) arrete()
        val m = AudioSystem.getMixer(info)
        for (f in formats()) {
            val di = DataLine.Info(TargetDataLine::class.java, f)
            if (!m.isLineSupported(di)) continue
            val l = runCatching { (m.getLine(di) as TargetDataLine).also { ouvre(it, f) } }.getOrNull() ?: continue
            val rate = f.sampleRate.toInt()
            // Read as the decoders' card: its own thread, its holes filled; this listener's losses only written to the log.
            val c = CaptureSon(LigneJava(l), rate, { pcm, n, r, _ -> recoit(pcm, n, r) },
                perte = { p -> avertit(nom, p, rate) }, blocOctets = 3840).demarre("satme-son-fi")
            return (rate to { c.arrete() }) to ""
        }
        return null to "$nom n'accepte ni 48 ni 44,1 kHz en 16 bits mono"
    }

    private fun ouvre(l: TargetDataLine, f: AudioFormat) {
        l.open(f, f.frameSize * (f.sampleRate.toInt() * CaptureSon.TAMPON_MS / 1000))
        l.start()
    }

    /**
     * [l] read as the decoders' card ([CaptureSon]): its blocks to every
     * listener, its holes as silence, its losses counted and told (also what
     * the tests start with a made-up line).
     */
    fun lance(l: LigneCapture, rate: Int, nom: String, nanos: () -> Long = System::nanoTime, threads: Boolean = true): CaptureSon {
        debutMs = horloge()
        _etat.update { Etat(actif = true, appareil = nom, rate = rate) }
        var moi: CaptureSon? = null
        // Only the capture running hands out (a stopped one's last block never beside the next one's).
        val c = CaptureSon(l, rate,
            recoit = { pcm, n, r, comble -> if (lecture === moi) { if (comble) distribueSilence(pcm, n, r) else distribue(pcm, n, r) } },
            trou = { k, comble -> if (lecture === moi) for (t in trous) runCatching { t(k, comble, rate) } },
            perte = { p -> notePerte(nom, p, rate) },
            nanos = nanos, horloge = horloge)
        moi = c
        lecture = c
        // The tests may drive it themselves, step by step.
        return if (threads) c.demarre() else c
    }

    private fun notePerte(nom: String, p: fr.f4ioz.satcombo.audio.SonPerdu.Perte, rate: Int) {
        pertes.note(p)
        val b = pertes.entre(debutMs)
        _etat.update { it.copy(perdus = b.n, perduMs = b.ms, dernierePerteMs = b.derniereMs) }
        avertit(nom, p, rate)
    }

    /** A warning line in the log, one every 5 s at most (the others counted in it). */
    private fun avertit(nom: String, p: fr.f4ioz.satcombo.audio.SonPerdu.Perte, rate: Int) {
        val t = horloge()
        if (t - dernierAvisMs < 5_000L) { avisTus++; return }
        val autres = avisTus
        dernierAvisMs = t; avisTus = 0
        val pourquoi = if (p.cause == fr.f4ioz.satcombo.audio.SonPerdu.Cause.CARTE) "tampon de la carte débordé" else "décodeurs en retard de plus de ${CaptureSon.FILE_MS / 1000} s"
        val charge = fr.f4ioz.satcombo.audio.SonPerdu.cause(chargeSysteme(), Runtime.getRuntime().availableProcessors())
        journal("son : « $nom » à $rate Hz — ${fr.f4ioz.satcombo.audio.SonPerdu.duree(p.ms)} perdus ($pourquoi" +
            (if (charge.isNotEmpty()) " ; $charge" else "") + ") — " + (if (p.comble) "comblés de silence" else "coupure trop longue pour être comblée") +
            (if (autres > 0) " (+ $autres autre" + (if (autres > 1) "s" else "") + " perte" + (if (autres > 1) "s" else "") + " depuis le dernier avis)" else ""))
    }

    /** One block to every listener (also what the tests feed). */
    fun distribue(pcm: ShortArray, n: Int, rate: Int, crete: Int = pcm.take(n).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0) {
        _etat.update { it.copy(niveau = crete / 32768f) }
        mesure.bloc(pcm, n, System.currentTimeMillis())
        for (a in auditeurs) runCatching { a(pcm, n, rate) }
    }

    /** Silence put in a hole: to every listener; neither the level nor the meter see it (it is no sound heard). */
    fun distribueSilence(pcm: ShortArray, n: Int, rate: Int) {
        for (a in auditeurs) runCatching { a(pcm, n, rate) }
    }

    /**
     * Another source takes the decoders' input (the SDR dongle): the card is
     * stopped — two threads feeding the same decoders would mix their state —
     * and the state says who feeds them, at what rate, as a card would.
     */
    fun prendPour(nom: String, rate: Int) {
        val carte = (lecture != null || virtuelle != null) && _etat.value.actif
        // A card being recorded keeps the decoders: the dongle is then only heard (speaker, « Écouter ici »),
        // never mixed into a recording made at the card's rate (an IC-9700 pass, QO-100 switched on to listen).
        if (carte && gardeCarte()) return
        reprendre = if (carte) _etat.value.appareil else null
        arrete()
        mesure.remet()
        _etat.update { Etat(actif = true, appareil = nom, rate = rate) }
    }

    /** That source is done: the card it replaced gets the decoders back, else nothing feeds them any more. */
    fun rendPar(nom: String) {
        if (_etat.value.appareil != nom || lecture != null || virtuelle != null) return
        val r = reprendre
        reprendre = null
        if (r != null && demarre(r)) return
        _etat.update { it.copy(actif = false, niveau = 0f) }
    }

    /** True while the card must keep the decoders (a recording runs on it). */
    @Volatile var gardeCarte: () -> Boolean = { false }
    /** The card a borrowing source (the dongle) took the decoders from, given back when it stops. */
    @Volatile private var reprendre: String? = null

    fun arrete() {
        virtuelle?.let { v -> virtuelle = null; runCatching { v.arrete() } }
        lecture?.arrete()
        lecture = null
        ligne?.let { runCatching { it.stop(); it.close() } }
        ligne = null
        _etat.update { it.copy(actif = false, niveau = 0f) }
    }

    companion object {
        /** The PC's load over the last minute (Linux's /proc/loadavg), null elsewhere. */
        fun chargeSysteme(racine: java.io.File = java.io.File("/")): Double? =
            fr.f4ioz.satcombo.audio.SonPerdu.charge(runCatching { java.io.File(racine, "proc/loadavg").takeIf { it.isFile }?.readText() }.getOrNull())
    }
}

/**
 * A recording as a WAV file, 16-bit mono, at half the card's rate (24 or
 * 22.05 kHz: enough for voice, SSTV and telemetry, half the size). The header
 * is rewritten at every second, so a PC switched off mid-pass leaves a file
 * that plays up to that second.
 */
class EnregistreurWav(val fichier: File, private val rateCarte: Int, private val horloge: () -> Long = System::currentTimeMillis) {
    val rate = rateCarte / 2
    private val raf = RandomAccessFile(fichier, "rw").apply { setLength(0); write(entete(0)) }
    private var donnees = 0L
    private var impair: Int? = null
    private var depuisEntete = 0L
    private val debutMs = horloge()
    private var trous = 0
    private var combleMs = 0L
    private var coupeMs = 0L
    private var depuisTemps = 0L

    /** Its sidecar ([fr.f4ioz.satcombo.audio.BilanEnregistrement.Temps]): start, stop, samples, the holes — a loss told afterwards. */
    val fichierTemps: File = fr.f4ioz.satcombo.audio.BilanEnregistrement.fichierTemps(fichier)

    init { ecritTemps(0L) }

    /**
     * A hole in the card's sound ([SonPc.trous]): [n] samples at the card's
     * rate. Filled: its silence comes next as blocks, only counted here; a cut
     * (not filled for the decoders): written here as silence (10 min at most),
     * so the file keeps the time the pass lasted.
     */
    @Synchronized fun trou(n: Int, comble: Boolean) {
        trous++
        val ms = n * 1000L / rateCarte
        if (comble) { combleMs += ms; return }
        coupeMs += ms
        var reste = minOf(n.toLong(), rateCarte * 600L)
        val z = ShortArray(4096)
        while (reste > 0) { val k = minOf(reste, z.size.toLong()).toInt(); ajoute(z, k); reste -= k }
    }

    private fun ecritTemps(fin: Long) {
        runCatching { fichierTemps.writeText(fr.f4ioz.satcombo.audio.BilanEnregistrement.Temps(debutMs, fin, donnees / 2, rate, trous, combleMs, coupeMs).encode()) }
    }

    @Synchronized fun ajoute(pcm: ShortArray, n: Int) {
        val out = java.io.ByteArrayOutputStream(n + 2)
        var i = 0
        // Two samples averaged into one; an odd one waits for the next block.
        impair?.let { if (n > 0) { ecrit(out, (it + pcm[0]) / 2); i = 1 }; impair = null }
        while (i + 1 < n) { ecrit(out, (pcm[i] + pcm[i + 1]) / 2); i += 2 }
        if (i < n) impair = pcm[i].toInt()
        val b = out.toByteArray()
        raf.seek(44 + donnees); raf.write(b)
        donnees += b.size
        depuisEntete += b.size
        if (depuisEntete >= rate * 2L) {
            depuisEntete = 0; raf.seek(0); raf.write(entete(donnees))
            if (++depuisTemps >= 10) { depuisTemps = 0; ecritTemps(0L) }
        }
    }

    private fun ecrit(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    /** Seconds recorded. */
    val secondes: Long get() = donnees / 2 / rate

    @Synchronized fun ferme() {
        runCatching { raf.seek(0); raf.write(entete(donnees)); raf.close() }
        ecritTemps(horloge())
    }

    private fun entete(n: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + n).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n.toInt())
        return b.array()
    }
}
