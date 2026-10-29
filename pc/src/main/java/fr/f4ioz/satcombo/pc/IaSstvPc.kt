/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.sstv.DebruiteurIa
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The SSTV pictures' learnt denoiser on this PC (« IA », never the
 * default): SCUNet (Zhang et al., 2022; code and weights Apache-2.0,
 * cszn/SCUNet), its « real-PSNR » weights as ONNX, run on the processor by
 * ONNX Runtime for Java (Microsoft, MIT).
 *
 * Nothing of it comes with SatMe-PC: the three files — ONNX Runtime's jar
 * from Maven Central (93 Mo, the processor's native library inside), the
 * model and its weights (77 Mo) from Hugging Face, a revision fixed — are
 * downloaded into `ia/` when « Installer » is clicked, each checked against
 * its SHA-256 before it is kept. ONNX Runtime is then loaded beside SatMe
 * ([Chargeur]); the model stays loaded while used, let go after
 * [INACTIF_MS] without a picture.
 *
 * A picture of 320×256 takes 2 to 3 s on six threads of a desktop
 * processor (measured: 2.0–2.7 s on a 12-thread processor; PD 120's
 * 640×496, 7.5–8.8 s).
 */
class IaSstvPc(dossierSatme: File) : DebruiteurIa {

    val dossier = File(dossierSatme, "ia")

    /** A file to download: its name in [dossier], where from, its size and SHA-256. */
    class Fichier(val nom: String, val url: String, val octets: Long, val sha256: String, val role: String)

    companion object {
        const val VERSION_ORT = "1.20.0"
        /** The model's revision on Hugging Face, fixed: the files checked are those. */
        const val REVISION = "6d11417ee2fbcc73783c502a238ac115097754fe"
        private const val HF = "https://huggingface.co/Heliosoph/scunet-onnx/resolve/$REVISION/"
        const val MODELE = "scunet_color_real_psnr.onnx"

        val FICHIERS = listOf(
            Fichier("onnxruntime-$VERSION_ORT.jar", "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/$VERSION_ORT/onnxruntime-$VERSION_ORT.jar",
                92_987_839L, "c46608681692b3693914defb42fa3119ad7cb6146870581ece247e0ed5793fe8", "ONNX Runtime $VERSION_ORT (Microsoft, MIT)"),
            Fichier(MODELE, HF + MODELE, 3_798_678L, "231be201ab413dbc999d7951caa9844846b93a12a40a41e037d6b5888ed4e88c", "SCUNet real-PSNR, graphe ONNX"),
            Fichier("$MODELE.data", HF + MODELE + ".data", 73_138_176L, "98825ea1210b641c71e5f052f582c70c49fd44b35387ebe2c034268c17df3feb", "SCUNet real-PSNR, poids (Apache-2.0)"),
        )

        const val INACTIF_MS = 10 * 60_000L

        /** The class run inside [Chargeur], with ONNX Runtime. */
        private const val SESSION = "fr.f4ioz.satcombo.pc.OnnxSessionIa"

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    override val nom = "SCUNet real-PSNR"
    override val id = "scunet-real-psnr-" + REVISION.take(8)

    private fun fichier(f: Fichier) = File(dossier, f.nom)

    /** All three files are there, at their size (their checksum was checked when downloaded). */
    fun installe(): Boolean = FICHIERS.all { fichier(it).let { x -> x.isFile && x.length() == it.octets } }

    // ------------------------------------------------------------------ installing

    @Volatile var enCours = false; private set
    @Volatile var etape = ""; private set
    @Volatile var recus = 0L; private set
    @Volatile var erreur = ""; private set

    /** Downloads what is missing, in the background; false when already under way. */
    @Synchronized fun installe(apres: () -> Unit = {}): Boolean {
        if (enCours) return false
        enCours = true; erreur = ""; recus = 0L; etape = ""
        thread(isDaemon = true, name = "satme-ia-installe") {
            try {
                dossier.mkdirs()
                for (f in FICHIERS) {
                    val dest = fichier(f)
                    if (dest.isFile && dest.length() == f.octets) { recus += f.octets; continue }
                    etape = f.role
                    val part = File(dossier, f.nom + ".part")
                    telecharge(f.url, part)
                    etape = f.role + " : vérification"
                    val h = sha256(part)
                    if (h != f.sha256) { part.delete(); throw IllegalStateException(f.nom + " : somme de contrôle fausse ($h)") }
                    if (!part.renameTo(dest)) { dest.delete(); if (!part.renameTo(dest)) throw IllegalStateException(f.nom + " : écriture impossible") }
                }
                etape = "installé"
                apres()
            } catch (e: Exception) {
                erreur = e.message ?: e.javaClass.simpleName
            } finally { enCours = false }
        }
        return true
    }

    private fun telecharge(url: String, dest: File) {
        var u = URI(url).toURL()
        var c: HttpURLConnection
        var sauts = 0
        while (true) {
            c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "SatMe-PC")
            val code = c.responseCode
            if (code in 300..399 && sauts < 6) { u = URI(url).resolve(c.getHeaderField("Location")).toURL(); c.disconnect(); sauts++; continue }
            if (code != 200) throw IllegalStateException("HTTP $code : $url")
            break
        }
        val avant = recus
        c.inputStream.use { i -> dest.outputStream().use { o ->
            val b = ByteArray(1 shl 16); var n = 0L
            while (true) { val k = i.read(b); if (k < 0) break; o.write(b, 0, k); n += k; recus = avant + n }
        } }
    }

    /** The three files taken away; not once the model was loaded in this run (its library is in use: after a restart). */
    @Synchronized fun supprime(): Boolean {
        if (enCours || chargeur != null) return false
        FICHIERS.forEach { fichier(it).delete(); File(dossier, it.nom + ".part").delete() }
        return true
    }

    /** What the page shows: installed, the files and their sizes, the download, the model loaded. */
    fun etat(): JSONObject = JSONObject().put("installe", installe()).put("enCours", enCours).put("etape", etape).put("erreur", erreur)
        .put("recus", recus).put("total", FICHIERS.sumOf { it.octets }).put("modele", nom).put("charge", session != null)
        .put("dernierMs", dernierMs).put("fils", fils())
        .put("fichiers", JSONArray(FICHIERS.map { f -> JSONObject().put("nom", f.nom).put("role", f.role).put("octets", f.octets)
            .put("present", fichier(f).let { it.isFile && it.length() == f.octets }).put("url", f.url) }))

    // ------------------------------------------------------------------ running

    /**
     * Loads ONNX Runtime's classes from its jar, and [SESSION] with them
     * (its bytes read from SatMe's own jar): the rest of SatMe from SatMe.
     */
    private class Chargeur(jar: File, private val parent0: ClassLoader) : URLClassLoader(arrayOf(jar.toURI().toURL()), parent0) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name) ?: when {
                name.startsWith("ai.onnxruntime.") -> findClass(name)
                name == SESSION || name.startsWith("$SESSION$") -> {
                    val b = parent0.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
                    defineClass(name, b, 0, b.size)
                }
                else -> super.loadClass(name, resolve)
            }.also { if (resolve) resolveClass(it) }
        }
    }

    /** The loaded model: one tile at a time (rgb, w, h) → rgb. */
    @Volatile private var session: java.util.function.Function<Array<Any>, FloatArray>? = null
    private var chargeur: Chargeur? = null
    @Volatile private var utiliseMs = 0L
    @Volatile var dernierMs = 0L; private set

    /** Threads for one picture: half the processor's (the other half for the rest of the station), 1 to 8. */
    fun fils(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)

    @Synchronized private fun charge(): java.util.function.Function<Array<Any>, FloatArray> {
        session?.let { return it }
        check(installe()) { "modèle IA non installé" }
        // One loader for the whole run: ONNX Runtime's native library loads once per process.
        val c = chargeur ?: Chargeur(fichier(FICHIERS[0]), IaSstvPc::class.java.classLoader).also { chargeur = it }
        @Suppress("UNCHECKED_CAST")
        val s = c.loadClass(SESSION).getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(fichier(FICHIERS[1]).absolutePath, fils()) as java.util.function.Function<Array<Any>, FloatArray>
        session = s
        // Let go once unused for a while (the weights and ONNX Runtime's memory, a few hundred Mo).
        thread(isDaemon = true, name = "satme-ia-veille") {
            while (session === s) {
                Thread.sleep(30_000L)
                if (System.currentTimeMillis() - utiliseMs > INACTIF_MS) { libere(); break }
            }
        }
        return s
    }

    @Synchronized fun libere() {
        val s = session ?: return
        session = null
        runCatching { (s as AutoCloseable).close() }
    }

    @Synchronized override fun tuile(rgb: FloatArray, w: Int, h: Int): FloatArray {
        val s = charge()
        utiliseMs = System.currentTimeMillis()
        val t0 = System.nanoTime()
        val r = s.apply(arrayOf(rgb, w, h))
        dernierMs = (System.nanoTime() - t0) / 1_000_000
        utiliseMs = System.currentTimeMillis()
        return r
    }
}
