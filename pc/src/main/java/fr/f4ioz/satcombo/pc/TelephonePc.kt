/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.sstv.SstvMeta
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * SatMe on the phone, the PC's companion: its passes come to the PC's
 * journal, their contacts to the PC's log (the same operator's), their
 * SSTV pictures to the PC's pictures.
 *
 * - **A pass file** (the phone's "pass file (.zip)"), sent from the page;
 * - **or straight from the phone** on the same network: its journal shared
 *   (the code it shows), its passes listed, those chosen fetched one by one
 *   as the phone packs them.
 *
 * The phone's recordings are MP3 with a spoken header: made WAV here (ffmpeg),
 * the header left out, under the same name (the time it began), as the PC's
 * journal reads its own.
 */
class TelephonePc(
    private val carnet: CarnetPc,
    private val journal: JournalPc,
    private val dossierEnregistrements: File,
    private val dossierSstv: File,
    private val travail: File,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** What one pass file brought. */
    data class Bilan(val satellite: String = "", val debutMs: Long = 0L, val images: Int = 0, val sons: Int = 0,
                     val contacts: Int = 0, val contactsDejaLa: Int = 0, val erreur: String = "")

    /**
     * Opens a pass file: its pictures among the PC's, its recordings made WAV,
     * the pass into the journal (merged if already there), its contacts into
     * the log unless already in it (same callsign within 2 minutes on the
     * same satellite); [dejaEnLigne]: they are in the online log already (the
     * phone sent them), not to be sent again.
     */
    @Synchronized
    fun importeZip(entree: InputStream, dejaEnLigne: Boolean = true): Bilan {
        val tmp = File(travail, "telephone-" + horloge()).apply { mkdirs() }
        try {
            dossierSstv.mkdirs(); dossierEnregistrements.mkdirs()
            val d = JournalPaquet.deballe(entree, mapOf("recordings" to tmp, "sstv" to dossierSstv))
            val e = d.entree ?: return Bilan(erreur = "ce n'est pas un fichier de passage SatMe")
            // The recordings: MP3 from the phone, WAV here.
            val sons = ArrayList<String>()
            for (f in tmp.listFiles { x -> x.name.endsWith(".mp3") }.orEmpty()) {
                val wav = File(dossierEnregistrements, f.name.substringBeforeLast('.') + ".wav")
                if (wav.isFile || enWav(f, wav, annonceMs(f))) sons += wav.name
            }
            journal.rangement.enregistre(e.copy(enregistrements = (e.enregistrements.map { it.substringBeforeLast('.') + ".wav" } + sons)
                .distinct().filter { File(dossierEnregistrements, it).isFile }))
            // The contacts: the phone's are the PC's operator's.
            val (ajoutes, deja) = ajouteContacts(d.contacts, dejaEnLigne)
            val images = d.poses - tmp.listFiles().orEmpty().size
            return Bilan(e.satName, e.debutMs, images.coerceAtLeast(0) / 2, sons.size, ajoutes, deja)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun ajouteContacts(l: List<LogEntry>, dejaEnLigne: Boolean): Pair<Int, Int> {
        if (l.isEmpty()) return 0 to 0
        val presents = carnet.store.load()
        var n = 0; var deja = 0
        for (c in l) {
            val b = Indicatifs.base(c.callsign)
            if (presents.any { Indicatifs.base(it.callsign) == b && Math.abs(it.timeMs - c.timeMs) <= 120_000L && it.catnum == c.catnum }) { deja++; continue }
            carnet.store.add(if (dejaEnLigne) c.copy(envoyeMs = horloge()) else c)
            n++
        }
        if (n > 0) carnet.oublieMemoire()
        return n to deja
    }

    /** The spoken header of a phone recording, from its ".info" (milliseconds), 0 without. */
    private fun annonceMs(mp3: File): Long = runCatching {
        File(mp3.parentFile, mp3.name.substringBeforeLast('.') + ".info").readLines()
            .firstOrNull { it.startsWith("annonce=") }?.substringAfter('=')?.trim()?.toLong() ?: 0L
    }.getOrDefault(0L)

    /** MP3 to WAV (16-bit mono, 22 050 Hz, the PC's) without its first [sauteMs]; its time kept. */
    private fun enWav(mp3: File, wav: File, sauteMs: Long): Boolean {
        val ff = RenduPc.ffmpeg() ?: return false
        val p = ProcessBuilder(ff, "-y", "-loglevel", "error", "-ss", "%.3f".format(java.util.Locale.US, sauteMs / 1000.0), "-i", mp3.path,
            "-ac", "1", "-ar", "22050", "-c:a", "pcm_s16le", wav.path).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        val ok = p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 && wav.length() > 44
        if (!ok) wav.delete() else wav.setLastModified(mp3.lastModified())
        return ok
    }

    // ------------------------------------------------------------ straight from the phone

    /** The phone reached, and the session it gave for the code. */
    @Volatile var adresse = ""; private set
    @Volatile private var cle = ""
    @Volatile var version = ""; private set

    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun lit(chemin: String): String {
        val r = client.newCall(Request.Builder().url("http://$adresse$chemin").build()).execute()
        r.use { if (!it.isSuccessful) throw java.io.IOException("le téléphone répond " + it.code); return it.body?.string().orEmpty() }
    }

    /** "host:port/c/token", from the desk's address as the phone shows it (the port 8080 when left out). */
    private fun base(a: String): String {
        val t = a.trim().removePrefix("http://").trimEnd('/')
        val hote = t.substringBefore('/')
        return (if (':' in hote) hote else "$hote:8080") + t.substring(hote.length)
    }

    /**
     * Joins the phone at [adr] — the control desk's address it shows
     * ("http://192.168.1.23:8080/c/…/") — with the code it shows; null when
     * done, else why not.
     */
    fun connecte(adr: String, code: String): String? {
        adresse = base(adr); cle = ""
        return runCatching {
            val api = JSONObject(lit("/api"))
            version = api.optString("satme")
            val j = JSONObject(lit("/entrer?code=" + code.trim()))
            if (!j.optBoolean("ok")) return "code refusé : celui affiché par le téléphone sous « Poste de commande sur PC »"
            cle = j.getString("cle")
            null
        }.getOrElse { "téléphone injoignable sur $adresse (même Wi-Fi ? « Poste de commande sur PC » ouvert ? l'adresse entière, avec /c/…)" }
    }

    val connecte: Boolean get() = cle.isNotEmpty()

    /** The phone's journal, newest first, as its page lists it (JSON). */
    fun liste(): JSONObject = JSONObject(lit("/journal/liste?cle=$cle"))

    /** A fetch under way, for the page: how far, what each pass brought. */
    data class Rapatriement(val enCours: Boolean = false, val fait: Int = 0, val total: Int = 0, val bilans: List<Bilan> = emptyList())
    @Volatile var rapatriement = Rapatriement(); private set

    /** [rapatrie] on a thread of its own; false when one is already running. */
    fun lanceRapatriement(ids: List<String>, dejaEnLigne: Boolean): Boolean {
        if (rapatriement.enCours || !connecte || ids.isEmpty()) return false
        rapatriement = Rapatriement(true, 0, ids.size)
        kotlin.concurrent.thread(isDaemon = true, name = "satme-telephone") {
            val l = ArrayList<Bilan>()
            runCatching { rapatrie(ids, dejaEnLigne) { i, b -> b?.let { l += it }; rapatriement = Rapatriement(true, i, ids.size, l.toList()) } }
            rapatriement = Rapatriement(false, ids.size, ids.size, l.toList())
        }
        return true
    }

    /**
     * Fetches the passes [ids] from the phone (each packed there with its
     * contacts), one after the other, into the PC; [progres] says which.
     */
    fun rapatrie(ids: List<String>, dejaEnLigne: Boolean, progres: (Int, Bilan?) -> Unit): List<Bilan> = ids.mapIndexed { i, id ->
        val b = runCatching {
            val n = JSONObject(lit("/journal/fabrique?cle=$cle&quoi=paquet&contacts=1&id=" + java.net.URLEncoder.encode(id, "UTF-8"))).getString("travail")
            val fin = horloge() + 300_000L
            while (true) {
                val t = JSONObject(lit("/journal/travail?cle=$cle&n=$n"))
                if (t.optBoolean("fini")) { if (!t.optBoolean("ok")) throw java.io.IOException("le téléphone n'a pas pu faire le fichier"); break }
                if (horloge() > fin) throw java.io.IOException("trop long")
                Thread.sleep(400)
            }
            client.newCall(Request.Builder().url("http://$adresse/journal/fichier?cle=$cle&n=$n").build()).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("le téléphone répond " + r.code)
                importeZip(r.body!!.byteStream(), dejaEnLigne)
            }
        }.getOrElse { Bilan(erreur = it.message ?: "échec") }
        progres(i + 1, b)
        b
    }
}
