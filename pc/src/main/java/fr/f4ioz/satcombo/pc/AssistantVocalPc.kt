/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ModulesStation
import fr.f4ioz.satcombo.i18n.I18n
import fr.f4ioz.satcombo.i18n.Lang
import fr.f4ioz.satcombo.phonie.IntonationProche
import fr.f4ioz.satcombo.phonie.ProtocolePhonie
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * The voice assistant (module « Assistant vocal », off by default): this
 * PC's microphone, the wake word « SatMe », the sentence written by the
 * local Whisper engine (Phonie → texte's Python environment, a small model of
 * its own), understood by [CommandesPc], the answer said by the synthetic
 * voice of « Traduction parlée » ([ParolePc]) and shown on the page.
 * Everything stays on this PC; nothing is sent anywhere, nothing is kept
 * but the last commands' text in memory.
 *
 * The wake word: openWakeWord's pretrained models (« hey jarvis », « alexa
 * »…) are CC BY-NC-SA, so none is used. Instead each piece of voice cut by
 * the level ([Segmenteur]) is written by Whisper, and a piece that starts
 * with « SatMe » (« Satmi », « Sat me »…, [MotDeReveil]) is a command —
 * its words after the name, or the next piece within 8 s. The page's
 * push-to-talk button skips the name.
 *
 * The microphone is opened only while « Écouter » is ticked (or for 8 s
 * after the button), and not at all in the demonstration. Never sent to a
 * transmitter: the answer goes to this PC's speaker or the page.
 */
