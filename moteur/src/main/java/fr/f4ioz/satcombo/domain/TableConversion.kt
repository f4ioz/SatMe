/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

/**
 * One line of the converter table: a band of the sky, the oscillator that
 * brings it to the device, and which devices it stands in front of.
 *
 * The table generalises the two fixed converters ([Convertisseur], one for
 * receive and one for transmit): a station grows boxes — the dish LNB for the
 * dongle, a down-converter to 2 m for the rig, an up-converter on 13 cm, a
 * 28 MHz transverter for an HF rig — and each one only stands in front of
 * some devices. The dongle hears 739 MHz while the IC-9700 hears 144 MHz from
 * the same LNB: two lines, same sky band, different devices.
 *
 * Frequencies are always given at RF (the sky); the IF is what the device is
 * actually set to.
 */
data class BandeConversion(
    /** What the operator calls it ("QO-100 · LNB 9750 → clé"). */
    val nom: String,
    /** Lower RF bound, Hz (sky side). */
    val basHz: Long,
    /** Upper RF bound, Hz (sky side). */
    val hautHz: Long,
    /** Local oscillator, Hz. */
    val olHz: Long,
    /** High-side injection: IF = LO − RF, the spectrum turned over. */
    val inverse: Boolean = false,
    /** In front of the SDR receiver. */
    val sdr: Boolean = false,
    /** In front of the rig's receive side (CAT). */
    val catRx: Boolean = false,
    /** In front of the rig's transmit side (CAT). */
    val catTx: Boolean = false,
    /** Off: kept in the table, never used. */
    val actif: Boolean = true,
    /**
     * "rx" / "tx": the line kept in step with the QO-100 station chain (the
     * single LO settings from before the table); empty for any other.
     */
    val chaine: String = "",
) {
    /** The IF for [rfHz], whether or not it is inside the band. */
    fun fi(rfHz: Long): Long = if (inverse) olHz - rfHz else rfHz - olHz

    /** The RF for [fiHz]: the same arithmetic backwards. */
    fun rf(fiHz: Long): Long = if (inverse) olHz - fiHz else fiHz + olHz

    /** Can it convert at all: an LO, a band, a positive IF at both ends. */
    val valide: Boolean get() = probleme() == null

    /** Why this line cannot be used (French, shown in the table), or null. */
    fun probleme(): String? = when {
        olHz <= 0L -> "oscillateur manquant"
        basHz <= 0L || hautHz <= 0L -> "bande RF manquante"
        hautHz <= basHz -> "RF haute au-dessous de la basse"
        fi(basHz) <= 0L || fi(hautHz) <= 0L -> "FI négative : oscillateur de l'autre côté (cocher « inversé ») ?"
        else -> null
    }

    /** Does [rfHz] go through this line (enabled, usable, inside the band)? */
    fun couvre(rfHz: Long): Boolean = actif && valide && rfHz in basHz..hautHz

    /** The IF window this line produces (low, high), whatever the injection side. */
    fun fenetreFi(): LongRange {
        val a = fi(basHz); val b = fi(hautHz)
        return minOf(a, b)..maxOf(a, b)
    }

    /** Is [fiHz] one of the IFs this line produces? */
    fun accepteFi(fiHz: Long): Boolean = actif && valide && fiHz in fenetreFi()

    /** In front of [usage]? */
    fun sert(usage: UsageConversion): Boolean = when (usage) {
        UsageConversion.SDR -> sdr
        UsageConversion.CAT_RX -> catRx
        UsageConversion.CAT_TX -> catTx
    }

    /** As the older single converter, for the code that works with one. */
    fun convertisseur(): Convertisseur = Convertisseur(actif = actif && valide, olHz = olHz, inverseur = inverse, basHz = basHz, hautHz = hautHz)
}

/** The three devices a line can stand in front of. */
enum class UsageConversion { SDR, CAT_RX, CAT_TX }

/**
 * The converter table: first line that matches wins.
 *
 * Pure: no settings, no device. [StationPc] reads it from the settings and
 * asks it the IF of each device.
 */
object TableConversion {

