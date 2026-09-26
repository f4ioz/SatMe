/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.apt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Le décodage APT, vérifié sur un signal fabriqué.
 *
 * Un passage NOAA ne se commande pas : il faut être dehors, au bon moment,
 * avec la bonne antenne. Vérifier le décodeur en attendant un satellite
 * reviendrait à ne jamais le vérifier. On fabrique donc ici un signal APT
 * complet à partir d'une image connue — dégradé croissant à gauche,
 * décroissant à droite — et on demande au décodeur de la retrouver.
 *
 * Ce que ces essais prouvent : la démodulation d'amplitude, le
 * rééchantillonnage à 4160 mots par seconde et le calage sur la salve de
 * synchronisation. Ce qu'ils ne prouvent pas : le comportement sur un vrai
 * signal bruité, avec effet Doppler et évanouissements. D'où la mention
 * « bêta » tant que personne n'a décodé une vraie image.
 */
class AptDecoderTest {

    private val fs = 44_100

    // ------------------------------------------------------------- géométrie

    @Test
    fun `la ligne fait bien 2080 mots repartis en deux canaux`() {
        assertEquals(2080, Apt.WORDS_PER_LINE)
        assertEquals(4160, Apt.WORD_RATE)
        // Canal A puis canal B, chacun salve + espace + image + télémétrie.
        assertEquals(Apt.SPACE_A, Apt.SYNC_A + Apt.SYNC_LEN)
        assertEquals(Apt.VIDEO_A, Apt.SPACE_A + Apt.SPACE_LEN)
        assertEquals(Apt.TELEMETRY_A, Apt.VIDEO_A + Apt.VIDEO_LEN)
        assertEquals(Apt.SYNC_B, Apt.TELEMETRY_A + Apt.TELEMETRY_LEN)
        assertEquals(Apt.SPACE_B, Apt.SYNC_B + Apt.SYNC_LEN)
        assertEquals(Apt.VIDEO_B, Apt.SPACE_B + Apt.SPACE_LEN)
        assertEquals(Apt.TELEMETRY_B, Apt.VIDEO_B + Apt.VIDEO_LEN)
        assertEquals(Apt.WORDS_PER_LINE, Apt.TELEMETRY_B + Apt.TELEMETRY_LEN)
    }

    @Test
    fun `les salves portent sept creneaux`() {
        assertEquals(39, Apt.SYNC_A_PATTERN.size)
        assertEquals(39, Apt.SYNC_B_PATTERN.size)
        // Salve A : 1040 Hz, quatre mots par cycle, deux hauts par cycle.
        assertEquals(14f, Apt.SYNC_A_PATTERN.sum(), 0.001f)
        // Salve B : 832 Hz, cinq mots par cycle, trois hauts par cycle.
        assertEquals(21f, Apt.SYNC_B_PATTERN.sum(), 0.001f)
        // Les quatre premiers mots restent au noir dans les deux cas.
        assertEquals(0f, Apt.SYNC_A_PATTERN[0], 0f)
        assertEquals(0f, Apt.SYNC_B_PATTERN[3], 0f)
    }

    // ------------------------------------------------------------- décodage

