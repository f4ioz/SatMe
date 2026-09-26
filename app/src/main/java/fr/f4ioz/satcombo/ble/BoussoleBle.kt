/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import fr.f4ioz.satcombo.domain.AttitudeWit
import fr.f4ioz.satcombo.domain.BoussoleWit
import fr.f4ioz.satcombo.domain.GattWit
import java.util.UUID

/**
 * The remote compass: a WitMotion attitude module over Bluetooth Low Energy,
 * clamped to the antenna boom.
 *
 * **Why off the phone.** A hand-held phone is rarely aligned with the boom and
 * sits inches from an FT-817 speaker magnet. On the boom the module is aligned
 * by construction, and aluminium disturbs nothing.
 *
 * **Datasheet rules no software works around:** at least 20 cm from any
 * magnet, speaker or electronics; recalibrate after every remount.
 *
 * A singleton because the link must outlive screen changes: reconnecting on
 * recomposition would make the heading flicker when it is needed.
 */
object BoussoleBle {

    /** Where the link stands. */
    enum class Etat { ARRET, RECHERCHE, CONNEXION, CONNECTE, MUET, ECHEC }

    data class Appareil(val nom: String, val adresse: String, val rssi: Int)

    // --- what the interface observes ---

    /**
     * The module's heading in degrees, or `null` until something arrives.
     *
     * Compose state, not a `UiState` field: up to 200 frames per second would
     * redraw the whole screen each time. The dial reads it directly.
     */
    val cap = mutableStateOf<Float?>(null)

    /**
     * Offset, held here rather than passed on every read: the dial reads `cap`
     * 200 times a second, and this changes once per outing. Set at startup
     * and whenever the operator changes it.
     */
    var calage: Float = 0f
        set(v) { field = v; lisse = Float.NaN }

    /**
     * The module's axis convention (e.g. east-north-up vs north-east-down),
     * by name. Measured by the two calibration sightings, never guessed: a
     * wrong one swaps east and west — a mirror, not an offset.
     */
    var convention: String = "DIRECTE"
        set(v) { field = v; lisse = Float.NaN }

    /**
     * The boom direction in the case's frame, or `null` until learned.
     *
     * When known, heading and elevation both come from the pointing vector,
     * and rotating the antenna on its axis changes nothing. Until then, yaw
     * alone and no elevation: there is no honest way to derive it.
     */
    var fleche: fr.f4ioz.satcombo.domain.Vec3? = null
        set(v) { field = v; lisse = Float.NaN }

    /** True when the operator picked the module as heading source. */
    var choisie: Boolean = false

    val etat = mutableStateOf(Etat.ARRET)

    /** The reason of the last failure, in plain words. Empty when none. */
    val raison = mutableStateOf("")

    val trouves = mutableStateListOf<Appareil>()

    /** Frames since connection — a counter that moves is reassuring. */
    val trames = mutableStateOf(0)

    /**
     * Yaw as the module gives it, before offset and inversion. For calibration
     * only: an offset derived from a corrected figure corrects the correction.
     */
    val lacetBrut = mutableStateOf<Float?>(null)

    /**
     * Elevation as seen by the module, or `null` when the operator has named
     * no axis — in which case the phone keeps the lead, as before.
     */
    val elevation = mutableStateOf<Float?>(null)

    /** The last full attitude, for taking both calibration readings. */
    val attitudeBrute = mutableStateOf<AttitudeWit?>(null)

    // --- the works ---

    private val accumulateur = BoussoleWit.Accumulateur()
    private var gatt: BluetoothGatt? = null
    private var scanneur: android.bluetooth.le.BluetoothLeScanner? = null
    private var rappelScan: ScanCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private var derniereTrameMs = 0L
    private var lisse = Float.NaN

    /** The address of the last link, to reconnect on its own. */
    var derniereAdresse: String? = null
        private set

    private const val SILENCE_MS = 4_000L
    private const val DUREE_RECHERCHE_MS = 12_000L

    // --- permissions ---

