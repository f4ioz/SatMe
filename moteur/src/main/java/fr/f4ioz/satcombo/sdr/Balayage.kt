/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sdr

import org.json.JSONArray
import org.json.JSONObject

/**
 * The receiver's scan, receive only: what to listen to next, when to stop
 * on a signal, when to go on. A state machine with no thread and no clock
 * of its own: the caller gives the time and what the receiver sees
 * ([Vue]: the last spectrum, the squelch) at each [tic], and applies the
 * [Ordre] it gets back (listen there, or put the captured band there).
 *
 * Three ways:
 * - [Genre.MEMOIRES]: the memories of a folder, one after the other;
 * - [Genre.PLAGE]: a range on a channel grid, one channel after the other;
 * - [Genre.SPECTRE]: a range judged from the spectrum, a whole captured
 *   band at once (every channel of the grid in it), then the next band —
 *   far faster than one channel after the other on a wide receiver.
 *
 * It stops on a level [Reglages.seuilDb] above the noise (the spectrum's
 * median), or on the squelch open; goes on when the signal has dropped for
 * [Reglages.maintienMs] ([Reprise.PORTEUSE]), after [Reglages.tempsMs]
 * ([Reprise.TEMPS]) or when asked ([Reprise.MANUELLE]). Frequencies passed
 * by (lockout) are never stopped on; a priority channel is looked at every
 * [Reglages.prioriteMs].
 */
class Balayage(val reglages: Reglages, debutMs: Long) {

    enum class Genre { MEMOIRES, PLAGE, SPECTRE }
    enum class Reprise { PORTEUSE, TEMPS, MANUELLE }
    enum class Critere { NIVEAU, SQUELCH }
    enum class Etat { CHERCHE, ARRETE, PRIORITE, FINI }

    /** A channel: sky frequency, mode ("": the receiver's), width (0: the mode's), the memory's name and id when it is one. */
    data class Canal(val hz: Long, val mode: String = "", val largeurHz: Int = 0, val nom: String = "", val id: String = "")

    data class Reglages(
        val genre: Genre = Genre.PLAGE,
        /** [Genre.MEMOIRES]: the channels, in order. */
        val canaux: List<Canal> = emptyList(),
        val debutHz: Long = 0L,
        val finHz: Long = 0L,
        val pasHz: Double = 12_500.0,
        /** [Genre.PLAGE], [Genre.SPECTRE]: the mode and width to listen with. */
        val mode: String = "",
        val largeurHz: Int = 0,
        val critere: Critere = Critere.NIVEAU,
        val seuilDb: Double = 12.0,
        val reprise: Reprise = Reprise.PORTEUSE,
        val maintienMs: Long = 2_000L,
        val tempsMs: Long = 5_000L,
        /** After a tuning, this long and two new spectra before judging. */
        val attenteMs: Long = 80L,
        /** Never stopped on (lockout), within half a step (1 kHz at least). */
        val passe: List<Long> = emptyList(),
        val priorite: Canal? = null,
        val prioriteMs: Long = 5_000L,
        /** [Genre.SPECTRE]: the width judged at once (the receiver's useful band). */
        val largeurVueHz: Long = 900_000L,
        /** Once round, or round and round. */
        val boucle: Boolean = true,
    )

    /**
     * What the receiver sees now: its last spectrum ([db], fft-shifted,
     * [binHz] per bin, centred on [centreHz] in the sky, [sens] −1 behind an
     * inverting converter), counted by [trame]; the squelch.
     */
    class Vue(val trame: Long, val centreHz: Long, val binHz: Double, val db: FloatArray?, val sens: Int = 1, val squelch: Boolean = false) {
        private val lisse: FloatArray? by lazy { db?.let { lisse(it) } }

        /** The noise: the median of the smoothed spectrum. */
        val bruitDb: Double by lazy { lisse?.let { val t = it.copyOf(); t.sort(); t[t.size / 2].toDouble() } ?: -120.0 }

        /** The useful half-width (the edges of a capture droop). */
        val demiUtileHz: Double get() = (db?.size ?: 0) * binHz * 0.45

        fun voit(hz: Long): Boolean = db != null && Math.abs(hz - centreHz) <= demiUtileHz

