/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.polarisation

import fr.f4ioz.satcombo.cat.SerialLink
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The polarisation box's firmware (`materiel/esp32-polarisation`) without
 * the box: the same rules, as a serial line SatMe opens like the real one.
 * For the tests, a demonstration, and the owner until the box is built:
 * the selector and the rig's SEND line are moved from the page.
 *
 *  - at start, every relay at rest (vertical);
 *  - selector off AUTO: every relay released, commands refused (`ERR MANUEL`);
 *  - SEND active: no switching at all (`ERR TX`);
 *  - a change goes through « no voltage » ([ArbreRelais.sequence]).
 *
 * [historique] keeps every relay state each tree went through, so the
 * tests can check that no two pins were ever fed.
 */
class Esp32Virtuel(val fw: String = "1.0-virtuel") : SerialLink {

    @Volatile var selecteur: Selecteur = Selecteur.AUTO; private set
    @Volatile var send: Boolean = false; private set
    private val arbres = mutableMapOf(Bande.U to listOf(false, false, false), Bande.V to listOf(false, false, false))
    /** Every state each tree went through, in order (the first: at rest). */
    val historique: Map<Bande, MutableList<List<Boolean>>> = mapOf(Bande.U to mutableListOf(listOf(false, false, false)),
        Bande.V to mutableListOf(listOf(false, false, false)))
    @Volatile var ferme = false; private set

    private val sortie = LinkedBlockingQueue<Byte>()
    private val entree = StringBuilder()

    init { emet("ID $ID FW=$fw"); emet(etatLigne()) }

    fun relais(b: Bande): List<Boolean> = synchronized(this) { arbres.getValue(b) }

    /** The real polarisation: the selector's own off AUTO (UHF), else what the relays give. */
    fun etat(): EtatBoitier = synchronized(this) {
        EtatBoitier(selecteur, selecteur.pol ?: ArbreRelais.pol(arbres.getValue(Bande.U)), ArbreRelais.pol(arbres.getValue(Bande.V)), send, fw)
    }

    private fun etatLigne() = ProtocolePolarisation.etat(etat())

    /** The front panel's selector turned. Off AUTO, every relay released at once. */
    fun tourne(s: Selecteur) {
        synchronized(this) {
            if (s == selecteur) return
            selecteur = s
            if (s.manuel) Bande.entries.forEach { pose(it, Pol.V) }
        }
        emet(etatLigne())
    }

    /** The rig's SEND line (true: grounded, transmitting). */
    fun emission(on: Boolean) {
        synchronized(this) { if (on == send) return; send = on }
        emet(etatLigne())
    }

    private fun pose(b: Bande, p: Pol) {
        ArbreRelais.sequence(arbres.getValue(b), p).forEach { k -> arbres[b] = k; historique.getValue(b) += k }
    }

    /** One command line; the lines answered. */
    fun traite(ligne: String): List<String> {
        val l = ligne.trim().uppercase()
        if (l.isEmpty()) return emptyList()
        return when {
            l == ProtocolePolarisation.ETAT -> listOf(etatLigne())
            l == ProtocolePolarisation.ID -> listOf("ID $ID FW=$fw")
            l.startsWith("POL") -> {
                val c = ProtocolePolarisation.litCommande(l) ?: return listOf("ERR SYNTAXE $l")
                synchronized(this) {
                    when {
                        selecteur.manuel -> listOf("ERR MANUEL sélecteur sur ${selecteur.code}")
                        send -> listOf("ERR TX émission en cours")
                        else -> {
                            val avant = ArbreRelais.pol(arbres.getValue(c.first))
                            pose(c.first, c.second)
                            listOfNotNull("OK POL ${c.first.name} ${c.second.name}", if (avant != c.second) etatLigne() else null)
                        }
                    }
                }
            }
            else -> listOf("ERR SYNTAXE $l")
        }
    }

    private fun emet(ligne: String) { (ligne + "\n").toByteArray().forEach { sortie.put(it) } }

    // ------------------------------------------------------------ as a serial line

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (ferme) return false
        val lignes = synchronized(entree) {
            entree.append(String(bytes, Charsets.UTF_8))
            val l = mutableListOf<String>()
            while (true) {
                val i = entree.indexOfFirst { it == '\n' || it == '\r' }
                if (i < 0) break
                l += entree.substring(0, i); entree.delete(0, i + 1)
            }
            l
        }
        lignes.forEach { c -> traite(c).forEach { emet(it) } }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (ferme) return 0
        val premier = sortie.poll(timeoutMs.toLong().coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: return 0
        buf[0] = premier
        var n = 1
        while (n < buf.size) { val b = sortie.poll() ?: break; buf[n++] = b }
        return n
    }

    override fun close() { ferme = true }

    companion object {
        const val ID = ProtocolePolarisation.SIGNATURE + " ESP32"
    }
}
