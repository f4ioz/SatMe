/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * What Windows says of a rig's USB devices: a Silicon Labs CP210x bridge
 * (an IC-9700's two COM ports, VID 10C4) or an Icom's sound codec (VID 08BB)
 * plugged in but without a working driver. Pure: the tests give it captured
 * outputs.
 */
object DetectionPiloteUsb {

    /** The CP210x family: CP2102N (EA60), CP2105 (EA70), CP2108 (EA71). */
    private val PONT = Regex("VID_10C4&PID_(EA60|EA70|EA71)", RegexOption.IGNORE_CASE)
    /** The Burr-Brown / TI USB Audio CODEC of an Icom. */
    private val CODEC = Regex("VID_08BB&PID_[0-9A-F]{4}", RegexOption.IGNORE_CASE)

    enum class Type { PONT_SERIE, CODEC_SON }

    /** One device found in error. [code]: Windows' problem code (28: no driver), -1 when not said. */
    data class Peripherique(val instance: String, val nom: String, val type: Type, val code: Int, val statut: String) {
        /** Code 28 (CM_PROB_FAILED_INSTALL), 1 (not configured), 18 (to reinstall): the driver itself is missing. */
        val sansPilote: Boolean get() = code in setOf(1, 18, 28) || code == -1 && type == Type.PONT_SERIE
    }

    /** The device's kind by its instance id (USB\VID_10C4&PID_EA60\…), null when not one of ours. */
    fun type(instance: String): Type? = when {
        !instance.trim().startsWith("USB\\", true) -> null
        PONT.containsMatchIn(instance) -> Type.PONT_SERIE
        CODEC.containsMatchIn(instance) -> Type.CODEC_SON
        else -> null
    }

    /** Problem names as Get-PnpDevice may write them (an enum turned into text). */
    private val CODES = mapOf("CM_PROB_NONE" to 0, "CM_PROB_NOT_CONFIGURED" to 1, "CM_PROB_FAILED_START" to 10,
        "CM_PROB_REINSTALL" to 18, "CM_PROB_FAILED_INSTALL" to 28, "CM_PROB_FAILED_ADD" to 31,
        "CM_PROB_DRIVER_FAILED_LOAD" to 39, "CM_PROB_UNSIGNED_DRIVER" to 52)

    /** A code given as a number (28), a text ("28", "CM_PROB_FAILED_INSTALL") or absent (null). */
    private fun code(v: Any?): Int? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toInt()
        else -> v.toString().trim().let { t -> t.toIntOrNull() ?: CODES[t.uppercase()] ?: if (t.startsWith("CM_PROB_", true)) -1 else null }
    }

    /**
     * A device is in error when Windows gives a problem code, or a status
     * other than OK (« Unknown » alone is not: a device without status yet).
     */
    private fun enErreur(statut: String, code: Int?): Boolean =
        (code != null && code != 0) || (statut.isNotBlank() && !statut.equals("OK", true) && !statut.equals("Unknown", true))

    /**
     * The JSON of `Get-PnpDevice | Select-Object … | ConvertTo-Json`: an
     * array, a single object (one device) or nothing. Names are as Windows
     * gives them (French or English); only the instance id, the status
     * (never translated) and the code are relied on.
     */
    fun depuisJson(texte: String): List<Peripherique> {
        // A byte order mark, a warning or a stray line before the JSON: skipped.
        val t = texte.trim().let { x -> x.indexOfFirst { it == '[' || it == '{' }.let { i -> if (i < 0) "" else x.substring(i) } }
        if (t.isEmpty()) return emptyList()
        val objets = runCatching {
            if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            else listOf(JSONObject(t))
        }.getOrDefault(emptyList())
        return objets.mapNotNull { o ->
            val instance = o.optString("InstanceId").ifBlank { o.optString("DeviceID") }
            val type = type(instance) ?: return@mapNotNull null
            if (o.has("Present") && o.opt("Present") == false) return@mapNotNull null
            val statut = o.optString("Status").takeIf { it != "null" }.orEmpty()
            val c = code(o.opt("Code")) ?: code(o.opt("ConfigManagerErrorCode")) ?: code(o.opt("Problem"))
            if (!enErreur(statut, c)) return@mapNotNull null
            Peripherique(instance.trim(), o.optString("FriendlyName").takeIf { it != "null" }.orEmpty(), type, c ?: -1, statut)
        }.distinctBy { it.instance.uppercase() }
    }

    /**
     * The text of `pnputil /enum-devices /connected /problem` (Windows 10
     * 2004 and later): one block per device, its labels in Windows' language.
     * Only values are read: the instance id (USB\VID_…) and the problem code
     * (« 28 (0x1C) [CM_PROB_FAILED_INSTALL] »). Every device listed has a
     * problem: that is what /problem asks.
     */
    fun depuisPnputil(texte: String): List<Peripherique> {
        val blocs = texte.replace("\r", "").split(Regex("\n\\s*\n"))
        return blocs.mapNotNull { b ->
            val lignes = b.lines()
            val valeurs = lignes.map { it.substringAfter(':', "").trim() }
            val instance = valeurs.firstOrNull { it.startsWith("USB\\", true) } ?: return@mapNotNull null
            val type = type(instance) ?: return@mapNotNull null
            // The problem's line: « 28 (0x1C) [CM_PROB_FAILED_INSTALL] » whatever its label.
            val code = valeurs.firstNotNullOfOrNull { v -> Regex("^(\\d+)\\s*\\(0x[0-9A-F]+\\)", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.toInt() }
                ?: valeurs.firstNotNullOfOrNull { v -> Regex("CM_PROB_[A-Z_]+").find(v)?.value?.let { code(it) } }
                ?: -1
            if (code == 0) return@mapNotNull null
            // The description: the line after the instance's, when it is not an id nor a GUID.
            val i = valeurs.indexOf(instance)
            val nom = valeurs.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("{") && !it.contains('\\') }.orEmpty()
            Peripherique(instance, nom, type, code, "")
        }.distinctBy { it.instance.uppercase() }
    }

    /** What pnputil's exit code means, said for the page. [ok] false: the driver is not in place. */
    data class Bilan(val ok: Boolean, val message: String, val redemarrer: Boolean = false)

    fun bilanInstallation(code: Int): Bilan = when (code) {
        0 -> Bilan(true, "pilote installé")
        3010 -> Bilan(true, "pilote installé : redémarrer Windows pour qu'il prenne effet", redemarrer = true)
        // ERROR_NO_MORE_ITEMS: the package is in the store but no device took it (the rig switched off, unplugged).
        259 -> Bilan(true, "pilote ajouté à Windows, mais aucun appareil ne l'a pris : allumer le poste, rebrancher le câble, puis « Rechercher à nouveau »")
        1223 -> Bilan(false, "installation refusée (fenêtre de contrôle du compte d'utilisateur fermée ou « Non »)")
        else -> Bilan(false, "pnputil a échoué (code $code) : installer le pilote à la main depuis la page de Silicon Labs")
    }

    /** At most this much downloaded or unpacked: the real zip weighs a few megabytes. */
    const val ZIP_MAX = 50L * 1024 * 1024
    private const val DEPLIE_MAX = 100L * 1024 * 1024

    /**
     * Unpacks [zip] into [dossier] (emptied first) after checking it is one:
     * the magic number, no entry outside the folder, a bounded size, an .inf
     * with its .cat beside it. Returns the .inf (silabser.inf preferred);
     * throws with the reason otherwise.
     */
    fun deplie(zip: File, dossier: File): File {
        val tete = zip.inputStream().use { i -> ByteArray(4).also { b -> if (i.read(b) != 4) throw IllegalStateException("fichier vide") } }
        if (!(tete[0] == 'P'.code.toByte() && tete[1] == 'K'.code.toByte() && tete[2] == 3.toByte() && tete[3] == 4.toByte()))
            throw IllegalStateException("ce n'est pas un fichier .zip")
        if (zip.length() > ZIP_MAX) throw IllegalStateException("fichier trop gros pour un pilote")
        dossier.deleteRecursively(); dossier.mkdirs()
        val racine = dossier.canonicalFile
        var total = 0L
        var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (++n > 500) throw IllegalStateException("archive inattendue (trop de fichiers)")
                val f = File(racine, e.name).canonicalFile
                if (!f.path.startsWith(racine.path + File.separator)) throw IllegalStateException("archive refusée (chemin hors du dossier : ${e.name})")
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile.mkdirs()
                f.outputStream().use { o ->
                    val tampon = ByteArray(64 * 1024)
                    while (true) {
                        val k = z.read(tampon)
                        if (k < 0) break
                        total += k
                        if (total > DEPLIE_MAX) throw IllegalStateException("archive trop grosse une fois dépliée")
                        o.write(tampon, 0, k)
                    }
                }
            }
        }
        val infs = racine.walkTopDown().filter { it.isFile && it.name.endsWith(".inf", true) }.toList()
        val avecCat = infs.filter { inf -> inf.parentFile.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".cat", true) } }
        if (infs.isEmpty()) throw IllegalStateException("aucun fichier .inf dans l'archive : ce n'est pas le pilote CP210x pour Windows")
        if (avecCat.isEmpty()) throw IllegalStateException("le .inf est là mais pas son catalogue signé (.cat) : archive incomplète")
        return avecCat.firstOrNull { it.name.equals("silabser.inf", true) } ?: avecCat.minByOrNull { it.path.length }!!
    }
}

