/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.rotor.BornesControleur
import fr.f4ioz.satcombo.rotor.CorrectionAxe
import fr.f4ioz.satcombo.rotor.CorrectionRotor
import fr.f4ioz.satcombo.rotor.Gs232Codec
import fr.f4ioz.satcombo.rotor.Gs232Rotor
import fr.f4ioz.satcombo.rotor.Gs232Simulator
import fr.f4ioz.satcombo.rotor.RotctldRotor
import fr.f4ioz.satcombo.rotor.RotorChemin
import fr.f4ioz.satcombo.rotor.RotorCorrige
import fr.f4ioz.satcombo.rotor.RotorDeroule
import fr.f4ioz.satcombo.rotor.RotorDriver
import fr.f4ioz.satcombo.rotor.RotorMath
import fr.f4ioz.satcombo.rotor.RotorPas
import fr.f4ioz.satcombo.rotor.RotorPlanPassage
import fr.f4ioz.satcombo.rotor.RotorPos
import fr.f4ioz.satcombo.rotor.RotorTenue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the mast needs to know of the rest of the station: the satellite
 * followed and its pass, whether we transmit, the SSTV pictures. The phone
 * answers from its screens and services; a PC station from its own.
 */
interface MondeRotor {
    /** The satellite followed, now; null when none. */
    fun satellite(): SatPosition?
    /** Its name ("" when none): the pictures that matter are this satellite's. */
    fun nomSatellite(): String
    /** The pass being followed, as (az, el) points; empty when none. */
    fun arcDuPassage(): List<Pair<Double, Double>>
    /** AOS of that pass (ms), 0 when none. */
    fun aosDuPassage(): Long
    /** Transmitting now (CAT or APRS): a turn round the stop waits. */
    fun enEmission(): Boolean
    /** The SSTV picture coming in now (start..end, ms), null when none. */
    fun imageEnCours(maintenant: Long): LongRange?
    /** The pictures received since [depuisMs] from [nomSatellite] ("" = any): start..end, ms. */
    fun imagesRecues(nomSatellite: String, depuisMs: Long): List<LongRange>
    /** The rigs' adapters: an automatic search never opens them (RTS/DTR may key them). */
    fun adaptateursDesPostes(): List<String>
    /** Something failed in [ou]; tracking has been stopped. */
    fun signale(ou: String, e: Throwable) {}
}

/**
 * The mast: connecting, following the satellite, parking, waiting at the rise
 * point, going round the end stop — what used to live in the phone's
 * ViewModel, now shared by the phone and the PC.
 *
 * Its state ([etat]) is only ever changed from its current value
 * (`update {}`): writing back a copy taken before a serial read undid what
 * changed meanwhile (a recording stopped by hand stayed "recording", 11/10).
 */
