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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La M10 confrontée à une vraie trame, sortie d'un vrai enregistrement.
 *
 * Les essais de [MeteomodemTest] fabriquent la trame qu'ils décodent : ils
 * disent que le code est cohérent avec lui-même, pas qu'il est juste. Trois
 * versions de décodeur M10 ont passé ces essais-là sans jamais sortir une seule
 * sonde à l'écran. Celui-ci referme le piège : les cent un octets ci-dessous
 * ont été démodulés d'un enregistrement de référence du projet auto_rx, et
 * l'application doit les lire comme les lit rs1729 — même somme de contrôle,
 * même position, même numéro de série.
 *
 * L'autre moitié de l'essai remonte plus haut : on refabrique un flux de
 * demi-bits avec le codeur de la mire, on le fait relire par la recherche de
 * synchronisation, et l'on exige la trame d'origine dans les deux polarités.
 * C'est ce chaînon-là qui manquait — le décodage partait d'un demi-bit trop
 * loin et rendait des FF à l'infini, sans qu'aucun essai ne s'en aperçoive.
 */
class MeteomodemM10Test {

    /** Relit une trame écrite en hexadécimal, comme la sort un décodeur. */
    private fun hex(s: String): ByteArray {
        val parts = s.trim().split(Regex("\\s+"))
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }

    /**
     * Rafale 0 de l'enregistrement de référence, cent un octets.
     *
     * Position en plein pôle Nord et longitude nulle : c'est une sonde au banc,
     * pas en vol, et c'est très bien ainsi — le décodeur ne doit pas être plus
     * regardant que le constructeur.
     */
    private val reference = hex(
        "64 9F 20 00 00 00 00 00 00 00 00 00 46 50 3F FF " +
        "FF F4 00 00 00 00 00 02 49 F0 00 00 00 05 00 12 " +
        "08 00 00 00 00 00 00 00 00 00 00 00 00 00 A7 7B " +
        "C6 71 99 97 09 95 8C 09 01 00 27 24 00 00 00 19 " +
        "A5 F2 09 F2 09 62 03 12 5A 0B 01 00 08 00 13 00 " +
        "00 00 00 00 00 E4 0F 1A 00 82 01 FF 00 02 14 83 " +
        "DC 22 9D BA D6"
    )

    @Test
    fun la_trame_de_reference_a_la_bonne_longueur() {
        assertEquals(Meteomodem.M10_LEN, reference.size)
    }

    @Test
    fun la_somme_de_controle_de_la_trame_de_reference_tombe_juste() {
        // 0xBAD6, calculée sur les 0x63 premiers octets. C'est cette somme qui
        // permet de savoir qu'on est parti du bon demi-bit : sans elle, un
        // décalage d'un chip rend une trame plausible et fausse.
        assertEquals(0xBAD6, Meteomodem.checkM10(reference, 0x63))
        assertTrue(Meteomodem.checkOkM10(reference))
    }

    @Test
    fun un_octet_retourne_casse_la_somme_de_controle() {
        val f = reference.copyOf()
        f[0x30] = (f[0x30].toInt() xor 1).toByte()
        assertTrue(!Meteomodem.checkOkM10(f))
        assertNull(Meteomodem.parseM10(f, 403_000_000L, 0L))
    }

    @Test
    fun la_trame_de_reference_rend_ce_que_rs1729_en_lit() {
        val f = Meteomodem.parseM10(reference, 403_000_000L, 1_700_000_000_000L)
        assertNotNull("trame de référence refusée", f)
        f!!
        assertEquals("M10", f.type)
        assertEquals(90.0, f.lat, 1e-5)
        assertEquals(0.0, f.lon, 1e-5)
        assertEquals(150.0, f.altM, 0.5)
        assertEquals("803-2-10732", f.serial)
        assertEquals(157, f.frameNo)
        assertEquals(403_000_000L, f.freqHz)
    }