        /**
         * The strongest (smoothed) level within ±[largeurHz]/2 of [hz], dB
         * above the noise; null when outside the spectrum. The centre's line
         * (a receiver's own DC) is never taken for a signal.
         */
        fun niveau(hz: Long, largeurHz: Double): Double? {
            val l = lisse ?: return null
            if (!voit(hz)) return null
            val n = l.size
            val demi = maxOf(largeurHz / 2, binHz * 1.5)
            val a = ((hz - centreHz) * sens - demi) / binHz + n / 2
            val b = ((hz - centreHz) * sens + demi) / binHz + n / 2
            var m = Float.NEGATIVE_INFINITY
            val dc = maxOf(2, Math.ceil(DC_HZ / binHz).toInt())
            for (i in maxOf(0, Math.ceil(a).toInt())..minOf(n - 1, Math.floor(b).toInt())) {
                if (Math.abs(i - n / 2) <= dc) continue
                if (l[i] > m) m = l[i]
            }
            return if (m == Float.NEGATIVE_INFINITY) null else m - bruitDb
        }

        companion object {
            /** Around the centre, left out: the DC line of a direct-conversion receiver. */
            const val DC_HZ = 1_200.0

            /** Power averaged over three bins: the noise's spikes tamed, a carrier kept. */
            fun lisse(d: FloatArray): FloatArray {
                val p = DoubleArray(d.size) { Math.pow(10.0, d[it] / 10.0) }
                return FloatArray(d.size) { i ->
                    val s = p[maxOf(0, i - 1)] + p[i] + p[minOf(d.size - 1, i + 1)]
                    (10.0 * Math.log10(s / 3.0 + 1e-30)).toFloat()
                }
            }
        }
    }

    sealed class Ordre {
        /** Listen to [canal] (by the offset within the captured band, else the PLL moved). */
        data class Accorde(val canal: Canal) : Ordre()
        /** Put the captured band's middle at [hz] ([Genre.SPECTRE]). */
        data class Centre(val hz: Long) : Ordre()
    }

    /** A signal stopped on: where, how strong, when first and last, how many times. */
    data class Touche(val canal: Canal, val niveauDb: Double, val premiereMs: Long, val derniereMs: Long, val fois: Int, val priorite: Boolean)

    @Volatile var etat: Etat = Etat.CHERCHE; private set
    /** Where it listens (or what band it judges) now. */
    @Volatile var ecoute: Canal? = null; private set
    @Volatile var tours = 0; private set
    @Volatile var vus = 0L; private set
    val debut = debutMs

    private val touches = ArrayList<Touche>()
    private val passe = ArrayList<Long>(reglages.passe)

    private val pas = reglages.pasHz.coerceAtLeast(1_000.0)
    private val tolerance = maxOf(1_000.0, pas / 2)
    private val nPlage: Int = if (reglages.finHz > reglages.debutHz) minOf(MAX_CANAUX, ((reglages.finHz - reglages.debutHz) / pas).toInt() + 1) else 0
    /** [Genre.SPECTRE]: the bands judged one after the other. */
    private val largeurBande = maxOf(pas * 2, reglages.largeurVueHz.toDouble())
    private val nBandes: Int = if (nPlage == 0) 0 else Math.ceil((reglages.finHz - reglages.debutHz + 1) / largeurBande).toInt().coerceAtLeast(1)

    private var i = 0
    private var bande = 0
    /** [Genre.SPECTRE]: within the band judged, only above this (after a stop). */
    private var curseurHz = Long.MIN_VALUE
    private var attente = false
    private var tOrdre = 0L
    private var trameOrdre = 0L
    // Stopped on a signal.
    private var arret: Canal? = null
    private var arretPrio = false
    private var tArret = 0L
    private var tSignal = 0L
    // The priority channel.
    private var tPrio = debutMs
    private var avantPrio = Etat.CHERCHE

    /** The count of channels (or of bands, [Genre.SPECTRE]) a round goes through. */
    val taille: Int get() = when (reglages.genre) { Genre.MEMOIRES -> reglages.canaux.size; Genre.PLAGE -> nPlage; Genre.SPECTRE -> nBandes }

