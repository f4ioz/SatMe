/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import android.content.Context
import fr.f4ioz.satcombo.JournalDesPassages
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.sstv.SstvHub
import fr.f4ioz.satcombo.ui.JournalRendu
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The pass journal on the PC: the routes behind `/c/<token>/journal/…`, under
 * the control desk's code.
 *
 * **The phone keeps everything and makes everything.** The PC replays (the
 * passes, their marks and their sound come from [JournalDesPassages]); what
 * it downloads — a sound, a picture, a video, a GIF, the pass file — is made
 * by the phone, with the very code of its own Share tab ([JournalRendu],
 * [JournalPaquet]).
 *
 * **Read only, apart from what it makes**: nothing in the journal, the log or
 * the gallery is changed or deleted from the PC.
 */
object JournalWeb {

    /** Set by the ViewModel. */
    @Volatile var app: Context? = null
    @Volatile var journal: JournalDesPassages? = null

    private fun json(s: String) = ReponseWeb("200 OK", "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))
    private val NON = ReponseWeb("404 Not Found", "text/plain", "non".toByteArray())

    private fun js(t: String): String = buildString {
        for (ch in t) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    private fun passage(j: JournalDesPassages, id: String): JournalPassage.Entree? =
        j.passages().firstOrNull { it.id == id }

    /**
     * The marks as the pass's card shows them by default: the pictures received live
     * when there are any, those set aside left out.
     */
    private fun liens(j: JournalDesPassages, e: JournalPassage.Entree): JournalDesPassages.Liens {
        val l = j.liens(listOf(e))[e.id] ?: JournalDesPassages.Liens()
        val direct = l.images.any { it.second.source == "live" }
        return l.copy(images = l.images.filter { (f, s) -> (!direct || s.source == "live") && f.name !in e.masquees })
    }

    /** The RX frequency at rest, moment by moment; else each contact's own. */
    private fun frequences(j: JournalDesPassages, e: JournalPassage.Entree, l: JournalDesPassages.Liens) =
        j.frequencesRepos(e).ifEmpty {
            l.qsos.filter { it.downlinkMhz > 0 }.map { it.timeMs to Math.round(it.downlinkMhz * 1e6) }.sortedBy { it.first }
        }

    // ------------------------------------------------------------ making

    /** Something being made on the phone (a video takes its time): its progress, then its file. */
    private class Travail(@Volatile var progres: Float = 0f, @Volatile var fichier: File? = null,
                          @Volatile var fini: Boolean = false)
    private val travaux = ConcurrentHashMap<String, Travail>()

    /** An option of the page ("1" / "0"), else the phone's own setting. */
    private fun opt(p: (String) -> String, nom: String, defaut: Boolean) = when (p(nom)) { "1" -> true; "0" -> false; else -> defaut }

    /** The accelerated replay's stretches, from the page's options (else the phone's settings). */
    private fun segments(j: JournalDesPassages, e: JournalPassage.Entree, marques: List<JournalPassage.Marque>,
                         p: (String) -> String): List<JournalPassage.Segment> {
        val avant = (p("avant").toIntOrNull() ?: j.avantS()).coerceIn(0, 120)
        val apres = (p("apres").toIntOrNull() ?: j.apresQsoS()).coerceIn(0, 300)
        val rapide = (p("rapide").toIntOrNull() ?: j.rapide()).coerceIn(2, 100)
        val activite = if (opt(p, "surActivite", j.surActivite())) j.morceaux(e).flatMap { j.activite(it.f) } else emptyList()
        return JournalPassage.segments(e.debutMs, e.finMs,
            JournalPassage.plagesNormales(marques, e.debutMs, e.finMs, avant * 1000L, apres * 1000L, activite), rapide)
    }

    private fun scene(ctx: Context, j: JournalDesPassages, e: JournalPassage.Entree, surCarte: Boolean,
                      moment: LongRange?, titre: String?, image: Boolean, p: (String) -> String = { "" }): JournalRendu.Scene {
        val reglages = SettingsStore(ctx)
        val l = liens(j, e)
        val marques = j.marques(l)
        val fiches = kotlinx.coroutines.runBlocking { runCatching { j.fiches(marques) }.getOrDefault(emptyMap()) }
        val qth = fr.f4ioz.satcombo.location.Maidenhead.centre(e.locator)
        val etiquette = if (opt(p, "locator", j.affLocator())) listOf(reglages.callsign, e.locator).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } else null
        return JournalRendu.Scene(e, j.prevue(e), marques, j.traceSol(e), qth, surCarte, reglages.callsign, reglages.useUtc,
            j.flashS(), j.flashTaille(), affSstv = opt(p, "sstv", j.affSstv()), affFiches = opt(p, "fiches", j.affFiches()),
            fiches = fiches, recap = opt(p, "recap", j.recap()) && moment == null && !image,
            ouverture = opt(p, "ouverture", j.ouverture()) && !image,
            resolution = p("res").takeIf { it in setOf("XS", "M", "HD") } ?: j.videoRes(),
            segments = moment?.let { listOf(JournalPassage.Segment(it.first, it.last, 1)) }
                ?: if (!image && opt(p, "accelere", false)) segments(j, e, marques, p) else null,
            nomFichier = moment?.let { JournalRendu.nomExtrait(e, titre?.substringAfter(' ')?.ifBlank { null } ?: "moment", it.first) },
            titreExtrait = if (moment != null) titre?.trim()?.ifBlank { null } else null,
            affSmetre = opt(p, "smetre", j.affSmetre()), affFreq = opt(p, "freq", j.affFreq()), etiquetteQth = etiquette,
            frequences = frequences(j, e, l))
    }

    private fun fabrique(ctx: Context, j: JournalDesPassages, e: JournalPassage.Entree, quoi: String,
                         moment: LongRange?, titre: String?, surCarte: Boolean, instant: Long?, travail: Travail,
                         p: (String) -> String) {
        val morceaux = j.morceaux(e)
        travail.fichier = runCatching {
            when (quoi) {
                "son" -> {
                    val r = moment ?: (e.debutMs..e.finMs)
                    val nom = JournalRendu.nomExtrait(e, titre?.substringAfter(' ')?.ifBlank { null } ?: (if (moment != null) "moment" else "passage"), r.first)
                    JournalRendu.extraitWav(ctx, morceaux, r.first, r.last, nom)
                }
                "image" -> JournalRendu.png(ctx, scene(ctx, j, e, surCarte, null, null, true, p), instant)
                "gif" -> JournalRendu.gif(ctx, scene(ctx, j, e, surCarte, moment, titre, false, p)) { travail.progres = it }
                "paquet" -> j.paquet(e)
                else -> JournalRendu.video(ctx, scene(ctx, j, e, surCarte, moment, titre, false, p),
                    if (opt(p, "avecSon", true)) morceaux else emptyList()) { travail.progres = it }
            }
        }.getOrNull()
        travail.progres = 1f; travail.fini = true
    }

    private fun typeDe(f: File) = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "png" -> "image/png"; "gif" -> "image/gif"
        "zip" -> JournalPaquet.TYPE; "mp4" -> "video/mp4"; else -> "application/octet-stream"
    }

