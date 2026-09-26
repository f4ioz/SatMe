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
 * Pourquoi un endroit unique : depuis Android 14 (API 34), un PendingIntent
 * MUTABLE carrying an *implicit* intent — one naming neither package nor
 * component — is refused outright by the platform:
 *
 *     java.lang.IllegalArgumentException: fr.f4ioz.satcombo: Targeting U+
 *     (version 34 and above) disallows creating or retrieving a PendingIntent
 *     with FLAG_MUTABLE, an implicit Intent within and without FLAG_NO_CREATE
 *     and FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT for security reasons.
 *
 * The exception comes from `PendingIntent.getBroadcast`, before a single CAT
 * frame moves: the app dies the instant the switch is flipped. On a phone
 * still on Android 13 the same code passes without a murmur, which makes the
 * fault invisible until you try a recent device (seen on a Pixel 8 in 18.5).
 *
 * The PendingIntent must stay MUTABLE — the system writes
 * [UsbManager.EXTRA_DEVICE] and [UsbManager.EXTRA_PERMISSION_GRANTED] into it —
 * so the only way out is making the intent explicit with [Intent.setPackage].
 */
object UsbPermission {

    /** Permission request for CAT (USB-serial adapters on the rigs). */
    const val ACTION_CAT = "fr.f4ioz.satcombo.USB_PERMISSION"

    /**
     * Builds the reply PendingIntent: always explicit (limited to our
     * package), MUTABLE where the platform demands it.
     *
     * [requestCode] tells simultaneous requests apart — the FT-817 pair fires
     * one per adapter, and two PendingIntents sharing a code would
     * recouvriraient.
     */
    fun pendingIntent(ctx: Context, action: String, requestCode: Int = 0): PendingIntent {
        val intent = Intent(action).setPackage(ctx.packageName)
        val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(ctx, requestCode, intent, flags)
    }

    /**
     * Requests permission for [dev] when missing, never letting an exception
     * escape: a refused permission, a missing driver or a vendor quirk must
     * come out as "it does not open", never as a crash.
     *
     * Returns `true` when permission is already held, `false` otherwise (the
     * system dialog is then shown, and the answer comes later).
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
     * This was the missing half, and it explains the ritual the operator had
     * worked out on his own: "I plug the SDR dongle in, go to the SDR menu,
     * unplug and connect the IC-9700, and then it works". [ensure] only shows
     * the system dialog; it returns at once, and the port opening that followed
     * failed while the user still had a finger in the air. The second attempt
     * worked — permission having been granted meanwhile — hence a connection
     * that never succeeded on the first try and always on the second.
     *
     * So we listen for the answer. The receiver is declared non-exported where
     * the platform requires it (API 33+): a USB permission intent from another
     * app has no business here.
     *
     * True when permission is held — just granted, or already there.
     * A timeout returns false without breaking anything: the user may simply
     * have put the phone down.
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
        // The safety net: on some phones the broadcast is lost while the
        // permission is in fact granted. Asking the system beats trusting our
        // own receiver.
        return accorde ?: runCatching { um.hasPermission(dev) }.getOrDefault(false)
    }
}
