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

import fr.f4ioz.satcombo.demo.ApiJournalSstv
import fr.f4ioz.satcombo.demo.PageJournal
import fr.f4ioz.satcombo.demo.ReponseWeb
import fr.f4ioz.satcombo.domain.CadreSstv
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.JournalPaquet
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

    /** The passes kept (a contact's pass, its rotor path). */
    fun passages() = j.passages()

    /** The page, opened at once (no code on this PC) and without the phone-only links. */
    fun page(): String = PageJournal.html()
        .replace("<script>", "<script>window.SATME_PC=true;try{localStorage.setItem('satme-cle','pc')}catch(e){}</script>\n<style>${PagePc.STYLE_BARRE}</style>\n<script>")
        // The PC's header above the journal, the same as on its other pages.
        .replace("<div id=\"page\" class=\"cache\">", "<div id=\"page\" class=\"cache\">" + PagePc.barre("journal") + "<script>${PagePc.SCRIPT_BARRE}</script>")
        // Its words are the phone's: said here of the PC.
        .let { h -> TEXTES_PC.entries.fold(h) { acc, (cle, pc) -> acc.replace(js(fr.f4ioz.satcombo.i18n.t(cle)), js(fr.f4ioz.satcombo.i18n.t(pc))) } }

    /** The page's words that speak of the phone, and the PC's in their place. */
    private val TEXTES_PC = mapOf("pj_sous" to "pc_pj_sous", "pj_aucun" to "pc_pj_aucun", "pw_pupitre" to "pc_pj_station",
        "pj_echec" to "pc_pj_echec", "pj_en_cours" to "pc_pj_en_cours", "pw_hors_ligne" to "pc_pj_hors_ligne",
        "pj_options_aide" to "pc_pj_options_aide")

    /** A received picture by its name: only a PNG of the SSTV folder, never another file. */
    private fun imageSstv(nom: String): File? =
        File(dossier, "sstv").let { d -> File(d, File(nom).name) }.takeIf { it.isFile && it.name.endsWith(".png") }

    // Small pictures, kept: the moments' list asks for them each time it is drawn.
    private val vignettes = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>?) = size > 120
    }
    private fun vignette(f: File, w: Int): ByteArray? = synchronized(vignettes) {
        val cle = f.name + "@" + w + "@" + f.lastModified()
        vignettes[cle] ?: PlancheRenduPc.charge(f, w)?.let { PlancheRenduPc.jpeg(PlancheRenduPc.rgb(it), 0.85f) }?.also { vignettes[cle] = it }
    }

    private fun js(s: String): String = buildString {
        for (ch in s) when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch == '<' -> append("\\u003c"); ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }

    /** The pass report's routes (`/rapport…`), when SatMe-PC gives them. */
    @Volatile var rapport: RapportPc? = null

    /** The SSTV sheet (its events, corrections, montage): the SSTV versions and the report's panels read through it (set by Main). */
    @Volatile var planche: PlancheWebPc? = null

    /** The pass's SSTV versions and the report's panels ([ApiJournalSstv], the phone's too). */
    val sstv = ApiJournalSstv(object : ApiJournalSstv.Plateforme {
        override fun source() = planche?.source
        override fun planches() = planche?.planches().orEmpty()
        override fun passage(id: String) = j.passage(id)
        override fun enregistre(e: JournalPassage.Entree) { j.rangement.enregistre(e) }
        override fun images(e: JournalPassage.Entree) = j.liens(e).images
    })

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

    fun sert(route: String, p: (String) -> String, methode: String = "GET", corps: (File) -> Boolean): ReponseWeb = runCatching {
        if (route == "/rapport" || route.startsWith("/rapport/")) return rapport?.sert(route.removePrefix("/rapport"), p) ?: NON
        if (route.startsWith("/sstv")) return sstv.sert(route, p, methode) ?: NON
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
                // QRZ out of reach never stops the contact: it is written without its record.
                val fiche = p("c").takeIf { it.isNotBlank() }?.let { runCatching { kotlinx.coroutines.runBlocking { j.ficheQrz(it) } }.getOrNull() }?.takeIf { it.erreur.isBlank() && !it.vide }
                val c = j.ajouteContact(e, t0, p("c"), p("l"), p("rs"), p("rr"), p("n"), p("md"), p("nm"), p("qth"), fiche)
                // Why it was not written: the real reason, not always the callsign.
                val pourquoi = when {
                    p("c").isBlank() -> t("pj_n_indicatif")
                    station.etat.value.satellites.none { it.catalogNumber == e.catnum } -> t("pj_n_sat_inconnu")
                    else -> t("pj_n_refuse")
                }
                json(if (c != null) "{\"ok\":true}" else "{\"ok\":false,\"erreur\":\"${js(pourquoi)}\"}")
            }
            // A pass taken out of the journal (asked on the page first): its entry only — its recordings,
            // SSTV pictures and the log's contacts stay where they are.
            "/supprime" -> {
                if (methode != "POST") return ReponseWeb("400 Bad Request", "application/json", "{\"ok\":false}".toByteArray())
                val e = j.passage(p("id")) ?: return NON
                j.rangement.supprime(e)
                json("{\"ok\":true}")
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
                    append(",\"doppler\":").append(station.dossierDoppler?.let { fr.f4ioz.satcombo.domain.JournalDoppler.duPassage(it, e.satName, e.debutMs, e.finMs) }
                        .orEmpty().joinToString(",", "[", "]") { "\"${js(it.name)}\"" })
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
                "\"ouverture\":${s.journalOuverture},\"recap\":${s.journalRecap},\"paquetQsos\":${s.journalPaquetQsos}," +
                "\"cadreSstv\":\"${js(CadreSstv.lit(s.journalCadreSstv)?.texte() ?: "")}\"}")
            // The SSTV picture placed with the mouse on the page (v: "x,y,l"), or back to by itself (v empty).
            "/cadreSstv" -> {
                s.journalCadreSstv = CadreSstv.lit(p("v"))?.texte() ?: ""
                json("{\"ok\":true,\"cadreSstv\":\"${js(s.journalCadreSstv)}\"}")
            }
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
            // With the pass (id): the version its pictures show (as received, corrected, fused).
            "/image" -> {
                val f = (p("id").takeIf { it.isNotBlank() }?.let { sstv.image(it, File(p("f")).name) }) ?: imageSstv(p("f")) ?: return NON
                ReponseWeb("200 OK", "image/png", fichier = f)
            }
            // A picture of the pass, small, for its row among the moments (the page asked, nothing answered: 22/10).
            "/vignette" -> {
                val f = (p("id").takeIf { it.isNotBlank() }?.let { sstv.image(it, File(p("f")).name) }) ?: imageSstv(p("f")) ?: return NON
                val b = vignette(f, p("w").toIntOrNull()?.coerceIn(80, 800) ?: 160) ?: return NON
                ReponseWeb("200 OK", "image/jpeg", b, "Cache-Control: max-age=3600\r\n")
            }
            "/fabrique" -> {
                val e = j.passage(p("id")) ?: return NON
                val quoi = p("quoi").takeIf { it in setOf("son", "image", "gif", "video", "paquet") }
                    ?: return json("{\"ok\":false,\"raison\":\"telephone\"}")
                if (quoi == "video" && RenduPc.ffmpeg() == null) return json("{\"ok\":false,\"raison\":\"ffmpeg\"}")
                val de = p("de").toLongOrNull(); val a = p("a").toLongOrNull()
                val moment = if (de != null && a != null && a > de) de..a else null
                // The pass whole on the globe, asked the session's way (moteur=session, the PC's page): a session of one —
                // its margins (margeAvant / margeApres, ms), paces, zooms, companions. « avant » / « apres » stay the
                // accelerated replay's seconds around a contact (the phone's words), not read as margins here.
                if (p("moteur") == "session" && quoi in setOf("video", "gif", "image") && moment == null && !opt(p, "accelere", false) &&
                    GlobeRenduPc.styleDemande(p("vue"), p("style")) != null)
                    return fabriqueSession { k -> when (k) { "ids" -> e.id; "avant", "apres" -> ""; else -> p(k) } }
                val r = moment ?: (e.debutMs..e.finMs)
                val n = "t" + System.currentTimeMillis().toString(36) + (100..999).random()
                val t = Travail(); travaux[n] = t
                if (travaux.size > 20) travaux.keys.sorted().take(travaux.size - 20).forEach { travaux.remove(it) }
                kotlin.concurrent.thread(name = "JournalWebPc", isDaemon = true) {
                    val sortie = File(dossier, "exports").apply { mkdirs() }
                    val ext = when (quoi) { "son" -> ".wav"; "image" -> ".png"; "gif" -> ".gif"; "paquet" -> ".zip"; else -> ".mp4" }
                    // « Image de ce moment »: named after its moment, not the pass's start.
                    val instant = p("t").toLongOrNull()?.takeIf { quoi == "image" }
                    val nom = "SatMe_" + e.satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(instant ?: r.first)) + "Z_" + (p("titre").take(30).replace(Regex("[^A-Za-z0-9-]"), "-").ifBlank { if (moment != null || instant != null) "moment" else "passage" }) + ext
                    t.fichier = runCatching {
                        when (quoi) {
                            "son" -> j.extraitWav(e, r.first, r.last, sortie, nom)
                            // The page's box ticked or not (contacts=1 / 0), else the station's setting.
                            "paquet" -> paquet(e, opt(p, "contacts", s.journalPaquetQsos), sortie) { t.progres = it }
                            "image" -> RenduPc.png(scene(e, null, null, true, p), p("t").toLongOrNull(), File(sortie, nom))
                            "gif" -> RenduPc.gif(scene(e, moment, p("titre"), false, p), File(sortie, nom)) { t.progres = it }
                            else -> video(e, scene(e, moment, p("titre"), false, p), opt(p, "avecSon", true), File(sortie, nom), sortie) { t.progres = it }
                        }
                    }.onFailure { System.err.println("journal : $quoi de ${e.id} : $it"); it.printStackTrace() }.getOrNull()
                    t.progres = 1f; t.fini = true
                }
                json("{\"ok\":true,\"travail\":\"$n\"}")
            }
            // A session: one satellite's passes one after another on the globe, as an MP4 (with the contacts' sound when asked), a GIF, a picture, its sound.
            "/fabrique-session" -> fabriqueSession(p)
            "/travail" -> {
                val t = travaux[p("n")] ?: return NON
                json("{\"fini\":${t.fini},\"progres\":${t.progres},\"ok\":${t.fichier != null},\"nom\":\"${js(t.fichier?.name ?: "")}\",\"taille\":${t.fichier?.length() ?: 0}}")
            }
            // A Doppler/CAT log (CSV), by its name.
            "/doppler" -> {
                val f = station.dossierDoppler?.let { fr.f4ioz.satcombo.domain.JournalDoppler.fichier(it, p("nom")) } ?: return NON
                ReponseWeb("200 OK", "text/csv; charset=utf-8", fichier = f, entetes = "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            }
            "/fichier" -> {
                val f = travaux[p("n")]?.fichier ?: return NON
                val type = when (f.extension) { "png" -> "image/png"; "gif" -> "image/gif"; "mp4" -> "video/mp4"; "zip" -> JournalPaquet.TYPE; else -> "audio/wav" }
                ReponseWeb("200 OK", type, fichier = f,
                    entetes = if (p("voir") == "1") "" else "Content-Disposition: attachment; filename=\"${f.name}\"\r\n")
            }
            else -> NON
        }
    }.getOrElse { json("{\"ok\":false,\"raison\":\"${js(it.javaClass.simpleName)}\"}") }

    /**
     * A session made by the station ([SessionRenduPc]): its passes ([p] « ids »), each from « avant » ms before its AOS
     * to « apres » ms after its LOS (« margeAvant » / « margeApres » too; 30 s by default). A single pass on the globe
     * is made here too (« /fabrique » with « moteur=session »): a session of one.
     */
    private fun fabriqueSession(p: (String) -> String): ReponseWeb {
        val q = SessionRenduPc.parametres(p)
        if (q.quoi == "video" && RenduPc.ffmpeg() == null) return json("{\"ok\":false,\"raison\":\"ffmpeg\"}")
        val es = q.ids.mapNotNull { j.passage(it) }.distinctBy { it.id }.sortedBy { it.debutMs }.take(150)
        if (es.isEmpty()) return json("{\"ok\":false,\"raison\":\"vide\"}")
        val n = "t" + System.currentTimeMillis().toString(36) + (100..999).random()
        val t = Travail(); travaux[n] = t
        if (travaux.size > 20) travaux.keys.sorted().take(travaux.size - 20).forEach { travaux.remove(it) }
        kotlin.concurrent.thread(name = "JournalWebPc", isDaemon = true) {
            val sortie = File(dossier, "exports").apply { mkdirs() }
            // Always the globe, the pass whole (the session sets its own pace).
            val pg: (String) -> String = { k -> when (k) { "vue" -> "globe"; "carte" -> "0"; "accelere" -> "0"; else -> p(k) } }
            val sons = HashMap<Int, fr.f4ioz.satcombo.domain.SonPassage>()
            t.fichier = runCatching {
                val scenes = es.map { e -> scene(e, null, null, false, pg).also { sc -> sc.horsTrace = { tt -> j.positionA(e, tt) } } }
                // The companions, seen from the first pass's station, each its colour (as on the page).
                val e0 = es[0]
                val autres = q.autres.filter { it != e0.satName }.mapIndexed { i, nom ->
                    GlobeRenduPc.Autre(nom, java.awt.Color(GlobeRenduPc.COULEURS_AUTRES[i % GlobeRenduPc.COULEURS_AUTRES.size])) { tt -> j.positionDe(nom, e0, tt) }
                }
                val avecSon = q.son && q.quoi in setOf("video", "son")
                val session = SessionRenduPc.Session(scenes, q.mode, q.v, q.titre, q.vignettes,
                    ouverture = opt(p, "ouverture", s.journalOuverture), recap = opt(p, "recap", s.journalRecap), transition = q.transition,
                    zHors = q.zHors, zPassage = q.zPassage, autres = autres, tachesAutres = q.tachesAutres, son = avecSon, avant = q.avant, apres = q.apres)
                // Each pass's sound, from its window's start (its margin before the AOS) to its end.
                val debuts = HashMap<Int, Long>()
                if (avecSon) es.forEachIndexed { i, e ->
                    val m = j.morceaux(e); if (m.isEmpty()) return@forEachIndexed
                    val de = e.debutMs - q.avant
                    fr.f4ioz.satcombo.domain.SonPassage.fabrique(m, de, e.finMs + q.apres, sortie, JournalPc.decodeur)?.let { sons[i] = it; debuts[i] = de }
                }
                val son = if (sons.isEmpty()) null else SessionRenduPc.Son(sons, debuts, sons.values.first().rate)
                val ext = when (q.quoi) { "gif" -> ".gif"; "image" -> ".png"; "son" -> ".wav"; else -> ".mp4" }
                val nom = "SatMe_" + es[0].satName.replace(Regex("[^A-Za-z0-9-]"), "-") + "_" +
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(if (q.quoi == "image") q.instant ?: es[0].debutMs else es[0].debutMs)) + (if (es.size == 1) "Z_passage" else "Z_session") +
                    (q.titre?.take(30)?.replace(Regex("[^A-Za-z0-9-]"), "-")?.let { "_$it" } ?: "") + ext
                val f = File(sortie, nom)
                when (q.quoi) {
                    "gif" -> SessionRenduPc.gif(session, f) { t.progres = it }
                    "image" -> SessionRenduPc.png(session, q.instant ?: es[0].debutMs, f)
                    "son" -> son?.let { sn -> RenduPc.ecritWav(SessionRenduPc.source(session, SessionRenduPc.plan(session), sn), sn.rate, f); f }
                    else -> SessionRenduPc.video(session, f, son, sortie) { t.progres = it }
                }
            }.onFailure { System.err.println("journal : session de ${es.size} passages : $it"); it.printStackTrace() }.getOrNull()
            sons.values.forEach { it.close() }
            t.progres = 1f; t.fini = true
        }
        return json("{\"ok\":true,\"travail\":\"$n\",\"passages\":${es.size}}")
    }

    /**
     * The pass file, as the phone makes it ([JournalPaquet]): the pass, its
     * recordings (MP3, as the phone keeps them, with their .info and their
     * time), its SSTV pictures and their sidecars, its contacts when asked —
     * a phone or another SatMe-PC opens it.
     */
    private fun paquet(e: JournalPassage.Entree, avecContacts: Boolean, sortie: File, progres: (Float) -> Unit): File {
        val tmp = File(sortie, "paquet-" + System.nanoTime()).apply { mkdirs() }
        try {
            val fichiers = ArrayList<Pair<String, File>>()
            val ff = RenduPc.ffmpeg()
            val sons = j.morceaux(e).map { it.f }.distinct()
            sons.forEachIndexed { i, wav ->
                progres(0.8f * i / sons.size.coerceAtLeast(1))
                if (ff == null) return@forEachIndexed
                val mp3 = File(tmp, wav.name.substringBeforeLast('.') + ".mp3")
                val pr = ProcessBuilder(ff, "-y", "-loglevel", "error", "-i", wav.absolutePath, "-codec:a", "libmp3lame", "-q:a", "4", mp3.absolutePath)
                    .redirectErrorStream(true).start()
                pr.inputStream.readBytes(); pr.waitFor()
                if (mp3.isFile && mp3.length() > 0) {
                    // The phone places a recording by its file's time (its end) and its length.
                    mp3.setLastModified(wav.lastModified())
                    val info = File(tmp, mp3.name.substringBeforeLast('.') + ".info")
                    info.writeText("locator=" + s.manualLocator.trim() + "\nannonce=0\n"); info.setLastModified(wav.lastModified())
                    fichiers += "recordings" to mp3; fichiers += "recordings" to info
                }
            }
            val l = j.liens(e)
            for ((png, shot) in l.images) {
                fichiers += "sstv" to png
                File(png.parentFile, fr.f4ioz.satcombo.sstv.SstvMeta.sidecarName(png.name)).takeIf { it.isFile }?.let { fichiers += "sstv" to it }
            }
            progres(0.9f)
            val f = File(sortie, JournalPaquet.nom(e))
            f.outputStream().buffered().use { o -> JournalPaquet.emballe(o, e, fichiers, contacts = if (avecContacts) l.qsos else emptyList()) }
            return f
        } finally { tmp.deleteRecursively() }
    }

    /** What a picture, a GIF or a video shows, from the page's options (else the settings). */
    private fun scene(e: JournalPassage.Entree, moment: LongRange?, titre: String?, image: Boolean, p: (String) -> String): RenduPc.Scene {
        val l = j.liens(e)
        // Each SSTV picture as the pass chose it (as received by default), « corrigée » said only when asked.
        val montrees = runCatching { sstv.montrees(e) }.getOrDefault(emptyMap())
        val marques = sstv.marques(e, j.marques(l), montrees)
        val elements = e.options.elements.values.any { it.actif }
        // The globe (vue=globe, style=…): the page sends carte=0 with it.
        val globe = GlobeRenduPc.styleDemande(p("vue"), p("style"))
        return RenduPc.Scene(e, j.prevue(e), marques, s.callsign, flashS(),
            affSstv = opt(p, "sstv", affSstv()), affFiches = opt(p, "fiches", s.journalAffFiches),
            affSmetre = opt(p, "smetre", s.journalAffSmetre), affFreq = opt(p, "freq", s.journalAffFreq), affTx = opt(p, "tx", s.journalAffTx),
            ouverture = opt(p, "ouverture", s.journalOuverture) && !image, recap = opt(p, "recap", s.journalRecap) && moment == null && !image,
            fiches = j.fiches(l),
            segments = moment?.let { listOf(JournalPassage.Segment(it.first, it.last, 1)) }
                ?: if (!image && opt(p, "accelere", false)) segments(e, marques, p) else null,
            titreExtrait = if (moment != null) titre?.trim()?.ifBlank { null } else null,
            frequences = frequences(e, l), resolution = p("res").takeIf { it in setOf("XS", "M", "HD") } ?: s.journalVideoRes,
            surCarte = p("carte") == "1" && globe == null, sol = j.traceSol(e), qth = Maidenhead.centre(e.locator),
            surGlobe = globe != null, styleGlobe = globe ?: GlobeRenduPc.DEFAUT, optionsGlobe = GlobeRenduPc.options(p), police = LegendePc.police(p), fuseau = LegendePc.fuseau(p),
            etiquetteQth = if (opt(p, "locator", s.journalAffLocator)) listOf(s.callsign, e.locator).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null } else null,
            vueVue = run {
                val cx = p("mcx").toDoubleOrNull(); val cy = p("mcy").toDoubleOrNull(); val z = p("zc").toDoubleOrNull(); val cw = p("cw").toFloatOrNull()
                if (cx != null && cy != null && z != null && cw != null && cw > 0f) JournalPassage.VueCarte(cx, cy, z) to cw else null
            },
            cadreSstv = cadreSstv(p("cadreSstv")),
            paysage = p("format") == "paysage", cielEntier = p("format").isNotEmpty(),
            tuile = { z, x, y -> tuile(z, x, y)?.let { f -> runCatching { javax.imageio.ImageIO.read(f) }.getOrNull() } },
            options = e.options, donnees = if (elements) runCatching { sstv.donnees(e) }.getOrNull() else null,
            etiquettes = sstv.etiquettes(e, montrees))
    }

    /** The SSTV picture's place sent with a request ("auto": by itself), else the one kept. */
    private fun cadreSstv(v: String): CadreSstv? = if (v.isBlank()) CadreSstv.lit(s.journalCadreSstv) else CadreSstv.lit(v)

    /** The video, with the pass's sound when asked and recorded (the opening title's seconds silent). */
    private fun video(e: JournalPassage.Entree, sc: RenduPc.Scene, avecSon: Boolean, sortie: File, travail: File, progres: (Float) -> Unit): File? {
        val morceaux = if (avecSon) j.morceaux(e) else emptyList()
        val segs = sc.segments
        val deSon = minOf(e.debutMs, segs?.firstOrNull()?.deMs ?: e.debutMs)
        val aSon = maxOf(e.finMs, segs?.lastOrNull()?.aMs ?: e.finMs)
        val son = if (morceaux.isNotEmpty()) fr.f4ioz.satcombo.domain.SonPassage.fabrique(morceaux, deSon, aSon, travail, JournalPc.decodeur) else null
        return try {
            // The sound waits for the title and the opening card.
            val titre = if (son != null) RenduPc.secondesAvantPassage(sc) * son.rate else 0
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
