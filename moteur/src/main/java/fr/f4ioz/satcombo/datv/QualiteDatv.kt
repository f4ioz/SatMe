/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.datv.s2.Modcod
import fr.f4ioz.satcombo.datv.s2.Taux
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The reception's quality in one look: the margin (MER less what the MODCOD
 * needs), what the receiver's FEC says, what the transport stream says
 * ([AnalyseTs]), what the decoders say, and a verdict in plain words.
 */
object QualiteDatv {
    /**
     * The Es/N0 (dB) a DVB-S2 MODCOD needs for a quasi-error-free stream
     * (packet error rate 1e-7), normal frames, ideal receiver on a linear
     * channel: EN 302 307-1 table 13. A real receiver needs a little more.
     */
    private val SEUILS_S2 = mapOf(
        "QPSK 1/4" to -2.35, "QPSK 1/3" to -1.24, "QPSK 2/5" to -0.30, "QPSK 1/2" to 1.00, "QPSK 3/5" to 2.23, "QPSK 2/3" to 3.10,
        "QPSK 3/4" to 4.03, "QPSK 4/5" to 4.68, "QPSK 5/6" to 5.18, "QPSK 8/9" to 6.20, "QPSK 9/10" to 6.42,
        "8PSK 3/5" to 5.50, "8PSK 2/3" to 6.62, "8PSK 3/4" to 7.91, "8PSK 5/6" to 9.35, "8PSK 8/9" to 10.69, "8PSK 9/10" to 10.98,
        "16APSK 2/3" to 8.97, "16APSK 3/4" to 10.21, "16APSK 4/5" to 11.03, "16APSK 5/6" to 11.61, "16APSK 8/9" to 12.89, "16APSK 9/10" to 13.13,
        "32APSK 3/4" to 12.73, "32APSK 4/5" to 13.64, "32APSK 5/6" to 14.28, "32APSK 8/9" to 15.69, "32APSK 9/10" to 16.05)

    /** Short frames' LDPC codes are a little weaker: about this much more. */
    const val COURTES_DB = 0.3

    /**
     * DVB-S (EN 300 421, Viterbi then Reed-Solomon, QEF after RS): its
     * Eb/N0 4.5 / 5.0 / 5.5 / 6.0 / 6.4 dB for 1/2, 2/3, 3/4, 5/6, 7/8 as
     * Es/N0 (× 2·R·188/204); 6/7 in between.
     */
    private val SEUILS_S1 = mapOf("QPSK 1/2" to 4.15, "QPSK 2/3" to 5.9, "QPSK 3/4" to 6.9, "QPSK 5/6" to 7.9, "QPSK 6/7" to 8.2, "QPSK 7/8" to 8.5)

    /** The MER (dB) [modcod] needs, or null when unknown. */
    fun seuilDb(modcod: String, etat: Int, courtes: Boolean? = null): Double? = when (etat) {
        4 -> SEUILS_S2[modcod.trim()]?.let { it + if (courtes == true) COURTES_DB else 0.0 }
        3 -> SEUILS_S1[modcod.trim()]
        else -> null
    }

    private fun bits(c: String): Int? = when (c.uppercase()) { "QPSK" -> 2; "8PSK" -> 3; "16APSK" -> 4; "32APSK" -> 5; else -> null }

