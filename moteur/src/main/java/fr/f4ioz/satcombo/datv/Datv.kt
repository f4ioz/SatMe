/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject

/**
 * DATV reception with a MiniTiouner, what the PC, the SatMe-Node and the
 * phone share: what to receive, what the receiver says, LongMynd's words.
 */
data class ReglageDatv(
    val cielKhz: Long = Datv.BALISE_KHZ,
    val srKs: Int = 1500,
    val olKhz: Long = 9_750_000L,
    /** The NIM's bottom input rather than the top one. */
    val entreeB: Boolean = false,
    /** LNB supply: "" off, "v" 13 V, "h" 18 V. */
    val lnb: String = "",
    val son: Boolean = true,
    /**
     * Other symbol rates to try in turn when the first finds nothing
     * (LongMynd cycles through them, [Datv.debitsEssayes]); empty: the one rate.
     */
    val balayage: List<Int> = emptyList(),
    /**
     * LongMynd's search half-width (`-S`), as a share of the rate: 0 its
     * own (±1.5 × the rate). Narrower keeps it off a neighbour.
     */
    val demiLargeur: Double = 0.0,
    /** How long LongMynd waits without packets before starting again (`-r`, ms): 0 [Datv.delaiTsMs]'s. */
    val attenteMs: Int = 0,
) {
    val fiKhz: Long get() = cielKhz - olKhz

    /** The rate locked on when scanning: the one tried nearest to the measured [srTrouveKs]; else [srKs]. */
    fun debitAccroche(srTrouveKs: Int): Int =
        if (balayage.isEmpty() || srTrouveKs <= 0) srKs else Datv.debitsEssayes(this).minByOrNull { Math.abs(it - srTrouveKs) } ?: srKs

    /** Why this cannot be received, or null. */
    fun refus(): String? = when {
        fiKhz !in Datv.FI_MIN_KHZ..Datv.FI_MAX_KHZ ->
            "fréquence intermédiaire hors de portée du MiniTiouner : %.3f MHz (ciel %.3f − OL %.3f), il faut 144 à 2450 MHz".format(
                java.util.Locale.FRANCE, fiKhz / 1000.0, cielKhz / 1000.0, olKhz / 1000.0)
        srKs !in 33..27_500 -> "débit de symboles hors de portée : $srKs kS/s"
        else -> null
    }
}

data class EtatDatv(
    val actif: Boolean = false,
    /** LongMynd's state: 0 starting, 1 searching, 2 headers, 3 DVB-S, 4 DVB-S2. */
    val etat: Int = 0,
    val fiTrouveeKhz: Long = 0L,
    val srTrouveKs: Int = 0,
    val merDb: Double? = null,
    val modcod: String = "",
    val fournisseur: String = "",
    val service: String = "",
    val nulsPct: Int = 0,
    /** Elementary streams: PID and type (0x1B H.264, 0x24 H.265, 0x03/0x04 MPEG audio, 0x0F AAC…). */
    val flux: List<Pair<Int, Int>> = emptyList(),
    val agc1: Int = 0, val agc2: Int = 0,
    val berPct: Double? = null,
    val trames: Boolean? = null, val pilotes: Boolean? = null,
    /** The LNA's gain word (boards with LNAs), the I and Q input powers. */
    val lna: Int? = null, val puissanceI: Int? = null, val puissanceQ: Int? = null,
    /** DVB-S: the Viterbi error rate (%) and the code rate n/(n+1) found. */
    val viterbiPct: Double? = null, val poinconnage: Int? = null,
    /** DVB-S2: the LDPC and BCH error counters as read, BCH's uncorrected flag. */
    val ldpc: Int? = null, val bch: Int? = null, val bchNonCorrige: Boolean? = null,
    /** The same counters followed through their wraps since the start. */
    val ldpcTotal: Long = 0L, val bchTotal: Long = 0L,
    val kbps: Int = 0,
    val images: Long = 0L,
    val enregistrement: String = "",
    val erreur: String = "",
    val depuis: Long = 0L,
) {
    val verrouille: Boolean get() = etat >= 3
    val libelle: String get() = Datv.libelle(etat)
}

