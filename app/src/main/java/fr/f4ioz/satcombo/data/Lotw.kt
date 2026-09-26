/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Logbook of the World: grid squares confirmed via satellite.
 *
 * LoTW returns a whole ADIF file, not per-square answers like Wavelog. So it
 * is downloaded **once**, squares are extracted and kept on disk; refresh is
 * manual or after several days — the load is on ARRL's side.
 *
 * Two sets: **confirmed** (counts for awards) and **worked** (in the log,
 * unconfirmed).
 *
 * The LoTW password is the ARRL account password and opens much more than a
 * log. It is stored in settings like the rest: a stopgap the operator should
 * know about, as LoTW offers no read-only token.
 */
object Lotw {

    class Etat(
        val confirmes: Set<String>, val travailles: Set<String>, val quandMs: Long,
        /**
         * Callsigns of satellite contacts **confirmed without a grid square**
         * in LoTW (the other station declared no locator). Most of the gap with
         * Gridmaster's count, which fills them from its own data; the callsign
         * memory can do the same.
         */
        val sansCarre: Set<String> = emptySet(),
        /**
         * **My** squares: where I transmitted from (`MY_GRIDSQUARE`). Not the
         * same as squares contacted, and painted in another colour.
         */
        val activés: Set<String> = emptySet(),
    )

    /** Raw response size, to explain a count that is too low. */
    @Volatile var diag: String = ""

    @Volatile private var cache: Etat? = null

    private fun fichier(ctx: Context) = File(ctx.filesDir, "lotw_carres.txt")

    /** What is already known, without touching the network. */
    fun charge(ctx: Context): Etat {
        cache?.let { return it }
        val f = fichier(ctx)
        val e = if (!f.exists()) Etat(emptySet(), emptySet(), 0L) else runCatching {
            val l = f.readLines()
            Etat(
                l.getOrElse(1) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(2) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(0) { "0" }.toLongOrNull() ?: 0L,
                l.getOrElse(3) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(4) { "" }.split(",").filter { it.isNotBlank() }.toSet())
        }.getOrDefault(Etat(emptySet(), emptySet(), 0L))
        cache = e
        return e
    }

