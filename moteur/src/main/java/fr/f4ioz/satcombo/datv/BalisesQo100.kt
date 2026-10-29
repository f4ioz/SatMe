/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.datv

import fr.f4ioz.satcombo.domain.Qo100
import org.json.JSONArray
import org.json.JSONObject

/**
 * QO-100's beacons watched: the wide band DATV beacon (10 491,5 MHz,
 * 1,5 MS/s) as the ground station's public spectrum shows it and, when the
 * receiver is on it, as it is received here (lock, MER, the stream's health
 * PID by PID, pictures made); the narrow band beacons as the SDR hears them.
 *
 * The point is to tell apart what goes wrong at the source (the beacon off,
 * its encoder sending a damaged stream: it happens) from what goes wrong
 * here (pointing, rain, LNB, a PC that cannot keep up), and to say it plainly.
 */
object BalisesQo100 {
    /** The wide band beacon's symbol rate (kS/s); its frequency is [Datv.BALISE_KHZ]. */
    const val SR_KS = 1500

    /** The verdict on a beacon. */
    enum class Statut(val cle: String, val libelle: String, val gravite: Int) {
        OK("ok", "ok", 0),
        INCONNU("inconnu", "pas de mesure", 0),
        FAIBLE("faible", "signal faible", 1),
        PERTES_ICI("pertes-ici", "paquets perdus ici", 1),
        FLUX_ABIME("flux-abime", "flux abîmé à la source", 2),
        ABSENTE("absente", "absente", 2);
        companion object { fun de(s: String?) = values().firstOrNull { it.cle == s } ?: INCONNU }
    }

    /** Where the fault is. */
    enum class Cause(val cle: String) { AUCUNE(""), SOURCE("source"), ICI("ici"), INCONNUE("inconnue") }

    /** A verdict, its words, and the figures it rests on. */
    data class Avis(val statut: Statut, val cause: Cause, val texte: String)

    /** The narrow band beacons. */
    data class Etroite(val cle: String, val nom: String, val hz: Long)
    val ETROITES = listOf(
        Etroite("basse", "balise basse (CW)", Qo100.BALISE_BASSE_HZ),
        Etroite("mediane", "balise médiane (BPSK 400 bit/s)", Qo100.BALISE_MEDIANE_HZ),
        Etroite("haute", "balise haute", Qo100.BALISE_HAUTE_HZ))

    /** Above this (dB over the floor), a narrow band beacon is heard well; under [ETROITE_PRESENTE_DB], not at all. */
    const val ETROITE_BONNE_DB = 12.0
    const val ETROITE_PRESENTE_DB = 6.0
    /** Over this margin (dB) our reception is good enough to blame nothing here. */
    const val MARGE_BONNE_DB = 1.0
    /** The share of a video/sound PID's packets missing that makes a picture break up. */
    const val TAUX_ABIME = 0.002
    /** Seconds on the beacon before the receiver's figures count. */
    const val ESSAI_MS = 10_000L

    /**
     * The stream's health over a window (deltas): [paquetsEs] and [ccEs] the video and sound PIDs' packets and
     * continuity errors, [paquetsAutres] and [ccAutres] the tables' and the rest's (nulls aside, not checked),
     * [tei] transport errors, [videoMin] decoder errors per minute, [imagesParS] pictures made per second
     * (null: none made here).
     */
    data class Flux(val paquetsEs: Long = 0, val ccEs: Long = 0, val paquetsAutres: Long = 0, val ccAutres: Long = 0, val tei: Long = 0,
                    val videoMin: Double = 0.0, val imagesParS: Double? = null) {
        val tauxEs: Double get() = if (paquetsEs > 0) ccEs.toDouble() / paquetsEs else 0.0
        val tauxAutres: Double get() = if (paquetsAutres > 0) ccAutres.toDouble() / paquetsAutres else 0.0
    }

    /** Our receiver on the beacon: for [depuisMs], locked or not, its MER and margin, its FEC failing, packets received over expected. */
    data class Reception(val depuisMs: Long, val verrouille: Boolean, val merDb: Double?, val margeDb: Double?, val fecEchec: Boolean?,
                         val ratio: Double?, val flux: Flux)

