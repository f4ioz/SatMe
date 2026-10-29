/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.BullePhonie
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * « Vidéo DATV en direct » (Publication web › En direct, off by default): the received DATV picture and its sound sent
 * live to the owner's site as **HLS** — short segments (1 or 2 s) in one or several renditions the owner picks
 * ([Rendu]: the received stream as it is, or re-encoded by ffmpeg in 720p, 480p, 360p), pushed one by one
 * (`PUT …/direct-video/<rendition>/<n>.ts`, the publication token) with a state message (`direct-video`) saying the
 * renditions; the site makes the playlists and serves the visitors. Only while the receiver is locked: nothing is
 * sent otherwise, and the site is told « hors ligne » when it stops.
 *
 * With it, optionally:
 * - **Sous-titres** — the DATV subtitles SatMe-PC writes from the received speech (and their translation), each over
 *   sent as it is written (`direct-soustitres`: its text, its language, its translation, its start and end on the
 *   wall clock, which the site lines up with the segments' times); also while only the picture « Image DATV » is
 *   sent.
 * - **Audio traduit** — while « Traduction parlée » speaks the DATV's overs: a second sound track (`trad`, audio
 *   only) where the synthetic voice speaks over the received sound lowered (« mix »), or alone (« seule »); the
 *   visitor chooses the track in the player.
 *
 * ffmpeg does the work (one process: the stream decoded once, each rendition encoded with the `veryfast` or
 * `ultrafast` preset): the page says how much CPU and upload it takes. What goes is the received picture and
 * sound, and the texts heard: never a setting, an address or a file of the station.
 */
