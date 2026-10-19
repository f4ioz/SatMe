/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The receive audio codecs the link asks for. The byte is what the
 * connection request carries; 0x04 (16-bit mono) is seen in captures, the
 * µ-law byte is not and stays to be confirmed on the owner's radio.
 *
 * 16-bit mono at 24 kHz is left out: measured on an IC-9700, the radio
 * accepts it and then sends nothing (its 48 000 bytes per second is the one
 * figure that never carries audio).
 */
enum class CodecReseau(val id: String, val octet: Int, val taux: Int, val octetsParEchantillon: Int, val libelle: String, val confirme: Boolean) {
    PCM16_16K("pcm16-16k", 0x04, 16_000, 2, "PCM 16 bits, 16 kHz", true),
    PCM16_8K("pcm16-8k", 0x04, 8_000, 2, "PCM 16 bits, 8 kHz", true),
    ULAW_8K("ulaw-8k", 0x01, 8_000, 1, "µ-law, 8 kHz (à confirmer)", false),
    ULAW_16K("ulaw-16k", 0x01, 16_000, 1, "µ-law, 16 kHz (à confirmer)", false);

    val octetsParSeconde: Int get() = taux * octetsParEchantillon

    companion object {
        val DEFAUT = PCM16_16K
        fun de(id: String?): CodecReseau = entries.firstOrNull { it.id == id } ?: DEFAUT
    }
}

/** Wire bytes to 16-bit samples. */
object DecodeAudio {

    fun pcm16(b: ByteArray): ShortArray =
        ShortArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xFF)).toShort() }

    /** G.711 µ-law, as the standard gives it. */
    fun ulaw(u: Int): Short {
        val x = u.inv() and 0xFF
        val signe = x and 0x80
        val exposant = (x shr 4) and 0x07
        val mantisse = x and 0x0F
        val v = (((mantisse shl 3) + 0x84) shl exposant) - 0x84
        return (if (signe != 0) -v else v).toShort()
    }

    fun decode(codec: CodecReseau, b: ByteArray): ShortArray =
        if (codec.octetsParEchantillon == 2) pcm16(b) else ShortArray(b.size) { ulaw(b[it].toInt() and 0xFF) }
}

/**
 * From the wire's rate to the decoders' 48 kHz, by an integer factor
 * (8 kHz ×6, 16 kHz ×3): linear interpolation then a short low-pass,
 * enough for voice, SSTV and FT8, which sit below 4 kHz anyway.
 */
class Surechantillonneur(val tauxEntree: Int, val tauxSortie: Int = 48_000) {
    val facteur = maxOf(1, tauxSortie / tauxEntree)
    private var precedent = 0f
    // A 3-tap smoother after interpolation: trims the images the steps leave.
    private var y1 = 0f; private var y2 = 0f

    fun traite(e: ShortArray, n: Int = e.size): ShortArray {
        val s = ShortArray(n * facteur)
        var k = 0
        for (i in 0 until n) {
            val x = e[i].toFloat()
            for (j in 1..facteur) {
                val v = precedent + (x - precedent) * j / facteur
                val f = 0.25f * y2 + 0.5f * y1 + 0.25f * v
                y2 = y1; y1 = v
                s[k++] = f.coerceIn(-32768f, 32767f).toInt().toShort()
            }
            precedent = x
        }
        return s
    }
}

/**
 * The receive audio put back in order.
 *
 * Packets are held up to [fenetreMs] after a hole so a late or
 * retransmitted one can still take its place; a hole still open when the
 * window closes is played as silence of the length its neighbours had, so
 * the decoders keep their timing. Duplicates and packets behind what was
 * played are dropped. Sequences are the header's 16-bit count.
 */
class TamponAudio(
    @Volatile var fenetreMs: Int = 80,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Each block, in order (holes as zeros). */
    private val sortie: (ByteArray) -> Unit,
) {
    private val attente = java.util.TreeMap<Int, ByteArray>()
    private var attendu = -1
    private var trouDepuis = -1L
    private var derniereLongueur = 0
    private var derniereDemande = Long.MIN_VALUE / 2

    @Volatile var recus = 0L; private set
    @Volatile var perdus = 0L; private set
    @Volatile var enRetard = 0L; private set
    @Volatile var doubles = 0L; private set

    /** Distance from [a] to [b] on the 16-bit circle, -32768..32767. */
    private fun ecart(a: Int, b: Int): Int { val d = (b - a) and 0xFFFF; return if (d >= 0x8000) d - 0x10000 else d }

    @Synchronized fun recois(seq: Int, donnees: ByteArray) {
        recus++
        if (attendu < 0) attendu = seq
        val d = ecart(attendu, seq)
        when {
            // A counter started again (a reopened stream): follow it.
            d < -1000 || d > 1000 -> { attente.clear(); attendu = seq }
            d < 0 -> { enRetard++; return }
        }
        if (attente.containsKey(seq)) { doubles++; return }
        attente[seq] = donnees
        vide()
    }

    /** Called often (each packet, each tick): plays what can be, closes old holes. */
    @Synchronized fun vide() {
        while (true) {
            val b = attente.remove(attendu)
            if (b != null) {
                derniereLongueur = b.size
                sortie(b); attendu = (attendu + 1) and 0xFFFF; trouDepuis = -1L
                continue
            }
            if (attente.isEmpty()) return
            val t = horloge()
            if (trouDepuis < 0L) trouDepuis = t
            if (t - trouDepuis < fenetreMs) return
            // The window closed on this hole: silence in its place.
            perdus++
            if (derniereLongueur > 0) sortie(ByteArray(derniereLongueur))
            attendu = (attendu + 1) and 0xFFFF
            trouDepuis = t
        }
    }

    /**
     * The sequences missing now, to ask the radio again — every half window,
     * never more often than 20 ms; none when the window is zero.
     */
    @Synchronized fun manquants(): List<Int> {
        if (attente.isEmpty() || fenetreMs <= 0) return emptyList()
        val t = horloge()
        if (t - derniereDemande < maxOf(20, fenetreMs / 2)) return emptyList()
        derniereDemande = t
        val dernier = attente.keys.maxByOrNull { ecart(attendu, it) } ?: return emptyList()
        val r = ArrayList<Int>()
        var s = attendu
        while (s != dernier && r.size < 16) { if (!attente.containsKey(s)) r += s; s = (s + 1) and 0xFFFF }
        return r
    }

    @Synchronized fun remetAZero() { attente.clear(); attendu = -1; trouDepuis = -1L }
}
