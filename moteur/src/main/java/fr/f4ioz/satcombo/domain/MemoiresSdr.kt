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
 * width, a folder (« Satellites FM/ISS », « QO-100 », « Balises »…: one
 * level of sub-folders), a tag, a colour for the flag drawn on the
 * spectrum, a CTCSS tone kept for FM, and whether the scan passes it by.
 * Receive only: a memory tunes the receiver, never a transmitter.
 *
 * Kept by SatMe-PC (memoires-sdr.json), exported and imported as JSON;
 * written here so the phone reads the same file. A version 1 file has no
 * folders: each memory goes into the folder named by its tag (kept).
 */
object MemoiresSdr {

    const val FORMAT = "satme-memoires-sdr"
    const val VERSION = 2
    /** More than anyone keeps; a file beyond is cut. */
    const val MAX = 2000
    const val NOM_MAX = 60
    const val ETIQUETTE_MAX = 40
    const val LARGEUR_MAX_HZ = 24_000
    /** Folders: two levels (« Satellites FM/ISS »), each name this long at most. */
    const val DOSSIER_NIVEAUX = 2
    const val DOSSIER_MAX = 40
    const val DOSSIERS_MAX = 200

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
        /** "Satellites FM/ISS": where it is kept ("": at the top). */
        val dossier: String = "",
        /** The CTCSS tone of an FM relay (Hz, 0: none); kept only, for now. */
        val ctcssHz: Double = 0.0,
        /** When it was made (ms, 0: unknown), for the sort by date. */
        val creeMs: Long = 0L,
        /** Left out of the scan (lockout). */
        val passe: Boolean = false,
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
            couleur = m.couleur.trim().takeIf { COULEUR.matches(it) }?.uppercase().orEmpty(),
            dossier = dossier(m.dossier), ctcssHz = ctcss(m.ctcssHz), creeMs = m.creeMs.coerceAtLeast(0L))
    }

    /** A folder's path made sound: "a / b /c" → "a/b" (two levels, names trimmed). */
    fun dossier(d: String): String = d.split('/').map { it.trim().take(DOSSIER_MAX) }.filter { it.isNotEmpty() }
        .take(DOSSIER_NIVEAUX).joinToString("/")

    /** The folder holding [d] ("" for a top one). */
    fun parent(d: String): String = d.substringBeforeLast('/', "")

    /** Folder [m] is [d] or one of its sub-folders ("" holds them all). */
    fun sous(m: String, d: String): Boolean = d.isEmpty() || m == d || m.startsWith("$d/")

    /** A CTCSS tone within the standard's span (67–254.1 Hz), else none. */
    fun ctcss(hz: Double): Double = if (hz in 60.0..260.0) Math.round(hz * 10) / 10.0 else 0.0

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

    /** [m] put in [l]: replaces the memory of the same id (its date kept), or is added (dated [maintenantMs]). */
    fun pose(l: List<Memoire>, m: Memoire, maintenantMs: Long = System.currentTimeMillis()): List<Memoire> {
        val v = valide(m) ?: return l
        val id = v.id.takeIf { it.isNotEmpty() } ?: nouvelId(l.map { it.id }.toSet(), v.hz)
        val i = l.indexOfFirst { it.id == id }
        val cree = if (v.creeMs > 0) v.creeMs else if (i >= 0 && l[i].creeMs > 0) l[i].creeMs else maintenantMs
        val w = v.copy(id = id, creeMs = cree)
        return nettoie(if (i >= 0) l.toMutableList().also { it[i] = w } else l + w)
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

    // ------------------------------------------------------------------ folders

    /**
     * Every folder: those made by hand ([crees], kept even when empty), those
     * the memories name, and their parents; sorted, a parent before its own.
     */
    fun dossiers(l: List<Memoire>, crees: Collection<String>): List<String> {
        val r = java.util.TreeSet<String>(compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it.replace('/', '\u0001') }.thenBy { it })
        for (d0 in crees.asSequence() + l.asSequence().map { it.dossier }) {
            val d = dossier(d0)
            if (d.isEmpty()) continue
            r += d
            parent(d).takeIf { it.isNotEmpty() }?.let { r += it }
        }
        return r.toList().take(DOSSIERS_MAX)
    }

    /** Folder [ancien] renamed [nouveau]: its sub-folders and memories follow, in [crees] too. */
    fun renommeDossier(l: List<Memoire>, crees: List<String>, ancien: String, nouveau: String): Pair<List<Memoire>, List<String>> {
        val a = dossier(ancien); val n = dossier(nouveau)
        if (a.isEmpty() || n.isEmpty() || a == n) return l to crees
        fun suit(d: String) = if (d.isNotEmpty() && sous(d, a)) dossier(n + d.removePrefix(a)) else d
        return l.map { it.copy(dossier = suit(it.dossier)) } to crees.map { suit(dossier(it)) }.filter { it.isNotEmpty() }.distinct()
    }

    /** Folder [d0] removed with its sub-folders: their memories go to its parent. */
    fun supprimeDossier(l: List<Memoire>, crees: List<String>, d0: String): Pair<List<Memoire>, List<String>> {
        val d = dossier(d0)
        if (d.isEmpty()) return l to crees
        val p = parent(d)
        return l.map { if (it.dossier.isNotEmpty() && sous(it.dossier, d)) it.copy(dossier = p) else it } to
            crees.map { dossier(it) }.filter { !sous(it, d) }
    }

    /** The memories [ids] moved to folder [d]. */
    fun deplace(l: List<Memoire>, ids: Collection<String>, d: String): List<Memoire> {
        val k = dossier(d)
        return l.map { if (it.id in ids) it.copy(dossier = k) else it }
    }

    enum class Tri { NOM, FREQUENCE, BANDE, DATE }

    /** [l] sorted by [t] ([inverse]: the other way); ties by frequency. */
    fun trie(l: List<Memoire>, t: Tri, inverse: Boolean = false): List<Memoire> {
        val c: Comparator<Memoire> = when (t) {
            Tri.NOM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.nom }
            Tri.FREQUENCE -> compareBy { it.hz }
            Tri.BANDE -> compareBy { PileBandes.rang(it.hz) }
            Tri.DATE -> compareBy { it.creeMs }
        }
        val s = l.sortedWith(c.thenBy { it.hz })
        return if (inverse) s.reversed() else s
    }

    /** The memories matching [texte]: in the name, folder, tag or mode, or the frequency in MHz (« 145,8 »). */
    fun cherche(l: List<Memoire>, texte: String): List<Memoire> {
        val t = texte.trim().lowercase()
        if (t.isEmpty()) return l
        val chiffres = t.replace(',', '.').replace(" ", "")
        val numerique = chiffres.isNotEmpty() && chiffres.all { it.isDigit() || it == '.' }
        return l.filter { m ->
            m.nom.lowercase().contains(t) || m.dossier.lowercase().contains(t) || m.etiquette.lowercase().contains(t) ||
                m.mode.lowercase() == t || (numerique && String.format(java.util.Locale.ROOT, "%.6f", m.hz / 1e6).contains(chiffres))
        }
    }

    fun json(m: Memoire): JSONObject = JSONObject().put("id", m.id).put("nom", m.nom).put("hz", m.hz).put("mode", m.mode)
        .put("largeur", m.largeurHz).put("etiquette", m.etiquette).put("couleur", m.couleur)
        .put("dossier", m.dossier).put("ctcss", m.ctcssHz).put("cree", m.creeMs).put("passe", m.passe)

    /** The file's (and the export's) form; [dossiers]: the folders made by hand. */
    fun ecrit(l: List<Memoire>, dossiers: List<String> = emptyList()): JSONObject = JSONObject().put("format", FORMAT).put("version", VERSION)
        .put("dossiers", JSONArray(dossiers)).put("memoires", JSONArray(l.map { json(it) }))

    /** The folders made by hand in a file ("dossiers"), sound; none in a version 1 file. */
    fun litDossiers(texte: String): List<String> {
        val a = runCatching { JSONObject(texte.trim()).optJSONArray("dossiers") }.getOrNull() ?: return emptyList()
        return (0 until minOf(a.length(), DOSSIERS_MAX)).map { dossier(a.optString(it)) }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * A list read from [texte]: the export's object, or a bare array. A
     * frequency in "hz" or "mhz"; the tag in "etiquette" or "tag"; with no
     * folder (a version 1 file), the tag's. What cannot be read is left
     * out; an unreadable text gives an empty list.
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
            val etiquette = o.optString("etiquette", o.optString("tag"))
            r += Memoire(id = o.optString("id"), nom = o.optString("nom", o.optString("name")), hz = hz,
                mode = o.optString("mode"), largeurHz = o.optInt("largeur", o.optInt("bw", 0)),
                etiquette = etiquette, couleur = o.optString("couleur", o.optString("color")),
                dossier = if (o.has("dossier")) o.optString("dossier") else o.optString("folder", etiquette),
                ctcssHz = o.optDouble("ctcss", o.optDouble("tone", 0.0)).takeIf { !it.isNaN() } ?: 0.0,
                creeMs = o.optLong("cree", 0L), passe = o.optBoolean("passe", o.optBoolean("lockout", false)))
        }
        return r
    }
}
