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
import kotlin.concurrent.thread

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
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /** Who gets each block: (samples, count, rate). */
    val auditeurs = CopyOnWriteArrayList<(ShortArray, Int, Int) -> Unit>()

    @Volatile private var ligne: TargetDataLine? = null
    @Volatile private var enMarche = false

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
        arrete()
        virtuelles.firstOrNull { it.nom == nom }?.let { v ->
            _etat.update { Etat(actif = false, appareil = nom, erreur = "connexion…") }
            val err = runCatching { v.demarre { pcm, n, rate -> if (virtuelle === v) distribue(pcm, n, rate) } }
                .getOrElse { it.message ?: "erreur" }
            if (err != null) { runCatching { v.arrete() }; _etat.update { Etat(actif = false, appareil = nom, erreur = err) }; return false }
            virtuelle = v
            _etat.update { Etat(actif = true, appareil = nom, rate = fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.SORTIE_HZ) }
            return true
        }
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == nom }
            ?: run { _etat.update { it.copy(erreur = "carte son introuvable : $nom") }; return false }
        val m = AudioSystem.getMixer(info)
        for (f in formats()) {
            val di = DataLine.Info(TargetDataLine::class.java, f)
            if (!m.isLineSupported(di)) continue
            val l = runCatching { (m.getLine(di) as TargetDataLine).also { it.open(f, f.frameSize * 4096); it.start() } }.getOrNull() ?: continue
            ligne = l
            enMarche = true
            val rate = f.sampleRate.toInt()
            _etat.update { Etat(actif = true, appareil = nom, rate = rate) }
            thread(isDaemon = true, name = "satme-son") { boucle(l, rate) }
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
        val info = AudioSystem.getMixerInfo().firstOrNull { it.name == nom } ?: return null to "carte son introuvable : $nom"
        // The decoders' card on the same card: let go first (one line a card on some systems).
        if (_etat.value.appareil == nom && ligne != null) arrete()
        val m = AudioSystem.getMixer(info)
        for (f in formats()) {
            val di = DataLine.Info(TargetDataLine::class.java, f)
            if (!m.isLineSupported(di)) continue
            val l = runCatching { (m.getLine(di) as TargetDataLine).also { it.open(f, f.frameSize * 4096); it.start() } }.getOrNull() ?: continue
            val rate = f.sampleRate.toInt()
            val marche = java.util.concurrent.atomic.AtomicBoolean(true)
            thread(isDaemon = true, name = "satme-son-fi") {
                val octets = ByteArray(3840)
                val pcm = ShortArray(1920)
                while (marche.get()) {
                    val n = runCatching { l.read(octets, 0, octets.size) }.getOrDefault(-1)
                    if (n < 0) break
                    if (n == 0) continue
                    val k = n / 2
                    for (i in 0 until k) pcm[i] = ((octets[2 * i + 1].toInt() shl 8) or (octets[2 * i].toInt() and 0xFF)).toShort()
                    runCatching { recoit(pcm, k, rate) }
                }
            }
            return (rate to { marche.set(false); runCatching { l.stop(); l.close() }; Unit }) to ""
        }
        return null to "$nom n'accepte ni 48 ni 44,1 kHz en 16 bits mono"
    }

    private fun boucle(l: TargetDataLine, rate: Int) {
        val octets = ByteArray(4096)
        val pcm = ShortArray(2048)
        while (enMarche && ligne === l) {
            val n = l.read(octets, 0, octets.size)
            if (n <= 0) continue
            val k = n / 2
            var crete = 0
            for (i in 0 until k) {
                val v = ((octets[2 * i + 1].toInt() shl 8) or (octets[2 * i].toInt() and 0xFF)).toShort()
                pcm[i] = v
                crete = maxOf(crete, kotlin.math.abs(v.toInt()))
            }
            distribue(pcm, k, rate, crete)
        }
    }

    /** One block to every listener (also what the tests feed). */
    fun distribue(pcm: ShortArray, n: Int, rate: Int, crete: Int = pcm.take(n).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0) {
        _etat.update { it.copy(niveau = crete / 32768f) }
        for (a in auditeurs) runCatching { a(pcm, n, rate) }
    }

    /**
     * Another source takes the decoders' input (the SDR dongle): the card is
     * stopped — two threads feeding the same decoders would mix their state —
     * and the state says who feeds them, at what rate, as a card would.
     */
    fun prendPour(nom: String, rate: Int) {
        arrete()
        _etat.update { Etat(actif = true, appareil = nom, rate = rate) }
    }

    /** That source is done: nothing feeds the decoders any more. */
    fun rendPar(nom: String) {
        if (_etat.value.appareil == nom && ligne == null && virtuelle == null) _etat.update { it.copy(actif = false, niveau = 0f) }
    }

    fun arrete() {
        virtuelle?.let { v -> virtuelle = null; runCatching { v.arrete() } }
        enMarche = false
        ligne?.let { runCatching { it.stop(); it.close() } }
        ligne = null
        _etat.update { it.copy(actif = false, niveau = 0f) }
    }
}

/**
 * A recording as a WAV file, 16-bit mono, at half the card's rate (24 or
 * 22.05 kHz: enough for voice, SSTV and telemetry, half the size). The header
 * is rewritten at every second, so a PC switched off mid-pass leaves a file
 * that plays up to that second.
 */
class EnregistreurWav(val fichier: File, rateCarte: Int) {
    val rate = rateCarte / 2
    private val raf = RandomAccessFile(fichier, "rw").apply { setLength(0); write(entete(0)) }
    private var donnees = 0L
    private var impair: Int? = null
    private var depuisEntete = 0L

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
        if (depuisEntete >= rate * 2L) { depuisEntete = 0; raf.seek(0); raf.write(entete(donnees)) }
    }

    private fun ecrit(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }

    /** Seconds recorded. */
    val secondes: Long get() = donnees / 2 / rate

    @Synchronized fun ferme() {
        runCatching { raf.seek(0); raf.write(entete(donnees)); raf.close() }
    }

    private fun entete(n: Long): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + n).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n.toInt())
        return b.array()
    }
}
