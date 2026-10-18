/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * The receiver's named frequencies: a name, a sky frequency, a mode, a
 * width, a tag (a folder: « QO-100 », « Satellites FM », « Balises »…) and
 * a colour for the flag drawn on the spectrum. Receive only: a memory
 * tunes the receiver, never a transmitter.
 *
 * Kept by SatMe-PC (memoires-sdr.json), exported and imported as JSON;
 * written here so the phone reads the same file.
 */
object MemoiresSdr {

    const val FORMAT = "satme-memoires-sdr"
    const val VERSION = 1
    /** More than anyone keeps; a file beyond is cut. */
    const val MAX = 2000
    const val NOM_MAX = 60
    const val ETIQUETTE_MAX = 40
    const val LARGEUR_MAX_HZ = 24_000

    /** The modes a memory may ask ("": the receiver's own). CW is heard in USB. */
    val MODES = listOf("", "NFM", "USB", "LSB", "CW", "AM")

    private val ID = Regex("[A-Za-z0-9_-]{1,40}")
    private val COULEUR = Regex("#[0-9A-Fa-f]{6}")

    data class Memoire(
        val id: String,
        val nom: String,
        val hz: Long,
        val mode: String = "",
        /** Receive width (Hz), 0: the mode's own. */
        val largeurHz: Int = 0,
        val etiquette: String = "",
        /** "#RRGGBB", or "" for the tag's colour. */
        val couleur: String = "",
    )

    /** The demodulator's mode for a memory's (CW in USB; "" keeps the receiver's). */
    fun modeSdr(mode: String): String = if (mode == "CW") "USB" else mode

    /** A memory made sound, or null when its frequency is not one (ids fixed by [nettoie] on a list). */
    fun valide(m: Memoire): Memoire? {
        if (m.hz !in 100_000L..300_000_000_000L) return null
        val nom = m.nom.trim().take(NOM_MAX).ifEmpty { (m.hz / 1e6).toString() }
        return m.copy(id = m.id.takeIf { ID.matches(it) }.orEmpty(), nom = nom,
            mode = m.mode.uppercase().let { if (it == "FM") "NFM" else it }.takeIf { it in MODES }.orEmpty(),
            largeurHz = m.largeurHz.coerceIn(0, LARGEUR_MAX_HZ),
            etiquette = m.etiquette.trim().take(ETIQUETTE_MAX),
            couleur = m.couleur.trim().takeIf { COULEUR.matches(it) }?.uppercase().orEmpty())
    }

    /** Every memory sound, each with its own id (a missing or repeated one replaced), by frequency. */
    fun nettoie(l: List<Memoire>): List<Memoire> {
        val vus = HashSet<String>()
        val r = ArrayList<Memoire>()
        for (m0 in l) {
            val m = valide(m0) ?: continue
            var id = m.id
            if (id.isEmpty() || id in vus) id = nouvelId(vus, m.hz)
            vus += id
            r += m.copy(id = id)
            if (r.size >= MAX) break
        }
        return r.sortedWith(compareBy({ it.hz }, { it.nom }))
    }

    /** A new id, not among [pris]. */
    fun nouvelId(pris: Set<String>, graine: Long = 0L): String {
        var k = (graine xor System.nanoTime()).let { if (it < 0) -it else it }
        while (true) {
            val id = "m" + java.lang.Long.toString(k % 2_176_782_336L, 36)
            if (id !in pris) return id
            k = k * 31 + 7
        }
    }

    /** [m] put in [l]: replaces the memory of the same id, or is added. */
    fun pose(l: List<Memoire>, m: Memoire): List<Memoire> {
        val v = valide(m) ?: return l
        val id = v.id.takeIf { it.isNotEmpty() } ?: nouvelId(l.map { it.id }.toSet(), v.hz)
        val i = l.indexOfFirst { it.id == id }
        return nettoie(if (i >= 0) l.toMutableList().also { it[i] = v.copy(id = id) } else l + v.copy(id = id))
    }

    fun retire(l: List<Memoire>, id: String): List<Memoire> = l.filter { it.id != id }

    /**
     * [importees] added to [l]: one of the same id replaces, one of the same
     * frequency and name is already there (not doubled); the others added.
     */
    fun fusionne(l: List<Memoire>, importees: List<Memoire>): List<Memoire> {
        val r = l.toMutableList()
        for (m0 in importees) {
            val m = valide(m0) ?: continue
            val i = if (m.id.isNotEmpty()) r.indexOfFirst { it.id == m.id } else -1
            when {
                i >= 0 -> r[i] = m
                r.any { it.hz == m.hz && it.nom.equals(m.nom, ignoreCase = true) } -> {}
                else -> r += m
            }
        }
        return nettoie(r)
    }

    /** The memory nearest [hz] within [toleranceHz], or null. */
    fun proche(l: List<Memoire>, hz: Long, toleranceHz: Long): Memoire? =
        l.filter { Math.abs(it.hz - hz) <= toleranceHz }.minByOrNull { Math.abs(it.hz - hz) }

    /** The memories between [basHz] and [hautHz] (inclusive), by frequency. */
    fun dans(l: List<Memoire>, basHz: Long, hautHz: Long): List<Memoire> =
        l.filter { it.hz in basHz..hautHz }.sortedBy { it.hz }

    /** The tags used, in alphabetical order (the empty one left out). */
    fun etiquettes(l: List<Memoire>): List<String> =
        l.map { it.etiquette }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun json(m: Memoire): JSONObject = JSONObject().put("id", m.id).put("nom", m.nom).put("hz", m.hz).put("mode", m.mode)
        .put("largeur", m.largeurHz).put("etiquette", m.etiquette).put("couleur", m.couleur)

    /** The file's (and the export's) form. */
    fun ecrit(l: List<Memoire>): JSONObject = JSONObject().put("format", FORMAT).put("version", VERSION)
        .put("memoires", JSONArray(l.map { json(it) }))

    /**
     * A list read from [texte]: the export's object, or a bare array. A
     * frequency in "hz" or "mhz"; the tag in "etiquette" or "tag". What
     * cannot be read is left out; an unreadable text gives an empty list.
     */
    fun lit(texte: String): List<Memoire> {
        val t = texte.trim()
        val a = runCatching { if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("memoires") }.getOrNull() ?: return emptyList()
        val r = ArrayList<Memoire>()
        for (i in 0 until minOf(a.length(), MAX)) {
            val o = a.optJSONObject(i) ?: continue
            val hz = when {
                o.has("hz") -> o.optLong("hz", 0L)
                o.has("mhz") -> Math.round(o.optDouble("mhz", 0.0) * 1e6)
                else -> 0L
            }
            r += Memoire(id = o.optString("id"), nom = o.optString("nom", o.optString("name")), hz = hz,
                mode = o.optString("mode"), largeurHz = o.optInt("largeur", o.optInt("bw", 0)),
                etiquette = o.optString("etiquette", o.optString("tag")), couleur = o.optString("couleur", o.optString("color")))
        }
        return r
    }
}
