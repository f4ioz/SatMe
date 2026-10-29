/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import org.json.JSONArray
import org.json.JSONObject

/**
 * « Balayage auto »: always a picture in the DATV tab. While the signal the
 * operator follows (the [prefere]) holds, nothing happens; when it does not
 * lock, is lost or its stream goes bad for [Reglages.attenteS], the receiver
 * goes to another signal — the favourites first, then the beacon, then the
 * stations present on the wide band spectrum (the strongest, those received
 * lately first) — and back to the followed one when it returns.
 *
 * Two ways to go round: [Mode.DUREE] stays [Reglages.dureeS] on each one,
 * [Mode.COUPURE] stays on one while it holds. A signal that failed is left
 * alone [ECHEC_MS] (unless nothing else is left).
 *
 * It never fights the operator: a tuning by hand ([manuel], or the receiver
 * found elsewhere than where this put it) pauses it [Reglages.pauseMin]
 * minutes and makes that signal the followed one. It only acts while the
 * receiver runs: stopped, it stays stopped.
 *
 * Pure: the caller gives the time, the receiver's state and the candidates
 * each second ([tic]), tunes when told, and says how it went ([accorde],
 * [refuse]).
 */
class SecoursDatv(var reglages: Reglages = Reglages()) {
    enum class Mode(val cle: String) {
        DUREE("duree"), COUPURE("coupure");
        companion object { fun de(s: String?) = values().firstOrNull { it.cle == s } ?: COUPURE }
    }

    data class Reglages(
        val actif: Boolean = false,
        val mode: Mode = Mode.COUPURE,
        /** [Mode.DUREE]: seconds on each signal. */
        val dureeS: Int = 30,
        /** Seconds without a sound reception before moving on. */
        val attenteS: Int = 15,
        /** Minutes the scan waits after a tuning by hand. */
        val pauseMin: Int = 10,
        /** Back to the followed signal when it returns. */
        val retour: Boolean = true,
        /** The beacon among the candidates (right after the favourites). */
        val balise: Boolean = true,
    ) {
        fun borne() = copy(dureeS = dureeS.coerceIn(10, 600), attenteS = attenteS.coerceIn(6, 120), pauseMin = pauseMin.coerceIn(1, 120))
    }

    /** A place to receive: sky frequency (kHz), symbol rate (kS/s), a name when known. */
    data class Cible(val khz: Long, val srKs: Int, val nom: String = "") {
        fun meme(o: Cible?): Boolean = o != null && meme(o.khz, o.srKs)
        fun meme(k: Long, sr: Int): Boolean = sr == srKs && Math.abs(k - khz) <= NomsSignaux.tolerance(srKs)
        val libelle: String get() = (if (nom.isNotBlank()) "$nom · " else "") +
            String.format(java.util.Locale.FRANCE, "%.3f MHz · %d kS/s", khz / 1000.0, srKs)
    }

    enum class Origine(val cle: String) { FAVORI("favori"), BALISE("balise"), SPECTRE("spectre") }

    /**
     * A signal that may be received: [niveau] its level on the spectrum (0 unknown), [recentMs] when it was
     * last received well here (0 never), [present] seen on a fresh spectrum (null: no spectrum to tell).
     */
    data class Candidat(val cible: Cible, val origine: Origine, val niveau: Int = 0, val recentMs: Long = 0L, val present: Boolean? = null)

    /** The receiver now: running, locked, [sain] locked with a sound stream and pictures; where it is tuned. */
    data class Reception(val actif: Boolean, val verrouille: Boolean, val sain: Boolean, val khz: Long, val srKs: Int)

    /** Tune there, for this reason. */
    data class Action(val cible: Cible, val raison: String, val versPrefere: Boolean)

    /** One move, for the page. */
    data class Passage(val t: Long, val cible: Cible, val raison: String)

