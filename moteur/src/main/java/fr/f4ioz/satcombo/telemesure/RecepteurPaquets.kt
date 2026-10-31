/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.aprs.Ax25
import fr.f4ioz.satcombo.aprs.Kiss
import fr.f4ioz.satcombo.meteor.Decimateur
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cubesat packets at 1200 to 9600 bit/s (FSK, GMSK, « G3RUH »), receive
 * only, from the SDR's raw samples:
 *
 * - the samples (the satellite [iq]'s offset from the dongle's centre,
 *   its Doppler already followed) moved to 0 Hz and cut to ~88 kS/s on a
 *   thread of their own, behind a queue (the dongle never waits);
 * - or, from a sound card, a rig's packet output in 9600 mode (the
 *   TS-2000's ACC2: its own discriminator, flat, Doppler by its CAT):
 *   [audio], to [DemodBandeBase] — no mixer, no AFC;
 * - [DemodFsk] (residual AFC, discriminator, clock), then two readings of
 *   the same bits: descrambled + NRZI, and NRZI alone — HDLC frames with
 *   a right CRC-16 come out of whichever fits;
 * - each frame: its AX.25 header when it has one, the SSDV packets inside
 *   it (pictures rebuilt as they come), the time, the frequency heard and
 *   the one sent (Doppler taken off);
 * - written per pass in [dossier]`/telemesure/paquets` (JSON lines, and a
 *   KISS file when [kiss]), pictures in `telemesure/ssdv`, put together
 *   over several passes ([AssemblageSsdv]). Frames from the
 *   test generator ([Contexte.essai]) are shown, never written nor shared.
 */
