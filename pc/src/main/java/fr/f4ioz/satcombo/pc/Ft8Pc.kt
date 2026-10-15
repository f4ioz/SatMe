/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.domain.Ft8Cascade
import fr.f4ioz.satcombo.domain.Ft8Decodeur
import fr.f4ioz.satcombo.domain.Ft8Signal
import java.util.concurrent.Executors

/**
 * FT8 and FT4 heard through the PC's sound card, as the phone hears them
 * (`Ft8Hub`): slots on UTC (15 s, 7.5 s for FT4), the first one dropped (it
 * starts mid-transmission), each decoded by the engine as it closes, the
 * waterfall and the spectrum. The card's rate brought down by an integer
 * factor to about 12 kHz, the decoder's.
 */
class Ft8Pc(private val horloge: () -> Long = System::currentTimeMillis) {

    data class Entendu(
        /** Start of its slot, UTC ms. */
        val tranche: Long,
        val texte: String,
        val appelant: String?,
        val appele: String?,
        val locator: String?,
        val frequenceHz: Int,
        val rapportDb: Int,
        val decalageS: Double,
        val mode: String,
    )

    @Volatile var enMarche = false
        private set
    @Volatile var mode = "FT8"
        private set
    @Volatile var entendus: List<Entendu> = emptyList()
        private set
    @Volatile var tranches = 0
        private set
    @Volatile var niveau = 0f
        private set
    @Volatile var cascade: Ft8Cascade? = null
        private set
    /** When the waterfall's newest row was made: the page draws the slots' edges from it. */
    @Volatile var derniereLigneMs = 0L
        private set

    private val decodeur = Executors.newSingleThreadExecutor { r -> Thread(r, "satme-ft8").apply { isDaemon = true } }
    private var facteur = 1
    private var cadence = 12_000
    private var tampon = FloatArray(0)
    private var ecrit = 0
    private var trancheCourante = -1L
    private var premiere = true
    private var somme = 0
    private var dansSomme = 0
    private val bloc = ShortArray(1024)
    private var dansBloc = 0

    private fun dureeTranche() = if (mode == "FT4") 7_500L else 15_000L
    private fun signal() = if (mode == "FT4") Ft8Signal.FT4 else Ft8Signal.FT8

    @Synchronized fun demarre(m: String = mode) {
        mode = if (m == "FT4") "FT4" else "FT8"
        enMarche = true; premiere = true; trancheCourante = -1L; ecrit = 0; cadence = 0
    }

    @Synchronized fun arrete() { enMarche = false }

    @Synchronized fun vide() { entendus = emptyList(); tranches = 0 }

    /** A block of the card's sound. */
    @Synchronized fun ecoute(pcm: ShortArray, n: Int, rate: Int) {
        if (!enMarche) return
        if (cadence == 0 || cadence != rate / facteur) {
            facteur = Math.round(rate / 12_000.0).toInt().coerceAtLeast(1)
            cadence = rate / facteur
            cascade = Ft8Cascade(BASSE_HZ, HAUTE_HZ, COLONNES, cadence, LIGNES)
            tampon = FloatArray((dureeTranche() * cadence / 1000).toInt())
            ecrit = 0; somme = 0; dansSomme = 0; dansBloc = 0
        }
        var pic = 0
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            if (kotlin.math.abs(v) > pic) pic = kotlin.math.abs(v)
            somme += v; dansSomme++
            if (dansSomme < facteur) continue
            val s = somme / facteur
            somme = 0; dansSomme = 0
            if (ecrit < tampon.size) tampon[ecrit++] = s / 32768f
            bloc[dansBloc++] = s.toShort()
            if (dansBloc == bloc.size) { if (cascade?.verse(bloc, dansBloc) == true) derniereLigneMs = horloge(); dansBloc = 0 }
        }
        niveau = pic / 32768f
        // A new slot: the one just over is decoded (the first one, cut, is dropped).
        val t = horloge()
        val numero = t / dureeTranche()
        if (numero != trancheCourante) {
            val plein = tampon.copyOf(ecrit)
            val debut = (numero - 1) * dureeTranche()
            val etaitPremiere = premiere || trancheCourante < 0
            trancheCourante = numero; ecrit = 0; premiere = false
            val sig = signal(); val m = mode; val c = cadence
            if (!etaitPremiere && plein.size > sig.parSymbole * sig.symboles / 4) decodeur.execute { decode(plein, c, sig, m, debut) }
        }
    }

    /** How far into the current slot (0..1), for the page's bar. */
    fun avancement(): Float = (horloge() % dureeTranche()).toFloat() / dureeTranche()

    private fun decode(audio: FloatArray, cadence: Int, sig: Ft8Signal.Mode, m: String, debut: Long) {
        val l = runCatching { Ft8Decodeur.decode(audio, cadence.toDouble(), sig) }.getOrDefault(emptyList())
        val nouveaux = l.map {
            Entendu(debut, it.message.brut, it.message.appelant, it.message.appele, it.message.locator,
                Math.round(it.frequenceHz).toInt(), it.rapportDb, it.instantS, m)
        }
        synchronized(this) {
            // Newest first, capped: an evening of listening would otherwise hold thousands of lines.
            entendus = (nouveaux + entendus).take(500)
            tranches++
        }
    }

    /** Fed directly (the tests): a slot's audio, decoded at once. */
    fun decodeTranche(audio: FloatArray, cadence: Int, debut: Long) = decode(audio, cadence, signal(), mode, debut)

    companion object {
        /** About 5.5 Hz a column (FT8's tones are 6.25 Hz apart). */
        const val COLONNES = 512
        /** About 46 s: three FT8 slots. */
        const val LIGNES = 360
        const val BASSE_HZ = 200
        const val HAUTE_HZ = 3000
    }
}
