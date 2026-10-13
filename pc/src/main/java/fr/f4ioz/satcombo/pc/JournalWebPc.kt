/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.PagePc

import fr.f4ioz.satcombo.demo.PageJournal
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.location.Maidenhead
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's journal page, served by the PC from its own passes: the same
 * page ([PageJournal]), the same answers as the phone's (`JournalWeb`) —
 * the list, a pass whole, its stations, the accelerated replay, the map
 * tiles, the sound and the pictures. Made here: a moment's sound (WAV);
 * pictures, GIFs and videos are drawn by the phone only for now.
 */
class JournalWebPc(private val j: JournalPc, private val station: StationPc, private val dossier: File) {

    /** The page, opened at once (no code on this PC) and without the phone-only links. */
    fun page(): String = PageJournal.html()
        .replace("<script>", "<script>window.SATME_PC=true;try{localStorage.setItem('satme-cle','pc')}catch(e){}</script>\n<style>${PagePc.STYLE_BARRE}</style>\n<script>")
        // The PC's header above the journal, the same as on its other pages.
        .replace("<div id=\"page\" class=\"cache\">", "<div id=\"page\" class=\"cache\">" + PagePc.barre("journal") + "<script>${PagePc.SCRIPT_BARRE}</script>")
        // Its words are the phone's: said here of the PC.
        .let { h -> TEXTES_PC.entries.fold(h) { acc, (cle, pc) -> acc.replace(js(fr.f4ioz.satcombo.i18n.t(cle)), js(fr.f4ioz.satcombo.i18n.t(pc))) } }

    /** The page's words that speak of the phone, and the PC's in their place. */
    private val TEXTES_PC = mapOf("pj_sous" to "pc_pj_sous", "pj_aucun" to "pc_pj_aucun", "pw_pupitre" to "pc_pj_station",
        "pj_echec" to "pc_pj_echec")

