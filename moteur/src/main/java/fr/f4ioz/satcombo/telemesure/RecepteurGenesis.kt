/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Narrow FSK heard in USB — SO-127 (HADES-SA) and AMSAT-EA's other
 * « GENESIS » satellites — from a receiver's **audio** (the IC-9700 in
 * USB-D through its USB sound card, or the SDR demodulating USB), receive
 * only:
 *
 * - [DemodFskAudio] finds the two tones and gives the bits;
 * - [Genesis.Deframeur] reads the frames (sync 0xBF35, CRC-16,
 *   descrambling): telemetry kept as frames, type 10 as SSDV;
 * - [ChercheurSsdv] looks for SSDV packets in the same bits whatever the
 *   framing (« 55 66 » at any bit, both polarities): the SSDV packets come
 *   out even if the framing was misread;
 * - the SSDV packets go to [assemblage], the same pictures as the packet
 *   decoder's (the gallery, the passes put together);
 * - with [capture], the demodulated bits of each pass are written as they
 *   come (`telemesure/genesis/<pass>.bits`, MSB first, with a `.jsonl` of
 *   the frames), to study a format later — real receptions only.
 */
class RecepteurGenesis(
    private val dossier: File?,
    val assemblage: AssemblageSsdv,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** A rate and its shift. */
    enum class Profil(val cle: String, val nom: String, val debit: Double, val ecartHz: Double) {
        G800("800", "GENESIS 800 bit/s (1600 Hz)", 800.0, 1_600.0),
        G200("200", "GENESIS 200 bit/s (1125 Hz)", 200.0, 1_125.0);

        companion object {
            fun de(cle: String?): Profil? = entries.firstOrNull { it.cle == cle }

            /** The profile of a SatNOGS DB transmitter (GENESIS FSK, SSDV or FSK under 1200 bit/s), or null. */
            fun pour(mode: String?, baud: Double?): Profil? {
                val m = mode.orEmpty().uppercase()
                val narrow = m.contains("GENESIS") || m == "SSDV" ||
                    ((m.contains("FSK") && !m.contains("AFSK") && !m.contains("MFSK")) && baud != null && baud < 1_200.0)
                if (!narrow) return null
                val b = baud ?: return null
                return when {
                    kotlin.math.abs(b - 200.0) < 1 -> G200
                    kotlin.math.abs(b - 800.0) < 1 -> G800
                    else -> null
                }
            }
        }
    }

    class Contexte(val satellite: String = "", val norad: Int = 0, val essai: Boolean = false)

    class TrameGenesis(val t: Long, val trame: Genesis.Trame, val satellite: String, val essai: Boolean)

    data class Etat(
        val profil: String = Profil.G800.cle,
        val bits: Long = 0,
        val centreHz: Double = 0.0,
        val snrDb: Double = 0.0,
        val niveauDb: Double = -120.0,
        val presence: Boolean = false,
        val syncs: Long = 0,
        val trames: Int = 0,
        val crcFaux: Int = 0,
        val ssdv: Int = 0,
        val derniereMs: Long = 0,
        val capture: String = "",
    )

    @Volatile var contexte: () -> Contexte = { Contexte() }
    @Volatile var profil: Profil = Profil.G800
    /** The demodulated bits written per pass (real receptions only). */
    @Volatile var capture = false
    /** What the pictures' passes are labelled with: the radio, or a recording read again. */
    @Volatile var source: String = AssemblageSsdv.SOURCE_RADIO
    /** Each SSDV packet kept (after [assemblage]): the picture's key. */
    @Volatile var surSsdv: ((String) -> Unit)? = null

    @Volatile var etat = Etat(); private set
    private val liste = java.util.ArrayDeque<TrameGenesis>()
    val trames: List<TrameGenesis> get() = synchronized(liste) { liste.toList() }

    private var demod: DemodFskAudio? = null
    private var deframeur: Genesis.Deframeur? = null
    private var chercheur: ChercheurSsdv? = null
    private var ssdvVus = 0
    private val recents = LinkedHashMap<Long, Long>()

    private fun chaine(rate: Int): DemodFskAudio {
        val p = profil
        demod?.takeIf { it.fs == rate.toDouble() && it.debit == p.debit }?.let { return it }
        // A bit wrong in a sync word is forgiven: the checks after (CRC-16, SSDV's CRC-32 and Reed-Solomon) decide.
        val df = Genesis.Deframeur({ t -> trame(t) }, { o -> Ssdv.lis(o)?.let { paquet(it) } }, toleranceSync = 1)
        val ch = ChercheurSsdv(lsbAussi = false, tolerance = 1) { pq, _ -> paquet(pq) }
        deframeur = df; chercheur = ch
        val d = DemodFskAudio(rate.toDouble(), p.debit, p.ecartHz) { b, _ ->
            df.bit(b); ch.bit(b); if (captureOuverte) captureBit(b)
        }
        demod = d
        return d
    }

    /** [n] audio samples at [rate] Hz (16-bit). */
    @Synchronized
    fun traite(pcm: ShortArray, n: Int, rate: Int) {
        val cx = runCatching { contexte() }.getOrDefault(Contexte())
        contexteCourant = cx
        captureOuverte = capture && !cx.essai && dossier != null
        val d = chaine(rate)
        d.traite(pcm, n)
        val df = deframeur
        etat = etat.copy(profil = profil.cle, bits = d.bits, centreHz = d.centreHz, snrDb = d.snrDb, niveauDb = d.niveauDb,
            presence = d.presence, syncs = df?.syncs ?: 0, crcFaux = df?.crcFaux ?: 0, ssdv = ssdvVus)
        if (captureOuverte) videCapture()
    }

    @Volatile private var contexteCourant = Contexte()

    private fun trame(t: Genesis.Trame) {
        val now = horloge()
        val cx = contexteCourant
        val tg = TrameGenesis(now, t, cx.satellite, cx.essai)
        synchronized(liste) { liste.addFirst(tg); while (liste.size > MAX_TRAMES) liste.pollLast() }
        etat = etat.copy(trames = etat.trames + 1, derniereMs = now)
        if (captureOuverte) runCatching { ecritTrame(tg) }
    }

    private fun paquet(p: Ssdv.Paquet) {
        val now = horloge()
        val cle = java.util.zip.CRC32().apply { update(p.brut) }.value
        // Found twice (by the deframer and the search): kept once.
        recents.entries.removeIf { now - it.value > 60_000L }
        if (recents.containsKey(cle)) return
        recents[cle] = now
        val cx = contexteCourant
        val a = assemblage.ajoute(p, now, cx.satellite, source, cx.essai)
        ssdvVus++
        etat = etat.copy(derniereMs = now)
        a.photo?.id?.let { id -> runCatching { surSsdv?.invoke(id) } }
    }

    fun vide() {
        synchronized(liste) { liste.clear() }
        ssdvVus = 0
        etat = etat.copy(trames = 0, ssdv = 0)
    }

    // ---------------------------------------------------------------- capture

    @Volatile private var captureOuverte = false
    private val bitsCapture = java.io.ByteArrayOutputStream()
    private var octetCapture = 0
    private var nbBitsCapture = 0
    private var passage: String? = null
    private var passageDernier = 0L

    private fun nomPassage(): String {
        val now = horloge()
        if (passage == null || now - passageDernier > NOUVEAU_PASSAGE_MS) {
            val f = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            passage = f.format(java.util.Date(now)) + "-" + RecepteurPaquets.nomFichier(contexteCourant.satellite.ifBlank { "inconnu" }) + "-" + profil.cle
        }
        passageDernier = now
        return passage!!
    }

    private fun captureBit(b: Int) {
        octetCapture = (octetCapture shl 1) or b
        if (++nbBitsCapture == 8) { bitsCapture.write(octetCapture); octetCapture = 0; nbBitsCapture = 0 }
    }

    private fun videCapture() {
        if (bitsCapture.size() == 0) return
        runCatching {
            val d = File(dossier ?: return, "telemesure/genesis").apply { mkdirs() }
            val nom = nomPassage()
            File(d, "$nom.bits").appendBytes(bitsCapture.toByteArray())
            etat = etat.copy(capture = "$nom.bits")
        }
        bitsCapture.reset()
    }

    private fun ecritTrame(t: TrameGenesis) {
        val d = File(dossier ?: return, "telemesure/genesis").apply { mkdirs() }
        File(d, nomPassage() + ".jsonl").appendText(json(t).toString() + "\n")
    }

    /** The receiver's state for the page. */
    fun json(): JSONObject {
        val e = etat
        return JSONObject().put("profil", e.profil).put("bits", e.bits).put("centreHz", Math.round(e.centreHz))
            .put("snrDb", Math.round(e.snrDb * 10) / 10.0).put("niveauDb", Math.round(e.niveauDb * 10) / 10.0)
            .put("presence", e.presence).put("syncs", e.syncs).put("trames", e.trames).put("crcFaux", e.crcFaux)
            .put("ssdv", e.ssdv).put("derniereMs", e.derniereMs).put("capture", e.capture)
            .put("liste", JSONArray(trames.take(30).map { json(it) }))
    }

    companion object {
        const val MAX_TRAMES = 200
        const val NOUVEAU_PASSAGE_MS = 20 * 60_000L

        fun json(t: TrameGenesis): JSONObject = JSONObject().put("t", t.t).put("type", t.trame.type).put("nom", t.trame.nomType)
            .put("de", t.trame.emetteur).put("n", t.trame.brut.size).put("sat", t.satellite).put("essai", t.essai)
            .put("hex", AssemblageSsdv.hex(t.trame.donnees)).put("texte", RecepteurPaquets.lisible(t.trame.donnees))

        /**
         * A capture file read again ([bits] MSB first): the frames and SSDV
         * packets it holds, for a format checked later or a decoder improved.
         */
        fun relit(bits: ByteArray, surTrame: (Genesis.Trame) -> Unit, surPaquet: (Ssdv.Paquet) -> Unit) {
            val vus = HashSet<Long>()
            fun p(pq: Ssdv.Paquet) { if (vus.add(java.util.zip.CRC32().apply { update(pq.brut) }.value)) surPaquet(pq) }
            val df = Genesis.Deframeur(surTrame, { o -> Ssdv.lis(o)?.let { p(it) } })
            val ch = ChercheurSsdv(lsbAussi = true) { pq, _ -> p(pq) }
            for (o in bits) for (k in 7 downTo 0) { val b = (o.toInt() ushr k) and 1; df.bit(b); ch.bit(b) }
        }
    }
}
