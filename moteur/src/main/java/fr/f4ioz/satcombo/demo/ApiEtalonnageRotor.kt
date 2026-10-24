/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.rotor.CorrectionAxe
import fr.f4ioz.satcombo.rotor.Gs232Codec
import fr.f4ioz.satcombo.station.StationRotor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rotor calibration assistant's routes (`api/rotor/etalonnage…`), the
 * same for SatMe-PC and for the page the phone serves: a GET reads, a POST
 * acts. Nothing moves but on a POST the operator clicked; the controller's
 * own calibrations want `confirme=1` on top.
 */
object ApiEtalonnageRotor {

    const val CHEMIN = "/api/rotor/etalonnage"

    /** This route is the assistant's. */
    fun pourMoi(chemin: String) = chemin == CHEMIN || chemin.startsWith("$CHEMIN/")

    /** What the assistant shows: the controller's raw reading, SatMe's, the correction, the log. */
    fun etat(r: StationRotor): JSONObject {
        val e = r.etat.value
        fun n(d: Double?): Any = d?.let { Math.round(it * 10.0) / 10.0 } ?: JSONObject.NULL
        val (pAz, pEl) = r.porteeReelle()
        val c = r.correction
        return JSONObject().put("ok", true)
            .put("ouvert", e.rotorEtalonnage).put("connecte", e.rotorConnected)
            .put("lien", if (e.rotorSim) "SIM" else e.rotorLink)
            // O/F go only over a serial GS-232 (or the simulated K3NG); rotctld has none.
            .put("commandes", e.rotorConnected && (e.rotorSim || e.rotorLink != "ROTCTLD"))
            .put("suit", e.rotorEnabled)
            .put("brutAz", n(e.rotorBrutAz)).put("brutEl", n(e.rotorBrutEl))
            .put("az", n(e.rotorActualAz)).put("el", n(e.rotorActualEl))
            .put("azCible", n(e.rotorTargetAz)).put("elCible", n(e.rotorTargetEl))
            .put("statut", e.rotorStatus)
            .put("journal", JSONArray(e.rotorEtalJournal))
            .put("ecretage", n(e.rotorEcretageDeg))
            .put("reglages", JSONObject().put("maxAz", e.rotorMaxAz).put("maxEl", e.rotorMaxEl).put("flip", e.rotorFlip)
                .put("butee", e.rotorAzStop).put("depuisButee", e.rotorAzFromStop).put("azSeul", e.rotorAzOnly))
            .put("correction", JSONObject()
                .put("az", e.rotorCorrAz).put("el", e.rotorCorrEl)
                .put("azResume", if (c.az.estIdentite) t("rotor_corr_aucune") else c.az.resume())
                .put("elResume", if (c.el.estIdentite) t("rotor_corr_aucune") else c.el.resume())
                .put("identite", c.estIdentite)
                .put("portee", JSONObject().put("azMin", n(pAz.start)).put("azMax", n(pAz.endInclusive))
                    .put("elMin", n(pEl.start)).put("elMax", n(pEl.endInclusive))))
    }

    /** `lu:réel;lu:réel` → pairs; null when a pair is not two numbers. */
    fun releves(s: String): List<Pair<Double, Double>>? =
        s.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
            val ab = p.split(':')
            if (ab.size != 2) return null
            (ab[0].trim().replace(',', '.').toDoubleOrNull() ?: return null) to
                (ab[1].trim().replace(',', '.').toDoubleOrNull() ?: return null)
        }

    /** A POST of the assistant. */
    fun action(r: StationRotor, chemin: String, p: (String) -> String): JSONObject {
        val ok = JSONObject().put("ok", true)
        fun non(m: String) = JSONObject().put("ok", false).put("erreur", m)
        fun nombre(k: String) = p(k).trim().replace(',', '.').toDoubleOrNull()
        val axe = if (p("axe").uppercase() == "AZ") "AZ" else "EL"
        when (chemin.removePrefix(CHEMIN)) {
            "/ouvrir" -> r.etalonnageOuvre()
            "/fermer" -> r.etalonnageFerme()
            "/stop" -> r.rotorStopNow()
            "/commande" -> {
                val e = Gs232Codec.Etalonnage.de(p("c")) ?: return non("commande inconnue : " + p("c"))
                // Writes the controller's EEPROM: never without the operator's yes.
                if (p("confirme") != "1") return non("à confirmer : écrit dans l'EEPROM du contrôleur")
                val res = runBlocking { r.etalonnageCommande(e) }
                return JSONObject().put("ok", res.ok).put("issue", res.issue).put("message", res.message)
                    .apply { if (!res.ok) put("erreur", res.message) }
            }
            "/aller" -> {
                val az = nombre("az"); val el = nombre("el")
                if (az == null && el == null) return non("azimut ou élévation attendu")
                runBlocking { r.etalonnageVa(az, el) }?.let { return non(it) }
            }
            "/correction" -> if (!r.setRotorCorrection(axe, p("valeur"))) return non(t("rotor_corr_refusee"))
            "/raz" -> if (p("axe").isBlank()) { r.setRotorCorrection("AZ", CorrectionAxe.IDENTITE); r.setRotorCorrection("EL", CorrectionAxe.IDENTITE) }
                else r.setRotorCorrection(axe, CorrectionAxe.IDENTITE)
            "/ajuste" -> {
                val l = releves(p("releves")) ?: return non(t("rotor_corr_refusee"))
                r.ajusteCorrection(axe, l, parPoints = p("mode") == "points") ?: return non(t("rotor_corr_refusee"))
            }
            else -> return non("inconnu : $chemin")
        }
        return ok.put("etat", etat(r))
    }
}
