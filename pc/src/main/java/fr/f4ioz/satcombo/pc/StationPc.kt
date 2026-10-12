/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.cat.CivController
import fr.f4ioz.satcombo.cat.Ic9700Sim
import fr.f4ioz.satcombo.cat.ModeleIcom
import fr.f4ioz.satcombo.cat.PosteCat
import fr.f4ioz.satcombo.cat.PosteCatCiv
import fr.f4ioz.satcombo.data.AgendaStore
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.station.MondeCat
import fr.f4ioz.satcombo.station.MondeRotor
import fr.f4ioz.satcombo.station.StationCat
import fr.f4ioz.satcombo.station.StationRotor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the page shows of the station, one copy, changed only through `update {}`. */
data class EtatPc(
    val maintenant: Long = 0L,
    val satellites: List<TleEntry> = emptyList(),
    val catalogue: String = "",
    val locator: String = "",
    val observateur: Observer? = null,
    val indicatif: String = "",
    /** The next passes of every satellite, soonest first. */
    val passages: List<SatPass> = emptyList(),
    val calculEnCours: Boolean = false,
    /** The satellite followed. */
    val suivi: TleEntry? = null,
    val position: SatPosition? = null,
    /** Its pass now, or its next one; its (az, el) arc. */
    val passage: SatPass? = null,
    val arc: List<Pair<Double, Double>> = emptyList(),
    val emetteurs: List<Transmitter> = emptyList(),
    val emetteur: Int = 0,
    /** The rest frequency chosen (sky, Hz), null: the transmitter's centre. */
    val repos: Long? = null,
    // CAT
    val catModele: String = "IC9700",
    val catConnecte: Boolean = false,
    val catStatut: String = "",
    val catSimule: Boolean = false,
    val maintienDoppler: Boolean = false,
    /** The satellites marked ★ (catalog numbers). */
    val favoris: Set<Int> = emptySet(),
    /** amsat.org's status page: is each satellite heard these days (by AMSAT name). */
    val amsat: Map<String, fr.f4ioz.satcombo.data.AmsatReport> = emptyMap(),
    /** The satellite followed's own settings: RX calibration, TX shift, their reference. */
    val calibHz: Long = 0L,
    val txShiftHz: Long = 0L,
    val refCalibHz: Long? = null,
    val refTxHz: Long? = null,
    /** The satellite followed's next passes (3 days). */
    val passagesSuivi: List<SatPass> = emptyList(),
    /** The passes chosen ahead, as on the phone. */
    val agenda: List<fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent> = emptyList(),
    /** Follow by itself the satellite an appointment names, from 5 min before it. */
    val agendaSuivi: Boolean = true,
)

/**
 * The station on a PC: satellites and passes, the satellite followed, the
 * rig (CAT Doppler) and the mast — the engine's stations ([StationCat],
 * [StationRotor]), answered here as the phone's ViewModel answers them.
 *
 * Nothing opens by itself: the rig and the mast connect when asked, on the
 * port chosen. The settings are those of the phone, in a file.
 */
