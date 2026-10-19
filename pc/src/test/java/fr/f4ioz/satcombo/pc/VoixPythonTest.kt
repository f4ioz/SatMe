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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * satme_voix.py's own tests (Python's unittest), when the speech engine's
 * environment is on this PC (SATME_VOIX_PYTHON, else ~/.satme/stt): skipped
 * otherwise. Nothing is installed, nothing downloaded.
 */
class VoixPythonTest {

    @Test fun `tests du programme satme_voix py`() {
        val py = System.getenv("SATME_VOIX_PYTHON")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".satme/stt/" + if (OutilsPc.windows) "Scripts/python.exe" else "bin/python")
        assumeTrue("pas d'environnement Python : ${py.path}", py.isFile)
        val d = File(System.getProperty("java.io.tmpdir"), "satme-voix-py-" + System.nanoTime()).apply { mkdirs() }
        try {
            for (n in listOf("/phonie/satme_voix.py", "/phonie/test_satme_voix.py"))
                File(d, n.substringAfterLast('/')).writeBytes(javaClass.getResourceAsStream(n)!!.readBytes())
            val numpy = ProcessBuilder(py.path, "-c", "import numpy").start().waitFor(60, TimeUnit.SECONDS)
            assumeTrue(numpy)
            val p = ProcessBuilder(py.path, "-m", "unittest", "-v", "test_satme_voix").directory(d).redirectErrorStream(true)
                .apply { environment()["SATME_VOIX_DIR"] = d.path }.start()
            val sortie = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            println(sortie)
            assertEquals(sortie, 0, p.exitValue())
        } finally { d.deleteRecursively() }
    }
}