class AssistantVocalPc(
    private val stockage: Stockage,
    private val install: InstallPhoniePc,
    private val commandes: CommandesPc,
    /** The engine's interpreter (Phonie → texte's, "" when not installed). */
    private val python: () -> String?,
    /** Opens the microphone: its blocks to [recoit] (PCM, n, rate); a closer, or null and why. */
    private val ouvre: (String, (ShortArray, Int, Int) -> Unit) -> Pair<(() -> Unit)?, String> = ::ouvreMicro,
    /** The speech engine (the tests' fake), made when first needed. */
    private val fabriqueMoteur: (String) -> MoteurPhonie = { m -> MoteurProcessus(install.commande(python() ?: "python3", m, "auto"), install.environnement()) },
    /** Says a sentence ([texte], language) on this PC; false when nothing could (the page's browser says it then). */
    private val dit: (String, String) -> Boolean = { _, _ -> false },
    private val horloge: () -> Long = System::currentTimeMillis,
    private val module: () -> Boolean = { ModulesStation.actif(stockage, ModulesStation.ASSISTANT) },
) {
    // ------------------------------------------------------------------ settings

    /** Listening to the microphone for the wake word (off until ticked). */
    var ecoute: Boolean
        get() = stockage.getBoolean("assistant_ecoute", false)
        set(v) { stockage.edit().putBoolean("assistant_ecoute", v).apply(); revise() }
    /** The microphone: a card's name, "" for the system's default. */
    var micro: String
        get() = stockage.getString("assistant_micro", "") ?: ""
        set(v) { if (v != micro) { stockage.edit().putString("assistant_micro", v.trim()).apply(); fermeMicro(); revise() } }
    /** Whisper's model for the commands: small and quick. */
    var modele: String
        get() = (stockage.getString("assistant_modele", "base") ?: "base").takeIf { it in MODELES } ?: "base"
        set(v) { if (v in MODELES && v != modele) { stockage.edit().putString("assistant_modele", v).apply(); arreteMoteur() } }
    /** "auto" (SatMe's language), "fr" or "en". */
    var langue: String
        get() = (stockage.getString("assistant_langue", "auto") ?: "auto").takeIf { it in setOf("auto", "fr", "en") } ?: "auto"
        set(v) { if (v in setOf("auto", "fr", "en")) stockage.edit().putString("assistant_langue", v).apply() }
    /** The answers said aloud on this PC (else only shown). */
    var parle: Boolean
        get() = stockage.getBoolean("assistant_parle", true)
        set(v) { stockage.edit().putBoolean("assistant_parle", v).apply() }
    /** The level a piece of voice must reach over the room's noise (1 to 10). */
    var sensibilite: Int
        get() = stockage.getInt("assistant_sensibilite", 5).coerceIn(1, 10)
        set(v) { stockage.edit().putInt("assistant_sensibilite", v.coerceIn(1, 10)).apply() }

    private fun langueEffective(): String = when (langue) { "fr", "en" -> langue; else -> if (I18n.current() == Lang.EN) "en" else "fr" }

    val actif: Boolean get() = runCatching { module() }.getOrDefault(false)

    // ------------------------------------------------------------------ state

    @Volatile var etat = "arrêté"; private set
    @Volatile var erreur = ""; private set
    @Volatile var niveau = 0f; private set
    /** Until when the next piece of voice is a command (the wake word heard alone, or the button). */
    @Volatile private var attenteJusqu = 0L
    /** While SatMe speaks: what the microphone hears is not listened to. */
    @Volatile private var parleJusqu = 0L

    private var fermeture: (() -> Unit)? = null
    private val segmenteur = Segmenteur()
    private val morceaux = LinkedBlockingQueue<FloatArray>(8)
    @Volatile private var travail: Thread? = null
    @Volatile private var moteur: MoteurPhonie? = null
    private var reechantillon = Reechantillonneur(16_000, ProtocolePhonie.RATE)

    /** Start or stop the microphone to match the settings and the button. */
    @Synchronized fun revise() {
        val voulu = actif && (ecoute || horloge() < attenteJusqu) && pret()
        if (voulu && fermeture == null) ouvreMicro()
        else if (!voulu && fermeture != null) fermeMicro()
        if (!actif) arreteMoteur()
    }

    /** Once a second (Main's loop): the button's 8 s over, the microphone closed again. */
    fun tic() { if (fermeture != null && !ecoute && horloge() >= attenteJusqu) revise() }

    private fun ouvreMicro() {
        ModeDemo.refus("le microphone")?.let { erreur = it; etat = "arrêté"; return }
        segmenteur.remet()
        val (f, err) = ouvre(micro) { pcm, n, rate -> recoit(pcm, n, rate) }
        if (f == null) { erreur = err.ifBlank { "microphone indisponible" }; etat = "erreur"; return }
        fermeture = f
        erreur = ""
        etat = "écoute"
        demarreTravail()
    }

    @Synchronized private fun fermeMicro() {
        fermeture?.let { runCatching { it() } }
        fermeture = null
        niveau = 0f
        if (etat == "écoute") etat = "arrêté"
    }

    /** The push-to-talk button: the next piece of voice within 8 s is a command, without the wake word. */
    fun bouton(): JSONObject {
        if (!actif) return JSONObject().put("ok", false).put("erreur", MODULE_COUPE)
        if (!pret()) return JSONObject().put("ok", false).put("erreur", "installer d'abord la reconnaissance (Réglages › Assistant et domotique › Assistant vocal)")
        attenteJusqu = horloge() + ATTENTE_MS
        revise()
        if (fermeture == null) return JSONObject().put("ok", false).put("erreur", erreur.ifBlank { "microphone indisponible" })
        return JSONObject().put("ok", true).put("jusqua", attenteJusqu)
    }

    /** A block of the microphone: brought to 16 kHz, cut into pieces of voice. */
    internal fun recoit(pcm: ShortArray, n: Int, rate: Int) {
        if (reechantillon.de != rate) reechantillon = Reechantillonneur(rate, ProtocolePhonie.RATE)
        val x = reechantillon.convertit(pcm, n)
        var crete = 0f
        for (v in x) crete = maxOf(crete, kotlin.math.abs(v))
        niveau = crete
        // Its own voice is not a command.
        if (horloge() < parleJusqu) { segmenteur.remet(); return }
        segmenteur.sensibilite = sensibilite
        segmenteur.ajoute(x).forEach { m -> if (!morceaux.offer(m)) { morceaux.poll(); morceaux.offer(m) } }
    }

    private fun demarreTravail() {
        if (travail?.isAlive == true) return
        travail = thread(isDaemon = true, name = "satme-assistant") {
            while (true) {
                val m = morceaux.poll(1, TimeUnit.SECONDS)
                if (m == null) { if (fermeture == null && morceaux.isEmpty()) break else continue }
                runCatching { traite(m) }.onFailure { erreur = (it.message ?: it.toString()).take(240); etat = "erreur"; arreteMoteur() }
            }
            travail = null
        }
    }

    /** One piece of voice: written, the wake word looked for, the command answered. */
    internal fun traite(pcm: FloatArray) {
        val l = langueEffective()
        val texte = transcrit(pcm, l) ?: return
        if (MotDeReveil.parasite(texte)) return
        val t = horloge()
        val enAttente = t < attenteJusqu
        val (reveil, reste) = MotDeReveil.cherche(texte)
        val commande = when {
            enAttente -> if (reveil) reste else texte
            reveil -> reste
            else -> return
        }
        if (commande.isBlank()) {
            // « SatMe » alone: the next piece is the command.
            attenteJusqu = t + ATTENTE_MS
            ditCourt(if (l == "en") "Yes?" else "Oui ?", l)
            return
        }
        attenteJusqu = 0L
        repondA(commande, l)
    }

    /** A sentence typed on the page, or heard: answered, said aloud when chosen. */
    fun repondA(commande: String, l: String = langueEffective(), source: String = SOURCE): CommandesPc.Reponse {
        val r = commandes.phrase(commande, source)
        if (parle && source == SOURCE) ditCourt(r.texte(l == "en"), l)
        return r
    }

    private fun ditCourt(texte: String, l: String) {
        if (!parle) return
        // The microphone deaf while it speaks (and a little after: the room's echo).
        parleJusqu = horloge() + 30_000L
        try { if (!runCatching { dit(texte, l) }.getOrDefault(false)) dernierPourPage = horloge() to texte }
        finally { parleJusqu = horloge() + 400L }
    }

    /** The last sentence the PC could not say: the page's browser says it. */
    @Volatile var dernierPourPage: Pair<Long, String>? = null; private set

    private var seq = 0L
    private fun transcrit(pcm: FloatArray, l: String): String? {
        val m = moteur ?: run {
            etat = "chargement du modèle"
            val x = fabriqueMoteur(modele)
            x.demarre()
            moteur = x
            x
        }
        etat = "écoute"
        return when (val r = m.transcrit(++seq, pcm, l, false)) {
            is ProtocolePhonie.Message.Reponse -> r.morceaux.filter { it.noSpeechProb < 0.7 }.joinToString(" ") { it.texte.trim() }.trim().takeIf { it.isNotEmpty() }
            is ProtocolePhonie.Message.Erreur -> { erreur = r.texte.take(240); null }
            else -> null
        }
    }

    @Synchronized private fun arreteMoteur() { moteur?.let { runCatching { it.arrete() } }; moteur = null }

    fun arrete() { fermeMicro(); arreteMoteur() }

    // ------------------------------------------------------------------ installation

    /** The model of [modele] is in `stt/modeles/` (faster-whisper's cache: `models--Systran--faster-whisper-base`). */
    fun modelePresent(m: String = modele): Boolean = (install.modeles.listFiles() ?: emptyArray()).any { d ->
        d.isDirectory && d.name.endsWith("faster-whisper-$m") && (File(d, "snapshots").listFiles()?.any { s -> File(s, "model.bin").isFile } == true)
    }

    /** Ready: the engine's Python there and the model downloaded. */
    fun pret(): Boolean = python() != null && modelePresent()

    @Volatile var installation = InstallPhoniePc.Installation(); private set
    private val journal = ArrayDeque<String>()

    private fun note(l: String) = synchronized(journal) {
        journal.addLast(l.take(300)); while (journal.size > 60) journal.removeFirst()
        installation = InstallPhoniePc.Installation(true, installation.etape, journal.toList(), mo = installation.mo)
    }

    /**
     * « Installer »: the engine's Python environment (`stt/`, made when absent, the same as Phonie → texte's),
     * faster-whisper in it when missing, then [modele]'s files — nothing else. In a thread.
     */
    fun installe(): JSONObject {
        if (!actif) return JSONObject().put("ok", false).put("erreur", MODULE_COUPE)
        ModeDemo.refus("l'installation")?.let { return JSONObject().put("ok", false).put("erreur", it) }
        if (installation.enCours) return JSONObject().put("ok", true)
        synchronized(journal) { journal.clear() }
        installation = InstallPhoniePc.Installation(true, "préparation")
        val m = modele
        thread(isDaemon = true, name = "satme-assistant-installe") {
            try {
                val py = install.pythonVenv()
                if (!py.isFile) {
                    etape("création de l'environnement Python (${install.venv.absolutePath})")
                    val (sys, version) = install.pythonSysteme() ?: throw IllegalStateException(
                        "Python 3.9 ou plus récent introuvable : installer python3 et python3-venv (Windows : python.org)")
                    note(version)
                    lance(sys + listOf("-m", "venv", install.venv.absolutePath))
                }
                if (!py.isFile) throw IllegalStateException("l'environnement n'a pas été créé (sous Debian/Ubuntu : sudo apt install python3-venv)")
                if (runCatching { lance(listOf(py.absolutePath, "-c", "import faster_whisper"), journalise = false) }.isFailure) {
                    etape("installation de faster-whisper (environ 120 Mo)")
                    lance(listOf(py.absolutePath, "-m", "pip", "install", "--disable-pip-version-check", "--progress-bar", "off", "faster-whisper"))
                }
                etape("téléchargement du modèle « $m » (${TAILLES[m] ?: "?"})")
                install.modeles.mkdirs()
                lance(listOf(py.absolutePath, install.ecritScript().absolutePath, "--telecharge", "--modele", m, "--modeles", install.modeles.absolutePath)) { l ->
                    val j = runCatching { JSONObject(l) }.getOrNull()
                    if (j != null && j.has("mo")) { installation = InstallPhoniePc.Installation(true, installation.etape, installation.lignes, mo = j.optDouble("mo")); true } else false
                }
                if (!modelePresent(m)) throw IllegalStateException("le modèle « $m » n'a pas été trouvé après le téléchargement")
                note("modèle « $m » prêt")
                installation = InstallPhoniePc.Installation(false, "installé", synchronized(journal) { journal.toList() }, fini = true)
                revise()
            } catch (e: Exception) {
                installation = InstallPhoniePc.Installation(false, installation.etape, synchronized(journal) { journal.toList() }, erreur = e.message ?: e.toString())
            }
        }
        return JSONObject().put("ok", true)
    }

    private fun etape(e: String) { installation = InstallPhoniePc.Installation(true, e, synchronized(journal) { journal.toList() }); note("— $e") }

    private fun lance(c: List<String>, journalise: Boolean = true, filtre: (String) -> Boolean = { false }) {
        if (journalise) note("$ " + c.joinToString(" "))
        val pb = ProcessBuilder(c).redirectErrorStream(true)
        pb.environment().putAll(install.environnement(horsLigne = false))
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { if (!filtre(it) && it.isNotBlank() && journalise) note(it) }
        val code = p.waitFor()
        if (code != 0) throw IllegalStateException("échec (code $code)")
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        val t = horloge()
        return JSONObject().put("module", actif).put("ecoute", ecoute).put("micro", micro).put("modele", modele).put("langue", langue)
            .put("parle", parle).put("sensibilite", sensibilite).put("pret", pret()).put("python", python() != null)
            .put("modelePresent", modelePresent()).put("etat", if (fermeture == null && etat == "écoute") "arrêté" else etat).put("erreur", erreur)
            .put("micOuvert", fermeture != null).put("niveau", Math.round(niveau * 100) / 100.0).put("attente", attenteJusqu > t)
            .put("tailles", JSONObject(TAILLES as Map<*, *>)).put("modeles", JSONArray(MODELES))
            .put("installation", JSONObject().put("enCours", installation.enCours).put("etape", installation.etape)
                .put("lignes", JSONArray(installation.lignes.takeLast(20))).put("erreur", installation.erreur).put("fini", installation.fini).put("mo", installation.mo))
            .put("pourPage", dernierPourPage?.let { JSONObject().put("ms", it.first).put("texte", it.second) } ?: JSONObject.NULL)
    }

    /** The microphones of this PC (for the list), the decoders' card left out of nothing: the page warns. */
    fun micros(): JSONArray = JSONArray(runCatching {
        AudioSystem.getMixerInfo().filter { info ->
            val m = AudioSystem.getMixer(info)
            m.targetLineInfo.any { it.lineClass == TargetDataLine::class.java }
        }.map { JSONObject().put("nom", it.name).put("description", it.description) }
    }.getOrDefault(emptyList()))

    companion object {
        const val SOURCE = "voix"
        const val ATTENTE_MS = 8_000L
        const val MODULE_COUPE = "Module « Assistant vocal » désactivé — l'activer dans Réglages › Modules"
        /** The models offered for the commands, the quick ones; what each weighs once downloaded. */
        val MODELES = listOf("tiny", "base", "small")
        val TAILLES = linkedMapOf("tiny" to "75 Mo", "base" to "145 Mo", "small" to "485 Mo")

        /** The system's default microphone, or a card by name, at 16 kHz when it can (else 48 / 44.1 kHz). */
        fun ouvreMicro(nom: String, recoit: (ShortArray, Int, Int) -> Unit): Pair<(() -> Unit)?, String> {
            val formats = listOf(16_000f, 48_000f, 44_100f).map { AudioFormat(it, 16, 1, true, false) }
            val mixer = if (nom.isBlank()) null else AudioSystem.getMixerInfo().firstOrNull { it.name == nom }?.let { AudioSystem.getMixer(it) }
                ?: return null to "microphone introuvable : $nom"
            for (f in formats) {
                val l = runCatching {
                    (if (mixer != null) mixer.getLine(javax.sound.sampled.DataLine.Info(TargetDataLine::class.java, f)) as TargetDataLine
                    else AudioSystem.getTargetDataLine(f)).also { it.open(f, f.frameSize * 4096); it.start() }
                }.getOrNull() ?: continue
                val rate = f.sampleRate.toInt()
                val marche = java.util.concurrent.atomic.AtomicBoolean(true)
                thread(isDaemon = true, name = "satme-assistant-micro") {
                    val octets = ByteArray(rate / 25 * 2)
                    val pcm = ShortArray(octets.size / 2)
                    while (marche.get()) {
                        val n = runCatching { l.read(octets, 0, octets.size) }.getOrDefault(-1)
                        if (n < 0) break
                        if (n == 0) continue
                        val k = n / 2
                        for (i in 0 until k) pcm[i] = ((octets[2 * i + 1].toInt() shl 8) or (octets[2 * i].toInt() and 0xFF)).toShort()
                        runCatching { recoit(pcm, k, rate) }
                    }
                }
                return { marche.set(false); runCatching { l.stop(); l.close() }; Unit } to ""
            }
            return null to "aucun microphone ne s'ouvre en 16 bits mono" + if (nom.isNotBlank()) " ($nom)" else ""
        }

        /** Says [texte] with « Traduction parlée »'s voice on this PC; false when no voice on the PC could. */
        fun parSynthese(parole: ParolePc, baisse: (Float) -> Unit): (String, String) -> Boolean = { texte, langue ->
            val p = listOf(IntonationProche.Phrase(0.0, 3.0, texte) to texte)
            val r = parole.rends(p, langue, null, ProtocolePhonie.RATE, langue, surPc = true)
            when {
                r.pcm != null -> { baisse(0.25f); try { ParolePc.Lecteur.Systeme.joue(r.pcm, r.rate) } finally { baisse(1f) }; true }
                r.dejaDit -> true
                else -> false
            }
        }
    }
}

