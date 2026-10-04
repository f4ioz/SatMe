package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.Qrz
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.TleEntry
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
     * recording), [suivi] the one whose page is open (it collects then).
     */
    fun fondTic(catnum: Int?, suivi: Int?, fichier: String?) {
        if (catnum == null) { garde(fond.ferme()); return }
        if (catnum == suivi) { if (!fond.cedeA(page)) garde(fond.ferme()); return }
        val sat = ui().satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        val now = System.currentTimeMillis()
        val pos = runCatching { predictor.positionAt(sat, observateur(), now) }.getOrNull() ?: return
        garde(fond.suit(sat.catalogNumber, sat.name, now, pos.azimuthDeg, pos.elevationDeg,
            etat().copy(enregistrement = fichier, transpondeur = "")))
    }


    /** The passes kept, newest first. */
    fun passages(): List<JournalPassage.Entree> =
        runCatching { rangement.passages() }.getOrDefault(emptyList())

    /** What belongs to a pass by its time: contacts, SSTV pictures, APRS frames. */
    data class Liens(
        val qsos: List<LogEntry> = emptyList(),
        val images: List<Pair<java.io.File, fr.f4ioz.satcombo.sstv.SstvMeta.SstvShot>> = emptyList(),
        val trames: List<fr.f4ioz.satcombo.aprs.Paquet> = emptyList()
    )

    /** Read once for many passes: the gallery and the APRS days are files. */
    fun liens(passages: List<JournalPassage.Entree>): Map<String, Liens> {
        val J = JournalPassage
        val ctx = app
        val log = ui().log
        val images = imagesALHeure()
        runCatching { fr.f4ioz.satcombo.aprs.AprsHub.charge(ctx) }
        val trames = fr.f4ioz.satcombo.aprs.AprsHub.etat.value.paquets
        return passages.associate { e ->
            val f = J.fenetre(e)
            e.id to Liens(
                qsos = log.filter { it.timeMs in f && (it.catnum == e.catnum || J.memeSatellite(it.satName, e.satName)) }
                    .sortedBy { it.timeMs },
                images = images.filter { it.second.timeMs in f }.sortedBy { it.second.timeMs },
                trames = trames.filter { it.quand in f && !it.emis }.sortedBy { it.quand })
        }
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
            for (m in marques.filter { it.type != JournalPassage.TypeMarque.SSTV }.distinctBy { it.texte }) {
                val base = J.indicatifDeBase(m.texte)
                if (base.isBlank() || J.estLeSatellite(base)) continue
                val q = log.filter { J.indicatifDeBase(it.callsign) == base }.maxByOrNull { it.timeMs }
                var f = JournalPassage.Fiche(m.texte, nom = q?.nom.orEmpty(), qth = q?.qth.orEmpty(),
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
            val ll = q.theirLocator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
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
        return (qso + aprs + sstv).sortedBy { it.debutMs }
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
            val obs = ev.locator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
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
            val obs = x.locator.takeIf { it.length >= 4 }?.let { fr.f4ioz.satcombo.location.Maidenhead.toLatLon(it) }
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

    fun supprime(e: JournalPassage.Entree) {
        runCatching { rangement.supprime(e) }
    }
}
