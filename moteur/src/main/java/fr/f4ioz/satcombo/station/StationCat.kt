/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.cat.BandPlan
import fr.f4ioz.satcombo.cat.CatDecode
import fr.f4ioz.satcombo.cat.DispositionSat
import fr.f4ioz.satcombo.cat.ModePoste
import fr.f4ioz.satcombo.cat.PosteCat
import fr.f4ioz.satcombo.cat.RxArbiter
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.ModeRadio
import fr.f4ioz.satcombo.domain.MoletteTx
import fr.f4ioz.satcombo.domain.SuiviMontee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Doppler loop needs from the rest of the station: the satellite
 * and the transponder chosen, the operator's settings (shifts, mode, hold,
 * tone), the rest frequency (shared with the screen: the operator moves it,
 * so does the loop), and the converters between sky and rig.
 */
interface MondeCat {
    /** The rig is connected and answers. */
    fun connecte(): Boolean
    /** The rig to drive now (an Icom on CI-V, or a pair). */
    fun poste(): PosteCat
    /** Transmit only (a pair whose receive side is the SDR dongle): nothing to read back. */
    fun emetSeul(): Boolean
    /** An APRS frame is going out: no frequency write for that second or two. */
    fun aprsEnEmission(): Boolean
    fun satellite(): SatPosition?
    fun catnum(): Int?
    fun transpondeur(): Transmitter?
    /** Drive the rig below the horizon too (a test on the bench). */
    fun sousHorizonAussi(): Boolean
    fun calibrationHz(): Long
    fun decalageTxHz(): Long
    /** Change the TX shift (the TX knob acting as the shift control). */
    fun poseDecalageTx(hz: Long)
    /** The RX offset of the operating mode (voice or CW). */
    fun decalageRxHz(): Long
    fun modeCw(): Boolean
    fun maintienDoppler(): Boolean
    fun dopplerRx(): Boolean
    fun moletteTxDecalage(): Boolean
    fun txSuitVite(): Boolean
    fun modelePoste(): String
    fun inversionForcee(): Boolean?
    fun ctcssChoisi(): Int
    fun ctcssAuto(): Boolean
    /** The rest frequency (sky, Hz), null when not chosen. */
    fun repos(): Long?
    fun poseRepos(hz: Long)
    /** Sky → rig (receive), rig → sky (receive), sky → rig (transmit), rig → sky (transmit). */
    fun posteRx(satHz: Long): Long
    fun satDepuisPoste(posteHz: Long): Long
    fun posteTx(satHz: Long): Long
    fun satDepuisPosteTx(posteHz: Long): Long
}

/** What the loop shows: the rig's frequencies (sky), who drives the RX dial, its mode read back. */
data class EtatBoucleCat(
    val descenteHz: Long? = null,
    val monteeHz: Long? = null,
    val rxPilote: Boolean = false,
    val mode: String? = null,
    val modeEcart: Boolean = false
)

/**
 * The CAT Doppler loop: sets the rig up for the satellite, then each round
 * (~100 ms) works out both frequencies and writes them when they moved —
 * letting go of the dial while the operator turns it. What used to be the
 * phone's ViewModel's `catTick`, shared now with the PC.
 */
class StationCat(private val monde: MondeCat, private val horloge: () -> Long = System::currentTimeMillis) {

    private val _etat = MutableStateFlow(EtatBoucleCat())
    val etat: StateFlow<EtatBoucleCat> = _etat.asStateFlow()
    private fun change(f: EtatBoucleCat.() -> EtatBoucleCat) = _etat.update { it.f() }

    // CAT session state (set up once when SAT mode armed for a transmitter).
    private var catArmedFor: Int? = null      // catnum currently armed
    private var catArmedTxDesc: String? = null
    private var lastSentDl = 0L
    private var lastSentUl = 0L
    private var catLayout: DispositionSat = DispositionSat.CROSS_BAND

    // Who holds the RX VFO: the operator, or us.
    private val rxArbiter = RxArbiter()

    /**
     * Same arbitration for FM, with a much coarser hand. 20 Hz recognises a
     * gesture on a linear transponder; in FM the rig rounds what we write to
     * its tuning step, and that rounding, read back, would look like an
     * operator gesture — control released every 100 ms with nobody touching
     * anything. A real FM channel change is at least 5 kHz; 1.5 kHz is wide
     * for one and out of reach for the other.
     */
    private val fmArbiter = RxArbiter(moveHz = 1_500L)

