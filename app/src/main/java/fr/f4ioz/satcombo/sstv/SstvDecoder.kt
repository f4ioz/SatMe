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
import kotlin.math.sqrt
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

    // The narrow reading (see [Demodulator]), stored aligned with [freq]: its
    // filter is longer, so each value is written [lagN] samples earlier.
    private var freqN = FloatArray(freq.size)
    private var scratchN = FloatArray(4096)
    private val lagN = demod.halfLengthNarrow - demod.halfLength

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

    /**
     * Tuning error removed from every frequency, Hz. Measured on the VIS leader
     * (1900 Hz nominal): on SSB a mistuned receiver shifts every tone, and
     * beyond ±70 Hz the header used to be refused outright.
     */
    private var correction = 0f

    /** Frequency spread inside recent sync pulses, Hz: the noise, measured. -1 until known. */
    private var syncSpread = -1.0

    // Recent accepted sync pulses (block index, position), for [fitLine].
    private val histK = DoubleArray(HIST)
    private val histP = DoubleArray(HIST)
    private var histLen = 0
    private var histNext = 0

    private fun remember(k: Int, pos: Double) {
        histK[histNext] = k.toDouble(); histP[histNext] = pos
        histNext = (histNext + 1) % HIST
        if (histLen < HIST) histLen++
    }

    /**
     * Least-squares line through the remembered pulses: position of block 0
     * and block period, or null with too few points or a slope that is no
     * line length at all.
     */
    private fun fitLine(): Pair<Double, Double>? {
        if (histLen < 4) return null
        var sk = 0.0; var sp = 0.0
        for (i in 0 until histLen) { sk += histK[i]; sp += histP[i] }
        val mk = sk / histLen; val mp = sp / histLen
        var skk = 0.0; var skp = 0.0
        for (i in 0 until histLen) {
            val dk = histK[i] - mk
            skk += dk * dk; skp += dk * (histP[i] - mp)
        }
        if (skk <= 0.0) return null
        val b = skp / skk
        if (b < nominalPeriod * 0.97 || b > nominalPeriod * 1.03) return null
        return (mp - b * mk) to b
    }

    /** Usual sync position error while locked, samples: RMS, for step detection. */
    private var residual = 0.0

    /** Sync error of the last pulse held as a possible step, samples, or NaN. */
    private var pendingStep = Double.NaN

    /** Prefix counts reused by [findSync]. */
    private var votes = IntArray(0)

    /** Half-width, in samples, of the majority vote that recognises sync. */
    private val voteHalf = (1.0 * sampleRate / 1000.0).toInt().coerceAtLeast(2)

    /**
     * Samples at each end of a scan left out of the pixel average: the
     * demodulator's filter blends the neighbouring tone into them. On the fast
     * chroma scans that painted the right edge green (Robot 36) or blue (PD).
     */
    private val edgeGuard = demod.halfLength * 0.6

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

    /**
     * Continuous decoding: while idle, a regular train of sync
     * pulses starts a picture on its own, header or not — a header lost in a
     * fade, or a pass joined late. Off, a headerless picture waits for the
     * operator's Start.
     */
    @Volatile var continuous: Boolean = false

    // Sync pulses seen while idle (trailing edge, length), for [huntTrain].
    private val pulseEnd = LongArray(PULSES)
    private val pulseLen = IntArray(PULSES)
    private var pulseCount = 0
    private var pulseScan = 0L
    private var wasIdle = true

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
        correction = 0f
        pulseCount = 0; pulseScan = 0L; wasIdle = true
    }

    /** Push audio in. Chunks of a few thousand samples are ideal. */
    @Synchronized
    fun feed(pcm: ShortArray, count: Int) {
        if (count <= 0) return
        if (scratch.size < count) { scratch = FloatArray(count); scratchN = FloatArray(count) }
        demod.process(pcm, count, scratch, scratchN)
        append(scratch, scratchN, count)
        produceMs()
        // Back to idle: pulses of the picture just decoded must not start it
        // again.
        if (state == State.IDLE && !wasIdle) { pulseScan = total; pulseCount = 0 }
        wasIdle = state == State.IDLE
        when (state) {
            State.IDLE -> if (!huntVis()) huntTrain()
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

    private fun append(src: FloatArray, srcN: FloatArray, count: Int) {
        if (freqLen + count > freq.size) {
            // Drop the oldest samples we are certain nobody will ask for again;
            // if that is not enough, grow rather than throw away live data.
            val keep = keepFrom()
            var drop = (keep - freqBase).toInt().coerceAtLeast(0)
            if (drop > freqLen) drop = freqLen
            if (drop > 0) {
                System.arraycopy(freq, drop, freq, 0, freqLen - drop)
                System.arraycopy(freqN, drop, freqN, 0, freqLen - drop)
                freqLen -= drop
                freqBase += drop
            }
            if (freqLen + count > freq.size) {
                freq = freq.copyOf(maxOf(freq.size * 2, freqLen + count))
                freqN = freqN.copyOf(freq.size)
            }
        }
        if (correction == 0f) System.arraycopy(src, 0, freq, freqLen, count)
        else for (k in 0 until count) freq[freqLen + k] = src[k] - correction
        for (k in 0 until count) {
            val j = freqLen + k - lagN
            if (j >= 0) freqN[j] = srcN[k] - correction
        }
        // The last [lagN] narrow values are not out of the filter yet: hold
        // the newest one until they are.
        val last = srcN[count - 1] - correction
        for (j in maxOf(0, freqLen + count - lagN) until freqLen + count) freqN[j] = last
        freqLen += count
        total += count
    }

    private fun keepFrom(): Long {
        val msNeed = (msProduced * spms).toLong()
        // Keep an extra half block: the widened sync search that recovers a
        // settled line offset looks that far back.
        val imgNeed = if (state == State.IMAGE || state == State.SYNC_HUNT)
            (blockStart - 0.55 * blockPeriod - 40 * spms).toLong() else msNeed
        val pulseNeed = if (continuous && state == State.IDLE)
            pulseScan - voteHalf - (40 * spms).toLong() else msNeed
        return minOf(msNeed, imgNeed, pulseNeed).coerceAtLeast(0L)
    }

    private fun compact() {
        val keep = keepFrom()
        val drop = (keep - freqBase).toInt()
        if (drop > 4096 && drop <= freqLen) {
            System.arraycopy(freq, drop, freq, 0, freqLen - drop)
            System.arraycopy(freqN, drop, freqN, 0, freqLen - drop)
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
    private fun meanFreq(a: Double, b: Double, buf: FloatArray = freq): Double {
        var i0 = a.roundToLong()
        var i1 = b.roundToLong()
        if (i1 <= i0) i1 = i0 + 1
        if (i0 < freqBase) i0 = freqBase
        if (i1 > freqBase + freqLen) i1 = freqBase + freqLen
        if (i1 <= i0) return SstvTone.CARRIER
        var s = 0.0
        for (i in i0 until i1) s += buf[(i - freqBase).toInt()]
        return s / (i1 - i0)
    }

    private fun produceMs() {
        while ((msProduced + 1) * spms <= total.toDouble()) {
            val a = (msProduced * spms)
            val b = ((msProduced + 1) * spms)
            val v = meanFreq(a, b, freqN)
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
            // Both leaders must agree with each other; their common offset from
            // 1900 Hz is the tuning error, and the rest of the header is read
            // relative to it.
            val lead1 = msMean(s + 20, s + 290)
            val lead2 = msMean(s + 330, s + 600)
            if (abs(lead1 - lead2) > 40.0) continue
            val off = (lead1 + lead2) / 2 - 1900.0
            if (abs(off) > MAX_OFFSET) continue
            // Break: just has to be clearly not the leader.
            if (msMean(s + 301, s + 309) - off > 1600.0) continue
            if (abs(msMean(s + 611, s + 639) - off - 1200.0) > 110.0) continue
            if (abs(msMean(s + 881, s + 909) - off - 1200.0) > 110.0) continue

            var vis = 0
            var ones = 0
            var ok = true
            for (b in 0 until 8) {
                val f = msMean(s + 641 + 30L * b, s + 669 + 30L * b) - off
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
            retune(off, ((s + VIS_MS - 60) * spms).toLong())
            startImage(m, ((s + VIS_MS) * spms).toLong())
            return true
        }
        return false
    }

    /**
     * Adds [delta] Hz to the tuning correction, and applies it to what is
     * already buffered from [fromAbs] on — the first sync is searched there.
     */
    private fun retune(delta: Double, fromAbs: Long) {
        if (delta == 0.0) return
        val d = delta.toFloat()
        correction = (correction + d).coerceIn(-MAX_OFFSET.toFloat(), MAX_OFFSET.toFloat())
        val i0 = (fromAbs - freqBase).toInt().coerceAtLeast(0)
        for (i in i0 until freqLen) { freq[i] -= d; freqN[i] -= d }
    }

    /**
     * Keeps the tuning correction right during the frame.
     *
     * The header measures the error once; uncorrected Doppler on SSB then
     * slides every tone during a two-minute picture, and the bottom of a PD
     * 120 came out 100 levels off. Every sync pulse is a 1200 Hz reference:
     * its centre (away from the edges the filter softens) is read, and a
     * tenth of the error applied: a drift is slow, and on a noisy signal a
     * quarter made the colours shimmer from line to line.
     */
    private fun followTuning(m: SstvMode, start: Long, end: Long) {
        val len = end - start
        if (len < m.syncMs * 0.7 * spms) return
        val a = start + len * 0.25; val b = end - len * 0.25
        var i0 = a.roundToLong(); val i1 = minOf(b.roundToLong(), freqBase + freqLen)
        if (i0 < freqBase) i0 = freqBase
        if (i1 - i0 < 4) return
        var s = 0.0; var s2 = 0.0
        for (i in i0 until i1) {
            val f = freqN[(i - freqBase).toInt()].coerceIn(900f, 1500f).toDouble()
            s += f; s2 += f * f
        }
        val n = (i1 - i0).toDouble()
        val mean = s / n
        syncSpread = noiseSpread(i0, i1, mean).let { if (syncSpread < 0) it else syncSpread + (it - syncSpread) * 0.2 }
        // Only a clean pulse is a reference. Receiver noise sits mostly above
        // 1200 Hz and pulls a noisy reading up: followed, it shifted every
        // colour of a weak picture to "correct" an error that was not there.
        if (s2 / n - mean * mean > NOISY_SYNC_HZ * NOISY_SYNC_HZ) return
        val err = mean - SstvTone.SYNC
        if (abs(err) > 150.0) return
        retune(err * 0.1, start)
    }

    /**
     * Spread of means over one 2400 Hz period around [mean], over [i0, i1): the
     * noise, without the ripple of an overdriven signal. A clipped 1200 Hz
     * sync grows a 3600 Hz harmonic that beats with it at exactly 2400 Hz;
     * measured sample by sample, that ripple read as noise and blurred a
     * clean but hot picture. Averaged over one period, it cancels.
     */
    private fun noiseSpread(i0: Long, i1: Long, mean: Double): Double {
        val w = maxOf(2, (spms / 2.4).roundToInt())
        var acc = 0.0; var k = 0
        var i = i0
        while (i + w <= i1) {
            var s = 0.0
            for (j in i until i + w) s += freqN[(j - freqBase).toInt()].coerceIn(900f, 1500f)
            val d = s / w - mean
            acc += d * d; k++
            i += w
        }
        return if (k == 0) 0.0 else sqrt(acc / k)
    }

    /**
     * Continuous decoding: collects sync pulses while idle and starts the mode
     * whose line length a regular train of them matches.
     */
    private fun huntTrain() {
        if (!continuous) return
        var lo = maxOf(pulseScan, freqBase + voteHalf)
        val hi = total - (30 * spms).toLong()      // leave the filter and vote room
        if (hi - lo < 64) return
        val a = lo - voteHalf; val b = hi + voteHalf
        val n = (b - a).toInt()
        if (votes.size < n + 1) votes = IntArray(n + 1)
        votes[0] = 0
        for (k in 0 until n) {
            votes[k + 1] = votes[k] + if (freqN[(a - freqBase).toInt() + k] < SYNC_MAX) 1 else 0
        }
        var runStart = -1L
        for (i in lo until hi) {
            val w0 = i - voteHalf; val w1 = i + voteHalf + 1
            val sync = 2 * (votes[(w1 - a).toInt()] - votes[(w0 - a).toInt()]) > (w1 - w0)
            if (sync) { if (runStart < 0) runStart = i }
            else if (runStart >= 0) {
                val len = (i - runStart).toInt()
                if (len > 3.5 * spms && len < 26 * spms && onPulse(i, len)) return
                runStart = -1
            }
        }
        // A pulse still running at the end is scanned again next time, whole.
        pulseScan = if (runStart >= 0) runStart else hi
    }

    /** Records a pulse; true when it completes a train and a picture starts. */
    private fun onPulse(end: Long, len: Int): Boolean {
        System.arraycopy(pulseEnd, 1, pulseEnd, 0, PULSES - 1)
        System.arraycopy(pulseLen, 1, pulseLen, 0, PULSES - 1)
        pulseEnd[PULSES - 1] = end; pulseLen[PULSES - 1] = len
        if (pulseCount < PULSES) pulseCount++
        if (pulseCount < TRAIN) return false
        // The mode whose line length the last pulses fit, one missed pulse
        // allowed per gap. Several fit (a Robot 72 line is two Robot 36 ones):
        // the fewest lines spanned wins.
        var best: SstvMode? = null; var bestSpan = Int.MAX_VALUE
        for (m in SstvMode.ALL) {
            val syncLen = m.syncMs * spms
            val period = m.blockMs * spms
            var span = 0; var ok = true
            for (j in PULSES - TRAIN until PULSES) {
                if (pulseLen[j] < syncLen * 0.6 || pulseLen[j] > syncLen * 1.4) { ok = false; break }
                if (j == PULSES - TRAIN) continue
                val gap = (pulseEnd[j] - pulseEnd[j - 1]).toDouble()
                val k = Math.round(gap / period).toInt()
                if (k !in 1..2 || abs(gap - k * period) > maxOf(1.0 * spms, 0.003 * k * period)) { ok = false; break }
                span += k
            }
            if (ok && span < bestSpan) { best = m; bestSpan = span }
        }
        val m = best ?: return false
        startFromTrain(m)
        return true
    }

    /** Starts [m] on the block after the train, the line already fitted on it. */
    private fun startFromTrain(m: SstvMode) {
        prepare(m)
        forcedAnchor = false
        val period = m.blockMs * spms
        val syncLen = m.syncMs * spms
        val lastStart = pulseEnd[PULSES - 1] - syncLen
        var k = 0
        remember(0, lastStart)
        for (j in PULSES - 2 downTo PULSES - TRAIN) {
            k -= Math.round((pulseEnd[j + 1] - pulseEnd[j]) / period).toInt()
            remember(k, pulseEnd[j] - syncLen)
        }
        // Block 0 is the one after the last pulse, which is block -1.
        for (i in 0 until histLen) histK[i] -= 1.0
        blockStart = lastStart + period - m.syncAtMs * spms
        lastSync = lastStart
        pulseCount = 0
        state = State.IMAGE
        listener?.onVis(m)
        decodeBlocks()
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
        histLen = 0; histNext = 0
        residual = 0.0; pendingStep = Double.NaN
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
        // **A majority vote, not an unbroken run.** The frequency is measured
        // sample by sample, and on a noisy or overdriven signal a few samples
        // jump above the threshold. Requiring every one of them below it lost
        // every sync from about 15 dB SNR down, and the frame was abandoned
        // after 8 s — 32 lines of PD 120. A sample now counts as sync when most
        // of its neighbours within ±1 ms are; the window is symmetric, so a
        // clean edge stays exactly where it was.
        val a = maxOf(freqBase, lo - voteHalf)
        val b = minOf(freqBase + freqLen, hi + voteHalf + 1)
        val n = (b - a).toInt()
        if (votes.size < n + 1) votes = IntArray(n + 1)
        votes[0] = 0
        for (k in 0 until n) {
            votes[k + 1] = votes[k] + if (freqN[(a - freqBase).toInt() + k] < SYNC_MAX) 1 else 0
        }
        var bestStart = -1L; var bestLen = 0
        var runStart = -1L; var run = 0
        for (i in lo until hi) {
            val w0 = maxOf(a, i - voteHalf); val w1 = minOf(b, i + voteHalf + 1)
            val below = votes[(w1 - a).toInt()] - votes[(w0 - a).toInt()]
            if (2 * below > (w1 - w0)) {
                if (run == 0) runStart = i
                run++
                if (run > bestLen) { bestLen = run; bestStart = runStart }
            } else run = 0
        }
        lastRunEnd = if (bestLen >= minSamples) bestStart + bestLen else -1L
        return if (bestLen >= minSamples) bestStart else -1
    }

    /** True when the last pulse came from [matchSync]: already placed exactly. */
    private var matched = false

    /** Clamped narrow frequencies, prefix-summed, reused by [matchSync]. */
    private var boxSum = DoubleArray(0)

    /**
     * **Matched filter**: the start in [from, to) where a window of exactly the
     * pulse length [len] has the lowest mean frequency, or -1 if even that is
     * no sync.
     *
     * The longest-run search wants an unbroken stretch below the threshold;
     * at 8 dB SNR noise broke most PD pulses into pieces, 190 of 248 were
     * missed and the frame ran on false wide relocks. The whole pulse, 880
     * samples of it, averaged at once is the best estimate there is against
     * noise. It does not move with the picture either: shifted either way,
     * the window takes in porch or picture, both at 1500 Hz or above.
     */
    private fun matchSync(from: Long, to: Long, len: Double): Long {
        var lo = from; var hi = to
        if (lo < freqBase) lo = freqBase
        if (hi > freqBase + freqLen) hi = freqBase + freqLen
        val l = len.roundToInt()
        val n = (hi - lo).toInt()
        if (l < 2 || n < l + 2) { matched = false; return -1 }
        if (boxSum.size < n + 1) boxSum = DoubleArray(n + 1)
        boxSum[0] = 0.0
        val base = (lo - freqBase).toInt()
        for (k in 0 until n) boxSum[k + 1] = boxSum[k] + freqN[base + k].coerceIn(1000f, 1700f)
        var best = Double.MAX_VALUE; var at = -1
        for (k in 0..n - l) {
            val v = boxSum[k + l] - boxSum[k]
            if (v < best) { best = v; at = k }
        }
        matched = true
        if (at < 0 || best / l > MATCH_MAX_HZ) { lastRunEnd = -1L; return -1 }
        var end = lo + at + l
        // On a clean signal, one more step: the filter smears the leading edge
        // by picture content and the window minimum drifts with it (a PD 120
        // lost 2.7 dB). The trailing edge, 1200 up to the 1500 Hz porch, does
        // not move: where the frequency crosses 1350 Hz near the window's end.
        if (syncSpread in 0.0..EDGE_REFINE_HZ) trailingEdge(end)?.let { end = it }
        lastRunEnd = end
        return end - l
    }

    /** Where the narrow frequency rises through 1350 Hz within ±2 ms of [near]. */
    private fun trailingEdge(near: Long): Long? {
        val w = (2 * spms).toLong()
        val a = maxOf(freqBase + 1, near - w); val b = minOf(freqBase + freqLen - 1, near + w)
        var best: Long? = null
        for (i in a until b) {
            val prev = freqN[(i - 1 - freqBase).toInt()]; val cur = freqN[(i - freqBase).toInt()]
            if (prev < SYNC_MAX && cur >= SYNC_MAX &&
                (best == null || abs(i - near) < abs(best - near))) best = i
        }
        return best
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
            val grownN = FloatArray(want)
            System.arraycopy(freqN, 0, grownN, 0, freqLen)
            freqN = grownN
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
            var expected = blockStart + m.syncAtMs * spms
            // While locked, ±10 ms avoids mistaking something else for sync.
            // After a few misses in a row the line is unlocked: a constant
            // offset that ±10 ms can't recover, seen as a colour band on one
            // edge. Then search half a block and apply the full correction to
            // relock in one step.
            // With a fitted line the prediction holds through a few missed
            // pulses; a wide search then mostly finds noise and throws the
            // fit away. It waits longer — a real jump is still caught.
            val wide = missedRun >= (if (histLen >= 4) WIDE_AFTER_FITTED else WIDE_AFTER)
            // ±20 ms while locked: wide enough to see a pulse moved by an audio
            // glitch (16 ms lost on a live capture), which the step
            // detection below then adopts.
            val win = if (wide) blockPeriod * 0.45 else 20.0 * spms
            val minRun = (m.syncMs * 0.55 * spms).toInt().coerceAtLeast(2)
            // The window must reach the end of the pulse, not just its start:
            // ±10 ms around the start could not hold a 20 ms PD pulse, every
            // locked search failed, and PD frames (the ISS ones) ran on a wide
            // relock every fourth block, lines jumping by a pixel.
            val reach = if (wide) 0.0 else m.syncMs * spms
            var hit = if (blockIndex == 0) -1L
                      else matchSync((expected - win).toLong(), (expected + win + reach).toLong(), m.syncMs * spms)
            if (hit >= 0) {
                // Place the pulse by its trailing edge when its length is
                // plausible. The leading edge follows the last pixels of the
                // previous line (white or black, as the picture goes), and the
                // filter smears it by content: lines wandered by a third of a
                // pixel on a checkerboard. The trailing edge always goes from
                // 1200 to the 1500 Hz porch, in every mode, and the 1350 Hz
                // threshold sits exactly between: it does not move with the
                // picture, whatever the filter. On a noisy signal, though, one
                // edge wanders more than the middle of the pulse, and that
                // content bias is small next to the noise: the middle is used.
                val len = lastRunEnd - hit
                val syncLen = m.syncMs * spms
                if (!matched && len > syncLen * 0.7 && len < syncLen * 1.3)
                    hit = if (syncSpread > CENTRE_SYNC_HZ)
                        ((hit + lastRunEnd) / 2.0 - syncLen / 2).roundToLong()
                    else (lastRunEnd - syncLen).roundToLong()
                // **A step, or a misread pulse?** Audio glitches (samples lost
                // or played twice by a busy phone or a USB sound card) move
                // every following pulse by the same amount. The fitted line kept
                // the old position and rejected them as outliers: ten lines
                // out of place. Two pulses in a row off by the same amount are
                // a step, adopted at once with a fresh line. The threshold
                // follows the usual error, so noise is not taken for a step.
                if (!wide) {
                    val r = (hit - expected)
                    val thr = maxOf(STEP_MIN_MS * spms, 3 * residual)
                    if (abs(r) > thr) {
                        if (!pendingStep.isNaN() && abs(r - pendingStep) < maxOf(0.3 * spms, residual)) {
                            histLen = 0; histNext = 0
                            blockStart += r; expected += r
                            lastSync = -1.0            // a step is not clock drift
                            pendingStep = Double.NaN
                        } else {
                            pendingStep = r.toDouble()
                            hit = -1L                  // hold: one more pulse decides
                        }
                    } else {
                        pendingStep = Double.NaN
                        residual = sqrt(residual * residual + (r * r - residual * residual) * 0.1)
                    }
                }
                // While locked, a jump no clock could make is a misread pulse,
                // not a correction. Followed, it shifted whole lines sideways by
                // 10-20 pixels on a noisy PD 120. It counts as a miss.
                if (hit >= 0 && !wide && abs(hit - expected) > LOCKED_MAX_ERR_MS * spms) hit = -1L
            }
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
                if (wide) histLen = 0          // relocked: the old line is history
                remember(blockIndex, hit.toDouble())
                missedRun = 0
                followTuning(m, hit, lastRunEnd)
            } else if (blockIndex > 0) missedRun++
            // **The line, fitted on recent pulses.** One pulse, one correction
            // made every line dance on a noisy signal; a straight line through
            // the last dozen pulses averages their noise, and its slope is the
            // line length exactly — sound-card clock error included, which
            // cost PD 120 about 4 dB at ±300 ppm.
            if (!wide) fitLine()?.let { (a, b) ->
                blockPeriod = b.coerceIn(nominalPeriod * 0.985, nominalPeriod * 1.015)
                blockStart = a + blockPeriod * blockIndex - m.syncAtMs * spms
            }

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
        val f = meanFreq(sepEnd - 3.5 * spms, sepEnd - 0.5 * spms, freqN)
        return if (f > 1900.0) Role.CB else Role.CR
    }

    /** Samples one channel of the block into [SstvMode.width] levels. */
    private fun readSegment(m: SstvMode, seg: Segment): IntArray {
        val out = IntArray(m.width)
        // Positions in the block follow the measured block length: with a
        // sound-card clock 300 ppm off, the second line of a PD block starts
        // 0.6 pixel away from where the nominal timing puts it.
        val scale = blockPeriod / nominalPeriod
        val start = blockStart + seg.startMs * spms * scale
        val end = start + seg.durMs * spms * scale
        val step = seg.durMs * spms * scale / m.width
        // Pixels whose span reaches into the filter's blend with the next (or
        // previous) tone take the nearest clean span instead.
        val lo = start + edgeGuard; val hi = end - edgeGuard
        val half = maxOf(step, smoothingMs() * spms) / 2
        for (x in 0 until m.width) {
            val c = start + (x + 0.5) * step
            var a = c - half; var b = c + half
            if (a < lo) { a = lo; if (b < a + 1) b = a + 1 }
            if (b > hi) { b = hi; if (a > b - 1) a = b - 1 }
            out[x] = SstvTone.level(pixelFreq(a, b))
        }
        return out
    }

    /**
     * Shortest span, in ms, a pixel is averaged over — from the noise measured
     * in the sync pulses.
     *
     * A clean signal is read pixel by pixel, for sharpness. A noisy one is
     * averaged over a longer span: read pixel by pixel, a PD 120 at 10-12 dB
     * SNR lost 4-5 dB to noise that a little blur removes. In time, not pixels:
     * a Martin pixel already
     * lasts 0.46 ms, a PD 120 one 0.19 ms. Spreads measured by [noiseSpread]:
     * 0 Hz clean, 2-8 overdriven, 12 at 30 dB SNR, 39 at 20, 68 at 15, 83 at
     * 12, 103 at 10.
     */
    private fun smoothingMs(): Double =
        if (syncSpread < 0) 0.0 else ((syncSpread - 20.0) / 60.0).coerceIn(0.0, 1.4)

    /**
     * Mean frequency over [a, b) for a pixel, each sample first held inside
     * the scan range. A noise click reads thousands of hertz off for a
     * sample or two; unbounded, one click was enough to paint a pixel white or
     * black.
     */
    private fun pixelFreq(a: Double, b: Double): Double {
        var i0 = a.roundToLong()
        var i1 = b.roundToLong()
        if (i1 <= i0) i1 = i0 + 1
        if (i0 < freqBase) i0 = freqBase
        if (i1 > freqBase + freqLen) i1 = freqBase + freqLen
        if (i1 <= i0) return SstvTone.CARRIER
        val lo = (SstvTone.BLACK - 300.0).toFloat(); val hi = (SstvTone.WHITE + 300.0).toFloat()
        val buf = if (syncSpread > NARROW_PIXELS_HZ) freqN else freq
        var s = 0.0
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in i0 until i1) {
            val f = buf[(i - freqBase).toInt()].coerceIn(lo, hi)
            s += f
            if (f < mn) mn = f
            if (f > mx) mx = f
        }
        val n = i1 - i0
        return if (n >= 5) (s - mn - mx) / (n - 2) else s / n
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
            pixels[off + x] = SstvTone.rgb(y[x], cr?.get(x) ?: 128, cb?.get(x) ?: 128)
        }
    }

    companion object {
        /** Total header length in milliseconds. */
        const val VIS_MS = 910L
        /** Missed syncs before widening the search, once a line is fitted. */
        private const val WIDE_AFTER_FITTED = 8
        /** Pulses remembered for continuous decoding. */
        private const val PULSES = 4
        /** Regular pulses that make a train (continuous decoding). */
        private const val TRAIN = 4
        /** Sync pulses the line is fitted on. */
        private const val HIST = 12
        /** Noise spread up to which a matched pulse is refined on its trailing edge, Hz. */
        private const val EDGE_REFINE_HZ = 50.0
        /** Mean frequency above which the best window is no sync pulse, Hz. */
        private const val MATCH_MAX_HZ = 1400.0
        /** Noise spread above which a sync is placed by its middle, Hz. */
        private const val CENTRE_SYNC_HZ = 30.0
        /** Noise spread above which pixels are read from the narrow reading, Hz. */
        private const val NARROW_PIXELS_HZ = 30.0
        /** Smallest sync step adopted, ms (see the step detection in decodeBlocks). */
        private const val STEP_MIN_MS = 0.4
        /** Largest sync error, ms, believed while locked. */
        private const val LOCKED_MAX_ERR_MS = 1.5
        /** Spread above which a sync pulse is too noisy to retune on, Hz. */
        private const val NOISY_SYNC_HZ = 60.0
        /** Largest tuning error the header is accepted with, Hz. */
        private const val MAX_OFFSET = 250.0
        /** Everything below this is treated as the sync tone. */
        private const val SYNC_MAX = 1350f
        /** Missed syncs before widening the search. */
        private const val WIDE_AFTER = 3
        /** Tolerated sync loss, ms, before abandoning the frame. */
        private const val ABORT_MS = 8000.0
    }
}