    private fun aLaPermission(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What to ask for, depending on the Android version. */
    fun permissionsNecessaires(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun permissionsAccordees(ctx: Context): Boolean =
        permissionsNecessaires().all { aLaPermission(ctx, it) }

    private fun adaptateur(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Records an **explicit** failure. Every error path goes through here: a
     * frozen dial does not tell the operator whether to switch the module on,
     * grant a permission or step closer.
     */
    private fun echoue(pourquoi: String) {
        raison.value = pourquoi
        etat.value = Etat.ECHEC
        cap.value = null
        elevation.value = null
    }

    // --- scanning ---

    @SuppressLint("MissingPermission")
    fun cherche(ctx: Context) {
        arreteRecherche()
        trouves.clear()

        if (!permissionsAccordees(ctx)) {
            echoue("permissions"); return
        }
        val ad = adaptateur(ctx)
        if (ad == null) { echoue("pas_de_bluetooth"); return }
        if (!ad.isEnabled) { echoue("bluetooth_eteint"); return }

        val s = ad.bluetoothLeScanner
        if (s == null) { echoue("scanner_indisponible"); return }

        val rappel = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult?) {
                val d = r?.device ?: return
                val nom = try { d.name } catch (_: SecurityException) { null }
                // Show everything named: filtering on "WT" would hide a renamed
                // module, and the advertisement carries no service UUID.
                val etiquette = nom ?: return
                if (trouves.none { it.adresse == d.address }) {
                    trouves.add(Appareil(etiquette, d.address, r.rssi))
                    // WitMotion modules first.
                    trouves.sortByDescending { GattWit.nomPlausible(it.nom) }
                }
            }

            override fun onScanFailed(code: Int) {
                echoue("recherche_echec_$code")
            }
        }
        rappelScan = rappel
        scanneur = s
        etat.value = Etat.RECHERCHE
        raison.value = ""

        val reglages = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(null, reglages, rappel)
        } catch (e: Exception) {
            echoue("recherche_refusee"); return
        }

        // An endless scan drains the battery and, since Android 7, is cut by
        // the system silently.
        main.postDelayed({
            if (etat.value == Etat.RECHERCHE) {
                arreteRecherche()
                if (trouves.isEmpty()) echoue("rien_trouve") else etat.value = Etat.ARRET
            }
        }, DUREE_RECHERCHE_MS)
    }

    @SuppressLint("MissingPermission")
    fun arreteRecherche() {
        val s = scanneur; val r = rappelScan
        if (s != null && r != null) {
            try { s.stopScan(r) } catch (_: Exception) { /* already stopped */ }
        }
        scanneur = null; rappelScan = null
    }

    // --- the link ---

