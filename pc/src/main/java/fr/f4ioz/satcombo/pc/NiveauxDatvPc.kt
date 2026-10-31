/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

/**
 * The DATV picture's « Qualité » levels, chosen per browser in the DATV tab (a tablet on 480p, this PC on Source).
 *
 * Source is the picture as received (the JPEG stream as decoded, the H.264 copied); the others are re-encoded by
 * ffmpeg: [lignes] lines at most, [ips] pictures a second at most, H.264 at [kbps] at most in [profil] / [preset]
 * with a key frame each second (« Lecture fluide »), JPEG at quality [qJpeg] and [ipsJpeg] pictures a second
 * (the JPEG stream).
 */
enum class NiveauDatvPc(val code: String, val lignes: Int, val kbps: Int, val profil: String, val preset: String, val ips: Int,
                        val qJpeg: Int, val ipsJpeg: Int) {
    SOURCE("source", 0, 0, "", "", 0, 0, 0),
    P720("720", 720, 2500, "main", "veryfast", 25, 8, 25),
    P480("480", 480, 1200, "main", "veryfast", 25, 8, 25),
    P360("360", 360, 600, "baseline", "veryfast", 25, 10, 15);

    companion object {
        /** From the page's `q` (anything else: Source). */
        fun de(code: String?): NiveauDatvPc = entries.firstOrNull { it.code == code?.removeSuffix("p") } ?: SOURCE
    }
}

/**
 * Who uses which re-encoded level, shared by the « Lecture fluide » ([LecteurFluideDatvPc], sort `mp4`) and the JPEG
 * stream ([ImagesDatvPc], sort `jpeg`): one encoder per (sort, level) in use, whatever the number of browsers
 * watching it; [MAX] of them at most, for the station PC's processor — a browser asking a third gets the nearest
 * level already running for its sort (Source when none), and is told why. Source is never counted (nothing
 * re-encoded for it). A level nobody uses any more is let go after [gardeMs] (a page reloading finds it running):
 * its owner stops the encoder when [aArreter] names it.
 */
class PartageNiveauxDatvPc(private val max: Int = MAX, private val gardeMs: Long = LecteurFluideDatvPc.GARDE_MS,
                           private val horloge: () -> Long = System::currentTimeMillis) {

    private class Usage(var pages: Int, var libreDepuisMs: Long)
    private val usages = LinkedHashMap<Pair<String, NiveauDatvPc>, Usage>()

    /** A page of sort [sorte] wanting [voulu]: the level it gets (taken until [rend]). */
    @Synchronized
    fun prend(sorte: String, voulu: NiveauDatvPc): NiveauDatvPc {
        if (voulu == NiveauDatvPc.SOURCE) return voulu
        val n = usages[sorte to voulu]?.let { voulu } ?: if (usages.size < max) voulu.also { usages[sorte to it] = Usage(0, horloge()) }
            else repli(sorte, voulu)
        usages[sorte to n]?.let { it.pages++ }
        return n
    }

    /** The nearest level running for [sorte] (the smaller of two as near), else Source. */
    private fun repli(sorte: String, voulu: NiveauDatvPc): NiveauDatvPc =
        usages.keys.filter { it.first == sorte }.map { it.second }
            .minWithOrNull(compareBy<NiveauDatvPc> { Math.abs(it.lignes - voulu.lignes) }.thenBy { it.lignes }) ?: NiveauDatvPc.SOURCE

    /** The page let [n] go. */
    @Synchronized
    fun rend(sorte: String, n: NiveauDatvPc) {
        val u = usages[sorte to n] ?: return
        u.pages = maxOf(0, u.pages - 1)
        if (u.pages == 0) u.libreDepuisMs = horloge()
    }

    /** The levels of [sorte] kept (in use, or let go less than [gardeMs] ago). */
    @Synchronized
    fun actifs(sorte: String): Set<NiveauDatvPc> = usages.keys.filter { it.first == sorte }.map { it.second }.toSet()

    @Synchronized
    fun pages(sorte: String, n: NiveauDatvPc): Int = usages[sorte to n]?.pages ?: 0

    /** The levels of [sorte] nobody used for [gardeMs]: forgotten here, their encoders to stop. */
    @Synchronized
    fun aArreter(sorte: String): List<NiveauDatvPc> {
        val t = horloge()
        val l = usages.filter { (k, u) -> k.first == sorte && u.pages == 0 && t - u.libreDepuisMs >= gardeMs }.keys.toList()
        l.forEach { usages.remove(it) }
        return l.map { it.second }
    }

    /** Why a page got another level than it asked. */
    fun raison(voulu: NiveauDatvPc, servi: NiveauDatvPc): String =
        if (voulu == servi) "" else "déjà $max encodages en cours sur ce PC : " + (if (servi == NiveauDatvPc.SOURCE) "la source" else servi.code + "p") + " à la place"

    companion object {
        /** Re-encoded levels at once, all sorts together. */
        const val MAX = 2
    }
}