object Datv {
    /** QO-100's wide band beacon: 10491.5 MHz, 1.5 MS/s DVB-S2 QPSK. */
    const val BALISE_KHZ = 10_491_500L
    /** What the MiniTiouner's tuner covers. */
    const val FI_MIN_KHZ = 144_000L
    const val FI_MAX_KHZ = 2_450_000L
    /** The SDR receivers' tuning range (ADALM-PLUTO, bladeRF), kHz. */
    const val FI_SDR_MIN_KHZ = 70_000L
    const val FI_SDR_MAX_KHZ = 6_000_000L
    /** The receivers: the MiniTiouner (LongMynd), or an SDR with SatMe's own DVB-S2 demodulator. */
    const val MINITIOUNER = "minitiouner"
    const val PLUTO = "pluto"
    const val BLADERF = "bladerf"
    val RECEPTEURS = listOf(MINITIOUNER, PLUTO, BLADERF)

    fun libelleRecepteur(r: String): String = when (r) { PLUTO -> "ADALM-PLUTO"; BLADERF -> "bladeRF 2.0"; else -> "MiniTiouner" }

    /** The symbol rates of QO-100's wide band. */
    val DEBITS = listOf(125, 250, 333, 500, 1000, 1500, 2000)
    /** The narrow channels, every 500 kHz from 10492.75 to 10499.25 MHz. */
    val CANAUX_KHZ = (0..13).map { 10_492_750L + it * 500L }

    fun libelle(etat: Int) = when (etat) {
        0 -> "démarrage"; 1 -> "recherche"; 2 -> "en-têtes trouvés"; 3 -> "accroché DVB-S"; 4 -> "accroché DVB-S2"; else -> "?"
    }

    /** "$n,m" lines (one or several per datagram, ended by CR). */
    fun lignes(t: String): List<Pair<Int, String>> =
        t.split('$').mapNotNull { m ->
            val l = m.trimEnd('\r', '\n', '\u0000')
            val i = l.indexOf(',')
            if (i <= 0) null else l.substring(0, i).toIntOrNull()?.let { it to l.substring(i + 1) }
        }

    /** A constellation point is a signed byte, sent as 0..255 or sign-extended to 32 bits: its low byte. */
    fun signe(v: String): Int? = v.trim().toLongOrNull()?.let { (it and 0xFF).toByte().toInt() }

    private val S2 = listOf("DummyPL", "QPSK 1/4", "QPSK 1/3", "QPSK 2/5", "QPSK 1/2", "QPSK 3/5", "QPSK 2/3", "QPSK 3/4", "QPSK 4/5",
        "QPSK 5/6", "QPSK 8/9", "QPSK 9/10", "8PSK 3/5", "8PSK 2/3", "8PSK 3/4", "8PSK 5/6", "8PSK 8/9", "8PSK 9/10",
        "16APSK 2/3", "16APSK 3/4", "16APSK 4/5", "16APSK 5/6", "16APSK 8/9", "16APSK 9/10",
        "32APSK 3/4", "32APSK 4/5", "32APSK 5/6", "32APSK 8/9", "32APSK 9/10")
    private val S1 = listOf("QPSK 1/2", "QPSK 2/3", "QPSK 3/4", "QPSK 5/6", "QPSK 6/7", "QPSK 7/8")

    /** The MODCOD in words, DVB-S2's table when locked on S2, DVB-S's otherwise. */
    fun modcod(n: Int, etat: Int): String = (if (etat == 3) S1 else S2).getOrNull(n).orEmpty()

    /** What an elementary stream type is. */
    fun typeFlux(t: Int): String = when (t) {
        0x01, 0x02 -> "MPEG-2"; 0x1B -> "H.264"; 0x24 -> "H.265"; 0x03, 0x04 -> "MPEG audio"; 0x0F -> "AAC"; 0x11 -> "AAC LATM"
        0x81 -> "AC-3"; 0x06 -> "données"; else -> "type 0x" + t.toString(16)
    }

