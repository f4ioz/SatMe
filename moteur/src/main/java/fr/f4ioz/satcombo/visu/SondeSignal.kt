/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.visu

/**
 * A demodulator's tap for the « modem scope » views ([VisuSignal]): the
 * last symbols, eye samples, decision levels and bits, kept in rings while
 * somebody looks, nothing otherwise.
 *
 * The demodulator holds one (or none) and, in its loops, writes only
 * behind `if (sonde?.actif == true)`: one volatile read a sample when no
 * viewer is there. A viewer calls [abonne] at each reading (a few times a
 * second, [cliche] after); [actif] falls back by itself [BAIL_MS] after the
 * last one, and the rings (allocated at the first viewer) stay as they are.
 *
 * One writer thread (the demodulator's), readers anywhere: the rings are
 * written without a lock, each count volatile after its element — a reading
 * may see the newest element or not, which a picture does not mind.
 *
 * Generic: no satellite, no mode in here. A receiver says what makes sense
 * ([vues]), the sync words to show in the bits ([motifs]), the bit order
 * of its bytes ([lsbDabord]), and feeds what it has:
 * - [point]: an I/Q symbol after the carrier loop (PSK: the constellation);
 * - [oeil]: a sample of the soft stream (discriminator, tone difference)
 *   with the clock's phase since the last decision (the eye);
 * - [niveau]: the level at each decision, in [unite] (the tones' histogram
 *   when it is a frequency, the eye's opening);
 * - [symbole]: when each decision fell, in the demodulator's samples at
 *   [fs] (the bit rate measured, the clock's error);
 * - [frequence]: a tone's frequency measured at a decision (audio FSK:
 *   the histogram of the tones then, mark and space as heard);
 * - [spectre] or [audio]: the tones' spectrum, when nothing measures them;
 * - [bit], [trame]: the bits as the frame reader sees them, the last good
 *   frame.
 */
