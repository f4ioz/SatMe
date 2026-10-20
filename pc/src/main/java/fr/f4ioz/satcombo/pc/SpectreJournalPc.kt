/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Png
import fr.f4ioz.satcombo.domain.SpectrePassage
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.Dsp
import org.json.JSONObject
import java.io.File

/**
 * « Journal détaillé » (off unless chosen, Réglages › Journal Doppler / CAT):
 * while a followed satellite is up and the SDR receives it, the spectrum
 * around the listened frequency noted — one averaged line every 2 s
 * ([SpectrePassage]) — and what the CW and FT8 decoders write, with its
 * time; kept per pass in `journal/spectres/` (the lines, a PNG strip, the
 * decodes) for the pass report. Reads what the receiver already computes:
 * nothing asked of the hardware.
 */
class SpectreJournalPc(
    private val stockage: Stockage,
    val dossier: File,
    /** The satellite followed and up now (number, name), null otherwise. */
    private val passage: () -> Pair<Int, String>?,
    /** The receiver's last spectrum (dB, low first), its frame count, and the listened frequency's offset in it (Hz); null when it does not receive. */
    private val spectre: () -> Triple<FloatArray, Long, Double>?,
    /** What the CW decoder has written (its rolling text). */
    private val cw: () -> String = { "" },
    /** The FT8/FT4 decodes heard lately: (slot start, text, details). */
    private val ft8: () -> List<Triple<Long, String, String>> = { emptyList() },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val periodeMs: Long = 2_000L,
) {
    var actif: Boolean
        get() = stockage.getBoolean(CLE, false)
        set(v) { stockage.edit().putBoolean(CLE, v).apply(); if (!v) ferme() }

    private class Session(val catnum: Int, val sat: String, val debutMs: Long, val enr: SpectrePassage.Enregistreur) {
        var vuMs = debutMs
        var ecritMs = debutMs
        val decodes = ArrayList<JSONObject>()
        val ft8Vus = HashSet<String>()
    }

    @Volatile private var session: Session? = null
    private var trameVue = -1L
    private var cwAvant: String? = null

    /** Every quarter second or so. */
    @Synchronized fun tic() {
        if (!actif) { ferme(); return }
        val t = horloge()
        val p = passage()
        val s0 = session
        // Another satellite, or this one gone a minute: its pass is over.
        if (s0 != null && (p == null && t - s0.vuMs > 60_000L || p != null && p.first != s0.catnum)) ferme()
        if (p == null) return
        val s = session ?: Session(p.first, p.second, t, SpectrePassage.Enregistreur(BINS, periodeMs)).also { session = it; cwAvant = cw() }
        s.vuMs = t
        spectre()?.let { (sp, trames, hz) ->
            if (trames != trameVue) {
                trameVue = trames
                val parBin = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1 / sp.size
                s.enr.ajoute(t, sp, hz / parBin + sp.size / 2.0, LARGEUR_HZ / 2 / parBin)
            }
        }
        // What the decoders wrote since.
        val c = cw()
        nouveauTexte(cwAvant, c).trim().takeIf { it.isNotEmpty() }?.let { s.decodes += JSONObject().put("t", t).put("g", "CW").put("x", it) }
        cwAvant = c
        for ((tr, texte, det) in ft8()) if (tr >= s.debutMs - 15_000L && s.ft8Vus.add("$tr $texte"))
            s.decodes += JSONObject().put("t", tr).put("g", "FT8").put("x", texte).put("d", det)
        if (t - s.ecritMs >= 30_000L) { ecrit(s, false); s.ecritMs = t }
    }

    /** The pass under way written and closed. */
    @Synchronized fun ferme() {
        val s = session ?: return
        session = null
        s.enr.termine()
        ecrit(s, true)
    }

    private fun ecrit(s: Session, png: Boolean) = runCatching {
        dossier.mkdirs()
        val l = s.enr.lignes()
        if (l.isEmpty() && s.decodes.isEmpty()) return@runCatching
        val base = SpectrePassage.nom(s.catnum, s.debutMs).removeSuffix(SpectrePassage.EXT)
        if (l.isNotEmpty()) {
            val b = SpectrePassage.Bande(s.catnum, s.sat, LARGEUR_HZ, l)
            val tmp = File(dossier, "$base.tmp")
            tmp.outputStream().buffered().use { SpectrePassage.ecrit(b, it) }
            tmp.renameTo(File(dossier, base + SpectrePassage.EXT))
            if (png && l.size >= 2) File(dossier, "$base.png").writeBytes(Png.encode(SpectrePassage.image(b)))
        }
        if (s.decodes.isNotEmpty()) File(dossier, "$base.decodes.jsonl").writeText(s.decodes.joinToString("") { it.toString() + "\n" })
    }

    companion object {
        const val CLE = "pc_journal_detaille"
        const val BINS = 128
        const val LARGEUR_HZ = 25_000.0

        /** What [apres] adds to [avant], the decoder's rolling text (its start cut when it grows too long). */
        fun nouveauTexte(avant: String?, apres: String): String {
            if (avant == null || avant == apres) return ""
            if (apres.startsWith(avant)) return apres.substring(avant.length)
            // Cut at the start: the end of what was there found again.
            val queue = avant.takeLast(minOf(40, avant.length))
            val i = if (queue.isEmpty()) -1 else apres.lastIndexOf(queue)
            return if (i >= 0) apres.substring(i + queue.length) else if (apres.length < avant.length) apres else ""
        }

        /** The decodes kept with a pass's spectra: (time, kind, text, details). */
        fun decodes(f: File): List<fr.f4ioz.satcombo.domain.RapportPassage.Evenement> = runCatching {
            f.readLines().mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull()?.let {
                fr.f4ioz.satcombo.domain.RapportPassage.Evenement(it.getLong("t"), it.optString("g"), it.optString("x"), it.optString("d")) } }
        }.getOrDefault(emptyList())
    }
}
