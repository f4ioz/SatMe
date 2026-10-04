package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The APRS station, out of the view model: its settings (mode, SSID, level,
 * work), the sender and the message numbers, the position sent, the frame's
 * audio checked by decoding it back, the test on the speaker, the KISS radio
 * (TH-D72…) and the FT3D. Sending through the IC-9700 (CAT, keying) and its
 * listening stay with the view model, which drives the rig.
 */
class AprsStation(
    private val app: Application,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val predictor: PassPredictor,
    private val ui: () -> UiState,
    private val iss: () -> TleEntry?,
    /** The work mode changed: the IC-9700's listening follows (view model). */
    private val travailChange: suspend () -> Unit
) {
    /** What the last transmit or test gave, for the APRS page (not in `UiState`). */
    val envoi = kotlinx.coroutines.flow.MutableStateFlow("")
    /** The last frame sent (any way): the rate limit is shared. */
    var derniereMs = 0L

    fun ft3dCle(): String = settings.aprsFt3dCle
    fun ft3dVitesse(): Int = settings.aprsFt3dVitesse
    fun ft3dConnecte(cle: String, vitesse: Int) {
        settings.aprsFt3dCle = cle; settings.aprsFt3dVitesse = vitesse
        scope.launch { fr.f4ioz.satcombo.aprs.RecepteurWaypoints.connecte(app, cle, vitesse) }
    }
    fun ft3dDeconnecte() { fr.f4ioz.satcombo.aprs.RecepteurWaypoints.deconnecte() }

    fun mode(): String = settings.aprsMode
    fun setMode(m: String) { settings.aprsMode = m }

    fun ssid(): Int = settings.aprsSsid
    fun setSsid(v: Int) { settings.aprsSsid = v }
    fun niveau(): Float = settings.aprsNiveau
    fun setNiveau(v: Float) { settings.aprsNiveau = v }

    /** The sender: the callsign from the settings, with the chosen SSID. */
    fun source(): String = settings.callsign.trim().uppercase().let {
        if (settings.aprsSsid > 0 && it.isNotEmpty()) "$it-${settings.aprsSsid}" else it
    }

    /** Next message number (1..999), so an ack can be matched. */
    fun numeroSuivant(): String {
        val n = settings.aprsNumero % 999 + 1
        settings.aprsNumero = n
        return n.toString()
    }

    /**
     * The frame's audio, checked by decoding it back before anything is
     * played: a frame that does not come out of our own decoder does not go
     * on the air either.
     */
    fun audio(t: fr.f4ioz.satcombo.aprs.Trame): ShortArray? {
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
    fun essai(trame: fr.f4ioz.satcombo.aprs.Trame, fichier: Boolean, partage: (java.io.File) -> Unit) {
        scope.launch {
            val pcm = withContext(Dispatchers.Default) { audio(trame) }
            if (pcm == null) { envoi.value = t("aprs_tx_controle"); return@launch }
            if (fichier) {
                // With the SSTV test cards (shared the same way, reachable over USB).
                val f = withContext(Dispatchers.IO) {
                    val d = app.getExternalFilesDir("mires")!!.apply { mkdirs() }
                    fr.f4ioz.satcombo.aprs.SortieAudio.wav(pcm, java.io.File(d, "SatMe_APRS_essai.wav"))
                }
                envoi.value = t("aprs_tx_wav")
                partage(f)
            } else {
                envoi.value = t("aprs_tx_hp_en_cours")
                val ok = fr.f4ioz.satcombo.aprs.SortieAudio.joue(pcm, null)
                envoi.value = if (ok) t("aprs_tx_hp_fini") else t("aprs_tx_audio")
            }
        }
    }

    // ------------------------------------------------ KISS radio (TH-D72…)

    fun kissCle(): String = settings.aprsKissCle
    fun kissVitesse(): Int = settings.aprsKissVitesse

    /**
     * Connects and switches straight to KISS, tuned as chosen: without KISS
     * the radio decodes for itself and nothing reaches the phone.
     */
    fun kissConnecte(cle: String, vitesse: Int) {
        settings.aprsKissCle = cle
        settings.aprsKissVitesse = vitesse
        scope.launch {
            if (fr.f4ioz.satcombo.aprs.TncKiss.connecte(app, cle, vitesse)) {
                fr.f4ioz.satcombo.aprs.TncKiss.passeEnKiss(kissFrequenceVoulue())
                kissChangeMs = System.currentTimeMillis()
            }
        }
    }

    fun kissPasseEnKiss() {
        scope.launch { fr.f4ioz.satcombo.aprs.TncKiss.passeEnKiss(kissFrequenceVoulue()) }
    }

    fun travail(): String = settings.aprsTravail
    /** A new choice is applied at once: KISS radio retuned, IC-9700 listening moved. */
    fun setTravail(v: String) {
        settings.aprsTravail = v
        scope.launch { runCatching { travailChange() } }
        val hz = kissFrequenceVoulue() ?: return
        if (!fr.f4ioz.satcombo.aprs.TncKiss.etat.value.connecte) return
        scope.launch {
            fr.f4ioz.satcombo.aprs.TncKiss.regleFrequence(hz)
            kissChangeMs = System.currentTimeMillis()
        }
    }

    /** The ISS is up, or rises within a minute. */
    fun issEnVue(): Boolean {
        val sat = iss() ?: return false
        val obs = ui().observer ?: return false
        val now = System.currentTimeMillis()
        return listOf(now, now + 60_000L).any {
            (runCatching { predictor.positionAt(sat, obs, it).elevationDeg }.getOrNull() ?: -90.0) > 0.0
        }
    }

    /** Where the KISS radio should be now, or null to leave it. */
    private fun kissFrequenceVoulue(): Long? {
        val base = when (settings.aprsTravail) {
            "TERRE" -> 144_800_000L
            "ISS" -> 145_825_000L
            "AUTO" -> if (issEnVue()) 145_825_000L else 144_800_000L
            else -> return null
        }
        return if (base == 145_825_000L && settings.aprsKissDoppler) base + palierDoppler(base) else base
    }

    /**
     * The ISS Doppler at [hz], in whole 5 kHz steps (the TH-D72's finest
     * grid for 145.825): +5 kHz early in the pass, 0 around its highest
     * point, −5 kHz at the end. Zero when the ISS is down.
     */
    private fun palierDoppler(hz: Long): Long {
        val sat = iss() ?: return 0L
        val obs = ui().observer ?: return 0L
        val p = runCatching { predictor.positionAt(sat, obs, System.currentTimeMillis()) }.getOrNull() ?: return 0L
        if (p.elevationDeg <= 0.0) return 0L
        val decalage = -hz * p.rangeRateKmS / 299_792.458
        return kotlin.math.round(decalage / 5_000.0).toLong() * 5_000L
    }

    fun kissDoppler(): Boolean = settings.aprsKissDoppler
    fun setKissDoppler(v: Boolean) { settings.aprsKissDoppler = v }

    private var kissChangeMs = 0L

    /**
     * Automatic frequency: 145.825 MHz while the ISS is up, 144.800 MHz
     * otherwise. A switch costs some 5 s of deafness, so at most one a minute.
     */
    suspend fun kissTic() {
        if (settings.aprsMode != "KISS") return
        if (settings.aprsTravail != "AUTO" && !(settings.aprsTravail == "ISS" && settings.aprsKissDoppler)) return
        val k = fr.f4ioz.satcombo.aprs.TncKiss.etat.value
        if (!k.connecte || !k.initialise || k.frequenceHz == null) return
        val hz = kissFrequenceVoulue() ?: return
        if (k.frequenceHz == hz) return
        if (System.currentTimeMillis() - kissChangeMs < 60_000L) return
        kissChangeMs = System.currentTimeMillis()
        fr.f4ioz.satcombo.aprs.TncKiss.regleFrequence(hz)
    }

    fun kissDeconnecte() {
        scope.launch(Dispatchers.IO) { fr.f4ioz.satcombo.aprs.TncKiss.deconnecte() }
    }

    /**
     * One frame to the KISS radio, which keys and returns to receive by
     * itself. SatMe cannot read its frequency in KISS mode: the operator
     * confirms it; the rest of the checks are the same as for the IC-9700.
     */
    fun emetKiss(trame: fr.f4ioz.satcombo.aprs.Trame, frequenceConfirmee: Boolean) {
        scope.launch {
            if (!fr.f4ioz.satcombo.aprs.TncKiss.etat.value.connecte) {
                envoi.value = t("aprs_kiss_non_connecte"); return@launch
            }
            // Frequency read (or set) before KISS (TH-D72 "FO"), when there is one: it must be right too.
            val lue = fr.f4ioz.satcombo.aprs.TncKiss.etat.value.frequenceHz
            // Set by SatMe itself on an APRS frequency: no need for the operator to vouch for it.
            val connue = lue != null && fr.f4ioz.satcombo.aprs.AprsEmission.FENETRES.any { lue in it }
            if (!frequenceConfirmee && !connue) { envoi.value = t("aprs_kiss_confirmer"); return@launch }
            val raison = fr.f4ioz.satcombo.aprs.AprsEmission.refus(trame.source.indicatif,
                System.currentTimeMillis(), derniereMs, lue ?: 145_825_000L, 0x05, false, false)
            if (raison != null) {
                envoi.value = if (raison == "frequence") tf("aprs_tx_refus_frequence",
                    "%.4f".format(java.util.Locale.US, (lue ?: 0L) / 1e6)) else t("aprs_tx_refus_$raison")
                return@launch
            }
            if (fr.f4ioz.satcombo.aprs.TncKiss.envoie(trame)) {
                derniereMs = System.currentTimeMillis()
                fr.f4ioz.satcombo.aprs.AprsHub.ajouteEmis(app, trame)
                envoi.value = t("aprs_kiss_ok")
            } else envoi.value = t("aprs_kiss_echec")
        }
    }

    /** The path for what we send now: the ISS digipeater, or the terrestrial network. */
    fun cheminParDefaut(): List<String> = when (settings.aprsTravail) {
        "ISS" -> listOf("ARISS")
        "TERRE" -> listOf("WIDE1-1", "WIDE2-1")
        else -> if (issEnVue()) listOf("ARISS") else listOf("WIDE1-1", "WIDE2-1")
    }

    /** Working the ISS right now (by choice, or in Auto while it is up). */
    fun surIss(): Boolean = when (settings.aprsTravail) {
        "ISS" -> true; "TERRE" -> false; else -> issEnVue()
    }

    /**
     * Where a position is sent from: the QTH, or — with the approximate
     * position option — the QTH shifted by a fixed random offset under 500 m.
     */
    fun positionEmise(): Pair<Double, Double>? {
        val o = ui().observer ?: return null
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

    fun positionFloue(): Boolean = settings.aprsPositionFloue
    fun setPositionFloue(v: Boolean) { settings.aprsPositionFloue = v }
    /** Draws a new offset (the next position sent uses it). */
    fun nouveauFlou() { settings.aprsFlouNordM = Float.NaN; settings.aprsFlouEstM = Float.NaN }
}
