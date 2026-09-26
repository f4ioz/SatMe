/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
     * Les touches de volume, détournées en commande de VFO.
     *
     * Une molette USB de volume est un clavier HID qui n'envoie que volume
     * plus, volume moins et sourdine. Android les traite comme les touches du
     * téléphone ; c'est ici, et seulement quand l'application est au premier
     * plan, qu'on peut les reprendre.
     *
     * **On ne reprend que ce qui vient d'un appareil branché.** Les touches
     * physiques du téléphone gardent leur rôle : confisquer le réglage
     * d'écoute sur une application de trafic serait absurde, et l'opérateur
     * n'aurait aucun moyen de comprendre pourquoi son volume ne bouge plus.
     *
     * Rendre `false` laisse l'événement au système : c'est ce qui se passe
     * quand le réglage est fermé, ou que la touche vient du téléphone.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val externe = runCatching {
                android.view.InputDevice.getDevice(event.deviceId)?.isExternal == true
            }.getOrDefault(false)
            if (vm.moletteCran(event.keyCode, externe)) return true
        }
        // Le relâchement de la touche qu'on a prise doit être avalé lui aussi,
        // sinon le système la voit remonter sans l'avoir vue descendre et
        // applique quand même son effet.
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
            // Au premier démarrage après installation, le suivi de position a
            // déjà tenté de partir et s'est fait refuser par le système. Rien
            // ne le prévenait ensuite que la réponse était arrivée : c'est le
            // chaînon qui manquait.
            runCatching { vm.onPermissionsResult() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Les trois instructions qui suivent parlent au système, et le système
        // n'est pas le même partout. Une seule d'entre elles qui lève, et
        // l'application se ferme avant d'avoir dessiné quoi que ce soit — sans
        // canal de notification, sans mise à jour proposée ou sans satellite
        // pré-ouvert, SatMe reste parfaitement utilisable ; fermée, non.
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

        // L'intention vient du lanceur, d'une notification, ou d'une clé USB
        // que le constructeur a décrite à sa façon. La lire, c'est désérialiser
        // un colis que nous n'avons pas emballé.
        runCatching { handleOpenIntent(intent) }

        setContent {
            SatComboTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        SatComboApp(vm)
                        UpdatePrompt(vm, updater)
                        PlantagePrompt()
                        // La liaison de la boussole déportée : globale, donc à
                        // la racine — pas dans l'écran de pointage, qu'on
                        // quitte en plein passage pour ouvrir le carnet.
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
        // Le rappel d'un rendez-vous : on ouvre l'agenda, pas un satellite.
        if (intent?.getBooleanExtra("open_agenda", false) == true) vm.openAgenda()

        // Une clé USB vient d'être branchée. Android nous réveille avec cette
        // intention ; en singleTop elle arrive par onNewIntent, l'écran en
        // cours est donc conservé. On note simplement la présence de la clé —
        // et surtout on ne change pas d'écran, c'était tout le problème.
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
