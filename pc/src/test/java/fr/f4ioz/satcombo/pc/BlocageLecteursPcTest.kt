/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.datv.GardeImage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A player that stops reading its input and ignores SIGTERM (ffmpeg seen
 * so for hours): stopping never waits on it, a new start works, the
 * watchdog starts it again.
 */
class BlocageLecteursPcTest {
    private lateinit var dossier: File
    private val processus = mutableListOf<Process>()

    @Before fun prepare() {
        assumeFalse(OutilsPc.windows)
        dossier = Files.createTempDirectory("satme-blocage").toFile()
    }

    @After fun range() {
        processus.forEach { runCatching { it.toHandle().descendants().forEach { d -> d.destroyForcibly() }; it.destroyForcibly() } }
        // Every fake ffmpeg started, by its own PID.
        File(dossier, "pids").takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }
            ?.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        dossier.deleteRecursively()
    }

    /** Never reads its stdin, ignores SIGTERM (inherited through exec). */
    private fun sourd(): File = script("ffmpeg", "echo $$ >> '${dossier.absolutePath}/pids'\ntrap '' TERM\nexec sleep 300\n")

    private fun script(nom: String, corps: String): File =
        File(dossier, nom).apply { writeText("#!/bin/bash\n$corps"); setExecutable(true) }

    private fun lancements(): Int = File(dossier, "pids").takeIf { it.isFile }?.readLines()?.count { it.isNotBlank() } ?: 0

    private fun attends(ms: Long, ok: () -> Boolean): Boolean {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) { if (ok()) return true; Thread.sleep(50) }
        return ok()
    }

    private fun vivant(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test fun `ArretProcessus revient aussitot meme si une ecriture est bloquee`() {
        val p = ProcessBuilder(sourd().path).start().also { processus += it }
        val ecrit = thread(isDaemon = true) { runCatching { val o: OutputStream = p.outputStream; repeat(1000) { o.write(ByteArray(65_536)); o.flush() } } }
        // The pipe full: the writer waits on it, holding the stream.
        Thread.sleep(500)
        assertTrue(ecrit.isAlive)
        val t0 = System.nanoTime()
        ArretProcessus.arrete(p, 300L)
        assertTrue("l'appelant n'attend pas", (System.nanoTime() - t0) / 1_000_000 < 200)
        // SIGTERM ignored: killed after the delay, the write fails, its thread ends.
        assertTrue(p.waitFor(3, TimeUnit.SECONDS))
        ecrit.join(3000)
        assertFalse("l'écriture bloquée est libérée", ecrit.isAlive)
    }

    @Test fun `une entree de lecteur ne bloque jamais celui qui donne`() {
        val p = ProcessBuilder(sourd().path).start().also { processus += it }
        val e = EntreeLecteurPc(p.outputStream, "test", capacite = 8)
        val t0 = System.nanoTime()
        repeat(200) { e.donne(ByteArray(65_536)) }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
        assertTrue("des blocs perdus plutôt qu'attendre", e.perdus > 0)
        assertTrue(attends(2000) { e.bloqueeDepuisMs() != null })
        ArretProcessus.arrete(p, 200L)
        assertTrue(attends(3000) { e.finie })
        assertNull(e.bloqueeDepuisMs())
    }

    @Test fun `ArretBorne ne reste pas sur une partie bloquee`() {
        val dits = mutableListOf<String>()
        val a = ArretBorne(300L, 10_000L) { synchronized(dits) { dits += it } }
        val verrou = Object()
        synchronized(verrou) {
            val t0 = System.nanoTime()
            assertFalse(a.fais("bloquee") { synchronized(verrou) {} })
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
        }
        var fait = false
        assertTrue(a.fais("suivante") { fait = true })
        assertTrue(fait)
        assertTrue(dits.any { it.contains("bloquee") })
    }

    /** A LongMynd that sends a transport stream to its TS port (its third argument) and nothing else. */
    private fun faussesCommandes(garde: GardeImage): DatvPc {
        val longmynd = script("longmynd", "trap 'exit 0' TERM\nwhile :; do head -c 13160 /dev/zero > /dev/udp/127.0.0.1/\$3 2>/dev/null; sleep 0.01; done\n")
        val ff = sourd()
        val d = DatvPc(StockageMemoire(), File(dossier, "enr"), trouveFfmpeg = { ff.path }, gardeFige = garde)
        d.chemin = longmynd.path
        d.regleSon(false)
        return d
    }

    @Test fun `l arret de DatvPc revient vite sur un ffmpeg sourd et un nouveau demarrage marche`() {
        val d = faussesCommandes(GardeImage(figeMs = 600_000L))
        assertNull(d.demarre(d.reglage.copy(son = false)))
        try {
            assertTrue("ffmpeg lancé avec les premiers paquets", attends(5000) { d.ffmpegLecteur != null })
            val premier = d.ffmpegLecteur!!.pid()
            // Its pipe full: the feeding writes wait on it from now on.
            Thread.sleep(1500)
            val t0 = System.nanoTime()
            d.arrete()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("arrêt en $ms ms", ms < 4000)
            assertNull(d.ffmpegLecteur)
            assertTrue("le ffmpeg sourd est tué", attends(4000) { !vivant(premier) })
            // Started again: works, with a new ffmpeg.
            assertNull(d.demarre(d.reglage.copy(son = false)))
            assertTrue(attends(5000) { d.ffmpegLecteur != null && d.ffmpegLecteur!!.pid() != premier })
            assertEquals(2, lancements())
        } finally {
            val t0 = System.nanoTime()
            d.arrete()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 4000)
        }
    }

    @Test fun `le chien de garde relance un ffmpeg qui ne prend plus le flux`() {
        val d = faussesCommandes(GardeImage(figeMs = 1_500L, pauseMs = 0L))
        assertNull(d.demarre(d.reglage.copy(son = false)))
        try {
            assertTrue(attends(5000) { d.ffmpegLecteur != null })
            val premier = d.ffmpegLecteur!!.pid()
            // Not locked (no status from this LongMynd): only its input not taken makes it start again.
            assertTrue("relancé", attends(10_000) { lancements() >= 2 })
            assertTrue(d.relancesImage >= 1)
            assertTrue("l'ancien est tué", attends(4000) { !vivant(premier) })
            assertNotNull(d.ffmpegLecteur)
        } finally { d.arrete() }
    }
}
