/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.domain

import fr.f4ioz.satcombo.cat.GardeLiaisonCat
import org.json.JSONObject
import java.util.Locale

/**
 * « Pourquoi rien ? »: when a receiver decodes nothing, one short line that
 * names the most likely cause from what SatMe already knows — the rig on
 * another frequency or in another mode than the function needs, a sound
 * input that gives no samples at all (dead) or only silence (a squelch shut:
 * not a fault), the satellite below the horizon, the SDR stopped, the node
 * out of reach, the DATV receiver not locked while the beacon was fine.
 *
 * Every fact is dated (« lu il y a 3 s », « il y a 4 min »): an old reading
 * is never shown as the present. Nothing is guessed: a fact SatMe does not
 * have is left out, and the line falls back to what is known.
 *
 * Pure functions of a snapshot, one per receiver. The only gesture offered
 * ([Action]) is one the page already has to tune the receive side — follow
 * the satellite, choose its transmitter, let the Doppler drive again
 * ([CHEMINS_PERMIS]): never a transmit route.
 */
object PourquoiRien {

    enum class Gravite(val cle: String) { OK("ok"), INFO("info"), ATTENTION("attention"), PANNE("panne") }

    /** A gesture of the page (POST [chemin] with [params]): receive-side tuning only. */
    data class Action(val chemin: String, val params: Map<String, String>, val libelle: String) {
        init { require(chemin in CHEMINS_PERMIS) { "not a receive tuning route: $chemin" } }
    }

    /**
     * The page's routes an [Action] may name: follow a satellite, choose its
     * transmitter (the Doppler loop then writes its downlink and mode, and
     * the uplink frequency only — never the PTT), release the Doppler hold.
     */
    val CHEMINS_PERMIS = setOf("api/suivre", "api/emetteur", "api/maintien")