    enum class Phase(val cle: String) { ARRET("arret"), ATTENTE("attente"), PAUSE("pause"), SUIVI("suivi"), REPLI("repli") }

    /** The signal followed: the operator's last choice. */
    var prefere: Cible? = null; private set
    /** Where this put the receiver when it is not on [prefere]. */
    var courante: Cible? = null; private set
    var phase = Phase.ARRET; private set
    var etat = ""; private set
    var pauseJusquA = 0L; private set
    /** When the receiver was tuned to where it is, and when it last received well there. */
    private var depuisMs = 0L
    private var dernierSainMs = 0L
    private var ici: Cible? = null
    /** Failed signals and when. */
    private val echecs = HashMap<Cible, Long>()
    private var essaiPrefereMs = 0L
    /** The followed signal was seen missing on the spectrum since it last failed: its return is news. */
    private var prefereParti = false
    private val passages = ArrayDeque<Passage>()
    /** The candidates in their order at the last [tic], for the page. */
    var ordre: List<Candidat> = emptyList(); private set

    @Synchronized
    fun journal(): List<Passage> = passages.toList()

    /** The operator tuned [c] by hand: followed from now, the scan paused. */
    @Synchronized
    fun manuel(t: Long, c: Cible) {
        prefere = c; courante = null; ici = c; depuisMs = t; dernierSainMs = 0L
        echecs.remove(echecs.keys.firstOrNull { it.meme(c) })
        prefereParti = false; essaiPrefereMs = t
        if (reglages.actif) pauseJusquA = t + reglages.borne().pauseMin * 60_000L
    }

    /** Switched on (nothing paused: the signal received now becomes the followed one at the next [tic]) or off. */
    @Synchronized
    fun active(on: Boolean) {
        reglages = reglages.copy(actif = on)
        pauseJusquA = 0L
        if (!on) { courante = null; phase = Phase.ARRET; etat = "" }
        else { prefere = null; courante = null; ici = null; echecs.clear() }
    }

    /** The pause ended at once (the page's « reprendre »). */
    @Synchronized
    fun reprends() { pauseJusquA = 0L }

    /** [a] done: the receiver is there now. */
    @Synchronized
    fun accorde(t: Long, a: Action) {
        courante = if (a.versPrefere) null else a.cible
        phase = if (a.versPrefere) Phase.SUIVI else Phase.REPLI
        ici = a.cible; depuisMs = t; dernierSainMs = 0L
        if (a.versPrefere) { essaiPrefereMs = t; prefereParti = false }
        passages.addLast(Passage(t, a.cible, a.raison)); while (passages.size > 20) passages.removeFirst()
    }

    /** [a] refused ([pourquoi]): that signal left alone a while, the receiver where it was. */
    @Synchronized
    fun refuse(t: Long, a: Action, pourquoi: String) {
        echecs[a.cible] = t
        if (a.versPrefere) essaiPrefereMs = t
        etat = "refusé : $pourquoi"
    }

