/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import fr.f4ioz.satcombo.data.TleEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * METEOR-M LRPT pictures for a station without the phone (the PC): the
 * phone's MeteorHub, its decoding unchanged, without Android — the pictures
 * come out as pixels and the caller writes them; the coastlines come from
 * the caller too.
 *
 * Live, the dongle's raw samples (before any audio chain: 120 kHz of
 * digital signal, nothing to hear) are handed over by [iq] and decoded on a
 * thread of their own, behind a queue: the dongle's reading never waits.
 * A recording of the raw signal (WAV, I and Q) is decoded by [decodeFichier]
 * where it lies — a 1 GB file is not copied.
 */
class RecepteurMeteor(
    /** Coastlines and borders (lines of lon/lat pairs), read once when first needed; null: none. */
    private val traits: () -> Pair<List<FloatArray>, List<FloatArray>>? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    data class Etat(
        val actif: Boolean = false,
        val source: String = "",
        val satellite: String = "",
        val qualiteDb: Float = 0f,
        /** The frames' synchronisation is held. */
        val verrou: Boolean = false,
        val trames: Int = 0,
        val tramesRatees: Int = 0,
        val lignes: Int = 0,
        val canaux: List<Int> = emptyList(),
        /** The carrier's offset found by the demodulator, Hz. */
        val frequenceHz: Int = 0,
        /** Blocks dropped because decoding fell behind. */
        val perdus: Int = 0,
        /** Replaying a file: 0..1; -1 otherwise. */
        val progression: Float = -1f,
        val fichier: String? = null,
        val erreur: String? = null,
        /** Changes each time [apercu] does. */
        val apercus: Int = 0,
    )

    /** A picture to write: its pixels, its kind (LRPT-221 colour, LRPT-IR), its lines. */
    class Image(val image: MeteorImage.Image, val mode: String, val lignes: Int, val satellite: String,
                val debutMs: Long, val source: String, val origine: String)

    @Volatile var etat = Etat(); private set
    /** The latest preview (a quarter of the size, every five seconds). */
    @Volatile var apercu: MeteorImage.Image? = null; private set
    /** Draw the coastlines on the pictures (when the satellite's elements are known). */
    @Volatile var cotes = true

    private var traitsLus: Pair<List<FloatArray>, List<FloatArray>>? = null
    private var traitsEssayes = false

    private class Session(fs: Double, var montant: Boolean, val elements: TleEntry?, var sensConnu: Boolean = true) {
        val msu = MsuMr()
        var orbite: MeteorCarte.Orbite? = null
        val paquets = Paquets { apid, _, d -> msu.paquet(apid, d) }
        val trames = Trames { paquets.trame(it) }
        val demod = DemodOqpsk(fs)
        val soft = ByteArray(65536)
        fun symboles(re: FloatArray, im: FloatArray, n: Int) {
            var k = 0
            while (k < n) {
                val m = minOf(8192, n - k)
                val reK = if (k == 0) re else re.copyOfRange(k, k + m)
                val imK = if (k == 0) im else im.copyOfRange(k, k + m)
                val s = demod.traite(reK, imK, m, soft)
                trames.traite(soft, s)
                k += m
            }
        }
    }

    @Volatile private var session: Session? = null
    private var debutMs = 0L
    private var dernierApercu = 0L
    private var dernierEtat = 0L

    /** Unsigned-byte blocks ([iq]) or 16-bit ones ([iq16]). */
    private val file = ArrayBlockingQueue<Pair<Any, Double>>(BLOCS)
    private val libres = ArrayBlockingQueue<ByteArray>(BLOCS + 4)
    private var fil: Thread? = null
    @Volatile private var enMarche = false
    private var decimateur: Decimateur? = null
    @Volatile private var perdus = 0

    val actif: Boolean get() = enMarche

    /**
     * Starts on the dongle's samples ([fsDongle] S/s). [montant]: the pass
     * goes north (the picture is turned so that north is up). [elements]:
     * the satellite's, for the coastlines.
     */
    @Synchronized
    fun demarre(fsDongle: Double, satellite: String, montant: Boolean, elements: TleEntry? = null) {
        if (enMarche) return
        val d = Decimateur(fsDongle, 4)
        decimateur = d
        session = Session(d.fsSortie, montant, elements)
        file.clear(); perdus = 0
        debutMs = horloge()
        apercu = null
        etat = Etat(actif = true, source = "sdr", satellite = satellite)
        enMarche = true
        fil = Thread({ boucle() }, "satme-meteor").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    /** A block of unsigned I/Q bytes from the dongle, the satellite [decalageHz] off its centre. */
    fun iq(bloc: ByteArray, n: Int, decalageHz: Double) {
        if (!enMarche || n <= 0) return
        val b = libres.poll()?.takeIf { it.size == n } ?: ByteArray(n)
        System.arraycopy(bloc, 0, b, 0, n)
        if (!file.offer(b to decalageHz)) { perdus++; libres.offer(b) }
    }

    /** As [iq], from signed 16-bit I/Q pairs ([n] shorts). */
    fun iq16(bloc: ShortArray, n: Int, decalageHz: Double) {
        if (!enMarche || n <= 0) return
        if (!file.offer(bloc.copyOf(n) to decalageHz)) perdus++
    }

    private fun boucle() {
        val s = session ?: return
        val d = decimateur ?: return
        val re = FloatArray(1 shl 17); val im = FloatArray(1 shl 17)
        while (enMarche) {
            val (b, dec) = file.poll(200, TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                val n = when (b) {
                    is ByteArray -> d.traiteU8(b, b.size, dec, re, im)
                    is ShortArray -> d.traiteS16(b, b.size, dec, re, im)
                    else -> 0
                }
                s.symboles(re, im, n)
            }
            if (b is ByteArray) libres.offer(b)
            publie(s, false)
        }
    }

    private fun orbite(s: Session): MeteorCarte.Orbite? {
        val el = s.elements ?: return null
        val (periode, t0, scans) = s.msu.periodeEtOrigine() ?: return null
        s.orbite?.takeIf { it.scans == scans + 1 }?.let { return it }
        return MeteorCarte.orbite(el.line1, el.line2, MeteorCarte.utc(t0), periode, scans)?.also { s.orbite = it }
    }

    /** A recording's direction, from the orbit: going north if the satellite ends further north. */
    private fun sensSelonOrbite(s: Session) {
        if (s.sensConnu) return
        val o = orbite(s) ?: return
        if (o.scans < 20) return
        s.montant = o.pos.last()[2] > o.pos.first()[2]
        s.sensConnu = true
    }

    private fun avecCotes(s: Session, img: MeteorImage.Image, pas: Int): MeteorImage.Image {
        if (!cotes || s.elements == null) return img
        val (_, _, scans) = s.msu.periodeEtOrigine() ?: return img
        val o = orbite(s) ?: return img
        if (!traitsEssayes) { traitsEssayes = true; traitsLus = runCatching { traits() }.getOrNull() }
        val (c, f) = traitsLus ?: return img
        runCatching {
            MeteorCarte.trace(img, o, f, scans * 8, s.montant, pas, 0xFFFFFF, 150)
            MeteorCarte.trace(img, o, c, scans * 8, s.montant, pas, 0xFFE040, 230)
        }
        return img
    }

    private fun publie(s: Session, force: Boolean) {
        val now = horloge()
        if (!force && now - dernierEtat < 400) return
        dernierEtat = now
        // The preview costs a whole picture's work: every five seconds is plenty.
        val apercuDu = force || now - dernierApercu > 5000
        if (apercuDu) runCatching { sensSelonOrbite(s) }
        var n = etat.apercus
        if (apercuDu) {
            dernierApercu = now
            runCatching { MeteorImage.meilleure(s.msu, s.montant, 4)?.let { avecCotes(s, it, 4) } }.getOrNull()?.let { apercu = it; n++ }
        }
        etat = etat.copy(
            qualiteDb = s.demod.qualiteDb.toFloat(), verrou = s.trames.verrouille,
            trames = s.trames.trames, tramesRatees = s.trames.tramesRatees,
            lignes = s.msu.periodeEtOrigine()?.third?.times(8) ?: 0,
            canaux = MeteorImage.canaux(s.msu).sorted(), frequenceHz = s.demod.frequenceHz.toInt(),
            perdus = perdus, apercus = n)
    }

    /** Stops listening; the pictures received (none under 80 lines). */
    @Synchronized
    fun arrete(): List<Image> {
        if (!enMarche) return emptyList()
        enMarche = false
        runCatching { fil?.join(3000) }
        fil = null
        val s = session ?: return emptyList()
        publie(s, true)
        val l = images(s, etat.satellite, "live", "")
        session = null
        etat = etat.copy(actif = false, verrou = false)
        return l
    }

    /** What has been received so far, without stopping. */
    fun imagesMaintenant(): List<Image> = session?.let { images(it, etat.satellite, "live", "") } ?: emptyList()

    @Synchronized
    private fun images(s: Session, sat: String, source: String, origine: String): List<Image> {
        val lignes = s.msu.periodeEtOrigine()?.third?.times(8) ?: 0
        if (lignes < 80) return emptyList()
        val t = if (debutMs > 0) debutMs else horloge()
        val couleur = runCatching { MeteorImage.couleur(s.msu, s.montant)?.let { avecCotes(s, it, 1) } }.getOrNull()
        val ir = MeteorImage.infrarouge(s.msu)?.let { runCatching { MeteorImage.gris(s.msu, it, s.montant)?.let { i -> avecCotes(s, i, 1) } }.getOrNull() }
        return listOfNotNull(couleur?.let { Image(it, "LRPT-221", lignes, sat, t, source, origine) },
            ir?.let { Image(it, "LRPT-IR", lignes, sat, t, source, origine) })
    }

    // ------------------------------------------------------------ replaying a recording

    @Volatile private var annule = false
    fun annuleFichier() { annule = true }

    /**
     * Decodes a recording of the raw signal: a WAV file, two channels (I, Q),
     * 32-bit float, 16-bit or 8-bit, 150 to 3 000 kS/s. Blocking. The
     * satellite's elements found by [elementsPour] (from the name) give the
     * coastlines and the direction.
     */
    fun decodeFichier(canal: FileChannel, nom: String, dateMs: Long, elementsPour: (String) -> TleEntry? = { null },
                      montant: Boolean = false): List<Image> {
        if (enMarche) return emptyList()
        annule = false
        val w = runCatching { Wav.lis(canal) }.getOrNull()
        if (w == null || w.canaux != 2) {
            etat = etat.copy(erreur = "Ce n'est pas un enregistrement I/Q (WAV à deux canaux).", fichier = nom)
            return emptyList()
        }
        // Down to ~300 kS/s at most: the demodulator needs no more.
        val facteur = maxOf(1, (w.fs / 300_000).toInt())
        val sat = Regex("(?i)meteor[-_ ]?m?[-_ ]?\\d?[-_ ]?\\d?").find(nom)?.value?.uppercase()?.replace('_', '-')?.trim('-') ?: "METEOR"
        val elements = elementsPour(sat)
        val s = Session(w.fs / facteur, montant, elements, sensConnu = elements == null)
        debutMs = dateMs
        apercu = null
        etat = Etat(source = "fichier", satellite = sat, fichier = nom, progression = 0f)
        val bloc = 32768
        val octets = w.octetsParEchantillon * 2
        val buf = ByteBuffer.allocate(bloc * octets)
        val re = FloatArray(bloc); val im = FloatArray(bloc)
        val oRe = FloatArray(bloc); val oIm = FloatArray(bloc)
        val fir = if (facteur > 1) DecimateurFlottant(facteur) else null
        canal.position(w.debutDonnees)
        var lus = 0L
        var tours = 0
        while (lus < w.echantillons && !annule) {
            val n = minOf(bloc.toLong(), w.echantillons - lus).toInt()
            buf.clear(); buf.limit(n * octets)
            while (buf.hasRemaining()) if (canal.read(buf) < 0) break
            val m0 = buf.position() / octets
            if (m0 == 0) break
            w.convertit(buf.array(), m0, re, im)
            lus += m0
            if (fir != null) s.symboles(oRe, oIm, fir.traite(re, im, m0, oRe, oIm)) else s.symboles(re, im, m0)
            if (++tours % 8 == 0) { publie(s, false); etat = etat.copy(progression = lus.toFloat() / w.echantillons) }
        }
        publie(s, true)
        val l = images(s, sat, "file", nom)
        etat = etat.copy(progression = -1f)
        return l
    }

    /** What a WAV file holds, enough to read raw I/Q from it. */
    class Wav(val fs: Double, val canaux: Int, val format: Int, val bits: Int, val debutDonnees: Long, val echantillons: Long) {
        val octetsParEchantillon = bits / 8

        fun convertit(b: ByteArray, n: Int, re: FloatArray, im: FloatArray) {
            val bb = ByteBuffer.wrap(b, 0, n * octetsParEchantillon * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until n) {
                when {
                    format == 3 && bits == 32 -> { re[k] = bb.float; im[k] = bb.float }
                    bits == 16 -> { re[k] = bb.short / 32768f; im[k] = bb.short / 32768f }
                    else -> { re[k] = ((bb.get().toInt() and 0xFF) - 127.5f) / 127.5f; im[k] = ((bb.get().toInt() and 0xFF) - 127.5f) / 127.5f }
                }
            }
        }

        companion object {
            fun lis(ch: FileChannel): Wav? {
                val longueur = ch.size()
                fun lit(pos: Long, n: Int): ByteBuffer? {
                    val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
                    ch.position(pos)
                    while (b.hasRemaining()) if (ch.read(b) < 0) return null
                    b.flip(); return b
                }
                val h = lit(0, 12) ?: return null
                val tag = ByteArray(4)
                h.get(tag); if (String(tag, Charsets.US_ASCII) != "RIFF") return null
                h.int; h.get(tag); if (String(tag, Charsets.US_ASCII) != "WAVE") return null
                var pos = 12L
                var fs = 0.0; var canaux = 0; var format = 0; var bits = 0
                while (pos + 8 <= longueur) {
                    val t = lit(pos, 8) ?: return null
                    t.get(tag)
                    val id = String(tag, Charsets.US_ASCII)
                    val taille = t.int.toLong() and 0xFFFFFFFFL
                    pos += 8
                    if (id == "fmt ") {
                        val bb = lit(pos, 16) ?: return null
                        format = bb.short.toInt() and 0xFFFF
                        canaux = bb.short.toInt()
                        fs = (bb.int.toLong() and 0xFFFFFFFFL).toDouble()
                        bb.int; bb.short
                        bits = bb.short.toInt()
                        if (format == 0xFFFE) format = if (bits == 32) 3 else 1
                    } else if (id == "data") {
                        // Some recorders leave the size at 0 (written while streaming): to the end, then.
                        val octets = if (taille == 0L || pos + taille > longueur) longueur - pos else taille
                        if (fs <= 0 || bits !in listOf(8, 16, 32) || canaux <= 0) return null
                        return Wav(fs, canaux, format, bits, pos, octets / (bits / 8 * canaux))
                    }
                    pos += taille + (taille and 1)
                }
                return null
            }
        }
    }

    /** Integer decimation of complex float samples (recordings faster than the demodulator needs). */
    class DecimateurFlottant(private val facteur: Int, ntaps: Int = 8 * facteur + 1) {
        private val taps = DoubleArray(ntaps) { i ->
            val m = i - (ntaps - 1) / 2.0
            val x = 0.8 / facteur
            val sinc = if (m == 0.0) x else kotlin.math.sin(Math.PI * x * m) / (Math.PI * m)
            sinc * (0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (ntaps - 1)))
        }.let { t -> val s = t.sum(); DoubleArray(t.size) { t[it] / s } }
        private val hi = DoubleArray(ntaps); private val hq = DoubleArray(ntaps)
        private var pos = 0; private var compte = 0
        fun traite(re: FloatArray, im: FloatArray, n: Int, oRe: FloatArray, oIm: FloatArray): Int {
            var o = 0
            for (k in 0 until n) {
                hi[pos] = re[k].toDouble(); hq[pos] = im[k].toDouble()
                pos = if (pos + 1 == taps.size) 0 else pos + 1
                if (++compte == facteur) {
                    compte = 0
                    var a = 0.0; var b = 0.0; var j = pos
                    for (t in taps.indices) { a += taps[t] * hi[j]; b += taps[t] * hq[j]; j = if (j + 1 == taps.size) 0 else j + 1 }
                    oRe[o] = a.toFloat(); oIm[o] = b.toFloat(); o++
                }
            }
            return o
        }
    }

    companion object {
        private const val BLOCS = 400

        /** METEOR-M satellites sending LRPT: M2-3 and M2-4 by number, later ones by name (M2-5…). */
        fun emetLrpt(catnum: Int, nom: String): Boolean {
            if (catnum == 57166 || catnum == 59051) return true
            val n = nom.uppercase().replace(" ", "").replace("-", "").replace("_", "")
            if (!n.startsWith("METEORM2")) return false
            val suite = n.removePrefix("METEORM2").firstOrNull() ?: return false
            return suite in '3'..'9'
        }

        /**
         * Which transmitter to receive, among [voies] (description, downlink Hz):
         * the index, or -1. SatMe decodes the 72 kbps LRPT; the lists also carry
         * 80 kbps variants (one of them at 137.9125 MHz, 12.5 kHz off the
         * signal), an "IQ recording" entry, HRPT and L/S/X bands. Best: LRPT
         * 72 kbps on 137.900 MHz, then on 137.100; then any LRPT on one of those
         * two frequencies that is not 80 kbps.
         */
        fun choisitVoie(voies: List<Pair<String, Long?>>): Int {
            fun sur(hz: Long?, f: Long) = hz != null && kotlin.math.abs(hz - f) <= 2_000L
            fun lrpt(d: String) = d.contains("LRPT", true) && !d.contains("IQ", true)
            fun en72(d: String) = Regex("\\b72\\s*k").containsMatchIn(d.lowercase())
            fun en80(d: String) = Regex("\\b80\\s*k").containsMatchIn(d.lowercase())
            val ordre = listOf<(String, Long?) -> Boolean>(
                { d, hz -> lrpt(d) && en72(d) && sur(hz, 137_900_000L) },
                { d, hz -> lrpt(d) && en72(d) && sur(hz, 137_100_000L) },
                { d, hz -> lrpt(d) && !en80(d) && sur(hz, 137_900_000L) },
                { d, hz -> lrpt(d) && !en80(d) && sur(hz, 137_100_000L) })
            for (test in ordre) {
                val i = voies.indexOfFirst { (d, hz) -> test(d, hz) }
                if (i >= 0) return i
            }
            return -1
        }

        /** A transmitter SatMe can decode as it is: LRPT 72 kbps (or not stated 80) on 137.9 or 137.1 MHz. */
        fun voieDecodable(description: String, hz: Long?): Boolean = choisitVoie(listOf(description to hz)) == 0

        /** A date in a file's name (2026-03-05 00:21, 20260305_0021, 20260305…), UTC; midnight when it has no time. */
        fun dateDuNom(nom: String): Long? {
            val m = Regex("(20\\d{2})[-_]?(\\d{2})[-_]?(\\d{2})(?:[-_T ]?(\\d{2})[-_:]?(\\d{2}))?").find(nom) ?: return null
            val g = m.groupValues
            val mo = g[2].toInt(); val d = g[3].toInt()
            if (mo !in 1..12 || d !in 1..31) return null
            val h = g[4].toIntOrNull()?.takeIf { it in 0..23 } ?: 0
            val mi = g[5].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
            return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(g[1].toInt(), mo - 1, d, h, mi)
            }.timeInMillis
        }
    }
}
