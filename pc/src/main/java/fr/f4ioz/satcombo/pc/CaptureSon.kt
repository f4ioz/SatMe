/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.audio.SonPerdu
import fr.f4ioz.satcombo.audio.TrousCapture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/** A sound line read block by block: the card's, or a made-up one in the tests. */
interface LigneCapture {
    /** Blocks until [len] bytes came (16-bit little-endian mono); -1 once closed. */
    fun lit(b: ByteArray, off: Int, len: Int): Int
    /** Bytes waiting in its buffer, -1 unknown. */
    fun enAttente(): Int
    /** Its buffer's size (bytes), -1 unknown. */
    val tampon: Int
    fun ferme()
}

/** The card through Java Sound. */
class LigneJava(private val l: TargetDataLine) : LigneCapture {
    override fun lit(b: ByteArray, off: Int, len: Int): Int = if (!l.isOpen) -1 else l.read(b, off, len)
    override fun enAttente(): Int = runCatching { l.available() }.getOrDefault(-1)
    override val tampon: Int get() = runCatching { l.bufferSize }.getOrDefault(-1)
    override fun ferme() { runCatching { l.stop(); l.close() } }
}

/**
 * The capture of a sound line in two threads. The first only reads the line
 * (at the highest priority, nothing else on it), measures what it lost
 * ([TrousCapture]) and hands each block on through a queue deep enough for
 * [fileMs] of sound; the second hands the blocks to the decoders, the
 * recorder, the level meter — the slow ones can no longer make the card's
 * buffer overrun.
 *
 * What was lost — the card's buffer overrun, or blocks dropped because the
 * decoders fell [fileMs] behind — is handed on as that many samples of
 * silence before the block that follows it (up to [TrousCapture.MAX_MS]):
 * what comes after keeps its time (an SSTV picture shows a blank band, not a
 * tear; FT8 keeps its slots). Never a sample handed twice: only zeros fill.
 * A longer loss is a cut, told by [trou] (the recorder fills it, the
 * decoders do not).
 */