/**
 * Pieces of voice cut from the microphone by their level, at 16 kHz: a
 * piece starts when 60 ms are well over the room's noise (followed slowly),
 * ends after 0.7 s of quiet, keeps 0.3 s before its start, lasts 0.4 to 10 s.
 */
class Segmenteur(private val rate: Int = ProtocolePhonie.RATE) {
    private val trame = rate / 50
    private val reste = FloatArray(trame)
    private var nReste = 0
    private var bruit = 0.003f
    private var dans = false
    private var fortes = 0
    private var calmes = 0
    private val avant = ArrayDeque<FloatArray>()
    private val courant = ArrayList<FloatArray>()
    /** 1 (only loud voices) to 10 (whispers): how far over the noise a piece must be. */
    @Volatile var sensibilite = 5

    fun remet() { nReste = 0; dans = false; fortes = 0; calmes = 0; avant.clear(); courant.clear() }

    /** [x] added; the pieces it completes. */
    fun ajoute(x: FloatArray): List<FloatArray> {
        val r = ArrayList<FloatArray>()
        var i = 0
        while (i < x.size) {
            val k = minOf(trame - nReste, x.size - i)
            System.arraycopy(x, i, reste, nReste, k)
            nReste += k; i += k
            if (nReste == trame) { nReste = 0; analyse(reste.copyOf())?.let { r += it } }
        }
        return r
    }

