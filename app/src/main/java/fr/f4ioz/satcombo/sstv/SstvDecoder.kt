/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The SSTV receiver proper: PCM in, pictures out.
 *
 * It runs continuously and silently until it recognises a VIS header, so it can
 * simply be left hanging off the pass recorder — no button to press at the
 * right moment, which is the whole point when the satellite is only up for ten
 * minutes and the operator has a rotator in one hand.
 *
 * Three states:
 *  - IDLE       hunting for a VIS header, one hypothesis every millisecond
 *  - SYNC_HUNT  header found, looking for the first sync pulse of the image
 *  - IMAGE      decoding block after block, re-aligning on every sync pulse
 *
 * Re-aligning on each sync pulse is what removes slant. The phone's ADC clock
 * and the transmitter's are never quite the same, and a 100 ppm difference over
 * a two-minute Scottie DX frame is a visibly tilted picture; tracking the real
 * measured line period fixes it without asking the operator to drag a slider.
 */
class SstvDecoder(
    private val sampleRate: Int,
    var listener: Listener? = null
) {

    interface Listener {
        /** A header was recognised; the picture is about to start. */
        fun onVis(mode: SstvMode) {}
        /** [linesDone] rows of [pixels] are now valid (ARGB, mode.width wide). */
        fun onProgress(mode: SstvMode, pixels: IntArray, linesDone: Int) {}
        /** Frame over — [complete] is false when the audio ran out mid-picture. */
        fun onImage(mode: SstvMode, pixels: IntArray, linesDone: Int, complete: Boolean) {}
    }

    private enum class State { IDLE, SYNC_HUNT, IMAGE }

    private val spms = sampleRate / 1000.0            // samples per millisecond
    private val demod = Demodulator(sampleRate)

    // ---- instantaneous frequency, linear buffer with an absolute origin ----
    private var freq = FloatArray((1.4 * sampleRate).toInt())
    private var freqLen = 0
    private var freqBase = 0L                          // abs index of freq[0]
    private var total = 0L                             // samples fed so far
    private var scratch = FloatArray(4096)

    // ---- per-millisecond means + prefix sums, used only for the VIS hunt ----
    private val msCap = 2048
    private val msBuf = DoubleArray(msCap)
    private val msSum = DoubleArray(msCap + 1)
    private var msLen = 0
    private var msBase = 0L                            // abs ms index of msBuf[0]
    private var msProduced = 0L                        // ms entries produced ever
    private var nextTest = 0L                          // next VIS hypothesis (abs ms)

    @Volatile private var state = State.IDLE

    // ---- image state ----
    @Volatile private var mode: SstvMode? = null
    private var pixels = IntArray(0)
    private var blockStart = 0.0                       // abs sample index, fractional
    private var blockPeriod = 0.0                      // samples per block, adaptive
    private var nominalPeriod = 0.0
    private var blockIndex = 0
    private var lastSync = -1.0
    private var huntFrom = 0L
    private var huntLimit = 0L
    private var flushing = false

    /** Consecutive missed syncs in the current frame. */
    private var missedRun = 0

    /** True when the frame was started by hand, without a VIS header. */
    private var forcedAnchor = false

    /**
     * Mode forced by the operator, or null to follow the VIS header.
     *
     * On a weak signal a header can pass parity yet name the wrong mode, and
     * the picture comes out scrambled. When the operator knows the mode (ISS
     * events announce it), let them say so.
     */
    @Volatile var forcedMode: SstvMode? = null

    // Robot 36 sends only one chroma component per line — R-Y on the even ones,
    // B-Y on the odd ones — so a line is held back until its partner arrives
    // and the pair is coloured together.
    private var heldY: IntArray? = null
    private var heldC: IntArray? = null
    private var heldIsCr = false
    private var heldRow = -1

    /** Rough audio level of the filtered signal — for a UI tuning indicator. */
    val level: Double get() = demod.level

    /** Name of the mode being decoded, or null when idle. */
    val currentMode: SstvMode? get() = if (state == State.IMAGE) mode else null

    /**
     * Armed mode: the picture in progress, or the one just locked whose first
     * sync is still being searched. This is what to display — up to ~100 ms
     * pass between header and first line.
     */
    val armedMode: SstvMode? get() = if (state == State.IDLE) null else mode

    /** True once a frame is under way, forced or not. */
    val decoding: Boolean get() = state != State.IDLE

    /** 0..1 progress through the current frame. */
    val progress: Float
        get() = mode?.let {
            if (state != State.IMAGE) 0f
            else (blockIndex.toFloat() / it.blocks).coerceIn(0f, 1f)
        } ?: 0f

    fun reset() {
        demod.reset()
        freqLen = 0; freqBase = 0L; total = 0L
        msLen = 0; msBase = 0L; msProduced = 0L; nextTest = 0L
        msSum[0] = 0.0
        state = State.IDLE
        mode = null; pixels = IntArray(0)
        heldY = null; heldC = null; heldRow = -1
        lastSync = -1.0; blockIndex = 0; flushing = false
        missedRun = 0; forcedAnchor = false
    }

    /** Push audio in. Chunks of a few thousand samples are ideal. */
    @Synchronized
    fun feed(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        if (scratch.size < count) scratch = FloatArray(count)
        demod.process(pcm, count, scratch)
        append(scratch, count)
        produceMs()
        when (state) {
            State.IDLE -> huntVis()
            State.SYNC_HUNT -> huntFirstSync()
            // Keep hunting for headers during the image. Otherwise a frame the
            // sender abandoned holds the decoder to its last line (two minutes
            // in PD 180) and the next transmission is missed. A new header wins;
            // the current picture is emitted as is.
            State.IMAGE -> if (!huntVis()) decodeBlocks()
        }
        compact()
    }

    /** No more audio: emit whatever picture was in progress. */
    @Synchronized
    fun finish() {
        // The last block of a frame has no audio after it, so the normal
        // look-ahead margin would leave it undecoded. Drain it first.
        if (state == State.IMAGE) { flushing = true; decodeBlocks() }
        if (state != State.IDLE) emitPartial()
        state = State.IDLE
        mode = null
        flushing = false
        forcedAnchor = false
    }

    /**
     * Emits the current picture, complete or not, and forgets the frame. Half
     * an ISS picture beats nothing; the Hub decides whether it has enough
     * lines to archive.
     */
    private fun emitPartial() {
        val m = mode ?: return
        // A Robot 36 line waiting for its chroma partner still gets drawn.
        val hy = heldY; val hc = heldC
        if (hy != null && hc != null) writeYuv(
            m, heldRow, hy, if (heldIsCr) hc else null, if (heldIsCr) null else hc)
        heldY = null; heldC = null; heldRow = -1
        if (blockIndex > 0) listener?.onImage(
            m, pixels, (blockIndex * m.linesPerBlock).coerceAtMost(m.height), false)
    }

    /**
     * Starts decoding without a header — for a signal joined mid-picture or a
     * header lost in a fade. Locks on the first sync pulse (sent every line)
     * and runs the given mode.
     */
    @Synchronized
    fun forceStart(m: SstvMode) {
        if (state != State.IDLE) emitPartial()
        prepare(m)
        forcedAnchor = true
        huntFrom = total
        huntLimit = total + ((m.blockMs + 40.0) * spms).toLong()
        state = State.SYNC_HUNT
        listener?.onVis(m)
    }

    /** Drops the current frame and goes back to listening. */
    @Synchronized
    fun abort() {
        if (state == State.IDLE) return
        emitPartial()
        state = State.IDLE
        mode = null
        forcedAnchor = false
    }

    // ---------------------------------------------------------------- buffers

    private fun append(src: FloatArray, count: Int) {
        if (freqLen + count > freq.size) {
            // Drop the oldest samples we are certain nobody will ask for again;
            // if that is not enough, grow rather than throw away live data.
            val keep = keepFrom()
            var drop = (keep - freqBase).toInt().coerceAtLeast(0)
            if (drop > freqLen) drop = freqLen
            if (drop > 0) {
                System.arraycopy(freq, drop, freq, 0, freqLen - drop)
                freqLen -= drop
                freqBase += drop
            }
            if (freqLen + count > freq.size) {
                freq = freq.copyOf(maxOf(freq.size * 2, freqLen + count))
            }
        }
        System.arraycopy(src, 0, freq, freqLen, count)
        freqLen += count
        total += count
    }

    private fun keepFrom(): Long {
        val msNeed = (msProduced * spms).toLong()
        // Keep an extra half block: the widened sync search that recovers a
        // settled line offset looks that far back.
        val imgNeed = if (state == State.IMAGE || state == State.SYNC_HUNT)
            (blockStart - 0.55 * blockPeriod - 40 * spms).toLong() else msNeed
        return minOf(msNeed, imgNeed).coerceAtLeast(0L)
    }

    private fun compact() {
        val keep = keepFrom()
        val drop = (keep - freqBase).toInt()
        if (drop > 4096 && drop <= freqLen) {
            System.arraycopy(freq, drop, freq, 0, freqLen - drop)
            freqLen -= drop
            freqBase += drop
        }
    }

    private fun freqAt(abs: Long): Float {
        val i = (abs - freqBase).toInt()
        if (i < 0 || i >= freqLen) return SstvTone.CARRIER.toFloat()
        return freq[i]
    }

    /** Mean instantaneous frequency over the absolute sample span [a, b). */
    private fun meanFreq(a: Double, b: Double): Double {
        var i0 = a.roundToLong()
        var i1 = b.roundToLong()
        if (i1 <= i0) i1 = i0 + 1
        if (i0 < freqBase) i0 = freqBase
        if (i1 > freqBase + freqLen) i1 = freqBase + freqLen
        if (i1 <= i0) return SstvTone.CARRIER
        var s = 0.0
        for (i in i0 until i1) s += freq[(i - freqBase).toInt()]
        return s / (i1 - i0)
    }

    private fun produceMs() {
        while ((msProduced + 1) * spms <= total.toDouble()) {
            val a = (msProduced * spms)
            val b = ((msProduced + 1) * spms)
            val v = meanFreq(a, b)
            if (msLen >= msCap) {
                val drop = msCap / 2
                System.arraycopy(msBuf, drop, msBuf, 0, msLen - drop)
                msLen -= drop
                msBase += drop
                msSum[0] = 0.0
                for (k in 0 until msLen) msSum[k + 1] = msSum[k] + msBuf[k]
            }
            msBuf[msLen] = v
            msSum[msLen + 1] = msSum[msLen] + v
            msLen++
            msProduced++
        }
        if (nextTest < msBase) nextTest = msBase
    }

    /** Mean over the absolute millisecond span [a, b). */
    private fun msMean(a: Long, b: Long): Double {
        val i = (a - msBase).toInt()
        val j = (b - msBase).toInt()
        if (i < 0 || j > msLen || j <= i) return SstvTone.CARRIER
        return (msSum[j] - msSum[i]) / (j - i)
    }

    // ------------------------------------------------------------- VIS header

    /**
     * Header layout, all of it a multiple of a millisecond:
     * 300 ms 1900 · 10 ms 1200 · 300 ms 1900 · 30 ms 1200 start ·
     * 8 x 30 ms data (1100 = 1, 1300 = 0, LSB first, bit 7 = even parity) ·
     * 30 ms 1200 stop.
     */
    private fun huntVis(): Boolean {
        val end = msBase + msLen
        while (nextTest + VIS_MS <= end) {
            val s = nextTest
            nextTest++
            // Leaders, inset a little so a millisecond of misalignment at the
            // edges cannot drag the mean.
            if (abs(msMean(s + 20, s + 290) - 1900.0) > 70.0) continue
            if (abs(msMean(s + 330, s + 600) - 1900.0) > 70.0) continue
            // Break: just has to be clearly not the leader.
            if (msMean(s + 301, s + 309) > 1600.0) continue
            if (abs(msMean(s + 611, s + 639) - 1200.0) > 110.0) continue
            if (abs(msMean(s + 881, s + 909) - 1200.0) > 110.0) continue

            var vis = 0
            var ones = 0
            var ok = true
            for (b in 0 until 8) {
                val f = msMean(s + 641 + 30L * b, s + 669 + 30L * b)
                if (abs(f - 1200.0) < 40.0) { ok = false; break }
                val one = f < 1200.0
                if (one) {
                    ones++
                    if (b < 7) vis = vis or (1 shl b)
                }
            }
            if (!ok) continue
            if (ones % 2 != 0) continue                      // even parity
            // A forced mode overrides the header, which then only marks the start.
            val m = forcedMode ?: SstvMode.byVis(vis) ?: continue

            // A forced decode is never interrupted by a header: on noise the
            // detector fires falsely and used to restart the frame, losing the
            // picture. The forced frame runs to its end first.
            if (forcedAnchor && state != State.IDLE) return false

            if (state != State.IDLE) emitPartial()
            // Do not re-read the header just consumed.
            nextTest = s + VIS_MS + 60
            startImage(m, ((s + VIS_MS) * spms).toLong())
            return true
        }
        return false
    }

    /** Resets all per-frame state. */
    private fun prepare(m: SstvMode) {
        mode = m
        if (pixels.size != m.width * m.height) pixels = IntArray(m.width * m.height)
        java.util.Arrays.fill(pixels, 0xFF101418.toInt())
        nominalPeriod = m.blockMs * spms
        blockPeriod = nominalPeriod
        blockIndex = 0
        lastSync = -1.0
        missedRun = 0
        heldY = null; heldC = null; heldRow = -1
        ensureCapacity(m)
    }

    private fun startImage(m: SstvMode, visEndAbs: Long) {
        prepare(m)
        forcedAnchor = false
        blockStart = visEndAbs.toDouble()
        // The VIS stop bit is itself 30 ms of 1200 Hz and runs straight into the
        // first sync pulse, so the hunt starts inside it and works from the
        // *trailing* edge of the merged run — see [huntFirstSync].
        huntFrom = visEndAbs - (25 * spms).toLong()
        huntLimit = visEndAbs + (90 * spms).toLong()
        state = State.SYNC_HUNT
        listener?.onVis(m)
        huntFirstSync()
    }

    // --------------------------------------------------------- sync alignment

    /**
     * Longest run of samples sitting on the sync tone inside [from, to).
     * Returns the absolute index where it starts, or -1.
     */
    private fun findSync(from: Long, to: Long, minSamples: Int): Long {
        var lo = from; var hi = to
        if (lo < freqBase) lo = freqBase
        if (hi > freqBase + freqLen) hi = freqBase + freqLen
        if (hi - lo < minSamples) return -1
        var bestStart = -1L; var bestLen = 0
        var runStart = -1L; var run = 0
        for (i in lo until hi) {
            if (freq[(i - freqBase).toInt()] < SYNC_MAX) {
                if (run == 0) runStart = i
                run++
                if (run > bestLen) { bestLen = run; bestStart = runStart }
            } else run = 0
        }
        lastRunEnd = if (bestLen >= minSamples) bestStart + bestLen else -1L
        return if (bestLen >= minSamples) bestStart else -1
    }

    /** End of the run [findSync] last returned, or -1. */
    private var lastRunEnd = -1L

    /**
     * Finds where block 0 begins.
     *
     * The stop bit of the VIS, the leading sync pulse and — for Scottie — the
     * lone 9 ms start sync are all 1200 Hz back to back, so the *start* of that
     * run says nothing. Its trailing edge does: it is the end of the sync pulse
     * that opens the picture (or, for Scottie, the end of the start sync, which
     * is exactly where its first block begins).
     */
    private fun huntFirstSync() {
        val m = mode ?: return
        val minRun = (m.syncMs * 0.55 * spms).toInt().coerceAtLeast(2)
        if (total < huntLimit + (2 * spms).toLong()) return      // wait for audio
        val hit = findSync(huntFrom, huntLimit, minRun)
        if (forcedAnchor) {
            // Manual start: the only anchor is a sync found mid-transmission.
            // It locates its own block — for Scottie, sync is mid-block, so the
            // block began well before. If that start is no longer buffered,
            // move to the next block rather than decode nothing.
            blockStart = if (hit >= 0) lastRunEnd - (m.syncMs + m.syncAtMs) * spms
                         else huntLimit.toDouble()
            while (blockStart < (freqBase + 8).toDouble()) blockStart += blockPeriod
        } else {
            val visEnd = huntFrom + 25 * spms
            blockStart = if (hit >= 0)
                lastRunEnd - m.syncMs * spms + m.firstBlockOffsetMs * spms
            else visEnd + m.firstBlockOffsetMs * spms
        }
        lastSync = -1.0
        state = State.IMAGE
        decodeBlocks()
    }

    private fun ensureCapacity(m: SstvMode) {
        val want = ((3.0 * m.blockMs + 200.0) * spms).toInt()
        if (freq.size < want) {
            val grown = FloatArray(want)
            System.arraycopy(freq, 0, grown, 0, freqLen)
            freq = grown
        }
    }

    // ------------------------------------------------------------- the picture

    private fun decodeBlocks() {
        val m = mode ?: return
        // While the audio keeps coming, wait for a little slack past the block
        // so its sync pulse can be searched for. On the final drain, accept a
        // block that is a couple of milliseconds short rather than lose it.
        val margin = if (flushing) -2.0 * spms else 12 * spms
        val missLimit = missLimitFor(m)
        while (blockIndex < m.blocks &&
               total.toDouble() >= blockStart + blockPeriod + margin) {

            // Re-align on this block's sync pulse before reading any pixel.
            // Block 0 is skipped: [huntFirstSync] has already placed it on the
            // trailing edge of the header, and a search window opened around it
            // would run straight back into the VIS stop bit — 1200 Hz as well —
            // and drag the whole frame several milliseconds early.
            val expected = blockStart + m.syncAtMs * spms
            // While locked, ±10 ms avoids mistaking something else for sync.
            // After a few misses in a row the line is unlocked: a constant
            // offset that ±10 ms can't recover, seen as a colour band on one
            // edge. Then search half a block and apply the full correction to
            // relock in one step.
            val wide = missedRun >= WIDE_AFTER
            val win = if (wide) blockPeriod * 0.45 else 10.0 * spms
            val minRun = (m.syncMs * 0.55 * spms).toInt().coerceAtLeast(2)
            val hit = if (blockIndex == 0) -1L
                      else findSync((expected - win).toLong(), (expected + win).toLong(), minRun)
            if (blockIndex == 0) lastSync = expected
            if (hit >= 0) {
                val err = hit - expected
                // Track the period only while locked: a relock jump is not clock drift.
                if (!wide && lastSync > 0) {
                    val measured = hit - lastSync
                    if (measured > nominalPeriod * 0.97 && measured < nominalPeriod * 1.03) {
                        blockPeriod += (measured - blockPeriod) * 0.08
                        blockPeriod = blockPeriod.coerceIn(
                            nominalPeriod * 0.985, nominalPeriod * 1.015)
                    }
                }
                lastSync = hit.toDouble()
                blockStart += if (wide) err.toDouble() else err * 0.6
                missedRun = 0
            } else if (blockIndex > 0) missedRun++

            decodeOneBlock(m)
            blockIndex++
            blockStart += blockPeriod

            val done = (blockIndex * m.linesPerBlock).coerceAtMost(m.height)
            listener?.onProgress(m, pixels, done)

            // No sync for several seconds: the transmission stopped. Rolling
            // noise lines to the end would miss the next transmission.
            //
            // **Except on a forced start.** The operator chose to force (weak
            // signal, late start, sync fading) and gets no second chance on a
            // pass; a forced decode runs to the end, noise and all.
            if (missedRun >= missLimit && !forcedAnchor) {
                emitPartial()
                state = State.IDLE
                mode = null
                forcedAnchor = false
                return
            }
        }
        if (blockIndex >= m.blocks) {
            listener?.onImage(m, pixels, m.height, true)
            state = State.IDLE
            mode = null
            forcedAnchor = false
        }
    }

    /**
     * Consecutive misses that end the frame: about [ABORT_MS], never fewer
     * than eight lines so a short fade does not abort.
     */
    private fun missLimitFor(m: SstvMode): Int =
        maxOf(8, (ABORT_MS / m.blockMs).toInt())

    private fun decodeOneBlock(m: SstvMode) {
        val row0 = blockIndex * m.linesPerBlock
        if (row0 >= m.height) return

        var y1: IntArray? = null
        var y2: IntArray? = null
        var cr: IntArray? = null
        var cb: IntArray? = null
        var r: IntArray? = null
        var g: IntArray? = null
        var b: IntArray? = null

        for (seg in m.segments) {
            val role = if (seg.role == Role.C_ALT) robot36Chroma(m, seg) else seg.role
            val line = readSegment(m, seg)
            when (role) {
                Role.RED -> r = line
                Role.GREEN -> g = line
                Role.BLUE -> b = line
                Role.Y1 -> y1 = line
                Role.Y2 -> y2 = line
                Role.CR -> cr = line
                Role.CB -> cb = line
                Role.C_ALT -> {}
            }
        }

        when (m.family) {
            Family.RGB -> {
                val rr = r ?: return; val gg = g ?: return; val bb = b ?: return
                writeRgb(m, row0, rr, gg, bb)
            }
            Family.YUV -> {
                val yy = y1 ?: return
                writeYuv(m, row0, yy, cr, cb)
            }
            Family.PD -> {
                val ya = y1 ?: return
                writeYuv(m, row0, ya, cr, cb)
                val yb = y2
                if (yb != null && row0 + 1 < m.height) writeYuv(m, row0 + 1, yb, cr, cb)
            }
            Family.ROBOT36 -> {
                val yy = y1 ?: return
                val cc = cr ?: cb ?: return
                val isCr = cr != null
                val hy = heldY; val hc = heldC
                if (hy != null && hc != null && heldIsCr != isCr) {
                    // Pair complete: both lines get both components.
                    val rr = if (heldIsCr) hc else cc
                    val bb = if (heldIsCr) cc else hc
                    writeYuv(m, heldRow, hy, rr, bb)
                    writeYuv(m, row0, yy, rr, bb)
                    heldY = null; heldC = null; heldRow = -1
                } else {
                    // Two of the same component running: the odd one out is
                    // drawn with what we have rather than dropped.
                    if (hy != null && hc != null) writeYuv(
                        m, heldRow, hy,
                        if (heldIsCr) hc else null, if (heldIsCr) null else hc)
                    heldY = yy; heldC = cc; heldIsCr = isCr; heldRow = row0
                }
            }
        }
    }

    /** Robot 36 announces the chroma of the line with the separator tone. */
    private fun robot36Chroma(m: SstvMode, seg: Segment): Role {
        // The separator sits 6 ms before the chroma scan (4.5 ms separator plus
        // a 1.5 ms porch): 1500 Hz means R-Y, 2300 Hz means B-Y.
        val sepEnd = blockStart + (seg.startMs - 1.5) * spms
        val f = meanFreq(sepEnd - 3.5 * spms, sepEnd - 0.5 * spms)
        return if (f > 1900.0) Role.CB else Role.CR
    }

    /** Samples one channel of the block into [SstvMode.width] levels. */
    private fun readSegment(m: SstvMode, seg: Segment): IntArray {
        val out = IntArray(m.width)
        val start = blockStart + seg.startMs * spms
        val step = seg.durMs * spms / m.width
        for (x in 0 until m.width) {
            val a = start + x * step
            out[x] = SstvTone.level(meanFreq(a, a + step))
        }
        return out
    }

    private fun writeRgb(m: SstvMode, row: Int, r: IntArray, g: IntArray, b: IntArray) {
        if (row >= m.height) return
        val off = row * m.width
        for (x in 0 until m.width) {
            pixels[off + x] = (0xFF shl 24) or (r[x] shl 16) or (g[x] shl 8) or b[x]
        }
    }

    private fun writeYuv(m: SstvMode, row: Int, y: IntArray, cr: IntArray?, cb: IntArray?) {
        if (row >= m.height || row < 0) return
        val off = row * m.width
        for (x in 0 until m.width) {
            val yy = y[x].toDouble()
            val v = (cr?.get(x) ?: 128) - 128.0
            val u = (cb?.get(x) ?: 128) - 128.0
            val rr = clamp8(yy + 1.402 * v)
            val gg = clamp8(yy - 0.344136 * u - 0.714136 * v)
            val bb = clamp8(yy + 1.772 * u)
            pixels[off + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun clamp8(v: Double): Int {
        val i = v.roundToInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    companion object {
        /** Total header length in milliseconds. */
        const val VIS_MS = 910L
        /** Everything below this is treated as the sync tone. */
        private const val SYNC_MAX = 1350f
        /** Missed syncs before widening the search. */
        private const val WIDE_AFTER = 3
        /** Tolerated sync loss, ms, before abandoning the frame. */
        private const val ABORT_MS = 8000.0
    }
}
