/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.PageJournal
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.JournalPassage
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
        .replace("<script>", "<script>try{localStorage.setItem('satme-cle','pc')}catch(e){}</script>\n<style>#lienPlanche{display:none}</style>\n<script>")
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

    /** One request of the journal page. [route] is what follows `/journal`; [p] reads a parameter. */
    fun sert(route: String, p: (String) -> String): ReponseWeb = runCatching {
        when (route) {
            "/liste" -> {
                val l = j.passages().sortedByDescending { it.debutMs }
                json("{\"utc\":${s.useUtc},\"indicatif\":\"${js(s.callsign)}\",\"passages\":" + l.joinToString(",", "[", "]") { e ->
                    val k = j.liens(e)
                    "{\"id\":\"${js(e.id)}\",\"sat\":\"${js(e.satName)}\",\"de\":${e.debutMs},\"a\":${e.finMs}," +
                        "\"el\":${Math.round(e.elMax)},\"son\":${e.enregistrements.isNotEmpty()}," +
                        "\"q\":${k.qsos.size},\"i\":${k.images.size},\"r\":0,\"s\":0,\"cat\":${e.avecCat},\"sm\":${e.signal.isNotEmpty()}," +
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
                // A moment's sound, here; the drawn exports are the phone's for now.
                if (p("quoi") != "son") return json("{\"ok\":false,\"raison\":\"telephone\"}")
                val de = p("de").toLongOrNull(); val a = p("a").toLongOrNull()
                val r = if (de != null && a != null && a > de) de..a else e.debutMs..e.finMs
                val n = "t" + System.currentTimeMillis().toString(36) + (100..999).random()
                val t = Travail(); travaux[n] = t
                if (travaux.size > 20) travaux.keys.sorted().take(travaux.size - 20).forEach { travaux.remove(it) }
                kotlin.concurrent.thread(name = "JournalWebPc", isDaemon = true) {
                    val sortie = File(dossier, "exports").apply { mkdirs() }
                    val nom = "SatMe_" + e.satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(r.first)) + "Z_" + (p("titre").take(30).replace(Regex("[^A-Za-z0-9-]"), "-").ifBlank { "son" }) + ".wav"
                    t.fichier = runCatching { j.extraitWav(e, r.first, r.last, sortie, nom) }.getOrNull()
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
                ReponseWeb("200 OK", "audio/wav", fichier = f,
                    entetes = if (p("voir") == "1") "" else "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            }
            else -> NON
        }
    }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }

    private val client by lazy { okhttp3.OkHttpClient() }

    /** An OpenStreetMap tile, kept a month on disk, fetched with SatMe's user agent. */
    private fun tuile(z: Int, x: Int, y: Int): File? {
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
