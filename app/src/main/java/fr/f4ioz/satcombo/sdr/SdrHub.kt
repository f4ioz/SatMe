/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sdr

// Le choix « PLL ou logiciel » vit dans le domaine, sans une ligne d'Android,
// pour qu'un essai puisse le rejouer seconde par seconde.
import fr.f4ioz.satcombo.domain.DopplerTuner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.naman14.androidlame.LameBuilder
import fr.f4ioz.satcombo.audio.EncodeurMp3
import fr.f4ioz.satcombo.sonde.SondeHub
import fr.f4ioz.satcombo.sstv.SstvHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Le point de rencontre entre la clé RTL-SDR et le reste de SatMe.
 *
 * Un seul objet, comme [SstvHub], parce qu'il n'y a jamais qu'un récepteur : la
 * clé est branchée ou elle ne l'est pas. Le fil de lecture USB vit ici, il
 * démodule, sort le son au haut-parleur du téléphone (ou au casque s'il est
 * branché), nourrit le moteur SSTV et écrit un MP3
 * dans le même dossier que les enregistrements de passage — de sorte qu'un
 * enregistrement fait à la clé se redécode plus tard exactement comme un
 * enregistrement fait au micro.
 *
 * L'accord suit le Doppler : [setCenter] est appelé une fois par seconde par le
 * ViewModel et le fil de lecture reprogramme la PLL entre deux blocs.
 */
object SdrHub {

    data class SdrState(
        /** Une clé connue est branchée (et l'autorisation est accordée). */
        val connected: Boolean = false,
        val deviceName: String? = null,
        /** Le fil de lecture tourne. */
        val running: Boolean = false,
        /** Fréquence de repos, sans Doppler. */
        val restHz: Long = 145_800_000L,
        /** Fréquence réellement affichée à la clé (repos + Doppler). */
        val centerHz: Long = 145_800_000L,
        /** Correction Doppler appliquée, en hertz. */
        val dopplerHz: Long = 0L,
        /** Débit d'échantillonnage réel de la clé. */
        val sampleRate: Double = 0.0,
        /** Niveau du signal en dBFS, rafraîchi quelques fois par seconde. */
        val levelDb: Float = -120f,
        /** Gain manuel en dixièmes de dB, null = automatique. */
        val gainTenthDb: Int? = null,
        /** Mode de démodulation en cours. */
        val mode: RxMode = RxMode.NFM,
        /** Largeur de canal, en hertz. Zéro = au mode de décider. */
        val bandwidthHz: Int = 0,
        /** Seuil du silencieux, en dBFS. -120 = silencieux coupé. */
        val squelchDb: Int = -120,
        /** Accord fin logiciel par rapport à la fréquence de la clé, en hertz. */
        val offsetHz: Int = 0,
        /** Le recentrage automatique tourne en continu. */
        val autoTune: Boolean = false,
        /**
         * Date du dernier recentrage automatique abouti, en millisecondes
         * système ; zéro tant qu'il n'y en a pas eu. L'écran s'en sert pour
         * dire « recalé il y a tant de secondes » plutôt que de laisser
         * l'opérateur se demander si le bouton a fait quelque chose.
         */
        val tunedAtMs: Long = 0L,
        /** Largeur couverte par le spectre, en hertz. */
        val spanHz: Double = 0.0,
        /** Sortie son active (haut-parleur, ou casque s'il est branché). */
        val audio: Boolean = true,
        val recording: Boolean = false,
        val recordFile: String? = null,
        val sstv: Boolean = false,
        val satName: String? = null,
        /** Mégaoctets lus depuis le démarrage, utile pour voir que ça vit. */
        val mbRead: Float = 0f,
        /** Dernier message d'erreur, effacé au démarrage suivant. */
        val error: String? = null,
        /** L'autorisation USB a été demandée et on attend la réponse. */
        val awaitingPermission: Boolean = false,
        /**
         * L'étage d'entrée de la clé sature. Le symptôme trompe : le souffle
         * disparaît, l'écran montre un signal fort, et il ne sort rien. Le
         * remède n'est pas dans l'application — gain plus bas, antenne plus
         * loin de l'émetteur, ou atténuateur.
         */
        val clipping: Boolean = false,
        /** Crête audio de sortie, 0 à 1. Vu-mètre de la modulation reçue. */
        val afLevel: Float = 0f,
        /**
         * Part du Doppler encaissée en logiciel, en hertz, sans toucher à la
         * PLL. C'est ce chiffre qui explique pourquoi la fréquence affichée
         * bouge alors que le tuner, lui, ne bouge pas.
         */
        val dopplerFineHz: Long = 0L,
        /**
         * Nombre de reprogrammations de la PLL depuis le démarrage. Un passage
         * bien mené en compte une : celle du départ.
         */
        val pllWrites: Int = 0
    )

    private val _state = MutableStateFlow(SdrState())
    val state: StateFlow<SdrState> = _state

    /**
     * Dernière trame de spectre, en dB pleine échelle, rangée de la fréquence
     * la plus basse à la plus haute. Un tableau neuf est publié à chaque trame
     * (une dizaine par seconde) : c'est ce que Compose sait observer, et cela
     * évite qu'un tableau soit relu pendant qu'il est réécrit.
     */
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum

    /**
     * Le panorama : la même chose, mais sur toute la largeur reçue par la clé
     * et sans décimation — 1 058 400 Hz en seize mille raies.
     *
     * Il n'est calculé que si quelqu'un le demande, par [wantPanorama]. Une
     * FFT de seize mille points à chaque trame coûte quatre fois celle du
     * spectre ordinaire, et l'écran qui s'en sert est le seul de
     * l'application : la faire tourner pendant une réception de radiosonde
     * serait du courant dépensé pour rien.
     *
     * Reste vide tant qu'aucune trame n'a été calculée, ce qui est aussi
     * l'état après un arrêt : un panorama figé sur l'écran laisserait croire
     * que la clé écoute encore.
     */
    private val _panorama = MutableStateFlow(FloatArray(0))
    val panorama: StateFlow<FloatArray> = _panorama

    /**
     * Faut-il alimenter le panorama ? Écrit par l'écran QO-100 quand il
     * s'ouvre, remis à faux quand il se ferme.
     */
    @Volatile
    private var wantPanorama = false

    /** Voir [wantPanorama]. Sans effet quand la clé ne tourne pas. */
    fun setPanorama(on: Boolean) {
        wantPanorama = on
        if (!on) _panorama.value = FloatArray(0)
    }

    /** Largeur couverte par le panorama, en hertz. Fixe, c'est le débit de la clé. */
    val PANORAMA_SPAN_HZ: Double = Dsp.RTL_RATE.toDouble()

    /** Taille d'un bloc USB : celle imposée par usbfs, voir [RtlSdr.XFER]. */
    private const val BLOCK = RtlSdr.XFER

    /**
     * Nombre de transferts USB en vol.
     *
     * À 1 058 400 échantillons par seconde, un bloc de 16 ko dure 7,7 ms :
     * seize blocs font un quart de seconde d'avance, de quoi absorber un
     * ramasse-miettes ou un rendu de cascade sans perdre un échantillon.
     */
    private const val STREAM_DEPTH = 16

    /**
     * Attente maximale d'un bloc USB. C'est aussi le temps que met la boucle à
     * remarquer un arrêt demandé, donc on le garde court.
     */
    private const val READ_MS = 250L

    /** Patience de [stopWorker] avant de forcer la fermeture de la clé. */
    private const val WORKER_JOIN_MS = 1500L

    private var sdr: RtlSdr? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    /**
     * Le fil de lecture n'a pas encore rendu la clé.
     *
     * `running` dit « il faut continuer », ce drapeau dit « il tourne encore ».
     * Les deux ne se valent pas : entre le moment où [stop] baisse `running` et
     * celui où la boucle a vraiment fermé l'AudioTrack et la clé, il s'écoule
     * quelques centaines de millisecondes. Ouvrir un second récepteur dans cet
     * intervalle donnait deux sorties son et deux connexions USB sur la même
     * clé — le son haché du deuxième démarrage.
     */
    @Volatile private var workerAlive = false
    /**
     * Numéro de la session de réception. Un fil de lecture qui appartient à une
     * session périmée n'a plus le droit d'écrire dans l'état ni de nourrir le
     * décodeur SSTV : il finit de se fermer en silence.
     */
    @Volatile private var generation = 0
    @Volatile private var pendingHz = 0L
    @Volatile private var wantAudio = true
    @Volatile private var wantMode = RxMode.NFM
    @Volatile private var wantBandwidth = 0
    @Volatile private var wantSquelch = -120
    @Volatile private var wantOffset = 0

    /**
     * Le décalage fin est la somme de deux volontés qu'il ne faut jamais
     * confondre : celle de l'opérateur, qui a posé le doigt sur la cascade
     * pour se caler sur une station, et celle du suivi Doppler, qui glisse
     * tout seul. Les additionner au dernier moment permet au suivi de
     * travailler sans jamais effacer la retouche manuelle — et c'est
     * exactement ce qu'on reprochait à l'ancienne version, qui écrasait l'un
     * avec l'autre.
     */
    @Volatile private var userOffset = 0
    @Volatile private var dopplerFine = 0
    @Volatile private var pllMoveCount = 0

    /**
     * Désaccentuation FM. On l'ouvre en écoute phonie et on la coupe pour la
     * télémétrie : sur une radiosonde elle arrondit les fronts du signal, et
     * plus rien ne se décode.
     */
    @Volatile private var wantDeemph = false

    /** Demande d'accord automatique sur la raie la plus forte. */
    @Volatile private var wantPeak = false
    @Volatile private var peakFromHz = -40_000.0
    @Volatile private var peakToHz = 40_000.0

    /**
     * Demande de recentrage sur le centre de gravité du signal — l'accord
     * automatique des modulations sans porteuse, radiosondes comprises.
     */
    @Volatile private var wantCentroid = false

    /** Le recentrage se refait tout seul, à la cadence de [AUTO_TUNE_MS]. */
    @Volatile private var autoCentroid = false

    /** Demi-largeur de la recherche, en hertz. */
    @Volatile private var centroidSearchHz = 25_000.0

    /**
     * Décalage audio visé par le calage, en hertz, et milieu de la recherche.
     *
     * Zéro pour le recentrage automatique historique : on veut la porteuse au
     * milieu. Non nul pour la bande latérale, où la voix doit tomber dans la
     * bande passante et non à cheval sur zéro — voir [AccordFin.cibleVoixHz].
     */
    @Volatile private var centroidCibleHz = 0
    @Volatile private var centroidAutourDuPoint = false

