/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The rig keyed by SatMe (CI-V `1C 00 01`), the operator holding a button:
 * on only while asked, off by every way out.
 *
 * - **Hold to talk.** [appuie] keys for one client; only that client's
 *   [vie] keeps it keyed. Silent for [chienMs] (its page closed, its link
 *   lost, a tab hidden): unkeyed. Any client may [relache].
 * - **Time-out.** Keyed longer than [totMs]: unkeyed, whatever the client says.
 * - **The link.** The CAT gone ([Lien.pret] says why): the state goes off at
 *   once, and the unkey is sent again as soon as the link answers.
 * - **Never by itself.** Nothing keys at start, after a reconnection, or
 *   from a stored setting; an unkey refused is tried again ([tic]).
 *
 * The rig's own rules stay (its TOT, its band edges); this only decides
 * when SatMe holds the PTT.
 */
class EmissionPoste(
    private val lien: Lien,
    private val horloge: () -> Long = System::currentTimeMillis,
    /** The holding client silent longer than this: unkeyed. */
    val chienMs: Long = 2_000L,
) {
    interface Lien {
        /** Null when the rig can be keyed now (CAT connected, transmitting allowed on this link), else why not. */
        fun pret(): String?
        /** The CAT reaches the rig (an unkey can be sent, whatever is allowed). */
        fun joignable(): Boolean
        /** Sends PTT on or off; true when the rig took it. */
        suspend fun ptt(on: Boolean): Boolean
    }

    data class Etat(
        val emet: Boolean = false,
        val client: String = "",
        val distant: Boolean = false,
        val adresse: String = "",
        val depuisMs: Long = 0L,
        val vieMs: Long = 0L,
        /** Why the last over ended (released, time-out, link lost…). */
        val raison: String = "",
        val erreur: String = "",
    )

    @Volatile var etat = Etat(); private set
    /** The time-out (ms): keyed longer than this, unkeyed. */
    @Volatile var totMs: Long = 180_000L
    /** An unkey still to be sent (the link was down, or refused it). */
    @Volatile var offEnAttente = false; private set
    private val fil = Mutex()

    /** Keys the rig for [client]; null once keyed, else why not. */
    suspend fun appuie(client: String, distant: Boolean = false, adresse: String = ""): String? = fil.withLock {
        val e = etat
        if (e.emet && e.client != client) return@withLock "l'émission est tenue par " + nom(e) + " : seul ce client la pilote (STOP reste possible ici)"
        lien.pret()?.let { return@withLock it }
        val t = horloge()
        if (e.emet) { etat = e.copy(vieMs = t); return@withLock null }
        val ok = runCatching { lien.ptt(true) }.getOrDefault(false)
        if (!ok) {
            // A key refused may have half-happened: unkey to be sure.
            runCatching { lien.ptt(false) }
            etat = Etat(raison = "refusé", erreur = "le poste n'a pas pris le PTT")
            return@withLock etat.erreur
        }
        offEnAttente = false
        etat = Etat(true, client, distant, adresse, t, t)
        null
    }

    /** [client]'s « still holding »: true when it holds the PTT. */
    fun vie(client: String): Boolean {
        val e = etat
        if (!e.emet || e.client != client) return false
        etat = e.copy(vieMs = horloge())
        return true
    }

    /** True when [client] holds the PTT now (its microphone may go to the rig). */
    fun tenuPar(client: String): Boolean = etat.let { it.emet && it.client == client }

    /** Unkeys, from anyone; [raison] said to every client. Sent even when SatMe thinks it is off. */
    suspend fun relache(raison: String = "relâché") = fil.withLock { relacheDedans(raison) }

    private suspend fun relacheDedans(raison: String) {
        val avant = etat
        etat = avant.copy(emet = false, raison = if (avant.emet) raison else avant.raison)
        val ok = lien.joignable() && runCatching { lien.ptt(false) }.getOrDefault(false)
        offEnAttente = !ok
        if (!ok) etat = etat.copy(erreur = "PTT relâché par SatMe, mais le poste n'a pas confirmé : il sera renvoyé")
        else if (avant.emet) etat = etat.copy(erreur = "")
    }

    /** Why the PTT must be released now, or null. */
    fun aCouper(t: Long = horloge()): String? {
        val e = etat
        if (!e.emet) return null
        if (!lien.joignable()) return "liaison avec le poste perdue"
        lien.pret()?.let { return it }
        if (t - e.depuisMs >= totMs) return "minuterie d'émission (" + duree(totMs) + ") écoulée"
        if (t - e.vieMs > chienMs) return "plus de nouvelles de " + nom(e) + " depuis " + (t - e.vieMs) / 100 / 10.0 + " s"
        return null
    }

    /** Often (a few times a second): time-out, watchdog, link lost; an unkey waiting sent again. */
    suspend fun tic() {
        aCouper()?.let { relache(it); return }
        if (offEnAttente && !etat.emet && lien.joignable()) fil.withLock {
            if (offEnAttente && !etat.emet) offEnAttente = !runCatching { lien.ptt(false) }.getOrDefault(false)
            if (!offEnAttente) etat = etat.copy(erreur = "")
        }
    }

    /** Seconds left before the time-out, null when not keyed. */
    fun restantS(t: Long = horloge()): Long? = etat.takeIf { it.emet }?.let { maxOf(0L, (it.depuisMs + totMs - t + 999) / 1000) }

    private fun nom(e: Etat) = if (e.distant) "un autre appareil" + (if (e.adresse.isNotBlank()) " (" + e.adresse + ")" else "") else "la page de ce PC"

    companion object {
        fun duree(ms: Long): String = (ms / 1000).let { s -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s" }
    }
}
