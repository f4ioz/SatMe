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
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.plateforme.StockageMemoire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder

/** The phone joins the PC by its QR code: the token checked, then the PC joins the phone's desk. */
class AppairagePcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-appairage-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telephone = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var appairage: AppairagePc? = null
    @After fun fin() { appairage?.ferme(); telephone.stop(0); portee.cancel(); dossier.deleteRecursively() }

    private fun tel(): TelephonePc {
        val st = StationPc(StockageMemoire(), Catalogue(File(dossier, "cache"), enLigne = false), PortsSeriePc(), portee)
        val carnet = CarnetPc(st, LogStore(File(dossier, "qso_log.json")))
        val j = JournalPc(st, carnet, SonStation(SonPc(), st, File(dossier, "e"), File(dossier, "s")), File(dossier, "journal"))
        return TelephonePc(carnet, j, File(dossier, "e"), File(dossier, "s"), File(dossier, "tmp"))
    }

    /** The phone's desk: its token in the path, its code. */
    private fun bureau() {
        telephone.createContext("/") { x ->
            val chemin = x.requestURI.path; val q = x.requestURI.rawQuery.orEmpty()
            val rep = when (chemin) {
                "/c/jeton23/api" -> """{"satme":"20.83"}"""
                "/c/jeton23/entrer" -> if ("code=654321" in q) """{"ok":true,"cle":"K"}""" else """{"ok":false}"""
                else -> "non"
            }.toByteArray()
            x.sendResponseHeaders(200, rep.size.toLong()); x.responseBody.use { it.write(rep) }
        }
        telephone.start()
    }

    private fun annonce(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.outputStream.close()
        return JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun le_telephone_qui_scanne_est_rejoint() {
        bureau()
        val t = tel()
        val a = AppairagePc(t, 0).also { appairage = it }
        val e = a.ouvre()
        if (!e.ouvert) return   // no local network on this machine: nothing to show a QR code for
        assertTrue(e.url.matches(Regex("http://[0-9.]+:\\d+/appairage\\?j=[a-z0-9]{20}")))
        assertTrue(AppairagePc.svg(e.url).startsWith("<svg"))
        val port = e.url.substringAfterLast(':').substringBefore('/')
        val bureauTel = "http://127.0.0.1:${telephone.address.port}/c/jeton23/"
        fun url(j: String, code: String) = "http://127.0.0.1:$port/appairage/telephone?j=$j&adresse=" + URLEncoder.encode(bureauTel, "UTF-8") + "&code=$code&nom=F4IOZ"
        val jeton = e.url.substringAfter("j=")

        // A wrong token: refused, the PC joins nothing.
        assertFalse(annonce(url("mauvais0000000000000", "654321")).getBoolean("ok"))
        assertFalse(t.connecte)
        // The right one with the wrong code (the phone's own words then).
        assertFalse(annonce(url(jeton, "000000")).getBoolean("ok"))
        // The right one: joined, the door closed, its token spent.
        val r = annonce(url(jeton, "654321"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertTrue(t.connecte); assertEquals("20.83", t.version)
        val fin = a.verifie()
        assertTrue(fin.joint); assertFalse(fin.ouvert); assertEquals("F4IOZ", fin.telephone)
        // The door shuts asynchronously: a loaded machine may answer once more before it is gone.
        val limite = System.currentTimeMillis() + 3_000
        var ferme = false
        while (!ferme && System.currentTimeMillis() < limite) {
            ferme = runCatching { annonce(url(jeton, "654321")) }.map { !it.optBoolean("ok") }.getOrDefault(true)
            if (!ferme) Thread.sleep(50)
        }
        assertTrue("closed", ferme)
    }

    @Test
    fun le_qr_code_expire() {
        var maintenant = 1_000_000L
        val a = AppairagePc(tel(), 0) { maintenant }.also { appairage = it }
        if (!a.ouvre().ouvert) return
        maintenant += AppairagePc.DUREE_MS + 1
        val e = a.verifie()
        assertFalse(e.ouvert); assertTrue(e.erreur.contains("expiré"))
        assertFalse(a.annonce("x", "y", "z", "").getBoolean("ok"))
    }

    @Test
    fun les_adresses_sont_locales() {
        AppairagePc.adresses().forEach { assertTrue(it, it.startsWith("10.") || it.startsWith("192.168.") || it.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*"))) }
    }
}
