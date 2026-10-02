/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.f4ioz.satcombo.data.FavoritesStore
import fr.f4ioz.satcombo.data.LocationMode
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.Geocoder
import fr.f4ioz.satcombo.data.GeoResult
import fr.f4ioz.satcombo.data.PotaHit
import fr.f4ioz.satcombo.data.PotaPark
import fr.f4ioz.satcombo.data.PotaRegion
import fr.f4ioz.satcombo.data.PassPdf
import fr.f4ioz.satcombo.data.PotaRepository
import fr.f4ioz.satcombo.data.SatConfig
import fr.f4ioz.satcombo.data.SatConfigStore
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.SkedAlert
import fr.f4ioz.satcombo.data.SkedPlan
import fr.f4ioz.satcombo.data.SkedSample
import fr.f4ioz.satcombo.data.SkedStationTrack
import fr.f4ioz.satcombo.data.SkedRepository
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.SuiviPosition
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.notify.TleRefreshWorker
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.RafraichissementTle
import fr.f4ioz.satcombo.data.SourcesStore
import fr.f4ioz.satcombo.data.TleCache
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.TleRepository
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.data.TransmittersRepository
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.DopplerPass
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.location.LocationProvider
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.notify.PassAlertWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PASSES, SETTINGS, LOCATOR, SKED, TIMELINE, PHOTO, ACTIVATION, SSTV, SDR, APT, AGENDA, SONDE, ROTOR, QO100, NOMMAGE, GLOBE, FT8, APRS }

/**
 * Fine-tuning settings, kept out of [UiState] because of the 255-register
 * constructor limit (see [RotorUi]). The three-key box lives here too: it also
 * answers "what are we tuning".
 */
data class AccordUi(
    /** What the knob drives: "VFO", "SHIFT_RX" or "SHIFT_TX". */
    val moletteCible: String = "VFO",
    val macroCodeA: Int = 0,
    val macroCodeB: Int = 0,
    val macroCodeC: Int = 0,
    val macroCibleA: String = "SHIFT_RX",
    val macroCibleB: String = "SHIFT_TX",
    val macroCibleC: String = "VFO",
    /** The knob's push button: its key and its action. */
    val macroCodeD: Int = 164,
    val macroActionD: String = "PAS",
    /** Magnifier: second spectrum view, a few kHz wide. */
    val loupe: Boolean = true,
    val loupeSpanHz: Int = 5_000,
    /** Vernier: scrolling dial, relative tuning by finger. */
    val vernier: Boolean = true,
    val vernierHzParCm: Int = 200,
    /** Button that locks onto the received voice. */
    val calageVoix: Boolean = true,
)

/**
 * Radio (CAT) link settings, kept out of [UiState] (255-register limit, see
 * [RotorUi]).
 */
data class CatUi(
    /** IC-705 CI-V speed in an FT-817 + IC-705 pair; the FT-817 has its own. */
    val ic705Baud: Int = 115_200,
    /**
     * Is the rig transmitting? Read over CAT, so also true when VOX keyed it —
     * precisely the case where the operator commanded nothing and the warning
     * matters.
     */
    val enEmission: Boolean = false,
    /** Is the transmit border requested? */
    val liseret: Boolean = true,
    /**
     * TX follows the RX knob without waiting for silence. Adjustable for links
     * where the extra writes are too chatty.
     */
    val txSuitVite: Boolean = true,
    /** Polling period, ms. */
    val sondeMs: Int = 500,
    /**
     * Raw PTT read reply. When the border does not light, there is no other
     * way to tell a command not sent from a rig not answering or a reply
     * misparsed.
     */
    val txDiag: String = "",
    /**
     * Silence required, ms, before the software takes the RX knob back.
     * Default 2 s; 1 s or 0.5 s for fast searching.
     */
    val holdMs: Int = 2_000,
    /**
     * The TX knob acts as the shift control: the gap between what was
     * commanded and what is read back becomes the new TX shift. The buttons
     * stay active — the last gesture wins, since the shift is one value, not
     * a sum of two sources.
     */
    val txVfoShift: Boolean = false,
    /**
     * Live CAT readback, Hz, shown in the CAT settings so the link can be
     * checked on the screen that sets it up (it used to take three screens).
     * Refreshed only while the CAT section is visible — the port belongs to
     * Doppler, not to this indicator.
     */
    val veilleRxHz: Long? = null,
    val veilleTxHz: Long? = null,
    /** Did the readback get an answer on the last round? */
    val veilleVivante: Boolean = false,
    /** Shifts saved as reference for the displayed satellite, if any. */
    val refCalibShiftHz: Long? = null,
    val refTxShiftHz: Long? = null,
    /**
     * Shifts **as they were when this satellite was opened**.
     *
     * The explicit reference only protects what the operator thought to save.
     * The TX knob acts as the shift control and `setTxShift` writes to disk on
     * every detent, so yesterday's working value is lost at the first touch.
     * We silently keep the value from opening time: not "the right value" —
     * nobody knows that — but "the one before this pass", enough to go back.
     *
     * Memory only, on purpose: persisting would make a third shift to
     * understand.
     */
    val arriveeCalibShiftHz: Long? = null,
    val arriveeTxShiftHz: Long? = null,
    /** Summary of the last batch merge, or empty. */
    val lotBilan: String = "",
)

/**
 * What location tracking can report about itself. Two first-launch fixes were
 * made blind; this lets the operator read the state on screen and report it.
 */
data class SuiviUi(
    /** Last thing that happened to tracking, in plain words. */
    val etat: String = "",
    /** Fixes received since launch. Zero is the symptom. */
    val points: Int = 0,
    /** Restarts decided by the watchdog. */
    val relances: Int = 0,
    /** Is location permission granted? */
    val permission: Boolean = false,
)

/**
 * Online log (Wavelog) and its answers. In its own holder from day one
 * (255-register limit, see [RotorUi]).
 */
data class CarnetUi(
    val url: String = "",
    val cle: String = "",
    val slug: String = "",
    /** Result of the last connection test. */
    val essai: String = "",
    /** Station profile id used to upload contacts. */
    val profil: String = "",
    /** What to harvest: "sat", "phonie", "cw" or "tout". */
    val filtre: String = "sat",
    /** Each new contact sent on after a minute (EnvoiAuto). */
    val auto: Boolean = false,
    /** Last automatic upload, or why it stopped. */
    val autoEtat: String = "",
    /** SatMe acts as a radio in the online log (RelaisRadio). */
    val radio: Boolean = false,
    val radioNom: String = "SatMe",
    /** Last radio message: time sent, or the reason it was refused. */
    val radioEtat: String = "",
    /** Result of the last upload. */
    val depot: String = "",
    /** Upload in progress: the button cannot be pressed again. */
    val depotEnCours: Boolean = false,
    /** LoTW: credentials and summary of the last refresh. */
    val lotwCall: String = "",
    val lotwMdp: String = "",
    val lotwEtat: String = "",
    val lotwConfirmes: Set<String> = emptySet(),
    val lotwTravailles: Set<String> = emptySet(),
    /** Grid squares I transmitted from, painted in another colour. */
    val lotwActives: Set<String> = emptySet(),
    /** Paint the squares on the maps. */
    val peindre: Boolean = true,
    /** Square → state, as shown on the locator page. */
    val carres: Map<String, fr.f4ioz.satcombo.data.CarnetEnLigne.Etat> = emptyMap(),
    /**
     * Location → station profile id.
     *
     * Wavelog files a contact by its profile and **ignores the file's
     * `MY_GRIDSQUARE`**. Without this table, an operator activating several
     * squares silently sees everything filed under the single profile's
     * square — and that is the one that counts for awards. The key is a
     * **set** of squares: on a line you are in both, and those contacts cannot
     * share the profile of contacts made in only one.
     */
    val profils: Map<Set<String>, String?> = emptyMap(),
    /**
     * Profiles as Wavelog returns them. An id alone is a number nobody
     * recognises; typing one from memory among 26 profiles is guesswork, and a
     * mistake shows nowhere — the contact is accepted and filed under another
     * location's square.
     */
    val profilsListe: List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil> = emptyList(),
    /** Matching precision: 4 like VUCC, or 6. */
    val maille: Int = 4,
    /** Result of the last profile fetch. */
    val profilsEtat: String = "",
    /** QRZ.com: credentials, and what the last test or fill reported. */
    val qrzUser: String = "",
    val qrzMdp: String = "",
    val qrzEtat: String = "",
    val qrzEnCours: Boolean = false,
) {
    val configure: Boolean get() = url.isNotBlank() && cle.isNotBlank() && slug.isNotBlank()
}

/** QRV photo overlays: country map and POTA. */
data class CarteUi(
    /**
     * Is the satellite's contact list expanded?
     *
     * Put in an existing holder: a new holder would still add a register to
     * `UiState`, which is at its limit. The rule is not "use a holder", it is
     * "not one more field in `UiState`".
     */
    val listeContactsOuverte: Boolean = false,
    /** The photo's second flag (moved out of `UiState` for the register limit). */
    val flagRight: String = "",
    val affichee: Boolean = false,
    /** POTA line: reference and name found around the position. */
    val potaAffiche: Boolean = false,
    val potaRef: String = "",
    val potaNom: String = "",
    /** A nearby park, suggested to the operator but never filled in automatically. */
    val potaPropose: String = "",
    val potaProposeNom: String = "",
    /** Result of the last outline preload. */
    val contoursEtat: String = "",
    val taille: Float = 0.42f,
    val x: Float = 0.5f,
    val y: Float = 0.52f,
    val remplissage: String = "DRAPEAU",
    /** "PAYS" or "ZONE": what the outline shows. */
    val contenu: String = "PAYS",
    val potaTaille: Float = 1f,
    val potaMonte: Float = 0f,
    val couleur: Int = 0x66FFFFFF,
    val bandeauAccueil: Boolean = true,
    /** Towns around the area, as landmarks. */
    val villes: List<Triple<String, Double, Double>> = emptyList(),
    val fondu: Boolean = true,
    val potaNomAffiche: Boolean = true,
    val qrgAffiche: Boolean = false,
    val qrgScale: Float = 1f,
    val qrgTexte: String = "",
    val passScale: Float = 1f,
    val fondUni: Int = 0xFF102030.toInt(),
    /** Rings of the current country, loaded on demand. */
    val anneaux: List<DoubleArray> = emptyList(),
    val paysNom: String = "",
    /**
     * The POTA area whose outline contains the position, if any. Judged by
     * polygon (pota-map.fr), not radius: being "in the park" is a matter of
     * boundary, not distance to the centre.
     */
    val zoneRef: String = "",
    val zoneNom: String = "",
    val zoneAnneaux: List<DoubleArray> = emptyList(),
)

/** Quick-log state, kept out of [UiState] (255-register limit, see [RotorUi]). */
data class CarnetExpress(
    /** Known stations: local log plus imported index. */
    val memoire: List<fr.f4ioz.satcombo.domain.Indicatifs.Connu> = emptyList(),
    /** Callsign keypad: confirm key on the side of the holding hand. */
    val mainGauche: Boolean = false,
    /** Key layout: "abc", "azerty" or "qwerty". */
    val disposition: String = "abc",
)

/** One satellite's elevation curve over the timeline window. */
data class TimelineTrack(
    val catnum: Int,
    val name: String,
    /** (epoch ms, elevation °) sampled at a regular step over the window. */
    val samples: List<Pair<Long, Double>>
) {
    val peakDeg: Double get() = samples.maxOfOrNull { it.second } ?: -90.0
}

/**
 * Rotor state, kept separate.
 *
 * **Not a tidiness choice: the 255-register limit.** Dalvik encodes the
 * register count of an `invoke/range` call on one byte. A `data class` whose
 * constructor needs more than 255 registers compiles silently, passes every
 * test that does not build it, and kills the app the moment it asks for its
 * first state (happened in 18.22). A non-null `Double` or `Long` costs two
 * registers, everything else one, plus one for the object itself. This is why
 * [UiState] is split into holders: never add a flat field to it lightly.
 *
 * Grouping brings these forty-five fields down to one register. Reads keep
 * their original names (`ui.rotorEnabled`) through the delegating accessors
 * of [UiState]; only writes go through [UiState.rot].
 */
data class RotorUi(
    // --- rotor az/el ---
    val rotorEnabled: Boolean = false,
    val rotorConnected: Boolean = false,
    val rotorStatus: String = "",
    val rotorType: String = "GS232",
    val rotorBaud: Int = 9600,
    val rotorUsbIndex: Int = 0,
    val rotorPort: Int = 4533,
    val rotorMaxAz: Int = 450,
    val rotorMaxEl: Int = 90,
    /** Where the mast's end stop is: "NORTH" or "SOUTH". */
    val rotorAzStop: String = "NORTH",
    /** Does the controller count from its end stop rather than true north? */
    val rotorAzFromStop: Boolean = false,
    /** Pointing error tolerated before the satellite is considered lost. */
    val rotorMaxError: Int = 15,
    val rotorMinEl: Int = 0,
    val rotorFlip: Boolean = false,
    val rotorAzOnly: Boolean = false,
    /** Minutes before AOS at which the mast goes to wait at the rise point. */
    val rotorPreAos: Int = 3,
    /** True while the mast waits for the satellite at its rise point. */
    val rotorPrePositioning: Boolean = false,
    val rotorPark: Boolean = true,
    val rotorParkAz: Int = 0,
    val rotorParkEl: Int = 0,
    val rotorSimulated: Boolean = false,
    val rotorPosAz: Double? = null,
    val rotorPosEl: Double? = null,
    val rotorFlipped: Boolean = false,
    val rotorMoves: Int = 0,
    val rotorDevices: List<String> = emptyList(),
    /** Share of the current pass actually reachable, 0 to 1. */
    val rotorCoverage: Double? = null,
    val rotorLink: String = "GS232",
    val rotorHost: String = "192.168.1.10",
    val rotorDeadband: Int = 2,
    val rotorSim: Boolean = false,
    val rotorTargetAz: Double? = null,
    val rotorTargetEl: Double? = null,
    val rotorActualAz: Double? = null,
    val rotorActualEl: Double? = null,
    /**
     * Where the antenna points, as the compass must show it: azimuth wrapped
     * into one turn, elevation flip undone. Null when the mast says nothing —
     * the compass falls back to the phone. [rotorAimEl] stays null on an
     * azimuth-only rotor.
     */
    val rotorAimAz: Double? = null,
    val rotorAimEl: Double? = null,
    val rotorOutOfRange: Boolean = false,

    /**
     * Log of the last connection attempt, line by line: which port was tried,
     * which refused permission, which opened and never answered.
     */
    val rotorDiag: List<String> = emptyList(),

    /**
     * Try the other ports when the chosen one does not answer: as for the rig,
     * the port index depends on plug-in order, which nobody controls.
     */
    val rotorAutoPort: Boolean = true,

    /** Manual azimuth, for testing without a satellite. */
    val rotorManualAz: Int = 0,

    /** Manual elevation, for testing without a satellite. */
    val rotorManualEl: Int = 0,

    /** Last frame received from the controller, verbatim — empty if silent. */
    val rotorLastReply: String = "",

    /** Last frame sent to the controller, verbatim. */
    val rotorLastSent: String = "",
    /** Gap between the target and the reachable point, degrees. */
    val rotorErrorDeg: Double = 0.0,

    // ---- Remote compass ----
    // Here rather than flat in UiState (register limit). The rotor is the
    // right holder: it also answers "where does the antenna point".
    /** "TEL" = phone sensors, "BLE" = module on the boom. */
    val boussoleSource: String = "TEL",
    val boussoleAdresse: String = "",
    val boussoleNom: String = "",
    val boussoleCalage: Float = 0f,
    /** The module's measured convention: "DIRECTE", "LACET_OPPOSE"… */
    val boussoleConvention: String = "DIRECTE",
    /** Calibration readings, one line per pose. */
    val boussoleReleves: String = "",
    /** The boom in the module's frame, "x,y,z". Empty = not learnt. */
    val boussoleFleche: String = "",
)

/**
 * QO-100 screen state, kept separate for the same reason as [RotorUi] (fifteen
 * flat fields would have crossed the 255-register limit).
 *
 * Unlike [RotorUi], no delegating accessors on [UiState]: read directly via
 * `ui.qo100.`, write via [UiState.qo].
 *
 * Everything here is in sky frequencies. Conversion to rig and dongle happens
 * at the last moment, through the converters, as everywhere else — except
 * [posteRxHz] and [posteTxHz], display-only and named so nobody mistakes them.
 */
data class Qo100Ui(
    /** Receivers and their own offset, ppm. */
    val materiels: List<fr.f4ioz.satcombo.domain.MaterielRx.Materiel> =
        fr.f4ioz.satcombo.domain.MaterielRx.parDefaut(),
    val materielPoste: String = "FT-817 A",
    val materielCle: String = "Clé SDR 1",
    /** Selected transponder key: "nb" or "wb". See [Qo100.TRANSPONDEURS]. */
    val transpondeur: String = "nb",
    /** True when sweeping all of QO-100 instead of just the transponder. */
    val sansBride: Boolean = false,

    /** Where we listen, in the sky. The uplink follows from the transponder offset. */
    val descenteHz: Long = Qo100.BALISE_MEDIANE_HZ,

    /** Does the rig follow the screen? Independent of [aLaCle], on purpose. */
    val auPoste: Boolean = false,

    /** Does the SDR dongle follow the screen? */
    val aLaCle: Boolean = false,

    /**
     * What the rig actually displays, after converters (on the target station:
     * 145 RX, 432 TX). Zero until a conversion is done. It is the number to
     * compare with the radio's display — the only check possible before
     * hearing anything.
     */
    val posteRxHz: Long = 0L,
    val posteTxHz: Long = 0L,

    /** Same for the SDR dongle, which may sit behind another setup. */
    val cleRxHz: Long = 0L,

    /**
     * Operator shortcuts. Band plan markers are **not** here: they derive from
     * the published segments; mixing them in would mean maintaining them twice.
     */
    val memoires: List<fr.f4ioz.satcombo.domain.MemoiresQo100.Memoire> = emptyList(),

    /** Saved conversion chains, and the one in use. */
    val chaines: List<fr.f4ioz.satcombo.domain.ChaineQo100.Chaine> = emptyList(),
    val chaine: String = "Fixe",

    /**
     * Can the rig really go there? False when the converter is not set, or the
     * resulting frequency falls outside its bands.
     */
    val posteAtteignable: Boolean = false,
    val cleAtteignable: Boolean = false,

    /**
     * Current calibration offset, Hz, as stored for NORAD 43700. It absorbs the
     * downconverter oscillator drift and nothing else — certainly not the
     * oscillator itself, which lives in [fr.f4ioz.satcombo.domain.Convertisseur].
     */
    val calageHz: Long = 0L,

    /** Cursor is on the middle beacon, within display tolerance. */
    val surBalise: Boolean = false,

    /** Dish pointing, computed once from the QTH. Null until computed. */
    val azDeg: Double? = null,
    val elDeg: Double? = null,
    val skewDeg: Double? = null,

    /**
     * Next time the Sun is at the satellite's azimuth: the shadow of a vertical
     * stake then gives the dish axis without a compass. Null until computed,
     * or when the satellite is not visible from the QTH.
     */
    val soleilAzimutMs: Long? = null,

    /**
     * Next Sun transits *behind* the satellite: fine-tunes both angles at once,
     * and explains reception collapsing for a few minutes around the equinoxes.
     */
    val soleilTransits: List<fr.f4ioz.satcombo.domain.SoleilQo100.Transit> = emptyList(),

    /**
     * Middle beacon as the dongle sees it now: offset and margin above noise.
     * Null when not found (dongle stopped, no panorama yet, dish off, or no
     * converter). The station's permanent check: the offset says whether
     * calibration holds, the ratio whether pointing is good.
     */
    val balise: fr.f4ioz.satcombo.domain.MesureBalise.Mesure? = null,

    /** Last status line shown under the controls. */
    val statut: String = "",
)

data class UiState(
    val loading: Boolean = false,
    val screen: Screen = Screen.PASSES,
    val observer: Observer? = null,
    val locationMode: LocationMode = LocationMode.AUTO,
    val manualLocator: String = "JN18FS",
    /** Exact point picked on the map; null = fall back to the square centre. */
    val manualLat: Double? = null,
    val manualLon: Double? = null,
    val locatorError: String? = null,
    val aimMode: String = "EDGE",
    val showAimModeChips: Boolean = false,
    val compassHeadUp: Boolean = true,
    val compassStyle: String = "NEEDLE",
    val tleCacheHours: Int = 24,
    /**
     * Source of the status shown next to the satellite. AMSAT alone by default:
     * hams feed it themselves in real time, and it is the one quoted on the air.
     */
    val statusSource: String = "AMSAT",
    val darkTheme: Boolean = true,
    // Display scaling — see SettingsStore/Theme: keeps the layout identical
    // across handsets instead of letting each screen size rearrange it.
    val uniformUi: Boolean = true,
    val uiScaleStep: Int = 0,
    val uiFollowSystemFont: Boolean = false,
    val language: String = "auto",
    val mapStyle: String = "OSM",
    val settingsSection: String? = null,
    val skedsEnabled: Boolean = false,
    val skedsToken: String = "",
    val skedsAuthed: Boolean = false,
    val skeds: List<SkedAlert> = emptyList(),
    val skedsMutualOnly: Boolean = false,
    // --- Mutual-sked page ---
    val skedSatCat: Int? = null,          // satellite chosen for the sked
    val skedOtherLoc: String = "",        // DX station locator
    val skedMinElOther: Int = 0,          // DX station minimum elevation
    val skedComputing: Boolean = false,
    val skedComputed: Boolean = false,    // a computation has completed at least once
    val skedError: String? = null,        // "bad" = invalid locator
    val skedPlans: List<SkedPlan> = emptyList(),
    val skedSelectedIndex: Int = 0,
    /** Quick taps on the compass that open the log entry screen. */
    val logTaps: Int = 3,
    val logEditTimeMs: Long? = null,  // entry awaiting callsign/grid input
    val minElevDeg: Int = 5,
    /** How far back the pass lists reach: 0 = only what is still to come. */
    val pastPassHours: Int = 0,
    val useUtc: Boolean = false,
    val locatorDetails: Boolean = true,
    // --- compass colours (ARGB ints) ---
    val compassTraceColor: Int = 0xFF6C8BFF.toInt(),
    val needleFarColor: Int = 0xFFD6336C.toInt(),
    val needleNearColor: Int = 0xFFF59F00.toInt(),
    val needleCloseColor: Int = 0xFF2FB344.toInt(),
    val bubbleFarColor: Int = 0xFFD6336C.toInt(),
    val bubbleNearColor: Int = 0xFFF59F00.toInt(),
    val bubbleCloseColor: Int = 0xFF2FB344.toInt(),
    val compassTraceWidth: Float = 2.4f,
    val ringAzNearColor: Int = 0xFFF59F00.toInt(),
    val ringAzCloseColor: Int = 0xFF2FB344.toInt(),
    val ringElNearColor: Int = 0xFFF59F00.toInt(),
    val ringElCloseColor: Int = 0xFF2FB344.toInt(),
    // --- audio recorder ---
    val recorderEnabled: Boolean = true,
    val recorderSource: String = "MIC",   // MIC, BT (Bluetooth HFP) or USB (sound card)
    val recorderUnprocessed: Boolean = false,
    /**
     * Doppler hold: computing, display and tracking go on, but no frequency is
     * sent to the rig. Deliberately not persisted — a hold forgotten from one
     * pass to the next could only lose the next one.
     */
    val dopplerHold: Boolean = false,
    val monitorSpectre: Boolean = false,
    val monitorSpeaker: Boolean = false,
    val sstvEnabled: Boolean = true,
    val aptEnabled: Boolean = false,
    // --- RTL-SDR dongle (beta) ---
    val sdrSstv: Boolean = true,
    val sdrRecord: Boolean = true,
    val sdrAudio: Boolean = true,
    val sdrAgc: Boolean = false,
    val sdrPpm: Int = 0,
    val sdrMode: String = "NFM",
    val sdrBandwidthHz: Int = 0,
    val sdrSquelchDb: Int = -120,
    val sdrSpanHz: Int = 48_000,
    /** The three fine-tuning aids (register limit, see [RotorUi]). */
    val accord: AccordUi = AccordUi(),
    /**
     * Quick log, kept separate: four more flat fields crossed the 255-register
     * limit; the guard test caught it before a device died at startup.
     */
    val express: CarnetExpress = CarnetExpress(),
    /** Location tracking state, so it can report on itself. */
    val suivi: SuiviUi = SuiviUi(),
    /** 0 dark, 1 light, 2 sunlight. */
    val themeIndex: Int = 0,
    /** USB knob drives the VFO, and its current step in Hz. */
    val moletteVfo: Boolean = false,
    val molettePasHz: Long = 100L,
    /** Radio (CAT) link settings. */
    val catUi: CatUi = CatUi(),
    val carnet: CarnetUi = CarnetUi(),

    /** FM de-emphasis (broadcast listening); off by default. */
    val sdrDeemph: Boolean = false,
    /** Automatic Doppler tracking of the dongle during the pass. */
    val sdrDopplerTrack: Boolean = true,
    /** Small waterfall under the compass on the pass page. */
    val sdrInlineWaterfall: Boolean = true,
    val recordingsTreeUri: String = "",   // SAF export folder ("" = app dir only)
    /** What the pass image strip follows: "SSTV" or "NOAA". */
    val rxImageMode: String = "SSTV",
    val recording: Boolean = false,
    // Freeze the detail screen scroll position (field use: no accidental scrolls
    // while tracking a bird; taps/buttons still work). Not persisted.
    val uiLocked: Boolean = false,
    val recordStartMs: Long = 0L,
    val recordFileName: String? = null,   // current or last recording
    val recordAutoStopMs: Long? = null,   // LOS + 5 s, null = manual only
    val potaEnabled: Boolean = false,
    val nearbyPota: List<PotaHit> = emptyList(),
    val potaRadiusKm: Int = 8,
    val potaUpdating: Boolean = false,
    val potaRegionLabel: String? = null,
    val potaCount: Int = 0,
    val selectedPassKeys: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val geoResults: List<GeoResult> = emptyList(),
    val geoSearching: Boolean = false,
    val notifyEnabled: Boolean = true,
    val notifyLeadMin: Int = 5,
    val notifyMode: String = "FAV",
    val notifiedPassKeys: Set<String> = emptySet(),
    val groundTrack: List<Pair<Double, Double>> = emptyList(),
    val dateFilter: Pair<Long, Long>? = null, // [startMs, endMs] local-day bounds
    /**
     * Pass list depth, hours. Opens at 48 h — what is looked at 99 times out of
     * 100; computing fifteen days at every startup would make everyone pay for
     * a few. Scrolling to the bottom adds two days at a time, up to fifteen
     * days, beyond which orbital elements are worth little.
     */
    val passHorizonHours: Int = 48,
    val showDatePicker: Boolean = false,
    val satellites: List<TleEntry> = emptyList(),
    val favorites: Set<Int> = emptySet(),
    val enabledSources: Set<String> = Sources.DEFAULT_IDS,
    val query: String = "",
    val selected: TleEntry? = null,
    val passes: List<SatPass> = emptyList(),
    val passTrack: List<Pair<Double, Double>> = emptyList(),
    val focusedPassAos: Long? = null,  // the specific pass the user tapped
    val transmitters: List<Transmitter> = emptyList(),
    val transmittersLoading: Boolean = false,
    val selectedTxIndex: Int = 0,
    val rxRestHz: Long? = null,  // chosen downlink (rest) freq within passband
    val satStatus: String? = null,  // SatNOGS operational status
    val calibShiftHz: Long = 0L, // operator's fixed per-sat RX correction
    val txShiftHz: Long = 0L,    // operator's fixed per-sat TX (uplink) correction
    val invertOverride: Boolean? = null, // null = use SatNOGS; true/false = manual NOR/REV
    val opMode: String = "VOICE",        // VOICE or CW (linear operating sub-mode)
    val rxOffsetVoiceHz: Long = 0L,
    val rxOffsetCwHz: Long = 0L,
    // --- converters (LNB on the downlink, transverter on the uplink) ---
    // The only parts between the satellite frequency and the one read on the
    // rig. Kept here so the settings screen shows the IF while the LO is typed.
    val convRx: fr.f4ioz.satcombo.domain.Convertisseur =
        fr.f4ioz.satcombo.domain.Convertisseur.AUCUN,
    val convTx: fr.f4ioz.satcombo.domain.Convertisseur =
        fr.f4ioz.satcombo.domain.Convertisseur.AUCUN,
    /** The downlink converter feeds the CAT-controlled rig. */
    val convRxPoste: Boolean = false,
    /** The downlink converter feeds the SDR dongle. */
    val convRxCle: Boolean = true,
    val showSatConfig: Boolean = false,
    val favoritePasses: List<SatPass> = emptyList(),
    val favPassesLoading: Boolean = false,
    val livePosition: SatPosition? = null,
    val log: List<LogEntry> = emptyList(),
    val lastLogMs: Long = 0L,
    // --- operator identity + QRV photo overlay ---
    val callsign: String = "",
    /** Content of the "Extensions" settings field (unlock keywords). */
    val extensionsCode: String = "",
    /** Beta features unlocked for this operator (see Extensions). */
    val extensions: Set<String> = emptySet(),
    val photoShowCallsign: Boolean = true,
    val photoShowDate: Boolean = true,
    val photoShowGrids: Boolean = true,
    val photoShowCoords: Boolean = false,
    val photoShowSat: Boolean = false,
    val photoShowPolar: Boolean = false,
    val photoShowPass: Boolean = true,
    val photoPolarScale: Float = 1f,
    val photoSatLabelScale: Float = 1f,
    /** QTH altitude on the photo, when the phone knows it. */
    val photoShowAlt: Boolean = false,
    /** Callsign colour on the QRV photo (ARGB). */
    val photoCallColor: Int = 0xFFFFC65C.toInt(),
    /** Callsign size on the QRV photo, 1.0 = reference. */
    val photoCallScale: Float = 1f,
    /** True = the picture shows "JN18" only, false = "JN18fv". */
    val photoLoc4: Boolean = false,
    /** Distance (m) under which a neighbouring grid square is announced. */
    val nearGridMeters: Int = 100,
    /** Number of neighbouring squares printed on the QRV photo, nearest first. */
    val photoNearCount: Int = 4,
    /** Unit system: metric, imperial or nautical. */
    val units: String = fr.f4ioz.satcombo.data.Units.METRIC,
    /** Radiosonde listening frequency, Hz. */
    val sondeFreqHz: Long = 404_000_000L,
    val sondeSource: String = "SDR",
    /** Sonde model: "AUTO", "RS41", "M20" or "M10". */
    val sondeModel: String = "AUTO",
    /** Flag code shown before the callsign, empty = none. */
    val photoFlag: String = "",
    /**
     * QRV photo country map, in its holder: seven more flat fields tripped
     * `RegistresTest` (255-register limit, see [RotorUi]).
     */
    val carte: CarteUi = CarteUi(),
    /** Agenda appointments, re-read on every change from the screen. */
    val agenda: List<fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent> = emptyList(),
    /**
     * Downlink frequency imposed by a running agenda appointment, Hz. When set,
     * the displayed VFO comes from the announcement, not the catalogue, and
     * the pass screen says so.
     */
    val rxFromAgendaHz: Long? = null,
    /** True while the "your callsign is missing" prompt must be shown. */
    val askCallsign: Boolean = false,
    /** AMSAT status report being sent / just sent ("", "ok", "fail", "busy"). */
    val amsatSubmitState: String = "",
    // Which satellite the QRV photo is about. The photo screen can be reached
    // from anywhere, so the context is carried explicitly instead of being
    // guessed from whatever happens to be selected.
    val photoSatCat: Int? = null,
    val photoSatName: String = "",
    val photoTrack: List<Pair<Double, Double>> = emptyList(),
    val photoPassLabel: String = "",
    /** AOS of the pass the photo is about (epoch ms), and its max elevation. */
    val photoPassAosMs: Long = 0L,
    val photoPassElDeg: Int = 0,
    /**
     * Every pass of that satellite within reach, a day back and two days ahead.
     * The operator does not always shoot during the pass: the picture may be
     * taken while packing up, or prepared the evening before, so the pass has
     * to be pickable instead of guessed.
     */
    val photoPasses: List<SatPass> = emptyList(),
    /** Kept pictures (private storage), newest first. */
    val qrvPhotos: List<fr.f4ioz.satcombo.data.QrvPhoto> = emptyList(),
    /** The kept picture currently open in the photo screen. */
    val photoCurrentId: Long? = null,
    /**
     * True when the photo page was opened from a satellite the operator was
     * looking at. The last kept picture is still reopened so the page is not
     * blank, but its stamped satellite and pass must NOT come back with it —
     * otherwise the choice just made on the previous screen is undone.
     */
    val photoPinnedSat: Boolean = false,
    // --- Play Store update ---
    /** versionCode waiting on the Store, 0 when there is nothing to propose. */
    val updateCode: Int = 0,
    /** Last thing the update flow has to say — error, or "already up to date". */
    val updateMsg: String = "",
    // --- field sessions ("activations") ---
    val activations: List<fr.f4ioz.satcombo.data.Activation> = emptyList(),
    // --- timeline (rolling elevation curves of the favourites) ---
    val timelineHours: Int = 6,
    val timelineTracks: List<TimelineTrack> = emptyList(),
    val timelineLoading: Boolean = false,
    val timelineFromMs: Long = 0L,
    val catEnabled: Boolean = false,      // user wants CAT control
    val catConnected: Boolean = false,    // serial port open

    val catStatus: String = "",           // human-readable status/last error
    val catRadioDownlinkHz: Long? = null, // frequency actually read from the rig
    val catRadioUplinkHz: Long? = null,
    /** True when the software holds the rig's RX VFO. */
    val catRxDriven: Boolean = false,
    /** Track Doppler on receive too, not only on transmit. */
    val catRxDoppler: Boolean = true,
    /**
     * Mode read back from the rig ("USB", "LSB", "FM"…), null until asked.
     * Read back, not inferred from what we wrote: the mode also changes by
     * hand, and a wrong sideband on an inverting transponder is not heard —
     * it goes unnoticed.
     */
    val catRadioMode: String? = null,
    /** True when the rig's mode is not the one the satellite needs. */
    val catModeMismatch: Boolean = false,
    val amsatReports: Map<String, fr.f4ioz.satcombo.data.AmsatReport> = emptyMap(),
    val rigModel: String = "IC9700",
    val civAddress: Int = 0xA2,
    val civBaud: Int = 115200,
    /**
     * Which serial adapter carries the rig, when there are several. The driver
     * used to take the first device found: an SDR dongle plugged in before the
     * rig stole its place, and only unplug/replug gymnastics got out of it.
     */
    val civUsbIndex: Int = 0,
    /** Visible serial adapters, to choose from. */
    val catDevices: List<String> = emptyList(),
    /** Try neighbouring ports when the chosen one does not answer. */
    val civUsbAuto: Boolean = true,
    /**
     * Log of the last connection attempt, line by line: which port was opened,
     * whether it answered, and if not why. Without it, "I can't connect" has
     * no answer.
     */
    val catDiag: List<String> = emptyList(),
    /** CAT talks to an in-memory rig instead of a cable. */
    val catSimulated: Boolean = false,
    /** CAT frame log is running. */
    val catMonitor: Boolean = false,
    /** All rotor state, grouped: see [RotorUi]. */
    val rotor: RotorUi = RotorUi(),
    /** All QO-100 screen state, grouped: see [Qo100Ui]. */
    val qo100: Qo100Ui = Qo100Ui(),
    // Dual FT-817 (full-duplex pair): FTDI serials for the RX/TX rigs, CAT baud,
    // and the currently visible USB adapters for the assignment UI.
    val ft817RxSerial: String = "",
    val ft817TxSerial: String = "",
    val ft817Baud: Int = 4800,
    val usbDevices: List<fr.f4ioz.satcombo.cat.UsbSerialInfo> = emptyList(),
    val ctcssTenthHz: Int = 0,
    val ctcssAuto: Boolean = true,
    val catTestSendAlways: Boolean = true,   // CAT tunes even below the horizon (setting, on by default)
    // Test bench: the start-of-pass sequence played against an in-memory rig
    // (to assert something without a radio), and the frame journal (to see
    // what preceded a refusal when a pass goes wrong).
    val benchRunning: Boolean = false,
    val benchReport: String = "",
    val benchOk: Boolean = false,
    val benchSteps: List<String> = emptyList(),
    val trail: List<Pair<Double, Double>> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    val tleCacheAgeMs: Long? = null,
    val error: String? = null,
    val visualOnly: Boolean = false,
    val satActiveOnly: Boolean = false
) {

    // --- The 45 rotor fields, read under their original names. See
    //     [RotorUi]: stored elsewhere to stay under the 255-register limit.
    // ---------------------------------------------------------------
    val rotorEnabled: Boolean get() = rotor.rotorEnabled
    val rotorConnected: Boolean get() = rotor.rotorConnected
    val rotorStatus: String get() = rotor.rotorStatus
    val rotorType: String get() = rotor.rotorType
    val rotorBaud: Int get() = rotor.rotorBaud
    val rotorUsbIndex: Int get() = rotor.rotorUsbIndex
    val rotorPort: Int get() = rotor.rotorPort
    val rotorMaxAz: Int get() = rotor.rotorMaxAz
    val rotorMaxEl: Int get() = rotor.rotorMaxEl
    val rotorAzStop: String get() = rotor.rotorAzStop
    val rotorAzFromStop: Boolean get() = rotor.rotorAzFromStop
    val rotorMaxError: Int get() = rotor.rotorMaxError
    val rotorMinEl: Int get() = rotor.rotorMinEl
    val rotorFlip: Boolean get() = rotor.rotorFlip
    val rotorAzOnly: Boolean get() = rotor.rotorAzOnly
    val rotorPreAos: Int get() = rotor.rotorPreAos
    val rotorPrePositioning: Boolean get() = rotor.rotorPrePositioning
    val rotorPark: Boolean get() = rotor.rotorPark
    val rotorParkAz: Int get() = rotor.rotorParkAz
    val rotorParkEl: Int get() = rotor.rotorParkEl
    val rotorSimulated: Boolean get() = rotor.rotorSimulated
    val rotorPosAz: Double? get() = rotor.rotorPosAz
    val rotorPosEl: Double? get() = rotor.rotorPosEl
    val rotorFlipped: Boolean get() = rotor.rotorFlipped
    val rotorMoves: Int get() = rotor.rotorMoves
    val rotorDevices: List<String> get() = rotor.rotorDevices
    val rotorCoverage: Double? get() = rotor.rotorCoverage
    val rotorLink: String get() = rotor.rotorLink
    val rotorHost: String get() = rotor.rotorHost
    val rotorDeadband: Int get() = rotor.rotorDeadband
    val rotorSim: Boolean get() = rotor.rotorSim
    val rotorTargetAz: Double? get() = rotor.rotorTargetAz
    val rotorTargetEl: Double? get() = rotor.rotorTargetEl
    val rotorActualAz: Double? get() = rotor.rotorActualAz
    val rotorActualEl: Double? get() = rotor.rotorActualEl
    val rotorAimAz: Double? get() = rotor.rotorAimAz
    val rotorAimEl: Double? get() = rotor.rotorAimEl
    val rotorOutOfRange: Boolean get() = rotor.rotorOutOfRange
    val rotorDiag: List<String> get() = rotor.rotorDiag
    val rotorAutoPort: Boolean get() = rotor.rotorAutoPort
    val rotorManualAz: Int get() = rotor.rotorManualAz
    val rotorManualEl: Int get() = rotor.rotorManualEl
    val rotorLastReply: String get() = rotor.rotorLastReply
    val rotorLastSent: String get() = rotor.rotorLastSent
    val rotorErrorDeg: Double get() = rotor.rotorErrorDeg

    /** Copies the state, changing only the rotor block. */
    fun rot(f: RotorUi.() -> RotorUi): UiState = copy(rotor = rotor.f())

    /** Copies the state, changing only the QO-100 block. */
    fun qo(f: Qo100Ui.() -> Qo100Ui): UiState = copy(qo100 = qo100.f())

    val filteredSatellites: List<TleEntry>
        get() {
            val q = query.trim()
            var list = satellites
            if (q.isNotEmpty()) {
                val asNum = q.toIntOrNull()
                list = list.filter {
                    it.name.contains(q, ignoreCase = true) ||
                            (asNum != null && it.catalogNumber.toString().startsWith(q))
                }
            }
            if (satActiveOnly && amsatReports.isNotEmpty()) {
                list = list.filter { sat ->
                    amsatMatch(sat.name, amsatReports, sat.catalogNumber)?.recent == fr.f4ioz.satcombo.data.AmsatStatus.ACTIVE
                }
            }
            return list
        }
}

/**
 * Square status across all sources. A LoTW confirmation wins (the only one
 * valid for awards), then the online log, then LoTW worked-only squares.
 * One rule for the square map and the PC control desk.
 */
fun etatCarre(ui: UiState, carre: String): fr.f4ioz.satcombo.data.CarnetEnLigne.Etat? {
    val k = carre.uppercase().take(4)
    if (k in ui.carnet.lotwConfirmes)
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.CONFIRME
    ui.carnet.carres[carre.uppercase()]?.let { return it }
    if (k in ui.carnet.lotwTravailles)
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.TRAVAILLE
    // LoTW answered and does not know this square: still needed.
    if (ui.carnet.lotwTravailles.isNotEmpty())
        return fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.JAMAIS
    return null
}

/** Rig models of the FT-817 + IC-705 pair: which one receives. */
const val FT817_IC705 = "FT817_IC705"   // IC-705 receives, FT-817 transmits
const val IC705_FT817 = "IC705_FT817"   // IC-705 transmits, FT-817 receives
const val THD72 = "THD72"               // Kenwood TH-D72, full duplex: one band RX, the other TX

/** Normalize a designator so AO-07 == AO-7, FO-029 == FO-29. */
private val DESIGNATEUR = Regex("""([A-Z]+)-0*(\d+)""")

fun normalizeDesignator(s: String): String {
    val up = s.uppercase().trim().substringBefore(" (").substringBefore("_").trim()
    return DESIGNATEUR.replace(up) { m ->
        "${m.groupValues[1]}-${m.groupValues[2]}"
    }
}

/**
 * AMSAT reports by normalized name, built once per report list. Matching
 * ~1700 satellites (SatNOGS) by scanning the reports and normalizing each
 * one every time made the satellite list crawl with « Active today ».
 */
private class IndexAmsat(val source: Map<String, fr.f4ioz.satcombo.data.AmsatReport>) {
    val parNom = LinkedHashMap<String, fr.f4ioz.satcombo.data.AmsatReport>().also { m ->
        source.values.forEach { m.putIfAbsent(normalizeDesignator(it.name), it) }
    }
    val iss = source.values.firstOrNull { it.name.uppercase().contains("ISS") }
}

@Volatile private var indexAmsat: IndexAmsat? = null

private fun indexDe(reports: Map<String, fr.f4ioz.satcombo.data.AmsatReport>): IndexAmsat =
    indexAmsat?.takeIf { it.source === reports } ?: IndexAmsat(reports).also { indexAmsat = it }

/**
 * Catalogue number → AMSAT name, from AMSAT's bulletin ([fr.f4ioz.satcombo.data.NomsAmsat]).
 * Set by the view model; read by [amsatMatch], also from [UiState].
 */
@Volatile var nomsAmsat: Map<Int, String> = emptyMap()

/**
 * Match a satellite against AMSAT reports (shared by VM and UiState). By its
 * catalogue number first, through its AMSAT name — the source may call it
 * "OSCAR 7" or "ISS (ZARYA)" — then by the name it has here.
 */
fun amsatMatch(
    satName: String, reports: Map<String, fr.f4ioz.satcombo.data.AmsatReport>, catnum: Int? = null
): fr.f4ioz.satcombo.data.AmsatReport? {
    if (reports.isEmpty()) return null
    catnum?.let { nomsAmsat[it] }?.takeIf { it != satName }?.let { alias ->
        amsatMatch(alias, reports)?.let { return it }
    }
    val key = satName.uppercase().trim()
    reports[key]?.let { return it }
    val index = indexDe(reports)
    val norm = normalizeDesignator(satName)
    index.parNom[norm]?.let { return it }
    if (norm.contains("ISS")) index.iss?.let { return it }
    return null
}


/** One mutual-visibility window between two stations for a satellite. */
data class SkedWindow(val startMs: Long, val endMs: Long, val elA: Int, val elB: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TleRepository()
    private val predictor = PassPredictor()

    private val locationProvider = LocationProvider(app)
    private val favStore = FavoritesStore(app)
    private val srcStore = SourcesStore(app)
    private val settings = SettingsStore(app)
    private val logStore = LogStore(app)
    private val activationStore = fr.f4ioz.satcombo.data.ActivationStore(app)
    private val qrvPhotoStore = fr.f4ioz.satcombo.data.QrvPhotoStore(app)
    private val cat = fr.f4ioz.satcombo.cat.CivController(app)
    private val ft817 = fr.f4ioz.satcombo.cat.Ft817Pair(app)
    /**
     * Rotor driver, chosen on connect. Null while nothing is connected: the
     * only way to be sure no command reaches a mast we have not opened.
     */
    private var rotorDriver: fr.f4ioz.satcombo.rotor.RotorDriver? = null
    /** Control goes through the FT-817 pair — two rigs, or one for TX only. */
    private val isPairRig: Boolean
        get() = _ui.value.rigModel in fr.f4ioz.satcombo.cat.Postes.MODELES || _ui.value.rigModel == THD72

    /** The pair is a TH-D72: its two bands on one cable. */
    private val isThd72: Boolean get() = _ui.value.rigModel == THD72

    /** FT-817 + IC-705: the pair whose two rigs speak different protocols. */
    private val isPaireMixte: Boolean
        get() = fr.f4ioz.satcombo.cat.Postes.mixte(_ui.value.rigModel)

    /** Gives each side of the pair its protocol, from the rig model. */
    private fun configurePaire() {
        if (isThd72) ft817.configureThd72(settings.thd72BandeTx)
        else ft817.configure(
            rxIc705 = fr.f4ioz.satcombo.cat.Postes.rxIc705(_ui.value.rigModel),
            txIc705 = fr.f4ioz.satcombo.cat.Postes.txIc705(_ui.value.rigModel))
    }

    /**
     * A mixed pair assigns its cables by itself: the one answering in CI-V is
     * the IC-705, the other the FT-817. Nothing to pick, nothing to swap.
     */
    private suspend fun attribuePaireMixte() {
        val cles = ft817.listDevices().filter { it.hasPermission }.map { it.cle }
        val icom = ft817.repereIc705(cles, _ui.value.catUi.ic705Baud) ?: return
        val yaesu = cles.firstOrNull { it != icom }
        val icomRecoit = _ui.value.rigModel == FT817_IC705
        poseRoleFt817(icom, if (icomRecoit) "RX" else "TX")
        if (yaesu != null) poseRoleFt817(yaesu, if (icomRecoit) "TX" else "RX")
    }

    /**
     * A single rig on TX (FT-817 or IC-705), receiving on the SDR dongle.
     *
     * The loop already knows how to drive one chain when the other is out of
     * reach (that is how QO-100 works). `descenteAuPoste` answers no by
     * construction, so the listening point picked on the waterfall drives TX:
     * tuning is done by finger on the spectrum and the rig mirrors it —
     * reversed on an inverting transponder.
     */
    private val isTxOnlyRig: Boolean get() = fr.f4ioz.satcombo.cat.Postes.emetSeul(_ui.value.rigModel)
    private val satConfigStore = SatConfigStore(app)
    private val skedRepo = SkedRepository()
    private val potaRepo = PotaRepository(app)
    private val geocoder = Geocoder()
    private val tleCache = TleCache(app)
    private val txRepo = TransmittersRepository(app)

    /**
     * Silent satellites (every SatNOGS transmitter dead) and whether the
     * satellite list shows them anyway. Flows of their own, out of `UiState`
     * (255-register limit): only the satellite list reads them.
     */
    private val _satInactifs = MutableStateFlow<Set<Int>>(emptySet())
    val satInactifs: StateFlow<Set<Int>> = _satInactifs
    private val _montreInactifs = MutableStateFlow(settings.montreInactifs)
    val montreInactifs: StateFlow<Boolean> = _montreInactifs

    private val amsatRepo = fr.f4ioz.satcombo.data.AmsatStatusRepository(app)
    /** Catalogue number → AMSAT name, for the status whatever the source (declared before `init`, which reads it). */
    private val nomsAmsatStore = fr.f4ioz.satcombo.data.NomsAmsat(app)
    /** A satellite other than the ISS was picked while SSTV ISS is armed: the screen warns (before `init`: `select` reads it). */
    val sstvIssAvertissement = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** A word for the SSTV page (no ISS, no pass…), not in `UiState`. */
    val sstvIssMessage = kotlinx.coroutines.flow.MutableStateFlow("")

    private val _ui = MutableStateFlow(
        UiState(
            favorites = favStore.load(),
            enabledSources = srcStore.load(),
            sdrSstv = settings.sdrSstv,
            sdrRecord = settings.sdrRecord,
            sdrAudio = settings.sdrAudio,
            sdrAgc = settings.sdrAgc,
            sdrPpm = settings.sdrPpm,
            sdrMode = settings.sdrMode,
            sdrBandwidthHz = settings.sdrBandwidthHz,
            sdrSquelchDb = settings.sdrSquelchDb,
            sdrSpanHz = settings.sdrSpanHz,
            accord = AccordUi(
                moletteCible = settings.moletteCible,
                macroCodeD = settings.macroCodeD,
                macroActionD = settings.macroActionD,
                macroCodeA = settings.macroCodeA,
                macroCodeB = settings.macroCodeB,
                macroCodeC = settings.macroCodeC,
                macroCibleA = settings.macroCibleA,
                macroCibleB = settings.macroCibleB,
                macroCibleC = settings.macroCibleC,
                loupe = settings.sdrLoupe,
                loupeSpanHz = settings.sdrLoupeSpanHz,
                vernier = settings.sdrVernier,
                vernierHzParCm = settings.sdrVernierHzParCm,
                calageVoix = settings.sdrCalageVoix),
            express = CarnetExpress(
                mainGauche = settings.clavierMainGauche,
                disposition = settings.clavierDisposition),
            qo100 = Qo100Ui(memoires = settings.qo100Memoires,
                chaines = settings.qo100Chaines, chaine = settings.qo100Chaine,
                materiels = fr.f4ioz.satcombo.domain.MaterielRx.lit(settings.materielsRx),
                materielPoste = settings.materielPoste,
                materielCle = settings.materielCle,
                sansBride = settings.qo100SansBride),
            carnet = CarnetUi(
                lotwCall = settings.lotwCall,
                peindre = settings.peindreCarres,
                lotwMdp = settings.lotwMdp,
                qrzUser = settings.qrzUser,
                qrzMdp = settings.qrzMdp,
                url = settings.carnetUrl,
                cle = settings.carnetCle,
                slug = settings.carnetSlug,
                profil = settings.carnetProfil,
                filtre = settings.carnetFiltre,
                radio = settings.carnetRadio,
                radioNom = settings.carnetRadioNom,
                auto = settings.carnetAuto),
            catUi = CatUi(
                ic705Baud = settings.ic705Baud,
                liseret = settings.liseréEmission,
                txSuitVite = settings.txSuitVite,
                sondeMs = settings.sondeTxMs,
                holdMs = settings.catHoldMs,
                txVfoShift = settings.catTxVfoShift),
            sdrDeemph = settings.sdrDeemph,
            sdrDopplerTrack = settings.sdrDopplerTrack,
            convRx = convRxDepuisReglages(),
            convTx = convTxDepuisReglages(),
            convRxPoste = settings.convRxPoste,
            convRxCle = settings.convRxCle,
            locationMode = settings.locationMode,
            manualLocator = settings.manualLocator,
            manualLat = settings.manualLat.takeIf { !it.isNaN() },
            manualLon = settings.manualLon.takeIf { !it.isNaN() },
            aimMode = settings.aimMode,
            showAimModeChips = settings.showAimModeChips,
            compassHeadUp = settings.compassHeadUp,
            compassStyle = settings.compassStyle,
            statusSource = settings.statusSource,
            tleCacheHours = settings.tleCacheHours,
            darkTheme = settings.darkTheme,
            // Without this the picker falls back to "Dark" on every launch,
            // whatever palette is applied: the palette was restored, its index
            // was not.
            themeIndex = settings.themeIndex,
            moletteVfo = settings.moletteVfo,
            molettePasHz = settings.molettePasHz,
            uniformUi = settings.uniformUi,
            uiScaleStep = settings.uiScaleStep,
            uiFollowSystemFont = settings.uiFollowSystemFont,
            language = settings.language,
            rigModel = settings.rigModel,
            civAddress = settings.civAddress,
            civBaud = settings.civBaud,
            civUsbIndex = settings.civUsbIndex,
            civUsbAuto = settings.civUsbAuto,
            catSimulated = settings.catSimulated,
            catMonitor = settings.catMonitor,
            catRxDoppler = settings.catRxDoppler,
            catTestSendAlways = settings.catSousHorizon,
            ft817RxSerial = settings.ft817RxSerial,
            ft817TxSerial = settings.ft817TxSerial,
            ft817Baud = settings.ft817Baud,
            ctcssTenthHz = settings.ctcssTenthHz,
            ctcssAuto = settings.ctcssAuto,
            mapStyle = settings.mapStyle,
            skedsEnabled = settings.skedsEnabled,
            skedsMutualOnly = settings.skedsMutualOnly,
            logTaps = settings.logTaps,
            log = logStore.load(),
            callsign = settings.callsign,
            extensionsCode = settings.extensionsCode,
            extensions = fr.f4ioz.satcombo.data.Extensions.unlocked(
                settings.callsign, settings.extensionsCode),
            photoShowCallsign = settings.photoShowCallsign,
            photoShowDate = settings.photoShowDate,
            photoShowGrids = settings.photoShowGrids,
            photoShowCoords = settings.photoShowCoords,
            photoShowSat = settings.photoShowSat,
            photoShowPolar = settings.photoShowPolar,
            photoShowPass = settings.photoShowPass,
            photoPolarScale = settings.photoPolarScale,
            photoSatLabelScale = settings.photoSatLabelScale,
            photoShowAlt = settings.photoShowAlt,
            photoCallColor = settings.photoCallColor,
            photoCallScale = settings.photoCallScale,
            photoLoc4 = settings.photoLoc4,
            nearGridMeters = settings.nearGridMeters,
            photoNearCount = settings.photoNearCount,
            units = settings.units,
            sondeFreqHz = settings.sondeFreqHz,
            sondeSource = settings.sondeSource,
            sondeModel = settings.sondeModel,
            photoFlag = settings.photoFlag,

            carte = CarteUi(
                flagRight = settings.photoFlagRight,
                affichee = settings.photoShowCarte,
                potaAffiche = settings.photoShowPota,
                taille = settings.photoCarteTaille,
                contenu = settings.photoCarteContenu,
                potaTaille = settings.photoPotaTaille,
                potaMonte = settings.photoPotaMonte,
                couleur = settings.photoCarteCouleur,
                bandeauAccueil = settings.potaBandeauAccueil,
                fondu = settings.photoCarteFondu,
                potaNomAffiche = settings.photoPotaNom,
                qrgAffiche = settings.photoShowQrg,
                qrgScale = settings.photoQrgScale,
                qrgTexte = settings.photoQrgTexte,
                passScale = settings.photoPassScale,
                fondUni = settings.photoFondUni,
                x = settings.photoCarteX,
                y = settings.photoCarteY,
                remplissage = settings.photoCarteRemplissage),
            agenda = fr.f4ioz.satcombo.data.AgendaStore.load(getApplication()),
            askCallsign = settings.callsign.isBlank() && !settings.callsignPromptOff,
            qrvPhotos = qrvPhotoStore.load(),
            pastPassHours = settings.pastPassHours,
            activations = activationStore.load(),
            skedsToken = settings.skedsToken,
            skedsAuthed = settings.skedsToken.isNotBlank(),
            minElevDeg = settings.minElevDeg,
            useUtc = settings.useUtc,
            locatorDetails = settings.locatorDetails,
            recorderEnabled = settings.recorderEnabled,
            recorderSource = settings.recorderSource,
            recorderUnprocessed = settings.recorderUnprocessed,
            monitorSpectre = settings.monitorSpectre,
            monitorSpeaker = settings.monitorSpeaker,
            sstvEnabled = settings.sstvEnabled,
            aptEnabled = settings.aptEnabled,
            rxImageMode = settings.rxImageMode,
            recordingsTreeUri = settings.recordingsTreeUri,
            compassTraceColor = settings.compassTraceColor,
            needleFarColor = settings.needleFarColor,
            needleNearColor = settings.needleNearColor,
            needleCloseColor = settings.needleCloseColor,
            bubbleFarColor = settings.bubbleFarColor,
            bubbleNearColor = settings.bubbleNearColor,
            bubbleCloseColor = settings.bubbleCloseColor,
            compassTraceWidth = settings.compassTraceWidth,
            ringAzNearColor = settings.ringAzNearColor,
            ringAzCloseColor = settings.ringAzCloseColor,
            ringElNearColor = settings.ringElNearColor,
            ringElCloseColor = settings.ringElCloseColor,
            potaEnabled = settings.potaEnabled,
            potaRadiusKm = settings.potaRadiusKm,
            notifyEnabled = settings.notifyEnabled,
            notifyLeadMin = settings.notifyLeadMin,
            notifyMode = settings.notifyMode,
            notifiedPassKeys = settings.notifiedPassKeys,
            rotor = RotorUi(
                boussoleSource = settings.boussoleSource,
                boussoleAdresse = settings.boussoleAdresse,
                boussoleNom = settings.boussoleNom,
                boussoleCalage = settings.boussoleCalage,
                boussoleConvention = settings.boussoleConvention,
                boussoleReleves = settings.boussoleReleves,
                boussoleFleche = settings.boussoleFleche,
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
                rotorMaxError = settings.rotorMaxError)
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // The demo access point survives a restart, so it need not be retyped
        // before each session.
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(
            settings.demoSsid, settings.demoMotDePasse)
        // --- SSTV ISS: when the recorder disarms (unticked, or past the last pass), the ISS gets its transmitter back ---
        viewModelScope.launch {
            var arme = false
            fr.f4ioz.satcombo.audio.RecorderService.fenetres.collect { f ->
                if (arme && f.isEmpty()) runCatching { sstvIssRendTransmetteur() }
                arme = f.isNotEmpty()
            }
        }
        // --- APRS, the fun side: who we are for the trophies, the cheers, the ISS beacon ---
        fr.f4ioz.satcombo.aprs.AprsHub.joueur = {
            Triple(settings.callsign, _ui.value.observer?.latDeg, _ui.value.observer?.lonDeg)
        }
        viewModelScope.launch {
            fr.f4ioz.satcombo.aprs.AprsHub.evenements.collect { runCatching { aprsFete(it) } }
        }
        viewModelScope.launch {
            while (true) {
                runCatching { aprsBaliseTic() }; runCatching { aprsKissTic() }; runCatching { aprsAudioTic() }
                delay(10_000)
            }
        }
        // --- control desk ---
        // The only gestures a PC can trigger are placed here; the server can
        // call nothing else, so the surface exposed to the LAN reads at a glance.
        fr.f4ioz.satcombo.demo.PontCommande.ajouteQso = { call, loc, rse, rsr ->
            val sat = _ui.value.selected
            if (sat == null || call.isBlank()) false
            else {
                ajouteContactDirect(sat, call, loc, rse, rsr, "COMMANDE")
                true
            }
        }
        fr.f4ioz.satcombo.demo.PontCommande.enregistre = { on ->
            if (on != _ui.value.recording) toggleRecording()
        }
        fr.f4ioz.satcombo.demo.PontCommande.satellites = {
            // **Favourites only.** The full list has dozens of satellites never
            // worked; scrolling through them mid-pass wastes time. The star is
            // already how the operator says which ones matter.
            val u = _ui.value
            val favoris = u.satellites.filter { it.catalogNumber in u.favorites }
            // No star set: the whole list beats an empty menu, which would make
            // switching satellite impossible.
            (if (favoris.isEmpty()) u.satellites else favoris).map { it.name }
        }
        fr.f4ioz.satcombo.demo.PontCommande.propose = { saisie ->
            fr.f4ioz.satcombo.domain.Indicatifs.suggestions(
                saisie, _ui.value.express.memoire, System.currentTimeMillis(),
                _ui.value.selected?.name.orEmpty()
            ).map {
                fr.f4ioz.satcombo.demo.PontCommande.Proposition(
                    it.indicatif, it.locatorPrincipal, it.nom, it.contacts)
            }
        }
        fr.f4ioz.satcombo.demo.PontCommande.chercheQrz = { call ->
            // **Open the session before searching.** `cherche()` does not log
            // in by itself: without a session key it returns "not connected",
            // and the control desk never showed an identity.
            // Reconnect only when the key is missing: QRZ also counts logins.
            val f = runCatching {
                val c = _ui.value.carnet
                var fiche = qrz.cherche(call.trim().uppercase())
                if (fiche.erreur.contains("connect", true) &&
                    c.qrzUser.isNotBlank() && c.qrzMdp.isNotBlank()) {
                    qrz.connecte(c.qrzUser, c.qrzMdp)
                    fiche = qrz.cherche(call.trim().uppercase())
                }
                fiche
            }.getOrNull()
            fr.f4ioz.satcombo.demo.PontCommande.Fiche(
                f?.indicatif.orEmpty(), f?.carre.orEmpty(), f?.nom.orEmpty(),
                f?.prenom.orEmpty(), f?.qth.orEmpty(), f?.pays.orEmpty(),
                f?.erreur ?: "QRZ indisponible")
        }
        fr.f4ioz.satcombo.demo.PontCommande.choisitSatellite = { nom ->
            val t = _ui.value.satellites.firstOrNull { it.name.equals(nom, true) }
            if (t == null) false else { select(t); true }
        }
        // The last 24 hours of the log: what may still need fixing, and the
        // automatic upload's state for each contact.
        fr.f4ioz.satcombo.demo.PontCommande.journal = {
            val maintenant = System.currentTimeMillis()
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            _ui.value.log.filter { it.callsign.isNotBlank() && maintenant - it.timeMs < 86_400_000L }
                .take(40).map { e ->
                    val etat = etatEnvoi(e, maintenant)
                    fr.f4ioz.satcombo.demo.PontCommande.Ligne(
                        e.timeMs, hm.format(java.util.Date(e.timeMs)), e.callsign, e.theirLocator,
                        e.satName, e.rstSent, e.rstRcvd, etat.name.lowercase(),
                        if (etat == fr.f4ioz.satcombo.domain.EnvoiAuto.Etat.ATTENTE) resteEnvoiS(e) else 0L,
                        refusEnvoi(e.timeMs))
                }
        }
        fr.f4ioz.satcombo.demo.PontCommande.modifie = { t, call, loc, rse, rsr ->
            val e = _ui.value.log.firstOrNull { it.timeMs == t }
            if (e == null || call.isBlank()) false
            else { updateLogEntry(t, call, loc, e.note, e.mode, rse, rsr); true }
        }
        fr.f4ioz.satcombo.demo.PontCommande.supprime = { t ->
            if (_ui.value.log.none { it.timeMs == t }) false
            else {
                modifies.remove(t); refusAuto.remove(t); reessai.remove(t)
                deleteLogEntry(t); true
            }
        }
        fr.f4ioz.satcombo.demo.PontCommande.retiens = { t, on ->
            if (_ui.value.log.none { it.timeMs == t }) false else { retiensContact(t, on); true }
        }
        fr.f4ioz.satcombo.demo.PontCommande.infos = { call, loc -> infosSaisie(call, loc) }
        fr.f4ioz.satcombo.demo.PontCommande.station = { stationPourPupitre() }
        fr.f4ioz.satcombo.demo.PontCommande.choisitProfil = { id ->
            if (_ui.value.carnet.profilsListe.none { it.id == id }) false
            else { setCarnetProfil(id); true }
        }
        fr.f4ioz.satcombo.demo.PontCommande.releveProfils = { relevProfils() }

        // The monitor needs the context to pick its output: without it, it
        // cannot force the speaker when a USB sound card is present.
        fr.f4ioz.satcombo.audio.MoniteurAudio.contexte =
            getApplication<android.app.Application>().applicationContext
        fr.f4ioz.satcombo.demo.ServeurDemo.versionApp = runCatching {
            val a = getApplication<android.app.Application>()
            a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        fr.f4ioz.satcombo.demo.ServeurDemo.nomStation =
            settings.callsign.ifBlank { "SatMe" }
        fr.f4ioz.satcombo.ui.theme.applyTheme(settings.themeIndex)
        fr.f4ioz.satcombo.ui.theme.applyUiScale(
            settings.uniformUi, settings.uiScaleStep, settings.uiFollowSystemFont)
        fr.f4ioz.satcombo.i18n.I18n.apply(settings.language, java.util.Locale.getDefault().language)
        TleRefreshWorker.schedule(app)
        _ui.value = _ui.value.copy(
            potaRegionLabel = potaRepo.downloadedRegion,
            potaCount = potaRepo.downloadedCount)
        // **Demo telemetry has its own loop.** Published from the satellite
        // tracking loop, it only ran when a satellite was selected **and**
        // tracked: the audience saw a correctly served but empty page. Here it
        // runs whenever broadcasting is on, whatever the screen. One-second
        // period; nothing is computed when nobody broadcasts.
        viewModelScope.launch {
            while (true) {
                if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
                    runCatching { publieDemo() }
                }
                runCatching { relaieRadio() }
                runCatching { envoieAuto() }
                kotlinx.coroutines.delay(1000)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                _ui.value = _ui.value.copy(nowMs = System.currentTimeMillis())
                delay(1000)
            }
        }
        // Mirror the foreground recorder service state into the UI.
        viewModelScope.launch {
            fr.f4ioz.satcombo.audio.RecorderService.state.collect { rs ->
                val was = _ui.value.recording
                _ui.value = _ui.value.copy(
                    recording = rs.recording, recordStartMs = rs.startMs,
                    recordFileName = rs.fileName, recordAutoStopMs = rs.autoStopMs)
                if (was && !rs.recording && rs.fileName != null) {
                    android.widget.Toast.makeText(getApplication(),
                        tf("recorded_toast", rs.fileName), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        chargeNomsAmsat()
        refreshAmsatStatus()
        chargeInactifs()
    }

    fun setMontreInactifs(on: Boolean) {
        settings.montreInactifs = on
        _montreInactifs.value = on
    }

    private fun chargeInactifs() {
        viewModelScope.launch { txRepo.inactifs()?.let { _satInactifs.value = it } }
    }

    /** Fetch the AMSAT operator-reported status (who's actually being heard). */
    fun refreshAmsatStatus(force: Boolean = false) {
        // Show cached status instantly (offline-friendly), then refresh.
        val cached = amsatRepo.loadCache()
        if (cached.isNotEmpty() && _ui.value.amsatReports.isEmpty())
            _ui.value = _ui.value.copy(amsatReports = cached)
        viewModelScope.launch {
            val reports = runCatching { amsatRepo.get(force) }.getOrDefault(emptyMap())
            if (reports.isNotEmpty()) _ui.value = _ui.value.copy(amsatReports = reports)
        }
    }

    /** Operator-reported status for a satellite, by its number when known, else its name. */
    fun amsatFor(satName: String, catnum: Int? = null): fr.f4ioz.satcombo.data.AmsatReport? {
        val reports = _ui.value.amsatReports
        if (reports.isEmpty()) return null
        return amsatMatch(satName, reports, catnum)
    }

    /**
     * The AMSAT names table: from disk at once, downloaded again when a week
     * old. The reports map is replaced afterwards so lists match again.
     */
    private fun chargeNomsAmsat() {
        nomsAmsat = nomsAmsatStore.charge()
        if (nomsAmsatStore.aJour() && nomsAmsat.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            nomsAmsat = nomsAmsatStore.rafraichit(settings.serveurGp, settings.serveurGpSeul)
            // Satellites already loaded take their short name too, and their passes with it.
            val avant = _ui.value.satellites
            val renommes = avant.map(::nomCourt)
            _ui.value = _ui.value.copy(amsatReports = HashMap(_ui.value.amsatReports))
            if (renommes != avant) {
                _ui.value = _ui.value.copy(satellites = renommes.sortedBy { it.name })
                withContext(Dispatchers.Main) { computeFavoritePasses() }
            }
        }
    }

    /**
     * The short AMSAT name when known ("AO-07", "ISS"), whatever the source
     * called it ("OSCAR 7", "ISS (ZARYA)"): shorter on screen, and the same
     * name from one source to another.
     */
    private fun nomCourt(e: TleEntry): TleEntry =
        nomsAmsat[e.catalogNumber]?.takeIf { it != e.name }?.let { e.copy(name = it) } ?: e

    private suspend fun resolveObserver(): Observer =
        if (_ui.value.locationMode == LocationMode.MANUAL) {
            val loc = _ui.value.manualLocator.trim().uppercase()
            val la = _ui.value.manualLat; val lo = _ui.value.manualLon
            // A point picked on the map wins over the centre of the square, but
            // only while it still belongs to the locator shown in the field:
            // typing a new locator by hand must not keep the old pin.
            if (la != null && lo != null && Maidenhead.fromLatLon(la, lo).startsWith(loc))
                Observer(la, lo, 50.0, Maidenhead.fromLatLon(la, lo))
            else {
                val ll = Maidenhead.toLatLon(loc)
                if (ll != null) Observer(ll.first, ll.second, 50.0, loc)
                else locationProvider.defaultObserver
            }
        } else {
            runCatching { locationProvider.startup() }.getOrDefault(locationProvider.defaultObserver)
                .let { it.copy(name = "GPS · " + Maidenhead.fromLatLon(it.latDeg, it.lonDeg)) }
        }

    fun bootstrap(force: Boolean = false) {
        // Cache-first startup: reuse the on-disk orbital elements when they are
        // younger than the user-set max age (Settings → Sources). The network is
        // only hit when the cache is stale, missing, from a different source
        // set, or the user forces a refresh. 0 h = always re-download.
        val ids = _ui.value.enabledSources
        // The cache is tagged with what was downloaded (AMSAT's TLE with its
        // JSON): a cache written before that companion existed is redone once.
        val idsCache = Sources.aTelecharger(ids).map { it.id }.toSet()
        val maxAgeMs = settings.tleCacheHours * 3600_000L
        val age = tleCache.ageMs()
        val cacheFresh = age != null && maxAgeMs > 0 && age <= maxAgeMs &&
            tleCache.cachedSources() == idsCache
        if (!force && cacheFresh && _ui.value.satellites.isNotEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val obs = resolveObserver()
            // Through the SatMe GP server when one is set, each source's own
            // address as the fallback.
            val groupes = Sources.aTelecharger(ids).map {
                fr.f4ioz.satcombo.data.ServeurGp.adresses(settings.serveurGp, it, settings.serveurGpSeul)
            }
            var sats = emptyList<TleEntry>()
            var cacheAge: Long? = null
            var err: String? = null
            if (!force && cacheFresh) {
                // Instant, offline path.
                val raw = withContext(Dispatchers.IO) { tleCache.loadRaw() }
                if (raw != null) sats = runCatching { repo.parse(raw) }.getOrDefault(emptyList())
            }
            if (sats.isEmpty()) {
                sats = runCatching { repo.fetchGroupesSecours(groupes) }.getOrDefault(emptyList())
                if (sats.isNotEmpty()) {
                    tleCache.save(sats, idsCache)
                } else {
                    // Download failed: fall back to the cache whatever its age.
                    val raw = withContext(Dispatchers.IO) { tleCache.loadRaw() }
                    if (raw != null) {
                        sats = repo.parse(raw)
                        cacheAge = tleCache.ageMs()
                        if (tleCache.cachedSources() != idsCache)
                            err = t("offline_other_sources")
                    } else {
                        err = t("tle_download_failed")
                    }
                }
            }
            _ui.value = _ui.value.copy(
                loading = false,
                observer = obs,
                satellites = sats.map(::nomCourt).sortedBy { it.name },
                tleCacheAgeMs = cacheAge,
                error = err
            )
            qthCalculLat = obs?.latDeg
            qthCalculLon = obs?.lonDeg
            dernierRecalculMs = System.currentTimeMillis()
            computeFavoritePasses()
            refreshPota()
            refreshSkeds()

            // Tracking starts here, with the app, not when the map first opens.
            startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        }
    }

    // ---------- settings ----------

    // ---------- the way back ----------

    /** Pages to return to, out of `UiState` (255-register limit). */
    private val chemin = fr.f4ioz.satcombo.ui.Chemin()

    /** Records the page being left before opening [vers]. */
    private fun va(vers: Screen, section: String? = null) =
        chemin.va(_ui.value.screen, _ui.value.settingsSection, vers, section)

    /** State after closing the current page: the one it was opened from. */
    private fun retour(): UiState {
        val e = chemin.retour()
        return _ui.value.copy(screen = e.ecran, settingsSection = e.section)
    }

    /**
     * Opens the settings, on [section] when given: a screen's gear opens its
     * own settings, and back returns to that screen.
     */
    fun openSettings(section: String? = null) {
        va(Screen.SETTINGS, section)
        _ui.value = _ui.value.copy(screen = Screen.SETTINGS, settingsSection = section)
    }
    fun closeSettings() {
        _ui.value = retour().copy(query = "")
        computeFavoritePasses()
    }

    /** Back inside the settings: a section reached from a gear leaves them. */
    fun closeSettingsSection() {
        if (chemin.fermeReglages(_ui.value.settingsSection)) closeSettings()
        else setSettingsSection(null)
    }

    private var locationUpdatesJob: kotlinx.coroutines.Job? = null

    fun openLocator() {
        va(Screen.LOCATOR)
        _ui.value = _ui.value.copy(screen = Screen.LOCATOR)
        startLiveLocation(SuiviPosition.CADENCE_CARTE_MS)
    }

    /** Current tracking period, so we restart only when it changes. */
    private var cadenceSuiviMs = 0L

    /** Time of the last fix received, and time tracking started. */
    @Volatile private var dernierPointMs = 0L
    @Volatile private var suiviDemarreMs = 0L

    private fun noteSuivi(quoi: String, point: Boolean = false, relance: Boolean = false) {
        val h = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val s = _ui.value.suivi
        _ui.value = _ui.value.copy(suivi = s.copy(
            etat = "$h  $quoi",
            points = s.points + if (point) 1 else 0,
            relances = s.relances + if (relance) 1 else 0,
            permission = permissionPosition()))
    }

    private fun permissionPosition(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * The permission was just granted — or refused.
     *
     * On first launch after install, tracking has already tried to start and
     * been refused, and nothing told it the situation changed. The watchdog
     * only caught a dead job, but a refused request can leave a job that ends
     * cleanly with no fix ever arriving. So: as soon as the answer is known,
     * restart from scratch and ask for an immediate fix too.
     */
    fun onPermissionsResult() {
        val ok = permissionPosition()
        noteSuivi(if (ok) "permission accordée" else "permission refusée")
        if (!ok) return
        // In manual mode a GPS fix must not override the chosen square. This is
        // called on return from the permission dialog, even with tracking
        // stopped; it used to overwrite a hand-typed locator seconds later.
        if (_ui.value.locationMode != LocationMode.AUTO) {
            noteSuivi("mode manuel : point GPS ignoré")
            return
        }
        stopLiveLocation()
        startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        viewModelScope.launch {
            runCatching {
                val obs = locationProvider.current()
                if (SuiviPosition.vraisemblable(obs.latDeg, obs.lonDeg)) {
                    _ui.value = _ui.value.copy(observer = obs.copy(
                        name = "GPS · " + Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg)))
                    noteSuivi("point immédiat obtenu", point = true)
                    computeFavoritePasses()
                }
            }
        }
    }

    /** Where and when the last prediction was computed. */
    private var qthCalculLat: Double? = null
    private var qthCalculLon: Double? = null
    private var dernierRecalculMs = 0L

    /**
     * Tracks position continuously while in automatic mode.
     *
     * It used to run only while the map was open, so on the pass page — the one
     * used while operating — a portable operator silently got azimuths for the
     * place he started from.
     *
     * Two periods, since it now runs permanently and shows up in the battery
     * report: fast on the map, where the marker must follow; slow elsewhere,
     * where seeing a square change within twenty seconds is plenty.
     */
    private fun startLiveLocation(cadenceMs: Long = SuiviPosition.CADENCE_FOND_MS) {
        if (_ui.value.locationMode != LocationMode.AUTO) return
        if (locationUpdatesJob?.isActive == true && cadenceSuiviMs == cadenceMs &&
            !SuiviPosition.doitRelancer(true, true, dernierPointMs, suiviDemarreMs,
                System.currentTimeMillis())
        ) return
        locationUpdatesJob?.cancel()
        cadenceSuiviMs = cadenceMs
        suiviDemarreMs = System.currentTimeMillis()
        noteSuivi("suivi démarré à ${cadenceMs / 1000} s")
        locationUpdatesJob = viewModelScope.launch {
            runCatching {
                locationProvider.updates(cadenceMs).collect { obs ->
                    // An aberrant fix would move the QTH, hence the azimuths,
                    // hence the antenna, on a receiver glitch.
                    if (!SuiviPosition.vraisemblable(obs.latDeg, obs.lonDeg)) return@collect
                    // A received fix is the only proof tracking works.
                    dernierPointMs = System.currentTimeMillis()
                    noteSuivi("point reçu", point = true)

                    // The mode may have changed since tracking started: an
                    // in-flight fix must not land on a manual QTH.
                    if (_ui.value.locationMode != LocationMode.AUTO) return@collect

                    val named = obs.copy(
                        name = "GPS · " + Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg))
                    _ui.value = _ui.value.copy(observer = named)

                    // Redrawing on every fix is free; a 48 h SGP4 prediction
                    // for all tracked satellites is not, and changes nothing
                    // until we really move. Hence the threshold.
                    val maintenant = System.currentTimeMillis()
                    if (SuiviPosition.doitRecalculer(
                            qthCalculLat, qthCalculLon, obs.latDeg, obs.lonDeg,
                            dernierRecalculMs, maintenant)
                    ) {
                        qthCalculLat = obs.latDeg
                        qthCalculLon = obs.lonDeg
                        dernierRecalculMs = maintenant
                        computeFavoritePasses()
                        // Everything position-dependent follows the GPS, not
                        // just passes. These three used to refresh only in
                        // `applyLocation` (manual locator change), so the home
                        // screen showed parks at 0 m while the photo said
                        // "no park within 3 km".
                        chargeZonePota()
                        if (_ui.value.carte.affichee) chargeCartePays()
                        if (_ui.value.carte.potaAffiche) chargePotaPhoto()
                    }
                }
            }.onFailure { noteSuivi("suivi interrompu : " + it.javaClass.simpleName) }
        }
    }

    private fun stopLiveLocation() {
        locationUpdatesJob?.cancel(); locationUpdatesJob = null
        cadenceSuiviMs = 0L
        dernierPointMs = 0L
        suiviDemarreMs = 0L
    }

    /**
     * Location tracking watchdog.
     *
     * Started from `init`, not `bootstrap`, on purpose: `bootstrap` returns
     * early when the orbital cache is fresh, so anything placed there may never
     * run. `init` runs exactly once per ViewModel.
     *
     * Every five seconds it checks not that the job exists but that it
     * **delivers**: a location request made before Google services are ready
     * leaves a live but silent job.
     */
    private fun veilleSuiviPosition() {
        viewModelScope.launch {
            while (isActive) {
                runCatching {
                    if (SuiviPosition.doitRelancer(
                            auto = _ui.value.locationMode == LocationMode.AUTO,
                            tacheActive = locationUpdatesJob?.isActive == true,
                            dernierPointMs = dernierPointMs,
                            demarreDepuisMs = suiviDemarreMs,
                            maintenantMs = System.currentTimeMillis())
                    ) {
                        locationUpdatesJob?.cancel()
                        locationUpdatesJob = null
                        noteSuivi("relance par le veilleur", relance = true)
                        startLiveLocation(
                            if (_ui.value.screen == Screen.LOCATOR)
                                SuiviPosition.CADENCE_CARTE_MS
                            else SuiviPosition.CADENCE_FOND_MS)
                    }
                }
                delay(5_000)
            }
        }
    }
    /**
     * Sked predictor (mutual visibility, like f4ioz.fr/sat/passes): crosses the
     * satellite's passes over MY QTH with its passes over another station's
     * locator and returns the overlapping windows for the next 48 h.
     * onResult(null) means the locator could not be parsed.
     */
    fun computeSked(catnum: Int, otherLocator: String, minElOther: Double,
                    onResult: (List<SkedWindow>?) -> Unit) {
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
            ?: return onResult(emptyList())
        val ll = fr.f4ioz.satcombo.location.Maidenhead.toLatLon(otherLocator.trim())
            ?: return onResult(null)
        val obsA = _ui.value.observer ?: locationProvider.defaultObserver
        val obsB = Observer(ll.first, ll.second, 0.0, otherLocator.trim().uppercase())
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val pa = runCatching { predictor.upcomingPasses(sat, obsA, now, 48,
                    settings.minElevDeg.toDouble()) }.getOrDefault(emptyList())
                val pb = runCatching { predictor.upcomingPasses(sat, obsB, now, 48,
                    minElOther) }.getOrDefault(emptyList())
                val out = ArrayList<SkedWindow>()
                for (a in pa) for (b in pb) {
                    val st = maxOf(a.aosEpochMs, b.aosEpochMs)
                    val en = minOf(a.losEpochMs, b.losEpochMs)
                    if (en - st >= 60_000L)
                        out.add(SkedWindow(st, en, a.maxElevationDeg.toInt(), b.maxElevationDeg.toInt()))
                }
                out.sortedBy { it.startMs }
            }
            onResult(res)
        }
    }

    // ---------------------------------------------------------------------
    // Mutual-sked page (full screen, animated, exportable)
    // ---------------------------------------------------------------------

    /**
     * Opens the mutual-sked page, pre-selecting a sensible satellite, possibly
     * **from an announcement**.
     *
     * With [depuis], hams.at already gave callsign, satellite, slot and grid
     * square; making the operator retype what the app displays is the best way
     * to put a typo into a sked. The announced slot is not copied into a field
     * (the computation sweeps 48 h): it is kept as a **target** so the sked's
     * window is shown first rather than the first one found.
     */
    fun openSked(depuis: fr.f4ioz.satcombo.data.SkedAlert? = null) {
        val cat = depuis?.satNorad
            ?: _ui.value.skedSatCat
            ?: _ui.value.selected?.catalogNumber
            ?: _ui.value.satellites.firstOrNull { it.catalogNumber in _ui.value.favorites }?.catalogNumber
            ?: _ui.value.satellites.firstOrNull { it.catalogNumber == 25544 }?.catalogNumber
            ?: _ui.value.satellites.firstOrNull()?.catalogNumber
        val carre = depuis?.grids?.firstOrNull { it.isNotBlank() }.orEmpty()
        skedViseeMs = depuis?.let { it.workableStartMs ?: it.aosMs }
        va(Screen.SKED)
        _ui.value = _ui.value.copy(
            screen = Screen.SKED,
            skedSatCat = cat,
            skedOtherLoc = carre.ifBlank {
                _ui.value.skedOtherLoc.ifBlank { settings.skedOtherLoc }
            },
            skedPlans = if (carre.isNotBlank()) emptyList() else _ui.value.skedPlans,
            skedError = null
        )
        // Everything is known: compute right away. A button with no choice left
        // is not a confirmation, it is an obstacle.
        if (carre.trim().length >= 4 && cat != null) computeSkedPlans()
    }

    /**
     * Time the next computation should highlight, or null. Not screen state —
     * a hand-off consumed by the first computation, kept out of `UiState`
     * (255-register limit).
     */
    private var skedViseeMs: Long? = null

    fun closeSked() { _ui.value = retour() }

    fun setSkedSat(cat: Int) {
        _ui.value = _ui.value.copy(
            skedSatCat = cat, skedPlans = emptyList(),
            skedComputed = false, skedError = null, skedSelectedIndex = 0)
    }

    fun setSkedOtherLoc(loc: String) {
        _ui.value = _ui.value.copy(skedOtherLoc = loc.uppercase().trim(), skedError = null)
    }

    fun setSkedMinElOther(v: Int) { _ui.value = _ui.value.copy(skedMinElOther = v) }

    fun setSkedSelectedIndex(i: Int) { _ui.value = _ui.value.copy(skedSelectedIndex = i) }

    /**
     * Compute mutual-visibility sked plans for the chosen satellite and the DX
     * station over the next 48 h. Each plan carries both stations' densely
     * sampled pass tracks so the page can animate the two polar plots and export
     * a shareable card. skedError = "bad" means the locator could not be parsed.
     */
    fun computeSkedPlans() {
        val cat = _ui.value.skedSatCat ?: return
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == cat } ?: return
        val loc = _ui.value.skedOtherLoc.trim().uppercase()
        val ll = Maidenhead.toLatLon(loc)
        if (ll == null) { _ui.value = _ui.value.copy(skedError = "bad"); return }
        val obsA = _ui.value.observer ?: locationProvider.defaultObserver
        val myLoc = Maidenhead.fromLatLon(obsA.latDeg, obsA.lonDeg)
        val obsB = Observer(ll.first, ll.second, 0.0, loc)
        val minElOther = _ui.value.skedMinElOther.toDouble()
        settings.skedOtherLoc = loc
        _ui.value = _ui.value.copy(skedComputing = true, skedError = null, skedPlans = emptyList())
        viewModelScope.launch {
            val plans = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val pa = runCatching {
                    predictor.upcomingPasses(sat, obsA, now, 48, settings.minElevDeg.toDouble())
                }.getOrDefault(emptyList())
                val pb = runCatching {
                    predictor.upcomingPasses(sat, obsB, now, 48, minElOther)
                }.getOrDefault(emptyList())
                val dist = haversineKm(obsA.latDeg, obsA.lonDeg, obsB.latDeg, obsB.lonDeg)
                val out = ArrayList<SkedPlan>()
                for (a in pa) for (b in pb) {
                    val st = maxOf(a.aosEpochMs, b.aosEpochMs)
                    val en = minOf(a.losEpochMs, b.losEpochMs)
                    if (en - st < 60_000L) continue
                    val youSamples = predictor.sampleTrack(sat, obsA, a.aosEpochMs, a.losEpochMs, 96)
                        .map { SkedSample(it.first, it.second, it.third) }
                    val dxSamples = predictor.sampleTrack(sat, obsB, b.aosEpochMs, b.losEpochMs, 96)
                        .map { SkedSample(it.first, it.second, it.third) }
                    out.add(SkedPlan(
                        satName = sat.name, catnum = cat,
                        mutualStartMs = st, mutualEndMs = en,
                        you = SkedStationTrack(myLoc, myLoc, a.aosEpochMs, a.losEpochMs,
                            a.maxElevationDeg, youSamples),
                        dx = SkedStationTrack(loc, loc, b.aosEpochMs, b.losEpochMs,
                            b.maxElevationDeg, dxSamples),
                        distanceKm = dist
                    ))
                }
                out.sortedBy { it.mutualStartMs }
            }
            // The announced sked's window comes first: the one containing it,
            // else the nearest — not simply the first slot of the 48 h.
            val visee = skedViseeMs
            skedViseeMs = null
            val index = fr.f4ioz.satcombo.domain.SkedVisee.index(
                plans.map { it.mutualStartMs..it.mutualEndMs }, visee)
            _ui.value = _ui.value.copy(
                skedComputing = false, skedComputed = true,
                skedPlans = plans, skedSelectedIndex = index)
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun setSettingsSection(id: String?) {
        chemin.choixSection()
        _ui.value = _ui.value.copy(settingsSection = id)
    }
    fun closeLocator() {
        // Do not stop tracking when leaving the map, drop to the background
        // period: stopping it froze the position until the next visit.
        startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        _ui.value = retour()
    }

    fun setLocationMode(mode: LocationMode) {
        settings.locationMode = mode
        _ui.value = _ui.value.copy(locationMode = mode, locatorError = null)
        if (mode == LocationMode.MANUAL) { stopLiveLocation(); applyLocation() }
        else startLiveLocation(
            if (_ui.value.screen == Screen.LOCATOR) SuiviPosition.CADENCE_CARTE_MS
            else SuiviPosition.CADENCE_FOND_MS)
        applyLocation()
    }

    fun setManualLocator(loc: String) {
        // Hand-typing a locator throws away the exact point: the operator is
        // back to naming a square, not a spot.
        _ui.value = _ui.value.copy(
            manualLocator = loc, manualLat = null, manualLon = null, locatorError = null)
    }

    /**
     * Map picker: keep the EXACT tapped coordinates, force MANUAL mode and
     * apply immediately. The locator is derived from the point, so the two can
     * never disagree.
     */
    fun pickLocator(loc: String, lat: Double? = null, lon: Double? = null) {
        if (Maidenhead.toLatLon(loc) == null) return
        val exact = lat != null && lon != null && Maidenhead.fromLatLon(lat, lon).startsWith(loc.uppercase())
        settings.manualLocator = loc
        settings.manualLat = if (exact) lat!! else Double.NaN
        settings.manualLon = if (exact) lon!! else Double.NaN
        settings.locationMode = LocationMode.MANUAL
        _ui.value = _ui.value.copy(
            manualLocator = loc,
            manualLat = if (exact) lat else null,
            manualLon = if (exact) lon else null,
            locationMode = LocationMode.MANUAL, locatorError = null)
        applyLocation()
    }

    fun applyManualLocator() {
        val loc = _ui.value.manualLocator
        if (Maidenhead.toLatLon(loc) == null) {
            _ui.value = _ui.value.copy(locatorError = t("locator_invalid"))
            return
        }
        settings.manualLocator = loc
        settings.manualLat = _ui.value.manualLat ?: Double.NaN
        settings.manualLon = _ui.value.manualLon ?: Double.NaN
        applyLocation()
    }

    private fun applyLocation() {
        viewModelScope.launch {
            val obs = resolveObserver()
            _ui.value = _ui.value.copy(observer = obs)
            // The QTH changed: the country outline must follow.
            if (_ui.value.carte.affichee) chargeCartePays()
            chargeZonePota()
            if (_ui.value.carte.potaAffiche) chargePotaPhoto()
            computeFavoritePasses()
            refreshPota()
        }
    }

    fun focusPass(aosMs: Long) {
        _ui.value = _ui.value.copy(focusedPassAos = aosMs)
        val sat = _ui.value.selected ?: return
        val obs = _ui.value.observer ?: return
        viewModelScope.launch { refreshPassTrack(sat, obs) }
    }

    fun setUseUtc(on: Boolean) {
        settings.useUtc = on
        _ui.value = _ui.value.copy(useUtc = on)
    }

    fun setLocatorDetails(on: Boolean) {
        settings.locatorDetails = on
        _ui.value = _ui.value.copy(locatorDetails = on)
    }

    // ---------------------------------------------------------------------
    // Audio recorder (MP3). Records the mic (rig RX audio) during a pass and
    // auto-stops 5 s after LOS unless stopped manually. RECORD_AUDIO must be
    // granted by the caller (UI) before start.
    // ---------------------------------------------------------------------

    /** Set one compass colour by key ("trace", "needle_far"…"bubble_close"). */
    fun setCompassColor(key: String, argb: Int) {
        when (key) {
            "trace" -> { settings.compassTraceColor = argb; _ui.value = _ui.value.copy(compassTraceColor = argb) }
            "needle_far" -> { settings.needleFarColor = argb; _ui.value = _ui.value.copy(needleFarColor = argb) }
            "needle_near" -> { settings.needleNearColor = argb; _ui.value = _ui.value.copy(needleNearColor = argb) }
            "needle_close" -> { settings.needleCloseColor = argb; _ui.value = _ui.value.copy(needleCloseColor = argb) }
            "bubble_far" -> { settings.bubbleFarColor = argb; _ui.value = _ui.value.copy(bubbleFarColor = argb) }
            "bubble_near" -> { settings.bubbleNearColor = argb; _ui.value = _ui.value.copy(bubbleNearColor = argb) }
            "bubble_close" -> { settings.bubbleCloseColor = argb; _ui.value = _ui.value.copy(bubbleCloseColor = argb) }
            "ring_az_near" -> { settings.ringAzNearColor = argb; _ui.value = _ui.value.copy(ringAzNearColor = argb) }
            "ring_az_close" -> { settings.ringAzCloseColor = argb; _ui.value = _ui.value.copy(ringAzCloseColor = argb) }
            "ring_el_near" -> { settings.ringElNearColor = argb; _ui.value = _ui.value.copy(ringElNearColor = argb) }
            "ring_el_close" -> { settings.ringElCloseColor = argb; _ui.value = _ui.value.copy(ringElCloseColor = argb) }
        }
    }

    fun setCompassTraceWidth(w: Float) {
        settings.compassTraceWidth = w
        _ui.value = _ui.value.copy(compassTraceWidth = w)
    }

    fun resetCompassColors() {
        settings.resetCompassColors()
        _ui.value = _ui.value.copy(
            compassTraceColor = settings.compassTraceColor,
            needleFarColor = settings.needleFarColor,
            needleNearColor = settings.needleNearColor,
            needleCloseColor = settings.needleCloseColor,
            bubbleFarColor = settings.bubbleFarColor,
            bubbleNearColor = settings.bubbleNearColor,
            bubbleCloseColor = settings.bubbleCloseColor,
            ringAzNearColor = settings.ringAzNearColor,
            ringAzCloseColor = settings.ringAzCloseColor,
            ringElNearColor = settings.ringElNearColor,
            ringElCloseColor = settings.ringElCloseColor)
    }

    fun setRecorderEnabled(on: Boolean) {
        settings.recorderEnabled = on
        if (!on && _ui.value.recording) stopRecording()
        _ui.value = _ui.value.copy(recorderEnabled = on)
    }

    fun setRecorderSource(src: String) {
        settings.recorderSource = src
        _ui.value = _ui.value.copy(recorderSource = src)
    }

    fun setRecorderUnprocessed(on: Boolean) {
        settings.recorderUnprocessed = on
        _ui.value = _ui.value.copy(recorderUnprocessed = on)
    }

    /**
     * Doppler hold: only writing to the rig is suspended. Satellite position,
     * Doppler, rest frequency and display go on, so on release the rig lands
     * back in place with nothing to redo.
     *
     * On release, the memory of the last commands is cleared: otherwise the app
     * would think it already wrote the right frequency and stay silent until
     * the next 200 Hz gap. Arming is forgotten too, so the rig is put back in
     * satellite/split mode on the next loop round.
     */
    fun toggleDopplerHold() {
        val on = !_ui.value.dopplerHold
        _ui.value = _ui.value.copy(dopplerHold = on)
        if (!on) {
            lastSentDl = 0L; lastSentUl = 0L
            catArmedFor = null; catArmedTxDesc = null
            rxArbiter.reset(); fmArbiter.reset()
        }
    }

    /**
     * Audio spectrum during recording. Can be toggled mid-pass: the monitor is
     * already running, it just resumes computing.
     */
    fun setMonitorSpectre(on: Boolean) {
        settings.monitorSpectre = on
        fr.f4ioz.satcombo.audio.MoniteurAudio.spectre = on
        _ui.value = _ui.value.copy(monitorSpectre = on)
    }

    /**
     * Listen-through. Only affects an external source (rig USB sound card or
     * Bluetooth): routing the phone mic to its own speaker would just howl.
     */
    fun setMonitorSpeaker(on: Boolean) {
        settings.monitorSpeaker = on
        fr.f4ioz.satcombo.audio.MoniteurAudio.hautParleur = on
        _ui.value = _ui.value.copy(monitorSpeaker = on)
    }

    fun setSstvEnabled(on: Boolean) {
        settings.sstvEnabled = on
        _ui.value = _ui.value.copy(sstvEnabled = on)
    }

    fun setAptEnabled(on: Boolean) {
        settings.aptEnabled = on
        _ui.value = _ui.value.copy(aptEnabled = on)
    }

    /**
     * Chooses what the pass page image strip follows. Choosing NOAA also turns
     * the APT decoder on: no settings checkbox after the operator has already
     * said, on the pass page, that NOAA is expected.
     */
    fun setRxImageMode(mode: String) {
        val m = if (mode.equals("NOAA", true) || mode.equals("APT", true)) "NOAA" else "SSTV"
        settings.rxImageMode = m
        if (m == "NOAA" && !_ui.value.aptEnabled) setAptEnabled(true)
        if (m == "SSTV" && !_ui.value.sstvEnabled) setSstvEnabled(true)
        _ui.value = _ui.value.copy(rxImageMode = m)
    }

    /**
     * Record button of the pass page image strip. Same recorder as everywhere
     * (one capture, one MP3); also makes sure the matching decoder is on,
     * otherwise it would record with no image decoded.
     */
    fun toggleRxRecording() {
        if (_ui.value.recording) { stopRecording(); return }
        if (!_ui.value.recorderEnabled) setRecorderEnabled(true)
        if (_ui.value.rxImageMode == "NOAA") setAptEnabled(true) else setSstvEnabled(true)
        startRecording()
    }

    fun setRecordingsTree(uri: String) {
        settings.recordingsTreeUri = uri
        _ui.value = _ui.value.copy(recordingsTreeUri = uri)
    }

    /**
     * Listening from the SSTV page: a recording with SSTV decoding on, as the
     * ⏺ of the pass page does, without leaving the page. The page used to
     * only say where to go.
     */
    fun ecouteSstv() {
        if (!_ui.value.recorderEnabled) setRecorderEnabled(true)
        if (!_ui.value.sstvEnabled) setSstvEnabled(true)
        startRecording()
    }

    fun toggleUiLock() { _ui.value = _ui.value.copy(uiLocked = !_ui.value.uiLocked) }

    fun toggleRecording() { if (_ui.value.recording) stopRecording() else startRecording() }

    /** Start the foreground recorder service (survives screen-off/background). */
    fun startRecording() {
        if (_ui.value.recording) return
        val sel = _ui.value.selected
        val satName = sel?.name ?: fr.f4ioz.satcombo.audio.AnnonceVocale.SANS_SATELLITE
        val now = System.currentTimeMillis()
        // Auto-stop target: LOS (+5 s) of the pass in progress for the selected
        // satellite, else its next pass. Null -> record until stopped manually.
        val passes = (_ui.value.passes + _ui.value.favoritePasses)
            .filter { sel == null || it.catalogNumber == sel.catalogNumber }
        val active = passes.firstOrNull { now in it.aosEpochMs..it.losEpochMs }
        val next = passes.filter { it.aosEpochMs >= now }.minByOrNull { it.aosEpochMs }
        val autoStop = (active ?: next)?.losEpochMs?.let { it + 5_000L }
        fr.f4ioz.satcombo.audio.RecorderService.start(
            getApplication(), satName, autoStop,
            _ui.value.recorderSource, _ui.value.recorderUnprocessed, myLocator())
    }

    fun stopRecording() {
        fr.f4ioz.satcombo.audio.RecorderService.stop(getApplication())
    }

    /** 0, 3, 6 or 12 h of already-finished passes kept in the lists. */
    fun setPastPassHours(h: Int) {
        settings.pastPassHours = h
        _ui.value = _ui.value.copy(pastPassHours = settings.pastPassHours)
        computeFavoritePasses()
        _ui.value.selected?.let { selectByCatnum(it.catalogNumber) }
    }

    fun setMinElev(deg: Int) {
        settings.minElevDeg = deg
        _ui.value = _ui.value.copy(minElevDeg = settings.minElevDeg)
        computeFavoritePasses()
        // Refresh the open satellite's pass list too, if any.
        _ui.value.selected?.let { selectByCatnum(it.catalogNumber) }
    }

    fun setSkedsEnabled(on: Boolean) {
        settings.skedsEnabled = on
        _ui.value = _ui.value.copy(skedsEnabled = on)
        if (on) refreshSkeds() else _ui.value = _ui.value.copy(skeds = emptyList())
    }

    private fun refreshSkeds() {
        if (!_ui.value.skedsEnabled) return
        viewModelScope.launch {
            val list = skedRepo.upcoming(token = settings.skedsToken)
            _ui.value = _ui.value.copy(skeds = list)
        }
    }

    fun setSkedsToken(token: String) {
        settings.skedsToken = token
        _ui.value = _ui.value.copy(skedsToken = token, skedsAuthed = token.isNotBlank())
        viewModelScope.launch {
            val list = skedRepo.upcoming(token = token, force = true)
            _ui.value = _ui.value.copy(skeds = list)
        }
    }

    /**
     * Opens the entry keypad, nothing else.
     *
     * A compass tap used to write a contact at once — time, satellite, az/el —
     * with **no callsign**, meant to be named later. It only produced anonymous
     * lines, which ended up in the ADIF export. A contact without a callsign is
     * a callsign known a moment ago and lost. **The gesture opens the keypad;
     * confirming writes.** Nothing reaches the log until a callsign is typed.
     */
    fun ouvreSaisie() {
        va(Screen.NOMMAGE)
        _ui.value = _ui.value.copy(screen = Screen.NOMMAGE)
    }

    private fun modeEmission(t: fr.f4ioz.satcombo.data.Transmitter?): String {
        val m = t?.mode.orEmpty().uppercase()
        return when {
            m.contains("FM") -> "FM"
            t != null && t.isTransponder && _ui.value.opMode == "CW" -> "CW"
            m.contains("CW") -> "CW"
            // On a linear transponder CAT sets the rig by a fixed convention
            // (downlink USB, uplink LSB when inverting) **ignoring the
            // catalogue's mode**. The log must say the same, or it records a
            // mode the VFO never had.
            t != null && t.isTransponder -> if (effectiveInvert(t)) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            m.contains("USB") -> "USB"
            else -> m
        }
    }

    // ---- country outline on the QRV photo ----
    // A photo option, not a screen: a QRV card is a photo with things on it.

    /**
     * The operator confirms being in the suggested park. His call: only he
     * knows whether he crossed the boundary; no 3 km radius can tell.
     */
    fun accepteParcPropose() {
        val c = _ui.value.carte
        if (c.potaPropose.isBlank()) return
        _ui.value = _ui.value.copy(carte = c.copy(
            potaRef = c.potaPropose, potaNom = c.potaProposeNom,
            potaPropose = "", potaProposeNom = ""))
    }

    fun setPhotoPota(on: Boolean) {
        settings.photoShowPota = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(potaAffiche = on))
        if (on) chargePotaPhoto()
    }

    /**
     * The POTA area containing the position, judged by polygon.
     *
     * The first call loads the bundled file (1 MB, ~100 ms), hence off the main
     * thread. Later calls cost 0.03 ms: the bounding-box prefilter only walks a
     * polygon when the point is inside its box.
     */
    fun chargeZonePota() {
        val obs = _ui.value.observer ?: return
        viewModelScope.launch {
            val z = withContext(Dispatchers.IO) {
                runCatching {
                    fr.f4ioz.satcombo.data.PotaZones.zoneContenant(
                        getApplication(), obs.latDeg, obs.lonDeg)
                }.getOrNull()
            }
            // Towns around the park: an outline without names is a blob; with
            // three towns it is a place.
            val villes = if (z != null) withContext(Dispatchers.IO) {
                runCatching {
                    var laMin = 90.0; var laMax = -90.0
                    var loMin = 180.0; var loMax = -180.0
                    z.anneaux.forEach { a ->
                        var i = 0
                        while (i < a.size) {
                            if (a[i] < laMin) laMin = a[i]; if (a[i] > laMax) laMax = a[i]
                            if (a[i+1] < loMin) loMin = a[i+1]
                            if (a[i+1] > loMax) loMax = a[i+1]
                            i += 2
                        }
                    }
                    val mLat = (laMax - laMin) * 0.35 + 0.02
                    val mLon = (loMax - loMin) * 0.35 + 0.03
                    fr.f4ioz.satcombo.data.Villes.dansFenetre(getApplication(),
                        laMin - mLat, laMax + mLat, loMin - mLon, loMax + mLon, 6)
                        .map { Triple(it.nom, it.lat, it.lon) }
                }.getOrDefault(emptyList())
            } else emptyList()

            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                zoneRef = z?.ref.orEmpty(),
                zoneNom = z?.nom.orEmpty(),
                zoneAnneaux = z?.anneaux ?: emptyList(),
                villes = villes,
                // The POTA line follows outline detection, the only proof of
                // being in the park. Outside, clear it: a line surviving the
                // exit would follow the operator all day.
                potaRef = z?.ref.orEmpty(),
                potaNom = z?.nom.orEmpty(),
                potaPropose = if (z != null) "" else _ui.value.carte.potaPropose,
                potaProposeNom = if (z != null) "" else _ui.value.carte.potaProposeNom))

            // Nothing bundled here? Fetch nearby park outlines from
            // pota-map.fr one by one and keep them. Until a full grab exists,
            // this is what makes detection work outside Brittany.
            if (z == null) {
                // No outline: suggest the nearest park, and try the network
                // for next time.
                val hit = withContext(Dispatchers.IO) {
                    runCatching {
                        potaRepo.near(obs.latDeg, obs.lonDeg, radiusKm = 3.0,
                            context = getApplication()).firstOrNull()
                    }.getOrNull()
                }
                // **The nearby park is suggested, never asserted.** A POTA line
                // on a shared photo claims an activation; being within 3 km of
                // a park's point used to be enough. A false claim is worse than
                // no line. The operator accepts it with one tap if really there.
                _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                    potaPropose = hit?.park?.reference.orEmpty(),
                    potaProposeNom = hit?.park?.name.orEmpty()))
                chercheZoneAuReseau(obs.latDeg, obs.lonDeg)
            }
        }
    }

    /**
     * Preloads park outlines around the position.
     *
     * **Why in advance.** Normal detection fetches an outline only when needed.
     * In the field, network is where you leave from, not where you arrive: a
     * park activated in a valley without coverage stays without outline, and
     * no POTA line shows.
     *
     * **Politeness to the server is part of the feature.** `pota-map.fr` is a
     * community service, not infrastructure: capped at thirty parks, 200 ms
     * between requests, cached outlines skipped.
     */
    fun chargeContoursAutour(rayonKm: Double) {
        if (contoursEnCours) return
        val obs = _ui.value.observer ?: run {
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                contoursEtat = t("pota_contours_sans_position")))
            return
        }
        contoursEnCours = true
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            contoursEtat = t("pota_contours_cours")))
        viewModelScope.launch {
            val bilan = withContext(Dispatchers.IO) {
                runCatching {
                    val proches = potaRepo.near(obs.latDeg, obs.lonDeg,
                        radiusKm = rayonKm, context = getApplication()).take(30)
                    var pris = 0
                    for (h in proches) {
                        val ref = h.park.reference
                        if (fr.f4ioz.satcombo.data.PotaZones.zone(
                                getApplication(), ref) != null) continue
                        if (fr.f4ioz.satcombo.data.PotaZones.telecharge(
                                getApplication(), ref) != null) pris++
                        kotlinx.coroutines.delay(200)
                    }
                    proches.size to pris
                }.getOrNull()
            }
            contoursEnCours = false
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                contoursEtat = if (bilan == null) t("pota_contours_echec")
                else tf("pota_contours_bilan", bilan.second, bilan.first)))
        }
    }

    @Volatile private var contoursEnCours = false

    /**
     * Fetches nearby park outlines from the network when nothing is bundled.
     * One park at a time, nearest first, stopping at the first containing the
     * position — the site is a ham's, not a CDN. Each outline is cached for good.
     */
    private fun chercheZoneAuReseau(lat: Double, lon: Double) {
        if (chercheZoneEnCours) return
        chercheZoneEnCours = true
        viewModelScope.launch {
            val trouve = withContext(Dispatchers.IO) {
                runCatching {
                    val proches = potaRepo.near(lat, lon, radiusKm = 12.0,
                        context = getApplication()).take(6)
                    var z: fr.f4ioz.satcombo.data.PotaZones.Zone? = null
                    for (h in proches) {
                        val zz = fr.f4ioz.satcombo.data.PotaZones.telecharge(
                            getApplication(), h.park.reference) ?: continue
                        if (zz.contient(lat, lon)) {
                            z = fr.f4ioz.satcombo.data.PotaZones.Zone(
                                zz.ref, h.park.name, zz.anneaux)
                            break
                        }
                    }
                    z
                }.getOrNull()
            }
            chercheZoneEnCours = false
            if (trouve != null) {
                _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                    zoneRef = trouve.ref, zoneNom = trouve.nom,
                    zoneAnneaux = trouve.anneaux))
            }
        }
    }

    private var chercheZoneEnCours = false

    /**
     * The photo's POTA reference is the detected area's. It once had its own
     * path (nearest park point), which could say "no park" — or name another
     * park — while the map drew the containing outline. One computation now:
     * `chargeZonePota` fills the area, the POTA line copies it.
     */
    fun chargePotaPhoto() = chargeZonePota()

    /**
     * Installs a POTA area file produced by PotaGrab. Stored in app-private
     * storage, not the APK: all of France weighs tens of MB, not to be imposed
     * on someone who never activates a park.
     */
    fun importeZonesPota(texte: String): Int {
        val n = runCatching {
            val o = org.json.JSONObject(texte)
            if (o.length() == 0) return 0
            fr.f4ioz.satcombo.data.PotaZones.fichierImporte(getApplication())
                .writeText(texte)
            fr.f4ioz.satcombo.data.PotaZones.rafraichis()
            o.length()
        }.getOrDefault(0)
        if (n > 0) chargeZonePota()
        return n
    }

    fun effaceZonesPota() {
        runCatching {
            fr.f4ioz.satcombo.data.PotaZones.fichierImporte(getApplication()).delete()
            fr.f4ioz.satcombo.data.PotaZones.rafraichis()
        }
        chargeZonePota()
    }

    fun compteZonesPota(): Int =
        fr.f4ioz.satcombo.data.PotaZones.compteImporte(getApplication())

    fun setPhotoCarte(on: Boolean) {
        settings.photoShowCarte = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(affichee = on))
        // Reload on every activation, not only when nothing is loaded: gated on
        // "no rings", the country stayed that of the previous QTH (UK outline on
        // an American photo, even after unchecking/rechecking).
        if (on) chargeCartePays()
    }

    fun setPhotoCarteTaille(v: Float) {
        settings.photoCarteTaille = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(taille = settings.photoCarteTaille))
    }

    fun setPhotoCartePosition(x: Float, y: Float) {
        settings.photoCarteX = x; settings.photoCarteY = y
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            x = settings.photoCarteX, y = settings.photoCarteY))
    }

    fun setPhotoCarteContenu(v: String) {
        settings.photoCarteContenu = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(contenu = v))
        if (v == "ZONE") chargeZonePota()
    }

    fun setPhotoPotaTaille(v: Float) {
        settings.photoPotaTaille = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            potaTaille = settings.photoPotaTaille))
    }

    fun setPhotoPotaMonte(v: Float) {
        settings.photoPotaMonte = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            potaMonte = settings.photoPotaMonte))
    }

    fun setPhotoQrg(on: Boolean) {
        settings.photoShowQrg = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgAffiche = on))
    }

    fun setLiseretEmission(on: Boolean) {
        settings.liseréEmission = on
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(liseret = on))
        // Takes effect at once: start or stop without waiting for a reconnect.
        surveilleEmission()
    }

    // ---- online log (Wavelog / Cloudlog) ----

    fun setCarnetUrl(v: String) {
        settings.carnetUrl = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            url = settings.carnetUrl, carres = emptyMap()))
    }

    fun setCarnetCle(v: String) {
        settings.carnetCle = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            cle = settings.carnetCle, carres = emptyMap()))
    }

    fun setCarnetSlug(v: String) {
        settings.carnetSlug = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            slug = settings.carnetSlug, carres = emptyMap()))
    }

    fun setCarnetProfil(v: String) {
        settings.carnetProfil = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(profil = settings.carnetProfil))
    }

    fun setCarnetAuto(on: Boolean) {
        settings.carnetAuto = on
        // Only what is logged from now on: an older log may already be in
        // Wavelog through an ADIF import, and Wavelog does not deduplicate.
        if (on) settings.carnetAutoDepuisMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(auto = on, autoEtat = ""))
    }

    /**
     * What the control desk shows while typing: is the square new, and was
     * this callsign already worked on this satellite in the last 24 hours.
     * Called from the desk's server thread, so the online-log check may wait
     * (three seconds at most, cached afterwards).
     */
    private fun infosSaisie(call: String, loc: String): fr.f4ioz.satcombo.demo.PontCommande.Infos {
        val u = _ui.value
        val k = loc.trim().uppercase().take(4)
        val carre = if (!Regex("[A-R]{2}[0-9]{2}").matches(k)) "" else {
            var etat = etatCarre(u, k)
            val c = u.carnet
            if ((etat == null || etat == fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.JAMAIS) &&
                c.configure && c.slug.isNotBlank()) {
                val enLigne = runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            fr.f4ioz.satcombo.data.CarnetEnLigne.carre(c.url, c.cle, c.slug, k)
                        }
                    }
                }.getOrNull()
                if (enLigne != null && enLigne != fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.INCONNU) etat = enLigne
            }
            val dansJournal = u.log.any { it.callsign.isNotBlank() && it.theirLocator.uppercase().startsWith(k) }
            when {
                etat == fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.CONFIRME -> "confirme"
                etat == fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.TRAVAILLE || dansJournal -> "travaille"
                etat == fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.JAMAIS -> "nouveau"
                // Nothing but SatMe's own log to go by: say so.
                else -> "nouveau_journal"
            }
        }
        val indicatif = call.trim().uppercase()
        val sat = u.selected?.name.orEmpty()
        val maintenant = System.currentTimeMillis()
        val avant = if (indicatif.isBlank() || sat.isBlank()) null else u.log.firstOrNull {
            it.callsign.equals(indicatif, true) && it.satName == sat && maintenant - it.timeMs < 86_400_000L
        }
        val doublon = avant?.let {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it.timeMs))
        }.orEmpty()
        return fr.f4ioz.satcombo.demo.PontCommande.Infos(carre, doublon)
    }

    /** What the PC control desk shows beside the pass: see PontCommande.Station. */
    private fun stationPourPupitre(): fr.f4ioz.satcombo.demo.PontCommande.Station {
        val u = _ui.value
        val c = u.carnet
        val obs = u.observer
        val ici = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) } ?: u.manualLocator
        val profilIci = if (c.profil.isBlank() && c.profils.isEmpty()) "" else
            fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                ici, myGridsCsv(), c.profils, c.profil, c.maille)
        val maintenant = System.currentTimeMillis()
        // The followed satellites' next passes, the one in progress first.
        val passages = u.favoritePasses.filter { it.losEpochMs > maintenant }
            .sortedBy { it.aosEpochMs }.take(8).map {
                fr.f4ioz.satcombo.demo.PontCommande.PassageWeb(
                    it.satName, it.aosEpochMs, it.losEpochMs, it.maxElevationDeg.toInt())
            }
        val actifs = activeTransmitters()
        val tp = actifs.getOrNull(u.selectedTxIndex.coerceIn(0, (actifs.size - 1).coerceAtLeast(0)))
        return fr.f4ioz.satcombo.demo.PontCommande.Station(
            indicatif = settings.callsign, locator = ici,
            profils = c.profilsListe.map {
                fr.f4ioz.satcombo.demo.PontCommande.ProfilWeb(it.id, it.nom, it.indicatif, it.carre)
            },
            profilDefaut = c.profil, profilIci = profilIci, profilsEtat = c.profilsEtat,
            passages = passages,
            cat = if (u.catConnected) u.rigModel else "",
            rotor = u.rotorConnected, enregistre = u.recording,
            radio = if (c.radio) c.radioEtat.ifBlank { t("radio_attente") } else "",
            auto = if (c.auto) c.autoEtat.ifBlank { t("auto_actif") } else "",
            transpondeur = tp?.let {
                it.description + " · " + (if (u.invertOverride ?: it.invert) "INVERSE" else "NORMAL")
            }.orEmpty())
    }

    // ---- automatic upload of each contact ----

    /** Last change of each contact, which restarts its minute. In memory only. */
    private val modifies = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** Why the server refused a contact (it is then held back). */
    private val refusAuto = java.util.concurrent.ConcurrentHashMap<Long, String>()
    /** No reply: not before this time (the request may have gone through). */
    private val reessai = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    @Volatile private var envoiAutoEnCours = false
    @Volatile private var profilsDemandes = false

    /**
     * Station callsign of a Wavelog profile ("F4IOZ/M"), else the settings
     * callsign. Wavelog skips a contact whose STATION_CALLSIGN differs from
     * the profile's: "Differing station callsign … SKIPPED".
     */
    private fun stationDuProfil(profilId: String): String =
        _ui.value.carnet.profilsListe.firstOrNull { it.id == profilId }
            ?.indicatif?.trim()?.uppercase()?.ifBlank { null }
            ?: settings.callsign

    /** State of a contact for the automatic upload. */
    fun etatEnvoi(e: fr.f4ioz.satcombo.data.LogEntry, maintenant: Long = System.currentTimeMillis()) =
        fr.f4ioz.satcombo.domain.EnvoiAuto.etat(e.timeMs, e.callsign, e.envoyeMs, e.retenu,
            if (settings.carnetAuto) settings.carnetAutoDepuisMs else null,
            modifies[e.timeMs], maintenant)

    fun resteEnvoiS(e: fr.f4ioz.satcombo.data.LogEntry, maintenant: Long = System.currentTimeMillis()): Long =
        fr.f4ioz.satcombo.domain.EnvoiAuto.resteS(e.timeMs, modifies[e.timeMs], maintenant)

    fun refusEnvoi(timeMs: Long): String = refusAuto[timeMs].orEmpty()

    /** Holds a contact back, or releases it (a refusal is then forgotten). */
    fun retiensContact(timeMs: Long, retenu: Boolean) {
        if (!retenu) { refusAuto.remove(timeMs); reessai.remove(timeMs) }
        _ui.value = _ui.value.copy(log = logStore.retiens(timeMs, retenu))
    }

    /**
     * Sends the oldest contact whose minute is over. One at a time, from the
     * one-second loop; marked only once the server took it, as for the batch.
     */
    private fun envoieAuto() {
        val c = _ui.value.carnet
        if (!c.auto || envoiAutoEnCours || c.depotEnCours) return
        if (c.url.isBlank() || c.cle.isBlank() || c.profil.isBlank()) return
        // The profile list is not kept across restarts: fetched once before
        // the first automatic upload, or every contact would go to the
        // settings profile whatever the square it was made from.
        if (c.profilsListe.isEmpty() && !profilsDemandes) {
            profilsDemandes = true
            relevProfils()
            return
        }
        val maintenant = System.currentTimeMillis()
        val e = _ui.value.log.filter {
            etatEnvoi(it, maintenant) == fr.f4ioz.satcombo.domain.EnvoiAuto.Etat.PRET &&
                (reessai[it.timeMs] ?: 0L) <= maintenant
        }.minByOrNull { it.timeMs } ?: return
        envoiAutoEnCours = true
        viewModelScope.launch {
            try {
                val profil = fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                    e.myLocator, e.myGrids, c.profils, c.profil, c.maille)
                val adif = fr.f4ioz.satcombo.data.Adif.enregistrement(
                    e, stationDuProfil(profil), settings.callsign)
                if (adif.isBlank()) return@launch
                val r = fr.f4ioz.satcombo.data.CarnetEnLigne.depose(c.url, c.cle, profil, adif)
                val issue = fr.f4ioz.satcombo.data.CarnetEnLigne.issue(r)
                val etat = when {
                    // Already in Wavelog: the upload's aim is met.
                    fr.f4ioz.satcombo.data.CarnetEnLigne.doublon(r) -> {
                        _ui.value = _ui.value.copy(
                            log = logStore.marqueEnvoye(e.timeMs, System.currentTimeMillis()))
                        tf("auto_doublon", e.callsign)
                    }
                    issue == fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.PRIS -> {
                        _ui.value = _ui.value.copy(
                            log = logStore.marqueEnvoye(e.timeMs, System.currentTimeMillis()))
                        tf("auto_envoye", e.callsign)
                    }
                    issue == fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.REFUS -> {
                        // Held back: sending it again would be refused again.
                        val pourquoi = fr.f4ioz.satcombo.data.CarnetEnLigne.raison(r)
                        refusAuto[e.timeMs] = pourquoi
                        _ui.value = _ui.value.copy(log = logStore.retiens(e.timeMs, true))
                        tf("auto_refus", e.callsign, pourquoi)
                    }
                    else -> {
                        // The request may have arrived: wait before trying again.
                        reessai[e.timeMs] = System.currentTimeMillis() + fr.f4ioz.satcombo.domain.EnvoiAuto.DELAI_MS
                        tf("auto_doute", e.callsign)
                    }
                }
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(autoEtat = etat))
            } finally {
                envoiAutoEnCours = false
            }
        }
    }

    fun setCarnetRadio(on: Boolean) {
        settings.carnetRadio = on
        relais.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(radio = on, radioEtat = ""))
    }

    fun setCarnetRadioNom(v: String) {
        settings.carnetRadioNom = v
        relais.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(radioNom = v))
    }

    // ---- SatMe as a radio in the online log ----

    private val relais = fr.f4ioz.satcombo.data.RelaisRadio()
    @Volatile private var relaisEnCours = false

    /**
     * Sends the worked satellite and the RX/TX box frequencies to Wavelog or
     * Cloudlog when they deserve it ([RelaisRadio.aEnvoyer]). Called every
     * second; nothing leaves while no satellite page is open, since there is
     * then no transponder chosen to describe.
     */
    private fun relaieRadio() {
        val u = _ui.value
        val c = u.carnet
        if (!c.radio || c.url.isBlank() || c.cle.isBlank() || relaisEnCours) return
        val sat = u.selected ?: return
        val actifs = activeTransmitters()
        if (actifs.isEmpty()) return
        val t = actifs[u.selectedTxIndex.coerceIn(0, actifs.size - 1)]
        val (rx, tx) = freqAffichees()
        if (rx == null && tx == null) return
        val e = fr.f4ioz.satcombo.data.RelaisRadio.Etat(
            satellite = sat.name, montantHz = tx, descendantHz = rx,
            modeMontant = modeJambe(t, montant = true), modeDescendant = modeJambe(t, montant = false))
        val maintenant = System.currentTimeMillis()
        if (!relais.aEnvoyer(e, maintenant)) return
        // Marked before the answer: a slow or failing server must not get a
        // message every second.
        relais.envoye(e, maintenant)
        relaisEnCours = true
        val corps = fr.f4ioz.satcombo.data.RelaisRadio.json(c.cle, c.radioNom.ifBlank { "SatMe" }, e)
        viewModelScope.launch {
            val refus = fr.f4ioz.satcombo.data.CarnetEnLigne.radio(c.url, corps)
            relaisEnCours = false
            val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(maintenant))
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                radioEtat = if (refus == null) tf("radio_envoye", heure, e.satellite, e.modeSat)
                            else tf("radio_refus", refus)))
        }
    }

    /**
     * Mode of one leg, as the rig is set: FM, CW, or on a linear transponder
     * USB down and LSB up when inverting — the same convention as CAT, so the
     * log never records a mode the VFO did not have.
     */
    private fun modeJambe(t: fr.f4ioz.satcombo.data.Transmitter, montant: Boolean): String {
        val m = t.mode.orEmpty().uppercase()
        return when {
            m.contains("FM") -> "FM"
            t.isTransponder && _ui.value.opMode == "CW" -> "CW"
            m.contains("CW") -> "CW"
            t.isTransponder -> if (montant && effectiveInvert(t)) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            m.contains("USB") -> "USB"
            else -> m.ifBlank { "FM" }
        }
    }

    /** How many contacts are waiting to be uploaded to the online log. */
    fun contactsADeposer(): Int =
        fr.f4ioz.satcombo.domain.EnvoiCarnet.combienAttendent(
            _ui.value.log.map {
                fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.callsign, it.envoyeMs)
            })

    private val qrz = fr.f4ioz.satcombo.data.Qrz()

    fun setMaille(n: Int) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(maille = n))
    }

    /**
     * How many contacts still carry a raw satellite name. Shown on the button,
     * so you know without pressing whether there is anything to do.
     */
    fun nomsSatellitesANettoyer(): Int =
        _ui.value.log.count {
            it.satName.isNotBlank() &&
                fr.f4ioz.satcombo.domain.NomSatellite.aNettoyer(it.satName)
        }

    /**
     * Square matching precision: 4 like VUCC, or 6. Changing it invalidates the
     * fetched table (profiles are keyed by truncated square), so it is cleared
     * to force a new fetch rather than upload on a stale mapping.
     */
    fun setMailleCarres(m: Int) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            maille = m.coerceIn(4, 6), profils = emptyMap(), profilsEtat = ""))
    }

    fun setQrzUser(v: String) {
        settings.qrzUser = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(qrzUser = v))
    }

    fun setQrzMdp(v: String) {
        settings.qrzMdp = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(qrzMdp = v))
    }

    /**
     * Fetches station profiles and matches them against the log's squares.
     * **Do it before any upload when activating several squares**, otherwise
     * everything goes to the single profile and is filed under its square.
     */
    fun relevProfils() {
        val c = _ui.value.carnet
        if (c.url.isBlank() || c.cle.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("carnet_depot_config")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_releve")))
        viewModelScope.launch {
            val liste = runCatching {
                fr.f4ioz.satcombo.data.CarnetEnLigne.profils(c.url, c.cle)
            }.getOrElse {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    profilsEtat = (it.message ?: it.javaClass.simpleName).take(140)))
                return@launch
            }
            val parLieu = emplacementsDuCarnet(c.maille)
            val table = fr.f4ioz.satcombo.domain.ProfilsStation.apparieEmplacements(
                parLieu.keys.toList(), liste, settings.callsign, c.maille)
            val orphelins = parLieu.keys.filter { table[it] == null }
                .map { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it) }
                .sorted()
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profils = table, profilsListe = liste,
                profilsEtat = tf("profils_bilan", liste.size, parLieu.size, orphelins.size) +
                    (if (orphelins.isEmpty()) "" else " · " + orphelins.joinToString(", "))))
        }
    }

    /**
     * Locations the log was made from, with contact counts. A line of squares
     * is a location of its own: its contacts must not mix with those made in
     * only one of the squares.
     */
    fun emplacementsDuCarnet(maille: Int): Map<Set<String>, Int> {
        val out = LinkedHashMap<Set<String>, Int>()
        _ui.value.log.filter { it.callsign.isNotBlank() }.forEach {
            val k = fr.f4ioz.satcombo.domain.ProfilsStation.emplacement(
                it.myLocator, it.myGrids, maille)
            out[k] = (out[k] ?: 0) + 1
        }
        return out.toList()
            .sortedBy { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it.first) }
            .toMap(LinkedHashMap())
    }

    /** Log locations, named, for the screen. */
    fun carresDuCarnet(maille: Int): Map<String, Int> =
        emplacementsDuCarnet(maille).mapKeys {
            fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it.key)
        }

    /** Creates in Wavelog the locations the log needs. */
    fun creeProfilsManquants(dxcc: String, pays: String, cq: String, itu: String) {
        val c = _ui.value.carnet
        if (itu.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_itu")))
            return
        }
        val parLieu = emplacementsDuCarnet(c.maille)
        val orphelins = parLieu.keys.filter { c.profils[it] == null }
            .map { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it) }
            .filter { it.isNotBlank() && it != "—" }
            .sorted()
        if (orphelins.isEmpty()) return
        _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_creation")))
        viewModelScope.launch {
            var faits = 0
            var dernier = ""
            for (carre in orphelins) {
                val nom = settings.callsign.trim().uppercase() + " @ " + carre
                val r = fr.f4ioz.satcombo.data.CarnetEnLigne.creeProfil(
                    c.url, c.cle, nom, carre, settings.callsign, dxcc, pays, cq, itu)
                if (fr.f4ioz.satcombo.data.CarnetEnLigne.profilEnPlace(r)) faits++
                else dernier = r.take(90)
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profilsEtat = tf("profils_crees", faits, orphelins.size) +
                    (if (dernier.isBlank()) "" else " · " + dernier)))
            // New ids are only known after a fetch.
            relevProfils()
        }
    }

    /**
     * Cleans satellite names in the log: "RS-44 & BREEZE-KM R/B" becomes
     * "RS-44". LoTW and Wavelog match on this field; under the long name the
     * contact never meets the other station's.
     */
    fun nettoieNomsSatellites() {
        var changes = 0
        val neuf = _ui.value.log.map {
            val propre = fr.f4ioz.satcombo.domain.NomSatellite.propre(it.satName)
            if (propre != it.satName) { changes++; it.copy(satName = propre) } else it
        }
        if (changes == 0) {
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profilsEtat = t("sat_noms_propres")))
            return
        }
        logStore.save(neuf)
        _ui.value = _ui.value.copy(log = neuf, carnet = _ui.value.carnet.copy(
            profilsEtat = tf("sat_noms_nettoyes", changes)))
    }

    /** How many contacts lack a square QRZ might provide. */
    fun carresManquants(): Int =
        _ui.value.log.count { it.callsign.isNotBlank() && it.theirLocator.isBlank() }

    /**
     * Tests the QRZ credentials and says so plainly. Otherwise a wrong
     * password is only found at the first contact, mid-pass — the worst moment.
     */
    fun testeQrz() {
        val c = _ui.value.carnet
        if (c.qrzUser.isBlank() || c.qrzMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_test_vide")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_test_cours")))
        viewModelScope.launch {
            val souci = withContext(Dispatchers.IO) { qrz.connecte(c.qrzUser, c.qrzMdp) }
            val dit = if (souci.isNotBlank()) souci else {
                // A successful login does not prove the XML subscription is
                // active: look up a callsign to really check.
                val f = withContext(Dispatchers.IO) { qrz.cherche(settings.callsign.ifBlank { "F4IOZ" }) }
                if (f.erreur.isNotBlank()) f.erreur else t("qrz_test_ok")
            }
            _ui.value = _ui.value.copy(
                carnet = _ui.value.carnet.copy(qrzEtat = dit))
        }
    }

    /**
     * Fills missing fields of the log from QRZ.
     *
     * **Only what is missing.** A square noted by ear during the contact beats
     * a directory one: the other station may have been portable, and QRZ gives
     * the home address. Overwriting would replace a fact with a guess.
     */
    fun combleParQrz() {
        val c = _ui.value.carnet
        if (c.qrzEnCours) return
        if (c.qrzUser.isBlank() || c.qrzMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_identifiants")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(qrzEnCours = true, qrzEtat = t("qrz_connexion")))
        viewModelScope.launch {
            val souci = withContext(Dispatchers.IO) { qrz.connecte(c.qrzUser, c.qrzMdp) }
            if (souci.isNotBlank()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    qrzEnCours = false, qrzEtat = souci.take(140)))
                return@launch
            }
            // Query for **everything missing**, not just the square: the online
            // log also wants name, town and e-mail.
            val aCombler = _ui.value.log
                .filter {
                    it.callsign.isNotBlank() &&
                        (it.theirLocator.isBlank() || it.nom.isBlank() || it.qth.isBlank())
                }
                .map { it.timeMs to it.callsign }
            var trouves = 0
            var dernier = ""
            for ((quand, indicatif) in aCombler) {
                val f = withContext(Dispatchers.IO) { qrz.cherche(indicatif) }
                if (f.erreur.isNotBlank()) {
                    dernier = f.erreur.take(90)
                    // Session lost or no subscription: insisting would repeat
                    // the same error a hundred times.
                    break
                }
                if (f.vide) continue
                // **Only fill blanks** (see above).
                var pose = false
                val neuf = _ui.value.log.map { e ->
                    if (e.timeMs != quand) e else {
                        var v = e
                        if (f.carre.isNotBlank() && v.theirLocator.isBlank()) {
                            v = v.copy(theirLocator = f.carre, locatorOrigine = "QRZ")
                            pose = true
                        }
                        if (f.nom.isNotBlank() && v.nom.isBlank()) {
                            v = v.copy(nom = f.nom); pose = true
                        }
                        if (f.qth.isNotBlank() && v.qth.isBlank()) {
                            v = v.copy(qth = f.qth); pose = true
                        }
                        if (f.courriel.isNotBlank() && v.courriel.isBlank()) {
                            v = v.copy(courriel = f.courriel); pose = true
                        }
                        v
                    }
                }
                if (!pose) continue
                logStore.save(neuf)
                _ui.value = _ui.value.copy(log = neuf)
                trouves++
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                qrzEnCours = false,
                qrzEtat = tf("qrz_bilan", trouves, aCombler.size) +
                    (if (dernier.isBlank()) "" else " · " + dernier)),
                // New squares must join the keypad memory, which suggests
                // them on the next pass.
                express = _ui.value.express.copy(memoire = construitMemoire()))
        }
    }

    /**
     * Uploads **a single** contact. Batch was the only path, which was wrong:
     * the first try is exactly when the API key and station profile turn out
     * wrong, and a whole batch filed wrongly must be untangled by hand on the
     * server. Same guard as batch: marked only once the server confirmed it.
     */
    fun deposeUnContact(timeMs: Long) {
        val c = _ui.value.carnet
        if (c.depotEnCours) return
        if (c.url.isBlank() || c.cle.isBlank() || c.profil.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_depot_config")))
            return
        }
        val e = _ui.value.log.firstOrNull { it.timeMs == timeMs } ?: return
        if (e.callsign.isBlank()) return
        _ui.value = _ui.value.copy(carnet = c.copy(depotEnCours = true, depot = ""))
        viewModelScope.launch {
            val profil = fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                e.myLocator, e.myGrids, c.profils, c.profil, c.maille)
            val adif = fr.f4ioz.satcombo.data.Adif.enregistrement(
                e, stationDuProfil(profil), settings.callsign)
            val r = if (adif.isBlank()) ""
                    else fr.f4ioz.satcombo.data.CarnetEnLigne.depose(c.url, c.cle, profil, adif)
            // A duplicate is already in Wavelog: as good as accepted.
            val pris = fr.f4ioz.satcombo.data.CarnetEnLigne.accepte(r) ||
                fr.f4ioz.satcombo.data.CarnetEnLigne.doublon(r)
            if (pris) {
                _ui.value = _ui.value.copy(
                    log = logStore.marqueEnvoye(timeMs, System.currentTimeMillis()))
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                depotEnCours = false,
                // Server reply shown verbatim: it is what says the key is
                // read-only or the profile is missing; a bare "failed" would not.
                depot = if (pris) tf("carnet_depot_un", e.callsign)
                        else fr.f4ioz.satcombo.data.CarnetEnLigne.raison(r)))
        }
    }

    /**
     * Uploads the contacts not yet in the online log.
     *
     * One by one, oldest first, **marked only after the server said it took
     * it**. Wavelog does not dedupe: marking early loses a contact on the first
     * network hiccup, marking too broadly makes a duplicate to delete by hand.
     * The log is rewritten after each acceptance, so a cut mid-batch keeps the
     * work done.
     */
    fun deposeAuCarnet() {
        val c = _ui.value.carnet
        if (c.depotEnCours) return
        if (c.url.isBlank() || c.cle.isBlank() || c.profil.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_depot_config")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(depotEnCours = true, depot = ""))
        viewModelScope.launch {
            // A contact held back is being fixed: the batch leaves it too.
            val fiches = fr.f4ioz.satcombo.domain.EnvoiCarnet.aDeposer(
                _ui.value.log.filter { !it.retenu }.map {
                    fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.callsign, it.envoyeMs)
                })
            val station = settings.callsign
            val acceptes = HashSet<Long>()
            var refuses = 0
            var dernier = ""
            var doutes = 0
            for (fiche in fiches) {
                val e = _ui.value.log.firstOrNull { it.timeMs == fiche.timeMs } ?: continue
                // Profile follows the contact's location (lines of squares
                // included); without a fetched table, the single settings profile.
                val profil = fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                    e.myLocator, e.myGrids, c.profils, c.profil, c.maille)
                val adif = fr.f4ioz.satcombo.data.Adif.enregistrement(
                    e, stationDuProfil(profil), station)
                if (adif.isBlank()) continue
                val r = fr.f4ioz.satcombo.data.CarnetEnLigne.depose(c.url, c.cle, profil, adif)
                when (fr.f4ioz.satcombo.data.CarnetEnLigne.issue(r)) {
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.PRIS -> {
                        acceptes.add(fiche.timeMs)
                        _ui.value = _ui.value.copy(
                            log = logStore.marqueEnvoye(fiche.timeMs, System.currentTimeMillis()))
                    }
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.REFUS ->
                        if (fr.f4ioz.satcombo.data.CarnetEnLigne.doublon(r)) {
                            // Already in Wavelog: marked, not counted as refused.
                            acceptes.add(fiche.timeMs)
                            _ui.value = _ui.value.copy(
                                log = logStore.marqueEnvoye(fiche.timeMs, System.currentTimeMillis()))
                        } else {
                            refuses++
                            dernier = fr.f4ioz.satcombo.data.CarnetEnLigne.raison(r)
                            // Three refusals with no success: it is the setup
                            // or the server, not this contact. Stop.
                            if (refuses >= 3 && acceptes.isEmpty()) break
                        }
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.DOUTE -> {
                        // **Doubt does not stop the batch.** The request left;
                        // only the reply is missing. The contact stays pending
                        // — Wavelog discards duplicates — but giving up the
                        // batch because the server was slow once would lose
                        // the whole pass.
                        doutes++
                        dernier = r.take(90)
                    }
                }
            }
            val b = fr.f4ioz.satcombo.domain.EnvoiCarnet.bilan(
                fiches.map { fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.indicatif, 0L) },
                acceptes, refuses)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                depotEnCours = false,
                depot = tf("carnet_depot_bilan", b.deposes, b.restants) +
                    (if (doutes > 0) " · " + tf("carnet_depot_doute", doutes) else "") +
                    (if (dernier.isNotBlank()) " · " + dernier else "")))
        }
    }

    /**
     * The globe reuses `groundTrack`, already computed for the pass map: two
     * views of the same data read the same variable.
     */
    fun ouvreGlobe() { va(Screen.GLOBE); _ui.value = _ui.value.copy(screen = Screen.GLOBE) }

    fun ouvreFt8() { va(Screen.FT8); _ui.value = _ui.value.copy(screen = Screen.FT8) }
    /**
     * Leaving the screen does not stop listening: a slot lasts fifteen seconds
     * and cutting it to check the log would lose a cycle. Only the screen's
     * button stops it.
     */
    fun fermeFt8() { _ui.value = retour() }
    fun fermeGlobe() { _ui.value = retour() }

    fun setLotwCall(v: String) {
        settings.lotwCall = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(lotwCall = settings.lotwCall))
    }

    fun setLotwMdp(v: String) {
        settings.lotwMdp = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(lotwMdp = v))
    }

    /** Loads what LoTW already returned, without network. */
    fun chargeLotwLocal() {
        val e = fr.f4ioz.satcombo.data.Lotw.charge(getApplication())
        if (e.travailles.isEmpty() && e.confirmes.isEmpty()) return
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            lotwConfirmes = e.confirmes + carresDeMemoire(e.sansCarre),
            lotwTravailles = e.travailles + carresDeMemoire(e.sansCarre),
            lotwActives = e.activés + mesCarresLocaux()))
    }

    /**
     * My squares from the local log: LoTW only knows what was uploaded, so
     * yesterday's activation is not there yet.
     */
    private fun mesCarresLocaux(): Set<String> =
        _ui.value.log.mapNotNull {
            it.myLocator.uppercase().take(4).takeIf { g -> g.length == 4 }
        }.toSet()

    /**
     * Corrects the satellite — and time — of a logged contact. Az/el are
     * **recomputed**: they describe where the antenna pointed, not typed data;
     * keeping them would make a log that looks consistent and is wrong.
     */
    fun corrigeSatellite(timeMs: Long, sat: TleEntry, nouvelleHeure: Long? = null) {
        val quand = nouvelleHeure ?: timeMs
        val obs = _ui.value.observer
        val pos = if (obs != null) runCatching {
            predictor.positionAt(sat, obs, quand)
        }.getOrNull() else null
        val l = logStore.changeSatellite(timeMs, sat.name, sat.catalogNumber,
            pos?.azimuthDeg, pos?.elevationDeg, nouvelleHeure)
        _ui.value = _ui.value.copy(log = l,
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /**
     * Adds a contact afterwards, at the given time (paper log typed in back
     * home): the time is the contact's, not the entry's, and az is recomputed
     * from it.
     */
    fun ajouteContact(quandMs: Long, sat: TleEntry, indicatif: String, locator: String) {
        // The audience sees the contact the moment it is confirmed: the
        // highlight of a demo cannot be told afterwards.
        if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
            fr.f4ioz.satcombo.demo.ServeurDemo.ajouteContact(indicatif, locator, sat.name)
        }
        // Same guard as keypad entry: no callsign, no contact. The dialog's
        // button is already disabled, but the rule belongs to the write, not
        // the button.
        if (indicatif.isBlank()) return
        val obs = _ui.value.observer
        val pos = if (obs != null) runCatching {
            predictor.positionAt(sat, obs, quandMs)
        }.getOrNull() else null
        val e = fr.f4ioz.satcombo.data.LogEntry(
            timeMs = quandMs,
            satName = sat.name,
            catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0,
            elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) }.orEmpty(),
            callsign = indicatif.trim().uppercase(),
            theirLocator = locator.trim().uppercase())
        val l = logStore.add(e)
        _ui.value = _ui.value.copy(log = l,
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /**
     * Callsigns already worked during the current pass, so the same station is
     * not called twice on a busy transponder. What "this pass" means is a
     * domain rule with its tests (it was wrong twice); here we only give it the
     * AOS–LOS window of the displayed satellite.
     */
    fun indicatifsDuPassage(): List<String> {
        val choisi = _ui.value.selected ?: return emptyList()
        val sat = choisi.name
        val maintenant = System.currentTimeMillis()
        val obs = _ui.value.observer
        // The real pass start, computed for this satellite. The pass list is the
        // fallback without an observer position: its window may be truncated
        // at the start, but truncated beats missing.
        val fenetre = obs?.let {
            runCatching { predictor.currentPass(choisi, it, maintenant) }.getOrNull()
        }?.let { fr.f4ioz.satcombo.domain.Passage.Fenetre(it.first, it.second) }
            ?: fr.f4ioz.satcombo.domain.Passage.enCours(
                _ui.value.passes.map {
                    fr.f4ioz.satcombo.domain.Passage.Fenetre(it.aosEpochMs, it.losEpochMs)
                },
                maintenant)
        return fr.f4ioz.satcombo.domain.Passage.indicatifs(
            _ui.value.log.map {
                fr.f4ioz.satcombo.domain.Passage.Inscrit(
                    it.timeMs, it.satName, it.elevationDeg, it.callsign)
            },
            sat, fenetre)
    }

    fun basculeListeContacts() {
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            listeContactsOuverte = !_ui.value.carte.listeContactsOuverte))
    }

    /**
     * Logs a contact typed on the keypad, here and now: displayed satellite,
     * confirmation time, and geometry for that time.
     *
     * **The only live path that writes to the log**, so it must carry what
     * cannot be recovered in the evening: mode, both rest frequencies, claimed
     * squares. Without them the ADIF lacks BAND, MODE and SAT_MODE.
     */
    fun ajouteContactDirect(
        sat: TleEntry, indicatif: String, locator: String,
        rstEnvoye: String, rstRecu: String, origine: String,
    ) {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return
        // The callsign keypad is the real field path, so the demo hook belongs
        // here (not only on manual entry, which nobody uses mid-pass).
        if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
            fr.f4ioz.satcombo.demo.ServeurDemo.ajouteContact(call, locator, sat.name)
        }
        val maintenant = System.currentTimeMillis()
        val obs = _ui.value.observer
        val pos = _ui.value.livePosition ?: obs?.let {
            runCatching { predictor.positionAt(sat, it, maintenant) }.getOrNull()
        }
        // The current transponder gives mode and both frequencies (MODE, BAND,
        // SAT_MODE), and now is when that information is still reliable.
        val tx = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val e = fr.f4ioz.satcombo.data.LogEntry(
            timeMs = maintenant,
            satName = sat.name, catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0,
            elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) }
                ?: _ui.value.manualLocator,
            // From a line of squares the contact counts for both; the claim
            // only holds if the log records it at the time.
            myGrids = myGridsCsv(),
            callsign = call,
            theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye, rstRcvd = rstRecu,
            locatorOrigine = origine,
            mode = modeEmission(tx),
            downlinkMhz = descenteAuRepos()?.let { it / 1_000_000.0 } ?: 0.0,
            uplinkMhz = monteeAuRepos()?.let { it / 1_000_000.0 } ?: 0.0)
        _ui.value = _ui.value.copy(log = logStore.add(e),
            express = _ui.value.express.copy(memoire = construitMemoire()))
        // A running recording gets a marker, to find the contact at the right
        // second on playback.
        if (_ui.value.recording) {
            fr.f4ioz.satcombo.audio.RecorderService.addMarker(
                tf("qso_marker_call", call, sat.name, (pos?.elevationDeg ?: 0.0).toInt()))
        }
    }

    fun setPeindreCarres(on: Boolean) {
        settings.peindreCarres = on
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(peindre = on))
    }

    /**
     * Squares of stations LoTW could not locate. LoTW returns a square only if
     * the other station declared one; many confirmed satellite contacts have
     * none. The callsign memory (log, imported ADIF, bundled base) knows many.
     * The contact is confirmed anyway: restoring its square invents nothing.
     */
    private fun carresDeMemoire(indicatifs: Set<String>): Set<String> {
        if (indicatifs.isEmpty()) return emptySet()
        val memoire = _ui.value.express.memoire
        if (memoire.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (ind in indicatifs) {
            val cle = fr.f4ioz.satcombo.domain.Indicatifs.cle(ind)
            val c = memoire.firstOrNull { it.indicatif == cle }
                ?: memoire.firstOrNull {
                    fr.f4ioz.satcombo.domain.Indicatifs.base(it.indicatif) ==
                        fr.f4ioz.satcombo.domain.Indicatifs.base(ind)
                }
            val g = c?.locatorPrincipal.orEmpty().uppercase().take(4)
            if (g.length == 4) out.add(g)
        }
        return out
    }

    /**
     * Downloads the log from LoTW. Full download, on demand only: LoTW does not
     * answer square by square and the file can be long.
     */
    fun rafraichisLotw() {
        val c = _ui.value.carnet
        if (c.lotwCall.isBlank() || c.lotwMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(lotwEtat = "indicatif et mot de passe"))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(lotwEtat = "téléchargement…"))
        viewModelScope.launch {
            val r = fr.f4ioz.satcombo.data.Lotw.rafraichis(
                getApplication(), c.lotwCall, c.lotwMdp)
            val e = fr.f4ioz.satcombo.data.Lotw.charge(getApplication())
            val retrouves = carresDeMemoire(e.sansCarre)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                lotwEtat = r + (if (retrouves.isNotEmpty())
                    " · +${retrouves.size} par la mémoire" else ""),
                lotwConfirmes = e.confirmes + retrouves,
                lotwTravailles = e.travailles + retrouves,
                lotwActives = e.activés + mesCarresLocaux()))
        }
    }

    fun essaieCarnet() {
        val c = _ui.value.carnet
        _ui.value = _ui.value.copy(carnet = c.copy(essai = "…"))
        viewModelScope.launch {
            val r = fr.f4ioz.satcombo.data.CarnetEnLigne.essai(c.url, c.cle, c.slug)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(essai = r))
        }
    }

    /**
     * Queries the online log for a handful of squares: one at a time, never the
     * same twice (the connector caches) — the API docs explicitly ask not to
     * hammer these routes.
     */
    fun demandeCarres(liste: List<String>) {
        val c = _ui.value.carnet
        if (!c.configure) return
        val aFaire = liste.map { it.uppercase() }.distinct()
            .filter { it.isNotBlank() && !c.carres.containsKey(it) }
        if (aFaire.isEmpty()) return
        viewModelScope.launch {
            for (k in aFaire) {
                val e = fr.f4ioz.satcombo.data.CarnetEnLigne.carre(c.url, c.cle, c.slug, k)
                if (e != fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.INCONNU) {
                    _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                        carres = _ui.value.carnet.carres + (k to e)))
                }
            }
        }
    }

    fun setTxSuitVite(on: Boolean) {
        settings.txSuitVite = on
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txSuitVite = on))
    }

    fun setSondeTxMs(v: Int) {
        settings.sondeTxMs = v
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(sondeMs = settings.sondeTxMs))
    }

    fun setPhotoQrgTexte(v: String) {
        settings.photoQrgTexte = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgTexte = settings.photoQrgTexte))
    }

    fun setPhotoPassScale(v: Float) {
        settings.photoPassScale = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(passScale = settings.photoPassScale))
    }

    fun setPhotoQrgScale(v: Float) {
        settings.photoQrgScale = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgScale = settings.photoQrgScale))
    }

    fun setPhotoPotaNom(on: Boolean) {
        settings.photoPotaNom = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(potaNomAffiche = on))
    }

    fun setPhotoFondUni(v: Int) {
        settings.photoFondUni = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(fondUni = v))
    }

    fun setPhotoCarteFondu(on: Boolean) {
        settings.photoCarteFondu = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(fondu = on))
    }

    fun setPhotoCarteCouleur(v: Int) {
        settings.photoCarteCouleur = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(couleur = v))
    }

    fun setPotaBandeauAccueil(on: Boolean) {
        settings.potaBandeauAccueil = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(bandeauAccueil = on))
    }

    fun setPhotoCarteRemplissage(v: String) {
        settings.photoCarteRemplissage = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(remplissage = v))
    }

    /**
     * Loads the outline of the current country. The catalogue is 463 KB: read
     * off the main thread, only if the operator asks for the map.
     */
    fun chargeCartePays() {
        val obs = _ui.value.observer ?: return
        // If the loaded country already contains the position, nothing to
        // re-read: the Photo screen calls this on every GPS fix.
        val c = _ui.value.carte
        if (c.anneaux.isNotEmpty() &&
            c.anneaux.any { fr.f4ioz.satcombo.domain.Pays.dansAnneau(it, obs.latDeg, obs.lonDeg) }
        ) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    fr.f4ioz.satcombo.data.PaysStore.autour(getApplication(), obs.latDeg, obs.lonDeg)
                }.getOrNull()
            }
            // Country not found: clear rather than keep the old one. A stale
            // outline is worse than a message — it looks right.
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                anneaux = r?.second ?: emptyList(),
                paysNom = r?.first?.nom ?: ""))
        }
    }

    fun fermeNommage() {
        _ui.value = retour()
    }

    /**
     * Known stations: local log plus imported index. Rebuilt whole rather than
     * maintained incrementally: ten thousand contacts fold in milliseconds, and
     * a rebuilt memory cannot drift from its source.
     */
    private fun construitMemoire(): List<fr.f4ioz.satcombo.domain.Indicatifs.Connu> {
        val locaux = _ui.value.log.filter { it.callsign.isNotBlank() }.map {
            fr.f4ioz.satcombo.domain.Indicatifs.Contact(
                indicatif = it.callsign, locator = it.theirLocator,
                quandMs = it.timeMs, satellite = it.satName)
        }
        return fr.f4ioz.satcombo.domain.Indicatifs.memoire(locaux + indexImporte)
    }

    private var indexImporte: List<fr.f4ioz.satcombo.domain.Indicatifs.Contact> = emptyList()

    // The bundled callsign base (3,763 contacts of the F4IOZ log, 294 KB in
    // every install) was removed, with its setting: each operator now harvests
    // his own online log.

    /**
     * Forgets everything harvested into the keypad memory. **Does not touch the
     * log**: only the imported index (ADIF or Wavelog harvest) is cleared. The
     * harvest cursor is reset, otherwise the next harvest would resume from the
     * last contact seen and leave the memory half empty.
     */
    fun purgeIndexImporte() {
        indexImporte = emptyList()
        runCatching { indexStore.enregistre(emptyList()) }
        settings.carnetDernierId = 0L
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()),
            carnet = _ui.value.carnet.copy(depot = t("express_purge_faite")))
    }

    fun importeAdif(texte: String): fr.f4ioz.satcombo.data.AdifImport.Bilan {
        val bilan = fr.f4ioz.satcombo.data.AdifImport.lit(texte)
        indexImporte = bilan.contacts
        runCatching { indexStore.enregistre(bilan.contacts) }
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()))
        return bilan
    }

    /** The requested profile set, in a stable comparable form. */
    private fun profilsDemandes(
        c: fr.f4ioz.satcombo.CarnetUi, choisis: List<String>
    ): String = choisis.ifEmpty {
        c.profils.values.filterNotNull().distinct()
            .ifEmpty { listOf(c.profil).filter { it.isNotBlank() } }
    }.sorted().joinToString(",")

    /**
     * Harvests from Wavelog to feed the keypad. A new or second phone otherwise
     * starts empty, while Wavelog, fed by both devices, **already knows
     * everything**.
     *
     * Incremental: resumes from the last harvested contact (re-fetching the
     * whole log each time would hit the instance's rate limits). Only the index
     * is kept (callsign, square, date, satellite); the local log stays sole
     * master of its contacts — no merge rule, no local fix overwritten.
     */
    fun moissonneCarnet(choisis: List<String> = emptyList()) {
        val c = _ui.value.carnet
        if (c.url.isBlank() || c.cle.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_reglages")))
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(depotEnCours = true))
            // **Changing the profile set resets the cursor.** The cursor only
            // means something for a given set; resuming a new profile from the
            // old cursor would skip its whole history, noticed only through
            // missing squares.
            val empreinte = profilsDemandes(c, choisis)
            if (empreinte != settings.carnetProfilsVus) {
                settings.carnetDernierId = 0L
                settings.carnetProfilsVus = empreinte
            }
            val depuisId = settings.carnetDernierId
            // Profiles ticked by the operator; else all known ones; else the
            // single hand-typed profile. The keypad memory need not stop at
            // one square.
            val profils = choisis.ifEmpty {
                c.profils.values.filterNotNull().distinct()
                    .ifEmpty { listOf(c.profil).filter { it.isNotBlank() } }
            }
            // **No profile, no call.** An empty list went out as
            // `station_id: []` and the server answered HTTP 400, which looks
            // like a connection failure right after "Test" passed. It is a
            // missing setting and must be reported as such.
            if (profils.isEmpty()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false, depot = t("carnet_sans_profil")))
                return@launch
            }
            val m = runCatching {
                fr.f4ioz.satcombo.data.CarnetEnLigne.moissonne(
                    c.url, c.cle, profils, depuisId, settings.carnetFiltre)
            }.getOrElse {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
                    depot = it.message ?: it.javaClass.simpleName))
                return@launch
            }
            if (m.adif.isBlank()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
                    depot = m.message.ifBlank { t("carnet_moisson_rien") }))
                return@launch
            }
            // **Append** to the index rather than replace: an incremental
            // harvest only returns new contacts.
            val bilan = fr.f4ioz.satcombo.data.AdifImport.lit(m.adif, settings.carnetFiltre)
            // Callsigns the keypad did not know yet — the only measure of what
            // it gained (38 QSOs with one station add one entry). Counted
            // **before** the merge, or there is nothing left to compare.
            val connus = indexImporte.mapTo(HashSet()) { it.indicatif }
            val nouveaux = bilan.contacts.mapTo(HashSet()) { it.indicatif }
                .count { it !in connus }
            val fusion = (indexImporte + bilan.contacts)
                .associateBy { it.indicatif + "|" + it.quandMs }.values.toList()
            indexImporte = fusion
            runCatching { indexStore.enregistre(fusion) }
            settings.carnetDernierId = m.dernierId
            _ui.value = _ui.value.copy(
                express = _ui.value.express.copy(memoire = construitMemoire()),
                carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
                    // **Say what the numbers mean.** "Zero new" looked like a
                    // failure while everything worked (the contacts were with
                    // known stations). The cursor stays shown in small print:
                    // it tells whether a harvest was incremental or from zero.
                    depot = (if (m.nombre == 0) t("carnet_moisson_ajour")
                             else tf("carnet_moisson", m.nombre, bilan.retenus,
                                 nouveaux, fusion.distinctBy { it.indicatif }.size)) +
                        "\n\n" + tf("carnet_moisson_curseur", depuisId, m.dernierId)))
        }
    }

    /**
     * Changes what is harvested and **resets the incremental cursor** (done in
     * the `carnetFiltre` setter). Otherwise going from "sat" to "all" would
     * only bring contacts after the last call: the HF history would stay
     * invisible, silently.
     */
    fun setCarnetFiltre(f: String) {
        settings.carnetFiltre = f
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            filtre = f, depot = t("carnet_moisson_remise")))
    }

    /** Starts over: the next harvest reloads the whole log. */
    fun oublieMoisson() {
        settings.carnetDernierId = 0L
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            depot = t("carnet_moisson_remise")))
    }

    private val indexStore = fr.f4ioz.satcombo.data.IndexImporte(app)

    init {
        // The imported index survives closing; reload it at startup.
        runCatching { indexImporte = indexStore.charge() }
        // **The keypad memory is built at startup.** It used to be built as a
        // side effect of a queue refresh; when the queue was removed (19.11),
        // the memory went with it, silently — no test covers startup — and the
        // keypad opened empty after every update.
        runCatching {
            _ui.value = _ui.value.copy(
                express = _ui.value.express.copy(memoire = construitMemoire()))
        }
        runCatching { veilleSuiviPosition() }
    }

    fun updateLogEntry(
        timeMs: Long, callsign: String, theirLocator: String, note: String,
        mode: String = "", rstSent: String = "", rstRcvd: String = ""
    ) {
        // A contact waiting for the automatic upload gets its minute again.
        modifies[timeMs] = System.currentTimeMillis()
        _ui.value = _ui.value.copy(
            log = logStore.update(timeMs, callsign.trim().uppercase(),
                theirLocator.trim().uppercase(), note.trim(),
                mode.trim(), rstSent.trim(), rstRcvd.trim()))
    }

    fun deleteLogEntry(timeMs: Long) {
        _ui.value = _ui.value.copy(log = logStore.delete(timeMs))
    }

    fun logAdif(): String = logStore.toAdif(settings.callsign)

    /**
     * Writes the log to a real .adi file and returns a shareable URI. Many logs
     * only accept a file, and pasted text loses its line breaks as soon as an
     * app tries to be clever.
     */
    fun adifFileUri(entries: List<LogEntry>? = null, tag: String = "carnet"): android.net.Uri? {
        val app = getApplication<android.app.Application>()
        val text = LogStore.toAdif(entries ?: _ui.value.log, settings.callsign)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd'_'HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val safe = tag.replace(Regex("[^A-Za-z0-9_-]"), "-")
        return runCatching {
            val dir = java.io.File(app.cacheDir, "export").apply { mkdirs() }
            val f = java.io.File(dir, "SatMe_${safe}_$stamp.adi")
            f.writeText(text)
            androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", f)
        }.getOrNull()
    }

    // ================= station identity / QRV photo =================

    fun setCallsign(cs: String) {
        settings.callsign = cs
        // The callsign is also a key: F4IOZ unlocks beta features.
        _ui.value = _ui.value.copy(
            callsign = settings.callsign,
            extensions = fr.f4ioz.satcombo.data.Extensions.unlocked(
                settings.callsign, settings.extensionsCode))
    }

    /**
     * Saves the "Extensions" field and recomputes what is unlocked. If the
     * operator locks the extension he is on, go back to the pass list: staying
     * on a screen no longer in the menu would be a trap.
     */
    fun setExtensionsCode(code: String) {
        settings.extensionsCode = code
        val ext = fr.f4ioz.satcombo.data.Extensions.unlocked(settings.callsign, settings.extensionsCode)
        val u = _ui.value
        val lost =
            (u.screen == Screen.SSTV && fr.f4ioz.satcombo.data.Extensions.SSTV !in ext) ||
            (u.screen == Screen.APRS && fr.f4ioz.satcombo.data.Extensions.APRS !in ext) ||
            (u.screen == Screen.SDR && fr.f4ioz.satcombo.data.Extensions.SDR !in ext)
        if (u.screen == Screen.SDR && fr.f4ioz.satcombo.data.Extensions.SDR !in ext) stopSdr()
        _ui.value = u.copy(
            extensionsCode = settings.extensionsCode,
            extensions = ext,
            screen = if (lost) Screen.PASSES else u.screen)
        // Rebuild the keypad memory now, not at next startup.
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /** True if beta feature [name] is unlocked for this operator. */
    fun hasExtension(name: String): Boolean = name in _ui.value.extensions

    /**
     * Dismisses the start-up reminder. [never] records that the operator does
     * not want to be asked again — some users run the app purely as a viewer.
     */
    fun dismissCallsignPrompt(never: Boolean) {
        if (never) settings.callsignPromptOff = true
        _ui.value = _ui.value.copy(askCallsign = false)
    }

    /** Distance (m) under which a neighbouring grid square is announced. */
    fun setNearGridMeters(m: Int) {
        settings.nearGridMeters = m
        _ui.value = _ui.value.copy(nearGridMeters = settings.nearGridMeters)
    }

    /**
     * How many of the eight neighbouring squares are written on the QRV photo:
     * none at a square's centre, at least four at a four-square corner, all for
     * a grid chaser.
     */
    fun setPhotoNearCount(n: Int) {
        settings.photoNearCount = n
        _ui.value = _ui.value.copy(photoNearCount = settings.photoNearCount)
    }

    /**
     * Unit system. Nobody converts in his head during a three-minute pass; a
     * balloon chaser thinks in nautical miles and knots.
     */
    fun setUnits(v: String) {
        settings.units = v
        _ui.value = _ui.value.copy(units = settings.units)
    }

    /** Flag before the callsign on the QRV photo. Empty = none. */
    fun setPhotoFlag(code: String) {
        settings.photoFlag = code
        _ui.value = _ui.value.copy(photoFlag = settings.photoFlag)
    }

    /** Flag right of the callsign, from the same catalogue. */
    fun setPhotoFlagRight(code: String) {
        settings.photoFlagRight = code
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(flagRight = settings.photoFlagRight))
    }

    fun setPhotoPolarScale(v: Float) {
        settings.photoPolarScale = v
        _ui.value = _ui.value.copy(photoPolarScale = settings.photoPolarScale)
    }

    /** Size of the satellite name under the polar plot. */
    fun setPhotoSatLabelScale(v: Float) {
        settings.photoSatLabelScale = v
        _ui.value = _ui.value.copy(photoSatLabelScale = settings.photoSatLabelScale)
    }

    /** Callsign colour on the QRV photo (opaque ARGB). */
    fun setPhotoCallColor(argb: Int) {
        settings.photoCallColor = argb
        _ui.value = _ui.value.copy(photoCallColor = settings.photoCallColor)
    }

    /** Callsign size on the QRV photo, clamped by the setting. */
    fun setPhotoCallScale(v: Float) {
        settings.photoCallScale = v
        _ui.value = _ui.value.copy(photoCallScale = settings.photoCallScale)
    }

    /**
     * Neighbouring BIG squares within the configured distance, closest first.
     * The search is always run on the 4-character squares — that is the line an
     * operator actually announces — and only the spelling follows the display
     * precision: "JN19" in short mode, "JN19av" in full mode.
     */
    fun nearLocators(): List<String> {
        val s = _ui.value
        if (s.nearGridMeters <= 0) return emptyList()
        val obs = s.observer ?: return emptyList()
        return runCatching {
            Maidenhead.neighbourFields(obs.latDeg, obs.lonDeg, s.nearGridMeters.toDouble())
                .map { (square, full) -> if (s.photoLoc4) square else full }
        }.getOrDefault(emptyList())
    }

    /**
     * Every big square the station legitimately sits in: ours first, then the
     * neighbours within the configured distance. This is the list that follows
     * the QSO into the log, the ADIF file and the activation sheet — always
     * 4-character squares, since that is what a grid claim is made of.
     */
    fun myGridSquares(): List<String> {
        val mine = myLocator().take(4).uppercase()
        val s = _ui.value
        val obs = s.observer
        val near = if (s.nearGridMeters <= 0 || obs == null) emptyList()
        else runCatching {
            Maidenhead.neighbourFields(obs.latDeg, obs.lonDeg, s.nearGridMeters.toDouble())
                .map { it.first }
        }.getOrDefault(emptyList())
        return (listOf(mine) + near.take(3)).filter { it.length == 4 }.distinct()
    }

    /** The same list, comma-separated, as stored in a log entry. */
    fun myGridsCsv(): String {
        val g = myGridSquares()
        return if (g.size <= 1) "" else g.joinToString(",")
    }

    /** The locator at the chosen precision, plus the squares within reach. */
    fun myLocatorFull(): String {
        val base = if (_ui.value.photoLoc4) myLocator().take(4) else myLocator()
        val near = nearLocators()
        return if (near.isEmpty()) base
        // Up to three: a site pinned in the corner of four big squares really
        // does belong to four locators, and the operator announces them all.
        else base + " / " + near.take(3).joinToString(" / ")
    }

    // ---- AMSAT Live OSCAR Status reporting ----

    /**
     * Reports a satellite as heard (or not) on the AMSAT status page. Only ever
     * called with a callsign set: an anonymous report is worthless to the page
     * and would pollute the table.
     */
    fun submitAmsatStatus(satName: String, heard: Boolean, catnum: Int? = null) {
        val s = _ui.value
        if (s.callsign.isBlank() || satName.isBlank()) return
        // Use the name AMSAT itself publishes when we can match it, so the
        // report lands on the right row ("RS-44", "AO-91"…).
        val amsatName = amsatMatch(satName, s.amsatReports, catnum)?.name ?: catnum?.let { nomsAmsat[it] } ?: satName
        _ui.value = _ui.value.copy(amsatSubmitState = "busy")
        viewModelScope.launch {
            val ok = fr.f4ioz.satcombo.data.AmsatSubmit.send(
                satName = amsatName,
                report = if (heard) fr.f4ioz.satcombo.data.AmsatSubmit.Report.HEARD
                else fr.f4ioz.satcombo.data.AmsatSubmit.Report.NOT_HEARD,
                callsign = s.callsign,
                locator = myLocator())
            _ui.value = _ui.value.copy(amsatSubmitState = if (ok) "ok" else "fail")
            if (ok) refreshAmsatStatus(force = true)
        }
    }

    fun clearAmsatSubmitState() {
        if (_ui.value.amsatSubmitState.isNotBlank())
            _ui.value = _ui.value.copy(amsatSubmitState = "")
    }

    /** Toggles one overlay option of the QRV photo ("call", "date", …). */
    fun setPhotoOption(key: String, on: Boolean) {
        when (key) {
            "call" -> { settings.photoShowCallsign = on; _ui.value = _ui.value.copy(photoShowCallsign = on) }
            "date" -> { settings.photoShowDate = on; _ui.value = _ui.value.copy(photoShowDate = on) }
            "grids" -> { settings.photoShowGrids = on; _ui.value = _ui.value.copy(photoShowGrids = on) }
            "coords" -> { settings.photoShowCoords = on; _ui.value = _ui.value.copy(photoShowCoords = on) }
            "sat" -> { settings.photoShowSat = on; _ui.value = _ui.value.copy(photoShowSat = on) }
            "polar" -> { settings.photoShowPolar = on; _ui.value = _ui.value.copy(photoShowPolar = on) }
            "pass" -> { settings.photoShowPass = on; _ui.value = _ui.value.copy(photoShowPass = on) }
            "loc4" -> { settings.photoLoc4 = on; _ui.value = _ui.value.copy(photoLoc4 = on) }
            "alt" -> { settings.photoShowAlt = on; _ui.value = _ui.value.copy(photoShowAlt = on) }
        }
    }

    // `estAuteur()` was removed: the "Location" section it guarded is open to
    // all since 20.58. A guard with nothing behind it ends up used for
    // something else.

    /**
     * The flag code, if the unlocked extensions allow it. [gated]: whether this
     * slot still needs the "flag" keyword (left: no, right: yes). The BZH filter
     * applies to both sides. BZH is in `Extensions.OPEN` by the author's choice,
     * so it currently passes everything; it stays so that closing BZH again
     * takes one line in `Extensions`.
     */
    private fun photoFlagOrNothing(s: UiState, code: String, gated: Boolean): String {
        if (gated && fr.f4ioz.satcombo.data.Extensions.FLAG !in s.extensions) return ""
        val bzh = fr.f4ioz.satcombo.data.Extensions.BZH in s.extensions
        return if (fr.f4ioz.satcombo.data.Flags.allowed(code, bzh)) code else ""
    }

    /**
     * Opens the QRV photo page. [catnum] is the satellite the picture is about:
     * passed explicitly from the satellite screen, left null when the page is
     * opened from the global menu (the operator then picks it on the page).
     */
    fun openPhoto(catnum: Int? = null, passAosMs: Long? = null) {
        // Arriving with a satellite in hand — tapped in the header, or simply
        // the one open behind the menu — the page is about THAT satellite. The
        // kept picture reopens as a background, but it no longer drags its own
        // satellite and pass back in on top of the choice just made.
        val pinned = catnum != null || _ui.value.selected != null
        va(Screen.PHOTO)
        _ui.value = _ui.value.copy(
            screen = Screen.PHOTO,
            photoPinnedSat = pinned)
        // The satellite the operator is looking at wins over whatever the photo
        // page was left on: coming from a satellite screen, the picture is about
        // THAT satellite, not the one used the last time the page was opened.
        val cat = catnum ?: _ui.value.selected?.catalogNumber ?: _ui.value.photoSatCat
        // Arriving from a pass page, the picture is about the pass being looked
        // at — the one prepared for tonight as much as the one just worked. The
        // operator has already chosen; the page must not choose again for him.
        val want = passAosMs ?: _ui.value.focusedPassAos
        if (cat != null) setPhotoSat(cat, want)
        else if (_ui.value.photoSatCat == null) setPhotoSat(null)
    }

    // ---- Update: we detect, the Play Store does the rest ----

    /**
     * The Store has a newer version. Offered once per version: a "later" is
     * remembered, so we never nag twice for the same one.
     */
    fun updateFound(code: Int) {
        if (code <= settings.updateSkipped) return
        _ui.value = _ui.value.copy(updateCode = code, updateMsg = "")
    }

    /** Something failed and must be said. A failed version is never marked as
     *  seen: it must come round again. */
    fun updateFailed(msg: String) {
        settings.updateSkipped = 0
        _ui.value = _ui.value.copy(updateCode = 0, updateMsg = msg)
    }

    /** Simple status line (up to date, no Store…), swiped away. */
    fun updateSay(msg: String) { _ui.value = _ui.value.copy(updateMsg = msg) }
    fun updateMsgClear() { _ui.value = _ui.value.copy(updateMsg = "") }

    /** Manual check: forget the "later" so the same version can be offered again. */
    fun updateForget() {
        settings.updateSkipped = 0
        _ui.value = _ui.value.copy(updateMsg = "")
    }

    /**
     * Going to the Store page. The version is marked handled: if the operator
     * comes back without updating, the prompt does not reappear on every
     * launch. The next published version will be announced.
     */
    fun updateOpened() {
        if (_ui.value.updateCode > 0) settings.updateSkipped = _ui.value.updateCode
        _ui.value = _ui.value.copy(updateCode = 0, updateMsg = "")
    }

    fun updateLater() {
        if (_ui.value.updateCode > 0) settings.updateSkipped = _ui.value.updateCode
        _ui.value = _ui.value.copy(updateCode = 0)
    }

    fun closePhoto() { _ui.value = retour() }

    /**
     * Finds the satellite back from the name alone and rebinds the photo to it,
     * WITHOUT moving the pass already stamped on the picture. Used for pictures
     * kept before the catalogue number was stored, and as a safety net whenever
     * the page ends up naming a satellite it cannot identify: that state is what
     * made the pass combo box disappear.
     */
    fun rebindPhotoSatByName(): Boolean {
        val s = _ui.value
        if (s.photoSatCat != null || s.photoSatName.isBlank()) return false
        val want = s.photoSatName.trim()
        val sat = s.satellites.firstOrNull { it.name.trim().equals(want, ignoreCase = true) }
            ?: return false
        setPhotoSat(sat.catalogNumber, s.photoPassAosMs.takeIf { it > 0L }, keepPass = true)
        return true
    }

    /**
     * Binds the photo to a satellite and grabs the arc of the pass being worked
     * — the one in progress, otherwise the one that has just finished, otherwise
     * the next one — so the polar plot on the picture is the right one.
     *
     * [keepPass] leaves the pass already chosen alone when the prediction window
     * does not contain it — reopening a picture shot last month must not stamp
     * it with a pass of today.
     */
    fun setPhotoSat(catnum: Int?, wantAosMs: Long? = null, keepPass: Boolean = false) {
        if (catnum == null) {
            _ui.value = _ui.value.copy(photoSatCat = null, photoSatName = "",
                photoTrack = emptyList(), photoPassLabel = "",
                photoPassAosMs = 0L, photoPassElDeg = 0, photoPasses = emptyList())
            return
        }
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        _ui.value = _ui.value.copy(photoSatCat = catnum, photoSatName = sat.name,
            photoPasses = emptyList())
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // A day behind and two ahead: enough to cover the pass just worked
            // as well as the one being prepared, without a costly prediction.
            val list = withContext(Dispatchers.Default) {
                runCatching {
                    predictor.upcomingPasses(sat, obs, fromMs = now - 24 * 3600_000L,
                        hours = 72, minElDeg = 0.0)
                }.getOrDefault(emptyList())
            }
            if (_ui.value.photoSatCat != catnum) return@launch
            // A wanted pass comes from the page the operator arrived from. Its
            // AOS is matched loosely: this prediction runs down to 0° elevation
            // while the pass list is filtered higher, so the same pass can start
            // a few minutes earlier here. Beyond half an hour it is another pass
            // and the usual choice applies.
            val wanted = wantAosMs?.let { w ->
                list.minByOrNull { kotlin.math.abs(it.aosEpochMs - w) }
                    ?.takeIf { kotlin.math.abs(it.aosEpochMs - w) < 30 * 60_000L }
            }
            val auto = if (keepPass) null
                else list.firstOrNull { now in it.aosEpochMs..it.losEpochMs }
                    ?: list.lastOrNull { it.losEpochMs <= now }
                    ?: list.firstOrNull { it.aosEpochMs > now }
            val pass = wanted ?: auto
            // Rebinding an old picture: the list of passes is refreshed, the
            // pass burnt into it is not touched.
            if (pass == null && keepPass) {
                _ui.value = _ui.value.copy(photoPasses = list)
                return@launch
            }
            _ui.value = _ui.value.copy(photoPasses = list,
                photoTrack = pass?.track ?: emptyList(),
                photoPassLabel = pass?.let { passLabel(it) } ?: "",
                photoPassAosMs = pass?.aosEpochMs ?: 0L,
                photoPassElDeg = pass?.maxElevationDeg?.toInt() ?: 0)
        }
    }

    /** "28/07 11:03 UTC · 67°" — the one wording used everywhere a pass is named. */
    fun passLabel(aosMs: Long, elDeg: Int): String {
        if (aosMs <= 0L) return ""
        val f = fr.f4ioz.satcombo.ui.tzFormat("dd/MM HH:mm", _ui.value.useUtc)
        return "${f.format(java.util.Date(aosMs))} ${fr.f4ioz.satcombo.ui.tzTag(_ui.value.useUtc)} · ${elDeg}°"
    }

    fun passLabel(p: SatPass): String = passLabel(p.aosEpochMs, p.maxElevationDeg.toInt())

    /**
     * Puts the photo on another pass of the same satellite — the one just
     * finished, or the one coming. Label, arc and stamped values move together,
     * so what the picker shows and what is burnt into the picture can never
     * describe two different passes.
     */
    fun setPhotoPass(aosMs: Long) {
        val p = _ui.value.photoPasses.firstOrNull { it.aosEpochMs == aosMs } ?: return
        _ui.value = _ui.value.copy(
            photoTrack = p.track,
            photoPassLabel = passLabel(p),
            photoPassAosMs = p.aosEpochMs,
            photoPassElDeg = p.maxElevationDeg.toInt())
    }

    // ---- camera capture in flight ----

    /**
     * Remembers where the camera app has been told to write. The ViewModel
     * survives a rotation and the preference survives even a process death, so
     * the shot is never orphaned — which is exactly what happened when the
     * phone was turned to frame a landscape picture.
     */
    fun armCapture(file: java.io.File) { settings.pendingCapture = file.absolutePath }

    fun pendingCaptureFile(): java.io.File? =
        settings.pendingCapture.takeIf { it.isNotBlank() }?.let { java.io.File(it) }

    fun clearCapture() { settings.pendingCapture = "" }

    // ---- kept pictures ----

    /** Stores the untouched shot in the album and makes it the current one. */
    fun keepPhoto(bmp: android.graphics.Bitmap, timeMs: Long) {
        val s = _ui.value
        val obs = s.observer
        val meta = fr.f4ioz.satcombo.data.QrvPhoto(
            id = timeMs, locator = myLocator(), callsign = s.callsign,
            satName = s.photoSatName, satCat = s.photoSatCat ?: 0,
            latDeg = obs?.latDeg ?: 0.0, lonDeg = obs?.lonDeg ?: 0.0,
            track = s.photoTrack, passMs = s.photoPassAosMs, passElDeg = s.photoPassElDeg)
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { qrvPhotoStore.add(bmp, meta) }
            _ui.value = _ui.value.copy(qrvPhotos = list, photoCurrentId = timeMs)
        }
    }

    fun deleteQrvPhoto(id: Long) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { qrvPhotoStore.delete(id) }
            _ui.value = _ui.value.copy(qrvPhotos = list,
                photoCurrentId = if (_ui.value.photoCurrentId == id) null else _ui.value.photoCurrentId)
        }
    }

    /** Re-opens a kept picture, restoring its satellite and its pass arc. */
    fun openQrvPhoto(id: Long, restoreMeta: Boolean = true,
                     onReady: (android.graphics.Bitmap?) -> Unit) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { qrvPhotoStore.bitmapOf(id) }
            val meta = _ui.value.qrvPhotos.firstOrNull { it.id == id }
            // Opening a kept picture on purpose brings its satellite and pass
            // back; reopening it merely to fill the page must not.
            if (restoreMeta) _ui.value = _ui.value.copy(photoPinnedSat = false)
            _ui.value = _ui.value.copy(photoCurrentId = id)
            if (meta != null && restoreMeta) {
                _ui.value = _ui.value.copy(
                    photoSatName = meta.satName, photoTrack = meta.track,
                    // The catalogue number comes back WITH the name. Restoring
                    // the name alone left the page unable to list the passes of
                    // the satellite it was naming, and the pass combo box —
                    // hidden when there is no catalogue number — vanished.
                    photoSatCat = meta.satCat.takeIf { it > 0 } ?: _ui.value.photoSatCat,
                    photoPassAosMs = meta.passMs, photoPassElDeg = meta.passElDeg,
                    // Without this the header kept naming the pass of the live
                    // satellite while the picture showed the one it was shot on.
                    photoPassLabel = passLabel(meta.passMs, meta.passElDeg))
                // Pictures kept before v16.3 only stored the name: find the
                // satellite back from it so the passes can be listed again.
                if (meta.satCat <= 0) rebindPhotoSatByName()
            }
            onReady(bmp)
        }
    }

    fun qrvPhotoFile(id: Long): java.io.File = qrvPhotoStore.fileOf(id)

    /** The locator of the current position (GPS or manual), 6 characters. */
    fun myLocator(): String {
        val obs = _ui.value.observer
        val loc = if (obs != null) Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg)
        else _ui.value.manualLocator.uppercase()
        // The SSTV decoder runs in a service with no GPS of its own: hand it
        // the locator so it can write it next to the received image.
        fr.f4ioz.satcombo.sstv.SstvHub.qthLocator = loc
        return loc
    }

    /** The launcher icon, decoded once and reused on every render. */
    private val appIcon: android.graphics.Bitmap? by lazy {
        fr.f4ioz.satcombo.data.QthPhoto.appIcon(getApplication())
    }

    /** Overlay options built from the settings + the live position. */
    fun photoOptions(timeMs: Long = System.currentTimeMillis()): fr.f4ioz.satcombo.data.QthPhoto.Options {
        val s = _ui.value
        val obs = s.observer
        val running = s.activations.firstOrNull { it.running }
        // The satellite explicitly attached to the photo wins; otherwise fall
        // back on whatever is open, then on the last one worked in the session.
        val sat = s.photoSatName.ifBlank {
            s.selected?.name
                ?: running?.let { a -> fr.f4ioz.satcombo.data.ActivationStore.satsOf(a, s.log).lastOrNull() }
                ?: ""
        }
        return fr.f4ioz.satcombo.data.QthPhoto.Options(
            callsign = s.callsign,
            locator = myLocator(),
            latDeg = obs?.latDeg ?: 0.0,
            lonDeg = obs?.lonDeg ?: 0.0,
            satName = sat,
            showCallsign = s.photoShowCallsign,
            showDate = s.photoShowDate,
            showGrids = s.photoShowGrids,
            showCoords = s.photoShowCoords,
            // **The logo is permanent.** The SatMe mark signs every shared
            // photo; nobody can remove it, author included.
            showLogo = true,
            showSat = s.photoShowSat,
            showPolar = s.photoShowPolar,
            showCarte = s.carte.affichee,
            showPota = s.carte.potaAffiche,
            potaRef = s.carte.potaRef,
            potaNom = s.carte.potaNom,
            carteAnneaux = s.carte.anneaux,
            carteTaille = s.carte.taille,
            carteX = s.carte.x,
            carteY = s.carte.y,
            carteRemplissage = s.carte.remplissage,
            carteContenu = s.carte.contenu,
            potaTaille = s.carte.potaTaille,
            potaMonte = s.carte.potaMonte,
            carteCouleur = s.carte.couleur,
            zoneAnneaux = s.carte.zoneAnneaux,
            villes = s.carte.villes,
            carteFondu = s.carte.fondu,
            potaNomAffiche = s.carte.potaNomAffiche,
            showQrg = s.carte.qrgAffiche,
            qrgScale = s.carte.qrgScale,
            qrgTexte = s.carte.qrgTexte,
            passScale = s.carte.passScale,
            upMHz = activeTransmitters().getOrNull(s.selectedTxIndex)
                ?.uplinkLowHz?.let { it / 1_000_000.0 } ?: 0.0,
            downMHz = (s.rxRestHz ?: activeTransmitters().getOrNull(s.selectedTxIndex)
                ?.downlinkLowHz)?.let { it / 1_000_000.0 } ?: 0.0,
            track = s.photoTrack,
            useUtc = s.useUtc,
            timeMs = timeMs,
            nearLocators = nearLocators(),
            passMs = s.photoPassAosMs,
            passElDeg = s.photoPassElDeg,
            showPass = s.photoShowPass,
            polarScale = s.photoPolarScale,
            satLabelScale = s.photoSatLabelScale,
            loc4 = s.photoLoc4,
            // Altitude only when measured: a hand-typed locator leaves it at
            // zero, and "0 m" on a mountain activation is worse than nothing.
            altM = if (s.photoShowAlt) obs?.altMeters?.takeIf {
                it != 0.0 && !it.isNaN() } else null,
            callColor = s.photoCallColor,
            callScale = s.photoCallScale,
            nearCount = s.photoNearCount,
            units = s.units,
            // Flags go through the extension filter (see photoFlagOrNothing);
            // FLAG and BZH are in OPEN today, so it lets everything through.
            flagLeft = photoFlagOrNothing(s, s.photoFlag, gated = false),
            flagsRight = listOfNotNull(
                photoFlagOrNothing(s, s.carte.flagRight, gated = true)
                    .takeIf { it.isNotBlank() }),
            logoIcon = appIcon
        )
    }

    // ================= activations (field sessions) =================

    fun openActivation() { va(Screen.ACTIVATION); _ui.value = _ui.value.copy(screen = Screen.ACTIVATION) }
    fun closeActivation() { _ui.value = retour() }

    // ================= Agenda (personal appointments) =================

    /**
     * Not a beta extension: a time reminder depends on no hardware and cannot
     * break anything, so it is open to all.
     */
    fun openAgenda() { va(Screen.AGENDA); _ui.value = _ui.value.copy(screen = Screen.AGENDA) }
    fun closeAgenda() { _ui.value = retour() }

    /**
     * Re-reads the agenda from disk, at startup and after every change on the
     * Agenda screen: the pass list shows a badge for appointments, and a badge
     * appearing only at next launch would be useless.
     */
    fun refreshAgenda() {
        _ui.value = _ui.value.copy(
            agenda = fr.f4ioz.satcombo.data.AgendaStore.load(getApplication()))
    }

    /**
     * The agenda appointment falling during this pass, if any.
     *
     * Its time must fall within the pass window with five minutes' slack each
     * side (nobody notes an appointment to the second: "ISS SSTV at 14:30"
     * applies to the pass starting at 14:32), and the satellite must match.
     * An appointment without a satellite attaches to no pass. One whose
     * reminder is off still shows: muting the reminder is not deleting the note.
     */
    fun agendaForPass(p: SatPass): fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? {
        val list = _ui.value.agenda
        if (list.isEmpty()) return null
        return list.firstOrNull { e ->
            e.satName.isNotBlank() &&
                e.covers(p.aosEpochMs, p.losEpochMs) &&
                sameSatName(e.satName, p.satName)
        }
    }

    /**
     * The not-yet-finished appointment for this satellite (satellite list and
     * satellite page header). The nearest upcoming one, not only a current
     * one: a slot announced three weeks ahead deserves to be seen now. Once
     * over, the mark disappears — an agenda keeping old marks ends up
     * signalling nothing.
     */
    fun agendaForSat(satName: String): fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? {
        val list = _ui.value.agenda
        if (list.isEmpty() || satName.isBlank()) return null
        val now = System.currentTimeMillis()
        return list.filter {
            it.satName.isNotBlank() && it.endOrStartMs >= now && sameSatName(it.satName, satName)
        }.minByOrNull { it.timeMs }
    }

    /**
     * Appointments within the requested period. The date filter is used to
     * prepare a given day; showing them at the top saves hunting badges pass
     * by pass.
     */
    fun agendaInRange(fromMs: Long, toMs: Long): List<fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent> =
        _ui.value.agenda.filter { it.overlaps(fromMs, toMs) }.sortedBy { it.timeMs }

    /**
     * The appointment imposing a frequency for this satellite at this time
     * (only those carrying a frequency). [refMs] is the AOS of the viewed pass,
     * else now, since a pass page is often opened before the pass starts.
     */
    private fun agendaFreqFor(satName: String, refMs: Long):
        fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? =
        _ui.value.agenda.firstOrNull {
            it.freqHz > 0L && it.satName.isNotBlank() &&
                it.activeAt(refMs) && sameSatName(it.satName, satName)
        }

    /**
     * Two satellite names for the same spacecraft. Loose both ways: the
     * operator writes "ISS" for "ISS (ZARYA)", "AO-91" for "FOX-1B (AO-91)".
     * Too loose shows one badge too many; too strict misses the appointment.
     */
    private fun sameSatName(a: String, b: String): Boolean {
        val x = a.trim().uppercase()
        val y = b.trim().uppercase()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    // ================= SSTV =================

    fun openSstv() {
        // Beta lock: the menu already hides it, but a future shortcut or a
        // restored state must not get through.
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SSTV)) return
        va(Screen.SSTV)
        _ui.value = _ui.value.copy(screen = Screen.SSTV)
    }
    fun closeSstv() { _ui.value = retour() }

    fun openAprs() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.APRS)) return
        va(Screen.APRS)
        _ui.value = _ui.value.copy(screen = Screen.APRS)
    }
    fun closeAprs() { _ui.value = retour() }

    /** APRS decoding while recording: read from the settings, not `UiState` (255-register limit). */
    fun aprsActif(): Boolean = settings.aprsEnabled
    fun setAprsActif(v: Boolean) { settings.aprsEnabled = v }

    // ------------------------------------------------------ APRS transmit

    /** What the last transmit or test gave, for the APRS page (not in `UiState`). */
    val aprsEnvoi = kotlinx.coroutines.flow.MutableStateFlow("")
    /** A frame is on its way: the Doppler loop leaves the rig alone meanwhile. */
    @Volatile private var aprsEnEmission = false
    private var aprsDerniereMs = 0L

    fun aprsFt3dCle(): String = settings.aprsFt3dCle
    fun aprsFt3dVitesse(): Int = settings.aprsFt3dVitesse
    fun ft3dConnecte(cle: String, vitesse: Int) {
        settings.aprsFt3dCle = cle; settings.aprsFt3dVitesse = vitesse
        viewModelScope.launch { fr.f4ioz.satcombo.aprs.RecepteurWaypoints.connecte(getApplication(), cle, vitesse) }
    }
    fun ft3dDeconnecte() { fr.f4ioz.satcombo.aprs.RecepteurWaypoints.deconnecte() }

    fun aprsMode(): String = settings.aprsMode
    fun setAprsMode(m: String) { settings.aprsMode = m }

    fun aprsSsid(): Int = settings.aprsSsid
    fun setAprsSsid(v: Int) { settings.aprsSsid = v }
    fun aprsNiveau(): Float = settings.aprsNiveau
    fun setAprsNiveau(v: Float) { settings.aprsNiveau = v }

    /** The sender: the callsign from the settings, with the chosen SSID. */
    fun aprsSource(): String = settings.callsign.trim().uppercase().let {
        if (settings.aprsSsid > 0 && it.isNotEmpty()) "$it-${settings.aprsSsid}" else it
    }

    /** Next message number (1..999), so an ack can be matched. */
    fun aprsNumeroSuivant(): String {
        val n = settings.aprsNumero % 999 + 1
        settings.aprsNumero = n
        return n.toString()
    }

    /**
     * The frame's audio, checked by decoding it back before anything is
     * played: a frame that does not come out of our own decoder does not go
     * on the air either.
     */
    private fun aprsAudio(t: fr.f4ioz.satcombo.aprs.Trame): ShortArray? {
        val octets = fr.f4ioz.satcombo.aprs.Ax25.encode(t)
        val pcm = fr.f4ioz.satcombo.aprs.Afsk.module(listOf(octets),
            fr.f4ioz.satcombo.aprs.SortieAudio.FREQUENCE, settings.aprsNiveau.toDouble(), drapeauxAvant = 40)
        // 150 ms of silence on each side: a glitch as playback starts or stops
        // (common on Android) falls there, not in the frame.
        val marge = ShortArray(fr.f4ioz.satcombo.aprs.SortieAudio.FREQUENCE * 15 / 100)
        val complet = marge + pcm + marge
        val relues = ArrayList<fr.f4ioz.satcombo.aprs.Trame>()
        fr.f4ioz.satcombo.aprs.AfskDemodulateur(fr.f4ioz.satcombo.aprs.SortieAudio.FREQUENCE) { relues += it }.traite(complet)
        return complet.takeIf { relues.singleOrNull() == t }
    }

    /** Test without transmitting: on the phone's speaker, or as a WAV to share. */
    fun aprsEssai(trame: fr.f4ioz.satcombo.aprs.Trame, fichier: Boolean, partage: (java.io.File) -> Unit) {
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.Default) { aprsAudio(trame) }
            if (pcm == null) { aprsEnvoi.value = t("aprs_tx_controle"); return@launch }
            if (fichier) {
                // With the SSTV test cards (shared the same way, reachable over USB).
                val f = withContext(Dispatchers.IO) {
                    val d = getApplication<android.app.Application>().getExternalFilesDir("mires")!!.apply { mkdirs() }
                    fr.f4ioz.satcombo.aprs.SortieAudio.wav(pcm, java.io.File(d, "SatMe_APRS_essai.wav"))
                }
                aprsEnvoi.value = t("aprs_tx_wav")
                partage(f)
            } else {
                aprsEnvoi.value = t("aprs_tx_hp_en_cours")
                val ok = fr.f4ioz.satcombo.aprs.SortieAudio.joue(pcm, null)
                aprsEnvoi.value = if (ok) t("aprs_tx_hp_fini") else t("aprs_tx_audio")
            }
        }
    }

    // ------------------------------------------------ KISS radio (TH-D72…)

    fun aprsKissCle(): String = settings.aprsKissCle
    fun aprsKissVitesse(): Int = settings.aprsKissVitesse

    /**
     * Connects and switches straight to KISS, tuned as chosen: without KISS
     * the radio decodes for itself and nothing reaches the phone.
     */
    fun kissConnecte(cle: String, vitesse: Int) {
        settings.aprsKissCle = cle
        settings.aprsKissVitesse = vitesse
        viewModelScope.launch {
            if (fr.f4ioz.satcombo.aprs.TncKiss.connecte(getApplication(), cle, vitesse)) {
                fr.f4ioz.satcombo.aprs.TncKiss.passeEnKiss(aprsKissFrequenceVoulue())
                aprsKissChangeMs = System.currentTimeMillis()
            }
        }
    }

    fun kissPasseEnKiss() {
        viewModelScope.launch { fr.f4ioz.satcombo.aprs.TncKiss.passeEnKiss(aprsKissFrequenceVoulue()) }
    }

    fun aprsTravail(): String = settings.aprsTravail
    /** A new choice is applied at once: KISS radio retuned, IC-9700 listening moved. */
    fun setAprsTravail(v: String) {
        settings.aprsTravail = v
        if (aprsEcouteEnCours) viewModelScope.launch { runCatching { aprsAudioTic(force = true) } }
        val hz = aprsKissFrequenceVoulue() ?: return
        if (!fr.f4ioz.satcombo.aprs.TncKiss.etat.value.connecte) return
        viewModelScope.launch {
            fr.f4ioz.satcombo.aprs.TncKiss.regleFrequence(hz)
            aprsKissChangeMs = System.currentTimeMillis()
        }
    }

    /** The ISS is up, or rises within a minute. */
    private fun issEnVue(): Boolean {
        val sat = iss() ?: return false
        val obs = _ui.value.observer ?: return false
        val now = System.currentTimeMillis()
        return listOf(now, now + 60_000L).any {
            (runCatching { predictor.positionAt(sat, obs, it).elevationDeg }.getOrNull() ?: -90.0) > 0.0
        }
    }

    /** Where the KISS radio should be now, or null to leave it. */
    private fun aprsKissFrequenceVoulue(): Long? {
        val base = when (settings.aprsTravail) {
            "TERRE" -> 144_800_000L
            "ISS" -> 145_825_000L
            "AUTO" -> if (issEnVue()) 145_825_000L else 144_800_000L
            else -> return null
        }
        return if (base == 145_825_000L && settings.aprsKissDoppler) base + aprsPalierDoppler(base) else base
    }

    /**
     * The ISS Doppler at [hz], in whole 5 kHz steps (the TH-D72's finest
     * grid for 145.825): +5 kHz early in the pass, 0 around its highest
     * point, −5 kHz at the end. Zero when the ISS is down.
     */
    private fun aprsPalierDoppler(hz: Long): Long {
        val sat = iss() ?: return 0L
        val obs = _ui.value.observer ?: return 0L
        val p = runCatching { predictor.positionAt(sat, obs, System.currentTimeMillis()) }.getOrNull() ?: return 0L
        if (p.elevationDeg <= 0.0) return 0L
        val decalage = -hz * p.rangeRateKmS / 299_792.458
        return kotlin.math.round(decalage / 5_000.0).toLong() * 5_000L
    }

    fun aprsKissDoppler(): Boolean = settings.aprsKissDoppler
    fun setAprsKissDoppler(v: Boolean) { settings.aprsKissDoppler = v }

    private var aprsKissChangeMs = 0L

    /**
     * Automatic frequency: 145.825 MHz while the ISS is up, 144.800 MHz
     * otherwise. A switch costs some 5 s of deafness, so at most one a minute.
     */
    private suspend fun aprsKissTic() {
        if (settings.aprsMode != "KISS") return
        if (settings.aprsTravail != "AUTO" && !(settings.aprsTravail == "ISS" && settings.aprsKissDoppler)) return
        val k = fr.f4ioz.satcombo.aprs.TncKiss.etat.value
        if (!k.connecte || !k.initialise || k.frequenceHz == null) return
        val hz = aprsKissFrequenceVoulue() ?: return
        if (k.frequenceHz == hz) return
        if (System.currentTimeMillis() - aprsKissChangeMs < 60_000L) return
        aprsKissChangeMs = System.currentTimeMillis()
        fr.f4ioz.satcombo.aprs.TncKiss.regleFrequence(hz)
    }

    fun kissDeconnecte() {
        viewModelScope.launch(Dispatchers.IO) { fr.f4ioz.satcombo.aprs.TncKiss.deconnecte() }
    }

    /**
     * One frame to the KISS radio, which keys and returns to receive by
     * itself. SatMe cannot read its frequency in KISS mode: the operator
     * confirms it; the rest of the checks are the same as for the IC-9700.
     */
    fun aprsEmetKiss(trame: fr.f4ioz.satcombo.aprs.Trame, frequenceConfirmee: Boolean) {
        viewModelScope.launch {
            if (!fr.f4ioz.satcombo.aprs.TncKiss.etat.value.connecte) {
                aprsEnvoi.value = t("aprs_kiss_non_connecte"); return@launch
            }
            // Frequency read (or set) before KISS (TH-D72 "FO"), when there is one: it must be right too.
            val lue = fr.f4ioz.satcombo.aprs.TncKiss.etat.value.frequenceHz
            // Set by SatMe itself on an APRS frequency: no need for the operator to vouch for it.
            val connue = lue != null && fr.f4ioz.satcombo.aprs.AprsEmission.FENETRES.any { lue in it }
            if (!frequenceConfirmee && !connue) { aprsEnvoi.value = t("aprs_kiss_confirmer"); return@launch }
            val raison = fr.f4ioz.satcombo.aprs.AprsEmission.refus(trame.source.indicatif,
                System.currentTimeMillis(), aprsDerniereMs, lue ?: 145_825_000L, 0x05, false, false)
            if (raison != null) {
                aprsEnvoi.value = if (raison == "frequence") tf("aprs_tx_refus_frequence",
                    "%.4f".format(java.util.Locale.US, (lue ?: 0L) / 1e6)) else t("aprs_tx_refus_$raison")
                return@launch
            }
            if (fr.f4ioz.satcombo.aprs.TncKiss.envoie(trame)) {
                aprsDerniereMs = System.currentTimeMillis()
                fr.f4ioz.satcombo.aprs.AprsHub.ajouteEmis(getApplication(), trame)
                aprsEnvoi.value = t("aprs_kiss_ok")
            } else aprsEnvoi.value = t("aprs_kiss_echec")
        }
    }

    /** The IC-9700 as seen by APRS transmit. */
    private val posteAprs = object : fr.f4ioz.satcombo.aprs.PosteAprs {
        override suspend fun frequence() = cat.readFrequency()
        override suspend fun mode() = cat.readMode()
        override suspend fun modeSatellite() = cat.readSatelliteMode()
        override suspend fun emission(on: Boolean) = cat.setTransmit(on)
        override suspend fun enEmission() = cat.isTransmitting()
    }

    /**
     * Sends one frame through the IC-9700: checks, key, audio on the rig's USB
     * sound card, unkey. Everything that can go wrong is said on the page.
     */
    fun aprsEmet(trame: fr.f4ioz.satcombo.aprs.Trame) {
        if (aprsEnEmission) return
        viewModelScope.launch {
            val app = getApplication<android.app.Application>()
            val u = _ui.value
            if (!u.catConnected) { aprsEnvoi.value = t("aprs_tx_cat"); return@launch }
            if (u.rigModel != "IC9700" || isPairRig) { aprsEnvoi.value = t("aprs_tx_ic9700"); return@launch }
            val carte = fr.f4ioz.satcombo.aprs.SortieAudio.carteDuPoste(app)
                ?: run { aprsEnvoi.value = t("aprs_tx_carte_son"); return@launch }
            aprsEnEmission = true
            try {
                val hz = posteAprs.frequence()
                val raison = fr.f4ioz.satcombo.aprs.AprsEmission.refus(
                    trame.source.indicatif, System.currentTimeMillis(), aprsDerniereMs,
                    hz, posteAprs.mode(), posteAprs.modeSatellite(), posteAprs.enEmission())
                if (raison != null) {
                    aprsEnvoi.value = if (raison == "frequence") tf("aprs_tx_refus_frequence",
                        "%.4f".format(java.util.Locale.US, (hz ?: 0L) / 1e6)) else t("aprs_tx_refus_$raison")
                    return@launch
                }
                val pcm = withContext(Dispatchers.Default) { aprsAudio(trame) }
                    ?: run { aprsEnvoi.value = t("aprs_tx_controle"); return@launch }
                aprsEnvoi.value = t("aprs_tx_en_cours")
                val duree = pcm.size * 1000L / fr.f4ioz.satcombo.aprs.SortieAudio.FREQUENCE
                val r = fr.f4ioz.satcombo.aprs.AprsEmission.emet(posteAprs, duree) {
                    fr.f4ioz.satcombo.aprs.SortieAudio.joue(pcm, carte)
                }
                if (r == null) {
                    aprsDerniereMs = System.currentTimeMillis()
                    fr.f4ioz.satcombo.aprs.AprsHub.ajouteEmis(app, trame)
                    aprsEnvoi.value = tf("aprs_tx_ok", "%.4f".format(java.util.Locale.US, (hz ?: 0L) / 1e6))
                } else aprsEnvoi.value = t("aprs_tx_echec_$r")
            } finally {
                aprsEnEmission = false
            }
        }
    }

    /** The phone is listening for APRS (button on the APRS page), until the recording stops. */
    @Volatile private var aprsEcouteEnCours = false
    /** The IC-9700 was put on 144.800 by APRS (Doppler held): to undo when done. */
    @Volatile private var aprsTerreActive = false

    /** Listening right now (for the APRS page). */
    fun aprsEcoute(): Boolean = aprsEcouteEnCours && _ui.value.recording

    /** The path for what we send now: the ISS digipeater, or the terrestrial network. */
    fun aprsCheminParDefaut(): List<String> = when (settings.aprsTravail) {
        "ISS" -> listOf("ARISS")
        "TERRE" -> listOf("WIDE1-1", "WIDE2-1")
        else -> if (issEnVue()) listOf("ARISS") else listOf("WIDE1-1", "WIDE2-1")
    }

    /** Working the ISS right now (by choice, or in Auto while it is up). */
    private fun aprsSurIss(): Boolean = when (settings.aprsTravail) {
        "ISS" -> true; "TERRE" -> false; else -> issEnVue()
    }

    /**
     * APRS with the phone and the IC-9700, in one tap. On the ISS: the ISS
     * and its APRS transmitter chosen, CAT connected (145.825 MHz FM,
     * Doppler). Terrestrial: CAT connected, Doppler held, the rig on 144.800
     * MHz FM out of satellite mode. Auto moves between the two as the ISS
     * rises and sets. Then decoding on and the recording started — the
     * decoder listens to what is recorded.
     */
    fun aprsEcouteDemarre() {
        viewModelScope.launch {
            settings.aprsEnabled = true
            aprsEcouteEnCours = true
            val app = getApplication<android.app.Application>()
            // The TH-D72's one cable carries KISS here: its CAT is left alone.
            if (_ui.value.rigModel != THD72) {
                if (!_ui.value.catEnabled) setCatEnabled(true)
                else if (!_ui.value.catConnected) connectCat()
                kotlinx.coroutines.withTimeoutOrNull(6_000) { while (!_ui.value.catConnected) delay(200) }
            }
            aprsAudioTic(force = true)
            if (!_ui.value.recording) {
                if (settings.aprsTravail == "ISS") startRecording()   // stops by itself after the pass
                else fr.f4ioz.satcombo.audio.RecorderService.start(app, "APRS", null,
                    _ui.value.recorderSource, _ui.value.recorderUnprocessed, myLocator())
            }
        }
    }

    fun aprsEcouteArrete() {
        stopRecording()
        aprsEcouteEnCours = false
        viewModelScope.launch { aprsLibereTerre() }
    }

    /** Gives the IC-9700 back to satellite tracking (Doppler released). */
    private fun aprsLibereTerre() {
        if (!aprsTerreActive) return
        aprsTerreActive = false
        if (_ui.value.dopplerHold) toggleDopplerHold()
    }

    /** Picks the ISS and its APRS transmitter (the Doppler loop then tunes 145.825 FM). */
    private suspend fun aprsChoisitIss(): Boolean {
        val sat = iss() ?: run { aprsEnvoi.value = t("aprs_iss_absente"); return false }
        if (_ui.value.selected?.catalogNumber != sat.catalogNumber) select(sat)
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            while (_ui.value.transmittersLoading || _ui.value.selected?.catalogNumber != sat.catalogNumber) delay(100)
        }
        val i = activeTransmitters().indexOfFirst { t ->
            val m = t.mode.orEmpty().uppercase()
            m.contains("AFSK") || m.contains("APRS") || t.description.uppercase().contains("APRS") ||
                t.downlinkLowHz?.let { it in 145_815_000L..145_835_000L } == true
        }
        if (i < 0) { aprsEnvoi.value = t("aprs_iss_sans_transpondeur"); return false }
        if (i != _ui.value.selectedTxIndex) selectTransmitter(i)
        return true
    }

    /** The IC-9700 on terrestrial APRS: Doppler held, out of satellite mode, 144.800 MHz FM. */
    private suspend fun aprsIc9700Terre(): Boolean {
        if (!_ui.value.catConnected || _ui.value.rigModel != "IC9700" || isPairRig) return false
        if (!_ui.value.dopplerHold) toggleDopplerHold()
        aprsTerreActive = true
        catArmedFor = null; catArmedTxDesc = null
        return runCatching {
            cat.setSatelliteMode(false)
            cat.setSplitOn(false)
            cat.selectVfo(false)
            cat.setFrequency(144_800_000L)
            cat.setMode(0x05)
        }.getOrDefault(false)
    }

    /**
     * Keeps the phone + IC-9700 listening where the work mode says: the ISS
     * while it is up, 144.800 otherwise (Auto), or always one of the two.
     */
    private suspend fun aprsAudioTic(force: Boolean = false) {
        if (!aprsEcouteEnCours) return
        if (!_ui.value.recording && !force) { aprsEcouteEnCours = false; aprsLibereTerre(); return }
        if (settings.aprsMode != "AUDIO") return
        val surIss = aprsSurIss()
        if (surIss) {
            if (aprsTerreActive || force) { aprsLibereTerre(); aprsChoisitIss() }
            aprsEnvoi.value = t("aprs_ecoute_iss_ok")
        } else if (!aprsTerreActive || force) {
            aprsEnvoi.value = if (aprsIc9700Terre()) t("aprs_ecoute_terre_ok")
                else if (_ui.value.rigModel == "IC9700") t("aprs_ecoute_terre_sans_cat")
                else t("aprs_ecoute_terre_autre")
        }
    }

    /**
     * Where a position is sent from: the QTH, or — with the approximate
     * position option — the QTH shifted by a fixed random offset under 500 m.
     */
    fun aprsPositionEmise(): Pair<Double, Double>? {
        val o = _ui.value.observer ?: return null
        if (!settings.aprsPositionFloue) return o.latDeg to o.lonDeg
        var n = settings.aprsFlouNordM; var e = settings.aprsFlouEstM
        if (n.isNaN() || e.isNaN()) {
            // Uniform over the disc: radius from the square root, so the centre is not favoured.
            val r = 500.0 * kotlin.math.sqrt(kotlin.random.Random.nextDouble())
            val a = kotlin.random.Random.nextDouble(0.0, 2 * Math.PI)
            n = (r * kotlin.math.cos(a)).toFloat(); e = (r * kotlin.math.sin(a)).toFloat()
            settings.aprsFlouNordM = n; settings.aprsFlouEstM = e
        }
        val lat = o.latDeg + n / 111_320.0
        val lon = o.lonDeg + e / (111_320.0 * kotlin.math.cos(Math.toRadians(o.latDeg)).coerceAtLeast(0.01))
        return lat to lon
    }

    fun aprsPositionFloue(): Boolean = settings.aprsPositionFloue
    fun setAprsPositionFloue(v: Boolean) { settings.aprsPositionFloue = v }
    /** Draws a new offset (the next position sent uses it). */
    fun aprsNouveauFlou() { settings.aprsFlouNordM = Float.NaN; settings.aprsFlouEstM = Float.NaN }

    // ------------------------------------------------------- automatic SSTV

    fun fermeAvertissementSstvIss() { sstvIssAvertissement.value = false }

    /** The satellites offered for automatic SSTV: the favourites, the ISS first when loaded. */
    fun sstvAutoSatellites(): List<TleEntry> {
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        return (listOfNotNull(iss()) + favs).distinctBy { it.catalogNumber }
    }

    fun sstvAutoCatnum(): Int = settings.sstvAutoCatnum
    fun sstvAutoTx(): String = settings.sstvAutoTx
    /** Another satellite: its transmitter will be found again (or chosen). */
    fun setSstvAutoCatnum(n: Int) {
        if (n != settings.sstvAutoCatnum) settings.sstvAutoTx = ""
        settings.sstvAutoCatnum = n
    }
    fun setSstvAutoTx(desc: String) { settings.sstvAutoTx = desc }

    /** The transmitters of satellite [catnum] worth offering, SSTV ones first. */
    suspend fun sstvAutoTransmetteurs(catnum: Int): List<fr.f4ioz.satcombo.data.Transmitter> =
        fr.f4ioz.satcombo.domain.SstvIss.candidats(
            runCatching { txRepo.forSatellite(catnum) }.getOrDefault(emptyList()).filter { it.alive })

    /** The coming passes (72 h) of satellite [catnum], for the "until" choice. */
    fun sstvIssPassages(catnum: Int = settings.sstvAutoCatnum): List<fr.f4ioz.satcombo.data.SatPass> {
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return emptyList()
        val obs = _ui.value.observer ?: return emptyList()
        return runCatching {
            predictor.upcomingPasses(sat, obs, System.currentTimeMillis() - 15 * 60_000L, 72,
                _ui.value.minElevDeg.toDouble())
        }.getOrDefault(emptyList()).filter { it.losEpochMs + fr.f4ioz.satcombo.domain.SstvIss.APRES_MS > System.currentTimeMillis() }
    }

    /**
     * Automatic SSTV until the pass starting at [dernierAos]: the chosen
     * satellite and transmitter selected (the one in use kept to give it
     * back), recording and SSTV decoding on, the recorder armed for every
     * window.
     */
    fun sstvIssActive(dernierAos: Long) {
        viewModelScope.launch {
            val catnum = settings.sstvAutoCatnum
            val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
                ?: run { sstvIssMessage.value = t("sstv_iss_absente"); return@launch }
            if (_ui.value.selected?.catalogNumber != sat.catalogNumber) select(sat)
            kotlinx.coroutines.withTimeoutOrNull(8_000) {
                while (_ui.value.transmittersLoading || _ui.value.selected?.catalogNumber != sat.catalogNumber) delay(100)
            }
            val actifs = activeTransmitters()
            if (settings.sstvIssTxAvant.isBlank()) {
                settings.sstvIssTxAvant = actifs.getOrNull(_ui.value.selectedTxIndex)?.description ?: ""
                settings.sstvAutoCatnumAvant = catnum
            }
            // The transmitter chosen on the SSTV page, else the one named SSTV.
            val voulu = settings.sstvAutoTx
            val i = actifs.indexOfFirst { voulu.isNotBlank() && it.description == voulu }
                .takeIf { it >= 0 } ?: fr.f4ioz.satcombo.domain.SstvIss.indexSstv(actifs)
            if (i >= 0 && i != _ui.value.selectedTxIndex) selectTransmitter(i)
            if (!_ui.value.recorderEnabled) setRecorderEnabled(true)
            if (!_ui.value.sstvEnabled) setSstvEnabled(true)
            val f = fr.f4ioz.satcombo.domain.SstvIss.fenetres(
                sstvIssPassages(catnum).map { it.aosEpochMs to it.losEpochMs }, System.currentTimeMillis(), dernierAos)
            if (f.isEmpty()) { sstvIssMessage.value = t("sstv_iss_aucun"); return@launch }
            sstvAutoArme = catnum
            fr.f4ioz.satcombo.audio.RecorderService.arme(getApplication(), sat.name, f,
                _ui.value.recorderSource, _ui.value.recorderUnprocessed, myLocator())
            sstvIssMessage.value = if (i < 0) t("sstv_iss_sans_transpondeur") else ""
        }
    }

    /** The satellite automatic SSTV is armed for (for the warning on changing satellite). */
    @Volatile private var sstvAutoArme: Int = 0

    /** Stops automatic SSTV (and a recording in progress); the transmitter comes back when the recorder disarms. */
    fun sstvIssDesactive() {
        fr.f4ioz.satcombo.audio.RecorderService.desarme(getApplication())
    }

    /** Gives the automatic SSTV satellite back the transmitter it had before. */
    private fun sstvIssRendTransmetteur() {
        val avant = settings.sstvIssTxAvant
        if (avant.isBlank()) return
        val catnum = settings.sstvAutoCatnumAvant
        settings.sstvIssTxAvant = ""
        if (_ui.value.selected?.catalogNumber == catnum) {
            val i = activeTransmitters().indexOfFirst { it.description == avant }
            if (i >= 0) selectTransmitter(i)
        } else satConfigStore.saveTransmitter(catnum, avant)
    }

    // ----------------------------------------------------- APRS, the fun side

    /** The ISS among the loaded satellites (NORAD 25544), or null. */
    private fun iss(): TleEntry? = _ui.value.satellites.firstOrNull { it.catalogNumber == 25544 }

    /** The point under the ISS at [ms], for the APRS map. */
    fun aprsSousIss(ms: Long): Pair<Double, Double>? {
        val e = iss() ?: return null
        return runCatching {
            predictor.positionAt(e, _ui.value.observer ?: fr.f4ioz.satcombo.data.Observer(0.0, 0.0), ms)
        }.getOrNull()?.let { it.latDeg to it.lonDeg }
    }

    /** The ISS ground track from [depuisMs], one orbit. */
    fun aprsTraceIss(depuisMs: Long): List<Pair<Double, Double>> {
        val e = iss() ?: return emptyList()
        return runCatching { predictor.groundTrack(e, depuisMs) }.getOrDefault(emptyList())
    }

    fun aprsBaliseIss(): Boolean = settings.aprsBaliseIss
    fun setAprsBaliseIss(v: Boolean) { settings.aprsBaliseIss = v }
    fun aprsFetes(): Boolean = settings.aprsFetes
    fun setAprsFetes(v: Boolean) { settings.aprsFetes = v }

    /** The radio chosen on the APRS page sends [trame] (FT3D: it transmits from its own menu). */
    fun aprsEmetSelonMode(trame: fr.f4ioz.satcombo.aprs.Trame, frequenceConfirmee: Boolean) {
        when (settings.aprsMode) {
            "KISS" -> aprsEmetKiss(trame, frequenceConfirmee)
            "FT3D" -> aprsEnvoi.value = t("aprs_ft3d_tx")
            else -> aprsEmet(trame)
        }
    }

    /**
     * An APRS contact through the ISS into the log: mode PKT on 145.825 MHz,
     * at the time of the ack, the other station's square when it sent a
     * position. Not twice the same station on the same pass.
     * @return a message for the page.
     */
    fun aprsAuCarnet(c: fr.f4ioz.satcombo.aprs.AprsJeu.Contact): String {
        val sat = iss() ?: return t("aprs_carnet_sans_iss")
        val call = fr.f4ioz.satcombo.aprs.AprsJeu.base(c.indicatif)
        val deja = _ui.value.log.any {
            it.callsign.uppercase().substringBefore('/') == call && it.satName == sat.name &&
                kotlin.math.abs(it.timeMs - c.quand) <= fr.f4ioz.satcombo.aprs.AprsJeu.PASSAGE_MS
        }
        if (deja) return tf("aprs_carnet_deja", call)
        val obs = _ui.value.observer
        val pos = obs?.let { runCatching { predictor.positionAt(sat, it, c.quand) }.getOrNull() }
        val e = fr.f4ioz.satcombo.data.LogEntry(
            timeMs = c.quand, satName = sat.name, catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0, elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) } ?: _ui.value.manualLocator,
            myGrids = myGridsCsv(),
            callsign = call, theirLocator = c.carre.orEmpty(),
            mode = "PKT", note = "APRS",
            downlinkMhz = 145.825, uplinkMhz = 145.825)
        _ui.value = _ui.value.copy(log = logStore.add(e),
            express = _ui.value.express.copy(memoire = construitMemoire()))
        return tf("aprs_carnet_ok", call)
    }

    /** AOS of the ISS pass already beaconed: one position per pass, never more. */
    private var aprsBaliseAos = 0L

    /**
     * The ISS beacon: once the ISS is 15° up, one position through the radio
     * chosen on the APRS page — only when that radio can be checked (IC-9700
     * on CAT, or a KISS radio whose frequency was read on 145.825 MHz). Every
     * transmit check still applies.
     */
    private fun aprsBaliseTic() {
        if (!settings.aprsBaliseIss) return
        val sat = iss() ?: return
        val obs = _ui.value.observer ?: return
        val now = System.currentTimeMillis()
        val el = runCatching { predictor.positionAt(sat, obs, now).elevationDeg }.getOrNull() ?: return
        if (el < 15.0) return
        val aos = runCatching { predictor.currentPass(sat, obs, now) }.getOrNull()?.first ?: return
        if (aos == aprsBaliseAos) return
        val source = aprsSource()
        if (source.isBlank()) return
        val prete = when (settings.aprsMode) {
            "KISS" -> fr.f4ioz.satcombo.aprs.TncKiss.etat.value.let { k ->
                k.connecte && k.frequenceHz != null && k.frequenceHz in fr.f4ioz.satcombo.aprs.AprsEmission.FENETRES[0]
            }
            "AUDIO" -> _ui.value.catConnected && _ui.value.rigModel == "IC9700" && !aprsEnEmission
            else -> false
        }
        if (!prete) return
        aprsBaliseAos = aos
        val (la, lo) = aprsPositionEmise() ?: return
        val info = fr.f4ioz.satcombo.aprs.AprsEmission.position(la, lo, "/-",
            "SatMe " + Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg).take(4))
        val trame = fr.f4ioz.satcombo.aprs.AprsEmission.trame(source, listOf("ARISS"), info)
        aprsEmetSelonMode(trame, frequenceConfirmee = true)
    }

    /** What a trophy event says, as a notification title and text. */
    private fun aprsFete(ev: fr.f4ioz.satcombo.aprs.Trophees.Evenement) {
        if (!settings.aprsFetes) return
        val app = getApplication<android.app.Application>()
        val (titre, texte) = when (ev) {
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.RepeteIss -> t("aprs_fete_iss") to
                (if (ev.autres.isEmpty()) t("aprs_fete_iss_seul")
                 else tf("aprs_fete_iss_autres", ev.autres.take(8).joinToString(", ")))
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.Contact ->
                tf("aprs_fete_contact", ev.contact.indicatif) to t("aprs_fete_contact_texte")
            is fr.f4ioz.satcombo.aprs.Trophees.Evenement.NouveauBadge ->
                tf("aprs_fete_badge", t("aprs_badge_" + ev.badge.cle)) to t("aprs_badge_" + ev.badge.cle + "_desc")
            else -> return
        }
        fr.f4ioz.satcombo.notify.AprsNotifier.notifie(app, titre, texte)
    }

    /**
     * Whether the SSTV page explains how to start decoding. Read straight from
     * the settings, not `UiState` (255-register limit): asked once per visit.
     */
    fun sstvAideAMontrer(): Boolean = !settings.sstvAideMasquee

    /** Spoken header at the start of recordings. Read from the settings, not
     *  `UiState` (255-register limit): only its switch shows it. */
    fun annonceVocale(): Boolean = settings.annonceVocale
    fun setAnnonceVocale(on: Boolean) { settings.annonceVocale = on }
    fun masqueAideSstv() { settings.sstvAideMasquee = true }

    // ================= APT (NOAA images, beta) =================

    fun openApt() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.APT)) return
        fr.f4ioz.satcombo.apt.AptHub.qthLocator = myLocator()
        va(Screen.APT)
        _ui.value = _ui.value.copy(screen = Screen.APT)
    }
    fun closeApt() { _ui.value = retour() }

    // ================= SDR (RTL-SDR dongle, beta) =================

    fun openSdr() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SDR)) return
        fr.f4ioz.satcombo.sdr.SdrHub.attach(getApplication())
        va(Screen.SDR)
        _ui.value = _ui.value.copy(screen = Screen.SDR)
    }

    fun closeSdr() { _ui.value = retour() }

    // ===================== weather radiosondes =====================

    fun openSonde() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SONDE)) return
        va(Screen.SONDE)
        _ui.value = _ui.value.copy(screen = Screen.SONDE)
    }

    /** Leaving the screen does not stop listening: a flight lasts three hours. */
    fun closeSonde() { _ui.value = retour() }

    /** Tunes to a frequency, live if reception is already running. */
    fun setSondeFreq(hz: Long) {
        if (!fr.f4ioz.satcombo.sonde.SondeSites.inBand(hz)) return
        settings.sondeFreqHz = hz
        _ui.value = _ui.value.copy(sondeFreqHz = hz)
        if (fr.f4ioz.satcombo.sonde.SondeHub.active) {
            fr.f4ioz.satcombo.sdr.SdrHub.setCenter(hz, hz)
        }
    }

    /** One 10 kHz step: the raster sondes sit on. */
    fun stepSondeFreq(steps: Int) {
        val step = fr.f4ioz.satcombo.sonde.SondeSites.SCAN_STEP_HZ
        setSondeFreq(_ui.value.sondeFreqHz + steps * step)
    }

    /** Where sonde audio comes from: "SDR", "MIC" or "USB". */
    fun setSondeSource(v: String) {
        settings.sondeSource = v
        _ui.value = _ui.value.copy(sondeSource = settings.sondeSource)
    }

    /**
     * Sonde model: "AUTO", "RS41", "M20" or "M10". Not cosmetic: it sets both
     * the FM filter width and the decoders started. An RS41 fits in 15 kHz;
     * opening 22 lets in half as much noise again for nothing.
     */
    fun setSondeModel(v: String) {
        settings.sondeModel = v
        _ui.value = _ui.value.copy(sondeModel = settings.sondeModel)
    }

    /**
     * Starts sonde reception from the chosen source. The RTL dongle is only
     * one way to hear 404 MHz: an existing receiver's audio can come through
     * the phone mic or a USB sound card, as for SSTV or NOAA. The decoder sees
     * no difference.
     */
    fun startSondeRx() {
        when (_ui.value.sondeSource) {
            "MIC", "USB" -> startSondeAudio(_ui.value.sondeSource)
            else -> startSonde()
        }
    }

    /** Stops reception, whatever the source. */
    fun stopSondeRx() {
        if (fr.f4ioz.satcombo.audio.SondeAudioService.running) stopSondeAudio()
        else stopSonde()
    }

    /** Listening through phone audio: no file is written, only the decoded
     *  frame log remains. */
    fun startSondeAudio(source: String) {
        val hz = _ui.value.sondeFreqHz
        fr.f4ioz.satcombo.sonde.SondeHub.start(
            getApplication(),
            fr.f4ioz.satcombo.audio.SondeAudioService.RATE, hz, source,
            model = _ui.value.sondeModel)
        fr.f4ioz.satcombo.audio.SondeAudioService.start(getApplication(), source)
    }

    fun stopSondeAudio() {
        fr.f4ioz.satcombo.audio.SondeAudioService.stop(getApplication())
        fr.f4ioz.satcombo.sonde.SondeHub.stop()
    }

    /**
     * Starts listening to a sonde on the SDR dongle. Three differences from
     * voice, each one matters: no audio (no three hours of white noise), no
     * squelch (a distant sonde drops below it long before it stops decoding),
     * and above all no de-emphasis — it rounds the signal edges and the
     * decoder finds nothing.
     */
    fun startSonde() {
        val hz = _ui.value.sondeFreqHz
        val gain = settings.sdrGainTenthDb.takeIf { it >= 0 }
        val ok = fr.f4ioz.satcombo.sdr.SdrHub.start(
            ctx = getApplication(),
            satName = "SONDE",
            restHz = hz,
            gainTenthDb = gain,
            agc = settings.sdrAgc,
            ppm = settings.sdrPpm,
            sstv = false,
            record = false,
            audio = false,
            mode = fr.f4ioz.satcombo.sdr.RxMode.NFM,
            bandwidthHz = fr.f4ioz.satcombo.sonde.SondeModel
                .bandwidthFor(_ui.value.sondeModel),
            squelchDb = -200,
            offsetHz = 0)
        if (!ok) return
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(false)
        fr.f4ioz.satcombo.sonde.SondeHub.start(
            getApplication(), fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE, hz,
            model = _ui.value.sondeModel)
    }

    fun stopSonde() {
        fr.f4ioz.satcombo.sonde.SondeHub.stop()
        fr.f4ioz.satcombo.sdr.SdrHub.stop()
        // Restore the user's de-emphasis setting.
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(settings.sdrDeemph)
    }

    fun clearSondeFlight() {
        fr.f4ioz.satcombo.sonde.SondeHub.clearFlight()
        _ui.value = _ui.value.copy()
    }

    /**
     * A USB device was plugged in (USB_DEVICE_ATTACHED).
     *
     * Navigate nowhere: the operator is almost always tracking a pass when he
     * plugs the dongle in, and sending him to the satellite list is exactly
     * wrong. Just note the device; the SDR panel appears under the compass.
     * Bonus: the system has already granted USB permission, no dialog to wait.
     */
    fun onUsbDeviceAttached(dev: android.hardware.usb.UsbDevice?) {
        if (hasExtension(fr.f4ioz.satcombo.data.Extensions.SDR)) {
            fr.f4ioz.satcombo.sdr.SdrHub.onDeviceAttached(getApplication(), dev)
        }
        onSerialAttached(dev)
    }

    /**
     * A serial bridge was plugged in: the rotor, or the rig.
     *
     * The best moment of the day: the system has just granted access while
     * choosing the app, so no permission dialog — which is exactly what blocked
     * rotor connection. Refresh both port lists, then try the rotor when it can
     * succeed (USB link chosen, no simulator, nothing connected). A port that
     * does not answer `C2` is closed at once, so the rig is never left held.
     */
    private fun onSerialAttached(dev: android.hardware.usb.UsbDevice?) {
        val d = runCatching { fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()) }.getOrNull()
            ?: return
        if (!d.recognises(dev)) return
        refreshRotorDevices()
        refreshCatDevices()
        val u = _ui.value
        if (u.rotorConnected || u.rotorSim || u.rotorLink != "GS232") return
        val nom = dev?.productName ?: dev?.deviceName ?: ""
        _ui.value = _ui.value.rot { copy(rotorStatus = tf("rotor_usb_attached", nom)) }
        connectRotor()
    }


    // ==================================================================
    // Converters
    //
    // Everything in this class reasons in satellite frequencies: Doppler,
    // transponder edges, inversion, rest point in the passband. That stays
    // true with an LNB in front of the receiver — 10 489 MHz still comes down.
    // Only the last step changes: what is written to rig or dongle, and what
    // is read back. So conversion happens as late as possible, right before
    // writing and right after reading, nowhere else: no computation ever
    // needs to know there is a box in the cable.
    // ==================================================================

    private fun convRxDepuisReglages() = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = settings.convRxActif, olHz = settings.convRxOlHz,
        inverseur = settings.convRxInverseur,
        basHz = settings.convRxBasHz, hautHz = settings.convRxHautHz)

    private fun convTxDepuisReglages() = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = settings.convTxActif, olHz = settings.convTxOlHz,
        inverseur = settings.convTxInverseur,
        basHz = settings.convTxBasHz, hautHz = settings.convTxHautHz)

    /**
     * Receiver offset, applied **after** the converter. A receiver's crystal
     * errs where it tunes — on the IF, not the sky frequency. Correcting before
     * the converter would scale the error to GHz: 2 ppm is 20 kHz at 10 GHz,
     * versus 300 Hz at 144 MHz.
     */
    private fun ppmPoste(): Double =
        fr.f4ioz.satcombo.domain.MaterielRx.choisi(
            _ui.value.qo100.materiels, _ui.value.qo100.materielPoste).ppm

    private fun ppmCle(): Double =
        fr.f4ioz.satcombo.domain.MaterielRx.choisi(
            _ui.value.qo100.materiels, _ui.value.qo100.materielCle).ppm

    /** Downlink: sky to CAT-controlled rig. */
    private fun posteRx(satHz: Long): Long {
        val fi = if (_ui.value.convRxPoste) _ui.value.convRx.versPoste(satHz) else satHz
        return fr.f4ioz.satcombo.domain.MaterielRx.corrige(fi, ppmPoste())
    }

    /**
     * Is the converter in **the dongle's** chain? Same rule as for the rig
     * (see [convRxDansLaChaine]). Checking that the converted result falls in
     * band proves nothing: a dongle on 145.9 MHz plus an LNB LO gives 10 490
     * MHz, right in QO-100's range — the guard validated itself, and a LEO
     * showed a GHz frequency.
     */
    private fun convRxCleDansLaChaine(): Boolean {
        if (!_ui.value.convRxCle || !_ui.value.convRx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val dl = t.downlinkLowHz ?: return false
        return _ui.value.convRx.couvre(dl)
    }

    /**
     * Is the converter in the current satellite's chain?
     *
     * **The satellite decides, not the instant frequency.** A converter belongs
     * to a band: the QO-100 one comes down from Ku and has no place in a
     * 145 MHz satellite's chain. Without this, on a LEO the 817 read 145.9, the
     * app added the LNB LO and showed 10 490.9 — which passed the converter's
     * bounds, since they apply to the result. So ask the selected
     * transponder's downlink, which does not lie: 435 MHz on a LEO, 10 489 on
     * QO-100.
     */
    private fun convRxDansLaChaine(): Boolean {
        if (!_ui.value.convRxPoste || !_ui.value.convRx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val dl = t.downlinkLowHz ?: return false
        return _ui.value.convRx.couvre(dl)
    }

    /** Downlink: what the rig displays, brought back to the sky. */
    private fun satDepuisPoste(posteHz: Long): Long =
        fr.f4ioz.satcombo.domain.MaterielRx.redresse(posteHz, ppmPoste()).let {
            if (convRxDansLaChaine()) _ui.value.convRx.versSatellite(it) else it
        }

    /** Uplink: sky to the exciter, upstream of the transverter. */
    private fun posteTx(satHz: Long): Long = _ui.value.convTx.versPoste(satHz)

    /** Same question for the uplink: the satellite decides. */
    private fun convTxDansLaChaine(): Boolean {
        if (!_ui.value.convTx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val ul = t.uplinkLowHz ?: return false
        return _ui.value.convTx.couvre(ul)
    }

    /**
     * Uplink: what the exciter displays, brought back to the sky. Inverse of
     * [posteTx]; without it, behind a transverter the knob would be read in the
     * wrong band and the derived shift would be megahertz.
     */
    private fun satDepuisPosteTx(posteHz: Long): Long =
        if (convTxDansLaChaine()) _ui.value.convTx.versSatellite(posteHz) else posteHz

    /** Downlink: sky to the SDR dongle's PLL. */
    private fun cleRx(satHz: Long): Long {
        val fi = if (convRxCleDansLaChaine()) _ui.value.convRx.versPoste(satHz) else satHz
        return fr.f4ioz.satcombo.domain.MaterielRx.corrige(fi, ppmCle())
    }

    /**
     * The way back: what the dongle receives, as a sky frequency. With an LNB
     * the SDR screen would show 739 MHz, meaningless to the operator, who works
     * on 10 489 and logs that.
     */
    fun cleVersSat(cleHz: Long): Long =
        fr.f4ioz.satcombo.domain.MaterielRx.redresse(cleHz, ppmCle()).let {
            if (convRxCleDansLaChaine()) _ui.value.convRx.versSatellite(it) else it
        }

    /** True when a converter is set on the dongle's chain. */
    val convertisseurSurLaCle: Boolean
        get() = _ui.value.convRxCle && _ui.value.convRx.configure

    fun setConvRxActif(on: Boolean) {
        settings.convRxActif = on
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxOl(hz: Long) {
        settings.convRxOlHz = hz
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxInverseur(on: Boolean) {
        settings.convRxInverseur = on
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxPlage(basHz: Long, hautHz: Long) {
        settings.convRxBasHz = basHz; settings.convRxHautHz = hautHz
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxPoste(on: Boolean) {
        settings.convRxPoste = on
        _ui.value = _ui.value.copy(convRxPoste = on)
    }

    fun setConvRxCle(on: Boolean) {
        settings.convRxCle = on
        _ui.value = _ui.value.copy(convRxCle = on)
    }

    fun setConvTxActif(on: Boolean) {
        settings.convTxActif = on
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxOl(hz: Long) {
        settings.convTxOlHz = hz
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxInverseur(on: Boolean) {
        settings.convTxInverseur = on
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxPlage(basHz: Long, hautHz: Long) {
        settings.convTxBasHz = basHz; settings.convTxHautHz = hautHz
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    /**
     * Applies a ready-made setup on the right side of the chain. A downlink
     * preset also ticks the SDR dongle and unticks the rig when its IF is out
     * of ham bands: a 9 750 LO LNB outputs 739 MHz, which no ham rig receives,
     * while the 10 057.5 preset outputs 432 and goes to the rig.
     */
    fun appliquerPreset(cle: String) {
        val p = fr.f4ioz.satcombo.domain.Convertisseur.PRESETS.firstOrNull { it.cle == cle } ?: return
        if (p.descente) {
            settings.convRxActif = true
            settings.convRxOlHz = p.olHz
            settings.convRxInverseur = false
            settings.convRxBasHz = p.basHz
            settings.convRxHautHz = p.hautHz
            val fi = p.vers().versPoste(fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ)
            val recevableParUnPoste = fr.f4ioz.satcombo.cat.BandPlan.band(fi) !=
                fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE
            settings.convRxPoste = recevableParUnPoste
            settings.convRxCle = true
            _ui.value = _ui.value.copy(convRx = convRxDepuisReglages(),
                convRxPoste = settings.convRxPoste, convRxCle = true)
        } else {
            settings.convTxActif = true
            settings.convTxOlHz = p.olHz
            settings.convTxInverseur = false
            settings.convTxBasHz = p.basHz
            settings.convTxHautHz = p.hautHz
            _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
        }
    }

    /**
     * Rest frequency for the dongle: the one chosen in the passband, else the
     * selected transponder's centre, else ISS SSTV — by far the most common
     * case for someone who just plugged a dongle in.
     */
    fun sdrRestHz(): Long {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        return _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: 145_800_000L
    }

    private var sdrLoopJob: kotlinx.coroutines.Job? = null

    /**
     * Starts reception on the rest frequency, then the Doppler loop moves it
     * while the satellite is above the horizon (receive only: the dongle does
     * not transmit).
     */
    fun startSdr() {
        val sat = _ui.value.selected?.name ?: "SAT"
        val rest = sdrRestHz()
        val gain = settings.sdrGainTenthDb.takeIf { it >= 0 }
        val ok = fr.f4ioz.satcombo.sdr.SdrHub.start(
            ctx = getApplication(),
            satName = sat,
            // The dongle only knows its IF; [rest] stays the satellite
            // frequency for the whole Doppler loop.
            restHz = cleRx(rest),
            gainTenthDb = gain,
            agc = settings.sdrAgc,
            ppm = settings.sdrPpm,
            sstv = settings.sdrSstv,
            record = settings.sdrRecord,
            audio = settings.sdrAudio,
            mode = sdrMode(),
            bandwidthHz = settings.sdrBandwidthHz,
            squelchDb = settings.sdrSquelchDb,
            offsetHz = 0)
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(settings.sdrDeemph)
        if (ok) startSdrLoop(rest)
    }

    /**
     * Dongle Doppler tracking, four times a second.
     *
     * Reprogramming the PLL is a phase jump — a click on SSB — and with the
     * 100 Hz guard of [SdrHub.setCenter] the note climbed in 100 Hz steps.
     * Now [DopplerTuner] decides, and almost always says: leave the dongle,
     * slide the software offset (applied to the raw signal before the channel
     * filter, continuous and free). A whole 435 MHz pass sweeps about 10 kHz,
     * the window absorbs 30: the PLL does not move from AOS to LOS.
     *
     * At the top of a LEO pass drift reaches ~100 Hz/s; refreshing every
     * 250 ms keeps the error under 30 Hz. [DopplerTuner.worthWriting] skips
     * writes of a hertz or two.
     */
    private fun startSdrLoop(restHz: Long) {
        sdrLoopJob?.cancel()
        sdrLoopJob = viewModelScope.launch {
            while (fr.f4ioz.satcombo.sdr.SdrHub.isRunning) {
                runCatching {
                    val rest = _ui.value.rxRestHz ?: restHz
                    if (!_ui.value.sdrDopplerTrack) {
                        // Tracking off: the dongle stays on the rest frequency.
                        fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(rest), cleRx(rest))
                    } else {
                        val pos = _ui.value.livePosition
                        val rr = if (pos != null && pos.elevationDeg >= 0) pos.rangeRateKmS else 0.0
                        val target = Doppler.downlink(rest, rr) + _ui.value.calibShiftHz
                        // The hub decides between blocks whether the PLL must
                        // move or the software mixer absorbs the gap; we only
                        // give the target. Both go through the converter, which
                        // keeps this right with no special case: a subtractive
                        // LO shifts target and rest equally (Doppler unchanged);
                        // an inverting LO flips the gap, exactly as the
                        // hardware does.
                        fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(target), cleRx(rest))
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /**
     * Toggles dongle Doppler tracking. Off resets the Doppler offset: back to
     * the rest frequency, as wanted for a fixed beacon or to hear the raw drift.
     */
    fun setSdrDopplerTrack(on: Boolean) {
        settings.sdrDopplerTrack = on
        _ui.value = _ui.value.copy(sdrDopplerTrack = on)
        if (!on) fr.f4ioz.satcombo.sdr.SdrHub.setDopplerFine(0)
    }

    fun stopSdr() {
        sdrLoopJob?.cancel(); sdrLoopJob = null
        fr.f4ioz.satcombo.sdr.SdrHub.stop()
    }

    fun setSdrGain(tenthDb: Int?) {
        settings.sdrGainTenthDb = tenthDb ?: -1
        fr.f4ioz.satcombo.sdr.SdrHub.setGain(tenthDb)
    }

    fun setSdrSstv(on: Boolean) { settings.sdrSstv = on; _ui.value = _ui.value.copy(sdrSstv = on) }
    fun setSdrRecord(on: Boolean) { settings.sdrRecord = on; _ui.value = _ui.value.copy(sdrRecord = on) }
    fun setSdrAudio(on: Boolean) {
        settings.sdrAudio = on
        _ui.value = _ui.value.copy(sdrAudio = on)
        fr.f4ioz.satcombo.sdr.SdrHub.setAudio(on)
    }
    fun setSdrAgc(on: Boolean) { settings.sdrAgc = on; _ui.value = _ui.value.copy(sdrAgc = on) }

    /** Small waterfall on the pass page: handy to see the satellite arrive, but
     *  it takes room on a small screen. */
    fun setSdrInlineWaterfall(on: Boolean) {
        settings.sdrInlineWaterfall = on
        _ui.value = _ui.value.copy(sdrInlineWaterfall = on)
    }
    fun setSdrPpm(ppm: Int) {
        val v = ppm.coerceIn(-200, 200)
        settings.sdrPpm = v
        _ui.value = _ui.value.copy(sdrPpm = v)
    }

    /** Saved demodulation mode, with a safe fallback if corrupt. */
    fun sdrMode(): fr.f4ioz.satcombo.sdr.RxMode =
        runCatching { fr.f4ioz.satcombo.sdr.RxMode.valueOf(_ui.value.sdrMode) }
            .getOrDefault(fr.f4ioz.satcombo.sdr.RxMode.NFM)

    /**
     * Changes receive mode. Channel width resets to the mode's default: SSB
     * with the 16 kHz of NFM makes no sense.
     */
    fun setSdrMode(m: fr.f4ioz.satcombo.sdr.RxMode) {
        settings.sdrMode = m.name
        settings.sdrBandwidthHz = 0
        _ui.value = _ui.value.copy(sdrMode = m.name, sdrBandwidthHz = 0)
        fr.f4ioz.satcombo.sdr.SdrHub.setBandwidth(0)
        fr.f4ioz.satcombo.sdr.SdrHub.setMode(m)
    }

    /** Channel width, Hz; zero lets the mode decide. */
    fun setSdrBandwidth(hz: Int) {
        val v = if (hz <= 0) 0 else hz.coerceIn(500, 24_000)
        settings.sdrBandwidthHz = v
        _ui.value = _ui.value.copy(sdrBandwidthHz = v)
        fr.f4ioz.satcombo.sdr.SdrHub.setBandwidth(v)
    }

    /** Squelch threshold, dBFS; -120 turns it off. */
    fun setSdrSquelch(db: Int) {
        val v = db.coerceIn(-120, 0)
        settings.sdrSquelchDb = v
        _ui.value = _ui.value.copy(sdrSquelchDb = v)
        fr.f4ioz.satcombo.sdr.SdrHub.setSquelch(v)
    }

    /**
     * FM de-emphasis. Only for wideband FM: on a repeater, a beacon or SSTV it
     * just crushes the highs.
     */
    fun setSdrDeemph(on: Boolean) {
        settings.sdrDeemph = on
        _ui.value = _ui.value.copy(sdrDeemph = on)
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(on)
    }

    /**
     * Auto-tunes to the strongest carrier in the displayed window: see the
     * line, tap, and the chain lands on it to FFT precision.
     */
    fun tuneSdrPeak(spanHz: Int) {
        val half = (spanHz / 2.0).coerceIn(2_000.0, 80_000.0)
        fr.f4ioz.satcombo.sdr.SdrHub.tunePeak(-half, half)
    }

    /** Spectrum display width, Hz. */
    fun setSdrSpan(hz: Int) {
        val v = hz.coerceIn(6_000, 176_400)
        settings.sdrSpanHz = v
        _ui.value = _ui.value.copy(sdrSpanHz = v)
    }

    // ------------------------------------------------------------ fine tuning

    fun setSdrLoupe(on: Boolean) {
        settings.sdrLoupe = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(loupe = on))
    }

    fun setSdrLoupeSpan(hz: Int) {
        val v = hz.coerceIn(1_000, 40_000)
        settings.sdrLoupeSpanHz = v
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(loupeSpanHz = v))
    }

    fun setSdrVernier(on: Boolean) {
        settings.sdrVernier = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(vernier = on))
    }

    fun setSdrVernierRatio(hzParCm: Int) {
        val v = hzParCm.coerceIn(1, 100_000)
        settings.sdrVernierHzParCm = v
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(vernierHzParCm = v))
    }

    fun setClavierMainGauche(on: Boolean) {
        settings.clavierMainGauche = on
        _ui.value = _ui.value.copy(express = _ui.value.express.copy(mainGauche = on))
    }

    /**
     * One USB knob detent. The action depends on the open screen, on purpose:
     * the knob drives "what is being tuned", not a named field — on QO-100 the
     * downlink, elsewhere the chosen target, as the on-screen buttons do.
     */
    fun moletteCran(codeTouche: Int, externe: Boolean): Boolean {
        val M = fr.f4ioz.satcombo.domain.MoletteUsb
        // Learning comes first: the only time a key must be captured rather
        // than interpreted.
        val rang = _apprentissage.value
        if (rang != null && externe) {
            if (M.apprenable(codeTouche)) { apprendMacro(rang, codeTouche); _apprentissage.value = null }
            return true
        }
        val a = _ui.value.accord
        val geste = M.geste(
            codeTouche, settings.moletteVfo, externe, settings.molettePasHz,
            fr.f4ioz.satcombo.domain.MoletteUsb.Touches(
                a.macroCodeA, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleA),
                a.macroCodeB, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleB),
                a.macroCodeC, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleC),
                a.macroCodeD, fr.f4ioz.satcombo.domain.MoletteUsb.Action.valueOf(a.macroActionD)))
        return when (geste) {
            is fr.f4ioz.satcombo.domain.MoletteUsb.Geste.ChoisitCible -> { setMoletteCible(geste.cible.name); true }
            is fr.f4ioz.satcombo.domain.MoletteUsb.Geste.Bouge -> {
                // The target decides, except on QO-100 where the whole screen
                // is already a VFO.
                when {
                    _ui.value.screen == Screen.QO100 -> qo100Pas(geste.deltaHz)
                    a.moletteCible == "SHIFT_TX" -> nudgeTxShift(geste.deltaHz)
                    a.moletteCible == "SHIFT_RX" -> nudgeRxOffset(geste.deltaHz)
                    else -> moletteDeplaceVfo(geste.deltaHz)
                }
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.CibleSuivante -> {
                setMoletteCible(
                    fr.f4ioz.satcombo.domain.MoletteUsb.cibleSuivante(_ui.value.accord.moletteCible))
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.RemetZero -> {
                // Reset only the current target: resetting both shifts at
                // once would erase a setting the operator wanted to keep.
                when (_ui.value.accord.moletteCible) {
                    "SHIFT_TX" -> setTxShift(0L)
                    "SHIFT_RX" -> setRxOffset(0L)
                    else -> Unit          // the VFO has no zero
                }
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.ChangePas -> {
                settings.molettePasHz =
                    fr.f4ioz.satcombo.domain.MoletteUsb.pasSuivant(settings.molettePasHz)
                _ui.value = _ui.value.copy(molettePasHz = settings.molettePasHz)
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.Ignore -> false
        }
    }

    fun setMolettePas(hz: Long) {
        settings.molettePasHz = hz
        _ui.value = _ui.value.copy(molettePasHz = hz)
    }

    /**
     * Index of the key being learnt, or `null`. Out of `UiState`: transient
     * settings state.
     */
    private val _apprentissage = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val apprentissage: kotlinx.coroutines.flow.StateFlow<Int?> = _apprentissage

    fun debuteApprentissage(rang: Int) { _apprentissage.value = rang }
    fun annuleApprentissage() { _apprentissage.value = null }

    fun setMoletteCible(nom: String) {
        settings.moletteCible = nom
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(moletteCible = nom))
    }

    /**
     * Stores a learnt key. A key cannot serve twice: otherwise the box would
     * switch targets unpredictably and look like a hardware fault.
     */
    fun apprendMacro(rang: Int, code: Int) {
        if (!fr.f4ioz.satcombo.domain.MoletteUsb.apprenable(code)) return
        var a = _ui.value.accord
        if (a.macroCodeA == code) a = a.copy(macroCodeA = 0)
        if (a.macroCodeB == code) a = a.copy(macroCodeB = 0)
        if (a.macroCodeC == code) a = a.copy(macroCodeC = 0)
        if (a.macroCodeD == code) a = a.copy(macroCodeD = 0)
        a = when (rang) {
            0 -> a.copy(macroCodeA = code)
            1 -> a.copy(macroCodeB = code)
            2 -> a.copy(macroCodeC = code)
            else -> a.copy(macroCodeD = code)
        }
        settings.macroCodeA = a.macroCodeA
        settings.macroCodeB = a.macroCodeB
        settings.macroCodeC = a.macroCodeC
        settings.macroCodeD = a.macroCodeD
        _ui.value = _ui.value.copy(accord = a)
    }

    /** Push-button action: "PAS", "CIBLE" or "ZERO". */
    fun setMacroAction(action: String) {
        settings.macroActionD = action
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(macroActionD = action))
    }

    fun setMacroCible(rang: Int, cible: String) {
        var a = _ui.value.accord
        a = when (rang) {
            0 -> a.copy(macroCibleA = cible)
            1 -> a.copy(macroCibleB = cible)
            else -> a.copy(macroCibleC = cible)
        }
        settings.macroCibleA = a.macroCibleA
        settings.macroCibleB = a.macroCibleB
        settings.macroCibleC = a.macroCibleC
        _ui.value = _ui.value.copy(accord = a)
    }

    fun setMoletteVfo(on: Boolean) {
        settings.moletteVfo = on
        _ui.value = _ui.value.copy(moletteVfo = on)
    }

    fun setClavierDisposition(nom: String) {
        settings.clavierDisposition = nom
        _ui.value = _ui.value.copy(express = _ui.value.express.copy(disposition = nom))
    }

    fun setSdrCalageVoix(on: Boolean) {
        settings.sdrCalageVoix = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(calageVoix = on))
    }

    /**
     * Locks onto the other station's voice, in SSB. The mode comes from the
     * displayed state, not the chain: if they differ, what the operator sees
     * is what his hand acts on.
     */
    fun sdrCaleSurLaVoix() {
        val cible = fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz(_ui.value.sdrMode)
        if (cible == 0) return
        fr.f4ioz.satcombo.sdr.SdrHub.caleVoix(cible)
    }

    /**
     * Same voice lock on the QO-100 page, with one more step. There the number
     * that matters is a **downlink** frequency, while the lock acts on the
     * receiver's fine offset, which that page ignores. The voice would sound
     * right while the displayed number stayed a few hundred Hz off — and that
     * is the number announced to the other station.
     *
     * So wait for the reader thread to resolve the measurement, fold it into
     * the downlink and reset the fine offset. The brief jump back lasts one
     * block; barely audible, and better than two diverging numbers.
     */
    fun qo100CaleSurLaVoix() {
        val hub = fr.f4ioz.satcombo.sdr.SdrHub
        if (!hub.isRunning) return
        val avant = hub.state.value.tunedAtMs
        // The narrowband transponder is always USB.
        hub.caleVoix(fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz("USB"))
        viewModelScope.launch {
            repeat(25) {
                delay(120)
                val st = hub.state.value
                if (st.tunedAtMs != avant) {
                    val corr = st.offsetHz - st.dopplerFineHz
                    if (corr != 0L) {
                        hub.setOffset(0)
                        setQo100Descente(_ui.value.qo100.descenteHz + corr)
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Relative fine-tuning step, Hz, pushed by the vernier on the SDR page. On
     * QO-100 [qo100Pas] does it, since the displayed value there is a downlink,
     * not an offset.
     */
    fun sdrPasFin(deltaHz: Long) {
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        setSdrOffset((st.offsetHz + deltaHz).toInt().coerceIn(-80_000, 80_000))
    }

    /** Software fine tuning, Hz around the dongle frequency (finger on the waterfall). */
    fun setSdrOffset(hz: Int) {
        fr.f4ioz.satcombo.sdr.SdrHub.setOffset(hz.coerceIn(-80_000, 80_000))
    }

    val currentActivation: fr.f4ioz.satcombo.data.Activation?
        get() = _ui.value.activations.firstOrNull { it.running }

    fun startActivation(name: String, note: String = "") {
        val obs = _ui.value.observer
        val a = fr.f4ioz.satcombo.data.Activation(
            startMs = System.currentTimeMillis(),
            name = name.trim(),
            locator = myLocator(),
            grids = myGridsCsv(),
            latDeg = obs?.latDeg ?: 0.0,
            lonDeg = obs?.lonDeg ?: 0.0,
            callsign = _ui.value.callsign,
            note = note.trim()
        )
        _ui.value = _ui.value.copy(activations = activationStore.start(a))
    }

    fun stopActivation(startMs: Long? = null) {
        val id = startMs ?: currentActivation?.startMs ?: return
        _ui.value = _ui.value.copy(activations = activationStore.stop(id))
    }

    fun updateActivation(a: fr.f4ioz.satcombo.data.Activation) {
        _ui.value = _ui.value.copy(activations = activationStore.update(a))
    }

    fun deleteActivation(startMs: Long) {
        _ui.value = _ui.value.copy(activations = activationStore.delete(startMs))
    }

    /** QSOs logged inside [a]'s time window. */
    fun activationQsos(a: fr.f4ioz.satcombo.data.Activation): List<LogEntry> =
        fr.f4ioz.satcombo.data.ActivationStore.qsosOf(a, _ui.value.log)

    /** Builds the A4 activation sheet and hands back a shareable URI. */
    fun exportActivationPdf(a: fr.f4ioz.satcombo.data.Activation, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                val file = fr.f4ioz.satcombo.data.ActivationPdf.build(
                    getApplication(), a, activationQsos(a), _ui.value.useUtc)
                fr.f4ioz.satcombo.data.ActivationPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /** ADIF limited to one session — ready to import in the logbook. */
    fun activationAdif(a: fr.f4ioz.satcombo.data.Activation): String =
        LogStore.toAdif(activationQsos(a), settings.callsign)

    // ================= timeline =================

    fun openTimeline() {
        va(Screen.TIMELINE)
        _ui.value = _ui.value.copy(screen = Screen.TIMELINE)
        computeTimeline()
    }

    fun closeTimeline() { _ui.value = retour() }

    fun setTimelineHours(h: Int) {
        _ui.value = _ui.value.copy(timelineHours = h.coerceIn(1, 24))
        computeTimeline()
    }

    /**
     * Elevation curve of every followed satellite over a rolling window that
     * starts now. One sample per minute-ish, computed off the main thread.
     */
    fun computeTimeline() {
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) {
            _ui.value = _ui.value.copy(timelineTracks = emptyList(), timelineLoading = false)
            return
        }
        val from = System.currentTimeMillis()
        val to = from + _ui.value.timelineHours * 3_600_000L
        _ui.value = _ui.value.copy(timelineLoading = true, timelineFromMs = from)
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.Default) {
                favs.map { sat ->
                    val samples = predictor.sampleTrack(sat, obs, from, to, steps = 240)
                        .map { (t, _, el) -> t to el }
                    TimelineTrack(sat.catalogNumber, sat.name, samples)
                }.sortedByDescending { it.peakDeg }
            }
            _ui.value = _ui.value.copy(timelineTracks = tracks, timelineLoading = false)
        }
    }

    private val configBackup = fr.f4ioz.satcombo.data.ConfigBackup(app)

    /** Full configuration as JSON (tracked satellites, settings, per-sat config, log). */
    fun exportConfig(): String = configBackup.export()
    fun configFileName(): String = configBackup.suggestedFileName()

    /**
     * Restore a configuration exported by [exportConfig]. Reloads everything
     * in place so the UI reflects the imported settings immediately.
     */
    fun importConfig(json: String) {
        val summary = configBackup.import(json)
        if (summary == null) {
            _ui.value = _ui.value.copy(error = t("config_invalid"))
            return
        }
        // Re-read every store into the UI state.
        _ui.value = _ui.value.copy(
            favorites = favStore.load(),
            enabledSources = srcStore.load(),
            manualLocator = settings.manualLocator,
            manualLat = settings.manualLat.takeIf { !it.isNaN() },
            manualLon = settings.manualLon.takeIf { !it.isNaN() },
            locationMode = settings.locationMode,
            useUtc = settings.useUtc,
            darkTheme = settings.darkTheme,
            // Without this the picker falls back to "Dark" on every launch,
            // whatever palette is applied: the palette was restored, its index
            // was not.
            themeIndex = settings.themeIndex,
            moletteVfo = settings.moletteVfo,
            molettePasHz = settings.molettePasHz,
            uniformUi = settings.uniformUi,
            uiScaleStep = settings.uiScaleStep,
            uiFollowSystemFont = settings.uiFollowSystemFont,
            language = settings.language,
            statusSource = settings.statusSource,
            compassHeadUp = settings.compassHeadUp,
            compassStyle = settings.compassStyle,
            aimMode = settings.aimMode,
            showAimModeChips = settings.showAimModeChips,
            civAddress = settings.civAddress,
            civBaud = settings.civBaud,
            civUsbIndex = settings.civUsbIndex,
            civUsbAuto = settings.civUsbAuto,
            catSimulated = settings.catSimulated,
            catMonitor = settings.catMonitor,
            catRxDoppler = settings.catRxDoppler,
            catTestSendAlways = settings.catSousHorizon,
            rigModel = settings.rigModel,
            ctcssTenthHz = settings.ctcssTenthHz,
            ctcssAuto = settings.ctcssAuto,
            skedsEnabled = settings.skedsEnabled,
            skedsMutualOnly = settings.skedsMutualOnly,
            log = logStore.load(),
            error = null,
            rotor = _ui.value.rotor.copy(
                boussoleSource = settings.boussoleSource,
                boussoleAdresse = settings.boussoleAdresse,
                boussoleNom = settings.boussoleNom,
                boussoleCalage = settings.boussoleCalage,
                boussoleConvention = settings.boussoleConvention,
                boussoleReleves = settings.boussoleReleves,
                boussoleFleche = settings.boussoleFleche,
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
                rotorMinEl = settings.rotorMinEl,
                rotorDeadband = settings.rotorDeadband,
                rotorFlip = settings.rotorFlip,
                rotorAzOnly = settings.rotorAzOnly,
                rotorParkAz = settings.rotorParkAz,
                rotorParkEl = settings.rotorParkEl)
        )
        fr.f4ioz.satcombo.ui.theme.applyTheme(settings.themeIndex)
        fr.f4ioz.satcombo.ui.theme.applyUiScale(
            settings.uniformUi, settings.uiScaleStep, settings.uiFollowSystemFont)
        fr.f4ioz.satcombo.i18n.I18n.apply(settings.language, java.util.Locale.getDefault().language)
        _ui.value = _ui.value.copy(catStatus = summary)
        // Recompute passes with the restored QTH/favorites.
        bootstrap(force = true)
    }

    /**
     * Two or three taps to log. Three is the safe default; two suits an
     * operator whose other hand is on the antenna, at the price of the odd
     * accidental entry. Anything else is refused: one tap would fire on every
     * touch of the compass.
     */
    fun setLogTaps(n: Int) {
        val v = n.coerceIn(2, 3)
        settings.logTaps = v
        _ui.value = _ui.value.copy(logTaps = v)
    }

    fun dismissLogEdit() { _ui.value = _ui.value.copy(logEditTimeMs = null) }

    fun editLogEntry(timeMs: Long) { _ui.value = _ui.value.copy(logEditTimeMs = timeMs) }

    fun setSkedsMutualOnly(on: Boolean) {
        settings.skedsMutualOnly = on
        _ui.value = _ui.value.copy(skedsMutualOnly = on)
    }

    /**
     * Mutual-visibility window for a sked: the time span where the satellite is
     * above the horizon BOTH at my QTH and at the announced station's grid.
     * Returns null if they never see it together (sked not workable from here).
     */
    fun skedMutualWindow(sked: SkedAlert): Pair<Long, Long>? {
        val obs = _ui.value.observer ?: return null
        val tle = _ui.value.satellites.firstOrNull { it.catalogNumber == sked.satNorad } ?: return null
        val grid = sked.grids.firstOrNull() ?: return null
        val (glat, glon) = Maidenhead.toLatLon(grid) ?: return null
        val other = Observer(glat, glon, 0.1, "sked")

        fun bothElev(t: Long): Boolean {
            val a = runCatching { predictor.positionAt(tle, obs, t).elevationDeg }.getOrDefault(-90.0)
            if (a <= 0.0) return false
            val b = runCatching { predictor.positionAt(tle, other, t).elevationDeg }.getOrDefault(-90.0)
            return b > 0.0
        }

        // The announced aos/los is the station's own window. With an aging TLE the
        // real common pass can sit up to ~40 min away, so search a WIDE bracket
        // (±60 min) and keep the common window nearest the announced center.
        val centerMs = (sked.aosMs + sked.losMs) / 2
        val from = sked.aosMs - 60 * 60_000L
        val to = sked.losMs + 60 * 60_000L
        val coarse = 30_000L

        // Collect every contiguous common interval in the bracket, then pick the
        // one whose midpoint is closest to the announced center.
        val intervals = mutableListOf<Pair<Long, Long>>()
        var segStart: Long? = null; var prev: Long? = null
        var t = from
        while (t <= to) {
            if (bothElev(t)) {
                if (segStart == null) segStart = t
                prev = t
            } else if (segStart != null) {
                intervals.add(segStart!! to (prev ?: segStart!!)); segStart = null
            }
            t += coarse
        }
        if (segStart != null) intervals.add(segStart!! to (prev ?: segStart!!))
        if (intervals.isEmpty()) return null

        val best = intervals.minByOrNull { kotlin.math.abs((it.first + it.second) / 2 - centerMs) }!!
        // Refine the edges at 5 s resolution.
        val step = 5_000L
        var s = (best.first - coarse).coerceAtLeast(from)
        while (s < best.first && !bothElev(s)) s += step
        var e = (best.second + coarse).coerceAtMost(to)
        while (e > best.second && !bothElev(e)) e -= step
        return s to e
    }

    /** True if the announced sat is visible from both stations together around the sked window. */
    fun isSkedWorkable(sked: SkedAlert): Boolean {
        // Unknown TLE or no grid: keep it visible rather than hide wrongly.
        _ui.value.satellites.firstOrNull { it.catalogNumber == sked.satNorad } ?: return true
        if (sked.grids.isEmpty()) return true
        return skedMutualWindow(sked) != null
    }

    private fun skedVisible(s: SkedAlert): Boolean =
        !_ui.value.skedsMutualOnly || isSkedWorkable(s)

    /** Announced callsigns for a satellite (future skeds), soonest first. */
    /** Age in days of the loaded TLE's epoch for a satellite, or null. */
    fun tleEpochAgeDays(catnum: Int): Double? {
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        val epoch = sat.epochMs ?: return null
        return (System.currentTimeMillis() - epoch) / 86_400_000.0
    }

    /**
     * Ensure a fresh-enough TLE before relying on a LOCAL sked computation
     * (used when not authenticated, or on a different locator). Forces a
     * single-satellite refresh if the loaded TLE epoch is older than 1 day.
     */
    fun ensureFreshTleForLocalSked(catnum: Int) {
        if (_ui.value.skedsAuthed) return  // hams.at provides authoritative windows
        val age = tleEpochAgeDays(catnum) ?: return
        if (age > 1.0) refreshTleFor(catnum)
    }

    fun skedsFor(catnum: Int): List<SkedAlert> =
        _ui.value.skeds.filter {
            it.satNorad == catnum && it.losMs > System.currentTimeMillis() && skedVisible(it)
        }.sortedBy { it.aosMs }

    /**
     * The announced skeds that fall on a given pass, soonest first. Same matching
     * as [skedCountForPass] — which is now written on top of this — so a badge and
     * the card it stands for can never disagree.
     */
    fun skedsForPass(catnum: Int, aosMs: Long, losMs: Long): List<SkedAlert> {
        val passMid = (aosMs + losMs) / 2
        return _ui.value.skeds.filter { s ->
            if (s.satNorad != catnum) return@filter false
            val sAos = s.workableStartMs ?: s.aosMs
            val sLos = s.workableEndMs ?: s.losMs
            val sMid = (sAos + sLos) / 2
            kotlin.math.abs(sMid - passMid) < 50 * 60_000L && skedVisible(s)
        }.sortedBy { it.workableStartMs ?: it.aosMs }
    }

    /** Count of announced skeds overlapping a given pass window (±a few min). */
    fun skedCountForPass(catnum: Int, aosMs: Long, losMs: Long): Int =
        skedsForPass(catnum, aosMs, losMs).size

    fun setPotaEnabled(on: Boolean) {
        settings.potaEnabled = on
        _ui.value = _ui.value.copy(potaEnabled = on)
        if (on) refreshPota() else _ui.value = _ui.value.copy(nearbyPota = emptyList())
    }

    fun updatePotaRegion(region: PotaRegion) {
        _ui.value = _ui.value.copy(potaUpdating = true)
        viewModelScope.launch {
            val n = potaRepo.updateRegion(region)
            _ui.value = _ui.value.copy(
                potaUpdating = false,
                potaRegionLabel = potaRepo.downloadedRegion,
                potaCount = potaRepo.downloadedCount)
            if (n != null) refreshPota()
        }
    }

    /** Parks inside a map window, for the locator map overlay. */
    suspend fun potaInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double):
            List<PotaPark> = potaRepo.inBounds(minLat, maxLat, minLon, maxLon)

    fun setPotaRadius(km: Int) {
        settings.potaRadiusKm = km
        _ui.value = _ui.value.copy(potaRadiusKm = km)
        refreshPota()
    }

    private fun refreshPota() {
        if (!_ui.value.potaEnabled) return
        val obs = _ui.value.observer ?: return
        viewModelScope.launch {
            val near = potaRepo.near(obs.latDeg, obs.lonDeg, context = getApplication(),
                radiusKm = _ui.value.potaRadiusKm.toDouble())
            _ui.value = _ui.value.copy(nearbyPota = near)
        }
    }

    fun searchCity(q: String) {
        if (q.isBlank()) return
        _ui.value = _ui.value.copy(geoSearching = true)
        viewModelScope.launch {
            val res = geocoder.search(q)
            _ui.value = _ui.value.copy(geoResults = res, geoSearching = false)
        }
    }

    fun clearGeoResults() { _ui.value = _ui.value.copy(geoResults = emptyList()) }

    fun requestDatePicker() { _ui.value = _ui.value.copy(showDatePicker = true) }
    fun dismissDatePicker() { _ui.value = _ui.value.copy(showDatePicker = false) }

    fun setDateFilter(startMs: Long?, endMs: Long?) {
        val filter = if (startMs != null && endMs != null && endMs > startMs)
            startMs to minOf(endMs, startMs + MAX_PASS_DAYS * 86_400_000L) else null
        _ui.value = _ui.value.copy(dateFilter = filter, showDatePicker = false)
        computeFavoritePasses()
    }

    fun clearDateFilter() {
        _ui.value = _ui.value.copy(dateFilter = null)
        computeFavoritePasses()
    }

    /**
     * Extends the list by two days each time the bottom is reached, up to
     * fifteen days. Recomputed from the start rather than appended: prediction
     * is deterministic and linear in cost, while stitched chunks would
     * eventually drop a pass straddling the seam.
     */
    fun extendPassHorizon() {
        if (_ui.value.dateFilter != null) return
        if (_ui.value.favPassesLoading) return
        val cur = _ui.value.passHorizonHours
        val max = MAX_PASS_DAYS * 24
        if (cur >= max) return
        _ui.value = _ui.value.copy(passHorizonHours = minOf(cur + 48, max))
        computeFavoritePasses()
    }

    fun setMapStyle(style: String) {
        settings.mapStyle = style
        _ui.value = _ui.value.copy(mapStyle = style)
    }

    fun setNotifyEnabled(on: Boolean) {
        settings.notifyEnabled = on
        _ui.value = _ui.value.copy(notifyEnabled = on)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    fun setNotifyMode(mode: String) {
        settings.notifyMode = mode
        _ui.value = _ui.value.copy(notifyMode = mode)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    /** Toggle a bell on a specific pass (targeted notifications). */
    fun togglePassNotify(p: fr.f4ioz.satcombo.data.SatPass) {
        val key = passKey(p)
        val cur = _ui.value.notifiedPassKeys.toMutableSet()
        if (!cur.add(key)) cur.remove(key)
        settings.notifiedPassKeys = cur
        _ui.value = _ui.value.copy(notifiedPassKeys = cur)
        schedulePassAlerts(_ui.value.favoritePasses)
    }
    fun isPassNotified(p: fr.f4ioz.satcombo.data.SatPass) = passKey(p) in _ui.value.notifiedPassKeys

    fun setNotifyLead(min: Int) {
        settings.notifyLeadMin = min
        _ui.value = _ui.value.copy(notifyLeadMin = min)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    private fun schedulePassAlerts(passes: List<SatPass>) {
        val wm = WorkManager.getInstance(getApplication())
        wm.cancelAllWorkByTag("pass_alert")
        if (!_ui.value.notifyEnabled) return
        val lead = _ui.value.notifyLeadMin * 60_000L
        val now = System.currentTimeMillis()

        // In TARGET mode, only bell-marked passes fire; look across favourite
        // AND general passes so any pass the user tapped a bell on is covered.
        val candidates = if (_ui.value.notifyMode == "TARGET") {
            val keys = _ui.value.notifiedPassKeys
            (passes + _ui.value.passes).distinctBy { passKey(it) }
                .filter { passKey(it) in keys }
        } else {
            // FAV mode (default): only favourite satellites wake the operator.
            // The list is already favourites, but computed elsewhere; the
            // explicit filter costs one line and guarantees no unchosen
            // satellite rings at 3 a.m.
            val favs = _ui.value.favorites
            passes.filter { it.catalogNumber in favs }
        }

        candidates.filter { it.aosEpochMs - lead > now }
            .sortedBy { it.aosEpochMs }
            .take(20)
            .forEach { p ->
                val req = OneTimeWorkRequestBuilder<PassAlertWorker>()
                    .setInitialDelay(p.aosEpochMs - lead - now, TimeUnit.MILLISECONDS)
                    .addTag("pass_alert")
                    .setInputData(workDataOf(
                        "name" to p.satName, "cat" to p.catalogNumber,
                        "aos" to p.aosEpochMs, "los" to p.losEpochMs,
                        "maxEl" to p.maxElevationDeg,
                        "aosAz" to p.aosAzimuthDeg, "losAz" to p.losAzimuthDeg,
                        "sunlit" to p.sunlit, "night" to p.nightAtObserver))
                    .build()
                wm.enqueue(req)
            }
    }

    fun setLanguage(pref: String) {
        settings.language = pref
        fr.f4ioz.satcombo.i18n.I18n.apply(pref, java.util.Locale.getDefault().language)
        _ui.value = _ui.value.copy(language = pref)
    }

    fun setDarkTheme(on: Boolean) = setThemeIndex(if (on) 0 else 1)

    fun setThemeIndex(i: Int) {
        settings.themeIndex = i
        fr.f4ioz.satcombo.ui.theme.applyTheme(i)
        _ui.value = _ui.value.copy(darkTheme = i == 0, themeIndex = i)
    }

    /** Uniform layout master switch — off falls back to the device's own dp. */
    fun setUniformUi(on: Boolean) {
        settings.uniformUi = on
        fr.f4ioz.satcombo.ui.theme.applyUiScale(on, settings.uiScaleStep, settings.uiFollowSystemFont)
        _ui.value = _ui.value.copy(uniformUi = on)
    }

    /** -1 compact / 0 normal / +1 large. */
    fun setUiScaleStep(step: Int) {
        settings.uiScaleStep = step
        val v = settings.uiScaleStep
        fr.f4ioz.satcombo.ui.theme.applyUiScale(settings.uniformUi, v, settings.uiFollowSystemFont)
        _ui.value = _ui.value.copy(uiScaleStep = v)
    }

    fun setUiFollowSystemFont(on: Boolean) {
        settings.uiFollowSystemFont = on
        fr.f4ioz.satcombo.ui.theme.applyUiScale(settings.uniformUi, settings.uiScaleStep, on)
        _ui.value = _ui.value.copy(uiFollowSystemFont = on)
    }

    fun setStatusSource(s: String) {
        settings.statusSource = s
        _ui.value = _ui.value.copy(statusSource = s)
    }

    fun setTleCacheHours(h: Int) {
        settings.tleCacheHours = h
        _ui.value = _ui.value.copy(tleCacheHours = settings.tleCacheHours)
    }

    fun setCompassStyle(style: String) {
        settings.compassStyle = style
        _ui.value = _ui.value.copy(compassStyle = style)
    }

    fun setCompassHeadUp(on: Boolean) {
        settings.compassHeadUp = on
        _ui.value = _ui.value.copy(compassHeadUp = on)
    }

    fun setShowAimModeChips(on: Boolean) {
        settings.showAimModeChips = on
        _ui.value = _ui.value.copy(showAimModeChips = on)
    }

    fun setAimMode(mode: String) {
        settings.aimMode = mode
        _ui.value = _ui.value.copy(aimMode = mode)
    }

    // ---- Remote compass ----

    fun setBoussoleSource(src: String) {
        settings.boussoleSource = src
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleSource = src))
    }

    /** Remembers the module picked in the list, without connecting. */
    fun setBoussoleModule(nom: String, adresse: String) {
        settings.boussoleNom = nom
        settings.boussoleAdresse = adresse
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(
            boussoleNom = nom, boussoleAdresse = adresse))
    }

    fun setBoussoleCalage(deg: Float) {
        settings.boussoleCalage = deg
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleCalage = deg))
    }

    /** Adds a pose to the readings, or replaces the one with the same key. */
    fun ajouteReleve(cle: String, roulis: Float, tangage: Float, lacet: Float) {
        val liste = fr.f4ioz.satcombo.domain.SequenceCalibrage
            .decode(settings.boussoleReleves)
            .filterNot { it.cle == cle } +
            fr.f4ioz.satcombo.domain.SequenceCalibrage.Releve(cle, roulis, tangage, lacet)
        // Sorted in sequence order, not gesture order: a report following the
        // pose order can be re-read.
        val ordonnees = fr.f4ioz.satcombo.domain.SequenceCalibrage.ETAPES
            .mapNotNull { e -> liste.firstOrNull { it.cle == e.cle } }
        val texte = fr.f4ioz.satcombo.domain.SequenceCalibrage.encode(ordonnees)
        settings.boussoleReleves = texte
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleReleves = texte))
    }

    fun videReleves() {
        settings.boussoleReleves = ""
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleReleves = ""))
    }

    /**
     * The two displayed frequencies, downlink and uplink, Doppler included.
     *
     * **One function, two callers.** The pass screen and the audience page
     * each computed them and drifted apart (5 kHz on RX, almost 2 on TX). Two
     * paths for one question always end up disagreeing: keep only one.
     */
    fun freqAffichees(): Pair<Long?, Long?> {
        val u = _ui.value
        val actifs = activeTransmitters()
        if (actifs.isEmpty()) return null to null
        val tx = actifs[u.selectedTxIndex.coerceIn(0, actifs.size - 1)]
        val rr = u.livePosition?.rangeRateKmS
        val dlLow = tx.downlinkLowHz
        val dlHigh = tx.downlinkHighHz
        val ulLow = tx.uplinkLowHz
        val ulHigh = tx.uplinkHighHz
        val rxRest = u.rxRestHz ?: dlLow
        val rxOff = if (u.opMode == "CW") u.rxOffsetCwHz else u.rxOffsetVoiceHz
        val effInvert = u.invertOverride ?: tx.invert
        val rxShown = rxRest?.let { base ->
            (rr?.let { Doppler.downlink(base, it) } ?: base) + u.calibShiftHz +
                (if (tx.isTransponder) rxOff else 0L)
        }
        val txRest = when {
            tx.isTransponder && rxRest != null && dlLow != null && dlHigh != null &&
                ulLow != null && ulHigh != null ->
                Doppler.transponderUplinkRest(rxRest, dlLow, dlHigh, ulLow, ulHigh,
                    effInvert)
            else -> ulLow
        }
        val txShown = txRest?.let { base ->
            (rr?.let { Doppler.uplink(base, it) } ?: base) + u.txShiftHz
        }
        return rxShown to txShown
    }

    private var dernierApercu: android.graphics.Bitmap? = null

    /**
     * Builds the telemetry sent to the audience. Hand-written JSON: a handful
     * of fields does not justify a serializer dependency. It reads the state
     * instead of taking arguments, so it works from any screen, even with no
     * tracking running. The pass trace keeps one point in three: sixty points
     * draw an arc, with a third of the text sent every second to each viewer.
     */
    private fun publieDemo() {
        val u = _ui.value
        val pos = u.livePosition
        val nom = u.selected?.name ?: "—"
        val trace = u.passTrack.filterIndexed { i, _ -> i % 3 == 0 }
            .joinToString(",") {
                "[%.1f,%.1f]".format(java.util.Locale.US, it.first, it.second)
            }
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date()) + " UTC"
        val az = pos?.azimuthDeg ?: 0.0
        val el = if (pos == null) "null"
                 else "%.1f".format(java.util.Locale.US, pos.elevationDeg)
        // Same frequencies as the screen, because same function.
        val (calcRx, calcTx) = freqAffichees()
        val rx = (u.catRadioDownlinkHz ?: calcRx)?.toString() ?: "null"
        val tx = (u.catRadioUplinkHz ?: calcTx)?.toString() ?: "null"

        // Current or next pass: AOS, LOS, max elevation — what tells the
        // audience what is at stake: ten minutes, no more.
        val passage = u.focusedPassAos
            ?.let { f -> u.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: u.passes.firstOrNull { it.losEpochMs > u.nowMs }
        val aos = passage?.aosEpochMs?.toString() ?: "null"
        val los = passage?.losEpochMs?.toString() ?: "null"
        val elMax = passage?.let { "%.0f".format(java.util.Locale.US, it.maxElevationDeg) }
            ?: "null"

        // Actual antenna heading: what the audience sees move in the
        // operator's hands; next to the satellite position it makes pointing
        // obvious. The **effective** heading: remote module if it talks, phone
        // compass otherwise (reading the module alone showed nothing most of
        // the time).
        val capAnt = fr.f4ioz.satcombo.domain.CapVivant.azimutDeg
        val elAnt = fr.f4ioz.satcombo.domain.CapVivant.elevationDeg
        val antAz = capAnt?.let { "%.0f".format(java.util.Locale.US, it) } ?: "null"
        val antEl = elAnt?.let { "%.0f".format(java.util.Locale.US, it) } ?: "null"
        val propre = nom.replace("\\", " ").replace("\"", " ")
        // SSTV image, encoded only when it changes: re-encoding tens of KB
        // every second would heat the phone for the whole pass.
        val apercu = fr.f4ioz.satcombo.sstv.SstvHub.state.value.preview
        if (apercu != null && apercu !== dernierApercu) {
            dernierApercu = apercu
            runCatching {
                val flux = java.io.ByteArrayOutputStream()
                apercu.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, flux)
                fr.f4ioz.satcombo.demo.ServeurDemo.poseImage(flux.toByteArray())
            }
        }
        val station = settings.callsign.replace("\"", " ")
        val grille = myLocator()
        fr.f4ioz.satcombo.demo.ServeurDemo.publie(
            "{\"station\":\"" + station + "\",\"grille\":\"" + grille + "\"," +
                "\"img\":" + fr.f4ioz.satcombo.demo.ServeurDemo.versionImage() + "," +
                "\"qso\":[" + fr.f4ioz.satcombo.demo.ServeurDemo.contactsJson() + "]," +
                "\"sat\":\"" + propre + "\"," +
                "\"az\":" + "%.1f".format(java.util.Locale.US, az) + "," +
                "\"el\":" + el + ",\"rx\":" + rx + ",\"tx\":" + tx + "," +
                "\"antaz\":" + antAz + ",\"antel\":" + antEl + "," +
                "\"aos\":" + aos + ",\"los\":" + los + ",\"elmax\":" + elMax + "," +
                "\"now\":" + u.nowMs + "," +
                "\"son\":" + fr.f4ioz.satcombo.demo.ServeurDemo.sonDisponible() + "," +
                "\"ptt\":" + u.catUi.enEmission + ",\"heure\":\"" + heure + "\"," +
                "\"trace\":[" + trace + "]}")
    }

    /**
     * Stores the hotspot announced by the QR code. The server holds it during
     * the session; settings only remember it between sessions.
     */
    fun setDemoWifi(ssid: String, motDePasse: String) {
        settings.demoSsid = ssid
        settings.demoMotDePasse = motDePasse
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(ssid, motDePasse)
    }

    /** Remembers the listened station's address, so it need not be retyped. */
    fun setEcouteAdresse(v: String) { settings.ecouteAdresse = v }

    fun ecouteAdresse(): String = settings.ecouteAdresse

    fun setBoussoleConvention(nom: String) {
        settings.boussoleConvention = nom
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleConvention = nom))
    }

    /**
     * Stores the learnt boom axis, or forgets it. `null` gives elevation back
     * to the phone and heading to yaw alone — right after a dismount, until
     * the new calibration. A dial that says it does not know beats a wrong one.
     */
    fun setBoussoleFleche(v: fr.f4ioz.satcombo.domain.Vec3?) {
        val t = v?.let { fr.f4ioz.satcombo.domain.PointageAntenne.enTexte(it) } ?: ""
        settings.boussoleFleche = t
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleFleche = t))
    }

    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }
    fun setSatActiveOnly(on: Boolean) { _ui.value = _ui.value.copy(satActiveOnly = on) }

    fun toggleSource(id: String) {
        val cur = _ui.value.enabledSources.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        if (cur.isEmpty()) return
        srcStore.save(cur)
        _ui.value = _ui.value.copy(enabledSources = cur)
        bootstrap(force = true)
    }

    fun toggleFavorite(catnum: Int) {
        val updated = favStore.toggle(catnum)
        _ui.value = _ui.value.copy(favorites = updated)
        computeFavoritePasses()
    }

    fun toggleVisualOnly() {
        _ui.value = _ui.value.copy(visualOnly = !_ui.value.visualOnly)
    }

    // ---------- passes ----------

    private companion object {
        /**
         * Maximum prediction depth, days. Beyond, orbital elements age enough
         * for pass times to drift by minutes: better promise nothing.
         */
        const val MAX_PASS_DAYS = 15

        /**
         * Safety cap on passes kept. Fifteen days, thirty favourites and a low
         * minimum elevation make about two thousand rows: the cap must stay
         * above that real case, or it becomes a silent cut again.
         */
        const val MAX_PASSES = 4000
    }

    private fun computeFavoritePasses() {
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) {
            _ui.value = _ui.value.copy(favoritePasses = emptyList(), favPassesLoading = false)
            return
        }
        _ui.value = _ui.value.copy(favPassesLoading = true)
        viewModelScope.launch {
            val filter = _ui.value.dateFilter
            val now = System.currentTimeMillis()
            // "Past passes": the lists reach back this far so the pass just
            // worked stays available (polar plot included) to write up the log.
            val back = maxOf(_ui.value.pastPassHours * 3_600_000L, 20 * 60_000L)
            val from: Long; val hours: Int; val windowEnd: Long
            if (filter != null) {
                from = maxOf(filter.first, now - back)
                windowEnd = filter.second
                hours = (((windowEnd - from) / 3_600_000L).toInt() + 1)
                    .coerceIn(1, MAX_PASS_DAYS * 24 + 24)
            } else {
                from = now - back
                windowEnd = now + _ui.value.passHorizonHours * 3_600_000L
                hours = _ui.value.passHorizonHours + _ui.value.pastPassHours
            }
            val merged = withContext(Dispatchers.Default) {
                favs.flatMap { predictor.upcomingPasses(it, obs, fromMs = from, hours = hours, minElDeg = settings.minElevDeg.toDouble()) }
                    .filter { it.losEpochMs > now - back && it.aosEpochMs < windowEnd }
                    .sortedBy { it.aosEpochMs }
                    // Safety cap only. The old limit of 120 silently dropped the
                    // last days of a long date range, and the displayed counter
                    // counted the cut list, so everything looked normal.
                    .take(MAX_PASSES)
            }
            _ui.value = _ui.value.copy(favoritePasses = merged, favPassesLoading = false)
            // Never ring for something already gone.
            schedulePassAlerts(merged.filter { it.losEpochMs > now })
        }
    }

    private fun centreRx(t: Transmitter): Long? {
        val lo = t.downlinkLowHz ?: return null
        val hi = t.downlinkHighHz ?: lo
        return (lo + hi) / 2
    }

    fun activeTransmitters(): List<Transmitter> =
        _ui.value.transmitters.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }

    /**
     * Goes anywhere in the band and **lets the transponder follow**.
     *
     * The ruler draws the whole QO-100 plan, but `setRxRest` clamps into the
     * selected transponder: you saw where to go and could not go there without
     * first picking the right one from a list of fifteen. The transponder is a
     * **consequence** of the frequency, not a precondition: switch to the one
     * containing it; if none (a beacon, a gap), keep the current one and go
     * anyway — listening commits to nothing.
     */
    fun allerLibre(satHz: Long) {
        val liste = activeTransmitters()
        val i = liste.indexOfFirst { t ->
            val lo = t.downlinkLowHz
            val hi = t.downlinkHighHz ?: lo
            lo != null && hi != null && satHz in minOf(lo, hi)..maxOf(lo, hi)
        }
        if (i >= 0 && i != _ui.value.selectedTxIndex) {
            liste.getOrNull(i)?.let { t ->
                _ui.value.selected?.let {
                    satConfigStore.saveTransmitter(it.catalogNumber, t.description)
                }
            }
            catArmedFor = null
            _ui.value = _ui.value.copy(selectedTxIndex = i)
        }
        // No clamping: the ruler is the whole band plan.
        _ui.value = _ui.value.copy(rxRestHz = satHz, rxFromAgendaHz = null)
    }

    fun selectTransmitter(index: Int) {
        val t = activeTransmitters().getOrNull(index) ?: return
        _ui.value.selected?.let { satConfigStore.saveTransmitter(it.catalogNumber, t.description) }
        catArmedFor = null
        _ui.value = _ui.value.copy(selectedTxIndex = index, rxRestHz = centreRx(t),
            rxFromAgendaHz = null)
    }

    fun openSatConfig() { _ui.value = _ui.value.copy(showSatConfig = true) }
    fun closeSatConfig() { _ui.value = _ui.value.copy(showSatConfig = false) }

    fun setCalibShift(hz: Long) {
        _ui.value.selected?.let { satConfigStore.saveShift(it.catalogNumber, hz) }
        _ui.value = _ui.value.copy(calibShiftHz = hz)
    }

    /**
     * Uplink at rest: the frequency actually transmitted on, Doppler removed.
     *
     * The log used to record `uplinkLowHz`, the transponder's **lower edge**
     * (145.950 on FO-29 wherever the contact was): constant, hence no
     * information. The real uplink derives from the chosen downlink by the
     * same rule as the Doppler engine, inversion included, plus the operator's
     * TX shift. The **rest** frequency goes to the log, not what was sent to
     * the rig: instant Doppler describes the pass geometry, not the
     * transponder slot, so two stations in QSO log the same thing.
     */
    fun monteeAuRepos(): Long? {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return null
        val rxRest = _ui.value.rxRestHz ?: centreRx(t) ?: t.downlinkLowHz ?: return null
        val base = when {
            t.isTransponder && t.downlinkLowHz != null && t.uplinkLowHz != null ->
                Doppler.transponderUplinkRest(
                    rxRest, t.downlinkLowHz!!, t.downlinkHighHz ?: t.downlinkLowHz!!,
                    t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
            else -> t.uplinkLowHz
        } ?: return null
        return base + _ui.value.txShiftHz
    }

    /** Downlink at rest, as logged. */
    fun descenteAuRepos(): Long? {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        return _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: t?.downlinkLowHz
    }

    /** TX (uplink) shift in Hz, persisted per satellite. */
    fun setTxShift(hz: Long) {
        _ui.value.selected?.let { satConfigStore.saveTxShift(it.catalogNumber, hz) }
        _ui.value = _ui.value.copy(txShiftHz = hz)
    }
    fun nudgeTxShift(deltaHz: Long) = setTxShift(_ui.value.txShiftHz + deltaHz)

    // ---- reference shifts ----

    /**
     * Saves the current shifts as this satellite's reference. Done by the
     * operator when he judges it right: automatic saving could not tell a
     * converging setting from a brushed knob.
     */
    fun memoriseDecalages() {
        val sat = _ui.value.selected ?: return
        satConfigStore.memoriseReference(
            sat.catalogNumber, _ui.value.calibShiftHz, _ui.value.txShiftHz)
        _ui.value = _ui.value.copy(
            catUi = _ui.value.catUi.copy(
                refCalibShiftHz = _ui.value.calibShiftHz,
                refTxShiftHz = _ui.value.txShiftHz),
            satStatus = tf("shift_saved",
                _ui.value.calibShiftHz, _ui.value.txShiftHz))
    }

    /**
     * Back to the shifts as they were when this satellite was opened: the
     * safety net for those who saved nothing. It undoes what happened since
     * opening — exactly what is wanted after brushing the knob.
     */
    fun rappelleArrivee() {
        val sat = _ui.value.selected ?: return
        val calib = _ui.value.catUi.arriveeCalibShiftHz ?: return
        val tx = _ui.value.catUi.arriveeTxShiftHz ?: 0L
        satConfigStore.saveShift(sat.catalogNumber, calib)
        satConfigStore.saveTxShift(sat.catalogNumber, tx)
        _ui.value = _ui.value.copy(calibShiftHz = calib, txShiftHz = tx,
            satStatus = tf("shift_back", calib, tx))
    }

    /** Back to the saved reference shifts for this satellite. */
    fun rappelleDecalages() {
        val sat = _ui.value.selected ?: return
        val cfg = satConfigStore.load(sat.catalogNumber)
        val calib = cfg.refCalibShiftHz ?: return
        val tx = cfg.refTxShiftHz ?: 0L
        satConfigStore.saveShift(sat.catalogNumber, calib)
        satConfigStore.saveTxShift(sat.catalogNumber, tx)
        _ui.value = _ui.value.copy(calibShiftHz = calib, txShiftHz = tx,
            satStatus = tf("shift_recalled", calib, tx))
    }

    // ---- contact batch, from one phone to another ----

    /** Serialises the chosen contacts, identified by their time. */
    fun ecritLotContacts(heures: Set<Long>): String =
        fr.f4ioz.satcombo.data.LotContacts.ecrit(
            _ui.value.log.filter { it.timeMs in heures && it.callsign.isNotBlank() },
            settings.callsign)

    /** Suggested batch file name. */
    fun nomLotContacts(): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "satme-contacts-" + f.format(java.util.Date()) + ".json"
    }

    /**
     * Merges a received batch into the local log and reports the result.
     * **Nothing is overwritten** (domain rule, tested). Disagreements are
     * counted, not resolved: nothing in an entry says which value was
     * corrected last.
     */
    fun fusionneLotContacts(json: String) {
        val entrant = fr.f4ioz.satcombo.data.LotContacts.lit(json)
        if (entrant == null) {
            _ui.value = _ui.value.copy(catStatus = t("lot_illisible"))
            return
        }
        val b = fr.f4ioz.satcombo.data.LotContacts.fusionne(_ui.value.log, entrant)
        logStore.save(b.fondu)
        _ui.value = _ui.value.copy(log = b.fondu,
            express = _ui.value.express.copy(memoire = construitMemoire()),
            catUi = _ui.value.catUi.copy(lotBilan = tf("lot_bilan", b.ajoutes, b.completes, b.identiques, b.desaccords)))
    }

    fun effaceBilanLot() {
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(lotBilan = ""))
    }

    /**
     * Delay before the software takes the RX knob back. All arbiters (linear,
     * FM, TX) are set together: two seconds too long is too long everywhere.
     */
    fun setCatHold(ms: Int) {
        val v = ms.coerceIn(200, 5_000)
        settings.catHoldMs = v
        rxArbiter.regle(v.toLong())
        fmArbiter.regle(v.toLong())
        txArbiter.regle(v.toLong())
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(holdMs = v))
    }

    /**
     * The TX knob acts as the shift control. Two-rig duplex only: on a single
     * rig in split the uplink is VFO B, not reliably readable while listening
     * on A across models — a switch that works half the time is worse than none.
     */
    fun setCatTxVfoShift(on: Boolean) {
        settings.catTxVfoShift = on
        txArbiter.reset()
        mainSurMoletteTx = false
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txVfoShift = on))
    }

    /** Operating sub-mode for linear birds: VOICE (SSB) or CW. */
    fun setOpMode(mode: String) {
        _ui.value = _ui.value.copy(opMode = mode)
        catArmedFor = null  // re-arm so the rig mode (USB/LSB/CW) updates
    }

    /** Per-mode RX offset, persisted per satellite. */
    fun setRxOffset(hz: Long) {
        if (_ui.value.opMode == "CW") {
            _ui.value.selected?.let { satConfigStore.saveRxOffsetCw(it.catalogNumber, hz) }
            _ui.value = _ui.value.copy(rxOffsetCwHz = hz)
        } else {
            _ui.value.selected?.let { satConfigStore.saveRxOffsetVoice(it.catalogNumber, hz) }
            _ui.value = _ui.value.copy(rxOffsetVoiceHz = hz)
        }
    }
    fun nudgeRxOffset(deltaHz: Long) =
        setRxOffset((if (_ui.value.opMode == "CW") _ui.value.rxOffsetCwHz else _ui.value.rxOffsetVoiceHz) + deltaHz)

    /** The active RX offset for the current op-mode. */
    private fun activeRxOffset(): Long =
        if (_ui.value.opMode == "CW") _ui.value.rxOffsetCwHz else _ui.value.rxOffsetVoiceHz

    /** Manual NOR/REV override for linear transponders (null = use SatNOGS data). */
    fun setInvertOverride(value: Boolean?) {
        _ui.value = _ui.value.copy(invertOverride = value)
    }
    /** Effective inversion: manual override if set, else the transmitter's flag. */
    private fun effectiveInvert(t: fr.f4ioz.satcombo.data.Transmitter): Boolean =
        _ui.value.invertOverride ?: t.invert

    /**
     * The knob moves the VFO, and **the uplink follows**.
     *
     * It used to move only the RX offset: you drifted away from the other
     * station on receive while transmitting in the same place. On a linear
     * transponder the natural gesture is a satellite rig's VFO, where moving
     * RX moves TX by the transponder law. So act on the same value as the
     * on-screen cursor, from which the uplink is already derived — one path,
     * no second uplink computation to drift apart.
     *
     * Off a transponder (beacon, FM relay) there is no uplink to follow and
     * the RX offset stays the right control.
     */
    private fun moletteDeplaceVfo(deltaHz: Long) {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        if (t?.isTransponder != true) {
            nudgeRxOffset(deltaHz)
            return
        }
        val actuel = _ui.value.rxRestHz ?: t.downlinkLowHz ?: return
        // On QO-100 the knob crosses transponders like the ruler: stopping at
        // the edge would force a trip to a list mid-sweep.
        if (_ui.value.selected?.catalogNumber == fr.f4ioz.satcombo.domain.Qo100.NORAD)
            allerLibre(actuel + deltaHz)
        else setRxRest(actuel + deltaHz)
    }

    /** Sets the chosen RX (downlink) REST frequency, clamped to the passband. */
    fun setRxRest(hz: Long) {
        // **The transponder follows the finger.** When the target falls in
        // another transponder, switch to it — as on a rig: you turn and land
        // where you land. Otherwise touching a distant segment did nothing.
        val tous = activeTransmitters()
        val ailleurs = tous.indexOfFirst { autre ->
            val b = autre.downlinkLowHz
            val h = autre.downlinkHighHz
            b != null && h != null && hz in minOf(b, h)..maxOf(b, h)
        }
        if (ailleurs >= 0 && ailleurs != _ui.value.selectedTxIndex) {
            _ui.value = _ui.value.copy(selectedTxIndex = ailleurs)
        }
        val t = tous.getOrNull(_ui.value.selectedTxIndex) ?: return
        val lo = t.downlinkLowHz ?: return
        val hi = t.downlinkHighHz ?: lo
        // Touching the cursor takes control back: the "agenda frequency" note
        // goes, it would now be false.
        _ui.value = _ui.value.copy(
            rxRestHz = hz.coerceIn(minOf(lo, hi), maxOf(lo, hi)), rxFromAgendaHz = null)
    }

    /**
     * What the SDR card's vernier moves.
     *
     * There were two tunings ignoring each other: the satellite VFO (the
     * channel in the transponder, which drives the RX/TX lines and the rig)
     * and the dongle's tuning. The vernier moved only the latter, and the
     * operator no longer knew his real frequency. Now, on a transponder, the
     * vernier moves the channel: cursor and RX line follow, TX mirrors it
     * (reversed on an inverting transponder), and the dongle retunes behind.
     */
    fun vernierPas(deltaHz: Long) {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val bas = t?.downlinkLowHz
        val haut = t?.downlinkHighHz
        val cible = fr.f4ioz.satcombo.domain.AccordFin.cibleDuVernier(
            t?.isTransponder == true, bas, haut)
        if (cible == fr.f4ioz.satcombo.domain.AccordFin.Cible.CANAL && bas != null && haut != null) {
            val actuel = _ui.value.rxRestHz ?: centreRx(t!!) ?: bas
            setRxRest(fr.f4ioz.satcombo.domain.AccordFin.nouveauCanal(
                actuel, deltaHz, bas, haut))
        } else {
            sdrPasFin(deltaHz)
        }
    }

    fun passKey(p: fr.f4ioz.satcombo.data.SatPass) = "${p.catalogNumber}@${p.aosEpochMs}"

    // ------------------------------------------------- pass Doppler

    private var dopplerPassKey: String = ""
    private var dopplerPassTable = fr.f4ioz.satcombo.domain.DopplerPass.Table()

    /**
     * Doppler table for the shown pass, computed on demand. No "satellite above
     * horizon" guard here — that was the bug: range rate exists for a future
     * pass too, and before the pass is when you want to know where to set the
     * VFO. Cached by (satellite, AOS, RX, TX): recomposing every second must
     * not rerun the hundred SGP4 propagations of the two sweeps.
     */
    fun dopplerPassRows(passAosMs: Long? = null): fr.f4ioz.satcombo.domain.DopplerPass.Table {
        val sat = _ui.value.selected ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val obs = _ui.value.observer ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val now = System.currentTimeMillis()
        val want = passAosMs ?: _ui.value.focusedPassAos
        val pass = (want?.let { f -> _ui.value.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: _ui.value.passes.firstOrNull { it.losEpochMs > now })
            ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()

        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val rxRest = _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: t?.downlinkLowHz
            ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val txRest = when {
            t == null -> null
            t.isTransponder && t.downlinkLowHz != null && t.uplinkLowHz != null ->
                Doppler.transponderUplinkRest(
                    rxRest, t.downlinkLowHz!!, t.downlinkHighHz ?: t.downlinkLowHz!!,
                    t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
            else -> t.uplinkLowHz
        }

        val key = "${sat.catalogNumber}@${pass.aosEpochMs}/$rxRest/$txRest"
        if (key == dopplerPassKey) return dopplerPassTable
        val table = runCatching {
            fr.f4ioz.satcombo.domain.DopplerPass.build(
                pass.aosEpochMs, pass.losEpochMs, rxRest, txRest
            ) { from, to, step -> predictor.samplePositions(sat, obs, from, to, step) }
        }.getOrDefault(fr.f4ioz.satcombo.domain.DopplerPass.Table())
        dopplerPassKey = key
        dopplerPassTable = table
        return table
    }

    // ---- CAT (IC-9700 CI-V over USB-C) ----

    fun catRequestPermission() =
        if (isPairRig) ft817.requestPermissions() else cat.requestPermission()

    // ---- Dual FT-817 configuration ----

    /** Refresh the list of visible USB-serial adapters (for the RX/TX picker). */
    fun refreshUsbDevices() {
        _ui.value = _ui.value.copy(usbDevices = ft817.listDevices())
        litFrequencesUsb()
    }

    /** Ask USB permission for every adapter, then refresh (serials become readable). */
    fun ft817RequestPermissions() {
        ft817.requestPermissions()
        viewModelScope.launch { delay(1500); refreshUsbDevices() }
    }

    /**
     * Queries each adapter and shows the frequency read next to it. Two
     * identical PL2303 cables differ by no label, but the rigs behind them are
     * on different bands: "145.866" on one line and "435.108" on the other
     * settles it. In the background, one line at a time: each read costs up to
     * 600 ms, and the list must show at once.
     */
    fun litFrequencesUsb() {
        if (!isPairRig) return
        viewModelScope.launch {
            val baud = _ui.value.ft817Baud
            _ui.value.usbDevices.forEach { d ->
                if (!d.hasPermission) return@forEach
                val hz = runCatching { ft817.sonde(d.cle, baud, _ui.value.catUi.ic705Baud) }.getOrNull()
                _ui.value = _ui.value.copy(usbDevices = _ui.value.usbDevices.map {
                    if (it.cle == d.cle) it.copy(freqLueHz = hz, sonde = true) else it
                })
            }
        }
    }

    /**
     * On connect, an assignment is trusted only if based on a serial number.
     * An identity built on the USB slot holds only until something is
     * unplugged. Otherwise query the rigs and correct only when the answer is
     * **certain** (one in the downlink band, the other in the uplink band). In
     * doubt keep what was stored: a guessed correction is worse than the error.
     */
    private suspend fun verifieRolesFt817SiSansNumero() {
        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        if (cleRx.isNullOrBlank() || cleTx.isNullOrBlank()) return
        val sansNumero = fr.f4ioz.satcombo.cat.IdentiteUsb.sansNumeroDeSerie(cleRx) ||
            fr.f4ioz.satcombo.cat.IdentiteUsb.sansNumeroDeSerie(cleTx)
        if (!sansNumero) return

        val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        val descente = _ui.value.rxRestHz ?: t0.downlinkLowHz ?: return
        val montee = t0.uplinkLowHz ?: return

        val baud = _ui.value.ft817Baud
        val sondes = ft817.listDevices().filter { it.hasPermission }.map { d ->
            fr.f4ioz.satcombo.cat.IdentiteUsb.Sonde(
                d.cle, runCatching { ft817.sonde(d.cle, baud, _ui.value.catUi.ic705Baud) }.getOrNull())
        }
        if (sondes.size < 2) return

        val a = fr.f4ioz.satcombo.cat.IdentiteUsb.attribue(sondes, descente, montee)
        if (!a.certaine) return
        if (a.rx != null && a.tx != null && (a.rx != cleRx || a.tx != cleTx)) {
            setFt817Role(a.rx, "RX")
            setFt817Role(a.tx, "TX")
        }
    }

    /**
     * Checks roles **once the links are open**, and corrects.
     *
     * `verifieRolesFt817SiSansNumero` silently gives up when no satellite is
     * chosen, transponders are not loaded, or an adapter does not answer; we
     * then connect with the slot-based assignment, which changes between
     * plug-ins for two serial-less PL2303. Symptoms are scattered and hard to
     * name: downlink written to the TX rig, TX border polling the rig that
     * never transmits, readback showing two right numbers swapped.
     *
     * Here each open link is asked its frequency over the existing connection
     * (no port reopened, no conflict), and roles are swapped only when the
     * answer is **clear**: one in the uplink band, the other in the downlink.
     */
    private suspend fun verifieRolesOuverts() {
        if (!isPairRig || isTxOnlyRig) return
        if (!ft817.rx.isOpen || !ft817.tx.isOpen) return
        val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        val descente = _ui.value.rxRestHz ?: t0.downlinkLowHz ?: return
        val montee = t0.uplinkLowHz ?: return
        // When uplink and downlink share a band, no reading can tell them apart.
        if (kotlin.math.abs(descente - montee) < 4_000_000L) return

        val fRx = runCatching { ft817.rx.readFrequency() }.getOrNull() ?: return
        val fTx = runCatching { ft817.tx.readFrequency() }.getOrNull() ?: return
        fun pres(f: Long, cible: Long) = kotlin.math.abs(f - cible) <= 2_000_000L

        val enversRx = pres(fRx, montee) && !pres(fRx, descente)
        val enversTx = pres(fTx, descente) && !pres(fTx, montee)
        if (!enversRx || !enversTx) return

        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        if (cleRx.isNullOrBlank() || cleTx.isNullOrBlank()) return
        // Set both roles **without reconnecting**, then reopen once, here.
        poseRoleFt817(cleTx, "RX")
        poseRoleFt817(cleRx, "TX")
        _ui.value = _ui.value.copy(catStatus = t("ft817_roles_swapped"))
        ft817.close()
        ft817.open(_ui.value.ft817RxSerial, _ui.value.ft817TxSerial, _ui.value.ft817Baud,
            _ui.value.catUi.ic705Baud)
        // The links were closed and reopened under the PTT poll, which was
        // querying a dead port: restarting it is part of reopening.
        surveilleEmission()
    }

    /**
     * Asks the rigs themselves which one is RX and which TX. Two identical
     * serial-less cables are indistinguishable and may swap USB positions, but
     * the rigs are on different bands and their answer settles it.
     */
    fun detecteFt817Roles() {
        viewModelScope.launch { detecteFt817RolesEtAttend() }
    }

    private suspend fun detecteFt817RolesEtAttend() {
        run {
            _ui.value = _ui.value.copy(catStatus = t("ft817_probing"))
            ft817.requestPermissions()
            delay(1200)
            val adaptateurs = ft817.listDevices().filter { it.hasPermission }
            if (adaptateurs.isEmpty()) {
                _ui.value = _ui.value.copy(catStatus = t("ft817_no_adapters"))
                return
            }
            val baud = _ui.value.ft817Baud
            val sondes = adaptateurs.map { d ->
                fr.f4ioz.satcombo.cat.IdentiteUsb.Sonde(
                    d.cle, runCatching { ft817.sonde(d.cle, baud, _ui.value.catUi.ic705Baud) }.getOrNull())
            }
            val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val a = fr.f4ioz.satcombo.cat.IdentiteUsb.attribue(
                sondes,
                descenteHz = _ui.value.rxRestHz ?: t0?.downlinkLowHz,
                monteeHz = t0?.uplinkLowHz)

            a.rx?.let { setFt817Role(it, "RX") }
            a.tx?.let { setFt817Role(it, "TX") }

            val lues = sondes.joinToString("  ") {
                if (it.freqHz == null) "—" else "%.4f".format(it.freqHz / 1_000_000.0)
            }
            _ui.value = _ui.value.copy(
                catStatus = if (a.certaine) tf("ft817_probe_ok", lues)
                else tf("ft817_probe_unsure", lues),
                usbDevices = adaptateurs)
        }
    }

    /**
     * Writes the assignment without touching the link.
     *
     * Setting and reopening used to be one gesture. Assigning both roles takes
     * **two** calls, so two concurrent reconnects ran (often a third behind).
     * The links ended open and CAT looked healthy, but the PTT poll — started
     * by opening, stopped when `catConnected` drops — was killed by a
     * reconnect that started earlier and finished later, and nothing restarted
     * it. The TX border stayed dark while everything else worked.
     */
    private fun poseRoleFt817(serial: String, role: String) {
        if (role == "RX") {
            settings.ft817RxSerial = serial
            if (settings.ft817TxSerial == serial) settings.ft817TxSerial = ""
        } else {
            settings.ft817TxSerial = serial
            if (settings.ft817RxSerial == serial) settings.ft817RxSerial = ""
        }
        _ui.value = _ui.value.copy(
            ft817RxSerial = settings.ft817RxSerial, ft817TxSerial = settings.ft817TxSerial)
    }

    /** Assigns an adapter (by its key) to the RX or TX rig, and reopens if open. */
    fun setFt817Role(serial: String, role: String) {
        poseRoleFt817(serial, role)
        rouvreSiOuvert()
    }

    /**
     * Reopens the link when the assignment changes under it. Enabling CAT
     * **then** detecting the rigs changed nothing: the ports were already open
     * on the old (often empty) assignment, and only toggling CAT fixed it — a
     * pass was lost to this. Resolve the contradiction at once rather than let
     * the operator find it mid-pass.
     */
    private fun rouvreSiOuvert() {
        if (!_ui.value.catEnabled) return
        // **One reopen at a time.** Concurrent reconnects interleave: one's
        // close lands in the other's open, and the PTT poll started by the
        // first is stopped by the second. Cancel the running one.
        rouvreJob?.cancel()
        rouvreJob = viewModelScope.launch {
            runCatching {
                disconnectCat()
                delay(200)
                ouvreCat()
            }
        }
    }

    /**
     * One tap for everything needed to operate. The right sequence was four
     * steps in an order that mattered and was written nowhere (list, grant,
     * detect roles, connect); three minutes before AOS nobody should need it.
     */
    fun prepareFt817() {
        viewModelScope.launch {
            runCatching {
                // 1. List without reading frequencies: without permission the
                //    read would fail and leave the lines pending.
                _ui.value = _ui.value.copy(usbDevices = ft817.listDevices())

                // 2. Ask permissions and **wait for the answer**. A fixed
                //    400 ms made this fail half the time (a slow finger on the
                //    system dialog). A delay is no substitute for a condition:
                //    wait until adapters are granted, up to ten seconds.
                ft817.requestPermissions()
                val autorises = attendAutorisationsUsb(10_000)
                if (autorises.isEmpty()) {
                    _ui.value = _ui.value.copy(catStatus = t("ft817_no_adapters"))
                    return@runCatching
                }

                // 3. Assign roles by querying the rigs: by band for two
                //    FT-817s, by protocol for an FT-817 with an IC-705.
                configurePaire()
                if (isPaireMixte) attribuePaireMixte() else detecteFt817RolesEtAttend()

                // 4. Connect and **wait until done**. `setCatEnabled` and
                //    `rouvreSiOuvert` return at once, so step 5 probed the
                //    adapters while they were being opened: two opens of one
                //    USB device, and the loser says nothing.
                if (!_ui.value.catEnabled) _ui.value = _ui.value.copy(catEnabled = true)
                else { disconnectCat(); delay(200) }
                ouvreCat()

                // 5. Check through the link just opened, not by reopening ports.
                verifieFt817Ouvert()
            }.onFailure {
                _ui.value = _ui.value.copy(
                    catStatus = "préparation interrompue : " +
                        (it.message ?: it.javaClass.simpleName))
            }
        }
    }

    /**
     * Waits up to [maxMs] for USB adapters to be granted. Returns the granted
     * ones — empty on timeout (refused or untouched).
     */
    private suspend fun attendAutorisationsUsb(maxMs: Long): List<fr.f4ioz.satcombo.cat.UsbSerialInfo> {
        val fin = System.currentTimeMillis() + maxMs
        var vus = ft817.listDevices()
        while (System.currentTimeMillis() < fin) {
            val ok = vus.filter { it.hasPermission }
            if (ok.isNotEmpty() && ok.size == vus.size) {
                _ui.value = _ui.value.copy(usbDevices = vus)
                return ok
            }
            delay(250)
            vus = ft817.listDevices()
            _ui.value = _ui.value.copy(usbDevices = vus)
        }
        return vus.filter { it.hasPermission }
    }

    /**
     * Reports whether preparation succeeded, and on what. It used to end
     * silently; a preparation must end with a verdict.
     */
    private suspend fun verifieFt817Ouvert() {
        if (!_ui.value.catConnected) {
            _ui.value = _ui.value.copy(catStatus = t("open_failed_usb"))
            return
        }
        val rx = runCatching { if (ft817.rx.isOpen) ft817.rx.readFrequency() else null }.getOrNull()
        val tx = runCatching { if (ft817.tx.isOpen) ft817.tx.readFrequency() else null }.getOrNull()
        fun mhz(hz: Long?) = if (hz == null) "—" else "%.4f".format(hz / 1_000_000.0)
        // **Fill the adapter lines too**: they stayed on "querying…" although
        // the frequencies were just read, which suggests nothing worked. Fill
        // from the open links, without reopening ports (`litFrequencesUsb`
        // reopening them was the open conflict fixed in 19.18).
        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        val garnis = _ui.value.usbDevices.map { d ->
            when (d.cle) {
                cleRx -> d.copy(freqLueHz = rx, sonde = true)
                cleTx -> d.copy(freqLueHz = tx, sonde = true)
                else -> d
            }
        }
        _ui.value = _ui.value.copy(
            usbDevices = garnis,
            catUi = _ui.value.catUi.copy(
                veilleRxHz = rx, veilleTxHz = tx, veilleVivante = rx != null || tx != null),
            catStatus = if (rx == null && tx == null) t("ft817_no_reply")
                        else "RX " + mhz(rx) + "  ·  TX " + mhz(tx))
    }

    /** Swap the RX and TX assignments. */
    fun swapFt817Roles() {
        val rx = settings.ft817RxSerial
        settings.ft817RxSerial = settings.ft817TxSerial
        settings.ft817TxSerial = rx
        _ui.value = _ui.value.copy(
            ft817RxSerial = settings.ft817RxSerial, ft817TxSerial = settings.ft817TxSerial)
    }

    fun setFt817Baud(baud: Int) {
        settings.ft817Baud = baud
        _ui.value = _ui.value.copy(ft817Baud = baud)
    }

    /** IC-705 CI-V speed, apart from the FT-817's; reopens the pair if open. */
    fun setIc705Baud(baud: Int) {
        settings.ic705Baud = baud
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(ic705Baud = baud))
        rouvreSiOuvert()
    }

    fun setCatEnabled(on: Boolean) {
        _ui.value = _ui.value.copy(catEnabled = on)
        if (on) connectCat() else { cat.close(); _ui.value = _ui.value.copy(catConnected = false, catStatus = t("cat_disconnected")) }
    }

    fun setRigModel(model: String) {
        settings.rigModel = model
        _ui.value = _ui.value.copy(rigModel = model)
        configurePaire()
        // Default CI-V address per Icom model.
        val addr = when (model) {
            "IC910" -> 0x60
            "IC9100" -> 0x7C
            else -> 0xA2  // IC9700
        }
        settings.civAddress = addr
        cat.radioAddr = addr
        _ui.value = _ui.value.copy(rigModel = model, civAddress = addr)
    }

    fun setCivAddress(addr: Int) {
        settings.civAddress = addr
        cat.radioAddr = addr
        _ui.value = _ui.value.copy(civAddress = addr)
    }

    fun setCivBaud(baud: Int) {
        settings.civBaud = baud
        _ui.value = _ui.value.copy(civBaud = baud)
    }

    /** Which plugged-in adapter is the rig. */
    fun setCivUsbIndex(v: Int) {
        settings.civUsbIndex = v
        _ui.value = _ui.value.copy(civUsbIndex = settings.civUsbIndex)
    }

    /** Scan neighbouring ports, or stick strictly to the chosen one. */
    fun setCivUsbAuto(on: Boolean) {
        settings.civUsbAuto = on
        _ui.value = _ui.value.copy(civUsbAuto = on)
    }

    /** Visible USB adapters, so the index can be chosen by sight. */
    fun refreshCatDevices() {
        _ui.value = _ui.value.copy(catDevices = runCatching { cat.availableDeviceNames() }
            .getOrDefault(emptyList()))
    }

    fun setCtcssAuto(on: Boolean) {
        settings.ctcssAuto = on
        _ui.value = _ui.value.copy(ctcssAuto = on)
        catArmedFor = null
    }

    /**
     * SO-50 arming: the transponder must be armed with a 74.4 Hz tone (starts a
     * 10-minute timer), then 67.0 Hz is used for the contact. This sends a brief
     * 74.4 Hz pulse on the uplink, then restores the working tone. The operator
     * should hold PTT briefly while this runs (we can't key the rig for them).
     */
    fun armSo50() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) {
                _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch
            }
            _ui.value = _ui.value.copy(catStatus = t("so50_arming"))
            runCatching {
                if (isPairRig) ft817.setCtcss(744)
                else {
                    cat.selectMainSub(true)       // SUB = uplink
                    cat.setToneFreq(744); cat.setToneOn(true)
                }
            }
            kotlinx.coroutines.delay(3000)        // window for the operator to key up
            // Restore the working tone (auto-detected or 67.0).
            val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val work = t?.let { effectiveCtcss(it) }?.takeIf { it > 0 } ?: 670
            runCatching {
                if (isPairRig) ft817.setCtcss(work)
                else {
                    cat.setToneFreq(work); cat.setToneOn(true)
                    cat.selectMainSub(false)
                }
            }
            _ui.value = _ui.value.copy(catStatus = tf("so50_armed", work/10.0))
        }
    }

    fun setCtcssTenthHz(tenth: Int) {
        settings.ctcssTenthHz = tenth
        _ui.value = _ui.value.copy(ctcssTenthHz = tenth)
        catArmedFor = null  // re-arm so the new tone is applied
    }

    /**
     * Doppler tracking on receive. On: the phone tunes the rig once the knob
     * has been idle for the hold time. Off: only TX follows, RX is entirely
     * the operator's.
     */
    fun setCatRxDoppler(on: Boolean) {
        settings.catRxDoppler = on
        rxArbiter.reset()
        _ui.value = _ui.value.copy(catRxDoppler = on,
            catRxDriven = if (on) _ui.value.catRxDriven else false)
    }

    fun setCatTestSendAlways(on: Boolean) {
        settings.catSousHorizon = on
        _ui.value = _ui.value.copy(catTestSendAlways = on)
    }

    /**
     * Plays the start-of-pass sequence against a simulated rig, no radio
     * needed. The in-memory rig refuses what a real one refuses and counts
     * refusals: a healthy sequence produces zero — much stronger than "it did
     * not crash".
     */
    fun runCatBench() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(benchRunning = true, benchOk = false,
                benchReport = "", benchSteps = emptyList())
            val r = runCatching {
                when {
                    // The IC-705 side, receiving or transmitting, has its own run.
                    _ui.value.rigModel == FT817_IC705 -> fr.f4ioz.satcombo.cat.CatBench.runFt817Ic705()
                    _ui.value.rigModel == IC705_FT817 ->
                        fr.f4ioz.satcombo.cat.CatBench.runFt817Ic705(ic705Emet = true)
                    _ui.value.rigModel == fr.f4ioz.satcombo.cat.Postes.IC705_X2 ->
                        fr.f4ioz.satcombo.cat.CatBench.runIc705Pair()
                    _ui.value.rigModel == fr.f4ioz.satcombo.cat.Postes.IC705_TX ->
                        fr.f4ioz.satcombo.cat.CatBench.runFt817Ic705(ic705Emet = true)
                    isPairRig -> fr.f4ioz.satcombo.cat.CatBench.runFt817Pair()
                    else -> fr.f4ioz.satcombo.cat.CatBench.runIc9700()
                }
            }.getOrNull()
            _ui.value = _ui.value.copy(
                benchRunning = false,
                benchOk = r?.ok == true,
                benchReport = r?.summary ?: t("send_failed"),
                benchSteps = r?.steps ?: emptyList())
        }
    }


    fun testCat() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) { _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch }
            val result = if (isPairRig) ft817.testLink() else cat.testLink()
            _ui.value = _ui.value.copy(catStatus = result)
        }
    }

    /** Send a fixed test frequency now (downlink VFO), ignoring horizon. */
    fun catSendTestFreq() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) { _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch }
            val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val hz = _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: 145_900_000L
            val ok = runCatching {
                if (isPairRig) ft817.rx.setFrequency(hz)
                else { cat.selectVfo(false); cat.setFrequency(hz) }
            }.isSuccess
            _ui.value = _ui.value.copy(catStatus = if (ok) tf("test_freq_sent", "${hz/1000}.${(hz%1000)}") else fr.f4ioz.satcombo.i18n.t("send_failed"))
        }
    }

    /** The current simulated rig, if any, kept so it can be closed cleanly. */
    private var civSim: fr.f4ioz.satcombo.cat.Ic9700Sim? = null
    private var ft817Sims: Pair<fr.f4ioz.satcombo.cat.SerialLink,
            fr.f4ioz.satcombo.cat.SerialLink>? = null

    fun setCatSimulated(on: Boolean) {
        settings.catSimulated = on
        _ui.value = _ui.value.copy(catSimulated = on)
        if (_ui.value.catConnected) disconnectCat()
    }

    fun setCatMonitor(on: Boolean) {
        settings.catMonitor = on
        fr.f4ioz.satcombo.cat.CatJournal.enabled = on
        if (!on) fr.f4ioz.satcombo.cat.CatJournal.clear()
        _ui.value = _ui.value.copy(catMonitor = on)
    }

    /**
     * Opens the link on an in-memory rig. The frame monitor stacks on top, so
     * the frames are exactly those of a real cable: a protocol-learning tool
     * as much as a test bench.
     */
    private fun connectSimulated() {
        if (isThd72) {
            configurePaire()
            ft817.rx.attach(fr.f4ioz.satcombo.cat.Thd72Sim())  // one line for both bands
            ft817.rx.pacingMs = 0
        } else if (isPairRig) {
            configurePaire()
            // Each side gets the simulator of the rig it stands for.
            val r: fr.f4ioz.satcombo.cat.SerialLink =
                if (fr.f4ioz.satcombo.cat.Postes.rxIc705(_ui.value.rigModel)) fr.f4ioz.satcombo.cat.Ic705Sim()
                else fr.f4ioz.satcombo.cat.Ft817Sim()
            val x: fr.f4ioz.satcombo.cat.SerialLink =
                if (fr.f4ioz.satcombo.cat.Postes.txIc705(_ui.value.rigModel)) fr.f4ioz.satcombo.cat.Ic705Sim()
                else fr.f4ioz.satcombo.cat.Ft817Sim()
            ft817Sims = r to x
            ft817.rx.attach(r)
            ft817.tx.attach(x)
            ft817.rx.pacingMs = 0; ft817.tx.pacingMs = 0
        } else {
            val sim = fr.f4ioz.satcombo.cat.Ic9700Sim(radioAddr = _ui.value.civAddress)
            civSim = sim
            cat.radioAddr = _ui.value.civAddress
            cat.attach(sim)
            cat.pacingMs = 0
        }
        _ui.value = _ui.value.copy(catConnected = true, catStatus = t("cat_sim_on"))
        surveilleEmission()
        startCatLoop()
    }

    /**
     * Connects a TH-D72: finds its adapter (the one answering "FV 0"), opens it
     * once for both bands, and makes the uplink band the one PTT keys.
     */
    private suspend fun ouvreThd72() {
        configurePaire()
        ft817.requestPermissions()
        val app = getApplication<android.app.Application>()
        val cle = ft817.listDevices().filter { it.hasPermission }.map { it.cle }.firstOrNull { c ->
            val essai = fr.f4ioz.satcombo.cat.Thd72Lien(app)
            val oui = essai.open(c, 9600) && essai.estUnThd72()
            essai.close()
            oui
        }
        if (cle == null) {
            _ui.value = _ui.value.copy(catConnected = false, catStatus = t("thd72_introuvable"))
            return
        }
        val (ok, _) = ft817.open(cle, cle, 9600)
        val lien = ft817.lienThd72
        if (!ok || lien == null) {
            _ui.value = _ui.value.copy(catConnected = false, catStatus = t("open_failed_usb"))
            return
        }
        lien.choisitBande(settings.thd72BandeTx)
        _ui.value = _ui.value.copy(catConnected = true,
            catStatus = tf("thd72_connecte", if (settings.thd72BandeTx == 0) "A" else "B",
                if (settings.thd72BandeTx == 0) "B" else "A"))
        surveilleEmission(); startCatLoop()
        thd72Lire()
    }

    // ------------------------------------------------------- TH-D72 panel

    /** What the TH-D72 panel shows (not in `UiState`): each band's frequency and power, PTT band. */
    data class Thd72Etat(
        val hz: List<Long?> = listOf(null, null),
        val puissance: List<Int?> = listOf(null, null),
        val bandePtt: Int? = null,
        val message: String = "",
    )
    val thd72Etat = MutableStateFlow(Thd72Etat())

    fun thd72BandeTx(): Int = settings.thd72BandeTx

    /** Transmit band: saved, applied at once when connected (roles swap, PTT follows). */
    fun setThd72BandeTx(b: Int) {
        settings.thd72BandeTx = b
        if (!isThd72) return
        viewModelScope.launch {
            if (_ui.value.catConnected) {
                // Bands are sides of the pair: reconnect with the new roles.
                disconnectCat()
                connectCat()
            } else configurePaire()
        }
    }

    fun thd72Lire() {
        val lien = ft817.lienThd72 ?: return
        viewModelScope.launch {
            val hz = (0..1).map { b -> lien.etatBande(b)?.let { fr.f4ioz.satcombo.cat.Thd72.frequence(it) } }
            val p = (0..1).map { b -> lien.puissance(b) }
            thd72Etat.value = Thd72Etat(hz, p, lien.bandeCourante())
        }
    }

    /** Sets band [b]'s frequency (put on its step); refused ones are said. */
    fun thd72Frequence(b: Int, hz: Long) {
        val lien = ft817.lienThd72 ?: return
        viewModelScope.launch {
            val ok = fr.f4ioz.satcombo.cat.Thd72Bande(lien, b).setFrequency(hz)
            thd72Lire()
            if (!ok) thd72Etat.value = thd72Etat.value.copy(message = t("thd72_refuse"))
        }
    }

    /** One step up or down on band [b]. */
    fun thd72Pas(b: Int, sens: Int) {
        val lien = ft817.lienThd72 ?: return
        viewModelScope.launch {
            val c = lien.etatBande(b) ?: return@launch
            thd72Frequence(b, fr.f4ioz.satcombo.cat.Thd72.frequence(c) + sens * fr.f4ioz.satcombo.cat.Thd72.pas(c))
        }
    }

    fun thd72Puissance(b: Int, p: Int) {
        val lien = ft817.lienThd72 ?: return
        viewModelScope.launch { lien.reglePuissance(b, p); thd72Lire() }
    }

    private var sondeTxJob: Job? = null

    /** Consecutive "transmitting" reads; two are needed to believe it. */
    private var confirmationsTx = 0

    private var veilleCatJob: Job? = null

    private var rouvreJob: Job? = null

    /**
     * CAT settings link readback (see [CatUi.veilleRxHz]). Runs only while the
     * CAT section is shown: the serial port belongs to Doppler during a pass.
     */
    fun veilleCat(actif: Boolean) {
        veilleCatJob?.cancel()
        if (!actif) {
            if (_ui.value.catUi.veilleVivante)
                _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(veilleVivante = false))
            return
        }
        veilleCatJob = viewModelScope.launch {
            while (_ui.value.catConnected) {
                val rx = runCatching {
                    if (isPairRig) { if (ft817.rx.isOpen) ft817.rx.readFrequency() else null }
                    else cat.readFrequency()
                }.getOrNull()
                val tx = runCatching {
                    if (isPairRig) { if (ft817.tx.isOpen) ft817.tx.readFrequency() else null }
                    else null
                }.getOrNull()
                // The per-adapter lines are filled from the same read: data
                // already at hand must not require a tap.
                val cleRx = _ui.value.ft817RxSerial
                val cleTx = _ui.value.ft817TxSerial
                _ui.value = _ui.value.copy(
                    catUi = _ui.value.catUi.copy(
                        veilleRxHz = rx, veilleTxHz = tx,
                        veilleVivante = rx != null || tx != null),
                    usbDevices = _ui.value.usbDevices.map { d ->
                        when (d.cle) {
                            cleRx -> if (rx != null) d.copy(freqLueHz = rx, sonde = true) else d
                            cleTx -> if (tx != null) d.copy(freqLueHz = tx, sonde = true) else d
                            else -> d
                        }
                    })
                delay(500)
            }
            _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(veilleVivante = false))
        }
    }

    /**
     * Watches the rig's transmit state. The operator keys up with VOX: the app
     * commands nothing, it **observes**. Twice a second is enough for the
     * border to show on the first word without cluttering the serial link
     * while Doppler works.
     */
    private fun surveilleEmission() {
        sondeTxJob?.cancel()
        confirmationsTx = 0
        // **The loop waits instead of dying.** Its entry condition read
        // `catConnected`, and it was launched from an `.also` **while computing**
        // the state that would set `catConnected` true. `viewModelScope` uses
        // `Main.immediate`: called from the main thread the coroutine starts
        // inline, read the old (false) value and exited before its first round.
        // It depended on the calling thread, hence intermittent: ordinary
        // connect worked, reopening after a role change failed. Callers are
        // fixed, but the real remedy is a loop that **waits** for the link and
        // survives any reconnect, whoever restarts it.
        sondeTxJob = viewModelScope.launch {
            while (true) {
                if (!_ui.value.catUi.liseret) {
                    if (_ui.value.catUi.enEmission)
                        _ui.value = _ui.value.copy(
                            catUi = _ui.value.catUi.copy(enEmission = false))
                    confirmationsTx = 0
                    delay(500)
                    continue
                }
                if (!_ui.value.catConnected) {
                    // No link: assert nothing, ask nothing.
                    if (_ui.value.catUi.enEmission)
                        _ui.value = _ui.value.copy(
                            catUi = _ui.value.catUi.copy(enEmission = false))
                    confirmationsTx = 0
                    delay(500)
                    continue
                }
                // **Query the TX rig, or nobody.** Falling back to the RX rig
                // seemed prudent, but an RX rig never transmits: it steadily
                // answers "receive" and the border never lights. The worst
                // state — the screen asserts something false instead of
                // admitting it does not know, and the operator trusts its
                // absence. A closed TX rig is common (cable out, roles not set,
                // second adapter missing); the honest answer is to say so.
                val txOuvert = if (isPairRig) ft817.tx.isOpen else cat.isOpen
                val r = runCatching {
                    if (!txOuvert) null
                    else if (isPairRig) ft817.tx.isTransmitting()
                    else cat.isTransmitting()
                }
                val tx = r.getOrNull()
                // The raw byte comes with the verdict: without it "receive" and
                // "no reply" look alike though they are unrelated faults.
                val brut = if (isPairRig) ft817.tx.dernierEtatTx else null
                val hex = brut?.let { " · 0x%02X".format(it) } ?: ""
                val diag = when {
                    !txOuvert -> "poste d'émission non ouvert"
                    r.isFailure -> "erreur " + (r.exceptionOrNull()?.javaClass?.simpleName ?: "")
                    tx == null -> "pas de réponse du poste" + hex
                    tx -> "émission" + hex
                    else -> "réception" + hex
                }
                // No reading: the border goes off, assert nothing.
                if (tx == null && _ui.value.catUi.enEmission) {
                    _ui.value = _ui.value.copy(
                        catUi = _ui.value.catUi.copy(enEmission = false))
                }
                if (diag != _ui.value.catUi.txDiag) {
                    _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txDiag = diag))
                }
                // **Two reads to light, one to clear.** A guard, not the fix
                // (flicker came from an ack left on the wire), but a stray byte
                // through cable, hub and busy rig stays possible, and a border
                // lit wrongly for half a second teaches the operator to ignore
                // it. Lighting wrongly ruins the signal; clearing half a round
                // late costs nothing.
                // **No reply is not a denial.** A missing read used to reset
                // the counter, i.e. count as "receive". The rig answers worst
                // while transmitting (one reply in two lost), so alternating
                // "transmit"/silence never reached two in a row and the border
                // never lit. Silence leaves the counter alone; only a clear
                // "receive" resets it.
                when (tx) {
                    true -> confirmationsTx++
                    false -> confirmationsTx = 0
                    null -> Unit
                }
                val allume = if (tx == null) _ui.value.catUi.enEmission
                             else confirmationsTx >= 2
                if (allume != _ui.value.catUi.enEmission) {
                    _ui.value = _ui.value.copy(
                        catUi = _ui.value.catUi.copy(enEmission = allume))
                }
                kotlinx.coroutines.delay(_ui.value.catUi.sondeMs.toLong())
            }
            // Never exits: only a restart cancels it, and the next loop clears
            // `enEmission` itself when there is no link or no reply.
        }
    }

    fun connectCat() { viewModelScope.launch { ouvreCat() } }

    /**
     * Opening, awaitable. `connectCat` returns at once, so one-tap preparation
     * bet on a 300 ms delay between "connect" and "read frequencies", and both
     * opened the same USB adapters simultaneously. Separating the body from
     * its launch lets callers wait for the end instead of guessing it.
     */
    private suspend fun ouvreCat() {
        run {
            // The frame monitor must be armed BEFORE opening: that is when the
            // driver decides whether to wrap itself in it.
            fr.f4ioz.satcombo.cat.CatJournal.enabled = _ui.value.catMonitor
            if (_ui.value.catSimulated) { connectSimulated(); return@run }
            if (isThd72) { ouvreThd72(); return@run }
            if (isPairRig) {
                configurePaire()
                if (isPaireMixte) {
                    ft817.requestPermissions()
                    attribuePaireMixte()
                }
                // Dual FT-817: open both adapters by their remembered FTDI serials.
                if (_ui.value.ft817RxSerial.isBlank() && _ui.value.ft817TxSerial.isBlank()) {
                    _ui.value = _ui.value.copy(catConnected = false, catStatus = t("ft817_assign_first"))
                    return@run
                }
                ft817.requestPermissions()
                // Check roles BEFORE opening when cables have no serial number:
                // two identical PL2303 differ only by USB slot, which **changes
                // between plug-ins**, so yesterday's assignment may name the
                // other cable today. Don't guess better; ask each rig its
                // frequency, and its answer gives its role.
                // A mixed pair was assigned by protocol just above.
                if (!isPaireMixte) verifieRolesFt817SiSansNumero()

                val (rxOk, txOk) = if (isTxOnlyRig)
                    // Single cable: open TX only; the one adapter present will
                    // do without assignment.
                    ft817.open("", _ui.value.ft817TxSerial.ifBlank {
                        ft817.listDevices().firstOrNull { it.hasPermission }?.cle.orEmpty()
                    }, _ui.value.ft817Baud, _ui.value.catUi.ic705Baud)
                else ft817.open(
                    _ui.value.ft817RxSerial, _ui.value.ft817TxSerial, _ui.value.ft817Baud,
                    _ui.value.catUi.ic705Baud)
                        .also { (r, t) -> if (r && t && !isPaireMixte) verifieRolesOuverts() }
                val ok = rxOk || txOk
                _ui.value = _ui.value.copy(catConnected = ok,
                    catStatus = if (ok) tf("ft817_connected",
                        if (rxOk) "✓" else "✗", if (txOk) "✓" else "✗")
                    else t("open_failed_usb"))
                // After setting `catConnected`, never during: the poll loop
                // reads it on its first round.
                if (ok) { surveilleEmission(); startCatLoop() }
                return@run
            }
            cat.radioAddr = _ui.value.civAddress
            val refs = cat.availablePorts()
            _ui.value = _ui.value.copy(catDevices = refs.map { it.label })
            if (refs.isEmpty()) {
                _ui.value = _ui.value.copy(catConnected = false,
                    catStatus = t("no_usb_serial"),
                    catDiag = listOf(t("cat_err_no_device")))
                return@run
            }
            // Wait for permission, open the designated port, and check the rig
            // answers before claiming success. (It used to request permission
            // without waiting, then open device 0 port 0 whatever the choice.)
            val trace = ArrayList<String>()
            val ordre =
                if (_ui.value.civUsbAuto) fr.f4ioz.satcombo.cat.CatScan.ordre(refs, _ui.value.civUsbIndex)
                else listOf(_ui.value.civUsbIndex.coerceIn(0, refs.size - 1))
            var gagnant = -1
            for (i in ordre) {
                trace.add(tf("cat_diag_try", refs[i].label))
                if (!cat.ensurePermission(i)) { trace.add(t("cat_diag_line_denied")); continue }
                if (!cat.open(i, _ui.value.civBaud)) {
                    trace.add(tf("cat_diag_line_open", cat.lastError)); continue
                }
                val hz = runCatching { cat.readFrequency() }.getOrNull()
                if (hz == null) {
                    // A port always opens; silence is the tell.
                    trace.add(t("cat_diag_line_mute")); cat.close(); continue
                }
                trace.add(tf("cat_diag_line_ok",
                    String.format(java.util.Locale.US, "%.3f", hz / 1_000_000.0)))
                gagnant = i; break
            }
            val ok = gagnant >= 0
            if (ok && gagnant != _ui.value.civUsbIndex) {
                // The port that answered is remembered: next time connection
                // is immediate.
                settings.civUsbIndex = gagnant
                _ui.value = _ui.value.copy(civUsbIndex = settings.civUsbIndex)
            }
            _ui.value = _ui.value.copy(catConnected = ok, catDiag = trace,
                catStatus = if (ok) tf("connected_to", refs[gagnant].label)
                            else t("open_failed_usb"))
            if (ok) { surveilleEmission(); startCatLoop() }
        }
    }

    private var catLoopJob: kotlinx.coroutines.Job? = null

    /**
     * Dedicated CAT loop, faster than the 1 Hz tracking loop (OscarWatch-style).
     * Runs every 100 ms plus tick time: reads the MAIN (downlink) every cycle so the display and
     * VFO-follow stay responsive; writes the uplink (SUB) only when needed, and
     * for linear leaves the dial to the operator until RxArbiter takes over
     * again (so we don't fight the tuning).
     */
    private fun startCatLoop() {
        catLoopJob?.cancel()
        catLoopJob = viewModelScope.launch {
            while (_ui.value.catConnected) {
                runCatching { catTick() }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun disconnectCat() {
        catLoopJob?.cancel(); catLoopJob = null
        cat.close()
        ft817.close()
        cat.pacingMs = 40; ft817.rx.pacingMs = 25; ft817.tx.pacingMs = 25
        civSim = null; ft817Sims = null
        catArmedFor = null
        rxArbiter.reset(); fmArbiter.reset()
        toursDepuisLeMode = 0
        _ui.value = _ui.value.copy(catConnected = false, catStatus = t("cat_disconnected"),
            catRadioDownlinkHz = null, catRadioUplinkHz = null, catRxDriven = false,
            catRadioMode = null, catModeMismatch = false)
    }

    /** True if the chosen transmitter is FM (fixed channels) vs linear transponder. */
    private fun isFmMode(t: fr.f4ioz.satcombo.data.Transmitter): Boolean {
        if (t.isTransponder) return false
        return fr.f4ioz.satcombo.domain.ModeRadio.surFm(t.mode)
    }

    /** Satellite RF layout, OscarWatch-style. */
    private enum class SatLayout { CROSS_BAND, SAME_BAND, BEACON }

    private fun layoutFor(t: fr.f4ioz.satcombo.data.Transmitter): SatLayout {
        val dl = t.downlinkLowHz ?: centreRx(t) ?: 0L
        val ul = t.uplinkLowHz ?: 0L
        return when {
            ul <= 0L -> SatLayout.BEACON
            kotlin.math.abs(dl - ul) > 10_000_000L -> SatLayout.CROSS_BAND
            else -> SatLayout.SAME_BAND   // V/V or U/U, e.g. ISS FM
        }
    }

    /** Map a transmitter mode string to an IC-9700 CI-V mode byte. */
    private fun civModeFor(t: fr.f4ioz.satcombo.data.Transmitter, isUplink: Boolean): Int {
        val m = t.mode?.uppercase() ?: ""
        return when {
            // FM, and the digital modes heard in FM (ISS APRS is "AFSK").
            fr.f4ioz.satcombo.domain.ModeRadio.surFm(m) -> 0x05
            // Linear in CW op-mode: use CW both sides.
            t.isTransponder && _ui.value.opMode == "CW" -> 0x03
            m.contains("CW") -> 0x03
            // Linear SSB: by convention downlink USB; uplink mirror is LSB if inverting.
            t.isTransponder && effectiveInvert(t) -> if (isUplink) 0x00 else 0x01  // LSB up / USB down
            m.contains("LSB") -> 0x00
            else -> 0x01  // USB default
        }
    }

    // ------------------------------------------------------------------
    // The boundary between sky and rig.
    //
    // These are the only places in the CAT loop where a frequency changes
    // domain. Everywhere else — Doppler, inversion, knob arbitration, write
    // thresholds — everything is in satellite frequencies and must stay so:
    // a `lastSentDl` mixing both domains would break the 20 Hz threshold, and
    // the arbiter would take our own command for an operator gesture.
    // ------------------------------------------------------------------

    /**
     * What the rig can do. A frequency outside its bands is not a computation
     * error but a path that does not go through it: on a typical QO-100
     * station the downlink leaves the LNB at 739 MHz for the dongle, and the
     * rig only transmits on 144 or 432. Sending it 10 GHz would just get a
     * silent NAK ten times a second. Hence this guard, right where the
     * frequency becomes a frame.
     */
    private fun atteignableParLePoste(posteHz: Long): Boolean =
        fr.f4ioz.satcombo.cat.BandPlan.band(posteHz) != fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE

    /**
     * Does the downlink reach the rig? Asked before reading it back: otherwise
     * what we read would be the TX frequency, and the arbiter would see an
     * operator gesture on every round.
     */
    private fun descenteAuPoste(dlSat: Long): Boolean =
        !isTxOnlyRig && atteignableParLePoste(posteRx(dlSat))

    /** Writes the downlink/uplink pair, each through its converter. */
    private suspend fun ecrireCouple(dlSat: Long, ulSat: Long, sameBand: Boolean) {
        if (isTxOnlyRig) { ecrireMontee(ulSat, sameBand); return }
        val dl = posteRx(dlSat)
        if (!atteignableParLePoste(dl)) { ecrireMontee(ulSat, sameBand); return }
        val ul = posteTx(ulSat)
        if (!atteignableParLePoste(ul)) {
            // The uplink goes elsewhere: keep only the downlink.
            if (isPairRig) ft817.rx.setFrequency(dl)
            else { cat.readMainFrequency(); cat.setFrequency(dl) }
            return
        }
        if (isPairRig) ft817.setPair(dl, ul)
        else if (sameBand) cat.setSplitPair(dl, ul) else cat.setSatellitePair(dl, ul)
    }

    /** Writes the uplink only — when the operator holds the receive side. */
    private suspend fun ecrireMontee(ulSat: Long, sameBand: Boolean) {
        val ul = posteTx(ulSat)
        if (!atteignableParLePoste(ul)) return
        if (isPairRig) ft817.setUplink(ul)
        else if (sameBand) cat.setSplitUplink(ul) else cat.setUplink(ul)
    }

    /** Reads the downlink back and converts it straight to sky frequency. */
    private suspend fun lireDescente(sameBand: Boolean): Long? =
        runCatching {
            if (isPairRig) ft817.readDownlink()
            else if (sameBand) cat.readVfoAFrequency() else cat.readMainFrequency()
        }.getOrNull()?.let { satDepuisPoste(it) }

    // CAT session state (set up once when SAT mode armed for a transmitter).
    private var catArmedFor: Int? = null      // catnum currently armed
    private var catArmedTxDesc: String? = null
    private var lastSentDl = 0L
    private var lastSentUl = 0L

    /** Arm the radio for the current satellite according to its RF layout. */
    private suspend fun armCatForCurrent() {
        // Doppler hold: arming writes mode, split and tone — that is touching
        // the rig. Return before the "already armed" test so nothing is
        // recorded as done: full arming happens on release.
        if (_ui.value.dopplerHold) return
        val sat = _ui.value.selected ?: return
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        if (catArmedFor == sat.catalogNumber && catArmedTxDesc == t.description) return
        val layout = layoutFor(t)
        if (isPairRig) {
            // Two dedicated rigs: no band/split gymnastics — just modes + tone.
            runCatching {
                ft817.setModes(
                    downlink = ft817ModeFor(t, isUplink = false),
                    uplink = ft817ModeFor(t, isUplink = true))
                val tone = effectiveCtcss(t)
                ft817.setCtcss(if (isFmMode(t) && tone > 0) tone else 0)
            }
            catArmedFor = sat.catalogNumber
            catArmedTxDesc = t.description
            catLayout = layout
            lastSentDl = 0L; lastSentUl = 0L
            rxArbiter.reset(); fmArbiter.reset()
            return
        }
        runCatching {
            when (layout) {
                SatLayout.CROSS_BAND -> {
                    cat.setSatelliteMode(true)
                    cat.setSplitOn(false)
                    // SUB = uplink: mode + CTCSS while selected.
                    cat.selectMainSub(true)
                    cat.setMode(civModeFor(t, isUplink = true))
                    val tone = effectiveCtcss(t)
                    if (isFmMode(t) && tone > 0) {
                        cat.setToneFreq(tone); cat.setToneOn(true)
                    } else cat.setToneOn(false)
                    // MAIN = downlink: mode; finish on MAIN.
                    cat.selectMainSub(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
                SatLayout.SAME_BAND -> {
                    // ISS V/V etc.: sat mode OFF, split ON, RX=VFO A, TX=VFO B.
                    cat.armSameBandSplit()
                    cat.selectVfo(true)
                    cat.setMode(civModeFor(t, isUplink = true))
                    val toneSB = effectiveCtcss(t)
                    if (isFmMode(t) && toneSB > 0) {
                        cat.setToneFreq(toneSB); cat.setToneOn(true)
                    } else cat.setToneOn(false)
                    cat.selectVfo(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
                SatLayout.BEACON -> {
                    // Receive-only: sat mode off, tune downlink on MAIN only.
                    cat.setSatelliteMode(false)
                    cat.setSplitOn(false)
                    cat.setToneOn(false)
                    cat.selectMainSub(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
            }
        }
        catArmedFor = sat.catalogNumber
        catArmedTxDesc = t.description
        catLayout = layout
        lastSentDl = 0L; lastSentUl = 0L
        rxArbiter.reset(); fmArbiter.reset()
        // New satellite, new bands: what the driver knew about the rig is
        // void, and this is exactly when the order of the two writes is decided.
        cat.forgetBands()
    }
    private var catLayout: SatLayout = SatLayout.CROSS_BAND

    /**
     * One CAT cycle (~100 ms), OscarWatch-style.
     *  - FM cross-band (V/U): both legs Doppler-tuned (MAIN downlink, SUB uplink).
     *  - FM same-band (V/V, ISS): split A/B; RX on VFO A, TX on VFO B.
     *  - Linear: read the operator's RX dial; pause Doppler while it moves; resume
     *    after RxArbiter.holdMs of quiet (setting, 2 s by default). While the
     *    dial turns, SuiviMontee decides whether the uplink follows (at once,
     *    except on single-knob rigs such as the IC-9700).
     *  - Beacon: receive-only, Doppler on downlink only.
     */
    private suspend fun catTick() {
        if (!_ui.value.catConnected) return
        // An APRS frame is going out: no frequency write for that second or two.
        if (aprsEnEmission) return
        val pos = _ui.value.livePosition
        val belowHorizon = pos == null || pos.elevationDeg < 0
        if (belowHorizon && !_ui.value.catTestSendAlways) {
            val dlNow = runCatching {
                if (isPairRig) ft817.readDownlink() else cat.readMainFrequency()
            }.getOrNull()?.let { satDepuisPoste(it) }
            // Below the horizon nobody drives: the hold countdown restarts
            // from zero at AOS.
            rxArbiter.reset(); fmArbiter.reset()
            if (dlNow != null) _ui.value = _ui.value.copy(catRadioDownlinkHz = dlNow,
                catRxDriven = false)
            return
        }
        val rr = pos?.rangeRateKmS ?: 0.0
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        armCatForCurrent()
        val shift = _ui.value.calibShiftHz
        val txShift = _ui.value.txShiftHz

        when (catLayout) {
            SatLayout.BEACON -> {
                val dlRest = _ui.value.rxRestHz ?: centreRx(t) ?: return
                val dl = Doppler.downlink(dlRest, rr) + shift
                if (_ui.value.dopplerHold) {
                    // On hold, show the rig as it is, not the command we do not
                    // send (that would fake tracking). Read via `lireDescente`,
                    // so already a sky frequency, never the LNB IF.
                    val lu = lireDescente(sameBand = false)
                    _ui.value = _ui.value.copy(catRadioDownlinkHz = lu ?: dl,
                        catRadioUplinkHz = null, catRxDriven = false)
                } else {
                    if (kotlin.math.abs(dl - lastSentDl) >= dopplerThreshold(t)) {
                        lastSentDl = dl
                        runCatching {
                            val dlPoste = posteRx(dl)
                            if (isPairRig) ft817.rx.setFrequency(dlPoste)
                            else { cat.readMainFrequency(); cat.setFrequency(dlPoste) }
                        }
                    }
                    _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = null)
                }
            }

            SatLayout.SAME_BAND -> {
                // ISS-type V/V. FM: fixed both sides + Doppler via split A/B.
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = true)
                else interactiveLinear(t, rr, shift, txShift, sameBand = true)
            }

            SatLayout.CROSS_BAND -> {
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = false)
                else interactiveLinear(t, rr, shift, txShift, sameBand = false)
            }
        }

        surveillerLeMode(t)
    }

    /** Loop rounds since the rig's mode was last asked. */
    private var toursDepuisLeMode = 0

    /**
     * Checks and shows the rig's mode. Set once at arming, nobody looked at it
     * again: if the operator switched to LSB or the rig reverted to its band
     * memory, the screen still said USB and the other station became
     * inaudible with no explanation. So read it back, show it, and restore it
     * when it moved. One round in twenty (~2 s): the mode does not change ten
     * times a second, and the CI-V bus is busy with Doppler.
     */
    private suspend fun surveillerLeMode(t: fr.f4ioz.satcombo.data.Transmitter) {
        if (isPairRig) return
        if (++toursDepuisLeMode < 20) return
        toursDepuisLeMode = 0
        val lu = runCatching { cat.readMode() }.getOrNull() ?: return
        val attendu = civModeFor(t, isUplink = false)
        val ecart = lu != attendu
        _ui.value = _ui.value.copy(
            catRadioMode = fr.f4ioz.satcombo.cat.CatDecode.civModeName(lu),
            catModeMismatch = ecart)
        // Restoring the mode is a write like any other: on Doppler hold, only
        // report the mismatch.
        if (ecart && !_ui.value.dopplerHold) runCatching { cat.setMode(attendu) }
    }

    /** Doppler threshold... (kept below). */
    private fun dopplerThreshold(t: fr.f4ioz.satcombo.data.Transmitter): Long =
        if (isFmMode(t)) 200L else 20L

    /** FT-817 mode string for a transmitter leg (same policy as civModeFor). */
    private fun ft817ModeFor(t: fr.f4ioz.satcombo.data.Transmitter, isUplink: Boolean): String {
        val m = (t.mode ?: "").uppercase()
        return when {
            fr.f4ioz.satcombo.domain.ModeRadio.surFm(m) -> "FM"
            m.contains("CW") && !isUplink -> "CW"
            t.isTransponder && effectiveInvert(t) -> if (isUplink) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            else -> "USB"
        }
    }

    /**
     * Extract a CTCSS tone (tenths of Hz) from a transmitter description such as
     * "Mode V/U FM - Voice Repeater CTCSS 67.0". Returns 0 if none found.
     */
    private fun autoCtcssTenthHz(t: fr.f4ioz.satcombo.data.Transmitter): Int {
        val d = t.description
        val m = Regex("""(?:CTCSS|TONE|PL)\s*:?\s*(\d{2,3}(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
            .find(d) ?: return 0
        val hz = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0.0.toInt()
        return Math.round(hz * 10).toInt()
    }

    /** Effective CTCSS: explicit user setting wins; else auto-detected from name. */
    private fun effectiveCtcss(t: fr.f4ioz.satcombo.data.Transmitter): Int {
        if (_ui.value.ctcssTenthHz > 0) return _ui.value.ctcssTenthHz
        return if (_ui.value.ctcssAuto) autoCtcssTenthHz(t) else 0
    }

    // Who holds the RX VFO: the operator, or us.
    private val rxArbiter = fr.f4ioz.satcombo.cat.RxArbiter()

    /**
     * Same arbitration for FM, with a much coarser hand. 20 Hz recognises a
     * gesture on a linear transponder; in FM the rig rounds what we write to
     * its tuning step, and that rounding, read back, would look like an
     * operator gesture — control released every 100 ms with nobody touching
     * anything. A real FM channel change is at least 5 kHz; 1.5 kHz is wide
     * for one and out of reach for the other.
     */
    private val fmArbiter = fr.f4ioz.satcombo.cat.RxArbiter(moveHz = 1_500L)

    /**
     * TX knob arbiter. Same class as RX on purpose: the problem is identical —
     * the rig does not say *who* turned the knob, and what we read after
     * writing looks exactly like a gesture. A separate arbiter would drift.
     */
    private val txArbiter = fr.f4ioz.satcombo.cat.RxArbiter()

    /**
     * Applies the stored hold delay as soon as the arbiters exist. `regle()`
     * used to be called only from the settings picker, so arbiters restarted
     * at their factory 2 s on every launch and the setting seemed useless.
     */
    init {
        // Hotspot credentials set from the start, otherwise the Wi-Fi QR code
        // stays empty until first typed, even when stored.
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(
            settings.demoSsid, settings.demoMotDePasse)
        val v = settings.catHoldMs.toLong()
        rxArbiter.regle(v)
        fmArbiter.regle(v)
        txArbiter.regle(v)
    }

    /**
     * A TX knob gesture was seen and not yet absorbed. Without this flag,
     * absorption would fire on every round while the arbiter holds control —
     * i.e. always — and every rig rounding would drift into the shift.
     */
    @Volatile private var mainSurMoletteTx = false

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
    private suspend fun interactiveFm(
        t: fr.f4ioz.satcombo.data.Transmitter, rr: Double, shift: Long, txShift: Long,
        sameBand: Boolean
    ) {
        val ulRest = t.uplinkLowHz ?: return
        val dlObserved = lireDescente(sameBand)

        if (dlObserved != null) {
            val geste = fmArbiter.observe(dlObserved, System.currentTimeMillis())
            if (geste) {
                lastSentDl = 0L
                // The hand-picked channel is a rig frequency; rest is a
                // satellite frequency. Removing instant Doppler converts.
                _ui.value = _ui.value.copy(
                    rxRestHz = Doppler.restFromDownlink(dlObserved - shift, rr))
            }
        }

        val rest = _ui.value.rxRestHz ?: centreRx(t) ?: return
        val dl = Doppler.downlink(rest, rr) + shift
        val ul = Doppler.uplink(ulRest, rr) + txShift

        // No readback possible: always write — a mute rig must not lose
        // Doppler. On Doppler hold, never drive, whatever the arbiter says.
        val pilote = (dlObserved == null || fmArbiter.driven) && !_ui.value.dopplerHold
        if (pilote) {
            if (kotlin.math.abs(dl - lastSentDl) >= 200 || kotlin.math.abs(ul - lastSentUl) >= 200) {
                lastSentDl = dl; lastSentUl = ul
                fmArbiter.commanded(dl)
                runCatching { ecrireCouple(dl, ul, sameBand) }
            }
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = ul,
                catRxDriven = true)
        } else {
            // No readback (mute rig and hold): show the computed command
            // rather than nothing.
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dlObserved ?: dl,
                catRadioUplinkHz = ul, catRxDriven = false)
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
    private suspend fun interactiveLinear(
        t: fr.f4ioz.satcombo.data.Transmitter, rr: Double, shift: Long, txShift: Long,
        sameBand: Boolean
    ) {
        // The downlink comes back to the rig only if it can receive it. On a
        // QO-100 station it is heard on the dongle: nothing to read back or
        // arbitrate, and the rest chosen elsewhere drives TX.
        val auPoste = descenteAuPoste(_ui.value.rxRestHz ?: centreRx(t) ?: return)
        val dlObserved = if (auPoste) lireDescente(sameBand) else null
        if (auPoste && dlObserved == null) return
        val now = System.currentTimeMillis()
        val geste = dlObserved != null && rxArbiter.observe(dlObserved, now)
        if (geste) lastSentDl = 0L
        val pilote = (!auPoste || rxArbiter.driven) && _ui.value.catRxDoppler &&
            !_ui.value.dopplerHold

        // While not driving, rest is read from the rig, with calibration and
        // mode (voice/CW) offsets removed since they are re-added on write.
        val lu = if (dlObserved != null)
            Doppler.restFromDownlink(dlObserved - shift - activeRxOffset(), rr)
        else _ui.value.rxRestHz ?: centreRx(t) ?: return
        val lo = t.downlinkLowHz ?: lu
        val hi = t.downlinkHighHz ?: lu
        if (!pilote) {
            val clamped = lu.coerceIn(minOf(lo, hi), maxOf(lo, hi))
            if (_ui.value.rxRestHz == null || kotlin.math.abs((_ui.value.rxRestHz ?: 0) - clamped) >= 20) {
                _ui.value = _ui.value.copy(rxRestHz = clamped)
            }
        }
        val rest = _ui.value.rxRestHz ?: lu

        val ulRest = Doppler.transponderUplinkRest(
            rest, t.downlinkLowHz ?: rest, t.downlinkHighHz ?: rest,
            t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
        val ul = Doppler.uplink(ulRest, rr) + txShift
        val dl = Doppler.downlink(rest, rr) + shift + activeRxOffset()

        // --- TX knob as shift control ---
        // `ul` = `Doppler.uplink(ulRest, rr) + txShift`; if the operator turned
        // the knob to `ulLu`, the shift expressed is `ulLu - (ul - txShift)`.
        // Two-rig duplex only, and only while driving — otherwise we would take
        // the drift of a rig not yet commanded for a gesture.
        if (_ui.value.catUi.txVfoShift && isPairRig && !sameBand && pilote) {
            val ulLu = runCatching { ft817.readUplink() }.getOrNull()
                ?.let { satDepuisPosteTx(it) }
            if (ulLu != null) {
                // The computation lives in `MoletteTx`: it was wrong twice here,
                // untestable amid serial reads and writes. Isolated, it is
                // tested, including the Doppler drift that made the shift
                // oscillate between two values.
                if (txArbiter.observe(ulLu, now)) mainSurMoletteTx = true
                val d = fr.f4ioz.satcombo.domain.MoletteTx.decide(
                    shiftHz = txShift,
                    referenceHz = lastSentUl,
                    lueHz = ulLu,
                    gesteVu = mainSurMoletteTx,
                    moletteTranquille = txArbiter.driven)
                if (d.absorbe) {
                    setTxShift(d.shiftHz)
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
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = ul,
                catRxDriven = true)
        } else {
            // The uplink follows the RX knob — but not on every rig. "The hold
            // protects the RX knob, no reason to hold back TX" is false on a
            // dual-VFO rig with one knob: on an IC-9700 in satellite mode,
            // writing the uplink moves RX back, and the operator fights us
            // (filmed on RS-44 and FO-29: the sum of both VFOs stayed constant,
            // proof the rig held the pair). The decision is in `SuiviMontee`,
            // dependency-free and tested.
            if (fr.f4ioz.satcombo.domain.SuiviMontee.doitEcrire(
                    rigModel = _ui.value.rigModel,
                    operateurTourne = !rxArbiter.driven,
                    txSuitVite = _ui.value.catUi.txSuitVite,
                    maintienDoppler = _ui.value.dopplerHold,
                    ecartHz = kotlin.math.abs(ul - lastSentUl))) {
                lastSentUl = ul
                txArbiter.commanded(ul)
                runCatching { ecrireMontee(ul, sameBand) }
            }
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dlObserved, catRadioUplinkHz = ul,
                catRxDriven = false)
        }
    }

    fun toggleSelectionMode() {
        val on = !_ui.value.selectionMode
        _ui.value = _ui.value.copy(selectionMode = on,
            selectedPassKeys = if (on) _ui.value.selectedPassKeys else emptySet())
    }

    fun togglePassSelected(p: fr.f4ioz.satcombo.data.SatPass) {
        val k = passKey(p)
        val s = _ui.value.selectedPassKeys.toMutableSet()
        if (!s.add(k)) s.remove(k)
        _ui.value = _ui.value.copy(selectedPassKeys = s)
    }

    fun selectAllVisible(passes: List<fr.f4ioz.satcombo.data.SatPass>) {
        _ui.value = _ui.value.copy(selectedPassKeys = passes.map { passKey(it) }.toSet())
    }

    fun clearPassSelection() {
        _ui.value = _ui.value.copy(selectedPassKeys = emptySet())
    }

    /** Build a PDF from the selected passes and hand it to onReady (file uri). */
    fun exportSelectedPdf(onReady: (android.net.Uri) -> Unit) {
        val keys = _ui.value.selectedPassKeys
        val all = (_ui.value.favoritePasses + _ui.value.passes)
        val chosen = all.filter { passKey(it) in keys }
            .distinctBy { passKey(it) }
            .sortedBy { it.aosEpochMs }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val file = PassPdf.build(getApplication(), chosen, _ui.value.observer)
                PassPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /**
     * Build a rich PDF from the SELECTED passes: one sheet per satellite that
     * has selected passes, showing its polar plot (from the earliest selected
     * pass), radio info, and the list of selected passes. Hands uri to onReady.
     */
    fun exportSelectedSheets(onReady: (android.net.Uri) -> Unit) {
        val keys = _ui.value.selectedPassKeys
        val all = (_ui.value.favoritePasses + _ui.value.passes).distinctBy { passKey(it) }
        val chosen = all.filter { passKey(it) in keys }.sortedBy { it.aosEpochMs }
        if (chosen.isEmpty()) return
        buildSheets(chosen, onReady)
    }

    /**
     * Build the pass PDF: one block per pass (its own polar plot), 6 per A4 page.
     * Resolves each satellite's radio context (chosen transmitter + config) once.
     */
    private fun buildSheets(passes: List<SatPass>, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val cats = passes.map { it.catalogNumber }.toSet()
                val satByCat = _ui.value.satellites.associateBy { it.catalogNumber }
                val cfgByCat = HashMap<Int, SatConfig>()
                val txByCat = HashMap<Int, fr.f4ioz.satcombo.data.Transmitter?>()
                for (cat in cats) {
                    val cfg = satConfigStore.load(cat)
                    cfgByCat[cat] = cfg
                    val txs = runCatching { txRepo.forSatellite(cat) }.getOrDefault(emptyList())
                    txByCat[cat] = txs.firstOrNull { it.description == cfg.txDescription }
                        ?: txs.firstOrNull { it.alive } ?: txs.firstOrNull()
                }
                val sheets = passes.mapNotNull { p ->
                    val sat = satByCat[p.catalogNumber] ?: return@mapNotNull null
                    fr.f4ioz.satcombo.data.SatSheetPdf.PassSheet(
                        pass = p, sat = sat,
                        transmitter = txByCat[p.catalogNumber],
                        config = cfgByCat[p.catalogNumber])
                }
                val obs = _ui.value.observer ?: locationProvider.defaultObserver
                val file = fr.f4ioz.satcombo.data.SatSheetPdf.build(
                    getApplication(), sheets, obs, _ui.value.useUtc)
                fr.f4ioz.satcombo.data.SatSheetPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /**
     * Export a sheet for every upcoming pass (48h) of all followed satellites.
     */
    fun exportSatSheets(onReady: (android.net.Uri) -> Unit) {
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) return
        viewModelScope.launch {
            val passes = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val obs = _ui.value.observer ?: locationProvider.defaultObserver
                favs.flatMap { sat ->
                    runCatching {
                        predictor.upcomingPasses(sat, obs, fromMs = now - 20 * 60_000L,
                            hours = 48, minElDeg = settings.minElevDeg.toDouble())
                            .filter { it.losEpochMs > now }
                    }.getOrDefault(emptyList())
                }.sortedBy { it.aosEpochMs }
            }
            buildSheets(passes, onReady)
        }
    }

    /** Open a satellite from a notification tap; waits for data if still loading. */
    fun openFromNotification(catnum: Int, aos: Long) {
        viewModelScope.launch {
            var tries = 0
            while (_ui.value.satellites.none { it.catalogNumber == catnum } && tries < 50) {
                kotlinx.coroutines.delay(100); tries++
            }
            if (_ui.value.satellites.any { it.catalogNumber == catnum }) {
                closeSettings()
                selectByCatnum(catnum, if (aos > 0) aos else null)
            }
        }
    }

    fun selectByCatnum(catnum: Int, focusPassAos: Long? = null) {
        _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }?.let { select(it, focusPassAos) }
    }

    fun backToList() {
        trackingFor = null
        _ui.value = _ui.value.copy(selected = null, passes = emptyList(),
            passTrack = emptyList(), livePosition = null, trail = emptyList(),
            transmitters = emptyList(), transmittersLoading = false, groundTrack = emptyList(),
            logEditTimeMs = null, uiLocked = false)
    }

    /** If an announced sked for this sat is near and its TLE is aging, refresh it. */
    private fun maybeRefreshTleForSkeds(sat: TleEntry) {
        if (!_ui.value.skedsEnabled) return
        val soon = _ui.value.skeds.any {
            it.satNorad == sat.catalogNumber &&
                it.aosMs - System.currentTimeMillis() in 0..(48 * 3600_000L)
        }
        val epochAgeDays = sat.epochMs?.let {
            (System.currentTimeMillis() - it) / 86_400_000.0 } ?: 0.0
        if (soon && epochAgeDays > 2) refreshTleFor(sat.catalogNumber)
    }

    /**
     * Re-download the freshest TLE for one satellite from the enabled sources
     * and replace it in the list. Used when a sked is near so the common-window
     * calc uses up-to-date elements, and by the stale-elements button.
     *
     * [annonce]: say what happened (the button). Automatic refreshes stay
     * silent; a button that answers nothing looks broken.
     */
    /** SatMe GP server, from the settings: not in `UiState` (255-register limit). */
    fun serveurGp(): String = settings.serveurGp
    fun setServeurGp(v: String) { settings.serveurGp = v }
    fun serveurGpSeul(): Boolean = settings.serveurGpSeul
    fun setServeurGpSeul(v: Boolean) { settings.serveurGpSeul = v }
    suspend fun testeServeurGp(base: String): String = repo.testeServeur(base)

    fun refreshTleFor(catnum: Int, annonce: Boolean = false) {
        viewModelScope.launch {
            val r = repo.plusRecent(catnum, Sources.byIds(srcStore.load()), settings.serveurGp,
                settings.serveurGpSeul)
            val ancienne = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
            if (annonce) {
                val date = { e: TleEntry? -> e?.epochMs?.let {
                    java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(it)) + " UTC" } ?: "?" }
                val texte = when (r) {
                    is RafraichissementTle.Resultat.Trouve ->
                        if ((r.entree.epochMs ?: 0L) > (ancienne?.epochMs ?: 0L))
                            tf("tle_maj_ok", date(r.entree))
                        else tf("tle_maj_deja", date(ancienne))
                    RafraichissementTle.Resultat.Absent -> t("tle_maj_absent")
                    RafraichissementTle.Resultat.Injoignable -> t("tle_maj_injoignable")
                }
                android.widget.Toast.makeText(getApplication(), texte,
                    android.widget.Toast.LENGTH_LONG).show()
            }
            val fresh = (r as? RafraichissementTle.Resultat.Trouve)?.entree ?: return@launch
            // Never step back to older elements than those loaded.
            if ((fresh.epochMs ?: 0L) < (ancienne?.epochMs ?: 0L)) return@launch
            val updated = _ui.value.satellites.map {
                if (it.catalogNumber == catnum) fresh.copy(name = it.name,
                    uplinkHz = it.uplinkHz, downlinkHz = it.downlinkHz, mode = it.mode) else it
            }
            // Unchanged elements: nothing to recompute. `select()` clears the
            // transponders and shows the loading indicator; calling it on every
            // refresh made the open satellite screen flicker for nothing.
            val ancien = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
            val identique = ancien != null &&
                ancien.line1 == fresh.line1 && ancien.line2 == fresh.line2
            _ui.value = _ui.value.copy(satellites = updated)
            if (!identique) {
                _ui.value.selected?.let {
                    if (it.catalogNumber == catnum) select(fresh, _ui.value.focusedPassAos)
                }
            }
        }
    }

    fun select(sat: TleEntry, focusPassAos: Long? = null) {
        // Automatic SSTV keeps recording its satellite: say so when another one is picked.
        val arme = sstvAutoArme.takeIf { it != 0 } ?: settings.sstvAutoCatnum
        if (sat.catalogNumber != arme && fr.f4ioz.satcombo.audio.RecorderService.fenetres.value.isNotEmpty())
            sstvIssAvertissement.value = true
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        // Everything belonging to the previous satellite goes with it —
        // especially the hand-tuned rest, or the old satellite's frequencies
        // would show under the new name.
        _ui.value = _ui.value.copy(selected = sat, loading = true, trail = emptyList(),
            passTrack = emptyList(), transmitters = emptyList(), transmittersLoading = true,
            satStatus = null, focusedPassAos = focusPassAos, rxFromAgendaHz = null,
            logEditTimeMs = null, invertOverride = null,
            rxRestHz = null, selectedTxIndex = 0,
            catRadioDownlinkHz = null, catRadioUplinkHz = null)
        viewModelScope.launch {
            val tx = txRepo.forSatellite(sat.catalogNumber)
            val status = txRepo.statusOf(sat.catalogNumber)
            if (_ui.value.selected?.catalogNumber == sat.catalogNumber) {
                val active = tx.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
                val cfg = satConfigStore.load(sat.catalogNumber)
                // Without a saved choice, take the first transmitter with **both
                // uplink and downlink** — something you can work. Index zero
                // was the CW beacon on FO-29: listen-only.
                var idx = active.indexOfFirst { it.description == cfg.txDescription }
                if (idx < 0) idx = active.indexOfFirst {
                    it.downlinkLowHz != null && it.uplinkLowHz != null
                }
                if (idx < 0) idx = 0
                var rx = active.getOrNull(idx)?.let { centreRx(it) }
                // An agenda frequency beats the catalogue for the whole slot —
                // that is the point of the announcement. Pick the transmitter
                // whose band contains it, or the VFO would show it under an
                // unrelated transponder label.
                val evFreq = agendaFreqFor(sat.name,
                    focusPassAos ?: System.currentTimeMillis())
                if (evFreq != null) {
                    val hz = evFreq.freqHz
                    val inBand = active.indexOfFirst {
                        val lo = it.downlinkLowHz
                        val hi = it.downlinkHighHz ?: lo
                        lo != null && hi != null && hz >= minOf(lo, hi) && hz <= maxOf(lo, hi)
                    }
                    if (inBand >= 0) idx = inBand
                    rx = hz
                }
                _ui.value = _ui.value.copy(transmitters = tx, transmittersLoading = false,
                    selectedTxIndex = idx, rxRestHz = rx, calibShiftHz = cfg.calibShiftHz, txShiftHz = cfg.txShiftHz,
                    catUi = _ui.value.catUi.copy(
                        refCalibShiftHz = cfg.refCalibShiftHz, refTxShiftHz = cfg.refTxShiftHz,
                        arriveeCalibShiftHz = cfg.calibShiftHz,
                        arriveeTxShiftHz = cfg.txShiftHz),
                    rxOffsetVoiceHz = cfg.rxOffsetVoiceHz, rxOffsetCwHz = cfg.rxOffsetCwHz,
                    rxFromAgendaHz = evFreq?.freqHz,
                    satStatus = status)
            }
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val back = maxOf(_ui.value.pastPassHours * 3_600_000L, 20 * 60_000L)
            val from = now - back
            // Extend the window so a focused pass (e.g. chosen via a date filter
            // days ahead) is included, not just the next 72 h.
            val focus = focusPassAos
            val hours = if (focus != null)
                (((focus - now) / 3600_000L) + 6).toInt().coerceIn(72, 24 * 90)
            else 72
            val passes = withContext(Dispatchers.Default) {
                predictor.upcomingPasses(sat, obs, fromMs = from, hours = hours, minElDeg = settings.minElevDeg.toDouble())
            }.filter { it.losEpochMs > now - back }
            val gt = withContext(Dispatchers.Default) {
                predictor.groundTrack(sat, System.currentTimeMillis())
            }
            _ui.value = _ui.value.copy(passes = passes, loading = false, groundTrack = gt)
            refreshPassTrack(sat, obs)
            trackLive(sat, obs)
            maybeRefreshTleForSkeds(sat)
        }
    }

    /** Compute the polar-plot arc for the current or next pass. */
    private var trackedPassAos: Long = 0
    private suspend fun refreshPassTrack(sat: TleEntry, obs: Observer) {
        val now = System.currentTimeMillis()
        val focus = _ui.value.focusedPassAos
        val pass = (focus?.let { f -> _ui.value.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: _ui.value.passes.firstOrNull { it.losEpochMs > now }) ?: run {
            _ui.value = _ui.value.copy(passTrack = emptyList()); return
        }
        if (pass.aosEpochMs == trackedPassAos && _ui.value.passTrack.isNotEmpty()) return
        trackedPassAos = pass.aosEpochMs
        val track = withContext(Dispatchers.Default) {
            predictor.passTrack(sat, obs, pass.aosEpochMs, pass.losEpochMs)
        }
        _ui.value = _ui.value.copy(passTrack = track)
    }

    // ===================== az/el rotor =====================

    /**
     * The mast's mechanical state as the app believes it. Not the command:
     * a minute of rotation lies between them, which is what makes overlap
     * useful. When the controller reports its position it is authoritative;
     * when silent (cable torn, mute controller) we fall back on the last
     * command, and the banner says so.
     */
    private var rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)

    /**
     * Last position **actually read**, and when. Display only, to hold a few
     * seconds when the controller skips a reply (see [RotorTenue]); a held
     * position must never decide pointing.
     */
    private var rotorLu: fr.f4ioz.satcombo.rotor.RotorPos? = null
    private var rotorLuMs: Long = 0L

    /**
     * Last command sent — not the mast position. The next azimuth is unwrapped
     * near it, not near the read position: while the mast climbs towards 540°
     * the controller answers 380, 400, 420; unwrapping near those would send
     * the command back to the branch we came from, and the mast would turn
     * round mid-pass — exactly what the plan avoided.
     */
    private var rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)

    /** Mast turn chosen for the current pass, at AOS. */
    private var rotorPlan: fr.f4ioz.satcombo.rotor.RotorMath.Plan? = null
    private var rotorPlanKey: String? = null
    private var rotorLoopJob: kotlinx.coroutines.Job? = null
    private var rotorWasFlipped = false
    private var rotorSimLink: fr.f4ioz.satcombo.rotor.Gs232Simulator? = null

    /**
     * Same driver as [rotorDriver] when it speaks GS-232. The concrete type is
     * needed for raw frames: the common interface knows only "position" and
     * "go", enough to track, not to understand why nothing moves.
     */
    private var rotorSerial: fr.f4ioz.satcombo.rotor.Gs232Rotor? = null

    // ==================================================================
    // QO-100 — the satellite that does not move
    //
    // This screen uses none of the usual loops, on purpose. `catTick()` starts
    // from `livePosition` (SGP4); a geostationary has no usable propagated
    // position and the pass predictor would return nothing. Rather than
    // sprinkle exceptions in those loops — each a chance to break other
    // satellites — we write here, when the operator moves something. Without
    // Doppler the frequency only moves when you move it.
    //
    // Reused as is: [ecrireCouple] (converters, reachability guard, satellite
    // mode) and the per-NORAD calibration offset.
    // ==================================================================

    /** The currently chosen transponder, never null. */
    private fun qo100Tp(): Qo100.Transpondeur =
        Qo100.TRANSPONDEURS.firstOrNull { it.cle == _ui.value.qo100.transpondeur } ?: Qo100.NB

    fun openQo100() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.QO100)) return
        va(Screen.QO100)
        _ui.value = _ui.value.copy(screen = Screen.QO100)
        // Calibration and dish pointing do not change while operating: read
        // once on opening.
        val calage = satConfigStore.load(Qo100.NORAD).calibShiftHz
        _ui.value = _ui.value.qo { copy(calageHz = calage) }
        qo100Recalcule()
        viewModelScope.launch {
            val obs = runCatching { resolveObserver() }.getOrNull() ?: return@launch
            val p = Qo100.pointage(obs.latDeg, obs.lonDeg)
            _ui.value = _ui.value.qo {
                copy(azDeg = p.azDeg, elDeg = p.elDeg, skewDeg = p.skewDeg)
            }
            qo100Soleil(obs.latDeg, obs.lonDeg)
        }
        qo100Panorama(true)
    }

    /** The beacon-watching job. Only one, and it dies with the screen. */
    private var jobBalise: kotlinx.coroutines.Job? = null

    /**
     * Turns the panorama and the beacon indicator on or off. Their only client
     * is this screen: a 16k-point FFT three times a second has no business
     * running while the operator listens to a radiosonde — hence a switch,
     * not a permanent computation in [SdrHub].
     */
    private fun qo100Panorama(on: Boolean) {
        runCatching { fr.f4ioz.satcombo.sdr.SdrHub.setPanorama(on) }
        jobBalise?.cancel()
        jobBalise = null
        if (!on) {
            if (_ui.value.qo100.balise != null) {
                _ui.value = _ui.value.qo { copy(balise = null) }
            }
            return
        }
        jobBalise = viewModelScope.launch {
            fr.f4ioz.satcombo.sdr.SdrHub.panorama.collect { pan ->
                val m = if (pan.isEmpty()) null
                else withContext(Dispatchers.Default) { qo100MesureBalise(pan) }
                // Write state only when the measurement changed, or the whole
                // screen recomposes three times a second for nothing.
                if (m != _ui.value.qo100.balise) {
                    _ui.value = _ui.value.qo { copy(balise = m) }
                }
            }
        }
    }

    /**
     * Where the middle beacon falls in the panorama, on the ruler's scale.
     *
     * It is all about the reference frame. The panorama is anchored on the
     * dongle's PLL (not the fine tuning, downstream of the tap). Go from the
     * dongle frequency to the sky through the converter, then remove the
     * calibration already applied: what remains is in nominal frequencies,
     * like the ruler and band plan. The returned offset is then exactly what
     * must be added to the calibration, so the button writes
     * `calibration + offset` and converges in one step.
     */
    private fun qo100MesureBalise(
        pan: FloatArray
    ): fr.f4ioz.satcombo.domain.MesureBalise.Mesure? {
        val axe = qo100AxeCiel() ?: return null
        return fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, axe.first, axe.second)
    }

    /**
     * Panorama axis: sky centre and signed span. One function so the beacon
     * measurement and the waterfall drawing share the same axis; two would
     * eventually differ by a sign or a calibration, and the line would miss
     * its mark. `null` when the dongle is not running.
     */
    fun qo100AxeCiel(): Pair<Double, Double>? {
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        if (!st.running) return null
        val q = _ui.value.qo100
        // PLL without the software Doppler part — zero on a geostationary, but
        // do not rely on it being zero.
        val pllHz = st.centerHz - st.dopplerFineHz
        val centreCiel = cleVersSat(pllHz).toDouble() - q.calageHz
        val conv = _ui.value.convRx
        val inverse = _ui.value.convRxCle && conv.inverseur &&
            conv.couvre(q.descenteHz + q.calageHz)
        val etendue = if (inverse) -fr.f4ioz.satcombo.sdr.SdrHub.PANORAMA_SPAN_HZ
        else fr.f4ioz.satcombo.sdr.SdrHub.PANORAMA_SPAN_HZ
        return centreCiel to etendue
    }

    /**
     * Writes at once the calibration dictated by the beacon — like manual
     * entry, but using the measured value. No measurement: do nothing,
     * certainly not reset to zero.
     */
    fun qo100CalerSurLaMesure() {
        val m = _ui.value.qo100.balise ?: return
        setQo100Calage(_ui.value.qo100.calageHz + m.ecartArrondiHz)
    }

    /**
     * Sun markers, computed off the main thread: the transit sweep walks a
     * year minute by minute (half a million Sun positions, tens of ms — two
     * dropped frames on a slow phone). Computed once on opening: the azimuth
     * time holds until tomorrow, transits until the next equinox.
     */
    private suspend fun qo100Soleil(latDeg: Double, lonDeg: Double) {
        val maintenant = System.currentTimeMillis()
        val calcul = withContext(Dispatchers.Default) {
            runCatching {
                fr.f4ioz.satcombo.domain.SoleilQo100.prochainPassageEnAzimut(
                    latDeg, lonDeg, maintenant) to
                    fr.f4ioz.satcombo.domain.SoleilQo100.prochainsTransits(
                        latDeg, lonDeg, maintenant, maximum = 4)
            }.getOrNull()
        } ?: return
        _ui.value = _ui.value.qo {
            copy(soleilAzimutMs = calcul.first, soleilTransits = calcul.second)
        }
    }

    /**
     * Leaving the screen undoes nothing: the rig stays where it was put, so a
     * QSO is not disturbed because someone looked at the pass list.
     */
    fun closeQo100() {
        qo100Panorama(false)
        _ui.value = retour()
    }

    fun setQo100Transpondeur(cle: String) {
        val tp = Qo100.TRANSPONDEURS.firstOrNull { it.cle == cle } ?: return
        _ui.value = _ui.value.qo {
            copy(transpondeur = tp.cle, descenteHz = tp.brideDescente(descenteHz))
        }
        qo100Recalcule()
        qo100Pousser()
    }

    /**
     * Tunes to a downlink, clamped to the transponder. Not comfort: beyond the
     * edges the matching uplink leaves the transponder and the carrier lands
     * on the neighbour — a geostationary has no horizon to end the mistake.
     */
    fun setQo100Descente(hz: Long) {
        // Unclamped mode sweeps all of QO-100, never beyond the band plan.
        val borne = if (settings.qo100SansBride)
            hz.coerceIn(Qo100.REGLETTE_BAS_HZ, Qo100.REGLETTE_HAUT_HZ)
        else qo100Tp().brideDescente(hz)
        _ui.value = _ui.value.qo { copy(descenteHz = borne) }
        qo100Recalcule()
        qo100Pousser()
    }

    /** Sweep all of QO-100, or stay in the chosen transponder. */
    fun setQo100SansBride(on: Boolean) {
        settings.qo100SansBride = on
        _ui.value = _ui.value.qo { copy(sansBride = on) }
    }

    /** The screen's knob step, signed Hz. */
    fun qo100Pas(deltaHz: Long) = setQo100Descente(_ui.value.qo100.descenteHz + deltaHz)

    /** Go to the middle beacon: everyone's reference point. */
    fun qo100AllerBalise() = setQo100Descente(Qo100.BALISE_MEDIANE_HZ)

    /** Go to a memory or marker. */
    fun qo100Aller(hz: Long) = setQo100Descente(hz)

    /**
     * Stores the current frequency as a memory. The name is optional (the kHz
     * are used otherwise): a mandatory name would stop you saving it mid-QSO,
     * with no hand free to type.
     */
    fun qo100PoseMemoire(nom: String) {
        val posees = fr.f4ioz.satcombo.domain.MemoiresQo100.pose(
            _ui.value.qo100.memoires, _ui.value.qo100.descenteHz, nom)
        settings.qo100Memoires = posees
        _ui.value = _ui.value.qo { copy(memoires = posees) }
    }

    fun qo100RetireMemoire(hz: Long) {
        val posees = fr.f4ioz.satcombo.domain.MemoiresQo100.retire(
            _ui.value.qo100.memoires, hz)
        settings.qo100Memoires = posees
        _ui.value = _ui.value.qo { copy(memoires = posees) }
    }

    // ---------------------------------------------- conversion chains

    /**
     * Stores a receiver's measured offset. The reference is ignored: without a
     * fixed point a measurement cannot tell the LNB's share from the receiver's.
     */
    fun setMaterielPpm(nom: String, ppm: Double) {
        val liste = fr.f4ioz.satcombo.domain.MaterielRx.range(
            _ui.value.qo100.materiels, nom, ppm)
        settings.materielsRx = fr.f4ioz.satcombo.domain.MaterielRx.ecrit(liste)
        _ui.value = _ui.value.qo { copy(materiels = liste) }
        qo100Recalcule()
    }

    fun setMaterielPoste(nom: String) {
        settings.materielPoste = nom
        _ui.value = _ui.value.qo { copy(materielPoste = nom) }
        qo100Recalcule()
    }

    fun setMaterielCle(nom: String) {
        settings.materielCle = nom
        _ui.value = _ui.value.qo { copy(materielCle = nom) }
        qo100Recalcule()
    }

    /**
     * Switches chain: fixed station, portable, anything. Each site has its
     * converters and **each oscillator its own measured error**; retyping it at
     * every site change means one day getting it wrong — and 300 kHz off on
     * QO-100 means hearing nothing.
     */
    fun setQo100Chaine(nom: String) {
        settings.qo100Chaine = nom
        _ui.value = _ui.value.qo { copy(chaine = nom) }
        qo100AppliqueChaine()
    }

    /**
     * Stores a measured oscillator in the active chain. [cielHz] is read on a
     * reference (a GPSDO-locked WebSDR), [posteHz] shown by the rig on the
     * **same signal**; the difference is the oscillator — the operator just
     * copies two numbers.
     *
     * Rejects what cannot be an oscillator (swapped fields, misplaced decimal)
     * but **never a mere offset from nominal**: that is what we measure.
     * Returns success **and** the message, so the screen can show a success
     * differently from a failure.
     */
    fun qo100Mesure(descente: Boolean, cielHz: Long, posteHz: Long): Pair<Boolean, String> {
        if (cielHz <= 0L || posteHz <= 0L) return false to t("qo100_mesure_vide")
        val ol = fr.f4ioz.satcombo.domain.ChaineQo100.olMesure(cielHz, posteHz)
        val bon = if (descente)
            fr.f4ioz.satcombo.domain.ChaineQo100.descenteCredible(ol)
        else fr.f4ioz.satcombo.domain.ChaineQo100.monteeCredible(ol)
        if (!bon) return false to tf("qo100_mesure_refus", ol / 1_000_000.0)
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = ol)
                    else actuelle.copy(monteeOlHz = ol)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
        // **Say when it worked**: return the oscillator found and its offset
        // from nominal — the interesting part (27 kHz is the LNB crystal, and
        // it is reproducible). Silence looked like a button that did nothing.
        val nominal = if (descente) 10_345_000_000L else 1_968_000_000L
        return true to tf("qo100_mesure_ok", ol / 1_000_000.0, (ol - nominal) / 1_000.0)
    }

    /** Clears an oscillator: the chain becomes direct on that side. */
    fun qo100EffaceOl(descente: Boolean) {
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = 0L)
                    else actuelle.copy(monteeOlHz = 0L)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
    }

    /**
     * Sets a nominal oscillator from a preset: the starting point (pick the
     * hardware, get the catalogue LO; measurement corrects it later). `olHz`
     * zero removes the converter — the rig already works on the sky frequency.
     */
    fun qo100PoseOl(descente: Boolean, olHz: Long) {
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = olHz)
                    else actuelle.copy(monteeOlHz = olHz)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
    }

    /** Adds an empty chain with this name and selects it. */
    fun qo100AjouteChaine(nom: String) {
        if (nom.isBlank()) return
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(
            _ui.value.qo100.chaines,
            fr.f4ioz.satcombo.domain.ChaineQo100.Chaine(nom = nom.trim()))
        settings.qo100Chaines = liste
        settings.qo100Chaine = nom.trim()
        _ui.value = _ui.value.qo { copy(chaines = liste, chaine = nom.trim()) }
        qo100AppliqueChaine()
    }

    /**
     * Copies the active chain's oscillators into the converters. Single source
     * of truth: the chain. Converters remain the mechanism but are no longer
     * set by hand for QO-100 — two places for one oscillator would diverge.
     */
    private fun qo100AppliqueChaine() {
        val q = _ui.value.qo100
        val c = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        // **The chain decides, including when it wants no converter.** Setting
        // only when active left the old oscillator on after choosing "direct".
        // A single source of truth must write the empty cases too.
        // **Bounds sized for QO-100, not the whole Ku band**: the wider the
        // bounds, the wider the IF window and the easier a foreign frequency
        // looks like one of ours.
        settings.convRxActif = c.descenteActive
        settings.convRxOlHz = c.descenteOlHz
        settings.convRxInverseur = false
        settings.convRxBasHz = 10_489_000_000L
        settings.convRxHautHz = 10_500_000_000L
        settings.convTxActif = c.monteeActive
        settings.convTxOlHz = c.monteeOlHz
        settings.convTxInverseur = false
        settings.convTxBasHz = 2_390_000_000L
        settings.convTxHautHz = 2_450_000_000L
        // The displayed state must follow, or the converter screen would show
        // the old value.
        _ui.value = _ui.value.copy(
            convRx = fr.f4ioz.satcombo.domain.Convertisseur(
                actif = c.descenteActive, olHz = c.descenteOlHz,
                inverseur = false,
                basHz = 10_489_000_000L, hautHz = 10_500_000_000L),
            convTx = fr.f4ioz.satcombo.domain.Convertisseur(
                actif = c.monteeActive, olHz = c.monteeOlHz,
                inverseur = false,
                basHz = 2_390_000_000L, hautHz = 2_450_000_000L))
        qo100Pousser()
    }

    fun setQo100AuPoste(on: Boolean) {
        _ui.value = _ui.value.qo { copy(auPoste = on) }
        if (on) qo100Pousser()
    }

    fun setQo100ALaCle(on: Boolean) {
        _ui.value = _ui.value.qo { copy(aLaCle = on) }
        if (on) qo100Pousser()
    }

    /**
     * Calibration on the beacon. The operator notes where the middle BPSK
     * beacon really falls; the gap from 10 489.750 is the downconverter LO
     * drift (tens of kHz at power-up of an ordinary LNB, under one after half
     * an hour). Stored like every other calibration offset, under the
     * satellite's NORAD.
     *
     * @param entenduHz where the beacon was heard, as a sky frequency.
     */
    fun qo100CalerSurLaBalise(entenduHz: Long) {
        val ecart = entenduHz - Qo100.BALISE_MEDIANE_HZ
        setQo100Calage(_ui.value.qo100.calageHz + ecart)
    }

    /** Calibration offset, set directly. */
    fun setQo100Calage(hz: Long) {
        satConfigStore.saveShift(Qo100.NORAD, hz)
        _ui.value = _ui.value.qo { copy(calageHz = hz) }
        qo100Recalcule()
        qo100Pousser()
    }

    /** Resets calibration to zero — after changing LNB. */
    fun qo100AnnulerCalage() = setQo100Calage(0L)

    /**
     * Recomputes derived values, nothing else. Rig and dongle frequencies are
     * display-only (what the front panel should read); real writes go through
     * [ecrireCouple], which converts on its own. Reusing a converted value
     * would convert it twice.
     */
    private fun qo100Recalcule() {
        val q = _ui.value.qo100
        val dlSat = q.descenteHz + q.calageHz
        val ulSat = Qo100.monteeDepuisDescente(q.descenteHz)
        val rx = posteRx(dlSat)
        val tx = posteTx(ulSat)
        val cle = cleRx(dlSat)
        _ui.value = _ui.value.qo {
            copy(
                posteRxHz = rx, posteTxHz = tx, cleRxHz = cle,
                posteAtteignable = atteignableParLePoste(rx) && atteignableParLePoste(tx),
                cleAtteignable = cle in 24_000_000L..1_766_000_000L,
                surBalise = kotlin.math.abs(descenteHz - Qo100.BALISE_MEDIANE_HZ) < 100L)
        }
    }

    /**
     * Pushes the current frequency to what is ticked, and only that. The two
     * boxes are truly independent: rig transmitting on 432 while listening on
     * the dongle through another converter is a common QO-100 setup.
     */
    private fun qo100Pousser() {
        val q = _ui.value.qo100
        val dlSat = q.descenteHz + q.calageHz
        val ulSat = Qo100.monteeDepuisDescente(q.descenteHz)
        // **Sweeping is not transmitting.** Unclamped, you cross beacons and
        // no-TX segments to listen. Pushing an uplink there would set the rig
        // to transmit on a beacon — the whole band's reference — one PTT press
        // away. So the uplink is written only where the band plan allows, and
        // the screen says so.
        val peutEmettre = Qo100.emissionAutorisee(q.descenteHz)
        if (q.auPoste && !peutEmettre) {
            _ui.value = _ui.value.qo { copy(statut = t("qo100_ecoute_seule")) }
        }
        if (q.auPoste && peutEmettre) viewModelScope.launch {
            // sameBand = false: 145 RX / 432 TX is the rig's satellite mode,
            // not a plain split.
            runCatching { ecrireCouple(dlSat, ulSat, sameBand = false) }
                .onFailure { e ->
                    _ui.value = _ui.value.qo { copy(statut = e.message ?: "CAT ?") }
                }
        }
        if (q.aLaCle) runCatching {
            fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(dlSat), cleRx(dlSat))
        }
    }

    fun openRotor() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.ROTOR)) return
        refreshRotorDevices()
        va(Screen.ROTOR)
        _ui.value = _ui.value.copy(screen = Screen.ROTOR)
    }

    /** Leaving the screen does not stop the mast: a pass lasts ten minutes. */
    fun closeRotor() { _ui.value = retour() }

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
            _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = tf("rotor_error", what)) }
            android.util.Log.w("SatMe/ROTOR", "echec " + where, e)
        }
    }

    fun setRotorEnabled(v: Boolean) {
        settings.rotorEnabled = v
        _ui.value = _ui.value.rot { copy(rotorEnabled = v) }
    }

    fun setRotorLink(v: String) {
        settings.rotorLink = v
        _ui.value = _ui.value.rot { copy(rotorLink = settings.rotorLink) }
    }

    fun setRotorUsbIndex(v: Int) {
        settings.rotorUsbIndex = v
        _ui.value = _ui.value.rot { copy(rotorUsbIndex = settings.rotorUsbIndex) }
    }

    fun setRotorBaud(v: Int) {
        settings.rotorBaud = v
        _ui.value = _ui.value.rot { copy(rotorBaud = settings.rotorBaud) }
    }

    fun setRotorHost(v: String) {
        settings.rotorHost = v
        _ui.value = _ui.value.rot { copy(rotorHost = settings.rotorHost) }
    }

    fun setRotorPort(v: Int) {
        settings.rotorPort = v
        _ui.value = _ui.value.rot { copy(rotorPort = settings.rotorPort) }
    }

    fun setRotorMaxAz(v: Int) {
        settings.rotorMaxAz = v
        _ui.value = _ui.value.rot { copy(rotorMaxAz = settings.rotorMaxAz) }
    }

    /**
     * Changing the end stop discards the current plan: it was computed for one
     * stop and means nothing for another. Keeping it until the next pass would
     * point the mast 180° off.
     */
    fun setRotorAzStop(v: String) {
        settings.rotorAzStop = v
        rotorPlan = null; rotorPlanKey = null
        _ui.value = _ui.value.rot { copy(rotorAzStop = settings.rotorAzStop, rotorCoverage = null) }
    }

    fun setRotorAzFromStop(v: Boolean) {
        settings.rotorAzFromStop = v
        _ui.value = _ui.value.rot { copy(rotorAzFromStop = settings.rotorAzFromStop) }
    }

    fun setRotorAzOnly(v: Boolean) {
        settings.rotorAzOnly = v
        _ui.value = _ui.value.rot { copy(rotorAzOnly = settings.rotorAzOnly,
            rotorAimEl = if (v) null else _ui.value.rotorAimEl) }
    }

    fun setRotorMaxError(v: Int) {
        settings.rotorMaxError = v
        rotorPlan = null; rotorPlanKey = null
        _ui.value = _ui.value.rot { copy(rotorMaxError = settings.rotorMaxError, rotorCoverage = null) }
    }

    fun setRotorMaxEl(v: Int) {
        settings.rotorMaxEl = v
        _ui.value = _ui.value.rot { copy(rotorMaxEl = settings.rotorMaxEl) }
    }

    fun setRotorDeadband(v: Int) {
        settings.rotorDeadband = v
        _ui.value = _ui.value.rot { copy(rotorDeadband = settings.rotorDeadband) }
    }

    fun setRotorFlip(v: Boolean) {
        settings.rotorFlip = v
        rotorWasFlipped = false
        _ui.value = _ui.value.rot { copy(rotorFlip = v) }
    }

    fun setRotorParkAz(v: Int) {
        settings.rotorParkAz = v
        _ui.value = _ui.value.rot { copy(rotorParkAz = settings.rotorParkAz) }
    }

    fun setRotorParkEl(v: Int) {
        settings.rotorParkEl = v
        _ui.value = _ui.value.rot { copy(rotorParkEl = settings.rotorParkEl) }
    }

    fun setRotorMinEl(v: Int) {
        settings.rotorMinEl = v
        _ui.value = _ui.value.rot { copy(rotorMinEl = settings.rotorMinEl) }
    }

    /** Minutes before AOS at which the mast goes to wait for the satellite. */
    fun setRotorPreAos(v: Int) {
        settings.rotorPreAos = v
        _ui.value = _ui.value.rot { copy(rotorPreAos = settings.rotorPreAos) }
    }

    fun setRotorSim(v: Boolean) {
        settings.rotorSim = v
        _ui.value = _ui.value.rot { copy(rotorSim = v) }
    }

    /** Visible USB adapters, so the index means something. */
    fun refreshRotorDevices() {
        rotorGuard("rotor_devices") {
            _ui.value = _ui.value.rot { copy(
                rotorDevices = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()).listDevices()) }
        }
    }

    fun rotorRequestPermissions() {
        rotorGuard("rotor_perm") {
            fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()).requestPermissions()
            refreshRotorDevices()
        }
    }

    fun connectRotor() {
        viewModelScope.launch { rotorGuard("rotor_connect") { connectRotorInner() } }
    }

    private suspend fun connectRotorInner() {
        disconnectRotor()
        val u = _ui.value
        if (u.rotorSim) {
            // A mast that does not exist: everything shows, nothing moves.
            val sim = fr.f4ioz.satcombo.rotor.Gs232Simulator(
                azMaxDeg = u.rotorMaxAz.toDouble(),
                elMaxDeg = if (u.rotorFlip) 180.0 else u.rotorMaxEl.toDouble())
            val d = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication())
            d.pacingMs = 0L
            d.attach(sim)
            rotorSimLink = sim
            rotorDriver = d
            rotorSerial = d
            rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)
            rotorWasFlipped = false
            _ui.value = _ui.value.rot { copy(rotorConnected = true, rotorStatus = t("rotor_sim_on")) }
            startRotorLoop()
            return
        }
        if (u.rotorLink == "ROTCTLD") {
            val d = fr.f4ioz.satcombo.rotor.RotctldRotor()
            val ok = d.open(u.rotorHost, u.rotorPort)
            rotorDriver = if (ok) d else null
            _ui.value = _ui.value.rot { copy(rotorConnected = ok,
                rotorStatus = if (ok) tf("rotor_connected", u.rotorHost + ":" + u.rotorPort)
                else tf("rotor_error", u.rotorHost + ":" + u.rotorPort)) }
            if (ok) { rotorWasFlipped = false; startRotorLoop() }
            return
        }
        val d = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication())
        val refs = d.availablePorts()
        if (refs.isEmpty()) {
            _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = t("rotor_none"),
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
        for (i in ordre) {
            trace.add(tf("cat_diag_try", refs[i].label))
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
        rotorDriver = if (ok) d else null
        rotorSerial = if (ok) d else null
        _ui.value = _ui.value.rot { copy(rotorConnected = ok,
            rotorDevices = refs.map { it.label }, rotorDiag = trace,
            rotorUsbIndex = if (ok) gagnant else u.rotorUsbIndex,
            rotorLastSent = d.lastSent, rotorLastReply = d.lastReply,
            rotorStatus = if (ok) tf("rotor_connected", refs[gagnant].label)
            else if (echec.isNotBlank()) echec
            else t("open_failed_usb")) }
        if (ok) { rotorWasFlipped = false; startRotorLoop() }
    }

    fun setRotorAutoPort(v: Boolean) { _ui.value = _ui.value.rot { copy(rotorAutoPort = v) } }

    fun setRotorManualAz(v: Int) {
        _ui.value = _ui.value.rot { copy(rotorManualAz = v.coerceIn(0, _ui.value.rotorMaxAz)) }
    }

    fun setRotorManualEl(v: Int) {
        _ui.value = _ui.value.rot { copy(rotorManualEl = v.coerceIn(0, if (_ui.value.rotorFlip) 180 else _ui.value.rotorMaxEl)) }
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
        viewModelScope.launch {
            rotorGuard("rotor_goto") {
                setRotorEnabled(false)
                val u = _ui.value
                val limits = rotorLimits(u)
                val aim = fr.f4ioz.satcombo.rotor.RotorMath.manual(
                    u.rotorManualAz.toDouble(), u.rotorManualEl.toDouble(), limits)
                if (aim == null) {
                    _ui.value = _ui.value.rot { copy(rotorOutOfRange = true,
                        rotorStatus = t("rotor_manual_refused")) }
                    return@rotorGuard
                }
                launch {
                    val cmdAz = fr.f4ioz.satcombo.rotor.RotorMath.commandAz(
                        aim.azDeg, limits, u.rotorAzFromStop)
                    val parti = rotorDriver?.moveTo(cmdAz, aim.elDeg) ?: false
                    rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
                    rotorCmd = rotorAt
                    rotorWasFlipped = false
                    _ui.value = _ui.value.rot { copy(
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
     * One step more or less from where we think we are: the **read** position
     * when available, else the previous command. Handy for rough end-stop
     * setup: step ten degrees, look at the antenna, repeat.
     */
    fun rotorJog(dAz: Int, dEl: Int) {
        val u = _ui.value
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
        viewModelScope.launch {
            rotorGuard("rotor_read") {
                launch {
                    val d = rotorDriver
                    val pos = runCatching { d?.readPosition() }.getOrNull()
                    val ser = rotorSerial
                    _ui.value = _ui.value.rot { copy(
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

    fun disconnectRotor() {
        rotorLoopJob?.cancel(); rotorLoopJob = null
        runCatching { rotorDriver?.close() }
        rotorDriver = null
        rotorSimLink = null
        rotorSerial = null
        // A disconnected mast has no position to hold: forget the last read,
        // or it would outlive the disconnection by four seconds.
        rotorLu = null; rotorLuMs = 0L
        _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = t("rotor_offline"),
            rotorTargetAz = null, rotorTargetEl = null,
            rotorActualAz = null, rotorActualEl = null, rotorOutOfRange = false,
            rotorAimAz = null, rotorAimEl = null) }
    }

    /**
     * Immediate stop. It also stops tracking: a stop followed by a command a
     * second later is not a stop, it is a pause.
     */
    fun rotorStopNow() {
        viewModelScope.launch {
            rotorGuard("rotor_stop") {
                setRotorEnabled(false)
                launch { rotorDriver?.stop() }
            }
        }
    }

    fun rotorParkNow() {
        viewModelScope.launch {
            rotorGuard("rotor_park") {
                setRotorEnabled(false)
                val u = _ui.value
                val aim = fr.f4ioz.satcombo.rotor.RotorMath.park(
                    u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), rotorLimits(u))
                if (aim == null) {
                    _ui.value = _ui.value.rot { copy(rotorOutOfRange = true) }
                } else {
                    launch {
                        rotorDriver?.moveTo(
                            fr.f4ioz.satcombo.rotor.RotorMath.commandAz(
                                aim.azDeg, rotorLimits(u), u.rotorAzFromStop),
                            aim.elDeg)
                        rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
                        rotorCmd = rotorAt
                        rotorWasFlipped = false
                        _ui.value = _ui.value.rot { copy(rotorTargetAz = aim.azDeg,
                            rotorTargetEl = aim.elDeg, rotorOutOfRange = false) }
                    }
                }
            }
        }
    }

    private fun rotorLimits(u: UiState) = fr.f4ioz.satcombo.rotor.RotorMath.Limits(
        azMaxDeg = u.rotorMaxAz.toDouble(),
        elMaxDeg = if (u.rotorFlip) 180.0 else u.rotorMaxEl.toDouble(),
        deadbandDeg = u.rotorDeadband.toDouble(),
        azStopDeg = if (u.rotorAzStop == "SOUTH") 180.0 else 0.0)

    /**
     * The pass plan: chosen once, held to the end. Recomputed only when pass,
     * end stop, travel or tolerance change; recomputing every second would
     * give a mast changing its mind mid-pass, with two minutes of rotation to
     * reach a place the satellite has already left.
     */
    private fun ensureRotorPlan(
        u: UiState,
        limits: fr.f4ioz.satcombo.rotor.RotorMath.Limits
    ): fr.f4ioz.satcombo.rotor.RotorMath.Plan? {
        val track = u.passTrack
        if (track.isEmpty()) {
            rotorPlan = null; rotorPlanKey = null
            return null
        }
        val key = "${track.size}|${track.first()}|${track.last()}|" +
            "${limits.azStopDeg}|${limits.azMaxDeg}|${u.rotorMaxError}"
        if (key == rotorPlanKey) return rotorPlan
        val p = fr.f4ioz.satcombo.rotor.RotorMath.plan(
            track, limits, toleranceDeg = u.rotorMaxError.toDouble())
        rotorPlanKey = key
        rotorPlan = p
        if (p != null) {
            rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(
                fr.f4ioz.satcombo.rotor.RotorMath.clampAz(p.startAzDeg, limits), rotorCmd.elDeg)
            rotorWasFlipped = false
        }
        _ui.value = _ui.value.rot { copy(rotorCoverage = p?.coverage) }
        return p
    }

    /**
     * Mast tracking loop: one command per second, no more. A rotor gains
     * nothing from faster commands — it turns at ~6°/s and every start wears a
     * relay.
     */
    private fun startRotorLoop() {
        rotorLoopJob?.cancel()
        rotorLoopJob = viewModelScope.launch {
            while (_ui.value.rotorConnected) {
                runCatching { rotorTick() }
                delay(1000)
            }
        }
    }

    private suspend fun rotorTick() {
        val d = rotorDriver ?: return
        // The simulated mast only moves when advanced.
        rotorSimLink?.advance(1000)
        val u = _ui.value
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
        val frais = brut?.let {
            fr.f4ioz.satcombo.rotor.RotorPos(
                fr.f4ioz.satcombo.rotor.RotorMath.trueAz(it.azDeg, limits, u.rotorAzFromStop),
                it.elDeg)
        }
        val maintenant = System.currentTimeMillis()
        if (frais != null) { rotorLu = frais; rotorLuMs = maintenant }
        // If it still stays silent, hold its last answer a few seconds rather
        // than make the compass flicker between mast and satellite. See
        // RotorTenue: holding serves the display, never the command.
        val lu = fr.f4ioz.satcombo.rotor.RotorTenue.montrer(
            frais, rotorLu, rotorLuMs, maintenant)
        if (frais != null) rotorAt = frais
        // A connected mast holds the antenna: the compass shows it, not the
        // phone. Only the aim source changes — same colours, trace, error.
        val visee = lu?.let { fr.f4ioz.satcombo.rotor.RotorMath.antennaAim(it) }
        _ui.value = u.rot { copy(rotorActualAz = lu?.azDeg, rotorActualEl = lu?.elDeg,
            rotorAimAz = visee?.azDeg,
            rotorAimEl = if (u.rotorAzOnly) null else visee?.elDeg,
            rotorLastSent = rotorSerial?.lastSent ?: u.rotorLastSent,
            rotorLastReply = rotorSerial?.lastReply ?: u.rotorLastReply) }
        if (!u.rotorEnabled) return
        val pos = u.livePosition
        if (pos == null) {
            _ui.value = _ui.value.rot { copy(rotorTargetAz = null, rotorTargetEl = null,
                rotorStatus = t("rotor_no_target")) }
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
            fr.f4ioz.satcombo.rotor.RotorMath.prePositionDue(
                System.currentTimeMillis(), trackedPassAos.takeIf { it > 0L }, u.rotorPreAos)
        if (avance != u.rotorPrePositioning) {
            _ui.value = _ui.value.rot { copy(rotorPrePositioning = avance) }
        }
        val aim = if (avance)
            fr.f4ioz.satcombo.rotor.RotorMath.park(
                fr.f4ioz.satcombo.rotor.RotorMath.clampAz(plan!!.startAzDeg, limits),
                maxOf(0.0, u.rotorMinEl.toDouble()), limits)
        else if (garage)
            fr.f4ioz.satcombo.rotor.RotorMath.park(
                u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), limits)
        else fr.f4ioz.satcombo.rotor.RotorMath.follow(
            pos.azimuthDeg, pos.elevationDeg, rotorCmd, limits, rotorWasFlipped)
        if (aim == null) {
            // An impossible park stays impossible: do not clamp a position the
            // operator chose, tell him.
            _ui.value = _ui.value.rot { copy(rotorOutOfRange = true,
                rotorTargetAz = null, rotorTargetEl = null) }
            return
        }
        // Beyond the end stop the mast does not unwind: it waits as close as
        // possible and the error is shown in degrees. A mast pointing elsewhere
        // silently is worse than one that did not move.
        _ui.value = _ui.value.rot { copy(rotorOutOfRange = false, rotorErrorDeg = aim.errorDeg,
            rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorFlipped = aim.flipped) }
        if (!garage) rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
        if (!fr.f4ioz.satcombo.rotor.RotorMath.needsMove(aim, rotorAt, u.rotorDeadband.toDouble()))
            return
        rotorWasFlipped = aim.flipped
        val cmd = fr.f4ioz.satcombo.rotor.RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop)
        // `lu` may be a held position three seconds old — not a reading.
        // Without a real read, assume the mast went where it was told.
        if (d.moveTo(cmd, aim.elDeg) && frais == null)
            rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
    }

    // ===================== live satellite position =====================

    private var trackingFor: Int? = null
    private fun trackLive(sat: TleEntry, obs: Observer) {
        trackingFor = sat.catalogNumber
        viewModelScope.launch {
            while (trackingFor == sat.catalogNumber) {
                val pos = withContext(Dispatchers.Default) {
                    runCatching { predictor.positionAt(sat, obs, System.currentTimeMillis()) }.getOrNull()
                }
                if (_ui.value.selected?.catalogNumber == sat.catalogNumber) {
                    val trail = _ui.value.trail.toMutableList()
                    if (pos != null && pos.elevationDeg >= 0) {
                        trail.add(pos.azimuthDeg to pos.elevationDeg)
                        if (trail.size > 60) trail.removeAt(0)
                    }
                    _ui.value = _ui.value.copy(livePosition = pos, trail = trail)
                    refreshPassTrack(sat, obs) // switch arc when the shown pass ends
                }
                delay(1000)
            }
        }
    }
}