    /**
     * Each second: [rx] the receiver, [candidats] what could be received, [spectreFrais] whether the wide band
     * spectrum is fresh (presence known), [bloque] a reason not to act now (the « veille », a recording…).
     * Returns where to tune, or null to stay.
     */
    @Synchronized
    fun tic(t: Long, rx: Reception, candidats: List<Candidat>, spectreFrais: Boolean, bloque: String? = null): Action? {
        val r = reglages.borne()
        if (!r.actif) { phase = Phase.ARRET; etat = ""; return null }
        val la = Cible(rx.khz, rx.srKs)
        if (!rx.actif) {
            phase = Phase.ATTENTE; etat = "récepteur arrêté : rien à faire"
            ici = null; return null
        }
        // Held by something else (the « veille », a recording, the beacons' check): where it puts the receiver is not ours to judge.
        if (bloque != null) { phase = Phase.ATTENTE; etat = "en attente : $bloque"; ici = null; return null }
        // Where the receiver is: moved by someone else than us, a tuning by hand.
        val p0 = prefere
        val nomme = la.copy(nom = candidats.firstOrNull { it.cible.meme(la) }?.cible?.nom.orEmpty())
        if (p0 == null) { prefere = nomme; ici = la; depuisMs = t; dernierSainMs = 0L; essaiPrefereMs = t }
        else if (!la.meme(ici)) {
            if (ici == null && (la.meme(p0) || la.meme(courante))) { ici = la; depuisMs = t; dernierSainMs = 0L }
            else manuel(t, nomme)
        }
        val pref = prefere!!
        if (rx.sain) dernierSainMs = t
        // The followed signal's presence on the spectrum, while we are elsewhere.
        val prefVu = candidats.firstOrNull { it.cible.meme(pref) }?.present
        if (courante != null && spectreFrais && prefVu != true) prefereParti = true
        ordre = ordonne(candidats, r, pref, spectreFrais, t)
        if (t < pauseJusquA) {
            phase = Phase.PAUSE
            etat = "en pause après un réglage à la main (encore " + ((pauseJusquA - t + 59_999) / 60_000) + " min)"
            return null
        }
        val sansMs = t - maxOf(depuisMs, dernierSainMs)
        val attenteMs = r.attenteS * 1000L
        val pourquoi = when {
            !rx.verrouille && dernierSainMs == 0L -> "ne s’accroche pas"
            !rx.verrouille -> "est perdu"
            else -> "a un flux abîmé"
        }
        val cour = courante
        if (cour == null) {
            phase = Phase.SUIVI
            if (rx.sain) { etat = "sur le signal suivi : tout va bien"; return null }
            if (sansMs < attenteMs) {
                etat = "le signal suivi $pourquoi (repli dans " + ((attenteMs - sansMs + 999) / 1000) + " s)"
                return null
            }
            echecs[pref] = t; essaiPrefereMs = t; prefereParti = false
            return va(t, null, r, "le signal suivi $pourquoi depuis " + (sansMs / 1000) + " s")
        }
        phase = Phase.REPLI
        // Back to the followed one: it reappeared on the spectrum, or now and then without a spectrum.
        if (r.retour && t - essaiPrefereMs >= RETOUR_MIN_MS) {
            if (spectreFrais && prefVu == true && prefereParti)
                return Action(pref, "retour au signal suivi : il est revenu sur le spectre", true)
            if (t - essaiPrefereMs >= ESSAI_PREFERE_MS && (!spectreFrais || prefVu == true))
                return Action(pref, "nouvel essai du signal suivi", true)
        }
        if (r.mode == Mode.DUREE && t - depuisMs >= r.dureeS * 1000L)
            return va(t, cour, r, r.dureeS.toString() + " s écoulées") ?: run { etat = "repli sur " + cour.libelle + " : aucun autre signal"; null }
        if (!rx.sain && sansMs >= attenteMs) {
            echecs[cour] = t
            return va(t, cour, r, cour.libelle + " $pourquoi depuis " + (sansMs / 1000) + " s")
        }
        etat = when {
            !rx.sain -> "essai de " + cour.libelle + " : pas encore d’image (" + (sansMs / 1000) + " s)"
            r.mode == Mode.COUPURE -> "repli sur " + cour.libelle + " : on y reste jusqu’à la coupure"
            else -> "repli sur " + cour.libelle + " : encore " + ((r.dureeS * 1000L - (t - depuisMs) + 999) / 1000) + " s"
        }
        return null
    }

