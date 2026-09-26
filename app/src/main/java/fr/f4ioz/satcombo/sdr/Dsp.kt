/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Traitement du signal de la chaîne SDR, sans une ligne d'Android : la clé
 * fournit de l'IQ, ce fichier en fait de l'audio.
 *
 * Le découpage suit la logique d'un récepteur classique :
 *
 *   1 058 400 éch/s IQ    ← ce que sort la clé (24 × 44 100, choisi exprès)
 *        ↓ décimation 6, filtre large   « on jette la bande qui ne sert à rien »
 *     176 400 éch/s IQ    ← c'est là qu'on calcule le spectre et la cascade
 *        ↓ décimation 4, filtre de canal   « on ne garde que la station »
 *      44 100 éch/s IQ
 *        ↓ démodulation (FM étroite, BLU supérieure ou inférieure, AM)
 *      44 100 éch/s audio  ← exactement ce qu'attend le moteur SSTV
 *
 * Filtrer en deux temps n'est pas de la coquetterie : un filtre de canal à
 * 8 kHz appliqué directement à 1 MHz d'échantillonnage demanderait des
 * centaines de coefficients. En descendant d'abord d'un facteur 6, le même
 * filtre en coûte soixante.
 */
object Dsp {

    /** Débit demandé à la clé : 24 fois la fréquence audio, pour tomber juste. */
    const val RTL_RATE = 1_058_400

    /** Fréquence audio de sortie, celle du moteur SSTV et de l'encodeur MP3. */
    const val AUDIO_RATE = 44_100

    const val DECIM_1 = 6
    const val DECIM_2 = 4

    /**
     * Nombre de raies de l'analyseur de spectre. Quatre mille quatre-vingt-seize
     * raies sur 176 400 Hz font 43 Hz par raie : de quoi voir une porteuse et
     * la poser au bon endroit sur la cascade. Mille raies donnaient 172 Hz, et
     * à l'échelle de six kilohertz il ne restait qu'une trentaine de points
     * réels étirés sur toute la largeur de l'écran.
     */
    const val SPECTRUM_SIZE = 4096

    /**
     * Nombre de raies du panorama, l'analyseur branché sur le flux brut.
     *
     * Le spectre ordinaire est pris après le premier décimateur : il couvre
     * 176 400 Hz, ce qui suffit largement pour poser une porteuse sur une
     * cascade. Le transpondeur étroit de QO-100 en fait 492 000, plus les deux
     * balises qui l'encadrent : 500 000 en tout. Il ne rentre pas.
     *
     * On prend donc le flux tel qu'il sort de la clé, à 1 058 400 échantillons
     * par seconde, et **le transpondeur y tient toujours en entier** : la
     * demi-largeur de la fenêtre est de 529 200 Hz, contre 500 000 pour la
     * réglette. Autrement dit, où que soit accordée la clé à l'intérieur du
     * transpondeur, la totalité du plan de bande reste visible — il n'y a
     * jamais à reprogrammer la PLL pour voir l'autre bout. C'est une
     * coïncidence heureuse entre un débit choisi pour l'audio et une largeur
     * choisie par AMSAT-DL, et elle vaut d'être écrite : elle a dispensé
     * d'inventer un mode de balayage.
     *
     * Reste la marge : vingt-neuf kilohertz au pire, et les bords d'un tuner
     * de clé sont mous. Un correspondant à l'extrême bord de la bande peut
     * donc paraître plus faible qu'il n'est si l'on écoute à l'autre bout.
     * Cela ne trompe personne tant qu'on n'en fait pas une mesure.
     *
     * Seize mille raies sur 1 058 400 Hz font 64,6 Hz par raie : une note de
     * télégraphie occupe une raie, la balise BPSK médiane deux ou trois, et
     * l'interpolation parabolique de [fr.f4ioz.satcombo.domain.MesureBalise]
     * descend l'incertitude bien en dessous.
     */
    const val PANORAMA_SIZE = 16384

    /**
     * Réponse d'un passe-bas par fenêtrage d'un sinus cardinal (fenêtre de
     * Blackman). [taps] doit être impair pour que le filtre soit à phase
     * linéaire et sans retard fractionnaire.
     */
    fun lowPass(taps: Int, cutoffHz: Double, sampleRate: Double): FloatArray {
        val n = if (taps % 2 == 0) taps + 1 else taps
        val out = FloatArray(n)
        val fc = cutoffHz / sampleRate          // fréquence normalisée (0..0,5)
        val mid = (n - 1) / 2
        var sum = 0.0
        for (i in 0 until n) {
            val k = i - mid
            val sinc = if (k == 0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = 0.42 - 0.5 * cos(2.0 * PI * i / (n - 1)) + 0.08 * cos(4.0 * PI * i / (n - 1))
            val v = sinc * w
            out[i] = v.toFloat()
            sum += v
        }
        // Gain unitaire en continu : sinon chaque étage change le niveau.
        if (sum != 0.0) for (i in 0 until n) out[i] = (out[i] / sum).toFloat()
        return out
    }
}

/** Ce que la chaîne doit faire du signal une fois ramené en bande de base. */
enum class RxMode {
    /** FM étroite : phonie satellite, télémétrie, SSTV. */
    NFM,

