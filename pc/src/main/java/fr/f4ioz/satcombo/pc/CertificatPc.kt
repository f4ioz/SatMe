/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** The little of DER (X.690) a certificate needs. */
object Der {
    fun tlv(tag: Int, v: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(v.size + 6)
        o.write(tag)
        val n = v.size
        when {
            n < 0x80 -> o.write(n)
            n <= 0xFF -> { o.write(0x81); o.write(n) }
            n <= 0xFFFF -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(v)
        return o.toByteArray()
    }

    private fun suite(e: Array<out ByteArray>) = ByteArrayOutputStream().also { o -> e.forEach { o.write(it) } }.toByteArray()
    fun sequence(vararg e: ByteArray) = tlv(0x30, suite(e))
    fun ensemble(vararg e: ByteArray) = tlv(0x31, suite(e))
    fun entier(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun entier(v: Int) = entier(BigInteger.valueOf(v.toLong()))
    fun booleen(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun octets(v: ByteArray) = tlv(0x04, v)
    fun bits(v: ByteArray, inutilises: Int = 0) = tlv(0x03, byteArrayOf(inutilises.toByte()) + v)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun explicite(n: Int, v: ByteArray) = tlv(0xA0 + n, v)

    fun oid(s: String): ByteArray {
        val p = s.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((p[0] * 40 + p[1]).toInt())
        for (x in p.drop(2)) {
            val g = ArrayList<Int>()
            var v = x
            do { g.add((v and 0x7F).toInt()); v = v shr 7 } while (v > 0)
            for (i in g.indices.reversed()) o.write(g[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, o.toByteArray())
    }

    /** UTCTime before 2050, GeneralizedTime after (RFC 5280, 4.1.2.5). */
    fun temps(ms: Long): ByteArray {
        val annee = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
        val (tag, motif) = if (annee < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val f = SimpleDateFormat(motif, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(tag, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }
}

/**
 * HTTPS for the remote page: a self-signed certificate made on first use
 * (EC P-256, about a year, the names and addresses of this PC), kept with
 * its key in [dossier]/https; the browser warns once, the owner compares
 * the fingerprint shown in Réglages › Accès à distance. A page in HTTPS is
 * a « secure context »: the browser then lets it use the microphone.
 */
class CertificatPc(dossier: File, private val horloge: () -> Long = System::currentTimeMillis) {
    private val rep = File(dossier, "https")
    val fichier = File(rep, "satme-pc.p12")
    private val fichierMdp = File(rep, "satme-pc.mdp")

    @Volatile private var charge: Pair<KeyStore, CharArray>? = null

    /** The certificate in use (made when missing or about to expire). */
    @Synchronized fun certificat(): X509Certificate = (ks().first.getCertificate(ALIAS) as X509Certificate)

    /** Its SHA-256 fingerprint, « AB:CD:… ». */
    fun empreinte(): String = empreinte(certificat())

    /** A new one now (the old one forgotten). */
    @Synchronized fun regenere() { charge = null; fichier.delete(); fichierMdp.delete(); ks() }

    /** The server's TLS context. */
    fun contexte(): SSLContext {
        val (k, m) = ks()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(k, m) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    @Synchronized private fun ks(): Pair<KeyStore, CharArray> {
        charge?.let { (k, m) -> if (valide(k)) return k to m }
        val lu = runCatching {
            val m = fichierMdp.readText().trim().toCharArray()
            KeyStore.getInstance("PKCS12").apply { fichier.inputStream().use { load(it, m) } } to m
        }.getOrNull()
        if (lu != null && valide(lu.first)) { charge = lu; return lu }
        val m = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.toCharArray()
        val (cle, cert) = genere(nomsDns(), adresses(), horloge())
        val k = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry(ALIAS, cle, m, arrayOf(cert)) }
        rep.mkdirs()
        prive(rep, true)
        fichierMdp.writeText(String(m)); prive(fichierMdp, false)
        fichier.outputStream().use { k.store(it, m) }; prive(fichier, false)
        charge = k to m
        return k to m
    }

    /** Still good for 30 days at least. */
    private fun valide(k: KeyStore): Boolean = runCatching {
        (k.getCertificate(ALIAS) as X509Certificate).notAfter.time - horloge() > 30L * 86_400_000L
    }.getOrDefault(false)

    private fun prive(f: File, dossier: Boolean) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(f.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString(if (dossier) "rwx------" else "rw-------"))
        }
    }

    companion object {
        const val ALIAS = "satme-pc"
        const val JOURS = 397L

        fun empreinte(c: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

        /** The names this PC answers to: localhost, its host name. */
        fun nomsDns(): List<String> = (listOf("localhost") + listOfNotNull(runCatching { InetAddress.getLocalHost().hostName }.getOrNull())
            .filter { it.isNotBlank() && it.matches(Regex("^[A-Za-z0-9.-]{1,253}$")) }).distinct()

        /** Its addresses on the network (IPv4, not the containers' bridges), and the loopback. */
        fun adresses(): List<InetAddress> = (listOf(InetAddress.getLoopbackAddress()) + runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("docker") && !it.name.startsWith("br-") && !it.name.startsWith("veth") }
                .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address }
        }.getOrDefault(emptyList())).distinctBy { it.hostAddress }

        /** A self-signed server certificate (EC P-256, SHA-256) for [dns] and [ips], from [debut] for [JOURS] days. */
        fun genere(dns: List<String>, ips: List<InetAddress>, debut: Long, jours: Long = JOURS): Pair<PrivateKey, X509Certificate> {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val algo = Der.sequence(Der.oid("1.2.840.10045.4.3.2"))
            val nom = Der.sequence(Der.ensemble(Der.sequence(Der.oid("2.5.4.10"), Der.utf8("SatMe"))),
                Der.ensemble(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("SatMe-PC"))))
            val serie = BigInteger(1, ByteArray(16).also { SecureRandom().nextBytes(it) }).setBit(126).clearBit(127)
            val noms = dns.map { Der.tlv(0x82, it.toByteArray(Charsets.US_ASCII)) } + ips.map { Der.tlv(0x87, it.address) }
            val extensions = Der.explicite(3, Der.sequence(
                Der.sequence(Der.oid("2.5.29.17"), Der.octets(Der.sequence(*noms.toTypedArray()))),
                Der.sequence(Der.oid("2.5.29.19"), Der.booleen(true), Der.octets(Der.sequence())),
                Der.sequence(Der.oid("2.5.29.15"), Der.booleen(true), Der.octets(Der.bits(byteArrayOf(0x80.toByte()), 7))),
                Der.sequence(Der.oid("2.5.29.37"), Der.octets(Der.sequence(Der.oid("1.3.6.1.5.5.7.3.1"))))))
            val tbs = Der.sequence(Der.explicite(0, Der.entier(2)), Der.entier(serie), algo, nom,
                Der.sequence(Der.temps(debut - 3_600_000L), Der.temps(debut + jours * 86_400_000L)), nom,
                kp.public.encoded, extensions)
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(tbs) }.sign()
            val der = Der.sequence(tbs, algo, Der.bits(sig))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            cert.verify(kp.public)
            return kp.private to cert
        }
    }
}
