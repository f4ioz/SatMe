/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.Transmitter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The Doppler/CAT log of a followed pass: every few seconds the satellite's
 * place, the Doppler predicted on both legs, what the loop wrote to the rig
 * and what it read back — one CSV per pass, written as it goes.
 *
 * Off by default; it only looks at what the CAT loop already does and never
 * talks to the rig itself.
 *
 * Phases: AOS (first row), montée, TCA (the last row before the elevation
 * falls), descente, LOS (the row below the horizon that closes it). A row
 * is written once the next one is known, so the TCA can still be marked.
 */
class JournalDoppler(private val dossier: File, private val periodeMs: Long = PERIODE_MS) {

    /** One instant. Frequencies in sky Hz; null when unknown (no transmitter, nothing written or read yet). */
    data class Echantillon(
        val tMs: Long,
        val azDeg: Double,
        val elDeg: Double,
        val distanceKm: Double,
        val vitesseKmS: Double,
        val descenteReposHz: Long?,
        val monteeReposHz: Long?,
        val descenteEcriteHz: Long?,
        val monteeEcriteHz: Long?,
        val descenteLueHz: Long?,
    )

    private class Ligne(val e: Echantillon, var phase: String)

    private var fichier: File? = null
    private var catnum: Int? = null
    private var attente: Ligne? = null
    private var monte = true
    private var dernierMs = 0L

    /** The pass being logged, if any. */
    val enCours: File? get() = fichier

    /**
     * One second of the station. [actif]: the log is on, the rig connected,
     * a satellite followed. A pass opens above the horizon and closes below
     * it, when the satellite changes or the log stops. Returns the file
     * just closed, if any.
     */
    fun tic(actif: Boolean, satellite: String, numero: Int, e: Echantillon?): File? {
        var ferme: File? = null
        if (!actif || e == null || (catnum != null && numero != catnum)) {
            ferme = ferme(null)
            if (!actif || e == null) return ferme
        }
        if (e.elDeg < 0.0) return ferme(e) ?: ferme
        if (fichier == null) ouvre(satellite, numero, e)
        else if (e.tMs - dernierMs >= periodeMs) ajoute(e)
        return ferme
    }

    /** Closes the pass: [los], below the horizon, as its last row (else the last one stays as it was). */
    fun ferme(los: Echantillon?): File? {
        val f = fichier ?: return null
        val a = attente
        if (los != null && a != null) {
            ecris(a)
            ecris(Ligne(los, LOS))
        } else if (a != null) ecris(a)
        fichier = null; catnum = null; attente = null
        return f
    }

    private fun ouvre(satellite: String, numero: Int, e: Echantillon) {
        dossier.mkdirs()
        val nom = "doppler-" + satellite.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { numero.toString() } +
            "-" + horo("yyyyMMdd-HHmmss").format(Date(e.tMs)) + ".csv"
        val f = File(dossier, nom)
        f.writeText(ENTETE + "\n")
        // The oldest go beyond a few hundred passes.
        liste(dossier).drop(GARDES).forEach { runCatching { it.delete() } }
        fichier = f; catnum = numero
        monte = true
        attente = Ligne(e, AOS)
        dernierMs = e.tMs
    }

    private fun ajoute(e: Echantillon) {
        val a = attente
        if (a != null) {
            // The elevation turned: the row before was the highest.
            if (monte && e.elDeg < a.e.elDeg) {
                monte = false
                if (a.phase != AOS) a.phase = TCA
            }
            ecris(a)
        }
        attente = Ligne(e, if (monte) MONTEE else DESCENTE)
        dernierMs = e.tMs
    }

    private fun ecris(l: Ligne) { runCatching { fichier?.appendText(ligne(l.e, l.phase) + "\n") } }

    companion object {
        const val PERIODE_MS = 5_000L
        const val GARDES = 300
        const val AOS = "AOS"
        const val MONTEE = "montée"
        const val TCA = "TCA"
        const val DESCENTE = "descente"
        const val LOS = "LOS"

        const val ENTETE = "heure_utc,epoch_ms,phase,azimut_deg,elevation_deg,distance_km,vitesse_radiale_km_s," +
            "descente_repos_hz,doppler_descente_hz,descente_prevue_hz,montee_repos_hz,doppler_montee_hz,montee_prevue_hz," +
            "descente_ecrite_hz,montee_ecrite_hz,descente_lue_hz"

        /** The file names this log writes. */
        fun estUnJournal(nom: String) = nom.startsWith("doppler-") && nom.endsWith(".csv")

        fun ligne(e: Echantillon, phase: String): String {
            fun n(v: Long?) = v?.toString() ?: ""
            val dl = e.descenteReposHz?.let { Doppler.downlink(it, e.vitesseKmS) }
            val ul = e.monteeReposHz?.let { Doppler.uplink(it, e.vitesseKmS) }
            return listOf(
                horo("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(e.tMs)), e.tMs.toString(), phase,
                String.format(Locale.US, "%.1f", e.azDeg), String.format(Locale.US, "%.2f", e.elDeg),
                String.format(Locale.US, "%.1f", e.distanceKm), String.format(Locale.US, "%.4f", e.vitesseKmS),
                n(e.descenteReposHz), n(dl?.let { it - e.descenteReposHz!! }), n(dl),
                n(e.monteeReposHz), n(ul?.let { it - e.monteeReposHz!! }), n(ul),
                n(e.descenteEcriteHz), n(e.monteeEcriteHz), n(e.descenteLueHz),
            ).joinToString(",")
        }

        /**
         * The uplink rest matching [descenteRepos] on [t]: the transponder's
         * mirror point on a linear one, the fixed input otherwise.
         */
        fun reposMontee(t: Transmitter?, descenteRepos: Long?): Long? {
            t ?: return null
            val ulLo = t.uplinkLowHz ?: return null
            val dlLo = t.downlinkLowHz
            if (t.isTransponder && dlLo != null && descenteRepos != null)
                return Doppler.transponderUplinkRest(descenteRepos, dlLo, t.downlinkHighHz ?: dlLo, ulLo, t.uplinkHighHz ?: ulLo, t.invert)
            return ulLo
        }

        /** The logs in [dossier], newest first. */
        fun liste(dossier: File): List<File> =
            (dossier.listFiles { f -> f.isFile && estUnJournal(f.name) } ?: emptyArray()).sortedByDescending { it.lastModified() }

        /** When a log began, read from its name (null: not one of ours). */
        fun debut(nom: String): Long? {
            if (!estUnJournal(nom)) return null
            val s = nom.removeSuffix(".csv").takeLast(15)
            return runCatching { horo("yyyyMMdd-HHmmss").parse(s)?.time }.getOrNull()
        }

        /** The logs of a pass: the same satellite, begun between a little before its AOS and its LOS. */
        fun duPassage(dossier: File, satellite: String, debutMs: Long, finMs: Long): List<File> {
            val cle = satellite.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_')
            return liste(dossier).filter { f ->
                val t = debut(f.name) ?: return@filter false
                f.name.removePrefix("doppler-").removeSuffix(".csv").dropLast(16) == cle && t in (debutMs - 15 * 60_000L)..finMs
            }.sortedBy { it.name }
        }

        /** A log by its name in [dossier]; null for any other name (no path walks out of it). */
        fun fichier(dossier: File, nom: String): File? =
            nom.takeIf { estUnJournal(it) && !it.contains('/') && !it.contains('\\') && !it.contains("..") }
                ?.let { File(dossier, it) }?.takeIf { it.isFile }

        private fun horo(motif: String) = SimpleDateFormat(motif, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
