/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.meteor

import android.content.Context
import android.graphics.Bitmap
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * METEOR-M pictures (LRPT), between the dongle and the screens.
 *
 * The dongle's USB thread only hands its blocks over ([iq]); the decoding
 * runs on its own thread, so that a slow moment never makes the dongle lose
 * samples — if the decoder falls behind, whole blocks are dropped and
 * counted, which shows as a black band, not as a broken stream.
 *
 * The pictures are written when listening stops (end of the pass), and can
 * be written mid-pass: colour (by day) and infrared, north up.
 */
object MeteorHub {

    data class Etat(
        val actif: Boolean = false,
        /** "sdr" or "fichier". */
        val source: String = "",
        val satName: String = "",
        /** Symbol quality, dB (above ~6 dB the frames come through). */
        val qualiteDb: Float = 0f,
        /** The frames' sync is held. */
        val verrou: Boolean = false,
        val trames: Int = 0,
        val tramesRatees: Int = 0,
        /** Lines of picture received (8 per scan). */
        val lignes: Int = 0,
        val canaux: List<Int> = emptyList(),
        /** Carrier offset found, Hz. */
        val frequenceHz: Int = 0,
        /** Blocks dropped because the decoder fell behind. */
        val perdus: Int = 0,
        val apercu: Bitmap? = null,
        val dernierEnregistre: String? = null,
        val enregistres: Int = 0,
        /** 0..1 while replaying a file, -1 otherwise. */
        val progression: Float = -1f,
        val fichier: String? = null,
        val erreur: String? = null
    )

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

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat

    @Volatile var qthLocator: String = ""

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "meteor").apply { mkdirs() }

    fun images(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Each saved picture with its metadata, newest first. */
    fun shots(ctx: Context): List<Pair<File, SstvMeta.SstvShot>> =
        images(ctx).map { f ->
            val side = File(f.parentFile, SstvMeta.sidecarName(f.name))
            val text = runCatching { if (side.isFile) side.readText(Charsets.UTF_8) else null }.getOrNull()
            var shot = SstvMeta.decode(f.name, text)
            if (shot.timeMs <= 0L) shot = shot.copy(timeMs = f.lastModified())
            f to shot
        }

    fun supprime(file: File): Boolean {
        runCatching { File(file.parentFile, SstvMeta.sidecarName(file.name)).delete() }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    // --- one reception at a time
    private class Session(fs: Double, val montant: Boolean) {
        val msu = MsuMr()
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
    private var ctxApp: Context? = null
    private var debutMs = 0L
    private var dernierApercu = 0L

    // --- live, from the dongle
    private const val BLOCS = 400
    private val file = ArrayBlockingQueue<Pair<ByteArray, Double>>(BLOCS)
    private val libres = ArrayBlockingQueue<ByteArray>(BLOCS + 4)
    private var fil: Thread? = null
    @Volatile private var enMarche = false
    private var decimateur: Decimateur? = null
    @Volatile private var perdus = 0

    val actif: Boolean get() = enMarche

    /**
     * Starts decoding the dongle's samples ([fsDongle] S/s). [montant]: the
     * pass goes north (the picture is turned so that north is up).
     */
    @Synchronized
    fun demarre(ctx: Context, fsDongle: Double, satName: String, montant: Boolean) {
        if (enMarche) return
        ctxApp = ctx.applicationContext
        val d = Decimateur(fsDongle, 4)
        decimateur = d
        session = Session(d.fsSortie, montant)
        file.clear(); perdus = 0
        debutMs = System.currentTimeMillis()
        _etat.value = Etat(actif = true, source = "sdr", satName = satName,
            dernierEnregistre = _etat.value.dernierEnregistre, enregistres = _etat.value.enregistres)
        enMarche = true
        fil = Thread({ boucle() }, "meteor-lrpt").apply { priority = Thread.NORM_PRIORITY + 1; start() }
    }

    /** From the dongle's USB thread: a block of unsigned I/Q bytes, the satellite [decalageHz] off centre. */
    fun iq(bloc: ByteArray, n: Int, decalageHz: Double) {
        if (!enMarche || n <= 0) return
        val b = libres.poll()?.takeIf { it.size == n } ?: ByteArray(n)
        System.arraycopy(bloc, 0, b, 0, n)
        if (!file.offer(b to decalageHz)) { perdus++; libres.offer(b) }
    }

    private fun boucle() {
        val s = session ?: return
        val d = decimateur ?: return
        val re = FloatArray(1 shl 17); val im = FloatArray(1 shl 17)
        while (enMarche) {
            val (b, dec) = file.poll(200, TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                val n = d.traiteU8(b, b.size, dec, re, im)
                s.symboles(re, im, n)
            }
            libres.offer(b)
            publie(s, false)
        }
    }

    private var dernierEtat = 0L

    private fun publie(s: Session, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - dernierEtat < 400) return
        dernierEtat = now
        // The preview costs a whole picture's worth of work: every five seconds is plenty.
        val apercuDu = force || now - dernierApercu > 5000
        val canaux = MeteorImage.canaux(s.msu).sorted()
        val lignes = s.msu.periodeEtOrigine()?.third?.times(8) ?: 0
        var ap = _etat.value.apercu
        if (apercuDu) {
            dernierApercu = now
            ap = runCatching { MeteorImage.meilleure(s.msu, s.montant, 4)?.let { bitmap(it) } }.getOrNull() ?: ap
        }
        _etat.value = _etat.value.copy(
            qualiteDb = s.demod.qualiteDb.toFloat(), verrou = s.trames.verrouille,
            trames = s.trames.trames, tramesRatees = s.trames.tramesRatees,
            lignes = lignes, canaux = canaux, frequenceHz = s.demod.frequenceHz.toInt(),
            perdus = perdus, apercu = ap)
    }

    /** Stops listening and writes the pictures. */
    @Synchronized
    fun arrete() {
        if (!enMarche) return
        enMarche = false
        runCatching { fil?.join(3000) }
        fil = null
        val s = session ?: return
        publie(s, true)
        val ctx = ctxApp
        if (ctx != null) enregistre(ctx, s, _etat.value.satName, "live", "")
        session = null
        _etat.value = _etat.value.copy(actif = false, verrou = false)
    }

    /** Writes what has been received so far, without stopping. */
    fun enregistreMaintenant(ctx: Context): String? {
        val s = session ?: return null
        return enregistre(ctx, s, _etat.value.satName, "live", "")
    }

    @Synchronized
    private fun enregistre(ctx: Context, s: Session, sat: String, source: String, origine: String): String? {
        val lignes = s.msu.periodeEtOrigine()?.third?.times(8) ?: 0
        if (lignes < 80) return null
        var premier: String? = null
        val t = if (debutMs > 0) debutMs else System.currentTimeMillis()
        val couleur = runCatching { MeteorImage.couleur(s.msu, s.montant) }.getOrNull()
        val ir = MeteorImage.infrarouge(s.msu)?.let { runCatching { MeteorImage.gris(s.msu, it, s.montant) }.getOrNull() }
        var n = 0
        for ((img, mode) in listOf(couleur to "LRPT-221", ir to "LRPT-IR")) {
            if (img == null) continue
            val nom = SstvMeta.fileName(sat, t, mode, lignes >= 800, kind = "LRPT")
            val f = File(dir(ctx), nom)
            val ok = runCatching {
                val b = bitmap(img)
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }.isSuccess
            if (!ok) continue
            runCatching {
                val st = SettingsStore(ctx)
                val shot = SstvMeta.SstvShot(
                    fileName = nom, satName = sat, timeMs = t, mode = mode, complete = lignes >= 800,
                    // A recording from elsewhere was not received here: no locator, no callsign.
                    locator = if (source == "live") qthLocator.ifBlank { st.manualLocator } else "",
                    callsign = if (source == "live") st.callsign else "",
                    source = source, recording = origine,
                    note = fr.f4ioz.satcombo.i18n.tf(if (mode == "LRPT-IR") "meteor_note_ir" else "meteor_note_couleur", lignes))
                File(dir(ctx), SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(shot), Charsets.UTF_8)
            }
            exporte(ctx, f)
            if (premier == null) premier = nom
            n++
        }
        if (premier != null) _etat.value = _etat.value.copy(dernierEnregistre = premier, enregistres = _etat.value.enregistres + n)
        return premier
    }

    private fun bitmap(img: MeteorImage.Image): Bitmap =
        Bitmap.createBitmap(img.pixels, img.largeur, img.hauteur, Bitmap.Config.ARGB_8888)

    private fun exporte(ctx: Context, file: File) {
        val uriStr = SettingsStore(ctx).recordingsTreeUri
        if (uriStr.isBlank()) return
        runCatching {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                ctx, android.net.Uri.parse(uriStr)) ?: return
            tree.findFile(file.name)?.delete()
            val doc = tree.createFile("image/png", file.name) ?: return
            ctx.contentResolver.openOutputStream(doc.uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        }
    }

    // --- replay of a recording

    @Volatile private var annuleFichier = false
    fun annuleFichier() { annuleFichier = true }

    /**
     * Decodes a recording of the raw signal: a WAV file, two channels (I, Q),
     * 32-bit float, 16-bit or 8-bit, any rate from 150 to 3 000 kS/s. Read
     * where it lies (a 1 GB file is not copied). Blocking: off the main
     * thread. Returns the number of pictures written.
     */
    fun decodeFichier(ctx: Context, canal: FileChannel, nom: String, dateMs: Long, montant: Boolean = false): Int {
        if (enMarche) return 0
        annuleFichier = false
        val app = ctx.applicationContext
        val w = runCatching { Wav.lis(canal) }.getOrNull()
        if (w == null || w.canaux != 2) {
            _etat.value = _etat.value.copy(erreur = "meteor_fichier_format", fichier = nom)
            return 0
        }
        // Down to at most ~300 kS/s: the demodulator needs no more.
        val facteur = maxOf(1, (w.fs / 300_000).toInt())
        val fsDemod = w.fs / facteur
        val s = Session(fsDemod, montant)
        val sat = Regex("(?i)meteor[-_ ]?m?[-_ ]?\\d?[-_ ]?\\d?").find(nom)?.value
            ?.uppercase()?.replace('_', '-')?.trim('-') ?: "METEOR"
        debutMs = dateMs
        _etat.value = _etat.value.copy(actif = false, source = "fichier", satName = sat, fichier = nom,
            progression = 0f, erreur = null, trames = 0, tramesRatees = 0, lignes = 0, apercu = null)
        val bloc = 32768
        val octets = w.octetsParEchantillon * 2
        val buf = ByteBuffer.allocate(bloc * octets)
        val re = FloatArray(bloc); val im = FloatArray(bloc)
        val outRe = FloatArray(bloc); val outIm = FloatArray(bloc)
        val fir = if (facteur > 1) DecimateurFlottant(w.fs, facteur) else null
        canal.position(w.debutDonnees)
        val total = w.echantillons
        var lus = 0L
        var tours = 0
        while (lus < total && !annuleFichier) {
            val n = minOf(bloc.toLong(), total - lus).toInt()
            buf.clear(); buf.limit(n * octets)
            while (buf.hasRemaining()) if (canal.read(buf) < 0) break
            val m0 = buf.position() / octets
            if (m0 == 0) break
            w.convertit(buf.array(), m0, re, im)
            lus += m0
            if (fir != null) {
                val m = fir.traite(re, im, m0, outRe, outIm)
                s.symboles(outRe, outIm, m)
            } else s.symboles(re, im, m0)
            if (++tours % 8 == 0) {
                publie(s, false)
                _etat.value = _etat.value.copy(progression = lus.toFloat() / total)
            }
        }
        publie(s, true)
        val avant = _etat.value.enregistres
        enregistre(app, s, sat, "file", nom)
        _etat.value = _etat.value.copy(progression = -1f)
        return _etat.value.enregistres - avant
    }

    /** From a document picked by the operator (content:// or file). */
    fun decodeDocument(ctx: Context, uri: android.net.Uri): Int {
        val nom = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "meteor.wav"
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return 0
        return pfd.use { d ->
            java.io.FileInputStream(d.fileDescriptor).channel.use { ch ->
                decodeFichier(ctx, ch, nom, dateDuNom(nom) ?: System.currentTimeMillis())
            }
        }
    }

    /** A date in the name (2026-03-05 00:21, 20260305_0021, 20260305…), UTC; midnight when it has no time. */
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
    class DecimateurFlottant(fs: Double, private val facteur: Int, ntaps: Int = 8 * facteur + 1) {
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
}
