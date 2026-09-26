/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Discovery of stations on the local network.
 *
 * **Why not a QR code.** Scanning would have required the camera permission
 * and three libraries, whereas the project has managed without so far: QRV
 * pictures go through the system camera app, and the manifest says so
 * explicitly. A broadcast announcement requires nothing at all — the network
 * permission is already there — and removes the gesture instead of shortening
 * it.
 *
 * **How.** The broadcasting station announces itself every two seconds on port
 * 8081, in the clear. Listening phones pick those announcements up and build a
 * list. Nothing to type, nothing to aim at: you tap a name.
 *
 * **Its limit, and it is a real one.** Some access points filter broadcast
 * traffic. Entering the address by hand therefore stays — not as a shameful
 * fallback, but because there will be rooms where it is the only thing that
 * works.
 */
object AnnonceReseau {

    const val PORT = 8081

    /** The prefix identifying our announcements. Without it we would read anything. */
    private const val MARQUE = "SATME1"

    data class Station(
        val nom: String,
        val url: String,
        val vueMs: Long
    )

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations = _stations.asStateFlow()

    // ----------------------------------------------------- the announcement

    @Volatile private var annonceEnMarche = false

    /**
     * Announces the station for as long as the broadcast lasts.
     *
     * Two seconds: frequent enough that a viewer who arrives sees the station
     * at once, rare enough to be invisible on the network.
     */
    fun annonce(nom: () -> String, url: () -> String) {
        if (annonceEnMarche) return
        annonceEnMarche = true
        thread(name = "AnnonceSatMe", isDaemon = true) {
            var prise: DatagramSocket? = null
            try {
                prise = DatagramSocket().apply { broadcast = true }
                val partout = InetAddress.getByName("255.255.255.255")
                while (annonceEnMarche) {
                    val adresse = url()
                    if (adresse.isNotBlank()) {
                        val texte = "$MARQUE|${propre(nom())}|$adresse"
                        val o = texte.toByteArray(Charsets.UTF_8)
                        runCatching {
                            prise.send(DatagramPacket(o, o.size, partout, PORT))
                        }
                    }
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {
                // A refused broadcast is not a failure: entering the address
                // by hand is still possible, and that is precisely why it was
                // never removed.
            } finally {
                runCatching { prise?.close() }
                annonceEnMarche = false
            }
        }
    }

    fun tais() { annonceEnMarche = false }

    // -------------------------------------------------------- the discovery

    @Volatile private var ecouteEnMarche = false

    /** Listens for announcements and keeps the list up to date. */
    fun ecoute() {
        if (ecouteEnMarche) return
        ecouteEnMarche = true
        thread(name = "DecouverteSatMe", isDaemon = true) {
            var prise: DatagramSocket? = null
            try {
                prise = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    // One second: time enough to come back through the loop,
                    // check whether we should stop, and forget silent
                    // stations.
                    soTimeout = 1000
                    bind(java.net.InetSocketAddress(PORT))
                }
                val tampon = ByteArray(512)
                while (ecouteEnMarche) {
                    runCatching {
                        val p = DatagramPacket(tampon, tampon.size)
                        prise.receive(p)
                        lit(String(p.data, 0, p.length, Charsets.UTF_8))
                    }
                    oublieLesMuettes()
                }
            } catch (e: Exception) {
                // Another application may hold the port: we give up silently,
                // manual entry remains.
            } finally {
                runCatching { prise?.close() }
                ecouteEnMarche = false
                _stations.value = emptyList()
            }
        }
    }

    fun cesse() { ecouteEnMarche = false }

    private fun lit(texte: String) {
        val p = texte.split('|')
        if (p.size != 3 || p[0] != MARQUE) return
        val url = p[2].trim()
        if (!url.startsWith("http://")) return
        val maintenant = System.currentTimeMillis()
        val liste = _stations.value.filterNot { it.url == url } +
            Station(p[1].ifBlank { "SatMe" }, url, maintenant)
        _stations.value = liste.sortedBy { it.nom }
    }

    /**
     * Drops stations that have fallen silent.
     *
     * Six seconds, that is three missed announcements. Keeping a dead station
     * in the list would have someone tap a name that no longer answers, and
     * look for the fault on the wrong side.
     */
    private fun oublieLesMuettes() {
        val limite = System.currentTimeMillis() - 6000
        val vivantes = _stations.value.filter { it.vueMs >= limite }
        if (vivantes.size != _stations.value.size) _stations.value = vivantes
    }

    /** The separator must never end up inside a field. */
    private fun propre(v: String) = v.replace('|', ' ').trim().ifBlank { "SatMe" }
}
