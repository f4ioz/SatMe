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
import fr.f4ioz.satcombo.domain.BandeConversion
import fr.f4ioz.satcombo.domain.Convertisseur
import fr.f4ioz.satcombo.domain.TableConversion
import fr.f4ioz.satcombo.domain.UsageConversion
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
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.SoleilQo100
import fr.f4ioz.satcombo.station.PiloteQo100
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
    /** The satellite followed's pass just over (its contacts still shown a while). */
    val dernierPassage: SatPass? = null,
    /** The skeds announced on hams.at (Réglages: off by default, as on the phone). */
    val skeds: List<fr.f4ioz.satcombo.data.SkedAlert> = emptyList(),
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
    /**
     * The rig tuned to the followed satellite even while it is below the
     * horizon (to be ready before AOS, or on the bench) — off unless chosen;
     * the phone's own setting has another default.
     */
    /** The page on this PC stops a transmission after 2 s without news too, like a remote one. */
    var txChienLocal: Boolean
        get() = stockage.getBoolean("tx_chien_local_pc", false)
        set(v) { stockage.edit().putBoolean("tx_chien_local_pc", v).apply() }

    var catSousHorizon: Boolean
        get() = stockage.getBoolean("cat_sous_horizon_pc", false)
        set(v) { stockage.edit().putBoolean("cat_sous_horizon_pc", v).apply() }
    private val predicteur = PassPredictor()

    private val _etat = MutableStateFlow(EtatPc())
    val etat: StateFlow<EtatPc> = _etat.asStateFlow()
    private fun change(f: EtatPc.() -> EtatPc) = _etat.update { it.f() }

    // ------------------------------------------------------------------ CAT

    private val civ = CivController(ports)
    private val posteCiv = PosteCatCiv(civ)
    /**
     * « Émission par le réseau autorisée » (Réglages › Émission du poste): off
     * by default, changed only from this PC (never from a remote page nor a
     * phone backup). Off, the network link refuses PTT and asks no TX audio.
     */
    var txReseauAutorise: Boolean
        get() = stockage.getBoolean(CLE_TX_RESEAU, false)
        set(v) { stockage.edit().putBoolean(CLE_TX_RESEAU, v).apply() }

    /** The IC-9700 on the network (Réglages › Poste › Liaison « Réseau »): the CAT's CI-V and a sound source. */
    val icomReseau = fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau(settings, emissionAutorisee = { txReseauAutorise })
    /** The rigs driven as a pair, the phone's drivers: FT-817 / IC-705 (one each side), TS-2000, TH-D72. */
    private val paire = fr.f4ioz.satcombo.cat.Ft817Pair(ports)
    private val postePaire = fr.f4ioz.satcombo.cat.PosteCatPaire(paire)
    private val fil = Mutex()
    private var boucleCat: Job? = null
    /** The adapter the rig is on: the mast's search never opens it. */
    @Volatile private var clePoste: String = ""

    /**
     * The automatic SSTV tuning's share of the downlink (sky Hz), added to
     * the Doppler loop's own writes (see [SstvPc.afc]); 0 when off.
     */
    @Volatile var afcSstvHz: Long = 0L

    val stationCat = StationCat(object : MondeCat {
        override fun connecte() = _etat.value.catConnecte
        // Behind the converter table's guard: a frequency no rig band and no line's IF holds is not sent.
        override fun poste(): PosteCat = fr.f4ioz.satcombo.cat.PosteCatFiltre(if (_etat.value.catModele in PAIRES) postePaire else posteCiv, ::atteignableCat)
        override fun atteignable(posteHz: Long) = atteignableCat(posteHz)
        // A pair with only its TX rig: nothing to read back.
        override fun emetSeul() = _etat.value.catModele in PAIRES && !paire.rx.isOpen && paire.tx.isOpen
        override fun aprsEnEmission() = false
        override fun satellite() = _etat.value.position
        override fun catnum() = _etat.value.suivi?.catalogNumber
        override fun transpondeur() = emetteurChoisi()
        override fun sousHorizonAussi() = catSousHorizon
        override fun calibrationHz() = _etat.value.calibHz
        override fun decalageTxHz() = _etat.value.txShiftHz
        override fun poseDecalageTx(hz: Long) = reglageSat(txHz = hz)
        override fun decalageRxHz() = 0L
        override fun afcRxHz() = afcSstvHz
        override fun modeCw() = false
        // QO-100 drives the rig itself, when asked: the Doppler loop then holds.
        override fun maintienDoppler() = _etat.value.maintienDoppler || qo100.etat.value.auPoste
        override fun dopplerRx() = true
        override fun moletteTxDecalage() = false
        override fun txSuitVite() = false
        override fun modelePoste() = _etat.value.catModele
        override fun inversionForcee(): Boolean? = null
        override fun ctcssChoisi() = 0
        override fun ctcssAuto() = true
        override fun repos() = _etat.value.repos
        override fun poseRepos(hz: Long) = change { copy(repos = hz) }
        // The converters (LNB, transverter): the table's first line for the rig's RX or TX side.
        override fun posteRx(satHz: Long) = posteRxCat(satHz)
        override fun satDepuisPoste(posteHz: Long): Long {
            val attendu = cielAttendu(descente = true) ?: return posteHz
            return TableConversion.depuisAppareil(bandes(), posteHz, UsageConversion.CAT_RX, rfAttendu = attendu)
        }
        override fun posteTx(satHz: Long) = posteTxCat(satHz)
        override fun satDepuisPosteTx(posteHz: Long): Long {
            val attendu = cielAttendu(descente = false) ?: return posteHz
            return TableConversion.depuisAppareil(bandes(), posteHz, UsageConversion.CAT_TX, rfAttendu = attendu)
        }
    }, horloge)

    // ---------------------------------------------------------------- the converter table

    /** The two converters as they were set before the table (and still are by the QO-100 chain). */
    private fun convRxAncien() = Convertisseur(actif = settings.convRxActif, olHz = settings.convRxOlHz,
        inverseur = settings.convRxInverseur, basHz = settings.convRxBasHz, hautHz = settings.convRxHautHz)
    private fun convTxAncien() = Convertisseur(actif = settings.convTxActif, olHz = settings.convTxOlHz,
        inverseur = settings.convTxInverseur, basHz = settings.convTxBasHz, hautHz = settings.convTxHautHz)

    @Volatile private var tableLue: Pair<String?, List<BandeConversion>>? = null

    /**
     * The converter table (Réglages › Poste et rotor). Made the first time
     * from the two older converters, which come first: nothing set before is
     * lost, nothing changes by itself.
     */
    fun bandes(): List<BandeConversion> {
        val texte = settings.convBandes
        tableLue?.let { (k, t) -> if (k == texte) return t }
        val t = TableConversion.lit(texte)
            ?: TableConversion.migre(convRxAncien(), settings.convRxCle, settings.convRxPoste, convTxAncien()).also { settings.convBandes = TableConversion.ecrit(it) }
        tableLue = settings.convBandes to t
        return t
    }

    /**
     * A new table from the page. The QO-100 chain's lines are written back to
     * its two converters, so its screen shows what is used.
     */
    fun poseBandes(t: List<BandeConversion>) {
        val table = t.take(TableConversion.MAX_LIGNES)
        settings.convBandes = TableConversion.ecrit(table)
        val rx = table.firstOrNull { it.chaine == TableConversion.CHAINE_RX }
        settings.convRxActif = rx?.actif == true
        rx?.let { settings.convRxOlHz = it.olHz; settings.convRxInverseur = it.inverse; settings.convRxBasHz = it.basHz
            settings.convRxHautHz = it.hautHz; settings.convRxCle = it.sdr; settings.convRxPoste = it.catRx }
        val tx = table.firstOrNull { it.chaine == TableConversion.CHAINE_TX }
        settings.convTxActif = tx?.actif == true
        tx?.let { settings.convTxOlHz = it.olHz; settings.convTxInverseur = it.inverse; settings.convTxBasHz = it.basHz; settings.convTxHautHz = it.hautHz }
        stationCat.oublieArmement()
    }

    /** The presets back, the QO-100 chain's lines kept on top. */
    fun retablitBandes() = poseBandes(TableConversion.migre(convRxAncien(), settings.convRxCle, settings.convRxPoste, convTxAncien()))

    /** The QO-100 chain was set elsewhere (its screen, the single LO fields): its lines follow. */
    private fun synchroniseChaine() {
        val t = TableConversion.avecChaine(bandes(), convRxAncien(), settings.convRxCle, settings.convRxPoste, convTxAncien())
        settings.convBandes = TableConversion.ecrit(t)
        stationCat.oublieArmement()
    }

    /** What the rig's RX is set to for the sky's [satHz]: the IF of its line, else [satHz]. */
    fun posteRxCat(satHz: Long): Long = TableConversion.versAppareil(bandes(), satHz, UsageConversion.CAT_RX)

    /** What the rig's TX is set to for the sky's [satHz]. */
    fun posteTxCat(satHz: Long): Long = TableConversion.versAppareil(bandes(), satHz, UsageConversion.CAT_TX)

    /** The dongle's line for the sky's [cielHz] as a converter ([Convertisseur.AUCUN] when none). */
    fun convSdr(cielHz: Long): Convertisseur =
        TableConversion.bande(bandes(), cielHz, UsageConversion.SDR)?.convertisseur() ?: Convertisseur.AUCUN

    /** The dongle's line producing [cleHz] as a converter ([Convertisseur.AUCUN] when none). */
    fun convSdrDeFi(cleHz: Long): Convertisseur =
        bandes().firstOrNull { it.sdr && it.accepteFi(cleHz) }?.convertisseur() ?: Convertisseur.AUCUN

    /**
     * May the rig be sent [posteHz]? One of its bands, or the IF of a line in
     * front of it (a 28 MHz transverter's, which no satellite rig band holds).
     */
    fun atteignableCat(posteHz: Long): Boolean =
        fr.f4ioz.satcombo.cat.BandPlan.band(posteHz) != fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE ||
            TableConversion.fiConnue(bandes(), posteHz, UsageConversion.CAT_RX) || TableConversion.fiConnue(bandes(), posteHz, UsageConversion.CAT_TX)

    /** The sky frequency the rig should be near: QO-100's when its tab holds the rig, else the transmitter followed. */
    private fun cielAttendu(descente: Boolean): Long? {
        val q = qo100.etat.value
        if (q.auPoste) return if (descente) q.descenteHz else q.monteeHz
        val t = emetteurChoisi() ?: return null
        return if (descente) t.downlinkLowHz else t.uplinkLowHz
    }

    /** What the rig is sent for one side: the sky, the IF, the line used, or why nothing is sent. */
    class FiCat(val cielHz: Long, val fiHz: Long, val bande: String?, val refus: String?)

    /** [cielHz] through the table for the rig's [usage] (CAT_RX or CAT_TX). */
    fun fiCat(cielHz: Long, usage: UsageConversion): FiCat {
        val b = TableConversion.bande(bandes(), cielHz, usage)
        val fi = b?.fi(cielHz) ?: cielHz
        val cote = if (usage == UsageConversion.CAT_TX) "émission" else "réception"
        val refus = if (b == null && !atteignableCat(cielHz)) String.format(java.util.Locale.FRANCE,
            "%.3f MHz (%s) : aucune ligne de conversion pour le poste, commande non envoyée (Réglages › Poste et rotor › Convertisseurs)", cielHz / 1e6, cote)
            else null
        return FiCat(cielHz, fi, b?.nom, refus)
    }

    /** The converters, set on the page (Hz; an LO of 0 turns one off): the QO-100 chain's lines. */
    fun poseConvertisseurs(rxOl: Long?, rxInverseur: Boolean?, txOl: Long?, txInverseur: Boolean?) {
        bandes()
        rxOl?.let { settings.convRxOlHz = it; settings.convRxActif = it > 0; settings.convRxPoste = it > 0 }
        rxInverseur?.let { settings.convRxInverseur = it }
        txOl?.let { settings.convTxOlHz = it; settings.convTxActif = it > 0 }
        txInverseur?.let { settings.convTxInverseur = it }
        synchroniseChaine()
    }

    /** The QRZ card in the log (photo, distance, map): the operator's choice, on by default. */
    var ficheQrz: Boolean
        get() = stockage.getBoolean("pc_qrz_fiche", true)
        set(v) { stockage.edit().putBoolean("pc_qrz_fiche", v).apply() }

    // ---------------------------------------------------------------- the SDR dongle

    /** What the dongle listens to: the transmitter's downlink at rest, with its Doppler now (sky, calibration in), its mode. */
    class EcouteSdr(val reposHz: Long, val cibleHz: Long, val mode: String, val satellite: String)

    fun ecouteSdr(): EcouteSdr? {
        val e = _etat.value
        // METEOR: the transmitter SatMe decodes (LRPT 72 k on 137.9 or 137.1), whichever is chosen.
        val t = if (estMeteor()) {
            val c = emetteurChoisi()
            if (c != null && fr.f4ioz.satcombo.meteor.RecepteurMeteor.voieDecodable(c.description, c.downlinkLowHz)) c
            else e.emetteurs.getOrNull(fr.f4ioz.satcombo.meteor.RecepteurMeteor.choisitVoie(e.emetteurs.map { it.description to it.downlinkLowHz })) ?: c
        } else emetteurChoisi()
        t ?: return null
        val lo = t.downlinkLowHz ?: return null
        val repos = e.repos ?: (lo + (t.downlinkHighHz ?: lo)) / 2
        // Below the horizon nothing is heard: the rest, not a Doppler that means nothing.
        val p = e.position
        val rr = if (p != null && p.elevationDeg >= 0) p.rangeRateKmS else 0.0
        return EcouteSdr(repos, Doppler.downlink(repos, rr) + e.calibHz, t.mode.orEmpty(), e.suivi?.name.orEmpty())
    }

    /** The satellite followed is a METEOR-M sending LRPT pictures. */
    fun estMeteor(): Boolean = _etat.value.suivi?.let { fr.f4ioz.satcombo.meteor.RecepteurMeteor.emetLrpt(it.catalogNumber, it.name) } == true

    /** Is the followed satellite going north now (the picture is turned so that north is up)? */
    fun monteVersLeNord(): Boolean {
        val sat = _etat.value.suivi ?: return false
        val obs = _etat.value.observateur ?: return false
        val t = horloge()
        return runCatching { predicteur.positionAt(sat, obs, t + 60_000).latDeg > predicteur.positionAt(sat, obs, t).latDeg }.getOrDefault(false)
    }

    /** The next METEOR LRPT pass (or the one under way) within [heures]: satellite and pass. */
    fun prochainPassageMeteor(heures: Int = 24): Pair<TleEntry, fr.f4ioz.satcombo.data.SatPass>? {
        val obs = _etat.value.observateur ?: return null
        val now = horloge()
        // Kept a minute: the page asks every second.
        cacheMeteor?.let { (h, t, r) -> if (h == heures && now - t < 60_000L && (r == null || r.second.losEpochMs > now)) return r }
        return calculeProchainMeteor(obs, now, heures).also { cacheMeteor = Triple(heures, now, it) }
    }
    @Volatile private var cacheMeteor: Triple<Int, Long, Pair<TleEntry, fr.f4ioz.satcombo.data.SatPass>?>? = null

    private fun calculeProchainMeteor(obs: Observer, now: Long, heures: Int): Pair<TleEntry, fr.f4ioz.satcombo.data.SatPass>? {
        return _etat.value.satellites.filter { fr.f4ioz.satcombo.meteor.RecepteurMeteor.emetLrpt(it.catalogNumber, it.name) }.mapNotNull { sat ->
            runCatching {
                predicteur.upcomingPasses(sat, obs, now - 20 * 60_000L, heures, settings.minElevDeg.toDouble())
                    .firstOrNull { it.losEpochMs > now }?.let { sat to it }
            }.getOrNull()
        }.minByOrNull { it.second.aosEpochMs }
    }

    /** A sky frequency as the dongle receives it: through the table's first line for it. */
    fun versCle(satHz: Long): Long = TableConversion.versAppareil(bandes(), satHz, UsageConversion.SDR)

    /** The dongle's frequency back to the sky. */
    fun depuisCle(cleHz: Long): Long = TableConversion.depuisAppareil(bandes(), cleHz, UsageConversion.SDR)

    // ---------------------------------------------------------------- QO-100

    /** [catnum]'s passes between [deMs] and [aMs] (any height: the caller sorts them out); empty when unknown. */
    fun passagesDe(catnum: Int, deMs: Long, aMs: Long): List<SatPass> {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return emptyList()
        val obs = e.observateur ?: return emptyList()
        if (aMs <= deMs || !PassPredictor.elementsUtilisables(sat)) return emptyList()
        val heures = ((aMs - deMs + 3_599_999L) / 3_600_000L).toInt().coerceIn(1, 24 * 15)
        return runCatching { predicteur.upcomingPasses(sat, obs, deMs, heures, 0.0, settings.masqueHorizon) }.getOrDefault(emptyList())
            .filter { it.aosEpochMs < aMs }
    }

    /** Where [catnum] was (az, el) at each of [ts], from the elements loaded; null when it is unknown. */
    fun positionsDe(catnum: Int, ts: List<Long>): List<Pair<Double, Double>>? {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        val obs = e.observateur ?: return null
        if (!PassPredictor.elementsUtilisables(sat)) return null
        return runCatching { predicteur.samplePositions(sat, obs, ts).map { it.azimuthDeg to it.elevationDeg } }.getOrNull()
    }

    /** The epoch of [catnum]'s elements, null unknown. */
    fun epoqueElements(catnum: Int): Long? = _etat.value.satellites.firstOrNull { it.catalogNumber == catnum }?.epochMs

    /** A satellite's NORAD number from its name (« ISS » for « ISS (ZARYA) »), 0 unknown. */
    fun catnumDe(nom: String): Int {
        fun n(s: String) = s.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "")
        val k = n(nom)
        if (k.isEmpty()) return 0
        if (k == "ISS") return 25544
        val l = _etat.value.satellites
        return (l.firstOrNull { n(it.name) == k } ?: l.firstOrNull { n(it.name).startsWith(k) || k.startsWith(n(it.name)) && n(it.name).length >= 3 })?.catalogNumber ?: 0
    }

    /** The pass of [catnum] under way at [t] (AOS, LOS); null when it is down or unknown. */
    fun passageEnCours(catnum: Int, t: Long): Pair<Long, Long>? {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        val obs = e.observateur ?: return null
        return runCatching { predicteur.currentPass(sat, obs, t) }.getOrNull()
    }

    /** Where satellite [catnum] is from here at [t] (elevation, range rate…); null when unknown. */
    fun positionSatellite(catnum: Int, t: Long): fr.f4ioz.satcombo.data.SatPosition? {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        val obs = e.observateur ?: return null
        return runCatching { predicteur.positionAt(sat, obs, t) }.getOrNull()
    }

    /** [catnum]'s passes over in the last three days, newest first: where a contact typed afterwards goes. */
    fun passagesFinis(catnum: Int): List<fr.f4ioz.satcombo.data.SatPass> {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return emptyList()
        val obs = e.observateur ?: return emptyList()
        return fr.f4ioz.satcombo.demo.ApiCalculs.passagesFinis(predicteur, sat, obs, horloge(), 72, settings.minElevDeg.toDouble())
    }

    /** A contact typed afterwards, on [catnum] at [quandMs] (the engine's, as on the phone); null: unknown satellite. */
    fun contactApres(catnum: Int, quandMs: Long, indicatif: String, locator: String, rstEnvoye: String, rstRecu: String,
                     note: String, mode: String): fr.f4ioz.satcombo.data.LogEntry? {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        return fr.f4ioz.satcombo.demo.ApiCalculs.contactApres(predicteur, sat, e.observateur, quandMs, indicatif, locator,
            rstEnvoye, rstRecu, note, mode, e.locator)
    }

    /** Each pass with a contact reported "Heard" to amsat.org, when chosen (the phone's, the same). */
    val pushAmsat = fr.f4ioz.satcombo.domain.PushAmsat(settings)

    /** QO-100 as the phone's screen drives it: written when moved, never by a loop. */
    val qo100: PiloteQo100 by lazy {
        PiloteQo100(settings, satConfig, object : PiloteQo100.Materiel {
            override fun posteRx(satHz: Long) = posteRxCat(satHz)
            override fun posteTx(satHz: Long) = posteTxCat(satHz)
            override fun atteignable(posteHz: Long) = stationCat.atteignableParLePoste(posteHz)
            override fun ecris(dlSat: Long, ulSat: Long) {
                if (!_etat.value.catConnecte) { qo100.erreur("Poste non connecté (onglet Station)."); return }
                portee.launch {
                    runCatching { stationCat.ecrireCouple(dlSat, ulSat, sameBand = false) }
                        .onFailure { qo100.erreur(it.message ?: "CAT ?") }
                }
            }
            override fun convertisseursChanges() = synchroniseChaine()
        }).apply { ouvre() }
    }

    /** The followed satellite's rest, kept while QO-100 has the rig. */
    @Volatile private var reposAvantQo100: Long? = null

    /**
     * The rig to QO-100, or back. While held, the loop reads the rig and takes
     * what it finds as the followed satellite's rest — QO-100's frequency: the
     * rest is kept on taking, put back on giving, and the satellite armed anew.
     */
    fun qo100AuPoste(on: Boolean) {
        if (on == qo100.etat.value.auPoste) return
        if (on) reposAvantQo100 = _etat.value.repos
        qo100.auPoste(on)
        if (!on) {
            change { copy(repos = reposAvantQo100) }
            stationCat.oublieArmement()
        }
    }

    /** The dish's aim and the Sun's dates, for a square: computed once (a year of Sun, a second or two). */
    class CielQo100(val locator: String, val pointage: Qo100.Pointage, val soleilAzimutMs: Long?, val transits: List<SoleilQo100.Transit>)
    @Volatile private var cielQo100: CielQo100? = null
    @Volatile private var cielQo100EnCours = false

    fun cielQo100(): CielQo100? {
        val obs = _etat.value.observateur ?: return null
        val loc = _etat.value.locator
        cielQo100?.takeIf { it.locator == loc }?.let { return it }
        if (!cielQo100EnCours) {
            cielQo100EnCours = true
            portee.launch(Dispatchers.Default) {
                try {
                    val p = Qo100.pointage(obs.latDeg, obs.lonDeg)
                    val now = horloge()
                    val az = if (p.visible) runCatching { SoleilQo100.prochainPassageEnAzimut(obs.latDeg, obs.lonDeg, now) }.getOrNull() else null
                    val tr = if (p.visible) runCatching { SoleilQo100.prochainsTransits(obs.latDeg, obs.lonDeg, now, maximum = 4) }.getOrDefault(emptyList()) else emptyList()
                    cielQo100 = CielQo100(loc, p, az, tr)
                } finally { cielQo100EnCours = false }
            }
        }
        return null
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
        runCatching { reprendVeille() }
        stationCat.regleMaintien(settings.catHoldMs.toLong())
        change { copy(agenda = AgendaStore.purge(stockageAgenda, nowMs = horloge()),
            agendaSuivi = stockage.getBoolean(CLE_AGENDA_SUIVI, true)) }
        catalogue.choix = choixSources()
        change { copy(favoris = lisFavoris(), satellites = this@StationPc.catalogue.satellites(), locator = settings.manualLocator,
            indicatif = settings.callsign, observateur = observateur(), catModele = modeleDe(settings.rigModel)) }
        stockage.getInt(CLE_SATELLITE, 0).takeIf { it > 0 }?.let { n -> _etat.value.satellites.firstOrNull { it.catalogNumber == n }?.let { suis(it) } }
        portee.launch(Dispatchers.IO) {
            val age = catalogue.age(horloge())
            if (age == null || age > 12 * 3_600_000L || catalogue.choixChange()) {
                change { copy(catalogue = "téléchargement des éléments…") }
                val dit = catalogue.rafraichis()
                change { copy(catalogue = dit, satellites = this@StationPc.catalogue.satellites()) }
            } else change { copy(catalogue = "éléments du disque (${age / 3_600_000L} h)") }
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
        // hams.at's announced skeds, when chosen: every 5 minutes.
        portee.launch(Dispatchers.IO) {
            while (true) {
                runCatching { rafraichisSkeds() }
                delay(5 * 60_000L)
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

    /** A pass's track with its times, every 5 s from AOS to LOS (above the horizon only). */
    private fun traceTemps(sat: fr.f4ioz.satcombo.data.TleEntry, obs: fr.f4ioz.satcombo.data.Observer,
                           aos: Long, los: Long): List<Triple<Long, Double, Double>> {
        val n = ((los - aos) / 5_000L).toInt().coerceIn(2, 400)
        return (0..n).mapNotNull { i ->
            val t = aos + (los - aos) * i / n
            predicteur.positionAt(sat, obs, t).let { p -> if (p.elevationDeg >= 0) Triple(t, p.azimuthDeg, p.elevationDeg) else null }
        }
    }

    /** The satellite followed, now: where it is, its pass. */
    private suspend fun tic() {
        val maintenant = horloge()
        suitAgenda(maintenant)
        suitVeille(maintenant)
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
        val nouveau = passage != null && passage != e.passage && sat != null && obs != null
        val arc = if (nouveau)
            runCatching { predicteur.passTrack(sat!!, obs!!, passage!!.aosEpochMs, passage.losEpochMs) }.getOrDefault(emptyList())
        else e.arc
        // The same pass with its times, every 5 s: the mast's whole-pass plan is made on it
        // (side of the stop, flip, unwind and when). Without it the PC only unwound as it came.
        if (nouveau) stationRotor.traceTemps = withContext(Dispatchers.Default) {
            runCatching { traceTemps(sat!!, obs!!, passage!!.aosEpochMs, passage.losEpochMs) }.getOrDefault(emptyList())
        } else if (passage == null && stationRotor.traceTemps.isNotEmpty()) stationRotor.traceTemps = emptyList()
        // Its next passes, again when they move on (one gone) or the satellite changed.
        val suivants = e.passagesSuivi
        val aRefaire = sat != null && obs != null && (suivants.isEmpty() || suivants.first().losEpochMs < maintenant ||
            suivants.first().catalogNumber != sat.catalogNumber) && maintenant - derniereSuite >= 60_000L
        val nouveaux = if (aRefaire) {
            derniereSuite = maintenant
            val masque = settings.masqueHorizon
            withContext(Dispatchers.Default) {
                runCatching { predicteur.upcomingPasses(sat!!, obs!!, maintenant - 30 * 60_000L, 72, settings.minElevDeg.toDouble(), masque) }
                    .getOrDefault(emptyList()).filter { it.losEpochMs >= maintenant }.take(8)
            }
        } else suivants
        val fini = e.passage?.takeIf { it.losEpochMs < maintenant && it != passage && it.catalogNumber == sat?.catalogNumber }
        change { copy(maintenant = maintenant, position = pos, passage = passage, arc = arc, passagesSuivi = nouveaux,
            dernierPassage = fini ?: dernierPassage) }
        runCatching { noteDoppler(maintenant) }
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
        val mis = fr.f4ioz.satcombo.demo.ApiCalculs.basculePassage(stockageAgenda, _etat.value.agenda, p, horloge())
        change { copy(agenda = AgendaStore.load(stockageAgenda)) }
        return mis
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

    // ------------------------------------------------------- automatic mode

    /** The automatic mode on: the chosen satellites' passes followed in turn (see [fr.f4ioz.satcombo.domain.VeilleAuto]). */
    val veille: Boolean get() = settings.veilleActive
    /** Each pass recorded too (the sound card running). */
    val veilleSon: Boolean get() = settings.veilleSon

    /** The first version's choices (13/10, "pc_veille…"), taken into the settings shared with the phone. */
    private fun reprendVeille() {
        val ancien = stockage.getStringSet("pc_veille_sats", null) ?: return
        if (settings.veilleSats.isEmpty()) settings.veilleSats = ancien.mapNotNull { it.toIntOrNull() }.toSet()
        if (stockage.getBoolean("pc_veille", false)) settings.veilleActive = true
        stockage.getInt("pc_veille_el", -1).takeIf { it >= 0 }?.let { settings.veilleElMin = it }
        if (!stockage.getBoolean("pc_veille_son", true)) settings.veilleSon = false
        stockage.getStringSet("pc_veille_ecartes", null)?.let { settings.veilleEcartes = it }
        stockage.edit().remove("pc_veille").remove("pc_veille_sats").remove("pc_veille_el").remove("pc_veille_son").remove("pc_veille_ecartes").apply()
    }

    /** The chosen satellites' passes over the next 24 h, as the automatic mode takes them. */
    fun planVeille(maintenant: Long = horloge()): List<fr.f4ioz.satcombo.domain.VeilleAuto.Ligne> =
        fr.f4ioz.satcombo.domain.VeilleAuto.plan(_etat.value.passages, settings.veilleSats, settings.veilleElMin.toDouble(), settings.veilleEcartes, maintenant)

    /** The pass the automatic mode records now, when it is the satellite followed's; null otherwise. */
    fun passageVeilleAEnregistrer(maintenant: Long = horloge()): SatPass? {
        if (!veille || !veilleSon) return null
        val p = fr.f4ioz.satcombo.domain.VeilleAuto.aEnregistrer(planVeille(maintenant), maintenant) ?: return null
        return p.takeIf { _etat.value.suivi?.catalogNumber == it.catalogNumber }
    }

    /** The passes already followed by the automatic mode: each once, the operator free to change after. */
    private val veilleSuivis = HashSet<String>()

    private fun suitVeille(maintenant: Long) {
        if (!veille) return
        val p = fr.f4ioz.satcombo.domain.VeilleAuto.aSuivre(planVeille(maintenant), maintenant) ?: return
        val k = fr.f4ioz.satcombo.domain.VeilleAuto.cle(p)
        // The same pass, its AOS moved a little by new elements: not followed again.
        if (fr.f4ioz.satcombo.domain.VeilleAuto.ecarte(p, veilleSuivis)) return
        veilleSuivis += k
        if (_etat.value.suivi?.catalogNumber != p.catalogNumber) suis(p.catalogNumber)
    }

    // ---------------------------------------------------------------- skeds

    private val skedRepo = fr.f4ioz.satcombo.data.SkedRepository()

    /** hams.at's announced skeds asked again (its key when given), or forgotten when the option is off. */
    suspend fun rafraichisSkeds(force: Boolean = false) {
        if (!settings.skedsEnabled || !catalogue.enLigne) { change { copy(skeds = emptyList()) }; return }
        val l = skedRepo.upcoming(settings.skedsToken, force)
        change { copy(skeds = l) }
    }

    fun regleSkeds(on: Boolean?, cle: String?) {
        on?.let { settings.skedsEnabled = it }
        cle?.let { settings.skedsToken = it }
        portee.launch(Dispatchers.IO) { runCatching { rafraichisSkeds(force = true) } }
    }

    /** The skeds announced for a pass. */
    fun skedsDu(p: SatPass): List<fr.f4ioz.satcombo.data.SkedAlert> =
        fr.f4ioz.satcombo.domain.SkedsPassage.duPassage(_etat.value.skeds, p.catalogNumber, p.aosEpochMs, p.losEpochMs)

    /** The agenda read again (the phone's taken from its backup). */
    fun rechargeAgenda() { change { copy(agenda = AgendaStore.load(stockageAgenda)) } }

    /** Is this pass in the agenda? */
    fun auAgenda(p: SatPass): Boolean = _etat.value.agenda.any { it.enabled && satDe(it.satName)?.catalogNumber == p.catalogNumber && it.covers(p.aosEpochMs, p.losEpochMs, 0L) }

    // ------------------------------------------------------------ timeline

    /**
     * The elevation of the favourites (and of the satellite followed, and of
     * the agenda's) over [heures] hours from now, one value a minute — the
     * engine's [fr.f4ioz.satcombo.demo.ApiCalculs], shared with the phone.
     */
    suspend fun frise(heures: Int = 12, aussi: Set<Int> = emptySet()): Pair<Long, List<fr.f4ioz.satcombo.demo.ApiCalculs.LigneFrise>> = withContext(Dispatchers.Default) {
        val e = _etat.value
        val obs = e.observateur ?: return@withContext horloge() to emptyList()
        val debut = horloge() / 60_000L * 60_000L
        // [aussi]: the SSTV campaigns' satellites, their passes marked on the timeline.
        val sats = fr.f4ioz.satcombo.demo.ApiCalculs.satellitesFrise(e.satellites, e.favoris + aussi, e.suivi, e.agenda, debut)
        debut to fr.f4ioz.satcombo.demo.ApiCalculs.frise(predicteur, obs, sats, debut, heures)
    }

    /**
     * The followed satellite's ground track over about one orbit, from an
     * eighth of it ago (as the phone's map): (lat, lon) points; empty for a
     * satellite too high for it to mean anything.
     */
    fun traceAuSol(): List<Pair<Double, Double>> {
        val sat = _etat.value.suivi ?: return emptyList()
        return runCatching { predicteur.groundTrack(sat, horloge(), 160) }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- skeds

    /** The windows of the next 48 hours both stations see [catnum] (the engine's, shared with the phone); null: not a square. */
    suspend fun sked(catnum: Int, locator: String, minElAutre: Double): List<fr.f4ioz.satcombo.demo.ApiCalculs.FenetreSked>? = withContext(Dispatchers.Default) {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return@withContext emptyList()
        val a = e.observateur ?: return@withContext emptyList()
        fr.f4ioz.satcombo.demo.ApiCalculs.sked(predicteur, sat, a, locator, settings.minElevDeg.toDouble(), minElAutre, horloge())
    }

    /** The sked replayed (the engine's [fr.f4ioz.satcombo.domain.TraceSked]). */
    suspend fun traceSked(catnum: Int, locator: String, deMs: Long, aMs: Long): fr.f4ioz.satcombo.domain.TraceSked? = withContext(Dispatchers.Default) {
        val e = _etat.value
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return@withContext null
        val a = e.observateur ?: return@withContext null
        fr.f4ioz.satcombo.demo.ApiCalculs.traceSked(predicteur, sat, a, locator, deMs, aMs)
    }

    /** A sked into the agenda (its window), as an appointment of kind SKED. */
    fun skedAgenda(catnum: Int, locator: String, deMs: Long, aMs: Long) {
        val sat = _etat.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        fr.f4ioz.satcombo.demo.ApiCalculs.skedAgenda(stockageAgenda, sat, locator, deMs, aMs, horloge())
        change { copy(agenda = AgendaStore.load(stockageAgenda)) }
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
        val masque = settings.masqueHorizon
        val l = withContext(Dispatchers.Default) {
            sats.flatMap { s ->
                if (!PassPredictor.elementsUtilisables(s)) emptyList()
                else runCatching { predicteur.upcomingPasses(s, obs, debut - 15 * 60_000L, 24, settings.minElevDeg.toDouble(), masque) }
                    .getOrDefault(emptyList()).filter { it.losEpochMs >= debut }
            }.sortedBy { it.aosEpochMs }
        }
        change { copy(passages = l, calculEnCours = false) }
    }

    // ------------------------------------------------------- the Doppler/CAT log

    /** Where the Doppler/CAT logs go, one CSV per pass (set at start; null: none kept). */
    @Volatile var dossierDoppler: java.io.File? = null
    private var suiviDoppler: fr.f4ioz.satcombo.domain.JournalDoppler? = null

    /**
     * Each second: with the log on and the rig connected, the followed pass
     * noted — what the loop wrote and read, observed only.
     */
    private fun noteDoppler(t: Long) {
        val d = dossierDoppler ?: return
        val e = _etat.value
        val sat = e.suivi
        val actif = settings.journalDoppler && e.catConnecte && sat != null
        if (!actif && suiviDoppler?.enCours == null) return
        val j = suiviDoppler ?: fr.f4ioz.satcombo.domain.JournalDoppler(d).also { suiviDoppler = it }
        val p = e.position
        val ech = if (actif && p != null) {
            val tx = emetteurChoisi()
            val dl = e.repos ?: tx?.downlinkLowHz?.let { lo -> (lo + (tx.downlinkHighHz ?: lo)) / 2 }
            fr.f4ioz.satcombo.domain.JournalDoppler.Echantillon(t, p.azimuthDeg, p.elevationDeg, p.rangeKm, p.rangeRateKmS,
                dl, fr.f4ioz.satcombo.domain.JournalDoppler.reposMontee(tx, dl),
                stationCat.ecritDescenteHz.takeIf { it > 0 }, stationCat.ecritMonteeHz.takeIf { it > 0 },
                stationCat.luDescenteHz.takeIf { it > 0 })
        } else null
        j.tic(actif, sat?.name.orEmpty(), sat?.catalogNumber ?: 0, ech)
    }

    /** The log switched on or off (off: the pass under way closes at the next second). */
    fun journalDoppler(on: Boolean) { settings.journalDoppler = on }

    /** The logs kept, newest first. */
    fun journauxDoppler(): List<java.io.File> = dossierDoppler?.let { fr.f4ioz.satcombo.domain.JournalDoppler.liste(it) }.orEmpty()

    /** The horizon mask set on the page: the lists computed again with it. */
    fun poseMasque(m: fr.f4ioz.satcombo.domain.MasqueHorizon) {
        settings.masqueHorizon = m
        derniereSuite = 0L
        change { copy(passagesSuivi = emptyList()) }
        portee.launch { runCatching { recalcule() } }
    }

    // ------------------------------------------------------------- choices

    /**
     * [sat] followed; [emetteurVoulu] picks its transmitter among those known
     * (the list it may add to, and the one chosen) — the SSTV auto-pilot's
     * frequency of its own; null: the one chosen last for it.
     */
    fun suis(sat: TleEntry, reposVoulu: Long? = null, emetteurVoulu: ((List<Transmitter>) -> Pair<List<Transmitter>, Int>)? = null) {
        stockage.edit().putInt(CLE_SATELLITE, sat.catalogNumber).apply()
        stationCat.nouveauSatellite()
        // The SSTV tuning's share belonged to the old satellite.
        afcSstvHz = 0L
        stationCat.oublieArmement()
        derniereRecherche = 0L
        val c = satConfig.load(sat.catalogNumber)
        derniereSuite = 0L
        change { copy(suivi = sat, position = null, passage = null, arc = emptyList(), emetteurs = emptyList(), dernierPassage = null,
            emetteur = 0, repos = null, passagesSuivi = emptyList(),
            calibHz = c.calibShiftHz, txShiftHz = c.txShiftHz, refCalibHz = c.refCalibShiftHz, refTxHz = c.refTxShiftHz) }
        portee.launch(Dispatchers.IO) {
            val l = catalogue.emetteurs(sat.catalogNumber).filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
            // The transmitter chosen last time for this satellite, as on the phone.
            val i = c.txDescription?.let { d -> l.indexOfFirst { it.description == d } }?.takeIf { it >= 0 } ?: 0
            val (liste, choisi) = emetteurVoulu?.let { f -> runCatching { f(l) }.getOrNull() } ?: (l to i)
            change { if (suivi?.catalogNumber == sat.catalogNumber) copy(emetteurs = liste, emetteur = choisi.coerceIn(0, (liste.size - 1).coerceAtLeast(0)), repos = reposVoulu) else this }
        }
    }

    /**
     * [catnum]'s transmitter [description] followed (from the SDR scale), with
     * [repos] its rest frequency in a transponder (null: its middle). False
     * when the satellite or the transmitter is not known.
     */
    fun suisEmetteur(catnum: Int, description: String, repos: Long? = null): Boolean {
        val e = _etat.value
        if (e.suivi?.catalogNumber == catnum && e.emetteurs.isNotEmpty()) {
            val i = e.emetteurs.indexOfFirst { it.description == description }.takeIf { it >= 0 } ?: return false
            if (i != e.emetteur) choisitEmetteur(i)
            change { copy(repos = repos) }
            return true
        }
        val sat = e.satellites.firstOrNull { it.catalogNumber == catnum } ?: return false
        satConfig.saveTransmitter(catnum, description)
        suis(sat, reposVoulu = repos)
        return true
    }

    /**
     * [catnum]'s « correction manuelle » (RX calibration, Hz, kept for it as
     * the satellite panel keeps it): moved by [dHz] or set to [hz]. Returns it.
     */
    fun correctionSat(catnum: Int, dHz: Long? = null, hz: Long? = null): Long {
        val avant = satConfig.load(catnum).calibShiftHz
        val v = (hz ?: (avant + (dHz ?: 0L))).coerceIn(-CORRECTION_MAX_HZ, CORRECTION_MAX_HZ)
        if (v != avant) satConfig.saveShift(catnum, v)
        if (_etat.value.suivi?.catalogNumber == catnum) change { copy(calibHz = v) }
        return v
    }

    /**
     * The SDR scale: every downlink already known falling in [deHz]..[aHz]
     * (Doppler and correction applied) for the satellites of the catalogue;
     * [suivi]: what the receiver follows (catnum, transmitter), if anything.
     */
    fun echelleSdr(deHz: Long, aHz: Long, suivi: Pair<Int, String>?): List<fr.f4ioz.satcombo.domain.EchelleSdr.Marque> {
        val e = _etat.value
        val obs = e.observateur ?: return emptyList()
        val t = horloge()
        val connus = runCatching { catalogue.emetteursConnus(t) }.getOrDefault(emptyMap())
        val marge = fr.f4ioz.satcombo.domain.EchelleSdr.margeDoppler(aHz) + CORRECTION_MAX_HZ
        val l = ArrayList<Pair<fr.f4ioz.satcombo.domain.EchelleSdr.Sat, List<Transmitter>>>()
        for (sat in e.satellites) {
            val tx = connus[sat.catalogNumber]?.filter { x -> x.alive && x.downlinkLowHz?.let { lo -> (x.downlinkHighHz ?: lo) >= deHz - marge && lo <= aHz + marge } == true }
            if (tx.isNullOrEmpty()) continue
            val p = runCatching { predicteur.positionAt(sat, obs, t) }.getOrNull() ?: continue
            val p1 = runCatching { predicteur.positionAt(sat, obs, t + 1_000) }.getOrNull()
            val pas = e.passages.firstOrNull { it.catalogNumber == sat.catalogNumber && it.losEpochMs > t }
            l += fr.f4ioz.satcombo.domain.EchelleSdr.Sat(sat.catalogNumber, sat.name, p.elevationDeg, p.azimuthDeg, p.rangeRateKmS,
                p1?.rangeRateKmS ?: p.rangeRateKmS, pas?.geoAosEpochMs, pas?.geoLosEpochMs,
                satConfig.load(sat.catalogNumber).calibShiftHz, sat.catalogNumber in e.favoris) to tx
        }
        return fr.f4ioz.satcombo.domain.EchelleSdr.marques(l, deHz, aHz, t, suivi)
    }

    /** [catnum]'s transmitter chosen last (else its first): a contact moved onto it takes its frequencies. */
    fun emetteurDe(catnum: Int): Transmitter? {
        val l = runCatching { catalogue.emetteurs(catnum) }.getOrDefault(emptyList())
            .filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
        val d = satConfig.load(catnum).txDescription
        return l.firstOrNull { it.description == d } ?: l.firstOrNull()
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

    /** The exact point (degrees, from « Configuration rapide »): its 6-character locator, the passes computed from the point itself. Null when out of range. */
    fun posePosition(lat: Double, lon: Double): String? {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val l = Maidenhead.fromLatLon(lat, lon)
        settings.manualLocator = l
        settings.manualLat = lat; settings.manualLon = lon
        change { copy(locator = l, observateur = observateur(), passage = null) }
        portee.launch { recalcule() }
        return l
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

    /** The orbital elements' choice, as on the phone: its sources, the SatMe GP server, the server only. */
    val sourcesStore = fr.f4ioz.satcombo.data.SourcesStore(stockage)

    fun choixSources() = Catalogue.Choix(sourcesStore.load(), settings.serveurGp, settings.serveurGpSeul)

    /** A new choice of sources: kept, then the elements downloaded again with it. */
    fun regleSources(ids: Set<String>?, serveur: String?, seul: Boolean?) {
        ids?.let { l -> sourcesStore.save(l.filter { id -> fr.f4ioz.satcombo.data.Sources.ALL.any { it.id == id } }.toSet()) }
        serveur?.let { settings.serveurGp = it }
        seul?.let { settings.serveurGpSeul = it }
        catalogue.choix = choixSources()
        rafraichisCatalogue()
    }

    fun rafraichisCatalogue() {
        portee.launch(Dispatchers.IO) {
            change { copy(catalogue = "téléchargement des éléments…") }
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
        val e = _etat.value
        return fr.f4ioz.satcombo.demo.ApiStation.frequencesDuCiel(emetteurChoisi(), e.position?.rangeRateKmS ?: 0.0, e.repos, e.calibHz, e.txShiftHz)
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

    /** The adapter that answered as a TH-D72: named so from then on (it says only "CP2102" itself). */
    var cleThd72: String
        get() = stockage.getString("pc_thd72_cle", "") ?: ""
        set(v) { stockage.edit().putString("pc_thd72_cle", v).apply() }

    /** A port as the lists show it: its own name, and "Kenwood TH-D72" once it answered as one. */
    fun nomPort(p: fr.f4ioz.satcombo.plateforme.PortSerie): String {
        if (cleThd72.isNotBlank() && p.cle == cleThd72) return p.libelle + " — Kenwood TH-D72"
        // An Icom's own USB ports carry its name in their serial number ("IC-9700 13012155 A").
        val icom = p.serie?.let { Regex("^(IC-?\\d{3,4}[A-Z]?)[ _]\\S+(?:[ _]([AB]))?$").find(it.trim()) }
        return p.libelle + (icom?.let { " — Icom " + it.groupValues[1] + it.groupValues[2].let { s -> if (s.isNotEmpty()) " ($s)" else "" } } ?: "")
    }

    /** A port that is an Icom's own (its serial number says so: "IC-9700 13012155 A") or a RigExpert's: never a TH-D72's. */
    fun portDAutrePoste(p: fr.f4ioz.satcombo.plateforme.PortSerie): Boolean =
        p.serie?.trim()?.let { Regex("^IC-?\\d{3,4}").containsMatchIn(it) } == true ||
            (fr.f4ioz.satcombo.cat.CatScan.nomAutrePoste(p.produit) && p.produit?.contains("TH-D7", true) != true) ||
            p.produit?.contains("RigExpert", true) == true

    /** [catnum]'s transmitters (SatNOGS, kept on disk), and the one chosen last for it: a contact typed afterwards on it. */
    fun emetteursDe(catnum: Int): List<Transmitter> = runCatching { catalogue.emetteurs(catnum) }.getOrDefault(emptyList())
    fun emetteurChoisiPour(catnum: Int): String? = satConfig.load(catnum).txDescription

    /** The adapters the connected rig holds (none when not connected). */
    fun clesPoste(): Set<String> = if (_etat.value.catConnecte) clePoste.split(',').filter { it.isNotBlank() }.toSet() else emptySet()

    /** The connected rig's port paths ("/dev/ttyUSB0"), to find its sound card by USB place; none when simulated or off. */
    @Volatile private var cheminsCat: List<String> = emptyList()
    fun cheminsPoste(): List<String> = if (_etat.value.catConnecte && !_etat.value.catSimule) cheminsCat else emptyList()

    /** The CAT asked last: the TH-D72 panel reconnects with it when the transmit band changes. */
    @Volatile private var derniereChoixCat: ChoixCat? = null

    /** The TH-D72 panel (each band's frequency and power, the transmit band): the phone's, the engine's. */
    val thd72 = fr.f4ioz.satcombo.cat.Thd72Panneau(settings, portee, { paire.lienThd72 },
        { _etat.value.catModele == "THD72" && _etat.value.catConnecte }) {
        // Bands are sides of the pair: reconnect with the new roles.
        derniereChoixCat?.let { connecteCat(it.copy(bandeTx = settings.thd72BandeTx)) }
    }

    fun connecteCat(c: ChoixCat) {
        if (c.modele == "THD72") { derniereChoixCat = c; settings.thd72BandeTx = c.bandeTx.coerceIn(0, 1) }
        portee.launch {
            fil.withLock {
                deconnecteCatDedans()
                catParReseau = false
                val m = modeleDe(c.modele)
                if (m !in SIMULES) settings.rigModel = m
                val liste = ports.liste()
                val p = liste.getOrNull(c.port)
                val pTx = liste.getOrNull(c.portTx)
                fun echec(pourquoi: String) = change { copy(catModele = m, catConnecte = false, catSimule = false, catStatut = "échec : $pourquoi") }
                // The demonstration: the simulated rigs only, no port nor network opened.
                if (m !in SIMULES) ModeDemo.refus("un vrai poste")?.let { echec(it); return@withLock }
                // An Icom by its LAN server (Réglages › Poste › Liaison « Réseau »): no serial port opened at all.
                if (settings.icomLiaison == "RESEAU" && m in ICOMS) {
                    if (m != "IC9700") { echec("la liaison réseau sert l'IC-9700 seulement (Liaison : USB pour les autres)"); return@withLock }
                    val mi = ModeleIcom.IC9700
                    civ.modele = mi
                    val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        icomReseau.prends(fr.f4ioz.satcombo.cat.icomreseau.PosteIcomReseau.Usage.CAT) }
                    if (err != null) { echec(err); return@withLock }
                    civ.radioAddr = icomReseau.statut().adresseCiv.takeIf { it > 0 } ?: mi.adresse
                    civ.pacingMs = 20L
                    civ.attach(icomReseau.lienCiv())
                    catParReseau = true
                    clePoste = ""
                    cheminsCat = emptyList()
                    change { copy(catModele = m, catConnecte = true, catSimule = false,
                        catStatut = "${mi.libelle} par le réseau (${settings.icomHote})") }
                    lanceBoucleCat()
                    return@withLock
                }
                when (m) {
                    "SIM" -> {
                        civ.modele = ModeleIcom.IC9700
                        civ.radioAddr = ModeleIcom.IC9700.adresse
                        civ.pacingMs = 0L
                        civ.attach(Ic9700Sim().also { posteSimule = it })
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
                        // Opened with RTS/DTR up, as its cable wants: never on another rig's interface.
                        if (portDAutrePoste(p)) { echec("${nomPort(p)} est l'interface d'un autre poste : jamais ouvert pour un TH-D72"); return@withLock }
                        if (!l.open(p.cle, if (c.baud > 0) c.baud else 9_600) || !l.estUnThd72())
                            { l.close(); echec("pas de TH-D72 qui réponde sur ${p.libelle}"); return@withLock }
                        clePoste = p.cle
                        cleThd72 = p.cle
                        change { copy(catModele = m, catConnecte = true, catSimule = false,
                            catStatut = "Kenwood TH-D72 sur ${p.libelle}, émission sur la bande " + (if (c.bandeTx == 0) "A" else "B")) }
                        thd72.lire()
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
                cheminsCat = listOfNotNull(p?.chemin, pTx?.chemin).filter { it.isNotBlank() }
                if (_etat.value.catConnecte) lanceBoucleCat()
            }
        }
    }

    fun deconnecteCat() { portee.launch { fil.withLock { deconnecteCatDedans() } } }

    /** Called before the CAT closes (a disconnection, another connection): the PTT released while the rig still hears. */
    @Volatile var avantCoupureCat: (suspend () -> Unit)? = null

    private suspend fun deconnecteCatDedans() {
        if (_etat.value.catConnecte) avantCoupureCat?.let { f -> runCatching { kotlinx.coroutines.withTimeoutOrNull(1_500L) { f() } } }
        boucleCat?.cancel(); boucleCat = null
        civ.close()
        paire.close()
        stationCat.deconnecte()
        change { copy(catConnecte = false, catSimule = false, catStatut = "poste déconnecté") }
    }

    /** The Doppler loop, ten times a second, as on the phone; between two rounds the knob's direct tuning and the RF power. */
    private fun lanceBoucleCat() {
        boucleCat?.cancel()
        boucleCat = portee.launch {
            while (_etat.value.catConnecte) {
                runCatching { stationCat.tour() }
                runCatching { tourPoste() }
                runCatching { lisSmetre() }
                delay(100)
            }
        }
    }

    // ------------------------------------------------------- the rig's dial, its power, its PTT

    /** The simulated IC-9700 when that is the rig (the tests read it back). */
    @Volatile internal var posteSimule: Ic9700Sim? = null; private set

    /** The knob's steps on the rig's own dial (terrestrial SSB, nothing else driving it). */
    val accordDirect = fr.f4ioz.satcombo.station.AccordDirect()

    /**
     * Is the Doppler loop writing the rig now? The conditions of its round
     * ([StationCat.tour]): connected, a transmitter, the satellite up (or
     * « sous l'horizon »), not on hold (QO-100 holding the rig counts).
     */
    fun pilotageDoppler(): Boolean {
        val e = _etat.value
        if (!e.catConnecte || emetteurChoisi() == null || e.suivi == null) return false
        if ((e.position?.elevationDeg ?: -1.0) < 0.0 && !catSousHorizon) return false
        return !(e.maintienDoppler || qo100.etat.value.auPoste)
    }

    /** The knob on the rig's dial when the loop does not drive it: true when taken. */
    fun accordeDirect(dHz: Long): Boolean {
        if (!_etat.value.catConnecte || pilotageDoppler()) return false
        accordDirect.ajoute(dHz)
        return true
    }

    /** The RF power level to write (0..255) on the band that transmits, null: nothing to write. */
    @Volatile var puissanceAEcrire: Int? = null
    /** The level read last, and when (ms); null: not read. */
    @Volatile var puissanceLue: Int? = null; private set
    @Volatile var puissanceLueMs = 0L; private set
    /** Read again soon (the page opened its panel, a level written). */
    @Volatile var puissanceARelire = true

    /** The CAT connected through the rig's network server (not its USB cable): set at connection. */
    @Volatile var catParReseau = false; private set

    /** The IC-9700 on CI-V (USB, network or simulated): what the TX panel drives. */
    fun posteIc9700(): Boolean = _etat.value.let { it.catConnecte && it.catModele == "IC9700" }

    private suspend fun tourPoste() {
        val poste: PosteCat = if (_etat.value.catModele in PAIRES) postePaire else posteCiv
        if (accordDirect.attente != 0L) {
            if (pilotageDoppler()) accordDirect.applique({ null }, {})
            else accordDirect.applique({ poste.lisDescente(false) }, { poste.ecrisDescente(it) })
        }
        if (!posteIc9700()) return
        puissanceAEcrire?.let { v ->
            puissanceAEcrire = null
            if (civ.setRfPowerTx(v)) { puissanceLue = v; puissanceLueMs = horloge() }
        }
        if (puissanceARelire || horloge() - puissanceLueMs > 10_000L) {
            puissanceARelire = false
            civ.readRfPowerTx()?.let { puissanceLue = it }
            puissanceLueMs = horloge()
        }
    }

    /** Keys or unkeys the IC-9700 (CI-V 1C 00 01/00, behind the network link's guard); true when it took it. */
    suspend fun pttPoste(on: Boolean): Boolean = if (_etat.value.catModele in ICOMS && _etat.value.catConnecte) civ.setTransmit(on) else false

    /** The rig's TX state read back (CI-V 1C 00), null when it does not answer. */
    suspend fun etatTxPoste(): Boolean? = if (_etat.value.catModele in ICOMS && _etat.value.catConnecte) civ.isTransmitting() else null

    /** The S-meter, read once a second for the journal (0..241, the Icom scale); when it was read. */
    @Volatile private var smetre: Int? = null
    @Volatile private var smetreMs = 0L

    /**
     * As on the phone: an Icom alone (CI-V) or a TS-2000, not the simulated
     * rig nor a pair of two rigs, a satellite up, never while transmitting
     * (the meter shows power then). Read only.
     */
    private suspend fun lisSmetre() {
        val e = _etat.value
        if (!e.catConnecte || e.catSimule || (e.position?.elevationDeg ?: -1.0) < 0.0) return
        val maintenant = horloge()
        if (maintenant - smetreMs < 1_000L) return
        smetreMs = maintenant
        smetre = when (e.catModele) {
            "TS2000" -> paire.lienTs2000?.let { l -> if (l.emission() == true) null else l.smetre() }
            "IC9700", "IC9100", "IC910" -> if (civ.isTransmitting() == true) null else civ.readSMeter()
            else -> null
        }
    }

    /** The S-meter read less than 3 s ago, else null. */
    fun smetreRecent(): Int? = smetre.takeIf { _etat.value.catConnecte && horloge() - smetreMs <= 3_000L }

    /** The last S-meter reading and when it was made (a probe takes only those after its switch), null when none lately. */
    fun smetreLu(): Pair<Int, Long>? {
        val v = smetre ?: return null
        val ms = smetreMs
        return if (_etat.value.catConnecte && horloge() - ms <= 3_000L) v to ms else null
    }

    companion object {
        /** The rigs the PC drives: the Icom family on CI-V, the pairs (TS-2000, FT-817 / IC-705, TH-D72), and simulators. */
        val MODELES = listOf("IC9700", "IC9100", "IC910", "TS2000", "PAIRE", "THD72", "SIM", "TS2000SIM")
        /** Driven as a pair: each leg its own rig or VFO. */
        val PAIRES = setOf("TS2000", "PAIRE", "THD72")
        val SIMULES = setOf("SIM", "TS2000SIM")
        /** The Icom family on CI-V. */
        val ICOMS = setOf("IC9700", "IC9100", "IC910")
        /** The satellite followed, kept between two starts. */
        const val CLE_SATELLITE = "pc_satellite"
        const val CLE_FAVORIS = "pc_favoris"
        /** The scale's « correction manuelle » stays within this (Hz). */
        const val CORRECTION_MAX_HZ = 50_000L
        const val CLE_AGENDA_SUIVI = "pc_agenda_suivi"
        /** « Émission par le réseau autorisée »: this PC's alone, never taken from a phone's backup. */
        const val CLE_TX_RESEAU = "pc_tx_poste_reseau"
        fun modeleDe(id: String): String = id.takeIf { it in MODELES } ?: "IC9700"
    }
}
