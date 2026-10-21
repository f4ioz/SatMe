/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONObject

/**
 * Who drives SatMe's transmissions (DATV, SSTV, through a SatMe-Node or a
 * board on the PC), whatever the client: the page on the PC, the same
 * page from another machine (remote mode, password), later the phone.
 *
 * - **One controller at a time.** A start is refused while another client
 *   holds a transmission; everyone sees who holds it, since when, what.
 * - **STOP from anyone** authenticated: never refused here.
 * - **Its heartbeat.** Only the controller's « still there » counts; a
 *   remote controller silent for [chienDistantMs] (its link lost, the phone
 *   gone) stops the transmission ([perdu]). The page on the PC itself
 *   keeps the transmitters' own, longer guard (no link to lose).
 * - **Remote: one more word.** A remote client confirms once per session
 *   ([confirme]) that it means to transmit from there, before its first start.
 *
 * The transmitters' rules stay theirs: this only says who may ask.
 */
class ControleEmission(
    private val horloge: () -> Long = System::currentTimeMillis,
    val chienDistantMs: Long = 2_000L,
) {
    /** A client: its id (the page's, drawn per tab), remote or this PC, its address for the others to see. */
    data class Client(val id: String, val distant: Boolean, val adresse: String = "")

    data class Tenue(val client: Client, val depuisMs: Long, val quoi: String, val vieMs: Long)

    @Volatile var tenue: Tenue? = null; private set
    private val confirmes = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Why [c] may not start a transmission now ([emet]: one is on), or null. */
    @Synchronized
    fun refus(c: Client, emet: Boolean): String? {
        val t = tenue
        if (emet && t != null && t.client.id != c.id)
            return "l'émission est tenue par " + nom(t.client) + " depuis " + ((horloge() - t.depuisMs) / 1000) + " s : seul ce client la pilote (STOP reste possible ici)"
        if (c.distant && c.id !in confirmes) return CONFIRMATION
        return null
    }

    /** A remote client says, once for its session, that it means to transmit from there. */
    fun confirme(c: Client) { if (c.id.isNotBlank()) confirmes += c.id }

    fun estConfirme(c: Client) = !c.distant || c.id in confirmes

    /** [c] started [quoi]: it holds the transmissions now. */
    @Synchronized
    fun prend(c: Client, quoi: String) { val t = horloge(); tenue = Tenue(c, tenue?.takeIf { it.client.id == c.id }?.depuisMs ?: t, quoi, t) }

    /** [c]'s « still there »: true when it counts (it is the controller, or nobody is). */
    @Synchronized
    fun vie(c: Client): Boolean {
        val t = tenue ?: return true
        if (t.client.id != c.id) return false
        tenue = t.copy(vieMs = horloge())
        return true
    }

    /** Nothing transmits any more: nobody holds it. */
    @Synchronized
    fun libere() { tenue = null }

    /** Why the transmission must stop now (a remote controller silent too long), or null. */
    fun perdu(): String? {
        val t = tenue ?: return null
        if (!t.client.distant) return null
        val d = horloge() - t.vieMs
        return if (d > chienDistantMs) "liaison perdue avec " + nom(t.client) + " (" + d / 1000.0 + " s sans nouvelles) : émission coupée" else null
    }

    /** For every client's page: who holds what; [pour]: whoever asks (remote, confirmed, the controller). */
    fun json(pour: Client?): JSONObject {
        val t = tenue
        val o = JSONObject().put("tenue", t != null)
        if (t != null) o.put("client", nom(t.client)).put("distant", t.client.distant).put("quoi", t.quoi)
            .put("depuisS", (horloge() - t.depuisMs) / 1000).put("vieS", (horloge() - t.vieMs) / 1000.0)
            .put("moi", pour != null && pour.id == t.client.id)
        if (pour != null) o.put("vous", JSONObject().put("distant", pour.distant).put("confirme", estConfirme(pour)))
        return o.put("chienDistantS", chienDistantMs / 1000.0)
    }

    private fun nom(c: Client) = if (c.distant) "un autre appareil" + (if (c.adresse.isNotBlank()) " (" + c.adresse + ")" else "") else "la page de ce PC"

    companion object {
        const val CONFIRMATION = "émission depuis un autre appareil : la confirmer d'abord pour cette session (la liaison coupée plus de 2 s arrête l'émission)"
    }
}