    private fun analyse(f: FloatArray): FloatArray? {
        var s = 0.0
        for (v in f) s += v * v
        val rms = Math.sqrt(s / f.size).toFloat()
        val facteur = 2f + (10 - sensibilite) * 0.6f
        val fort = rms > maxOf(bruit * facteur, 0.004f)
        if (!dans) {
            // The noise followed while nobody speaks: down at once, up slowly.
            bruit = if (rms < bruit) rms.coerceAtLeast(0.0005f) else bruit * 0.995f + rms * 0.005f
            avant.addLast(f); while (avant.size > 15) avant.removeFirst()
            fortes = if (fort) fortes + 1 else 0
            if (fortes >= 3) { dans = true; calmes = 0; courant.clear(); courant.addAll(avant); avant.clear() }
            return null
        }
        courant += f
        calmes = if (rms > maxOf(bruit * facteur * 0.6f, 0.003f)) 0 else calmes + 1
        val duree = courant.size * trame
        if (calmes >= 35 || duree >= rate * 10) {
            dans = false; fortes = 0
            val parole = duree - calmes * trame
            val sortie = if (parole >= rate * 4 / 10) FloatArray(duree).also { o -> courant.forEachIndexed { j, t -> System.arraycopy(t, 0, o, j * trame, trame) } } else null
            courant.clear()
            return sortie
        }
        return null
    }
}