    /**
     * Downloads the log and extracts satellite grid squares. The propagation
     * mode filter is applied here rather than in the query: not every setup
     * accepts it.
     */
    suspend fun rafraichis(ctx: Context, indicatif: String, motDePasse: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                // The other station's grid square only appears in the
                // **confirmation detail**: without `qso_qsl=yes&qso_qsldetail=yes`
                // LoTW returns contacts without locators.
                fun demande(params: String): String {
                    val url = "https://lotw.arrl.org/lotwuser/lotwreport.adi" +
                        "?login=" + URLEncoder.encode(indicatif.trim(), "UTF-8") +
                        "&password=" + URLEncoder.encode(motDePasse, "UTF-8") +
                        "&qso_query=1" + params
                    val co = URL(url).openConnection() as HttpURLConnection
                    co.connectTimeout = 15000
                    // The full report is several MB and takes minutes: a short
                    // timeout silently yields a truncated file.
                    co.readTimeout = 420000
                    co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
                    return co.inputStream.bufferedReader().use { it.readText() }
                }
                // The dates force the full period. **The parameter is
                // `qso_qslsince`**, not `qso_qsorxsince`: without it LoTW
                // defaults to confirmations since yesterday, and says so in the
                // response header ("QSL RX SINCE: … (system supplied default)").
                // The raw files are kept and their size shown: when the count
                // is low, the cause is in what LoTW returned.
                val depuis = "&qso_qslsince=1990-01-01&qso_startdate=1990-01-01"
                // **One request only.** LoTW throttles repeated downloads:
                // asking for two full logs back to back returned a tiny report.
                // The squares are in the confirmations; a second request for
                // all contacts added almost nothing.
                val confirme = runCatching {
                    demande("&qso_qsl=yes&qso_qsldetail=yes&qso_mydetail=yes" + depuis)
                }.getOrDefault("")
                // LoTW announces the record count: fewer read means a
                // truncated file, and we say so.
                val annonce = Regex("<APP_LoTW_NUMREC:\\d+>(\\d+)")
                    .find(confirme)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val lus = Regex("<eor>", RegexOption.IGNORE_CASE).findAll(confirme).count()
                diag = (confirme.length / 1024).toString() + " Ko · " + lus + "/" + annonce +
                    (if (annonce > 0 && lus < annonce) " TRONQUÉ" else "")
                runCatching {
                    File(ctx.getExternalFilesDir(null), "lotw_confirme.adi").writeText(confirme)
                }
                val txt = confirme
                if (!txt.contains("<call:", true)) {
                    return@withContext if (txt.contains("password", true))
                        "identifiants refusés" else "réponse inattendue de LoTW"
                }
                val conf = HashSet<String>()
                val trav = HashSet<String>()
                // Diagnostic counters: a low total may come from the satellite
                // filter, from missing squares (LoTW only knows the other
                // square once confirmed), or a genuinely short log.
                var enregistrements = 0
                var sat = 0
                var satSansCarre = 0
                val orphelins = HashSet<String>()
                val miens = HashSet<String>()
                // One record per <eor>, ADIF fields <name:length>.
                for (bloc in txt.split(Regex("<eor>", RegexOption.IGNORE_CASE))) {
                    if (bloc.contains("<call:", true)) enregistrements++
                    val prop = champ(bloc, "prop_mode")
                    val satName = champ(bloc, "sat_name")
                    // Some satellite contacts carry SAT_NAME without PROP_MODE.
                    if (!prop.equals("SAT", true) && satName.isBlank()) continue
                    sat++
                    // The main square **and** the VUCC squares: a station on a
                    // grid line lists up to four in `VUCC_GRIDS`, all of which count.
                    champ(bloc, "my_gridsquare").uppercase().take(4)
                        .takeIf { it.length == 4 }?.let { miens.add(it) }
                    val g = champ(bloc, "gridsquare").uppercase().take(4)
                    val vucc = champ(bloc, "vucc_grids").uppercase()
                        .replace(" ", "").split(",")
                        .map { it.take(4) }.filter { it.length == 4 }
                    if (g.length < 4 && vucc.isEmpty()) {
                        satSansCarre++
                        champ(bloc, "call").uppercase().takeIf { it.isNotBlank() }
                            ?.let { orphelins.add(it) }
                        continue
                    }
                    val confirmeIci = champ(bloc, "qsl_rcvd").equals("Y", true) ||
                        champ(bloc, "qslrdate").isNotBlank()
                    for (v in vucc) {
                        trav.add(v)
                        if (confirmeIci) conf.add(v)
                    }
                    if (g.length < 4) continue
                    trav.add(g)
                    // QSL_RCVD (or QSLRDATE) confirms when present.
                    if (champ(bloc, "qsl_rcvd").equals("Y", true) ||
                        champ(bloc, "qslrdate").isNotBlank()) conf.add(g)
                }
                val e = Etat(conf, trav, System.currentTimeMillis(), orphelins, miens)
                fichier(ctx).writeText(
                    "${e.quandMs}\n${conf.joinToString(",")}\n${trav.joinToString(",")}" +
                        "\n${orphelins.joinToString(",")}" +
                        "\n${miens.joinToString(",")}")
                cache = e
                "${conf.size} confirmés · ${trav.size} travaillés · " +
                    "$sat sat / $enregistrements contacts · $diag"
            }.getOrElse { "échec : " + it.javaClass.simpleName }
        }

    fun oublie(ctx: Context) {
        cache = null
        runCatching { fichier(ctx).delete() }
    }

    private fun champ(bloc: String, nom: String): String {
        val m = Regex("<$nom:(\\d+)(?::[^>]*)?>", RegexOption.IGNORE_CASE).find(bloc)
            ?: return ""
        val n = m.groupValues[1].toIntOrNull() ?: return ""
        val i = m.range.last + 1
        return bloc.substring(i, minOf(i + n, bloc.length)).trim()
    }
}
