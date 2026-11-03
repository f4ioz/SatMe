/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.visu.SondeSignal
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Narrow FSK heard in USB — SO-127 (HADES-SA) and AMSAT-EA's other
 * « GENESIS » satellites — from a receiver's **audio** (the IC-9700 in
 * USB-D through its USB sound card, or the SDR demodulating USB), receive
 * only:
 *
 * - [DemodFskAudio] finds the two tones and gives the bits — with
 *   [Profil.AUTO] (the default) one at 200 bit/s and one at 800 bit/s on
 *   the same sound, each with its own deframer and search: AMSAT-EA's
 *   satellites alternate between the two rates;
 * - [Genesis.Deframeur] reads the frames (sync 0xBF35, CRC-16,
 *   descrambling): telemetry kept as frames, type 10 as SSDV;
 * - [ChercheurSsdv] looks for SSDV packets in the same bits whatever the
 *   framing (« 55 66 » at any bit, both polarities): the SSDV packets come
 *   out even if the framing was misread;
 * - the Codec2 voice packets (type 11) go to [voix] ([AssemblageVoix]);
 * - the SSDV packets go to [assemblage], the same pictures as the packet
 *   decoder's (the gallery, the passes put together);
 * - with [capture], the demodulated bits of each pass are written as they
 *   come (`telemesure/genesis/<pass>.bits`, MSB first, with a `.jsonl` of
 *   the frames), to study a format later — real receptions only;
 * - every telemetry frame and SSDV packet of a real reception goes to
 *   [archive] ([ArchiveGenesis]): the journal shows them with their pass.
 */
