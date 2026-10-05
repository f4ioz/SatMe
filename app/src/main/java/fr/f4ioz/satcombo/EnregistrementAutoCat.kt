package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.StationCheck
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every pass recorded under CAT (option, Settings › Recording): the recorder
 * armed for the coming passes of the satellite chosen while the rig is under
 * CAT, once its audio input has been listened to. Out of the view model; it
 * only arms the recorder (nothing transmits, nothing is written to the rig).
 */
class EnregistrementAutoCat(
    private val app: Application,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val predictor: PassPredictor,
    private val ui: () -> UiState,
    private val observateur: () -> Observer,
    /** Two seconds of the audio input: its levels, or why it could not listen. */
    private val mesureAudio: suspend () -> Pair<StationCheck.Niveaux?, String?>,
    private val activeEnregistreur: () -> Unit,
    private val locator: () -> String,
    /** The transmitter chosen for the satellite on its page. */
    private val transpondeurChoisi: () -> String
) {
    /** Where the automatic recording stands, for its card in the settings. */
    val etat = MutableStateFlow("")
    /** The satellite it is armed for; 0 = not armed by it. */
    @Volatile var arme: Int = 0
        private set
    /** The transmitter chosen when it armed (for the journal). */
    @Volatile var transpondeur: String = ""
        private set
    private var job: kotlinx.coroutines.Job? = null

    /** Automatic SSTV takes the recorder over. */
    fun cedeASstv() { arme = 0 }

    /** Follows the rig being connected and the satellite chosen; armed again when its windows are used. */
    fun demarre(flux: StateFlow<UiState>) {
        scope.launch {
            var avant: Pair<Boolean, Int?>? = null
            flux.collect { u ->
                val cle = u.catConnected to u.selected?.catalogNumber
                if (cle != avant) { avant = cle; runCatching { verifie() } }
            }
        }
        scope.launch {
            fr.f4ioz.satcombo.audio.RecorderService.fenetres.collect { f ->
                // Its windows all used: armed again for the next ones.
                if (f.isEmpty() && arme != 0) {
                    arme = 0
                    delay(10_000)
                    runCatching { verifie() }
                }
            }
        }
    }

    fun actif(): Boolean = settings.enregAutoCat
    fun setActif(on: Boolean) {
        settings.enregAutoCat = on
        if (on) verifie(force = true)
        else {
            if (arme != 0) fr.f4ioz.satcombo.audio.RecorderService.desarme(app)
            arme = 0
            etat.value = ""
        }
    }

    /**
     * With the option on and the rig under CAT: the recorder armed for the
     * coming passes of the satellite chosen (5 s before AOS to 5 s after LOS),
     * once its audio input has been listened to for 2 s. Disarmed when CAT
     * goes; armed again for another satellite. Automatic SSTV, when armed,
     * has the recorder: left alone.
     */
    fun verifie(force: Boolean = false) {
        if (!settings.enregAutoCat) return
        job?.cancel()
        job = scope.launch {
            val ctx = app
            val R = fr.f4ioz.satcombo.audio.RecorderService
            val u = ui()
            if (R.sstvArmee) {
                etat.value = t("enreg_auto_sstv"); return@launch
            }
            if (!u.catConnected) {
                if (arme != 0) { R.desarme(ctx); arme = 0 }
                etat.value = t("enreg_auto_attente_cat"); return@launch
            }
            val sat = u.selected ?: run { etat.value = t("enreg_auto_choisir_sat"); return@launch }
            if (!force && arme == sat.catalogNumber && R.fenetres.value.isNotEmpty()) return@launch
            val obs = observateur()
            val maintenant = System.currentTimeMillis()
            val passages = withContext(Dispatchers.Default) {
                runCatching { predictor.upcomingPasses(sat, obs, maintenant - 15 * 60_000L, 24, u.minElevDeg.toDouble()) }
                    .getOrDefault(emptyList())
            }
            val f = fr.f4ioz.satcombo.domain.EnregistrementAuto.fenetres(passages.map { it.aosEpochMs to it.losEpochMs }, maintenant)
            if (f.isEmpty()) { etat.value = tf("enreg_auto_aucun", sat.name); return@launch }
            // The input listened to first (not while it records: it would be its own pass).
            val audio = if (u.recording) null else mesureAudio()
            val juge = fr.f4ioz.satcombo.domain.EnregistrementAuto.juge(audio?.first?.rmsDbfs, audio?.first?.creteDbfs)
            val mot = when {
                audio == null -> ""
                juge == fr.f4ioz.satcombo.domain.EnregistrementAuto.Audio.ABSENTE -> t(audio.second ?: "rd_test_audio_impossible")
                juge == fr.f4ioz.satcombo.domain.EnregistrementAuto.Audio.SILENCE -> tf("enreg_auto_silence", "%.0f".format(audio.first!!.rmsDbfs))
                juge == fr.f4ioz.satcombo.domain.EnregistrementAuto.Audio.SATURE -> tf("enreg_auto_sature", "%.0f".format(audio.first!!.creteDbfs))
                else -> tf("enreg_auto_audio_ok", "%.0f".format(audio.first!!.rmsDbfs))
            }
            // No sound card where one is wanted: nothing worth recording.
            if (audio != null && juge == fr.f4ioz.satcombo.domain.EnregistrementAuto.Audio.ABSENTE) {
                if (arme != 0) { R.desarme(ctx); arme = 0 }
                etat.value = mot + " " + t("enreg_auto_pas_arme"); return@launch
            }
            if (!ui().recorderEnabled) activeEnregistreur()
            arme = sat.catalogNumber
            transpondeur = transpondeurChoisi()
            R.arme(ctx, sat.name, f, ui().recorderSource, ui().recorderUnprocessed, locator(),
                avanceMs = fr.f4ioz.satcombo.domain.EnregistrementAuto.AVANT_MS, par = R.PAR_CAT)
            val h = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).apply {
                if (ui().useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") }
            etat.value = tf("enreg_auto_arme", f.size, sat.name, h.format(java.util.Date(f.first().first + fr.f4ioz.satcombo.domain.EnregistrementAuto.AVANT_MS))) +
                (if (mot.isNotBlank()) "\n" + mot else "")
        }
    }
}
