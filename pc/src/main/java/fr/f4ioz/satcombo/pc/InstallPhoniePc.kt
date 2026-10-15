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
        listOf(python, ecritScript().absolutePath, "--modele", modele, "--modeles", modeles.absolutePath, "--calcul", calcul)

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

    @Volatile var installation = Installation()
        private set
    private val journal = ArrayDeque<String>()

    private fun note(l: String) = synchronized(journal) {
        journal.addLast(l.take(300)); while (journal.size > 80) journal.removeFirst()
        installation = Installation(true, installation.etape, journal.toList(), mo = installation.mo)
    }

    private fun etape(e: String) { installation = Installation(true, e, synchronized(journal) { journal.toList() }); note("— $e") }

    /**
     * Creates `stt/` (when it is not there), installs faster-whisper with
     * pip (with cuBLAS and cuDNN 9 when a NVIDIA card answers), downloads
     * [modele], checks. In a thread; [installation] says how it goes.
     */
    fun installe(modele: String, apres: () -> Unit = {}) {
        if (installation.enCours) return
        synchronized(journal) { journal.clear() }
        installation = Installation(true, "préparation")
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
                    if (j != null && j.has("mo")) { installation = Installation(true, installation.etape, synchronized(journal) { journal.toList() }, mo = j.optDouble("mo")); true }
                    else false
                }
                etape("vérification")
                verification = null
                verifie(py.absolutePath)
                while (verificationEnCours) Thread.sleep(200)
                val v = verification
                if (v == null || !v.optBoolean("ok")) throw IllegalStateException(v?.optString("erreur") ?: "vérification impossible")
                note("faster-whisper ${v.optString("faster_whisper")} · CUDA : ${v.optInt("cuda")} carte(s) · modèles : ${v.optJSONArray("modeles")?.join(", ")?.replace("\"", "")}")
                installation = Installation(false, "installé", synchronized(journal) { journal.toList() }, fini = true)
                apres()
            } catch (e: Exception) {
                installation = Installation(false, installation.etape, synchronized(journal) { journal.toList() }, erreur = e.message ?: e.toString())
            }
        }
    }

    /** Runs [c], each line of its output to the log ([filtre] true: taken, not logged); fails on a non-zero exit. */
    private fun lance(c: List<String>, env: Map<String, String> = environnement(horsLigne = false), filtre: (String) -> Boolean = { false }) {
        note("$ " + c.joinToString(" "))
        val pb = ProcessBuilder(c).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { if (!filtre(it) && it.isNotBlank()) note(it) }
        val code = p.waitFor()
        if (code != 0) throw IllegalStateException("échec (code $code) : " + (synchronized(journal) { journal.lastOrNull { !it.startsWith("$") } } ?: c.first()))
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
    }
}