    /**
     * TX knob arbiter. Same class as RX on purpose: the problem is identical —
     * the rig does not say *who* turned the knob, and what we read after
     * writing looks exactly like a gesture. A separate arbiter would drift.
     */
    private val txArbiter = RxArbiter()

    /**
     * A TX knob gesture was seen and not yet absorbed. Without this flag,
     * absorption would fire on every round while the arbiter holds control —
     * i.e. always — and every rig rounding would drift into the shift.
     */
    @Volatile private var mainSurMoletteTx = false

    /** Loop rounds since the rig's mode was last asked. */
    private var toursDepuisLeMode = 0

    // ---------------------------------------------- what the rest of the app tells

    /** The knob hold delay, the same for RX, FM and TX: two seconds too long is too long everywhere. */
    fun regleMaintien(ms: Long) { rxArbiter.regle(ms); fmArbiter.regle(ms); txArbiter.regle(ms) }

    /** Something the arming depends on changed (transponder, mode, tone): set the rig up again. */
    fun oublieArmement() { catArmedFor = null; catArmedTxDesc = null }

    /** Doppler hold released: everything is written again, the rig set up again. */
    fun relache() {
        lastSentDl = 0L; lastSentUl = 0L
        oublieArmement()
        rxArbiter.reset(); fmArbiter.reset()
    }

    /** The TX knob changed role (shift control or not). */
    fun moletteTxChange() { txArbiter.reset(); mainSurMoletteTx = false }

    /** RX Doppler switched: the dial is the operator's until the loop takes it again. */
    fun dopplerRxChange(on: Boolean) {
        rxArbiter.reset()
        if (!on) change { copy(rxPilote = false) }
    }

    /** Another satellite: nothing read from the rig stands for it. */
    fun nouveauSatellite() = change { copy(descenteHz = null, monteeHz = null) }

    /** The rig is gone. */
    fun deconnecte() {
        catArmedFor = null
        rxArbiter.reset(); fmArbiter.reset()
        toursDepuisLeMode = 0
        _etat.value = EtatBoucleCat()
    }

    // ---------------------------------------------- the boundary between sky and rig
    //
    // These are the only places in the CAT loop where a frequency changes
    // domain. Everywhere else — Doppler, inversion, knob arbitration, write
    // thresholds — everything is in satellite frequencies and must stay so:
    // a `lastSentDl` mixing both domains would break the 20 Hz threshold, and
    // the arbiter would take our own command for an operator gesture.

    /**
     * What the rig can do. A frequency outside its bands is not a computation
     * error but a path that does not go through it: on a typical QO-100
     * station the downlink leaves the LNB at 739 MHz for the dongle, and the
     * rig only transmits on 144 or 432. Sending it 10 GHz would just get a
     * silent NAK ten times a second. Hence this guard, right where the
     * frequency becomes a frame.
     */
    fun atteignableParLePoste(posteHz: Long): Boolean =
        BandPlan.band(posteHz) != BandPlan.Band.AUTRE

    /**
     * Does the downlink reach the rig? Asked before reading it back: otherwise
     * what we read would be the TX frequency, and the arbiter would see an
     * operator gesture on every round.
     */
    private fun descenteAuPoste(dlSat: Long): Boolean =
        !monde.emetSeul() && atteignableParLePoste(monde.posteRx(dlSat))

    /** Writes the downlink/uplink pair, each through its converter (QO-100 too). */
    suspend fun ecrireCouple(dlSat: Long, ulSat: Long, sameBand: Boolean) {
        val poste = monde.poste()
        if (monde.emetSeul()) { ecrireMontee(ulSat, sameBand); return }
        val dl = monde.posteRx(dlSat)
        if (!atteignableParLePoste(dl)) { ecrireMontee(ulSat, sameBand); return }
        val ul = monde.posteTx(ulSat)
        if (!atteignableParLePoste(ul)) {
            // The uplink goes elsewhere: keep only the downlink.
            poste.ecrisDescente(dl)
            return
        }
        poste.ecrisCouple(dl, ul, sameBand)
    }

