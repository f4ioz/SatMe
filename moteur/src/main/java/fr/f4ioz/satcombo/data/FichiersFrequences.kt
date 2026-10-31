/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.NomsSatellites
import fr.f4ioz.satcombo.domain.PourquoiRien
import java.util.Locale

/**
 * The satellites' frequencies in the two files other programs keep, read and
 * written from their public descriptions (no code of theirs):
 *
 * **SatPC32's `Doppler.SQF`** — a text file, one line per frequency pair,
 * comma separated: `name, downlink kHz, uplink kHz, downlink mode, uplink
 * mode, NOR|REV, downlink offset Hz, uplink offset Hz, remark` (an uplink of
 * 0: none; newer files may add a CTCSS tone after the remark). One frequency
 * per side: a linear transponder is given by its middle. Lines starting with
 * `;` are comments.
 *
 * **Gpredict's `.trsp`** — one file per satellite, named by its NORAD number
 * (`25544.trsp`), INI style: a `[section]` per transponder (its name), then
 * `UP_LOW`, `UP_HIGH`, `DOWN_LOW`, `DOWN_HIGH` (Hz), `INVERT` (true/false),
 * `MODE`, `BAUD`.
 *
 * Each becomes a [Transmitter] of type [TYPE_SQF] or [TYPE_TRSP], added to
 * the satellite's list after SatNOGS DB's. SQF names are matched to the
 * catalogue the way announcements are ([NomsSatellites]: AO-7 is AO-07,
 * ISS is ISS (ZARYA)); a name with no single match is reported, not guessed.
 */
object FichiersFrequences {

    const val TYPE_SQF = "SatPC32"
    const val TYPE_TRSP = "Gpredict"

    data class LigneSqf(val nom: String, val descenteHz: Long, val monteeHz: Long?, val modeDescente: String, val modeMontee: String,
                        val inverse: Boolean, val decalageRxHz: Long = 0, val decalageTxHz: Long = 0, val remarque: String = "",
                        val ctcss: Double? = null)

    /** A file read: what was understood, and the lines that were not (their number from 1, their text). */
    data class Lecture<T>(val lus: List<T>, val illisibles: List<Pair<Int, String>>)

    /** A description without the « (SatPC32) » / « (Gpredict) » an import added: an export then an import gives it back as it was. */
    fun sansOrigine(d: String): String = d.trim().removeSuffix("($TYPE_SQF)").removeSuffix("($TYPE_TRSP)").trim()

    private fun khz(s: String): Long? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { Math.round(it * 1000) }

    /** `Doppler.SQF` read. */
    fun lisSqf(texte: String): Lecture<LigneSqf> {
        val lus = ArrayList<LigneSqf>()
        val mauvais = ArrayList<Pair<Int, String>>()
        texte.removePrefix("﻿").lines().forEachIndexed { i, brut ->
            val l = brut.trim()
            if (l.isEmpty() || l.startsWith(";") || l.startsWith("#") || l.startsWith("//")) return@forEachIndexed
            val c = l.split(',').map { it.trim() }
            val dl = c.getOrNull(1)?.let(::khz)
            if (c.size < 6 || c[0].isEmpty() || dl == null || dl <= 0) { mauvais += (i + 1) to l; return@forEachIndexed }
            val ul = c.getOrNull(2)?.let(::khz)?.takeIf { it > 0 }
            val sens = c[5].uppercase()
            if (sens != "NOR" && sens != "REV") { mauvais += (i + 1) to l; return@forEachIndexed }
            lus += LigneSqf(c[0], dl, ul, c[3].uppercase(), c[4].uppercase(), sens == "REV",
                c.getOrNull(6)?.toLongOrNull() ?: 0, c.getOrNull(7)?.toLongOrNull() ?: 0, c.getOrNull(8).orEmpty(),
                c.getOrNull(9)?.replace(',', '.')?.toDoubleOrNull())
        }
        return Lecture(lus, mauvais)
    }

