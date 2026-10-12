/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.domain.ActiviteSon
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.SonPassage
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import java.io.File
import java.io.RandomAccessFile

/**
 * The pass journal on the PC: each pass of the satellite followed, gathered
 * second by second as on the phone (the engine's [JournalPassage.Collecte]),
 * kept in the phone's format, and tied by time to the log's contacts, the
 * SSTV pictures and the recordings — for the phone's own journal page.
 */
class JournalPc(
    private val station: StationPc,
    private val carnet: CarnetPc,
    private val son: SonStation,
    dossier: File,
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    val rangement = JournalPassage.Rangement(dossier)
    private val collecte = JournalPassage.Collecte()
    private val predicteur = PassPredictor()

    /** Every second: the satellite followed, where it is, what the station does. */
    @Synchronized fun tic() {
        val e = station.etat.value
        val sat = e.suivi
        val pos = e.position
        if (sat == null || pos == null) { collecte.ferme()?.let { garde(it) }; return }
        val c = station.stationCat.etat.value
        val r = station.stationRotor.etat.value
        val etat = JournalPassage.Etat(
            dlHz = if (e.catConnecte) c.descenteHz else null,
            ulHz = if (e.catConnecte) c.monteeHz else null,
            rotorAz = if (r.rotorConnected) r.rotorActualAz else null,
            rotorEl = if (r.rotorConnected) r.rotorActualEl else null,
            enregistrement = son.etat.value.enregistrement.ifBlank { null },
            transpondeur = station.emetteurChoisi()?.description.orEmpty(),
            locator = e.locator)
        collecte.suit(sat.catalogNumber, sat.name, horloge(), pos.azimuthDeg, pos.elevationDeg, etat)?.let { garde(it) }
    }

    /** The pass under way kept now (the program stopping). */
    @Synchronized fun ferme() { collecte.ferme()?.let { garde(it) } }

    private fun garde(e: JournalPassage.Entree) { runCatching { rangement.enregistre(e) } }

    fun passages(): List<JournalPassage.Entree> = runCatching { rangement.passages() }.getOrDefault(emptyList())

    fun passage(id: String): JournalPassage.Entree? = passages().firstOrNull { it.id == id }

    // ------------------------------------------------------- what belongs to a pass

    class Liens(
        val qsos: List<fr.f4ioz.satcombo.data.LogEntry>,
        val images: List<Pair<File, SstvMeta.SstvShot>>,
    )

    fun liens(e: JournalPassage.Entree): Liens {
        val f = JournalPassage.fenetre(e)
        return Liens(
            qsos = carnet.contacts().filter { it.timeMs in f && (it.catnum == e.catnum || JournalPassage.memeSatellite(it.satName, e.satName)) }
                .sortedBy { it.timeMs },
            images = son.images().filter { it.timeMs in f }.mapNotNull { s -> son.fichierImage(s.fileName)?.let { it to s } }
                .sortedBy { it.second.timeMs })
    }

    /** What happened on the pass, as the phone marks it (contacts put back by the logging delay). */
    fun marques(l: Liens): List<JournalPassage.Marque> {
        val avance = station.settings.journalDecalageQsoS * 1000L
        val qso = l.qsos.map { q ->
            val ll = Maidenhead.centre(q.theirLocator)
            val rst = listOf(q.rstSent, q.rstRcvd).filter { it.isNotBlank() }.joinToString(" / ")
            JournalPassage.Marque(JournalPassage.TypeMarque.QSO, q.timeMs - avance, q.timeMs - avance, q.callsign, ll?.first, ll?.second,
                details = listOf(q.mode, rst).filter { it.isNotBlank() }.joinToString(" · "))
        }
        val sstv = l.images.distinctBy { it.second.timeMs / 10_000 }.map { (f, s) ->
            val duree = SstvMode.byName(s.mode)?.let { (it.frameSeconds * 1000).toLong() + 1_000L } ?: 60_000L
            JournalPassage.Marque(JournalPassage.TypeMarque.SSTV, s.timeMs - duree, s.timeMs, s.mode, fichier = f.absolutePath)
        }
        return (qso + sstv).sortedBy { it.debutMs }
    }

    /** Who the stations are, from the log (name, town, square). */
    fun fiches(l: Liens): Map<String, JournalPassage.Fiche> =
        l.qsos.groupBy { it.callsign }.mapValues { (c, q) ->
            val d = q.lastOrNull { it.nom.isNotBlank() } ?: q.last()
            JournalPassage.Fiche(c, d.nom, d.qth, "", q.lastOrNull { it.theirLocator.isNotBlank() }?.theirLocator.orEmpty())
        }

    // ------------------------------------------------------------ the sky and the map

    private fun observateur(e: JournalPassage.Entree): Observer? =
        Maidenhead.centre(e.locator)?.let { Observer(it.first, it.second) } ?: station.etat.value.observateur

    private fun satellite(e: JournalPassage.Entree) = station.etat.value.satellites.firstOrNull { it.catalogNumber == e.catnum }

    /** The pass as predicted from today's elements (az, el). */
    fun prevue(e: JournalPassage.Entree): List<Pair<Double, Double>> = runCatching {
        val sat = satellite(e) ?: return emptyList()
        val obs = observateur(e) ?: return emptyList()
        val (aos, los) = predicteur.currentPass(sat, obs, (e.debutMs + e.finMs) / 2) ?: (e.debutMs to e.finMs)
        predicteur.passTrack(sat, obs, aos, los)
    }.getOrDefault(emptyList())

    /** Under the satellite at each point of the pass, for the map. */
    fun traceSol(e: JournalPassage.Entree): List<JournalPassage.Sol> = runCatching {
        val sat = satellite(e) ?: return emptyList()
        val obs = observateur(e) ?: return emptyList()
        e.points.map { p -> predicteur.positionAt(sat, obs, p.tMs).let { JournalPassage.Sol(p.tMs, it.latDeg, it.lonDeg, it.altKm) } }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ the sound

    /** The recordings of the pass, each with when it started (its name) and its length (its header). */
    fun morceaux(e: JournalPassage.Entree): List<SonPassage.Morceau> =
        e.enregistrements.distinct().mapNotNull { son.fichierEnregistrement(it) }
            .filter { it.name.endsWith(".wav") }
            .map { SonPassage.Morceau(it, SstvMeta.debutEnregistrement(it.name), 0L, dureeWav(it)) }
            .filter { it.origine > 0L }.sortedBy { it.origine }

    /** Where the sound shows activity, as pass times; worked out once, kept next to the recording. */
    fun activite(e: JournalPassage.Entree): List<LongRange> = morceaux(e).flatMap { m ->
        runCatching {
            val garde = File(m.f.parentFile, m.f.name.substringBeforeLast('.') + ".activite")
            val p = if (garde.isFile && garde.lastModified() >= m.f.lastModified()) ActiviteSon.lit(garde.readText()) else {
                var mesure: ActiviteSon.Mesure? = null
                decodeur.decode(m.f) { pcm, n, rate -> (mesure ?: ActiviteSon.Mesure(rate).also { mesure = it }).ajoute(pcm, n); true }
                val pl = mesure?.let { ActiviteSon.plages(it.fenetres) } ?: emptyList()
                runCatching { garde.writeText(ActiviteSon.ecrit(pl)) }
                pl
            }
            p.map { (m.origine + it.first)..(m.origine + it.last) }
        }.getOrDefault(emptyList())
    }

    /** A moment's sound as a WAV file in [dossier], from the pass's recordings; null when none covers it. */
    fun extraitWav(e: JournalPassage.Entree, deMs: Long, aMs: Long, dossier: File, nom: String): File? {
        val s = SonPassage.fabrique(morceaux(e), deMs, aMs, dossier, decodeur) ?: return null
        return s.use {
            val f = File(dossier, nom)
            val w = EnregistreurWav(f, it.rate * 2)
            val src = it.source(deMs, null, 0)
            val buf = ShortArray(8192)
            // EnregistreurWav halves the rate: hand it each sample twice.
            val double = ShortArray(16384)
            while (true) {
                val n = src.lis(buf); if (n <= 0) break
                for (i in 0 until n) { double[2 * i] = buf[i]; double[2 * i + 1] = buf[i] }
                w.ajoute(double, 2 * n)
            }
            w.ferme()
            f
        }
    }

    companion object {
        /** WAV files read block by block: 16-bit mono PCM (the PC's recordings). */
        val decodeur = SonPassage.Decodeur { f, sortie ->
            RandomAccessFile(f, "r").use { raf ->
                val tete = ByteArray(44); raf.readFully(tete)
                val b = java.nio.ByteBuffer.wrap(tete).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val rate = b.getInt(24)
                val octets = ByteArray(8192)
                val pcm = ShortArray(4096)
                while (true) {
                    val n = raf.read(octets); if (n <= 1) break
                    val k = n / 2
                    java.nio.ByteBuffer.wrap(octets, 0, k * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm, 0, k)
                    if (!sortie(pcm, k, rate)) break
                }
            }
        }

        /** A WAV file's length (ms), from its header and size. */
        fun dureeWav(f: File): Long = runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val tete = ByteArray(44); raf.readFully(tete)
                val rate = java.nio.ByteBuffer.wrap(tete).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24)
                (f.length() - 44) / 2 * 1000 / rate
            }
        }.getOrDefault(0L)
    }
}