    data class Diagnostic(val gravite: Gravite, val texte: String, val action: Action? = null) {
        fun json(t: Long): JSONObject = JSONObject().put("gravite", gravite.cle).put("texte", texte).put("t", t)
            .put("action", action?.let { a -> JSONObject().put("chemin", a.chemin).put("libelle", a.libelle)
                .put("params", JSONObject(a.params)) } ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------ the facts

    /** The decoders' sound input: running, its name, its last block, its level over a window. */
    data class Entree(val actif: Boolean, val nom: String, val dernierBlocMs: Long, val rmsDbfs: Double?, val creteDbfs: Double?, val fenetreMs: Long)

    /**
     * The rig in CAT: its name, its dial (sky Hz, converters undone) and mode
     * with when they were read, silent since (the link's watch), the Doppler
     * on hold.
     */
    data class Poste(val nom: String, val dialHz: Long?, val dialMs: Long, val mode: String?, val modeMs: Long,
                     val muetDepuisMs: Long? = null, val maintien: Boolean = false)

    /** A transmitter of the satellite followed (SatNOGS): its number in the list, its words, its downlink, its mode. */
    data class Voie(val index: Int, val description: String, val basHz: Long, val hautHz: Long?, val mode: String) {
        val lineaire: Boolean get() = hautHz != null && hautHz != basHz
    }

    /** The satellite followed: elevation now, its next pass, its transmitters and the one chosen. */
    data class Ciel(val satellite: String, val catnum: Int, val elevationDeg: Double?, val aosMs: Long?,
                    val voies: List<Voie> = emptyList(), val voieChoisie: Int = 0, val sousHorizonAussi: Boolean = false)

    /** The SDR receiver: there, running, where it listens (sky Hz), its error. */
    data class Sdr(val actif: Boolean, val cielHz: Long, val mode: String = "", val erreur: String = "")

    /** What every sound decoder shares: the input, whether the SDR feeds it, the rig, the sky. */
    data class Contexte(val t: Long, val entree: Entree?, val parSdr: Boolean, val sdr: Sdr?, val poste: Poste?, val ciel: Ciel?)

    // ------------------------------------------------------------ words

    private val FR = Locale.FRANCE

    /** « 145,800 », « 10 489,550 » (MHz). */
    fun mhz(hz: Long): String = String.format(FR, "%,.3f", hz / 1e6).replace(' ', ' ').replace(' ', ' ')

    fun ilYa(ms: Long): String = GardeLiaisonCat.ilYa(ms)
    fun duree(ms: Long): String = GardeLiaisonCat.duree(ms)

    private fun db(x: Double) = String.format(FR, "%.1f", x).replace("-", "−")
    private fun dbfs(x: Double) = db(x) + " dBFS"

    /** « lu il y a 3 s », or « lu à l'instant ». */
    private fun lu(t: Long, ms: Long) = if (ms <= 0) "" else "lu " + ilYa(t - ms)

    /** Dial readings older than this are shown as old, never as the present. */
    const val VIEUX_MS = 60_000L
    /** No block for this long: the input gives nothing at all. */
    const val MORT_MS = 3_000L
    /** Under this mean level over the window: silence (a squelch shut, the AF gain down). */
    const val SILENCE_DBFS = -60.0
    /** A peak at or over this: clipping. */
    const val SATURE_DBFS = -0.5

    /** The mode a function hears in, from the transmitter's words: FM unless linear, data or CW. */
    fun modeAttendu(v: Voie): String {
        val m = v.mode.uppercase()
        return when {
            ModeRadio.surFm(m, null) -> "FM"
            m.contains("CW") -> "CW"
            m.contains("LSB") -> "LSB"
            m.isBlank() && !v.lineaire -> "FM"
            else -> "USB"
        }
    }

    /** Does the rig's mode ("USB-D", "FM") match [attendu] (data variants count as their base)? */
    fun memeMode(lu: String, attendu: String): Boolean {
        val b = lu.uppercase().trim().removeSuffix("-D").let { if (it == "NFM" || it == "WFM") "FM" else it }
        return b == attendu.uppercase()
    }

    /** How far the dial may be from [v] and still hear it: the passband (linear), else the Doppler at its frequency and a channel's slack. */
    fun tolerance(v: Voie): Long {
        val doppler = (v.basHz * 7.6 / 299_792.458).toLong()
        return doppler + if (modeAttendu(v) == "FM") 6_000L else 2_000L
    }

    fun surLaVoie(hz: Long, v: Voie): Boolean {
        val lo = minOf(v.basHz, v.hautHz ?: v.basHz); val hi = maxOf(v.basHz, v.hautHz ?: v.basHz)
        val tol = tolerance(v)
        return hz in (lo - tol)..(hi + tol)
    }

    private fun accorder(c: Ciel, v: Voie, p: Poste?): Action =
        if (p?.maintien == true && v.index == c.voieChoisie) Action("api/maintien", mapOf("on" to "0"), "Reprendre le Doppler")
        else Action("api/emetteur", mapOf("i" to v.index.toString()), "Accorder")

    private fun voieTexte(c: Ciel, v: Voie, quoi: String): String =
        "$quoi de ${c.satellite} est sur ${mhz(v.basHz)}" + (v.hautHz?.takeIf { it != v.basHz }?.let { "–" + mhz(it) }.orEmpty()) + " " + modeAttendu(v)

    // ------------------------------------------------------------ the shared chain

    /**
     * The causes every sound decoder shares, most likely first: no input, a
     * dead input, a silent rig, the satellite down, the wrong transmitter,
     * the dial elsewhere, the wrong mode. Null when none applies: the caller
     * then says what the level is.
     */
    private fun chaine(c: Contexte, quoi: String, voie: Voie?, satellite: Boolean): Diagnostic? {
        val t = c.t
        // The source.
        if (c.parSdr) {
            val s = c.sdr ?: return Diagnostic(Gravite.PANNE, "Aucun récepteur SDR : la clé SDR devait donner le son.")
            if (s.erreur.isNotBlank()) return Diagnostic(Gravite.PANNE, "Clé SDR en erreur : ${s.erreur}")
            if (!s.actif) return Diagnostic(Gravite.PANNE, "La clé SDR est arrêtée : plus de son pour les décodeurs (onglet SDR).")
        } else {
            val e = c.entree
            if (e == null || !e.actif) return Diagnostic(Gravite.PANNE, "Aucune carte son en marche : rien n'arrive aux décodeurs (onglet Son, « Source »).")
            if (e.dernierBlocMs <= 0L) return Diagnostic(Gravite.PANNE, "La carte « ${e.nom} » est ouverte mais n'a donné aucun échantillon : périphérique mort, débranché ou pris par un autre logiciel.")
            if (t - e.dernierBlocMs > MORT_MS) return Diagnostic(Gravite.PANNE,
                "La carte « ${e.nom} » ne donne plus aucun échantillon depuis ${duree(t - e.dernierBlocMs)} : périphérique débranché ou pris par un autre logiciel.")
        }
        // The rig, when its sound is what is heard.
        val p = c.poste.takeIf { !c.parSdr }
        p?.muetDepuisMs?.let { return Diagnostic(Gravite.PANNE, "${p.nom} muet depuis ${duree(t - it)} : sa fréquence et son mode ne sont plus connus (câble, alimentation ?).") }
        // The sky.
        val ciel = c.ciel
        if (satellite && ciel != null) {
            val el = ciel.elevationDeg
            if (el != null && el < 0) {
                val aos = ciel.aosMs?.takeIf { it > t }
                return Diagnostic(Gravite.INFO, "${ciel.satellite} est sous l'horizon (" + db(el).replace(",0", "") + "°)" +
                    (aos?.let { " — prochain passage dans " + duree(it - t) } ?: " — pas de passage prévu dans les heures qui viennent") + ".")
            }
        }
        if (ciel != null && voie != null) {
            if (voie.index != ciel.voieChoisie) {
                val choisie = ciel.voies.firstOrNull { it.index == ciel.voieChoisie }
                return Diagnostic(Gravite.ATTENTION, "Émetteur choisi : « ${choisie?.description ?: "?"} » — " + voieTexte(ciel, voie, quoi) +
                    " (« ${voie.description} »).", accorder(ciel, voie, p))
            }
            val s = c.sdr
            if (c.parSdr && s != null && s.cielHz > 0 && !surLaVoie(s.cielHz, voie))
                return Diagnostic(Gravite.ATTENTION, "Clé SDR sur ${mhz(s.cielHz)} MHz — " + voieTexte(ciel, voie, quoi) + ".")
            if (p != null && p.dialHz != null) {
                val vieux = t - p.dialMs > VIEUX_MS
                if (!surLaVoie(p.dialHz, voie)) {
                    val m = p.mode?.let { " $it" }.orEmpty()
                    return Diagnostic(Gravite.ATTENTION, "${p.nom} sur ${mhz(p.dialHz)}$m (${lu(t, p.dialMs)}" +
                        (if (vieux) ", relevé ancien" else "") + ") — " + voieTexte(ciel, voie, quoi) + ".", accorder(ciel, voie, p))
                }
                val attendu = modeAttendu(voie)
                if (p.mode != null && !memeMode(p.mode, attendu))
                    return Diagnostic(Gravite.ATTENTION, "${p.nom} en ${p.mode} (${lu(t, p.modeMs)}) — $quoi de ${ciel.satellite} se reçoit en $attendu.",
                        accorder(ciel, voie, p))
            }
        }
        return null
    }

    /** What the level says when nothing else is wrong: silence (not a fault), clipping, or plain listening. */
    private fun niveau(c: Contexte, rien: String): Diagnostic {
        val e = c.entree
        if (c.parSdr || e == null) return Diagnostic(Gravite.INFO, rien)
        val f = if (e.fenetreMs > 0) " sur ${duree(e.fenetreMs)}" else ""
        val rms = e.rmsDbfs
        val crete = e.creteDbfs
        if (crete != null && crete >= SATURE_DBFS)
            return Diagnostic(Gravite.ATTENTION, "« ${e.nom} » sature (crête ${dbfs(crete)}$f) : baisser le volume (AF) du poste ou le niveau de la carte.")
        if (rms != null && rms < SILENCE_DBFS)
            return Diagnostic(Gravite.INFO, "« ${e.nom} » donne du son mais presque rien (${dbfs(rms)} en moyenne$f) : silencieux (squelch) fermé ou volume (AF) bas — pas une panne.")
        return Diagnostic(Gravite.INFO, rien + (rms?.let { " Niveau ${dbfs(it)}$f." }.orEmpty()))
    }

    private fun dernier(t: Long, ms: Long, quoi: String): String =
        if (ms <= 0L) "Rien $quoi depuis le démarrage." else "Rien $quoi depuis ${duree(t - ms)}."

    // ------------------------------------------------------------ the receivers

    /**
     * Live SSTV. [voie]: the followed satellite's SSTV transmitter (null: none
     * known); [derniereImageMs]: the last picture kept (0: none);
     * [enCours]: the picture arriving's mode and lines, "" when none.
     */
    fun sstv(c: Contexte, actif: Boolean, derniereImageMs: Long, enCours: String, voie: Voie?): Diagnostic {
        if (!actif) return Diagnostic(Gravite.INFO, "Décodage SSTV coupé.")
        if (enCours.isNotBlank()) return Diagnostic(Gravite.OK, "Image en cours : $enCours.")
        if (derniereImageMs > 0 && c.t - derniereImageMs < 120_000L) return Diagnostic(Gravite.OK, "Dernière image ${ilYa(c.t - derniereImageMs)}.")
        chaine(c, "la SSTV", voie, satellite = voie != null)?.let { return it }
        return niveau(c, "À l'écoute. " + dernier(c.t, derniereImageMs, "reçu"))
    }

    /** APRS / packet on the sound card. [voie]: the followed satellite's APRS transmitter (null: terrestrial or none). */
    fun aprs(c: Contexte, actif: Boolean, dernierPaquetMs: Long, voie: Voie?): Diagnostic {
        if (!actif) return Diagnostic(Gravite.INFO, "Décodeur APRS coupé.")
        if (dernierPaquetMs > 0 && c.t - dernierPaquetMs < 120_000L) return Diagnostic(Gravite.OK, "Dernière trame ${ilYa(c.t - dernierPaquetMs)}.")
        chaine(c, "l'APRS", voie, satellite = voie != null)?.let { return it }
        return niveau(c, "À l'écoute. " + dernier(c.t, dernierPaquetMs, "décodé"))
    }

    /** The usual FT8 dials (MHz × 1000), and QO-100's narrow band segment. */
    val FT8_KHZ = listOf(1_840L, 3_573L, 5_357L, 7_074L, 10_136L, 14_074L, 18_100L, 21_074L, 24_915L, 28_074L, 50_313L, 144_174L, 432_174L, 1_296_174L, 10_489_540L)

    fun frequenceFt8(hz: Long): Boolean = FT8_KHZ.any { Math.abs(hz - it * 1000) <= 4_000L } ||
        hz in Qo100.BALISE_BASSE_HZ..Qo100.BALISE_HAUTE_HZ

    /**
     * FT8/FT4. [derniereLigneMs]: the last decode; [creneauxVides]: slots
     * decoded with nothing in a row; [lineaire]: the rig is on a followed
     * linear transponder (FT8 may then be anywhere in its passband).
     */
    fun ft8(c: Contexte, enMarche: Boolean, mode: String, derniereLigneMs: Long, creneauxVides: Int, lineaire: Boolean): Diagnostic {
        val m = mode.ifBlank { "FT8" }
        if (!enMarche) return Diagnostic(Gravite.INFO, "$m à l'arrêt.")
        if (derniereLigneMs > 0 && c.t - derniereLigneMs < 60_000L) return Diagnostic(Gravite.OK, "Dernier décodage ${ilYa(c.t - derniereLigneMs)}.")
        chaine(c, m, null, satellite = false)?.let { return it }
        val p = c.poste.takeIf { !c.parSdr }
        if (p != null) {
            if (p.mode != null && !memeMode(p.mode, "USB"))
                return Diagnostic(Gravite.ATTENTION, "${p.nom} en ${p.mode} (${lu(c.t, p.modeMs)}) — $m se reçoit en USB (USB-D).")
            if (p.dialHz != null && !lineaire && !frequenceFt8(p.dialHz))
                return Diagnostic(Gravite.INFO, "${p.nom} sur ${mhz(p.dialHz)} MHz (${lu(c.t, p.dialMs)}) : pas une fréquence $m habituelle " +
                    "(144,174 · 432,174 · 10 489,540 sur QO-100 · 14,074…).")
        }
        val vides = if (creneauxVides > 0) " ($creneauxVides créneau" + (if (creneauxVides > 1) "x" else "") + " sans rien)" else ""
        return niveau(c, "À l'écoute. " + dernier(c.t, derniereLigneMs, "décodé") .removeSuffix(".") + vides + ".")
    }

    /** The packet decoder's figures (RecepteurPaquets): frames, bad CRCs, the last frame, the level over the floor. */
    data class Paquets(val trames: Int, val crcFaux: Int, val derniereMs: Long, val niveauDb: Double, val plancherDb: Double,
                       val signal: Boolean, val debit: Int)

    /** Telemetry packets from the SDR's I/Q. [voie]: the followed satellite's packet transmitter (null: none known). */
    fun telemesure(t: Long, actif: Boolean, p: Paquets, sdr: Sdr?, ciel: Ciel?, voie: Voie?): Diagnostic {
        if (!actif) return Diagnostic(Gravite.INFO, "Décodeur de paquets coupé.")
        if (p.derniereMs > 0 && t - p.derniereMs < 120_000L) return Diagnostic(Gravite.OK, "Dernière trame ${ilYa(t - p.derniereMs)}.")
        chaine(Contexte(t, null, true, sdr, null, ciel), "la télémesure", voie, satellite = ciel != null)?.let { return it }
        if (ciel != null && voie == null)
            return Diagnostic(Gravite.INFO, "Aucun émetteur de paquets connu pour ${ciel.satellite} (SatNOGS) : choisir le débit à la main.")
        val ecart = p.niveauDb - p.plancherDb
        if ((p.signal || ecart >= 6.0) && p.crcFaux > 0)
            return Diagnostic(Gravite.ATTENTION, "Signal vu (${db(ecart)} dB au-dessus du bruit) mais aucune trame valide (${p.crcFaux} CRC faux) : " +
                "débit (${p.debit} bit/s ?) ou modulation différente.")
        return Diagnostic(Gravite.INFO, "Rien au-dessus du bruit (${db(ecart)} dB). " + dernier(t, p.derniereMs, "décodé"))
    }

    /**
     * DATV: the receiver's state and the facts around it. [noeudMuetMs]:
     * since when the node no longer answers (null: it does, or no node);
     * [balise]: the last MER read on the beacon and when; [avisBalise]: the
     * beacons' verdict (its words, where the fault is, since when).
     */
    data class Datv(
        val actif: Boolean, val parNoeud: Boolean, val etat: Int, val libelle: String, val merDb: Double?, val depuisMs: Long,
        val verrouDepuisMs: Long, val cielKhz: Long, val srKs: Int, val lnb: String, val erreur: String,
        val noeudMuetMs: Long? = null, val balise: Pair<Double, Long>? = null, val avisBalise: Triple<String, String, Long>? = null,
    )

    /** QO-100's wide band: horizontal polarisation (18 V on a universal LNB). */
    const val LARGE_BAS_KHZ = 10_491_000L
    const val LARGE_HAUT_KHZ = 10_499_000L

    fun datv(t: Long, d: Datv): Diagnostic {
        d.noeudMuetMs?.let { return Diagnostic(Gravite.PANNE, "Le node ne répond plus depuis ${duree(t - it)} : réseau, Raspberry Pi ou SatMe-Node arrêté ?") }
        if (!d.actif) return Diagnostic(Gravite.INFO, if (d.erreur.isNotBlank()) "Réception arrêtée : ${d.erreur}" else "Réception DATV arrêtée.")
        val ici = String.format(FR, "%s MHz, %d kS/s", mhz(d.cielKhz * 1000), d.srKs)
        if (d.etat >= 3) {
            val m = d.merDb?.let { " MER ${db(it)} dB" }.orEmpty()
            val ref = d.balise?.let { (mer, ms) -> " (balise : ${db(mer)} dB, ${ilYa(t - ms)})" }.orEmpty()
            return Diagnostic(Gravite.OK, "${d.libelle.replaceFirstChar { it.uppercase() }} sur $ici depuis ${duree(t - d.verrouDepuisMs.coerceAtLeast(d.depuisMs))}.$m$ref".trim())
        }
        val cherche = t - d.depuisMs
        if (cherche < 10_000L) return Diagnostic(Gravite.INFO, "Recherche sur $ici…")
        val pas = "Pas d'accroche sur $ici depuis ${duree(cherche)}"
        val large = d.cielKhz in LARGE_BAS_KHZ..LARGE_HAUT_KHZ
        if (large && d.lnb == "v") return Diagnostic(Gravite.ATTENTION,
            "$pas : le LNB est alimenté en 13 V (polarisation verticale), la bande large de QO-100 est en horizontale (18 V).")
        d.avisBalise?.let { (texte, cause, ms) ->
            if (cause == "source" || cause == "ici") return Diagnostic(if (cause == "ici") Gravite.ATTENTION else Gravite.INFO, "$pas. $texte (${ilYa(t - ms)}).")
        }
        d.balise?.let { (mer, ms) ->
            return Diagnostic(Gravite.INFO, "$pas. La balise était reçue (MER ${db(mer)} dB, ${ilYa(t - ms)}) : la station n'émet sans doute pas, ou plus faible.")
        }
        return Diagnostic(Gravite.INFO, "$pas. Essayer la balise (10 491,500 MHz, 1500 kS/s) pour savoir si la réception marche.")
    }

    /**
     * QO-100's narrow band from the SDR: the dongle running, on the
     * transponder, the beacon last measured ([baliseDb] over the noise, at
     * [baliseMs]; null: never).
     */
    fun qo100(t: Long, sdr: Sdr?, surTranspondeur: Boolean, baliseDb: Double?, baliseMs: Long): Diagnostic {
        val s = sdr ?: return Diagnostic(Gravite.INFO, "Pas de récepteur SDR : le spectre de QO-100 vient de la clé SDR.")
        if (s.erreur.isNotBlank()) return Diagnostic(Gravite.PANNE, "Clé SDR en erreur : ${s.erreur}")
        if (!s.actif) return Diagnostic(Gravite.INFO, "La clé SDR est arrêtée : pas de spectre (onglet QO-100, Écouter).")
        if (!surTranspondeur) return Diagnostic(Gravite.INFO, "La clé SDR écoute ${mhz(s.cielHz)} MHz, pas QO-100.")
        if (baliseDb == null || baliseMs <= 0L) return Diagnostic(Gravite.ATTENTION,
            "Balise de QO-100 jamais vue : alimentation du LNB (bias-T), pointage, ou oscillateur du LNB hors de la fenêtre de recherche.")
        val age = t - baliseMs
        if (age > 30_000L) return Diagnostic(Gravite.ATTENTION, "Balise non vue depuis ${duree(age)} (dernière à ${db(baliseDb)} dB) : " +
            "alimentation du LNB, pointage, pluie, ou l'oscillateur du LNB a glissé.")
        if (baliseDb < BALISE_FAIBLE_DB) return Diagnostic(Gravite.INFO, "Balise faible : ${db(baliseDb)} dB au-dessus du bruit (${ilYa(age)}) — pointage, pluie.")
        return Diagnostic(Gravite.OK, "Balise vue à ${db(baliseDb)} dB au-dessus du bruit (${ilYa(age)}).")
    }

    /** Under this (dB over the noise) the narrow band beacon is weak. */
    const val BALISE_FAIBLE_DB = 10.0

    /** The wide band spectrum (the ground station's, over the internet): on, fresh, or its own words. */
    fun large(t: Long, on: Boolean, frais: Boolean, etat: String, dernierMs: Long): Diagnostic {
        if (!on) return Diagnostic(Gravite.INFO, "Spectre de la bande large coupé.")
        if (frais) return Diagnostic(Gravite.OK, "Spectre reçu" + (if (dernierMs > 0) " ${ilYa(t - dernierMs)}" else "") + ".")
        val e = etat.ifBlank { "pas de spectre" }
        return Diagnostic(if (e.startsWith("en attente")) Gravite.INFO else Gravite.ATTENTION,
            "Spectre de la bande large : $e" + (if (dernierMs > 0) " — dernier reçu ${ilYa(t - dernierMs)}" else "") + ".")
    }
}
