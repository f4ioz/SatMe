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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A broker in the test's own process: enough MQTT 3.1.1 for one client (CONNECT with a user and a will,
 * SUBSCRIBE, PUBLISH at QoS 0, retained messages, PINGREQ), written apart from [MqttPc] so that one does not
 * hide the other's mistakes.
 */
internal class BrokerFictif(private val motDePasse: String? = null) {
    val serveur = ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = serveur.localPort
    val retenus = ConcurrentHashMap<String, String>()
    val recus = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var utilisateur: String? = null
    @Volatile var motDePasseVu: String? = null
    @Volatile var testament: Pair<String, String>? = null
    private val clients = CopyOnWriteArrayList<Pair<OutputStream, MutableList<String>>>()

    init { thread(isDaemon = true) { while (!serveur.isClosed) { val s = runCatching { serveur.accept() }.getOrNull() ?: break; thread(isDaemon = true) { client(s) } } } }

    private fun chaine(b: ByteArray, i: Int): Pair<String, Int> {
        val n = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        return String(b, i + 2, n, Charsets.UTF_8) to i + 2 + n
    }

    private fun longueur(n: Int): ByteArray { val o = ByteArrayOutputStream(); var x = n; do { var c = x % 128; x /= 128; if (x > 0) c = c or 128; o.write(c) } while (x > 0); return o.toByteArray() }

    private fun publish(topic: String, p: String): ByteArray {
        val t = topic.toByteArray(); val c = p.toByteArray()
        val corps = byteArrayOf((t.size shr 8).toByte(), (t.size and 0xFF).toByte()) + t + c
        return byteArrayOf(0x30) + longueur(corps.size) + corps
    }

    private fun correspond(filtre: String, topic: String): Boolean {
        if (filtre.endsWith("/#")) return topic.startsWith(filtre.removeSuffix("#"))
        return filtre == topic
    }

    private fun client(s: Socket) {
        val e = DataInputStream(s.getInputStream())
        val o = s.getOutputStream()
        val abonnements = CopyOnWriteArrayList<String>()
        val moi = o to abonnements
        var propre = false
        try {
            while (true) {
                val b = e.read(); if (b < 0) break
                var n = 0; var m = 1
                while (true) { val c = e.read(); n += (c and 127) * m; m *= 128; if (c and 128 == 0) break }
                val corps = ByteArray(n); e.readFully(corps)
                when (b shr 4) {
                    1 -> {
                        val (proto, i0) = chaine(corps, 0)
                        check(proto == "MQTT" && corps[i0].toInt() == 4)
                        val drapeaux = corps[i0 + 1].toInt() and 0xFF
                        var i = i0 + 4
                        val (_, i1) = chaine(corps, i); i = i1
                        if (drapeaux and 0x04 != 0) { val (t, i2) = chaine(corps, i); val (p, i3) = chaine(corps, i2); testament = t to p; i = i3 }
                        if (drapeaux and 0x80 != 0) { val (u, i2) = chaine(corps, i); utilisateur = u; i = i2 }
                        if (drapeaux and 0x40 != 0) { val (p, i2) = chaine(corps, i); motDePasseVu = p; i = i2 }
                        val refuse = motDePasse != null && motDePasseVu != motDePasse
                        synchronized(o) { o.write(byteArrayOf(0x20, 2, 0, if (refuse) 4 else 0)); o.flush() }
                        if (refuse) { s.close(); return }
                        clients += moi
                    }
                    3 -> {
                        val (t, i) = chaine(corps, 0)
                        val p = String(corps, i, corps.size - i, Charsets.UTF_8)
                        recus += t to p
                        if (b and 1 != 0) { if (p.isEmpty()) retenus.remove(t) else retenus[t] = p }
                        diffuse(t, p)
                    }
                    8 -> {
                        var i = 2
                        while (i < corps.size) { val (f, j) = chaine(corps, i); abonnements += f; i = j + 1 }
                        synchronized(o) { o.write(byteArrayOf(0x90.toByte(), 3, corps[0], corps[1], 0)); o.flush() }
                    }
                    12 -> synchronized(o) { o.write(byteArrayOf(0xD0.toByte(), 0)); o.flush() }
                    14 -> { propre = true; break }
                }
            }
        } catch (_: Exception) {}
        clients.remove(moi)
        if (!propre) testament?.let { (t, p) -> retenus[t] = p }
        runCatching { s.close() }
    }

    private fun diffuse(t: String, p: String) {
        clients.forEach { (o, ab) -> if (ab.any { correspond(it, t) }) runCatching { synchronized(o) { o.write(publish(t, p)); o.flush() } } }
    }

    /** Home Assistant publishing (a switch flipped, a command typed). */
    fun publie(t: String, p: String) { recus += t to p; diffuse(t, p) }

    fun ferme() { runCatching { serveur.close() } }
}

class DomotiquePcTest {

    private val brokers = ArrayList<BrokerFictif>()
    @After fun fin() { brokers.forEach { it.ferme() } }

    private fun attend(f: () -> Boolean): Boolean { val fin = System.currentTimeMillis() + 10_000; while (!f() && System.currentTimeMillis() < fin) Thread.sleep(20); return f() }

    private fun banc(mdpBroker: String? = "mot-de-passe-fictif", mdp: String = "mot-de-passe-fictif"): Triple<DomotiquePc, BrokerFictif, MondeFictif> {
        val b = BrokerFictif(mdpBroker).also { brokers += it }
        val monde = MondeFictif()
        val d = DomotiquePc(StockageMemoire(), CommandesPc(monde) { ZoneId.of("Europe/Paris") }, horloge = { monde.t }, module = { true }, version = "essai")
        d.regle { k -> mapOf("hote" to "127.0.0.1", "port" to b.port.toString(), "utilisateurDonne" to "1", "utilisateur" to "ha-fictif", "mdp" to mdp)[k].orEmpty() }
        return Triple(d, b, monde)
    }