    private fun js(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch == '<' -> append("\\u003c"); ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    private fun json(s: String) = ReponseWeb("200 OK", "application/json; charset=utf-8", s.toByteArray())
    private val NON = ReponseWeb("404 Not Found", "application/json; charset=utf-8", "{\"ok\":false}".toByteArray())
    private fun d(v: Double) = "%.2f".format(Locale.US, v)

    private class Travail(@Volatile var progres: Float = 0f, @Volatile var fichier: File? = null, @Volatile var fini: Boolean = false)
    private val travaux = ConcurrentHashMap<String, Travail>()

    private fun opt(p: (String) -> String, nom: String, defaut: Boolean) = when (p(nom)) { "1" -> true; "0" -> false; else -> defaut }

    // The phone's journal settings, read the same way.
    private val s get() = station.settings
    private fun flashS() = s.journalFlashS.let { if (it <= 0) 3 else it }
    private fun affSstv() = s.journalFlashS > 0 && s.journalAffSstv

    private fun segments(e: JournalPassage.Entree, marques: List<JournalPassage.Marque>, p: (String) -> String): List<JournalPassage.Segment> {
        val avant = (p("avant").toIntOrNull() ?: s.journalAvantS).coerceIn(0, 120)
        val apres = (p("apres").toIntOrNull() ?: s.journalApresQsoS).coerceIn(0, 300)
        val rapide = (p("rapide").toIntOrNull() ?: s.journalRapide).coerceIn(2, 100)
        val activite = if (opt(p, "surActivite", s.journalActivite)) j.activite(e) else emptyList()
        return JournalPassage.segments(e.debutMs, e.finMs,
            JournalPassage.plagesNormales(marques, e.debutMs, e.finMs, avant * 1000L, apres * 1000L, activite,
                apresSstvMs = if (opt(p, "sstv", affSstv())) flashS() * 1000L else 0L,
                emissions = e.emissions, avantTxMs = s.journalAvantTxS * 1000L, apresTxMs = s.journalApresTxS * 1000L), rapide)
    }

    /** The RX frequency at rest, moment by moment (CAT readings, Doppler taken off); else each contact's. */
    private fun frequences(e: JournalPassage.Entree, l: JournalPc.Liens): List<Pair<Long, Long>> {
        val sat = station.etat.value.satellites.firstOrNull { it.catalogNumber == e.catnum }
        val obs = Maidenhead.centre(e.locator)?.let { fr.f4ioz.satcombo.data.Observer(it.first, it.second) } ?: station.etat.value.observateur
        val lus = e.points.filter { it.dlHz != null }
        val parCat = if (sat != null && obs != null && lus.isNotEmpty()) runCatching {
            val rr = fr.f4ioz.satcombo.domain.PassPredictor().rangeRates(sat, obs, lus.map { it.tMs })
            lus.mapIndexed { i, pt -> pt.tMs to Doppler.restFromDownlink(pt.dlHz!!, rr[i]) }
        }.getOrDefault(emptyList()) else emptyList()
        return parCat.ifEmpty { l.qsos.filter { it.downlinkMhz > 0 }.map { it.timeMs to Math.round(it.downlinkMhz * 1e6) } }
    }

    /**
     * One request of the journal page. [route] is what follows `/journal`;
     * [p] reads a parameter; [corps] writes the request's body (a sound, a
     * picture) to a file, false when there is none or it is too large.
     */
    fun sert(route: String, p: (String) -> String): ReponseWeb = sert(route, p) { false }

    fun sert(route: String, p: (String) -> String, corps: (File) -> Boolean): ReponseWeb = runCatching {
        when (route) {
            // ---- a pass from nothing: its satellite and times, then its sound, pictures and contacts
            "/nouveau/satellites" -> json(org.json.JSONArray(station.etat.value.satellites.sortedBy { it.name.uppercase() }
                .map { org.json.JSONObject().put("nom", it.name).put("catnum", it.catalogNumber) }).toString())
            "/nouveau/passages" -> json(fr.f4ioz.satcombo.demo.ApiCalculs.passagesFinisJson(j.passagesPasses(p("catnum").toIntOrNull() ?: 0)).toString())
            "/nouveau/cree" -> {
                val e = j.cree(p("catnum").toIntOrNull() ?: 0, p("aos").toLongOrNull() ?: 0L, p("los").toLongOrNull() ?: 0L)
                    ?: return json("{\"ok\":false,\"erreur\":\"${js(t("pj_n_refus"))}\"}")
                json("{\"ok\":true,\"id\":\"${js(e.id)}\"}")
            }
            "/nouveau/son", "/nouveau/image" -> {
                val e = j.passage(p("id")) ?: return NON
                val ext = if (route == "/nouveau/son") fr.f4ioz.satcombo.domain.NouveauPassage.extensionSon(p("nom"))
                    ?: return json("{\"ok\":false,\"erreur\":\"WAV ou MP3\"}") else "img"
                val tmp = File.createTempFile("satme-import", ".$ext", File(dossier, "tmp").apply { mkdirs() })
                try {
                    if (!corps(tmp)) return json("{\"ok\":false,\"erreur\":\"${js(t("pj_n_trop_gros"))}\"}")
                    val t0 = p("t").toLongOrNull() ?: e.debutMs
                    val err = if (route == "/nouveau/son") j.ajouteSon(e, t0, tmp, ext) else j.ajouteImage(e, t0, p("mode").trim(), tmp)
                    json(if (err == null) "{\"ok\":true}" else "{\"ok\":false,\"erreur\":\"${js(err)}\"}")
                } finally { tmp.delete() }
            }
            "/nouveau/contact" -> {
                val e = j.passage(p("id")) ?: return NON
                val t0 = p("t").toLongOrNull() ?: ((e.debutMs + e.finMs) / 2)
                // Who it is: QRZ as for any contact (name, town, e-mail where the log knows nothing).
                val fiche = p("c").takeIf { it.isNotBlank() }?.let { kotlinx.coroutines.runBlocking { j.ficheQrz(it) } }?.takeIf { it.erreur.isBlank() && !it.vide }
                val c = j.ajouteContact(e, t0, p("c"), p("l"), p("rs"), p("rr"), p("n"), p("md"), p("nm"), p("qth"), fiche)
                val pourquoi = if (p("c").isBlank()) t("pj_n_indicatif") else if (station.etat.value.satellites.none { it.catalogNumber == e.catnum }) t("pj_n_sat_inconnu") else t("pj_n_indicatif")
                json(if (c != null) "{\"ok\":true}" else "{\"ok\":false,\"erreur\":\"${js(pourquoi)}\"}")
            }
            "/liste" -> {
                val l = j.passages().sortedByDescending { it.debutMs }
                val tous = j.liens(l)
                json("{\"utc\":${s.useUtc},\"indicatif\":\"${js(s.callsign)}\",\"passages\":" + l.joinToString(",", "[", "]") { e ->
                    val k = tous.getValue(e.id)
                    "{\"id\":\"${js(e.id)}\",\"sat\":\"${js(e.satName)}\",\"de\":${e.debutMs},\"a\":${e.finMs}," +
                        "\"el\":${Math.round(e.elMax)},\"son\":${e.enregistrements.isNotEmpty()}," +
                        "\"q\":${k.qsos.size},\"i\":${k.images.size},\"r\":${k.trames.size},\"s\":0,\"cat\":${e.avecCat},\"sm\":${e.signal.isNotEmpty()}," +
                        "\"rec\":${e.reconstitue},\"auto\":\"${js(e.auto)}\"}"
                } + "}")
            }
            "/passage" -> {
                val e = j.passage(p("id")) ?: return NON
                val l = j.liens(e)
                val marques = j.marques(l)
                json(buildString {
                    append("{\"id\":\"${js(e.id)}\",\"sat\":\"${js(e.satName)}\",\"de\":${e.debutMs},\"a\":${e.finMs}")
                    append(",\"loc\":\"${js(e.locator)}\",\"tp\":\"${js(e.transpondeur)}\",\"el\":${Math.round(e.elMax)}")
                    append(",\"utc\":${s.useUtc},\"indicatif\":\"${js(s.callsign)}\",\"avant\":${s.journalAvantS}")
                    append(",\"pts\":").append(e.points.joinToString(",", "[", "]") { "[${it.tMs},${d(it.az)},${d(it.el)}" + (it.ulHz?.let { u -> ",$u" } ?: "") + "]" })
                    append(",\"prevue\":").append(j.prevue(e).joinToString(",", "[", "]") { "[${d(it.first)},${d(it.second)}]" })
                    append(",\"marques\":").append(marques.joinToString(",", "[", "]") { m ->
                        "{\"k\":\"${m.type}\",\"de\":${m.debutMs},\"a\":${m.finMs},\"t\":\"${js(m.texte)}\",\"d\":\"${js(m.details)}\"," +
                            "\"iss\":${m.viaIss},\"f\":\"${js(m.fichier?.let { File(it).name } ?: "")}\"" +
                            (if (m.lat != null && m.lon != null) ",\"lat\":${m.lat},\"lon\":${m.lon}" else "") + "}"
                    })
                    append(",\"sol\":").append(j.traceSol(e).joinToString(",", "[", "]") {
                        "[${it.tMs},${"%.3f".format(Locale.US, it.lat)},${"%.3f".format(Locale.US, it.lon)},${Math.round(it.altKm)}]" })
                    append(",\"solp\":").append(j.traceSolPrevue(e).joinToString(",", "[", "]") {
                        "[${it.tMs},${"%.3f".format(Locale.US, it.lat)},${"%.3f".format(Locale.US, it.lon)},${Math.round(it.altKm)}]" })
                    Maidenhead.centre(e.locator)?.let { (la, lo) -> append(",\"qth\":[$la,$lo]") }
                    append(",\"sons\":").append(j.morceaux(e).withIndex().joinToString(",", "[", "]") { (i, m) ->
                        "{\"n\":$i,\"nom\":\"${js(m.f.name)}\",\"o\":${m.origine},\"an\":${m.annonce},\"du\":${m.dureeMs}}" })
                    append(",\"sig\":").append(e.signal.joinToString(",", "[", "]") { "[${it.tMs},${it.s}]" })
                    append(",\"freq\":").append(frequences(e, l).joinToString(",", "[", "]") { "[${it.first},${it.second}]" })
                    append("}")
                })
            }
            "/fiches" -> {
                val e = j.passage(p("id")) ?: return NON
                json(j.fiches(j.liens(e)).entries.joinToString(",", "{", "}") { (k, v) ->
                    "\"${js(k)}\":{\"nom\":\"${js(v.nom)}\",\"qth\":\"${js(v.qth)}\",\"pays\":\"${js(v.pays)}\",\"loc\":\"${js(v.locator)}\"}" })
            }
            "/segments" -> {
                val e = j.passage(p("id")) ?: return NON
                json(segments(e, j.marques(j.liens(e)), p).joinToString(",", "[", "]") { "[${it.deMs},${it.aMs},${it.vitesse}]" })
            }
            "/reglages" -> json("{\"avant\":${s.journalAvantS},\"apres\":${s.journalApresQsoS},\"rapide\":${s.journalRapide}," +
                "\"accelere\":${s.journalAccelere},\"surActivite\":${s.journalActivite},\"sstv\":${affSstv()}," +
                "\"fiches\":${s.journalAffFiches},\"smetre\":${s.journalAffSmetre},\"freq\":${s.journalAffFreq}," +
                "\"locator\":${s.journalAffLocator},\"tx\":${s.journalAffTx},\"res\":\"${js(s.journalVideoRes)}\"," +
                "\"ouverture\":${s.journalOuverture},\"recap\":${s.journalRecap}}")
            "/tuile" -> {
                val z = p("z").toIntOrNull() ?: return NON; val x = p("x").toIntOrNull() ?: return NON; val y = p("y").toIntOrNull() ?: return NON
                val f = tuile(z, x, y) ?: return NON
                ReponseWeb("200 OK", "image/png", fichier = f, entetes = "Cache-Control: max-age=604800\r\n")
            }
            "/activite" -> {
                val e = j.passage(p("id")) ?: return NON
                json(j.activite(e).joinToString(",", "[", "]") { "[${it.first},${it.last}]" })
            }
            "/son" -> {
                val e = j.passage(p("id")) ?: return NON
                val m = j.morceaux(e).getOrNull(p("n").toIntOrNull() ?: -1) ?: return NON
                ReponseWeb("200 OK", "audio/wav", fichier = m.f)
            }
            "/image" -> {
                val f = File(dossier, "sstv").let { d -> File(d, File(p("f")).name) }.takeIf { it.isFile && it.name.endsWith(".png") } ?: return NON
                ReponseWeb("200 OK", "image/png", fichier = f)
            }
            "/fabrique" -> {
                val e = j.passage(p("id")) ?: return NON
                val quoi = p("quoi").takeIf { it in setOf("son", "image", "gif", "video") }
                    ?: return json("{\"ok\":false,\"raison\":\"telephone\"}")
                if (quoi == "video" && RenduPc.ffmpeg() == null) return json("{\"ok\":false,\"raison\":\"ffmpeg\"}")
                val de = p("de").toLongOrNull(); val a = p("a").toLongOrNull()
                val moment = if (de != null && a != null && a > de) de..a else null
                val r = moment ?: (e.debutMs..e.finMs)
                val n = "t" + System.currentTimeMillis().toString(36) + (100..999).random()
                val t = Travail(); travaux[n] = t
                if (travaux.size > 20) travaux.keys.sorted().take(travaux.size - 20).forEach { travaux.remove(it) }
                kotlin.concurrent.thread(name = "JournalWebPc", isDaemon = true) {
                    val sortie = File(dossier, "exports").apply { mkdirs() }
                    val ext = when (quoi) { "son" -> ".wav"; "image" -> ".png"; "gif" -> ".gif"; else -> ".mp4" }
                    val nom = "SatMe_" + e.satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(r.first)) + "Z_" + (p("titre").take(30).replace(Regex("[^A-Za-z0-9-]"), "-").ifBlank { if (moment != null) "moment" else "passage" }) + ext
                    t.fichier = runCatching {
                        when (quoi) {
                            "son" -> j.extraitWav(e, r.first, r.last, sortie, nom)
                            "image" -> RenduPc.png(scene(e, null, null, true, p), p("t").toLongOrNull(), File(sortie, nom))
                            "gif" -> RenduPc.gif(scene(e, moment, p("titre"), false, p), File(sortie, nom)) { t.progres = it }
                            else -> video(e, scene(e, moment, p("titre"), false, p), opt(p, "avecSon", true), File(sortie, nom), sortie) { t.progres = it }
                        }
                    }.onFailure { System.err.println("journal : $quoi de ${e.id} : $it"); it.printStackTrace() }.getOrNull()
                    t.progres = 1f; t.fini = true
                }
                json("{\"ok\":true,\"travail\":\"$n\"}")
            }
            "/travail" -> {
                val t = travaux[p("n")] ?: return NON
                json("{\"fini\":${t.fini},\"progres\":${t.progres},\"ok\":${t.fichier != null},\"nom\":\"${js(t.fichier?.name ?: "")}\",\"taille\":${t.fichier?.length() ?: 0}}")
            }
            "/fichier" -> {
                val f = travaux[p("n")]?.fichier ?: return NON
                val type = when (f.extension) { "png" -> "image/png"; "gif" -> "image/gif"; "mp4" -> "video/mp4"; else -> "audio/wav" }
                ReponseWeb("200 OK", type, fichier = f,
                    entetes = if (p("voir") == "1") "" else "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            }
            else -> NON
        }
    }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }

    /** What a picture, a GIF or a video shows, from the page's options (else the settings). */
    private fun scene(e: JournalPassage.Entree, moment: LongRange?, titre: String?, image: Boolean, p: (String) -> String): RenduPc.Scene {
        val l = j.liens(e)
        val marques = j.marques(l)
        return RenduPc.Scene(e, j.prevue(e), marques, s.callsign, flashS(),
            affSstv = opt(p, "sstv", affSstv()), affFiches = opt(p, "fiches", s.journalAffFiches),
            affSmetre = opt(p, "smetre", s.journalAffSmetre), affFreq = opt(p, "freq", s.journalAffFreq), affTx = opt(p, "tx", s.journalAffTx),
            ouverture = opt(p, "ouverture", s.journalOuverture) && !image, recap = opt(p, "recap", s.journalRecap) && moment == null && !image,
            fiches = j.fiches(l),
            segments = moment?.let { listOf(JournalPassage.Segment(it.first, it.last, 1)) }
                ?: if (!image && opt(p, "accelere", false)) segments(e, marques, p) else null,
            titreExtrait = if (moment != null) titre?.trim()?.ifBlank { null } else null,
            frequences = frequences(e, l), resolution = p("res").takeIf { it in setOf("XS", "M", "HD") } ?: s.journalVideoRes,
            surCarte = p("carte") == "1", sol = j.traceSol(e), qth = Maidenhead.centre(e.locator),
            etiquetteQth = if (opt(p, "locator", s.journalAffLocator)) listOf(s.callsign, e.locator).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } else null,
            vueVue = run {
                val cx = p("mcx").toDoubleOrNull(); val cy = p("mcy").toDoubleOrNull(); val z = p("zc").toDoubleOrNull(); val cw = p("cw").toFloatOrNull()
                if (cx != null && cy != null && z != null && cw != null && cw > 0f) JournalPassage.VueCarte(cx, cy, z) to cw else null
            },
            paysage = p("format") == "paysage", cielEntier = p("format").isNotEmpty(),
            tuile = { z, x, y -> tuile(z, x, y)?.let { f -> runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() } })
    }