    /** The grid's [k]th channel. */
    fun canalPlage(k: Int): Canal = Canal(reglages.debutHz + Math.round(k * pas), reglages.mode, reglages.largeurHz)

    /** The [k]th band's middle ([Genre.SPECTRE]): half a step off the grid, so no channel falls on its DC line. */
    fun centreBande(k: Int): Long {
        val haut = minOf(reglages.finHz.toDouble(), reglages.debutHz + (k + 1) * largeurBande)
        val bas = reglages.debutHz + k * largeurBande
        return Math.round((bas + haut) / 2 + pas / 2)
    }

    fun passe(hz: Long): Boolean = passe.any { Math.abs(it - hz) <= tolerance }

    /** [hz] passed by from now on (and the stop on it left, when it is the one). */
    @Synchronized fun ajoutePasse(hz: Long) {
        if (!passe(hz)) passe += hz
        if (etat == Etat.ARRETE && arret?.let { Math.abs(it.hz - hz) <= tolerance } == true) reprend()
    }

    @Synchronized fun retirePasse(hz: Long) { passe.removeAll { Math.abs(it - hz) <= tolerance } }

    fun passes(): List<Long> = synchronized(this) { passe.toList() }

    fun touches(): List<Touche> = synchronized(this) { touches.toList() }

    /** The stop left now (« Reprendre »). */
    @Synchronized fun reprend() {
        if (etat != Etat.ARRETE) return
        val a = arret
        arret = null
        etat = Etat.CHERCHE
        attente = false
        if (arretPrio) { arretPrio = false; return }
        when (reglages.genre) {
            Genre.SPECTRE -> curseurHz = (a?.hz ?: curseurHz) + Math.round(pas / 2)
            else -> avance()
        }
    }

    private fun canalCourant(): Canal? = when (reglages.genre) {
        Genre.MEMOIRES -> reglages.canaux.getOrNull(i)
        Genre.PLAGE -> if (i < nPlage) canalPlage(i) else null
        Genre.SPECTRE -> null
    }

    private fun avance() {
        if (taille == 0) { etat = Etat.FINI; return }
        if (reglages.genre == Genre.SPECTRE) {
            bande++; curseurHz = Long.MIN_VALUE
            if (bande >= nBandes) { bande = 0; tours++; if (!reglages.boucle) etat = Etat.FINI }
            return
        }
        // The next channel not passed by; a whole round of them all passed: finished.
        var k = 0
        do {
            i++
            if (i >= taille) { i = 0; tours++; if (!reglages.boucle) { etat = Etat.FINI; return } }
            k++
        } while (canalCourant()?.let { passe(it.hz) } == true && k <= taille)
        if (k > taille) etat = Etat.FINI
    }

    private fun pret(maintenantMs: Long, v: Vue) = maintenantMs - tOrdre >= reglages.attenteMs && v.trame >= trameOrdre + 2

    private fun ordonne(maintenantMs: Long, v: Vue, o: Ordre): Ordre {
        attente = true; tOrdre = maintenantMs; trameOrdre = v.trame
        ecoute = when (o) { is Ordre.Accorde -> o.canal; is Ordre.Centre -> Canal(o.hz, reglages.mode, reglages.largeurHz) }
        return o
    }

    /** The width judged around a channel: its own, a share of the step on a grid, its mode's for a memory. */
    fun fenetreHz(c: Canal): Double = when {
        c.largeurHz > 0 -> c.largeurHz.toDouble()
        reglages.genre != Genre.MEMOIRES -> (pas * 0.8).coerceIn(600.0, 20_000.0)
        else -> when (c.mode) { "NFM" -> 12_000.0; "AM" -> 8_000.0; "USB", "LSB" -> 3_000.0; "CW" -> 800.0; else -> 6_000.0 }
    }

    /** A signal on [c] now: the squelch open (when that is the rule, and [c] is where it listens), else its level. */
    private fun present(v: Vue, c: Canal): Pair<Boolean, Double> {
        val n = v.niveau(c.hz, fenetreHz(c))
        val squelch = reglages.critere == Critere.SQUELCH && reglages.genre != Genre.SPECTRE
        return (if (squelch) v.squelch else n != null && n >= reglages.seuilDb) to (n ?: 0.0)
    }

