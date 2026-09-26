/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.apt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * La géométrie d'une ligne APT.
 *
 * Les satellites NOAA descendent leurs images sur 137 MHz en modulant en
 * amplitude une sous-porteuse de 2400 Hz. Le débit est fixe et connu depuis
 * 1978 : 4160 mots par seconde, deux lignes par seconde, donc 2080 mots par
 * ligne. Une ligne porte deux images côte à côte — le canal A (visible ou
 * proche infrarouge selon l'heure) et le canal B (infrarouge thermique) —
 * chacune précédée d'une salve de synchronisation et suivie d'une échelle de
 * télémétrie.
 *
 * Tout est ici en constantes plutôt qu'en nombres semés dans le code : le jour
 * où une ligne se décale d'un mot, il vaut mieux avoir un seul endroit à
 * relire.
 *
 * Aucun import Android : le décodage complet se vérifie sur machine, ce qui
 * compte d'autant plus qu'il n'y a pas de passage NOAA à la demande.
 */
object Apt {

    /** Mots par seconde. Fixé par la norme, jamais négocié. */
    const val WORD_RATE = 4160

    /** Mots par ligne — donc deux lignes par seconde. */
    const val WORDS_PER_LINE = 2080

    /** Sous-porteuse modulée en amplitude, en hertz. */
    const val SUBCARRIER_HZ = 2400.0

    /** Longueur d'une salve de synchronisation, en mots. */
    const val SYNC_LEN = 39

    const val SYNC_A = 0
    const val SPACE_A = 39
    const val SPACE_LEN = 47
    const val VIDEO_A = 86
    const val VIDEO_LEN = 909
    const val TELEMETRY_A = 995
    const val TELEMETRY_LEN = 45
    const val SYNC_B = 1040
    const val SPACE_B = 1079
    const val VIDEO_B = 1126
    const val TELEMETRY_B = 2035

    /**
     * La salve A : sept créneaux à 1040 Hz, soit quatre mots par cycle.
     * Quatre mots noirs devant, le reste au noir derrière, 39 mots en tout.
     */
    val SYNC_A_PATTERN: FloatArray = buildSync(high = 2, low = 2)

    /** La salve B : sept créneaux à 832 Hz, soit cinq mots par cycle. */
    val SYNC_B_PATTERN: FloatArray = buildSync(high = 3, low = 2)

    private fun buildSync(high: Int, low: Int): FloatArray {
        val out = FloatArray(SYNC_LEN)
        var i = 4
        repeat(7) {
            repeat(high) { if (i < SYNC_LEN) out[i] = 1f; i++ }
            i += low
        }
        return out
    }

    /** Extrait les 909 mots d'un canal dans une ligne complète. */
    fun channel(line: FloatArray, b: Boolean): FloatArray {
        val from = if (b) VIDEO_B else VIDEO_A
        if (line.size < from + VIDEO_LEN) return FloatArray(VIDEO_LEN)
        return line.copyOfRange(from, from + VIDEO_LEN)
    }

    /**
     * Les deux bornes de contraste, prises sur les seuls mots d'image.
     *
     * Un étalement bête entre le minimum et le maximum donnerait une image
     * délavée : une seule ligne de parasites suffit à occuper toute l'échelle.
     * On prend donc le premier et le quatre-vingt-dix-neuvième centile, ce qui
     * accepte de brûler un pour cent des pixels aux deux bouts en échange d'un
     * contraste qui tient debout. Les salves de synchronisation et la
     * télémétrie sont écartées du calcul — elles sont toujours au blanc ou au
     * noir franc et fausseraient les bornes.
     */
    fun levels(lines: List<FloatArray>): FloatArray {
        if (lines.isEmpty()) return floatArrayOf(0f, 1f)
        val total = lines.size * VIDEO_LEN * 2
        val stride = max(1, total / 60_000)
        val out = FloatArray(total / stride + 4)
        var idx = 0
        var n = 0
        for (line in lines) {
            if (line.size < VIDEO_B + VIDEO_LEN) continue
            for (k in 0 until VIDEO_LEN) {
                if (idx++ % stride == 0 && n < out.size) out[n++] = line[VIDEO_A + k]
                if (idx++ % stride == 0 && n < out.size) out[n++] = line[VIDEO_B + k]
            }
        }
        if (n < 2) return floatArrayOf(0f, 1f)
        val used = out.copyOf(n)
        used.sort()
        val lo = used[(n * 0.01f).toInt().coerceIn(0, n - 1)]
        var hi = used[(n * 0.99f).toInt().coerceIn(0, n - 1)]
        if (hi <= lo) hi = lo + 1e-6f
        return floatArrayOf(lo, hi)
    }

    /** Ramène une ligne brute sur 0..255 avec les bornes données. */
    fun gray(line: FloatArray, lo: Float, hi: Float): IntArray {
        val span = if (hi > lo) hi - lo else 1e-6f
        val out = IntArray(line.size)
        for (i in line.indices) {
            val v = (line[i] - lo) / span * 255f
            out[i] = when {
                v.isNaN() -> 0
                v < 0f -> 0
                v > 255f -> 255
                else -> v.toInt()
            }
        }
        return out
    }
}

/**
 * Le décodeur APT : du son vers des lignes d'image.
 *
 * Le travail se fait en trois temps, et chacun a sa raison d'être.
 *
 * D'abord la démodulation. Le signal reçu est une sous-porteuse à 2400 Hz dont
 * l'amplitude porte l'image. On la multiplie par un cosinus et un sinus à cette
 * même fréquence, on filtre le tout en passe-bas, et le module du couple obtenu
 * donne l'enveloppe. Ce détour par les deux voies évite d'avoir à connaître la
 * phase du signal reçu — ce qui serait illusoire avec une clé SDR ou un simple
 * câble entre un récepteur et le téléphone.
 *
 * Ensuite le rééchantillonnage. L'enveloppe arrive au rythme de la carte son
 * (44 100 Hz le plus souvent) et l'image se lit à 4160 mots par seconde ; on
 * interpole linéairement entre deux échantillons pour tomber sur chaque mot.
 *
 * Enfin la mise en ligne. Chaque ligne commence par une salve de
 * synchronisation reconnaissable ; on la cherche par corrélation. La première
 * fois on fouille une ligne entière, ensuite on ne regarde qu'à quarante mots
 * autour de l'endroit attendu — assez pour suivre la dérive d'une horloge de
 * téléphone, trop peu pour partir en vrille sur un coup de parasite. Quand la
 * corrélation s'effondre, on garde le rythme sans se recaler : une seconde de
 * signal perdu ne doit pas décaler tout le reste de l'image.
 *
 * Rien ici ne dépend d'Android, ce qui permet de tout vérifier sur un signal
 * fabriqué de toutes pièces, faute de satellite NOAA disponible sur commande.
 */
class AptDecoder(
    private val sampleRate: Int,
    var listener: Listener? = null
) {

    interface Listener {
        /** Une ligne complète de 2080 mots bruts, dans l'ordre d'arrivée. */
        fun onLine(index: Int, line: FloatArray)

        /** L'état du verrouillage ligne et la qualité de la dernière salve. */
        fun onSync(locked: Boolean, quality: Float)
    }

    /** Nombre de lignes déjà rendues. */
    var lineCount: Int = 0
        private set

    /** Qualité de la dernière corrélation retenue, entre 0 et 1. */
    var quality: Float = 0f
        private set

    /** Vrai quand une ligne a été trouvée et que le rythme est tenu. */
    var locked: Boolean = false
        private set

    // --------------------------------------------------------- démodulation

    private val taps: Int = 101
    private val fir: DoubleArray = lowpass(sampleRate, 2200.0, taps)
    private val bufI = DoubleArray(taps * 2)
    private val bufQ = DoubleArray(taps * 2)
    private var wp = 0

    // Phaseur tournant : moins cher qu'un cosinus par échantillon, et
    // renormalisé régulièrement pour que l'erreur d'arrondi ne l'aplatisse pas.
    private val dc = cos(2.0 * PI * Apt.SUBCARRIER_HZ / sampleRate)
    private val ds = sin(2.0 * PI * Apt.SUBCARRIER_HZ / sampleRate)
    private var oc = 1.0
    private var os = 0.0
    private var spin = 0

    // ------------------------------------------------------ rééchantillonnage

    private val step: Double = sampleRate.toDouble() / Apt.WORD_RATE
    private var nextPos = 0.0
    private var absIndex = 0L
    private var lastEnv = 0.0

    // ------------------------------------------------------------ mise en ligne

    private val wbuf = FloatArray(Apt.WORDS_PER_LINE * 6)
    private var wn = 0
    private var start = 0

    private val tpl = FloatArray(Apt.SYNC_LEN)
    private var tplNorm = 1.0

    init {
        var mean = 0f
        for (v in Apt.SYNC_A_PATTERN) mean += v
        mean /= Apt.SYNC_LEN
        var sum = 0.0
        for (i in 0 until Apt.SYNC_LEN) {
            tpl[i] = Apt.SYNC_A_PATTERN[i] - mean
            sum += tpl[i].toDouble() * tpl[i]
        }
        tplNorm = sqrt(max(sum, 1e-12))
    }

    /** Donne du son au décodeur. Les échantillons sont consommés sur place. */
    fun feed(pcm: ShortArray, count: Int) {
        val n = min(count, pcm.size)
        for (i in 0 until n) {
            val s = pcm[i] / 32768.0

            // Descente en bande de base par les deux voies.
            val vi = s * oc
            val vq = -s * os
            val nc = oc * dc - os * ds
            os = os * dc + oc * ds
            oc = nc
            if (++spin >= 1024) {
                spin = 0
                val m = sqrt(oc * oc + os * os)
                if (m > 1e-9) { oc /= m; os /= m } else { oc = 1.0; os = 0.0 }
            }

            bufI[wp] = vi; bufI[wp + taps] = vi
            bufQ[wp] = vq; bufQ[wp + taps] = vq
            wp = if (wp + 1 == taps) 0 else wp + 1

            var ai = 0.0
            var aq = 0.0
            for (k in 0 until taps) {
                val h = fir[k]
                ai += h * bufI[wp + k]
                aq += h * bufQ[wp + k]
            }
            val env = sqrt(ai * ai + aq * aq)

            // Un mot au plus par échantillon : le pas vaut environ 10,6.
            while (nextPos <= absIndex) {
                val f = (nextPos - (absIndex - 1)).coerceIn(0.0, 1.0)
                pushWord(lastEnv + (env - lastEnv) * f)
                nextPos += step
            }
            lastEnv = env
            absIndex++
        }
        drain()
    }

    /** Vide ce qui reste de complet. Les lignes tronquées sont abandonnées. */
    fun finish() {
        drain()
    }

    private fun pushWord(v: Double) {
        if (wn == wbuf.size) drain()
        if (wn == wbuf.size) { wn = 0; start = 0 } // garde-fou : ne jamais bloquer
        wbuf[wn++] = v.toFloat()
    }

    /**
     * Corrélation normalisée entre la salve attendue et ce qu'on a à cet
     * endroit. Normalisée parce que le niveau de réception varie du tout au
     * tout d'un passage à l'autre : ce qui compte est la forme, pas l'amplitude.
     */
    private fun corr(off: Int): Double {
        var mean = 0.0
        for (k in 0 until Apt.SYNC_LEN) mean += wbuf[off + k]
        mean /= Apt.SYNC_LEN
        var num = 0.0
        var den = 0.0
        for (k in 0 until Apt.SYNC_LEN) {
            val d = wbuf[off + k] - mean
            num += d * tpl[k]
            den += d * d
        }
        if (den <= 1e-12) return 0.0
        return num / (sqrt(den) * tplNorm)
    }

    private fun drain() {
        val line = Apt.WORDS_PER_LINE

        if (!locked) {
            // Il faut deux lignes derrière l'essai pour juger d'un accrochage :
            // une salve isolée peut être un hasard, deux séparées d'exactement
            // une ligne, beaucoup moins.
            if (wn - start < line * 3) return
            var best = -2.0
            var bestOff = start
            for (o in start until start + line) {
                val sc = corr(o) + corr(o + line)
                if (sc > best) { best = sc; bestOff = o }
            }
            if (best < LOCK_THRESHOLD * 2) {
                start += line
                compact()
                listener?.onSync(false, 0f)
                return
            }
            start = bestOff
            locked = true
            quality = (best / 2.0).toFloat()
            listener?.onSync(true, quality)
        }

        while (wn - start >= line) {
            val lo = max(0, start - DRIFT)
            val hi = min(start + DRIFT, wn - line)
            if (hi >= lo) {
                var b = -2.0
                var bi = start
                for (o in lo..hi) {
                    val c = corr(o)
                    if (c > b) { b = c; bi = o }
                }
                if (b > LOCK_THRESHOLD) {
                    start = bi
                    quality = b.toFloat()
                } else {
                    // Signal perdu : on tient le rythme plutôt que de se
                    // recaler sur du bruit.
                    quality = max(0f, quality * 0.8f)
                }
            }
            if (wn - start < line) break
            val out = FloatArray(line)
            System.arraycopy(wbuf, start, out, 0, line)
            listener?.onLine(lineCount, out)
            lineCount++
            start += line
        }
        compact()
    }

    private fun compact() {
        // On laisse une marge derrière pour que le recalage puisse encore
        // reculer de quelques dizaines de mots à la ligne suivante.
        val shift = max(0, start - DRIFT * 2)
        if (shift > 0) {
            System.arraycopy(wbuf, shift, wbuf, 0, wn - shift)
            wn -= shift
            start -= shift
        }
    }

    private fun lowpass(fs: Int, cutHz: Double, n: Int): DoubleArray {
        val h = DoubleArray(n)
        val fc = cutHz / fs
        val m = (n - 1) / 2
        var sum = 0.0
        for (i in 0 until n) {
            val k = (i - m).toDouble()
            val sinc = if (k == 0.0) 2.0 * fc else sin(2.0 * PI * fc * k) / (PI * k)
            val w = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            h[i] = sinc * w
            sum += h[i]
        }
        if (sum != 0.0) for (i in 0 until n) h[i] /= sum
        return h
    }

    companion object {
        /** En deçà, la salve trouvée ne vaut pas mieux que du bruit. */
        const val LOCK_THRESHOLD = 0.30

        /** Fenêtre de rattrapage d'une ligne à l'autre, en mots. */
        const val DRIFT = 40

        /** Décode d'un coup un enregistrement entier. Sert surtout aux essais. */
        fun decodeAll(pcm: ShortArray, sampleRate: Int): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            val d = AptDecoder(sampleRate, object : Listener {
                override fun onLine(index: Int, line: FloatArray) { out.add(line) }
                override fun onSync(locked: Boolean, quality: Float) {}
            })
            d.feed(pcm, pcm.size)
            d.finish()
            return out
        }
    }
}