    /**
     * What is known of the wide band beacon: [spectre] seen on the ground station's fresh spectrum (null: no
     * spectrum), [spectreDb] its level over the noise there; [reception] ours when on it; [etroiteDb] the middle
     * narrow band beacon over the floor here (null: not heard by an SDR now) — our dish and LNB working.
     */
    data class Large(val spectre: Boolean?, val spectreDb: Double?, val reception: Reception?, val etroiteDb: Double?)

    private fun f1(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f", x)
    private fun pct(x: Double) = String.format(java.util.Locale.FRANCE, "%.1f %%", x * 100)

    /** The wide band beacon's verdict. */
    fun large(o: Large): Avis {
        val r = o.reception?.takeIf { it.depuisMs >= ESSAI_MS }
        val sol = o.spectre
        val etroiteBonne = o.etroiteDb?.let { it >= ETROITE_BONNE_DB }
        if (r != null && !r.verrouille) return when {
            sol == false -> Avis(Statut.ABSENTE, Cause.SOURCE, "Balise absente : la station sol ne la voit pas non plus — défaut à la source, pas chez vous")
            sol == true && etroiteBonne == true -> Avis(Statut.FAIBLE, Cause.ICI,
                "Pas d’accroche alors que la station sol voit la balise et que la bande étroite arrive bien : chez vous (oscillateur du LNB, entrée, câble, récepteur)")
            sol == true -> Avis(Statut.FAIBLE, Cause.ICI, "Pas d’accroche alors que la station sol voit la balise : chez vous (pointage, pluie, LNB)")
            etroiteBonne == true -> Avis(Statut.ABSENTE, Cause.INCONNUE,
                "Pas d’accroche, mais la bande étroite arrive bien : sans doute la balise (le spectre de la station sol le dirait)")
            else -> Avis(Statut.FAIBLE, Cause.INCONNUE, "Pas d’accroche sur la balise")
        }
        if (r != null) {
            val f = r.flux
            val m = r.margeDb
            val mer = r.merDb?.let { " (MER " + f1(it) + " dB" + (m?.let { x -> ", marge " + f1(x) + " dB" } ?: "") + ")" }.orEmpty()
            val abime = (f.ccEs >= 3 && f.tauxEs >= TAUX_ABIME) || f.videoMin > 6.0
            val signalJuste = m != null && m < MARGE_BONNE_DB
            if (abime) {
                // Whole at our end: no transport error, the FEC clean, margin enough, every packet the sender's clock
                // says it sent here, the tables clean — the holes were in the stream before it left.
                val entier = f.tei == 0L && r.fecEchec != true && !signalJuste && m != null && (r.ratio == null || r.ratio >= 0.97) &&
                    (f.ccAutres == 0L || f.tauxAutres * 4 < f.tauxEs)
                return when {
                    entier -> Avis(Statut.FLUX_ABIME, Cause.SOURCE,
                        "Flux abîmé à la source (le codeur de la balise) — pas chez vous : votre réception est bonne$mer, sans erreur de transport ni de FEC ; " +
                            "seuls la vidéo et le son ont des trous" + (if (f.paquetsEs > 0) " (" + pct(f.tauxEs) + " des paquets)" else ""))
                    signalJuste || r.fecEchec == true || f.tei > 0 -> Avis(Statut.FAIBLE, Cause.ICI,
                        "Signal juste$mer : des trames sont perdues au démodulateur, chez vous (pointage, pluie, LNB)")
                    else -> Avis(Statut.PERTES_ICI, Cause.ICI,
                        "Paquets perdus sur toutes les voies avec un bon signal$mer : le PC ou le réseau (node) n’arrive pas à suivre — la balise n’y est pour rien")
                }
            }
            if (signalJuste) return Avis(Statut.FAIBLE, Cause.ICI, "Image correcte, mais signal juste$mer : chez vous (pointage, pluie, LNB)")
            if (f.imagesParS != null && f.imagesParS < 0.1 && r.depuisMs >= 2 * ESSAI_MS) return Avis(Statut.FLUX_ABIME, Cause.SOURCE,
                "Flux reçu entier$mer mais aucune image décodable : le codeur de la balise — pas chez vous")
            return Avis(Statut.OK, Cause.AUCUNE, "Balise reçue$mer, image correcte")
        }
        return when (sol) {
            false -> Avis(Statut.ABSENTE, Cause.SOURCE, "Balise absente du spectre de la station sol — défaut à la source, pas chez vous")
            true -> Avis(Statut.OK, Cause.AUCUNE, "Présente sur le spectre de la station sol" + (o.spectreDb?.let { " (" + f1(it) + " dB au-dessus du bruit)" }.orEmpty()))
            null -> Avis(Statut.INCONNU, Cause.AUCUNE, "Pas de mesure : ni spectre de la station sol, ni récepteur sur la balise")
        }
    }

    /** A narrow band beacon heard (dB over the floor, Hz off its frequency), or null when not heard. */
    data class Ecoute(val db: Double, val ecartHz: Double)

    /**
     * The narrow band beacons' verdict: [l] each one in the SDR's view and its measure (null: not heard; a beacon
     * out of the view has no key), [sdr] an SDR on the transponder (else nothing is known). One missing while the
     * others are heard: that beacon is off (source); all missing: nothing comes in here.
     */
    fun etroites(sdr: Boolean, l: Map<String, Ecoute?>): Avis {
        if (!sdr) return Avis(Statut.INCONNU, Cause.AUCUNE, "Pas de SDR sur le transpondeur bande étroite")
        val vues = ETROITES.filter { it.cle in l }
        if (vues.isEmpty()) return Avis(Statut.INCONNU, Cause.AUCUNE, "Aucune balise bande étroite dans la vue du SDR")
        val entendues = vues.filter { l[it.cle] != null }
        val muettes = vues.filter { l[it.cle] == null }
        return when {
            entendues.isEmpty() -> Avis(Statut.ABSENTE, Cause.ICI, "Aucune balise bande étroite entendue : vérifier le SDR, l’oscillateur, la parabole")
            muettes.isNotEmpty() -> Avis(Statut.ABSENTE, Cause.SOURCE, muettes.joinToString(", ") { it.nom }.replaceFirstChar { it.uppercase() } +
                (if (muettes.size > 1) " muettes" else " muette") + " alors que " + (if (entendues.size > 1) "les autres arrivent" else "l’autre arrive") +
                " — défaut à la source, pas chez vous")
            entendues.all { (l[it.cle]?.db ?: 0.0) < ETROITE_BONNE_DB } -> Avis(Statut.FAIBLE, Cause.ICI, "Balises bande étroite faibles : pointage, pluie, LNB")
            else -> Avis(Statut.OK, Cause.AUCUNE, "Balises bande étroite entendues (" + entendues.size + "/" + vues.size + ")")
        }
    }

    /**
     * The narrow band beacons in a panorama ([db] per bin, its axis: [centreHz] the sky at its middle, [largeurHz]
     * signed): each one in view (5 kHz inside the edges) measured — the middle one first within 20 kHz, the others
     * within 3 kHz of where its offset puts them. Out of the view: no key.
     */
    fun ecoutes(db: FloatArray, centreHz: Double, largeurHz: Double): Map<String, Ecoute?> {
        val demi = Math.abs(largeurHz) / 2 - 5_000.0
        fun vue(hz: Long) = Math.abs(hz - centreHz) <= demi
        val m = ETROITES.first { it.cle == "mediane" }
        val mesureM = if (vue(m.hz)) fr.f4ioz.satcombo.domain.MesureBalise.mesurerCentre(db, centreHz, largeurHz, m.hz.toDouble(), 20_000.0) else null
        val decalage = mesureM?.ecartHz ?: 0.0
        val out = LinkedHashMap<String, Ecoute?>()
        for (b in ETROITES) {
            if (!vue(b.hz)) continue
            val me = if (b === m) mesureM else fr.f4ioz.satcombo.domain.MesureBalise.mesurerCentre(db, centreHz, largeurHz, b.hz + decalage,
                if (mesureM != null) 3_000.0 else 20_000.0)
            out[b.cle] = me?.let { Ecoute(it.rapportDb.toDouble(), if (b === m) it.ecartHz else it.ecartHz + decalage) }
        }
        return out
    }

    /**
     * The stream's health between two of [SuiviQualite]'s seconds (their `pids` arrays, cumulative counts): video and
     * sound PIDs apart from the rest; a PID whose count went down (a new reception) counted from zero.
     */
    fun flux(avant: JSONObject?, apres: JSONObject, imagesParS: Double?): Flux {
        fun pids(o: JSONObject?): Map<Int, JSONObject> {
            val a = o?.optJSONArray("pids") ?: return emptyMap()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associateBy { it.optInt("pid") }
        }
        val p0 = pids(avant); val p1 = pids(apres)
        var pe = 0L; var ce = 0L; var pa = 0L; var ca = 0L
        for ((pid, o) in p1) {
            if (pid == 0x1FFF) continue
            val a = p0[pid]
            val dp = o.optLong("paquets") - (a?.optLong("paquets") ?: 0L)
            val dc = o.optLong("cc") - (a?.optLong("cc") ?: 0L)
            val (p, c) = if (dp < 0 || dc < 0) o.optLong("paquets") to o.optLong("cc") else dp to dc
            if (o.optString("role") in ROLES_ES) { pe += p; ce += c } else { pa += p; ca += c }
        }
        val tei = (apres.optLong("totalTei") - (avant?.optLong("totalTei") ?: 0L)).let { if (it < 0) apres.optLong("totalTei") else it }
        return Flux(pe, ce, pa, ca, tei, apres.optDouble("videoMin", 0.0).takeIf { !it.isNaN() } ?: 0.0, imagesParS)
    }

    /** What [AnalyseTs.role] calls a video or sound stream. */
    val ROLES_ES = setOf("MPEG-2", "H.264", "H.265", "MPEG audio", "AAC", "AAC LATM", "AC-3")
}

/**
 * Debounced verdicts and their history: a new verdict counts once it held [confirmeMs]; each change kept
 * (an event), one point every [pasMs] (the worst verdict and the figures seen), [joursMax] days at most.
 */
class HistoriqueBalises(private val confirmeMs: Long = 20_000L, private val pasMs: Long = 5 * 60_000L, var jours: Int = 7) {
    data class Evenement(val t: Long, val quoi: String, val statut: BalisesQo100.Statut, val cause: BalisesQo100.Cause, val texte: String)
    /** [mer] best MER, [sol] the beacon's level on the ground station's spectrum, [etroite] the middle narrow beacon's, over the step. */
    data class Point(val t: Long, val large: BalisesQo100.Statut, val etroites: BalisesQo100.Statut, val mer: Double?, val sol: Double?, val etroite: Double?)

