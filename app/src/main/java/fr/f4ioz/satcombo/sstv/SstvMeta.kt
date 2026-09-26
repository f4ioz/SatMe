/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sstv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Ce qu'on sait d'une image reçue, en dehors des pixels.
 *
 * Une image SSTV sans contexte ne vaut pas grand-chose : six mois plus tard,
 * « c'était laquelle, l'ISS ou un relais ? » n'a plus de réponse. Le nom de
 * fichier porte déjà le satellite, l'heure UTC et le mode, et il continue de
 * les porter — c'est le seul renseignement qui survit à une copie vers la
 * galerie du téléphone ou à un envoi par messagerie.
 *
 * Le reste — locator, indicatif, provenance — va dans un petit fichier voisin,
 * une ligne par champ. Pas de JSON : un fichier annexe illisible parce qu'une
 * accolade manque serait pire que pas de fichier du tout, alors qu'une ligne
 * abîmée dans ce format-ci ne coûte que son propre champ.
 *
 * Rien ici ne touche à Android, donc tout se vérifie sur machine.
 */
object SstvMeta {

    data class SstvShot(
        /** Nom du PNG, tel qu'il est sur le disque. */
        val fileName: String,
        /** Satellite écouté au moment de la réception, « ISS » par exemple. */
        val satName: String = "",
        /** Instant de la réception, en millisecondes UTC ; 0 si illisible. */
        val timeMs: Long = 0L,
        /** Mode SSTV décodé, « PD120 », « Robot36 »… */
        val mode: String = "",
        /** Faux quand la trame s'est arrêtée en route (perte de signal). */
        val complete: Boolean = true,
        /** Locator de la station au moment de la réception. */
        val locator: String = "",
        /** Indicatif de la station. */
        val callsign: String = "",
        /** « live » pendant un passage, « file » après redécodage d'un MP3. */
        val source: String = "",
        /** Fichier audio d'origine, quand l'image vient d'un redécodage. */
        val recording: String = "",
        /** Note libre, laissée à l'opérateur. */
        val note: String = ""
    )

    /**
     * Les familles d'images que SatMe archive. Le format du nom et du fichier
     * annexe est le même pour toutes — seule la marque change — parce qu'une
     * image APT et une image SSTV posent exactement la même question six mois
     * plus tard : quel satellite, quand, et depuis où ?
     */
    private val KINDS = setOf("SSTV", "APT")

    /** Nom du fichier annexe correspondant à une image. */
    fun sidecarName(pngName: String): String = pngName.removeSuffix(".png") + ".meta"

    /**
     * Fabrique le nom de fichier d'une image.
     *
     * Le satellite est nettoyé de tout ce qui n'est pas alphanumérique parce
     * qu'un nom comme « ISS (ZARYA) » traverse mal les systèmes de fichiers ;
     * les tirets bas y compris, sinon la relecture ne saurait plus où finit le
     * nom du satellite et où commence la date.
     */
    fun fileName(
        satName: String, timeMs: Long, mode: String, complete: Boolean,
        kind: String = "SSTV"
    ): String {
        val safeKind = if (kind in KINDS) kind else "SSTV"
        val safeSat = satName.replace(Regex("[^A-Za-z0-9-]"), "-")
            .trim('-').ifBlank { "SAT" }
        val safeMode = mode.replace(Regex("[^A-Za-z0-9-]"), "").ifBlank { safeKind }
        return "SatMe_" + safeKind + "_" + safeSat + "_" + stamp(timeMs) + "_" + safeMode +
            (if (complete) "" else "_partiel") + ".png"
    }

    private fun stamp(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd'_'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timeMs))

    private val DATE_RE = Regex("^\\d{8}$")
    private val TIME_RE = Regex("^\\d{6}Z$")

    /**
     * Relit un nom de fichier. La lecture se fait par la fin — mode, puis
     * horodatage — pour qu'un nom de satellite contenant un tiret bas hérité
     * d'une ancienne version ne décale pas tout le reste.
     */
    fun parseName(pngName: String): SstvShot {
        val base = pngName.removeSuffix(".png")
        val parts = base.split("_").toMutableList()
        if (parts.size < 5 || parts[0] != "SatMe" || parts[1] !in KINDS) {
            return SstvShot(fileName = pngName)
        }
        var complete = true
        if (parts.last() == "partiel" || parts.last() == "partial") {
            complete = false
            parts.removeAt(parts.size - 1)
        }
        if (parts.size < 5) return SstvShot(fileName = pngName, complete = complete)
        val mode = parts.removeAt(parts.size - 1)
        val time = parts.removeAt(parts.size - 1)
        val date = parts.removeAt(parts.size - 1)
        if (!DATE_RE.matches(date) || !TIME_RE.matches(time)) {
            return SstvShot(fileName = pngName, complete = complete)
        }
        val sat = parts.drop(2).joinToString("_")
        return SstvShot(
            fileName = pngName,
            satName = sat,
            timeMs = parseStamp(date, time),
            mode = mode,
            complete = complete)
    }

    private fun parseStamp(date: String, time: String): Long = runCatching {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            .apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            .parse(date + time.removeSuffix("Z"))!!.time
    }.getOrDefault(0L)

    // ------------------------------------------------------------ fichier annexe

    /** Le fichier annexe, une ligne « clé=valeur » par renseignement connu. */
    fun encode(shot: SstvShot): String {
        val sb = StringBuilder()
        fun put(k: String, v: String) {
            if (v.isNotBlank()) sb.append(k).append('=')
                .append(v.replace('\n', ' ').replace('\r', ' ').trim()).append('\n')
        }
        put("sat", shot.satName)
        if (shot.timeMs > 0L) put("time", shot.timeMs.toString())
        put("mode", shot.mode)
        put("complete", if (shot.complete) "1" else "0")
        put("locator", shot.locator)
        put("call", shot.callsign)
        put("source", shot.source)
        put("recording", shot.recording)
        put("note", shot.note)
        return sb.toString()
    }

    /**
     * Relit le fichier annexe par-dessus ce que dit déjà le nom de fichier :
     * une image dont l'annexe a été perdue garde son satellite, son heure et
     * son mode, et ne perd que le locator et l'indicatif.
     */
    fun decode(pngName: String, text: String?): SstvShot {
        var shot = parseName(pngName)
        if (text.isNullOrBlank()) return shot
        text.split("\n").forEach { raw ->
            val line = raw.trim()
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            if (v.isBlank()) return@forEach
            shot = when (k) {
                "sat" -> shot.copy(satName = v)
                "time" -> v.toLongOrNull()?.let { shot.copy(timeMs = it) } ?: shot
                "mode" -> shot.copy(mode = v)
                "complete" -> shot.copy(complete = v != "0")
                "locator" -> shot.copy(locator = v)
                "call" -> shot.copy(callsign = v)
                "source" -> shot.copy(source = v)
                "recording" -> shot.copy(recording = v)
                "note" -> shot.copy(note = v)
                else -> shot
            }
        }
        return shot
    }
}