    private fun note(maintenantMs: Long, c: Canal, niveau: Double, prio: Boolean) {
        val k = touches.indexOfFirst { Math.abs(it.canal.hz - c.hz) <= tolerance }
        if (k >= 0) {
            val t = touches.removeAt(k)
            // A memory's name (the priority channel's) over a bare frequency.
            val canal = if (t.canal.nom.isEmpty() && c.nom.isNotEmpty()) c else t.canal
            touches.add(0, t.copy(canal = canal, niveauDb = maxOf(t.niveauDb, niveau), derniereMs = maintenantMs, fois = t.fois + 1, priorite = t.priorite || prio))
        } else {
            touches.add(0, Touche(c, niveau, maintenantMs, maintenantMs, 1, prio))
            while (touches.size > MAX_TOUCHES) touches.removeAt(touches.size - 1)
        }
    }

    private fun arreteSur(maintenantMs: Long, c: Canal, niveau: Double, prio: Boolean) {
        etat = Etat.ARRETE; arret = c; arretPrio = prio
        tArret = maintenantMs; tSignal = maintenantMs
        note(maintenantMs, c, niveau, prio)
    }

    /** The touches' level kept up while stopped. */
    private fun rafraichit(maintenantMs: Long, c: Canal, niveau: Double) {
        val k = touches.indexOfFirst { Math.abs(it.canal.hz - c.hz) <= tolerance }
        if (k >= 0) touches[k] = touches[k].let { it.copy(niveauDb = maxOf(it.niveauDb, niveau), derniereMs = maintenantMs) }
    }

    /**
     * [Genre.SPECTRE]: the band's channels judged at once; the first above
     * the threshold (a local top, so a wide signal stops once), not passed
     * by, above [curseurHz]; null when none.
     */
    fun meilleur(v: Vue, k: Int): Pair<Canal, Double>? {
        val bas = maxOf(reglages.debutHz.toDouble(), reglages.debutHz + k * largeurBande)
        val haut = minOf(reglages.finHz.toDouble(), reglages.debutHz + (k + 1) * largeurBande - 1)
        val k0 = Math.ceil((bas - reglages.debutHz) / pas).toInt().coerceAtLeast(0)
        val k1 = minOf(nPlage - 1, Math.floor((haut - reglages.debutHz) / pas).toInt())
        if (k1 < k0) return null
        val niv = DoubleArray(k1 - k0 + 1) { j -> canalPlage(k0 + j).let { v.niveau(it.hz, fenetreHz(it)) } ?: Double.NEGATIVE_INFINITY }
        for (j in niv.indices) {
            val c = canalPlage(k0 + j)
            if (c.hz <= curseurHz || niv[j] < reglages.seuilDb || passe(c.hz)) continue
            val g = if (j > 0) niv[j - 1] else Double.NEGATIVE_INFINITY
            val d = if (j < niv.size - 1) niv[j + 1] else Double.NEGATIVE_INFINITY
            if (niv[j] >= g && niv[j] >= d) return c to niv[j]
        }
        return null
    }

