package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.Qrz
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PassPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pass journal (SatMe 21, Alpha 4), out of the view model: the passes
 * gathered (on their page, or in the background while recording), kept,
 * linked to the log, the SSTV pictures, the APRS frames and the recordings,
 * found again from them. Reads the station's state, never acts on it.
 */
class JournalDesPassages(
    private val app: Application,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val predictor: PassPredictor,
    private val qrz: Qrz,
    private val ui: () -> UiState,
    private val observateur: () -> Observer,
    /** What the station does at this second (see [JournalPassage.Etat]). */
    private val etat: () -> JournalPassage.Etat
) {
    /** The pass followed on its page, gathered second by second. */
    private val page = JournalPassage.Collecte()
    /**
     * The pass being recorded when its page is not open — automatic SSTV,
     * every pass under CAT, screen off included: kept as one followed on its
     * page (the two pieces merged if both).
     */
    private val fond = JournalPassage.Collecte()
    private val rangement by lazy { JournalPassage.Rangement(java.io.File(app.filesDir, "journal")) }

    private fun garde(e: JournalPassage.Entree?) {
        e ?: return
        scope.launch(Dispatchers.IO) { runCatching { rangement.enregistre(e) } }
    }

    /** The satellite of the open page, at this second. */
    fun suitPage(sat: TleEntry, pos: SatPosition) {
        garde(page.suit(sat.catalogNumber, sat.name, System.currentTimeMillis(), pos.azimuthDeg, pos.elevationDeg, etat()))
    }

    /** Leaving the page: its pass goes on in the background while it records, else the background closes it. */
    fun quittePage() { if (!page.cedeA(fond)) garde(page.ferme()) }

    /** The last station test, kept with the pass it prepared. */
    fun test(resultat: Map<String, String>, tMs: Long) = page.test(resultat, tMs)

    /**
     * Every few seconds: [catnum] is the satellite being recorded (null: no
     * recording), [suivi] the one whose page is open (it collects then),
     * [par] who started the recording (see [JournalPassage.Entree.auto]),
     * [transpondeur] the transmitter it was armed with.
     */
    fun fondTic(catnum: Int?, suivi: Int?, fichier: String?, par: String, transpondeur: String) {
        if (catnum == null) { gardeFond(fond.ferme()); return }
        if (catnum == suivi) { if (!fond.cedeA(page)) gardeFond(fond.ferme()); return }
        val sat = ui().satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        val now = System.currentTimeMillis()
        val pos = runCatching { predictor.positionAt(sat, observateur(), now) }.getOrNull() ?: return
        gardeFond(fond.suit(sat.catalogNumber, sat.name, now, pos.azimuthDeg, pos.elevationDeg,
            etat().copy(enregistrement = fichier, transpondeur = transpondeur, auto = par)))
    }

    /** A pass kept by itself: written, then a quiet word if wanted (what it holds). */
    private fun gardeFond(e: JournalPassage.Entree?) {
        e ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { rangement.enregistre(e) }
            if (!settings.journalNotif) return@launch
            runCatching {
                val l = liens(listOf(e))[e.id] ?: Liens()
                val h = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                    if (ui().useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val quoi = buildList {
                    if (e.enregistrements.isNotEmpty()) add(fr.f4ioz.satcombo.i18n.t("journal_b_son"))
                    l.images.distinctBy { it.second.timeMs / 10_000 }.size.takeIf { it > 0 }
                        ?.let { add(fr.f4ioz.satcombo.i18n.tf("journal_notif_images", it)) }
                    l.qsos.size.takeIf { it > 0 }?.let { add(fr.f4ioz.satcombo.i18n.tf("journal_b_qso", it)) }
                    l.trames.size.takeIf { it > 0 }?.let { add("APRS $it") }
                }
                fr.f4ioz.satcombo.notify.JournalNotifier.notifie(app,
                    fr.f4ioz.satcombo.i18n.tf("journal_notif_titre", e.satName),
                    h.format(java.util.Date(e.debutMs)) + (if (ui().useUtc) " UTC" else "") +
                        " · " + fr.f4ioz.satcombo.i18n.tf("journal_elmax", e.elMax.toInt()) +
                        (if (quoi.isEmpty()) "" else " · " + quoi.joinToString(" · ")),
                    e.catnum, e.debutMs, e.finMs)
            }
        }
    }

    /** A word when a pass is kept by itself (on by default). */
    fun notif(): Boolean = settings.journalNotif
    fun setNotif(on: Boolean) { settings.journalNotif = on }


    /** The passes kept, newest first. */
    fun passages(): List<JournalPassage.Entree> =
        runCatching { rangement.passages() }.getOrDefault(emptyList())

    /** What belongs to a pass by its time: contacts, SSTV pictures, APRS frames. */
    data class Liens(
        val qsos: List<LogEntry> = emptyList(),
        val images: List<Pair<java.io.File, fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot>> = emptyList(),
        val trames: List<fr.f4ioz.satcombo.aprs.Paquet> = emptyList(),
        /** The moments marked (⚑) during the pass. */
        val signets: List<JournalPassage.Signet> = emptyList()
    )

    /** Read once for many passes: the gallery and the APRS days are files. */
    fun liens(passages: List<JournalPassage.Entree>): Map<String, Liens> {
        val J = JournalPassage
        val ctx = app
        val log = ui().log
        val images = imagesALHeure()
        runCatching { fr.f4ioz.satcombo.aprs.AprsHub.charge(ctx) }
        val trames = fr.f4ioz.satcombo.aprs.AprsHub.etat.value.paquets
        val signets = signets()
        // The contacts received with pass files, those the log does not have already (same station, within 2 min).
        val recus = contactsRecus().filter { r -> log.none { it.callsign.equals(r.callsign, true) && kotlin.math.abs(it.timeMs - r.timeMs) < 120_000L } }
        return passages.associate { e ->
            val f = J.fenetre(e)
            e.id to Liens(
                qsos = (log + recus).filter { it.timeMs in f && (it.catnum == e.catnum || J.memeSatellite(it.satName, e.satName)) }
                    .sortedBy { it.timeMs },
                images = images.filter { it.second.timeMs in f }.sortedBy { it.second.timeMs },
                trames = trames.filter { it.quand in f && !it.emis }.sortedBy { it.quand },
                signets = signets.filter { it.tMs in f && it.catnum == e.catnum }.sortedBy { it.tMs })
        }
    }

    // ---------------------------------------------------------- the summary

    /** One satellite's line of the summary (or the total). */
    data class LigneBilan(val sat: String, val passages: Int = 0, val qsos: Int = 0, val images: Int = 0,
                          val aprs: Int = 0, val signets: Int = 0)
    /** A station on the summary's map: a contact at its locator, or an APRS station where it said it was. */
    data class StationBilan(val lat: Double, val lon: Double, val indicatif: String, val qso: Boolean)
    data class Bilan(val lignes: List<LigneBilan>, val total: LigneBilan, val indicatifs: Int,
                     val stationsAprs: Int, val stations: List<StationBilan>)

    /** All the journal at a glance: by satellite, the stations worked and heard, where they are. */
    fun bilan(passages: List<JournalPassage.Entree>, liens: Map<String, Liens>): Bilan {
        val J = JournalPassage
        fun ligne(sat: String, l: List<JournalPassage.Entree>) = LigneBilan(sat, l.size,
            l.sumOf { liens[it.id]?.qsos?.size ?: 0 },
            l.sumOf { e -> liens[e.id]?.images?.distinctBy { it.second.timeMs / 10_000 }?.size ?: 0 },
            l.sumOf { e -> liens[e.id]?.trames?.map { J.indicatifDeBase(it.source) }?.distinct()?.size ?: 0 },
            l.sumOf { liens[it.id]?.signets?.size ?: 0 })
        val lignes = passages.groupBy { it.satName }.map { (s, l) -> ligne(s, l) }.sortedByDescending { it.passages }
        val tous = passages.mapNotNull { liens[it.id] }
        val qsos = tous.flatMap { it.qsos }
        val trames = tous.flatMap { it.trames }.filter { !J.estLeSatellite(it.source) }
        val stations = qsos.groupBy { J.indicatifDeBase(it.callsign) }.mapNotNull { (_, l) ->
            val q = l.firstOrNull { it.theirLocator.length >= 4 } ?: return@mapNotNull null
            fr.f4ioz.satcombo.location.Maidenhead.centre(q.theirLocator)?.let { (la, lo) -> StationBilan(la, lo, q.callsign, true) }
        } + trames.groupBy { J.indicatifDeBase(it.source) }.mapNotNull { (_, l) ->
            val p = l.firstOrNull { it.lat != null && it.lon != null } ?: return@mapNotNull null
            StationBilan(p.lat!!, p.lon!!, p.source, false)
        }
        return Bilan(lignes, ligne("", passages), qsos.map { J.indicatifDeBase(it.callsign) }.distinct().size,
            trames.map { J.indicatifDeBase(it.source) }.distinct().size, stations)
    }

    // ------------------------------------------------------------- signets

    private val fichierSignets get() = java.io.File(java.io.File(app.filesDir, "journal").apply { mkdirs() }, "signets.tsv")

    /** A moment marked now (⚑) for [catnum]: kept at once, found by its time in the pass. */
    fun signet(catnum: Int, tMs: Long = System.currentTimeMillis(), note: String = ""): JournalPassage.Signet {
        val s = JournalPassage.Signet(tMs, catnum, note)
        synchronized(this) { runCatching { fichierSignets.appendText(JournalPassage.ecritSignet(s) + "\n") } }
        return s
    }

    fun signets(): List<JournalPassage.Signet> = synchronized(this) {
        runCatching { fichierSignets.readLines().mapNotNull { JournalPassage.litSignet(it) } }.getOrDefault(emptyList())
    }

    fun supprimeSignet(s: JournalPassage.Signet) = synchronized(this) {
        runCatching { fichierSignets.writeText(signets().filter { it != s }.joinToString("") { JournalPassage.ecritSignet(it) + "\n" }) }
    }

    /** What was heard, written on a bookmark afterwards. */
    fun noteSignet(s: JournalPassage.Signet, note: String) = synchronized(this) {
        runCatching { fichierSignets.writeText(signets().joinToString("") {
            JournalPassage.ecritSignet(if (it == s) it.copy(note = note.trim()) else it) + "\n" }) }
    }

    // Contacts brought by pass files: shown with their pass, never in the log (not this station's own).
    private val fichierContactsRecus get() = java.io.File(java.io.File(app.filesDir, "journal").apply { mkdirs() }, "contacts.tsv")

    fun contactsRecus(): List<LogEntry> = synchronized(this) {
        runCatching { fichierContactsRecus.readLines().mapNotNull { JournalPaquet.litContact(it) } }.getOrDefault(emptyList())
    }

    fun ajouteContactsRecus(l: List<LogEntry>) = synchronized(this) {
        val deja = contactsRecus().map { it.callsign to it.timeMs }.toSet()
        l.filter { (it.callsign to it.timeMs) !in deja }
            .forEach { runCatching { fichierContactsRecus.appendText(JournalPaquet.ecritContact(it) + "\n") } }
    }

    /** Signets brought by a pass file: kept, without doubles. */
    fun ajouteSignets(l: List<JournalPassage.Signet>) = synchronized(this) {
        val deja = signets().toSet()
        l.filter { it !in deja }.forEach { runCatching { fichierSignets.appendText(JournalPassage.ecritSignet(it) + "\n") } }
    }

    /** The pass as predicted, AOS to LOS, for the real one to be drawn against. */
    fun prevue(e: JournalPassage.Entree): List<Pair<Double, Double>> = runCatching {
        val sat = ui().satellites.firstOrNull { it.catalogNumber == e.catnum } ?: return emptyList()
        val obs = observateur()
        val (aos, los) = predictor.currentPass(sat, obs, (e.debutMs + e.finMs) / 2) ?: (e.debutMs to e.finMs)
        predictor.passTrack(sat, obs, aos, los)
    }.getOrDefault(emptyList())

    /** A recording of the pass, if still on the phone. */
    fun enregistrement(nom: String): java.io.File? =
        java.io.File(app.getExternalFilesDir(null), "recordings/$nom").takeIf { it.isFile }

    /** Where under the satellite each point of the pass was (time, lat, lon), for the map. */
    fun traceSol(e: JournalPassage.Entree): List<JournalPassage.Sol> = runCatching {
        val sat = ui().satellites.firstOrNull { it.catalogNumber == e.catnum } ?: return emptyList()
        val obs = observateur()
        e.points.mapNotNull { p -> predictor.positionAt(sat, obs, p.tMs)?.let {
            JournalPassage.Sol(p.tMs, it.latDeg, it.lonDeg, it.altKm) } }
    }.getOrDefault(emptyList())

    /** Seconds the log's contacts are put back (logged after the contact ended). */
    fun decalageQsoS(): Int = settings.journalDecalageQsoS
    fun setDecalageQsoS(s: Int) { settings.journalDecalageQsoS = s }
    /** The video ends on the pass's pictures and a summary. */
    fun recap(): Boolean = settings.journalRecap
    /** The video opens on a title (satellite, UTC, the receiving station, SatMe). */
    fun ouverture(): Boolean = settings.journalOuverture
    /** The journal's video size: XS (480 px), M (720 px), HD (1080 px). */
    fun videoRes(): String = settings.journalVideoRes
    fun setVideoRes(r: String) { settings.journalVideoRes = r }
    fun setOuverture(on: Boolean) { settings.journalOuverture = on }
    fun setRecap(on: Boolean) { settings.journalRecap = on }

    /**
     * Who the stations of a pass are, for their cards: the log first (name,
     * town, locator kept with a contact), the APRS position for the locator,
     * then QRZ.com for what is missing — only with QRZ credentials set, a
     * few lookups at most, kept for the session.
     */
    suspend fun fiches(marques: List<JournalPassage.Marque>): Map<String, JournalPassage.Fiche> =
        withContext(Dispatchers.IO) {
            val J = JournalPassage
            val log = ui().log
            val c = ui().carnet
            val out = HashMap<String, JournalPassage.Fiche>()
            var recherches = 0
            for (m in marques.filter { it.type == JournalPassage.TypeMarque.QSO || it.type == JournalPassage.TypeMarque.APRS }.distinctBy { it.texte }) {
                val base = J.indicatifDeBase(m.texte)
                if (base.isBlank() || J.estLeSatellite(base)) continue
                // Name and town from any contact with that station (one imported from the online log
                // has them, one typed during a pass not), then the correspondents' memory.
                val avec = log.filter { J.indicatifDeBase(it.callsign) == base }.sortedByDescending { it.timeMs }
                val q = avec.firstOrNull()
                val nomConnu = avec.firstOrNull { it.nom.isNotBlank() }?.nom
                    ?: ui().express.memoire.firstOrNull { J.indicatifDeBase(it.indicatif) == base && it.nom.isNotBlank() }?.nom.orEmpty()
                var f = JournalPassage.Fiche(m.texte, nom = nomConnu, qth = avec.firstOrNull { it.qth.isNotBlank() }?.qth.orEmpty(),
                    locator = q?.theirLocator?.ifBlank { null }
                        ?: if (m.lat != null && m.lon != null) fr.f4ioz.satcombo.location.Maidenhead.fromLatLon(m.lat, m.lon) else "")
                ficheQrzCache[base]?.let { r -> f = f.copy(nom = f.nom.ifBlank { r.nom }, qth = f.qth.ifBlank { r.qth },
                    pays = r.pays, locator = f.locator.ifBlank { r.locator }) }
                if (f.nom.isBlank() && !ficheQrzCache.containsKey(base) && recherches < 20 && c.qrzUser.isNotBlank() && c.qrzMdp.isNotBlank()) {
                    recherches++
                    val r = runCatching {
                        var x = qrz.cherche(base)
                        if (x.erreur.contains("connect", true)) { qrz.connecte(c.qrzUser, c.qrzMdp); x = qrz.cherche(base) }
                        x
                    }.getOrNull()
                    val fr2 = JournalPassage.Fiche(base, nom = r?.nom.orEmpty(), qth = r?.qth.orEmpty(), pays = r?.pays.orEmpty(), locator = r?.carre.orEmpty())
                    ficheQrzCache[base] = fr2
                    f = f.copy(nom = f.nom.ifBlank { fr2.nom }, qth = f.qth.ifBlank { fr2.qth }, pays = fr2.pays,
                        locator = f.locator.ifBlank { fr2.locator })
                }
                out[m.texte] = f
            }
            out
        }
    /** Who [indicatif] is: the log first, then QRZ.com when its credentials are set (kept for the session). */
    suspend fun ficheIndicatif(indicatif: String): JournalPassage.Fiche? =
        fiches(listOf(JournalPassage.Marque(JournalPassage.TypeMarque.QSO, 0L, 0L, indicatif.trim().uppercase())))[indicatif.trim().uppercase()]

    private val ficheQrzCache = java.util.concurrent.ConcurrentHashMap<String, JournalPassage.Fiche>()

    /** How long an SSTV picture is shown when it has arrived, during the replay (s; 0 = never). */
    fun flashS(): Int = settings.journalFlashS.let { if (it <= 0) 3 else it }
    /** During the replay: the SSTV picture arriving; the stations' cards. Off before as "0 s". */
    fun affSstv(): Boolean = settings.journalFlashS > 0 && settings.journalAffSstv
    fun setAffSstv(on: Boolean) { settings.journalAffSstv = on; if (settings.journalFlashS <= 0) settings.journalFlashS = 3 }
    fun affFiches(): Boolean = settings.journalAffFiches
    fun setAffFiches(on: Boolean) { settings.journalAffFiches = on }
    /** Its size, a share of the view's width. */
    fun flashTaille(): Float = settings.journalFlashTaille
    /** Seconds before what was logged: where a jump lands, where the accelerated replay slows down. */
    fun avantS(): Int = settings.journalAvantS
    fun setAvantS(s: Int) { settings.journalAvantS = s }
    /** Fast where nothing was logged, normal around it; how many times faster. */
    fun apresQsoS(): Int = settings.journalApresQsoS
    fun setApresQsoS(s: Int) { settings.journalApresQsoS = s }
    fun affSmetre(): Boolean = settings.journalAffSmetre
    fun setAffSmetre(on: Boolean) { settings.journalAffSmetre = on }
    fun affFreq(): Boolean = settings.journalAffFreq
    fun setAffFreq(on: Boolean) { settings.journalAffFreq = on }
    fun affLocator(): Boolean = settings.journalAffLocator
    fun setAffLocator(on: Boolean) { settings.journalAffLocator = on }

    /**
     * Where the rig was in the passband, second by second: the RX frequency
     * read under CAT with that moment's Doppler taken off (rest frequency, as
     * the log has it) — on a linear transponder, the spot actually listened to.
     */
    fun frequencesRepos(e: JournalPassage.Entree): List<Pair<Long, Long>> = runCatching {
        val sat = ui().satellites.firstOrNull { it.catalogNumber == e.catnum } ?: return emptyList()
        val obs = fr.f4ioz.satcombo.location.Maidenhead.centre(e.locator)
            ?.let { (la, lo) -> Observer(la, lo) } ?: observateur()
        val lus = e.points.filter { it.dlHz != null }
        val rr = predictor.rangeRates(sat, obs, lus.map { it.tMs })
        lus.mapIndexed { i, p -> p.tMs to fr.f4ioz.satcombo.domain.Doppler.restFromDownlink(p.dlHz!!, rr[i]) }
    }.getOrDefault(emptyList())

    fun accelere(): Boolean = settings.journalAccelere
    fun setAccelere(on: Boolean) { settings.journalAccelere = on }
    fun rapide(): Int = settings.journalRapide
    fun setRapide(x: Int) { settings.journalRapide = x }
    fun setFlashTaille(v: Float) { settings.journalFlashTaille = v }

    /** Length of a recording (ms), read once. */
    private val dureesEnregistrements = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun dureeEnregistrement(f: java.io.File): Long = dureesEnregistrements.getOrPut(f.name + ":" + f.length()) {
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
            finally { runCatching { r.release() } }
        }.getOrDefault(0L)
    }

    /**
     * The recording that covers a pass, if one is still on the phone: by its
     * start (in its name) and its length, of that satellite — or of none
     * named ("SAT", "APRS"). The one covering most of the pass.
     */
    fun sonDuPassage(e: JournalPassage.Entree): java.io.File? = runCatching {
        val J = JournalPassage
        val dossier = java.io.File(app.getExternalFilesDir(null), "recordings")
        (dossier.listFiles { f -> f.name.endsWith(".mp3") } ?: emptyArray()).mapNotNull { f ->
            val debut = fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(f.name).takeIf { it > 0L } ?: return@mapNotNull null
            // A quick look before reading the length: started after the pass, or a day before.
            if (debut > e.finMs || debut < e.debutMs - 6 * 3_600_000L) return@mapNotNull null
            val nom = f.name.removePrefix("SatMe_").substringBeforeLast("_").substringBeforeLast("_")
            val autreSat = satImport(0, nom)?.let { it.catalogNumber != e.catnum } ?: false
            if (autreSat) return@mapNotNull null
            val fin = debut + dureeEnregistrement(f)
            val commun = minOf(fin, e.finMs) - maxOf(debut, e.debutMs)
            if (commun < 30_000L) null else f to commun
        }.maxByOrNull { it.second }?.first
    }.getOrNull()
    fun setFlashS(s: Int) { settings.journalFlashS = s }

    /**
     * What to put on the trajectory: contacts (at the other station's locator on
     * the map), APRS frames (at the station's position when it gave one), SSTV
     * pictures (from their start to their end, a picture taking its mode's time).
     */
    fun marques(l: Liens, decalageQsoS: Int = settings.journalDecalageQsoS): List<JournalPassage.Marque> {
        val J = JournalPassage
        // A contact is logged a few seconds after it ended: put back where it was heard.
        val avance = decalageQsoS * 1000L
        val qso = l.qsos.map { q ->
            val ll = fr.f4ioz.satcombo.location.Maidenhead.centre(q.theirLocator)
            val rst = listOf(q.rstSent, q.rstRcvd).filter { it.isNotBlank() }.joinToString(" / ")
            JournalPassage.Marque(JournalPassage.TypeMarque.QSO,
                q.timeMs - avance, q.timeMs - avance, q.callsign, ll?.first, ll?.second,
                details = listOf(q.mode, rst).filter { it.isNotBlank() }.joinToString(" · "))
        }
        val aprs = l.trames.distinctBy { it.source to it.quand / 10_000 }.map { p ->
            // The ISS's own beacon belongs on the trajectory, not where it says it is.
            val sat = J.estLeSatellite(p.source)
            JournalPassage.Marque(JournalPassage.TypeMarque.APRS, p.quand, p.quand, p.source,
                if (sat) null else p.lat, if (sat) null else p.lon, p.viaIss, details = p.commentaire.trim().take(70))
        }
        // A picture decoded again (_r2) is the same picture: one stretch.
        val sstv = l.images.distinctBy { it.second.timeMs / 10_000 }.map { (f, s) ->
            val duree = fr.f4ioz.satcombo.sstv.SstvMode.byName(s.mode)?.let { (it.frameSeconds * 1000).toLong() + 1_000L } ?: 60_000L
            JournalPassage.Marque(JournalPassage.TypeMarque.SSTV, s.timeMs - duree, s.timeMs, s.mode,
                fichier = f.absolutePath)
        }
        // The moments marked (⚑): on the trajectory, the note as what was said.
        val signets = l.signets.map { s ->
            JournalPassage.Marque(JournalPassage.TypeMarque.SIGNET, s.tMs, s.tMs,
                "⚑" + if (s.note.isBlank()) "" else " " + s.note.take(18), details = s.note)
        }
        return (qso + aprs + sstv + signets).sortedBy { it.debutMs }
    }

    /**
     * The SSTV pictures, each at the time it was really received: those
     * decoded again from an older recording put back by its spoken header.
     */
    private fun imagesALHeure(): List<Pair<java.io.File, fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot>> {
        val ctx = app
        val tous = runCatching { fr.f4ioz.satcombo.sstv.SstvHub.shots(ctx) }.getOrDefault(emptyList())
        val directs = tous.map { it.second }.filter { it.source == "live" }
        val dossier = java.io.File(ctx.getExternalFilesDir(null), "recordings")
        val annonces = HashMap<String, Long>()
        return tous.map { (f, s) ->
            if (s.source != "file" || s.recale) return@map f to s
            val annonce = annonces.getOrPut(s.recording) {
                val rec = java.io.File(dossier, s.recording)
                // Already taken off when the length was kept with the recording.
                if (!rec.isFile || (fr.f4ioz.satcombo.audio.InfoEnregistrement.lit(rec)?.annonceMs ?: 0L) > 0L) 0L
                else fr.f4ioz.satcombo.audio.InfoEnregistrement.annonceMs(rec, dureeEnregistrement(rec))
            }
            f to s.copy(timeMs = fr.f4ioz.satcombo.sstv.SstvMeta.heureOrigine(s, directs, annonce))
        }
    }

    /**
     * Where something was heard in a recording (see [fr.f4ioz.satcombo.domain.ActiviteSon]),
     * as pass times; the spoken header left out. Worked out once, kept next to
     * the recording ("<name>.activite").
     */
    fun activite(f: java.io.File): List<LongRange> = runCatching {
        val A = fr.f4ioz.satcombo.domain.ActiviteSon
        val garde = java.io.File(f.parentFile, f.name.substringBeforeLast('.') + ".activite")
        val dansFichier = if (garde.isFile && garde.lastModified() >= f.lastModified()) A.lit(garde.readText()) else {
            var m: fr.f4ioz.satcombo.domain.ActiviteSon.Mesure? = null
            fr.f4ioz.satcombo.sstv.Mp3Pcm.decode(f) { pcm, count, rate, _ ->
                (m ?: fr.f4ioz.satcombo.domain.ActiviteSon.Mesure(rate).also { m = it }).ajoute(pcm, count); true
            }
            val p = m?.let { A.plages(it.fenetres) } ?: emptyList()
            runCatching { garde.writeText(A.ecrit(p)) }
            p
        }
        val annonce = annonceDe(f)
        val origine = origineSon(f)
        if (origine <= 0L) return emptyList()
        dansFichier.filter { it.last > annonce }.map { (origine + maxOf(it.first, annonce))..(origine + it.last) }
    }.getOrDefault(emptyList())

    /**
     * The recordings of a pass, in order (each with when its start was heard,
     * its spoken header, its length): those it names, else one found covering it.
     */
    fun morceaux(e: JournalPassage.Entree): List<fr.f4ioz.satcombo.ui.JournalRendu.Morceau> =
        (e.enregistrements.mapNotNull { enregistrement(it) }.ifEmpty { listOfNotNull(sonDuPassage(e)) })
            .distinctBy { it.name }
            .map { fr.f4ioz.satcombo.ui.JournalRendu.Morceau(it, origineSon(it), annonceDe(it), dureeEnregistrement(it)) }
            .filter { it.origine > 0L }.sortedBy { it.origine }

    /** Recordings of the same day near a pass (3 h either side), not yet attached: to attach by hand. */
    fun enregistrementsProches(e: JournalPassage.Entree): List<java.io.File> {
        val dossier = java.io.File(app.getExternalFilesDir(null), "recordings")
        return (dossier.listFiles { f -> f.name.endsWith(".mp3") } ?: emptyArray()).filter { f ->
            val d = fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(f.name)
            d > 0L && d in (e.debutMs - 3 * 3_600_000L)..(e.finMs + 3 * 3_600_000L) && f.name !in e.enregistrements
        }.sortedBy { it.name }
    }

    /** Slow down too where the sound shows activity (on by default). */
    fun surActivite(): Boolean = settings.journalActivite
    fun setSurActivite(on: Boolean) { settings.journalActivite = on }

    /** The spoken header of a recording (kept, else estimated), to put its sound in step. */
    fun annonceDe(f: java.io.File): Long = fr.f4ioz.satcombo.audio.InfoEnregistrement.annonceMs(f, dureeEnregistrement(f))

    /** When the start of a recording's file was (the header's first sample), to put its sound at the right time. */
    fun origineSon(f: java.io.File): Long = fr.f4ioz.satcombo.audio.InfoEnregistrement.origine(
        fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(f.name), f.lastModified(), dureeEnregistrement(f), annonceDe(f))

    /** A pass of the journal changed (pictures set aside…): written again. */
    fun maj(e: JournalPassage.Entree) {
        // A small file: written at once, so the list read just after sees it.
        runCatching { rangement.enregistre(e) }
    }

    /** A satellite by its number, else by its name (as written in a picture or a recording). */
    fun satImport(catnum: Int, nom: String): TleEntry? {
        val l = ui().satellites
        return l.firstOrNull { catnum > 0 && it.catalogNumber == catnum }
            // The same name exactly: "SAT" (no satellite chosen) must not become some "SAT-…".
            ?: JournalPassage.nomNormalise(nom).takeIf { it.length >= 2 }
                ?.let { n -> l.firstOrNull { JournalPassage.nomNormalise(it.name) == n } }
    }

    /**
     * What past passes can be found again from: the log's contacts, the SSTV
     * pictures, the APRS frames relayed by the ISS (the last 7 days are kept),
     * the recordings (their name gives satellite and start).
     */
    fun evenementsImport(): Map<JournalPassage.Source, List<JournalPassage.Evenement>> {
        val S = JournalPassage.Source.entries
        val ctx = app
        fun ev(src: JournalPassage.Source, t: Long, sat: TleEntry, loc: String, enr: String? = null) =
            JournalPassage.Evenement(src, t, sat.catalogNumber, sat.name, loc, enr)
        val carnet = ui().log.mapNotNull { q ->
            satImport(q.catnum, q.satName)?.let { ev(S[0], q.timeMs, it, q.myLocator) }
        }
        val sstv = imagesALHeure()
            .distinctBy { it.second.timeMs / 10_000 }
            .mapNotNull { (_, s) -> satImport(0, s.satName)?.let { ev(S[1], s.timeMs, it, s.locator) } }
        runCatching { fr.f4ioz.satcombo.aprs.AprsHub.charge(ctx) }
        val iss = satImport(25544, "ISS")
        val aprs = if (iss == null) emptyList() else fr.f4ioz.satcombo.aprs.AprsHub.etat.value.paquets
            .filter { it.viaIss && !it.emis }.map { ev(S[2], it.quand, iss, "") }
        val dossier = java.io.File(ctx.getExternalFilesDir(null), "recordings")
        val enregistrements = (dossier.listFiles { f -> f.name.endsWith(".mp3") } ?: emptyArray()).mapNotNull { f ->
            val debut = fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(f.name).takeIf { it > 0L } ?: return@mapNotNull null
            val nom = f.name.removePrefix("SatMe_").substringBeforeLast("_").substringBeforeLast("_")
            val sat = satImport(0, nom) ?: return@mapNotNull null
            val info = fr.f4ioz.satcombo.audio.InfoEnregistrement.lit(f)
            // Two minutes in: past the spoken header, inside the pass even when started early.
            ev(S[3], debut + 120_000L, sat, info?.locator.orEmpty(), f.name)
        }
        return mapOf(S[0] to carnet, S[1] to sstv, S[2] to aprs, S[3] to enregistrements)
    }

    /** The passes those events belong to (today's elements, from where each was made), and how many were not placed. */
    fun candidatsImport(evts: List<JournalPassage.Evenement>):
        Pair<List<JournalPassage.Candidat>, Int> {
        val defaut = observateur()
        return JournalPassage.regroupe(evts, { ev ->
            val sat = ui().satellites.firstOrNull { it.catalogNumber == ev.catnum } ?: return@regroupe null
            val obs = fr.f4ioz.satcombo.location.Maidenhead.centre(ev.locator)
                ?.let { (la, lo) -> Observer(la, lo) } ?: defaut
            // Elements drift and clocks too: looked for up to 15 minutes either side.
            val ecarts = listOf(0) + (1..15).flatMap { listOf(it, -it) }
            ecarts.firstNotNullOfOrNull { d -> runCatching { predictor.currentPass(sat, obs, ev.tMs + d * 60_000L) }.getOrNull() }
        }, passages())
    }

    /** The passes chosen, written into the journal; how many. */
    fun importe(c: List<JournalPassage.Candidat>): Int {
        val defaut = observateur()
        var n = 0
        for (x in c) runCatching {
            val sat = ui().satellites.firstOrNull { it.catalogNumber == x.catnum } ?: return@runCatching
            val obs = fr.f4ioz.satcombo.location.Maidenhead.centre(x.locator)
                ?.let { (la, lo) -> Observer(la, lo) } ?: defaut
            val pas = ((x.losMs - x.aosMs) / JournalPassage.PAS_MS).toInt().coerceIn(10, 400)
            val piste = predictor.sampleTrack(sat, obs, x.aosMs - 5_000L, x.losMs + 5_000L, pas)
            JournalPassage.reconstitue(x, piste)?.let { r ->
                // Its sound, if a recording covers it, even when not chosen as a source.
                val avecSon = if (r.enregistrements.isEmpty()) sonDuPassage(r)?.let { r.copy(enregistrements = listOf(it.name)) } ?: r else r
                rangement.enregistre(avecSon); n++
            }
        }
        return n
    }

    /**
     * The pass in one file (see [JournalPaquet]), to keep or give: its
     * recording, its SSTV pictures (not those set aside). Written in the
     * cache's export folder, shared or saved from there.
     */
    fun paquetQsos(): Boolean = settings.journalPaquetQsos
    fun setPaquetQsos(on: Boolean) { settings.journalPaquetQsos = on }

    /** The pass in one file; with its contacts when [avecContacts] (the pass's, without their email). */
    fun paquet(e: JournalPassage.Entree, avecContacts: Boolean = paquetQsos()): java.io.File {
        val rec = java.io.File(app.getExternalFilesDir(null), "recordings")
        val sons = (e.enregistrements.map { java.io.File(rec, it) } + listOfNotNull(sonDuPassage(e)))
            .filter { it.isFile }.distinctBy { it.name }
        val images = (liens(listOf(e))[e.id]?.images ?: emptyList()).map { it.first }
            .filter { it.name !in e.masquees }.distinctBy { it.name }
        val fichiers = sons.flatMap { listOf("recordings" to it, "recordings" to fr.f4ioz.satcombo.audio.InfoEnregistrement.fichier(it)) } +
            images.flatMap { listOf("sstv" to it, "sstv" to java.io.File(it.parentFile, fr.f4ioz.satcombo.sstv.SstvMeta.sidecarName(it.name))) }
        val sortie = java.io.File(java.io.File(app.cacheDir, "export").apply { mkdirs() }, JournalPaquet.nom(e))
        // Its recordings named in it: the other phone finds the sound as here.
        val avecSons = e.copy(enregistrements = (e.enregistrements + sons.map { it.name }).distinct())
        val l = liens(listOf(e))[e.id]
        val sesSignets = l?.signets ?: emptyList()
        val sesContacts = if (avecContacts) l?.qsos.orEmpty().filter { it.callsign.isNotBlank() } else emptyList()
        sortie.outputStream().use { JournalPaquet.emballe(it, avecSons, fichiers, sesSignets, sesContacts) }
        return sortie
    }

    /** A pass file opened: its recording and pictures put in place, the pass kept (merged if already here). */
    fun importePaquet(uri: android.net.Uri): JournalPaquet.Deballage {
        val ext = app.getExternalFilesDir(null)
        val d = app.contentResolver.openInputStream(uri)?.use {
            JournalPaquet.deballe(it, mapOf("recordings" to java.io.File(ext, "recordings"), "sstv" to java.io.File(ext, "sstv")))
        } ?: return JournalPaquet.Deballage(null)
        // An older pack (no times kept): each recording's end from its name (its start) and its length.
        d.sansDate.filter { it.name.endsWith(".mp3") }.forEach { f ->
            val debut = fr.f4ioz.satcombo.sstv.SstvMeta.debutEnregistrement(f.name)
            val duree = dureeEnregistrement(f)
            if (debut > 0L && duree > 0L) f.setLastModified(debut + duree)
        }
        d.entree?.let { rangement.enregistre(it) }
        if (d.signets.isNotEmpty()) ajouteSignets(d.signets)
        if (d.contacts.isNotEmpty()) ajouteContactsRecus(d.contacts)
        return d
    }

    /**
     * The room taken by the recordings and the SSTV pictures, and the old
     * recordings nothing uses (no pass of the journal, no SSTV picture
     * decoded from it, not the one being written).
     */
    fun place(): fr.f4ioz.satcombo.domain.PlaceEnregistrements.Bilan {
        val P = fr.f4ioz.satcombo.domain.PlaceEnregistrements
        val ext = app.getExternalFilesDir(null)
        fun liste(d: String) = (java.io.File(ext, d).listFiles() ?: emptyArray()).filter { it.isFile }
            .map { fr.f4ioz.satcombo.domain.PlaceEnregistrements.Fichier(it.name, it.length(), it.lastModified()) }
        val passages = passages()
        val utilises = HashSet<String>()
        passages.forEach { utilises += it.enregistrements }
        passages.forEach { e -> sonDuPassage(e)?.let { utilises += it.name } }
        runCatching { fr.f4ioz.satcombo.sstv.SstvHub.shots(app) }.getOrDefault(emptyList())
            .forEach { if (it.second.recording.isNotBlank()) utilises += it.second.recording }
        val enCours = fr.f4ioz.satcombo.audio.RecorderService.state.value.let { if (it.recording) it.fileName else null }
        return P.bilan(liste("recordings"), liste("sstv"), utilises, enCours, System.currentTimeMillis())
    }

    /** The old recordings nothing uses, deleted (with their ".info"), counted again first; how many. */
    fun supprimeVieux(): Int {
        val rec = java.io.File(app.getExternalFilesDir(null), "recordings")
        var n = 0
        for (nom in place().vieux) {
            val f = java.io.File(rec, nom)
            if (f.delete()) {
                n++; fr.f4ioz.satcombo.audio.InfoEnregistrement.fichier(f).delete()
                java.io.File(rec, nom.substringBeforeLast('.') + ".activite").delete()
            }
        }
        return n
    }

    fun supprime(e: JournalPassage.Entree) {
        runCatching { rangement.supprime(e) }
    }
}