    /** Writes the uplink only — when the operator holds the receive side. */
    private suspend fun ecrireMontee(ulSat: Long, sameBand: Boolean) {
        val ul = monde.posteTx(ulSat)
        if (!atteignableParLePoste(ul)) return
        monde.poste().ecrisMontee(ul, sameBand)
    }

    /** Reads the downlink back and converts it straight to sky frequency. */
    private suspend fun lireDescente(sameBand: Boolean): Long? =
        runCatching { monde.poste().lisDescente(sameBand) }.getOrNull()?.let { monde.satDepuisPoste(it) }

    // ---------------------------------------------- the transponder's rules

    private fun centreRx(t: Transmitter): Long? {
        val lo = t.downlinkLowHz ?: return null
        val hi = t.downlinkHighHz ?: lo
        return (lo + hi) / 2
    }

    /** True if the chosen transmitter is FM (fixed channels) vs linear transponder. */
    private fun isFmMode(t: Transmitter): Boolean {
        if (t.isTransponder) return false
        return ModeRadio.surFm(t.mode)
    }

    /** Effective inversion: manual override if set, else the transmitter's flag. */
    private fun effectiveInvert(t: Transmitter): Boolean = monde.inversionForcee() ?: t.invert

    private fun layoutFor(t: Transmitter): DispositionSat {
        val dl = t.downlinkLowHz ?: centreRx(t) ?: 0L
        val ul = t.uplinkLowHz ?: 0L
        return when {
            ul <= 0L -> DispositionSat.BEACON
            kotlin.math.abs(dl - ul) > 10_000_000L -> DispositionSat.CROSS_BAND
            else -> DispositionSat.SAME_BAND   // V/V or U/U, e.g. ISS FM
        }
    }

    /** Map a transmitter mode string to an IC-9700 CI-V mode byte. */
    private fun civModeFor(t: Transmitter, isUplink: Boolean): Int {
        val m = t.mode?.uppercase() ?: ""
        return when {
            // FM, and the digital modes heard in FM (ISS APRS is "AFSK").
            ModeRadio.surFm(m) -> 0x05
            // Linear in CW op-mode: use CW both sides.
            t.isTransponder && monde.modeCw() -> 0x03
            m.contains("CW") -> 0x03
            // Linear SSB: by convention downlink USB; uplink mirror is LSB if inverting.
            t.isTransponder && effectiveInvert(t) -> if (isUplink) 0x00 else 0x01  // LSB up / USB down
            m.contains("LSB") -> 0x00
            else -> 0x01  // USB default
        }
    }

    /** FT-817 mode string for a transmitter leg (same policy as civModeFor). */
    private fun ft817ModeFor(t: Transmitter, isUplink: Boolean): String {
        val m = (t.mode ?: "").uppercase()
        return when {
            ModeRadio.surFm(m) -> "FM"
            m.contains("CW") && !isUplink -> "CW"
            t.isTransponder && effectiveInvert(t) -> if (isUplink) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            else -> "USB"
        }
    }

    private fun mode(t: Transmitter, isUplink: Boolean) = ModePoste(civModeFor(t, isUplink), ft817ModeFor(t, isUplink))

