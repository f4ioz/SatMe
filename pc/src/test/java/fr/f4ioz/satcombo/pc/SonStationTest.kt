/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import fr.f4ioz.satcombo.sstv.SstvTestSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** The PC's sound without a sound card: blocks handed out as the card would. */
class SonStationTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-son-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private fun sonStation(): Pair<SonStation, SonPc> {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val son = SonPc()
        return SonStation(son, st, File(dossier, "enregistrements"), File(dossier, "sstv")) to son
    }

    /** Feeds [pcm] in blocks of 2048, as the card does. */
    private fun verse(son: SonPc, pcm: ShortArray, rate: Int) {
        var i = 0
        val b = ShortArray(2048)
        while (i < pcm.size) {
            val n = minOf(b.size, pcm.size - i)
            System.arraycopy(pcm, i, b, 0, n)
            son.distribue(b, n, rate)
            i += n
        }
    }

    @Test
    fun une_image_sstv_recue_est_gardee_en_png_avec_sa_fiche() {
        val (s, son) = sonStation()
        val m = SstvMode.byName("Robot 36")!!
        val img = IntArray(m.width * m.height) { i -> if (i / m.width < m.height / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        verse(son, SstvTestSignal.encode(m, img, 48_000), 48_000)
        val images = s.images()
        assertEquals(1, images.size)
        val i = images.single()
        assertEquals("Robot 36", i.mode)
        assertEquals("live", i.source)
        assertTrue(i.complete)
        val png = ImageIO.read(s.fichierImage(i.fileName)!!)
        assertEquals(m.width, png.width)
        // Black on top, white below, as sent.
        assertTrue((png.getRGB(m.width / 2, 10) and 0xFF) < 60)
        assertTrue((png.getRGB(m.width / 2, m.height - 10) and 0xFF) > 200)
        assertTrue(File(dossier, "sstv/" + SstvMeta.sidecarName(i.fileName)).isFile)
    }

    @Test
    fun un_enregistrement_est_un_wav_qui_se_lit_meme_avant_d_etre_ferme() {
        val (s, son) = sonStation()
        // No card started: refused, in words.
        assertTrue(s.demarreEnregistrement()!!.contains("carte son"))
        // As if the card ran at 48 kHz: a 1 kHz tone, 3 s.
        val f = son.javaClass.getDeclaredField("_etat").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(son) as kotlinx.coroutines.flow.MutableStateFlow<SonPc.Etat>).value = SonPc.Etat(actif = true, appareil = "essai", rate = 48_000)
        assertEquals(null, s.demarreEnregistrement())
        val ton = ShortArray(3 * 48_000) { i -> (8000 * Math.sin(2 * Math.PI * 1000 * i / 48_000)).toInt().toShort() }
        verse(son, ton, 48_000)
        val fichier = s.enregistrements().single()
        assertTrue(fichier.name.matches(Regex("SatMe_SAT_\\d{8}_\\d{6}Z\\.wav")))
        // The header already counts what is written (rewritten each second).
        val tete = ByteBuffer.wrap(fichier.readBytes().copyOf(44)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24_000, tete.getInt(24))
        assertTrue(tete.getInt(40) >= 2 * 24_000 * 2)
        s.arreteEnregistrement()
        val fin = ByteBuffer.wrap(fichier.readBytes().copyOf(44)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3 * 24_000 * 2, fin.getInt(40))
        assertEquals(44L + 3 * 24_000 * 2, fichier.length())
    }

    // ------------------------------------------------------------ stopped after the pass

    private var heure = 1_000_000_000L

    /** A station whose card "runs" at 48 kHz, the satellite followed at [el]°, the clock in hand. */
    private fun aLHeure(): Triple<SonStation, StationPc, (Double) -> Unit> {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, { heure })
        val son = SonPc()
        val f = son.javaClass.getDeclaredField("_etat").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(son) as kotlinx.coroutines.flow.MutableStateFlow<SonPc.Etat>).value = SonPc.Etat(actif = true, appareil = "essai", rate = 48_000)
        val e = st.javaClass.getDeclaredField("_etat").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flux = e.get(st) as kotlinx.coroutines.flow.MutableStateFlow<EtatPc>
        val sat = fr.f4ioz.satcombo.data.TleEntry("RS-44", "1 44909U", "2 44909")
        val pose: (Double) -> Unit = { el -> flux.value = flux.value.copy(suivi = sat,
            position = fr.f4ioz.satcombo.data.SatPosition(0.0, el, 2000.0, 0.0, 1000.0, 0.0, 0.0, false)) }
        return Triple(SonStation(son, st, File(dossier, "enregistrements"), File(dossier, "sstv"), { heure }), st, pose)
    }

    @Test
    fun a_la_main_avec_l_option_il_s_arrete_30_s_apres_le_los() {
        val (s, _, el) = aLHeure()
        s.coupeLos = true
        el(-5.0)
        assertEquals(null, s.demarreEnregistrement())
        // Started before AOS: it waits for the satellite to rise.
        heure += 60_000L; s.tic()
        assertTrue(s.etat.value.enregistrement.isNotEmpty())
        el(20.0); heure += 1_000L; s.tic()
        el(-0.5); heure += 1_000L; s.tic()
        heure += 29_000L; s.tic()
        assertTrue(s.etat.value.enregistrement.isNotEmpty())
        heure += 2_000L; s.tic()
        assertEquals("", s.etat.value.enregistrement)
    }

    @Test
    fun a_la_main_sans_l_option_il_continue() {
        val (s, _, el) = aLHeure()
        el(20.0)
        s.demarreEnregistrement(); s.tic()
        el(-5.0); s.tic(); heure += 120_000L; s.tic()
        assertTrue(s.etat.value.enregistrement.isNotEmpty())
        s.arreteEnregistrement()
    }

    @Test
    fun automatique_arrete_a_la_main_il_ne_repart_pas_avant_le_passage_suivant() {
        val (s, _, el) = aLHeure()
        s.enregAuto = true
        el(20.0); s.tic()
        assertTrue(s.etat.value.auto)
        s.arreteEnregistrement()
        heure += 1_000L; s.tic()
        assertEquals("", s.etat.value.enregistrement)
        // Set, then up again: the next pass is recorded.
        el(-5.0); heure += 1_000L; s.tic()
        el(10.0); heure += 1_000L; s.tic()
        assertTrue(s.etat.value.enregistrement.isNotEmpty())
        s.arreteEnregistrement()
    }

    @Test
    fun le_mode_automatique_enregistre_son_passage_5_s_avant_l_aos() {
        val (s, st, el) = aLHeure()
        val aos = heure + 60_000L
        val p = fr.f4ioz.satcombo.data.SatPass("RS-44", 44909, aos, aos + 600_000L, 40.0, 0.0, 0.0, false, false)
        val e = st.javaClass.getDeclaredField("_etat").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flux = e.get(st) as kotlinx.coroutines.flow.MutableStateFlow<EtatPc>
        el(-3.0)
        flux.value = flux.value.copy(passages = listOf(p))
        st.settings.veilleSats = setOf(44909); st.settings.veilleActive = true
        s.tic()
        assertEquals("", s.etat.value.enregistrement)
        heure = aos - 4_000L; s.tic()
        assertTrue(s.etat.value.auto)
        // The satellite never seen (another one followed by hand meanwhile): stopped at LOS + 30 s all the same.
        heure = p.losEpochMs + 31_000L; s.tic()
        assertEquals("", s.etat.value.enregistrement)
        // And not started again for the same pass.
        heure = p.losEpochMs + 20_000L; s.tic()
        assertEquals("", s.etat.value.enregistrement)
    }

    @Test
    fun le_decodeur_cw_ecrit_ce_que_la_carte_entend_quand_on_l_a_choisi() {
        val (s, son) = sonStation()
        val rate = 48_000
        val manip = listOf(false to 500.0) + fr.f4ioz.satcombo.cw.DecodeurCw.manipulation("CQ DE F4IOZ K", 20) + listOf(false to 1200.0)
        val l = ArrayList<Short>(); var t = 0L
        for ((on, ms) in manip) repeat((ms * rate / 1000).toInt()) {
            l += ((if (on) 0.3 * Math.sin(2 * Math.PI * 700 * t / rate) else 0.0) * 32000).toInt().toShort(); t++
        }
        val pcm = l.toShortArray()
        verse(son, pcm, rate)
        assertEquals("", s.cw.texte())            // off unless chosen
        s.cw.actif = true
        verse(son, pcm, rate)
        assertTrue(s.cw.texte(), s.cw.texte().trim().endsWith("CQ DE F4IOZ K"))
        assertTrue(s.cw.wpm in 17..23)
    }
}
