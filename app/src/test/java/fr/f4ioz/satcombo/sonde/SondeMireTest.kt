/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * L'essai de bout en bout de la chaîne radiosonde.
 *
 * Celui-ci compte plus que tous les autres, et il a une histoire. Pendant trois
 * versions, la M20 n'est jamais sortie à l'écran alors que chaque morceau du
 * décodage passait ses essais un par un : la trame se fabriquait bien, l'analyse
 * de trame marchait bien, le démodulateur marchait bien. C'est l'assemblage qui
 * était faux — la M20 était lue par le démodulateur de la M10, à deux symboles
 * par bit, et l'ancien décodage par demi-bits jetait tout. Aucun essai
 * unitaire ne
 * pouvait le voir, puisque aucun ne traversait la chaîne entière.
 *
 * D'où celui-ci : la mire fabrique le signal, on le verse dans [SondeHub]
 * exactement comme le fait la clé, et l'on exige des trames à la sortie. Si
 * quelqu'un recâble un jour les démodulateurs de travers, l'essai tombe avant
 * que l'application ne parte.
 */
class SondeMireTest {

    @After
    fun tearDown() {
        SondeHub.stop()
    }

    /** Pousse la mire dans le concentrateur comme le ferait la clé SDR. */
    private fun run(model: String, seconds: Int = 6): SondeHub.SondeState {
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = model, log = false)
        val src = SondeMire.Source(model, 48.2, -4.5, seconds)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        return st
    }

    @Test
    fun la_mire_rs41_traverse_toute_la_chaine() {
        val st = run("RS41")
        assertTrue("aucune trame RS41 décodée", st.frames > 0)
        assertEquals("RS41", st.type)
    }

    @Test
    fun la_mire_m20_traverse_toute_la_chaine() {
        // L'essai qui aurait dû exister trois versions plus tôt.
        val st = run("M20")
        assertTrue("aucune trame M20 décodée", st.frames > 0)
        assertEquals("M20", st.type)
    }

    @Test
    fun la_mire_m10_traverse_toute_la_chaine() {
        val st = run("M10")
        assertTrue("aucune trame M10 décodée", st.frames > 0)
        assertEquals("M10", st.type)
    }

    @Test
    fun le_mode_automatique_trouve_la_sonde_sans_qu_on_la_nomme() {
        // C'est le réglage par défaut : les trois décodeurs en parallèle.
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = SondeModel.AUTO, log = false)
        val src = SondeMire.Source("M20", 45.0, 5.0, 6)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        assertTrue("le mode automatique n'a rien vu", st.frames > 0)
        assertEquals("M20", st.type)
    }

    @Test
    fun la_position_decodee_est_celle_qui_a_ete_emise() {
        // Le décodage peut « sortir des trames » et rendre des coordonnées
        // fausses. On ne peut pas pour autant exiger que le dernier point soit
        // resté près du lâcher : le vol de la mire est comprimé, le ballon
        // dérive de plusieurs degrés en huit trames, et c'est voulu. Ce qu'on
        // exige, c'est que la position décodée soit l'une de celles que la mire
        // a réellement émises — la vérification est plus serrée, et elle ne
        // dépend plus de la dramaturgie du vol.
        val emitted = SondeMire.flight(48.2, -4.5, 8)
        val st = run("RS41", 8)
        val f = st.last
        assertTrue("pas de trame", f != null)
        val near = emitted.any {
            abs(it.lat - f!!.lat) < 0.001 && abs(it.lon - f.lon) < 0.001 &&
                abs(it.altM - f.altM) < 50.0
        }
        assertTrue("position hors du vol émis : ${f!!.lat}, ${f.lon}, ${f.altM}", near)
    }

    @Test
    fun le_vol_monte_eclate_et_redescend() {
        val pts = SondeMire.flight(48.0, -4.0, 300)
        val top = pts.maxByOrNull { it.altM }!!
        assertTrue("le ballon n'a pas éclaté haut : ${top.altM}", top.altM > 25_000.0)
        assertTrue("la descente manque",
            pts.last().altM < top.altM - 10_000.0)
        // La dérive doit être visible : un ballon qui reste sur place n'a
        // aucun intérêt pour éprouver l'affichage de la trace.
        val drift = abs(pts.last().lon - pts.first().lon)
        assertTrue("aucune dérive : $drift", drift > 0.1)
    }

    @Test
    fun le_signal_a_la_bonne_longueur_et_la_bonne_amplitude() {
        val pcm = SondeMire.render("M20", 48.0, -4.0, 2)
        assertEquals(2 * SondeMire.RATE, pcm.size)
        val peak = pcm.maxOf { abs(it.toInt()) }
        // Depuis la 18.7 la synthèse passe par un filtre qui arrondit les
        // fronts : la crête ne touche plus tout à fait la consigne, et c'est
        // exactement ce qu'on lui demande. On vérifie qu'elle reste dessous —
        // sinon le WAV écrête — et qu'elle n'a pas fondu.
        assertTrue("crete $peak", peak <= SondeMire.AMPLITUDE)
        assertTrue("crete $peak", peak > SondeMire.AMPLITUDE * 0.75)
    }

    @Test
    fun la_mire_reste_decodable_avec_l_ambiance() {
        // Le souffle et le fading sont là pour la démonstration, pas pour
        // saborder le décodeur : une mire bruitée doit encore sortir des
        // trames, sans quoi la case ne montrerait rien du tout.
        SondeHub.stop()
        SondeHub.start(null, SondeMire.RATE, SondeMire.DEMO_FREQ_HZ,
            source = "DEMO", model = "RS41", log = false)
        val src = SondeMire.Source("RS41", 48.2, -4.5, 6, ambience = true)
        val chunk = ShortArray(SondeMire.RATE / 4)
        while (true) {
            val n = src.read(chunk)
            if (n <= 0) break
            SondeHub.feedLive(chunk, n)
        }
        val st = SondeHub.state.value
        SondeHub.stop()
        assertTrue("trames ${st.frames}", st.frames > 0)
    }

    @Test
    fun les_profils_disent_la_bonne_largeur_de_filtre() {
        // Une RS41 dans quinze kilohertz, une Meteomodem dans vingt-deux :
        // c'est tout l'intérêt de nommer le modèle.
        assertEquals(Rs41.BANDWIDTH_HZ, SondeModel.bandwidthFor("RS41"))
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeModel.bandwidthFor("M20"))
        assertEquals(Meteomodem.BANDWIDTH_HZ, SondeModel.bandwidthFor(SondeModel.AUTO))
        assertTrue(SondeModel.wantsRs41(SondeModel.AUTO))
        assertTrue(SondeModel.wantsM20(SondeModel.AUTO))
        assertTrue(SondeModel.wantsM10(SondeModel.AUTO))
        assertTrue(SondeModel.wantsRs41("RS41"))
        assertTrue(!SondeModel.wantsM10("RS41"))
    }

    @Test
    fun la_m10_reste_jouable_a_quarante_quatre_kilohertz() {
        // 44 100 / 9 616 = 4,59 échantillons par chip. La 18.5 attendait 2,29
        // ici, parce qu'elle prenait 9616 pour un débit binaire au lieu d'un
        // débit de chips ; l'essai gardait donc la faute au chaud.
        val m10 = SondeModel.byId("M10")
        val s = m10.samplesPerChip(SondeMire.RATE)
        assertTrue("échantillons par symbole : $s", s > 4.5 && s < 4.7)
        assertTrue("la M10 ne devrait pas être signalée comme limite",
            !m10.marginal(SondeMire.RATE))
        // À huit kilohertz, en revanche, elle ne passe plus.
        assertTrue(m10.marginal(8_000))
    }
}