    private fun fraction(t: String): Pair<Int, Int>? = t.split('/').takeIf { it.size == 2 }?.let { (a, b) -> a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { x to it } } }

    /** A DVB-S2 frame's useful bits (DFL, frame full) for [modcod], or null. */
    fun bitsParTrame(modcod: String, courtes: Boolean?): Int? {
        val p = modcod.trim().split(' ')
        if (p.size != 2 || bits(p[0]) == null) return null
        val i = Taux.de(p[1])?.ordinal ?: return null
        val kbch = if (courtes == true) Modcod.KBCH_COURTE[i] else Modcod.KBCH_NORMALE[i]
        return if (kbch <= 0) null else kbch - 80
    }

    /** Transport stream packets a DVB-S2 frame of [modcod] carries (non-integer: packets straddle frames). */
    fun paquetsParTrame(modcod: String, courtes: Boolean?): Double? = bitsParTrame(modcod, courtes)?.let { it / 1504.0 }

    /**
     * The transport stream's rate (bit/s, 188-byte packets, null packets
     * included) [modcod] carries at [srKs], as the sender fills it: DVB-S2
     * frames full (CCM), DVB-S's 188/204 after Reed-Solomon.
     */
    fun debitTs(modcod: String, etat: Int, srKs: Int, courtes: Boolean?, pilotes: Boolean?): Double? {
        if (srKs <= 0) return null
        val p = modcod.trim().split(' ')
        if (p.size != 2) return null
        val rs = srKs * 1000.0
        if (etat == 3) {
            val (a, b) = fraction(p[1]) ?: return null
            return rs * 2 * a / b * 188.0 / 204.0
        }
        if (etat != 4) return null
        val m = bits(p[0]) ?: return null
        val dfl = bitsParTrame(modcod, courtes) ?: return null
        val slots = (if (courtes == true) 16_200 else 64_800) / m / 90
        val symboles = 90 * (slots + 1) + (if (pilotes == true) 36 * ((slots - 1) / 16) else 0)
        return rs * dfl / symboles
    }

    /** One line of ffmpeg's or ffplay's log: 'v' a picture's decoding, 'a' the sound's, 't' the stream's, null nothing. */
    fun classe(ligne: String): Char? {
        val l = ligne.trim()
        if (l.isEmpty()) return null
        val m = Regex("^\\[([a-z0-9_]+) @").find(l)?.groupValues?.get(1)
        return when {
            m == null -> 'v'.takeIf { l.contains("error while decoding", true) || l.contains("concealing", true) }
            m.startsWith("h264") || m.startsWith("hevc") || m.startsWith("mpeg2") || m.startsWith("mpeg4") || m == "mjpeg" -> 'v'
            m.startsWith("aac") || m.startsWith("mp2") || m.startsWith("mp3") || m.startsWith("ac3") || m.startsWith("opus") -> 'a'
            m == "mpegts" -> 't'
            else -> null
        }
    }

    /** "Last message repeated N times": the line before, N more. */
    fun repetitions(ligne: String): Int? = Regex("Last message repeated (\\d+) times").find(ligne)?.groupValues?.get(1)?.toIntOrNull()

    /** What is measured for the verdict. */
    data class Mesure(
        val actif: Boolean,
        val verrouille: Boolean,
        val margeDb: Double?,
        /** Continuity errors and transport errors (TEI) per second. */
        val ccParS: Double,
        val teiParS: Double,
        /** The receiver's FEC failing (BCH uncorrected), true; clean, false; unknown, null. */
        val fecEnEchec: Boolean?,
        /** The losses come by whole DVB-S2 frames. */
        val tramesEntieres: Boolean,
        val erreursVideoMin: Double,
        val erreursAudioMin: Double,
        /** Pictures made per second, null when nothing makes pictures here. */
        val imagesParS: Double?,
        val secondes: Int,
        /** The receiver's bit error rate (%), null when unknown. */
        val berPct: Double? = null,
    )

    /** 0 all well, 1 watch it, 2 the picture suffers; the verdict and what to do. */
    data class Verdict(val niveau: Int, val texte: String, val conseil: String = "")

    const val MARGE_JUSTE_DB = 1.0

    fun verdict(m: Mesure): Verdict {
        if (!m.actif) return Verdict(1, "Réception arrêtée")
        if (!m.verrouille) return Verdict(1, "Pas accroché", "vérifier la fréquence, le débit de symboles et l'oscillateur du LNB")
        val pertes = m.ccParS >= 0.2 || m.teiParS > 0
        val marge = m.margeDb
        val m1 = marge?.let { "%.1f".format(java.util.Locale.FRANCE, it) }
        if (pertes) {
            return when {
                marge != null && marge < MARGE_JUSTE_DB -> Verdict(2, "Signal juste : des trames sont perdues après correction (BCH)",
                    "rapprocher les antennes / augmenter le gain / choisir un MODCOD plus robuste")
                // The receiver's « BER » is left out: it stayed near 12 % with a perfect picture in QPSK 1/2 (16/10), not a measure to judge by.
                m.fecEnEchec == true || m.tramesEntieres -> Verdict(2,
                    "Trames entières perdues au démodulateur" + (if (m1 != null) " malgré " + m1 + " dB de marge" else "") + " : signal déformé plutôt que faible",
                    "baisser le gain d'émission ou de réception (saturation), vérifier l'émetteur (horloge, niveau), essayer un MODCOD plus robuste")
                m.fecEnEchec == false -> Verdict(2, "Paquets perdus mais trames correctes : le PC n'arrive pas à suivre",
                    "fermer ce qui charge le PC, ou recevoir par un SatMe-Node")
                else -> Verdict(2, "Paquets perdus, marge suffisante : le PC ou le réseau n'arrive peut-être pas à suivre")
            }
        }
        if (m.erreursVideoMin + m.erreursAudioMin > 3) return Verdict(1, "Erreurs de décodage vidéo seulement : débit vidéo trop haut / codage",
            "le flux arrive entier : c'est le codage à l'émission (débit vidéo, images clés, profil) qui est en cause")
        if (marge != null && marge < MARGE_JUSTE_DB) return Verdict(1, "Image correcte, mais signal juste",
            "la moindre baisse fera perdre des trames : un peu plus de signal ou un MODCOD plus robuste")
        if (m.imagesParS != null && m.imagesParS < 0.1 && m.secondes > 8) return Verdict(1, "Flux reçu entier, mais aucune image produite",
            "le service n'a peut-être pas de vidéo, ou elle n'est pas encore partie (image clé)")
        return Verdict(0, "Image parfaite")
    }

    /**
     * Losses by whole frames: most lossy PCR intervals miss a whole number
     * of frames' worth of packets ([ppt] each, give or take the packets
     * straddling a frame's edges).
     */
    fun tramesEntieres(manques: List<Double>, ppt: Double?): Boolean {
        if (ppt == null || ppt < 4 || manques.size < 3) return false
        val bons = manques.count { x -> val k = (x / ppt).roundToInt(); k >= 1 && abs(x - k * ppt) <= 1.5 + 0.05 * k }
        return bons >= 0.6 * manques.size
    }
}

