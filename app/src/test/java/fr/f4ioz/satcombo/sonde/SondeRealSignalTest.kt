/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les essais nés de la confrontation avec de vraies sondes.
 *
 * Jusqu'en 18.5, tout le décodage de radiosondes avait été écrit et vérifié
 * contre lui-même : la mire fabriquait une trame, le décodeur la relisait,
 * l'essai passait au vert. Il aura suffi que les enregistrements de référence de
 * radiosonde_auto_rx arrivent sur la machine pour que trois défauts sortent du
 * bois en une soirée — trois défauts qu'aucun essai n'aurait pu voir, parce que
 * la mire et le décodeur partageaient exactement les mêmes erreurs.
 *
 * D'où ce fichier, et sa règle : chaque essai qu'on trouve ici affirme une
 * valeur *mesurée sur l'air*, pas une valeur que le programme se donne à
 * lui-même. Un essai qui compare un programme à lui-même ne prouve rien sur le
 * monde ; il prouve seulement que le programme est cohérent.
 */
class SondeRealSignalTest {

    private val RATE = 44_100.0

    // ------------------------------------------------------------ en-tête

    @Test
    fun `l en-tete cherche est celui que la RS41 emet vraiment`() {
        // Constante relevée sur l'air, et non déduite du code.
        val surLAir = intArrayOf(0x10, 0xB6, 0xCA, 0x11, 0x22, 0x96, 0x12, 0xF8)
        assertEquals(surLAir.size, Rs41.HEADER_RAW.size)
        for (k in surLAir.indices) {
            assertEquals("octet $k de l'en-tête sur l'air",
                surLAir[k], Rs41.HEADER_RAW[k])
        }
        // Et une fois le masque retiré, c'est l'autre constante — celle que la
        // 18.5 cherchait par erreur dans le flux reçu.
        val desembrouille = intArrayOf(0x86, 0x35, 0xF4, 0x40, 0x93, 0xDF, 0x1A, 0x60)
        for (k in desembrouille.indices) {
            assertEquals("octet $k de l'en-tête désembrouillé",
                desembrouille[k], Rs41.HEADER[k])
        }
    }

    @Test
    fun `le decodeur trouve l en-tete tel qu il passe sur l air`() {
        val buf = ByteArray(64) { 0x5A }
        for (k in Rs41.HEADER_RAW.indices) buf[20 + k] = Rs41.HEADER_RAW[k].toByte()
        assertEquals(20, Rs41.findHeader(buf, 0, buf.size))
    }

    // ------------------------------------------------------- octet de type

    @Test
    fun `l octet de type se lit apres la parite Reed-Solomon`() {
        assertEquals(0x38, Rs41.TYPE_AT)
        assertEquals(Rs41.TYPE_AT + 1, Rs41.BLOCKS_AT)
        // Huit octets d'en-tête, puis quarante-huit de parité : 8 + 48 = 0x38.
        assertEquals(Rs41.TYPE_AT, Rs41.HEADER.size + 48)
    }

    @Test
    fun `une parite quelconque ne trouble plus la lecture du type`() {
        val frame = SondeTestFrames.rs41(48.5, -4.0, 12_000.0,
            east = 3.0, north = 4.0, up = -5.0, sats = 9, serial = "P1234567")
        // La parité Reed-Solomon est du bruit du point de vue de SatMe, qui ne
        // corrige pas les erreurs. On la remplit donc pour de bon — et en
        // particulier l'octet 8, exactement là où la 18.5 allait chercher le
        // type de trame, c'est-à-dire en plein milieu de la parité.
        for (k in 0x08 until Rs41.TYPE_AT) frame[k] = ((k * 37 + 11) and 0xff).toByte()
        assertTrue("l'octet 8 doit être autre chose que 0x0F",
            (frame[8].toInt() and 0xff) != 0x0F)

        Rs41.descramble(frame)                  // telle qu'elle passe sur l'air
        val hit = Rs41.scan(frame, 0, frame.size, 404_000_000L, 1L)
        assertNotNull("la trame doit sortir malgré une parité quelconque", hit)
        assertEquals("P1234567", hit!!.frame.serial)
        assertEquals(48.5, hit.frame.lat, 1e-6)
    }

    // ------------------------------------------------------------ horloge