    @Test
    fun la_semaine_et_l_heure_gps_sont_celles_de_l_enregistrement() {
        // TOW à 0x0A vaut 0x4650 millisecondes, semaine 2048 à 0x20. Le champ
        // est en millisecondes et non en secondes : c'est m10ptu.c qui le dit,
        // en divisant par mille juste après l'avoir lu.
        var tow = 0L
        for (k in 0 until 4) tow = (tow shl 8) or (reference[0x0A + k].toLong() and 0xff)
        assertEquals(18_000L, tow)
        val week = ((reference[0x20].toInt() and 0xff) shl 8) or
            (reference[0x21].toInt() and 0xff)
        assertEquals(2048, week)
    }

    @Test
    fun le_numero_de_serie_se_lit_sur_les_cinq_octets() {
        assertEquals("803-2-10732", Meteomodem.serialM10(reference))
        // La mire émet le même, pour que la démonstration et l'enregistrement
        // de référence racontent la même sonde.
        val demo = ByteArray(Meteomodem.M10_LEN)
        for (k in Meteomodem.M10_SERIAL_DEMO.indices) {
            demo[Meteomodem.M10.SN + k] = Meteomodem.M10_SERIAL_DEMO[k]
        }
        assertEquals("803-2-10732", Meteomodem.serialM10(demo))
    }

    /** Le préambule de la M10 : le motif 1001 répété. */
    private fun preamble(n: Int) =
        ByteArray(n) { (if ((it and 3) == 0 || (it and 3) == 3) 1 else 0).toByte() }

    /** Fabrique le flux de demi-bits complet : préambule, motif, corps. */
    private fun chipsOf(frame: ByteArray, invert: Boolean): ByteArray {
        val head = preamble(400)
        val sync = Meteomodem.M10_SYNC
        val cut = Meteomodem.M10_SYNC_TO_FRAME
        val body = SondeMire.biphaseEncode(
            SondeMire.bitsOf(frame, lsbFirst = false), sync[cut].toInt() and 1)
        val all = ByteArray(head.size + cut + body.size)
        System.arraycopy(head, 0, all, 0, head.size)
        for (k in 0 until cut) all[head.size + k] = sync[k]
        System.arraycopy(body, 0, all, head.size + cut, body.size)
        if (invert) for (k in all.indices) all[k] = (all[k].toInt() xor 1).toByte()
        return all
    }

    @Test
    fun le_flux_de_demi_bits_se_relit_dans_les_deux_polarites() {
        // Une clé SDR ne garantit rien sur le sens du discriminateur : selon le
        // côté de la porteuse, tous les demi-bits sortent retournés. Le codage
        // bi-phase à marque y est insensible — encore faut-il que la recherche
        // du motif le soit aussi.
        for (invert in listOf(false, true)) {
            val chips = chipsOf(reference, invert)
            val at = Meteomodem.findSync(chips, chips.size, 0)
            assertTrue("motif introuvable (invert=$invert)", at >= 0)
            val out = ByteArray(Meteomodem.M10_LEN)
            assertTrue("trame incomplète (invert=$invert)",
                Meteomodem.frameFromChips(chips, at, out))
            assertTrue("somme fausse (invert=$invert)", Meteomodem.checkOkM10(out))
            for (k in reference.indices) {
                assertEquals("octet $k (invert=$invert)", reference[k], out[k])
            }
        }
    }

    @Test
    fun le_motif_ne_se_declenche_pas_dans_le_preambule() {
        // Le préambule est du 1001 répété, et les seize premiers demi-bits du
        // motif lui sont identiques : c'est la deuxième moitié qui distingue.
        // Avec deux erreurs tolérées et cinq positions de différence, le motif
        // ne peut pas s'accrocher au milieu du préambule.
        val head = preamble(400)
        assertEquals(-1, Meteomodem.findSync(head, head.size - 40, 0))
    }
}
