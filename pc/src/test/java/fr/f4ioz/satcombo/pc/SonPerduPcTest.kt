/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.audio.BilanEnregistrement
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvEncoder
import fr.f4ioz.satcombo.sstv.SstvMeta
import fr.f4ioz.satcombo.sstv.SstvMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * « Son perdu » end to end: a made-up card losing 0.7 s in the middle of an
 * SSTV picture, captured by SatMe-PC's sound while a recording runs — the
 * hole told in the Son state, the picture's sidecar, the recording's own,
 * the warning line, the recording as long as the pass.
 */
class SonPerduPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-sonperdu-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    @Test
    fun un_trou_pendant_une_image_est_comble_et_dit_partout() {
        val rate = 48_000
        val m = SstvMode.byName("Robot 36")!!
        val img = IntArray(m.width * m.height) { i -> 0xFF000000.toInt() or if ((i % m.width) < m.width / 2) 0xFFFFFF else 0x2040A0 }
        val signal = SstvEncoder.encode(m, img, rate, leadMs = 500.0, trailMs = 500.0) + ShortArray(rate)
        val lecture = ((0.5 + 0.9 + 100 * m.blockMs / 1000.0) * rate / 2048).toInt()
        val l = LigneVirtuelle(signal, rate, tamponMs = 85, stalls = mapOf(lecture to 700))
        // One clock for all: the made-up card's.
        val debut = 1_793_000_000_000L
        val heure = { debut + (l.tNs - 1_000_000_000L) / 1_000_000L }
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee, heure)
        val son = SonPc().apply { horloge = heure }
        val avis = ArrayList<String>()
        son.journal = { avis += it }
        val s = SonStation(son, st, File(dossier, "enregistrements"), File(dossier, "sstv"), heure)
        val c = son.lance(l, rate, "carte d'essai", nanos = l::nanos, threads = false)
        assertEquals(null, s.demarreEnregistrement())
        while (c.pasLecture()) c.pasDistribution(0)
        while (c.enFile > 0) c.pasDistribution(0)
        s.arreteEnregistrement()

        val perdu = l.jetes * 1000 / rate
        // The Son state.
        val e = son.etat.value
        assertEquals(1, e.perdus)
        assertTrue("${e.perduMs} pour $perdu", abs(e.perduMs - perdu) <= 25)
        // The warning line.
        assertTrue(avis.single(), avis.single().startsWith("son : « carte d'essai » à 48000 Hz — ") && avis.single().contains("perdus (tampon de la carte débordé") &&
            avis.single().endsWith("comblés de silence"))
        // The picture: whole, its sidecar says how much sound it lost.
        val image = s.images().single()
        assertEquals(m.name, image.mode)
        assertTrue("sonPerdu=${image.sonPerduMs}", abs(image.sonPerduMs - perdu) <= 25)
        val fiche = File(dossier, "sstv/" + SstvMeta.sidecarName(image.fileName)).readText()
        assertTrue(fiche, fiche.contains("sonPerdu=${image.sonPerduMs}"))
        // The recording: as long as it lasted (the hole is silence in it), its sidecar and its verdict say so.
        val wav = s.enregistrements().single()
        val (_, sonMs) = BilanEnregistrement.dureeWav(wav)!!
        val t = BilanEnregistrement.Temps.decode(BilanEnregistrement.fichierTemps(wav).readText())!!
        assertEquals(1, t.trous)
        assertTrue(abs(t.combleMs - perdu) <= 25)
        assertTrue("$sonMs pour ${t.finMs - t.debutMs}", abs(sonMs - (t.finMs - t.debutMs)) <= 120)
        val b = BilanEnregistrement.bilan(wav, SstvMeta.debutEnregistrement(wav.name))!!
        assertTrue(b.parTemps && b.perte)
        assertTrue(b.texte(), b.texte().contains("comblés de silence (1 trou)"))
    }
}
