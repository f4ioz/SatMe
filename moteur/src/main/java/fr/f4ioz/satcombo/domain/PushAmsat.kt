/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.data.AmsatSubmit
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.SettingsStore

/**
 * The AMSAT push: each pass where a contact was logged, reported "Heard" to
 * the AMSAT Live OSCAR Status page by itself, once the pass is over — at
 * the time of its first contact (AMSAT files a report in its quarter-hour).
 * The same on the phone and on SatMe-PC.
 *
 * Only when chosen ([SettingsStore.amsatAuto]), with a callsign, and only
 * the contacts made since: turning it on never reports an old log.
 */
class PushAmsat(
    private val settings: SettingsStore,
    private val envoie: suspend (nom: String, timeMs: Long, indicatif: String, locator: String) -> Boolean =
        { nom, t, ind, loc -> AmsatSubmit.send(nom, AmsatSubmit.Report.HEARD, ind, loc, t) },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    /** A pass to report: the satellite as logged, when it was first heard, from where, its key. */
    data class Rapport(val sat: String, val timeMs: Long, val locator: String, val cle: String)

    /** What it last did, for the settings ("ISS signalé entendu à 14:32"). */
    @Volatile var etat: String = ""; private set
    @Volatile private var enCours = false
    private var reessai = 0L

    /** Called every second or so: one pass reported at a time, in the background. */
    fun tic(log: List<LogEntry>, indicatif: String, locatorStation: String, nomAmsat: (String) -> String) {
        if (!settings.amsatAuto || enCours || indicatif.isBlank()) return
        val maintenant = horloge()
        if (maintenant < reessai) return
        val r = aEnvoyer(log, settings.amsatAutoDepuisMs, settings.amsatEnvoyes.toSet(), maintenant).firstOrNull() ?: return
        enCours = true
        kotlin.concurrent.thread(isDaemon = true, name = "satme-amsat") {
            try {
                val nom = nomAmsat(r.sat)
                val ok = runCatching { kotlinx.coroutines.runBlocking { envoie(nom, r.timeMs, indicatif, r.locator.ifBlank { locatorStation }) } }.getOrDefault(false)
                val heure = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE).format(java.util.Date(r.timeMs))
                if (ok) {
                    settings.amsatEnvoyes = settings.amsatEnvoyes + r.cle
                    etat = "$nom signalé entendu à amsat.org ($heure)"
                } else {
                    reessai = horloge() + REESSAI_MS
                    etat = "$nom : amsat.org n'a pas répondu, nouvel essai dans 10 min"
                }
            } finally { enCours = false }
        }
    }

    companion object {
        /** No contact on it for this long: the pass is over. */
        const val CALME_MS = 15 * 60_000L
        /** Contacts closer than this on the same satellite: the same pass. */
        const val ECART_MS = 25 * 60_000L
        /** Older than this: no longer reported (AMSAT's table is about now). */
        const val AGE_MAX_MS = 24 * 3_600_000L
        const val REESSAI_MS = 10 * 60_000L

        /** The passes to report now, oldest first: contacts since [depuisMs], by satellite and pass, over, not yet sent. */
        fun aEnvoyer(log: List<LogEntry>, depuisMs: Long, envoyes: Set<String>, maintenant: Long): List<Rapport> {
            val l = log.filter { it.satName.isNotBlank() && it.callsign.isNotBlank() && it.timeMs >= depuisMs && it.timeMs >= maintenant - AGE_MAX_MS && it.timeMs <= maintenant }
            val out = ArrayList<Rapport>()
            for ((sat, contacts) in l.groupBy { it.satName.trim() }) {
                val tries = contacts.sortedBy { it.timeMs }
                var debut = 0
                for (i in tries.indices) {
                    val fin = i == tries.lastIndex || tries[i + 1].timeMs - tries[i].timeMs > ECART_MS
                    if (!fin) continue
                    val premier = tries[debut]; val dernier = tries[i]
                    debut = i + 1
                    if (maintenant - dernier.timeMs < CALME_MS) continue
                    val cle = sat.uppercase().replace(Regex("[^A-Z0-9-]"), "") + "@" + premier.timeMs / 60_000L
                    if (cle !in envoyes) out += Rapport(sat, premier.timeMs, premier.myLocator, cle)
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}