class RecepteurPaquets(
    private val dossier: File?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** What the frames are stamped with, asked when one comes. */
    class Contexte(
        val satellite: String = "",
        val norad: Int = 0,
        /** The sky frequency the receiver listens to (Doppler in), and the satellite's own (Doppler out), Hz; 0: unknown. */
        val cielHz: Long = 0,
        val reposHz: Long = 0,
        /** Signals made by SatMe's generator. */
        val essai: Boolean = false,
    )

    class TramePaquet(
        val t: Long,
        /** Without the FCS. */
        val octets: ByteArray,
        val ax25: Ax25.Entete?,
        val g3ruh: Boolean,
        val afcHz: Double,
        val frequenceHz: Long,
        val frequenceCorrigeeHz: Long,
        val satellite: String,
        val norad: Int,
        val essai: Boolean,
        /** The SSDV packets inside: picture key, packet number. */
        val ssdv: List<Pair<String, Int>>,
        /** « HDLC », or « Geoscan » (sync word, PN9, CRC-16: [Geoscan]). */
        val format: String = FORMAT_HDLC,
        /** A Geoscan picture's chunk inside: the picture's key. */
        val geoscan: String? = null,
    ) {
        fun hex(): String = octets.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
    }

    data class Etat(
        val actif: Boolean = false,
        val debit: Int = 9600,
        val bits: Long = 0,
        val trames: Int = 0,
        val crcFaux: Int = 0,
        val afcHz: Double = 0.0,
        val niveauDb: Double = -120.0,
        val plancherDb: Double = -120.0,
        val presence: Boolean = false,
        val signal: Boolean = false,
        val perdus: Int = 0,
        val derniereMs: Long = 0,
        /** « iq » (the SDR) or « son » (a rig's 9600 output on a sound card). */
        val source: String = SOURCE_IQ,
        /** The sound's peak over the last 50 ms (0..1), its rate; « son » only. */
        val crete: Double = 0.0,
        val rate: Int = 0,
    )

    @Volatile var contexte: () -> Contexte = { Contexte() }
    /** Each new frame (after it was kept), for sharing. */
    @Volatile var surTrame: ((TramePaquet) -> Unit)? = null
    /** Write a KISS file next to the JSON lines. */
    @Volatile var kiss = false
    /** Bit rate wanted; taken at the next block. */
    @Volatile var debit = 9600

    @Volatile var etat = Etat(); private set
    private val liste = java.util.ArrayDeque<TramePaquet>()
    val trames: List<TramePaquet> get() = synchronized(liste) { liste.toList() }
    /** The SSDV pictures, put together over the passes (kept in `telemesure/ssdv` with a [dossier]). */
    val assemblage = AssemblageSsdv(dossier, horloge)
    /** The Geoscan satellites' pictures (JPEG chunks, not SSDV), kept in `telemesure/geoscan`. */
    val geoscan = Geoscan.Images(dossier, horloge)

    // ---------------------------------------------------------------- the chain (one thread)

    /**
     * From the line's bits on, the same for both sources; [son]: the bits come
     * from a sound card's baseband ([DemodBandeBase]), not the SDR's I/Q.
     */
    private inner class Chaine(val fs: Double, val debit: Int, val son: Boolean = false) {
        val g = DeframeurHdlc { t -> trame(t, true) }
        val n = DeframeurHdlc { t -> trame(t, false) }
        val desembrouille = G3ruh.Desembrouilleur()
        val nrziG = G3ruh.Nrzi(); val nrziN = G3ruh.Nrzi()
        // The Geoscan satellites' frames (CC1125-like: sync word, PN9, CRC-16) in the same bits, NRZ.
        val geo = Geoscan.Deframeur { o -> trameGeoscan(o) }
        val bit: (Int) -> Unit = { b ->
            g.bit(nrziG.bit(desembrouille.bit(b)))
            n.bit(nrziN.bit(b))
            geo.bit(b)
        }
        val demod: DemodFsk? = if (son) null else DemodFsk(fs, debit, surBit = bit)
        val demodSon: DemodBandeBase? = if (son) DemodBandeBase(fs, debit, bit) else null
        private fun verrou() = maxOf(g.signe, n.signe) > g.compte - 2L * debit
        fun traite(re: FloatArray, im: FloatArray, k: Int) {
            val dm = demod ?: return
            var i = 0
            while (i < k) {
                val m = minOf(2048, k - i)
                dm.verrouExterne = verrou()
                if (i == 0) dm.traite(re, im, m) else dm.traite(re.copyOfRange(i, i + m), im.copyOfRange(i, i + m), m)
                i += m
            }
        }
        fun traiteSon(x: FloatArray, k: Int) {
            val dm = demodSon ?: return
            var i = 0
            while (i < k) {
                val m = minOf(1024, k - i)
                dm.verrouExterne = verrou()
                if (i == 0) dm.traite(x, m) else dm.traite(x.copyOfRange(i, i + m), m)
                i += m
            }
        }
        val afcHz: Double get() = demod?.afcHz ?: 0.0
    }

    private var chaine: Chaine? = null
    @Volatile private var decalageHz = 0.0
    private var decimateur: Decimateur? = null
    private val file = ArrayBlockingQueue<Pair<Any, Double>>(64)
    /** A block of sound for the « son » source. */
    private class BlocSon(val pcm: ShortArray, val rate: Int)
    @Volatile private var enMarche = false
    private var fil: Thread? = null
    @Volatile private var perdus = 0

    val actif: Boolean get() = enMarche

    /** Starts on the dongle's samples at [fsEntree] S/s. */
    @Synchronized
    fun demarre(fsEntree: Double) = lance { prepare(fsEntree) }

    /** Starts on a sound card's baseband ([audio]): the rate comes with each block. */
    @Synchronized
    fun demarreSon() = lance { decimateur = null }

    /** What it was started on: [SOURCE_IQ] or [SOURCE_SON]; null when stopped. */
    val sourceEnMarche: String? get() = if (!enMarche) null else if (decimateur != null) SOURCE_IQ else SOURCE_SON

    private fun lance(prepare: () -> Unit) {
        if (enMarche) return
        prepare()
        file.clear(); perdus = 0; chaine = null
        enMarche = true
        etat = etat.copy(actif = true)
        fil = Thread({ boucle() }, "satme-paquets").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun arrete() {
        if (!enMarche) return
        enMarche = false
        runCatching { fil?.join(2000) }
        fil = null
        etat = etat.copy(actif = false, presence = false, signal = false)
    }

    /** A block of unsigned I/Q bytes, the satellite [decalage] Hz off the centre. */
    fun iq(bloc: ByteArray, n: Int, decalage: Double) {
        if (!enMarche || n <= 0) return
        if (!file.offer(bloc.copyOf(n) to decalage)) perdus++
    }

    fun iq16(bloc: ShortArray, n: Int, decalage: Double) {
        if (!enMarche || n <= 0) return
        if (!file.offer(bloc.copyOf(n) to decalage)) perdus++
    }

    /**
     * A block of 16-bit mono sound at [rate] Hz (44.1, 48 or 96 kHz): a rig's
     * packet output in 9600 mode (the FM discriminator, flat).
     */
    fun audio(pcm: ShortArray, n: Int, rate: Int) {
        if (!enMarche || n <= 0 || rate < 8_000) return
        if (!file.offer(BlocSon(pcm.copyOf(n), rate) to rate.toDouble())) perdus++
    }

    private fun boucle() {
        while (enMarche) {
            val (b, dec) = file.poll(200, TimeUnit.MILLISECONDS) ?: continue
            runCatching { traiteBloc(b, dec) }
        }
    }

    private var re = FloatArray(0); private var im = FloatArray(0)

    /** One block of raw samples (bytes or 16-bit pairs), synchronously; [demarre] must have set the rate. */
    fun traiteBloc(b: Any, dec: Double) {
        if (b is BlocSon) { traiteSon(b.pcm, b.pcm.size, b.rate); return }
        val d = decimateur ?: return
        val taille = when (b) { is ByteArray -> b.size; is ShortArray -> b.size; else -> 0 }
        val max = taille / 2 / d.facteur + 2
        if (re.size < max) { re = FloatArray(max); im = FloatArray(max) }
        decalageHz = dec
        val n = when (b) {
            is ByteArray -> d.traiteU8(b, b.size, dec, re, im)
            is ShortArray -> d.traiteS16(b, b.size, dec, re, im)
            else -> 0
        }
        traiteBaseband(re, im, n, d.fsSortie)
    }

    /** For tests: the rate set without the thread. */
    fun prepare(fsEntree: Double) {
        decimateur = Decimateur(fsEntree, maxOf(1, Math.round(fsEntree / FS_CIBLE).toInt()), 22_000.0, 160)
    }

    /** Complex baseband at [fs] (the satellite near 0 Hz): the chain, synchronously (also for tests). */
    fun traiteBaseband(re: FloatArray, im: FloatArray, n: Int, fs: Double) {
        val voulu = debit
        var c = chaine
        if (c == null || c.son || c.debit != voulu || c.fs != fs) { c = Chaine(fs, voulu); chaine = c }
        c.traite(re, im, n)
        run {
            val dm = c.demod ?: return@run
            etat = etat.copy(debit = voulu, bits = dm.bits, crcFaux = c.g.crcFaux + c.n.crcFaux, afcHz = dm.afcHz,
                niveauDb = dm.niveauDb, plancherDb = dm.plancherDb, presence = dm.presence, signal = dm.verrouExterne, perdus = perdus,
                source = SOURCE_IQ, crete = 0.0, rate = 0)
        }
    }

    private var son = FloatArray(0)

    /**
     * Sound at [rate] Hz, 16-bit mono, from a rig's 9600 output: the chain,
     * synchronously (also for tests). The discriminator and the Doppler are
     * the rig's: no AFC, the frequency is the CAT's.
     */
    fun traiteSon(pcm: ShortArray, n: Int, rate: Int) {
        if (son.size < n) son = FloatArray(n)
        for (i in 0 until n) son[i] = pcm[i] / 32768f
        traiteSon(son, n, rate.toDouble())
    }

    /** The same, samples at full scale ±1. */
    fun traiteSon(x: FloatArray, n: Int, fs: Double) {
        val voulu = debit
        var c = chaine
        if (c == null || !c.son || c.debit != voulu || c.fs != fs) { c = Chaine(fs, voulu, son = true); chaine = c }
        decalageHz = 0.0
        c.traiteSon(x, n)
        val dm = c.demodSon ?: return
        // « Signal »: flags or a frame lately; « présence »: the same (a discriminator's noise is as loud as a signal).
        etat = etat.copy(debit = voulu, bits = dm.bits, crcFaux = c.g.crcFaux + c.n.crcFaux, afcHz = 0.0,
            niveauDb = dm.niveauDb, plancherDb = dm.plancherDb, presence = dm.verrouExterne, signal = dm.verrouExterne, perdus = perdus,
            source = SOURCE_SON, crete = dm.crete, rate = fs.toInt())
    }

    // ---------------------------------------------------------------- frames

    private val recents = java.util.ArrayDeque<Pair<Long, Int>>()

    private fun trame(avecFcs: ByteArray, g3ruh: Boolean) {
        val now = horloge()
        val o = avecFcs.copyOf(avecFcs.size - 2)
        // The same frame from both readings, or repeated by the satellite at once: kept once.
        val h = o.contentHashCode()
        synchronized(liste) {
            while (recents.isNotEmpty() && now - recents.first.first > 3_000) recents.pollFirst()
            if (recents.any { it.second == h }) return
            recents.addLast(now to h)
        }
        val cx = runCatching { contexte() }.getOrDefault(Contexte())
        val c = chaine
        val afc = c?.afcHz ?: 0.0
        val ecart = Math.round(decalageHz + afc)
        val ax = Ax25.entete(o)
        val ssdv = Ssdv.cherche(ax?.info ?: o).map { p -> image(p, cx, now) }
        val t = TramePaquet(now, o, ax, g3ruh, afc, if (cx.cielHz > 0) cx.cielHz + ecart else 0L,
            if (cx.reposHz > 0) cx.reposHz + ecart else 0L, cx.satellite, cx.norad, cx.essai, ssdv)
        synchronized(liste) {
            liste.addFirst(t)
            while (liste.size > MAX_TRAMES) liste.pollLast()
        }
        etat = etat.copy(trames = etat.trames + 1, derniereMs = now)
        if (!cx.essai) runCatching { ecrit(t) }
        runCatching { surTrame?.invoke(t) }
    }

    /** A Geoscan frame (72 bytes, CRC right): kept as a frame, its picture chunk put in its picture. */
    private fun trameGeoscan(o: ByteArray) {
        val now = horloge()
        val cx = runCatching { contexte() }.getOrDefault(Contexte())
        val c = chaine
        val afc = c?.afcHz ?: 0.0
        val ecart = Math.round(decalageHz + afc)
        val m = Geoscan.morceau(o)
        val im = m?.let { geoscan.takeIf { _ -> !cx.essai }?.ajoute(it, now, cx.satellite) ?: geoscanEssai.ajoute(it, now, cx.satellite) }
        val t = TramePaquet(now, o, Ax25.entete(o), false, afc, if (cx.cielHz > 0) cx.cielHz + ecart else 0L,
            if (cx.reposHz > 0) cx.reposHz + ecart else 0L, cx.satellite, cx.norad, cx.essai, emptyList(), FORMAT_GEOSCAN, im?.id)
        synchronized(liste) { liste.addFirst(t); while (liste.size > MAX_TRAMES) liste.pollLast() }
        etat = etat.copy(trames = etat.trames + 1, derniereMs = now)
        if (!cx.essai) runCatching { ecrit(t) }
        runCatching { surTrame?.invoke(t) }
    }
    /** The test signals' Geoscan pictures: shown, never written. */
    val geoscanEssai = Geoscan.Images(null, horloge)

    private fun image(p: Ssdv.Paquet, cx: Contexte, now: Long): Pair<String, Int> {
        val a = assemblage.ajoute(p, now, cx.satellite, AssemblageSsdv.SOURCE_RADIO, cx.essai)
        return (a.photo?.id ?: (p.indicatif + "-" + p.image)) to p.numero
    }

    /** One picture's JPEG as it stands (missing blocks grey). */
    fun jpeg(cle: String): ByteArray? = assemblage.photo(cle)?.jpeg()

    fun imagesJson(): JSONArray = assemblage.json()

    fun vide() {
        synchronized(liste) { liste.clear(); recents.clear() }
        // The pictures under way are the station's (on disk): only the test ones go.
        assemblage.videEssais()
        geoscanEssai.vide()
        etat = etat.copy(trames = 0)
    }

    // ---------------------------------------------------------------- the files of a pass

    private var passage: String? = null
    private var passageSat = ""
    private var passageDernier = 0L

    private fun ecrit(t: TramePaquet) {
        val d = File(dossier ?: return, "telemesure/paquets").apply { mkdirs() }
        if (passage == null || passageSat != t.satellite || t.t - passageDernier > NOUVEAU_PASSAGE_MS) {
            passage = jour(t.t, "yyyyMMdd-HHmm") + "-" + nomFichier(t.satellite.ifBlank { "inconnu" })
            passageSat = t.satellite
        }
        passageDernier = t.t
        File(d, "$passage.jsonl").appendText(json(t).put("hex", t.hex()).toString() + "\n")
        if (kiss) File(d, "$passage.kiss").appendBytes(Kiss.trame(t.octets))
    }

    companion object {
        /** About 9 samples a bit at 9600 bit/s. */
        const val FS_CIBLE = 88_200.0
        const val MAX_TRAMES = 300
        const val FORMAT_HDLC = "HDLC"
        const val FORMAT_GEOSCAN = "Geoscan"
        const val SOURCE_IQ = "iq"
        const val SOURCE_SON = "son"
        const val NOUVEAU_PASSAGE_MS = 20 * 60_000L
        val DEBITS = listOf(1200, 2400, 4800, 9600)

        private fun jour(ms: Long, f: String) = java.text.SimpleDateFormat(f, java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

        fun nomFichier(s: String) = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "sat" }

        /**
         * The bit rate of a SatNOGS DB transmitter this decoder reads (FSK,
         * GFSK, GMSK, MSK at 1200 to 9600 bit/s), or null (AFSK goes to the
         * APRS decoder, BPSK to the BPSK telemetry).
         */
        fun debitPour(mode: String?, baud: Double?): Int? {
            val m = mode?.uppercase() ?: return null
            if (m.contains("AFSK") || m.contains("PSK") || m.contains("LORA") || m.contains("CW")) return null
            if (!(m.contains("FSK") || m.contains("MSK"))) return null
            val b = baud?.let { Math.round(it).toInt() } ?: Regex("(\\d{4})").find(m)?.value?.toIntOrNull() ?: return null
            return b.takeIf { it in DEBITS }
        }

        /** What the transmitter's mode name says of its framing, as a hint (AX.100 « mode 5 » is not read here). */
        fun noteMode(mode: String?): String? {
            val m = mode?.uppercase() ?: return null
            return when {
                m.contains("GEOSCAN") -> "trames Geoscan (mot de synchro, PN9, CRC-16) lues avec les HDLC ; leurs images (morceaux de JPEG) rejoignent la galerie"
                m.contains("MODE 5") -> "trames ASM + Golay (« mode 5 ») : non décodées ici, seules les trames HDLC le sont"
                m.contains("USP") -> "protocole propre au satellite : seules les trames HDLC sont lues"
                else -> null
            }
        }

        fun json(t: TramePaquet): JSONObject {
            val o = JSONObject().put("t", t.t).put("n", t.octets.size).put("g3ruh", t.g3ruh).put("afc", Math.round(t.afcHz))
                .put("f", t.frequenceHz).put("fc", t.frequenceCorrigeeHz).put("sat", t.satellite).put("norad", t.norad).put("essai", t.essai)
                .put("format", t.format)
            t.geoscan?.let { o.put("geoscan", it) }
            t.ax25?.let { a ->
                o.put("ax25", JSONObject().put("de", a.source.toString()).put("vers", a.destination.toString())
                    .put("relais", JSONArray(a.relais.map { it.toString() })).put("ctl", a.controle).put("pid", a.pid ?: -1)
                    .put("texte", lisible(a.info)))
            }
            if (t.ssdv.isNotEmpty()) o.put("ssdv", JSONArray(t.ssdv.map { (c, n) -> JSONObject().put("cle", c).put("n", n) }))
            return o
        }

        /** Printable characters kept, the others as dots. */
        fun lisible(b: ByteArray): String = String(CharArray(minOf(b.size, 400)) { i -> val c = b[i].toInt() and 0xFF; if (c in 32..126) c.toChar() else '·' })
    }
}