    /** The rig mode for a transmitter: the transponder's sideband (USB down; LSB up when inverting), else its mode as heard. */
    private fun modes(t: Transmitter): Pair<String, String> {
        if (t.isTransponder) return "USB" to if (t.invert) "LSB" else "USB"
        val m = PourquoiRien.modeAttendu(PourquoiRien.Voie(0, t.description, t.downlinkLowHz ?: 0L, t.downlinkHighHz, t.mode.orEmpty()))
        // One frequency of an inverting transponder (an SQF line read back): the other sideband going up.
        return m to if (t.invert && m == "USB") "LSB" else if (t.invert && m == "LSB") "USB" else m
    }

    private fun milieu(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null || b == a -> a
        else -> (a + b) / 2
    }

    /** The line for [t] of satellite [nom]; null when it has no downlink. */
    fun ligneSqf(nom: String, t: Transmitter): LigneSqf? {
        val dl = milieu(t.downlinkLowHz, t.downlinkHighHz)?.takeIf { it > 0 } ?: return null
        val (md, mu) = modes(t)
        return LigneSqf(nom.replace(',', ' ').trim(), dl, milieu(t.uplinkLowHz, t.uplinkHighHz), md, mu, t.invert,
            remarque = sansOrigine(t.description).replace(',', ' ').replace(Regex("[\\r\\n]+"), " ").trim().take(60))
    }

    private fun kHzTexte(hz: Long): String = String.format(Locale.US, "%.3f", hz / 1000.0).removeSuffix(".000")

    fun texteSqf(l: LigneSqf): String = listOf(l.nom, kHzTexte(l.descenteHz), l.monteeHz?.let(::kHzTexte) ?: "0", l.modeDescente, l.modeMontee,
        if (l.inverse) "REV" else "NOR", l.decalageRxHz.toString(), l.decalageTxHz.toString(), l.remarque).joinToString(",") +
        (l.ctcss?.let { "," + String.format(Locale.US, "%.1f", it) }.orEmpty())

    /** A `Doppler.SQF` for [sats] (name, its transmitters), Windows line ends as SatPC32 writes them. */
    fun ecritSqf(sats: List<Pair<String, List<Transmitter>>>): String {
        val b = StringBuilder("; Doppler.SQF — SatMe export\r\n; name,downlink kHz,uplink kHz,downlink mode,uplink mode,NOR/REV,RX offset Hz,TX offset Hz,remark\r\n")
        for ((nom, l) in sats) for (t in l) ligneSqf(nom, t)?.let { b.append(texteSqf(it)).append("\r\n") }
        return b.toString()
    }

    /** An SQF line as a transmitter. */
    fun emetteur(l: LigneSqf): Transmitter = Transmitter(
        description = sansOrigine(l.remarque).ifBlank { "${l.modeDescente} ${kHzTexte(l.descenteHz)} kHz" } + " ($TYPE_SQF)",
        mode = l.modeDescente, uplinkLowHz = l.monteeHz, uplinkHighHz = null, downlinkLowHz = l.descenteHz, downlinkHighHz = null,
        invert = l.inverse, alive = true, type = TYPE_SQF)

    // ------------------------------------------------------------ Gpredict