class CaptureSon(
    private val ligne: LigneCapture,
    val rate: Int,
    /** A block: samples, count, rate, true for silence put in a hole. */
    private val recoit: (ShortArray, Int, Int, Boolean) -> Unit,
    /** Each hole, once, right before its silence: its samples, filled (true) or a cut too long to fill for the decoders (false). */
    private val trou: (Int, Boolean) -> Unit = { _, _ -> },
    /** Each loss, as soon as it is seen (from the reading thread). */
    private val perte: (SonPerdu.Perte) -> Unit = {},
    private val nanos: () -> Long = System::nanoTime,
    private val horloge: () -> Long = System::currentTimeMillis,
    fileMs: Int = FILE_MS,
    private val blocOctets: Int = BLOC_OCTETS,
) {
    class Bloc(val pcm: ShortArray, val n: Int, val manquants: Int, val coupureMs: Long)

    val trous = TrousCapture(rate)
    private val file = ArrayBlockingQueue<Bloc>((fileMs.toLong() * rate / 1000 / (blocOctets / 2)).toInt().coerceAtLeast(4))
    private val octets = ByteArray(blocOctets)
    /** Samples dropped (and silence owed) since the last block queued: the reading thread's own. */
    private var perdusFile = 0L
    private var perdusFileDepuis = 0L

    @Volatile var marche = true; private set
    /** Blocks read; readings with the card's buffer nearly full (its overrun close). */
    @Volatile var blocs = 0L; private set
    @Volatile var tamponPlein = 0L; private set
    /** Samples of silence handed out; blocks dropped in the queue. */
    @Volatile var combles = 0L; private set
    @Volatile var blocsJetes = 0L; private set
    /** The queue's fullest (blocks) and its depth. */
    @Volatile var fileMax = 0; private set
    val fileCapacite: Int get() = file.remainingCapacity() + file.size

    private var lecteur: Thread? = null
    private var distributeur: Thread? = null

    fun demarre(nom: String = "satme-son"): CaptureSon {
        lecteur = thread(isDaemon = true, name = nom, priority = Thread.MAX_PRIORITY) {
            try { while (marche && pasLecture()) Unit } catch (_: Throwable) {}
            marche = false
        }
        distributeur = thread(isDaemon = true, name = "$nom-decodeurs") {
            // Stopped: what waits is dropped, never handed out beside the next source's blocks.
            try { while (marche) pasDistribution(200) } catch (_: InterruptedException) {}
        }
        return this
    }

    fun arrete() {
        marche = false
        ligne.ferme()
        distributeur?.interrupt()
        file.clear()
    }

    /** One read of the line, measured and queued; false once the line is closed. */
    fun pasLecture(): Boolean {
        val n = ligne.lit(octets, 0, octets.size)
        if (n < 0) return false
        if (n < 2) return true
        val t = nanos()
        val attente = ligne.enAttente()
        val k = n / 2
        blocs++
        val tampon = ligne.tampon
        if (attente >= 0 && tampon > 0 && attente >= tampon * 9 / 10) tamponPlein++
        val v = trous.bloc(k, t, if (attente < 0) -1 else attente / 2)
        if (!v.rien) {
            val ms = if (v.manquants > 0) v.manquants * 1000L / rate else v.coupureMs
            runCatching { perte(SonPerdu.Perte(horloge(), ms, SonPerdu.Cause.CARTE, v.manquants > 0)) }
        }
        val pcm = ShortArray(k)
        for (i in 0 until k) pcm[i] = ((octets[2 * i + 1].toInt() shl 8) or (octets[2 * i].toInt() and 0xFF)).toShort()
        // What the queue dropped before, and what the card lost now: one hole before this block.
        val du = perdusFile + v.manquants
        val max = (TrousCapture.MAX_MS * rate / 1000).toLong()
        val b = if (du <= max) Bloc(pcm, k, du.toInt(), v.coupureMs) else Bloc(pcm, k, 0, v.coupureMs + du * 1000L / rate)
        if (file.offer(b)) {
            if (perdusFile > 0) {
                runCatching { perte(SonPerdu.Perte(perdusFileDepuis, perdusFile * 1000L / rate, SonPerdu.Cause.FILE, perdusFile <= max)) }
                perdusFile = 0
            }
            fileMax = maxOf(fileMax, file.size)
        } else {
            // The decoders this far behind: the block dropped, counted, its time owed as silence.
            if (perdusFile == 0L) perdusFileDepuis = horloge()
            perdusFile = du + k + v.coupureMs * rate / 1000L
            blocsJetes++
            fileMax = fileCapacite
        }
        return true
    }

    /** One block handed out (its hole's silence first); waits up to [attenteMs] for one. */
    fun pasDistribution(attenteMs: Long) {
        val b = file.poll(attenteMs, TimeUnit.MILLISECONDS) ?: return
        if (b.manquants > 0) runCatching { trou(b.manquants, true) }
        var reste = b.manquants
        while (reste > 0) {
            val m = minOf(reste, blocOctets / 2)
            runCatching { recoit(ShortArray(m), m, rate, true) }
            combles += m
            reste -= m
        }
        if (b.coupureMs > 0) runCatching { trou((b.coupureMs * rate / 1000L).toInt(), false) }
        runCatching { recoit(b.pcm, b.n, rate, false) }
    }

    /** Blocks waiting to be handed out. */
    val enFile: Int get() = file.size

    companion object {
        /** 42 ms at 48 kHz: the size of each read. */
        const val BLOC_OCTETS = 4096
        /** The queue between the capture and the decoders: this much sound (ms). */
        const val FILE_MS = 10_000
        /** The card's own buffer (ms): what the capture thread may be held back without loss. */
        const val TAMPON_MS = 1_000
    }
}
