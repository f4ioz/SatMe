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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The speech-to-text engine on this PC: a Python environment of its own in
 * SatMe's folder (`stt/`), faster-whisper in it (and NVIDIA's libraries when
 * a GeForce is there), the model chosen in `stt/modeles/`. Everything is
 * downloaded only when the user clicks « Installer le moteur ».
 */
class InstallPhoniePc(val dossier: File, private val windows: Boolean = OutilsPc.windows) {

    val venv = File(dossier, "stt")
    val modeles = File(venv, "modeles")
    val script = File(venv, "satme_phonie.py")
    /** The translation's language pairs (Argos Translate's packages, unpacked). */
    val traduction = File(venv, "traduction")

    /** The environment's interpreter (bin/python, Scripts\python.exe on Windows). */
    fun pythonVenv(): File = if (windows) File(venv, "Scripts/python.exe") else File(venv, "bin/python")

    /** The interpreter to use: the one given in the settings, else the environment's when it exists. */
    fun python(choisi: String): String? = choisi.trim().takeIf { it.isNotEmpty() } ?: pythonVenv().takeIf { it.isFile }?.absolutePath

    /** The helper written next to the environment (again when SatMe-PC brings a new one). */
    fun ecritScript(): File {
        val contenu = InstallPhoniePc::class.java.getResourceAsStream("/phonie/satme_phonie.py")?.use { it.readBytes() }
            ?: throw IllegalStateException("satme_phonie.py manque dans SatMe-PC")
        venv.mkdirs()
        if (!script.isFile || !script.readBytes().contentEquals(contenu)) script.writeBytes(contenu)
        return script
    }

    /** What the helper's process needs: UTF-8, no buffering, never the network. */
    fun environnement(horsLigne: Boolean = true): Map<String, String> = buildMap {
        put("PYTHONIOENCODING", "utf-8"); put("PYTHONUNBUFFERED", "1"); put("HF_HUB_DISABLE_TELEMETRY", "1")
        if (horsLigne) { put("HF_HUB_OFFLINE", "1"); put("TRANSFORMERS_OFFLINE", "1") }
    }

    fun commande(python: String, modele: String, calcul: String): List<String> =
        listOf(python, ecritScript().absolutePath, "--modele", modele, "--modeles", modeles.absolutePath, "--calcul", calcul,
            "--traduction", traduction.absolutePath)

    // ------------------------------------------------------------------ what is there

    /** The NVIDIA card (name, memory), "" when none or no driver. */
    @Volatile var gpu: String? = null
        private set

    fun detecteGpu(): String = gpu ?: (executeCourt(listOf("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"), 5)
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()).also { gpu = it }

    /** A Python 3.9+ to create the environment with: its command and its version. */
    fun pythonSysteme(): Pair<List<String>, String>? {
        val essais = if (windows) listOf(listOf("py", "-3"), listOf("python"), listOf("python3")) else listOf(listOf("python3"), listOf("python"))
        for (c in essais) {
            val v = executeCourt(c + "--version", 10)?.trim() ?: continue
            val m = Regex("Python (\\d+)\\.(\\d+)").find(v) ?: continue
            val (a, b) = m.destructured
            if (a.toInt() == 3 && b.toInt() >= 9) return c to v
        }
        return null
    }

    /** The last check (`--verifie`): faster-whisper's version, CUDA, the models present; null before one. */
    @Volatile var verification: JSONObject? = null
        private set
    @Volatile var verificationEnCours = false
        private set

