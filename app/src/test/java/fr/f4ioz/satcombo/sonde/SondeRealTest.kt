/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

import org.junit.Assume
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Le décodeur devant de vraies sondes.
 *
 * Le banc mesure la chaîne sur un signal qu'on fabrique nous-mêmes ; il dit
 * donc surtout que l'émetteur et le récepteur sont d'accord entre eux. Ces
 * essais-ci partent des enregistrements de référence du projet
 * radiosonde_auto_rx — de l'air, capté par une vraie station — démodulés en FM
 * puis versés dans le concentrateur tel quel. Rien ici n'est de notre
 * fabrication sauf le discriminateur.
 *
 * Les fichiers ne sont pas dans le dépôt : ils pèsent quatre-vingt-dix
 * mégaoctets pièce. L'essai s'efface tout seul quand ils ne sont pas là.
 */
class SondeRealTest {

    private fun run(name: String, rate: Int, model: String): Triple<Int, Int, Int> {
        val f = File("/home/claude/samples/$name")
        Assume.assumeTrue("échantillon absent : $name", f.exists())
        SondeHub.stop()
        SondeHub.start(null, rate, 404_000_000L, source = "TEST", model = model, log = false)
        val chunk = ShortArray(4096)
        DataInputStream(f.inputStream().buffered(1 shl 20)).use { s ->
            val raw = ByteArray(chunk.size * 2)
            while (true) {
                var got = 0
                while (got < raw.size) {
                    val k = s.read(raw, got, raw.size - got)
                    if (k <= 0) break
                    got += k
                }
                if (got < 2) break
                val n = got / 2
                for (i in 0 until n) {
                    chunk[i] = ((raw[2 * i].toInt() and 0xff) or
                        (raw[2 * i + 1].toInt() shl 8)).toShort()
                }
                SondeHub.feedLive(chunk, n)
                if (got < raw.size) break
            }
        }
        val st = SondeHub.state.value
        val fl = SondeHub.currentFlight
        SondeHub.stop()
        println("REEL $name modele=$model rate=$rate -> trames=${st.frames} " +
            "rejets=${st.rejected} serie=${fl?.serial ?: "-"} points=${fl?.count ?: 0}")
        return Triple(st.frames, st.rejected, fl?.count ?: 0)
    }

    @Test fun m10_96k() { run("m10_96000.pcm", 96_000, "M10") }
    @Test fun m10_48k() { run("m10_48000.pcm", 48_000, "M10") }
    @Test fun m10_44k() { run("m10_44100.pcm", 44_100, "M10") }
    @Test fun rs41_96k() { run("rs41_96000.pcm", 96_000, "RS41") }
    @Test fun rs41_48k() { run("rs41_48000.pcm", 48_000, "RS41") }
    @Test fun rs41_44k() { run("rs41_44100.pcm", 44_100, "RS41") }
}