    @Test fun decouverte_etat_et_disponibilite() {
        val (d, b, _) = banc()
        d.on = true
        d.demarre()
        assertTrue(attend { b.retenus["satme/disponible"] == "online" && b.retenus.containsKey("satme/etat") })
        assertEquals("ha-fictif", b.utilisateur)
        assertEquals("mot-de-passe-fictif", b.motDePasseVu)
        assertEquals("satme/disponible" to "offline", b.testament)
        // The sensors announced to Home Assistant, with their device.
        val c = JSONObject(b.retenus.getValue("homeassistant/sensor/satme/prochain_passage/config"))
        assertEquals("satme_prochain_passage", c.getString("unique_id"))
        assertEquals("satme/etat", c.getString("state_topic"))
        assertEquals("satme/disponible", c.getString("availability_topic"))
        assertEquals("SatMe-PC", c.getJSONObject("device").getString("model"))
        assertEquals("timestamp", JSONObject(b.retenus.getValue("homeassistant/sensor/satme/prochain_aos/config")).getString("device_class"))
        assertTrue(b.retenus.containsKey("homeassistant/binary_sensor/satme/datv_verrou/config"))
        // Actions not allowed: no switch, no command field.
        assertFalse(b.retenus.containsKey("homeassistant/switch/satme/sstv/config"))
        assertFalse(b.retenus.containsKey("homeassistant/text/satme/commande/config"))
        val e = JSONObject(b.retenus.getValue("satme/etat"))
        assertTrue(e.getString("prochain_passage").contains("SO-50"))
        assertEquals(java.time.Instant.ofEpochMilli(MondeFictif.T0 + 25 * 60_000L).toString(), e.getString("prochain_aos"))
        assertEquals(1, e.getInt("visibles"))
        assertEquals(182, e.getInt("rotor_azimut"))
        // The password is never given back to the page.
        val j = d.json().toString()
        assertFalse(j.contains("mot-de-passe-fictif"))
        assertTrue(d.json().getBoolean("motDePasse"))
        d.arrete()
    }

    @Test fun les_interrupteurs_seulement_quand_permis_et_jamais_d_emission() {
        val (d, b, monde) = banc()
        d.on = true
        d.demarre()
        assertTrue(attend { b.retenus["satme/disponible"] == "online" })
        Thread.sleep(200)
        // Not allowed yet: a switch flipped does nothing.
        b.publie("satme/commande/sstv", "ON")
        Thread.sleep(300)
        assertTrue(monde.gestes.isEmpty())
        d.actions = true
        assertTrue(attend { b.retenus.containsKey("homeassistant/switch/satme/sstv/config") })
        val sw = JSONObject(b.retenus.getValue("homeassistant/switch/satme/sstv/config"))
        assertEquals("satme/commande/sstv", sw.getString("command_topic"))
        assertTrue(b.retenus.containsKey("homeassistant/text/satme/commande/config"))
        b.publie("satme/commande/sstv", "ON")
        assertTrue(attend { monde.gestes == listOf("sstv true") })
        assertTrue(attend { JSONObject(b.retenus.getValue("satme/etat")).getString("sstv") == "ON" })
        b.publie("satme/commande/son_muet", "ON")
        assertTrue(attend { monde.gestes.contains("son true") })
        b.publie("satme/commande/texte", "passe en émission sur QO-100")
        assertTrue(attend { JSONObject(b.retenus.getValue("satme/etat")).getString("reponse") == CommandesPc.EMISSION_FR })
        b.publie("satme/commande/intention", "ptt")
        b.publie("satme/commande/rotor", "ON")
        Thread.sleep(300)
        assertEquals(listOf("sstv true", "son true"), monde.gestes)
        // Back to state only: the switches removed from Home Assistant.
        d.actions = false
        assertTrue(attend { !b.retenus.containsKey("homeassistant/switch/satme/sstv/config") })
        d.arrete()
        assertTrue(attend { b.retenus["satme/disponible"] == "offline" })
    }

    @Test fun mot_de_passe_refuse() {
        val (d, b, _) = banc(mdp = "autre-mot-fictif")
        d.on = true
        d.demarre()
        assertTrue(attend { d.etat == "erreur" })
        assertEquals("utilisateur ou mot de passe refusé", d.erreur)
        assertFalse(b.retenus.containsKey("satme/etat"))
        d.arrete()
    }

    @Test fun rien_sans_le_module() {
        val monde = MondeFictif()
        val d = DomotiquePc(StockageMemoire(), CommandesPc(monde), module = { false }, fabrique = { throw AssertionError("connexion") })
        assertFalse(d.regle { "" }.getBoolean("ok"))
        d.on = true
        d.demarre()
        Thread.sleep(200)
        assertEquals("coupé", d.etat)
    }

    @Test fun le_codec() {
        assertTrue(MqttPc.Codec.longueur(0).contentEquals(byteArrayOf(0)))
        assertTrue(MqttPc.Codec.longueur(321).contentEquals(byteArrayOf(0xC1.toByte(), 0x02)))
        val p = MqttPc.Codec.publish("a/b", "é".toByteArray(), true)
        assertEquals(0x31, p[0].toInt())
        val (octet, corps) = MqttPc.Codec.lit(DataInputStream(p.inputStream()))!!
        assertEquals("a/b" to "é", MqttPc.Codec.publication(octet, corps))
    }
}
