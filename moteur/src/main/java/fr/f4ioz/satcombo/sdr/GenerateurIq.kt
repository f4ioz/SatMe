/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import fr.f4ioz.satcombo.cw.DecodeurCw
import fr.f4ioz.satcombo.domain.Ft8
import fr.f4ioz.satcombo.domain.Ft8Signal
import fr.f4ioz.satcombo.domain.Ldpc
import fr.f4ioz.satcombo.sstv.IndicatifSstv
import fr.f4ioz.satcombo.sstv.ProgrammeSstv
import fr.f4ioz.satcombo.sstv.SstvMode
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * « Générateur d'essai »: a receiver with no hardware — noise and test
 * signals made here, at [Dsp.RTL_RATE] in 16 bits, so every decoder can be
 * tried without an antenna: a carrier, keyed CW, a sideband « voice » (tone
 * pairs in syllables), an FM tone, an FT8 call in the even 15 s slots, and
 * SSTV pictures in narrow FM.
 *
 * A fake receiver only: what it makes goes to the reception, never to a
 * transmitter.
 */
class GenerateurIq(
    reglages: Reglages = Reglages(),
    private val horloge: () -> Long = System::currentTimeMillis,
    /** In real time (false: as fast as asked, for tests). */
    private val cadence: Boolean = true,
    /** The first sample's time (FT8's slots are counted from it). */
    debutMs: Long = horloge(),
    private val dort: (Long) -> Unit = { Thread.sleep(it) },
    graine: Int = 0x5A7E,
) : SourceIq {

    /** One signal: on or not, where (Hz from the centre), how strong (dB above the noise in 3 kHz). */
    data class Signal(val actif: Boolean, val decalageHz: Int, val snrDb: Double)

    data class Reglages(
        /** The noise's level, dB below full scale (each of I and Q). */
        val bruitDbfs: Double = -32.0,
        val porteuse: Signal = Signal(true, -70_000, 30.0),
        val cw: Signal = Signal(true, 12_000, 25.0),
        val cwTexte: String = "VVV DE SATME TEST",
        val cwWpm: Int = 18,
        val blu: Signal = Signal(true, 30_000, 25.0),
        val fm: Signal = Signal(true, -20_000, 30.0),
        val fmTonHz: Int = 1_000,
        val ft8: Signal = Signal(true, 50_000, 15.0),
        val ft8Message: String = "CQ Q0SAT JN18",
        val sstv: Signal = Signal(true, -45_000, 30.0),
        val sstvMode: String = "Robot 36",
        /** A CTCSS sub-tone under the FM tone (one of [DetecteurSousTon.TONS], Hz); 0: none. */
        val fmCtcssHz: Double = 0.0,
    ) {
        /** Flat, the keys [avec] reads: kept as is, sent to the page as is. */
        fun plat(): JSONObject {
            val o = JSONObject().put("bruit", bruitDbfs).put("cwTexte", cwTexte).put("cwWpm", cwWpm).put("fmTon", fmTonHz).put("fmCtcss", fmCtcssHz)
                .put("ft8Message", ft8Message).put("sstvMode", sstvMode)
            for ((nom, x) in listOf("porteuse" to porteuse, "cw" to cw, "blu" to blu, "fm" to fm, "ft8" to ft8, "sstv" to sstv))
                o.put("${nom}On", if (x.actif) "1" else "0").put("${nom}Hz", x.decalageHz).put("${nom}Snr", x.snrDb)
            return o
        }

        /** Changed by the page's fields ([p]: a field's value, or null when not sent); bounded. */
        fun avec(p: (String) -> String?): Reglages {
            fun sig(nom: String, x: Signal) = Signal(
                p("${nom}On")?.let { it == "1" } ?: x.actif,
                p("${nom}Hz")?.toIntOrNull()?.coerceIn(-85_000, 85_000) ?: x.decalageHz,
                p("${nom}Snr")?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(-20.0, 60.0) ?: x.snrDb)
            return Reglages(
                p("bruit")?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(-80.0, -10.0) ?: bruitDbfs,
                sig("porteuse", porteuse), sig("cw", cw),
                p("cwTexte")?.uppercase()?.filter { it.isLetterOrDigit() || it in " /?.,=" }?.take(60)?.ifBlank { null } ?: cwTexte,
                p("cwWpm")?.toIntOrNull()?.coerceIn(5, 40) ?: cwWpm,
                sig("blu", blu), sig("fm", fm),
                p("fmTon")?.toIntOrNull()?.coerceIn(100, 3_000) ?: fmTonHz,
                sig("ft8", ft8),
                p("ft8Message")?.trim()?.uppercase()?.takeIf { messageFt8(it) != null } ?: ft8Message,
                sig("sstv", sstv),
                p("sstvMode")?.takeIf { SstvMode.byName(it) != null } ?: sstvMode,
                p("fmCtcss")?.replace(',', '.')?.toDoubleOrNull()?.let { DetecteurSousTon.normalise(it) } ?: fmCtcssHz)
        }
    }

    @Volatile var reglages: Reglages = reglages
        set(v) { field = v; change = true }
    @Volatile private var change = true

    override val iq16: Boolean get() = true
    @Volatile override var octets = 0L; private set
    @Volatile private var ferme = false

    /** Pairs made so far; their time is [debut] + count / rate. */
    @Volatile var produites = 0L; private set
    val debut: Long = debutMs
    private var t0 = 0L

    // ---------------------------------------------------------------- the parts

    private val alea = Xorshift(graine)
    private var sigma = 0f
    private var aPorteuse = 0f; private var phPorteuse = 0; private var pasPorteuse = 0
    private var aCw = 0f; private var phCw = 0; private var pasCw = 0
    private var cwManip: IntArray = IntArray(0)   // samples per element, signed: > 0 key down
    private var cwElem = 0; private var cwDans = 0; private var cwEnv = 0f
    private var aBlu = 0f; private var phBlu1 = 0; private var phBlu2 = 0
    private var bluSyll = 0; private var bluF1 = 0; private var bluF2 = 0; private var bluLong = 0; private var bluSilence = true
    private var aFm = 0f; private var phFm = 0; private var phFmTon = 0; private var phCtcss = 0
    private var aFt8 = 0f; private var phFt8 = 0
    private var ft8Freq: DoubleArray? = null
    private var aSstv = 0f; private var phSstv = 0
    private var sstvMod: ModulateurAudio? = null
    private var sstvIq = ShortArray(0)
    private var r = Reglages()

    private fun amplitude(s: Signal): Float =
        if (!s.actif) 0f else (sigma * sqrt(2.0 * 3_000.0 / Dsp.RTL_RATE) * 10.0.pow(s.snrDb / 20)).toFloat().coerceAtMost(29_000f)

    private fun prepare() {
        change = false
        val n = reglages
        val ancien = r
        r = n
        sigma = (32_767.0 * 10.0.pow(n.bruitDbfs / 20)).toFloat()
        aPorteuse = amplitude(n.porteuse); pasPorteuse = pas(n.porteuse.decalageHz.toDouble())
        aCw = amplitude(n.cw); pasCw = pas(n.cw.decalageHz.toDouble())
        if (cwManip.isEmpty() || n.cwTexte != ancien.cwTexte || n.cwWpm != ancien.cwWpm) {
            val ech = Dsp.RTL_RATE / 1000.0
            cwManip = (DecodeurCw.manipulation(n.cwTexte, n.cwWpm) + (false to 3_000.0))
                .map { (bas, ms) -> (ms * ech).toInt().coerceAtLeast(1) * (if (bas) 1 else -1) }.toIntArray()
            cwElem = 0; cwDans = 0
        }
        aBlu = amplitude(n.blu)
        aFm = amplitude(n.fm)
        aFt8 = amplitude(n.ft8)
        if (ft8Freq == null || n.ft8Message != ancien.ft8Message) {
            ft8Freq = tonsFt8(n.ft8Message)?.let { Ft8Signal.trajectoire(it, Ft8Signal.FT8, FT8_AUDIO_HZ) }
        }
        aSstv = amplitude(n.sstv)
        if (aSstv > 0f && (sstvMod == null || n.sstvMode != ancien.sstvMode || n.sstv.snrDb != ancien.sstv.snrDb)) {
            val m = SstvMode.byName(n.sstvMode) ?: SstvMode.ALL.first()
            sstvMod = ModulateurAudio(ModulationAudio.NBFM, Dsp.AUDIO_RATE, Dsp.RTL_RATE / Dsp.AUDIO_RATE, SSTV_DEVIATION_HZ,
                niveau = 1.0 * (aSstv / ModulateurAudio.PLEIN).coerceIn(0.001, 0.9), source = SourceSstv(m))
        } else if (aSstv == 0f) sstvMod = null
    }

    /** SSTV pictures one after the other, [PAUSE_SSTV_S] of silence between (the FM carrier stays). */
    private class SourceSstv(private val m: SstvMode) : (ShortArray) -> Int {
        private var p = ProgrammeSstv(listOf(m to mire(m)), Dsp.AUDIO_RATE)
        private var silence = 0
        override fun invoke(b: ShortArray): Int {
            if (silence > 0) {
                val k = minOf(silence, b.size); b.fill(0, 0, k); silence -= k; return k
            }
            val n = p(b)
            if (n > 0) return n
            p = ProgrammeSstv(listOf(m to mire(m)), Dsp.AUDIO_RATE)
            silence = (PAUSE_SSTV_S * Dsp.AUDIO_RATE).toInt()
            b.fill(0, 0, 1)
            return 1
        }
    }

    private fun pas(hz: Double): Int = Math.round(hz / Dsp.RTL_RATE * 4_294_967_296.0).toInt()

    // ---------------------------------------------------------------- making

    override fun read16(buf: ShortArray, timeoutMs: Int): Int {
        if (ferme) return -1
        if (change) prepare()
        val n = buf.size / 2
        if (cadence) {
            if (t0 == 0L) t0 = horloge() - Math.round(produites * 1000.0 / Dsp.RTL_RATE)
            val d = t0 + Math.round((produites + n) * 1000.0 / Dsp.RTL_RATE) - horloge()
            if (d > 0) dort(d)
            else if (d < -2_000) t0 = horloge() - Math.round(produites * 1000.0 / Dsp.RTL_RATE)
        }
        if (sstvMod != null && sstvIq.size < 2 * n) sstvIq = ShortArray(2 * n)
        sstvMod?.remplis(sstvIq, n)
        // The FT8 slot: even ones only, the call 0.5 s after the slot's start.
        val tMs = debut + produites * 1000.0 / Dsp.RTL_RATE
        val ft = ft8Freq
        val tranche = Math.floorDiv(tMs.toLong(), 15_000L)
        val dansTrancheS = (tMs - tranche * 15_000.0) / 1000.0 - 0.5
        val ft8Ici = aFt8 > 0f && ft != null && tranche % 2 == 0L
        val ft8Base = r.ft8.decalageHz.toDouble()
        val fmTon = pas(r.fmTonHz.toDouble())
        val fmDev = FM_DEVIATION_HZ / Dsp.RTL_RATE * 4_294_967_296.0
        val fmBase = pas(r.fm.decalageHz.toDouble())
        val ctcss = pas(r.fmCtcssHz)
        val ctcssDev = if (r.fmCtcssHz > 0.0) CTCSS_DEVIATION_HZ / Dsp.RTL_RATE * 4_294_967_296.0 else 0.0
        val pasSstv = pas(r.sstv.decalageHz.toDouble())
        val rampe = 1f / (CW_RAMPE_MS * Dsp.RTL_RATE / 1000f)
        var o = 0
        for (k in 0 until n) {
            var re = alea.gauss() * sigma
            var im = alea.gauss() * sigma
            if (aPorteuse > 0f) {
                val x = phPorteuse ushr 16
                re += COS[x] * aPorteuse; im += SIN[x] * aPorteuse
                phPorteuse += pasPorteuse
            }
            if (aCw > 0f && cwManip.isNotEmpty()) {
                val e = cwManip[cwElem]
                val cible = if (e > 0) 1f else 0f
                cwEnv = if (cwEnv < cible) minOf(cible, cwEnv + rampe) else maxOf(cible, cwEnv - rampe)
                if (cwEnv > 0f) {
                    // Raised-cosine edges: no key clicks across the band.
                    val g = (0.5f - 0.5f * COS[((cwEnv * 0.5f) * 65_536f).toInt().coerceIn(0, 65_535)]) * aCw
                    val x = phCw ushr 16
                    re += COS[x] * g; im += SIN[x] * g
                }
                phCw += pasCw
                if (++cwDans >= kotlin.math.abs(e)) { cwDans = 0; cwElem = (cwElem + 1) % cwManip.size }
            }
            if (aBlu > 0f) {
                if (bluLong <= 0) syllabe()
                bluLong--
                if (!bluSilence) {
                    val a1 = phBlu1 ushr 16; val a2 = phBlu2 ushr 16
                    val g = aBlu * 0.5f
                    re += (COS[a1] + COS[a2] * 0.7f) * g; im += (SIN[a1] + SIN[a2] * 0.7f) * g
                }
                phBlu1 += bluF1; phBlu2 += bluF2
            }
            if (aFm > 0f) {
                val x = phFm ushr 16
                re += COS[x] * aFm; im += SIN[x] * aFm
                phFm += fmBase + (COS[phFmTon ushr 16] * fmDev).toInt() + (COS[phCtcss ushr 16] * ctcssDev).toInt()
                phFmTon += fmTon; phCtcss += ctcss
            }
            if (ft8Ici) {
                val t = dansTrancheS + k.toDouble() / Dsp.RTL_RATE
                val idx = (t * Ft8Signal.FT8.cadenceHz).toInt()
                if (t >= 0 && idx < ft!!.size) {
                    val x = phFt8 ushr 16
                    re += COS[x] * aFt8; im += SIN[x] * aFt8
                    phFt8 += Math.round((ft8Base + ft[idx]) / Dsp.RTL_RATE * 4_294_967_296.0).toInt()
                }
            }
            if (sstvMod != null) {
                // The modulator's baseband, moved to its place.
                val si = sstvIq[2 * k].toFloat(); val sq = sstvIq[2 * k + 1].toFloat()
                val x = phSstv ushr 16
                val c = COS[x]; val s = SIN[x]
                re += si * c - sq * s; im += si * s + sq * c
                phSstv += pasSstv
            }
            buf[o++] = re.toInt().coerceIn(-32_767, 32_767).toShort()
            buf[o++] = im.toInt().coerceIn(-32_767, 32_767).toShort()
        }
        produites += n
        octets += 4L * n
        return o
    }

    /** The « voice »: syllables of two tones (300–2400 Hz) 80 to 300 ms long, short silences between. */
    private fun syllabe() {
        val ech = Dsp.RTL_RATE / 1000
        if (bluSilence) {
            bluSilence = false
            val f1 = 300 + alea.entier(900)
            val f2 = f1 + 500 + alea.entier(1_000)
            bluF1 = pas(r.blu.decalageHz + f1.toDouble()); bluF2 = pas(r.blu.decalageHz + f2.toDouble())
            bluLong = (80 + alea.entier(220)) * ech
            bluSyll++
        } else {
            bluSilence = true
            // A breath every few syllables.
            bluLong = (if (bluSyll % 6 == 0) 600 else 40 + alea.entier(120)) * ech
        }
    }

    /** What to listen to: per signal, the mode and where to set the receiver (Hz from the centre) to hear it. */
    data class Ecoute(val cle: String, val nom: String, val mode: String, val decalageHz: Int, val largeurHz: Int)

    fun ecoutes(): List<Ecoute> = ecoutes(reglages)

    override fun read(buf: ByteArray, timeoutMs: Int): Int = throw UnsupportedOperationException()
    override fun setSampleRate(rate: Int): Double = Dsp.RTL_RATE.toDouble()
    override fun setFreqCorrection(ppm: Int) {}
    override fun setCenterFreq(hz: Long): Long = hz
    override fun setGain(tenthDb: Int?) {}
    override fun setAgc(on: Boolean) {}
    override fun close() { ferme = true }

    /** A fast pseudo-random source: Gaussian values from a table, xorshift indices. */
    private class Xorshift(graine: Int) {
        private var s = if (graine == 0) 1 else graine
        private fun suivant(): Int { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); return s }
        fun gauss(): Float = GAUSS[suivant() ushr 16]
        fun entier(n: Int): Int = ((suivant() ushr 1) % n)
    }

    companion object {
        const val FT8_AUDIO_HZ = 1_500.0
        const val FM_DEVIATION_HZ = 3_000.0
        /** The sub-tone's deviation: a tenth of the 5 kHz channel, as transmitters put it. */
        const val CTCSS_DEVIATION_HZ = 500.0
        const val SSTV_DEVIATION_HZ = 5_000.0
        const val PAUSE_SSTV_S = 5.0
        const val CW_RAMPE_MS = 5f
        /** The CW note heard in USB (Hz): the receiver set this much below the carrier. */
        const val NOTE_CW_HZ = 700
        private val COS = FloatArray(65_536) { cos(2.0 * PI * it / 65_536).toFloat() }
        private val SIN = FloatArray(65_536) { sin(2.0 * PI * it / 65_536).toFloat() }
        /** 65 536 standard normal values (fixed seed), each drawn equally often. */
        private val GAUSS = java.util.Random(20_261_018L).let { r -> FloatArray(65_536) { r.nextGaussian().toFloat() } }

        fun ecoutes(r: Reglages): List<Ecoute> = listOfNotNull(
            Ecoute("porteuse", "Porteuse", "USB", r.porteuse.decalageHz - 1_000, 0).takeIf { r.porteuse.actif },
            Ecoute("cw", "CW « " + r.cwTexte + " »", "USB", r.cw.decalageHz - NOTE_CW_HZ, 0).takeIf { r.cw.actif },
            Ecoute("blu", "BLU (voix de synthèse)", "USB", r.blu.decalageHz, 0).takeIf { r.blu.actif },
            Ecoute("fm", "FM, ton " + r.fmTonHz + " Hz" + (if (r.fmCtcssHz > 0.0) ", CTCSS " + DetecteurSousTon.libelle(r.fmCtcssHz) else ""), "NFM", r.fm.decalageHz, 12_000).takeIf { r.fm.actif },
            Ecoute("ft8", "FT8 « " + r.ft8Message + " »", "USB", r.ft8.decalageHz, 3_000).takeIf { r.ft8.actif },
            Ecoute("sstv", "SSTV " + r.sstvMode + " (FM)", "NFM", r.sstv.decalageHz, 16_000).takeIf { r.sstv.actif },
        )

        /** « CQ CALL GRID » or « CALL1 CALL2 GRID » (standard callsigns) as FT8's 77 bits; null when it does not fit. */
        fun messageFt8(texte: String): BooleanArray? {
            val m = texte.trim().uppercase().split(Regex("\\s+"))
            if (m.size != 3) return null
            val c1 = if (m[0] == "CQ") 2L else Ft8.indicatifVers28(m[0]) ?: return null
            val c2 = Ft8.indicatifVers28(m[1]) ?: return null
            val g = m[2]
            if (!Regex("[A-R]{2}[0-9]{2}").matches(g)) return null
            val g15 = (g[0] - 'A') * 1800 + (g[1] - 'A') * 100 + (g[2] - '0') * 10 + (g[3] - '0')
            val bits = BooleanArray(Ft8.BITS_MESSAGE)
            Ft8.ecritEntier(bits, 0, 28, c1)
            Ft8.ecritEntier(bits, 29, 28, c2)
            Ft8.ecritEntier(bits, 59, 15, g15.toLong())
            Ft8.ecritEntier(bits, 74, 3, 1L)
            return bits
        }

        /** The 79 tones of [texte]: CRC, LDPC parity, Costas arrays. */
        fun tonsFt8(texte: String): IntArray? {
            val m = messageFt8(texte) ?: return null
            return Ft8.bitsVersSymboles(Ldpc.encode(Ft8.avecControle(m)))
        }

        /** The test card: colour bars over a grey ramp, « SATME TEST » across the top. */
        fun mire(m: SstvMode): IntArray {
            val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
            val px = IntArray(m.width * m.height) { i ->
                val x = i % m.width; val y = i / m.width
                if (y < m.height / 2) bars[x * 8 / m.width] else { val v = x * 255 / (m.width - 1); (v shl 16) or (v shl 8) or v }
            }
            return IndicatifSstv.incruste(px, m.width, m.height, "SATME TEST")
        }
    }
}