    /** Bande latérale supérieure : transpondeurs linéaires en mode inversé ou non. */
    USB,

    /** Bande latérale inférieure. */
    LSB,

    /** Amplitude : balises, et plus tard les images météo APT. */
    AM
}

/**
 * Filtre décimateur complexe. Les coefficients ne sont évalués qu'aux instants
 * réellement produits : décimer par 6 divise le coût par 6, ce qui est tout
 * l'intérêt de mettre le filtre et la décimation dans le même objet.
 */
class ComplexDecimator(private val taps: FloatArray, private val factor: Int) {

    private val n = taps.size
    private var bufI = FloatArray(n - 1)
    private var bufQ = FloatArray(n - 1)
    /** Position, dans le prochain bloc, du premier échantillon qui produira une sortie. */
    private var phase = 0

    /** Nombre de sorties maximal pour un bloc de [count] entrées. */
    fun maxOut(count: Int): Int = count / factor + 2

    fun reset() {
        bufI.fill(0f); bufQ.fill(0f); phase = 0
    }

    fun process(
        inI: FloatArray, inQ: FloatArray, count: Int,
        outI: FloatArray, outQ: FloatArray
    ): Int {
        val need = n - 1 + count
        if (bufI.size < need) {
            bufI = bufI.copyOf(need)
            bufQ = bufQ.copyOf(need)
        }
        System.arraycopy(inI, 0, bufI, n - 1, count)
        System.arraycopy(inQ, 0, bufQ, n - 1, count)

        var out = 0
        var k = phase
        while (k < count) {
            var si = 0f
            var sq = 0f
            val base = k + n - 1
            for (t in 0 until n) {
                val c = taps[t]
                si += c * bufI[base - t]
                sq += c * bufQ[base - t]
            }
            outI[out] = si
            outQ[out] = sq
            out++
            k += factor
        }
        phase = k - count

        System.arraycopy(bufI, count, bufI, 0, n - 1)
        System.arraycopy(bufQ, count, bufQ, 0, n - 1)
        return out
    }
}

/**
 * Filtre passe-bande à coefficients complexes, la pièce qui permet la BLU.
 *
 * En bande de base complexe, une bande latérale supérieure occupe uniquement
 * les fréquences positives et une bande latérale inférieure uniquement les
 * négatives. Un filtre réel ne sait pas les distinguer : il traite +1 500 Hz et
 * −1 500 Hz de la même façon. En décalant la réponse d'un passe-bas par
 * h[k] = passe_bas[k]·e^{j2πf_c k/f_e}, on obtient un passe-bande qui n'existe
 * que d'un seul côté de zéro. Le signal filtré vaut alors m + j·m̂ (ou m − j·m̂
 * pour l'autre côté) : dans les deux cas la partie réelle est le message, et la
 * démodulation se réduit à jeter la partie imaginaire.
 *
 * C'est la méthode « par déphasage », la même que dans un transceiver, sauf
 * qu'ici le déphasage exact de 90° est obtenu par construction plutôt que par
 * des réseaux RC appairés.
 */
class ComplexBandpass(
    taps: Int,
    centerHz: Double,
    widthHz: Double,
    sampleRate: Double
) {
    private val hI: FloatArray
    private val hQ: FloatArray
    private val n: Int

    init {
        val lp = Dsp.lowPass(taps, widthHz / 2.0, sampleRate)
        n = lp.size
        hI = FloatArray(n)
        hQ = FloatArray(n)
        val mid = (n - 1) / 2
        val dp = 2.0 * PI * centerHz / sampleRate
        for (k in 0 until n) {
            val ph = dp * (k - mid)
            hI[k] = (lp[k] * cos(ph)).toFloat()
            hQ[k] = (lp[k] * sin(ph)).toFloat()
        }
    }

    private var bufI = FloatArray(n - 1)
    private var bufQ = FloatArray(n - 1)

    fun reset() { bufI.fill(0f); bufQ.fill(0f) }

    /**
     * Filtre [count] échantillons complexes et n'écrit que la partie réelle du
     * résultat dans [out] — c'est déjà l'audio BLU.
     */
    fun process(inI: FloatArray, inQ: FloatArray, count: Int, out: FloatArray): Int {
        val need = n - 1 + count
        if (bufI.size < need) {
            bufI = bufI.copyOf(need)
            bufQ = bufQ.copyOf(need)
        }
        System.arraycopy(inI, 0, bufI, n - 1, count)
        System.arraycopy(inQ, 0, bufQ, n - 1, count)
        for (k in 0 until count) {
            var re = 0f
            val base = k + n - 1
            for (t in 0 until n) {
                re += hI[t] * bufI[base - t] - hQ[t] * bufQ[base - t]
            }
            out[k] = re
        }
        System.arraycopy(bufI, count, bufI, 0, n - 1)
        System.arraycopy(bufQ, count, bufQ, 0, n - 1)
        return count
    }
}

/**
 * Discriminateur de fréquence. La FM porte l'information dans la dérivée de la
 * phase, donc l'argument de z[n]·conj(z[n-1]) donne directement l'écart de
 * fréquence instantané, indépendamment de l'amplitude — c'est pour cela qu'une
 * liaison FM est insensible au niveau tant qu'elle reste au-dessus du seuil.
 */
class FmDiscriminator(private val sampleRate: Double, private val maxDeviationHz: Double) {