class StationRotor(
    private val settings: SettingsStore,
    private val ports: PortsSerie?,
    private val portee: CoroutineScope,
    private val monde: MondeRotor,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** False for the tests, which call [rotorTick] themselves, at their own pace. */
    private val boucleAuto: Boolean = true
) {
    private val _etat = MutableStateFlow(EtatRotor(
        rotorEnabled = settings.rotorEnabled,
        rotorLink = settings.rotorLink,
        rotorUsbIndex = settings.rotorUsbIndex,
        rotorBaud = settings.rotorBaud,
        rotorHost = settings.rotorHost,
        rotorPort = settings.rotorPort,
        rotorMaxAz = settings.rotorMaxAz,
        rotorMaxEl = settings.rotorMaxEl,
        rotorDeadband = settings.rotorDeadband,
        rotorFlip = settings.rotorFlip,
        rotorAzOnly = settings.rotorAzOnly,
        rotorParkAz = settings.rotorParkAz,
        rotorParkEl = settings.rotorParkEl,
        rotorMinEl = settings.rotorMinEl,
        rotorPreAos = settings.rotorPreAos,
        rotorSim = settings.rotorSim,
        rotorAzStop = settings.rotorAzStop,
        rotorAzFromStop = settings.rotorAzFromStop,
        rotorMaxError = settings.rotorMaxError,
        rotorZones = settings.rotorZones,
        rotorCorrAz = settings.rotorCorrAz,
        rotorCorrEl = settings.rotorCorrEl))
    val etat: StateFlow<EtatRotor> = _etat.asStateFlow()

    private fun change(f: EtatRotor.() -> EtatRotor) = _etat.update { it.f() }

    private var rotorDriver: RotorDriver? = null

    /** The same driver, through the software correction: what reads and sends in real degrees. */
    private var rotorCorrige: RotorCorrige? = null

    /** The software correction now (identity unless calibrated). */
    @Volatile var correction: CorrectionRotor = CorrectionRotor(
        CorrectionAxe.lit(settings.rotorCorrAz), CorrectionAxe.lit(settings.rotorCorrEl))
        private set

    /**
     * The serial line, one talker at a time: the loop's `C2` slipped into a
     * calibration's handshake would be taken as its keystroke, and the pot
     * read wherever the mast happens to be.
     */
    private val liaison = Mutex()

    /** Tracking as it was when the calibration assistant opened, given back when it closes. */
    private var suiviAvantEtalonnage = false

    /**
     * The mast's mechanical state as the app believes it. Not the command:
     * a minute of rotation lies between them, which is what makes overlap
     * useful. When the controller reports its position it is authoritative;
     * when silent (cable torn, mute controller) we fall back on the last
     * command, and the banner says so.
     */
    private var rotorAt = RotorPos(0.0, 0.0)

    /**
     * Last position **actually read**, and when. Display only, to hold a few
     * seconds when the controller skips a reply (see [RotorTenue]); a held
     * position must never decide pointing.
     */
    private var rotorLu: RotorPos? = null
    private var rotorLuMs: Long = 0L
    /**
     * The mast's path over a pass crossing the stop (see [RotorChemin]),
     * empty when the pass does not cross it; the expected pictures it was planned with; a turn
     * held (transmission, picture coming in) that calls for a new plan from where the mast is.
     */
    private var rotorChemin: List<RotorChemin.Point> = emptyList()
    private var rotorCheminImages: List<LongRange> = emptyList()
    private var rotorCheminARefaire = false
    /** SSTV pictures of the last half hour (start..end), read from the gallery now and then. */
    private var rotorImages: List<LongRange> = emptyList()
    private var rotorImagesMs = 0L

    /**
     * The pass's track with its times (AOS to LOS): where the satellite will be when a
     * picture comes. Given by whoever computes the pass.
     */
    @Volatile var traceTemps: List<Triple<Long, Double, Double>> = emptyList()

    /**
     * Last command sent — not the mast position. The next azimuth is unwrapped
     * near it, not near the read position: while the mast climbs towards 540°
     * the controller answers 380, 400, 420; unwrapping near those would send
     * the command back to the branch we came from, and the mast would turn
     * round mid-pass — exactly what the plan avoided.
     */
    private var rotorCmd = RotorPos(0.0, 0.0)

    /** Mast turn chosen for the current pass, at AOS. */
    private var rotorPlan: RotorMath.Plan? = null
    private var rotorPlanKey: String? = null
    /** The whole pass decided before AOS (side, flip, unwind and when), null without the pass's times. */
    private var rotorPlanPassage: RotorPlanPassage.Plan? = null
    private var rotorLoopJob: Job? = null
    private var rotorWasFlipped = false
    private var rotorSimLink: Gs232Simulator? = null

    /** The simulated mast, when that is what is connected (tests, a station without hardware). */
    val simulateur: Gs232Simulator? get() = rotorSimLink

    /**
     * Same driver as [rotorDriver] when it speaks GS-232. The concrete type is
     * needed for raw frames: the common interface knows only "position" and
     * "go", enough to track, not to understand why nothing moves.
     */
    private var rotorSerial: Gs232Rotor? = null

    /** The aim at the last tick, its speed, and when the last command left (see RotorPas). */
    private var rotorVisePrec: RotorPos? = null
    private var rotorVisePrecMs = 0L
    private var rotorVitesseAz = 0.0
    private var rotorVitesseEl = 0.0
    private var rotorDernierEnvoiMs = 0L
    /** Where the mast was last seen moving from, and when (see RotorPas: a command not taken). */
    private var rotorBougeRef: RotorPos? = null
    private var rotorBougeMs = 0L
    /** The last command the controller holds (true azimuth), null when unknown: see RotorPas. */
    private var rotorDerniereCmd: RotorPos? = null

    /** The adapter the GS-232 is open on (its key): a rig search must not ask it questions. */
    val cleUsb: String? get() = rotorSerial?.takeIf { it.isOpen }?.cleUsb

    /**
     * Same safety net as for CAT, with one weighty difference: a failure also
     * stops tracking. After an exception we no longer know what the driver
     * sent; a loop still pushing commands would turn a mast who knows where.
     */
    private inline fun rotorGuard(where: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            val what = e::class.java.simpleName + (e.message?.let { ": " + it } ?: "")
            change { copy(rotorConnected = false, rotorStatus = tf("rotor_error", what)) }
            monde.signale(where, e)
        }
    }

    /** The settings read again (a configuration restored): the new state. */
    fun rechargeReglages(): EtatRotor {
        change { copy(
            rotorEnabled = settings.rotorEnabled,
            rotorBaud = settings.rotorBaud,
            rotorUsbIndex = settings.rotorUsbIndex,
            rotorHost = settings.rotorHost,
            rotorPort = settings.rotorPort,
            rotorMaxAz = settings.rotorMaxAz,
            rotorMaxEl = settings.rotorMaxEl,
            rotorAzStop = settings.rotorAzStop,
            rotorAzFromStop = settings.rotorAzFromStop,
            rotorMaxError = settings.rotorMaxError,
            rotorZones = settings.rotorZones,
            rotorMinEl = settings.rotorMinEl,
            rotorDeadband = settings.rotorDeadband,
            rotorFlip = settings.rotorFlip,
            rotorAzOnly = settings.rotorAzOnly,
            rotorParkAz = settings.rotorParkAz,
            rotorParkEl = settings.rotorParkEl,
            rotorCorrAz = settings.rotorCorrAz,
            rotorCorrEl = settings.rotorCorrEl) }
        correction = CorrectionRotor(CorrectionAxe.lit(settings.rotorCorrAz), CorrectionAxe.lit(settings.rotorCorrEl))
        return _etat.value
    }

    /** A line on the rotor's status (a cable just plugged in…). */
    fun annonce(statut: String) = change { copy(rotorStatus = statut) }

    /**
     * Tracking on or off. While the calibration assistant is open it stays
     * off: an order to follow is kept for when the assistant closes — a mast
     * set off after a satellite mid-calibration would turn under the
     * operator's inclinometer.
     */
    fun setRotorEnabled(v: Boolean) {
        if (v && _etat.value.rotorEtalonnage) {
            suiviAvantEtalonnage = true
            change { copy(rotorStatus = t("rotor_etal_suivi_attend")) }
            return
        }
        settings.rotorEnabled = v
        change { copy(rotorEnabled = v) }
    }

    fun setRotorLink(v: String) {
        settings.rotorLink = v
        change { copy(rotorLink = settings.rotorLink) }
    }

    fun setRotorUsbIndex(v: Int) {
        settings.rotorUsbIndex = v
        change { copy(rotorUsbIndex = settings.rotorUsbIndex) }
    }

    fun setRotorBaud(v: Int) {
        settings.rotorBaud = v
        change { copy(rotorBaud = settings.rotorBaud) }
    }

    fun setRotorHost(v: String) {
        settings.rotorHost = v
        change { copy(rotorHost = settings.rotorHost) }
    }

    fun setRotorPort(v: Int) {
        settings.rotorPort = v
        change { copy(rotorPort = settings.rotorPort) }
    }

    fun setRotorMaxAz(v: Int) {
        settings.rotorMaxAz = v
        change { copy(rotorMaxAz = settings.rotorMaxAz) }
    }

    /**
     * Changing the end stop discards the current plan: it was computed for one
     * stop and means nothing for another. Keeping it until the next pass would
     * point the mast 180° off.
     */
    fun setRotorAzStop(v: String) {
        settings.rotorAzStop = v
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorAzStop = settings.rotorAzStop, rotorCoverage = null) }
    }

    fun setRotorAzFromStop(v: Boolean) {
        settings.rotorAzFromStop = v
        change { copy(rotorAzFromStop = settings.rotorAzFromStop) }
    }

    fun setRotorAzOnly(v: Boolean) {
        settings.rotorAzOnly = v
        change { copy(rotorAzOnly = settings.rotorAzOnly, rotorAimEl = if (v) null else rotorAimEl) }
    }

    fun setRotorMaxError(v: Int) {
        settings.rotorMaxError = v
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorMaxError = settings.rotorMaxError, rotorCoverage = null) }
    }

    fun setRotorMaxEl(v: Int) {
        settings.rotorMaxEl = v
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorMaxEl = settings.rotorMaxEl) }
    }

    /** Obstacle zones (`170-190<20;…`): the plan weighs them; the current one is redone. */
    fun setRotorZones(v: String) {
        settings.rotorZones = RotorPlanPassage.ecritZones(RotorPlanPassage.litZones(v))
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorZones = settings.rotorZones) }
    }

    fun setRotorDeadband(v: Int) {
        settings.rotorDeadband = v
        change { copy(rotorDeadband = settings.rotorDeadband) }
    }

    fun setRotorFlip(v: Boolean) {
        settings.rotorFlip = v
        rotorWasFlipped = false
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorFlip = v) }
    }

    fun setRotorParkAz(v: Int) {
        settings.rotorParkAz = v
        change { copy(rotorParkAz = settings.rotorParkAz) }
    }

    fun setRotorParkEl(v: Int) {
        settings.rotorParkEl = v
        change { copy(rotorParkEl = settings.rotorParkEl) }
    }

    fun setRotorMinEl(v: Int) {
        settings.rotorMinEl = v
        change { copy(rotorMinEl = settings.rotorMinEl) }
    }

    /** Minutes before AOS at which the mast goes to wait for the satellite. */
    fun setRotorPreAos(v: Int) {
        settings.rotorPreAos = v
        change { copy(rotorPreAos = settings.rotorPreAos) }
    }

    fun setRotorSim(v: Boolean) {
        val w = v || simulationSeule
        settings.rotorSim = w
        change { copy(rotorSim = w) }
    }

    fun setRotorAutoPort(v: Boolean) = change { copy(rotorAutoPort = v) }

    fun setRotorManualAz(v: Int) = change { copy(rotorManualAz = v.coerceIn(0, rotorMaxAz)) }

    fun setRotorManualEl(v: Int) = change { copy(rotorManualEl = v.coerceIn(0, if (rotorFlip) 180 else rotorMaxEl)) }

    /** Visible USB adapters, so the index means something. */
    fun refreshRotorDevices() {
        rotorGuard("rotor_devices") {
            val l = Gs232Rotor(ports).listDevices()
            change { copy(rotorDevices = l) }
        }
    }

    fun rotorRequestPermissions() {
        rotorGuard("rotor_perm") {
            Gs232Rotor(ports).requestPermissions()
            refreshRotorDevices()
        }
    }

    fun connectRotor() {
        portee.launch { rotorGuard("rotor_connect") { connectRotorInner() } }
    }

    /** A demonstration (SatMe-PC's `--demo`): the simulated mast only, whatever the link chosen (no port, no rotctld). */
    @Volatile var simulationSeule = false

    private suspend fun connectRotorInner() {
        disconnectRotor()
        val u = _etat.value
        if (u.rotorSim || simulationSeule) {
            // A mast that does not exist: everything shows, nothing moves.
            // Its frames are the ring's own numbers: a south-stop mast is sent 180 to 540.
            val l = rotorLimits(u)
            // It answers the calibrations as K3NG does: the assistant can be tried without a roof.
            val sim = Gs232Simulator(
                azMaxDeg = l.azMaxReach,
                elMaxDeg = l.elMaxDeg,
                azInitDeg = l.azMinReach,
                k3ng = true)
            val d = Gs232Rotor(ports)
            d.pacingMs = 0L
            d.attach(sim)
            rotorSimLink = sim
            rotorDriver = corrige(d)
            rotorSerial = d
            rotorAt = RotorPos(l.azMinReach, 0.0)
            rotorWasFlipped = false
            change { copy(rotorConnected = true, rotorStatus = t("rotor_sim_on")) }
            startRotorLoop()
            return
        }
        if (u.rotorLink == "ROTCTLD") {
            val d = RotctldRotor()
            val ok = d.open(u.rotorHost, u.rotorPort)
            rotorDriver = if (ok) corrige(d) else null
            change { copy(rotorConnected = ok,
                rotorStatus = if (ok) tf("rotor_connected", u.rotorHost + ":" + u.rotorPort)
                else tf("rotor_error", u.rotorHost + ":" + u.rotorPort)) }
            if (ok) { rotorWasFlipped = false; startRotorLoop() }
            return
        }
        val d = Gs232Rotor(ports)
        val refs = d.availablePorts()
        if (refs.isEmpty()) {
            change { copy(rotorConnected = false, rotorStatus = t("rotor_none"),
                rotorDiag = listOf(t("cat_err_no_device"))) }
            return
        }
        // Same sequence as for the rig: wait for permission, open the chosen
        // port, raise DTR/RTS. An Arduino GS-232 emulator forgives none of
        // these.
        val trace = ArrayList<String>()
        val ordre = if (u.rotorAutoPort)
            fr.f4ioz.satcombo.cat.CatScan.ordre(refs, u.rotorUsbIndex)
        else listOf(u.rotorUsbIndex.coerceIn(0, refs.size - 1))
        var gagnant = -1
        // Why the last port tried failed. Otherwise the screen said "allow USB
        // access" whatever the failure, sending the operator after a
        // permission already granted.
        var echec = ""
        // The adapters the rigs use: never opened by an automatic search (RTS/DTR may key them).
        val clesPostes = monde.adaptateursDesPostes().filter { it.isNotBlank() }
        for (i in ordre) {
            trace.add(tf("cat_diag_try", refs[i].label))
            val id = d.identitePort(i)
            if (u.rotorAutoPort && !fr.f4ioz.satcombo.domain.RotorRecherche.permis(id?.first, id?.second, clesPostes, settings.rotorUsbCle)) {
                trace.add(t("rotor_diag_poste")); continue
            }
            if (!d.ensurePermission(i)) {
                trace.add(t("cat_diag_line_denied")); echec = t("rotor_fail_denied"); continue
            }
            if (!d.open(i, u.rotorBaud)) {
                trace.add(tf("cat_diag_line_open", d.lastError))
                echec = tf("rotor_fail_open", d.lastError); continue
            }
            // A port always opens. The reply to `C2` tells the rotor
            // controller from an SDR dongle or a rig — asked several times:
            // raising DTR resets an Arduino, and the first query goes out
            // during boot.
            trace.add(t("rotor_diag_settle"))
            val pos = runCatching { d.probePosition() }.getOrNull()
            if (pos == null) {
                val muet = d.lastReply.isBlank()
                trace.add(if (muet) tf("rotor_diag_mute_tries", d.lastTries.toString())
                          else tf("rotor_diag_garbled", d.lastReply))
                echec = if (muet) t("rotor_fail_mute") else tf("rotor_fail_garbled", d.lastReply)
                d.close(); continue
            }
            trace.add(tf("rotor_diag_ok",
                String.format(java.util.Locale.US, "%.0f", pos.azDeg),
                String.format(java.util.Locale.US, "%.0f", pos.elDeg)))
            gagnant = i; break
        }
        val ok = gagnant >= 0
        if (ok && gagnant != u.rotorUsbIndex) {
            // The port that answered is remembered.
            settings.rotorUsbIndex = gagnant
        }
        if (ok) d.identitePort(gagnant)?.first?.let { settings.rotorUsbCle = it }
        rotorDriver = if (ok) corrige(d) else null
        rotorSerial = if (ok) d else null
        change { copy(rotorConnected = ok,
            rotorDevices = refs.map { it.label }, rotorDiag = trace,
            rotorUsbIndex = if (ok) gagnant else u.rotorUsbIndex,
            rotorLastSent = d.lastSent, rotorLastReply = d.lastReply,
            rotorStatus = if (ok) tf("rotor_connected", refs[gagnant].label)
            else if (echec.isNotBlank()) echec
            else t("open_failed_usb")) }
        if (ok) { rotorWasFlipped = false; startRotorLoop() }
    }

    /**
     * Sends the hand-entered command, without satellite or tracking — the
     * honest way to test a whole chain: a pass comes when it wants and does
     * not wait for a cable to be sorted out.
     *
     * It stops tracking first: two masters commanding one mast a second apart
     * contradict each other and the mast shakes between them. The angle is in
     * **true** azimuth like everything displayed; conversion to the
     * controller's origin happens on the outgoing frame, as for park and
     * tracking.
     */
    fun rotorGotoManual() {
        portee.launch {
            rotorGuard("rotor_goto") {
                setRotorEnabled(false)
                val u = _etat.value
                val limits = rotorLimits(u)
                val aim = RotorMath.manual(u.rotorManualAz.toDouble(), u.rotorManualEl.toDouble(), limits)
                if (aim == null) {
                    change { copy(rotorOutOfRange = true, rotorStatus = t("rotor_manual_refused")) }
                    return@rotorGuard
                }
                if (!atteignable(aim, limits, u)) {
                    change { copy(rotorOutOfRange = true, rotorStatus = t("rotor_corr_hors_portee")) }
                    return@rotorGuard
                }
                launch {
                    val cmdAz = RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop)
                    val parti = rotorDriver?.moveTo(cmdAz, aim.elDeg) ?: false
                    rotorAt = RotorPos(aim.azDeg, aim.elDeg)
                    rotorDerniereCmd = if (parti) rotorAt else null
                    rotorCmd = rotorAt
                    rotorWasFlipped = false
                    change { copy(
                        rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg,
                        rotorOutOfRange = false,
                        rotorLastSent = rotorSerial?.lastSent ?: "",
                        rotorStatus = if (parti)
                            tf("rotor_manual_sent",
                                String.format(java.util.Locale.US, "%.0f", cmdAz),
                                String.format(java.util.Locale.US, "%.0f", aim.elDeg))
                        else t("rotor_send_failed")) }
                }
            }
        }
    }

    /**
     * A precise direction (hundredths of a degree), for a measuring raster:
     * tracking stopped, the rotor's limits applied — refused, never clamped,
     * nor below its lowest elevation. False when refused, not connected or
     * not sent; through the driver already open, nothing else opened.
     */
    suspend fun vers(azDeg: Double, elDeg: Double): Boolean {
        val d = rotorDriver ?: return false
        val u = _etat.value
        if (!u.rotorConnected || u.rotorAzOnly || elDeg < u.rotorMinEl) return false
        val limits = rotorLimits(u)
        val aim = RotorMath.manual(azDeg, elDeg, limits) ?: return false
        if (!atteignable(aim, limits, u)) return false
        if (u.rotorEnabled) setRotorEnabled(false)
        val cmdAz = RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop)
        val parti = runCatching { d.moveTo(cmdAz, aim.elDeg) }.getOrDefault(false)
        rotorAt = RotorPos(aim.azDeg, aim.elDeg)
        rotorDerniereCmd = if (parti) rotorAt else null
        rotorCmd = rotorAt
        rotorWasFlipped = false
        change { copy(rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorOutOfRange = false) }
        return parti
    }

    /**
     * One step more or less from where we think we are: the **read** position
     * when available, else the previous command. Handy for rough end-stop
     * setup: step ten degrees, look at the antenna, repeat.
     */
    fun rotorJog(dAz: Int, dEl: Int) {
        val u = _etat.value
        val baseAz = u.rotorActualAz ?: u.rotorManualAz.toDouble()
        val baseEl = u.rotorActualEl ?: u.rotorManualEl.toDouble()
        setRotorManualAz((baseAz.toInt() + dAz))
        setRotorManualEl((baseEl.toInt() + dEl))
        rotorGotoManual()
    }

    /**
     * A single query outside the loop, for when nothing is wired right yet:
     * shows the reply as it arrives — or its absence, which is an answer too.
     */
    fun rotorReadNow() {
        portee.launch {
            rotorGuard("rotor_read") {
                launch {
                    val d = rotorDriver
                    val pos = runCatching { d?.readPosition() }.getOrNull()
                    val ser = rotorSerial
                    change { copy(
                        rotorLastSent = ser?.lastSent ?: "",
                        rotorLastReply = ser?.lastReply ?: "",
                        rotorStatus = if (pos == null) t("rotor_diag_mute")
                        else tf("rotor_diag_ok",
                            String.format(java.util.Locale.US, "%.0f", pos.azDeg),
                            String.format(java.util.Locale.US, "%.0f", pos.elDeg))) }
                }
            }
        }
    }

    /** The mast's position, read now (the station check), or null. */
    suspend fun lisPosition(): RotorPos? = runCatching { rotorDriver?.readPosition() }.getOrNull()

    fun disconnectRotor() {
        rotorLoopJob?.cancel(); rotorLoopJob = null
        runCatching { rotorDriver?.close() }
        rotorDriver = null
        rotorCorrige = null
        rotorSimLink = null
        rotorSerial = null
        // A disconnected mast has no position to hold: forget the last read,
        // or it would outlive the disconnection by four seconds.
        rotorLu = null; rotorLuMs = 0L
        rotorDerniereCmd = null; rotorBougeRef = null; rotorBougeMs = 0L
        change { copy(rotorConnected = false, rotorStatus = t("rotor_offline"),
            rotorTargetAz = null, rotorTargetEl = null,
            rotorActualAz = null, rotorActualEl = null, rotorOutOfRange = false,
            rotorAimAz = null, rotorAimEl = null, rotorBrutAz = null, rotorBrutEl = null) }
    }

    /** [d] seen through the software correction, in real degrees. */
    private fun corrige(d: RotorDriver): RotorCorrige =
        RotorCorrige(d, { correction }, { bornesControleur(_etat.value) }).also { rotorCorrige = it }

    /** The controller's range on the wire, for the mast's settings. */
    private fun bornesControleur(u: EtatRotor) = BornesControleur.de(rotorLimits(u), u.rotorAzFromStop)

    /**
     * Can the real direction [aim] be sent without the controller being told
     * more than its range? Always with no correction. The angle goes through
     * the controller's origin first ([RotorMath.commandAz]), as on the wire.
     */
    private fun atteignable(aim: RotorMath.Aim, limits: RotorMath.Limits, u: EtatRotor): Boolean {
        if (correction.estIdentite) return true
        val c = rotorCorrige ?: return true
        return c.atteignable(RotorPos(RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop), aim.elDeg))
    }

    /**
     * Immediate stop. It also stops tracking: a stop followed by a command a
     * second later is not a stop, it is a pause.
     */
    fun rotorStopNow() {
        portee.launch {
            rotorGuard("rotor_stop") {
                setRotorEnabled(false)
                launch { rotorDriver?.stop(); rotorDerniereCmd = null }
            }
        }
    }

    fun rotorParkNow() {
        portee.launch {
            rotorGuard("rotor_park") {
                setRotorEnabled(false)
                val u = _etat.value
                val aim = RotorMath.park(u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), rotorLimits(u))
                    ?.takeIf { atteignable(it, rotorLimits(u), u) }
                if (aim == null) {
                    change { copy(rotorOutOfRange = true) }
                } else {
                    launch {
                        val parti = rotorDriver?.moveTo(
                            RotorMath.commandAz(aim.azDeg, rotorLimits(u), u.rotorAzFromStop),
                            aim.elDeg) ?: false
                        rotorAt = RotorPos(aim.azDeg, aim.elDeg)
                        rotorDerniereCmd = if (parti) rotorAt else null
                        rotorCmd = rotorAt
                        rotorWasFlipped = false
                        change { copy(rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorOutOfRange = false) }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Calibration: the controller's own (O, F, O2, F2) and SatMe's correction.
    // ------------------------------------------------------------------

    /** What a calibration command gave: its outcome (an [Gs232Rotor.Issue] name, or "") and a sentence. */
    data class ResultatEtalonnage(val ok: Boolean, val issue: String, val message: String)

    /** Adds lines to the calibration log, the last forty kept. */
    private fun journalEtal(vararg lignes: String) =
        change { copy(rotorEtalJournal = (rotorEtalJournal + lignes).takeLast(40)) }

    /**
     * The software correction of one axis ("AZ" or "EL"). Identity clears it.
     * Stored as typed in the controller's numbers; nothing else changes: the
     * limits stay real degrees.
     */
    fun setRotorCorrection(axe: String, c: CorrectionAxe) {
        if (axe == "AZ") settings.rotorCorrAz = c.ecrit() else settings.rotorCorrEl = c.ecrit()
        correction = CorrectionRotor(CorrectionAxe.lit(settings.rotorCorrAz), CorrectionAxe.lit(settings.rotorCorrEl))
        rotorPlan = null; rotorPlanKey = null
        change { copy(rotorCorrAz = settings.rotorCorrAz, rotorCorrEl = settings.rotorCorrEl) }
    }

    /** Same from its written form (`read:real;…`); false, nothing changed, when it cannot be a correction. */
    fun setRotorCorrection(axe: String, texte: String): Boolean {
        val c = CorrectionAxe.litOuNull(texte) ?: return false
        setRotorCorrection(axe, c)
        return true
    }

    /**
     * The correction of [axe] from the verification table: each pair is the
     * controller's reading (its own numbers) and what was measured on the
     * mast (compass: true azimuth; inclinometer: 0 to 180, past the zenith
     * above 90). A compass reads 10 where an overlap mast reads 370: the
     * measure is written on the turn of the reading, then in the
     * controller's origin, as on the wire. Null, nothing changed, when the
     * measures cannot make a correction.
     */
    fun ajusteCorrection(axe: String, releves: List<Pair<Double, Double>>, parPoints: Boolean): CorrectionAxe? {
        val u = _etat.value
        val limits = rotorLimits(u)
        val anneau = bornesControleur(u)
        val pts = if (axe == "AZ") releves.map { (lu0, mesure) ->
            val lu = anneau.surAnneau(lu0)
            val pres = RotorMath.trueAz(lu, limits, u.rotorAzFromStop)
            lu to RotorMath.commandAz(RotorMath.unwrapFree(mesure, pres), limits, u.rotorAzFromStop)
        } else releves
        val c = CorrectionAxe.ajuste(pts, parPoints) ?: return null
        setRotorCorrection(axe, c)
        return c
    }

    /**
     * The assistant opens: tracking stops (and the mast with it), the log
     * starts afresh. What tracking was is given back by [etalonnageFerme].
     */
    fun etalonnageOuvre() {
        val u = _etat.value
        if (u.rotorEtalonnage) return
        suiviAvantEtalonnage = u.rotorEnabled
        if (u.rotorEnabled) {
            settings.rotorEnabled = false
            change { copy(rotorEnabled = false) }
            portee.launch { runCatching { rotorDriver?.stop() }; rotorDerniereCmd = null }
        }
        change { copy(rotorEtalonnage = true, rotorEtalJournal = emptyList(), rotorStatus = t("rotor_etal_ouvert")) }
    }

    /** The assistant closes: tracking as it was before. */
    fun etalonnageFerme() {
        if (!_etat.value.rotorEtalonnage) return
        change { copy(rotorEtalonnage = false) }
        val reprendre = suiviAvantEtalonnage
        suiviAvantEtalonnage = false
        if (reprendre) setRotorEnabled(true)
    }

    /**
     * One of the controller's own calibrations, with its handshake, the line
     * held. Only with the assistant open (tracking off) and over GS-232: rotctld
     * has no such command — there only SatMe's correction applies.
     */
    suspend fun etalonnageCommande(e: Gs232Codec.Etalonnage): ResultatEtalonnage {
        val u = _etat.value
        fun non(m: String) = ResultatEtalonnage(false, "", m).also { journalEtal(m) }
        if (!u.rotorEtalonnage) return non(t("rotor_etal_ferme_dabord"))
        if (!u.rotorConnected) return non(t("rotor_etal_non_connecte"))
        val ser = rotorSerial
        if (ser == null || (u.rotorLink == "ROTCTLD" && !u.rotorSim)) return non(t("rotor_etal_rotctld"))
        val r = liaison.withLock { runCatching { ser.etalonne(e) }.getOrNull() }
            ?: return non(tf("rotor_etal_muet", e.commande))
        journalEtal("→ " + e.commande, *r.lignes.map { "← $it" }.toTypedArray())
        val m = when (r.issue) {
            Gs232Rotor.Issue.ECRIT -> tf("rotor_etal_ecrit", e.commande)
            Gs232Rotor.Issue.INCONNU -> tf("rotor_etal_inconnu", e.commande)
            Gs232Rotor.Issue.MUET -> tf("rotor_etal_muet", e.commande)
            Gs232Rotor.Issue.SANS_CONFIRMATION -> tf("rotor_etal_sans_confirmation", e.commande)
        }
        journalEtal(m)
        change { copy(rotorLastSent = ser.lastSent, rotorLastReply = ser.lastReply, rotorStatus = m) }
        return ResultatEtalonnage(r.issue == Gs232Rotor.Issue.ECRIT, r.issue.name, m)
    }

    /**
     * A test position of the verification, sent only on the operator's
     * click: [az] / [el] in real degrees (true azimuth), the other axis kept
     * where the mast is. Through the correction and within the limits —
     * refused, never clamped. Null when sent, else why not.
     */
    suspend fun etalonnageVa(az: Double?, el: Double?): String? {
        val u = _etat.value
        if (!u.rotorEtalonnage) return t("rotor_etal_ferme_dabord").also { journalEtal(it) }
        val d = rotorDriver
        if (!u.rotorConnected || d == null) return t("rotor_etal_non_connecte").also { journalEtal(it) }
        val limits = rotorLimits(u)
        val azV = az ?: u.rotorActualAz ?: rotorAt.azDeg
        val elV = if (u.rotorAzOnly) 0.0 else el ?: u.rotorActualEl ?: rotorAt.elDeg
        val aim = RotorMath.manual(azV, elV, limits)
        if (aim == null || !atteignable(aim, limits, u)) {
            val m = if (aim == null) t("rotor_manual_refused") else t("rotor_corr_hors_portee")
            journalEtal(m); change { copy(rotorStatus = m) }
            return m
        }
        val cmdAz = RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop)
        val parti = liaison.withLock { runCatching { d.moveTo(cmdAz, aim.elDeg) }.getOrDefault(false) }
        rotorAt = RotorPos(aim.azDeg, aim.elDeg)
        rotorDerniereCmd = if (parti) rotorAt else null
        rotorCmd = rotorAt
        rotorWasFlipped = false
        val envoye = rotorSerial?.lastSent ?: ""
        val m = if (parti) tf("rotor_etal_va", envoye.ifBlank { "→" }) else t("rotor_send_failed")
        journalEtal(m)
        change { copy(rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorOutOfRange = false,
            rotorLastSent = envoye, rotorStatus = m) }
        return if (parti) null else m
    }

    /**
     * The real range the controller can be sent to, through the correction:
     * azimuth (true north, unwrapped) and elevation, low and high. A real top
     * of 160 on a controller that says 180 there shows here.
     */
    fun porteeReelle(): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
        val u = _etat.value
        val limits = rotorLimits(u)
        val b = bornesControleur(u)
        val bas = correction.versReel(RotorPos(b.azMin, b.elMin))
        val haut = correction.versReel(RotorPos(b.azMax, b.elMax))
        val azBas = maxOf(limits.azMinReach, RotorMath.trueAz(bas.azDeg, limits, u.rotorAzFromStop))
        val azHaut = minOf(limits.azMaxReach, RotorMath.trueAz(haut.azDeg, limits, u.rotorAzFromStop))
        return azBas..azHaut to maxOf(0.0, bas.elDeg)..minOf(limits.elMaxDeg, haut.elDeg)
    }

    private fun rotorLimits(u: EtatRotor) = RotorMath.Limits(
        azMaxDeg = u.rotorMaxAz.toDouble(),
        // Over the top needs a rotator reaching 180°: said by its travel (180) or the flip box.
        elMaxDeg = if (u.rotorFlip || u.rotorMaxEl >= 180) 180.0 else u.rotorMaxEl.toDouble(),
        deadbandDeg = u.rotorDeadband.toDouble(),
        azStopDeg = if (u.rotorAzStop == "SOUTH") 180.0 else 0.0)

    /**
     * The pass plan: chosen once, held to the end. Recomputed only when pass,
     * end stop, travel, tolerance or zones change; recomputing every second
     * would give a mast changing its mind mid-pass, with a minute of rotation
     * to reach a place the satellite has already left.
     *
     * With the pass's times ([traceTemps]) the whole pass is decided here
     * ([RotorPlanPassage]): side of the stop, upright or over the top, the
     * one flip or unwind and its moment. Without them, only the side
     * ([RotorMath.plan]) and the unwind is decided as it comes ([RotorDeroule]).
     */
    private fun ensureRotorPlan(u: EtatRotor, limits: RotorMath.Limits): RotorMath.Plan? {
        val track = monde.arcDuPassage()
        if (track.isEmpty()) {
            rotorPlan = null; rotorPlanKey = null; rotorPlanPassage = null; rotorChemin = emptyList()
            val e = _etat.value
            if (e.rotorTrajet.isNotEmpty() || e.rotorPlan != null || e.rotorChemin.isNotEmpty())
                change { copy(rotorTrajet = emptyList(), rotorChemin = emptyList(), rotorPlan = null, rotorDerouleAt = null) }
            return null
        }
        val tt = traceTemps
        val key = "${track.size}|${track.first()}|${track.last()}|" +
            "${limits.azStopDeg}|${limits.azMaxDeg}|${limits.elMaxDeg}|${u.rotorMaxError}|${u.rotorZones}|" +
            "${tt.size}|${tt.firstOrNull()?.first}"
        if (key == rotorPlanKey) return rotorPlan
        // At equal coverage, the side nearest the mast: a short pass costs no turn.
        val p = RotorMath.plan(track, limits, startNearDeg = matDeroule(limits), toleranceDeg = u.rotorMaxError.toDouble())
        rotorPlanKey = key
        rotorPlan = p
        if (p != null) {
            rotorCmd = RotorPos(RotorMath.clampAz(p.startAzDeg, limits), rotorCmd.elDeg)
            rotorWasFlipped = false
        }
        rotorChemin = emptyList(); rotorCheminARefaire = false; rotorPlanPassage = null
        if (tt.size >= 2) planifieChemin(u, limits, depuis = null)
        if (rotorPlanPassage == null)
            change { copy(rotorCoverage = p?.coverage, rotorDerouleAt = null, rotorChemin = emptyList(),
                rotorTrajet = emptyList(), rotorPlan = null) }
        return p
    }

    /**
     * The mast's azimuth on its ring: the reading is a sky azimuth (147 on a
     * south-stop mast is its 507), written on the turn nearest the last command.
     */
    private fun matDeroule(limits: RotorMath.Limits): Double {
        val ref = rotorDerniereCmd?.azDeg ?: rotorCmd.azDeg
        val a = rotorAt.azDeg
        return (-2..2).map { a + it * 360.0 }
            .filter { it >= limits.azMinReach - 5.0 && it <= limits.azMaxReach + 5.0 }
            .minByOrNull { kotlin.math.abs(it - ref) } ?: RotorMath.clampAz(a, limits)
    }

    /** The rotator's speed for planning: as measured, never below 2°/s (a bad measure must not forbid every flip). */
    private fun vitessePlan(): Double = maxOf(2.0, settings.rotorVitesse.toDouble())

    /**
     * Mast tracking loop: one command per second, no more. A rotor gains
     * nothing from faster commands — it turns at ~6°/s and every start wears a
     * relay.
     */
    private fun startRotorLoop() {
        rotorLoopJob?.cancel()
        if (!boucleAuto) return
        rotorLoopJob = portee.launch {
            while (_etat.value.rotorConnected) {
                runCatching { rotorTick() }
                delay(1000)
            }
        }
    }

    /** Two azimuths apart on the sky, whatever turn of the ring each is written on. */
    private fun ecartAz(a: Double, b: Double): Double = kotlin.math.abs(RotorMath.norm360(a - b + 180.0) - 180.0)

    /** One round of the loop (public for the tests, which drive the clock): the line held throughout. */
    suspend fun rotorTick() = liaison.withLock { rotorTickInner() }

    private suspend fun rotorTickInner() {
        val d = rotorDriver ?: return
        // The simulated mast only moves when advanced.
        rotorSimLink?.advance(1000)
        val u = _etat.value
        val limits = rotorLimits(u)
        // Ask twice if the first query got no answer: an emulator busy driving
        // two motors skips a reply now and then; asking again 300 ms later
        // costs two bytes and recovers nearly all of those gaps.
        var brut = runCatching { d.readPosition() }.getOrNull()
        if (brut == null) {
            delay(300)
            brut = runCatching { d.readPosition() }.getOrNull()
        }
        // The controller answers in its own origin; from here on it is true
        // azimuth until the next outgoing frame.
        val frais = brut?.let { RotorPos(RotorMath.trueAz(it.azDeg, limits, u.rotorAzFromStop), it.elDeg) }
        val maintenant = horloge()
        // The rotator's speed, measured while it turns (simulated mast excepted): the time a turn takes.
        val avantLu = rotorLu
        // Only while it runs flat out, far from its command: tracking moves in short steps,
        // starts and stops included, and measured 1.3°/s on a 6.5°/s rotator (21/10).
        val cmdLoin = rotorDerniereCmd
        val aFond = cmdLoin != null && frais != null && avantLu != null &&
            ecartAz(cmdLoin.azDeg, frais.azDeg) >= 20.0 && ecartAz(cmdLoin.azDeg, avantLu.azDeg) >= 20.0
        if (frais != null && avantLu != null && !u.rotorSim && aFond)
            RotorDeroule.mesureVitesse(avantLu.azDeg, rotorLuMs, frais.azDeg, maintenant,
                settings.rotorVitesse.toDouble())?.let { settings.rotorVitesse = it.toFloat() }
        if (frais != null) { rotorLu = frais; rotorLuMs = maintenant }
        // Whether the mast moves: by more than the reading's own wander.
        if (frais != null) {
            val r = rotorBougeRef
            if (r == null || kotlin.math.abs(frais.azDeg - r.azDeg) >= RotorPas.BOUGE_DEG ||
                kotlin.math.abs(frais.elDeg - r.elDeg) >= RotorPas.BOUGE_DEG) {
                rotorBougeRef = frais; rotorBougeMs = maintenant
            }
        }
        // If it still stays silent, hold its last answer a few seconds rather
        // than make the compass flicker between mast and satellite. See
        // RotorTenue: holding serves the display, never the command.
        val lu = RotorTenue.montrer(frais, rotorLu, rotorLuMs, maintenant)
        if (frais != null) rotorAt = frais
        // A connected mast holds the antenna: the compass shows it, not the
        // phone. Only the aim source changes — same colours, trace, error.
        val visee = lu?.let { RotorMath.antennaAim(it) }
        val ser = rotorSerial
        val brutLu = if (brut != null) rotorCorrige?.dernierBrut else null
        change { copy(rotorActualAz = lu?.azDeg, rotorActualEl = lu?.elDeg,
            rotorBrutAz = brutLu?.azDeg, rotorBrutEl = brutLu?.elDeg,
            rotorEcretageDeg = rotorCorrige?.ecretageDeg ?: 0.0,
            rotorAimAz = visee?.azDeg,
            rotorAimEl = if (rotorAzOnly) null else visee?.elDeg,
            rotorLastSent = ser?.lastSent ?: rotorLastSent,
            rotorLastReply = ser?.lastReply ?: rotorLastReply) }
        if (!u.rotorEnabled) return
        val pos = monde.satellite()
        if (pos == null) {
            change { copy(rotorTargetAz = null, rotorTargetEl = null, rotorStatus = t("rotor_no_target")) }
            return
        }
        val plan = ensureRotorPlan(u, limits)
        // Below minimum elevation the satellite is behind the hill: park
        // rather than follow a point we cannot hear…
        val garage = pos.elevationDeg < u.rotorMinEl.toDouble()
        // …except in the last minutes before AOS: better wait where it will
        // rise than park and leave again. The waiting point is the start of
        // the mast turn already chosen for this pass, end stop included, so
        // nothing is left to unwind at AOS.
        val avance = garage && plan != null &&
            RotorMath.prePositionDue(horloge(), monde.aosDuPassage().takeIf { it > 0L }, u.rotorPreAos)
        if (avance != _etat.value.rotorPrePositioning) change { copy(rotorPrePositioning = avance) }
        val depart = rotorPlanPassage?.points?.firstOrNull()
        val aim = if (avance && depart != null)
            // Waiting where the planned path starts — lying back already when the pass starts over the top.
            RotorMath.park(depart.azDeg, if (depart.elDeg > 90.0) depart.elDeg else maxOf(0.0, u.rotorMinEl.toDouble()), limits)
        else if (avance)
            RotorMath.park(RotorMath.clampAz(plan!!.startAzDeg, limits), maxOf(0.0, u.rotorMinEl.toDouble()), limits)
        else if (garage)
            RotorMath.park(u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), limits)
        else if (rotorPlanPassage != null) viseeSurPlan(u, limits, pos)
        else viseeAvecDeroule(u, pos, limits)
        if (garage && (_etat.value.rotorDeroule.isNotBlank() || _etat.value.rotorDerouleAlerte))
            change { copy(rotorDeroule = "", rotorDerouleAlerte = false) }
        if (aim == null) {
            // An impossible park stays impossible: do not clamp a position the
            // operator chose, tell him.
            change { copy(rotorOutOfRange = true, rotorTargetAz = null, rotorTargetEl = null) }
            return
        }
        // Beyond the end stop the mast does not unwind: it waits as close as
        // possible and the error is shown in degrees. A mast pointing elsewhere
        // silently is worse than one that did not move.
        change { copy(rotorOutOfRange = false, rotorErrorDeg = aim.errorDeg,
            rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorFlipped = aim.flipped,
            rotorCmdAz = aim.azDeg) }
        if (!garage) rotorCmd = RotorPos(aim.azDeg, aim.elDeg)
        // How fast the aim moves (°/s, smoothed): the mast goes that way ahead of it.
        val ici = horloge()
        val avant = rotorVisePrec
        val dt = (ici - rotorVisePrecMs) / 1000.0
        if (avant != null && dt in 0.3..5.0) {
            rotorVitesseAz = 0.7 * rotorVitesseAz + 0.3 * (aim.azDeg - avant.azDeg) / dt
            rotorVitesseEl = 0.7 * rotorVitesseEl + 0.3 * (aim.elDeg - avant.elDeg) / dt
        } else if (avant == null || dt > 5.0) { rotorVitesseAz = 0.0; rotorVitesseEl = 0.0 }
        rotorVisePrec = RotorPos(aim.azDeg, aim.elDeg)
        rotorVisePrecMs = ici
        // Parked: no lead (the aim stands still anyway).
        val c = RotorPas.commande(
            RotorPos(aim.azDeg, aim.elDeg), rotorAt,
            if (garage && !avance) 0.0 else rotorVitesseAz, if (garage && !avance) 0.0 else rotorVitesseEl,
            u.rotorDeadband.toDouble(), limits, ici, rotorDernierEnvoiMs, u.rotorAzOnly,
            derniere = rotorDerniereCmd,
            matImmobileMs = ici - maxOf(rotorBougeMs, rotorDernierEnvoiMs)) ?: return
        rotorWasFlipped = aim.flipped
        val cmd = RotorMath.commandAz(c.azDeg, limits, u.rotorAzFromStop)
        // `lu` may be a held position three seconds old — not a reading.
        // Without a real read, assume the mast went where it was told.
        if (d.moveTo(cmd, c.elDeg)) {
            rotorDernierEnvoiMs = ici
            rotorDerniereCmd = c
            if (frais == null) rotorAt = c
        }
    }

    /**
     * Following the satellite, and going round to the other side of the end
     * stop when it is worth it (see [RotorDeroule]): beyond the dead zone, or
     * before an SSTV picture due beyond it; never while transmitting, never
     * into a picture. The side chosen is carried by [rotorCmd]: returning the
     * other side's aim makes the turn.
     */
    private fun viseeAvecDeroule(u: EtatRotor, pos: SatPosition, limits: RotorMath.Limits): RotorMath.Aim {
        val maintenant = horloge()
        val v = RotorDeroule.vise(pos.azimuthDeg, pos.elevationDeg, rotorCmd, limits, rotorWasFlipped,
            u.rotorMaxError.toDouble(), maintenant, imagesSstv(maintenant), monde.enEmission(),
            settings.rotorVitesse.toDouble()) { positionPrevue(it) }
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        fun h(ms: Long?) = heure.format(java.util.Date(ms ?: maintenant))
        val m = when (v.etat) {
            RotorDeroule.Etat.SUIT -> ""
            RotorDeroule.Etat.TOUR -> tf("rotor_deroule_tour", v.dureeS)
            RotorDeroule.Etat.TOUR_ANTICIPE -> tf("rotor_deroule_anticipe", h(v.imageMs), v.dureeS)
            RotorDeroule.Etat.ATTEND_EMISSION -> t("rotor_deroule_emission")
            RotorDeroule.Etat.ATTEND_IMAGE -> tf("rotor_deroule_image", h(v.jusquaMs))
            RotorDeroule.Etat.ATTEND_IMAGE_PROCHE -> tf("rotor_deroule_image_proche", h(v.jusquaMs))
        }
        val alerte = v.etat == RotorDeroule.Etat.ATTEND_EMISSION
        if (_etat.value.rotorDeroule != m || _etat.value.rotorDerouleAlerte != alerte)
            change { copy(rotorDeroule = m, rotorDerouleAlerte = alerte) }
        return v.aim
    }

    /** A mast command (unwrapped azimuth, elevation up to 180) as the sky point it aims at. */
    private fun versCiel(azDeg: Double, elDeg: Double): Pair<Double, Double> =
        if (elDeg > 90.0) RotorMath.norm360(azDeg + 180.0) to 180.0 - elDeg
        else RotorMath.norm360(azDeg) to elDeg

    /**
     * Plans the mast over the rest of the pass ([RotorPlanPassage]), from now
     * (or the whole pass before AOS), with the expected SSTV pictures; from
     * [depuis] (the mast, unwrapped) when redone during the pass. Shown on the
     * dial and the page.
     */
    private fun planifieChemin(u: EtatRotor, limits: RotorMath.Limits, depuis: RotorPos?) {
        val maintenant = horloge()
        val tt = traceTemps
        val enCours = tt.isNotEmpty() && maintenant > tt.first().first + 5_000L
        val trace = if (enCours) tt.filter { it.first >= maintenant - 5_000L } else tt
        val images = imagesSstv(maintenant)
        rotorCheminImages = images
        val mat = depuis ?: RotorPos(matDeroule(limits), rotorAt.elDeg)
        val plan = RotorPlanPassage.plan(trace, limits, vitessePlan(), u.rotorMaxError.toDouble(),
            RotorPlanPassage.litZones(u.rotorZones), depuis = mat, imposeDepart = enCours, images = images)
        rotorPlanPassage = plan
        rotorChemin = plan?.points ?: emptyList()
        if (plan == null) return
        val ciel = plan.points.map { p -> versCiel(p.azDeg, p.elDeg) }
        val pire = plan.points.filter { !it.tour }.maxByOrNull { it.erreurDeg }
        val ev = plan.evenement
        val resume = resume(plan, tt, u)
        change { copy(
            rotorCoverage = plan.couverture,
            rotorChemin = ciel, rotorTrajet = ciel,
            rotorEcartMax = pire?.erreurDeg, rotorEcartMaxAt = pire?.tMs,
            rotorDerouleAt = ev?.debutMs, rotorTourS = ev?.dureeS ?: rotorTourS,
            rotorPlan = resume) }
    }

    /** The plan as the screens show it, with its banner line. */
    private fun resume(plan: RotorPlanPassage.Plan, tt: List<Triple<Long, Double, Double>>, u: EtatRotor): ResumePlanRotor {
        val ev = plan.evenement
        val heure = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val texte = when {
            ev != null && ev.retournement -> tf("rotor_plan_flip", heure.format(java.util.Date(ev.debutMs)), ev.dureeS)
            ev != null -> tf("rotor_plan_deroule", heure.format(java.util.Date(ev.debutMs)), ev.dureeS)
            plan.retourne -> t("rotor_plan_retourne")
            else -> tf("rotor_cadran_pas_de_tour", u.rotorMaxError)
        }
        return ResumePlanRotor(
            aosMs = tt.first().first, losMs = tt.last().first, sat = tt,
            mat = plan.points.map { p -> val c = versCiel(p.azDeg, p.elDeg); PointPlanRotor(p.tMs, c.first, c.second, p.elDeg > 90.0, p.tour) },
            couverture = plan.couverture, courseAzDeg = RotorPlanPassage.courseAz(plan.points),
            retourne = plan.retourne,
            changement = if (ev == null) "" else if (ev.retournement) "flip" else "deroule",
            changementMs = ev?.debutMs, changementS = ev?.dureeS ?: 0, texte = texte)
    }

    private var apercuCle: String? = null
    private var apercu: ResumePlanRotor? = null

    /**
     * The plan of the pass known, for the screens: the one in force when the
     * mast follows this pass, else the one that would be made now with the
     * settings as they are (no mast needed: a preview). Null without a pass.
     */
    fun apercuPlan(): ResumePlanRotor? {
        val tt = traceTemps
        if (tt.size < 2) return null
        val u = _etat.value
        u.rotorPlan?.takeIf { u.rotorConnected && u.rotorEnabled && it.aosMs == tt.first().first }?.let { return it }
        val l = rotorLimits(u)
        val cle = "${tt.first().first}|${tt.size}|${l.azStopDeg}|${l.azMaxDeg}|${l.elMaxDeg}|${u.rotorMaxError}|${u.rotorZones}"
        if (cle == apercuCle) return apercu
        val plan = RotorPlanPassage.plan(tt, l, vitessePlan(), u.rotorMaxError.toDouble(), RotorPlanPassage.litZones(u.rotorZones))
        apercuCle = cle
        apercu = plan?.let { resume(it, tt, u) }
        return apercu
    }

    /**
     * Following the plan: the satellite where it is, on the branch the plan
     * gives for this time (a little ahead, the rotator lags) — the flip and
     * the side come from the clock, so nothing flaps. A change (flip, unwind)
     * is held while transmitting or while a picture comes in — the mast stays
     * — and the plan is made again from where it is once that is over. A new
     * picture received changes the pictures to expect: planned again too.
     */
    private fun viseeSurPlan(u: EtatRotor, limits: RotorMath.Limits, pos: SatPosition): RotorMath.Aim {
        val maintenant = horloge()
        val images = imagesSstv(maintenant)
        val imageEnCours = monde.imageEnCours(maintenant) != null
        val emission = monde.enEmission()
        fun dit(m: String, alerte: Boolean = false) {
            if (_etat.value.rotorDeroule != m || _etat.value.rotorDerouleAlerte != alerte)
                change { copy(rotorDeroule = m, rotorDerouleAlerte = alerte) }
        }
        val plan0 = rotorPlanPassage ?: return viseeAvecDeroule(u, pos, limits)
        val dans = { p: RotorPlanPassage.Plan -> p.evenement?.let { maintenant + 2_000L in it.debutMs..it.finMs } == true }
        if (dans(plan0) && (emission || imageEnCours)) {
            rotorCheminARefaire = true
            if (emission) dit(t("rotor_deroule_emission"), alerte = true)
            else dit(tf("rotor_deroule_image", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(images.firstOrNull { maintenant in it }?.last ?: maintenant))))
            return RotorMath.Aim(rotorCmd.azDeg, rotorCmd.elDeg, rotorCmd.elDeg > 90.0)
        }
        // Planned again from the mast: after a held change, or when the pictures to expect changed.
        val imagesChangees = images.map { it.first / 10_000 } != rotorCheminImages.map { it.first / 10_000 }
        if ((rotorCheminARefaire && !emission && !imageEnCours) || (imagesChangees && !dans(plan0))) {
            rotorCheminARefaire = false
            planifieChemin(u, limits, depuis = rotorCmd)
        }
        val plan = rotorPlanPassage ?: return viseeAvecDeroule(u, pos, limits)
        val ev = plan.evenement
        dit(if (ev != null && dans(plan))
            (if (ev.retournement) tf("rotor_plan_flip_cours", ev.dureeS) else tf("rotor_deroule_tour", ev.dureeS))
            else "")
        return RotorPlanPassage.vise(plan, maintenant + 2_000L, pos.azimuthDeg, pos.elevationDeg, limits)
    }

    /** Where the satellite will be at [tMs] on the tracked pass (az, el), null outside it. */
    private fun positionPrevue(tMs: Long): Pair<Double, Double>? {
        val l = traceTemps
        if (l.size < 2 || tMs < l.first().first || tMs > l.last().first) return null
        val i = l.indexOfFirst { it.first >= tMs }.coerceAtLeast(1)
        val a = l[i - 1]; val b = l[i]
        val f = (tMs - a.first).toDouble() / (b.first - a.first).coerceAtLeast(1)
        var daz = b.second - a.second
        if (daz > 180) daz -= 360.0; if (daz < -180) daz += 360.0
        return RotorMath.norm360(a.second + daz * f) to (a.third + (b.third - a.third) * f)
    }

    /**
     * The SSTV pictures that matter to the mast: the one being received now
     * (from the decoder), the ones of the last half hour on this satellite
     * (from the gallery, read every 20 s), and those to come at their pace.
     */
    private fun imagesSstv(maintenant: Long): List<LongRange> {
        if (maintenant - rotorImagesMs > 20_000L) {
            rotorImagesMs = maintenant
            rotorImages = runCatching { monde.imagesRecues(monde.nomSatellite(), maintenant - 30 * 60_000L) }
                .getOrDefault(emptyList())
        }
        val enCours = monde.imageEnCours(maintenant)
        val recues = (rotorImages + listOfNotNull(enCours)).sortedBy { it.first }
        val los = traceTemps.lastOrNull()?.first ?: (maintenant + 15 * 60_000L)
        return listOfNotNull(enCours) + RotorDeroule.imagesAVenir(recues, maintenant, los)
    }
}
