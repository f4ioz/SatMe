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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** The remote page's HTTPS: the self-signed certificate made and kept, the page served over TLS, http as before. */
class CertificatPcTest {

    private val dossier = File(System.getProperty("java.io.tmpdir"), "satme-https-" + System.nanoTime()).apply { mkdirs() }
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun fin() { portee.cancel(); dossier.deleteRecursively() }

    @Test fun der_oid_et_longueurs() {
        assertEquals("06 03 55 1d 11", Der.oid("2.5.29.17").joinToString(" ") { "%02x".format(it) })
        assertEquals("06 08 2a 86 48 ce 3d 04 03 02", Der.oid("1.2.840.10045.4.3.2").joinToString(" ") { "%02x".format(it) })
        assertEquals(2 + 200 + 1, Der.octets(ByteArray(200)).size)
        assertEquals(0x81, Der.octets(ByteArray(200))[1].toInt() and 0xFF)
        assertEquals(0x17, Der.temps(1_800_000_000_000L)[0].toInt())
        assertEquals(0x18, Der.temps(2_600_000_000_000L)[0].toInt())
    }

    @Test fun certificat_genere_lu_par_java() {
        val t = System.currentTimeMillis()
        val (_, c) = CertificatPc.genere(listOf("localhost", "station"), listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("192.168.1.20")), t)
        assertEquals("CN=SatMe-PC,O=SatMe", c.subjectX500Principal.name)
        assertEquals(c.subjectX500Principal, c.issuerX500Principal)
        assertEquals("SHA256withECDSA", c.sigAlgName)
        val noms = c.subjectAlternativeNames.map { it[1].toString() }
        assertTrue(noms.containsAll(listOf("localhost", "station", "127.0.0.1", "192.168.1.20")))
        assertTrue(c.notAfter.time - t > 390L * 86_400_000L && c.notAfter.time - t < 400L * 86_400_000L)
        assertEquals(-1, c.basicConstraints)
        assertTrue(c.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        assertEquals(95, CertificatPc.empreinte(c).length)
    }

    @Test fun garde_puis_regenere() {
        val a = CertificatPc(dossier)
        val e1 = a.empreinte()
        assertTrue(a.fichier.exists())
        // Read again from disk: the same one.
        assertEquals(e1, CertificatPc(dossier).empreinte())
        a.regenere()
        assertNotEquals(e1, a.empreinte())
        // Nearly expired (a clock a year ahead): made again by itself.
        val tard = CertificatPc(dossier, horloge = { System.currentTimeMillis() + 380L * 86_400_000L })
        assertNotEquals(a.empreinte(), tard.empreinte())
    }

    private fun libre(): Int = ServerSocket(0).use { it.localPort }

    @Test fun la_page_en_https_et_toujours_en_http() {
        val st = StationPc(StockageMemoire(), Catalogue(dossier, enLigne = false), PortsSeriePc(), portee)
        val s = ServeurPc(st, 0, diffusion = DiffusionSon())
        val cert = CertificatPc(dossier)
        s.certificat = cert
        s.httpsPort = libre()
        s.demarre()
        try {
            assertEquals(0, s.portHttpsReel)
            val base = "http://127.0.0.1:${s.portReel}"
            val avant = JSONObject(URL("$base/api/https").readText())
            assertFalse(avant.getBoolean("on"))
            // Switched on from this PC.
            val c = URL("$base/api/https/reglage?on=1").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            val r = JSONObject(c.inputStream.bufferedReader().readText())
            assertTrue(r.toString(), r.getBoolean("on"))
            assertTrue(s.portHttpsReel > 0)
            assertEquals(cert.empreinte(), r.getString("empreinte"))
            // A browser that accepted the certificate: the page and the API over TLS.
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("satme", cert.certificat()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }.trustManagers, null) }
            val h = URL("https://localhost:${s.portHttpsReel}/api/etat").openConnection() as HttpsURLConnection
            h.sslSocketFactory = ctx.socketFactory
            assertEquals(200, h.responseCode)
            assertTrue((h.serverCertificates[0] as X509Certificate).encoded.contentEquals(cert.certificat().encoded))
            assertEquals(200, (URL("$base/api/etat").openConnection() as java.net.HttpURLConnection).responseCode)
            // Another machine may not change it.
            s.estLocal = { false }
            assertTrue(st.stockage.getBoolean("pc_https", false))
        } finally { s.arrete() }
    }
}