    /** A `.trsp` read: its transponders. */
    fun lisTrsp(texte: String): Lecture<Transmitter> {
        val lus = ArrayList<Transmitter>()
        val mauvais = ArrayList<Pair<Int, String>>()
        var nom: String? = null
        var ligneNom = 0
        val v = HashMap<String, String>()
        fun fin() {
            val n = nom ?: return
            val dl = v["DOWN_LOW"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            val ul = v["UP_LOW"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            if (dl == null && ul == null) { mauvais += ligneNom to "[$n]"; return }
            lus += Transmitter(description = sansOrigine(n) + " ($TYPE_TRSP)", mode = v["MODE"]?.trim()?.takeIf { it.isNotEmpty() },
                uplinkLowHz = ul, uplinkHighHz = v["UP_HIGH"]?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                downlinkLowHz = dl, downlinkHighHz = v["DOWN_HIGH"]?.trim()?.toLongOrNull()?.takeIf { it > 0 },
                invert = v["INVERT"]?.trim()?.equals("true", true) == true, alive = true, type = TYPE_TRSP,
                baud = v["BAUD"]?.trim()?.toDoubleOrNull()?.takeIf { it > 0 })
        }
        texte.removePrefix("﻿").lines().forEachIndexed { i, brut ->
            val l = brut.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) return@forEachIndexed
            if (l.startsWith("[") && l.endsWith("]")) { fin(); nom = l.substring(1, l.length - 1).trim(); ligneNom = i + 1; v.clear(); return@forEachIndexed }
            val k = l.indexOf('=')
            if (k <= 0 || nom == null) { mauvais += (i + 1) to l; return@forEachIndexed }
            v[l.substring(0, k).trim().uppercase()] = l.substring(k + 1)
        }
        fin()
        return Lecture(lus, mauvais)
    }

    /** A `.trsp` for one satellite's transmitters (those with a frequency). */
    fun ecritTrsp(l: List<Transmitter>): String {
        val b = StringBuilder()
        val noms = HashSet<String>()
        for (t in l) {
            if (t.downlinkLowHz == null && t.uplinkLowHz == null) continue
            var n = sansOrigine(t.description).replace('[', '(').replace(']', ')').replace(Regex("[\\r\\n]+"), " ").trim().ifBlank { "Transpondeur" }
            var k = 2
            val base = n
            while (!noms.add(n)) n = "$base $k".also { k++ }
            b.append('[').append(n).append("]\n")
            t.uplinkLowHz?.let { b.append("UP_LOW=").append(it).append('\n') }
            t.uplinkHighHz?.takeIf { it != t.uplinkLowHz }?.let { b.append("UP_HIGH=").append(it).append('\n') }
            t.downlinkLowHz?.let { b.append("DOWN_LOW=").append(it).append('\n') }
            t.downlinkHighHz?.takeIf { it != t.downlinkLowHz }?.let { b.append("DOWN_HIGH=").append(it).append('\n') }
            b.append("INVERT=").append(if (t.invert) "true" else "false").append('\n')
            t.mode?.takeIf { it.isNotBlank() }?.let { b.append("MODE=").append(it.trim()).append('\n') }
            t.baud?.let { b.append("BAUD=").append(if (it == Math.floor(it)) it.toLong().toString() else it.toString()).append('\n') }
            b.append('\n')
        }
        return b.toString()
    }

    /** The NORAD number a `.trsp` file is named by (« 25544.trsp », « 43017.TRSP »), or null. */
    fun numeroDuFichier(nom: String): Int? =
        Regex("(\\d{1,9})\\.trsp$", RegexOption.IGNORE_CASE).find(nom.substringAfterLast('/').substringAfterLast('\\').trim())?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------ matching

    /** An import's outcome: the transmitters per NORAD number, and the SQF names that matched no single satellite (with their line count). */
    data class Import(val parSatellite: Map<Int, List<Transmitter>>, val inconnus: Map<String, Int>, val illisibles: List<Pair<Int, String>>) {
        val nombre: Int get() = parSatellite.values.sumOf { it.size }
    }

    /** SQF lines matched to [catalogue] (name, NORAD number). */
    fun associe(lecture: Lecture<LigneSqf>, catalogue: List<Pair<String, Int>>): Import {
        val par = LinkedHashMap<Int, MutableList<Transmitter>>()
        val inconnus = LinkedHashMap<String, Int>()
        val vus = HashMap<String, Int>()
        for (l in lecture.lus) {
            val n = vus.getOrPut(NomsSatellites.cle(l.nom)) { NomsSatellites.identifie(l.nom, "", catalogue) }
            if (n == 0) { inconnus[l.nom] = (inconnus[l.nom] ?: 0) + 1; continue }
            par.getOrPut(n) { ArrayList() } += emetteur(l)
        }
        return Import(par, inconnus, lecture.illisibles)
    }
}