/**
 * The rig's USB driver on Windows: finds a CP210x bridge (or an Icom codec)
 * plugged in without its driver and, on the operator's click only,
 * downloads Silicon Labs' official driver and installs it with pnputil,
 * elevated through Windows' own consent prompt (UAC). Nothing is bundled
 * with SatMe; nothing runs on Linux or macOS (cp210x is in their kernels).
 */
class PiloteUsbPc(
    private val dossier: File,
    private val windows: Boolean = OutilsPc.windows,
    /** Runs a command: its exit code and its output, null when it could not run or ran too long. */
    private val executeur: (List<String>, Map<String, String>, Long) -> Pair<Int, String>? = ::execute,
    /** Downloads an address into a file (throws with the reason). */
    private val telecharge: (String, File) -> Unit = ::telechargeHttps,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** Only on Windows, never in the demonstration. */
    val actif: Boolean get() = windows && !ModeDemo.actif

    @Volatile var trouves: List<DetectionPiloteUsb.Peripherique> = emptyList(); private set
    @Volatile private var analyseMs = 0L
    @Volatile var recherche = false; private set

    /** Looks again, in a thread, unless one is running ([force]: even when the last one is recent). */
    fun rafraichis(force: Boolean = false) {
        if (!actif || recherche) return
        if (!force && horloge() - analyseMs < RAFRAICHI_MS) return
        recherche = true
        thread(isDaemon = true, name = "satme-pilote-usb") { try { analyse() } finally { recherche = false } }
    }

    /** One look, here and now: Get-PnpDevice first, pnputil when PowerShell gives nothing readable. */
    fun analyse(): List<DetectionPiloteUsb.Peripherique> {
        if (!actif) return emptyList()
        val ps = executeur(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", PS_DETECTION), emptyMap(), 30)
        val l = if (ps != null && ps.first == 0 && ps.second.trim().let { it.contains('[') || it.contains('{') || it.isEmpty() })
            DetectionPiloteUsb.depuisJson(ps.second)
        else executeur(listOf("pnputil.exe", "/enum-devices", "/connected", "/problem"), emptyMap(), 30)
            ?.let { DetectionPiloteUsb.depuisPnputil(it.second) }.orEmpty()
        trouves = l
        analyseMs = horloge()
        return l
    }

    // ------------------------------------------------------------------ installing

    class Installation(val enCours: Boolean = false, val etape: String = "", val erreur: String = "", val fini: Boolean = false,
                       val message: String = "", val redemarrer: Boolean = false)

    @Volatile var installation = Installation(); private set

    /** Downloads Silicon Labs' driver (the click on « Installer le pilote »), then installs it. */
    fun installe(): String? = commence { zip ->
        installation = Installation(true, "téléchargement du pilote depuis silabs.com")
        telecharge(URL_ZIP, zip)
    }

    /** Installs from a zip the operator downloaded himself (« Choisir le zip téléchargé… »). */
    fun installeZip(octets: ByteArray): String? = commence { zip ->
        installation = Installation(true, "lecture du fichier choisi")
        zip.writeBytes(octets)
    }

    private fun commence(obtiens: (File) -> Unit): String? {
        if (!actif) return "seulement sous Windows"
        if (installation.enCours) return "installation déjà en cours"
        installation = Installation(true, "préparation")
        thread(isDaemon = true, name = "satme-pilote-usb-installe") {
            try {
                val travail = File(dossier, "pilotes").apply { mkdirs() }
                val zip = File(travail, "cp210x.zip").apply { delete() }
                obtiens(zip)
                installation = Installation(true, "vérification de l'archive")
                val inf = DetectionPiloteUsb.deplie(zip, File(travail, "cp210x"))
                installation = Installation(true, "installation (Windows demande l'autorisation : répondre « Oui »)")
                // The .inf's path through the environment: never pasted into the command's text.
                val r = executeur(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", PS_INSTALLE),
                    mapOf("SATME_INF" to inf.absolutePath), 600)
                    ?: throw IllegalStateException("pnputil ne répond pas")
                val b = DetectionPiloteUsb.bilanInstallation(r.first)
                installation = if (b.ok) Installation(false, "fini", fini = true, message = b.message, redemarrer = b.redemarrer)
                    else Installation(false, "installation", erreur = b.message)
                if (b.ok) { Thread.sleep(2000); analyse() }
            } catch (e: Exception) {
                installation = Installation(false, installation.etape, erreur = e.message ?: e.toString())
            }
        }
        return null
    }

    // ------------------------------------------------------------------ the page

    fun json(): JSONObject {
        val o = JSONObject().put("windows", actif)
        if (!actif) return o
        rafraichis()
        val i = installation
        return o.put("recherche", recherche).put("analyse", analyseMs)
            .put("appareils", JSONArray(trouves.map { p ->
                JSONObject().put("instance", p.instance).put("nom", p.nom).put("type", p.type.name).put("code", p.code)
                    .put("sansPilote", p.sansPilote)
            }))
            .put("pont", trouves.any { it.type == DetectionPiloteUsb.Type.PONT_SERIE })
            .put("codec", trouves.any { it.type == DetectionPiloteUsb.Type.CODEC_SON })
            .put("installation", JSONObject().put("enCours", i.enCours).put("etape", i.etape).put("erreur", i.erreur)
                .put("fini", i.fini).put("message", i.message).put("redemarrer", i.redemarrer))
            .put("liens", JSONObject().put("silabs", PAGE_SILABS).put("icom", PAGE_ICOM).put("zip", URL_ZIP))
    }

    companion object {
        /** Silicon Labs' page of its VCP drivers (official). */
        const val PAGE_SILABS = "https://www.silabs.com/developer-tools/usb-to-uart-bridge-vcp-drivers"
        /** Icom's firmware and software page: its « USB Driver (Serial Port) » is Silicon Labs' driver, repackaged. */
        const val PAGE_ICOM = "https://www.icomjapan.com/support/firmware_driver/"
        /** The CP210x Universal Windows Driver, as Silicon Labs' page links it (Windows 10 1803 and later, x64 and ARM64). */
        const val URL_ZIP = "https://www.silabs.com/documents/public/software/CP210x_Universal_Windows_Driver.zip"

        /** A new look at most every so often while the page asks (PowerShell takes a second or two). */
        const val RAFRAICHI_MS = 20_000L

        /** Present USB devices of the CP210x bridges and Icom codecs, always a JSON array, UTF-8. */
        const val PS_DETECTION = "\$ErrorActionPreference='SilentlyContinue'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
            "\$d = @(Get-PnpDevice -PresentOnly | Where-Object { \$_.InstanceId -match '^USB\\\\VID_(10C4|08BB)&' } | " +
            "Select-Object InstanceId,FriendlyName,Status,Class,@{n='Code';e={[int]\$_.ConfigManagerErrorCode}}); " +
            "ConvertTo-Json -InputObject \$d -Compress"

        /**
         * pnputil elevated through UAC; its exit code comes back as ours
         * (1223 when the prompt is refused). The path is read from SATME_INF,
         * quoted with [char]34: no double quote in the command itself (Java
         * would not escape it on Windows' command line).
         */
        const val PS_INSTALLE = "try { \$p = Start-Process -FilePath (Join-Path \$env:SystemRoot 'System32\\pnputil.exe') " +
            "-ArgumentList @('/add-driver', ([char]34 + \$env:SATME_INF + [char]34), '/install') -Verb RunAs -Wait -PassThru -WindowStyle Hidden; " +
            "exit \$p.ExitCode } catch { Write-Output \$_.Exception.Message; exit 1223 }"

        private fun execute(c: List<String>, env: Map<String, String>, secondes: Long): Pair<Int, String>? = runCatching {
            val pb = ProcessBuilder(c).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            p.outputStream.close()
            val sortie = StringBuilder()
            val lecteur = thread(isDaemon = true) { runCatching { sortie.append(p.inputStream.bufferedReader(Charsets.UTF_8).readText()) } }
            if (!p.waitFor(secondes, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
            lecteur.join(2000)
            p.exitValue() to sortie.toString()
        }.getOrNull()

        /** silabs.com only, over HTTPS, redirects followed within it; [DetectionPiloteUsb.ZIP_MAX] at most. */
        private fun telechargeHttps(adresse: String, f: File) {
            val client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20)).build()
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(adresse)).timeout(java.time.Duration.ofMinutes(3))
                .header("User-Agent", "SatMe-PC").GET().build()
            val rep = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            val hote = rep.uri().host.orEmpty()
            rep.body().use { corps ->
                if (rep.uri().scheme != "https" || !(hote == "silabs.com" || hote.endsWith(".silabs.com")))
                    throw IllegalStateException("téléchargement redirigé hors de silabs.com ($hote) : abandonné")
                if (rep.statusCode() != 200) throw IllegalStateException("silabs.com refuse le téléchargement (HTTP ${rep.statusCode()}) : " +
                    "« Ouvrir la page du pilote », télécharger « CP210x Universal Windows Driver », puis « Choisir le zip téléchargé… »")
                f.outputStream().use { o ->
                    val tampon = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val k = corps.read(tampon)
                        if (k < 0) break
                        total += k
                        if (total > DetectionPiloteUsb.ZIP_MAX) throw IllegalStateException("fichier trop gros pour un pilote")
                        o.write(tampon, 0, k)
                    }
                }
            }
        }
    }
}
