/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * « Lecture fluide » of the DATV tab: the received picture and its sound together, played by the browser itself
 * (a `<video>` fed through Media Source Extensions) instead of one JPEG after the other with the sound apart.
 *
 * One ffmpeg for all the pages watching, started when the first one asks and stopped 10 s after the last one
 * left: it reads the same transport stream as the other players (the MiniTiouner's, a SatMe-Node's, the software
 * receiver's; the demonstration's test picture in the demonstration) and writes **fragmented MP4** — the H.264
 * picture copied as it is (no work, no delay), H.265 or MPEG-2 re-encoded in H.264 (`veryfast`, `zerolatency`,
 * a key frame each second, 720 lines at most), the sound in AAC; a fragment every 200 ms at most. The fragments
 * since the last key frame are kept, so that a page joining shows a picture at once.
 *
 * Each page gets the stream over HTTP (`/api/datv/fluide.mp4`, the same guard as every route: this PC, or a
 * session opened with the password over HTTP or HTTPS), the codecs in a header to set up its MediaSource. The
 * stream ends when the coding changes (another station, H.264 → H.265) or ffmpeg stops: the page asks again.
 *
 * « Qualité » (per browser): a page may ask a smaller level ([NiveauDatvPc], `?q=480`): its own ffmpeg then, shared
 * by all the pages asking it, re-encoding in H.264 at that size and rate; two such levels at most at once
 * ([PartageNiveauxDatvPc], with the JPEG stream's), a third asked gets the nearest one running. Source alone
 * re-encodes nothing more than before.
 *
 * The sound: a page on this PC playing it asks the PC's own players (ffplay, « Vumètre et volume ») to be quiet
 * meanwhile ([sonNavigateur]), so it is not heard twice and out of step; its levels, « Écouter ici », the
 * subtitles and the translation keep their own decoders. The JPEG pictures go on as before (the snapshots, the
 * recordings' pictures, the callsigns read in the picture, the publication).
 */
class LecteurFluideDatvPc(
    private val ffmpeg: () -> String? = { RenduPc.ffmpeg() },
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The re-encoded levels' bookkeeping, shared with the JPEG stream's. */
    val partage: PartageNiveauxDatvPc = PartageNiveauxDatvPc(horloge = horloge),
) {
    /** Where the stream comes from (set by Main): the DATV receiver, or the demonstration's test picture. */
    @Volatile var source: VideoSitePc.SourceTs? = null
    /** The demonstration: no receiver, the test picture plays. */
    @Volatile var demo = false
    /** « Décodage tolérant » of the DATV tab, for the re-encoding's decoder. */
    @Volatile var tolerant: () -> Boolean = { false }
    /** Pages play the sound: (one on this PC, any). Said when it changes; « off » after a short grace. */
    @Volatile var sonNavigateur: (Boolean, Boolean) -> Unit = { _, _ -> }

    @Volatile var erreur = ""; private set
    /** The ffmpeg of each level played (Source: the picture copied, or re-encoded when not H.264). */
    private val encodages = java.util.concurrent.ConcurrentHashMap<NiveauDatvPc, Session>()
    private val attente = CopyOnWriteArrayList<Client>()
    @Volatile private var dernierBlocMs = 0L
    @Volatile private var besoinMs = 0L
    @Volatile private var besoinSourceMs = 0L
    @Volatile private var branche = false
    private val recents = ArrayDeque<ByteArray>()
    private var recentsOctets = 0
    private var cheminFfmpeg: String? = null
    @Volatile private var fil: Thread? = null
    @Volatile var sessions = 0L; private set

    // ------------------------------------------------------------------ the stream in

    /** A block of the transport stream (any size, any thread). */
    fun bloc(b: ByteArray) {
        dernierBlocMs = horloge()
        synchronized(recents) {
            recents.addLast(b); recentsOctets += b.size
            while (recentsOctets > RECENTS_MAX && recents.size > 1) recentsOctets -= recents.removeFirst().size
        }
        encodages.values.forEach { it.donne(b) }
    }

    private fun recentsAlignes(): ByteArray = aligne(synchronized(recents) { concat(recents.toList()) })

    // ------------------------------------------------------------------ the loop

    fun demarre() {
        if (fil != null) return
        fil = Thread({ while (true) { runCatching { tic() }.onFailure { erreur = it.message ?: it.javaClass.simpleName }; Thread.sleep(250L) } },
            "satme-datv-fluide").apply { isDaemon = true; start() }
    }

    private var codecVu: String? = null
    private var codecVuMs = 0L
    private var sonLocal = false
    private var sonTous = false
    private var sonOffDepuis = 0L

    /** Four times a second: the source taken or left, each level's ffmpeg started, watched, stopped. */
    internal fun tic() {
        val t = horloge()
        val voulus = (attente.map { it.niveau } + encodages.filterValues { it.clients.isNotEmpty() }.keys).toSet()
        if (voulus.isNotEmpty()) besoinMs = t
        if (NiveauDatvPc.SOURCE in voulus) besoinSourceMs = t
        val besoin = t - besoinMs < GARDE_MS
        val src = source
        if (besoin && !branche && src != null) { branche = true; src.branche { bloc(it) } }
        if (!besoin && branche) { branche = false; src?.branche(null); synchronized(recents) { recents.clear(); recentsOctets = 0 } }
        // The levels nobody watches any more (10 s), Source among them.
        val finis = partage.aArreter(SORTE) + listOfNotNull(NiveauDatvPc.SOURCE.takeIf { t - besoinSourceMs >= GARDE_MS })
        finis.forEach { n -> encodages.remove(n)?.ferme("plus personne") }
        // The demonstration's test picture starts when asked.
        if (besoin) runCatching { src?.verrouille() }
        if (!besoin) { encodages.values.forEach { it.ferme("plus personne") }; encodages.clear(); codecVu = null; majSon(t); return }
        // What the stream carries now (its PMT), each second.
        if (t - codecVuMs >= 1000L) { codecVuMs = t; VideoSitePc.codecsTs(recentsAlignes())?.let { codecVu = it.substringBefore(',') } }
        val flux = t - dernierBlocMs < 2000L
        for ((n, s) in encodages) {
            val pourquoi = when {
                s.finie -> s.cause.ifEmpty { "ffmpeg arrêté" }
                codecVu != null && s.codecTs != null && codecVu != s.codecTs -> "codage changé : " + s.codecTs + " → " + codecVu
                s.init == null && t - s.debutMs > 12_000L -> "ffmpeg ne donne rien"
                s.init != null && flux && t - s.dernierFragmentMs > 6000L -> "ffmpeg figé"
                else -> null
            }
            if (pourquoi != null) { s.ferme(pourquoi); encodages.remove(n); erreur = pourquoi }
        }
        // Started once the stream flows and its coding is known (or not found within 3 s: re-encoded, whatever it is);
        // a smaller level only while it holds its place among the re-encoded ones.
        if (!flux) debutFluxMs = null else if (debutFluxMs == null) debutFluxMs = t
        if (flux && (codecVu != null || t - (debutFluxMs ?: t) >= 3000L))
            for (n in voulus) if (encodages[n] == null && (n == NiveauDatvPc.SOURCE || n in partage.actifs(SORTE))) lance(n, codecVu)
        majSon(t)
    }
    private var debutFluxMs: Long? = null

    private fun lance(n: NiveauDatvPc, codec: String?) {
        val ff = cheminFfmpeg ?: ffmpeg()?.also { cheminFfmpeg = it } ?: run { erreur = "ffmpeg introuvable (" + OutilsPc.conseil("ffmpeg") + ")"; return }
        val copie = n == NiveauDatvPc.SOURCE && copie(codec)
        val s = Session(ff, n, copie, codec)
        try { s.lance(recentsAlignes()) } catch (e: Exception) { erreur = "ffmpeg : " + (e.message ?: ""); s.ferme("ne démarre pas"); return }
        encodages[n] = s; sessions++; erreur = ""
    }

    private fun clients(): List<Client> = encodages.values.flatMap { it.clients } + attente

    /** The sound in the browsers: on at once, off after [GRACE_SON_MS] (a page reconnecting does not wake the PC's own). */
    private fun majSon(t: Long) {
        val tous = clients().filter { it.son && !it.ferme }
        val local = tous.any { it.local }
        val any = tous.isNotEmpty()
        if (local || any) sonOffDepuis = t
        val l2 = local || (sonLocal && t - sonOffDepuis < GRACE_SON_MS)
        val a2 = any || (sonTous && t - sonOffDepuis < GRACE_SON_MS)
        if (l2 != sonLocal || a2 != sonTous) { sonLocal = l2; sonTous = a2; runCatching { sonNavigateur(l2, a2) } }
    }

    // ------------------------------------------------------------------ the pages

    /** A page watching: its fragments queue (a page too slow is let go: it asks again). */
    class Client(val id: String, val local: Boolean, @Volatile var son: Boolean, val niveau: NiveauDatvPc = NiveauDatvPc.SOURCE) {
        val file = ArrayBlockingQueue<ByteArray>(400)
        @Volatile var ferme = false
        fun donne(b: ByteArray) { if (!ferme && !file.offer(b)) ferme = true }
    }

    /**
     * `/api/datv/fluide.mp4`: waits (10 s at most) for ffmpeg's first fragments, then sends the stream until the
     * page goes or the stream ends. 503 with a reason when nothing comes. [voulu]: the page's « Qualité »; the level
     * served (another when two are re-encoded already) said in `X-Satme-Niveau`, and why in `X-Satme-Repli`.
     */
    fun sert(sortie: OutputStream, id: String, local: Boolean, son: Boolean, voulu: NiveauDatvPc = NiveauDatvPc.SOURCE) {
        val n = partage.prend(SORTE, voulu)
        try { sertNiveau(sortie, Client(id.take(40), local, son, n), voulu) } finally { partage.rend(SORTE, n) }
    }

    private fun sertNiveau(sortie: OutputStream, c: Client, voulu: NiveauDatvPc) {
        attente += c
        besoinMs = horloge()
        if (c.niveau == NiveauDatvPc.SOURCE) besoinSourceMs = besoinMs
        val debut = horloge()
        var s: Session? = null
        try {
            while (horloge() - debut < 10_000L) {
                s = encodages[c.niveau]?.takeIf { it.init != null && !it.finie }
                if (s != null) break
                Thread.sleep(50)
            }
        } finally { attente -= c }
        if (s == null || !s.ajoute(c)) {
            val pourquoi = erreur.ifEmpty { if (horloge() - dernierBlocMs > 2000L) "pas de flux DATV" else "ffmpeg ne donne rien" }
            val b = JSONObject().put("ok", false).put("erreur", pourquoi).toString().toByteArray()
            runCatching { sortie.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()); sortie.write(b); sortie.flush() }
            return
        }
        try {
            val repli = partage.raison(voulu, c.niveau)
            sortie.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nX-Satme-Codecs: ${s.codecs}\r\nX-Satme-Mode: ${if (s.copie) "copie" else "h264"}\r\n" +
                "X-Satme-Niveau: ${c.niveau.code}\r\n" + (if (repli.isEmpty()) "" else "X-Satme-Repli: " + java.net.URLEncoder.encode(repli, "UTF-8") + "\r\n") +
                "Cache-Control: no-store\r\nX-Accel-Buffering: no\r\nConnection: close\r\n\r\n").toByteArray())
            sortie.flush()
            while (!c.ferme) {
                val b = c.file.poll(1, TimeUnit.SECONDS)
                if (b == null) { if (s.finie) break; continue }
                sortie.write(b)
                // The rest already there goes in the same write.
                while (true) { val x = c.file.poll() ?: break; sortie.write(x) }
                sortie.flush()
            }
        } catch (_: Exception) {
        } finally {
            c.ferme = true
            s.retire(c)
        }
    }

    /** A page's sound turned on or off (its own volume stays in the page). */
    fun son(id: String, on: Boolean): Boolean {
        val l = clients().filter { it.id == id }
        if (l.isEmpty()) return false
        l.forEach { it.son = on }
        majSon(horloge())
        return true
    }

    fun json(): JSONObject {
        val s = encodages[NiveauDatvPc.SOURCE] ?: encodages.values.firstOrNull()
        val niveaux = org.json.JSONArray()
        encodages.forEach { (n, e) -> niveaux.put(JSONObject().put("niveau", n.code).put("clients", e.clients.size).put("enMarche", e.init != null && !e.finie)) }
        return JSONObject().put("ok", true).put("demo", demo).put("flux", horloge() - dernierBlocMs < 2000L)
            .put("enMarche", s?.init != null && s.finie.not()).put("mode", if (s == null) "" else if (s.copie) "copie" else "h264")
            .put("codecs", s?.codecs.orEmpty()).put("clients", encodages.values.sumOf { it.clients.size }).put("erreur", erreur)
            .put("sonPc", sonLocal).put("sessions", sessions).put("niveaux", niveaux)
    }

    // ------------------------------------------------------------------ one ffmpeg

    private inner class Session(val ff: String, val niveau: NiveauDatvPc, val copie: Boolean, val codecTs: String?) {
        val debutMs = horloge()
        val blocs = ArrayBlockingQueue<ByteArray>(4096)
        val clients = CopyOnWriteArrayList<Client>()
        @Volatile var p: Process? = null
        @Volatile var finie = false
        @Volatile var cause = ""
        @Volatile var init: ByteArray? = null
        @Volatile var codecs = ""
        @Volatile var dernierFragmentMs = horloge()
        private var piste: Int? = null
        private var drapeauxDefaut: Int? = null
        private val gop = ArrayList<ByteArray>()
        private var gopOctets = 0

        fun lance(debut: ByteArray) {
            val q = ProcessBuilder(commande(ff, copie, tolerant(), niveau)).redirectError(JOURNAL).start()
            p = q
            if (debut.isNotEmpty()) blocs.offer(debut)
            Thread({ nourrit(q.outputStream) }, "satme-datv-fluide-ts").apply { isDaemon = true; start() }
            Thread({ lit(q.inputStream) }, "satme-datv-fluide-mp4").apply { isDaemon = true; start() }
        }

        fun donne(b: ByteArray) { if (!blocs.offer(b)) { blocs.poll(); blocs.offer(b) } }

        private fun nourrit(o: OutputStream) {
            try {
                while (!finie) {
                    val b = blocs.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    o.write(b); o.flush()
                }
            } catch (_: Exception) { if (!finie) { cause = "ffmpeg s'est arrêté"; finie = true } }
            runCatching { o.close() }
        }

        private fun lit(e: InputStream) {
            val d = DecoupeMp4()
            val b = ByteArray(65_536)
            try {
                while (!finie) {
                    val n = e.read(b)
                    if (n < 0) break
                    d.ajoute(b, n, { i -> recoitInit(i) }, { f -> recoitFragment(f) })
                }
            } catch (_: Exception) {}
            if (!finie) {
                cause = "ffmpeg s'est arrêté : " + runCatching { JOURNAL.readLines().takeLast(2).joinToString(" ").take(200) }.getOrDefault("")
                finie = true
            }
            clients.forEach { it.ferme = true }
        }

        private fun recoitInit(i: ByteArray) {
            synchronized(this) {
                if (init != null) return
                piste = pisteVideo(i)
                drapeauxDefaut = piste?.let { drapeauxTrex(i, it) }
                codecs = codecsInit(i) ?: "avc1.640028,mp4a.40.2"
                init = i
            }
        }

        private fun recoitFragment(f: ByteArray) {
            dernierFragmentMs = horloge()
            synchronized(this) {
                val cle = piste?.let { estCle(f, it, drapeauxDefaut) } ?: true
                if (cle || gopOctets > GOP_MAX) { gop.clear(); gopOctets = 0 }
                gop += f; gopOctets += f.size
                clients.forEach { it.donne(f) }
            }
        }

        /** A page joins: the init segment and the fragments since the last key frame first. */
        fun ajoute(c: Client): Boolean = synchronized(this) {
            val i = init ?: return false
            if (finie) return false
            c.file.offer(i)
            gop.forEach { c.file.offer(it) }
            clients += c
            true
        }

        fun retire(c: Client) { clients -= c }

        fun ferme(raison: String) {
            if (cause.isEmpty()) cause = raison
            finie = true
            clients.forEach { it.ferme = true }
            p?.let { q -> runCatching { q.outputStream.close() }; q.destroy(); if (runCatching { !q.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(true)) q.destroyForcibly() }
        }
    }

    // ------------------------------------------------------------------ MP4 boxes

    /** ffmpeg's output cut back into the init segment (ftyp + moov) and fragments (moof + mdat, whole). */
    class DecoupeMp4 {
        private val tampon = java.io.ByteArrayOutputStream()
        private var initFait = false
        private val initEnCours = java.io.ByteArrayOutputStream()
        private val fragment = java.io.ByteArrayOutputStream()

        fun ajoute(b: ByteArray, n: Int, init: (ByteArray) -> Unit, frag: (ByteArray) -> Unit) {
            tampon.write(b, 0, n)
            val t = tampon.toByteArray()
            var o = 0
            while (t.size - o >= 8) {
                var taille = u32(t, o)
                var entete = 8
                if (taille == 1L) { if (t.size - o < 16) break; taille = u64(t, o + 8); entete = 16 }
                if (taille < entete || taille > 256L shl 20) { tampon.reset(); return }   // lost: wait for ffmpeg's end
                if (t.size - o < taille) break
                val type = String(t, o + 4, 4, Charsets.ISO_8859_1)
                val boite = t.copyOfRange(o, o + taille.toInt())
                o += taille.toInt()
                when {
                    !initFait && (type == "ftyp" || type == "moov") -> {
                        initEnCours.write(boite)
                        if (type == "moov") { initFait = true; init(initEnCours.toByteArray()); initEnCours.reset() }
                    }
                    type == "moof" -> { fragment.reset(); fragment.write(boite) }
                    type == "mdat" -> { if (fragment.size() > 0) { fragment.write(boite); frag(fragment.toByteArray()); fragment.reset() } }
                    else -> if (initFait) fragment.write(boite)
                }
            }
            tampon.reset()
            if (o < t.size) tampon.write(t, o, t.size - o)
        }
    }

    companion object {
        val JOURNAL = File(System.getProperty("java.io.tmpdir"), "satme-datv-fluide-ffmpeg.log")
        /** The source kept taken this long after the last page left (a page reloading finds it running). */
        const val GARDE_MS = 10_000L
        const val GRACE_SON_MS = 3000L
        private const val RECENTS_MAX = 512 * 1024
        private const val GOP_MAX = 6 shl 20

        /** The sort of [PartageNiveauxDatvPc] for these encoders. */
        const val SORTE = "mp4"

        /** The picture copied as it is when it is H.264 (or unknown before the start: re-encoded then). */
        fun copie(codecTs: String?): Boolean = codecTs?.startsWith("avc1") == true

        /**
         * ffmpeg's command: the transport stream on its input, fragmented MP4 on its output — the H.264 copied
         * ([copie]), else re-encoded in H.264 (deinterlaced when the picture says it is interlaced, 720 lines at
         * most, a key frame each second); the sound in AAC (none when the stream has none). A smaller [niveau]: always
         * re-encoded, at its size, its rate at most, its bit rate and profile.
         */
        fun commande(ff: String, copie: Boolean, tolerant: Boolean, niveau: NiveauDatvPc = NiveauDatvPc.SOURCE): List<String> {
            val a = arrayListOf(ff, "-hide_banner", "-loglevel", "error")
            a += DatvPc.lecteurEntree(tolerant)
            a += listOf("-i", "pipe:0", "-map", "0:v:0", "-map", "0:a:0?")
            val n = niveau
            if (n == NiveauDatvPc.SOURCE && copie) a += listOf("-c:v", "copy")
            else if (n == NiveauDatvPc.SOURCE) a += listOf("-vf", "yadif=deint=interlaced,scale=-2:'min(720,ih)',format=yuv420p", "-c:v", "libx264", "-preset", "veryfast",
                "-tune", "zerolatency", "-profile:v", "main", "-crf", "23", "-maxrate", "4000k", "-bufsize", "4000k",
                "-g", "25", "-keyint_min", "25", "-sc_threshold", "0", "-force_key_frames", "expr:gte(t,n_forced*1)")
            else a += listOf("-vf", "yadif=deint=interlaced,scale=-2:'min(${n.lignes},ih)',format=yuv420p", "-fpsmax", n.ips.toString(),
                "-c:v", "libx264", "-preset", n.preset, "-tune", "zerolatency", "-profile:v", n.profil, "-crf", "23",
                "-maxrate", "${n.kbps}k", "-bufsize", "${n.kbps}k", "-g", n.ips.toString(), "-keyint_min", n.ips.toString(), "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*1)")
            a += listOf("-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000")
            a += listOf("-max_muxing_queue_size", "1024", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov+default_base_moof",
                "-frag_duration", "200000", "-flush_packets", "1", "pipe:1")
            return a
        }

        /** The stream from its first packet start (0x47 every 188 bytes), whole packets only. */
        fun aligne(ts: ByteArray): ByteArray {
            for (o in 0 until minOf(188, ts.size)) {
                if (ts[o] != 0x47.toByte()) continue
                if (o + 188 < ts.size && ts[o + 188] != 0x47.toByte()) continue
                val n = (ts.size - o) / 188 * 188
                return ts.copyOfRange(o, o + n)
            }
            return ByteArray(0)
        }

        private fun concat(l: List<ByteArray>): ByteArray {
            val r = ByteArray(l.sumOf { it.size }); var o = 0
            for (b in l) { System.arraycopy(b, 0, r, o, b.size); o += b.size }
            return r
        }

        internal fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

        /** A box's children: (type, start of its content, end). */
        internal fun enfants(b: ByteArray, debut: Int, fin: Int): List<Triple<String, Int, Int>> {
            val l = ArrayList<Triple<String, Int, Int>>()
            var o = debut
            while (o + 8 <= fin) {
                var t = u32(b, o); var e = 8
                if (t == 1L && o + 16 <= fin) { t = u64(b, o + 8); e = 16 }
                if (t == 0L) t = (fin - o).toLong()
                if (t < e || o + t > fin) break
                l += Triple(String(b, o + 4, 4, Charsets.ISO_8859_1), o + e, (o + t).toInt())
                o += t.toInt()
            }
            return l
        }

        private fun enfant(b: ByteArray, parent: Triple<String, Int, Int>?, type: String) = parent?.let { p -> enfants(b, p.second, p.third).firstOrNull { it.first == type } }

        private fun pistes(init: ByteArray): List<Triple<String, Int, Int>> {
            val moov = enfants(init, 0, init.size).firstOrNull { it.first == "moov" } ?: return emptyList()
            return enfants(init, moov.second, moov.third).filter { it.first == "trak" }
        }

        private fun idPiste(b: ByteArray, trak: Triple<String, Int, Int>): Int? {
            val tkhd = enfant(b, trak, "tkhd") ?: return null
            val v = b[tkhd.second].toInt()
            return u32(b, tkhd.second + if (v == 1) 20 else 12).toInt()
        }

        private fun gestionnaire(b: ByteArray, trak: Triple<String, Int, Int>): String? {
            val hdlr = enfant(b, enfant(b, trak, "mdia"), "hdlr") ?: return null
            return String(b, hdlr.second + 8, 4, Charsets.ISO_8859_1)
        }

        /** The picture's track id in an init segment. */
        fun pisteVideo(init: ByteArray): Int? = pistes(init).firstOrNull { gestionnaire(init, it) == "vide" }?.let { idPiste(init, it) }

        /** The default sample flags of track [piste] (moov › mvex › trex), or null. */
        fun drapeauxTrex(init: ByteArray, piste: Int): Int? {
            val moov = enfants(init, 0, init.size).firstOrNull { it.first == "moov" } ?: return null
            val mvex = enfant(init, moov, "mvex") ?: return null
            return enfants(init, mvex.second, mvex.third).filter { it.first == "trex" }
                .firstOrNull { u32(init, it.second + 4).toInt() == piste }?.let { u32(init, it.second + 20).toInt() }
        }

        /**
         * The codecs for MediaSource from an init segment: `avc1.PPCCLL` from the avcC, `mp4a.40.2` when there is an
         * AAC track; null when there is no H.264 track.
         */
        fun codecsInit(init: ByteArray): String? {
            var video: String? = null
            var audio = false
            for (trak in pistes(init)) {
                val stsd = enfant(init, enfant(init, enfant(init, enfant(init, trak, "mdia"), "minf"), "stbl"), "stsd") ?: continue
                for (e in enfants(init, stsd.second + 8, stsd.third)) {
                    when (e.first) {
                        "avc1", "avc3" -> enfants(init, e.second + 78, e.third).firstOrNull { it.first == "avcC" }?.let { c ->
                            video = e.first + "." + String.format("%02x%02x%02x", init[c.second + 1].toInt() and 0xFF, init[c.second + 2].toInt() and 0xFF, init[c.second + 3].toInt() and 0xFF)
                        }
                        "mp4a" -> audio = true
                    }
                }
            }
            val v = video ?: return null
            return if (audio) "$v,mp4a.40.2" else v
        }

        /**
         * Whether a fragment (moof + mdat) begins with a key frame on track [piste]: the first sample's flags (the
         * trun's first-sample flags, its own, the tfhd's default, else the trex's [defaut]); unknown counts as yes.
         */
        fun estCle(f: ByteArray, piste: Int, defaut: Int?): Boolean {
            val moof = enfants(f, 0, f.size).firstOrNull { it.first == "moof" } ?: return true
            for (traf in enfants(f, moof.second, moof.third).filter { it.first == "traf" }) {
                val tfhd = enfant(f, traf, "tfhd") ?: continue
                if (u32(f, tfhd.second + 4).toInt() != piste) continue
                val fl = (u32(f, tfhd.second) and 0xFFFFFF).toInt()
                var o = tfhd.second + 8
                if (fl and 0x01 != 0) o += 8
                if (fl and 0x02 != 0) o += 4
                if (fl and 0x08 != 0) o += 4
                if (fl and 0x10 != 0) o += 4
                val defTfhd = if (fl and 0x20 != 0) u32(f, o).toInt() else null
                val trun = enfant(f, traf, "trun") ?: return true
                val tf = (u32(f, trun.second) and 0xFFFFFF).toInt()
                var k = trun.second + 8
                if (tf and 0x01 != 0) k += 4
                val drapeaux = when {
                    tf and 0x04 != 0 -> u32(f, k).toInt()
                    tf and 0x400 != 0 -> u32(f, k + (if (tf and 0x100 != 0) 4 else 0) + (if (tf and 0x200 != 0) 4 else 0)).toInt()
                    defTfhd != null -> defTfhd
                    defaut != null -> defaut
                    else -> return true
                }
                // sample_is_non_sync_sample
                return drapeaux and 0x00010000 == 0
            }
            return true
        }
    }
}