/** From the card's rate to 16 kHz: linear interpolation (enough for speech), shorts to −1…1. */
class Reechantillonneur(val de: Int, private val vers: Int) {
    /** Where the next output sample falls, counted from this block's first sample (−1: the previous block's last). */
    private var pos = 0.0
    private var precedent = 0f
    fun convertit(pcm: ShortArray, n: Int): FloatArray {
        if (de == vers) return FloatArray(n) { pcm[it] / 32768f }
        if (n <= 0) return FloatArray(0)
        fun s(i: Int) = if (i < 0) precedent else pcm[i] / 32768f
        val pas = de.toDouble() / vers
        val r = FloatArray(((n + 1) / pas).toInt() + 2)
        var k = 0
        while (pos < n - 1 && k < r.size) {
            val i = Math.floor(pos).toInt()
            val f = (pos - i).toFloat()
            r[k++] = s(i) + (s(i + 1) - s(i)) * f
            pos += pas
        }
        pos -= n
        precedent = s(n - 1)
        return r.copyOf(k)
    }
}

/** The wake word « SatMe » in a sentence Whisper wrote, and the words after it. */
object MotDeReveil {
    private val FORMES = listOf("satme", "satmi", "satmy", "satmee", "satmie", "sadmi", "sadme", "satemi", "satmei", "satmeh")
    private val AVANT = setOf("ok", "okay", "hey", "he", "eh", "dis", "hello", "bonjour", "allo", "alors", "oh", "hi", "salut", "et")

    /** (found, the rest of the sentence as written). */
    fun cherche(texte: String): Pair<Boolean, String> {
        val mots = texte.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = mots.map { Phrases.cle(it) }
        var i = 0
        while (i < n.size && (n[i].isEmpty() || n[i] in AVANT)) i++
        for (k in 2 downTo 1) {
            if (i + k > n.size) continue
            val joint = n.subList(i, i + k).joinToString("")
            if (joint.length in 4..8 && FORMES.any { Phrases.distance(it, joint) <= if (it.length >= 5) 1 else 0 })
                return true to mots.drop(i + k).joinToString(" ").trimStart(',', '.', '!', '?', ':', ' ', '-')
        }
        return false to texte
    }

    /** What Whisper writes on noise or music (« Sous-titres réalisés par… », « Thanks for watching »). */
    fun parasite(texte: String): Boolean {
        val n = Phrases.normalise(texte)
        return n.isBlank() || listOf("sous titres", "amara", "merci d avoir regarde", "thanks for watching", "subtitles by", "merci de votre attention")
            .any { n.contains(it) }
    }
}
