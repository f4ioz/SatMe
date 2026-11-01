/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import org.json.JSONObject

/**
 * Whether SatMe-PC has the rotor and the rig now, said outright (the
 * `occupation` of `GET /api/etat`) so another program on this PC sharing
 * them through SatMe-PC need not work it out: the pass window — from the
 * rotor's « avant AOS » (plus a minute) before AOS to a minute after LOS,
 * the automatic mode's own lead when it is on — and what else holds them
 * (the rotor waiting at the rise point, the Doppler loop writing the rig,
 * QO-100 at the rig, a transmission, an agenda entry, the SSTV autopilot).
 * The routes that let another program tune the rig or point the mast
 * refuse while the part they touch is taken.
 */
object OccupationPc {

    /** After LOS (and before the pre-positioning): a pass's margin. */
    const val MARGE_MS = 60_000L

    data class Passage(val nom: String, val catnum: Int, val aosMs: Long, val losMs: Long)

    class Entree(
        val maintenant: Long,
        /** The followed satellite's pass under way or next, null when none. */
        val passage: Passage?,
        /** The rotor's « avant AOS » (minutes). */
        val avantAosMin: Int,
        /** The automatic mode's next kept pass, null when the mode is off or has none. */
        val veille: Passage? = null,
        /** How long before AOS the automatic mode takes a pass (ms). */
        val avanceVeilleMs: Long = fr.f4ioz.satcombo.domain.VeilleAuto.AVANT_SUIVI_MS,
        /** The followed satellite's elevation now, null when unknown. */
        val elevation: Double? = null,
        val nomSuivi: String = "",
        val rotorSuit: Boolean = false,
        /** The mast waiting at the rise point (pre-positioning). */
        val rotorAttente: Boolean = false,
        /** The Doppler loop writes the rig now (satellite up, or « sous l'horizon »). */
        val pilotageDoppler: Boolean = false,
        val qo100AuPoste: Boolean = false,
        /** SatMe-PC transmits (any transmitter). */
        val emet: Boolean = false,
        /** Title of an agenda entry under way, null when none. */
        val agenda: String? = null,
        /** The SSTV autopilot's campaign under way (its satellite), null when none. */
        val autopilote: String? = null,
    )

    class Resultat(
        val passageEnCours: Boolean,
        /** The window under way or next (null when no pass is known): its bounds and its pass. */
        val debutMs: Long?,
        val finMs: Long?,
        val passage: Passage?,
        /** Why the rotor is SatMe-PC's now, null when free. */
        val rotor: String?,
        /** Why the rig is SatMe-PC's now, null when free. */
        val poste: String?,
    ) {
        val occupe: Boolean get() = rotor != null || poste != null
        val raison: String get() = rotor ?: poste ?: ""
    }

    /** The window of [p]: from [avantMs] before AOS to [MARGE_MS] after LOS. */
    private fun fenetre(p: Passage, avantMs: Long) = (p.aosMs - avantMs) to (p.losMs + MARGE_MS)

    fun calcule(e: Entree): Resultat {
        val t = e.maintenant
        val avantRotor = (e.avantAosMin.coerceIn(0, 60) + 1) * 60_000L
        // The followed satellite's and the automatic mode's: the one under way, else the soonest not over.
        val fenetres = listOfNotNull(
            e.passage?.let { Triple(it, fenetre(it, avantRotor).first, fenetre(it, avantRotor).second) },
            e.veille?.let { val f = fenetre(it, maxOf(avantRotor, e.avanceVeilleMs)); Triple(it, f.first, f.second) })
            .filter { it.third >= t }
        val enCours = fenetres.filter { t >= it.second }.minByOrNull { it.second }
        val retenue = enCours ?: fenetres.minByOrNull { it.second }
        val pass = enCours?.let { (p, _, _) ->
            if (t < p.aosMs) "SatMe-PC se prépare au passage de ${p.nom} (AOS ${heure(p.aosMs)} UTC)"
            else "passage de ${p.nom} en cours dans SatMe-PC (LOS ${heure(p.losMs)} UTC)"
        }
        val agenda = e.agenda?.let { "rendez-vous de l'agenda de SatMe-PC en cours ($it)" }
        val autopilote = e.autopilote?.let { "l'autopilote SSTV de SatMe-PC suit $it" }
        val rotor = pass
            ?: (if (e.rotorAttente) "le rotor de SatMe-PC attend l'AOS" else null)
            ?: (if (e.rotorSuit && (e.elevation ?: -90.0) >= 0.0) "le satellite suivi par SatMe-PC (${e.nomSuivi}) est au-dessus de l'horizon" else null)
            ?: agenda ?: autopilote
        val poste = pass
            ?: (if (e.pilotageDoppler) "SatMe-PC pilote le poste (Doppler de ${e.nomSuivi.ifBlank { "satellite" }})" else null)
            ?: (if (e.qo100AuPoste) "QO-100 tient le poste dans SatMe-PC" else null)
            ?: (if (e.emet) "SatMe-PC émet" else null)
            ?: agenda ?: autopilote
        return Resultat(enCours != null, retenue?.second, retenue?.third, retenue?.first, rotor, poste)
    }

    fun json(r: Resultat): JSONObject = JSONObject()
        .put("passageEnCours", r.passageEnCours)
        .put("debut", r.debutMs ?: JSONObject.NULL).put("fin", r.finMs ?: JSONObject.NULL)
        .put("satellite", r.passage?.let { JSONObject().put("nom", it.nom).put("catnum", it.catnum).put("aos", it.aosMs).put("los", it.losMs) } ?: JSONObject.NULL)
        .put("occupe", r.occupe).put("raison", r.raison)
        .put("rotor", JSONObject().put("occupe", r.rotor != null).put("raison", r.rotor ?: ""))
        .put("poste", JSONObject().put("occupe", r.poste != null).put("raison", r.poste ?: ""))

    fun heure(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString().substring(11, 16)
}