    @Test
    fun `un signal fabrique se decode en lignes`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        // Le filtre met une ligne à s'établir et la dernière est tronquée.
        assertTrue("lignes rendues : ${lines.size}", lines.size >= 11)
        assertEquals(Apt.WORDS_PER_LINE, lines[0].size)
    }

    @Test
    fun `le degrade du canal A se retrouve`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = false), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] < 60)
        assertTrue("milieu = ${g[454]}", g[454] in 90..165)
        assertTrue("bord droit = ${g[900]}", g[900] > 195)
    }

    @Test
    fun `le canal B descend quand le canal A monte`() {
        val lines = AptDecoder.decodeAll(signal(14), fs)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = true), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] > 195)
        assertTrue("bord droit = ${g[900]}", g[900] < 60)
    }

    @Test
    fun `le calage ne depend pas de l-instant ou l-on commence a ecouter`() {
        // 7 431 échantillons de silence devant : le début de ligne ne tombe
        // plus sur une frontière ronde, ce qui est le cas courant en l'air.
        val lines = AptDecoder.decodeAll(signal(14, lead = 7_431), fs)
        assertTrue("lignes rendues : ${lines.size}", lines.size >= 10)
        val lv = Apt.levels(lines)
        val g = Apt.gray(Apt.channel(lines[lines.size / 2], b = false), lv[0], lv[1])
        assertTrue("bord gauche = ${g[8]}", g[8] < 60)
        assertTrue("bord droit = ${g[900]}", g[900] > 195)
    }

    @Test
    fun `la salve est reconnue franchement sur un signal propre`() {
        var q = 0f
        val d = AptDecoder(fs, object : AptDecoder.Listener {
            override fun onLine(index: Int, line: FloatArray) {}
            override fun onSync(locked: Boolean, quality: Float) { if (locked) q = quality }
        })
        val s = signal(10)
        d.feed(s, s.size)
        d.finish()
        assertTrue("qualité = $q", q > 0.6f)
        assertTrue(d.locked)
    }

    @Test
    fun `du bruit ne fabrique pas une image`() {
        val rnd = Random(7)
        val s = ShortArray(fs * 6) { (rnd.nextInt(-9000, 9000)).toShort() }
        val d = AptDecoder(fs)
        d.feed(s, s.size)
        d.finish()
        // Soit rien ne s'accroche, soit l'accrochage est franchement mauvais :
        // dans les deux cas l'appelant sait qu'il n'y a pas d'image.
        assertTrue("qualité = ${d.quality}", !d.locked || d.quality < 0.55f)
    }

    @Test
    fun `les lignes sont numerotees sans trou`() {
        val seen = ArrayList<Int>()
        val d = AptDecoder(fs, object : AptDecoder.Listener {
            override fun onLine(index: Int, line: FloatArray) { seen.add(index) }
            override fun onSync(locked: Boolean, quality: Float) {}
        })
        val s = signal(10)
        // Par petits morceaux, comme le fait la prise de son.
        var i = 0
        val chunk = 4096
        while (i < s.size) {
            val n = minOf(chunk, s.size - i)
            d.feed(s.copyOfRange(i, i + n), n)
            i += n
        }
        d.finish()
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.indices.toList(), seen)
    }

    // ------------------------------------------------------------- contraste

    @Test
    fun `une ligne de parasites ne delave pas toute l-image`() {
        val clean = ArrayList<FloatArray>()
        repeat(300) { clean.add(rawLine()) }
        val lv0 = Apt.levels(clean)
        // Une ligne saturée à cent fois le niveau utile, sur trois minutes
        // d'image : moins d'un pour cent des mots, donc écartée par les bornes.
        clean.add(FloatArray(Apt.WORDS_PER_LINE) { 100f })
        val lv1 = Apt.levels(clean)
        assertTrue("avant ${lv0[1]}, après ${lv1[1]}", lv1[1] < lv0[1] * 3f)
    }

    @Test
    fun `un tableau vide ne fait pas exploser le calcul de contraste`() {
        val lv = Apt.levels(emptyList())
        assertTrue(lv[1] > lv[0])
        val g = Apt.gray(FloatArray(10), lv[0], lv[1])
        assertEquals(10, g.size)
    }

    @Test
    fun `les valeurs hors bornes sont ramenees dans l-echelle`() {
        val g = Apt.gray(floatArrayOf(-5f, 0f, 0.5f, 1f, 12f), 0f, 1f)
        assertEquals(0, g[0])
        assertEquals(0, g[1])
        assertEquals(127, g[2])
        assertEquals(255, g[3])
        assertEquals(255, g[4])
    }

    // ------------------------------------------------------------- fabrication

    /** Une ligne d'image telle qu'un NOAA l'enverrait. */
    private fun rawLine(): FloatArray {
        val w = FloatArray(Apt.WORDS_PER_LINE)
        for (k in 0 until Apt.SYNC_LEN) w[Apt.SYNC_A + k] = Apt.SYNC_A_PATTERN[k]
        for (k in 0 until Apt.VIDEO_LEN) w[Apt.VIDEO_A + k] = k / (Apt.VIDEO_LEN - 1f)
        for (k in 0 until Apt.TELEMETRY_LEN) w[Apt.TELEMETRY_A + k] = 0.5f
        for (k in 0 until Apt.SYNC_LEN) w[Apt.SYNC_B + k] = Apt.SYNC_B_PATTERN[k]
        for (k in 0 until Apt.SPACE_LEN) w[Apt.SPACE_B + k] = 1f
        for (k in 0 until Apt.VIDEO_LEN) w[Apt.VIDEO_B + k] = 1f - k / (Apt.VIDEO_LEN - 1f)
        for (k in 0 until Apt.TELEMETRY_LEN) w[Apt.TELEMETRY_B + k] = 0.5f
        return w
    }

    /** Module ces lignes sur une sous-porteuse de 2400 Hz. */
    private fun signal(lineCount: Int, lead: Int = 0): ShortArray {
        val words = FloatArray(lineCount * Apt.WORDS_PER_LINE)
        val line = rawLine()
        for (l in 0 until lineCount) {
            System.arraycopy(line, 0, words, l * Apt.WORDS_PER_LINE, Apt.WORDS_PER_LINE)
        }
        val total = lead + (words.size.toLong() * fs / Apt.WORD_RATE).toInt() + 1
        val out = ShortArray(total)
        for (n in 0 until total) {
            val v = if (n < lead) 0f else {
                val wi = ((n - lead).toLong() * Apt.WORD_RATE / fs).toInt()
                if (wi < words.size) words[wi] else 0f
            }
            val a = 0.05 + 0.95 * v
            out[n] = (a * 12_000.0 * cos(2.0 * PI * Apt.SUBCARRIER_HZ * n / fs)).toInt().toShort()
        }
        return out
    }
}
