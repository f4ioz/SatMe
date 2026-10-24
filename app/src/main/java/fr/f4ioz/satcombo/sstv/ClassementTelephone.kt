/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.content.Context
import android.graphics.BitmapFactory
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.ActivitesTelephone
import fr.f4ioz.satcombo.domain.PassPredictor
import java.io.File

/**
 * The phone's gallery sorted by slide ([ClassementGalerie]): the screen,
 * the page and the sheet share it. The series under way from « Activités à
 * venir », its passes worked out here (kept 10 minutes).
 */
object ClassementTelephone {

    val galerie = ClassementGalerie { f ->
        runCatching {
            val b = BitmapFactory.decodeFile(f.absolutePath) ?: return@runCatching null
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val p = ClassementGalerie.Pixels(px, b.width, b.height)
            b.recycle()
            p
        }.getOrNull()
    }

    fun shots(ctx: Context): List<SstvMeta.SstvShot> = SstvHub.shots(ctx).map { it.second }

    fun etat(ctx: Context, attendre: Boolean = false): ClassementGalerie.Etat = galerie.etat(SstvHub.dir(ctx), shots(ctx), attendre)

    private var passes: Triple<String, Long, Pair<List<SatPass>, List<SatPass>>>? = null

    /** The series to show now, its passes (to come, done), from the activities and elements on the phone. */
    suspend fun prevision(ctx: Context, e: ClassementGalerie.Etat, t: Long = System.currentTimeMillis()): SerieSstv.Prevision? {
        val reglages = SettingsStore(ctx)
        val acts = runCatching { ActivitesTelephone.enLigne(ctx).liste(reglages.reglagesActivites) }.getOrDefault(emptyList())
        val s = SerieSstv.choisit(acts, emptyList(), t) ?: return null
        val cle = s.catnum.toString() + ":" + s.debutMs + ":" + s.finMs
        val l = synchronized(this) { passes?.takeIf { it.first == cle && t - it.second < 10 * 60_000L }?.third } ?: run {
            val sat = ActivitesTelephone.satellites(ctx).firstOrNull { it.catalogNumber == s.catnum }
            val obs = ActivitesTelephone.observateur(ctx)
            val r = if (sat == null || !PassPredictor.elementsUtilisables(sat)) emptyList<SatPass>() to emptyList() else {
                val p = PassPredictor()
                val avenir = if (s.finMs > t) runCatching {
                    p.upcomingPasses(sat, obs, t - 15 * 60_000L, ((s.finMs - t) / 3_600_000L + 1).toInt().coerceIn(1, 24 * 15), 0.0)
                        .filter { it.aosEpochMs < s.finMs }
                }.getOrDefault(emptyList()) else emptyList()
                val de = s.debutMs - 3_600_000L
                val faits = runCatching {
                    p.upcomingPasses(sat, obs, de, ((minOf(t, s.finMs) - de) / 3_600_000L + 2).toInt().coerceIn(1, 24 * 15), 0.0)
                }.getOrDefault(emptyList())
                avenir to faits
            }
            synchronized(this) { passes = Triple(cle, t, r) }
            r
        }
        return SerieSstv.prevision(s, e, l.first, l.second, SerieSstv.elMin(reglages.minElevDeg.toDouble()), t)
    }

    /** The sheet's signatures with the groups and marks. */
    fun signatures(ctx: Context, base: Map<String, Planche.Signature>): Map<String, Planche.Signature> =
        runCatching { galerie.signatures(base, etat(ctx, attendre = true)) }.getOrDefault(base)
}
