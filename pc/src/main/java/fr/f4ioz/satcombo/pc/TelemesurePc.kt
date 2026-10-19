/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.DecodeursMultiples
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Dsp
import fr.f4ioz.satcombo.telemesure.Ao40Fec
import fr.f4ioz.satcombo.telemesure.BlocP3
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure
import fr.f4ioz.satcombo.telemesure.DecodeurTelemesure.Profil
import fr.f4ioz.satcombo.telemesure.SignalEssai
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BPSK telemetry on SatMe-PC (receive only):
 *
 * - **the QO-100 middle beacon**: a « Balise BPSK » channel of the
 *   « Décodeurs multiples » ([CanauxPc]) decodes it; the cockpit's
 *   « Balise » panel shows its frames, quality and errors, and adds that
 *   channel in one click;
 * - **FUNcube-like satellites at 1200 bit/s** on a LEO pass: the main SDR
 *   receiver's audio (USB, Doppler followed by the tracking), off by default;
 * - **a test mode**: a made-up signal (coded per the published format,
 *   with noise and an offset) through the same chain, never kept.
 *
 * Frames received are written locally, one JSON line each, in
 * `telemesure/` (nothing is sent anywhere).
 */
class TelemesurePc(
    private val stockage: Stockage,
    private val dossier: File,
    private val canaux: CanauxPc?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** The tracked satellite's name, for the LEO frames' log. */
    @Volatile var satellite: () -> String = { "" }

    /** The main receiver's 1200 bit/s decoder: off by default. */
    var leoActif: Boolean
        get() = stockage.getBoolean(CLE_LEO, false)
        set(v) { stockage.edit().putBoolean(CLE_LEO, v).apply(); if (!v) leo = null }

    @Volatile private var leo: DecodeurTelemesure? = null
    @Volatile private var essai: DecodeurTelemesure? = null
    @Volatile private var essaiEnCours = false

    init {
        canaux?.auTrame = { t -> garde(t, Profil.QO100, "QO-100") }
    }

    /** The main receiver's audio ([Dsp.AUDIO_RATE]) when it is not on QO-100. */
    fun ecouteSdr(pcm: ShortArray, n: Int) {
        if (!leoActif) return
        val d = leo ?: DecodeurTelemesure(Profil.FUNCUBE, Dsp.AUDIO_RATE, horloge = horloge) { t -> garde(t, Profil.FUNCUBE, satellite()) }
            .also { leo = it }
        d.traite(pcm, n)
    }

    /** A frame written to the day's file (test frames are not). */
    private fun garde(t: DecodeurTelemesure.Trame, p: Profil, sat: String) {
        if (t.essai) return
        runCatching {
            val d = File(dossier, "telemesure").apply { mkdirs() }
            val jour = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(t.t))
            val o = JSONObject().put("t", t.t).put("profil", p.cle).put("satellite", sat).put("format", t.format).put("valide", t.valide)
                .put("corriges", t.octetsCorriges).put("faux", t.symbolesFaux).put("hex", t.hex())
            File(d, "${p.cle}-$jour.jsonl").appendText(o.toString() + "\n")
        }
    }

    /** The test mode: a made-up signal for [p] through a decoder of its own (a few seconds of work). */
    fun lanceEssai(p: Profil): Boolean {
        if (essaiEnCours) return false
        essaiEnCours = true
        val d = DecodeurTelemesure(p, Dsp.AUDIO_RATE, horloge = horloge).also { it.essai = true }
        essai = d
        Thread {
            try {
                val audio = when (p) {
                    Profil.QO100 -> {
                        val fec = SignalEssai.texteBalise("E HI de SatMe : essai du décodeur de balise, signal fabriqué".replace('é', 'e').replace('à', 'a'), Ao40Fec.OCTETS)
                        val p3 = SignalEssai.texteBalise("E HI de SatMe : bloc non codé d'essai".replace('é', 'e'), BlocP3.OCTETS)
                        SignalEssai.audio(Ao40Fec.code(fec) + BlocP3.code(p3) + IntArray(40) { it and 1 }, p.debit, manchester = true,
                            porteuseHz = 1_640.0, deriveHzS = 1.0, ebN0Db = 11.0, graine = horloge())
                    }
                    Profil.FUNCUBE -> {
                        val champs = ESSAI_FUNCUBE
                        val t1 = SignalEssai.trameFuncube(14, champs, "Essai SatMe : trame FUNcube fabriquee".toByteArray())
                        val t2 = SignalEssai.trameFuncube(1, champs)
                        SignalEssai.audio(Ao40Fec.code(t1) + IntArray(240) { (it / 2) and 1 } + Ao40Fec.code(t2), p.debit, manchester = false,
                            porteuseHz = 1_220.0, deriveHzS = 12.0, ebN0Db = 6.0, graine = horloge())
                    }
                }
                var k = 0
                while (k < audio.size) {
                    val n = minOf(4096, audio.size - k)
                    d.traite(audio.copyOfRange(k, k + n), n); k += n
                }
            } finally { essaiEnCours = false }
        }.apply { isDaemon = true; name = "essai-telemesure" }.start()
        return true
    }

    fun action(chemin: String, q: Map<String, String>): JSONObject {
        val ok = JSONObject().put("ok", true)
        when (chemin.removePrefix("/api/telemesure").trim('/')) {
            // The beacon channel in one click (the option « Décodeurs multiples » turned on with it).
            "balise" -> {
                val k = canaux ?: return erreur("absent")
                if (q["on"] == "0") {
                    k.liste.firstOrNull { it.decodeur == DecodeursMultiples.Decodeur.BALISE }?.let { k.action("/api/canaux/retire", mapOf("id" to it.id.toString())) }
                    return ok
                }
                if (k.balise() != null) return ok
                return k.action("/api/canaux/ajoute", mapOf("hz" to DecodeursMultiples.CADRAN_BALISE_HZ.toString(),
                    "cadran" to DecodeursMultiples.CADRAN_BALISE_HZ.toString(), "decodeur" to "balise"))
            }
            "leo" -> leoActif = q["on"] == "1"
            "essai" -> if (!lanceEssai(Profil.de(q["profil"]) ?: Profil.QO100)) return erreur("un essai est déjà en cours")
            "vide" -> { leo?.vide(); canaux?.balise()?.second?.vide(); essai = null }
            else -> return erreur("geste inconnu")
        }
        return ok
    }

    private fun erreur(m: String) = JSONObject().put("ok", false).put("erreur", m)

    fun json(): JSONObject {
        val o = JSONObject()
        val b = canaux?.balise()
        o.put("balise", JSONObject().put("canal", b != null).put("cadran", b?.first?.cadranHz ?: DecodeursMultiples.CADRAN_BALISE_HZ)
            .put("tourne", canaux?.tourne ?: false).also { j -> b?.second?.let { decodeur(j, it) } })
        o.put("leo", JSONObject().put("option", leoActif).put("satellite", satellite()).also { j -> leo?.let { decodeur(j, it) } })
        o.put("essai", JSONObject().put("enCours", essaiEnCours).also { j -> essai?.let { decodeur(j, it) } })
        return o.put("ok", true)
    }

    private fun decodeur(j: JSONObject, d: DecodeurTelemesure) {
        val e = d.etat
        j.put("profil", d.profil.cle).put("nom", d.profil.nom)
            .put("etat", JSONObject().put("bits", e.bits).put("trames", e.trames).put("echecs", e.echecs).put("blocs", e.blocsP3)
                .put("crcFaux", e.crcFaux).put("decalageHz", Math.round(e.decalageHz * 10) / 10.0)
                .put("verrou", Math.round(e.verrouillage * 100) / 100.0).put("manchester", e.manchester)
                .put("presenceDb", Math.round(e.presenceDb * 10) / 10.0).put("derniereMs", e.derniereMs)
                .put("sync", Math.round(e.meilleureSync * 100) / 100.0))
            .put("trames", JSONArray(d.trames.take(12).map { DecodeurTelemesure.json(it, d.profil) }))
    }

    companion object {
        const val CLE_LEO = "pc_tlm_leo"

        /** Plausible real-time values for the test frames (raw counts, in the document's order). */
        val ESSAI_FUNCUBE: List<Pair<Int, Long>> = buildList {
            addAll(listOf(16 to 4810L, 16 to 4790L, 16 to 3020L, 16 to 312L, 16 to 8190L, 16 to 245L, 16 to 12L, 16 to 0L))
            addAll(listOf(8 to 18L, 8 to 19L, 8 to 18L, 8 to 16L, 8 to 0L, 8 to 0L, 8 to 0L, 8 to 1L))
            addAll(listOf(10 to 420L, 10 to 80L, 10 to 610L, 10 to 690L, 10 to 700L, 10 to 705L, 10 to 698L, 10 to 330L, 10 to 120L, 10 to 500L))
            addAll(listOf(8 to 163L, 8 to 182L, 8 to 200L, 8 to 30L, 8 to 60L, 8 to 70L))
            addAll(listOf(8 to 20L, 8 to 140L, 8 to 180L, 8 to 160L))
            addAll(listOf(8 to 120L, 8 to 121L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L))
            addAll(listOf(24 to 31_415L, 6 to 0L, 5 to 0L, 1 to 0L))
            addAll(listOf(1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 1L, 1 to 0L, 1 to 0L, 1 to 0L, 1 to 0L, 1 to 0L))
        }
    }
}