    private var prevI = 0f
    private var prevQ = 0f
    private val hzPerRad = sampleRate / (2.0 * PI)

    /** Niveau moyen du dernier bloc, en dB pleine échelle (négatif). */
    var levelDb: Float = -120f
        private set

    fun reset() { prevI = 0f; prevQ = 0f; levelDb = -120f }

    /** Démodule [count] échantillons complexes en audio 16 bits. */
    fun process(inI: FloatArray, inQ: FloatArray, count: Int, out: ShortArray): Int {
        var mag = 0.0
        for (k in 0 until count) {
            val i = inI[k]
            val q = inQ[k]
            val re = i * prevI + q * prevQ
            val im = q * prevI - i * prevQ
            prevI = i; prevQ = q
            val hz = atan2(im.toDouble(), re.toDouble()) * hzPerRad
            val v = (hz / maxDeviationHz).coerceIn(-1.0, 1.0)
            out[k] = (v * 26_000).toInt().toShort()
            mag += sqrt((i * i + q * q).toDouble())
        }
        if (count > 0) {
            val avg = mag / count
            levelDb = if (avg <= 1e-9) -120f else (20.0 * log10(avg)).toFloat()
        }
        return count
    }
}

/**
 * Désaccentuation audio. La FM commerciale et amateur accentue les aigus à
 * l'émission ; sans le filtre inverse, la réception siffle.
 *
 * Le moteur SSTV n'en souffre pas : son propre discriminateur ne regarde que
 * la fréquence, pas le niveau. Le réglage reste néanmoins débrayable, parce
 * qu'un signal télémétrique ou une balise se lisent mieux à plat.
 */
class Deemphasis(tauMicros: Double, sampleRate: Double) {
    private val a = kotlin.math.exp(-1.0 / (sampleRate * tauMicros * 1e-6)).toFloat()

    /**
     * Rattrapage de niveau, calculé et non plus deviné.
     *
     * Le filtre a pour réponse H(f) = (1 − a) / |1 − a·e^{−jω}|. Multiplier
     * bêtement par trois laissait mille hertz onze décibels trop bas : une
     * tonalité d'appel à 1 750 Hz devenait inaudible alors que le signal était
     * parfaitement reçu. On normalise donc à 1 kHz, la référence des mesures de
     * modulation, pour que la voix sorte au même niveau qu'à plat.
     */
    private val makeup: Float = run {
        val ad = a.toDouble()
        val g = 1.0 - ad
        val w = 2.0 * PI * 1_000.0 / sampleRate
        val x = 1.0 - ad * cos(w)
        val y0 = ad * sin(w)
        val h = g / sqrt(x * x + y0 * y0)
        if (h <= 1e-9) 1f else (1.0 / h).coerceIn(1.0, 64.0).toFloat()
    }

    /** Gain de rattrapage réellement appliqué (essais). */
    val makeupGain: Float get() = makeup