class StationPc(
    val stockage: Stockage,
    private val catalogue: Catalogue,
    val ports: PortsSerie,
    private val portee: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Each satellite's settings (the phone's "satcombo_satconfig"). */
    val stockageSat: Stockage = fr.f4ioz.satcombo.plateforme.StockageMemoire(),
    /** The agenda (the phone's "satcombo_agenda"). */
    val stockageAgenda: Stockage = fr.f4ioz.satcombo.plateforme.StockageMemoire(),
) {
    private val satConfig = fr.f4ioz.satcombo.data.SatConfigStore(stockageSat)
    val settings = SettingsStore(stockage)
    private val predicteur = PassPredictor()

    private val _etat = MutableStateFlow(EtatPc())
    val etat: StateFlow<EtatPc> = _etat.asStateFlow()
    private fun change(f: EtatPc.() -> EtatPc) = _etat.update { it.f() }

    // ------------------------------------------------------------------ CAT

    private val civ = CivController(ports)
    private val posteCiv = PosteCatCiv(civ)
    /** The rigs driven as a pair, the phone's drivers: FT-817 / IC-705 (one each side), TS-2000, TH-D72. */
    private val paire = fr.f4ioz.satcombo.cat.Ft817Pair(ports)
    private val postePaire = fr.f4ioz.satcombo.cat.PosteCatPaire(paire)
    private val fil = Mutex()
    private var boucleCat: Job? = null
    /** The adapter the rig is on: the mast's search never opens it. */
    @Volatile private var clePoste: String = ""

    val stationCat = StationCat(object : MondeCat {
        override fun connecte() = _etat.value.catConnecte
        override fun poste(): PosteCat = if (_etat.value.catModele in PAIRES) postePaire else posteCiv
        // A pair with only its TX rig: nothing to read back.
        override fun emetSeul() = _etat.value.catModele in PAIRES && !paire.rx.isOpen && paire.tx.isOpen
        override fun aprsEnEmission() = false
        override fun satellite() = _etat.value.position
        override fun catnum() = _etat.value.suivi?.catalogNumber
        override fun transpondeur() = emetteurChoisi()
        override fun sousHorizonAussi() = false
        override fun calibrationHz() = _etat.value.calibHz
        override fun decalageTxHz() = _etat.value.txShiftHz
        override fun poseDecalageTx(hz: Long) = reglageSat(txHz = hz)
        override fun decalageRxHz() = 0L
        override fun modeCw() = false
        override fun maintienDoppler() = _etat.value.maintienDoppler
        override fun dopplerRx() = true
        override fun moletteTxDecalage() = false
        override fun txSuitVite() = false
        override fun modelePoste() = _etat.value.catModele
        override fun inversionForcee(): Boolean? = null
        override fun ctcssChoisi() = 0
        override fun ctcssAuto() = true
        override fun repos() = _etat.value.repos
        override fun poseRepos(hz: Long) = change { copy(repos = hz) }
        // The converters (LNB, transverter) as on the phone, from the same settings.
        override fun posteRx(satHz: Long) = if (settings.convRxPoste) convRx().versPoste(satHz) else satHz
        override fun satDepuisPoste(posteHz: Long) = if (convRxDansLaChaine()) convRx().versSatellite(posteHz) else posteHz
        override fun posteTx(satHz: Long) = convTx().versPoste(satHz)
        override fun satDepuisPosteTx(posteHz: Long) = if (convTxDansLaChaine()) convTx().versSatellite(posteHz) else posteHz
    }, horloge)

    fun convRx() = Convertisseur(actif = settings.convRxActif, olHz = settings.convRxOlHz,
        inverseur = settings.convRxInverseur, basHz = settings.convRxBasHz, hautHz = settings.convRxHautHz)
    fun convTx() = Convertisseur(actif = settings.convTxActif, olHz = settings.convTxOlHz,
        inverseur = settings.convTxInverseur, basHz = settings.convTxBasHz, hautHz = settings.convTxHautHz)

    /** Is the RX converter in the rig's chain? The satellite decides: its downlink in the converter's band. */
    private fun convRxDansLaChaine(): Boolean {
        val c = convRx()
        if (!settings.convRxPoste || !c.configure) return false
        return emetteurChoisi()?.downlinkLowHz?.let { c.couvre(it) } == true
    }

    private fun convTxDansLaChaine(): Boolean {
        val c = convTx()
        if (!c.configure) return false
        return emetteurChoisi()?.uplinkLowHz?.let { c.couvre(it) } == true
    }

    /** The converters, set on the page (Hz; an LO of 0 turns one off). */
    fun poseConvertisseurs(rxOl: Long?, rxInverseur: Boolean?, txOl: Long?, txInverseur: Boolean?) {
        rxOl?.let { settings.convRxOlHz = it; settings.convRxActif = it > 0; settings.convRxPoste = it > 0 }
        rxInverseur?.let { settings.convRxInverseur = it }
        txOl?.let { settings.convTxOlHz = it; settings.convTxActif = it > 0 }
        txInverseur?.let { settings.convTxInverseur = it }
        stationCat.oublieArmement()
    }

    // ---------------------------------------------------------------- rotor

    val stationRotor = StationRotor(settings, ports, portee, object : MondeRotor {
        override fun satellite() = _etat.value.position
        override fun nomSatellite() = _etat.value.suivi?.name.orEmpty()
        override fun arcDuPassage() = _etat.value.arc
        override fun aosDuPassage() = _etat.value.passage?.aosEpochMs ?: 0L
        override fun enEmission() = false
        override fun imageEnCours(maintenant: Long): LongRange? = null
        override fun imagesRecues(nomSatellite: String, depuisMs: Long): List<LongRange> = emptyList()
        override fun adaptateursDesPostes() = listOf(clePoste).filter { it.isNotBlank() }
        override fun signale(ou: String, e: Throwable) { System.err.println("rotor : $ou : ${e.message}") }
    }, horloge)

    // ------------------------------------------------------------ the start

    /** Reads what is on disk, then keeps the station going: position every second, passes every ten minutes. */
    fun demarre() {
        // On a PC the mast's port is the one chosen, never searched among the others.
        stationRotor.setRotorAutoPort(false)
        stationCat.regleMaintien(settings.catHoldMs.toLong())
        change { copy(agenda = AgendaStore.purge(stockageAgenda, nowMs = horloge()),
            agendaSuivi = stockage.getBoolean(CLE_AGENDA_SUIVI, true)) }
        change { copy(favoris = lisFavoris(), satellites = this@StationPc.catalogue.satellites(), locator = settings.manualLocator,
            indicatif = settings.callsign, observateur = observateur(), catModele = modeleDe(settings.rigModel)) }
        stockage.getInt(CLE_SATELLITE, 0).takeIf { it > 0 }?.let { n -> _etat.value.satellites.firstOrNull { it.catalogNumber == n }?.let { suis(it) } }
        portee.launch(Dispatchers.IO) {
            val age = catalogue.age(horloge())
            if (age == null || age > 12 * 3_600_000L) {
                change { copy(catalogue = "téléchargement des éléments AMSAT…") }
                val dit = catalogue.rafraichis()
                change { copy(catalogue = dit, satellites = this@StationPc.catalogue.satellites()) }
            } else change { copy(catalogue = "éléments AMSAT du disque (${age / 3_600_000L} h)") }
            recalcule()
        }
        portee.launch {
            while (true) {
                runCatching { tic() }
                delay(1000)
            }
        }
        portee.launch {
            while (true) {
                delay(10 * 60_000L)
                runCatching { recalcule() }
            }
        }
        // AMSAT's status page: kept on disk, asked again once an hour.
        portee.launch(Dispatchers.IO) {
            change { copy(amsat = amsatRepo.loadCache()) }
            while (catalogue.enLigne) {
                runCatching { amsatRepo.get() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { r -> change { copy(amsat = r) } }
                delay(3_600_000L)
            }
        }
    }

    private val amsatRepo = fr.f4ioz.satcombo.data.AmsatStatusRepository(this.catalogue.dossierCache?.let { java.io.File(it, "amsat_status.json") })

    /** The status page asked again now (a report just sent). */
    fun rafraichisAmsat() {
        if (!catalogue.enLigne) return
        portee.launch(Dispatchers.IO) { runCatching { amsatRepo.get(force = true) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { r -> change { copy(amsat = r) } } }
    }

    /** amsat.org's report for a satellite, by its AMSAT name (letters and digits compared). */
    fun statutAmsat(nom: String): fr.f4ioz.satcombo.data.AmsatReport? {
        val r = _etat.value.amsat
        if (r.isEmpty()) return null
        fun n(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
        r[nom.uppercase().trim()]?.let { return it }
        val k = n(nom)
        return r.values.firstOrNull { n(it.name) == k } ?: if (k.contains("ISS")) r.values.firstOrNull { n(it.name).contains("ISS") } else null
    }

    // ------------------------------------------------------------ favourites

    private fun lisFavoris(): Set<Int> = stockage.getStringSet(CLE_FAVORIS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun favori(catnum: Int, on: Boolean) {
        val f = if (on) _etat.value.favoris + catnum else _etat.value.favoris - catnum
        stockage.edit().putStringSet(CLE_FAVORIS, f.map { it.toString() }.toSet()).apply()
        change { copy(favoris = f) }
    }

    /** The phone's favourites (its backup), added to the PC's. */
    fun ajouteFavoris(l: Set<Int>) {
        if (l.isEmpty()) return
        val f = _etat.value.favoris + l
        stockage.edit().putStringSet(CLE_FAVORIS, f.map { it.toString() }.toSet()).apply()
        change { copy(favoris = f) }
    }

    @Volatile private var derniereRecherche = 0L

    /** The satellite followed, now: where it is, its pass. */
    private suspend fun tic() {
        val maintenant = horloge()
        suitAgenda(maintenant)
        val e = _etat.value
        val sat = e.suivi
        val obs = e.observateur
        val pos = if (sat != null && obs != null) withContext(Dispatchers.Default) {
            runCatching { predicteur.positionAt(sat, obs, maintenant) }.getOrNull()
        } else null
        // The pass followed: the one under way, else the next; its arc for the mast and the sky.
        var passage = e.passage
        // Searched again when over, and at most once a minute when there is none (a satellite
        // that never rises, or never sets — QO-100): 48 hours of orbit each second would be a waste.
        val aChercher = passage?.let { it.losEpochMs < maintenant } ?: (maintenant - derniereRecherche >= 60_000L)
        if (sat != null && obs != null && aChercher) {
            derniereRecherche = maintenant
            passage = withContext(Dispatchers.Default) {
                runCatching { predicteur.upcomingPasses(sat, obs, maintenant - 30 * 60_000L, 48, 0.0) }.getOrNull()
                    ?.firstOrNull { it.losEpochMs >= maintenant }
            }
        }
        val arc = if (passage != null && passage != e.passage && sat != null && obs != null)
            runCatching { predicteur.passTrack(sat, obs, passage.aosEpochMs, passage.losEpochMs) }.getOrDefault(emptyList())
        else e.arc
        // Its next passes, again when they move on (one gone) or the satellite changed.
        val suivants = e.passagesSuivi
        val aRefaire = sat != null && obs != null && (suivants.isEmpty() || suivants.first().losEpochMs < maintenant ||
            suivants.first().catalogNumber != sat.catalogNumber) && maintenant - derniereSuite >= 60_000L
        val nouveaux = if (aRefaire) {
            derniereSuite = maintenant
            withContext(Dispatchers.Default) {
                runCatching { predicteur.upcomingPasses(sat!!, obs!!, maintenant - 30 * 60_000L, 72, settings.minElevDeg.toDouble()) }
                    .getOrDefault(emptyList()).filter { it.losEpochMs >= maintenant }.take(8)
            }
        } else suivants
        change { copy(maintenant = maintenant, position = pos, passage = passage, arc = arc, passagesSuivi = nouveaux) }
    }
    @Volatile private var derniereSuite = 0L

    // --------------------------------------------------------------- agenda

    /** An appointment's satellite among the known ones (names compared on letters and digits). */
    private fun satDe(nom: String): TleEntry? {
        fun n(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val k = n(nom)
        if (k.isEmpty()) return null
        val l = _etat.value.satellites
        return l.firstOrNull { n(it.name) == k } ?: if (k.contains("ISS")) l.firstOrNull { n(it.name) == "ISS" || it.catalogNumber == 25544 } else null
    }

    /** The appointments already followed by themselves: each once, the operator free to change after. */
    private val dejaSuivis = HashSet<Long>()

    /**
     * With automatic following on: from 5 minutes before an appointment
     * (the mast's time to go and wait), follow its satellite — once per
     * appointment, so a hand that picks another satellite is not overruled.
     */
    private fun suitAgenda(maintenant: Long) {
        val e = _etat.value
        if (!e.agendaSuivi) return
        val rdv = e.agenda.firstOrNull { it.enabled && it.satName.isNotBlank() && it.id !in dejaSuivis && it.activeAt(maintenant) } ?: return
        dejaSuivis += rdv.id
        val sat = satDe(rdv.satName) ?: return
        if (e.suivi?.catalogNumber != sat.catalogNumber) suis(sat)
    }

    /** A pass into the agenda (its window, AOS to LOS), or out of it when already there. */
    fun basculeAgenda(p: SatPass): Boolean {
        val deja = _etat.value.agenda.firstOrNull { it.satName == p.satName && it.covers(p.aosEpochMs, p.losEpochMs, 0L) }
        val l = if (deja != null) AgendaStore.remove(stockageAgenda, deja.id)
        else AgendaStore.put(stockageAgenda, AgendaStore.AgendaEvent(id = horloge(), title = "Passage " + p.satName, timeMs = p.aosEpochMs,
            satName = p.satName, leadMin = 5, endMs = p.losEpochMs))
        change { copy(agenda = l) }
        return deja == null
    }

    fun retireAgenda(id: Long) { change { copy(agenda = AgendaStore.remove(stockageAgenda, id)) } }

    fun activeAgenda(id: Long) {
        val e = _etat.value.agenda.firstOrNull { it.id == id } ?: return
        change { copy(agenda = AgendaStore.put(stockageAgenda, e.copy(enabled = !e.enabled))) }
    }

    fun agendaSuivi(on: Boolean) {
        stockage.edit().putBoolean(CLE_AGENDA_SUIVI, on).apply()
        change { copy(agendaSuivi = on) }
    }

    /** The agenda read again (the phone's taken from its backup). */
    fun rechargeAgenda() { change { copy(agenda = AgendaStore.load(stockageAgenda)) } }

    /** Is this pass in the agenda? */
    fun auAgenda(p: SatPass): Boolean = _etat.value.agenda.any { it.enabled && satDe(it.satName)?.catalogNumber == p.catalogNumber && it.covers(p.aosEpochMs, p.losEpochMs, 0L) }

    // ------------------------------------------------------------ timeline

    /** One satellite on the timeline: its elevation each minute (negative: below the horizon). */
    class LigneFrise(val sat: TleEntry, val elevations: List<Double>)

    /**
     * The elevation of the favourites (and of the satellite followed, and of
     * the agenda's) over [heures] hours from now, one value a minute: what is
     * coming up, how high, which overlap.
     */
    suspend fun frise(heures: Int = 12): Pair<Long, List<LigneFrise>> = withContext(Dispatchers.Default) {
        val e = _etat.value
        val obs = e.observateur ?: return@withContext horloge() to emptyList()
        val debut = horloge() / 60_000L * 60_000L
        val nums = (e.favoris + listOfNotNull(e.suivi?.catalogNumber) +
            e.agenda.filter { it.endOrStartMs >= debut }.mapNotNull { satDe(it.satName)?.catalogNumber }).distinct()
        val sats = nums.mapNotNull { n -> e.satellites.firstOrNull { it.catalogNumber == n } }.sortedBy { it.name.uppercase() }.take(30)
        debut to sats.map { s ->
            LigneFrise(s, (0 until heures * 60).map { k -> runCatching { predicteur.positionAt(s, obs, debut + k * 60_000L).elevationDeg }.getOrDefault(-90.0) })
        }
    }

    // ---------------------------------------------------------------- skeds

    /** A window both stations see the satellite: when, and how high for each. */
    class FenetreSked(val deMs: Long, val aMs: Long, val elMoi: Int, val elAutre: Int, val azMoi: Double, val azAutre: Double,
                      val debutTrace: Long, val finTrace: Long)

    /**
     * The windows of the next 48 hours in which I and the station in square
     * [locator] (seeing above [minElAutre]°) both see [catnum], a minute at
     * least: as the phone's mutual sked.
     */
    suspend fun sked(catnum: Int, locator: String, minElAutre: Double): List<FenetreSked>? = withContext(Dispatchers.Default) {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return@withContext emptyList()
        val ll = Maidenhead.centre(locator.trim()) ?: return@withContext null
        val a = e.observateur ?: return@withContext emptyList()
        val b = Observer(ll.first, ll.second, 0.0, locator.trim().uppercase())
        val now = horloge()
        val pa = runCatching { predicteur.upcomingPasses(sat, a, now, 48, settings.minElevDeg.toDouble()) }.getOrDefault(emptyList())
        val pb = runCatching { predicteur.upcomingPasses(sat, b, now, 48, minElAutre) }.getOrDefault(emptyList())
        val out = ArrayList<FenetreSked>()
        for (x in pa) for (y in pb) {
            val de = maxOf(x.aosEpochMs, y.aosEpochMs); val fin = minOf(x.losEpochMs, y.losEpochMs)
            if (fin - de < 60_000L) continue
            val mi = (de + fin) / 2
            val pA = runCatching { predicteur.positionAt(sat, a, mi) }.getOrNull()
            val pB = runCatching { predicteur.positionAt(sat, b, mi) }.getOrNull()
            out += FenetreSked(de, fin, x.maxElevationDeg.toInt(), y.maxElevationDeg.toInt(), pA?.azimuthDeg ?: 0.0, pB?.azimuthDeg ?: 0.0,
                minOf(x.aosEpochMs, y.aosEpochMs), maxOf(x.losEpochMs, y.losEpochMs))
        }
        out.sortedBy { it.deMs }
    }

    /** One instant of a sked replayed: each side's look angles, where the satellite is over. */
    class PointSked(val t: Long, val azMoi: Double, val elMoi: Double, val azAutre: Double, val elAutre: Double,
                    val lat: Double, val lon: Double, val altKm: Double)

    /** Both stations and the instants from [deMs] to [aMs] (both passes), a few hundred at most. */
    class TraceSked(val moi: Observer, val autre: Observer, val points: List<PointSked>)

    /**
     * The sked replayed, as the phone's animation: from the first station's
     * rise to the last one's set, every few seconds, both skies and the
     * satellite's place over the earth.
     */
    suspend fun traceSked(catnum: Int, locator: String, deMs: Long, aMs: Long): TraceSked? = withContext(Dispatchers.Default) {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return@withContext null
        val ll = Maidenhead.centre(locator.trim()) ?: return@withContext null
        val a = e.observateur ?: return@withContext null
        val b = Observer(ll.first, ll.second, 0.0, locator.trim().uppercase())
        if (aMs <= deMs || aMs - deMs > 6 * 3_600_000L) return@withContext null
        val pas = maxOf(2_000L, (aMs - deMs) / 400)
        val pts = ArrayList<PointSked>()
        var t = deMs
        while (t <= aMs) {
            val pA = runCatching { predicteur.positionAt(sat, a, t) }.getOrNull()
            val pB = runCatching { predicteur.positionAt(sat, b, t) }.getOrNull()
            if (pA != null && pB != null) pts += PointSked(t, pA.azimuthDeg, pA.elevationDeg, pB.azimuthDeg, pB.elevationDeg, pA.latDeg, pA.lonDeg, pA.altKm)
            t += pas
        }
        TraceSked(a, b, pts)
    }

    /** A sked into the agenda (its window), as an appointment of kind SKED. */
    fun skedAgenda(catnum: Int, locator: String, deMs: Long, aMs: Long) {
        val sat = _etat.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        change { copy(agenda = AgendaStore.put(stockageAgenda, AgendaStore.AgendaEvent(id = horloge(), title = "Sked " + locator.trim().uppercase(),
            timeMs = deMs, satName = sat.name, leadMin = 10, endMs = aMs, kind = "SKED"))) }
    }

    // ---------------------------------------------------- each satellite's own

    /**
     * The followed satellite's calibration and TX shift, changed by hand or
     * by the TX knob, kept for it; [memorise] keeps them as its reference,
     * [retour] goes back to it.
     */
    fun reglageSat(calibHz: Long? = null, txHz: Long? = null, memorise: Boolean = false, retour: Boolean = false) {
        val sat = _etat.value.suivi ?: return
        val n = sat.catalogNumber
        if (retour) {
            val c = satConfig.load(n)
            c.refCalibShiftHz?.let { satConfig.saveShift(n, it) }
            c.refTxShiftHz?.let { satConfig.saveTxShift(n, it) }
        }
        calibHz?.let { satConfig.saveShift(n, it) }
        txHz?.let { satConfig.saveTxShift(n, it) }
        if (memorise) satConfig.load(n).let { satConfig.memoriseReference(n, it.calibShiftHz, it.txShiftHz) }
        val c = satConfig.load(n)
        change { copy(calibHz = c.calibShiftHz, txShiftHz = c.txShiftHz, refCalibHz = c.refCalibShiftHz, refTxHz = c.refTxShiftHz) }
    }

    /** The next passes of every satellite, over 24 h. */
    suspend fun recalcule() {
        val obs = _etat.value.observateur ?: return
        val sats = _etat.value.satellites
        change { copy(calculEnCours = true) }
        val debut = horloge()
        val l = withContext(Dispatchers.Default) {
            sats.flatMap { s ->
                if (!PassPredictor.elementsUtilisables(s)) emptyList()
                else runCatching { predicteur.upcomingPasses(s, obs, debut - 15 * 60_000L, 24, settings.minElevDeg.toDouble()) }
                    .getOrDefault(emptyList()).filter { it.losEpochMs >= debut }
            }.sortedBy { it.aosEpochMs }
        }
        change { copy(passages = l, calculEnCours = false) }
    }

    // ------------------------------------------------------------- choices

    fun suis(sat: TleEntry) {
        stockage.edit().putInt(CLE_SATELLITE, sat.catalogNumber).apply()
        stationCat.nouveauSatellite()
        stationCat.oublieArmement()
        derniereRecherche = 0L
        val c = satConfig.load(sat.catalogNumber)
        derniereSuite = 0L
        change { copy(suivi = sat, position = null, passage = null, arc = emptyList(), emetteurs = emptyList(),
            emetteur = 0, repos = null, passagesSuivi = emptyList(),
            calibHz = c.calibShiftHz, txShiftHz = c.txShiftHz, refCalibHz = c.refCalibShiftHz, refTxHz = c.refTxShiftHz) }
        portee.launch(Dispatchers.IO) {
            val l = catalogue.emetteurs(sat.catalogNumber).filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
            // The transmitter chosen last time for this satellite, as on the phone.
            val i = c.txDescription?.let { d -> l.indexOfFirst { it.description == d } }?.takeIf { it >= 0 } ?: 0
            change { if (suivi?.catalogNumber == sat.catalogNumber) copy(emetteurs = l, emetteur = i) else this }
        }
    }

    fun suis(catnum: Int): Boolean {
        val s = _etat.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return false
        suis(s); return true
    }

    fun choisitEmetteur(i: Int) {
        stationCat.oublieArmement()
        change { copy(emetteur = i.coerceIn(0, (emetteurs.size - 1).coerceAtLeast(0)), repos = null) }
        _etat.value.let { e -> e.suivi?.let { s -> satConfig.saveTransmitter(s.catalogNumber, e.emetteurs.getOrNull(e.emetteur)?.description) } }
    }

    fun emetteurChoisi(): Transmitter? = _etat.value.let { it.emetteurs.getOrNull(it.emetteur) }

    /** The rest moved by [dHz] in the passband (a linear transponder), from where it is. */
    fun deplaceRepos(dHz: Long) {
        val t = emetteurChoisi() ?: return
        val lo = t.downlinkLowHz ?: return
        val hi = t.downlinkHighHz ?: lo
        val ici = _etat.value.repos ?: (lo + hi) / 2
        change { copy(repos = (ici + dHz).coerceIn(minOf(lo, hi), maxOf(lo, hi))) }
    }

    fun maintien(on: Boolean) = change { copy(maintienDoppler = on) }

    /** My square (or exact place), then the passes again. */
    fun poseLocator(loc: String): Boolean {
        val l = loc.trim().uppercase()
        if (Maidenhead.centre(l) == null) return false
        settings.manualLocator = l
        settings.manualLat = Double.NaN; settings.manualLon = Double.NaN
        change { copy(locator = l, observateur = observateur(), passage = null) }
        portee.launch { recalcule() }
        return true
    }

    fun poseIndicatif(c: String) {
        settings.callsign = c.trim().uppercase()
        change { copy(indicatif = settings.callsign) }
    }

    /** The phone's settings (its backup file), then everything that reads them again. */
    fun importeTelephone(json: String, carnet: fr.f4ioz.satcombo.data.LogStore? = null): Result<Pair<Int, Int>> {
        val r = ImportTelephone.importe(json, stockage).map { it to (carnet?.let { c -> ImportTelephone.importeCarnet(json, c) } ?: 0) }
        if (r.isSuccess) {
            ajouteFavoris(ImportTelephone.favoris(json))
            // Each satellite's settings and the agenda, as the phone kept them.
            ImportTelephone.importeStore(json, fr.f4ioz.satcombo.data.SatConfigStore.PREFS, stockageSat)
            ImportTelephone.importeStore(json, AgendaStore.PREFS, stockageAgenda)
            rechargeAgenda()
            _etat.value.suivi?.let { s -> val c = satConfig.load(s.catalogNumber)
                change { copy(calibHz = c.calibShiftHz, txShiftHz = c.txShiftHz, refCalibHz = c.refCalibShiftHz, refTxHz = c.refTxShiftHz) } }
        }
        if (r.isSuccess) {
            stationRotor.rechargeReglages()
            stationRotor.setRotorAutoPort(false)
            stationCat.regleMaintien(settings.catHoldMs.toLong())
            change { copy(locator = settings.manualLocator, indicatif = settings.callsign, observateur = observateur(),
                catModele = if (catConnecte) catModele else modeleDe(settings.rigModel), passage = null) }
            portee.launch { recalcule() }
        }
        return r
    }

    fun rafraichisCatalogue() {
        portee.launch(Dispatchers.IO) {
            change { copy(catalogue = "téléchargement des éléments AMSAT…") }
            val dit = catalogue.rafraichis()
            change { copy(catalogue = dit, satellites = this@StationPc.catalogue.satellites()) }
            recalcule()
        }
    }

    private fun observateur(): Observer? {
        val la = settings.manualLat; val lo = settings.manualLon
        if (!la.isNaN() && !lo.isNaN()) return Observer(la, lo, 0.0, settings.manualLocator)
        val c = Maidenhead.centre(settings.manualLocator) ?: return null
        return Observer(c.first, c.second, 0.0, settings.manualLocator)
    }

    /** What the sky gives now for the transmitter chosen: (downlink, uplink) with Doppler, Hz. */
    fun frequencesDuCiel(): Pair<Long?, Long?> {
        val t = emetteurChoisi() ?: return null to null
        val rr = _etat.value.position?.rangeRateKmS ?: 0.0
        val dlLo = t.downlinkLowHz; val dlHi = t.downlinkHighHz ?: dlLo
        val repos = _etat.value.repos ?: dlLo?.let { (it + (dlHi ?: it)) / 2 }
        val ul = t.uplinkLowHz?.let { ulLo ->
            val ulHi = t.uplinkHighHz ?: ulLo
            // Linear: the uplink that lands on the rest, inverting or not.
            if (repos != null && dlLo != null && dlHi != null && dlHi != dlLo && ulHi != ulLo) {
                val part = (repos - dlLo).toDouble() / (dlHi - dlLo)
                if (t.invert) ulHi - Math.round(part * (ulHi - ulLo)) else ulLo + Math.round(part * (ulHi - ulLo))
            } else (ulLo + ulHi) / 2
        }
        // As the rig is set: the calibration after the Doppler, the TX shift on the uplink at rest.
        val e = _etat.value
        return repos?.let { Doppler.downlink(it, rr) + e.calibHz } to ul?.let { Doppler.uplink(it + e.txShiftHz, rr) }
    }

    // ---------------------------------------------------------- connecting

    /** How the operator wants the rig connected: the model and the port(s) chosen on the page. */
    data class ChoixCat(
        val modele: String,
        /** The rig's port (Icom, TS-2000, TH-D72), or the RX rig's in a pair; -1: none. */
        val port: Int = -1,
        val baud: Int = 0,
        /** A pair: the TX rig's port (-1: none), and which side is an IC-705. */
        val portTx: Int = -1,
        val rxIc705: Boolean = false,
        val txIc705: Boolean = false,
        /** TH-D72: the band that transmits (0 = A, 1 = B). */
        val bandeTx: Int = 0,
    )

    /**
     * Opens the rig on the port(s) chosen, nothing else tried: an Icom on
     * CI-V, a TS-2000, an FT-817 / IC-705 pair, a TH-D72 — or a simulated
     * IC-9700 or TS-2000, nothing opened.
     */
    fun connecteCat(modele: String, index: Int, baud: Int) = connecteCat(ChoixCat(modele, index, baud))

    fun connecteCat(c: ChoixCat) {
        portee.launch {
            fil.withLock {
                deconnecteCatDedans()
                val m = modeleDe(c.modele)
                if (m !in SIMULES) settings.rigModel = m
                val liste = ports.liste()
                val p = liste.getOrNull(c.port)
                val pTx = liste.getOrNull(c.portTx)
                fun echec(pourquoi: String) = change { copy(catModele = m, catConnecte = false, catSimule = false, catStatut = "échec : $pourquoi") }
                when (m) {
                    "SIM" -> {
                        civ.modele = ModeleIcom.IC9700
                        civ.radioAddr = ModeleIcom.IC9700.adresse
                        civ.pacingMs = 0L
                        civ.attach(Ic9700Sim())
                        change { copy(catModele = "IC9700", catConnecte = true, catSimule = true,
                            catStatut = "IC-9700 simulé : rien n'est ouvert") }
                    }
                    "TS2000SIM" -> {
                        paire.configureTs2000()
                        paire.rx.attach(fr.f4ioz.satcombo.cat.Ts2000Sim())
                        paire.pacingMs = 0L
                        change { copy(catModele = "TS2000", catConnecte = true, catSimule = true,
                            catStatut = "TS-2000 simulé : rien n'est ouvert") }
                    }
                    "TS2000" -> {
                        paire.configureTs2000()
                        val l = paire.lienTs2000!!
                        l.pacingMs = 20L
                        if (p == null) { echec("aucun port choisi"); return@withLock }
                        if (!l.ouvrePort(p, if (c.baud > 0) c.baud else 57_600))
                            { echec("pas de TS-2000 qui réponde sur ${p.libelle} (vitesse : menu 56 ; RigExpert : port A)"); return@withLock }
                        clePoste = p.cle
                        change { copy(catModele = m, catConnecte = true, catSimule = false,
                            catStatut = "Kenwood TS-2000 sur ${p.libelle} à ${l.vitesse} bauds") }
                    }
                    "THD72" -> {
                        paire.configureThd72(c.bandeTx.coerceIn(0, 1))
                        val l = paire.lienThd72!!
                        if (p == null) { echec("aucun port choisi"); return@withLock }
                        if (!l.open(p.cle, if (c.baud > 0) c.baud else 9_600) || !l.estUnThd72())
                            { l.close(); echec("pas de TH-D72 qui réponde sur ${p.libelle}"); return@withLock }
                        clePoste = p.cle
                        change { copy(catModele = m, catConnecte = true, catSimule = false,
                            catStatut = "Kenwood TH-D72 sur ${p.libelle}, émission sur la bande " + (if (c.bandeTx == 0) "A" else "B")) }
                    }
                    "PAIRE" -> {
                        if (p == null && pTx == null) { echec("aucun port choisi"); return@withLock }
                        if (p != null && pTx != null && p.cle == pTx.cle) { echec("le même port des deux côtés"); return@withLock }
                        paire.configure(rxIc705 = c.rxIc705, txIc705 = c.txIc705)
                        paire.pacingMs = 25L
                        val vitesse = if (c.baud > 0) c.baud else settings.ft817Baud
                        val (a, b) = paire.open(p?.cle, pTx?.cle, vitesse)
                        if ((p != null && !a) || (pTx != null && !b)) {
                            paire.close()
                            echec("le poste " + (if (p != null && !a) "RX" else "TX") + " ne s'ouvre pas"); return@withLock
                        }
                        clePoste = listOfNotNull(p?.cle, pTx?.cle).joinToString(",")
                        fun nom(ic: Boolean) = if (ic) "IC-705" else "FT-817"
                        change { copy(catModele = m, catConnecte = true, catSimule = false,
                            catStatut = listOfNotNull(p?.let { "RX ${nom(c.rxIc705)} sur ${it.libelle}" },
                                pTx?.let { "TX ${nom(c.txIc705)} sur ${it.libelle}" }).joinToString(" · ")) }
                    }
                    else -> {
                        // The CI-V raises RTS/DTR: never on a cable named for another rig (a TS-2000's RigExpert).
                        if (p != null && fr.f4ioz.satcombo.cat.CatScan.nomAutrePoste(p.produit))
                            { echec("${p.libelle} est l'interface d'un autre poste : le CI-V le ferait émettre"); return@withLock }
                        val mi = ModeleIcom.de(m)
                        civ.modele = mi
                        civ.radioAddr = mi.adresse
                        civ.pacingMs = 40L
                        val ok = p != null && civ.open(c.port, if (c.baud > 0) c.baud else mi.baud)
                        if (ok) {
                            clePoste = p!!.cle
                            settings.civUsbIndex = c.port
                        }
                        change { copy(catModele = m, catConnecte = ok, catSimule = false,
                            catStatut = if (ok) "${mi.libelle} sur ${p!!.libelle}" else "échec : ${civ.lastError.ifBlank { "port introuvable" }}") }
                    }
                }
                if (_etat.value.catConnecte) lanceBoucleCat()
            }
        }
    }

    fun deconnecteCat() { portee.launch { fil.withLock { deconnecteCatDedans() } } }

    private fun deconnecteCatDedans() {
        boucleCat?.cancel(); boucleCat = null
        civ.close()
        paire.close()
        stationCat.deconnecte()
        change { copy(catConnecte = false, catSimule = false, catStatut = "poste déconnecté") }
    }

    /** The Doppler loop, ten times a second, as on the phone. */
    private fun lanceBoucleCat() {
        boucleCat?.cancel()
        boucleCat = portee.launch {
            while (_etat.value.catConnecte) {
                runCatching { stationCat.tour() }
                delay(100)
            }
        }
    }

    companion object {
        /** The rigs the PC drives: the Icom family on CI-V, the pairs (TS-2000, FT-817 / IC-705, TH-D72), and simulators. */
        val MODELES = listOf("IC9700", "IC9100", "IC910", "TS2000", "PAIRE", "THD72", "SIM", "TS2000SIM")
        /** Driven as a pair: each leg its own rig or VFO. */
        val PAIRES = setOf("TS2000", "PAIRE", "THD72")
        val SIMULES = setOf("SIM", "TS2000SIM")
        /** The satellite followed, kept between two starts. */
        const val CLE_SATELLITE = "pc_satellite"
        const val CLE_FAVORIS = "pc_favoris"
        const val CLE_AGENDA_SUIVI = "pc_agenda_suivi"
        fun modeleDe(id: String): String = id.takeIf { it in MODELES } ?: "IC9700"
    }
}