    private class Suivi { var courant: BalisesQo100.Avis? = null; var candidat: BalisesQo100.Avis? = null; var candidatMs = 0L; var depuisMs = 0L }
    private val suivis = mapOf("large" to Suivi(), "etroites" to Suivi())
    private val evenements = ArrayDeque<Evenement>()
    private val points = ArrayDeque<Point>()
    private var pasEnCours = -1L
    private var pire = mapOf("large" to BalisesQo100.Statut.INCONNU, "etroites" to BalisesQo100.Statut.INCONNU)
    private var merMax: Double? = null
    private val sols = ArrayList<Double>()
    private val etroitesDb = ArrayList<Double>()

    /** The verdict in force for [quoi] ("large", "etroites"), and since when. */
    @Synchronized fun courant(quoi: String): Pair<BalisesQo100.Avis, Long>? = suivis[quoi]?.let { s -> s.courant?.let { it to s.depuisMs } }

    /**
     * One second's verdict for [quoi]: returns the event when it becomes the one in force (held [confirmeMs], or
     * the first one). The words may change without an event (same verdict).
     */
    @Synchronized
    fun avis(t: Long, quoi: String, a: BalisesQo100.Avis): Evenement? {
        val s = suivis[quoi] ?: return null
        val c = s.courant
        if (c != null && c.statut == a.statut && c.cause == a.cause) { s.courant = a; s.candidat = null; return null }
        if (s.candidat?.statut != a.statut || s.candidat?.cause != a.cause) { s.candidat = a; s.candidatMs = t }
        else s.candidat = a
        // The first verdict at once (nothing to debounce against), an unknown one at once too.
        if (c != null && c.statut != BalisesQo100.Statut.INCONNU && t - s.candidatMs < confirmeMs) return null
        s.courant = a; s.candidat = null; s.depuisMs = t
        // Nothing known from the start: not a change worth keeping.
        if (c == null && a.statut == BalisesQo100.Statut.INCONNU) return null
        val e = Evenement(t, quoi, a.statut, a.cause, a.texte)
        evenements.addLast(e)
        oublie(t)
        return e
    }

