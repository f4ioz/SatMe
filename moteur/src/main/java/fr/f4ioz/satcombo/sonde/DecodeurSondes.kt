/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sonde

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Radiosonde decoding from the dongle's discriminator output, for a station
 * without the phone (the PC): the phone's SondeHub, its rules unchanged, its
 * log in a folder given here instead of the app's files.
 *
 * One decoder per family on the same stream: RS41 at 4800 baud, M20 plain
 * FSK at 9600 baud, M10 biphase at 9616 chips/s. **M20 and M10 must not share
 * a demodulator:** tuned to M10 half-bits, the M20 is read as two identical
 * symbols per bit and every frame is thrown away. Naming the model turns the
 * others off (and narrows the dongle's filter, a few dB on a distant sonde).
 * The audio must come without de-emphasis: it rounds the edges, and nothing
 * decodes.
 */
class DecodeurSondes(
    /** Where the CSV logs and exports go; null: no log. */
    private val dossier: File?,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    data class Etat(
        val actif: Boolean = false,
        /** Frames kept since the start, and those thrown away (tuning is right when few are). */
        val trames: Int = 0,
        val rejetees: Int = 0,
        val derniere: SondeFrame? = null,
        val serie: String = "",
        val type: String = "",
        val journal: String? = null,
        /** The discriminator's swing, 0..100. */
        val excursion: Int = 0,
        val modele: String = SondeModel.AUTO,
    )

    @Volatile var etat = Etat(); private set

    private var rs41: SondeDemod? = null
    private var m10: SondeDemod? = null
    private var m20: SondeDemod? = null
    private val m10Trame = ByteArray(Meteomodem.M10_LEN)
    private var freqHz = 0L
    private var journal: File? = null
    private var trames = 0
    private var rejetees = 0
    private var dernierEtatMs = 0L

    /** The flight followed (a new serial starts another one). */
    @Volatile var vol: SondeFlight? = null; private set

    /** Arms decoding for audio at [rate] (the chain's 44 100 Hz), on [freq] (written in every frame). */
    @Synchronized fun demarre(rate: Int, freq: Long, modele: String = SondeModel.AUTO) {
        freqHz = freq
        val r = rate.toDouble()
        rs41 = if (SondeModel.wantsRs41(modele)) SondeDemod(r, Rs41.BAUD, 2048) else null
        m20 = if (SondeModel.wantsM20(modele)) SondeDemod(r, Meteomodem.M20_BAUD, 2048) else null
        // M10: biphase mark, the demodulator counts chips (9616/s, not the 4808 bit/s of the payload).
        m10 = if (SondeModel.wantsM10(modele)) SondeDemod(r, Meteomodem.M10_CHIP_RATE, 2048) else null
        trames = 0; rejetees = 0; vol = null
        // The log opens with the first frame: hours of listening to nothing leave no empty file.
        journal = null
        etat = Etat(actif = true, modele = modele)
    }

    @Synchronized fun arrete() {
        rs41 = null; m10 = null; m20 = null
        etat = etat.copy(actif = false)
    }

    /** The frequency changed while running (a step, a site chosen): the next frames say so. */
    fun frequence(hz: Long) { freqHz = hz }

    /** Forgets the flight followed, decoding goes on. */
    @Synchronized fun oublieVol() {
        vol = null; trames = 0; rejetees = 0
        etat = etat.copy(trames = 0, rejetees = 0, derniere = null, serie = "", type = "")
    }

    /** A block of demodulated audio. */
    @Synchronized fun ecoute(pcm: ShortArray, n: Int) {
        if (!etat.actif || n <= 0) return
        val maintenant = horloge()

        rs41?.let { a ->
            a.feedBits(pcm, n, null)
            if (a.bitsAvailable >= Rs41.LEN_STD * 8 + 64) {
                var trouve = false
                // Byte alignment unknown: the eight offsets (seven fail at once on the header).
                for (off in 0 until 8) {
                    val k = a.packBytes(off)
                    val t = Rs41.scan(a.bytes, 0, k, freqHz, maintenant) ?: continue
                    accepte(t.frame); trouve = true; break
                }
                if (!trouve) rejetees++
                a.trimTo(Rs41.LEN_STD * 8)
            }
        }
        m20?.let { d ->
            d.feedBits(pcm, n, null)
            if (d.bitsAvailable >= Meteomodem.M20_LEN * 8 + 128) {
                val chips = d.chipsCopy()
                for (off in 0 until 8) {
                    val k = d.packBytesMsb(chips, chips.size, off)
                    val t = Meteomodem.scanM20(d.bytes, 0, k, freqHz, maintenant) ?: continue
                    accepte(t.frame); break
                }
                d.trimTo(Meteomodem.M20_LEN * 8)
            }
        }
        m10?.let { b ->
            b.feedBits(pcm, n, null)
            val besoin = Meteomodem.M10_LEN * 16 + Meteomodem.M10_SYNC.size
            if (b.bitsAvailable >= besoin + 64) {
                val chips = b.chipsCopy()
                var a = 0
                var vu = false
                // Every sync found is tried: a buffer can hold several bursts.
                while (a < chips.size) {
                    val k = Meteomodem.findSync(chips, chips.size, a)
                    if (k < 0) break
                    if (Meteomodem.frameFromChips(chips, k, m10Trame)) {
                        val f = Meteomodem.parseM10(m10Trame, freqHz, maintenant)
                        if (f != null) { accepte(f); vu = true; break }
                    }
                    a = k + 1
                }
                if (!vu) rejetees++
                b.trimTo(besoin)
            }
        }
        if (maintenant - dernierEtatMs >= 300L) {
            dernierEtatMs = maintenant
            val d = rs41 ?: m20 ?: m10
            val ex = ((d?.swing(pcm, n) ?: 0) / 328).coerceIn(0, 100)
            etat = etat.copy(trames = trames, rejetees = rejetees, excursion = ex)
        }
    }

    private fun accepte(f: SondeFrame) {
        val v = vol ?: SondeFlight(f.serial.ifBlank { "?" }, f.type).also { vol = it }
        // Another serial: another sonde, another flight — never two tracks mixed.
        if (f.serial.isNotBlank() && v.serial != "?" && v.serial != f.serial) vol = SondeFlight(f.serial, f.type)
        val cible = vol!!
        if (!cible.add(f)) { rejetees++; return }
        trames++
        if (journal == null) journal = dossier?.let { d -> runCatching { ouvreJournal(d) }.getOrNull() }
        journal?.let { j -> runCatching { j.appendText(SondeExport.csvLine(f)) } }
        etat = etat.copy(trames = trames, derniere = f, serie = cible.serial, type = cible.type, journal = journal?.name)
    }

    private fun ouvreJournal(d: File): File {
        d.mkdirs()
        val f = File(d, "sonde-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(horloge())) + ".csv")
        if (!f.exists()) f.writeText(SondeExport.csvHeader())
        return f
    }

    /** The logs, newest first. */
    fun journaux(): List<File> = dossier?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** The flight's track as GPX or KML, written next to the logs; null when there is none. */
    fun exporte(kml: Boolean): File? {
        val v = vol ?: return null
        if (v.count == 0 || dossier == null) return null
        dossier.mkdirs()
        val f = File(dossier, v.serial.ifBlank { "sonde" } + if (kml) ".kml" else ".gpx")
        f.writeText(if (kml) SondeExport.kml(v) else SondeExport.gpx(v))
        return f
    }
}