    /** The video, with the pass's sound when asked and recorded (the opening title's seconds silent). */
    private fun video(e: JournalPassage.Entree, sc: RenduPc.Scene, avecSon: Boolean, sortie: File, travail: File, progres: (Float) -> Unit): File? {
        val morceaux = if (avecSon) j.morceaux(e) else emptyList()
        val segs = sc.segments
        val deSon = minOf(e.debutMs, segs?.firstOrNull()?.deMs ?: e.debutMs)
        val aSon = maxOf(e.finMs, segs?.lastOrNull()?.aMs ?: e.finMs)
        val son = if (morceaux.isNotEmpty()) fr.f4ioz.satcombo.domain.SonPassage.fabrique(morceaux, deSon, aSon, travail, JournalPc.decodeur) else null
        return try {
            val titre = if (sc.ouverture && son != null) 3 * son.rate else 0
            RenduPc.video(sc, sortie, son?.source(deSon, segs, titre), son?.rate ?: 22_050, travail, progres)
        } finally { son?.close() }
    }

    private val client by lazy { okhttp3.OkHttpClient() }

    /** An OpenStreetMap tile, kept a month on disk, fetched with SatMe's user agent. */
    fun tuile(z: Int, x: Int, y: Int): File? {
        if (z !in 0..16) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val xw = ((x % n) + n) % n
        val f = File(dossier, "tuiles/$z/$xw/$y.png")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) return f
        return runCatching {
            val req = okhttp3.Request.Builder().url("https://tile.openstreetmap.org/$z/$xw/$y.png")
                .header("User-Agent", fr.f4ioz.satcombo.data.AgentHttp.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val b = r.body?.bytes() ?: return@use null
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part"); tmp.writeBytes(b); tmp.renameTo(f)
                f
            }
        }.getOrNull() ?: f.takeIf { it.isFile }
    }
}
