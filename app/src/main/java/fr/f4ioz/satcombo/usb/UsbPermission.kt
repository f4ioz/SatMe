/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The only place in the app that builds the PendingIntent
 * [UsbManager.requestPermission] returns once the user has answered the system
 * dialog.
 *
 * **Why one place.** Since Android 14 (API 34), a MUTABLE PendingIntent
 * carrying an *implicit* intent (no package, no component) throws
 * `IllegalArgumentException` in `PendingIntent.getBroadcast`: the app dies the
 * instant CAT is switched on. Android 13 accepts the same code silently, so
 * the fault only shows on a recent device.
 *
 * The PendingIntent must stay MUTABLE — the system writes
 * [UsbManager.EXTRA_DEVICE] and [UsbManager.EXTRA_PERMISSION_GRANTED] into it —
 * so the intent is made explicit with [Intent.setPackage].
 */
object UsbPermission {

    /** Permission request for CAT (USB-serial adapters on the rigs). */
    const val ACTION_CAT = "fr.f4ioz.satcombo.USB_PERMISSION"

    /**
     * Builds the reply PendingIntent: always explicit, MUTABLE where needed.
     *
     * [requestCode] tells simultaneous requests apart — the FT-817 pair fires
     * one per adapter, and two PendingIntents sharing a code would overwrite
     * each other.
     */
    fun pendingIntent(ctx: Context, action: String, requestCode: Int = 0): PendingIntent {
        val intent = Intent(action).setPackage(ctx.packageName)
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    /**
     * Requests permission for [dev] when missing, never letting an exception
     * escape: a vendor quirk must come out as "it does not open", not a crash.
     *
     * True when permission is already held; false otherwise (the system
     * dialog is shown and the answer comes later).
     */
    fun ensure(ctx: Context, um: UsbManager, dev: UsbDevice,
               action: String, requestCode: Int = 0): Boolean {
        if (runCatching { um.hasPermission(dev) }.getOrDefault(false)) return true
        runCatching { um.requestPermission(dev, pendingIntent(ctx, action, requestCode)) }
        return false
    }

    /**
     * Requests permission **and waits for the answer**.
     *
     * [ensure] returns at once, so a port opened right after it fails while
     * the dialog is still up — the connection failed on the first try and
     * worked on the second. Hence waiting for the answer. The receiver is
     * non-exported (API 33+): another app's USB intent has no business here.
     *
     * True when permission is held. A timeout returns false harmlessly: the
     * user may simply have put the phone down.
     */
    suspend fun await(ctx: Context, um: UsbManager, dev: UsbDevice,
                      action: String, requestCode: Int = 0,
                      timeoutMs: Long = 30_000L): Boolean {
        if (runCatching { um.hasPermission(dev) }.getOrDefault(false)) return true
        val accorde = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val recv = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        if (i?.action != action) return
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        runCatching { ctx.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                val filtre = IntentFilter(action)
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        ctx.registerReceiver(recv, filtre, Context.RECEIVER_NOT_EXPORTED)
                    else ctx.registerReceiver(recv, filtre)
                }
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(recv) } }
                runCatching { um.requestPermission(dev, pendingIntent(ctx, action, requestCode)) }
                    .onFailure { if (cont.isActive) cont.resume(false) }
            }
        }
        // On some phones the broadcast is lost although permission was
        // granted: ask the system rather than trust our receiver.
        return accorde ?: runCatching { um.hasPermission(dev) }.getOrDefault(false)
    }
}