    fun verifie(python: String?) {
        if (verificationEnCours) return
        if (python == null) { verification = JSONObject().put("ok", false).put("erreur", "moteur non installé"); return }
        verificationEnCours = true
        thread(isDaemon = true, name = "satme-phonie-verifie") {
            try {
                val sortie = executeCourt(listOf(python, ecritScript().absolutePath, "--verifie", "--modeles", modeles.absolutePath), 120, environnement())
                verification = sortie?.lineSequence()?.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }?.lastOrNull()
                    ?: JSONObject().put("ok", false).put("erreur", "le moteur ne répond pas (Python : $python)")
            } finally { verificationEnCours = false }
        }
    }

    // ------------------------------------------------------------------ installing

    class Installation(val enCours: Boolean = false, val etape: String = "", val lignes: List<String> = emptyList(),
                       val erreur: String = "", val fini: Boolean = false, val mo: Double = 0.0)

    /** One installation's log and state (the engine's, the translation's). */
    private inner class Suivi {
        @Volatile var etat = Installation()
        private val journal = ArrayDeque<String>()
        private fun lignes() = synchronized(journal) { journal.toList() }

        fun commence(): Boolean {
            if (etat.enCours) return false
            synchronized(journal) { journal.clear() }
            etat = Installation(true, "préparation")
            return true
        }
        fun note(l: String) = synchronized(journal) {
            journal.addLast(l.take(300)); while (journal.size > 80) journal.removeFirst()
            etat = Installation(true, etat.etape, journal.toList(), mo = etat.mo)
        }
        fun etape(e: String) { etat = Installation(true, e, lignes()); note("— $e") }
        fun mo(m: Double) { etat = Installation(true, etat.etape, lignes(), mo = m) }
        fun fini() { etat = Installation(false, "installé", lignes(), fini = true) }
        fun echec(e: Exception) { etat = Installation(false, etat.etape, lignes(), erreur = e.message ?: e.toString()) }

        /** Runs [c], each line of its output to the log ([filtre] true: taken, not logged); fails on a non-zero exit. */
        fun lance(c: List<String>, env: Map<String, String> = environnement(horsLigne = false), filtre: (String) -> Boolean = { false }) {
            note("$ " + c.joinToString(" "))
            val pb = ProcessBuilder(c).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            p.outputStream.close()
            p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { if (!filtre(it) && it.isNotBlank()) note(it) }
            val code = p.waitFor()
            if (code != 0) throw IllegalStateException("échec (code $code) : " + (synchronized(journal) { journal.lastOrNull { !it.startsWith("$") } } ?: c.first()))
        }
    }

    private val suivi = Suivi()
    private val suiviTraduction = Suivi()

    /** The engine's installation. */
    val installation: Installation get() = suivi.etat
    /** The translation's installation. */
    val installationTraduction: Installation get() = suiviTraduction.etat

    private fun note(l: String) = suivi.note(l)
    private fun etape(e: String) = suivi.etape(e)
    private fun lance(c: List<String>, env: Map<String, String> = environnement(horsLigne = false), filtre: (String) -> Boolean = { false }) = suivi.lance(c, env, filtre)

    /**
     * Creates `stt/` (when it is not there), installs faster-whisper with
     * pip (with cuBLAS and cuDNN 9 when a NVIDIA card answers), downloads
     * [modele], checks. In a thread; [installation] says how it goes.
     */
    fun installe(modele: String, apres: () -> Unit = {}) {
        if (!suivi.commence()) return
        thread(isDaemon = true, name = "satme-phonie-installe") {
            try {
                val py = pythonVenv()
                if (!py.isFile) {
                    etape("création de l'environnement Python (${venv.absolutePath})")
                    val (sys, version) = pythonSysteme() ?: throw IllegalStateException(
                        if (windows) "Python 3.9 ou plus récent introuvable : l'installer depuis python.org (cocher « Add python.exe to PATH »)"
                        else "Python 3.9 ou plus récent introuvable : installer python3 et python3-venv (sudo apt install python3-venv)")
                    note(version)
                    lance(sys + listOf("-m", "venv", venv.absolutePath))
                    if (!py.isFile) throw IllegalStateException("l'environnement n'a pas été créé (sous Debian/Ubuntu : sudo apt install python3-venv)")
                }
                val paquets = mutableListOf("faster-whisper")
                val carte = detecteGpu()
                if (carte.isNotEmpty() && !System.getProperty("os.name").orEmpty().startsWith("Mac")) paquets += listOf("nvidia-cublas-cu12", "nvidia-cudnn-cu12==9.*")
                etape("installation de " + paquets.joinToString(", ") + if (carte.isNotEmpty()) " (carte : $carte)" else " (pas de carte NVIDIA : calcul sur le processeur)")
                lance(listOf(py.absolutePath, "-m", "pip", "install", "--disable-pip-version-check", "--progress-bar", "off") + paquets)
                etape("téléchargement du modèle « $modele » (${TAILLES[modele] ?: "?"})")
                modeles.mkdirs()
                lance(listOf(py.absolutePath, ecritScript().absolutePath, "--telecharge", "--modele", modele, "--modeles", modeles.absolutePath),
                    environnement(horsLigne = false)) { l ->
                    val j = runCatching { JSONObject(l) }.getOrNull()
                    if (j != null && j.has("mo")) { suivi.mo(j.optDouble("mo")); true }
                    else false
                }
                etape("vérification")
                verification = null
                verifie(py.absolutePath)
                while (verificationEnCours) Thread.sleep(200)
                val v = verification
                if (v == null || !v.optBoolean("ok")) throw IllegalStateException(v?.optString("erreur") ?: "vérification impossible")
                note("faster-whisper ${v.optString("faster_whisper")} · CUDA : ${v.optInt("cuda")} carte(s) · modèles : ${v.optJSONArray("modeles")?.join(", ")?.replace("\"", "")}")
                suivi.fini()
                apres()
            } catch (e: Exception) {
                suivi.echec(e)
            }
        }
    }

    // ------------------------------------------------------------------ translation

    /** The language pairs unpacked (`pl_en`, `en_fr`…), read from the folder. */
    fun pairesInstallees(): List<String> = (traduction.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.matches(Regex("[a-z]{2,3}_[a-z]{2,3}")) && File(it, "model/model.bin").isFile && File(it, "sentencepiece.model").isFile }
        .map { it.name }.sorted()

    /**
     * Installs the offline translation: sentencepiece with pip in the
     * engine's environment, then the [paires] from Argos Translate's index
     * into `stt/traduction/`. In a thread; [installationTraduction] says how it goes.
     */
    fun installeTraduction(python: String?, paires: List<String>, apres: () -> Unit = {}) {
        if (!suiviTraduction.commence()) return
        thread(isDaemon = true, name = "satme-phonie-traduction") {
            val s = suiviTraduction
            try {
                val py = python ?: throw IllegalStateException("installer d'abord le moteur (bouton « Installer le moteur »)")
                s.etape("installation de sentencepiece (le reste est déjà là avec faster-whisper)")
                s.lance(listOf(py, "-m", "pip", "install", "--disable-pip-version-check", "--progress-bar", "off", "sentencepiece"))
                val manquent = paires.filter { it !in pairesInstallees() }
                if (manquent.isNotEmpty()) {
                    s.etape("téléchargement de " + manquent.joinToString(", ") { it.replace("_", " → ") } +
                        " (environ ${manquent.sumOf { TAILLES_PAIRES[it] ?: 80 }} Mo)")
                    traduction.mkdirs()
                    s.lance(listOf(py, ecritScript().absolutePath, "--installe-traduction", "--paires", manquent.joinToString(","),
                        "--modeles", modeles.absolutePath, "--traduction", traduction.absolutePath), environnement(horsLigne = false)) { l ->
                        val j = runCatching { JSONObject(l) }.getOrNull() ?: return@lance false
                        when {
                            j.has("erreur") -> { s.note(j.optString("erreur")); true }
                            j.has("progres") -> { s.mo(j.optDouble("mo")); true }
                            j.has("paire") -> { s.note(j.optString("paire").replace("_", " → ") + " : installée (${j.optDouble("mo")} Mo)"); true }
                            j.optBoolean("fini") -> {
                                val absentes = j.optJSONArray("absentes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                                if (absentes.isNotEmpty()) s.note("non proposées par l'index : " + absentes.joinToString(", ") { it.replace("_", " → ") })
                                true
                            }
                            else -> false
                        }
                    }
                }
                s.note("paires présentes : " + pairesInstallees().joinToString(", ") { it.replace("_", " → ") })
                s.fini()
                apres()
            } catch (e: Exception) {
                s.echec(e)
            }
        }
    }

    /** Removes one pair ("pl_en"). */
    fun retirePaire(paire: String): Boolean {
        if (!paire.matches(Regex("[a-z]{2,3}_[a-z]{2,3}"))) return false
        return File(traduction, paire).takeIf { it.isDirectory }?.deleteRecursively() ?: false
    }

    private fun executeCourt(c: List<String>, secondes: Long, env: Map<String, String> = emptyMap()): String? = runCatching {
        val pb = ProcessBuilder(c)
        pb.environment().putAll(env)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        p.outputStream.close()
        val sortie = StringBuilder()
        val lecteur = thread(isDaemon = true) { runCatching { sortie.append(p.inputStream.bufferedReader(Charsets.UTF_8).readText()) } }
        if (!p.waitFor(secondes, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
        lecteur.join(2000)
        if (p.exitValue() != 0) null else sortie.toString()
    }.getOrNull()

    companion object {
        /** The models offered, and what each weighs once downloaded. */
        val TAILLES = linkedMapOf("large-v3" to "3,1 Go", "turbo" to "1,6 Go", "medium" to "1,5 Go", "small" to "0,5 Go")

        /** The languages heard on QO-100 that can be translated, in French. */
        val LANGUES = linkedMapOf("pl" to "polonais", "de" to "allemand", "en" to "anglais", "es" to "espagnol", "it" to "italien",
            "nl" to "néerlandais", "pt" to "portugais", "ru" to "russe", "uk" to "ukrainien", "cs" to "tchèque", "fr" to "français")
        /** The languages a translation can be written in. */
        val CIBLES = linkedMapOf("fr" to "français", "en" to "anglais", "de" to "allemand", "es" to "espagnol", "it" to "italien",
            "nl" to "néerlandais", "pl" to "polonais", "pt" to "portugais")
        /** Each pair's download (Mo, Argos Translate's index, October 2026); about 15 % more once unpacked. */
        val TAILLES_PAIRES = mapOf(
            "pl_en" to 72, "de_en" to 151, "es_en" to 285, "it_en" to 87, "nl_en" to 70, "pt_en" to 69, "ru_en" to 156,
            "uk_en" to 76, "cs_en" to 71, "fr_en" to 67,
            "en_fr" to 65, "en_de" to 151, "en_es" to 88, "en_it" to 88, "en_nl" to 72, "en_pl" to 67, "en_pt" to 66)

        /** The pairs to translate [langues] into [cible]: each through English (the index has no direct pair between two others). */
        fun pairesPour(langues: Collection<String>, cible: String): List<String> = langues.filter { it != cible }.flatMap { l ->
            when {
                l == "en" -> listOf("en_$cible")
                cible == "en" -> listOf("${l}_en")
                else -> listOf("${l}_en", "en_$cible")
            }
        }.distinct()
    }
}
