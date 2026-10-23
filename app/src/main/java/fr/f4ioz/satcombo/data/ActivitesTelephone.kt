/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.ActivitesAVenir.Activite
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.location.LocationProvider
import fr.f4ioz.satcombo.location.Maidenhead
import java.io.File

/**
 * « Activités à venir » on the phone: the sources' cache in the app's files
 * (one instance for the screen and the background worker), the satellites
 * known (the TLE cache), where the station is, and the passes during an
 * activity — the same answers in the background as on screen.
 */
object ActivitesTelephone {

    @Volatile private var instance: ActivitesEnLigne? = null

    fun enLigne(ctx: Context): ActivitesEnLigne = instance ?: synchronized(this) {
        instance ?: ActivitesEnLigne(File(ctx.applicationContext.filesDir, "activites")).also { instance = it }
    }

    private fun fichierMemoire(ctx: Context) = File(File(ctx.applicationContext.filesDir, "activites"), "activites-alertes.json")

    fun memoire(ctx: Context): ActivitesAVenir.Memoire =
        runCatching { ActivitesAVenir.litMemoire(fichierMemoire(ctx).readText()) }.getOrDefault(ActivitesAVenir.Memoire())

    @Synchronized fun garde(ctx: Context, m: ActivitesAVenir.Memoire) {
        runCatching { fichierMemoire(ctx).apply { parentFile?.mkdirs() }.writeText(ActivitesAVenir.ecrit(m).toString()) }
    }

    /** The satellites whose elements are on disk (the last download). */
    fun satellites(ctx: Context): List<TleEntry> =
        runCatching { TleCache(ctx).loadRaw()?.let { TleRepository().parse(it) } }.getOrNull().orEmpty()

    /**
     * Where the station is, without waiting: the locator (or the point) typed,
     * else the last fix the phone holds, else the locator.
     */
    suspend fun observateur(ctx: Context): Observer {
        val s = SettingsStore(ctx)
        val loc = s.manualLocator.trim().uppercase()
        val manuel = s.manualLat.takeIf { !it.isNaN() }?.let { la -> s.manualLon.takeIf { !it.isNaN() }?.let { lo -> Observer(la, lo, 50.0, loc) } }
            ?: Maidenhead.toLatLon(loc)?.let { Observer(it.first, it.second, 50.0, loc) }
        if (s.locationMode == LocationMode.MANUAL && manuel != null) return manuel
        val permis = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val lp = LocationProvider(ctx)
        val fix = if (permis) runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000L) { lp.lastKnown() } }.getOrNull() else null
        return fix ?: manuel ?: lp.defaultObserver
    }

    /** Each activity's passes worth listening to (by the owner's minimum elevation), worked out once per call. */
    fun passages(l: List<Activite>, sats: List<TleEntry>, obs: Observer, elMin: Double, maintenant: Long = System.currentTimeMillis()): Map<String, List<SatPass>> {
        val p = PassPredictor()
        val parSat = HashMap<String, List<SatPass>>()
        return l.associate { a ->
            val f = ActivitesAVenir.fenetrePassages(a, maintenant)
            val e = sats.firstOrNull { it.catalogNumber == a.catnum }
            val brut = if (f == null || e == null || !PassPredictor.elementsUtilisables(e)) emptyList() else {
                val cle = a.catnum.toString() + ":" + f.first / 60_000L + ":" + f.second / 60_000L
                parSat.getOrPut(cle) {
                    val heures = ((f.second - f.first + 3_599_999L) / 3_600_000L).toInt().coerceIn(1, 24 * 15)
                    runCatching { p.upcomingPasses(e, obs, f.first - 30 * 60_000L, heures, 0.0) }.getOrDefault(emptyList())
                }
            }
            a.id to ActivitesAVenir.passages(a, brut, elMin, maintenant)
        }
    }
}
