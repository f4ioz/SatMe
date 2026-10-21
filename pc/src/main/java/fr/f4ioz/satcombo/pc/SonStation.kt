/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What the PC does with the rig's sound, as the phone: records the pass
 * (by hand, or by itself while the satellite followed is up) and decodes
 * SSTV live, the pictures kept as PNG with the phone's names and sidecars.
 */
class SonStation(
    val son: SonPc,
    private val station: StationPc,
    val dossierEnregistrements: File,
    val dossierSstv: File,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    data class Etat(
        val enregistrement: String = "",
        val depuisMs: Long = 0L,
        val secondes: Long = 0L,
        /** Started by itself for the pass (stops by itself at LOS). */
        val auto: Boolean = false,
    )

    private val _etat = MutableStateFlow(Etat())
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    // A recording keeps its card: the dongle started meanwhile is only heard, never recorded in its place.
    init { son.gardeCarte = { wav != null } }

    // The PC's own choices, kept with its settings.
    var sstvActif: Boolean
        get() = sstv.actif
        set(v) { sstv.actif = v }
    /** SSTV live (the card or the dongle), its settings, its pictures. */
    val sstv = SstvPc(station.stockage, dossierSstv, {
        val e = station.etat.value
        SstvPc.Contexte(e.suivi?.name.orEmpty(), e.locator, e.indicatif, _etat.value.enregistrement, noteSstv)
    }, horloge)
    /** Written on the pictures received meanwhile (the SSTV auto-pilot's campaign); "" none. */
    @Volatile var noteSstv: String = ""
    var aprsActif: Boolean
        get() = station.stockage.getBoolean("pc_aprs", true)
        set(v) { station.stockage.edit().putBoolean("pc_aprs", v).apply() }
    /** CW decoded on the card (off unless chosen), for the Carnet's window. */
    val cw = CwPc(station.stockage)
    /** PSK31 / RTTY decoded on the card (off unless chosen), for the QO-100 dock. */
    val clavier = ClavierPc(station.stockage, horloge)
    /** FT8 / FT4 on the card, as the phone's FT8 screen. */
    val ft8 = Ft8Pc(station.stockage, File(dossierEnregistrements.parentFile, "ft8-tranches"), horloge)
    /** Its stations sent to PSK Reporter, when chosen (off by default). */
    val psk = PskReporterPc(station, ft8, horloge = horloge)
    /** APRS heard on the card, kept as the phone keeps it. */
    val aprs = AprsPc(File(dossierEnregistrements.parentFile, "aprs"), { station.etat.value.suivi?.name ?: "" }, horloge) {
        Triple(station.settings.callsign, station.etat.value.observateur?.latDeg, station.etat.value.observateur?.lonDeg)
    }
    /**
     * What the card's sound carries now when it is not on the air (the SDR in its
     * place, playing its test generator or a recording); null: a real reception.
     * Nothing then is kept as heard: no APRS frame, no automatic pass recording.
     */
    @Volatile var horsAntenne: () -> SstvPc.HorsAntenne? = { null }

    init { aprs.horsAntenne = { horsAntenne() != null }; sstv.posteEntend = { posteEntendSstv() }; ft8.reprend() }

    var enregAuto: Boolean
        get() = station.stockage.getBoolean("pc_enreg_auto", false)
        set(v) { station.stockage.edit().putBoolean("pc_enreg_auto", v).apply() }
    /** The sound card last used, started again at launch. */
    var carte: String
        get() = station.stockage.getString("pc_son_carte", "") ?: ""
        set(v) {
            station.stockage.edit().putString("pc_son_carte", v).apply()
            if (v.isNotBlank()) station.stockage.edit().putString("pc_son_derniere", v).apply()
        }
    /** The last card started, kept even once stopped: the one a decoder switched on starts again. */
    val derniereCarte: String get() = station.stockage.getString("pc_son_derniere", "")?.ifBlank { null } ?: carte

    /** The source last chosen while [catnum] was followed (a card's name, or [fr.f4ioz.satcombo.domain.SourceAudio.SDR]). */
    fun sourcePourSatellite(catnum: Int?): String? =
        catnum?.let { station.stockage.getString("pc_son_sat_$it", "") }?.ifBlank { null }
    fun suiviCatnum(): Int? = station.etat.value.suivi?.catalogNumber
    fun memoriseSourceSatellite(source: String) {
        val n = station.etat.value.suivi?.catalogNumber ?: return
        station.stockage.edit().putString("pc_son_sat_$n", source).apply()
    }

    /** The rig's own sound: the IC-9700's by the network when its CAT goes that way, else its USB codec. */
    fun sourceDuPoste(appareils: List<SonPc.Appareil> = son.appareils()): String? {
        val e = station.etat.value
        val reseau = fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.NOM_SOURCE
        if (e.catConnecte && !e.catSimule && station.settings.icomLiaison == "RESEAU" && appareils.any { it.nom == reseau }) return reseau
        return carteDuPoste(appareils)
    }

    /**
     * A decoder switched on (or a recording asked) with nothing feeding: the
     * card it needs started, receive only — the one chosen for this
     * satellite, the last one used, the rig's own; never a guess. Null when
     * the sound runs, else why not.
     */
    fun assureSource(): String? {
        if (son.etat.value.actif) return null
        val l = son.appareils()
        val n = fr.f4ioz.satcombo.domain.SourceAudio.carteAPrendre(l.map { it.nom },
            sourcePourSatellite(station.etat.value.suivi?.catalogNumber), derniereCarte, sourceDuPoste(l))
            ?: return "aucune source audio : la choisir dans l'en-tête (« Son ») ou l'onglet Son"
        return prendCarte(n)
    }

    /** Since when the source running has been silent (0: sound now). */
    @Volatile var silenceDepuis = 0L
        private set

    /** One-shot: record the next time this satellite (its catalogue number), still followed, is up (a pass made ready before its AOS); 0: none. */
    @Volatile var enregistrePassage = 0

    @Volatile private var wav: EnregistreurWav? = null

    // ------------------------------------------------------------ the cards by name

    /** The rig as the cards' names need it: its model's name, Icom or not, its CAT ports. */
    fun posteCartes(): CartesSonLinux.Poste {
        val e = station.etat.value
        val m = if (e.catConnecte) e.catModele else StationPc.modeleDe(station.settings.rigModel)
        val icom = fr.f4ioz.satcombo.cat.ModeleIcom.entries.firstOrNull { it.id == m }
        val nom = if (!e.catConnecte || e.catSimule) "" else icom?.libelle?.removePrefix("Icom ") ?: when (m) { "TS2000" -> "TS-2000"; else -> "" }
        return CartesSonLinux.Poste(nom, icom != null, station.cheminsPoste())
    }

    /** Where Linux is read (a fixture in the tests); null: not Linux, no names. */
    var racineSysteme: File? = File("/").takeIf { System.getProperty("os.name").orEmpty().startsWith("Linux") }
    @Volatile private var nomsLus: Triple<Long, String, Map<String, CartesSonLinux.Libelle>> = Triple(0L, "", emptyMap())

    /** The cards' friendly names, by Java's name (« CODEC [plughw:1,0] » → « IC-9700 (USB Audio CODEC) »), read again every 5 s. */
    fun nomsCartes(appareils: List<SonPc.Appareil> = son.appareils()): Map<String, CartesSonLinux.Libelle> {
        val r = racineSysteme ?: return emptyMap()
        val poste = posteCartes()
        val cle = appareils.joinToString("|") { it.nom } + "#" + poste
        val (t, k, m) = nomsLus
        if (k == cle && horloge() - t < 5_000L) return m
        val n = runCatching { CartesSonLinux.tous(r, appareils.map { it.nom to it.description }, poste) }.getOrDefault(emptyMap())
        nomsLus = Triple(horloge(), cle, n)
        return n
    }

    @Volatile private var posteLu: Pair<Long, String?> = 0L to null

    /** The rig's own card (its USB codec), when one is recognised. */
    fun carteDuPoste(appareils: List<SonPc.Appareil> = son.appareils()): String? =
        nomsCartes(appareils).entries.firstOrNull { it.value.poste.isNotBlank() }?.key

    /**
     * Auto SSTV prefers the rig's card: it is the card feeding, the rig is
     * in CAT and its downlink is within its bands.
     */
    fun posteEntendSstv(): Boolean {
        val e = station.etat.value
        if (!e.catConnecte || e.catSimule || !son.etat.value.actif) return false
        // The IC-9700's audio by the network is the rig's own, like its USB codec.
        if (son.etat.value.appareil == fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.NOM_SOURCE)
            return CartesSonLinux.posteEntend(e.catModele, runCatching { station.stationCat.etat.value.descenteHz }.getOrNull())
        // Asked at every block: the mixers listed again only every ten seconds.
        val (t, lu) = posteLu
        val poste = (if (horloge() - t < 10_000L) lu else carteDuPoste().also { posteLu = horloge() to it }) ?: return false
        return son.etat.value.appareil == poste &&
            CartesSonLinux.posteEntend(e.catModele, runCatching { station.stationCat.etat.value.descenteHz }.getOrNull())
    }

    /**
     * A card chosen as a decoder's source (SSTV's selector): kept as the
     * Sound tab's and started, receive only. Null when it runs, else why not.
     */
    fun prendCarte(nom: String): String? {
        if (son.etat.value.actif && son.etat.value.appareil == nom) { carte = nom; return null }
        if (!son.demarre(nom)) return son.etat.value.erreur.ifBlank { "carte son introuvable" }
        carte = nom
        return null
    }

    /** The rig answers by CAT (for the SSTV tuning's page). */
    fun catConnecte(): Boolean = station.etat.value.catConnecte

    init {
        son.auditeurs += { pcm, n, rate ->
            if (son.etat.value.niveau >= fr.f4ioz.satcombo.domain.SourceAudio.SILENCE) silenceDepuis = 0L
            else if (silenceDepuis == 0L) silenceDepuis = horloge()
            wav?.let { w -> w.ajoute(pcm, n); _etat.update { it.copy(secondes = w.secondes) } }
            sstv.ecouteCarte(pcm, n, rate)
            if (aprsActif) aprs.ecoute(pcm, n, rate)
            ft8.ecoute(pcm, n, rate)
            cw.ecoute(pcm, n, rate)
            clavier.ecoute(pcm, n, rate)
        }
    }

    // ------------------------------------------------------------ recording

    fun demarreEnregistrement(auto: Boolean = false): String? {
        if (wav != null) return null
        val rate = son.etat.value.rate.takeIf { son.etat.value.actif } ?: return "la carte son n'est pas démarrée (onglet Son)"
        dossierEnregistrements.mkdirs()
        val sat = station.etat.value.suivi?.name ?: "SAT"
        val safe = sat.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifBlank { "SAT" }
        val f = File(dossierEnregistrements, "SatMe_${safe}_${stamp(horloge())}.wav")
        wav = EnregistreurWav(f, rate)
        basDepuis = 0L; vuLeve = false; finPrevue = 0L
        _etat.update { it.copy(enregistrement = f.name, depuisMs = horloge(), secondes = 0, auto = auto) }
        return null
    }

    /** Stopped by hand: not started again by itself before the satellite has set. */
    fun arreteEnregistrement(parLaMain: Boolean = true) {
        val w = wav ?: return
        if (parLaMain) arreteALaMain = true
        wav = null
        w.ferme()
        _etat.update { it.copy(enregistrement = "", depuisMs = 0L, auto = false) }
    }

    /** A recording, even started by hand, stops after the pass it caught (30 s after LOS). */
    var coupeLos: Boolean
        get() = station.stockage.getBoolean("pc_enreg_coupe_los", false)
        set(v) { station.stockage.edit().putBoolean("pc_enreg_coupe_los", v).apply() }

    /**
     * Every second. Starts by itself (the card running): with automatic
     * recording on, at AOS of the satellite followed; with the automatic
     * mode, 5 s before AOS of the pass it follows. Stops 30 s after LOS a
     * recording it started — and one started by hand with [coupeLos] on,
     * once the satellite has been up during it.
     */
    fun tic() {
        val maintenant = horloge()
        runCatching { ticCw() }
        val p = station.etat.value.position
        val leve = p != null && p.elevationDeg >= 0
        // The automatic mode's pass, recorded once (not again after a stop, by hand or at LOS).
        val veille = station.passageVeilleAEnregistrer(maintenant)
            ?.takeIf { fr.f4ioz.satcombo.domain.VeilleAuto.cle(it) != veilleFaite }
        if (!leve && veille == null) arreteALaMain = false
        if (!son.etat.value.actif) silenceDepuis = 0L
        if (wav == null && son.etat.value.actif && !arreteALaMain && ((enregAuto && leve) || veille != null || (enregistrePassage != 0 && leve && enregistrePassage == station.etat.value.suivi?.catalogNumber)) &&
            runCatching { horsAntenne() }.getOrNull() == null) {
            demarreEnregistrement(auto = true)
            enregistrePassage = 0
            finPrevue = veille?.let { it.losEpochMs + fr.f4ioz.satcombo.domain.VeilleAuto.APRES_MS } ?: 0L
            veille?.let { veilleFaite = fr.f4ioz.satcombo.domain.VeilleAuto.cle(it) }
        }
        val e = _etat.value
        if (wav == null) return
        if (leve) { basDepuis = 0L; vuLeve = true; return }
        // The automatic mode's pass over, the satellite seen or not (another one followed by hand).
        if (e.auto && finPrevue > 0L && maintenant > finPrevue) { arreteEnregistrement(parLaMain = false); return }
        if (!(e.auto || coupeLos) || !vuLeve) return
        if (basDepuis == 0L) basDepuis = maintenant
        if (maintenant - basDepuis >= 30_000L) arreteEnregistrement(parLaMain = false)
    }
    private var basDepuis = 0L

    /**
     * The CW decoder and FT8 told how the satellite's Doppler moves the
     * audio: when the rig follows it (CAT, not paused) or the dongle does,
     * nothing slides.
     */
    private fun ticCw() {
        val e = station.etat.value
        val tx = station.emetteurChoisi()
        val f = tx?.downlinkLowHz
        val p = e.position
        val dop = if (f != null && p != null && p.elevationDeg > -2) -f * p.rangeRateKmS / 299_792.458 else null
        val parLaCle = son.etat.value.appareil == SdrPc.NOM_SOURCE
        val corrige = (e.catConnecte && !e.maintienDoppler) || (parLaCle && station.settings.sdrDopplerTrack)
        val signe = if ((tx?.mode ?: "").uppercase() == "LSB") -1 else 1
        // FT8 too: the slot's residual drift removed before decoding.
        ft8.indicatif = station.settings.callsign
        ft8.ticDoppler(dop, corrige, signe, p?.elevationDeg?.takeIf { it > -2 && f != null })
        if (!cw.actif) return
        cw.ticDoppler(dop, corrige, signe)
    }
    /** The satellite followed has been up during this recording. */
    private var vuLeve = false
    /** The automatic mode's recording: LOS + 30 s at the latest. */
    private var finPrevue = 0L
    private var veilleFaite = ""
    private var arreteALaMain = false

    fun enregistrements(): List<File> =
        (dossierEnregistrements.listFiles { f -> f.name.startsWith("SatMe_") && (f.name.endsWith(".wav") || f.name.endsWith(".mp3")) }
            ?: emptyArray()).sortedByDescending { it.lastModified() }

    // ----------------------------------------------------------------- SSTV

    /** The picture arriving, as PNG; null when none. */
    fun imageEnCours(): ByteArray? = sstv.imageEnCours()

    /** The pictures kept, newest first, with what their sidecars say. */
    fun images(): List<SstvMeta.SstvShot> = sstv.images()

    fun fichierImage(nom: String): File? = sstv.fichier(nom)

    fun fichierEnregistrement(nom: String): File? =
        File(dossierEnregistrements, File(nom).name).takeIf { it.isFile && it.name.startsWith("SatMe_") }

    private fun stamp(t: Long) = SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
}
