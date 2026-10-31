/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.CarnetEnLigne
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.data.StatutsQsl
import fr.f4ioz.satcombo.domain.FiltreMoisson
import org.json.JSONObject

/**
 * The QSL statuses (eQSL, LoTW, paper) of the log's contacts, read from the
 * online log (Wavelog, Cloudlog) — read only, with the key already set.
 *
 * SatMe-PC never logs into eQSL or LoTW: Wavelog does, with its own sync, and
 * SatMe reads what Wavelog knows. A confirmation reaches SatMe only after
 * Wavelog's LoTW/eQSL sync has run.
 */
class QslPc(private val station: StationPc, private val carnet: CarnetPc, val store: StatutsQsl.Store,
            private val horloge: () -> Long = System::currentTimeMillis) {

    /** A full re-read when the last one is older than this: confirmations come on old contacts. */
    val completTousLesMs = 86_400_000L

    @Volatile var enCours = false; private set
    @Volatile var message = ""; private set

    /** The matching, kept while neither the log nor the statuses change. */
    @Volatile private var cache: Triple<Int, Long, Map<Long, StatutsQsl.Statut>>? = null

    fun associes(l: List<LogEntry> = carnet.contacts()): Map<Long, StatutsQsl.Statut> {
        val empreinte = l.hashCode()
        cache?.let { (h, r, m) -> if (h == empreinte && r == store.releveMs()) return m }
        val m = StatutsQsl.associe(l, store.statuts())
        cache = Triple(empreinte, store.releveMs(), m)
        return m
    }

    /**
     * Reads the statuses from the online log: every station location, from
     * where the last read stopped — or everything again when [tout] or when
     * the last full read is more than a day old. What happened, in a sentence.
     */
    suspend fun releve(tout: Boolean = false): String {
        ModeDemo.refus("la lecture des QSL en ligne")?.let { message = it; return it }
        val s = station.settings
        if (s.carnetUrl.isBlank() || s.carnetCle.isBlank()) return "renseigne l'adresse et la clé du carnet en ligne (Réglages)".also { message = it }
        if (enCours) return "lecture déjà en cours"
        enCours = true
        try {
            carnet.releveProfils()?.let { message = "profils illisibles : $it"; return message }
            val profils = carnet.profils
            if (profils.isEmpty()) { message = "le carnet en ligne ne donne aucun profil de station"; return message }
            val maintenant = horloge()
            val complet = tout || maintenant - store.completMs() > completTousLesMs
            val lus = ArrayList<StatutsQsl.Statut>()
            val curseurs = HashMap<String, Long>()
            var erreur = ""
            for (p in profils) {
                val depuis = if (complet) 0L else store.curseur(p.id)
                val r = runCatching { CarnetEnLigne.moissonne(s.carnetUrl, s.carnetCle, listOf(p.id), depuis, FiltreMoisson.SAT) }
                r.exceptionOrNull()?.let { erreur = it.message ?: it.javaClass.simpleName }
                val m = r.getOrNull() ?: continue
                lus += StatutsQsl.lis(m.adif)
                curseurs[p.id] = m.dernierId
            }
            if (curseurs.isEmpty()) { message = "lecture impossible : ${erreur.ifBlank { "aucune réponse" }}"; return message }
            store.fusionne(lus, curseurs, complet && curseurs.size == profils.size, maintenant)
            val b = StatutsQsl.bilan(carnet.contacts(), associes())
            message = (if (complet) "relecture complète" else "contacts nouveaux") + " : ${lus.size} lu" + (if (lus.size > 1) "s" else "") +
                " ; LoTW ${b.lotwConfirmes} confirmé" + (if (b.lotwConfirmes > 1) "s" else "") +
                ", eQSL ${b.eqslConfirmes}, papier ${b.papierConfirmes}"
            return message
        } finally { enCours = false }
    }

    /** The summary for the page. */
    fun json(): JSONObject {
        val l = carnet.contacts()
        val b = StatutsQsl.bilan(l, associes(l))
        return StatutsQsl.json(b).put("confirmations", fr.f4ioz.satcombo.domain.Confirmations.json(fr.f4ioz.satcombo.domain.Confirmations.bilan(l, associes(l))))
            .put("releveMs", store.releveMs()).put("completMs", store.completMs())
            .put("enCours", enCours).put("message", message)
            .put("configure", station.settings.carnetUrl.isNotBlank() && station.settings.carnetCle.isNotBlank())
    }
}
