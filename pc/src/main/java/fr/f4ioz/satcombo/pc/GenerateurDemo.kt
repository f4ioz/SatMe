/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.ActivitesAVenir
import fr.f4ioz.satcombo.domain.CampagneSstv
import fr.f4ioz.satcombo.domain.JournalFt
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The demonstration's data, made up but believable, for `satme --demo`:
 * the station's settings, its favourite satellites, a log of contacts, the
 * journal of its last passes (most followed with the virtual rig and mast,
 * some rebuilt), an SSTV campaign with its pictures, the station's chain.
 *
 * **Nothing is anybody's.** Every callsign is in the Q series, which the ITU
 * keeps for the Q code and never gives to a country: none can be on the air.
 * The pictures are drawn here ([ImagesDemo]); the names are first names only.
 *
 * **Relative to [maintenant]**: the passes are the real ones of the days
 * before it (the elements carried in the program, [ELEMENTS]), so the
 * journal and the log always look recent. Same [maintenant] and [graine],
 * same files, byte for byte (the settings' date line aside).
 */
class GenerateurDemo(private val maintenant: Long, private val graine: Long = GRAINE) {

    /** A made-up correspondent: its callsign (Q series), its square, a first name. */
    data class Correspondant(val indicatif: String, val locator: String, val prenom: String)

    enum class Genre { FM, LINEAIRE, GEO }

    /** A satellite the station works: its sort, its frequencies (MHz), its transponder, the passes kept in the journal. */
    data class SatDemo(val catnum: Int, val genre: Genre, val descenteMhz: Double, val monteeMhz: Double,
                       val transpondeur: String, val passagesJournal: Int)

    /** What was written. */
    data class Bilan(val passages: Int, val contacts: Int, val images: Int, val satellites: Int)

    private val rnd = java.util.Random(graine)
    private val predicteur = PassPredictor()
    private val observateur: Observer = Maidenhead.centre(LOCATOR)!!.let { (la, lo) -> Observer(la, lo, 320.0) }
    private val zone = ZoneId.of("Europe/Paris")

    /**
     * Writes the demonstration into [dossier]: with [reglages], the settings,
     * the elements and the station's chain too; always the dated part (the log,
     * the journal, the pictures, the campaign), what was there replaced.
     */
    fun genere(dossier: File, reglages: Boolean = true): Bilan {
        dossier.mkdirs()
        val cache = File(dossier, "cache")
        amorceElements(cache)
        emetteurs(cache)
        if (reglages) {
            ecritReglages(dossier)
            File(dossier, "montage.json").writeText(montage().toString(), Charsets.UTF_8)
        }
        val sats = Catalogue(cache, enLigne = false).satellites().associateBy { it.catalogNumber }

        // The dated part made again from nothing.
        File(dossier, "journal").deleteRecursively()
        File(dossier, "sstv").deleteRecursively()
        File(dossier, "qso_log.json").delete()
        val journal = JournalPassage.Rangement(File(dossier, "journal"))
        val dossierSstv = File(dossier, "sstv").apply { mkdirs() }
        val contacts = ArrayList<LogEntry>()
        var nbPassages = 0
        val images = ArrayList<Pair<SatPass, List<String>>>()

        for (s in SATELLITES.filter { it.genre != Genre.GEO }) {
            val tle = sats[s.catnum] ?: continue
            // The journal's passes: the last days, at hours a station is up, high enough to be worked.
            val recents = passages(tle, JOURS_JOURNAL, 15.0).filter { heureVeille(it.aosEpochMs) }
            // The last one always (the journal ends today), the ISS's every pass of the SSTV campaign.
            val choisis = if (s.catnum == ISS) recents.partition { it.aosEpochMs >= debutCampagne() }.let { (c, avant) -> choisit(avant, s.passagesJournal - 3) + c }
                else (choisit(recents.dropLast(1), s.passagesJournal - 1) + listOfNotNull(recents.lastOrNull()))
            for (p in choisis) {
                val suivi = s.catnum == ISS || rnd.nextInt(4) != 0
                val piste = piste(tle, p, s, suivi)
                if (piste.size < 3) continue
                val qsos = contactsDuPassage(tle, s, p, piste)
                contacts += qsos
                val imagesIss = if (s.catnum == ISS && p.aosEpochMs >= debutCampagne()) imagesSstv(dossierSstv, p, piste) else emptyList()
                if (imagesIss.isNotEmpty()) images += p to imagesIss
                val e = entree(tle, s, p, piste, suivi)
                journal.enregistre(e)
                // The last pass of the linear transponder heard in FT4 too: its messages kept beside it.
                if (s.catnum == FT4_SAT && suivi && p == choisis.last()) runCatching {
                    File(journal.dossier, e.id + ".ft").writeText(JournalFt.ecritTout(ft4(e, piste, qsos)), Charsets.UTF_8)
                }
                nbPassages++
            }
            // Older contacts: the log goes back further than the journal.
            val anciens = passages(tle, JOURS_CARNET, 20.0)
                .filter { it.aosEpochMs < maintenant - JOURS_JOURNAL * JOUR_MS && heureVeille(it.aosEpochMs) }
            for (p in choisit(anciens, (s.passagesJournal * 2).coerceAtMost(8))) {
                val piste = piste(tle, p, s, false)
                if (piste.size >= 3) contacts += contactsDuPassage(tle, s, p, piste)
            }
        }
        sats[QO100]?.let { contacts += contactsQo100(it) }

        // Uploaded to the online log a couple of hours after, but for the last two days'.
        LogStore(File(dossier, "qso_log.json")).save(contacts.sortedBy { it.timeMs }
            .map { if (it.timeMs < maintenant - 2 * JOUR_MS) it.copy(envoyeMs = it.timeMs + 2 * 3_600_000L) else it })
        // What the (virtual) online log says of their confirmations: most uploaded to LoTW and
        // eQSL, a good part confirmed, a few paper cards. Fictitious, as everything here.
        File(dossier, "qsl_statuts.json").delete()
        fr.f4ioz.satcombo.data.StatutsQsl.Store(File(dossier, "qsl_statuts.json")).fusionne(
            contacts.filter { it.timeMs < maintenant - 2 * JOUR_MS && it.callsign.isNotBlank() }.mapIndexed { i, e ->
                fr.f4ioz.satcombo.data.StatutsQsl.Statut(e.callsign, e.timeMs, fr.f4ioz.satcombo.domain.NomSatellite.propre(e.satName),
                    lotwRecu = if (i % 3 != 0) "Y" else "N", lotwEnvoye = "Y",
                    eqslRecu = if (i % 2 == 0) "Y" else "N", eqslEnvoye = if (i % 5 != 4) "Y" else "N",
                    papierRecu = if (i % 11 == 0) "Y" else "N", papierEnvoye = if (i % 11 == 0 || i % 13 == 0) "Y" else "N")
            }, emptyMap(), true, maintenant)
        File(dossier, "sstv-campagnes.json").writeText(campagnes(images).toString(), Charsets.UTF_8)
        runCatching { imagesSsdv(dossier) }
        File(dossier, ModeDemo.MARQUE).writeText("genere=$maintenant\ngraine=$graine\nversion=$VERSION\n", Charsets.UTF_8)
        return Bilan(nbPassages, contacts.size, images.sumOf { it.second.size }, sats.size)
    }

    // ---------------------------------------------------------------- the station

    /** The elements carried in the program, put where the station reads them; dated as they are, so a network refreshes them. */
    private fun amorceElements(cache: File) {
        val texte = GenerateurDemo::class.java.getResourceAsStream(ELEMENTS)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val sources = File(cache, "sources").apply { mkdirs() }
        val f = File(sources, "amsat_gp.txt")
        f.writeText(texte, Charsets.UTF_8)
        val fait = File(sources, "fait")
        fait.writeText("amsat_gp," + fr.f4ioz.satcombo.data.ServeurGp.DEFAUT + ",false", Charsets.UTF_8)
        f.setLastModified(ELEMENTS_MS); fait.setLastModified(ELEMENTS_MS)
    }

    /** The favourites' transmitters, as SatNOGS DB gives them (replaced by its own when the network is there). */
    private fun emetteurs(cache: File) {
        val d = File(cache, "emetteurs").apply { mkdirs() }
        fun hz(mhz: Double) = (mhz * 1e6).roundToLong()
        fun e(desc: String, type: String, mode: String, ulBas: Double?, ulHaut: Double?, dlBas: Double?, dlHaut: Double?, invert: Boolean = false, baud: Int = 0) =
            JSONObject().put("description", desc).put("type", type).put("mode", mode).put("alive", true).put("status", "active")
                .put("uplink_low", ulBas?.let(::hz) ?: JSONObject.NULL).put("uplink_high", ulHaut?.let(::hz) ?: JSONObject.NULL)
                .put("downlink_low", dlBas?.let(::hz) ?: JSONObject.NULL).put("downlink_high", dlHaut?.let(::hz) ?: JSONObject.NULL)
                .put("invert", invert).put("baud", if (baud > 0) baud else JSONObject.NULL)
        val l = mapOf(
            ISS to listOf(e("Mode V/U FM - Voice Repeater", "Transceiver", "FM", 145.990, null, 437.800, null),
                e("Mode V APRS", "Transceiver", "AFSK", 145.825, null, 145.825, null, baud = 1200),
                e("Mode V SSTV", "Transmitter", "FM", null, null, 145.800, null)),
            27607 to listOf(e("Mode V/U FM", "Transceiver", "FM", 145.850, null, 436.795, null)),
            43017 to listOf(e("Mode U/V FM", "Transceiver", "FM", 435.250, null, 145.960, null)),
            44909 to listOf(e("Mode V/U Linear Transponder", "Transponder", "USB", 145.935, 145.995, 435.610, 435.670, invert = true),
                e("CW Beacon", "Transmitter", "CW", null, null, 435.605, null)),
            24278 to listOf(e("Mode V/U Linear Transponder", "Transponder", "USB", 145.900, 146.000, 435.800, 435.900, invert = true)),
            7530 to listOf(e("Mode B Linear Transponder", "Transponder", "USB", 432.125, 432.175, 145.925, 145.975, invert = true),
                e("Mode B CW Beacon", "Transmitter", "CW", null, null, 145.970, null)),
            QO100 to listOf(e("NB Transponder", "Transponder", "USB", 2400.050, 2400.300, 10489.550, 10489.800)),
        )
        for ((n, t) in l) File(d, "$n.json").writeText(JSONArray(t).toString(), Charsets.UTF_8)
    }

    /** The settings of a station that has been running a while: no first-start assistant, the virtual hardware chosen. */
    private fun ecritReglages(dossier: File) {
        val f = File(dossier, "reglages.properties")
        f.delete()
        val st = StockageFichier(f)
        val s = SettingsStore(st)
        s.callsign = INDICATIF
        s.manualLocator = LOCATOR
        s.locationMode = fr.f4ioz.satcombo.data.LocationMode.MANUAL
        s.language = "fr"
        s.rotorSim = true
        s.rotorEnabled = true
        s.amsatAuto = false
        s.carnetAuto = false
        s.plancheNom = "Station de démonstration"
        st.edit()
            .putStringSet(StationPc.CLE_FAVORIS, FAVORIS.map { it.toString() }.toSet())
            .putInt(StationPc.CLE_SATELLITE, ISS)
            .putString(CLE_CONFIG_RAPIDE, "fait")
            .putString(CLE_VERSION_VUE, versionPc())
            .putBoolean(MiseAJourPc.CLE_ACTIF, false)
            .putString(SdrPc.CLE_RECEPTEUR, SdrPc.GENERATEUR)
            // The generator centred where the scale marks several satellites (ISS SSTV and APRS, the FM and linear ones on 2 m).
            .putLong(SdrPc.CLE_MANUELLE, SDR_CENTRE_HZ)
            .putString("pc_son_carte", SonDemo.NOM)
            // The functions not about satellites shown: the trial aircraft and ships run at start.
            .putStringSet(fr.f4ioz.satcombo.demo.FonctionsNonSat.CLE, fr.f4ioz.satcombo.demo.FonctionsNonSat.ONGLETS.keys.toSet())
            .putStringSet(fr.f4ioz.satcombo.demo.ModulesStation.CLE, setOf("noeud"))
            // Where the virtual polarisation switch (ESP32) will plug in: its port, « virtuel ».
            .putString(ModeDemo.CLE_POLARISATION, ModeDemo.POLARISATION_VIRTUELLE)
            .putString(ModeDemo.CLE_POLARISATION_UHF, "WIMO")
            .putString(ModeDemo.CLE_POLARISATION_VHF, "AUCUN")
            .apply()
    }

    private fun montage(): JSONObject = JSONObject().put("v", 2).put("id", "p1").put("nom", "Station de démonstration (fictive)")
        .put("elements", JSONArray(listOf("yagi-70cm" to "Yagi 2 m / 70 cm", "rotor-gs232" to "Rotor (simulé)", "ic9700" to "IC-9700 (simulé)",
            "rtl-sdr" to "Générateur SDR", "pc" to "PC de démonstration").mapIndexed { i, (m, nom) ->
            JSONObject().put("id", "e" + (i + 1)).put("m", m).put("nom", nom) }))
        .put("liens", JSONArray()).put("plans", JSONArray()).put("affiche", "p1").put("suivre", false)

    // ---------------------------------------------------------------- the passes

    private fun passages(tle: TleEntry, jours: Int, elMin: Double): List<SatPass> =
        runCatching { predicteur.upcomingPasses(tle, observateur, maintenant - jours * JOUR_MS, jours * 24, elMin) }.getOrDefault(emptyList())
            .filter { it.losEpochMs < maintenant - 10 * 60_000L }

    private fun heureVeille(t: Long): Boolean = Instant.ofEpochMilli(t).atZone(zone).hour in 6..22

    /** [n] of [l], spread over the period, in time order. */
    private fun choisit(l: List<SatPass>, n: Int): List<SatPass> {
        if (l.size <= n) return l
        val pas = l.size.toDouble() / n
        return (0 until n).map { i -> l[((i + rnd.nextDouble() * 0.8) * pas).toInt().coerceIn(0, l.size - 1)] }.distinct().sortedBy { it.aosEpochMs }
    }

    /** A point of a pass: where the satellite was, how fast it moved away (km/s). */
    private class Pt(val t: Long, val az: Double, val el: Double, val vitesse: Double, val lat: Double, val lon: Double, val altKm: Double)

    private fun piste(tle: TleEntry, p: SatPass, s: SatDemo, fine: Boolean): List<Pt> {
        val pas = if (fine) JournalPassage.PAS_MS else 20_000L
        val l = ArrayList<Pt>()
        var t = p.aosEpochMs
        while (t <= p.losEpochMs) {
            val q = runCatching { predicteur.positionAt(tle, observateur, t) }.getOrNull()
            if (q != null && q.elevationDeg >= 0) l += Pt(t, q.azimuthDeg, q.elevationDeg, q.rangeRateKmS, q.latDeg, q.lonDeg, q.altKm)
            t += pas
        }
        return l
    }

    /** A pass in the journal: followed with the virtual rig and mast (Doppler, S-meter, the mast's lag), or rebuilt. */
    private fun entree(tle: TleEntry, s: SatDemo, p: SatPass, piste: List<Pt>, suivi: Boolean): JournalPassage.Entree {
        if (!suivi) return JournalPassage.Entree(tle.catalogNumber, tle.name, piste.first().t, piste.last().t, LOCATOR,
            reconstitue = true, points = piste.map { JournalPassage.Point(it.t, arrondi(it.az), arrondi(it.el)) })
        val c = 299_792.458
        var mat = piste.first().az to 0.0
        val points = piste.map { q ->
            // The mast follows by steps of a few degrees, a little behind.
            if (abs(ecart(q.az, mat.first)) > 4.0 || abs(q.el - mat.second) > 4.0) mat = Math.round(q.az).toDouble() % 360.0 to Math.round(q.el).toDouble()
            JournalPassage.Point(q.t, arrondi(q.az), arrondi(q.el),
                dlHz = (s.descenteMhz * 1e6 * (1 - q.vitesse / c)).roundToLong(),
                ulHz = (s.monteeMhz * 1e6 * (1 + q.vitesse / c)).roundToLong(),
                rotorAz = mat.first, rotorEl = mat.second)
        }
        // The S-meter: the noise floor, the satellite rising out of it with the elevation, a little fading.
        val signal = piste.map { q ->
            val v = 18 + q.el * 1.6 + rnd.nextGaussian() * 6 - (if (q.el < 6) 15 else 0)
            JournalPassage.Signal(q.t, v.roundToInt().coerceIn(0, 200))
        }
        return JournalPassage.Entree(tle.catalogNumber, tle.name, piste.first().t, piste.last().t, LOCATOR,
            transpondeur = s.transpondeur, points = points, signal = signal,
            auto = if (s.catnum == ISS) "" else JournalPassage.AUTO_CAT)
    }

    private fun arrondi(v: Double) = Math.round(v * 100) / 100.0
    private fun ecart(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0

    // ---------------------------------------------------------------- FT4 heard on a pass

    /**
     * A pass's FT4 messages, made up: each 7.5 s slot above 8°, a few of the stations in the footprint calling CQ
     * or answering each other, one of them calling the station (QF4DMO) through to its RR73, the pass's contacts
     * calling CQ too. Its own random series, so the rest of the demonstration stays as it was.
     */
    private fun ft4(e: JournalPassage.Entree, piste: List<Pt>, qsos: List<LogEntry>): List<JournalFt.Decode> {
        val r = java.util.Random(graine xor 0xF74L)
        val out = ArrayList<JournalFt.Decode>()
        val hauts = piste.filter { it.el >= 8.0 }
        if (hauts.isEmpty()) return out
        val duree = JournalFt.dureeTranche("FT4")
        val milieu = hauts[hauts.size / 2].t
        val qui = CORRESPONDANTS.firstOrNull { c -> qsos.none { it.callsign == c.indicatif } && Maidenhead.centre(c.locator) != null }
        var tranche = hauts.first().t / duree * duree + duree
        while (tranche + duree <= hauts.last().t) {
            val q = hauts.minByOrNull { kotlin.math.abs(it.t - tranche) }!!
            val vus = visibles(q, CORRESPONDANTS)
            fun db() = (-21 + (q.el / 90.0 * 18).roundToInt() + r.nextInt(9)).coerceIn(-24, 12)
            fun d(msg: String) = JournalFt.analyse(msg).let { (de, a, loc) ->
                JournalFt.Decode(tranche, "FT4", db(), Math.round((r.nextDouble() * 1.4 - 0.3) * 10) / 10.0, 300 + r.nextInt(2400), msg, de, a, loc, catnum = e.catnum) }
            val n = if (vus.isEmpty()) 0 else 1 + r.nextInt(minOf(3, vus.size))
            for (c in vus.shuffled(r).take(n)) {
                val autre = vus.filter { it != c }.let { if (it.isEmpty()) null else it[r.nextInt(it.size)] }
                out += d(when {
                    autre == null || r.nextInt(3) == 0 -> "CQ " + c.indicatif + " " + c.locator.take(4)
                    r.nextBoolean() -> autre.indicatif + " " + c.indicatif + " " + c.locator.take(4)
                    else -> autre.indicatif + " " + c.indicatif + " " + listOf("-12", "R-09", "RR73", "73", "-05", "R+02")[r.nextInt(6)]
                })
            }
            // Someone calling the station, around the middle of the pass, and the pass's contacts calling CQ before.
            if (qui != null) when (Math.floorDiv(tranche - milieu, duree)) {
                0L -> out += d("$INDICATIF ${qui.indicatif} ${qui.locator.take(4)}")
                2L -> out += d("$INDICATIF ${qui.indicatif} R-11")
                4L -> out += d("$INDICATIF ${qui.indicatif} RR73")
            }
            qsos.firstOrNull { kotlin.math.abs(it.timeMs - 60_000L - tranche) < duree }?.let { c ->
                out += d("CQ " + c.callsign + " " + c.theirLocator.take(4))
            }
            tranche += duree
        }
        return out
    }

    // ---------------------------------------------------------------- the log

    /** Who could be heard at [q]: inside the satellite's footprint, well inside. */
    private fun visibles(q: Pt, l: List<Correspondant>): List<Correspondant> {
        val r = 6371.0
        val portee = r * acos(r / (r + q.altKm)) * 0.9
        return l.filter { c -> Maidenhead.centre(c.locator)?.let { (la, lo) -> distanceKm(la, lo, q.lat, q.lon) < portee } == true }
    }

    private fun contactsDuPassage(tle: TleEntry, s: SatDemo, p: SatPass, piste: List<Pt>): List<LogEntry> {
        val hauts = piste.filter { it.el >= 12.0 }
        if (hauts.isEmpty()) return emptyList()
        val n = when {
            p.maxElevationDeg >= 50 -> 3 + rnd.nextInt(4)
            p.maxElevationDeg >= 30 -> 2 + rnd.nextInt(3)
            else -> 1 + rnd.nextInt(2)
        }
        val out = ArrayList<LogEntry>()
        val pris = HashSet<String>()
        var dernier = 0L
        for (i in 0 until n) {
            val q = hauts[((i + 0.2 + rnd.nextDouble() * 0.6) * hauts.size / n).toInt().coerceIn(0, hauts.size - 1)]
            if (q.t - dernier < 45_000L) continue
            val cand = visibles(q, CORRESPONDANTS).filter { it.indicatif !in pris }
            if (cand.isEmpty()) continue
            val c = cand[rnd.nextInt(cand.size)]
            pris += c.indicatif; dernier = q.t
            out += contact(tle.name, tle.catalogNumber, s, q.t + rnd.nextInt(20_000), q.az, q.el, c)
        }
        return out
    }

    private fun contact(sat: String, catnum: Int, s: SatDemo, t: Long, az: Double, el: Double, c: Correspondant): LogEntry {
        val cw = s.genre != Genre.FM && rnd.nextInt(5) == 0
        val mode = when { s.genre == Genre.FM -> "FM"; cw -> "CW"; else -> "SSB" }
        val rst = if (cw) listOf("599", "579", "559", "589") else listOf("59", "57", "55", "58", "53")
        // Inside a passband: somewhere in it, as the operator tuned.
        val decale = if (s.genre == Genre.FM) 0.0 else (rnd.nextInt(40) - 20) / 1000.0
        return LogEntry(timeMs = t, satName = sat, catnum = catnum, azimuthDeg = arrondi(az), elevationDeg = arrondi(el),
            myLocator = LOCATOR, callsign = c.indicatif,
            theirLocator = if (rnd.nextInt(3) == 0) c.locator.take(4) else c.locator,
            nom = if (rnd.nextInt(3) != 0) c.prenom else "", mode = mode,
            rstSent = rst[rnd.nextInt(rst.size)], rstRcvd = rst[rnd.nextInt(rst.size)],
            downlinkMhz = s.descenteMhz + (if (s.genre == Genre.LINEAIRE) -decale else decale),
            uplinkMhz = s.monteeMhz + decale, locatorOrigine = "SAISI")
    }

    /** QO-100: no pass, a few contacts in the evenings of the last days. */
    private fun contactsQo100(tle: TleEntry): List<LogEntry> {
        val s = SATELLITES.first { it.catnum == QO100 }
        val pos = runCatching { predicteur.positionAt(tle, observateur, maintenant) }.getOrNull() ?: return emptyList()
        val europe = CORRESPONDANTS.filter { c -> Maidenhead.centre(c.locator)?.let { (la, lo) -> lo > -30 && lo < 60 && la > 20 } == true }
        return (0 until 9).map { i ->
            val jour = 1 + i * JOURS_JOURNAL / 9
            val t = Instant.ofEpochMilli(maintenant - jour * JOUR_MS).atZone(zone).withHour(18 + rnd.nextInt(4)).withMinute(rnd.nextInt(60))
                .withSecond(rnd.nextInt(60)).toInstant().toEpochMilli()
            contact(tle.name, tle.catalogNumber, s, t, pos.azimuthDeg, pos.elevationDeg, europe[rnd.nextInt(europe.size)])
        }
    }

    // ---------------------------------------------------------------- SSDV

    /**
     * Two made-up SSDV pictures put together over two passes (the packets
     * written as the station keeps them): one finished, in the gallery, one
     * still missing a few packets, « en cours ».
     */
    private fun imagesSsdv(dossier: File) {
        val d = File(dossier, "telemesure/ssdv")
        d.deleteRecursively()
        val a = fr.f4ioz.satcombo.telemesure.AssemblageSsdv(dossier) { maintenant }
        fun paquets(diapo: Int, numero: Int): List<fr.f4ioz.satcombo.telemesure.Ssdv.Paquet> {
            val im = ImagesDemo.image(diapo, DIAPOS, 320, 240, graine + numero)
            val rgb = im.getRGB(0, 0, 320, 240, null, 0, 320).map { it and 0xFFFFFF }.toIntArray()
            return fr.f4ioz.satcombo.telemesure.Ssdv.codeImage(rgb, 320, 240, SSDV_INDICATIF, numero, qualite = 4)
                .mapNotNull { fr.f4ioz.satcombo.telemesure.Ssdv.lis(it) }
        }
        val orbite = 96 * 60_000L
        for ((k, numero) in listOf(41, 42).withIndex()) {
            val l = paquets(3 + 4 * k, numero)
            val debut = maintenant - (if (k == 0) 28 else 6) * 3_600_000L
            // First pass: from the start, some lost on the way, the signal gone before the end.
            val premier = l.indices.filter { it < l.size * (if (k == 0) 70 else 55) / 100 && rnd.nextInt(7) != 0 }
            premier.forEachIndexed { j, i -> a.ajoute(l[i], debut + j * 3_000L, SSDV_SAT) }
            // The next orbit: the picture sent again, the rest caught (the second one still a few short).
            val second = l.indices.filter { it !in premier && (k == 0 || rnd.nextInt(5) != 0) }
            second.forEachIndexed { j, i -> a.ajoute(l[i], debut + orbite + j * 3_000L, SSDV_SAT) }
        }
    }

    // ---------------------------------------------------------------- SSTV

    private fun debutCampagne() = maintenant - 3 * JOUR_MS

    /** Two or three pictures of the made-up series on an ISS pass, at its height, as a 2-minute-off cycle sends them. */
    private fun imagesSstv(d: File, p: SatPass, piste: List<Pt>): List<String> {
        val hauts = piste.filter { it.el >= 8.0 }
        if (hauts.size < 2) return emptyList()
        val mode = SstvMode.byName("PD 120")!!
        val duree = (mode.frameSeconds * 1000).toLong()
        val out = ArrayList<String>()
        var t = hauts.first().t + duree + rnd.nextInt(30_000)
        while (t <= hauts.last().t + 20_000L && out.size < 3) {
            val diapo = 1 + Math.floorMod((t / (duree + 120_000L)).toInt(), DIAPOS)
            val partiel = out.isNotEmpty() && rnd.nextInt(6) == 0
            val im = ImagesDemo.image(diapo, DIAPOS, mode.width, mode.height, graine + t, if (partiel) 0.55 + rnd.nextDouble() * 0.3 else 1.0)
            var nom = SstvMeta.fileName(ISS_NOM, t, mode.name, !partiel)
            var v = 0
            while (File(d, nom).exists()) nom = SstvMeta.fileName(ISS_NOM, t, mode.name, !partiel, variante = ++v)
            javax.imageio.ImageIO.write(im, "png", File(d, nom))
            val shot = SstvMeta.SstvShot(fileName = nom, satName = ISS_NOM, timeMs = t, mode = mode.name, complete = !partiel,
                locator = LOCATOR, callsign = INDICATIF, source = "live", note = "Campagne : $CAMPAGNE", entree = SstvPc.CARTE,
                diapo = diapo, diapos = DIAPOS)
            File(d, SstvMeta.sidecarName(nom)).writeText(SstvMeta.encode(shot), Charsets.UTF_8)
            out += nom
            t += duree + 120_000L
        }
        return out
    }

    /** The made-up campaign (three days back, two ahead) and what became of its passes. */
    private fun campagnes(images: List<Pair<SatPass, List<String>>>): JSONObject {
        val c = CampagneSstv.Campagne(id = "demo-sstv", nom = CAMPAGNE, catnum = ISS, satNom = ISS_NOM,
            emetteur = "Mode V SSTV", modeSstv = "PD 120", debutMs = debutCampagne(), finMs = maintenant + 2 * JOUR_MS,
            elMin = 10.0, horaire = "2 min on / 2 min off", notes = "Série fictive de la démonstration : 12 images faites maison.",
            creeMs = maintenant - 5 * JOUR_MS)
        val h = images.map { (p, l) ->
            CampagneSstv.Trace(c.id, c.nom, ISS, ISS_NOM, p.aosEpochMs, p.losEpochMs, arrondi(p.maxElevationDeg),
                CampagneSstv.Statut.FAIT, quandMs = p.losEpochMs + 60_000L, images = l,
                faits = listOf("poste : Doppler suivi (simulé)", "rotor : connexion simulée, suit le satellite", "SSTV : ${l.size} image(s)"))
        }
        return CampagneSstv.ecrit(CampagneSstv.Fichier(maitre = false, campagnes = listOf(c), historique = h))
    }

    companion object {
        /** The demonstration's own callsign and square (Clermont-Ferrand): made up. */
        const val INDICATIF = "QF4DMO"
        const val LOCATOR = "JN15MS"
        const val GRAINE = 20_261_031L
        const val VERSION = 1
        const val JOUR_MS = 86_400_000L
        const val JOURS_JOURNAL = 10
        const val JOURS_CARNET = 40
        const val ISS = 25544
        const val ISS_NOM = "ISS"
        const val QO100 = 43700
        /** The satellite whose last pass was heard in FT4 too (its linear transponder). */
        const val FT4_SAT = 44909
        const val DIAPOS = 12
        const val CAMPAGNE = "SSTV de démonstration"
        /** The made-up SSDV pictures' callsign and satellite. */
        const val SSDV_INDICATIF = "DEMSAT"
        const val SSDV_SAT = "DEMOSAT-1 (fictif)"
        /** The orbital elements carried for the demonstration (AMSAT's bulletin of 25 October 2026) and their date. */
        const val ELEMENTS = "/fr/f4ioz/satcombo/pc/demo/elements.json"
        const val ELEMENTS_MS = 1_792_886_400_000L

        val SATELLITES = listOf(
            SatDemo(ISS, Genre.FM, 437.800, 145.990, "Mode V/U FM - Voice Repeater", 6),
            SatDemo(27607, Genre.FM, 436.795, 145.850, "Mode V/U FM", 3),
            SatDemo(43017, Genre.FM, 145.960, 435.250, "Mode U/V FM", 3),
            SatDemo(44909, Genre.LINEAIRE, 435.640, 145.965, "Mode V/U Linear Transponder", 4),
            SatDemo(24278, Genre.LINEAIRE, 435.850, 145.950, "Mode V/U Linear Transponder", 2),
            SatDemo(7530, Genre.LINEAIRE, 145.950, 432.150, "Mode B Linear Transponder", 2),
            SatDemo(QO100, Genre.GEO, 10489.750, 2400.250, "NB Transponder", 0),
        )

        /** The demonstration's SDR (its test generator) centred here, sky Hz. */
        const val SDR_CENTRE_HZ = 145_885_000L

        /** The favourites: those worked, and two more FM satellites. */
        val FAVORIS = SATELLITES.map { it.catnum } + listOf(61781, 43678)

        /** Made-up correspondents across Europe and beyond: Q series callsigns, real squares, first names. */
        val CORRESPONDANTS = listOf(
            Correspondant("QF1ABE", "IN88GS", "Yann"), Correspondant("QF5LMN", "JN03QO", "Pierre"),
            Correspondant("QF6KTR", "JN23MI", "Marc"), Correspondant("QF4HBX", "JO10KP", "Julie"),
            Correspondant("QF1RVY", "IN94QU", "Thierry"), Correspondant("QF8DGW", "JN38VN", "Luc"),
            Correspondant("QF4GZP", "JN05LT", "Sophie"), Correspondant("QF5UJA", "JN25DS", "Nicolas"),
            Correspondant("QEA4XY", "IM68MS", "Javier"), Correspondant("QEA1ZK", "IN73DM", "Ana"),
            Correspondant("QEA3HJ", "JN11CK", "Jordi"), Correspondant("QEA5QR", "IM99SL", "Carmen"),
            Correspondant("QCT1EB", "IM58JS", "Rui"), Correspondant("QG4ABC", "IO91VM", "Peter"),
            Correspondant("QG0RTV", "IO83QL", "Ian"), Correspondant("QGM4XZ", "IO85JW", "Callum"),
            Correspondant("QEI4HN", "IO63VI", "Sean"), Correspondant("QON4LK", "JO20EU", "Koen"),
            Correspondant("QPA3GT", "JO22JI", "Bram"), Correspondant("QDL3AB", "JO62QM", "Klaus"),
            Correspondant("QDK7XQ", "JN58TD", "Stefan"), Correspondant("QDF2RT", "JO31NG", "Anja"),
            Correspondant("QHB9CV", "JN47PI", "Urs"), Correspondant("QI0ABC", "JN61FV", "Marco"),
            Correspondant("QIK2XY", "JN45OL", "Giulia"), Correspondant("QIT9ZQ", "JM77NM", "Salvo"),
            Correspondant("QOE3AB", "JN88EF", "Franz"), Correspondant("QOK1DT", "JO70FC", "Petr"),
            Correspondant("QSP5AB", "KO02MF", "Tomasz"), Correspondant("QOZ1AB", "JO65HQ", "Lars"),
            Correspondant("QSM5XY", "JO99BH", "Erik"), Correspondant("QLA9AB", "JO59JW", "Ola"),
            Correspondant("QOH2AB", "KP20LE", "Mika"), Correspondant("QSV1AB", "KM18UA", "Nikos"),
            Correspondant("QYO3AB", "KN34BK", "Mihai"), Correspondant("QHA5AB", "JN97LM", "Gábor"),
            Correspondant("QCN8AB", "IM63NX", "Youssef"), Correspondant("QEA8AB", "IL18QJ", "Pedro"),
            Correspondant("QW1ABC", "FN42IL", "Mike"), Correspondant("QK4XYZ", "EM73UW", "Dave"),
            Correspondant("QVE2AB", "FN35FM", "Luc"), Correspondant("Q4X1AB", "KM72JB", "Avi"),
            Correspondant("QUA3AB", "KO85SR", "Sergei"), Correspondant("QTF3AB", "HP94AD", "Einar"),
        )

        /**
         * The demonstration's « Activités à venir », made up and dated from [t]'s day (UTC): an ISS SSTV
         * series from tomorrow, a post naming SONATE-2 without dates or frequency, and a made-up satellite
         * the catalogue does not know (to pick by hand). Never fetched, never sent anywhere.
         */
        fun activites(t: Long): List<ActivitesAVenir.Activite> {
            val jour = t - Math.floorMod(t, JOUR_MS)
            val h = 3_600_000L
            val src = listOf(ActivitesAVenir.Source("Démonstration (fictive)"))
            return listOf(
                ActivitesAVenir.Activite(id = "demo:sstv-iss", type = ActivitesAVenir.Type.SSTV, satNom = ISS_NOM, catnum = ISS, debutMs = jour + JOUR_MS + 9 * h,
                    finMs = jour + 3 * JOUR_MS + 18 * h, freqHz = 145_800_000L, mode = "PD 120", horaire = "2 min on / 2 min off",
                    titre = "Série SSTV de démonstration (fictive)", details = "Activité inventée pour la démonstration.", sources = src),
                ActivitesAVenir.Activite(id = "demo:sonate", type = ActivitesAVenir.Type.SSTV, satNom = "SONATE-2", catnum = 0, debutMs = jour, finMs = jour + 7 * JOUR_MS,
                    precision = ActivitesAVenir.Precision.INCONNUE, titre = "SONATE-2 : des images SSTV bientôt (nouvelle fictive)", sources = src),
                ActivitesAVenir.Activite(id = "demo:demosat", type = ActivitesAVenir.Type.SSTV, satNom = "DEMOSAT-2", catnum = 0, debutMs = jour + 2 * JOUR_MS + 8 * h,
                    finMs = jour + 2 * JOUR_MS + 20 * h, freqHz = 145_800_000L, mode = "Robot 36",
                    titre = "DEMOSAT-2 : SSTV d'un satellite fictif", sources = src),
            )
        }

        /**
         * The demonstration's made-up awards at [t]: a club's certificate for
         * the made-up series (its pictures are in the gallery: obtainable, its
         * deadline near), and an event to come. Stable through a day.
         */
        fun diplomes(t: Long): List<fr.f4ioz.satcombo.domain.Diplomes.Diplome> {
            val jour = t - Math.floorMod(t, JOUR_MS)
            val h = 3_600_000L
            val g = fr.f4ioz.satcombo.domain.Diplomes.Genre.IMAGE
            return listOf(
                fr.f4ioz.satcombo.domain.Diplomes.Diplome(id = "demo:club", nom = "Diplôme de participation (fictif) — série SSTV de démonstration",
                    emetteur = "Radio-club de démonstration (fictif)", genre = g, satNom = ISS_NOM, catnum = ISS, debutMs = jour - 4 * JOUR_MS, finMs = jour + 2 * JOUR_MS,
                    limiteMs = jour + 2 * JOUR_MS + 20 * h, conditions = "Recevoir une image de la série de démonstration, la demander avec le formulaire du club (fictif : rien n'existe).",
                    champs = listOf("indicatif", "nom", "courriel", "image", "date", "heure"), source = "Démonstration", freqHz = 145_800_000L, mode = "PD 120",
                    unParParticipant = true, notes = "Diplôme inventé pour la démonstration.", fictif = true),
                fr.f4ioz.satcombo.domain.Diplomes.Diplome(id = "demo:demosat", nom = "Certificat DEMOSAT-2 (fictif)", emetteur = "Club spatial de démonstration (fictif)",
                    genre = g, satNom = "DEMOSAT-2", debutMs = jour + 2 * JOUR_MS + 8 * h, finMs = jour + 2 * JOUR_MS + 20 * h, limiteMs = jour + 6 * JOUR_MS,
                    conditions = "Recevoir une image SSTV de DEMOSAT-2 pendant la journée annoncée (satellite fictif).", source = "Démonstration",
                    freqHz = 145_800_000L, mode = "Robot 36", fictif = true),
            )
        }

        fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val r = 6371.0
            val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
            val dp = p2 - p1; val dl = Math.toRadians(lo2 - lo1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