    /** One step: what to do now, given what the receiver sees ([v]). Null: nothing to change. */
    @Synchronized fun tic(maintenantMs: Long, v: Vue): Ordre? {
        if (taille == 0) etat = Etat.FINI
        when (etat) {
            Etat.FINI -> return null
            Etat.PRIORITE -> {
                val p = reglages.priorite ?: run { etat = avantPrio; attente = false; return null }
                if (!pret(maintenantMs, v)) return null
                attente = false
                tPrio = maintenantMs
                val (oui, n) = present(v, p)
                if (oui) { arreteSur(maintenantMs, p, n, true); return null }
                // Nothing there: back to where it was.
                etat = avantPrio
                val a = arret
                return if (etat == Etat.ARRETE && a != null) ordonne(maintenantMs, v, Ordre.Accorde(a)).also { attente = true } else null
            }
            Etat.ARRETE -> {
                val c = arret ?: run { etat = Etat.CHERCHE; return null }
                if (attente) { if (!pret(maintenantMs, v)) return null; attente = false; tSignal = maintenantMs }
                val (oui, n) = present(v, c)
                if (oui) { tSignal = maintenantMs; rafraichit(maintenantMs, c, n) }
                val repart = when (reglages.reprise) {
                    Reprise.PORTEUSE -> !oui && maintenantMs - tSignal >= reglages.maintienMs
                    Reprise.TEMPS -> maintenantMs - tArret >= reglages.tempsMs
                    Reprise.MANUELLE -> false
                }
                if (repart) { reprend(); return tic(maintenantMs, v) }
                if (!arretPrio && prioriteDue(maintenantMs)) return versPriorite(maintenantMs, v)
                return null
            }
            Etat.CHERCHE -> {
                if (!attente) {
                    if (prioriteDue(maintenantMs)) return versPriorite(maintenantMs, v)
                    return if (reglages.genre == Genre.SPECTRE) ordonne(maintenantMs, v, Ordre.Centre(centreBande(bande)))
                    else {
                        // The first channel may be a passed one.
                        if (canalCourant()?.let { passe(it.hz) } == true) { avance(); if (etat == Etat.FINI) return null }
                        ordonne(maintenantMs, v, Ordre.Accorde(canalCourant() ?: return null))
                    }
                }
                if (!pret(maintenantMs, v)) return null
                attente = false
                if (reglages.genre == Genre.SPECTRE) {
                    vus += 1
                    val m = meilleur(v, bande)
                    if (m != null) {
                        arreteSur(maintenantMs, m.first, m.second, false)
                        return ordonne(maintenantMs, v, Ordre.Accorde(m.first))
                    }
                    avance()
                    return if (etat == Etat.FINI) null else tic(maintenantMs, v)
                }
                val c = canalCourant() ?: return null
                vus += 1
                val (oui, n) = present(v, c)
                if (oui && !passe(c.hz)) { arreteSur(maintenantMs, c, n, false); return null }
                avance()
                return if (etat == Etat.FINI) null else tic(maintenantMs, v)
            }
        }
    }

    private fun prioriteDue(maintenantMs: Long): Boolean {
        val p = reglages.priorite ?: return false
        if (passe(p.hz)) return false
        return maintenantMs - tPrio >= reglages.prioriteMs
    }

    private fun versPriorite(maintenantMs: Long, v: Vue): Ordre {
        avantPrio = etat
        etat = Etat.PRIORITE
        return ordonne(maintenantMs, v, Ordre.Accorde(reglages.priorite!!))
    }

    /** Where it is and what it heard, for the page. */
    @Synchronized fun json(maintenantMs: Long): JSONObject {
        val r = reglages
        return JSONObject().put("etat", etat.name).put("genre", r.genre.name).put("critere", r.critere.name).put("reprise", r.reprise.name)
            .put("seuil", r.seuilDb).put("pas", r.pasHz).put("debut", r.debutHz).put("fin", r.finHz)
            .put("ecoute", ecoute?.let { canalJson(it) } ?: JSONObject.NULL)
            .put("arret", arret?.let { canalJson(it) } ?: JSONObject.NULL).put("arretPrio", arretPrio)
            .put("arretDepuis", if (etat == Etat.ARRETE) maintenantMs - tArret else 0L)
            .put("position", if (r.genre == Genre.SPECTRE) bande else i).put("taille", taille).put("tours", tours).put("vus", vus)
            .put("priorite", r.priorite?.let { canalJson(it) } ?: JSONObject.NULL)
            .put("passe", JSONArray(passe.toList()))
            .put("touches", JSONArray(touches.map { t ->
                canalJson(t.canal).put("niveau", Math.round(t.niveauDb * 10) / 10.0).put("premiere", t.premiereMs).put("derniere", t.derniereMs)
                    .put("fois", t.fois).put("prio", t.priorite)
            }))
    }

    companion object {
        const val MAX_CANAUX = 20_000
        const val MAX_TOUCHES = 60
        /** The grids offered (Hz): 8.33 is the air band's 25/3. */
        val PAS = listOf(5_000.0, 6_250.0, 25_000.0 / 3, 10_000.0, 12_500.0, 25_000.0)

        fun canalJson(c: Canal): JSONObject = JSONObject().put("hz", c.hz).put("mode", c.mode).put("largeur", c.largeurHz).put("nom", c.nom).put("id", c.id)
    }
}
