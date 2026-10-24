/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Redémarrer SatMe-PC »: offered only where it is safe; the same program and arguments, told to wait. Nothing is started here. */
class RedemarragePcTest {

    private val java = ProcessHandle.current().info().command().orElse("")

    @Test
    fun pas_sous_systemd_ni_depuis_un_terminal() {
        assertNotNull(RedemarragePc(arrayOf(), env = { if (it == "INVOCATION_ID") "abc" else null }, console = { false }).refus())
        assertNotNull(RedemarragePc(arrayOf(), env = { null }, console = { true }).refus())
    }

    @Test
    fun la_meme_commande_avec_le_numero_du_processus() {
        val r = RedemarragePc(arrayOf("--port", "8095", "--apres", "1", "--reseau"), env = { null }, console = { false },
            classpath = "/opt/satme/lib/pc.jar:/opt/satme/lib/moteur.jar", optionsJvm = { listOf("-Xmx256m", "-agentlib:jdwp=transport=dt_socket") })
        val c = r.commande()
        if (!java.endsWith("java")) return
        assertNotNull(c); c!!
        assertTrue(c.contains("-Xmx256m"))
        assertTrue("pas le débogueur", c.none { it.startsWith("-agentlib:jdwp") })
        val i = c.indexOf(RedemarragePc.MAIN)
        assertEquals("-cp", c[i - 2])
        // The old --apres replaced by this process's number, the rest as given.
        assertEquals(listOf("--port", "8095", "--reseau", "--apres", ProcessHandle.current().pid().toString()), c.subList(i + 1, c.size))
        assertNull(r.refus())
    }

    @Test
    fun attendre_un_processus_deja_parti_ne_bloque_pas() {
        val t = System.currentTimeMillis()
        RedemarragePc.attend(Long.MAX_VALUE - 1)
        assertTrue(System.currentTimeMillis() - t < 2000)
    }
}
