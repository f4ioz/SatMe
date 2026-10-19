/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import fr.f4ioz.satcombo.meteor.Convolutif
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * BPSK telemetry from a receiver's audio, through to frames: the shared
 * core of the QO-100 beacon and the FUNcube-like 1200 bit/s satellites.
 * One [DemodBpsk]; behind it, on both its soft streams (differential and
 * coherent), a correlator for the coded frame's distributed sync; and for
 * the beacon, the uncoded Phase 3 blocks it sends between coded frames.
 *
 * Feed it audio ([traite]); read [etat] and [trames] from any thread.
 * Receive only.
 */
class DecodeurTelemesure(
    val profil: Profil,
    fs: Int,
    centreHz: Double = 1_500.0,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val auDecode: (Trame) -> Unit = {},
) {
    enum class Profil(val cle: String, val nom: String, val debit: Double, val rechercheHz: Double, val blocsP3: Boolean) {
        /** The QO-100 middle beacon, 10 489,750 MHz: 400 bit/s, Manchester; coded frames and Phase 3 blocks. */
        QO100("qo100", "Balise QO-100 (400 bit/s)", 400.0, 600.0, true),
        /** FUNcube-1 (AO-73) and its kind: 1200 bit/s, coded frames. */
        FUNCUBE("funcube", "FUNcube (1200 bit/s)", 1200.0, 1_200.0, false);

        companion object { fun de(c: String?) = entries.firstOrNull { it.cle == c } }
    }

    /** A frame received. */
    data class Trame(
        val t: Long,
        /** « fec » (coded, 256 bytes) or « p3 » (uncoded block, 512 bytes). */
        val format: String,
        val donnees: ByteArray,
        /** For a Phase 3 block: its CRC was right. Coded frames are only kept when corrected. */
        val valide: Boolean,
        val octetsCorriges: Int = 0,
        val symbolesFaux: Int = 0,
        val snrDb: Double = Double.NaN,
        /** The sync correlation, 0..1. */
        val sync: Double = 1.0,
        val essai: Boolean = false,
    ) {
        fun hex(): String = donnees.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    data class Etat(
        val bits: Long = 0,
        val trames: Int = 0,
        val echecs: Int = 0,
        val blocsP3: Int = 0,
        val crcFaux: Int = 0,
        val decalageHz: Double = 0.0,
        val verrouillage: Double = 0.0,
        val manchester: Boolean = false,
        val presenceDb: Double = 0.0,
        val derniereMs: Long = 0,
        val meilleureSync: Double = 0.0,
    )

    private val demod = DemodBpsk(fs, profil.debit, centreHz, profil.rechercheHz) { d, c -> bit(d, c) }
    private val viterbi = Convolutif.Decodeur()
    private val chercheurP3 = BlocP3.Chercheur { b, ok -> blocP3(b, ok) }

    // The last 5200 soft symbols of each stream (twice over: a frame is always contiguous).
    private val nS = Ao40Fec.SYMBOLES
    private val ringD = FloatArray(2 * nS); private val ringC = FloatArray(2 * nS)
    private var pos = 0L
    private var muetJusqua = 0L
    private val tampon = FloatArray(nS)

    private val liste = ArrayDeque<Trame>()
    @Volatile var trames: List<Trame> = emptyList(); private set
    @Volatile var etat = Etat(); private set
    private var nTrames = 0; private var nEchecs = 0; private var nP3 = 0; private var nCrc = 0
    private var derniere = 0L; private var meilleure = 0.0
    private var debutSilence = 0L
    var essai = false

    fun traite(pcm: ShortArray, n: Int) {
        demod.traite(pcm, n)
        // No frame for long while the loop does not hold: look for the carrier again.
        val bitsSans = demod.bits - debutSilence
        if (!demod.enAcquisition && bitsSans > 3 * nS && demod.verrouillage < 0.3) {
            demod.reacquiert(); debutSilence = demod.bits
        }
        publie()
    }

    fun reinitialise() {
        demod.reinitialise(); chercheurP3.reinitialise()
        ringD.fill(0f); ringC.fill(0f); pos = 0; muetJusqua = 0; debutSilence = 0
    }

    private fun bit(d: Float, c: Float) {
        val i = (pos % nS).toInt()
        ringD[i] = d; ringD[i + nS] = d
        ringC[i] = c; ringC[i + nS] = c
        pos++
        if (profil.blocsP3) chercheurP3.pousse(if (d > 0) 1 else 0)
        if (pos < nS || pos < muetJusqua || chercheurP3.enCours) return
        val debut = ((pos - nS) % nS).toInt()
        for (flux in 0..1) {
            val r = if (flux == 0) ringD else ringC
            var s = 0.0; var a = 0.0
            for (k in 0 until Ao40Fec.COLONNES) {
                val v = r[debut + Ao40Fec.positionSync(k)]
                s += v * Ao40Fec.SYNC_SIGNE[k]; a += abs(v)
            }
            val q = if (a > 0) s / a else 0.0
            if (abs(q) > meilleure) meilleure = abs(q)
            if (abs(q) < SEUIL) continue
            val signe = if (q < 0) -1f else 1f
            for (k in 0 until nS) tampon[k] = r[debut + k] * signe
            val res = Ao40Fec.decode(tampon, 0, viterbi)
            if (res == null) { nEchecs++; continue }
            nTrames++
            derniere = horloge(); debutSilence = demod.bits
            ajoute(Trame(derniere, "fec", res.donnees, true, res.octetsCorriges, res.symbolesFaux, res.snrDb, abs(q), essai))
            muetJusqua = pos + nS - 200
            break
        }
    }

    private fun blocP3(b: ByteArray, ok: Boolean) {
        if (ok) { nP3++; derniere = horloge(); debutSilence = demod.bits } else nCrc++
        // A wrong CRC is kept too when the text is mostly readable (a few wrong bits); garbage is only counted.
        if (ok || lisible(b)) ajoute(Trame(horloge(), "p3", b, ok, essai = essai))
    }

    private fun lisible(b: ByteArray): Boolean = b.count { (it.toInt() and 0x7F) in 0x20..0x7E } >= b.size * 0.8

    private fun ajoute(t: Trame) {
        liste.addFirst(t)
        while (liste.size > GARDEES) liste.removeLast()
        trames = liste.toList()
        auDecode(t)
    }

    private var publieBits = 0L
    private fun publie() {
        if (demod.bits - publieBits < 50 && etat.trames == nTrames) return
        publieBits = demod.bits
        etat = Etat(demod.bits, nTrames, nEchecs, nP3, nCrc, demod.decalageHz, demod.verrouillage, demod.manchester,
            demod.presenceDb, derniere, meilleure)
        meilleure *= 0.5
    }

    fun vide() { liste.clear(); trames = emptyList(); nTrames = 0; nEchecs = 0; nP3 = 0; nCrc = 0 }

    companion object {
        /** Sync correlation from which a frame is tried (noise alone: σ ≈ 0,12). */
        const val SEUIL = 0.45
        const val GARDEES = 40

        /** A frame for the page: its text (beacon) or its fields (FUNcube), and the raw bytes. */
        fun json(t: Trame, profil: Profil): JSONObject {
            val o = JSONObject().put("t", t.t).put("format", t.format).put("valide", t.valide).put("hex", t.hex())
                .put("corriges", t.octetsCorriges).put("faux", t.symbolesFaux).put("sync", Math.round(t.sync * 100) / 100.0)
                .put("essai", t.essai)
            if (!t.snrDb.isNaN()) o.put("snr", Math.round(t.snrDb * 10) / 10.0)
            if (profil == Profil.FUNCUBE && t.format == "fec") {
                val f = Funcube.lis(t.donnees)
                o.put("sat", f.satellite).put("genre", f.genre).put("type", f.type)
                if (f.message != null) o.put("message", f.message)
                o.put("mesures", JSONArray(f.mesures.map { JSONObject().put("g", it.groupe).put("n", it.nom).put("v", it.valeur) }))
            } else {
                o.put("lignes", JSONArray(BlocP3.texte(t.donnees)))
                o.put("bloc", (t.donnees[0].toInt() and 0x7F).toChar().toString())
            }
            return o
        }
    }
}