    /** One second's figures for the points. */
    @Synchronized
    fun mesure(t: Long, mer: Double?, sol: Double?, etroite: Double?) {
        val pas = t / pasMs
        if (pas != pasEnCours) {
            if (pasEnCours >= 0) {
                points.addLast(Point(pasEnCours * pasMs, pire.getValue("large"), pire.getValue("etroites"), merMax,
                    sols.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }, etroitesDb.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }))
                oublie(t)
            }
            pasEnCours = pas; merMax = null; sols.clear(); etroitesDb.clear()
            pire = suivis.mapValues { it.value.courant?.statut ?: BalisesQo100.Statut.INCONNU }
        }
        pire = suivis.mapValues { (k, s) -> listOfNotNull(pire[k], s.courant?.statut).maxByOrNull { it.gravite * 10 + if (it == BalisesQo100.Statut.INCONNU) 0 else 1 }!! }
        if (mer != null) merMax = maxOf(merMax ?: mer, mer)
        if (sol != null) sols += sol
        if (etroite != null) etroitesDb += etroite
    }

    private fun oublie(t: Long) {
        val limite = t - jours.coerceIn(1, 7) * 86_400_000L
        while (evenements.isNotEmpty() && (evenements.first().t < limite || evenements.size > 500)) evenements.removeFirst()
        while (points.isNotEmpty() && points.first().t < limite) points.removeFirst()
    }

    @Synchronized fun evenements(): List<Evenement> = evenements.toList()
    @Synchronized fun points(): List<Point> = points.toList()

    /** As kept on disk (JSON). */
    @Synchronized
    fun texte(): String = JSONObject()
        .put("e", JSONArray(evenements.map { JSONArray().put(it.t).put(it.quoi).put(it.statut.cle).put(it.cause.cle).put(it.texte) }))
        .put("p", JSONArray(points.map { p -> JSONArray().put(p.t).put(p.large.cle).put(p.etroites.cle).put(p.mer ?: JSONObject.NULL)
            .put(p.sol ?: JSONObject.NULL).put(p.etroite ?: JSONObject.NULL) }))
        .toString()

    /** Taken back from [texte], what is still within [jours]. */
    @Synchronized
    fun reprends(s: String?, t: Long) {
        val o = runCatching { JSONObject(s ?: return) }.getOrNull() ?: return
        evenements.clear(); points.clear()
        val e = o.optJSONArray("e") ?: JSONArray()
        for (i in 0 until e.length()) {
            val a = e.optJSONArray(i) ?: continue
            evenements += Evenement(a.optLong(0), a.optString(1), BalisesQo100.Statut.de(a.optString(2)),
                BalisesQo100.Cause.values().firstOrNull { it.cle == a.optString(3) } ?: BalisesQo100.Cause.AUCUNE, a.optString(4))
        }
        val p = o.optJSONArray("p") ?: JSONArray()
        fun d(a: JSONArray, i: Int) = if (a.isNull(i)) null else a.optDouble(i).takeIf { !it.isNaN() }
        for (i in 0 until p.length()) {
            val a = p.optJSONArray(i) ?: continue
            points += Point(a.optLong(0), BalisesQo100.Statut.de(a.optString(1)), BalisesQo100.Statut.de(a.optString(2)), d(a, 3), d(a, 4), d(a, 5))
        }
        oublie(t)
    }
}
