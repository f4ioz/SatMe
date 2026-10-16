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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioSystem

/**
 * By hand only, on a PC with the engine installed: a recording played in
 * real time through « Phonie → texte » and the real helper. Skipped unless
 * SATME_PHONIE_PYTHON (the interpreter) and SATME_PHONIE_WAV (16-bit mono)
 * are set; SATME_PHONIE_DOSSIER: where stt/ and phonie/ are;
 * SATME_PHONIE_CIBLE: translated into that language ("fr", "whisper"…);
 * SATME_PHONIE_CALCUL: "cuda" or "cpu".
 */
class PhonieReelleTest {

    @Test fun `un enregistrement transcrit par le vrai moteur`() {
        val python = System.getenv("SATME_PHONIE_PYTHON")
        val wav = System.getenv("SATME_PHONIE_WAV")
        assumeTrue(python != null && wav != null)
        val dossier = File(System.getenv("SATME_PHONIE_DOSSIER") ?: System.getProperty("java.io.tmpdir"))
        val st = StockageMemoire()
        val p = PhoniePc(st, InstallPhoniePc(dossier), File(dossier, "phonie"))
        p.python = python!!
        p.modele = System.getenv("SATME_PHONIE_MODELE") ?: "large-v3"
        System.getenv("SATME_PHONIE_CALCUL")?.let { p.calcul = it }
        System.getenv("SATME_PHONIE_CIBLE")?.let { p.traduire = true; p.cible = it }
        p.contexte = { 10_489_750_000L to "QO-100" }
        val t0 = System.currentTimeMillis()
        p.actif = true
        while (p.etat != "prêt" && System.currentTimeMillis() - t0 < 120_000) Thread.sleep(100)
        println("moteur : ${p.etat} ${p.info} ${p.erreur} (${System.currentTimeMillis() - t0} ms)")
        val ais = AudioSystem.getAudioInputStream(File(wav!!))
        val rate = ais.format.sampleRate.toInt()
        val octets = ais.readAllBytes()
        val pcm = ShortArray(octets.size / 2) { ((octets[2 * it].toInt() and 0xFF) or (octets[2 * it + 1].toInt() shl 8)).toShort() }
        val bloc = rate / 10
        var i = 0
        val debut = System.currentTimeMillis()
        val vus = HashSet<Long>()
        fun montre() = p.bulles().filter { vus.add(it.id) }.forEach { b ->
            val fin = b.t + (b.dureeS * 1000).toLong()
            println(String.format("[+%5.1f s, %4.1f s de voix, calcul %.2f s, en retard de %.1f s, %s %.2f, sûr %.0f %%] %s%s%s",
                (b.t - debut) / 1000.0, b.dureeS, b.calculS, (System.currentTimeMillis() - fin) / 1000.0, b.langue, b.probaLangue, b.confiance * 100, b.texte,
                if (b.traduction.isNotBlank()) "\n    (${b.langueCible}) " + b.traduction else "",
                if (b.elements.isNotEmpty()) "\n    → " + b.elements.joinToString { "${it.genre} ${it.valeur} (${it.score})" } else ""))
        }
        while (i < pcm.size) {
            val n = minOf(bloc, pcm.size - i)
            p.ecouteCarte(pcm.copyOfRange(i, i + n), n, rate)
            i += n
            val attendu = debut + i * 1000L / rate
            while (System.currentTimeMillis() < attendu) Thread.sleep(5)
            montre()
        }
        // Two seconds of silence closing the last over.
        repeat(20) { p.ecouteCarte(ShortArray(bloc), bloc, rate); Thread.sleep(100); montre() }
        val fin = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < fin && (p.enFile > 0 || p.retardS() > 0)) { Thread.sleep(100); montre() }
        Thread.sleep(500); montre()
        println("perdus : ${p.perdus}, calcul moyen ${p.calculMoyenS} s" + if (p.traductionErreur.isNotBlank()) " · ${p.traductionErreur}" else "")
        p.actif = false
    }
}