    /** The next candidate after [depuis] (the first when null), or null with a word in [etat]. */
    private fun va(t: Long, depuis: Cible?, r: Reglages, raison: String): Action? {
        val pref = prefere
        val l = ordre.filter { !it.cible.meme(pref) && it.present != false }
        val libres = l.filter { c -> echecs.entries.none { it.key.meme(c.cible) && t - it.value < ECHEC_MS } }
        // All failed lately: tried again all the same rather than nothing.
        val choix = libres.ifEmpty { l }.filter { !it.cible.meme(depuis) }
        if (choix.isEmpty()) { etat = "aucun autre signal à essayer ($raison)"; return null }
        val suivant = if (r.mode == Mode.DUREE && depuis != null) {
            // Round: the next one after where we are in the order, back to the first.
            val i = l.indexOfFirst { it.cible.meme(depuis) }
            (l.drop(i + 1) + l.take(maxOf(0, i + 1))).firstOrNull { c -> choix.any { it.cible.meme(c.cible) } } ?: choix.first()
        } else choix.first()
        return Action(suivant.cible, raison + " → " + suivant.cible.libelle, false)
    }

    companion object {
        /** A failed signal left alone this long. */
        const val ECHEC_MS = 3 * 60_000L
        /** No return to the followed signal sooner than this after it last failed. */
        const val RETOUR_MIN_MS = 60_000L
        /** The followed signal tried again this often while it cannot be seen. */
        const val ESSAI_PREFERE_MS = 5 * 60_000L
        /** Received well within this: « recent », ahead of the others. */
        const val RECENT_MS = 3_600_000L

        /**
         * The candidates in their order: the favourites (as listed), the beacon (when chosen), then the other
         * signals — those received well within [RECENT_MS] first, then the strongest. Each once; the followed one
         * kept (for its presence), never twice; absent ones (a fresh spectrum without them) at the end.
         */
        fun ordonne(l: List<Candidat>, r: Reglages, prefere: Cible?, spectreFrais: Boolean, t: Long = Long.MAX_VALUE): List<Candidat> {
            val vus = ArrayList<Candidat>()
            fun ajoute(c: Candidat) { if (vus.none { it.cible.meme(c.cible) }) vus += c }
            l.filter { it.origine == Origine.FAVORI }.forEach { ajoute(it) }
            if (r.balise) l.filter { it.origine == Origine.BALISE }.forEach { ajoute(it) }
            l.filter { it.origine == Origine.SPECTRE }
                .sortedWith(compareByDescending<Candidat> { it.recentMs > 0 && (t == Long.MAX_VALUE || t - it.recentMs < RECENT_MS) }.thenByDescending { it.niveau })
                .forEach { ajoute(it) }
            // Not seen on a fresh spectrum: kept for the page, last (va() leaves them out).
            return vus.filter { it.present != false || !spectreFrais } + vus.filter { it.present == false && spectreFrais }
        }
    }

    /** For the page. */
    @Synchronized
    fun json(t: Long): JSONObject {
        val r = reglages.borne()
        fun c(x: Cible?) = x?.let { JSONObject().put("khz", it.khz).put("sr", it.srKs).put("nom", it.nom).put("libelle", it.libelle) } ?: JSONObject.NULL
        return JSONObject().put("on", r.actif).put("mode", r.mode.cle).put("duree", r.dureeS).put("attente", r.attenteS).put("pause", r.pauseMin)
            .put("retour", r.retour).put("balise", r.balise)
            .put("phase", phase.cle).put("etat", etat).put("pauseReste", if (t < pauseJusquA) (pauseJusquA - t) / 1000 else 0)
            .put("prefere", c(prefere)).put("courante", c(courante))
            .put("candidats", JSONArray(ordre.take(16).map { k ->
                c(k.cible).let { it as JSONObject }.put("origine", k.origine.cle).put("niveau", k.niveau).put("present", k.present ?: JSONObject.NULL)
                    .put("suivi", k.cible.meme(prefere)).put("ici", k.cible.meme(courante ?: prefere))
                    .put("echec", echecs.entries.any { it.key.meme(k.cible) && t - it.value < ECHEC_MS })
            }))
            .put("journal", JSONArray(passages.reversed().take(10).map { JSONObject().put("t", it.t).put("libelle", it.cible.libelle).put("raison", it.raison) }))
    }
}