    private var y = 0f
    fun reset() { y = 0f }
    fun process(buf: ShortArray, count: Int) {
        val g = 1f - a
        for (k in 0 until count) {
            y = a * y + g * buf[k]
            buf[k] = (y * makeup).coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/** Retire la composante continue résiduelle du discriminateur (désaccord). */
class DcBlock(private val alpha: Float = 0.9995f) {
    private var xPrev = 0f
    private var yPrev = 0f
    fun reset() { xPrev = 0f; yPrev = 0f }
    fun process(buf: ShortArray, count: Int) {
        for (k in 0 until count) {
            val x = buf[k].toFloat()
            val y = x - xPrev + alpha * yPrev
            xPrev = x; yPrev = y
            buf[k] = y.coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/**
 * Commande automatique de gain audio.
 *
 * En FM le niveau sonore ne dépend pas du signal reçu ; en BLU et en AM si, et
 * dans des proportions énormes : entre un correspondant qui passe au zénith
 * avec dix watts et un autre au ras de l'horizon, il y a facilement quarante
 * décibels. Sans CAG, on passe le passage à courir après le bouton de volume.
 *
 * L'enveloppe monte vite (on ne veut pas saturer sur une syllabe) et redescend
 * lentement (on ne veut pas que le souffle remonte entre deux mots).
 */
class AudioAgc(
    private val target: Float = 8_000f,
    private val maxGain: Float = 20_000_000f
) {
    private var env = 0f
    private val attack = 0.02f
    private val release = 0.0003f

    fun reset() { env = 0f }

    /** Convertit [count] échantillons flottants en audio 16 bits à niveau tenu. */
    fun process(buf: FloatArray, count: Int, out: ShortArray) {
        for (k in 0 until count) {
            val x = buf[k]
            val a = abs(x)
            env += if (a > env) attack * (a - env) else release * (a - env)
            val g = if (env <= 1e-7f) maxGain else (target / env).coerceIn(1f, maxGain)
            out[k] = (x * g).coerceIn(-32000f, 32000f).toInt().toShort()
        }
    }
}

/**
 * Silencieux. Coupe la sortie tant que le niveau reste sous le seuil, avec une
 * hystérésis pour que le souffle ne fasse pas battre la porte à chaque syllabe.
 *
 * Seuil à −120 dB : la porte reste ouverte en permanence, c'est le réglage
 * « silencieux coupé ».
 */
class Squelch(var thresholdDb: Float = -120f, private val hysteresisDb: Float = 4f) {

    var open: Boolean = true
        private set

    fun reset() { open = true }

    /** Met la porte à jour pour un bloc au niveau [levelDb] ; renvoie son état. */
    fun update(levelDb: Float): Boolean {
        open = if (open) levelDb > thresholdDb - hysteresisDb else levelDb > thresholdDb
        return open
    }
}

/**
 * Transformée de Fourier rapide, radix 2, sur place.
 *
 * Écrite ici plutôt qu'empruntée à une bibliothèque : la chaîne SDR entière
 * tient dans du Kotlin pur testable sur machine de bureau, et une FFT de mille
 * points fait vingt lignes.
 */
object Fft {

    /** Transforme [re]/[im] sur place. La taille doit être une puissance de deux. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return
        require(n and (n - 1) == 0) { "taille non puissance de deux : $n" }

        // Permutation par inversion de bits.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Analyseur de spectre : accumule des échantillons complexes et produit, une
 * fois la fenêtre pleine, la puissance par raie en dB pleine échelle.
 *
 * Le résultat est rangé « à l'endroit » : l'indice 0 correspond à −f_e/2, le
 * milieu à la fréquence d'accord, le dernier à +f_e/2. C'est ce que l'œil
 * attend d'un panoramique, et cela évite à l'affichage de faire la gymnastique.
 */
class SpectrumAnalyzer(val size: Int = 1024) {

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val win = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }
    private var fill = 0

    /** Puissance par raie, en dB pleine échelle, de −f_e/2 à +f_e/2. */
    val magDb = FloatArray(size) { -120f }

    /** Nombre de trames complètes calculées depuis le dernier [reset]. */
    var frames: Long = 0
        private set

    fun reset() { fill = 0; frames = 0; magDb.fill(-120f) }

    /**
     * Repart d'une trame vide sans effacer l'affichage.
     *
     * L'appelant n'alimente l'analyseur que dix fois par seconde : sans cet
     * appel, une trame se retrouvait recollée à partir de morceaux pris à
     * quatre-vingt-dix millisecondes d'intervalle, et la FFT d'un signal ainsi
     * découpé n'a plus de sens — la porteuse s'étalait au lieu de faire une
     * raie.
     */
    fun begin() { fill = 0 }

    /**
     * Empile [count] échantillons complexes. Renvoie vrai si une trame vient
     * d'être calculée, auquel cas [magDb] est à jour.
     */
    fun push(inI: FloatArray, inQ: FloatArray, count: Int): Boolean {
        var done = false
        var k = 0
        while (k < count) {
            val take = minOf(size - fill, count - k)
            for (t in 0 until take) {
                re[fill + t] = inI[k + t].toDouble()
                im[fill + t] = inQ[k + t].toDouble()
            }
            fill += take
            k += take
            if (fill == size) {
                compute()
                fill = 0
                done = true
            }
        }
        return done
    }

    private fun compute() {
        for (i in 0 until size) {
            re[i] *= win[i]
            im[i] *= win[i]
        }
        Fft.transform(re, im)
        val half = size / 2
        val norm = 1.0 / (size * 0.5)   // 0,5 : gain moyen de la fenêtre de Hann
        for (i in 0 until size) {
            // fftshift : la raie 0 de la FFT est la fréquence d'accord, elle va
            // au milieu ; les raies au-delà de size/2 sont les négatives.
            val src = if (i < half) i + half else i - half
            val m = sqrt(re[src] * re[src] + im[src] * im[src]) * norm
            magDb[i] = if (m <= 1e-9) -120f else (20.0 * log10(m)).toFloat()
        }
        frames++
    }
}

/**
 * Chaîne complète : octets IQ bruts de la clé → audio 44 100 Hz mono, dans le
 * mode demandé.
 *
 * [offsetHz] décale la fréquence d'écoute à l'intérieur de la bande reçue sans
 * toucher à la PLL du tuner. Cela sert à trois choses : rattraper le pas fini
 * du synthétiseur (une centaine de hertz, invisible en FM mais pas en BLU),
 * suivre un correspondant qui dérive dans le transpondeur, et permettre de
 * poser le doigt sur la cascade pour s'accorder. Le décalage est appliqué sur
 * l'IQ brut, avant le premier filtre : après le décalage la station visée se
 * retrouve à zéro hertz et toute la chaîne fonctionne comme d'habitude.
 */
class RxChain(
    private val rtlRate: Double = Dsp.RTL_RATE.toDouble(),
    maxDeviationHz: Double = 5_000.0
) {
    private val stage1Rate = rtlRate / Dsp.DECIM_1
    private val stage2Rate = stage1Rate / Dsp.DECIM_2

    // Étage 1 : on ne cherche pas la sélectivité, seulement à ne pas replier
    // de bruit dans la bande utile en descendant d'un facteur 6.
    // Le filtre est plus raide et plus large qu'avant : le décalage fin se fait
    // maintenant après cet étage, donc tout ce que l'utilisateur peut viser à
    // l'écran doit y survivre. À 2,9 la bande utile s'arrêtait à ±30 kHz.
    private val dec1 = ComplexDecimator(
        Dsp.lowPass(95, rtlRate / Dsp.DECIM_1 / 2.4, rtlRate), Dsp.DECIM_1)

    // Étage 2 : le filtre de canal, reconstruit quand la largeur change.
    private var dec2Cutoff = 8_000.0
    private var dec2 = ComplexDecimator(
        Dsp.lowPass(63, dec2Cutoff, stage1Rate), Dsp.DECIM_2)

    private val disc = FmDiscriminator(stage2Rate, maxDeviationHz)
    private val dc = DcBlock()
    private val deemph = Deemphasis(750.0, stage2Rate)
    private val agc = AudioAgc()

    /** Filtre de bande latérale, reconstruit quand le mode ou la largeur change. */
    private var ssb: ComplexBandpass? = null
    private var ssbKey = ""

    /** Mode de démodulation. */
    var mode: RxMode = RxMode.NFM

    /**
     * Désaccentuation. Coupée par défaut : elle n'a de sens qu'en FM à large
     * bande (radiodiffusion), et sur un répéteur ou une image SSTV elle ne fait
     * qu'écraser les aigus. Les récepteurs sérieux la réservent à la WFM.
     */
    var deemphasis: Boolean = false

    /** Décalage fin appliqué en logiciel, en hertz. */
    var offsetHz: Double = 0.0

    /**
     * Décalage Doppler encaissé en logiciel, en hertz, en plus de [offsetHz].
     *
     * Les deux sont séparés parce qu'ils n'appartiennent pas à la même main :
     * [offsetHz] est le doigt de l'opérateur sur la cascade, celui-ci est le
     * suivi automatique. Les additionner dans une seule variable ferait
     * disparaître le réglage manuel à la première seconde de passage.
     */
    var dopplerFineHz: Double = 0.0

    /**
     * Largeur de canal demandée, en hertz. Zéro veut dire « au mode de
     * décider » : 16 kHz en FM étroite, 2,4 kHz en BLU, 6 kHz en AM.
     */
    var bandwidthHz: Double = 0.0

    /** Silencieux, en dB pleine échelle. −120 dB veut dire « coupé ». */
    val squelch = Squelch()

    /** Analyseur de spectre, alimenté à la demande sur la sortie du premier étage. */
    val spectrum = SpectrumAnalyzer(Dsp.SPECTRUM_SIZE)

    /** Vrai tant qu'on remplit une trame de spectre entamée. */
    private var collecting = false

    /**
     * Analyseur du flux brut, avant toute décimation : tout ce que la clé
     * reçoit, d'un bord à l'autre. Voir [Dsp.PANORAMA_SIZE] pour la raison
     * d'être de ce second analyseur.
     */
    val panorama = SpectrumAnalyzer(Dsp.PANORAMA_SIZE)

    /** Vrai tant qu'on remplit une trame de panorama entamée. */
    private var collectingPan = false

    /** Largeur, en hertz, couverte par le spectre (débit du premier étage). */
    val spectrumSpanHz: Double get() = stage1Rate

    /** Largeur, en hertz, couverte par le panorama : le débit de la clé. */
    val panoramaSpanHz: Double get() = rtlRate

    private var nco = 0.0
    private var level = -120f
    private var amDc = 0f
    private var clip = 0f
    private var peak = 0f

    /**
     * Part des échantillons bruts collés aux butées du convertisseur. Au-delà
     * de quelques pour mille, l'étage d'entrée de la clé est saturé : le
     * souffle disparaît et la modulation avec — exactement ce qu'on observe
     * quand un émetteur voisin arrose le dongle. Aucun réglage logiciel ne
     * rattrape cela, il faut baisser le gain ou éloigner l'antenne.
     */
    val clipRatio: Float get() = clip

    /** Crête audio de sortie, ramenée à 0..1. Sert de vu-mètre. */
    val audioPeak: Float get() = peak

    /**
     * Fréquence, en hertz relatifs à l'accord, de la raie la plus forte entre
     * [fromHz] et [toHz] d'après la dernière trame de spectre.
     */
    fun peakOffsetHz(fromHz: Double, toHz: Double): Double {
        val n = spectrum.size
        if (spectrum.frames == 0L) return 0.0
        val hzPerBin = stage1Rate / n
        var best = -1
        var bestV = -400f
        for (i in 0 until n) {
            val f = (i - n / 2) * hzPerBin
            if (f < fromHz || f > toHz) continue
            val v = spectrum.magDb[i]
            if (v > bestV) { bestV = v; best = i }
        }
        if (best < 0) return 0.0
        return (best - n / 2) * hzPerBin
    }

    /**
     * Centre de gravité du signal dans le spectre, en hertz relatifs à
     * l'accord de la clé. C'est l'accord automatique de la 18.5, et il est né
     * au banc de mesure des radiosondes.
     *
     * Chercher la raie la plus forte — ce que fait [peakOffsetHz] — convient à
     * une porteuse, et à elle seule. Une modulation par déplacement de
     * fréquence n'en a pas : elle a deux bosses écartées d'une excursion, et
     * viser la plus haute des deux revient à s'accorder systématiquement à
     * côté. La moyenne pondérée par la puissance, elle, tombe entre les deux
     * bosses, c'est-à-dire là où serait la porteuse s'il y en avait une.
     *
     * Trois précautions, et chacune a coûté une mesure :
     *
     *  - Le seuil relatif [thresholdDb] écarte le plancher de bruit. Sans lui
     *    les milliers de raies vides de la bande, toutes également faibles mais
     *    innombrables, tirent la moyenne vers le milieu de la fenêtre.
     *  - [dcNotchHz] saute les raies collées au zéro. Elles ne viennent pas de
     *    la station mais de la clé : tout récepteur à conversion directe laisse
     *    une raie de continu au centre de sa bande, et comme elle est forte,
     *    elle rabotait l'estimation d'un bon sixième — mille six cents hertz
     *    annoncés là où il y en avait deux mille.
     *  - La mesure se fait en deux passes. La première cherche large et rend
     *    une position approchée ; la seconde recommence dans une fenêtre de
     *    [narrowHz] autour d'elle, où il ne reste plus que la station. Le bruit
     *    résiduel d'une fenêtre large pèse peu mais tire toujours vers son
     *    centre, d'autant plus que la station en est loin.
     *
     * Le spectre est prélevé avant le mélangeur d'accord fin : le chiffre rendu
     * est donc absolu par rapport à la fréquence affichée par la clé, et
     * s'écrit directement dans [offsetHz] — il ne s'y ajoute pas. C'est ce qui
     * rend le recentrage continu inoffensif : une fois accordé, il rend zéro.
     *
     * Rend zéro quand rien ne dépasse le seuil : pas de signal, pas d'accord,
     * et surtout pas de dérive vers le bruit.
     */
    fun centroidOffsetHz(
        searchHz: Double = 25_000.0,
        thresholdDb: Double = 6.0,
        dcNotchHz: Double = 400.0,
        narrowHz: Double = 4_000.0,
        /**
         * Milieu de la fenêtre de recherche, en hertz relatifs à l'accord.
         *
         * Zéro — l'ancien comportement — cherche autour de la clé, ce qui est
         * ce qu'il faut pour rattraper une radiosonde dans une bande vide. Pour
         * caler sur le correspondant qu'on écoute déjà, il faut au contraire
         * chercher autour de l'endroit où l'on est **posé** : sinon la mesure
         * saute sur la station voisine plus forte au premier silence.
         */
        centreHz: Double = 0.0
    ): Double {
        if (spectrum.frames == 0L) return 0.0
        val n = spectrum.size
        val hzPerBin = stage1Rate / n
        val mag = spectrum.magDb

        val basHz = centreHz - searchHz
        val hautHz = centreHz + searchHz

        var floorDb = 0.0
        var count = 0
        for (i in 0 until n) {
            val f = (i - n / 2) * hzPerBin
            if (f < basHz || f > hautHz) continue
            floorDb += mag[i]; count++
        }
        if (count == 0) return 0.0
        floorDb /= count

        fun pass(loHz: Double, hiHz: Double): Double {
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                val f = (i - n / 2) * hzPerBin
                if (f < loHz || f > hiHz) continue
                if (f > -dcNotchHz && f < dcNotchHz) continue
                val d = mag[i] - floorDb
                if (d < thresholdDb) continue
                val w = 10.0.pow(d / 10.0)
                num += w * f; den += w
            }
            return if (den <= 0.0) Double.NaN else num / den
        }

        val rough = pass(basHz, hautHz)
        if (rough.isNaN()) return 0.0
        val fine = pass(rough - narrowHz, rough + narrowHz)
        return if (fine.isNaN()) rough else fine
    }

    /** Niveau du signal dans le canal, en dB pleine échelle. */
    val levelDb: Float get() = level

    /** Débit audio de sortie, arrondi à l'entier le plus proche. */
    val audioRate: Int get() = Math.round(stage2Rate).toInt()

    /** Largeur de canal réellement appliquée, en hertz. */
    val effectiveBandwidthHz: Double
        get() {
            val asked = bandwidthHz
            if (asked > 0.0) return asked.coerceIn(500.0, 24_000.0)
            return when (mode) {
                RxMode.NFM -> 16_000.0
                RxMode.USB, RxMode.LSB -> 2_400.0
                RxMode.AM -> 6_000.0
            }
        }

    private var aI = FloatArray(0)
    private var aQ = FloatArray(0)
    private var bI = FloatArray(0)
    private var bQ = FloatArray(0)
    private var cI = FloatArray(0)
    private var cQ = FloatArray(0)
    private var fl = FloatArray(0)

    fun reset() {
        dec1.reset(); dec2.reset(); disc.reset(); dc.reset(); deemph.reset()
        agc.reset(); squelch.reset(); ssb?.reset(); spectrum.reset()
        nco = 0.0; level = -120f; amDc = 0f
        clip = 0f; peak = 0f; collecting = false
    }

    /** Taille d'un tampon audio suffisant pour [iqBytes] octets d'entrée. */
    fun maxAudio(iqBytes: Int): Int = iqBytes / 2 / (Dsp.DECIM_1 * Dsp.DECIM_2) + 4

    /** Reconstruit les filtres si le mode ou la largeur ont bougé. */
    private fun retune() {
        val bw = effectiveBandwidthHz
        // En BLU le filtre de canal reste large : c'est le passe-bande complexe
        // qui fait la sélectivité, et il travaille mieux avec de la marge.
        val wanted = when (mode) {
            RxMode.NFM -> (bw / 2.0).coerceIn(2_500.0, 20_000.0)
            RxMode.USB, RxMode.LSB -> 6_000.0
            RxMode.AM -> (bw / 2.0).coerceIn(1_500.0, 20_000.0)
        }
        if (abs(wanted - dec2Cutoff) > 1.0) {
            dec2Cutoff = wanted
            dec2 = ComplexDecimator(Dsp.lowPass(63, dec2Cutoff, stage1Rate), Dsp.DECIM_2)
        }
        if (mode == RxMode.USB || mode == RxMode.LSB) {
            val sign = if (mode == RxMode.USB) 1.0 else -1.0
            // Bande passante de 300 Hz à 300 + largeur : le grave n'apporte rien
            // en phonie et coûte cher en souffle.
            val center = sign * (300.0 + bw / 2.0)
            val key = "$center/$bw"
            if (key != ssbKey) {
                ssbKey = key
                ssb = ComplexBandpass(255, center, bw, stage2Rate)
            }
        } else if (ssbKey.isNotEmpty()) {
            ssbKey = ""
            ssb = null
        }
    }

    /**
     * Traite [len] octets d'IQ (I puis Q, entiers 8 bits non signés) et écrit
     * l'audio dans [out]. Renvoie le nombre d'échantillons audio produits.
     *
     * Si [feedSpectrum] est vrai, la sortie du premier étage part aussi dans
     * l'analyseur de spectre. L'appelant ne le demande que dix fois par seconde :
     * une FFT de mille points par bloc reçu serait du calcul jeté à l'écran.
     *
     * [feedPanorama] fait de même avec le flux brut, pour l'analyseur large.
     * Il se demande encore plus rarement — quelques fois par seconde — parce
     * qu'une FFT de seize mille points coûte quatre fois celle du spectre et
     * qu'un panorama de transpondeur n'a rien d'un signal qui file : la
     * répartition des stations dans le transpondeur change à l'échelle de la
     * minute, pas de l'image.
     */
    fun process(
        iq: ByteArray,
        len: Int,
        out: ShortArray,
        feedSpectrum: Boolean = false,
        feedPanorama: Boolean = false,
    ): Int {
        val n = len / 2
        if (n == 0) return 0
        retune()
        if (aI.size < n) { aI = FloatArray(n); aQ = FloatArray(n) }

        // Conversion des octets bruts, et comptage de la saturation au passage.
        var clipped = 0
        for (k in 0 until n) {
            val bi = iq[2 * k].toInt() and 0xff
            val bq = iq[2 * k + 1].toInt() and 0xff
            if (bi <= 2 || bi >= 253) clipped++
            if (bq <= 2 || bq >= 253) clipped++
            aI[k] = (bi - 127.5f) / 127.5f
            aQ[k] = (bq - 127.5f) / 127.5f
        }
        clip = clipped.toFloat() / (2 * n)

        // Le panorama se prend ici, sur le flux brut, avant le premier
        // décimateur et bien avant le mélangeur d'accord fin. Il est donc
        // ancré sur la fréquence programmée dans la PLL, et sur elle seule :
        // c'est ce qui permet de tracer une échelle en fréquences absolues du
        // ciel et d'y poser des repères fixes. Un panorama pris après le
        // mélangeur glisserait sous les repères à chaque coup de curseur.
        if (feedPanorama && !collectingPan) { panorama.begin(); collectingPan = true }
        if (collectingPan && panorama.push(aI, aQ, n)) collectingPan = false

        val m1 = dec1.maxOut(n)
        if (bI.size < m1) { bI = FloatArray(m1); bQ = FloatArray(m1) }
        val n1 = dec1.process(aI, aQ, n, bI, bQ)

        // Le spectre est prélevé AVANT le décalage fin : c'était là le défaut
        // qui rendait l'accord impossible. Le mélangeur déplaçait tout le
        // signal, donc la cascade glissait avec le curseur, et l'écart affiché
        // se cumulait au lieu de se réduire. Ici la cascade reste fixe, ancrée
        // sur la fréquence d'accord de la clé, et le curseur désigne enfin un
        // endroit réel du spectre.
        if (feedSpectrum && !collecting) { spectrum.begin(); collecting = true }
        if (collecting && spectrum.push(bI, bQ, n1)) collecting = false

        // Décalage fin : on descend la station visée sur zéro. Le signe est
        // celui du bon sens — « + 2 000 Hz » veut dire « écouter deux
        // kilohertz au-dessus de la fréquence affichée ».
        val shiftHz = offsetHz + dopplerFineHz
        if (shiftHz != 0.0) {
            val dp = 2.0 * PI * shiftHz / stage1Rate
            for (k in 0 until n1) {
                val i = bI[k]; val q = bQ[k]
                val c = cos(nco).toFloat()
                val sn = sin(nco).toFloat()
                bI[k] = i * c + q * sn
                bQ[k] = q * c - i * sn
                nco += dp
                if (nco > PI) nco -= 2.0 * PI else if (nco < -PI) nco += 2.0 * PI
            }
        }

        val m2 = dec2.maxOut(n1)
        if (cI.size < m2) { cI = FloatArray(m2); cQ = FloatArray(m2) }
        val n2 = dec2.process(bI, bQ, n1, cI, cQ)

        val count = minOf(n2, out.size)
        if (count <= 0) return 0

        // Niveau mesuré sur la bande de base, valable dans tous les modes.
        var mag = 0.0
        for (k in 0 until count) {
            val i = cI[k]; val q = cQ[k]
            mag += sqrt((i * i + q * q).toDouble())
        }
        val avg = mag / count
        level = if (avg <= 1e-9) -120f else (20.0 * log10(avg)).toFloat()

        val produced = when (mode) {
            RxMode.NFM -> {
                val p = disc.process(cI, cQ, count, out)
                dc.process(out, p)
                if (deemphasis) deemph.process(out, p)
                p
            }
            RxMode.USB, RxMode.LSB -> {
                if (fl.size < count) fl = FloatArray(count)
                val f = ssb ?: ComplexBandpass(255, 1_500.0, 2_400.0, stage2Rate).also { ssb = it }
                f.process(cI, cQ, count, fl)
                agc.process(fl, count, out)
                count
            }
            RxMode.AM -> {
                if (fl.size < count) fl = FloatArray(count)
                for (k in 0 until count) {
                    val i = cI[k]; val q = cQ[k]
                    val a = sqrt(i * i + q * q)
                    // La porteuse est une continue : on la retire, sinon elle
                    // sature l'étage audio sans rien apporter à l'oreille.
                    amDc += 0.0005f * (a - amDc)
                    fl[k] = a - amDc
                }
                agc.process(fl, count, out)
                count
            }
        }

        if (!squelch.update(level)) {
            java.util.Arrays.fill(out, 0, produced, 0)
        }
        var mx = 0
        for (k in 0 until produced) {
            val v = kotlin.math.abs(out[k].toInt())
            if (v > mx) mx = v
        }
        peak = mx / 32768f
        return produced
    }
}

/**
 * Ancien nom de la chaîne, du temps où elle ne savait faire que la FM étroite.
 * Conservé pour ne pas casser les appels et les tests existants.
 */
typealias NfmChain = RxChain

/**
 * Mesure de spectre par bandes, sans FFT. Gardée parce qu'elle travaille
 * directement sur les octets bruts et sert de contrôle indépendant de
 * [SpectrumAnalyzer] : deux méthodes qui tombent d'accord sur la position d'une
 * porteuse valent mieux qu'une seule qu'on croit sur parole.
 */
class SpectrumProbe(private val bins: Int = 64) {

    private val acc = FloatArray(bins)

    /** Puissance par bande, en dB pleine échelle, du plus bas au plus haut. */
    val bands = FloatArray(bins) { -120f }

    /**
     * Analyse un extrait d'IQ par transformée de Goertzel sur [bins] bandes
     * réparties sur toute la largeur reçue. On travaille sur un extrait court
     * et espacé : l'affichage se rafraîchit dix fois par seconde, inutile de
     * passer tout le flux dedans.
     */
    fun analyse(iq: ByteArray, len: Int, stride: Int = 4) {
        val n = len / 2
        if (n < bins * 4) return
        acc.fill(0f)
        for (b in 0 until bins) {
            // Bande b centrée sur (b/bins - 0,5) fois le débit.
            val f = (b.toDouble() / bins) - 0.5
            var re = 0.0
            var im = 0.0
            var ph = 0.0
            val dp = -2.0 * PI * f * stride
            var k = 0
            var used = 0
            while (k < n) {
                val i = ((iq[2 * k].toInt() and 0xff) - 127.5)
                val q = ((iq[2 * k + 1].toInt() and 0xff) - 127.5)
                val c = cos(ph)
                val s = sin(ph)
                re += i * c - q * s
                im += i * s + q * c
                ph += dp
                k += stride
                used++
            }
            val mag = sqrt(re * re + im * im) / (used.coerceAtLeast(1) * 127.5)
            bands[b] = if (mag <= 1e-9) -120f else (20.0 * log10(mag)).toFloat()
        }
    }
}
