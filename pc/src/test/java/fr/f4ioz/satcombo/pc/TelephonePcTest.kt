/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import com.sun.net.httpserver.HttpServer
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.JournalPaquet
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import fr.f4ioz.satcombo.sstv.SstvMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress

/** A pass from the phone, its pictures, recording and contacts, into the PC — as a file or straight from it. */
class TelephonePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-telephone-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    private val t0 = 1_791_800_000_000L

    private fun pc(): Triple<TelephonePc, CarnetPc, JournalPc> {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val son = SonStation(SonPc(), st, File(dossier, "enregistrements"), File(dossier, "sstv"))
        val j = JournalPc(st, carnet, son, File(dossier, "journal"))
        var t = t0 + 3_600_000L
        return Triple(TelephonePc(carnet, j, File(dossier, "enregistrements"), File(dossier, "sstv"), File(dossier, "tmp")) { t++ }, carnet, j)
    }

    /** The phone's pack: the pass, a picture, two contacts, and a recording when ffmpeg can make one. */
    private fun paquet(): Pair<ByteArray, Boolean> {
        val tel = File(dossier, "telephone").apply { mkdirs() }
        val image = SstvMeta.fileName("ISS", t0 + 240_000L, "PD120", true)
        File(tel, image).writeBytes(ByteArray(100) { it.toByte() })
        File(tel, SstvMeta.sidecarName(image)).writeText(SstvMeta.encode(SstvMeta.SstvShot(image, "ISS", t0 + 240_000L, mode = "PD120", source = "live")))
        val fichiers = arrayListOf("sstv" to File(tel, image), "sstv" to File(tel, SstvMeta.sidecarName(image)))
        val ff = RenduPc.ffmpeg()
        var mp3 = false
        if (ff != null) {
            val m = File(tel, "SatMe_ISS_20261012_100000Z.mp3")
            val p = ProcessBuilder(ff, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=800:duration=4",
                "-c:a", "libmp3lame", m.path).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() == 0 && m.isFile) {
                File(tel, "SatMe_ISS_20261012_100000Z.info").writeText("annonce=1000\n")
                fichiers += "recordings" to m; fichiers += "recordings" to File(tel, "SatMe_ISS_20261012_100000Z.info")
                mp3 = true
            }
        }
        val e = JournalPassage.Entree(25544, "ISS", t0, t0 + 600_000L, "JN18FS",
            enregistrements = if (mp3) listOf("SatMe_ISS_20261012_100000Z.mp3") else emptyList(),
            points = (0..10).map { JournalPassage.Point(t0 + it * 60_000L, 100.0 + it * 10, 5.0 + it) })
        val contacts = listOf(
            LogEntry(t0 + 120_000L, "ISS", 25544, 120.0, 7.0, callsign = "F5XYZ", theirLocator = "JN03", mode = "FM"),
            LogEntry(t0 + 300_000L, "ISS", 25544, 160.0, 12.0, callsign = "EA4ABC", theirLocator = "IN80", mode = "FM"))
        val o = ByteArrayOutputStream()
        JournalPaquet.emballe(o, e, fichiers, contacts = contacts)
        return o.toByteArray() to mp3
    }

    @Test
    fun un_fichier_de_passage_du_telephone_arrive_dans_le_journal_le_carnet_et_les_images() {
        val (tel, carnet, j) = pc()
        // Already in the PC's log: F5XYZ within a minute, on the same satellite.
        carnet.store.add(LogEntry(t0 + 150_000L, "ISS", 25544, 120.0, 7.0, callsign = "F5XYZ/P", mode = "FM"))
        val (zip, mp3) = paquet()

        val b = tel.importeZip(ByteArrayInputStream(zip), dejaEnLigne = true)
        assertEquals("", b.erreur)
        assertEquals("ISS", b.satellite)
        assertEquals(1, b.images)
        assertEquals(1, b.contacts); assertEquals(1, b.contactsDejaLa)
        val log = carnet.store.load()
        assertEquals(2, log.size)
        val ea = log.single { it.callsign == "EA4ABC" }
        assertTrue("sent by the phone already", ea.envoyeMs > 0)
        assertTrue(File(dossier, "sstv").list()!!.any { it.endsWith(".png") || it.contains("PD120") })
        val passages = j.rangement.passages()
        assertEquals(1, passages.size)
        if (mp3) {
            assertEquals(1, b.sons)
            val wav = File(dossier, "enregistrements/SatMe_ISS_20261012_100000Z.wav")
            assertTrue(wav.isFile && wav.length() > 44)
            assertEquals(listOf(wav.name), passages[0].enregistrements)
        }
        assertTrue("nothing left behind", File(dossier, "tmp").list().orEmpty().isEmpty())

        // The same file again: nothing doubled.
        val b2 = tel.importeZip(ByteArrayInputStream(zip))
        assertEquals(0, b2.contacts); assertEquals(2, b2.contactsDejaLa)
        assertEquals(2, carnet.store.load().size)
        assertEquals(1, j.rangement.passages().size)
    }

    @Test
    fun ce_qui_n_est_pas_un_passage_est_refuse() {
        val (tel, carnet, _) = pc()
        val o = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(o).use { it.putNextEntry(java.util.zip.ZipEntry("autre.txt")); it.write(1); it.closeEntry() }
        assertTrue(tel.importeZip(ByteArrayInputStream(o.toByteArray())).erreur.isNotEmpty())
        assertTrue(carnet.store.load().isEmpty())
    }

    @Test
    fun le_telephone_sur_le_reseau_donne_ses_passages() {
        val (tel, carnet, j) = pc()
        val (zip, _) = paquet()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var demandes = ""
        fun rep(x: com.sun.net.httpserver.HttpExchange, corps: ByteArray, code: Int = 200) {
            x.sendResponseHeaders(code, corps.size.toLong()); x.responseBody.use { it.write(corps) }
        }
        s.createContext("/") { x ->
            val q = x.requestURI.rawQuery.orEmpty()
            val bon = "cle=K1" in q
            // The desk answers under its own token only.
            if (!x.requestURI.path.startsWith("/c/jeton23/")) { rep(x, "non".toByteArray(), 404); return@createContext }
            when (x.requestURI.path.removePrefix("/c/jeton23")) {
                "/api" -> rep(x, """{"satme":"20.83"}""".toByteArray())
                "/entrer" -> rep(x, (if ("code=123456" in q) """{"ok":true,"cle":"K1"}""" else """{"ok":false}""").toByteArray())
                "/journal/liste" -> if (bon) rep(x, """{"passages":[{"id":"p1","sat":"ISS"}]}""".toByteArray()) else rep(x, ByteArray(0), 403)
                "/journal/fabrique" -> { demandes = q; rep(x, """{"travail":"7"}""".toByteArray()) }
                "/journal/travail" -> rep(x, """{"fini":true,"ok":true}""".toByteArray())
                "/journal/fichier" -> if (bon && "n=7" in q) rep(x, zip) else rep(x, ByteArray(0), 404)
                else -> rep(x, ByteArray(0), 404)
            }
        }
        s.start()
        try {
            val adr = "http://127.0.0.1:" + s.address.port + "/c/jeton23/"
            assertNotNull(tel.connecte("127.0.0.1:" + s.address.port, "123456"))
            assertNotNull(tel.connecte(adr, "000000"))
            assertTrue(!tel.connecte)
            assertNull(tel.connecte(adr, " 123456 "))
            assertTrue(tel.connecte); assertEquals("20.83", tel.version)
            assertEquals("p1", tel.liste().getJSONArray("passages").getJSONObject(0).getString("id"))
            val bilans = tel.rapatrie(listOf("p1"), dejaEnLigne = false) { _, _ -> }
            assertEquals("", bilans.single().erreur)
            assertTrue("packed with its contacts", "contacts=1" in demandes && "quoi=paquet" in demandes)
            assertEquals(2, carnet.store.load().size)
            assertTrue("not marked as sent", carnet.store.load().all { it.envoyeMs == 0L })
            assertEquals(1, j.rangement.passages().size)
        } finally { s.stop(0) }
        assertNotNull(TelephonePc(pc().second, pc().third, dossier, dossier, dossier).connecte("127.0.0.1:1", "1"))
    }
}
