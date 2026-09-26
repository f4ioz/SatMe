/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import fr.f4ioz.satcombo.data.Observer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    /** Default QTH = F6KMX, Saint-Maur-des-Fossés (JN18FS) if no fix yet. */
    val defaultObserver = Observer(48.8049, 2.4836, 45.0, "F6KMX JN18FS")

    @SuppressLint("MissingPermission")
    suspend fun current(): Observer = suspendCancellableCoroutine { cont ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    cont.resume(Observer(loc.latitude, loc.longitude, loc.altitude, "Live GPS"))
                } else cont.resume(defaultObserver)
            }
            .addOnFailureListener { cont.resume(defaultObserver) }
    }

    /**
     * Continuous high-accuracy fixes for live positioning (e.g. walking between
     * grid squares on the locator map). Emits an [Observer] on every update and
     * stops requesting fixes when the collector cancels.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 2000L): Flow<Observer> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(Observer(loc.latitude, loc.longitude, loc.altitude, "Live GPS"))
            }
        }
        runCatching {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        }.onFailure { close(it) }
        awaitClose { runCatching { client.removeLocationUpdates(cb) } }
    }
}