    @SuppressLint("MissingPermission")
    fun connecte(ctx: Context, adresse: String) {
        arreteRecherche()
        coupe()

        if (!permissionsAccordees(ctx)) { echoue("permissions"); return }
        val ad = adaptateur(ctx)
        if (ad == null) { echoue("pas_de_bluetooth"); return }
        if (!ad.isEnabled) { echoue("bluetooth_eteint"); return }

        val appareil: BluetoothDevice = try {
            ad.getRemoteDevice(adresse)
        } catch (e: IllegalArgumentException) {
            echoue("adresse_invalide"); return
        }

        derniereAdresse = adresse
        etat.value = Etat.CONNEXION
        raison.value = ""
        accumulateur.vide()
        lisse = Float.NaN
        trames.value = 0

        gatt = try {
            appareil.connectGatt(ctx, false, rappelGatt, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            echoue("connexion_refusee"); null
        }
    }

    @SuppressLint("MissingPermission")
    fun coupe() {
        main.removeCallbacks(veilleur)
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect(); g.close() } catch (_: Exception) { /* already closed */ }
        }
        cap.value = null
        elevation.value = null
        lisse = Float.NaN
        if (etat.value != Etat.ECHEC) { etat.value = Etat.ARRET; raison.value = "" }
    }

    /** Reconnect to the last known module, at startup or after a drop. */
    fun reprend(ctx: Context) {
        val a = derniereAdresse
        if (a.isNullOrBlank()) { echoue("aucun_module"); return }
        connecte(ctx, a)
    }

    /**
     * Silence watchdog.
     *
     * A silent module looks like a working one: the GATT link stays open, no
     * error arrives, the dial shows the last value. **Silence needs its own
     * branch**, or the operator aims on a two-minute-old heading.
     */
    // Quaternion reads were removed: useless once the module is magnetically
    // calibrated. The verified protocol is in the handover notes, should the
    // ninety-degree dropout reappear.

    private val veilleur = object : Runnable {
        override fun run() {
            if (etat.value == Etat.CONNECTE || etat.value == Etat.MUET) {
                val silence = System.currentTimeMillis() - derniereTrameMs
                if (silence > SILENCE_MS) {
                    etat.value = Etat.MUET
                    // The dial falls back to the phone, elevation included.
                    cap.value = null
                    elevation.value = null
                } else if (etat.value == Etat.MUET) {
                    etat.value = Etat.CONNECTE
                }
                main.postDelayed(this, 1_000L)
            }
        }
    }

    private val rappelGatt = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nouvel: Int) {
            when (nouvel) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try { g.discoverServices() } catch (e: Exception) { echoue("services_refuses") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    main.removeCallbacks(veilleur)
                    cap.value = null
                    elevation.value = null
                    // A deliberate disconnect has already nulled `gatt`.
                    if (gatt != null) echoue("liaison_perdue_$status")
                    try { g.close() } catch (_: Exception) { }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { echoue("services_echec_$status"); return }

            val service = g.getService(UUID.fromString(GattWit.SERVICE))
            if (service == null) { echoue("service_absent"); return }

            val carac = service.getCharacteristic(UUID.fromString(GattWit.NOTIFICATION))
            if (carac == null) { echoue("caracteristique_absente"); return }

            val pris = try { g.setCharacteristicNotification(carac, true) }
                catch (e: Exception) { false }
            if (!pris) { echoue("notification_refusee"); return }

            // The subscription only takes once the CCCD is written: without
            // it, `setCharacteristicNotification` returns `true` and nothing
            // ever arrives.
            val d: BluetoothGattDescriptor? = carac.getDescriptor(UUID.fromString(GattWit.CCCD))
            if (d == null) { echoue("descripteur_absent"); return }

            val ecrit = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            } catch (e: Exception) { false }
            if (!ecrit) { echoue("abonnement_refuse"); return }

            derniereTrameMs = System.currentTimeMillis()
            etat.value = Etat.CONNECTE
            raison.value = ""
            main.removeCallbacks(veilleur)
            main.postDelayed(veilleur, 1_000L)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, valeur: ByteArray
        ) { recoit(valeur) }

        @Deprecated("Android 12 et avant")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            recoit(c.value ?: return)
        }
    }

    private fun recoit(octets: ByteArray) {
        val lues: List<AttitudeWit> = accumulateur.verse(octets)
        if (lues.isEmpty()) return
        derniereTrameMs = System.currentTimeMillis()
        trames.value += lues.size
        val derniere = lues.last()
        val f = fleche
        // With the boom known, pointing reads off a vector and polarisation
        // rotation does not matter. Without it, yaw alone: degraded but honest.
        val el: Float?
        if (f != null) {
            // **No offset or sign correction here.** Matrix and vector fully
            // determine the heading; an extra correction is a second answer to
            // one question (a leftover 175° offset once sent east to west).
            // The convention is the **measured** one. The offset serves only
            // the scalar fallback.
            val p = fr.f4ioz.satcombo.domain.PointageAntenne.pointageLibre(
                derniere, f, conventionActuelle())
            lisse = BoussoleWit.lisse(lisse, p.azimutDeg)
            el = p.elevationDeg
        } else {
            lisse = BoussoleWit.lisse(
                lisse, BoussoleWit.azimutDepuisLacet(
                    // The scalar fallback has only a yaw: with no vector to
                    // swap axes in, only the sign the measured convention
                    // applies to yaw means anything.
                    derniere.lacet, calage,
                    conventionActuelle().let { c ->
                        c.signes[c.assign.indexOf(2)] < 0
                    }))
            el = null
        }
        // Compose state: publish on the main thread, BLE calls from a binder
        // thread.
        val v = lisse
        val brut = derniere.lacet
        main.post {
            cap.value = v
            lacetBrut.value = brut
            attitudeBrute.value = derniere
            elevation.value = el
        }
    }

    /** The frame handed to the vector computation. */
    private fun conventionActuelle() =
        fr.f4ioz.satcombo.domain.PointageAntenne.ConventionLibre.decode(convention)

    fun enService(): Boolean = etat.value == Etat.CONNECTE
}
