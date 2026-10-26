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
import fr.f4ioz.satcombo.phonie.HauteurVoix
import fr.f4ioz.satcombo.phonie.IntonationProche
import fr.f4ioz.satcombo.phonie.ParoleTraduite
import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * « Traduction parlée »: each over written out and translated, said aloud
 * by a synthetic voice — on this PC's speaker (the receiver's sound lowered
 * meanwhile), in the browser, or both. Receive only: the voice is for the
 * listener, never sent to a transmitter. Off by default.
 *
 * The voice: Piper with a model of the target language (installed on
 * demand); without it eSpeak NG (its sound, so the same treatment), else
 * speech-dispatcher (the PC speaks directly), else the browser's own voice.
 * Its intonation: « neutre », « proche » ([IntonationProche]: speed, mean
 * pitch, phrase movements, loudness of the over heard), or « voix clonée »
 * (the tone colour converter first, when installed, then « proche »).
 *
 * At most two overs wait; one heard more than 30 s ago is not said.
 */
class ParolePc(
    private val stockage: Stockage,
    val install: InstallPhoniePc,
    /** The engine's interpreter ("" when not installed) and the language translated into. */
    private val python: () -> String?,
    private val cible: () -> String,
    private val horloge: () -> Long = System::currentTimeMillis,
    private val trouve: (String) -> String? = OutilsPc::trouve,
    /** The SDR speaker's level (1 normal), lowered while this PC speaks. */
    private val baisse: (Float) -> Unit = {},
    private val lecteur: Lecteur = Lecteur.Systeme,
    private val fabriqueSynthese: (ParolePc) -> SyntheseVoix? = { it.synthesePiper() },
    private val fabriqueClone: (ParolePc) -> ConvertisseurVoix? = { it.convertisseur() },
    private val lanceur: AnnoncesPc.Lanceur = AnnoncesPc.Lanceur.Systeme,
) {
    /** Plays a sound to the end. */
    fun interface Lecteur {
        fun joue(pcm: ShortArray, rate: Int)

        companion object {
            val Systeme = Lecteur { pcm, rate ->
                if (ModeDemo.actif) return@Lecteur
                val f = AudioFormat(rate.toFloat(), 16, 1, true, false)
                val b = ByteArray(pcm.size * 2)
                for (i in pcm.indices) { b[2 * i] = pcm[i].toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                val l = runCatching { (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, f)) as SourceDataLine).also { it.open(f, rate / 5 * 2); it.start() } }.getOrNull()
                if (l != null) {
                    try { l.write(b, 0, b.size); l.drain() } finally { runCatching { l.stop(); l.close() } }
                } else {
                    // No Java sound line: aplay, the WAV on its input.
                    val a = OutilsPc.trouve("aplay") ?: throw IllegalStateException("aucune sortie son")
                    val p = ProcessBuilder(a, "-q", "-").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    runCatching { p.outputStream.use { it.write(ParoleTraduite.wav(pcm, rate)) } }
                    if (!p.waitFor(120, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }
    }

    // ------------------------------------------------------------------ settings

    var actif: Boolean
        get() = stockage.getBoolean("pc_parole", false)
        set(v) { stockage.edit().putBoolean("pc_parole", v).apply(); if (v) demarre() else arrete() }
    /** A voice of [ParoleTraduite.VOIX], or "auto" (by the speaker's pitch). */
    var voix: String
        get() = stockage.getString("pc_parole_voix", "auto") ?: "auto"
        set(v) { if (v == "auto" || ParoleTraduite.voix(v) != null) stockage.edit().putString("pc_parole_voix", v).apply() }
    var intonation: String
        get() = (stockage.getString("pc_parole_intonation", "proche") ?: "proche").takeIf { it in ParoleTraduite.INTONATIONS } ?: "proche"
        set(v) { if (v in ParoleTraduite.INTONATIONS) { stockage.edit().putString("pc_parole_intonation", v).apply(); if (v != "clonee") arreteClone() } }
    var volume: Int
        get() = stockage.getInt("pc_parole_volume", 80).coerceIn(0, 100)
        set(v) { stockage.edit().putInt("pc_parole_volume", v.coerceIn(0, 100)).apply() }
    /** "pc", "navigateur", "deux". */
    var sortie: String
        get() = (stockage.getString("pc_parole_sortie", "pc") ?: "pc").takeIf { it in SORTIES } ?: "pc"
        set(v) { if (v in SORTIES) stockage.edit().putString("pc_parole_sortie", v).apply() }
    /** Only the overs in another language than the one translated into. */
    var etrangere: Boolean
        get() = stockage.getBoolean("pc_parole_etrangere", true)
        set(v) { stockage.edit().putBoolean("pc_parole_etrangere", v).apply() }

    private val pc: Boolean get() = sortie != "navigateur"
    private val navigateur: Boolean get() = sortie != "pc"

    // ------------------------------------------------------------------ the overs

    /** An over understood: its bubble, its sound (16 kHz), the segments kept, Whisper's whole translation. */
    class Entendu(val bulle: BullePhonie, val pcm: FloatArray, val morceaux: List<ProtocolePhonie.Morceau>, val traductionEntiere: String) {
        val finMs: Long get() = bulle.t + (bulle.dureeS * 1000).toLong()
    }

    private val file = ParoleTraduite.File<Entendu>(2, PERIME_MS, horloge)

    /** From « Phonie → texte », each over kept: queued when it is to be said. */
    fun entendu(e: Entendu) {
        if (!actif) return
        if (aDire(e) == null) return
        file.ajoute(e, e.finMs)
        demarre()
    }

    /** What is said for [e]: its phrases (time heard, words heard, words said), null when nothing. */
    fun aDire(e: Entendu): List<Pair<IntonationProche.Phrase, String>>? {
        val b = e.bulle
        val c = cible()
        val dit = ParoleTraduite.aDire(b.langue, c, b.traduction, b.texte, etrangere) ?: return null
        val parSegment = e.morceaux.isNotEmpty() && e.morceaux.all { it.traduction.isNotBlank() } && dit == b.traduction
        return when {
            parSegment -> e.morceaux.map { IntonationProche.Phrase(it.debutS, it.finS, it.texte) to it.traduction.trim() }
            dit == b.texte && e.morceaux.isNotEmpty() -> e.morceaux.map { IntonationProche.Phrase(it.debutS, it.finS, it.texte) to it.texte.trim() }
            else -> listOf(IntonationProche.Phrase(e.morceaux.firstOrNull()?.debutS ?: 0.0, e.morceaux.lastOrNull()?.finS ?: b.dureeS, b.texte) to dit)
        }.filter { it.second.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ the worker

    /** "coupé", "prêt", "synthèse", "parle", "erreur". */
    @Volatile var etat = "coupé"; private set
    @Volatile var erreur = ""; private set
    @Volatile var moteurUtilise = ""; private set
    /** From the over's end to its voice ready (ms), the last one. */
    @Volatile var latenceMs = 0L; private set
    @Volatile private var travail: Thread? = null
    @Volatile private var generation = 0
    @Volatile private var synthese: SyntheseVoix? = null
    @Volatile private var clone: ConvertisseurVoix? = null
    /** Each voice's syllables a second at length 1, learnt as it speaks. */
    private val debits = HashMap<String, Double>()

    @Synchronized fun demarre() {
        if (!actif || travail?.isAlive == true) return
        val g = ++generation
        etat = "prêt"
        travail = thread(isDaemon = true, name = "satme-parole") { boucle(g) }
    }

    @Synchronized fun arrete() {
        generation++
        travail = null
        file.vide()
        runCatching { synthese?.arrete() }; synthese = null
        arreteClone()
        etat = "coupé"; erreur = ""
    }

    private fun arreteClone() { runCatching { clone?.arrete() }; clone = null }

    private fun boucle(g: Int) {
        while (g == generation) {
            val e = file.attend(500) ?: continue
            if (g != generation) return
            runCatching { dis(e) }.onFailure { erreur = (it.message ?: it.toString()).take(240); etat = "erreur" }
        }
    }

    /** Processes one over: synthesised, brought near the speaker, played and handed to the browsers. */
    fun dis(e: Entendu) {
        val phrases = aDire(e) ?: return
        // Said translated when foreign (as [aDire] chose), else as heard.
        val traduit = e.bulle.traduction.isNotBlank() && e.bulle.langue != cible()
        val langue = if (traduit) e.bulle.langueCible.ifBlank { cible() } else e.bulle.langue
        etat = "synthèse"
        val r = rends(phrases, langue, e.pcm, ProtocolePhonie.RATE, e.bulle.langue)
        if (!file.frais(e.finMs)) { etat = "prêt"; return }
        latenceMs = (horloge() - e.finMs).coerceAtLeast(0)
        publie(e, phrases.joinToString(" ") { it.second }, langue, r)
    }

    /** What [rends] made: the sound (null when the PC spoke it itself), the voice, hints for a browser voice. */
    class Rendu(val pcm: ShortArray?, val rate: Int, val voix: String, val moteur: String, val vitesse: Double, val hauteur: Double, val f0Hz: Double, val dejaDit: Boolean = false)

    fun rends(phrases: List<Pair<IntonationProche.Phrase, String>>, langue: String, original: FloatArray?, rateOriginal: Int, langueOriginale: String): Rendu {
        val into = intonation
        // Measured whatever the intonation: « auto » picks the voice nearest the speaker's pitch even when « neutre ».
        val mesure = if (original != null && original.size > rateOriginal / 2)
            runCatching { IntonationProche.analyse(original, rateOriginal, phrases.map { it.first }) }.getOrNull() else null
        val a = mesure?.takeIf { into != "neutre" }
        val f0 = mesure?.profil?.f0Hz ?: 0.0
        val textes = phrases.map { it.second }
        // A browser's voice: its speed and pitch nudged the same way.
        val hauteurNav = if (f0 > 0) (1.0 + (HauteurVoix.demiTons(f0) - HauteurVoix.demiTons(160.0)) / 24.0).coerceIn(0.6, 1.4) else 1.0
        val v = ParoleTraduite.choisit(voix, langue, install.voixPresentes(), f0)
        val s = if (v != null) synthese ?: runCatching { fabriqueSynthese(this) }.getOrNull()?.also { synthese = it } else null
        if (v != null && s != null) {
            val cle = v.id
            val longueur = if (a != null) IntonationProche.longueur(a, langueOriginale, langue, synchronized(debits) { debits[cle] } ?: 6.0) else 1.0
            var (morceaux, rate) = s.dit(v.fichier, v.locuteur, textes, longueur)
            moteurUtilise = "Piper" + ((s as? SynthesePiper)?.version?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
            apprendDebit(cle, textes, morceaux, rate, longueur)
            if (into == "clonee" && original != null) {
                val c = clone ?: runCatching { fabriqueClone(this) }.getOrNull()?.also { clone = it }
                if (c != null) {
                    val conv = morceaux.map { m -> if (m.isEmpty()) m to rate else c.convertit(original, rateOriginal, m, rate) }
                    rate = conv.firstOrNull()?.second ?: rate
                    morceaux = conv.map { it.first }
                    moteurUtilise += " + convertisseur de timbre"
                }
            }
            val x = if (a != null) IntonationProche.applique(morceaux, rate, a) else IntonationProche.joint(morceaux, rate)
            return Rendu(volume(x), rate, v.nom, moteurUtilise, 1.0 / longueur, hauteurNav, f0)
        }
        // eSpeak NG: its sound, treated the same.
        val espeak = trouve("espeak-ng")
        if (espeak != null) {
            val vitesse = if (a != null) 1.0 / IntonationProche.longueur(a, langueOriginale, langue, 6.0) else 1.0
            val morceaux = textes.map { t -> espeak(espeak, langue, t, vitesse) }
            val rate = morceaux.firstOrNull { it.second > 0 }?.second ?: 22_050
            moteurUtilise = "eSpeak NG"
            val x = morceaux.map { it.first }.let { m -> if (a != null) IntonationProche.applique(m, rate, a) else IntonationProche.joint(m, rate) }
            return Rendu(volume(x), rate, "eSpeak NG", moteurUtilise, vitesse, hauteurNav, f0)
        }
        // speech-dispatcher on this PC: it speaks itself (nothing for a browser but the text).
        val spd = trouve("spd-say")
        if (spd != null && pc) {
            moteurUtilise = "speech-dispatcher"
            val vitesse = if (a != null) 1.0 / IntonationProche.longueur(a, langueOriginale, langue, 6.0) else 1.0
            etat = "parle"
            val masculine = f0 in 1.0..165.0
            parleSurPc { lanceur.lance(listOf(spd, "-w", "-l", langue, "-r", ((vitesse - 1) * 100).toInt().coerceIn(-50, 50).toString(),
                "-p", (((hauteurNav - 1) * 100).toInt()).coerceIn(-40, 40).toString(), "-t", if (masculine) "male1" else "female1",
                "-i", (volume * 2 - 100).toString(), "--", textes.joinToString(" ").trimStart('-', ' ')), null) }
            return Rendu(null, 0, "speech-dispatcher", moteurUtilise, vitesse, hauteurNav, f0, dejaDit = true)
        }
        moteurUtilise = "navigateur"
        return Rendu(null, 0, "", moteurUtilise, if (a != null) 1.0 / IntonationProche.longueur(a, langueOriginale, langue, 6.0) else 1.0, hauteurNav, f0)
    }

    private fun apprendDebit(cle: String, textes: List<String>, morceaux: List<FloatArray>, rate: Int, longueur: Double) {
        val syl = textes.sumOf { IntonationProche.syllabes(it) }
        val s = morceaux.sumOf { it.size } / rate.toDouble()
        if (syl < 4 || s < 0.8) return
        val d = syl / (s / longueur)
        synchronized(debits) { debits[cle] = debits[cle]?.let { it * 0.7 + d * 0.3 } ?: d }
    }

    private fun volume(x: FloatArray): ShortArray = ParoleTraduite.enShorts(x, 0.85f * volume / 100f)

    private fun espeak(chemin: String, langue: String, texte: String, vitesse: Double): Pair<FloatArray, Int> = runCatching {
        val p = ProcessBuilder(chemin, "-v", langue, "-s", (165 * vitesse).toInt().coerceIn(90, 300).toString(), "--stdout", "--", texte.trimStart('-', ' '))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        val b = p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS)
        litWav(b)
    }.getOrDefault(FloatArray(0) to 0)

    private fun parleSurPc(f: () -> Unit) {
        baisse(BAISSE)
        try { f() } finally { baisse(1f) }
    }

    // ------------------------------------------------------------------ what was said

    class Dite(val seq: Long, val ms: Long, val texte: String, val langue: String, val voix: String, val moteur: String,
               val wav: ByteArray?, val dureeS: Double, val latenceMs: Long, val vitesse: Double, val hauteur: Double, val navigateur: Boolean, val essai: Boolean) {
        fun json(): JSONObject = JSONObject().put("seq", seq).put("ms", ms).put("texte", texte).put("langue", langue).put("voix", voix)
            .put("moteur", moteur).put("audio", wav != null).put("duree", Math.round(dureeS * 10) / 10.0).put("latence", latenceMs)
            .put("vitesse", Math.round(vitesse * 100) / 100.0).put("hauteur", Math.round(hauteur * 100) / 100.0).put("navigateur", navigateur).put("essai", essai)
    }

    private val dites = ArrayDeque<Dite>()
    @Volatile var dernierSeq = 0L; private set

    private fun publie(e: Entendu?, texte: String, langue: String, r: Rendu, essai: Boolean = false) {
        val wav = r.pcm?.let { ParoleTraduite.wav(it, r.rate) }
        val d = Dite(0, e?.finMs ?: horloge(), texte, langue, r.voix, r.moteur, wav, if (r.pcm != null) r.pcm.size / r.rate.toDouble() else 0.0,
            if (e != null) latenceMs else 0, r.vitesse, r.hauteur, navigateur, essai)
        synchronized(dites) {
            val c = Dite(++dernierSeq, d.ms, d.texte, d.langue, d.voix, d.moteur, d.wav, d.dureeS, d.latenceMs, d.vitesse, d.hauteur, d.navigateur, d.essai)
            dites.addLast(c)
            while (dites.size > 6) dites.removeFirst()
        }
        erreur = ""
        if (pc && r.pcm != null && !r.dejaDit) {
            etat = "parle"
            parleSurPc { lecteur.joue(r.pcm, r.rate) }
        }
        etat = if (actif) "prêt" else "coupé"
    }

    /** The sentences handed out after [seq] (the live stream, the page). */
    fun depuis(seq: Long): List<JSONObject> = synchronized(dites) { dites.filter { it.seq > seq }.map { it.json() } }

    /** A sound said, as WAV, by its number. */
    fun son(seq: Long): ByteArray? = synchronized(dites) { dites.firstOrNull { it.seq == seq }?.wav }

    /** « Essayer »: a sentence in the voice chosen, now (whatever the queue). */
    fun essai(): JSONObject {
        val c = cible()
        val texte = ESSAIS[c] ?: ESSAIS.getValue("fr")
        thread(isDaemon = true, name = "satme-parole-essai") {
            runCatching {
                etat = "synthèse"
                val r = rends(listOf(IntonationProche.Phrase(0.0, 3.0, texte) to texte), c, null, ProtocolePhonie.RATE, c)
                publie(null, texte, c, r, essai = true)
            }.onFailure { erreur = (it.message ?: it.toString()).take(240); etat = "erreur" }
        }
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------------ the engines

    fun synthesePiper(): SyntheseVoix? {
        val py = python() ?: return null
        if (install.voixPresentes().isEmpty()) return null
        return SynthesePiper(listOf(py, install.ecritScriptVoix().absolutePath, "--parle", "--voix", install.voix.absolutePath), install.environnement())
            .also { s -> runCatching { s.demarre() }.onFailure { s.arrete(); erreur = it.message ?: "Piper ne démarre pas"; return null } }
    }

    fun convertisseur(): ConvertisseurVoix? {
        if (!install.clonageInstalle()) return null
        return ConvertisseurProcessus(listOf(install.pythonClonage().absolutePath, install.ecritScriptVoix().absolutePath, "--convertit",
            "--clone", install.modeleClone.absolutePath), install.environnement())
            .also { c -> runCatching { c.demarre() }.onFailure { c.arrete(); erreur = it.message ?: "convertisseur illisible"; return null } }
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        val c = cible()
        val presentes = install.voixPresentes()
        return JSONObject().put("present", true).put("on", actif).put("voix", voix).put("intonation", intonation).put("volume", volume)
            .put("sortie", sortie).put("etrangere", etrangere).put("etat", etat).put("erreur", erreur).put("moteur", moteurUtilise)
            .put("cible", c).put("latence", latenceMs).put("attente", file.taille()).put("sautees", file.sautees).put("perimees", file.perimees)
            .put("clonage", install.clonageInstalle())
            .put("voixDispo", JSONArray(ParoleTraduite.disponibles(c, presentes).map { JSONObject().put("id", it.id).put("nom", it.nom) }))
            .put("piper", presentes.isNotEmpty()).put("espeak", trouve("espeak-ng") != null).put("spd", trouve("spd-say") != null)
            .put("seq", dernierSeq).put("dites", JSONArray(synchronized(dites) { dites.toList().takeLast(4).reversed().map { it.json() } }))
    }

    @Volatile private var verificationDemandee = false

    /** The settings card: Piper, the voices of each language (size, licence, there or not), the converter. */
    fun jsonInstall(): JSONObject {
        // Piper's state looked at once, the first time the card shows.
        if (install.verificationVoix == null && !verificationDemandee && python() != null) { verificationDemandee = true; install.verifieVoix(python()) }
        val presentes = install.voixPresentes()
        val c = cible()
        val iv = install.installationVoix
        val ic = install.installationClone
        fun inst(i: InstallPhoniePc.Installation) = JSONObject().put("enCours", i.enCours).put("etape", i.etape).put("lignes", JSONArray(i.lignes.takeLast(30)))
            .put("erreur", i.erreur).put("fini", i.fini).put("mo", i.mo)
        return JSONObject().put("cible", c).put("python", python() ?: "").put("verification", install.verificationVoix ?: JSONObject.NULL)
            .put("voix", JSONArray(ParoleTraduite.VOIX.distinctBy { it.fichier }.map { v ->
                val noms = ParoleTraduite.VOIX.filter { it.fichier == v.fichier }.joinToString(", ") { it.nom }
                JSONObject().put("fichier", v.fichier).put("langue", v.langue).put("noms", noms).put("mo", v.mo).put("licence", v.licence)
                    .put("installee", v.fichier in presentes)
            }))
            .put("installation", inst(iv))
            .put("clonage", JSONObject().put("installe", install.clonageInstalle()).put("venv", install.clonage.absolutePath)
                .put("verification", install.verificationClone ?: JSONObject.NULL).put("installation", inst(ic))
                .put("mo", if (install.clonage.isDirectory && !ic.enCours) install.mo(install.clonage) else 0.0))
    }

    fun action(q: Map<String, String>): JSONObject {
        q["voix"]?.let { voix = it }
        q["intonation"]?.let { intonation = it }
        q["volume"]?.toIntOrNull()?.let { volume = it }
        q["sortie"]?.let { sortie = it }
        q["etrangere"]?.let { etrangere = it == "1" }
        q["on"]?.let { actif = it == "1" }
        if (q["essai"] == "1") return essai()
        q["installeVoix"]?.let { l ->
            val ids = l.split(",").map { it.trim() }.filter { f -> ParoleTraduite.VOIX.any { it.fichier == f } }
            install.installeVoix(python(), ids) { runCatching { synthese?.arrete() }; synthese = null }
        }
        q["retireVoix"]?.let { if (install.retireVoix(it)) { runCatching { synthese?.arrete() }; synthese = null } }
        if (q["installeClone"] == "1") install.installeClone()
        if (q["retireClone"] == "1") { arreteClone(); install.retireClone() }
        if (q["verifie"] == "1") install.verifieVoix(python())
        return JSONObject().put("ok", true)
    }

    companion object {
        const val PERIME_MS = 30_000L
        /** The SDR speaker while the voice speaks. */
        const val BAISSE = 0.25f
        val SORTIES = setOf("pc", "navigateur", "deux")
        val ESSAIS = mapOf(
            "fr" to "Essai de la traduction parlée. Ceci est une voix de synthèse, entendue sur ce poste seulement.",
            "en" to "Spoken translation test. This is a synthetic voice, heard on this station only.",
            "de" to "Test der gesprochenen Übersetzung. Dies ist eine synthetische Stimme.",
            "es" to "Prueba de la traducción hablada. Esta es una voz sintética.",
            "it" to "Prova della traduzione parlata. Questa è una voce sintetica.",
            "nl" to "Test van de gesproken vertaling. Dit is een synthetische stem.",
            "pl" to "Test mówionego tłumaczenia. To jest głos syntetyczny.",
            "pt" to "Teste da tradução falada. Esta é uma voz sintética.")

        /** A WAV file's samples and rate (16-bit PCM; a streamed one's sizes may be wrong: the rest is taken). */
        fun litWav(b: ByteArray): Pair<FloatArray, Int> {
            if (b.size < 44 || String(b, 0, 4) != "RIFF") return FloatArray(0) to 0
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var p = 12
            var rate = 0
            while (p + 8 <= b.size) {
                val id = String(b, p, 4)
                val n = bb.getInt(p + 4)
                if (id == "fmt ") rate = bb.getInt(p + 12)
                if (id == "data") {
                    val de = p + 8
                    val m = (b.size - de) / 2
                    return FloatArray(m) { i -> bb.getShort(de + 2 * i) / 32768f } to rate
                }
                if (n < 0) break
                p += 8 + n + (n and 1)
            }
            return FloatArray(0) to rate
        }
    }
}
