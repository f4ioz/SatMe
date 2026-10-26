/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.polarisation

/**
 * « Sonder H/V »: the receiving antenna put on H then V (the one it is on
 * first, to switch once less), each held [stabMs] for the relay and the
 * receiver's AGC to settle, then measured [mesureMs]; the best kept, with
 * [hystDb] of hysteresis so a fading does not flip it back and forth.
 * Again every [periodeMs], or at once when the signal falls [chuteDb]
 * under what it was after the last decision.
 *
 * Driven by [tic] (a few times a second); it never commands by itself:
 * it says what to command, the caller sends it only where allowed.
 * Receive only: the caller never lets it touch an antenna that transmits.
 */
class SondeHv(
    var periodeMs: Long = 30_000L,
    var stabMs: Long = 1_500L,
    var mesureMs: Long = 2_000L,
    var hystDb: Double = 2.5,
    var chuteDb: Double = 6.0,
) {
    /** A level read ([db]) and when ([tMs]: a reading older than the switch is never taken). */
    data class Mesure(val db: Double, val tMs: Long)

    /** One probe's outcome: [hDb], [vDb] the mean levels, [gagnante] kept, [avant] the polarisation before. */
    data class Resultat(val hDb: Double, val vDb: Double, val gagnante: Pol, val avant: Pol?, val tMs: Long) {
        /** H minus V, dB. */
        val ecartDb: Double get() = hDb - vDb
    }

    /** What to do now: [commande] to send (null: nothing), [resultat] when a probe just ended. */
    data class Action(val commande: Pol? = null, val resultat: Resultat? = null, val abandon: String? = null)

    enum class Phase { REPOS, ATTENTE1, MESURE1, ATTENTE2, MESURE2 }

    @Volatile var phase = Phase.REPOS; private set
    private var debutMs = 0L
    private var premiere = Pol.H
    private var avant: Pol? = null
    private val echantillons = mutableListOf<Mesure>()
    private var niveau1: Double? = null
    /** When the last probe ended (or started, when it was abandoned). */
    @Volatile var derniereMs = Long.MIN_VALUE / 2; private set
    /** The level after the last decision (the reference a fall is measured from). */
    @Volatile var reference: Double? = null; private set
    /** A polarisation to put back once commanding is allowed again (a probe cut short). */
    @Volatile var aRetablir: Pol? = null; private set
    @Volatile var dernier: Resultat? = null; private set

    private fun autre(p: Pol) = if (p == Pol.H) Pol.V else Pol.H

    /** Starts a probe at the next [tic], whatever the period says. */
    fun maintenant() { derniereMs = Long.MIN_VALUE / 2; reference = null }

    /** Forgets the reference (another satellite, another pass). */
    fun oublie() { reference = null; aRetablir = null; if (phase != Phase.REPOS) phase = Phase.REPOS }

    /**
     * One step at [t]. [actif]: the probe is wanted and possible (selector
     * on AUTO, the satellite up, no decoding to spoil); [peutCommander]:
     * a command may go now (not transmitting on it). [actuelle]: the
     * antenna's polarisation now; [mesure]: the latest level read.
     */
    fun tic(t: Long, actif: Boolean, peutCommander: Boolean, actuelle: Pol?, mesure: Mesure?): Action {
        if (!actif || !peutCommander) {
            if (phase == Phase.REPOS) {
                if (actif || aRetablir == null || !peutCommander) return Action()
                // Not wanted any more, but a probe was cut short: its polarisation back when allowed.
                return Action(commande = aRetablir.also { aRetablir = null }?.takeIf { it != actuelle })
            }
            phase = Phase.REPOS
            aRetablir = avant
            derniereMs = t
            return Action(abandon = if (!peutCommander) "émission ou sélecteur : sondage interrompu" else "sondage arrêté")
        }
        aRetablir?.let { r -> aRetablir = null; if (phase == Phase.REPOS && r != actuelle) return Action(commande = r) }
        when (phase) {
            Phase.REPOS -> {
                val m = mesure?.takeIf { t - it.tMs <= 3_000L }
                val chute = reference?.let { r -> m != null && m.db < r - chuteDb } == true
                if (t - derniereMs < periodeMs && !chute) return Action()
                avant = actuelle
                premiere = if (actuelle == Pol.V) Pol.V else Pol.H
                return demarre(t, Phase.ATTENTE1, premiere, actuelle)
            }
            Phase.ATTENTE1, Phase.ATTENTE2 -> {
                if (t - debutMs >= stabMs) { phase = if (phase == Phase.ATTENTE1) Phase.MESURE1 else Phase.MESURE2; echantillons.clear() }
                return Action()
            }
            Phase.MESURE1, Phase.MESURE2 -> {
                if (mesure != null && mesure.tMs >= debutMs + stabMs && echantillons.none { it.tMs == mesure.tMs }) echantillons += mesure
                if (t - debutMs < stabMs + mesureMs) return Action()
                val moyenne = echantillons.takeIf { it.isNotEmpty() }?.map { it.db }?.average()
                if (moyenne == null) {
                    phase = Phase.REPOS; derniereMs = t
                    return Action(commande = avant?.takeIf { it != actuelle }, abandon = "aucune mesure du signal")
                }
                if (phase == Phase.MESURE1) {
                    niveau1 = moyenne
                    return demarre(t, Phase.ATTENTE2, autre(premiere), actuelle)
                }
                val (h, v) = if (premiere == Pol.H) niveau1!! to moyenne else moyenne to niveau1!!
                val gagnante = decide(h, v, avant, hystDb)
                phase = Phase.REPOS; derniereMs = t
                reference = if (gagnante == Pol.H) h else v
                val r = Resultat(h, v, gagnante, avant, t)
                dernier = r
                return Action(commande = gagnante.takeIf { it != actuelle }, resultat = r)
            }
        }
    }

    private fun demarre(t: Long, p: Phase, pol: Pol, actuelle: Pol?): Action {
        phase = p; debutMs = t; echantillons.clear()
        return Action(commande = pol.takeIf { it != actuelle })
    }

    companion object {
        /**
         * H or V from their levels: the better one, but the one in place
         * ([avant], when H or V) kept unless the other beats it by [hystDb].
         */
        fun decide(hDb: Double, vDb: Double, avant: Pol?, hystDb: Double): Pol = when (avant) {
            Pol.H -> if (vDb >= hDb + hystDb) Pol.V else Pol.H
            Pol.V -> if (hDb >= vDb + hystDb) Pol.H else Pol.V
            else -> if (hDb >= vDb) Pol.H else Pol.V
        }

        /** The Icom S-meter scale (0 = S0, 120 = S9, 241 = S9+60 dB) as dB above S0, roughly. */
        fun smetreDb(v: Int): Double = if (v <= 120) v * 54.0 / 120.0 else 54.0 + (v - 120) * 60.0 / 121.0
    }
}
