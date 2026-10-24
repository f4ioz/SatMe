/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * « Re-décoder chaque image à la fin »: each picture received live, once
 * saved with its own sound, is decoded again from that sound, the whole of it
 * at once ([RedecodageSstv]), and corrected ([CorrectionSstv]) — in the
 * background, one at a time, on a low-priority thread: the live decoder and
 * the next picture never wait for it. The corrected version is kept in
 * `corrigees/` ([CorrectionsGalerie.corrigeSiMieux]) only when it marks
 * better; the gallery, the sheets' « Auto » and « Meilleures versions
 * traitées », the viewer's Brute | Corrigée then take it. The picture as
 * received stays as it is.
 *
 * On the owner's 42 ISS pictures of 23–24/10/2026 the best version kept goes
 * from a mean mark of 84.4 (the live decoder alone) to 90.2.
 *
 * [actif]: the option on (and, on the phone, the battery saver off), asked
 * when a picture is handed over and again when its turn comes. [lance] runs a
 * job (by default [filBas], one low-priority thread); [fini] hears each one's
 * outcome.
 */
class RedecodageAuto(
    private val corrections: CorrectionsGalerie,
    private val actif: () -> Boolean,
    private val lance: (Runnable) -> Unit = filBas(),
    private val fini: (png: String, issue: CorrectionsGalerie.Issue) -> Unit = { _, _ -> },
) {
    private val attente = AtomicInteger(0)

    /** Pictures handed over and not done yet. */
    val enAttente: Int get() = attente.get()

    /**
     * Picture [s] of gallery [galerie] just saved: queued to be decoded again
     * (true), or not (the option off, no sound kept, the queue full). Returns
     * at once.
     */
    fun demande(galerie: File, s: SstvMeta.SstvShot): Boolean {
        if (!runCatching(actif).getOrDefault(false)) return false
        if (!corrections.aUnSon(galerie, s)) return false
        if (attente.get() >= MAX_ATTENTE) return false
        attente.incrementAndGet()
        try {
            lance(Runnable {
                try {
                    val i = if (!runCatching(actif).getOrDefault(false)) CorrectionsGalerie.Issue(null, COUPE)
                            else runCatching { corrections.corrigeSiMieux(galerie, s) }.getOrElse { CorrectionsGalerie.Issue(null, it.javaClass.simpleName) }
                    runCatching { fini(s.fileName, i) }
                } finally { attente.decrementAndGet() }
            })
        } catch (e: Exception) { attente.decrementAndGet(); return false }
        return true
    }

    companion object {
        /** The option was switched off (or the battery saver on) before the picture's turn. */
        const val COUPE = "coupe"
        /** Pictures waiting at most (a pass gives one every two minutes; a backlog is not worth the battery). */
        const val MAX_ATTENTE = 8

        /**
         * One daemon thread at the lowest priority, the jobs one after the
         * other; [preparation] runs on it first (the phone lowers it further,
         * to the background class).
         */
        fun filBas(preparation: () -> Unit = {}): (Runnable) -> Unit {
            val ex = Executors.newSingleThreadExecutor { r ->
                Thread({ runCatching(preparation); r.run() }, "sstv-redecodage-auto").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
            return { ex.execute(it) }
        }
    }
}