    @Test
    fun `le compte de bits tient sur un signal bruite`() {
        val rnd = java.util.Random(20_240_618L)
        val bits = ByteArray(6_000) { rnd.nextInt(2).toByte() }
        val propre = SondeTestFrames.modulate(bits, RATE, Rs41.BAUD, leadingBits = 64)
        // Souffle gaussien à la moitié de l'amplitude utile : de quoi faire
        // franchir le zéro plusieurs fois par symbole, ce qui est exactement ce
        // qui faisait dérailler l'horloge de la 18.5.
        val bruite = ShortArray(propre.size) {
            (propre[it] + Math.round(rnd.nextGaussian() * 5_000.0).toInt())
                .coerceIn(-32_000, 32_000).toShort()
        }
        val d = SondeDemod(RATE, Rs41.BAUD, 8_192)
        d.feedBits(bruite, bruite.size, null)

        val attendu = bruite.size / (RATE / Rs41.BAUD)
        val obtenu = d.bitsAvailable.toDouble()
        val ecart = kotlin.math.abs(obtenu - attendu) / attendu
        // La 18.5 tombait à 99,1 % : un pour cent de bits perdus, soit
        // vingt-cinq par trame de trois cent vingt octets, donc jamais une
        // trame entière. Un défaut d'un pour cent qui coûte cent pour cent du
        // résultat.
        assertTrue("bits produits : $obtenu pour $attendu attendus (écart ${ecart * 100} %)",
            ecart < 0.005)
    }

    @Test
    fun `une trame RS41 bruitee est encore retrouvee`() {
        val rnd = java.util.Random(1_866L)
        val lat = 48.44425; val lon = -4.41238; val alt = 18_300.0
        val frame = SondeTestFrames.rs41(lat, lon, alt,
            east = 12.0, north = -9.0, up = 5.0, sats = 11, serial = "S4351234")
        Rs41.descramble(frame)
        val bits = SondeTestFrames.bitsOf(frame, lsbFirst = true)
        val propre = SondeTestFrames.modulate(bits, RATE, Rs41.BAUD, leadingBits = 128)
        val bruite = ShortArray(propre.size) {
            (propre[it] + Math.round(rnd.nextGaussian() * 3_000.0).toInt())
                .coerceIn(-32_000, 32_000).toShort()
        }

        val d = SondeDemod(RATE, Rs41.BAUD, 4_096)
        d.feedBits(bruite, bruite.size, null)
        var hit: Rs41.Hit? = null
        for (off in 0 until 8) {
            val n = d.packBytes(off)
            hit = Rs41.scan(d.bytes, 0, n, 404_000_000L, 3L)
            if (hit != null) break
        }
        assertNotNull("aucune trame retrouvée sous le souffle", hit)
        assertEquals(lat, hit!!.frame.lat, 1e-6)
        assertEquals(lon, hit.frame.lon, 1e-6)
        assertEquals(alt, hit.frame.altM, 0.05)
    }

    // ---------------------------------------------------------------- M10

    @Test
    fun `la M10 tourne sur ses chips et non sur leur double`() {
        // 9616 est le débit de chips, pas le débit binaire : le facteur deux du
        // Manchester est déjà dedans. Le multiplier encore réglait le
        // démodulateur sur 19 232 chips par seconde, chaque chip était lu deux
        // fois, et le décodage Manchester ne voyait plus que des paires plates.
        assertEquals(9_616.0, Meteomodem.M10_CHIP_RATE, 1e-9)
        assertEquals(4_808.0, Meteomodem.M10_BAUD, 1e-9)
        assertEquals(Meteomodem.M10_CHIP_RATE, SondeModel.M10.chipRate, 1e-9)
        assertEquals(Meteomodem.M10_BAUD, SondeModel.M10.baud, 1e-9)
        assertEquals(4.5861, SondeModel.M10.samplesPerChip(44_100), 1e-4)
        assertTrue("la M10 n'est pas un cas limite à 44,1 kHz",
            !SondeModel.M10.marginal(44_100))
    }

    @Test
    fun `un flux Manchester lu a la bonne cadence ne donne que des plages de un et deux`() {
        // Signature exacte d'un codage Manchester lu à la bonne cadence :
        // l'histogramme des longueurs de plage du flux de chips ne contient que
        // des un et des deux. C'est ce qu'on observe sur l'enregistrement réel,
        // et ce que la mire doit reproduire.
        val rnd = java.util.Random(910L)
        val bits = ByteArray(2_000) { rnd.nextInt(2).toByte() }
        val chips = SondeMire.biphaseEncode(bits)
        var plage = 1
        var maxPlage = 1
        for (k in 1 until chips.size) {
            if (chips[k] == chips[k - 1]) plage++ else { if (plage > maxPlage) maxPlage = plage; plage = 1 }
        }
        if (plage > maxPlage) maxPlage = plage
        assertEquals("plage la plus longue du flux de chips", 2, maxPlage)
        assertEquals("deux chips par bit", bits.size * 2, chips.size)
    }
}