class VideoSitePc(
    private val stockage: Stockage,
    private val site: SitePc,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val ffmpeg: () -> String? = { RenduPc.ffmpeg() },
    private val demo: () -> Boolean = { ModeDemo.actif },
) {
    /** A rendition: its name on the site, its label, its height (null: as received), its video and audio rates (kbit/s; null: copied). */
    enum class Rendu(val nom: String, val libelle: String, val hauteur: Int?, val videoKbps: Int?, val audioKbps: Int) {
        SOURCE("source", "Source (copie sans réencodage)", null, null, 96),
        P720("720p", "720p (~2 Mb/s)", 720, 2000, 96),
        P480("480p", "480p (~1 Mb/s)", 480, 1000, 96),
        P360("360p", "360p (~500 kb/s)", 360, 450, 64);

        companion object { fun de(n: String) = values().firstOrNull { it.nom == n } }
    }

    /** Where the stream comes from: the DATV receiver ([DatvPc]), or the demonstration's test picture. */
    interface SourceTs {
        /** [f] gets each block of the transport stream from now on; null: no more. */
        fun branche(f: ((ByteArray) -> Unit)?)
        fun verrouille(): Boolean
        fun service(): String
        /** The stream's rate now (kbit/s), 0 when unknown. */
        fun kbps(): Int
    }

    // Set by Main.
    @Volatile var source: SourceTs? = null
    /** The DATV's overs written out since [Long] (their id), the DATV's only; the latest id now. */
    @Volatile var repliques: (Long) -> List<BullePhonie> = { emptyList() }
    @Volatile var derniereReplique: () -> Long = { 0L }
    /** « Traduction parlée » speaks the DATV now, and in which language ("" when not). */
    @Volatile var paroleDatv: () -> String = { "" }
    /** The subtitles are being translated, and into which language ("" when not). */
    @Volatile var traductionDatv: () -> String = { "" }
    /** « Image DATV » is sent (the subtitles then go with the picture alone). */
    @Volatile var imageDatv: () -> Boolean = { false }

    // ------------------------------------------------------------------ settings

    var on: Boolean
        get() = stockage.getBoolean(CLE, false)
        set(v) { stockage.edit().putBoolean(CLE, v).apply() }
    var rendus: List<Rendu>
        get() = (stockage.getString("${CLE}_rendus", "480p") ?: "480p").split(',').mapNotNull { Rendu.de(it.trim()) }.distinct()
            .sortedBy { it.ordinal }.ifEmpty { listOf(Rendu.P480) }
        set(v) { stockage.edit().putString("${CLE}_rendus", v.distinct().sortedBy { it.ordinal }.joinToString(",") { it.nom }.ifEmpty { "480p" }).apply() }
    var segmentS: Int
        get() = if (stockage.getInt("${CLE}_segment", 2) == 1) 1 else 2
        set(v) { stockage.edit().putInt("${CLE}_segment", if (v == 1) 1 else 2).apply() }
    var preset: String
        get() = (stockage.getString("${CLE}_preset", "veryfast") ?: "veryfast").takeIf { it in PRESETS } ?: "veryfast"
        set(v) { if (v in PRESETS) stockage.edit().putString("${CLE}_preset", v).apply() }
    var sousTitres: Boolean
        get() = stockage.getBoolean("${CLE}_st", false)
        set(v) { stockage.edit().putBoolean("${CLE}_st", v).apply(); if (v) repliqueVue = -1L }
    /** « non », « mix » (the voice over the received sound lowered), « seule » (the voice alone). */
    var audioTraduit: String
        get() = (stockage.getString("${CLE}_audio", "non") ?: "non").takeIf { it in AUDIOS } ?: "non"
        set(v) { if (v in AUDIOS) stockage.edit().putString("${CLE}_audio", v).apply() }

    // ------------------------------------------------------------------ state

    @Volatile var erreur = ""; private set
    @Volatile var etat = "arrêté"; private set
    @Volatile private var session: Session? = null
    @Volatile private var pasAvantMs = 0L
    @Volatile private var etatEnvoyeMs = 0L
    @Volatile private var horsLigneDu = false
    /** The sequence of each rendition on the site: always growing, across sessions and restarts. */
    private val seqs = HashMap<String, Long>()
    @Volatile var segmentsEnvoyes = 0L; private set
    private val envoisRecents = ArrayDeque<Pair<Long, Int>>()
    @Volatile private var repliqueVue = -1L
    @Volatile var repliquesEnvoyees = 0L; private set
    @Volatile private var dernierBlocMs = 0L
    @Volatile private var essaiMix = true
    /** The copied rendition's codecs as its first segment says them (null before). */
    @Volatile private var codecsSource: String? = null

    // ------------------------------------------------------------------ the loop

    @Volatile private var fil: Thread? = null

    fun demarre() {
        if (fil != null) return
        fil = Thread({ while (true) { runCatching { tic() }.onFailure { erreur = it.message ?: it.javaClass.simpleName }; Thread.sleep(500L) } }, "satme-video-site")
            .apply { isDaemon = true; start() }
    }

    /** Twice a second: the session started or stopped as the receiver locks, the state kept alive, the subtitles sent. */
    fun tic() {
        val t = horloge()
        val src = source
        val possible = on && site.fluxPossible && src != null
        // The demonstration's test picture runs only while the option is on.
        if (src is SourceTsDemo) src.voulue = possible
        val verrou = possible && runCatching { src!!.verrouille() }.getOrDefault(false)
        val s = session
        when {
            s != null && (!verrou || !s.vivante(t)) -> {
                arrete(if (!verrou) "en attente du verrouillage DATV" else s.raison())
            }
            s == null && verrou && t >= pasAvantMs -> demarreSession(src!!, t)
            s == null && !possible -> { etat = if (on) "site non prêt" else "arrêté"; if (horsLigneDu) horsLigne(t) }
            s == null && !verrou -> { etat = "en attente du verrouillage DATV"; if (horsLigneDu) horsLigne(t) }
        }
        session?.let { ss ->
            if (t - etatEnvoyeMs >= BATTEMENT_MS) envoieEtat(ss, t)
        }
        if (sousTitres && site.fluxPossible && (session != null || (imageDatv() && verrou))) runCatching { envoieRepliques(t) }
        if (src is SourceTsDemo && session != null) src.repliqueDemo(t)?.let { b -> demoRepliques += b }
    }

    private fun arrete(raison: String) {
        session?.ferme(); session = null
        etat = raison
        horsLigne(horloge())
    }

    /** « hors ligne » said to the site (tried again on the next tics until it is taken). */
    private fun horsLigne(t: Long) {
        horsLigneDu = true
        val r = runCatching { site.envoieDirect("direct-video", JSONObject().put("t", t).put("actif", false)) }.getOrNull()
        if (r == null || r.first in 200..299 || r.first in 400..499) horsLigneDu = false
    }

    private fun demarreSession(src: SourceTs, t: Long) {
        val ff = ffmpeg() ?: run { erreur = "ffmpeg introuvable (" + OutilsPc.conseil("ffmpeg") + ")"; pasAvantMs = t + 60_000L; return }
        val trad = if (audioTraduit != "non" && (paroleDatv().isNotEmpty() || src is SourceTsDemo)) (if (essaiMix) audioTraduit else "seule") else "non"
        codecsSource = null
        val s = Session(ff, src, rendus, segmentS, preset, trad, t)
        if (!envoieEtat(s, t)) { s.ferme(); return }
        try { s.lance() } catch (e: Exception) { erreur = "ffmpeg : " + (e.message ?: ""); s.ferme(); pasAvantMs = t + 30_000L; return }
        session = s; etat = "en cours"; erreur = ""
    }

    /** The state message: the renditions, the sound tracks, the subtitles' tracks. False when the site does not take it. */
    private fun envoieEtat(s: Session, t: Long): Boolean {
        val r = runCatching { site.envoieDirect("direct-video", etatJson(s.rendus, s.trad, t, s.src)) }.getOrNull()
        etatEnvoyeMs = t
        if (r == null) { erreur = "site non réglé"; pasAvantMs = t + 30_000L; return false }
        return when {
            r.first in 200..299 -> true
            r.first == 401 -> { erreur = "jeton refusé par le site"; pasAvantMs = t + 10 * 60_000L; s.ferme(); session = null; false }
            r.first == 403 || r.first == 404 || r.first == 415 -> { erreur = "le site ne prend pas la vidéo en direct (Admin › Paramètres › En direct › Vidéo DATV)"; pasAvantMs = t + 2 * 60_000L; s.ferme(); session = null; false }
            else -> { erreur = "HTTP ${r.first} : " + r.second.take(120); false }
        }
    }

    fun etatJson(rs: List<Rendu>, trad: String, t: Long, src: SourceTs?): JSONObject {
        val kbps = runCatching { src?.kbps() ?: 0 }.getOrDefault(0)
        val o = JSONObject().put("t", t).put("actif", true).put("segmentS", segmentS)
            .put("service", runCatching { src?.service().orEmpty() }.getOrDefault("").replace(Regex("\\p{Cntrl}"), " ").trim().take(40))
            .put("rendus", JSONArray(rs.map { r ->
                val j = JSONObject().put("nom", r.nom).put("libelle", if (r == Rendu.SOURCE) "Source" else r.nom).put("debit", debitBits(r, kbps))
                if (r.hauteur != null) j.put("largeur", (r.hauteur * 16 / 9 + 1) / 2 * 2).put("hauteur", r.hauteur).put("codecs", codecs(r)) else j.put("codecs", codecsSource.orEmpty())
                j
            }))
        val cible = (paroleDatv().ifEmpty { if (src is SourceTsDemo) "en" else "" })
        if (trad != "non") o.put("audio", JSONArray().put(JSONObject().put("nom", "trad").put("langue", cible)
            .put("libelle", (if (trad == "seule") "Voix traduite" else "Traduction") + if (cible.isNotEmpty()) " ($cible)" else "")))
        if (sousTitres) {
            val st = JSONArray().put(JSONObject().put("nom", "orig").put("langue", "").put("libelle", "Original"))
            val tr = traductionDatv().ifEmpty { if (src is SourceTsDemo) "en" else "" }
            if (tr.isNotEmpty()) st.put(JSONObject().put("nom", "trad").put("langue", tr).put("libelle", "Traduction ($tr)"))
            o.put("soustitres", st)
        }
        return o
    }

    // ------------------------------------------------------------------ subtitles

    private val demoRepliques = java.util.concurrent.CopyOnWriteArrayList<BullePhonie>()

    /** The DATV's overs written since the last sent, as cues (`direct-soustitres`). */
    private fun envoieRepliques(t: Long) {
        val src = source
        val demoSrc = src is SourceTsDemo
        if (repliqueVue < 0) { repliqueVue = if (demoSrc) 0L else runCatching { derniereReplique() }.getOrDefault(0L); return }
        val l = (if (demoSrc) demoRepliques.filter { it.id > repliqueVue } else runCatching { repliques(repliqueVue) }.getOrDefault(emptyList()))
            .filter { it.source == "datv" }.takeLast(20)
        if (l.isEmpty()) return
        repliqueVue = l.maxOf { it.id }
        demoRepliques.removeIf { it.id <= repliqueVue }
        val o = JSONObject().put("t", t).put("service", runCatching { src?.service().orEmpty() }.getOrDefault("").take(40))
            .put("repliques", JSONArray(l.mapNotNull { repliqueJson(it) }))
        val r = runCatching { site.envoieDirect("direct-soustitres", o) }.getOrNull() ?: return
        if (r.first in 200..299) repliquesEnvoyees += l.size
    }

    // ------------------------------------------------------------------ the translated voice

    /** A sentence said by « Traduction parlée » for the DATV ([ParolePc]): into the `trad` track while a session has one. */
    fun parle(pcm: ShortArray, rate: Int) { session?.voix(pcm, rate) }

    // ------------------------------------------------------------------ one session: ffmpeg, its feeding, its segments sent

    private inner class Session(val ff: String, val src: SourceTs, val rendus: List<Rendu>, val segment: Int, val preset: String, val trad: String, val debutMs: Long) {
        val dossier: File = File(System.getProperty("java.io.tmpdir"), "satme-hls-" + debutMs + "-" + Integer.toHexString(System.identityHashCode(this)))
        val journal = File(System.getProperty("java.io.tmpdir"), "satme-video-site-ffmpeg.log")
        @Volatile var processus: Process? = null
        @Volatile var finie = false
        @Volatile var cause = ""
        val blocs = ArrayBlockingQueue<ByteArray>(4096)
        val vus = HashMap<String, Int>()
        val premiers = HashSet<String>()
        var tts: ServerSocket? = null
        val voixFile = ArrayDeque<Short>()

        fun noms(): List<String> = rendus.map { it.nom } + if (trad != "non") listOf("trad") else emptyList()

        fun lance() {
            dossier.mkdirs(); noms().forEach { File(dossier, it).mkdirs() }
            val port = if (trad != "non") ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { tts = it }.localPort else null
            val cmd = commande(ff, rendus, segment, preset, dossier, trad, port)
            val p = ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(journal).start()
            processus = p
            src.branche { b -> dernierBlocMs = horloge(); if (!blocs.offer(b)) { blocs.poll(); blocs.offer(b) } }
            Thread({ nourrit(p.outputStream) }, "satme-video-site-ts").apply { isDaemon = true; start() }
            Thread({ surveille() }, "satme-video-site-envoi").apply { isDaemon = true; start() }
            tts?.let { s -> Thread({ voixVersFfmpeg(s, debutMs) }, "satme-video-site-voix").apply { isDaemon = true; start() } }
        }

        private fun nourrit(o: OutputStream) {
            try {
                while (!finie) {
                    val b = blocs.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    o.write(b)
                }
            } catch (e: Exception) { if (!finie) { cause = "ffmpeg s'est arrêté" ; finie = true } }
            runCatching { o.close() }
        }

        /** The playlists ffmpeg writes, read four times a second: each new segment sent once. */
        private fun surveille() {
            while (!finie) {
                for (nom in noms()) {
                    val f = File(dossier, "$nom/index.m3u8")
                    if (!f.isFile) continue
                    val liste = runCatching { lisListe(f.readText()) }.getOrDefault(emptyList())
                    for ((fichier, duree) in liste) {
                        val n = numero(fichier) ?: continue
                        if (n <= (vus[nom] ?: -1)) continue
                        vus[nom] = n
                        val octets = runCatching { File(dossier, "$nom/$fichier").readBytes() }.getOrNull() ?: continue
                        envoie(nom, duree, octets)
                    }
                }
                Thread.sleep(250L)
            }
        }

        private fun envoie(nom: String, duree: Double, octets: ByteArray) {
            val t = horloge()
            val seq = synchronized(seqs) { val s = (seqs[nom] ?: (t / 1000L)) + 1; seqs[nom] = s; s }
            val disc = premiers.add(nom)
            // The copy's codecs known from its first segment: the state sent again with them, before the segment.
            if (nom == Rendu.SOURCE.nom && codecsSource == null) codecsTs(octets)?.let { c -> codecsSource = c; etatEnvoyeMs = 0L; envoieEtat(this, horloge()) }
            val r = runCatching { site.envoieSegment(nom, seq, duree, t - Math.round(duree * 1000), disc, octets) }
                .getOrElse { erreur = "envoi : " + (it.message ?: it.javaClass.simpleName); null } ?: return
            when {
                r.first in 200..299 -> {
                    segmentsEnvoyes++; erreur = ""
                    synchronized(envoisRecents) { envoisRecents.addLast(t to octets.size); while (envoisRecents.isNotEmpty() && t - envoisRecents.first().first > 10_000L) envoisRecents.removeFirst() }
                }
                r.first == 409 -> { etatEnvoyeMs = 0L; premiers.remove(nom) }
                r.first == 401 -> { erreur = "jeton refusé par le site"; cause = erreur; finie = true; pasAvantMs = t + 10 * 60_000L }
                r.first == 403 -> { erreur = "le site ne prend pas la vidéo en direct"; cause = erreur; finie = true; pasAvantMs = t + 2 * 60_000L }
                else -> erreur = "segment refusé : HTTP ${r.first} " + r.second.take(100)
            }
        }

        /** The synthetic voice (24 kHz mono) to ffmpeg, at the pace of the clock: silence between the sentences. */
        private fun voixVersFfmpeg(s: ServerSocket, t0: Long) {
            val c: Socket = runCatching { s.soTimeout = 20_000; s.accept() }.getOrNull() ?: return
            runCatching { s.close() }
            try {
                val o = c.getOutputStream().buffered(16 * 1024)
                var ecrits = 0L
                val b = ByteArray(2 * TTS_RATE / 10)
                while (!finie) {
                    val du = (horloge() - t0) * TTS_RATE / 1000
                    var n = (du - ecrits).coerceIn(0L, TTS_RATE.toLong()).toInt()
                    if (du - ecrits > TTS_RATE) ecrits = du - TTS_RATE
                    while (n > 0) {
                        val k = minOf(n, b.size / 2)
                        synchronized(voixFile) {
                            for (i in 0 until k) { val v = voixFile.removeFirstOrNull()?.toInt() ?: 0; b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
                        }
                        o.write(b, 0, 2 * k); n -= k; ecrits += k
                    }
                    o.flush()
                    Thread.sleep(20L)
                }
            } catch (_: Exception) {}
            runCatching { c.close() }
        }

        fun voix(pcm: ShortArray, rate: Int) {
            if (trad == "non") return
            val r = reechantillonne(pcm, rate, TTS_RATE)
            synchronized(voixFile) { r.forEach { voixFile.addLast(it) }; while (voixFile.size > TTS_RATE * 60) voixFile.removeFirst() }
        }

        fun vivante(t: Long): Boolean {
            val p = processus ?: return false
            if (finie) return false
            if (!p.isAlive) {
                cause = "ffmpeg s'est arrêté : " + runCatching { journal.readLines().takeLast(2).joinToString(" ").take(200) }.getOrDefault("")
                // The mixed voice needs the received sound: without one, the voice alone next time.
                if (trad == "mix" && t - debutMs < 15_000L) essaiMix = false
                pasAvantMs = t + 10_000L
                return false
            }
            // No stream for 5 s: stopped (started again with the next blocks).
            if (t - maxOf(dernierBlocMs, debutMs) > 5_000L) { cause = "plus de flux DATV"; return false }
            return true
        }

        fun raison(): String = cause.ifEmpty { "arrêté" }

        fun ferme() {
            finie = true
            runCatching { src.branche(null) }
            runCatching { tts?.close() }
            processus?.let { p -> runCatching { p.outputStream.close() }; p.destroy(); if (!runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly() }
            Thread({ Thread.sleep(1500L); dossier.deleteRecursively() }, "satme-video-site-menage").apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------------ the page

    /** The upload measured over the last 10 s (kbit/s). */
    fun debitMesureKbps(t: Long = horloge()): Long = synchronized(envoisRecents) { envoisRecents.filter { t - it.first <= 10_000L }.sumOf { it.second.toLong() } * 8 / 10_000 }

    fun json(): JSONObject {
        val kbps = runCatching { source?.kbps() ?: 0 }.getOrDefault(0)
        val trad = audioTraduit != "non"
        return JSONObject().put("on", on).put("rendus", JSONArray(rendus.map { it.nom }))
            .put("possibles", JSONArray(Rendu.values().map { JSONObject().put("nom", it.nom).put("libelle", it.libelle).put("kbps", debitBits(it, kbps) / 1000) }))
            .put("segment", segmentS).put("preset", preset).put("presets", JSONArray(PRESETS)).put("soustitres", sousTitres).put("audio", audioTraduit)
            .put("etat", if (on) etat else "arrêté").put("erreur", if (on) erreur else "").put("segments", segmentsEnvoyes).put("repliques", repliquesEnvoyees)
            .put("debitEstimeKbps", debitEstimeKbps(rendus, kbps, trad)).put("debitMesureKbps", debitMesureKbps())
            .put("reencodages", rendus.count { it != Rendu.SOURCE }).put("parole", paroleDatv().isNotEmpty()).put("traduction", traductionDatv().isNotEmpty())
            .put("demo", demo())
    }

    /** `api/site/direct`: `video=0|1`, `video_rendus=a,b`, `video_segment`, `video_preset`, `video_st=0|1`, `video_audio`. */
    fun regle(p: (String) -> String) {
        val avant = listOf(rendus, segmentS, preset, audioTraduit)
        p("video_rendus").takeIf { it.isNotEmpty() }?.let { v -> rendus = v.split(',').mapNotNull { Rendu.de(it.trim()) } }
        p("video_segment").toIntOrNull()?.let { segmentS = it }
        p("video_preset").takeIf { it.isNotEmpty() }?.let { preset = it }
        p("video_audio").takeIf { it.isNotEmpty() }?.let { audioTraduit = it; essaiMix = true }
        when (p("video_st")) { "1" -> sousTitres = true; "0" -> sousTitres = false }
        when (p("video")) { "1" -> { on = true; pasAvantMs = 0L }; "0" -> on = false }
        // Another set of renditions: a new session at once.
        if (avant != listOf(rendus, segmentS, preset, audioTraduit) && session != null) { session?.ferme(); session = null; pasAvantMs = 0L }
    }

    companion object {
        const val CLE = "site_direct_video"
        val PRESETS = listOf("veryfast", "superfast", "ultrafast")
        val AUDIOS = listOf("non", "mix", "seule")
        const val BATTEMENT_MS = 30_000L
        const val TTS_RATE = 24_000

        /** A rendition's announced rate (bit/s): its video and sound, 10 % more; the copy as the stream comes (1.5 Mb/s unknown). */
        fun debitBits(r: Rendu, sourceKbps: Int): Long = if (r.videoKbps == null) ((if (sourceKbps > 0) sourceKbps else 1500) + r.audioKbps) * 1100L
            else (r.videoKbps + r.audioKbps) * 1100L

        /** The upload all the renditions chosen take (kbit/s), the translated sound's track with them. */
        fun debitEstimeKbps(rs: List<Rendu>, sourceKbps: Int, trad: Boolean): Long = rs.sumOf { debitBits(it, sourceKbps) } / 1000 + if (trad) 106 else 0

        /**
         * The copied rendition's codecs, read from a segment's PMT (the stream types): H.264 → `avc1.640028`, H.265 →
         * `hvc1.1.6.L120.90`, MPEG-2 → `mp4v.61`, with AAC-LC (the sound is always re-encoded); null when not found.
         * A player that sees CODECS on some renditions only may drop the others: the copy must say its own.
         */
        fun codecsTs(ts: ByteArray): String? {
            fun paquets() = (0 until ts.size / 188).asSequence().map { it * 188 }.filter { ts[it] == 0x47.toByte() }
            fun pid(o: Int) = ((ts[o + 1].toInt() and 0x1F) shl 8) or (ts[o + 2].toInt() and 0xFF)
            fun charge(o: Int): Int? {
                if (ts[o + 1].toInt() and 0x40 == 0) return null
                val af = (ts[o + 3].toInt() shr 4) and 3
                var d = o + 4
                if (af == 2 || af == 3) d += 1 + (ts[d].toInt() and 0xFF)
                if (af == 2 || d >= o + 188) return null
                return d + 1 + (ts[d].toInt() and 0xFF)
            }
            var pmt = -1
            for (o in paquets()) if (pid(o) == 0) {
                val d = charge(o) ?: continue
                if (d + 12 > o + 188) continue
                pmt = ((ts[d + 10].toInt() and 0x1F) shl 8) or (ts[d + 11].toInt() and 0xFF); break
            }
            if (pmt < 0) return null
            for (o in paquets()) if (pid(o) == pmt) {
                val d = charge(o) ?: continue
                val longueur = ((ts[d + 1].toInt() and 0x0F) shl 8) or (ts[d + 2].toInt() and 0xFF)
                val fin = minOf(d + 3 + longueur - 4, o + 188)
                var k = d + 12 + (((ts[d + 10].toInt() and 0x0F) shl 8) or (ts[d + 11].toInt() and 0xFF))
                while (k + 5 <= fin) {
                    val video = when (ts[k].toInt() and 0xFF) { 0x1B -> "avc1.640028"; 0x24 -> "hvc1.1.6.L120.90"; 0x02 -> "mp4v.61"; else -> null }
                    if (video != null) return "$video,mp4a.40.2"
                    k += 5 + ((((ts[k + 3].toInt() and 0x0F) shl 8) or (ts[k + 4].toInt() and 0xFF)))
                }
            }
            return null
        }

        /** H.264 Main and AAC-LC, the level by the height. */
        fun codecs(r: Rendu): String = if (r.hauteur == null) "" else (if (r.hauteur >= 720) "avc1.4d4020" else "avc1.4d401f") + ",mp4a.40.2"

        /**
         * ffmpeg's command: the received transport stream on its input (and, for [trad], the synthetic voice as 24 kHz
         * PCM on a local TCP port), one HLS output per rendition in [dossier]/<name>/ (segments of [segmentS] s,
         * a key frame at each), the `trad` sound track beside them.
         */
        fun commande(ff: String, rs: List<Rendu>, segmentS: Int, preset: String, dossier: File, trad: String, portTts: Int?): List<String> {
            val a = arrayListOf(ff, "-hide_banner", "-loglevel", "error",
                "-fflags", "+genpts+discardcorrupt", "-err_detect", "ignore_err", "-thread_queue_size", "4096",
                "-analyzeduration", "2000000", "-probesize", "2000000", "-f", "mpegts", "-i", "pipe:0")
            val avecVoix = trad != "non" && portTts != null
            if (avecVoix) {
                a += listOf("-thread_queue_size", "4096", "-f", "s16le", "-ar", TTS_RATE.toString(), "-ac", "1", "-i", "tcp://127.0.0.1:$portTts")
                a += listOf("-filter_complex", filtreVoix(trad))
            }
            for (r in rs) {
                a += listOf("-map", "0:v:0", "-map", "0:a:0?")
                if (r.videoKbps == null || r.hauteur == null) a += listOf("-c:v", "copy")
                else a += listOf("-vf", "scale=-2:'min(${r.hauteur},ih)',format=yuv420p", "-c:v", "libx264", "-preset", preset, "-tune", "zerolatency",
                    "-profile:v", "main", "-b:v", "${r.videoKbps}k", "-maxrate", "${r.videoKbps * 6 / 5}k", "-bufsize", "${r.videoKbps * 2}k",
                    "-g", (segmentS * 60).toString(), "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*$segmentS)")
                a += listOf("-c:a", "aac", "-b:a", "${r.audioKbps}k", "-ac", "2", "-ar", "48000")
                a += hls(dossier, r.nom, segmentS)
            }
            if (avecVoix) a += listOf("-map", "[trad]", "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-ar", "48000") + hls(dossier, "trad", segmentS)
            return a
        }

        /** The translated track: the voice over the received sound lowered while it speaks (« mix »), or the voice alone. */
        fun filtreVoix(trad: String): String = if (trad == "mix")
            "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asplit=2[v1][v2];" +
                "[0:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[o];" +
                "[o][v1]sidechaincompress=threshold=0.01:ratio=12:attack=10:release=500[b];" +
                "[b][v2]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[trad]"
            else "[1:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[trad]"

        private fun hls(d: File, nom: String, s: Int) = listOf("-f", "hls", "-hls_time", s.toString(), "-hls_list_size", "8",
            "-hls_flags", "delete_segments+independent_segments+omit_endlist+temp_file", "-hls_segment_type", "mpegts", "-start_number", "0",
            "-hls_segment_filename", File(d, "$nom/s%d.ts").path, File(d, "$nom/index.m3u8").path)

        /** A media playlist's segments: file name and duration, in order. */
        fun lisListe(texte: String): List<Pair<String, Double>> {
            val l = ArrayList<Pair<String, Double>>()
            var duree: Double? = null
            for (ligne in texte.lineSequence().map { it.trim() }) {
                when {
                    ligne.startsWith("#EXTINF:") -> duree = ligne.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                    ligne.isEmpty() || ligne.startsWith("#") -> {}
                    else -> { duree?.let { if (Regex("^[A-Za-z0-9_-]{1,40}\\.ts$").matches(ligne)) l += ligne to it }; duree = null }
                }
            }
            return l
        }

        /** The segment's number in ffmpeg's name (`s12.ts` → 12). */
        fun numero(f: String): Int? = Regex("^s(\\d{1,9})\\.ts$").find(f)?.groupValues?.get(1)?.toIntOrNull()

        private fun net(s: String, n: Int): String = s.replace(Regex("[\\p{Cntrl}<>]"), " ").replace(Regex("\\s+"), " ").trim().take(n)
        private fun langue(s: String): String = s.trim().lowercase().takeIf { Regex("^[a-z]{2,3}(-[a-z]{2})?$").matches(it) }.orEmpty()

        /** One over as a cue: plain text (500 characters at most), its languages, its start and end on the wall clock; null when empty. */
        fun repliqueJson(b: BullePhonie): JSONObject? {
            val texte = net(b.texte, 500)
            if (texte.isEmpty()) return null
            val debut = b.t
            val fin = debut + (b.dureeS.coerceIn(0.5, 30.0) * 1000).toLong()
            val o = JSONObject().put("id", b.id).put("debut", debut).put("fin", fin).put("texte", texte).put("langue", langue(b.langue))
            val tr = net(b.traduction, 500)
            if (tr.isNotEmpty()) o.put("traduction", tr).put("langueCible", langue(b.langueCible))
            return o
        }

        /** [pcm] at [de] Hz to [vers] Hz (linear). */
        fun reechantillonne(pcm: ShortArray, de: Int, vers: Int): ShortArray {
            if (de == vers || de <= 0 || pcm.isEmpty()) return pcm
            val n = (pcm.size.toLong() * vers / de).toInt()
            return ShortArray(n) { i ->
                val x = i.toDouble() * de / vers
                val k = x.toInt().coerceAtMost(pcm.size - 1); val f = x - k
                val b = if (k + 1 < pcm.size) pcm[k + 1] else pcm[k]
                (pcm[k] * (1 - f) + b * f).toInt().toShort()
            }
        }
    }
}

/**
 * The demonstration's DATV: a moving test picture with a tone, made by ffmpeg (`testsrc2`, H.264 and MP2 in a
 * transport stream, real time) while « Vidéo DATV en direct » is on; « locked » while it runs. Now and then a test
 * cue (and its translation) and a short « voice » of beeps for the translated track, so the whole chain can be tried
 * without a receiver. Nothing real is opened.
 */
class SourceTsDemo(private val horloge: () -> Long = System::currentTimeMillis, private val ffmpeg: () -> String? = { RenduPc.ffmpeg() }) : VideoSitePc.SourceTs {
    @Volatile var voulue = false
        set(v) { field = v; if (!v) arrete() }
    @Volatile private var p: Process? = null
    @Volatile private var f: ((ByteArray) -> Unit)? = null
    @Volatile private var depuisMs = 0L
    @Volatile private var prochaineMs = 0L
    private var n = 0L
    /** The translated voice's beeps go there ([VideoSitePc.parle]). */
    @Volatile var voix: ((ShortArray, Int) -> Unit)? = null

    override fun branche(f: ((ByteArray) -> Unit)?) { this.f = f }
    override fun verrouille(): Boolean { if (voulue) assure(); return p?.isAlive == true && horloge() - depuisMs > 1500L }
    override fun service(): String = "SatMe démo"
    override fun kbps(): Int = 1700

    @Synchronized private fun assure() {
        if (p?.isAlive == true) return
        val ff = ffmpeg() ?: return
        val q = ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=600:sample_rate=48000:beep_factor=4", "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
            "-g", "25", "-b:v", "1500k", "-c:a", "mp2", "-b:a", "128k", "-f", "mpegts", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p = q; depuisMs = horloge()
        Thread({
            val b = ByteArray(188 * 7 * 8)
            runCatching { while (true) { val k = q.inputStream.read(b); if (k < 0) break; f?.invoke(b.copyOf(k)) } }
        }, "satme-datv-demo").apply { isDaemon = true; start() }
    }

    @Synchronized private fun arrete() { p?.destroy(); p = null }

    /** Every 6 s a test cue (the last 4 s), with its translation and a beeped « voice ». */
    fun repliqueDemo(t: Long): BullePhonie? {
        if (t < prochaineMs) return null
        prochaineMs = t + 6000L
        n++
        voix?.invoke(ShortArray(24_000) { i -> if ((i / 2400) % 2 == 0) (Math.sin(i * 2 * Math.PI * 880 / 24_000) * 9000).toInt().toShort() else 0 }, 24_000)
        return BullePhonie(n, t - 4000L, 4.0, "datv", null, "QO-100", "fr", 0.99, "Essai de sous-titres numéro $n : mire de démonstration de SatMe.",
            "Subtitle test number $n: SatMe's demonstration pattern.", 0.9, 0.5, emptyList(), "en")
    }
}
