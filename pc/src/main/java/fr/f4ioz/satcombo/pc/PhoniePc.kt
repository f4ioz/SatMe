/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.phonie.AnalysePhonie
import fr.f4ioz.satcombo.phonie.BullePhonie
import fr.f4ioz.satcombo.phonie.CapteurPhonie
import fr.f4ioz.satcombo.phonie.DecoupeVoix
import fr.f4ioz.satcombo.phonie.FilePhonie
import fr.f4ioz.satcombo.phonie.FiltrePhonie
import fr.f4ioz.satcombo.phonie.ProtocolePhonie.Message
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * « Phonie → texte »: the voice heard (the sound card, or the SDR dongle)
 * cut into overs, written out by the speech-to-text engine (a Python
 * helper, one process, started only while this is on), with the callsigns,
 * squares and reports found in it. Off by default: nothing listens, nothing
 * starts.
 *
 * The overs wait in a short queue: when the engine lags, the oldest goes.
 * The day's transcript is kept in `phonie/AAAA-MM-JJ.jsonl`.
 */
class PhoniePc(
    private val stockage: Stockage,
    val install: InstallPhoniePc,
    private val dossierJournal: File,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The engine for the settings now (a child process; a fake in the tests). */
    private val fabrique: (PhoniePc) -> MoteurPhonie = { p -> p.moteurProcessus() },
) {
    // ------------------------------------------------------------------ settings

    var actif: Boolean
        get() = stockage.getBoolean("pc_phonie", false)
        set(v) { stockage.edit().putBoolean("pc_phonie", v).apply(); if (v) demarre() else arrete() }
    var python: String
        get() = stockage.getString("pc_phonie_python", "") ?: ""
        set(v) { stockage.edit().putString("pc_phonie_python", v.trim()).apply(); redemarre() }
    var modele: String
        get() = (stockage.getString("pc_phonie_modele", "turbo") ?: "turbo").takeIf { it in InstallPhoniePc.TAILLES } ?: "turbo"
        set(v) { if (v in InstallPhoniePc.TAILLES && v != modele) { stockage.edit().putString("pc_phonie_modele", v).apply(); redemarre() } }
    /** "auto", "cuda", "cpu". */
    var calcul: String
        get() = (stockage.getString("pc_phonie_calcul", "auto") ?: "auto").takeIf { it in CALCULS } ?: "auto"
        set(v) { if (v in CALCULS && v != calcul) { stockage.edit().putString("pc_phonie_calcul", v).apply(); repliCpu = false; redemarre() } }
    /** The language said ("" = found by the engine). */
    var langue: String
        get() = stockage.getString("pc_phonie_langue", "") ?: ""
        set(v) { stockage.edit().putString("pc_phonie_langue", v.trim().lowercase().take(3)).apply() }
    var traduire: Boolean
        get() = stockage.getBoolean("pc_phonie_traduire", false)
        set(v) { stockage.edit().putBoolean("pc_phonie_traduire", v).apply() }
    /** "auto" (the dongle while it plays, else the card), "carte", "sdr". */
    var source: String
        get() = (stockage.getString("pc_phonie_source", "auto") ?: "auto").takeIf { it in SOURCES } ?: "auto"
        set(v) { if (v in SOURCES) { stockage.edit().putString("pc_phonie_source", v).apply(); carte.reinitialise(); sdr.reinitialise() } }

    // ------------------------------------------------------------------ what the station says

    /** The log's callsigns (their base), read again each minute. */
    @Volatile var connus: () -> Set<String> = { emptySet() }
    /** The sky frequency and the satellite for a source ("carte", "sdr"), when known. */
    @Volatile var contexte: (String) -> Pair<Long?, String> = { null to "" }

    // ------------------------------------------------------------------ the sound in

    private val carte = CapteurPhonie()
    private val sdr = CapteurPhonie()
    @Volatile private var sdrVuMs = 0L
    /** The source feeding now ("carte", "sdr", "" before any sound). */
    @Volatile var sourceActive = ""
        private set

    /** The sound card's blocks (the dongle's too when it is the card's source). */
    fun ecouteCarte(pcm: ShortArray, n: Int, rate: Int) {
        if (!actif) return
        val s = source
        if (s == "sdr" || (s == "auto" && horloge() - sdrVuMs < 1500L)) return
        if (sourceActive != "carte") { sourceActive = "carte"; carte.reinitialise() }
        carte.pousse(pcm, n, rate, horloge()).forEach { ajoute(it, "carte") }
    }

    /** The dongle's demodulated sound; [squelch]: its gate when one is set. */
    fun ecouteSdr(pcm: ShortArray, n: Int, rate: Int, squelch: Boolean?) {
        if (!actif) return
        val s = source
        if (s == "carte") return
        sdrVuMs = horloge()
        if (sourceActive != "sdr") { sourceActive = "sdr"; sdr.reinitialise() }
        sdr.pousse(pcm, n, rate, horloge(), squelch).forEach { ajoute(it, "sdr") }
    }

    /** True while a voice is being heard. */
    val enVoix: Boolean get() = when (sourceActive) { "carte" -> carte.enVoix; "sdr" -> sdr.enVoix; else -> false }

    private class Attente(val piece: CapteurPhonie.Piece, val source: String, val freqHz: Long?, val satellite: String)

    private val file = FilePhonie<Attente>(MAX_FILE)
    val perdus: Long get() = file.perdus
    val enFile: Int get() = file.taille()

    private fun ajoute(p: CapteurPhonie.Piece, source: String) {
        val (f, sat) = runCatching { contexte(source) }.getOrDefault(null to "")
        file.ajoute(Attente(p, source, f, sat))
    }

    /** How late the transcript is (s): the oldest over waiting, or the one being written. */
    fun retardS(): Double {
        val maintenant = horloge()
        val plusVieux = (listOfNotNull(enCours) + file.tous()).minOfOrNull { it.piece.debutMs + (it.piece.segment.dureeS * 1000).toLong() }
        return if (plusVieux == null) 0.0 else ((maintenant - plusVieux) / 1000.0).coerceAtLeast(0.0)
    }

    // ------------------------------------------------------------------ the engine

    /** "coupé", "démarrage", "prêt", "erreur". */
    @Volatile var etat = "coupé"
        private set
    @Volatile var erreur = ""
        private set
    @Volatile var info = ""
        private set
    @Volatile private var moteur: MoteurPhonie? = null
    @Volatile private var travail: Thread? = null
    @Volatile private var enCours: Attente? = null
    @Volatile private var generation = 0
    /** CUDA crashed the engine twice while loading: the processor for this session. */
    @Volatile private var repliCpu = false
    private var echecs = 0
    @Volatile var calculMoyenS = 0.0
        private set

    fun moteurProcessus(): MoteurPhonie {
        val py = install.python(python) ?: throw IllegalStateException("moteur non installé : Réglages › Phonie → texte › Installer le moteur")
        return MoteurProcessus(install.commande(py, modele, if (repliCpu) "cpu" else calcul), install.environnement())
    }

    /** Starts the worker (when on, at SatMe-PC's start too). */
    @Synchronized fun demarre() {
        if (!actif || travail?.isAlive == true) return
        val g = ++generation
        etat = "démarrage"; erreur = ""
        travail = thread(isDaemon = true, name = "satme-phonie") { boucle(g) }
    }

    @Synchronized fun arrete() {
        generation++
        travail = null
        runCatching { moteur?.arrete() }
        moteur = null
        file.vide(); enCours = null
        carte.reinitialise(); sdr.reinitialise(); sourceActive = ""
        etat = "coupé"; erreur = ""; info = ""; echecs = 0
    }

    /** A setting that the engine reads at start changed: started again (when on). */
    fun redemarre() { if (!actif) return; arrete(); demarre() }

    private fun boucle(g: Int) {
        while (g == generation) {
            val m: MoteurPhonie = moteur ?: (try {
                etat = "démarrage"
                val nouveau = fabrique(this)
                val p = nouveau.demarre()
                if (g != generation) { nouveau.arrete(); return }
                moteur = nouveau
                echecs = 0
                etat = "prêt"; erreur = ""
                info = "${p.modele} · " + (if (p.calcul == "cuda") "carte graphique (${p.type})" else "processeur (${p.type})") +
                    " · chargé en ${"%.1f".format(Locale.FRANCE, p.chargeS)} s" + (if (p.repli.isNotBlank() && p.calcul == "cpu") " · CUDA indisponible : ${p.repli.take(160)}" else "")
                nouveau
            } catch (e: Exception) {
                echecs++
                if (calcul == "auto" && !repliCpu && echecs >= 2 && (e.message ?: "").contains("arrêté")) repliCpu = true
                attend(g, e.message ?: e.toString())
                null
            }) ?: continue
            val a = file.prend(500) ?: continue
            if (g != generation) return
            enCours = a
            try {
                val pcm = DecoupeVoix.normalise(a.piece.segment.pcm)
                val r = m.transcrit(++numero, pcm, langue, traduire)
                if (g != generation) return
                when (r) {
                    is Message.Reponse -> recoit(a, r)
                    is Message.Erreur -> erreur = "dernier passage non transcrit : " + r.texte.take(200)
                    else -> {}
                }
            } catch (e: Exception) {
                runCatching { m.arrete() }
                moteur = null
                echecs++
                attend(g, e.message ?: e.toString())
            } finally { enCours = null }
        }
    }

    private var numero = 0L

    /** The engine failed: said, then tried again after 2, 4, 8… s (a minute at most). */
    private fun attend(g: Int, raison: String) {
        val s = minOf(60, 1 shl echecs.coerceAtMost(6))
        etat = "erreur"
        erreur = "$raison — nouvel essai dans $s s"
        val fin = horloge() + s * 1000L
        while (g == generation && horloge() < fin) Thread.sleep(200)
    }

    // ------------------------------------------------------------------ what was said

    private val bulles = ArrayDeque<BullePhonie>()
    @Volatile var version = 0L
        private set
    @Volatile private var idBulle = 0L
    @Volatile private var connusCache: Set<String> = emptySet()
    @Volatile private var connusMs = 0L
    private var charge = false

    private fun recoit(a: Attente, r: Message.Reponse) {
        val gardes = FiltrePhonie.garde(r.morceaux)
        calculMoyenS = if (calculMoyenS == 0.0) r.calculS else calculMoyenS * 0.8 + r.calculS * 0.2
        if (gardes.isEmpty()) return
        val texte = gardes.joinToString(" ") { it.texte.trim() }.trim()
        val maintenant = horloge()
        if (maintenant - connusMs > 60_000L) { connusCache = runCatching(connus).getOrDefault(emptySet()); connusMs = maintenant }
        val vus = synchronized(bulles) {
            bulles.filter { maintenant - it.t < 30 * 60_000L }.flatMap { b -> b.elements.filter { it.genre == AnalysePhonie.Genre.INDICATIF }.map { AnalysePhonie.base(it.valeur) } }.toSet()
        }
        val b = BullePhonie(0, a.piece.debutMs, a.piece.segment.dureeS, a.source, a.freqHz, a.satellite, r.langue, r.proba,
            texte, r.traduction.trim(), BullePhonie.confiance(gardes), r.calculS, AnalysePhonie.analyse(texte, r.langue, connusCache, vus))
        synchronized(bulles) {
            chargeJour()
            val c = b.copy(id = ++idBulle)
            bulles.addLast(c)
            while (bulles.size > MAX_BULLES) bulles.removeFirst()
            version++
            runCatching { fichierJour(c.t).also { it.parentFile.mkdirs() }.appendText(c.json().toString() + "\n", Charsets.UTF_8) }
        }
    }

    /** Today's overs (from the file) at the first look, so a restart shows them. */
    private fun chargeJour() {
        if (charge) return
        charge = true
        val f = fichierJour(horloge())
        if (!f.isFile) return
        val l = runCatching { f.readLines(Charsets.UTF_8).takeLast(MAX_BULLES).mapNotNull { runCatching { BullePhonie.depuis(JSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())
        l.forEach { bulles.addLast(it.copy(id = ++idBulle)) }
        if (l.isNotEmpty()) version++
    }

    /** The overs after [depuis] (an id), oldest first. */
    fun bulles(depuis: Long = 0L): List<BullePhonie> = synchronized(bulles) { chargeJour(); bulles.filter { it.id > depuis } }

    fun dernierId(): Long = synchronized(bulles) { chargeJour(); idBulle }

    /** Clears the window (the day's file stays). */
    fun efface() = synchronized(bulles) { chargeJour(); bulles.clear(); version++ }

    fun fichierJour(t: Long): File = File(dossierJournal, jour(t) + ".jsonl")

    /** The days kept, newest first. */
    fun jours(): List<String> = (dossierJournal.listFiles { f -> f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.jsonl")) } ?: emptyArray())
        .map { it.name.removeSuffix(".jsonl") }.sortedDescending()

    /** A day as text: one line per over (UTC time, frequency, language, text, translation, what was found). */
    fun texteJour(jour: String): String? {
        val f = File(dossierJournal, File(jour).name + ".jsonl").takeIf { it.isFile } ?: return null
        val heure = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return f.readLines(Charsets.UTF_8).mapNotNull { runCatching { BullePhonie.depuis(JSONObject(it)) }.getOrNull() }.joinToString("") { b ->
            val trouves = b.elements.joinToString(" ") { it.valeur }
            heure.format(Date(b.t)) + " UTC" + (b.freqHz?.let { " · %.4f MHz".format(Locale.US, it / 1e6) } ?: "") +
                (if (b.satellite.isNotBlank()) " · ${b.satellite}" else "") + " · ${b.langue}" + "\n" + b.texte + "\n" +
                (if (b.traduction.isNotBlank()) "  (en) ${b.traduction}\n" else "") + (if (trouves.isNotBlank()) "  → $trouves\n" else "") + "\n"
        }
    }

    // ------------------------------------------------------------------ the page

    fun json(depuis: Long): JSONObject = JSONObject().put("present", true).put("on", actif).put("etat", if (actif) etat else "coupé")
        .put("erreur", if (actif) erreur else "").put("info", info).put("modele", modele).put("langue", langue).put("traduire", traduire)
        .put("source", source).put("sourceActive", sourceActive).put("voix", actif && enVoix).put("file", enFile).put("perdus", perdus)
        .put("retard", Math.round(retardS() * 10) / 10.0).put("calcul", Math.round(calculMoyenS * 100) / 100.0)
        .put("installe", install.python(python) != null).put("version", version).put("dernier", dernierId())
        .put("bulles", JSONArray(bulles(depuis).takeLast(80).map { b ->
            b.json().put("elements", JSONArray(b.elements.map { e ->
                e.json().also { j -> if (e.genre == AnalysePhonie.Genre.INDICATIF) fr.f4ioz.satcombo.domain.Dxcc.entite(e.valeur)?.let { d -> j.put("pays", d.nom).put("drapeau", d.drapeau) } }
            }))
        }))

    /** The settings card: the interpreter, the check, the installation. */
    fun jsonMoteur(): JSONObject {
        val i = install.installation
        return JSONObject().put("python", python).put("pythonUtilise", install.python(python) ?: "").put("venv", install.venv.absolutePath)
            .put("venvExiste", install.pythonVenv().isFile).put("gpu", install.gpu ?: JSONObject.NULL).put("modele", modele).put("calcul", calcul)
            .put("verification", install.verification ?: JSONObject.NULL).put("verificationEnCours", install.verificationEnCours)
            .put("modeles", JSONArray(InstallPhoniePc.TAILLES.map { (k, v) -> JSONObject().put("nom", k).put("taille", v) }))
            .put("installation", JSONObject().put("enCours", i.enCours).put("etape", i.etape).put("lignes", JSONArray(i.lignes.takeLast(30)))
                .put("erreur", i.erreur).put("fini", i.fini).put("mo", i.mo))
    }

    /** The page's settings and actions (POST /api/phonie…). */
    fun action(chemin: String, q: Map<String, String>): JSONObject {
        when (chemin) {
            "/api/phonie" -> {
                q["langue"]?.let { langue = it }
                q["traduire"]?.let { traduire = it == "1" }
                q["source"]?.let { source = it }
                if (q["efface"] == "1") efface()
                q["on"]?.let { v ->
                    if (v == "1" && install.python(python) == null) return JSONObject().put("ok", false)
                        .put("erreur", "Le moteur n'est pas installé : Réglages › Phonie → texte › Installer le moteur.")
                    actif = v == "1"
                }
            }
            "/api/phonie/moteur" -> {
                q["python"]?.let { python = it }
                q["modele"]?.let { modele = it }
                q["calcul"]?.let { calcul = it }
                if (q["verifie"] == "1") { if (install.gpu == null) thread(isDaemon = true) { install.detecteGpu() }; install.verifie(install.python(python)) }
                if (q["installe"] == "1") install.installe(modele) { redemarre() }
            }
            else -> return JSONObject().put("ok", false).put("erreur", "inconnu : $chemin")
        }
        return JSONObject().put("ok", true)
    }

    companion object {
        const val MAX_FILE = 6
        const val MAX_BULLES = 300
        val CALCULS = setOf("auto", "cuda", "cpu")
        val SOURCES = setOf("auto", "carte", "sdr")
        private fun jour(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    }
}