class SondeSignal(
    /** Short key (« paquets », « genesis »…), and the name shown. */
    val cle: String,
    val nom: String,
    val vues: Set<Vue>,
    /** The sync words looked for in the bits as they come (shown, never used to decode). */
    val motifs: List<Motif> = emptyList(),
    /** Bytes sent least significant bit first (HDLC), else MSB first. */
    val lsbDabord: Boolean = false,
    /** Bits a character (8; 5 for ITA2). */
    val groupe: Int = 8,
    /** The constellation's order (2: BPSK, 4: QPSK…). */
    val ordre: Int = 2,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    enum class Vue { CONSTELLATION, OEIL, TONS, BITS }

    /**
     * A sync word: its last [longueur] bits ([valeur], the oldest bit the
     * most significant), up to [tolerance] wrong; [deuxPolarites]: also
     * inverted (an FM discriminator's sign, LSB against USB).
     */
    data class Motif(val nom: String, val valeur: Long, val longueur: Int, val tolerance: Int = 0, val deuxPolarites: Boolean = false) {
        internal val masque: Long = if (longueur >= 64) -1L else (1L shl longueur) - 1
    }

    /** Bits [debut] (absolute count) to [debut] + [longueur]: a sync word or a frame. */
    data class Repere(val debut: Long, val longueur: Int, val nom: String, val inverse: Boolean = false, val ms: Long = 0)

    /** Someone looks: write. */
    @Volatile var actif = false; private set
    @Volatile private var luMs = 0L

    /** The demodulator's sample rate ([symbole]'s unit) and nominal symbol rate; [unite]: the levels'. */
    @Volatile var fs = 0.0; private set
    @Volatile var debit = 0.0; private set
    @Volatile var unite = ""; private set
    /** The tones' band (audio Hz) for [audio]'s spectrum, and the shift expected (0: unknown). */
    @Volatile var bandeTons: Pair<Double, Double> = 300.0 to 3_000.0
    @Volatile var ecartAttenduHz = 0.0
    /** Which reading of the bits is shown (« G3RUH », « NRZI »…), when there are several. */
    @Volatile var lecture = ""

    /** The demodulator's rates (when its chain is built, or changes). */
    fun regle(fs: Double, debit: Double, unite: String = this.unite) { this.fs = fs; this.debit = debit; this.unite = unite }

    // ------------------------------------------------------------ rings (allocated at the first viewer)
    private var pI = FloatArray(0); private var pQ = FloatArray(0)
    @Volatile private var nP = 0L
    private var oX = FloatArray(0); private var oY = FloatArray(0)
    @Volatile private var nO = 0L
    private var nv = FloatArray(0); private var tSym = DoubleArray(0)
    @Volatile private var nN = 0L
    @Volatile private var nT = 0L
    private var fr = FloatArray(0)
    @Volatile private var nF = 0L
    private var bt = ByteArray(0)
    @Volatile private var nB = 0L
    private var son = FloatArray(0)
    @Volatile private var nA = 0L
    @Volatile private var fsAudio = 0.0
    private val reperes = arrayOfNulls<Repere>(N_REPERES)
    @Volatile private var nR = 0L
    @Volatile private var derniereTrame: Repere? = null
    @Volatile private var tons: VisuSignal.Spectre? = null
    private var registre = 0L
    private var verif = 0

    /** Samples given to [oeil] so far: a clock for [symbole] when the demodulator has none of its own. */
    var echantillons = 0L; private set

    /** A viewer's reading: the taps write for [BAIL_MS] more. */
    @Synchronized
    fun abonne() {
        luMs = horloge()
        if (actif) return
        if (pI.isEmpty()) {
            pI = FloatArray(N_POINTS); pQ = FloatArray(N_POINTS)
            oX = FloatArray(N_OEIL); oY = FloatArray(N_OEIL)
            nv = FloatArray(N_NIVEAUX); tSym = DoubleArray(N_NIVEAUX); fr = FloatArray(N_NIVEAUX)
            bt = ByteArray(N_BITS)
        }
        actif = true
    }

    /**
     * Another demodulator feeds the tap now (a receiver running several rates
     * at once): what the old one measured — points, eye, levels, tones,
     * decision times — is dropped; the bits and their marks stay.
     */
    @Synchronized
    fun oublie() {
        nP = 0; nO = 0; nN = 0; nT = 0; nF = 0; tons = null
    }

    /** Now and then, from the writer: nobody read for a while, the taps go quiet. */
    private fun echeance() {
        if (++verif and 4095 != 0) return
        if (horloge() - luMs > BAIL_MS) actif = false
    }

    fun point(i: Float, q: Float) {
        if (!actif) return
        val k = (nP % N_POINTS).toInt()
        pI[k] = i; pQ[k] = q
        nP++
        echeance()
    }

    /** One soft sample, [phase] (symbol periods since the last decision, 0..1), [v] scaled so the two levels are ±1. */
    fun oeil(phase: Double, v: Float) {
        if (!actif) return
        echantillons++
        val k = (nO % N_OEIL).toInt()
        var x = phase + 0.5
        x -= Math.floor(x)
        oX[k] = x.toFloat(); oY[k] = v
        nO++
        echeance()
    }

    /** The level at a decision, in [unite]. */
    fun niveau(v: Float) {
        if (!actif) return
        nv[(nN % N_NIVEAUX).toInt()] = v
        nN++
    }

    /** The frequency of the tone heard at a decision (Hz). */
    fun frequence(hz: Float) {
        if (!actif || hz.isNaN()) return
        fr[(nF % N_NIVEAUX).toInt()] = hz
        nF++
    }

    /** A decision's time, in the demodulator's samples at [fs]. */
    fun symbole(t: Double) {
        if (!actif) return
        tSym[(nT % N_NIVEAUX).toInt()] = t
        nT++
    }

    /** One bit as the frame reader takes it; the sync words looked for. */
    fun bit(b: Int) {
        if (!actif) return
        bt[(nB % N_BITS).toInt()] = (b and 1).toByte()
        nB++
        registre = (registre shl 1) or (b and 1).toLong()
        for (m in motifs) {
            if (nB < m.longueur) continue
            val d = java.lang.Long.bitCount((registre xor m.valeur) and m.masque)
            val inverse = m.deuxPolarites && m.longueur - d <= m.tolerance
            if (d <= m.tolerance || inverse) {
                reperes[(nR % N_REPERES).toInt()] = Repere(nB - m.longueur, m.longueur, m.nom, inverse && d > m.tolerance)
                nR++
            }
        }
        echeance()
    }

    /** A good frame [longueur] bits long ending at the last bit. */
    fun trame(nom: String, longueur: Int) {
        if (!actif) return
        derniereTrame = Repere(maxOf(0L, nB - longueur), minOf(longueur.toLong(), nB).toInt(), nom, ms = horloge())
    }

    /** A good frame between the last two sync words (HDLC: the flag before and the one that closed it). */
    fun trameEntreReperes(nom: String) {
        if (!actif || nR < 2) return
        val fin = reperes[((nR - 1) % N_REPERES).toInt()] ?: return
        val l = maxOf(0L, nB - fin.debut - fin.longueur)
        // The opening flag: the last one more than a byte before the closing one.
        var debut = -1L
        var k = nR - 2
        while (k >= 0 && k >= nR - N_REPERES) {
            val r = reperes[(k % N_REPERES).toInt()] ?: break
            if (r.debut + r.longueur <= fin.debut - 8) { debut = r.debut + r.longueur; break }
            k--
        }
        // The closing flag not just now (another reading's frame): no mark rather than a wrong one.
        if (debut < 0 || l > 16) return
        derniereTrame = Repere(debut, (fin.debut - debut).toInt(), nom, ms = horloge())
    }

    /** The tones' spectrum as the demodulator measured it: [p] bins [pasHz] apart, bin [de] to [a] (excluded). */
    fun spectre(p: DoubleArray, pasHz: Double, de: Int, a: Int) {
        if (!actif || a <= de) return
        val n = a - de
        val reduit = maxOf(1, (n + N_SPECTRE - 1) / N_SPECTRE)
        val m = n / reduit
        val v = FloatArray(m) { i -> var s = 0.0; for (j in 0 until reduit) s += p[de + i * reduit + j]; (s / reduit).toFloat() }
        tons = VisuSignal.Spectre(de * pasHz + (reduit - 1) * pasHz / 2, pasHz * reduit, v)
    }

    /** The raw audio at [fs] Hz: its spectrum is worked out at each reading (the tones). */
    fun audio(x: Float, fs: Double) {
        if (!actif) return
        if (son.isEmpty()) son = FloatArray(N_AUDIO)
        fsAudio = fs
        son[(nA % N_AUDIO).toInt()] = x
        nA++
    }

    // ------------------------------------------------------------ the reader's side

    private var vuEcrits = -1L
    private var vivantMs = 0L

    /** Everything written so far (all rings): it grows while the demodulator runs. */
    val ecrits: Long get() = nP + nO + nB + nN

    /** When the rings last grew, as seen by the readings (0: never). */
    @Synchronized
    fun vivant(): Long {
        val e = ecrits
        if (e != vuEcrits) { if (vuEcrits >= 0 || e > 0) vivantMs = horloge(); vuEcrits = e }
        return vivantMs
    }

    /** What the rings hold now, analysed. */
    @Synchronized
    fun cliche(): VisuSignal {
        val np = nP; val no = nO; val nn = nN; val nt = nT; val nb = nB; val nr = nR; val na = nA
        fun <T> dernier(n: Long, cap: Int, f: (Int, Int) -> T): T {
            val m = minOf(n, cap.toLong()).toInt()
            return f(m, ((n - m) % cap).toInt())
        }
        val points = if (pI.isEmpty()) FloatArray(0) else dernier(np, N_POINTS) { m, d -> FloatArray(2 * m) { k -> val j = (d + k / 2) % N_POINTS; if (k % 2 == 0) pI[j] else pQ[j] } }
        val oeil = if (oX.isEmpty()) FloatArray(0) else dernier(no, N_OEIL) { m, d -> FloatArray(2 * m) { k -> val j = (d + k / 2) % N_OEIL; if (k % 2 == 0) oX[j] else oY[j] } }
        val niveaux = if (nv.isEmpty()) FloatArray(0) else dernier(nn, N_NIVEAUX) { m, d -> FloatArray(m) { nv[(d + it) % N_NIVEAUX] } }
        val frequences = if (fr.isEmpty()) FloatArray(0) else dernier(nF, N_NIVEAUX) { m, d -> FloatArray(m) { fr[(d + it) % N_NIVEAUX] } }
        val instants = if (tSym.isEmpty()) DoubleArray(0) else dernier(nt, N_NIVEAUX) { m, d -> DoubleArray(m) { tSym[(d + it) % N_NIVEAUX] } }
        val nBits = minOf(nb, N_BITS.toLong()).toInt()
        val premier = nb - nBits
        val bits = if (bt.isEmpty()) ByteArray(0) else ByteArray(nBits) { bt[((premier + it) % N_BITS).toInt()] }
        val rs = ArrayList<Repere>()
        for (k in maxOf(0L, nr - N_REPERES) until nr) reperes[(k % N_REPERES).toInt()]?.takeIf { it.debut >= premier }?.let { rs += it }
        val spectre = when {
            Vue.TONS !in vues -> null
            frequences.size >= 40 -> AnalyseSignal.histogramme(frequences, 120)?.let { VisuSignal.Spectre(it.de, it.pas, it.valeurs, mesure = true) }
            na > 0 && son.isNotEmpty() -> dernier(na, N_AUDIO) { m, d -> AnalyseSignal.spectre(FloatArray(m) { son[(d + it) % N_AUDIO] }, fsAudio, bandeTons.first, bandeTons.second) }
            else -> tons
        }
        return VisuSignal(cle, nom, vues, debit, fs, unite, lsbDabord, groupe, ordre, lecture, points, oeil, niveaux, instants,
            bits, premier, rs, derniereTrame, spectre, ecartAttenduHz, ecrits, vivant())
    }

    companion object {
        /** A viewer's reading keeps the taps writing this long. */
        const val BAIL_MS = 4_000L
        const val N_POINTS = 1024
        const val N_OEIL = 4096
        const val N_NIVEAUX = 2048
        const val N_BITS = 2048
        const val N_REPERES = 96
        const val N_AUDIO = 16_384
        const val N_SPECTRE = 400

        /** The usual sync words. */
        val DRAPEAU_HDLC = Motif("7E", 0x7E, 8)
    }
}