    /**
     * Intervalle entre deux recentrages automatiques.
     *
     * Deux secondes : assez lent pour qu'un décodage en cours ne soit pas
     * dérangé par un accord qui bouge sous lui, assez vif pour rattraper la
     * dérive d'un quartz qui chauffe ou le Doppler d'une sonde qui passe à la
     * verticale. Le recentrage ne s'additionne pas — il pose une valeur
     * absolue — donc le répéter sur un signal déjà accordé ne fait rien.
     */
    private const val AUTO_TUNE_MS = 2_000L

    private var appCtx: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var autoStart: (() -> Unit)? = null

    // ------------------------------------------------------------ détection

    /** Une clé connue est-elle branchée ? Ne dit rien de l'autorisation. */
    fun devicePresent(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return runCatching { RtlSdr.find(um) }.getOrNull()
    }

    fun deviceLabel(d: UsbDevice): String {
        val name = d.productName ?: "RTL-SDR"
        return "$name (%04x:%04x)".format(d.vendorId, d.productId)
    }

    /**
     * Met en place l'écoute de la réponse à la demande d'autorisation USB.
     * À appeler une fois, quand l'écran SDR apparaît.
     */
    @Synchronized
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != RtlSdr.ACTION_USB_PERMISSION) return
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                _state.value = _state.value.copy(awaitingPermission = false)
                if (granted) {
                    val go = autoStart
                    autoStart = null
                    go?.invoke()
                } else {
                    autoStart = null
                    _state.value = _state.value.copy(error = "usb_denied")
                }
            }
        }
        val filter = IntentFilter(RtlSdr.ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
        receiver = r

        // Le débranchement n'existe pas dans le manifeste : Android ne le
        // diffuse qu'aux receveurs enregistrés à chaud. Sans lui, arracher la
        // clé laissait `running` à vrai, le bouton lecture devenait un bouton
        // mort et l'état affiché ne correspondait plus à rien.
        val u = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val d = i?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                when (i?.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        if (d == null || RtlSdr.isRtl(d)) onDeviceDetached()
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                        if (d != null && RtlSdr.isRtl(d)) onDeviceAttached(app, d)
                }
            }
        }
        val usbFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        // Diffusions système protégées : elles arrivent d'ailleurs que de nous,
        // donc RECEIVER_EXPORTED, contrairement à la réponse d'autorisation.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(u, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(u, usbFilter)
        }
        usbReceiver = u

        refreshPresence(app)
    }

    @Synchronized
    fun detach() {
        val app = appCtx ?: return
        val r = receiver
        if (r != null) runCatching { app.unregisterReceiver(r) }
        receiver = null
        val u = usbReceiver
        if (u != null) runCatching { app.unregisterReceiver(u) }
        usbReceiver = null
    }

    /**
     * Appelé quand le système signale une clé fraîchement branchée.
     *
     * Le filtre du manifeste laisse aussi passer les interfaces série du CAT :
     * on ne réagit qu'à une clé RTL-SDR, et on n'ouvre rien — c'est l'opérateur
     * qui décide de démarrer la réception.
     */
    fun onDeviceAttached(ctx: Context, dev: UsbDevice?) {
        attach(ctx)
        if (dev != null && RtlSdr.isRtl(dev)) {
            _state.value = _state.value.copy(
                deviceName = deviceLabel(dev), error = null, awaitingPermission = false)
        }
        refreshPresence(ctx)
    }

    /**
     * La clé vient d'être arrachée du port USB.
     *
     * Tout s'arrête, et surtout l'état repart de zéro : l'autorisation USB est
     * révoquée par le système au débranchement, donc le rebranchement doit
     * repasser par la demande d'autorisation comme la première fois. Une
     * réception qui « ne fonctionne pas bien » après un rebranchement, c'était
     * ça : un drapeau resté à vrai sur une clé qui n'était plus là.
     */
    @Synchronized
    fun onDeviceDetached() {
        val wasRunning = running || workerAlive
        if (wasRunning) {
            stopWorker()
            if (_state.value.sstv) runCatching { SstvHub.stopLive() }
            _spectrum.value = FloatArray(0)
            _panorama.value = FloatArray(0)
        }
        autoStart = null
        _state.value = _state.value.copy(
            connected = false,
            deviceName = null,
            running = false,
            levelDb = -120f,
            sstv = false,
            awaitingPermission = false,
            error = if (wasRunning) "sdr_unplugged" else null)
    }

    /**
     * Présence physique de la clé, et rien d'autre.
     *
     * Autrefois ce champ valait « branchée *et* en réception », ce qui le
     * rendait faux au repos et faisait croire à l'écran qu'aucune clé n'était
     * là dès qu'on coupait la lecture.
     */
    fun refreshPresence(ctx: Context) {
        val d = devicePresent(ctx)
        _state.value = _state.value.copy(
            connected = d != null,
            deviceName = d?.let { deviceLabel(it) })
    }

    // ------------------------------------------------------------- démarrage

    /**
     * Ouvre la clé et lance la réception. [restHz] est la fréquence de repos du
     * satellite ; le Doppler est appliqué ensuite par [setCenter].
     *
     * Renvoie false immédiatement si l'autorisation USB manque : elle est alors
     * demandée, et la réception démarre toute seule dès que l'opérateur accepte.
     */
    @Synchronized
    fun start(
        ctx: Context,
        satName: String,
        restHz: Long,
        gainTenthDb: Int? = null,
        agc: Boolean = false,
        ppm: Int = 0,
        sstv: Boolean = false,
        record: Boolean = false,
        audio: Boolean = true,
        mode: RxMode = RxMode.NFM,
        bandwidthHz: Int = 0,
        squelchDb: Int = -120,
        offsetHz: Int = 0
    ): Boolean {
        if (running) return true
        // La session précédente n'a pas fini de rendre la clé : on l'attend
        // plutôt que d'ouvrir une seconde connexion USB sur le même matériel.
        if (workerAlive) {
            runCatching { worker?.join(1500) }
            if (workerAlive) { fail("sdr_busy"); return false }
        }
        val app = ctx.applicationContext
        appCtx = app
        val um = app.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (um == null) { fail("usb_unavailable"); return false }
        val dev = RtlSdr.find(um)
        if (dev == null) { fail("sdr_not_found"); return false }

        if (!um.hasPermission(dev)) {
            autoStart = {
                start(app, satName, restHz, gainTenthDb, agc, ppm, sstv, record, audio,
                    mode, bandwidthHz, squelchDb, offsetHz)
            }
            _state.value = _state.value.copy(
                awaitingPermission = true, error = null,
                deviceName = deviceLabel(dev))
            RtlSdr.requestPermission(app, dev)
            return false
        }

        val s = RtlSdr(app)
        if (!s.open(dev)) { fail(s.lastError ?: "sdr_open_failed"); return false }

        val rate = s.setSampleRate(Dsp.RTL_RATE)
        if (ppm != 0) s.setFreqCorrection(ppm)
        s.setAgc(agc)
        s.setGain(gainTenthDb)
        val tuned = s.setCenterFreq(restHz)
        // Le vidage du tampon n'est plus fait ici mais juste avant la mise en
        // file des transferts : entre les deux il s'écoulait le temps de créer
        // le fil, pendant lequel la FIFO du RTL2832 se remplissait sans lecteur
        // et débordait — un début de flux déjà en retard.

        sdr = s
        pendingHz = restHz
        wantAudio = audio
        wantMode = mode
        wantBandwidth = bandwidthHz
        wantSquelch = squelchDb
        userOffset = offsetHz
        dopplerFine = 0
        pllMoveCount = 0
        wantOffset = offsetHz
        running = true
        _state.value = SdrState(
            connected = true,
            deviceName = deviceLabel(dev),
            running = true,
            restHz = restHz,
            centerHz = if (tuned > 0) tuned else restHz,
            dopplerHz = 0L,
            sampleRate = rate,
            gainTenthDb = gainTenthDb,
            audio = audio,
            sstv = sstv,
            satName = satName,
            recording = record,
            mode = mode,
            bandwidthHz = bandwidthHz,
            squelchDb = squelchDb,
            offsetHz = offsetHz,
            // Le recentrage automatique est un réglage d'opérateur, pas de
            // session : il survit à un débranchement de clé.
            autoTune = autoCentroid,
            spanHz = Dsp.RTL_RATE.toDouble() / Dsp.DECIM_1)

        if (sstv) runCatching { SstvHub.startLive(app, Dsp.AUDIO_RATE, satName) }

        val recFile: File? = if (record) newRecordFile(app, satName) else null
        if (recFile != null) {
            _state.value = _state.value.copy(recordFile = recFile.name)
        }

        val gen = ++generation
        workerAlive = true
        worker = thread(name = "SdrReader", isDaemon = true) {
            runLoop(gen, s, satName, recFile)
        }
        return true
    }

    private fun newRecordFile(ctx: Context, satName: String): File {
        val fmt = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val safe = satName.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        return File(dir, "SatMe_${safe}_${fmt.format(Date())}.mp3")
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(
            running = false, connected = false, error = msg, awaitingPermission = false)
    }

    // --------------------------------------------------------------- boucle

    private fun runLoop(gen: Int, s: RtlSdr, satName: String, recFile: File?) {
        /** Cette session est-elle encore celle qui a la main ? */
        fun mine() = gen == generation
        val chain = RxChain()
        val iq = ByteArray(BLOCK)
        val pcm = ShortArray(chain.maxAudio(BLOCK))
        var track: AudioTrack? = null
        var playing = false
        var lame: com.naman14.androidlame.AndroidLame? = null
        var mp3Out: FileOutputStream? = null
        var mp3Buf: ByteArray? = null
        // Avons-nous pris l'encodeur unique ? Le `finally` a besoin de le
        // savoir : rendre un tour qu'on n'a pas pris libérerait celui d'un
        // autre, ce qui est précisément la panne qu'on ferme ici.
        var encodeurPris = false
        // On compare la consigne à la consigne, jamais à la fréquence obtenue :
        // le sigma-delta de la PLL rend un chiffre légèrement différent, et
        // comparer les deux ferait reprogrammer le tuner à chaque bloc.
        var appliedHz = pendingHz
        var achievedHz = _state.value.centerHz
        // Le Doppler encaissé en logiciel, et le compteur qui sert de preuve.
        var fineHz = 0L
        var pllWrites = 1   // celle du démarrage, déjà faite par [start]
        var bytes = 0L
        var lastUi = 0L
        var lastSpec = 0L
        var lastPan = 0L
        var lastTune = 0L
        var lastFrames = 0L
        var lastPanFrames = 0L
        var errors = 0
        var idle = 0

        try {
            // Le flux asynchrone : le noyau continue de remplir des tampons
            // pendant qu'on démodule le précédent. Sans lui, tout le temps de
            // calcul serait du temps où la clé n'est pas lue.
            s.resetBuffer()
            val streaming = s.startStream(STREAM_DEPTH)

            // La sortie son est ouverte dans tous les cas, quitte à la laisser
            // en pause : l'ouvrir en cours de route ferait un trou dans le
            // décodage SSTV, et c'est ce qui empêchait le bouton « Son » de
            // faire quoi que ce soit pendant une réception.
            track = runCatching { openTrack() }.getOrNull()
            if (wantAudio) { runCatching { track?.play() }; playing = true }

            // Il n'y a qu'un encodeur MP3 dans le processus — voir
            // [EncodeurMp3]. S'il est déjà pris, on renonce à l'enregistrement
            // mais on garde la réception : couper le SDR parce qu'un export de
            // mire tourne serait une punition sans rapport avec la faute. Le
            // témoin d'enregistrement passe au repos et l'écran le dit, plutôt
            // que d'afficher un enregistrement qui n'existe pas.
            if (recFile != null && !EncodeurMp3.prend(EncodeurMp3.SDR)) {
                _state.value = _state.value.copy(recording = false, error = "sdr_mp3_busy")
            } else if (recFile != null) {
                encodeurPris = true
                lame = LameBuilder()
                    .setInSampleRate(Dsp.AUDIO_RATE)
                    .setOutSampleRate(Dsp.AUDIO_RATE)
                    .setOutChannels(1)
                    .setOutBitrate(128)
                    .setQuality(5)
                    .setId3tagTitle(satName)
                    .setId3tagArtist("F4IOZ")
                    .setId3tagComment("SatMe RTL-SDR")
                    .build()
                mp3Out = FileOutputStream(recFile)
                mp3Buf = ByteArray((pcm.size * 1.25).toInt() + 7200)
            }

            while (running && mine()) {
                // Doppler. La PLL ne bouge plus à chaque seconde : tant que
                // l'écart tient dans le décalage fin, c'est le mélangeur
                // logiciel qui l'encaisse, et le tuner ne s'aperçoit de rien.
                // Quand elle doit bouger malgré tout, c'est entre deux blocs,
                // jamais pendant une lecture en cours.
                val want = pendingHz
                if (want > 0) {
                    val plan = DopplerTuner.plan(want, appliedHz, fineHz)
                    if (plan.retune) {
                        val got = s.setCenterFreq(plan.pllHz)
                        appliedHz = plan.pllHz
                        achievedHz = if (got > 0) got else plan.pllHz
                        pllWrites++
                    }
                    if (plan.fineHz != fineHz) {
                        fineHz = plan.fineHz
                        chain.dopplerFineHz = fineHz.toDouble()
                    }
                }

                // Réglages modifiables en cours de réception : le fil de
                // lecture les relit à chaque bloc plutôt que de se faire
                // interrompre, ce qui évite tout verrou dans la boucle chaude.
                if (chain.mode != wantMode) { chain.mode = wantMode; chain.reset() }
                val bw = wantBandwidth.toDouble()
                if (chain.bandwidthHz != bw) chain.bandwidthHz = bw
                val sq = wantSquelch.toFloat()
                if (chain.squelch.thresholdDb != sq) chain.squelch.thresholdDb = sq
                val off = wantOffset.toDouble()
                if (chain.offsetHz != off) chain.offsetHz = off
                if (chain.deemphasis != wantDeemph) chain.deemphasis = wantDeemph

                // Attente courte : c'est elle qui fixe le temps que met la
                // boucle à s'apercevoir qu'on lui a demandé de s'arrêter, donc
                // le temps que [stop] doit attendre avant de rendre la main.
                val n = if (streaming) s.readStream(iq, READ_MS) else s.read(iq, READ_MS.toInt())
                if (n == 0) {
                    // Rien reçu dans le délai : la clé est muette, pas fâchée.
                    idle++
                    if (idle > 60) { failIf(mine(), "sdr_read_failed"); break }
                    continue
                }
                if (n < 0) {
                    errors++
                    if (errors > 20) { failIf(mine(), "sdr_read_failed"); break }
                    continue
                }
                errors = 0
                idle = 0
                bytes += n

                val nowSpec = System.currentTimeMillis()
                val wantSpec = nowSpec - lastSpec >= 90L
                // Le panorama tourne trois fois moins vite que le spectre :
                // une FFT de seize mille points est quatre fois plus chère, et
                // un transpondeur ne change pas de peuplement en trois cents
                // millisecondes.
                val wantPan = wantPanorama && nowSpec - lastPan >= 300L
                val produced = chain.process(
                    iq, n, pcm, feedSpectrum = wantSpec, feedPanorama = wantPan)
                if (wantSpec) lastSpec = nowSpec
                if (wantPan) lastPan = nowSpec
                if (chain.panorama.frames != lastPanFrames) {
                    lastPanFrames = chain.panorama.frames
                    if (mine() && wantPanorama) {
                        _panorama.value = chain.panorama.magDb.copyOf()
                    }
                }
                // Une trame de spectre couvre maintenant plusieurs blocs USB
                // d'affilée : on publie quand elle est finie, pas quand on a
                // décidé d'en commencer une.
                if (chain.spectrum.frames != lastFrames) {
                    lastFrames = chain.spectrum.frames
                    if (mine()) _spectrum.value = chain.spectrum.magDb.copyOf()
                    if (wantPeak) {
                        wantPeak = false
                        val p = chain.peakOffsetHz(peakFromHz, peakToHz)
                        val hz = Math.round(p).toInt().coerceIn(-80_000, 80_000)
                        // Le recentrage vise une position absolue ; on en
                        // retire la part Doppler pour ne réécrire que la
                        // retouche de l'opérateur.
                        userOffset = hz - dopplerFine
                        wantOffset = hz
                        if (mine()) _state.value = _state.value.copy(offsetHz = hz)
                    }
                    // Recentrage sur le centre de gravité. Le chiffre rendu est
                    // absolu par rapport à l'accord de la clé : on l'écrit, on
                    // ne l'ajoute pas. Zéro veut dire « rien au-dessus du bruit »
                    // et l'on garde alors l'accord en cours plutôt que de sauter
                    // au milieu de la bande sur un coup de silence.
                    val autoDue = autoCentroid && nowSpec - lastTune >= AUTO_TUNE_MS
                    if (wantCentroid || autoDue) {
                        wantCentroid = false
                        lastTune = nowSpec
                        val autour =
                            if (centroidAutourDuPoint) (userOffset + dopplerFine).toDouble()
                            else 0.0
                        val c = chain.centroidOffsetHz(
                            searchHz = centroidSearchHz, centreHz = autour)
                        if (c != 0.0) {
                            val hz = fr.f4ioz.satcombo.domain.AccordFin
                                .accordVise(c, centroidCibleHz)
                                .toInt().coerceIn(-80_000, 80_000)
                            userOffset = hz - dopplerFine
                            wantOffset = hz
                            if (mine()) _state.value = _state.value
                                .copy(offsetHz = hz, tunedAtMs = nowSpec)
                        }
                    }
                }
                if (produced > 0) {
                    val tr = track
                    if (tr != null) {
                        if (wantAudio) {
                            if (!playing) { runCatching { tr.play() }; playing = true }
                            runCatching { tr.write(pcm, 0, produced) }
                        } else if (playing) {
                            runCatching { tr.pause(); tr.flush() }
                            playing = false
                        }
                    }
                    if (mine()) runCatching { SstvHub.feedLive(pcm, produced) }
                    if (SondeHub.active && mine()) {
                        runCatching { SondeHub.feedLive(pcm, produced) }
                    }
                    val l = lame
                    val b = mp3Buf
                    if (l != null && b != null) {
                        val enc = l.encode(pcm, pcm, produced, b)
                        if (enc > 0) mp3Out?.write(b, 0, enc)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUi >= 250L && mine()) {
                    lastUi = now
                    val cur = _state.value
                    _state.value = cur.copy(
                        // La fréquence annoncée est celle qu'on écoute vraiment,
                        // décalage logiciel compris — pas celle du tuner.
                        centerHz = achievedHz + fineHz,
                        dopplerFineHz = fineHz,
                        pllWrites = pllWrites,
                        levelDb = chain.levelDb,
                        offsetHz = wantOffset,
                        mode = wantMode,
                        bandwidthHz = wantBandwidth,
                        squelchDb = wantSquelch,
                        clipping = chain.clipRatio > 0.002f,
                        afLevel = chain.audioPeak,
                        mbRead = (bytes / 1_048_576.0).toFloat())
                }
            }
        } catch (e: Exception) {
            failIf(mine(), e.message ?: "sdr_loop_error")
        } finally {
            runCatching {
                val l = lame
                val b = mp3Buf
                if (l != null && b != null) {
                    val flushed = l.flush(b)
                    if (flushed > 0) mp3Out?.write(b, 0, flushed)
                }
            }
            runCatching { lame?.close() }
            // Après `lame_close`, jamais avant : voir [EncodeurMp3].
            if (encodeurPris) EncodeurMp3.rend(EncodeurMp3.SDR)
            runCatching { mp3Out?.flush(); mp3Out?.close() }
            // L'ordre compte : couper la sortie son avant de fermer l'USB,
            // sinon le tampon audio continue de se vider sur un flux mort.
            runCatching { if (playing) track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { s.stopStream() }
            runCatching { s.close() }
            // Dernier geste du fil : annoncer qu'il a rendu la clé. C'est ce
            // que [stop] et [start] attendent.
            workerAlive = false
        }
    }

    /** [fail] seulement si la session appelante est encore la bonne. */
    private fun failIf(mine: Boolean, msg: String) { if (mine) fail(msg) }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            Dsp.AUDIO_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Un octet n'est pas un échantillon : AUDIO_RATE octets font une
        // demi-seconde de son mono 16 bits. La marge d'un quart de seconde
        // d'avant tenait tant que rien d'autre ne tournait ; elle craquait dès
        // que le décodeur SSTV et l'encodeur MP3 travaillaient en même temps.
        val size = maxOf(min * 2, Dsp.AUDIO_RATE)   // ~0,5 s de marge
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Dsp.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // USAGE_MEDIA sort sur le haut-parleur du téléphone tant qu'aucun
        // casque ni aucune enceinte Bluetooth n'est branché — c'est exactement
        // ce qu'on veut pour écouter un passage à l'oreille, sans fil.
        runCatching { t.setVolume(AudioTrack.getMaxVolume()) }
        return t
    }

    // ------------------------------------------------------------- pilotage

    /**
     * Nouvelle fréquence à afficher, Doppler compris. Appelé une fois par
     * seconde ; on ignore les écarts inférieurs à 100 Hz, inaudibles en FM
     * étroite et qui ne feraient que faire travailler la PLL pour rien.
     */
    fun setCenter(hz: Long, restHz: Long = _state.value.restHz) {
        if (!running) return
        val cur = pendingHz
        // La bande morte est descendue de cent hertz à dix : elle n'a plus le
        // même prix. Autrefois chaque consigne coûtait une reprogrammation de
        // PLL, et il fallait bien s'en protéger ; désormais elle ne coûte
        // qu'une multiplication complexe de plus.
        if (cur > 0 && Math.abs(hz - cur) < DopplerTuner.DEADBAND_HZ) return
        pendingHz = hz
        // Changer la fréquence de la clé annule la part Doppler du décalage
        // fin : elle était comptée par rapport à l'ancienne position.
        dopplerFine = 0
        _state.value = _state.value.copy(restHz = restHz, dopplerHz = hz - restHz)
        applyOffset()
    }

    /** Gain manuel (dixièmes de dB) ou null pour l'automatique. */
    fun setGain(tenthDb: Int?) {
        val s = sdr ?: return
        runCatching { s.setGain(tenthDb) }
        _state.value = _state.value.copy(gainTenthDb = tenthDb)
    }

    /**
     * Coupe ou rétablit le son sans toucher au reste : le fil de lecture met
     * l'AudioTrack en pause et le relance, la démodulation et le décodage SSTV
     * continuent de tourner exactement pareil.
     */
    fun setAudio(on: Boolean) {
        wantAudio = on
        _state.value = _state.value.copy(audio = on)
    }

    /** Change le mode de démodulation en cours de réception. */
    /**
     * Coupe ou rétablit la désaccentuation. À couper dès qu'on décode autre
     * chose que de la parole.
     */
    fun setDeemphasis(on: Boolean) { wantDeemph = on }

    fun setMode(m: RxMode) {
        wantMode = m
        _state.value = _state.value.copy(mode = m)
    }

    /** Largeur de canal en hertz ; zéro laisse le mode décider. */
    fun setBandwidth(hz: Int) {
        wantBandwidth = hz
        _state.value = _state.value.copy(bandwidthHz = hz)
    }

    /** Seuil du silencieux en dBFS ; -120 le coupe. */
    fun setSquelch(db: Int) {
        wantSquelch = db
        _state.value = _state.value.copy(squelchDb = db)
    }

    /**
     * Accord fin logiciel, en hertz par rapport à la fréquence de la clé.
     *
     * C'est ce que déplace le doigt posé sur la cascade : le décalage est
     * appliqué sur l'IQ brut, avant le filtre de canal, donc n'importe quelle
     * station visible sur le spectre est accessible sans retoucher la PLL.
     */
    fun setOffset(hz: Int) {
        userOffset = hz.coerceIn(-80_000, 80_000)
        applyOffset()
    }

    /**
     * Part Doppler du décalage fin, écrite par la boucle de suivi.
     *
     * Elle s'ajoute à la retouche de l'opérateur au lieu de la remplacer : on
     * peut se caler à la main sur une station pendant que le suivi continue de
     * compenser la dérive du satellite.
     */
    fun setDopplerFine(hz: Int) {
        dopplerFine = hz.coerceIn(-80_000, 80_000)
        applyOffset()
    }

    /**
     * Reprogramme la clé et remet la part Doppler à sa nouvelle valeur.
     *
     * Réservé au recentrage : c'est le seul geste qui s'entend, et le compteur
     * le dit pour qu'on puisse vérifier qu'il reste rare.
     */
    fun retune(pllHz: Long, fineHz: Int, restHz: Long) {
        if (!running) return
        pendingHz = pllHz
        pllMoveCount++
        dopplerFine = fineHz.coerceIn(-80_000, 80_000)
        // La nouvelle fréquence est publiée tout de suite, avant même que le
        // fil de lecture ne l'ait écrite dans la clé. Sans cela, le suivi
        // relirait l'ancienne position au tour suivant, croirait le recentrage
        // perdu et le redemanderait — le compteur de recentrages s'envolerait
        // pour un seul geste. Le fil corrigera ce chiffre au quart de seconde
        // suivant avec la fréquence réellement obtenue, qui diffère de
        // quelques dizaines de hertz à cause du sigma-delta de la PLL.
        _state.value = _state.value.copy(
            centerHz = pllHz,
            restHz = restHz,
            dopplerHz = pllHz + dopplerFine - restHz,
            pllWrites = pllMoveCount)
        applyOffset()
    }

    /** Somme des deux volontés, bornée, publiée. */
    private fun applyOffset() {
        val v = (userOffset + dopplerFine).coerceIn(-80_000, 80_000)
        wantOffset = v
        _state.value = _state.value.copy(offsetHz = v, dopplerFineHz = dopplerFine.toLong())
    }

    /**
     * Correction totale en cours, en hertz : ce que la clé a de plus que la
     * fréquence de repos, décalage fin compris. C'est le chiffre à afficher.
     */
    fun dopplerAppliedHz(): Long {
        val st = _state.value
        return st.centerHz + st.dopplerFineHz - st.restHz
    }

    /**
     * Accord automatique sur la porteuse la plus forte de la fenêtre donnée.
     *
     * Le fil de lecture résout la demande à la prochaine trame de spectre : lui
     * seul détient la chaîne de traitement, et c'est la seule façon de lire le
     * spectre sans verrou dans la boucle chaude.
     */
    fun tunePeak(fromHz: Double, toHz: Double) {
        if (!running) return
        peakFromHz = minOf(fromHz, toHz)
        peakToHz = maxOf(fromHz, toHz)
        wantPeak = true
    }

    /**
     * Recentre l'accord fin sur le centre de gravité du signal reçu.
     *
     * C'est l'accord automatique des modulations sans porteuse : une
     * radiosonde, une balise de télémétrie, tout ce qui a deux bosses et rien
     * au milieu. Voir [RxChain.centroidOffsetHz] pour la mesure elle-même.
     *
     * Comme [tunePeak], la demande est résolue par le fil de lecture à la
     * prochaine trame de spectre — lui seul détient la chaîne, et c'est la
     * seule façon de la lire sans poser un verrou dans la boucle chaude.
     */
    fun tuneCentroid(searchHz: Double = 25_000.0) {
        if (!running) return
        centroidSearchHz = searchHz.coerceIn(2_000.0, 80_000.0)
        centroidCibleHz = 0
        centroidAutourDuPoint = false
        wantCentroid = true
    }

    /**
     * Calage sur la voix reçue, pour la bande latérale unique.
     *
     * La différence avec [tuneCentroid] tient en deux chiffres et elles
     * comptent toutes les deux. La cible n'est pas zéro : une voix doit tomber
     * vers 1 500 hertz dans la bande audio, pas à cheval sur la fréquence
     * d'accord, sinon on n'entend qu'une moitié de chaque syllabe. Et la
     * recherche est étroite — trois kilohertz, un canal — parce qu'on cale sur
     * le correspondant qu'on écoute déjà et non sur la station la plus forte
     * du voisinage.
     */
    fun caleVoix(cibleHz: Int, searchHz: Double = 3_000.0) {
        if (!running) return
        centroidSearchHz = searchHz.coerceIn(1_000.0, 80_000.0)
        centroidCibleHz = cibleHz
        centroidAutourDuPoint = true
        wantCentroid = true
    }

    /**
     * Laisse le recentrage se refaire tout seul, ou l'arrête.
     *
     * Le premier recentrage est demandé sans attendre, pour que le bouton
     * réponde dans la seconde ; les suivants suivent [AUTO_TUNE_MS].
     */
    fun setAutoTune(on: Boolean, searchHz: Double = 25_000.0) {
        centroidSearchHz = searchHz.coerceIn(2_000.0, 80_000.0)
        centroidCibleHz = 0
        centroidAutourDuPoint = false
        autoCentroid = on
        if (on && running) wantCentroid = true
        _state.value = _state.value.copy(autoTune = on)
    }

    val isRunning: Boolean get() = running

    @Synchronized
    fun stop() {
        if (!running && !workerAlive) return
        stopWorker()
        if (_state.value.sstv) runCatching { SstvHub.stopLive() }
        _spectrum.value = FloatArray(0)
        _panorama.value = FloatArray(0)
        val present = appCtx?.let { devicePresent(it) != null } ?: false
        _state.value = _state.value.copy(
            running = false, connected = present, levelDb = -120f, sstv = false)
    }

    /**
     * Arrête vraiment le fil de lecture, et n'en revient qu'une fois la clé
     * rendue.
     *
     * L'ancien code se contentait d'un `join(2500)` dont il ignorait le
     * résultat, puis effaçait ses références : si la boucle traînait — elle
     * pouvait rester plus d'une seconde dans son attente USB, puis prendre
     * encore le temps de vider le MP3 et d'endormir le tuner — un nouveau
     * démarrage ouvrait une deuxième sortie son et une deuxième connexion sur
     * la même clé, pendant que l'ancienne coupait l'endpoint sous ses pieds.
     * D'où un premier passage impeccable et un second haché.
     *
     * Maintenant : on baisse le drapeau, on attend, et si le fil s'obstine on
     * lui ferme la connexion USB au nez pour débloquer son attente.
     */
    private fun stopWorker() {
        running = false
        generation++          // la session en cours perd le droit d'écrire
        val w = worker
        val s = sdr
        if (w != null) {
            runCatching { w.join(WORKER_JOIN_MS) }
            if (w.isAlive) {
                // Fermer la connexion fait rendre la main à requestWait().
                runCatching { s?.close() }
                runCatching { w.join(WORKER_JOIN_MS) }
            }
        }
        worker = null
        sdr = null
    }

    /** Volume de l'AudioManager, pour que l'écran puisse prévenir si c'est à zéro. */
    fun musicVolumeZero(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }
}