    /** The first enabled line covering [rfHz] for [usage], or null: the device then works at RF. */
    fun bande(table: List<BandeConversion>, rfHz: Long, usage: UsageConversion): BandeConversion? =
        table.firstOrNull { it.sert(usage) && it.couvre(rfHz) }

    /** What to set the device to for [rfHz]: the line's IF, else [rfHz] itself. */
    fun versAppareil(table: List<BandeConversion>, rfHz: Long, usage: UsageConversion): Long =
        bande(table, rfHz, usage)?.fi(rfHz) ?: rfHz

    /**
     * What the device's [fiHz] means in the sky.
     *
     * With [rfAttendu] (the satellite followed): only the line covering it is
     * tried, so a LEO dial on 145.9 is never read through the QO-100 2 m
     * converter (that would show 10 490 MHz). Without it, the first line whose
     * IF window holds [fiHz].
     */
    fun depuisAppareil(table: List<BandeConversion>, fiHz: Long, usage: UsageConversion, rfAttendu: Long? = null): Long {
        val b = if (rfAttendu != null) bande(table, rfAttendu, usage)
            else table.firstOrNull { it.sert(usage) && it.accepteFi(fiHz) }
        return if (b != null && b.accepteFi(fiHz)) b.rf(fiHz).takeIf { b.couvre(it) } ?: fiHz else fiHz
    }

    /** Is [fiHz] the IF of some line in front of [usage] (so a device may be sent it)? */
    fun fiConnue(table: List<BandeConversion>, fiHz: Long, usage: UsageConversion): Boolean =
        table.any { it.sert(usage) && it.accepteFi(fiHz) }

    // ------------------------------------------------------------ presets

    private const val MHZ = 1_000_000L

    /** QO-100's narrowband and wideband transponders' downlink, with margin. */
    const val QO100_DL_BAS = 10_489_000_000L
    const val QO100_DL_HAUT = 10_500_000_000L
    /** QO-100's uplink, 13 cm. */
    const val QO100_UL_BAS = 2_400_000_000L
    const val QO100_UL_HAUT = 2_450_000_000L

    /**
     * Starting points. Only the dongle behind the dish LNB is on: it is what
     * any QO-100 receiver does, and it cannot break a LEO (the band stops at
     * 10 489 MHz). The rest is off until ticked — a 28 MHz transverter left
     * on would send a 145.9 MHz ISS pass to 29.9 MHz on an IC-9700.
     */
    val PRESETS: List<BandeConversion> = listOf(
        BandeConversion("QO-100 descente · LNB 9750 → clé SDR", QO100_DL_BAS, QO100_DL_HAUT, 9_750 * MHZ, sdr = true),
        BandeConversion("QO-100 descente · LNB 9750 + convertisseur → 2 m (poste)", QO100_DL_BAS, QO100_DL_HAUT, 10_345 * MHZ,
            catRx = true, actif = false),
        BandeConversion("QO-100 descente · LNB 9750 + convertisseur → 70 cm (poste)", QO100_DL_BAS, QO100_DL_HAUT, 10_057_500_000L,
            catRx = true, actif = false),
        BandeConversion("QO-100 montée · transverter 2400 ← 432", QO100_UL_BAS, QO100_UL_HAUT, 1_968 * MHZ, catTx = true, actif = false),
        BandeConversion("QO-100 montée · transverter 2400 ← 144", QO100_UL_BAS, QO100_UL_HAUT, 2_256 * MHZ, catTx = true, actif = false),
        BandeConversion("Transverter 2 m · FI 28 MHz", 144 * MHZ, 146 * MHZ, 116 * MHZ, catRx = true, catTx = true, actif = false),
        BandeConversion("Transverter 70 cm · FI 28 MHz", 432 * MHZ, 434 * MHZ, 404 * MHZ, catRx = true, catTx = true, actif = false),
    )

    // ------------------------------------------------------------ the older settings

    const val CHAINE_RX = "rx"
    const val CHAINE_TX = "tx"
    const val NOM_CHAINE_RX = "Chaîne QO-100 · réception"
    const val NOM_CHAINE_TX = "Chaîne QO-100 · émission"

