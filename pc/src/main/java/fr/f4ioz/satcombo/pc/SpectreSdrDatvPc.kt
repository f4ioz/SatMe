/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.BandeLarge
import fr.f4ioz.satcombo.datv.Datv
import fr.f4ioz.satcombo.datv.GenerateurBandeLarge
import fr.f4ioz.satcombo.datv.NomsSignaux
import fr.f4ioz.satcombo.datv.SignalLarge
import fr.f4ioz.satcombo.datv.SpectreLocal
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.RtlTcp
import fr.f4ioz.satcombo.sdr.SourceIq
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * QO-100's wide band from our own SDR, in the DATV tab ([SpectreLocal]):
 * the SDR tab's receiver (an RTL-SDR on rtl_tcp, a Pluto, a bladeRF, a
 * SatMe-Node's) on a splitter after the same LNB as the MiniTiouner, swept
 * across the 9 MHz about once a second; or a simulated band, to try it.
 *
 * Off unless chosen; the receiver opened only while the option is on and
 * the DATV tab asked within the last [VU_MS], given back otherwise. Receive
 * only.
 */
class SpectreSdrDatvPc(
    private val stockage: Stockage,
    private val datv: DatvPc,
    /** The SDR tab's receiver, null where there is none. */
    private val sdr: SdrPc?,
    /** The names the ground station's strip learnt (frequency + rate → name), or null. */
    private val nom: (Long, Int) -> String? = { _, _ -> null },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    var on: Boolean
        get() = stockage.getBoolean("datv_sdr_spectre", false)
        set(v) { stockage.edit().putBoolean("datv_sdr_spectre", v).apply() }

    /** [SDR] the SDR tab's receiver, [ESSAI] a simulated band. */
    var source: String
        get() = stockage.getString("datv_sdr_source", SDR).takeIf { it == SDR || it == ESSAI } ?: SDR
        set(v) { stockage.edit().putString("datv_sdr_source", if (v == ESSAI) ESSAI else SDR).apply() }

    @Volatile private var vuMs = 0L
    @Volatile var etat = ""; private set
    @Volatile var numero = 0L; private set
    @Volatile private var trameMs = 0L
    @Volatile private var derniere = IntArray(0)
    private val recentes = ArrayDeque<IntArray>()
    @Volatile var signaux: List<SignalLarge> = emptyList(); private set
    @Volatile private var tient = ""
    @Volatile private var generation = 0
    @Volatile private var descriptionSource = ""
    @Volatile private var pas = 0

    fun voulu(): Boolean = on && horloge() - vuMs < VU_MS

    fun demarre() {
        thread(isDaemon = true, name = "satme-datv-sdr-spectre") {
            while (true) {
                runCatching { boucle() }.onFailure { etat = "erreur : " + (it.message ?: it.javaClass.simpleName) }
                Thread.sleep(500)
            }
        }
    }

    /** One opening: swept while wanted, then given back. */
    fun boucle() {
        if (!voulu()) { if (on) etat = "en attente de l’onglet DATV"; return }
        val gen = generation
        val essai = source == ESSAI
        val src: SourceIq = if (essai) GenerateurBandeLarge(datv.reglage.olKhz).also { descriptionSource = "bande simulée (essai)" } else {
            ModeDemo.refus("le SDR")?.let { etat = "$it : choisir « bande simulée »"; Thread.sleep(2000); return }
            val s = sdr ?: run { etat = "pas de récepteur SDR ici"; Thread.sleep(2000); return }
            etat = "ouverture du récepteur…"
            runCatching { s.ouvrePourSpectreDatv() }.getOrElse { etat = it.message ?: "récepteur injoignable"; Thread.sleep(3000); return }
                .also { descriptionSource = s.etat.value.tuner.ifBlank { libelle(it) } }
        }
        tient = source
        try {
            val fs = src.setSampleRate(DEBIT)
            if (src is RtlTcp) { src.setAgc(false); src.setGain(sdr?.station?.settings?.sdrGainTenthDb?.takeIf { it >= 0 } ?: GAIN_RTL) }
            sdr?.station?.settings?.sdrPpm?.takeIf { it != 0 && !essai }?.let { src.setFreqCorrection(it) }
            pas = SpectreLocal.pas(datv.reglage.olKhz, fs).size
            etat = "balayage…"
            while (voulu() && generation == gen && source == (if (essai) ESSAI else SDR)) {
                val a = SpectreLocal.balaie(src, fs, datv.reglage.olKhz, { voulu() && generation == gen }) ?: break
                trame(a.valeurs())
                etat = "en marche"
                Thread.sleep(if (essai) 700 else 200)
            }
        } finally {
            runCatching { src.close() }
            if (!essai) runCatching { sdr?.rendSpectreDatv() }
            tient = ""
        }
        if (!voulu()) etat = if (on) "en attente de l’onglet DATV" else ""
    }

    private fun libelle(s: SourceIq): String = when (s) { is RtlTcp -> RtlTcp.nomTuner(s.tuner); else -> s.javaClass.simpleName }

    /** A sweep: kept, its signals found on the last three's average. */
    fun trame(v: IntArray) {
        synchronized(recentes) {
            recentes.addLast(v); while (recentes.size > 3) recentes.removeFirst()
            signaux = BandeLarge.detecte(BandeLarge.moyenne(recentes.toList()))
        }
        derniere = v; trameMs = horloge(); numero++
    }

    fun frais(): Boolean = numero > 0 && horloge() - trameMs < 5_000L

    /** The signal the DATV receiver is on, when it receives. */
    private fun tenu(sg: SignalLarge): Boolean = datv.etat.actif &&
        Math.abs(sg.centreKhz - datv.reglage.cielKhz) <= maxOf(NomsSignaux.tolerance(sg.srKs), sg.largeurKhz / 2)

    /** For the page; [vu]: the DATV tab shows it (which keeps the receiver). */
    fun json(depuis: Long, vu: Boolean = true): JSONObject {
        if (vu) vuMs = horloge()
        val o = JSONObject().put("ok", true).put("on", on).put("source", source).put("etat", etat).put("n", numero).put("frais", frais())
            .put("debut", BandeLarge.DEBUT_KHZ).put("largeur", BandeLarge.LARGEUR_KHZ).put("recepteur", descriptionSource).put("pas", pas)
            .put("sdrTab", sdr?.recepteur ?: "")
            // The ground station's strip's fields: the page draws both with the same code.
            .put("balise", Datv.BALISE_KHZ).put("canaux", JSONArray(Datv.CANAUX_KHZ)).put("canauxLarges", JSONArray(BandeLarge.CANAUX_LARGES_KHZ))
            .put("bruit", BandeLarge.BRUIT).put("seuil", BandeLarge.SEUIL).put("unitesDb", BandeLarge.UNITES_PAR_DB)
        if (on && frais() && depuis != numero) o.put("valeurs", JSONArray(derniere))
        if (on && frais()) {
            val l = signaux
            o.put("signaux", JSONArray(l.map { sg -> BandeLarge.json(sg).put("nom", nom(sg.centreKhz, sg.srKs) ?: "").put("tenu", tenu(sg)) }))
            val e = datv.etat
            val surBalise = e.actif && e.verrouille && e.fiTrouveeKhz > 0 && Math.abs(datv.reglage.cielKhz - Datv.BALISE_KHZ) <= 100
            o.put("avertissement", SpectreLocal.avertissement(l, if (surBalise) e.fiTrouveeKhz + datv.reglage.olKhz else 0L) ?: "")
        } else o.put("signaux", JSONArray()).put("avertissement", "")
        return o
    }

    fun action(q: Map<String, String>): JSONObject {
        q["source"]?.let { if (it != source) { source = it; generation++ } }
        q["on"]?.let { on = it == "1"; if (on) vuMs = horloge() else { generation++; etat = "" } }
        return JSONObject().put("ok", true)
    }

    companion object {
        const val SDR = "sdr"
        const val ESSAI = "essai"
        const val VU_MS = 10_000L
        /** The rate asked (an RTL-SDR's steady maximum); a receiver that gives less takes more steps. */
        const val DEBIT = 2_400_000
        /** An RTL-SDR's gain when its tab leaves it automatic: fixed, so that the steps match (tenths of dB). */
        const val GAIN_RTL = 402
    }
}