    // ------------------------------------------------------------ map tiles

    private val client by lazy { okhttp3.OkHttpClient() }

    /**
     * An OpenStreetMap tile, from the phone's cache (kept a month), else fetched as the
     * phone's own map does, with its User-Agent. Zoom 0 to 16 only: enough for a pass.
     */
    private fun tuile(ctx: Context, z: Int, x: Int, y: Int): File? {
        if (z !in 0..16) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val xw = ((x % n) + n) % n
        val f = File(ctx.cacheDir, "tuiles/$z/$xw/$y.png")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) return f
        return runCatching {
            val req = okhttp3.Request.Builder().url("https://tile.openstreetmap.org/$z/$xw/$y.png")
                .header("User-Agent", fr.f4ioz.satcombo.data.TleRepository.USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val b = r.body?.bytes() ?: return@use null
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part"); tmp.writeBytes(b); tmp.renameTo(f)
                f
            }
        }.getOrNull() ?: f.takeIf { it.isFile }
    }

    // ------------------------------------------------------------ routes

    /** One request of the journal page. [route] is what follows `/journal`; [p] reads a parameter. */
    fun sert(route: String, p: (String) -> String): ReponseWeb {
        val ctx = app ?: return json("{\"ok\":false,\"raison\":\"pasPret\"}")
        val j = journal ?: return json("{\"ok\":false,\"raison\":\"pasPret\"}")
        val chemin = route.substringBefore('?')
        return runCatching {
            when (chemin) {
                // The passes kept, newest first, with what each holds.
                "/liste" -> {
                    val l = j.passages().sortedByDescending { it.debutMs }
                    val liens = j.liens(l)
                    val reglages = SettingsStore(ctx)
                    json("{\"utc\":${reglages.useUtc},\"indicatif\":\"${js(reglages.callsign)}\",\"passages\":" +
                        l.joinToString(",", "[", "]") { e ->
                            val k = liens[e.id]
                            "{\"id\":\"${js(e.id)}\",\"sat\":\"${js(e.satName)}\",\"de\":${e.debutMs},\"a\":${e.finMs}," +
                                "\"el\":${Math.round(e.elMax)},\"son\":${e.enregistrements.isNotEmpty()}," +
                                "\"q\":${k?.qsos?.size ?: 0},\"i\":${k?.images?.size ?: 0},\"r\":${k?.trames?.size ?: 0}," +
                                "\"s\":${k?.signets?.size ?: 0},\"cat\":${e.avecCat},\"sm\":${e.signal.isNotEmpty()}," +
                                "\"rec\":${e.reconstitue},\"auto\":\"${js(e.auto)}\"}"
                        } + "}")
                }

                // A pass whole: its trajectory, its marks, its sound files, its S-meter and RX frequency.
                "/passage" -> {
                    val e = passage(j, p("id")) ?: return NON
                    val l = liens(j, e)
                    val marques = j.marques(l)
                    val morceaux = j.morceaux(e)
                    val reglages = SettingsStore(ctx)
                    json(buildString {
                        append("{\"id\":\"${js(e.id)}\",\"sat\":\"${js(e.satName)}\",\"de\":${e.debutMs},\"a\":${e.finMs}")
                        append(",\"loc\":\"${js(e.locator)}\",\"tp\":\"${js(e.transpondeur)}\",\"el\":${Math.round(e.elMax)}")
                        append(",\"utc\":${reglages.useUtc},\"indicatif\":\"${js(reglages.callsign)}\",\"avant\":${j.avantS()}")
                        append(",\"pts\":").append(e.points.joinToString(",", "[", "]") { "[${it.tMs},${"%.2f".format(java.util.Locale.US, it.az)},${"%.2f".format(java.util.Locale.US, it.el)}" + (it.ulHz?.let { u -> ",$u" } ?: "") + "]" })
                        append(",\"prevue\":").append(j.prevue(e).joinToString(",", "[", "]") { "[${"%.2f".format(java.util.Locale.US, it.first)},${"%.2f".format(java.util.Locale.US, it.second)}]" })
                        append(",\"marques\":").append(marques.joinToString(",", "[", "]") { m ->
                            "{\"k\":\"${m.type}\",\"de\":${m.debutMs},\"a\":${m.finMs},\"t\":\"${js(m.texte)}\",\"d\":\"${js(m.details)}\"," +
                                "\"iss\":${m.viaIss},\"f\":\"${js(m.fichier?.let { File(it).name } ?: "")}\"" +
                                (if (m.lat != null && m.lon != null) ",\"lat\":${m.lat},\"lon\":${m.lon}" else "") + "}"
                        })
                        // The ground track (where the satellite was over the Earth) and the station, for the map.
                        append(",\"sol\":").append(j.traceSol(e).joinToString(",", "[", "]") {
                            "[${it.tMs},${"%.3f".format(java.util.Locale.US, it.lat)},${"%.3f".format(java.util.Locale.US, it.lon)},${Math.round(it.altKm)}]" })
                        fr.f4ioz.satcombo.location.Maidenhead.centre(e.locator)?.let { (la, lo) -> append(",\"qth\":[$la,$lo]") }
                        append(",\"sons\":").append(morceaux.withIndex().joinToString(",", "[", "]") { (i, m) ->
                            "{\"n\":$i,\"nom\":\"${js(m.f.name)}\",\"o\":${m.origine},\"an\":${m.annonce},\"du\":${m.dureeMs}}"
                        })
                        append(",\"sig\":").append(e.signal.joinToString(",", "[", "]") { "[${it.tMs},${it.s}]" })
                        append(",\"freq\":").append(frequences(j, e, l).joinToString(",", "[", "]") { "[${it.first},${it.second}]" })
                        append("}")
                    })
                }

                // Who the stations are (log, APRS, QRZ.com), for their cards.
                "/fiches" -> {
                    val e = passage(j, p("id")) ?: return NON
                    val f = kotlinx.coroutines.runBlocking { runCatching { j.fiches(j.marques(liens(j, e))) }.getOrDefault(emptyMap()) }
                    json(f.entries.joinToString(",", "{", "}") { (k, v) ->
                        "\"${js(k)}\":{\"nom\":\"${js(v.nom)}\",\"qth\":\"${js(v.qth)}\",\"pays\":\"${js(v.pays)}\",\"loc\":\"${js(v.locator)}\"}" })
                }

                // The accelerated replay's stretches: fast and silent where nothing was logged or heard.
                "/segments" -> {
                    val e = passage(j, p("id")) ?: return NON
                    json(segments(j, e, j.marques(liens(j, e)), p).joinToString(",", "[", "]") { "[${it.deMs},${it.aMs},${it.vitesse}]" })
                }

                // The phone's own replay and export settings: what the page starts from.
                "/reglages" -> json("{\"avant\":${j.avantS()},\"apres\":${j.apresQsoS()},\"rapide\":${j.rapide()}," +
                    "\"accelere\":${j.accelere()},\"surActivite\":${j.surActivite()},\"sstv\":${j.affSstv()}," +
                    "\"fiches\":${j.affFiches()},\"smetre\":${j.affSmetre()},\"freq\":${j.affFreq()}," +
                    "\"locator\":${j.affLocator()},\"res\":\"${js(j.videoRes())}\",\"ouverture\":${j.ouverture()},\"recap\":${j.recap()}}")

                // A map tile, fetched by the phone (as for its own map) and kept: the PC asks nothing outside.
                "/tuile" -> {
                    val z = p("z").toIntOrNull() ?: return NON; val x = p("x").toIntOrNull() ?: return NON; val y = p("y").toIntOrNull() ?: return NON
                    val f = tuile(ctx, z, x, y) ?: return NON
                    ReponseWeb("200 OK", "image/png", fichier = f, entetes = "Cache-Control: max-age=604800\r\n")
                }

                // Where the sound shows activity (worked out once per recording, then kept): asked apart, it may take a moment.
                "/activite" -> {
                    val e = passage(j, p("id")) ?: return NON
                    json(j.morceaux(e).flatMap { j.activite(it.f) }.joinToString(",", "[", "]") { "[${it.first},${it.last}]" })
                }

                // A sound file of the pass, by parts (the browser seeks in it).
                "/son" -> {
                    val e = passage(j, p("id")) ?: return NON
                    val m = j.morceaux(e).getOrNull(p("n").toIntOrNull() ?: -1) ?: return NON
                    ReponseWeb("200 OK", "audio/mpeg", fichier = m.f, entetes = "Cache-Control: max-age=3600\r\n")
                }

                // An SSTV picture of the gallery, whole (never another file).
                "/image" -> {
                    val f = runCatching { SstvHub.shots(ctx) }.getOrDefault(emptyList()).firstOrNull { it.first.name == p("f") }?.first ?: return NON
                    ReponseWeb("200 OK", "image/png", fichier = f, entetes = "Cache-Control: max-age=3600\r\n")
                }
                "/vignette" -> PlancheWeb.sert("/vignette", "GET", p) { null }

                // Something to make: a sound, a picture of the moment, a video, a GIF, the pass file.
                "/fabrique" -> {
                    val e = passage(j, p("id")) ?: return NON
                    val quoi = p("quoi").takeIf { it in setOf("son", "image", "video", "gif", "paquet") } ?: return NON
                    val de = p("de").toLongOrNull(); val a = p("a").toLongOrNull()
                    val moment = if (de != null && a != null && a > de) de.coerceAtLeast(e.debutMs - 600_000L)..a.coerceAtMost(e.finMs + 600_000L) else null
                    val n = "t" + System.currentTimeMillis().toString(36) + (100..999).random()
                    val t = Travail(); travaux[n] = t
                    // Older ones forgotten: a page left open does not fill the phone's memory.
                    if (travaux.size > 20) travaux.keys.sorted().take(travaux.size - 20).forEach { travaux.remove(it) }
                    kotlin.concurrent.thread(name = "JournalWeb", isDaemon = true) {
                        fabrique(ctx, j, e, quoi, moment, p("titre").take(60).ifBlank { null }, p("carte") == "1", p("t").toLongOrNull(), t, p)
                    }
                    json("{\"ok\":true,\"travail\":\"$n\"}")
                }
                "/travail" -> {
                    val t = travaux[p("n")] ?: return NON
                    json("{\"fini\":${t.fini},\"progres\":${t.progres},\"ok\":${t.fichier != null}," +
                        "\"nom\":\"${js(t.fichier?.name ?: "")}\",\"taille\":${t.fichier?.length() ?: 0}}")
                }
                "/fichier" -> {
                    val f = travaux[p("n")]?.fichier ?: return NON
                    ReponseWeb("200 OK", typeDe(f), fichier = f,
                        entetes = (if (p("voir") == "1") "" else "Content-Disposition: attachment; filename=\"${f.name}\"\r\n"))
                }

                else -> NON
            }
        }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }
    }
}