    /**
     * The two older converters as table lines: the receive one in front of
     * the dongle and/or the rig as it was, the transmit one in front of the
     * rig's TX. A converter never enabled with its default LO gives no line.
     */
    fun lignesChaine(rx: Convertisseur, rxSdr: Boolean, rxCat: Boolean, tx: Convertisseur): List<BandeConversion> {
        val out = mutableListOf<BandeConversion>()
        if (rx.olHz > 0L && rx.actif) out += BandeConversion(NOM_CHAINE_RX, rx.basHz, rx.hautHz, rx.olHz, rx.inverseur,
            sdr = rxSdr, catRx = rxCat, actif = true, chaine = CHAINE_RX)
        if (tx.olHz > 0L && tx.actif) out += BandeConversion(NOM_CHAINE_TX, tx.basHz, tx.hautHz, tx.olHz, tx.inverseur,
            catTx = true, actif = true, chaine = CHAINE_TX)
        return out
    }

    /**
     * The first table, from what was set before it existed: the older lines
     * first (they win, as they did), then the presets — all off when the
     * older ones were in use, so nothing changes by itself.
     */
    fun migre(rx: Convertisseur, rxSdr: Boolean, rxCat: Boolean, tx: Convertisseur): List<BandeConversion> {
        val anciens = lignesChaine(rx, rxSdr, rxCat, tx)
        return if (anciens.isEmpty()) PRESETS else anciens + PRESETS.map { it.copy(actif = false) }
    }

    /**
     * The table after the QO-100 chain was set (its screen, or the page's
     * single LO fields): its lines updated in place, added on top when new,
     * removed when that converter was turned off. Other lines untouched.
     */
    fun avecChaine(table: List<BandeConversion>, rx: Convertisseur, rxSdr: Boolean, rxCat: Boolean, tx: Convertisseur): List<BandeConversion> {
        val nouvelles = lignesChaine(rx, rxSdr, rxCat, tx).associateBy { it.chaine }
        val out = mutableListOf<BandeConversion>()
        val vues = mutableSetOf<String>()
        for (l in table) {
            if (l.chaine.isEmpty()) { out += l; continue }
            vues += l.chaine
            // Its name as the operator may have changed it.
            nouvelles[l.chaine]?.let { out += it.copy(nom = l.nom) }
        }
        val ajout = nouvelles.values.filter { it.chaine !in vues }
        return ajout + out
    }

    // ------------------------------------------------------------ persistence
    //
    // Plain text as [MaterielRx]: one line per band, fields separated by '|',
    // the separator taken out of names.

    private const val SEP = '|'

    fun ecrit(table: List<BandeConversion>): String = table.joinToString("\n") { b ->
        listOf(b.nom.replace(SEP, '/').replace('\n', ' '), b.basHz, b.hautHz, b.olHz, b.inverse, b.sdr, b.catRx, b.catTx, b.actif, b.chaine)
            .joinToString(SEP.toString())
    }

    /** Lines that cannot be read are dropped; null when nothing was stored. */
    fun lit(texte: String?): List<BandeConversion>? {
        if (texte.isNullOrBlank()) return null
        return texte.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { ligne ->
            val p = ligne.split(SEP)
            if (p.size < 9) return@mapNotNull null
            fun b(i: Int) = p[i].trim().equals("true", ignoreCase = true)
            BandeConversion(
                nom = p[0].trim(),
                basHz = p[1].trim().toLongOrNull() ?: return@mapNotNull null,
                hautHz = p[2].trim().toLongOrNull() ?: return@mapNotNull null,
                olHz = p[3].trim().toLongOrNull() ?: return@mapNotNull null,
                inverse = b(4), sdr = b(5), catRx = b(6), catTx = b(7), actif = b(8),
                chaine = p.getOrNull(9)?.trim().orEmpty().takeIf { it == CHAINE_RX || it == CHAINE_TX }.orEmpty())
        }.toList()
    }

    /** At most this many lines: a table, not a database. */
    const val MAX_LIGNES = 40
}