/**
 * The quality followed second by second: rates over the last seconds,
 * errors over the last minute, two minutes of history for the curves.
 */
class SuiviQualite(private val capacite: Int = 120) {
    private data class Echantillon(val t: Long, val b: AnalyseTs.Bilan, val video: Long, val audio: Long, val flux: Long, val images: Long, val bch: Long, val ldpc: Long)

    private val echantillons = ArrayDeque<Echantillon>()
    private val histoMer = ArrayDeque<Double?>()
    private val histoMarge = ArrayDeque<Double?>()
    private val histoRatio = ArrayDeque<Double?>()
    private val histoCc = ArrayDeque<Double>()
    @Volatile private var dernier: JSONObject? = null
    private var debut = -1L

    /**
     * One second more: [e] the receiver's status, [b] the stream's
     * analysis, [video], [audio], [flux] the decoders' error lines so far,
     * [images] whether pictures are made here.
     */
    @Synchronized
    fun seconde(t: Long, e: EtatDatv, srKs: Int, b: AnalyseTs.Bilan, video: Long, audio: Long, flux: Long, imagesIci: Boolean): JSONObject {
        if (debut < 0) debut = t
        echantillons.addLast(Echantillon(t, b, video, audio, flux, e.images, e.bchTotal, e.ldpcTotal))
        while (echantillons.size > 61) echantillons.removeFirst()
        val n = echantillons.last()
        val c = echantillons.elementAt(maxOf(0, echantillons.size - 6))   // 5 s back
        val m = echantillons.first()                                        // a minute back
        val dt = (n.t - c.t) / 1000.0
        val dtm = (n.t - m.t) / 1000.0
        fun parS(f: (Echantillon) -> Long) = if (dt > 0) (f(n) - f(c)) / dt else 0.0
        fun parMin(f: (Echantillon) -> Long) = if (dtm > 0) (f(n) - f(m)) * 60.0 / dtm else 0.0

        val sr = if (e.srTrouveKs > 0) e.srTrouveKs else srKs
        val attendu = QualiteDatv.debitTs(e.modcod, e.etat, sr, e.trames, e.pilotes)?.takeIf { e.verrouille }
        val seuil = QualiteDatv.seuilDb(e.modcod, e.etat, e.trames)?.takeIf { e.verrouille }
        val mer = e.merDb?.takeIf { e.verrouille }
        val marge = if (mer != null && seuil != null) mer - seuil else null
        val recus = parS { it.b.paquets }
        val attenduPs = attendu?.let { it / 1504.0 }
        val ratio = if (attenduPs != null && attenduPs > 0 && dt > 0) recus / attenduPs else null
        val debitRef = attendu ?: b.debitPcr
        val ccs = parS { it.b.erreursCc }
        val teis = parS { it.b.tei }
        val bchs = parS { it.bch }
        val ppt = QualiteDatv.paquetsParTrame(e.modcod, e.trames).takeIf { e.etat == 4 }
        val entieres = debitRef != null && QualiteDatv.tramesEntieres(b.manquesParTrou(debitRef), ppt)
        val fec: Boolean? = when {
            e.bchNonCorrige == true || bchs > 0 -> true
            e.bchNonCorrige == false -> false
            else -> null
        }
        val imagesS = if (imagesIci) parS { it.images } else null
        val secondes = ((t - debut) / 1000).toInt()
        val v = QualiteDatv.verdict(QualiteDatv.Mesure(e.actif, e.verrouille, marge, ccs, teis, fec, entieres,
            parMin { it.video }, parMin { it.audio }, imagesS, secondes, e.berPct))

        fun <T> pousse(q: ArrayDeque<T>, x: T) { q.addLast(x); while (q.size > capacite) q.removeFirst() }
        pousse(histoMer, mer); pousse(histoMarge, marge); pousse(histoRatio, ratio); pousse(histoCc, ccs)

        val pidsAvant = c.b.pids.associateBy { it.pid }
        val pids = JSONArray()
        for (p in b.pids) {
            val avant = pidsAvant[p.pid]?.paquets ?: 0L
            pids.put(JSONObject().put("pid", p.pid).put("role", AnalyseTs.role(p.pid, b)).put("paquets", p.paquets)
                .put("kbps", if (dt > 0) (p.paquets - avant) * 1504 / dt / 1000 else 0.0).put("cc", p.erreursCc).put("manquants", p.manquants)
                .put("doublons", p.doublons).put("pcr", p.pcr))
        }
        val nulsKbps = if (dt > 0) (n.b.nuls - c.b.nuls) * 1504 / dt / 1000 else 0.0
        if (b.nuls > 0) pids.put(JSONObject().put("pid", 0x1FFF).put("role", "vide").put("paquets", b.nuls).put("kbps", nulsKbps)
            .put("cc", 0).put("manquants", 0).put("doublons", 0).put("pcr", false))
        fun nul(x: Double?) = x ?: JSONObject.NULL
        val o = JSONObject()
            .put("secondes", secondes)
            .put("recus", recus).put("attendus", nul(attenduPs)).put("ratio", nul(ratio))
            .put("debitAttendu", nul(attendu)).put("debitPcr", nul(b.debitPcr)).put("ratioPcr", nul(b.ratioPcr(debitRef)))
            .put("cc", ccs).put("tei", teis).put("manquantsS", parS { it.b.manquants })
            .put("totalPaquets", b.paquets).put("totalCc", b.erreursCc).put("totalManquants", b.manquants).put("totalTei", b.tei)
            .put("sync", b.pertesSync).put("doublons", b.doublons).put("pcrSauts", b.pcrSauts)
            .put("nulsPct", if (b.paquets > 0) 100.0 * b.nuls / b.paquets else 0.0)
            .put("pat", b.pat).put("pmt", b.pmt.isNotEmpty()).put("sdt", b.sdt).put("pcr", b.pcrPid >= 0).put("pcrPid", b.pcrPid)
            .put("pids", pids)
            .put("seuil", nul(seuil)).put("marge", nul(marge)).put("mer", nul(mer))
            .put("paquetsParTrame", nul(ppt)).put("tramesEntieres", entieres)
            .put("bchS", bchs).put("ldpcS", parS { it.ldpc }).put("fec", fec ?: JSONObject.NULL)
            .put("videoMin", parMin { it.video }).put("audioMin", parMin { it.audio }).put("fluxMin", parMin { it.flux })
            .put("imagesS", nul(imagesS))
            .put("verdict", JSONObject().put("niveau", v.niveau).put("texte", v.texte).put("conseil", v.conseil))
            .put("histo", JSONObject().put("mer", JSONArray(histoMer.map { nul(it) })).put("marge", JSONArray(histoMarge.map { nul(it) }))
                .put("ratio", JSONArray(histoRatio.map { nul(it) })).put("cc", JSONArray(histoCc.toList())))
        dernier = o
        return o
    }

    /** The last second's view, or null before the first. */
    fun json(): JSONObject? = dernier
}