class RecepteurGenesis(
    private val dossier: File?,
    val assemblage: AssemblageSsdv,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** Where the frames and the packets heard are kept for the journal (shared by the live receiver and the read-agains). */
    @Volatile var archive: ArchiveGenesis? = dossier?.let { ArchiveGenesis(it) }

    /** A rate and its shift; [AUTO]: both demodulators at once on the same sound (the satellite alternates). */
    enum class Profil(val cle: String, val nom: String, val debit: Double, val ecartHz: Double) {
        AUTO("auto", "GENESIS 200 et 800 bit/s (les deux à la fois)", 800.0, 1_600.0),
        G800("800", "GENESIS 800 bit/s (1600 Hz)", 800.0, 1_600.0),
        G200("200", "GENESIS 200 bit/s (1125 Hz)", 200.0, 1_125.0);

        /** The single-rate profiles demodulated. */
        val voies: List<Profil> get() = if (this == AUTO) listOf(G200, G800) else listOf(this)

        companion object {
            fun de(cle: String?): Profil? = entries.firstOrNull { it.cle == cle }

            /**
             * The profile for a SatNOGS DB transmitter (GENESIS FSK, SSDV or FSK under 1200 bit/s), or null:
             * [AUTO] for 200 or 800 bit/s — AMSAT-EA's satellites alternate between the two (HADES-SA: a month each).
             */
            fun pour(mode: String?, baud: Double?): Profil? {
                val m = mode.orEmpty().uppercase()
                val narrow = m.contains("GENESIS") || m == "SSDV" ||
                    ((m.contains("FSK") && !m.contains("AFSK") && !m.contains("MFSK")) && baud != null && baud < 1_200.0)
                if (!narrow) return null
                val b = baud ?: return null
                return if (kotlin.math.abs(b - 200.0) < 1 || kotlin.math.abs(b - 800.0) < 1) AUTO else null
            }
        }
    }

    /** [enVue]: the satellite followed is above the horizon (voice heard otherwise is noise: it has no CRC). */
    class Contexte(val satellite: String = "", val norad: Int = 0, val essai: Boolean = false, val enVue: Boolean = true)

    class TrameGenesis(val t: Long, val trame: Genesis.Trame, val satellite: String, val essai: Boolean, val profil: String = "")

    data class Etat(
        val profil: String = Profil.AUTO.cle,
        /** The rate heard last (« 200 », « 800 »), "" before anything. */
        val recoit: String = "",
        val bits: Long = 0,
        val centreHz: Double = 0.0,
        val snrDb: Double = 0.0,
        val niveauDb: Double = -120.0,
        val presence: Boolean = false,
        val syncs: Long = 0,
        val trames: Int = 0,
        val crcFaux: Int = 0,
        val ssdv: Int = 0,
        val voix: Int = 0,
        val derniereMs: Long = 0,
        val capture: String = "",
    )

    @Volatile var contexte: () -> Contexte = { Contexte() }
    @Volatile var profil: Profil = Profil.AUTO
    /** The demodulated bits written per pass (real receptions only). */
    @Volatile var capture = false
    /** What the pictures' passes are labelled with: the radio, or a recording read again. */
    @Volatile var source: String = AssemblageSsdv.SOURCE_RADIO
    /** Each SSDV packet kept (after [assemblage]): the picture's key. */
    @Volatile var surSsdv: ((String) -> Unit)? = null

    /** The Codec2 voice messages (HADES-SA's BBS), written beside the captures when [dossier] is set. */
    val voix = AssemblageVoix(dossier)

    @Volatile var etat = Etat(); private set
    private val liste = java.util.ArrayDeque<TrameGenesis>()
    val trames: List<TrameGenesis> get() = synchronized(liste) { liste.toList() }

    /**
     * The views' tap: the soft stream's eye, the two tones' spectrum, the
     * bits (MSB first) with the GENESIS sync word and SSDV's marked — the
     * demodulator of the rate heard last.
     */
    val sonde = SondeSignal("genesis", "GENESIS FSK (BLU)", setOf(SondeSignal.Vue.OEIL, SondeSignal.Vue.TONS, SondeSignal.Vue.BITS),
        motifs = listOf(SondeSignal.Motif("synchro BF35", Genesis.SYNC.toLong(), 16, 1, true),
            SondeSignal.Motif("SSDV 55 66", 0x5566, 16, 0, true), SondeSignal.Motif("SSDV 55 67", 0x5567, 16, 0, true)),
        horloge = horloge)

    /** One rate's chain: demodulator, deframer, SSDV search, its own capture. */
    private inner class Voie(val p: Profil, rate: Int) {
        val deframeur: Genesis.Deframeur
        val demod: DemodFskAudio
        val bitsCapture = java.io.ByteArrayOutputStream()
        var octetCapture = 0
        var nbBitsCapture = 0
        var passage: String? = null

        init {
            // A bit wrong in a sync word is forgiven: the checks after (CRC-16, SSDV's CRC-32 and Reed-Solomon) decide.
            val df = Genesis.Deframeur({ t -> trame(t, this) }, { o -> Ssdv.lis(o)?.let { paquet(it, this) } }, toleranceSync = 1,
                surVoix = { a, n, o -> voixRecue(a, n, o, this) })
            val ch = ChercheurSsdv(lsbAussi = false, tolerance = 1) { pq, _ -> paquet(pq, this) }
            deframeur = df
            demod = DemodFskAudio(rate.toDouble(), p.debit, p.ecartHz) { b, _ ->
                if (this === active && sonde.actif) sonde.bit(b)
                df.bit(b); ch.bit(b); if (captureOuverte) captureBit(this, b)
            }
        }
    }

    private var voies: List<Voie> = emptyList()
    private var voiesProfil: Profil? = null
    private var voiesRate = 0
    /** The chain whose rate was heard last: the views and the state show it. */
    private var active: Voie? = null
    private var ssdvVus = 0
    private val recents = LinkedHashMap<Long, Long>()
    private val tramesRecentes = LinkedHashMap<Long, Long>()

    private fun chaines(rate: Int): List<Voie> {
        val p = profil
        if (voiesProfil == p && voiesRate == rate) return voies
        voies = p.voies.map { Voie(it, rate) }
        voiesProfil = p; voiesRate = rate
        rendActive(voies.first())
        return voies
    }

    private fun rendActive(v: Voie) {
        if (active === v) return
        val avant = active
        avant?.demod?.sonde = null
        active = v
        if (avant != null) sonde.oublie()
        v.demod.sonde = sonde
    }

    /** [n] audio samples at [rate] Hz (16-bit). */
    @Synchronized
    fun traite(pcm: ShortArray, n: Int, rate: Int) {
        val cx = runCatching { contexte() }.getOrDefault(Contexte())
        contexteCourant = cx
        captureOuverte = capture && !cx.essai && dossier != null
        val vs = chaines(rate)
        for (v in vs) v.demod.traite(pcm, n)
        // Which one is heard, if no frame said it yet: the demodulator that sees its pair of tones best.
        if (etat.recoit.isEmpty() && active?.demod?.presence != true) vs.filter { it.demod.presence }.maxByOrNull { it.demod.snrDb }?.let { rendActive(it) }
        val a = active ?: vs.first()
        etat = etat.copy(profil = profil.cle, bits = a.demod.bits, centreHz = a.demod.centreHz, snrDb = a.demod.snrDb, niveauDb = a.demod.niveauDb,
            presence = vs.any { it.demod.presence }, syncs = vs.sumOf { it.deframeur.syncs }, crcFaux = vs.sumOf { it.deframeur.crcFaux }, ssdv = ssdvVus)
        if (captureOuverte) for (v in vs) videCapture(v)
    }

    @Volatile private var contexteCourant = Contexte()

    /** Heard on [v]: it becomes the one shown. */
    private fun entendu(v: Voie) {
        rendActive(v)
        if (etat.recoit != v.p.cle) etat = etat.copy(recoit = v.p.cle)
    }

    private fun trame(t: Genesis.Trame, v: Voie) {
        val now = horloge()
        // The same frame from both chains (or both polarities): kept once.
        val cle = java.util.zip.CRC32().apply { update(t.brut) }.value
        tramesRecentes.entries.removeIf { now - it.value > 60_000L }
        if (tramesRecentes.containsKey(cle)) return
        tramesRecentes[cle] = now
        entendu(v)
        val cx = contexteCourant
        val tg = TrameGenesis(now, t, cx.satellite, cx.essai, v.p.cle)
        // The sync word, the size byte, the bytes and the CRC.
        sonde.trame("trame " + t.nomType, 16 + 8 + (t.brut.size + 2) * 8)
        garde(tg)
        etat = etat.copy(trames = etat.trames + 1, derniereMs = now)
        if (captureOuverte) runCatching { ecritTrame(tg, v) }
        if (!cx.essai) runCatching { archive?.trame(now, cx.satellite, cx.norad, t.type, t.donnees, source) }
    }

    private fun garde(tg: TrameGenesis) {
        synchronized(liste) { liste.addFirst(tg); while (liste.size > MAX_TRAMES) liste.pollLast() }
    }

    private fun voixRecue(adresse: Int, numero: Int, octets: ByteArray, v: Voie) {
        val now = horloge()
        val cx = contexteCourant
        if (!cx.enVue && !cx.essai) return
        if (!voix.ajoute(numero, octets, now, cx.satellite, cx.essai)) return
        entendu(v)
        sonde.trame("voix Codec2", 16 + 8 + VoixGenesis.TAILLE * 8)
        val brut = byteArrayOf(((Genesis.TYPE_CODEC2 shl 4) or adresse).toByte(), numero.toByte()) + octets
        garde(TrameGenesis(now, Genesis.Trame(Genesis.TYPE_CODEC2, adresse, brut, byteArrayOf(numero.toByte()), false),
            cx.satellite, cx.essai, v.p.cle))
        etat = etat.copy(voix = etat.voix + 1, derniereMs = now)
    }

    /**
     * The voice message being put together, ended when no packet came for [VoixGenesis.FIN_MS], or when its
     * satellite is below the horizon or no longer followed ([AssemblageVoix.verifie]). Called on a timer: it
     * holds when the sound stops too.
     */
    fun finVoix() {
        val cx = runCatching { contexte() }.getOrNull()
        voix.verifie(horloge()) { sat -> cx == null || (cx.enVue && cx.satellite == sat) }
    }

    private fun paquet(p: Ssdv.Paquet, v: Voie) {
        val now = horloge()
        val cle = java.util.zip.CRC32().apply { update(p.brut) }.value
        // Found twice (by the deframer and the search, or by both chains): kept once.
        recents.entries.removeIf { now - it.value > 60_000L }
        if (recents.containsKey(cle)) return
        recents[cle] = now
        entendu(v)
        val cx = contexteCourant
        val a = assemblage.ajoute(p, now, cx.satellite, source, cx.essai)
        val ph = a.photo
        if (!cx.essai && ph != null && a.resultat != AssemblageSsdv.Resultat.REFUSE)
            runCatching { archive?.ssdv(now, cx.satellite, cx.norad, ph.id, p.numero, source) }
        sonde.trame("paquet SSDV", p.brut.size * 8)
        ssdvVus++
        etat = etat.copy(derniereMs = now)
        a.photo?.id?.let { id -> runCatching { surSsdv?.invoke(id) } }
    }

    fun vide() {
        synchronized(liste) { liste.clear() }
        ssdvVus = 0
        etat = etat.copy(trames = 0, ssdv = 0, voix = 0, recoit = "")
    }

    // ---------------------------------------------------------------- capture

    @Volatile private var captureOuverte = false
    private var passageDernier = 0L

    private fun nomPassage(v: Voie): String {
        val now = horloge()
        if (v.passage == null || now - passageDernier > NOUVEAU_PASSAGE_MS) {
            val f = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            val base = f.format(java.util.Date(now)) + "-" + RecepteurPaquets.nomFichier(contexteCourant.satellite.ifBlank { "inconnu" })
            for (w in voies) w.passage = base + "-" + w.p.cle
        }
        passageDernier = now
        return v.passage!!
    }

    private fun captureBit(v: Voie, b: Int) {
        v.octetCapture = (v.octetCapture shl 1) or b
        if (++v.nbBitsCapture == 8) { v.bitsCapture.write(v.octetCapture); v.octetCapture = 0; v.nbBitsCapture = 0 }
    }

    private fun videCapture(v: Voie) {
        if (v.bitsCapture.size() == 0) return
        runCatching {
            val d = File(dossier ?: return, "telemesure/genesis").apply { mkdirs() }
            val nom = nomPassage(v)
            File(d, "$nom.bits").appendBytes(v.bitsCapture.toByteArray())
            if (v === active) etat = etat.copy(capture = "$nom.bits")
        }
        v.bitsCapture.reset()
    }

    private fun ecritTrame(t: TrameGenesis, v: Voie) {
        val d = File(dossier ?: return, "telemesure/genesis").apply { mkdirs() }
        File(d, nomPassage(v) + ".jsonl").appendText(json(t).toString() + "\n")
    }

    /** The receiver's state for the page. */
    fun json(): JSONObject {
        val e = etat
        return JSONObject().put("profil", e.profil).put("recoit", e.recoit).put("voix", e.voix).put("bits", e.bits).put("centreHz", Math.round(e.centreHz))
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
            .put("crc", t.trame.crcJuste).put("debit", t.profil)
            .put("hex", AssemblageSsdv.hex(if (t.trame.type == Genesis.TYPE_CODEC2 && !t.trame.crcJuste) t.trame.brut else t.trame.donnees))
            .put("texte", if (t.trame.type == Genesis.TYPE_CODEC2 && !t.trame.crcJuste) "paquet de voix n° " + (t.trame.donnees[0].toInt() and 0xFF) + " (Codec2 700C, 0,4 s ; pas de CRC dans ce format)"
                else RecepteurPaquets.lisible(t.trame.donnees))

        /**
         * A capture file read again ([bits] MSB first): the frames and SSDV
         * packets it holds, for a format checked later or a decoder improved.
         */
        fun relit(bits: ByteArray, surTrame: (Genesis.Trame) -> Unit, surPaquet: (Ssdv.Paquet) -> Unit,
                  surVoix: (numero: Int, octets: ByteArray) -> Unit = { _, _ -> }) {
            val vus = HashSet<Long>()
            fun p(pq: Ssdv.Paquet) { if (vus.add(java.util.zip.CRC32().apply { update(pq.brut) }.value)) surPaquet(pq) }
            val df = Genesis.Deframeur(surTrame, { o -> Ssdv.lis(o)?.let { p(it) } }, surVoix = { _, n, o -> surVoix(n, o) })
            val ch = ChercheurSsdv(lsbAussi = true) { pq, _ -> p(pq) }
            for (o in bits) for (k in 7 downTo 0) { val b = (o.toInt() ushr k) and 1; df.bit(b); ch.bit(b) }
        }
    }
}
