/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.VeilleAuto
import org.json.JSONArray
import org.json.JSONObject

/**
 * The automatic mode on the station page (Agenda tab), written once: SatMe-PC
 * and the phone keep its choices in their settings and say its plan in the
 * same words.
 */
object ApiVeille {

    /** What the station has ready for it: the sound, the rig, the mast. */
    class Pret(val carte: Boolean, val cat: Boolean, val rotor: Boolean, val rotorSuit: Boolean, val calcul: Boolean)

    fun json(s: SettingsStore, nom: (Int) -> String?, plan: List<VeilleAuto.Ligne>, maintenant: Long, pret: Pret): JSONObject {
        val suivi = VeilleAuto.aSuivre(plan, maintenant)
        return JSONObject().put("on", s.veilleActive).put("el", s.veilleElMin).put("son", s.veilleSon)
            .put("sats", JSONArray(s.veilleSats.map { n -> JSONObject().put("n", n).put("nom", nom(n) ?: "#$n") }
                .sortedBy { it.getString("nom") }))
            .put("plan", JSONArray(plan.map { l -> JSONObject().put("cle", VeilleAuto.cle(l.passage))
                .put("sat", l.passage.satName).put("n", l.passage.catalogNumber).put("aos", l.passage.aosEpochMs).put("los", l.passage.losEpochMs)
                .put("el", Math.round(l.passage.maxElevationDeg)).put("garde", l.garde).put("ecarte", l.ecarte).put("perdu", l.perdu ?: "") }))
            .put("enCours", suivi?.let { VeilleAuto.cle(it) } ?: "")
            .put("calcul", pret.calcul).put("carte", pret.carte)
            .put("cat", pret.cat).put("rotor", pret.rotor).put("rotorSuit", pret.rotorSuit)
    }

    /** The page's changes ([q] reads its parameters); true when something changed. */
    fun action(q: (String) -> String?, s: SettingsStore, favoris: Set<Int>, maintenant: Long): Boolean {
        var change = false
        q("on")?.let { s.veilleActive = it == "1"; change = true }
        q("ajoute")?.toIntOrNull()?.let { s.veilleSats = s.veilleSats + it; change = true }
        q("retire")?.toIntOrNull()?.let { s.veilleSats = s.veilleSats - it; change = true }
        if (q("favoris") == "1") { s.veilleSats = s.veilleSats + favoris; change = true }
        q("el")?.toIntOrNull()?.let { s.veilleElMin = it; change = true }
        q("son")?.let { s.veilleSon = it == "1"; change = true }
        q("ecarte")?.let { s.veilleEcartes = VeilleAuto.bascule(s.veilleEcartes, it, true, maintenant); change = true }
        q("garde")?.let { s.veilleEcartes = VeilleAuto.bascule(s.veilleEcartes, it, false, maintenant); change = true }
        return change
    }
}