    /**
     * Extract a CTCSS tone (tenths of Hz) from a transmitter description such as
     * "Mode V/U FM - Voice Repeater CTCSS 67.0". Returns 0 if none found.
     */
    private fun autoCtcssTenthHz(t: Transmitter): Int {
        val d = t.description
        val m = Regex("""(?:CTCSS|TONE|PL)\s*:?\s*(\d{2,3}(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
            .find(d) ?: return 0
        val hz = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0.0.toInt()
        return Math.round(hz * 10).toInt()
    }

    /** Effective CTCSS: explicit user setting wins; else auto-detected from name. */
    fun effectiveCtcss(t: Transmitter): Int {
        if (monde.ctcssChoisi() > 0) return monde.ctcssChoisi()
        return if (monde.ctcssAuto()) autoCtcssTenthHz(t) else 0
    }

    /** Doppler threshold: FM channels move by 200 Hz steps at least, SSB by 20. */
    private fun dopplerThreshold(t: Transmitter): Long = if (isFmMode(t)) 200L else 20L

    // ---------------------------------------------- the loop

    /** Arm the radio for the current satellite according to its RF layout. */
    private suspend fun armCatForCurrent() {
        // Doppler hold: arming writes mode, split and tone — that is touching
        // the rig. Return before the "already armed" test so nothing is
        // recorded as done: full arming happens on release.
        if (monde.maintienDoppler()) return
        val catnum = monde.catnum() ?: return
        val t = monde.transpondeur() ?: return
        if (catArmedFor == catnum && catArmedTxDesc == t.description) return
        val layout = layoutFor(t)
        val poste = monde.poste()
        val tone = effectiveCtcss(t)
        runCatching {
            poste.arme(layout, mode(t, isUplink = false), mode(t, isUplink = true),
                if (isFmMode(t) && tone > 0) tone else 0)
        }
        catArmedFor = catnum
        catArmedTxDesc = t.description
        catLayout = layout
        lastSentDl = 0L; lastSentUl = 0L
        rxArbiter.reset(); fmArbiter.reset()
        // New satellite, new bands: what the driver knew about the rig is
        // void, and this is exactly when the order of the two writes is decided.
        poste.oublieBandes()
    }

    /**
     * One CAT cycle (~100 ms).
     *  - FM cross-band (V/U): both legs Doppler-tuned (MAIN downlink, SUB uplink).
     *  - FM same-band (V/V, ISS): split A/B; RX on VFO A, TX on VFO B.
     *  - Linear: read the operator's RX dial; pause Doppler while it moves; resume
     *    after RxArbiter.holdMs of quiet (setting, 2 s by default). While the
     *    dial turns, SuiviMontee decides whether the uplink follows (at once,
     *    except on single-knob rigs such as the IC-9700).
     *  - Beacon: receive-only, Doppler on downlink only.
     */
    suspend fun tour() {
        if (!monde.connecte()) return
        // An APRS frame is going out: no frequency write for that second or two.
        if (monde.aprsEnEmission()) return
        val poste = monde.poste()
        val pos = monde.satellite()
        val belowHorizon = pos == null || pos.elevationDeg < 0
        if (belowHorizon && !monde.sousHorizonAussi()) {
            val dlNow = runCatching { poste.lisDescente(memeBande = false) }.getOrNull()?.let { monde.satDepuisPoste(it) }
            // Below the horizon nobody drives: the hold countdown restarts
            // from zero at AOS.
            rxArbiter.reset(); fmArbiter.reset()
            if (dlNow != null) change { copy(descenteHz = dlNow, rxPilote = false) }
            return
        }
        val rr = pos?.rangeRateKmS ?: 0.0
        val t = monde.transpondeur() ?: return
        armCatForCurrent()
        val shift = monde.calibrationHz()
        val txShift = monde.decalageTxHz()

        when (catLayout) {
            DispositionSat.BEACON -> {
                val dlRest = monde.repos() ?: centreRx(t) ?: return
                val dl = Doppler.downlink(dlRest, rr) + shift
                if (monde.maintienDoppler()) {
                    // On hold, show the rig as it is, not the command we do not
                    // send (that would fake tracking). Read via `lireDescente`,
                    // so already a sky frequency, never the LNB IF.
                    val lu = lireDescente(sameBand = false)
                    change { copy(descenteHz = lu ?: dl, monteeHz = null, rxPilote = false) }
                } else {
                    if (kotlin.math.abs(dl - lastSentDl) >= dopplerThreshold(t)) {
                        lastSentDl = dl
                        runCatching { poste.ecrisDescente(monde.posteRx(dl)) }
                    }
                    change { copy(descenteHz = dl, monteeHz = null) }
                }
            }

            DispositionSat.SAME_BAND -> {
                // ISS-type V/V. FM: fixed both sides + Doppler via split A/B.
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = true)
                else interactiveLinear(t, rr, shift, txShift, sameBand = true)
            }

            DispositionSat.CROSS_BAND -> {
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = false)
                else interactiveLinear(t, rr, shift, txShift, sameBand = false)
            }
        }

        surveillerLeMode(t)
    }

    /**
     * Checks and shows the rig's mode. Set once at arming, nobody looked at it
     * again: if the operator switched to LSB or the rig reverted to its band
     * memory, the screen still said USB and the other station became
     * inaudible with no explanation. So read it back, show it, and restore it
     * when it moved. One round in twenty (~2 s): the mode does not change ten
     * times a second, and the CI-V bus is busy with Doppler.
     */
    private suspend fun surveillerLeMode(t: Transmitter) {
        val poste = monde.poste()
        if (poste.paire) return
        if (++toursDepuisLeMode < 20) return
        toursDepuisLeMode = 0
        val lu = runCatching { poste.lisMode() }.getOrNull() ?: return
        val attendu = civModeFor(t, isUplink = false)
        val ecart = lu != attendu
        change { copy(mode = CatDecode.civModeName(lu), modeEcart = ecart) }
        // Restoring the mode is a write like any other: on Doppler hold, only
        // report the mismatch.
        if (ecart && !monde.maintienDoppler()) runCatching { poste.ecrisMode(attendu) }
    }

    /**
     * FM: Doppler drives both legs, and lets go of the knob when the operator
     * touches it. It used to write both frequencies every round without
     * looking, so any manual RX touch was erased 100 ms later.
     *
     * Now the downlink is read back. A gap explained by none of our commands
     * is a gesture: go quiet, and the chosen channel becomes the new rest —
     * with the instant Doppler removed, or it would apply twice. After the
     * hold time, tracking resumes from that rest. The uplink never changes
     * channel: a satellite repeater input is fixed, only its Doppler varies.
     */
    private suspend fun interactiveFm(t: Transmitter, rr: Double, shift: Long, txShift: Long, sameBand: Boolean) {
        val ulRest = t.uplinkLowHz ?: return
        val dlObserved = lireDescente(sameBand)

        if (dlObserved != null) {
            val geste = fmArbiter.observe(dlObserved, horloge())
            if (geste) {
                lastSentDl = 0L
                // The hand-picked channel is a rig frequency; rest is a
                // satellite frequency. Removing instant Doppler converts.
                monde.poseRepos(Doppler.restFromDownlink(dlObserved - shift, rr))
            }
        }

        val rest = monde.repos() ?: centreRx(t) ?: return
        val dl = Doppler.downlink(rest, rr) + shift
        val ul = Doppler.uplink(ulRest, rr) + txShift

        // No readback possible: always write — a mute rig must not lose
        // Doppler. On Doppler hold, never drive, whatever the arbiter says.
        val pilote = (dlObserved == null || fmArbiter.driven) && !monde.maintienDoppler()
        if (pilote) {
            if (kotlin.math.abs(dl - lastSentDl) >= 200 || kotlin.math.abs(ul - lastSentUl) >= 200) {
                lastSentDl = dl; lastSentUl = ul
                fmArbiter.commanded(dl)
                runCatching { ecrireCouple(dl, ul, sameBand) }
            }
            change { copy(descenteHz = dl, monteeHz = ul, rxPilote = true) }
        } else {
            // No readback (mute rig and hold): show the computed command
            // rather than nothing.
            change { copy(descenteHz = dlObserved ?: dl, monteeHz = ul, rxPilote = false) }
        }
    }

    /**
     * Linear transponder: the operator leads, then the software takes over.
     *
     * While the knob moves, read it and derive the rest frequency: the operator
     * chooses where to listen, and we touch nothing. After the hold time that
     * choice becomes a satellite frequency, and the phone tunes RX **and** TX,
     * both derived from the same frozen rest.
     *
     * **The frozen rest is the key.** Recomputing it on every read asked the rig
     * where it was only to tell it it was right: RX never moved, and TX drifted
     * with the Doppler we had just re-injected by mistake.
     */
    private suspend fun interactiveLinear(t: Transmitter, rr: Double, shift: Long, txShift: Long, sameBand: Boolean) {
        // The downlink comes back to the rig only if it can receive it. On a
        // QO-100 station it is heard on the dongle: nothing to read back or
        // arbitrate, and the rest chosen elsewhere drives TX.
        val auPoste = descenteAuPoste(monde.repos() ?: centreRx(t) ?: return)
        val dlObserved = if (auPoste) lireDescente(sameBand) else null
        if (auPoste && dlObserved == null) return
        val now = horloge()
        val geste = dlObserved != null && rxArbiter.observe(dlObserved, now)
        if (geste) lastSentDl = 0L
        val pilote = (!auPoste || rxArbiter.driven) && monde.dopplerRx() && !monde.maintienDoppler()

        // While not driving, rest is read from the rig, with calibration and
        // mode (voice/CW) offsets removed since they are re-added on write.
        val lu = if (dlObserved != null)
            Doppler.restFromDownlink(dlObserved - shift - monde.decalageRxHz(), rr)
        else monde.repos() ?: centreRx(t) ?: return
        val lo = t.downlinkLowHz ?: lu
        val hi = t.downlinkHighHz ?: lu
        if (!pilote) {
            val clamped = lu.coerceIn(minOf(lo, hi), maxOf(lo, hi))
            val r = monde.repos()
            if (r == null || kotlin.math.abs(r - clamped) >= 20) monde.poseRepos(clamped)
        }
        val rest = monde.repos() ?: lu

        val ulRest = Doppler.transponderUplinkRest(
            rest, t.downlinkLowHz ?: rest, t.downlinkHighHz ?: rest,
            t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
        val ul = Doppler.uplink(ulRest, rr) + txShift
        val dl = Doppler.downlink(rest, rr) + shift + monde.decalageRxHz()

        // --- TX knob as shift control ---
        // `ul` = `Doppler.uplink(ulRest, rr) + txShift`; if the operator turned
        // the knob to `ulLu`, the shift expressed is `ulLu - (ul - txShift)`.
        // Two-rig duplex only, and only while driving — otherwise we would take
        // the drift of a rig not yet commanded for a gesture.
        val poste = monde.poste()
        if (monde.moletteTxDecalage() && poste.paire && !sameBand && pilote) {
            val ulLu = runCatching { poste.lisMontee() }.getOrNull()?.let { monde.satDepuisPosteTx(it) }
            if (ulLu != null) {
                // The computation lives in `MoletteTx`: it was wrong twice here,
                // untestable amid serial reads and writes. Isolated, it is
                // tested, including the Doppler drift that made the shift
                // oscillate between two values.
                if (txArbiter.observe(ulLu, now)) mainSurMoletteTx = true
                val d = MoletteTx.decide(
                    shiftHz = txShift,
                    referenceHz = lastSentUl,
                    lueHz = ulLu,
                    gesteVu = mainSurMoletteTx,
                    moletteTranquille = txArbiter.driven)
                if (d.absorbe) {
                    monde.poseDecalageTx(d.shiftHz)
                    lastSentUl = d.referenceHz
                }
                if (d.gesteConsomme) mainSurMoletteTx = false
            }
        }

        if (pilote) {
            // 20 Hz: below that the gap is inaudible and writing only loads
            // the CI-V bus.
            if (kotlin.math.abs(dl - lastSentDl) >= 20 || kotlin.math.abs(ul - lastSentUl) >= 20) {
                lastSentDl = dl; lastSentUl = ul
                // Recognise ourselves on readback, or our own command would
                // look like an operator gesture and we would let go every round.
                rxArbiter.commanded(dl)
                // Same for our own uplink.
                txArbiter.commanded(ul)
                runCatching { ecrireCouple(dl, ul, sameBand) }
            }
            change { copy(descenteHz = dl, monteeHz = ul, rxPilote = true) }
        } else {
            // The uplink follows the RX knob — but not on every rig. "The hold
            // protects the RX knob, no reason to hold back TX" is false on a
            // dual-VFO rig with one knob: on an IC-9700 in satellite mode,
            // writing the uplink moves RX back, and the operator fights us
            // (filmed on RS-44 and FO-29: the sum of both VFOs stayed constant,
            // proof the rig held the pair). The decision is in `SuiviMontee`,
            // dependency-free and tested.
            if (SuiviMontee.doitEcrire(
                    rigModel = monde.modelePoste(),
                    operateurTourne = !rxArbiter.driven,
                    txSuitVite = monde.txSuitVite(),
                    maintienDoppler = monde.maintienDoppler(),
                    ecartHz = kotlin.math.abs(ul - lastSentUl))) {
                lastSentUl = ul
                txArbiter.commanded(ul)
                runCatching { ecrireMontee(ul, sameBand) }
            }
            change { copy(descenteHz = dlObserved, monteeHz = ul, rxPilote = false) }
        }
    }
}