    /**
     * The settings a receiver works with, from its status ([json]): what the
     * phone takes up when it comes back to a node already receiving, so that
     * tuning elsewhere keeps the LNB supply as it is (an older node does not
     * say it: [ici]'s kept).
     */
    /**
     * The symbol rates LongMynd tries, in this order: [r]'s own, then its
     * [ReglageDatv.balayage] (4 at most, as LongMynd takes them), each tried
     * for [delaiTsMs] before the next.
     */
    fun debitsEssayes(r: ReglageDatv): List<Int> = (listOf(r.srKs) + r.balayage.filter { it in 33..27_500 }).distinct().take(4)

    /**
     * How long LongMynd waits without packets before trying again (and the
     * next rate when scanning): 5 s, longer when scanning low rates, slower
     * to lock (twice at 250 kS/s and below, four times at 125 and below, as
     * BATC's receivers do); one rate alone keeps 5 s.
     */
    fun delaiTsMs(debits: List<Int>): Int {
        if (debits.size <= 1) return 5000
        val m = debits.minOrNull() ?: return 5000
        return when { m <= 125 -> 20_000; m <= 250 -> 10_000; else -> 5000 }
    }

    /** "333,500" as rates (kS/s), the wrong ones left out. */
    fun balayageDe(t: String?): List<Int> = t.orEmpty().split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 33..27_500 }.distinct().take(3)

    fun reglageDe(j: JSONObject, ici: ReglageDatv): ReglageDatv = ici.copy(
        balayage = if (j.has("balayage")) balayageDe(j.optString("balayage")) else ici.balayage,
        cielKhz = j.optLong("ciel", ici.cielKhz).takeIf { it > 0 } ?: ici.cielKhz,
        srKs = j.optInt("sr", ici.srKs).takeIf { it > 0 } ?: ici.srKs,
        olKhz = j.optLong("ol", ici.olKhz).takeIf { it > 0 } ?: ici.olKhz,
        entreeB = if (j.has("entree")) j.optString("entree") == "b" else ici.entreeB,
        lnb = if (j.has("lnb")) j.optString("lnb").takeIf { it in setOf("", "v", "h") } ?: ici.lnb else ici.lnb,
        demiLargeur = if (j.has("demiLargeur")) demiLargeurDe(j.optString("demiLargeur")) else ici.demiLargeur,
        attenteMs = if (j.has("attenteTs")) attenteDe(j.optString("attenteTs")) else ici.attenteMs)

    /** LongMynd's `-S` from the page or a node's request: 0.2 to 3 (× the rate), else 0 (its own). */
    fun demiLargeurDe(t: String?): Double = t?.replace(',', '.')?.trim()?.toDoubleOrNull()?.takeIf { it in 0.2..3.0 }?.let { Math.round(it * 100) / 100.0 } ?: 0.0

    /** LongMynd's `-r` from the page or a node's request: 2 to 60 s (ms), else 0 (the usual). */
    fun attenteDe(t: String?): Int = t?.trim()?.toIntOrNull()?.takeIf { it in 2_000..60_000 } ?: 0

    /** The status as JSON, as a node and SatMe-PC's page give it. */
    fun json(e: EtatDatv, r: ReglageDatv, points: List<Pair<Int, Int>>): JSONObject =
        JSONObject().put("ok", true).put("actif", e.actif).put("etat", e.etat).put("libelle", e.libelle).put("verrouille", e.verrouille)
            .put("ciel", r.cielKhz).put("sr", r.srKs).put("ol", r.olKhz).put("fi", r.fiKhz).put("entree", if (r.entreeB) "b" else "a").put("son", r.son).put("lnb", r.lnb)
            .put("balayage", r.balayage.joinToString(",")).put("demiLargeur", r.demiLargeur).put("attenteTs", r.attenteMs)
            .put("fiTrouvee", e.fiTrouveeKhz).put("cielTrouve", if (e.fiTrouveeKhz > 0) e.fiTrouveeKhz + r.olKhz else 0L).put("srTrouve", e.srTrouveKs)
            .put("mer", e.merDb ?: JSONObject.NULL).put("modcod", e.modcod).put("fournisseur", e.fournisseur).put("service", e.service)
            .put("nuls", e.nulsPct).put("agc1", e.agc1).put("agc2", e.agc2).put("kbps", e.kbps).put("images", e.images)
            .put("ber", e.berPct ?: JSONObject.NULL).put("courtes", e.trames ?: JSONObject.NULL).put("pilotes", e.pilotes ?: JSONObject.NULL)
            .put("flux", JSONArray(e.flux.map { (pid, t) -> JSONObject().put("pid", pid).put("t", t).put("type", typeFlux(t)) }))
            .put("enregistrement", e.enregistrement).put("erreur", e.erreur)
            .put("lna", e.lna ?: JSONObject.NULL).put("puissanceI", e.puissanceI ?: JSONObject.NULL).put("puissanceQ", e.puissanceQ ?: JSONObject.NULL)
            .put("viterbi", e.viterbiPct ?: JSONObject.NULL).put("poinconnage", e.poinconnage ?: JSONObject.NULL)
            .put("ldpc", e.ldpc ?: JSONObject.NULL).put("bch", e.bch ?: JSONObject.NULL).put("bchNonCorrige", e.bchNonCorrige ?: JSONObject.NULL)
            .put("ldpcTotal", e.ldpcTotal).put("bchTotal", e.bchTotal)
            .put("points", JSONArray(points.map { JSONArray(listOf(it.first, it.second)) }))

    /** A node's status (its JSON) as ours; what only this side knows ([ici]) is kept. */
    fun etatDe(j: JSONObject, ici: EtatDatv): EtatDatv = ici.copy(
        etat = j.optInt("etat"), fiTrouveeKhz = j.optLong("fiTrouvee"), srTrouveKs = j.optInt("srTrouve"),
        merDb = if (j.isNull("mer")) null else j.optDouble("mer"), modcod = j.optString("modcod"),
        fournisseur = j.optString("fournisseur"), service = j.optString("service"), nulsPct = j.optInt("nuls"),
        flux = j.optJSONArray("flux")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optInt("pid") to o.optInt("t") } } } ?: emptyList(),
        agc1 = j.optInt("agc1"), agc2 = j.optInt("agc2"), berPct = if (j.isNull("ber")) null else j.optDouble("ber"),
        trames = if (j.isNull("courtes")) null else j.optBoolean("courtes"), pilotes = if (j.isNull("pilotes")) null else j.optBoolean("pilotes"),
        lna = entier(j, "lna"), puissanceI = entier(j, "puissanceI"), puissanceQ = entier(j, "puissanceQ"),
        viterbiPct = if (j.has("viterbi") && !j.isNull("viterbi")) j.optDouble("viterbi") else null, poinconnage = entier(j, "poinconnage"),
        ldpc = entier(j, "ldpc"), bch = entier(j, "bch"), bchNonCorrige = if (j.has("bchNonCorrige") && !j.isNull("bchNonCorrige")) j.optBoolean("bchNonCorrige") else null,
        ldpcTotal = j.optLong("ldpcTotal"), bchTotal = j.optLong("bchTotal"))

    private fun entier(j: JSONObject, k: String): Int? = if (j.has(k) && !j.isNull(k)) j.optInt(k) else null

    /** How far a wrapping counter went from [avant] to [apres] (0 the first time). */
    fun ecartCompteur(avant: Int?, apres: Int, modulo: Int): Int = if (avant == null) 0 else Math.floorMod(apres - avant, modulo)

    /** LongMynd's BCH error counter has 4 bits, its LDPC one 16. */
    const val MODULO_BCH = 16
    const val MODULO_LDPC = 65_536

    /** The constellation points of a status JSON. */
    fun points(j: JSONObject): List<Pair<Int, Int>> =
        j.optJSONArray("points")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { p -> p.optInt(0) to p.optInt(1) } } } ?: emptyList()
}
