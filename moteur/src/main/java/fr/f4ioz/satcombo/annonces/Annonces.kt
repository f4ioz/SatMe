/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.annonces

import fr.f4ioz.satcombo.annonces.LectureAnnonce as L
import fr.f4ioz.satcombo.plateforme.Stockage

/**
 * Spoken announcements: what the station says aloud when something happens
 * (a pass rising, a transmission started, our callsign decoded…). Off unless
 * chosen; each event has its own switch. Nothing here speaks — the PC (or a
 * browser) does; this is what to say and when.
 */
object Annonces {

    /** What may be announced; [parDefaut]: on once the feature is switched on. */
    enum class Sorte(val libelle: String, val parDefaut: Boolean) {
        AOS_PROCHE("Passage dans N minutes", true),
        AOS("Début du passage (AOS)", true),
        LOS("Fin du passage (LOS)", true),
        EMISSION("Émission commencée / arrêtée", true),
        RAPPEL_EMISSION("« Émission en cours » toutes les N minutes", true),
        LEILA("LEILA entendue sur QO-100", true),
        SSTV("Nouvelle image SSTV", true),
        APPEL("Mon indicatif décodé (FT8, CW)", true),
        DATV_VERROU("DATV accrochée / perdue", false),
        DATV_STATION("Station DATV identifiée", true),
        AVION("Fenêtre de diffusion par avion", false),
        ROTOR("Rotor en butée ou perdu", true),
        SDR("Récepteur SDR arrêté de lui-même", true),
        MOLETTE("Pas de la molette", false),
        ESSAI("Essai", true),
    }

    enum class Detail { COURT, NORMAL, DETAILLE }

    /** Where the voice comes from: the browser showing the page, this PC's speaker, or both. */
    enum class Sortie { NAVIGATEUR, PC, LES_DEUX }

    data class Reglages(
        val on: Boolean = false,
        /** Quick mute (the header's speaker): the settings kept, nothing said. */
        val muet: Boolean = false,
        val sortes: Set<Sorte> = Sorte.values().filter { it.parDefaut }.toSet(),
        val detail: Detail = Detail.NORMAL,
        val sortie: Sortie = Sortie.NAVIGATEUR,
        /** Callsigns spelled with the ICAO alphabet (else letter by letter). */
        val phonetique: Boolean = true,
        val minutesAvant: Int = 2,
        val rappelMinutes: Int = 5,
        /** 0.5 slow … 2 fast, 1 normal. */
        val vitesse: Double = 1.0,
        /** 0..100. */
        val volume: Int = 80,
        /** The engine's voice ("" its French default). */
        val voix: String = "",
        /** The PC's engine chosen ("" the first found). */
        val moteur: String = "",
        /** The SDR speaker lowered while the PC speaks (0..100 % of its level kept; 100 = not lowered). */
        val baisse: Int = 25,
    ) {
        fun veut(s: Sorte) = on && !muet && (s == Sorte.ESSAI || s in sortes)
        fun pc() = sortie != Sortie.NAVIGATEUR
        fun navigateur() = sortie != Sortie.PC
    }

    private const val P = "annonces_"

    fun lit(s: Stockage): Reglages {
        val d = Reglages()
        val sortes = s.getString(P + "sortes", null)?.split(',')?.mapNotNull { n -> Sorte.values().firstOrNull { it.name == n } }?.toSet() ?: d.sortes
        return Reglages(
            on = s.getBoolean(P + "on", false),
            muet = s.getBoolean(P + "muet", false),
            sortes = sortes,
            detail = runCatching { Detail.valueOf(s.getString(P + "detail", null) ?: "") }.getOrDefault(d.detail),
            sortie = runCatching { Sortie.valueOf(s.getString(P + "sortie", null) ?: "") }.getOrDefault(d.sortie),
            phonetique = s.getBoolean(P + "phonetique", true),
            minutesAvant = s.getInt(P + "minutes", d.minutesAvant).coerceIn(1, 30),
            rappelMinutes = s.getInt(P + "rappel", d.rappelMinutes).coerceIn(1, 60),
            vitesse = s.getFloat(P + "vitesse", 1f).toDouble().coerceIn(0.5, 2.0),
            volume = s.getInt(P + "volume", d.volume).coerceIn(0, 100),
            voix = s.getString(P + "voix", "") ?: "",
            moteur = s.getString(P + "moteur", "") ?: "",
            baisse = s.getInt(P + "baisse", d.baisse).coerceIn(0, 100),
        )
    }

