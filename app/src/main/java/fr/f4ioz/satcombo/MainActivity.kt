/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import fr.f4ioz.satcombo.diag.PlantagePrompt
import fr.f4ioz.satcombo.notify.PassNotifier
import fr.f4ioz.satcombo.ui.SatComboApp
import fr.f4ioz.satcombo.ui.theme.SatComboTheme
import fr.f4ioz.satcombo.update.PlayUpdater
import fr.f4ioz.satcombo.update.UpdatePrompt

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /**
     * Volume keys hijacked as a VFO control.
     *
     * A USB volume knob is a HID keyboard sending only volume up, volume down
     * and mute. Android treats them like the phone's own keys; this is the only
     * place, and only while in the foreground, where we can take them back.
     *
     * **Only keys from an external device are taken.** The phone's own keys
     * keep their role: the operator would have no way to understand why the
     * volume no longer moves.
     *
     * Returning `false` leaves the event to the system (setting off, or key
     * from the phone).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val externe = runCatching {
                android.view.InputDevice.getDevice(event.deviceId)?.isExternal == true
            }.getOrDefault(false)
            if (vm.moletteCran(event.keyCode, externe)) return true
        }
        // The release of a key we took must be swallowed too, otherwise the
        // system sees it come up without having seen it go down and still
        // applies its effect.
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            val externe = runCatching {
                android.view.InputDevice.getDevice(event.deviceId)?.isExternal == true
            }.getOrDefault(false)
            val M = fr.f4ioz.satcombo.domain.MoletteUsb
            val a = vm.ui.value.accord
            val geste = M.geste(
                event.keyCode, vm.ui.value.moletteVfo, externe,
                vm.ui.value.molettePasHz,
                fr.f4ioz.satcombo.domain.MoletteUsb.Touches(
                    a.macroCodeA, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleA),
                    a.macroCodeB, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleB),
                    a.macroCodeC, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleC),
                    a.macroCodeD, fr.f4ioz.satcombo.domain.MoletteUsb.Action.valueOf(a.macroActionD)))
            if (geste != fr.f4ioz.satcombo.domain.MoletteUsb.Geste.Ignore) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Built in onCreate: registerForActivityResult refuses to register once
    // the activity is started.
    private lateinit var updater: PlayUpdater

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.bootstrap()
            // On first launch after install, location tracking has already
            // tried to start and been refused. Nothing else tells it that the
            // answer has arrived.
            runCatching { vm.onPermissionsResult() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // These system calls differ between vendors. If one throws, the app
        // closes before drawing anything. Without a notification channel, an
        // update prompt or a pre-opened satellite SatMe is still usable;
        // closed, it is not.
        runCatching { PassNotifier.ensureChannel(this) }
        updater = PlayUpdater(this, vm)

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())

        // The intent comes from the launcher, a notification, or a USB device
        // described the vendor's way. Reading it deserialises a parcel we did
        // not pack.
        runCatching { handleOpenIntent(intent) }

        setContent {
            SatComboTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        SatComboApp(vm)
                        UpdatePrompt(vm, updater)
                        PlantagePrompt()
                        // Remote compass link: global, so at the root — not in
                        // the pointing screen, which is left mid-pass to open
                        // the log.
                        fr.f4ioz.satcombo.ble.TenueBoussole(vm)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Cheap, and it also catches an update downloaded while the app was in
        // the background.
        updater.check()
    }

    override fun onDestroy() {
        updater.dispose()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { handleOpenIntent(intent) }
    }

    /** A notification tap carries the satellite to open. */
    private fun handleOpenIntent(intent: android.content.Intent?) {
        val cat = intent?.getIntExtra("open_catnum", -1) ?: -1
        if (cat > 0) {
            val aos = intent?.getLongExtra("open_aos", 0L) ?: 0L
            vm.openFromNotification(cat, aos)
        }
        // An appointment reminder opens the agenda, not a satellite.
        if (intent?.getBooleanExtra("open_agenda", false) == true) vm.openAgenda()
        // A pass kept by itself: the journal opened on it.
        val N = fr.f4ioz.satcombo.notify.JournalNotifier
        val jc = intent?.getIntExtra(N.EXTRA_CATNUM, -1) ?: -1
        if (jc > 0) vm.ouvreJournalSur(jc, intent!!.getLongExtra(N.EXTRA_DEBUT, 0L), intent.getLongExtra(N.EXTRA_FIN, 0L))

        // A USB device was plugged in. In singleTop this arrives via
        // onNewIntent, so the current screen is kept. Just record the device —
        // and do NOT change screen, that was the whole problem.
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val dev: android.hardware.usb.UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(
                        android.hardware.usb.UsbManager.EXTRA_DEVICE,
                        android.hardware.usb.UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
            vm.onUsbDeviceAttached(dev)
        }
    }
}