    fun ecrit(s: Stockage, r: Reglages) {
        s.edit().putBoolean(P + "on", r.on).putBoolean(P + "muet", r.muet)
            .putString(P + "sortes", r.sortes.joinToString(",") { it.name })
            .putString(P + "detail", r.detail.name).putString(P + "sortie", r.sortie.name)
            .putBoolean(P + "phonetique", r.phonetique).putInt(P + "minutes", r.minutesAvant).putInt(P + "rappel", r.rappelMinutes)
            .putFloat(P + "vitesse", r.vitesse.toFloat()).putInt(P + "volume", r.volume).putString(P + "voix", r.voix)
            .putString(P + "moteur", r.moteur).putInt(P + "baisse", r.baisse).apply()
    }

    /**
     * Something worth saying. [sujet]: the satellite, the transmission
     * (« DATV »…), the callsign heard; [nombre]: minutes, MER, report… as
     * the event uses them; [cle] tells two different events of a kind apart.
     */
    data class Evenement(
        val sorte: Sorte,
        val sujet: String = "",
        val nombre: Int? = null,
        val elevation: Int? = null,
        val hz: Long? = null,
        val mode: String = "",
        val texte: String = "",
        val debut: Boolean = true,
    )

    /** The sentence said for [e]. */
    fun texte(e: Evenement, r: Reglages, moi: String = ""): String {
        val d = r.detail
        val court = d == Detail.COURT
        val detaille = d == Detail.DETAILLE
        fun ind(c: String) = L.indicatif(c, r.phonetique)
        fun el() = e.elevation?.takeIf { !court }?.let { ", élévation maximale " + L.degres(it) } ?: ""
        val sat = L.satellite(e.sujet)
        return when (e.sorte) {
            Annonces.Sorte.AOS_PROCHE -> sat + " dans " + L.minutes(e.nombre ?: r.minutesAvant) + el()
            Annonces.Sorte.AOS -> (if (court) "$sat se lève" else "Début du passage de $sat") + el() +
                (e.nombre?.takeIf { detaille }?.let { ", pendant " + L.minutes(it) } ?: "")
            Annonces.Sorte.LOS -> if (court) "$sat se couche" else "Fin du passage de $sat"
            Annonces.Sorte.EMISSION -> (if (e.debut) "Émission " + e.sujet + (if (court) "" else " en cours") else "Fin d'émission " + e.sujet) +
                (e.hz?.takeIf { detaille && e.debut }?.let { ", " + L.frequence(it) + " mégahertz" } ?: "")
            Annonces.Sorte.RAPPEL_EMISSION -> "Émission en cours" +
                (if (detaille) " : " + e.sujet + (e.nombre?.let { ", depuis " + L.minutes(it) } ?: "") else "")
            Annonces.Sorte.LEILA -> if (court) "LEILA" else "LEILA : trop de puissance"
            Annonces.Sorte.SSTV -> (if (court) "Image SSTV" else "Nouvelle image SSTV") + (if (detaille && e.mode.isNotBlank()) ", " + e.mode else "")
            Annonces.Sorte.APPEL -> (if (court) "Appel de " + ind(e.sujet) else ind(moi) + " appelé par " + ind(e.sujet)) +
                (if (detaille) (if (e.mode.isNotBlank()) ", en " + e.mode else "") + (e.nombre?.let { ", " + L.nombre(it) + " décibels" } ?: "") else "")
            Annonces.Sorte.DATV_VERROU -> (if (e.debut) "DATV accrochée" else "DATV perdue") +
                (if (detaille && e.debut) (e.hz?.let { ", " + L.frequence(it) } ?: "") + (e.nombre?.let { ", MER " + L.nombre(it) + " décibels" } ?: "") else "")
            Annonces.Sorte.DATV_STATION -> (if (court) "DATV : " else "Station DATV identifiée : ") + ind(e.sujet)
            Annonces.Sorte.AVION -> (if (court) "Avion" else "Fenêtre de diffusion par avion ouverte") +
                (if (!court && e.sujet.isNotBlank()) ", vol " + L.indicatif(e.sujet, false) else "") +
                (e.nombre?.takeIf { detaille }?.let { ", pendant " + L.minutes(it) } ?: "")
            Annonces.Sorte.ROTOR -> e.texte.ifBlank { "Rotor" }
            Annonces.Sorte.SDR -> (if (court) "SDR arrêté" else "Récepteur SDR arrêté") + (if (detaille && e.texte.isNotBlank()) " : " + e.texte else "")
            Annonces.Sorte.MOLETTE -> "pas " + L.pasHz(e.hz ?: 0L)
            Annonces.Sorte.ESSAI -> "Annonces SatMe : essai. " + L.satellite("ISS") + " dans " + L.minutes(2) +
                ", élévation maximale " + L.degres(45) + ". " + ind(moi.ifBlank { "F4IOZ" }) + ". " + L.frequence(10_489_750_000L) + "."
        }
    }
}
